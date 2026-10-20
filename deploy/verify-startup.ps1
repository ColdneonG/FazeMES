#requires -Version 7.0
# Isolated regression checks: fake executables/SQL client only, no live services or database.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-tools.ps1')
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('FazeMES startup checks ' + [Guid]::NewGuid().ToString('N'))
$savedPath = $env:PATH
$savedJavaHome = $env:JAVA_HOME
$savedLocation = Get-Location
function Assert-Startup([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Startup check failed: $Message" }
}
function Assert-StartupThrows([scriptblock]$Action, [string]$Expected) {
    try { & $Action } catch {
        if ($_.Exception.Message -like "*$Expected*") { return }
        throw
    }
    throw "Expected failure containing: $Expected"
}
try {
    $tools = Join-Path $fixtureRoot 'tools'
    $javaHome = Join-Path $fixtureRoot 'Java Home'
    $fixtureDeploy = Join-Path $fixtureRoot 'deploy'
    $fixtureData = Join-Path $fixtureRoot '.runtime\mysql\data'
    $fixtureMigrations = Join-Path $fixtureRoot 'database\migrations'
    $jarDir = Join-Path $fixtureRoot 'backend\yudao-server\target'
    New-Item -ItemType Directory -Path $tools,(Join-Path $javaHome 'bin'),$fixtureDeploy,$fixtureData,$fixtureMigrations,$jarDir | Out-Null
    New-Item -ItemType File -Path (Join-Path $tools 'java.exe'),(Join-Path $javaHome 'bin\java.exe') | Out-Null
    $fakeMaven = Join-Path $tools 'mvn.cmd'
    '@exit /b 7' | Set-Content -LiteralPath $fakeMaven -Encoding ascii
    $env:PATH = $tools
    $env:JAVA_HOME = $javaHome
    Assert-Startup ((Resolve-LocalTool -Name java.exe -HomeVariable JAVA_HOME -HomeRelativePath 'bin\java.exe') -eq (Join-Path $javaHome 'bin\java.exe')) 'JAVA_HOME must take precedence, including paths with spaces.'
    $env:JAVA_HOME = $null
    Assert-Startup ((Resolve-LocalTool -Name java.exe -HomeVariable JAVA_HOME -HomeRelativePath 'bin\java.exe') -eq (Join-Path $tools 'java.exe')) 'Java must fall back to PATH.'
    Assert-Startup ((Resolve-LocalTool -Name mvn.cmd) -eq $fakeMaven) 'Maven must be discovered from PATH.'
    $env:JAVA_HOME = Join-Path $fixtureRoot 'missing JDK'
    Assert-StartupThrows { Resolve-LocalTool -Name java.exe -HomeVariable JAVA_HOME -HomeRelativePath 'bin\java.exe' } 'JAVA_HOME does not contain'
    $env:JAVA_HOME = $javaHome
    Assert-StartupThrows { Resolve-LocalTool -Name 'fazemes-missing-tool.exe' } 'was not found'

    $jar = Join-Path $jarDir 'yudao-server.jar'
    $zip = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $entry = $zip.CreateEntry('META-INF/MANIFEST.MF')
        $writer = [IO.StreamWriter]::new($entry.Open())
        try { $writer.WriteLine('Main-Class: org.springframework.boot.loader.launch.JarLauncher') }
        finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
    $jarBytes = [IO.File]::ReadAllBytes($jar)
    $jarHash = (Get-FileHash -LiteralPath $jar).Hash
    Assert-StartupThrows { Build-LocalBackend -ProjectRoot $fixtureRoot } 'Backend build failed'
    Assert-Startup ((Get-Location).Path -eq $savedLocation.Path) 'Failed builds must restore the caller directory.'
    Assert-Startup ((Get-FileHash -LiteralPath (Join-Path $fixtureRoot '.runtime\backups\yudao-server.previous.jar')).Hash -eq $jarHash) 'A runnable previous JAR must be backed up before building.'
    Assert-Startup ((Get-FileHash -LiteralPath $jar).Hash -eq $jarHash) 'A failed stub build must preserve the existing JAR.'
    '@exit /b 0' | Set-Content -LiteralPath $fakeMaven -Encoding ascii
    Build-LocalBackend -ProjectRoot $fixtureRoot
    # Success alone must not permit starting a missing/corrupt artifact.
    Remove-Item -LiteralPath $jar
    Assert-StartupThrows { Build-LocalBackend -ProjectRoot $fixtureRoot } 'did not produce a runnable backend JAR'

    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-local.ps1'),(Join-Path $PSScriptRoot 'local-tools.ps1'),(Join-Path $PSScriptRoot 'apply-migrations.ps1') -Destination $fixtureDeploy
    'existing mysql identity' | Set-Content -LiteralPath (Join-Path $fixtureData 'auto.cnf')
    @'
param([switch]$Build)
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'resume-called.txt') -Value $Build.IsPresent
'@ | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'resume-local.ps1')
    "throw 'Unexpected database initialization'" | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'init-local.ps1')
    & (Join-Path $fixtureDeploy 'start-local.ps1') -Build
    Assert-Startup ((Get-Content -LiteralPath (Join-Path $fixtureDeploy 'resume-called.txt') -Raw).Trim() -eq 'True') 'Existing data must route to resume and preserve -Build, even with missing config.'

    # Run the real restart control flow with private TCP listeners and mocked process
    # operations. The copied script probes only these private ports; no live service
    # is started, stopped or queried by the checks below.
    $probeListeners = @([Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0),[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0))
    try {
        foreach ($listener in $probeListeners) { $listener.Start() }
        $privatePorts = @($probeListeners | ForEach-Object { $_.LocalEndpoint.Port })
        $restartSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'restart-backend.ps1') -Raw
        $restartSource.Replace('@(3308,6381)', ('@(' + ($privatePorts -join ',') + ')')) | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'restart-backend.ps1')
        [IO.File]::WriteAllBytes($jar, $jarBytes)
        'fixture config' | Set-Content -LiteralPath (Join-Path $fixtureRoot '.runtime\application-meslocal.yaml')
        @'
