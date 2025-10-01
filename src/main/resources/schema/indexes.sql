-- 白名单表索引
CREATE INDEX IF NOT EXISTS idx_whitelist_name ON whitelist(name);
CREATE INDEX IF NOT EXISTS idx_whitelist_uuid ON whitelist(uuid);
CREATE INDEX IF NOT EXISTS idx_whitelist_added_by ON whitelist(added_by_name);
CREATE INDEX IF NOT EXISTS idx_whitelist_added_at ON whitelist(added_at DESC);
CREATE INDEX IF NOT EXISTS idx_whitelist_active ON whitelist(is_active);
CREATE INDEX IF NOT EXISTS idx_whitelist_source ON whitelist(source);

-- 复合索引
CREATE INDEX IF NOT EXISTS idx_whitelist_active_name ON whitelist(is_active, name);
CREATE INDEX IF NOT EXISTS idx_whitelist_search ON whitelist(is_active, name, uuid);

-- 同步任务表索引
CREATE INDEX IF NOT EXISTS idx_sync_tasks_status ON sync_tasks(status);
CREATE INDEX IF NOT EXISTS idx_sync_tasks_type ON sync_tasks(task_type);
CREATE INDEX IF NOT EXISTS idx_sync_tasks_priority ON sync_tasks(priority DESC, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_sync_tasks_scheduled ON sync_tasks(scheduled_at);

-- 操作日志表索引
CREATE INDEX IF NOT EXISTS idx_operation_log_type ON operation_log(operation_type);
CREATE INDEX IF NOT EXISTS idx_operation_log_target ON operation_log(target_uuid);
CREATE INDEX IF NOT EXISTS idx_operation_log_time ON operation_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_operation_log_ip ON operation_log(operator_ip);

-- 注册令牌表索引
CREATE INDEX IF NOT EXISTS idx_registration_tokens_hash ON registration_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_registration_tokens_expires ON registration_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_registration_tokens_used ON registration_tokens(is_used);

-- 管理员相关表的索引已移除，因为对应的表定义文件不存在
-- 如果将来添加了管理员功能的表定义，可以重新添加这些索引：
-- admin_users, admin_sessions, auth_logs, admin_operation_logs