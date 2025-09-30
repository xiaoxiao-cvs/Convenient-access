package com.xaoxiao.convenientaccess.auth;

import com.xaoxiao.convenientaccess.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * 注册令牌管理器
 * 处理用户注册令牌的生成、验证和管理
 */
public class RegistrationTokenManager {
    private static final Logger logger = LoggerFactory.getLogger(RegistrationTokenManager.class);
    
    private final DatabaseManager databaseManager;
    private final SecureRandom secureRandom;
    
    // Token配置
    private static final String TOKEN_PREFIX = "reg_";
    private static final int TOKEN_LENGTH = 32;
    private static final int DEFAULT_EXPIRY_HOURS = 24; // 默认24小时过期
    
    public RegistrationTokenManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.secureRandom = new SecureRandom();
    }
    
    /**
     * 生成注册令牌
     */
    public CompletableFuture<String> generateRegistrationToken(int expiryHours) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = generateRandomToken();
                String tokenHash = hashToken(token);
                LocalDateTime expiresAt = LocalDateTime.now().plusHours(expiryHours);
                
                boolean saved = databaseManager.executeTransactionAsync(connection -> {
                    String sql = """
                        INSERT INTO registration_tokens (token, token_hash, expires_at, is_used)
                        VALUES (?, ?, ?, ?)
                    """;
                    
                    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                        stmt.setString(1, token);
                        stmt.setString(2, tokenHash);
                        stmt.setTimestamp(3, Timestamp.valueOf(expiresAt));
                        stmt.setBoolean(4, false);
                        
                        return stmt.executeUpdate() > 0;
                    }
                }).get();
                
                if (saved) {
                    logger.info("生成注册令牌成功，过期时间: {}", expiresAt);
                    return token;
                } else {
                    logger.error("保存注册令牌失败");
                    return null;
                }
            } catch (Exception e) {
                logger.error("生成注册令牌失败", e);
                return null;
            }
        });
    }
    
    /**
     * 验证注册令牌
     */
    public CompletableFuture<TokenValidationResult> validateToken(String token, String clientIp) {
        return databaseManager.executeAsync(connection -> {
            String tokenHash = hashToken(token);
            String sql = """
                SELECT id, expires_at, is_used, used_at, used_by_ip 
                FROM registration_tokens 
                WHERE token_hash = ?
            """;
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, tokenHash);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return TokenValidationResult.invalid("令牌不存在");
                    }
                    
                    long tokenId = rs.getLong("id");
                    LocalDateTime expiresAt = rs.getTimestamp("expires_at").toLocalDateTime();
                    boolean isUsed = rs.getBoolean("is_used");
                    
                    // 检查是否已过期
                    if (LocalDateTime.now().isAfter(expiresAt)) {
                        return TokenValidationResult.invalid("令牌已过期");
                    }
                    
                    // 检查是否已使用
                    if (isUsed) {
                        return TokenValidationResult.invalid("令牌已被使用");
                    }
                    
                    return TokenValidationResult.valid(tokenId);
                }
            }
        }).exceptionally(throwable -> {
            logger.error("验证注册令牌失败", throwable);
            return TokenValidationResult.invalid("验证服务异常");
        });
    }
    
    /**
     * 标记令牌为已使用
     */
    public CompletableFuture<Boolean> markTokenAsUsed(long tokenId, String clientIp) {
        return databaseManager.executeTransactionAsync(connection -> {
            String sql = """
                UPDATE registration_tokens 
                SET is_used = ?, used_at = ?, used_by_ip = ? 
                WHERE id = ?
            """;
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setBoolean(1, true);
                stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                stmt.setString(3, clientIp);
                stmt.setLong(4, tokenId);
                
                return stmt.executeUpdate() > 0;
            }
        }).exceptionally(throwable -> {
            logger.error("标记令牌为已使用失败", throwable);
            return false;
        });
    }
    
    /**
     * 清理过期令牌
     */
    public CompletableFuture<Integer> cleanupExpiredTokens() {
        return databaseManager.executeTransactionAsync(connection -> {
            String sql = "DELETE FROM registration_tokens WHERE expires_at < ?";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
                
                int deleted = stmt.executeUpdate();
                if (deleted > 0) {
                    logger.info("清理过期注册令牌: {} 个", deleted);
                }
                return deleted;
            }
        }).exceptionally(throwable -> {
            logger.error("清理过期令牌失败", throwable);
            return 0;
        });
    }
    
    /**
     * 生成随机令牌
     */
    private String generateRandomToken() {
        byte[] randomBytes = new byte[TOKEN_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return TOKEN_PREFIX + encoded.substring(0, TOKEN_LENGTH - TOKEN_PREFIX.length());
    }
    
    /**
     * 计算令牌哈希值
     */
    private String hashToken(String token) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("计算令牌哈希失败", e);
        }
    }
    
    /**
     * 令牌验证结果类
     */
    public static class TokenValidationResult {
        private final boolean valid;
        private final String message;
        private final Long tokenId;
        
        private TokenValidationResult(boolean valid, String message, Long tokenId) {
            this.valid = valid;
            this.message = message;
            this.tokenId = tokenId;
        }
        
        public static TokenValidationResult valid(Long tokenId) {
            return new TokenValidationResult(true, "令牌有效", tokenId);
        }
        
        public static TokenValidationResult invalid(String message) {
            return new TokenValidationResult(false, message, null);
        }
        
        public boolean isValid() { return valid; }
        public String getMessage() { return message; }
        public Long getTokenId() { return tokenId; }
    }
}