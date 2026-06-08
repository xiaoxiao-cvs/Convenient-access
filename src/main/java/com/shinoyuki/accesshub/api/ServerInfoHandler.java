package com.shinoyuki.accesshub.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 服务器信息查询处理器接口, 服务 /api/v1/server/* 只读端点。
 *
 * 由 ServerInfoHandlerImpl 实现:
 *  - GET /api/v1/server/performance : 性能数据 (Spark + JVM)
 *  - GET /api/v1/server/players     : 在线玩家列表
 */
public interface ServerInfoHandler {

    void handleGetPerformance(HttpServletRequest request, HttpServletResponse response) throws IOException;

    void handleGetPlayers(HttpServletRequest request, HttpServletResponse response) throws IOException;
}
