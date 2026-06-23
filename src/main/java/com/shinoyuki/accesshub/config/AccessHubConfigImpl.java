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

        config.set("api.cors.enabled", true);
        config.set("api.cors.allowed-origins", new ArrayList<>(List.of("*")));

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
        config.setComment("whitelist.join-notification.enabled",
                " 白名单玩家加入时向在线 OP (权限等级>=2) 广播通知");
        config.set("whitelist.welcome-message.enabled", true);
        config.set("whitelist.welcome-message.text", "&a欢迎回到服务器！\n&7玩家: &e{player}");

        config.set("auth.enabled", true);
        config.setComment("auth.enabled",
                " 玩家离线认证总开关: 开启后进服玩家必须 /register 注册并 /login 登录, 未认证前全限制");
        config.set("auth.timeout-seconds", 60);
        config.setComment("auth.timeout-seconds", " 进服后未在此秒数内完成认证则踢出");
        config.set("auth.max-attempts", 5);
        config.setComment("auth.max-attempts",
                " 同一会话登录密码连续错误上限, 达到后踢下线 (重连即重置, 不持久锁号; 按 IP 锁因动态公网作废)");
        config.set("auth.lock-minutes", 10);
        config.setComment("auth.lock-minutes", " 已弃用: 失败改为会话内踢出, 此项不再生效, 仅保留以兼容旧配置");
        config.set("auth.min-password-length", 8);
        config.setComment("auth.min-password-length", " 注册 / 改密的密码最低位数");
        config.set("auth.reject-weak-password", true);
        config.setComment("auth.reject-weak-password",
                " 拒绝弱密码 (纯数字 / 与用户名相同 / 常见弱口令)");
        config.set("auth.code-expiry-minutes", 1440);
        config.setComment("auth.code-expiry-minutes",
                " 加白时生成的注册码有效期 (分钟), 默认 1440=24 小时; 一次性, 仅限绑定的用户名");
        config.set("auth.device-auth.enabled", true);
        config.setComment("auth.device-auth.enabled",
                " 免密登录二期: 装了本 mod 的客户端进服时服务端用设备公钥验签自动解冻; 没装/验签失败静默回退密码登录");
        config.set("auth.device-auth.challenge-timeout-seconds", 5);
        config.setComment("auth.device-auth.challenge-timeout-seconds",
                " 免密挑战宽限秒数, 必须远小于 auth.timeout-seconds, 否则未装 mod 的玩家会在能 /login 前被踢");

        config.set("backup.enabled", true);
        config.set("backup.schedule", "0:2:0");
        config.setComment("backup.schedule", " 备份计划 \"天:小时:分钟\", 如 0:2:0=每天02:00, 1:0:0=每隔1天的00:00");
        config.set("backup.retention-days", 7);
        config.set("backup.compress", true);
        config.setComment("backup.compress", " 是否压缩备份为 ZIP");

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

        if (getServerInstanceId().isEmpty()) {
            // 免密签名域分隔用: 持久化, 避免每次重启使飞行中挑战失效 + 客户端按实例分文件失配
            config.set("auth.device-auth.server-instance-id", generateRandomString(24, CHARSET_ALPHANUMERIC));
            logger.info("自动生成 DeviceAuth 服务器实例标识 (持久化于配置)");
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

    @Override public boolean isWhitelistEnabled()    { return config.getOrElse("whitelist.enabled", true); }
    @Override public boolean isWhitelistStrictMode() { return config.getOrElse("whitelist.strict-mode", true); }

    @Override
    public String getWhitelistKickMessage() {
        return config.getOrElse("whitelist.kick-message",
                "&c您不在服务器白名单中！\n&7请联系管理员申请加入白名单");
    }

    @Override
    public String getWhitelistDisabledMessage() {
        return config.getOrElse("whitelist.disabled-message",
                "&c您已在白名单中，但管理员手动关闭了您的访问权限\n&7如有疑问请联系管理员");
    }

    @Override public String getContactInfo()           { return config.getOrElse("whitelist.contact-info", "请联系管理员"); }
    @Override public int    getTokenExpiryHours()      { return config.getIntOrElse("whitelist.token-expiry-hours", 24); }
    @Override public boolean isAutoCleanupTokens()     { return config.getOrElse("whitelist.auto-cleanup-tokens", true); }
    @Override public boolean isJoinNotificationEnabled() { return config.getOrElse("whitelist.join-notification.enabled", true); }

    @Override public boolean isWelcomeMessageEnabled() { return config.getOrElse("whitelist.welcome-message.enabled", true); }

    @Override
    public String getWelcomeMessage() {
        return config.getOrElse("whitelist.welcome-message.text", "&a欢迎回到服务器！\n&7玩家: &e{player}");
    }

    @Override public boolean isPlayerAuthEnabled()       { return config.getOrElse("auth.enabled", true); }
    @Override public int     getPlayerAuthTimeoutSeconds(){ return config.getIntOrElse("auth.timeout-seconds", 60); }
    @Override public int     getPlayerAuthMaxAttempts()  { return config.getIntOrElse("auth.max-attempts", 5); }
    @Override public int     getPlayerAuthLockMinutes()  { return config.getIntOrElse("auth.lock-minutes", 10); }
    @Override public int     getPlayerAuthMinPasswordLength()   { return config.getIntOrElse("auth.min-password-length", 8); }
    @Override public boolean isPlayerAuthRejectWeakPassword()   { return config.getOrElse("auth.reject-weak-password", true); }
    @Override public int     getPlayerAuthCodeExpiryMinutes()   { return config.getIntOrElse("auth.code-expiry-minutes", 1440); }
    @Override public boolean isDeviceAuthEnabled()                  { return config.getOrElse("auth.device-auth.enabled", true); }
    @Override public int     getDeviceAuthChallengeTimeoutSeconds(){ return config.getIntOrElse("auth.device-auth.challenge-timeout-seconds", 5); }
    @Override public String  getServerInstanceId()                 { return config.getOrElse("auth.device-auth.server-instance-id", ""); }

    @Override public boolean isBackupEnabled()       { return config.getOrElse("backup.enabled", true); }
    @Override public String  getBackupSchedule()     { return config.getOrElse("backup.schedule", "0:2:0"); }
    @Override public int     getBackupRetentionDays(){ return config.getIntOrElse("backup.retention-days", 7); }
    @Override public boolean isBackupCompress()      { return config.getOrElse("backup.compress", true); }

    @Override public boolean isLogRequests() { return config.getOrElse("logging.log-requests", false); }
    @Override public boolean isDebug()       { return config.getOrElse("logging.debug", false); }
}
