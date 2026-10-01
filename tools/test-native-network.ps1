param(
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$Serial = 'emulator-5554'
)

# 仅改变独立测试模拟器的网络。APP 内的仪器只观察真实生命周期和请求，不改调度状态。
$ErrorActionPreference = 'Stop'
if ($Serial -ne 'emulator-5554') { throw '只允许已明确用于本机热更验收的 emulator-5554' }
$networkRoot = Split-Path -Parent $PSScriptRoot
$networkRun = [Guid]::NewGuid().ToString('N')
$networkArea = 'files/native-host-network-checks/' + $networkRun
$networkOutput = Join-Path $networkRoot ('.local/native-network-' + $networkRun)
[void][IO.Directory]::CreateDirectory($networkOutput)
$networkPackage = 'app.luoxianlv.debug'
$networkStdout = Join-Path $networkOutput 'instrumentation.txt'
$networkStderr = Join-Path $networkOutput 'instrumentation.stderr.txt'
$networkInstrument = $null
$networkOriginal = @{}
$networkReply = $null
$networkFailure = $null
$networkCleanupErrors = [Collections.Generic.List[string]]::new()
$networkExpires = [DateTime]::UtcNow.AddSeconds(300)

function Invoke-NetworkAdb {
    $networkResult = & $Adb -s $Serial @args 2>$null
    if ($LASTEXITCODE -ne 0) { throw '本机测试模拟器 ADB 操作失败' }
    $networkResult
}

function Write-NetworkAck([string]$phase) {
    if ($phase -notin @('disconnect', 'reconnect')) { throw '网络握手阶段无效' }
    $networkAck = Join-Path $networkOutput ($phase + '.json')
    [IO.File]::WriteAllText($networkAck, (@{runId=$networkRun;phase=$phase} | ConvertTo-Json),
        [Text.UTF8Encoding]::new($false))
    $networkTemporary = '/data/local/tmp/lxhot-network-' + $networkRun + '.json'
    try {
        Invoke-NetworkAdb push $networkAck $networkTemporary | Out-Null
        Invoke-NetworkAdb shell chmod 644 $networkTemporary | Out-Null
        Invoke-NetworkAdb shell run-as $networkPackage cp $networkTemporary ($networkArea + '/ack.json.part') | Out-Null
        Invoke-NetworkAdb shell run-as $networkPackage mv ($networkArea + '/ack.json.part') ($networkArea + '/ack.json') | Out-Null
    } finally {
        Invoke-NetworkAdb shell rm -f $networkTemporary | Out-Null
    }
}

