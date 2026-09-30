package app.luoxianlv.hot;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Looper;
import android.view.LayoutInflater;
import app.luoxianlv.hot.contract.BusinessFactory;
import app.luoxianlv.hot.contract.NativePage;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 一进程一套共享运行时；业务类使用独立加载器，旧页面由上层代际管理器按租约释放。 */
public final class NativeLoader {
  private static RuntimeSlot runtime;
  private final Context application;
  private final ContentStore store;
  private final ContentQuarantine quarantine;
  private final long hostContract;
  private final Set<String> supportedMounts;

  private static final class RuntimeSlot {
    final String hash, abi;
    final File apk;
    final ClassLoader loader;
    volatile boolean used;

    RuntimeSlot(String hash, String abi, File apk, ClassLoader loader) {
      this.hash = hash;
      this.abi = abi;
      this.apk = apk;
      this.loader = loader;
    }
  }

  public static final class RestartRequired extends Exception {
    RestartRequired() {
      super("共享运行时改变，需下次进程启动采用整组快照");
    }
  }

  public static final class Prepared {
    public final HotManifest manifest;
    public final String runtimeHash, runtimeAbi;
    private final Class<?> entry;
    private final ClassLoader loader;
    private final ModuleResources resources;
    public final File resourceRoot;
    private final String identity;
    private ModuleApplication moduleApplication;

    Prepared(
        HotManifest manifest,
        Class<?> entry,
        ClassLoader loader,
        Resources resources,
        File resourceRoot) {
      this(
          manifest,
          entry,
          loader,
          testResources(resources),
          resourceRoot,
          manifest == null ? "baseline" : manifest.snapshotId,
          manifest == null ? "" : manifest.runtime.sha256,
          manifest == null ? "" : manifest.runtimeAbi);
    }

    private static ModuleResources testResources(Resources resources) {
      try {
        return new ModuleResources(resources, null, null);
      } catch (java.io.IOException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    private Prepared(
        HotManifest manifest,
        Class<?> entry,
        ClassLoader loader,
        ModuleResources resources,
        File resourceRoot,
        String identity,
        String runtimeHash,
        String runtimeAbi) {
      this.manifest = manifest;
      this.entry = entry;
      this.loader = loader;
      this.resources = resources;
      this.resourceRoot = resourceRoot;
      this.identity = identity;
      this.runtimeHash = runtimeHash;
      this.runtimeAbi = runtimeAbi;
    }

    /** 构造业务对象可能建立主线程生命周期，必须由宿主在主线程调用。 */
    public NativePage instantiate() throws Exception {
      StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "业务页面必须在主线程创建");
      markRuntimeUsed();
      Object value = entry.getDeclaredConstructor().newInstance();
      return value instanceof BusinessFactory
          ? ((BusinessFactory) value).page("main")
          : (NativePage) value;
    }

    public BusinessFactory factory() throws Exception {
      StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "业务工厂必须在主线程创建");
      markRuntimeUsed();
      return entry.asSubclass(BusinessFactory.class).getDeclaredConstructor().newInstance();
    }

    private void markRuntimeUsed() {
      synchronized (NativeLoader.class) {
        if (runtime != null && runtime.hash.equals(runtimeHash)) runtime.used = true;
      }
    }

    public String identity() {
      return identity;
    }

    public ClassLoader classLoader() {
      return loader;
    }

    public PageTarget page(String route, BusinessFactory factory) {
      StrictJson.require(route != null && route.matches("[a-z][a-z0-9._-]{0,95}"), "业务路由无效");
      return new PageTarget() {
        @Override
        public String identity() {
          return Prepared.this.identity() + "#" + route;
        }

        @Override
        public Context context(Context owner) {
          return Prepared.this.context(owner);
        }

        @Override
        public NativePage create() {
          return factory.page(route);
        }
      };
    }

