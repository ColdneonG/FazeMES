#requires -Version 7.0
<#!
.SYNOPSIS
Prepare the local runtime, rebuild comprehensive test data, then start FazeMES.
.DESCRIPTION
Explorer entry point: the repository-root 重建数据库并启动.cmd. No confirmation
is required. Uses the existing local-only backup/reset and managed startup paths.
#>
[CmdletBinding()]
param([switch]$CheckOnly,[switch]$NoBrowser,[switch]$Build)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot '.runtime'
. (Join-Path $PSScriptRoot 'local-tools.ps1')

function Add-ProcessToolDirectory([string]$Directory) {
    if ($Directory -and (Test-Path -LiteralPath $Directory -PathType Container)) {
        $env:PATH = "$Directory;$env:PATH"
    }
}

function Initialize-LauncherEnvironment {
    $env:PYTHONUTF8 = '1'
    $env:PYTHONIOENCODING = 'utf-8'
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    Add-ProcessToolDirectory (Join-Path $runtimeRoot 'tools-python\Scripts')
    # Explorer may inherit a PATH older than tool installation. These fallbacks
    # affect only this script and its child services, never user/system settings.
    $pythonCommand = Get-Command python.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $pythonCommand -or $pythonCommand.Source -like '*\Microsoft\WindowsApps\*') {
        $pythonRoot = Join-Path $env:LOCALAPPDATA 'Programs\Python'
        $installedPython = @(Get-ChildItem -LiteralPath $pythonRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^Python3(\d+)$' -and [int]$Matches[1] -ge 12 -and (Test-Path -LiteralPath (Join-Path $_.FullName 'python.exe')) } |
            Sort-Object { [int]($_.Name -replace '^Python3','') } -Descending)
        if ($installedPython.Count) { Add-ProcessToolDirectory $installedPython[0].FullName }
    }
    if (-not (Get-Command node.exe -CommandType Application -ErrorAction SilentlyContinue)) {
        Add-ProcessToolDirectory (Join-Path $env:ProgramFiles 'nodejs')
    }
    if (-not $env:JAVA_HOME -and -not (Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue)) {
        $jdkCandidates = @()
        foreach ($parent in @('Microsoft','Java','Eclipse Adoptium','Amazon Corretto','Zulu')) {
            $jdkCandidates += @(Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles $parent) -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '(jdk[-_]?17|jdk17|zulu[-_]?17)' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')) })
        }
        if ($jdkCandidates.Count) {
            $env:JAVA_HOME = ($jdkCandidates | Sort-Object Name -Descending | Select-Object -First 1).FullName
            Add-ProcessToolDirectory (Join-Path $env:JAVA_HOME 'bin')
        }
    }
}

function Test-LauncherPrerequisites {
    $python = Resolve-LocalTool -Name python.exe
    $java = Resolve-LocalTool -Name java.exe -HomeVariable JAVA_HOME -HomeRelativePath 'bin\java.exe'
    $node = Resolve-LocalTool -Name node.exe
    & $python -c 'import sys; sys.exit(0 if sys.version_info >= (3,12) else 1)'
    if ($LASTEXITCODE -ne 0) { throw '需要 Python 3.12 或更新版本。' }
    $javaVersion = (& $java -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "17\.') { throw '需要 JDK 17；请检查 JAVA_HOME 或 PATH。' }
    $nodeVersion = & $node --version
    if ($LASTEXITCODE -ne 0 -or [Version]($nodeVersion.TrimStart('v')) -lt [Version]'22.13.0') { throw '需要 Node.js 22.13.0 或更新版本。' }
    foreach ($path in @('C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
                       'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe',
                       'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe',
                       (Join-Path $projectRoot 'frontend\node_modules\vite\bin\vite.js'),
                       (Join-Path $projectRoot 'database\init\mes_seed.sql'),
                       (Join-Path $PSScriptRoot 'reset-test-data.ps1'),
                       (Join-Path $projectRoot 'database\tools\testdata_backups.py'))) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "缺少必要文件：$path" }
    }
    & $python -c 'import bcrypt, pymysql' 2>$null
    if ($LASTEXITCODE -ne 0) {
        if ($CheckOnly) { throw '缺少 Python 依赖；正式运行将自动安装 deploy/requirements.txt。' }
        Write-Host '正在安装数据库初始化所需的 Python 依赖……'
        & $python -m pip install -r (Join-Path $PSScriptRoot 'requirements.txt')
        if ($LASTEXITCODE -ne 0) { throw 'Python 依赖安装失败；尚未重建数据库。' }
    }
    return $python
}

