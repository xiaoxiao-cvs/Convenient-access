package com.shinoyuki.accesshub.client.deviceauth;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.deviceauth.DeviceCrypto;
import com.shinoyuki.accesshub.deviceauth.net.AuthChannel;
import com.shinoyuki.accesshub.deviceauth.net.C2SResponse;
import com.shinoyuki.accesshub.deviceauth.net.S2CChallenge;

import net.minecraft.client.Minecraft;

/**
 * 客户端免密握手 (仅客户端 dist, 经 DistExecutor 触达; 专用服务器永不加载本类)。
 *
 * 收到挑战: AUTH -> 取本机私钥签名回传; ENROLL -> 本地生成密钥对、DPAPI 封存、签名回传公钥。
 * 任何取不到/解不开私钥 -> 静默不响应, 玩家走 /login 密码兜底。
 */
public final class ClientDeviceAuth {

    private static final Logger logger = LoggerFactory.getLogger(ClientDeviceAuth.class);

    private ClientDeviceAuth() {}

    public static void onChallenge(S2CChallenge msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
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
            return; // 无密钥/解不开 -> 静默, 走密码
        }
        DeviceKeyStore.Loaded loaded = opt.get();
        if (!loaded.username.equalsIgnoreCase(username)) {
            return; // 本机密钥不属于当前账号
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
