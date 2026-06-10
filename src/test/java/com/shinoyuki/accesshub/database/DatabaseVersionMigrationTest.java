package com.shinoyuki.accesshub.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * database_version 单行不变量 + 迁移自愈 (走真 SQLite).
 * 复现历史缺陷: version 作主键时 INSERT OR REPLACE 写新版本号不冲突而追加成多行,
 * 无序 LIMIT 1 读到陈旧值导致每次重启重复迁移。断言修复后:
 *  - 新库初始化为单行、版本 = CURRENT_VERSION;
 *  - 脏的多行 v3 库升级后折叠为单行 v4 且建出注册码表。
 * 删掉单行不变量 / MAX 读取, 脏库用例必挂。
 */
class DatabaseVersionMigrationTest {

    private static final int CURRENT_VERSION = 5;

    @TempDir
    File tempDir;

    private int rowCount(DatabaseManager db) throws SQLException {
        try (Connection c = db.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM database_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int version(DatabaseManager db) throws SQLException {
        try (Connection c = db.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAX(version) FROM database_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean tableExists(DatabaseManager db, String name) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    void freshDbInitializesToSingleRowCurrentVersion() throws Exception {
        DatabaseManager db = new DatabaseManager(tempDir);
        assertTrue(db.initialize().get(), "新库初始化应成功");
        assertEquals(1, rowCount(db), "version 表应恒为单行");
        assertEquals(CURRENT_VERSION, version(db), "新库应直接为最新版本");
        assertTrue(tableExists(db, "player_registration_codes"), "新库应建出注册码表");
        db.shutdown();
    }

    @Test
    void dirtyMultiRowV3DbSelfHealsToSingleRowV4() throws Exception {
        // 预置"脏库": 模拟历史缺陷累积的多行 version 表 {1,2,3}, 且尚无 v4 注册码表
        File dbFile = new File(tempDir, "whitelist.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE database_version (version INTEGER PRIMARY KEY)");
            s.execute("INSERT INTO database_version (version) VALUES (1)");
            s.execute("INSERT INTO database_version (version) VALUES (2)");
            s.execute("INSERT INTO database_version (version) VALUES (3)");
        }

        DatabaseManager db = new DatabaseManager(tempDir);
        assertTrue(db.initialize().get(), "脏库升级初始化应成功");
        // MAX 读到 3 (而非无序 LIMIT 1 的陈旧 1) -> 只跑 3->4; setDatabaseVersion 折叠为单行
        assertEquals(1, rowCount(db), "脏的多行 version 表应被折叠为单行");
        assertEquals(CURRENT_VERSION, version(db), "应升级到最新版本");
        assertTrue(tableExists(db, "player_registration_codes"), "3->4 迁移应建出注册码表");
        db.shutdown();
    }
}
