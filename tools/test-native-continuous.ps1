param(
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$Serial = 'emulator-5554',
    [Parameter(Mandatory)][string]$Fixture,
    [Parameter(Mandatory)][string[]]$Candidates,
    [Parameter(Mandatory)][string]$AuthDirectory,
    [string]$Lxhot,
    [string]$HostApk,
    [ValidateSet('http://127.0.0.1:18472','https://www.luoxianlv.cn')]
    [string]$TestOrigin = 'http://127.0.0.1:18472',
    [switch]$Fresh
)

# 一次 instrumentation 保持同一 PID。前三份或更多候选真实确认，最后一份故障回退。
# 先用本轮普通宿主构建冻结 runtime.apk/base.lxhp，再逐次构建、冻结不同业务 APK：
# :app-business:assembleDebug -PnativeBusinessProbe=true -PappVersionName=1.0.9-loop-A/B/C/D
# Candidates 是 Fixture 内已签名包；显式 TestOrigin 可验证真实 HTTPS/OSS，仍只允许 Debug/test。
$ErrorActionPreference = 'Stop'
if ($Serial -ne 'emulator-5554' -or !$Fresh) { throw '只允许独立 emulator-5554，并显式使用 Fresh 测试热更状态' }
if ($Candidates.Count -lt 4 -or $Candidates.Count -gt 12) { throw '至少三份健康候选及最后一份回退候选，至多十二份' }
$repo = Split-Path -Parent $PSScriptRoot
$hotRepo = [IO.Path]::GetFullPath((Join-Path $repo '../luo-xian-lv-hot-update'))
$localRoot = [IO.Path]::GetFullPath((Join-Path $hotRepo '.local'))
$Fixture = (Resolve-Path -LiteralPath $Fixture).Path
$AuthDirectory = (Resolve-Path -LiteralPath $AuthDirectory).Path
foreach ($path in @($Fixture, $AuthDirectory)) {
    if (!$path.StartsWith($localRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw '资料与授权目录必须位于 CLI 仓库忽略的 .local 内'
    }
}
if (!$Lxhot) { $Lxhot = Join-Path $hotRepo 'bin/lxhot.exe' }
$Lxhot = (Resolve-Path -LiteralPath $Lxhot).Path
$currentHost = Join-Path $repo 'app-host/build/outputs/apk/debug/app-host-debug.apk'
if (!$HostApk) { $HostApk = $currentHost }
$HostApk = (Resolve-Path -LiteralPath $HostApk).Path
if ((Get-FileHash -LiteralPath $HostApk -Algorithm SHA256).Hash -ne
    (Get-FileHash -LiteralPath $currentHost -Algorithm SHA256).Hash) {
    throw '宿主冻结副本必须与本轮当前 app-host Debug 输出完全一致；不能复用旧 host-baseline.apk'
}
$testApk = (Resolve-Path -LiteralPath (Join-Path $repo 'app-host/build/outputs/apk/androidTest/debug/app-host-debug-androidTest.apk')).Path
$rootKey = (Resolve-Path -LiteralPath (Join-Path $Fixture 'root.public.json')).Path
$tokenFile = (Resolve-Path -LiteralPath (Join-Path $AuthDirectory 'admin-token')).Path
$origin = $TestOrigin
$package = 'app.luoxianlv.debug'
$component = "$package/app.luoxianlv.service.MusicAccessibilityService"
$runId = [Guid]::NewGuid().ToString('N')
$continuousSource = [IO.File]::ReadAllText((Join-Path $repo 'app-host/src/androidTest/java/app/luoxianlv/host/NativeContinuousChecks.java'))
$continuousBudgetMatch = [regex]::Matches($continuousSource, 'static final long STAGE_TIMEOUT_MILLIS = ([0-9]+);')
if ($continuousBudgetMatch.Count -ne 1) { throw '无法读取本轮实际仪器共同阶段期限' }
$stageTimeoutMillis = [long]$continuousBudgetMatch[0].Groups[1].Value
$output = Join-Path $repo ('.local/native-continuous-' + $runId)
[IO.Directory]::CreateDirectory($output) | Out-Null

function Invoke-Lxhot([string[]]$CommandArgs) {
    # 原始错误与回执可能含服务端 URL；只解析机器结果，不输出这些原文。
    $priorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $raw = (& $Lxhot --json @CommandArgs 2>$null | Out-String)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $priorPreference }
    if ($code -ne 0) { throw "本机 CLI 操作失败：$($CommandArgs[0])，退出码 $code；未输出原始服务回执" }
    try { $reply = $raw | ConvertFrom-Json } catch { throw '本机 CLI 没有返回有效机器 JSON' }
    if (!$reply.ok) { throw "本机 CLI 拒绝操作：$($CommandArgs[0])" }
    $reply.data
}

