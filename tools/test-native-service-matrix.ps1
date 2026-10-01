#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [Parameter(Mandatory)][string]$Checkpoint,
    [string]$Adb="$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$Serial='emulator-5554'
)
$ErrorActionPreference='Stop'
# SERVICE_MATRIX_POLICY_BEGIN：仅策略测试提取，不执行ADB或改变设备。
function Convert-ServiceMatrixJson([string]$Raw) {
    if(!$Raw-or [Text.Encoding]::UTF8.GetByteCount($Raw)-gt 1048576-or $Raw[0]-eq [char]0xfeff){throw '矩阵JSON无效'}
    $document=[Text.Json.JsonDocument]::Parse($Raw)
    try {
        if($document.RootElement.ValueKind-ne [Text.Json.JsonValueKind]::Object){throw '矩阵JSON根无效'}
        $nodes=[Collections.Generic.Stack[Text.Json.JsonElement]]::new();$nodes.Push($document.RootElement)
        while($nodes.Count){
            $node=$nodes.Pop()
            if($node.ValueKind-eq [Text.Json.JsonValueKind]::Object){
                $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
                foreach($property in $node.EnumerateObject()){if(!$names.Add($property.Name)){throw '矩阵JSON重复字段'};$nodes.Push($property.Value)}
            }elseif($node.ValueKind-eq [Text.Json.JsonValueKind]::Array){foreach($value in $node.EnumerateArray()){$nodes.Push($value)}}
        }
    }finally{$document.Dispose()}
    $Raw|ConvertFrom-Json -Depth 20
}
function Assert-ServiceMatrixPlan($Plan) {
    $allowed=@('schema','case','consumer','sourceSnapshot','sourceRuntime','targetSnapshot','targetRuntime','minRevision','minTrustVersion','grantExpirationEpoch')
    foreach($name in $Plan.PSObject.Properties.Name){if($name-cnotin $allowed){throw '矩阵计划含未知字段'}}
    if($Plan.schema-ne 1-or $Plan.case-cnotin @('valid','revoked','expired','offline')-or
        $Plan.consumer-cnotin @('accessibility','foreground')){throw '矩阵范围或入口无效'}
    foreach($name in @('sourceSnapshot','sourceRuntime','targetSnapshot','targetRuntime')){
        if($Plan.$name-cnotmatch '^[a-f0-9]{64}$'){throw '矩阵身份须为公开SHA256'}
    }
    if($Plan.targetSnapshot-ceq $Plan.sourceSnapshot-or $Plan.targetRuntime-ceq $Plan.sourceRuntime){throw '须为新待重启运行时'}
    foreach($name in @('minRevision','minTrustVersion','grantExpirationEpoch')){
        if($Plan.$name-isnot [long]-and $Plan.$name-isnot [int]-or $Plan.$name-lt 0-or $Plan.$name-gt 9007199254740991){throw '矩阵下限或真实到期时间无效'}
    }
    if(($Plan.case-ceq 'expired')-ne ($Plan.grantExpirationEpoch-gt 0)){throw '仅真实过期场景须给grant到期秒'}
}
function Get-ServiceMatrixExpected($Plan) {
    $valid=$Plan.case-ceq 'valid'
    [pscustomobject]@{
        snapshot=$(if($valid){$Plan.targetSnapshot}else{$Plan.sourceSnapshot})
        runtime=$(if($valid){$Plan.targetRuntime}else{$Plan.sourceRuntime})
        phase=$(if(!$valid){'STABLE'}elseif($Plan.consumer-ceq 'accessibility'){'TRIAL'}else{'PREPARING'})
    }
}
function Assert-ServiceMatrixReport($Report,$Plan,[string]$RunId,[long]$PreviousPid,[long]$StartedEpoch) {
    $expected=Get-ServiceMatrixExpected $Plan
    if($Report.passed-isnot [bool]-or !$Report.passed-or $Report.matrixRunId-cne $RunId-or $Report.matrixCase-cne $Plan.case-or
        $Report.matrixTarget-cne $Plan.targetSnapshot-or $Report.consumer-cne $Plan.consumer-or $Report.pid-le 0-or
        $Report.pid-eq $PreviousPid-or $Report.snapshot-cne $expected.snapshot-or $Report.runtimeHash-cne $expected.runtime-or
        $Report.stable-cne $Plan.sourceSnapshot-or $Report.pending-cne $Plan.targetSnapshot-or $Report.phase-cne $expected.phase-or
        $Report.activityCreations-ne 0-or $Report.activityStarts-ne 0-or $Report.pages-ne 0-or $Report.windows-ne 0-or
        !$Report.cachedTargetVerified-or !$Report.quarantineUnchanged-or $Report.candidateIsolated-or $Report.playbackUsed-or
        $Report.healthBeforeMillis-ne 0-or $Report.healthAfterMillis-ne 0-or $Report.idleObservationMillis-lt 3000-or
        $Report.revision-lt $Plan.minRevision-or $Report.trustVersion-lt $Plan.minTrustVersion-or
        $Report.stateInjected-or $Report.clockInjected-or $Report.healthInjected-or $Report.grantAcquiredDirectly-or
        $Report.scenarioConditionProvenByInstrument-or $Report.productionTouched-or
        $Report.wallClockStartedEpochMillis-lt $StartedEpoch-or $Report.wallClockEndedEpochMillis-lt $Report.wallClockStartedEpochMillis){
        throw '缺少本轮真实无页面冷服务完整回执'
    }
    if($Plan.case-ceq 'valid'){
        if(!$Report.newRuntimeMarkerLoaded-or !$Report.sameRuntimeParent-or $Report.candidate-cne $Plan.targetSnapshot){throw '未使用真实新运行时父加载器'}
    }elseif($Report.newRuntimeMarkerLoaded-or $Report.sameRuntimeParent-or $Report.candidate){throw '拒绝场景仍加载新组合'}
    if($Plan.consumer-ceq 'accessibility' -and !$Report.playbackConnected){throw '未实际连接无障碍业务'}
    if($Plan.consumer-ceq 'foreground' -and (!$Report.foregroundStartObserved-or !$Report.foregroundPolicyObserved)){throw '前台承诺或业务策略未观察'}
    if($Plan.case-ceq 'expired' -and $Report.wallClockStartedEpochMillis-lt ($Plan.grantExpirationEpoch*1000)) {throw '没有跨真实grant到期'}
}
# SERVICE_MATRIX_POLICY_END

