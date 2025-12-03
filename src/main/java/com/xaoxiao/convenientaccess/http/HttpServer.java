package com.xaoxiao.convenientaccess.http;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import com.xaoxiao.convenientaccess.api.ApiManager;
import com.xaoxiao.convenientaccess.api.ApiRouter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP服务器
 * 使用Jetty提供RESTful API服务
 */
public class HttpServer {
    
    private final ConvenientAccessPlugin plugin;
    private final ApiManager apiManager;
    private final ApiRouter apiRouter;
    private Server server;
    
    public HttpServer(ConvenientAccessPlugin plugin, ApiManager apiManager) {
        this.plugin = plugin;
        this.apiManager = apiManager;
        // 从白名单系统获取ApiRouter
        this.apiRouter = plugin.getWhitelistSystem() != null ? 
            plugin.getWhitelistSystem().getApiRouter() : null;
    }
    
    /**
     * 启动HTTP服务器
     */
    public void start() throws Exception {
        int port = plugin.getConfigManager().getHttpPort();
        String host = plugin.getConfigManager().getHttpHost();
        int maxThreads = plugin.getConfigManager().getMaxThreads();
        
        // 创建线程池
        QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, 2);
        threadPool.setName("ConvenientAccess-HTTP");
        
        // 创建服务器
        server = new Server(threadPool);
        server.setHandler(new ApiHandler());
        
        // 配置连接器
        org.eclipse.jetty.server.ServerConnector connector = 
            new org.eclipse.jetty.server.ServerConnector(server);
        connector.setHost(host);
        connector.setPort(port);
        connector.setIdleTimeout(plugin.getConfigManager().getTimeout());
        
        server.addConnector(connector);
        
        // 启动服务器
        server.start();
        
        plugin.getLogger().info(String.format("HTTP服务器已启动: http://%s:%d", 
            "0.0.0.0".equals(host) ? "localhost" : host, port));
    }
    
    /**
     * 停止HTTP服务器
     */
    public void stop() {
        if (server != null) {
            try {
                server.stop();
                plugin.getLogger().info("HTTP服务器已停止");
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "停止HTTP服务器时发生错误", e);
            }
        }
    }
    
    /**
     * 检查服务器是否正在运行
     */
    public boolean isRunning() {
        return server != null && server.isRunning();
    }
    
    /**
     * API请求处理器
     */
    private class ApiHandler extends AbstractHandler {
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, 
                          HttpServletResponse response) throws IOException {
            
            baseRequest.setHandled(true);
            
            try {
                // 设置CORS头
                setCorsHeaders(response);
                
                // 处理OPTIONS预检请求
                if ("OPTIONS".equals(request.getMethod())) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
                
                // 获取请求信息
                String path = request.getRequestURI();
                String method = request.getMethod();
                String clientIp = getClientIp(request);
                Map<String, String> headers = getHeaders(request);
                
                // 判断是否为白名单或管理员API
                if (isWhitelistOrAdminApi(path) && apiRouter != null) {
                    // 使用ApiRouter处理白名单和管理员API
                    handleWithApiRouter(request, response);
                } else {
                    // 使用ApiManager处理其他API
                    handleWithApiManager(path, method, clientIp, headers, response);
                }
                    
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "处理HTTP请求时发生错误", e);
                
                try {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"success\":false,\"error\":\"Internal Server Error\"}");
                    response.getWriter().flush();
                } catch (IOException ioException) {
                    plugin.getLogger().log(Level.SEVERE, "写入错误响应时发生异常", ioException);
                }
            }
        }
        
        /**
         * 判断是否为白名单、用户注册、玩家数据或管理员API
         */
        private boolean isWhitelistOrAdminApi(String path) {
            return path.startsWith("/api/v1/whitelist") || 
                   path.startsWith("/api/v1/admin") ||
                   path.startsWith("/api/v1/register") ||
                   path.startsWith("/api/v1/logs") ||
                   path.equals("/api/v1/player");
        }
        
        /**
         * 使用ApiRouter处理请求
         */
        private void handleWithApiRouter(HttpServletRequest request, HttpServletResponse response) 
                throws IOException {
            try {
                apiRouter.handleRequest(request, response);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "ApiRouter处理请求时发生错误", e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"success\":false,\"error\":\"Internal Server Error\"}");
                response.getWriter().flush();
            }
        }
        
        /**
         * 使用ApiManager处理请求
         */
        private void handleWithApiManager(String path, String method, String clientIp, 
                Map<String, String> headers, HttpServletResponse response) throws IOException {
            
            apiManager.handleRequest(path, method, clientIp, headers)
                .thenAccept(apiResponse -> {
                    try {
                        // 设置响应
                        response.setStatus(apiResponse.getStatusCode());
                        response.setContentType(apiResponse.getContentType());
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write(apiResponse.getBody());
                        response.getWriter().flush();
                        
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.WARNING, "写入HTTP响应时发生错误", e);
                    }
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.SEVERE, "处理API请求时发生异常", throwable);
                    try {
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write("{\"success\":false,\"error\":\"Internal Server Error\"}");
                        response.getWriter().flush();
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.SEVERE, "写入错误响应时发生异常", e);
                    }
                    return null;
                })
                .join(); // 等待异步操作完成
        }
        
        /**
         * 设置CORS头
         */
        private void setCorsHeaders(HttpServletResponse response) {
            if (plugin.getConfigManager().isCorsEnabled()) {
                // 获取允许的源
                String allowedOrigins = String.join(",", plugin.getConfigManager().getAllowedOrigins());
                if (allowedOrigins.contains("*")) {
                    response.setHeader("Access-Control-Allow-Origin", "*");
                } else {
                    response.setHeader("Access-Control-Allow-Origin", allowedOrigins);
                }
                
                response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers", 
                    "Content-Type, Authorization, X-API-Key, X-Requested-With");
                response.setHeader("Access-Control-Max-Age", "3600");
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
         * 获取请求头
         */
        private Map<String, String> getHeaders(HttpServletRequest request) {
            Map<String, String> headers = new HashMap<>();
            
            java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                String headerValue = request.getHeader(headerName);
                headers.put(headerName, headerValue);
            }
            
            return headers;
        }
    }
}