# ConvenientAccess API 文档

## 概述

ConvenientAccess 提供了一套完整的 RESTful API，用于获取 Minecraft 1.20.1 Arclight 服务器的详细信息。所有 API 端点都返回 JSON 格式的数据。

## 基础信息

- **基础URL**: `http://your-server:8080/api/v1`
- **内容类型**: `application/json`
- **字符编码**: `UTF-8`

## 认证

如果启用了 API 认证，需要在请求头中包含 API 密钥：

```http
Authorization: Bearer YOUR_API_KEY
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

## API 端点

### 1. 服务器状态

#### `GET /api/v1/status`

获取服务器基本状态信息。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "online": true,
    "timestamp": 1640995200000,
    "spark_available": true,
    "plugin_version": "0.1.0"
  }
}
```

### 2. 服务器信息

#### `GET /api/v1/server/info`

获取详细的服务器信息。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "name": "Minecraft Server",
    "version": "1.20.1-R0.1-SNAPSHOT",
    "bukkit_version": "1.20.1-R0.1-SNAPSHOT",
    "motd": "A Minecraft Server",
    "max_players": 20,
    "port": 25565,
    "allow_nether": true,
    "allow_end": true,
    "hardcore": false,
    "online_mode": true,
    "whitelist": false,
    "uptime_ms": 3600000,
    "uptime_formatted": "1h 0m 0s",
    "timestamp": 1640995200000
  }
}
```

### 3. 性能数据

#### `GET /api/v1/performance`

获取详细的服务器性能数据，包括 TPS、MSPT、CPU、内存、GC 和线程信息。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "tps": {
      "available": true,
      "source": "spark",
      "values": {
        "last_10s": 19.8,
        "last_1m": 19.5,
        "last_5m": 19.2
      },
      "server_load_percent": 2.5
    },
    "mspt": {
      "available": true,
      "source": "spark",
      "values": {
        "last_1m": {
          "mean": 45.2,
          "max": 120.5,
          "min": 25.1,
          "percentile_95": 85.3
        },
        "last_5m": {
          "mean": 48.1,
          "max": 150.2,
          "min": 22.8,
          "percentile_95": 92.7
        }
      }
    },
    "cpu": {
      "available": true,
      "source": "spark",
      "system": {
        "last_10s": 25.4,
        "last_1m": 23.8,
        "last_15m": 22.1
      },
      "process": {
        "last_10s": 15.2,
        "last_1m": 14.6,
        "last_15m": 13.9
      }
    },
    "memory": {
      "heap": {
        "init": 268435456,
        "used": 1073741824,
        "committed": 2147483648,
        "max": 4294967296,
        "usage_percent": 25.0
      },
      "non_heap": {
        "init": 2555904,
        "used": 52428800,
        "committed": 67108864,
        "max": -1
      },
      "pools": {
        "eden_space": {
          "used": 536870912,
          "committed": 1073741824,
          "max": 1073741824,
          "type": "HEAP"
        }
      },
      "source": "jvm"
    },
    "gc": {
      "collectors": {
        "g1_young_generation": {
          "collection_count": 150,
          "collection_time": 2500,
          "memory_pool_names": ["G1 Eden Space", "G1 Survivor Space"]
        },
        "g1_old_generation": {
          "collection_count": 5,
          "collection_time": 800,
          "memory_pool_names": ["G1 Old Gen"]
        }
      },
      "total_collections": 155,
      "total_time_ms": 3300,
      "average_time_per_collection": 21.29,
      "source": "jvm"
    },
    "threads": {
      "current_thread_count": 45,
      "daemon_thread_count": 38,
      "peak_thread_count": 52,
      "total_started_thread_count": 125,
      "deadlocked_threads": 0,
      "source": "jvm"
    },
    "timestamp": 1640995200000
  }
}
```

### 4. 玩家信息

#### `GET /api/v1/players`

获取在线玩家的基本统计信息。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "online_count": 5,
    "max_players": 20,
    "timestamp": 1640995200000
  }
}
```

#### `GET /api/v1/players/list`

获取详细的在线玩家列表。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "online_count": 2,
    "max_players": 20,
    "players": [
      {
        "name": "Player1",
        "uuid": "550e8400-e29b-41d4-a716-446655440000",
        "display_name": "Player1",
        "level": 30,
        "health": 20.0,
        "food_level": 20,
        "game_mode": "SURVIVAL",
        "world": "world",
        "location": {
          "x": 100.5,
          "y": 64.0,
          "z": -200.3,
          "yaw": 45.0,
          "pitch": 0.0
        },
        "ping": 25,
        "ip": "192.168.1.100"
      }
    ],
    "timestamp": 1640995200000
  }
}
```

### 5. 世界信息

#### `GET /api/v1/worlds/list`

