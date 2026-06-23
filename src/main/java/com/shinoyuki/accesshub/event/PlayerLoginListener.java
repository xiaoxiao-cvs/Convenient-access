package com.shinoyuki.accesshub.event;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mojang.authlib.GameProfile;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.database.DatabaseManager;
import com.shinoyuki.accesshub.whitelist.AccessDecision;
import com.shinoyuki.accesshub.whitelist.WhitelistEntry;
import com.shinoyuki.accesshub.whitelist.WhitelistManager;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 白名单事件监听器, 替代 v1 Bukkit WhitelistListener.
 *
 * 事件映射:
 *   v1 AsyncPlayerPreLoginEvent (Bukkit) -> v3 PlayerNegotiationEvent (Forge)
 *   PlayerNegotiationEvent 在玩家完成 handshake / 登录协商但尚未进入游戏世界前触发, 异步安全.
 *   监听器创建 future 通过 event.enqueueWork(future) 入队, Forge 在 negotiation 阶段等待所有 future 完成.
 *   若 future 内部调用 connection.disconnect(...), 玩家立刻被踢出, 不再进入后续阶段.
 *
 * 保留 v1 加固后的全部行为:
 *  - 双 key 缓存查询 (UUID + name) - 由 WhitelistManager.isPlayerWhitelistedOffline 自身实现
 *  - 15s 查询超时, 防止异步线程池堆积无限拖延 negotiation
 *  - 1.5s 慢查询日志预警 (堆积征兆)
 *  - 严格模式: 查询失败默认踢人, 配置可关 (whitelist.strict-mode)
 *  - operation_log 表记录 UNAUTHORIZED_ACCESS 审计条目
 *
 * v3 Forge 环境下日志直接走 SLF4J -> Forge 主 console, 不再需要 v1 的 plugin.getLogger() workaround.
 */
public final class PlayerLoginListener {

    private static final Logger logger = LoggerFactory.getLogger(PlayerLoginListener.class);

    /** 白名单查询超时. 含异步线程池排队 + DB 查询全部时间. */
    private static final int WHITELIST_CHECK_TIMEOUT_SECONDS = 15;
    /** 超过此阈值的查询触发慢查询日志, 便于发现线程池堆积征兆. */
    private static final long SLOW_CHECK_WARN_THRESHOLD_MS = 1500;

    private final AccessHubConfig config;
    private final WhitelistManager whitelistManager;
    private final DatabaseManager databaseManager;

    public PlayerLoginListener(AccessHubConfig config,
                               WhitelistManager whitelistManager,
                               DatabaseManager databaseManager) {
        this.config = config;
        this.whitelistManager = whitelistManager;
        this.databaseManager = databaseManager;
    }

    @SubscribeEvent
    public void onPlayerNegotiation(PlayerNegotiationEvent event) {
        if (!config.isWhitelistEnabled()) {
            return;
        }

        GameProfile profile = event.getProfile();
        if (profile == null) {
            return;
        }
        String playerName = profile.getName();
        UUID uuidObj = profile.getId();
        if (playerName == null || uuidObj == null) {
            return;
        }
        String playerUuid = uuidObj.toString();
        String ipAddress = formatRemoteAddress(event.getConnection().getRemoteAddress());

        CompletableFuture<Void> check = CompletableFuture.runAsync(
                () -> performWhitelistCheck(event, playerName, playerUuid, ipAddress)
        );
        event.enqueueWork(check);
    }

