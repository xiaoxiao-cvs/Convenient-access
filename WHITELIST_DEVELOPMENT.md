# 白名单管理系统开发文档

## 项目概述

本文档描述了基于SQLite + 任务同步模式的白名单管理系统架构设计。该系统旨在为WhitelistPlus插件提供高性能的Web可视化管理界面，支持大规模白名单数据的高效操作。

## 技术选型

### 核心技术栈
- **数据库**: SQLite 3.42.0+ (轻量级、无服务器、事务支持)
- **Web框架**: Jetty 11.0.15 (已集成在现有项目中)
- **JSON处理**: Gson 2.10.1 (已集成)
- **并发处理**: Java CompletableFuture + ExecutorService
- **数据同步**: 基于任务队列的异步同步机制

### 选型理由
1. **SQLite**: 无需额外服务器，支持并发读取，事务保证数据一致性
2. **任务同步**: 解耦Web操作与文件IO，提高响应速度
3. **异步处理**: 避免阻塞主线程，提升用户体验

## 系统架构

### 整体架构图
```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Web Frontend  │    │   HTTP Server   │    │  WhitelistPlus  │
│                 │◄──►│    (Jetty)      │    │     Plugin      │
│  - 管理界面      │    │  - API Routes   │    │  - JSON File    │
└─────────────────┘    │  - Auth & CORS  │    │  - File Watch   │
                       └─────────────────┘    └─────────────────┘
                                │                       ▲
                                ▼                       │
                       ┌─────────────────┐              │
                       │ WhitelistManager│              │
                       │                 │              │
                       │ - CRUD操作       │              │
                       │ - 数据验证       │              │
                       │ - 缓存管理       │              │
                       └─────────────────┘              │
                                │                       │
                                ▼                       │
                       ┌─────────────────┐              │
                       │ SQLite Database │              │
                       │                 │              │
                       │ - whitelist     │              │
                       │ - sync_tasks    │              │
                       │ - operation_log │              │
                       └─────────────────┘              │
                                │                       │
                                ▼                       │
                       ┌─────────────────┐              │
                       │ SyncTaskManager │              │
                       │                 │              │
                       │ - 任务调度      │──────────────┘
                       │ - 文件同步      │
                       │ - 错误重试      │
                       └─────────────────┘
```

### 核心组件设计

#### 1. WhitelistManager (白名单管理器)
**职责**:
- 提供白名单的CRUD操作接口
- 数据验证和格式化
- 缓存管理和性能优化
- 与SyncTaskManager协调数据同步

**主要功能**:
- 分页查询白名单数据
- 按条件搜索和筛选
- 添加/删除白名单条目
- 批量操作支持
- 统计信息生成

#### 2. SyncTaskManager (同步任务管理器)
**职责**:
- 管理数据库与JSON文件的同步
- 任务队列调度和执行
- 错误处理和重试机制
- 同步状态监控

**同步策略**:
- **启动同步**: 插件启动时从JSON全量导入
- **实时同步**: Web操作后异步同步到JSON
- **定时同步**: 定期检查并处理待同步任务
- **冲突解决**: 基于时间戳的冲突解决策略

#### 3. DatabaseManager (数据库管理器)
**职责**:
- SQLite连接池管理
- 数据库初始化和迁移
- 事务管理
- 性能监控和优化

#### 4. ApiController (API控制器)
**职责**:
- HTTP请求路由和处理
- 请求参数验证
- 响应格式化
- 错误处理和状态码管理

## 数据库设计

### 表结构设计

#### whitelist (白名单主表)
```sql
CREATE TABLE whitelist (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(16) NOT NULL,                    -- 玩家名称
    uuid VARCHAR(36) NOT NULL UNIQUE,             -- 玩家UUID (唯一)
    added_by_name VARCHAR(16) NOT NULL,           -- 添加者名称
    added_by_uuid VARCHAR(36) NOT NULL,           -- 添加者UUID
    added_at TIMESTAMP NOT NULL,                  -- 添加时间 (插件格式)
    source VARCHAR(10) NOT NULL DEFAULT 'PLAYER', -- 来源类型
    is_active BOOLEAN NOT NULL DEFAULT 1,         -- 是否激活
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- 约束
    CONSTRAINT chk_source CHECK (source IN ('PLAYER', 'ADMIN', 'SYSTEM'))
);
```

