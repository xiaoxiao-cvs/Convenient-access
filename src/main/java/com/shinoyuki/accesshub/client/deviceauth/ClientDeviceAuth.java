package com.shinoyuki.accesshub.client.deviceauth;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.deviceauth.DeviceCrypto;
import com.shinoyuki.accesshub.deviceauth.net.AuthChannel;
import com.shinoyuki.accesshub.deviceauth.net.C2SHello;
import com.shinoyuki.accesshub.deviceauth.net.C2SResponse;
import com.shinoyuki.accesshub.deviceauth.net.S2CChallenge;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraftforge.network.NetworkDirection;

/**
 * 客户端免密握手 (仅客户端 dist, 经 DistExecutor 触达; 专用服务器永不加载本类)。
 *
 * 收到挑战: AUTH -> 取本机私钥签名回传; ENROLL -> 本地生成密钥对、DPAPI 封存、签名回传公钥。
 * 任何取不到/解不开私钥 -> 静默不响应, 玩家走 /login 密码兜底。
 */
public final class ClientDeviceAuth {

    private static final Logger logger = LoggerFactory.getLogger(ClientDeviceAuth.class);

    private ClientDeviceAuth() {}

    /**
     * 进入 PLAY 且本地玩家已就绪后主动宣告, 由服务端据此下发挑战 (见 C2SHello 说明)。
     *
     * 不看 isRemotePresent: 该判定不可靠正是本包存在的理由。服务端没装本 mod 时, 这个未知通道的包
     * 会被直接丢弃, 无任何副作用。
     */
    public static void sendHello(Connection connection) {
        if (connection == null) {
            return;
        }
        AuthChannel.CHANNEL.sendTo(C2SHello.INSTANCE, connection, NetworkDirection.PLAY_TO_SERVER);
    }

    public static void onChallenge(S2CChallenge msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            // hello 之后才会收到挑战, 正常不该出现; 真出现也不必自救 —— 服务端会在挑战超时后重发
            logger.warn("免密挑战到达时本地玩家尚未创建, 本条丢弃, 等服务端重发");
            return;
        }
        String username = mc.player.getGameProfile().getName();
        String serverId = msg.serverInstanceId();
        if (DeviceCrypto.PHASE_ENROLL.equals(msg.phase())) {
            doEnroll(serverId, username, msg.nonce());
        } else {
            doAuth(serverId, username, msg.nonce());
        }
    }

    private static void doAuth(String serverId, String username, byte[] nonce) {
        Optional<DeviceKeyStore.Loaded> opt = DeviceKeyStore.load(serverId);
        if (opt.isEmpty()) {
            // 对玩家仍是静默回退密码, 但日志必须留痕: 否则"服务端发了挑战、客户端没动静"在两端都查不出原因
            logger.info("本机没有该服务器 (serverId={}) 的设备密钥或已解不开, 回退密码登录 (/login 后可 /enroll 重新登记)",
                    serverId);
            return;
        }
        DeviceKeyStore.Loaded loaded = opt.get();
        if (!loaded.username.equalsIgnoreCase(username)) {
            logger.info("本机设备密钥属于账号 {}, 与当前账号 {} 不符, 回退密码登录", loaded.username, username);
            return;
        }
        try {
            byte[] payload = DeviceCrypto.challengePayload(
                    DeviceCrypto.PHASE_AUTH, serverId, nonce, username, loaded.publicKeyX509);
            PrivateKey priv = DeviceCrypto.decodePrivate(loaded.privatePkcs8);
            byte[] sig = DeviceCrypto.sign(priv, payload);
            AuthChannel.CHANNEL.sendToServer(new C2SResponse(DeviceCrypto.PHASE_AUTH, new byte[0], sig));
        } catch (RuntimeException e) {
            logger.warn("免密签名失败, 回退密码", e);
        }
    }

    private static void doEnroll(String serverId, String username, byte[] nonce) {
        try {
            KeyPair kp = DeviceCrypto.generateKeyPair();
            byte[] pub = DeviceCrypto.encodePublic(kp.getPublic());
            byte[] priv = DeviceCrypto.encodePrivate(kp.getPrivate());
            // 先存私钥 (DPAPI 封存): 存不下就不登记, 否则下次无法免密
            DeviceKeyStore.save(serverId, username, pub, priv);
            byte[] payload = DeviceCrypto.challengePayload(
                    DeviceCrypto.PHASE_ENROLL, serverId, nonce, username, pub);
            byte[] sig = DeviceCrypto.sign(kp.getPrivate(), payload);
            AuthChannel.CHANNEL.sendToServer(new C2SResponse(DeviceCrypto.PHASE_ENROLL, pub, sig));
        } catch (Exception e) {
            logger.warn("设备登记失败, 回退密码", e);
        }
    }
}