try {
    foreach ($networkSetting in @('wifi_on', 'mobile_data')) {
        $networkValue = (Invoke-NetworkAdb shell settings get global $networkSetting).Trim()
        if ($networkValue -notin @('0', '1')) { throw '无法确认系统原网络状态，拒绝改变' }
        $networkOriginal[$networkSetting] = $networkValue
    }
    if ($networkOriginal.wifi_on -ne '1' -and $networkOriginal.mobile_data -ne '1') {
        throw '测试必须从系统网络可用开始，不能自行打开原本关闭的网络'
    }
    $networkInstrument = Start-Process -FilePath $Adb -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $networkStdout -RedirectStandardError $networkStderr `
        -ArgumentList @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-e', 'networkRecovery',
            $networkRun, "$networkPackage.test/app.luoxianlv.host.NativeAppInstrumentation")
    # 总上界300秒，最后15秒留给停止仪器及核验网络还原。
    $networkDeadline = $networkExpires.AddSeconds(-15)
    $networkCompleted = [Collections.Generic.HashSet[string]]::new()
    while ($true) {
        $networkInstrument.Refresh()
        if ($networkInstrument.HasExited) { break }
        if ([DateTime]::UtcNow -gt $networkDeadline) { throw '真实断网恢复验收超时' }
        $networkRaw = & $Adb -s $Serial shell run-as $networkPackage cat ($networkArea + '/control.json') 2>$null
        if ($LASTEXITCODE -eq 0) {
            $networkControl = ($networkRaw -join "`n") | ConvertFrom-Json
            if ($networkControl.runId -ne $networkRun) { throw '网络握手身份失配' }
            $networkPhase = [string]$networkControl.phase
            if ($networkPhase -in @('disconnect', 'reconnect') -and $networkCompleted.Add($networkPhase)) {
                if ($networkPhase -eq 'disconnect') {
                    Write-Output '断开测试模拟器网络，APP将真实等待至少75秒并越过检查期限。'
                    Invoke-NetworkAdb shell svc wifi disable | Out-Null
                    Invoke-NetworkAdb shell svc data disable | Out-Null
                } else {
                    if (!$networkCompleted.Contains('disconnect')) { throw '尚未真实断网，拒绝冒充恢复' }
                    Write-Output '恢复原网络状态，观察自然恢复和单次合并检查。'
                    foreach ($networkPair in @(@('wifi', 'wifi_on'), @('data', 'mobile_data'))) {
                        $networkAction = if ($networkOriginal[$networkPair[1]] -eq '1') { 'enable' } else { 'disable' }
                        Invoke-NetworkAdb shell svc $networkPair[0] $networkAction | Out-Null
                    }
                }
                Write-NetworkAck $networkPhase
            }
        }
        Start-Sleep -Milliseconds 250
    }
    $networkText = [IO.File]::ReadAllText($networkStdout)
    if ($networkInstrument.ExitCode -ne 0 -or $networkText -notmatch '通过：真实断网' -or
        $networkText -match 'AssertionError|Process crashed|INSTRUMENTATION_FAILED') {
        throw "设备网络验收失败，原始记录：$networkStdout"
    }
    $networkReply = ((Invoke-NetworkAdb shell run-as $networkPackage cat ($networkArea + '/report.json')) -join "`n") | ConvertFrom-Json
    if (!$networkReply.passed -or $networkReply.runId -ne $networkRun -or
        !$networkCompleted.Contains('disconnect') -or !$networkCompleted.Contains('reconnect')) {
        throw '缺少本轮实际断网和恢复的完整成功回执'
    }
} catch {
    $networkFailure = $_
} finally {
    if ($networkInstrument) {
        try {
            $networkInstrument.Refresh()
            if (!$networkInstrument.HasExited) { Stop-Process -Id $networkInstrument.Id -ErrorAction Stop }
        } catch { $networkCleanupErrors.Add('停止本轮ADB客户端失败') }
        if ($networkFailure -or !$networkReply) {
            try { Invoke-NetworkAdb shell am force-stop $networkPackage | Out-Null }
            catch { $networkCleanupErrors.Add('停止失败的APP仪器失败') }
        }
    }
    foreach ($networkPair in @(@('wifi', 'wifi_on'), @('data', 'mobile_data'))) {
        if ($networkOriginal.ContainsKey($networkPair[1])) {
            try {
                $networkAction = if ($networkOriginal[$networkPair[1]] -eq '1') { 'enable' } else { 'disable' }
                Invoke-NetworkAdb shell svc $networkPair[0] $networkAction | Out-Null
            } catch { $networkCleanupErrors.Add('还原系统网络命令失败：' + $networkPair[1]) }
        }
    }
    foreach ($networkSetting in @('wifi_on', 'mobile_data')) {
        if (!$networkOriginal.ContainsKey($networkSetting)) { continue }
        try {
            do {
                $networkActual = (Invoke-NetworkAdb shell settings get global $networkSetting).Trim()
                if ($networkActual -eq $networkOriginal[$networkSetting]) { break }
                if ([DateTime]::UtcNow -ge $networkExpires) { throw '原网络状态未恢复' }
                Start-Sleep -Milliseconds 100
            } while ($true)
        } catch { $networkCleanupErrors.Add('系统原网络状态核验失败：' + $networkSetting) }
    }
    if ($networkInstrument) {
        try { Invoke-NetworkAdb shell am start -n "$networkPackage/app.luoxianlv.MainActivity" | Out-Null }
        catch { $networkCleanupErrors.Add('恢复APP首页失败') }
    }
}
if ($networkFailure) { throw $networkFailure }
if ($networkCleanupErrors.Count) { throw ($networkCleanupErrors -join '；') }
if (!$networkReply -or [DateTime]::UtcNow -gt $networkExpires) { throw '未在完整上界内结束网络验收与还原' }
$networkReply | Add-Member -NotePropertyName driverNetworkSettingsRestored -NotePropertyValue $true
$networkReply | Add-Member -NotePropertyName driverWaitLimitMs -NotePropertyValue 300000
[IO.File]::WriteAllText((Join-Path $networkOutput 'report.json'), ($networkReply | ConvertTo-Json -Depth 20),
    [Text.UTF8Encoding]::new($false))
Write-Output "通过：真实断网恢复验收及系统原网络还原；报告：$(Join-Path $networkOutput 'report.json')"
