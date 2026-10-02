#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-apk-signature.ps1')
$digest = '48e73acfb69979f899d975fbeb6f62cab20ed1938c4ad4da51b8a68ca42c6751'
foreach ($name in @('Signer #1', 'V2 Signer:', 'Signer (minSdkVersion=33, maxSdkVersion=2147483647)', 'Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647)')) {
    $text = "Verifies`n$name certificate SHA-256 digest: $digest`n$name certificate DN: CN=Android Debug`n"
    $result = Get-NativeApkSignature $text
    if ($result.certificates.Count -ne 1 -or $result.certificates[0] -cne $digest -or !$result.debugCertificate) {
        throw '已确认的 apksigner 格式未能保留证书及 Debug 身份。'
    }
}
foreach ($text in @("Source Stamp Signer certificate SHA-256 digest: $digest", "WARNING: Signer #1 certificate SHA-256 digest: $digest", "Signer #1 certificate SHA-256 digest: ${digest}00", 'Signer #1 certificate SHA-256 digest: incomplete')) {
    if ((Get-NativeApkSignature $text).certificates.Count -ne 0) { throw '非安装签名或不完整摘要被错误采纳。' }
}
$multiple = Get-NativeApkSignature "Signer #1 certificate SHA-256 digest: $digest`nSigner #2 certificate SHA-256 digest: $digest"
if ($multiple.certificates.Count -ne 2) { throw '多签名证据不能被自动去重为单签名。' }
$formal = Get-NativeApkSignature "V2 Signer: certificate SHA-256 digest: $digest`nV2 Signer: certificate DN: CN=Luoxianlv"
if ($formal.debugCertificate) { throw '正式证书被误判为 Debug。' }
Write-Output '通过：apksigner 已确认格式、Debug 身份、多签名、源戳和不完整摘要边界。'
