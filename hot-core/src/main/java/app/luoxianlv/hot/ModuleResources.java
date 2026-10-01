package app.luoxianlv.hot;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;

/** 资源内容属于代际，方向、字体和尺寸属于窗口；不能把进程启动时的配置固定给所有页面。 */
final class ModuleResources {
  private final Resources packaged;
  private final Object loader;
  private final File runtime, business;
  private final java.util.Set<Resources> owners =
      java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
  private final java.util.Map<Resources, android.content.res.AssetManager> legacyOwners =
      new java.util.WeakHashMap<>();
  private boolean closed;

  ModuleResources(Resources packaged, File runtime, File business) throws IOException {
    this.packaged = packaged;
    this.runtime = runtime;
    this.business = business;
    loader = Build.VERSION.SDK_INT >= 30 && runtime != null ? Api30.load(runtime, business) : null;
  }

  synchronized Binding forOwner(Context owner, Configuration override) {
    StrictJson.require(!closed, "已退役模块不能挂载新的窗口资源");
    // 只创建一层配置 Context；再次以空配置派生会丢掉调用者的字体、主题等覆盖。
    Context configured = owner.createConfigurationContext(override);
    Resources current = configured.getResources();
    if (loader != null) {
      Api30.attach(current, loader);
      owners.add(current);
      return new Binding(configured, current, false);
    }
    // 旧系统的 AssetManager 也保存配置；只复制 Resources 仍会让第二窗口改掉第一窗口的资源选择。
    var assets = runtime == null ? packaged.getAssets() : legacyAssets(owner);
    Resources resources =
        new Resources(assets, current.getDisplayMetrics(), current.getConfiguration());
    if (runtime != null) legacyOwners.put(resources, assets);
    return new Binding(configured, resources, true);
  }

  private android.content.res.AssetManager legacyAssets(Context owner) {
    android.content.res.AssetManager assets = null;
    try {
      assets = android.content.res.AssetManager.class.getDeclaredConstructor().newInstance();
      var add =
          android.content.res.AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
      var paths = new java.util.LinkedHashSet<String>();
      var info = owner.getApplicationInfo();
      paths.add(info.sourceDir);
      if (info.splitSourceDirs != null) java.util.Collections.addAll(paths, info.splitSourceDirs);
      paths.add(runtime.getAbsolutePath());
      paths.add(business.getAbsolutePath());
      for (String path : paths)
        StrictJson.require(path != null && (Integer) add.invoke(assets, path) != 0, "窗口资源路径未加载");
      return assets;
    } catch (ReflectiveOperationException | RuntimeException error) {
      if (assets != null) assets.close();
      throw new IllegalStateException("旧系统窗口资源加载失败", error);
    }
  }

  /** 页面租约和后台工作已退出后调用；仅移除本代加载器，不关闭系统共享的 AssetManager。 */
  synchronized void close() {
    if (closed) return;
    closed = true;
    if (loader != null) {
      for (Resources owner : owners) Api30.detach(owner, loader);
      owners.clear();
      Api30.close(loader);
    }
    for (var assets : legacyOwners.values()) assets.close();
    legacyOwners.clear();
  }

  static final class Binding {
    private final Context configured;
    private final Resources resources;
    private final boolean manual;

    Binding(Context configured, Resources resources, boolean manual) {
      this.configured = configured;
      this.resources = resources;
      this.manual = manual;
    }

    Resources get() {
      if (manual) {
        Resources current = configured.getResources();
        if (!resources.getConfiguration().equals(current.getConfiguration())
            || !resources.getDisplayMetrics().equals(current.getDisplayMetrics())) {
          resources.updateConfiguration(current.getConfiguration(), current.getDisplayMetrics());
        }
      }
      return resources;
    }
  }

  private static final class Api30 {
    static Object load(File runtime, File business) throws IOException {
      var loader = new android.content.res.loader.ResourcesLoader();
      var providers = new java.util.ArrayList<android.content.res.loader.ResourcesProvider>();
      try {
        for (File file : new File[] {runtime, business}) {
          try (var descriptor =
              ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            providers.add(android.content.res.loader.ResourcesProvider.loadFromApk(descriptor));
          }
        }
        loader.setProviders(providers);
        return loader;
      } catch (Throwable failure) {
        for (var provider : providers) provider.close();
        throw failure;
      }
    }

    static void attach(Resources resources, Object loader) {
      resources.addLoaders((android.content.res.loader.ResourcesLoader) loader);
    }

    static void detach(Resources resources, Object loader) {
      resources.removeLoaders((android.content.res.loader.ResourcesLoader) loader);
    }

    static void close(Object value) {
      var loader = (android.content.res.loader.ResourcesLoader) value;
      var providers = java.util.List.copyOf(loader.getProviders());
      loader.clearProviders();
      for (var provider : providers) provider.close();
    }
  }
}
