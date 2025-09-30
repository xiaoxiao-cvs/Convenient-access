package com.xaoxiao.convenientaccess.sync;

import com.xaoxiao.convenientaccess.whitelist.WhitelistEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 同步冲突解决器
 * 处理数据库与JSON文件之间的数据冲突
 */
public class SyncConflictResolver {
    private static final Logger logger = LoggerFactory.getLogger(SyncConflictResolver.class);
    
    /**
     * 解决同步冲突
     */
    public ConflictResolution resolveConflicts(List<WhitelistEntry> databaseEntries, 
                                             List<WhitelistEntry> jsonEntries) {
        
        logger.debug("开始解决同步冲突，数据库条目: {}, JSON条目: {}", 
                databaseEntries.size(), jsonEntries.size());
        
        // 按UUID分组
        Map<String, WhitelistEntry> dbMap = databaseEntries.stream()
                .collect(Collectors.toMap(WhitelistEntry::getUuid, entry -> entry));
        
        Map<String, WhitelistEntry> jsonMap = jsonEntries.stream()
                .collect(Collectors.toMap(WhitelistEntry::getUuid, entry -> entry));
        
        ConflictResolution resolution = new ConflictResolution();
        
        // 处理数据库中存在但JSON中不存在的条目
        for (WhitelistEntry dbEntry : databaseEntries) {
            String uuid = dbEntry.getUuid();
            if (!jsonMap.containsKey(uuid)) {
                // 数据库有，JSON没有 - 需要添加到JSON
                resolution.addToJson(dbEntry);
            }
        }
        
        // 处理JSON中存在但数据库中不存在的条目
        for (WhitelistEntry jsonEntry : jsonEntries) {
            String uuid = jsonEntry.getUuid();
            if (!dbMap.containsKey(uuid)) {
                // JSON有，数据库没有 - 需要添加到数据库
                resolution.addToDatabase(jsonEntry);
            }
        }
        
        // 处理两边都存在但数据不一致的条目
        for (String uuid : dbMap.keySet()) {
            if (jsonMap.containsKey(uuid)) {
                WhitelistEntry dbEntry = dbMap.get(uuid);
                WhitelistEntry jsonEntry = jsonMap.get(uuid);
                
                if (!areEntriesEqual(dbEntry, jsonEntry)) {
                    // 数据不一致，需要解决冲突
                    WhitelistEntry resolvedEntry = resolveDataConflict(dbEntry, jsonEntry);
                    resolution.addConflict(new DataConflict(dbEntry, jsonEntry, resolvedEntry));
                }
            }
        }
        
        logger.info("冲突解决完成 - 添加到JSON: {}, 添加到数据库: {}, 数据冲突: {}", 
                resolution.getToAddToJson().size(), 
                resolution.getToAddToDatabase().size(), 
                resolution.getConflicts().size());
        
        return resolution;
    }
    
    /**
     * 解决数据冲突
     * 使用时间戳优先策略
     */
    private WhitelistEntry resolveDataConflict(WhitelistEntry dbEntry, WhitelistEntry jsonEntry) {
        // 策略1: 时间戳优先 - 使用最新的数据
        LocalDateTime dbTime = dbEntry.getUpdatedAt() != null ? dbEntry.getUpdatedAt() : dbEntry.getCreatedAt();
        LocalDateTime jsonTime = jsonEntry.getUpdatedAt() != null ? jsonEntry.getUpdatedAt() : jsonEntry.getCreatedAt();
        
        if (dbTime != null && jsonTime != null) {
            if (dbTime.isAfter(jsonTime)) {
                logger.debug("使用数据库数据解决冲突: {} (数据库时间更新)", dbEntry.getUuid());
                return dbEntry;
            } else if (jsonTime.isAfter(dbTime)) {
                logger.debug("使用JSON数据解决冲突: {} (JSON时间更新)", jsonEntry.getUuid());
                return jsonEntry;
            }
        }
        
        // 策略2: 操作类型优先级
        ConflictPriority dbPriority = getConflictPriority(dbEntry);
        ConflictPriority jsonPriority = getConflictPriority(jsonEntry);
        
        if (dbPriority.ordinal() < jsonPriority.ordinal()) {
            logger.debug("使用数据库数据解决冲突: {} (优先级更高)", dbEntry.getUuid());
            return dbEntry;
        } else if (jsonPriority.ordinal() < dbPriority.ordinal()) {
            logger.debug("使用JSON数据解决冲突: {} (优先级更高)", jsonEntry.getUuid());
            return jsonEntry;
        }
        
        // 策略3: 默认使用数据库数据
        logger.debug("使用数据库数据解决冲突: {} (默认策略)", dbEntry.getUuid());
        return dbEntry;
    }
    
