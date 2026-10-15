param([string]$User = 'root')

$ErrorActionPreference = 'Stop'
$database = 'campus_activity_miniapp_dev'
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$clone = "campus_activity_miniapp_stage2_rehearsal_$stamp"
$backupDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'backups'
$dumpPath = Join-Path $backupDir "${database}_before_live_stage2_$stamp.sql"
$liveStarted = $false
$expectedTables = 'activities,activity_audits,activity_feedback,announcements,checkins,miniapp_access_tokens,registrations,user_accounts,user_messages,user_roles,user_security_audits,wechat_student_bindings'

foreach ($port in @(8080, 18080, 18081)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "Application port $port is listening; stop real-database writers before migration."
    }
}

$jarCandidates = @(
    (Join-Path $PSScriptRoot 'target/campus-activity-0.0.1-SNAPSHOT.jar'),
    (Join-Path $PSScriptRoot 'target/campus-activity-preview-next.jar')
)
$jar = $jarCandidates | Where-Object { Test-Path -LiteralPath $_ } |
    Sort-Object { (Get-Item -LiteralPath $_).LastWriteTimeUtc } -Descending |
    Where-Object {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($_)
        try { $null -ne $archive.GetEntry('org/springframework/boot/loader/launch/JarLauncher.class') }
        finally { $archive.Dispose() }
    } | Select-Object -First 1
if (!$jar) { throw 'A complete Spring Boot backend jar is required before migration.' }

$password = Read-Host 'MySQL password (hidden)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
try { $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }

