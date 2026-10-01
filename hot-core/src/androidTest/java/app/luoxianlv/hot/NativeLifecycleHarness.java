package app.luoxianlv.hot;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import app.luoxianlv.hot.contract.HostActions;
import app.luoxianlv.hot.contract.NativePage;
import java.util.ArrayList;
import java.util.List;

/** 故障注入只存在于独立测试 APK，不进入正式宿主。 */
public final class NativeLifecycleHarness extends NativeHostActivity {
  static String mode = "healthy";
  static volatile NativeLifecycleHarness latest;
  final List<Integer> lifecycleEvents = new ArrayList<>();
  final List<String> warnings = new ArrayList<>();
  int closes;
  int creates;
  int preparationCloses;
  Runnable prepared;
  HostActions actions;
  int results;
  String resultKey;
  android.content.Intent resultData;

  @Override
  protected AutoCloseable whenPageReady(Runnable callback) {
    latest = this;
    if (!mode.startsWith("delayed")) return super.whenPageReady(callback);
    prepared = callback;
    return () -> preparationCloses++;
  }

  @Override
  protected NativePage createPage() {
    latest = this;
    creates++;
    String failureMode = mode;
    return new NativePage() {
      @Override
      public void attachHost(HostActions host) {
        actions = host;
      }

      @Override
      public boolean result(String key, int code, android.content.Intent data) {
        results++;
        resultKey = key;
        resultData = data;
        return true;
      }

      @Override
      public View create(
          Context context, Bundle state, Bundle hostState, Events events, Ready ready) {
        if (failureMode.endsWith("create")) throw new IllegalStateException("测试创建故障");
        TextView text = new TextView(context);
        text.setText("业务已显示");
        ready.ready();
        return text;
      }

      @Override
      public Bundle save() {
        Bundle state = new Bundle();
        if (failureMode.equals("save"))
          state.putParcelable("invalid", new android.content.Intent());
        else state.putInt("position", 42);
        return state;
      }

      @Override
      public void updateHostState(Bundle state) {}

      @Override
      public void lifecycle(int state) {
        lifecycleEvents.add(state);
        if (state == RESUMED && failureMode.equals("resume"))
          throw new IllegalStateException("测试恢复故障");
      }

      @Override
      public void hostWarning(String code, Throwable error) {
        warnings.add(code);
      }

      @Override
      public void close() {
        closes++;
        if (failureMode.equals("close")) throw new IllegalStateException("测试关闭故障");
      }
    };
  }
}
