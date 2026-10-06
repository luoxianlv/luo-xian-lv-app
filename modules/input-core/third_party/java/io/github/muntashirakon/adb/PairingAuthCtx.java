// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import javax.security.auth.Destroyable;

import io.github.muntashirakon.crypto.spake2.Spake2Context;
import io.github.muntashirakon.crypto.spake2.Spake2Role;

@RequiresApi(Build.VERSION_CODES.GINGERBREAD)
class PairingAuthCtx implements Destroyable {
    // The following values are taken from the following source and are subjected to change
    // https://github.com/aosp-mirror/platform_system_core/blob/android-11.0.0_r1/adb/pairing_auth/pairing_auth.cpp
    private static final byte[] CLIENT_NAME = StringCompat.getBytes("adb pair client\u0000", "UTF-8");
    private static final byte[] SERVER_NAME = StringCompat.getBytes("adb pair server\u0000", "UTF-8");

    // The following values are taken from the following source and are subjected to change
    // https://github.com/aosp-mirror/platform_system_core/blob/android-11.0.0_r1/adb/pairing_auth/aes_128_gcm.cpp
    private static final byte[] INFO = StringCompat.getBytes("adb pairing_auth aes-128-gcm key", "UTF-8");
    private static final int HKDF_KEY_LENGTH = 128 / 8;
    public static final int GCM_IV_LENGTH = 12; // in bytes

    private final byte[] mMsg;
    private final Spake2Context mSpake2Ctx;
    private final byte[] mSecretKey = new byte[HKDF_KEY_LENGTH];
    private long mDecIv = 0;
    private long mEncIv = 0;
    private boolean mIsDestroyed = false;

    @Nullable
    public static PairingAuthCtx createAlice(byte[] password) {
        Spake2Context spake25519 = new Spake2Context(Spake2Role.Alice, CLIENT_NAME, SERVER_NAME);
        try {
            return new PairingAuthCtx(spake25519, password);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    @VisibleForTesting
    @Nullable
    public static PairingAuthCtx createBob(byte[] password) {
        Spake2Context spake25519 = new Spake2Context(Spake2Role.Bob, SERVER_NAME, CLIENT_NAME);
        try {
            return new PairingAuthCtx(spake25519, password);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    private PairingAuthCtx(Spake2Context spake25519, byte[] password)
            throws IllegalArgumentException, IllegalStateException {
        mSpake2Ctx = spake25519;
        mMsg = mSpake2Ctx.generateMessage(password);
    }

    public byte[] getMsg() {
        return mMsg;
    }

    public synchronized boolean initCipher(byte[] theirMsg) throws IllegalArgumentException, IllegalStateException {
        if (mIsDestroyed) return false;
        byte[] keyMaterial = mSpake2Ctx.processMessage(theirMsg);
        if (keyMaterial == null) return false;
        byte[] key = deriveKey(keyMaterial, INFO);
        System.arraycopy(key, 0, mSecretKey, 0, mSecretKey.length);
        Arrays.fill(key, (byte) 0);
        Arrays.fill(keyMaterial, (byte) 0);
        return true;
    }

    @Nullable
    public synchronized byte[] encrypt(@NonNull byte[] in) {
        return encryptDecrypt(true, in, ByteBuffer.allocate(GCM_IV_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(mEncIv++).array());
    }

    @Nullable
    public synchronized byte[] decrypt(@NonNull byte[] in) {
        return encryptDecrypt(false, in, ByteBuffer.allocate(GCM_IV_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(mDecIv++).array());
    }

    @Override
    public boolean isDestroyed() {
        return mIsDestroyed;
    }

    @Override
    public synchronized void destroy() {
        if (mIsDestroyed) return;
        mIsDestroyed = true;
        Arrays.fill(mSecretKey, (byte) 0);
        mSpake2Ctx.destroy();
    }

    @Nullable
    private byte[] encryptDecrypt(boolean forEncryption, @NonNull byte[] in, @NonNull byte[] iv) {
        if (mIsDestroyed) return null;
        return aesGcm(forEncryption, mSecretKey, iv, in);
    }

    static byte[] aesGcm(boolean forEncryption, byte[] key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(forEncryption ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    // RFC 5869 HKDF-SHA256，ADB 只取首个扩展块的 16 字节；由测试向量验证。
    static byte[] deriveKey(byte[] input, byte[] info) {
        return hkdf(input, new byte[32], info, HKDF_KEY_LENGTH);
    }

    static byte[] hkdf(byte[] input, byte[] salt, byte[] info, int length) {
        if (length < 0 || length > 255 * 32) throw new IllegalArgumentException("HKDF 长度无效");
        byte[] prk = null;
        byte[] previous = new byte[0];
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            prk = mac.doFinal(input);
            byte[] output = new byte[length];
            int offset = 0;
            for (int counter = 1; offset < length; counter++) {
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(previous);
                mac.update(info);
                mac.update((byte) counter);
                byte[] block = mac.doFinal();
                Arrays.fill(previous, (byte) 0);
                previous = block;
                int copied = Math.min(block.length, length - offset);
                System.arraycopy(block, 0, output, offset, copied);
                offset += copied;
            }
            return output;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("系统缺少 HMAC-SHA256", e);
        } finally {
            if (prk != null) Arrays.fill(prk, (byte) 0);
            Arrays.fill(previous, (byte) 0);
        }
    }
}
