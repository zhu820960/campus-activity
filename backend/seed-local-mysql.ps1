param(
    [switch]$PrepareOnly,
    [switch]$ShowCredentials,
    [string]$BaseUrl = 'http://127.0.0.1:18080'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$credentialFile = Join-Path $root 'backups/local-test-accounts.clixml'
$database = 'campus_activity_miniapp_dev'

function New-TestPassword {
    $bytes = [byte[]]::new(24)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

if (-not (Test-Path -LiteralPath $credentialFile)) {
    $accounts = [pscustomobject]@{
        Admin = [pscustomobject]@{ Email = 'dev-admin@campus.test'; Password = (ConvertTo-SecureString (New-TestPassword) -AsPlainText -Force) }
        Organizer = [pscustomobject]@{ Email = 'dev-organizer@campus.test'; Password = (ConvertTo-SecureString (New-TestPassword) -AsPlainText -Force) }
        StudentA = [pscustomobject]@{ Email = 'dev-student-a@campus.test'; Password = (ConvertTo-SecureString (New-TestPassword) -AsPlainText -Force) }
        StudentB = [pscustomobject]@{ Email = 'dev-student-b@campus.test'; Password = (ConvertTo-SecureString (New-TestPassword) -AsPlainText -Force) }
    }
    $accounts | Export-Clixml -LiteralPath $credentialFile
} else {
    $accounts = Import-Clixml -LiteralPath $credentialFile
}

if ($ShowCredentials) {
    foreach ($role in 'Admin', 'Organizer', 'StudentA', 'StudentB') {
        $account = $accounts.$role
        [pscustomobject]@{ Role = $role; Email = $account.Email; Password = (ConvertFrom-SecureString $account.Password -AsPlainText) }
    }
    return
}

if ($PrepareOnly) {
    Write-Output "Encrypted test credentials ready at $credentialFile"
    Write-Output 'Start the backend with APP_BOOTSTRAP_ADMIN_EMAIL and APP_BOOTSTRAP_ADMIN_PASSWORD from this file, then run this script again to seed through the API.'
    return
}

if ($BaseUrl -ne 'http://127.0.0.1:18080') {
    throw 'Test data may only be sent to the verified local backend on port 18080.'
}

$securePassword = Read-Host 'Local MySQL password' -AsSecureString
$env:MYSQL_PWD = ConvertFrom-SecureString $securePassword -AsPlainText
try {
    $selected = & mysql --protocol=TCP --host=127.0.0.1 --port=3306 --user=root --connect-timeout=5 --database=$database --batch --skip-column-names --execute='SELECT DATABASE();'
    if ($LASTEXITCODE -ne 0 -or $selected.Trim() -ne $database) {
        throw 'The selected MySQL database is not campus_activity_miniapp_dev.'
    }
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

function Invoke-Api {
    param([Microsoft.PowerShell.Commands.WebRequestSession]$Session, [string]$Path,
          [string]$Method = 'GET', $Body, [string]$Csrf)
    $parameters = @{ Uri = "$BaseUrl$Path"; Method = $Method; WebSession = $Session; ErrorAction = 'Stop' }
    if ($null -ne $Body) {
        $parameters.Body = ConvertTo-Json -InputObject $Body -Depth 8 -Compress
        $parameters.ContentType = 'application/json; charset=utf-8'
    }
    if ($Csrf) { $parameters.Headers = @{ 'X-XSRF-TOKEN' = $Csrf } }
    $response = Invoke-RestMethod @parameters
    foreach ($entry in $response) { Write-Output $entry }
}

function New-Session {
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrf = (Invoke-Api -Session $session -Path '/api/auth/csrf').token
    [pscustomobject]@{ Session = $session; Csrf = $csrf }
}

function Sign-In {
    param($Account)
    $state = New-Session
    $password = ConvertFrom-SecureString $Account.Password -AsPlainText
    $null = Invoke-Api -Session $state.Session -Path '/api/auth/login' -Method 'POST' -Csrf $state.Csrf -Body @{ email = $Account.Email; password = $password }
    $state.Csrf = (Invoke-Api -Session $state.Session -Path '/api/auth/csrf').token
    return $state
}

$admin = Sign-In $accounts.Admin
$users = @(Invoke-Api -Session $admin.Session -Path '/api/admin/users')
foreach ($item in @(
    @{ Account = $accounts.Organizer; Name = '[DEV TEST] 活动组织者' },
    @{ Account = $accounts.StudentA; Name = '[DEV TEST] 学生甲' },
    @{ Account = $accounts.StudentB; Name = '[DEV TEST] 学生乙' }
)) {
    $account = $item.Account
    if (@($users | Where-Object email -eq $account.Email).Count -eq 0) {
        $password = ConvertFrom-SecureString $account.Password -AsPlainText
        $null = Invoke-Api -Session $admin.Session -Path '/api/auth/register' -Method 'POST' -Csrf $admin.Csrf -Body @{ name = $item.Name; email = $account.Email; password = $password }
    }
}
$users = @(Invoke-Api -Session $admin.Session -Path '/api/admin/users')
$organizer = $users | Where-Object email -eq $accounts.Organizer.Email | Select-Object -First 1
if (-not $organizer) { throw 'The test organizer account was not created.' }
if (@($organizer.roles) -notcontains 'ORGANIZER') {
    $null = Invoke-Api -Session $admin.Session -Path "/api/admin/users/$($organizer.id)/organizer" -Method 'PATCH' -Csrf $admin.Csrf -Body @{ enabled = $true }
}

function New-ActivityInput {
    param([string]$Title, [int]$StartMinutes, [int]$Capacity, [switch]$CheckinNow)
    $now = [DateTimeOffset]::UtcNow
    $start = $now.AddMinutes($StartMinutes)
    $end = $start.AddHours(2)
    $checkinOpen = if ($CheckinNow) { $now.AddMinutes(-5) } else { $start.AddMinutes(-10) }
    @{
        title = $Title
        description = '开发测试活动；用于验证活动发布、报名和动态签到，不是面向真实学生的通知。'
        articleSummary = '仅供本机开发联调。'
        coverImageUrl = $null
        location = '开发测试地点（示意坐标，非真实活动）'
        longitude = 108.9
        latitude = 34.23
        startsAt = $start.ToString('o')
        endsAt = $end.ToString('o')
        registrationOpensAt = $now.AddHours(-1).ToString('o')
        registrationClosesAt = $start.AddMinutes(-10).ToString('o')
        checkinOpensAt = $checkinOpen.ToString('o')
        checkinClosesAt = $end.ToString('o')
        capacity = $Capacity
    }
}

$existing = @(Invoke-Api -Session $admin.Session -Path '/api/admin/activities')
function Has-Activity([string]$title) {
    @($existing | Where-Object { $_.activity.title -eq $title }).Count -gt 0
}

foreach ($spec in @(
    @{ Title = '[DEV TEST] 未来报名活动'; StartMinutes = 1440; Capacity = 20; CheckinNow = $false },
    @{ Title = '[DEV TEST] 现场签到活动'; StartMinutes = 30; Capacity = 20; CheckinNow = $true },
    @{ Title = '[DEV TEST] 单名额活动'; StartMinutes = 2880; Capacity = 1; CheckinNow = $false }
)) {
    if (-not (Has-Activity $spec.Title)) {
        $input = New-ActivityInput @spec
        $created = Invoke-Api -Session $admin.Session -Path '/api/admin/activities' -Method 'POST' -Csrf $admin.Csrf -Body $input
        $null = Invoke-Api -Session $admin.Session -Path "/api/admin/activities/$($created.id)/publish" -Method 'POST' -Csrf $admin.Csrf
    }
}

$pendingTitle = '[DEV TEST] 待审核活动'
if (-not (Has-Activity $pendingTitle)) {
    $owner = Sign-In $accounts.Organizer
    $input = New-ActivityInput -Title $pendingTitle -StartMinutes 4320 -Capacity 20
    $created = Invoke-Api -Session $owner.Session -Path '/api/organizer/activities' -Method 'POST' -Csrf $owner.Csrf -Body $input
    $null = Invoke-Api -Session $owner.Session -Path "/api/organizer/activities/$($created.id)/submit" -Method 'POST' -Csrf $owner.Csrf
}

$result = @(Invoke-Api -Session $admin.Session -Path '/api/admin/activities') |
    Where-Object { $_.activity.title -like '[[]DEV TEST[]]*' } |
    ForEach-Object { [pscustomobject]@{ Id = $_.activity.id; Title = $_.activity.title; Status = $_.activity.status; Registered = $_.stats.registeredCount; Checked = $_.stats.checkedCount } }
$result | Sort-Object Id | Format-Table -AutoSize