#### sync_tasks (同步任务表)
```sql
CREATE TABLE sync_tasks (
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
```

#### operation_log (操作日志表)
```sql
CREATE TABLE operation_log (
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
    
    -- 约束
    CONSTRAINT chk_operation_type CHECK (operation_type IN ('ADD', 'REMOVE', 'QUERY', 'BATCH_ADD', 'BATCH_REMOVE', 'SYNC'))
);
```

#### registration_tokens (注册令牌表)
```sql
CREATE TABLE registration_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token VARCHAR(64) NOT NULL UNIQUE,            -- 注册令牌
    token_hash VARCHAR(128) NOT NULL,             -- 令牌哈希值
    expires_at TIMESTAMP NOT NULL,                -- 过期时间
    used_at TIMESTAMP,                            -- 使用时间
    used_by_ip VARCHAR(45),                       -- 使用者IP
    is_used BOOLEAN DEFAULT 0,                    -- 是否已使用
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_token_hash (token_hash),
    INDEX idx_expires_at (expires_at),
    INDEX idx_is_used (is_used)
);
```

### 索引设计
```sql
-- 白名单表索引
CREATE INDEX idx_whitelist_name ON whitelist(name);
CREATE INDEX idx_whitelist_uuid ON whitelist(uuid);
CREATE INDEX idx_whitelist_added_by ON whitelist(added_by_name);
CREATE INDEX idx_whitelist_added_at ON whitelist(added_at DESC);
CREATE INDEX idx_whitelist_active ON whitelist(is_active);
CREATE INDEX idx_whitelist_source ON whitelist(source);

-- 复合索引
CREATE INDEX idx_whitelist_active_name ON whitelist(is_active, name);
CREATE INDEX idx_whitelist_search ON whitelist(is_active, name, uuid);

-- 同步任务表索引
CREATE INDEX idx_sync_tasks_status ON sync_tasks(status);
CREATE INDEX idx_sync_tasks_type ON sync_tasks(task_type);
CREATE INDEX idx_sync_tasks_priority ON sync_tasks(priority DESC, created_at ASC);
CREATE INDEX idx_sync_tasks_scheduled ON sync_tasks(scheduled_at);

-- 操作日志表索引
CREATE INDEX idx_operation_log_type ON operation_log(operation_type);
CREATE INDEX idx_operation_log_target ON operation_log(target_uuid);
CREATE INDEX idx_operation_log_time ON operation_log(created_at DESC);
CREATE INDEX idx_operation_log_ip ON operation_log(operator_ip);

-- 注册令牌表索引
CREATE INDEX idx_registration_tokens_hash ON registration_tokens(token_hash);
CREATE INDEX idx_registration_tokens_expires ON registration_tokens(expires_at);
CREATE INDEX idx_registration_tokens_used ON registration_tokens(is_used);
```

## API接口设计

### RESTful API规范

#### 基础路径
```
/api/v1/whitelist
```

#### 认证和授权
详见下方"鉴权系统设计"章节的完整说明。

#### 接口列表

##### 1. 查询接口
```http
GET /api/v1/whitelist
```
**功能**: 分页查询白名单
**参数**:
- `page`: 页码 (默认: 1)
- `size`: 每页大小 (默认: 20, 最大: 100)
- `search`: 搜索关键词 (支持名称和UUID)
- `source`: 来源筛选
- `added_by`: 添加者筛选
- `sort`: 排序字段 (name, added_at, created_at)
- `order`: 排序方向 (asc, desc)

**响应格式**:
```json
{
  "success": true,
  "data": {
    "items": [...],
    "pagination": {
      "page": 1,
      "size": 20,
      "total": 7000,
      "pages": 350
    }
  },
  "timestamp": "2024-01-01T12:00:00Z"
}
```

##### 2. 添加接口
```http
POST /api/v1/whitelist
```
**功能**: 添加玩家到白名单
**请求体**:
```json
{
  "name": "PlayerName",
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "added_by_name": "AdminName",
  "added_by_uuid": "550e8400-e29b-41d4-a716-446655440001",
  "source": "ADMIN"
}
```

##### 3. 删除接口
```http
DELETE /api/v1/whitelist/{uuid}
```
**功能**: 从白名单移除玩家

