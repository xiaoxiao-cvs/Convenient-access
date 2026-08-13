package com.shinoyuki.accesshub.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 线路延迟探针: 一个极简的 WebSocket 回显服务。
 *
 * 玩家自查页面对每条线路的探针地址各开一条 WebSocket, 反复发小消息并计时, 得到该线路的
 * 往返延迟中位数与抖动。探针必须部署在 Minecraft 所在的这台机器上并经由与游戏流量完全相同的
 * frp 隧道暴露 — 若把探针放在 frps 那一侧, 测到的只是"玩家到节点"的半程, 隧道抖动与家宽状况
 * 一概测不出来, 数据反而会误导玩家选错线路。
 *
 * 协议上刻意只做回显: 收到什么文本就原样发回, 不解析 JSON 也不追加服务端时间戳。客户端把发送
 * 时刻编进消息里, 收回来一减即是往返耗时。服务端处理时间趋近于零, 测出来的才是纯网络往返。
 *
 * 只实现 RFC 6455 中探针用得到的部分: 升级握手、单帧文本、ping/pong、close。分片帧、二进制帧、
 * 扩展协商一律拒绝 — 浏览器发这么小的消息不会分片, 支持它们只会增加无人走到的代码路径。
 *
 * 线程模型: 独立的单 selector 事件循环线程, 与 {@link NodeRelayServer} 互不影响。
 */
public final class ProbeServer {

    private static final Logger logger = LoggerFactory.getLogger(ProbeServer.class);

    /** RFC 6455 规定的握手魔术串, 与客户端 key 拼接后取 SHA-1 即为 Sec-WebSocket-Accept。 */
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OPCODE_CONTINUATION = 0x0;
    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_CLOSE = 0x8;
    private static final int OPCODE_PING = 0x9;
    private static final int OPCODE_PONG = 0xA;

    /** 探针消息只有几十字节, 给足冗余后仍远小于任何有意义的攻击载荷。 */
    private static final int MAX_FRAME_PAYLOAD = 1024;
    private static final int MAX_HANDSHAKE_BYTES = 8 * 1024;
    private static final int BUFFER_SIZE = 8 * 1024;

    /** 连上却迟迟不完成 WebSocket 握手的连接会被回收。 */
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 10_000L;
    private static final long SELECT_TIMEOUT_MILLIS = 1_000L;

    private static final class ProbeConnection {
        final SocketChannel channel;
        final long createdAtMillis;
        final ByteBuffer inbound = ByteBuffer.allocate(BUFFER_SIZE);
        final ByteBuffer outbound = ByteBuffer.allocate(BUFFER_SIZE);

        SelectionKey key;
        boolean upgraded;
        boolean closing;
        boolean closed;

        ProbeConnection(SocketChannel channel, long createdAtMillis) {
            this.channel = channel;
            this.createdAtMillis = createdAtMillis;
        }
    }

    private final String bindHost;
    private final int port;

    private final Set<ProbeConnection> connections = new HashSet<>();

    private ServerSocketChannel listener;
    private Selector selector;
    private Thread eventLoop;
    private volatile boolean running;

    public ProbeServer(String bindHost, int port) {
        this.bindHost = bindHost;
        this.port = port;
    }

