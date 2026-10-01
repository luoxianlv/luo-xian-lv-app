package app.luoxianlv.tools;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** 正常 Release 原字节副本的独立同证书仪器；不导入目标/业务代码、不更改隐私选择。 */
public final class NativeNormalReleaseInstrumentation extends Instrumentation {
  private static final String TARGET = "app.luoxianlv";
  private final JSONObject report = new JSONObject();
  private Bundle arguments;
  private Activity home;
  private View compose;
  private ClassLoader host, runtime, business;
  private Method completed, initAt, entries;
  private Object analytics, disclaimer;
  private Method agreed;
  private String disclaimerHash;

  @Override public void onCreate(Bundle input) { super.onCreate(input); arguments = input; start(); }
  private static final class Check extends AssertionError { Check(String value) { super(value); } }
  private void check(boolean value, String message) { if (!value) throw new Check(message); }
  private String argument(String name) {
    String value = arguments == null ? null : arguments.getString(name);
    check(value != null && value.matches("[a-f0-9]{64}"), "Missing public artifact/certificate identity");
    return value;
  }
  private void main(Runnable action) {
    var failure = new AtomicReference<Throwable>();
    runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
    if (failure.get() != null) throw new AssertionError("Normal Release main observation failed", failure.get());
  }
  private static String hash(InputStream input) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[32768]; long total = 0;
    for (int count; (count = input.read(buffer)) != -1;) {
      if (count <= 0 || (total += count) > (512L << 20)) throw new Check("Artifact read exceeds bound");
      digest.update(buffer, 0, count);
    }
    return hex(digest.digest());
  }
  private static String hex(byte[] bytes) {
    var text = new StringBuilder();
    for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255));
    return text.toString();
  }
  private boolean offline(Context context) {
    var manager = context.getSystemService(ConnectivityManager.class);
    if (manager == null) return true;
    for (var network : manager.getAllNetworks()) {
      var caps = manager.getNetworkCapabilities(network);
      if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
    }
    return true;
  }
  private View compose(View view) {
    if (view.getClass().getName().equals("androidx.compose.ui.platform.ComposeView")) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) { View found = compose(group.getChildAt(i)); if (found != null) return found; }
    return null;
  }
  private void absentFromHost(String name) throws Exception {
    try { Class.forName(name, false, host); } catch (ClassNotFoundException expected) { return; }
    throw new Check("Host unexpectedly defines a runtime type");
  }
  private String certificate(Context context, String packageName) throws Exception {
    var signatures = context.getPackageManager().getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures;
    check(signatures != null && signatures.length == 1, "Single fixture certificate required");
    var certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(signatures[0].toByteArray()));
    check(certificate.getSubjectX500Principal().getName().contains("CN=Android Debug"), "Only local Android Debug fixture certificates allowed");
    return hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
  }
  private void privacy() {
    main(() -> {
      try {
        check(!disclaimerHash.equals(agreed.invoke(disclaimer)), "Privacy consent changed during test");
        check(completed.invoke(null, "analytics-initialize") == null && initAt.invoke(analytics) == null,
            "Formal analytics initialization completed without consent");
        for (Object entry : (List<?>) entries.invoke(analytics)) {
          var type = entry.getClass();
          check(!("SDK".equals(type.getMethod("getKind").invoke(entry))
              && "initialize 调用".equals(type.getMethod("getDetail").invoke(entry))),
              "Formal analytics initialization was attempted without consent");
        }
      } catch (ReflectiveOperationException error) { throw new AssertionError("Privacy reflection failed", error); }
    });
  }
  private void componentAndServiceBoundary(Context context) throws Exception {
    var pm = context.getPackageManager();
    String wallpaper = "app.luoxianlv.ui.practice.WallpaperPickerActivity";
    var activity = pm.getActivityInfo(new ComponentName(TARGET, wallpaper), 0);
    check(!activity.exported && Class.forName(wallpaper, false, host).getClassLoader() == host,
        "Normal wallpaper component boundary differs");
    var accessibility = pm.getServiceInfo(new ComponentName(TARGET, "app.luoxianlv.service.MusicAccessibilityService"), 0);
    var foreground = pm.getServiceInfo(new ComponentName(TARGET, "app.luoxianlv.service.PlaybackForegroundService"), 0);
    check(accessibility.exported && "android.permission.BIND_ACCESSIBILITY_SERVICE".equals(accessibility.permission)
        && !foreground.exported && (foreground.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) != 0,
        "Normal accessibility/foreground declaration differs");
    for (String name : List.of(accessibility.name, foreground.name)) {
      var service = Class.forName(name, false, host);
      check(service.getClassLoader() == host && Service.class.isAssignableFrom(service), "Normal service is not host-defined");
    }
    var port = Class.forName("app.luoxianlv.hot.contract.PlaybackPort", false, host);
    var session = Class.forName("app.luoxianlv.hot.contract.NativePlaybackSession", false, host);
    var bridge = Class.forName("app.luoxianlv.hot.contract.PlaybackBridge", false, host);
    check(port.isInterface() && port.getClassLoader() == host && session.isInterface() && session.getClassLoader() == host
        && port.getMethod("query", String.class).getReturnType() == Bundle.class
        && port.getMethod("command", String.class, Bundle.class).getReturnType() == void.class,
        "Stable playback SDK is not host-owned/basic-value based");
    main(() -> {
      try {
        Object connection = bridge.getMethod("current").invoke(null);
        report.put("serviceSdkBoundaryVerified", true);
        report.put("serviceConnectedObserved", connection != null);
        if (connection != null) {
          Object state = port.getMethod("query", String.class).invoke(connection, "state");
          check(state instanceof Bundle, "Existing service did not return basic state Bundle");
          report.put("serviceStateQueryObserved", true);
        } else report.put("serviceStateQueryObserved", false);
        report.put("serviceEnabledByTest", false);
      } catch (Exception error) { throw new AssertionError("Service SDK observation failed", error); }
    });
    report.put("wallpaperComponentVerified", true);
    report.put("wallpaperEntryStatus", "blocked-by-unconsented-home");
    report.put("wallpaperScreenRendered", false);
    report.put("foregroundServiceLifecycleVerified", false);
    report.put("gesturePlaybackVerified", false);
  }

  @Override public void onStart() {
    Context target = getTargetContext(); boolean passed = false;
    long started = SystemClock.elapsedRealtime();
    try {
      check(TARGET.equals(target.getPackageName()) && "app.luoxianlv.normalrelease.test".equals(getContext().getPackageName()), "Wrong normal/test package");
      var info = target.getApplicationInfo();
      check((info.flags & (ApplicationInfo.FLAG_DEBUGGABLE | ApplicationInfo.FLAG_TEST_ONLY)) == 0, "Normal manifest flags changed");
      check(offline(target), "Root must remove emulator Internet before instrumentation");
      String cert = argument("certificateSha256"), installed = argument("installedHostSha256");
      check(cert.equals(certificate(target, TARGET)) && cert.equals(certificate(getContext(), getContext().getPackageName())), "Host/test fixture certificates differ");
      try (var input = new FileInputStream(info.sourceDir)) { check(installed.equals(hash(input)), "Installed signed Host bytes differ"); }
      for (String role : List.of("runtime", "business"))
        try (var input = target.getAssets().open("baseline/" + role + ".apk")) {
          check(argument(role + "Sha256").equals(hash(input)), "Bundled normal module bytes differ");
        }
      try (var input = target.getAssets().open("hot/config.json")) { throw new Check("Normal baseline must not contain test/production hot config"); }
      catch (FileNotFoundException expected) { }
      report.put("schema", 1).put("profile", "normal-release-payload-local-fixture-signature")
          .put("targetPackage", TARGET).put("sourceHostSha256", argument("sourceHostSha256"))
          .put("installedHostSha256", installed).put("certificateSha256", cert)
          .put("runtimeSha256", argument("runtimeSha256")).put("businessSha256", argument("businessSha256"))
          .put("debuggable", false).put("testOnly", false).put("sdkInt", Build.VERSION.SDK_INT)
          .put("pid", android.os.Process.myPid()).put("normalReleaseReady", false).put("productionTouched", false);
      host = target.getClassLoader();
      var once = Class.forName("app.luoxianlv.hot.contract.ProcessOnce", false, host);
      check(once.getClassLoader() == host, "Stable SDK is not installed-host owned");
      completed = once.getMethod("completedAt", String.class);
      check(completed.invoke(null, "analytics-initialize") == null, "Formal initialization preceded normal Activity");
      home = startActivitySync(new Intent().setClassName(TARGET, "app.luoxianlv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long until = SystemClock.elapsedRealtime() + 60000;
      do {
        main(() -> {
          View found = compose(home.getWindow().getDecorView());
          if (found != null && found.isShown() && found.getWidth() > 0 && found.getHeight() > 0) compose = found;
        });
        check(SystemClock.elapsedRealtime() < until, "Normal Release Compose page did not become visible");
        if (compose == null) SystemClock.sleep(50);
      } while (compose == null);
      main(() -> { runtime = compose.getClass().getClassLoader(); business = compose.getContext().getClassLoader(); });
      check(runtime != host && runtime.getParent() == host && business != runtime && business.getParent() == runtime, "Actual loader parent chain differs");
      absentFromHost("kotlin.Unit"); absentFromHost("androidx.compose.ui.platform.ComposeView");
      check(Class.forName("kotlin.Unit", false, business).getClassLoader() == runtime, "Kotlin is not shared-runtime owned");
      check(completed.invoke(null, "analytics-preinitialize") != null, "Normal Release preInit path not observed");
      var analyticsType = Class.forName("app.luoxianlv.core.Analytics", false, business);
      check(analyticsType.getClassLoader() == business, "Normal analytics is not business-owned");
      analytics = analyticsType.getField("INSTANCE").get(null);
      initAt = analyticsType.getMethod("getInitAt"); entries = analyticsType.getMethod("getDiagEntries");
      main(() -> {
        try {
          var store = Class.forName("app.luoxianlv.data.DisclaimerStore", false, business);
          disclaimer = store.getConstructor(Context.class).newInstance(compose.getContext());
          agreed = store.getMethod("agreedSha"); Object companion = store.getField("Companion").get(null);
          disclaimerHash = (String) companion.getClass().getMethod("currentSha", Context.class).invoke(companion, compose.getContext());
          check(disclaimerHash != null && disclaimerHash.matches("[a-f0-9]{64}"), "Normal disclaimer asset missing");
          check(!disclaimerHash.equals(agreed.invoke(disclaimer)), "Refuses an already-consented normal profile");
          int style = Class.forName("com.google.android.material.R$style", false, runtime).getField("Theme_MaterialComponents_DayNight_NoActionBar").getInt(null);
          check((style >>> 24) == 0x7f && compose.getContext().getResources().getResourceTypeName(style).equals("style"), "Material runtime resource boundary differs");
          var material = Class.forName("com.google.android.material.button.MaterialButton", true, runtime);
          View button = (View) material.getConstructor(Context.class).newInstance(new ContextThemeWrapper(compose.getContext(), style));
          ((TextView) button).setText("local normal Release check");
          button.measure(View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
          check(material.getClassLoader() == runtime && button.getMeasuredWidth() == 96 && button.getMeasuredHeight() == 48, "Actual Material measurement failed");
          report.put("materialMeasured", true).put("layeredLoadersVerified", true).put("composeVisible", true);
        } catch (Exception error) { throw new AssertionError("Normal resource/privacy read failed", error); }
      });
      componentAndServiceBoundary(target);
      long privacyStart = SystemClock.elapsedRealtime();
      do { privacy(); check(offline(target), "Internet became available during local observation"); SystemClock.sleep(100); }
      while (SystemClock.elapsedRealtime() - privacyStart < 10000);
      report.put("privacyObservedMs", SystemClock.elapsedRealtime() - privacyStart).put("consentClicked", false)
          .put("formalInitializeAttempted", false).put("businessHomeStatus", "blocked-by-unconsented-home")
          .put("hotUpdateHealth", "not-verified").put("hotUpdateRollback", "not-verified").put("activationCalled", false);
      passed = true;
    } catch (Throwable error) {
      try {
        report.put("errorType", error.getClass().getName());
        if (error instanceof Check) report.put("failedCheck", error.getMessage());
        if (error.getCause() instanceof Check) report.put("failedCheck", error.getCause().getMessage());
        if (error.getCause() != null) report.put("causeType", error.getCause().getClass().getName());
      } catch (Exception ignored) { }
    } finally {
      try { if (home != null) main(() -> home.finish()); }
      catch (Throwable closing) { passed = false; }
      try {
        check(SystemClock.elapsedRealtime() - started < 90000, "Normal observation exceeded 90 seconds");
        report.put("passed", passed).put("elapsedMs", SystemClock.elapsedRealtime() - started);
        // 仅返回公开证据；非debuggable目标不需要run-as，也不改业务私有文件。
      } catch (Throwable closing) { passed = false; try { report.put("passed", false); } catch (Exception ignored) { } }
      var result = new Bundle(); result.putString("normalReleaseReport", report.toString());
      result.putString("stream", passed ? "PASS: normal Release payload, loaders, Material and unconsented privacy gate\n" : "FAIL: normal Release local observation\n");
      finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
  }
}
