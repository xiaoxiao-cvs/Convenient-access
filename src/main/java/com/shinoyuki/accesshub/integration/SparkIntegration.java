package com.shinoyuki.accesshub.integration;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

/**
 * Spark API 集成 (v3 Forge 版, 由 v1 Bukkit SparkIntegration 重写)。
 *
 * Spark API (me.lucko.spark.api) 是平台中立的, Bukkit/Forge/Fabric 通用。本类:
 *  - 经 ModList.isLoaded("spark") 检测 spark mod 是否安装 (替代 v1 的 Bukkit.getPluginManager)
 *  - 装了则用 SparkProvider.get() 取 TPS/MSPT/CPU 等精确数据
 *  - 未装则降级: TPS/MSPT 用 MinecraftServer.getAverageTickTime() 估算 (替代 v1 的 Bukkit getTPS 反射)
 *  - 内存/GC/线程数据走 JVM MXBean, 与 spark 无关, 始终可用
 *
 * 关键: spark-api 是 compileOnly (不打包)。me.lucko.spark.api.* 仅在 spark mod 安装时存在于运行期
 * classpath。本类先用 ModList.isLoaded 判断, 仅在 spark 存在时才触达 SparkProvider/Spark 类型 ——
 * JVM 惰性链接保证 spark 缺失时这些类永不加载, 不触发 NoClassDefFoundError。
 */
public class SparkIntegration {

    private static final Logger logger = LoggerFactory.getLogger(SparkIntegration.class);

    private final MinecraftServer server;
    private Spark sparkApi;
    private final boolean sparkAvailable;

    public SparkIntegration(MinecraftServer server) {
        this.server = server;
        this.sparkAvailable = initializeSpark();
    }

    private boolean initializeSpark() {
        try {
            if (!ModList.get().isLoaded("spark")) {
                logger.info("spark mod 未安装, 性能监测将降级为 JVM 基础数据");
                return false;
            }
            this.sparkApi = SparkProvider.get();
            logger.info("Spark API 集成成功");
            return true;
        } catch (Throwable t) {
            // 捕获 NoClassDefFoundError 等: spark mod 声明已加载但 API 不可用时安全降级
            logger.warn("Spark API 集成失败, 降级为 JVM 基础数据: {}", t.getMessage());
            return false;
        }
    }

    public boolean isSparkAvailable() {
        return sparkAvailable && sparkApi != null;
    }

