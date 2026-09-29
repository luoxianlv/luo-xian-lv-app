package app.luoxianlv.host;

import android.content.Context;
import app.luoxianlv.hot.NativeHostActivity;
import app.luoxianlv.hot.contract.NativePage;

/** 固定 Android 窗口绑定完整业务快照中的页面路由。 */
public abstract class BusinessActivity extends NativeHostActivity {
  private Bootstrap.Source source;

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
}
