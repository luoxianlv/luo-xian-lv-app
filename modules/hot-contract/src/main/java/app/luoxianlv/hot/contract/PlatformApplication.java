package app.luoxianlv.hot.contract;

import android.content.Context;

/** 全局 SDK/存储使用真实进程上下文，不能长期捕获业务加载器的资源 Application。 */
public interface PlatformApplication {
  Context platformApplication();

  static Context of(Context context) {
    Context application = context.getApplicationContext();
    return application instanceof PlatformApplication
        ? ((PlatformApplication) application).platformApplication()
        : application;
  }
}
