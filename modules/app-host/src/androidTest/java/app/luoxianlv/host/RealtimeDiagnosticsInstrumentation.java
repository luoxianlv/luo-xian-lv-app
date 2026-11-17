package app.luoxianlv.host;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.SharedRealtime;
import java.net.URI;
import java.util.Set;
import org.json.JSONObject;

/** 只采样真实宿主的现有连接；不创建引擎、不请求授权，也不发送或更改账号/房间。 */
public final class RealtimeDiagnosticsInstrumentation extends Instrumentation {
  private static final Set<String> STATES = Set.of(
      "stopped", "waiting_network", "unauthorized", "connecting", "connected", "backoff");
  private static final String[] FLAGS = {
      "connected", "serviceUnavailable", "ensembleEnabled", "hotSubscribed", "queriesReady", "hotQueriesReady", "clockReady"
  };
  private String durationOption;

  @Override public void onCreate(Bundle arguments) {
    durationOption = arguments == null ? "20000" : arguments.getString("durationMs", "20000");
    super.onCreate(arguments);
    start();
  }

  @Override public void onStart() {
    Bundle report = new Bundle();
    boolean completed = false;
    try {
      if (!BuildConfig.DEBUG || !getTargetContext().getPackageName().endsWith(".debug"))
        throw new IllegalStateException("Debug only");
      long durationMs = Long.parseLong(durationOption);
      if (durationMs < 0 || durationMs > 60_000) throw new IllegalArgumentException("durationMs range");
      startActivitySync(new Intent().setClassName(getTargetContext(), "app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long startupDeadline = SystemClock.elapsedRealtime() + 30_000;
      while (true) {
        try { if (Bootstrap.source().prepared != null) break; }
        catch (IllegalStateException waiting) { }
        if (SystemClock.elapsedRealtime() >= startupDeadline) throw new IllegalStateException("startup timeout");
        SystemClock.sleep(25);
      }

      long started = SystemClock.elapsedRealtime();
      String previous = "";
      JSONObject last = null;
      int samples = 0;
      int changes = 0;
      boolean observedConnected = false;
      while (true) {
        SharedRealtime.Bridge bridge = SharedRealtime.current();
        Bundle state = bridge == null ? null : bridge.state();
        last = snapshot(state);
        samples++;
        observedConnected |= state != null && state.getBoolean("connected");
        String current = last.toString();
        if (!current.equals(previous)) {
          previous = current;
          changes++;
          Bundle status = new Bundle();
          status.putString("stream", new JSONObject()
              .put("elapsedMs", SystemClock.elapsedRealtime() - started)
              .put("snapshot", last).toString() + "\n");
          sendStatus(0, status);
        }
        long elapsed = SystemClock.elapsedRealtime() - started;
        if (elapsed >= durationMs) break;
        SystemClock.sleep(Math.min(500, durationMs - elapsed));
      }
      JSONObject summary = new JSONObject()
          .put("readOnly", true)
          .put("samples", samples)
          .put("changes", changes)
          .put("elapsedMs", SystemClock.elapsedRealtime() - started)
          .put("observedConnected", observedConnected)
          .put("last", last);
      report.putString("stream", "只读实时连接诊断完成：" + summary + "\n");
      completed = true;
    } catch (Throwable failure) {
      // 异常原文可能来自网络或第三方库，只输出类别，不打印消息、堆栈或 Bundle。
      report.putString("stream", "只读实时连接诊断未完成：" + failure.getClass().getSimpleName() + "\n");
    }
    finish(completed ? -1 : 0, report);
  }

  private static JSONObject snapshot(Bundle state) throws Exception {
    String phase = state == null ? "unavailable" : state.getString("state", "unknown");
    if (state != null && !STATES.contains(phase)) phase = "unknown";
    JSONObject result = new JSONObject()
        .put("bridgeAvailable", state != null)
        .put("realtimeEnabled", BuildConfig.REALTIME_ENABLED)
        .put("configuredOrigin", safeOrigin(BuildConfig.REALTIME_ORIGIN))
        .put("state", phase)
        .put("apiOrigin", state == null ? "" : safeOrigin(state.getString("apiOrigin", "")));
    for (String flag : FLAGS) result.put(flag, state != null && state.getBoolean(flag));
    if (state != null) {
      safeFailureScalar(result, state, "failureStage");
      safeFailureScalar(result, state, "failureStatus");
      safeFailureScalar(result, state, "failureCategory");
    }
    return result;
  }

  private static String safeOrigin(String value) {
    if (value == null || value.isEmpty()) return "";
    try {
      URI source = URI.create(value);
      if (!Set.of("https", "http", "wss", "ws").contains(source.getScheme()) || source.getHost() == null)
        return "invalid_origin";
      return new URI(source.getScheme(), null, source.getHost(), source.getPort(), null, null, null).toASCIIString();
    } catch (Exception invalid) {
      return "invalid_origin";
    }
  }

  @SuppressWarnings("deprecation")
  private static void safeFailureScalar(JSONObject target, Bundle source, String key) throws Exception {
    Object value = source.get(key);
    if (value instanceof Number) target.put(key, ((Number) value).longValue());
    else if (value instanceof Boolean) target.put(key, value);
    else if (value instanceof String) {
      String text = (String) value;
      target.put(key, text.isEmpty() || text.matches("[A-Za-z][A-Za-z0-9_.:-]{0,95}") ? text : "redacted");
    }
  }
}
