# 📝 配置文件自动更新说明

## ❓ 配置文件会自动更新吗？

**答案**: ⚠️ **部分自动,需要注意**

---

## 🔄 当前配置更新机制

### 1. 首次启动 (插件安装)

✅ **完全自动**:
```java
// ConfigManager.java
private void loadConfig() {
    plugin.saveDefaultConfig();  // 从 JAR 复制 config.yml 到插件目录
    plugin.reloadConfig();
    this.config = plugin.getConfig();
}
```

**效果**:
- 如果 `plugins/ConvenientAccess/config.yml` 不存在
- 自动从 JAR 包内的 `resources/config.yml` 复制
- ✅ 包含所有最新配置项

---

### 2. 插件更新 (升级版本)

⚠️ **不会自动更新**:
- Bukkit/Spigot 默认行为: `saveDefaultConfig()` 只在文件不存在时才复制
- 如果 `config.yml` 已存在,**不会覆盖**
- ❌ 新增的配置项**不会自动添加**

**问题场景**:
```yaml
# v0.4.0 的 config.yml (旧版本)
api:
  auth:
    enabled: true
    admin-password: "xxx"
    api-token: "xxx"
    # ❌ 缺少新增的 login-attempt-limit 配置

# v0.5.0 的 config.yml (新版本) - 不会自动合并
api:
  auth:
    enabled: true
    admin-password: "xxx"
    api-token: "xxx"
    login-attempt-limit:  # ✅ 新增配置
      enabled: true
      max-attempts: 5
      lock-duration-minutes: 15
```

---

## 🛠️ 解决方案

### 方案1: 手动添加新配置项 (推荐)

**用户需要手动编辑 `config.yml` 添加**:

```yaml
api:
  auth:
    # ... 现有配置 ...
    
    # 👇 手动添加这部分
    login-attempt-limit:
      enabled: true
      max-attempts: 5
      lock-duration-minutes: 15
```

**优点**: 
- ✅ 保留现有配置
- ✅ 不会丢失自定义设置

**缺点**:
- ❌ 需要用户手动操作
- ❌ 容易遗漏

---

### 方案2: 代码提供默认值 (已实现) ✅

**当前实现**:
```java
// 读取配置时提供默认值
boolean attemptLimitEnabled = config.getBoolean("api.auth.login-attempt-limit.enabled", true);
int maxAttempts = config.getInt("api.auth.login-attempt-limit.max-attempts", 5);
int lockDuration = config.getInt("api.auth.login-attempt-limit.lock-duration-minutes", 15);
```

**效果**:
- ✅ 即使配置文件中没有该项,代码也能正常工作
- ✅ 使用硬编码的默认值
- ⚠️ 用户无法通过查看配置文件知道有这个功能

---

### 方案3: 自动合并配置 (建议实施) 🎯

**实现思路**:
```java
public class ConfigManager {
    private void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        
        // 🆕 自动添加缺失的配置项
        autoUpdateConfig();
    }
    
    private void autoUpdateConfig() {
        boolean updated = false;
        
        // 检查并添加 login-attempt-limit 配置
        if (!config.contains("api.auth.login-attempt-limit.enabled")) {
            config.set("api.auth.login-attempt-limit.enabled", true);
            updated = true;
        }
        if (!config.contains("api.auth.login-attempt-limit.max-attempts")) {
            config.set("api.auth.login-attempt-limit.max-attempts", 5);
            updated = true;
        }
        if (!config.contains("api.auth.login-attempt-limit.lock-duration-minutes")) {
            config.set("api.auth.login-attempt-limit.lock-duration-minutes", 15);
            updated = true;
        }
        
        if (updated) {
            plugin.saveConfig();
            logger.info("配置文件已自动更新,添加了新的配置项");
        }
    }
}
```

**优点**:
- ✅ 完全自动,无需用户干预
- ✅ 保留现有配置
- ✅ 自动添加新配置项

**缺点**:
- ⚠️ 需要为每个新配置项编写检查代码
- ⚠️ 版本升级时需要维护

---

### 方案4: 配置迁移系统 (最佳但复杂)

**版本化配置管理**:
```java
public class ConfigMigrator {
    private static final String CONFIG_VERSION_KEY = "config-version";
    private static final int CURRENT_VERSION = 2;
    
    public void migrate(FileConfiguration config) {
        int version = config.getInt(CONFIG_VERSION_KEY, 1);
        
        if (version < 2) {
            // 从版本1迁移到版本2
            migrateV1toV2(config);
        }
        
        // 更新版本号
        config.set(CONFIG_VERSION_KEY, CURRENT_VERSION);
    }
    
    private void migrateV1toV2(FileConfiguration config) {
        // 添加 login-attempt-limit 配置
        if (!config.contains("api.auth.login-attempt-limit")) {
            config.set("api.auth.login-attempt-limit.enabled", true);
            config.set("api.auth.login-attempt-limit.max-attempts", 5);
            config.set("api.auth.login-attempt-limit.lock-duration-minutes", 15);
            logger.info("配置已从 v1 迁移到 v2: 添加登录失败限制功能");
        }
    }
}
```

---

## 📋 当前状态

### 已有新配置项 (v0.5.0)

```yaml
api:
  auth:
    login-attempt-limit:
      enabled: true
      max-attempts: 5
      lock-duration-minutes: 15
```

### 当前行为

1. **首次安装**: ✅ 自动包含新配置
2. **从旧版本升级**: ❌ 需要手动添加

### 代码行为

```java
// 即使配置文件中没有,代码也会使用默认值
boolean attemptLimitEnabled = config.getBoolean(
    "api.auth.login-attempt-limit.enabled", 
    true  // 👈 默认启用
);
```

---

## 🎯 推荐的升级步骤

### 对于用户

1. **备份现有配置**:
   ```bash
   cp plugins/ConvenientAccess/config.yml plugins/ConvenientAccess/config.yml.backup
   ```

2. **查看新配置示例**:
   - 解压 JAR 包查看 `resources/config.yml`
   - 或查看 GitHub/文档

3. **手动添加新配置项**:
   ```yaml
   # 在 api.auth 部分添加
   login-attempt-limit:
     enabled: true
     max-attempts: 5
     lock-duration-minutes: 15
   ```

4. **重载插件**:
   ```bash
   /convenientaccess reload
   ```

### 对于开发者 (TODO)

建议实施**方案3: 自动合并配置**:

1. 在 `ConfigManager.java` 添加 `autoUpdateConfig()` 方法
2. 在 `loadConfig()` 中调用
3. 检查并添加缺失的配置项
4. 保存配置文件

---

## ⚠️ 注意事项

### 1. 配置文件格式
- YAML 格式严格要求**缩进**
- 使用**空格**缩进,不要用 Tab
- 每级缩进 **2个空格**

### 2. 重载配置
```bash
# 重载配置命令
/convenientaccess reload

# 或重启插件
/reload confirm  # 不推荐,可能导致内存泄漏
```

### 3. 配置优先级
1. 配置文件中的值 (最高优先级)
2. 代码中的默认值 (配置缺失时使用)

---

## 📚 相关文件

- 📄 `/src/main/resources/config.yml` - 默认配置模板
- 📄 `ConfigManager.java` - 配置管理器
- 📄 `plugins/ConvenientAccess/config.yml` - 实际运行配置

---

## 🔄 未来改进计划

- [ ] 实现配置自动合并功能
- [ ] 添加配置版本管理
- [ ] 提供配置迁移向导
- [ ] 生成配置变更日志
- [ ] 支持配置热重载

---

**最后更新**: 2025年10月3日  
**适用版本**: v0.5.0+
