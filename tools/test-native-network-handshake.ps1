#Requires -Version 7.0
param()
$ErrorActionPreference = 'Stop'
$networkApp = Split-Path -Parent $PSScriptRoot
$networkProfile = [Environment]::GetFolderPath('UserProfile')
$networkCache = Join-Path $networkProfile '.gradle/caches/modules-2/files-2.1'
$networkAndroid = Join-Path $networkProfile 'AppData/Local/Android/Sdk/platforms/android-37.0/android.jar'
$networkJunit = (Get-ChildItem -LiteralPath (Join-Path $networkCache 'junit/junit/4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$networkHamcrest = (Get-ChildItem -LiteralPath (Join-Path $networkCache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$networkInputs = @('hot-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','hot-contract/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','app-host/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes','app-host/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar') | ForEach-Object { Join-Path $networkApp $_ }
$networkClasspath = ($networkInputs + @($networkAndroid,$networkJunit,$networkHamcrest)) -join ';'
foreach($networkInput in $networkInputs + @($networkAndroid,$networkJunit,$networkHamcrest)){if(-not(Test-Path -LiteralPath $networkInput)){throw "Missing existing offline input: $networkInput; no Gradle will run."}}
$networkScratch = Join-Path $networkApp ('.local/network-handshake-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($networkScratch)
$networkSources = @('app-host/src/androidTest/java/app/luoxianlv/host/NativeSchedulerChecks.java','app-host/src/androidTest/java/app/luoxianlv/host/NativeNetworkChecks.java','tools/test-support/NativeNetworkHandshakeTest.java') | ForEach-Object { Join-Path $networkApp $_ }
$networkArguments = @('-encoding','UTF-8','--release','17','-classpath',$networkClasspath,'-d',(Join-Path $networkScratch 'classes')) + $networkSources
$networkArgs = Join-Path $networkScratch 'javac.args'
[IO.File]::WriteAllLines($networkArgs,($networkArguments | ForEach-Object { '"' + $_.Replace('\','/') + '"' }),[Text.UTF8Encoding]::new($false))
& javac '-J-Dfile.encoding=UTF-8' "@$networkArgs"
if($LASTEXITCODE -ne 0){throw 'Actual Android helper failed standalone compilation.'}
& java -cp ((Join-Path $networkScratch 'classes') + ';' + $networkClasspath) app.luoxianlv.host.NativeNetworkHandshakeTest
if($LASTEXITCODE -ne 0){throw 'Actual handshake and bounded wait policy checks failed.'}
Write-Output ('Offline helper fixture retained: ' + $networkScratch)
Write-Output 'Android callback/network/scheduler recovery was not simulated or run; root owns real device verification.'
