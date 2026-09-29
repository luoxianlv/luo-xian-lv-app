package app.luoxianlv.hot.contract;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

/** 跨加载器只传 Android 基础类型；Compose、协程和 ViewModel 由共享运行时与业务持有。 */
public interface NativePage extends AutoCloseable {
  int CREATED = 1;
  int STARTED = 2;
  int RESUMED = 3;

  View create(Context context, Bundle state, Bundle hostState, Events events, Ready ready);

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

  interface Ready {
    void ready();
  }
}
