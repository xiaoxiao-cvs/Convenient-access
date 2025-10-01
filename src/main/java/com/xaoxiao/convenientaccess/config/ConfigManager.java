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
    
    public boolean isAuthEnabled() {
        return config.getBoolean("api.auth.enabled", false);
    }
    
    public String getApiKey() {
        return config.getString("api.auth.api-key", "");
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
    
    // 日志配置
    public boolean isLogRequests() {
        return config.getBoolean("logging.log-requests", false);
    }
    
    public boolean isDebug() {
        return config.getBoolean("logging.debug", false);
    }
}