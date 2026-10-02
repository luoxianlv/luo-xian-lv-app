package app.luoxianlv.hot;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** 独立挂载页面并隔离输入：预绘制页不能抢焦点、触摸穿透或覆盖旧页。 */
final class PageContainer extends FrameLayout {
  private boolean input;

  PageContainer(Context context, View content, boolean input) {
    super(context);
    setSaveFromParentEnabled(false);
    input(input);
    if (!input) content.clearFocus();
    addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
  }

  void input(boolean enabled) {
    input = enabled;
    setDescendantFocusability(enabled ? FOCUS_AFTER_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
    setImportantForAccessibility(
        enabled ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    return input && super.dispatchTouchEvent(event);
  }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent event) {
    return input && super.dispatchGenericMotionEvent(event);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    return input && super.dispatchKeyEvent(event);
  }
}
