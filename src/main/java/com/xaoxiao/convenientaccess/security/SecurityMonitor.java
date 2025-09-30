package com.xaoxiao.convenientaccess.security;

import com.xaoxiao.convenientaccess.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安全监控器
 * 检测异常行为、潜在攻击和安全威胁
 */
public class SecurityMonitor {
    private static final Logger logger = LoggerFactory.getLogger(SecurityMonitor.class);
    
    private final DatabaseManager databaseManager;
    private final ScheduledExecutorService monitorExecutor;
    private final Map<String, ClientSecurityInfo> clientSecurityMap = new ConcurrentHashMap<>();
    private final List<SecurityEventListener> eventListeners = new ArrayList<>();
    
    // 监控配置
    private static final int FAILED_LOGIN_THRESHOLD = 5;
    private static final int SUSPICIOUS_IP_THRESHOLD = 10;
    private static final int API_ABUSE_THRESHOLD = 1000;
    private static final int MONITOR_WINDOW_MINUTES = 15;
    private static final int CLEANUP_INTERVAL_MINUTES = 30;
    
    public SecurityMonitor(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.monitorExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "SecurityMonitor");
            thread.setDaemon(true);
            return thread;
        });
        
        startMonitoring();
    }
    
    /**
     * 启动安全监控
     */
    private void startMonitoring() {
        // 每5分钟检查一次安全事件
        monitorExecutor.scheduleWithFixedDelay(this::checkSecurityEvents, 1, 5, TimeUnit.MINUTES);
        
        // 每30分钟清理过期数据
        monitorExecutor.scheduleWithFixedDelay(this::cleanupExpiredData, 
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        
        logger.info("安全监控器已启动");
    }
    
    /**
     * 记录登录尝试
     */
    public void recordLoginAttempt(String clientIp, String username, boolean success, String userAgent) {
        ClientSecurityInfo securityInfo = clientSecurityMap.computeIfAbsent(clientIp, 
                k -> new ClientSecurityInfo(clientIp));
        
        securityInfo.recordLoginAttempt(username, success, userAgent);
        
        if (!success) {
            // 检查是否达到失败登录阈值
            if (securityInfo.getFailedLoginCount(MONITOR_WINDOW_MINUTES) >= FAILED_LOGIN_THRESHOLD) {
                triggerSecurityEvent(SecurityEventType.BRUTE_FORCE_ATTACK, clientIp, 
                        "检测到暴力破解攻击，失败登录次数: " + securityInfo.getFailedLoginCount(MONITOR_WINDOW_MINUTES));
            }
        }
        
        // 检查是否为可疑IP
        if (securityInfo.getTotalLoginAttempts(MONITOR_WINDOW_MINUTES) >= SUSPICIOUS_IP_THRESHOLD) {
            triggerSecurityEvent(SecurityEventType.SUSPICIOUS_IP, clientIp,
                    "检测到可疑IP活动，登录尝试次数: " + securityInfo.getTotalLoginAttempts(MONITOR_WINDOW_MINUTES));
        }
    }
    
    /**
     * 记录API请求
     */
    public void recordApiRequest(String clientIp, String endpoint, int statusCode, String userAgent) {
        ClientSecurityInfo securityInfo = clientSecurityMap.computeIfAbsent(clientIp, 
                k -> new ClientSecurityInfo(clientIp));
        
        securityInfo.recordApiRequest(endpoint, statusCode, userAgent);
        
        // 检查API滥用
        if (securityInfo.getApiRequestCount(MONITOR_WINDOW_MINUTES) >= API_ABUSE_THRESHOLD) {
            triggerSecurityEvent(SecurityEventType.API_ABUSE, clientIp,
                    "检测到API滥用，请求次数: " + securityInfo.getApiRequestCount(MONITOR_WINDOW_MINUTES));
        }
        
        // 检查异常状态码
        if (statusCode >= 400) {
            int errorCount = securityInfo.getErrorRequestCount(MONITOR_WINDOW_MINUTES);
            if (errorCount >= 50) {
                triggerSecurityEvent(SecurityEventType.ABNORMAL_BEHAVIOR, clientIp,
                        "检测到异常行为，错误请求次数: " + errorCount);
            }
        }
    }
    
    /**
     * 检查安全事件
     */
    private void checkSecurityEvents() {
        try {
            // 检查数据库中的认证日志
            checkAuthenticationLogs();
            
            // 检查操作日志
            checkOperationLogs();
            
            // 分析客户端行为模式
            analyzeClientBehaviorPatterns();
            
        } catch (Exception e) {
            logger.error("检查安全事件时发生错误", e);
        }
    }
    
    /**
     * 检查认证日志
     */
    private void checkAuthenticationLogs() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minus(MONITOR_WINDOW_MINUTES, ChronoUnit.MINUTES);
            
            String sql = """
                SELECT ip_address, COUNT(*) as failed_count
                FROM auth_logs 
                WHERE created_at >= ? AND success = 0 AND event_type = 'LOGIN_FAILED'
                GROUP BY ip_address
                HAVING failed_count >= ?
            """;
            
            databaseManager.executeAsync(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setTimestamp(1, Timestamp.valueOf(cutoff));
                    stmt.setInt(2, FAILED_LOGIN_THRESHOLD);
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String ipAddress = rs.getString("ip_address");
                            int failedCount = rs.getInt("failed_count");
                            
                            triggerSecurityEvent(SecurityEventType.BRUTE_FORCE_ATTACK, ipAddress,
                                    "数据库检测到暴力破解攻击，失败次数: " + failedCount);
                        }
                    }
                }
                return null;
            });
            
        } catch (Exception e) {
            logger.error("检查认证日志失败", e);
        }
    }
    
    /**
     * 检查操作日志
     */
    private void checkOperationLogs() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minus(MONITOR_WINDOW_MINUTES, ChronoUnit.MINUTES);
            
            String sql = """
                SELECT operator_ip, COUNT(*) as request_count
                FROM operation_log 
                WHERE created_at >= ? AND response_status >= 400
                GROUP BY operator_ip
                HAVING request_count >= ?
            """;
            
            databaseManager.executeAsync(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setTimestamp(1, Timestamp.valueOf(cutoff));
                    stmt.setInt(2, 50);
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String ipAddress = rs.getString("operator_ip");
                            int requestCount = rs.getInt("request_count");
                            
                            triggerSecurityEvent(SecurityEventType.ABNORMAL_BEHAVIOR, ipAddress,
                                    "检测到异常操作行为，错误请求次数: " + requestCount);
                        }
                    }
                }
                return null;
            });
            
        } catch (Exception e) {
            logger.error("检查操作日志失败", e);
        }
    }
    
    /**
     * 分析客户端行为模式
     */
    private void analyzeClientBehaviorPatterns() {
        LocalDateTime now = LocalDateTime.now();
        
        for (ClientSecurityInfo securityInfo : clientSecurityMap.values()) {
            // 检查短时间内大量不同用户名登录尝试
            Set<String> attemptedUsernames = securityInfo.getAttemptedUsernames(MONITOR_WINDOW_MINUTES);
            if (attemptedUsernames.size() >= 10) {
                triggerSecurityEvent(SecurityEventType.USERNAME_ENUMERATION, securityInfo.getClientIp(),
                        "检测到用户名枚举攻击，尝试用户名数量: " + attemptedUsernames.size());
            }
            
            // 检查异常的User-Agent模式
            Set<String> userAgents = securityInfo.getUserAgents(MONITOR_WINDOW_MINUTES);
            if (userAgents.size() >= 5) {
                triggerSecurityEvent(SecurityEventType.SUSPICIOUS_USER_AGENT, securityInfo.getClientIp(),
                        "检测到可疑User-Agent模式，数量: " + userAgents.size());
            }
            
            // 检查请求时间模式（机器人行为）
            if (securityInfo.isRobotLikeBehavior(MONITOR_WINDOW_MINUTES)) {
                triggerSecurityEvent(SecurityEventType.BOT_BEHAVIOR, securityInfo.getClientIp(),
                        "检测到机器人行为模式");
            }
        }
    }
    
    /**
     * 触发安全事件
     */
    private void triggerSecurityEvent(SecurityEventType eventType, String clientIp, String description) {
        SecurityEvent event = new SecurityEvent(eventType, clientIp, description, LocalDateTime.now());
        
        // 记录到日志
        logger.warn("安全事件: {} - IP: {} - {}", eventType, clientIp, description);
        
        // 保存到数据库
        saveSecurityEvent(event);
        
        // 通知监听器
        notifyEventListeners(event);
        
        // 根据事件类型采取相应措施
        handleSecurityEvent(event);
    }
    
    /**
     * 处理安全事件
     */
    private void handleSecurityEvent(SecurityEvent event) {
        switch (event.getEventType()) {
            case BRUTE_FORCE_ATTACK:
                // 可以考虑临时封禁IP
                logger.warn("建议临时封禁IP: {}", event.getClientIp());
                break;
                
            case API_ABUSE:
                // 可以考虑限制API访问
                logger.warn("建议限制API访问: {}", event.getClientIp());
                break;
                
            case SUSPICIOUS_IP:
                // 增加监控频率
                logger.warn("增加对IP的监控: {}", event.getClientIp());
                break;
                
            default:
                // 记录其他类型的事件
                break;
        }
    }
    
    /**
     * 保存安全事件到数据库
     */
    private void saveSecurityEvent(SecurityEvent event) {
        try {
            databaseManager.executeAsync(connection -> {
                String sql = """
                    INSERT INTO security_events (event_type, client_ip, description, severity, created_at)
                    VALUES (?, ?, ?, ?, ?)
                """;
                
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setString(1, event.getEventType().name());
                    stmt.setString(2, event.getClientIp());
                    stmt.setString(3, event.getDescription());
                    stmt.setString(4, event.getSeverity().name());
                    stmt.setTimestamp(5, Timestamp.valueOf(event.getTimestamp()));
                    
                    stmt.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("保存安全事件失败", e);
        }
    }
    
    /**
     * 清理过期数据
     */
    private void cleanupExpiredData() {
        LocalDateTime cutoff = LocalDateTime.now().minus(2, ChronoUnit.HOURS);
        
        clientSecurityMap.entrySet().removeIf(entry -> {
            ClientSecurityInfo securityInfo = entry.getValue();
            return securityInfo.getLastActivityTime().isBefore(cutoff);
        });
        
        logger.debug("清理过期安全数据，当前监控客户端: {}", clientSecurityMap.size());
    }
    
    /**
     * 添加事件监听器
     */
    public void addEventListener(SecurityEventListener listener) {
        eventListeners.add(listener);
    }
    
    /**
     * 通知事件监听器
     */
    private void notifyEventListeners(SecurityEvent event) {
        for (SecurityEventListener listener : eventListeners) {
            try {
                listener.onSecurityEvent(event);
            } catch (Exception e) {
                logger.error("通知安全事件监听器失败", e);
            }
        }
    }
    
    /**
     * 获取安全统计信息
     */
    public SecurityStats getSecurityStats() {
        int totalClients = clientSecurityMap.size();
        int suspiciousClients = 0;
        int totalLoginAttempts = 0;
        int failedLoginAttempts = 0;
        int totalApiRequests = 0;
        
        LocalDateTime now = LocalDateTime.now();
        
        for (ClientSecurityInfo securityInfo : clientSecurityMap.values()) {
            int loginAttempts = securityInfo.getTotalLoginAttempts(MONITOR_WINDOW_MINUTES);
            int failedLogins = securityInfo.getFailedLoginCount(MONITOR_WINDOW_MINUTES);
            int apiRequests = securityInfo.getApiRequestCount(MONITOR_WINDOW_MINUTES);
            
            totalLoginAttempts += loginAttempts;
            failedLoginAttempts += failedLogins;
            totalApiRequests += apiRequests;
            
            if (failedLogins >= FAILED_LOGIN_THRESHOLD || 
                apiRequests >= API_ABUSE_THRESHOLD ||
                loginAttempts >= SUSPICIOUS_IP_THRESHOLD) {
                suspiciousClients++;
            }
        }
        
        return new SecurityStats(totalClients, suspiciousClients, totalLoginAttempts, 
                failedLoginAttempts, totalApiRequests);
    }
    
    /**
     * 关闭安全监控器
     */
    public void shutdown() {
        monitorExecutor.shutdown();
        try {
            if (!monitorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        clientSecurityMap.clear();
        eventListeners.clear();
        logger.info("安全监控器已关闭");
    }
    
    /**
     * 安全事件类型枚举
     */
    public enum SecurityEventType {
        BRUTE_FORCE_ATTACK,     // 暴力破解攻击
        API_ABUSE,              // API滥用
        SUSPICIOUS_IP,          // 可疑IP
        ABNORMAL_BEHAVIOR,      // 异常行为
        USERNAME_ENUMERATION,   // 用户名枚举
        SUSPICIOUS_USER_AGENT,  // 可疑User-Agent
        BOT_BEHAVIOR           // 机器人行为
    }
    
    /**
     * 安全事件严重程度枚举
     */
    public enum SecuritySeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
    
    /**
     * 安全事件类
     */
    public static class SecurityEvent {
        private final SecurityEventType eventType;
        private final String clientIp;
        private final String description;
        private final LocalDateTime timestamp;
        private final SecuritySeverity severity;
        
        public SecurityEvent(SecurityEventType eventType, String clientIp, String description, LocalDateTime timestamp) {
            this.eventType = eventType;
            this.clientIp = clientIp;
            this.description = description;
            this.timestamp = timestamp;
            this.severity = determineSeverity(eventType);
        }
        
        private SecuritySeverity determineSeverity(SecurityEventType eventType) {
            switch (eventType) {
                case BRUTE_FORCE_ATTACK:
                    return SecuritySeverity.HIGH;
                case API_ABUSE:
                    return SecuritySeverity.MEDIUM;
                case SUSPICIOUS_IP:
                    return SecuritySeverity.MEDIUM;
                case ABNORMAL_BEHAVIOR:
                    return SecuritySeverity.LOW;
                case USERNAME_ENUMERATION:
                    return SecuritySeverity.HIGH;
                case SUSPICIOUS_USER_AGENT:
                    return SecuritySeverity.LOW;
                case BOT_BEHAVIOR:
                    return SecuritySeverity.MEDIUM;
                default:
                    return SecuritySeverity.LOW;
            }
        }
        
        // Getters
        public SecurityEventType getEventType() { return eventType; }
        public String getClientIp() { return clientIp; }
        public String getDescription() { return description; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public SecuritySeverity getSeverity() { return severity; }
    }
    
    /**
     * 客户端安全信息类
     */
    private static class ClientSecurityInfo {
        private final String clientIp;
        private final List<LoginAttempt> loginAttempts = new ArrayList<>();
        private final List<ApiRequest> apiRequests = new ArrayList<>();
        private volatile LocalDateTime lastActivityTime = LocalDateTime.now();
        
        public ClientSecurityInfo(String clientIp) {
            this.clientIp = clientIp;
        }
        
        public synchronized void recordLoginAttempt(String username, boolean success, String userAgent) {
            loginAttempts.add(new LoginAttempt(username, success, userAgent, LocalDateTime.now()));
            lastActivityTime = LocalDateTime.now();
        }
        
        public synchronized void recordApiRequest(String endpoint, int statusCode, String userAgent) {
            apiRequests.add(new ApiRequest(endpoint, statusCode, userAgent, LocalDateTime.now()));
            lastActivityTime = LocalDateTime.now();
        }
        
        public synchronized int getFailedLoginCount(int windowMinutes) {
            LocalDateTime cutoff = LocalDateTime.now().minus(windowMinutes, ChronoUnit.MINUTES);
            return (int) loginAttempts.stream()
                    .filter(attempt -> attempt.timestamp.isAfter(cutoff) && !attempt.success)
                    .count();
        }
        
        public synchronized int getTotalLoginAttempts(int windowMinutes) {
            LocalDateTime cutoff = LocalDateTime.now().minus(windowMinutes, ChronoUnit.MINUTES);
            return (int) loginAttempts.stream()
                    .filter(attempt -> attempt.timestamp.isAfter(cutoff))
                    .count();
        }
        
        public synchronized int getApiRequestCount(int windowMinutes) {
            LocalDateTime cutoff = LocalDateTime.now().minus(windowMinutes, ChronoUnit.MINUTES);
            return (int) apiRequests.stream()
                    .filter(request -> request.timestamp.isAfter(cutoff))
                    .count();
        }
        
        public synchronized int getErrorRequestCount(int windowMinutes) {
            LocalDateTime cutoff = LocalDateTime.now().minus(windowMinutes, ChronoUnit.MINUTES);
            return (int) apiRequests.stream()
                    .filter(request -> request.timestamp.isAfter(cutoff) && request.statusCode >= 400)
                    .count();
        }
        
        public synchronized Set<String> getAttemptedUsernames(int windowMinutes) {
            LocalDateTime cutoff = LocalDateTime.now().minus(windowMinutes, ChronoUnit.MINUTES);
            return loginAttempts.stream()
                    .filter(attempt -> attempt.timestamp.isAfter(cutoff))
                    .map(attempt -> attempt.username)
                    .collect(HashSet::new, HashSet::add, HashSet::addAll);
        }
        
        public synchronized Set<String> getUserAgents(int windowMinutes) {
            LocalDateTime cutoff = LocalDateTime.now().minus(windowMinutes, ChronoUnit.MINUTES);
            Set<String> userAgents = new HashSet<>();
            
            loginAttempts.stream()
                    .filter(attempt -> attempt.timestamp.isAfter(cutoff))
                    .forEach(attempt -> userAgents.add(attempt.userAgent));
            
            apiRequests.stream()
                    .filter(request -> request.timestamp.isAfter(cutoff))
                    .forEach(request -> userAgents.add(request.userAgent));
            
            return userAgents;
        }
        
        public synchronized boolean isRobotLikeBehavior(int windowMinutes) {
            LocalDateTime cutoff = LocalDateTime.now().minus(windowMinutes, ChronoUnit.MINUTES);
            
            List<LocalDateTime> allTimestamps = new ArrayList<>();
            loginAttempts.stream()
                    .filter(attempt -> attempt.timestamp.isAfter(cutoff))
                    .forEach(attempt -> allTimestamps.add(attempt.timestamp));
            
            apiRequests.stream()
                    .filter(request -> request.timestamp.isAfter(cutoff))
                    .forEach(request -> allTimestamps.add(request.timestamp));
            
            if (allTimestamps.size() < 10) {
                return false;
            }
            
            // 检查请求间隔是否过于规律（可能是机器人）
            allTimestamps.sort(LocalDateTime::compareTo);
            List<Long> intervals = new ArrayList<>();
            
            for (int i = 1; i < allTimestamps.size(); i++) {
                long interval = ChronoUnit.SECONDS.between(allTimestamps.get(i-1), allTimestamps.get(i));
                intervals.add(interval);
            }
            
            // 如果大部分间隔都相同或非常接近，可能是机器人
            if (intervals.size() >= 5) {
                long avgInterval = intervals.stream().mapToLong(Long::longValue).sum() / intervals.size();
                long similarCount = intervals.stream()
                        .mapToLong(interval -> Math.abs(interval - avgInterval) <= 2 ? 1 : 0)
                        .sum();
                
                return similarCount >= intervals.size() * 0.8; // 80%的间隔相似
            }
            
            return false;
        }
        
        public String getClientIp() { return clientIp; }
        public LocalDateTime getLastActivityTime() { return lastActivityTime; }
        
        private static class LoginAttempt {
            final String username;
            final boolean success;
            final String userAgent;
            final LocalDateTime timestamp;
            
            LoginAttempt(String username, boolean success, String userAgent, LocalDateTime timestamp) {
                this.username = username;
                this.success = success;
                this.userAgent = userAgent;
                this.timestamp = timestamp;
            }
        }
        
        private static class ApiRequest {
            final String endpoint;
            final int statusCode;
            final String userAgent;
            final LocalDateTime timestamp;
            
            ApiRequest(String endpoint, int statusCode, String userAgent, LocalDateTime timestamp) {
                this.endpoint = endpoint;
                this.statusCode = statusCode;
                this.userAgent = userAgent;
                this.timestamp = timestamp;
            }
        }
    }
    
    /**
     * 安全统计信息类
     */
    public static class SecurityStats {
        private final int totalClients;
        private final int suspiciousClients;
        private final int totalLoginAttempts;
        private final int failedLoginAttempts;
        private final int totalApiRequests;
        
        public SecurityStats(int totalClients, int suspiciousClients, int totalLoginAttempts, 
                           int failedLoginAttempts, int totalApiRequests) {
            this.totalClients = totalClients;
            this.suspiciousClients = suspiciousClients;
            this.totalLoginAttempts = totalLoginAttempts;
            this.failedLoginAttempts = failedLoginAttempts;
            this.totalApiRequests = totalApiRequests;
        }
        
        // Getters
        public int getTotalClients() { return totalClients; }
        public int getSuspiciousClients() { return suspiciousClients; }
        public int getTotalLoginAttempts() { return totalLoginAttempts; }
        public int getFailedLoginAttempts() { return failedLoginAttempts; }
        public int getTotalApiRequests() { return totalApiRequests; }
        
        public double getSuspiciousPercentage() {
            return totalClients > 0 ? (double) suspiciousClients / totalClients * 100 : 0;
        }
        
        public double getFailedLoginPercentage() {
            return totalLoginAttempts > 0 ? (double) failedLoginAttempts / totalLoginAttempts * 100 : 0;
        }
        
        @Override
        public String toString() {
            return String.format("SecurityStats{clients=%d, suspicious=%d(%.1f%%), logins=%d, failed=%d(%.1f%%), api=%d}",
                    totalClients, suspiciousClients, getSuspiciousPercentage(),
                    totalLoginAttempts, failedLoginAttempts, getFailedLoginPercentage(), totalApiRequests);
        }
    }
    
    /**
     * 安全事件监听器接口
     */
    public interface SecurityEventListener {
        void onSecurityEvent(SecurityEvent event);
    }
}