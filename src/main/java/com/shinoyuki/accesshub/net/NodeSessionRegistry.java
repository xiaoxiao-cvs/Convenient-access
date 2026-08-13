package com.shinoyuki.accesshub.net;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃连接登记表, 以"转发器连 Minecraft 时占用的本地端口"为主键。
 *
 * 这个端口是整套线路归属统计的枢纽: 转发器建连时知道 (线路, 真实IP, 本地端口), Minecraft 侧
 * 在玩家进服时只知道 {@code 127.0.0.1:本地端口} — 两边靠它对上号。时序上必然安全, 因为这条
 * 到 Minecraft 的连接本来就是转发器发起的, 登记一定早于 Minecraft 触发任何登录事件。
 *
 * 线程安全: NIO 线程写入/移除, 服务器主线程绑定玩家, HTTP 线程读取快照。
 */
public final class NodeSessionRegistry {

    /**
     * 某条线路的瞬时人数。
     *
     * @param nodeId     线路标识
     * @param online     已认领到具体玩家的连接数, 即这条线上真实在玩的人数
     * @param connecting 尚未认领的连接数。除了正在登录的玩家, 还包含客户端刷新服务器列表时
     *                   发起的状态查询短连接, 所以这个值会有秒级抖动, 只作参考
     */
    public record NodeStats(String nodeId, int online, int connecting) {
    }

    private final Map<Integer, NodeSession> sessions = new ConcurrentHashMap<>();

    public void register(NodeSession session) {
        sessions.put(session.upstreamPort(), session);
    }

    /**
     * 移除指定会话。必须传入原会话对象做值比对: 操作系统会复用刚释放的本地端口, 若只按端口删,
     * 一个迟到的关闭回调可能把刚建立的新会话误删, 让那名玩家从统计里凭空消失。
     */
    public void unregister(NodeSession session) {
        sessions.remove(session.upstreamPort(), session);
    }

    /** 按 Minecraft 侧看到的来源端口查会话; 未经转发器的连接 (内网直连) 查不到, 返回 null。 */
    public NodeSession findByUpstreamPort(int upstreamPort) {
        return sessions.get(upstreamPort);
    }

    /**
     * 把玩家认领到对应连接上。
     *
     * @return 被认领的会话; 该连接未经转发器时返回 null
     */
    public NodeSession bindPlayer(int upstreamPort, String name, UUID uuid) {
        NodeSession session = sessions.get(upstreamPort);
        if (session != null) {
            session.bind(name, uuid);
        }
        return session;
    }

    /**
     * 按线路聚合当前人数。
     *
     * 传入线路定义而不是只统计出现过的 key, 是为了让当前无人的线路也能输出 0 — 前端需要
     * 稳定的线路列表, 不能因为没人就整条消失。
     */
    public List<NodeStats> snapshot(Collection<NodeDefinition> nodes) {
        Map<String, int[]> counters = new LinkedHashMap<>();
        for (NodeDefinition node : nodes) {
            counters.put(node.id(), new int[2]);
        }
        for (NodeSession session : sessions.values()) {
            int[] counter = counters.get(session.nodeId());
            if (counter == null) {
                // 线路定义被热改动过, 而旧连接还挂着; 单列出来而不是丢弃, 免得人数对不上
                counter = counters.computeIfAbsent(session.nodeId(), key -> new int[2]);
            }
            if (session.binding() != null) {
                counter[0]++;
            } else {
                counter[1]++;
            }
        }
        List<NodeStats> result = new ArrayList<>(counters.size());
        counters.forEach((nodeId, counter) -> result.add(new NodeStats(nodeId, counter[0], counter[1])));
        return result;
    }

    /** 已认领玩家的连接总数。与服务器实际在线人数的差值即为未经转发器进来的玩家 (内网直连)。 */
    public int boundPlayerCount() {
        int count = 0;
        for (NodeSession session : sessions.values()) {
            if (session.binding() != null) {
                count++;
            }
        }
        return count;
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public void clear() {
        sessions.clear();
    }
}
