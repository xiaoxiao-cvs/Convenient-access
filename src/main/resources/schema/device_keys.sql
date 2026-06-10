-- 设备公钥表 (免密登录二期 DeviceAuth)
-- 服务端只存 Ed25519 公钥, 永不存私钥 (私钥经 DPAPI 封存于客户端本机, 永不过网)。
-- 默认单设备: username 为主键, 换机 /enroll 覆盖旧公钥 (旧机自动失效)。
-- username 小写规范化 (toLowerCase Locale.ROOT), 与 player_auth / 白名单按名查询一致。
CREATE TABLE IF NOT EXISTS device_keys (
    username      VARCHAR(50) PRIMARY KEY,
    public_key    TEXT NOT NULL,                    -- Ed25519 公钥 X.509 编码 Base64
    algorithm     VARCHAR(16) DEFAULT 'Ed25519',
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at  TIMESTAMP
);