function Invoke-Adb {
    $raw = & $Adb -s $Serial @args 2>$null
    if ($LASTEXITCODE -ne 0) { throw '独立模拟器 ADB 操作失败' }
    $raw
}

function Save-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 16), [Text.UTF8Encoding]::new($false))
}

# CONTINUOUS_POLICY_BEGIN：离线负例提取实际的范围和Fresh操作顺序，不调用设备。
function Select-ContinuousServices([string]$Value, [string]$Component) {
    if ($Value -notmatch '^(null|[A-Za-z0-9_.$/:]*)$') { throw '无障碍设置格式不支持安全还原' }
    if ($Value -eq 'null' -or !$Value) { return '' }
    $short = $Component.Split('/')[0] + '/.service.MusicAccessibilityService'
    ($Value.Split(':') | Where-Object { $_ -ne $Component -and $_ -ne $short }) -join ':'
}

function Assert-ContinuousArtifactScope($PackageReply) {
    if (@($PackageReply.artifacts).Count -ne 2 -or
        @($PackageReply.artifacts | Where-Object role -eq 'runtime').Count -ne 1 -or
        @($PackageReply.artifacts | Where-Object role -eq 'business').Count -ne 1 -or
        @($PackageReply.artifacts | Where-Object { $_.role -notin @('runtime','business') -or $_.mount }).Count -ne 0) {
        throw '本轮连续压力只允许runtime+business且无官方挂载；资源压力须单独验收'
    }
}

function Start-ContinuousInstrumentation {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Adb
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in @('-s',$Serial,'shell','am','instrument','-w','-e','continuousPlan',
            'files/native-continuous/plan.json',"$package.test/app.luoxianlv.host.NativeAppInstrumentation")) {
        $start.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (!$process.Start()) { throw '本轮连续仪器ADB客户端未启动' }
    [pscustomobject]@{Process=$process;Out=$process.StandardOutput.ReadToEndAsync();Err=$process.StandardError.ReadToEndAsync()}
}

function Wait-ContinuousOutput($Child, [int]$Maximum) {
    if (!$Child -or $Maximum -lt 0 -or $Maximum -gt 15000) { throw '仪器输出等待必须有界' }
    $until = [Environment]::TickCount64 + $Maximum
    if (!$Child.Process.WaitForExit($Maximum)) { return $false }
    $out = $Child.Out.Wait([int][Math]::Max(0,$until-[Environment]::TickCount64))
    $err = $Child.Err.Wait([int][Math]::Max(0,$until-[Environment]::TickCount64))
    $out -and $err
}

function Save-ContinuousOutput {
    if ($instrument.Out.IsCompletedSuccessfully) {
        [IO.File]::WriteAllText($stdout,$instrument.Out.Result,[Text.UTF8Encoding]::new($false))
    }
    if ($instrument.Err.IsCompletedSuccessfully) {
        [IO.File]::WriteAllText($stderr,$instrument.Err.Result,[Text.UTF8Encoding]::new($false))
    }
}