try {
    $mysql = (Get-Command mysql.exe -CommandType Application).Source
    $mysqldump = (Get-Command mysqldump.exe -CommandType Application).Source
    $common = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=3306', "--user=$User", '--default-character-set=utf8mb4', '--batch', '--skip-column-names')

    function Invoke-Scalar([string]$Name, [string]$Sql) {
        $result = & $mysql @common "--database=$Name" "--execute=$Sql"
        if ($LASTEXITCODE -ne 0) { throw "MySQL query failed against $Name." }
        return ($result | Out-String).Trim()
    }
    function Invoke-SqlFile([string]$Name, [string]$File) {
        Get-Content -LiteralPath $File -Raw | & $mysql @common "--database=$Name"
        if ($LASTEXITCODE -ne 0) { throw "SQL failed against $Name in $(Split-Path $File -Leaf)." }
    }
    function Assert-Quiescent {
        if ((Invoke-Scalar $database "SELECT COUNT(*) FROM information_schema.processlist WHERE DB = '$database' AND ID <> CONNECTION_ID();") -ne '0') {
            throw 'Other MySQL sessions are using the real database; no live DDL will run.'
        }
    }
    function Row-Counts([string]$Name) {
        return Invoke-Scalar $Name @'
SELECT 'activities', COUNT(*) FROM activities
UNION ALL SELECT 'activity_audits', COUNT(*) FROM activity_audits
UNION ALL SELECT 'activity_feedback', COUNT(*) FROM activity_feedback
UNION ALL SELECT 'announcements', COUNT(*) FROM announcements
UNION ALL SELECT 'checkins', COUNT(*) FROM checkins
UNION ALL SELECT 'miniapp_access_tokens', COUNT(*) FROM miniapp_access_tokens
UNION ALL SELECT 'registrations', COUNT(*) FROM registrations
UNION ALL SELECT 'user_accounts', COUNT(*) FROM user_accounts
UNION ALL SELECT 'user_messages', COUNT(*) FROM user_messages
UNION ALL SELECT 'user_roles', COUNT(*) FROM user_roles
UNION ALL SELECT 'user_security_audits', COUNT(*) FROM user_security_audits
UNION ALL SELECT 'wechat_student_bindings', COUNT(*) FROM wechat_student_bindings;
'@
    }
    function Assert-PostMigration([string]$Name, [string]$Before, [string]$ExpectedIdentities) {
        if ((Row-Counts $Name) -cne $Before) { throw "Original data row counts changed in $Name." }
        if ((Invoke-Scalar $Name "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';") -ne '19') {
            throw "Expected 19 tables in $Name after migration."
        }
        $actual = Invoke-Scalar $Name @'
SELECT
  (SELECT COUNT(*) FROM wechat_student_bindings WHERE identity_id IS NULL),
  (SELECT COUNT(*) FROM wechat_identities),
  (SELECT COUNT(*) FROM registrations WHERE identity_id IS NOT NULL OR binding_id IS NOT NULL),
  (SELECT COUNT(*) FROM checkins WHERE identity_id IS NOT NULL OR binding_id IS NOT NULL),
  (SELECT COUNT(*) FROM user_messages WHERE identity_id IS NOT NULL),
  (SELECT COUNT(*) FROM binding_appeals),
  (SELECT COUNT(*) FROM participation_export_audits);
'@
        if ($actual -cne "0`t$ExpectedIdentities`t0`t0`t0`t0`t0") {
            throw "Post-migration identity/history checks failed in ${Name}: $actual"
        }
    }
    function Test-AppMapping([string]$Name, [bool]$IsClone) {
        $env:DB_URL = "jdbc:mysql://127.0.0.1:3306/$Name`?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
        $env:DB_USER = $User
        $env:DB_PASSWORD = $env:MYSQL_PWD
        $env:SPRING_PROFILES_ACTIVE = 'mysql'
        $env:SPRING_MAIN_WEB_APPLICATION_TYPE = 'none'
        $env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
        $env:APP_BOOTSTRAP_ADMIN_EMAIL = ''
        $env:APP_BOOTSTRAP_ADMIN_PASSWORD = ''
        if ($IsClone) { $env:STAGE2_CLONE_VALIDATION = 'true' }
        else { Remove-Item Env:STAGE2_CLONE_VALIDATION -ErrorAction SilentlyContinue }
        try {
            & java -jar $jar --logging.level.root=ERROR
            if ($LASTEXITCODE -ne 0) { throw "Backend JPA validation failed for $Name." }
        } finally {
            Remove-Item Env:DB_URL, Env:DB_USER, Env:DB_PASSWORD, Env:SPRING_PROFILES_ACTIVE, `
                Env:SPRING_MAIN_WEB_APPLICATION_TYPE, Env:SPRING_JPA_HIBERNATE_DDL_AUTO, `
                Env:APP_BOOTSTRAP_ADMIN_EMAIL, Env:APP_BOOTSTRAP_ADMIN_PASSWORD, `
                Env:STAGE2_CLONE_VALIDATION -ErrorAction SilentlyContinue
        }
    }

    if ((Invoke-Scalar $database 'SELECT DATABASE();') -cne $database) { throw 'Real database target guard failed.' }
    Assert-Quiescent
    $tables = Invoke-Scalar $database "SELECT GROUP_CONCAT(table_name ORDER BY table_name SEPARATOR ',') FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';"
    if ($tables -cne $expectedTables) { throw "Real database is not the expected untouched 12-table schema: $tables" }
    $oldIndexes = Invoke-Scalar $database @'
SELECT COUNT(*) FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'wechat_student_bindings'
  AND index_name IN ('uk_wechat_binding_app_openid', 'uk_wechat_binding_app_student');
'@
    if ($oldIndexes -ne '4') { throw "Original binding indexes differ; found $oldIndexes columns." }
    $conflicts = Invoke-Scalar $database @'
SELECT
  (SELECT COUNT(*) FROM (SELECT app_id, open_id FROM wechat_student_bindings GROUP BY app_id, open_id HAVING COUNT(*) > 1) x),
  (SELECT COUNT(*) FROM (SELECT app_id, student_id FROM wechat_student_bindings GROUP BY app_id, student_id HAVING COUNT(*) > 1) x),
  (SELECT COUNT(*) FROM checkins c LEFT JOIN registrations r ON r.activity_id=c.activity_id AND r.student_id=c.student_id WHERE r.id IS NULL);
'@
    if ($conflicts -cne "0`t0`t0") { throw "Binding/participation conflicts need review before migration: $conflicts" }
    $before = Row-Counts $database
    $identityCount = Invoke-Scalar $database 'SELECT COUNT(DISTINCT app_id, open_id) FROM wechat_student_bindings;'
    $cloneExists = Invoke-Scalar $database "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$clone';"
    if ($cloneExists -ne '0') { throw 'Fresh rehearsal clone name already exists.' }

    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    & $mysqldump --no-defaults --protocol=TCP --host=127.0.0.1 --port=3306 "--user=$User" `
        --default-character-set=utf8mb4 --single-transaction --quick --skip-lock-tables `
        --skip-add-drop-table --routines --triggers --events --hex-blob --set-gtid-purged=OFF `
        "--result-file=$dumpPath" $database
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $dumpPath) -or (Get-Item -LiteralPath $dumpPath).Length -lt 1024) {
        throw 'Fresh full backup failed or is unexpectedly small. Real database was not changed.'
    }
    $hash = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash
    if ((Row-Counts $database) -cne $before) { throw 'Real data changed while backing up; no migration was applied.' }
    Write-Host "Fresh backup: $dumpPath"
    Write-Host "Backup bytes: $((Get-Item -LiteralPath $dumpPath).Length)"
    Write-Host "Backup SHA-256: $hash"

    & $mysql @common "--database=$database" "--execute=CREATE DATABASE ``$clone`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh rehearsal clone. Real database was not changed.' }
    Get-Content -LiteralPath $dumpPath -Raw | & $mysql @common "--database=$clone"
    if ($LASTEXITCODE -ne 0 -or (Row-Counts $clone) -cne $before) {
        throw 'Fresh backup restore/count verification failed. Real database was not changed.'
    }
    Write-Host "Fresh rehearsal clone restored and row counts match: $clone"

    $groups = @(
        'stage2_rehearsal_A_identity.sql',
        'stage2_rehearsal_B_appeals.sql',
        'stage2_rehearsal_C_changes_exports.sql',
        'stage2_rehearsal_D_message_provenance.sql'
    )
    foreach ($group in $groups) { Invoke-SqlFile $clone (Join-Path $PSScriptRoot "migrations/$group") }
    Assert-PostMigration $clone $before $identityCount
    Test-AppMapping $clone $true
    Write-Host 'Fresh clone A-D migration and backend JPA validation passed.'

    Assert-Quiescent
    if ((Row-Counts $database) -cne $before -or
        (Invoke-Scalar $database "SELECT GROUP_CONCAT(table_name ORDER BY table_name SEPARATOR ',') FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';") -cne $expectedTables) {
        throw 'Real database changed during rehearsal. No live DDL was applied.'
    }
    $liveStarted = $true
    foreach ($group in $groups) {
        Write-Host "Applying live Stage 2 group: $group"
        Invoke-SqlFile $database (Join-Path $PSScriptRoot "migrations/$group")
    }
    Assert-PostMigration $database $before $identityCount
    Test-AppMapping $database $false
    Write-Host 'LIVE STAGE 2 MIGRATION VERIFIED. V9 was not applied.'
    Write-Host "Rollback source (manual recovery only): $dumpPath"
} catch {
    if ($liveStarted) {
        Write-Warning "LIVE DDL MAY BE PARTIAL. Do not rerun or restore automatically. Preserve the backup: $dumpPath"
    }
    throw
} finally {
    Remove-Item Env:MYSQL_PWD, Env:DB_URL, Env:DB_USER, Env:DB_PASSWORD, `
        Env:SPRING_PROFILES_ACTIVE, Env:SPRING_MAIN_WEB_APPLICATION_TYPE, `
        Env:SPRING_JPA_HIBERNATE_DDL_AUTO, Env:APP_BOOTSTRAP_ADMIN_EMAIL, `
        Env:APP_BOOTSTRAP_ADMIN_PASSWORD, Env:STAGE2_CLONE_VALIDATION -ErrorAction SilentlyContinue
}
