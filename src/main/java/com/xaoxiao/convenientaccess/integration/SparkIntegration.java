package com.xaoxiao.convenientaccess.integration;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import org.bukkit.Bukkit;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
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
                Map<String, Object> tpsData = getTpsData();
                performanceData.put("tps", tpsData);
                
                // 获取MSPT数据
                Map<String, Object> msptData = getMsptData();
                performanceData.put("mspt", msptData);
                
                // 获取CPU数据
                Map<String, Object> cpuData = getCpuData();
                performanceData.put("cpu", cpuData);
                
                // 获取详细内存数据
                Map<String, Object> memoryData = getDetailedMemoryData();
                performanceData.put("memory", memoryData);
                
                // 获取GC数据
                Map<String, Object> gcData = getGarbageCollectionData();
                performanceData.put("gc", gcData);
                
                // 获取线程数据
                Map<String, Object> threadData = getThreadData();
                performanceData.put("threads", threadData);
                
                return performanceData;
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "获取Spark性能数据失败，使用备用方案", e);
                return getFallbackPerformanceData();
            }
        });
    }
    
    /**
     * 获取TPS数据
     */
    private Map<String, Object> getTpsData() {
        Map<String, Object> tpsData = new HashMap<>();
        
        try {
            if (sparkApi.tps() != null) {
                DoubleStatistic<StatisticWindow.TicksPerSecond> tpsStatistic = sparkApi.tps();
                
                tpsData.put("available", true);
                tpsData.put("source", "spark");
                
                // 获取不同时间窗口的TPS数据
                Map<String, Double> tpsWindows = new HashMap<>();
                tpsWindows.put("last_10s", tpsStatistic.poll(StatisticWindow.TicksPerSecond.SECONDS_10));
                tpsWindows.put("last_1m", tpsStatistic.poll(StatisticWindow.TicksPerSecond.MINUTES_1));
                tpsWindows.put("last_5m", tpsStatistic.poll(StatisticWindow.TicksPerSecond.MINUTES_5));
                
                tpsData.put("values", tpsWindows);
                
                // 计算服务器负载（基于20TPS）
                double currentTps = tpsWindows.get("last_1m");
                double load = Math.max(0, (20.0 - currentTps) / 20.0 * 100);
                tpsData.put("server_load_percent", load);
                
            } else {
                tpsData.put("available", false);
                tpsData.put("error", "TPS statistic not available");
            }
        } catch (Exception e) {
            tpsData.put("available", false);
            tpsData.put("error", e.getMessage());
        }
        
        return tpsData;
    }
    
    /**
     * 获取MSPT数据
     */
    private Map<String, Object> getMsptData() {
        Map<String, Object> msptData = new HashMap<>();
        
        try {
            if (sparkApi.mspt() != null) {
                GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> msptStatistic = sparkApi.mspt();
                
                msptData.put("available", true);
                msptData.put("source", "spark");
                
                // 获取不同时间窗口的MSPT数据
                Map<String, Object> msptWindows = new HashMap<>();
                
                DoubleAverageInfo mspt1m = msptStatistic.poll(StatisticWindow.MillisPerTick.MINUTES_1);
                if (mspt1m != null) {
                    Map<String, Double> mspt1mData = new HashMap<>();
                    mspt1mData.put("mean", mspt1m.mean());
                    mspt1mData.put("max", mspt1m.max());
                    mspt1mData.put("min", mspt1m.min());
                    mspt1mData.put("percentile_95", mspt1m.percentile95th());
                    msptWindows.put("last_1m", mspt1mData);
                }
                
                DoubleAverageInfo mspt5m = msptStatistic.poll(StatisticWindow.MillisPerTick.MINUTES_5);
                if (mspt5m != null) {
                    Map<String, Double> mspt5mData = new HashMap<>();
                    mspt5mData.put("mean", mspt5m.mean());
                    mspt5mData.put("max", mspt5m.max());
                    mspt5mData.put("min", mspt5m.min());
                    mspt5mData.put("percentile_95", mspt5m.percentile95th());
                    msptWindows.put("last_5m", mspt5mData);
                }
                
                msptData.put("values", msptWindows);
                
            } else {
                msptData.put("available", false);
                msptData.put("error", "MSPT statistic not available");
            }
        } catch (Exception e) {
            msptData.put("available", false);
            msptData.put("error", e.getMessage());
        }
        
        return msptData;
    }
    
    /**
     * 获取CPU数据
     */
    private Map<String, Object> getCpuData() {
        Map<String, Object> cpuData = new HashMap<>();
        
        try {
            if (sparkApi.cpuSystem() != null && sparkApi.cpuProcess() != null) {
                DoubleStatistic<StatisticWindow.CpuUsage> systemCpu = sparkApi.cpuSystem();
                DoubleStatistic<StatisticWindow.CpuUsage> processCpu = sparkApi.cpuProcess();
                
                cpuData.put("available", true);
                cpuData.put("source", "spark");
                
                // 系统CPU使用率
                Map<String, Double> systemCpuData = new HashMap<>();
                systemCpuData.put("last_10s", systemCpu.poll(StatisticWindow.CpuUsage.SECONDS_10));
                systemCpuData.put("last_1m", systemCpu.poll(StatisticWindow.CpuUsage.MINUTES_1));
                systemCpuData.put("last_15m", systemCpu.poll(StatisticWindow.CpuUsage.MINUTES_15));
                cpuData.put("system", systemCpuData);
                
                // 进程CPU使用率
                Map<String, Double> processCpuData = new HashMap<>();
                processCpuData.put("last_10s", processCpu.poll(StatisticWindow.CpuUsage.SECONDS_10));
                processCpuData.put("last_1m", processCpu.poll(StatisticWindow.CpuUsage.MINUTES_1));
                processCpuData.put("last_15m", processCpu.poll(StatisticWindow.CpuUsage.MINUTES_15));
                cpuData.put("process", processCpuData);
                
            } else {
                cpuData.put("available", false);
                cpuData.put("error", "CPU statistics not available");
            }
        } catch (Exception e) {
            cpuData.put("available", false);
            cpuData.put("error", e.getMessage());
        }
        
        return cpuData;
    }
    
    /**
     * 获取详细内存数据
     */
    private Map<String, Object> getDetailedMemoryData() {
        Map<String, Object> memoryData = new HashMap<>();
        
        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            
            // 堆内存
            MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
            Map<String, Object> heapData = new HashMap<>();
            heapData.put("init", heapMemory.getInit());
            heapData.put("used", heapMemory.getUsed());
            heapData.put("committed", heapMemory.getCommitted());
            heapData.put("max", heapMemory.getMax());
            heapData.put("usage_percent", (double) heapMemory.getUsed() / heapMemory.getMax() * 100);
            memoryData.put("heap", heapData);
            
            // 非堆内存
            MemoryUsage nonHeapMemory = memoryBean.getNonHeapMemoryUsage();
            Map<String, Object> nonHeapData = new HashMap<>();
            nonHeapData.put("init", nonHeapMemory.getInit());
            nonHeapData.put("used", nonHeapMemory.getUsed());
            nonHeapData.put("committed", nonHeapMemory.getCommitted());
            nonHeapData.put("max", nonHeapMemory.getMax());
            memoryData.put("non_heap", nonHeapData);
            
            // 内存池详情
            List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
            Map<String, Object> poolsData = new HashMap<>();
            for (MemoryPoolMXBean pool : memoryPools) {
                MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    Map<String, Object> poolData = new HashMap<>();
                    poolData.put("used", usage.getUsed());
                    poolData.put("committed", usage.getCommitted());
                    poolData.put("max", usage.getMax());
                    poolData.put("type", pool.getType().name());
                    poolsData.put(pool.getName().replace(" ", "_").toLowerCase(), poolData);
                }
            }
            memoryData.put("pools", poolsData);
            
            memoryData.put("source", "jvm");
            
        } catch (Exception e) {
            memoryData.put("available", false);
            memoryData.put("error", e.getMessage());
        }
        
        return memoryData;
    }
    
    /**
     * 获取垃圾回收数据
     */
    private Map<String, Object> getGarbageCollectionData() {
        Map<String, Object> gcData = new HashMap<>();
        
        try {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            Map<String, Object> collectorsData = new HashMap<>();
            
            long totalCollections = 0;
            long totalTime = 0;
            
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                Map<String, Object> collectorData = new HashMap<>();
                collectorData.put("collection_count", gcBean.getCollectionCount());
                collectorData.put("collection_time", gcBean.getCollectionTime());
                collectorData.put("memory_pool_names", gcBean.getMemoryPoolNames());
                
                collectorsData.put(gcBean.getName().replace(" ", "_").toLowerCase(), collectorData);
                
                totalCollections += gcBean.getCollectionCount();
                totalTime += gcBean.getCollectionTime();
            }
            
            gcData.put("collectors", collectorsData);
            gcData.put("total_collections", totalCollections);
            gcData.put("total_time_ms", totalTime);
            gcData.put("average_time_per_collection", totalCollections > 0 ? (double) totalTime / totalCollections : 0);
            gcData.put("source", "jvm");
            
        } catch (Exception e) {
            gcData.put("available", false);
            gcData.put("error", e.getMessage());
        }
        
        return gcData;
    }
    
    /**
     * 获取线程数据
     */
    private Map<String, Object> getThreadData() {
        Map<String, Object> threadData = new HashMap<>();
        
        try {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            
            threadData.put("current_thread_count", threadBean.getThreadCount());
            threadData.put("daemon_thread_count", threadBean.getDaemonThreadCount());
            threadData.put("peak_thread_count", threadBean.getPeakThreadCount());
            threadData.put("total_started_thread_count", threadBean.getTotalStartedThreadCount());
            
            // 死锁检测
            long[] deadlockedThreads = threadBean.findDeadlockedThreads();
            threadData.put("deadlocked_threads", deadlockedThreads != null ? deadlockedThreads.length : 0);
            
            threadData.put("source", "jvm");
            
        } catch (Exception e) {
            threadData.put("available", false);
            threadData.put("error", e.getMessage());
        }
        
        return threadData;
    }
    
    /**
     * 备用性能数据获取方法（当Spark不可用时使用）
     */
    private Map<String, Object> getFallbackPerformanceData() {
        Map<String, Object> performanceData = new HashMap<>();
        
        // TPS数据 - 使用服务器内置方法
        Map<String, Object> tpsData = new HashMap<>();
        try {
            // 简单的TPS估算 - 使用反射获取TPS
            double estimatedTps = 20.0; // 默认值
            try {
                // 尝试获取服务器TPS
                Object server = Bukkit.getServer();
                java.lang.reflect.Method getTpsMethod = server.getClass().getMethod("getTPS");
                double[] tpsArray = (double[]) getTpsMethod.invoke(server);
                if (tpsArray != null && tpsArray.length > 0) {
                    estimatedTps = Math.min(20.0, tpsArray[0]);
                }
            } catch (Exception e) {
                // 如果反射失败，使用默认值
                plugin.getLogger().log(Level.FINE, "无法获取服务器TPS，使用默认值", e);
            }
            
            Map<String, Double> tpsValues = new HashMap<>();
            tpsValues.put("last_10s", estimatedTps);
            tpsValues.put("last_1m", estimatedTps);
            tpsValues.put("last_5m", estimatedTps);
            
            tpsData.put("values", tpsValues);
            tpsData.put("server_load_percent", Math.max(0, (20.0 - estimatedTps) / 20.0 * 100));
            tpsData.put("source", "fallback");
            tpsData.put("available", true);
            tpsData.put("note", "Using server built-in TPS estimation");
        } catch (Exception e) {
            tpsData.put("available", false);
            tpsData.put("error", "Unable to get TPS data");
        }
        performanceData.put("tps", tpsData);
        
        // MSPT数据 - 估算
        Map<String, Object> msptData = new HashMap<>();
        try {
            double estimatedTps = 20.0;
            try {
                Object server = Bukkit.getServer();
                java.lang.reflect.Method getTpsMethod = server.getClass().getMethod("getTPS");
                double[] tpsArray = (double[]) getTpsMethod.invoke(server);
                if (tpsArray != null && tpsArray.length > 0) {
                    estimatedTps = Math.max(1.0, tpsArray[0]);
                }
            } catch (Exception e) {
                // 使用默认值
            }
            
            double estimatedMspt = 1000.0 / estimatedTps;
            Map<String, Object> msptValues = new HashMap<>();
            
            Map<String, Double> mspt1mData = new HashMap<>();
            mspt1mData.put("mean", estimatedMspt);
            mspt1mData.put("max", estimatedMspt * 1.2);
            mspt1mData.put("min", estimatedMspt * 0.8);
            mspt1mData.put("percentile_95", estimatedMspt * 1.1);
            msptValues.put("last_1m", mspt1mData);
            
            Map<String, Double> mspt5mData = new HashMap<>();
            mspt5mData.put("mean", estimatedMspt);
            mspt5mData.put("max", estimatedMspt * 1.2);
            mspt5mData.put("min", estimatedMspt * 0.8);
            mspt5mData.put("percentile_95", estimatedMspt * 1.1);
            msptValues.put("last_5m", mspt5mData);
            
            msptData.put("values", msptValues);
            msptData.put("source", "fallback");
            msptData.put("available", true);
            msptData.put("note", "Estimated from TPS data");
        } catch (Exception e) {
            msptData.put("available", false);
            msptData.put("error", "Unable to estimate MSPT data");
        }
        performanceData.put("mspt", msptData);
        
        // CPU数据 - 无法获取详细信息
        Map<String, Object> cpuData = new HashMap<>();
        cpuData.put("available", false);
        cpuData.put("source", "fallback");
        cpuData.put("note", "Detailed CPU data requires Spark plugin");
        performanceData.put("cpu", cpuData);
        
        // 内存数据 - 使用基本JVM信息
        performanceData.put("memory", getDetailedMemoryData());
        
        // GC数据
        performanceData.put("gc", getGarbageCollectionData());
        
        // 线程数据
        performanceData.put("threads", getThreadData());
        
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