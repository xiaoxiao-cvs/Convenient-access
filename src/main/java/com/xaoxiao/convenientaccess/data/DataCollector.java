package com.xaoxiao.convenientaccess.data;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import com.xaoxiao.convenientaccess.cache.CacheManager;
import com.xaoxiao.convenientaccess.integration.SparkIntegration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 数据收集器
 * 负责异步收集服务器各种数据，确保线程安全
 */
public class DataCollector {
    
    private final ConvenientAccessPlugin plugin;
    private final SparkIntegration sparkIntegration;
    private final CacheManager cacheManager;
    private final ExecutorService executorService;
    
    // 缓存键常量
    private static final String CACHE_SERVER_INFO = "server_info";
    private static final String CACHE_PERFORMANCE = "performance";
    private static final String CACHE_PLAYERS = "players";
    private static final String CACHE_WORLDS = "worlds";
    
    public DataCollector(ConvenientAccessPlugin plugin, SparkIntegration sparkIntegration, CacheManager cacheManager) {
        this.plugin = plugin;
        this.sparkIntegration = sparkIntegration;
        this.cacheManager = cacheManager;
        
        // 创建专用线程池
        this.executorService = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "ConvenientAccess-DataCollector");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * 异步获取服务器基本信息
     */
    public CompletableFuture<Map<String, Object>> getServerInfoAsync() {
        // 检查缓存
        Map<String, Object> cached = cacheManager.get(CACHE_SERVER_INFO, Map.class);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> serverInfo = new HashMap<>();
                
                // 基本服务器信息
                serverInfo.put("name", Bukkit.getServer().getName());
                serverInfo.put("version", Bukkit.getVersion());
                serverInfo.put("bukkit_version", Bukkit.getBukkitVersion());
                serverInfo.put("motd", Bukkit.getMotd());
                serverInfo.put("max_players", Bukkit.getMaxPlayers());
                serverInfo.put("port", Bukkit.getPort());
                serverInfo.put("allow_nether", Bukkit.getAllowNether());
                serverInfo.put("allow_end", Bukkit.getAllowEnd());
                serverInfo.put("hardcore", Bukkit.isHardcore());
                serverInfo.put("online_mode", Bukkit.getOnlineMode());
                serverInfo.put("whitelist", Bukkit.hasWhitelist());
                
                // 服务器运行时间
                long uptimeMillis = System.currentTimeMillis() - getServerStartTime();
                serverInfo.put("uptime_ms", uptimeMillis);
                serverInfo.put("uptime_formatted", formatUptime(uptimeMillis));
                
                // 时间戳
                serverInfo.put("timestamp", System.currentTimeMillis());
                
                // 缓存数据
                cacheManager.put(CACHE_SERVER_INFO, serverInfo, 
                    plugin.getConfigManager().getServerInfoCacheTime());
                
                return serverInfo;
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "获取服务器信息失败", e);
                return getErrorResponse("Failed to get server info", e);
            }
        }, executorService);
    }
    
    /**
     * 异步获取性能数据
     */
    public CompletableFuture<Map<String, Object>> getPerformanceDataAsync() {
        // 检查缓存
        Map<String, Object> cached = cacheManager.get(CACHE_PERFORMANCE, Map.class);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        return sparkIntegration.getPerformanceDataAsync()
            .thenApply(performanceData -> {
                try {
                    // 添加额外的性能信息
                    performanceData.put("timestamp", System.currentTimeMillis());
                    
                    // 添加线程信息
                    Map<String, Object> threadInfo = new HashMap<>();
                    threadInfo.put("active_count", Thread.activeCount());
                    threadInfo.put("daemon_count", getDaemonThreadCount());
                    performanceData.put("threads", threadInfo);
                    
                    // 添加服务器级别的性能指标
                    Map<String, Object> serverMetrics = getServerMetrics();
                    performanceData.put("server_metrics", serverMetrics);
                    
                    // 缓存数据
                    cacheManager.put(CACHE_PERFORMANCE, performanceData, 
                        plugin.getConfigManager().getPerformanceCacheTime());
                    
                    return performanceData;
                    
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "处理性能数据失败", e);
                    return getErrorResponse("Failed to process performance data", e);
                }
            })
            .exceptionally(throwable -> {
                plugin.getLogger().log(Level.WARNING, "获取性能数据失败", throwable);
                return getErrorResponse("Failed to get performance data", throwable);
            });
    }
    
    /**
     * 获取服务器级别的性能指标
     */
    private Map<String, Object> getServerMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        try {
            // 实体统计
            Map<String, Object> entityStats = new HashMap<>();
            int totalEntities = 0;
            int totalLivingEntities = 0;
            int totalPlayers = 0;
            
            for (World world : Bukkit.getWorlds()) {
                totalEntities += world.getEntities().size();
                totalLivingEntities += world.getLivingEntities().size();
                totalPlayers += world.getPlayers().size();
            }
            
            entityStats.put("total_entities", totalEntities);
            entityStats.put("total_living_entities", totalLivingEntities);
            entityStats.put("total_players", totalPlayers);
            entityStats.put("non_living_entities", totalEntities - totalLivingEntities);
            metrics.put("entities", entityStats);
            
            // 区块统计
            Map<String, Object> chunkStats = new HashMap<>();
            int totalLoadedChunks = 0;
            
            for (World world : Bukkit.getWorlds()) {
                totalLoadedChunks += world.getLoadedChunks().length;
            }
            
            chunkStats.put("total_loaded_chunks", totalLoadedChunks);
            chunkStats.put("chunks_per_world", totalLoadedChunks / Math.max(1, Bukkit.getWorlds().size()));
            metrics.put("chunks", chunkStats);
            
            // 插件统计
            Map<String, Object> pluginStats = new HashMap<>();
            pluginStats.put("total_plugins", Bukkit.getPluginManager().getPlugins().length);
            pluginStats.put("enabled_plugins", 
                (int) java.util.Arrays.stream(Bukkit.getPluginManager().getPlugins())
                    .filter(p -> p.isEnabled())
                    .count());
            metrics.put("plugins", pluginStats);
            
            // 网络统计（基本信息）
            Map<String, Object> networkStats = new HashMap<>();
            networkStats.put("max_players", Bukkit.getMaxPlayers());
            networkStats.put("online_players", Bukkit.getOnlinePlayers().size());
            networkStats.put("player_slots_used_percent", 
                (double) Bukkit.getOnlinePlayers().size() / Bukkit.getMaxPlayers() * 100);
            metrics.put("network", networkStats);
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "获取服务器指标失败", e);
            metrics.put("error", e.getMessage());
        }
        
        return metrics;
    }
    
    /**
     * 异步获取玩家数据
     */
    public CompletableFuture<Map<String, Object>> getPlayersDataAsync() {
        // 检查缓存
        Map<String, Object> cached = cacheManager.get(CACHE_PLAYERS, Map.class);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> playersData = new HashMap<>();
                
                Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
                
                // 基本统计
                playersData.put("online_count", onlinePlayers.size());
                playersData.put("max_players", Bukkit.getMaxPlayers());
                
                // 玩家列表
                List<Map<String, Object>> playerList = new ArrayList<>();
                for (Player player : onlinePlayers) {
                    Map<String, Object> playerInfo = new HashMap<>();
                    playerInfo.put("name", player.getName());
                    playerInfo.put("uuid", player.getUniqueId().toString());
                    playerInfo.put("display_name", player.getDisplayName());
                    playerInfo.put("level", player.getLevel());
                    playerInfo.put("health", player.getHealth());
                    playerInfo.put("food_level", player.getFoodLevel());
                    playerInfo.put("game_mode", player.getGameMode().name());
                    playerInfo.put("world", player.getWorld().getName());
                    
                    // 位置信息
                    Map<String, Object> location = new HashMap<>();
                    location.put("x", player.getLocation().getX());
                    location.put("y", player.getLocation().getY());
                    location.put("z", player.getLocation().getZ());
                    location.put("yaw", player.getLocation().getYaw());
                    location.put("pitch", player.getLocation().getPitch());
                    playerInfo.put("location", location);
                    
                    // 连接信息
                    playerInfo.put("ping", getPing(player));
                    playerInfo.put("ip", player.getAddress() != null ? 
                        player.getAddress().getAddress().getHostAddress() : "unknown");
                    
                    playerList.add(playerInfo);
                }
                
                playersData.put("players", playerList);
                playersData.put("timestamp", System.currentTimeMillis());
                
                // 缓存数据
                cacheManager.put(CACHE_PLAYERS, playersData, 
                    plugin.getConfigManager().getPlayersCacheTime());
                
                return playersData;
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "获取玩家数据失败", e);
                return getErrorResponse("Failed to get players data", e);
            }
        }, executorService);
    }
    
    /**
     * 异步获取世界数据
     */
    public CompletableFuture<Map<String, Object>> getWorldsDataAsync() {
        // 检查缓存
        Map<String, Object> cached = cacheManager.get(CACHE_WORLDS, Map.class);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> worldsData = new HashMap<>();
                
                List<World> worlds = Bukkit.getWorlds();
                List<Map<String, Object>> worldList = new ArrayList<>();
                
                for (World world : worlds) {
                    Map<String, Object> worldInfo = new HashMap<>();
                    worldInfo.put("name", world.getName());
                    worldInfo.put("environment", world.getEnvironment().name());
                    
                    // 添加维度信息
                    Map<String, Object> dimensionInfo = getDimensionInfo(world.getEnvironment());
                    worldInfo.put("dimension_type", dimensionInfo.get("type"));
                    worldInfo.put("dimension_name", dimensionInfo.get("name"));
                    worldInfo.put("dimension_id", dimensionInfo.get("id"));
                    
                    worldInfo.put("difficulty", world.getDifficulty().name());
                    worldInfo.put("spawn_location", Map.of(
                        "x", world.getSpawnLocation().getX(),
                        "y", world.getSpawnLocation().getY(),
                        "z", world.getSpawnLocation().getZ()
                    ));
                    worldInfo.put("time", world.getTime());
                    worldInfo.put("full_time", world.getFullTime());
                    worldInfo.put("weather_duration", world.getWeatherDuration());
                    worldInfo.put("thunder_duration", world.getThunderDuration());
                    worldInfo.put("has_storm", world.hasStorm());
                    worldInfo.put("thundering", world.isThundering());
                    
                    // 实体统计
                    worldInfo.put("entity_count", world.getEntities().size());
                    worldInfo.put("living_entity_count", world.getLivingEntities().size());
                    worldInfo.put("player_count", world.getPlayers().size());
                    
                    // 加载的区块数量
                    worldInfo.put("loaded_chunks", world.getLoadedChunks().length);
                    
                    worldList.add(worldInfo);
                }
                
                worldsData.put("worlds", worldList);
                worldsData.put("world_count", worlds.size());
                worldsData.put("timestamp", System.currentTimeMillis());
                
                // 缓存数据
                cacheManager.put(CACHE_WORLDS, worldsData, 
                    plugin.getConfigManager().getWorldsCacheTime());
                
                return worldsData;
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "获取世界数据失败", e);
                return getErrorResponse("Failed to get worlds data", e);
            }
        }, executorService);
    }
    
    /**
     * 获取系统资源使用情况
     */
    public CompletableFuture<Map<String, Object>> getSystemResourcesAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> resources = new HashMap<>();
                
                // JVM内存信息
                Runtime runtime = Runtime.getRuntime();
                Map<String, Object> memory = new HashMap<>();
                memory.put("max", runtime.maxMemory());
                memory.put("total", runtime.totalMemory());
                memory.put("free", runtime.freeMemory());
                memory.put("used", runtime.totalMemory() - runtime.freeMemory());
                memory.put("usage_percent", 
                    ((double)(runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory()) * 100);
                resources.put("memory", memory);
                
                // 系统信息
                Map<String, Object> system = new HashMap<>();
                system.put("os_name", System.getProperty("os.name"));
                system.put("os_version", System.getProperty("os.version"));
                system.put("os_arch", System.getProperty("os.arch"));
                system.put("java_version", System.getProperty("java.version"));
                system.put("java_vendor", System.getProperty("java.vendor"));
                system.put("available_processors", Runtime.getRuntime().availableProcessors());
                resources.put("system", system);
                
                resources.put("timestamp", System.currentTimeMillis());
                
                return resources;
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "获取系统资源信息失败", e);
                return getErrorResponse("Failed to get system resources", e);
            }
        }, executorService);
    }
    
    // 辅助方法
    
    private long getServerStartTime() {
        // 简单估算服务器启动时间
        return System.currentTimeMillis() - (System.currentTimeMillis() % 1000);
    }
    
    private String formatUptime(long uptimeMillis) {
        long seconds = uptimeMillis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return String.format("%dd %dh %dm %ds", days, hours % 24, minutes % 60, seconds % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }
    
    private int getDaemonThreadCount() {
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        while (rootGroup.getParent() != null) {
            rootGroup = rootGroup.getParent();
        }
        
        Thread[] threads = new Thread[rootGroup.activeCount()];
        rootGroup.enumerate(threads);
        
        int daemonCount = 0;
        for (Thread thread : threads) {
            if (thread != null && thread.isDaemon()) {
                daemonCount++;
            }
        }
        return daemonCount;
    }
    
    private int getPing(Player player) {
        try {
            // 使用反射获取ping值
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return (Integer) handle.getClass().getField("ping").get(handle);
        } catch (Exception e) {
            return -1; // 无法获取ping值
        }
    }
    
    private Map<String, Object> getErrorResponse(String message, Throwable throwable) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", true);
        error.put("message", message);
        error.put("details", throwable.getMessage());
        error.put("timestamp", System.currentTimeMillis());
        return error;
    }
    
    /**
     * 获取维度信息
     */
    private Map<String, Object> getDimensionInfo(World.Environment environment) {
        Map<String, Object> dimensionInfo = new HashMap<>();
        
        switch (environment) {
            case NORMAL:
                dimensionInfo.put("type", "overworld");
                dimensionInfo.put("name", "主世界");
                dimensionInfo.put("id", 0);
                break;
            case NETHER:
                dimensionInfo.put("type", "the_nether");
                dimensionInfo.put("name", "下界");
                dimensionInfo.put("id", -1);
                break;
            case THE_END:
                dimensionInfo.put("type", "the_end");
                dimensionInfo.put("name", "末地");
                dimensionInfo.put("id", 1);
                break;
            default:
                dimensionInfo.put("type", "unknown");
                dimensionInfo.put("name", "未知维度");
                dimensionInfo.put("id", 999);
                break;
        }
        
        return dimensionInfo;
    }
    
    /**
     * 关闭数据收集器
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}