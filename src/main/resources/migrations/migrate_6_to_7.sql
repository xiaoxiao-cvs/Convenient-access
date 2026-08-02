-- 迁移脚本: 版本 6 到版本 7
-- operation_log 的 CHECK 约束漏掉了代码实际写入的 SET_ACTIVE 与 GENCODE, 导致这两类操作
-- (启用/禁用白名单、签发注册码) 的日志被数据库直接拒收。DAO 捕获 SQLException 后仅返回
-- false, 调用方又不检查返回值, 于是失败一路静默 —— 生产库实测只有 UNAUTHORIZED_ACCESS /
-- ADD / REMOVE 三种类型, SET_ACTIVE 与 GENCODE 各 0 条, 这两类操作至今无任何审计记录。
--
-- SQLite 不支持 ALTER 已有的 CHECK 约束, 只能重建表再迁数据。原有类型全部保留(只放宽、
-- 不收紧), 以免影响任何既有写入路径。

CREATE TABLE operation_log_new (
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

    CONSTRAINT chk_operation_type CHECK (operation_type IN
        ('ADD', 'REMOVE', 'QUERY', 'BATCH_ADD', 'BATCH_REMOVE', 'SYNC',
         'UNAUTHORIZED_ACCESS', 'SET_ACTIVE', 'GENCODE'))
);

INSERT INTO operation_log_new
    (id, operation_type, target_uuid, target_name, operator_ip, operator_agent,
     request_data, response_status, execution_time, created_at)
SELECT
    id, operation_type, target_uuid, target_name, operator_ip, operator_agent,
    request_data, response_status, execution_time, created_at
FROM operation_log;

DROP TABLE operation_log;

ALTER TABLE operation_log_new RENAME TO operation_log;

-- DROP TABLE 会连带删掉表上的索引, 此处按 schema/indexes.sql 原样重建
CREATE INDEX IF NOT EXISTS idx_operation_log_type ON operation_log(operation_type);
CREATE INDEX IF NOT EXISTS idx_operation_log_target ON operation_log(target_uuid);
CREATE INDEX IF NOT EXISTS idx_operation_log_time ON operation_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_operation_log_ip ON operation_log(operator_ip);
