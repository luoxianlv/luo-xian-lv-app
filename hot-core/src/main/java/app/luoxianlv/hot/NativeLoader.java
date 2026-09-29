package app.luoxianlv.hot;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Looper;
import android.view.LayoutInflater;
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
    private final Class<? extends NativePage> entry;
    private final ClassLoader loader;
    private final Resources resources;
    public final File resourceRoot;

    Prepared(
        HotManifest manifest,
        Class<? extends NativePage> entry,
        ClassLoader loader,
        Resources resources,
        File resourceRoot) {
      this.manifest = manifest;
      this.entry = entry;
      this.loader = loader;
      this.resources = resources;
      this.resourceRoot = resourceRoot;
    }

    /** 构造业务对象可能建立主线程生命周期，必须由宿主在主线程调用。 */
    public NativePage instantiate() throws Exception {
      StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "业务页面必须在主线程创建");
      return entry.getDeclaredConstructor().newInstance();
    }

    public Context context(Context owner) {
      return new PageContext(owner, resources, loader);
    }
  }

  public NativeLoader(
      Context application, ContentStore store, ContentQuarantine quarantine, long hostContract) {
    this(application, store, quarantine, hostContract, java.util.Collections.emptySet());
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
    Class<? extends NativePage> entry =
        Class.forName(manifest.business.entryClass, false, loader).asSubclass(NativePage.class);
    ApplicationInfo combined = new ApplicationInfo(application.getApplicationInfo());
    combined.splitSourceDirs =
        new String[] {shared.apk.getAbsolutePath(), business.getAbsolutePath()};
    combined.splitPublicSourceDirs = combined.splitSourceDirs.clone();
    Resources resources = application.getPackageManager().getResourcesForApplication(combined);
    return new Prepared(manifest, entry, loader, resources, mounted);
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
      try {
        Class.forName("kotlin.Unit", false, application.getClassLoader());
        throw new IllegalStateException("宿主仍包含共享运行时，需要完成三层 APK 构建迁移");
      } catch (ClassNotFoundException expected) {
      }
      File runtimeDirectory = store.runtimeDirectory(manifest.runtime.sha256);
      File apk = apkAlias(runtimeDirectory, manifest.runtime, "runtime.apk");
      try (ZipFile zip = new ZipFile(apk)) {
        ZipEntry marker = zip.getEntry("assets/runtime-abi.txt");
        StrictJson.require(marker != null && marker.getSize() <= 256, "运行时缺少 ABI 标识");
        try (InputStream input = zip.getInputStream(marker)) {
          String abi = new String(HotPackage.read(input, 256), StandardCharsets.UTF_8).trim();
          StrictJson.require(abi.equals(manifest.runtimeAbi), "运行时实际 ABI 与签名声明不符");
        }
      }
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
      Files.move(temporary.toPath(), alias.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
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

  private static final class PageContext extends android.view.ContextThemeWrapper {
    private final Resources resources;
    private final ClassLoader loader;

    PageContext(Context base, Resources resources, ClassLoader loader) {
      super(base, 0);
      this.resources = resources;
      this.loader = loader;
    }

    @Override
    public Resources getResources() {
      return resources;
    }

    @Override
    public AssetManager getAssets() {
      return resources.getAssets();
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
