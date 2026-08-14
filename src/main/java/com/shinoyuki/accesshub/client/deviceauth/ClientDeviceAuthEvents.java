package com.shinoyuki.accesshub.client.deviceauth;

import com.shinoyuki.accesshub.AccessHubMod;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端免密事件挂钩 (仅 Dist.CLIENT 加载)。
 *
 * Forge 依据注解里的 Dist 决定是否加载本类, 专用服务器永不触达它以及它引用的任何客户端类。
 *
 * LoggingIn 在 ClientPacketListener.handleLogin 里本地玩家创建、resetPos 之后触发, 是"客户端确实
 * 进入 PLAY 且能处理挑战"的最早可靠时刻 —— 在此发 hello, 服务端才据此下发挑战。
 */
@Mod.EventBusSubscriber(modid = AccessHubMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientDeviceAuthEvents {

    private ClientDeviceAuthEvents() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // 用事件自带的 Connection 而非 Minecraft.getInstance().getConnection(), 免受全局状态时序影响
        ClientDeviceAuth.sendHello(event.getConnection());
    }
}
