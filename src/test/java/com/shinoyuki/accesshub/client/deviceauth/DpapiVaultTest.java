package com.shinoyuki.accesshub.client.deviceauth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.sun.jna.Platform;

/**
 * DPAPI 加解密往返实测. 仅 Windows 跑 (非 Windows assumeTrue 跳过, 不算失败)。
 * 在 Windows 构建机上真实调用 Crypt32, 验证 protect/unprotect 往返还原 + 篡改即失败。
 * 这是免密私钥保护的底座; DeviceKeyStore 依赖它的正确性。
 */
class DpapiVaultTest {

    @Test
    void protectUnprotectRoundTripOnWindows() {
        assumeTrue(Platform.isWindows(), "DPAPI 仅 Windows; 非 Windows 跳过");
        byte[] secret = "ed25519-pkcs8-private-key-bytes!".getBytes(StandardCharsets.UTF_8);
        byte[] blob = DpapiVault.protect(secret);
        assertFalse(Arrays.equals(secret, blob), "密文应不等于明文");
        byte[] back = DpapiVault.unprotect(blob);
        assertArrayEquals(secret, back, "DPAPI 往返应还原原字节");
    }

    @Test
    void tamperedBlobFailsToUnprotect() {
        assumeTrue(Platform.isWindows(), "DPAPI 仅 Windows; 非 Windows 跳过");
        byte[] secret = "another-secret-payload-1234567890".getBytes(StandardCharsets.UTF_8);
        byte[] blob = DpapiVault.protect(secret);
        blob[blob.length - 1] ^= 0x01; // 篡改一字节
        assertThrows(RuntimeException.class, () -> DpapiVault.unprotect(blob),
                "篡改后的密文应解密失败 (完整性保护)");
    }
}
