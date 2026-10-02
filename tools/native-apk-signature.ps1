# 只解析 apksigner 已验证输出中的公开证书；源戳证书不能冒充安装签名。
function Get-NativeApkSignature([string]$Text) {
    $signer = '(?:Signer #\d+|Signer \(minSdkVersion=\d+(?: \(dev release=true\))?, maxSdkVersion=\d+\)|V[123](?:\.1)? Signer):?'
    $matches = [regex]::Matches($Text, "(?m)^$signer certificate SHA-256 digest: ([0-9a-fA-F]{64})[ \t\r]*$")
    $certificates = @($matches | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() })
    $debug = $Text -match "(?im)^$signer certificate DN: .*CN=Android Debug(?:,|$)"
    return [pscustomobject]@{ certificates = $certificates; debugCertificate = $debug }
}
