package app.luoxianlv.hot;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.luoxianlv.hot.contract.HostActions;
import app.luoxianlv.hot.contract.NativePage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** 仅依赖 Android/Java 的窗口宿主；页面、Compose 和业务生命周期由可替换入口拥有。 */
public abstract class NativeHostActivity extends Activity {
  // 多个窗口共用串行提交线程；首次有更新事务才创建线程，不阻塞首屏。
  private static final Executor COMMITS =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "lxhot-commit");
            thread.setDaemon(true);
            return thread;
          });
  private NativePage page;
  private HostResults results;
  private boolean visible;
  private Object backCallback;

  protected abstract NativePage createPage() throws Exception;

  protected final PageSwapHost pageHost() {
    StrictJson.require(page instanceof PageSession, "原生页面会话尚未就绪");
    return ((PageSession) page).pages();
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    else
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
    results = new HostResults(this, null);
    try {
      results = new HostResults(this, state == null ? null : state.getBundle("native.results"));
      NativePage baseline = createPage();
      page =
          new PageSession(
              baseline,
              getApplicationInfo().sourceDir + "#" + baseline.getClass().getName(),
              COMMITS,
              (code, failure) -> {
                if (code.equals("baseline_recovery_failed"))
                  getWindow()
                      .getDecorView()
                      .post(
                          () -> {
                            if (page != null && !isDestroyed()) pageFailed(failure);
                          });
                else if (failure != null) warning(code, failure);
              });
      page.attachHost(actions(page));
      Bundle restored = state == null ? null : state.getBundle("native.page");
      setContentView(
          page.create(
              this,
              restored == null ? new Bundle() : PageState.copy(restored),
              new Bundle(),
              (event, payload) -> {},
              () -> {}));
      page.lifecycle(NativePage.CREATED);
      page.newIntent(getIntent());
    } catch (Throwable failure) {
      pageFailed(failure);
    }
    if (Build.VERSION.SDK_INT >= 33) {
      backCallback = Api33.register(this, this::dispatchBack);
    }
  }

  private HostActions actions(NativePage owner) {
    return new HostActions() {
      private boolean active() {
        return page == owner && visible && !isFinishing();
      }

      @Override
      public void launch(String key, Intent intent, Bundle options) {
        if (active()) results.launch(key, intent, options);
      }

      @Override
      public void permissions(String key, String[] permissions) {
        if (active()) results.permissions(key, permissions);
      }

      @Override
      public void resultReady(String key) {
        if (page == owner)
          getWindow()
              .getDecorView()
              .post(
                  () -> {
                    if (page == owner) forward(() -> results.deliver(key, owner));
                  });
      }

      @Override
      public boolean hasPendingResults() {
        return results.busy();
      }

      @Override
      public void finish() {
        if (active()) finishAffinity();
      }
    };
  }

  @Override
  protected void onStart() {
    super.onStart();
    visible = true;
    forward(() -> page.lifecycle(NativePage.STARTED));
  }

  @Override
  protected void onResume() {
    super.onResume();
    forward(() -> page.lifecycle(NativePage.RESUMED));
    forward(() -> results.deliverAll(page));
  }

  @Override
  protected void onPause() {
    forward(() -> page.lifecycle(NativePage.STARTED));
    super.onPause();
  }

  @Override
  protected void onStop() {
    visible = false;
    forward(() -> page.lifecycle(NativePage.CREATED));
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    if (Build.VERSION.SDK_INT >= 33 && backCallback != null) Api33.unregister(this, backCallback);
    if (page != null) {
      try {
        page.close();
      } catch (Throwable failure) {
        warning("page_close_failed", failure);
      }
      page = null;
    }
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (page != null) {
      try {
        out.putBundle("native.page", PageState.copy(page.save()));
      } catch (Throwable failure) {
        warning("page_state_failed", failure);
      }
    }
    out.putBundle("native.results", results.save());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    forward(() -> page.newIntent(intent));
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    forward(() -> page.configurationChanged(configuration));
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    try {
      results.accept(requestCode, resultCode, data, page);
    } catch (Throwable failure) {
      pageFailed(failure);
    }
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(code, permissions, grants);
    try {
      results.acceptPermissions(code, permissions, grants, page);
    } catch (Throwable failure) {
      pageFailed(failure);
    }
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    forward(() -> page.windowTouch());
    try {
      return super.dispatchTouchEvent(event);
    } catch (IllegalStateException error) {
      if (!HostActions.isStaleWindowOperation(error)) throw error;
      warning("stale_freeform", error);
      return false;
    }
  }

  @Override
  public void onBackPressed() {
    dispatchBack();
  }

  private void dispatchBack() {
    if (page == null) {
      super.onBackPressed();
      return;
    }
    forward(
        () -> {
          if (!page.back()) super.onBackPressed();
        });
  }

  private void forward(Runnable action) {
    if (page == null) return;
    try {
      action.run();
    } catch (Throwable failure) {
      pageFailed(failure);
    }
  }

  private void warning(String code, Throwable failure) {
    android.util.Log.w("原生宿主", "页面事件：" + code + "（" + failure.getClass().getName() + "）");
    if (page != null) {
      try {
        page.hostWarning(code, failure);
      } catch (Throwable ignored) {
        /* 日志回调不能打断宿主恢复。 */
      }
    }
  }

  private void pageFailed(Throwable failure) {
    warning("page_initialization_failed", failure);
    if (page != null) {
      try {
        page.close();
      } catch (Throwable ignored) {
      }
      page = null;
    }
    LinearLayout recovery = new LinearLayout(this);
    recovery.setOrientation(LinearLayout.VERTICAL);
    recovery.setPadding(40, 80, 40, 40);
    TextView message = new TextView(this);
    message.setText("页面暂时无法打开，请重新进入应用。");
    message.setTextSize(18);
    recovery.addView(message);
    Button retry = new Button(this);
    retry.setText("重试");
    retry.setOnClickListener(view -> recreate());
    recovery.addView(retry);
    Button exit = new Button(this);
    exit.setText("返回");
    exit.setOnClickListener(view -> finish());
    recovery.addView(exit);
    setContentView(recovery);
  }

  /** 新系统类型隔离到按版本加载的类，避免旧设备在解析宿主字段时解析不存在的 API。 */
  private static final class Api33 {
    static Object register(Activity activity, Runnable action) {
      android.window.OnBackInvokedCallback callback = action::run;
      activity
          .getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
      return callback;
    }

    static void unregister(Activity activity, Object callback) {
      activity
          .getOnBackInvokedDispatcher()
          .unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) callback);
    }
  }
}
