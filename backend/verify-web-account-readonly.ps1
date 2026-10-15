$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:5175/api'
$accountFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'backups/local-test-accounts.clixml'
$accounts = Import-Clixml -LiteralPath $accountFile
foreach ($role in @('Admin', 'Organizer')) {
    $account = $accounts.$role
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Invoke-RestMethod "$base/auth/csrf" -WebSession $session
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($account.Password)
    try {
        $payload = @{ email = $account.Email; password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) } | ConvertTo-Json
        $loggedIn = Invoke-RestMethod "$base/auth/login" -Method POST -WebSession $session -Headers @{ 'X-XSRF-TOKEN' = $csrf.token } -ContentType 'application/json' -Body $payload
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $payload = $null
    }
    $me = Invoke-RestMethod "$base/auth/me" -WebSession $session
    if (-not ($me.PSObject.Properties.Name -contains 'hasAvatar')) { throw "$role missing avatar metadata" }
    $notifications = @(Invoke-RestMethod "$base/account/notifications" -WebSession $session)
    $unread = Invoke-RestMethod "$base/account/notifications/unread-count" -WebSession $session
    $expectedRoot = if ($role -eq 'Admin') { '/admin/' } else { '/organizer/' }
    foreach ($notice in $notifications) {
        if ($null -eq $notice -or -not $notice.id) { continue }
        if ([string]::IsNullOrWhiteSpace($notice.targetPath)) { throw "$role notification has no navigation target" }
        if (-not $notice.targetPath.StartsWith($expectedRoot) -and -not $notice.targetPath.StartsWith('/account')) {
            throw "$role notification has invalid navigation scope"
        }
    }
    if ($me.hasAvatar) {
        $avatar = Invoke-WebRequest "$base/account/avatar" -WebSession $session -UseBasicParsing
        if ($avatar.StatusCode -ne 200) { throw "$role avatar read failed" }
    }
    Write-Output "$role PASS: session, avatar metadata, scoped notification list and unread count."
}
Write-Output 'Read-only checks complete; no avatar, notification read-state, activity or binding writes.'
