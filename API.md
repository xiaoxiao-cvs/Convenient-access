# ConvenientAccess API 文档

## 概述

ConvenientAccess 提供了一套简洁的 RESTful API，用于管理 Minecraft 1.20.1 Arclight 服务器的白名单系统。所有 API 端点都返回 JSON 格式的数据，专注于核心功能和服务器监控。

## 基础信息

- **基础URL**: `http://your-server:22222/api/v1`
- **内容类型**: `application/json`
- **字符编码**: `UTF-8`
- **认证方式**: 简化认证（管理员直接操作）
- **频率限制**: 无特殊限制（适合管理员使用）

## 🚀 所有可用端点

### 白名单管理 API
| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/whitelist` | GET | 获取白名单列表（支持分页、搜索、排序） | 无 |
| `/api/v1/whitelist` | POST | 添加白名单条目 | 无 |
| `/api/v1/whitelist/{uuid}` | DELETE | 删除指定UUID的白名单条目 | 无 |
| `/api/v1/whitelist/batch` | POST | 批量操作白名单条目 | 无 |
| `/api/v1/whitelist/stats` | GET | 获取白名单统计信息 | 无 |
| `/api/v1/whitelist/sync` | POST | 手动触发同步 | 无 |
| `/api/v1/whitelist/sync/status` | GET | 获取同步状态 | 无 |

### 用户注册 API
| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/register` | POST | 用户注册（使用注册令牌） | 无 |
| `/api/v1/admin/generate-token` | POST | 生成注册令牌 | 管理员密码 |

### 服务器监控 API
| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/server/info` | GET | 获取服务器详细信息 | 无 |
| `/api/v1/server/status` | GET | 获取服务器状态信息 | 无 |
| `/api/v1/server/performance` | GET | 获取服务器性能数据 | 无 |
| `/api/v1/players/online` | GET | 获取在线玩家数量 | 无 |
| `/api/v1/players/list` | GET | 获取详细玩家列表 | 无 |
| `/api/v1/worlds/list` | GET | 获取世界列表 | 无 |
| `/api/v1/system/resources` | GET | 获取系统资源信息 | 无 |
| `/api/v1/health` | GET | 健康检查端点 | 无 |

## 简化认证机制

### 管理员操作
对于白名单管理等核心功能，系统采用简化认证：
- 管理员直接通过Web界面操作
- 无需复杂的登录流程
- 适合服务器管理员使用场景

### 注册令牌
用于用户自助注册白名单：
```http
# 生成令牌时需要管理员密码验证
X-Admin-Password: your-admin-password
```

## 响应格式

### 成功响应

```json
{
  "success": true,
  "data": {
    // 具体数据内容
  },
  "timestamp": 1640995200000
}
```

### 错误响应

```json
{
  "success": false,
  "error": {
    "code": 404,
    "message": "Not Found",
    "details": "API路径不存在"
  },
  "timestamp": 1640995200000
}
```

## 📋 API 端点详细说明

### 白名单管理 API

#### `GET /api/v1/whitelist`

获取白名单列表，支持分页、搜索和排序。

**请求参数：**
- `page` (可选): 页码，默认为1
- `size` (可选): 每页大小，默认为20
- `search` (可选): 搜索关键词
- `sort` (可选): 排序字段 (name, uuid, created_at)
- `order` (可选): 排序方向 (asc, desc)

**响应示例：**
```json
{
  "success": true,
  "data": {
    "entries": [
      {
        "uuid": "550e8400-e29b-41d4-a716-446655440000",
        "name": "Player1",
        "created_at": "2024-01-01T00:00:00Z",
        "updated_at": "2024-01-01T00:00:00Z",
        "source": "manual",
        "notes": "VIP玩家"
      }
    ],
    "pagination": {
      "page": 1,
      "size": 20,
      "total": 100,
      "total_pages": 5
    }
  },
  "timestamp": 1640995200000
}
```

#### `POST /api/v1/whitelist`

添加新的白名单条目。

**请求体：**
```json
{
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Player1",
  "notes": "VIP玩家"
}
```

**响应示例：**
```json
{
  "success": true,
  "data": {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Player1",
    "created_at": "2024-01-01T00:00:00Z",
    "updated_at": "2024-01-01T00:00:00Z",
    "source": "api",
    "notes": "VIP玩家"
  },
  "timestamp": 1640995200000
}
```

#### `DELETE /api/v1/whitelist/{uuid}`

删除指定UUID的白名单条目。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "message": "白名单条目已删除",
    "uuid": "550e8400-e29b-41d4-a716-446655440000"
  },
  "timestamp": 1640995200000
}
```

