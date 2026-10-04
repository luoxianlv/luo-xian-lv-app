#Requires -Version 7.0
param()
$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
$profilePath=[Environment]::GetFolderPath('UserProfile')
$sdk=Join-Path $profilePath 'AppData/Local/Android/Sdk/platforms/android-37.0/android.jar'
$cache=Join-Path $profilePath '.gradle/caches/modules-2/files-2.1'
$junit=(Get-ChildItem -LiteralPath (Join-Path $cache 'junit/junit/4.13.2') -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
$hamcrest=(Get-ChildItem -LiteralPath (Join-Path $cache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar'|Select-Object -First 1).FullName
$classes=Join-Path $repoRoot ('.local/native-download-plan-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($classes)
$inputs=@(
    (Join-Path $repoRoot 'modules/app-host/src/androidTest/java/app/luoxianlv/host/NativeDownloadChecks.java'),
    (Join-Path $repoRoot 'modules/app-host/src/androidTest/java/app/luoxianlv/host/NativeNetworkChecks.java'),
    (Join-Path $repoRoot 'tools/test-support/NativeDownloadPlanTest.java'))
$classpath=@(
    (Join-Path $repoRoot 'modules/app-host/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),
    (Join-Path $repoRoot 'modules/hot-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),
    (Join-Path $repoRoot 'modules/hot-contract/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),
    $sdk,$junit,$hamcrest) -join ';'
$arguments=@('-encoding','UTF-8','--release','17','-classpath',$classpath,'-d',$classes)+$inputs
$argfile=Join-Path $classes 'compiler.args'
[IO.File]::WriteAllLines($argfile,($arguments|ForEach-Object {'"'+$_.Replace('\','/')+'"'}),[Text.UTF8Encoding]::new($false))
& javac "@$argfile"
if($LASTEXITCODE-ne 0){throw '真实下载helper独立编译失败'}
& java -cp "$classes;$classpath" org.junit.runner.JUnitCore app.luoxianlv.host.NativeDownloadPlanTest
if($LASTEXITCODE-ne 0){throw '实际下载计划/握手检查失败'}
Write-Output "计划/握手检查目录：$classes"
