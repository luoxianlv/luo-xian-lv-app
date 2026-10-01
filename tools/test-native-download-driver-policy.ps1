#Requires -Version 7.0
param()
$ErrorActionPreference='Stop'
$path=Join-Path $PSScriptRoot 'test-native-download.ps1'
$tokens=$null;$errors=$null
[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)|Out-Null
if($errors.Count){throw '实际driver PowerShell解析失败'}
$sourceText=[IO.File]::ReadAllText($path)
$begin=$sourceText.IndexOf('# DOWNLOAD_DRIVER_POLICY_BEGIN')
$end=$sourceText.IndexOf('# DOWNLOAD_DRIVER_POLICY_END')
if($begin-lt 0-or $end-le $begin){throw '实际纯policy源码标记缺失'}
Invoke-Expression $sourceText.Substring($begin,$end-$begin)
$script:checks=0
function Check([bool]$Condition,[string]$Message){if(!$Condition){throw $Message};$script:checks++}
function Reject([scriptblock]$Action,[string]$Message){try{& $Action}catch{$script:checks++;return};throw $Message}
$runId='a'*32;$target='b'*64;$source='c'*64;$slow='d'*64
$verified=[pscustomobject]@{snapshotId=$target;applicationId='app.luoxianlv.debug';environment='test';activation='live';mode='full';complete=$true;
    artifacts=@([pscustomobject]@{sha256=$slow;size=554;role='config'})}
$template=[pscustomobject]@{targetSnapshotId=$target;sourceIdentity=$source;slowObjectSha=$slow;prefixBytes=32;expectedMissingBytes=554;
    expectedObjects=@([pscustomobject]@{sha256=$slow;size=554})}
Assert-DownloadCandidate $verified $template 'cancellation';$checks++
$template.prefixBytes=0;Assert-DownloadCandidate $verified $template 'delta';$checks++
Reject {Assert-DownloadCandidate $verified $template 'cancellation'} '取消不能无前缀'
$template.prefixBytes=554;Reject {Assert-DownloadCandidate $verified $template 'delta'} '完整对象不能冒充在途'
$template.prefixBytes=32;$verified.environment='production';Reject {Assert-DownloadCandidate $verified $template 'cancellation'} '生产范围必须拒绝'
$verified.environment='test';$verified.applicationId='app.luoxianlv';Reject {Assert-DownloadCandidate $verified $template 'cancellation'} '正式包必须拒绝'
$verified.applicationId='app.luoxianlv.debug';$template.expectedMissingBytes=555;Reject {Assert-DownloadCandidate $verified $template 'cancellation'} '不一致总量必须拒绝'
$template.expectedMissingBytes=554
$template.expectedObjects+= $template.expectedObjects[0];Reject {Assert-DownloadCandidate $verified $template 'cancellation'} '重复对象必须拒绝'
$template.expectedObjects=@($template.expectedObjects[0])
$verified.artifacts[0].role='business';Reject {Assert-DownloadCandidate $verified $template 'cancellation'} '业务APK不是小资源'
$verified.artifacts[0].role='config'
$owner=[pscustomobject]@{id='4488c02e-d8bc-408a-9b97-d0ee3e19eb54';revision=36;target=$target;status='active';fallback=$source}
$channel=[pscustomobject]@{revision=36;current=[pscustomobject]@{id=$owner.id;snapshotId=$target;status='active';fallbackId=$source;mode='direct'}}
$commands=Select-DownloadRollback $channel $owner $source
Check ($commands[0]-eq 'rollback'-and $commands[1]-eq $owner.id-and $commands[3]-eq $source) '实际回滚参数未绑定本发布和来源'
$channel.revision=37;Reject {Select-DownloadRollback $channel $owner $source} '新的修订不能覆盖'
$channel.revision=36;$channel.current.id='22222222-2222-2222-2222-222222222222';Reject {Select-DownloadRollback $channel $owner $source} '同target的其他发布不能覆盖'
$channel.current.id=$owner.id;$channel.current.snapshotId='e'*64;Reject {Select-DownloadRollback $channel $owner $source} '不同target不能覆盖'
$channel.current.snapshotId=$target
foreach($status in @('paused','revoked','rolled_back')) {
    $channel.current.status=$status;Reject {Select-DownloadRollback $channel $owner $source} '非活动状态不能重复回滚'
}
$channel.current.status='active';$channel.current.fallbackId='e'*64
Reject {Assert-DownloadOwnedChannel $channel $owner} '原fallback改变不能保持归属'
$channel.current.fallbackId=$source
$rolled=Convert-DownloadJson (@{revision=37;current=@{id=$owner.id;snapshotId=$target;status='rolled_back';fallbackId=$source;mode='direct'}}|ConvertTo-Json -Depth 5 -Compress)
$restored=Resolve-DownloadRollbackReceipt $rolled $owner $source
Check ($restored.id-ceq $owner.id-and $restored.target-ceq $target-and $restored.revision-eq 37-and $restored.status-ceq 'rolled_back'-and
    $restored.fallback-ceq $source-and $restored.restoredTo-ceq $source) '真实36→37回滚应保留发布和候选身份'
