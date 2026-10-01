package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** 仅显式 testOnly/debug 签名的非 debuggable Release profile；不用 R8 内部类名。 */
public final class NativeReleaseInstrumentation extends Instrumentation {
  private static final String TARGET = "app.luoxianlv.releaseprobe";
  private static final String INITIALIZE = "analytics-initialize";
  private final JSONObject report = new JSONObject();
  private Activity activity;
  private View compose;
  private ClassLoader hostLoader, runtimeLoader, businessLoader;
  private Method completedAt, analyticsInitAt, analyticsEntries;
  private Object analytics;

  @Override public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  private void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private void onMain(Runnable work) {
    var failure = new AtomicReference<Throwable>();
    runOnMainSync(() -> {
      try { work.run(); } catch (Throwable error) { failure.set(error); }
    });
    if (failure.get() != null) throw new AssertionError("Release probe main-thread check failed", failure.get());
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

  private View findCompose(View view) {
    if (view.getClass().getName().equals("androidx.compose.ui.platform.ComposeView")) return view;
    if (view instanceof ViewGroup) {
      var group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        var found = findCompose(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private void requireHostDoesNotDefine(String name) throws Exception {
    try {
      Class.forName(name, false, hostLoader);
    } catch (ClassNotFoundException expected) { return; }
    throw new AssertionError("Host defines runtime type: " + name);
  }

  private void waitForCompose() {
    long until = SystemClock.uptimeMillis() + 60000;
    while (compose == null) {
      onMain(() -> {
        var found = findCompose(activity.getWindow().getDecorView());
        if (found != null && found.isShown() && found.getWidth() > 0 && found.getHeight() > 0) compose = found;
      });
      require(SystemClock.uptimeMillis() < until, "Actual Release Compose page did not become visible");
      if (compose == null) SystemClock.sleep(50);
    }
  }

  private void materialCheck() throws Exception {
    onMain(() -> {
      try {
        Context module = compose.getContext();
        var styles = Class.forName("com.google.android.material.R$style", false, runtimeLoader);
        int style = styles.getField("Theme_MaterialComponents_DayNight_NoActionBar").getInt(null);
        require((style >>> 24) == 0x7f, "Material style does not belong to runtime package 0x7f");
        require(module.getResources().getResourceTypeName(style).equals("style"), "Module cannot resolve runtime Material style");
        var material = Class.forName("com.google.android.material.button.MaterialButton", true, runtimeLoader);
        var theme = new ContextThemeWrapper(module, style);
        View button = (View) material.getConstructor(Context.class).newInstance(theme);
        require(button.getClass().getClassLoader() == runtimeLoader, "Material view does not come from runtime loader");
        ((TextView) button).setText("releaseprobe");
        button.measure(View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
        require(button.getMeasuredWidth() == 96 && button.getMeasuredHeight() == 48, "Actual Material measurement failed");
        report.put("materialClass", material.getName());
        report.put("materialStylePackageId", style >>> 24);
        report.put("materialMeasured", true);
      } catch (Exception error) { throw new AssertionError("Actual Release Material resource check failed", error); }
    });
  }

  private void privacyCheck() {
    onMain(() -> {
      try {
        require(completedAt.invoke(null, INITIALIZE) == null, "Formal analytics initialization completed without consent");
        require(analyticsInitAt.invoke(analytics) == null, "Business analytics getter observes formal initialization");
        for (Object entry : (List<?>) analyticsEntries.invoke(analytics)) {
          var type = entry.getClass();
          Object kind = type.getMethod("getKind").invoke(entry);
          Object detail = type.getMethod("getDetail").invoke(entry);
          require(!("SDK".equals(kind) && "initialize 调用".equals(detail)), "Formal analytics initialization was attempted without consent");
        }
      } catch (Exception error) { throw new AssertionError("Release privacy observation failed", error); }
    });
  }

  @Override public void onStart() {
    Context target = getTargetContext();
    boolean passed = false;
    try {
      require(TARGET.equals(target.getPackageName()), "Release probe refuses any other target package");
      var info = target.getApplicationInfo();
      require((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0, "Release code must remain non-debuggable");
      require((info.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0, "Release probe must be testOnly");
      require(offline(target), "Root must disable all emulator internet before running Release probe");
      var signatures = target.getPackageManager().getPackageInfo(TARGET, PackageManager.GET_SIGNATURES).signatures;
      require(signatures != null && signatures.length == 1, "Expected a single local test signing certificate");
      var cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(signatures[0].toByteArray()));
      require(cert.getSubjectX500Principal().getName().contains("CN=Android Debug"), "Release probe requires Android Debug signing certificate");
      report.put("schema", 1);
      report.put("profile", "test-only-release-code");
      report.put("targetPackage", TARGET);
      report.put("sdkInt", Build.VERSION.SDK_INT);
      report.put("pid", android.os.Process.myPid());
      report.put("debuggable", false);
      report.put("testOnly", true);
      report.put("signing", "verified-local-debug-certificate");
      report.put("certificateSha256", hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())));
      report.put("formalReleaseReady", false);
      report.put("offlineObserved", true);
      report.put("productionTouched", false);
      hostLoader = target.getClassLoader();
      var once = Class.forName("app.luoxianlv.hot.contract.ProcessOnce", false, hostLoader);
      require(once.getClassLoader() == hostLoader, "Stable SDK does not come from installed host");
      completedAt = once.getMethod("completedAt", String.class);
      require(completedAt.invoke(null, INITIALIZE) == null, "Formal analytics initialized before Activity probe");
      activity = startActivitySync(new Intent().setClassName(TARGET, "app.luoxianlv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForCompose();
      onMain(() -> {
        runtimeLoader = compose.getClass().getClassLoader();
        businessLoader = compose.getContext().getClassLoader();
      });
      require(runtimeLoader != hostLoader && runtimeLoader.getParent() == hostLoader, "Actual runtime loader parent differs from host");
      require(businessLoader != runtimeLoader && businessLoader.getParent() == runtimeLoader, "Actual business loader parent differs from runtime");
      requireHostDoesNotDefine("kotlin.Unit");
      requireHostDoesNotDefine("androidx.compose.ui.platform.ComposeView");
      require(Class.forName("kotlin.Unit", false, businessLoader).getClassLoader() == runtimeLoader, "Kotlin does not resolve through shared runtime");
      var analyticsType = Class.forName("app.luoxianlv.core.Analytics", false, businessLoader);
      require(analyticsType.getClassLoader() == businessLoader, "Release analytics implementation is not business-owned");
      onMain(() -> {
        try {
          var disclaimer = Class.forName("app.luoxianlv.data.DisclaimerStore", false, businessLoader);
          Object store = disclaimer.getConstructor(Context.class).newInstance(compose.getContext());
          Object agreed = disclaimer.getMethod("agreedSha").invoke(store);
          Object companion = disclaimer.getField("Companion").get(null);
          Object current = companion.getClass().getMethod("currentSha", Context.class).invoke(companion, compose.getContext());
          require(current != null && current.toString().matches("[a-f0-9]{64}"), "Current Release disclaimer asset is missing");
          require(!current.equals(agreed), "Release probe refuses an already-consented profile");
          report.put("currentDisclaimerConsented", false);
        } catch (Exception error) { throw new AssertionError("Read-only Release consent check failed", error); }
      });
      analytics = analyticsType.getField("INSTANCE").get(null); // AppProcess already created this singleton through production preInitialize.
      analyticsInitAt = analyticsType.getMethod("getInitAt");
      analyticsEntries = analyticsType.getMethod("getDiagEntries");
      Object preInit = completedAt.invoke(null, "analytics-preinitialize");
      require(preInit != null, "Actual Release preInit path was not observed");
      report.put("preInitializeAt", preInit);
      report.put("preInitOccurred", true);
      report.put("layeredLoadersVerified", true);
      materialCheck();
      long started = SystemClock.uptimeMillis();
      do {
        privacyCheck();
        require(offline(target), "Emulator internet became available during privacy observation");
        SystemClock.sleep(100);
      } while (SystemClock.uptimeMillis() - started < 10000);
      report.put("privacyObservedMs", SystemClock.uptimeMillis() - started);
      report.put("initializeCompletedAt", JSONObject.NULL);
      report.put("formalInitializeAttempted", false);
      report.put("consentClicked", false);
      report.put("activationCalled", false);
      report.put("healthInjected", false);
      passed = true;
    } catch (Throwable error) {
      try {
        report.put("errorType", error.getClass().getName());
        // Assertion messages are fixed test labels; do not emit vendor exception text or event payloads.
        if (error instanceof AssertionError) report.put("failedCheck", error.getMessage());
        if (error.getCause() != null) report.put("causeType", error.getCause().getClass().getName());
      } catch (Exception ignored) { }
    } finally {
      if (activity != null) {
        try { onMain(() -> activity.finish()); } catch (Throwable closeError) { passed = false; }
      }
      try {
        report.put("passed", passed);
        if (TARGET.equals(target.getPackageName())) {
          try (var out = new FileOutputStream(new File(target.getFilesDir(), "native-release-probe-report.json"))) {
            out.write((report.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
          }
        }
      } catch (Exception reportError) { passed = false; }
      Bundle result = new Bundle();
      result.putString("releaseProbeReport", report.toString()); // Non-debuggable target cannot be read with run-as; expose only this public report.
      result.putString("reportPath", "files/native-release-probe-report.json");
      result.putString("stream", passed ? "PASS: release layered loading, Material and unconsented analytics gate\n" : "FAIL: release probe; inspect private test report\n");
      finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
  }

  private static String hex(byte[] bytes) {
    var output = new StringBuilder();
    for (byte value : bytes) output.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
    return output.toString();
  }
}
