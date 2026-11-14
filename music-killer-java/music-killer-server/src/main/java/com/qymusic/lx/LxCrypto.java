package com.qymusic.lx;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 供 lx 音源脚本调用的加密工具（对应 lx.utils.crypto / lx.utils.buffer）。
 */
public final class LxCrypto {

    private LxCrypto() {
    }

    public static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("md5 失败: " + e.getMessage(), e);
        }
    }

    public static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** base64 -> JSON 数组字符串（脚本里 new Uint8Array(JSON.parse(...))） */
    public static String base64ToJsonArray(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        StringBuilder sb = new StringBuilder(bytes.length * 4 + 2);
        sb.append('[');
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(bytes[i] & 0xFF);
        }
        return sb.append(']').toString();
    }

    public static String bufferToString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * AES 加密。cipher 为 CBC（PKCS7）或 ECB（无填充），密钥固定 128 位，与 lx 协议一致。
     */
    public static String aesEncrypt(String dataBase64, String keyBase64, String ivBase64, String cipherMode) {
        try {
            byte[] data = Base64.getDecoder().decode(dataBase64);
            byte[] key = Base64.getDecoder().decode(keyBase64);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");

            Cipher cipher;
            if ("CBC".equalsIgnoreCase(cipherMode)) {
                byte[] iv = ivBase64 == null || ivBase64.isEmpty()
                        ? new byte[16]
                        : Base64.getDecoder().decode(ivBase64);
                cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
            } else {
                cipher = Cipher.getInstance("AES/ECB/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, keySpec);
                // ECB 无填充要求数据长度是 16 的整数倍，不足补 0（与原实现一致）
                if (data.length % 16 != 0) {
                    byte[] padded = new byte[(data.length / 16 + 1) * 16];
                    System.arraycopy(data, 0, padded, 0, data.length);
                    data = padded;
                }
            }
            return Base64.getEncoder().encodeToString(cipher.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException("aes 加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * RSA 无填充加密：按公钥模长左补 0，与 lx 的 RSA/ECB/NoPadding 行为一致。
     */
    public static String rsaEncrypt(String dataBase64, String publicKeyBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(publicKeyBase64.replaceAll("\\s+", ""));
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            int keySize = ((java.security.interfaces.RSAPublicKey) publicKey).getModulus().bitLength() / 8;

            byte[] data = Base64.getDecoder().decode(dataBase64);
            byte[] padded = new byte[keySize];
            System.arraycopy(data, 0, padded, keySize - data.length, data.length);

            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (Exception e) {
            throw new IllegalStateException("rsa 加密失败: " + e.getMessage(), e);
        }
    }
}