Assert-DownloadOwnedChannel $rolled $restored;$checks++
Reject {Select-DownloadRollback $rolled $owner $source} '旧活动归属不能重复回滚已37终态'
Reject {Select-DownloadRollback $rolled $restored $source} '已确认恢复归属也不能重复回滚'
foreach($case in @(@{field='snapshotId';value=$source},@{field='id';value='22222222-2222-2222-2222-222222222222'},
    @{field='fallbackId';value='e'*64},@{field='status';value='active'})) {
    $saved=$rolled.current.($case.field);$rolled.current.($case.field)=$case.value
    Reject {Resolve-DownloadRollbackReceipt $rolled $owner $source} ('错误回滚'+$case.field+'必须拒绝')
    Reject {Assert-DownloadOwnedChannel $rolled $restored} ('终态归属必须拒绝变更'+$case.field)
    $rolled.current.($case.field)=$saved
}
$rolled.revision=38;Reject {Resolve-DownloadRollbackReceipt $rolled $owner $source} '回滚必须恰为预期修订+1'
$rolled.revision=37
Assert-DownloadChannelSource $rolled $source;$checks++
Reject {Assert-DownloadChannelSource $rolled $target} 'rolled_back的旧候选不是有效恢复来源'
foreach($mode in @('direct','staged')) {
    $channel.current.mode=$mode
    Assert-DownloadChannelSource $channel $target;$checks++
    Reject {Assert-DownloadChannelSource $channel $source} '活动paired test发布不得把fallback当当前来源'
}
$channel.current.status='paused';Reject {Assert-DownloadChannelSource $channel $target} '暂停不下发新来源，不能自动替换'
$channel.current.status='revoked';Reject {Assert-DownloadChannelSource $channel $source} '撤销不在本轮自动恢复入口'
$channel.current.status='cancelled';Reject {Assert-DownloadChannelSource $channel $source} '不存在的状态必须拒绝'
$channel.current.status='active';$channel.current.mode='unknown';Reject {Assert-DownloadChannelSource $channel $target} '未知mode不能猜来源'
# 提取真实收尾函数、mock仅外部调用，证明已提交回滚只读确认且正常回滚只写一次。
$ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
$restoreAst=$ast.Find({param($node) $node-is [Management.Automation.Language.FunctionDefinitionAst]-and $node.Name-ceq 'Restore-DownloadChannel'},$true)
Invoke-Expression $restoreAst.Extent.Text
$script:channelToRead=$rolled;$script:ownership=$owner;$script:restored=$null;$script:publishAttempted=$true;$script:writeCount=0
function Read-DownloadChannel { $script:channelToRead }
function Invoke-DownloadLxhot { param($Arguments) $script:writeCount++;[pscustomobject]@{requestId=$runId+'-rollback';response=$rolled} }
Restore-DownloadChannel
Check ($writeCount-eq 0-and $restored.target-ceq $target-and $restored.fallback-ceq $source) '回执丢失后已37应只读认领，不再次回滚'
Restore-DownloadChannel;Check ($writeCount-eq 0) '已确认回滚再次收尾不得写渠道'
$script:restored=$null;$channel.current.mode='direct';$script:channelToRead=$channel
Restore-DownloadChannel
Check ($writeCount-eq 1-and $restored.revision-eq 37-and $restored.target-ceq $target) '正常36→37应仅一次真实收尾CLI调用'
Reject {Convert-DownloadJson '{"runId":"a","runId":"b"}'} '重复JSON必须拒绝'
Reject {Convert-DownloadJson '{"runId":"a","RUNID":"b"}'} '大小写混淆必须拒绝'
Reject {Convert-DownloadJson ('['+'1'+']')} '数组根必须拒绝'
$valid=Convert-DownloadJson '{"schema":1,"runId":"a"}';Check ($valid.schema-eq 1) '合法机器JSON读取失败'
$control=[pscustomobject]@{schema=1;runId=$runId;mode='delta';targetSnapshotId=$target;sourceIdentity=$source;phase='arm'}
Assert-DownloadControl $control $runId 'delta' $target;$checks++
$control.runId='f'*32;Reject {Assert-DownloadControl $control $runId 'delta' $target} '旧run握手必须拒绝'
$control.runId=$runId;$control.phase='go';Reject {Assert-DownloadControl $control $runId 'delta' $target} '未知阶段必须拒绝'
$metrics=[pscustomobject]@{schema=1;runId=$runId;slowObjectSha=$slow;requests=@(
    [pscustomobject]@{index=0;snapshotId=$target;offset=0;status=200;bodyBytesReturnedToProxy=32;cancelled=$true;released=$false},
    [pscustomobject]@{index=1;snapshotId=$target;offset=32;status=206;bodyBytesReturnedToProxy=522;cancelled=$false;released=$true})}
