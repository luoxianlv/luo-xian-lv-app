package app.luoxianlv.input;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** 只允许当前启动在期限内交付一次；取消后的迟到助手不能重新连接。 */
final class HelperBootstrapTicket {
  final String nonce;
  final long deadline;
  private final AtomicBoolean used = new AtomicBoolean();
  private final AtomicBoolean revoked = new AtomicBoolean();

  HelperBootstrapTicket(long now, long timeout) {
    if (now < 0 || timeout <= 0) throw new IllegalArgumentException("启动期限无效");
    deadline = Math.addExact(now, timeout);
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    StringBuilder value = new StringBuilder(64);
    for (byte b : bytes) value.append(Character.forDigit((b & 255) >> 4, 16))
        .append(Character.forDigit(b & 15, 16));
    nonce = value.toString();
  }

  boolean accept(String candidate, long now) {
    return !revoked.get() && now < deadline && candidate != null && candidate.length() == 64
        && MessageDigest.isEqual(nonce.getBytes(StandardCharsets.US_ASCII),
            candidate.getBytes(StandardCharsets.US_ASCII)) && used.compareAndSet(false, true);
  }

  boolean expired(long now) { return !used.get() && now >= deadline; }
  boolean revoked() { return revoked.get(); }
  void revoke() { revoked.set(true); }
}
