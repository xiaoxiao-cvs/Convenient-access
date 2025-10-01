package com.xaoxiao.convenientaccess.sync;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 同步任务实体类
 */
public class SyncTask {
    private Long id;
    private TaskType taskType;
    private TaskStatus status;
    private int priority;
    private String data; // JSON格式的任务数据
    private int retryCount;
    private int maxRetries;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    
    public SyncTask() {
        this.status = TaskStatus.PENDING;
        this.priority = 5;
        this.retryCount = 0;
        this.maxRetries = 3;
        this.createdAt = LocalDateTime.now();
    }
    
    public SyncTask(TaskType taskType, String data) {
        this();
        this.taskType = taskType;
        this.data = data;
    }
    
    public SyncTask(TaskType taskType, String data, int priority) {
        this(taskType, data);
        this.priority = priority;
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public TaskType getTaskType() {
        return taskType;
    }
    
    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
    }
    
    public TaskStatus getStatus() {
        return status;
    }
    
    public void setStatus(TaskStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }
    
    public int getPriority() {
        return priority;
    }
    
    public void setPriority(int priority) {
        this.priority = priority;
    }
    
    public String getData() {
        return data;
    }
    
    public void setData(String data) {
        this.data = data;
    }
    
    public int getRetryCount() {
        return retryCount;
    }
    
    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public LocalDateTime getScheduledAt() {
        return scheduledAt;
    }
    
    public void setScheduledAt(LocalDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }
    
    public LocalDateTime getStartedAt() {
        return startedAt;
    }
    
    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }
    
    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
    
    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    /**
     * 增加重试次数
     */
    public void incrementRetryCount() {
        this.retryCount++;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 检查是否可以重试
     */
    public boolean canRetry() {
        return retryCount < maxRetries;
    }
    
    /**
     * 标记任务开始
     */
    public void markStarted() {
        this.status = TaskStatus.PROCESSING;
        this.startedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 标记任务完成
     */
    public void markCompleted() {
        this.status = TaskStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 标记任务失败
     */
    public void markFailed(String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.errorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncTask syncTask = (SyncTask) o;
        return Objects.equals(id, syncTask.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "SyncTask{" +
                "id=" + id +
                ", taskType=" + taskType +
                ", status=" + status +
                ", priority=" + priority +
                ", retryCount=" + retryCount +
                ", createdAt=" + createdAt +
                '}';
    }
    
    /**
     * 任务类型枚举
     */
    public enum TaskType {
        FULL_SYNC("FULL_SYNC"),
        ADD_PLAYER("ADD_PLAYER"),
        REMOVE_PLAYER("REMOVE_PLAYER"),
        UPDATE_UUID("UPDATE_UUID"),
        BATCH_UPDATE("BATCH_UPDATE");
        
        private final String value;
        
        TaskType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static TaskType fromString(String value) {
            for (TaskType type : TaskType.values()) {
                if (type.value.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown task type: " + value);
        }
    }
    
    /**
     * 任务状态枚举
     */
    public enum TaskStatus {
        PENDING("PENDING"),
        PROCESSING("PROCESSING"),
        COMPLETED("COMPLETED"),
        FAILED("FAILED"),
        CANCELLED("CANCELLED");
        
        private final String value;
        
        TaskStatus(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static TaskStatus fromString(String value) {
            for (TaskStatus status : TaskStatus.values()) {
                if (status.value.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown task status: " + value);
        }
    }
}