Check ((Assert-DownloadMetrics $metrics $runId $slow 554 $target)-eq 554) '真实proxy read计数求和失败'
$metrics.requests[1].snapshotId='e'*64;Reject {Assert-DownloadMetrics $metrics $runId $slow 554 $target} '不同快照的同hash请求不能绑定本目标'
$metrics.requests[1].snapshotId=$target;$metrics.slowObjectSha='e'*64;Reject {Assert-DownloadMetrics $metrics $runId $slow 554 $target} '其他slowhash不能合并'
$report=[pscustomobject]@{passed=$true;runId=$runId;mode='delta';targetSnapshotId=$target;initialSourceIdentity=$source;expectedMissingBytes=554;
    homeRestored=$true;stageClosed=$true;clockInjected=$false;updateStateInjected=$false;explicitCheckCalled=$false;
    explicitActivationCalled=$false;healthInjected=$false;objectReadBytes=554;
    action=[pscustomobject]@{exactMissingBytesObserved=$true;naturalHealthyJournalObserved=$true;naturalActivationObserved=$true;stable=[pscustomobject]@{observedActiveMillis=60000}}}
Assert-DownloadReport $report $runId 'delta' $target $source 554;$checks++
$report.action.naturalHealthyJournalObserved=$false;Reject {Assert-DownloadReport $report $runId 'delta' $target $source 554} 'TRIAL不能报告同次健康'
$report.action.naturalHealthyJournalObserved=$true;$report.objectReadBytes=2097152;Reject {Assert-DownloadReport $report $runId 'delta' $target $source 554} '预算预留不能冒充正文read'
$report.objectReadBytes=554;$report.runId='f'*32;Reject {Assert-DownloadReport $report $runId 'delta' $target $source 554} '旧设备回执不能复用'
$boundary=Join-Path ([IO.Path]::GetTempPath()) 'download-policy-root'
$inside=Join-Path $boundary 'fixture/candidate.lxhp'
Check ((Get-DownloadScopedPath $inside $boundary)-eq [IO.Path]::GetFullPath($inside)) '合法子路径拒绝'
Reject {Get-DownloadScopedPath $boundary $boundary} '根目录不能作为fixture'
Reject {Get-DownloadScopedPath (Join-Path $boundary '../outside') $boundary} '越界不能接受'
Check ($sourceText.Contains('CreateNoWindow = $true')-and $sourceText.Contains('ArgumentList.Add')) '子进程必须隐藏且使用结构化参数'
Check ($sourceText.IndexOf('if ($cleanupErrors.Count)')-lt $sourceText.LastIndexOf('Write-Output "通过')) '不能在收尾验证前打印成功'
Check ($sourceText.Contains('downloadRunId')-and !$sourceText.Contains('Get-Content $tokenFile')-and !$sourceText.Contains('ReadAllText($tokenFile')) '仪器runId或token读取边界不正确'
$failureReport=[pscustomobject]@{schema=1;passed=$false;runId=$runId;mode='delta';targetSnapshotId=$target;initialSourceIdentity=$source;
    failureChain=@([pscustomobject]@{relation='root';type='app.luoxianlv.host.NativeDownloadChecks$CheckFailure';message='健康确认后没有实际前台首页'})}
