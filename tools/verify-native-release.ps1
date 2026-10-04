#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$AppRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$ExpectOptimized,
    [string]$HostApk,
    [string]$ExpectedCertificateSha256,
    [switch]$CompileOnly,
    [string]$ApkSigner,
    [string]$ReportOutput
)

# 只读取构建产物与公开证书，绝不调用 Gradle、签名、网络、ADB 或 CI。
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'native-release-output.ps1')
. (Join-Path $PSScriptRoot 'native-apk-signature.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$nativeRoot = (Resolve-Path -LiteralPath $AppRoot).Path
$nativeModuleRoot = if (Test-Path -LiteralPath (Join-Path $nativeRoot 'modules/app-host/build.gradle.kts')) {
    Join-Path $nativeRoot 'modules'
} else { $nativeRoot }

function Read-PublicJson([string]$Path, [long]$MaximumBytes = 16MB) {
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -gt $MaximumBytes) { throw 'Public metadata exceeds size limit.' }
    return [System.IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json
}
function Assert-Same($Actual, $Expected, [string]$Description) {
    if ([string]$Actual -cne [string]$Expected) { throw "Mismatch: $Description" }
}
function Assert-FileObject([string]$Path, $Object, [string]$Description) {
    $file = Get-Item -LiteralPath $Path
    if ($file.PSIsContainer) { throw "Not a regular file: $Description" }
    Assert-Same $file.Length $Object.size "$Description size"
    Assert-Same ((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()) $Object.sha256 "$Description hash"
}
function Find-OnlyApk([string]$Path) {
    $files = @(Get-ChildItem -LiteralPath $Path -File -Filter '*.apk')
    if ($files.Count -ne 1) { throw 'Expected exactly one current APK; clean stale outputs before rebuilding.' }
    return $files[0].FullName
}
function Get-FingerprintFile([string]$Path) {
    # ExportNativeBuildReport 写出的 sorted UTF-8/LF 清单原字节即 fingerprint 输入。
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

if (-not $HostApk) { $HostApk = Find-OnlyApk (Join-Path $nativeModuleRoot 'app-host/build/outputs/apk/release') }
$HostApk = (Resolve-Path -LiteralPath $HostApk).Path
if (-not $ApkSigner) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $buildTools = @(Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending)
    foreach ($directory in $buildTools) {
        $candidate = Join-Path $directory.FullName $(if ($IsWindows) { 'apksigner.bat' } else { 'apksigner' })
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $ApkSigner = $candidate; break }
    }
    if (-not $ApkSigner) { throw 'apksigner is required for public signature verification.' }
}
$ApkSigner = (Resolve-Path -LiteralPath $ApkSigner).Path
if (-not $ReportOutput) { $ReportOutput = Join-Path $nativeModuleRoot 'app-host/build/native-release-verification.json' }
$ReportOutput = [System.IO.Path]::GetFullPath($ReportOutput)
if ([System.IO.Path]::GetExtension($ReportOutput) -ine '.json') { throw 'Verification output must be a JSON file.' }

$scratchParent = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$scratch = Join-Path $scratchParent ('native-release-audit-' + [guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($scratch)
$canWriteReport = $false
$verificationRunId = [guid]::NewGuid().ToString('N')
try {
    $classes = Join-Path $scratch 'classes'
    [void][System.IO.Directory]::CreateDirectory($classes)
    $javaSource = Join-Path $nativeRoot 'tools/test-support/NativeSdkAudit.java'
    $dexSource = Join-Path $nativeRoot 'buildSrc/src/main/java/app/luoxianlv/buildlogic/NativeApkContents.java'
    $reports = @{}
    $audits = @{}
    $frozenApks = @{}
    $inputs = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    [void]$inputs.Add($HostApk)
    $moduleApks = @{
        host = $HostApk
        runtime = Join-Path $nativeModuleRoot 'app-runtime/build/native-link/release/runtime.apk'
        business = Join-Path $nativeModuleRoot 'app-business/build/native-link/release/business.apk'
    }
    $sdkJars = @{
        host = Join-Path $nativeModuleRoot 'hot-contract/build/native-sdk/release/host-contract-sdk.jar'
        runtime = Join-Path $nativeModuleRoot 'app-runtime/build/native-sdk/release/runtime-sdk.jar'
    }
    $packageIds = @{ host = 128; runtime = 127; business = 129 }
    foreach ($role in @('host', 'runtime', 'business')) {
        [void]$inputs.Add([System.IO.Path]::GetFullPath($moduleApks[$role]))
        if ($role -ne 'business') { [void]$inputs.Add([System.IO.Path]::GetFullPath($sdkJars[$role])) }
        foreach ($metadata in @('report.json', 'sdk-contract.json', 'classes.txt', 'exports.txt', 'mapping.txt')) {
            [void]$inputs.Add([System.IO.Path]::GetFullPath((Join-Path $nativeModuleRoot "app-$role/build/native-report/release/$metadata")))
        }
        [void]$inputs.Add([System.IO.Path]::GetFullPath((Join-Path $nativeModuleRoot "app-$role/build/outputs/mapping/release/mapping.txt")))
    }
    if ($inputs.Contains($ReportOutput)) { throw 'Verification output must not overwrite an input artifact.' }
    $canWriteReport = $true
    Write-AtomicNativeReport $ReportOutput (([ordered]@{ schema = 1; runId = $verificationRunId; state = 'verifying'; releaseReady = $false } | ConvertTo-Json) + "`n")
    if (-not $CompileOnly -and $ExpectedCertificateSha256 -notmatch '^[a-fA-F0-9]{64}$') {
        throw 'Formal verification requires the public ExpectedCertificateSha256 (64 hex characters).'
    }
    if ($ExpectedCertificateSha256 -and $ExpectedCertificateSha256 -notmatch '^[a-fA-F0-9]{64}$') {
        throw 'ExpectedCertificateSha256 must have 64 hex characters.'
    }
    & javac -encoding UTF-8 -d $classes $javaSource $dexSource
    if ($LASTEXITCODE -ne 0) { throw 'Standalone SDK/DEX audit compilation failed.' }
    foreach ($role in @('host', 'runtime', 'business')) {
        $directory = Join-Path $nativeModuleRoot "app-$role/build/native-report/release"
        $reportPath = Join-Path $directory 'report.json'
        foreach ($metadata in @('report.json', 'sdk-contract.json', 'classes.txt', 'exports.txt')) {
            [void]$inputs.Add([System.IO.Path]::GetFullPath((Join-Path $directory $metadata)))
        }
        $r = Read-PublicJson $reportPath
        $reports[$role] = $r
        Assert-Same $r.schema 1 "$role schema"
        Assert-Same $r.role $role "$role role"
        Assert-Same $r.variant 'release' "$role variant"
        Assert-Same $r.applicationId $(if ($role -eq 'host') { 'app.luoxianlv' } else { "app.luoxianlv.$role" }) "$role applicationId"
        Assert-Same $r.exportAlgorithm 'dex-public-protected-signatures-v1' "$role export algorithm"
        Assert-Same $r.resourcePackageIds.Count 1 "$role resource package count"
        Assert-Same $r.resourcePackageIds[0] $packageIds[$role] "$role resource package ID"
        $wantR8 = $ExpectOptimized.IsPresent -and $role -ne 'business'
        Assert-Same $r.build.r8 $wantR8 "$role R8 policy"
        Assert-Same $r.build.resourceShrink $false "$role resource shrink policy"
        Assert-FileObject $moduleApks[$role] $r.apk "$role current APK"
        [void]$inputs.Add([System.IO.Path]::GetFullPath($moduleApks[$role]))
        # DEX、baseline 与证书读取同一本次隔离副本；不在共享 build 路径多次打开不同版本。
        $frozenApks[$role] = Join-Path $scratch "$role.apk"
        [System.IO.File]::Copy($moduleApks[$role], $frozenApks[$role], $false)
        Assert-FileObject $frozenApks[$role] $r.apk "$role frozen APK"
        $auditArgs = @('-cp', $classes, 'app.luoxianlv.tools.NativeSdkAudit', $role, $frozenApks[$role])
        if ($role -eq 'business') { $auditArgs += $reports.host.sdkJarHash }
        if ($role -ne 'business') {
            [void]$inputs.Add([System.IO.Path]::GetFullPath($sdkJars[$role]))
            $frozenSdk = Join-Path $scratch "$role-sdk.jar"
            [System.IO.File]::Copy($sdkJars[$role], $frozenSdk, $false)
            Assert-Same ((Get-FileHash -LiteralPath $frozenSdk -Algorithm SHA256).Hash.ToLowerInvariant()) $r.sdkJarHash "$role frozen SDK hash"
            $auditArgs += $frozenSdk
        }
        if ($wantR8) {
            $mapping = Join-Path $directory 'mapping.txt'
            Assert-Same $r.mappingObject.path 'mapping.txt' "$role frozen mapping path"
            Assert-FileObject $mapping $r.mappingObject "$role frozen mapping"
            Assert-Same $r.mappingObject.sha256 $r.build.mappingHash "$role mapping build binding"
            $currentMapping = Join-Path $nativeModuleRoot "app-$role/build/outputs/mapping/release/mapping.txt"
            Assert-FileObject $currentMapping $r.mappingObject "$role current AGP mapping"
            [void]$inputs.Add([System.IO.Path]::GetFullPath($mapping))
            [void]$inputs.Add([System.IO.Path]::GetFullPath($currentMapping))
            $frozenMapping = Join-Path $scratch "$role-mapping.txt"
            [System.IO.File]::Copy($mapping, $frozenMapping, $false)
            Assert-FileObject $frozenMapping $r.mappingObject "$role audit mapping copy"
            $auditArgs += $frozenMapping
        } else {
            Assert-Same $r.build.mappingHash '' "$role disabled mapping"
            if ($r.PSObject.Properties['mappingObject']) { throw "Disabled R8 report binds mapping: $role" }
            if (Test-Path -LiteralPath (Join-Path $directory 'mapping.txt')) { throw "Disabled R8 frozen directory has stale mapping: $role" }
        }
        $auditText = @(& java @auditArgs)
        if ($LASTEXITCODE -ne 0) { throw "Actual SDK/DEX audit failed: $role" }
        $actual = ($auditText -join "`n") | ConvertFrom-Json
        $audits[$role] = $actual
        Assert-Same $actual.apkHash $r.apk.sha256 "$role audit APK binding"
        Assert-Same $actual.sdkHash $r.sdkJarHash "$role audit SDK binding"
        Assert-Same $actual.classCount $r.classCount "$role actual class count"
        Assert-Same $actual.classFingerprint $r.classFingerprint "$role actual class fingerprint"
        Assert-Same $actual.exportFingerprint $r.exportFingerprint "$role actual export fingerprint"
        Assert-Same $actual.resourcePackageIds[0] $packageIds[$role] "$role actual resource package ID"
        if ($role -eq 'business' -and $actual.nativeLibraryCount -ne 0) { throw 'Business APK contains native libraries.' }
        Assert-Same (Get-FingerprintFile (Join-Path $directory 'classes.txt')) $r.classFingerprint "$role class list bytes"
        Assert-Same (Get-FingerprintFile (Join-Path $directory 'exports.txt')) $r.exportFingerprint "$role export list bytes"
        $contract = Read-PublicJson (Join-Path $directory 'sdk-contract.json') 64MB
        Assert-Same $contract.apkHash $r.apk.sha256 "$role sdk-contract APK"
        Assert-Same $contract.sdkJarHash $r.sdkJarHash "$role sdk-contract SDK"
        Assert-Same $contract.exportFingerprint $r.exportFingerprint "$role sdk-contract exports"
        $contractDigest = [System.Security.Cryptography.SHA256]::Create()
        try {
            $contractBytes = [System.Text.Encoding]::UTF8.GetBytes(($contract.exports -join "`n") + "`n")
            $contractHash = [System.BitConverter]::ToString($contractDigest.ComputeHash($contractBytes)).Replace('-', '').ToLowerInvariant()
        } finally { $contractDigest.Dispose() }
        Assert-Same $contractHash $r.exportFingerprint "$role sdk-contract actual exports"
    }

    foreach ($role in @('runtime', 'business')) {
        $related = @($reports.host.related | Where-Object { $_.role -eq $role })
        Assert-Same $related.Count 1 "host related $role count"
        Assert-Same ($related[0] | ConvertTo-Json -Depth 100 -Compress) ($reports[$role] | ConvertTo-Json -Depth 100 -Compress) "host related $role current report"
    }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($frozenApks.host)
    try {
        $baselineEntry = $zip.GetEntry('assets/baseline/index.json')
        if (-not $baselineEntry -or $baselineEntry.Length -gt 16384) { throw 'Missing/oversized bundled baseline index.' }
        $reader = [System.IO.StreamReader]::new($baselineEntry.Open(), [System.Text.UTF8Encoding]::new($false, $true))
        try { $baseline = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        Assert-Same $baseline.runtimeAbi $reports.runtime.runtimeAbi 'bundled baseline runtime ABI'
        Assert-Same $baseline.entryClass 'app.luoxianlv.business.AppBusinessFactory' 'bundled business entry'
        foreach ($role in @('runtime', 'business')) {
            $entry = $zip.GetEntry("assets/baseline/$role.apk")
            if (-not $entry) { throw "Missing bundled baseline: $role" }
            Assert-Same $entry.Length $reports[$role].apk.size "bundled $role size"
            $stream = $entry.Open()
            $digest = [System.Security.Cryptography.SHA256]::Create()
            try { $hash = [System.BitConverter]::ToString($digest.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() } finally { $stream.Dispose(); $digest.Dispose() }
            Assert-Same $hash $reports[$role].apk.sha256 "bundled $role actual bytes"
            Assert-Same $baseline.$role.sha256 $hash "bundled $role index hash"
            Assert-Same $baseline.$role.size $entry.Length "bundled $role index size"
        }
    } finally { $zip.Dispose() }

    # 只读 APK 公开证书，不提供 keystore 参数，也不打印命令完整输出。
    $previousErrorPreference = $ErrorActionPreference
    try {
        # 未签名产物的 stderr 也捕获为数据；正式路径仍按 exit code 严格失败。
        $ErrorActionPreference = 'Continue'
        $signerOutput = @(& $ApkSigner verify --verbose --print-certs $frozenApks.host 2>&1)
        $signatureExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorPreference }
    $signerText = $signerOutput -join "`n"
    $parsedSignature = Get-NativeApkSignature $signerText
    $certificates = $parsedSignature.certificates
    $debugCertificate = $parsedSignature.debugCertificate
    $signatureStatus = 'not-verified'
    if ($signatureExit -eq 0) {
        if ($certificates.Count -ne 1) {
            $publicDigestLines = @($signerOutput | Where-Object { [string]$_ -match 'certificate SHA-256 digest:' })
            throw "Expected one verified current signing certificate; parsed=$($certificates.Count); public digest lines: $($publicDigestLines -join '; ')"
        }
        if ($ExpectedCertificateSha256) { Assert-Same $certificates[0] $ExpectedCertificateSha256.ToLowerInvariant() 'public signing certificate' }
        $signatureStatus = if ($debugCertificate) { 'verified-debug-certificate' } elseif ($CompileOnly) { 'verified-certificate-compile-only' } else { 'verified-expected-release-certificate' }
    }
    if (-not $CompileOnly -and ($signatureExit -ne 0 -or $debugCertificate)) {
        throw 'Formal release verification rejected an unverified signature or Android Debug certificate.'
    }
    # 独立副本已经提供一致字节证据；再拒绝核验期间被替换的公开 APK 输入。
    foreach ($role in @('host', 'runtime', 'business')) {
        Assert-FileObject $moduleApks[$role] $reports[$role].apk "$role input changed during verification"
    }
    if ($inputs.Contains($ReportOutput)) { throw 'Verification output must not overwrite an input artifact.' }
    $result = [ordered]@{
        schema = 1
        runId = $verificationRunId
        state = 'passed'
        verification = 'local-compiled-artifact-and-public-signature'
        releaseReady = (-not $CompileOnly -and $signatureExit -eq 0 -and -not $debugCertificate)
        signature = [ordered]@{ status = $signatureStatus; verifyExitCode = $signatureExit; certificateSha256 = $certificates; debugCertificate = $debugCertificate }
        optimization = [ordered]@{ host = $reports.host.build.r8; runtime = $reports.runtime.build.r8; business = $reports.business.build.r8; resourceShrink = $false }
        hostApk = $reports.host.apk
        runtimeApk = $reports.runtime.apk
        businessApk = $reports.business.apk
        sdkAudits = @($audits.host, $audits.runtime)
        mapping = [ordered]@{ host = $reports.host.build.mappingHash; runtime = $reports.runtime.build.mappingHash; business = $reports.business.build.mappingHash }
        sourceCommit = $reports.host.build.commit
        sourceDirty = $reports.host.build.sourceDirty
        limits = @('No device/health/rollback evidence', 'No generic-signature/annotation ABI equality proof', 'No CI or deployment performed')
    }
    Write-AtomicNativeReport $ReportOutput (($result | ConvertTo-Json -Depth 30) + "`n")
    Write-Output "Native release artifact audit passed. Signature: $signatureStatus. releaseReady=$($result.releaseReady)"
    Write-Output $ReportOutput
    # CompileOnly允许未签名；不能把签名探测的退出码遗留成整项编译核验失败。
    $global:LASTEXITCODE = 0
} catch {
    $verificationFailure = $_
    if ($canWriteReport) {
        try {
            Write-AtomicNativeReport $ReportOutput (([ordered]@{ schema = 1; runId = $verificationRunId; state = 'failed'; releaseReady = $false } | ConvertTo-Json) + "`n")
        } catch { } # 输出目录不可写时保留原核验错误；调用方仍必须检查非零退出码。
    }
    throw $verificationFailure
} finally {
    # 只删除本次创建的随机临时编译目录，先核验绝对路径仍在系统 TEMP 下。
    $resolvedScratch = [System.IO.Path]::GetFullPath($scratch)
    $tempPrefix = $scratchParent.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if ($resolvedScratch.StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
        [System.IO.Path]::GetFileName($resolvedScratch) -match '^native-release-audit-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
    }
}
