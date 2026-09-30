package app.luoxianlv.hot;

import java.io.File;
import java.util.*;

/** 只持有路径与内容身份，不捕获业务对象；文件租约和对象库准备锁共同保护清理边界。 */
final class ContentLeases {
  private record Key(String root, String snapshot, String runtime) {}

  private static final Map<Key, Integer> pins = new HashMap<>();

  static synchronized AutoCloseable pin(File root, String snapshot, String runtime) {
    Key key = new Key(root.getPath(), snapshot, runtime);
    pins.merge(key, 1, Integer::sum);
    return new AutoCloseable() {
      private boolean closed;

      public void close() {
        synchronized (ContentLeases.class) {
          if (closed) return;
          closed = true;
          int count = pins.get(key);
          if (count == 1) pins.remove(key);
          else pins.put(key, count - 1);
        }
      }
    };
  }

  static synchronized Set<String> snapshots(File root) {
    var result = new HashSet<String>();
    for (Key key : pins.keySet())
      if (key.root.equals(root.getPath()) && !key.snapshot.isEmpty()) result.add(key.snapshot);
    return result;
  }

  static synchronized Set<String> runtimes(File root) {
    var result = new HashSet<String>();
    for (Key key : pins.keySet())
      if (key.root.equals(root.getPath()) && !key.runtime.isEmpty()) result.add(key.runtime);
    return result;
  }
}
