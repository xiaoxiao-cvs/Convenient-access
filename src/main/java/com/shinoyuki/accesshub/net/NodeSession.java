package com.shinoyuki.accesshub.net;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * 一条经过转发器的活跃连接。
 *
 * 生命周期严格等于底层 TCP 连接: 转发器 accept 后创建, 连接关闭时从
 * {@link NodeSessionRegistry} 移除。玩家下线时 TCP 随之断开, 因此不需要单独的解绑逻辑。
 *
 * 线程模型: 由转发器的 NIO 线程创建, 由服务器主线程绑定玩家, 由 HTTP 线程读取统计,
 * 故绑定字段用 volatile 发布。名字与 UUID 合成一个不可变 record 一起发布, 避免读到
 * "有名字但没 UUID" 的半成品状态。
 */
public final class NodeSession {

    /** 已认领这条连接的玩家。 */
    public record PlayerBinding(String name, UUID uuid) {
    }

    private final String nodeId;
    private final InetSocketAddress clientAddress;
    private final int upstreamPort;
    private final long establishedAtMillis;

    private volatile PlayerBinding binding;

    /**
     * @param nodeId              线路标识, 取自 {@link NodeDefinition#id()}
     * @param clientAddress       玩家真实地址; PROXY protocol 未启用或给的是 LOCAL 命令时为 null
     * @param upstreamPort        转发器连接 Minecraft 时占用的本地端口, 这是与 Minecraft 侧
     *                            {@code connection.getRemoteAddress()} 对齐的唯一 join key
     * @param establishedAtMillis 建连时刻
     */
    public NodeSession(String nodeId, InetSocketAddress clientAddress,
                       int upstreamPort, long establishedAtMillis) {
        this.nodeId = nodeId;
        this.clientAddress = clientAddress;
        this.upstreamPort = upstreamPort;
        this.establishedAtMillis = establishedAtMillis;
    }

    public String nodeId() {
        return nodeId;
    }

    /** 玩家真实地址, 可能为 null。 */
    public InetSocketAddress clientAddress() {
        return clientAddress;
    }

    /** 真实客户端 IP 的字符串形式; 未知时返回 null 而不是占位串 — 审计日志需要能区分"未知"与"某个 IP"。 */
    public String clientIp() {
        if (clientAddress == null || clientAddress.getAddress() == null) {
            return null;
        }
        return clientAddress.getAddress().getHostAddress();
    }

    public int upstreamPort() {
        return upstreamPort;
    }

    public long establishedAtMillis() {
        return establishedAtMillis;
    }

    /** 尚未绑定玩家时返回 null: 该连接可能仍在登录流程中, 也可能只是一次服务器列表状态查询。 */
    public PlayerBinding binding() {
        return binding;
    }

    public void bind(String name, UUID uuid) {
        this.binding = new PlayerBinding(name, uuid);
    }
}
