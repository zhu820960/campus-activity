param([string]$User = 'root')

$ErrorActionPreference = 'Stop'
$clone = 'campus_activity_miniapp_stage2_rehearsal_20261013_113503'
$password = Read-Host 'MySQL password (hidden)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
try {
    $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}

try {
    $mysql = (Get-Command mysql.exe -CommandType Application).Source
    $common = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=3306', "--user=$User", '--default-character-set=utf8mb4', "--database=$clone", '--batch', '--skip-column-names')
    function Scalar([string]$Sql) {
        $result = & $mysql @common "--execute=$Sql"
        if ($LASTEXITCODE -ne 0) { throw 'Clone-only verification query failed.' }
        return ($result | Out-String).Trim()
    }
    if ((Scalar 'SELECT DATABASE();') -cne $clone) { throw 'Wrong database selected.' }
    $baseline = Scalar @'
SELECT (SELECT COUNT(*) FROM wechat_identities),
       (SELECT COUNT(*) FROM wechat_student_bindings),
       (SELECT COUNT(*) FROM wechat_student_bindings WHERE unbound_at IS NULL);
'@
    if ($baseline -ne "2`t2`t2") { throw 'Unexpected identity or binding baseline.' }

    $rebind = @'
START TRANSACTION;
UPDATE wechat_student_bindings SET unbound_at = UTC_TIMESTAMP(6) WHERE id = 1;
INSERT INTO wechat_student_bindings (app_id, open_id, student_id, created_at, identity_id)
SELECT app_id, open_id, student_id, UTC_TIMESTAMP(6), identity_id
FROM wechat_student_bindings WHERE id = 1;
SELECT COUNT(*) FROM wechat_student_bindings WHERE identity_id =
  (SELECT identity_id FROM wechat_student_bindings WHERE id = 1) AND unbound_at IS NULL;
ROLLBACK;
'@
    $activeAfterRebind = Scalar $rebind
    if ($activeAfterRebind -ne '1') { throw 'Unbind and rebind test failed.' }
    Write-Host 'PASS: old binding can be marked inactive and the same identity/student rebound in one transaction.'

    $duplicateOpenId = @'
START TRANSACTION;
INSERT INTO wechat_student_bindings (app_id, open_id, student_id, created_at, identity_id)
SELECT app_id, open_id, 4, UTC_TIMESTAMP(6), identity_id
FROM wechat_student_bindings WHERE id = 1;
'@
    & $mysql @common "--execute=$duplicateOpenId" 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { throw 'Duplicate active WeChat identity unexpectedly succeeded.' }
    Write-Host 'PASS: duplicate active WeChat identity is rejected.'

    $duplicateStudent = @'
START TRANSACTION;
INSERT INTO wechat_identities (app_id, open_id, created_at)
SELECT app_id, 'stage2-constraint-probe', UTC_TIMESTAMP(6)
FROM wechat_student_bindings WHERE id = 1;
INSERT INTO wechat_student_bindings (app_id, open_id, student_id, created_at, identity_id)
SELECT app_id, 'stage2-constraint-probe', student_id, UTC_TIMESTAMP(6), LAST_INSERT_ID()
FROM wechat_student_bindings WHERE id = 1;
'@
    & $mysql @common "--execute=$duplicateStudent" 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { throw 'Duplicate active student unexpectedly succeeded.' }
    Write-Host 'PASS: duplicate active student binding is rejected.'

    if ((Scalar @'
SELECT (SELECT COUNT(*) FROM wechat_identities),
       (SELECT COUNT(*) FROM wechat_student_bindings),
       (SELECT COUNT(*) FROM wechat_student_bindings WHERE unbound_at IS NULL);
'@) -cne $baseline) {
        throw 'Clone row counts changed after rollback-only constraint probes.'
    }
    Write-Host 'All temporary writes rolled back; independent clone row counts unchanged.'
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
