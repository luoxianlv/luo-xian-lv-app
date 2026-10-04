package app.luoxianlv.hot.contract;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;

/** 跨加载器只传 Android 基础类型；Compose、协程和 ViewModel 由共享运行时与业务持有。 */
public interface NativePage extends AutoCloseable {
  int CREATED = 1;
  int STARTED = 2;
  int RESUMED = 3;

  View create(Context context, Bundle state, Bundle hostState, Events events, Ready ready);

  default void attachHost(HostActions host) {}

  default void newIntent(Intent intent) {}

  default boolean result(String key, int resultCode, Intent data) {
    return false;
  }

  default boolean back() {
    return false;
  }

  default void windowTouch() {}

  default void hostWarning(String code, Throwable error) {}

  default void configurationChanged(Configuration configuration) {}

  /** 系统窗口开始退出时立即停用音频和手势，不等窗口动画结束。 */
  default void finishing() {}

  /** 仅同内容、同加载器的 Activity 重建使用；热更不得借此传递旧业务对象。 */
  default Retained retain() {
    return null;
  }

  default void restoreRetained(Retained state) {
    state.close();
  }

  Bundle save();

  /** 滚动惯性、编辑提交等业务事务结束后才能导出并替换状态。 */
  default boolean canReplace() {
    return true;
  }

  void updateHostState(Bundle state);

  void lifecycle(int state);

  @Override
  void close();

  interface Events {
    void emit(String event, Bundle payload);
  }

  interface Retained extends AutoCloseable {
    @Override
    void close();
  }

  interface Ready {
    void ready();

    default void failed(Throwable failure) {}
  }
}
