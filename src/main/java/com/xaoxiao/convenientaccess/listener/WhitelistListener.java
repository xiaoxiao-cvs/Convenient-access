package com.xaoxiao.convenientaccess.listener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import com.xaoxiao.convenientaccess.whitelist.WhitelistManager;

/**
 * 白名单监听器
 * 监听玩家连接事件，验证白名单并阻止未授权玩家进入
 */
public class WhitelistListener implements Listener {
    private static final Logger logger = LoggerFactory.getLogger(WhitelistListener.class);

    // PreLogin 阶段白名单查询超时（秒）
    // 包含异步线程池排队 + DB 查询全部时间。配置过低会在高负载时误踢真实白名单玩家
    private static final int WHITELIST_CHECK_TIMEOUT_SECONDS = 15;
    // 超过该阈值的查询会触发警告日志，便于发现线程池堆积 / DB 慢查询
    private static final long SLOW_CHECK_WARN_THRESHOLD_MS = 1500;

    private final ConvenientAccessPlugin plugin;
    private final WhitelistManager whitelistManager;



    public WhitelistListener(ConvenientAccessPlugin plugin) {
        this.plugin = plugin;
        this.whitelistManager = plugin.getWhitelistSystem().getWhitelistManager();
    }

    /**
     * 处理玩家预登录事件（异步）
     * 在玩家实际进入服务器前检查白名单
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        String playerName = event.getName();
        String playerUuid = event.getUniqueId().toString();
        String ipAddress = event.getAddress().getHostAddress();

        logger.info("=== 白名单验证开始 ===");
        logger.info("玩家: {} ({})", playerName, playerUuid);
        logger.info("IP地址: {}", ipAddress);

        long startedAt = System.currentTimeMillis();
        try {
            // 检查白名单系统是否已初始化
            boolean isSystemInitialized = plugin.getWhitelistSystem().isInitialized();
            logger.info("白名单系统初始化状态: {}", isSystemInitialized);

            if (!isSystemInitialized) {
                logger.warn("❌ 白名单系统未初始化，允许玩家 {} 进入", playerName);
                return;
            }

            // 检查配置是否启用白名单
            boolean isWhitelistEnabled = plugin.getConfigManager().isWhitelistEnabled();
            logger.info("白名单功能启用状态: {}", isWhitelistEnabled);

            if (!isWhitelistEnabled) {
                logger.info("✅ 白名单功能已禁用，允许玩家 {} 进入", playerName);
                return;
            }

            // 检查白名单管理器状态
            int cacheSize = whitelistManager.getCacheSize();
            logger.info("白名单缓存大小: {}", cacheSize);

            // 异步检查玩家是否在白名单中
            logger.info("开始检查玩家白名单状态...");

            // 使用离线模式检查（同时检查用户名和UUID）
            CompletableFuture<Boolean> whitelistCheck = whitelistManager.isPlayerWhitelistedOffline(playerName, playerUuid);

            Boolean isWhitelisted = whitelistCheck.get(WHITELIST_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long elapsedMs = System.currentTimeMillis() - startedAt;
            logger.info("白名单检查结果: {} (耗时 {}ms)", isWhitelisted, elapsedMs);

            // 慢查询警告 — 同时写到 Bukkit 主 console，便于发现线程池堆积征兆
            if (elapsedMs >= SLOW_CHECK_WARN_THRESHOLD_MS) {
                plugin.getLogger().warning(String.format(
                    "[Whitelist] 慢查询: %s 耗时 %dms (阈值 %dms) — 可能预示线程池堆积，距离 %ds 超时还有 %dms",
                    playerName, elapsedMs, SLOW_CHECK_WARN_THRESHOLD_MS,
                    WHITELIST_CHECK_TIMEOUT_SECONDS, WHITELIST_CHECK_TIMEOUT_SECONDS * 1000L - elapsedMs));
            }

            if (!isWhitelisted) {
                // 玩家不在白名单中，拒绝连接
                String kickMessage = getCustomKickMessage(playerName);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, kickMessage);

                logger.warn("❌ 拒绝玩家连接（未在白名单中）: {} ({})", playerName, playerUuid);
                logger.info("踢出消息: {}", kickMessage);

                // 记录操作日志
                logUnauthorizedAccess(playerName, playerUuid, ipAddress);
            } else {
                logger.info("✅ 允许玩家连接（已在白名单中）: {} ({})", playerName, playerUuid);
            }

        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException | InterruptedException e) {
            long elapsedMs = System.currentTimeMillis() - startedAt;
            logger.error("❌ 检查玩家白名单状态时发生错误 (耗时 {}ms): {} ({})", elapsedMs, playerName, playerUuid, e);

            // 发生错误时的处理策略
            boolean strictMode = plugin.getConfigManager().isWhitelistStrictMode();
            logger.info("严格模式状态: {}", strictMode);

            // 关键: 这条 WARN 必须落到 Bukkit 主 console，确保运维能看到 — SLF4J error 在某些桥接环境下可能被吞
            String reason = e instanceof java.util.concurrent.TimeoutException
                ? String.format("查询超时 (>=%ds, 实际 %dms) — 异步线程池可能堆积",
                                WHITELIST_CHECK_TIMEOUT_SECONDS, elapsedMs)
                : "查询异常: " + e.getClass().getSimpleName() + " - " + e.getMessage();

            if (strictMode) {
                // 严格模式：发生错误时拒绝连接
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "§c白名单验证失败，请稍后重试"
                );
                plugin.getLogger().warning(String.format(
                    "[Whitelist] 严格模式踢出: %s (%s, IP: %s) — 原因: %s",
                    playerName, playerUuid, ipAddress, reason));
                logger.warn("❌ 严格模式下拒绝玩家连接（白名单验证失败）: {}", playerName);
            } else {
                plugin.getLogger().warning(String.format(
                    "[Whitelist] 宽松模式放行: %s (%s) — 原因: %s",
                    playerName, playerUuid, reason));
                logger.warn("⚠️ 宽松模式下允许玩家连接（白名单验证失败）: {}", playerName);
            }
        } finally {
            logger.info("=== 白名单验证结束 (总耗时 {}ms) ===", System.currentTimeMillis() - startedAt);
        }
    }
    
    /**
     * 处理玩家加入事件
     * 发送欢迎消息给白名单玩家，并补充UUID（如果缺失）
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String playerUuid = player.getUniqueId().toString();
        
        logger.info("玩家 {} ({}) 加入服务器", playerName, playerUuid);
        
        // 先尝试通过UUID查找
        whitelistManager.getPlayerByUuid(playerUuid).thenCompose(uuidEntry -> {
            if (uuidEntry.isPresent()) {
                // 通过UUID找到了，正常处理
                logger.debug("通过UUID找到白名单条目: {}", playerName);
                handlePlayerJoinNotifications(player, uuidEntry.get());
                return CompletableFuture.completedFuture(null);
            }
            
            // 通过UUID没找到，尝试通过玩家名查找（可能UUID为空）
            return whitelistManager.getPlayerByName(playerName).thenCompose(nameEntry -> {
                if (nameEntry.isPresent()) {
                    com.xaoxiao.convenientaccess.whitelist.WhitelistEntry entry = nameEntry.get();
                    if (entry.getUuid() == null || entry.getUuid().trim().isEmpty()) {
                        // 找到了基于名称的条目但UUID为空，需要补充UUID
                        logger.info("为玩家 {} 补充UUID: {}", playerName, playerUuid);
                        return whitelistManager.updatePlayerUuid(playerName, playerUuid).thenAccept(success -> {
                            if (success) {
                                logger.info("✅ 成功为玩家 {} 补充UUID: {}", playerName, playerUuid);
                                
                                // 发送通知消息
                                handlePlayerJoinNotifications(player, entry);
                                // 向玩家发送UUID补充成功的消息
                                player.sendMessage(ChatColor.GREEN + "欢迎回来！您的玩家信息已更新。");
                            } else {
                                logger.warn("❌ 为玩家 {} 补充UUID失败", playerName);
                            }
                        });
                    } else {
                        // UUID不为空但不匹配，可能是重名玩家？
                        logger.warn("⚠️ 发现同名玩家但UUID不匹配: {} (数据库UUID: {}, 当前UUID: {})", 
                                  playerName, entry.getUuid(), playerUuid);
                        handlePlayerJoinNotifications(player, entry);
                    }
                } else {
                    // 名称也没找到，说明玩家不在白名单中（但能进入说明白名单检查通过了）
                    logger.debug("玩家 {} 不在白名单中或白名单功能已禁用", playerName);
                }
                return CompletableFuture.completedFuture(null);
            });
        }).exceptionally(throwable -> {
            logger.error("处理玩家加入事件时发生错误: {}", playerName, throwable);
            return null;
        });
    }
    
    /**
     * 处理玩家加入通知（提取的公共方法）
     */
    private void handlePlayerJoinNotifications(Player player, com.xaoxiao.convenientaccess.whitelist.WhitelistEntry entry) {
        // 向管理员发送玩家加入通知
        if (plugin.getConfigManager().isJoinNotificationEnabled()) {
            sendJoinNotificationToAdmins(player, entry);
        }
        
        // 向玩家发送自定义欢迎消息
        if (plugin.getConfigManager().isWelcomeMessageEnabled()) {
            sendWelcomeMessage(player);
        }
    }
    
