package com.xaoxiao.convenientaccess.auth;

import com.xaoxiao.convenientaccess.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Key管理器
 * 处理API Key的生成、验证和管理
 */
public class ApiKeyManager {
    private static final Logger logger = LoggerFactory.getLogger(ApiKeyManager.class);
    
    private final DatabaseManager databaseManager;
    private final SecureRandom secureRandom;
    private final ConcurrentHashMap<String, ApiKeyInfo> keyCache = new ConcurrentHashMap<>();
    
    // API Key配置
    private static final String KEY_PREFIX = "ca_";
    private static final int KEY_LENGTH = 64;
    private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    
    public ApiKeyManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.secureRandom = new SecureRandom();
    }
    
    /**
     * 初始化API Key管理器
     */
    public CompletableFuture<Boolean> initialize() {
        return databaseManager.executeAsync(connection -> {
            // 创建API Keys表（如果不存在）
            String createTableSql = """
                CREATE TABLE IF NOT EXISTS api_keys (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    key_hash VARCHAR(128) NOT NULL UNIQUE,
                    name VARCHAR(64) NOT NULL,
                    permissions TEXT NOT NULL,
                    ip_whitelist TEXT,
                    expires_at TIMESTAMP,
                    is_active BOOLEAN DEFAULT 1,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    last_used_at TIMESTAMP,
                    usage_count INTEGER DEFAULT 0
                )
            """;
            
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(createTableSql);
                
                // 创建索引（SQLite需要单独创建）
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_api_keys_key_hash ON api_keys(key_hash)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_api_keys_is_active ON api_keys(is_active)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_api_keys_expires_at ON api_keys(expires_at)");
            }
            
            // 加载活跃的API Keys到缓存
            loadKeysToCache(connection);
            
            logger.info("API Key管理器初始化完成，缓存了 {} 个API Key", keyCache.size());
            return true;
        }).exceptionally(throwable -> {
            logger.error("API Key管理器初始化失败", throwable);
            return false;
        });
    }
    
    /**
     * 生成新的API Key
     */
    public CompletableFuture<String> generateApiKey(String name, String[] permissions, String ipWhitelist, LocalDateTime expiresAt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 生成随机API Key
                String apiKey = generateRandomKey();
                String keyHash = hashApiKey(apiKey);
                
                // 保存到数据库
                boolean saved = databaseManager.executeTransactionAsync(connection -> {
                    String sql = """
                        INSERT INTO api_keys (key_hash, name, permissions, ip_whitelist, expires_at, is_active)
                        VALUES (?, ?, ?, ?, ?, ?)
                    """;
                    
                    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                        stmt.setString(1, keyHash);
                        stmt.setString(2, name);
                        stmt.setString(3, String.join(",", permissions));
                        stmt.setString(4, ipWhitelist);
                        if (expiresAt != null) {
                            stmt.setTimestamp(5, Timestamp.valueOf(expiresAt));
                        } else {
                            stmt.setNull(5, Types.TIMESTAMP);
                        }
                        stmt.setBoolean(6, true);
                        
                        return stmt.executeUpdate() > 0;
                    }
                }).get();
                
                if (saved) {
                    // 添加到缓存
                    ApiKeyInfo keyInfo = new ApiKeyInfo();
                    keyInfo.keyHash = keyHash;
                    keyInfo.name = name;
                    keyInfo.permissions = permissions;
                    keyInfo.ipWhitelist = ipWhitelist;
                    keyInfo.expiresAt = expiresAt;
                    keyInfo.isActive = true;
                    keyCache.put(keyHash, keyInfo);
                    
                    logger.info("生成新的API Key: {} ({})", name, keyHash.substring(0, 8) + "...");
                    return apiKey;
                } else {
                    logger.error("保存API Key失败: {}", name);
                    return null;
                }
            } catch (Exception e) {
                logger.error("生成API Key失败: {}", name, e);
                return null;
            }
        });
    }
    
    /**
     * 验证API Key
     */
    public CompletableFuture<ApiKeyValidationResult> validateApiKey(String apiKey, String clientIp) {
        if (apiKey == null || !apiKey.startsWith(KEY_PREFIX)) {
            return CompletableFuture.completedFuture(ApiKeyValidationResult.invalid("API Key格式无效"));
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String keyHash = hashApiKey(apiKey);
                ApiKeyInfo keyInfo = keyCache.get(keyHash);
                
                if (keyInfo == null) {
                    // 从数据库查询
                    keyInfo = loadKeyFromDatabase(keyHash);
                    if (keyInfo != null) {
                        keyCache.put(keyHash, keyInfo);
                    }
                }
                
                if (keyInfo == null) {
                    return ApiKeyValidationResult.invalid("API Key不存在");
                }
                
                if (!keyInfo.isActive) {
                    return ApiKeyValidationResult.invalid("API Key已禁用");
                }
                
                // 检查过期时间
                if (keyInfo.expiresAt != null && LocalDateTime.now().isAfter(keyInfo.expiresAt)) {
                    return ApiKeyValidationResult.invalid("API Key已过期");
                }
                
                // 检查IP白名单
                if (keyInfo.ipWhitelist != null && !keyInfo.ipWhitelist.isEmpty()) {
                    String[] allowedIps = keyInfo.ipWhitelist.split(",");
                    boolean ipAllowed = false;
                    for (String allowedIp : allowedIps) {
                        if (allowedIp.trim().equals(clientIp) || allowedIp.trim().equals("*")) {
                            ipAllowed = true;
                            break;
                        }
                    }
                    if (!ipAllowed) {
                        return ApiKeyValidationResult.invalid("IP地址不在白名单中");
                    }
                }
                
                // 更新使用统计
                updateKeyUsage(keyHash);
                
                return ApiKeyValidationResult.valid(keyInfo);
            } catch (Exception e) {
                logger.error("验证API Key失败", e);
                return ApiKeyValidationResult.invalid("验证失败");
            }
        });
    }
    
    /**
     * 撤销API Key
     */
    public CompletableFuture<Boolean> revokeApiKey(String keyHash) {
        return databaseManager.executeTransactionAsync(connection -> {
            String sql = "UPDATE api_keys SET is_active = 0 WHERE key_hash = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, keyHash);
                boolean updated = stmt.executeUpdate() > 0;
                
                if (updated) {
                    // 从缓存移除
                    keyCache.remove(keyHash);
                    logger.info("撤销API Key: {}", keyHash.substring(0, 8) + "...");
                }
                
                return updated;
            }
        }).exceptionally(throwable -> {
            logger.error("撤销API Key失败: {}", keyHash, throwable);
            return false;
        });
    }
    
    /**
     * 生成随机API Key
     */
    private String generateRandomKey() {
        byte[] randomBytes = new byte[48]; // 48字节 = 64个Base58字符（约）
        secureRandom.nextBytes(randomBytes);
        
        // 使用Base58编码
        String encoded = encodeBase58(randomBytes);
        
        // 截取到指定长度并添加前缀
        String keyPart = encoded.substring(0, KEY_LENGTH - KEY_PREFIX.length());
        return KEY_PREFIX + keyPart;
    }
    
    /**
     * Base58编码
     */
    private String encodeBase58(byte[] input) {
        if (input.length == 0) {
            return "";
        }
        
        // 计算前导零的数量
        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == 0) {
            leadingZeros++;
        }
        
        // 转换为大整数并编码
        StringBuilder result = new StringBuilder();
        byte[] inputCopy = input.clone();
        
        while (hasNonZero(inputCopy)) {
            int remainder = divideBy58(inputCopy);
            result.insert(0, BASE58_ALPHABET.charAt(remainder));
        }
        
        // 添加前导零对应的字符
        for (int i = 0; i < leadingZeros; i++) {
            result.insert(0, BASE58_ALPHABET.charAt(0));
        }
        
        return result.toString();
    }
    
    /**
     * 检查字节数组是否有非零值
     */
    private boolean hasNonZero(byte[] array) {
        for (byte b : array) {
            if (b != 0) return true;
        }
        return false;
    }
    
    /**
     * 字节数组除以58
     */
    private int divideBy58(byte[] number) {
        int remainder = 0;
        for (int i = 0; i < number.length; i++) {
            int temp = remainder * 256 + (number[i] & 0xFF);
            number[i] = (byte) (temp / 58);
            remainder = temp % 58;
        }
        return remainder;
    }
    
    /**
     * 计算API Key哈希值
     */
    private String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256算法不可用", e);
        }
    }
    
    /**
     * 从数据库加载API Key信息
     */
    private ApiKeyInfo loadKeyFromDatabase(String keyHash) {
        try {
            return databaseManager.executeAsync(connection -> {
                String sql = """
                    SELECT key_hash, name, permissions, ip_whitelist, expires_at, is_active
                    FROM api_keys WHERE key_hash = ?
                """;
                
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setString(1, keyHash);
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            ApiKeyInfo keyInfo = new ApiKeyInfo();
                            keyInfo.keyHash = rs.getString("key_hash");
                            keyInfo.name = rs.getString("name");
                            keyInfo.permissions = rs.getString("permissions").split(",");
                            keyInfo.ipWhitelist = rs.getString("ip_whitelist");
                            
                            Timestamp expiresAt = rs.getTimestamp("expires_at");
                            if (expiresAt != null) {
                                keyInfo.expiresAt = expiresAt.toLocalDateTime();
                            }
                            
                            keyInfo.isActive = rs.getBoolean("is_active");
                            return keyInfo;
                        }
                        return null;
                    }
                }
            }).get();
        } catch (Exception e) {
            logger.error("从数据库加载API Key失败: {}", keyHash, e);
            return null;
        }
    }
    
    /**
     * 加载所有活跃的API Keys到缓存
     */
    private void loadKeysToCache(Connection connection) throws SQLException {
        String sql = """
            SELECT key_hash, name, permissions, ip_whitelist, expires_at, is_active
            FROM api_keys WHERE is_active = 1
        """;
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            keyCache.clear();
            while (rs.next()) {
                ApiKeyInfo keyInfo = new ApiKeyInfo();
                keyInfo.keyHash = rs.getString("key_hash");
                keyInfo.name = rs.getString("name");
                keyInfo.permissions = rs.getString("permissions").split(",");
                keyInfo.ipWhitelist = rs.getString("ip_whitelist");
                
                Timestamp expiresAt = rs.getTimestamp("expires_at");
                if (expiresAt != null) {
                    keyInfo.expiresAt = expiresAt.toLocalDateTime();
                }
                
                keyInfo.isActive = rs.getBoolean("is_active");
                keyCache.put(keyInfo.keyHash, keyInfo);
            }
        }
    }
    
    /**
     * 更新API Key使用统计
     */
    private void updateKeyUsage(String keyHash) {
        // 异步更新，不阻塞验证流程
        databaseManager.executeAsync(connection -> {
            String sql = "UPDATE api_keys SET last_used_at = ?, usage_count = usage_count + 1 WHERE key_hash = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
                stmt.setString(2, keyHash);
                stmt.executeUpdate();
                return true;
            }
        }).exceptionally(throwable -> {
            logger.warn("更新API Key使用统计失败: {}", keyHash, throwable);
            return false;
        });
    }
    
    /**
     * API Key信息类
     */
    public static class ApiKeyInfo {
        public String keyHash;
        public String name;
        public String[] permissions;
        public String ipWhitelist;
        public LocalDateTime expiresAt;
        public boolean isActive;
        
        public boolean hasPermission(String permission) {
            if (permissions == null) return false;
            for (String perm : permissions) {
                if ("*".equals(perm) || permission.equals(perm)) {
                    return true;
                }
            }
            return false;
        }
    }
    
    /**
     * API Key验证结果类
     */
    public static class ApiKeyValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final ApiKeyInfo keyInfo;
        
        private ApiKeyValidationResult(boolean valid, String errorMessage, ApiKeyInfo keyInfo) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.keyInfo = keyInfo;
        }
        
        public static ApiKeyValidationResult valid(ApiKeyInfo keyInfo) {
            return new ApiKeyValidationResult(true, null, keyInfo);
        }
        
        public static ApiKeyValidationResult invalid(String errorMessage) {
            return new ApiKeyValidationResult(false, errorMessage, null);
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public ApiKeyInfo getKeyInfo() {
            return keyInfo;
        }
    }
}