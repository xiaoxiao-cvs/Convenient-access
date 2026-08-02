package com.shinoyuki.accesshub.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shinoyuki.accesshub.database.DatabaseManager;

/**
 * 操作日志的可写类型与按类型聚合 (走真 SQLite)。
 *
 * 复现两个互相掩盖的历史缺陷:
 *  1) operation_log 的 CHECK 约束只列了 ADD/REMOVE/QUERY/BATCH_ADD/BATCH_REMOVE/SYNC/
 *     UNAUTHORIZED_ACCESS, 而代码实际还写 SET_ACTIVE 与 GENCODE -> 这两类 INSERT 被数据库
 *     拒收, DAO 把 SQLException 吞成 false, 调用方又不看返回值, 于是整类审计日志静默丢失
 *     (生产库实测这两种各 0 条);
 *  2) 统计端点硬编码类型清单 {ADD,REMOVE,BATCH_ADD,BATCH_REMOVE,UPDATE}, 与实际写入的类型
 *     对不上 -> 三项恒为 0, SET_ACTIVE/GENCODE 连分项都进不去。
 *
 * 断言修复后: 两种类型能写入, 且聚合结果只反映库中真实存在的类型。
 * 若回退迁移(CHECK 不含新类型) 或把聚合换回硬编码清单, 本类用例必挂。
 */
class OperationLogStatsTest {

    @TempDir
    File tempDir;

    private DatabaseManager db;
    private OperationLogDao dao;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseManager(tempDir);
        assertTrue(db.initialize().get(), "测试库初始化应成功");
        dao = new OperationLogDao(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    private void log(String type) {
        assertTrue(
                dao.logOperation(type, null, "tester", "127.0.0.1", "junit", null, 200, 1L),
                "该操作类型应能写入 operation_log: " + type);
    }

    /** created_at 由数据库默认值填充, 要造指定时刻的历史数据只能直接写 SQL。 */
    private void logAt(String type, LocalDateTime at) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO operation_log (operation_type, response_status, execution_time, created_at)"
                             + " VALUES (?, ?, ?, ?)")) {
            ps.setString(1, type);
            ps.setInt(2, 200);
            ps.setLong(3, 1L);
            ps.setTimestamp(4, Timestamp.valueOf(at));
            assertEquals(1, ps.executeUpdate(), "预置历史日志应写入成功: " + type);
        }
    }

    @Test
    void setActiveAndGencodeAreWritable() {
        // 修复前这两行会因 CHECK 约束失败而返回 false
        log("SET_ACTIVE");
        log("GENCODE");

        Map<String, Long> counts = dao.countByOperationType(null, null);
        assertEquals(1L, counts.get("SET_ACTIVE"), "SET_ACTIVE 应真正落库");
        assertEquals(1L, counts.get("GENCODE"), "GENCODE 应真正落库");
    }

    @Test
    void rejectsTypeOutsideConstraint() {
        // CHECK 仍应拦住未登记的类型, 放宽不等于取消约束
        assertFalse(
                dao.logOperation("NOT_A_REAL_TYPE", null, "tester", "127.0.0.1", "junit", null, 200, 1L),
                "未登记的操作类型应被 CHECK 约束拒收");
    }

    @Test
    void aggregatesOnlyTypesActuallyPresent() {
        log("ADD");
        log("ADD");
        log("ADD");
        log("REMOVE");
        log("SET_ACTIVE");
        log("SET_ACTIVE");
        log("GENCODE");

        Map<String, Long> counts = dao.countByOperationType(null, null);

        assertEquals(3L, counts.get("ADD"), "ADD 应聚合出 3 条");
        assertEquals(1L, counts.get("REMOVE"), "REMOVE 应聚合出 1 条");
        assertEquals(2L, counts.get("SET_ACTIVE"), "SET_ACTIVE 必须进入分项统计");
        assertEquals(1L, counts.get("GENCODE"), "GENCODE 必须进入分项统计");

        // 从未写入的类型不应凭空出现在统计里 (旧实现会给出 BATCH_ADD=0 这类噪音)
        assertFalse(counts.containsKey("BATCH_ADD"), "未发生的类型不应出现");
        assertFalse(counts.containsKey("UPDATE"), "未发生的类型不应出现");
        assertEquals(4, counts.size(), "应恰好聚合出 4 种真实类型");

        long sum = counts.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(7L, sum, "分项之和应等于写入总数");
    }

    @Test
    void respectsTimeRange() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        logAt("ADD", now.minusDays(3));
        logAt("ADD", now.minusHours(1));
        logAt("REMOVE", now.minusHours(1));

        Map<String, Long> recent = dao.countByOperationType(now.minusDays(1), null);
        assertEquals(1L, recent.get("ADD"), "3 天前那条应被起始时间排除");
        assertEquals(1L, recent.get("REMOVE"), "范围内的 REMOVE 应保留");

        Map<String, Long> all = dao.countByOperationType(null, null);
        assertEquals(2L, all.get("ADD"), "不设范围时应统计全部 ADD");

        Map<String, Long> ancient = dao.countByOperationType(null, now.minusDays(2));
        assertEquals(1L, ancient.get("ADD"), "结束时间应把近 1 小时的记录排除");
        assertFalse(ancient.containsKey("REMOVE"), "范围外的类型不应出现");
    }
}
