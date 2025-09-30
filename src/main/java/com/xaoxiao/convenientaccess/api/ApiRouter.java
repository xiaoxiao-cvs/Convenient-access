package com.xaoxiao.convenientaccess.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * API路由器（简化版）
 * 处理白名单管理和用户注册相关的API路由
 */
public class ApiRouter extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(ApiRouter.class);
    
    private final WhitelistApiController whitelistController;
    private final UserApiController userController;
    
    public ApiRouter(WhitelistApiController whitelistController, UserApiController userController) {
        this.whitelistController = whitelistController;
        this.userController = userController;
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
            } else {
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
             // 用户注册路由
             else if (path.equals("/api/v1/register")) {
                 userController.handleRegister(request, response);
             }
             // 令牌生成路由（简化版，需要管理员密码验证）
             else if (path.equals("/api/v1/admin/generate-token")) {
                 // TODO: 需要在UserApiController中添加handleGenerateToken方法
                 send404Response(response, "Token generation not implemented yet");
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
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Admin-Password");
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
}