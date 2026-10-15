param([string]$User = 'root')

$ErrorActionPreference = 'Stop'
$database = 'campus_activity_miniapp_dev'
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$copy = "campus_activity_miniapp_stage2_rehearsal_$stamp"
$backupDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'backups'
$dumpPath = Join-Path $backupDir "${database}_before_stage2_$stamp.sql"
$password = Read-Host 'MySQL password (hidden)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
try {
    $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}

try {
    $mysql = (Get-Command mysql.exe -CommandType Application).Source
    $mysqldump = (Get-Command mysqldump.exe -CommandType Application).Source
    $common = @('--protocol=TCP', '--host=127.0.0.1', '--port=3306', "--user=$User")
    $selected = & $mysql --no-defaults @common "--database=$database" --batch --skip-column-names --execute='SELECT DATABASE();'
    if ($LASTEXITCODE -ne 0 -or $selected.Trim() -ne $database) {
        throw 'Target guard failed: selected database is not campus_activity_miniapp_dev.'
    }
    $existing = & $mysql --no-defaults @common --batch --skip-column-names `
        --execute="SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$copy';"
    if ($LASTEXITCODE -ne 0 -or $existing.Trim() -ne '0') {
        throw 'Rehearsal database already exists or its absence could not be verified.'
    }

    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    & $mysqldump --no-defaults @common --default-character-set=utf8mb4 `
        --single-transaction --quick --skip-lock-tables --skip-add-drop-table `
        --routines --triggers --events --hex-blob --set-gtid-purged=OFF `
        "--result-file=$dumpPath" $database
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $dumpPath) `
        -or (Get-Item -LiteralPath $dumpPath).Length -lt 1024) {
        throw 'Full backup failed or is unexpectedly small; rehearsal was not created.'
    }
    $hash = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash
    Write-Host "Backup: $dumpPath"
    Write-Host "Backup bytes: $((Get-Item -LiteralPath $dumpPath).Length)"
    Write-Host "Backup SHA-256: $hash"

    & $mysql --no-defaults @common --execute="CREATE DATABASE ``$copy`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create independent rehearsal database.' }
    Write-Host "Rehearsal database created: $copy"
    Get-Content -LiteralPath $dumpPath | & $mysql --no-defaults @common --default-character-set=utf8mb4 "--database=$copy"
    if ($LASTEXITCODE -ne 0) { throw "Restore failed; inspect $copy before any further action." }

    $counts = @'
SELECT 'user_accounts' AS table_name, COUNT(*) AS row_count FROM user_accounts
UNION ALL SELECT 'wechat_student_bindings', COUNT(*) FROM wechat_student_bindings
UNION ALL SELECT 'miniapp_access_tokens', COUNT(*) FROM miniapp_access_tokens
UNION ALL SELECT 'activities', COUNT(*) FROM activities
UNION ALL SELECT 'registrations', COUNT(*) FROM registrations
UNION ALL SELECT 'checkins', COUNT(*) FROM checkins
UNION ALL SELECT 'activity_audits', COUNT(*) FROM activity_audits
UNION ALL SELECT 'user_messages', COUNT(*) FROM user_messages;
'@
    $original = & $mysql --no-defaults @common "--database=$database" --batch --skip-column-names --execute=$counts
    if ($LASTEXITCODE -ne 0) { throw 'Could not count source rows.' }
    $restored = & $mysql --no-defaults @common "--database=$copy" --batch --skip-column-names --execute=$counts
    if ($LASTEXITCODE -ne 0) { throw 'Could not count rehearsal rows.' }
    if (($original -join "`n") -ne ($restored -join "`n")) {
        throw "Source and rehearsal row counts differ; inspect $copy before proceeding."
    }
    Write-Host 'Key table row counts match:'
    $restored | ForEach-Object { Write-Host $_ }
    Write-Host "Backup and independent rehearsal copy ready: $copy"
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