    /**
     * 玩家进入游戏世界后的处理, 对应 v1 WhitelistListener.onPlayerJoin。
     *
     * 三件事 (沿用 v1 行为):
     *  1. UUID 补全: 按名字加入(UUID留空)的白名单条目, 玩家首次登录时补上真实 UUID
     *  2. 欢迎消息: 向玩家发送可配置的欢迎语
     *  3. 加入通知: 向在线 OP 广播白名单玩家加入
     *
     * 注: WhitelistManager 的查询在异步线程池执行, 但向玩家/OP 发包必须回到服务器主线程,
     * 故消息发送统一经 server.execute() 调度。
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!config.isWhitelistEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String name = player.getGameProfile().getName();
        String uuid = player.getUUID().toString();
        String ip = formatRemoteAddress(player.connection.connection.getRemoteAddress());
        MinecraftServer server = player.getServer();

        // 白名单拦截 (进服瞬间踢人).
        // 说明: 理论上 PreLogin 阶段的 PlayerNegotiationEvent 能更早拒绝, 但实测在部分整合包环境
        // (如 Sinytra Connector) 该事件不被触发, 故以 PlayerLoggedInEvent (核心事件, 必然触发) 兜底,
        // 非白名单玩家进服后立即被踢。两个事件并存形成防御纵深。
        whitelistManager.checkAccess(name, uuid).thenAccept(decision -> {
            if (server == null) {
                return;
            }
            server.execute(() -> {
                if (decision == AccessDecision.ALLOWED) {
                    processWhitelistedJoin(player, name, uuid);
                } else {
                    // PLAY 阶段: player.connection (ServerGamePacketListenerImpl) 的 disconnect 会先发
                    // ClientboundDisconnectPacket 再关通道, 文案能正常显示, 无需 login 阶段的手动发包补丁。
                    String message = decision == AccessDecision.DISABLED
                            ? formatDisabledMessage(name)
                            : formatKickMessage(name);
                    player.connection.disconnect(Component.literal(message));
                    logger.warn("拒绝玩家进入 ({}): {} ({}) IP: {}",
                            decision == AccessDecision.DISABLED ? "白名单被禁用" : "未在白名单", name, uuid, ip);
                    logUnauthorizedAccess(name, uuid, ip);
                }
            });
        }).exceptionally(t -> {
            // 查询异常: 严格模式踢人, 宽松模式放行 (与 PreLogin 路径一致)
            if (server != null) {
                server.execute(() -> {
                    if (config.isWhitelistStrictMode()) {
                        player.connection.disconnect(Component.literal("§c白名单验证失败, 请稍后重试"));
                        logger.warn("[Whitelist] 严格模式踢出 (查询异常): {} - {}", name, t.getMessage());
                    } else {
                        logger.warn("[Whitelist] 宽松模式放行 (查询异常): {} - {}", name, t.getMessage());
                    }
                });
            }
            return null;
        });
    }

    /**
     * 白名单内玩家的加入后处理: UUID 补全 + 欢迎 + 通知 (对应 v1 onPlayerJoin)。
     * 调用前已确认在白名单中 (isPlayerWhitelistedOffline 命中)。
     */
    private void processWhitelistedJoin(ServerPlayer player, String name, String uuid) {
        whitelistManager.getPlayerByUuid(uuid).thenCompose(byUuid -> {
            if (byUuid.isPresent()) {
                handleJoin(player, byUuid.get());
                return CompletableFuture.completedFuture(null);
            }
            // UUID 未命中, 按名字找 (UUID 待补充的条目), 补全 UUID
            return whitelistManager.getPlayerByName(name).thenAccept(byName -> {
                if (byName.isEmpty()) {
                    return;
                }
                WhitelistEntry entry = byName.get();
                if (entry.getUuid() == null || entry.getUuid().trim().isEmpty()) {
                    whitelistManager.updatePlayerUuid(name, uuid).thenAccept(ok -> {
                        if (ok) {
                            logger.info("已为玩家 {} 补充 UUID: {}", name, uuid);
                            handleJoin(player, entry);
                        } else {
                            logger.warn("为玩家 {} 补充 UUID 失败", name);
                        }
                    });
                } else {
                    logger.warn("同名玩家 UUID 不匹配: {} (库内: {}, 当前: {})",
                            name, entry.getUuid(), uuid);
                    handleJoin(player, entry);
                }
            });
        }).exceptionally(t -> {
            logger.error("处理玩家 {} 加入事件失败", name, t);
            return null;
        });
    }

