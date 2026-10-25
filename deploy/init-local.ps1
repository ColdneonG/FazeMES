#requires -Version 7.0
[CmdletBinding()]
param([switch]$CoreOnly,
      [string]$MysqlClient = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
      [string]$MysqlServer = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe',
      [string]$ExistingMysqlSecrets)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot '.runtime'
. (Join-Path $PSScriptRoot 'local-tools.ps1')
$maintenanceLease = Enter-LocalMaintenanceLock -ProjectRoot $projectRoot
try {
New-Item -ItemType Directory -Force -Path $runtimeRoot,(Join-Path $runtimeRoot 'logs'),(Join-Path $runtimeRoot 'redis'),(Join-Path $runtimeRoot 'uploads') | Out-Null
$secretPath = Join-Path $runtimeRoot 'secrets.local.json'
if (-not (Test-Path -LiteralPath $secretPath)) {
    $taskSecrets = @{ DbUsername='yudao_mes_dev'; DbPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant(); RedisPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant(); AdminUsername='admin'; AdminPassword='MesLocal@2026'; MysqlPort=3308; RedisPort=6381 }
    $taskSecrets | ConvertTo-Json | Set-Content -LiteralPath $secretPath -Encoding utf8NoBOM
} else { $taskSecrets = Get-Content -LiteralPath $secretPath -Raw | ConvertFrom-Json -AsHashtable }
if ($ExistingMysqlSecrets) {
    if (-not (Test-Path -LiteralPath $ExistingMysqlSecrets)) { throw 'The supplied external MySQL credential file does not exist.' }
    $existingSecrets = Get-Content -LiteralPath $ExistingMysqlSecrets -Raw | ConvertFrom-Json
    $mysqlAdminPassword = $existingSecrets.MysqlRootPassword
} else {
    if (-not $taskSecrets.MysqlRootPassword) {
        $taskSecrets.MysqlRootPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
        $taskSecrets | ConvertTo-Json | Set-Content -LiteralPath $secretPath -Encoding utf8NoBOM
    }
    $mysqlAdminPassword = $taskSecrets.MysqlRootPassword
    $mysqlData = Join-Path $runtimeRoot 'mysql\data'
    $mysqlPidPath = Join-Path $runtimeRoot 'mysql.pid'
    $mysqlProbe = [Net.Sockets.TcpClient]::new()
    try { $mysqlListening = $mysqlProbe.ConnectAsync('127.0.0.1',[int]$taskSecrets.MysqlPort).Wait(500) -and $mysqlProbe.Connected }
    catch { $mysqlListening=$false } finally { $mysqlProbe.Dispose() }
    if ($mysqlListening) {
        if (-not (Test-Path -LiteralPath $mysqlPidPath)) { throw 'MySQL development port is occupied by an unmanaged process; it has not been changed.' }
        $mysqlRecordedPid = [int](Get-Content -LiteralPath $mysqlPidPath -Raw).Trim()
        $mysqlRecordedProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$mysqlRecordedPid" -ErrorAction SilentlyContinue
        if (-not $mysqlRecordedProcess -or -not $mysqlRecordedProcess.CommandLine.Contains($mysqlData)) { throw 'MySQL process ownership check failed; it has not been changed.' }
    } else {
        if (-not (Test-Path -LiteralPath $MysqlServer)) { throw "MySQL server executable not found: $MysqlServer" }
        $newMysqlData = -not (Test-Path -LiteralPath (Join-Path $mysqlData 'auto.cnf'))
        if ($newMysqlData) {
            if ((Test-Path -LiteralPath $mysqlData) -and @(Get-ChildItem -LiteralPath $mysqlData -Force).Count -gt 0) { throw 'Local MySQL directory is nonempty but incomplete; it has not been overwritten.' }
            New-Item -ItemType Directory -Force -Path $mysqlData | Out-Null
            & $MysqlServer --no-defaults --initialize-insecure "--datadir=$mysqlData" --console *> (Join-Path $runtimeRoot 'logs\mysql-init.log')
            if ($LASTEXITCODE -ne 0) { throw 'Local MySQL initialization failed; check logs/mysql-init.log.' }
        }
        $mysqlProcess = Start-Process -FilePath $MysqlServer -ArgumentList @('--no-defaults',"--datadir=$mysqlData","--port=$($taskSecrets.MysqlPort)",'--bind-address=127.0.0.1','--mysqlx=0','--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci',("--log-error="+(Join-Path $runtimeRoot 'logs\mysql-error.log'))) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeRoot 'logs\mysql.stdout.log') -RedirectStandardError (Join-Path $runtimeRoot 'logs\mysql.stderr.log')
        Set-Content -LiteralPath $mysqlPidPath -Value $mysqlProcess.Id
        $mysqlReady=$false
        for ($attempt=0; $attempt -lt 120; $attempt++) {
            $mysqlProbe = [Net.Sockets.TcpClient]::new()
            try { if ($mysqlProbe.ConnectAsync('127.0.0.1',[int]$taskSecrets.MysqlPort).Wait(300) -and $mysqlProbe.Connected) { $mysqlReady=$true; break } }
            catch { } finally { $mysqlProbe.Dispose() }
            if ($mysqlProcess.HasExited) { throw 'Local MySQL exited; check its log.' }
            Start-Sleep -Milliseconds 250
        }
        if (-not $mysqlReady) { throw 'Local MySQL did not become ready.' }
        if ($newMysqlData) {
            $mysqlRootSetup = Join-Path $runtimeRoot 'mysql-root-setup.local.sql'
            "ALTER USER 'root'@'localhost' IDENTIFIED BY '$mysqlAdminPassword';" | Set-Content -LiteralPath $mysqlRootSetup -Encoding utf8NoBOM
            & $MysqlClient --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$($taskSecrets.MysqlPort)" --user=root "--execute=source $($mysqlRootSetup.Replace('\','/'))"
            if ($LASTEXITCODE -ne 0) { throw 'Local MySQL root account setup failed.' }
        }
    }
}
$adminOptions = Join-Path $runtimeRoot 'mysql-admin.local.ini'
"[client]`nhost=127.0.0.1`nport=$($taskSecrets.MysqlPort)`nprotocol=TCP`nuser=root`npassword=$mysqlAdminPassword`ndefault-character-set=utf8mb4" | Set-Content -LiteralPath $adminOptions -Encoding utf8NoBOM
function Invoke-AdminSql([string]$Sql) {
    $taskResult = & $MysqlClient "--defaults-extra-file=$adminOptions" --batch --skip-column-names "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) { throw 'MySQL admin command failed. Existing databases have not been reset.' }
    return $taskResult
}
$bootstrap = Join-Path $runtimeRoot 'bootstrap.local.sql'
@"
CREATE DATABASE IF NOT EXISTS yudao_mes CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$($taskSecrets.DbUsername)'@'127.0.0.1' IDENTIFIED BY '$($taskSecrets.DbPassword)';
CREATE USER IF NOT EXISTS '$($taskSecrets.DbUsername)'@'localhost' IDENTIFIED BY '$($taskSecrets.DbPassword)';
GRANT ALL PRIVILEGES ON yudao_mes.* TO '$($taskSecrets.DbUsername)'@'127.0.0.1';
GRANT ALL PRIVILEGES ON yudao_mes.* TO '$($taskSecrets.DbUsername)'@'localhost';
"@ | Set-Content -LiteralPath $bootstrap -Encoding utf8NoBOM
Invoke-AdminSql "source $($bootstrap.Replace('\','/'))" | Out-Null
$clientOptions = Join-Path $runtimeRoot 'mysql-client.local.ini'
"[client]`nhost=127.0.0.1`nport=$($taskSecrets.MysqlPort)`nprotocol=TCP`nuser=$($taskSecrets.DbUsername)`npassword=$($taskSecrets.DbPassword)`ndefault-character-set=utf8mb4" | Set-Content -LiteralPath $clientOptions -Encoding utf8NoBOM
function Invoke-LocalSql([string]$Sql) {
    $taskResult = & $MysqlClient "--defaults-extra-file=$clientOptions" --database=yudao_mes --batch --skip-column-names "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) { throw 'MySQL development database command failed.' }
    return $taskResult
}
$systemExists = [int](Invoke-LocalSql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='yudao_mes' AND table_name='system_users'")
if ($systemExists -eq 0) {
    $tableCount = [int](Invoke-LocalSql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='yudao_mes'")
    if ($tableCount -ne 0) { throw 'The dedicated yudao_mes database is not empty and lacks system_users. It has not been overwritten.' }
    $baseSql = Join-Path $projectRoot 'backend\sql\mysql\ruoyi-vue-pro.sql'
    Invoke-LocalSql "source $($baseSql.Replace('\','/'))" | Out-Null
    Write-Host 'Imported upstream system schema into the new yudao_mes database.'
}
if ($systemExists -eq 0 -or -not (Test-Path -LiteralPath (Join-Path $runtimeRoot 'admin-initialized'))) {
    $env:YUDAO_LOCAL_ADMIN_PASSWORD = $taskSecrets.AdminPassword
    try { $passwordHash = & python -X utf8 -c 'import bcrypt,os; print(bcrypt.hashpw(os.environ["YUDAO_LOCAL_ADMIN_PASSWORD"].encode(),bcrypt.gensalt()).decode())'; if ($LASTEXITCODE -ne 0) { throw 'Python bcrypt is required to initialize the local admin password.' } }
    finally { Remove-Item Env:YUDAO_LOCAL_ADMIN_PASSWORD -ErrorAction SilentlyContinue }
    $localSql = Join-Path $runtimeRoot 'admin-setup.local.sql'
    @"
UPDATE system_users SET password='$passwordHash', nickname='示例管理员', avatar='http://127.0.0.1:5173/placeholder-avatar.svg' WHERE id=1 AND tenant_id=1;
UPDATE system_tenant SET websites='127.0.0.1:5173,localhost:5173', expire_time='2099-12-31 23:59:59' WHERE id=1;
"@ | Set-Content -LiteralPath $localSql -Encoding utf8NoBOM
    Invoke-LocalSql "source $($localSql.Replace('\','/'))" | Out-Null
    Set-Content -LiteralPath (Join-Path $runtimeRoot 'admin-initialized') -Value (Get-Date -Format o)
}
Invoke-LocalSql "UPDATE system_menu SET status=CASE WHEN path IN ('/mes','/system','/infra') THEN 0 ELSE 1 END WHERE parent_id=0" | Out-Null
$localFileConfig = @{ '@class'='cn.iocoder.yudao.module.infra.framework.file.core.client.local.LocalFileClientConfig'; basePath=(Join-Path $runtimeRoot 'uploads').Replace('\','/'); domain='http://127.0.0.1:48080' } | ConvertTo-Json -Compress
$fileSql = Join-Path $runtimeRoot 'file-storage.local.sql'
@"
UPDATE infra_file_config SET master=b'0' WHERE master=b'1';
UPDATE infra_file_config SET name='示例本地附件', storage=10, master=b'1', config='$localFileConfig' WHERE id=29;
"@ | Set-Content -LiteralPath $fileSql -Encoding utf8NoBOM
Invoke-LocalSql "source $($fileSql.Replace('\','/'))" | Out-Null
if (-not $CoreOnly) {
    foreach ($sqlName in @('mes_schema.sql','mes_seed.sql')) {
        $sqlPath = Join-Path $projectRoot "database\init\$sqlName"
        if (-not (Test-Path -LiteralPath $sqlPath)) { throw "Missing generated SQL: $sqlPath" }
        Invoke-LocalSql "source $($sqlPath.Replace('\','/'))" | Out-Null
        Write-Host "Imported $sqlName"
    }
}
& (Join-Path $PSScriptRoot 'apply-migrations.ps1') -MysqlClient $MysqlClient -ClientOptions $clientOptions
$jdbcUrl = "jdbc:mysql://127.0.0.1:$($taskSecrets.MysqlPort)/yudao_mes?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true&rewriteBatchedStatements=true"
$taskConfig = @"
server:
  address: 127.0.0.1
  port: 48080
spring:
  quartz:
    auto-startup: true
    scheduler-name: FazeMESLocal
    job-store-type: jdbc
    wait-for-jobs-to-complete-on-shutdown: true
    jdbc:
      initialize-schema: never
    properties:
      org.quartz.scheduler.instanceId: AUTO
      org.quartz.jobStore.class: org.springframework.scheduling.quartz.LocalDataSourceJobStore
      org.quartz.jobStore.driverDelegateClass: org.quartz.impl.jdbcjobstore.StdJDBCDelegate
      org.quartz.jobStore.isClustered: false
      org.quartz.jobStore.misfireThreshold: 60000
      org.quartz.threadPool.threadCount: 3
  autoconfigure:
    exclude:
      - cn.iocoder.yudao.framework.banner.config.YudaoBannerAutoConfiguration
  datasource:
    dynamic:
      primary: master
      datasource:
        master:
          name: yudao_mes
          url: '$jdbcUrl'
          username: '$($taskSecrets.DbUsername)'
          password: '$($taskSecrets.DbPassword)'
      druid:
        filters: stat
        initial-size: 1
        min-idle: 1
        max-active: 10
    druid:
      stat-view-servlet:
        enabled: false
  data:
    redis:
      host: 127.0.0.1
      port: $($taskSecrets.RedisPort)
      password: '$($taskSecrets.RedisPassword)'
      database: 0
  boot:
    admin:
      client:
        enabled: false
justauth:
  enabled: false
wx:
  mp:
    appid: local-mes-disabled
    secret: local-mes-disabled
  miniapp:
    appid: local-mes-disabled
    secret: local-mes-disabled
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never
yudao:
  swagger:
    title: FazeMES
    description: FazeMES 生产执行管理接口
    url: http://127.0.0.1:5173
    email: admin@example.com
  web:
    admin-ui:
      url: http://127.0.0.1:5173
  captcha:
    enable: false
  security:
    mock-enable: false
  demo: false
  tenant:
    enable: true
  api-encrypt:
    enable: false
  access-log:
    enable: false
logging:
  file:
    name: '$($runtimeRoot.Replace('\','/'))/logs/yudao-mes.log'
"@
$taskConfig | Set-Content -LiteralPath (Join-Path $runtimeRoot 'application-meslocal.yaml') -Encoding utf8NoBOM
@('bind 127.0.0.1','protected-mode yes',"port $($taskSecrets.RedisPort)","requirepass $($taskSecrets.RedisPassword)",('dir "' + (Join-Path $runtimeRoot 'redis').Replace('\','/') + '"'),'save ""','appendonly no','maxmemory 128mb') | Set-Content -LiteralPath (Join-Path $runtimeRoot 'redis.local.conf') -Encoding utf8NoBOM
} finally { Exit-LocalMaintenanceLock -Lease $maintenanceLease }
Write-Host 'Local configuration ready. Database yudao_mes; Redis 6381; API 48080; UI 5173.'
