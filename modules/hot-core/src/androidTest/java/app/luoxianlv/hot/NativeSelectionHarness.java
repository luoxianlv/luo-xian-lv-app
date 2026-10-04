package app.luoxianlv.hot;

/** 可由测试控制何时返回的系统选择替身；真实 Activity 结果仍经过 Android 框架。 */
public final class NativeSelectionHarness extends android.app.Activity {
  @Override
  protected void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    android.widget.TextView text = new android.widget.TextView(this);
    text.setText("等待测试返回文件");
    setContentView(text);
  }
}
