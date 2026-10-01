#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$AppRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$AndroidJar = (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platforms/android-37.0/android.jar'),
    [string]$KotlinCompiler = 'C:/kotlin/bin/kotlinc.bat'
)
$ErrorActionPreference = 'Stop'
$probeRoot = (Resolve-Path -LiteralPath $AppRoot).Path
$probeScratch = Join-Path $probeRoot ('.local/native-release-probe-checks-' + [guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($probeScratch)
$probeScript = [System.IO.File]::ReadAllText((Join-Path $probeRoot 'gradle/native-release-probe.gradle.kts'))
$policyStart = $probeScript.IndexOf('fun requireNativeReleaseProbePolicy(')
$policyEnd = $probeScript.IndexOf('// PROBE_POLICY_END')
if ($policyStart -lt 0 -or $policyEnd -le $policyStart) { throw 'Actual profile policy function not found.' }
$probePolicyFile = Join-Path $probeScratch 'NativeReleaseProbePolicy.kt'
[System.IO.File]::WriteAllText($probePolicyFile, $probeScript.Substring($policyStart, $policyEnd - $policyStart), [System.Text.UTF8Encoding]::new($false))
& $KotlinCompiler $probePolicyFile (Join-Path $probeRoot 'tools/test-support/NativeReleaseProbePolicyChecks.kt') -include-runtime -d (Join-Path $probeScratch 'policy-checks.jar')
if ($LASTEXITCODE -ne 0) { throw 'Independent actual probe policy compilation failed.' }
& java -cp (Join-Path $probeScratch 'policy-checks.jar') NativeReleaseProbePolicyChecksKt
if ($LASTEXITCODE -ne 0) { throw 'Actual probe policy checks failed.' }
$probeSource = Join-Path $probeRoot 'app-host/src/releaseProbeAndroidTest/java/app/luoxianlv/host/NativeReleaseInstrumentation.java'
& javac '-J-Dfile.encoding=UTF-8' '-J-Dstdout.encoding=UTF-8' '-J-Dstderr.encoding=UTF-8' --release 17 -encoding UTF-8 -proc:none -classpath $AndroidJar -d $probeScratch $probeSource
if ($LASTEXITCODE -ne 0) { throw 'Independent Release instrumentation compilation failed.' }
$probeSourceText = [System.IO.File]::ReadAllText($probeSource)
if ($probeSourceText -match 'import app\.luoxianlv\.hot\.|Class\.forName\("app\.luoxianlv\.hot\.(?!contract\.)|getMethod\("(?:initialize|preInitialize|markAgreed)"|UMConfigure|MobclickAgent|UMCrash') {
    throw 'Probe references an obfuscated internal type or performs SDK/consent initialization.'
}
[xml]$probeManifest = [System.IO.File]::ReadAllText((Join-Path $probeRoot 'app-host/src/releaseProbeAndroidTest/AndroidManifest.xml'))
if ($probeManifest.manifest.instrumentation.targetPackage -ne 'app.luoxianlv.releaseprobe' -or
    $probeManifest.manifest.instrumentation.name -ne 'app.luoxianlv.host.NativeReleaseInstrumentation' -or
    @($probeManifest.manifest.instrumentation).Count -ne 1) { throw 'Probe runner/target manifest is not isolated.' }
[xml]$profileManifest = [System.IO.File]::ReadAllText((Join-Path $probeRoot 'app-host/src/releaseProbe/AndroidManifest.xml'))
if ($profileManifest.manifest.application.debuggable -ne 'false' -or $profileManifest.manifest.application.testOnly -ne 'true') {
    throw 'Probe manifest is not a non-debuggable testOnly package.'
}
foreach ($requiredDsl in @('android.testBuildType = "release"', 'applicationIdSuffix = ".releaseprobe"',
    'android.defaultConfig.testApplicationId = "app.luoxianlv.releaseprobe.test"', 'setRoot("src/releaseProbeAndroidTest")')) {
    if (-not $probeScript.Contains($requiredDsl)) { throw 'Required isolated profile DSL missing.' }
}
$normalBuild = [System.IO.File]::ReadAllText((Join-Path $probeRoot 'app-host/build.gradle.kts'))
if (-not $normalBuild.Contains('if (providers.gradleProperty("nativeReleaseProbe").orNull == "true") apply(')) {
    throw 'Normal production build must not apply probe without explicit opt-in.'
}
Write-Output 'Independent Release instrumentation + profile XML/static checks: pass'
Write-Output ('Local fixture output retained: ' + $probeScratch)
