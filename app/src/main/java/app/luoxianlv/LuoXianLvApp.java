package app.luoxianlv;

import android.app.Application;
import app.luoxianlv.business.AppProcess;
import app.luoxianlv.debug.CrashLog;
import app.luoxianlv.hot.contract.ProcessHooks;

/** 稳定进程入口；先安装不依赖 Kotlin 的崩溃记录，再进入业务初始化。 */
public final class LuoXianLvApp extends Application {
  private ProcessHooks business;

  @Override
  public void onCreate() {
    super.onCreate();
    CrashLog.install(this);
    business = new AppProcess(this);
    business.initialize();
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (business != null) business.trimMemory(level);
  }
}