function Assert-ContinuousFailureReport($Report, $State) {
    if ($State.runId -cne $runId -or $State.phase -cne 'failed' -or $State.pid -le 0 -or
        $Report.schema -ne 1 -or $Report.passed -isnot [bool] -or $Report.passed -or
        $Report.runId -cne $runId -or $Report.pid -ne $State.pid -or
        @($Report.failureChain).Count -lt 1 -or @($Report.failureChain).Count -gt 24 -or
        !$Report.lastStage -or $Report.lastStage.completedStages -ne @($Report.stages).Count) {
        throw '不是本run/PID的安全连续失败回执'
    }
    foreach ($row in $Report.failureChain) {
        if ($row.relation -cnotin @('root','cause','suppressed') -or $row.type -cnotmatch '^[a-zA-Z0-9_.$]+$' -or
            $row.message -isnot [string] -or $row.message.Length -gt 512 -or
            ($row.type -cne 'app.luoxianlv.host.NativeContinuousChecks$CheckFailure' -and $row.message -cne '外部异常详情已隐藏')) {
            throw '连续失败回执异常链未脱敏或格式无效'
        }
    }
}

function Receive-ContinuousFailure($State) {
    # failed已对应原子安全回执；runner仍需完成finally/finish，最多8秒刷出原客户端和两条输出流。
    $flushed = Wait-ContinuousOutput $instrument 8000
    Save-ContinuousOutput
    $raw = ((Invoke-Adb shell run-as $package cat files/native-continuous/report.json) -join "`n")
    $diagnostic = $raw | ConvertFrom-Json
    Assert-ContinuousFailureReport $diagnostic $State
    $diagnostic | Add-Member -NotePropertyName driverFailureOutputFlushed -NotePropertyValue $flushed
    Save-Json (Join-Path $output 'failure-report.json') $diagnostic
}

function Assert-ContinuousSuccessReport($Report) {
    if ($Report.passed -isnot [bool] -or !$Report.passed -or !$Report.helperPlaybackStateRestored -or
        @($Report.PSObject.Properties.Name | Where-Object { $_ -cin @('failureType','failureChain','lastStage') }).Count) {
        throw '成功连续回执缺少自身收尾或仍含失败诊断'
    }
}

function Test-ContinuousHomeResumed([string]$Activities) {
    # API36实际topResumedActivity=与ResumedActivity:，其他版本mResumedActivity:；仅当前Resumed行。
    $Activities -cmatch '(?m)^\s*(?:(?:mResumedActivity|topResumedActivity)\s*[:=]|ResumedActivity\s*:)[^\r\n]*[ \t]app\.luoxianlv\.debug/(app\.luoxianlv\.|\.)?MainActivity(?:[ \t}\r]|$)'
}

function Set-ContinuousServices([string]$Value) {
    if ($Value -notmatch '^(null|[A-Za-z0-9_.$/:]*)$') { throw '无障碍设置格式不支持安全还原' }
    if ($Value -eq 'null') { Invoke-Adb shell settings delete secure enabled_accessibility_services | Out-Null }
    else { Invoke-Adb shell ("settings put secure enabled_accessibility_services '" + $Value + "'") | Out-Null }
}

function Wait-ContinuousProcessStopped {
    $stoppedUntil = [DateTime]::UtcNow.AddSeconds(15)
    while ($true) {
        $running = & $Adb -s $Serial shell pidof $package 2>$null
        $code = $LASTEXITCODE
        if ($code -in @(0,1) -and !($running -join '').Trim()) { return }
        if ($code -notin @(0,1)) { throw '无法确认Debug目标进程已停止，拒绝归档' }
        if ([DateTime]::UtcNow -gt $stoppedUntil) { throw 'Debug进程仍在运行，拒绝移动其热更状态' }
        Start-Sleep -Milliseconds 100
    }
}