##### 4. 批量操作接口
```http
POST /api/v1/whitelist/batch
```
**功能**: 批量添加或删除
**请求体**:
```json
{
  "operation": "add",
  "players": [
    {
      "name": "Player1",
      "uuid": "uuid1"
    }
  ],
  "added_by_name": "AdminName",
  "added_by_uuid": "admin_uuid"
}
```

##### 5. 统计接口
```http
GET /api/v1/whitelist/stats
```
**功能**: 获取白名单统计信息
**响应**:
```json
{
  "success": true,
  "data": {
    "total_players": 7000,
    "active_players": 6950,
    "sources": {
      "PLAYER": 6000,
      "ADMIN": 950,
      "SYSTEM": 50
    },
    "recent_additions": 25,
    "growth_trend": "stable"
  }
}
```

##### 6. 同步管理接口
```http
POST /api/v1/whitelist/sync
GET /api/v1/whitelist/sync/status
GET /api/v1/whitelist/sync/tasks
```

##### 7. 管理员注册接口
```http
POST /api/v1/admin/register
```
**功能**: 管理员注册（需要一次性token验证）
**请求体**:
```json
{
  "username": "newadmin",
  "password": "SecurePassword123!",
  "email": "admin@example.com",
  "registration_token": "one-time-token-from-config"
}
```
**响应**:
```json
{
  "success": true,
  "message": "管理员注册成功",
  "user_id": "admin123"
}
```

**安全机制**:
- 一次性token验证，使用后立即失效
- token在配置文件中生成，有效期24小时
- 注册成功后自动分配默认角色

## 数据同步机制

### 同步流程设计

#### 1. 启动时全量同步
```
1. 检查数据库是否存在且有效
2. 读取JSON文件获取最新数据
3. 比较数据版本和完整性
4. 执行全量导入或增量更新
5. 建立文件监控机制
```

#### 2. Web操作同步流程
```
1. 接收Web请求
2. 验证请求参数
3. 更新SQLite数据库
4. 创建同步任务
5. 返回操作结果
6. 异步执行同步任务
7. 更新JSON文件
8. 记录同步结果
```

#### 3. 任务调度机制
- **优先级队列**: 高优先级任务优先执行
- **批量处理**: 合并相似任务减少IO操作
- **错误重试**: 指数退避重试策略
- **死信队列**: 失败任务隔离处理

### 冲突解决策略

#### 1. 时间戳优先
- 以最新的操作时间为准
- 记录冲突日志便于审计

#### 2. 操作类型优先级
```
删除操作 > 添加操作 > 更新操作
```

#### 3. 数据完整性检查
- UUID唯一性验证
- 玩家名称格式验证
- 时间戳合理性检查

## 性能优化策略

### 1. 数据库优化
- **连接池**: 复用数据库连接
- **预编译语句**: 减少SQL解析开销
- **批量操作**: 使用事务批量提交
- **索引优化**: 针对查询模式优化索引

### 2. 缓存策略
- **查询缓存**: 缓存热点查询结果
- **统计缓存**: 缓存统计数据减少计算
- **LRU淘汰**: 内存不足时淘汰最少使用数据

### 3. 异步处理
- **非阻塞IO**: 使用CompletableFuture异步处理
- **线程池**: 合理配置线程池大小
- **队列缓冲**: 使用队列缓冲高并发请求

### 4. 文件操作优化
- **文件锁**: 防止并发写入冲突
- **原子操作**: 使用临时文件保证原子性
- **压缩存储**: 大文件启用压缩减少IO

## 错误处理和监控

### 1. 错误分类
- **业务错误**: 参数验证失败、数据冲突等
- **系统错误**: 数据库连接失败、文件IO错误等
- **网络错误**: 请求超时、连接中断等

### 2. 错误处理策略
- **优雅降级**: 部分功能不可用时保证核心功能
- **自动恢复**: 临时错误自动重试
- **错误隔离**: 防止错误传播影响其他功能

### 3. 监控指标
- **性能指标**: 响应时间、吞吐量、错误率
- **业务指标**: 白名单增长、操作频率、用户活跃度
- **系统指标**: CPU使用率、内存占用、磁盘IO

### 4. 日志记录
- **结构化日志**: 使用JSON格式便于分析
- **日志级别**: DEBUG、INFO、WARN、ERROR
- **日志轮转**: 防止日志文件过大
- **敏感信息**: 脱敏处理用户隐私数据

## 鉴权系统设计

