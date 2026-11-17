package app.luoxianlv.host;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.contract.SharedRealtime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 通过真实业务加载器调用 Debug 探针，测试 APK 不携带另一份 Kotlin/Ktor。 */
public final class BusinessHttpInstrumentation extends Instrumentation {
  private boolean coordinator;
  private boolean tls;
  private boolean retirement;
  private boolean body;
  private String bodyOrigin="";
  @Override public void onCreate(Bundle args) {bodyOrigin=args.getString("bodyOrigin","");body="true".equals(args.getString("body"));coordinator="true".equals(args.getString("coordinator"));tls="true".equals(args.getString("tls"));retirement="true".equals(args.getString("retirement"));super.onCreate(args);start();}
  @Override public void onStart() {
    Bundle report=new Bundle();boolean passed=false;
    try {
      startActivitySync(new Intent().setClassName(getTargetContext(),"app.luoxianlv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long until=SystemClock.elapsedRealtime()+30000;
      while(true) {
        try {if(Bootstrap.source().prepared!=null)break;} catch(IllegalStateException waiting) {}
        if(SystemClock.elapsedRealtime()>until)throw new AssertionError("业务加载超时");
        SystemClock.sleep(20);
      }
      var result=new AtomicReference<String>();var ok=new AtomicBoolean();var done=new CountDownLatch(1);
      SharedRealtime.Completion callback=(success,text)->{ok.set(success);result.set(text);done.countDown();};
      if(retirement) {
        var context=new AtomicReference<android.content.Context>();
        runOnMainSync(()->context.set(Bootstrap.source().prepared.context(getTargetContext())));
        Class.forName("app.luoxianlv.ensemble.EnsembleProbe",true,Bootstrap.source().prepared.classLoader())
            .getMethod("verifyRetirement",android.content.Context.class,SharedRealtime.Completion.class)
            .invoke(null,context.get(),callback);
      } else if(coordinator) {
        var file=new java.io.File(getTargetContext().getFilesDir(),"ensemble-test-token");
        if(!file.isFile() || file.length()>4096)throw new AssertionError("缺少本机测试令牌");
        String token=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8).trim();
        if(!file.delete())throw new AssertionError("未移除临时测试令牌");
        var context=new AtomicReference<android.content.Context>();
        runOnMainSync(()->context.set(Bootstrap.source().prepared.context(getTargetContext())));
        Class.forName("app.luoxianlv.ensemble.EnsembleProbe",true,Bootstrap.source().prepared.classLoader())
            .getMethod("verify",android.content.Context.class,String.class,SharedRealtime.Completion.class)
            .invoke(null,context.get(),token,callback);
      } else {
        Class<?> probe=Class.forName("app.luoxianlv.platform.NetworkProbe",true,Bootstrap.source().prepared.classLoader());
        if(body) probe.getMethod("verifyBody",String.class,SharedRealtime.Completion.class).invoke(null,bodyOrigin,callback);
        else if(tls) {
          try(var input=getContext().getAssets().open("tls-probe.p12");var bytes=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[1024];int count;
            while((count=input.read(buffer))!=-1) {bytes.write(buffer,0,count);if(bytes.size()>16384)throw new AssertionError("TLS 测试材料过大");}
            probe.getMethod("verifyTls",byte[].class,SharedRealtime.Completion.class).invoke(null,bytes.toByteArray(),callback);
          }
        } else probe.getMethod("verify",SharedRealtime.Completion.class).invoke(null,callback);
      }
      if(!done.await(coordinator?120000:30000,TimeUnit.MILLISECONDS))throw new AssertionError("网络验证超时");
      if(!ok.get())throw new AssertionError(result.get());
      report.putString("stream",result.get()+"\n");passed=true;
    } catch(Throwable failure) {report.putString("stream",android.util.Log.getStackTraceString(failure));}
    finish(passed?-1:0,report);
  }
}
