# Rebuild/restart only the managed application. Never initializes or stops MySQL/Redis.
#requires -Version 7.0
[CmdletBinding()]
param([switch]$Build)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$runtimeRoot=Join-Path $projectRoot '.runtime'
$logRoot=Join-Path $runtimeRoot 'logs'
. (Join-Path $PSScriptRoot 'local-tools.ps1')
$javaExe=Resolve-LocalTool -Name java.exe -HomeVariable JAVA_HOME -HomeRelativePath 'bin\java.exe'
if($Build) { $null=Resolve-LocalTool -Name mvn.cmd }
foreach($servicePort in @(3308,6381)) {
 $probe=[Net.Sockets.TcpClient]::new()
 try {if(-not ($probe.ConnectAsync('127.0.0.1',$servicePort).Wait(500) -and $probe.Connected)){throw "Existing service unavailable on $servicePort; no database initialization was attempted."}} finally {$probe.Dispose()}
}
$jar=Join-Path $projectRoot 'backend\yudao-server\target\yudao-server.jar'
$config=Join-Path $runtimeRoot 'application-meslocal.yaml'
if(-not (Test-Path -LiteralPath $config)){throw 'Existing local configuration required.'}
# Validate tools/configuration before interrupting services.
if(-not $Build -and -not (Test-LocalBackendJar -JarPath $jar)){throw 'Runnable backend JAR missing; rebuild with -Build or restore the saved previous JAR.'}
if($Build) { Backup-LocalBackend -ProjectRoot $projectRoot }
$pidPath=Join-Path $runtimeRoot 'backend.pid'
$backendIds=[Collections.Generic.HashSet[int]]::new()
if(Test-Path -LiteralPath $pidPath) {
 $taskPid=[int](Get-Content -LiteralPath $pidPath -Raw).Trim()
 $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$taskPid" -ErrorAction SilentlyContinue
 if($taskProcess) {
  if(-not ($taskProcess.Name -eq 'java.exe' -and $taskProcess.CommandLine -and $taskProcess.CommandLine.Contains($jar))){throw 'Recorded backend PID belongs to another process; it was not stopped.'}
  [void]$backendIds.Add($taskPid)
 }
}
# The recorded launcher may exit or be replaced; verify the actual listening process too.
foreach($listener in @(Get-LocalPortOwner 48080)) {
 $actual=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
 if(-not ($actual -and $actual.Name -eq 'java.exe' -and $actual.CommandLine -and $actual.CommandLine.Contains($jar))) {throw 'Backend port is owned by another application; it has not been stopped.'}
 [void]$backendIds.Add([int]$actual.ProcessId)
}
foreach($backendId in $backendIds) {
 Stop-Process -Id $backendId
 Wait-Process -Id $backendId -Timeout 20 -ErrorAction SilentlyContinue
}
for($attempt=0;$attempt -lt 20;$attempt++) {
 if(@(Get-LocalPortOwner 48080).Count -eq 0){break}
 Start-Sleep -Milliseconds 250
}
if(@(Get-LocalPortOwner 48080).Count -gt 0){throw 'Backend port is still occupied; no new application was started.'}
# Old application code must not write new unguarded returns while quality holds
# and source-evidence migrations inspect historical rows.
try { & (Join-Path $PSScriptRoot 'apply-migrations.ps1') }
catch { throw "Backend remains stopped. Resolve the migration failure before restarting; the previous application must not write across a partial schema upgrade. $($_.Exception.Message)" }
# Windows may lock a running Spring Boot JAR. Build only after its owner has exited.
if($Build) {
 try { Build-LocalBackend -ProjectRoot $projectRoot }
 catch { throw "Backend remains stopped. Fix the build and rerun with -Build, or restore .runtime/backups/yudao-server.previous.jar if available. $($_.Exception.Message)" }
}
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$backendArgs=@('-Xms128m','-Xmx1536m','-Dfile.encoding=UTF-8','-jar',$jar,'--spring.profiles.active=meslocal',('--spring.config.additional-location=file:'+$config.Replace('\','/'))) | ForEach-Object { if($_ -match '\s'){'"'+$_+'"'}else{$_} }
$backend=Start-Process -FilePath $javaExe -ArgumentList $backendArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'backend.stdout.log') -RedirectStandardError (Join-Path $logRoot 'backend.stderr.log')
Set-Content -LiteralPath $pidPath -Value $backend.Id
for($attempt=0;$attempt -lt 120;$attempt++) {
 try {
  $response=Invoke-RestMethod -Uri 'http://127.0.0.1:48080/admin-api/system/tenant/get-by-website?website=127.0.0.1:5173' -TimeoutSec 2
  if($response.code -eq 0){
   $listener=Get-LocalPortOwner 48080 | Select-Object -First 1
   $actual=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction Stop
   if(-not ($actual.Name -eq 'java.exe' -and $actual.CommandLine.Contains($jar))){throw 'Unexpected backend listener after restart.'}
   Set-Content -LiteralPath $pidPath -Value $actual.ProcessId
   Write-Host 'Backend ready; existing MySQL, Redis, frontend and face services were retained.';return
  }
 } catch {}
 if($backend.HasExited){throw 'Backend exited; check .runtime/logs/backend.stdout.log.'}
 Start-Sleep -Milliseconds 500
}
throw 'Backend health check timed out; check its log.'
