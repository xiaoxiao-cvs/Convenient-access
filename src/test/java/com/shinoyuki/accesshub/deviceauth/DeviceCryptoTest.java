package com.shinoyuki.accesshub.deviceauth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;

import org.junit.jupiter.api.Test;

/**
 * Ed25519 设备密钥工具断言测试 (纯 JDK, CI 全覆盖, 不依赖 MC/DPAPI/Windows).
 * 这是免密验签的密码学底座: 验签通过/篡改即拒/换钥即拒/编解码往返/签名原文确定性。
 * 删掉验签或域分隔逻辑测试必挂。
 */
class DeviceCryptoTest {

    private static final byte[] DATA = "challenge-data".getBytes(StandardCharsets.UTF_8);

    @Test
    void signVerifyRoundTrip() {
        KeyPair kp = DeviceCrypto.generateKeyPair();
        byte[] sig = DeviceCrypto.sign(kp.getPrivate(), DATA);
        assertTrue(DeviceCrypto.verify(kp.getPublic(), DATA, sig), "正确签名应验签通过");
    }

    @Test
    void tamperedDataOrSignatureFailsVerify() {
        KeyPair kp = DeviceCrypto.generateKeyPair();
        byte[] sig = DeviceCrypto.sign(kp.getPrivate(), DATA);

        byte[] otherData = "challenge-DATA".getBytes(StandardCharsets.UTF_8);
        assertFalse(DeviceCrypto.verify(kp.getPublic(), otherData, sig), "数据被改应验签失败");

        byte[] badSig = sig.clone();
        badSig[0] ^= 0x01;
        assertFalse(DeviceCrypto.verify(kp.getPublic(), DATA, badSig), "签名被改应验签失败");
    }

    @Test
    void wrongPublicKeyFailsVerify() {
        KeyPair a = DeviceCrypto.generateKeyPair();
        KeyPair b = DeviceCrypto.generateKeyPair();
        byte[] sig = DeviceCrypto.sign(a.getPrivate(), DATA);
        assertFalse(DeviceCrypto.verify(b.getPublic(), DATA, sig), "用别的公钥验签应失败 (冒名核心防线)");
    }

    @Test
    void publicKeyEncodeDecodeRoundTrip() {
        KeyPair kp = DeviceCrypto.generateKeyPair();
        byte[] x509 = DeviceCrypto.encodePublic(kp.getPublic());
        PublicKey restored = DeviceCrypto.decodePublic(x509);
        byte[] sig = DeviceCrypto.sign(kp.getPrivate(), DATA);
        assertTrue(DeviceCrypto.verify(restored, DATA, sig), "公钥经 X.509 往返后仍能验签 (服务端存/取公钥路径)");
        assertArrayEquals(x509, DeviceCrypto.encodePublic(restored), "往返编码应一致");
    }

    @Test
    void privateKeyEncodeDecodeRoundTrip() {
        KeyPair kp = DeviceCrypto.generateKeyPair();
        byte[] pkcs8 = DeviceCrypto.encodePrivate(kp.getPrivate());
        // 模拟客户端 DPAPI 解封后用 PKCS#8 还原私钥签名
        byte[] sig = DeviceCrypto.sign(DeviceCrypto.decodePrivate(pkcs8), DATA);
        assertTrue(DeviceCrypto.verify(kp.getPublic(), DATA, sig), "私钥经 PKCS#8 往返后仍能签出可验签名 (DPAPI 封存路径)");
    }

    @Test
    void challengePayloadIsDeterministicAndDomainSeparated() {
        byte[] nonce = new byte[]{1, 2, 3, 4};
        byte[] pub = DeviceCrypto.encodePublic(DeviceCrypto.generateKeyPair().getPublic());

        byte[] p1 = DeviceCrypto.challengePayload(DeviceCrypto.PHASE_AUTH, "srv1", nonce, "Alice", pub);
        byte[] p2 = DeviceCrypto.challengePayload(DeviceCrypto.PHASE_AUTH, "srv1", nonce, "alice", pub);
        assertArrayEquals(p1, p2, "username 大小写应被规范化为同一原文");

        byte[] enroll = DeviceCrypto.challengePayload(DeviceCrypto.PHASE_ENROLL, "srv1", nonce, "Alice", pub);
        assertFalse(java.util.Arrays.equals(p1, enroll), "AUTH 与 ENROLL 原文必须不同 (防跨阶段重放)");

        byte[] otherServer = DeviceCrypto.challengePayload(DeviceCrypto.PHASE_AUTH, "srv2", nonce, "Alice", pub);
        assertFalse(java.util.Arrays.equals(p1, otherServer), "不同 serverInstanceId 原文必须不同 (防跨服重放)");

        byte[] otherNonce = DeviceCrypto.challengePayload(DeviceCrypto.PHASE_AUTH, "srv1", new byte[]{9}, "Alice", pub);
        assertFalse(java.util.Arrays.equals(p1, otherNonce), "不同 nonce 原文必须不同 (防重放)");
    }

    @Test
    void base64RoundTrip() {
        byte[] raw = DeviceCrypto.encodePublic(DeviceCrypto.generateKeyPair().getPublic());
        assertArrayEquals(raw, DeviceCrypto.fromBase64(DeviceCrypto.toBase64(raw)));
    }
}
