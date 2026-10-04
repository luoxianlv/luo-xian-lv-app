package app.luoxianlv.hot.contract;

import android.accessibilityservice.GestureDescription;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每个业务会话独占的系统能力；会话退役后不能继续发手势、截图或修改前台状态。 */
public interface AccessibilityBinding {
  boolean current();

  boolean gesture(GestureDescription description, GestureCallback callback);

  void screenshot(int displayId, ScreenshotCallback callback);

  void foreground(boolean enabled);

  /** 健康观察只累计实际演奏；保持前台服务或显示浮窗不代表正在使用模块。 */
  default void usage(boolean playing) {}

  /** 本代异步官方资源/业务错误通过受控宿主恢复；不能遗失或直接当作健康。 */
  default void contentFailed(Throwable failure) { throw new IllegalStateException("异步业务失败", failure); }

  interface GestureCallback {
    void completed(boolean success);
  }

  interface ScreenshotCallback {
    void success(Frame frame);

    void failure(int code);
  }

  /** 成功交付后由接收方关闭；旧会话的迟到截图由宿主直接释放。 */
  final class Frame implements AutoCloseable {
    public final HardwareBuffer buffer;
    public final ColorSpace colorSpace;
    private final AtomicBoolean closed = new AtomicBoolean();

    public Frame(HardwareBuffer buffer, ColorSpace colorSpace) {
      this.buffer = java.util.Objects.requireNonNull(buffer);
      this.colorSpace = colorSpace;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) buffer.close();
    }
  }
}
