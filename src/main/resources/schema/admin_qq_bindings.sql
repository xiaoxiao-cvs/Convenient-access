-- QQ 号与管理员账号的绑定表
-- qq 唯一: 一个 QQ 只能认领到一个管理员, 避免两个管理员共用一个 QQ 时无法归因操作者。
-- 反向不限制: 同一管理员可绑多个 QQ (大小号)。
-- 解绑即 DELETE, 不留 tombstone —— 重新绑定只需再发一次识别码, 保留死行没有价值。
CREATE TABLE IF NOT EXISTS admin_qq_bindings (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_id INTEGER     NOT NULL,
    qq       VARCHAR(20) NOT NULL UNIQUE,
    bound_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_aqb_admin ON admin_qq_bindings(admin_id);
