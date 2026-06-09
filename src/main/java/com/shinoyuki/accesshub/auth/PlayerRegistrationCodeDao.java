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
 * 玩家注册码数据访问对象 (player_registration_codes 表).
 *
 * 与管理员 Web 注册令牌 (RegistrationTokenManager / registration_tokens) 是不同业务:
 * 本表的码"绑定到具体玩家用户名" (bound_username), 是离线模式下堵死冒名抢注的关键 ——
 * 只有持有管理员为某用户名签发的码, 才能注册该用户名。
 *
 * 仅存储码的 SHA-256 哈希 (code_hash), 不落明文。username 统一 toLowerCase(Locale.ROOT)
 * 规范化, 与 PlayerAuthDao / 白名单按名查询保持一致。时间戳以固定格式存储 (yyyy-MM-dd HH:mm:ss)
 * 以保证 deleteExpired 的字面量比较可靠。SQLException 一律上抛, 由上层 fail-closed。
 */
public final class PlayerRegistrationCodeDao {

    private static final Logger logger = LoggerFactory.getLogger(PlayerRegistrationCodeDao.class);

    /** 固定长度零填充格式: 既能被 parseTimestamp 还原, 又能在 SQL 里做可靠的字面量大小比较。 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DatabaseManager dbManager;

    public PlayerRegistrationCodeDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /** 作废某用户名名下所有未使用的旧码, 保证同一用户名同时只有一个有效注册码 (重签即替换)。 */
    public void invalidateUnusedFor(String username) throws SQLException {
        String sql = "DELETE FROM player_registration_codes WHERE bound_username = ? AND is_used = 0";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(username));
            ps.executeUpdate();
        }
    }

    /** 新建一条注册码记录 (仅存哈希)。 */
    public void insert(String codeHash, String username, LocalDateTime expiresAt) throws SQLException {
        String sql = "INSERT INTO player_registration_codes (code_hash, bound_username, expires_at, is_used, created_at) "
                + "VALUES (?, ?, ?, 0, ?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, codeHash);
            ps.setString(2, normalize(username));
            ps.setString(3, expiresAt.format(TS));
            ps.setString(4, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        }
    }

    /** 按码哈希查询。不存在返回 empty。 */
    public Optional<CodeRecord> findByHash(String codeHash) throws SQLException {
        String sql = "SELECT id, bound_username, expires_at, is_used FROM player_registration_codes WHERE code_hash = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, codeHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new CodeRecord(
                            rs.getLong("id"),
                            rs.getString("bound_username"),
                            parseTimestamp(rs.getString("expires_at")),
                            rs.getBoolean("is_used")));
                }
                return Optional.empty();
            }
        }
    }

    /** 标记码已使用 (一次性消费)。 */
    public void markUsed(long id) throws SQLException {
        String sql = "UPDATE player_registration_codes SET is_used = 1, used_at = ? WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, LocalDateTime.now().format(TS));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** 清理已过期的码。返回删除行数。 (权威过期判定在校验时由 Java 比较完成, 此处仅清库)。 */
    public int deleteExpired() throws SQLException {
        String sql = "DELETE FROM player_registration_codes WHERE expires_at < ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, LocalDateTime.now().format(TS));
            return ps.executeUpdate();
        }
    }

    private LocalDateTime parseTimestamp(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, TS);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException e2) {
                logger.warn("无法解析 player_registration_codes 时间戳: {}", value);
                return null;
            }
        }
    }

    /** 注册码记录快照。 */
    public static final class CodeRecord {
        private final long id;
        private final String boundUsername;
        private final LocalDateTime expiresAt;
        private final boolean used;

        public CodeRecord(long id, String boundUsername, LocalDateTime expiresAt, boolean used) {
            this.id = id;
            this.boundUsername = boundUsername;
            this.expiresAt = expiresAt;
            this.used = used;
        }

        public long getId() { return id; }
        public String getBoundUsername() { return boundUsername; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public boolean isUsed() { return used; }
    }
}
