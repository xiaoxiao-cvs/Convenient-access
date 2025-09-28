package com.xaoxiao.convenientaccess.cache;

import com.xaoxiao.convenientaccess.config.ConfigManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 缓存管理器
 * 提供线程安全的数据缓存功能
 */
public class CacheManager {
    
    private final ConfigManager configManager;
    private final ConcurrentHashMap<String, CacheEntry> cache;
    private final ScheduledExecutorService cleanupExecutor;
    
    public CacheManager(ConfigManager configManager) {
        this.configManager = configManager;
        this.cache = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConvenientAccess-Cache-Cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // 每分钟清理一次过期缓存
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired, 1, 1, TimeUnit.MINUTES);
    }
    
    /**
     * 存储数据到缓存
     */
    public void put(String key, Object data, int ttlSeconds) {
        long expireTime = System.currentTimeMillis() + (ttlSeconds * 1000L);
        cache.put(key, new CacheEntry(data, expireTime));
    }
    
    /**
     * 从缓存获取数据
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.isExpired()) {
            cache.remove(key);
            return null;
        }
        
        try {
            return type.cast(entry.getData());
        } catch (ClassCastException e) {
            cache.remove(key);
            return null;
        }
    }
    
    /**
     * 检查缓存是否存在且未过期
     */
    public boolean contains(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.isExpired()) {
            cache.remove(key);
            return false;
        }
        return true;
    }
    
    /**
     * 移除指定缓存
     */
    public void remove(String key) {
        cache.remove(key);
    }
    
    /**
     * 清理所有缓存
     */
    public void clearAll() {
        cache.clear();
    }
    
    /**
     * 清理过期缓存
     */
    private void cleanupExpired() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        int totalEntries = cache.size();
        int expiredEntries = (int) cache.values().stream().filter(CacheEntry::isExpired).count();
        return new CacheStats(totalEntries, expiredEntries);
    }
    
    /**
     * 缓存条目类
     */
    private static class CacheEntry {
        private final Object data;
        private final long expireTime;
        
        public CacheEntry(Object data, long expireTime) {
            this.data = data;
            this.expireTime = expireTime;
        }
        
        public Object getData() {
            return data;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }
    
    /**
     * 缓存统计信息类
     */
    public static class CacheStats {
        private final int totalEntries;
        private final int expiredEntries;
        
        public CacheStats(int totalEntries, int expiredEntries) {
            this.totalEntries = totalEntries;
            this.expiredEntries = expiredEntries;
        }
        
        public int getTotalEntries() {
            return totalEntries;
        }
        
        public int getExpiredEntries() {
            return expiredEntries;
        }
        
        public int getValidEntries() {
            return totalEntries - expiredEntries;
        }
    }
    
    /**
     * 关闭缓存管理器
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        clearAll();
    }
}