-- 管理员个人识别码表 (供 QQ Bot 私聊绑定用)
-- 一人一码: admin_id 直接作主键, 重置即 INSERT OR REPLACE 覆盖, 天然保证唯一。
-- 与 registration_tokens (一次性、24h、面向新管理员注册) 的区别: 本表的码长期有效、
-- 归属某个已存在的管理员, 用途是把 QQ 号认领到该管理员名下。
-- 仅存哈希, 不落明文 —— 明文只在签发响应里返回一次, 之后面板只能看前后缀掩码。
CREATE TABLE IF NOT EXISTS admin_personal_codes (
    admin_id    INTEGER PRIMARY KEY,
    code_hash   VARCHAR(128) NOT NULL UNIQUE,
    code_prefix VARCHAR(8)   NOT NULL,        -- 掩码展示: 明文前 8 位
    code_suffix VARCHAR(4)   NOT NULL,        -- 掩码展示: 明文后 4 位
    issued_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_apc_hash ON admin_personal_codes(code_hash);
