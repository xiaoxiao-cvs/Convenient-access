-- 同步任务表
CREATE TABLE IF NOT EXISTS sync_tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_type VARCHAR(20) NOT NULL,               -- 任务类型
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- 任务状态
    priority INTEGER NOT NULL DEFAULT 5,          -- 优先级 (1-10)
    data TEXT,                                     -- JSON格式任务数据
    retry_count INTEGER NOT NULL DEFAULT 0,       -- 重试次数
    max_retries INTEGER NOT NULL DEFAULT 3,       -- 最大重试次数
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    scheduled_at TIMESTAMP,                       -- 计划执行时间
    started_at TIMESTAMP,                         -- 开始执行时间
    completed_at TIMESTAMP,                       -- 完成时间
    error_message TEXT,                           -- 错误信息
    
    -- 约束
    CONSTRAINT chk_task_type CHECK (task_type IN ('FULL_SYNC', 'ADD_PLAYER', 'REMOVE_PLAYER', 'BATCH_UPDATE')),
    CONSTRAINT chk_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'))
);