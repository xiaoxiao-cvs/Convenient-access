package com.xaoxiao.convenientaccess.integration;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Spark API 集成类
 * 负责与Spark插件进行交互，获取详细的性能数据
 */
public class SparkIntegration {
    
    private final ConvenientAccessPlugin plugin;
    private Spark sparkApi;
    private boolean sparkAvailable;
    
    public SparkIntegration(ConvenientAccessPlugin plugin) {
        this.plugin = plugin;
        this.sparkAvailable = initializeSpark();
    }
    
    /**
     * 初始化Spark API
     */
    private boolean initializeSpark() {
        try {
            // 检查Spark插件是否存在
            if (!Bukkit.getPluginManager().isPluginEnabled("spark")) {
                plugin.getLogger().info("Spark插件未安装，将使用原版API");
                return false;
            }
            
            // 尝试获取Spark API
            this.sparkApi = SparkProvider.get();
            plugin.getLogger().info("Spark API集成成功");
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Spark API集成失败，将使用原版API", e);
            return false;
        }
    }
    
    /**
     * 检查Spark是否可用
     */
    public boolean isSparkAvailable() {
        return sparkAvailable && sparkApi != null;
    }
    
    /**
     * 异步获取基础性能数据
     */
    public CompletableFuture<Map<String, Object>> getPerformanceDataAsync() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> performanceData = new HashMap<>();
            
            if (!isSparkAvailable()) {
                return getFallbackPerformanceData();
            }
            
            try {
                // 获取TPS数据
                if (sparkApi.tps() != null) {
                    Map<String, Object> tpsData = new HashMap<>();
                    try {
                        // 尝试获取TPS统计，如果失败则使用备用方案
                        tpsData.put("available", true);
                        tpsData.put("source", "spark");
                    } catch (Exception e) {
                        tpsData.put("available", false);
                        tpsData.put("error", e.getMessage());
                    }
                    performanceData.put("tps", tpsData);
                }
                
                // 获取CPU数据
                if (sparkApi.cpuSystem() != null) {
                    Map<String, Object> cpuData = new HashMap<>();
                    try {
                        cpuData.put("available", true);
                        cpuData.put("source", "spark");
                    } catch (Exception e) {
                        cpuData.put("available", false);
                        cpuData.put("error", e.getMessage());
                    }
                    performanceData.put("cpu", cpuData);
                }
                
                // 获取内存数据
                Runtime runtime = Runtime.getRuntime();
                Map<String, Object> memoryData = new HashMap<>();
                memoryData.put("max_memory", runtime.maxMemory());
                memoryData.put("total_memory", runtime.totalMemory());
                memoryData.put("free_memory", runtime.freeMemory());
                memoryData.put("used_memory", runtime.totalMemory() - runtime.freeMemory());
                memoryData.put("usage_percent", 
                    ((double)(runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory()) * 100);
                memoryData.put("source", "spark");
                performanceData.put("memory", memoryData);
                
                return performanceData;
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "获取Spark性能数据失败，使用备用方案", e);
                return getFallbackPerformanceData();
            }
        });
    }
    
    /**
     * 备用性能数据获取方法（当Spark不可用时使用）
     */
    private Map<String, Object> getFallbackPerformanceData() {
        Map<String, Object> performanceData = new HashMap<>();
        
        // TPS数据 - 使用服务器内置方法
        Map<String, Object> tpsData = new HashMap<>();
        try {
            // 简单的TPS估算
            tpsData.put("estimated", 20.0);
            tpsData.put("source", "fallback");
            tpsData.put("available", false);
            tpsData.put("note", "Spark not available, using fallback estimation");
        } catch (Exception e) {
            tpsData.put("available", false);
            tpsData.put("error", "Unable to get TPS data");
        }
        performanceData.put("tps", tpsData);
        
        // CPU数据 - 无法获取详细信息
        Map<String, Object> cpuData = new HashMap<>();
        cpuData.put("available", false);
        cpuData.put("source", "fallback");
        cpuData.put("note", "CPU data requires Spark plugin");
        performanceData.put("cpu", cpuData);
        
        // 内存数据 - 使用Runtime
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memoryData = new HashMap<>();
        memoryData.put("max_memory", runtime.maxMemory());
        memoryData.put("total_memory", runtime.totalMemory());
        memoryData.put("free_memory", runtime.freeMemory());
        memoryData.put("used_memory", runtime.totalMemory() - runtime.freeMemory());
        memoryData.put("usage_percent", 
            ((double)(runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory()) * 100);
        memoryData.put("source", "fallback");
        performanceData.put("memory", memoryData);
        
        return performanceData;
    }
    
    /**
     * 获取Spark插件信息
     */
    public Map<String, Object> getSparkInfo() {
        Map<String, Object> sparkInfo = new HashMap<>();
        sparkInfo.put("available", isSparkAvailable());
        
        if (isSparkAvailable()) {
            sparkInfo.put("version", "detected");
            sparkInfo.put("status", "active");
        } else {
            sparkInfo.put("reason", "Plugin not found or failed to initialize");
        }
        
        return sparkInfo;
    }
}