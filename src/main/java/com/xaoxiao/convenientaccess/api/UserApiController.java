package com.xaoxiao.convenientaccess.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xaoxiao.convenientaccess.auth.RegistrationTokenManager;
import com.xaoxiao.convenientaccess.whitelist.WhitelistEntry;
import com.xaoxiao.convenientaccess.whitelist.WhitelistManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 用户API控制器
 * 处理用户注册相关的API请求
 */
public class UserApiController {
    private static final Logger logger = LoggerFactory.getLogger(UserApiController.class);
    
    private final RegistrationTokenManager tokenManager;
    private final WhitelistManager whitelistManager;
    private final Gson gson;
    
    // 玩家名称验证正则
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
    
    // 管理员密码（从WhitelistSystem获取）
    private String adminPassword;
    
    public UserApiController(RegistrationTokenManager tokenManager, WhitelistManager whitelistManager) {
        this.tokenManager = tokenManager;
        this.whitelistManager = whitelistManager;
        this.gson = new Gson();
    }
    
    /**
     * 设置管理员密码
     */
    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }
    
    /**
     * 处理POST /api/v1/admin/generate-token - 生成注册令牌
     */
    public void handleGenerateToken(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 验证管理员密码
            String providedPassword = request.getHeader("X-Admin-Password");
            if (providedPassword == null || providedPassword.trim().isEmpty()) {
                sendJsonResponse(response, 401, ApiResponse.unauthorized("缺少管理员密码"));
                return;
            }
            
            if (adminPassword == null || !adminPassword.equals(providedPassword.trim())) {
                sendJsonResponse(response, 401, ApiResponse.unauthorized("管理员密码错误"));
                return;
            }
            
            // 读取请求体（可选参数）
            String requestBody = readRequestBody(request);
            JsonObject json = null;
            int expiryHours = 24; // 默认24小时
            
            if (!requestBody.isEmpty()) {
                try {
                    json = JsonParser.parseString(requestBody).getAsJsonObject();
                    if (json.has("expiryHours")) {
                        expiryHours = json.get("expiryHours").getAsInt();
                        if (expiryHours <= 0 || expiryHours > 168) { // 最大7天
                            sendJsonResponse(response, 400, ApiResponse.badRequest("过期时间必须在1-168小时之间"));
                            return;
                        }
                    }
                } catch (Exception e) {
                    logger.warn("解析请求体失败，使用默认参数", e);
                }
            }
            
            final int finalExpiryHours = expiryHours; // 为lambda表达式创建final变量
            
            // 生成注册令牌（同步等待结果）
            try {
                String token = tokenManager.generateRegistrationToken(finalExpiryHours).get();
                
                if (token != null) {
                    JsonObject responseData = new JsonObject();
                    responseData.addProperty("token", token);
                    responseData.addProperty("expiryHours", finalExpiryHours);
                    responseData.addProperty("message", "令牌生成成功");
                    
                    sendJsonResponse(response, 200, ApiResponse.success(responseData, "令牌生成成功"));
                    logger.info("管理员生成注册令牌成功，过期时间: {}小时", finalExpiryHours);
                } else {
                    sendJsonResponse(response, 500, ApiResponse.error("令牌生成失败"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("生成注册令牌被中断", e);
                sendJsonResponse(response, 500, ApiResponse.error("令牌生成被中断"));
            } catch (java.util.concurrent.ExecutionException e) {
                logger.error("生成注册令牌失败", e);
                sendJsonResponse(response, 500, ApiResponse.error("令牌生成服务异常: " + e.getMessage()));
            }
                
        } catch (Exception e) {
            logger.error("处理令牌生成请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理POST /api/v1/register - 用户注册
     */
    public void handleRegister(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 读取请求体
            String requestBody = readRequestBody(request);
            JsonObject json = JsonParser.parseString(requestBody).getAsJsonObject();
            
            // 参数验证
            if (!json.has("token") || !json.has("playerName") || !json.has("playerUuid")) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必要参数: token, playerName, playerUuid"));
                return;
            }
            
            String token = json.get("token").getAsString().trim();
            String playerName = json.get("playerName").getAsString().trim();
            String playerUuid = json.get("playerUuid").getAsString().trim();
            String clientIp = getClientIp(request);
            
            // 验证参数格式
            if (token.isEmpty() || playerName.isEmpty() || playerUuid.isEmpty()) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("参数不能为空"));
                return;
            }
            
            if (!isValidPlayerName(playerName)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名称格式无效（3-16位字母数字下划线）"));
                return;
            }
            
            if (!isValidUuid(playerUuid)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("UUID格式无效"));
                return;
            }
            
            // 验证注册令牌（同步等待结果）
            try {
                var validationResult = tokenManager.validateToken(token, clientIp).get();
                
                if (!validationResult.isValid()) {
                    sendJsonResponse(response, 400, ApiResponse.badRequest(validationResult.getMessage()));
                    return;
                }
                
                // 检查玩家是否已在白名单中
                boolean isWhitelisted = whitelistManager.isPlayerWhitelisted(playerUuid).get();
                if (isWhitelisted) {
                    sendJsonResponse(response, 409, ApiResponse.error("玩家已在白名单中"));
                    return;
                }
                
                // 添加玩家到白名单
                boolean addSuccess = whitelistManager.addPlayer(
                    playerName, 
                    playerUuid, 
                    "SYSTEM", 
                    "00000000-0000-0000-0000-000000000000", 
                    WhitelistEntry.Source.SYSTEM
                ).get();
                
                if (!addSuccess) {
                    sendJsonResponse(response, 500, ApiResponse.error("添加到白名单失败"));
                    return;
                }
                
                // 标记令牌为已使用
                try {
                    boolean markSuccess = tokenManager.markTokenAsUsed(validationResult.getTokenId(), clientIp).get();
                    if (!markSuccess) {
                        logger.warn("标记令牌为已使用失败，但玩家已添加到白名单");
                    }
                } catch (Exception e) {
                    logger.error("标记令牌为已使用失败", e);
                }
                
                // 发送成功响应
                JsonObject responseData = new JsonObject();
                responseData.addProperty("playerName", playerName);
                responseData.addProperty("playerUuid", playerUuid);
                responseData.addProperty("message", "注册成功，已添加到白名单");
                
                sendJsonResponse(response, 200, ApiResponse.success(responseData, "注册成功"));
                logger.info("用户注册成功: {} ({})", playerName, playerUuid);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("用户注册被中断", e);
                sendJsonResponse(response, 500, ApiResponse.error("注册被中断"));
            } catch (java.util.concurrent.ExecutionException e) {
                logger.error("用户注册失败", e);
                sendJsonResponse(response, 500, ApiResponse.error("注册服务异常: " + e.getMessage()));
            }
                
        } catch (Exception e) {
            logger.error("处理用户注册请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 验证玩家名称格式
     */
    private boolean isValidPlayerName(String name) {
        return name != null && PLAYER_NAME_PATTERN.matcher(name).matches();
    }
    
    /**
     * 验证UUID格式
     */
    private boolean isValidUuid(String uuid) {
        try {
            UUID.fromString(uuid);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    
    /**
     * 获取客户端IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
    
    /**
     * 读取请求体
     */
    private String readRequestBody(HttpServletRequest request) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        return body.toString();
    }
    
    /**
     * 发送JSON响应
     */
    private void sendJsonResponse(HttpServletResponse response, int statusCode, ApiResponse<?> apiResponse) {
        try {
            response.setStatus(statusCode);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            // 设置CORS头
            response.setHeader("Access-Control-Allow-Origin", "*");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-ID");
            
            response.getWriter().write(gson.toJson(apiResponse));
            response.getWriter().flush();
        } catch (IOException e) {
            logger.error("发送JSON响应失败", e);
        }
    }
}