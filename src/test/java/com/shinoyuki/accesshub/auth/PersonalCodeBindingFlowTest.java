package com.shinoyuki.accesshub.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shinoyuki.accesshub.database.DatabaseManager;

/**
 * 个人识别码签发与 QQ 绑定的完整链路 (走真 SQLite)。
 *
 * 覆盖三条必须成立的业务不变量:
 *  1. 一人一码 —— 重签立即作废旧码, 且表里不堆行;
 *  2. 码只认人不认别的 —— 用甲的码只能认领到甲, 错码谁也认领不到;
 *  3. 一 QQ 一主 —— 同人重复绑定幂等, 他人绑定必须冲突而不是覆盖。
 *
 * 删掉 issue 的 INSERT OR REPLACE (改成纯 INSERT)、bind 的 INSERT OR IGNORE 冲突分支、
 * 或 findByQq 的 is_active 连表, 对应用例都会挂。
 */
class PersonalCodeBindingFlowTest {

    @TempDir
    File tempDir;

    private DatabaseManager db;
    private PersonalCodeManager codes;
    private QqBindingDao bindings;

    private long adminAlice;
    private long adminBob;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseManager(tempDir);
        assertTrue(db.initialize().get(), "测试库初始化应成功");
        codes = new PersonalCodeManager(db);
        bindings = new QqBindingDao(db);
        adminAlice = insertAdmin("alice", "爱丽丝", true);
        adminBob = insertAdmin("bob", null, true);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    private long insertAdmin(String username, String displayName, boolean active) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO admin_users (username, password_hash, display_name, is_active) VALUES (?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, "not-a-real-hash");
            ps.setString(3, displayName);
            ps.setBoolean(4, active);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next(), "应拿到自增主键");
                return keys.getLong(1);
            }
        }
    }

    private void deactivateAdmin(long adminId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE admin_users SET is_active = 0 WHERE id = ?")) {
            ps.setLong(1, adminId);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private int codeRowCount(long adminId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM admin_personal_codes WHERE admin_id = ?")) {
            ps.setLong(1, adminId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void 签发的码为64位base64url且能反查到本人() throws Exception {
        String code = codes.issue(adminAlice).get();

        assertEquals(64, code.length(), "识别码应为 64 字符");
        assertTrue(code.matches("^[A-Za-z0-9_-]{64}$"), "识别码字符集应为 base64url: " + code);
        assertTrue(PersonalCodeManager.isWellFormed(code));
        assertEquals(adminAlice, codes.resolveAdminId(code).get(), "应能用码反查到签发人");
    }

    @Test
    void 重复签发使旧码立即失效且不堆行() throws Exception {
        String first = codes.issue(adminAlice).get();
        String second = codes.issue(adminAlice).get();

        assertNotEquals(first, second, "两次签发不应产生相同的码");
        assertNull(codes.resolveAdminId(first).get(), "旧码必须立即失效");
        assertEquals(adminAlice, codes.resolveAdminId(second).get(), "新码应生效");
        assertEquals(1, codeRowCount(adminAlice), "一人一码, 重签应覆盖而非追加");
    }

    @Test
    void 不同管理员的码互不串号() throws Exception {
        String aliceCode = codes.issue(adminAlice).get();
        String bobCode = codes.issue(adminBob).get();

        assertEquals(adminAlice, codes.resolveAdminId(aliceCode).get());
        assertEquals(adminBob, codes.resolveAdminId(bobCode).get());
    }

    @Test
    void 无效码一律反查不到() throws Exception {
        codes.issue(adminAlice).get();

        assertNull(codes.resolveAdminId("x".repeat(64)).get(), "长度对但内容错的码不应命中");
        assertNull(codes.resolveAdminId("tooshort").get(), "长度不足应直接判负");
        assertNull(codes.resolveAdminId(null).get(), "null 应安全判负");
        assertFalse(PersonalCodeManager.isWellFormed("a".repeat(63)));
        assertFalse(PersonalCodeManager.isWellFormed("a".repeat(64) + "!"));
        assertFalse(PersonalCodeManager.isWellFormed("包含中文" + "a".repeat(60)));
    }

    @Test
    void 掩码状态与明文首尾一致且不泄露中段() throws Exception {
        assertNull(codes.getStatus(adminAlice).get(), "从未签发时应无状态");

        String code = codes.issue(adminAlice).get();
        PersonalCodeManager.PersonalCodeStatus status = codes.getStatus(adminAlice).get();

        assertEquals(code.substring(0, 8), status.prefix(), "掩码前缀应为明文前 8 位");
        assertEquals(code.substring(60), status.suffix(), "掩码后缀应为明文后 4 位");
        assertTrue(status.issuedAt() != null, "应记录签发时间");
    }

    @Test
    void 首次绑定成功并带出管理员身份() throws Exception {
        QqBindingDao.BindOutcome outcome = bindings.bind(adminAlice, "10001").get();

        assertEquals(QqBindingDao.BindStatus.BOUND, outcome.status());
        assertEquals(adminAlice, outcome.binding().adminId());
        assertEquals("alice", outcome.binding().adminUsername());
        assertEquals("爱丽丝", outcome.binding().displayLabel(), "有昵称时展示昵称");
        assertTrue(outcome.binding().adminActive());
    }

    @Test
    void 没设昵称时展示名回退到登录名() throws Exception {
        bindings.bind(adminBob, "20002").get();
        assertEquals("bob", bindings.findByQq("20002").get().displayLabel());
    }

    @Test
    void 同一人重复绑定幂等不新增行() throws Exception {
        bindings.bind(adminAlice, "10001").get();
        QqBindingDao.BindOutcome again = bindings.bind(adminAlice, "10001").get();

        assertEquals(QqBindingDao.BindStatus.ALREADY_BOUND, again.status());
        assertEquals(adminAlice, again.binding().adminId());
        assertEquals(List.of("10001"), bindings.listQqByAdmin(adminAlice).get(), "不应出现重复行");
    }

    @Test
    void 他人已占用的QQ不得被抢绑() throws Exception {
        bindings.bind(adminAlice, "10001").get();
        QqBindingDao.BindOutcome stolen = bindings.bind(adminBob, "10001").get();

        assertEquals(QqBindingDao.BindStatus.CONFLICT, stolen.status());
        assertEquals(adminAlice, stolen.binding().adminId(), "冲突时应返回原占用者");
        assertEquals(adminAlice, bindings.findByQq("10001").get().adminId(), "原绑定不得被覆盖");
        assertTrue(bindings.listQqByAdmin(adminBob).get().isEmpty(), "抢绑失败方名下不应留痕");
    }

    @Test
    void 一个管理员可绑多个QQ且按绑定先后返回() throws Exception {
        bindings.bind(adminAlice, "10001").get();
        bindings.bind(adminAlice, "10002").get();
        bindings.bind(adminAlice, "10003").get();

        assertEquals(List.of("10001", "10002", "10003"), bindings.listQqByAdmin(adminAlice).get());
    }

    @Test
    void 解绑后查不到且可重新绑给他人() throws Exception {
        bindings.bind(adminAlice, "10001").get();

        assertTrue(bindings.unbind("10001").get(), "已存在的绑定应被删除");
        assertNull(bindings.findByQq("10001").get(), "解绑后不应再查到");
        assertFalse(bindings.unbind("10001").get(), "重复解绑应返回 false");

        QqBindingDao.BindOutcome rebound = bindings.bind(adminBob, "10001").get();
        assertEquals(QqBindingDao.BindStatus.BOUND, rebound.status(), "解绑后该 QQ 应可被他人认领");
    }

    @Test
    void 管理员被停用后绑定仍在但状态标记为不可用() throws Exception {
        bindings.bind(adminAlice, "10001").get();
        deactivateAdmin(adminAlice);

        QqBindingDao.QqBinding binding = bindings.findByQq("10001").get();
        assertFalse(binding.adminActive(), "停用账号必须连同其 QQ 通道一起失效");
        assertEquals(adminAlice, binding.adminId(), "绑定记录本身应保留, 供恢复账号后继续使用");
    }

    @Test
    void 未绑定的QQ查不到任何东西() throws Exception {
        assertNull(bindings.findByQq("99999").get());
        assertTrue(bindings.listQqByAdmin(adminAlice).get().isEmpty());
    }
}
