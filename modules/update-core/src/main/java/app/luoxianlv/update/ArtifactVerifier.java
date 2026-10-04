package app.luoxianlv.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ArtifactVerifier {
  private ArtifactVerifier() {}

  public static String sha256(File file, Cancellation cancellation, Progress progress)
      throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
    long completed = 0;
    byte[] buffer = new byte[65536];
    try (FileInputStream input = new FileInputStream(file)) {
      int count;
      while ((count = input.read(buffer)) != -1) {
        cancellation.check();
        digest.update(buffer, 0, count);
        completed += count;
        progress.onProgress("verifying", completed, file.length(), 0, "正在校验文件");
      }
    }
    cancellation.check();
    return hex(digest.digest());
  }

  public static void verify(File file, long size, String sha256, Cancellation cancellation)
      throws IOException {
    cancellation.check();
    if (!file.isFile() || file.length() != size) throw new IOException("文件大小不匹配");
    if (!sha256(file, cancellation, Progress.NONE).equals(sha256)) throw new IOException("文件摘要不匹配");
  }

  public static String hex(byte[] bytes) {
    char[] alphabet = "0123456789abcdef".toCharArray();
    char[] result = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      result[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
      result[i * 2 + 1] = alphabet[bytes[i] & 15];
    }
    return new String(result);
  }
}
