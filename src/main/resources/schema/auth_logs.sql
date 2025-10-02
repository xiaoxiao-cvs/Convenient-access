-- 认证日志表
CREATE TABLE IF NOT EXISTS auth_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(50) NOT NULL,
    action_type VARCHAR(20) NOT NULL, -- LOGIN, LOGOUT, REGISTER, FAILED_LOGIN
    success BOOLEAN NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    failure_reason VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_action_type CHECK (action_type IN ('LOGIN', 'LOGOUT', 'REGISTER', 'FAILED_LOGIN', 'TOKEN_REFRESH'))
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_auth_logs_username ON auth_logs(username);
CREATE INDEX IF NOT EXISTS idx_auth_logs_action ON auth_logs(action_type);
CREATE INDEX IF NOT EXISTS idx_auth_logs_created ON auth_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_auth_logs_ip ON auth_logs(ip_address);
