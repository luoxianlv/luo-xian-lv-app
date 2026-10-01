#Requires -Version 7.0
param([string]$KotlinCompiler = 'C:/kotlin/bin/kotlinc.bat')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$taskRoot = Join-Path $repoRoot ('.local/playback-preparation-checks-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($taskRoot)
$utf8 = [Text.UTF8Encoding]::new($false)
$actual = [IO.File]::ReadAllText((Join-Path $repoRoot 'app/src/main/java/app/luoxianlv/business/playback/PlaybackSession.kt'))
$begin = $actual.IndexOf('    var playing = false')
$end = $actual.IndexOf('    var error: String? = null', $begin)
if($begin -lt 0 -or $end -le $begin) { throw '无法定位实际PlaybackSession边沿通知源码' }
$fixture = @'
package app.luoxianlv.business.playback
import android.os.Looper
class ActualPlaybackEdgeFixture(usage: (Boolean) -> Unit) {
    private var active = true
    private var closed = false
    private lateinit var binding: Binding
    private class Binding(val callback: (Boolean) -> Unit) {
        fun usage(value: Boolean) = callback(value)
    }
    init { binding = Binding(usage) }
'@
$fixture += "`n" + $actual.Substring($begin, $end-$begin) + @'
    fun prepare(value: Boolean) { preparing = value }
    fun play(value: Boolean) { playing = value }
}
'@
$fixtureFile = Join-Path $taskRoot 'ActualPlaybackEdgeFixture.kt'
[IO.File]::WriteAllText($fixtureFile, $fixture, $utf8)
$looperFile = Join-Path $taskRoot 'Looper.kt'
[IO.File]::WriteAllText($looperFile, @'
package android.os
object Looper {
    private val main = Any()
    @JvmStatic fun getMainLooper(): Any = main
    @JvmStatic fun myLooper(): Any = main
}
'@, $utf8)
$profilePath = [Environment]::GetFolderPath('UserProfile')
$cache = Join-Path $profilePath '.gradle/caches/modules-2/files-2.1'
$junit = (Get-ChildItem -LiteralPath (Join-Path $cache 'junit/junit/4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$hamcrest = (Get-ChildItem -LiteralPath (Join-Path $cache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$jar = Join-Path $taskRoot 'preparation-checks.jar'
$arguments = @($fixtureFile,$looperFile,
    (Join-Path $repoRoot 'app/src/main/java/app/luoxianlv/core/playback/SongLoadGate.kt'),
    (Join-Path $repoRoot 'app/src/test/java/app/luoxianlv/SongLoadGateTest.kt'),
    (Join-Path $repoRoot 'tools/test-support/PlaybackPreparationChecks.kt'),
    '-jvm-target','17','-classpath',"$junit;$hamcrest",'-include-runtime','-d',$jar)
$argumentFile = Join-Path $taskRoot 'compiler.args'
[IO.File]::WriteAllLines($argumentFile,($arguments | ForEach-Object { '"'+$_.Replace('\','/')+'"' }),$utf8)
& $KotlinCompiler "@$argumentFile"
if($LASTEXITCODE -ne 0) { throw '实际Kotlin边沿源码编译失败' }
& java -cp "$jar;$junit;$hamcrest" org.junit.runner.JUnitCore app.luoxianlv.SongLoadGateTest app.luoxianlv.PlaybackPreparationChecks
if($LASTEXITCODE -ne 0) { throw '实际Kotlin准备边沿检查失败' }
Write-Output "独立检查目录：$taskRoot"
