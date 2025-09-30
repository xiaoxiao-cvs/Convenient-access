package com.xaoxiao.convenientaccess.whitelist;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import com.xaoxiao.convenientaccess.api.AdminApiController;
import com.xaoxiao.convenientaccess.api.ApiRouter;
import com.xaoxiao.convenientaccess.api.WhitelistApiController;
import com.xaoxiao.convenientaccess.auth.AdminAuthManager;
import com.xaoxiao.convenientaccess.auth.ApiKeyManager;
import com.xaoxiao.convenientaccess.auth.AuthenticationFilter;
import com.xaoxiao.convenientaccess.database.DatabaseManager;
import com.xaoxiao.convenientaccess.security.RateLimiter;
import com.xaoxiao.convenientaccess.security.SecurityFilter;
import com.xaoxiao.convenientaccess.security.SecurityMonitor;
import com.xaoxiao.convenientaccess.sync.SyncTaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 白名单管理系统主类（完整版）
 * 整合所有白名单相关组件，包括管理员认证系统和安全防护机制
 */
public class WhitelistSystem {
    private static final Logger logger = LoggerFactory.getLogger(WhitelistSystem.class);
    
    private final ConvenientAccessPlugin plugin;
    
    // 核心组件
    private DatabaseManager databaseManager;
    private WhitelistManager whitelistManager;
    private SyncTaskManager syncTaskManager;
    private ApiKeyManager apiKeyManager;
    private AdminAuthManager adminAuthManager;
    
    // 安全组件
    private RateLimiter rateLimiter;
    private SecurityMonitor securityMonitor;
    private SecurityFilter securityFilter;
    
    // API组件
    private WhitelistApiController whitelistApiController;
    private AdminApiController adminApiController;
    private ApiRouter apiRouter;
    private AuthenticationFilter authenticationFilter;
    
    private boolean initialized = false;
    
    public WhitelistSystem(ConvenientAccessPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * 初始化白名单系统（完整版）
     */
    public CompletableFuture<Boolean> initialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("正在初始化白名单管理系统（完整版）...");
                
                // 初始化数据库管理器
                databaseManager = new DatabaseManager(plugin);
                boolean dbInit = databaseManager.initialize().get();
                if (!dbInit) {
                    throw new RuntimeException("数据库初始化失败");
                }
                
                // 初始化白名单管理器
                whitelistManager = new WhitelistManager(databaseManager);
                boolean whitelistInit = whitelistManager.initialize().get();
                if (!whitelistInit) {
                    throw new RuntimeException("白名单管理器初始化失败");
                }
                
                // 初始化同步任务管理器
                syncTaskManager = new SyncTaskManager(plugin, databaseManager);
                boolean syncInit = syncTaskManager.initialize().get();
                if (!syncInit) {
                    throw new RuntimeException("同步任务管理器初始化失败");
                }
                
                // 初始化API Key管理器
                apiKeyManager = new ApiKeyManager(databaseManager);
                boolean apiKeyInit = apiKeyManager.initialize().get();
                if (!apiKeyInit) {
                    throw new RuntimeException("API Key管理器初始化失败");
                }
                
                // 初始化管理员认证管理器
                adminAuthManager = new AdminAuthManager(databaseManager);
                boolean adminAuthInit = adminAuthManager.initialize().get();
                if (!adminAuthInit) {
                    throw new RuntimeException("管理员认证管理器初始化失败");
                }
                
                // 初始化安全组件
                rateLimiter = new RateLimiter();
                securityMonitor = new SecurityMonitor(databaseManager);
                securityFilter = new SecurityFilter(rateLimiter, securityMonitor);
                
                // 初始化API组件
                whitelistApiController = new WhitelistApiController(whitelistManager, syncTaskManager);
                adminApiController = new AdminApiController(adminAuthManager);
                apiRouter = new ApiRouter(whitelistApiController, adminApiController);
                authenticationFilter = new AuthenticationFilter(apiKeyManager);
                
                initialized = true;
                logger.info("白名单管理系统（完整版）初始化完成");
                return true;
                
            } catch (Exception e) {
                logger.error("白名单管理系统初始化失败", e);
                return false;
            }
        });
    }
    
    /**
     * 关闭白名单系统
     */
    public void shutdown() {
        logger.info("正在关闭白名单管理系统...");
        
        try {
            // 关闭安全组件
            if (rateLimiter != null) {
                rateLimiter.shutdown();
            }
            
            if (securityMonitor != null) {
                securityMonitor.shutdown();
            }
            
            // 关闭同步任务管理器
            if (syncTaskManager != null) {
                syncTaskManager.shutdown();
            }
            
            // 关闭数据库管理器
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
            
            initialized = false;
            logger.info("白名单管理系统已关闭");
        } catch (Exception e) {
            logger.error("关闭白名单管理系统时发生异常", e);
        }
    }
    
    // Getters
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
    
    public WhitelistManager getWhitelistManager() {
        return whitelistManager;
    }
    
    public SyncTaskManager getSyncTaskManager() {
        return syncTaskManager;
    }
    
    public ApiKeyManager getApiKeyManager() {
        return apiKeyManager;
    }
    
    public AdminAuthManager getAdminAuthManager() {
        return adminAuthManager;
    }
    
    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }
    
    public SecurityMonitor getSecurityMonitor() {
        return securityMonitor;
    }
    
    public SecurityFilter getSecurityFilter() {
        return securityFilter;
    }
    
    public WhitelistApiController getWhitelistApiController() {
        return whitelistApiController;
    }
    
    public AdminApiController getAdminApiController() {
        return adminApiController;
    }
    
    public ApiRouter getApiRouter() {
        return apiRouter;
    }
    
    public AuthenticationFilter getAuthenticationFilter() {
        return authenticationFilter;
    }
    
    /**
     * 检查系统是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
}