#### `POST /api/v1/whitelist/batch`

批量添加白名单条目。

**请求体：**
```json
{
  "entries": [
    {
      "uuid": "550e8400-e29b-41d4-a716-446655440000",
      "name": "Player1",
      "notes": "批量添加"
    },
    {
      "uuid": "550e8400-e29b-41d4-a716-446655440001",
      "name": "Player2",
      "notes": "批量添加"
    }
  ]
}
```

#### `GET /api/v1/whitelist/stats`

获取白名单统计信息。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "total_entries": 150,
    "recent_additions": 5,
    "recent_deletions": 2,
    "sync_status": "active",
    "last_sync": "2024-01-01T00:00:00Z"
  },
  "timestamp": 1640995200000
}
```

### 令牌管理 API

#### `POST /api/v1/admin/generate-token`

生成注册令牌。

**请求头：**
```http
X-Admin-Password: your-admin-password
```

**请求体：**
```json
{
  "expiryHours": 24
}
```

**响应示例：**
```json
{
  "success": true,
  "data": {
    "token": "reg_xxxxxxxxxxxxxxxxxxxxxxxxx",
    "expiryHours": 24
  },
  "message": "注册令牌生成成功",
  "timestamp": 1640995200000
}
```

### 用户注册 API

#### `POST /api/v1/register`

用户注册（使用注册令牌）。

**请求体：**
```json
{
  "token": "reg_xxxxxxxxxxxxxxxxxxxxxxxxx",
  "playerName": "PlayerName",
  "playerUuid": "550e8400-e29b-41d4-a716-446655440000"
}
```

**响应示例：**
```json
{
  "success": true,
  "data": {
    "playerName": "PlayerName",
    "playerUuid": "550e8400-e29b-41d4-a716-446655440000",
    "message": "注册成功，已添加到白名单"
  },
  "message": "注册成功",
  "timestamp": 1640995200000
}
```

### 服务器监控 API

#### `GET /api/v1/server/status`

获取服务器状态信息。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "online": true,
    "spark_available": true,
    "plugin_version": "0.1.0",
    "timestamp": 1640995200000
  },
  "timestamp": 1640995200000
}
```

#### `GET /api/v1/server/performance`

获取服务器性能数据。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "tps": {
      "values": {
        "last_1m": 20.0,
        "last_5m": 19.8,
        "last_15m": 19.5
      }
    },
    "mspt": {
      "values": {
        "last_1m": 15.2,
        "last_5m": 16.1,
        "last_15m": 17.3
      }
    },
    "memory": {
      "used": 2048,
      "max": 4096,
      "free": 2048
    },
    "cpu": {
      "process": 25.5,
      "system": 45.2
    },
    "timestamp": 1640995200000
  },
  "timestamp": 1640995200000
}
```

#### `GET /api/v1/health`

简单的健康检查端点。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "status": "healthy",
    "uptime": 1640995200000,
    "version": "0.1.0",
    "components": {
      "cache": "healthy",
      "data_collector": "healthy"
    },
    "timestamp": 1640995200000
  }
}
```
## 错误代码说明

| 错误代码 | 说明 | 解决方案 |
|----------|------|----------|
| 400 | 请求参数错误 | 检查请求参数格式和必填字段 |
| 401 | 认证失败 | 检查API Key或JWT Token是否正确 |
| 403 | 权限不足 | 确认用户具有相应操作权限 |
| 404 | 资源不存在 | 检查请求的UUID或路径是否正确 |
| 409 | 资源冲突 | 白名单条目已存在或操作冲突 |
| 429 | 请求频率超限 | 降低请求频率，等待限制解除 |
| 500 | 服务器内部错误 | 联系管理员检查服务器状态 |

## 安全最佳实践

### 1. API Key 管理
- 定期轮换API Key
- 不要在客户端代码中硬编码API Key
- 使用环境变量存储敏感信息
- 监控API Key使用情况

### 2. JWT Token 安全
- Token具有过期时间，需要定期刷新
- 在安全的地方存储Token
- 登出时及时清理Token
- 避免在URL中传递Token

### 3. 网络安全
- 使用HTTPS加密传输
- 配置适当的CORS策略
- 实施IP白名单（如需要）
- 监控异常访问模式

## 使用示例

### 白名单管理示例

