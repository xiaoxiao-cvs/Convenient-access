package com.xaoxiao.convenientaccess.command;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import com.xaoxiao.convenientaccess.cache.CacheManager;

/**
 * 插件命令处理器
 */
public class ConvenientAccessCommand implements CommandExecutor, TabCompleter {
    
    private final ConvenientAccessPlugin plugin;
    
    public ConvenientAccessCommand(ConvenientAccessPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("convenientaccess.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
            return true;
        }
        
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "reload":
                handleReload(sender);
                break;
            case "status":
                handleStatus(sender);
                break;
            case "cache":
                handleCache(sender, args);
                break;
            case "backup":
                handleBackup(sender, args);
                break;
            case "whitelist":
            case "wl":
                handleWhitelist(sender, args);
                break;
            case "help":
                showHelp(sender);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "未知命令: " + args[0]);
                showHelp(sender);
                break;
        }
        
        return true;
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("convenientaccess.admin")) {
            return null;
        }
        
        if (args.length == 1) {
            return Arrays.asList("reload", "status", "cache", "backup", "whitelist", "wl", "help");
        } else if (args.length == 2 && "cache".equals(args[0])) {
            return Arrays.asList("clear", "stats");
        } else if (args.length == 2 && "backup".equals(args[0])) {
            return Arrays.asList("now", "list", "restore");
        } else if (args.length == 2 && ("whitelist".equals(args[0]) || "wl".equals(args[0]))) {
            return Arrays.asList("add", "remove", "list", "check", "sync");
        }
        
        return null;
    }
    
    private void handleReload(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "正在重载插件配置...");
        
        try {
            plugin.reload();
            sender.sendMessage(ChatColor.GREEN + "插件配置重载完成！");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "重载失败: " + e.getMessage());
            plugin.getLogger().severe("重载配置时发生错误: " + e.getMessage());
        }
    }
    
    private void handleStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== ConvenientAccess 状态 ===");
        sender.sendMessage(ChatColor.YELLOW + "版本: " + ChatColor.WHITE + plugin.getDescription().getVersion());
        
        // HTTP服务器状态
        boolean httpRunning = plugin.getHttpServer() != null && plugin.getHttpServer().isRunning();
        sender.sendMessage(ChatColor.YELLOW + "HTTP服务器: " + 
            (httpRunning ? ChatColor.GREEN + "运行中" : ChatColor.RED + "已停止"));
        
        if (httpRunning) {
            int port = plugin.getConfigManager().getHttpPort();
            String host = plugin.getConfigManager().getHttpHost();
            sender.sendMessage(ChatColor.YELLOW + "监听地址: " + ChatColor.WHITE + 
                String.format("http://%s:%d", "0.0.0.0".equals(host) ? "localhost" : host, port));
        }
        
        // Spark集成状态
        boolean sparkAvailable = plugin.getSparkIntegration().isSparkAvailable();
        sender.sendMessage(ChatColor.YELLOW + "Spark集成: " + 
            (sparkAvailable ? ChatColor.GREEN + "可用" : ChatColor.RED + "不可用"));
        
        // 缓存状态
        if (plugin.getCacheManager() != null) {
            CacheManager.CacheStats stats = plugin.getCacheManager().getStats();
            sender.sendMessage(ChatColor.YELLOW + "缓存统计: " + ChatColor.WHITE + 
                String.format("总计 %d 项，有效 %d 项", stats.getTotalEntries(), stats.getValidEntries()));
        }
        
        sender.sendMessage(ChatColor.GOLD + "========================");
    }
    
    private void handleCache(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /ca cache <clear|stats>");
            return;
        }
        
        switch (args[1].toLowerCase()) {
            case "clear":
                plugin.getCacheManager().clearAll();
                sender.sendMessage(ChatColor.GREEN + "缓存已清理！");
                break;
            case "stats":
                showCacheStats(sender);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "未知缓存命令: " + args[1]);
                break;
        }
    }
    
    private void showCacheStats(CommandSender sender) {
        CacheManager.CacheStats stats = plugin.getCacheManager().getStats();
        
        sender.sendMessage(ChatColor.GOLD + "=== 缓存统计 ===");
        sender.sendMessage(ChatColor.YELLOW + "总缓存项: " + ChatColor.WHITE + stats.getTotalEntries());
        sender.sendMessage(ChatColor.YELLOW + "有效缓存项: " + ChatColor.WHITE + stats.getValidEntries());
        sender.sendMessage(ChatColor.YELLOW + "过期缓存项: " + ChatColor.WHITE + stats.getExpiredEntries());
        
        // 显示缓存配置
        sender.sendMessage(ChatColor.GOLD + "=== 缓存配置 ===");
        sender.sendMessage(ChatColor.YELLOW + "服务器信息缓存: " + ChatColor.WHITE + 
            plugin.getConfigManager().getServerInfoCacheTime() + "秒");
        sender.sendMessage(ChatColor.YELLOW + "性能数据缓存: " + ChatColor.WHITE + 
            plugin.getConfigManager().getPerformanceCacheTime() + "秒");
        sender.sendMessage(ChatColor.YELLOW + "玩家数据缓存: " + ChatColor.WHITE + 
            plugin.getConfigManager().getPlayersCacheTime() + "秒");
        sender.sendMessage(ChatColor.YELLOW + "世界数据缓存: " + ChatColor.WHITE + 
            plugin.getConfigManager().getWorldsCacheTime() + "秒");
    }
    
    private void handleBackup(CommandSender sender, String[] args) {
        if (plugin.getBackupManager() == null) {
            sender.sendMessage(ChatColor.RED + "备份管理器未初始化！");
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /ca backup <now|list|restore>");
            return;
        }
        
        switch (args[1].toLowerCase()) {
            case "now":
                sender.sendMessage(ChatColor.YELLOW + "正在执行数据库备份...");
                plugin.getBackupManager().manualBackup().thenAccept(success -> {
                    if (success) {
                        sender.sendMessage(ChatColor.GREEN + "数据库备份成功！");
                    } else {
                        sender.sendMessage(ChatColor.RED + "数据库备份失败，请查看控制台日志。");
                    }
                });
                break;
            case "list":
                showBackupList(sender);
                break;
            case "restore":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /ca backup restore <备份文件名>");
                    return;
                }
                restoreBackup(sender, args[2]);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "未知备份命令: " + args[1]);
                break;
        }
    }
    
    private void showBackupList(CommandSender sender) {
        List<com.xaoxiao.convenientaccess.backup.BackupManager.BackupInfo> backups = 
            plugin.getBackupManager().listBackups();
        
        if (backups.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "暂无备份文件");
            return;
        }
        
        sender.sendMessage(ChatColor.GOLD + "=== 备份文件列表 ===");
        for (int i = 0; i < backups.size(); i++) {
            com.xaoxiao.convenientaccess.backup.BackupManager.BackupInfo backup = backups.get(i);
            sender.sendMessage(ChatColor.YELLOW + String.format("%d. %s", i + 1, backup.getFileName()));
            sender.sendMessage(ChatColor.WHITE + "   大小: " + backup.getFileSizeFormatted() + 
                             " | 创建时间: " + backup.getCreatedTime());
        }
        sender.sendMessage(ChatColor.GRAY + "提示: 使用 /ca backup restore <文件名> 恢复备份");
    }
    
    private void restoreBackup(CommandSender sender, String backupFileName) {
        sender.sendMessage(ChatColor.YELLOW + "正在恢复备份: " + backupFileName);
        sender.sendMessage(ChatColor.RED + "警告: 此操作将覆盖当前数据库！");
        
        plugin.getBackupManager().restoreBackup(backupFileName).thenAccept(success -> {
            if (success) {
                sender.sendMessage(ChatColor.GREEN + "备份恢复成功！建议重启服务器以应用更改。");
            } else {
                sender.sendMessage(ChatColor.RED + "备份恢复失败，请查看控制台日志。");
            }
        });
    }
    
    private void handleWhitelist(CommandSender sender, String[] args) {
        if (plugin.getWhitelistSystem() == null || plugin.getWhitelistSystem().getWhitelistManager() == null) {
            sender.sendMessage(ChatColor.RED + "白名单系统未初始化！");
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /ca whitelist <add|remove|list|check|sync>");
            return;
        }
        
        switch (args[1].toLowerCase()) {
            case "add":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /ca whitelist add <玩家名> [备注]");
                    return;
                }
                handleWhitelistAdd(sender, args);
                break;
            case "remove":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /ca whitelist remove <玩家名>");
                    return;
                }
                handleWhitelistRemove(sender, args[2]);
                break;
            case "list":
                handleWhitelistList(sender);
                break;
            case "check":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /ca whitelist check <玩家名>");
                    return;
                }
                handleWhitelistCheck(sender, args[2]);
                break;
            case "sync":
                handleWhitelistSync(sender);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "未知白名单命令: " + args[1]);
                break;
        }
    }
    
    private void handleWhitelistAdd(CommandSender sender, String[] args) {
        String playerName = args[2];
        
        sender.sendMessage(ChatColor.YELLOW + "正在添加玩家 " + playerName + " 到白名单...");
        
        // 使用 addPlayerOffline 方法，它会自动获取UUID
        plugin.getWhitelistSystem().getWhitelistManager()
            .addPlayerOffline(playerName, sender.getName(), "CONSOLE", 
                com.xaoxiao.convenientaccess.whitelist.WhitelistEntry.Source.ADMIN)
            .thenAccept(success -> {
                if (success) {
                    sender.sendMessage(ChatColor.GREEN + "✓ 成功添加玩家 " + playerName + " 到白名单！");
                } else {
                    sender.sendMessage(ChatColor.RED + "✗ 添加失败，玩家可能已在白名单中");
                }
            })
            .exceptionally(throwable -> {
                sender.sendMessage(ChatColor.RED + "✗ 添加失败: " + throwable.getMessage());
                return null;
            });
    }
    
    private void handleWhitelistRemove(CommandSender sender, String playerName) {
        sender.sendMessage(ChatColor.YELLOW + "正在从白名单移除玩家 " + playerName + "...");
        
        // 首先通过名称查找玩家的UUID
        plugin.getWhitelistSystem().getWhitelistManager()
            .searchPlayersByName(playerName, 1)
            .thenCompose(entries -> {
                if (entries.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "✗ 玩家不在白名单中");
                    return CompletableFuture.completedFuture(false);
                }
                
                String uuid = entries.get(0).getUuid();
                return plugin.getWhitelistSystem().getWhitelistManager().removePlayer(uuid);
            })
            .thenAccept(success -> {
                if (success) {
                    sender.sendMessage(ChatColor.GREEN + "✓ 成功从白名单移除玩家 " + playerName + "！");
                }
            })
            .exceptionally(throwable -> {
                sender.sendMessage(ChatColor.RED + "✗ 移除失败: " + throwable.getMessage());
                return null;
            });
    }
    
    private void handleWhitelistList(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "正在获取白名单列表...");
        
        // 使用分页API获取前20个
        plugin.getWhitelistSystem().getWhitelistManager()
            .getWhitelistPaginated(1, 20, null, null, null, "added_at", "DESC", null, null)
            .thenAccept(result -> {
                if (result.getItems().isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "白名单为空");
                    return;
                }
                
                sender.sendMessage(ChatColor.GOLD + "=== 白名单列表 (共 " + result.getTotal() + " 人) ===");
                int count = 0;
                for (com.xaoxiao.convenientaccess.whitelist.WhitelistEntry entry : result.getItems()) {
                    count++;
                    sender.sendMessage(ChatColor.YELLOW + String.format("%d. %s", count, entry.getName()));
                    sender.sendMessage(ChatColor.GRAY + "   UUID: " + entry.getUuid());
                    if (count >= 10) {
                        sender.sendMessage(ChatColor.GRAY + "... 还有 " + (result.getTotal() - 10) + " 个玩家");
                        break;
                    }
                }
                sender.sendMessage(ChatColor.GRAY + "提示: 使用API查看完整列表");
            })
            .exceptionally(throwable -> {
                sender.sendMessage(ChatColor.RED + "✗ 获取白名单失败: " + throwable.getMessage());
                return null;
            });
    }
    
    private void handleWhitelistCheck(CommandSender sender, String playerName) {
        plugin.getWhitelistSystem().getWhitelistManager()
            .isPlayerWhitelistedByName(playerName)
            .thenAccept(isWhitelisted -> {
                if (isWhitelisted) {
                    sender.sendMessage(ChatColor.GREEN + "✓ 玩家 " + playerName + " 在白名单中");
                } else {
                    sender.sendMessage(ChatColor.RED + "✗ 玩家 " + playerName + " 不在白名单中");
                }
            })
            .exceptionally(throwable -> {
                sender.sendMessage(ChatColor.RED + "✗ 查询失败: " + throwable.getMessage());
                return null;
            });
    }
    
    private void handleWhitelistSync(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "正在触发白名单同步...");
        
        // 触发全量同步
        plugin.getWhitelistSystem().getSyncTaskManager().scheduleFullSync();
        sender.sendMessage(ChatColor.GREEN + "✓ 同步任务已提交，请稍后查看同步结果");
    }
    
    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== ConvenientAccess 命令帮助 ===");
        sender.sendMessage(ChatColor.YELLOW + "/ca reload" + ChatColor.WHITE + " - 重载插件配置");
        sender.sendMessage(ChatColor.YELLOW + "/ca status" + ChatColor.WHITE + " - 显示插件状态");
        sender.sendMessage(ChatColor.YELLOW + "/ca cache clear" + ChatColor.WHITE + " - 清理所有缓存");
        sender.sendMessage(ChatColor.YELLOW + "/ca cache stats" + ChatColor.WHITE + " - 显示缓存统计");
        sender.sendMessage(ChatColor.YELLOW + "/ca backup now" + ChatColor.WHITE + " - 立即执行备份");
        sender.sendMessage(ChatColor.YELLOW + "/ca backup list" + ChatColor.WHITE + " - 查看备份列表");
        sender.sendMessage(ChatColor.YELLOW + "/ca backup restore <文件名>" + ChatColor.WHITE + " - 恢复备份");
        sender.sendMessage(ChatColor.YELLOW + "/ca whitelist add <玩家名> [备注]" + ChatColor.WHITE + " - 添加白名单");
        sender.sendMessage(ChatColor.YELLOW + "/ca whitelist remove <玩家名>" + ChatColor.WHITE + " - 移除白名单");
        sender.sendMessage(ChatColor.YELLOW + "/ca whitelist list" + ChatColor.WHITE + " - 查看白名单列表");
        sender.sendMessage(ChatColor.YELLOW + "/ca whitelist check <玩家名>" + ChatColor.WHITE + " - 检查是否在白名单");
        sender.sendMessage(ChatColor.YELLOW + "/ca whitelist sync" + ChatColor.WHITE + " - 触发白名单同步");
        sender.sendMessage(ChatColor.YELLOW + "/ca help" + ChatColor.WHITE + " - 显示此帮助信息");
        
        // 显示API端点信息
        if (plugin.getHttpServer() != null && plugin.getHttpServer().isRunning()) {
            int port = plugin.getConfigManager().getHttpPort();
            String apiVersion = plugin.getConfigManager().getApiVersion();
            String baseUrl = "http://localhost:" + port + "/api/" + apiVersion;
            
            sender.sendMessage(ChatColor.GOLD + "=== API 端点 ===");
            sender.sendMessage(ChatColor.AQUA + baseUrl + "/server/info" + ChatColor.WHITE + " - 服务器基本信息");
            sender.sendMessage(ChatColor.AQUA + baseUrl + "/server/status" + ChatColor.WHITE + " - 服务器状态");
            sender.sendMessage(ChatColor.AQUA + baseUrl + "/server/performance" + ChatColor.WHITE + " - 性能数据");
            sender.sendMessage(ChatColor.AQUA + baseUrl + "/players/online" + ChatColor.WHITE + " - 在线玩家数");
            sender.sendMessage(ChatColor.AQUA + baseUrl + "/players/list" + ChatColor.WHITE + " - 玩家列表");
            sender.sendMessage(ChatColor.AQUA + baseUrl + "/worlds/list" + ChatColor.WHITE + " - 世界列表");
            sender.sendMessage(ChatColor.AQUA + baseUrl + "/system/resources" + ChatColor.WHITE + " - 系统资源");
            sender.sendMessage(ChatColor.AQUA + baseUrl + "/health" + ChatColor.WHITE + " - 健康检查");
        }
    }
}