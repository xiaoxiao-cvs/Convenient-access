package com.xaoxiao.convenientaccess.sync;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import com.xaoxiao.convenientaccess.database.DatabaseManager;

/**
 * 同步任务管理器（简化版）
 * 管理后台任务执行和调度
 * 注意: 已移除WhitelistPlus JSON同步功能,现在是纯数据库模式
 */
public class SyncTaskManager {
    private static final Logger logger = LoggerFactory.getLogger(SyncTaskManager.class);
    
    private final ConvenientAccessPlugin plugin;
    private final DatabaseManager databaseManager;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService taskExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    public SyncTaskManager(ConvenientAccessPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "SyncTaskManager-Scheduler");
            thread.setDaemon(true);
            return thread;
        });
        
        this.taskExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "SyncTaskManager-Executor");
            thread.setDaemon(true);
            return thread;
        });
    }
    
    /**
     * 初始化同步任务管理器
     */
    public CompletableFuture<Boolean> initialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("同步任务管理器初始化中 (纯数据库模式)...");
                
                running.set(true);
                logger.info("同步任务管理器初始化完成");
                return true;
            } catch (Exception e) {
                logger.error("同步任务管理器初始化失败", e);
                return false;
            }
        }, taskExecutor);
    }
    
    /**
     * 调度添加玩家任务
     * 注意: JSON同步已移除,此方法保留仅用于向后兼容
     */
    public CompletableFuture<Long> scheduleAddPlayer(String uuid, String name) {
        return CompletableFuture.completedFuture(0L);
    }
    
    /**
     * 调度移除玩家任务
     * 注意: JSON同步已移除,此方法保留仅用于向后兼容
     */
    public CompletableFuture<Long> scheduleRemovePlayer(String uuid, String name) {
        return CompletableFuture.completedFuture(0L);
    }
    
    /**
     * 调度UUID更新任务
     * 注意: JSON同步已移除,此方法保留仅用于向后兼容
     */
    public CompletableFuture<Long> scheduleUuidUpdate(String name, String uuid) {
        return CompletableFuture.completedFuture(0L);
    }
    
    /**
     * 执行异步任务
     */
    public CompletableFuture<Void> executeAsync(Runnable task) {
        return CompletableFuture.runAsync(task, taskExecutor);
    }
    
    /**
     * 获取同步状态
     */
    public CompletableFuture<JsonObject> getSyncStatus() {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject status = new JsonObject();
            status.addProperty("running", running.get());
            status.addProperty("mode", "database-only");
            status.addProperty("json_sync", "disabled");
            return status;
        }, taskExecutor);
    }
    
    /**
     * 关闭同步任务管理器
     */
    public void shutdown() {
        logger.info("正在关闭同步任务管理器...");
        running.set(false);
        
        try {
            scheduler.shutdown();
            taskExecutor.shutdown();
            logger.info("同步任务管理器已关闭");
        } catch (Exception e) {
            logger.error("关闭同步任务管理器时发生错误", e);
        }
    }
    
    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }
}
