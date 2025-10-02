package com.xaoxiao.convenientaccess.database;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;

/**
 * SQLite数据库管理器
 * 负责数据库连接池管理、初始化、迁移和事务管理
 */
public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    
    private final ConvenientAccessPlugin plugin;
    private final String databasePath;
    private final ExecutorService executorService;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // 数据库版本
    private static final int CURRENT_VERSION = 2; // 增加版本以支持管理员认证系统
    
    public DatabaseManager(ConvenientAccessPlugin plugin) {
        this.plugin = plugin;
        this.databasePath = plugin.getDataFolder().getAbsolutePath() + File.separator + "whitelist.db";
        // 增加线程池大小以处理更多并发数据库操作
        this.executorService = Executors.newFixedThreadPool(8, r -> {
            Thread thread = new Thread(r, "DatabaseManager-Thread");
            thread.setDaemon(true);
            return thread;
        });
    }
    
    /**
     * 初始化数据库
     */
    public CompletableFuture<Boolean> initialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 确保数据文件夹存在
                File dataFolder = plugin.getDataFolder();
                if (!dataFolder.exists()) {
                    dataFolder.mkdirs();
                }
                
                // 创建数据库连接
                try (Connection connection = getConnection()) {
                    // 启用外键约束和优化并发性能
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute("PRAGMA foreign_keys = ON");
                        stmt.execute("PRAGMA journal_mode = WAL");
                        stmt.execute("PRAGMA synchronous = NORMAL");
                        stmt.execute("PRAGMA cache_size = 10000");
                        stmt.execute("PRAGMA temp_store = MEMORY");
                        stmt.execute("PRAGMA busy_timeout = 30000"); // 设置30秒的锁等待超时
                        stmt.execute("PRAGMA wal_autocheckpoint = 1000"); // WAL自动检查点
                    }
                    
                    // 检查数据库版本
                    int currentVersion = getDatabaseVersion(connection);
                    if (currentVersion == 0) {
                        // 新数据库，创建所有表
                        createTables(connection);
                        setDatabaseVersion(connection, CURRENT_VERSION);
                        logger.info("数据库初始化完成，版本: {}", CURRENT_VERSION);
                    } else if (currentVersion < CURRENT_VERSION) {
                        // 需要升级
                        migrateDatabaseFrom(connection, currentVersion);
                        logger.info("数据库升级完成，从版本 {} 升级到 {}", currentVersion, CURRENT_VERSION);
                    }
                    
                    initialized.set(true);
                    return true;
                }
            } catch (Exception e) {
                logger.error("数据库初始化失败", e);
                return false;
            }
        }, executorService);
    }
    
    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        // 为每个连接设置 busy_timeout，处理并发锁等待
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = 30000"); // 30秒超时
        } catch (SQLException e) {
            logger.warn("设置 busy_timeout 失败: {}", e.getMessage());
        }
        return conn;
    }
    
    /**
     * 异步执行数据库操作
     */
    public <T> CompletableFuture<T> executeAsync(DatabaseOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = getConnection()) {
                return operation.execute(connection);
            } catch (Exception e) {
                logger.error("数据库操作执行失败", e);
                throw new RuntimeException(e);
            }
        }, executorService);
    }
    
    /**
     * 异步执行事务操作
     */
    public <T> CompletableFuture<T> executeTransactionAsync(DatabaseOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = getConnection()) {
                connection.setAutoCommit(false);
                try {
                    T result = operation.execute(connection);
                    connection.commit();
                    return result;
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            } catch (Exception e) {
                logger.error("数据库事务执行失败", e);
                throw new RuntimeException(e);
            }
        }, executorService);
    }
    
    /**
     * 创建所有数据库表
     */
    private void createTables(Connection connection) throws SQLException {
        logger.info("开始创建数据库表...");
        
        // 读取SQL脚本并执行（简化版，只保留必要的表）
        String[] sqlScripts = {
            "schema/whitelist.sql",
            "schema/sync_tasks.sql", 
            "schema/operation_log.sql",
            "schema/registration_tokens.sql",
            "schema/admin_users.sql",
            "schema/admin_sessions.sql",
            "schema/auth_logs.sql",
            "schema/indexes.sql"
        };
        
        int successCount = 0;
        for (String scriptPath : sqlScripts) {
            try {
                executeScript(connection, scriptPath);
                successCount++;
            } catch (SQLException e) {
                logger.error("执行SQL脚本失败: {}", scriptPath, e);
                // 继续执行其他脚本，不中断整个过程
            }
        }
        
        logger.info("数据库表创建完成，成功执行 {}/{} 个脚本", successCount, sqlScripts.length);
        
        // 插入初始数据
        insertInitialData(connection);
    }
    
    /**
     * 执行SQL脚本
     */
    private void executeScript(Connection connection, String scriptPath) throws SQLException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(scriptPath)) {
            if (inputStream == null) {
                logger.warn("SQL脚本文件不存在: {}", scriptPath);
                return;
            }
            
            String sql = new String(inputStream.readAllBytes());
            
            // 移除注释并处理多行语句
            StringBuilder cleanSql = new StringBuilder();
            String[] lines = sql.split("\n");
            
            for (String line : lines) {
                String trimmedLine = line.trim();
                // 跳过空行和注释行
                if (!trimmedLine.isEmpty() && !trimmedLine.startsWith("--")) {
                    cleanSql.append(line).append("\n");
                }
            }
            
            // 按分号分割语句，但要处理字符串中的分号
            String[] statements = splitSqlStatements(cleanSql.toString());
            
            try (Statement stmt = connection.createStatement()) {
                for (String statement : statements) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        logger.debug("执行SQL: {}", trimmed);
                        stmt.execute(trimmed);
                    }
                }
            }
            
            logger.info("成功执行SQL脚本: {}", scriptPath);
        } catch (IOException e) {
            logger.error("读取SQL脚本失败: {}", scriptPath, e);
            throw new SQLException("读取SQL脚本失败", e);
        } catch (SQLException e) {
            logger.error("执行SQL脚本失败: {}", scriptPath, e);
            throw e;
        }
    }
    
    /**
     * 智能分割SQL语句（处理字符串中的分号）
     */
    private String[] splitSqlStatements(String sql) {
        java.util.List<String> statements = new java.util.ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();
        boolean inString = false;
        char stringChar = 0;
        
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            
            if (!inString && (c == '\'' || c == '"')) {
                inString = true;
                stringChar = c;
            } else if (inString && c == stringChar) {
                // 检查是否是转义字符
                if (i + 1 < sql.length() && sql.charAt(i + 1) == stringChar) {
                    currentStatement.append(c);
                    i++; // 跳过下一个字符
                } else {
                    inString = false;
                }
            } else if (!inString && c == ';') {
                String statement = currentStatement.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                currentStatement = new StringBuilder();
                continue;
            }
            
            currentStatement.append(c);
        }
        
        // 添加最后一个语句
        String lastStatement = currentStatement.toString().trim();
        if (!lastStatement.isEmpty()) {
            statements.add(lastStatement);
        }
        
        return statements.toArray(new String[0]);
    }
    
    /**
     * 插入初始数据（简化版）
     */
    private void insertInitialData(Connection connection) throws SQLException {
        logger.debug("简化版系统无需插入初始数据");
        // 简化版系统不需要插入角色等初始数据
    }
    
    /**
     * 获取数据库版本
     */
    private int getDatabaseVersion(Connection connection) throws SQLException {
        // 检查版本表是否存在
        String checkTable = """
            SELECT name FROM sqlite_master 
            WHERE type='table' AND name='database_version'
        """;
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(checkTable)) {
            
            if (!rs.next()) {
                // 版本表不存在，创建它
                String createVersionTable = """
                    CREATE TABLE database_version (
                        version INTEGER PRIMARY KEY
                    )
                """;
                stmt.execute(createVersionTable);
                return 0;
            }
        }
        
        // 获取当前版本
        String getVersion = "SELECT version FROM database_version LIMIT 1";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(getVersion)) {
            
            if (rs.next()) {
                return rs.getInt("version");
            } else {
                return 0;
            }
        }
    }
    
    /**
     * 设置数据库版本
     */
    private void setDatabaseVersion(Connection connection, int version) throws SQLException {
        String sql = "INSERT OR REPLACE INTO database_version (version) VALUES (?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, version);
            stmt.executeUpdate();
        }
    }
    
    /**
     * 数据库迁移
     */
    private void migrateDatabaseFrom(Connection connection, int fromVersion) throws SQLException {
        logger.info("开始数据库迁移，从版本 {} 到版本 {}", fromVersion, CURRENT_VERSION);
        
        for (int version = fromVersion; version < CURRENT_VERSION; version++) {
            String migrationScript = "migrations/migrate_" + version + "_to_" + (version + 1) + ".sql";
            executeScript(connection, migrationScript);
            setDatabaseVersion(connection, version + 1);
            logger.info("数据库迁移完成: {} -> {}", version, version + 1);
        }
    }
    
    /**
     * 检查数据库是否已初始化
     */
    public boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * 关闭数据库管理器
     */
    public void shutdown() {
        executorService.shutdown();
        logger.info("数据库管理器已关闭");
    }
    
    /**
     * 数据库操作接口
     */
    @FunctionalInterface
    public interface DatabaseOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}