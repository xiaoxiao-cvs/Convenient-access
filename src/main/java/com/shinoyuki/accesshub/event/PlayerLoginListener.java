package com.shinoyuki.accesshub.event;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.database.DatabaseManager;
import com.shinoyuki.accesshub.whitelist.AccessDecision;
import com.shinoyuki.accesshub.whitelist.WhitelistEntry;
import com.shinoyuki.accesshub.whitelist.WhitelistManager;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 白名单事件监听器, 替代 v1 Bukkit WhitelistListener.
 *
 * 仅在 {@link PlayerEvent.PlayerLoggedInEvent}(PLAY 阶段, 核心事件必然触发)做拦截:
 * 不在白名单 / 被管理员手动禁用的玩家进服瞬间用 {@code player.connection.disconnect(...)}
 * (原版 ServerGamePacketListenerImpl, 会先发 ClientboundDisconnectPacket 再关通道)踢出, 文案能正常显示。
 *
 * 历史: 曾另在 PlayerNegotiationEvent(LOGIN 协商阶段)提前拦截, 但该 login 阶段断连在本环境
 * (含 packetfixer/xlpackets 等包管线 mixin)极不可靠 — 表现为客户端只看到 "连接中断 / Connection reset"
 * 而非踢出文案, 且与 PLAY 阶段拦截存在双重断连竞态。故移除 login 阶段断连, 统一由 PLAY 阶段拦截,
 * 代价仅是被拒玩家会"进服一瞬再被踢"(可接受), 换取文案稳定可见。详见 [[forge-login-disconnect-gotcha]]。
 *
 * 保留的加固行为:
 *  - 双 key 缓存查询 (UUID + name) - 由 WhitelistManager.checkAccess 自身实现
 *  - 严格模式: 查询异常默认踢人, 配置可关 (whitelist.strict-mode)
 *  - operation_log 表记录 UNAUTHORIZED_ACCESS 审计条目
 */
public final class PlayerLoginListener {

    private static final Logger logger = LoggerFactory.getLogger(PlayerLoginListener.class);

    /**
     * 被拒玩家延迟踢出秒数。在 join tick 立即踢, 客户端尚在进服序列中途 (接收区块/各 mod 同步配置),
     * 收到断开包只显示通用"连接中断"而非踢出文案 (实测: 服务端已正确下发文案但客户端不渲染)。延迟数秒待
     * 客户端完全进入 PLAY 再踢, 等价正常 /kick, 文案稳定可见。重度整合包加载慢, 取较宽裕的冗余值。
     */
    private static final long REJECT_DELAY_SECONDS = 3;

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
        UUID uuidObj = player.getUUID();
        String uuid = uuidObj.toString();
        String ip = formatRemoteAddress(player.connection.connection.getRemoteAddress());
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        // 唯一拦截点 (PlayerLoggedInEvent 为核心事件, 任何环境必然触发). 已弃用 LOGIN 协商阶段提前拦截 (见类注释)。
        // 放行: 主线程处理加入; 拒绝: 延迟踢出 (rejectAfterJoin), 等客户端完全进服后再踢, 文案方能显示。
        whitelistManager.checkAccess(name, uuid).thenAccept(decision -> {
            if (decision == AccessDecision.ALLOWED) {
                server.execute(() -> processWhitelistedJoin(player, name, uuid));
                return;
            }
            String message = decision == AccessDecision.DISABLED
                    ? formatDisabledMessage(name)
                    : formatKickMessage(name);
            String reasonTag = decision == AccessDecision.DISABLED ? "白名单被禁用" : "未在白名单";
            rejectAfterJoin(server, uuidObj, name, ip, Component.literal(message), reasonTag);
        }).exceptionally(t -> {
            // 查询异常: 严格模式踢人, 宽松模式放行
            if (config.isWhitelistStrictMode()) {
                rejectAfterJoin(server, uuidObj, name, ip,
                        Component.literal("§c白名单验证失败, 请稍后重试"), "查询异常");
                logger.warn("[Whitelist] 严格模式踢出 (查询异常): {} - {}", name, t.getMessage());
            } else {
                logger.warn("[Whitelist] 宽松模式放行 (查询异常): {} - {}", name, t.getMessage());
            }
            return null;
        });
    }

    /**
     * 延迟 {@link #REJECT_DELAY_SECONDS} 秒后踢出被拒玩家。延迟原因见该常量注释:
     * join tick 立即踢会让客户端只显示"连接中断"而非文案。延迟后等价一次正常 /kick。
     * 踢出前按 UUID 重新取在线玩家 (期间可能已自行离开/换对象), 并校验 server 仍在运行。
     */
    private void rejectAfterJoin(MinecraftServer server, UUID uuid, String name, String ip,
                                 Component message, String reasonTag) {
        CompletableFuture.delayedExecutor(REJECT_DELAY_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (!server.isRunning()) {
                return;
            }
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                if (online == null) {
                    return; // 期间已自行离开
                }
                online.connection.disconnect(message);
                logger.warn("拒绝玩家进入 ({}): {} ({}) IP: {}", reasonTag, name, uuid, ip);
                logUnauthorizedAccess(name, uuid.toString(), ip);
            });
        });
    }

    /**
     * 白名单内玩家的加入后处理: UUID 补全 + 欢迎 + 通知 (对应 v1 onPlayerJoin)。
     * 调用前已确认在白名单中 (checkAccess 返回 ALLOWED)。
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
