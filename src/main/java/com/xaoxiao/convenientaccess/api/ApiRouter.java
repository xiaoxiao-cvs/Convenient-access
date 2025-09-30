package com.xaoxiao.convenientaccess.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * API路由器
 * 处理HTTP请求路由到相应的控制器
 */
public class ApiRouter extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(ApiRouter.class);
    
    private final WhitelistApiController whitelistController;
    private final AdminApiController adminController;
    
    public ApiRouter(WhitelistApiController whitelistController, AdminApiController adminController) {
        this.whitelistController = whitelistController;
        this.adminController = adminController;
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("处理GET请求: {}", path);
        
        try {
            switch (path) {
                case "/api/v1/whitelist":
                    whitelistController.handleGetWhitelist(request, response);
                    break;
                    
                case "/api/v1/whitelist/stats":
                    whitelistController.handleGetStats(request, response);
                    break;
                    
                case "/api/v1/whitelist/sync/status":
                    whitelistController.handleGetSyncStatus(request, response);
                    break;
                    
                case "/api/v1/admin/session":
                    adminController.handleValidateSession(request, response);
                    break;
                    
                case "/api/v1/admin/profile":
                    adminController.handleGetProfile(request, response);
                    break;
                    
                default:
                    send404Response(response, "API端点不存在: " + path);
            }
        } catch (Exception e) {
            logger.error("处理GET请求失败: {}", path, e);
            send500Response(response, "服务器内部错误");
        }
    }
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("处理POST请求: {}", path);
        
        try {
            switch (path) {
                case "/api/v1/whitelist":
                    whitelistController.handleAddPlayer(request, response);
                    break;
                    
                case "/api/v1/whitelist/batch":
                    whitelistController.handleBatchOperation(request, response);
                    break;
                    
                case "/api/v1/whitelist/sync":
                    whitelistController.handleTriggerSync(request, response);
                    break;
                    
                case "/api/v1/admin/login":
                    adminController.handleLogin(request, response);
                    break;
                    
                case "/api/v1/admin/logout":
                    adminController.handleLogout(request, response);
                    break;
                    
                default:
                    send404Response(response, "API端点不存在: " + path);
            }
        } catch (Exception e) {
            logger.error("处理POST请求失败: {}", path, e);
            send500Response(response, "服务器内部错误");
        }
    }
    
    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("处理DELETE请求: {}", path);
        
        try {
            if (path.startsWith("/api/v1/whitelist/")) {
                // 提取UUID参数
                String[] pathParts = path.split("/");
                if (pathParts.length >= 5) {
                    String uuid = pathParts[4];
                    whitelistController.handleRemovePlayer(request, response, uuid);
                } else {
                    send404Response(response, "缺少UUID参数");
                }
            } else {
                send404Response(response, "API端点不存在: " + path);
            }
        } catch (Exception e) {
            logger.error("处理DELETE请求失败: {}", path, e);
            send500Response(response, "服务器内部错误");
        }
    }
    
    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // 处理CORS预检请求
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-ID, X-API-Key");
        response.setHeader("Access-Control-Max-Age", "3600");
        response.setStatus(HttpServletResponse.SC_OK);
    }
    
    /**
     * 公共方法：处理请求
     */
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String method = request.getMethod();
        switch (method) {
            case "GET":
                doGet(request, response);
                break;
            case "POST":
                doPost(request, response);
                break;
            case "DELETE":
                doDelete(request, response);
                break;
            case "OPTIONS":
                doOptions(request, response);
                break;
            default:
                send405Response(response, "不支持的请求方法: " + method);
        }
    }
    
    /**
     * 发送405响应
     */
    private void send405Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(405);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        ApiResponse<Object> apiResponse = ApiResponse.error(message);
        response.getWriter().write(apiResponse.toString());
    }
    
    /**
     * 发送404响应
     */
    private void send404Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(404);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        ApiResponse<Object> apiResponse = ApiResponse.notFound(message);
        response.getWriter().write(apiResponse.toString());
    }
    
    /**
     * 发送500响应
     */
    private void send500Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(500);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        ApiResponse<Object> apiResponse = ApiResponse.error(message);
        response.getWriter().write(apiResponse.toString());
    }
}