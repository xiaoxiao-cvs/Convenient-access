package com.xaoxiao.convenientaccess.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登录尝试限制服务
 * 防止暴力破解攻击
 * 注意: 由于使用FRP等内网穿透,IP地址会变成回环地址,因此仅基于用户名进行限制
 */
public class LoginAttemptService {
    private static final Logger logger = LoggerFactory.getLogger(LoginAttemptService.class);
    
    // 失败尝试记录: key = "username", value = 失败记录
    private final Map<String, FailedAttempt> attempts = new ConcurrentHashMap<>();
    
    // 配置参数
    private final int maxAttempts;              // 最大失败次数
    private final long lockDurationMinutes;     // 锁定时长(分钟)
    private final boolean enabled;              // 是否启用
    
    // 定时清理任务
    private final ScheduledExecutorService cleanupExecutor;
    
    /**
     * 构造函数
     * @param maxAttempts 最大失败次数
     * @param lockDurationMinutes 锁定时长(分钟)
     * @param enabled 是否启用
     */
    public LoginAttemptService(int maxAttempts, long lockDurationMinutes, boolean enabled) {
        this.maxAttempts = maxAttempts;
        this.lockDurationMinutes = lockDurationMinutes;
        this.enabled = enabled;
        
        // 启动定时清理任务,每小时清理一次过期记录
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "LoginAttempt-Cleanup");
            thread.setDaemon(true);
            return thread;
        });
        
        cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredAttempts,
            1, 1, TimeUnit.HOURS
        );
        
        logger.info("登录尝试限制服务已启动 - 最大尝试次数: {}, 锁定时长: {}分钟, 启用: {}", 
                   maxAttempts, lockDurationMinutes, enabled);
    }
    
    /**
     * 检查是否被锁定
     * @param username 用户名
     * @param ipAddress IP地址 (已弃用,保留参数以兼容)
     * @return 是否被锁定
     */
    public boolean isBlocked(String username, String ipAddress) {
        if (!enabled) {
            return false; // 功能未启用
        }
        
        String key = buildKey(username);
        FailedAttempt attempt = attempts.get(key);
        
        if (attempt == null) {
            return false; // 没有失败记录
        }
        
        // 检查是否达到最大尝试次数
        if (attempt.getCount() >= maxAttempts) {
            long lockEndTime = attempt.getLastAttemptTime() + (lockDurationMinutes * 60 * 1000);
            long now = System.currentTimeMillis();
            
            if (now < lockEndTime) {
                // 仍在锁定期内
                long remainingMinutes = (lockEndTime - now) / (60 * 1000);
                logger.warn("账号锁定 - 用户: {}, 剩余时间: {}分钟", 
                           username, remainingMinutes);
                return true;
            } else {
                // 锁定期已过,自动解锁
                attempts.remove(key);
                logger.info("账号自动解锁 - 用户: {}", username);
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * 记录登录失败
     * @param username 用户名
     * @param ipAddress IP地址 (已弃用,保留参数以兼容)
     */
    public void recordFailure(String username, String ipAddress) {
        if (!enabled) {
            return; // 功能未启用
        }
        
        String key = buildKey(username);
        FailedAttempt attempt = attempts.computeIfAbsent(key, k -> new FailedAttempt());
        
        attempt.incrementCount();
        attempt.setLastAttemptTime(System.currentTimeMillis());
        attempt.setUsername(username);
        
        int count = attempt.getCount();
        if (count >= maxAttempts) {
            logger.warn("⚠️ 账号已锁定 - 用户: {}, 失败次数: {}, 锁定时长: {}分钟", 
                       username, count, lockDurationMinutes);
        } else {
            logger.info("登录失败记录 - 用户: {}, 失败次数: {}/{}", 
                       username, count, maxAttempts);
        }
    }
    
    /**
     * 重置失败记录(登录成功时调用)
     * @param username 用户名
     * @param ipAddress IP地址 (已弃用,保留参数以兼容)
     */
    public void resetAttempts(String username, String ipAddress) {
        if (!enabled) {
            return;
        }
        
        String key = buildKey(username);
        FailedAttempt removed = attempts.remove(key);
        
        if (removed != null) {
            logger.info("清除失败记录 - 用户: {}, 之前失败次数: {}", 
                       username, removed.getCount());
        }
    }
    
    /**
     * 获取剩余锁定时间(秒)
     * @param username 用户名
     * @param ipAddress IP地址 (已弃用,保留参数以兼容)
     * @return 剩余锁定时间(秒), 如果未锁定返回0
     */
    public long getRemainingLockTime(String username, String ipAddress) {
        if (!enabled) {
            return 0;
        }
        
        String key = buildKey(username);
        FailedAttempt attempt = attempts.get(key);
        
        if (attempt == null || attempt.getCount() < maxAttempts) {
            return 0;
        }
        
        long lockEndTime = attempt.getLastAttemptTime() + (lockDurationMinutes * 60 * 1000);
        long now = System.currentTimeMillis();
        
        if (now < lockEndTime) {
            return (lockEndTime - now) / 1000;
        }
        
        return 0;
    }
    
    /**
     * 获取失败次数
     * @param username 用户名
     * @param ipAddress IP地址 (已弃用,保留参数以兼容)
     * @return 失败次数
     */
    public int getFailureCount(String username, String ipAddress) {
        if (!enabled) {
            return 0;
        }
        
        String key = buildKey(username);
        FailedAttempt attempt = attempts.get(key);
        return attempt != null ? attempt.getCount() : 0;
    }
    
    /**
     * 手动解锁账号(管理员操作)
     * @param username 用户名
     * @param ipAddress IP地址 (已弃用,保留参数以兼容)
     */
    public void unlockAccount(String username, String ipAddress) {
        String key = buildKey(username);
        FailedAttempt removed = attempts.remove(key);
        
        if (removed != null) {
            logger.info("✅ 账号已手动解锁 - 用户: {}, 失败次数: {}", 
                       username, removed.getCount());
        }
    }
    
    /**
     * 清理过期的失败记录
     */
    private void cleanupExpiredAttempts() {
        long now = System.currentTimeMillis();
        long expirationTime = lockDurationMinutes * 60 * 1000 * 2; // 锁定时长的2倍
        
        int removed = 0;
        for (Map.Entry<String, FailedAttempt> entry : attempts.entrySet()) {
            FailedAttempt attempt = entry.getValue();
            if (now - attempt.getLastAttemptTime() > expirationTime) {
                attempts.remove(entry.getKey());
                removed++;
            }
        }
        
        if (removed > 0) {
            logger.info("清理过期登录失败记录: {} 条", removed);
        }
    }
    
    /**
     * 构建缓存key
     * 注意: 由于FRP内网穿透会导致所有IP变成127.0.0.1,因此仅使用用户名作为key
     */
    private String buildKey(String username) {
        return username.toLowerCase();
    }
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("enabled", enabled);
        stats.put("max_attempts", maxAttempts);
        stats.put("lock_duration_minutes", lockDurationMinutes);
        stats.put("total_records", attempts.size());
        
        long lockedCount = attempts.values().stream()
            .filter(a -> a.getCount() >= maxAttempts)
            .count();
        stats.put("locked_accounts", lockedCount);
        
        return stats;
    }
    
    /**
     * 关闭服务
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        logger.info("登录尝试限制服务已关闭");
    }
    
    /**
     * 失败尝试记录类
     */
    private static class FailedAttempt {
        private int count = 0;
        private long lastAttemptTime = 0;
        @SuppressWarnings("unused")
        private String username;
        
        public void incrementCount() {
            this.count++;
        }
        
        public int getCount() {
            return count;
        }
        
        public long getLastAttemptTime() {
            return lastAttemptTime;
        }
        
        public void setLastAttemptTime(long time) {
            this.lastAttemptTime = time;
        }
        
        public void setUsername(String username) {
            this.username = username;
        }
    }
}
