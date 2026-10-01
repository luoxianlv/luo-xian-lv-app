package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import app.luoxianlv.hot.ActivationJournal;
import app.luoxianlv.hot.HotManifest;
import app.luoxianlv.hot.contract.OfficialAssets;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** 仅观察普通自动链路和真实资源消费者；不调用激活，不伪造首帧或健康时钟。 */
final class NativeResourceChecks {
  static void rejected(Instrumentation runner, Activity home, String bad, String stable)
      throws Exception {
    var state = Bootstrap.startupState();
    require(
        state != null
            && state.config.environment.equals("test")
            && state.config.origin.toString().equals("http://127.0.0.1:18472")
            && HotManifest.validHash(bad)
            && HotManifest.validHash(stable),
        "仅允许本机资源回退验收");
    String userBefore = userWallpaperFingerprint(runner.getTargetContext());
    await(
        "损坏渲染资源未隔离并恢复稳定版本",
        90000,
        () -> {
          var ready = new AtomicReference<Boolean>(false);
          runner.runOnMainSync(
              () -> {
                var current = state.journal.state();
                try {
                  ready.set(
                      current.quarantine.contains(bad)
                          && current.stable.equals(stable)
                          && current.phase == ActivationJournal.Phase.STABLE
                          && Bootstrap.source().prepared.identity().equals(stable)
                          && !Bootstrap.businessStopped()
                          && Bootstrap.resourcesReady()
                          && home.hasWindowFocus()
                          && Bootstrap.pages().stream().allMatch(page -> page.host().readyFrame()));
                } catch (IllegalStateException waiting) {
                  /* 等待正常恢复首帧。 */
                }
              });
          return ready.get();
        });
    require(
        userBefore.equals(userWallpaperFingerprint(runner.getTargetContext())), "资源回退改变了用户壁纸文件");
    var current = state.journal.state();
    var report =
        new JSONObject()
            .put("passed", true)
            .put("productionTouched", false)
            .put("rejected", bad)
            .put("stable", stable)
            .put("pid", android.os.Process.myPid())
            .put("quarantined", current.quarantine.contains(bad))
            .put("visibleStableFrame", true)
            .put("userWallpaperFingerprint", userBefore)
            .put("userWallpaperUnchanged", true)
            .put("explicitActivationCalled", false)
            .put("revision", current.revision)
            .put("trustVersion", current.trustVersion);
    Files.write(
        new File(runner.getTargetContext().getFilesDir(), "native-resource-rollback-report.json")
            .toPath(),
        report.toString(2).getBytes(StandardCharsets.UTF_8));
  }

