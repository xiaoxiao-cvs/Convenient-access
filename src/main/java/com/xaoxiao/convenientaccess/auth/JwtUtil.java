package com.xaoxiao.convenientaccess.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JWT工具类 - 简化版JWT实现
 * 格式: header.payload.signature
 */
public class JwtUtil {
    private static final String SECRET_KEY_PREFIX = "ConvenientAccess-";
    private static String secretKey;
    
    /**
     * 初始化JWT密钥
     */
    public static void initialize(String adminPassword) {
        secretKey = SECRET_KEY_PREFIX + adminPassword;
    }
    
    /**
     * 生成JWT token
     * @param adminId 管理员ID
     * @param username 用户名
     * @param expirationHours token有效期(小时)
     * @return JWT token
     */
    public static String generateToken(Long adminId, String username, int expirationHours) {
        try {
            // Header
            JsonObject header = new JsonObject();
            header.addProperty("alg", "HS256");
            header.addProperty("typ", "JWT");
            
            // Payload
            JsonObject payload = new JsonObject();
            payload.addProperty("sub", username);
            payload.addProperty("adminId", adminId);
            payload.addProperty("iat", Instant.now().getEpochSecond());
            payload.addProperty("exp", Instant.now().plusSeconds(expirationHours * 3600L).getEpochSecond());
            payload.addProperty("jti", UUID.randomUUID().toString());
            
            // Encode header and payload
            String encodedHeader = base64UrlEncode(header.toString());
            String encodedPayload = base64UrlEncode(payload.toString());
            
            // Create signature
            String data = encodedHeader + "." + encodedPayload;
            String signature = createSignature(data);
            
            return data + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("生成JWT token失败", e);
        }
    }
    
    /**
     * 验证并解析JWT token
     * @param token JWT token
     * @return 解析后的payload，如果验证失败返回null
     */
    public static JsonObject verifyAndDecode(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            
            String encodedHeader = parts[0];
            String encodedPayload = parts[1];
            String signature = parts[2];
            
            // 验证签名
            String data = encodedHeader + "." + encodedPayload;
            String expectedSignature = createSignature(data);
            if (!signature.equals(expectedSignature)) {
                return null;
            }
            
            // 解析payload
            String payloadJson = base64UrlDecode(encodedPayload);
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            
            // 检查过期时间
            long exp = payload.get("exp").getAsLong();
            if (Instant.now().getEpochSecond() > exp) {
                return null; // Token已过期
            }
            
            return payload;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 从token中提取管理员ID
     */
    public static Long getAdminId(String token) {
        JsonObject payload = verifyAndDecode(token);
        return payload != null ? payload.get("adminId").getAsLong() : null;
    }
    
    /**
     * 从token中提取用户名
     */
    public static String getUsername(String token) {
        JsonObject payload = verifyAndDecode(token);
        return payload != null ? payload.get("sub").getAsString() : null;
    }
    
    /**
     * 检查token是否过期
     */
    public static boolean isTokenExpired(String token) {
        JsonObject payload = verifyAndDecode(token);
        if (payload == null) {
            return true;
        }
        long exp = payload.get("exp").getAsLong();
        return Instant.now().getEpochSecond() > exp;
    }
    
    /**
     * 获取token过期时间
     */
    public static LocalDateTime getExpirationTime(String token) {
        JsonObject payload = verifyAndDecode(token);
        if (payload == null) {
            return null;
        }
        long exp = payload.get("exp").getAsLong();
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(exp), ZoneId.systemDefault());
    }
    
    /**
     * 创建签名
     */
    private static String createSignature(String data) throws Exception {
        String signData = data + secretKey;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(signData.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(Base64.getEncoder().encodeToString(hash));
    }
    
    /**
     * Base64 URL编码
     */
    private static String base64UrlEncode(String str) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(str.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Base64 URL解码
     */
    private static String base64UrlDecode(String str) {
        return new String(Base64.getUrlDecoder().decode(str), StandardCharsets.UTF_8);
    }
}