    /** 加入后通知 + 欢迎消息, 统一回主线程执行发包。 */
    private void handleJoin(ServerPlayer player, WhitelistEntry entry) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (config.isJoinNotificationEnabled()) {
                notifyOnlineOps(server, player, entry);
            }
            if (config.isWelcomeMessageEnabled()) {
                sendWelcome(player);
            }
        });
    }

    /** 向在线 OP (权限等级>=2) 广播白名单玩家加入。Forge 无 Bukkit 权限节点, 用 OP 等级替代 v1 的权限节点过滤。 */
    private void notifyOnlineOps(MinecraftServer server, ServerPlayer joined, WhitelistEntry entry) {
        String text = "§a§l[白名单] §e" + joined.getGameProfile().getName()
                + " §7已加入服务器 §8(添加者: " + entry.getAddedByName() + ")";
        Component message = Component.literal(text);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.hasPermissions(2)) {
                online.sendSystemMessage(message);
            }
        }
    }

    /** 向玩家发送可配置欢迎消息, & 颜色码转 §, {player} 占位符替换。 */
    private void sendWelcome(ServerPlayer player) {
        String text = config.getWelcomeMessage()
                .replace("{player}", player.getGameProfile().getName())
                .replace("&", "§");
        player.sendSystemMessage(Component.literal(text));
    }

    private void performWhitelistCheck(PlayerNegotiationEvent event,
                                       String playerName, String playerUuid, String ipAddress) {
        long startedAt = System.currentTimeMillis();
        logger.info("=== 白名单验证: {} ({}) IP: {} ===", playerName, playerUuid, ipAddress);

        try {
            CompletableFuture<AccessDecision> future =
                    whitelistManager.checkAccess(playerName, playerUuid);
            AccessDecision decision = future.get(WHITELIST_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long elapsedMs = System.currentTimeMillis() - startedAt;

            if (elapsedMs >= SLOW_CHECK_WARN_THRESHOLD_MS) {
                logger.warn("[Whitelist] 慢查询: {} 耗时 {}ms (阈值 {}ms, 距离 {}s 超时还有 {}ms)",
                        playerName, elapsedMs, SLOW_CHECK_WARN_THRESHOLD_MS,
                        WHITELIST_CHECK_TIMEOUT_SECONDS,
                        WHITELIST_CHECK_TIMEOUT_SECONDS * 1000L - elapsedMs);
            }

            if (decision == AccessDecision.ALLOWED) {
                logger.info("允许玩家连接 (在白名单中): {} ({}) 耗时 {}ms",
                        playerName, playerUuid, elapsedMs);
                return;
            }

            // 被禁用与不在名单展示不同文案; 统一走 login 阶段安全断连 (先发包再关通道)
            String message = decision == AccessDecision.DISABLED
                    ? formatDisabledMessage(playerName)
                    : formatKickMessage(playerName);
            disconnectDuringLogin(event.getConnection(), Component.literal(message));
            logger.warn("拒绝玩家连接 ({}): {} ({}) IP: {}",
                    decision == AccessDecision.DISABLED ? "白名单被禁用" : "未在白名单",
                    playerName, playerUuid, ipAddress);
            logUnauthorizedAccess(playerName, playerUuid, ipAddress);

        } catch (TimeoutException e) {
            long elapsedMs = System.currentTimeMillis() - startedAt;
            handleQueryFailure(event, playerName, playerUuid, ipAddress,
                    String.format("查询超时 (>=%ds, 实际 %dms) - 异步线程池可能堆积",
                            WHITELIST_CHECK_TIMEOUT_SECONDS, elapsedMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleQueryFailure(event, playerName, playerUuid, ipAddress,
                    "查询被中断: " + e.getMessage());
        } catch (Exception e) {
            handleQueryFailure(event, playerName, playerUuid, ipAddress,
                    "查询异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private void handleQueryFailure(PlayerNegotiationEvent event,
                                    String playerName, String playerUuid, String ipAddress,
                                    String reason) {
        if (config.isWhitelistStrictMode()) {
            disconnectDuringLogin(event.getConnection(), Component.literal("§c白名单验证失败, 请稍后重试"));
            logger.warn("[Whitelist] 严格模式踢出: {} ({}) IP: {} - 原因: {}",
                    playerName, playerUuid, ipAddress, reason);
        } else {
            logger.warn("[Whitelist] 宽松模式放行: {} ({}) - 原因: {}",
                    playerName, playerUuid, reason);
        }
    }

    /**
     * login (协商) 阶段带文案安全断连, 严格镜像原版 ServerLoginPacketListenerImpl#disconnect。
     *
     * 背景: 1.20.1 的 {@link Connection#disconnect(Component)} 只 channel.close() 不发断开包, 客户端只
     * 会看到通用"连接中断"。故须先发 {@link ClientboundLoginDisconnectPacket} 把文案送达, 再关通道。
     *
     * 关键(踩坑): send 与 disconnect 必须在本方法所在的【异步线程】(performWhitelistCheck 跑在
     * ForkJoinPool, 非 netty 事件循环)上【同步顺序】调用 — 绝不能把 disconnect 放进 PacketSendListener
     * 回调。因为该回调在 netty 事件循环线程触发, 而 Connection.disconnect 内部是
     * channel.close().awaitUninterruptibly() (阻塞等待自身 close future); 在事件循环线程上这样阻塞会触发
     * netty BlockingOperationException, 通道被异常关闭, 客户端反而收到 "连接重置/Connection reset"。
     * 同线程顺序调用则: send 把写任务排进事件循环, disconnect 的 close 任务紧随其后, 事件循环按序先刷包再关闭,
     * awaitUninterruptibly 阻塞的只是本异步线程(无害)。
     */
    private void disconnectDuringLogin(Connection connection, Component reason) {
        try {
            connection.send(new ClientboundLoginDisconnectPacket(reason));
            connection.disconnect(reason);
        } catch (Exception e) {
            logger.error("登录阶段带文案断连失败", e);
        }
    }

    private String formatKickMessage(String playerName) {
        return config.getWhitelistKickMessage()
                .replace("{player}", playerName)
                .replace("{contact}", config.getContactInfo())
                .replace("&", "§");
    }

    /** 在白名单但被管理员手动禁用时的提示文案。 */
    private String formatDisabledMessage(String playerName) {
        return config.getWhitelistDisabledMessage()
                .replace("{player}", playerName)
                .replace("{contact}", config.getContactInfo())
                .replace("&", "§");
    }

    private String formatRemoteAddress(SocketAddress addr) {
        if (addr instanceof InetSocketAddress inet) {
            InetAddress address = inet.getAddress();
            return address != null ? address.getHostAddress() : inet.getHostString();
        }
        return addr != null ? addr.toString() : "unknown";
    }

    /**
     * 写入 operation_log 表的 UNAUTHORIZED_ACCESS 审计条目.
     * 直接走 databaseManager 而非 OperationLogDao, 因为 DAO 暂无对应方法, 沿用 v1 实现.
     */
    private void logUnauthorizedAccess(String playerName, String playerUuid, String ipAddress) {
        databaseManager.executeAsync(connection -> {
            String sql = """
                    INSERT INTO operation_log
                    (operation_type, target_uuid, target_name, operator_ip, operator_agent,
                     request_data, response_status, execution_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, "UNAUTHORIZED_ACCESS");
                ps.setString(2, playerUuid);
                ps.setString(3, playerName);
                ps.setString(4, ipAddress);
                ps.setString(5, "Minecraft Client");
                ps.setString(6, String.format(
                        "{\"reason\":\"not_in_whitelist\",\"player\":\"%s\",\"uuid\":\"%s\"}",
                        playerName, playerUuid));
                ps.setInt(7, 403);
                ps.setLong(8, 0);
                return ps.executeUpdate();
            }
        }).exceptionally(throwable -> {
            logger.warn("写入未授权访问审计日志失败: {} ({})", playerName, playerUuid, throwable);
            return 0;
        });
    }
}
