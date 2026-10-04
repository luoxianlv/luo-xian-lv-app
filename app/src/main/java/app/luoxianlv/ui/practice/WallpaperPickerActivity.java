package app.luoxianlv.ui.practice;

import app.luoxianlv.wallpaper.WallpaperPickerPage;
import app.luoxianlv.hot.NativeHostActivity;
import app.luoxianlv.hot.contract.NativePage;

/** 保留竖屏壁纸选择的组件身份，内容与返回路径由业务页处理。 */
public final class WallpaperPickerActivity extends NativeHostActivity {
  @Override
  protected NativePage createPage() {
    return new WallpaperPickerPage();
  }
}
