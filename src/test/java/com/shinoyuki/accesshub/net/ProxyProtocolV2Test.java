package com.shinoyuki.accesshub.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

/**
 * PROXY protocol v2 解码器测试。
 *
 * 断言的是"从 frpc 收到的这段字节能还原出玩家的真实 IP 与他连上的那个 frps 端口",
 * 这两项正是线路归属统计与白名单审计日志的唯一数据来源。
 */
class ProxyProtocolV2Test {

    private static final byte[] SIGNATURE = {
            0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D,
            0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };

    private static final byte VERSION_2_PROXY = 0x21;
    private static final byte VERSION_2_LOCAL = 0x20;
    private static final byte AF_INET_STREAM = 0x11;
    private static final byte AF_INET6_STREAM = 0x21;

    @Test
    void 解析IPv4头还原真实客户端与目标地址() throws UnknownHostException {
        ByteBuffer buffer = ipv4Header("223.104.3.77", 54321, "8.148.217.146", 25565, new byte[0]);

        ProxyProtocolV2.Result result = ProxyProtocolV2.parse(buffer);

        ProxyProtocolV2.Result.Parsed parsed =
                assertInstanceOf(ProxyProtocolV2.Result.Parsed.class, result);
        assertEquals(28, parsed.headerLength(), "IPv4 无 TLV 时头长必须是 16+12");
        assertEquals("223.104.3.77", parsed.source().getAddress().getHostAddress());
        assertEquals(54321, parsed.source().getPort());
        assertEquals("8.148.217.146", parsed.destination().getAddress().getHostAddress(),
                "目标地址决定玩家走的是哪条线路, 不能错");
        assertEquals(25565, parsed.destination().getPort());
    }

    @Test
    void 带TLV扩展时头长包含TLV且地址仍正确() throws UnknownHostException {
        byte[] tlv = {0x03, 0x00, 0x04, 0x11, 0x22, 0x33, 0x44};
        ByteBuffer buffer = ipv4Header("100.64.7.9", 1234, "120.24.184.115", 25565, tlv);

        ProxyProtocolV2.Result.Parsed parsed = assertInstanceOf(
                ProxyProtocolV2.Result.Parsed.class, ProxyProtocolV2.parse(buffer));

        assertEquals(28 + tlv.length, parsed.headerLength(),
                "头长少算会把 TLV 残字节当成 Minecraft 握手包透传过去");
        assertEquals("100.64.7.9", parsed.source().getAddress().getHostAddress());
        assertEquals(25565, parsed.destination().getPort());
    }

    @Test
    void 解析IPv6头还原真实地址() throws UnknownHostException {
        ByteBuffer buffer = ipv6Header("2409:8a55:1234::10", 40000, "240e:aa:bb::1", 25565);

        ProxyProtocolV2.Result.Parsed parsed = assertInstanceOf(
                ProxyProtocolV2.Result.Parsed.class, ProxyProtocolV2.parse(buffer));

        assertEquals(52, parsed.headerLength(), "IPv6 头长必须是 16+36");
        assertEquals(InetAddress.getByName("2409:8a55:1234::10"), parsed.source().getAddress());
        assertEquals(40000, parsed.source().getPort());
        assertEquals(25565, parsed.destination().getPort());
    }

    @Test
    void 数据不足时返回NeedMore而非误判() throws UnknownHostException {
        ByteBuffer full = ipv4Header("1.2.3.4", 5555, "8.148.217.146", 25565, new byte[0]);
        byte[] bytes = new byte[full.remaining()];
        full.get(bytes);

        // 签名只到一半
        assertInstanceOf(ProxyProtocolV2.Result.NeedMore.class,
                ProxyProtocolV2.parse(ByteBuffer.wrap(bytes, 0, 6)));
        // 固定头差一字节
        assertInstanceOf(ProxyProtocolV2.Result.NeedMore.class,
                ProxyProtocolV2.parse(ByteBuffer.wrap(bytes, 0, 15)));
        // 固定头齐了但地址块只到一半
        assertInstanceOf(ProxyProtocolV2.Result.NeedMore.class,
                ProxyProtocolV2.parse(ByteBuffer.wrap(bytes, 0, 22)));
        // 刚好收满
        assertInstanceOf(ProxyProtocolV2.Result.Parsed.class,
                ProxyProtocolV2.parse(ByteBuffer.wrap(bytes, 0, 28)));
    }

