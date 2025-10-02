package com.xaoxiao.convenientaccess.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.database.DatabaseManager;

/**
 * 认证日志数据访问对象
 */
public class AuthLogDao {
    private static final Logger logger = LoggerFactory.getLogger(AuthLogDao.class);
    private final DatabaseManager dbManager;
    
    public AuthLogDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }
    
    /**
     * 记录认证日志
     * @param username 用户名
     * @param actionType 操作类型（LOGIN, LOGOUT, REGISTER, FAILED_LOGIN）
     * @param success 是否成功
     * @param ipAddress IP地址
     * @param userAgent 用户代理
     * @param failureReason 失败原因
     * @return 是否记录成功
     */
    public boolean logAuth(String username, String actionType, boolean success, 
                          String ipAddress, String userAgent, String failureReason) {
        String sql = "INSERT INTO auth_logs (username, action_type, success, ip_address, user_agent, failure_reason) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            pstmt.setString(2, actionType);
            pstmt.setBoolean(3, success);
            pstmt.setString(4, ipAddress);
            pstmt.setString(5, userAgent);
            pstmt.setString(6, failureReason);
            
            int affected = pstmt.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            logger.error("记录认证日志失败", e);
            return false;
        }
    }
}
