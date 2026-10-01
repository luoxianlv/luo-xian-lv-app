#Requires -Version 7.0
[CmdletBinding()]
param([string]$AppRoot = (Split-Path -Parent $PSScriptRoot), [string]$KotlinCompiler = 'C:/kotlin/bin/kotlinc.bat')
$ErrorActionPreference = 'Stop'
$rendererRoot = (Resolve-Path -LiteralPath $AppRoot).Path
$rendererScratch = Join-Path $rendererRoot ('.local/renderer-budget-checks-' + [guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($rendererScratch)
$rendererSource = [System.IO.File]::ReadAllText((Join-Path $rendererRoot 'app/src/main/java/app/luoxianlv/business/OfficialRendererGate.kt'))
$rendererStart = $rendererSource.IndexOf('internal class RendererValidationBudget(')
$rendererEnd = $rendererSource.IndexOf('// RENDERER_BUDGET_END')
if ($rendererStart -lt 0 -or $rendererEnd -le $rendererStart) { throw 'Actual renderer budget implementation not found.' }
$rendererBudgetFile = Join-Path $rendererScratch 'RendererValidationBudget.kt'
[System.IO.File]::WriteAllText($rendererBudgetFile, $rendererSource.Substring($rendererStart, $rendererEnd - $rendererStart), [System.Text.UTF8Encoding]::new($false))
& $KotlinCompiler $rendererBudgetFile (Join-Path $rendererRoot 'tools/test-support/RendererValidationBudgetChecks.kt') -include-runtime -d (Join-Path $rendererScratch 'budget-checks.jar')
if ($LASTEXITCODE -ne 0) { throw 'Actual renderer budget failed to compile.' }
& java -cp (Join-Path $rendererScratch 'budget-checks.jar') RendererValidationBudgetChecksKt
if ($LASTEXITCODE -ne 0) { throw 'Actual renderer budget checks failed.' }
Write-Output ('Independent budget fixture retained: ' + $rendererScratch)
