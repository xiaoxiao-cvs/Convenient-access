package com.xaoxiao.convenientaccess;

import com.xaoxiao.convenientaccess.api.ApiManager;
import com.xaoxiao.convenientaccess.cache.CacheManager;
import com.xaoxiao.convenientaccess.command.ConvenientAccessCommand;
import com.xaoxiao.convenientaccess.config.ConfigManager;
import com.xaoxiao.convenientaccess.data.DataCollector;
import com.xaoxiao.convenientaccess.http.HttpServer;
import com.xaoxiao.convenientaccess.integration.SparkIntegration;
import com.xaoxiao.convenientaccess.whitelist.WhitelistSystem;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            
            // 初始化现有组件
            cacheManager = new CacheManager(configManager);
            sparkIntegration = new SparkIntegration(this);
            dataCollector = new DataCollector(this, sparkIntegration, cacheManager);
            apiManager = new ApiManager(this, dataCollector);
            
            // 初始化白名单管理系统
            whitelistSystem = new WhitelistSystem(this);
            whitelistSystem.initialize().thenAccept(success -> {
                if (success) {
                    logger.info("白名单管理系统启动成功");
                } else {
                    logger.error("白名单管理系统启动失败");
                }
            }).exceptionally(throwable -> {
                logger.error("白名单管理系统启动异常", throwable);
                return null;
            });
            
            // 初始化HTTP服务器
            if (configManager.isHttpEnabled()) {
                httpServer = new HttpServer(this, apiManager);
                httpServer.start();
                logger.info("HTTP服务器启动完成 - 端口: {}", configManager.getHttpPort());
            } else {
                logger.info("HTTP服务器已禁用");
            }
            
            // 注册命令
            getCommand("convenientaccess").setExecutor(new ConvenientAccessCommand(this));
            
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
}