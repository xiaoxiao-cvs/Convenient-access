package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.shinoyuki.accesshub.integration.SparkIntegration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * /api/v1/server/* 只读端点实现。
 *
 * - performance: 包装 SparkIntegration (TPS/MSPT/CPU spark + 内存/GC/线程 JVM), 异步 5s 超时
 * - players: 在线玩家列表, 在服务器主线程采集 (server.execute) 3s 超时
 */
public final class ServerInfoHandlerImpl implements ServerInfoHandler {

    private static final Logger logger = LoggerFactory.getLogger(ServerInfoHandlerImpl.class);

    private final MinecraftServer server;
    private final SparkIntegration spark;
    private final Gson gson = new Gson();

    public ServerInfoHandlerImpl(MinecraftServer server, SparkIntegration spark) {
        this.server = server;
        this.spark = spark;
    }

    @Override
    public void handleGetPerformance(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            Map<String, Object> data = spark.getPerformanceDataAsync().get(5, TimeUnit.SECONDS);
            data.put("sparkAvailable", spark.isSparkAvailable());
            send(response, 200, ApiResponse.success(data, "成功获取服务器性能数据"));
        } catch (TimeoutException e) {
            logger.warn("获取性能数据超时");
            send(response, 504, ApiResponse.error("获取性能数据超时"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(response, 500, ApiResponse.error("查询被中断"));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.error("获取性能数据失败", cause);
            send(response, 500, ApiResponse.error("获取性能数据失败: " + cause.getMessage()));
        }
    }

    @Override
    public void handleGetPlayers(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 读取在线玩家状态必须在服务器主线程
            CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    future.complete(collectOnlinePlayers());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            List<Map<String, Object>> players = future.get(3, TimeUnit.SECONDS);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", players.size());
            data.put("maxPlayers", server.getMaxPlayers());
            data.put("players", players);
            send(response, 200, ApiResponse.success(data, "成功获取在线玩家列表"));
        } catch (TimeoutException e) {
            logger.warn("获取在线玩家超时 (服务器主线程繁忙)");
            send(response, 504, ApiResponse.error("获取在线玩家超时"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(response, 500, ApiResponse.error("查询被中断"));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.error("获取在线玩家失败", cause);
            send(response, 500, ApiResponse.error("获取在线玩家失败: " + cause.getMessage()));
        }
    }

    /** 采集在线玩家轻量列表 (主线程执行)。 */
    private List<Map<String, Object>> collectOnlinePlayers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", player.getGameProfile().getName());
            p.put("uuid", player.getUUID().toString());
            p.put("dimension", player.level().dimension().location().toString());
            p.put("x", player.getX());
            p.put("y", player.getY());
            p.put("z", player.getZ());
            p.put("health", player.getHealth());
            p.put("ping", player.latency);
            p.put("gameMode", player.gameMode.getGameModeForPlayer().getName());
            result.add(p);
        }
        return result;
    }

    private void send(HttpServletResponse response, int status, ApiResponse<?> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(body));
        response.getWriter().flush();
    }
}
