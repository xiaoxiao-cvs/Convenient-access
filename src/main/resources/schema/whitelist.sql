-- 白名单主表
CREATE TABLE IF NOT EXISTS whitelist (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(16) NOT NULL,                    -- 玩家名称
    uuid VARCHAR(36) NOT NULL UNIQUE,             -- 玩家UUID (唯一)
    added_by_name VARCHAR(16) NOT NULL,           -- 添加者名称
    added_by_uuid VARCHAR(36) NOT NULL,           -- 添加者UUID
    added_at TIMESTAMP NOT NULL,                  -- 添加时间 (插件格式)
    source VARCHAR(10) NOT NULL DEFAULT 'PLAYER', -- 来源类型
    is_active BOOLEAN NOT NULL DEFAULT 1,         -- 是否激活
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- 约束
    CONSTRAINT chk_source CHECK (source IN ('PLAYER', 'ADMIN', 'SYSTEM'))
);