#Requires -Version 7.0
param()
$ErrorActionPreference = 'Stop'
$continuousApp = Split-Path -Parent $PSScriptRoot
$continuousScript = Join-Path $continuousApp 'tools/test-native-continuous.ps1'
$continuousParseErrors = $null
[void][Management.Automation.Language.Parser]::ParseFile($continuousScript,[ref]$null,[ref]$continuousParseErrors)
if($continuousParseErrors.Count){throw 'Continuous driver syntax failed.'}
$continuousText = [IO.File]::ReadAllText($continuousScript)
$continuousStart = $continuousText.IndexOf('# CONTINUOUS_POLICY_BEGIN')
$continuousEnd = $continuousText.IndexOf('# CONTINUOUS_POLICY_END')
if($continuousStart -lt 0 -or $continuousEnd -le $continuousStart){throw 'Actual continuous policy block missing.'}
Invoke-Expression $continuousText.Substring($continuousStart,$continuousEnd-$continuousStart)
$continuousChecks = 0
function Require-Continuous([bool]$value,[string]$message){if(!$value){throw $message};$script:continuousChecks++}
function Reject-Continuous([scriptblock]$action){$rejected=$false;try{&$action}catch{$rejected=$true};Require-Continuous $rejected 'Expected safe rejection.'}
$component = 'app.luoxianlv.debug/app.luoxianlv.service.MusicAccessibilityService'
$other = 'com.other/com.other.Access$Inner'
Require-Continuous ((Select-ContinuousServices ($other+':'+$component) $component)-eq$other) 'Other service changed.'
Require-Continuous ((Select-ContinuousServices ('app.luoxianlv.debug/.service.MusicAccessibilityService:'+$other) $component)-eq$other) 'Short component alias retained.'
Require-Continuous ((Select-ContinuousServices 'null' $component)-eq'') 'Null setting changed.'
Reject-Continuous { Select-ContinuousServices 'bad;command' $component }
$normal = [pscustomobject]@{artifacts=@([pscustomobject]@{role='runtime';mount=''},[pscustomobject]@{role='business';mount=''})}
Assert-ContinuousArtifactScope $normal
$continuousChecks++
Reject-Continuous { Assert-ContinuousArtifactScope ([pscustomobject]@{artifacts=@($normal.artifacts)+@([pscustomobject]@{role='resources';mount='wallpaperengine'})}) }
Reject-Continuous { Assert-ContinuousArtifactScope ([pscustomobject]@{artifacts=@([pscustomobject]@{role='runtime';mount=''},[pscustomobject]@{role='business';mount='theme'})}) }
Reject-Continuous { Assert-ContinuousArtifactScope ([pscustomobject]@{artifacts=@([pscustomobject]@{role='runtime';mount=''},[pscustomobject]@{role='runtime';mount=''})}) }

