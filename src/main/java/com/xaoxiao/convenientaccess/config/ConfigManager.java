package com.xaoxiao.convenientaccess.config;

import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;

/**
 * 配置管理器
 * 负责管理插件的所有配置项
 */
public class ConfigManager {
    
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