param()
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$profilePath = [Environment]::GetFolderPath('UserProfile')
$androidJar = Join-Path $profilePath 'AppData/Local/Android/Sdk/platforms/android-37.0/android.jar'
$cache = Join-Path $profilePath '.gradle/caches/modules-2/files-2.1'
$junit = (Get-ChildItem -LiteralPath (Join-Path $cache 'junit/junit/4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$hamcrest = (Get-ChildItem -LiteralPath (Join-Path $cache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$hostClasses = Join-Path $repoRoot 'modules/app-host/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$coreClasses = Join-Path $repoRoot 'modules/hot-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$contractClasses = Join-Path $repoRoot 'modules/hot-contract/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$hostResources = Join-Path $repoRoot 'modules/app-host/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar'
$resources = Join-Path $repoRoot 'modules/hot-core/src/test/resources'
foreach ($path in @($androidJar,$junit,$hamcrest,$hostClasses,$coreClasses,$contractClasses,$hostResources)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "缺少本地编译输入：$path；本工具不运行 Gradle。" }
}
$runRoot = Join-Path $repoRoot ('.local/host-cold-guards-' + [Guid]::NewGuid().ToString('N'))
$sources = Join-Path $runRoot 'src'
New-Item -ItemType Directory -Path $sources | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
function Save-Source([string]$name,[string]$text) {
    $path = Join-Path $sources $name
    [IO.File]::WriteAllText($path,$text,$utf8)
    return $path
}
$looper = Save-Source 'Looper.java' @'
package android.os;
public final class Looper {
  private static final Looper MAIN = new Looper();
  public static Looper getMainLooper() { return MAIN; }
  public static Looper myLooper() { return MAIN; }
}
'@
$handler = Save-Source 'Handler.java' @'
package android.os;
import java.util.*;
public class Handler {
  private static final List<Runnable> TASKS = new ArrayList<>();
  public Handler(Looper loop) {}
  public boolean post(Runnable task) { synchronized(TASKS) { TASKS.add(task); } return true; }
  public boolean postDelayed(Runnable task,long delay) { return post(task); }
  public void removeCallbacks(Runnable task) { synchronized(TASKS) { TASKS.removeIf(value -> value==task); } }
  public void removeCallbacksAndMessages(Object token) { reset(); }
  public static void reset() { synchronized(TASKS) { TASKS.clear(); } }
  public static void drain() {
    List<Runnable> tasks;
    synchronized(TASKS) { tasks=new ArrayList<>(TASKS); TASKS.clear(); }
    for(Runnable task:tasks) task.run();
  }
}
'@
$log = Save-Source 'Log.java' @'
package android.util;
public final class Log {
  public static final int DEBUG=3,INFO=4,WARN=5,ERROR=6;
  public static int e(String tag,String message,Throwable error) { return 0; }
  public static int w(String tag,String message) { return 0; }
  public static int w(String tag,String message,Throwable error) { return 0; }
  public static int i(String tag,String message) { return 0; }
  public static int println(int priority,String tag,String message) { return 0; }
  public static String getStackTraceString(Throwable error) { return error.toString(); }
}
'@
$service = Save-Source 'Service.java' @'
package android.app;
import android.content.*;
public class Service extends ContextWrapper {
  public static final int START_STICKY=1,STOP_FOREGROUND_REMOVE=1;
  public Service() { super(null); }
  public void onCreate() {}
  public void onDestroy() {}
  public int onStartCommand(Intent intent,int flags,int startId) { return START_STICKY; }
  public android.os.IBinder onBind(Intent intent) { return null; }
  public void startForeground(int id,Notification notification) {}
  public boolean stopSelfResult(int id) { return true; }
  public void stopForeground(int flags) {}
}
'@
$intent = Save-Source 'Intent.java' @'
package android.content;
public class Intent {
  private String action;
  public Intent() {}
  public Intent(Context context,Class<?> type) {}
  public Intent setAction(String action) { this.action=action; return this; }
  public String getAction() { return action; }
}
'@
$loader = Save-Source 'NativeLoader.java' @'
package app.luoxianlv.hot;
import android.content.Context;
import app.luoxianlv.hot.contract.BusinessFactory;
public final class NativeLoader {
  public static BusinessFactory testFactory;
  public NativeLoader(Context context,ContentStore store,ContentQuarantine quarantine,long host) {}
  public Prepared prepareBaseline(BundledBaseline baseline) { return null; }
  public static final class Prepared {
    public HotManifest manifest;
    public String runtimeHash,runtimeAbi;
    public BusinessFactory factory() { return testFactory; }
    public Context context(Context base) { return base; }
    public ClassLoader classLoader() { return getClass().getClassLoader(); }
    public PageTarget page(String route,BusinessFactory factory) { return null; }
    public void closeCallbacks() {}
    public String identity() { return manifest==null?"baseline":manifest.snapshotId; }
  }
}
'@
$bootstrap = Save-Source 'Bootstrap.java' @'
package app.luoxianlv.host;
import java.util.*;
import app.luoxianlv.hot.NativeLoader;
import app.luoxianlv.hot.contract.BusinessFactory;
public final class Bootstrap {
  public static boolean testReady=true,testDeferred;
  public static int testGateCalls;
  public static Throwable testFailure;
  public static Source testSource;
  private static final List<Runnable> WAITING=new ArrayList<>();
  public static final class Source { public BusinessFactory factory; public NativeLoader.Prepared prepared; }
  public static Source source() { return testSource; }
  public static AutoCloseable ready(Runnable task) { if(testReady) task.run(); else WAITING.add(task); return () -> WAITING.remove(task); }
  public static boolean initialCreation(Runnable task) { testGateCalls++; if(testDeferred) return false; task.run(); return true; }
  public static void componentFailed(Throwable failure) { testFailure=failure; }
  public static void finishReady() { testReady=true; for(Runnable task:new ArrayList<>(WAITING)) task.run(); WAITING.clear(); }
  public static void resetTest() { testReady=true;testDeferred=false;testGateCalls=0;testFailure=null;testSource=null;WAITING.clear(); }
}
'@
$baseClasspath = "$coreClasses;$contractClasses;$hostClasses;$hostResources;$androidJar;$junit;$hamcrest"
function Run-Checks([string]$name,[string[]]$inputs,[string]$testClass) {
    $classes = Join-Path $runRoot $name
    New-Item -ItemType Directory -Path $classes | Out-Null
    $arguments = @('-encoding','UTF-8','-source','17','-target','17','-classpath',$baseClasspath,'-d',$classes) + $inputs
    $argumentFile = Join-Path $runRoot ($name+'.args')
    [IO.File]::WriteAllLines($argumentFile,($arguments | ForEach-Object { '"'+$_.Replace('\','/')+'"' }),$utf8)
    & javac "@$argumentFile"
    if($LASTEXITCODE -ne 0) { throw "独立编译失败：$name" }
    if($testClass) {
        & java -cp "$classes;$baseClasspath;$resources" org.junit.runner.JUnitCore $testClass
        if($LASTEXITCODE -ne 0) { throw "独立检查失败：$name" }
    }
}
$platform = @($looper,$handler,$log,$service,$intent,$loader)
Write-Host '前台服务实际准备逻辑：Bootstrap 许可入口由可控 JVM 替身提供，不执行 Android 生命周期。'
Run-Checks 'foreground' ($platform + @($bootstrap,
    (Join-Path $repoRoot 'modules/app-host/src/main/java/app/luoxianlv/service/PlaybackForegroundService.java'),
    (Join-Path $repoRoot 'tools/test-support/ForegroundCreationGuardTest.java'))) 'app.luoxianlv.host.ForegroundCreationGuardTest'
Write-Host '实际 Bootstrap 初始化故障分支：真实激活/隔离持久记录，平台与已准备模块为 JVM 替身。'
Run-Checks 'initialization' ($platform + @(
    (Join-Path $repoRoot 'modules/hot-contract/src/main/java/app/luoxianlv/hot/contract/ProcessHooks.java'),
    (Join-Path $repoRoot 'modules/app-host/src/main/java/app/luoxianlv/host/Bootstrap.java'),
    (Join-Path $repoRoot 'modules/app-host/src/main/java/app/luoxianlv/service/PlaybackForegroundService.java'),
    (Join-Path $repoRoot 'tools/test-support/HostInitializationRecoveryTest.java'))) 'app.luoxianlv.host.HostInitializationRecoveryTest'
Write-Host '无界面仪器仅针对真实 Android SDK 独立编译；设备生命周期与合并 runner 注册由统一 Android 构建验收。'
Run-Checks 'instrumentation' @(
    (Join-Path $repoRoot 'modules/hot-contract/src/main/java/app/luoxianlv/hot/contract/ProcessHooks.java'),
    (Join-Path $repoRoot 'modules/app-host/src/main/java/app/luoxianlv/host/Bootstrap.java'),
    (Join-Path $repoRoot 'modules/app-host/src/main/java/app/luoxianlv/service/PlaybackForegroundService.java'),
    (Join-Path $repoRoot 'modules/app-host/src/androidTest/java/app/luoxianlv/host/NativeServiceColdInstrumentation.java')) ''