$package = 'app.luoxianlv.debug'; $Serial = 'emulator-5554'; $HostApk = 'test-host.apk'; $runId = 'a'*32
$script:continuousTrace = [Collections.Generic.List[string]]::new()
$script:continuousEnabled = $true; $script:continuousRunning = $false
function Invoke-Adb {
    $command = $args -join ' '
    $script:continuousTrace.Add($command)
    if($command.StartsWith('shell settings put secure enabled_accessibility_services ')) {
        $script:continuousEnabled = $command.Contains($component) -or $command.Contains('app.luoxianlv.debug/.service.MusicAccessibilityService')
    }
    if($command -eq "shell am force-stop $package"){$script:continuousRunning=$false}
    if($command -eq "shell run-as $package pwd"){return '/data/user/0/app.luoxianlv.debug'}
    if($command.StartsWith('install ')){$script:continuousRunning=$script:continuousEnabled}
    if($command.Contains(' mv ') -and $script:continuousRunning){throw 'negative control: archived a live target process'}
}
$Adb = {
    $command = $args -join ' '
    $script:continuousTrace.Add($command)
    $global:LASTEXITCODE=0
    if($command.Contains(' pidof ') -and $script:continuousRunning){return '123'}
}
Prepare-ContinuousFresh ($other+':'+$component)
$trace = $continuousTrace -join "`n"
Require-Continuous ($trace.IndexOf('settings put') -lt $trace.IndexOf('am force-stop')) 'Target service was disabled too late.'
Require-Continuous ($trace.IndexOf('pidof') -lt $trace.IndexOf(' mv ')) 'Live PID was not checked before archive.'
Require-Continuous ($trace.LastIndexOf(' mv ') -lt $trace.IndexOf('install -r')) 'Installation preceded archive.'
Require-Continuous ($trace.Contains("'$other'")) 'Dollar-containing service was not shell-quoted.'
Require-Continuous (!$trace.Contains(' rm ')) 'Fresh removed history.'
$script:continuousEnabled=$true; $script:continuousRunning=$false
Reject-Continuous { Invoke-Adb shell am force-stop $package; Invoke-Adb install -r $HostApk; Invoke-Adb shell run-as $package mv no_backup/native-update no_backup/archive }
$continuousJava = [IO.File]::ReadAllText((Join-Path $continuousApp 'app-host/src/androidTest/java/app/luoxianlv/host/NativeContinuousChecks.java'))
Require-Continuous (!$continuousJava.Contains('"nextCheck"')) 'Diagnostic nextCheck is still injected.'
Require-Continuous ($continuousJava.Contains('stageTimeoutMillis') -and $continuousJava.Contains('awaitStage')) 'Actual shared stage deadline missing.'
Require-Continuous ($continuousJava.IndexOf('save("report.json", report); //') -lt $continuousJava.IndexOf('checkpoint("failed"')) 'Failed control can precede atomic failure report.'
Require-Continuous ($continuousJava.IndexOf('report.put("passed", true)') -gt $continuousJava.IndexOf('if (failure != null)')) 'Success report can precede cleanup failure handling.'
Require-Continuous ([regex]::Matches($continuousText,'Receive-ContinuousFailure \$state; throw').Count -eq 3) 'A failed handshake can bypass output flush.'
$goodReport = [pscustomobject]@{schema=1;passed=$false;runId=$runId;pid=123;stages=@();lastStage=[pscustomobject]@{completedStages=0};failureChain=@([pscustomobject]@{relation='root';type='app.luoxianlv.host.NativeContinuousChecks$CheckFailure';message='连续验收没有交接安全点'})}
$failedState = [pscustomobject]@{runId=$runId;pid=123;phase='failed'}
Assert-ContinuousFailureReport $goodReport $failedState
$continuousChecks++
Reject-Continuous { Assert-ContinuousFailureReport $goodReport ([pscustomobject]@{runId=('b'*32);pid=123;phase='failed'}) }
Reject-Continuous { Assert-ContinuousFailureReport $goodReport ([pscustomobject]@{runId=$runId;pid=124;phase='failed'}) }
$externalReport = [pscustomobject]@{schema=1;passed=$false;runId=$runId;pid=123;stages=@();lastStage=[pscustomobject]@{completedStages=0};failureChain=@([pscustomobject]@{relation='cause';type='java.io.IOException';message='https://invalid.local/hidden'})}
Reject-Continuous { Assert-ContinuousFailureReport $externalReport $failedState }
Assert-ContinuousSuccessReport ([pscustomobject]@{passed=$true;helperPlaybackStateRestored=$true})
$continuousChecks++
Reject-Continuous { Assert-ContinuousSuccessReport ([pscustomobject]@{passed=$true;helperPlaybackStateRestored=$true;failureChain=@()}) }
Reject-Continuous { Assert-ContinuousSuccessReport ([pscustomobject]@{passed=$true}) }
foreach($vector in @('  topResumedActivity=ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity t144}',
    '  ResumedActivity: ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity t144}',
    ' mResumedActivity: ActivityRecord{123 u0 app.luoxianlv.debug/.MainActivity t144}',
    ' topResumedActivity: ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity t144}')) {
    Require-Continuous (Test-ContinuousHomeResumed $vector) 'Actual resumed format was rejected.'
}
foreach($vector in @('  topResumedActivity=ActivityRecord{123 u0 com.other/app.luoxianlv.MainActivity t144}',
    '  topResumedActivity=ActivityRecord{123 u0 other.app.luoxianlv.debug/app.luoxianlv.MainActivity t144}',
    '  mPausedActivity: ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity t144}',
    '  Hist #0: ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity t144}',
    '  topResumedActivity=ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.MainActivity$Other t144}',
    '  topResumedActivity=ActivityRecord{123 u0 app.luoxianlv.debug/app.luoxianlv.ui.practice.WallpaperPickerActivity t144}')) {
    Require-Continuous (!(Test-ContinuousHomeResumed $vector)) 'Wrong app/page or historical record was accepted.'
}
Write-Output ("Continuous driver policies: $continuousChecks passed; fake ADB only, no device/API.")