function Build-LocalBackend {
    param([string]$ProjectRoot)
    Add-Content -LiteralPath (Join-Path $ProjectRoot 'events.txt') -Value 'build'
    if (Test-Path -LiteralPath (Join-Path $ProjectRoot 'fail-build')) { throw 'Simulated compiler failure' }
}
'@ | Add-Content -LiteralPath (Join-Path $fixtureDeploy 'local-tools.ps1')
        @'
$fixtureProject = Split-Path -Parent $PSScriptRoot
Add-Content -LiteralPath (Join-Path $fixtureProject 'events.txt') -Value 'migrate'
if (Test-Path -LiteralPath (Join-Path $fixtureProject 'fail-migration')) { throw 'Simulated migration failure' }
'@ | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'apply-migrations.ps1')
        $eventsPath = Join-Path $fixtureRoot 'events.txt'
        function Invoke-FixtureRestart {
            param([bool]$Rebuild = $true)
            & {
                function Get-NetTCPConnection {
                    $events = @(Get-Content -LiteralPath $eventsPath -ErrorAction SilentlyContinue)
                    if ($events -contains 'start') { [pscustomobject]@{ OwningProcess=4242 } }
                    elseif ($events -notcontains 'stop') { [pscustomobject]@{ OwningProcess=1000 } }
                }
                function Get-CimInstance { [pscustomobject]@{ Name='java.exe'; CommandLine=$jar; ProcessId=4242 } }
                function Stop-Process { Add-Content -LiteralPath $eventsPath -Value 'stop' }
                function Wait-Process { }
                function Start-Process {
                    Add-Content -LiteralPath $eventsPath -Value 'start'
                    [pscustomobject]@{ Id=4242; HasExited=$false }
                }
                function Invoke-RestMethod { [pscustomobject]@{ code=0 } }
                & (Join-Path $fixtureDeploy 'restart-backend.ps1') -Build:$Rebuild
            }
        }
        Set-Content -LiteralPath $eventsPath -Value @()
        Invoke-FixtureRestart
        Assert-Startup (((Get-Content -LiteralPath $eventsPath) -join ',') -eq 'stop,migrate,build,start') 'Restart must stop old writers before data-quality migrations, then build and start.'
        Set-Content -LiteralPath $eventsPath -Value @()
        Invoke-FixtureRestart -Rebuild $false
        Assert-Startup (((Get-Content -LiteralPath $eventsPath) -join ',') -eq 'stop,migrate,start') 'Resuming without a build must also stop old writers before migrations.'
        Set-Content -LiteralPath $eventsPath -Value @()
        New-Item -ItemType File -Path (Join-Path $fixtureRoot 'fail-migration') | Out-Null
        Assert-StartupThrows { Invoke-FixtureRestart } 'Simulated migration failure'
        Assert-Startup (((Get-Content -LiteralPath $eventsPath) -join ',') -eq 'stop,migrate') 'A migration failure must leave the backend stopped so old writers cannot bypass new quality guards.'
        Remove-Item -LiteralPath (Join-Path $fixtureRoot 'fail-migration')
        Set-Content -LiteralPath $eventsPath -Value @()
        New-Item -ItemType File -Path (Join-Path $fixtureRoot 'fail-build') | Out-Null
        Assert-StartupThrows { Invoke-FixtureRestart } 'Backend remains stopped'
        Assert-Startup (((Get-Content -LiteralPath $eventsPath) -join ',') -eq 'stop,migrate,build') 'A compiler failure must report the stopped backend without starting a bad JAR.'
        Assert-Startup ((Get-FileHash -LiteralPath (Join-Path $fixtureRoot '.runtime\backups\yudao-server.previous.jar')).Hash -eq $jarHash) 'The prior runnable JAR must survive a failed restart build.'
    } finally { foreach ($listener in $probeListeners) { $listener.Stop() } }

    # Exercise the real migration dispatcher against a fake SQL client. The fake stores
    # only checksum records; it cannot connect to a real database or execute SQL.
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'apply-migrations.ps1') -Destination $fixtureDeploy -Force
    foreach ($name in @('20261017_md_qc_coverage.sql','20261018_broky_cost.sql','20261018_broky_product.sql','20261018_controlled_sop.sql','20261019_production_insight.sql','20261020_jev_incident_triage.sql')) {
        Set-Content -LiteralPath (Join-Path $fixtureMigrations $name) -Value '-- isolated fixture'
    }
    $clientOptions = Join-Path $fixtureRoot '.runtime\mysql-client.local.ini'
    '[client]' | Set-Content -LiteralPath $clientOptions
    $fakeClient = Join-Path $fixtureDeploy 'fake-mysql.ps1'
    @'
