-- 操作日志表
CREATE TABLE IF NOT EXISTS operation_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_type VARCHAR(20) NOT NULL,          -- 操作类型
    target_uuid VARCHAR(36),                      -- 目标玩家UUID
    target_name VARCHAR(16),                      -- 目标玩家名称
    operator_ip VARCHAR(45),                      -- 操作者IP
    operator_agent TEXT,                          -- 用户代理
    request_data TEXT,                            -- 请求数据
    response_status INTEGER,                      -- 响应状态码
    execution_time INTEGER,                       -- 执行时间(ms)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- 约束
    CONSTRAINT chk_operation_type CHECK (operation_type IN ('ADD', 'REMOVE', 'QUERY', 'BATCH_ADD', 'BATCH_REMOVE', 'SYNC'))
);