param(
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$Serial = 'emulator-5554',
    [switch]$Group,
    [string]$Online,
    [string]$Fixture,
    [switch]$Rollback,
    [switch]$Persist
)

# 使用纯 Java 测试 APK；不能安装旧 :app 的 Kotlin 测试包，以免污染宿主类加载器。
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw '此脚本只允许操作模拟器' }
if ($Rollback -and !$Online) { throw '回退验收需要显式本机在线场景' }
if ($Persist -and (!$Online -or $Rollback)) { throw '持久启动验收需要显式本机成功激活场景' }
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
    if ($Online) {
        if ($Online -ne 'http://127.0.0.1:18472' -or !$Fixture) { throw '完整在线验收仅允许显式本机测试地址和资料目录' }
        $Fixture = (Resolve-Path -LiteralPath $Fixture).Path
        Invoke-Adb install -r (Join-Path $Fixture 'host-baseline.apk')
        # shell 写入外部目录可能继承旧安装的 FUSE 身份；由应用 UID 接收私有验收文件。
        Invoke-Adb shell run-as $package mkdir -p files/native-full-test
        foreach ($name in @('base.lxhp','root.public.json')) {
            $temporary = "/data/local/tmp/lxhot-fixture-$name"
            try {
                Invoke-Adb push (Join-Path $Fixture $name) $temporary
                Invoke-Adb shell chmod 644 $temporary
                Invoke-Adb shell run-as $package cp $temporary "files/native-full-test/$name"
            } finally {
                Invoke-Adb shell rm -f $temporary
            }
        }
        Invoke-Adb reverse tcp:18472 tcp:18472
    } else { Invoke-Adb install -r (Join-Path $repo 'modules/app-host/build/outputs/apk/debug/app-host-debug.apk') }
    Invoke-Adb install -r (Join-Path $repo 'modules/app-host/build/outputs/apk/androidTest/debug/app-host-debug-androidTest.apk')
    $reports = Join-Path $repo '.local'
    [System.IO.Directory]::CreateDirectory($reports) | Out-Null
    $report = Join-Path $reports ('native-host-' + [Guid]::NewGuid().ToString('N') + '.txt')
    $lines = [System.Collections.Generic.List[string]]::new()
    $options = if ($Online) { @('-e', 'online', $Online) } elseif ($Group) { @('-e', 'group', 'true') } else { @() }
    if ($Rollback) { $options += @('-e','onlineRollback','true') }
    if ($Persist) { $options += @('-e','onlinePersist','true') }
    & $Adb -s $Serial shell am instrument -w @options "$package.test/app.luoxianlv.host.NativeAppInstrumentation" |
        ForEach-Object { $lines.Add($_); $_ }
    $exitCode = $LASTEXITCODE
    $result = $lines -join "`n"
    [System.IO.File]::WriteAllText($report, $result, [System.Text.UTF8Encoding]::new($false))
    $onlinePassed = if ($Rollback) { $result -match '通过：完整 APP 在线回退' } else { $result -match '通过：完整 APP 真实在线整组更新' }
    if ($exitCode -ne 0 -or $result -notmatch '通过：宿主无 Kotlin/Compose/业务类' -or ($Group -and !$Online -and $result -notmatch '通过：真实页面与播放整组交接') -or ($Online -and !$onlinePassed) -or $result -match 'AssertionError|Process crashed|INSTRUMENTATION_FAILED') {
        throw "三层宿主回归失败，记录：$report"
    }
    if ($Persist) {
        $deviceReport = ((Invoke-Adb shell run-as $package cat files/native-full-online-report.json) -join "`n") | ConvertFrom-Json
        if (!$deviceReport.passed -or !$deviceReport.persistentStartupState -or $deviceReport.target -notmatch '^[a-f0-9]{64}$') {
            throw '缺少真实稳定确认的持久启动记录'
        }
        # 去掉本机网络转发并重启进程，证明从普通 Application 离线读取，而非复用旧加载器。
        Invoke-Adb reverse --remove tcp:18472
        Invoke-Adb shell am force-stop $package
        $cold = (Invoke-Adb shell am instrument -w -e startupSnapshot $deviceReport.target "$package.test/app.luoxianlv.host.NativeAppInstrumentation") -join "`n"
        [IO.File]::WriteAllText("$report.cold.txt", $cold, [Text.UTF8Encoding]::new($false))
        Write-Output $cold
        if ($cold -notmatch '通过：普通冷启动离线读取稳定签名版本并显示新增原生组件' -or $cold -match 'AssertionError|Process crashed|INSTRUMENTATION_FAILED') {
            throw "离线冷启动失败：$report.cold.txt"
        }
    }
} finally {
    # 即使测试进程崩溃，也由外部脚本恢复用户原来的无障碍设置。
    if ($priorServices -eq 'null') { Invoke-Adb shell settings delete secure enabled_accessibility_services }
    else { Invoke-Adb shell settings put secure enabled_accessibility_services $priorServices }
    if ($priorEnabled -eq 'null') { Invoke-Adb shell settings delete secure accessibility_enabled }
    else { Invoke-Adb shell settings put secure accessibility_enabled $priorEnabled }
    Invoke-Adb shell am start -n "$package/app.luoxianlv.MainActivity"
}
