-- 迁移脚本: 版本 2 到版本 3
-- 添加玩家离线认证表 (与新库 schema/player_auth.sql 保持一致)

CREATE TABLE IF NOT EXISTS player_auth (
    username       VARCHAR(50) PRIMARY KEY,
    password_hash  VARCHAR(72) NOT NULL,
    registered_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at  TIMESTAMP,
    last_login_ip  VARCHAR(45),
    fail_count     INTEGER DEFAULT 0,
    locked_until   TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_player_auth_locked ON player_auth(locked_until);
