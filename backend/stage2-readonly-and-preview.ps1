param([string]$User = 'root')

$ErrorActionPreference = 'Stop'
$database = 'campus_activity_miniapp_dev'
$root = Split-Path -Parent $PSScriptRoot
$sqlPath = Join-Path $PSScriptRoot 'migrations/stage2_preflight_readonly.sql'
$outPath = Join-Path $root ('backups/stage2_preflight_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.txt')
$securePassword = Read-Host 'MySQL password (hidden)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}

try {
    $selected = & mysql --protocol=TCP --host=127.0.0.1 --port=3306 --user=$User `
        --connect-timeout=5 --database=$database --batch --skip-column-names `
        --execute='SELECT DATABASE();'
    if ($LASTEXITCODE -ne 0 -or $selected.Trim() -ne $database) {
        throw 'MySQL connection failed or selected database differs from campus_activity_miniapp_dev.'
    }

    $sql = Get-Content -Raw -LiteralPath $sqlPath
    $result = & mysql --protocol=TCP --host=127.0.0.1 --port=3306 --user=$User `
        --connect-timeout=5 --database=$database --batch --raw --execute=$sql
    if ($LASTEXITCODE -ne 0) { throw 'Read-only preflight failed; preview backend was not started.' }
    $result | Set-Content -LiteralPath $outPath -Encoding UTF8
    Write-Host "Read-only preflight saved: $outPath"

    $env:DB_URL = "jdbc:mysql://127.0.0.1:3306/$database`?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
    $env:DB_USER = $User
    $env:DB_PASSWORD = $env:MYSQL_PWD
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    $env:SERVER_PORT = '18081'
    Remove-Item Env:MYSQL_PWD
    Write-Host 'Starting preview backend on 18081; existing 8080 remains untouched.'
    Push-Location $PSScriptRoot
    try { & .\mvnw.cmd spring-boot:run }
    finally { Pop-Location }
} finally {
    Remove-Item Env:MYSQL_PWD, Env:DB_PASSWORD, Env:DB_URL, Env:DB_USER, `
        Env:SPRING_PROFILES_ACTIVE, Env:SERVER_PORT -ErrorAction SilentlyContinue
}
