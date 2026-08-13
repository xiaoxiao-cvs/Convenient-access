package com.shinoyuki.accesshub.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /api/v1/net/* 只读端点。
 *
 * 与 {@link ServerInfoHandler} 同样的分层动机: 让 ApiRouter 不直接依赖 MinecraftServer 与转发器实现。
 */
public interface NetworkInfoHandler {

    /** 各接入线路的在线人数与连接地址, 供玩家自查页面使用。 */
    void handleGetNodes(HttpServletRequest request, HttpServletResponse response) throws IOException;
}
