package com.xaoxiao.convenientaccess.whitelist;

import com.xaoxiao.convenientaccess.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 白名单管理器
 * 提供白名单的CRUD操作接口、数据验证和缓存管理
 */
public class WhitelistManager {
    private static final Logger logger = LoggerFactory.getLogger(WhitelistManager.class);
    
    private final DatabaseManager databaseManager;
    private final ConcurrentHashMap<String, WhitelistEntry> cache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded = false;
    
    public WhitelistManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }
    
    /**
     * 初始化白名单管理器
     */
    public CompletableFuture<Boolean> initialize() {
        return loadCache().thenApply(success -> {
            if (success) {
                logger.info("白名单管理器初始化完成，缓存了 {} 个条目", cache.size());
                return true;
            } else {
                logger.error("白名单管理器初始化失败");
                return false;
            }
        });
    }
    
    /**
     * 添加玩家到白名单
     */
    public CompletableFuture<Boolean> addPlayer(String name, String uuid, String addedByName, String addedByUuid, WhitelistEntry.Source source) {
        // 参数验证
        if (!isValidPlayerName(name) || !isValidUuid(uuid)) {
            return CompletableFuture.completedFuture(false);
        }
        
        WhitelistEntry entry = new WhitelistEntry(name, uuid, addedByName, addedByUuid, source.getValue());
        
        return databaseManager.executeTransactionAsync(connection -> {
            String sql = """
                INSERT INTO whitelist (name, uuid, added_by_name, added_by_uuid, added_at, source, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
            
            try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, entry.getName());
                stmt.setString(2, entry.getUuid());
                stmt.setString(3, entry.getAddedByName());
                stmt.setString(4, entry.getAddedByUuid());
                stmt.setTimestamp(5, Timestamp.valueOf(entry.getAddedAt()));
                stmt.setString(6, entry.getSource());
                stmt.setBoolean(7, entry.isActive());
                
                int affected = stmt.executeUpdate();
                if (affected > 0) {
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            entry.setId(rs.getLong(1));
                        }
                    }
                    
                    // 更新缓存
                    cache.put(uuid, entry);
                    logger.info("添加玩家到白名单: {} ({})", name, uuid);
                    return true;
                }
                return false;
            }
        }).exceptionally(throwable -> {
            logger.error("添加玩家到白名单失败: {} ({})", name, uuid, throwable);
            return false;
        });
    }
    
    /**
     * 从白名单移除玩家
     */
    public CompletableFuture<Boolean> removePlayer(String uuid) {
        if (!isValidUuid(uuid)) {
            return CompletableFuture.completedFuture(false);
        }
        
        return databaseManager.executeTransactionAsync(connection -> {
            String sql = "DELETE FROM whitelist WHERE uuid = ?";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                
                int affected = stmt.executeUpdate();
                if (affected > 0) {
                    // 更新缓存
                    WhitelistEntry removed = cache.remove(uuid);
                    if (removed != null) {
                        logger.info("从白名单移除玩家: {} ({})", removed.getName(), uuid);
                    }
                    return true;
                }
                return false;
            }
        }).exceptionally(throwable -> {
            logger.error("从白名单移除玩家失败: {}", uuid, throwable);
            return false;
        });
    }
    
    /**
     * 检查玩家是否在白名单中
     */
    public CompletableFuture<Boolean> isPlayerWhitelisted(String uuid) {
        if (!isValidUuid(uuid)) {
            return CompletableFuture.completedFuture(false);
        }
        
        // 先检查缓存
        if (cacheLoaded && cache.containsKey(uuid)) {
            WhitelistEntry entry = cache.get(uuid);
            return CompletableFuture.completedFuture(entry != null && entry.isActive());
        }
        
        // 查询数据库
        return databaseManager.executeAsync(connection -> {
            String sql = "SELECT is_active FROM whitelist WHERE uuid = ? AND is_active = 1";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        }).exceptionally(throwable -> {
            logger.error("检查玩家白名单状态失败: {}", uuid, throwable);
            return false;
        });
    }
    
    /**
     * 根据UUID获取白名单条目
     */
    public CompletableFuture<Optional<WhitelistEntry>> getPlayerByUuid(String uuid) {
        if (!isValidUuid(uuid)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        // 先检查缓存
        if (cacheLoaded && cache.containsKey(uuid)) {
            return CompletableFuture.completedFuture(Optional.ofNullable(cache.get(uuid)));
        }
        
        // 查询数据库
        return databaseManager.executeAsync(connection -> {
            String sql = "SELECT * FROM whitelist WHERE uuid = ?";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        WhitelistEntry entry = mapResultSetToEntry(rs);
                        // 更新缓存
                        if (cacheLoaded) {
                            cache.put(uuid, entry);
                        }
                        return Optional.of(entry);
                    }
                    return Optional.empty();
                }
            }
        }).exceptionally(throwable -> {
            logger.error("根据UUID获取白名单条目失败: {}", uuid, throwable);
            return Optional.<WhitelistEntry>empty();
        });
    }
    
    /**
     * 根据玩家名称搜索白名单条目
     */
    public CompletableFuture<List<WhitelistEntry>> searchPlayersByName(String name, int limit) {
        if (name == null || name.trim().isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        
        return databaseManager.executeAsync(connection -> {
            String sql = """
                SELECT * FROM whitelist 
                WHERE name LIKE ? AND is_active = 1 
                ORDER BY name ASC 
                LIMIT ?
            """;
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, "%" + name + "%");
                stmt.setInt(2, limit);
                
                List<WhitelistEntry> results = new ArrayList<>();
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapResultSetToEntry(rs));
                    }
                }
                return results;
            }
        }).exceptionally(throwable -> {
            logger.error("根据名称搜索白名单条目失败: {}", name, throwable);
            return new ArrayList<>();
        });
    }
    
    /**
     * 获取白名单统计信息
     */
    public CompletableFuture<WhitelistStats> getStats() {
        return databaseManager.executeAsync(connection -> {
            WhitelistStats stats = new WhitelistStats();
            
            // 总玩家数
            String totalSql = "SELECT COUNT(*) FROM whitelist";
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(totalSql)) {
                if (rs.next()) {
                    stats.setTotalPlayers(rs.getInt(1));
                }
            }
            
            // 活跃玩家数
            String activeSql = "SELECT COUNT(*) FROM whitelist WHERE is_active = 1";
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(activeSql)) {
                if (rs.next()) {
                    stats.setActivePlayers(rs.getInt(1));
                }
            }
            
            // 按来源统计
            String sourceSql = "SELECT source, COUNT(*) FROM whitelist WHERE is_active = 1 GROUP BY source";
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sourceSql)) {
                while (rs.next()) {
                    stats.addSourceCount(rs.getString(1), rs.getInt(2));
                }
            }
            
            // 最近24小时新增
            String recentSql = """
                SELECT COUNT(*) FROM whitelist 
                WHERE created_at > datetime('now', '-1 day')
            """;
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(recentSql)) {
                if (rs.next()) {
                    stats.setRecentAdditions(rs.getInt(1));
                }
            }
            
            return stats;
        }).exceptionally(throwable -> {
            logger.error("获取白名单统计信息失败", throwable);
            return new WhitelistStats();
        });
    }
    
    /**
     * 批量添加玩家
     */
    public CompletableFuture<Integer> addPlayersBatch(List<WhitelistEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        
        return databaseManager.executeTransactionAsync(connection -> {
            String sql = """
                INSERT OR IGNORE INTO whitelist (name, uuid, added_by_name, added_by_uuid, added_at, source, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
            
            int successCount = 0;
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                for (WhitelistEntry entry : entries) {
                    if (!isValidPlayerName(entry.getName()) || !isValidUuid(entry.getUuid())) {
                        continue;
                    }
                    
                    stmt.setString(1, entry.getName());
                    stmt.setString(2, entry.getUuid());
                    stmt.setString(3, entry.getAddedByName());
                    stmt.setString(4, entry.getAddedByUuid());
                    stmt.setTimestamp(5, Timestamp.valueOf(entry.getAddedAt()));
                    stmt.setString(6, entry.getSource());
                    stmt.setBoolean(7, entry.isActive());
                    
                    stmt.addBatch();
                }
                
                int[] results = stmt.executeBatch();
                for (int result : results) {
                    if (result > 0) {
                        successCount++;
                    }
                }
                
                // 更新缓存
                if (cacheLoaded) {
                    for (WhitelistEntry entry : entries) {
                        if (isValidUuid(entry.getUuid())) {
                            cache.put(entry.getUuid(), entry);
                        }
                    }
                }
            }
            
            logger.info("批量添加玩家完成，成功: {}, 总数: {}", successCount, entries.size());
            return successCount;
        }).exceptionally(throwable -> {
            logger.error("批量添加玩家失败", throwable);
            return 0;
        });
    }
    
    /**
     * 加载缓存
     */
    private CompletableFuture<Boolean> loadCache() {
        return databaseManager.executeAsync(connection -> {
            String sql = "SELECT * FROM whitelist WHERE is_active = 1";
            
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                cache.clear();
                while (rs.next()) {
                    WhitelistEntry entry = mapResultSetToEntry(rs);
                    cache.put(entry.getUuid(), entry);
                }
                
                cacheLoaded = true;
                return true;
            }
        }).exceptionally(throwable -> {
            logger.error("加载白名单缓存失败", throwable);
            return false;
        });
    }
    
    /**
     * 将ResultSet映射为WhitelistEntry
     */
    private WhitelistEntry mapResultSetToEntry(ResultSet rs) throws SQLException {
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
        return entry;
    }
    
    /**
     * 验证玩家名称格式
     */
    private boolean isValidPlayerName(String name) {
        return name != null && name.length() >= 3 && name.length() <= 16 && name.matches("^[a-zA-Z0-9_]+$");
    }
    
    /**
     * 验证UUID格式
     */
    private boolean isValidUuid(String uuid) {
        return uuid != null && uuid.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }
    
    /**
     * 获取缓存大小
     */
    public int getCacheSize() {
        return cache.size();
    }
    
    /**
     * 清空缓存
     */
    public void clearCache() {
        cache.clear();
        cacheLoaded = false;
    }
    
    /**
     * 重新加载缓存
     */
    public CompletableFuture<Boolean> reloadCache() {
        return loadCache();
    }
}