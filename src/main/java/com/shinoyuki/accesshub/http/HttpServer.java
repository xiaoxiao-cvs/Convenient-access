package com.shinoyuki.accesshub.http;

import java.io.IOException;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.api.ApiRouter;
import com.shinoyuki.accesshub.config.AccessHubConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP 服务器
 *
 * 使用 Jetty 提供 RESTful API 入口, 所有请求统一路由到 ApiRouter.
 *
 * v2 起完全摆脱 Bukkit Plugin 依赖, 通过 AccessHubConfig 接口获取运行配置,
 * 通过 SLF4J 直接输出日志.
 */
public class HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private final AccessHubConfig config;
    private final ApiRouter apiRouter;
    private Server server;

    public HttpServer(AccessHubConfig config, ApiRouter apiRouter) {
        this.config = config;
        this.apiRouter = apiRouter;
    }

    public void start() throws Exception {
        int port = config.getHttpPort();
        String host = config.getHttpHost();
        int maxThreads = config.getMaxThreads();

        QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, 2);
        threadPool.setName("AccessHub-HTTP");

        server = new Server(threadPool);
        server.setHandler(new ApiHandler());

        ServerConnector connector = new ServerConnector(server);
        connector.setHost(host);
        connector.setPort(port);
        connector.setIdleTimeout(config.getTimeout());

        server.addConnector(connector);
        server.start();

        logger.info("HTTP 服务器已启动: http://{}:{}",
                "0.0.0.0".equals(host) ? "localhost" : host, port);
    }

    public void stop() {
        if (server != null) {
            try {
                server.stop();
                logger.info("HTTP 服务器已停止");
            } catch (Exception e) {
                logger.warn("停止 HTTP 服务器时发生错误", e);
            }
        }
    }

    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    /**
     * API 请求处理器, 所有路径统一交给 ApiRouter.
     *
     * v1 曾有 ApiManager 备用分支, 但实际请求路径全部被 ApiRouter 覆盖, 备用分支为 dead code, v2 删除.
     */
    private class ApiHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                          HttpServletResponse response) throws IOException {

            baseRequest.setHandled(true);

            try {
                setCorsHeaders(response);

                if ("OPTIONS".equals(request.getMethod())) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                if (apiRouter == null) {
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"success\":false,\"error\":\"API router not initialized\"}");
                    return;
                }

                apiRouter.handleRequest(request, response);

            } catch (Exception e) {
                logger.error("处理 HTTP 请求时发生错误", e);

                try {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"success\":false,\"error\":\"Internal Server Error\"}");
                    response.getWriter().flush();
                } catch (IOException ioException) {
                    logger.error("写入错误响应时发生异常", ioException);
                }
            }
        }

        private void setCorsHeaders(HttpServletResponse response) {
            if (config.isCorsEnabled()) {
                String allowedOrigins = String.join(",", config.getAllowedOrigins());
                if (allowedOrigins.contains("*")) {
                    response.setHeader("Access-Control-Allow-Origin", "*");
                } else {
                    response.setHeader("Access-Control-Allow-Origin", allowedOrigins);
                }

                response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers",
                        "Content-Type, Authorization, X-API-Key, X-Requested-With");
                response.setHeader("Access-Control-Max-Age", "3600");
            }
        }
    }
}
