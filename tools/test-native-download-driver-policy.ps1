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
$owner=[pscustomobject]@{id='11111111-1111-1111-1111-111111111111';revision=37;target=$target}
$channel=[pscustomobject]@{revision=37;current=[pscustomobject]@{id=$owner.id;snapshotId=$target}}
$commands=Select-DownloadRollback $channel $owner $source
Check ($commands[0]-eq 'rollback'-and $commands[1]-eq $owner.id-and $commands[3]-eq $source) '实际回滚参数未绑定本发布和来源'
$channel.revision=38;Reject {Select-DownloadRollback $channel $owner $source} '新的修订不能覆盖'
$channel.revision=37;$channel.current.id='22222222-2222-2222-2222-222222222222';Reject {Select-DownloadRollback $channel $owner $source} '同target的其他发布不能覆盖'
$channel.current.id=$owner.id;$channel.current.snapshotId='e'*64;Reject {Select-DownloadRollback $channel $owner $source} '不同target不能覆盖'
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
    action=[pscustomobject]@{exactMissingBytesObserved=$true;naturalHealthyJournalObserved=$true;naturalActivationObserved=$true}}
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
Write-Output "实际driver纯policy/mock检查：$checks 通过；未执行CLI/ADB/API/代理/凭据读取。"