    /**
     * 获取自定义踢出消息
     */
    private String getCustomKickMessage(String playerName) {
        String template = plugin.getConfigManager().getWhitelistKickMessage();
        
        // 替换占位符
        return template
            .replace("{player}", playerName)
            .replace("{server}", plugin.getServer().getName())
            .replace("{contact}", plugin.getConfigManager().getContactInfo())
            .replace("&", "§"); // 支持颜色代码
    }
    
    /**
     * 记录未授权访问
     */
    private void logUnauthorizedAccess(String playerName, String playerUuid, String ipAddress) {
        logger.warn("未授权访问尝试 - 玩家: {} ({}), IP: {}", playerName, playerUuid, ipAddress);
        
        // 异步写入数据库操作日志
        plugin.getWhitelistSystem().getDatabaseManager().executeAsync(connection -> {
            try {
                String sql = """
                    INSERT INTO operation_log 
                    (operation_type, target_uuid, target_name, operator_ip, operator_agent, 
                     request_data, response_status, execution_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
                
                try (var pstmt = connection.prepareStatement(sql)) {
                    pstmt.setString(1, "UNAUTHORIZED_ACCESS");
                    pstmt.setString(2, playerUuid);
                    pstmt.setString(3, playerName);
                    pstmt.setString(4, ipAddress);
                    pstmt.setString(5, "Minecraft Client"); // 游戏客户端
                    pstmt.setString(6, String.format("{\"reason\":\"not_in_whitelist\",\"player\":\"%s\",\"uuid\":\"%s\"}", 
                                                     playerName, playerUuid));
                    pstmt.setInt(7, 403); // HTTP 403 Forbidden 表示拒绝访问
                    pstmt.setLong(8, 0); // 不需要记录执行时间
                    
                    int affected = pstmt.executeUpdate();
                    if (affected > 0) {
                        logger.info("✅ 已记录未授权访问日志: {} ({})", playerName, ipAddress);
                    } else {
                        logger.warn("❌ 记录未授权访问日志失败: {}", playerName);
                    }
                }
                
                return null;
            } catch (java.sql.SQLException e) {
                logger.error("记录未授权访问日志时发生SQL异常: {} ({})", playerName, ipAddress, e);
                return null;
            }
        });
    }
    
    /**
     * 向管理员发送玩家加入通知
     */
    private void sendJoinNotificationToAdmins(Player player, com.xaoxiao.convenientaccess.whitelist.WhitelistEntry entry) {
        String notification = ChatColor.GREEN + "" + ChatColor.BOLD + "[白名单] " + 
                            ChatColor.YELLOW + player.getName() + 
                            ChatColor.GRAY + " 已加入服务器 " +
                            ChatColor.DARK_GRAY + "(添加者: " + entry.getAddedByName() + ")";
        
        // 发送给有权限的管理员
        String permission = plugin.getConfigManager().getJoinNotificationPermission();
        plugin.getServer().getOnlinePlayers().stream()
            .filter(p -> p.hasPermission(permission))
            .forEach(admin -> admin.sendMessage(notification));
    }
    
    /**
     * 发送欢迎消息给玩家
     */
    private void sendWelcomeMessage(Player player) {
        String welcomeTemplate = plugin.getConfigManager().getWelcomeMessage();
        String welcomeMessage = welcomeTemplate
            .replace("{player}", player.getName())
            .replace("{server}", plugin.getServer().getName())
            .replace("&", "§");
        
        // 延迟1秒发送，确保玩家完全加入
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            player.sendMessage(welcomeMessage);
        }, 20L);
    }
}