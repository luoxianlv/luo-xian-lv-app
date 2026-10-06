package app.luoxianlv;

import android.app.Application;
import app.luoxianlv.debug.CrashLog;
import app.luoxianlv.host.Bootstrap;

/** 真正的纯 Java 进程入口，业务和共享依赖仅从已校验的独立 APK 加载。 */
public final class LuoXianLvApp extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    if (app.luoxianlv.update.DeltaWorkerService.isPatchProcess(this)) return;
    if (app.luoxianlv.host.RecoveryActivity.isRecoveryProcess(this)) return;
    if (app.luoxianlv.input.InputController.isHelperProcess(this)) return;
    CrashLog.install(this, Bootstrap::recordCrash);
    app.luoxianlv.hot.ApkUpdateBridge.install(
        this, Bootstrap::usageChanged, Bootstrap::ordinaryUpdateIdle);
    app.luoxianlv.input.InputController.install(this).setWirelessBackend(
        new app.luoxianlv.input.WirelessAdbBackend(this));
    Bootstrap.start(this);
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    Bootstrap.trim(level);
  }
}
