package com.shinoyuki.accesshub.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多线路 TCP 前置转发器。
 *
 * 每条线路在本机独占一个入口端口, 所有入口的流量统一转发到 Minecraft 的真实监听端口。
 * 玩家从哪个入口端口进来, 就属于哪条线路 — 这是物理事实, 比读取玩家输入的域名可靠:
 * 玩家换成 IP 直连时域名法就失效了, 端口法不会。
 *
 * 顺带解决另一个必然踩到的坑: 经 frp 中转后 Minecraft 看到的源地址全是本机回环, 白名单审计
 * 日志里的 IP 会整体作废。frpc 开启 PROXY protocol v2 后, 本类在转发前先吃掉那段头并把真实
 * 客户端地址登记进 {@link NodeSessionRegistry}, 由登录监听器取回。
 *
 * 线程模型: 单个 selector 事件循环线程。Minecraft 二十人满载的上行也就十几 Mbps, 单线程 NIO
 * 绰绰有余; 与 Minecraft 同处一个 JVM 意味着 GC 停顿会同时影响转发, 但玩家数据包本来就要经过
 * 这个 JVM 的 netty, 多这一跳的边际影响可以忽略。
 */
public final class NodeRelayServer {

    private static final Logger logger = LoggerFactory.getLogger(NodeRelayServer.class);

    /** 单向缓冲区大小。满了即停止从对侧读取形成背压, 不需要容纳整个区块包。 */
    private static final int BUFFER_SIZE = 32 * 1024;

    /**
     * PROXY 头长度上限。frp 实际发出的头是 28 (IPv4) 或 52 (IPv6) 字节, 留足冗余给 TLV 扩展。
     * 超过即判定对端在谎报长度, 直接断开, 避免被拖住无限缓冲。
     */
    private static final int MAX_PROXY_HEADER_LENGTH = 256;

    /** 连接建立后必须在此时间内完成 PROXY 头解析并接上 Minecraft, 否则回收, 防止慢速连接堆积。 */
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 10_000L;

    private static final long SELECT_TIMEOUT_MILLIS = 1_000L;

    private enum State {
        /** 正在等待并解析 PROXY protocol 头 (或判定对端没有发头)。 */
        AWAIT_PROXY_HEADER,
        /** 正在建立到 Minecraft 的连接。 */
        CONNECTING,
        /** 双向透传中。 */
        RELAYING
    }

    /** 一对连接: 玩家侧 downstream 与 Minecraft 侧 upstream。 */
    private static final class RelayConnection {
        final NodeDefinition node;
        final SocketChannel downstream;
        final long createdAtMillis;

        /** 两个缓冲区始终维持在写模式: position 是已缓冲字节数, remaining 是剩余可填空间。 */
        final ByteBuffer toUpstream = ByteBuffer.allocate(BUFFER_SIZE);
        final ByteBuffer toDownstream = ByteBuffer.allocate(BUFFER_SIZE);

        SocketChannel upstream;
        SelectionKey downstreamKey;
        SelectionKey upstreamKey;
        State state = State.AWAIT_PROXY_HEADER;
        InetSocketAddress clientAddress;
        NodeSession session;

        boolean downstreamEof;
        boolean upstreamEof;
        boolean upstreamOutputShutdown;
        boolean downstreamOutputShutdown;
        boolean closed;

        RelayConnection(NodeDefinition node, SocketChannel downstream, long createdAtMillis) {
            this.node = node;
            this.downstream = downstream;
            this.createdAtMillis = createdAtMillis;
        }
    }

    private final List<NodeDefinition> nodes;
    private final InetSocketAddress minecraftAddress;
    private final String bindHost;
    private final NodeSessionRegistry registry;

    private final Set<RelayConnection> connections = new HashSet<>();
    private final List<ServerSocketChannel> listeners = new ArrayList<>();

    private Selector selector;
    private Thread eventLoop;
    private volatile boolean running;

    public NodeRelayServer(Collection<NodeDefinition> nodes, String bindHost,
                           InetSocketAddress minecraftAddress, NodeSessionRegistry registry) {
        this.nodes = List.copyOf(nodes);
        this.bindHost = bindHost;
        this.minecraftAddress = minecraftAddress;
        this.registry = registry;
    }

