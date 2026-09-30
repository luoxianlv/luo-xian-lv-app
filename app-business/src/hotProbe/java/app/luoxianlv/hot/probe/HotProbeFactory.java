package app.luoxianlv.hot.probe;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.luoxianlv.business.AppBusinessFactory;
import app.luoxianlv.hot.contract.*;

/** 仅显式 nativeBusinessProbe 的 Debug 业务 APK 包含；用于证明下载了基线中不存在的原生组件。 */
public final class HotProbeFactory implements BusinessFactory {
  private final BusinessFactory app = new AppBusinessFactory();

  @Override
  public NativePage page(String route) {
    NativePage delegate = app.page(route);
    return route.equals("main") ? new Main(delegate) : delegate;
  }

  @Override
  public NativePlaybackSession playback() {
    return app.playback();
  }

  @Override
  public ProcessHooks process(Context context) {
    return app.process(context);
  }

  @Override
  public ForegroundPolicy foreground(Context context) {
    return app.foreground(context);
  }

  public static final class NewNativeBadge extends TextView {
    public NewNativeBadge(Context context) {
      super(context);
      setText("原生热更验证");
      setTextColor(0xffebf4ff);
      setTextSize(12);
      setBackgroundColor(0xdd315070);
      int padding = Math.round(10 * getResources().getDisplayMetrics().density);
      setPadding(padding, padding / 2, padding, padding / 2);
    }
  }

  private record Main(NativePage delegate) implements NativePage {
    @Override
    public void attachHost(HostActions host) {
      delegate.attachHost(host);
    }

    @Override
    public View create(Context context, Bundle saved, Bundle state, Events events, Ready ready) {
      FrameLayout root = new FrameLayout(context);
      root.addView(
          delegate.create(context, saved, state, events, ready),
          new FrameLayout.LayoutParams(-1, -1));
      var badge = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
      badge.topMargin = Math.round(36 * context.getResources().getDisplayMetrics().density);
      root.addView(new NewNativeBadge(context), badge);
      return root;
    }

    @Override
    public Bundle save() {
      return delegate.save();
    }

    @Override
    public boolean canReplace() {
      return delegate.canReplace();
    }

    @Override
    public void updateHostState(Bundle state) {
      delegate.updateHostState(state);
    }

    @Override
    public void lifecycle(int state) {
      delegate.lifecycle(state);
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public void newIntent(Intent intent) {
      delegate.newIntent(intent);
    }

    @Override
    public boolean result(String key, int code, Intent intent) {
      return delegate.result(key, code, intent);
    }

    @Override
    public boolean back() {
      return delegate.back();
    }

    @Override
    public void windowTouch() {
      delegate.windowTouch();
    }

    @Override
    public void configurationChanged(Configuration value) {
      delegate.configurationChanged(value);
    }

    @Override
    public void finishing() {
      delegate.finishing();
    }

    @Override
    public void hostWarning(String code, Throwable error) {
      delegate.hostWarning(code, error);
    }

    @Override
    public Retained retain() {
      return delegate.retain();
    }

    @Override
    public void restoreRetained(Retained state) {
      delegate.restoreRetained(state);
    }
  }
}
