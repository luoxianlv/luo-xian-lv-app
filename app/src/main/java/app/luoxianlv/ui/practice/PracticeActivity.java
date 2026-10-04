package app.luoxianlv.ui.practice;

import app.luoxianlv.practice.PracticePage;
import app.luoxianlv.hot.NativeHostActivity;
import app.luoxianlv.hot.contract.NativePage;

/** 保留系统组件身份和清单方向；演奏、渲染和退出流程由业务页实现。 */
public final class PracticeActivity extends NativeHostActivity {
  @Override
  protected NativePage createPage() {
    return new PracticePage();
  }
}
