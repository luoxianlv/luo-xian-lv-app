param(
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$Serial = 'emulator-5554',
    [Parameter(Mandatory)][string]$Fixture,
    [Parameter(Mandatory)][string]$Target,
    [switch]$Fresh,
    [switch]$ReceiptFault,
    [switch]$RuntimeStartup
)

# 仅操作独立热更模拟器，Fresh 只清理本测试建立的内部热更状态，不删除谱子或设置。
$ErrorActionPreference = 'Stop'
if ($Serial -ne 'emulator-5554' -or $Target -notmatch '^[a-f0-9]{64}$') { throw '仅允许独立模拟器及明确目标快照' }
if ($RuntimeStartup -and $ReceiptFault) { throw '运行时与回执故障验收分别执行' }
$Fixture = (Resolve-Path -LiteralPath $Fixture).Path
$package = 'app.luoxianlv.debug'
$repo = Split-Path -Parent $PSScriptRoot
function Invoke-Adb {
    $output = & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB 操作失败：$($args -join ' ')" }
    $output
}
$services = (Invoke-Adb shell settings get secure enabled_accessibility_services).Trim()
$enabled = (Invoke-Adb shell settings get secure accessibility_enabled).Trim()
$report = Join-Path $repo ('.local/native-automatic-' + [Guid]::NewGuid().ToString('N') + '.txt')
try {
    Invoke-Adb shell am force-stop $package
    Invoke-Adb install -r (Join-Path $Fixture 'host-baseline.apk')
    if ($Fresh) {
        $sandbox = (Invoke-Adb shell run-as $package pwd).Trim()
        if ($sandbox -notmatch '^/data/(user/0|data)/app\.luoxianlv\.debug$') { throw '无法确认测试私有目录' }
        Invoke-Adb shell run-as $package rm -rf no_backup/native-update
    }
    Invoke-Adb install -r (Join-Path $repo 'modules/app-host/build/outputs/apk/androidTest/debug/app-host-debug-androidTest.apk')
    Invoke-Adb reverse tcp:18472 tcp:18472
    $lines = [Collections.Generic.List[string]]::new()
    $options = @('-e','automaticSnapshot',$Target)
    if ($RuntimeStartup) { $options = @('-e','restartPrepared',$Target) }
    if ($ReceiptFault) { $options += @('-e','receiptFault','true') }
    & $Adb -s $Serial shell am instrument -w @options "$package.test/app.luoxianlv.host.NativeAppInstrumentation" |
        ForEach-Object { $lines.Add($_); $_ }
    $exitCode = $LASTEXITCODE
    $result = $lines -join "`n"
    [IO.File]::WriteAllText($report, $result, [Text.UTF8Encoding]::new($false))
    $passed = if ($RuntimeStartup) { $result -match '通过：不同共享运行时完整缓存并持久等待重启' } else { $result -match '通过：普通入口自动更新验收' }
    if ($exitCode -ne 0 -or !$passed -or $result -match 'AssertionError|Process crashed|INSTRUMENTATION_FAILED') {
        throw "普通入口自动更新验收失败：$report"
    }
    if ($RuntimeStartup) {
        Invoke-Adb shell am force-stop $package
        $cold = (Invoke-Adb shell am instrument -w -e coldRuntime $Target "$package.test/app.luoxianlv.host.NativeAppInstrumentation") -join "`n"
        [IO.File]::WriteAllText("$report.runtime.txt", $cold, [Text.UTF8Encoding]::new($false))
        Write-Output $cold
        if ($cold -notmatch '通过：普通冷启动重新授权，实际新共享运行时' -or $cold -match 'AssertionError|Process crashed|INSTRUMENTATION_FAILED') {
            throw "运行时冷启动验收失败：$report.runtime.txt"
        }
    }
    if ($ReceiptFault) {
        if ($result -notmatch '通过：真实健康确认后队列写入受阻') { throw '未完成真实结果回执的故障注入' }
        Invoke-Adb shell am force-stop $package
        $recovered = (Invoke-Adb shell am instrument -w -e startupSnapshot $Target -e receiptRecovered true "$package.test/app.luoxianlv.host.NativeAppInstrumentation") -join "`n"
        [IO.File]::WriteAllText("$report.recovered.txt", $recovered, [Text.UTF8Encoding]::new($false))
        Write-Output $recovered
        if ($recovered -notmatch '通过：真实健康结果跨进程补发' -or $recovered -match 'AssertionError|Process crashed|INSTRUMENTATION_FAILED') {
            throw "跨进程结果回执补发失败：$report.recovered.txt"
        }
    }
} finally {
    Invoke-Adb reverse --remove tcp:18472
    if ($services -eq 'null') { Invoke-Adb shell settings delete secure enabled_accessibility_services }
    else { Invoke-Adb shell settings put secure enabled_accessibility_services $services }
    if ($enabled -eq 'null') { Invoke-Adb shell settings delete secure accessibility_enabled }
    else { Invoke-Adb shell settings put secure accessibility_enabled $enabled }
    Invoke-Adb shell am start -n "$package/app.luoxianlv.MainActivity"
}
