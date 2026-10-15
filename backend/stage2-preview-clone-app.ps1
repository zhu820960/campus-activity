param(
    [string]$User = 'root',
    [string]$RehearsalDatabase = 'campus_activity_miniapp_stage2_rehearsal_20261013_113503'
)

$ErrorActionPreference = 'Stop'
if ($RehearsalDatabase -notmatch '^campus_activity_miniapp_stage2_rehearsal_[0-9]{8}_[0-9]{6}$') {
    throw 'Only an independent stage 2 rehearsal clone may be previewed.'
}
$jarCandidates = @(
    (Join-Path $PSScriptRoot 'target/campus-activity-0.0.1-SNAPSHOT.jar'),
    (Join-Path $PSScriptRoot 'target/campus-activity-preview-next.jar')
)
$jar = $jarCandidates | Where-Object { Test-Path -LiteralPath $_ } |
    Sort-Object { (Get-Item -LiteralPath $_).LastWriteTimeUtc } -Descending |
    Where-Object {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($_)
        try {
            $null -ne $archive.GetEntry('org/springframework/boot/loader/launch/JarLauncher.class')
        } finally {
            $archive.Dispose()
        }
    } | Select-Object -First 1
if (!$jar) { throw 'Build a complete Spring Boot backend jar before previewing.' }
if (Get-NetTCPConnection -LocalPort 18082 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Preview port 18082 is already in use.'
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
    $selected = & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 --port=3306 "--user=$User" `
        "--database=$RehearsalDatabase" --batch --skip-column-names --execute='SELECT DATABASE();'
    if ($LASTEXITCODE -ne 0 -or ($selected | Out-String).Trim() -cne $RehearsalDatabase) {
        throw 'Clone database guard failed; preview was not started.'
    }

    $env:DB_URL = "jdbc:mysql://127.0.0.1:3306/$RehearsalDatabase`?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
    $env:DB_USER = $User
    $env:DB_PASSWORD = $env:MYSQL_PWD
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    $env:SPRING_MAIN_WEB_APPLICATION_TYPE = 'servlet'
    $env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
    $env:STAGE2_CLONE_PREVIEW = 'true'
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:SERVER_PORT = '18082'
    $env:APP_BOOTSTRAP_ADMIN_EMAIL = ''
    $env:APP_BOOTSTRAP_ADMIN_PASSWORD = ''
    $env:APP_IMAGE_DIR = Join-Path $PSScriptRoot '../backups/stage2_preview_uploads/images'
    $env:APP_AVATAR_DIR = Join-Path $PSScriptRoot '../backups/stage2_preview_uploads/avatars'
    Remove-Item Env:MYSQL_PWD

    Write-Host "Starting loopback-only preview against independent clone: $RehearsalDatabase"
    Write-Host 'This preview may write test data to the clone only. Press Ctrl+C to stop it.'
    & java -jar $jar --server.address=127.0.0.1 --server.port=18082 `
        --spring.main.web-application-type=servlet --spring.jpa.hibernate.ddl-auto=validate
    if ($LASTEXITCODE -ne 0) { throw 'Clone preview backend stopped with an error.' }
} finally {
    Remove-Item Env:MYSQL_PWD, Env:DB_PASSWORD, Env:DB_URL, Env:DB_USER, `
        Env:SPRING_PROFILES_ACTIVE, Env:SPRING_MAIN_WEB_APPLICATION_TYPE, `
        Env:SPRING_JPA_HIBERNATE_DDL_AUTO, Env:STAGE2_CLONE_PREVIEW, `
        Env:SERVER_ADDRESS, Env:SERVER_PORT, Env:APP_BOOTSTRAP_ADMIN_EMAIL, `
        Env:APP_BOOTSTRAP_ADMIN_PASSWORD, Env:APP_IMAGE_DIR, Env:APP_AVATAR_DIR `
        -ErrorAction SilentlyContinue
}