    @Test
    void Minecraft握手包被判定为非PROXY协议() {
        // 真实的 1.20.1 握手包开头: 包长 VarInt, packet id 0x00, 协议版本 763
        byte[] handshake = {0x10, 0x00, (byte) 0xFB, 0x05, 0x09, 0x6C, 0x6F, 0x63, 0x61, 0x6C, 0x68, 0x6F, 0x73, 0x74};

        assertInstanceOf(ProxyProtocolV2.Result.NotProxyProtocol.class,
                ProxyProtocolV2.parse(ByteBuffer.wrap(handshake)),
                "没开 PROXY protocol 的直连必须能被识别出来并降级放行");
    }

    @Test
    void 首字节即不匹配时无需等满签名就判定() {
        assertInstanceOf(ProxyProtocolV2.Result.NotProxyProtocol.class,
                ProxyProtocolV2.parse(ByteBuffer.wrap(new byte[]{0x10})));
    }

    @Test
    void LOCAL命令忽略地址块但仍报出头长() throws UnknownHostException {
        ByteBuffer buffer = ipv4Header("9.9.9.9", 1111, "8.8.8.8", 2222, new byte[0]);
        buffer.put(12, VERSION_2_LOCAL);

        ProxyProtocolV2.Result.Parsed parsed = assertInstanceOf(
                ProxyProtocolV2.Result.Parsed.class, ProxyProtocolV2.parse(buffer));

        assertEquals(28, parsed.headerLength());
        assertNull(parsed.source(), "LOCAL 命令按规范不得采信地址块");
        assertNull(parsed.destination());
    }

    @Test
    void 版本号非2时抛异常而不是静默透传() throws UnknownHostException {
        ByteBuffer buffer = ipv4Header("1.1.1.1", 100, "2.2.2.2", 200, new byte[0]);
        buffer.put(12, (byte) 0x31);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProxyProtocolV2.parse(buffer));
        assertEquals(true, e.getMessage().contains("版本号为 3"));
    }

    @Test
    void 解析不得移动buffer的position() throws UnknownHostException {
        ByteBuffer buffer = ipv4Header("5.6.7.8", 9999, "8.148.217.146", 25565, new byte[0]);
        int before = buffer.position();

        ProxyProtocolV2.parse(buffer);

        assertEquals(before, buffer.position(),
                "调用方依赖 position 不变来自行跳过头部, 隐式消费会吞掉后续应用数据");
    }

    @Test
    void 从非零position起解析同样正确() throws UnknownHostException {
        ByteBuffer full = ipv4Header("172.16.0.9", 33333, "8.148.217.146", 25565, new byte[0]);
        ByteBuffer padded = ByteBuffer.allocate(full.remaining() + 5);
        padded.put(new byte[]{1, 2, 3, 4, 5});
        padded.put(full);
        padded.flip();
        padded.position(5);

        ProxyProtocolV2.Result.Parsed parsed = assertInstanceOf(
                ProxyProtocolV2.Result.Parsed.class, ProxyProtocolV2.parse(padded));

        assertEquals("172.16.0.9", parsed.source().getAddress().getHostAddress());
        assertEquals(33333, parsed.source().getPort());
    }

    private static ByteBuffer ipv4Header(String sourceIp, int sourcePort,
                                         String destinationIp, int destinationPort,
                                         byte[] tlv) throws UnknownHostException {
        return header(AF_INET_STREAM, sourceIp, sourcePort, destinationIp, destinationPort, 12, tlv);
    }

    private static ByteBuffer ipv6Header(String sourceIp, int sourcePort,
                                         String destinationIp, int destinationPort) throws UnknownHostException {
        return header(AF_INET6_STREAM, sourceIp, sourcePort, destinationIp, destinationPort, 36, new byte[0]);
    }

    private static ByteBuffer header(byte familyAndProtocol,
                                     String sourceIp, int sourcePort,
                                     String destinationIp, int destinationPort,
                                     int addressBlockLength, byte[] tlv) throws UnknownHostException {
        int blockLength = addressBlockLength + tlv.length;
        ByteBuffer buffer = ByteBuffer.allocate(16 + blockLength);
        buffer.put(SIGNATURE);
        buffer.put(VERSION_2_PROXY);
        buffer.put(familyAndProtocol);
        buffer.putShort((short) blockLength);
        buffer.put(InetAddress.getByName(sourceIp).getAddress());
        buffer.put(InetAddress.getByName(destinationIp).getAddress());
        buffer.putShort((short) sourcePort);
        buffer.putShort((short) destinationPort);
        buffer.put(tlv);
        buffer.flip();
        return buffer;
    }
}
