package app.luoxianlv.host;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import app.luoxianlv.hot.HotApiClient;
import app.luoxianlv.hot.InstallationIdentity;
import app.luoxianlv.hot.UpdateCancellation;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** 在真实系统 HTTP 栈等待响应头；不能拿 JVM 取消行为替代 Android。 */
public final class HostHttpInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
  @Override public void onStart() {
    var report=new Bundle();boolean passed=false;
    var worker=Executors.newSingleThreadExecutor();
    try (var server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))) {
      var calls=new AtomicInteger();var received=new CountDownLatch(1);
      var identity=InstallationIdentity.open(new File(getTargetContext().getCacheDir(),"header-check-"+java.util.UUID.randomUUID()),"app.luoxianlv.debug","test");
      var serving=worker.submit(()-> {
        try {
          for(int i=0;i<2;i++) try(var socket=server.accept()) {
            calls.incrementAndGet();socket.setSoTimeout(5000);
            var input=socket.getInputStream();var head=new ByteArrayOutputStream();
            while(head.size()<8192) {int value=input.read();if(value<0)throw new EOFException();head.write(value);if(head.toString("US-ASCII").endsWith("\r\n\r\n"))break;}
            int length=0;
            for(String line:head.toString("US-ASCII").split("\r\n")) if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
            if(length<1||length>4096)throw new IOException("请求长度异常");
            var raw=new byte[length];new DataInputStream(input).readFully(raw);
            String id=new JSONObject(new String(raw,StandardCharsets.UTF_8)).getString("installationId");
            if(i==1) {received.countDown();Thread.sleep(3000);continue;}
            Thread.sleep(900);
            byte[] body=new JSONObject().put("installationId",id).put("credentialFormat","Bearer <installationId>.<secret>").put("scope","installation").toString().getBytes(StandardCharsets.UTF_8);
            var output=socket.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nX-Lxhot-Time: 1791842981\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
          }
        } catch(Exception failure) {throw new RuntimeException(failure);}
      });
      var origin=URI.create("http://127.0.0.1:"+server.getLocalPort());
      new HotApiClient(origin,identity,1,"a".repeat(64),true).register(new UpdateCancellation(()->false));
      if(calls.get()!=1)throw new AssertionError("慢响应重发了注册请求");
      var cancellation=new UpdateCancellation(()->false);
      var client=Executors.newSingleThreadExecutor();
      try {
        var pending=client.submit(()->{try{new HotApiClient(origin,identity,1,"a".repeat(64),true).register(cancellation);throw new AssertionError("取消后返回成功");}catch(UpdateCancellation.Cancelled expected){}catch(Exception failure){throw new RuntimeException(failure);}});
        if(!received.await(5,TimeUnit.SECONDS))throw new AssertionError("请求未到达");
        long before=SystemClock.elapsedRealtime();cancellation.cancel();
        if(SystemClock.elapsedRealtime()-before>100)throw new AssertionError("取消调用阻塞 UI");
        pending.get(5,TimeUnit.SECONDS);
        if(calls.get()!=2)throw new AssertionError("取消后重发请求");
      } finally {client.shutdownNow();}
      serving.get(5,TimeUnit.SECONDS);
      passed=true;report.putString("stream","通过：真实宿主 HTTP 慢响应头不重发、取消不阻塞调用线程、原请求释放且不再重试。\n");
    } catch(Throwable failure) {report.putString("stream",android.util.Log.getStackTraceString(failure));}
    finally {worker.shutdownNow();}
    finish(passed?-1:0,report);
  }
}
