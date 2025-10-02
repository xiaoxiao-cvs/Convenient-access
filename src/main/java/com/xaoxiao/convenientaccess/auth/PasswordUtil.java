package com.xaoxiao.convenientaccess.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码工具类 - 用于密码加密和验证
 */
public class PasswordUtil {
    private static final int SALT_LENGTH = 16;
    private static final String HASH_ALGORITHM = "SHA-256";
    
    /**
     * 加密密码
     * @param password 明文密码
     * @return 加密后的密码（包含salt）
     */
    public static String hashPassword(String password) {
        try {
            // 生成随机salt
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_LENGTH];
            random.nextBytes(salt);
            
            // 使用salt加密密码
            String hash = hashPasswordWithSalt(password, salt);
            
            // 将salt和hash组合返回: salt:hash
            return Base64.getEncoder().encodeToString(salt) + ":" + hash;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("密码加密失败", e);
        }
    }
    
    /**
     * 验证密码
     * @param password 明文密码
     * @param storedPassword 存储的加密密码
     * @return 是否匹配
     */
    public static boolean verifyPassword(String password, String storedPassword) {
        try {
            // 分离salt和hash
            String[] parts = storedPassword.split(":");
            if (parts.length != 2) {
                return false;
            }
            
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            String storedHash = parts[1];
            
            // 使用相同的salt加密输入的密码
            String hash = hashPasswordWithSalt(password, salt);
            
            // 比较hash值
            return hash.equals(storedHash);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 使用指定salt加密密码
     */
    private static String hashPasswordWithSalt(String password, byte[] salt) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
        md.update(salt);
        byte[] hashedPassword = md.digest(password.getBytes());
        return Base64.getEncoder().encodeToString(hashedPassword);
    }
    
    /**
     * 生成随机密码
     * @param length 密码长度
     * @return 随机密码
     */
    public static String generateRandomPassword(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder();
        
        for (int i = 0; i < length; i++) {
            password.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return password.toString();
    }
}
