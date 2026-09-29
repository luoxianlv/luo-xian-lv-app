package app.luoxianlv.host;

import android.content.Context;
import app.luoxianlv.hot.NativeHostActivity;
import app.luoxianlv.hot.PageSwapHost;
import app.luoxianlv.hot.PageTarget;
import app.luoxianlv.hot.contract.NativePage;

/** 固定 Android 窗口绑定完整业务快照中的页面路由。 */
public abstract class BusinessActivity extends NativeHostActivity {
  private Bootstrap.Source source;
  private PageSwapHost session;

  protected abstract String route();

  @Override
  protected AutoCloseable whenPageReady(Runnable ready) {
    return Bootstrap.ready(ready);
  }

  @Override
  protected NativePage createPage() {
    source = Bootstrap.source();
    return source.factory.page(route());
  }

  @Override
  protected Context initialPageContext() {
    return source.prepared.context(this);
  }

  @Override
  protected String initialPageIdentity(NativePage page) {
    return source.prepared.identity() + "#" + route();
  }

  @Override
  protected PageTarget initialPageTarget() {
    return source.prepared.page(route(), source.factory);
  }

  @Override
  protected void pageSessionReady(PageSwapHost session) {
    this.session = session;
    source = null;
    Bootstrap.pageOpened(session, route());
  }

  @Override
  protected void pageSessionClosed() {
    if (session != null) Bootstrap.pageClosed(session);
    session = null;
  }
}
