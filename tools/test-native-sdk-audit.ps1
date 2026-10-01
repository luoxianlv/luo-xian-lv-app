#Requires -Version 7.0
[CmdletBinding()]
param([string]$AppRoot = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$nativeRoot = (Resolve-Path -LiteralPath $AppRoot).Path
$nativeTempParent = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$nativeScratch = Join-Path $nativeTempParent ('native-sdk-checks-' + [guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($nativeScratch)
try {
    & javac -encoding UTF-8 -d $nativeScratch `
        (Join-Path $nativeRoot 'tools/test-support/NativeSdkAudit.java') `
        (Join-Path $nativeRoot 'tools/test-support/NativeSdkAuditChecks.java') `
        (Join-Path $nativeRoot 'buildSrc/src/main/java/app/luoxianlv/buildlogic/NativeApkContents.java')
    if ($LASTEXITCODE -ne 0) { throw 'SDK audit checks failed to compile.' }
    & java -cp $nativeScratch app.luoxianlv.tools.NativeSdkAuditChecks
    if ($LASTEXITCODE -ne 0) { throw 'SDK audit checks failed.' }
    . (Join-Path $nativeRoot 'tools/native-release-output.ps1')
    $nativeIoPassed = 0
    $nativeIoSkipped = 0
    $normalOutput = Join-Path $nativeScratch 'normal.json'
    Write-AtomicNativeReport $normalOutput '{"releaseReady":false}'
    if ([System.IO.File]::ReadAllText($normalOutput) -ne '{"releaseReady":false}') { throw 'Atomic create failed.' }
    $nativeIoPassed++
    Write-AtomicNativeReport $normalOutput '{"releaseReady":true}'
    if ([System.IO.File]::ReadAllText($normalOutput) -ne '{"releaseReady":true}') { throw 'Atomic replace failed.' }
    $nativeIoPassed++
    $protectedArtifact = Join-Path $nativeScratch 'protected.apk'
    [System.IO.File]::WriteAllBytes($protectedArtifact, [System.Text.Encoding]::UTF8.GetBytes('public artifact must not be overwritten'))
    $protectedHash = (Get-FileHash -LiteralPath $protectedArtifact -Algorithm SHA256).Hash
    $hardlinkOutput = Join-Path $nativeScratch 'hardlink-output.json'
    [void](New-Item -ItemType HardLink -Path $hardlinkOutput -Target $protectedArtifact)
    Write-AtomicNativeReport $hardlinkOutput '{"hardlink":true}'
    if ((Get-FileHash -LiteralPath $protectedArtifact -Algorithm SHA256).Hash -ne $protectedHash -or
        [System.IO.File]::ReadAllText($hardlinkOutput) -ne '{"hardlink":true}') { throw 'Atomic hardlink output corrupted source artifact.' }
    $nativeIoPassed++
    $symlinkOutput = Join-Path $nativeScratch 'symlink-output.json'
    $symlinkCreated = $false
    try { [void](New-Item -ItemType SymbolicLink -Path $symlinkOutput -Target $protectedArtifact); $symlinkCreated = $true }
    catch { $nativeIoSkipped++; Write-Output 'Symbolic-link atomic output skipped: current Windows account cannot create it.' }
    if ($symlinkCreated) {
        Write-AtomicNativeReport $symlinkOutput '{"symlink":true}'
        if ((Get-FileHash -LiteralPath $protectedArtifact -Algorithm SHA256).Hash -ne $protectedHash -or
            [System.IO.File]::ReadAllText($symlinkOutput) -ne '{"symlink":true}') { throw 'Atomic symbolic-link output corrupted source artifact.' }
        $nativeIoPassed++
    }
    $directoryOutput = Join-Path $nativeScratch 'directory.json'
    [void][System.IO.Directory]::CreateDirectory($directoryOutput)
    $directoryRejected = $false
    try { Write-AtomicNativeReport $directoryOutput '{"mustReject":true}' } catch { $directoryRejected = $true }
    if (-not $directoryRejected -or -not [System.IO.Directory]::Exists($directoryOutput) -or
        @(Get-ChildItem -LiteralPath $nativeScratch -File -Filter '.native-release-report-*.tmp').Count -ne 0) {
        throw 'Atomic output directory rejection/temporary cleanup failed.'
    }
    $nativeIoPassed++
    Write-Output "Native release atomic output: $nativeIoPassed passed, $nativeIoSkipped skipped"
} finally {
    $nativeAbsoluteScratch = [System.IO.Path]::GetFullPath($nativeScratch)
    $nativeTempPrefix = $nativeTempParent.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if ($nativeAbsoluteScratch.StartsWith($nativeTempPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
        [System.IO.Path]::GetFileName($nativeAbsoluteScratch) -match '^native-sdk-checks-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $nativeAbsoluteScratch -Recurse -Force
    }
}
