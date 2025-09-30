package com.xaoxiao.convenientaccess.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xaoxiao.convenientaccess.sync.SyncTaskManager;
import com.xaoxiao.convenientaccess.whitelist.WhitelistEntry;
import com.xaoxiao.convenientaccess.whitelist.WhitelistManager;
import com.xaoxiao.convenientaccess.whitelist.WhitelistStats;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 白名单API控制器
 * 处理HTTP请求路由、参数验证、响应格式化和错误处理
 */
public class WhitelistApiController {
    private static final Logger logger = LoggerFactory.getLogger(WhitelistApiController.class);
    
    private final WhitelistManager whitelistManager;
    private final SyncTaskManager syncTaskManager;
    private final Gson gson;
    
    public WhitelistApiController(WhitelistManager whitelistManager, SyncTaskManager syncTaskManager) {
        this.whitelistManager = whitelistManager;
        this.syncTaskManager = syncTaskManager;
        this.gson = new Gson();
    }
    
    /**
     * 处理GET /api/v1/whitelist - 分页查询白名单
     */
    public void handleGetWhitelist(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 解析查询参数
            final int page = Math.max(getIntParameter(request, "page", 1), 1);
            final int size = Math.min(Math.max(getIntParameter(request, "size", 20), 1), 100);
            String search = request.getParameter("search");
            String source = request.getParameter("source");
            String addedBy = request.getParameter("added_by");
            String sort = request.getParameter("sort");
            String order = request.getParameter("order");
            
            // TODO: 实现分页查询（第二阶段功能）
            // 目前返回简单的搜索结果
            CompletableFuture<List<WhitelistEntry>> future;
            if (search != null && !search.trim().isEmpty()) {
                future = whitelistManager.searchPlayersByName(search.trim(), size);
            } else {
                // 返回空列表，等待第二阶段实现完整分页
                future = CompletableFuture.completedFuture(new ArrayList<>());
            }
            
            future.thenAccept(items -> {
                PaginationResult<WhitelistEntry> result = new PaginationResult<>(items, page, size, items.size());
                sendJsonResponse(response, 200, ApiResponse.success(result));
            }).exceptionally(throwable -> {
                logger.error("查询白名单失败", throwable);
                sendJsonResponse(response, 500, ApiResponse.error("查询白名单失败"));
                return null;
            });
            
        } catch (Exception e) {
            logger.error("处理白名单查询请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理POST /api/v1/whitelist - 添加玩家到白名单
     */
    public void handleAddPlayer(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 读取请求体
            String requestBody = readRequestBody(request);
            JsonObject json = JsonParser.parseString(requestBody).getAsJsonObject();
            
            // 参数验证
            if (!json.has("name") || !json.has("uuid") || 
                !json.has("added_by_name") || !json.has("added_by_uuid")) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必需参数"));
                return;
            }
            
            String name = json.get("name").getAsString();
            String uuid = json.get("uuid").getAsString();
            String addedByName = json.get("added_by_name").getAsString();
            String addedByUuid = json.get("added_by_uuid").getAsString();
            String sourceStr = json.has("source") ? json.get("source").getAsString() : "ADMIN";
            
            // 验证参数格式
            if (!isValidPlayerName(name)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名称格式无效"));
                return;
            }
            
            if (!isValidUuid(uuid)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("UUID格式无效"));
                return;
            }
            
            WhitelistEntry.Source source;
            try {
                source = WhitelistEntry.Source.fromString(sourceStr);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("来源类型无效"));
                return;
            }
            
