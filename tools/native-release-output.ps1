#Requires -Version 7.0

# 以同目录原子 rename 替换目录项，不能 WriteAllText 穿过输出的 hardlink/symlink。
function Write-AtomicNativeReport([string]$Path, [string]$Json) {
    $absolute = [System.IO.Path]::GetFullPath($Path)
    $directory = [System.IO.Path]::GetDirectoryName($absolute)
    [void][System.IO.Directory]::CreateDirectory($directory)
    $temporary = Join-Path $directory ('.native-release-report-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $stream = [System.IO.File]::Open($temporary, [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($Json)
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush($true)
        } finally { $stream.Dispose() }
        [System.IO.File]::Move($temporary, $absolute, $true)
    } finally {
        # 非递归，仅本次创建的单个临时目录项；Delete 不跟随链接。
        if ([System.IO.File]::Exists($temporary)) { [System.IO.File]::Delete($temporary) }
    }
}
