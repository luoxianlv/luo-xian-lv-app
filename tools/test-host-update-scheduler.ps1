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
$updateClasses = Join-Path $repoRoot 'modules/update-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$hostResources = Join-Path $repoRoot 'modules/app-host/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar'
foreach ($path in @($androidJar,$junit,$hamcrest,$hostClasses,$coreClasses,$contractClasses,$updateClasses,$hostResources)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "缺少离线编译输入：$path；此脚本不运行 Gradle。" }
}
$runRoot = Join-Path $repoRoot ('.local/host-scheduler-' + [Guid]::NewGuid().ToString('N'))
$sourceRoot = Join-Path $runRoot 'src'
New-Item -ItemType Directory -Path $sourceRoot | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
function Save-Source([string]$name,[string]$source) {
    $path = Join-Path $sourceRoot $name
    [IO.File]::WriteAllText($path,$source,$utf8)
    return $path
}
$looper = Save-Source 'Looper.java' @'
package android.os;
public final class Looper {
  private static final Looper MAIN=new Looper();
  public static Looper getMainLooper() { return MAIN; }
  public static Looper myLooper() { return MAIN; }
}
'@
$handler = Save-Source 'Handler.java' @'
package android.os;
import java.util.*;
public class Handler {
  private static final List<Runnable> TASKS=new ArrayList<>();
  public static int removeCalls;
  public Handler(Looper loop) {}
  public boolean post(Runnable task) { TASKS.add(task); return true; }
  public boolean postDelayed(Runnable task,long delay) { return post(task); }
  public void removeCallbacks(Runnable task) { removeCalls++; TASKS.removeIf(value -> value==task); }
  public void removeCallbacksAndMessages(Object token) { reset(); }
  public static int count() { return TASKS.size(); }
  public static void reset() { TASKS.clear(); removeCalls=0; }
  public static void drain() { List<Runnable> tasks=new ArrayList<>(TASKS); TASKS.clear(); for(Runnable task:tasks) task.run(); }
}
'@
$clock = Save-Source 'SystemClock.java' @'
package android.os;
public final class SystemClock {
  public static long now;
  public static long elapsedRealtime() { return now; }
}
'@
$log = Save-Source 'Log.java' @'
package android.util;
public final class Log {
  public static final int WARN=5,ERROR=6;
  public static int w(String tag,String message) { return 0; }
  public static int w(String tag,String message,Throwable error) { return 0; }
  public static int e(String tag,String message,Throwable error) { return 0; }
  public static int i(String tag,String message) { return 0; }
}
'@
$network = Save-Source 'Network.java' @'
package android.net;
public final class Network {}
'@
$capabilities = Save-Source 'NetworkCapabilities.java' @'
package android.net;
public final class NetworkCapabilities {
  public static final int NET_CAPABILITY_INTERNET=12,NET_CAPABILITY_VALIDATED=16;
  public boolean validated;
  public boolean hasCapability(int value) { return value==12 || value==16&&validated; }
}
'@
$connectivity = Save-Source 'ConnectivityManager.java' @'
package android.net;
import android.os.Handler;
public class ConnectivityManager {
  public boolean connected,validated;
  public int unregistered,queries;
  public boolean rejectQuery;
  public Network getActiveNetwork() { queries++; if(rejectQuery) throw new AssertionError("Binder queried on usage edge"); return connected?new Network():null; }
  public NetworkCapabilities getNetworkCapabilities(Network network) {
    NetworkCapabilities value=new NetworkCapabilities(); value.validated=validated; return value;
  }
  public boolean isActiveNetworkMetered() { return true; }
  public void registerDefaultNetworkCallback(NetworkCallback callback,Handler handler) {}
  public void unregisterNetworkCallback(NetworkCallback callback) { unregistered++; }
  public static class NetworkCallback {
    public void onAvailable(Network network) {}
    public void onLost(Network network) {}
    public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities) {}
  }
}
'@
$bundle = Save-Source 'Bundle.java' @'
package android.os;
import java.util.*;
public class Bundle {
  private final Map<String,Object> values=new HashMap<>();
  public void putBoolean(String name,boolean value) { values.put(name,value); }
  public boolean getBoolean(String name) { return Boolean.TRUE.equals(values.get(name)); }
  public boolean containsKey(String name) { return values.containsKey(name); }
}
'@
$bootstrap = Save-Source 'Bootstrap.java' @'
package app.luoxianlv.host;
import app.luoxianlv.hot.*;
import java.util.*;
import java.util.concurrent.Executor;
public final class Bootstrap {
  public static boolean testForeground,testPlayback,testPreparing,testCanActivate,testBusinessWorking;
  public static final class Source { public NativeLoader.Prepared prepared; }
  public static Source source() { return null; }
  public static boolean foregroundInUse() { return testForeground; }
  public static boolean playbackInUse() { return testPlayback; }
  public static boolean inUse() { return testForeground||testPlayback; }
  public static boolean playbackPreparing() { return testPreparing; }
  public static boolean businessWorking() { return testBusinessWorking; }
  public static boolean ordinaryUpdateIdle() { return !testPlayback&&!testPreparing; }
  public static boolean playbackReady() { return testPlayback; }
  public static boolean resourcesReady() { return true; }
  public static List<GroupHandover.Page> pages() { return List.of(); }
  public static boolean canAutoActivate() { return testCanActivate; }
  public static boolean supportsLiveWork() { return true; }
  public static boolean diagnosticsAllowed() { return false; }
  public static void stopBusiness(Throwable failure) {}
  public static void recoveryPersisted(boolean value) {}
  public static GroupActivation activate(ActivationController controller,ActivationController.Ticket ticket,
      NativeLoader.Prepared prepared,Executor executor,GroupActivation.Listener listener) { return null; }
}
'@
$baseClasspath = "$coreClasses;$contractClasses;$updateClasses;$hostClasses;$hostResources;$androidJar;$junit;$hamcrest"
$schedule = Join-Path $repoRoot 'modules/hot-core/src/main/java/app/luoxianlv/hot/UpdateSchedule.java'
$scheduleTest = Join-Path $repoRoot 'modules/hot-core/src/test/java/app/luoxianlv/hot/UpdateScheduleTest.java'
$hostSource = Join-Path $repoRoot 'modules/app-host/src/main/java/app/luoxianlv/host/HostUpdates.java'
$hostUpdateHelpers = @('HostUpdateReports','HostUpdateContent') | ForEach-Object {
    Join-Path $repoRoot ("modules/app-host/src/main/java/app/luoxianlv/host/$_.java")
}
$test = Join-Path $repoRoot 'tools/test-support/HostUpdateSchedulerTest.java'
function Run-Checks([string]$name,[string[]]$inputs,[string[]]$tests) {
    $sharedSources = @('UpdateCancellation','HotApiClient','HttpObjectSource','ObjectDownloader','UpdateClient',
        'HotObjectDeltaResolver','HotByteDeltaPlan','ApkUpdateBridge','ApkDeliveryVerifier',
        'BundledBaseline','PreparationSpace','HotSignatures','TrustStore') | ForEach-Object {
        Join-Path $repoRoot ("modules/hot-core/src/main/java/app/luoxianlv/hot/$_.java")
    }
    $sharedSources += Join-Path $repoRoot 'modules/hot-contract/src/main/java/app/luoxianlv/hot/contract/SharedUpdate.java'
    $inputs = @($sharedSources + $hostUpdateHelpers + $inputs | Select-Object -Unique)
    $classes = Join-Path $runRoot $name
    New-Item -ItemType Directory -Path $classes | Out-Null
    $arguments = @('-encoding','UTF-8','--release','17','-classpath',$baseClasspath,'-d',$classes) + $inputs
    $argumentFile = Join-Path $runRoot ($name+'.args')
    [IO.File]::WriteAllLines($argumentFile,($arguments | ForEach-Object { '"'+$_.Replace('\','/')+'"' }),$utf8)
    & javac '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' "@$argumentFile"
    if($LASTEXITCODE -ne 0) { throw "独立编译失败：$name" }
    & java -cp "$classes;$baseClasspath" org.junit.runner.JUnitCore @tests
    if($LASTEXITCODE -ne 0) { throw "独立检查失败：$name" }
}
$platform = @($looper,$handler,$clock,$log,$network,$capabilities,$connectivity,$bundle)
Write-Host '真实 UpdateSchedule 与 HostUpdates；Android 网络/时钟/Handler、Bootstrap输入和worker为JVM替身，不发HTTP。'
Run-Checks 'host' ($platform + @($bootstrap,$schedule,$scheduleTest,$hostSource,$test)) @('app.luoxianlv.hot.UpdateScheduleTest','app.luoxianlv.host.HostUpdateSchedulerTest')
Write-Host '真实 Bootstrap 状态查询；不创建业务加载器，不改变许可或健康。'
Run-Checks 'bootstrap' ($platform + @($schedule,$hostSource,$test,
    (Join-Path $repoRoot 'modules/app-host/src/main/java/app/luoxianlv/host/Bootstrap.java'))) @('app.luoxianlv.host.HostUpdateSchedulerTest$PlaybackPriorityTest')
Write-Host "离线结果目录：$runRoot"
