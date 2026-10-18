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
function Test-RunnableJar {
 if(-not (Test-Path -LiteralPath $jar)){return $false}
 $archive=[IO.Compression.ZipFile]::OpenRead($jar)
 try {
  $manifest=$archive.GetEntry('META-INF/MANIFEST.MF')
  if(-not $manifest){return $false}
  $reader=[IO.StreamReader]::new($manifest.Open())
  try {return $reader.ReadToEnd().Contains('Main-Class: org.springframework.boot.loader.launch.JarLauncher')} finally {$reader.Dispose()}
 } finally {$archive.Dispose()}
}
if($Build -and (Test-RunnableJar)) {
 $backupDir=Join-Path $runtimeRoot 'backups'
 New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
 Copy-Item -LiteralPath $jar -Destination (Join-Path $backupDir 'yudao-server.previous.jar') -Force
}
$pidPath=Join-Path $runtimeRoot 'backend.pid'
$backendIds=[Collections.Generic.HashSet[int]]::new()
if(Test-Path -LiteralPath $pidPath) {
 $taskPid=[int](Get-Content -LiteralPath $pidPath -Raw).Trim()
 $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$taskPid" -ErrorAction SilentlyContinue
 if($taskProcess) {
  if(-not ($taskProcess.Name -eq 'java.exe' -and $taskProcess.CommandLine.Contains($projectRoot) -and $taskProcess.CommandLine.Contains('yudao-server.jar'))){throw 'Recorded backend PID belongs to another process; it was not stopped.'}
  [void]$backendIds.Add($taskPid)
 }
}
# The recorded launcher may exit or be replaced; verify the actual listening process too.
foreach($listener in @(Get-NetTCPConnection -LocalPort 48080 -State Listen -ErrorAction SilentlyContinue)) {
 $actual=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
 if(-not ($actual -and $actual.Name -eq 'java.exe' -and $actual.CommandLine -and $actual.CommandLine.Contains($jar))) {throw 'Backend port is owned by another application; it has not been stopped.'}
 [void]$backendIds.Add([int]$actual.ProcessId)
}
foreach($backendId in $backendIds) {
 Stop-Process -Id $backendId
 Wait-Process -Id $backendId -Timeout 20 -ErrorAction SilentlyContinue
}
for($attempt=0;$attempt -lt 20;$attempt++) {
 if(@(Get-NetTCPConnection -LocalPort 48080 -State Listen -ErrorAction SilentlyContinue).Count -eq 0){break}
 Start-Sleep -Milliseconds 250
}
if(@(Get-NetTCPConnection -LocalPort 48080 -State Listen -ErrorAction SilentlyContinue).Count -gt 0){throw 'Backend port is still occupied; no build was attempted.'}
Set-Location -LiteralPath $projectRoot
if($Build) {
 & $maven -s (Join-Path $PSScriptRoot 'maven-local-settings.xml') -B -ntp -f backend/pom.xml -pl yudao-server -am package -DskipTests
 if($LASTEXITCODE -ne 0){throw 'Backend build failed; the previous JAR is retained in .runtime/backups. Database services were not changed.'}
}
if(-not (Test-RunnableJar)){throw 'Runnable backend JAR missing; rebuild with -Build or restore the saved previous JAR.'}
$backend=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -ArgumentList @('-Xms128m','-Xmx1536m','-Dfile.encoding=UTF-8','-jar',$jar,'--spring.profiles.active=meslocal',('--spring.config.additional-location=file:'+$config.Replace('\','/'))) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'backend.stdout.log') -RedirectStandardError (Join-Path $logRoot 'backend.stderr.log')
Set-Content -LiteralPath $pidPath -Value $backend.Id
for($attempt=0;$attempt -lt 120;$attempt++) {
 try {
  $response=Invoke-RestMethod -Uri 'http://127.0.0.1:48080/admin-api/system/tenant/get-by-website?website=127.0.0.1:5173' -TimeoutSec 2
  if($response.code -eq 0){
   $listener=Get-NetTCPConnection -LocalPort 48080 -State Listen -ErrorAction Stop | Select-Object -First 1
   $actual=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction Stop
   if(-not ($actual.Name -eq 'java.exe' -and $actual.CommandLine.Contains($jar))){throw 'Unexpected backend listener after restart.'}
   Set-Content -LiteralPath $pidPath -Value $actual.ProcessId
   Write-Host 'Backend ready; existing MySQL, Redis, frontend and face services were retained.';exit 0
  }
 } catch {}
 if($backend.HasExited){throw 'Backend exited; check .runtime/logs/backend.stdout.log.'}
 Start-Sleep -Milliseconds 500
}
throw 'Backend health check timed out; check its log.'
