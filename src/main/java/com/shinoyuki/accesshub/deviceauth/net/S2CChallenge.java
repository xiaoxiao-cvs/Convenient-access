package com.shinoyuki.accesshub.deviceauth.net;

import java.util.function.Supplier;

import com.shinoyuki.accesshub.client.deviceauth.ClientDeviceAuth;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * 服务端 -> 客户端: 免密挑战. phase=AUTH (登录) 或 ENROLL (登记).
 * 客户端处理仅经 DistExecutor 在客户端 dist 触达 ClientDeviceAuth (专用服务器永不加载该类)。
 */
public final class S2CChallenge {

    private final String phase;
    private final byte[] nonce;
    private final String serverInstanceId;

    public S2CChallenge(String phase, byte[] nonce, String serverInstanceId) {
        this.phase = phase;
        this.nonce = nonce;
        this.serverInstanceId = serverInstanceId;
    }

    public String phase() { return phase; }
    public byte[] nonce() { return nonce; }
    public String serverInstanceId() { return serverInstanceId; }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(phase, 16);
        buf.writeByteArray(nonce);
        buf.writeUtf(serverInstanceId, 64);
    }

    public static S2CChallenge decode(FriendlyByteBuf buf) {
        String phase = buf.readUtf(16);
        byte[] nonce = buf.readByteArray(64);
        String serverId = buf.readUtf(64);
        return new S2CChallenge(phase, nonce, serverId);
    }

    public static void handle(S2CChallenge msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        // 双 Supplier: 外层仅在 Dist.CLIENT 被调用, 故 ClientDeviceAuth 在专用服务器永不链接/加载
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientDeviceAuth.onChallenge(msg)));
        ctx.setPacketHandled(true);
    }
}
