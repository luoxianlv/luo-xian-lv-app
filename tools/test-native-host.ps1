param(
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$Serial = 'emulator-5554'
)

# 使用纯 Java 测试 APK；不能安装旧 :app 的 Kotlin 测试包，以免污染宿主类加载器。
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw '此脚本只允许操作模拟器' }
$repo = Split-Path -Parent $PSScriptRoot
$package = 'app.luoxianlv.debug'
$component = "$package/app.luoxianlv.service.MusicAccessibilityService"
function Invoke-Adb {
    $output = & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB 操作失败：$($args -join ' ')" }
    $output
}

$priorServices = (Invoke-Adb shell settings get secure enabled_accessibility_services).Trim()
$priorEnabled = (Invoke-Adb shell settings get secure accessibility_enabled).Trim()
$others = if ($priorServices -eq 'null' -or !$priorServices) { '' } else {
    ($priorServices.Split(':') | Where-Object { $_ -ne $component }) -join ':'
}
try {
    # instrumentation 会重启应用；先移除本应用的旧绑定，让测试进程重新连接。
    if (!$others) { Invoke-Adb shell settings delete secure enabled_accessibility_services }
    else { Invoke-Adb shell settings put secure enabled_accessibility_services $others }
    Invoke-Adb install -r (Join-Path $repo 'app-host/build/outputs/apk/debug/app-host-debug.apk')
    Invoke-Adb install -r (Join-Path $repo 'app-host/build/outputs/apk/androidTest/debug/app-host-debug-androidTest.apk')
    $reports = Join-Path $repo '.local'
    [System.IO.Directory]::CreateDirectory($reports) | Out-Null
    $report = Join-Path $reports ('native-host-' + [Guid]::NewGuid().ToString('N') + '.txt')
    $lines = [System.Collections.Generic.List[string]]::new()
    & $Adb -s $Serial shell am instrument -w "$package.test/app.luoxianlv.host.NativeAppInstrumentation" |
        ForEach-Object { $lines.Add($_); $_ }
    $exitCode = $LASTEXITCODE
    $result = $lines -join "`n"
    [System.IO.File]::WriteAllText($report, $result, [System.Text.UTF8Encoding]::new($false))
    if ($exitCode -ne 0 -or $result -notmatch '通过：宿主无 Kotlin/Compose/业务类' -or $result -match 'AssertionError|Process crashed|INSTRUMENTATION_FAILED') {
        throw "三层宿主回归失败，记录：$report"
    }
} finally {
    # 即使测试进程崩溃，也由外部脚本恢复用户原来的无障碍设置。
    if ($priorServices -eq 'null') { Invoke-Adb shell settings delete secure enabled_accessibility_services }
    else { Invoke-Adb shell settings put secure enabled_accessibility_services $priorServices }
    if ($priorEnabled -eq 'null') { Invoke-Adb shell settings delete secure accessibility_enabled }
    else { Invoke-Adb shell settings put secure accessibility_enabled $priorEnabled }
    Invoke-Adb shell am start -n "$package/app.luoxianlv.MainActivity"
}
