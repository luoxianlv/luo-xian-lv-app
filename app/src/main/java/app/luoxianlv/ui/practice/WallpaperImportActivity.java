package app.luoxianlv.ui.practice;

import app.luoxianlv.business.wallpaper.WallpaperImportPage;
import app.luoxianlv.hot.NativeHostActivity;
import app.luoxianlv.hot.contract.NativePage;

/** 外部 ZIP 关联仍指向此组件；平台 URI 授权和应用身份保持不变。 */
public final class WallpaperImportActivity extends NativeHostActivity {
  @Override
  protected NativePage createPage() {
    return new WallpaperImportPage();
  }
}
