-- 注册令牌表
CREATE TABLE IF NOT EXISTS registration_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token VARCHAR(64) NOT NULL UNIQUE,            -- 注册令牌
    token_hash VARCHAR(128) NOT NULL,             -- 令牌哈希值
    expires_at TIMESTAMP NOT NULL,                -- 过期时间
    used_at TIMESTAMP,                            -- 使用时间
    used_by_ip VARCHAR(45),                       -- 使用者IP
    is_used BOOLEAN DEFAULT 0,                    -- 是否已使用
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);