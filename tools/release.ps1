param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [string]$Notes = "",
    [string]$Repo = "luoxianlv/luo-xian-lv-app",
    [string]$Target = "main",
    [switch]$UseDebugSigning
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root 'gradlew.bat'
if ($VersionCode -le 0) { throw 'VersionCode must be positive and greater than the previous release' }
$signingArgs = @("-PappVersionCode=$VersionCode", "-PappVersionName=$Version")
if ($UseDebugSigning) {
    $private = gh repo view $Repo --json isPrivate --jq .isPrivate
    if ($LASTEXITCODE -ne 0 -or $private -ne 'true') { throw 'Debug signing is allowed only for private test releases' }
    $signingArgs += "-PuseDebugSigning=true"
}
& $gradle -p $root assembleRelease @signingArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed" }

$module = if (Test-Path (Join-Path $root 'luo-xian-lv-app')) { 'luo-xian-lv-app' } else { 'app' }
$apk = Join-Path $root "$module\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $apk)) { throw 'Signed APK not found. Configure releaseStoreFile/releaseStorePassword/releaseKeyAlias/releaseKeyPassword in Gradle properties.' }
$verifier = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $verifier) { throw 'Android apksigner was not found' }
& $verifier verify $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$sha = "$apk.sha256"
[IO.File]::WriteAllText($sha, ((Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant() + '  app-release.apk'), [Text.UTF8Encoding]::new($false))
$tag = if ($UseDebugSigning) { "update-test-$Version" } else { "v$Version" }
$title = "落弦律 $Version"
$noteFile = Join-Path ([IO.Path]::GetTempPath()) ("luoxianlv-release-" + [Guid]::NewGuid() + '.md')
[IO.File]::WriteAllText($noteFile, $Notes, [Text.UTF8Encoding]::new($false))
$flags = if ($UseDebugSigning) { @('--prerelease', '--latest=false') } else { @() }
gh release create $tag $apk $sha --repo $Repo --title $title --notes-file $noteFile --target $Target @flags
if ($LASTEXITCODE -ne 0) { throw "GitHub release failed" }
Write-Host "Published $tag to $Repo"
