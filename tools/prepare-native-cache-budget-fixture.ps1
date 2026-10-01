#Requires -Version 7.0
param()
$ErrorActionPreference='Stop'
$cacheApp=Split-Path -Parent $PSScriptRoot
$cacheCli=[IO.Path]::GetFullPath((Join-Path $cacheApp '../luo-xian-lv-hot-update'))
$cacheSource=Join-Path $cacheCli '.local/native-continuous-latest-20261001'
$cacheDestination=Join-Path $cacheCli '.local/native-cache-budget-latest-20261001'
if(Test-Path -LiteralPath $cacheDestination){throw '公开fixture目录已存在，保留原资料并拒绝覆盖；本工具不签名/上传/发布。'}
[void][IO.Directory]::CreateDirectory($cacheDestination)
$cacheUtf8=[Text.UTF8Encoding]::new($false,$true)
foreach($name in @('runtime.apk','business-C.apk','root.public.json','trust.json','trust.json.sig.json')) {
    [IO.File]::Copy((Join-Path $cacheSource $name),(Join-Path $cacheDestination $name),$false)
}
$cacheRecipe=[IO.File]::ReadAllText((Join-Path $cacheSource 'recipe-C.json'),$cacheUtf8)|ConvertFrom-Json
$cacheMetadata=[IO.File]::ReadAllText((Join-Path $cacheSource 'pack-C.json'),$cacheUtf8)|ConvertFrom-Json
$cachePalette=[ordered]@{schema=1;light=[ordered]@{primary='#FF1565C0';onPrimary='#FFFFFFFF';secondary='#FF008577';background='#FFF7F9FC';surface='#FFF7F9FC';onBackground='#FF18212B';onSurface='#FF18212B'};
    dark=[ordered]@{primary='#FF90CAF9';onPrimary='#FF002A4D';secondary='#FF80CBC4';background='#FF101820';surface='#FF101820';onBackground='#FFE0E8EF';onSurface='#FFE0E8EF'}}
$cacheTheme=[ordered]@{'palette.json'=$cacheUtf8.GetBytes(($cachePalette|ConvertTo-Json -Depth 5 -Compress))}
$cacheShaders=[ordered]@{}
foreach($name in @('white-sphere.agsl','gravity-lens.agsl')) {
    $cacheShaders[$name]=[IO.File]::ReadAllBytes((Join-Path $cacheApp ('app/src/main/assets/shaders/'+$name)))
}
function New-CacheStoredZip([string]$Path,$Entries,[int]$Padding) {
    $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        $archive=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$true)
        try {
            foreach($name in @($Entries.Keys)+@('budget-padding.bin')) {
                $entry=$archive.CreateEntry($name,[IO.Compression.CompressionLevel]::NoCompression)
                $entry.LastWriteTime=[DateTimeOffset]::new(2026,10,1,0,0,0,[TimeSpan]::Zero)
                $entryStream=$entry.Open()
                try {
                    if($name-eq'budget-padding.bin') {
                        $buffer=[byte[]]::new(32768);$left=$Padding
                        while($left-gt0){[Security.Cryptography.RandomNumberGenerator]::Fill($buffer);$count=[Math]::Min($left,$buffer.Length);$entryStream.Write($buffer,0,$count);$left-=$count}
                    } else {$bytes=[byte[]]$Entries[$name];$entryStream.Write($bytes,0,$bytes.Length)}
                } finally {$entryStream.Dispose()}
            }
        } finally {$archive.Dispose()}
    } finally {$stream.Dispose()}
}
function New-CacheExactZip([string]$Mode,[string]$Mount,$Entries,[int]$Size) {
    $shape=Join-Path $cacheDestination ($Mode+'-'+$Mount+'-shape.zip')
    New-CacheStoredZip $shape $Entries 0
    $overhead=[int](Get-Item -LiteralPath $shape).Length
    if($overhead-ge$Size){throw '有效资源条目已经超过目标对象大小'}
    $path=Join-Path $cacheDestination ($Mode+'-'+$Mount+'.zip')
    New-CacheStoredZip $path $Entries ($Size-$overhead)
    if((Get-Item -LiteralPath $path).Length-ne$Size){throw 'STORED资源ZIP准确大小不符'}
    [pscustomobject]@{sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant();size=[long]$Size;file=[IO.Path]::GetFileName($path)}
}
foreach($mode in @('cache-repair','budget-limit','budget-retry')) {
    $themeSize=if($mode-eq'cache-repair'){4096}else{10*1024*1024}
    $shaderSize=$themeSize+$(if($mode-eq'budget-limit'){1}else{0})
    $theme=New-CacheExactZip $mode 'theme' $cacheTheme $themeSize
    $shaders=New-CacheExactZip $mode 'shaders' $cacheShaders $shaderSize
    $manifest=$cacheRecipe.manifest|ConvertTo-Json -Depth 20|ConvertFrom-Json
    $manifest.label='实际缓存与计费预算-'+$mode
    $manifest.createdAt=[DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $manifest.artifacts+=@([pscustomobject]@{id='theme';role='resources';sha256='';size=0;requires=@('business');mount='theme'},
        [pscustomobject]@{id='shaders';role='resources';sha256='';size=0;requires=@('business');mount='shaders'})
    $recipe=[ordered]@{schema=1;manifest=$manifest;sources=[ordered]@{runtime='runtime.apk';business='business-C.apk';theme=$theme.file;shaders=$shaders.file}}
    [IO.File]::WriteAllText((Join-Path $cacheDestination ('recipe-'+$mode+'.json')),($recipe|ConvertTo-Json -Depth 20),$cacheUtf8)
    $template=[ordered]@{schema=1;mode=$mode;targetSnapshotId='';sourceIdentity='';
        expectedObjects=@([ordered]@{sha256=$theme.sha256;size=$theme.size},[ordered]@{sha256=$shaders.sha256;size=$shaders.size});
        expectedMissingBytes=$theme.size+$shaders.size;slowObjectSha=$theme.sha256;prefixBytes=$(if($mode-eq'cache-repair'){32}else{16384})}
    [IO.File]::WriteAllText((Join-Path $cacheDestination ('plan-'+$mode+'.json')),($template|ConvertTo-Json -Depth 10),$cacheUtf8)
}
Write-Output ('已准备三组公开原始资源与recipe：'+$cacheDestination)
Write-Output '未读取私钥/口令/token，未签名、网络、上传或发布；空targetSnapshotId由driver核对已签名候选后填写本run计划。'
