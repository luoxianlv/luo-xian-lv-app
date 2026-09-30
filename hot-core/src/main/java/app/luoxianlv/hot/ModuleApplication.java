package app.luoxianlv.hot;

import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;

/** 为 ViewModel 保留 Application 类型与本代资源；系统监听仍登记到真实进程 Application。 */
final class ModuleApplication extends Application
    implements app.luoxianlv.hot.contract.PlatformApplication {
  private final Application platform;
  private final ModuleResources source;
  private final ModuleResources.Binding resources;
  private final ClassLoader loader;
  private final java.util.List<ComponentCallbacks> componentCallbacks = new java.util.ArrayList<>();
  private final java.util.List<ActivityLifecycleCallbacks> activityCallbacks =
      new java.util.ArrayList<>();
  private final java.util.List<OnProvideAssistDataListener> assistCallbacks =
      new java.util.ArrayList<>();
  private boolean retired;

  ModuleApplication(Application platform, ModuleResources resources, ClassLoader loader) {
    this.platform = platform;
    this.source = resources;
    this.resources = resources.forOwner(platform, new android.content.res.Configuration());
    this.loader = loader;
    attachBaseContext(platform);
  }

  @Override
  public Context getApplicationContext() {
    return this;
  }

  @Override
  public Context platformApplication() {
    return platform;
  }

  @Override
  public Resources getResources() {
    return resources.get();
  }

  @Override
  public AssetManager getAssets() {
    return getResources().getAssets();
  }

  @Override
  public ClassLoader getClassLoader() {
    return loader;
  }

  @Override
  public Context createConfigurationContext(android.content.res.Configuration override) {
    return new NativeLoader.PageContext(platform, source, loader, this, override);
  }

  @Override
  public synchronized void registerComponentCallbacks(ComponentCallbacks callback) {
    checkActive();
    platform.registerComponentCallbacks(callback);
    componentCallbacks.add(callback);
  }

  @Override
  public synchronized void unregisterComponentCallbacks(ComponentCallbacks callback) {
    platform.unregisterComponentCallbacks(callback);
    componentCallbacks.remove(callback);
  }

  @Override
  public synchronized void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
    checkActive();
    platform.registerActivityLifecycleCallbacks(callback);
    activityCallbacks.add(callback);
  }

  @Override
  public synchronized void unregisterActivityLifecycleCallbacks(
      ActivityLifecycleCallbacks callback) {
    platform.unregisterActivityLifecycleCallbacks(callback);
    activityCallbacks.remove(callback);
  }

  @Override
  public synchronized void registerOnProvideAssistDataListener(
      OnProvideAssistDataListener callback) {
    checkActive();
    platform.registerOnProvideAssistDataListener(callback);
    assistCallbacks.add(callback);
  }

  @Override
  public synchronized void unregisterOnProvideAssistDataListener(
      OnProvideAssistDataListener callback) {
    platform.unregisterOnProvideAssistDataListener(callback);
    assistCallbacks.remove(callback);
  }

  private void checkActive() {
    if (retired) throw new IllegalStateException("本代应用监听已退役");
  }

  synchronized void closeCallbacks() {
    retired = true;
    for (var callback : componentCallbacks) platform.unregisterComponentCallbacks(callback);
    for (var callback : activityCallbacks) platform.unregisterActivityLifecycleCallbacks(callback);
    for (var callback : assistCallbacks) platform.unregisterOnProvideAssistDataListener(callback);
    componentCallbacks.clear();
    activityCallbacks.clear();
    assistCallbacks.clear();
  }
}
