package com.shinoyuki.accesshub.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import com.shinoyuki.accesshub.database.DatabaseManager;

/**
 * 管理员个人识别码管理器。
 *
 * 识别码是管理员在外部渠道 (当前是 QQ Bot 私聊) 自证身份的长期凭据: 管理员在面板签发一次,
 * 私聊发给 Bot 完成绑定, 之后该 QQ 发出的运维命令即以该管理员身份执行。
 *
 * 与 {@link RegistrationTokenManager} 的分工: 那个是一次性的、给"还不存在的管理员"用来注册账号;
 * 本类的码长期有效、归属"已存在的管理员", 且可随时重签使旧码作废。
 *
 * 存储只落 SHA-256 哈希与前后缀掩码, 明文仅在签发时返回一次。库被拖走也拿不到可用的码。
 */
public final class PersonalCodeManager {

    /** 明文码长度。48 字节 base64url 无 padding 恰好编码成 64 字符, 不需要截断。 */
    public static final int CODE_LENGTH = 64;

    private static final int CODE_RANDOM_BYTES = 48;
    private static final int PREFIX_LENGTH = 8;
    private static final int SUFFIX_LENGTH = 4;

    /** 供调用方在打数据库前先挡掉明显不合法的输入 (base64url 字符集)。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{" + CODE_LENGTH + "}$");

    private final DatabaseManager databaseManager;
    private final SecureRandom secureRandom = new SecureRandom();

    public PersonalCodeManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public static boolean isWellFormed(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }

    /**
     * 为指定管理员签发个人识别码, 返回明文。重复调用即重置: 旧码立即失效, 已有的 QQ 绑定不受影响
     * (绑定一旦建立就是既成事实, 重置码是为了让泄露的码失效, 不是为了断开已认领的 QQ)。
     */
    public CompletableFuture<String> issue(long adminId) {
        String code = generateCode();
        String codeHash = hash(code);
        String prefix = code.substring(0, PREFIX_LENGTH);
        String suffix = code.substring(CODE_LENGTH - SUFFIX_LENGTH);
        LocalDateTime issuedAt = LocalDateTime.now();

        return databaseManager.executeTransactionAsync(connection -> {
            String sql = """
                INSERT OR REPLACE INTO admin_personal_codes
                    (admin_id, code_hash, code_prefix, code_suffix, issued_at)
                VALUES (?, ?, ?, ?, ?)
            """;
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setLong(1, adminId);
                stmt.setString(2, codeHash);
                stmt.setString(3, prefix);
                stmt.setString(4, suffix);
                stmt.setTimestamp(5, Timestamp.valueOf(issuedAt));
                stmt.executeUpdate();
            }
            return code;
        });
    }

    /** 查询某管理员当前识别码的掩码信息; 从未签发过返回 null。 */
    public CompletableFuture<PersonalCodeStatus> getStatus(long adminId) {
        return databaseManager.executeAsync(connection -> {
            String sql = """
                SELECT code_prefix, code_suffix, issued_at
                FROM admin_personal_codes
                WHERE admin_id = ?
            """;
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setLong(1, adminId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Timestamp issuedAt = rs.getTimestamp("issued_at");
                    return new PersonalCodeStatus(
                            rs.getString("code_prefix"),
                            rs.getString("code_suffix"),
                            issuedAt == null ? null : issuedAt.toLocalDateTime()
                    );
                }
            }
        });
    }

    /** 用明文码反查管理员 id; 码不存在返回 null。 */
    public CompletableFuture<Long> resolveAdminId(String code) {
        if (!isWellFormed(code)) {
            return CompletableFuture.completedFuture(null);
        }
        String codeHash = hash(code);
        return databaseManager.executeAsync(connection -> {
            String sql = "SELECT admin_id FROM admin_personal_codes WHERE code_hash = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, codeHash);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getLong("admin_id") : null;
                }
            }
        });
    }

    private String generateCode() {
        byte[] randomBytes = new byte[CODE_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String hash(String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法, 走到这里说明运行环境本身已损坏, 没有降级的意义
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }

    /** 面板展示用的识别码状态: 只有掩码前后缀与签发时间, 不含任何可还原明文的信息。 */
    public record PersonalCodeStatus(String prefix, String suffix, LocalDateTime issuedAt) {
    }
}
