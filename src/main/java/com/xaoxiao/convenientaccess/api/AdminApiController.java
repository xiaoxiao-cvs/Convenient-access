package com.xaoxiao.convenientaccess.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xaoxiao.convenientaccess.auth.AdminAuthManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * 管理员API控制器
 * 处理管理员认证相关的API请求
 */
public class AdminApiController {
    private static final Logger logger = LoggerFactory.getLogger(AdminApiController.class);
    
    private final AdminAuthManager adminAuthManager;
    private final Gson gson;
    
    public AdminApiController(AdminAuthManager adminAuthManager) {
        this.adminAuthManager = adminAuthManager;
        this.gson = new Gson();
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
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少用户名或密码"));
                return;
            }
            
            String username = json.get("username").getAsString();
            String password = json.get("password").getAsString();
            String clientIp = getClientIp(request);
            String userAgent = request.getHeader("User-Agent");
            
            // 参数验证
            if (username.trim().isEmpty() || password.trim().isEmpty()) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("用户名和密码不能为空"));
                return;
            }
            
            // 执行登录
            adminAuthManager.login(username, password, clientIp, userAgent)
                .thenAccept(loginResult -> {
                    if (loginResult.isSuccess()) {
                        // 登录成功
                        JsonObject responseData = new JsonObject();
                        responseData.addProperty("sessionId", loginResult.getSession().getSessionId());
                        responseData.addProperty("token", loginResult.getSession().getJwtToken());
                        responseData.addProperty("username", loginResult.getSession().getUser().getUsername());
                        responseData.addProperty("role", loginResult.getSession().getUser().getRole().getRoleName());
                        responseData.addProperty("expiresAt", loginResult.getSession().getExpiresAt().toString());
                        
                        sendJsonResponse(response, 200, ApiResponse.success(responseData, "登录成功"));
                    } else {
                        // 登录失败
                        sendJsonResponse(response, 401, ApiResponse.unauthorized(loginResult.getMessage()));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("管理员登录失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("登录服务异常"));
                    return null;
                });
                
        } catch (Exception e) {
            logger.error("处理管理员登录请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理POST /api/v1/admin/logout - 管理员登出
     */
    public void handleLogout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String sessionId = extractSessionId(request);
            if (sessionId == null) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少会话ID"));
                return;
            }
            
            adminAuthManager.logout(sessionId)
                .thenAccept(success -> {
                    if (success) {
                        sendJsonResponse(response, 200, ApiResponse.success(null, "登出成功"));
                    } else {
                        sendJsonResponse(response, 500, ApiResponse.error("登出失败"));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("管理员登出失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("登出服务异常"));
                    return null;
                });
                
        } catch (Exception e) {
            logger.error("处理管理员登出请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理GET /api/v1/admin/session - 验证会话
     */
    public void handleValidateSession(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String sessionId = extractSessionId(request);
            if (sessionId == null) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少会话ID"));
                return;
            }
            
            String clientIp = getClientIp(request);
            
            adminAuthManager.validateSession(sessionId, clientIp)
                .thenAccept(validationResult -> {
                    if (validationResult.isValid()) {
                        // 会话有效
                        JsonObject responseData = new JsonObject();
                        responseData.addProperty("sessionId", validationResult.getSession().getSessionId());
                        responseData.addProperty("username", validationResult.getSession().getUser().getUsername());
                        responseData.addProperty("role", validationResult.getSession().getUser().getRole().getRoleName());
                        responseData.addProperty("expiresAt", validationResult.getSession().getExpiresAt().toString());
                        responseData.addProperty("lastActivity", validationResult.getSession().getLastActivity().toString());
                        
                        sendJsonResponse(response, 200, ApiResponse.success(responseData, "会话有效"));
                    } else {
                        // 会话无效
                        sendJsonResponse(response, 401, ApiResponse.unauthorized(validationResult.getMessage()));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("会话验证失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("会话验证服务异常"));
                    return null;
                });
                
        } catch (Exception e) {
            logger.error("处理会话验证请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理GET /api/v1/admin/profile - 获取管理员信息
     */
    public void handleGetProfile(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String sessionId = extractSessionId(request);
            if (sessionId == null) {
                sendJsonResponse(response, 401, ApiResponse.unauthorized("需要登录"));
                return;
            }
            
            String clientIp = getClientIp(request);
            
            adminAuthManager.validateSession(sessionId, clientIp)
                .thenAccept(validationResult -> {
                    if (validationResult.isValid()) {
                        AdminAuthManager.AdminSession session = validationResult.getSession();
                        AdminAuthManager.AdminUser user = session.getUser();
                        
                        JsonObject responseData = new JsonObject();
                        responseData.addProperty("id", user.getId());
                        responseData.addProperty("username", user.getUsername());
                        responseData.addProperty("email", user.getEmail());
                        responseData.addProperty("role", user.getRole().getRoleName());
                        responseData.addProperty("roleDisplayName", user.getRole().getDisplayName());
                        
                        sendJsonResponse(response, 200, ApiResponse.success(responseData));
                    } else {
                        sendJsonResponse(response, 401, ApiResponse.unauthorized(validationResult.getMessage()));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("获取管理员信息失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("服务异常"));
                    return null;
                });
                
        } catch (Exception e) {
            logger.error("处理获取管理员信息请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 提取会话ID
     */
    private String extractSessionId(HttpServletRequest request) {
        // 从Authorization头提取
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
        // 从X-Session-ID头提取
        String sessionHeader = request.getHeader("X-Session-ID");
        if (sessionHeader != null && !sessionHeader.trim().isEmpty()) {
            return sessionHeader.trim();
        }
        
        // 从查询参数提取
        String sessionParam = request.getParameter("sessionId");
        if (sessionParam != null && !sessionParam.trim().isEmpty()) {
            return sessionParam.trim();
        }
        
        return null;
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