package app.luoxianlv.hot.contract;

import java.util.HashSet;
import java.util.Set;

/** 仅保存基础字符串，不捕获业务对象；进程迁移和全局 SDK 初始化不能随业务热更重复。 */
public final class ProcessOnce {
  private static final Set<String> DONE = new HashSet<>();

  private ProcessOnce() {}

  public static synchronized boolean claim(String key) {
    if (key == null || !key.matches("[a-z][a-z0-9._-]{0,95}"))
      throw new IllegalArgumentException("进程任务身份无效");
    return DONE.add(key);
  }
}
