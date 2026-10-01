#Requires -Version 7.0
param([string]$AppRoot = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'native-normal-release-policy.ps1')
$parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$scratch = Join-Path $parent ('native-normal-policy-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($scratch)
$script:normalChecks = 0
function Require-Rejected([scriptblock]$Action) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw 'Independent policy counterexample was accepted.' }
    $script:normalChecks++
}
function Write-ZipFixture([string]$Path, [string[]]$Names, [string]$Changed = '') {
    $zip = [IO.Compression.ZipFile]::Open($Path,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $Names) {
            $entry = $zip.CreateEntry($name)
            $stream = $entry.Open()
            try { $bytes=[Text.Encoding]::UTF8.GetBytes($(if ($name -eq $Changed) {'changed'} else {'fixed-public-test-bytes'})); $stream.Write($bytes) }
            finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
}
try {
    $names = @('AndroidManifest.xml','resources.arsc','classes.dex','assets/baseline/index.json','assets/baseline/runtime.apk','assets/baseline/business.apk')
    $base = Join-Path $scratch 'base.apk'; Write-ZipFixture $base $names
    $payload = Get-NormalReleasePayload $base
    $hash = (Get-FileHash -LiteralPath $base -Algorithm SHA256).Hash.ToLowerInvariant()
    $report = [pscustomobject]@{ role='host'; variant='release'; applicationId='app.luoxianlv'; verification='compiled-artifact-only'; apk=[pscustomobject]@{sha256=$hash}; build=[pscustomobject]@{r8=$true; resourceShrink=$false; sourceDirty=$false; commit=('1'*40)} }
    $badging = "package: name='app.luoxianlv' versionCode='16'"
    Assert-NormalReleaseSourcePolicy $report $hash $hash $badging $payload; $script:normalChecks++
    Assert-NormalReleasePayload $payload (Get-NormalReleasePayload $base); $script:normalChecks++
    if ((Get-NormalReleasePayloadFingerprint $payload) -notmatch '^[a-f0-9]{64}$') { throw 'Missing deterministic payload fingerprint.' }; $script:normalChecks++
    Require-Rejected { Assert-NormalReleaseSourcePolicy $report $hash ('0'*64) $badging $payload }
    Require-Rejected { Assert-NormalReleaseSourcePolicy $report $hash $hash "package: name='app.luoxianlv.releaseprobe' versionCode='16'" $payload }
    Require-Rejected { Assert-NormalReleaseSourcePolicy $report $hash $hash ($badging+"`napplication-debuggable") $payload }
    $report.build.r8=$false; Require-Rejected { Assert-NormalReleaseSourcePolicy $report $hash $hash $badging $payload }; $report.build.r8=$true
    $report.build.sourceDirty=$true; Require-Rejected { Assert-NormalReleaseSourcePolicy $report $hash $hash $badging $payload }; $report.build.sourceDirty=$false
    $report.build.resourceShrink=$true; Require-Rejected { Assert-NormalReleaseSourcePolicy $report $hash $hash $badging $payload }; $report.build.resourceShrink=$false
    foreach ($change in @('classes.dex','AndroidManifest.xml','resources.arsc','assets/baseline/runtime.apk','assets/baseline/business.apk')) {
        $changed=Join-Path $scratch ([guid]::NewGuid().ToString('N')+'.apk'); Write-ZipFixture $changed $names $change
        Require-Rejected { Assert-NormalReleasePayload $payload (Get-NormalReleasePayload $changed) }
    }
    $extra=Join-Path $scratch 'extra.apk'; Write-ZipFixture $extra ($names+@('assets/hot/config.json'))
    Require-Rejected { Assert-NormalReleasePayload $payload (Get-NormalReleasePayload $extra) }
    Require-Rejected { Assert-NormalReleaseSourcePolicy $report $hash $hash $badging (Get-NormalReleasePayload $extra) }
    $duplicate=Join-Path $scratch 'duplicate.apk'; Write-ZipFixture $duplicate ($names+@('classes.dex'))
    Require-Rejected { Get-NormalReleasePayload $duplicate }
    $unsafe=Join-Path $scratch 'unsafe.apk'; Write-ZipFixture $unsafe @('../classes.dex')
    Require-Rejected { Get-NormalReleasePayload $unsafe }
    [xml]$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'test-support/normal-release-instrumentation.xml') -Raw
    if ($manifest.manifest.package -cne 'app.luoxianlv.normalrelease.test' -or $manifest.manifest.instrumentation.targetPackage -cne 'app.luoxianlv' -or
        $manifest.manifest.instrumentation.name -cne 'app.luoxianlv.tools.NativeNormalReleaseInstrumentation' -or $manifest.manifest.application.debuggable -cne 'false') { throw 'Independent normal runner manifest differs.' }; $script:normalChecks++
    $source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'test-support/NativeNormalReleaseInstrumentation.java') -Raw
    if ($source -match 'import app\.luoxianlv\.hot\.|getMethod\("(?:initialize|preInitialize|markAgreed|connect)"|\.(?:performAction|command)\(') { throw 'Runner calls mutable business/consent/playback actions.' }; $script:normalChecks++
    if ([regex]::Matches($source,'\.startForegroundService\(').Count -ne 1 -or $source -match 'getDeclaredField|getDeclaredMethod|settings put|PlaybackForegroundService\.start') { throw 'Normal foreground probe must make one public start without private R8/system mutation access.' }; $script:normalChecks++
    $prepare = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'prepare-native-normal-release.ps1') -Raw
    if (-not $prepare.Contains('NativeForegroundLogEvents.java') -or -not $prepare.Contains('NormalForegroundIdleEvidence.java')) { throw 'Prepare must compile actual shared foreground evidence parser.' }; $script:normalChecks++
    Write-Output "Normal Release policy: $script:normalChecks independent checks passed"
} finally {
    $resolved=[IO.Path]::GetFullPath($scratch); $prefix=$parent.TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    if ($resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($resolved) -match '^native-normal-policy-[a-f0-9]{32}$') { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
