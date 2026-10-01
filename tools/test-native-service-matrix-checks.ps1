#Requires -Version 7.0
param()
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$utf8=[Text.UTF8Encoding]::new($false,$true)
$path=Join-Path $PSScriptRoot 'test-native-service-matrix.ps1'
$tokens=$null;$errors=$null;[void][Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
if($errors.Count){throw '矩阵driver实际解析失败'}
$source=[IO.File]::ReadAllText($path,$utf8);$start=$source.IndexOf('# SERVICE_MATRIX_POLICY_BEGIN');$end=$source.IndexOf('# SERVICE_MATRIX_POLICY_END')
Invoke-Expression $source.Substring($start,$end-$start)
$script:count=0
function Check([bool]$Value,[string]$Message){if(!$Value){throw $Message};$script:count++}
function Reject([scriptblock]$Action){try{&$Action}catch{$script:count++;return};throw '无效矩阵输入被接受'}
$plan=[pscustomobject]@{schema=1;case='valid';consumer='accessibility';sourceSnapshot='c'*64;sourceRuntime='d'*64;
    targetSnapshot='b'*64;targetRuntime='e'*64;minRevision=52;minTrustVersion=3;grantExpirationEpoch=0}
Assert-ServiceMatrixPlan $plan;$count++
Check ((Get-ServiceMatrixExpected $plan).phase-ceq 'TRIAL') '实际A11y应TRIAL'
$plan.consumer='foreground';Check ((Get-ServiceMatrixExpected $plan).phase-ceq 'PREPARING') '实际空闲FGS应PREPARING'
foreach($scenario in @('revoked','offline','expired')){
    $plan.case=$scenario;$plan.grantExpirationEpoch=$(if($scenario-ceq 'expired'){100}else{0})
    Assert-ServiceMatrixPlan $plan;$count++
    Check ((Get-ServiceMatrixExpected $plan).snapshot-ceq $plan.sourceSnapshot) '拒绝来源不应切换'
}
$plan.case='valid';$plan.grantExpirationEpoch=0;$plan.consumer='accessibility';$run='a'*32
$report=[pscustomobject]@{passed=$true;matrixRunId=$run;matrixCase='valid';matrixTarget=$plan.targetSnapshot;consumer='accessibility';
    pid=1234;snapshot=$plan.targetSnapshot;runtimeHash=$plan.targetRuntime;stable=$plan.sourceSnapshot;pending=$plan.targetSnapshot;phase='TRIAL';
    activityCreations=0;activityStarts=0;pages=0;windows=0;cachedTargetVerified=$true;quarantineUnchanged=$true;candidateIsolated=$false;
    playbackUsed=$false;playbackConnected=$true;healthBeforeMillis=0;healthAfterMillis=0;idleObservationMillis=3000;revision=52;trustVersion=3;
    stateInjected=$false;clockInjected=$false;healthInjected=$false;grantAcquiredDirectly=$false;scenarioConditionProvenByInstrument=$false;
    productionTouched=$false;wallClockStartedEpochMillis=100000;wallClockEndedEpochMillis=104000;newRuntimeMarkerLoaded=$true;
    sameRuntimeParent=$true;candidate=$plan.targetSnapshot}
Assert-ServiceMatrixReport $report $plan $run 999 100000;$count++
foreach($pair in @(@{field='matrixRunId';value='f'*32},@{field='healthAfterMillis';value=1},@{field='pages';value=1},
    @{field='stateInjected';value=$true},@{field='candidateIsolated';value=$true},@{field='runtimeHash';value=$plan.sourceRuntime},
    @{field='wallClockStartedEpochMillis';value=99999},@{field='pid';value=999})){
    $old=$report.($pair.field);$report.($pair.field)=$pair.value;Reject {Assert-ServiceMatrixReport $report $plan $run 999 100000};$report.($pair.field)=$old
}
$plan.case='expired';$plan.grantExpirationEpoch=101;$report.matrixCase='expired';$report.snapshot=$plan.sourceSnapshot;
$report.runtimeHash=$plan.sourceRuntime;$report.phase='STABLE';$report.candidate='';$report.newRuntimeMarkerLoaded=$false;$report.sameRuntimeParent=$false
Reject {Assert-ServiceMatrixReport $report $plan $run 999 100000}
$report.wallClockStartedEpochMillis=102000;Assert-ServiceMatrixReport $report $plan $run 999 100000;$count++
Reject {Convert-ServiceMatrixJson '{"case":"valid","CASE":"expired"}'}
Check (!$source.Contains('rm -rf')-and !$source.Contains('admin-token')-and !$source.Contains('Invoke-WebRequest')) '矩阵driver不能删状态/读凭据/操作API'
Check ($source.Contains('CreateNoWindow=$true')-and $source.Contains('ArgumentList.Add')-and $source.Contains('homeStartedByDriver=$false')) '进程/主页面边界不正确'
Write-Output "实际矩阵driver策略：$count PASS；未执行ADB/API或读取凭据。"
$profile=[Environment]::GetFolderPath('UserProfile')
$sdk=Join-Path $profile 'AppData/Local/Android/Sdk/platforms/android-37.0/android.jar'
$cache=Join-Path $profile '.gradle/caches/modules-2/files-2.1'
$junit=(Get-ChildItem -LiteralPath (Join-Path $cache 'junit/junit/4.13.2') -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
$hamcrest=(Get-ChildItem -LiteralPath (Join-Path $cache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
$classpath=@((Join-Path $repo 'app-host/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),
    (Join-Path $repo 'hot-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),
    (Join-Path $repo 'hot-contract/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),$sdk,$junit,$hamcrest)-join ';'
$output=Join-Path $repo ('.local/service-matrix-checks-'+[guid]::NewGuid().ToString('N'));[void][IO.Directory]::CreateDirectory($output)
$inputs=@('app-host/src/androidTest/java/app/luoxianlv/host/NativeServiceMatrixPlan.java',
    'app-host/src/androidTest/java/app/luoxianlv/host/NativeServiceColdInstrumentation.java',
    'app-host/src/androidTest/java/app/luoxianlv/host/NativeForegroundLogEvents.java',
    'tools/test-support/NativeServiceMatrixPlanTest.java')|ForEach-Object{Join-Path $repo $_}
$arguments=@('-encoding','UTF-8','--release','17','-cp',$classpath,'-d',$output)+$inputs
$argsFile=Join-Path $output 'javac.args';[IO.File]::WriteAllLines($argsFile,($arguments|ForEach-Object{'"'+$_.Replace('\','/')+'"'}),$utf8)
& javac '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' "@$argsFile"
if($LASTEXITCODE-ne 0){throw '实际冷服务矩阵独立编译失败'}
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp "$output;$classpath" org.junit.runner.JUnitCore app.luoxianlv.host.NativeServiceMatrixPlanTest
if($LASTEXITCODE-ne 0){throw '实际冷服务矩阵计划/加载器检查失败'}