  private static String userWallpaperFingerprint(Context context) throws Exception {
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    File external = context.getExternalFilesDir("wallpapers");
    File[] roots =
        external == null
            ? new File[] {new File(context.getFilesDir(), "wallpapers")}
            : new File[] {external, new File(context.getFilesDir(), "wallpapers")};
    for (File root : roots) {
      if (!root.exists()) continue;
      try (var paths = Files.walk(root.toPath())) {
        for (var path : paths.sorted().toList()) {
          require(!Files.isSymbolicLink(path), "用户壁纸包含链接，不能跟随");
          if (!Files.isRegularFile(path)) continue;
          digest.update(root.toPath().relativize(path).toString().getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
          try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
          }
        }
      }
    }
    return java.util.Base64.getEncoder().encodeToString(digest.digest());
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void await(String message, long timeout, BooleanSupplier predicate) {
    long until = SystemClock.elapsedRealtime() + timeout;
    while (!predicate.getAsBoolean()) {
      require(SystemClock.elapsedRealtime() < until, message);
      SystemClock.sleep(100);
    }
  }

  static void run(Instrumentation runner, Activity home, String target) throws Exception {
    var state = Bootstrap.startupState();
    require(
        runner.getTargetContext().getPackageName().equals("app.luoxianlv.debug")
            && HotManifest.validHash(target)
            && state != null
            && state.config.automatic
            && state.config.environment.equals("test")
            && state.config.origin.toString().equals("http://127.0.0.1:18472"),
        "仅允许本机资源验收");
    await(
        "普通入口没有显示资源候选",
        90000,
        () -> {
          var ready = new AtomicReference<Boolean>(false);
          runner.runOnMainSync(
              () -> {
                try {
                  ready.set(
                      target.equals(Bootstrap.source().prepared.identity())
                          && Bootstrap.resourcesReady());
                } catch (IllegalStateException waiting) {
                  /* 普通异步加载尚未完成。 */
                }
              });
          return ready.get();
        });
    long exposedAt = SystemClock.elapsedRealtime();
    var prepared = Bootstrap.source().prepared;
    var context = new AtomicReference<Context>();
    var mainFailure = new AtomicReference<Throwable>();
    runner.runOnMainSync(
        () -> {
          try {
            require(Bootstrap.resourcesReady(), "资源首帧检查尚未就绪");
            context.set(prepared.context(home));
          } catch (Throwable failure) {
            mainFailure.set(failure);
          }
        });
    if (mainFailure.get() != null) throw new AssertionError("资源上下文尚未可用", mainFailure.get());
    Context module = context.get();
    var source = OfficialAssets.source(module);
    require(source != null && source.identity().equals(target), "资源范围与候选身份不一致");
    for (String mount : new String[] {"harmonica", "wallpaperengine", "shaders", "theme", "config"})
      require(source.mounted(mount), "正式挂载没有交给业务");
    var loader = prepared.classLoader();
    JSONObject config = new JSONObject(OfficialAssets.text(module, "config", "value", "", 65536));
    var configType = Class.forName("app.luoxianlv.business.ui.OfficialRuntimeConfig", true, loader);
    var values =
        configType
            .getMethod("read", Context.class)
            .invoke(configType.getField("INSTANCE").get(null), module);
    float strength = (Float) values.getClass().getMethod("getShaderStrength").invoke(values);
    float gain = (Float) values.getClass().getMethod("getHarmonicaGain").invoke(values);
    require(
        Math.abs(strength - config.getInt("shaderStrengthPermille") / 1000f) < .00001f
            && Math.abs(gain - config.getInt("harmonicaGainPermille") / 1000f) < .00001f,
        "业务配置消费者没有读取当前资源");

    var sampler = Class.forName("app.luoxianlv.audio.HarmonicaSampler", true, loader);
    var companion = sampler.getField("Companion").get(null);
    Map<?, ?> samples =
        (Map<?, ?>) companion.getClass().getMethod("load", Context.class).invoke(companion, module);
    require(samples.size() == 38, "真实口琴消费者没有加载完整音色");
    for (int note = 48; note <= 85; note++) {
      Object sample = samples.get(note);
      short[] actual = (short[]) sample.getClass().getMethod("getPcm").invoke(sample);
      byte[] bytes = OfficialAssets.read(module, "harmonica", note + ".pcm", "", 16 << 20);
      require(bytes.length == actual.length * 2, "消费者音色长度不符");
      var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
      for (short value : actual) require(buffer.get() == value, "消费者音色字节不符");
    }

    JSONObject palette = new JSONObject(OfficialAssets.text(module, "theme", "value", "", 65536));
    var paletteType = Class.forName("app.luoxianlv.ui.theme.OfficialPalette", true, loader);
    var paletteCompanion = paletteType.getField("Companion").get(null);
    var actualPalette =
        paletteCompanion
            .getClass()
            .getMethod("load", Context.class)
            .invoke(paletteCompanion, module);
    var themeType = Class.forName("app.luoxianlv.ui.theme.ThemeKt", true, loader);
    var lightField = themeType.getDeclaredField("LightColors");
    lightField.setAccessible(true);
    var base = lightField.get(null);
    var colors =
        paletteType
            .getMethod("apply", boolean.class, base.getClass())
            .invoke(actualPalette, false, base);
    Method primary = null, toArgb = null;
    for (var method : colors.getClass().getMethods())
      if (method.getName().startsWith("getPrimary-") && method.getParameterCount() == 0)
        primary = method;
    var colorTools = Class.forName("androidx.compose.ui.graphics.ColorKt", true, loader);
    for (var method : colorTools.getMethods())
      if (method.getName().startsWith("toArgb-") && method.getParameterCount() == 1)
        toArgb = method;
    require(primary != null && toArgb != null, "真实Compose颜色方法缺失");
    int argb = (Integer) toArgb.invoke(null, primary.invoke(colors));
    require(
        argb
            == (int)
                Long.parseLong(
                    palette.getJSONObject("light").getString("primary").substring(1), 16),
        "主题消费者没有应用当前资源颜色");
    runner.runOnMainSync(
        () -> {
          try {
            var sphereType =
                Class.forName("app.luoxianlv.ui.practice.WhiteSphereShader", true, loader);
            var sphere = sphereType.getDeclaredConstructor(Context.class).newInstance(module);
            var strengthField = sphereType.getDeclaredField("strength");
            strengthField.setAccessible(true);
            require(
                Math.abs(strengthField.getFloat(sphere) - strength) < .00001f, "着色器消费者没有使用当前配置");
          } catch (Throwable failure) {
            mainFailure.set(failure);
          }
        });
    if (mainFailure.get() != null) throw new AssertionError("实际AGSL消费者构造失败", mainFailure.get());
    await(
        "资源候选未完成真实健康观察",
        90000,
        () ->
            state.journal.state().phase == ActivationJournal.Phase.STABLE
                && state.journal.state().stable.equals(target));
    require(SystemClock.elapsedRealtime() - exposedAt >= 55000, "资源验收未经历真实使用观察");
    var report =
        new JSONObject()
            .put("passed", true)
            .put("productionTouched", false)
            .put("snapshot", target)
            .put("runtimeHash", prepared.manifest.runtime.sha256)
            .put("businessHash", prepared.manifest.business.sha256)
            .put("pid", android.os.Process.myPid())
            .put("resourceScope", source.identity())
            .put("pcmNotes", samples.size())
            .put("primaryArgb", String.format("#%08X", argb))
            .put("shaderStrength", strength)
            .put("harmonicaGain", gain)
            .put("actualAgslConsumerConstructed", true)
            .put("rendererFixedImageVisualGate", true)
            .put("allWallpaperProjectsVerified", false)
            .put("explicitActivationCalled", false)
            .put("observationWallMillis", SystemClock.elapsedRealtime() - exposedAt);
    Files.write(
        new File(runner.getTargetContext().getFilesDir(), "native-resource-report.json").toPath(),
        report.toString(2).getBytes(StandardCharsets.UTF_8));
  }
}
