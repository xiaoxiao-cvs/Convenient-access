package com.shinoyuki.accesshub.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 玩家数据查询处理器接口。
 *
 * v3 (Forge 重写阶段) 将由基于 MinecraftServer.getPlayerList() / GameProfileCache 的实现承担,
 * 替代 v1 中重度依赖 Bukkit Player API 的 com.xaoxiao.convenientaccess.api.PlayerDataApiController.
 */
public interface PlayerDataHandler {

    /**
     * 处理 GET /api/v1/player?name=&lt;playerName&gt; 请求.
     */
    void handleGetPlayerData(HttpServletRequest request, HttpServletResponse response) throws IOException;
}
