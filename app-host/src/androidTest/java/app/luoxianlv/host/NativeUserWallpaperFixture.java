package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.widget.ImageView;
import app.luoxianlv.hot.contract.PracticeBridge;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** 仅为 Debug 本机验收通过生产导入器新增用户项目；保留用户选择和所有已存在项目。 */
final class NativeUserWallpaperFixture {
  private static final String TITLE = "Native user wallpaper preservation fixture";
  private static final byte[] HTML =
      ("<!doctype html><html><head><meta charset=\"utf-8\"><title>Native fixture</title>"
              + "<style>html,body{margin:0;height:100%;background:#30485c;color:white}"
              + "body{display:grid;place-items:center;font:18px sans-serif}</style></head>"
              + "<body>Native user wallpaper fixture</body></html>")
          .getBytes(StandardCharsets.UTF_8);

  private NativeUserWallpaperFixture() {}

  /** 在 instrumentation 工作线程调用；主线程只取得当前业务资源 Context，不阻塞界面导入。 */
  static JSONObject prepare(Instrumentation runner, Activity home) throws Exception {
    Context target = testTarget(runner, home);
    Path report =
        new File(target.getFilesDir(), "native-user-wallpaper-fixture-report.json").toPath();
    Files.deleteIfExists(report); // 本次失败不保留上一轮成功回执；不触碰任何用户项目。
    var source = Bootstrap.source();
    Context module = moduleContext(runner, home, source);
    ClassLoader loader = source.prepared.classLoader();
    Class<?> storeType =
        Class.forName("app.luoxianlv.wallpaper.WallpaperProjectStore", true, loader);
    Object store = storeType.getField("INSTANCE").get(null);
    Class<?> storageType = Class.forName("app.luoxianlv.shared.AppStorage", true, loader);
    File wallpapers =
        ((File)
                storageType
                    .getMethod("wallpapers", Context.class)
                    .invoke(storageType.getField("INSTANCE").get(null), module))
            .getCanonicalFile();
    File external = target.getExternalFilesDir(null);
    require(
        external != null && wallpapers.equals(new File(external, "wallpapers").getCanonicalFile()),
        "存储已回退内部目录，不能声明正常外部用户项目导入");
    SharedPreferences prefs =
        module.getSharedPreferences("practice_wallpaper", Context.MODE_PRIVATE);
    boolean hadSelection = prefs.contains("project");
    String original = prefs.getString("project", null);
    require(!hadSelection || original != null, "原选中项目偏好格式无效");
    List<Path> userRoots =
        List.of(wallpapers.toPath(), new File(target.getFilesDir(), "wallpapers").toPath());
    String oldProjects = fingerprint(userRoots, null);
    Path cache = target.getCacheDir().getCanonicalFile().toPath();
    Path zip = Files.createTempFile(cache, "native-user-wallpaper-", ".zip");
    Path imported = null;
    JSONObject result = null;
    Throwable failure = null;
    try {
      byte[] preview = preview();
      byte[] project =
          new JSONObject()
              .put("type", "web")
              .put("title", TITLE)
              .put("file", "index.html")
              .put("preview", "preview.png")
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
        entry(output, "project.json", project);
        entry(output, "index.html", HTML);
        entry(output, "preview.png", preview);
      }
      AtomicInteger checkpoints = new AtomicInteger();
      Class<?> function = Class.forName("kotlin.jvm.functions.Function0", true, loader);
      Object unit = Class.forName("kotlin.Unit", true, loader).getField("INSTANCE").get(null);
      Object checkpoint =
          Proxy.newProxyInstance(
              loader,
              new Class<?>[] {function},
              (proxy, method, arguments) -> {
                if (method.getName().equals("invoke")) {
                  if (Bootstrap.source() != source) throw new IllegalStateException("导入期间当前业务来源改变");
                  checkpoints.incrementAndGet();
                  return unit;
                }
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == arguments[0];
                if (method.getName().equals("toString")) return "NativeWallpaperCheckpoint";
                throw new UnsupportedOperationException("测试回调出现非预期方法");
              });
      Object title;
      try {
        title =
            storeType
                .getMethod("import", Context.class, Uri.class, boolean.class, function)
                .invoke(store, module, Uri.fromFile(zip.toFile()), false, checkpoint);
      } catch (InvocationTargetException invalid) {
        throw new AssertionError("生产壁纸导入器失败", invalid.getCause());
      }
      // import 返回标题；只有生产偏好和实际目录共同确认新 UUID，不能拿标题冒充项目 id。
      String id = (String) storeType.getMethod("selectedId", Context.class).invoke(store, module);
      require(
          TITLE.equals(title) && id != null && UUID.fromString(id).toString().equals(id),
          "导入未返回实际用户项目");
      require(!id.equals(original) && checkpoints.get() > 0, "生产导入检查点或新选择未生效");
      imported = new File(wallpapers, id).getCanonicalFile().toPath();
      require(
          imported.getParent().equals(wallpapers.toPath()) && Files.isDirectory(imported),
          "新项目不在正常外部目录");
      require(
          imported
                  .toFile()
                  .equals(
                      canonical(
                          storeType.getMethod("current", Context.class).invoke(store, module)))
              && imported
                  .toFile()
                  .equals(
                      canonical(storeType.getMethod("root", Context.class).invoke(store, module))),
          "生产项目定位器没有识别实际导入目录");
      require(
          Files.isRegularFile(imported.resolve(".root"))
              && Files.size(imported.resolve(".root")) == 0,
          "根 ZIP 的生产标记无效");
      require(
          Arrays.equals(project, Files.readAllBytes(imported.resolve("project.json"))),
          "项目元数据字节不符");
      require(Arrays.equals(HTML, Files.readAllBytes(imported.resolve("index.html"))), "用户入口字节不符");
      require(
          Arrays.equals(preview, Files.readAllBytes(imported.resolve("preview.png"))), "用户预览字节不符");
      File visiblePreview =
          (File) storeType.getMethod("preview", File.class).invoke(store, imported.toFile());
      require(imported.resolve("preview.png").toFile().equals(visiblePreview), "生产预览定位器未读取用户预览");
      Bitmap decoded = BitmapFactory.decodeFile(visiblePreview.getAbsolutePath());
      require(decoded != null, "生产目录预览不是可解码图片");
      try {
        require(decoded.getWidth() == 8 && decoded.getHeight() == 8, "预览尺寸不符");
      } finally {
        decoded.recycle();
      }
      JSONArray files = records(imported);
      require(files.length() == 4, "实际用户项目文件数无效");
      long bytes = 0;
      for (int i = 0; i < files.length(); i++) bytes += files.getJSONObject(i).getLong("size");
      require(bytes > 0 && Bootstrap.source() == source, "导入未留下非空当前业务用户项目");
      require(oldProjects.equals(fingerprint(userRoots, imported)), "生产导入改变了既有用户项目文件");
      result =
          new JSONObject()
              .put("passed", true)
              .put("productionTouched", false)
              .put("pid", android.os.Process.myPid())
              .put("importer", storeType.getName())
              .put("sourceIdentity", source.prepared.identity())
              .put("projectId", id)
              .put("projectPath", imported.toString())
              .put("projectHash", fingerprint(List.of(imported), null))
              .put("fileCount", files.length())
              .put("totalBytes", bytes)
              .put("files", files)
              .put("checkpointCalls", checkpoints.get())
              .put("existingProjectsUnchanged", true)
              .put("existingProjectsHash", oldProjects)
              .put("originalSelectionPresent", hadSelection)
              .put("selectionRestored", false)
              .put("updateStateInjected", false)
              .put("activationCalled", false)
              .put("healthInjected", false);
    } catch (Throwable invalid) {
      failure = invalid;
    } finally {
      try {
        restoreSelection(prefs, hadSelection, original);
        if (result != null) result.put("selectionRestored", true);
      } catch (Throwable restoreFailed) {
        if (failure == null) failure = restoreFailed;
        else failure.addSuppressed(restoreFailed);
      }
      try {
        require(
            zip.getParent().equals(cache)
                && zip.getFileName().toString().startsWith("native-user-wallpaper-"),
            "临时 ZIP 不属于本次 fixture");
        Files.deleteIfExists(zip); // 只清理本次 cache 单文件；所有旧、新用户项目均保留。
      } catch (Throwable cleanupFailed) {
        if (failure == null) failure = cleanupFailed;
        else failure.addSuppressed(cleanupFailed);
      }
    }
    if (failure != null) throw new AssertionError("用户壁纸 fixture 未完成", failure);
    require(result != null && result.getBoolean("selectionRestored"), "不得生成未恢复选择的成功回执");
    writeReport(target, report, result);
    return result;
  }

  /** 只通过生产选择和真实演练场渲染既有 fixture；不调用更新、许可或健康入口。 */
  static JSONObject render(Instrumentation runner, Activity home, String projectId)
      throws Exception {
    Context target = testTarget(runner, home);
    Path report =
        new File(target.getFilesDir(), "native-user-wallpaper-render-report.json").toPath();
    Path image = new File(target.getFilesDir(), "native-user-wallpaper-render.png").toPath();
    Files.deleteIfExists(report);
    Files.deleteIfExists(image);
    require(
        projectId != null && UUID.fromString(projectId).toString().equals(projectId),
        "渲染项目 id 不是实际 UUID");
    require(!PracticeBridge.active(), "已有演练场不能被 fixture 接管");
    Bootstrap.Source source = Bootstrap.source();
    Context module = moduleContext(runner, home, source);
    ClassLoader loader = source.prepared.classLoader();
    Class<?> storeType =
        Class.forName("app.luoxianlv.wallpaper.WallpaperProjectStore", true, loader);
    Object store = storeType.getField("INSTANCE").get(null);
    Class<?> entryType = Class.forName(storeType.getName() + "$Entry", true, loader);
    File external = target.getExternalFilesDir(null);
    require(external != null, "渲染 fixture 缺少正常外部项目目录");
    File wallpapers = new File(external, "wallpapers").getCanonicalFile();
    Path project = new File(wallpapers, projectId).getCanonicalFile().toPath();
    require(
        project.getParent().equals(wallpapers.toPath()) && Files.isDirectory(project),
        "用户项目没有保存在外部目录");
    // 只接受本 helper 生产导入的已知离线 HTML，不向任意用户项目注入脚本或替换文件。
    JSONObject metadata =
        new JSONObject(Files.readString(project.resolve("project.json"), StandardCharsets.UTF_8));
    require(
        "web".equals(metadata.getString("type"))
            && TITLE.equals(metadata.getString("title"))
            && "index.html".equals(metadata.getString("file"))
            && Arrays.equals(HTML, Files.readAllBytes(project.resolve("index.html")))
            && Files.size(project.resolve(".root")) == 0,
        "项目不是已导入的非空用户 web fixture");
    JSONArray files = records(project);
    require(files.length() == 4, "用户 fixture 的实际文件集合不符");
    List<Path> userRoots =
        List.of(wallpapers.toPath(), new File(target.getFilesDir(), "wallpapers").toPath());
    String allProjects = fingerprint(userRoots, null);
    String projectHash = fingerprint(List.of(project), null);
    Object selectedEntry = null;
    for (Object entry :
        (List<?>) storeType.getMethod("entries", Context.class).invoke(store, module)) {
      if (projectId.equals(entryType.getMethod("getId").invoke(entry))) {
        require(
            project.toFile().equals(canonical(entryType.getMethod("getRoot").invoke(entry))),
            "生产项目列表指向其他目录");
        selectedEntry = entry;
      }
    }
    require(selectedEntry != null, "生产项目列表没有识别 UUID");
    SharedPreferences prefs =
        module.getSharedPreferences("practice_wallpaper", Context.MODE_PRIVATE);
    boolean hadSelection = prefs.contains("project");
    String original = prefs.getString("project", null);
    require(!hadSelection || original != null, "原选中项目偏好格式无效");
    Activity stage = null;
    JSONObject result = null;
    Throwable failure = null;
    try {
      storeType.getMethod("select", Context.class, entryType).invoke(store, module, selectedEntry);
      require(
          projectId.equals(storeType.getMethod("selectedId", Context.class).invoke(store, module))
              && project
                  .toFile()
                  .equals(
                      canonical(storeType.getMethod("root", Context.class).invoke(store, module))),
          "生产选择未定位用户项目");
      stage =
          runner.startActivitySync(
              new Intent()
                  .setClassName(target, "app.luoxianlv.ui.practice.PracticeActivity")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      require(stage != null, "实际演练场未启动");
      Activity playing = stage;
      Class<?> backdropType =
          Class.forName("app.luoxianlv.wallpaper.PracticeBackdrop", true, loader);
      var projectField = backdropType.getDeclaredField("project");
      var webField = backdropType.getDeclaredField("web");
      var posterField = backdropType.getDeclaredField("posterView");
      projectField.setAccessible(true);
      webField.setAccessible(true);
      posterField.setAccessible(true);
      AtomicReference<View> backdrop = new AtomicReference<>();
      AtomicReference<WebView> browser = new AtomicReference<>();
      long began = SystemClock.elapsedRealtime();
      awaitMain(
          runner,
          90000,
          "用户项目演练场首帧未就绪",
          () -> {
            require(Bootstrap.source() == source, "渲染期间业务来源改变");
            require(!playing.isFinishing() && !playing.isDestroyed(), "演练场在首帧前关闭");
            View view = view(playing.getWindow().getDecorView(), backdropType);
            if (view == null) return false;
            require(
                backdropType.getMethod("getOfficialFailure").invoke(view) == null, "实际官方渲染资源失败");
            String state = (String) backdropType.getMethod("getRenderState").invoke(view);
            require(!"error".equals(state) && !"static".equals(state), "预览或空背景不能算用户 web 渲染成功");
            if (!PracticeBridge.ready() || !playing.hasWindowFocus() || !"ready".equals(state))
              return false;
            require(
                project.toFile().equals(canonical(projectField.get(view))),
                "Backdrop 实际读取的不是该用户项目");
            WebView web = (WebView) webField.get(view);
            require(web != null && "wallpaper:ready".equals(web.getTitle()), "生产 WebView 没有发出首帧标题");
            require(
                ((Boolean) backdropType.getMethod("getPrepared").invoke(view))
                    && ((ImageView) posterField.get(view)).getDrawable() == null,
                "实际视图仍是预览回退");
            if (!web.isShown()
                || !web.isAttachedToWindow()
                || web.getAlpha() < .99f
                || web.getWidth() <= 0
                || web.getHeight() <= 0) return false;
            Rect visible = new Rect();
            if (!web.getGlobalVisibleRect(visible) || visible.width() <= 0 || visible.height() <= 0)
              return false;
            backdrop.set(view);
            browser.set(web);
            return true;
          });
      AtomicBoolean visualState = new AtomicBoolean();
      onMain(
          runner,
          () ->
              browser
                  .get()
                  .postVisualStateCallback(
                      1,
                      new WebView.VisualStateCallback() {
                        @Override
                        public void onComplete(long requestId) {
                          if (requestId == 1) visualState.set(true);
                        }
                      }));
      awaitMain(
          runner,
          15000,
          "用户 WebView 没有提交可见视觉状态",
          () -> visualState.get() && PracticeBridge.ready() && playing.hasWindowFocus());
      // 视觉状态回调后再取实际屏幕合成帧；不是 View.draw，也不修改页面 JS/标题。
      SystemClock.sleep(250);
      AtomicReference<Rect> bounds = new AtomicReference<>();
      onMain(
          runner,
          () -> {
            require(
                Bootstrap.source() == source && PracticeBridge.ready() && playing.hasWindowFocus(),
                "取帧前演练场或业务来源改变");
            require(
                "ready".equals(backdropType.getMethod("getRenderState").invoke(backdrop.get()))
                    && "wallpaper:ready".equals(browser.get().getTitle())
                    && browser.get().isShown()
                    && browser.get().getAlpha() >= .99f
                    && ((ImageView) posterField.get(backdrop.get())).getDrawable() == null,
                "取帧前已退回空背景或预览");
            Rect visible = new Rect();
            require(
                browser.get().getLocalVisibleRect(visible) && !visible.isEmpty(),
                "真实 WebView 已不可见");
            int[] position = new int[2];
            browser.get().getLocationOnScreen(position);
            visible.offset(position[0], position[1]);
            bounds.set(visible);
          });
      JSONObject frame = captureFrame(runner, image, bounds.get());
      require(
          Bootstrap.source() == source && projectId.equals(prefs.getString("project", null)),
          "取得实际帧期间业务来源或用户选择改变");
      require(allProjects.equals(fingerprint(userRoots, null)), "渲染改变了用户项目文件");
      long bytes = 0;
      for (int i = 0; i < files.length(); i++) bytes += files.getJSONObject(i).getLong("size");
      result =
          new JSONObject()
              .put("passed", true)
              .put("productionTouched", false)
              .put("pid", android.os.Process.myPid())
              .put("sourceIdentity", source.prepared.identity())
              .put("selector", storeType.getName() + ".select")
              .put("activity", playing.getClass().getName())
              .put("projectId", projectId)
              .put("projectPath", project.toString())
              .put("projectHash", projectHash)
              .put("fileCount", files.length())
              .put("totalBytes", bytes)
              .put("files", files)
              .put("backdropProjectPath", project.toString())
              .put("renderState", "ready")
              .put("prepared", true)
              .put("practiceReady", true)
              .put("webTitle", "wallpaper:ready")
              .put("webVisualStateCallback", true)
              .put("previewCleared", true)
              .put("visibleFrame", frame)
              .put("elapsedMs", SystemClock.elapsedRealtime() - began)
              .put("evidence", "production-user-web-first-frame-and-screen-pixels")
              .put("continuousAnimationVerified", false)
              .put("audioVerified", false)
              .put("allUserProjectsUnchanged", true)
              .put("allUserProjectsHash", allProjects)
              .put("originalSelectionPresent", hadSelection)
              .put("selectionRestored", false)
              .put("stageClosed", false)
              .put("updateStateInjected", false)
              .put("activationCalled", false)
              .put("healthInjected", false);
    } catch (Throwable invalid) {
      failure = invalid;
    } finally {
      // 先恢复偏好，使首页恢复时按原选择预加载；已经创建的 Backdrop 不会改读其他项目。
      try {
        restoreSelection(prefs, hadSelection, original);
        if (result != null) result.put("selectionRestored", true);
      } catch (Throwable restoreFailed) {
        if (failure == null) failure = restoreFailed;
        else failure.addSuppressed(restoreFailed);
      }
      if (stage != null) {
        try {
          Activity closing = stage;
          onMain(
              runner,
              () -> {
                if (!closing.isDestroyed()) closing.finish();
              });
          awaitMain(
              runner, 15000, "测试演练场未释放", () -> closing.isDestroyed() && !PracticeBridge.active());
          if (result != null) result.put("stageClosed", true);
        } catch (Throwable closeFailed) {
          if (failure == null) failure = closeFailed;
          else failure.addSuppressed(closeFailed);
        }
      }
    }
    if (failure != null) throw new AssertionError("用户壁纸实际渲染未完成", failure);
    require(
        result != null
            && result.getBoolean("selectionRestored")
            && result.getBoolean("stageClosed"),
        "不得生成未恢复选择或未关闭演练场的成功回执");
    require(allProjects.equals(fingerprint(userRoots, null)), "退出演练场改变了用户项目文件");
    writeReport(target, report, result);
    return result;
  }

  private static JSONObject captureFrame(Instrumentation runner, Path output, Rect bounds)
      throws Exception {
    Bitmap screen = runner.getUiAutomation().takeScreenshot();
    require(screen != null, "无法取得实际屏幕合成帧");
    Bitmap frame = null;
    try {
      require(
          bounds.left >= 0
              && bounds.top >= 0
              && bounds.right <= screen.getWidth()
              && bounds.bottom <= screen.getHeight(),
          "WebView 屏幕范围与截图方向不一致");
      frame = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height());
      long matching = 0, samples = 0;
      // 真实键盘始终叠加 alpha=130 的 (6,8,7) 暗底；上下边缘避开控件后的渐变。
      int expectedRed = (48 * 125 + 6 * 130 + 127) / 255;
      int expectedGreen = (72 * 125 + 8 * 130 + 127) / 255;
      int expectedBlue = (92 * 125 + 7 * 130 + 127) / 255;
      for (int y = 0; y < frame.getHeight(); y += 4)
        for (int x = 0; x < frame.getWidth(); x += 4) {
          if (y >= frame.getHeight() * .16 && y < frame.getHeight() * .84) continue;
          int color = frame.getPixel(x, y);
          samples++;
          if (Math.abs(Color.red(color) - expectedRed) <= 8
              && Math.abs(Color.green(color) - expectedGreen) <= 8
              && Math.abs(Color.blue(color) - expectedBlue) <= 8) matching++;
        }
      try (var stream = Files.newOutputStream(output)) {
        require(frame.compress(Bitmap.CompressFormat.PNG, 100, stream), "真实屏幕帧保存失败");
      }
      require(
          samples > 0 && matching * 100 >= samples,
          "真实屏幕帧没有非空用户 HTML 背景：匹配="
              + matching
              + "/"
              + samples
              + "；诊断帧="
              + output
              + "，不能把引擎 ready 视为空帧成功");
      return new JSONObject()
          .put("path", output.toString())
          .put("sha256", hash(output))
          .put("size", Files.size(output))
          .put("width", frame.getWidth())
          .put("height", frame.getHeight())
          .put("sampleCount", samples)
          .put("fixtureBackgroundSamples", matching)
          .put("fixtureBackgroundMinPercent", 1)
          .put("sampling", "top-and-bottom-16-percent")
          .put("fixtureBackgroundRgb", new JSONArray(new int[] {48, 72, 92}))
          .put("keyboardDimAlpha", 130)
          .put(
              "compositedExpectedRgb",
              new JSONArray(new int[] {expectedRed, expectedGreen, expectedBlue}))
          .put("source", "UiAutomation.takeScreenshot");
    } finally {
      if (frame != null && frame != screen) frame.recycle();
      screen.recycle();
    }
  }

  private interface MainCheck {
    boolean get() throws Exception;
  }

  private interface MainAction {
    void run() throws Exception;
  }

  private static void onMain(Instrumentation runner, MainAction action) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runner.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable invalid) {
            failure.set(invalid);
          }
        });
    if (failure.get() != null) throw new AssertionError("用户壁纸主线程检查失败", failure.get());
  }

  private static void awaitMain(
      Instrumentation runner, long timeout, String message, MainCheck check) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    AtomicBoolean ready = new AtomicBoolean();
    while (SystemClock.elapsedRealtime() < deadline) {
      dismissFullscreenHint(runner);
      onMain(runner, () -> ready.set(check.get()));
      if (ready.get()) return;
      SystemClock.sleep(50);
    }
    throw new AssertionError(message);
  }

  @SuppressWarnings("deprecation") // API 26 仍需回收节点；较新 SDK 的 recycle 已是空操作。
  private static void dismissFullscreenHint(Instrumentation runner) {
    AccessibilityNodeInfo root = runner.getUiAutomation().getRootInActiveWindow();
    if (root == null) return;
    // 只关闭系统首次全屏提示；不自动点击应用许可、更新或健康操作。
    List<AccessibilityNodeInfo> titles =
        root.findAccessibilityNodeInfosByText("Viewing full screen");
    List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByText("Got it");
    try {
      if (titles.stream()
          .noneMatch(node -> "com.android.systemui".contentEquals(node.getPackageName()))) return;
      for (AccessibilityNodeInfo node : buttons)
        if ("com.android.systemui".contentEquals(node.getPackageName()))
          require(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "系统首次全屏提示无法关闭");
    } finally {
      for (AccessibilityNodeInfo node : titles) node.recycle();
      for (AccessibilityNodeInfo node : buttons) node.recycle();
      root.recycle();
    }
  }

  private static View view(View root, Class<?> type) {
    if (root.getClass() == type) return root;
    if (root instanceof ViewGroup) {
      ViewGroup parent = (ViewGroup) root;
      for (int i = 0; i < parent.getChildCount(); i++) {
        View found = view(parent.getChildAt(i), type);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static Context testTarget(Instrumentation runner, Activity home) {
    Context target = runner.getTargetContext();
    var startup = Bootstrap.startupState();
    require(
        home != null
            && target.getPackageName().equals("app.luoxianlv.debug")
            && (target.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && startup != null
            && startup.config.environment.equals("test")
            && startup.config.origin.toString().equals("http://127.0.0.1:18472")
            && Looper.myLooper() != Looper.getMainLooper(),
        "用户壁纸 fixture 仅允许 Debug 本机测试工作线程");
    return target;
  }

  private static Context moduleContext(
      Instrumentation runner, Activity home, Bootstrap.Source source) throws Exception {
    AtomicReference<Context> result = new AtomicReference<>();
    onMain(
        runner,
        () -> {
          require(Bootstrap.source() == source, "业务来源在创建资源上下文前改变");
          result.set(source.prepared.context(home));
        });
    return result.get();
  }

  private static File canonical(Object value) throws Exception {
    return value == null ? null : ((File) value).getCanonicalFile();
  }

  private static void restoreSelection(SharedPreferences prefs, boolean present, String original) {
    SharedPreferences.Editor restore = prefs.edit();
    if (present) restore.putString("project", original);
    else restore.remove("project");
    require(restore.commit(), "无法恢复原用户壁纸选择");
    require(
        prefs.contains("project") == present
            && Objects.equals(original, prefs.getString("project", null)),
        "原用户壁纸选择未完整恢复");
  }

  private static void writeReport(Context target, Path report, JSONObject result) throws Exception {
    Path temporary =
        Files.createTempFile(
            target.getFilesDir().toPath(), "native-user-wallpaper-report-", ".tmp");
    try {
      Files.write(temporary, result.toString(2).getBytes(StandardCharsets.UTF_8));
      Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static byte[] preview() throws Exception {
    Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
    try {
      bitmap.eraseColor(Color.rgb(48, 72, 92));
      var bytes = new ByteArrayOutputStream();
      require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes), "测试 PNG 创建失败");
      return bytes.toByteArray();
    } finally {
      bitmap.recycle();
    }
  }

  private static void entry(ZipOutputStream output, String name, byte[] bytes) throws Exception {
    output.putNextEntry(new ZipEntry(name));
    output.write(bytes);
    output.closeEntry();
  }

  private static JSONArray records(Path root) throws Exception {
    JSONArray result = new JSONArray();
    if (!Files.exists(root)) return result;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted().collect(Collectors.toList())) {
        require(!Files.isSymbolicLink(path), "用户壁纸含链接，不能跟随");
        if (!Files.isRegularFile(path)) continue;
        require(path.toRealPath().startsWith(root.toRealPath()), "用户项目文件越界");
        result.put(
            new JSONObject()
                .put("path", root.relativize(path).toString().replace(File.separatorChar, '/'))
                .put("size", Files.size(path))
                .put("sha256", hash(path)));
      }
    }
    return result;
  }

  private static String fingerprint(List<Path> roots, Path excluded) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (int index = 0; index < roots.size(); index++) {
      Path root = roots.get(index);
      if (!Files.exists(root)) continue;
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted().collect(Collectors.toList())) {
          if (excluded != null && path.startsWith(excluded)) continue;
          require(!Files.isSymbolicLink(path), "用户壁纸含链接，不能跟随");
          if (!Files.isRegularFile(path)) continue;
          require(path.toRealPath().startsWith(root.toRealPath()), "用户项目文件越界");
          digest.update(
              (index
                      + ":"
                      + root.relativize(path).toString().replace(File.separatorChar, '/')
                      + ":"
                      + Files.size(path)
                      + ":"
                      + hash(path)
                      + "\n")
                  .getBytes(StandardCharsets.UTF_8));
        }
      }
    }
    return hex(digest.digest());
  }

  private static String hash(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] bytes = new byte[32768];
      int count;
      while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
    }
    return hex(digest.digest());
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte value : bytes)
      result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
