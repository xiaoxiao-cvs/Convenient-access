package com.xaoxiao.convenientaccess.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import com.xaoxiao.convenientaccess.data.DataCollector;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * API管理器
 * 负责处理所有API请求和响应
 */
public class ApiManager {
    
    private final ConvenientAccessPlugin plugin;
    private final DataCollector dataCollector;
    private final Gson gson;
    
    // 请求频率限制
    private final Map<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();
    
    public ApiManager(ConvenientAccessPlugin plugin, DataCollector dataCollector) {
        this.plugin = plugin;
        this.dataCollector = dataCollector;
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();
    }
    
    /**
     * 处理API请求
     */
    public CompletableFuture<ApiResponse> handleRequest(String path, String method, String clientIp, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 记录请求日志
                if (plugin.getConfigManager().isLogRequests()) {
                    plugin.getLogger().info(String.format("API请求: %s %s from %s", method, path, clientIp));
                }
                
                // 检查请求频率限制
                if (!checkRateLimit(clientIp)) {
                    return createErrorResponse(429, "Too Many Requests", "请求频率超限");
                }
                
                // 检查认证
                if (!checkAuthentication(headers)) {
                    return createErrorResponse(401, "Unauthorized", "认证失败");
                }
                
                // 路由请求
                return routeRequest(path, method).join();
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "处理API请求时发生错误: " + path, e);
                return createErrorResponse(500, "Internal Server Error", "服务器内部错误");
            }
        });
    }
    
    /**
     * 路由请求到对应的处理方法
     */
    private CompletableFuture<ApiResponse> routeRequest(String path, String method) {
        String apiVersion = plugin.getConfigManager().getApiVersion();
        String basePath = "/api/" + apiVersion;
        
        if (!path.startsWith(basePath)) {
            return CompletableFuture.completedFuture(
                createErrorResponse(404, "Not Found", "API路径不存在"));
        }
        
        String endpoint = path.substring(basePath.length());
        
        // 支持GET请求的端点
        if ("GET".equals(method)) {
            switch (endpoint) {
                case "/server/info":
                    return handleServerInfo();
                case "/server/status":
                    return handleServerStatus();
                case "/server/performance":
                    return handlePerformance();
                case "/players/online":
                    return handlePlayersOnline();
                case "/players/list":
                    return handlePlayersList();
                case "/worlds/list":
                    return handleWorldsList();
                case "/system/resources":
                    return handleSystemResources();
                case "/health":
                    return handleHealthCheck();
                default:
                    return CompletableFuture.completedFuture(
                        createErrorResponse(404, "Not Found", "API端点不存在: " + endpoint));
            }
        }
        
        // 支持POST请求的端点
        if ("POST".equals(method)) {
            switch (endpoint) {
                case "/server/reload":
                    return handleServerReload();
                case "/cache/clear":
                    return handleCacheClear();
                default:
                    return CompletableFuture.completedFuture(
                        createErrorResponse(404, "Not Found", "API端点不存在: " + endpoint));
            }
        }
        
        // 支持DELETE请求的端点
        if ("DELETE".equals(method)) {
            switch (endpoint) {
                case "/cache/clear":
                    return handleCacheClear();
                default:
                    return CompletableFuture.completedFuture(
                        createErrorResponse(404, "Not Found", "API端点不存在: " + endpoint));
            }
        }
        
        return CompletableFuture.completedFuture(
            createErrorResponse(405, "Method Not Allowed", "不支持的请求方法: " + method));
    }
    
    // API端点处理方法
    
    private CompletableFuture<ApiResponse> handleServerInfo() {
        return dataCollector.getServerInfoAsync()
            .thenApply(data -> createSuccessResponse(data));
    }
    
    private CompletableFuture<ApiResponse> handleServerStatus() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> status = new HashMap<>();
            status.put("online", true);
            status.put("timestamp", System.currentTimeMillis());
            status.put("spark_available", plugin.getSparkIntegration().isSparkAvailable());
            status.put("plugin_version", plugin.getDescription().getVersion());
            return createSuccessResponse(status);
        });
    }
    
    private CompletableFuture<ApiResponse> handlePerformance() {
        return dataCollector.getPerformanceDataAsync()
            .thenApply(data -> createSuccessResponse(data));
    }
    
    private CompletableFuture<ApiResponse> handlePlayersOnline() {
        return dataCollector.getPlayersDataAsync()
            .thenApply(data -> {
                // 只返回在线玩家数量，不包含详细信息
                Map<String, Object> onlineData = new HashMap<>();
                onlineData.put("online_count", data.get("online_count"));
                onlineData.put("max_players", data.get("max_players"));
                onlineData.put("timestamp", data.get("timestamp"));
                return createSuccessResponse(onlineData);
            });
    }
    
    private CompletableFuture<ApiResponse> handlePlayersList() {
        return dataCollector.getPlayersDataAsync()
            .thenApply(data -> createSuccessResponse(data));
    }
    
    private CompletableFuture<ApiResponse> handleWorldsList() {
        return dataCollector.getWorldsDataAsync()
            .thenApply(data -> createSuccessResponse(data));
    }
    
    private CompletableFuture<ApiResponse> handleSystemResources() {
        return dataCollector.getSystemResourcesAsync()
            .thenApply(data -> createSuccessResponse(data));
    }
    
    private CompletableFuture<ApiResponse> handleHealthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "healthy");
            health.put("timestamp", System.currentTimeMillis());
            health.put("uptime", System.currentTimeMillis());
            health.put("version", plugin.getDescription().getVersion());
            
            // 检查各组件状态
            Map<String, Object> components = new HashMap<>();
            components.put("cache", plugin.getCacheManager() != null ? "healthy" : "error");
            components.put("spark", plugin.getSparkIntegration().isSparkAvailable() ? "available" : "unavailable");
            components.put("data_collector", "healthy");
            health.put("components", components);
            
            return createSuccessResponse(health);
        });
    }
    
    /**
     * 处理服务器重载请求
     */
    private CompletableFuture<ApiResponse> handleServerReload() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                plugin.reload();
                Map<String, Object> result = new HashMap<>();
                result.put("message", "服务器重载成功");
                result.put("timestamp", System.currentTimeMillis());
                return createSuccessResponse(result);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "服务器重载失败", e);
                return createErrorResponse(500, "Internal Server Error", "服务器重载失败: " + e.getMessage());
            }
        });
    }
    
    /**
     * 处理缓存清理请求
     */
    private CompletableFuture<ApiResponse> handleCacheClear() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (plugin.getCacheManager() != null) {
                    plugin.getCacheManager().clearAll();
                }
                Map<String, Object> result = new HashMap<>();
                result.put("message", "缓存清理成功");
                result.put("timestamp", System.currentTimeMillis());
                return createSuccessResponse(result);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "缓存清理失败", e);
                return createErrorResponse(500, "Internal Server Error", "缓存清理失败: " + e.getMessage());
            }
        });
    }
    
    // 辅助方法
    
    /**
     * 检查请求频率限制
     */
    private boolean checkRateLimit(String clientIp) {
        if (!plugin.getConfigManager().isRateLimitEnabled()) {
            return true;
        }
        
        long currentTime = System.currentTimeMillis();
        long windowStart = currentTime - 60000; // 1分钟窗口
        
        // 清理过期的请求记录
        lastRequestTime.entrySet().removeIf(entry -> entry.getValue() < windowStart);
        requestCounts.entrySet().removeIf(entry -> !lastRequestTime.containsKey(entry.getKey()));
        
        // 检查当前IP的请求次数
        AtomicLong count = requestCounts.computeIfAbsent(clientIp, k -> new AtomicLong(0));
        lastRequestTime.put(clientIp, currentTime);
        
        long currentCount = count.incrementAndGet();
        int maxRequests = plugin.getConfigManager().getRequestsPerMinute();
        
        return currentCount <= maxRequests;
    }
    
    /**
     * 检查API认证
     */
    private boolean checkAuthentication(Map<String, String> headers) {
        if (!plugin.getConfigManager().isAuthEnabled()) {
            return true;
        }
        
        String apiKey = plugin.getConfigManager().getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return true; // 如果没有设置API密钥，则跳过认证
        }
        
        String authHeader = headers.get("Authorization");
        if (authHeader == null) {
            authHeader = headers.get("X-API-Key");
        }
        
        return apiKey.equals(authHeader);
    }
    
    /**
     * 创建成功响应
     */
    private ApiResponse createSuccessResponse(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("timestamp", System.currentTimeMillis());
        
        return new ApiResponse(200, gson.toJson(response), "application/json");
    }
    
    /**
     * 创建错误响应
     */
    private ApiResponse createErrorResponse(int statusCode, String error, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", error);
        response.put("message", message);
        response.put("timestamp", System.currentTimeMillis());
        
        return new ApiResponse(statusCode, gson.toJson(response), "application/json");
    }
    
    /**
     * API响应类
     */
    public static class ApiResponse {
        private final int statusCode;
        private final String body;
        private final String contentType;
        
        public ApiResponse(int statusCode, String body, String contentType) {
            this.statusCode = statusCode;
            this.body = body;
            this.contentType = contentType;
        }
        
        public int getStatusCode() {
            return statusCode;
        }
        
        public String getBody() {
            return body;
        }
        
        public String getContentType() {
            return contentType;
        }
    }
}