            // 添加玩家
            whitelistManager.addPlayer(name, uuid, addedByName, addedByUuid, source)
                .thenAccept(success -> {
                    if (success) {
                        // 创建同步任务
                        syncTaskManager.scheduleAddPlayer(uuid, name);
                        
                        JsonObject result = new JsonObject();
                        result.addProperty("uuid", uuid);
                        result.addProperty("name", name);
                        result.addProperty("added", true);
                        
                        sendJsonResponse(response, 201, ApiResponse.success(result, "玩家添加成功"));
                    } else {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("添加玩家失败，可能已存在"));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("添加玩家失败: {} ({})", name, uuid, throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("添加玩家失败"));
                    return null;
                });
            
        } catch (Exception e) {
            logger.error("处理添加玩家请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理DELETE /api/v1/whitelist/{uuid} - 从白名单移除玩家
     */
    public void handleRemovePlayer(HttpServletRequest request, HttpServletResponse response, String uuid) throws IOException {
        try {
            // 验证UUID格式
            if (!isValidUuid(uuid)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("UUID格式无效"));
                return;
            }
            
            // 先获取玩家信息
            whitelistManager.getPlayerByUuid(uuid)
                .thenCompose(playerOpt -> {
                    if (playerOpt.isEmpty()) {
                        sendJsonResponse(response, 404, ApiResponse.notFound("玩家不存在"));
                        return CompletableFuture.completedFuture(false);
                    }
                    
                    WhitelistEntry player = playerOpt.get();
                    return whitelistManager.removePlayer(uuid)
                        .thenApply(success -> {
                            if (success) {
                                // 创建同步任务
                                syncTaskManager.scheduleRemovePlayer(uuid, player.getName());
                                
                                JsonObject result = new JsonObject();
                                result.addProperty("uuid", uuid);
                                result.addProperty("name", player.getName());
                                result.addProperty("removed", true);
                                
                                sendJsonResponse(response, 200, ApiResponse.success(result, "玩家移除成功"));
                                return true;
                            } else {
                                sendJsonResponse(response, 400, ApiResponse.badRequest("移除玩家失败"));
                                return false;
                            }
                        });
                })
                .exceptionally(throwable -> {
                    logger.error("移除玩家失败: {}", uuid, throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("移除玩家失败"));
                    return false;
                });
            
        } catch (Exception e) {
            logger.error("处理移除玩家请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理GET /api/v1/whitelist/stats - 获取白名单统计信息
     */
    public void handleGetStats(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            whitelistManager.getStats()
                .thenAccept(stats -> {
                    sendJsonResponse(response, 200, ApiResponse.success(stats));
                })
                .exceptionally(throwable -> {
                    logger.error("获取白名单统计失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("获取统计信息失败"));
                    return null;
                });
            
        } catch (Exception e) {
            logger.error("处理统计信息请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理POST /api/v1/whitelist/sync - 手动触发同步
     */
    public void handleTriggerSync(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            syncTaskManager.scheduleFullSync()
                .thenAccept(taskId -> {
                    if (taskId > 0) {
                        JsonObject result = new JsonObject();
                        result.addProperty("task_id", taskId);
                        result.addProperty("message", "同步任务已创建");
                        sendJsonResponse(response, 200, ApiResponse.success(result));
                    } else {
                        sendJsonResponse(response, 500, ApiResponse.error("创建同步任务失败"));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("触发同步失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("触发同步失败"));
                    return null;
                });
            
        } catch (Exception e) {
            logger.error("处理同步请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理GET /api/v1/whitelist/sync/status - 获取同步状态
     */
    public void handleGetSyncStatus(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            syncTaskManager.getSyncStatus()
                .thenAccept(status -> {
                    sendJsonResponse(response, 200, ApiResponse.success(status));
                })
                .exceptionally(throwable -> {
                    logger.error("获取同步状态失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("获取同步状态失败"));
                    return null;
                });
            
        } catch (Exception e) {
            logger.error("处理同步状态请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 发送JSON响应
     */
    private void sendJsonResponse(HttpServletResponse response, int statusCode, ApiResponse<?> apiResponse) {
        try {
            response.setStatus(statusCode);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(gson.toJson(apiResponse));
        } catch (IOException e) {
            logger.error("发送JSON响应失败", e);
        }
    }
    
    /**
     * 读取请求体
     */
    private String readRequestBody(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = request.getReader().readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
    
    /**
     * 获取整数参数
     */
    private int getIntParameter(HttpServletRequest request, String name, int defaultValue) {
        String value = request.getParameter(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    /**
     * 验证玩家名称格式
     */
    private boolean isValidPlayerName(String name) {
        return name != null && name.length() >= 3 && name.length() <= 16 && name.matches("^[a-zA-Z0-9_]+$");
    }
    
    /**
     * 验证UUID格式
     */
    private boolean isValidUuid(String uuid) {
        return uuid != null && uuid.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }
}