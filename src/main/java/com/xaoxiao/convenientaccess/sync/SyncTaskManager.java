package com.xaoxiao.convenientaccess.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import com.xaoxiao.convenientaccess.database.DatabaseManager;
import com.xaoxiao.convenientaccess.whitelist.WhitelistEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同步任务管理器（增强版）
 * 管理数据库与JSON文件的同步、任务队列调度和执行、错误处理和重试机制
 * 支持优先级队列、批量处理、冲突解决和智能重试
 */
public class SyncTaskManager {
    private static final Logger logger = LoggerFactory.getLogger(SyncTaskManager.class);
    
    private final ConvenientAccessPlugin plugin;
    private final DatabaseManager databaseManager;
    private final Gson gson;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService taskExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // 增强功能组件
    private final TaskQueue taskQueue;
    private final SyncConflictResolver conflictResolver;
    
    private String whitelistJsonPath;
    private long lastSyncTime = 0;
    
    public SyncTaskManager(ConvenientAccessPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .setDateFormat("yyyy-MM-dd HH:mm:ss")
                .create();
        
        this.scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread thread = new Thread(r, "SyncTaskManager-Scheduler");
            thread.setDaemon(true);
            return thread;
        });
        
        this.taskExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "SyncTaskManager-Executor");
            thread.setDaemon(true);
            return thread;
        });
        
        // 初始化增强组件
        this.taskQueue = new TaskQueue();
        this.conflictResolver = new SyncConflictResolver();
        
        // 默认白名单文件路径
        this.whitelistJsonPath = "whitelist.json";
    }
    
    /**
     * 初始化同步任务管理器
     */
    public CompletableFuture<Boolean> initialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 设置白名单文件路径
                File serverRoot = plugin.getServer().getWorldContainer();
                File whitelistFile = new File(serverRoot, whitelistJsonPath);
                this.whitelistJsonPath = whitelistFile.getAbsolutePath();
                
                // 启动时执行全量同步
                scheduleFullSync();
                
                // 启动定期任务处理
                startTaskProcessor();
                
                running.set(true);
                logger.info("同步任务管理器初始化完成，白名单文件路径: {}", whitelistJsonPath);
                return true;
            } catch (Exception e) {
                logger.error("同步任务管理器初始化失败", e);
                return false;
            }
        }, taskExecutor);
    }
    
    /**
     * 创建同步任务（增强版）
     */
    public CompletableFuture<Long> createTask(SyncTask.TaskType taskType, String data, int priority) {
        // 优先使用任务队列
        SyncTask task = new SyncTask(taskType, data, priority);
        boolean added = taskQueue.addTask(task);
        
        if (added) {
            logger.debug("任务已添加到队列: {} (类型: {}, 优先级: {})", task.getId(), taskType, priority);
            // 确保返回有效的任务ID
            Long taskId = task.getId();
            if (taskId == null || taskId <= 0) {
                logger.warn("队列任务ID无效: {}, 使用时间戳作为ID", taskId);
                taskId = System.currentTimeMillis();
                task.setId(taskId);
            }
            return CompletableFuture.completedFuture(taskId);
        }
        
        // 队列添加失败，回退到数据库方式
        return databaseManager.executeTransactionAsync(connection -> {
            String sql = """
                INSERT INTO sync_tasks (task_type, status, priority, data, retry_count, max_retries, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
            
            try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, task.getTaskType().getValue());
                stmt.setString(2, task.getStatus().getValue());
                stmt.setInt(3, task.getPriority());
                stmt.setString(4, task.getData());
                stmt.setInt(5, task.getRetryCount());
                stmt.setInt(6, task.getMaxRetries());
                stmt.setTimestamp(7, Timestamp.valueOf(task.getCreatedAt()));
                stmt.setTimestamp(8, Timestamp.valueOf(task.getUpdatedAt()));
                
                int affected = stmt.executeUpdate();
                if (affected > 0) {
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            long taskId = rs.getLong(1);
                            logger.debug("创建同步任务: {} (ID: {})", taskType, taskId);
                            return taskId;
                        }
                    }
                }
                return -1L;
            }
        }).exceptionally(throwable -> {
            logger.error("创建同步任务失败: {}", taskType, throwable);
            return -1L;
        });
    }
    
    /**
     * 处理增强任务（支持重试和批量处理）
     */
    private void processEnhancedTasks() {
        if (!running.get()) {
            return;
        }
        
        try {
            List<SyncTask> pendingTasks = getPendingTasks(5);
            for (SyncTask task : pendingTasks) {
                taskExecutor.submit(() -> executeTaskWithEnhancedRetry(task));
            }
        } catch (Exception e) {
            logger.error("处理增强任务时发生错误", e);
        }
    }
    
    /**
     * 执行任务（增强重试机制）
     */
    private void executeTaskWithEnhancedRetry(SyncTask task) {
        try {
            // 更新任务状态为处理中
            updateTaskStatus(task.getId(), SyncTask.TaskStatus.PROCESSING, null);
            task.markStarted();
            
            boolean success = false;
            String errorMessage = null;
            
            switch (task.getTaskType()) {
                case FULL_SYNC:
                    success = executeFullSyncWithConflictResolution();
                    break;
                case ADD_PLAYER:
                    success = executeAddPlayer(task.getData());
                    break;
                case REMOVE_PLAYER:
                    success = executeRemovePlayer(task.getData());
                    break;
                case BATCH_UPDATE:
                    success = executeBatchUpdate(task.getData());
                    break;
                default:
                    errorMessage = "未知的任务类型: " + task.getTaskType();
            }
            
            if (success) {
                // 任务成功完成
                updateTaskStatus(task.getId(), SyncTask.TaskStatus.COMPLETED, null);
                logger.debug("任务执行成功: {} (ID: {})", task.getTaskType(), task.getId());
            } else {
                // 任务失败，检查是否可以重试
                handleTaskFailureWithRetry(task, errorMessage);
            }
            
        } catch (Exception e) {
            logger.error("执行任务时发生异常: {} (ID: {})", task.getTaskType(), task.getId(), e);
            handleTaskFailureWithRetry(task, e.getMessage());
        }
    }
    
    /**
     * 处理任务失败（增强重试）
     */
    private void handleTaskFailureWithRetry(SyncTask task, String errorMessage) {
        task.incrementRetryCount();
        
        if (task.canRetry()) {
            // 计算重试延迟
            long retryDelay = RetryStrategy.calculateRetryDelay(task.getRetryCount(), task.getTaskType());
            
            // 重新调度任务
            updateTaskStatus(task.getId(), SyncTask.TaskStatus.PENDING, 
                    "重试 " + task.getRetryCount() + "/" + task.getMaxRetries() + " - " + errorMessage);
            
            logger.warn("任务执行失败，将在{}ms后重试: {} (ID: {}, 重试次数: {})", 
                    retryDelay, task.getTaskType(), task.getId(), task.getRetryCount());
        } else {
            // 超过最大重试次数，标记为失败
            updateTaskStatus(task.getId(), SyncTask.TaskStatus.FAILED, errorMessage);
            logger.error("任务执行失败，已达到最大重试次数: {} (ID: {})", 
                    task.getTaskType(), task.getId());
        }
    }
    
    /**
     * 执行全量同步（带冲突解决）
     */
    private boolean executeFullSyncWithConflictResolution() {
        try {
            // 从数据库读取所有活跃的白名单条目
            List<WhitelistEntry> databaseEntries = databaseManager.executeAsync(connection -> {
                String sql = "SELECT * FROM whitelist WHERE is_active = 1 ORDER BY name";
                List<WhitelistEntry> result = new ArrayList<>();
                
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    
                    while (rs.next()) {
                        WhitelistEntry entry = new WhitelistEntry();
                        entry.setId(rs.getLong("id"));
                        entry.setName(rs.getString("name"));
                        entry.setUuid(rs.getString("uuid"));
                        entry.setAddedByName(rs.getString("added_by_name"));
                        entry.setAddedByUuid(rs.getString("added_by_uuid"));
                        entry.setAddedAt(rs.getTimestamp("added_at").toLocalDateTime());
                        entry.setSource(rs.getString("source"));
                        entry.setActive(rs.getBoolean("is_active"));
                        entry.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                        entry.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
                        result.add(entry);
                    }
                }
                return result;
            }).get(10, TimeUnit.SECONDS);
            
            // 从JSON文件读取白名单
            List<WhitelistEntry> jsonEntries = readWhitelistFromJson();
            
            // 使用冲突解决器
            SyncConflictResolver.ConflictResolution resolution = 
                    conflictResolver.resolveConflicts(databaseEntries, jsonEntries);
            
            if (resolution.hasConflicts()) {
                logger.info("检测到同步冲突，开始解决: {} 个变更", resolution.getTotalChanges());
                
                // 简化的冲突解决：直接使用数据库数据覆盖JSON
                writeWhitelistToJson(databaseEntries);
                
                logger.info("冲突解决完成，使用数据库数据");
            } else {
                // 没有冲突，正常同步
                writeWhitelistToJson(databaseEntries);
            }
            
            return true;
            
        } catch (Exception e) {
            logger.error("执行全量同步失败", e);
            return false;
        }
    }
    
    /**
     * 检测和解决冲突
     */
    private void detectAndResolveConflicts() {
        if (!running.get()) {
            return;
        }
        
        try {
            logger.debug("开始定期冲突检测");
            
            // 创建一个全量同步任务来检测冲突
            SyncTask conflictCheckTask = new SyncTask(SyncTask.TaskType.FULL_SYNC, "{\"type\":\"conflict_check\"}", 1);
            taskQueue.addTask(conflictCheckTask);
            
        } catch (Exception e) {
            logger.error("冲突检测失败", e);
        }
    }
    
    /**
     * 调度全量同步任务
     */
    public CompletableFuture<Long> scheduleFullSync() {
        return createTask(SyncTask.TaskType.FULL_SYNC, "{}", 1);
    }
    
    /**
     * 调度添加玩家任务
     */
    public CompletableFuture<Long> scheduleAddPlayer(String uuid, String name) {
        JsonObject data = new JsonObject();
        data.addProperty("uuid", uuid);
        data.addProperty("name", name);
        return createTask(SyncTask.TaskType.ADD_PLAYER, data.toString(), 3);
    }
    
    /**
     * 调度移除玩家任务
     */
    public CompletableFuture<Long> scheduleRemovePlayer(String uuid, String name) {
        JsonObject data = new JsonObject();
        data.addProperty("uuid", uuid);
        data.addProperty("name", name);
        return createTask(SyncTask.TaskType.REMOVE_PLAYER, data.toString(), 3);
    }
    
    /**
     * 启动任务处理器
     */
    private void startTaskProcessor() {
        // 每5秒检查一次待处理任务
        scheduler.scheduleWithFixedDelay(this::processPendingTasks, 0, 5, TimeUnit.SECONDS);
        
        // 每30秒清理已完成的任务
        scheduler.scheduleWithFixedDelay(this::cleanupCompletedTasks, 30, 30, TimeUnit.SECONDS);
    }
    
    /**
     * 处理待处理任务
     */
    private void processPendingTasks() {
        if (!running.get()) {
            return;
        }
        
        try {
            List<SyncTask> pendingTasks = getPendingTasks(10);
            for (SyncTask task : pendingTasks) {
                taskExecutor.submit(() -> executeTask(task));
            }
        } catch (Exception e) {
            logger.error("处理待处理任务时发生错误", e);
        }
    }
    
    /**
     * 获取待处理任务
     */
    private List<SyncTask> getPendingTasks(int limit) {
        try {
            return databaseManager.executeAsync(connection -> {
                String sql = """
                    SELECT * FROM sync_tasks 
                    WHERE status = 'PENDING' 
                    ORDER BY priority DESC, created_at ASC 
                    LIMIT ?
                """;
                
                List<SyncTask> tasks = new ArrayList<>();
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setInt(1, limit);
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            tasks.add(mapResultSetToTask(rs));
                        }
                    }
                }
                return tasks;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("获取待处理任务失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 执行同步任务
     */
    private void executeTask(SyncTask task) {
        try {
            // 更新任务状态为处理中
            updateTaskStatus(task.getId(), SyncTask.TaskStatus.PROCESSING, null);
            task.markStarted();
            
            boolean success = false;
            String errorMessage = null;
            
            switch (task.getTaskType()) {
                case FULL_SYNC:
                    success = executeFullSync();
                    break;
                case ADD_PLAYER:
                    success = executeAddPlayer(task.getData());
                    break;
                case REMOVE_PLAYER:
                    success = executeRemovePlayer(task.getData());
                    break;
                case BATCH_UPDATE:
                    success = executeBatchUpdate(task.getData());
                    break;
                default:
                    errorMessage = "未知的任务类型: " + task.getTaskType();
            }
            
            if (success) {
                // 任务成功完成
                updateTaskStatus(task.getId(), SyncTask.TaskStatus.COMPLETED, null);
                logger.debug("同步任务执行成功: {} (ID: {})", task.getTaskType(), task.getId());
            } else {
                // 任务失败，检查是否可以重试
                task.incrementRetryCount();
                if (task.canRetry()) {
                    // 重新调度任务
                    updateTaskStatus(task.getId(), SyncTask.TaskStatus.PENDING, errorMessage);
                    logger.warn("同步任务执行失败，将重试: {} (ID: {}, 重试次数: {})", 
                            task.getTaskType(), task.getId(), task.getRetryCount());
                } else {
                    // 超过最大重试次数，标记为失败
                    updateTaskStatus(task.getId(), SyncTask.TaskStatus.FAILED, errorMessage);
                    logger.error("同步任务执行失败，已达到最大重试次数: {} (ID: {})", 
                            task.getTaskType(), task.getId());
                }
            }
        } catch (Exception e) {
            logger.error("执行同步任务时发生异常: {} (ID: {})", task.getTaskType(), task.getId(), e);
            updateTaskStatus(task.getId(), SyncTask.TaskStatus.FAILED, e.getMessage());
        }
    }
    
    /**
     * 执行全量同步
     */
    private boolean executeFullSync() {
        try {
            // 从数据库读取所有活跃的白名单条目
            List<WhitelistEntry> entries = databaseManager.executeAsync(connection -> {
                String sql = "SELECT * FROM whitelist WHERE is_active = 1 ORDER BY name";
                List<WhitelistEntry> result = new ArrayList<>();
                
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    
                    while (rs.next()) {
                        WhitelistEntry entry = new WhitelistEntry();
                        entry.setId(rs.getLong("id"));
                        entry.setName(rs.getString("name"));
                        entry.setUuid(rs.getString("uuid"));
                        entry.setAddedByName(rs.getString("added_by_name"));
                        entry.setAddedByUuid(rs.getString("added_by_uuid"));
                        entry.setAddedAt(rs.getTimestamp("added_at").toLocalDateTime());
                        entry.setSource(rs.getString("source"));
                        entry.setActive(rs.getBoolean("is_active"));
                        entry.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                        entry.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
                        result.add(entry);
                    }
                }
                return result;
            }).get(10, TimeUnit.SECONDS);
            
            // 写入JSON文件
            return writeWhitelistToJson(entries);
        } catch (Exception e) {
            logger.error("执行全量同步失败", e);
            return false;
        }
    }
    
    /**
     * 执行添加玩家同步
     */
    private boolean executeAddPlayer(String data) {
        try {
            JsonObject json = gson.fromJson(data, JsonObject.class);
            String uuid = json.get("uuid").getAsString();
            String name = json.get("name").getAsString();
            
            // 读取现有白名单
            List<WhitelistEntry> entries = readWhitelistFromJson();
            
            // 检查是否已存在
            boolean exists = entries.stream().anyMatch(entry -> entry.getUuid().equals(uuid));
            if (!exists) {
                // 从数据库获取完整信息
                WhitelistEntry entry = databaseManager.executeAsync(connection -> {
                    String sql = "SELECT * FROM whitelist WHERE uuid = ? AND is_active = 1";
                    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                        stmt.setString(1, uuid);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                WhitelistEntry result = new WhitelistEntry();
                                result.setName(rs.getString("name"));
                                result.setUuid(rs.getString("uuid"));
                                return result;
                            }
                        }
                    }
                    return null;
                }).get(5, TimeUnit.SECONDS);
                
                if (entry != null) {
                    entries.add(entry);
                    return writeWhitelistToJson(entries);
                }
            }
            return true;
        } catch (Exception e) {
            logger.error("执行添加玩家同步失败", e);
            return false;
        }
    }
    
    /**
     * 执行移除玩家同步
     */
    private boolean executeRemovePlayer(String data) {
        try {
            JsonObject json = gson.fromJson(data, JsonObject.class);
            String uuid = json.get("uuid").getAsString();
            
            // 读取现有白名单
            List<WhitelistEntry> entries = readWhitelistFromJson();
            
            // 移除指定玩家
            entries.removeIf(entry -> entry.getUuid().equals(uuid));
            
            return writeWhitelistToJson(entries);
        } catch (Exception e) {
            logger.error("执行移除玩家同步失败", e);
            return false;
        }
    }
    
    /**
     * 执行批量更新同步
     */
    private boolean executeBatchUpdate(String data) {
        // 批量更新直接执行全量同步
        return executeFullSync();
    }
    
    /**
     * 从JSON文件读取白名单
     */
    private List<WhitelistEntry> readWhitelistFromJson() {
        List<WhitelistEntry> entries = new ArrayList<>();
        File file = new File(whitelistJsonPath);
        
        if (!file.exists()) {
            return entries;
        }
        
        try (FileReader reader = new FileReader(file)) {
            JsonArray jsonArray = gson.fromJson(reader, JsonArray.class);
            if (jsonArray != null) {
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject obj = jsonArray.get(i).getAsJsonObject();
                    WhitelistEntry entry = new WhitelistEntry();
                    entry.setUuid(obj.get("uuid").getAsString());
                    entry.setName(obj.get("name").getAsString());
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            logger.error("读取白名单JSON文件失败: {}", whitelistJsonPath, e);
        }
        
        return entries;
    }
    
    /**
     * 将白名单写入JSON文件
     */
    private boolean writeWhitelistToJson(List<WhitelistEntry> entries) {
        try {
            JsonArray jsonArray = new JsonArray();
            for (WhitelistEntry entry : entries) {
                JsonObject obj = new JsonObject();
                obj.addProperty("uuid", entry.getUuid());
                obj.addProperty("name", entry.getName());
                jsonArray.add(obj);
            }
            
            // 使用临时文件确保原子性写入
            File tempFile = new File(whitelistJsonPath + ".tmp");
            try (FileWriter writer = new FileWriter(tempFile)) {
                gson.toJson(jsonArray, writer);
            }
            
            // 原子性替换
            Path tempPath = tempFile.toPath();
            Path targetPath = new File(whitelistJsonPath).toPath();
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            
            lastSyncTime = System.currentTimeMillis();
            logger.debug("白名单JSON文件更新完成: {} 个条目", entries.size());
            return true;
        } catch (IOException e) {
            logger.error("写入白名单JSON文件失败: {}", whitelistJsonPath, e);
            return false;
        }
    }
    
    /**
     * 更新任务状态
     */
    private void updateTaskStatus(Long taskId, SyncTask.TaskStatus status, String errorMessage) {
        try {
            databaseManager.executeTransactionAsync(connection -> {
                String sql = "UPDATE sync_tasks SET status = ?, updated_at = ?, error_message = ? WHERE id = ?";
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setString(1, status.getValue());
                    stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                    stmt.setString(3, errorMessage);
                    stmt.setLong(4, taskId);
                    return stmt.executeUpdate() > 0;
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("更新任务状态失败: {} -> {}", taskId, status, e);
        }
    }
    
    /**
     * 清理已完成的任务
     */
    private void cleanupCompletedTasks() {
        try {
            databaseManager.executeTransactionAsync(connection -> {
                // 删除7天前完成的任务
                String sql = """
                    DELETE FROM sync_tasks 
                    WHERE status IN ('COMPLETED', 'FAILED') 
                    AND updated_at < datetime('now', '-7 days')
                """;
                
                try (Statement stmt = connection.createStatement()) {
                    int deleted = stmt.executeUpdate(sql);
                    if (deleted > 0) {
                        logger.debug("清理已完成的同步任务: {} 个", deleted);
                    }
                    return deleted;
                }
            });
        } catch (Exception e) {
            logger.error("清理已完成任务失败", e);
        }
    }
    
    /**
     * 将ResultSet映射为SyncTask
     */
    private SyncTask mapResultSetToTask(ResultSet rs) throws SQLException {
        SyncTask task = new SyncTask();
        task.setId(rs.getLong("id"));
        task.setTaskType(SyncTask.TaskType.fromString(rs.getString("task_type")));
        task.setStatus(SyncTask.TaskStatus.fromString(rs.getString("status")));
        task.setPriority(rs.getInt("priority"));
        task.setData(rs.getString("data"));
        task.setRetryCount(rs.getInt("retry_count"));
        task.setMaxRetries(rs.getInt("max_retries"));
        task.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        task.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        
        Timestamp scheduledAt = rs.getTimestamp("scheduled_at");
        if (scheduledAt != null) {
            task.setScheduledAt(scheduledAt.toLocalDateTime());
        }
        
        Timestamp startedAt = rs.getTimestamp("started_at");
        if (startedAt != null) {
            task.setStartedAt(startedAt.toLocalDateTime());
        }
        
        Timestamp completedAt = rs.getTimestamp("completed_at");
        if (completedAt != null) {
            task.setCompletedAt(completedAt.toLocalDateTime());
        }
        
        task.setErrorMessage(rs.getString("error_message"));
        return task;
    }
    
    /**
     * 获取同步状态
     */
    public CompletableFuture<JsonObject> getSyncStatus() {
        return databaseManager.executeAsync(connection -> {
            JsonObject status = new JsonObject();
            
            // 统计各状态任务数量
            String sql = "SELECT status, COUNT(*) as count FROM sync_tasks GROUP BY status";
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                JsonObject taskCounts = new JsonObject();
                while (rs.next()) {
                    taskCounts.addProperty(rs.getString("status"), rs.getInt("count"));
                }
                status.add("task_counts", taskCounts);
            }
            
            // 最后同步时间
            status.addProperty("last_sync_time", lastSyncTime);
            status.addProperty("is_running", running.get());
            
            return status;
        }).exceptionally(throwable -> {
            logger.error("获取同步状态失败", throwable);
            JsonObject errorStatus = new JsonObject();
            errorStatus.addProperty("error", "获取同步状态失败");
            return errorStatus;
        });
    }
    
    /**
     * 关闭同步任务管理器
     */
    public void shutdown() {
        running.set(false);
        scheduler.shutdown();
        taskExecutor.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!taskExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                taskExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            taskExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("同步任务管理器已关闭");
    }
}