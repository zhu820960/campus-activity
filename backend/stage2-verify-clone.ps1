param(
    [Parameter(Mandatory = $true)][string]$RehearsalDatabase,
    [string]$User = 'root'
)

$ErrorActionPreference = 'Stop'
$source = 'campus_activity_miniapp_dev'
if ($RehearsalDatabase -notmatch '^campus_activity_miniapp_stage2_rehearsal_[0-9]{8}_[0-9]{6}$') {
    throw 'Only a named stage 2 rehearsal database may be verified.'
}
$password = Read-Host 'MySQL password (hidden)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
try {
    $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}

try {
    $mysql = (Get-Command mysql.exe -CommandType Application).Source
    $common = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=3306', "--user=$User", '--batch', '--skip-column-names')
    foreach ($database in @($source, $RehearsalDatabase)) {
        $selected = & $mysql @common "--database=$database" --execute='SELECT DATABASE();'
        if ($LASTEXITCODE -ne 0 -or $selected.Trim() -ne $database) {
            throw "Database guard failed for $database."
        }
    }

    $tables = @'
SELECT table_name FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
ORDER BY table_name;
'@
    $counts = @'
SELECT 'activities' AS table_name, COUNT(*) AS row_count FROM activities
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
    $sourceTables = & $mysql @common "--database=$source" --execute=$tables
    if ($LASTEXITCODE -ne 0) { throw 'Could not list source tables.' }
    $cloneTables = & $mysql @common "--database=$RehearsalDatabase" --execute=$tables
    if ($LASTEXITCODE -ne 0) { throw 'Could not list clone tables.' }
    if (($sourceTables -join "`n") -ne ($cloneTables -join "`n") -or $sourceTables.Count -ne 12) {
        throw 'Source and clone table names differ, or the expected 12 tables are not present.'
    }

    $sourceRows = & $mysql @common "--database=$source" --execute=$counts
    if ($LASTEXITCODE -ne 0) { throw 'Could not count source rows.' }
    $cloneRows = & $mysql @common "--database=$RehearsalDatabase" --execute=$counts
    if ($LASTEXITCODE -ne 0) { throw 'Could not count clone rows.' }
    if (($sourceRows -join "`n") -ne ($cloneRows -join "`n")) {
        throw 'Source and clone row counts differ.'
    }
    Write-Host 'All 12 table names and exact row counts match:'
    $cloneRows | ForEach-Object { Write-Host $_ }
    Write-Host "Independent clone verified: $RehearsalDatabase"
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
