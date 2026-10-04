param()
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$originalLocation = Get-Location
try {
    Set-Location -LiteralPath $repoRoot
    # 复用离线平台替身、真实源码编译和既有调度回归；此工具不运行 Gradle 或设备。
    . (Join-Path $PSScriptRoot 'test-host-update-scheduler.ps1')
    Write-Host '实际 Bootstrap/HostUpdates 双线程转交、真实许可/日志、Prepared资源关闭与稳定故障恢复。'
    Run-Checks 'stable-recovery-handoff' ($platform + @(
        $schedule,
        $hostSource,
        (Join-Path $repoRoot 'modules/app-host/src/main/java/app/luoxianlv/host/Bootstrap.java'),
        (Join-Path $repoRoot 'modules/hot-core/src/main/java/app/luoxianlv/hot/ActivationController.java'),
        (Join-Path $repoRoot 'modules/hot-core/src/main/java/app/luoxianlv/hot/ActivationJournal.java'),
        (Join-Path $repoRoot 'tools/test-support/HostStableRecoveryHandoffTest.java')
    )) @('app.luoxianlv.host.HostStableRecoveryHandoffTest')
    Write-Host "恢复交错检查目录：$runRoot"
} finally {
    Set-Location -LiteralPath $originalLocation
}
