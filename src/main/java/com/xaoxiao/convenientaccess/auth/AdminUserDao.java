package com.xaoxiao.convenientaccess.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.database.DatabaseManager;

/**
 * 管理员用户数据访问对象
 */
public class AdminUserDao {
    private static final Logger logger = LoggerFactory.getLogger(AdminUserDao.class);
    private final DatabaseManager dbManager;
    
    public AdminUserDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }
    
    /**
     * 创建管理员用户
     */
    public boolean createAdminUser(AdminUser user) {
        String sql = "INSERT INTO admin_users (username, password_hash, display_name, email, is_super_admin, is_active) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, user.getUsername());
            pstmt.setString(2, user.getPasswordHash());
            pstmt.setString(3, user.getDisplayName());
            pstmt.setString(4, user.getEmail());
            pstmt.setBoolean(5, user.isSuperAdmin());
            pstmt.setBoolean(6, user.isActive());
            
            int affected = pstmt.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            logger.error("创建管理员用户失败", e);
            return false;
        }
    }
    
    /**
     * 根据用户名查找管理员
     */
    public Optional<AdminUser> findByUsername(String username) {
        String sql = "SELECT * FROM admin_users WHERE username = ?";
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                return Optional.of(mapResultSetToAdminUser(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            logger.error("查找管理员失败", e);
            return Optional.empty();
        }
    }
    
    /**
     * 根据ID查找管理员
     */
    public Optional<AdminUser> findById(Long id) {
        String sql = "SELECT * FROM admin_users WHERE id = ?";
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setLong(1, id);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                return Optional.of(mapResultSetToAdminUser(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            logger.error("查找管理员失败", e);
            return Optional.empty();
        }
    }
    
    /**
     * 更新最后登录时间
     */
    public boolean updateLastLogin(Long adminId, String ipAddress) {
        String sql = "UPDATE admin_users SET last_login_at = ?, last_login_ip = ?, updated_at = ? WHERE id = ?";
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            LocalDateTime now = LocalDateTime.now();
            pstmt.setObject(1, now);
            pstmt.setString(2, ipAddress);
            pstmt.setObject(3, now);
            pstmt.setLong(4, adminId);
            
            int affected = pstmt.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            logger.error("更新最后登录时间失败", e);
            return false;
        }
    }
    
    /**
     * 检查用户名是否存在
     */
    public boolean existsByUsername(String username) {
        String sql = "SELECT COUNT(*) FROM admin_users WHERE username = ?";
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
            return false;
        } catch (SQLException e) {
            logger.error("检查用户名是否存在失败", e);
            return false;
        }
    }
    
    /**
     * 获取所有管理员列表
     */
    public List<AdminUser> findAll() {
        String sql = "SELECT * FROM admin_users ORDER BY created_at DESC";
        List<AdminUser> users = new ArrayList<>();
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            
            while (rs.next()) {
                users.add(mapResultSetToAdminUser(rs));
            }
        } catch (SQLException e) {
            logger.error("获取管理员列表失败", e);
        }
        
        return users;
    }
    
    /**
     * 停用管理员账号
     */
    public boolean deactivateUser(Long adminId) {
        String sql = "UPDATE admin_users SET is_active = FALSE, updated_at = ? WHERE id = ?";
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setObject(1, LocalDateTime.now());
            pstmt.setLong(2, adminId);
            
            int affected = pstmt.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            logger.error("停用管理员账号失败", e);
            return false;
        }
    }
    
    /**
     * 将ResultSet映射为AdminUser对象
     */
    private AdminUser mapResultSetToAdminUser(ResultSet rs) throws SQLException {
        AdminUser user = new AdminUser();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setDisplayName(rs.getString("display_name"));
        user.setEmail(rs.getString("email"));
        user.setSuperAdmin(rs.getBoolean("is_super_admin"));
        user.setActive(rs.getBoolean("is_active"));
        
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            user.setCreatedAt(createdAt.toLocalDateTime());
        }
        
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) {
            user.setUpdatedAt(updatedAt.toLocalDateTime());
        }
        
        Timestamp lastLoginAt = rs.getTimestamp("last_login_at");
        if (lastLoginAt != null) {
            user.setLastLoginAt(lastLoginAt.toLocalDateTime());
        }
        
        user.setLastLoginIp(rs.getString("last_login_ip"));
        
        return user;
    }
}
