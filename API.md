# ConvenientAccess API 文档

> 🎉 **v0.5.0 重大更新**：基于 WhitelistPlus 设计理念重构！现在添加白名单**只需玩家名**，UUID会在首次登录时自动补充！

## 🌟 新特性亮点

- ✨ **简化API**：添加白名单只需 `name` 和 `source` 两个参数
- 🔄 **智能UUID补充**：玩家登录时自动补充UUID，无需手动获取
- 🎮 **完美兼容**：支持离线和正版服务器，适应各种环境
- 📊 **增强统计**：新增UUID待补充状态、来源分解等详细统计
- 🔧 **批量操作**：支持批量添加/删除，提高管理效率
- 🔐 **安全认证**：可配置的API认证系统，支持自动生成的API令牌

## 概述

ConvenientAccess 提供了一套简洁的 RESTful API，用于管理 Minecraft 1.20.1 Arclight 服务器的白名单系统。基于 WhitelistPlus 设计理念，极大简化了白名单管理流程。所有 API 端点都返回 JSON 格式的数据，专注于核心功能和服务器监控。

## 快速导航

- [基础信息](#基础信息) - API基础配置和认证说明
- [认证系统](#-认证系统) - API认证方式和安全配置
- [白名单管理API](#白名单管理-api) - 白名单增删改查操作
- [用户注册API](#用户注册-api) - 用户自助注册功能
- [玩家数据查询API](#玩家数据查询-api) - 获取玩家详细信息 **[新增]**
- [服务器监控API](#服务器监控-api) - 服务器状态和性能监控
- [UUID自动补充机制](#-uuid自动补充机制) - 简化白名单管理流程
- [响应格式](#响应格式) - 统一的响应格式说明

## 基础信息

- **基础URL**: `http://your-server:22222/api/v1`
- **内容类型**: `application/json`
- **字符编码**: `UTF-8`
- **认证方式**: API Token 或 管理员密码认证
- **频率限制**: 无特殊限制（适合管理员使用）

## 🔐 认证系统

### 认证配置

插件支持可配置的认证系统，默认启用认证功能：

```yaml
# config.yml
auth:
  enabled: true  # 是否启用认证（默认：true）
api-token: "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"  # 64位API令牌
token-prefix: "sk-"  # 令牌前缀
admin-password: "xxxxxxxxxxxx"  # 12位管理员密码
```

### 认证方式

#### 1. API Token 认证（推荐）

使用 `X-API-Key` 头或 `Authorization` 头：

```bash
# 方式1：使用 X-API-Key 头
curl -H "X-API-Key: sk-your-api-token-here" \
     -X GET http://your-server:22222/api/v1/whitelist

# 方式2：使用 Authorization Bearer
curl -H "Authorization: Bearer sk-your-api-token-here" \
     -X GET http://your-server:22222/api/v1/whitelist
```

#### 2. 管理员密码认证

使用 `X-Admin-Password` 头（主要用于管理员端点）：

```bash
curl -H "X-Admin-Password: your-admin-password" \
     -X POST http://your-server:22222/api/v1/admin/generate-token
```

### 公开端点

以下端点无需 API Token 认证（如果认证被禁用，所有端点都无需认证）：
- `/api/v1/admin/login` - 管理员登录
- `/api/v1/admin/register` - 管理员注册（需要有效的注册令牌）

**说明：**
- "公开端点"指的是不需要 API Token 或 JWT 认证的端点
- `/api/v1/admin/login` 使用用户名和密码登录，返回 JWT token
- `/api/v1/admin/register` 使用注册令牌进行管理员注册

### 自动生成凭证

插件首次启动时会自动生成：
- **管理员密码**：12位随机字符串
- **API令牌**：64位 sk- 开头的随机字符串

生成的凭证会自动保存到配置文件中，并在控制台输出供管理员记录。

### 安全建议

⚠️ **重要安全提示：**
- 请妥善保管API令牌和管理员密码
- 定期更换API令牌，避免长期使用同一令牌
- 在生产环境中，建议启用认证功能
- 如果不需要认证，可以在配置文件中设置 `auth.enabled: false`
- 确保服务器防火墙正确配置，避免未授权访问

### 禁用认证

如果您不需要API认证（如内网环境），可以在 `config.yml` 中禁用：

```yaml
auth:
  enabled: false  # 禁用认证
```

**注意：** 禁用认证后，所有API端点都可以无限制访问，请谨慎使用。

## 🚀 所有可用端点

### 白名单管理 API
| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/whitelist` | GET | 获取白名单列表（支持分页、搜索、排序） | API Token |
| `/api/v1/whitelist` | POST | 添加白名单条目 | API Token |
| `/api/v1/whitelist/{uuid}` | DELETE | 删除指定UUID的白名单条目 | API Token |
| `/api/v1/whitelist/batch` | POST | 批量操作白名单条目 | API Token |
| `/api/v1/whitelist/regcode` | POST | 为指定玩家名签发一次性注册码（仅发码，不加白） | API Token |
| `/api/v1/whitelist/stats` | GET | 获取白名单统计信息 | API Token |
| `/api/v1/whitelist/sync` | POST | 手动触发同步 | API Token |
| `/api/v1/whitelist/sync/status` | GET | 获取同步状态 | API Token |

### 管理员认证 API
| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/admin/login` | POST | 管理员登录 | 无（公开） |
| `/api/v1/admin/register` | POST | 管理员注册 | 注册令牌 |
| `/api/v1/admin/me` | GET | 获取当前管理员信息 | JWT Token |
| `/api/v1/admin/generate-token` | POST | 生成注册令牌 | JWT Token |

**说明：**
- `/api/v1/admin/login` 使用用户名和密码登录，返回 JWT token
- `/api/v1/admin/register` 使用注册令牌进行管理员注册
- `/api/v1/admin/me` 和 `/api/v1/admin/generate-token` 需要在请求头中提供 `Authorization: Bearer <jwt-token>`

### 玩家数据查询 API
| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/player` | GET | 获取玩家详细数据（使用查询参数 `?name=玩家名`） | API Token |

### 服务器监控 API
| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/server/info` | GET | 获取服务器详细信息 | API Token |
| `/api/v1/server/status` | GET | 获取服务器状态信息 | API Token |
| `/api/v1/server/performance` | GET | 获取服务器性能数据 | API Token |
| `/api/v1/players/online` | GET | 获取在线玩家数量 | API Token |
| `/api/v1/players/list` | GET | 获取详细玩家列表 | 无 |
| `/api/v1/worlds/list` | GET | 获取世界列表 | 无 |
| `/api/v1/system/resources` | GET | 获取系统资源信息 | 无 |
| `/api/v1/health` | GET | 健康检查端点 | 无 |

## 🎯 UUID自动补充机制

### 设计理念

基于 WhitelistPlus 插件的设计理念，我们的白名单系统采用了**"玩家名优先，UUID后补"**的策略：

1. **添加阶段**：管理员只需提供玩家名即可添加白名单
2. **登录阶段**：玩家首次登录时系统自动补充UUID  
3. **同步阶段**：创建同步任务更新JSON文件，保持数据一致性

### 工作流程

```mermaid
sequenceDiagram
    participant Admin as 管理员
    participant API as API接口
    participant DB as 数据库
    participant Player as 玩家
    participant Listener as 登录监听器
    participant Sync as 同步系统

    Admin->>API: POST /api/v1/whitelist {"name": "PlayerName"}
    API->>DB: INSERT (name, uuid=NULL)
    API->>Admin: 返回成功响应

    Player->>Listener: 玩家登录服务器
    Listener->>DB: 查询玩家名对应记录
    Listener->>DB: UPDATE uuid WHERE name=PlayerName
    Listener->>Sync: 创建UUID更新任务
    Listener->>Player: 发送欢迎消息
```

### 数据库状态变化

**添加时：**
```sql
id | name       | uuid | source | is_active | uuid_pending
1  | PlayerName | NULL | API    | 1         | true
```

**首次登录后：**
```sql  
id | name       | uuid                                 | source | is_active | uuid_pending
1  | PlayerName | 550e8400-e29b-41d4-a716-446655440000 | API    | 1         | false
```

### 优势

- ✅ **简化管理**：无需获取玩家UUID，直接使用玩家名
- ✅ **兼容性强**：支持离线和正版服务器
- ✅ **自动化**：UUID自动补充，无需人工干预
- ✅ **数据完整性**：保证最终数据的完整性
- ✅ **实用性**：符合大多数服务器的实际使用场景

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
        "id": 1,
        "uuid": "550e8400-e29b-41d4-a716-446655440000",
        "name": "Player1",
        "added_by_name": "AdminUser",
        "added_by_uuid": "admin-uuid-here",
        "added_at": "2024-01-01T00:00:00",
        "source": "API",
        "is_active": true,
        "created_at": "2024-01-01T00:00:00",
        "updated_at": "2024-01-01T00:00:00"
      },
      {
        "id": 2,
        "uuid": null,
        "name": "Player2",
        "added_by_name": "AdminUser", 
        "added_by_uuid": "admin-uuid-here",
        "added_at": "2024-01-01T01:00:00",
        "source": "API",
        "is_active": true,
        "created_at": "2024-01-01T01:00:00",
        "updated_at": "2024-01-01T01:00:00",
        "uuid_pending": true
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

> **💡 说明**：当 `uuid` 字段为 `null` 且 `uuid_pending` 为 `true` 时，表示该玩家的UUID将在首次登录时自动补充。

#### `POST /api/v1/whitelist`

添加新的白名单条目（基于WhitelistPlus设计理念）。

> **🎯 新特性**：现在只需要玩家名即可添加白名单，UUID会在玩家首次登录时自动补充！

**请求体：**
```json
{
  "name": "PlayerName",
  "source": "API",
  "added_by_name": "AdminName",
  "added_by_uuid": "admin-uuid-here",
  "added_at": "2024-01-01T12:00:00"
}
```

**参数说明：**
- `name` (必需): 玩家名称
- `source` (必需): 添加来源，可选值：`PLAYER`、`ADMIN`、`SYSTEM`、`API`
- `added_by_name` (可选): 添加者名称，默认为 "API"
- `added_by_uuid` (可选): 添加者UUID，默认为 "00000000-0000-0000-0000-000000000000"
- `added_at` (可选): 添加时间，默认为当前时间（ISO格式）

**最简请求示例：**
```json
{
  "name": "PlayerName",
  "source": "API"
}
```

**响应示例：**
```json
{
  "success": true,
  "message": "玩家添加成功",
  "data": {
    "name": "PlayerName",
    "added": true,
    "uuid_pending": true,
    "message": "玩家已添加到白名单，UUID将在首次登录时自动补充"
  },
  "timestamp": 1640995200000
}
```

**UUID补充机制：**
当玩家首次登录服务器时，系统会自动：
1. 检测到玩家UUID为空
2. 自动更新数据库中的UUID
3. 创建同步任务更新JSON文件
4. 向玩家发送欢迎消息

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

批量操作白名单条目（支持批量添加和删除）。

**批量添加请求体：**
```json
{
  "operation": "add",
  "source": "API",
  "added_by_name": "AdminName",
  "added_by_uuid": "admin-uuid-here",
  "added_at": "2024-01-01T12:00:00",
  "players": [
    {
      "name": "Player1"
    },
    {
      "name": "Player2"
    }
  ]
}
```

**批量删除请求体：**
```json
{
  "operation": "remove",
  "added_by_name": "AdminName",
  "added_by_uuid": "admin-uuid-here",
  "players": [
    {
      "uuid": "550e8400-e29b-41d4-a716-446655440000"
    },
    {
      "uuid": "550e8400-e29b-41d4-a716-446655440001"
    }
  ]
}
```

**响应示例：**
```json
{
  "success": true,
  "message": "批量操作完成",
  "data": {
    "operation": "add",
    "total_requested": 2,
    "success_count": 2,
    "failed_count": 0,
    "details": [
      {
        "name": "Player1",
        "success": true
      },
      {
        "name": "Player2", 
        "success": true
      }
    ]
  },
  "timestamp": 1640995200000
}
```

#### `POST /api/v1/whitelist/regcode`

为指定玩家名签发一次性注册码，仅发码、不改动白名单。与 `POST /api/v1/whitelist`（加白即发码）解耦：当玩家已在白名单（如问卷审核时已加白）时再调加白会撞 409 拿不到码，此端点直接重签注册码，与白名单状态无关。内部会作废该玩家名名下旧的未用码，保证同名同时只有一个有效码；仅返回明文码一次，库内只存其 SHA-256 哈希。

主要供问卷后端在玩家凭 hash 自助领码时以服务端身份调用。需玩家认证（`player-auth`）启用，否则返回 409。

**请求体：**
```json
{
  "name": "PlayerName"
}
```

**响应示例：**
```json
{
  "success": true,
  "message": "注册码已生成",
  "data": {
    "name": "PlayerName",
    "registration_code": "ABCD-2345",
    "code_expires_minutes": 1440
  },
  "timestamp": 1640995200000
}
```

**说明：**
- 注册码绑定该玩家名、一次性、`code_expires_minutes` 分钟后过期，玩家游戏内 `/register <密码> <确认> <注册码>` 使用。
- 玩家认证未启用时返回 409 `玩家认证未启用, 无法签发注册码`。

#### `GET /api/v1/whitelist/stats`

获取白名单统计信息。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "total_entries": 150,
    "active_entries": 148,
    "uuid_pending_entries": 12,
    "recent_additions": 5,
    "recent_deletions": 2,
    "recent_uuid_updates": 3,
    "source_breakdown": {
      "API": 80,
      "ADMIN": 45,
      "SYSTEM": 20,
      "PLAYER": 5
    },
    "sync_status": "active",
    "last_sync": "2024-01-01T00:00:00Z",
    "cache_status": {
      "loaded": true,
      "size": 150,
      "last_refresh": "2024-01-01T00:00:00Z"
    }
  },
  "timestamp": 1640995200000
}
```

#### `POST /api/v1/whitelist/sync`

手动触发白名单同步。

**响应示例：**
```json
{
  "success": true,
  "message": "同步任务已创建",
  "data": {
    "task_id": 12345,
    "task_type": "FULL_SYNC",
    "status": "PENDING",
    "created_at": "2024-01-01T00:00:00Z"
  },
  "timestamp": 1640995200000
}
```

#### `GET /api/v1/whitelist/sync/status`

获取同步状态信息。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "sync_enabled": true,
    "last_sync_time": "2024-01-01T00:00:00Z",
    "sync_status": "completed",
    "pending_tasks": 2,
    "recent_tasks": [
      {
        "id": 12345,
        "type": "FULL_SYNC",
        "status": "COMPLETED",
        "created_at": "2024-01-01T00:00:00Z",
        "completed_at": "2024-01-01T00:00:05Z"
      },
      {
        "id": 12346,
        "type": "UPDATE_UUID",
        "status": "PROCESSING",
        "created_at": "2024-01-01T00:05:00Z"
      }
    ]
  },
  "timestamp": 1640995200000
}
```

### 令牌管理 API

#### `POST /api/v1/admin/generate-token`

生成注册令牌(需要管理员JWT认证)。

**请求头：**
```http
Authorization: Bearer <jwt-token>
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
    "expiryHours": 24,
    "message": "令牌生成成功"
  },
  "message": "令牌生成成功",
  "timestamp": 1640995200000
}
```

### 用户注册 API

#### `POST /api/v1/register`

用户自助注册白名单。

**用途说明**：
- 这是一个让用户**自助添加到白名单**的功能
- 用户需要从管理员处获取注册令牌（鉴权码）
- 用户使用 **玩家名称（账号）** + **UUID（密码）** + **注册令牌（鉴权码）** 来注册
- 注册成功后,用户的玩家名称和UUID将被添加到服务器白名单

**请求体：**
```json
{
  "token": "reg_xxxxxxxxxxxxxxxxxxxxxxxxx",
  "playerName": "PlayerName",
  "playerUuid": "550e8400-e29b-41d4-a716-446655440000"
}
```

**参数说明：**
- `token` (必需): 注册令牌，由管理员生成的鉴权码
- `playerName` (必需): 玩家的 Minecraft 游戏名称（账号）
- `playerUuid` (必需): 玩家的 Minecraft UUID（密码）

**响应示例：**
```json
{
  "success": true,
  "message": "注册成功",
  "data": {
    "playerName": "PlayerName",
    "playerUuid": "550e8400-e29b-41d4-a716-446655440000",
    "message": "注册成功，已添加到白名单"
  },
  "timestamp": 1640995200000
}
```

**响应示例（提供UUID）：**
```json
{
  "success": true,
  "message": "注册成功",
  "data": {
    "playerName": "PlayerName",
    "playerUuid": "550e8400-e29b-41d4-a716-446655440000",
    "uuid_pending": false,
    "message": "注册成功，已添加到白名单"
  },
  "timestamp": 1640995200000
}
```

### 玩家数据查询 API

#### `GET /api/v1/player`

获取指定玩家的详细数据，包括基本信息、位置、生命值、背包、装备等完整信息。

**查询参数：**
- `name` (string, 必需): 玩家名称
- `includeOffline` (boolean, 可选): 是否查询离线玩家，默认为 `false`。设置为 `true` 可以查询离线玩家的基本信息

**请求示例：**
```bash
# 查询在线玩家
curl -H "X-API-Key: sk-your-api-token-here" \
     -X GET "http://your-server:22222/api/v1/player?name=PlayerName"

# 查询离线玩家
curl -H "X-API-Key: sk-your-api-token-here" \
     -X GET "http://your-server:22222/api/v1/player?name=PlayerName&includeOffline=true"
```

**响应示例（在线玩家）：**
```json
{
  "success": true,
  "data": {
    "playerName": "PlayerName",
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "isOnline": true,
    "hasPlayedBefore": true,
    "firstPlayed": 1640995200000,
    "lastPlayed": 1640995200000,
    "lastLogin": 1640995200000,
    "gameMode": "SURVIVAL",
    "location": {
      "world": "world",
      "x": 123.45,
      "y": 64.0,
      "z": -67.89,
      "yaw": 90.0,
      "pitch": 0.0,
      "dimension": "NORMAL"
    },
    "bedSpawnLocation": {
      "world": "world",
      "x": 100.0,
      "y": 65.0,
      "z": -50.0
    },
    "health": 20.0,
    "maxHealth": 20.0,
    "foodLevel": 20,
    "saturation": 5.0,
    "exhaustion": 0.0,
    "level": 30,
    "exp": 0.5,
    "totalExperience": 825,
    "remainingAir": 300,
    "maximumAir": 300,
    "fireTicks": 0,
    "isFlying": false,
    "allowFlight": false,
    "isInvulnerable": false,
    "isSneaking": false,
    "isSprinting": false,
    "isSwimming": false,
    "isGliding": false,
    "isBlocking": false,
    "walkSpeed": 0.2,
    "flySpeed": 0.1,
    "potionEffects": [
      {
        "type": "SPEED",
        "amplifier": 1,
        "duration": 600,
        "isAmbient": false,
        "hasParticles": true,
        "hasIcon": true
      }
    ],
    "inventory": {
      "mainInventory": [
        {
          "type": "DIAMOND_SWORD",
          "amount": 1,
          "damage": 0,
          "maxDurability": 1561,
          "slot": "0",
          "displayName": "§6传奇之剑",
          "enchantments": {
            "sharpness": 5,
            "unbreaking": 3
          }
        }
      ],
      "armor": [
        {
          "type": "DIAMOND_HELMET",
          "amount": 1,
          "damage": 10,
          "maxDurability": 363,
          "slot": "head",
          "enchantments": {
            "protection": 4
          }
        }
      ],
      "mainHand": {
        "type": "DIAMOND_PICKAXE",
        "amount": 1,
        "damage": 50,
        "maxDurability": 1561,
        "enchantments": {
          "efficiency": 5,
          "fortune": 3
        }
      },
      "offHand": {
        "type": "TORCH",
        "amount": 64
      }
    },
    "enderChest": [
      {
        "type": "DIAMOND",
        "amount": 64,
        "slot": "0"
      }
    ],
    "statistics": {
      "playTime": 360000,
      "deaths": 5,
      "mobKills": 1234,
      "playerKills": 10,
      "timeSinceRest": 72000,
      "damageTaken": 150.5,
      "damageDealt": 5234.5
    }
  },
  "message": "成功获取玩家数据（在线）",
  "timestamp": "2025-10-02T12:00:00"
}
```

**响应示例（离线玩家）：**
```json
{
  "success": true,
  "data": {
    "playerName": "PlayerName",
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "isOnline": false,
    "hasPlayedBefore": true,
    "firstPlayed": 1640995200000,
    "lastPlayed": 1640995200000,
    "lastLogin": 1640995200000,
    "gameMode": "UNKNOWN",
    "bedSpawnLocation": {
      "world": "world",
      "x": 100.0,
      "y": 65.0,
      "z": -50.0
    }
  },
  "message": "成功获取玩家数据（离线）",
  "timestamp": "2025-10-02T12:00:00"
}
```

**错误响应示例：**
```json
{
  "success": false,
  "error": "玩家不在线 (提示: 使用 ?includeOffline=true 查询离线玩家)",
  "code": 404,
  "timestamp": "2025-10-02T12:00:00"
}
```

```json
{
  "success": false,
  "error": "玩家不存在或从未登录过服务器",
  "code": 404,
  "timestamp": "2025-10-02T12:00:00"
}
```

```json
{
  "success": false,
  "error": "服务器繁忙,请稍后重试(TPS过低)",
  "code": 504,
  "timestamp": "2025-10-02T12:00:00"
}
```

```json
{
  "success": false,
  "error": "查询请求过多,请稍后再试",
  "code": 429,
  "timestamp": "2025-10-02T12:00:00"
}
```

**数据字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `playerName` | string | 玩家名称 |
| `uuid` | string | 玩家UUID |
| `isOnline` | boolean | 是否在线 |
| `hasPlayedBefore` | boolean | 是否曾经登录过 |
| `firstPlayed` | long | 首次登录时间戳（毫秒） |
| `lastPlayed` | long | 最后登录时间戳（毫秒） |
| `lastLogin` | long | 最后登录时间戳（毫秒） |
| `gameMode` | string | 游戏模式 (SURVIVAL/CREATIVE/ADVENTURE/SPECTATOR/UNKNOWN) |
| `location` | object | 当前位置信息（仅在线） |
| `location.world` | string | 世界名称 |
| `location.x/y/z` | double | 坐标 |
| `location.yaw/pitch` | float | 视角方向 |
| `location.dimension` | string | 维度 (NORMAL/NETHER/THE_END) |
| `bedSpawnLocation` | object | 重生点位置 |
| `health` | double | 当前生命值（仅在线） |
| `maxHealth` | double | 最大生命值（仅在线） |
| `foodLevel` | int | 饥饿值（仅在线） |
| `saturation` | float | 饱和度（仅在线） |
| `level` | int | 经验等级（仅在线） |
| `exp` | float | 当前等级经验进度（仅在线） |
| `totalExperience` | int | 总经验值（仅在线） |
| `remainingAir` | int | 剩余空气值（仅在线） |
| `fireTicks` | int | 火焰剩余时间（仅在线） |
| `isFlying` | boolean | 是否正在飞行（仅在线） |
| `allowFlight` | boolean | 是否允许飞行（仅在线） |
| `walkSpeed` | float | 行走速度（仅在线） |
| `flySpeed` | float | 飞行速度（仅在线） |
| `potionEffects` | array | 药水效果列表（仅在线） |
| `inventory` | object | 背包信息（仅在线） |
| `inventory.mainInventory` | array | 主背包物品 |
| `inventory.armor` | array | 装备栏物品 |
| `inventory.mainHand` | object | 主手物品 |
| `inventory.offHand` | object | 副手物品 |
| `enderChest` | array | 末影箱物品（仅在线） |
| `statistics` | object | 游戏统计数据（仅在线） |
| `statistics.playTime` | long | 游戏时长（秒） |
| `statistics.deaths` | int | 死亡次数 |
| `statistics.mobKills` | int | 生物击杀数 |
| `statistics.playerKills` | int | 玩家击杀数 |

**使用场景：**
- 查看玩家当前状态和位置
- 监控玩家背包和装备
- 分析玩家游戏数据
- 开发自定义管理工具
- 生成玩家数据报告

**注意事项：**
- 离线玩家只能获取有限的基本信息（需要设置 `includeOffline=true`）
- 在线玩家可以获取完整的实时数据
- 需要 API Token 认证才能访问
- 玩家名称区分大小写
- 查询在线玩家超时时间为 3 秒，离线玩家为 5 秒
- 系统限制最多 5 个并发查询，超过限制将返回 429 错误
- 如果服务器 TPS 过低可能返回 504 超时错误

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
    "plugin_version": "0.5.0",
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
    "version": "0.5.0",
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

#### 🎯 新版API - 简化的白名单管理

```bash
# 1. 获取白名单列表
curl -X GET http://localhost:22222/api/v1/whitelist \
  -H "X-API-Key: sk-your-api-token-here"

# 2. 添加白名单条目（只需玩家名）
curl -X POST http://localhost:22222/api/v1/whitelist \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{
    "name": "NewPlayer",
    "source": "API"
  }'

# 3. 添加白名单条目（完整参数）
curl -X POST http://localhost:22222/api/v1/whitelist \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{
    "name": "NewPlayer",
    "source": "ADMIN", 
    "added_by_name": "AdminUser",
    "added_by_uuid": "admin-uuid-here"
  }'

# 4. 删除白名单条目
curl -X DELETE http://localhost:22222/api/v1/whitelist/550e8400-e29b-41d4-a716-446655440000 \
  -H "X-API-Key: sk-your-api-token-here"

# 5. 获取白名单统计
curl -X GET http://localhost:22222/api/v1/whitelist/stats \
  -H "X-API-Key: sk-your-api-token-here"

# 6. 手动触发同步
curl -X POST http://localhost:22222/api/v1/whitelist/sync \
  -H "X-API-Key: sk-your-api-token-here"

# 7. 获取同步状态
curl -X GET http://localhost:22222/api/v1/whitelist/sync/status \
  -H "X-API-Key: sk-your-api-token-here"

# 8. 生成注册令牌（需要管理员密码）
curl -X POST http://localhost:22222/api/v1/admin/generate-token \
  -H "Content-Type: application/json" \
  -H "X-Admin-Password: your-admin-password" \
  -d '{
    "expiryHours": 24
  }'
```

#### 批量操作示例

```bash
# 批量添加白名单（新版）
curl -X POST http://localhost:22222/api/v1/whitelist/batch \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{
    "operation": "add",
    "source": "API",
    "added_by_name": "AdminUser",
    "players": [
      {"name": "Player1"},
      {"name": "Player2"},
      {"name": "Player3"}
    ]
  }'

# 批量删除白名单
curl -X POST http://localhost:22222/api/v1/whitelist/batch \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{
    "operation": "remove",
    "added_by_name": "AdminUser",
    "players": [
      {"uuid": "550e8400-e29b-41d4-a716-446655440000"},
      {"uuid": "550e8400-e29b-41d4-a716-446655440001"}
    ]
  }'
```

#### 用户自助注册示例

```bash
# 用户注册（只需玩家名）
curl -X POST http://localhost:22222/api/v1/register \
  -H "Content-Type: application/json" \
  -d '{
    "token": "reg_xxxxxxxxxxxxxxxxxxxxxxxxx",
    "playerName": "NewPlayer"
  }'

# 用户注册（提供UUID）
curl -X POST http://localhost:22222/api/v1/register \
  -H "Content-Type: application/json" \
  -d '{
    "token": "reg_xxxxxxxxxxxxxxxxxxxxxxxxx", 
    "playerName": "NewPlayer",
    "playerUuid": "550e8400-e29b-41d4-a716-446655440000"
  }'
```

### 玩家数据查询示例

```bash
# 查询在线玩家数据
curl -X GET "http://localhost:22222/api/v1/player?name=PlayerName" \
  -H "X-API-Key: sk-your-api-token-here"

# 查询离线玩家数据
curl -X GET "http://localhost:22222/api/v1/player?name=PlayerName&includeOffline=true" \
  -H "X-API-Key: sk-your-api-token-here"
```

### 系统监控示例

```bash
# 获取服务器状态
curl -X GET http://localhost:22222/api/v1/server/status \
  -H "X-API-Key: sk-your-api-token-here"

# 获取服务器性能数据
curl -X GET http://localhost:22222/api/v1/server/performance \
  -H "X-API-Key: sk-your-api-token-here"

# 健康检查（无需认证）
curl -X GET http://localhost:22222/api/v1/health
```

## 版本信息

- **当前版本**: v0.5.0
- **API版本**: v1  
- **最后更新**: 2025-10-02
- **兼容性**: Minecraft 1.20.1, Arclight
- **设计理念**: 基于 WhitelistPlus 插件设计

## 更新日志

### v0.5.0 (2025-10-02) - WhitelistPlus设计集成
- 🎯 **重大改进**：基于WhitelistPlus设计理念重构白名单系统
- ✨ **简化API**：添加白名单现在只需玩家名，UUID可选
- 🔄 **自动UUID补充**：玩家首次登录时自动补充UUID
- 📊 **增强统计**：新增UUID待补充状态、来源分解等统计信息
- 🔧 **批量操作**：支持批量添加和删除操作
- 📁 **同步系统**：新增UUID更新同步任务类型
- 🎮 **兼容性**：完美支持离线和正版服务器
- 📖 **文档更新**：全面更新API文档和使用示例

### v0.1.0 (2024-01-01) - 初始版本
- 🚀 初始版本发布
- 📡 支持基本的服务器信息获取
- ⚡ 集成 Spark 性能监控
- 🌍 添加详细的维度信息
- 📈 实现完整的性能数据收集
- 🔐 基础白名单管理功能

## 迁移指南

### 从v0.1.0升级到v0.5.0

**API变化：**
1. `POST /api/v1/whitelist` 不再要求 `uuid` 参数
2. 新增 `uuid_pending` 状态字段
3. 批量操作API结构调整
4. 新增同步状态查询端点

**兼容性：**
- ✅ 向后兼容：旧的API调用仍然有效
- ✅ 数据库兼容：现有数据无需迁移
- ✅ JSON文件兼容：现有白名单文件继续有效

**建议操作：**
```bash
# 检查新的统计信息
curl -X GET http://localhost:22222/api/v1/whitelist/stats \
  -H "X-API-Key: sk-your-api-token-here"

# 测试新的简化添加API
curl -X POST http://localhost:22222/api/v1/whitelist \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{"name": "TestPlayer", "source": "API"}'
```

## 技术支持

如果您在使用API时遇到问题，请：

1. 📋 检查本文档中的错误代码说明
2. 🔍 验证请求格式和认证信息  
3. 📝 查看服务器日志获取详细错误信息
4. 💬 查看 UUID自动补充机制 部分了解新特性
5. 🆘 联系技术支持团队

## 常见问题

**Q: 为什么有些玩家的UUID显示为null？**
A: 这是正常情况。采用新的设计后，玩家添加时UUID可以为空，会在首次登录时自动补充。

**Q: 如何确认UUID已经补充？**  
A: 可以通过 `GET /api/v1/whitelist/stats` 查看 `uuid_pending_entries` 数量，或查看具体玩家条目的 `uuid_pending` 字段。

**Q: 旧的API调用还能使用吗？**
A: 是的，系统保持向后兼容，但建议使用新的简化API以获得更好的体验。

**Q: 为什么查询玩家数据时提示"玩家不在线"？**
A: 默认情况下，API 只查询在线玩家。如果需要查询离线玩家，请在 URL 中添加 `includeOffline=true` 参数。

**Q: 查询玩家数据时为什么会超时？**
A: 玩家数据查询需要在 Minecraft 主线程执行，如果服务器 TPS 过低或负载过高，可能导致超时。查询在线玩家超时时间为 3 秒，离线玩家为 5 秒。

**Q: 为什么会收到 429 错误（请求过多）？**
A: 系统限制最多同时处理 5 个玩家数据查询请求，以保护服务器性能。请稍后重试或减少并发请求数量。

---

*本文档描述了ConvenientAccess白名单管理系统的API接口。系统基于WhitelistPlus设计理念，专注于简化白名单管理流程，同时保持数据完整性和系统可靠性。*
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
fetch('http://your-server:22222/api/v1/server/status', {
  headers: {
    'X-API-Key': 'sk-your-api-token-here'
  }
})
  .then(response => response.json())
  .then(data => {
    if (data.success) {
      console.log('服务器在线:', data.data.online);
    }
  });

// 获取性能数据
fetch('http://your-server:22222/api/v1/server/performance', {
  headers: {
    'X-API-Key': 'sk-your-api-token-here'
  }
})
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

# 设置认证头
headers = {
    'X-API-Key': 'sk-your-api-token-here'
}

# 获取玩家列表
response = requests.get('http://your-server:22222/api/v1/players/list', headers=headers)
if response.status_code == 200:
    data = response.json()
    if data['success']:
        players = data['data']['players']
        print(f'在线玩家数: {len(players)}')

# 添加白名单示例
def add_player_to_whitelist(player_name, source="API"):
    payload = {
        "name": player_name,
        "source": source
    }
    response = requests.post(
        'http://your-server:22222/api/v1/whitelist',
        json=payload,
        headers=headers
    )
    return response.json()
```

### cURL

```bash
# 获取服务器状态
curl -X GET "http://your-server:22222/api/v1/server/status" \
     -H "X-API-Key: sk-your-api-token-here" \
     -H "Accept: application/json"

# 获取世界信息  
curl -X GET "http://your-server:22222/api/v1/worlds/list" \
     -H "X-API-Key: sk-your-api-token-here" \
     -H "Accept: application/json"

# 使用Authorization Bearer认证的请求
curl -X GET "http://your-server:22222/api/v1/server/performance" \
     -H "Authorization: Bearer sk-your-api-token-here" \
     -H "Accept: application/json"
```

## 更新日志

### v0.5.0 (2025-10-02)
- 🎉 **重大更新**：基于 WhitelistPlus 设计理念完全重构
- ✨ **简化白名单管理**：添加白名单只需玩家名，UUID自动补充
- 🔐 **新增认证系统**：可配置的API Token认证，默认启用安全保护
- 🔑 **自动生成凭证**：插件首次启动自动生成管理员密码和API令牌
- 📊 **增强统计功能**：新增UUID待补充状态、来源分析等详细统计
- 🔄 **智能UUID补充**：玩家首次登录时自动补充UUID信息
- 🚀 **批量操作优化**：支持名称批量添加，提高管理效率
- 📝 **API文档更新**：完整的认证示例和使用指南

### v0.1.0
- 初始版本发布
- 支持基本的服务器信息获取
- 集成 Spark 性能监控
- 添加详细的维度信息
- 实现完整的性能数据收集