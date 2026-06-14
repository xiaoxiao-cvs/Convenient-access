package com.shinoyuki.accesshub.deviceauth.net;

import com.shinoyuki.accesshub.AccessHubMod;
import com.shinoyuki.accesshub.deviceauth.DeviceAuthServer;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * DeviceAuth 的 PLAY 阶段自定义通道. 可选通道: 谓词须同时放行 ABSENT 与 ACCEPTVANILLA,
 * 才能让 "装了 Forge 但没装本 mod" 的客户端 (握手时该通道上报 ABSENT) 与纯 vanilla 客户端
 * (上报 ACCEPTVANILLA) 都不被 "Mismatched Channel List" 踢。仅放行 ACCEPTVANILLA 会漏掉
 * ABSENT 这条路径 -> Forge 无本 mod 客户端仍被拒。用 acceptMissingOr 一次覆盖三种取值
 * (协议版本 / ABSENT / ACCEPTVANILLA)。
 *
 * 通道对象在类加载期 (static) 构造; 消息注册 (messageBuilder.add) 须在 FMLCommonSetupEvent
 * 调 register()。服务端验签器引用在装配期 setServer 设入, 包处理器惰性读取。
 */
public final class AuthChannel {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AccessHubMod.MOD_ID, "deviceauth"),
            () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));

    private static volatile DeviceAuthServer server;
    private static int packetId = 0;

    private AuthChannel() {}

    public static void setServer(DeviceAuthServer s) { server = s; }
    public static DeviceAuthServer server() { return server; }

    /** 在 FMLCommonSetupEvent (MOD bus) 调用. 两端注册顺序必须一致, 否则包 id 错位。 */
    public static void register() {
        CHANNEL.messageBuilder(S2CChallenge.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CChallenge::encode)
                .decoder(S2CChallenge::decode)
                .consumerMainThread(S2CChallenge::handle)
                .add();
        CHANNEL.messageBuilder(C2SResponse.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SResponse::encode)
                .decoder(C2SResponse::decode)
                .consumerMainThread(C2SResponse::handle)
                .add();
    }

    public static void sendTo(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /** 该玩家客户端是否装了本 mod (注册了本通道)。 */
    public static boolean clientHasMod(ServerPlayer player) {
        Connection conn = player.connection.connection;
        return CHANNEL.isRemotePresent(conn);
    }
}
