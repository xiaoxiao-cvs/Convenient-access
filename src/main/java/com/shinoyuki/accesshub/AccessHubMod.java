package com.shinoyuki.accesshub;

import java.nio.file.Path;

import com.mojang.logging.LogUtils;
import com.shinoyuki.accesshub.api.AdminAuthController;
import com.shinoyuki.accesshub.api.ApiRouter;
import com.shinoyuki.accesshub.api.OperationLogApiController;
import com.shinoyuki.accesshub.api.PlayerDataHandler;
import com.shinoyuki.accesshub.api.PlayerDataHandlerImpl;
import com.shinoyuki.accesshub.api.ServerInfoHandler;
import com.shinoyuki.accesshub.api.ServerInfoHandlerImpl;
import com.shinoyuki.accesshub.api.UserApiController;
import com.shinoyuki.accesshub.integration.SparkIntegration;
import com.shinoyuki.accesshub.api.WhitelistApiController;
import com.shinoyuki.accesshub.auth.AdminAuthService;
import com.shinoyuki.accesshub.auth.LoginAttemptService;
import com.shinoyuki.accesshub.auth.RegistrationTokenManager;
import com.shinoyuki.accesshub.backup.BackupManager;
import com.shinoyuki.accesshub.command.AccessHubCommand;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.config.AccessHubConfigImpl;
import com.shinoyuki.accesshub.database.DatabaseManager;
import com.shinoyuki.accesshub.event.PlayerLoginListener;
import com.shinoyuki.accesshub.http.HttpServer;
import com.shinoyuki.accesshub.operation.OperationLogDao;
import com.shinoyuki.accesshub.whitelist.WhitelistManager;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

@Mod(AccessHubMod.MOD_ID)
public final class AccessHubMod {
    public static final String MOD_ID = "shinoyuki_accesshub";

    /** Shinoyuki 生态约定: 所有 mod 共享 config/Shinoyuki-Optimize/&lt;mod_id&gt;/ 状态目录, 配置 + 数据库集中存放便于运维统一备份. */
    private static final String SHINOYUKI_DIR = "Shinoyuki-Optimize";

    private static final Logger LOGGER = LogUtils.getLogger();

    private AccessHubConfig config;
    private DatabaseManager databaseManager;
    private WhitelistManager whitelistManager;
    private AdminAuthService adminAuthService;
    private HttpServer httpServer;
    private BackupManager backupManager;
    private SparkIntegration sparkIntegration;

    public AccessHubMod() {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("AccessHub v0.2.0 loading on Forge 1.20.1");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        try {
            // 传入 MinecraftServer 供 PlayerDataHandler 在主线程采集玩家数据
            initialize(event.getServer());
            LOGGER.info("AccessHub 服务端启动完成");
        } catch (Exception e) {
            // 不向 Forge 抛出: mod 启动失败应该只让本 mod 业务不可用, 而不是把整个服务器拖死
            LOGGER.error("AccessHub 启动失败, 白名单/HTTP API 功能将不可用", e);
        }
    }

