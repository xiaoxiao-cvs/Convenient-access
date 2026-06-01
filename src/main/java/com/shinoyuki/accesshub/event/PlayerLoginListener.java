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
import com.shinoyuki.accesshub.whitelist.WhitelistEntry;
import com.shinoyuki.accesshub.whitelist.WhitelistManager;

import net.minecraft.network.chat.Component;
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

        whitelistManager.getPlayerByUuid(uuid).thenCompose(byUuid -> {
            if (byUuid.isPresent()) {
                handleJoin(player, byUuid.get());
                return CompletableFuture.completedFuture(null);
            }
            // UUID 未命中, 尝试按名字找 (可能是 UUID 待补充的条目)
            return whitelistManager.getPlayerByName(name).thenAccept(byName -> {
                if (byName.isEmpty()) {
                    // 不在白名单却能进服: 白名单未启用或被放行, 不处理
                    logger.debug("玩家 {} 不在白名单, 跳过加入处理", name);
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
            CompletableFuture<Boolean> future =
                    whitelistManager.isPlayerWhitelistedOffline(playerName, playerUuid);
            Boolean isWhitelisted = future.get(WHITELIST_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long elapsedMs = System.currentTimeMillis() - startedAt;

            if (elapsedMs >= SLOW_CHECK_WARN_THRESHOLD_MS) {
                logger.warn("[Whitelist] 慢查询: {} 耗时 {}ms (阈值 {}ms, 距离 {}s 超时还有 {}ms)",
                        playerName, elapsedMs, SLOW_CHECK_WARN_THRESHOLD_MS,
                        WHITELIST_CHECK_TIMEOUT_SECONDS,
                        WHITELIST_CHECK_TIMEOUT_SECONDS * 1000L - elapsedMs);
            }

            if (Boolean.TRUE.equals(isWhitelisted)) {
                logger.info("允许玩家连接 (在白名单中): {} ({}) 耗时 {}ms",
                        playerName, playerUuid, elapsedMs);
                return;
            }

            String kickMessage = formatKickMessage(playerName);
            event.getConnection().disconnect(Component.literal(kickMessage));
            logger.warn("拒绝玩家连接 (未在白名单): {} ({}) IP: {}",
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
            event.getConnection().disconnect(Component.literal("§c白名单验证失败, 请稍后重试"));
            logger.warn("[Whitelist] 严格模式踢出: {} ({}) IP: {} - 原因: {}",
                    playerName, playerUuid, ipAddress, reason);
        } else {
            logger.warn("[Whitelist] 宽松模式放行: {} ({}) - 原因: {}",
                    playerName, playerUuid, reason);
        }
    }

    private String formatKickMessage(String playerName) {
        return config.getWhitelistKickMessage()
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
