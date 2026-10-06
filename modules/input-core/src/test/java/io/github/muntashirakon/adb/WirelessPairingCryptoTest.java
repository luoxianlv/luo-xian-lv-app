package io.github.muntashirakon.adb;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

/** 标准向量及真实 SPAKE2 双方测试，确保瘦依赖替换不改变 ADB 密码协议。 */
public class WirelessPairingCryptoTest {
  @Test public void hkdfRfc5869CaseOne() {
    byte[] input = new byte[22];
    Arrays.fill(input, (byte) 0x0b);
    assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
        PairingAuthCtx.hkdf(input, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42));
  }

  @Test public void hkdfRfc5869EmptySalt() {
    byte[] input = new byte[22];
    Arrays.fill(input, (byte) 0x0b);
    assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
        PairingAuthCtx.hkdf(input, new byte[0], new byte[0], 42));
  }

  @Test public void aesGcmNistVector() {
    byte[] expected = hex("0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf");
    byte[] key = new byte[16], nonce = new byte[12];
    assertArrayEquals(expected, PairingAuthCtx.aesGcm(true, key, nonce, new byte[16]));
    assertArrayEquals(new byte[16], PairingAuthCtx.aesGcm(false, key, nonce, expected));
    expected[expected.length - 1] ^= 1;
    assertNull(PairingAuthCtx.aesGcm(false, key, nonce, expected));
  }

  @Test(expected = IllegalArgumentException.class) public void hkdfRejectsLengthOverflow() {
    PairingAuthCtx.hkdf(new byte[1], new byte[1], new byte[1], 8161);
  }

  private static byte[] hex(String source) {
    byte[] bytes = new byte[source.length() / 2];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(source.substring(i * 2, i * 2 + 2), 16);
    return bytes;
  }
}
