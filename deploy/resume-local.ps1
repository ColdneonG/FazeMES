#requires -Version 7.0
[CmdletBinding()]
param([switch]$Build)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot '.runtime'
. (Join-Path $PSScriptRoot 'local-tools.ps1')
$logRoot = Join-Path $runtimeRoot 'logs'
$mysqlData = Join-Path $runtimeRoot 'mysql\data'
$mysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe'
$mysqlClient = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$javaExe = Resolve-LocalTool -Name java.exe -HomeVariable JAVA_HOME -HomeRelativePath 'bin\java.exe'
$nodeExe = Resolve-LocalTool -Name node.exe
$redisExe = Join-Path $runtimeRoot 'redis-bin\redis-server.exe'
$faceRoot = Join-Path $runtimeRoot 'face-auth'
$facePython = Join-Path $faceRoot 'venv\Scripts\python.exe'
$faceTokenFile = Join-Path $faceRoot 'service.key'
$jarPath = Join-Path $projectRoot 'backend\yudao-server\target\yudao-server.jar'
$vitePath = Join-Path $projectRoot 'frontend\node_modules\vite\bin\vite.js'
$configPath = Join-Path $runtimeRoot 'application-meslocal.yaml'
# Resume never reinitializes a database, imports seed SQL, installs packages or downloads files.
foreach ($required in @($mysqlExe,$mysqlClient,$javaExe,$nodeExe,$redisExe,$facePython,$faceTokenFile,$vitePath,$configPath,
    (Join-Path $mysqlData 'auto.cnf'),(Join-Path $runtimeRoot 'mysql-client.local.ini'),
    (Join-Path $runtimeRoot 'redis.local.conf'),(Join-Path $faceRoot 'models\face_detection_yunet_2023mar.onnx'),
    (Join-Path $faceRoot 'models\face_recognition_sface_2021dec.onnx'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required local file missing: $required. Existing data has not been initialized or replaced." }
}
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
function Test-Port([int]$Port) {
    $socket = [Net.Sockets.TcpClient]::new()
    try { return $socket.ConnectAsync('127.0.0.1',$Port).Wait(300) -and $socket.Connected }
    catch { return $false } finally { $socket.Dispose() }
}
function Start-Managed([string]$Name,[int]$Port,[string]$Executable,[string[]]$ProcessArgs,[string]$Directory) {
    $pidPath = Join-Path $runtimeRoot "$Name.pid"
    $allowedExecutables = @($Executable)
    # A Windows venv launcher forwards to the Python installation recorded in pyvenv.cfg.
    if ($Name -eq 'face-auth') {
        $venvHome = Get-Content -LiteralPath (Join-Path $faceRoot 'venv\pyvenv.cfg') | Where-Object { $_ -match '^home\s*=' } | Select-Object -First 1
        if ($venvHome) { $allowedExecutables += Join-Path ($venvHome -replace '^home\s*=\s*','').Trim() 'python.exe' }
    }
    if (Test-Port $Port) {
        # Check the actual listener, since Windows may reuse a stale PID after reboot.
        $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        if ($listeners.Count -eq 0) { throw "Cannot verify listener on port $Port; no process has been stopped." }
        foreach ($listener in $listeners) {
            $existing = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
            if (-not $existing -or $existing.ExecutablePath -notin $allowedExecutables -or -not $existing.CommandLine -or -not $existing.CommandLine.Contains($projectRoot) -or ($Name -eq 'face-auth' -and -not $existing.CommandLine.Contains($ProcessArgs[0]))) {
                throw "Port $Port is occupied by an unrelated process; no process has been stopped."
            }
        }
        Set-Content -LiteralPath $pidPath -Value $listeners[0].OwningProcess
        Write-Host "$Name already running on $Port"
        return
    }
    # Start-Process joins arguments on Windows, so quote paths containing spaces.
    $quotedArgs = @($ProcessArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
    $taskProcess = Start-Process -FilePath $Executable -ArgumentList $quotedArgs -WorkingDirectory $Directory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot "$Name.stdout.log") -RedirectStandardError (Join-Path $logRoot "$Name.stderr.log")
    Set-Content -LiteralPath $pidPath -Value $taskProcess.Id
    Write-Host "Started $Name on $Port (PID $($taskProcess.Id))"
}
function Wait-Port([string]$Name,[int]$Port) {
    for ($attempt=0; $attempt -lt 120; $attempt++) {
        if (Test-Port $Port) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name did not start; check $logRoot."
}
$lockName = 'Local\FazeMES-' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($projectRoot))).Substring(0,16)
$startupLock = [Threading.Mutex]::new($false,$lockName)
$lockHeld = $false
try {
    try { $lockHeld = $startupLock.WaitOne(0) } catch [Threading.AbandonedMutexException] { $lockHeld = $true }
    if (-not $lockHeld) { throw 'FazeMES is already starting in another window. Wait for that window to finish.' }
    if ($Build) { $null = Resolve-LocalTool -Name mvn.cmd }
    elseif (-not (Test-LocalBackendJar -JarPath $jarPath)) { throw 'Runnable backend JAR missing. Run start-local.ps1 -Build first.' }
    Start-Managed 'mysql' 3308 $mysqlExe @('--no-defaults',"--datadir=$mysqlData",'--port=3308','--bind-address=127.0.0.1','--mysqlx=0','--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci',("--log-error="+(Join-Path $logRoot 'mysql-error.log'))) $runtimeRoot
    Wait-Port 'mysql' 3308
    $dbProbe = & $mysqlClient ("--defaults-extra-file="+(Join-Path $runtimeRoot 'mysql-client.local.ini')) --batch --skip-column-names --execute 'SELECT 1;' 2>&1
    if ($LASTEXITCODE -ne 0 -or ($dbProbe -join '').Trim() -ne '1') { throw 'Existing database could not be read; check the local MySQL configuration.' }
    # Digest-tracked, pending migrations must precede starting the new application.
    & (Join-Path $PSScriptRoot 'apply-migrations.ps1') -MysqlClient $mysqlClient
    Start-Managed 'redis' 6381 $redisExe @((Join-Path $runtimeRoot 'redis.local.conf')) $runtimeRoot
    Wait-Port 'redis' 6381
    Start-Managed 'face-auth' 8091 $facePython @((Join-Path $projectRoot 'face-auth\app.py'),'--model-dir',(Join-Path $faceRoot 'models'),'--token-file',$faceTokenFile) $projectRoot
    if ($Build) { & (Join-Path $PSScriptRoot 'restart-backend.ps1') -Build }
    else { Start-Managed 'backend' 48080 $javaExe @('-Xms128m','-Xmx1536m','-Dfile.encoding=UTF-8','-jar',$jarPath,'--spring.profiles.active=meslocal',('--spring.config.additional-location=file:'+$configPath.Replace('\','/'))) $projectRoot }
    Start-Managed 'frontend' 5173 $nodeExe @('--max-old-space-size=4096',$vitePath,'--mode','env.local','--host','127.0.0.1','--port','5173','--strictPort') (Join-Path $projectRoot 'frontend')
    Write-Host 'Waiting for services to become ready...'
    foreach ($service in @(@{Name='backend'; Url='http://127.0.0.1:48080/admin-api/system/tenant/get-by-website?website=127.0.0.1:5173'},@{Name='frontend'; Url='http://127.0.0.1:5173/'},@{Name='face-auth'; Url='http://127.0.0.1:8091/health'; Headers=@{'X-Faze-Face-Token'=(Get-Content -LiteralPath $faceTokenFile -Raw).Trim()}})) {
        $ready = $false
        for ($attempt=0; $attempt -lt 120; $attempt++) {
            try {
                $options = @{Uri=$service.Url;TimeoutSec=2}
                if ($service.Headers) { $options.Headers=$service.Headers }
                $response = Invoke-WebRequest @options
                if ($response.StatusCode -eq 200 -and ($service.Name -ne 'backend' -or ($response.Content | ConvertFrom-Json).code -eq 0) -and ($service.Name -ne 'face-auth' -or ($response.Content | ConvertFrom-Json).ready -eq $true)) { $ready=$true; break }
            } catch { }
            $taskPid = [int](Get-Content -LiteralPath (Join-Path $runtimeRoot "$($service.Name).pid") -Raw).Trim()
            if (-not (Get-Process -Id $taskPid -ErrorAction SilentlyContinue)) { throw "$($service.Name) exited; check $logRoot." }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) { throw "$($service.Name) did not become ready; check $logRoot." }
        Write-Host "$($service.Name) ready"
    }
    Write-Host 'FazeMES ready: http://127.0.0.1:5173/mes/insight'
} finally {
    if ($lockHeld) { $startupLock.ReleaseMutex() }
    $startupLock.Dispose()
}
