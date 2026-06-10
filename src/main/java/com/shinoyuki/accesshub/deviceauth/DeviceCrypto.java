package com.shinoyuki.accesshub.deviceauth;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

/**
 * Ed25519 设备密钥工具 (双端共用, 纯 JDK 17, 零第三方依赖, 不引 BouncyCastle).
 *
 * 公钥 X.509 编码、私钥 PKCS#8 编码; 签名/验签用 java.security "Ed25519" (JDK 15+ 原生)。
 * 客户端持私钥签名, 服务端用数据库内绑定的公钥验签 —— 私钥永不过网。
 *
 * 待签名原文域分隔 (challengePayload): DOMAIN || phase || serverInstanceId || nonce ||
 * username || sha256(pubkey), 各字段带 4 字节长度前缀防拼接歧义。域+阶段标签使登记签名不能
 * 当登录签名重放, serverInstanceId 使跨服/跨启动不可迁移, nonce 新鲜性防重放。
 */
public final class DeviceCrypto {

    public static final String ALGORITHM = "Ed25519";
    /** 签名域标签: 版本化, 跨 mod/跨用途隔离。 */
    public static final String DOMAIN = "shinoyuki_accesshub:deviceauth:v1";
    public static final String PHASE_AUTH = "AUTH";
    public static final String PHASE_ENROLL = "ENROLL";

    private DeviceCrypto() {}

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 Ed25519 provider (需 Java 15+)", e);
        }
    }

    /** 公钥 -> X.509 DER 字节。 */
    public static byte[] encodePublic(PublicKey key) {
        return key.getEncoded();
    }

    /** 私钥 -> PKCS#8 DER 字节 (客户端用 DPAPI 封存这串)。 */
    public static byte[] encodePrivate(PrivateKey key) {
        return key.getEncoded();
    }

    public static PublicKey decodePublic(byte[] x509) {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(x509));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("无效的 Ed25519 公钥编码", e);
        }
    }

    public static PrivateKey decodePrivate(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("无效的 Ed25519 私钥编码", e);
        }
    }

    public static byte[] sign(PrivateKey priv, byte[] data) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(priv);
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 签名失败", e);
        }
    }

    /** 验签. 任何异常 (坏签名/坏公钥) 一律视为不通过 (fail-closed)。 */
    public static boolean verify(PublicKey pub, byte[] data, byte[] sig) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(pub);
            s.update(data);
            return s.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * 构造待签名原文. 客户端与服务端必须用完全一致的字段拼接, 否则验签必不过。
     * username 在此统一小写规范化, 与按名查询口径一致。
     */
    public static byte[] challengePayload(String phase, String serverInstanceId,
                                          byte[] nonce, String username, byte[] publicKeyX509) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, DOMAIN.getBytes(StandardCharsets.UTF_8));
        writeField(out, phase.getBytes(StandardCharsets.UTF_8));
        writeField(out, serverInstanceId.getBytes(StandardCharsets.UTF_8));
        writeField(out, nonce);
        writeField(out, username.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        writeField(out, sha256(publicKeyX509));
        return out.toByteArray();
    }

    public static String toBase64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    public static byte[] fromBase64(String s) {
        return Base64.getDecoder().decode(s);
    }

    public static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void writeField(ByteArrayOutputStream out, byte[] b) {
        int len = b.length;
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(b, 0, b.length);
    }
}
