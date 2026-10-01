package app.luoxianlv;

import app.luoxianlv.business.MainPage;
import app.luoxianlv.hot.NativeHostActivity;
import app.luoxianlv.hot.contract.NativePage;

/** 组件身份保持不变；业务入口完成拆分后由统一快照选择器替换当前内置构造。 */
public final class MainActivity extends NativeHostActivity {
  @Override
  protected NativePage createPage() {
    return new MainPage();
  }
}
