package com.xaoxiao.convenientaccess.whitelist;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.database.DatabaseManager;

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
        return addPlayer(name, uuid, addedByName, addedByUuid, source, LocalDateTime.now());
    }
    
    /**
     * 添加玩家到白名单（只需用户名，UUID留空待后续补充）
     */
    public CompletableFuture<Boolean> addPlayerByNameOnly(String name, String addedByName, String addedByUuid, WhitelistEntry.Source source) {
        return addPlayerByNameOnly(name, addedByName, addedByUuid, source, LocalDateTime.now());
    }
    
    /**
     * 添加玩家到白名单（只需用户名，支持自定义时间戳）
     */
    public CompletableFuture<Boolean> addPlayerByNameOnly(String name, String addedByName, String addedByUuid, WhitelistEntry.Source source, LocalDateTime addedAt) {
        // 参数验证
        if (!isValidPlayerName(name)) {
            logger.warn("无效的玩家名: {}", name);
            return CompletableFuture.completedFuture(false);
        }
        
        // 先检查是否已存在该玩家名
        return isPlayerWhitelistedByName(name).thenCompose(exists -> {
            if (exists) {
                logger.info("玩家 {} 已在白名单中", name);
                return CompletableFuture.completedFuture(false);
            }
            
            // UUID留空，等玩家登录时补充
            WhitelistEntry entry = new WhitelistEntry(name, null, addedByName, addedByUuid, source.getValue(), addedAt);
            
            return databaseManager.executeTransactionAsync(connection -> {
                String sql = """
                    INSERT INTO whitelist (name, uuid, added_by_name, added_by_uuid, added_at, source, is_active)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
                
                try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setString(1, entry.getName());
                    stmt.setString(2, entry.getUuid()); // null
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
                        
                        // 缓存使用玩家名作为key（因为UUID为空）
                        cache.put("name:" + name.toLowerCase(), entry);
                        logger.info("添加玩家到白名单（仅用户名）: {}", name);
                        return true;
                    }
                    return false;
                }
            }).exceptionally(throwable -> {
                logger.error("添加玩家到白名单失败: {}", name, throwable);
                return false;
            });
        });
    }
    
    /**
     * 添加玩家到白名单（离线模式 - 只需用户名，UUID可为空）
     * @deprecated 使用 addPlayerByNameOnly 替代
     */
    @Deprecated
    public CompletableFuture<Boolean> addPlayerOffline(String name, String addedByName, String addedByUuid, WhitelistEntry.Source source) {
        return addPlayerByNameOnly(name, addedByName, addedByUuid, source);
    }
    
    /**
     * 生成离线模式UUID（基于玩家名）
     */
    private String generateOfflineUuid(String playerName) {
        // 使用 Minecraft 离线模式的 UUID 生成算法
        java.util.UUID uuid = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return uuid.toString();
    }
    
    /**
     * 添加玩家到白名单（支持自定义时间戳）
     */
    public CompletableFuture<Boolean> addPlayer(String name, String uuid, String addedByName, String addedByUuid, WhitelistEntry.Source source, LocalDateTime addedAt) {
        // 参数验证
        if (!isValidPlayerName(name) || !isValidUuid(uuid)) {
            return CompletableFuture.completedFuture(false);
        }
        
        WhitelistEntry entry = new WhitelistEntry(name, uuid, addedByName, addedByUuid, source.getValue(), addedAt);
        
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
     * 通过玩家名称从白名单移除玩家
     */
    public CompletableFuture<Boolean> removePlayerByName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        return databaseManager.executeTransactionAsync(connection -> {
            String sql = "DELETE FROM whitelist WHERE name = ?";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, name);
                
                int affected = stmt.executeUpdate();
                if (affected > 0) {
                    // 更新缓存 - 需要找到对应的UUID
                    String uuidToRemove = null;
                    for (Map.Entry<String, WhitelistEntry> entry : cache.entrySet()) {
                        if (name.equals(entry.getValue().getName())) {
                            uuidToRemove = entry.getKey();
                            break;
                        }
                    }
                    
                    if (uuidToRemove != null) {
                        WhitelistEntry removed = cache.remove(uuidToRemove);
                        if (removed != null) {
                            logger.info("从白名单移除玩家(按名称): {}", name);
                        }
                    } else {
                        logger.info("从白名单移除玩家(按名称,未缓存): {}", name);
                    }
                    return true;
                }
                return false;
            }
        }).exceptionally(throwable -> {
            logger.error("从白名单移除玩家失败(按名称): {}", name, throwable);
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
     * 通过玩家名检查是否在白名单中
     */
    public CompletableFuture<Boolean> isPlayerWhitelistedByName(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        String normalizedName = playerName.trim().toLowerCase();
        
        // 先检查缓存（检查基于名称的缓存）
        if (cacheLoaded && cache.containsKey("name:" + normalizedName)) {
            WhitelistEntry entry = cache.get("name:" + normalizedName);
            return CompletableFuture.completedFuture(entry != null && entry.isActive());
        }
        
        // 查询数据库
        return databaseManager.executeAsync(connection -> {
            String sql = "SELECT is_active FROM whitelist WHERE LOWER(name) = ? AND is_active = 1";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, normalizedName);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        }).exceptionally(throwable -> {
            logger.error("检查玩家白名单状态失败（按名称）: {}", playerName, throwable);
            return false;
        });
    }

    /**
     * 检查玩家是否在白名单中（离线模式 - 同时检查用户名和UUID）
     */
    public CompletableFuture<Boolean> isPlayerWhitelistedOffline(String playerName, String uuid) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        // 先检查 UUID（如果有效）
        if (isValidUuid(uuid)) {
            if (cacheLoaded && cache.containsKey(uuid)) {
                WhitelistEntry entry = cache.get(uuid);
                if (entry != null && entry.isActive()) {
                    return CompletableFuture.completedFuture(true);
                }
            }
        }
        
        // 查询数据库 - 同时检查用户名和UUID
        return databaseManager.executeAsync(connection -> {
            String sql = """
                SELECT is_active FROM whitelist 
                WHERE (LOWER(name) = LOWER(?) OR uuid = ?) 
                AND is_active = 1
                LIMIT 1
            """;
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, playerName.trim());
                stmt.setString(2, uuid);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    boolean found = rs.next();
                    if (found) {
                        logger.info("离线模式白名单匹配: 玩家 {} (UUID: {}) 已在白名单中", playerName, uuid);
                    }
                    return found;
                }
            }
        }).exceptionally(throwable -> {
            logger.error("检查离线模式玩家白名单状态失败: {} ({})", playerName, uuid, throwable);
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
                    return Optional.<WhitelistEntry>empty();
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
     * 更新玩家UUID（当玩家首次登录时）
     */
    public CompletableFuture<Boolean> updatePlayerUuid(String playerName, String uuid) {
        if (!isValidPlayerName(playerName) || !isValidUuid(uuid)) {
            logger.warn("无效的玩家名或UUID: name={}, uuid={}", playerName, uuid);
            return CompletableFuture.completedFuture(false);
        }
        
        return databaseManager.executeTransactionAsync(connection -> {
            String sql = "UPDATE whitelist SET uuid = ?, updated_at = CURRENT_TIMESTAMP WHERE LOWER(name) = ? AND uuid IS NULL";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                stmt.setString(2, playerName.toLowerCase());
                
                int affected = stmt.executeUpdate();
                if (affected > 0) {
                    // 更新缓存
                    String nameKey = "name:" + playerName.toLowerCase();
                    WhitelistEntry entry = cache.get(nameKey);
                    if (entry != null) {
                        entry.setUuid(uuid);
                        // 添加基于UUID的缓存
                        cache.put(uuid, entry);
                        // 可以选择保留基于名称的缓存或移除
                        // cache.remove(nameKey);
                    }
                    
                    logger.info("更新玩家UUID: {} -> {}", playerName, uuid);
                    return true;
                }
                return false;
            }
        }).exceptionally(throwable -> {
            logger.error("更新玩家UUID失败: {} -> {}", playerName, uuid, throwable);
            return false;
        });
    }
    
    /**
     * 根据玩家名获取白名单条目
     */
    public CompletableFuture<Optional<WhitelistEntry>> getPlayerByName(String playerName) {
        if (!isValidPlayerName(playerName)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        String normalizedName = playerName.toLowerCase();
        
        // 先检查缓存
        if (cacheLoaded && cache.containsKey("name:" + normalizedName)) {
            return CompletableFuture.completedFuture(Optional.ofNullable(cache.get("name:" + normalizedName)));
        }
        
        // 查询数据库
        return databaseManager.executeAsync(connection -> {
            String sql = "SELECT * FROM whitelist WHERE LOWER(name) = ?";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, normalizedName);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        WhitelistEntry entry = mapResultSetToEntry(rs);
                        // 更新缓存
                        if (cacheLoaded) {
                            cache.put("name:" + normalizedName, entry);
                            if (entry.getUuid() != null) {
                                cache.put(entry.getUuid(), entry);
                            }
                        }
                        return Optional.of(entry);
                    }
                    return Optional.<WhitelistEntry>empty();
                }
            }
        }).exceptionally(throwable -> {
            logger.error("根据玩家名获取白名单条目失败: {}", playerName, throwable);
            return Optional.<WhitelistEntry>empty();
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
                    // 只缓存有 UUID 的条目，UUID 待补充的条目不放入缓存
                    if (entry.getUuid() != null) {
                        cache.put(entry.getUuid(), entry);
                    }
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
     * 分页查询白名单条目
     */
    public CompletableFuture<PaginatedResult<WhitelistEntry>> getWhitelistPaginated(
            int page, int size, String search, String source, String addedBy, 
            String sort, String order, String startDate, String endDate) {
        
        return databaseManager.executeAsync(connection -> {
            // 构建查询条件
            WhitelistQueryBuilder queryBuilder = new WhitelistQueryBuilder()
                    .filterByActive(true)
                    .paginate(page, size);
            
            // 添加搜索条件
            if (search != null && !search.trim().isEmpty()) {
                queryBuilder.searchByName(search);
            }
            
            if (source != null && !source.trim().isEmpty()) {
                queryBuilder.filterBySource(source);
            }
            
            if (addedBy != null && !addedBy.trim().isEmpty()) {
                queryBuilder.filterByAddedBy(addedBy);
            }
            
            if (startDate != null || endDate != null) {
                queryBuilder.filterByDateRange(startDate, endDate);
            }
            
            // 设置排序
            if (sort != null && !sort.trim().isEmpty()) {
                queryBuilder.orderBy(sort, order);
            }
            
            // 执行查询
            WhitelistQueryBuilder.QueryResult queryResult = queryBuilder.build();
            WhitelistQueryBuilder.QueryResult countResult = queryBuilder.buildCount();
            
            // 获取总数
            long total = 0;
            try (PreparedStatement countStmt = connection.prepareStatement(countResult.getSql())) {
                setParameters(countStmt, countResult.getParameters());
                try (ResultSet rs = countStmt.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getLong(1);
                    }
                }
            }
            
            // 获取数据
            List<WhitelistEntry> items = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement(queryResult.getSql())) {
                setParameters(stmt, queryResult.getParameters());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        items.add(mapResultSetToEntry(rs));
                    }
                }
            }
            
            return new PaginatedResult<>(items, page, size, total);
        }).exceptionally(throwable -> {
            logger.error("分页查询白名单失败", throwable);
            return new PaginatedResult<>(new ArrayList<>(), page, size, 0);
        });
    }
    
    /**
     * 高级搜索白名单条目
     */
    public CompletableFuture<List<WhitelistEntry>> searchWhitelistAdvanced(
            String namePattern, String uuidPattern, String sourceFilter, 
            String addedByFilter, int limit) {
        
        return databaseManager.executeAsync(connection -> {
            WhitelistQueryBuilder queryBuilder = new WhitelistQueryBuilder()
                    .filterByActive(true);
            
            // 添加搜索条件
            if (namePattern != null && !namePattern.trim().isEmpty()) {
                queryBuilder.searchByName(namePattern);
            }
            
            if (uuidPattern != null && !uuidPattern.trim().isEmpty()) {
                queryBuilder.filterByUuid(uuidPattern);
            }
            
            if (sourceFilter != null && !sourceFilter.trim().isEmpty()) {
                queryBuilder.filterBySource(sourceFilter);
            }
            
            if (addedByFilter != null && !addedByFilter.trim().isEmpty()) {
                queryBuilder.filterByAddedBy(addedByFilter);
            }
            
            // 设置限制
            if (limit > 0) {
                queryBuilder.paginate(1, Math.min(limit, 100));
            }
            
            WhitelistQueryBuilder.QueryResult queryResult = queryBuilder.build();
            
            List<WhitelistEntry> results = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement(queryResult.getSql())) {
                setParameters(stmt, queryResult.getParameters());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapResultSetToEntry(rs));
                    }
                }
            }
            
            return results;
        }).exceptionally(throwable -> {
            logger.error("高级搜索白名单失败", throwable);
            return new ArrayList<>();
        });
    }
    
    /**
     * 设置PreparedStatement参数
     */
    private void setParameters(PreparedStatement stmt, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            Object param = parameters.get(i);
            if (param instanceof String) {
                stmt.setString(i + 1, (String) param);
            } else if (param instanceof Integer) {
                stmt.setInt(i + 1, (Integer) param);
            } else if (param instanceof Long) {
                stmt.setLong(i + 1, (Long) param);
            } else if (param instanceof Boolean) {
                stmt.setBoolean(i + 1, (Boolean) param);
            } else if (param instanceof LocalDateTime) {
                stmt.setTimestamp(i + 1, Timestamp.valueOf((LocalDateTime) param));
            } else {
                stmt.setObject(i + 1, param);
            }
        }
    }
    
    /**
     * 执行批量操作
     */
    public CompletableFuture<BatchOperation.BatchResult> executeBatchOperation(BatchOperation batchOperation) {
        if (batchOperation.isEmpty()) {
            return CompletableFuture.completedFuture(
                new BatchOperation.BatchResult(0, 0, 0, List.of("批量操作为空"), new ArrayList<>(), new ArrayList<>())
            );
        }
        
        return databaseManager.executeTransactionAsync(connection -> {
            List<String> errors = new ArrayList<>();
            List<String> successfulUuids = new ArrayList<>();
            List<String> failedUuids = new ArrayList<>();
            int successCount = 0;
            int failureCount = 0;
            
            if (batchOperation.getOperationType() == BatchOperation.OperationType.ADD) {
                // 批量添加
                String sql = """
                    INSERT OR IGNORE INTO whitelist (name, uuid, added_by_name, added_by_uuid, added_at, source, is_active)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
                
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    for (WhitelistEntry entry : batchOperation.getEntries()) {
                        try {
                            // 验证数据
                            if (!isValidPlayerName(entry.getName()) || !isValidUuid(entry.getUuid())) {
                                errors.add("无效的玩家数据: " + entry.getName() + " (" + entry.getUuid() + ")");
                                failedUuids.add(entry.getUuid());
                                failureCount++;
                                continue;
                            }
                            
                            stmt.setString(1, entry.getName());
                            stmt.setString(2, entry.getUuid());
                            stmt.setString(3, entry.getAddedByName());
                            stmt.setString(4, entry.getAddedByUuid());
                            stmt.setTimestamp(5, Timestamp.valueOf(entry.getAddedAt()));
                            stmt.setString(6, entry.getSource());
                            stmt.setBoolean(7, entry.isActive());
                            
                            int affected = stmt.executeUpdate();
                            if (affected > 0) {
                                successfulUuids.add(entry.getUuid());
                                successCount++;
                                
                                // 更新缓存
                                if (cacheLoaded) {
                                    cache.put(entry.getUuid(), entry);
                                }
                            } else {
                                errors.add("玩家已存在: " + entry.getName() + " (" + entry.getUuid() + ")");
                                failedUuids.add(entry.getUuid());
                                failureCount++;
                            }
                        } catch (SQLException e) {
                            errors.add("添加玩家失败: " + entry.getName() + " - " + e.getMessage());
                            failedUuids.add(entry.getUuid());
                            failureCount++;
                        }
                    }
                }
                
            } else if (batchOperation.getOperationType() == BatchOperation.OperationType.REMOVE) {
                // 批量删除
                String sql = "DELETE FROM whitelist WHERE uuid = ?";
                
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    for (WhitelistEntry entry : batchOperation.getEntries()) {
                        try {
                            stmt.setString(1, entry.getUuid());
                            
                            int affected = stmt.executeUpdate();
                            if (affected > 0) {
                                successfulUuids.add(entry.getUuid());
                                successCount++;
                                
                                // 更新缓存
                                cache.remove(entry.getUuid());
                            } else {
                                errors.add("玩家不存在: " + entry.getUuid());
                                failedUuids.add(entry.getUuid());
                                failureCount++;
                            }
                        } catch (SQLException e) {
                            errors.add("删除玩家失败: " + entry.getUuid() + " - " + e.getMessage());
                            failedUuids.add(entry.getUuid());
                            failureCount++;
                        }
                    }
                }
            }
            
            logger.info("批量操作完成: {} - 成功: {}, 失败: {}", 
                    batchOperation.getOperationType(), successCount, failureCount);
            
            return new BatchOperation.BatchResult(
                batchOperation.getSize(), successCount, failureCount, 
                errors, successfulUuids, failedUuids
            );
            
        }).exceptionally(throwable -> {
            logger.error("批量操作执行失败", throwable);
            List<String> errorList = List.of("批量操作执行失败: " + throwable.getMessage());
            List<String> allUuids = batchOperation.getEntries().stream()
                    .map(WhitelistEntry::getUuid)
                    .toList();
            return new BatchOperation.BatchResult(
                batchOperation.getSize(), 0, batchOperation.getSize(), 
                errorList, new ArrayList<>(), allUuids
            );
        });
    }
    
    /**
     * 批量删除玩家（通过UUID列表）
     */
    public CompletableFuture<BatchOperation.BatchResult> batchRemovePlayersByUuid(
            List<String> uuids, String operatorName, String operatorUuid) {
        
        BatchOperation batchOperation = new BatchOperation(
            BatchOperation.OperationType.REMOVE, operatorName, operatorUuid
        );
        
        // 添加要删除的条目
        for (String uuid : uuids) {
            if (isValidUuid(uuid)) {
                WhitelistEntry entry = new WhitelistEntry();
                entry.setUuid(uuid);
                batchOperation.addEntry(entry);
            }
        }
        
        return executeBatchOperation(batchOperation);
     }
     
     /**
      * 重新加载缓存
      */
     public CompletableFuture<Boolean> reloadCache() {
         return loadCache();
     }
 }