if($Serial-cne 'emulator-5554'-or $Checkpoint-cnotmatch '^no_backup/native-service-checkpoint-[A-Za-z0-9-]{1,80}$'){throw '仅允许独立模拟器与明确原样checkpoint'}
$repo=Split-Path -Parent $PSScriptRoot
$hotRoot=[IO.Path]::GetFullPath((Join-Path $repo '../luo-xian-lv-hot-update/.local'))
$PlanPath=[IO.Path]::GetFullPath($PlanPath)
if(!$PlanPath.StartsWith($hotRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw '公开计划须在CLI本机资料子目录'}
$encoding=[Text.UTF8Encoding]::new($false,$true)
$item=Get-Item -LiteralPath $PlanPath
if($item.PSIsContainer-or $item.Length-gt 65536-or $item.Attributes-band [IO.FileAttributes]::ReparsePoint){throw '公开计划类型无效'}
$plan=Convert-ServiceMatrixJson ([IO.File]::ReadAllText($PlanPath,$encoding));Assert-ServiceMatrixPlan $plan
$runId=[guid]::NewGuid().ToString('N');$package='app.luoxianlv.debug'
$output=Join-Path $repo ('.local/native-service-matrix-'+$runId);[void][IO.Directory]::CreateDirectory($output)
$expires=[DateTime]::UtcNow.AddSeconds(255);$closing=$false;$failure=$null;$child=$null;$report=$null
$services=$null;$enabled=$null;$reverseOriginal=$null;$changedSettings=$false;$checkpointRestored=$false
$cleanup=[Collections.Generic.List[string]]::new()
function Remaining([int]$Maximum) {
    $limit=$(if($closing){$expires}else{$expires.AddSeconds(-15)})
    $left=[long]($limit-[DateTime]::UtcNow).TotalMilliseconds
    if($left-le 0){throw '本轮共同观察/收尾期限已用尽'}
    [int][Math]::Min($Maximum,$left)
}
function Start-Adb([string[]]$Arguments) {
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Adb;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true;$info.StandardOutputEncoding=$encoding;$info.StandardErrorEncoding=$encoding
    foreach($argument in (@('-s',$Serial)+$Arguments)){$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info;if(!$process.Start()){throw '本次ADB无法启动'}
    [pscustomobject]@{Process=$process;Out=$process.StandardOutput.ReadToEndAsync();Err=$process.StandardError.ReadToEndAsync()}
}
function Adb([string[]]$Arguments,[switch]$Missing) {
    $call=Start-Adb $Arguments
    try{
        if(!$call.Process.WaitForExit((Remaining 5000))){$call.Process.Kill();throw '本次ADB等待超时'}
        if(!$call.Out.Wait(500)-or !$call.Err.Wait(500)){throw '本次ADB管道未完成'}
        if($call.Process.ExitCode-ne 0){if($Missing){return ''};throw '本轮ADB失败，原始命令正文未输出'}
        $call.Out.Result.Trim()
    }finally{if(!$call.Process.HasExited){$call.Process.Kill()};$call.Process.Dispose()}
}
function Setting([string]$Name,[string]$Value){
    if($Value-ceq 'null'-or !$Value){Adb @('shell','settings','delete','secure',$Name)|Out-Null}
    else{if($Value-cnotmatch '^[A-Za-z0-9_.$:/]+$'){throw '原系统设置格式不安全'};Adb @('shell','settings','put','secure',$Name,$Value)|Out-Null}
}
function Save-Streams {
    if($child.Out.IsCompletedSuccessfully){[IO.File]::WriteAllText((Join-Path $output 'instrumentation.txt'),$child.Out.Result,$encoding)}
    if($child.Err.IsCompletedSuccessfully){[IO.File]::WriteAllText((Join-Path $output 'instrumentation-stderr.txt'),$child.Err.Result,$encoding)}
}
try{
    $services=Adb @('shell','settings','get','secure','enabled_accessibility_services')
    $enabled=Adb @('shell','settings','get','secure','accessibility_enabled')
    $reverseOriginal=Adb @('reverse','--list')
    $route=@($reverseOriginal-split "`n"|Where-Object{$_-match '\stcp:18472\s'})
    if($route.Count-gt 1-or $route.Count-eq 1-and $route[0]-notmatch '\stcp:18472\s+tcp:18472$'){throw '已有非本机原始reverse，拒绝接管'}
    $own=@("$package/app.luoxianlv.service.MusicAccessibilityService","$package/.service.MusicAccessibilityService")
    $others=@($services-split ':'|Where-Object{$_-and $_-cne 'null'-and $_-cnotin $own})-join ':'
    $changedSettings=$true;Setting 'enabled_accessibility_services' $others
    if(!$others){Setting 'accessibility_enabled' '0'}
    $old=Adb @('shell','pidof',$package) -Missing
    $previousPid=$(if($old-match '^\d+$'){[long]$old}else{0})
    Adb @('shell','am','force-stop',$package)|Out-Null
    if(Adb @('shell','pidof',$package) -Missing){throw '测试进程尚在运行，拒绝还原checkpoint'}
    $private=Adb @('shell','run-as',$package,'pwd')
    if($private-cnotmatch '^/data/(user/0|data)/app\.luoxianlv\.debug$'){throw '私有目录无法确认'}
    foreach($directory in @('no_backup',$Checkpoint,'no_backup/native-update')){
        $kind=Adb @('shell','run-as',$package,'stat','-c','%F',$directory)
        if($kind-cne 'directory'){throw 'checkpoint或当前状态目录并非普通目录'}
    }
    $archive='no_backup/native-service-matrix-'+$runId
    Adb @('shell','run-as',$package,'mkdir',$archive)|Out-Null
    Adb @('shell','run-as',$package,'mv','no_backup/native-update',($archive+'/previous-native-update'))|Out-Null
    Adb @('shell','run-as',$package,'cp','-a',$Checkpoint,'no_backup/native-update')|Out-Null
    $checkpointRestored=$true
    if($plan.case-ceq 'offline'){
        if($route.Count){Adb @('reverse','--remove','tcp:18472')|Out-Null}
    }elseif(!$route.Count){Adb @('reverse','tcp:18472','tcp:18472')|Out-Null}
    if($plan.case-ceq 'expired'){
        while([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()-le $plan.grantExpirationEpoch){[void](Remaining 1000);Start-Sleep -Milliseconds 200}
    }
    $expected=Get-ServiceMatrixExpected $plan
    $started=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $options=@('shell','am','instrument','-w','-e','consumer',$plan.consumer,
        '-e','expectedSnapshot',$expected.snapshot,'-e','expectedStable',$plan.sourceSnapshot,
        '-e','expectedPending',$plan.targetSnapshot,'-e','expectedRuntime',$expected.runtime,'-e','expectedPhase',$expected.phase,
        '-e','minRevision',[string]$plan.minRevision,'-e','minTrustVersion',[string]$plan.minTrustVersion,
        '-e','previousPid',[string]$previousPid,'-e','observeMillis','3000',
        '-e','matrixRunId',$runId,'-e','matrixCase',$plan.case,'-e','matrixTarget',$plan.targetSnapshot,
        '-e','sourceRuntime',$plan.sourceRuntime,'-e','targetRuntime',$plan.targetRuntime,
        "$package.test/app.luoxianlv.host.NativeServiceColdInstrumentation")
    $child=Start-Adb $options
    if(!$child.Process.WaitForExit((Remaining 120000))){throw '本轮真实冷服务观察未完成'}
    if(!$child.Out.Wait(1000)-or !$child.Err.Wait(1000)){throw '本轮仪器输出未完成'}
    Save-Streams
    $report=Convert-ServiceMatrixJson (Adb @('shell','run-as',$package,'cat','files/native-service-cold-report.json'))
    [IO.File]::WriteAllText((Join-Path $output 'device-report.json'),($report|ConvertTo-Json -Depth 20),$encoding)
    if($child.Process.ExitCode-ne 0-or $child.Out.Result-notmatch '通过：真实服务无 Activity 冷启动'-or
        $child.Out.Result-match 'INSTRUMENTATION_FAILED|Process crashed|失败阶段'){throw '本轮仪器原始结果未通过'}
    Assert-ServiceMatrixReport $report $plan $runId $previousPid $started
}catch{$failure=$_}
finally{
    $closing=$true
    if($child){
        try{if(!$child.Process.HasExited){$child.Process.Kill();[void]$child.Process.WaitForExit(1000)};[void]$child.Out.Wait(1000);[void]$child.Err.Wait(1000);Save-Streams}
        catch{$cleanup.Add('本轮instrument输出/退出未确认')}
    }
    try{Adb @('shell','am','force-stop',$package)|Out-Null}catch{$cleanup.Add('本轮Debug进程停止未确认')}
    if($changedSettings){
        try{Setting 'enabled_accessibility_services' $services;Setting 'accessibility_enabled' $enabled
            if((Adb @('shell','settings','get','secure','enabled_accessibility_services'))-cne $services-or
                (Adb @('shell','settings','get','secure','accessibility_enabled'))-cne $enabled){throw '设置未恢复'}}
        catch{$cleanup.Add('原无障碍设置恢复未确认')}
    }
    if($null-ne $reverseOriginal){
        try{
            $now=Adb @('reverse','--list');$has=$now-match '\stcp:18472\s'
            if($route.Count){if(!$has){Adb @('reverse','tcp:18472','tcp:18472')|Out-Null}}
            elseif($has){Adb @('reverse','--remove','tcp:18472')|Out-Null}
            if((Adb @('reverse','--list'))-cne $reverseOriginal){throw 'reverse未恢复'}
        }catch{$cleanup.Add('原reverse恢复未确认')}
    }
}
if($failure){throw $failure}
if($cleanup.Count){throw ($cleanup-join '；')}
if(!$report-or !$checkpointRestored-or [DateTime]::UtcNow-gt $expires){throw '缺少本轮报告或完整收尾'}
$final=[ordered]@{passed=$true;runId=$runId;plan=$plan;device=$report;checkpointRestoredByDriver=$true;
    checkpoint=$Checkpoint;archivedStateDirectory=$archive;settingsRestored=$true;reverseRestored=$true;
    homeStartedByDriver=$false;productionTouched=$false;scenarioConditionRequiresRootEvidence=$true}
[IO.File]::WriteAllText((Join-Path $output 'report.json'),($final|ConvertTo-Json -Depth 25),$encoding)
Write-Output "通过：本轮$($plan.case)/$($plan.consumer)真实冷服务观察及外层收尾；报告：$(Join-Path $output 'report.json')"