    public void start() throws IOException {
        selector = Selector.open();
        try {
            listener = ServerSocketChannel.open();
            listener.configureBlocking(false);
            listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            listener.bind(new InetSocketAddress(bindHost, port));
            listener.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException e) {
            closeQuietly(listener);
            closeQuietly(selector);
            listener = null;
            selector = null;
            throw e;
        }

        running = true;
        eventLoop = new Thread(this::runEventLoop, "AccessHub-Probe");
        eventLoop.setDaemon(true);
        eventLoop.start();
        logger.info("延迟探针已监听 {}:{} (WebSocket 回显)", bindHost, port);
    }

    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
        if (eventLoop != null) {
            try {
                eventLoop.join(3_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeQuietly(listener);
        for (ProbeConnection connection : new ArrayList<>(connections)) {
            closeConnection(connection);
        }
        connections.clear();
        closeQuietly(selector);
        logger.info("延迟探针已停止");
    }

    public boolean isRunning() {
        return running;
    }

    private void runEventLoop() {
        long lastSweep = System.currentTimeMillis();
        while (running) {
            try {
                selector.select(SELECT_TIMEOUT_MILLIS);
                if (!running) {
                    break;
                }
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    dispatch(key);
                }

                long now = System.currentTimeMillis();
                if (now - lastSweep >= SELECT_TIMEOUT_MILLIS) {
                    sweepStalledHandshakes(now);
                    lastSweep = now;
                }
            } catch (IOException e) {
                if (running) {
                    logger.error("探针事件循环 select 失败", e);
                }
            } catch (RuntimeException e) {
                logger.error("探针事件循环异常", e);
            }
        }
    }

    private void dispatch(SelectionKey key) {
        if (!key.isValid()) {
            return;
        }
        ProbeConnection connection = key.attachment() instanceof ProbeConnection probe ? probe : null;
        try {
            if (key.isAcceptable()) {
                accept();
                return;
            }
            if (connection == null) {
                return;
            }
            if (key.isReadable()) {
                readFrom(connection);
            }
            if (key.isValid() && key.isWritable()) {
                flush(connection);
            }
            updateInterest(connection);
            if (connection.closing && connection.outbound.position() == 0) {
                closeConnection(connection);
            }
        } catch (IOException | RuntimeException e) {
            if (connection != null) {
                logger.debug("探针连接异常关闭: {}", e.toString());
                closeConnection(connection);
            } else {
                logger.warn("探针 accept 失败", e);
            }
        }
    }

    private void accept() throws IOException {
        SocketChannel channel = listener.accept();
        if (channel == null) {
            return;
        }
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        ProbeConnection connection = new ProbeConnection(channel, System.currentTimeMillis());
        connection.key = channel.register(selector, SelectionKey.OP_READ, connection);
        connections.add(connection);
    }

    private void readFrom(ProbeConnection connection) throws IOException {
        int count = connection.channel.read(connection.inbound);
        if (count < 0) {
            closeConnection(connection);
            return;
        }
        if (!connection.upgraded) {
            if (!tryUpgrade(connection)) {
                return;
            }
        }
        while (!connection.closing && processFrame(connection)) {
            // 一次可读事件里可能到达多个帧, 全部处理完再返回
        }
        flush(connection);
    }

    /**
     * 处理 WebSocket 升级握手。
     *
     * @return true 表示握手已完成, 缓冲区里剩下的字节可以按帧解析
     */
    private boolean tryUpgrade(ProbeConnection connection) throws IOException {
        ByteBuffer buffer = connection.inbound;
        int headerEnd = indexOfHeaderEnd(buffer);
        if (headerEnd < 0) {
            if (buffer.position() > MAX_HANDSHAKE_BYTES) {
                throw new IOException("HTTP 请求头超过 " + MAX_HANDSHAKE_BYTES + " 字节仍未结束");
            }
            if (!buffer.hasRemaining()) {
                throw new IOException("HTTP 请求头填满缓冲区仍未结束");
            }
            return false;
        }

        String request = new String(buffer.array(), 0, headerEnd, StandardCharsets.ISO_8859_1);
        String clientKey = findHeader(request, "sec-websocket-key");
        if (clientKey == null) {
            throw new IOException("不是 WebSocket 升级请求: 缺少 Sec-WebSocket-Key");
        }

        // 消费掉握手报文, 之后到达的都是数据帧
        buffer.flip();
        buffer.position(headerEnd);
        buffer.compact();

        byte[] response = ("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey(clientKey) + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        if (connection.outbound.remaining() < response.length) {
            throw new IOException("握手响应写入缓冲区失败");
        }
        connection.outbound.put(response);
        connection.upgraded = true;
        return true;
    }

    private static String acceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((clientKey + WEBSOCKET_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 是 JDK 必备算法, 走到这里说明运行环境被裁剪过
            throw new IllegalStateException("运行环境缺少 SHA-1 实现, 无法完成 WebSocket 握手", e);
        }
    }

    /** 返回请求头结束位置 (含结尾的空行), 未结束返回 -1。 */
    private static int indexOfHeaderEnd(ByteBuffer buffer) {
        byte[] data = buffer.array();
        int length = buffer.position();
        for (int i = 3; i < length; i++) {
            if (data[i] == '\n' && data[i - 1] == '\r' && data[i - 2] == '\n' && data[i - 3] == '\r') {
                return i + 1;
            }
        }
        return -1;
    }

    /** 大小写不敏感地取一个请求头的值。 */
    private static String findHeader(String request, String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (String line : request.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals(lowerName)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /**
     * 尝试解析并处理一个数据帧。
     *
     * @return true 表示消费了一个完整帧, 调用方可继续尝试下一个
     */
    private boolean processFrame(ProbeConnection connection) throws IOException {
        ByteBuffer buffer = connection.inbound;
        buffer.flip();
        try {
            if (buffer.remaining() < 2) {
                return false;
            }
            int first = buffer.get(0) & 0xFF;
            int second = buffer.get(1) & 0xFF;
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            boolean masked = (second & 0x80) != 0;
            int payloadLength = second & 0x7F;
            int offset = 2;

            if (payloadLength == 127) {
                throw new IOException("探针拒绝 64 位长度帧");
            }
            if (payloadLength == 126) {
                if (buffer.remaining() < 4) {
                    return false;
                }
                payloadLength = ((buffer.get(2) & 0xFF) << 8) | (buffer.get(3) & 0xFF);
                offset = 4;
            }
            if (payloadLength > MAX_FRAME_PAYLOAD) {
                throw new IOException("探针帧载荷 " + payloadLength + " 字节超过上限 " + MAX_FRAME_PAYLOAD);
            }
            // RFC 6455 要求客户端发往服务端的帧一律掩码, 未掩码说明对端不是合规客户端
            if (!masked) {
                throw new IOException("客户端帧缺少掩码");
            }
            if (buffer.remaining() < offset + 4 + payloadLength) {
                return false;
            }

            byte[] payload = new byte[payloadLength];
            int maskOffset = offset;
            int dataOffset = offset + 4;
            for (int i = 0; i < payloadLength; i++) {
                payload[i] = (byte) (buffer.get(dataOffset + i) ^ buffer.get(maskOffset + (i & 3)));
            }
            buffer.position(dataOffset + payloadLength);

            handleFrame(connection, fin, opcode, payload);
            return true;
        } finally {
            buffer.compact();
        }
    }

    private void handleFrame(ProbeConnection connection, boolean fin, int opcode, byte[] payload)
            throws IOException {
        switch (opcode) {
            case OPCODE_TEXT -> {
                if (!fin) {
                    throw new IOException("探针不支持分片帧");
                }
                // 原样回显: 客户端把发送时刻编在消息里, 收回来一减即是往返耗时
                writeFrame(connection, OPCODE_TEXT, payload);
            }
            case OPCODE_PING -> writeFrame(connection, OPCODE_PONG, payload);
            case OPCODE_PONG -> {
                // 浏览器不会主动发 pong 以外的心跳, 收到就忽略
            }
            case OPCODE_CLOSE -> {
                writeFrame(connection, OPCODE_CLOSE, payload);
                connection.closing = true;
            }
            case OPCODE_CONTINUATION -> throw new IOException("探针不支持分片帧");
            default -> throw new IOException("探针不支持的 opcode: " + opcode);
        }
    }

    /** 服务端发出的帧按规范一律不加掩码。 */
    private void writeFrame(ProbeConnection connection, int opcode, byte[] payload) throws IOException {
        int headerLength = payload.length < 126 ? 2 : 4;
        if (connection.outbound.remaining() < headerLength + payload.length) {
            throw new IOException("探针发送缓冲区已满, 对端发送速率过高");
        }
        connection.outbound.put((byte) (0x80 | opcode));
        if (payload.length < 126) {
            connection.outbound.put((byte) payload.length);
        } else {
            connection.outbound.put((byte) 126);
            connection.outbound.putShort((short) payload.length);
        }
        connection.outbound.put(payload);
    }

    private void flush(ProbeConnection connection) throws IOException {
        ByteBuffer buffer = connection.outbound;
        buffer.flip();
        try {
            while (buffer.hasRemaining()) {
                if (connection.channel.write(buffer) == 0) {
                    break;
                }
            }
        } finally {
            buffer.compact();
        }
    }

    private void updateInterest(ProbeConnection connection) {
        if (connection.closed || connection.key == null || !connection.key.isValid()) {
            return;
        }
        int ops = connection.closing ? 0 : SelectionKey.OP_READ;
        if (connection.outbound.position() > 0) {
            ops |= SelectionKey.OP_WRITE;
        }
        connection.key.interestOps(ops);
    }

    private void sweepStalledHandshakes(long now) {
        List<ProbeConnection> stalled = null;
        for (ProbeConnection connection : connections) {
            if (!connection.upgraded && now - connection.createdAtMillis > HANDSHAKE_TIMEOUT_MILLIS) {
                if (stalled == null) {
                    stalled = new ArrayList<>();
                }
                stalled.add(connection);
            }
        }
        if (stalled == null) {
            return;
        }
        for (ProbeConnection connection : stalled) {
            closeConnection(connection);
        }
    }

    private void closeConnection(ProbeConnection connection) {
        if (connection.closed) {
            return;
        }
        connection.closed = true;
        closeQuietly(connection.channel);
        connections.remove(connection);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            logger.debug("关闭探针资源失败: {}", e.toString());
        }
    }
}
