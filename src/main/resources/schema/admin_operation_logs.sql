-- 管理员操作日志表
CREATE TABLE IF NOT EXISTS admin_operation_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id VARCHAR(32) NOT NULL,
    username VARCHAR(32) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64),
    old_value TEXT,
    new_value TEXT,
    ip_address VARCHAR(45) NOT NULL,
    user_agent TEXT,
    session_id VARCHAR(36),
    execution_time INTEGER, -- 执行时间(ms)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);