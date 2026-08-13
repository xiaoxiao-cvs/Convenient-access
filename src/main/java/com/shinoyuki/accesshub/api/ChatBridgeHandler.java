package com.shinoyuki.accesshub.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 外部渠道向游戏内公屏发言的入口。当前唯一调用方是 QQ Bot 的 #say 命令。
 */
public interface ChatBridgeHandler {

    /** POST /api/v1/server/broadcast */
    void handleBroadcast(HttpServletRequest request, HttpServletResponse response) throws IOException;
}
