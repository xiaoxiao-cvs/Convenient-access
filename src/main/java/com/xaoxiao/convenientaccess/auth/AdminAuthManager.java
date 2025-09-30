package com.xaoxiao.convenientaccess.auth;

import com.xaoxiao.convenientaccess.database.DatabaseManager;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员认证管理器
 * 处理管理员登录、会话管理和权限控制
 */
public class AdminAuthManager {
    private static final Logger logger = LoggerFactory.getLogger(AdminAuthManager.class);
    
    private final DatabaseManager databaseManager;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecretKey jwtSecretKey;
    private final Map<String, AdminSession> activeSessions = new ConcurrentHashMap<>();
    
    // 配置常量
    private static final int JWT_EXPIRATION_HOURS = 24;
    private static final int SESSION_TIMEOUT_MINUTES = 30;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_DURATION_MINUTES = 15;
    
    public AdminAuthManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.passwordEncoder = new BCryptPasswordEncoder(12);
        this.jwtSecretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);
    }
    
    /**
     * 初始化管理员认证系统
     */
    public CompletableFuture<Boolean> initialize() {
        return databaseManager.executeAsync(connection -> {
            // 创建默认管理员角色
            createDefaultRoles(connection);
            
            // 创建默认管理员用户
            createDefaultAdmin(connection);
            
            logger.info("管理员认证系统初始化完成");
            return true;
        }).exceptionally(throwable -> {
            logger.error("管理员认证系统初始化失败", throwable);
            return false;
        });
    }
    
    /**
     * 管理员登录
     */
    public CompletableFuture<LoginResult> login(String username, String password, String clientIp, String userAgent) {
        return databaseManager.executeAsync(connection -> {
            // 检查用户是否存在
            AdminUser user = getAdminUser(connection, username);
            if (user == null) {
                logAuthEvent(connection, "LOGIN_FAILED", username, clientIp, userAgent, false, "用户不存在");
                return LoginResult.failure("用户名或密码错误");
            }
            
            // 检查账户是否被锁定
            if (user.isLocked()) {
                logAuthEvent(connection, "LOGIN_BLOCKED", username, clientIp, userAgent, false, "账户被锁定");
                return LoginResult.failure("账户已被锁定，请稍后再试");
            }
            
            // 验证密码
            if (!passwordEncoder.matches(password, user.getPasswordHash())) {
                // 记录失败尝试
                incrementFailedAttempts(connection, user.getId());
                logAuthEvent(connection, "LOGIN_FAILED", username, clientIp, userAgent, false, "密码错误");
                return LoginResult.failure("用户名或密码错误");
            }
            
            // 登录成功，重置失败计数
            resetFailedAttempts(connection, user.getId());
            
            // 创建会话
            AdminSession session = createSession(user, clientIp, userAgent);
            activeSessions.put(session.getSessionId(), session);
            
            // 保存会话到数据库
            saveSession(connection, session);
            
            // 更新用户登录信息
            updateUserLoginInfo(connection, user.getId());
            
            // 记录登录成功
            logAuthEvent(connection, "LOGIN_SUCCESS", username, clientIp, userAgent, true, null);
            
            logger.info("管理员登录成功: {} (IP: {})", username, clientIp);
            return LoginResult.success(session);
            
        }).exceptionally(throwable -> {
            logger.error("管理员登录失败", throwable);
            return LoginResult.failure("登录服务异常");
        });
    }
    
    /**
     * 验证会话
     */
    public CompletableFuture<SessionValidationResult> validateSession(String sessionId, String clientIp) {
        return CompletableFuture.supplyAsync(() -> {
            AdminSession session = activeSessions.get(sessionId);
            
            if (session == null) {
                // 从数据库查询会话
                session = getSessionFromDatabase(sessionId);
                if (session != null && session.isValid()) {
                    activeSessions.put(sessionId, session);
                }
            }
            
            if (session == null) {
                return SessionValidationResult.invalid("会话不存在");
            }
            
            if (!session.isValid()) {
                activeSessions.remove(sessionId);
                return SessionValidationResult.invalid("会话已过期");
            }
            
            // 检查IP地址（可选的安全检查）
            if (!session.getIpAddress().equals(clientIp)) {
                logger.warn("会话IP地址不匹配: {} vs {}", session.getIpAddress(), clientIp);
                // 可以选择是否严格检查IP
            }
            
            // 更新最后活动时间
            session.updateLastActivity();
            updateSessionActivity(sessionId);
            
            return SessionValidationResult.valid(session);
        });
    }
    
    /**
     * 管理员登出
     */
    public CompletableFuture<Boolean> logout(String sessionId) {
        return databaseManager.executeAsync(connection -> {
            AdminSession session = activeSessions.remove(sessionId);
            
            // 从数据库删除会话
            String sql = "UPDATE admin_sessions SET is_active = 0 WHERE session_id = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, sessionId);
                stmt.executeUpdate();
            }
            
            if (session != null) {
                logAuthEvent(connection, "LOGOUT", session.getUser().getUsername(), 
                        session.getIpAddress(), session.getUserAgent(), true, null);
                logger.info("管理员登出: {}", session.getUser().getUsername());
            }
            
            return true;
        }).exceptionally(throwable -> {
            logger.error("管理员登出失败", throwable);
            return false;
        });
    }
    
    /**
     * 检查权限
     */
    public boolean hasPermission(AdminSession session, String permission) {
        if (session == null || !session.isValid()) {
            return false;
        }
        
        AdminRole role = session.getUser().getRole();
        return role != null && role.hasPermission(permission);
    }
    
    /**
     * 创建会话
     */
    private AdminSession createSession(AdminUser user, String clientIp, String userAgent) {
        String sessionId = generateSessionId();
        String jwtToken = generateJwtToken(user, sessionId);
        
        AdminSession session = new AdminSession();
        session.setSessionId(sessionId);
        session.setUser(user);
        session.setJwtToken(jwtToken);
        session.setIpAddress(clientIp);
        session.setUserAgent(userAgent);
        session.setCreatedAt(LocalDateTime.now());
        session.setLastActivity(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusMinutes(SESSION_TIMEOUT_MINUTES));
        
        return session;
    }
    
    /**
     * 生成会话ID
     */
    private String generateSessionId() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    
    /**
     * 生成JWT令牌
     */
    private String generateJwtToken(AdminUser user, String sessionId) {
        java.util.Date now = new java.util.Date();
        java.util.Date expiration = new java.util.Date(now.getTime() + JWT_EXPIRATION_HOURS * 60 * 60 * 1000);
        
        return Jwts.builder()
                .setSubject(user.getUsername())
                .setIssuedAt(now)
                .setExpiration(expiration)
                .claim("userId", user.getId())
                .claim("sessionId", sessionId)
                .claim("role", user.getRole().getRoleName())
                .signWith(jwtSecretKey)
                .compact();
    }
    
    /**
     * 验证JWT令牌
     */
    public Claims validateJwtToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(jwtSecretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            logger.debug("JWT令牌验证失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 创建默认角色
     */
    private void createDefaultRoles(Connection connection) throws SQLException {
        String[] defaultRoles = {
                "('SUPER_ADMIN', '超级管理员', '拥有所有权限', '[\"*\"]', 1)",
                "('ADMIN', '管理员', '白名单管理权限', '[\"whitelist.*\", \"api.read\"]', 1)",
                "('VIEWER', '查看者', '只读权限', '[\"whitelist.read\", \"api.read\"]', 1)"
        };
        
        String checkSql = "SELECT COUNT(*) FROM admin_roles WHERE role_name = ?";
        String insertSql = "INSERT INTO admin_roles (role_name, display_name, description, permissions, is_active) VALUES ";
        
        for (String roleData : defaultRoles) {
            String roleName = roleData.split("'")[1];
            
            try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
                checkStmt.setString(1, roleName);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        // 角色不存在，创建它
                        try (Statement stmt = connection.createStatement()) {
                            stmt.executeUpdate(insertSql + roleData);
                            logger.debug("创建默认角色: {}", roleName);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 创建默认管理员
     */
    private void createDefaultAdmin(Connection connection) throws SQLException {
        String checkSql = "SELECT COUNT(*) FROM admin_users WHERE username = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
            stmt.setString(1, "admin");
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    // 创建默认管理员
                    String defaultPassword = generateRandomPassword();
                    String hashedPassword = passwordEncoder.encode(defaultPassword);
                    
                    String insertSql = """
                        INSERT INTO admin_users (username, email, password_hash, salt, role_id, is_active, password_changed_at)
                        VALUES (?, ?, ?, ?, (SELECT id FROM admin_roles WHERE role_name = 'SUPER_ADMIN'), 1, ?)
                    """;
                    
                    try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                        insertStmt.setString(1, "admin");
                        insertStmt.setString(2, "admin@localhost");
                        insertStmt.setString(3, hashedPassword);
                        insertStmt.setString(4, ""); // BCrypt包含盐值
                        insertStmt.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
                        
                        insertStmt.executeUpdate();
                        
                        logger.warn("创建默认管理员账户 - 用户名: admin, 密码: {} (请立即修改密码)", defaultPassword);
                    }
                }
            }
        }
    }
    
    /**
     * 生成随机密码
     */
    private String generateRandomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder();
        
        for (int i = 0; i < 12; i++) {
            password.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return password.toString();
    }
    
    /**
     * 获取管理员用户
     */
    private AdminUser getAdminUser(Connection connection, String username) throws SQLException {
        String sql = """
            SELECT u.*, r.role_name, r.display_name, r.permissions
            FROM admin_users u
            JOIN admin_roles r ON u.role_id = r.id
            WHERE u.username = ? AND u.is_active = 1
        """;
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, username);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    AdminUser user = new AdminUser();
                    user.setId(rs.getLong("id"));
                    user.setUsername(rs.getString("username"));
                    user.setEmail(rs.getString("email"));
                    user.setPasswordHash(rs.getString("password_hash"));
                    user.setFailedAttempts(rs.getInt("failed_attempts"));
                    user.setLockedUntil(rs.getTimestamp("locked_until"));
                    
                    // 设置角色
                    AdminRole role = new AdminRole();
                    role.setRoleName(rs.getString("role_name"));
                    role.setDisplayName(rs.getString("display_name"));
                    role.setPermissions(rs.getString("permissions"));
                    user.setRole(role);
                    
                    return user;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 其他辅助方法的占位符
     */
    private void incrementFailedAttempts(Connection connection, Long userId) throws SQLException {
        String sql = """
            UPDATE admin_users 
            SET failed_attempts = failed_attempts + 1,
                locked_until = CASE 
                    WHEN failed_attempts + 1 >= ? THEN datetime('now', '+' || ? || ' minutes')
                    ELSE locked_until 
                END
            WHERE id = ?
        """;
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, MAX_FAILED_ATTEMPTS);
            stmt.setInt(2, LOCKOUT_DURATION_MINUTES);
            stmt.setLong(3, userId);
            stmt.executeUpdate();
        }
    }
    
    private void resetFailedAttempts(Connection connection, Long userId) throws SQLException {
        String sql = "UPDATE admin_users SET failed_attempts = 0, locked_until = NULL WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.executeUpdate();
        }
    }
    
    private void saveSession(Connection connection, AdminSession session) throws SQLException {
        String sql = """
            INSERT INTO admin_sessions (session_id, user_id, jwt_token_hash, ip_address, user_agent_hash, 
                                      created_at, last_activity, expires_at, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
        """;
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, session.getSessionId());
            stmt.setLong(2, session.getUser().getId());
            stmt.setString(3, hashString(session.getJwtToken()));
            stmt.setString(4, session.getIpAddress());
            stmt.setString(5, hashString(session.getUserAgent()));
            stmt.setTimestamp(6, Timestamp.valueOf(session.getCreatedAt()));
            stmt.setTimestamp(7, Timestamp.valueOf(session.getLastActivity()));
            stmt.setTimestamp(8, Timestamp.valueOf(session.getExpiresAt()));
            stmt.executeUpdate();
        }
    }
    
    private void updateUserLoginInfo(Connection connection, Long userId) throws SQLException {
        String sql = "UPDATE admin_users SET last_login = ?, login_count = login_count + 1 WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setLong(2, userId);
            stmt.executeUpdate();
        }
    }
    
    private void logAuthEvent(Connection connection, String eventType, String username, 
                             String ipAddress, String userAgent, boolean success, String failureReason) throws SQLException {
        String sql = """
            INSERT INTO auth_logs (event_type, username, ip_address, user_agent, success, failure_reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, eventType);
            stmt.setString(2, username);
            stmt.setString(3, ipAddress);
            stmt.setString(4, userAgent);
            stmt.setBoolean(5, success);
            stmt.setString(6, failureReason);
            stmt.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now()));
            stmt.executeUpdate();
        }
    }
    
    private AdminSession getSessionFromDatabase(String sessionId) {
        // 实现从数据库获取会话的逻辑
        return null;
    }
    
    private void updateSessionActivity(String sessionId) {
        // 实现更新会话活动时间的逻辑
    }
    
    private String hashString(String input) {
        // 实现字符串哈希的逻辑
        return String.valueOf(input.hashCode());
    }
    
    /**
     * 登录结果类
     */
    public static class LoginResult {
        private final boolean success;
        private final String message;
        private final AdminSession session;
        
        private LoginResult(boolean success, String message, AdminSession session) {
            this.success = success;
            this.message = message;
            this.session = session;
        }
        
        public static LoginResult success(AdminSession session) {
            return new LoginResult(true, "登录成功", session);
        }
        
        public static LoginResult failure(String message) {
            return new LoginResult(false, message, null);
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public AdminSession getSession() { return session; }
    }
    
    /**
     * 会话验证结果类
     */
    public static class SessionValidationResult {
        private final boolean valid;
        private final String message;
        private final AdminSession session;
        
        private SessionValidationResult(boolean valid, String message, AdminSession session) {
            this.valid = valid;
            this.message = message;
            this.session = session;
        }
        
        public static SessionValidationResult valid(AdminSession session) {
            return new SessionValidationResult(true, "会话有效", session);
        }
        
        public static SessionValidationResult invalid(String message) {
            return new SessionValidationResult(false, message, null);
        }
        
        public boolean isValid() { return valid; }
        public String getMessage() { return message; }
        public AdminSession getSession() { return session; }
    }
    
    /**
     * 管理员用户类
     */
    public static class AdminUser {
        private Long id;
        private String username;
        private String email;
        private String passwordHash;
        private AdminRole role;
        private int failedAttempts;
        private Timestamp lockedUntil;
        
        public boolean isLocked() {
            return lockedUntil != null && lockedUntil.after(Timestamp.valueOf(LocalDateTime.now()));
        }
        
        // Getters and Setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPasswordHash() { return passwordHash; }
        public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
        public AdminRole getRole() { return role; }
        public void setRole(AdminRole role) { this.role = role; }
        public int getFailedAttempts() { return failedAttempts; }
        public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
        public Timestamp getLockedUntil() { return lockedUntil; }
        public void setLockedUntil(Timestamp lockedUntil) { this.lockedUntil = lockedUntil; }
    }
    
    /**
     * 管理员角色类
     */
    public static class AdminRole {
        private String roleName;
        private String displayName;
        private String permissions;
        
        public boolean hasPermission(String permission) {
            if (permissions == null) return false;
            return permissions.contains("\"*\"") || permissions.contains("\"" + permission + "\"");
        }
        
        // Getters and Setters
        public String getRoleName() { return roleName; }
        public void setRoleName(String roleName) { this.roleName = roleName; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getPermissions() { return permissions; }
        public void setPermissions(String permissions) { this.permissions = permissions; }
    }
    
    /**
     * 管理员会话类
     */
    public static class AdminSession {
        private String sessionId;
        private AdminUser user;
        private String jwtToken;
        private String ipAddress;
        private String userAgent;
        private LocalDateTime createdAt;
        private LocalDateTime lastActivity;
        private LocalDateTime expiresAt;
        
        public boolean isValid() {
            return expiresAt != null && LocalDateTime.now().isBefore(expiresAt);
        }
        
        public void updateLastActivity() {
            this.lastActivity = LocalDateTime.now();
            this.expiresAt = LocalDateTime.now().plusMinutes(SESSION_TIMEOUT_MINUTES);
        }
        
        // Getters and Setters
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public AdminUser getUser() { return user; }
        public void setUser(AdminUser user) { this.user = user; }
        public String getJwtToken() { return jwtToken; }
        public void setJwtToken(String jwtToken) { this.jwtToken = jwtToken; }
        public String getIpAddress() { return ipAddress; }
        public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getLastActivity() { return lastActivity; }
        public void setLastActivity(LocalDateTime lastActivity) { this.lastActivity = lastActivity; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    }
}