#Requires -Version 7.0
param()
$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
$utf8=[Text.UTF8Encoding]::new($false,$true)
function Exact-Block([string]$Text,[string]$Signature) {
    $start=$Text.IndexOf($Signature,[StringComparison]::Ordinal)
    if($start-lt 0){throw "真实源码缺少限定符号：$Signature"}
    $open=$Text.IndexOf('{',$start);$depth=0
    for($i=$open;$i-lt $Text.Length;$i++) {
        if($Text[$i]-eq '{'){$depth++}elseif($Text[$i]-eq '}'){$depth--;if($depth-eq 0){return $Text.Substring($start,$i-$start+1)}}
    }
    throw '限定源码块未闭合'
}
$helper=[IO.File]::ReadAllText((Join-Path $repoRoot 'app-host/src/androidTest/java/app/luoxianlv/host/NativeDownloadChecks.java'),$utf8)
$bootstrap=[IO.File]::ReadAllText((Join-Path $repoRoot 'app-host/src/main/java/app/luoxianlv/host/Bootstrap.java'),$utf8)
$page=[IO.File]::ReadAllText((Join-Path $repoRoot 'hot-core/src/main/java/app/luoxianlv/hot/PageSwapHost.java'),$utf8)
$old=(& git -C $repoRoot show 'b85980d:app-host/src/androidTest/java/app/luoxianlv/host/NativeDownloadChecks.java')-join "`n"
if($LASTEXITCODE-ne 0){throw '缺少真实失败前helper源码负对照'}
$blocks=@('private interface Action','private interface Condition','private static boolean mainCondition(',
    'private static void main(','private static long now(','private static void require(','static final class CheckFailure',
    'private static void restoreHome(')|ForEach-Object{Exact-Block $helper $_}
$original=(Exact-Block $old 'private static void restoreHome(').Replace('void restoreHome(','void restoreHomeOriginal(')
$hostGate=Exact-Block $bootstrap 'static boolean foregroundInUse('
$pageGate=Exact-Block $page 'private static void requireMain('
$pageInUse=Exact-Block $page 'public boolean inUse('
$runRoot=Join-Path $repoRoot ('.local/native-download-thread-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($runRoot)
$source=@'
package app.luoxianlv.hot;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import app.luoxianlv.hot.StrictJson;
public final class NativeDownloadThreadProof {
  static volatile Thread ui;
  static final ExecutorService MAIN=Executors.newSingleThreadExecutor(task -> new Thread(task,"proof-main"));
  static class Looper {
    static final Looper MAIN_LOOP=new Looper();
    static Looper getMainLooper() { return MAIN_LOOP; }
    static Looper myLooper() { return Thread.currentThread()==ui?MAIN_LOOP:null; }
  }
  static class NativePage { static final int RESUMED=1; }
  static class PageSwapHost {
    boolean closed,shown=true; int lifecycle=NativePage.RESUMED;
    boolean isShown() { return shown; }
    // 实际PageSwapHost的读路径和主线程门禁，未镜像判断分支。
    PAGE_IN_USE
    PAGE_GATE
  }
  static class Bootstrap {
    static final Map<PageSwapHost,String> PAGES=new LinkedHashMap<>();
    HOST_GATE
  }
  static class Instrumentation {
    void runOnMainSync(Runnable task) {
      try { MAIN.submit(() -> {ui=Thread.currentThread();task.run();}).get(); }
      catch(Exception error) { throw new AssertionError(error); }
    }
  }
  static class Activity {
    volatile boolean focus=true,destroyed,finishing; int offThreadFocus;
    boolean isDestroyed() { return destroyed; }
    boolean isFinishing() { return finishing; }
    boolean hasWindowFocus() { if(Thread.currentThread()!=ui)offThreadFocus++; return focus; }
  }
  static class Intent {
    static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_REORDER_TO_FRONT=2;
    Intent setClassName(Context context,String name) { return this; }
    Intent addFlags(int flags) { return this; }
  }
  static class Context { void startActivity(Intent intent) {} }
  static class SystemClock {
    static long elapsedRealtime() { return System.nanoTime()/1000000; }
    static void sleep(long ms) { try{Thread.sleep(ms);}catch(InterruptedException error){throw new AssertionError(error);} }
  }
  EXACT_HELPER_BLOCKS
  EXACT_ORIGINAL_BLOCK
  static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
  public static void main(String[] ignored) throws Exception {
    var runner=new Instrumentation();var target=new Context();var home=new Activity();var page=new PageSwapHost();
    runner.runOnMainSync(() -> Bootstrap.PAGES.put(page,"home"));
    try {
      try {restoreHomeOriginal(runner,target,home,now()+1000);throw new AssertionError("原源码没有复现非法线程");}
      catch(IllegalArgumentException expected){check(expected.getMessage().equals("页面切换必须在主线程"),"负对照缺少真实PageSwapHost门禁错误");}
      home.offThreadFocus=0;
      restoreHome(runner,target,home,now()+1000);
      check(home.offThreadFocus==0,"新源码仍在instrument线程读取焦点");
      runner.runOnMainSync(() -> page.closed=true);
      try {restoreHome(runner,target,home,now()+100);throw new AssertionError("关闭页面被错误放行");}
      catch(CheckFailure expected){check(expected.getMessage().equals("未恢复原首页焦点"),"原条件没有保留");}
      runner.runOnMainSync(() -> {page.closed=false;home.finishing=true;});
      try {restoreHome(runner,target,home,now()+1000);throw new AssertionError("结束中的home被错误放行");}
      catch(CheckFailure expected){check(expected.getCause() instanceof CheckFailure,"主线程原home门禁错误链丢失");}
      System.out.println("实际helper/Bootstrap/PageSwapHost源码线程验证：4 PASS；原b85980d负对照复现，未调用Android设备/API。");
    } finally {MAIN.shutdownNow();}
  }
}
'@
$source=$source.Replace('PAGE_IN_USE',$pageInUse).Replace('PAGE_GATE',$pageGate).Replace('HOST_GATE',$hostGate).
    Replace('EXACT_HELPER_BLOCKS',($blocks-join "`n")).Replace('EXACT_ORIGINAL_BLOCK',$original)
$sourcePath=Join-Path $runRoot 'NativeDownloadThreadProof.java'
[IO.File]::WriteAllText($sourcePath,$source,$utf8)
$core=Join-Path $repoRoot 'hot-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$arguments=@('-encoding','UTF-8','--release','17','-cp',$core,'-d',$runRoot,$sourcePath)
$argsFile=Join-Path $runRoot 'javac.args'
[IO.File]::WriteAllLines($argsFile,($arguments|ForEach-Object{'"'+$_.Replace('\','/')+'"'}),$utf8)
& javac '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' "@$argsFile"
if($LASTEXITCODE-ne 0){throw '实际源码线程验证编译失败'}
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp "$runRoot;$core" app.luoxianlv.hot.NativeDownloadThreadProof
if($LASTEXITCODE-ne 0){throw '实际源码线程验证失败'}
