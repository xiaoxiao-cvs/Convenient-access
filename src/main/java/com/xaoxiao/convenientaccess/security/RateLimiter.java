package com.xaoxiao.convenientaccess.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求频率限制器
 * 实现滑动窗口算法，防止API滥用和暴力破解攻击
 */
public class RateLimiter {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);
    
    private final ConcurrentHashMap<String, ClientRateInfo> clientRates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;
    
    // 默认限制配置
    private static final int DEFAULT_MAX_REQUESTS = 100;
    private static final int DEFAULT_WINDOW_MINUTES = 1;
    private static final int LOGIN_MAX_ATTEMPTS = 5;
    private static final int LOGIN_WINDOW_MINUTES = 15;
    private static final int API_MAX_REQUESTS = 1000;
    private static final int API_WINDOW_MINUTES = 60;
    
    public RateLimiter() {
        this.cleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "RateLimiter-Cleanup");
            thread.setDaemon(true);
            return thread;
        });
        
        // 每5分钟清理过期的客户端记录
        cleanupExecutor.scheduleWithFixedDelay(this::cleanupExpiredEntries, 5, 5, TimeUnit.MINUTES);
    }
    
    /**
     * 检查请求是否被限制
     */
    public RateLimitResult checkLimit(String clientId, RateLimitType limitType) {
        return checkLimit(clientId, limitType, 1);
    }
    
    /**
     * 检查请求是否被限制（指定请求数量）
     */
    public RateLimitResult checkLimit(String clientId, RateLimitType limitType, int requestCount) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return RateLimitResult.allowed();
        }
        
        String key = generateKey(clientId, limitType);
        ClientRateInfo rateInfo = clientRates.computeIfAbsent(key, k -> new ClientRateInfo(limitType));
        
        synchronized (rateInfo) {
            LocalDateTime now = LocalDateTime.now();
            
            // 清理过期的请求记录
            rateInfo.cleanupExpiredRequests(now);
            
            // 检查是否超过限制
            int currentRequests = rateInfo.getCurrentRequests();
            int maxRequests = getMaxRequests(limitType);
            
            if (currentRequests + requestCount > maxRequests) {
                // 超过限制
                rateInfo.incrementBlockedCount();
                
                LocalDateTime resetTime = rateInfo.getEarliestRequestTime().plus(getWindowMinutes(limitType), ChronoUnit.MINUTES);
                long resetInSeconds = ChronoUnit.SECONDS.between(now, resetTime);
                
                logger.warn("请求被限制: {} - 类型: {}, 当前: {}, 限制: {}, 重置时间: {}秒", 
                        clientId, limitType, currentRequests, maxRequests, resetInSeconds);
                
                return RateLimitResult.blocked(maxRequests, currentRequests, resetInSeconds);
            } else {
                // 允许请求
                rateInfo.addRequests(now, requestCount);
                
                long remaining = maxRequests - (currentRequests + requestCount);
                LocalDateTime resetTime = rateInfo.getEarliestRequestTime().plus(getWindowMinutes(limitType), ChronoUnit.MINUTES);
                long resetInSeconds = ChronoUnit.SECONDS.between(now, resetTime);
                
                return RateLimitResult.allowed(maxRequests, remaining, resetInSeconds);
            }
        }
    }
    
    /**
     * 获取客户端限制状态
     */
    public RateLimitStatus getStatus(String clientId, RateLimitType limitType) {
        String key = generateKey(clientId, limitType);
        ClientRateInfo rateInfo = clientRates.get(key);
        
        if (rateInfo == null) {
            return new RateLimitStatus(limitType, 0, 0, 0, LocalDateTime.now());
        }
        
        synchronized (rateInfo) {
            LocalDateTime now = LocalDateTime.now();
            rateInfo.cleanupExpiredRequests(now);
            
            return new RateLimitStatus(
                    limitType,
                    rateInfo.getCurrentRequests(),
                    rateInfo.getBlockedCount(),
                    getMaxRequests(limitType),
                    rateInfo.getLastRequestTime()
            );
        }
    }
    
    /**
     * 重置客户端限制
     */
    public void resetLimit(String clientId, RateLimitType limitType) {
        String key = generateKey(clientId, limitType);
        ClientRateInfo removed = clientRates.remove(key);
        
        if (removed != null) {
            logger.info("重置客户端限制: {} - 类型: {}", clientId, limitType);
        }
    }
    
    /**
     * 清理所有过期记录
     */
    public void cleanupExpiredEntries() {
        LocalDateTime cutoff = LocalDateTime.now().minus(2, ChronoUnit.HOURS);
        
        clientRates.entrySet().removeIf(entry -> {
            ClientRateInfo rateInfo = entry.getValue();
            synchronized (rateInfo) {
                return rateInfo.getLastRequestTime().isBefore(cutoff);
            }
        });
        
        logger.debug("清理过期的限制记录，当前活跃客户端: {}", clientRates.size());
    }
    
    /**
     * 获取统计信息
     */
    public RateLimiterStats getStats() {
        int totalClients = clientRates.size();
        int blockedClients = 0;
        int totalRequests = 0;
        int totalBlocked = 0;
        
        LocalDateTime now = LocalDateTime.now();
        
        for (ClientRateInfo rateInfo : clientRates.values()) {
            synchronized (rateInfo) {
                rateInfo.cleanupExpiredRequests(now);
                totalRequests += rateInfo.getCurrentRequests();
                totalBlocked += rateInfo.getBlockedCount();
                
                if (rateInfo.getBlockedCount() > 0) {
                    blockedClients++;
                }
            }
        }
        
        return new RateLimiterStats(totalClients, blockedClients, totalRequests, totalBlocked);
    }
    
    /**
     * 关闭限制器
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
        
        clientRates.clear();
        logger.info("请求频率限制器已关闭");
    }
    
    /**
     * 生成客户端键
     */
    private String generateKey(String clientId, RateLimitType limitType) {
        return clientId + ":" + limitType.name();
    }
    
    /**
     * 获取最大请求数
     */
    private int getMaxRequests(RateLimitType limitType) {
        switch (limitType) {
            case LOGIN:
                return LOGIN_MAX_ATTEMPTS;
            case API:
                return API_MAX_REQUESTS;
            case GENERAL:
            default:
                return DEFAULT_MAX_REQUESTS;
        }
    }
    
    /**
     * 获取时间窗口（分钟）
     */
    private int getWindowMinutes(RateLimitType limitType) {
        switch (limitType) {
            case LOGIN:
                return LOGIN_WINDOW_MINUTES;
            case API:
                return API_WINDOW_MINUTES;
            case GENERAL:
            default:
                return DEFAULT_WINDOW_MINUTES;
        }
    }
    
    /**
     * 限制类型枚举
     */
    public enum RateLimitType {
        LOGIN,      // 登录请求
        API,        // API请求
        GENERAL     // 一般请求
    }
    
    /**
     * 客户端频率信息
     */
    private static class ClientRateInfo {
        private final RateLimitType limitType;
        private final ConcurrentHashMap<LocalDateTime, Integer> requestTimes = new ConcurrentHashMap<>();
        private final AtomicInteger blockedCount = new AtomicInteger(0);
        private volatile LocalDateTime lastRequestTime = LocalDateTime.now();
        
        public ClientRateInfo(RateLimitType limitType) {
            this.limitType = limitType;
        }
        
        public void addRequests(LocalDateTime time, int count) {
            requestTimes.put(time, count);
            lastRequestTime = time;
        }
        
        public void cleanupExpiredRequests(LocalDateTime now) {
            int windowMinutes = getWindowMinutes();
            LocalDateTime cutoff = now.minus(windowMinutes, ChronoUnit.MINUTES);
            
            requestTimes.entrySet().removeIf(entry -> entry.getKey().isBefore(cutoff));
        }
        
        public int getCurrentRequests() {
            return requestTimes.values().stream().mapToInt(Integer::intValue).sum();
        }
        
        public LocalDateTime getEarliestRequestTime() {
            return requestTimes.keySet().stream()
                    .min(LocalDateTime::compareTo)
                    .orElse(LocalDateTime.now());
        }
        
        public LocalDateTime getLastRequestTime() {
            return lastRequestTime;
        }
        
        public void incrementBlockedCount() {
            blockedCount.incrementAndGet();
        }
        
        public int getBlockedCount() {
            return blockedCount.get();
        }
        
        private int getWindowMinutes() {
            switch (limitType) {
                case LOGIN:
                    return LOGIN_WINDOW_MINUTES;
                case API:
                    return API_WINDOW_MINUTES;
                case GENERAL:
                default:
                    return DEFAULT_WINDOW_MINUTES;
            }
        }
    }
    
    /**
     * 限制结果类
     */
    public static class RateLimitResult {
        private final boolean allowed;
        private final int limit;
        private final long remaining;
        private final long resetInSeconds;
        private final String message;
        
        private RateLimitResult(boolean allowed, int limit, long remaining, long resetInSeconds, String message) {
            this.allowed = allowed;
            this.limit = limit;
            this.remaining = remaining;
            this.resetInSeconds = resetInSeconds;
            this.message = message;
        }
        
        public static RateLimitResult allowed() {
            return new RateLimitResult(true, 0, 0, 0, "请求允许");
        }
        
        public static RateLimitResult allowed(int limit, long remaining, long resetInSeconds) {
            return new RateLimitResult(true, limit, remaining, resetInSeconds, "请求允许");
        }
        
        public static RateLimitResult blocked(int limit, long current, long resetInSeconds) {
            String message = String.format("请求频率超限，当前: %d, 限制: %d, %d秒后重置", current, limit, resetInSeconds);
            return new RateLimitResult(false, limit, 0, resetInSeconds, message);
        }
        
        // Getters
        public boolean isAllowed() { return allowed; }
        public int getLimit() { return limit; }
        public long getRemaining() { return remaining; }
        public long getResetInSeconds() { return resetInSeconds; }
        public String getMessage() { return message; }
    }
    
    /**
     * 限制状态类
     */
    public static class RateLimitStatus {
        private final RateLimitType limitType;
        private final int currentRequests;
        private final int blockedCount;
        private final int maxRequests;
        private final LocalDateTime lastRequestTime;
        
        public RateLimitStatus(RateLimitType limitType, int currentRequests, int blockedCount, 
                              int maxRequests, LocalDateTime lastRequestTime) {
            this.limitType = limitType;
            this.currentRequests = currentRequests;
            this.blockedCount = blockedCount;
            this.maxRequests = maxRequests;
            this.lastRequestTime = lastRequestTime;
        }
        
        // Getters
        public RateLimitType getLimitType() { return limitType; }
        public int getCurrentRequests() { return currentRequests; }
        public int getBlockedCount() { return blockedCount; }
        public int getMaxRequests() { return maxRequests; }
        public LocalDateTime getLastRequestTime() { return lastRequestTime; }
        
        public double getUsagePercentage() {
            return maxRequests > 0 ? (double) currentRequests / maxRequests * 100 : 0;
        }
    }
    
    /**
     * 限制器统计信息类
     */
    public static class RateLimiterStats {
        private final int totalClients;
        private final int blockedClients;
        private final int totalRequests;
        private final int totalBlocked;
        
        public RateLimiterStats(int totalClients, int blockedClients, int totalRequests, int totalBlocked) {
            this.totalClients = totalClients;
            this.blockedClients = blockedClients;
            this.totalRequests = totalRequests;
            this.totalBlocked = totalBlocked;
        }
        
        // Getters
        public int getTotalClients() { return totalClients; }
        public int getBlockedClients() { return blockedClients; }
        public int getTotalRequests() { return totalRequests; }
        public int getTotalBlocked() { return totalBlocked; }
        
        public double getBlockedPercentage() {
            return totalClients > 0 ? (double) blockedClients / totalClients * 100 : 0;
        }
        
        @Override
        public String toString() {
            return String.format("RateLimiterStats{clients=%d, blocked=%d(%.1f%%), requests=%d, totalBlocked=%d}",
                    totalClients, blockedClients, getBlockedPercentage(), totalRequests, totalBlocked);
        }
    }
}