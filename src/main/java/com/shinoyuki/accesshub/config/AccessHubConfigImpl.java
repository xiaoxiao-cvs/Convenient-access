package com.shinoyuki.accesshub.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;

/**
 * AccessHubConfig 的 Night Config TOML 实现.
 *
 * 配置文件路径: config/Shinoyuki-Optimize/shinoyuki_accesshub/common.toml
 *
 * 首次启动行为:
 *  - 自动创建配置目录
 *  - 写入默认值与字段注释
 *  - 用 SecureRandom 生成 admin password / API token / JWT secret 三件套
 *  - 在日志中打印 admin password 一次, 之后只能从配置文件读取
 *
 * 线程安全: Night Config 对 CommentedFileConfig 内部加锁, 多线程 get/set 安全.
 * autosave 让每次 set 立即写盘.
 */
public final class AccessHubConfigImpl implements AccessHubConfig {

    private static final Logger logger = LoggerFactory.getLogger(AccessHubConfigImpl.class);

    private static final String CHARSET_ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CommentedFileConfig config;

    public AccessHubConfigImpl(Path configFile) throws IOException {
        Files.createDirectories(configFile.getParent());

        boolean firstRun = !Files.exists(configFile);

        this.config = CommentedFileConfig.builder(configFile, TomlFormat.instance())
                .sync()
                .autosave()
                .build();
        this.config.load();

        if (firstRun) {
            populateDefaults();
            logger.info("已生成默认配置: {}", configFile);
        }

        ensureSecrets();
    }

    /**
     * 首次启动写入默认值 + 字段注释.
     */
    private void populateDefaults() {
        config.set("http.enabled", true);
        config.setComment("http.enabled", " 是否启用 HTTP 服务器");
        config.set("http.port", 22222);
        config.setComment("http.port", " 监听端口");
        config.set("http.host", "0.0.0.0");
        config.setComment("http.host", " 监听地址 (0.0.0.0 表示所有接口)");
        config.set("http.max-threads", 10);
        config.setComment("http.max-threads", " Jetty 线程池上限");
        config.set("http.timeout", 30000);
        config.setComment("http.timeout", " 连接闲置超时 (毫秒)");

        config.set("api.version", "v1");
        config.set("api.auth.enabled", true);
        config.setComment("api.auth.enabled", " 是否启用 API 鉴权 (生产环境强烈建议开启)");
        config.set("api.auth.admin-password", "");
        config.setComment("api.auth.admin-password", " 管理员密码 (首次启动自动生成 12 位)");
        config.set("api.auth.api-token", "");
        config.setComment("api.auth.api-token", " API 访问令牌 (首次启动自动生成 sk- 开头的 64 位)");
        config.set("api.auth.token-prefix", "sk-");
        config.set("api.auth.jwt-secret", "");
        config.setComment("api.auth.jwt-secret",
                " JWT 签名密钥 (首次启动生成 base64 256 位强随机). 修改此值会让所有已签发的 token 立即失效");

        config.set("api.auth.login-attempt-limit.enabled", true);
        config.set("api.auth.login-attempt-limit.max-attempts", 5);
        config.set("api.auth.login-attempt-limit.lock-duration-minutes", 15);

        config.set("api.rate-limit.enabled", false);
        config.set("api.rate-limit.requests-per-minute", 60);

        config.set("api.cors.enabled", true);
        config.set("api.cors.allowed-origins", new ArrayList<>(List.of("*")));

        config.set("cache.server-info", 300);
        config.set("cache.performance", 5);
        config.set("cache.players", 10);
        config.set("cache.worlds", 60);

        config.set("spark.prefer-spark", true);
        config.set("spark.timeout", 5000);

        config.set("whitelist.enabled", true);
        config.set("whitelist.strict-mode", true);
        config.setComment("whitelist.strict-mode",
                " 严格模式: 数据库查询失败时拒绝玩家连接. 关闭则放行 (适合调试)");
        config.set("whitelist.kick-message",
                "&c您不在服务器白名单中！\n&7请联系管理员申请加入白名单");
        config.set("whitelist.contact-info", "请联系管理员");
        config.set("whitelist.token-expiry-hours", 24);
        config.set("whitelist.auto-cleanup-tokens", true);
        config.set("whitelist.join-notification.enabled", true);
        config.set("whitelist.join-notification.permission", "accesshub.whitelist.notify");
        config.set("whitelist.welcome-message.enabled", true);
        config.set("whitelist.welcome-message.text", "&a欢迎回到服务器！\n&7玩家: &e{player}");

        config.set("logging.log-requests", false);
        config.set("logging.debug", false);

        config.save();
    }

    /**
     * 确保 admin password / api token / jwt secret 三件套都已生成.
     */
    private void ensureSecrets() {
        if (getAdminPassword().isEmpty()) {
            String pwd = generateRandomString(12, CHARSET_ALPHANUMERIC);
            setAdminPassword(pwd);
            logger.warn("自动生成管理员密码 (首次启动一次性输出, 请妥善保管): {}", pwd);
        }

        if (getApiToken().isEmpty()) {
            String prefix = config.getOrElse("api.auth.token-prefix", "sk-");
            String token = prefix + generateRandomString(64 - prefix.length(), CHARSET_ALPHANUMERIC);
            setApiToken(token);
            logger.warn("自动生成 API 访问令牌: {}", token);
        }

        if (getJwtSecret().isEmpty()) {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            setJwtSecret(Base64.getEncoder().encodeToString(bytes));
            logger.info("自动生成 JWT 签名密钥 (256 bit, 已写入配置文件)");
        }
    }