```bash
# 1. 获取白名单列表
curl -X GET http://localhost:22222/api/v1/whitelist

# 2. 添加白名单条目
curl -X POST http://localhost:22222/api/v1/whitelist \
  -H "Content-Type: application/json" \
  -d '{
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "name": "NewPlayer",
    "notes": "新玩家"
  }'

# 3. 生成注册令牌（需要管理员密码）
curl -X POST http://localhost:22222/api/v1/admin/generate-token \
  -H "Content-Type: application/json" \
  -H "X-Admin-Password: your-admin-password" \
  -d '{
    "expiryHours": 24
  }'
```

### 批量操作示例

```bash
# 批量添加白名单
curl -X POST http://localhost:22222/api/v1/whitelist/batch \
  -H "Content-Type: application/json" \
  -d '{
    "entries": [
      {
        "uuid": "550e8400-e29b-41d4-a716-446655440000",
        "name": "Player1",
        "notes": "VIP玩家"
      },
      {
        "uuid": "550e8400-e29b-41d4-a716-446655440001", 
        "name": "Player2",
        "notes": "普通玩家"
      }
    ]
  }'
```

### 系统监控示例

```bash
# 获取服务器状态
curl -X GET http://localhost:22222/api/v1/server/status

# 获取服务器性能数据
curl -X GET http://localhost:22222/api/v1/server/performance

# 健康检查（无需认证）
curl -X GET http://localhost:22222/api/v1/health
```

## 版本信息

- **当前版本**: v0.1.0
- **API版本**: v1
- **最后更新**: 2024-01-01
- **兼容性**: Minecraft 1.20.1, Arclight

## 技术支持

如果您在使用API时遇到问题，请：

1. 检查本文档中的错误代码说明
2. 验证请求格式和认证信息
3. 查看服务器日志获取详细错误信息
4. 联系技术支持团队

---

*本文档描述了ConvenientAccess白名单管理系统的简化API接口。系统专注于核心的白名单管理功能和服务器监控能力，适合管理员直接操作的场景。*
| 401 | 未授权访问 |
| 403 | 访问被拒绝 |
| 404 | API端点不存在 |
| 405 | 请求方法不支持 |
| 429 | 请求频率超限 |
| 500 | 服务器内部错误 |

## 请求频率限制

默认情况下，每个IP地址每分钟最多可以发送60个请求。超过限制将返回429错误。

## CORS 支持

API 支持跨域请求，默认允许所有来源。可以在配置文件中自定义允许的来源。

## 缓存机制

为了提高性能，API 使用了智能缓存系统：

- 服务器信息：缓存5分钟
- 性能数据：缓存5秒
- 玩家数据：缓存30秒
- 世界数据：缓存1分钟

## Spark 集成

当服务器安装了 Spark 插件时，API 会自动使用 Spark 提供的高精度性能数据：

- 更准确的 TPS 和 MSPT 测量
- 详细的 CPU 使用率统计
- 系统级性能指标

如果 Spark 不可用，API 会自动降级使用内置的性能监控功能。

## 示例代码

### JavaScript (Fetch API)

```javascript
// 获取服务器状态
fetch('http://your-server:22222/api/v1/server/status')
  .then(response => response.json())
  .then(data => {
    if (data.success) {
      console.log('服务器在线:', data.data.online);
    }
  });

// 获取性能数据
fetch('http://your-server:22222/api/v1/server/performance')
  .then(response => response.json())
  .then(data => {
    if (data.success) {
      const tps = data.data.tps.values.last_1m;
      console.log('当前TPS:', tps);
    }
  });
```

### Python (requests)

```python
import requests

# 获取玩家列表
response = requests.get('http://your-server:22222/api/v1/players/list')
if response.status_code == 200:
    data = response.json()
    if data['success']:
        players = data['data']['players']
        print(f'在线玩家数: {len(players)}')
```

### cURL

```bash
# 获取服务器状态
curl -X GET "http://your-server:22222/api/v1/server/status" \
     -H "Accept: application/json"

# 获取世界信息
curl -X GET "http://your-server:22222/api/v1/worlds/list" \
     -H "Accept: application/json"

# 带认证的请求
curl -X GET "http://your-server:22222/api/v1/server/performance" \
     -H "Authorization: Bearer YOUR_API_KEY" \
     -H "Accept: application/json"
```

## 更新日志

### v0.1.0
- 初始版本发布
- 支持基本的服务器信息获取
- 集成 Spark 性能监控
- 添加详细的维度信息
- 实现完整的性能数据收集