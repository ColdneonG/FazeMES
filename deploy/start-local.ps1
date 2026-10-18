#requires -Version 7.0
[CmdletBinding()]
param([switch]$Build,[switch]$InitializeDatabase)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot '.runtime'
$logRoot = Join-Path $runtimeRoot 'logs'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
function Test-Port([int]$Port) {
    $socket = [Net.Sockets.TcpClient]::new()
    try { return $socket.ConnectAsync('127.0.0.1',$Port).Wait(300) -and $socket.Connected }
    catch { return $false } finally { $socket.Dispose() }
}
if ($InitializeDatabase -or -not (Test-Path -LiteralPath (Join-Path $runtimeRoot 'application-meslocal.yaml')) -or -not (Test-Port 3308)) {
    & (Join-Path $PSScriptRoot 'init-local.ps1')
}
& (Join-Path $PSScriptRoot 'apply-migrations.ps1')
function Start-Managed([string]$Name,[int]$Port,[string]$Executable,[string[]]$ProcessArgs,[string]$Directory) {
    $pidPath = Join-Path $runtimeRoot "$Name.pid"
    if (Test-Port $Port) {
        if (Test-Path -LiteralPath $pidPath) {
            $taskPid = [int](Get-Content -LiteralPath $pidPath -Raw).Trim()
            $taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$taskPid" -ErrorAction SilentlyContinue
            if ($taskProcess -and $taskProcess.CommandLine.Contains($projectRoot)) { Write-Host "$Name already running on $Port"; return }
        }
        throw "Port $Port is occupied by an unrelated process. It has not been stopped."
    }
    $taskProcess = Start-Process -FilePath $Executable -ArgumentList $ProcessArgs -WorkingDirectory $Directory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot "$Name.stdout.log") -RedirectStandardError (Join-Path $logRoot "$Name.stderr.log")
    Set-Content -LiteralPath $pidPath -Value $taskProcess.Id
    Write-Host "Started $Name (PID $($taskProcess.Id)); see $logRoot"
}
if ($Build) {
    Push-Location (Join-Path $projectRoot 'backend')
    try { & mvn.cmd -B -ntp -pl yudao-server -am package -DskipTests; if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' } } finally { Pop-Location }
}
$redisBin = Join-Path $runtimeRoot 'redis-bin'
if (-not (Test-Path -LiteralPath (Join-Path $redisBin 'redis-server.exe'))) {
    $redisArchive = Join-Path $runtimeRoot 'Redis-x64-5.0.14.1.zip'
    Invoke-WebRequest -Uri 'https://github.com/tporadowski/redis/releases/download/v5.0.14.1/Redis-x64-5.0.14.1.zip' -OutFile $redisArchive
    if ((Get-FileHash -LiteralPath $redisArchive -Algorithm SHA256).Hash -ne '018EA18A35876383CBB5F4CD0258ADFC87747CF9D619BCE1CF73A2E36F720CCF') { throw 'Redis archive checksum mismatch.' }
    Expand-Archive -LiteralPath $redisArchive -DestinationPath $redisBin -Force
}
Start-Managed 'redis' 6381 (Join-Path $redisBin 'redis-server.exe') @((Join-Path $runtimeRoot 'redis.local.conf')) $runtimeRoot
$faceRuntime = Join-Path $runtimeRoot 'face-auth'
$facePython = Join-Path $faceRuntime 'venv\Scripts\python.exe'
$faceTokenFile = Join-Path $faceRuntime 'service.key'
if (-not (Test-Path -LiteralPath $facePython) -or -not (Test-Path -LiteralPath $faceTokenFile) -or -not (Test-Path -LiteralPath (Join-Path $faceRuntime 'models\face_detection_yunet_2023mar.onnx')) -or -not (Test-Path -LiteralPath (Join-Path $faceRuntime 'models\face_recognition_sface_2021dec.onnx'))) {
    & (Join-Path $PSScriptRoot 'init-face-auth.ps1')
}
Start-Managed 'face-auth' 8091 $facePython @((Join-Path $projectRoot 'face-auth\app.py'),'--model-dir',(Join-Path $faceRuntime 'models'),'--token-file',$faceTokenFile) $projectRoot
$jarPath = Join-Path $projectRoot 'backend\yudao-server\target\yudao-server.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Backend JAR missing. Run with -Build first.' }
Start-Managed 'backend' 48080 (Get-Command java.exe).Source @('-Xms128m','-Xmx1536m','-Dfile.encoding=UTF-8','-jar',$jarPath,'--spring.profiles.active=meslocal',('--spring.config.additional-location=file:'+(Join-Path $runtimeRoot 'application-meslocal.yaml').Replace('\','/'))) $projectRoot
$vitePath = Join-Path $projectRoot 'frontend\node_modules\vite\bin\vite.js'
if (-not (Test-Path -LiteralPath $vitePath)) { throw 'Frontend dependencies missing. Run pnpm install in frontend first.' }
Start-Managed 'frontend' 5173 (Get-Command node.exe).Source @('--max-old-space-size=4096',$vitePath,'--mode','env.local','--host','127.0.0.1','--port','5173','--strictPort') (Join-Path $projectRoot 'frontend')
foreach ($service in @(@{Name='backend'; Url='http://127.0.0.1:48080/admin-api/system/tenant/get-by-website?website=127.0.0.1:5173'},@{Name='frontend'; Url='http://127.0.0.1:5173/'},@{Name='face-auth'; Url='http://127.0.0.1:8091/health'; Headers=@{'X-Faze-Face-Token'=(Get-Content -LiteralPath $faceTokenFile -Raw).Trim()}})) {
    $ready = $false
    for ($attempt=0; $attempt -lt 90; $attempt++) {
        try {
            $requestOptions = @{Uri=$service.Url;TimeoutSec=2}
            if ($service.Headers) { $requestOptions.Headers=$service.Headers }
            $response = Invoke-WebRequest @requestOptions
            if ($response.StatusCode -eq 200 -and ($service.Name -ne 'backend' -or ($response.Content | ConvertFrom-Json).code -eq 0) -and ($service.Name -ne 'face-auth' -or ($response.Content | ConvertFrom-Json).ready -eq $true)) { $ready=$true; break }
        } catch { }
        $taskPid = [int](Get-Content -LiteralPath (Join-Path $runtimeRoot "$($service.Name).pid") -Raw).Trim()
        if (-not (Get-Process -Id $taskPid -ErrorAction SilentlyContinue)) { throw "$($service.Name) exited. Check $logRoot." }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "$($service.Name) did not become ready. Check $logRoot." }
}
Write-Host 'MES UI: http://127.0.0.1:5173'
Write-Host 'MES API: http://127.0.0.1:48080/admin-api'
