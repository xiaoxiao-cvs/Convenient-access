package com.shinoyuki.accesshub.command;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shinoyuki.accesshub.AccessHubMod;
import com.shinoyuki.accesshub.auth.PlayerAuthService;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.deviceauth.DeviceAuthServer;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 玩家自助免密登记命令: /enroll [注册码].
 *
 * 授权二选一: 已认证会话 (本连接已 /login 或已免密) 直接登记, 无需码;
 * 未认证时凭管理员签发的绑定注册码登记 (复用 player_registration_codes 校验)。
 * 不加 .requires 权限; 依赖经 AccessHubMod 惰性解析, 与 AuthCommand 同模式。
 */
public final class EnrollCommand {

    private EnrollCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AccessHubMod mod) {
        dispatcher.register(Commands.literal("enroll")
                .executes(ctx -> doEnroll(ctx, mod, null))
                .then(Commands.argument("code", StringArgumentType.word())
                        .executes(ctx -> doEnroll(ctx, mod, StringArgumentType.getString(ctx, "code")))));
    }

    private static int doEnroll(CommandContext<CommandSourceStack> ctx, AccessHubMod mod, String code) {
        PlayerAuthService auth = mod.getPlayerAuthService();
        AccessHubConfig config = mod.getConfig();
        DeviceAuthServer server = mod.getDeviceAuthServer();
        if (auth == null || config == null || server == null || !config.isPlayerAuthEnabled()) {
            ctx.getSource().sendFailure(Component.literal("认证未启用或未就绪"));
            return 0;
        }
        if (!config.isDeviceAuthEnabled()) {
            ctx.getSource().sendFailure(Component.literal("免密登录未启用"));
            return 0;
        }
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("该命令只能由玩家执行"));
            return 0;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        MinecraftServer mcServer = player.getServer();
        if (mcServer == null) {
            return 0;
        }

        // 已认证会话: 直接登记本设备, 无需注册码 (一生只在注册时输一次密码, 此后免密)
        if (auth.isAuthed(uuid)) {
            server.beginEnroll(player, -1L);
            return 1;
        }
        if (code == null) {
            // 注册码在 /register 环节已临时停用, 但换机登记仍可凭管理员发的码 (/accesshub auth gencode);
            // 这条面向玩家的提示不再主动宣传码, 免得又要解释一遍"码是什么"。恢复时补回 "或新机用注册码: /enroll <注册码>"
            player.sendSystemMessage(colored(
                    "请先 /login 登录后再 /enroll (换新设备且忘记密码时联系管理员)", ChatFormatting.RED));
            return 0;
        }
        // 未认证 + 带码: 异步校验 (账号须已注册, 密码=恢复锚) -> 回主线程发起登记 (成功后消费码)
        CompletableFuture.supplyAsync(() -> auth.validateEnrollWithCode(name, code))
                .thenAccept(cc -> mcServer.execute(() -> {
                    if (!cc.isValid()) {
                        player.sendSystemMessage(colored(cc.getMessage(), ChatFormatting.RED));
                    } else {
                        server.beginEnroll(player, cc.getCodeId());
                    }
                }))
                .exceptionally(t -> {
                    mcServer.execute(() -> player.sendSystemMessage(
                            colored("登记异常, 请稍后重试", ChatFormatting.RED)));
                    return null;
                });
        return 1;
    }

    private static Component colored(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }
}
