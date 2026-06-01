package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.shinoyuki.accesshub.integration.SparkIntegration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * GET /api/v1/server/performance 实现, 包装 SparkIntegration。
 *
 * 返回 TPS/MSPT/CPU (装了 spark mod) + 内存/GC/线程 (JVM, 始终可用)。
 * 数据采集在 SparkIntegration 的异步线程, 5s 超时。
 */
public final class ServerPerformanceHandlerImpl implements ServerPerformanceHandler {

    private static final Logger logger = LoggerFactory.getLogger(ServerPerformanceHandlerImpl.class);

    private final SparkIntegration spark;
    private final Gson gson = new Gson();

    public ServerPerformanceHandlerImpl(SparkIntegration spark) {
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

    private void send(HttpServletResponse response, int status, ApiResponse<?> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(body));
        response.getWriter().flush();
    }
}
