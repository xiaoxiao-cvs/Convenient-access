package com.xaoxiao.convenientaccess.whitelist;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量操作类
 * 支持批量添加和删除白名单条目
 */
public class BatchOperation {
    private final OperationType operationType;
    private final List<WhitelistEntry> entries;
    private final String operatorName;
    private final String operatorUuid;
    
    public BatchOperation(OperationType operationType, String operatorName, String operatorUuid) {
        this.operationType = operationType;
        this.operatorName = operatorName;
        this.operatorUuid = operatorUuid;
        this.entries = new ArrayList<>();
    }
    
    /**
     * 添加条目到批量操作
     */
    public BatchOperation addEntry(String name, String uuid) {
        WhitelistEntry entry = new WhitelistEntry(name, uuid, operatorName, operatorUuid, WhitelistEntry.Source.ADMIN.getValue());
        entries.add(entry);
        return this;
    }
    
    /**
     * 添加条目到批量操作（指定来源）
     */
    public BatchOperation addEntry(String name, String uuid, WhitelistEntry.Source source) {
        WhitelistEntry entry = new WhitelistEntry(name, uuid, operatorName, operatorUuid, source.getValue());
        entries.add(entry);
        return this;
    }
    
    /**
     * 添加现有条目到批量操作
     */
    public BatchOperation addEntry(WhitelistEntry entry) {
        entries.add(entry);
        return this;
    }
    
    /**
     * 批量添加多个条目
     */
    public BatchOperation addEntries(List<PlayerInfo> players, WhitelistEntry.Source source) {
        for (PlayerInfo player : players) {
            addEntry(player.getName(), player.getUuid(), source);
        }
        return this;
    }
    
    // Getters
    public OperationType getOperationType() {
        return operationType;
    }
    
    public List<WhitelistEntry> getEntries() {
        return entries;
    }
    
    public String getOperatorName() {
        return operatorName;
    }
    
    public String getOperatorUuid() {
        return operatorUuid;
    }
    
    public int getSize() {
        return entries.size();
    }
    
    public boolean isEmpty() {
        return entries.isEmpty();
    }
    
    /**
     * 操作类型枚举
     */
    public enum OperationType {
        ADD("ADD"),
        REMOVE("REMOVE");
        
        private final String value;
        
        OperationType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
    
    /**
     * 玩家信息类
     */
    public static class PlayerInfo {
        private final String name;
        private final String uuid;
        
        public PlayerInfo(String name, String uuid) {
            this.name = name;
            this.uuid = uuid;
        }
        
        public String getName() {
            return name;
        }
        
        public String getUuid() {
            return uuid;
        }
        
        @Override
        public String toString() {
            return "PlayerInfo{name='" + name + "', uuid='" + uuid + "'}";
        }
    }
    
    /**
     * 批量操作结果类
     */
    public static class BatchResult {
        private final int totalRequested;
        private final int successCount;
        private final int failureCount;
        private final List<String> errors;
        private final List<String> successfulUuids;
        private final List<String> failedUuids;
        
        public BatchResult(int totalRequested) {
            this.totalRequested = totalRequested;
            this.successCount = 0;
            this.failureCount = 0;
            this.errors = new ArrayList<>();
            this.successfulUuids = new ArrayList<>();
            this.failedUuids = new ArrayList<>();
        }
        
        public BatchResult(int totalRequested, int successCount, int failureCount, 
                          List<String> errors, List<String> successfulUuids, List<String> failedUuids) {
            this.totalRequested = totalRequested;
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
            this.successfulUuids = successfulUuids != null ? new ArrayList<>(successfulUuids) : new ArrayList<>();
            this.failedUuids = failedUuids != null ? new ArrayList<>(failedUuids) : new ArrayList<>();
        }
        
        public int getTotalRequested() {
            return totalRequested;
        }
        
        public int getSuccessCount() {
            return successCount;
        }
        
        public int getFailureCount() {
            return failureCount;
        }
        
        public List<String> getErrors() {
            return errors;
        }
        
        public List<String> getSuccessfulUuids() {
            return successfulUuids;
        }
        
        public List<String> getFailedUuids() {
            return failedUuids;
        }
        
        public boolean isCompleteSuccess() {
            return failureCount == 0 && successCount == totalRequested;
        }
        
        public boolean isCompleteFailure() {
            return successCount == 0 && failureCount == totalRequested;
        }
        
        public double getSuccessRate() {
            if (totalRequested == 0) return 0.0;
            return (double) successCount / totalRequested;
        }
        
        @Override
        public String toString() {
            return "BatchResult{" +
                    "total=" + totalRequested +
                    ", success=" + successCount +
                    ", failure=" + failureCount +
                    ", successRate=" + String.format("%.2f%%", getSuccessRate() * 100) +
                    '}';
        }
    }
}