package com.shinoyuki.accesshub.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.database.DatabaseManager;

/**
 * 玩家离线认证数据访问对象 (player_auth 表).
 *
 * 设计取舍 (与既有 AdminUserDao 的"吞异常返回兜底值"风格不同):
 * 本 DAO 在 SQLException 时直接向上抛出, 不静默返回, 以满足全局规范"异常必须痛"。
 * 上层 PlayerAuthService 负责捕获并以 fail-closed (查询失败=未认证) 收口, 绝不放行。
 *
 * username 统一以 toLowerCase(Locale.ROOT) 规范化后作为主键读写, 与 WhitelistManager 一致。
 */
public final class PlayerAuthDao {

    private static final Logger logger = LoggerFactory.getLogger(PlayerAuthDao.class);

    private final DatabaseManager dbManager;

    public PlayerAuthDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /** 按规范化用户名查询. 不存在返回 empty. SQL 失败上抛, 由上层 fail-closed 处理. */
    public Optional<PlayerAuthRecord> findByUsername(String username) throws SQLException {
        String sql = "SELECT username, password_hash, registered_at, last_login_at, "
                + "last_login_ip, fail_count, locked_until FROM player_auth WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(username));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
                return Optional.empty();
            }
        }
    }

    /** 新建注册记录. 用户名已存在时主键冲突由上层先 findByUsername 规避; 此处冲突直接抛出. */
    public void insert(String username, String passwordHash) throws SQLException {
        String sql = "INSERT INTO player_auth (username, password_hash, registered_at, fail_count) "
                + "VALUES (?, ?, ?, 0)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(username));
            ps.setString(2, passwordHash);
            ps.setString(3, LocalDateTime.now().toString());
            ps.executeUpdate();
        }
    }

    /** 改密. 同时清零失败计数与锁定状态. */
    public boolean updatePasswordHash(String username, String passwordHash) throws SQLException {
        String sql = "UPDATE player_auth SET password_hash = ?, fail_count = 0, locked_until = NULL "
                + "WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setString(2, normalize(username));
            return ps.executeUpdate() > 0;
        }
    }

    /** 登录成功: 记录登录时间/IP, 清零失败计数与锁定. */
    public void recordLoginSuccess(String username, String ip) throws SQLException {
        String sql = "UPDATE player_auth SET last_login_at = ?, last_login_ip = ?, "
                + "fail_count = 0, locked_until = NULL WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, LocalDateTime.now().toString());
            ps.setString(2, ip);
            ps.setString(3, normalize(username));
            ps.executeUpdate();
        }
    }

    /**
     * 登录失败: 失败计数 +1; 若达到 maxAttempts 则写入锁定截止时间.
     * 单条 UPDATE 原子完成, 避免读改写竞态.
     */
    public void recordLoginFailure(String username, int maxAttempts, int lockMinutes) throws SQLException {
        String sql = "UPDATE player_auth SET fail_count = fail_count + 1, "
                + "locked_until = CASE WHEN fail_count + 1 >= ? THEN ? ELSE locked_until END "
                + "WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, maxAttempts);
            ps.setString(2, LocalDateTime.now().plusMinutes(lockMinutes).toString());
            ps.setString(3, normalize(username));
            ps.executeUpdate();
        }
    }

    /** 管理: 删除记录 (强制重新注册 / unregister). 返回是否有行被删除. */
    public boolean delete(String username) throws SQLException {
        String sql = "DELETE FROM player_auth WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(username));
            return ps.executeUpdate() > 0;
        }
    }

    private PlayerAuthRecord map(ResultSet rs) throws SQLException {
        return new PlayerAuthRecord(
                rs.getString("username"),
                rs.getString("password_hash"),
                parseTimestamp(rs.getString("registered_at")),
                parseTimestamp(rs.getString("last_login_at")),
                rs.getString("last_login_ip"),
                rs.getInt("fail_count"),
                parseTimestamp(rs.getString("locked_until"))
        );
    }

    /** 兼容 ISO-8601 与传统 "yyyy-MM-dd HH:mm:ss" 两种时间戳格式 (与 AdminUserDao 一致). */
    private LocalDateTime parseTimestamp(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (DateTimeParseException e2) {
                logger.warn("无法解析 player_auth 时间戳: {}", value);
                return null;
            }
        }
    }
}
