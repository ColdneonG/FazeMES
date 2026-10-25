#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot '.runtime'
. (Join-Path $PSScriptRoot 'local-tools.ps1')
# Gracefully stop any in-flight dump before shutting down its MySQL connection.
Stop-LocalBackupScheduler -ProjectRoot $projectRoot
foreach ($name in @('frontend','backend','face-auth','redis','mysql')) {
    $pidPath = Join-Path $runtimeRoot "$name.pid"
    if (-not (Test-Path -LiteralPath $pidPath)) { continue }
    $taskPid = [int](Get-Content -LiteralPath $pidPath -Raw).Trim()
    $taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$taskPid" -ErrorAction SilentlyContinue
    if ($taskProcess -and $taskProcess.CommandLine.Contains($projectRoot)) {
        if ($name -eq 'mysql') {
            $mysqlAdmin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqladmin.exe'
            & $mysqlAdmin "--defaults-extra-file=$(Join-Path $runtimeRoot 'mysql-admin.local.ini')" shutdown
            if ($LASTEXITCODE -ne 0) { throw 'Local MySQL shutdown failed; check its log.' }
        } else { Stop-Process -Id $taskPid }
        Write-Host "Stopped $name ($taskPid)"
    } elseif ($taskProcess) { throw "PID $taskPid no longer belongs to this project; it has not been stopped." }
    Remove-Item -LiteralPath $pidPath
}
Write-Host 'Project services stopped. The system MySQL service is unchanged.'