    public synchronized Context context(Context owner) {
      StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "模块上下文必须在主线程创建");
      if (moduleApplication == null) {
        Context app = owner.getApplicationContext();
        StrictJson.require(app instanceof Application, "模块上下文缺少真实 Application");
        moduleApplication = new ModuleApplication((Application) app, resources, loader);
      }
      return new PageContext(owner, resources, loader, moduleApplication);
    }
  }

  public NativeLoader(
      Context application, ContentStore store, ContentQuarantine quarantine, long hostContract) {
    this(application, store, quarantine, hostContract, java.util.Collections.emptySet());
  }

  /** 只允许准备失败、尚未构造任何业务时放弃加载器；执行过业务后不能混用另一运行时。 */
  public boolean discardUninitializedRuntime(String expectedHash) {
    synchronized (NativeLoader.class) {
      if (runtime == null) return true;
      if (!runtime.hash.equals(expectedHash) || runtime.used) return false;
      runtime = null;
      return true;
    }
  }

  public NativeLoader(
      Context application,
      ContentStore store,
      ContentQuarantine quarantine,
      long hostContract,
      Set<String> supportedMounts) {
    this.application = application.getApplicationContext();
    this.store = store;
    this.quarantine = quarantine;
    this.hostContract = hostContract;
    this.supportedMounts = java.util.Collections.unmodifiableSet(new HashSet<>(supportedMounts));
  }

  /** 调用前必须验证许可并落盘 PREPARING 日志；该方法在后台校验与准备，不创建 View。 */
  public Prepared prepare(ContentStore.Snapshot snapshot, ActivationJournal.State activation)
      throws Exception {
    StrictJson.require(Looper.myLooper() != Looper.getMainLooper(), "禁止在主线程校验或加载热更包");
    HotManifest manifest = snapshot.manifest;
    StrictJson.require(
        manifest.applicationId.equals(application.getPackageName())
            && hostContract >= manifest.hostMin
            && hostContract <= manifest.hostMax,
        "候选与实际宿主不兼容");
    StrictJson.require(
        activation.phase == ActivationJournal.Phase.PREPARING
            && activation.candidate.equals(manifest.snapshotId),
        "执行新代码前必须先保存本次激活记录");
    return prepareVerified(snapshot);
  }

  /** 普通冷启动仅允许稳定日志指向的已签名组合，不使用过期许可启动新候选。 */
  public Prepared prepareStable(
      ContentStore.Snapshot snapshot,
      ActivationJournal.State state,
      TrustStore trust,
      String environment)
      throws Exception {
    StrictJson.require(Looper.myLooper() != Looper.getMainLooper(), "稳定版本必须在后台准备");
    snapshot.manifest.compatible(
        application.getPackageName(), environment, hostContract, supportedMounts);
    trust.verifyStable(snapshot, state);
    return prepareVerified(snapshot);
  }

  private Prepared prepareVerified(ContentStore.Snapshot snapshot) throws Exception {
    HotManifest manifest = snapshot.manifest;
    quarantine.requireAllowed(manifest, hostContract);
    store.verifySnapshotObjects(snapshot);
    File mounted = null;
    if (manifest.artifacts.stream().anyMatch(artifact -> !artifact.mount.isEmpty()))
      mounted = new ResourceMounts(store).prepare(snapshot, supportedMounts);
    RuntimeSlot shared = runtime(snapshot);
    File business = apkAlias(snapshot.directory, manifest.business, "business.apk");
    rejectBusinessNativeCode(business);
    DexClassLoader loader =
        new DexClassLoader(
            business.getAbsolutePath(),
            application.getCodeCacheDir().getAbsolutePath(),
            null,
            shared.loader);
    Class<?> entry = businessEntry(manifest.business.entryClass, loader);
    ApplicationInfo combined = new ApplicationInfo(application.getApplicationInfo());
    combined.splitSourceDirs =
        new String[] {shared.apk.getAbsolutePath(), business.getAbsolutePath()};
    combined.splitPublicSourceDirs = combined.splitSourceDirs.clone();
    Resources resources = application.getPackageManager().getResourcesForApplication(combined);
    return new Prepared(
        manifest,
        entry,
        loader,
        new ModuleResources(resources, shared.apk, business),
        mounted,
        manifest.snapshotId,
        shared.hash,
        shared.abi);
  }

  /** APK 签名保护的内置恢复组合；文件与哈希必须先由 BundledBaseline 校验，不能用于下载候选。 */
  public Prepared prepareBaseline(BundledBaseline baseline) throws Exception {
    StrictJson.require(Looper.myLooper() != Looper.getMainLooper(), "禁止在主线程准备内置业务");
    baseline.verify();
    RuntimeSlot shared;
    synchronized (NativeLoader.class) {
      if (runtime == null) {
        requireThinHost();
        verifyRuntimeAbi(baseline.runtime, baseline.abi);
        String libraries =
            extractLibraries(baseline.runtime, new File(baseline.directory, "native"));
        runtime =
            new RuntimeSlot(
                baseline.runtimeHash,
                baseline.abi,
                baseline.runtime,
                new DexClassLoader(
                    baseline.runtime.getPath(),
                    application.getCodeCacheDir().getPath(),
                    libraries,
                    application.getClassLoader()));
      }
      if (!runtime.hash.equals(baseline.runtimeHash) || !runtime.abi.equals(baseline.abi))
        throw new RestartRequired();
      shared = runtime;
    }
    rejectBusinessNativeCode(baseline.business);
    DexClassLoader loader =
        new DexClassLoader(
            baseline.business.getPath(),
            application.getCodeCacheDir().getPath(),
            null,
            shared.loader);
    Class<?> entry = businessEntry(baseline.entry, loader);
    ApplicationInfo combined = new ApplicationInfo(application.getApplicationInfo());
    combined.splitSourceDirs = new String[] {shared.apk.getPath(), baseline.business.getPath()};
    combined.splitPublicSourceDirs = combined.splitSourceDirs.clone();
    Resources resources = application.getPackageManager().getResourcesForApplication(combined);
    return new Prepared(
        null,
        entry,
        loader,
        new ModuleResources(resources, shared.apk, baseline.business),
        null,
        "apk:" + baseline.runtimeHash + ":" + baseline.businessHash,
        shared.hash,
        shared.abi);
  }

  private static Class<?> businessEntry(String name, ClassLoader loader) throws Exception {
    Class<?> entry = Class.forName(name, false, loader);
    StrictJson.require(
        NativePage.class.isAssignableFrom(entry) || BusinessFactory.class.isAssignableFrom(entry),
        "业务入口没有实现宿主契约");
    StrictJson.require(entry.getClassLoader() == loader, "业务入口被父加载器截获");
    return entry;
  }

  private void requireThinHost() throws Exception {
    try {
      Class.forName("kotlin.Unit", false, application.getClassLoader());
      throw new IllegalStateException("宿主仍包含共享运行时，需要完成三层 APK 构建迁移");
    } catch (ClassNotFoundException expected) {
    }
  }

  private static void verifyRuntimeAbi(File apk, String expected) throws Exception {
    try (ZipFile zip = new ZipFile(apk)) {
      ZipEntry marker = zip.getEntry("assets/runtime-abi.txt");
      StrictJson.require(marker != null && marker.getSize() <= 256, "运行时缺少 ABI 标识");
      try (InputStream input = zip.getInputStream(marker)) {
        StrictJson.require(
            new String(HotPackage.read(input, 256), StandardCharsets.UTF_8).trim().equals(expected),
            "运行时实际 ABI 与声明不符");
      }
    }
  }

  private RuntimeSlot runtime(ContentStore.Snapshot snapshot) throws Exception {
    synchronized (NativeLoader.class) {
      HotManifest manifest = snapshot.manifest;
      if (runtime != null) {
        if (!runtime.hash.equals(manifest.runtime.sha256)
            || !runtime.abi.equals(manifest.runtimeAbi)) throw new RestartRequired();
        return runtime;
      }
      // 迁移未完成的旧宿主不能冒充薄宿主，否则 Kotlin/Compose 会被父加载器错误截获。
      requireThinHost();
      File runtimeDirectory = store.runtimeDirectory(manifest.runtime.sha256);
      File apk = apkAlias(runtimeDirectory, manifest.runtime, "runtime.apk");
      verifyRuntimeAbi(apk, manifest.runtimeAbi);
      String libraries = extractLibraries(apk, new File(runtimeDirectory, "native"));
      ClassLoader loader =
          new DexClassLoader(
              apk.getAbsolutePath(),
              application.getCodeCacheDir().getAbsolutePath(),
              libraries,
              application.getClassLoader());
      runtime = new RuntimeSlot(manifest.runtime.sha256, manifest.runtimeAbi, apk, loader);
      return runtime;
    }
  }

  private File apkAlias(File directory, HotManifest.Artifact artifact, String name)
      throws Exception {
    File source = store.objectFile(artifact.sha256), alias = new File(directory, name);
    if (alias.exists()) {
      ContentStore.verifyFile(alias, artifact.sha256, artifact.size);
      return alias;
    }
    StrictJson.require(
        directory.getUsableSpace() >= artifact.size + 16L * StrictJson.MAX_BYTES, "空间不足，保留当前运行时");
    // Android 应用沙箱可能禁止硬链接；使用只读、校验后原子提交的 APK 副本。
    File temporary = File.createTempFile("apk-", ".part", directory);
    try {
      try (InputStream input = Files.newInputStream(source.toPath());
          FileOutputStream output = new FileOutputStream(temporary)) {
        StrictJson.require(temporary.setReadOnly(), "无法将模块副本设为只读");
        byte[] buffer = new byte[32768];
        long total = 0;
        int n;
        while ((n = input.read(buffer)) != -1) {
          total += n;
          StrictJson.require(total <= artifact.size, "模块来源大小改变");
          output.write(buffer, 0, n);
        }
        StrictJson.require(total == artifact.size, "模块来源不完整");
        output.getFD().sync();
      }
      ContentStore.verifyFile(temporary, artifact.sha256, artifact.size);
      ContentStore.moveAtomic(
          temporary.toPath(), alias.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } finally {
      if (temporary.exists()) {
        temporary.setWritable(true, true);
        Files.deleteIfExists(temporary.toPath());
      }
    }
    ContentStore.syncDirectory(directory);
    return alias;
  }

  private static void rejectBusinessNativeCode(File apk) throws Exception {
    try (ZipFile zip = new ZipFile(apk)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements())
        StrictJson.require(
            !entries.nextElement().getName().startsWith("lib/"), "原生库必须放入共享运行时组，不能即时替换");
    }
  }

  private static String extractLibraries(File apk, File directory) throws Exception {
    try (ZipFile zip = new ZipFile(apk)) {
      Set<String> supported = new HashSet<>();
      Enumeration<? extends ZipEntry> scan = zip.entries();
      while (scan.hasMoreElements()) {
        String name = scan.nextElement().getName();
        if (name.startsWith("lib/") && name.endsWith(".so")) {
          String[] parts = name.split("/");
          StrictJson.require(parts.length == 3, "原生库路径无效");
          supported.add(parts[1]);
        }
      }
      if (supported.isEmpty()) return null;
      String chosen = null;
      for (String abi : Build.SUPPORTED_ABIS)
        if (supported.contains(abi)) {
          chosen = abi;
          break;
        }
      StrictJson.require(chosen != null, "运行时不支持当前设备 CPU 架构");
      StrictJson.require(directory.isDirectory() || directory.mkdirs(), "无法创建原生库目录");
      long total = 0;
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (!entry.getName().startsWith("lib/" + chosen + "/") || !entry.getName().endsWith(".so"))
          continue;
        String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
        StrictJson.require(
            name.matches("[A-Za-z0-9_.-]+\\.so")
                && entry.getSize() > 0
                && entry.getSize() <= HotManifest.MAX_EXPANDED,
            "原生库名称或大小无效");
        total += entry.getSize();
        StrictJson.require(total <= HotManifest.MAX_EXPANDED, "原生库展开总量超限");
        File target = new File(directory, name);
        if (target.exists()) Files.delete(target.toPath());
        try (InputStream input = zip.getInputStream(entry);
            FileOutputStream output = new FileOutputStream(target)) {
          StrictJson.require(target.setReadOnly(), "无法将原生库设为只读");
          byte[] buffer = new byte[32768];
          long copied = 0;
          int n;
          while ((n = input.read(buffer)) != -1) {
            copied += n;
            StrictJson.require(copied <= entry.getSize(), "原生库展开超限");
            output.write(buffer, 0, n);
          }
          StrictJson.require(copied == entry.getSize(), "原生库展开不完整");
          output.getFD().sync();
        }
      }
      ContentStore.syncDirectory(directory);
      return directory.getAbsolutePath();
    }
  }

  static final class PageContext extends android.view.ContextThemeWrapper {
    private final ModuleResources source;
    private final ModuleResources.Binding resources;
    private final android.content.res.Configuration overrides;
    private final ClassLoader loader;
    private final Application application;

    PageContext(
        Context base, ModuleResources resources, ClassLoader loader, Application application) {
      this(base, resources, loader, application, new android.content.res.Configuration());
    }

    PageContext(
        Context base,
        ModuleResources resources,
        ClassLoader loader,
        Application application,
        android.content.res.Configuration overrides) {
      super(base, 0);
      this.source = resources;
      this.overrides = new android.content.res.Configuration(overrides);
      this.resources = resources.forOwner(base, this.overrides);
      this.loader = loader;
      this.application = application;
    }

    @Override
    public Context getApplicationContext() {
      return application;
    }

    @Override
    public Context createConfigurationContext(android.content.res.Configuration override) {
      var merged = new android.content.res.Configuration(overrides);
      merged.updateFrom(override);
      return new PageContext(getBaseContext(), source, loader, application, merged);
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
    public Object getSystemService(String name) {
      if (Context.LAYOUT_INFLATER_SERVICE.equals(name))
        return LayoutInflater.from(getBaseContext()).cloneInContext(this);
      return super.getSystemService(name);
    }
  }
}