Assert-DownloadFailureReport $failureReport $runId 'delta' $target $source;$checks++
$failureReport.passed=$true;Reject {Assert-DownloadFailureReport $failureReport $runId 'delta' $target $source} 'success残留不能冒充失败诊断'
$failureReport.passed=$false;$failureReport.runId='f'*32;Reject {Assert-DownloadFailureReport $failureReport $runId 'delta' $target $source} '旧run失败报告不能复用'
$failureReport.runId=$runId;$failureReport.failureChain[0].type='https://private.invalid'
Reject {Assert-DownloadFailureReport $failureReport $runId 'delta' $target $source} '类型字段不能包含URL'
function New-LocalFlushChild {
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=(Get-Process -Id $PID).Path;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in @('-NoProfile','-NonInteractive','-Command',"Start-Sleep -Milliseconds 300; [Console]::Out.WriteLine('AssertionError: bounded-local-test'); [Console]::Error.WriteLine('safe-local-test')")){$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info;[void]$process.Start()
    [pscustomobject]@{Process=$process;Out=$process.StandardOutput.ReadToEndAsync();Err=$process.StandardError.ReadToEndAsync()}
}
$child=New-LocalFlushChild
try {
    Check (Wait-DownloadFailureOutput $child 2000) '真实本地晚到栈输出应等待退出及两个管道结束'
    Check ($child.Out.Result.Contains('AssertionError: bounded-local-test')-and $child.Err.Result.Contains('safe-local-test')) '等待flush不能丢stdout/stderr'
} finally {if(!$child.Process.HasExited){$child.Process.Kill()};$child.Process.Dispose()}
$child=New-LocalFlushChild
try {
    $started=[Environment]::TickCount64
    Check (!(Wait-DownloadFailureOutput $child 10)-and [Environment]::TickCount64-$started-lt 500) '未退出仪器flush必须有界'
    Check (!$child.Process.HasExited) '等待flush本身不得kill原仪器'
} finally {if(!$child.Process.HasExited){$child.Process.Kill()};[void]$child.Process.WaitForExit(1000);$child.Process.Dispose()}
Reject {Wait-DownloadFailureOutput $null 8001} 'flush不能超8秒或使用非本轮仪器'
Check ($sourceText.Contains('Receive-DownloadFailure')-and $sourceText.Contains('instrumentation-stderr.txt')-and $sourceText.Contains('failure-report.json')) '失败分支须保留双管道和安全报告'
Write-Output "实际driver纯policy/mock检查：$checks 通过；未执行CLI/ADB/API/代理/凭据读取。"

Check ((Get-DownloadMeteredPolicy "AndroidWifi;none`nAndroidWifi;none`n")-ceq'none') '真实重复none策略行应可准确保存'
Check ((Get-DownloadMeteredRestore 'none')-ceq'undefined') 'none必须用实际支持undefined恢复'
Reject {Get-DownloadMeteredPolicy "AndroidWifi;none`nAndroidWifi;true"} '矛盾策略不能猜测原值'
Reject {Get-DownloadMeteredPolicy 'OtherWifi;false'} '非本机已知WiFi不能接管'
Reject {Get-DownloadMeteredRestore 'unknown'} '未知策略不能变成false'
$budgetVerified=[pscustomobject]@{snapshotId=$target;applicationId='app.luoxianlv.debug';environment='test';activation='live';mode='full';complete=$true;
    artifacts=@([pscustomobject]@{sha256=$slow;size=10485760;role='resources'},[pscustomobject]@{sha256=('e'*64);size=10485760;role='resources'})}
$budgetTemplate=[pscustomobject]@{targetSnapshotId=$target;sourceIdentity=$source;mode='budget-retry';slowObjectSha=$slow;prefixBytes=16384;expectedMissingBytes=20971520;
    expectedObjects=@([pscustomobject]@{sha256=$slow;size=10485760},[pscustomobject]@{sha256=('e'*64);size=10485760})}
