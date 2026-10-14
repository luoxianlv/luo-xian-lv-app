package app.luoxianlv.hot.contract;

import android.os.Bundle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 宿主共享连接；业务只持有可关闭的监听和本次命令句柄。 */
public final class SharedRealtime {
  private static final AtomicReference<Bridge> CURRENT = new AtomicReference<>();
  private SharedRealtime() {}
  public static Bridge current() { return CURRENT.get(); }
  public static AutoCloseable connect(Bridge bridge) {
    CURRENT.set(Objects.requireNonNull(bridge));
    return () -> CURRENT.compareAndSet(bridge, null);
  }
  public interface Listener {
    void changed(Bundle state);
    void message(String topic, String type, String json);
  }
  public interface Completion {
    void complete(boolean success, String json);
  }
  public interface Execution { void execute(long localStartUs); }
  /** 宿主只交付短期授权结果；关闭句柄取消对应请求，不泄露长期安装凭据。 */
  public interface Authorization {
    void complete(String secret, int status, long retryAfterMillis);
  }
  public interface Host {
    AutoCloseable ticket(Authorization callback);
    AutoCloseable accountGrant(String token, String epoch, Authorization callback);
    void hotChanged(boolean subscribed, String revision, String trustVersion);
    void usageChanged();
  }
  /** 由宿主持有；接口不跨加载器传递 Kotlin、Socket 或协程类型。 */
  public interface Engine extends Bridge, AutoCloseable {
    void usage(boolean active);
    boolean performing();
  }
  public interface Bridge {
    /** 返回内存状态，不在页面读取时进行网络或 Binder 调用。 */
    Bundle state();
    Bundle clock();
    void prepareRun(String runId);
    void stopScheduled();
    AutoCloseable schedule(Bundle plan, Execution execute, Completion completion);
    /** 主线程状态与合奏控制；普通通知通过 observeEvents 订阅。 */
    AutoCloseable observe(Listener listener);
    /** 后台普通通知：状态、catalog.changed 和 business.changed；不传合奏控制或查询响应。
     * 回调只更新内存、不能阻塞；关闭租约后不再交付。旧运行时保留原监听作为兼容入口。 */
    default AutoCloseable observeEvents(Listener listener) { return observe(listener); }
    /** 同一命令 ID 只能对应同一内容；取消监听不能冒充取消已接受的远端操作。 */
    AutoCloseable send(String id, String topic, String type, String json, Completion completion);
    void account(String accessToken);
    /** 房间启用快速校时；后台保持连接需显式前台服务，准备/演奏期间阻止普通热更切换。 */
    void room(boolean active, boolean performing);
  }
}
