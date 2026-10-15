$ErrorActionPreference = 'Stop'
if (Get-NetTCPConnection -LocalPort 5176 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Preview port 5176 is already in use.'
}
$env:VITE_BACKEND_URL = 'http://127.0.0.1:18082'
$env:VITE_STAGE2_PREVIEW = 'true'
try {
    Set-Location -LiteralPath $PSScriptRoot
    & npm.cmd run dev -- --host 127.0.0.1 --port 5176 --strictPort
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Web preview stopped with an error.' }
} finally {
    Remove-Item Env:VITE_BACKEND_URL, Env:VITE_STAGE2_PREVIEW -ErrorAction SilentlyContinue
}