    private static String generateRandomString(int length, String charset) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(charset.charAt(RANDOM.nextInt(charset.length())));
        }
        return sb.toString();
    }

    @Override
    public void reload() {
        config.load();
    }

    @Override public boolean isHttpEnabled()   { return config.getOrElse("http.enabled", true); }
    @Override public int     getHttpPort()     { return config.getIntOrElse("http.port", 22222); }
    @Override public String  getHttpHost()     { return config.getOrElse("http.host", "0.0.0.0"); }
    @Override public int     getMaxThreads()   { return config.getIntOrElse("http.max-threads", 10); }
    @Override public int     getTimeout()      { return config.getIntOrElse("http.timeout", 30000); }

    @Override public String  getApiVersion()   { return config.getOrElse("api.version", "v1"); }
    @Override public boolean isAuthEnabled()   { return config.getOrElse("api.auth.enabled", true); }
    @Override public String  getApiToken()     { return config.getOrElse("api.auth.api-token", ""); }
    @Override public void    setApiToken(String token) { config.set("api.auth.api-token", token); }
    @Override public String  getTokenPrefix()  { return config.getOrElse("api.auth.token-prefix", "sk-"); }

    @Override public String  getJwtSecret()    { return config.getOrElse("api.auth.jwt-secret", ""); }
    @Override public void    setJwtSecret(String secret) { config.set("api.auth.jwt-secret", secret); }

    @Override public String  getAdminPassword() { return config.getOrElse("api.auth.admin-password", ""); }
    @Override public void    setAdminPassword(String password) { config.set("api.auth.admin-password", password); }

    @Override public boolean isLoginAttemptLimitEnabled() { return config.getOrElse("api.auth.login-attempt-limit.enabled", true); }
    @Override public int     getLoginMaxAttempts()        { return config.getIntOrElse("api.auth.login-attempt-limit.max-attempts", 5); }
    @Override public int     getLoginLockDurationMinutes(){ return config.getIntOrElse("api.auth.login-attempt-limit.lock-duration-minutes", 15); }

    @Override public boolean isRateLimitEnabled()    { return config.getOrElse("api.rate-limit.enabled", false); }
    @Override public int     getRequestsPerMinute() { return config.getIntOrElse("api.rate-limit.requests-per-minute", 60); }

    @Override public boolean isCorsEnabled() { return config.getOrElse("api.cors.enabled", true); }

    @Override
    public List<String> getAllowedOrigins() {
        // Night Config 的 List 内部是 List<Object>, 需要逐元素 toString 化以满足接口契约
        List<Object> raw = config.getOrElse("api.cors.allowed-origins", new ArrayList<>(List.of("*")));
        List<String> result = new ArrayList<>(raw.size());
        for (Object o : raw) {
            result.add(String.valueOf(o));
        }
        return result;
    }

    @Override public int getServerInfoCacheTime()  { return config.getIntOrElse("cache.server-info", 300); }
    @Override public int getPerformanceCacheTime() { return config.getIntOrElse("cache.performance", 5); }
    @Override public int getPlayersCacheTime()     { return config.getIntOrElse("cache.players", 10); }
    @Override public int getWorldsCacheTime()      { return config.getIntOrElse("cache.worlds", 60); }

    @Override public boolean isPreferSpark()  { return config.getOrElse("spark.prefer-spark", true); }
    @Override public int     getSparkTimeout(){ return config.getIntOrElse("spark.timeout", 5000); }

    @Override public boolean isWhitelistEnabled()    { return config.getOrElse("whitelist.enabled", true); }
    @Override public boolean isWhitelistStrictMode() { return config.getOrElse("whitelist.strict-mode", true); }

    @Override
    public String getWhitelistKickMessage() {
        return config.getOrElse("whitelist.kick-message",
                "&c您不在服务器白名单中！\n&7请联系管理员申请加入白名单");
    }

    @Override public String getContactInfo()           { return config.getOrElse("whitelist.contact-info", "请联系管理员"); }
    @Override public int    getTokenExpiryHours()      { return config.getIntOrElse("whitelist.token-expiry-hours", 24); }
    @Override public boolean isAutoCleanupTokens()     { return config.getOrElse("whitelist.auto-cleanup-tokens", true); }
    @Override public boolean isJoinNotificationEnabled() { return config.getOrElse("whitelist.join-notification.enabled", true); }

    @Override
    public String getJoinNotificationPermission() {
        return config.getOrElse("whitelist.join-notification.permission", "accesshub.whitelist.notify");
    }

    @Override public boolean isWelcomeMessageEnabled() { return config.getOrElse("whitelist.welcome-message.enabled", true); }

    @Override
    public String getWelcomeMessage() {
        return config.getOrElse("whitelist.welcome-message.text", "&a欢迎回到服务器！\n&7玩家: &e{player}");
    }

    @Override public boolean isLogRequests() { return config.getOrElse("logging.log-requests", false); }
    @Override public boolean isDebug()       { return config.getOrElse("logging.debug", false); }
}
