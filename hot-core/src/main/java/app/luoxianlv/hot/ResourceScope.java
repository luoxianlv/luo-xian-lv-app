package app.luoxianlv.hot;

import app.luoxianlv.hot.contract.OfficialResources;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 一个Prepared拥有一个不可变版本范围；打开的流各自持有内容租约至关闭。 */
final class ResourceScope implements OfficialResources {
  static final Set<String> SUPPORTED = Set.of("harmonica", "wallpaperengine", "shaders", "theme", "config");
  static final List<String> ENGINE = List.of("index.html", "host.mjs", "webwallgl.mjs", "compat.mjs",
      "clock.mjs", "scene-video.mjs", "lifecycle.mjs", "audio.mjs");
  private final ContentStore store;
  private final ContentStore.Snapshot snapshot;
  private final File root;
  private final String identity;
  private final Map<String, String> kinds;
  private boolean retired;
  private int streams;

  ResourceScope(ContentStore store, ContentStore.Snapshot snapshot, File root, String identity) {
    this.store = store; this.snapshot = snapshot; this.root = root; this.identity = identity;
    var kinds = new HashMap<String, String>();
    if (snapshot != null) for (var artifact : snapshot.manifest.artifacts)
      if (!artifact.mount.isEmpty()) kinds.put(artifact.mount, artifact.role);
    this.kinds = Map.copyOf(kinds);
  }
  @Override public String identity() { return identity; }
  @Override public synchronized boolean mounted(String mount) { checkActive(); return kinds.containsKey(mount); }
  @Override public synchronized String kind(String mount) { checkActive(); return kinds.getOrDefault(mount, ""); }
  private void checkActive() { if (retired) throw new IllegalStateException("资源代际已退役"); }
  synchronized void retire() { retired = true; }
  synchronized int openStreams() { return streams; }