Assert-DownloadCandidate $budgetVerified $budgetTemplate 'budget-retry';$checks++
Reject {Assert-DownloadCandidate $budgetVerified $budgetTemplate 'budget-limit'} '21MiB门禁不能用20MiB样本冒充'
$budgetTemplate.mode='budget-limit';$budgetTemplate.expectedMissingBytes=20971521;$budgetTemplate.expectedObjects[1].size=10485761;$budgetVerified.artifacts[1].size=10485761
Assert-DownloadCandidate $budgetVerified $budgetTemplate 'budget-limit';$checks++
$budgetVerified.complete=$false;Reject {Assert-DownloadCandidate $budgetVerified $budgetTemplate 'budget-limit'} '预算候选必须是签名完整包'
Check ($sourceText.Contains("if(`$Mode-eq'cancellation'){Restore-DownloadChannel;Release-DownloadProxy}")-and
    $sourceText.Contains("'resumed_captured'")) '预算取消不得沿旧路径回滚或提前释放'
Check ($sourceText.IndexOf('if($meteredOwned){')-lt $sourceText.LastIndexOf('Write-Output "通过')) '计费策略必须在成功报告前还原'
Write-Output "缓存/整候选新增policy累计：$checks 通过；未执行系统设置、CLI、设备或API。"

# 调实际setter，仅命令/只读返回为替身；255不能绕过严格策略读回，也不改变其他ADB的strict0。
$setterAst=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$null,[ref]$null)
$setterNode=$setterAst.Find({param($node)$node-is[Management.Automation.Language.FunctionDefinitionAst]-and$node.Name-ceq'Set-DownloadMeteredPolicy'},$true)
if(!$setterNode){throw '实际metered setter缺失'}
Invoke-Expression $setterNode.Extent.Text
$Serial='emulator-5554';$Adb='fake-adb';$script:meteredCode=255;$script:meteredRows="AndroidWifi;true`nAndroidWifi;true"
$script:meteredTrace=[Collections.Generic.List[string]]::new()
function Invoke-DownloadCommand([string]$Executable,[string[]]$Arguments,[int]$Maximum){
    $meteredTrace.Add(($Arguments-join' '))
    [pscustomobject]@{Code=$script:meteredCode;Output='ignored-safe-local-output'}
}
function Invoke-DownloadAdb([string[]]$Arguments){$meteredTrace.Add(($Arguments-join' '));$script:meteredRows}
Set-DownloadMeteredPolicy 'true';$checks++
Check ($meteredTrace[0]-ceq'-s emulator-5554 shell cmd netpolicy set metered-network AndroidWifi true'-and
    $meteredTrace[1]-ceq'shell cmd netpolicy list wifi-networks') '255路径必须执行精确本机setter并真实读回'
$meteredRows="AndroidWifi;none`nAndroidWifi;none"
Set-DownloadMeteredPolicy 'undefined';$checks++
$meteredRows='AndroidWifi;false';Reject {Set-DownloadMeteredPolicy 'true'} '255但读回错误不得通过'
$meteredRows="AndroidWifi;true`nAndroidWifi;none";Reject {Set-DownloadMeteredPolicy 'true'} '255但读回矛盾不得通过'
$meteredRows='OtherWifi;true';Reject {Set-DownloadMeteredPolicy 'true'} '255但非AndroidWifi不得通过'
$meteredRows="AndroidWifi;true`nAndroidWifi;unknown";Reject {Set-DownloadMeteredPolicy 'true'} '不能忽略同SSID未知策略行'
$meteredRows="AndroidWifi;none`nAndroidWifi;TRUE";Reject {Set-DownloadMeteredPolicy 'undefined'} '不能忽略同SSID非法大小写值'
$meteredCode=1;$meteredRows='AndroidWifi;true';Reject {Set-DownloadMeteredPolicy 'true'} '退出1即使读回true也不得通过'
$meteredCode=254;Reject {Set-DownloadMeteredPolicy 'true'} '不得放宽其他退出码'
$meteredCode=0;Set-DownloadMeteredPolicy 'true';$checks++
$meteredRows='AndroidWifi;none';Reject {Set-DownloadMeteredPolicy 'true'} '退出0也不能绕过实际读回'
Check ($sourceText.Contains("if (`$reply.Code -ne 0)")-and !$sourceText.Contains('if ($reply.Code -notin @(0,255))')) '其他ADB必须仍仅接受exit0'
Write-Output "计费setter新增累计：$checks 通过；实际setter函数+mock返回，无设备设置或网络操作。"

