$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$credentialFile = Join-Path $root 'backups/local-test-accounts.clixml'
if (-not (Test-Path -LiteralPath $credentialFile)) {
    throw 'Local test credentials are missing. Run seed-local-mysql.ps1 -PrepareOnly first.'
}

$accounts = Import-Clixml -LiteralPath $credentialFile
$env:APP_BOOTSTRAP_ADMIN_EMAIL = $accounts.Admin.Email
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($accounts.Admin.Password)
try {
    $env:APP_BOOTSTRAP_ADMIN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
$env:WECHAT_APP_ID = 'wx141754dff3369b18'
$env:SERVER_PORT = '18080'

try {
    & (Join-Path $PSScriptRoot 'start-local-mysql.ps1') -PromptWechatSecret
} finally {
    Remove-Item Env:APP_BOOTSTRAP_ADMIN_EMAIL, Env:APP_BOOTSTRAP_ADMIN_PASSWORD, Env:WECHAT_APP_ID, Env:SERVER_PORT -ErrorAction SilentlyContinue
}
