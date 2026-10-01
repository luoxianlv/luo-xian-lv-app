#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$Fixture,
    [Parameter(Mandatory)][string]$Candidate,
    [Parameter(Mandatory)][string]$AuthDirectory,
    [Parameter(Mandatory)][string]$Lxhot,
    [Parameter(Mandatory)][string]$Gateway,
    [Parameter(Mandatory)][ValidateSet('cancellation','delta')][string]$Mode,
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$Serial = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'

# DOWNLOAD_DRIVER_POLICY_BEGIN：独立测试只提取这些真实纯策略，不执行CLI/设备/网络。
function Get-DownloadScopedPath([string]$Path, [string]$Root) {
    $full = [IO.Path]::GetFullPath($Path)
    $base = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (!$full.StartsWith($base + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw '路径必须位于明确的本机资料子目录，不能使用根目录或越界路径'
    }
    $full
}
function Test-DownloadJsonNode($Node, [int]$Depth = 0) {
    if ($Depth -gt 30) { throw '机器JSON层级过深' }
    if ($Node.ValueKind -eq [Text.Json.JsonValueKind]::Object) {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($property in $Node.EnumerateObject()) {
            if (!$names.Add($property.Name)) { throw '机器JSON包含重复或大小写混淆字段' }
            Test-DownloadJsonNode $property.Value ($Depth + 1)
        }
    } elseif ($Node.ValueKind -eq [Text.Json.JsonValueKind]::Array) {
        foreach ($value in $Node.EnumerateArray()) { Test-DownloadJsonNode $value ($Depth + 1) }
    }
}
function Convert-DownloadJson([string]$Raw, [int]$Limit = 1048576) {
    if (!$Raw -or [Text.Encoding]::UTF8.GetByteCount($Raw) -gt $Limit -or $Raw[0] -eq [char]0xfeff) {
        throw '机器JSON为空、带BOM或过大'
    }
    try {
        $document = [Text.Json.JsonDocument]::Parse($Raw)
        try {
            if ($document.RootElement.ValueKind -ne [Text.Json.JsonValueKind]::Object) { throw '机器JSON根必须是对象' }
            Test-DownloadJsonNode $document.RootElement
        } finally { $document.Dispose() }
        $Raw | ConvertFrom-Json -Depth 30
    } catch { throw '机器JSON无效或字段重复' }
}
function Assert-DownloadCandidate($Verified, $Template, [string]$Mode) {
    $allowed = @('schema','runId','mode','targetSnapshotId','sourceIdentity','expectedObjects','expectedMissingBytes','slowObjectSha','prefixBytes')
    foreach ($name in $Template.PSObject.Properties.Name) {
        if (![Collections.Generic.HashSet[string]]::new([string[]]$allowed, [StringComparer]::Ordinal).Contains([string]$name)) { throw '计划模板包含未声明字段' }
    }
    if ($Verified.applicationId -cne 'app.luoxianlv.debug' -or $Verified.environment -cne 'test' -or
        $Verified.activation -cne 'live' -or $Verified.mode -notin @('full','delta') -or
        $Verified.snapshotId -cnotmatch '^[a-f0-9]{64}$' -or $Template.targetSnapshotId -cne $Verified.snapshotId) {
        throw '候选必须验签通过并属于本机Debug/test即时资源快照'
    }
    if ($Template.sourceIdentity -and $Template.sourceIdentity -cnotmatch '^[a-f0-9]{64}$') { throw '原稳定来源身份无效' }
    if ($Template.mode -and $Template.mode -cne $Mode) { throw '模板和明确模式不同' }
    $objects = @($Template.expectedObjects)
    if ($objects.Count -lt 1 -or $objects.Count -gt 8) { throw '计划只允许1至8个小资源对象' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    [long]$total = 0
    [long]$slowSize = 0
    foreach ($object in $objects) {
        if ($object.sha256 -cnotmatch '^[a-f0-9]{64}$' -or !$seen.Add([string]$object.sha256) -or
            $object.size -isnot [long] -and $object.size -isnot [int] -or $object.size -le 0 -or $object.size -gt 1048576) {
            throw '计划资源身份、大小或重复对象无效'
        }
        $artifact = @($Verified.artifacts | Where-Object { $_.sha256 -ceq $object.sha256 })
        if (!$artifact.Count -or @($artifact | Where-Object { $_.size -ne $object.size -or $_.role -notin @('resources','config') }).Count) {
            throw '计划小对象不属于候选已签名资源'
        }
        $total += [long]$object.size
        if ($object.sha256 -ceq $Template.slowObjectSha) { $slowSize = [long]$object.size }
    }
    if ($total -ne $Template.expectedMissingBytes -or $total -gt 2097152 -or !$slowSize -or
        $Template.prefixBytes -lt $(if ($Mode -eq 'cancellation') {1} else {0}) -or
        $Template.prefixBytes -ge $slowSize -or $Template.prefixBytes -gt 32768) { throw '缺失总量或慢对象前缀不一致' }
}
function Assert-DownloadControl($Control, [string]$RunId, [string]$Mode, [string]$Target) {
    if ($Control.schema -ne 1 -or $Control.runId -cne $RunId -or $Control.mode -cne $Mode -or
        $Control.targetSnapshotId -cne $Target -or $Control.sourceIdentity -cnotmatch '^[a-f0-9]{64}$' -or
        $Control.phase -notin @('arm','request_captured','cancelled','complete','failed')) { throw '不是本轮实际下载握手' }
}
function Assert-DownloadOwnedChannel($Channel, $Ownership) {
    if (!$Ownership -or !$Ownership.id -or !$Channel.current -or
        $Channel.current.id -cne $Ownership.id -or $Channel.current.snapshotId -cne $Ownership.target -or
        [long]$Channel.revision -ne [long]$Ownership.revision) { throw '渠道已被其他操作改变，拒绝覆盖' }
}
function Select-DownloadRollback($Channel, $Ownership, [string]$Source) {
    Assert-DownloadOwnedChannel $Channel $Ownership
    if ($Source -cnotmatch '^[a-f0-9]{64}$' -or $Source -ceq $Ownership.target) { throw '恢复来源无效' }
    @('rollback', [string]$Ownership.id, '--to', $Source, '--reason', '本机下载取消验收恢复原稳定来源',
        '--expect-revision', [string]$Ownership.revision, '--application-id', 'app.luoxianlv.debug', '--environment', 'test')
}
function Assert-DownloadReport($Report, [string]$RunId, [string]$Mode, [string]$Target, [string]$Source, [long]$Missing) {
    if ($Report.passed -isnot [bool] -or !$Report.passed -or $Report.runId -cne $RunId -or $Report.mode -cne $Mode -or
        $Report.targetSnapshotId -cne $Target -or $Report.initialSourceIdentity -cne $Source -or
        $Report.expectedMissingBytes -ne $Missing -or !$Report.homeRestored -or !$Report.stageClosed -or
        $Report.clockInjected -isnot [bool] -or $Report.clockInjected -or $Report.updateStateInjected -or
        $Report.explicitCheckCalled -or $Report.explicitActivationCalled -or $Report.healthInjected) { throw '缺少本轮实际完整设备成功回执' }
    if ($Mode -eq 'delta') {
        if ($Report.objectReadBytes -ne $Missing -or !$Report.action.exactMissingBytesObserved -or
            !$Report.action.naturalHealthyJournalObserved -or !$Report.action.naturalActivationObserved) {
            throw '小资源正文read或同次自然健康未完成'
        }
    } elseif (!$Report.action.selectionUnchanged -or $Report.action.prefixBytesRetained -le 0 -or
        $Report.action.stopped.inFlight -or $Report.action.stopped.busy) { throw '取消未保留前缀/选择或未结束在途任务' }
}
function Assert-DownloadMetrics($Metrics, [string]$RunId, [string]$SlowHash, [long]$Size, [string]$Target) {
    if ($Metrics.schema -ne 1 -or $Metrics.runId -cne $RunId -or $Metrics.slowObjectSha -cne $SlowHash -or
        @($Metrics.requests).Count -lt 1) { throw '缺少本轮真实慢对象代理请求记录' }
    [long]$sum = 0; [int]$index = 0
    foreach ($row in $Metrics.requests) {
        if ($row.index -ne $index -or $row.snapshotId -cne $Target -or $row.offset -lt 0 -or $row.offset -ge $Size -or
            $row.status -notin @(200,206) -or $row.bodyBytesReturnedToProxy -lt 0 -or
            $row.bodyBytesReturnedToProxy -gt $Size - $row.offset) { throw '代理请求索引、范围或计数无效' }
        $sum += [long]$row.bodyBytesReturnedToProxy; $index++
    }
    $sum
}
# DOWNLOAD_DRIVER_POLICY_END

if ($Serial -cne 'emulator-5554') { throw '仅允许明确的独立 emulator-5554' }
$appRoot = Split-Path -Parent $PSScriptRoot
$hotRoot = [IO.Path]::GetFullPath((Join-Path $appRoot '../luo-xian-lv-hot-update'))
$localRoot = Join-Path $hotRoot '.local'
$Fixture = Get-DownloadScopedPath $Fixture $localRoot
$AuthDirectory = Get-DownloadScopedPath $AuthDirectory $localRoot
if (![IO.Path]::IsPathRooted($Candidate)) { $Candidate = Join-Path $Fixture $Candidate }
$Candidate = Get-DownloadScopedPath $Candidate $Fixture
if ([IO.Path]::GetExtension($Candidate) -cne '.lxhp') { throw '候选必须是Fixture内已签名.lxhp' }
$origin = 'http://127.0.0.1:18472'; $package = 'app.luoxianlv.debug'
$rootKey = Join-Path $Fixture 'root.public.json'
$tokenFile = Join-Path $AuthDirectory 'admin-token' # 仅传CLI，不读取或输出内容。
$runId = [guid]::NewGuid().ToString('N')
$output = Join-Path $Fixture ('download-run-' + $runId)
$remote = 'files/native-download-checks/' + $runId
$expires = [DateTime]::UtcNow.AddSeconds(300); $closing = $false
$proxy = $null; $instrument = $null; $reverseOwned = $false
$ownership = $null; $publishArguments = $null; $publishAttempted = $false; $restored = $null
$failure = $null; $report = $null; $metrics = $null; $source = $null
$cleanupErrors = [Collections.Generic.List[string]]::new()
$utf8 = [Text.UTF8Encoding]::new($false, $true)

function Assert-DownloadNoLinks([string]$Path, [string]$Boundary) {
    $current = [IO.Path]::GetFullPath($Path); $boundaryPath = [IO.Path]::GetFullPath($Boundary)
    while ($true) {
        $item = Get-Item -LiteralPath $current -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '资料路径不能包含链接' }
        if ($current.Equals($boundaryPath, [StringComparison]::OrdinalIgnoreCase)) { return }
        $current = Split-Path -Parent $current
        if (!$current) { throw '资料路径边界不匹配' }
    }
}
function Read-DownloadJson([string]$Path, [int]$Limit = 1048576) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or $item.Attributes -band [IO.FileAttributes]::ReparsePoint -or $item.Length -gt $Limit) { throw '公开JSON类型或大小无效' }
    Convert-DownloadJson ([IO.File]::ReadAllText($Path, $utf8)) $Limit
}
function Save-DownloadJson([string]$Path, $Value) {
    $temporary = $Path + '.' + [guid]::NewGuid().ToString('N') + '.part'
    try {
        [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 25), $utf8)
        [IO.File]::Move($temporary, $Path, $true)
    } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
}
function Get-DownloadRemaining([int]$Maximum = 30000) {
    $limit = if ($closing) { $expires } else { $expires.AddSeconds(-15) }
    $left = [long]($limit - [DateTime]::UtcNow).TotalMilliseconds
    if ($left -le 0) { throw '下载driver共同观察/收尾期限用尽' }
    [int][Math]::Min($left, $Maximum)
}
function Start-DownloadProcess([string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory = $hotRoot) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable; $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = $utf8; $info.StandardErrorEncoding = $utf8
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
    if (!$process.Start()) { throw '本轮外部进程无法启动' }
    [pscustomobject]@{Process=$process;Out=$process.StandardOutput.ReadToEndAsync();Err=$process.StandardError.ReadToEndAsync()}
}
function Invoke-DownloadCommand([string]$Executable, [string[]]$Arguments, [int]$Maximum = 30000) {
    $child = Start-DownloadProcess $Executable $Arguments
    try {
        if (!$child.Process.WaitForExit((Get-DownloadRemaining $Maximum))) { $child.Process.Kill(); throw '本轮外部命令等待超时' }
        if (!$child.Out.Wait(1000) -or !$child.Err.Wait(1000)) { throw '本轮外部命令管道未结束' }
        [pscustomobject]@{Code=$child.Process.ExitCode;Output=$child.Out.Result}
    } finally {
        if (!$child.Process.HasExited) { $child.Process.Kill() }
        $child.Process.Dispose()
    }
}
function Invoke-DownloadLxhot([string[]]$Arguments) {
    $reply = Invoke-DownloadCommand $Lxhot (@('--json') + $Arguments) 45000
    if ($reply.Code -ne 0) { throw '本机CLI操作失败，未输出原始凭据或服务回执' }
    $value = Convert-DownloadJson $reply.Output
    if ($value.ok -isnot [bool] -or !$value.ok -or $value.code -cne 'ok') { throw '本机CLI拒绝操作' }
    $value.data
}
function Invoke-DownloadAdb([string[]]$Arguments, [switch]$AllowMissing) {
    $reply = Invoke-DownloadCommand $Adb (@('-s',$Serial) + $Arguments) 5000
    if ($reply.Code -ne 0) {
        if ($AllowMissing) { return $null }
        throw '本轮独立模拟器ADB操作失败'
    }
    $reply.Output
}
function Send-DownloadFile([string]$Local, [string]$Remote) {
    $temporary = '/data/local/tmp/lxhot-dl-' + $runId + '.json'
    try {
        Invoke-DownloadAdb @('push',$Local,$temporary) | Out-Null
        Invoke-DownloadAdb @('shell','chmod','644',$temporary) | Out-Null
        Invoke-DownloadAdb @('shell','run-as',$package,'cp',$temporary,($Remote+'.part')) | Out-Null
        Invoke-DownloadAdb @('shell','run-as',$package,'mv',($Remote+'.part'),$Remote) | Out-Null
    } finally { Invoke-DownloadAdb @('shell','rm','-f',$temporary) | Out-Null }
}
function Write-DownloadAck([string]$Phase) {
    $path = Join-Path $output ($Phase + '-ack.json')
    Save-DownloadJson $path ([ordered]@{schema=1;runId=$runId;phase=$Phase})
    Send-DownloadFile $path ($remote + '/ack.json')
}
function Release-DownloadProxy {
    $temporary = Join-Path $output 'released.part'
    [IO.File]::WriteAllText($temporary,$runId,$utf8)
    [IO.File]::Move($temporary,(Join-Path $output 'released'),$true)
}
function Read-DownloadChannel {
    $status = Invoke-DownloadLxhot @('status','--application-id',$package,'--environment','test','--server',$origin,'--token-file',$tokenFile)
    if (!$status.channel -or $status.channel.revision -lt 0 -or $status.channel.revision -gt 9007199254740991) { throw '本机渠道回执无效' }
    $status.channel
}
function Resolve-DownloadPublication($Reply) {
    if ($Reply.requestId -cne $runId -or !$Reply.response.current -or
        $Reply.response.current.snapshotId -cne $template.targetSnapshotId -or
        $Reply.response.current.fallbackId -cne $source -or $Reply.response.current.id -cnotmatch '^[a-f0-9-]{36}$' -or
        [long]$Reply.response.revision -ne [long]$originalChannel.revision+1) { throw '本轮发布身份/修订回执不匹配' }
    [pscustomobject]@{id=$Reply.response.current.id;revision=[long]$Reply.response.revision;target=[string]$template.targetSnapshotId}
}
function Restore-DownloadChannel {
    $channel = Read-DownloadChannel
    if (!$ownership -and $publishAttempted) {
        if ($channel.current.snapshotId -ceq $template.targetSnapshotId -and
            [long]$channel.revision -eq [long]$originalChannel.revision+1) {
            # 只在可能已提交的修订重放原幂等请求；非本请求提交会409，不能取得其他发布归属。
            $script:ownership = Resolve-DownloadPublication (Invoke-DownloadLxhot $publishArguments)
        } else { throw '发布提交结果尚未确认，保留诊断且不覆盖其他渠道决定' }
    }
    if (!$ownership) { return }
    $arguments = Select-DownloadRollback $channel $ownership $source
    $reply = Invoke-DownloadLxhot ($arguments + @('--request-id',($runId+'-rollback'),'--server',$origin,'--token-file',$tokenFile))
    if (!$reply.response.current -or $reply.response.current.snapshotId -cne $source -or
        [long]$reply.response.revision -ne [long]$ownership.revision+1) { throw '本轮恢复回执不匹配' }
    $script:restored = [pscustomobject]@{id=$reply.response.current.id;revision=[long]$reply.response.revision;target=$source}
}

