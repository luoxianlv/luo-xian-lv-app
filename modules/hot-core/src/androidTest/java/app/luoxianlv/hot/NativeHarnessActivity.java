package app.luoxianlv.hot;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import app.luoxianlv.hot.contract.NativePage;

/** 独立验收宿主，只包含 Java/Android 契约；不会替换用户主 APP。 */
public final class NativeHarnessActivity extends Activity {
  FrameLayout container;
  NativePage page;
  PageSwapHost swap;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    container = new FrameLayout(this);
    setContentView(container);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (page != null) page.lifecycle(NativePage.RESUMED);
    if (swap != null) swap.lifecycle(NativePage.RESUMED);
  }

  @Override
  protected void onPause() {
    if (page != null) page.lifecycle(NativePage.STARTED);
    if (swap != null) swap.lifecycle(NativePage.STARTED);
    super.onPause();
  }

  @Override
  protected void onStop() {
    if (page != null) page.lifecycle(NativePage.CREATED);
    if (swap != null) swap.lifecycle(NativePage.CREATED);
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    if (page != null) {
      page.close();
      page = null;
    }
    if (swap != null) {
      swap.close();
      swap = null;
    }
    super.onDestroy();
  }
}
