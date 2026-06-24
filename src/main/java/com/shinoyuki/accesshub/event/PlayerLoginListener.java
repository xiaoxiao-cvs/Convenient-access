package com.shinoyuki.accesshub.event;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
 * Forge 白名单事件监听器, 替代 v1 Bukkit WhitelistListener. 双层拦截:
 *
 * 1. {@link PlayerNegotiationEvent}(LOGIN 协商阶段, best-effort): 在玩家进入世界前于"正在登录"界面拒绝,
 *    像 Bukkit AsyncPlayerPreLoginEvent 那样不加载地形。仅在确定性"拒绝"决策时动作; 查询超时/异常一律
 *    放行交 PLAY 兜底。该 login 阶段断连在重度整合包(含 packetfixer/xlpackets 包管线 mixin)下能否被客户端
 *    渲染出文案并不可靠 — 故只当"尽力而为的提前拦截", 不作为唯一防线。
 * 2. {@link PlayerEvent.PlayerLoggedInEvent}(PLAY 阶段, 核心事件必然触发, 唯一可靠防线): 玩家若走到 PLAY
 *    (协商未 fire / 协商断连未真正关闭连接), 用原版 {@code player.connection.disconnect} 延迟数秒踢出,
 *    文案稳定可见 (见 {@link #REJECT_DELAY_SECONDS})。
 *
 * 不会双踢: 协商成功关闭连接 -> PlayerLoggedInEvent 永不触发 (Forge 生命周期保证); 协商未关闭 -> 只有 PLAY 生效。
 * 断连写法的踩坑史与正确姿势见 disconnectDuringLogin 注释 / [[forge-login-disconnect-gotcha]]。
 */
public final class PlayerLoginListener {

    private static final Logger logger = LoggerFactory.getLogger(PlayerLoginListener.class);

    /** 协商阶段白名单查询超时. 超时/异常一律不在协商阶段动作, 交 PLAY 阶段兜底 (含异步线程池排队 + DB 查询)。 */
    private static final int NEGOTIATION_CHECK_TIMEOUT_SECONDS = 10;

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
     * 协商阶段 (LOGIN) best-effort 提前拦截。在 enqueueWork 的异步线程做白名单查询; 仅当确定性"拒绝"时
     * 于登录界面断连 (不进地形)。查询超时/异常/放行一律不在此动作, 交 PLAY 阶段 (PlayerLoggedInEvent) 兜底。
     */
    @SubscribeEvent
    public void onPlayerNegotiation(PlayerNegotiationEvent event) {
        if (!config.isWhitelistEnabled()) {
            return;
        }
        GameProfile profile = event.getProfile();
        // 协商阶段(离线模式)UUID 常未解析(id=null), 但玩家名已有; 白名单按名查即可 (checkAccess 支持 name-only)。
        // 故只要求有名字, 不再要求 UUID — 这是之前协商拦截"从不动作"的真正原因。
        if (profile == null || profile.getName() == null) {
            return; // 连玩家名都没有才放弃, 交 PLAY 兜底
        }
        String playerName = profile.getName();
        String playerUuid = profile.getId() != null ? profile.getId().toString() : null;
        String ipAddress = formatRemoteAddress(event.getConnection().getRemoteAddress());

        CompletableFuture<Void> check = CompletableFuture.runAsync(
                () -> performNegotiationCheck(event, playerName, playerUuid, ipAddress));
        event.enqueueWork(check);
    }

    /** 协商阶段查询并(仅在确定拒绝时)断连。本方法跑在 ForkJoinPool 异步线程 (非 netty 事件循环), 这点对 disconnectDuringLogin 的安全性至关重要。 */
    private void performNegotiationCheck(PlayerNegotiationEvent event,
                                         String playerName, String playerUuid, String ipAddress) {
        logger.info("[协商诊断] performNegotiationCheck 开始: {} ({})", playerName, playerUuid);
        try {
            AccessDecision decision = whitelistManager.checkAccess(playerName, playerUuid)
                    .get(NEGOTIATION_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            logger.info("[协商诊断] 决策={}: {} ({})", decision, playerName, playerUuid);
            if (decision == AccessDecision.ALLOWED) {
                return; // 放行: 玩家继续走到 PLAY, 由 PlayerLoggedInEvent 做加入后处理
            }
            String message = decision == AccessDecision.DISABLED
                    ? formatDisabledMessage(playerName)
                    : formatKickMessage(playerName);
            disconnectDuringLogin(event.getConnection(), Component.literal(message));
            logger.warn("协商阶段拒绝 ({}): {} ({}) IP: {}",
                    decision == AccessDecision.DISABLED ? "白名单被禁用" : "未在白名单",
                    playerName, playerUuid, ipAddress);
            logUnauthorizedAccess(playerName, playerUuid, ipAddress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[Whitelist] 协商阶段查询被中断, 交 PLAY 兜底: {} ({})", playerName, playerUuid);
        } catch (Exception e) {
            // 查询超时/异常: 不在协商阶段动作 (避免误踢), 放行交 PLAY 阶段处理 (含严格模式)。
            logger.warn("[Whitelist] 协商阶段查询未决, 交 PLAY 兜底: {} ({}) - {}",
                    playerName, playerUuid, e.getClass().getSimpleName());
        }
    }

    /**
     * login (协商) 阶段带文案断连。严格镜像原版 ServerLoginPacketListenerImpl#disconnect: 同线程顺序
     * send(ClientboundLoginDisconnectPacket) 再 connection.disconnect。
     *
     * 线程安全的唯一正确写法 (已对照 1.20.1 官方映射源码核实, 见 [[forge-login-disconnect-gotcha]]):
     * 本方法必须在【非 netty 事件循环线程】(performNegotiationCheck 跑在 ForkJoinPool) 上同步顺序调用。
     *  - Connection.send 用 writeAndFlush 且 off-loop 时把写任务排进事件循环; 紧随的 disconnect 之 close 任务
     *    排在其后, 事件循环 FIFO 保证"先刷断开包, 后关通道"。awaitUninterruptibly 阻塞的只是本异步线程(无害)。
     *  - 绝不可用 PacketSendListener.thenRun(...) 包 disconnect (c6f260d), 也绝不可用 channel.eventLoop().execute(disconnect):
     *    二者都把 disconnect 搬到事件循环线程, 而 disconnect 内部 channel.close().awaitUninterruptibly() 在事件循环
     *    线程上等待自身 close future -> netty BlockingOperationException -> 通道异常关闭 -> 客户端 "连接重置"。
     *
     * 注意: 即便写法正确, 在含 packetfixer/xlpackets 的整合包里客户端能否渲染该早期断开包仍不确定; 失败时连接会
     * 走到 PLAY 阶段由 PlayerLoggedInEvent 延迟踢兜底 (文案可见)。故本拦截是 best-effort, 非唯一防线。
     */
    private void disconnectDuringLogin(Connection connection, Component reason) {
        try {
            connection.send(new ClientboundLoginDisconnectPacket(reason));
            connection.disconnect(reason);
        } catch (Exception e) {
            logger.error("协商阶段带文案断连失败, 交 PLAY 阶段兜底", e);
        }
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
