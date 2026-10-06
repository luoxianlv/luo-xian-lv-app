package app.luoxianlv;

import android.app.Application;
import app.luoxianlv.app.AppProcess;
import app.luoxianlv.debug.CrashLog;
import app.luoxianlv.hot.contract.ProcessHooks;

/** 稳定进程入口；先安装不依赖 Kotlin 的崩溃记录，再进入业务初始化。 */
public final class LuoXianLvApp extends Application {
  private ProcessHooks business;

  @Override
  public void onCreate() {
    super.onCreate();
    if (app.luoxianlv.update.DeltaWorkerService.isPatchProcess(this)) return;
    if (app.luoxianlv.input.InputController.isHelperProcess(this)) return;
    app.luoxianlv.input.InputController.install(this).setWirelessBackend(
        new app.luoxianlv.input.WirelessAdbBackend(this));
    CrashLog.install(this);
    app.luoxianlv.hot.ApkUpdateBridge.install(
        this,
        () -> {},
        () -> {
          var port = app.luoxianlv.hot.contract.PlaybackBridge.current();
          if (port == null) return true;
          var value = port.query("state");
          return value != null && !value.getBoolean("playing") && !value.getBoolean("preparing");
        });
    business = new AppProcess(this);
    business.initialize();
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (business != null) business.trimMemory(level);
  }
}
