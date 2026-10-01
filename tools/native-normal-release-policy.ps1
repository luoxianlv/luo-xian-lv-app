#Requires -Version 7.0
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-NormalReleasePayload([string]$Apk) {
    $file = Get-Item -LiteralPath $Apk
    if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $file.Length -gt 512MB) { throw 'Invalid APK input.' }
    $zip = [IO.Compression.ZipFile]::OpenRead($file.FullName)
    $entries = [Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal)
    [long]$total = 0
    try {
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName
            if (-not $name -or $name -match '[\\:\x00\r\n]' -or $name.StartsWith('/') -or $name.Split('/') -contains '..' -or $name.Split('/') -contains '.' -or $entries.ContainsKey($name)) { throw 'Invalid/duplicate APK entry.' }
            if ($entry.Length -gt 512MB -or ($total += $entry.Length) -gt 512MB) { throw 'Expanded APK exceeds bound.' }
            $entryInput = $entry.Open(); $digest = [Security.Cryptography.SHA256]::Create()
            try { $hash = [Convert]::ToHexString($digest.ComputeHash($entryInput)).ToLowerInvariant() }
            finally { $entryInput.Dispose(); $digest.Dispose() }
            $entries.Add($name, [pscustomobject]@{ size = $entry.Length; sha256 = $hash })
        }
    } finally { $zip.Dispose() }
    return ,$entries
}

function Assert-NormalReleasePayload($Original, $Signed) {
    if ($Original.Count -ne $Signed.Count) { throw 'Signing changed APK entry set.' }
    foreach ($name in $Original.Keys) {
        if (-not $Signed.ContainsKey($name) -or $Original[$name].size -ne $Signed[$name].size -or $Original[$name].sha256 -cne $Signed[$name].sha256) { throw 'Signing changed APK payload bytes.' }
    }
}

function Get-NormalReleasePayloadFingerprint($Entries) {
    $text = [Text.StringBuilder]::new()
    foreach ($name in $Entries.Keys) { [void]$text.Append($name).Append('|').Append($Entries[$name].size).Append('|').Append($Entries[$name].sha256).Append("`n") }
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text.ToString()))).ToLowerInvariant()
}

function Assert-NormalReleaseSourcePolicy($Report, [string]$ActualHash, [string]$ExpectedHash, [string]$Badging, $Payload) {
    if ($ExpectedHash -notmatch '^[a-f0-9]{64}$' -or $ActualHash -cne $ExpectedHash -or $Report.apk.sha256 -cne $ExpectedHash -or
        $Report.role -cne 'host' -or $Report.variant -cne 'release' -or $Report.applicationId -cne 'app.luoxianlv' -or
        $Report.verification -cne 'compiled-artifact-only' -or $Report.build.r8 -ne $true -or $Report.build.resourceShrink -ne $false -or
        $Report.build.sourceDirty -ne $false -or $Report.build.commit -notmatch '^[a-f0-9]{40}$' -or
        $Badging -notmatch "(?m)^package: name='app\.luoxianlv' " -or $Badging -match 'application-debuggable' -or
        $Payload.ContainsKey('assets/hot/config.json')) { throw 'Only pinned normal non-debuggable/no-hot-config optimized Release is allowed.' }
    foreach ($name in @('AndroidManifest.xml','resources.arsc','classes.dex','assets/baseline/index.json','assets/baseline/runtime.apk','assets/baseline/business.apk')) {
        if (-not $Payload.ContainsKey($name)) { throw 'Normal payload is incomplete.' }
    }
}
