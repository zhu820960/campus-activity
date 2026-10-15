$ErrorActionPreference = 'Stop'
$target = Join-Path (Split-Path -Parent $PSScriptRoot) 'backups/local-deepseek-key.clixml'
$key = Read-Host 'DeepSeek API Key (hidden)' -AsSecureString
if ($key.Length -eq 0) {
    Write-Host 'No key entered. Nothing was saved.'
    exit 1
}
$key | Export-Clixml -LiteralPath $target
Write-Host 'Encrypted local key saved. You can close this window.'
Read-Host 'Press Enter to close' | Out-Null
