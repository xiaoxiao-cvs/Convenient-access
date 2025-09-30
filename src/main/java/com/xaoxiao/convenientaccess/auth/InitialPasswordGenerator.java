package com.xaoxiao.convenientaccess.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Random;

/**
 * 初始管理员密码生成器
 * 在系统首次启动时生成管理员密码
 */
public class InitialPasswordGenerator {
    private static final Logger logger = LoggerFactory.getLogger(InitialPasswordGenerator.class);
    
    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL_CHARS = "!@#$%^&*";
    private static final String ALL_CHARS = UPPERCASE + LOWERCASE + DIGITS + SPECIAL_CHARS;
    
    private static final int PASSWORD_LENGTH = 12;
    private final SecureRandom secureRandom;
    
    public InitialPasswordGenerator() {
        this.secureRandom = new SecureRandom();
    }
    
    /**
     * 生成初始管理员密码
     */
    public String generatePassword() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        
        // 确保密码包含各种字符类型
        password.append(getRandomChar(UPPERCASE));
        password.append(getRandomChar(LOWERCASE));
        password.append(getRandomChar(DIGITS));
        password.append(getRandomChar(SPECIAL_CHARS));
        
        // 填充剩余长度
        for (int i = 4; i < PASSWORD_LENGTH; i++) {
            password.append(getRandomChar(ALL_CHARS));
        }
        
        // 打乱字符顺序
        return shuffleString(password.toString());
    }
    
    /**
     * 从字符集中随机选择一个字符
     */
    private char getRandomChar(String charset) {
        return charset.charAt(secureRandom.nextInt(charset.length()));
    }
    
    /**
     * 打乱字符串顺序
     */
    private String shuffleString(String input) {
        char[] chars = input.toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = secureRandom.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
    }
    
    /**
     * 显示初始密码到控制台
     */
    public void displayPassword(String password) {
        logger.info("=".repeat(60));
        logger.info("ConvenientAccess 初始管理员密码");
        logger.info("=".repeat(60));
        logger.info("管理员密码: {}", password);
        logger.info("请妥善保存此密码，用于生成注册令牌");
        logger.info("=".repeat(60));
    }
}