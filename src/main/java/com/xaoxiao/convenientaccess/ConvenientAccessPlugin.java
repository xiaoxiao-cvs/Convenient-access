package com.xaoxiao.convenientaccess;

import com.xaoxiao.convenientaccess.api.ApiManager;
import com.xaoxiao.convenientaccess.cache.CacheManager;
import com.xaoxiao.convenientaccess.command.ConvenientAccessCommand;
import com.xaoxiao.convenientaccess.config.ConfigManager;
import com.xaoxiao.convenientaccess.data.DataCollector;
import com.xaoxiao.convenientaccess.http.HttpServer;
import com.xaoxiao.convenientaccess.integration.SparkIntegration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * ConvenientAccess 主插件类
 * 提供便捷的服务器信息获取API
 */
public class ConvenientAccessPlugin extends JavaPlugin {
    
    private static ConvenientAccessPlugin instance;
    
    private ConfigManager configManager;
    private CacheManager cacheManager;
    private SparkIntegration sparkIntegration;
    private DataCollector dataCollector;
    private ApiManager apiManager;
    private HttpServer httpServer;
    
    @Override
    public void onEnable() {
        instance = this;
        
        try {
            // 初始化配置管理器
            this.configManager = new ConfigManager(this);
            getLogger().info("配置管理器初始化完成");
            
            // 初始化缓存管理器
            this.cacheManager = new CacheManager(configManager);
            getLogger().info("缓存管理器初始化完成");
            
            // 初始化Spark集成
            this.sparkIntegration = new SparkIntegration(this);
            getLogger().info("Spark集成初始化完成 - 状态: " + 
                (sparkIntegration.isSparkAvailable() ? "可用" : "不可用"));
            
            // 初始化数据收集器
            this.dataCollector = new DataCollector(this, sparkIntegration, cacheManager);
            getLogger().info("数据收集器初始化完成");
            
            // 初始化API管理器
            this.apiManager = new ApiManager(this, dataCollector);
            getLogger().info("API管理器初始化完成");
            
            // 初始化HTTP服务器
            if (configManager.isHttpEnabled()) {
                this.httpServer = new HttpServer(this, apiManager);
                httpServer.start();
                getLogger().info("HTTP服务器启动完成 - 端口: " + configManager.getHttpPort());
            } else {
                getLogger().info("HTTP服务器已禁用");
            }
            
            // 注册命令
            getCommand("convenientaccess").setExecutor(new ConvenientAccessCommand(this));
            
            getLogger().info("ConvenientAccess v" + getDescription().getVersion() + " 启用完成!");
            
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "插件启用失败!", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    
    @Override
    public void onDisable() {
        try {
            // 停止HTTP服务器
            if (httpServer != null) {
                httpServer.stop();
                getLogger().info("HTTP服务器已停止");
            }
            
            // 清理缓存
            if (cacheManager != null) {
                cacheManager.clearAll();
                getLogger().info("缓存已清理");
            }
            
            getLogger().info("ConvenientAccess 已禁用");
            
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "插件禁用时发生错误", e);
        } finally {
            instance = null;
        }
    }
    
    /**
     * 重载插件配置
     */
    public void reload() {
        try {
            // 重载配置
            configManager.reload();
            
            // 清理缓存
            cacheManager.clearAll();
            
            // 重启HTTP服务器
            if (httpServer != null) {
                httpServer.stop();
            }
            
            if (configManager.isHttpEnabled()) {
                httpServer = new HttpServer(this, apiManager);
                httpServer.start();
                getLogger().info("HTTP服务器重启完成");
            }
            
            getLogger().info("插件重载完成");
            
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "插件重载失败!", e);
        }
    }
    
    // Getter方法
    public static ConvenientAccessPlugin getInstance() {
        return instance;
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public CacheManager getCacheManager() {
        return cacheManager;
    }
    
    public SparkIntegration getSparkIntegration() {
        return sparkIntegration;
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
}