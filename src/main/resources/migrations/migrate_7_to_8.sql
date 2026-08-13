-- 迁移脚本: 版本 7 到版本 8
-- 新增 QQ Bot 绑定链路的两张表。纯新增, 不触碰既有表, 因此直接建表即可, 无需重建迁移。
-- 表定义与 schema/admin_personal_codes.sql、schema/admin_qq_bindings.sql 保持逐字一致,
-- 新库走 createTables、老库走本脚本, 两条路径必须产出相同结构。

CREATE TABLE IF NOT EXISTS admin_personal_codes (
    admin_id    INTEGER PRIMARY KEY,
    code_hash   VARCHAR(128) NOT NULL UNIQUE,
    code_prefix VARCHAR(8)   NOT NULL,
    code_suffix VARCHAR(4)   NOT NULL,
    issued_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_apc_hash ON admin_personal_codes(code_hash);

CREATE TABLE IF NOT EXISTS admin_qq_bindings (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_id INTEGER     NOT NULL,
    qq       VARCHAR(20) NOT NULL UNIQUE,
    bound_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_aqb_admin ON admin_qq_bindings(admin_id);
