package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.net.NodeDefinition;
import com.shinoyuki.accesshub.net.NodeSessionRegistry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.minecraft.server.MinecraftServer;

/**
 * 各接入线路的实时人数。
 *
 * 这是个公开端点 (玩家自查页面无需登录即可访问), 因此只输出人数与连接地址, 绝不输出玩家名单或
 * 客户端 IP — 前者服务器列表本来就能看到, 后者属于个人信息, 泄露出去无法挽回。
 */
public final class NetworkInfoHandlerImpl implements NetworkInfoHandler {

    private final MinecraftServer server;
    private final AccessHubConfig config;
    private final NodeSessionRegistry registry;
    private final Gson gson = new Gson();

    public NetworkInfoHandlerImpl(MinecraftServer server, AccessHubConfig config,
                                  NodeSessionRegistry registry) {
        this.server = server;
        this.config = config;
        this.registry = registry;
    }

    @Override
    public void handleGetNodes(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<NodeDefinition> definitions = config.getNodes();
        Map<String, NodeSessionRegistry.NodeStats> statsById = new LinkedHashMap<>();
        for (NodeSessionRegistry.NodeStats stats : registry.snapshot(definitions)) {
            statsById.put(stats.nodeId(), stats);
        }

        List<Map<String, Object>> nodes = new ArrayList<>(definitions.size());
        for (NodeDefinition definition : definitions) {
            NodeSessionRegistry.NodeStats stats = statsById.get(definition.id());
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", definition.id());
            node.put("name", definition.displayName());
            node.put("endpoint", definition.endpoint());
            node.put("probeUrl", definition.probeUrl());
            node.put("online", stats != null ? stats.online() : 0);
            node.put("connecting", stats != null ? stats.connecting() : 0);
            nodes.add(node);
        }

        // 只取计数不读玩家状态, 无需切到服务器主线程; 自查页面会持续轮询, 每次都往主线程排队
        // 反而会在服务器繁忙时拖慢 tick
        int totalOnline = server.getPlayerList().getPlayerCount();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", nodes);
        data.put("totalOnline", totalOnline);
        data.put("maxPlayers", server.getMaxPlayers());
        // 总在线减去认领到线路的人数, 差值即绕过转发器直连 Minecraft 端口的玩家 (通常是内网)
        data.put("unattributed", Math.max(0, totalOnline - registry.boundPlayerCount()));
        data.put("relayEnabled", config.isNetworkRelayEnabled());

        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(ApiResponse.success(data, "成功获取线路状态")));
        response.getWriter().flush();
    }
}
