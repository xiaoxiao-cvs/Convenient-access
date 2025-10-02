package com.xaoxiao.convenientaccess.auth;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.database.DatabaseManager;

/**
 * 管理员认证服务
 */
public class AdminAuthService {
    private static final Logger logger = LoggerFactory.getLogger(AdminAuthService.class);
    private static final int TOKEN_EXPIRATION_HOURS = 24; // Token有效期24小时
    
    private final AdminUserDao adminUserDao;
    private final AuthLogDao authLogDao;
    private final RegistrationTokenManager tokenManager;
    private final String systemAdminPassword;
    
    public AdminAuthService(DatabaseManager dbManager, RegistrationTokenManager tokenManager, String systemAdminPassword) {
        this.adminUserDao = new AdminUserDao(dbManager);
        this.authLogDao = new AuthLogDao(dbManager);
        this.tokenManager = tokenManager;
        this.systemAdminPassword = systemAdminPassword;
        
        // 初始化JWT密钥
        JwtUtil.initialize(systemAdminPassword);
        
        // 确保超级管理员账号存在
        ensureSuperAdminExists();
    }
    
    /**
     * 确保超级管理员账号存在
     */
    public void ensureSuperAdminExists() {
        if (!adminUserDao.existsByUsername("admin")) {
            AdminUser superAdmin = new AdminUser("admin", 
                                                 PasswordUtil.hashPassword(systemAdminPassword), 
                                                 "超级管理员");
            superAdmin.setSuperAdmin(true);
            superAdmin.setActive(true);
            
            if (adminUserDao.createAdminUser(superAdmin)) {
                logger.info("超级管理员账号创建成功");
            } else {
                logger.error("超级管理员账号创建失败");
            }
        }
    }
    
    /**
     * 管理员登录
     * @param username 用户名
     * @param password 密码
     * @param ipAddress IP地址
     * @return 登录结果（包含JWT token）
     */
    public LoginResult login(String username, String password, String ipAddress) {
        try {
            // 查找管理员
            Optional<AdminUser> userOpt = adminUserDao.findByUsername(username);
            if (!userOpt.isPresent()) {
                authLogDao.logAuth(username, "FAILED_LOGIN", false, ipAddress, null, "用户不存在");
                return LoginResult.failure("用户名或密码错误");
            }
            
            AdminUser user = userOpt.get();
            
            // 检查账号是否激活
            if (!user.isActive()) {
                authLogDao.logAuth(username, "FAILED_LOGIN", false, ipAddress, null, "账号已停用");
                return LoginResult.failure("账号已停用");
            }
            
            // 验证密码
            if (!PasswordUtil.verifyPassword(password, user.getPasswordHash())) {
                authLogDao.logAuth(username, "FAILED_LOGIN", false, ipAddress, null, "密码错误");
                return LoginResult.failure("用户名或密码错误");
            }
            
            // 生成JWT token
            String token = JwtUtil.generateToken(user.getId(), user.getUsername(), TOKEN_EXPIRATION_HOURS);
            
            // 更新最后登录时间
            adminUserDao.updateLastLogin(user.getId(), ipAddress);
            
            // 记录登录日志
            authLogDao.logAuth(username, "LOGIN", true, ipAddress, null, null);
            
            logger.info("管理员 {} 登录成功", username);
            return LoginResult.success(token, user);
            
        } catch (Exception e) {
            logger.error("管理员登录失败", e);
            return LoginResult.failure("登录失败: " + e.getMessage());
        }
    }
    
    /**
     * 管理员注册
     * @param username 用户名
     * @param password 密码
     * @param displayName 显示名称
     * @param registrationToken 注册令牌
     * @param ipAddress IP地址
     * @return 注册结果
     */
    public RegisterResult register(String username, String password, String displayName, 
                                   String registrationToken, String ipAddress) {
        try {
            // 验证注册令牌
            var tokenValidation = tokenManager.validateToken(registrationToken, ipAddress).get();
            if (!tokenValidation.isValid()) {
                authLogDao.logAuth(username, "REGISTER", false, ipAddress, null, "注册令牌无效");
                return RegisterResult.failure(tokenValidation.getMessage());
            }
            
            // 检查用户名是否已存在
            if (adminUserDao.existsByUsername(username)) {
                authLogDao.logAuth(username, "REGISTER", false, ipAddress, null, "用户名已存在");
                return RegisterResult.failure("用户名已存在");
            }
            
            // 创建管理员账号
            String passwordHash = PasswordUtil.hashPassword(password);
            AdminUser newAdmin = new AdminUser(username, passwordHash, displayName);
            newAdmin.setSuperAdmin(false);
            newAdmin.setActive(true);
            
            if (!adminUserDao.createAdminUser(newAdmin)) {
                authLogDao.logAuth(username, "REGISTER", false, ipAddress, null, "创建账号失败");
                return RegisterResult.failure("创建账号失败");
            }
            
            // 标记令牌为已使用
            tokenManager.markTokenAsUsed(tokenValidation.getTokenId(), ipAddress);
            
            // 记录注册日志
            authLogDao.logAuth(username, "REGISTER", true, ipAddress, null, null);
            
            logger.info("管理员 {} 注册成功", username);
            return RegisterResult.success("注册成功");
            
        } catch (Exception e) {
            logger.error("管理员注册失败", e);
            return RegisterResult.failure("注册失败: " + e.getMessage());
        }
    }
    
    /**
     * 验证JWT token
     * @param token JWT token
     * @return 管理员用户，如果验证失败返回null
     */
    public AdminUser validateToken(String token) {
        try {
            Long adminId = JwtUtil.getAdminId(token);
            if (adminId == null) {
                return null;
            }
            
            Optional<AdminUser> userOpt = adminUserDao.findById(adminId);
            if (!userOpt.isPresent()) {
                return null;
            }
            
            AdminUser user = userOpt.get();
            // 检查账号是否激活
            if (!user.isActive()) {
                return null;
            }
            
            return user;
        } catch (Exception e) {
            logger.error("验证token失败", e);
            return null;
        }
    }
    
    /**
     * 登录结果
     */
    public static class LoginResult {
        private final boolean success;
        private final String message;
        private final String token;
        private final AdminUser user;
        
        private LoginResult(boolean success, String message, String token, AdminUser user) {
            this.success = success;
            this.message = message;
            this.token = token;
            this.user = user;
        }
        
        public static LoginResult success(String token, AdminUser user) {
            return new LoginResult(true, "登录成功", token, user);
        }
        
        public static LoginResult failure(String message) {
            return new LoginResult(false, message, null, null);
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getToken() { return token; }
        public AdminUser getUser() { return user; }
    }
    
    /**
     * 注册结果
     */
    public static class RegisterResult {
        private final boolean success;
        private final String message;
        
        private RegisterResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        
        public static RegisterResult success(String message) {
            return new RegisterResult(true, message);
        }
        
        public static RegisterResult failure(String message) {
            return new RegisterResult(false, message);
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }
}
