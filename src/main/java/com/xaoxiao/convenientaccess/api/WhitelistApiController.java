package com.xaoxiao.convenientaccess.api;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.xaoxiao.convenientaccess.sync.SyncTask;
import com.xaoxiao.convenientaccess.sync.SyncTaskManager;
import com.xaoxiao.convenientaccess.utils.UuidUtils;
import com.xaoxiao.convenientaccess.whitelist.BatchOperation;
import com.xaoxiao.convenientaccess.whitelist.WhitelistEntry;
import com.xaoxiao.convenientaccess.whitelist.WhitelistManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
        // 配置Gson以正确处理LocalDateTime
        this.gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new TypeAdapter<LocalDateTime>() {
                private final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
                
                @Override
                public void write(JsonWriter out, LocalDateTime value) throws IOException {
                    if (value == null) {
                        out.nullValue();
                    } else {
                        out.value(value.format(formatter));
                    }
                }
                
                @Override
                public LocalDateTime read(JsonReader in) throws IOException {
                    if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                        in.nextNull();
                        return null;
                    }
                    return LocalDateTime.parse(in.nextString(), formatter);
                }
            })
            .setPrettyPrinting()
            .create();
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
            String startDate = request.getParameter("start_date");
            String endDate = request.getParameter("end_date");
            
            // 使用新的分页查询方法
            whitelistManager.getWhitelistPaginated(page, size, search, source, addedBy, sort, order, startDate, endDate)
                .thenAccept(result -> {
                    // 直接使用PaginatedResult，不需要转换
                    sendJsonResponse(response, 200, ApiResponse.success(result));
                })
                .exceptionally(throwable -> {
                    logger.error("查询白名单失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("查询白名单失败"));
                    return null;
                })
                .join(); // 等待异步操作完成
            
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
            
            // 参数验证 - 只需要name、source，其他为可选
            if (!json.has("name") || !json.has("source")) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必需参数: name, source"));
                return;
            }
            
            String name = json.get("name").getAsString();
            String addedByName = json.has("added_by_name") ? json.get("added_by_name").getAsString() : "API";
            String addedByUuid = json.has("added_by_uuid") ? json.get("added_by_uuid").getAsString() : "00000000-0000-0000-0000-000000000000";
            String sourceStr = json.get("source").getAsString();
            
            // 处理时间戳 - 如果前端提供则使用，否则使用当前时间
            LocalDateTime addedAt;
            if (json.has("added_at")) {
                try {
                    String addedAtStr = json.get("added_at").getAsString();
                    addedAt = LocalDateTime.parse(addedAtStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (Exception e) {
                    sendJsonResponse(response, 400, ApiResponse.badRequest("时间格式无效，请使用ISO格式: yyyy-MM-ddTHH:mm:ss"));
                    return;
                }
            } else {
                addedAt = LocalDateTime.now();
            }
            
            // 验证参数格式
            if (!isValidPlayerName(name)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名称格式无效"));
                return;
            }
            
            WhitelistEntry.Source source;
            try {
                source = WhitelistEntry.Source.fromString(sourceStr);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("来源类型无效"));
                return;
            }
            
            // 新逻辑：只使用玩家名添加到白名单，UUID留空等玩家登录时补充
            logger.info("添加玩家到白名单（仅用户名）: {}", name);
            
            CompletableFuture<Boolean> addFuture = whitelistManager.addPlayerByNameOnly(name, addedByName, addedByUuid, source, addedAt);
            
            // 处理添加结果
            addFuture.thenAccept(success -> {
                    if (success) {
                        // 创建同步任务（使用玩家名）
                        syncTaskManager.scheduleAddPlayer(null, name);
                        
                        JsonObject result = new JsonObject();
                        result.addProperty("name", name);
                        result.addProperty("added", true);
                        result.addProperty("uuid_pending", true); // 表示UUID将在玩家登录时补充
                        result.addProperty("message", "玩家已添加到白名单，UUID将在首次登录时自动补充");
                        
                        sendJsonResponse(response, 201, ApiResponse.success(result, "玩家添加成功"));
                    } else {
                        sendJsonResponse(response, 409, ApiResponse.error("玩家已在白名单中或添加失败"));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("添加玩家失败: {}", name, throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("添加玩家失败"));
                    return null;
                })
                .join(); // 等待异步操作完成
            
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
                })
                .join(); // 等待异步操作完成
            
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
                })
                .join(); // 等待异步操作完成
            
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
                    if (taskId != null && taskId > 0) {
                        JsonObject result = new JsonObject();
                        result.addProperty("task_id", taskId);
                        result.addProperty("message", "同步任务已创建");
                        sendJsonResponse(response, 200, ApiResponse.success(result));
                    } else {
                        logger.warn("同步任务创建失败，返回的taskId: {}", taskId);
                        sendJsonResponse(response, 500, ApiResponse.error("创建同步任务失败"));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("触发同步失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("触发同步失败"));
                    return null;
                })
                .join(); // 等待异步操作完成
            
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
                })
                .join(); // 等待异步操作完成
            
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
            response.getWriter().flush();
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
     * 处理POST /api/v1/whitelist/batch - 批量操作
     */
    public void handleBatchOperation(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 读取请求体
            String requestBody = readRequestBody(request);
            JsonObject json = JsonParser.parseString(requestBody).getAsJsonObject();
            
            // 参数验证
            if (!json.has("operation") || !json.has("players") || !json.has("source")) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必需参数: operation, players, source"));
                return;
            }
            
            String operation = json.get("operation").getAsString();
            JsonArray playersArray = json.getAsJsonArray("players");
            String sourceStr = json.get("source").getAsString();
            
            // 验证source参数
            WhitelistEntry.Source source;
            try {
                source = WhitelistEntry.Source.fromString(sourceStr);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("无效的来源类型: " + sourceStr));
                return;
            }
            
            // 解析时间戳（可选）
                LocalDateTime addedAt = json.has("added_at") ? 
                    LocalDateTime.parse(json.get("added_at").getAsString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME) : 
                    LocalDateTime.now();
            
            
            if (playersArray.size() == 0) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家列表不能为空"));
                return;
            }
            
            if (playersArray.size() > 100) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("批量操作最多支持100个玩家"));
                return;
            }
            
            // 获取操作者信息
            String addedByName = json.has("added_by_name") ? json.get("added_by_name").getAsString() : "API";
            String addedByUuid = json.has("added_by_uuid") ? json.get("added_by_uuid").getAsString() : "00000000-0000-0000-0000-000000000000";
            
            if ("add".equalsIgnoreCase(operation)) {
                // 批量添加
                BatchOperation batchOperation = new BatchOperation(
                    BatchOperation.OperationType.ADD, addedByName, addedByUuid
                );
                
                // 解析玩家列表
                for (int i = 0; i < playersArray.size(); i++) {
                    JsonObject playerObj = playersArray.get(i).getAsJsonObject();
                    
                    if (!playerObj.has("name")) {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("玩家信息缺少name字段"));
                        return;
                    }
                    
                    String name = playerObj.get("name").getAsString();
                    String providedUuid = playerObj.has("uuid") ? playerObj.get("uuid").getAsString() : null;
                    
                    if (!isValidPlayerName(name)) {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("无效的玩家名: " + name));
                        return;
                    }
                    
                    // 生成或验证UUID
                    String uuid;
                    try {
                        uuid = UuidUtils.getOrGenerateUuid(name, providedUuid);
                    } catch (IllegalArgumentException e) {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名不能为空: " + name));
                        return;
                    }
                    
                    batchOperation.addEntry(name, uuid, source, addedAt);
                }
                
                // 执行批量添加
                whitelistManager.executeBatchOperation(batchOperation)
                    .thenAccept(result -> {
                        // 创建同步任务
                        if (result.getSuccessCount() > 0) {
                            syncTaskManager.createTask(SyncTask.TaskType.BATCH_UPDATE, "{\"operation\":\"batch_add\"}", 2);
                        }
                        
                        JsonObject responseData = new JsonObject();
                        responseData.addProperty("operation", "add");
                        responseData.addProperty("total_requested", result.getTotalRequested());
                        responseData.addProperty("success_count", result.getSuccessCount());
                        responseData.addProperty("failure_count", result.getFailureCount());
                        responseData.addProperty("success_rate", result.getSuccessRate());
                        
                        if (!result.getErrors().isEmpty()) {
                            responseData.add("errors", gson.toJsonTree(result.getErrors()));
                        }
                        
                        if (result.isCompleteSuccess()) {
                            sendJsonResponse(response, 200, ApiResponse.success(responseData, "批量添加完成"));
                        } else if (result.isCompleteFailure()) {
                            sendJsonResponse(response, 400, ApiResponse.badRequest("批量添加全部失败"));
                        } else {
                            sendJsonResponse(response, 207, ApiResponse.success(responseData, "批量添加部分成功"));
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.error("批量添加失败", throwable);
                        sendJsonResponse(response, 500, ApiResponse.error("批量添加失败"));
                        return null;
                    })
                    .join(); // 等待异步操作完成
                    
            } else if ("remove".equalsIgnoreCase(operation)) {
                // 批量删除
                List<String> uuids = new ArrayList<>();
                
                // 解析UUID列表
                for (int i = 0; i < playersArray.size(); i++) {
                    JsonObject playerObj = playersArray.get(i).getAsJsonObject();
                    
                    if (!playerObj.has("uuid")) {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("删除操作需要uuid字段"));
                        return;
                    }
                    
                    String uuid = playerObj.get("uuid").getAsString();
                    if (!isValidUuid(uuid)) {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("无效的UUID: " + uuid));
                        return;
                    }
                    
                    uuids.add(uuid);
                }
                
                // 执行批量删除
                whitelistManager.batchRemovePlayersByUuid(uuids, addedByName, addedByUuid)
                    .thenAccept(result -> {
                        // 创建同步任务
                        if (result.getSuccessCount() > 0) {
                            syncTaskManager.createTask(SyncTask.TaskType.BATCH_UPDATE, "{\"operation\":\"batch_remove\"}", 2);
                        }
                        
                        JsonObject responseData = new JsonObject();
                        responseData.addProperty("operation", "remove");
                        responseData.addProperty("total_requested", result.getTotalRequested());
                        responseData.addProperty("success_count", result.getSuccessCount());
                        responseData.addProperty("failure_count", result.getFailureCount());
                        responseData.addProperty("success_rate", result.getSuccessRate());
                        
                        if (!result.getErrors().isEmpty()) {
                            responseData.add("errors", gson.toJsonTree(result.getErrors()));
                        }
                        
                        if (result.isCompleteSuccess()) {
                            sendJsonResponse(response, 200, ApiResponse.success(responseData, "批量删除完成"));
                        } else if (result.isCompleteFailure()) {
                            sendJsonResponse(response, 400, ApiResponse.badRequest("批量删除全部失败"));
                        } else {
                            sendJsonResponse(response, 207, ApiResponse.success(responseData, "批量删除部分成功"));
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.error("批量删除失败", throwable);
                        sendJsonResponse(response, 500, ApiResponse.error("批量删除失败"));
                        return null;
                    })
                    .join(); // 等待异步操作完成
                    
            } else {
                sendJsonResponse(response, 400, ApiResponse.badRequest("不支持的操作类型: " + operation));
            }
            
        } catch (Exception e) {
             logger.error("处理批量操作请求失败", e);
             sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
         }
     }
      
      /**
       * 验证UUID格式
       */
      private boolean isValidUuid(String uuid) {
          return uuid != null && uuid.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
      }
  }