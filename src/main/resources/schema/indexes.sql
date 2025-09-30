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

-- 管理员用户表索引
CREATE INDEX IF NOT EXISTS idx_admin_users_username ON admin_users(username);
CREATE INDEX IF NOT EXISTS idx_admin_users_email ON admin_users(email);

-- 管理员会话表索引
CREATE INDEX IF NOT EXISTS idx_admin_sessions_session_id ON admin_sessions(session_id);
CREATE INDEX IF NOT EXISTS idx_admin_sessions_user_id ON admin_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_admin_sessions_expires_at ON admin_sessions(expires_at);

-- 认证日志表索引
CREATE INDEX IF NOT EXISTS idx_auth_logs_event_type ON auth_logs(event_type);
CREATE INDEX IF NOT EXISTS idx_auth_logs_username ON auth_logs(username);
CREATE INDEX IF NOT EXISTS idx_auth_logs_ip_address ON auth_logs(ip_address);
CREATE INDEX IF NOT EXISTS idx_auth_logs_created_at ON auth_logs(created_at DESC);

-- 管理员操作日志表索引
CREATE INDEX IF NOT EXISTS idx_admin_operation_logs_user_id ON admin_operation_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_admin_operation_logs_operation ON admin_operation_logs(operation);
CREATE INDEX IF NOT EXISTS idx_admin_operation_logs_resource_type ON admin_operation_logs(resource_type);
CREATE INDEX IF NOT EXISTS idx_admin_operation_logs_created_at ON admin_operation_logs(created_at DESC);