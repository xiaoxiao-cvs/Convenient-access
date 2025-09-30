package com.xaoxiao.convenientaccess.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试策略管理器
 * 实现指数退避和智能重试机制
 */
public class RetryStrategy {
    private static final Logger logger = LoggerFactory.getLogger(RetryStrategy.class);
    
    // 重试配置
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 1000; // 基础延迟1秒
    private static final long MAX_DELAY_MS = 300000; // 最大延迟5分钟
    private static final double JITTER_FACTOR = 0.1; // 抖动因子
    
    /**
     * 计算下次重试时间
     */
    public static LocalDateTime calculateNextRetryTime(SyncTask task) {
        if (task == null || !task.canRetry()) {
            return null;
        }
        
        long delayMs = calculateRetryDelay(task.getRetryCount(), task.getTaskType());
        return LocalDateTime.now().plus(delayMs, ChronoUnit.MILLIS);
    }
    
    /**
     * 计算重试延迟时间（毫秒）
     */
    public static long calculateRetryDelay(int retryCount, SyncTask.TaskType taskType) {
        // 基础延迟使用指数退避
        long baseDelay = Math.min(BASE_DELAY_MS * (1L << retryCount), MAX_DELAY_MS);
        
        // 根据任务类型调整延迟
        double taskMultiplier = getTaskTypeMultiplier(taskType);
        long adjustedDelay = (long) (baseDelay * taskMultiplier);
        
        // 添加随机抖动避免雷群效应
        long jitter = (long) (adjustedDelay * JITTER_FACTOR * ThreadLocalRandom.current().nextDouble());
        
        return adjustedDelay + jitter;
    }
    
    /**
     * 获取任务类型的延迟倍数
     */
    private static double getTaskTypeMultiplier(SyncTask.TaskType taskType) {
        switch (taskType) {
            case FULL_SYNC:
                return 2.0; // 全量同步延迟更长
            case BATCH_UPDATE:
                return 1.5; // 批量更新适中延迟
            case ADD_PLAYER:
            case REMOVE_PLAYER:
                return 1.0; // 单个操作标准延迟
            default:
                return 1.0;
        }
    }
    
    /**
     * 检查任务是否应该重试
     */
    public static boolean shouldRetry(SyncTask task, Exception error) {
        if (task == null || !task.canRetry()) {
            return false;
        }
        
        // 检查错误类型是否可重试
        if (!isRetryableError(error)) {
            logger.debug("错误不可重试: {}", error.getClass().getSimpleName());
            return false;
        }
        
        // 检查重试次数
        if (task.getRetryCount() >= getMaxRetries(task.getTaskType())) {
            logger.debug("已达到最大重试次数: {}", task.getRetryCount());
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取任务类型的最大重试次数
     */
    public static int getMaxRetries(SyncTask.TaskType taskType) {
        switch (taskType) {
            case FULL_SYNC:
                return 5; // 全量同步允许更多重试
            case BATCH_UPDATE:
                return 4; // 批量更新适中重试
            case ADD_PLAYER:
            case REMOVE_PLAYER:
                return DEFAULT_MAX_RETRIES; // 单个操作标准重试
            default:
                return DEFAULT_MAX_RETRIES;
        }
    }
    
    /**
     * 检查错误是否可重试
     */
    private static boolean isRetryableError(Exception error) {
        if (error == null) {
            return false;
        }
        
        String errorMessage = error.getMessage();
        String errorClass = error.getClass().getSimpleName();
        
        // 网络相关错误通常可重试
        if (errorClass.contains("IOException") || 
            errorClass.contains("TimeoutException") ||
            errorClass.contains("ConnectException")) {
            return true;
        }
        
        // 数据库锁定错误可重试
        if (errorMessage != null && (
            errorMessage.contains("database is locked") ||
            errorMessage.contains("SQLITE_BUSY") ||
            errorMessage.contains("connection timeout"))) {
            return true;
        }
        
        // 临时文件系统错误可重试
        if (errorMessage != null && (
            errorMessage.contains("No space left on device") ||
            errorMessage.contains("Permission denied") ||
            errorMessage.contains("Resource temporarily unavailable"))) {
            return true;
        }
        
        // 数据验证错误不可重试
        if (errorMessage != null && (
            errorMessage.contains("Invalid UUID") ||
            errorMessage.contains("Invalid player name") ||
            errorMessage.contains("Validation failed"))) {
            return false;
        }
        
        // 默认可重试
        return true;
    }
    
    /**
     * 创建重试任务
     */
    public static SyncTask createRetryTask(SyncTask originalTask, Exception error) {
        if (!shouldRetry(originalTask, error)) {
            return null;
        }
        
        // 创建重试任务
        SyncTask retryTask = new SyncTask(originalTask.getTaskType(), originalTask.getData());
        retryTask.setId(originalTask.getId());
        retryTask.setPriority(Math.max(1, originalTask.getPriority() - 1)); // 提高优先级
        retryTask.setRetryCount(originalTask.getRetryCount() + 1);
        retryTask.setMaxRetries(getMaxRetries(originalTask.getTaskType()));
        
        // 设置调度时间
        LocalDateTime nextRetryTime = calculateNextRetryTime(originalTask);
        retryTask.setScheduledAt(nextRetryTime);
        
        // 记录错误信息
        String errorInfo = String.format("重试 %d/%d - 原因: %s", 
                retryTask.getRetryCount(), 
                retryTask.getMaxRetries(),
                error.getMessage());
        retryTask.setErrorMessage(errorInfo);
        
        logger.info("创建重试任务: {} (第{}次重试，延迟{}ms)", 
                retryTask.getId(), 
                retryTask.getRetryCount(),
                calculateRetryDelay(originalTask.getRetryCount(), originalTask.getTaskType()));
        
        return retryTask;
    }
    
    /**
     * 检查任务是否到达重试时间
     */
    public static boolean isRetryTimeReached(SyncTask task) {
        if (task.getScheduledAt() == null) {
            return true; // 没有调度时间，立即执行
        }
        
        return LocalDateTime.now().isAfter(task.getScheduledAt());
    }
    
    /**
     * 获取重试统计信息
     */
    public static RetryStats getRetryStats(SyncTask task) {
        return new RetryStats(
                task.getRetryCount(),
                getMaxRetries(task.getTaskType()),
                task.getScheduledAt(),
                task.canRetry()
        );
    }
    
    /**
     * 重试统计信息类
     */
    public static class RetryStats {
        private final int currentRetries;
        private final int maxRetries;
        private final LocalDateTime nextRetryTime;
        private final boolean canRetry;
        
        public RetryStats(int currentRetries, int maxRetries, LocalDateTime nextRetryTime, boolean canRetry) {
            this.currentRetries = currentRetries;
            this.maxRetries = maxRetries;
            this.nextRetryTime = nextRetryTime;
            this.canRetry = canRetry;
        }
        
        public int getCurrentRetries() {
            return currentRetries;
        }
        
        public int getMaxRetries() {
            return maxRetries;
        }
        
        public LocalDateTime getNextRetryTime() {
            return nextRetryTime;
        }
        
        public boolean canRetry() {
            return canRetry;
        }
        
        public int getRemainingRetries() {
            return Math.max(0, maxRetries - currentRetries);
        }
        
        public double getRetryProgress() {
            if (maxRetries == 0) return 1.0;
            return (double) currentRetries / maxRetries;
        }
        
        @Override
        public String toString() {
            return "RetryStats{" +
                    "retries=" + currentRetries + "/" + maxRetries +
                    ", canRetry=" + canRetry +
                    ", nextRetry=" + nextRetryTime +
                    '}';
        }
    }
}