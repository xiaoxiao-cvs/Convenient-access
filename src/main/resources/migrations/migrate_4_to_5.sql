-- 迁移脚本: 版本 4 到版本 5
-- 新增设备公钥表 (与新库 schema/device_keys.sql 保持一致)
-- 免密登录二期 DeviceAuth: 服务端只存 Ed25519 公钥, 私钥经客户端 DPAPI 封存永不过网。

CREATE TABLE IF NOT EXISTS device_keys (
    username      VARCHAR(50) PRIMARY KEY,
    public_key    TEXT NOT NULL,
    algorithm     VARCHAR(16) DEFAULT 'Ed25519',
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at  TIMESTAMP
);
