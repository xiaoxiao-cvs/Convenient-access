package com.shinoyuki.accesshub.client.deviceauth;

import java.nio.charset.StandardCharsets;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

/**
 * Windows DPAPI 私钥封装 (仅客户端 dist). 复用 MC 自带 JNA (compileOnly, 不打包)。
 *
 * 作用域 = CurrentUser (不传 LOCAL_MACHINE): 密文绑定当前 Windows 用户 + 本机, 拷到别的
 * 机器/账号无法解密 —— 堵死"拷 .key 文件冒名"失窃路径。固定 entropy 作深度防御 (非安全边界:
 * 盐在字节码里可逆向, 不防同用户恶意软件)。CRYPTPROTECT_UI_FORBIDDEN 禁任何弹窗。
 */
final class DpapiVault {

    private static final byte[] ENTROPY =
            "shinoyuki_accesshub:deviceauth:v1".getBytes(StandardCharsets.UTF_8);
    private static final int FLAGS = WinCrypt.CRYPTPROTECT_UI_FORBIDDEN;

    private DpapiVault() {}

    static boolean available() {
        return Platform.isWindows();
    }

    static byte[] protect(byte[] data) {
        // cryptProtectData(data, entropy, flags, description, prompt)
        return Crypt32Util.cryptProtectData(data, ENTROPY, FLAGS, "AccessHub Ed25519 device key", null);
    }

    static byte[] unprotect(byte[] blob) {
        // cryptUnprotectData(data, entropy, flags, prompt) —— 注意无 description 参
        return Crypt32Util.cryptUnprotectData(blob, ENTROPY, FLAGS, null);
    }
}
