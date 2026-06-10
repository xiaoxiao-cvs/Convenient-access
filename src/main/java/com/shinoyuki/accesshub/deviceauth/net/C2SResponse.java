package com.shinoyuki.accesshub.deviceauth.net;

import java.util.function.Supplier;

import com.shinoyuki.accesshub.deviceauth.DeviceAuthServer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * 客户端 -> 服务端: 对挑战的 Ed25519 签名. AUTH 时 publicKey 为空 (服务端按名查库内公钥);
 * ENROLL 时 publicKey 为新生成的设备公钥 (服务端用它自验签做 PoP, 再入库)。
 */
public final class C2SResponse {

    private final String phase;
    private final byte[] publicKey;
    private final byte[] signature;

    public C2SResponse(String phase, byte[] publicKey, byte[] signature) {
        this.phase = phase;
        this.publicKey = publicKey;
        this.signature = signature;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(phase, 16);
        buf.writeByteArray(publicKey);
        buf.writeByteArray(signature);
    }

    public static C2SResponse decode(FriendlyByteBuf buf) {
        String phase = buf.readUtf(16);
        byte[] pub = buf.readByteArray(1024);
        byte[] sig = buf.readByteArray(1024);
        return new C2SResponse(phase, pub, sig);
    }

    public static void handle(C2SResponse msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender(); // 权威身份, 零信任客户端自报
            if (sender == null) {
                return;
            }
            DeviceAuthServer server = AuthChannel.server();
            if (server == null) {
                return; // 未就绪 -> 忽略
            }
            server.handleResponse(sender, msg.phase, msg.publicKey, msg.signature);
        });
        ctx.setPacketHandled(true);
    }
}
