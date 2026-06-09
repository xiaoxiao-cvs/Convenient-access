-- 迁移脚本: 版本 3 到版本 4
-- 新增玩家注册码表 (与新库 schema/player_registration_codes.sql 保持一致)
-- 离线模式下用于堵死冒名抢注: /register 必须带管理员为该用户名签发的绑定码。

CREATE TABLE IF NOT EXISTS player_registration_codes (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    code_hash      VARCHAR(128) NOT NULL,
    bound_username VARCHAR(50)  NOT NULL,
    expires_at     TIMESTAMP    NOT NULL,
    used_at        TIMESTAMP,
    is_used        BOOLEAN DEFAULT 0,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_prc_username ON player_registration_codes(bound_username);
CREATE INDEX IF NOT EXISTS idx_prc_hash ON player_registration_codes(code_hash);
