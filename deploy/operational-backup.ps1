#requires -Version 7.2
<#!
.SYNOPSIS
Run or verify local database backups; prepare/install an unattended Windows task.
.DESCRIPTION
No restore, initialization or service startup is performed. Installing/removing
the SYSTEM task is explicit and requires an elevated PowerShell. Task arguments
contain paths only; database credentials stay in mysql-admin.local.ini.
#>
[CmdletBinding()]
param([ValidateSet('Run','Verify','Status','TaskPlan','InstallTask','RemoveTask')][string]$Action='Run')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot '.runtime'
. (Join-Path $PSScriptRoot 'local-tools.ps1')
$python = Resolve-LocalTool -Name python.exe
$script = Join-Path $projectRoot 'database\tools\operational_backups.py'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$dump = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$taskName = 'FazeMES-Backup-' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($projectRoot))).Substring(0,16)
if ($Action -eq 'Status') {
    $status = Join-Path $runtimeRoot 'operational-backup-status.json'
    if (Test-Path -LiteralPath $status) { Get-Content -LiteralPath $status -Raw }
    else { Write-Host 'No completed operational backup check has been recorded.' }
    return
}
if ($Action -in @('Run','Verify')) {
    & $python -X utf8 $script --project-root $projectRoot --mysql-client $mysql --mysqldump $dump --mode $(if($Action -eq 'Run'){'due'}else{'verify'})
    if ($LASTEXITCODE -ne 0) { throw 'Operational backup failed. Inspect .runtime/operational-backup-status.json.' }
    return
}
function Xml-Escape([string]$Value) { [Security.SecurityElement]::Escape($Value) }
$shanghai = [TimeZoneInfo]::FindSystemTimeZoneById('China Standard Time')
$localNow = [TimeZoneInfo]::ConvertTime([DateTimeOffset]::UtcNow,$shanghai)
$boundary = $localNow.Date.AddDays(1).ToString('yyyy-MM-dd') + 'T02:00:00+08:00'
$arguments = '-X utf8 "' + $script + '" --project-root "' + $projectRoot + '" --mysql-client "' + $mysql + '" --mysqldump "' + $dump + '" --mode due'
$xml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>FazeMES local full database backup at Asia/Shanghai 02:00; 30-day retention.</Description></RegistrationInfo>
  <Triggers><CalendarTrigger><StartBoundary>$boundary</StartBoundary><Enabled>true</Enabled><ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay></CalendarTrigger></Triggers>
  <Principals><Principal id="Backup"><UserId>S-1-5-18</UserId><LogonType>ServiceAccount</LogonType><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
  <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><StartWhenAvailable>true</StartWhenAvailable><WakeToRun>true</WakeToRun><ExecutionTimeLimit>PT30M</ExecutionTimeLimit><RestartOnFailure><Interval>PT15M</Interval><Count>3</Count></RestartOnFailure></Settings>
  <Actions Context="Backup"><Exec><Command>$(Xml-Escape $python)</Command><Arguments>$(Xml-Escape $arguments)</Arguments><WorkingDirectory>$(Xml-Escape $projectRoot)</WorkingDirectory></Exec></Actions>
</Task>
"@
if ($Action -eq 'TaskPlan') { $xml; return }
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'InstallTask/RemoveTask requires an elevated PowerShell; no task was changed.' }
if ($Action -eq 'RemoveTask') {
    $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($existing) {
        if ($existing.Actions.Execute -ne $python -or -not $existing.Actions.Arguments.Contains($script) -or -not $existing.Actions.Arguments.Contains($projectRoot)) { throw 'Task ownership does not match this project; no task was removed.' }
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    }
    return
}
foreach ($required in @($python,$script,$mysql,$dump,(Join-Path $runtimeRoot 'mysql-admin.local.ini'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw 'Operational backup prerequisite is missing; no task was installed.' }
}
$backupRoot = Join-Path $runtimeRoot 'backups\operations'
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
Protect-LocalBackupPath -Path $backupRoot
Protect-LocalBackupPath -Path (Join-Path $runtimeRoot 'mysql-admin.local.ini')
$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existing -and ($existing.Actions.Execute -ne $python -or -not $existing.Actions.Arguments.Contains($script) -or -not $existing.Actions.Arguments.Contains($projectRoot))) { throw 'Task ownership does not match this project; no task was replaced.' }
Register-ScheduledTask -TaskName $taskName -Xml $xml -Force | Out-Null
Write-Host "Installed $taskName; it runs as SYSTEM without an interactive logon. MySQL must already be running."