    /**
     * 绑定全部线路入口并启动事件循环。任一端口绑定失败即整体回滚并抛出 — 少绑一个端口意味着
     * 那条线路的玩家会连不上, 静默降级只会让问题在玩家侧暴露而不是在启动日志里。
     */
    public void start() throws IOException {
        for (NodeDefinition node : nodes) {
            if (node.listenPort() == minecraftAddress.getPort()) {
                throw new IllegalArgumentException(
                        "线路 " + node.id() + " 的入口端口与 Minecraft 端口相同 (" + node.listenPort()
                                + "), 会造成自我转发死循环");
            }
        }

        selector = Selector.open();
        try {
            for (NodeDefinition node : nodes) {
                ServerSocketChannel listener = ServerSocketChannel.open();
                listener.configureBlocking(false);
                listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                listener.bind(new InetSocketAddress(bindHost, node.listenPort()));
                listener.register(selector, SelectionKey.OP_ACCEPT, node);
                listeners.add(listener);
                logger.info("线路 [{}] {} 入口已监听 {}:{} -> {}",
                        node.id(), node.displayName(), bindHost, node.listenPort(), minecraftAddress);
            }
        } catch (IOException | RuntimeException e) {
            closeListeners();
            closeQuietly(selector);
            selector = null;
            throw e;
        }

        running = true;
        eventLoop = new Thread(this::runEventLoop, "AccessHub-NodeRelay");
        eventLoop.setDaemon(true);
        eventLoop.start();
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
        closeListeners();
        // 事件循环退出后统一清理残余连接, 此时已无并发访问
        for (RelayConnection connection : new ArrayList<>(connections)) {
            closeConnection(connection);
        }
        connections.clear();
        registry.clear();
        closeQuietly(selector);
        logger.info("线路转发器已停止");
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
                    logger.error("线路转发器事件循环 select 失败", e);
                }
            } catch (RuntimeException e) {
                // 事件循环线程绝不能因单次异常退出, 否则所有线路瞬间失联且没有任何提示
                logger.error("线路转发器事件循环异常", e);
            }
        }
    }

    /** 单个连接上的异常只终结该连接, 不能影响其它玩家。 */
    private void dispatch(SelectionKey key) {
        if (!key.isValid()) {
            return;
        }
        RelayConnection connection = key.attachment() instanceof RelayConnection relay ? relay : null;
        try {
            if (key.isAcceptable()) {
                accept(key);
                return;
            }
            if (connection == null) {
                return;
            }
            if (key.isConnectable()) {
                finishUpstreamConnect(connection);
            }
            if (key.isValid() && key.isReadable()) {
                read(key, connection);
            }
            if (key.isValid() && key.isWritable()) {
                write(key, connection);
            }
            if (connection.state == State.RELAYING || connection.downstreamEof) {
                propagateHalfClose(connection);
            }
            updateInterest(connection);
            if (isFinished(connection)) {
                closeConnection(connection);
            }
        } catch (IOException | RuntimeException e) {
            if (connection != null) {
                logger.debug("线路 [{}] 连接异常关闭: {}",
                        connection.node.id(), e.toString());
                closeConnection(connection);
            } else {
                logger.warn("处理 accept 事件失败", e);
            }
        }
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel listener = (ServerSocketChannel) key.channel();
        NodeDefinition node = (NodeDefinition) key.attachment();
        SocketChannel downstream = listener.accept();
        if (downstream == null) {
            return;
        }
        downstream.configureBlocking(false);
        // Minecraft 是小包低延迟场景, Nagle 合包会凭空增加几十毫秒手感延迟
        downstream.setOption(StandardSocketOptions.TCP_NODELAY, true);

        RelayConnection connection = new RelayConnection(node, downstream, System.currentTimeMillis());
        connection.downstreamKey = downstream.register(selector, SelectionKey.OP_READ, connection);
        connections.add(connection);
    }

    private void read(SelectionKey key, RelayConnection connection) throws IOException {
        if (key.channel() == connection.downstream) {
            readFromDownstream(connection);
        } else {
            readFromUpstream(connection);
        }
    }

    private void write(SelectionKey key, RelayConnection connection) throws IOException {
        if (key.channel() == connection.downstream) {
            flushToDownstream(connection);
        } else {
            flushToUpstream(connection);
        }
    }

    private void readFromDownstream(RelayConnection connection) throws IOException {
        int count = connection.downstream.read(connection.toUpstream);
        if (count < 0) {
            connection.downstreamEof = true;
        }
        if (connection.state == State.AWAIT_PROXY_HEADER) {
            resolveProxyHeader(connection);
        }
        if (connection.state == State.RELAYING) {
            flushToUpstream(connection);
        }
    }

    private void readFromUpstream(RelayConnection connection) throws IOException {
        int count = connection.upstream.read(connection.toDownstream);
        if (count < 0) {
            connection.upstreamEof = true;
        }
        flushToDownstream(connection);
    }

    /**
     * 判定并消费 PROXY 头, 随后发起到 Minecraft 的连接。
     *
     * 缓冲区里此刻可能已经混着玩家的握手包 (客户端连上后会立刻发), 所以只跳过头部长度,
     * 剩余字节原样留在缓冲区, 接上 Minecraft 后第一时间转发出去。
     */
    private void resolveProxyHeader(RelayConnection connection) throws IOException {
        ByteBuffer buffer = connection.toUpstream;
        buffer.flip();
        try {
            ProxyProtocolV2.Result result = ProxyProtocolV2.parse(buffer);
            if (result instanceof ProxyProtocolV2.Result.NeedMore) {
                if (buffer.remaining() > MAX_PROXY_HEADER_LENGTH) {
                    throw new IOException("PROXY 头超过 " + MAX_PROXY_HEADER_LENGTH + " 字节仍未完整, 判定为非法对端");
                }
                if (connection.downstreamEof) {
                    throw new IOException("对端在 PROXY 头发完之前就断开了");
                }
                return;
            }
            if (result instanceof ProxyProtocolV2.Result.Parsed parsed) {
                connection.clientAddress = parsed.source() != null
                        ? parsed.source()
                        : actualRemoteAddress(connection);
                buffer.position(buffer.position() + parsed.headerLength());
            } else {
                // 对端没开 PROXY protocol: 家宽直连线路就是这种情况, TCP 层地址本身就是玩家真实地址
                connection.clientAddress = actualRemoteAddress(connection);
            }
        } finally {
            buffer.compact();
        }

        connection.state = State.CONNECTING;
        startUpstreamConnect(connection);
    }

    private InetSocketAddress actualRemoteAddress(RelayConnection connection) throws IOException {
        return connection.downstream.getRemoteAddress() instanceof InetSocketAddress address ? address : null;
    }

    private void startUpstreamConnect(RelayConnection connection) throws IOException {
        SocketChannel upstream = SocketChannel.open();
        upstream.configureBlocking(false);
        upstream.setOption(StandardSocketOptions.TCP_NODELAY, true);
        connection.upstream = upstream;
        if (upstream.connect(minecraftAddress)) {
            connection.upstreamKey = upstream.register(selector, SelectionKey.OP_READ, connection);
            onUpstreamConnected(connection);
        } else {
            connection.upstreamKey = upstream.register(selector, SelectionKey.OP_CONNECT, connection);
        }
    }

    private void finishUpstreamConnect(RelayConnection connection) throws IOException {
        if (!connection.upstream.finishConnect()) {
            return;
        }
        connection.upstreamKey.interestOps(SelectionKey.OP_READ);
        onUpstreamConnected(connection);
    }

    /**
     * 登记会话。本地端口要在连接真正建立后才取, 此时内核已完成绑定; 它就是 Minecraft 侧
     * {@code connection.getRemoteAddress()} 里的端口, 两边靠它对上号。
     */
    private void onUpstreamConnected(RelayConnection connection) throws IOException {
        int upstreamPort = ((InetSocketAddress) connection.upstream.getLocalAddress()).getPort();
        connection.session = new NodeSession(
                connection.node.id(), connection.clientAddress, upstreamPort, connection.createdAtMillis);
        registry.register(connection.session);
        connection.state = State.RELAYING;
        flushToUpstream(connection);
    }

    private void flushToUpstream(RelayConnection connection) throws IOException {
        if (connection.upstream == null) {
            return;
        }
        flush(connection.toUpstream, connection.upstream);
    }

    private void flushToDownstream(RelayConnection connection) throws IOException {
        flush(connection.toDownstream, connection.downstream);
    }

    /** 缓冲区在写模式与读模式之间来回切换, 写不完的部分由 compact 保留到下次可写事件。 */
    private void flush(ByteBuffer buffer, SocketChannel channel) throws IOException {
        buffer.flip();
        try {
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) == 0) {
                    break;
                }
            }
        } finally {
            buffer.compact();
        }
    }

    /**
     * 单向关闭传播。一侧读到 EOF 时不能立刻整体断开: 另一侧可能还有数据没送达。
     * 等缓冲区排空再关闭对应的写方向, 让对端也读到 EOF。
     */
    private void propagateHalfClose(RelayConnection connection) throws IOException {
        if (connection.downstreamEof && !connection.upstreamOutputShutdown
                && connection.upstream != null && connection.toUpstream.position() == 0) {
            connection.upstream.shutdownOutput();
            connection.upstreamOutputShutdown = true;
        }
        if (connection.upstreamEof && !connection.downstreamOutputShutdown
                && connection.toDownstream.position() == 0) {
            connection.downstream.shutdownOutput();
            connection.downstreamOutputShutdown = true;
        }
    }

    private boolean isFinished(RelayConnection connection) {
        if (connection.downstreamEof && connection.upstream == null) {
            // 还没接上 Minecraft 玩家就跑了
            return true;
        }
        return connection.downstreamEof && connection.upstreamEof
                && connection.toUpstream.position() == 0
                && connection.toDownstream.position() == 0;
    }

    /**
     * 重算两端的关注事件, 这是背压的落点: 缓冲区填满就停止从对侧读取, 让 TCP 窗口自然收缩,
     * 而不是在用户态无限堆积。
     */
    private void updateInterest(RelayConnection connection) {
        if (connection.closed) {
            return;
        }
        if (connection.downstreamKey != null && connection.downstreamKey.isValid()) {
            int ops = 0;
            if (!connection.downstreamEof && connection.toUpstream.hasRemaining()) {
                ops |= SelectionKey.OP_READ;
            }
            if (connection.toDownstream.position() > 0) {
                ops |= SelectionKey.OP_WRITE;
            }
            connection.downstreamKey.interestOps(ops);
        }
        if (connection.upstreamKey != null && connection.upstreamKey.isValid()
                && connection.state == State.RELAYING) {
            int ops = 0;
            if (!connection.upstreamEof && connection.toDownstream.hasRemaining()) {
                ops |= SelectionKey.OP_READ;
            }
            if (connection.toUpstream.position() > 0) {
                ops |= SelectionKey.OP_WRITE;
            }
            connection.upstreamKey.interestOps(ops);
        }
    }

    /** 回收迟迟没能接上 Minecraft 的连接, 防止只连不发的客户端把入口拖垮。 */
    private void sweepStalledHandshakes(long now) {
        List<RelayConnection> stalled = null;
        for (RelayConnection connection : connections) {
            if (connection.state != State.RELAYING
                    && now - connection.createdAtMillis > HANDSHAKE_TIMEOUT_MILLIS) {
                if (stalled == null) {
                    stalled = new ArrayList<>();
                }
                stalled.add(connection);
            }
        }
        if (stalled == null) {
            return;
        }
        for (RelayConnection connection : stalled) {
            logger.debug("线路 [{}] 回收超时未完成握手的连接", connection.node.id());
            closeConnection(connection);
        }
    }

    private void closeConnection(RelayConnection connection) {
        if (connection.closed) {
            return;
        }
        connection.closed = true;
        if (connection.session != null) {
            registry.unregister(connection.session);
        }
        closeQuietly(connection.downstream);
        closeQuietly(connection.upstream);
        connections.remove(connection);
    }

    private void closeListeners() {
        for (ServerSocketChannel listener : listeners) {
            closeQuietly(listener);
        }
        listeners.clear();
    }

    /** 取 Closeable 而非 Channel: Selector 同样需要关闭, 但它并不是 Channel。 */
    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            logger.debug("关闭资源失败: {}", e.toString());
        }
    }
}
