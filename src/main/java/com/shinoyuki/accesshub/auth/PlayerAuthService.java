package com.shinoyuki.accesshub.auth;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final PlayerAuthDao dao;
    private final AccessHubConfig config;

    /** 已通过 /login 的玩家 UUID. 仅这些玩家能对世界产生影响; 不在集合内即视为未认证。 */
    private final Set<UUID> authedSessions = ConcurrentHashMap.newKeySet();

    public PlayerAuthService(PlayerAuthDao dao, AccessHubConfig config) {
        this.dao = dao;
        this.config = config;
    }

    // ==================== 会话状态 ====================

    public boolean isAuthed(UUID uuid) {
        return uuid != null && authedSessions.contains(uuid);
    }

    public void markAuthed(UUID uuid) {
        if (uuid != null) {
            authedSessions.add(uuid);
        }
    }

    /** 玩家退出时清理, 防止 UUID 复用 (同一会话踢出再进) 残留已认证状态。 */
    public void clearSession(UUID uuid) {
        if (uuid != null) {
            authedSessions.remove(uuid);
        }
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

    public AuthResult register(String username, String password) {
        if (password == null || password.length() < 4) {
            return AuthResult.failure("密码至少 4 位");
        }
        try {
            if (dao.findByUsername(username).isPresent()) {
                return AuthResult.failure("该账号已注册, 请使用 /login 登录");
            }
            String hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
            dao.insert(username, hash);
            logger.info("玩家完成注册: {}", username);
            return AuthResult.success("注册成功, 请使用 /login 登录");
        } catch (SQLException e) {
            // fail-closed: 入库失败不视为注册成功
            logger.error("玩家注册数据库异常: {}", username, e);
            return AuthResult.failure("注册失败 (系统繁忙), 请稍后重试");
        }
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

        // 锁定检查
        LocalDateTime lockedUntil = record.getLockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now())) {
            long remainingMinutes = java.time.Duration.between(LocalDateTime.now(), lockedUntil).toMinutes() + 1;
            return AuthResult.failure("账号已锁定, 请在约 " + remainingMinutes + " 分钟后重试");
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
                dao.recordLoginFailure(username, config.getPlayerAuthMaxAttempts(), config.getPlayerAuthLockMinutes());
            } catch (SQLException e) {
                logger.warn("记录登录失败计数异常: {}", username, e);
            }
            return AuthResult.failure("密码错误");
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
        if (newPassword == null || newPassword.length() < 4) {
            return AuthResult.failure("新密码至少 4 位");
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

    /** 命令层结果对象: 是否成功 + 可直接展示给玩家的消息。 */
    public static final class AuthResult {
        private final boolean success;
        private final String message;

        private AuthResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static AuthResult success(String message) { return new AuthResult(true, message); }
        public static AuthResult failure(String message) { return new AuthResult(false, message); }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }
}
