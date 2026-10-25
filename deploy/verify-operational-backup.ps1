#requires -Version 7.2
# Private processes and temporary files only; never registers a task or opens MySQL.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-tools.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Resolve-LocalTool -Name python.exe
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('FazeMES backup checks 空格 ' + [Guid]::NewGuid().ToString('N'))
$runtime = Join-Path $fixtureRoot '.runtime'
$checks = 0
function Assert-Backup([bool]$Condition,[string]$Message) {
    if (-not $Condition) { throw "Backup check failed: $Message" }
    $script:checks++
}
function Assert-BackupThrows([scriptblock]$Action,[string]$Expected) {
    try { & $Action } catch {
        if ($_.Exception.Message -like "*$Expected*") { $script:checks++; return }
        throw
    }
    throw "Expected failure: $Expected"
}
try {
    foreach ($directory in @($runtime,(Join-Path $runtime 'logs'),(Join-Path $fixtureRoot 'database\tools'))) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    '[client]' | Set-Content -LiteralPath (Join-Path $runtime 'mysql-admin.local.ini')
    # This worker only publishes a launch nonce and waits for its private stop file.
    @'
import argparse, json, os, pathlib, time
p=argparse.ArgumentParser()
p.add_argument('--project-root'); p.add_argument('--instance-id')
a,_=p.parse_known_args()
r=pathlib.Path(a.project_root)/'.runtime'
(r/'backup-scheduler-ready.json').write_text(json.dumps(dict(pid=os.getpid(),instanceId=a.instance_id)))
while not (r/'backup.stop').exists(): time.sleep(.05)
'@ | Set-Content -LiteralPath (Join-Path $fixtureRoot 'database\tools\operational_backups.py') -Encoding utf8
    & {
        # The real worker does not execute either executable in this test.
        function Test-Path {
            [CmdletBinding()]
            param([string[]]$LiteralPath,[string[]]$Path,[string]$PathType)
            if ($LiteralPath.Count -eq 1 -and $LiteralPath[0] -match '^C:\\Program Files\\MySQL\\MySQL Server 8\.0\\bin\\(mysql|mysqldump)\.exe$') { return $true }
            Microsoft.PowerShell.Management\Test-Path @PSBoundParameters
        }
        Start-LocalBackupScheduler -ProjectRoot $fixtureRoot
        $first = [int](Get-Content -LiteralPath (Join-Path $runtime 'backup.pid') -Raw)
        Start-LocalBackupScheduler -ProjectRoot $fixtureRoot
        $second = [int](Get-Content -LiteralPath (Join-Path $runtime 'backup.pid') -Raw)
        Assert-Backup ($first -eq $second) 'Repeated startup must reuse the owned backup worker.'
        Assert-Backup ($null -ne (Get-Process -Id $first -ErrorAction SilentlyContinue)) 'Worker must be alive after readiness.'
        Stop-LocalBackupScheduler -ProjectRoot $fixtureRoot
        Assert-Backup ($null -eq (Get-Process -Id $first -ErrorAction SilentlyContinue)) 'Graceful stop must finish the worker before database shutdown.'
        Assert-Backup (-not (Test-Path -LiteralPath (Join-Path $runtime 'backup.pid'))) 'Stopped worker PID must be removed.'
        Set-Content -LiteralPath (Join-Path $runtime 'backup.pid') -Value $PID
        Assert-BackupThrows { Stop-LocalBackupScheduler -ProjectRoot $fixtureRoot } 'belongs to another process'
        Assert-Backup ($null -ne (Get-Process -Id $PID -ErrorAction SilentlyContinue)) 'Unrelated PID must survive.'
        Assert-BackupThrows { Start-LocalBackupScheduler -ProjectRoot $fixtureRoot } 'belongs to another process'
        Remove-Item -LiteralPath (Join-Path $runtime 'backup.pid')
    }
    $lease = Enter-LocalMaintenanceLock -ProjectRoot $fixtureRoot
    try {
        $nested = Enter-LocalMaintenanceLock -ProjectRoot $fixtureRoot
        Exit-LocalMaintenanceLock -Lease $nested
        $probe = Join-Path $fixtureRoot 'probe.py'
        @'
import pathlib,sys
sys.path.insert(0,sys.argv[1])
from operational_backups import file_lock, BackupError
try:
    with file_lock(pathlib.Path(sys.argv[2])): print('ACQUIRED')
except BackupError: print('BLOCKED')
'@ | Set-Content -LiteralPath $probe -Encoding utf8
        $lockFile = Join-Path $runtime 'maintenance.lock'
        $blocked = & $python -X utf8 $probe (Join-Path $projectRoot 'database\tools') $lockFile
        Assert-Backup ($blocked -eq 'BLOCKED') 'PowerShell maintenance lock must exclude Python even after nested lease release.'
    } finally { Exit-LocalMaintenanceLock -Lease $lease }
    $released = & $python -X utf8 $probe (Join-Path $projectRoot 'database\tools') $lockFile
    Assert-Backup ($released -eq 'ACQUIRED') 'Final lease release must allow the backup worker.'
    [xml]$plan = & (Join-Path $PSScriptRoot 'operational-backup.ps1') -Action TaskPlan
    $namespace = [Xml.XmlNamespaceManager]::new($plan.NameTable)
    $namespace.AddNamespace('t','http://schemas.microsoft.com/windows/2004/02/mit/task')
    Assert-Backup ($plan.SelectSingleNode('//t:UserId',$namespace).InnerText -eq 'S-1-5-18') 'Unattended task must run as SYSTEM.'
    Assert-Backup ($plan.SelectSingleNode('//t:StartBoundary',$namespace).InnerText.EndsWith('T02:00:00+08:00')) 'Task trigger must use Shanghai time, independent of machine timezone.'
    Assert-Backup ($plan.SelectSingleNode('//t:Command',$namespace).InnerText -eq $python) 'Task must pin its absolute Python executable.'
    Assert-Backup ($plan.SelectSingleNode('//t:WorkingDirectory',$namespace).InnerText -eq $projectRoot) 'Task must pin its project working directory.'
    $taskArgs = $plan.SelectSingleNode('//t:Arguments',$namespace).InnerText
    Assert-Backup ($taskArgs.Contains('--mode due') -and $taskArgs.Contains($projectRoot)) 'Task must target this project and the idempotent due action.'
    Assert-Backup ($taskArgs -notmatch '(?i)password|DbPassword|accessToken') 'Task arguments must not contain credentials.'
    Write-Host "Operational backup checks passed: $checks checks; no database or scheduled task was changed."
} finally {
    if (Test-Path -LiteralPath (Join-Path $runtime 'backup.pid')) {
        $recorded = [int](Get-Content -LiteralPath (Join-Path $runtime 'backup.pid') -Raw)
        if ($recorded -ne $PID) {
            Set-Content -LiteralPath (Join-Path $runtime 'backup.stop') -Value 'stop'
            Wait-Process -Id $recorded -Timeout 5 -ErrorAction SilentlyContinue
        }
    }
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $expected = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($expected,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notlike 'FazeMES backup checks *') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
