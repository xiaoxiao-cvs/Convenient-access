package com.xaoxiao.convenientaccess.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JWT工具类 - 使用 HMAC-SHA256 签名 (HS256)
 * 格式: header.payload.signature
 */
public class JwtUtil {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static volatile byte[] secretKey;

    /**
     * 初始化JWT密钥
     * 必须在使用任何签名/验签接口之前调用。
     * Why: 旧实现把签名密钥从 admin password 简单拼接派生 (SECRET_KEY_PREFIX + password)，
     * admin password 一旦泄漏（默认 12 位字符 + 配置文件明文），JWT 签名即可被伪造。
     * 现在改为接收一个独立的、首次启动随机生成的高熵 secret。
     */
    public static void initialize(String jwtSecret) {
        if (jwtSecret == null || jwtSecret.isEmpty()) {
            throw new IllegalArgumentException("JWT secret 不能为空");
        }
        secretKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
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
            
            // 验证签名 (constant-time 比较，防 timing attack)
            String data = encodedHeader + "." + encodedPayload;
            String expectedSignature = createSignature(data);
            byte[] signatureBytes = signature.getBytes(StandardCharsets.UTF_8);
            byte[] expectedBytes = expectedSignature.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(signatureBytes, expectedBytes)) {
                return null;
            }
            
            // 解析payload
            String payloadJson = base64UrlDecode(encodedPayload);
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

            // 必备字段校验（防止伪造或损坏的 token 通过签名验证后引发 NPE）
            if (!payload.has("exp") || !payload.has("sub") || !payload.has("adminId")) {
                return null;
            }

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
        if (payload == null || !payload.has("adminId")) {
            return null;
        }
        try {
            return payload.get("adminId").getAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从token中提取用户名
     */
    public static String getUsername(String token) {
        JsonObject payload = verifyAndDecode(token);
        if (payload == null || !payload.has("sub")) {
            return null;
        }
        try {
            return payload.get("sub").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检查token是否过期
     */
    public static boolean isTokenExpired(String token) {
        JsonObject payload = verifyAndDecode(token);
        if (payload == null || !payload.has("exp")) {
            return true;
        }
        try {
            long exp = payload.get("exp").getAsLong();
            return Instant.now().getEpochSecond() > exp;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 获取token过期时间
     */
    public static LocalDateTime getExpirationTime(String token) {
        JsonObject payload = verifyAndDecode(token);
        if (payload == null || !payload.has("exp")) {
            return null;
        }
        try {
            long exp = payload.get("exp").getAsLong();
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(exp), ZoneId.systemDefault());
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 创建签名 (HMAC-SHA256)
     */
    private static String createSignature(String data) throws Exception {
        if (secretKey == null) {
            throw new IllegalStateException("JwtUtil 未初始化, 请先调用 JwtUtil.initialize()");
        }
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
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
