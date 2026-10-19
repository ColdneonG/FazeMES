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
