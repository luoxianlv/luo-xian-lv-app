param()
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$originalLocation = Get-Location
try {
    Set-Location -LiteralPath $repoRoot
    . (Join-Path $PSScriptRoot 'test-host-update-scheduler.ps1')
    $baseClasspath += ';' + (Join-Path $repoRoot 'hot-core/src/test/resources')
    $coreSources = @('HotApiClient','HttpObjectSource','ObjectDownloader','UpdateClient') | ForEach-Object {
        Join-Path $repoRoot ("hot-core/src/main/java/app/luoxianlv/hot/$_.java")
    }
    $tests = @('UpdateCancellationTest','HttpObjectSourceTest','DownloadGrantTest','OnlineClientTest')
    $testSources = $tests | ForEach-Object { Join-Path $repoRoot ("hot-core/src/test/java/app/luoxianlv/hot/$_.java") }
    Write-Host '真实回环慢请求/慢读取取消、预算断点与HTTP授权兼容；不访问真实API或OSS。'
    Run-Checks 'cancellation-http' ($coreSources + $testSources + @(
        (Join-Path $repoRoot 'hot-core/src/testSupport/java/app/luoxianlv/hot/LoopbackHttp.java')
    )) ($tests | ForEach-Object { "app.luoxianlv.hot.$_" })
    $commandLooper = Save-Source 'CommandLooper.java' @'
package android.os;
public final class Looper {
  private static final Looper MAIN = new Looper(), BACKGROUND = new Looper();
  private static boolean isMain = true;
  public static Looper getMainLooper() { return MAIN; }
  public static Looper myLooper() { return isMain ? MAIN : BACKGROUND; }
  public static void setMain(boolean value) { isMain = value; }
}
'@
    # javac公开类名必须与文件名一致；只替换本次随机测试目录中的平台文件。
    $commandLooperPath = Join-Path $sourceRoot 'Looper.java'
    [IO.File]::WriteAllText($commandLooperPath,[IO.File]::ReadAllText($commandLooper),$utf8)
    Run-Checks 'command-priority' ($platform + @(
        (Join-Path $repoRoot 'hot-core/src/main/java/app/luoxianlv/hot/NativeAccessibilityService.java'),
        (Join-Path $repoRoot 'tools/test-support/PlaybackCommandPriorityTest.java')
    )) @('app.luoxianlv.hot.PlaybackCommandPriorityTest')
    Write-Host "取消链检查目录：$runRoot"
} finally { Set-Location -LiteralPath $originalLocation }
