package com.shinoyuki.accesshub.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 服务器性能查询处理器接口。
 *
 * 由 ServerPerformanceHandlerImpl (包装 SparkIntegration) 实现, 服务 GET /api/v1/server/performance。
 */
public interface ServerPerformanceHandler {

    void handleGetPerformance(HttpServletRequest request, HttpServletResponse response) throws IOException;
}