try {
    Assert-DownloadNoLinks $Fixture $localRoot
    Assert-DownloadNoLinks $AuthDirectory $localRoot # 目录元数据检查；不读授权文件。
    Assert-DownloadNoLinks $Candidate $Fixture
    foreach ($exe in @($Lxhot,$Gateway,$Adb)) { if (!(Test-Path -LiteralPath $exe -PathType Leaf)) { throw '缺少明确本机可执行工具' } }
    $public = Read-DownloadJson $rootKey 4096
    if ($public.schema -ne 1 -or $public.algorithm -cne 'ecdsa-p256-sha256' -or $public.purpose -cne 'root' -or
        $public.keyId -cnotmatch '^[a-f0-9]{64}$' -or !$public.spki) { throw '必须提供Fixture内合法公开根资料' }
    $template = Read-DownloadJson (Join-Path $Fixture 'plan-template.json') 65536
    $verified = Invoke-DownloadLxhot @('verify',$Candidate,'--root',$rootKey,'--host-contract','1')
    Assert-DownloadCandidate $verified $template $Mode
    $registered = Invoke-DownloadLxhot @('upload',$Candidate,'--root',$rootKey,'--host-contract','1','--server',$origin,'--token-file',$tokenFile)
    if ($registered.snapshotId -cne $template.targetSnapshotId -or $registered.published -ne $false) { throw '候选未准确登记或发生非预期发布' }
    if (Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18474 -State Listen -ErrorAction SilentlyContinue) { throw '18474已有进程，拒绝接管' }
    $reverse = Invoke-DownloadAdb @('reverse','--list')
    if ($reverse -match 'tcp:18472\s') { throw '18472已有ADB反向端口，拒绝覆盖' }
    [void][IO.Directory]::CreateDirectory($output)
    $plan = [ordered]@{schema=1;runId=$runId;mode=$Mode;targetSnapshotId=$template.targetSnapshotId;sourceIdentity=[string]$template.sourceIdentity;
        expectedObjects=@($template.expectedObjects);expectedMissingBytes=[long]$template.expectedMissingBytes;slowObjectSha=$template.slowObjectSha;prefixBytes=[long]$template.prefixBytes}
    Save-DownloadJson (Join-Path $output 'plan.json') $plan
    Save-DownloadJson (Join-Path $output 'config.json') ([ordered]@{schema=1;runId=$runId;slowObjectSha=$template.slowObjectSha;prefixBytes=[long]$template.prefixBytes})
    $proxy = Start-DownloadProcess $Gateway @('--state-dir',$output)
    do {
        $proxy.Process.Refresh(); if ($proxy.Process.HasExited) { throw '本轮代理启动失败' }
        $listener = @(Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18474 -State Listen -ErrorAction SilentlyContinue)
        if ($listener.Count -and @($listener | Where-Object OwningProcess -eq $proxy.Process.Id).Count -eq $listener.Count) { break }
        [void](Get-DownloadRemaining 5000); Start-Sleep -Milliseconds 100
    } while ($true)
    Invoke-DownloadAdb @('reverse','tcp:18472','tcp:18474') | Out-Null; $reverseOwned = $true
    Invoke-DownloadAdb @('shell','run-as',$package,'mkdir','-p',$remote) | Out-Null
    Send-DownloadFile (Join-Path $output 'plan.json') ($remote+'/plan.json')
    $instrument = Start-DownloadProcess $Adb @('-s',$Serial,'shell','am','instrument','-w','-e','downloadRunId',$runId,"$package.test/app.luoxianlv.host.NativeAppInstrumentation")
    $phases = [Collections.Generic.HashSet[string]]::new()
    while ($true) {
        [void](Get-DownloadRemaining 1000)
        $raw = Invoke-DownloadAdb @('shell','run-as',$package,'cat',($remote+'/control.json')) -AllowMissing
        if ($raw) {
            $control = Convert-DownloadJson $raw 65536
            Assert-DownloadControl $control $runId $Mode $template.targetSnapshotId
            if ($control.slowObjectSha -cne $template.slowObjectSha -or $control.expectedMissingBytes -ne $template.expectedMissingBytes) { throw '设备计划指标不同' }
            if ($source -and $control.sourceIdentity -cne $source) { throw '本轮实际原来源改变' }
            if ($control.phase -eq 'failed') { throw '设备下载验收失败' }
            if ($phases.Add([string]$control.phase)) {
                switch ($control.phase) {
                    'arm' {
                        $source = [string]$control.sourceIdentity
                        if ($template.sourceIdentity -and $source -cne $template.sourceIdentity) { throw '实际来源与模板不同' }
                        $originalChannel = Read-DownloadChannel
                        if (!$originalChannel.current -or $originalChannel.current.snapshotId -cne $source) { throw 'API当前发布不对应设备原稳定来源' }
                        $publishArguments = @('publish',$template.targetSnapshotId,'--fallback',$source,'--mode','direct','--scope','all-compatible',
                            '--replace-release',$originalChannel.current.id,'--expect-revision',[string]$originalChannel.revision,
                            '--request-id',$runId,'--server',$origin,'--token-file',$tokenFile)
                        $publishAttempted = $true
                        $ownership = Resolve-DownloadPublication (Invoke-DownloadLxhot $publishArguments)
                        Assert-DownloadOwnedChannel (Read-DownloadChannel) $ownership
                        Write-DownloadAck 'armed'
                    }
                    'request_captured' {
                        if (!$phases.Contains('arm') -or !$ownership) { throw '没有本轮发布，拒绝释放' }
                        if ($Mode -eq 'delta') { Release-DownloadProxy; Write-DownloadAck 'released' }
                    }
                    'cancelled' {
                        if ($Mode -cne 'cancellation' -or !$phases.Contains('request_captured')) { throw '取消握手顺序错误' }
                        Restore-DownloadChannel; Release-DownloadProxy; Write-DownloadAck 'released'
                    }
                    'complete' { }
                }
            }
        }
        $instrument.Process.Refresh()
        if ($instrument.Process.HasExited) { break }
        Start-Sleep -Milliseconds 100
    }
    if ($instrument.Process.ExitCode -ne 0 -or !$instrument.Out.Wait(1000) -or !$instrument.Err.Wait(1000)) { throw '本轮instrumentation没有正常退出' }
    [IO.File]::WriteAllText((Join-Path $output 'instrumentation.txt'),$instrument.Out.Result,$utf8)
    if ($instrument.Out.Result -match 'AssertionError|Process crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED') { throw '设备原始结果包含失败' }
    $report = Convert-DownloadJson (Invoke-DownloadAdb @('shell','run-as',$package,'cat',($remote+'/report.json')))
    Assert-DownloadReport $report $runId $Mode $template.targetSnapshotId $source $template.expectedMissingBytes
    $finalControl = Convert-DownloadJson (Invoke-DownloadAdb @('shell','run-as',$package,'cat',($remote+'/control.json'))) 65536
    Assert-DownloadControl $finalControl $runId $Mode $template.targetSnapshotId
    if ($finalControl.phase -cne 'complete' -or $finalControl.sourceIdentity -cne $source) { throw '最终握手没有完整结束' }
    [void]$phases.Add('complete')
    if (!$phases.Contains('arm') -or !$phases.Contains('request_captured') -or !$phases.Contains('complete') -or
        ($Mode -eq 'cancellation' -and (!$phases.Contains('cancelled') -or !$restored))) { throw '缺少完整本轮driver握手' }
    Assert-DownloadOwnedChannel (Read-DownloadChannel) $(if($Mode -eq 'cancellation'){$restored}else{$ownership})
} catch { $failure = $_ }
finally {
    $closing = $true
    if ($instrument) {
        try { if (!$instrument.Process.HasExited) { $instrument.Process.Kill(); [void]$instrument.Process.WaitForExit(1000) } }
        catch { $cleanupErrors.Add('停止本次ADB客户端失败') }
        if ($failure -or !$report) {
            try { Invoke-DownloadAdb @('shell','am','force-stop',$package) | Out-Null }
            catch { $cleanupErrors.Add('停止失败的Debug仪器失败') }
        }
    }
    if ($reverseOwned) {
        try {
            Invoke-DownloadAdb @('reverse','--remove','tcp:18472') | Out-Null
            if ((Invoke-DownloadAdb @('reverse','--list')) -match 'tcp:18472\s') { throw '反向端口未撤销' }
        } catch { $cleanupErrors.Add('本轮反向端口清理未验证') }
    }
    if ($failure -and $publishAttempted -and !$restored) {
        try { Restore-DownloadChannel } catch { $cleanupErrors.Add('本轮发布恢复未确认；未覆盖其他发布') }
    }
    if ($proxy) {
        try {
            if (!$proxy.Process.HasExited) { $proxy.Process.Kill(); [void]$proxy.Process.WaitForExit(1000) }
            if (!$proxy.Process.HasExited -or (Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18474 -State Listen -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $proxy.Process.Id)) { throw '本轮代理仍在运行' }
            $metrics = Read-DownloadJson (Join-Path $output 'metrics.json')
            $slowSize = [long](@($template.expectedObjects | Where-Object sha256 -CEQ $template.slowObjectSha)[0].size)
            [long]$returned = Assert-DownloadMetrics $metrics $runId $template.slowObjectSha $slowSize $template.targetSnapshotId
        } catch { $cleanupErrors.Add('本轮代理停止或指标核验未完成') }
    }
    if ($instrument) {
        try { Invoke-DownloadAdb @('shell','am','start','-n',"$package/app.luoxianlv.MainActivity") | Out-Null }
        catch { $cleanupErrors.Add('恢复APP首页失败') }
        if ($instrument.Out.IsCompleted) { [IO.File]::WriteAllText((Join-Path $output 'instrumentation.txt'),$instrument.Out.Result,$utf8) }
    }
}
if ($failure) { throw $failure }
if ($cleanupErrors.Count) { throw ($cleanupErrors -join '；') }
if (!$report -or !$metrics -or [DateTime]::UtcNow -gt $expires) { throw '缺少最终回执、代理指标或完整期限内收尾' }
$report | Add-Member -NotePropertyName gatewayMetrics -NotePropertyValue $metrics
$report | Add-Member -NotePropertyName gatewayBodyBytesReturned -NotePropertyValue $returned
$report | Add-Member -NotePropertyName gatewayBytesAreTcpDelivery -NotePropertyValue $false
$report | Add-Member -NotePropertyName driverReverseRemoved -NotePropertyValue $true
$report | Add-Member -NotePropertyName driverGatewayStopped -NotePropertyValue $true
$report | Add-Member -NotePropertyName driverWaitLimitMs -NotePropertyValue 300000
Save-DownloadJson (Join-Path $output 'report.json') $report
Write-Output "通过：本轮真实下载$Mode、原始设备回执与外部driver收尾；报告：$(Join-Path $output 'report.json')"
