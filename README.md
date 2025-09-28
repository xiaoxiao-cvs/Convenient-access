# ConvenientAccess

一个为 Minecraft 1.20.1 Arclight 服务端设计的便捷服务器信息获取插件。

## 功能特性

- 🚀 **高性能异步数据获取** - 所有数据获取操作均为异步执行，不影响服务器性能
- 🔌 **Spark 集成** - 优先使用 Spark 插件获取详细性能数据，未安装时自动降级到原版 API
- 🌐 **RESTful API** - 提供标准的 HTTP API 接口，支持跨域访问
- 💾 **智能缓存系统** - 内置缓存机制，减少重复计算，提升响应速度
- 🔒 **安全认证** - 支持 API 密钥认证和请求频率限制
- 📊 **丰富的数据类型** - 服务器信息、性能数据、玩家信息、世界数据等

## 安装要求

- Minecraft 1.20.1
- Arclight 服务端
- Java 17+
- Spark 插件（可选，推荐安装以获取更详细的性能数据）

## 安装方法

1. 下载最新版本的 `ConvenientAccess-0.1.0.jar`
2. 将插件文件放入服务器的 `plugins` 目录
3. 重启服务器或使用 `/reload` 命令
4. 插件将自动生成配置文件并启动 HTTP 服务器

## 配置文件

插件首次运行时会在 `plugins/ConvenientAccess/config.yml` 生成配置文件：

```yaml
# HTTP服务器配置
http:
  enabled: true          # 是否启用HTTP服务器
  port: 8080            # 监听端口
  host: "0.0.0.0"       # 监听地址
  max-threads: 10       # 最大线程数
  timeout: 30000        # 连接超时时间(毫秒)

# API配置
api:
  version: "v1"         # API版本
  auth:
    enabled: false      # 是否启用API认证
    api-key: ""        # API密钥
  rate-limit:
    enabled: true       # 是否启用请求频率限制
    requests-per-minute: 60  # 每分钟最大请求数
  cors:
    enabled: true       # 是否启用CORS
    allowed-origins: ["*"]   # 允许的源

# 数据缓存配置
cache:
  server-info: 300      # 服务器信息缓存时间(秒)
  performance: 5        # 性能数据缓存时间(秒)
  players: 10          # 玩家数据缓存时间(秒)
  worlds: 60           # 世界数据缓存时间(秒)

# Spark集成配置
spark:
  prefer-spark: true    # 是否优先使用Spark API
  timeout: 5000        # Spark API超时时间(毫秒)

# 日志配置
logging:
  log-requests: false   # 是否记录API请求日志
  debug: false         # 是否启用调试模式
```

## API 端点

所有 API 端点都使用 `/api/v1` 前缀：

### 服务器信息
- `GET /api/v1/server/info` - 获取服务器基本信息
- `GET /api/v1/server/status` - 获取服务器运行状态
- `GET /api/v1/server/performance` - 获取服务器性能数据

### 玩家信息
- `GET /api/v1/players/online` - 获取在线玩家数量
- `GET /api/v1/players/list` - 获取详细玩家列表

### 世界信息
- `GET /api/v1/worlds/list` - 获取世界列表和详细信息

### 系统信息
- `GET /api/v1/system/resources` - 获取系统资源使用情况
- `GET /api/v1/health` - 健康检查端点

## API 响应格式

所有 API 响应都使用统一的 JSON 格式：

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
  "error": "Error Type",
  "message": "详细错误信息",
  "timestamp": 1640995200000
}
```

## 使用示例

### 获取服务器基本信息
```bash
curl http://localhost:8080/api/v1/server/info
```

### 获取性能数据
```bash
curl http://localhost:8080/api/v1/server/performance
```

### 获取在线玩家列表
```bash
curl http://localhost:8080/api/v1/players/list
```

## 命令

插件提供以下管理命令（需要 `convenientaccess.admin` 权限）：

- `/ca reload` - 重载插件配置
- `/ca status` - 显示插件运行状态
- `/ca cache clear` - 清理所有缓存
- `/ca cache stats` - 显示缓存统计信息
- `/ca help` - 显示帮助信息

## 权限

- `convenientaccess.admin` - 管理员权限，允许使用所有命令（默认：OP）
- `convenientaccess.api.access` - API访问权限（默认：所有人）
- `convenientaccess.api.sensitive` - 敏感信息访问权限（默认：OP）

## 性能优化

- 所有数据获取操作都是异步执行的，不会阻塞主线程
- 内置智能缓存系统，避免重复计算
- 支持请求频率限制，防止API滥用
- 使用专用线程池处理HTTP请求

## Spark 集成

当服务器安装了 Spark 插件时，ConvenientAccess 会自动检测并使用 Spark API 获取更详细的性能数据：

- 更精确的 TPS 和 MSPT 数据
- 详细的 CPU 使用率统计
- 内存和垃圾回收信息
- 系统级性能指标

如果 Spark 不可用，插件会自动降级到使用原版 API，确保基本功能正常运行。

## 开发信息

- **版本**: 0.1.0
- **作者**: xaoxiao
- **许可证**: MIT
- **最低 Java 版本**: 17
- **支持的 Minecraft 版本**: 1.20.1

## 故障排除

### HTTP 服务器无法启动
1. 检查端口是否被占用
2. 确认防火墙设置
3. 查看服务器日志获取详细错误信息

### API 返回错误数据
1. 检查 Spark 插件是否正确安装
2. 清理缓存：`/ca cache clear`
3. 重载插件配置：`/ca reload`

### 性能问题
1. 调整缓存时间设置
2. 启用请求频率限制
3. 检查线程池配置

## 更新日志

### v0.1.0 (2024-01-01)
- 初始版本发布
- 实现基础 API 功能
- 添加 Spark 集成支持
- 实现缓存和异步处理机制

## 支持

如果您遇到问题或有功能建议，请在 GitHub 仓库中创建 Issue。

## 贡献

欢迎提交 Pull Request 来改进这个插件！