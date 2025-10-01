package com.xaoxiao.convenientaccess;

import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.api.ApiManager;
import com.xaoxiao.convenientaccess.cache.CacheManager;
import com.xaoxiao.convenientaccess.command.ConvenientAccessCommand;
import com.xaoxiao.convenientaccess.config.ConfigManager;
import com.xaoxiao.convenientaccess.data.DataCollector;
import com.xaoxiao.convenientaccess.http.HttpServer;
import com.xaoxiao.convenientaccess.integration.SparkIntegration;
import com.xaoxiao.convenientaccess.whitelist.WhitelistSystem;

public class ConvenientAccessPlugin extends JavaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(ConvenientAccessPlugin.class);
    
    // 现有组件
    private ConfigManager configManager;
    private CacheManager cacheManager;
    private DataCollector dataCollector;
    private ApiManager apiManager;
    private HttpServer httpServer;
    private SparkIntegration sparkIntegration;
    
    // 白名单管理系统
    private WhitelistSystem whitelistSystem;
    
    @Override
    public void onEnable() {
        logger.info("ConvenientAccess 插件正在启动...");
        
        try {
            // 初始化配置管理器
            configManager = new ConfigManager(this);
            
            // 自动生成管理员密码和API Token（仅首次启动）
            initializeAuthConfig();
            
            // 初始化现有组件
            cacheManager = new CacheManager(configManager);
            sparkIntegration = new SparkIntegration(this);
            dataCollector = new DataCollector(this, sparkIntegration, cacheManager);
            apiManager = new ApiManager(this, dataCollector);
            
            // 注册命令
            if (getCommand("convenientaccess") != null) {
                getCommand("convenientaccess").setExecutor(new ConvenientAccessCommand(this));
                logger.info("命令处理器已注册");
            } else {
                logger.warn("无法注册命令处理器 - 命令未在plugin.yml中定义");
            }
            
            // 初始化白名单管理系统
            whitelistSystem = new WhitelistSystem(this);
            whitelistSystem.initialize().thenAccept(success -> {
                if (success) {
                    logger.info("白名单管理系统启动成功");
                    
                    // 在白名单系统初始化完成后注册监听器
                    getServer().getPluginManager().registerEvents(
                        new com.xaoxiao.convenientaccess.listener.WhitelistListener(this), 
                        this
                    );
                    logger.info("白名单监听器已注册");
                    
                    // 在白名单系统初始化完成后启动HTTP服务器
                    if (configManager.isHttpEnabled()) {
                        try {
                            httpServer = new HttpServer(this, apiManager);
                            httpServer.start();
                            logger.info("HTTP服务器启动完成 - 端口: {}", configManager.getHttpPort());
                        } catch (Exception e) {
                            logger.error("HTTP服务器启动失败", e);
                        }
                    } else {
                        logger.info("HTTP服务器已禁用");
                    }
                } else {
                    logger.error("白名单管理系统启动失败");
                }
            }).exceptionally(throwable -> {
                logger.error("白名单管理系统启动异常", throwable);
                return null;
            });
            
            logger.info("ConvenientAccess 插件启动完成！");
            
        } catch (Exception e) {
            logger.error("插件启动过程中发生异常", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    
    @Override
    public void onDisable() {
        logger.info("ConvenientAccess 插件正在关闭...");
        
        try {
            // 关闭HTTP服务器
            if (httpServer != null) {
                httpServer.stop();
            }
            
            // 关闭白名单管理系统
            if (whitelistSystem != null) {
                whitelistSystem.shutdown();
            }
            
            // 清理缓存
            if (cacheManager != null) {
                cacheManager.clearAll();
            }
            
            logger.info("ConvenientAccess 插件已关闭");
        } catch (Exception e) {
            logger.error("插件关闭过程中发生异常", e);
        }
    }
    
    // Getters for components
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public CacheManager getCacheManager() {
        return cacheManager;
    }
    
    public DataCollector getDataCollector() {
        return dataCollector;
    }
    
    public ApiManager getApiManager() {
        return apiManager;
    }
    
    public HttpServer getHttpServer() {
        return httpServer;
    }
    
    public SparkIntegration getSparkIntegration() {
        return sparkIntegration;
    }
    
    public WhitelistSystem getWhitelistSystem() {
        return whitelistSystem;
    }
    
    /**
     * 重载插件配置
     */
    public void reload() {
        try {
            // 重载配置
            if (configManager != null) {
                configManager.reload();
            }
            
            // 清理缓存
            if (cacheManager != null) {
                cacheManager.clearAll();
            }
            
            // 重启HTTP服务器
            if (httpServer != null) {
                httpServer.stop();
            }
            
            if (configManager != null && configManager.isHttpEnabled()) {
                httpServer = new HttpServer(this, apiManager);
                httpServer.start();
                logger.info("HTTP服务器重启完成");
            }
            
            logger.info("插件重载完成");
            
        } catch (Exception e) {
            logger.error("插件重载失败", e);
            throw new RuntimeException("插件重载失败: " + e.getMessage());
        }
    }
    
    /**
     * 初始化认证配置（自动生成密码和Token）
     */
    private void initializeAuthConfig() {
        boolean configChanged = false;
        
        // 检查并生成管理员密码
        String adminPassword = configManager.getAdminPassword();
        if (adminPassword == null || adminPassword.trim().isEmpty()) {
            // 生成12位随机密码
            String newPassword = generateRandomPassword(12);
            configManager.setAdminPassword(newPassword);
            configChanged = true;
            logger.info("已自动生成管理员密码: {}", newPassword);
            logger.warn("请妥善保管管理员密码，用于生成注册令牌等管理操作");
        } else {
            logger.info("使用配置文件中的管理员密码");
        }
        
        // 检查并生成API Token
        String apiToken = configManager.getApiToken();
        if (apiToken == null || apiToken.trim().isEmpty()) {
            // 生成64位API Token（sk-开头）
            String newToken = generateApiToken();
            configManager.setApiToken(newToken);
            configChanged = true;
            logger.info("已自动生成API访问令牌: {}", newToken);
            logger.warn("请妥善保管API令牌，用于API访问认证");
        } else {
            logger.info("使用配置文件中的API令牌");
        }
        
        if (configChanged) {
            logger.info("认证配置已更新并保存到配置文件");
        }
    }
    
    /**
     * 生成随机密码
     */
    private String generateRandomPassword(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return sb.toString();
    }
    
    /**
     * 生成API Token（sk-开头的64位token）
     */
    private String generateApiToken() {
        String prefix = configManager.getTokenPrefix();
        String tokenChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(prefix);
        java.util.Random random = new java.util.Random();
        
        // 生成64位token（包含前缀）
        int tokenLength = 64 - prefix.length();
        for (int i = 0; i < tokenLength; i++) {
            sb.append(tokenChars.charAt(random.nextInt(tokenChars.length())));
        }
        
        return sb.toString();
    }
}