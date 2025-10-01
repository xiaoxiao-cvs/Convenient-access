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
        
        logger.debug("检查玩家白名单状态: {} ({})", playerName, playerUuid);
        
        try {
            // 检查白名单系统是否已初始化
            if (!plugin.getWhitelistSystem().isInitialized()) {
                logger.warn("白名单系统未初始化，允许玩家 {} 进入", playerName);
                return;
            }
            
            // 检查配置是否启用白名单
            if (!plugin.getConfigManager().isWhitelistEnabled()) {
                logger.debug("白名单功能已禁用，允许玩家 {} 进入", playerName);
                return;
            }
            
            // 异步检查玩家是否在白名单中
            CompletableFuture<Boolean> whitelistCheck = whitelistManager.isPlayerWhitelisted(playerUuid);
            
            // 等待结果（设置合理的超时时间）
            Boolean isWhitelisted = whitelistCheck.get(5, TimeUnit.SECONDS);
            
            if (!isWhitelisted) {
                // 玩家不在白名单中，拒绝连接
                String kickMessage = getCustomKickMessage(playerName);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, kickMessage);
                
                logger.info("拒绝玩家连接（未在白名单中）: {} ({})", playerName, playerUuid);
                
                // 记录操作日志
                logUnauthorizedAccess(playerName, playerUuid, event.getAddress().getHostAddress());
            } else {
                logger.info("允许玩家连接（已在白名单中）: {} ({})", playerName, playerUuid);
            }
            
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException | InterruptedException e) {
            logger.error("检查玩家白名单状态时发生错误: {} ({})", playerName, playerUuid, e);
            
            // 发生错误时的处理策略
            if (plugin.getConfigManager().isWhitelistStrictMode()) {
                // 严格模式：发生错误时拒绝连接
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER, 
                    "§c白名单验证失败，请稍后重试"
                );
                logger.warn("严格模式下拒绝玩家连接（白名单验证失败）: {}", playerName);
            } else {
                // 宽松模式：发生错误时允许连接
                logger.warn("宽松模式下允许玩家连接（白名单验证失败）: {}", playerName);
            }
        }
    }
    
    /**
     * 处理玩家加入事件
     * 发送欢迎消息给白名单玩家
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerUuid = player.getUniqueId().toString();
        
        // 异步检查玩家信息并发送消息
        whitelistManager.getPlayerByUuid(playerUuid).thenAccept(entryOpt -> {
            if (entryOpt.isPresent()) {
                // 向管理员发送玩家加入通知
                if (plugin.getConfigManager().isJoinNotificationEnabled()) {
                    sendJoinNotificationToAdmins(player, entryOpt.get());
                }
                
                // 向玩家发送自定义欢迎消息
                if (plugin.getConfigManager().isWelcomeMessageEnabled()) {
                    sendWelcomeMessage(player);
                }
            }
        }).exceptionally(throwable -> {
            logger.error("处理玩家加入事件时发生错误: {}", player.getName(), throwable);
            return null;
        });
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
        
        // TODO: 可以扩展为写入数据库操作日志
        // plugin.getDatabaseManager().executeAsync(connection -> {
        //     // 插入操作日志
        //     return null;
        // });
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