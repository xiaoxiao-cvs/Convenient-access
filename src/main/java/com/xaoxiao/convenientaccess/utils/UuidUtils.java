package com.xaoxiao.convenientaccess.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * UUID工具类
 * 提供UUID生成和验证功能
 */
public class UuidUtils {
    
    /**
     * 验证UUID格式
     */
    public static boolean isValidUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) {
            return false;
        }
        try {
            UUID.fromString(uuid);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    
    /**
     * 生成随机UUID
     */
    public static String generateRandomUuid() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * 根据玩家名生成确定性UUID
     * 使用MD5哈希算法确保相同玩家名总是生成相同的UUID
     * 
     * @param playerName 玩家名
     * @return 生成的UUID字符串
     */
    public static String generateUuidFromPlayerName(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new IllegalArgumentException("玩家名不能为空");
        }
        
        try {
            // 使用MD5哈希算法
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(playerName.toLowerCase().getBytes(StandardCharsets.UTF_8));
            
            // 将哈希值转换为UUID格式
            // 取前16字节构造UUID
            long mostSigBits = 0;
            long leastSigBits = 0;
            
            for (int i = 0; i < 8; i++) {
                mostSigBits = (mostSigBits << 8) | (hash[i] & 0xff);
            }
            
            for (int i = 8; i < 16; i++) {
                leastSigBits = (leastSigBits << 8) | (hash[i] & 0xff);
            }
            
            // 设置版本号为3（基于名称的UUID）
            mostSigBits &= ~(0xF000L);
            mostSigBits |= 0x3000L;
            
            // 设置变体位
            leastSigBits &= ~(0xC000000000000000L);
            leastSigBits |= 0x8000000000000000L;
            
            UUID uuid = new UUID(mostSigBits, leastSigBits);
            return uuid.toString();
            
        } catch (NoSuchAlgorithmException e) {
            // MD5算法不可用时，使用随机UUID作为后备方案
            return generateRandomUuid();
        }
    }
    
    /**
     * 根据玩家名生成UUID，如果已提供UUID则验证并返回
     * 
     * @param playerName 玩家名
     * @param providedUuid 提供的UUID（可为null）
     * @return 有效的UUID字符串
     */
    public static String getOrGenerateUuid(String playerName, String providedUuid) {
        // 如果提供了UUID且格式有效，则使用提供的UUID
        if (providedUuid != null && !providedUuid.trim().isEmpty() && isValidUuid(providedUuid)) {
            return providedUuid.trim();
        }
        
        // 否则根据玩家名生成UUID
        return generateUuidFromPlayerName(playerName);
    }
}