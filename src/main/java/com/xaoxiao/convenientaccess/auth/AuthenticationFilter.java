package com.xaoxiao.convenientaccess.auth;

import com.google.gson.Gson;
import com.xaoxiao.convenientaccess.api.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 认证过滤器
 * 处理API请求的认证和授权
 */
public class AuthenticationFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationFilter.class);
    
    private final ApiKeyManager apiKeyManager;
    private final Gson gson;
    
    // 不需要认证的路径
    private static final String[] PUBLIC_PATHS = {
        "/api/v1/server/info",
        "/api/v1/health"
    };
    
    public AuthenticationFilter(ApiKeyManager apiKeyManager) {
        this.apiKeyManager = apiKeyManager;
        this.gson = new Gson();
    }
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        logger.info("认证过滤器初始化完成");
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        String path = httpRequest.getPathInfo();
        if (path == null) {
            path = httpRequest.getServletPath();
        }
        
        // 检查是否为公开路径
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }
        
        // 处理OPTIONS请求（CORS预检）
        if ("OPTIONS".equals(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        
        // 提取认证信息
        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendUnauthorizedResponse(httpResponse, "缺少Authorization头");
            return;
        }
        
        String apiKey = authHeader.substring(7); // 移除 "Bearer " 前缀
        String clientIp = getClientIp(httpRequest);
        
        // 验证API Key
        try {
            CompletableFuture<ApiKeyManager.ApiKeyValidationResult> validationFuture = 
                apiKeyManager.validateApiKey(apiKey, clientIp);
            
            ApiKeyManager.ApiKeyValidationResult result = validationFuture.get();
            
            if (!result.isValid()) {
                sendUnauthorizedResponse(httpResponse, result.getErrorMessage());
                return;
            }
            
            // 将API Key信息添加到请求属性中
            httpRequest.setAttribute("apiKeyInfo", result.getKeyInfo());
            httpRequest.setAttribute("clientIp", clientIp);
            
            // 继续处理请求
            chain.doFilter(request, response);
            
        } catch (Exception e) {
            logger.error("API Key验证失败", e);
            sendInternalErrorResponse(httpResponse, "认证服务异常");
        }
    }
    
    @Override
    public void destroy() {
        logger.info("认证过滤器已销毁");
    }
    
    /**
     * 检查是否为公开路径
     */
    private boolean isPublicPath(String path) {
        for (String publicPath : PUBLIC_PATHS) {
            if (path.startsWith(publicPath)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 获取客户端IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        // 检查代理头
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // 取第一个IP地址
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
    
    /**
     * 发送未授权响应
     */
    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        ApiResponse<Object> apiResponse = ApiResponse.unauthorized(message);
        response.getWriter().write(gson.toJson(apiResponse));
    }
    
    /**
     * 发送内部错误响应
     */
    private void sendInternalErrorResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(500);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        ApiResponse<Object> apiResponse = ApiResponse.error(message);
        response.getWriter().write(gson.toJson(apiResponse));
    }
}