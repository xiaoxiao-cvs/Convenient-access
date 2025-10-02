package com.xaoxiao.convenientaccess.api;

import java.io.BufferedReader;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xaoxiao.convenientaccess.auth.AdminAuthService;
import com.xaoxiao.convenientaccess.auth.AdminUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 管理员认证API控制器
 */
public class AdminAuthController {
    private static final Logger logger = LoggerFactory.getLogger(AdminAuthController.class);
    
    private final AdminAuthService authService;
    private final Gson gson;
    
    public AdminAuthController(AdminAuthService authService) {
        this.authService = authService;
        this.gson = new Gson();
    }
    
    /**
     * 获取认证服务（用于JWT验证）
     */
    public AdminAuthService getAdminAuthService() {
        return authService;
    }
    
    /**
     * 处理POST /api/v1/admin/login - 管理员登录
     */
    public void handleLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 读取请求体
            String requestBody = readRequestBody(request);
            JsonObject json = JsonParser.parseString(requestBody).getAsJsonObject();
            
            // 参数验证
            if (!json.has("username") || !json.has("password")) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必要参数: username, password"));
                return;
            }
            
            String username = json.get("username").getAsString().trim();
            String password = json.get("password").getAsString();
            String clientIp = getClientIp(request);
            
            // 验证参数
            if (username.isEmpty() || password.isEmpty()) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("用户名和密码不能为空"));
                return;
            }
            
            // 执行登录
            var loginResult = authService.login(username, password, clientIp);
            
            if (!loginResult.isSuccess()) {
                sendJsonResponse(response, 401, ApiResponse.error(loginResult.getMessage()));
                return;
            }
            
            // 构建响应数据
            JsonObject responseData = new JsonObject();
            responseData.addProperty("token", loginResult.getToken());
            
            AdminUser user = loginResult.getUser();
            JsonObject userInfo = new JsonObject();
            userInfo.addProperty("id", user.getId());
            userInfo.addProperty("username", user.getUsername());
            userInfo.addProperty("displayName", user.getDisplayName());
            userInfo.addProperty("isSuperAdmin", user.isSuperAdmin());
            userInfo.addProperty("isAdmin", true);
            responseData.add("user", userInfo);
            
            sendJsonResponse(response, 200, ApiResponse.success(responseData, "登录成功"));
            logger.info("管理员登录成功: {}", username);
            
        } catch (Exception e) {
            logger.error("处理登录请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理POST /api/v1/admin/register - 管理员注册
     */
    public void handleRegister(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 读取请求体
            String requestBody = readRequestBody(request);
            JsonObject json = JsonParser.parseString(requestBody).getAsJsonObject();
            
            // 参数验证
            if (!json.has("username") || !json.has("password") || !json.has("token")) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必要参数: username, password, token"));
                return;
            }
            
            String username = json.get("username").getAsString().trim();
            String password = json.get("password").getAsString();
            String token = json.get("token").getAsString().trim();
            String displayName = json.has("displayName") ? json.get("displayName").getAsString().trim() : username;
            String clientIp = getClientIp(request);
            
            // 验证参数
            if (username.isEmpty() || password.isEmpty() || token.isEmpty()) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("用户名、密码和注册令牌不能为空"));
                return;
            }
            
            // 验证用户名格式（3-20位字母数字下划线）
            if (!username.matches("^[a-zA-Z0-9_]{3,20}$")) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("用户名格式无效（3-20位字母数字下划线）"));
                return;
            }
            
            // 验证密码长度
            if (password.length() < 6 || password.length() > 50) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("密码长度必须在6-50位之间"));
                return;
            }
            
            // 执行注册
            var registerResult = authService.register(username, password, displayName, token, clientIp);
            
            if (!registerResult.isSuccess()) {
                sendJsonResponse(response, 400, ApiResponse.badRequest(registerResult.getMessage()));
                return;
            }
            
            // 构建响应数据
            JsonObject responseData = new JsonObject();
            responseData.addProperty("username", username);
            responseData.addProperty("message", registerResult.getMessage());
            
            sendJsonResponse(response, 200, ApiResponse.success(responseData, "注册成功"));
            logger.info("管理员注册成功: {}", username);
            
        } catch (Exception e) {
            logger.error("处理注册请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理GET /api/v1/admin/me - 获取当前管理员信息
     */
    public void handleGetCurrentUser(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 从请求头获取token
            String token = extractToken(request);
            if (token == null) {
                sendJsonResponse(response, 401, ApiResponse.error("未提供认证token"));
                return;
            }
            
            // 验证token
            AdminUser user = authService.validateToken(token);
            if (user == null) {
                sendJsonResponse(response, 401, ApiResponse.error("认证失败或token已过期"));
                return;
            }
            
            // 构建响应数据
            JsonObject userInfo = new JsonObject();
            userInfo.addProperty("id", user.getId());
            userInfo.addProperty("username", user.getUsername());
            userInfo.addProperty("displayName", user.getDisplayName());
            userInfo.addProperty("email", user.getEmail());
            userInfo.addProperty("isSuperAdmin", user.isSuperAdmin());
            userInfo.addProperty("isAdmin", true);
            
            sendJsonResponse(response, 200, ApiResponse.success(userInfo, "获取成功"));
            
        } catch (Exception e) {
            logger.error("获取当前用户信息失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 从请求中提取JWT token
     */
    private String extractToken(HttpServletRequest request) {
        // 从Authorization头获取
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
        // 从X-Auth-Token头获取
        String tokenHeader = request.getHeader("X-Auth-Token");
        if (tokenHeader != null && !tokenHeader.isEmpty()) {
            return tokenHeader;
        }
        
        return null;
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
     * 获取客户端IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
    
    /**
     * 发送JSON响应
     */
    private void sendJsonResponse(HttpServletResponse response, int status, Object data) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(data));
    }
}
