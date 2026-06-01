package com.shinoyuki.accesshub.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.shinoyuki.accesshub.AccessHubMod;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.http.HttpServer;
import com.shinoyuki.accesshub.whitelist.WhitelistEntry;
import com.shinoyuki.accesshub.whitelist.WhitelistManager;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

/**
 * AccessHub 服务端命令注册.
 *
 * 主命令: /accesshub
 * 别名:  /ca, /ahub (通过 Brigadier redirect 共享同一棵子树, /ca status 等价于 /accesshub status)
 *
 * 权限: 默认要求 OP level 2 (.requires(src -&gt; src.hasPermission(2))),
 * 这是 Forge 命令的标准做法, 跟原版 /gamemode 等命令一致.
 *
 * 子命令:
 *   /accesshub status  - 显示运行状态 (HTTP 服务、配置文件路径)
 *   /accesshub reload  - 重载 common.toml 配置 (不重启 HTTP, 仅刷新内存缓存的配置值)
 *   /accesshub help    - 显示帮助 (默认子命令)
 */
public final class AccessHubCommand {

    /** 默认权限等级: OP level 2, 跟原版管理命令一致. */
    public static final int PERMISSION_LEVEL = 2;

    private AccessHubCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AccessHubMod mod) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
                Commands.literal("accesshub")
                        .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                        .then(Commands.literal("status").executes(ctx -> doStatus(ctx, mod)))
                        .then(Commands.literal("reload").executes(ctx -> doReload(ctx, mod)))
                        .then(Commands.literal("whitelist")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> doWhitelistAdd(ctx, mod))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> doWhitelistRemove(ctx, mod))))
                                .then(Commands.literal("check")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> doWhitelistCheck(ctx, mod))))
                                .then(Commands.literal("list")
                                        .executes(ctx -> doWhitelistList(ctx, mod))))
                        .then(Commands.literal("help").executes(AccessHubCommand::doHelp))
                        .executes(AccessHubCommand::doHelp)
        );

        // alias 通过 redirect 共享根节点的所有子树
        dispatcher.register(Commands.literal("ca")
                .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                .redirect(root));
        dispatcher.register(Commands.literal("ahub")
                .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                .redirect(root));
    }

    private static int doStatus(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack source = ctx.getSource();
        AccessHubConfig config = mod.getConfig();
        HttpServer httpServer = mod.getHttpServer();

        source.sendSuccess(() -> headerLine("AccessHub 状态"), false);

        if (config == null) {
            source.sendSuccess(() -> Component.literal("  配置未加载 (mod 启动失败)")
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }

        boolean httpRunning = httpServer != null && httpServer.isRunning();
        source.sendSuccess(() -> kv("HTTP 服务", httpRunning
                        ? Component.literal("运行中 :" + config.getHttpPort()).withStyle(ChatFormatting.GREEN)
                        : Component.literal("未运行").withStyle(ChatFormatting.RED)),
                false);
        source.sendSuccess(() -> kv("白名单功能",
                        config.isWhitelistEnabled()
                                ? Component.literal("启用").withStyle(ChatFormatting.GREEN)
                                : Component.literal("禁用").withStyle(ChatFormatting.YELLOW)),
                false);
        source.sendSuccess(() -> kv("严格模式",
                        config.isWhitelistStrictMode()
                                ? Component.literal("开 (查询失败踢人)").withStyle(ChatFormatting.YELLOW)
                                : Component.literal("关 (查询失败放行)").withStyle(ChatFormatting.GRAY)),
                false);

        return httpRunning ? 1 : 0;
    }

    private static int doReload(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack source = ctx.getSource();
        AccessHubConfig config = mod.getConfig();
        if (config == null) {
            source.sendFailure(Component.literal("配置未加载, 无法重载 (mod 启动失败)"));
            return 0;
        }
        try {
            config.reload();
            source.sendSuccess(() -> Component.literal("配置已重载: common.toml")
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("配置重载失败: " + e.getMessage())
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    // ==================== 白名单子命令 ====================
    // WhitelistManager 操作返回 CompletableFuture, 异步完成后需回服务器主线程发包 (reply)。

    private static int doWhitelistAdd(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        WhitelistManager wm = mod.getWhitelistManager();
        if (wm == null) {
            src.sendFailure(Component.literal("白名单系统未就绪 (mod 启动失败)"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        // UUID 留空, 由玩家首次登录时 PlayerLoggedInEvent 补全
        wm.addPlayerByNameOnly(name, src.getTextName(), "CONSOLE", WhitelistEntry.Source.ADMIN)
                .thenAccept(ok -> reply(src, ok
                        ? Component.literal("已添加 " + name + " 到白名单 (UUID 待首次登录补全)").withStyle(ChatFormatting.GREEN)
                        : Component.literal("添加失败: " + name + " 可能已在白名单中").withStyle(ChatFormatting.RED)))
                .exceptionally(t -> {
                    reply(src, Component.literal("添加异常: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    private static int doWhitelistRemove(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        WhitelistManager wm = mod.getWhitelistManager();
        if (wm == null) {
            src.sendFailure(Component.literal("白名单系统未就绪 (mod 启动失败)"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        wm.removePlayerByName(name)
                .thenAccept(ok -> reply(src, ok
                        ? Component.literal("已从白名单移除 " + name).withStyle(ChatFormatting.GREEN)
                        : Component.literal("移除失败: " + name + " 不在白名单中").withStyle(ChatFormatting.RED)))
                .exceptionally(t -> {
                    reply(src, Component.literal("移除异常: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    private static int doWhitelistCheck(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        WhitelistManager wm = mod.getWhitelistManager();
        if (wm == null) {
            src.sendFailure(Component.literal("白名单系统未就绪 (mod 启动失败)"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        wm.isPlayerWhitelistedByName(name)
                .thenAccept(in -> reply(src, in
                        ? Component.literal(name + " 在白名单中").withStyle(ChatFormatting.GREEN)
                        : Component.literal(name + " 不在白名单中").withStyle(ChatFormatting.YELLOW)))
                .exceptionally(t -> {
                    reply(src, Component.literal("查询异常: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    private static int doWhitelistList(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        CommandSourceStack src = ctx.getSource();
        WhitelistManager wm = mod.getWhitelistManager();
        if (wm == null) {
            src.sendFailure(Component.literal("白名单系统未就绪 (mod 启动失败)"));
            return 0;
        }
        // 取前 20 条, 按添加时间降序; 命令行只展示前 10 条 + 余量提示, 完整列表走 HTTP API/面板
        wm.getWhitelistPaginated(1, 20, null, null, null, "added_at", "DESC", null, null)
                .thenAccept(result -> {
                    StringBuilder sb = new StringBuilder("§6=== 白名单 (共 " + result.getTotal() + " 人) ===");
                    int count = 0;
                    for (WhitelistEntry entry : result.getItems()) {
                        count++;
                        sb.append("\n§e").append(count).append(". §f").append(entry.getName());
                        String uuid = entry.getUuid();
                        sb.append(uuid == null || uuid.isEmpty() ? " §8(UUID 待补全)" : " §8" + uuid);
                        if (count >= 10) {
                            long rest = result.getTotal() - 10;
                            if (rest > 0) {
                                sb.append("\n§7... 还有 ").append(rest).append(" 人, 完整列表见后台/API");
                            }
                            break;
                        }
                    }
                    if (count == 0) {
                        sb.append("\n§7白名单为空");
                    }
                    reply(src, Component.literal(sb.toString()));
                })
                .exceptionally(t -> {
                    reply(src, Component.literal("获取白名单失败: " + t.getMessage()).withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    /** 异步回调结果回服务器主线程发送给命令源 (sendSuccess 须在主线程调用)。 */
    private static void reply(CommandSourceStack src, Component message) {
        MinecraftServer server = src.getServer();
        server.execute(() -> src.sendSuccess(() -> message, false));
    }

    private static int doHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> headerLine("AccessHub 命令"), false);
        source.sendSuccess(() -> usage("/accesshub status", "显示运行状态"), false);
        source.sendSuccess(() -> usage("/accesshub reload", "重载 common.toml"), false);
        source.sendSuccess(() -> usage("/accesshub whitelist add <名字>", "加白名单 (UUID 登录补全)"), false);
        source.sendSuccess(() -> usage("/accesshub whitelist remove <名字>", "移除白名单"), false);
        source.sendSuccess(() -> usage("/accesshub whitelist check <名字>", "检查是否在白名单"), false);
        source.sendSuccess(() -> usage("/accesshub whitelist list", "列出白名单 (前 10)"), false);
        source.sendSuccess(() -> usage("/accesshub help", "显示此帮助"), false);
        source.sendSuccess(() -> Component.literal("别名: /ca /ahub").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static MutableComponent headerLine(String title) {
        return Component.literal("=== ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(title).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" ===").withStyle(ChatFormatting.GRAY));
    }

    private static MutableComponent kv(String key, Component value) {
        return Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY).append(value);
    }

    private static MutableComponent usage(String command, String desc) {
        return Component.literal("  " + command).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - " + desc).withStyle(ChatFormatting.GRAY));
    }
}
