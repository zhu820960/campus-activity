param(
    [string]$User = 'root',
    [string]$RehearsalDatabase = 'campus_activity_miniapp_stage2_rehearsal_20261013_113503'
)

$ErrorActionPreference = 'Stop'
if ($RehearsalDatabase -notmatch '^campus_activity_miniapp_stage2_rehearsal_[0-9]{8}_[0-9]{6}$') {
    throw 'Only a named independent stage 2 rehearsal clone may be extended.'
}
$jar = Join-Path $PSScriptRoot 'target/campus-activity-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Build the current backend jar first.' }
$password = Read-Host 'MySQL password (hidden)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
try {
    $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}

try {
    $mysql = (Get-Command mysql.exe -CommandType Application).Source
    $common = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=3306',
        "--user=$User", '--default-character-set=utf8mb4', "--database=$RehearsalDatabase",
        '--batch', '--skip-column-names')
    function Invoke-Scalar([string]$Sql) {
        $output = & $mysql @common "--execute=$Sql"
        if ($LASTEXITCODE -ne 0) { throw 'Clone verification query failed.' }
        return ($output | Out-String).Trim()
    }
    if ((Invoke-Scalar 'SELECT DATABASE();') -cne $RehearsalDatabase) {
        throw 'Database guard failed; no DDL was applied.'
    }
    $before = Invoke-Scalar @'
SELECT (SELECT COUNT(*) FROM user_messages),
       (SELECT COUNT(*) FROM wechat_identities),
       (SELECT COUNT(*) FROM registrations),
       (SELECT COUNT(*) FROM checkins),
       (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE');
'@
    $columns = Invoke-Scalar @'
SELECT COUNT(*) FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'user_messages' AND column_name = 'identity_id';
'@
    if (($before -split "`t")[-1] -ne '19' -or $columns -ne '0') {
        throw 'Expected the verified A/B/C clone without message identity column; no DDL was applied.'
    }
    $script = Join-Path $PSScriptRoot 'migrations/stage2_rehearsal_D_message_provenance.sql'
    Write-Host "Applying D message provenance to independent clone: $RehearsalDatabase"
    Get-Content -LiteralPath $script -Raw | & $mysql @common
    if ($LASTEXITCODE -ne 0) { throw 'D group failed; inspect clone before any retry.' }
    $after = Invoke-Scalar @'
SELECT (SELECT COUNT(*) FROM user_messages),
       (SELECT COUNT(*) FROM wechat_identities),
       (SELECT COUNT(*) FROM registrations),
       (SELECT COUNT(*) FROM checkins),
       (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE');
'@
    $provenance = Invoke-Scalar 'SELECT COUNT(*) FROM user_messages WHERE identity_id IS NOT NULL;'
    if ($before -cne $after -or $provenance -ne '0') {
        throw 'Clone row counts changed or old messages were attributed; stop for inspection.'
    }
    Write-Host 'D group passed; prior table counts unchanged and historical message provenance remains NULL.'

    $env:DB_URL = "jdbc:mysql://127.0.0.1:3306/$RehearsalDatabase`?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
    $env:DB_USER = $User
    $env:DB_PASSWORD = $env:MYSQL_PWD
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    $env:SPRING_MAIN_WEB_APPLICATION_TYPE = 'none'
    $env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
    $env:STAGE2_CLONE_VALIDATION = 'true'
    $env:APP_BOOTSTRAP_ADMIN_EMAIL = ''
    $env:APP_BOOTSTRAP_ADMIN_PASSWORD = ''
    Remove-Item Env:MYSQL_PWD
    Write-Host 'Validating current backend against the extended independent clone.'
    & java -jar $jar --logging.level.root=ERROR
    if ($LASTEXITCODE -ne 0) { throw 'Current backend JPA validation failed; D group remains on the clone.' }
    Write-Host 'Message provenance clone JPA validation passed. Existing services were not touched.'
} finally {
    Remove-Item Env:MYSQL_PWD, Env:DB_PASSWORD, Env:DB_URL, Env:DB_USER, `
        Env:SPRING_PROFILES_ACTIVE, Env:SPRING_MAIN_WEB_APPLICATION_TYPE, `
        Env:SPRING_JPA_HIBERNATE_DDL_AUTO, Env:APP_BOOTSTRAP_ADMIN_EMAIL, `
        Env:APP_BOOTSTRAP_ADMIN_PASSWORD, Env:STAGE2_CLONE_VALIDATION -ErrorAction SilentlyContinue
}
