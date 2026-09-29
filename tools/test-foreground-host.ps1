param(
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$Serial = 'emulator-5554'
)

# 只用于 Debug 模拟器回归；在测试进程建立后重连无障碍，结束后恢复权限。
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

Invoke-Adb install -r (Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk')
Invoke-Adb install -r (Join-Path $repo 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
$priorServices = (Invoke-Adb shell settings get secure enabled_accessibility_services).Trim()
$priorEnabled = (Invoke-Adb shell settings get secure accessibility_enabled).Trim()
$notificationAllowed = ((Invoke-Adb shell dumpsys package $package) -match 'android.permission.POST_NOTIFICATIONS: granted=true').Count -gt 0
$others = if ($priorServices -eq 'null' -or !$priorServices) { '' } else {
    ($priorServices.Split(':') | Where-Object { $_ -ne $component }) -join ':'
}
$enabled = if (!$others) { $component } else { $others + ':' + $component }
$reports = Join-Path $repo '.local'
[System.IO.Directory]::CreateDirectory($reports) | Out-Null
$report = Join-Path $reports ('foreground-' + [Guid]::NewGuid().ToString('N') + '.txt')
$test = $null
try {
    Invoke-Adb shell pm grant $package android.permission.POST_NOTIFICATIONS
    if (!$others) { Invoke-Adb shell settings delete secure enabled_accessibility_services }
    else { Invoke-Adb shell settings put secure enabled_accessibility_services $others }
    Invoke-Adb shell am force-stop $package
    $test = Start-Process -FilePath $Adb -ArgumentList '-s',$Serial,'shell','am','instrument','-w',"$package.test/app.luoxianlv.PlaybackServiceInstrumentation" -WindowStyle Hidden -RedirectStandardOutput $report -RedirectStandardError ($report + '.stderr') -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    do {
        $appProcess = & $Adb -s $Serial shell pidof $package
        if ($appProcess -or $test.HasExited) { break }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    if (!$appProcess) { throw '测试进程未启动' }
    Invoke-Adb shell settings put secure enabled_accessibility_services $enabled
    Invoke-Adb shell settings put secure accessibility_enabled 1
    if (!$test.WaitForExit(55000)) { throw '播放服务回归超时' }
    $result = Get-Content -LiteralPath $report -Encoding utf8 -Raw
    $result
    Get-Content -LiteralPath ($report + '.stderr') -Encoding utf8
    # am instrument 即使失败也可能退出 0，必须检查 runner 的明确成功结果。
    if ($test.ExitCode -ne 0 -or $result -notmatch '^通过：') { throw "播放服务回归失败，记录：$report" }
} finally {
    if ($null -ne $test) {
        if (!$test.HasExited) { Invoke-Adb shell am force-stop $package }
        $test.Dispose()
    }
    if ($priorServices -eq 'null') { Invoke-Adb shell settings delete secure enabled_accessibility_services }
    else { Invoke-Adb shell settings put secure enabled_accessibility_services $priorServices }
    if ($priorEnabled -eq 'null') { Invoke-Adb shell settings delete secure accessibility_enabled }
    else { Invoke-Adb shell settings put secure accessibility_enabled $priorEnabled }
    if (!$notificationAllowed) { Invoke-Adb shell pm revoke $package android.permission.POST_NOTIFICATIONS }
}
