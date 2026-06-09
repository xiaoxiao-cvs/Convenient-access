package com.shinoyuki.accesshub.command;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shinoyuki.accesshub.AccessHubMod;
import com.shinoyuki.accesshub.auth.PlayerAuthService;
import com.shinoyuki.accesshub.config.AccessHubConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 玩家自助认证命令: /register /login /changepassword (含短别名 /reg /l).
 *
 * 与 AccessHubCommand (管理命令, 要求 OP) 解耦: 这些命令面向普通玩家, 不加 .requires 权限。
 * 放行名单由 com.shinoyuki.accesshub.event.AuthCommandNames 统一定义, 与未认证命令拦截一致。
 *
 * 依赖经 AccessHubMod 惰性解析 (getPlayerAuthService): RegisterCommandsEvent 在服务器
 * bootstrap (initialize 之前) 即触发, 注册期服务可能尚未就绪, 故在执行期再取, 与 AccessHubCommand 一致。
 *
 * DB 调用 (注册/校验) 阻塞且不可在服务器主线程执行, 故业务逻辑放 supplyAsync 后回主线程发包 + 标记已认证。
 */
public final class AuthCommand {

    private AuthCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AccessHubMod mod) {
        // /register <password> <confirm> <code> (注册码绑定用户名, 离线模式防冒名抢注; 无码不可注册)
        dispatcher.register(Commands.literal("register")
                .then(Commands.argument("password", StringArgumentType.word())
                        .then(Commands.argument("confirm", StringArgumentType.word())
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(ctx -> doRegister(ctx, mod))))));
        dispatcher.register(Commands.literal("reg")
                .then(Commands.argument("password", StringArgumentType.word())
                        .then(Commands.argument("confirm", StringArgumentType.word())
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(ctx -> doRegister(ctx, mod))))));

        // /login <password>
        dispatcher.register(Commands.literal("login")
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(ctx -> doLogin(ctx, mod))));
        dispatcher.register(Commands.literal("l")
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(ctx -> doLogin(ctx, mod))));

        // /changepassword <old> <new>
        dispatcher.register(Commands.literal("changepassword")
                .then(Commands.argument("old", StringArgumentType.word())
                        .then(Commands.argument("new", StringArgumentType.word())
                                .executes(ctx -> doChangePassword(ctx, mod)))));
    }

    /**
     * 解析公共前置: 认证启用 + 服务就绪 + 命令源是玩家.
     * 返回 null 表示前置不满足 (已向命令源发失败提示), 调用方应直接返回 0。
     */
    private static ServerPlayer resolvePlayer(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        PlayerAuthService auth = mod.getPlayerAuthService();
        AccessHubConfig config = mod.getConfig();
        if (auth == null || config == null || !config.isPlayerAuthEnabled()) {
            ctx.getSource().sendFailure(Component.literal("玩家认证未启用或未就绪"));
            return null;
        }
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("该命令只能由玩家执行"));
            return null;
        }
        return player;
    }

    private static int doRegister(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        ServerPlayer player = resolvePlayer(ctx, mod);
        if (player == null) {
            return 0;
        }
        PlayerAuthService auth = mod.getPlayerAuthService();
        String password = StringArgumentType.getString(ctx, "password");
        String confirm = StringArgumentType.getString(ctx, "confirm");
        String code = StringArgumentType.getString(ctx, "code");
        String name = player.getGameProfile().getName();
        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0;
        }
        if (!password.equals(confirm)) {
            player.sendSystemMessage(colored("两次输入的密码不一致", ChatFormatting.RED));
            return 0;
        }

        CompletableFuture.supplyAsync(() -> auth.register(name, password, code))
                .thenAccept(result -> server.execute(() ->
                        player.sendSystemMessage(colored(result.getMessage(),
                                result.isSuccess() ? ChatFormatting.GREEN : ChatFormatting.RED))))
                .exceptionally(t -> {
                    server.execute(() -> player.sendSystemMessage(
                            colored("注册异常, 请稍后重试", ChatFormatting.RED)));
                    return null;
                });
        return 1;
    }

    private static int doLogin(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        ServerPlayer player = resolvePlayer(ctx, mod);
        if (player == null) {
            return 0;
        }
        PlayerAuthService auth = mod.getPlayerAuthService();
        AccessHubConfig config = mod.getConfig();
        if (auth.isAuthed(player.getUUID())) {
            player.sendSystemMessage(colored("你已登录", ChatFormatting.YELLOW));
            return 0;
        }
        String password = StringArgumentType.getString(ctx, "password");
        String name = player.getGameProfile().getName();
        UUID uuid = player.getUUID();
        String ip = formatIp(player.connection.connection.getRemoteAddress());
        // 下限钳制到 1: 防止管理员把 max-attempts 误填 0/负数导致首次密码错误即踢死合法玩家
        final int max = Math.max(1, config.getPlayerAuthMaxAttempts());
        // 入口同步短路: 已达上限直接踢, 不再派发 verify, 把并发在途 bcrypt 钳制在上限内 (主线程串行)
        if (auth.getSessionFailureCount(uuid) >= max) {
            player.connection.disconnect(Component.literal(
                    "§c登录失败次数过多, 已断开连接, 请重新进入再试"));
            return 0;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0;
        }

        CompletableFuture.supplyAsync(() -> auth.verify(name, password, ip))
                .thenAccept(result -> server.execute(() -> {
                    if (result.isSuccess()) {
                        // 标记已认证必须在主线程, 解除限制: tick 冻结自然停止, 撤销无敌
                        auth.markAuthed(uuid);
                        player.setInvulnerable(false);
                        player.sendSystemMessage(colored(result.getMessage(), ChatFormatting.GREEN));
                    } else if (result.isPasswordMismatch()) {
                        // 仅密码错误才累计会话失败; 达上限即踢下线 (重连重置, 不持久锁号)
                        int fails = auth.recordSessionFailure(uuid);
                        if (fails >= max) {
                            player.connection.disconnect(Component.literal(
                                    "§c登录失败次数过多, 已断开连接, 请重新进入再试"));
                        } else {
                            player.sendSystemMessage(colored(
                                    "密码错误, 还可尝试 " + (max - fails) + " 次", ChatFormatting.RED));
                        }
                    } else {
                        // 未注册 / 系统繁忙等非密码错误: 不计入踢出阈值
                        player.sendSystemMessage(colored(result.getMessage(), ChatFormatting.RED));
                    }
                }))
                .exceptionally(t -> {
                    server.execute(() -> player.sendSystemMessage(
                            colored("登录异常, 请稍后重试", ChatFormatting.RED)));
                    return null;
                });
        return 1;
    }

    private static int doChangePassword(CommandContext<CommandSourceStack> ctx, AccessHubMod mod) {
        ServerPlayer player = resolvePlayer(ctx, mod);
        if (player == null) {
            return 0;
        }
        PlayerAuthService auth = mod.getPlayerAuthService();
        String oldPwd = StringArgumentType.getString(ctx, "old");
        String newPwd = StringArgumentType.getString(ctx, "new");
        String name = player.getGameProfile().getName();
        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0;
        }

        CompletableFuture.supplyAsync(() -> auth.changePassword(name, oldPwd, newPwd))
                .thenAccept(result -> server.execute(() ->
                        player.sendSystemMessage(colored(result.getMessage(),
                                result.isSuccess() ? ChatFormatting.GREEN : ChatFormatting.RED))))
                .exceptionally(t -> {
                    server.execute(() -> player.sendSystemMessage(
                            colored("改密异常, 请稍后重试", ChatFormatting.RED)));
                    return null;
                });
        return 1;
    }

    private static Component colored(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }

    private static String formatIp(SocketAddress addr) {
        if (addr instanceof InetSocketAddress inet) {
            return inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
        }
        return addr != null ? addr.toString() : "unknown";
    }
}
