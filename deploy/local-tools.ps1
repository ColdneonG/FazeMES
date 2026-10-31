# Shared process-local tool discovery. No machine-specific paths or system settings.
function Resolve-LocalTool {
    param([Parameter(Mandatory)][string]$Name, [string]$HomeVariable, [string]$HomeRelativePath)
    if ($HomeVariable) {
        $toolHome = [Environment]::GetEnvironmentVariable($HomeVariable, 'Process')
        if ($toolHome) {
            $candidate = Join-Path $toolHome $HomeRelativePath
            if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
                throw "$HomeVariable does not contain $HomeRelativePath. Correct it before starting FazeMES."
            }
            return (Get-Item -LiteralPath $candidate).FullName
        }
    }
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) { throw "$Name was not found. Install the required tool and add it to PATH." }
    return $command.Source
}

function Test-LocalBackendJar {
    param([Parameter(Mandatory)][string]$JarPath)
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { return $false }
    try {
        $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
        try {
            $manifest = $archive.GetEntry('META-INF/MANIFEST.MF')
            if (-not $manifest) { return $false }
            $reader = [IO.StreamReader]::new($manifest.Open())
            try { return $reader.ReadToEnd().Contains('Main-Class: org.springframework.boot.loader.launch.JarLauncher') }
            finally { $reader.Dispose() }
        } finally { $archive.Dispose() }
    } catch { return $false }
}

function Backup-LocalBackend {
    param([Parameter(Mandatory)][string]$ProjectRoot)
    $jar = Join-Path $ProjectRoot 'backend\yudao-server\target\yudao-server.jar'
    if (Test-LocalBackendJar -JarPath $jar) {
        $backupDir = Join-Path $ProjectRoot '.runtime\backups'
        New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
        Copy-Item -LiteralPath $jar -Destination (Join-Path $backupDir 'yudao-server.previous.jar') -Force
    }
}

function Build-LocalBackend {
    param([Parameter(Mandatory)][string]$ProjectRoot)
    $null = Resolve-LocalTool -Name java.exe -HomeVariable JAVA_HOME -HomeRelativePath 'bin\java.exe'
    $maven = Resolve-LocalTool -Name mvn.cmd
    $jar = Join-Path $ProjectRoot 'backend\yudao-server\target\yudao-server.jar'
    Backup-LocalBackend -ProjectRoot $ProjectRoot
    Push-Location -LiteralPath $ProjectRoot
    try {
        & $maven -s (Join-Path $ProjectRoot 'deploy\maven-local-settings.xml') -B -ntp -f backend/pom.xml -pl yudao-server -am package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed. A previous runnable JAR, when available, is retained in .runtime/backups.' }
        if (-not (Test-LocalBackendJar -JarPath $jar)) { throw 'Build did not produce a runnable backend JAR.' }
    } finally { Pop-Location }
}

