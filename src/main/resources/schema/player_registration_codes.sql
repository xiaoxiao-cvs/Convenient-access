-- 玩家注册码表 (离线模式防冒名抢注)
-- 与管理员 Web 注册令牌 (registration_tokens) 不同: 本表的码绑定到具体玩家用户名,
-- 只有持有管理员为某用户名签发的码, 才能 /register 注册该用户名。
-- 仅存哈希, 不落明文。bound_username 为小写规范化玩家名 (toLowerCase Locale.ROOT)。
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
