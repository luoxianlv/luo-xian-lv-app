#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedHostSha256,
    [string]$AppRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android/Sdk'),
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$BuildToolsVersion = '36.0.0',
    [string]$AndroidJar = (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platforms/android-37.0/android.jar')
)
# 仅外部编译与全新fake证书签副本；无Gradle/ADB/API/网络或用户keystore参数。
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'native-normal-release-policy.ps1')
. (Join-Path $PSScriptRoot 'native-release-output.ps1')
$root = (Resolve-Path -LiteralPath $AppRoot).Path
$tools = Join-Path $SdkRoot "build-tools/$BuildToolsVersion"
$aapt = (Resolve-Path -LiteralPath (Join-Path $tools 'aapt2.exe')).Path
$d8 = (Resolve-Path -LiteralPath (Join-Path $tools 'd8.bat')).Path
$align = (Resolve-Path -LiteralPath (Join-Path $tools 'zipalign.exe')).Path
$signer = (Resolve-Path -LiteralPath (Join-Path $tools 'apksigner.bat')).Path
$android = (Resolve-Path -LiteralPath $AndroidJar).Path
$source = Join-Path $root 'app-host/build/outputs/apk/release/app-host-release-unsigned.apk'
$report = Get-Content -LiteralPath (Join-Path $root 'app-host/build/native-report/release/report.json') -Raw | ConvertFrom-Json
$originalHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$originalPayload = Get-NormalReleasePayload $source
$badging = (& $aapt dump badging $source 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Normal APK badging failed.' }
Assert-NormalReleaseSourcePolicy $report $originalHash $ExpectedHostSha256 $badging $originalPayload
$run = [guid]::NewGuid().ToString('N')
$output = Join-Path $root "app-host/build/native-normal-device-fixtures/$run"
$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$scratch = Join-Path $tempParent "native-normal-release-$run"
[void][IO.Directory]::CreateDirectory($scratch)
[void][IO.Directory]::CreateDirectory($output)
function Invoke-NormalTool([string]$Program, [string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $lines = @(& $Program @Arguments 2>&1); $toolExit = $LASTEXITCODE }
    finally { $ErrorActionPreference = $previousPreference }
    if ($toolExit -ne 0) { throw "Local tool failed: $([IO.Path]::GetFileName($Program))" }
    return $lines
}
function Certificate-Of([string]$Apk) {
    $text = (Invoke-NormalTool $signer @('verify','--verbose','--print-certs',$Apk)) -join "`n"
    $certificateMatches = [regex]::Matches($text, '(?m)^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$')
    if ($certificateMatches.Count -ne 1 -or $text -notmatch '(?im)^Signer #\d+ certificate DN: .*CN=Android Debug(?:,|$)') { throw 'Not one verified local Android Debug certificate.' }
    return $certificateMatches[0].Groups[1].Value.ToLowerInvariant()
}
try {
    # 重新运行既有实际Java严格门禁，输出仅在本次临时目录。
    & (Join-Path $PSScriptRoot 'verify-native-release.ps1') -AppRoot $root -HostApk $source -ExpectOptimized -CompileOnly -ApkSigner $signer -ReportOutput (Join-Path $scratch 'compiled-audit.json') | Out-Null
    $classes = Join-Path $scratch 'classes'; [void][IO.Directory]::CreateDirectory($classes)
    $runner = Join-Path $PSScriptRoot 'test-support/NativeNormalReleaseInstrumentation.java'
    $foregroundAdapter = Join-Path $PSScriptRoot 'test-support/NormalForegroundIdleEvidence.java'
    $foregroundParser = Join-Path $root 'app-host/src/androidTest/java/app/luoxianlv/host/NativeForegroundLogEvents.java'
    $manifest = Join-Path $PSScriptRoot 'test-support/normal-release-instrumentation.xml'
    [void](Invoke-NormalTool 'javac' @('-encoding','UTF-8','--release','17','-proc:none','-classpath',$android,'-d',$classes,$runner,$foregroundAdapter,$foregroundParser))
    $jar = Join-Path $scratch 'runner.jar'
    [void](Invoke-NormalTool 'jar' @('--create','--file',$jar,'-C',$classes,'.'))
    $dex = Join-Path $scratch 'dex'; [void][IO.Directory]::CreateDirectory($dex)
    [void](Invoke-NormalTool $d8 @('--release','--min-api','26','--lib',$android,'--output',$dex,$jar))
    $test = Join-Path $scratch 'test-unsigned.apk'
    [void](Invoke-NormalTool $aapt @('link','--manifest',$manifest,'-I',$android,'-o',$test))
    $zip = [IO.Compression.ZipFile]::Open($test, [IO.Compression.ZipArchiveMode]::Update)
    try { foreach ($file in Get-ChildItem -LiteralPath $dex -File -Filter '*.dex') { [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$file.Name,[IO.Compression.CompressionLevel]::NoCompression) } }
    finally { $zip.Dispose() }
    $key = Join-Path $scratch 'fresh-fixture.p12'
    # 公共fake密码固定android；不读取任何已有证书/私钥。签完删除随机临时目录。
    [void](Invoke-NormalTool 'keytool' @('-genkeypair','-keystore',$key,'-storetype','PKCS12','-storepass','android','-keypass','android','-alias','local-normal-fixture','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Android Debug,O=Local Native Acceptance,C=US','-noprompt'))
    $signedHost = Join-Path $output 'host.local-test-signed.apk'
    $signedTest = Join-Path $output 'normal-instrumentation.local-test-signed.apk'
    foreach ($pair in @(@($source,$signedHost),@($test,$signedTest))) {
        $aligned = Join-Path $scratch ([IO.Path]::GetFileName($pair[1]))
        [void](Invoke-NormalTool $align @('-p','4',$pair[0],$aligned))
        [void](Invoke-NormalTool $signer @('sign','--ks',$key,'--ks-key-alias','local-normal-fixture','--ks-pass','pass:android','--key-pass','pass:android','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','false','--v4-signing-enabled','false','--out',$pair[1],$aligned))
    }
    $certificate = Certificate-Of $signedHost
    if ((Certificate-Of $signedTest) -cne $certificate) { throw 'Host/test certificates differ.' }
    Assert-NormalReleasePayload $originalPayload (Get-NormalReleasePayload $signedHost)
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedHostSha256) { throw 'Source changed while preparing fixture.' }
    $result = [ordered]@{
        schema=1; runId=$run; state='prepared'; verification='normal-release-original-payload-local-fixture-signature'; releaseReady=$false
        sourceCommit=$report.build.commit; buildSourceDirty=$report.build.sourceDirty; sourceHostSha256=$ExpectedHostSha256
        installedHostSha256=(Get-FileHash -LiteralPath $signedHost -Algorithm SHA256).Hash.ToLowerInvariant()
        testApkSha256=(Get-FileHash -LiteralPath $signedTest -Algorithm SHA256).Hash.ToLowerInvariant(); certificateSha256=$certificate
        runtimeSha256=$originalPayload['assets/baseline/runtime.apk'].sha256; businessSha256=$originalPayload['assets/baseline/business.apk'].sha256
        payloadFingerprint=(Get-NormalReleasePayloadFingerprint $originalPayload); payloadEntryCount=$originalPayload.Count; payloadEntryBytesPreserved=$true
        hostDebuggable=$false; hostTestOnly=$false; signing='new-temporary-local-Android-Debug-fixture'; productionCertificateUsed=$false
        targetPackage='app.luoxianlv'; testPackage='app.luoxianlv.normalrelease.test'; runner='app.luoxianlv.tools.NativeNormalReleaseInstrumentation'
        deviceHealth='not-verified'; rollback='not-verified'; deviceExecuted=$false; gradleExecuted=$false; apiExecuted=$false
    }
    Write-AtomicNativeReport (Join-Path $output 'prepared.json') (($result | ConvertTo-Json -Depth 8)+"`n")
    Write-Output "Normal Release fixture prepared. Device not executed. releaseReady=false"
    Write-Output $output
} finally {
    $resolved = [IO.Path]::GetFullPath($scratch)
    $prefix = $tempParent.TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    if ($resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($resolved) -match '^native-normal-release-[a-f0-9]{32}$') { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
