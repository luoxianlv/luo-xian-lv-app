package app.luoxianlv.hot.contract;

/** 跨业务加载器保护同一个日志目录，只持有基础对象，不保存业务回调。 */
public final class ProcessLocks {
  private static final java.util.Map<String, Object> LOCKS = new java.util.HashMap<>();

  private ProcessLocks() {}

  public static synchronized Object monitor(String key) {
    if (key == null || !key.matches("[a-z][a-z0-9._-]{0,95}"))
      throw new IllegalArgumentException("进程锁身份无效");
    return LOCKS.computeIfAbsent(key, ignored -> new Object());
  }
}
