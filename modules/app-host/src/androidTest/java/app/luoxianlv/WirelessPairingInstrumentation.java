package app.luoxianlv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 真正加载 Android SPAKE2 JNI，覆盖协议双端、篡改、重放及重复清理。 */
public final class WirelessPairingInstrumentation extends Instrumentation {
  private Class<?> auth;
  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      auth = Class.forName("io.github.muntashirakon.adb.PairingAuthCtx", true, getTargetContext().getClassLoader());
      Set<Integer> messages = new HashSet<>();
      for (int i = 0; i < 12; i++) {
        Object[] peers = peers("123456-tls-exporter", "123456-tls-exporter");
        byte[] hello = (byte[]) invoke(peers[0], "getMsg");
        check(messages.add(Arrays.hashCode(hello)), "临时密钥消息重复");
        establish(peers);
        byte[] plaintext = "luoxianlv-peer-info".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = (byte[]) invoke(peers[0], "encrypt", byte[].class, plaintext);
        check(Arrays.equals(plaintext, (byte[]) invoke(peers[1], "decrypt", byte[].class, encrypted)), "配对双方认证失败");
        check(invoke(peers[1], "decrypt", byte[].class, encrypted) == null, "重放未被拒绝");
        destroy(peers);
      }
      Object[] mismatch = peers("123456", "654321");
      establish(mismatch);
      byte[] wrong = (byte[]) invoke(mismatch[0], "encrypt", byte[].class, new byte[]{1, 2, 3});
      check(invoke(mismatch[1], "decrypt", byte[].class, wrong) == null, "错误密码未被拒绝");
      destroy(mismatch);

      Object[] altered = peers("654321", "654321");
      establish(altered);
      byte[] damaged = (byte[]) invoke(altered[0], "encrypt", byte[].class, new byte[]{4, 5, 6});
      damaged[damaged.length - 1] ^= 1;
      check(invoke(altered[1], "decrypt", byte[].class, damaged) == null, "篡改未被拒绝");
      destroy(altered);

      Object[] malformed = peers("654321", "654321");
      boolean rejected = false;
      try { invoke(malformed[0], "initCipher", byte[].class, new byte[1]); }
      catch (InvocationTargetException failure) { rejected = failure.getCause() instanceof IllegalStateException; }
      check(rejected, "无效曲线消息未被拒绝");
      destroy(malformed);
      result.putString("stream", "无线配对协议：12 次双端认证、随机性、错误密码、篡改、重放及幂等清理通过。\n");
      finish(0, result);
    } catch (Throwable failure) {
      result.putString("stream", "无线配对协议验证失败：" + failure + "\n");
      finish(1, result);
    }
  }

  private Object[] peers(String clientPassword, String serverPassword) throws Exception {
    Method client = auth.getDeclaredMethod("createAlice", byte[].class);
    Method server = auth.getDeclaredMethod("createBob", byte[].class);
    client.setAccessible(true); server.setAccessible(true);
    Object a = client.invoke(null, clientPassword.getBytes(StandardCharsets.UTF_8));
    Object b = server.invoke(null, serverPassword.getBytes(StandardCharsets.UTF_8));
    check(a != null && b != null, "无法创建原生 SPAKE2 双端");
    return new Object[]{a, b};
  }

  private void establish(Object[] pair) throws Exception {
    byte[] a = (byte[]) invoke(pair[0], "getMsg"), b = (byte[]) invoke(pair[1], "getMsg");
    check(Boolean.TRUE.equals(invoke(pair[0], "initCipher", byte[].class, b)), "客户端密钥协商失败");
    check(Boolean.TRUE.equals(invoke(pair[1], "initCipher", byte[].class, a)), "服务端密钥协商失败");
  }

  private void destroy(Object[] pair) throws Exception {
    for (Object peer : pair) { invoke(peer, "destroy"); invoke(peer, "destroy"); }
  }

  private Object invoke(Object target, String name, Object... arguments) throws Exception {
    Class<?>[] types = arguments.length == 0 ? new Class<?>[0] : new Class<?>[]{(Class<?>) arguments[0]};
    Method method = auth.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(target, arguments.length == 0 ? new Object[0] : new Object[]{arguments[1]});
  }

  private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
