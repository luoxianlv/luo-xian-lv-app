package app.luoxianlv.host.input;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.SharedInput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** 只做真实系统路由预检，不接管或注入普通触屏。 */
public final class TouchRoutingInstrumentation extends Instrumentation {
  private String mode;
  private int port;
  private boolean requireMultiple;
  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    mode = args == null ? SharedInput.SHIZUKU : args.getString("mode",SharedInput.SHIZUKU);
    port = args == null ? 0 : Integer.parseInt(args.getString("wirelessPort","0"));
    requireMultiple = args != null && "true".equals(args.getString("requireMultiple"));
    start();
  }
  @Override public void onStart() {
    Bundle output = new Bundle(); boolean passed = false;
    SharedInput.Bridge bridge = null; String previous = null;
    try {
      require(getTargetContext().getPackageName().endsWith(".debug"),"仅允许 Debug 预检");
      getTargetContext().startActivity(new Intent().setClassName(getTargetContext(),"app.luoxianlv.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long end = SystemClock.elapsedRealtime()+10000;
      while ((SharedInput.current()==null || !SharedInput.current().state().getBoolean("initialized")) && SystemClock.elapsedRealtime()<end) SystemClock.sleep(20);
      bridge=SharedInput.current(); require(bridge!=null,"输入桥尚未安装");
      previous=bridge.state().getString("mode",SharedInput.ACCESSIBILITY); bridge.select(mode);
      end=SystemClock.elapsedRealtime()+5000;
      while (!mode.equals(bridge.state().getString("mode")) && SystemClock.elapsedRealtime()<end) SystemClock.sleep(20);
      Bundle args=new Bundle(); if(port>0)args.putInt("port",port);
      bridge.command("connect",args);
      end=SystemClock.elapsedRealtime()+25000;
      while ((!bridge.state().getBoolean("touchReady") || bridge.state().getInt("helperUid")!=2000) && SystemClock.elapsedRealtime()<end) SystemClock.sleep(20);
      Bundle state=bridge.state();
      require(state.getBoolean("connected") && state.getInt("helperUid")==2000,"不是实际 shell 连接："+state.getString("message"));
      require(state.getBoolean("touchReady"),"触屏筛选失败："+state.getString("message")+"；"+state.getString("routingDiagnostics"));
      if(requireMultiple)require(state.getInt("candidateCount")>1,"本轮未覆盖多个兼容触屏");
      require(!state.getBoolean("active"),"预检意外接管了触屏");
      JSONObject report=new JSONObject().put("mode",mode).put("helperUid",state.getInt("helperUid"))
          .put("candidateCount",state.getInt("candidateCount")).put("unknownCount",state.getInt("candidateUnknownCount"))
          .put("path",state.getString("path")).put("method",state.getString("deviceSelectionMethod"))
          .put("reason",state.getString("deviceSelectionReason")).put("routingDiagnostics",state.getString("routingDiagnostics"))
          .put("active",state.getBoolean("active"));
      Files.write(new java.io.File(getTargetContext().getExternalFilesDir(null),"touch-routing.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
      output.putString("stream","触屏路由预检通过："+report+"\n"); passed=true;
    }catch(Throwable failure){output.putString("stream","触屏路由预检失败："+failure+"\n");output.putString("error",android.util.Log.getStackTraceString(failure));}
    finally{if(bridge!=null && previous!=null)bridge.select(previous);}
    finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,output);
  }
  private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
