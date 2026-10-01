#Requires -Version 7.0
param()
$ErrorActionPreference='Stop'
$foregroundApp=Split-Path -Parent $PSScriptRoot
$foregroundProfile=[Environment]::GetFolderPath('UserProfile')
$foregroundCache=Join-Path $foregroundProfile '.gradle/caches/modules-2/files-2.1'
$foregroundAndroid=Join-Path $foregroundProfile 'AppData/Local/Android/Sdk/platforms/android-37.0/android.jar'
$foregroundJunit=(Get-ChildItem -LiteralPath (Join-Path $foregroundCache 'junit/junit/4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$foregroundHamcrest=(Get-ChildItem -LiteralPath (Join-Path $foregroundCache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$foregroundInputs=@('hot-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','hot-contract/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','app-host/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes') | ForEach-Object {Join-Path $foregroundApp $_}
$foregroundClasspath=($foregroundInputs+@($foregroundAndroid,$foregroundJunit,$foregroundHamcrest))-join';'
foreach($foregroundInput in $foregroundInputs+@($foregroundAndroid,$foregroundJunit,$foregroundHamcrest)){if(!(Test-Path -LiteralPath $foregroundInput)){throw "Missing frozen compile input: $foregroundInput; no Gradle will run."}}
$foregroundScratch=Join-Path $foregroundApp ('.local/foreground-log-checks-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($foregroundScratch)
$foregroundSources=@('app-host/src/androidTest/java/app/luoxianlv/host/NativeForegroundLogEvents.java','app-host/src/androidTest/java/app/luoxianlv/host/NativeServiceColdInstrumentation.java','tools/test-support/NativeForegroundLogEventsTest.java') | ForEach-Object {Join-Path $foregroundApp $_}
& javac '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' --release 17 -encoding UTF-8 -cp $foregroundClasspath -d $foregroundScratch @foregroundSources
if($LASTEXITCODE-ne0){throw 'Actual cold instrumentation/parser failed standalone compilation.'}
& java -cp ($foregroundScratch+';'+$foregroundClasspath) org.junit.runner.JUnitCore app.luoxianlv.host.NativeForegroundLogEventsTest
if($LASTEXITCODE-ne0){throw 'Foreground log evidence checks failed.'}
Write-Output ('Foreground parser fixtures retained: '+$foregroundScratch)
Write-Output 'No foreground service, device, network, activation or production state was simulated/run.'
