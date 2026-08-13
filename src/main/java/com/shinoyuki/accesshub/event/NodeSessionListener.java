package com.shinoyuki.accesshub.event;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import com.shinoyuki.accesshub.net.NodeSession;
import com.shinoyuki.accesshub.net.NodeSessionRegistry;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把进服玩家认领到其所属的线路会话上。
 *
 * 独立成一个监听器而不是并进 {@link PlayerLoginListener}: 后者整体受 whitelist.enabled 开关控制,
 * 白名单一关线路统计就跟着失效, 而这两件事本来毫无关系。
 *
 * 不需要对应的解绑逻辑 — 会话生命周期等于 TCP 连接生命周期, 玩家下线时连接断开, 转发器会把会话
 * 从登记表移除。
 */
public final class NodeSessionListener {

    private static final Logger logger = LoggerFactory.getLogger(NodeSessionListener.class);

    private final NodeSessionRegistry registry;

    public NodeSessionListener(NodeSessionRegistry registry) {
        this.registry = registry;
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        SocketAddress address = player.connection.connection.getRemoteAddress();
        if (!(address instanceof InetSocketAddress inet)) {
            return;
        }
        // 经转发器进来的连接源地址必然是回环。加这道判断可以避免外部直连玩家的临时端口
        // 恰好撞上某个会话端口时被错误认领到别的线路上。
        if (inet.getAddress() == null || !inet.getAddress().isLoopbackAddress()) {
            return;
        }

        String name = player.getGameProfile().getName();
        NodeSession session = registry.bindPlayer(inet.getPort(), name, player.getUUID());
        if (session != null) {
            logger.debug("玩家 {} 归属线路 [{}], 真实 IP {}", name, session.nodeId(), session.clientIp());
        }
    }
}
