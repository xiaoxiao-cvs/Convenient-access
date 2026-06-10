package com.shinoyuki.accesshub.deviceauth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import com.shinoyuki.accesshub.database.DatabaseManager;

/**
 * 设备公钥 DAO (device_keys 表). 服务端用, 只读写公钥, 永不接触私钥。
 *
 * 默认单设备: username 主键, upsert 即覆盖 (换机 /enroll 使旧机失效)。
 * username 统一 toLowerCase(Locale.ROOT), 与 PlayerAuthDao / 注册码 / 白名单一致。
 * SQLException 一律上抛, 由上层 fail-closed (验签查不到公钥即拒绝, 回退密码)。
 */
public final class DeviceKeyDao {

    private final DatabaseManager dbManager;

    public DeviceKeyDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /** 登记/覆盖某用户名的设备公钥 (单设备语义)。 */
    public void upsert(String username, String publicKeyBase64) throws SQLException {
        String sql = "INSERT OR REPLACE INTO device_keys (username, public_key, algorithm, created_at) "
                + "VALUES (?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(username));
            ps.setString(2, publicKeyBase64);
            ps.setString(3, DeviceCrypto.ALGORITHM);
            ps.setString(4, LocalDateTime.now().toString());
            ps.executeUpdate();
        }
    }

    /** 查某用户名的设备公钥 (X.509 Base64). 不存在返回 empty。 */
    public Optional<String> findPublicKey(String username) throws SQLException {
        String sql = "SELECT public_key FROM device_keys WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(username));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("public_key"));
                }
                return Optional.empty();
            }
        }
    }

    public void touchLastUsed(String username) throws SQLException {
        String sql = "UPDATE device_keys SET last_used_at = ? WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, LocalDateTime.now().toString());
            ps.setString(2, normalize(username));
            ps.executeUpdate();
        }
    }

    /** 吊销/重置某用户名的设备绑定. 返回是否有行被删。 */
    public boolean delete(String username) throws SQLException {
        String sql = "DELETE FROM device_keys WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(username));
            return ps.executeUpdate() > 0;
        }
    }
}
