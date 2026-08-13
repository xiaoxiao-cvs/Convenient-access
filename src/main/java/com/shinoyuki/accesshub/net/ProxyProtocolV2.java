package com.shinoyuki.accesshub.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

/**
 * PROXY protocol v2 头部解码器 (HAProxy 规范 2.2 节的二进制格式)。
 *
 * 为什么需要: frps 把玩家连接转交给 frpc 后, TCP 层源地址变成 frpc 的本机地址, 真实玩家 IP
 * 在链路中丢失 — 白名单审计日志会全部记成 127.0.0.1。frpc 侧开启 transport.proxyProtocolVersion="v2"
 * 后会在应用数据之前先发一段定长二进制头携带原始四元组, 本类负责解析它。
 *
 * 只实现 v2: frp 生成的就是 v2, 且规范已将 v1 文本格式标注为过时。不引入 netty-codec-haproxy —
 * 该 artifact 不在 Forge 运行期 classpath, 为百来行定长二进制解析多 shade 一个 jar 不划算。
 *
 * 无状态, 线程安全。
 */
public final class ProxyProtocolV2 {

    /** 规范固定签名 "\r\n\r\n\0\r\nQUIT\n"。用于把 PROXY 头与普通应用数据区分开。 */
    private static final byte[] SIGNATURE = {
            0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D,
            0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };

    /** 签名(12) + 版本与命令(1) + 地址族与传输协议(1) + 地址块长度(2)。 */
    public static final int FIXED_HEADER_LENGTH = 16;

    private static final int CMD_PROXY = 0x1;

    private static final int AF_INET = 0x1;
    private static final int AF_INET6 = 0x2;

    private static final int IPV4_ADDRESS_SIZE = 4;
    private static final int IPV6_ADDRESS_SIZE = 16;

    /** src(4) + dst(4) + sport(2) + dport(2)。 */
    private static final int INET_BLOCK_LENGTH = 12;
    /** src(16) + dst(16) + sport(2) + dport(2)。 */
    private static final int INET6_BLOCK_LENGTH = 36;

    /** 解析结果三态。 */
    public sealed interface Result {

        /** 已到达的字节还不足以判定, 调用方应继续读取后重试。 */
        record NeedMore() implements Result {
        }

        /** 确认不是 PROXY protocol v2 (签名不匹配)。调用方可降级为普通直连处理。 */
        record NotProxyProtocol() implements Result {
        }

        /**
         * 解析成功。
         *
         * @param headerLength 头部总字节数, 调用方须跳过这么多字节之后才是真正的应用数据
         * @param source       原始客户端地址; LOCAL 命令或非 INET/INET6 地址族时为 null
         * @param destination  原始目标地址, 即玩家实际连上的那个 frps 监听地址; 同上可能为 null
         */
        record Parsed(int headerLength, InetSocketAddress source, InetSocketAddress destination)
                implements Result {
        }
    }

    private static final Result NEED_MORE = new Result.NeedMore();
    private static final Result NOT_PROXY_PROTOCOL = new Result.NotProxyProtocol();

    private ProxyProtocolV2() {
    }

    /**
     * 从 buffer 当前 position 起尝试解析一个 PROXY v2 头。
     *
     * 不修改 buffer 的 position/limit: NIO 下同一段数据可能要重试多次解析 (分片到达), 由调用方
     * 在拿到 {@link Result.Parsed} 后自行前进 headerLength 个字节, 语义比隐式消费更可控。
     *
     * @throws IllegalArgumentException 签名匹配但版本号不是 2 — 这是对端配置错误 (说了 PROXY 协议
     *                                  却给了未知版本), 必须让它冒泡关闭连接, 而不是把带头的脏流量
     *                                  透传给 Minecraft 让玩家看到莫名其妙的解码错误
     */
    public static Result parse(ByteBuffer buffer) {
        final int start = buffer.position();
        final int available = buffer.limit() - start;

        // 逐字节比对: 只要已到达的部分出现不匹配就能立刻判定, 无需等签名收满 12 字节
        final int comparable = Math.min(available, SIGNATURE.length);
        for (int i = 0; i < comparable; i++) {
            if (buffer.get(start + i) != SIGNATURE[i]) {
                return NOT_PROXY_PROTOCOL;
            }
        }
        if (available < FIXED_HEADER_LENGTH) {
            return NEED_MORE;
        }

        final int versionAndCommand = buffer.get(start + 12) & 0xFF;
        final int version = versionAndCommand >>> 4;
        if (version != 0x2) {
            throw new IllegalArgumentException(
                    "PROXY protocol 签名匹配但版本号为 " + version + " (期望 2), 请检查 frpc 的 proxyProtocolVersion 配置");
        }
        final int command = versionAndCommand & 0x0F;
        final int addressFamily = (buffer.get(start + 13) & 0xFF) >>> 4;

        final int blockLength = ((buffer.get(start + 14) & 0xFF) << 8) | (buffer.get(start + 15) & 0xFF);
        final int headerLength = FIXED_HEADER_LENGTH + blockLength;
        if (available < headerLength) {
            return NEED_MORE;
        }

        // LOCAL 命令 (frps 的健康检查一类非代理连接) 按规范须忽略地址块, 沿用真实 TCP 四元组
        if (command != CMD_PROXY) {
            return new Result.Parsed(headerLength, null, null);
        }

        final int block = start + FIXED_HEADER_LENGTH;
        // 地址块长度小于该地址族的规定值说明对端截断了, 此时只认头长度让调用方能正确跳过, 不取地址
        return switch (addressFamily) {
            case AF_INET -> blockLength >= INET_BLOCK_LENGTH
                    ? readAddresses(buffer, block, headerLength, IPV4_ADDRESS_SIZE)
                    : new Result.Parsed(headerLength, null, null);
            case AF_INET6 -> blockLength >= INET6_BLOCK_LENGTH
                    ? readAddresses(buffer, block, headerLength, IPV6_ADDRESS_SIZE)
                    : new Result.Parsed(headerLength, null, null);
            // UNSPEC / UNIX: 没有可用的 IP 四元组
            default -> new Result.Parsed(headerLength, null, null);
        };
    }

    /** 地址块布局固定为 src、dst、sport、dport 四段紧邻排列。 */
    private static Result readAddresses(ByteBuffer buffer, int block, int headerLength, int addressSize) {
        byte[] sourceBytes = new byte[addressSize];
        byte[] destinationBytes = new byte[addressSize];
        for (int i = 0; i < addressSize; i++) {
            sourceBytes[i] = buffer.get(block + i);
            destinationBytes[i] = buffer.get(block + addressSize + i);
        }

        final int portOffset = block + addressSize * 2;
        final int sourcePort = readUnsignedShort(buffer, portOffset);
        final int destinationPort = readUnsignedShort(buffer, portOffset + 2);

        try {
            return new Result.Parsed(headerLength,
                    new InetSocketAddress(InetAddress.getByAddress(sourceBytes), sourcePort),
                    new InetSocketAddress(InetAddress.getByAddress(destinationBytes), destinationPort));
        } catch (UnknownHostException e) {
            // getByAddress 仅在字节数组长度非法时抛; 上面已按地址族固定为 4 或 16, 走到这里说明代码被改坏了
            throw new IllegalStateException("PROXY v2 地址字节长度非法: " + addressSize, e);
        }
    }

    private static int readUnsignedShort(ByteBuffer buffer, int index) {
        return ((buffer.get(index) & 0xFF) << 8) | (buffer.get(index + 1) & 0xFF);
    }
}