Initialize-LauncherEnvironment
if ($CheckOnly) {
    $null = Test-LauncherPrerequisites
    Write-Host '环境检查通过；未启动服务或修改数据库。' -ForegroundColor Green
    return
}

$lockName = 'Local\FazeMES-' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($projectRoot))).Substring(0,16)
$startupLock = [Threading.Mutex]::new($false,$lockName)
$held = $false
$transcribing = $false
$previousLocation = Get-Location
try {
    try { $held = $startupLock.WaitOne(0) } catch [Threading.AbandonedMutexException] { $held = $true }
    if (-not $held) { throw '项目正在另一个窗口中启动或重建，请等待该窗口完成。' }
    Set-Location -LiteralPath $projectRoot
    $logRoot = Join-Path $runtimeRoot 'logs'
    New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
    $logPath = Join-Path $logRoot ('reset-and-start-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,6) + '.log')
    Start-Transcript -LiteralPath $logPath -Force | Out-Null
    $transcribing = $true
    Write-Host 'FazeMES 一键重建数据库并启动' -ForegroundColor Cyan
    Write-Host '将先备份本机数据库，再重新生成完整测试数据。管理员密码和系统配置保留。'
    Write-Host "运行日志：$logPath"
    Write-Host '[1/4] 检查运行环境'
    $null = Test-LauncherPrerequisites
    $jar = Join-Path $projectRoot 'backend\yudao-server\target\yudao-server.jar'
    $installed = (Test-Path -LiteralPath (Join-Path $runtimeRoot 'mysql\data\auto.cnf')) -and
                 (Test-Path -LiteralPath (Join-Path $runtimeRoot 'application-meslocal.yaml'))
    $needsBuild = $Build.IsPresent -or -not (Test-LocalBackendJar -JarPath $jar)
    if ($needsBuild) { $null = Resolve-LocalTool -Name mvn.cmd }
    Write-Host '[2/4] 准备本机数据库及缓存服务'
    if (-not $installed -or $needsBuild) {
        & (Join-Path $PSScriptRoot 'start-local.ps1') -Build:$needsBuild
    } else {
        & (Join-Path $PSScriptRoot 'resume-local.ps1') -DependenciesOnly
    }
    Write-Host '[3/4] 备份、重建测试数据并执行验收（需要数分钟）'
    & (Join-Path $PSScriptRoot 'reset-test-data.ps1') -DeferBackupRetention
    Write-Host '[4/4] 启动前端、人脸服务并确认全部服务就绪'
    & (Join-Path $PSScriptRoot 'resume-local.ps1') -ReuseBackend
    $report = Get-Content -LiteralPath (Join-Path $runtimeRoot 'testdata-verification.json') -Raw | ConvertFrom-Json
    if ($report.result -ne 'PASS') { throw '测试数据验收未通过，请查看 .runtime/testdata-verification.json。' }
    Write-Host '全部校验及服务启动成功，整理备份：永久保留首次原库备份，后续压缩保留最近 3 份。'
    & (Join-Path $PSScriptRoot 'reset-test-data.ps1') -FinalizeBackups
    Write-Host ("完成：{0} 条 MES 数据，{1} 张数据表，{2} 项校验通过。" -f $report.totalMesRows,$report.populatedMesTables,$report.checks.Count) -ForegroundColor Green
    Write-Host '项目地址：http://127.0.0.1:5173'
    Write-Host '测试账号及密码说明：database/TEST-DATA.md'
    if (-not $NoBrowser) {
        try { Start-Process 'http://127.0.0.1:5173' | Out-Null }
        catch { Write-Host '项目已启动；请手动打开 http://127.0.0.1:5173。' -ForegroundColor Yellow }
    }
} catch {
    Write-Host ("操作失败：{0}" -f $_.Exception.Message) -ForegroundColor Red
    if ($logPath) { Write-Host "请保留此窗口并查看日志：$logPath" }
    throw
} finally {
    if ($transcribing) { Stop-Transcript | Out-Null }
    Set-Location -LiteralPath $previousLocation.Path
    if ($held) { $startupLock.ReleaseMutex() }
    $startupLock.Dispose()
}
