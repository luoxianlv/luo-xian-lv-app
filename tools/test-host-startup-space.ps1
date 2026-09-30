param([string]$CoreClasses)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $CoreClasses) {
    $candidate = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.local') -Directory -Filter 'space-jvm-*' |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'classes/app/luoxianlv/hot/PreparationSpace.class') } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $candidate) { throw '请用 -CoreClasses 指定已有核心 JVM classes；本工具不运行 Gradle。' }
    $CoreClasses = Join-Path $candidate.FullName 'classes'
}
$userProfilePath = [Environment]::GetFolderPath('UserProfile')
$sdkJar = Join-Path $userProfilePath 'AppData/Local/Android/Sdk/platforms/android-37.0/android.jar'
$libraryCache = Join-Path $userProfilePath '.gradle/caches/modules-2/files-2.1'
$junitJar = Get-ChildItem -LiteralPath (Join-Path $libraryCache 'junit/junit/4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1
$hamcrestJar = Get-ChildItem -LiteralPath (Join-Path $libraryCache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar' | Select-Object -First 1
if (-not (Test-Path -LiteralPath $sdkJar) -or -not $junitJar -or -not $hamcrestJar) { throw '本地 SDK/JUnit/hamcrest 不完整。' }
$runRoot = Join-Path $projectRoot ('.local/host-startup-space-' + [Guid]::NewGuid().ToString('N'))
$sourceRoot = Join-Path $runRoot 'src'
$classes = Join-Path $runRoot 'classes'
New-Item -ItemType Directory -Path $sourceRoot, $classes | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$loaderPath = Join-Path $sourceRoot 'NativeLoader.java'
[IO.File]::WriteAllText($loaderPath, @'
package app.luoxianlv.hot;
import android.content.Context;
import java.util.*;
/** JVM test double: actual trust/cache/budget, no Android execution or APK class loading. */
public final class NativeLoader {
  public static boolean testOnlySpaceDeferred;
  public static List<String> testOnlyAttempts = new ArrayList<>();
  public static int testOnlyDiscardCalls;
  private final ContentStore store;
  public NativeLoader(Context context, ContentStore store, ContentQuarantine quarantine,
      long host, Set<String> mounts) { this.store = store; }
  public static final class Prepared {
    public final HotManifest manifest;
    Prepared(HotManifest manifest) { this.manifest = manifest; }
  }
  public Prepared prepareStable(ContentStore.Snapshot snapshot, ActivationJournal.State state,
      TrustStore trust, String environment) throws Exception {
    testOnlyAttempts.add(snapshot.manifest.snapshotId);
    snapshot.manifest.compatible("app.luoxianlv.debug", environment, 1, Set.of());
    trust.verifyStable(snapshot, state);
    store.verifySnapshotObjects(snapshot);
    if (testOnlySpaceDeferred)
      new PreparationSpace(path -> new PreparationSpace.Volume("test", 16L << 20, 4096))
          .admit(List.of(new PreparationSpace.Demand(snapshot.directory, 1)));
    return new Prepared(snapshot.manifest);
  }
  public boolean discardUninitializedRuntime(String hash) { testOnlyDiscardCalls++; return true; }
}
'@, $utf8)
$logPath = Join-Path $sourceRoot 'Log.java'
[IO.File]::WriteAllText($logPath, @'
package android.util;
public final class Log { public static int w(String tag, String message) { return 0; } }
'@, $utf8)
$classpath = "$CoreClasses;$sdkJar;$($junitJar.FullName);$($hamcrestJar.FullName)"
$arguments = @('-encoding', 'UTF-8', '-source', '17', '-target', '17', '-classpath', $classpath, '-d', $classes,
    $loaderPath, $logPath,
    (Join-Path $projectRoot 'app-host/src/main/java/app/luoxianlv/host/HostStartup.java'),
    (Join-Path $projectRoot 'tools/test-support/HostStartupSpaceTest.java'))
$argumentFile = Join-Path $runRoot 'javac.args'
[IO.File]::WriteAllLines($argumentFile, ($arguments | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), $utf8)
& javac "@$argumentFile"
if ($LASTEXITCODE -ne 0) { throw '独立 javac 编译失败。' }
$resourceRoot = Join-Path $projectRoot 'hot-core/src/test/resources'
Write-Host '验证真实 HostStartup/日志/签名/缓存；NativeLoader 使用隔离 JVM double，不执行 Android APK。'
& java -cp "$classes;$classpath;$resourceRoot" org.junit.runner.JUnitCore app.luoxianlv.host.HostStartupSpaceTest
if ($LASTEXITCODE -ne 0) { throw '宿主空间选择回归失败。' }