    /**
     * 获取冲突优先级
     */
    private ConflictPriority getConflictPriority(WhitelistEntry entry) {
        if (!entry.isActive()) {
            return ConflictPriority.DELETION; // 删除操作优先级最高
        }
        
        switch (WhitelistEntry.Source.fromString(entry.getSource())) {
            case SYSTEM:
                return ConflictPriority.SYSTEM_ADD;
            case ADMIN:
                return ConflictPriority.ADMIN_ADD;
            case PLAYER:
            default:
                return ConflictPriority.PLAYER_ADD;
        }
    }
    
    /**
     * 检查两个条目是否相等
     */
    private boolean areEntriesEqual(WhitelistEntry entry1, WhitelistEntry entry2) {
        if (entry1 == null || entry2 == null) {
            return entry1 == entry2;
        }
        
        return entry1.getName().equals(entry2.getName()) &&
               entry1.getUuid().equals(entry2.getUuid()) &&
               entry1.isActive() == entry2.isActive() &&
               entry1.getSource().equals(entry2.getSource());
    }
    
    /**
     * 冲突优先级枚举
     */
    private enum ConflictPriority {
        DELETION,      // 删除操作 - 最高优先级
        SYSTEM_ADD,    // 系统添加
        ADMIN_ADD,     // 管理员添加
        PLAYER_ADD     // 玩家添加 - 最低优先级
    }
    
    /**
     * 冲突解决结果类
     */
    public static class ConflictResolution {
        private final List<WhitelistEntry> toAddToJson = new ArrayList<>();
        private final List<WhitelistEntry> toAddToDatabase = new ArrayList<>();
        private final List<DataConflict> conflicts = new ArrayList<>();
        
        public void addToJson(WhitelistEntry entry) {
            toAddToJson.add(entry);
        }
        
        public void addToDatabase(WhitelistEntry entry) {
            toAddToDatabase.add(entry);
        }
        
        public void addConflict(DataConflict conflict) {
            conflicts.add(conflict);
        }
        
        public List<WhitelistEntry> getToAddToJson() {
            return toAddToJson;
        }
        
        public List<WhitelistEntry> getToAddToDatabase() {
            return toAddToDatabase;
        }
        
        public List<DataConflict> getConflicts() {
            return conflicts;
        }
        
        public boolean hasConflicts() {
            return !toAddToJson.isEmpty() || !toAddToDatabase.isEmpty() || !conflicts.isEmpty();
        }
        
        public int getTotalChanges() {
            return toAddToJson.size() + toAddToDatabase.size() + conflicts.size();
        }
    }
    
    /**
     * 数据冲突类
     */
    public static class DataConflict {
        private final WhitelistEntry databaseEntry;
        private final WhitelistEntry jsonEntry;
        private final WhitelistEntry resolvedEntry;
        
        public DataConflict(WhitelistEntry databaseEntry, WhitelistEntry jsonEntry, WhitelistEntry resolvedEntry) {
            this.databaseEntry = databaseEntry;
            this.jsonEntry = jsonEntry;
            this.resolvedEntry = resolvedEntry;
        }
        
        public WhitelistEntry getDatabaseEntry() {
            return databaseEntry;
        }
        
        public WhitelistEntry getJsonEntry() {
            return jsonEntry;
        }
        
        public WhitelistEntry getResolvedEntry() {
            return resolvedEntry;
        }
        
        public boolean isResolvedFromDatabase() {
            return resolvedEntry.equals(databaseEntry);
        }
        
        public boolean isResolvedFromJson() {
            return resolvedEntry.equals(jsonEntry);
        }
        
        @Override
        public String toString() {
            return "DataConflict{" +
                    "uuid=" + databaseEntry.getUuid() +
                    ", resolvedFrom=" + (isResolvedFromDatabase() ? "DATABASE" : "JSON") +
                    '}';
        }
    }
}