package app.luoxianlv.hot;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import app.luoxianlv.hot.contract.HostActions;
import app.luoxianlv.hot.contract.NativePage;
import java.util.concurrent.Executor;

/** Activity 的固定入口；系统回调与页面替换共用同一个会话，业务对象不存入宿主状态。 */
final class PageSession implements NativePage {
  private NativePage baseline;
  private final String identity;
  private Context initialContext;
  private final Executor worker;
  private final PageSwapHost.Listener listener;
  private HostActions platform;
  private PageSwapHost pages;
  private boolean closed;

  PageSession(
      NativePage baseline, String identity, Executor worker, PageSwapHost.Listener listener) {
    this(baseline, identity, worker, listener, null);
  }

  PageSession(
      NativePage baseline,
      String identity,
      Executor worker,
      PageSwapHost.Listener listener,
      Context initialContext) {
    this.baseline = baseline;
    this.identity = identity;
    this.worker = worker;
    this.listener = listener;
    this.initialContext = initialContext;
  }

  @Override
  public void attachHost(HostActions platform) {
    this.platform = platform;
  }

  @Override
  public NativePage.Retained retain() {
    return pages().retain();
  }

  @Override
  public void restoreRetained(NativePage.Retained retained) {
    StrictJson.require(pages == null && baseline != null, "重建状态只能在创建页面前移交");
    if (retained instanceof RetainedPage) ((RetainedPage) retained).restore(identity, baseline);
    else retained.close();
  }

  @Override
  public View create(Context context, Bundle state, Bundle hostState, Events events, Ready ready) {
    StrictJson.require(!closed && pages == null, "页面会话已经创建或关闭");
    pages = new PageSwapHost(context, null, worker, events, listener);
    pages.attachHost(platform);
    pages.updateHostState(hostState);
    NativePage initial = baseline;
    baseline = null;
    pages.initialSession(
        initial, initialContext == null ? context : initialContext, state, identity, ready);
    initialContext = null;
    return pages;
  }

  PageSwapHost pages() {
    StrictJson.require(pages != null && !closed, "页面会话尚未打开或已关闭");
    return pages;
  }

  @Override
  public Bundle save() {
    return pages().saveSession();
  }

  @Override
  public void lifecycle(int state) {
    pages().lifecycle(state);
  }

  @Override
  public void updateHostState(Bundle state) {
    pages().updateHostState(state);
  }

  @Override
  public void newIntent(Intent intent) {
    pages().newIntent(intent);
  }

  @Override
  public boolean result(String key, int resultCode, Intent data) {
    return pages().result(key, resultCode, data);
  }

  @Override
  public boolean back() {
    return pages().back();
  }

  @Override
  public void windowTouch() {
    pages().windowTouch();
  }

  @Override
  public void configurationChanged(Configuration configuration) {
    pages().configurationChanged(configuration);
  }

  @Override
  public void finishing() {
    pages().finishing();
  }

  @Override
  public void hostWarning(String code, Throwable failure) {
    if (pages != null && !closed) pages.hostWarning(code, failure);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (baseline != null) {
      NativePage unused = baseline;
      baseline = null;
      unused.close();
    }
    if (pages != null) {
      PageSwapHost host = pages;
      pages = null;
      host.close();
    }
  }
}