    /**
     * 业务初始化, 顺序: 配置 -> 数据库 -> 业务管理器 -> 认证 -> Controllers -> ApiRouter -> HttpServer.
     */
    private void initialize(net.minecraft.server.MinecraftServer server) throws Exception {
        // 1. 状态目录 config/Shinoyuki-Optimize/shinoyuki_accesshub/ (含 common.toml 与 whitelist.db)
        Path baseDir = FMLPaths.CONFIGDIR.get().resolve(SHINOYUKI_DIR).resolve(MOD_ID);
        LOGGER.info("AccessHub 状态目录: {}", baseDir);

        // 2. 配置 (Night Config TOML, 首次启动自动生成默认值与三件套密钥)
        config = new AccessHubConfigImpl(baseDir.resolve("common.toml"));

        // 3. 数据库 (跟配置文件同目录, 简化运维备份: 备份 config/Shinoyuki-Optimize/ 一次性带走所有状态)
        databaseManager = new DatabaseManager(baseDir.toFile());
        if (!databaseManager.initialize().get()) {
            throw new IllegalStateException("数据库初始化失败");
        }

        // 4. 业务管理器
        whitelistManager = new WhitelistManager(databaseManager);
        if (!whitelistManager.initialize().get()) {
            throw new IllegalStateException("白名单管理器初始化失败");
        }
        RegistrationTokenManager tokenManager = new RegistrationTokenManager(databaseManager);
        OperationLogDao operationLogDao = new OperationLogDao(databaseManager);

        // 5. 认证服务
        LoginAttemptService loginAttempt = new LoginAttemptService(
                config.getLoginMaxAttempts(),
                config.getLoginLockDurationMinutes(),
                config.isLoginAttemptLimitEnabled()
        );
        adminAuthService = new AdminAuthService(
                databaseManager,
                tokenManager,
                config.getAdminPassword(),
                config.getJwtSecret(),
                loginAttempt
        );

        // 6. API Controllers
        WhitelistApiController whitelistController = new WhitelistApiController(whitelistManager, operationLogDao);
        UserApiController userController = new UserApiController(tokenManager, whitelistManager);
        OperationLogApiController operationLogController = new OperationLogApiController(operationLogDao);
        AdminAuthController adminAuthController = new AdminAuthController(adminAuthService);
        PlayerDataHandler playerDataHandler = new PlayerDataHandlerImpl(server);
        // Spark 性能监测 (软依赖, 未装 spark mod 时降级为 JVM 数据) + 在线玩家列表
        sparkIntegration = new SparkIntegration(server);
        ServerInfoHandler serverInfoHandler = new ServerInfoHandlerImpl(server, sparkIntegration);

        ApiRouter apiRouter = new ApiRouter(
                whitelistController, userController,
                playerDataHandler, serverInfoHandler,
                operationLogController, adminAuthController,
                config
        );

        // 7. HTTP 服务器
        if (config.isHttpEnabled()) {
            httpServer = new HttpServer(config, apiRouter);
            httpServer.start();
        } else {
            LOGGER.info("HTTP 服务器在配置中已禁用, 跳过启动");
        }

        // 8. 玩家登录监听器 (PreLogin 阶段拦截未在白名单的玩家)
        // 注册到 EVENT_BUS, 实例持有 config / whitelistManager / databaseManager 依赖.
        // 不放进 mod 启动早期是因为它依赖 whitelistManager 已初始化完成 (步骤 4).
        PlayerLoginListener loginListener = new PlayerLoginListener(
                config, whitelistManager, databaseManager);
        MinecraftForge.EVENT_BUS.register(loginListener);
        LOGGER.info("白名单登录监听器已注册到事件总线");

        // 9. 数据库自动备份 (定时备份 whitelist.db, 与数据库同目录)
        backupManager = new BackupManager(baseDir.toFile(), config);
        backupManager.initialize();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("AccessHub 正在关闭...");
        try {
            if (backupManager != null) {
                backupManager.shutdown();
            }
            if (httpServer != null) {
                httpServer.stop();
            }
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
        } catch (Exception e) {
            LOGGER.warn("AccessHub 关闭时发生异常", e);
        }
        LOGGER.info("AccessHub 已关闭");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        AccessHubCommand.register(event.getDispatcher(), this);
        LOGGER.info("AccessHub 命令已注册: /accesshub (alias: /ca /ahub)");
    }

    /**
     * 暴露给命令层使用. mod 启动失败时返回 null, 命令实现负责 null 防御并提示用户.
     */
    public AccessHubConfig getConfig() {
        return config;
    }

    /**
     * 暴露给命令层使用. mod 启动失败或 http 被配置禁用时返回 null.
     */
    public HttpServer getHttpServer() {
        return httpServer;
    }

    /**
     * 暴露给命令层使用. mod 启动失败时返回 null.
     */
    public WhitelistManager getWhitelistManager() {
        return whitelistManager;
    }
}
