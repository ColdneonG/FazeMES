#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$MysqlClient = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    [string]$ClientOptions
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $ClientOptions) { $ClientOptions = Join-Path $projectRoot '.runtime\mysql-client.local.ini' }
if (-not (Test-Path -LiteralPath $ClientOptions)) { throw 'Initialize the local database before applying migrations.' }
function Invoke-MigrationSql([string]$Sql) {
    $result = & $MysqlClient "--defaults-extra-file=$ClientOptions" --database=yudao_mes --batch --skip-column-names "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) { throw 'Local database migration failed. Check the preceding SQL diagnostic.' }
    return $result
}
Invoke-MigrationSql 'CREATE TABLE IF NOT EXISTS fazemes_schema_migration (name VARCHAR(255) PRIMARY KEY, sha256 CHAR(64) NOT NULL, applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)' | Out-Null
foreach ($migration in (Get-ChildItem -LiteralPath (Join-Path $projectRoot 'database\migrations') -Filter '*.sql' -File | Sort-Object Name)) {
    $migrationName = $migration.Name.Replace("'", "''")
    $digest = (Get-FileHash -LiteralPath $migration.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $previous = Invoke-MigrationSql "SELECT sha256 FROM fazemes_schema_migration WHERE name='$migrationName'"
    if ($previous) {
        if ($previous.Trim() -ne $digest) { throw "Applied migration changed: $($migration.Name). Add a new migration instead." }
        continue
    }
    $migrationPath = $migration.FullName.Replace('\', '/')
    Invoke-MigrationSql "source $migrationPath" | Out-Null
    Invoke-MigrationSql "INSERT INTO fazemes_schema_migration(name,sha256) VALUES('$migrationName','$digest')" | Out-Null
    Write-Host "Applied $($migration.Name)"
}
