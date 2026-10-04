param([string[]]$OnlyTests = @())
$ErrorActionPreference = 'Stop'
$byteApp = Split-Path -Parent $PSScriptRoot
$byteUser = [Environment]::GetFolderPath('UserProfile')
$byteModules = Join-Path $byteUser '.gradle/caches/modules-2/files-2.1'
$byteJunit = (Get-ChildItem -LiteralPath (Join-Path $byteModules 'junit/junit/4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$byteHamcrest = (Get-ChildItem -LiteralPath (Join-Path $byteModules 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$byteInputs = @('hot-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes', 'hot-contract/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes', 'update-core/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes') | ForEach-Object { Join-Path $byteApp $_ }
$byteAndroid = Join-Path $byteUser 'AppData/Local/Android/Sdk/platforms/android-37.0/android.jar'
foreach ($byteInput in $byteInputs + @($byteJunit, $byteHamcrest, $byteAndroid)) {
    if (!(Test-Path -LiteralPath $byteInput)) { throw "Missing existing offline compile input: $byteInput. No Gradle will run." }
}
$byteOutput = Join-Path $byteApp ('.local/hot-byte-delta-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $byteOutput | Out-Null
$byteClasspath = ($byteInputs + @($byteJunit, $byteHamcrest, $byteAndroid)) -join ';'
$byteSources = @('HotObjectDeltaResolver','HotByteDeltaPlan','HotApiClient','UpdateClient','BundledBaseline','PreparationSpace','HotSignatures','ApkUpdateBridge') | ForEach-Object { Join-Path $byteApp "hot-core/src/main/java/app/luoxianlv/hot/$_.java" }
$byteSources += Join-Path $byteApp 'hot-contract/src/main/java/app/luoxianlv/hot/contract/SharedUpdate.java'
$byteSources += Join-Path $byteApp 'update-core/src/main/java/app/luoxianlv/update/Cancellation.java'
$byteTests = @('HotObjectDeltaResolverTest','ApkUpdatePriorityTest','OnlineClientTest','DownloadGrantTest','HttpObjectSourceTest','UpdateCancellationTest','PreparationSpaceTest')
$byteSources += $byteTests | ForEach-Object { Join-Path $byteApp "hot-core/src/test/java/app/luoxianlv/hot/$_.java" }
$byteSources += Join-Path $byteApp 'hot-core/src/testSupport/java/app/luoxianlv/hot/LoopbackHttp.java'
$byteArguments = @('-encoding','UTF-8','--release','17','-classpath',$byteClasspath,'-sourcepath',(Join-Path $byteApp 'hot-core/src/main/java'),'-d',$byteOutput) + $byteSources
$byteArgumentFile = Join-Path $byteOutput 'javac.args'
[IO.File]::WriteAllLines($byteArgumentFile, ($byteArguments | ForEach-Object { '"' + $_.Replace('\','/') + '"' }), [Text.UTF8Encoding]::new($false))
& javac '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' "@$byteArgumentFile"
if ($LASTEXITCODE -ne 0) { throw 'Byte-object resolver compilation failed.' }
$byteRunTests = if ($OnlyTests.Count -gt 0) { $OnlyTests } else { $byteTests }
foreach ($byteRunTest in $byteRunTests) { if ($byteRunTest -notin $byteTests) { throw 'Unknown test selector.' } }
& java '-Dfile.encoding=UTF-8' -cp ($byteOutput + ';' + $byteClasspath + ';' + (Join-Path $byteApp 'hot-core/src/test/resources')) org.junit.runner.JUnitCore ($byteRunTests | ForEach-Object { "app.luoxianlv.hot.$_" })
if ($LASTEXITCODE -ne 0) { throw 'Byte-object behavior tests failed.' }
Write-Host "Offline byte-object tests: $byteOutput"