### 1. 系统鉴权架构

#### 双层鉴权模式
```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Web Client    │    │  API Gateway    │    │  Service Layer  │
│                 │    │                 │    │                 │
│ - 管理员登录    │◄──►│ - 基础API鉴权   │◄──►│ - 业务逻辑      │
│ - Token管理     │    │ - 白名单鉴权    │    │ - 数据访问      │
│ - 权限控制      │    │ - 限流防护      │    │ - 操作审计      │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

#### 鉴权层级设计
1. **L1 - 基础API鉴权**: 保护所有数据库相关API
2. **L2 - 白名单管理鉴权**: 保护白名单管理功能
3. **L3 - 操作权限鉴权**: 细粒度操作权限控制

### 2. 基础API鉴权 (L1)

#### 2.1 API Key认证
**适用范围**: 所有数据库相关API (`/api/v1/*`)

**实现方式**:
```http
GET /api/v1/server/info
Authorization: Bearer <API_KEY>
X-API-Version: v1
```

**API Key格式**:
- 长度: 64字符
- 格式: `ca_` + 58位随机字符串 (Base58编码)
- 示例: `ca_8KjH9mN2pQ4rS6tU8vW1xY3zA5bC7dE9fG2hI4jK6lM8nO0pR2sT4uV6wX8yZ`

**安全特性**:
- 支持多个API Key并存
- 可设置过期时间
- 支持IP绑定限制
- 请求频率限制 (默认: 100/分钟)

#### 2.2 JWT Token认证 (可选)
**适用场景**: 需要用户身份信息的场景

**Token结构**:
```json
{
  "header": {
    "alg": "HS256",
    "typ": "JWT"
  },
  "payload": {
    "iss": "convenient-access",
    "sub": "api-access",
    "aud": "minecraft-server",
    "exp": 1640995200,
    "iat": 1640908800,
    "jti": "unique-token-id",
    "scope": ["read", "write"]
  }
}
```

### 3. 白名单管理鉴权 (L2)

#### 3.1 管理员认证系统

**认证流程**:
```
1. 管理员输入用户名/密码
2. 系统验证凭据 (bcrypt + salt)
3. 生成高强度JWT Token
4. 返回Token和权限信息
5. 后续请求携带Token访问
```

**密码安全策略**:
- **哈希算法**: bcrypt (cost=12)
- **盐值**: 每个密码独立的32字节随机盐
- **最小长度**: 12位
- **复杂度要求**: 必须包含大小写字母、数字、特殊字符

#### 3.2 高强度JWT Token

**Token配置**:
```json
{
  "algorithm": "HS512",
  "secret_length": 256,
  "expiry": 3600,
  "refresh_threshold": 300,
  "max_refresh_count": 5
}
```

**Token载荷**:
```json
{
  "iss": "whitelist-manager",
  "sub": "admin-user-id",
  "aud": "whitelist-api",
  "exp": 1640995200,
  "iat": 1640908800,
  "nbf": 1640908800,
  "jti": "unique-jwt-id",
  "user_id": "admin123",
  "username": "admin",
  "role": "super_admin",
  "permissions": ["whitelist:read", "whitelist:write", "whitelist:delete"],
  "session_id": "session-uuid",
  "ip_address": "192.168.1.100",
  "user_agent_hash": "sha256-hash"
}
```

#### 3.3 会话管理

**会话存储**:
```sql
CREATE TABLE admin_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id VARCHAR(36) NOT NULL UNIQUE,
    user_id VARCHAR(32) NOT NULL,
    jwt_token_hash VARCHAR(64) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    user_agent_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_activity TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    is_active BOOLEAN DEFAULT 1,
    
    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id),
    INDEX idx_expires_at (expires_at)
);
```

**会话安全**:
- 单用户最多3个并发会话
- 异地登录自动踢出旧会话
- 30分钟无操作自动过期
- 支持手动注销所有会话

### 4. 防暴力破解机制

#### 4.1 登录保护

**失败次数限制**:
```json
{
  "max_attempts": 5,
  "lockout_duration": 900,
  "progressive_delay": [1, 2, 5, 10, 30],
  "ip_lockout_threshold": 10,
  "global_lockout_threshold": 50
}
```

**实现策略**:
- 用户级别: 5次失败锁定15分钟
- IP级别: 10次失败锁定30分钟  
- 全局级别: 50次失败启用验证码
- 渐进式延迟: 每次失败增加响应延迟

#### 4.2 请求频率限制

**多级限流**:
```yaml
rate_limits:
  global:
    requests_per_minute: 1000
    burst: 100
  
  per_ip:
    requests_per_minute: 60
    burst: 10
  
  per_user:
    requests_per_minute: 120
    burst: 20
  
  auth_endpoints:
    requests_per_minute: 10
    burst: 3
```

#### 4.3 异常检测

**检测指标**:
- 短时间内大量失败登录
- 来自同一IP的异常请求模式
- 非正常时间段的访问
- 异常的User-Agent模式

**响应措施**:
- 自动IP封禁 (可配置时长)
- 邮件/日志告警
- 强制所有用户重新登录
- 临时启用额外验证

### 5. 权限管理系统

#### 5.1 角色定义

```sql
CREATE TABLE admin_roles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    role_name VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    description TEXT,
    permissions TEXT NOT NULL, -- JSON格式权限列表
    is_active BOOLEAN DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 预定义角色
INSERT INTO admin_roles (role_name, display_name, permissions) VALUES
('super_admin', '超级管理员', '["*"]'),
('admin', '管理员', '["whitelist:*", "stats:read"]'),
('operator', '操作员', '["whitelist:read", "whitelist:write"]'),
('viewer', '查看者', '["whitelist:read", "stats:read"]');
```

#### 5.2 用户管理

```sql
CREATE TABLE admin_users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(32) NOT NULL UNIQUE,
    email VARCHAR(128),
    password_hash VARCHAR(128) NOT NULL,
    salt VARCHAR(64) NOT NULL,
    role_id INTEGER NOT NULL,
    is_active BOOLEAN DEFAULT 1,
    last_login TIMESTAMP,
    login_count INTEGER DEFAULT 0,
    failed_attempts INTEGER DEFAULT 0,
    locked_until TIMESTAMP,
    password_changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (role_id) REFERENCES admin_roles(id),
    INDEX idx_username (username),
    INDEX idx_email (email)
);
```

#### 5.3 权限验证

**权限格式**:
```
资源:操作
whitelist:read     # 读取白名单
whitelist:write    # 修改白名单  
whitelist:delete   # 删除白名单
stats:read         # 查看统计
system:config      # 系统配置
*                  # 所有权限
```

**验证流程**:
```java
// 伪代码示例
public boolean hasPermission(String token, String permission) {
    // 1. 验证Token有效性
    Claims claims = validateJWT(token);
    
    // 2. 检查会话状态
    if (!isSessionActive(claims.getSessionId())) {
        return false;
    }
    
    // 3. 获取用户权限
    List<String> permissions = getUserPermissions(claims.getUserId());
    
    // 4. 权限匹配
    return matchPermission(permissions, permission);
}
```

### 6. 安全配置

#### 6.1 加密配置

```yaml
security:
  jwt:
    secret_key: "${JWT_SECRET_KEY}" # 环境变量，256位随机密钥
    algorithm: "HS512"
    expiry_seconds: 3600
    
  password:
    bcrypt_cost: 12
    min_length: 12
    require_complexity: true
    
  api_key:
    length: 64
    prefix: "ca_"
    encoding: "base58"
    
  session:
    max_concurrent: 3
    idle_timeout: 1800
    absolute_timeout: 28800
```

#### 6.2 服务器配置

```yaml
server:
  port: 22222  # 默认端口改为22222，避免热点端口冲突
  
security:
  # HTTPS由服务器强制启用，无需额外配置
  require_ssl: true
```

### 7. 审计日志

#### 7.1 认证日志

```sql
CREATE TABLE auth_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type VARCHAR(32) NOT NULL, -- LOGIN, LOGOUT, TOKEN_REFRESH, etc.
    username VARCHAR(32),
    ip_address VARCHAR(45) NOT NULL,
    user_agent TEXT,
    success BOOLEAN NOT NULL,
    failure_reason VARCHAR(128),
    session_id VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_event_type (event_type),
    INDEX idx_username (username),
    INDEX idx_ip_address (ip_address),
    INDEX idx_created_at (created_at DESC)
);
```

#### 7.2 操作日志

```sql
CREATE TABLE admin_operation_logs (
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_user_id (user_id),
    INDEX idx_operation (operation),
    INDEX idx_resource_type (resource_type),
    INDEX idx_created_at (created_at DESC)
);
```

### 8. 配置文件管理

#### 8.1 配置文件结构

**config.yml** (插件配置文件)
```yaml
# 服务器配置
server:
  port: 22222
  host: "0.0.0.0"

# 安全配置
security:
  # JWT密钥 (插件启动时自动生成)
  jwt_secret: "auto-generated-256-bit-key"
  
  # API密钥 (插件启动时自动生成)
  api_keys:
    - key: "ca_auto_generated_api_key_1"
      name: "Default API Key"
      permissions: ["read"]
      expires_at: "2025-12-31T23:59:59Z"
  
  # 一次性注册token (插件启动时自动生成)
  registration_tokens:
    - token: "reg_auto_generated_token_1"
      expires_at: "2024-01-02T23:59:59Z"  # 24小时有效期
      used: false

# 初始管理员账户 (插件启动时自动生成)
admin:
  initial_user:
    username: "admin"
    password: "auto-generated-secure-password"
    email: "admin@localhost"
    role: "super_admin"

# 数据库配置
database:
  path: "plugins/ConvenientAccess/whitelist.db"
  encryption_key: "auto-generated-db-encryption-key"

# 白名单同步配置
whitelist:
  json_path: "whitelist.json"
  sync_interval: 300  # 5分钟
  backup_enabled: true
  backup_interval: 3600  # 1小时
```

#### 8.2 配置自动生成

**插件启动时的配置生成逻辑**:
```java
// 伪代码示例
public void initializeConfig() {
    ConfigFile config = loadConfig();
    
    // 生成JWT密钥
    if (config.getSecurity().getJwtSecret() == null) {
        String jwtSecret = generateSecureKey(256);
        config.getSecurity().setJwtSecret(jwtSecret);
    }
    
    // 生成API密钥
    if (config.getSecurity().getApiKeys().isEmpty()) {
        String apiKey = generateApiKey();
        config.getSecurity().addApiKey(apiKey, "Default API Key", ["read"]);
    }
    
    // 生成注册token
    String regToken = generateRegistrationToken();
    config.getSecurity().addRegistrationToken(regToken, 24); // 24小时有效
    
    // 生成初始管理员密码
    if (config.getAdmin().getInitialUser().getPassword() == null) {
        String password = generateSecurePassword(16);
        config.getAdmin().getInitialUser().setPassword(password);
        
        // 输出到控制台供管理员查看
        logger.info("=================================");
        logger.info("初始管理员账户信息:");
        logger.info("用户名: admin");
        logger.info("密码: " + password);
        logger.info("注册Token: " + regToken);
        logger.info("请及时修改密码并保存注册Token!");
        logger.info("=================================");
    }
    
    saveConfig(config);
}
```

#### 8.3 配置迁移支持

**旧配置检测和迁移**:
```java
public void migrateOldConfig() {
    // 检测旧版本配置文件
    File oldConfig = new File("plugins/ConvenientAccess/config_old.yml");
    File currentConfig = new File("plugins/ConvenientAccess/config.yml");
    
    if (oldConfig.exists() && !currentConfig.exists()) {
        logger.info("检测到旧版本配置文件，开始迁移...");
        
        // 读取旧配置
        OldConfig old = loadOldConfig(oldConfig);
        
        // 创建新配置
        NewConfig newConfig = new NewConfig();
        
        // 迁移基础设置
        newConfig.getServer().setPort(old.getPort() != 0 ? old.getPort() : 22222);
        newConfig.getServer().setHost(old.getHost() != null ? old.getHost() : "0.0.0.0");
        
        // 迁移API密钥 (如果存在)
        if (old.getApiKey() != null) {
            newConfig.getSecurity().addApiKey(old.getApiKey(), "Migrated API Key", ["read", "write"]);
        }
        
        // 生成新的安全配置
        generateSecurityConfig(newConfig);
        
        // 保存新配置
        saveConfig(newConfig);
        
        // 备份旧配置
        oldConfig.renameTo(new File("plugins/ConvenientAccess/config_old_backup.yml"));
        
        logger.info("配置迁移完成!");
    }
}
```

#### 8.4 配置验证

**启动时配置验证**:
```java
public boolean validateConfig(Config config) {
    List<String> errors = new ArrayList<>();
    
    // 验证端口
    if (config.getServer().getPort() < 1024 || config.getServer().getPort() > 65535) {
        errors.add("端口号必须在1024-65535范围内");
    }
    
    // 验证JWT密钥长度
    if (config.getSecurity().getJwtSecret().length() < 32) {
        errors.add("JWT密钥长度不足，至少需要32字符");
    }
    
    // 验证API密钥格式
    for (ApiKey key : config.getSecurity().getApiKeys()) {
        if (!key.getKey().startsWith("ca_") || key.getKey().length() != 64) {
            errors.add("API密钥格式错误: " + key.getName());
        }
    }
    
    // 验证数据库路径
    if (!isValidPath(config.getDatabase().getPath())) {
        errors.add("数据库路径无效");
    }
    
    if (!errors.isEmpty()) {
        logger.error("配置验证失败:");
        errors.forEach(logger::error);
        return false;
    }
    
    return true;
}
```

#### 8.2 初始化脚本

```sql
-- 创建默认超级管理员 (从配置文件读取)
INSERT INTO admin_users (username, email, password_hash, salt, role_id) 
VALUES ('admin', 'admin@localhost', 
        '$2b$12$encrypted_password_hash', 
        'random_salt_32_bytes', 1);

-- 创建默认API Key (从配置文件读取)
INSERT INTO api_keys (key_hash, name, permissions, expires_at)
VALUES ('sha256_hash_of_key', 'Default API Key', '["read"]', 
        datetime('now', '+1 year'));

-- 插入初始注册令牌 (从配置文件读取)
INSERT INTO registration_tokens (token, token_hash, expires_at)
VALUES ('reg_token_from_config', 'sha256_hash_of_token',
        datetime('now', '+1 day'));
```

## 安全考虑

### 1. 访问控制
详见上方"鉴权系统设计"章节的完整实现。

### 2. 数据安全
- **输入验证**: 严格验证所有输入参数
- **SQL注入防护**: 使用预编译语句
- **XSS防护**: 输出数据转义处理

### 3. 操作审计
- **操作日志**: 记录所有敏感操作
- **IP追踪**: 记录操作来源IP
- **时间戳**: 精确记录操作时间

## 部署和运维

### 1. 配置管理
- **环境配置**: 开发、测试、生产环境隔离
- **动态配置**: 支持运行时配置更新
- **配置验证**: 启动时验证配置有效性

### 2. 数据备份
- **定期备份**: 自动定期备份数据库
- **增量备份**: 减少备份时间和存储空间
- **备份验证**: 定期验证备份完整性

### 3. 监控告警
- **健康检查**: 定期检查系统健康状态
- **性能监控**: 监控关键性能指标
- **异常告警**: 及时通知系统异常

### 4. 版本管理
- **数据库迁移**: 支持数据库结构升级
- **向后兼容**: 保证API向后兼容性
- **灰度发布**: 支持渐进式功能发布

## 开发计划

### 第一阶段: 核心功能 (2-3周)
1. 数据库设计和初始化
2. 基础CRUD操作实现
3. 简单同步机制
4. 基础API接口
5. **基础API鉴权系统实现**

### 第二阶段: 高级功能 (2-3周)
1. 复杂查询和筛选
2. 批量操作支持
3. 完整同步机制
4. 错误处理和重试
5. **白名单管理鉴权系统实现**

### 第三阶段: 优化和监控 (1-2周)
1. 性能优化
2. 缓存机制
3. 监控和日志
4. 安全加固
5. **防暴力破解机制实现**

### 第四阶段: 测试和部署 (1周)
1. 单元测试
2. 集成测试
3. 性能测试
4. 部署文档
5. **安全测试和渗透测试**

## 总结

本白名单管理系统采用SQLite + 任务同步的架构，能够有效解决大规模白名单数据的管理问题。通过合理的数据库设计、异步同步机制和性能优化策略，系统能够提供高性能、高可用的白名单管理服务，同时保证与现有WhitelistPlus插件的完全兼容。

**安全特性**:
- 双层鉴权架构保护系统安全
- 高强度加密防止暴力破解
- 完整的审计日志追踪所有操作
- 多级权限管理支持精细化控制
- 全面的安全监控和异常检测

该系统不仅满足当前的白名单管理需求，更为未来的功能扩展和安全升级提供了坚实的基础架构。