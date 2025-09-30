package com.xaoxiao.convenientaccess.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API路由器
 * 处理HTTP请求路由分发
 */
public class ApiRouter extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(ApiRouter.class);
    
    private final WhitelistApiController whitelistController;
    
    // 路由模式
    private static final Pattern WHITELIST_UUID_PATTERN = Pattern.compile("^/api/v1/whitelist/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");
    
    public ApiRouter(WhitelistApiController whitelistController) {
        this.whitelistController = whitelistController;
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // 设置CORS头
        setCorsHeaders(response);
        
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
                    
                default:
                    sendNotFound(response);
                    break;
            }
        } catch (Exception e) {
            logger.error("处理GET请求失败: {}", path, e);
            sendInternalError(response);
        }
    }
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // 设置CORS头
        setCorsHeaders(response);
        
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
                    
                case "/api/v1/whitelist/sync":
                    whitelistController.handleTriggerSync(request, response);
                    break;
                    
                default:
                    sendNotFound(response);
                    break;
            }
        } catch (Exception e) {
            logger.error("处理POST请求失败: {}", path, e);
            sendInternalError(response);
        }
    }
    
    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // 设置CORS头
        setCorsHeaders(response);
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("处理DELETE请求: {}", path);
        
        try {
            // 匹配 /api/v1/whitelist/{uuid} 模式
            Matcher matcher = WHITELIST_UUID_PATTERN.matcher(path);
            if (matcher.matches()) {
                String uuid = matcher.group(1);
                whitelistController.handleRemovePlayer(request, response, uuid);
            } else {
                sendNotFound(response);
            }
        } catch (Exception e) {
            logger.error("处理DELETE请求失败: {}", path, e);
            sendInternalError(response);
        }
    }
    
    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // 处理CORS预检请求
        setCorsHeaders(response);
        response.setStatus(HttpServletResponse.SC_OK);
    }
    
    /**
     * 设置CORS头
     */
    private void setCorsHeaders(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Version");
        response.setHeader("Access-Control-Max-Age", "3600");
    }
    
    /**
     * 发送404响应
     */
    private void sendNotFound(HttpServletResponse response) throws IOException {
        response.setStatus(404);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"error\":\"API端点未找到\",\"code\":404}");
    }
    
    /**
     * 发送500响应
     */
    private void sendInternalError(HttpServletResponse response) throws IOException {
        response.setStatus(500);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"error\":\"服务器内部错误\",\"code\":500}");
    }
}