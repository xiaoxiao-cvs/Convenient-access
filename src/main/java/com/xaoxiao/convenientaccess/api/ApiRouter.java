package com.xaoxiao.convenientaccess.api;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.config.ConfigManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API路由器（简化版）
 * 处理白名单管理和用户注册相关的API路由
 */
public class ApiRouter extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(ApiRouter.class);
    
    private final WhitelistApiController whitelistController;
    private final UserApiController userController;
    private final PlayerDataApiController playerDataController;
    private AdminAuthController adminAuthController;
    private final ConfigManager configManager;
    
    public ApiRouter(WhitelistApiController whitelistController, UserApiController userController, 
                     PlayerDataApiController playerDataController, AdminAuthController adminAuthController,
                     ConfigManager configManager) {
        this.whitelistController = whitelistController;
        this.userController = userController;
        this.playerDataController = playerDataController;
        this.adminAuthController = adminAuthController;
        this.configManager = configManager;
    }
    
    /**
     * 设置管理员认证控制器（用于延迟初始化）
     */
    public void setAdminAuthController(AdminAuthController adminAuthController) {
        this.adminAuthController = adminAuthController;
    }
    
    /**
     * 验证API请求的认证
     */
    private boolean isAuthenticated(HttpServletRequest request, String path) {
        // 如果认证被禁用，直接通过
        if (!configManager.isAuthEnabled()) {
            return true;
        }
        
        // 公开的端点不需要认证
        if (isPublicEndpoint(path)) {
            return true;
        }
        
        // 检查JWT Token (用于管理员已登录的请求)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jwtToken = authHeader.substring(7);
            // 验证JWT token
            if (adminAuthController != null) {
                try {
                    com.xaoxiao.convenientaccess.auth.AdminUser user = 
                        adminAuthController.getAdminAuthService().validateToken(jwtToken);
                    if (user != null) {
                        // JWT token 有效
                        request.setAttribute("currentUser", user);
                        return true;
                    }
                } catch (Exception e) {
                    logger.debug("JWT token validation failed: {}", e.getMessage());
                }
            }
        }
        
        // 检查API Token (用于非管理员的API访问)
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null) {
            String validToken = configManager.getApiToken();
            if (validToken != null && validToken.equals(apiKey)) {
                return true;
            }
        }
        
        // 对于管理员端点,检查管理员密码 (用于生成注册token等操作)
        if (isAdminEndpoint(path)) {
            String adminPassword = request.getHeader("X-Admin-Password");
            if (adminPassword != null) {
                String validPassword = configManager.getAdminPassword();
                return validPassword != null && validPassword.equals(adminPassword);
            }
        }
        
        return false;
    }
    
    /**
     * 判断是否为公开端点（不需要认证）
     */
    private boolean isPublicEndpoint(String path) {
        return path.equals("/api/v1/admin/login") || 
               path.equals("/api/v1/admin/register") ||
               path.equals("/api/v1/admin/generate-token");
    }
    
    /**
     * 判断是否为管理员端点
     */
    private boolean isAdminEndpoint(String path) {
        return path.startsWith("/api/v1/admin/");
    }
    
    /**
     * 发送认证失败响应
     */
    private void sendAuthFailedResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"error\":\"Unauthorized: Invalid API key or admin password\"}");
        response.getWriter().flush();
    }
    
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("PUT request to: {}", path);
        
        // 目前没有PUT请求的路由
        send405Response(response, "Method not allowed for this endpoint");
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("GET request to: {}", path);
        
        // 检查认证
        if (!isAuthenticated(request, path)) {
            sendAuthFailedResponse(response);
            return;
        }
        
        try {
            // 白名单相关路由
            if (path.startsWith("/api/v1/whitelist")) {
                if (path.equals("/api/v1/whitelist")) {
                    whitelistController.handleGetWhitelist(request, response);
                } else if (path.equals("/api/v1/whitelist/stats")) {
                    whitelistController.handleGetStats(request, response);
                } else if (path.equals("/api/v1/whitelist/sync/status")) {
                    whitelistController.handleGetSyncStatus(request, response);
                } else {
                    send404Response(response, "Endpoint not found");
                }
            }
            // 玩家数据查询路由（使用查询参数）
            else if (path.equals("/api/v1/player")) {
                playerDataController.handleGetPlayerData(request, response);
            }
            // 管理员信息查询路由
            else if (path.equals("/api/v1/admin/me")) {
                if (adminAuthController != null) {
                    adminAuthController.handleGetCurrentUser(request, response);
                } else {
                    send503Response(response, "Admin authentication service not available");
                }
            }
            else {
                send404Response(response, "API endpoint not found");
            }
        } catch (Exception e) {
            logger.error("Error handling GET request to {}", path, e);
            send500Response(response, "Internal server error");
        }
    }
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("POST request to: {}", path);
        
        // 检查认证
        if (!isAuthenticated(request, path)) {
            sendAuthFailedResponse(response);
            return;
        }
        
        try {
            // 白名单相关路由
             if (path.startsWith("/api/v1/whitelist")) {
                 if (path.equals("/api/v1/whitelist")) {
                     whitelistController.handleAddPlayer(request, response);
                 } else if (path.equals("/api/v1/whitelist/batch")) {
                     whitelistController.handleBatchOperation(request, response);
                 } else if (path.equals("/api/v1/whitelist/sync")) {
                     whitelistController.handleTriggerSync(request, response);
                 } else {
                     send404Response(response, "Endpoint not found");
                 }
             }
             // 管理员认证路由
             else if (path.equals("/api/v1/admin/login")) {
                 if (adminAuthController != null) {
                     adminAuthController.handleLogin(request, response);
                 } else {
                     send503Response(response, "Admin authentication service not available");
                 }
             }
             else if (path.equals("/api/v1/admin/register")) {
                 if (adminAuthController != null) {
                     adminAuthController.handleRegister(request, response);
                 } else {
                     send503Response(response, "Admin authentication service not available");
                 }
             }
             // 令牌生成路由（简化版，需要管理员密码验证）
             else if (path.equals("/api/v1/admin/generate-token")) {
                 userController.handleGenerateToken(request, response);
             }
            else {
                send404Response(response, "API endpoint not found");
            }
        } catch (Exception e) {
            logger.error("Error handling POST request to {}", path, e);
            send500Response(response, "Internal server error");
        }
    }
    
    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("DELETE request to: {}", path);
        
        // 检查认证
        if (!isAuthenticated(request, path)) {
            sendAuthFailedResponse(response);
            return;
        }
        
        try {
             // 白名单删除路由
             if (path.startsWith("/api/v1/whitelist/")) {
                 String uuid = path.substring("/api/v1/whitelist/".length());
                 whitelistController.handleRemovePlayer(request, response, uuid);
             } else {
                 send404Response(response, "Endpoint not found");
             }
        } catch (Exception e) {
            logger.error("Error handling DELETE request to {}", path, e);
            send500Response(response, "Internal server error");
        }
    }
    
    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // 设置CORS头
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key, X-Admin-Password");
        response.setHeader("Access-Control-Max-Age", "3600");
        response.setStatus(HttpServletResponse.SC_OK);
    }
    
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String method = request.getMethod();
        
        switch (method.toUpperCase()) {
            case "GET":
                doGet(request, response);
                break;
            case "POST":
                doPost(request, response);
                break;
            case "PUT":
                doPut(request, response);
                break;
            case "DELETE":
                doDelete(request, response);
                break;
            case "OPTIONS":
                doOptions(request, response);
                break;
            default:
                send405Response(response, "Method not supported: " + method);
        }
    }
    
    private void send405Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String jsonResponse = String.format(
            "{\"success\": false, \"error\": {\"code\": 405, \"message\": \"%s\"}, \"timestamp\": %d}",
            message, System.currentTimeMillis()
        );
        
        response.getWriter().write(jsonResponse);
    }
    
    private void send404Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String jsonResponse = String.format(
            "{\"success\": false, \"error\": {\"code\": 404, \"message\": \"%s\"}, \"timestamp\": %d}",
            message, System.currentTimeMillis()
        );
        
        response.getWriter().write(jsonResponse);
    }
    
    private void send500Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String jsonResponse = String.format(
            "{\"success\": false, \"error\": {\"code\": 500, \"message\": \"%s\"}, \"timestamp\": %d}",
            message, System.currentTimeMillis()
        );
        
        response.getWriter().write(jsonResponse);
    }
    
    private void send503Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String jsonResponse = String.format(
            "{\"success\": false, \"error\": {\"code\": 503, \"message\": \"%s\"}, \"timestamp\": %d}",
            message, System.currentTimeMillis()
        );
        
        response.getWriter().write(jsonResponse);
    }
}