package com.shinoyuki.accesshub.config;

import java.util.List;

/**
 * 配置访问接口。
 *
 * v2 起业务代码统一通过此接口读写配置，与具体的存储后端解耦：
 *  - v1 (Bukkit) 由 com.xaoxiao.convenientaccess.config 下的实现类承担，留在该包供历史构建参考
 *  - v3 (Forge 重写阶段) 将提供基于 Night Config 的 AccessHubConfigImpl
 *
 * 抽象目标: 让 v2 可复用层在 Bukkit 实现就绪前即可编译通过。
 */
public interface AccessHubConfig {

    void reload();

    // HTTP 服务
    boolean isHttpEnabled();
    int getHttpPort();
    String getHttpHost();
    int getMaxThreads();
    int getTimeout();

    // API 基础
    String getApiVersion();
    boolean isAuthEnabled();
    String getApiToken();
    void setApiToken(String token);
    String getTokenPrefix();

    // JWT 签名
    String getJwtSecret();
    void setJwtSecret(String secret);

    // 管理员密码
    String getAdminPassword();
    void setAdminPassword(String password);

    // 登录失败限制
    boolean isLoginAttemptLimitEnabled();
    int getLoginMaxAttempts();
    int getLoginLockDurationMinutes();

    // 请求速率
    boolean isRateLimitEnabled();
    int getRequestsPerMinute();

    // CORS
    boolean isCorsEnabled();
    List<String> getAllowedOrigins();

    // 缓存 TTL (秒)
    int getServerInfoCacheTime();
    int getPerformanceCacheTime();
    int getPlayersCacheTime();
    int getWorldsCacheTime();

    // Spark 集成
    boolean isPreferSpark();
    int getSparkTimeout();

    // 白名单
    boolean isWhitelistEnabled();
    boolean isWhitelistStrictMode();
    String getWhitelistKickMessage();
    String getContactInfo();
    int getTokenExpiryHours();
    boolean isAutoCleanupTokens();
    boolean isJoinNotificationEnabled();
    String getJoinNotificationPermission();
    boolean isWelcomeMessageEnabled();
    String getWelcomeMessage();

    // 日志
    boolean isLogRequests();
    boolean isDebug();
}