# One byte-range file lock coordinates maintenance across Windows logon sessions
# and with the Python backup worker. Nested deployment scripts share its lease.
function Enter-LocalMaintenanceLock {
    param([Parameter(Mandatory)][string]$ProjectRoot, [int]$TimeoutSeconds = 30)
    $key = [IO.Path]::GetFullPath($ProjectRoot)
    if (-not (Get-Variable -Name FazeMesMaintenanceLocks -Scope Global -ErrorAction SilentlyContinue)) {
        $global:FazeMesMaintenanceLocks = @{}
    }
    if ($global:FazeMesMaintenanceLocks.ContainsKey($key)) {
        $global:FazeMesMaintenanceLocks[$key].Count++
        return $key
    }
    $runtime = Join-Path $key '.runtime'
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    $lockPath = Join-Path $runtime 'maintenance.lock'
    if ((Get-Item -LiteralPath $runtime).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Runtime lock directory cannot be a link.' }
    if ((Test-Path -LiteralPath $lockPath) -and ((Get-Item -LiteralPath $lockPath).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Maintenance lock cannot be a link.' }
    $stream = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::ReadWrite)
    try {
        if ($stream.Length -eq 0) { $stream.SetLength(1) }
        $until = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ($true) {
            try { $stream.Lock(0,1); break }
            catch [IO.IOException] {
                if ([DateTime]::UtcNow -ge $until) { throw 'Backup or database maintenance is running. Retry after it completes.' }
                Start-Sleep -Milliseconds 200
            }
        }
        $global:FazeMesMaintenanceLocks[$key] = @{Stream=$stream;Count=1}
        return $key
    } catch { $stream.Dispose(); throw }
}

function Exit-LocalMaintenanceLock {
    param([Parameter(Mandatory)][string]$Lease)
    $entry = $global:FazeMesMaintenanceLocks[$Lease]
    if (-not $entry) { throw 'Unknown database maintenance lease.' }
    $entry.Count--
    if ($entry.Count -eq 0) {
        try { $entry.Stream.Unlock(0,1) }
        finally { $entry.Stream.Dispose(); $global:FazeMesMaintenanceLocks.Remove($Lease) }
    }
}

function Protect-LocalBackupPath {
    param([Parameter(Mandatory)][string]$Path)
    $item = Get-Item -LiteralPath $Path
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Backup paths cannot be links.' }
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = if ($item.PSIsContainer) { [Security.AccessControl.DirectorySecurity]::new() } else { [Security.AccessControl.FileSecurity]::new() }
    $acl.SetOwner($owner)
    $acl.SetAccessRuleProtection($true,$false)
    foreach ($sid in @($owner,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'),[Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
        $rule = if ($item.PSIsContainer) {
            [Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
        } else { [Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow') }
        $acl.AddAccessRule($rule)
    }
    [IO.FileSystemAclExtensions]::SetAccessControl($item,$acl)
}

function Start-LocalBackupScheduler {
    param([Parameter(Mandatory)][string]$ProjectRoot)
    $runtime = Join-Path $ProjectRoot '.runtime'
    $script = Join-Path $ProjectRoot 'database\tools\operational_backups.py'
    $python = Resolve-LocalTool -Name python.exe
    $mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    $dump = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
    foreach ($required in @($script,$python,$mysql,$dump,(Join-Path $runtime 'mysql-admin.local.ini'))) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw 'Operational backup prerequisite is missing.' }
    }
    $pidPath = Join-Path $runtime 'backup.pid'
    $candidates = @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction Stop | Where-Object {
        $_.ExecutablePath -eq $python -and $_.CommandLine -and $_.CommandLine.Contains($script) -and $_.CommandLine.Contains('--mode daemon') -and $_.CommandLine.Contains($ProjectRoot)
    })
    if ($candidates.Count -gt 1) { throw 'Multiple project backup schedulers were found; inspect their process ownership.' }
    if ($candidates.Count -eq 1) {
        Set-Content -LiteralPath $pidPath -Value $candidates[0].ProcessId
        Write-Host 'Operational backup scheduler already running.'
        return
    }
    if (Test-Path -LiteralPath $pidPath) {
        $recorded = Get-CimInstance Win32_Process -Filter "ProcessId=$([int](Get-Content -LiteralPath $pidPath -Raw))" -ErrorAction SilentlyContinue
        if ($recorded) { throw 'Backup PID belongs to another process; it was not changed.' }
    }
    $backupRoot = Join-Path $runtime 'backups\operations'
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
    Protect-LocalBackupPath -Path $backupRoot
    Protect-LocalBackupPath -Path (Join-Path $runtime 'mysql-admin.local.ini')
    $stopPath = Join-Path $runtime 'backup.stop'
    if (Test-Path -LiteralPath $stopPath) { Remove-Item -LiteralPath $stopPath }
    $instanceId = [Guid]::NewGuid().ToString('N')
    $arguments = @('-X','utf8',$script,'--project-root',$ProjectRoot,'--mysql-client',$mysql,'--mysqldump',$dump,'--mode','daemon','--instance-id',$instanceId)
    $quoted = @($arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
    $process = Start-Process -FilePath $python -ArgumentList $quoted -WorkingDirectory $ProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'logs\backup.stdout.log') -RedirectStandardError (Join-Path $runtime 'logs\backup.stderr.log')
    Set-Content -LiteralPath $pidPath -Value $process.Id
    # Readiness only waits for its exclusive scheduler lease, never for a database dump.
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        $process.Refresh()
        if ($process.HasExited) { throw 'Operational backup scheduler exited; see .runtime/logs/backup.stderr.log.' }
        $readyPath = Join-Path $runtime 'backup-scheduler-ready.json'
        if (Test-Path -LiteralPath $readyPath) {
            $ready = Get-Content -LiteralPath $readyPath -Raw | ConvertFrom-Json
            if ($ready.pid -eq $process.Id -and $ready.instanceId -eq $instanceId) { Write-Host 'Operational backup scheduler ready (Asia/Shanghai 02:00).'; return }
        }
        Start-Sleep -Milliseconds 100
    }
    throw 'Operational backup scheduler readiness timed out; inspect its status and logs.'
}

function Stop-LocalBackupScheduler {
    param([Parameter(Mandatory)][string]$ProjectRoot)
    $runtime = Join-Path $ProjectRoot '.runtime'
    $script = Join-Path $ProjectRoot 'database\tools\operational_backups.py'
    $pidPath = Join-Path $runtime 'backup.pid'
    if (-not (Test-Path -LiteralPath $pidPath)) { return }
    $recordedId = [int](Get-Content -LiteralPath $pidPath -Raw)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$recordedId" -ErrorAction SilentlyContinue
    if ($process) {
        if ($process.Name -ne 'python.exe' -or -not $process.CommandLine -or -not $process.CommandLine.Contains($script) -or -not $process.CommandLine.Contains('--mode daemon') -or -not $process.CommandLine.Contains($ProjectRoot)) {
            throw 'Backup PID belongs to another process; it was not stopped.'
        }
        Set-Content -LiteralPath (Join-Path $runtime 'backup.stop') -Value 'stop'
        Wait-Process -Id $recordedId -Timeout 30 -ErrorAction SilentlyContinue
        if (Get-Process -Id $recordedId -ErrorAction SilentlyContinue) { throw 'Backup worker is still stopping; database services were retained.' }
        Write-Host 'Stopped operational backup scheduler.'
    }
    Remove-Item -LiteralPath $pidPath
}

# Some Windows providers return state 0 for an active loopback listener.
# Pair a successful connection with the listening endpoint (remote port zero).
function Get-LocalPortOwner([int]$Port) {
    $rows = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.RemotePort -eq 0 -and $_.OwningProcess -gt 0 })
    if ($rows.Count) { return $rows }
    $probe = [Net.Sockets.TcpClient]::new()
    try {
        try { if (-not $probe.ConnectAsync('127.0.0.1',$Port).Wait(5000)) { throw "Port probe timed out on $Port" } }
        catch [System.AggregateException] {
            if ($_.Exception.GetBaseException().SocketErrorCode -eq 'ConnectionRefused') { return }
            throw
        }
        $rows = @(Get-NetTCPConnection -LocalPort $Port -ErrorAction Stop | Where-Object { $_.RemotePort -eq 0 -and $_.OwningProcess -gt 0 })
        if (-not $rows.Count) { throw "Cannot identify the owner of active port $Port." }
        return $rows
    } finally { $probe.Dispose() }
}