$global:LASTEXITCODE = 0
$sql = ($args | Where-Object { $_ -like '--execute=*' }) -replace '^--execute=', ''
$statePath = Join-Path $PSScriptRoot 'migration-state.json'
$state = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable } else { @{} }
Add-Content -LiteralPath (Join-Path $PSScriptRoot 'sql-calls.txt') -Value $sql
if ($sql -match "^SELECT sha256 .* name='([^']+)'") { if ($state.ContainsKey($Matches[1])) { $state[$Matches[1]] }; return }
if ($sql -match "^INSERT INTO .*VALUES\('([^']+)','([^']+)'\)") {
    $state[$Matches[1]] = $Matches[2]
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath
}
'@ | Set-Content -LiteralPath $fakeClient
    & (Join-Path $fixtureDeploy 'apply-migrations.ps1') -MysqlClient $fakeClient -ClientOptions $clientOptions
    $sqlCalls = @(Get-Content -LiteralPath (Join-Path $fixtureDeploy 'sql-calls.txt'))
    $sources = @($sqlCalls | Where-Object { $_ -like 'source *' })
    Assert-Startup ($sources.Count -eq 6) 'All pending migrations must execute once.'
    $sopIndex = [Array]::FindIndex($sources, [Predicate[string]]{ param($line) $line.EndsWith('20261018_controlled_sop.sql') })
    $productIndex = [Array]::FindIndex($sources, [Predicate[string]]{ param($line) $line.EndsWith('20261018_broky_product.sql') })
    Assert-Startup ($sopIndex -ge 0 -and $sopIndex -lt $productIndex) 'Controlled SOP must execute before the Broky product migration.'
    & (Join-Path $fixtureDeploy 'apply-migrations.ps1') -MysqlClient $fakeClient -ClientOptions $clientOptions
    $sourcesAfterResume = @(Get-Content -LiteralPath (Join-Path $fixtureDeploy 'sql-calls.txt') | Where-Object { $_ -like 'source *' })
    Assert-Startup ($sourcesAfterResume.Count -eq 6) 'Already applied migrations must be verified and skipped.'
    Add-Content -LiteralPath (Join-Path $fixtureMigrations '20261018_broky_product.sql') -Value '-- changed history'
    Assert-StartupThrows { & (Join-Path $fixtureDeploy 'apply-migrations.ps1') -MysqlClient $fakeClient -ClientOptions $clientOptions } 'Applied migration changed'
    Write-Host 'Startup checks passed: tool discovery, JAR backup, safe restart order, failure handling, existing-data routing, migration dependencies, idempotence and checksum protection.'
} finally {
    $env:PATH = $savedPath
    $env:JAVA_HOME = $savedJavaHome
    Set-Location -LiteralPath $savedLocation
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolvedFixture.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $resolvedFixture) -like 'FazeMES startup checks *') {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force -ErrorAction SilentlyContinue
    }
}
