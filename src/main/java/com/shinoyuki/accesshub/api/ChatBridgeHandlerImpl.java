package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

/**
 * 把外部渠道的一句话投到全服公屏。
 *
 * 渲染成 {@code [来源|频道] 发言人: 内容}, 前缀灰、发言人金、正文白 —— 玩家一眼能分清这不是游戏内发言。
 * 所有外来文本都经 {@link #sanitize} 洗过: 剥掉分节符与控制字符并限长, 避免有人用 §c 之类把自己
 * 伪装成系统提示, 或用换行撑出假的多行公告。
 */
public final class ChatBridgeHandlerImpl implements ChatBridgeHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatBridgeHandlerImpl.class);

    private static final int MAX_CONTENT_LENGTH = 256;
    private static final int MAX_LABEL_LENGTH = 32;
    private static final String DEFAULT_SOURCE = "QQ";

    private final MinecraftServer server;

    public ChatBridgeHandlerImpl(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void handleBroadcast(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject json;
        try {
            String body = ApiSupport.readRequestBody(request);
            var parsed = body.isBlank() ? null : JsonParser.parseString(body);
            json = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            ApiSupport.sendJson(response, 400, ApiResponse.badRequest("请求体不是合法 JSON"));
            return;
        }
        if (json == null) {
            ApiSupport.sendJson(response, 400, ApiResponse.badRequest("请求体必须是 JSON 对象"));
            return;
        }

        String sender = ChatTextSanitizer.sanitize(optString(json, "sender"), MAX_LABEL_LENGTH);
        String content = ChatTextSanitizer.sanitize(optString(json, "content"), MAX_CONTENT_LENGTH);
        String source = ChatTextSanitizer.sanitize(optString(json, "source"), MAX_LABEL_LENGTH);
        String channel = ChatTextSanitizer.sanitize(optString(json, "channel"), MAX_LABEL_LENGTH);

        if (sender == null || content == null) {
            ApiSupport.sendJson(response, 400, ApiResponse.badRequest("缺少必要参数: sender, content"));
            return;
        }
        if (source == null) {
            source = DEFAULT_SOURCE;
        }

        String prefix = channel == null ? source : source + "|" + channel;
        MutableComponent line = Component.empty()
                .append(Component.literal("[" + prefix + "] ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(sender).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(content).withStyle(ChatFormatting.WHITE));

        try {
            // 玩家列表只能在主线程安全遍历; broadcastSystemMessage 内部还会写一份到控制台, 顺带留痕
            CompletableFuture<Integer> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    server.getPlayerList().broadcastSystemMessage(line, false);
                    future.complete(server.getPlayerList().getPlayerCount());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            int recipients = future.get(3, TimeUnit.SECONDS);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("recipients", recipients);
            data.put("rendered", "[" + prefix + "] " + sender + ": " + content);
            ApiSupport.sendJson(response, 200, ApiResponse.success(data, "已发送到公屏"));
            logger.info("公屏广播 [{}] {}: {} (在线 {} 人)", prefix, sender, content, recipients);
        } catch (TimeoutException e) {
            logger.warn("公屏广播超时 (服务器主线程繁忙)");
            ApiSupport.sendJson(response, 504, ApiResponse.error(504, "服务器主线程繁忙, 广播超时"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ApiSupport.sendJson(response, 500, ApiResponse.error("广播被中断"));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.error("公屏广播失败", cause);
            ApiSupport.sendJson(response, 500, ApiResponse.error("广播失败: " + cause.getMessage()));
        }
    }

    private String optString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return json.get(key).getAsString();
    }
}
