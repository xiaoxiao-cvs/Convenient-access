package com.xaoxiao.convenientaccess.whitelist;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import com.xaoxiao.convenientaccess.api.ApiRouter;
import com.xaoxiao.convenientaccess.api.PlayerDataApiController;
import com.xaoxiao.convenientaccess.api.UserApiController;
import com.xaoxiao.convenientaccess.api.WhitelistApiController;
import com.xaoxiao.convenientaccess.auth.InitialPasswordGenerator;
import com.xaoxiao.convenientaccess.auth.RegistrationTokenManager;
import com.xaoxiao.convenientaccess.database.DatabaseManager;
import com.xaoxiao.convenientaccess.sync.SyncTaskManager;

/**
 * 白名单管理系统主类（简化版）
 * 整合核心白名单组件，专注于白名单管理和注册令牌功能
 */
public class WhitelistSystem {
    private static final Logger logger = LoggerFactory.getLogger(WhitelistSystem.class);
    
    private final ConvenientAccessPlugin plugin;
    
    // 核心组件
    private DatabaseManager databaseManager;
    private WhitelistManager whitelistManager;
    private SyncTaskManager syncTaskManager;
    private RegistrationTokenManager registrationTokenManager;
    private InitialPasswordGenerator passwordGenerator;
    
    // API组件
    private WhitelistApiController whitelistApiController;
    private UserApiController userApiController;
    private ApiRouter apiRouter;
    
    private boolean initialized = false;
    private String adminPassword = null;
    
    public WhitelistSystem(ConvenientAccessPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * 初始化白名单系统（简化版）
     */
    public CompletableFuture<Boolean> initialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("正在初始化白名单管理系统（简化版）...");
                
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
                
                // 初始化注册令牌管理器
                registrationTokenManager = new RegistrationTokenManager(databaseManager);
                
                // 检查配置文件中是否已有管理员密码
                String existingPassword = plugin.getConfigManager().getAdminPassword();
                if (existingPassword != null && !existingPassword.trim().isEmpty()) {
                    // 使用现有密码
                    adminPassword = existingPassword;
                    logger.info("使用配置文件中的现有管理员密码");
                } else {
                    // 生成新的管理员密码
                    passwordGenerator = new InitialPasswordGenerator();
                    adminPassword = passwordGenerator.generatePassword();
                    passwordGenerator.displayPassword(adminPassword);
                    
                    // 将管理员密码保存到配置文件
                    plugin.getConfigManager().setAdminPassword(adminPassword);
                }
                
                // 初始化API组件
                whitelistApiController = new WhitelistApiController(whitelistManager, syncTaskManager);
                userApiController = new UserApiController(registrationTokenManager, whitelistManager);
                PlayerDataApiController playerDataApiController = new PlayerDataApiController(plugin);
                
                // 设置管理员密码到UserApiController
                userApiController.setAdminPassword(adminPassword);
                
                apiRouter = new ApiRouter(whitelistApiController, userApiController, playerDataApiController, plugin.getConfigManager());
                
                initialized = true;
                logger.info("白名单管理系统（简化版）初始化完成");
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
    
    public RegistrationTokenManager getRegistrationTokenManager() {
        return registrationTokenManager;
    }
    
    public WhitelistApiController getWhitelistApiController() {
        return whitelistApiController;
    }
    
    public UserApiController getUserApiController() {
        return userApiController;
    }
    
    public ApiRouter getApiRouter() {
        return apiRouter;
    }
    
    public String getAdminPassword() {
        return adminPassword;
    }
    
    /**
     * 检查系统是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
}