param([string]$User = 'root')

$ErrorActionPreference = 'Stop'
$backup = Join-Path (Split-Path -Parent $PSScriptRoot) 'backups/campus_activity_miniapp_dev_before_stage2_20261013_112246.sql'
$expectedHash = '293B4093391EF92665E5B2D512CC77C2C0F47BDB29CC1B120D8DBBB6B3A2C84B'
if (!(Test-Path -LiteralPath $backup) -or
    (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -cne $expectedHash) {
    throw 'Verified full backup is missing or its checksum changed.'
}
Write-Host 'Verified full backup checksum.'

$password = Read-Host 'MySQL password (hidden)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
try {
    $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}

try {
    $mysql = (Get-Command mysql.exe -CommandType Application).Source
    $common = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=3306', "--user=$User", '--default-character-set=utf8mb4', '--batch', '--skip-column-names')
    $clone = 'campus_activity_miniapp_stage2_rehearsal_' + (Get-Date -Format 'yyyyMMdd_HHmmss')
    $exists = & $mysql @common "--execute=SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$clone';"
    if ($LASTEXITCODE -ne 0 -or ($exists | Out-String).Trim() -ne '0') {
        throw 'Could not confirm that the new rehearsal database name is unused.'
    }
    & $mysql @common "--execute=CREATE DATABASE ``$clone`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create new independent rehearsal database.' }
    Write-Host "New independent rehearsal database created: $clone"

    Get-Content -LiteralPath $backup | & $mysql @common "--database=$clone"
    if ($LASTEXITCODE -ne 0) { throw 'Backup restore failed; do not migrate this clone.' }
    $selected = & $mysql @common "--database=$clone" '--execute=SELECT DATABASE();'
    if ($LASTEXITCODE -ne 0 -or ($selected | Out-String).Trim() -cne $clone) {
        throw 'Rehearsal database guard failed.'
    }
    $counts = @'
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
    $actual = & $mysql @common "--database=$clone" "--execute=$counts"
    if ($LASTEXITCODE -ne 0) { throw 'Could not count restored rows.' }
    $expected = @(
        "activities`t8", "activity_audits`t33", "activity_feedback`t0", "announcements`t2",
        "checkins`t2", "miniapp_access_tokens`t13", "registrations`t6", "user_accounts`t5",
        "user_messages`t4", "user_roles`t7", "user_security_audits`t3", "wechat_student_bindings`t2"
    )
    if (($actual -join "`n") -cne ($expected -join "`n")) {
        throw 'Restored row counts do not match the verified full backup; do not migrate this clone.'
    }
    Write-Host 'All 12 original table row counts match the verified backup.'
    & (Join-Path $PSScriptRoot 'stage2-run-rehearsal.ps1') -RehearsalDatabase $clone -User $User
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