foreach($row in @(' topResumedActivity=ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity t144}',
    ' ResumedActivity: ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity t144}',
    ' mResumedActivity: ActivityRecord{123 u0 app.luoxianlv.debug/.MainActivity t144}')){
    Check (Test-DownloadHomeResumed $row) '真实API36/旧版当前Main格式必须支持'
}
foreach($row in @(' Hist #0: ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity t144}',
    ' mPausedActivity: ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity t144}',
    ' topResumedActivity=ActivityRecord{123 u0 other.app.luoxianlv.debug/app.luoxianlv.MainActivity t144}',
    ' topResumedActivity=ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity$Inner t144}',
    ' topResumedActivity=ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivityPreview t144}',
    ' topResumedActivity=ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.ui.practice.PracticeActivity t144}')){
    Check (!(Test-DownloadHomeResumed $row)) 'History/Paused/包或页面前缀不能冒充当前Main'
}
$homeNode=$setterAst.Find({param($node)$node-is[Management.Automation.Language.FunctionDefinitionAst]-and$node.Name-ceq'Restore-DownloadHome'},$true)
if(!$homeNode){throw '实际home收尾函数缺失'}
Invoke-Expression $homeNode.Extent.Text
$package='app.luoxianlv.debug';$script:homeCommandCode=0;$script:homeColdMillis=6000;$script:homeReadIndex=0;$script:homeBudgetPolls=0
$script:homeRows=@(' mPausedActivity: ActivityRecord{123 u0 app.luoxianlv.debug/.MainActivity t144}',
    ' topResumedActivity=ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity t144}')
$script:homeTrace=[Collections.Generic.List[string]]::new()
function Invoke-DownloadCommand([string]$Executable,[string[]]$Arguments,[int]$Maximum){
    $homeTrace.Add(($Arguments-join' ')+' / '+$Maximum)
    if($Maximum-lt$homeColdMillis){throw 'mock cold start exceeds command window'}
    [pscustomobject]@{Code=$script:homeCommandCode;Output=''}
}
function Invoke-DownloadAdb([string[]]$Arguments){
    $homeTrace.Add(($Arguments-join' '))
    $index=[Math]::Min($script:homeReadIndex,$homeRows.Count-1);$script:homeReadIndex++
    $homeRows[$index]
}
function Get-DownloadRemaining([int]$Maximum){$script:homeBudgetPolls++;if($homeBudgetPolls-ge2){throw 'mock shared deadline expired'};$Maximum}
Restore-DownloadHome;$checks++
Check ($homeTrace[0]-ceq'-s emulator-5554 shell am start -n app.luoxianlv.debug/app.luoxianlv.MainActivity / 10000'-and$homeReadIndex-eq2) '慢冷启动须获10s且继续等待实际Main'
# 原5秒窗口在相同慢冷启动模型明确失败；不模拟真实设备时间或Activity成功。
Reject {Invoke-DownloadCommand $Adb @('-s',$Serial,'shell','am','start','-n',"$package/app.luoxianlv.MainActivity") 5000} '旧5s冷启动负例必须失败'
$homeRows=@(' Hist #0: ActivityRecord{123 u0 app.luoxianlv.debug/.MainActivity t144}');$homeBudgetPolls=0
Reject {Restore-DownloadHome} 'am start exit0但只有History须在共同期限拒绝'
$homeCommandCode=255;Reject {Restore-DownloadHome} '255例外只属于netpolicy，不允许home启动'
Check ($sourceText.Contains('$reply = Invoke-DownloadCommand $Adb (@(''-s'',$Serial) + $Arguments) 5000')) '全局ADB5s不应改变'
Check ($sourceText.LastIndexOf('driverHomeRestored')-gt$sourceText.IndexOf('if ($cleanupErrors.Count)')) '完整home核验应在成功保存之前门禁'
Write-Output "首页收尾新增累计：$checks 通过；实际函数+mock冷启动预算/Resumed向量，无设备操作。"
