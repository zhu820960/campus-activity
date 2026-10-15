param(
    [Parameter(Mandatory = $true)][string]$RehearsalDatabase,
    [string]$User = 'root'
)

$ErrorActionPreference = 'Stop'
if ($RehearsalDatabase -notmatch '^campus_activity_miniapp_stage2_rehearsal_[0-9]{8}_[0-9]{6}$') {
    throw 'Only a named independent stage 2 rehearsal clone may be migrated by this script.'
}

if (!$env:MYSQL_PWD) {
    $password = Read-Host 'MySQL password (hidden)' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
    try {
        $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

try {
    $mysql = (Get-Command mysql.exe -CommandType Application).Source
    $common = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=3306', "--user=$User", '--default-character-set=utf8mb4', "--database=$RehearsalDatabase", '--batch', '--skip-column-names')

    function Invoke-Scalar([string]$Sql) {
        $output = & $mysql @common "--execute=$Sql"
        if ($LASTEXITCODE -ne 0) { throw 'MySQL guard or verification query failed.' }
        return ($output | Out-String).Trim()
    }

    if ((Invoke-Scalar 'SELECT DATABASE();') -cne $RehearsalDatabase) {
        throw 'Database guard failed.'
    }

    $existing = Invoke-Scalar @'
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';
'@
    if ($existing -ne '12') {
        throw "Expected an untouched 12-table rehearsal clone; found $existing tables. No SQL was applied."
    }

    $before = Invoke-Scalar @'
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

    $groups = @(
        @{ Name = 'A identity'; File = 'stage2_rehearsal_A_identity.sql' },
        @{ Name = 'B appeals'; File = 'stage2_rehearsal_B_appeals.sql' },
        @{ Name = 'C changes and exports'; File = 'stage2_rehearsal_C_changes_exports.sql' },
        @{ Name = 'D message provenance'; File = 'stage2_rehearsal_D_message_provenance.sql' }
    )
    foreach ($group in $groups) {
        $path = Join-Path $PSScriptRoot "migrations/$($group.File)"
        if (!(Test-Path -LiteralPath $path)) { throw "Missing migration group: $($group.Name)" }
        Write-Host "Applying clone-only group: $($group.Name)"
        Get-Content -LiteralPath $path -Raw | & $mysql @common
        if ($LASTEXITCODE -ne 0) {
            throw "Group $($group.Name) failed. The clone may be partially migrated; do not rerun this script."
        }
        Write-Host "Applied: $($group.Name)"
    }

    $after = Invoke-Scalar @'
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
    if ($before -cne $after) { throw 'Original 12-table row counts changed during rehearsal.' }

    $tableCount = Invoke-Scalar @'
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';
'@
    if ($tableCount -ne '19') { throw "Expected 19 tables after rehearsal; found $tableCount." }
    $checks = Invoke-Scalar @'
SELECT
  (SELECT COUNT(*) FROM wechat_student_bindings WHERE identity_id IS NULL),
  (SELECT COUNT(*) FROM wechat_identities),
  (SELECT COUNT(*) FROM registrations WHERE identity_id IS NOT NULL OR binding_id IS NOT NULL),
  (SELECT COUNT(*) FROM checkins WHERE identity_id IS NOT NULL OR binding_id IS NOT NULL),
  (SELECT COUNT(*) FROM activity_change_requests),
  (SELECT COUNT(*) FROM binding_appeals),
  (SELECT COUNT(*) FROM participation_export_audits),
  (SELECT COUNT(*) FROM user_messages WHERE identity_id IS NOT NULL);
'@
    if ($checks -ne "0`t2`t0`t0`t0`t0`t0`t0") {
        throw "Post-migration identity/history checks failed: $checks"
    }
    Write-Host "Independent stage 2 rehearsal passed: $RehearsalDatabase"
    Write-Host 'Original 12 table row counts unchanged; 19 tables total; 2 identities backfilled; historical participation and message attribution remains NULL.'
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
