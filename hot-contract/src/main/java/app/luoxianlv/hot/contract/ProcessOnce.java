package app.luoxianlv.hot.contract;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 仅保存基础字符串，不捕获业务对象；进程迁移和全局 SDK 初始化不能随业务热更重复。 */
public final class ProcessOnce {
  private static final Map<String, Long> DONE = new HashMap<>();
  private static final Set<String> RUNNING = new HashSet<>();

  private ProcessOnce() {}

  public static boolean claim(String key) {
    checkKey(key);
    synchronized (ProcessLocks.monitor(key)) {
      synchronized (ProcessOnce.class) {
        if (RUNNING.contains(key)) throw new IllegalStateException("进程任务不能重复进入");
        return DONE.putIfAbsent(key, System.currentTimeMillis()) == null;
      }
    }
  }

  /** 操作正常返回后才记为成功；异常允许重试，不在稳定层保留业务回调。 */
  public static long run(String key, Runnable operation) {
    checkKey(key);
    Objects.requireNonNull(operation, "进程任务不能为空");
    synchronized (ProcessLocks.monitor(key)) {
      synchronized (ProcessOnce.class) {
        Long completed = DONE.get(key);
        if (completed != null) return completed;
        if (!RUNNING.add(key)) throw new IllegalStateException("进程任务不能重复进入");
      }
      try {
        operation.run();
        long completed = System.currentTimeMillis();
        synchronized (ProcessOnce.class) {
          DONE.put(key, completed);
        }
        return completed;
      } finally {
        synchronized (ProcessOnce.class) {
          RUNNING.remove(key);
        }
      }
    }
  }

  /** 只共享成功时间；候选业务不能用此状态绕过自己的展示与隐私授权边界。 */
  public static synchronized Long completedAt(String key) {
    checkKey(key);
    return DONE.get(key);
  }

  private static void checkKey(String key) {
    if (key == null || !key.matches("[a-z][a-z0-9._-]{0,95}"))
      throw new IllegalArgumentException("进程任务身份无效");
  }
}
