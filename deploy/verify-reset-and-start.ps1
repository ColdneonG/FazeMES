#requires -Version 7.0
# Isolated launcher checks: temporary fake tools/helpers only; no live services or database.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('FazeMES reset checks 空格 ' + [Guid]::NewGuid().ToString('N'))
$fixtureDeploy = Join-Path $fixtureRoot 'deploy'
$runtimeRoot = Join-Path $fixtureRoot '.runtime'
$eventsPath = Join-Path $fixtureRoot 'events.txt'
$savedLocation = Get-Location
$savedEnvironment = @{}
foreach ($name in @('PATH','JAVA_HOME','PYTHONUTF8','PYTHONIOENCODING')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$savedConsoleEncoding = [Console]::OutputEncoding
$savedOutputEncoding = $OutputEncoding
$savedLastExitCode = $global:LASTEXITCODE
$checks = 0

function Assert-ResetStartup([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Reset/start check failed: $Message" }
    $script:checks++
}

function Assert-ResetStartupThrows([scriptblock]$Action, [string]$Expected) {
    try { & $Action } catch {
        if ($_.Exception.Message -like "*$Expected*") { $script:checks++; return }
        throw
    }
    throw "Expected failure containing: $Expected"
}

function Set-FixtureInstalled([bool]$Installed = $true) {
    foreach ($relative in @('mysql\data\auto.cnf','application-meslocal.yaml')) {
        $path = Join-Path $runtimeRoot $relative
        if ($Installed) { Set-Content -LiteralPath $path -Value 'fixture' }
        elseif (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
}

function New-FixtureJar {
    $jar = Join-Path $fixtureRoot 'backend\yudao-server\target\yudao-server.jar'
    if (Test-Path -LiteralPath $jar) { Remove-Item -LiteralPath $jar }
    $archive = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $writer = [IO.StreamWriter]::new($archive.CreateEntry('META-INF/MANIFEST.MF').Open())
        try { $writer.WriteLine('Main-Class: org.springframework.boot.loader.launch.JarLauncher') }
        finally { $writer.Dispose() }
    } finally { $archive.Dispose() }
}

function Reset-FixtureEvents {
    Set-Content -LiteralPath $eventsPath -Value @()
}

function Assert-FixtureEvents([string]$Expected, [string]$Message) {
    $events = @(Get-Content -LiteralPath $eventsPath -ErrorAction SilentlyContinue)
    Assert-ResetStartup (($events -join ',') -eq $Expected) "$Message; events: $($events -join ',')"
}

function Invoke-FixtureLauncher {
    param([switch]$CheckOnly,[switch]$WithBrowser,[switch]$Build)
    & {
        # The launcher has fixed MySQL installation checks. Satisfy only those
        # file checks; no executable or network operation is redirected to MySQL.
        function Test-Path {
            [CmdletBinding()]
            param([string[]]$LiteralPath,[string[]]$Path,[string]$PathType)
            if ($LiteralPath.Count -eq 1 -and $LiteralPath[0] -match '^C:\\Program Files\\MySQL\\MySQL Server 8\.0\\bin\\(mysql|mysqld|mysqldump)\.exe$') { return $true }
            Microsoft.PowerShell.Management\Test-Path @PSBoundParameters
        }
        function Get-Command {
            [CmdletBinding()]
            param([string]$Name,[string]$CommandType)
            if ($Name -in @('python.exe','java.exe','node.exe')) {
                return [pscustomobject]@{Source=(Join-Path $fixtureDeploy ($Name + '.ps1'))}
            }
            Microsoft.PowerShell.Core\Get-Command @PSBoundParameters
        }
        function Start-Transcript { param([string]$LiteralPath,[switch]$Force) }
        function Stop-Transcript { }
        function Start-Process {
            param([string]$FilePath)
            Add-Content -LiteralPath $eventsPath -Value 'browser'
        }
        & (Join-Path $fixtureDeploy 'reinitialize-and-start.ps1') -CheckOnly:$CheckOnly -NoBrowser:(!$WithBrowser) -Build:$Build 6>$null
    }
}

try {
    foreach ($directory in @($fixtureDeploy,(Join-Path $runtimeRoot 'mysql\data'),
        (Join-Path $fixtureRoot 'frontend\node_modules\vite\bin'),
        (Join-Path $fixtureRoot 'database\init'),
        (Join-Path $fixtureRoot 'backend\yudao-server\target'))) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'reinitialize-and-start.ps1'),(Join-Path $PSScriptRoot 'local-tools.ps1') -Destination $fixtureDeploy
    # Keep the real runnable-JAR validator. Tool execution uses private .ps1
    # doubles, so these checks need no installed Java, Node, Python or MySQL.
    @'
function Resolve-LocalTool {
    param([string]$Name,[string]$HomeVariable,[string]$HomeRelativePath)
    $root = Split-Path -Parent $PSScriptRoot
    if ($Name -eq 'mvn.cmd') { Add-Content -LiteralPath (Join-Path $root 'events.txt') -Value 'maven' }
    return Join-Path $PSScriptRoot ($Name + '.ps1')
}
'@ | Add-Content -LiteralPath (Join-Path $fixtureDeploy 'local-tools.ps1')
    @'
$global:LASTEXITCODE = 0
$root = Split-Path -Parent $PSScriptRoot
if ($args -contains '-c' -and ($args -join ' ') -like '*import bcrypt, pymysql*' -and (Test-Path -LiteralPath (Join-Path $root 'missing-python-dependencies'))) {
    $global:LASTEXITCODE = 1
}
if ($args -contains '-m') {
    Add-Content -LiteralPath (Join-Path $root 'events.txt') -Value 'pip'
    $global:LASTEXITCODE = 7
}
'@ | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'python.exe.ps1')
    @'
$global:LASTEXITCODE = 0
'openjdk version "17.0.11"'
'@ | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'java.exe.ps1')
    @'
$global:LASTEXITCODE = 0
'v22.14.0'
'@ | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'node.exe.ps1')
    '$global:LASTEXITCODE = 0' | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'mvn.cmd.ps1')
    @'
param([switch]$Build)
$root = Split-Path -Parent $PSScriptRoot
Add-Content -LiteralPath (Join-Path $root 'events.txt') -Value "start:$($Build.IsPresent)"
if (Test-Path -LiteralPath (Join-Path $root 'fail-preparation')) { throw 'Fixture preparation failure' }
'@ | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'start-local.ps1')
    @'
param([switch]$DependenciesOnly,[switch]$ReuseBackend)
$root = Split-Path -Parent $PSScriptRoot
if ($DependenciesOnly) {
    Add-Content -LiteralPath (Join-Path $root 'events.txt') -Value 'dependencies'
    if (Test-Path -LiteralPath (Join-Path $root 'fail-preparation')) { throw 'Fixture preparation failure' }
} elseif ($ReuseBackend) { Add-Content -LiteralPath (Join-Path $root 'events.txt') -Value 'reuse' }
else { throw 'Unexpected full resume; would restart backend twice' }
'@ | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'resume-local.ps1')
    @'
$root = Split-Path -Parent $PSScriptRoot
Add-Content -LiteralPath (Join-Path $root 'events.txt') -Value 'reset'
if (Test-Path -LiteralPath (Join-Path $root 'fail-reset')) { throw 'Fixture reset failure' }
$result = if (Test-Path -LiteralPath (Join-Path $root 'fail-report')) { 'FAIL' } else { 'PASS' }
@{result=$result;totalMesRows=6000;populatedMesTables=177;checks=@(@{pass=$true})} |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $root '.runtime\testdata-verification.json')
'@ | Set-Content -LiteralPath (Join-Path $fixtureDeploy 'reset-test-data.ps1')
    'fixture' | Set-Content -LiteralPath (Join-Path $fixtureRoot 'frontend\node_modules\vite\bin\vite.js')
    '-- fixture' | Set-Content -LiteralPath (Join-Path $fixtureRoot 'database\init\mes_seed.sql')
    New-FixtureJar
    Set-FixtureInstalled

    Reset-FixtureEvents
    Invoke-FixtureLauncher -CheckOnly
    Assert-FixtureEvents '' 'CheckOnly must not start services, reset data or open a browser'
    Assert-ResetStartup ((Get-Location).Path -eq $savedLocation.Path) 'CheckOnly must preserve the caller directory'

    Reset-FixtureEvents
    Invoke-FixtureLauncher
    Assert-FixtureEvents 'dependencies,reset,reuse' 'An existing installation must prepare dependencies, reset once and reuse its backend'
    Assert-ResetStartup ((Get-Location).Path -eq $savedLocation.Path) 'Success must restore the caller directory'

    Reset-FixtureEvents
    Invoke-FixtureLauncher -WithBrowser
    Assert-FixtureEvents 'dependencies,reset,reuse,browser' 'Browser opening must follow successful initialization and final readiness'

    Set-FixtureInstalled $false
    Reset-FixtureEvents
    Invoke-FixtureLauncher
    Assert-FixtureEvents 'start:False,reset,reuse' 'First installation must use the full preparation route'
    Set-FixtureInstalled

    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'backend\yudao-server\target\yudao-server.jar')
    Reset-FixtureEvents
    Invoke-FixtureLauncher
    Assert-FixtureEvents 'maven,start:True,reset,reuse' 'A missing JAR must require Maven and request a build before reset'
    New-FixtureJar
    Reset-FixtureEvents
    Invoke-FixtureLauncher -Build
    Assert-FixtureEvents 'maven,start:True,reset,reuse' 'An explicit build must use the full preparation route'

    $vite = Join-Path $fixtureRoot 'frontend\node_modules\vite\bin\vite.js'
    Remove-Item -LiteralPath $vite
    Reset-FixtureEvents
    Assert-ResetStartupThrows { Invoke-FixtureLauncher -WithBrowser } '缺少必要文件'
    Assert-FixtureEvents '' 'Preflight failure must stop before any helper, reset or browser launch'
    Assert-ResetStartup ((Get-Location).Path -eq $savedLocation.Path) 'Preflight failure must restore the caller directory'
    'fixture' | Set-Content -LiteralPath $vite

    $missingDependencies = Join-Path $fixtureRoot 'missing-python-dependencies'
    New-Item -ItemType File -Path $missingDependencies | Out-Null
    Reset-FixtureEvents
    Assert-ResetStartupThrows { Invoke-FixtureLauncher -CheckOnly } '缺少 Python 依赖'
    Assert-FixtureEvents '' 'CheckOnly must not install missing dependencies'
    Assert-ResetStartupThrows { Invoke-FixtureLauncher } 'Python 依赖安装失败'
    Assert-FixtureEvents 'pip' 'Dependency installation failure must stop before preparing or resetting services'
    Remove-Item -LiteralPath $missingDependencies

    foreach ($failure in @(@{Marker='fail-preparation';Message='Fixture preparation failure';Events='dependencies'},
                           @{Marker='fail-reset';Message='Fixture reset failure';Events='dependencies,reset'},
                           @{Marker='fail-report';Message='测试数据验收未通过';Events='dependencies,reset,reuse'})) {
        $markerPath = Join-Path $fixtureRoot $failure.Marker
        New-Item -ItemType File -Path $markerPath | Out-Null
        Reset-FixtureEvents
        Assert-ResetStartupThrows { Invoke-FixtureLauncher -WithBrowser } $failure.Message
        Assert-FixtureEvents $failure.Events 'A failed stage must prevent all later stages and browser opening'
        Assert-ResetStartup ((Get-Location).Path -eq $savedLocation.Path) 'Every stage failure must restore the caller directory'
        Remove-Item -LiteralPath $markerPath
    }

    # Own the same named mutex on another runspace thread. A same-thread mutex
    # would be reentrant and would not model a second Explorer window.
    $lockName = 'Local\FazeMES-' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($fixtureRoot))).Substring(0,16)
    $ready = [Threading.ManualResetEventSlim]::new($false)
    $release = [Threading.ManualResetEventSlim]::new($false)
    $blocker = [PowerShell]::Create()
    $null = $blocker.AddScript({
        param($Name,$Ready,$Release)
        $mutex = [Threading.Mutex]::new($false,$Name)
        $held = $false
        try {
            $held = $mutex.WaitOne(0)
            if (-not $held) { throw 'Fixture mutex unexpectedly held' }
            $Ready.Set()
            if (-not $Release.Wait(15000)) { throw 'Fixture mutex release timed out' }
        } finally {
            if ($held) { $mutex.ReleaseMutex() }
            $mutex.Dispose()
        }
    }).AddArgument($lockName).AddArgument($ready).AddArgument($release)
    $pending = $blocker.BeginInvoke()
    try {
        Assert-ResetStartup ($ready.Wait(5000)) 'The duplicate-window mutex fixture must acquire its lock'
        Reset-FixtureEvents
        Assert-ResetStartupThrows { Invoke-FixtureLauncher -WithBrowser } '项目正在另一个窗口'
        Assert-FixtureEvents '' 'A concurrent launch must reject before service/reset helpers'
    } finally {
        $release.Set()
        $null = $blocker.EndInvoke($pending)
        $blocker.Dispose()
        $ready.Dispose()
        $release.Dispose()
    }
    Reset-FixtureEvents
    Invoke-FixtureLauncher
    Assert-FixtureEvents 'dependencies,reset,reuse' 'Failure and duplicate rejection must release the startup lock for a later run'
    Write-Host "Reset/start isolated checks passed: $checks assertions; existing/new installs, builds, failures, Unicode paths, directory restoration and duplicate-launch locking."
} finally {
    Set-Location -LiteralPath $savedLocation.Path
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    [Console]::OutputEncoding = $savedConsoleEncoding
    $OutputEncoding = $savedOutputEncoding
    $global:LASTEXITCODE = $savedLastExitCode
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolvedFixture.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedFixture) -like 'FazeMES reset checks 空格 *') {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force -ErrorAction SilentlyContinue
    }
}
