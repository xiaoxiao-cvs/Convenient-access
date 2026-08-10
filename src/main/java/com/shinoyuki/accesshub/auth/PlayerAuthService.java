package com.shinoyuki.accesshub.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.auth.PlayerRegistrationCodeDao.CodeRecord;
import com.shinoyuki.accesshub.config.AccessHubConfig;

import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * 玩家离线认证服务.
 *
 * 职责:
 *  - 注册: bcrypt 哈希入库 (cost=12)
 *  - 登录校验: bcrypt 校验 + 失败计数 + 锁定 (lock-minutes), 成功后写入内存已认证集合
 *  - 改密
 *  - 已认证会话管理: 内存 Set&lt;UUID&gt;, 玩家退出/认证服务复位时清除
 *
 * fail-closed 铁律: 任何数据库异常 (SQLException) 一律视为"未认证/拒绝",
 * 绝不因查询失败而把玩家放进已认证集合。bcrypt 校验过程中的异常同样判失败。
 *
 * 线程模型: register/verify/changePassword 在命令异步线程调用 (DatabaseManager 线程池外的
 * 命令处理); authedSessions 用 ConcurrentHashMap 支撑的 Set 保证跨线程可见与原子增删。
 */
public final class PlayerAuthService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerAuthService.class);

    /** bcrypt cost 因子. 12 在现代 CPU 上约 200-300ms/次, 兼顾安全与登录体验. */
    private static final int BCRYPT_COST = 12;

    /** 注册码字符集: 去除易混淆字符 (0/O/1/I/L), 便于人工口述与输入。 */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    /** 注册码长度 (展示为 XXXX-XXXX). 31^8 ≈ 8.5e11, 配合绑定用户名+一次性+过期, 在线爆破无意义。 */
    private static final int CODE_LENGTH = 8;

    /** 常见弱口令黑名单 (小写比较). 仅拦最典型的几个, 与"纯数字/同用户名"规则共同生效。 */
    private static final Set<String> COMMON_WEAK = Set.of(
            "password", "12345678", "123456789", "1234567890", "qwertyui",
            "11111111", "00000000", "passw0rd", "iloveyou", "abcd1234");

    private final PlayerAuthDao dao;
    private final PlayerRegistrationCodeDao codeDao;
    private final AccessHubConfig config;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 已通过 /login 的玩家 UUID. 仅这些玩家能对世界产生影响; 不在集合内即视为未认证。 */
    private final Set<UUID> authedSessions = ConcurrentHashMap.newKeySet();

    /**
     * 会话级登录失败计数 (按 UUID). 仅统计"密码错误"; 达 max-attempts 由命令层踢下线。
     * 重连即新会话, clearSession 清零 -> 不持久锁号, 不误伤合法玩家 (按 IP 锁因动态公网作废)。
     */
    private final Map<UUID, Integer> sessionFailures = new ConcurrentHashMap<>();

    public PlayerAuthService(PlayerAuthDao dao, PlayerRegistrationCodeDao codeDao, AccessHubConfig config) {
        this.dao = dao;
        this.codeDao = codeDao;
        this.config = config;
    }

    // ==================== 会话状态 ====================

    public boolean isAuthed(UUID uuid) {
        return uuid != null && authedSessions.contains(uuid);
    }

    public void markAuthed(UUID uuid) {
        if (uuid != null) {
            authedSessions.add(uuid);
            sessionFailures.remove(uuid); // 认证成功即清零失败计数
        }
    }

    /** 玩家退出时清理, 防止 UUID 复用 (同一会话踢出再进) 残留已认证状态。 */
    public void clearSession(UUID uuid) {
        if (uuid != null) {
            authedSessions.remove(uuid);
            sessionFailures.remove(uuid); // 重连重置: 退服/被踢即清失败计数
        }
    }

    /**
     * 记录一次"密码错误"的会话失败并返回累计次数. 仅供命令层据此判断是否踢下线。
     * 退服/认证成功由 clearSession/markAuthed 清零, 重连即从 0 起算。
     */
    public int recordSessionFailure(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        return sessionFailures.merge(uuid, 1, Integer::sum);
    }

    /** 只读当前会话失败次数. 供命令层在派发 verify 前同步短路, 避免并发在途登录突破上限。 */
    public int getSessionFailureCount(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        return sessionFailures.getOrDefault(uuid, 0);
    }

    // ==================== 注册 / 登录 / 改密 ====================

    /** 是否已注册. 查询失败按"未知"上抛由调用方决定, 但本方法仅供命令层做存在性提示。 */
    public boolean isRegistered(String username) {
        try {
            return dao.findByUsername(username).isPresent();
        } catch (SQLException e) {
            // fail-closed: 查询失败不能伪称"未注册"诱导覆盖, 也不能伪称"已注册"。
            // 抛回让命令层提示"系统繁忙", 绝不静默放行。
            throw new IllegalStateException("查询注册状态失败", e);
        }
    }

    /**
     * 注册. 查重、入库在同一 try 内 fail-closed: 任何 SQLException 一律视为失败, 绝不入库。
     * 玩家名主键唯一性兜底并发双注册。
     *
     * 注册码校验已临时停用 (2026-08-10): 玩家普遍看不懂"先领码、再带码注册"的流程, 改为白名单放行即可自助设密。
     * 停用期间冒名抢注只剩白名单兜底 (只有过审玩家名能进服), 抢注窗口 = 加白到该玩家首次注册之间。
     * 恢复方式: 取消下方两处 "注册码校验 (临时停用)" 注释块, 并同步恢复 AuthCommand 的两参数 /register 节点、
     * PlayerAuthListener 进服提示与 PlayerRegistrationCodeFlowTest 的 @Disabled。
     * code 形参保留: 老玩家/旧文案仍可能带码调用, 停用期间该值不参与任何判定。
     */
    public AuthResult register(String username, String password, String code) {
        AuthResult policy = checkPasswordPolicy(password, username, "密码");
        if (policy != null) {
            return policy;
        }
        // ===== 注册码校验 (临时停用, 恢复时整块取消注释) =====
        // String canonical = canonicalizeCode(code);
        // if (canonical.isEmpty()) {
        //     return AuthResult.failure("请提供注册码 (向管理员索取): /register <密码> <确认密码> <注册码>");
        // }
        try {
            if (dao.findByUsername(username).isPresent()) {
                return AuthResult.failure("该账号已注册, 请使用 /login 登录");
            }
            // ===== 注册码校验 + 消费码 (临时停用, 恢复时整块取消注释) =====
            // CodeCheck cc = checkCode(username, canonical);
            // if (!cc.isValid()) {
            //     return AuthResult.failure(cc.getMessage());
            // }
            String hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
            dao.insert(username, hash);
            // 账号写入即注册成功。消费码与建号非同一事务, 故 markUsed 单独兜底:
            // 失败不回滚、不回报"注册失败" (账号已建, 玩家可直接 /login), 仅告警人工核对。
            // 残留 is_used=0 的码无法被再利用: 它绑定该用户名, 而该名已被占用, register 查重会拦下。
            // try {
            //     codeDao.markUsed(cc.getCodeId());
            // } catch (SQLException e) {
            //     logger.error("账号已建但注册码未能标记已用, 需人工核对: username={} codeId={}", username, cc.getCodeId(), e);
            // }
            logger.info("玩家完成注册: {} (注册码校验已临时停用)", username);
            return AuthResult.success("注册成功, 请使用 /login 登录");
        } catch (SQLException e) {
            // fail-closed: 入库/校验失败不视为注册成功
            logger.error("玩家注册数据库异常: {}", username, e);
            return AuthResult.failure("注册失败 (系统繁忙), 请稍后重试");
        }
    }

    /**
     * 为指定用户名生成一次性、绑定名字、会过期的注册码 (加白时 / OP 命令调用).
     * 同时作废该名下旧的未用码, 保证同名同时只有一个有效码。返回展示用明文码 (XXXX-XXXX);
     * 生成失败 (DB 异常) 返回 null, 由调用方提示。明文码不入库, 仅存其 SHA-256 哈希。
     */
    public String generateRegistrationCode(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(secureRandom.nextInt(CODE_ALPHABET.length())));
        }
        String canonical = sb.toString();
        try {
            codeDao.invalidateUnusedFor(username);
            codeDao.insert(sha256Base64(canonical), username,
                    LocalDateTime.now().plusMinutes(config.getPlayerAuthCodeExpiryMinutes()));
        } catch (SQLException e) {
            logger.error("生成玩家注册码数据库异常: {}", username, e);
            return null;
        }
        // 展示为 XXXX-XXXX, 易读易口述; 校验时会去除分隔符并大写还原为 canonical
        return canonical.substring(0, 4) + "-" + canonical.substring(4);
    }

    /**
     * 校验注册码 (入参已 canonicalize): 绑名 + 一次性 + 过期, 不消费。
     * 供 register 与 /enroll 共用同一口径。必须在 try/SQLException fail-closed 上下文调用。
     */
    private CodeCheck checkCode(String username, String canonical) throws SQLException {
        Optional<CodeRecord> opt = codeDao.findByHash(sha256Base64(canonical));
        if (opt.isEmpty()) {
            return CodeCheck.fail("注册码无效");
        }
        CodeRecord cr = opt.get();
        if (cr.isUsed()) {
            return CodeCheck.fail("注册码已被使用, 请向管理员重新索取");
        }
        if (cr.getExpiresAt() == null || cr.getExpiresAt().isBefore(LocalDateTime.now())) {
            return CodeCheck.fail("注册码已过期, 请向管理员重新索取");
        }
        // 关键: 码绑定的用户名必须与本玩家名一致 -> 离线模式下别人的码用不到你的名字
        if (!normalize(cr.getBoundUsername()).equals(normalize(username))) {
            return CodeCheck.fail("该注册码不属于你的用户名");
        }
        return CodeCheck.ok(cr.getId());
    }

    /** 公开: 为 /enroll 校验注册码 (自带 canonicalize + fail-closed)。不消费。 */
    public CodeCheck validateRegistrationCode(String username, String code) {
        String canonical = canonicalizeCode(code);
        if (canonical.isEmpty()) {
            return CodeCheck.fail("请提供注册码");
        }
        try {
            return checkCode(username, canonical);
        } catch (SQLException e) {
            logger.error("校验注册码数据库异常: {}", username, e);
            return CodeCheck.fail("校验失败 (系统繁忙), 请稍后重试");
        }
    }

    /**
     * 为 /enroll &lt;码&gt; 校验: 账号必须已注册 (密码 = 换机/重装后的恢复锚, 不允许纯码登记跳过密码) + 码有效。
     * 已登录会话登记走 isAuthed 直通, 不经此方法。
     */
    public CodeCheck validateEnrollWithCode(String username, String code) {
        try {
            if (dao.findByUsername(username).isEmpty()) {
                return CodeCheck.fail("请先 /register <密码> <确认密码> 设置密码, 再 /enroll 登记设备 (密码是换机后的恢复手段)");
            }
        } catch (SQLException e) {
            logger.error("enroll 查注册状态异常: {}", username, e);
            return CodeCheck.fail("校验失败 (系统繁忙), 请稍后重试");
        }
        return validateRegistrationCode(username, code);
    }

    /** 消费注册码 (enroll 成功后). 失败仅告警, 不回滚 (码绑该名, 该名已绑设备, 残码无法复用)。 */
    public void consumeRegistrationCode(long codeId) {
        try {
            codeDao.markUsed(codeId);
        } catch (SQLException e) {
            logger.error("消费注册码异常 id={}", codeId, e);
        }
    }

    /** 注册码校验结果: 成功带 codeId 供调用方消费, 失败带可直接展示的消息。 */
    public static final class CodeCheck {
        private final boolean valid;
        private final String message;
        private final long codeId;

        private CodeCheck(boolean valid, String message, long codeId) {
            this.valid = valid;
            this.message = message;
            this.codeId = codeId;
        }

        static CodeCheck ok(long codeId) { return new CodeCheck(true, null, codeId); }
        static CodeCheck fail(String message) { return new CodeCheck(false, message, -1L); }

        public boolean isValid() { return valid; }
        public String getMessage() { return message; }
        public long getCodeId() { return codeId; }
    }

    /**
     * 登录校验. 成功返回 success; 失败/锁定/未注册返回 failure 且 message 已可直接展示。
     * 注意: 成功只表示密码正确, 标记已认证由调用方在主线程 markAuthed, 避免竞态。
     */
    public AuthResult verify(String username, String password, String ip) {
        final PlayerAuthRecord record;
        try {
            Optional<PlayerAuthRecord> opt = dao.findByUsername(username);
            if (opt.isEmpty()) {
                return AuthResult.failure("该账号尚未注册, 请使用 /register 注册");
            }
            record = opt.get();
        } catch (SQLException e) {
            // fail-closed: 查不到记录就拒绝, 绝不放行
            logger.error("玩家登录查询数据库异常: {}", username, e);
            return AuthResult.failure("登录失败 (系统繁忙), 请稍后重试");
        }

        boolean ok;
        try {
            BCrypt.Result r = BCrypt.verifyer().verify(password.toCharArray(), record.getPasswordHash());
            ok = r.verified;
        } catch (RuntimeException e) {
            // 哈希串损坏等: 视为校验失败, 不放行
            logger.error("玩家登录 bcrypt 校验异常: {}", username, e);
            ok = false;
        }

        if (!ok) {
            try {
                dao.recordLoginFailure(username); // 仅累计审计计数, 不持久锁号
            } catch (SQLException e) {
                logger.warn("记录登录失败计数异常: {}", username, e);
            }
            // 失败仍写日志 (供管理员发现爆破): 会话级踢出判定由命令层据 recordSessionFailure 做
            logger.warn("玩家登录密码错误: {} (IP: {})", username, ip);
            return AuthResult.passwordError("密码错误");
        }

        try {
            dao.recordLoginSuccess(username, ip);
        } catch (SQLException e) {
            // 登录成功但写入登录时间失败: 不影响认证结论, 仅记日志
            logger.warn("记录登录成功信息异常: {}", username, e);
        }
        logger.info("玩家登录成功: {} (IP: {})", username, ip);
        return AuthResult.success("登录成功, 欢迎回来");
    }

    public AuthResult changePassword(String username, String oldPassword, String newPassword) {
        AuthResult policy = checkPasswordPolicy(newPassword, username, "新密码");
        if (policy != null) {
            return policy;
        }
        final PlayerAuthRecord record;
        try {
            Optional<PlayerAuthRecord> opt = dao.findByUsername(username);
            if (opt.isEmpty()) {
                return AuthResult.failure("该账号尚未注册");
            }
            record = opt.get();
        } catch (SQLException e) {
            logger.error("改密查询数据库异常: {}", username, e);
            return AuthResult.failure("改密失败 (系统繁忙), 请稍后重试");
        }

        boolean oldOk;
        try {
            oldOk = BCrypt.verifyer().verify(oldPassword.toCharArray(), record.getPasswordHash()).verified;
        } catch (RuntimeException e) {
            logger.error("改密旧密码 bcrypt 校验异常: {}", username, e);
            oldOk = false;
        }
        if (!oldOk) {
            return AuthResult.failure("旧密码错误");
        }

        try {
            String hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, newPassword.toCharArray());
            dao.updatePasswordHash(username, hash);
            logger.info("玩家修改密码成功: {}", username);
            return AuthResult.success("密码已修改");
        } catch (SQLException e) {
            logger.error("改密入库异常: {}", username, e);
            return AuthResult.failure("改密失败 (系统繁忙), 请稍后重试");
        }
    }

    // ==================== 管理操作 ====================

    /** 清除密码并强制重新注册 (删除记录). 同时踢出已认证内存态由调用方按需处理。 */
    public boolean adminReset(String username) {
        try {
            return dao.delete(username);
        } catch (SQLException e) {
            logger.error("管理重置玩家认证异常: {}", username, e);
            throw new IllegalStateException("重置失败", e);
        }
    }

    public Optional<PlayerAuthRecord> adminInfo(String username) {
        try {
            return dao.findByUsername(username);
        } catch (SQLException e) {
            logger.error("管理查询玩家认证异常: {}", username, e);
            throw new IllegalStateException("查询失败", e);
        }
    }

    // ==================== 辅助 ====================

    /** 用户名规范化, 与 PlayerAuthDao / 注册码绑定名统一口径 (Locale.ROOT, 避免土耳其 I/i 问题)。 */
    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /** 密码策略校验: 最低位数 + (可选) 弱口令拦截。通过返回 null, 不通过返回可直接展示的 failure。 */
    private AuthResult checkPasswordPolicy(String password, String username, String label) {
        int min = config.getPlayerAuthMinPasswordLength();
        if (password == null || password.length() < min) {
            return AuthResult.failure(label + "至少 " + min + " 位");
        }
        if (config.isPlayerAuthRejectWeakPassword() && isWeakPassword(password, username)) {
            return AuthResult.failure(label + "过于简单 (不能是纯数字 / 与用户名相同 / 常见弱口令), 请换一个");
        }
        return null;
    }

    private static boolean isWeakPassword(String password, String username) {
        if (password.chars().allMatch(Character::isDigit)) {
            return true;
        }
        if (username != null && password.equalsIgnoreCase(username.trim())) {
            return true;
        }
        return COMMON_WEAK.contains(password.toLowerCase(Locale.ROOT));
    }

    /** 注册码规范化: 去除分隔符/空白并大写, 与生成时的 canonical 一致, 容忍玩家输入 XXXX-XXXX 或小写。 */
    private static String canonicalizeCode(String code) {
        if (code == null) {
            return "";
        }
        return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static String sha256Base64(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 标配; 真缺失则抛出阻断 (fail-closed), 绝不降级放行
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 命令层结果对象: 是否成功 + 可直接展示给玩家的消息 + 是否为"密码错误"(供会话失败计数)。 */
    public static final class AuthResult {
        private final boolean success;
        private final String message;
        private final boolean passwordMismatch;

        private AuthResult(boolean success, String message, boolean passwordMismatch) {
            this.success = success;
            this.message = message;
            this.passwordMismatch = passwordMismatch;
        }

        public static AuthResult success(String message) { return new AuthResult(true, message, false); }
        public static AuthResult failure(String message) { return new AuthResult(false, message, false); }
        /** 仅"密码错误"用此工厂: 命令层据 isPasswordMismatch 决定是否累计会话失败并踢下线。 */
        public static AuthResult passwordError(String message) { return new AuthResult(false, message, true); }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public boolean isPasswordMismatch() { return passwordMismatch; }
    }
}
