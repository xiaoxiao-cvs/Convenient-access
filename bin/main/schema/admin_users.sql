-- 管理员用户表
CREATE TABLE IF NOT EXISTS admin_users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    email VARCHAR(100),
    is_super_admin BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP,
    last_login_ip VARCHAR(45)
);

-- 创建默认超级管理员（密码将在代码中设置为配置文件中的管理员密码）
-- INSERT INTO admin_users (username, password_hash, display_name, is_super_admin, is_active) 
-- VALUES ('admin', '', '超级管理员', TRUE, TRUE);

-- 索引
CREATE INDEX IF NOT EXISTS idx_admin_users_username ON admin_users(username);
CREATE INDEX IF NOT EXISTS idx_admin_users_active ON admin_users(is_active);
CREATE INDEX IF NOT EXISTS idx_admin_users_created ON admin_users(created_at DESC);