function Prepare-ContinuousFresh([string]$OriginalServices) {
    $others = Select-ContinuousServices $OriginalServices $component
    Set-ContinuousServices $others
    Invoke-Adb shell am force-stop $package | Out-Null
    Wait-ContinuousProcessStopped
    $sandbox = ((Invoke-Adb shell run-as $package pwd) -join "`n").Trim()
    if ($sandbox -notmatch '^/data/(user/0|data)/app\.luoxianlv\.debug$') { throw '无法确认测试私有目录' }
    foreach ($area in @('no_backup/native-update', 'files/native-continuous')) {
        $exists = & $Adb -s $Serial shell run-as $package test -e $area 2>$null
        if ($LASTEXITCODE -eq 0) {
            Invoke-Adb shell run-as $package mv $area ($area + '-archive-' + $runId) | Out-Null
        } elseif ($LASTEXITCODE -ne 1) { throw '无法确认上一轮测试区，拒绝覆盖' }
    }
    # 已归档且没有本Debug服务可启动，再更新宿主；旧隔离/预算/证据不会删除。
    Invoke-Adb install -r $HostApk | Out-Null
    Invoke-Adb shell run-as $package mkdir -p files/native-continuous | Out-Null
}

function Read-ContinuousReverse {
    $found = @()
    foreach ($line in @(Invoke-Adb reverse --list)) {
        $parts = ([string]$line).Trim() -split '\s+'
        if ($parts.Count -ge 2 -and $parts[-2] -eq 'tcp:18472') {
            if ($parts[-1] -notmatch '^tcp:[1-9][0-9]{0,4}$' -or [int]$parts[-1].Substring(4) -gt 65535) {
                throw '现有18472反向映射格式不支持安全还原'
            }
            $found += $parts[-1]
        }
    }
    if ($found.Count -gt 1) { throw '同端口有多个反向映射，拒绝改变' }
    if ($found.Count) { return $found[0] }
    return ''
}

function Restore-ContinuousEnvironment([string]$OriginalServices, [string]$OriginalEnabled, [string]$OriginalReverse) {
    $errors = [Collections.Generic.List[string]]::new()
    try { Invoke-Adb shell am force-stop $package | Out-Null }
    catch { $errors.Add('停止本轮Debug进程失败') }
    try {
        if ($OriginalReverse) { Invoke-Adb reverse tcp:18472 $OriginalReverse | Out-Null }
        elseif (Read-ContinuousReverse) { Invoke-Adb reverse --remove tcp:18472 | Out-Null }
    } catch { $errors.Add('还原18472反向映射失败') }
    try { Set-ContinuousServices $OriginalServices }
    catch { $errors.Add('还原原无障碍组件失败') }
    try {
        if ($OriginalEnabled -eq 'null') { Invoke-Adb shell settings delete secure accessibility_enabled | Out-Null }
        else { Invoke-Adb shell settings put secure accessibility_enabled $OriginalEnabled | Out-Null }
    } catch { $errors.Add('还原原无障碍启用标志失败') }
    try { Invoke-Adb shell am start -n "$package/app.luoxianlv.MainActivity" | Out-Null }
    catch { $errors.Add('恢复Debug首页失败') }
    # 操作失败不跳过其他恢复；settings/reverse必须回读，不能仅信命令exit0。
    foreach ($setting in @(@('enabled_accessibility_services',$OriginalServices),@('accessibility_enabled',$OriginalEnabled))) {
        try {
            $until = [DateTime]::UtcNow.AddSeconds(15)
            do {
                $actual = ((Invoke-Adb shell settings get secure $setting[0]) -join "`n").Trim()
                if ($actual -eq $setting[1]) { break }
                if ([DateTime]::UtcNow -ge $until) { throw '原无障碍设置值未恢复' }
                Start-Sleep -Milliseconds 100
            } while ($true)
        } catch { $errors.Add('原无障碍设置核验失败：' + $setting[0]) }
    }
    try { if ((Read-ContinuousReverse) -ne $OriginalReverse) { throw '原映射未恢复' } }
    catch { $errors.Add('原18472反向映射核验失败') }
    try {
        $until = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $activity = (Invoke-Adb shell dumpsys activity activities) -join "`n"
            if (Test-ContinuousHomeResumed $activity) { break }
            if ([DateTime]::UtcNow -ge $until) { throw 'Debug首页未恢复为实际Resumed窗口' }
            Start-Sleep -Milliseconds 100
        } while ($true)
    } catch { $errors.Add('实际Debug首页核验失败') }
    $errors.ToArray()
}
# CONTINUOUS_POLICY_END

