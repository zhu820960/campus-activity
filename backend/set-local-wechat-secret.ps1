$ErrorActionPreference = 'Stop'
$target = Join-Path (Split-Path -Parent $PSScriptRoot) 'backups/local-wechat-secret.clixml'
$secret = Read-Host 'WeChat AppSecret (hidden)' -AsSecureString
if ($secret.Length -eq 0) {
    Write-Host 'No secret entered. Nothing was saved.'
    exit 1
}
$secret | Export-Clixml -LiteralPath $target
Write-Host 'Encrypted local secret saved. You can close this window.'
Read-Host 'Press Enter to close' | Out-Null
