param(
    [string]$User = 'root',
    [string]$RehearsalDatabase = 'campus_activity_miniapp_stage2_rehearsal_20261013_113503'
)

$ErrorActionPreference = 'Stop'
$clone = $RehearsalDatabase
if ($clone -notmatch '^campus_activity_miniapp_stage2_rehearsal_[0-9]{8}_[0-9]{6}$') {
    throw 'Only a named independent stage 2 rehearsal clone may be validated.'
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
if (!$jar) { throw 'Build a complete Spring Boot backend jar before validating the clone.' }

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
        "--database=$clone" --batch --skip-column-names --execute='SELECT DATABASE();'
    if ($LASTEXITCODE -ne 0 -or ($selected | Out-String).Trim() -cne $clone) {
        throw 'Clone database guard failed; the backend was not started.'
    }
    $env:DB_URL = "jdbc:mysql://127.0.0.1:3306/$clone`?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
    $env:DB_USER = $User
    $env:DB_PASSWORD = $env:MYSQL_PWD
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    $env:SPRING_MAIN_WEB_APPLICATION_TYPE = 'none'
    $env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
    $env:STAGE2_CLONE_VALIDATION = 'true'
    $env:APP_BOOTSTRAP_ADMIN_EMAIL = ''
    $env:APP_BOOTSTRAP_ADMIN_PASSWORD = ''
    Remove-Item Env:MYSQL_PWD

    Write-Host "Validating current backend against independent clone: $clone"
    & java -jar $jar --logging.level.root=ERROR
    if ($LASTEXITCODE -ne 0) { throw 'Backend JPA validation against the clone failed.' }
    Write-Host 'Clone JPA validation passed. Existing 8080/5175 were not touched.'
} finally {
    Remove-Item Env:MYSQL_PWD, Env:DB_PASSWORD, Env:DB_URL, Env:DB_USER, `
        Env:SPRING_PROFILES_ACTIVE, Env:SPRING_MAIN_WEB_APPLICATION_TYPE, `
        Env:SPRING_JPA_HIBERNATE_DDL_AUTO, Env:APP_BOOTSTRAP_ADMIN_EMAIL, `
        Env:APP_BOOTSTRAP_ADMIN_PASSWORD, Env:STAGE2_CLONE_VALIDATION -ErrorAction SilentlyContinue
}
