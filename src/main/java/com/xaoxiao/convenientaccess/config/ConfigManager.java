package com.xaoxiao.convenientaccess.config;

import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;

/**
 * 配置管理器
 * 负责管理插件的所有配置项
 */
public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    
    private final ConvenientAccessPlugin plugin;
    private FileConfiguration config;
    
    public ConfigManager(ConvenientAccessPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    /**
     * 加载配置文件
     */
    private void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        
        // 自动添加缺失的配置项
        autoUpdateConfig();
    }
    
    /**
     * 自动更新配置文件
     * 添加新版本中引入的配置项,同时保留用户的现有配置
     */
    private void autoUpdateConfig() {
        boolean updated = false;
        
        // 检查并添加登录失败限制配置 (v0.5.0新增)
        if (!config.contains("api.auth.login-attempt-limit.enabled")) {
            config.set("api.auth.login-attempt-limit.enabled", true);
            logger.info("添加配置项: api.auth.login-attempt-limit.enabled = true");
            updated = true;
        }
        if (!config.contains("api.auth.login-attempt-limit.max-attempts")) {
            config.set("api.auth.login-attempt-limit.max-attempts", 5);
            logger.info("添加配置项: api.auth.login-attempt-limit.max-attempts = 5");
            updated = true;
        }
        if (!config.contains("api.auth.login-attempt-limit.lock-duration-minutes")) {
            config.set("api.auth.login-attempt-limit.lock-duration-minutes", 15);
            logger.info("添加配置项: api.auth.login-attempt-limit.lock-duration-minutes = 15");
            updated = true;
        }
        
        // 如果有更新,保存配置文件
        if (updated) {
            plugin.saveConfig();
            logger.info("✅ 配置文件已自动更新,添加了新的配置项 (登录失败限制功能)");
        }
    }
    
    /**
     * 重载配置
     */
    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }
    
    // HTTP服务器配置
    public boolean isHttpEnabled() {
        return config.getBoolean("http.enabled", true);
    }
    
    public int getHttpPort() {
        return config.getInt("http.port", 22222);
    }
    
    public String getHttpHost() {
        return config.getString("http.host", "0.0.0.0");
    }
    
    public int getMaxThreads() {
        return config.getInt("http.max-threads", 10);
    }
    
    public int getTimeout() {
        return config.getInt("http.timeout", 30000);
    }
    
    // API配置
    public String getApiVersion() {
        return config.getString("api.version", "v1");
    }
    
    /**
     * 是否启用API鉴权
     */
    public boolean isAuthEnabled() {
        return config.getBoolean("api.auth.enabled", true);
    }
    
    /**
     * 获取API访问令牌
     */
    public String getApiToken() {
        return config.getString("api.auth.api-token", "");
    }
    
    /**
     * 设置API访问令牌到配置文件
     */
    public void setApiToken(String token) {
        config.set("api.auth.api-token", token);
        plugin.saveConfig();
    }
    
    /**
     * 获取令牌前缀
     */
    public String getTokenPrefix() {
        return config.getString("api.auth.token-prefix", "sk-");
    }
    
    /**
     * 获取管理员密码
     */
    public String getAdminPassword() {
        return config.getString("api.auth.admin-password", "");
    }
    
    /**
     * 设置管理员密码到配置文件
     */
    public void setAdminPassword(String password) {
        config.set("api.auth.admin-password", password);
        plugin.saveConfig();
    }
    
    /**
     * 是否启用登录失败限制
     */
    public boolean isLoginAttemptLimitEnabled() {
        return config.getBoolean("api.auth.login-attempt-limit.enabled", true);
    }
    
    /**
     * 获取最大登录失败次数
     */
    public int getLoginMaxAttempts() {
        return config.getInt("api.auth.login-attempt-limit.max-attempts", 5);
    }
    
    /**
     * 获取账号锁定时长(分钟)
     */
    public int getLoginLockDurationMinutes() {
        return config.getInt("api.auth.login-attempt-limit.lock-duration-minutes", 15);
    }

    public boolean isRateLimitEnabled() {
        return config.getBoolean("api.rate-limit.enabled", true);
    }
    
    public int getRequestsPerMinute() {
        return config.getInt("api.rate-limit.requests-per-minute", 60);
    }
    
    public boolean isCorsEnabled() {
        return config.getBoolean("api.cors.enabled", true);
    }
    
    public List<String> getAllowedOrigins() {
        return config.getStringList("api.cors.allowed-origins");
    }
    
    // 缓存配置
    public int getServerInfoCacheTime() {
        return config.getInt("cache.server-info", 300);
    }
    
    public int getPerformanceCacheTime() {
        return config.getInt("cache.performance", 5);
    }
    
    public int getPlayersCacheTime() {
        return config.getInt("cache.players", 10);
    }
    
    public int getWorldsCacheTime() {
        return config.getInt("cache.worlds", 60);
    }
    
    // Spark配置
    public boolean isPreferSpark() {
        return config.getBoolean("spark.prefer-spark", true);
    }
    
    public int getSparkTimeout() {
        return config.getInt("spark.timeout", 5000);
    }
    
    // 白名单配置
    public boolean isWhitelistEnabled() {
        return config.getBoolean("whitelist.enabled", true);
    }
    
    public boolean isWhitelistStrictMode() {
        return config.getBoolean("whitelist.strict-mode", false);
    }
    
    public String getWhitelistKickMessage() {
        return config.getString("whitelist.kick-message", 
            "&c您不在服务器白名单中！\n&7请联系管理员申请加入白名单");
    }
    
    public String getContactInfo() {
        return config.getString("whitelist.contact-info", "请联系管理员");
    }
    
    public int getTokenExpiryHours() {
        return config.getInt("whitelist.token-expiry-hours", 24);
    }
    
    public boolean isAutoCleanupTokens() {
        return config.getBoolean("whitelist.auto-cleanup-tokens", true);
    }
    
    public boolean isJoinNotificationEnabled() {
        return config.getBoolean("whitelist.join-notification.enabled", true);
    }
    
    public String getJoinNotificationPermission() {
        return config.getString("whitelist.join-notification.permission", 
            "convenientaccess.whitelist.notify");
    }
    
    public boolean isWelcomeMessageEnabled() {
        return config.getBoolean("whitelist.welcome-message.enabled", true);
    }
    
    public String getWelcomeMessage() {
        return config.getString("whitelist.welcome-message.text", 
            "&a欢迎回到服务器！\n&7玩家: &e{player}");
    }
    
    // 日志配置
    public boolean isLogRequests() {
        return config.getBoolean("logging.log-requests", false);
    }
    
    public boolean isDebug() {
        return config.getBoolean("logging.debug", false);
    }
}