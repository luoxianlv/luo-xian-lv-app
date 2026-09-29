package app.luoxianlv.hot;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import app.luoxianlv.hot.contract.NativePage;
import java.util.ArrayList;
import java.util.List;

/** 故障注入只存在于独立测试 APK，不进入正式宿主。 */
public final class NativeLifecycleHarness extends NativeHostActivity {
  static String mode = "healthy";
  final List<Integer> lifecycleEvents = new ArrayList<>();
  final List<String> warnings = new ArrayList<>();
  int closes;

  @Override
  protected NativePage createPage() {
    return new NativePage() {
      @Override
      public View create(
          Context context, Bundle state, Bundle hostState, Events events, Ready ready) {
        if (mode.equals("create")) throw new IllegalStateException("测试创建故障");
        TextView text = new TextView(context);
        text.setText("业务已显示");
        ready.ready();
        return text;
      }

      @Override
      public Bundle save() {
        Bundle state = new Bundle();
        if (mode.equals("save")) state.putParcelable("invalid", new android.content.Intent());
        else state.putInt("position", 42);
        return state;
      }

      @Override
      public void updateHostState(Bundle state) {}

      @Override
      public void lifecycle(int state) {
        lifecycleEvents.add(state);
        if (state == RESUMED && mode.equals("resume")) throw new IllegalStateException("测试恢复故障");
      }

      @Override
      public void hostWarning(String code, Throwable error) {
        warnings.add(code);
      }

      @Override
      public void close() {
        closes++;
        if (mode.equals("close")) throw new IllegalStateException("测试关闭故障");
      }
    };
  }
}
