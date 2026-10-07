package app.luoxianlv.input;

import java.io.IOException;

/** 单次截图独占管道；旧请求的超时或迟到返回不能关闭新请求。 */
final class ScreenshotResources implements AutoCloseable {
  private AutoCloseable current;
  private boolean closed;

  synchronized void track(AutoCloseable resource) throws IOException {
    if (resource == null) throw new IOException("截图没有返回管道");
    if (closed) { dispose(resource); throw new IOException("截图请求已结束"); }
    clear();
    current = resource;
  }

  synchronized void clear() {
    dispose(current);
    current = null;
  }

  @Override public synchronized void close() { closed = true; clear(); }

  private static void dispose(AutoCloseable resource) {
    if (resource != null) try { resource.close(); } catch (Exception ignored) { }
  }
}
