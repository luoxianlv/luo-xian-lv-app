#Requires -Version 7.0
param()
$ErrorActionPreference='Stop'
$cacheCheckApp=Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'test-native-download-driver-policy.ps1')
if(!$?){throw 'Download policy failed'}
$cacheCheckProfile=[Environment]::GetFolderPath('UserProfile')
$cacheCheckModules=Join-Path $cacheCheckProfile '.gradle/caches/modules-2/files-2.1'
$cacheCheckJunit=(Get-ChildItem -LiteralPath (Join-Path $cacheCheckModules 'junit/junit/4.13.2') -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
$cacheCheckHamcrest=(Get-ChildItem -LiteralPath (Join-Path $cacheCheckModules 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
$cacheCheckAndroid=Join-Path $cacheCheckProfile 'AppData/Local/Android/Sdk/platforms/android-37.0/android.jar'
$cacheCheckClasses=@('hot-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','hot-contract/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','app-host/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes')|ForEach-Object{Join-Path $cacheCheckApp $_}
$cacheCheckClasspath=($cacheCheckClasses+@($cacheCheckAndroid,$cacheCheckJunit,$cacheCheckHamcrest))-join';'
foreach($cacheCheckInput in $cacheCheckClasses+@($cacheCheckAndroid,$cacheCheckJunit,$cacheCheckHamcrest)){if(!(Test-Path -LiteralPath $cacheCheckInput)){throw 'Missing frozen javac/JUnit inputs; no Gradle will run'}}
$cacheCheckScratch=Join-Path $cacheCheckApp ('.local/cache-budget-checks-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($cacheCheckScratch)
$cacheCheckSources=@('app-host/src/androidTest/java/app/luoxianlv/host/NativeNetworkChecks.java','app-host/src/androidTest/java/app/luoxianlv/host/NativeDownloadChecks.java','tools/test-support/NativeDownloadPlanTest.java')|ForEach-Object{Join-Path $cacheCheckApp $_}
& javac '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' --release 17 -encoding UTF-8 -cp $cacheCheckClasspath -d $cacheCheckScratch @cacheCheckSources
if($LASTEXITCODE-ne0){throw 'Actual ordinary-host helper/Plan tests failed javac'}
& java -cp ($cacheCheckScratch+';'+$cacheCheckClasspath) org.junit.runner.JUnitCore app.luoxianlv.host.NativeDownloadPlanTest
if($LASTEXITCODE-ne0){throw 'Actual helper protocol/budget unit checks failed'}
Write-Output ('Actual helper standalone results retained: '+$cacheCheckScratch)
Write-Output 'No Gradle, device, system metering, API, signing or production state was changed.'
