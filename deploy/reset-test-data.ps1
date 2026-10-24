#requires -Version 7.0
<#!
.SYNOPSIS
Replaces all local MES business records with the comprehensive synthetic test suite.
.DESCRIPTION
The dedicated .runtime MySQL database is backed up before deleting records. Keeps
schema/migration history, administrator password, dictionaries, menus and local
infrastructure configuration. All old MES business data and non-admin users are
replaced. The managed backend is stopped before reset and restarted before API
fixture generation. A failure leaves a concrete backup path and diagnostic state.
The first original dump is permanent. Later dumps are gzip-compressed, and only
the latest three are kept after successful validation. The double-click launcher
defers retention until its final service readiness checks have also succeeded.
#>
[CmdletBinding()]
param([switch]$VerifyOnly,[switch]$DeferBackupRetention,[switch]$FinalizeBackups)
$ErrorActionPreference = 'Stop'
if (($VerifyOnly -and ($DeferBackupRetention -or $FinalizeBackups)) -or ($DeferBackupRetention -and $FinalizeBackups)) { throw 'VerifyOnly, DeferBackupRetention and FinalizeBackups cannot be combined.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot '.runtime'
$python = (Get-Command python.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$script = Join-Path $projectRoot 'database\tools\reset_test_data.py'
if ($VerifyOnly) {
    & $python -X utf8 $script verify
    if ($LASTEXITCODE -ne 0) { throw 'Test data validation failed; inspect .runtime/testdata-verification.json.' }
    return
}
& $python -c 'import pymysql, bcrypt'
if ($LASTEXITCODE -ne 0) { throw 'Install deploy/requirements.txt before generating test data.' }
foreach ($module in @('testdata_backups.py','testdata_inventory.py','testdata_supporting.py','testdata_manufacturing.py','testdata_collaboration.py','testdata_production_quality.py','testdata_warehouse_ops.py','verify_test_data_api.py')) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot "database\tools\$module"))) { throw "Missing fixture module $module" }
}
$lockName = 'Local\FazeMES-TestData-' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($projectRoot))).Substring(0,16)
$resetLock = [Threading.Mutex]::new($false,$lockName)
$startupLockName = 'Local\FazeMES-' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($projectRoot))).Substring(0,16)
$startupLock = [Threading.Mutex]::new($false,$startupLockName)
$startupHeld = $false
$held = $false
try {
    try { $startupHeld = $startupLock.WaitOne(0) } catch [Threading.AbandonedMutexException] { $startupHeld = $true }
    if (-not $startupHeld) { throw 'FazeMES is already starting or resetting in another window.' }
    try { $held = $resetLock.WaitOne(0) } catch [Threading.AbandonedMutexException] { $held = $true }
    if (-not $held) { throw 'Another test-data reset is already running.' }
    if ($FinalizeBackups) {
        & $python -X utf8 $script retain-backups
        if ($LASTEXITCODE -ne 0) { throw 'Backup retention failed; project data is unchanged. Check the error above.' }
        return
    }
    # Inspect actual listeners, including stale recorded launcher PID scenarios.
    $jar = Join-Path $projectRoot 'backend\yudao-server\target\yudao-server.jar'
    $owned = [Collections.Generic.HashSet[int]]::new()
    foreach ($listener in @(Get-NetTCPConnection -LocalPort 48080 -State Listen -ErrorAction SilentlyContinue)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction Stop
        if (-not ($process.Name -eq 'java.exe' -and $process.CommandLine -and $process.CommandLine.Contains($jar))) {
            throw 'Backend port belongs to an unrelated process; nothing was stopped.'
        }
        [void]$owned.Add([int]$process.ProcessId)
    }
    # Cache invalidation later uses only this project's Redis DB0.
    foreach ($listener in @(Get-NetTCPConnection -LocalPort 6381 -State Listen -ErrorAction Stop)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction Stop
        if (-not ($process.CommandLine -and $process.CommandLine.Contains($runtimeRoot))) {
            throw 'Redis ownership does not match this project.'
        }
    }
    foreach ($backendId in $owned) {
        Stop-Process -Id $backendId
        Wait-Process -Id $backendId -Timeout 20 -ErrorAction SilentlyContinue
    }
    & $python -X utf8 $script reset
    if ($LASTEXITCODE -ne 0) { throw 'Database fixture reset failed. Backend remains stopped; inspect state and the backup before recovery.' }
    & (Join-Path $PSScriptRoot 'restart-backend.ps1')
    & $python -X utf8 $script api
    if ($LASTEXITCODE -ne 0) { throw 'Business fixture creation or verification failed. Inspect .runtime/testdata-state.json and testdata-verification.json.' }
    if (-not $DeferBackupRetention) {
        & $python -X utf8 $script retain-backups
        if ($LASTEXITCODE -ne 0) { throw 'Data is ready, but backup retention failed. Check the error above.' }
    }
    Write-Host 'Comprehensive test data ready. Report: .runtime/testdata-verification.json'
} finally {
    if ($held) { $resetLock.ReleaseMutex() }
    $resetLock.Dispose()
    if ($startupHeld) { $startupLock.ReleaseMutex() }
    $startupLock.Dispose()
}
