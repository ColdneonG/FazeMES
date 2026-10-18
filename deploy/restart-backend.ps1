# Rebuild/restart only the managed application. Never initializes or stops MySQL/Redis.
[CmdletBinding()]
param([switch]$Build)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$runtimeRoot=Join-Path $projectRoot '.runtime'
$logRoot=Join-Path $runtimeRoot 'logs'
$env:JAVA_HOME='C:\Users\mm\.jdks\ms-17.0.19'
$maven='C:\Users\mm\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'
$env:PATH="$env:JAVA_HOME\bin;C:\Program Files\nodejs;$env:PATH"
foreach($servicePort in @(3308,6381)) {
 $probe=[Net.Sockets.TcpClient]::new()
 try {if(-not ($probe.ConnectAsync('127.0.0.1',$servicePort).Wait(500) -and $probe.Connected)){throw "Existing service unavailable on $servicePort; no database initialization was attempted."}} finally {$probe.Dispose()}
}
$jar=Join-Path $projectRoot 'backend\yudao-server\target\yudao-server.jar'
$config=Join-Path $runtimeRoot 'application-meslocal.yaml'
if(-not (Test-Path -LiteralPath $config)){throw 'Existing local configuration required.'}
if($Build -and (Test-Path -LiteralPath $jar)) {
 $backupDir=Join-Path $runtimeRoot 'backups'
 New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
 Copy-Item -LiteralPath $jar -Destination (Join-Path $backupDir 'yudao-server.previous.jar') -Force
}
$pidPath=Join-Path $runtimeRoot 'backend.pid'
if(Test-Path -LiteralPath $pidPath) {
 $taskPid=[int](Get-Content -LiteralPath $pidPath -Raw).Trim()
 $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$taskPid" -ErrorAction SilentlyContinue
 if($taskProcess) {
  if(-not ($taskProcess.Name -eq 'java.exe' -and $taskProcess.CommandLine.Contains($projectRoot) -and $taskProcess.CommandLine.Contains('yudao-server.jar'))){throw 'Recorded backend PID belongs to another process; it was not stopped.'}
  Stop-Process -Id $taskPid
  Wait-Process -Id $taskPid -Timeout 20 -ErrorAction SilentlyContinue
 }
}
Set-Location -LiteralPath $projectRoot
if($Build) {
 & $maven -s (Join-Path $PSScriptRoot 'maven-local-settings.xml') -B -ntp -f backend/pom.xml -pl yudao-server -am package -DskipTests
 if($LASTEXITCODE -ne 0){throw 'Backend build failed; the previous JAR is retained in .runtime/backups. Database services were not changed.'}
}
if(-not (Test-Path -LiteralPath $jar)){throw 'Backend JAR not found.'}
$backend=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -ArgumentList @('-Xms128m','-Xmx1536m','-Dfile.encoding=UTF-8','-jar',$jar,'--spring.profiles.active=meslocal',('--spring.config.additional-location=file:'+$config.Replace('\','/'))) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'backend.stdout.log') -RedirectStandardError (Join-Path $logRoot 'backend.stderr.log')
Set-Content -LiteralPath $pidPath -Value $backend.Id
for($attempt=0;$attempt -lt 120;$attempt++) {
 try {
  $response=Invoke-RestMethod -Uri 'http://127.0.0.1:48080/admin-api/system/tenant/get-by-website?website=127.0.0.1:5173' -TimeoutSec 2
  if($response.code -eq 0){Write-Host 'Backend ready; existing MySQL, Redis, frontend and face services were retained.';exit 0}
 } catch {}
 if($backend.HasExited){throw 'Backend exited; check .runtime/logs/backend.stdout.log.'}
 Start-Sleep -Milliseconds 500
}
throw 'Backend health check timed out; check its log.'
