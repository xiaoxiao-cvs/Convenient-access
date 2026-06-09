-- 玩家离线认证表
-- username 为小写规范化后的玩家名 (DAO 层在读写前统一 toLowerCase(Locale.ROOT), 与白名单按名查询一致)
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
