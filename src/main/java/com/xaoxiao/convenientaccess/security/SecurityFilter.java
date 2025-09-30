package com.xaoxiao.convenientaccess.security;

import com.google.gson.Gson;
import com.xaoxiao.convenientaccess.api.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 安全过滤器
 * 集成请求频率限制、安全监控和防护机制
 */
public class SecurityFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(SecurityFilter.class);
    
    private final RateLimiter rateLimiter;
    private final SecurityMonitor securityMonitor;
    private final Gson gson;
    
    // 需要特殊处理的路径
    private static final Set<String> LOGIN_PATHS = new HashSet<>(Arrays.asList(
            "/api/v1/admin/login"
    ));
    
    private static final Set<String> API_PATHS = new HashSet<>(Arrays.asList(
            "/api/v1/whitelist",
            "/api/v1/admin"
    ));
    
    // 不需要安全检查的公开路径
    private static final Set<String> PUBLIC_PATHS = new HashSet<>(Arrays.asList(
            "/api/v1/server/info",
            "/api/v1/health"
    ));
    
    public SecurityFilter(RateLimiter rateLimiter, SecurityMonitor securityMonitor) {
        this.rateLimiter = rateLimiter;
        this.securityMonitor = securityMonitor;
        this.gson = new Gson();
    }
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        logger.info("安全过滤器初始化完成");
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        String path = getRequestPath(httpRequest);
        String clientIp = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        String method = httpRequest.getMethod();
        
        // 检查是否为公开路径
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }
        
        // 处理OPTIONS请求（CORS预检）
        if ("OPTIONS".equals(method)) {
            chain.doFilter(request, response);
            return;
        }
        
        try {
            // 1. 基本安全检查
            if (!performBasicSecurityChecks(httpRequest, httpResponse)) {
                return;
            }
            
            // 2. 请求频率限制检查
            if (!performRateLimitCheck(httpRequest, httpResponse, path, clientIp)) {
                return;
            }
            
            // 3. 记录请求到安全监控
            recordRequestForMonitoring(httpRequest, path, clientIp, userAgent);
            
            // 4. 继续处理请求
            chain.doFilter(request, response);
            
            // 5. 记录响应状态
            recordResponseForMonitoring(httpResponse, path, clientIp, userAgent);
            
        } catch (Exception e) {
            logger.error("安全过滤器处理请求时发生错误", e);
            sendSecurityErrorResponse(httpResponse, "安全检查失败");
        }
    }
    
    @Override
    public void destroy() {
        logger.info("安全过滤器已销毁");
    }
    
    /**
     * 执行基本安全检查
     */
    private boolean performBasicSecurityChecks(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        String clientIp = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        
        // 检查User-Agent
        if (userAgent == null || userAgent.trim().isEmpty()) {
            logger.warn("拒绝没有User-Agent的请求: {}", clientIp);
            sendSecurityErrorResponse(response, "缺少User-Agent头");
            return false;
        }
        
        // 检查可疑的User-Agent
        if (isSuspiciousUserAgent(userAgent)) {
            logger.warn("拒绝可疑User-Agent的请求: {} - {}", clientIp, userAgent);
            sendSecurityErrorResponse(response, "可疑的User-Agent");
            return false;
        }
        
        // 检查请求头大小
        if (hasOversizedHeaders(request)) {
            logger.warn("拒绝请求头过大的请求: {}", clientIp);
            sendSecurityErrorResponse(response, "请求头过大");
            return false;
        }
        
        // 检查IP地址格式
        if (!isValidIpAddress(clientIp)) {
            logger.warn("拒绝无效IP地址的请求: {}", clientIp);
            sendSecurityErrorResponse(response, "无效的IP地址");
            return false;
        }
        
        return true;
    }
    
    /**
     * 执行请求频率限制检查
     */
    private boolean performRateLimitCheck(HttpServletRequest request, HttpServletResponse response, 
                                        String path, String clientIp) throws IOException {
        
        RateLimiter.RateLimitType limitType = determineLimitType(path);
        RateLimiter.RateLimitResult limitResult = rateLimiter.checkLimit(clientIp, limitType);
        
        if (!limitResult.isAllowed()) {
            logger.warn("请求被频率限制拒绝: {} - {} - {}", clientIp, path, limitResult.getMessage());
            
            // 设置频率限制响应头
            response.setHeader("X-RateLimit-Limit", String.valueOf(limitResult.getLimit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(limitResult.getRemaining()));
            response.setHeader("X-RateLimit-Reset", String.valueOf(limitResult.getResetInSeconds()));
            
            sendRateLimitResponse(response, limitResult);
            return false;
        }
        
        // 设置频率限制信息头
        response.setHeader("X-RateLimit-Limit", String.valueOf(limitResult.getLimit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(limitResult.getRemaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(limitResult.getResetInSeconds()));
        
        return true;
    }
    
    /**
     * 记录请求到安全监控
     */
    private void recordRequestForMonitoring(HttpServletRequest request, String path, String clientIp, String userAgent) {
        // 这里可以记录请求信息，但不记录响应状态（因为还没有响应）
        // 主要用于检测请求模式
    }
    
    /**
     * 记录响应到安全监控
     */
    private void recordResponseForMonitoring(HttpServletResponse response, String path, String clientIp, String userAgent) {
        int statusCode = response.getStatus();
        
        // 记录API请求
        if (isApiPath(path)) {
            securityMonitor.recordApiRequest(clientIp, path, statusCode, userAgent);
        }
        
        // 如果是登录相关的请求，记录登录尝试
        if (isLoginPath(path)) {
            boolean success = statusCode == 200;
            String username = "unknown"; // 这里可以从请求中提取用户名
            securityMonitor.recordLoginAttempt(clientIp, username, success, userAgent);
        }
    }
    
    /**
     * 获取请求路径
     */
    private String getRequestPath(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        return path != null ? path : "";
    }
    
    /**
     * 获取客户端IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        // 检查代理头
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
     * 检查是否为公开路径
     */
    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }
    
    /**
     * 检查是否为登录路径
     */
    private boolean isLoginPath(String path) {
        return LOGIN_PATHS.stream().anyMatch(path::startsWith);
    }
    
    /**
     * 检查是否为API路径
     */
    private boolean isApiPath(String path) {
        return API_PATHS.stream().anyMatch(path::startsWith);
    }
    
    /**
     * 确定限制类型
     */
    private RateLimiter.RateLimitType determineLimitType(String path) {
        if (isLoginPath(path)) {
            return RateLimiter.RateLimitType.LOGIN;
        } else if (isApiPath(path)) {
            return RateLimiter.RateLimitType.API;
        } else {
            return RateLimiter.RateLimitType.GENERAL;
        }
    }
    
    /**
     * 检查是否为可疑的User-Agent
     */
    private boolean isSuspiciousUserAgent(String userAgent) {
        if (userAgent == null) {
            return true;
        }
        
        String lowerUserAgent = userAgent.toLowerCase();
        
        // 检查已知的恶意User-Agent模式
        String[] suspiciousPatterns = {
                "bot", "crawler", "spider", "scraper", "scanner",
                "sqlmap", "nikto", "nmap", "masscan", "zap",
                "python-requests", "curl", "wget", "httpclient"
        };
        
        for (String pattern : suspiciousPatterns) {
            if (lowerUserAgent.contains(pattern)) {
                return true;
            }
        }
        
        // 检查过短的User-Agent
        if (userAgent.length() < 10) {
            return true;
        }
        
        // 检查只包含特殊字符的User-Agent
        if (userAgent.matches("^[^a-zA-Z0-9\\s]+$")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查是否有过大的请求头
     */
    private boolean hasOversizedHeaders(HttpServletRequest request) {
        // 检查单个头的大小
        java.util.Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = request.getHeader(headerName);
            
            if (headerName.length() > 1024 || (headerValue != null && headerValue.length() > 8192)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查IP地址是否有效
     */
    private boolean isValidIpAddress(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }
        
        // 简单的IP地址格式检查
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        
        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * 发送安全错误响应
     */
    private void sendSecurityErrorResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        ApiResponse<Object> apiResponse = ApiResponse.forbidden(message);
        response.getWriter().write(gson.toJson(apiResponse));
    }
    
    /**
     * 发送频率限制响应
     */
    private void sendRateLimitResponse(HttpServletResponse response, RateLimiter.RateLimitResult limitResult) 
            throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        ApiResponse<Object> apiResponse = ApiResponse.error(limitResult.getMessage());
        response.getWriter().write(gson.toJson(apiResponse));
    }
}