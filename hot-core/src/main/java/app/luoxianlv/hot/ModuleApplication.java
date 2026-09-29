package app.luoxianlv.hot;

import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;

/** 为 ViewModel 保留 Application 类型与本代资源；系统监听仍登记到真实进程 Application。 */
final class ModuleApplication extends Application {
  private final Application platform;
  private final ModuleResources source;
  private final ModuleResources.Binding resources;
  private final ClassLoader loader;

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
  public void registerComponentCallbacks(ComponentCallbacks callback) {
    platform.registerComponentCallbacks(callback);
  }

  @Override
  public void unregisterComponentCallbacks(ComponentCallbacks callback) {
    platform.unregisterComponentCallbacks(callback);
  }

  @Override
  public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
    platform.registerActivityLifecycleCallbacks(callback);
  }

  @Override
  public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
    platform.unregisterActivityLifecycleCallbacks(callback);
  }

  @Override
  public void registerOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
    platform.registerOnProvideAssistDataListener(callback);
  }

  @Override
  public void unregisterOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
    platform.unregisterOnProvideAssistDataListener(callback);
  }
}
