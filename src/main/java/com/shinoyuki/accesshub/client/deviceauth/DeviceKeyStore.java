package com.shinoyuki.accesshub.client.deviceauth;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.deviceauth.DeviceCrypto;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 客户端设备密钥文件存储 (仅客户端 dist). 按 serverInstanceId 分文件, 支持单机多服各自独立密钥。
 *
 * Windows: 私钥经 DPAPI 封存; 非 Windows: 明文存储 + 警告 (安全性依赖密码登录)。
 * DPAPI 解封失败 (换机/重装/换号/损坏) 视为无密钥 -> 静默回退密码登录。
 * 私钥落 .minecraft 下客户端目录, 与服务端数据目录严格分离。
 */
final class DeviceKeyStore {

    private static final Logger logger = LoggerFactory.getLogger(DeviceKeyStore.class);

    private DeviceKeyStore() {}

    private static Path file(String serverId) {
        String safe = serverId.replaceAll("[^A-Za-z0-9_-]", "_");
        return FMLPaths.GAMEDIR.get().resolve("shinoyuki_accesshub").resolve("device-" + safe + ".key");
    }

    static void save(String serverId, String username, byte[] publicKeyX509, byte[] privatePkcs8) throws IOException {
        boolean protect = DpapiVault.available();
        byte[] payload = protect ? DpapiVault.protect(privatePkcs8) : privatePkcs8;

        Properties props = new Properties();
        props.setProperty("username", username);
        props.setProperty("publicKey", DeviceCrypto.toBase64(publicKeyX509));
        props.setProperty("protected", Boolean.toString(protect));
        props.setProperty("payload", DeviceCrypto.toBase64(payload));

        Path f = file(serverId);
        Files.createDirectories(f.getParent());
        try (Writer w = Files.newBufferedWriter(f)) {
            props.store(w, "AccessHub Ed25519 device key. private key "
                    + (protect ? "DPAPI-protected (Windows CurrentUser)" : "PLAINTEXT - no OS protection on this platform"));
        }
        if (!protect) {
            logger.warn("非 Windows 平台: 设备私钥以明文存储, 安全性依赖密码登录。文件: {}", f);
        }
    }

    static Optional<Loaded> load(String serverId) {
        Path f = file(serverId);
        if (!Files.exists(f)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(f)) {
            props.load(r);
        } catch (IOException e) {
            logger.warn("读取设备密钥失败: {}", f, e);
            return Optional.empty();
        }
        String username = props.getProperty("username");
        String pubB64 = props.getProperty("publicKey");
        boolean protectedFlag = Boolean.parseBoolean(props.getProperty("protected", "false"));
        String payloadB64 = props.getProperty("payload");
        if (username == null || pubB64 == null || payloadB64 == null) {
            return Optional.empty();
        }
        byte[] payload = DeviceCrypto.fromBase64(payloadB64);
        final byte[] priv;
        try {
            priv = protectedFlag ? DpapiVault.unprotect(payload) : payload;
        } catch (RuntimeException e) {
            // DPAPI 解密失败 (换机/重装/换号/损坏) -> 视为无密钥, 回退密码登录
            logger.info("设备私钥无法解封 (可能换机/重装 Windows), 回退密码登录: {}", f);
            return Optional.empty();
        }
        return Optional.of(new Loaded(username, DeviceCrypto.fromBase64(pubB64), priv));
    }

    static final class Loaded {
        final String username;
        final byte[] publicKeyX509;
        final byte[] privatePkcs8;

        Loaded(String username, byte[] publicKeyX509, byte[] privatePkcs8) {
            this.username = username;
            this.publicKeyX509 = publicKeyX509;
            this.privatePkcs8 = privatePkcs8;
        }
    }
}