获取服务器所有世界的详细信息。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "world_count": 3,
    "worlds": [
      {
        "name": "world",
        "environment": "NORMAL",
        "dimension_type": "overworld",
        "dimension_name": "主世界",
        "dimension_id": 0,
        "difficulty": "NORMAL",
        "spawn_location": {
          "x": 0.0,
          "y": 64.0,
          "z": 0.0
        },
        "time": 6000,
        "full_time": 24000,
        "weather_duration": 12000,
        "thunder_duration": 0,
        "has_storm": false,
        "thundering": false,
        "entity_count": 150,
        "living_entity_count": 120,
        "player_count": 2,
        "loaded_chunks": 256
      },
      {
        "name": "world_nether",
        "environment": "NETHER",
        "dimension_type": "the_nether",
        "dimension_name": "下界",
        "dimension_id": -1,
        "difficulty": "NORMAL",
        "spawn_location": {
          "x": 0.0,
          "y": 64.0,
          "z": 0.0
        },
        "time": 18000,
        "full_time": 18000,
        "weather_duration": 0,
        "thunder_duration": 0,
        "has_storm": false,
        "thundering": false,
        "entity_count": 50,
        "living_entity_count": 45,
        "player_count": 0,
        "loaded_chunks": 64
      },
      {
        "name": "world_the_end",
        "environment": "THE_END",
        "dimension_type": "the_end",
        "dimension_name": "末地",
        "dimension_id": 1,
        "difficulty": "NORMAL",
        "spawn_location": {
          "x": 100.0,
          "y": 49.0,
          "z": 0.0
        },
        "time": 6000,
        "full_time": 6000,
        "weather_duration": 0,
        "thunder_duration": 0,
        "has_storm": false,
        "thundering": false,
        "entity_count": 25,
        "living_entity_count": 20,
        "player_count": 0,
        "loaded_chunks": 32
      }
    ],
    "timestamp": 1640995200000
  }
}
```

### 6. 系统资源

#### `GET /api/v1/system/resources`

获取系统资源使用情况。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "memory": {
      "max": 4294967296,
      "total": 2147483648,
      "free": 1073741824,
      "used": 1073741824,
      "usage_percent": 25.0
    },
    "system": {
      "os_name": "Linux",
      "os_version": "5.4.0-74-generic",
      "os_arch": "amd64",
      "java_version": "17.0.1",
      "java_vendor": "Eclipse Adoptium",
      "available_processors": 8
    },
    "timestamp": 1640995200000
  }
}
```

### 7. 健康检查

#### `GET /api/v1/health`

简单的健康检查端点。

**响应示例：**
```json
{
  "success": true,
  "data": {
    "status": "healthy",
    "timestamp": 1640995200000
  }
}
```

## 数据字段说明

### 维度信息

| 字段 | 类型 | 说明 |
|------|------|------|
| `environment` | String | Bukkit 环境类型 (NORMAL, NETHER, THE_END) |
| `dimension_type` | String | 标准维度类型 (overworld, the_nether, the_end) |
| `dimension_name` | String | 中文维度名称 (主世界, 下界, 末地) |
| `dimension_id` | Integer | 维度ID (0: 主世界, -1: 下界, 1: 末地) |

### 性能数据说明

#### TPS (Ticks Per Second)
- `last_10s`: 最近10秒的平均TPS
- `last_1m`: 最近1分钟的平均TPS
- `last_5m`: 最近5分钟的平均TPS
- `server_load_percent`: 服务器负载百分比 (基于20TPS计算)

#### MSPT (Milliseconds Per Tick)
- `mean`: 平均每tick耗时
- `max`: 最大每tick耗时
- `min`: 最小每tick耗时
- `percentile_95`: 95%分位数

#### CPU 使用率
- `system`: 系统整体CPU使用率
- `process`: 服务器进程CPU使用率

#### 内存信息
- `heap`: 堆内存使用情况
- `non_heap`: 非堆内存使用情况
- `pools`: 各内存池详细信息

#### 垃圾回收 (GC)
- `collectors`: 各垃圾回收器的统计信息
- `total_collections`: 总回收次数
- `total_time_ms`: 总回收耗时
- `average_time_per_collection`: 平均每次回收耗时

## 错误代码

| 代码 | 说明 |
|------|------|
| 400 | 请求参数错误 |
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
fetch('http://your-server:8080/api/v1/status')
  .then(response => response.json())
  .then(data => {
    if (data.success) {
      console.log('服务器在线:', data.data.online);
    }
  });

// 获取性能数据
fetch('http://your-server:8080/api/v1/performance')
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
response = requests.get('http://your-server:8080/api/v1/players/list')
if response.status_code == 200:
    data = response.json()
    if data['success']:
        players = data['data']['players']
        print(f'在线玩家数: {len(players)}')
```

### cURL

```bash
# 获取世界信息
curl -X GET "http://your-server:8080/api/v1/worlds/list" \
     -H "Accept: application/json"

# 带认证的请求
curl -X GET "http://your-server:8080/api/v1/performance" \
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