function Read-ApkJson([string]$Name) {
    $zip = [IO.Compression.ZipFile]::OpenRead($HostApk)
    try {
        $entry = $zip.GetEntry($Name)
        if (!$entry -or $entry.Length -gt 65536) { throw '当前宿主缺少有界的基线或自动更新配置' }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try { $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}

function Send-File([string]$Local, [string]$Remote) {
    if ($Remote -notmatch '^files/native-continuous/[a-z0-9.-]+\.json$') { throw '测试写入目标越界' }
    $temporary = '/data/local/tmp/lxhot-continuous-' + $runId + '.json'
    try {
        Invoke-Adb push $Local $temporary | Out-Null
        Invoke-Adb shell chmod 644 $temporary | Out-Null
        Invoke-Adb shell run-as $package cp $temporary ($Remote + '.part') | Out-Null
        Invoke-Adb shell run-as $package mv ($Remote + '.part') $Remote | Out-Null
    } finally { Invoke-Adb shell rm -f $temporary | Out-Null }
}

function Read-State {
    $raw = & $Adb -s $Serial shell run-as $package cat files/native-continuous/state.json 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    try {
        $state = ($raw -join "`n") | ConvertFrom-Json
        if ($state.runId -eq $runId) { return $state }
    } catch { }
    $null
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$config = Read-ApkJson 'assets/hot/config.json'
$baseline = Read-ApkJson 'assets/baseline/index.json'
$metadata = Get-Content -LiteralPath (Join-Path $repo 'app-host/build/outputs/apk/debug/output-metadata.json') -Raw | ConvertFrom-Json
$versionCode = [long]$metadata.elements[0].versionCode
if ($versionCode -lt 1 -or $versionCode -gt [int]::MaxValue) { throw '宿主安装版本号无效' }
$publicRoot = Get-Content -LiteralPath $rootKey -Raw | ConvertFrom-Json
if ($config.applicationId -ne $package -or $config.environment -ne 'test' -or
    $config.origin -ne $origin -or $config.hostContract -ne 1 -or $config.automatic -eq $false -or !$config.testHealthReports -or
    !$publicRoot.keyId -or $config.root.keyId -ne $publicRoot.keyId -or
    $config.root.algorithm -ne $publicRoot.algorithm -or $config.root.spki -ne $publicRoot.spki) {
    throw '当前宿主必须明确启用同一本机 test 根和健康回报'
}
$frozenRuntime = Join-Path $Fixture 'runtime.apk'
if ((Get-FileHash -LiteralPath $frozenRuntime -Algorithm SHA256).Hash.ToLowerInvariant() -ne $baseline.runtime.sha256) {
    throw '本轮冻结运行时不匹配最新宿主基线'
}
$basePackage = Join-Path $Fixture 'base.lxhp'
$base = Invoke-Lxhot @('verify', $basePackage, '--root', $rootKey, '--host-contract', '1', '--app-version-code', [string]$versionCode)
Assert-ContinuousArtifactScope $base
$baseRuntime = @($base.artifacts | Where-Object role -eq 'runtime')
$baseBusiness = @($base.artifacts | Where-Object role -eq 'business')
if (!$base.complete -or $base.applicationId -ne $package -or $base.environment -ne 'test' -or
    $baseRuntime.Count -ne 1 -or $baseBusiness.Count -ne 1 -or
    $baseRuntime[0].sha256 -ne $baseline.runtime.sha256 -or $baseBusiness[0].sha256 -ne $baseline.business.sha256) {
    throw '签名基础包必须匹配当前宿主实际内置的运行时及业务 APK'
}
$stages = @()
$packagePaths = @()
$snapshots = [Collections.Generic.HashSet[string]]::new()
$businesses = [Collections.Generic.HashSet[string]]::new()
foreach ($name in $Candidates) {
    if ($name -notmatch '^[A-Za-z0-9._-]+\.lxhp$') { throw '候选必须是 Fixture 内普通 .lxhp 文件名' }
    $path = (Resolve-Path -LiteralPath (Join-Path $Fixture $name)).Path
    $candidate = Invoke-Lxhot @('verify', $path, '--root', $rootKey, '--host-contract', '1', '--app-version-code', [string]$versionCode)
    Assert-ContinuousArtifactScope $candidate
    $runtime = @($candidate.artifacts | Where-Object role -eq 'runtime')
    $business = @($candidate.artifacts | Where-Object role -eq 'business')
    if (!$candidate.complete -or $candidate.applicationId -ne $package -or $candidate.environment -ne 'test' -or
        $candidate.activation -ne 'live' -or $runtime.Count -ne 1 -or $business.Count -ne 1 -or
        $candidate.runtimeAbi -ne $base.runtimeAbi -or $runtime[0].sha256 -ne $baseline.runtime.sha256 -or
        $business[0].entryClass -ne 'app.luoxianlv.hot.probe.HotProbeFactory' -or
        !$snapshots.Add($candidate.snapshotId) -or !$businesses.Add($business[0].sha256) -or
        $business[0].sha256 -eq $baseline.business.sha256) { throw '候选必须签名有效、不同业务内容、共享冻结运行时且仅含 Debug 原生探针' }
    $stages += [ordered]@{ snapshot=$candidate.snapshotId; business=$business[0].sha256; mode='healthy' }
    $packagePaths += $path
}
$stages[-1].mode = 'rollback'
$plan = [ordered]@{ schema=1; runId=$runId; testOrigin=$origin; appVersionCode=$versionCode; runtime=$baseline.runtime.sha256; baselineSnapshot=$base.snapshotId; stageTimeoutMillis=$stageTimeoutMillis; stages=$stages }
$planPath = Join-Path $output 'plan.json'
Save-Json $planPath $plan
$services = ((Invoke-Adb shell settings get secure enabled_accessibility_services) -join "`n").Trim()
$enabled = ((Invoke-Adb shell settings get secure accessibility_enabled) -join "`n").Trim()
[void](Select-ContinuousServices $services $component)
if ($enabled -notin @('null','0','1')) { throw '无障碍启用状态无法安全保存还原' }
$originalReverse = Read-ContinuousReverse
$instrument = $null
$continuousFailure = $null
$continuousCleanupErrors = [Collections.Generic.List[string]]::new()
$deviceReport = $null
$fallback = $base.snapshotId
$lastPublished = $null
$stdout = Join-Path $output 'instrumentation.txt'
$stderr = Join-Path $output 'instrumentation.stderr.txt'
try {
    Prepare-ContinuousFresh $services
    Invoke-Adb install -r $testApk | Out-Null
    if ($origin -eq 'http://127.0.0.1:18472') { Invoke-Adb reverse tcp:18472 tcp:18472 | Out-Null }
    Send-File $planPath 'files/native-continuous/plan.json'
    # 返回值、对象回读地址和服务端许可均不写入设备计划或公开报告。
    Invoke-Lxhot @('upload', $basePackage, '--root', $rootKey, '--server', $origin, '--token-file', $tokenFile) | Out-Null
    foreach ($path in $packagePaths) {
        Invoke-Lxhot @('upload', $path, '--root', $rootKey, '--server', $origin, '--token-file', $tokenFile) | Out-Null
    }
    $instrument = Start-ContinuousInstrumentation
    $firstPid = $null
    for ($index=0; $index -lt $stages.Count; $index++) {
        $deadline = [DateTime]::UtcNow.AddSeconds(180)
        do {
            $state = Read-State
            if ($state -and $state.phase -eq 'failed') { Receive-ContinuousFailure $state; throw "连续设备验收失败；安全根因：$(Join-Path $output 'failure-report.json')；输出：$stdout" }
            $instrument.Process.Refresh()
            if ($instrument.Process.HasExited) { throw "连续仪器进程提前结束；记录：$stdout" }
            if ($state -and $state.phase -eq 'ready' -and $state.index -eq $index) { break }
            if ([DateTime]::UtcNow -gt $deadline) { throw '设备没有到达预期连续阶段' }
            Start-Sleep -Milliseconds 250
        } while ($true)
        if ($state.snapshot -ne $stages[$index].snapshot -or ($firstPid -and $state.pid -ne $firstPid) -or
            $state.stageTimeoutMillis -ne $stageTimeoutMillis) { throw '阶段握手的快照、PID或实际仪器期限不符' }
        $firstPid = $state.pid
        $status = Invoke-Lxhot @('status', '--application-id', $package, '--environment', 'test', '--server', $origin, '--token-file', $tokenFile)
        $channel = $status.channel
        $publish = @('publish', $stages[$index].snapshot, '--fallback', $fallback, '--mode', 'direct', '--scope', 'all-compatible',
            '--expect-revision', [string]$channel.revision, '--server', $origin, '--token-file', $tokenFile)
        if ($channel.current -and $channel.current.id) { $publish += @('--replace-release', $channel.current.id) }
        Invoke-Lxhot $publish | Out-Null
        $lastPublished = $stages[$index].snapshot
        $goPath = Join-Path $output ("go-$index.json")
        Save-Json $goPath ([ordered]@{runId=$runId;index=$index;snapshot=$lastPublished})
        Send-File $goPath ("files/native-continuous/go-$index.json")
        Write-Output "已投放连续阶段 $($index+1)/$($stages.Count)，PID $firstPid，模式 $($stages[$index].mode)"
        $deadline = [DateTime]::UtcNow.AddMilliseconds($stageTimeoutMillis)
        do {
            $state = Read-State
            if ($state -and $state.phase -eq 'failed') { Receive-ContinuousFailure $state; throw "连续设备验收失败；安全根因：$(Join-Path $output 'failure-report.json')；输出：$stdout" }
            if ($state -and $state.pid -ne $firstPid) { throw '阶段运行时PID改变' }
            if ($state -and (($state.phase -eq 'passed' -and $state.index -eq $index) -or
                ($state.phase -eq 'ready' -and $state.index -eq ($index+1)) -or
                ($state.phase -eq 'complete' -and $index -eq ($stages.Count-1)))) { break }
            $instrument.Process.Refresh()
            if ($instrument.Process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw '连续阶段超过共同总期限或仪器提前退出' }
            Start-Sleep -Milliseconds 100
        } while ($true)
        $partial = ((Invoke-Adb shell run-as $package cat files/native-continuous/report.json) -join "`n") | ConvertFrom-Json
        if ($partial.runId -ne $runId -or $partial.pid -ne $firstPid -or $partial.stages.Count -le $index) {
            throw '阶段成功信号没有同run/PID的实际回执'
        }
        $row = $partial.stages[$index]
        if ($row.index -ne $index -or $row.snapshot -ne $lastPublished -or $row.business -ne $stages[$index].business -or
            $row.stageTimeoutMillis -ne $stageTimeoutMillis -or $row.stageElapsedMillis -gt $stageTimeoutMillis -or
            ($stages[$index].mode -eq 'healthy' -and $row.observedActiveMillis -lt 60000)) {
            throw '阶段实际字节、真实60秒观察或总期限不符'
        }
        if ($stages[$index].mode -eq 'healthy') { $fallback = $lastPublished }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $state = Read-State
        if ($state -and $state.phase -eq 'complete') { break }
        if ($state -and $state.phase -eq 'failed') { Receive-ContinuousFailure $state; throw "连续设备验收失败；安全根因：$(Join-Path $output 'failure-report.json')；输出：$stdout" }
        $instrument.Process.Refresh()
        if ($instrument.Process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw '连续设备验收未完成最终回退' }
        Start-Sleep -Milliseconds 250
    } while ($true)
    if (!(Wait-ContinuousOutput $instrument 15000) -or $instrument.Process.ExitCode -ne 0) { throw '连续 instrumentation 未正常结束或输出未刷出' }
    Save-ContinuousOutput
    $deviceReport = ((Invoke-Adb shell run-as $package cat files/native-continuous/report.json) -join "`n") | ConvertFrom-Json
    Assert-ContinuousSuccessReport $deviceReport
    if (!$deviceReport.passed -or $deviceReport.runId -ne $runId -or $deviceReport.pid -ne $firstPid -or
        $deviceReport.stageTimeoutMillis -ne $stageTimeoutMillis -or $deviceReport.stages.Count -ne $stages.Count -or
        @($deviceReport.stages | Where-Object { $_.mode -eq 'healthy' -and $_.observedActiveMillis -ge 60000 }).Count -ne ($stages.Count-1)) {
        throw '缺少同一 PID 的逐轮真实健康及退役边界报告'
    }
    $text = [IO.File]::ReadAllText($stdout)
    if ($text -notmatch '通过：同一 PID 连续不同业务候选' -or $text -match 'AssertionError|Process crashed|INSTRUMENTATION_FAILED') { throw '连续仪器结果未通过' }
    # 本地最后一轮故障后恢复渠道指向已验证版本；修订并发校验仍由真实 CLI/API 执行。
    $status = Invoke-Lxhot @('status', '--application-id', $package, '--environment', 'test', '--server', $origin, '--token-file', $tokenFile)
    if ($status.channel.current.snapshotId -ne $lastPublished) { throw '本机渠道被其他任务改变，拒绝覆盖' }
    Invoke-Lxhot @('rollback', $status.channel.current.id, '--to', $fallback, '--reason', '本机连续热更验收恢复已验证版本',
        '--expect-revision', [string]$status.channel.revision, '--application-id', $package, '--environment', 'test',
        '--server', $origin, '--token-file', $tokenFile) | Out-Null
    $deviceReport | Add-Member -NotePropertyName localChannelRestoredTo -NotePropertyValue $fallback
    $deviceReport | Add-Member -NotePropertyName hostApkSha256 -NotePropertyValue (Get-FileHash -LiteralPath $HostApk -Algorithm SHA256).Hash.ToLowerInvariant()
} catch {
    $continuousFailure = $_
} finally {
    if ($instrument) {
        try {
            $instrument.Process.Refresh()
            if (!$instrument.Process.HasExited) { Stop-Process -Id $instrument.Process.Id -ErrorAction Stop }
        } catch { $continuousCleanupErrors.Add('停止本轮ADB客户端失败') }
        try { [void](Wait-ContinuousOutput $instrument 2000); Save-ContinuousOutput }
        catch { $continuousCleanupErrors.Add('保存本轮仪器输出失败') }
    }
    foreach ($cleanupError in @(Restore-ContinuousEnvironment $services $enabled $originalReverse)) {
        $continuousCleanupErrors.Add($cleanupError)
    }
}
if ($continuousFailure -or $continuousCleanupErrors.Count) {
    $failureMessage = if ($continuousFailure) { [string]$continuousFailure.Exception.Message } else { '连续设备观察未完整收尾' }
    if ($continuousCleanupErrors.Count) { $failureMessage += '；收尾错误：' + ($continuousCleanupErrors -join '；') }
    throw $failureMessage
}
if (!$deviceReport) { throw '缺少完整连续设备回执' }
$deviceReport | Add-Member -NotePropertyName driverOriginalAccessibilityRestored -NotePropertyValue $true
$deviceReport | Add-Member -NotePropertyName driverOriginalReverseRestored -NotePropertyValue $true
$deviceReport | Add-Member -NotePropertyName driverHomeRestored -NotePropertyValue $true
$deviceReport | Add-Member -NotePropertyName driverRestartedAfterObservation -NotePropertyValue $true
Save-Json (Join-Path $output 'report.json') $deviceReport
Write-Output "通过：同一 PID 连续更新、真实健康、整组回退、持有上界及原环境还原；报告：$(Join-Path $output 'report.json')"