    /**
     * 异步获取性能数据。spark 可用时含 TPS/MSPT/CPU; 始终含内存/GC/线程 (JVM)。
     */
    public CompletableFuture<Map<String, Object>> getPerformanceDataAsync() {
        return CompletableFuture.supplyAsync(() -> {
            if (!isSparkAvailable()) {
                return getFallbackPerformanceData();
            }
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("source", "spark");
                data.put("tps", getTpsData());
                data.put("mspt", getMsptData());
                data.put("cpu", getCpuData());
                data.put("memory", getDetailedMemoryData());
                data.put("gc", getGarbageCollectionData());
                data.put("threads", getThreadData());
                return data;
            } catch (Exception e) {
                logger.warn("获取 Spark 性能数据失败, 使用 JVM fallback", e);
                return getFallbackPerformanceData();
            }
        });
    }

    private Map<String, Object> getTpsData() {
        Map<String, Object> tpsData = new LinkedHashMap<>();
        try {
            DoubleStatistic<StatisticWindow.TicksPerSecond> tps = sparkApi.tps();
            if (tps != null) {
                tpsData.put("available", true);
                Map<String, Double> windows = new LinkedHashMap<>();
                windows.put("last_10s", tps.poll(StatisticWindow.TicksPerSecond.SECONDS_10));
                windows.put("last_1m", tps.poll(StatisticWindow.TicksPerSecond.MINUTES_1));
                windows.put("last_5m", tps.poll(StatisticWindow.TicksPerSecond.MINUTES_5));
                tpsData.put("values", windows);
                double current = windows.get("last_1m");
                tpsData.put("server_load_percent", Math.max(0, (20.0 - current) / 20.0 * 100));
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

    private Map<String, Object> getMsptData() {
        Map<String, Object> msptData = new LinkedHashMap<>();
        try {
            GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt = sparkApi.mspt();
            if (mspt != null) {
                msptData.put("available", true);
                Map<String, Object> windows = new LinkedHashMap<>();
                DoubleAverageInfo m1 = mspt.poll(StatisticWindow.MillisPerTick.MINUTES_1);
                if (m1 != null) {
                    windows.put("last_1m", msptWindow(m1));
                }
                DoubleAverageInfo m5 = mspt.poll(StatisticWindow.MillisPerTick.MINUTES_5);
                if (m5 != null) {
                    windows.put("last_5m", msptWindow(m5));
                }
                msptData.put("values", windows);
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

    private Map<String, Double> msptWindow(DoubleAverageInfo info) {
        Map<String, Double> data = new LinkedHashMap<>();
        data.put("mean", info.mean());
        data.put("max", info.max());
        data.put("min", info.min());
        data.put("percentile_95", info.percentile95th());
        return data;
    }

    private Map<String, Object> getCpuData() {
        Map<String, Object> cpuData = new LinkedHashMap<>();
        try {
            DoubleStatistic<StatisticWindow.CpuUsage> systemCpu = sparkApi.cpuSystem();
            DoubleStatistic<StatisticWindow.CpuUsage> processCpu = sparkApi.cpuProcess();
            if (systemCpu != null && processCpu != null) {
                cpuData.put("available", true);
                cpuData.put("system", cpuWindows(systemCpu));
                cpuData.put("process", cpuWindows(processCpu));
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

    private Map<String, Double> cpuWindows(DoubleStatistic<StatisticWindow.CpuUsage> stat) {
        Map<String, Double> data = new LinkedHashMap<>();
        data.put("last_10s", stat.poll(StatisticWindow.CpuUsage.SECONDS_10));
        data.put("last_1m", stat.poll(StatisticWindow.CpuUsage.MINUTES_1));
        data.put("last_15m", stat.poll(StatisticWindow.CpuUsage.MINUTES_15));
        return data;
    }

    private Map<String, Object> getDetailedMemoryData() {
        Map<String, Object> memoryData = new LinkedHashMap<>();
        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            Map<String, Object> heapData = new LinkedHashMap<>();
            heapData.put("init", heap.getInit());
            heapData.put("used", heap.getUsed());
            heapData.put("committed", heap.getCommitted());
            heapData.put("max", heap.getMax());
            heapData.put("usage_percent", heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() * 100 : 0);
            memoryData.put("heap", heapData);

            MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
            Map<String, Object> nonHeapData = new LinkedHashMap<>();
            nonHeapData.put("init", nonHeap.getInit());
            nonHeapData.put("used", nonHeap.getUsed());
            nonHeapData.put("committed", nonHeap.getCommitted());
            nonHeapData.put("max", nonHeap.getMax());
            memoryData.put("non_heap", nonHeapData);

            Map<String, Object> pools = new LinkedHashMap<>();
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    Map<String, Object> poolData = new LinkedHashMap<>();
                    poolData.put("used", usage.getUsed());
                    poolData.put("committed", usage.getCommitted());
                    poolData.put("max", usage.getMax());
                    poolData.put("type", pool.getType().name());
                    pools.put(pool.getName().replace(" ", "_").toLowerCase(), poolData);
                }
            }
            memoryData.put("pools", pools);
            memoryData.put("source", "jvm");
        } catch (Exception e) {
            memoryData.put("available", false);
            memoryData.put("error", e.getMessage());
        }
        return memoryData;
    }

    private Map<String, Object> getGarbageCollectionData() {
        Map<String, Object> gcData = new LinkedHashMap<>();
        try {
            Map<String, Object> collectors = new LinkedHashMap<>();
            long totalCollections = 0;
            long totalTime = 0;
            for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                Map<String, Object> collectorData = new LinkedHashMap<>();
                collectorData.put("collection_count", gcBean.getCollectionCount());
                collectorData.put("collection_time", gcBean.getCollectionTime());
                collectors.put(gcBean.getName().replace(" ", "_").toLowerCase(), collectorData);
                totalCollections += gcBean.getCollectionCount();
                totalTime += gcBean.getCollectionTime();
            }
            gcData.put("collectors", collectors);
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

    private Map<String, Object> getThreadData() {
        Map<String, Object> threadData = new LinkedHashMap<>();
        try {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            threadData.put("current_thread_count", threadBean.getThreadCount());
            threadData.put("daemon_thread_count", threadBean.getDaemonThreadCount());
            threadData.put("peak_thread_count", threadBean.getPeakThreadCount());
            threadData.put("total_started_thread_count", threadBean.getTotalStartedThreadCount());
            long[] deadlocked = threadBean.findDeadlockedThreads();
            threadData.put("deadlocked_threads", deadlocked != null ? deadlocked.length : 0);
            threadData.put("source", "jvm");
        } catch (Exception e) {
            threadData.put("available", false);
            threadData.put("error", e.getMessage());
        }
        return threadData;
    }

    /**
     * spark 不可用时的降级数据: TPS/MSPT 用 MinecraftServer.getAverageTickTime() 估算,
     * 内存/GC/线程仍走 JVM MXBean。
     */
    private Map<String, Object> getFallbackPerformanceData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "fallback");

        // getAverageTickTime() 返回平均 MSPT; TPS = min(20, 1000/mspt)
        double mspt = Math.max(0.01, server.getAverageTickTime());
        double tps = Math.min(20.0, 1000.0 / mspt);

        Map<String, Object> tpsData = new LinkedHashMap<>();
        Map<String, Double> tpsValues = new LinkedHashMap<>();
        tpsValues.put("last_10s", tps);
        tpsValues.put("last_1m", tps);
        tpsValues.put("last_5m", tps);
        tpsData.put("values", tpsValues);
        tpsData.put("server_load_percent", Math.max(0, (20.0 - tps) / 20.0 * 100));
        tpsData.put("available", true);
        tpsData.put("note", "估算自 MinecraftServer.getAverageTickTime, 安装 spark mod 获取精确数据");
        data.put("tps", tpsData);

        Map<String, Object> msptData = new LinkedHashMap<>();
        Map<String, Object> msptWindow = new LinkedHashMap<>();
        msptWindow.put("mean", mspt);
        msptData.put("values", Map.of("last_1m", msptWindow));
        msptData.put("available", true);
        msptData.put("note", "实测平均 MSPT");
        data.put("mspt", msptData);

        Map<String, Object> cpuData = new HashMap<>();
        cpuData.put("available", false);
        cpuData.put("note", "CPU 详细数据需安装 spark mod");
        data.put("cpu", cpuData);

        data.put("memory", getDetailedMemoryData());
        data.put("gc", getGarbageCollectionData());
        data.put("threads", getThreadData());
        return data;
    }
}