  @Override public synchronized InputStream open(String mount, String relative) throws IOException {
    if (retired) throw new IOException("资源代际已退役");
    if (!mounted(mount) || root == null || snapshot == null) throw new IOException("资源挂载未声明");
    if (streams >= 128) throw new IOException("本代资源流达到上限");
    if (relative.isEmpty() || relative.startsWith("/") || relative.indexOf('\\') >= 0) throw new IOException("资源相对路径无效");
    for (String part : relative.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("资源路径越界");
    Path base = root.toPath().toAbsolutePath().normalize(), target = base.resolve(mount).resolve(relative).normalize();
    if (!target.startsWith(base.resolve(mount))) throw new IOException("资源路径越界");
    for (Path current = target; !current.equals(base); current = current.getParent())
      if (Files.isSymbolicLink(current)) throw new IOException("资源路径包含链接");
    if (Files.isSymbolicLink(base) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
        || target.toFile().canWrite()) throw new IOException("官方资源缺失或类型改变");
    AutoCloseable lease = null;
    try {
      // 构造/验证/曝光时Prepared的主租约仍有效；open与retire同锁，先完成转租再释放主租约。
      lease = ContentLeases.pin(store.rootDirectory(), snapshot.manifest.snapshotId, snapshot.manifest.runtime.sha256);
      InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
      AutoCloseable retained = lease; streams++;
      return new FilterInputStream(input) {
        private boolean closed;
        @Override public synchronized void close() throws IOException {
          if (closed) return; closed = true;
          try { super.close(); }
          finally {
            synchronized (ResourceScope.this) { streams--; }
            try { retained.close(); } catch (Exception failure) { throw new IOException("资源租约释放失败", failure); }
          }
        }
      };
    } catch (Exception failure) {
      if (lease != null) try { lease.close(); } catch (Exception release) { failure.addSuppressed(release); }
      throw failure instanceof IOException ? (IOException) failure : new IOException("资源打开失败", failure);
    }
  }

  private byte[] read(String mount, String path, int maximum) throws Exception {
    try (var input = open(mount, path)) { return HotPackage.read(input, maximum); }
  }
  private String text(String mount, String path, int maximum) throws Exception {
    byte[] bytes = read(mount, path, maximum);
    String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    StrictJson.require(!value.startsWith("\uFEFF") && !value.isBlank(), "官方文本为空或包含BOM");
    return value;
  }
  private String document(String mount, String path) { return kind(mount).equals("config") ? "value" : path; }

  /** 在业务曝光前校验已声明挂载的关键结构，不改变任何用户项目。 */
  void validate() throws Exception {
    StrictJson.require(SUPPORTED.containsAll(kinds.keySet()), "官方资源挂载不支持");
    boolean androidRuntime = System.getProperty("java.vm.name", "").equals("Dalvik");
    int sdk = androidRuntime ? android.os.Build.VERSION.SDK_INT : 0;
    if (mounted("shaders")) requireShaderApi(androidRuntime, sdk);
    for (String mount : List.of("harmonica", "wallpaperengine", "shaders"))
      if (mounted(mount)) StrictJson.require(kind(mount).equals("resources"), "该挂载必须为资源ZIP");
    if (mounted("harmonica")) {
      var notes = new HashSet<Integer>();
      for (String line : text("harmonica", "index.tsv", 65536).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
        if (line.isBlank()) continue;
        String[] parts = line.split("\\t", -1);
        StrictJson.require(parts.length == 5, "音色索引字段无效");
        int midi = Integer.parseInt(parts[0]), count = Integer.parseInt(parts[1]), start = Integer.parseInt(parts[2]),
            end = Integer.parseInt(parts[3]), blend = Integer.parseInt(parts[4]);
        StrictJson.require(midi >= 48 && midi <= 85 && notes.add(midi) && count > 0 && count <= (8 << 20)
            && start >= 0 && blend > 0 && (long) start + blend < (long) end - blend && end <= count, "音色范围或循环无效");
        try (var input = open("harmonica", midi + ".pcm")) {
          long size = 0; byte[] buffer = new byte[32768]; int n;
          while ((n = input.read(buffer)) != -1) { StrictJson.require(n > 0, "音色读取停滞"); size += n; StrictJson.require(size <= (long) count * 2, "音色大小超限"); }
          StrictJson.require(size == (long) count * 2, "音色实际大小无效");
        }
      }
      StrictJson.require(notes.size() == 38, "音色集合不完整");
    }
    if (mounted("wallpaperengine")) for (String path : ENGINE) text("wallpaperengine", path, 8 << 20);
    if (mounted("theme")) {
      var value = StrictJson.object(read("theme", document("theme", "palette.json"), 65536)).only("schema", "light", "dark");
      StrictJson.require(value.number("schema") == 1, "主题schema不支持");
      for (String mode : List.of("light", "dark")) {
        var colors = value.object(mode).only("primary", "onPrimary", "secondary", "background", "surface", "onBackground", "onSurface");
        for (String name : List.of("primary", "onPrimary", "secondary", "background", "surface", "onBackground", "onSurface"))
          StrictJson.require(colors.string(name).matches("#[0-9a-fA-F]{8}"), "主题颜色必须为ARGB");
      }
    }
    if (mounted("config")) {
      var value = StrictJson.object(read("config", document("config", "config.json"), 65536))
          .only("schema", "harmonicaGainPermille", "shaderStrengthPermille");
      StrictJson.require(value.number("schema") == 1, "资源配置schema不支持");
      for (String name : List.of("harmonicaGainPermille", "shaderStrengthPermille"))
        StrictJson.require(value.number(name) >= 0 && value.number(name) <= 1000, "资源配置范围无效");
    }
    if (mounted("shaders")) {
      String sphere = text("shaders", "white-sphere.agsl", 65536), gravity = text("shaders", "gravity-lens.agsl", 65536);
      if (androidRuntime)
        Api33.validate(sphere, gravity);
    }
  }

  static void requireShaderApi(boolean android, int sdk) throws NativeLoader.ResourceUnsupported {
    if (android && sdk < 33) throw new NativeLoader.ResourceUnsupported("shaders", 33, sdk);
  }

  private static final class Api33 {
    static void validate(String sphereSource, String gravitySource) {
      var sphere = new android.graphics.RuntimeShader(sphereSource);
      sphere.setFloatUniform("center", 0, 0); sphere.setFloatUniform("radius", 1);
      sphere.setFloatUniform("opacity", 1); sphere.setFloatUniform("time", 0);
      var gravity = new android.graphics.RuntimeShader(gravitySource);
      gravity.setFloatUniform("center", 0, 0); gravity.setFloatUniform("radius", 1); gravity.setFloatUniform("strength", 1);
      android.graphics.RenderEffect.createRuntimeShaderEffect(gravity, "background");
    }
  }
}
