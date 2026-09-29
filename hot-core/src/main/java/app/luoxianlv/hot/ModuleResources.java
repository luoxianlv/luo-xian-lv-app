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

  ModuleResources(Resources packaged, File runtime, File business) throws IOException {
    this.packaged = packaged;
    loader = Build.VERSION.SDK_INT >= 30 && runtime != null ? Api30.load(runtime, business) : null;
  }

  Binding forOwner(Context owner, Configuration override) {
    // 只创建一层配置 Context；再次以空配置派生会丢掉调用者的字体、主题等覆盖。
    Context configured = owner.createConfigurationContext(override);
    Resources current = configured.getResources();
    if (loader != null) {
      Api30.attach(current, loader);
      return new Binding(configured, current, false);
    }
    // Android 8–10 没有公开的资源加载器；此副本只供单个窗口，读取前同步配置。
    return new Binding(
        configured,
        new Resources(
            packaged.getAssets(), current.getDisplayMetrics(), current.getConfiguration()),
        true);
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
  }
}
