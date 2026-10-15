param([string]$BaseUrl = 'http://127.0.0.1:18080')

$ErrorActionPreference = 'Stop'
if ($BaseUrl -ne 'http://127.0.0.1:18080') { throw 'Verification is limited to the local backend on port 18080.' }
$root = Split-Path -Parent $PSScriptRoot
$accounts = Import-Clixml -LiteralPath (Join-Path $root 'backups/local-test-accounts.clixml')

$securePassword = Read-Host 'Local MySQL password' -AsSecureString
$env:MYSQL_PWD = ConvertFrom-SecureString $securePassword -AsPlainText
try {
    $selected = & mysql --protocol=TCP --host=127.0.0.1 --port=3306 --user=root --connect-timeout=5 --database=campus_activity_miniapp_dev --batch --skip-column-names --execute='SELECT DATABASE();'
    if ($LASTEXITCODE -ne 0 -or $selected.Trim() -ne 'campus_activity_miniapp_dev') {
        throw 'The selected database is not campus_activity_miniapp_dev.'
    }
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

function Request {
    param($State, [string]$Path, [string]$Method = 'GET', $Body)
    $parameters = @{ Uri = "$BaseUrl$Path"; Method = $Method; WebSession = $State.Session; ErrorAction = 'Stop' }
    if ($null -ne $Body) {
        $parameters.Body = ConvertTo-Json -InputObject $Body -Depth 6 -Compress
        $parameters.ContentType = 'application/json; charset=utf-8'
    }
    if ($State.Csrf) { $parameters.Headers = @{ 'X-XSRF-TOKEN' = $State.Csrf } }
    $response = Invoke-RestMethod @parameters
    foreach ($entry in $response) { Write-Output $entry }
}

function Sign-In($account) {
    $state = [pscustomobject]@{ Session = [Microsoft.PowerShell.Commands.WebRequestSession]::new(); Csrf = $null }
    $state.Csrf = (Request $state '/api/auth/csrf').token
    $password = ConvertFrom-SecureString $account.Password -AsPlainText
    $null = Request $state '/api/auth/login' 'POST' @{ email = $account.Email; password = $password }
    $state.Csrf = (Request $state '/api/auth/csrf').token
    return $state
}

function Expect-Conflict {
    param($State, [string]$Path, [string]$Method, $Body)
    try {
        $null = Request $State $Path $Method $Body
    } catch {
        if ([int]$_.Exception.Response.StatusCode -eq 409) { return }
        throw
    }
    throw "Expected HTTP 409 for $Method $Path"
}

function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

$admin = Sign-In $accounts.Admin
$studentA = Sign-In $accounts.StudentA
$studentB = Sign-In $accounts.StudentB
$overview = @(Request $admin '/api/admin/activities')
$byTitle = @{}
foreach ($item in $overview) { $byTitle[$item.activity.title] = $item.activity.id }
$future = $byTitle['[DEV TEST] 未来报名活动']
$onsite = $byTitle['[DEV TEST] 现场签到活动']
$limited = $byTitle['[DEV TEST] 单名额活动']
Assert ($future -and $onsite -and $limited) 'Expected marked test activities are missing.'
$public = @(Request $studentA '/api/activities')
Assert (@($public | Where-Object title -eq '[DEV TEST] 待审核活动').Count -eq 0) 'Pending activity leaked into public list.'

$null = Request $studentA "/api/activities/$future/registrations" 'POST'
Expect-Conflict $studentA "/api/activities/$future/registrations" 'POST' $null
$null = Request $studentA "/api/activities/$future/registrations" 'DELETE'
$null = Request $studentA "/api/activities/$future/registrations" 'POST'
$futureStats = Request $admin "/api/admin/activities/$future/stats"
Assert ($futureStats.registeredCount -eq 1) 'Future activity count did not recover after cancellation.'

$null = Request $studentA "/api/activities/$limited/registrations" 'POST'
Expect-Conflict $studentB "/api/activities/$limited/registrations" 'POST' $null
$null = Request $studentA "/api/activities/$limited/registrations" 'DELETE'
$null = Request $studentB "/api/activities/$limited/registrations" 'POST'
$limitedStats = Request $admin "/api/admin/activities/$limited/stats"
Assert ($limitedStats.registeredCount -eq 1) 'Limited activity capacity count is inconsistent.'

$null = Request $studentA "/api/activities/$onsite/registrations" 'POST'
$issued = Request $admin "/api/admin/activities/$onsite/checkin-token" 'POST'
Assert ($issued.token -and $issued.expiresAt) 'Dynamic QR token was not issued.'
$null = Request $studentA "/api/activities/$onsite/checkins" 'POST' @{ token = $issued.token }
Expect-Conflict $studentA "/api/activities/$onsite/checkins" 'POST' @{ token = $issued.token }
Expect-Conflict $studentA "/api/activities/$onsite/registrations" 'DELETE' $null
$onsiteStats = Request $admin "/api/admin/activities/$onsite/stats"
Assert ($onsiteStats.registeredCount -eq 1 -and $onsiteStats.checkedCount -eq 1) 'Checkin and admin statistics differ.'
$mine = @(Request $studentA '/api/my/registrations')
Assert (@($mine | Where-Object { $_.activityId -eq $onsite -and $_.checkedAt }).Count -eq 1) 'Student checkin is absent from personal records.'

[pscustomobject]@{
    PublicPendingHidden = $true
    FutureRegistrationAndRecovery = $futureStats.registeredCount
    LimitedActivityOccupancy = $limitedStats.registeredCount
    OnsiteRegistrations = $onsiteStats.registeredCount
    OnsiteCheckins = $onsiteStats.checkedCount
    PersonalCheckinVisible = $true
} | Format-List
