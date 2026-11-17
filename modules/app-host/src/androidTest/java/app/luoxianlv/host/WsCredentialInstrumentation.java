package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.Bundle;
import app.luoxianlv.host.realtime.RealtimeApi;
import app.luoxianlv.hot.InstallationIdentity;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;

/** 用本机 Rust 夹具和两个临时账号验证真实 HTTP、磁盘缓存、续期和换号。 */
public final class WsCredentialInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private RealtimeApi api(SharedPreferences prefs, InstallationIdentity id) {
    return new RealtimeApi(prefs, URI.create("http://127.0.0.1:18474"), id, 1,
        "b".repeat(64), 26, true);
  }
  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  @Override public void onStart() {
    boolean ok = false;
    Bundle result = new Bundle();
    var context = getTargetContext();
    var prefs = context.getSharedPreferences("ws-credential-test", 0);
    File input = new File(context.getFilesDir(), "ws-credential-test.json");
    try (var proxy = LocalFixtureProxy.direct()) {
      require(context.getPackageName().equals("app.luoxianlv.debug"), "仅供 Debug 模拟器");
      JSONArray users = new JSONArray(new String(Files.readAllBytes(input.toPath()), StandardCharsets.UTF_8));
      Files.delete(input.toPath());
      String a = users.getJSONObject(0).getString("token"), b = users.getJSONObject(1).getString("token");
      var id = InstallationIdentity.open(new File(context.getFilesDir(), "ws-test-identity"), context.getPackageName(), "test");
      prefs.edit().clear().commit();
      var first = api(prefs, id);
      first.login(a);
      String ticket = first.ticket(new RealtimeApi.Request());
      String saved = prefs.getString("credential", "");
      require(saved.length() == 64 && prefs.getLong("expires",0) > System.currentTimeMillis()+3_500_000, "凭证未持久化一小时");
      var next = api(prefs, id);
      next.login(a);
      require(!next.ticket(new RealtimeApi.Request()).equals(ticket) && saved.equals(prefs.getString("credential","")), "重建客户端没有复用凭证或握手票据重复");
      prefs.edit().putLong("expires",System.currentTimeMillis()+90_000).commit();
      next.ticket(new RealtimeApi.Request());
      String renewed = prefs.getString("credential","");
      require(!renewed.equals(saved) && renewed.length()==64, "临近到期未续期");
      prefs.edit().putLong("expires",System.currentTimeMillis()+90_000).commit();
      next.ticket(new RealtimeApi.Request());
      require(renewed.equals(prefs.getString("credential","")) && prefs.getLong("retry",0)>System.currentTimeMillis(), "额度用尽时未复用仍然有效的凭证");
      next.login("");
      require(!prefs.contains("credential"), "登出没有清理凭证");
      try { next.ticket(new RealtimeApi.Request()); throw new AssertionError("登出仍能取票"); }
      catch (RealtimeApi.Failure denied) { require(denied.status==401,"登出状态不正确"); }
      next.login(b);
      next.ticket(new RealtimeApi.Request());
      require(!renewed.equals(prefs.getString("credential","")), "换号仍使用旧凭证");
      result.putString("stream","通过：登录申请、重建复用、续期、限额回退、登出及换号。");
      ok = true;
    } catch (Throwable error) {
      result.putString("stream",android.util.Log.getStackTraceString(error));
    } finally { prefs.edit().clear().commit(); }
    finish(ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
}