$continuousProfile = [Environment]::GetFolderPath('UserProfile')
$continuousAndroid = Join-Path $continuousProfile 'AppData/Local/Android/Sdk/platforms/android-37.0/android.jar'
$continuousInputs = @('hot-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','hot-contract/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','app-host/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','app-business/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes','business-ui/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes','app-runtime/build/native-sdk/debug/runtime-sdk.jar') | ForEach-Object { Join-Path $continuousApp $_ }
$continuousClasspath = ($continuousInputs + @($continuousAndroid)) -join ';'
foreach($continuousInput in $continuousInputs+@($continuousAndroid)){if(!(Test-Path -LiteralPath $continuousInput)){throw "Missing frozen compile input: $continuousInput; no Gradle will run."}}
$continuousScratch = Join-Path $continuousApp ('.local/continuous-checks-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($continuousScratch)
$continuousFactory = Join-Path $continuousApp 'app-business/src/hotProbe/java/app/luoxianlv/hot/probe/HotProbeFactory.java'
$continuousHarness = Join-Path $continuousApp 'tools/test-support/NativeProbeResourceBindingChecks.java'
$continuousHelper = Join-Path $continuousApp 'app-host/src/androidTest/java/app/luoxianlv/host/NativeContinuousChecks.java'
$continuousClasses = Join-Path $continuousScratch 'classes'
$continuousFailureChecks = Join-Path $continuousApp 'tools/test-support/NativeContinuousFailureChecks.java'
& javac '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' --release 17 -encoding UTF-8 -cp $continuousClasspath -d $continuousClasses $continuousFactory $continuousHarness $continuousHelper $continuousFailureChecks
if($LASTEXITCODE -ne 0){throw 'Actual continuous helper/probe did not compile.'}
& java -cp ($continuousClasses+';'+$continuousClasspath) app.luoxianlv.host.NativeContinuousFailureChecks ([IO.Path]::GetRelativePath($continuousApp,$continuousClasses))
if($LASTEXITCODE -ne 0){throw 'Actual helper safe failure evidence failed.'}

# 真正本机子进程延迟输出，覆盖failed后两条管道刷出；无ADB/设备或网络。
$continuousFlushStart = [Diagnostics.ProcessStartInfo]::new()
$continuousFlushStart.FileName = (Get-Process -Id $PID).Path
$continuousFlushStart.UseShellExecute=$false; $continuousFlushStart.CreateNoWindow=$true
$continuousFlushStart.RedirectStandardOutput=$true; $continuousFlushStart.RedirectStandardError=$true
foreach($argument in @('-NoProfile','-Command','Start-Sleep -Milliseconds 150; [Console]::Out.WriteLine("own delayed diagnostic"); [Console]::Error.WriteLine("own delayed stderr")')){$continuousFlushStart.ArgumentList.Add($argument)}
$continuousFlushProcess=[Diagnostics.Process]::new();$continuousFlushProcess.StartInfo=$continuousFlushStart
if(!$continuousFlushProcess.Start()){throw 'Local output fixture failed to start.'}
$instrument=[pscustomobject]@{Process=$continuousFlushProcess;Out=$continuousFlushProcess.StandardOutput.ReadToEndAsync();Err=$continuousFlushProcess.StandardError.ReadToEndAsync()}
$stdout=Join-Path $continuousScratch 'fixture-stdout.txt';$stderr=Join-Path $continuousScratch 'fixture-stderr.txt'
Require-Continuous (Wait-ContinuousOutput $instrument 8000) 'Actual delayed output did not flush.'
Save-ContinuousOutput
Require-Continuous (([IO.File]::ReadAllText($stdout)).Contains('own delayed diagnostic')) 'Actual stdout missing.'
Require-Continuous (([IO.File]::ReadAllText($stderr)).Contains('own delayed stderr')) 'Actual stderr missing.'
Reject-Continuous { Wait-ContinuousOutput $instrument 15001 }
Write-Output 'PASS actual local process: delayed stdout/stderr retained after bounded flush; no device/API.'
& java -cp ($continuousClasses+';'+$continuousClasspath) app.luoxianlv.host.NativeProbeResourceBindingChecks
if($LASTEXITCODE -ne 0){throw 'Actual compiled delegate did not receive official resource binding.'}
$continuousFactoryText = [IO.File]::ReadAllText($continuousFactory)
$continuousBinding = '(?s)  @Override\r?\n  public void bindResources\(OfficialResources resources\) \{\r?\n    app.bindResources\(resources\);\r?\n  \}\r?\n'
if([regex]::Matches($continuousFactoryText,$continuousBinding).Count-ne1){throw 'Binding negative control target not unique.'}
$continuousOldDir=Join-Path $continuousScratch 'old-factory';[void][IO.Directory]::CreateDirectory($continuousOldDir)
$continuousOldFactory=Join-Path $continuousOldDir 'HotProbeFactory.java'
[IO.File]::WriteAllText($continuousOldFactory,[regex]::Replace($continuousFactoryText,$continuousBinding,''),[Text.UTF8Encoding]::new($false))
& javac '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' --release 17 -encoding UTF-8 -cp $continuousClasspath -d $continuousOldDir $continuousOldFactory $continuousHarness
if($LASTEXITCODE-ne0){throw 'Old factory counterexample failed compilation.'}
$continuousOldOutput = (& java -cp ($continuousOldDir+';'+$continuousClasspath) app.luoxianlv.host.NativeProbeResourceBindingChecks 2>&1 | Out-String)
if($LASTEXITCODE-eq0 -or !$continuousOldOutput.Contains('Actual AppBusinessFactory did not bind')){throw 'Old factory default-method omission was not reproduced.'}
Write-Output 'PASS negative control: original BusinessFactory default skips real AppBusinessFactory resource binding.'
Write-Output ('Actual helper/probe standalone outputs: '+$continuousScratch)
$continuousClock = Join-Path $continuousScratch 'SystemClock.java'
[IO.File]::WriteAllText($continuousClock, @'
package android.os;
public final class SystemClock {
 public static long now;
 public static long elapsedRealtime(){return now;}
 public static void sleep(long millis){now+=millis;}
}
'@,[Text.UTF8Encoding]::new($false))
$continuousBudgetClasses=Join-Path $continuousScratch 'budget-classes'
& javac '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' --release 17 -encoding UTF-8 -cp ($continuousClasses+';'+$continuousClasspath) -d $continuousBudgetClasses $continuousClock (Join-Path $continuousApp 'tools/test-support/NativeContinuousBudgetChecks.java')
if($LASTEXITCODE-ne0){throw 'Actual stage budget checks failed compilation.'}
& java -cp ($continuousBudgetClasses+';'+$continuousClasses+';'+$continuousClasspath) app.luoxianlv.host.NativeContinuousBudgetChecks
if($LASTEXITCODE-ne0){throw 'Actual stage total budget did not enforce expected boundaries.'}
