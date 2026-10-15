param(
    [string]$User = 'root',
    [switch]$PromptWechatSecret,
    [switch]$VerifyUserDeletion
)

$ErrorActionPreference = 'Stop'
$database = 'campus_activity_miniapp_dev'
$loadedMapVariables = @()
$loadedDeepSeekKey = $false
$loadedWechatAppId = $false
if (-not (Test-Path Env:DB_URL) -or [string]::IsNullOrWhiteSpace($env:DB_URL)) {
    $env:DB_URL = "jdbc:mysql://127.0.0.1:3306/$database`?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
}
if ($env:DB_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:3306/campus_activity_miniapp_dev(?:\?|$)') {
    throw 'DB_URL must target the local campus_activity_miniapp_dev database.'
}

try {
    if ([string]::IsNullOrWhiteSpace($env:WECHAT_APP_ID)) {
        $env:WECHAT_APP_ID = 'wx141754dff3369b18'
        $loadedWechatAppId = $true
    }

    $mapKeyFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'backups/local-map-keys.clixml'
    if (Test-Path -LiteralPath $mapKeyFile) {
        $mapKeys = Import-Clixml -LiteralPath $mapKeyFile
        foreach ($entry in @{'AMAP_JS_KEY' = 'JsKey'; 'AMAP_SECURITY_JS_CODE' = 'SecurityJsCode'; 'AMAP_WEB_SERVICE_KEY' = 'WebServiceKey'}.GetEnumerator()) {
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($entry.Key))) {
                $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($mapKeys.($entry.Value))
                try {
                    [Environment]::SetEnvironmentVariable($entry.Key,
                        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer), 'Process')
                    $loadedMapVariables += $entry.Key
                } finally {
                    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
                }
            }
        }
    }

    $deepSeekKeyFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'backups/local-deepseek-key.clixml'
    if ((Test-Path -LiteralPath $deepSeekKeyFile) -and [string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
        $encryptedKey = Import-Clixml -LiteralPath $deepSeekKeyFile
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($encryptedKey)
        try {
            $env:DEEPSEEK_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
            $loadedDeepSeekKey = $true
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    }

    $securePassword = Read-Host 'MySQL password' -AsSecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }

    $env:MYSQL_PWD = $env:DB_PASSWORD
    try {
        $selectedDatabase = & mysql --protocol=TCP --host=127.0.0.1 --port=3306 --user=$User --connect-timeout=5 --database=$database --batch --skip-column-names --execute='SELECT DATABASE();'
        if ($LASTEXITCODE -ne 0 -or $selectedDatabase.Trim() -ne $database) {
            throw 'MySQL connection failed or the selected database is not campus_activity_miniapp_dev.'
        }
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }

    if ($PromptWechatSecret) {
        $secureWechatSecret = Read-Host 'WeChat AppSecret (hidden)' -AsSecureString
        $secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureWechatSecret)
        try {
            $env:WECHAT_APP_SECRET = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
        }
        if ([string]::IsNullOrWhiteSpace($env:WECHAT_APP_SECRET)) {
            throw 'WeChat AppSecret was not supplied.'
        }
    } elseif ([string]::IsNullOrWhiteSpace($env:WECHAT_APP_SECRET)) {
        $wechatSecretFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'backups/local-wechat-secret.clixml'
        if (Test-Path -LiteralPath $wechatSecretFile) {
            $encryptedSecret = Import-Clixml -LiteralPath $wechatSecretFile
            $secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($encryptedSecret)
            try {
                $env:WECHAT_APP_SECRET = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
            } finally {
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
            }
        }
    }

    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    $env:DB_USER = $User

    Push-Location $PSScriptRoot
    try {
        if ($VerifyUserDeletion) {
            $env:RUN_USER_DELETION_MYSQL_CHECK = 'true'
            try {
                & .\mvnw.cmd '-Dtest=AdminUserDeletionMysqlTests' test -q
                if ($LASTEXITCODE -ne 0) { throw 'Transactional MySQL user-deletion check failed; service was not started.' }
            } finally {
                Remove-Item Env:RUN_USER_DELETION_MYSQL_CHECK -ErrorAction SilentlyContinue
            }
        }
        & .\mvnw.cmd spring-boot:run
    } finally {
        Pop-Location
    }
} finally {
    if ($loadedWechatAppId) {
        Remove-Item Env:WECHAT_APP_ID -ErrorAction SilentlyContinue
    }
    if ($loadedDeepSeekKey) {
        Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
    }
    foreach ($name in $loadedMapVariables) {
        Remove-Item -Path "Env:$name" -ErrorAction SilentlyContinue
    }
    Remove-Item Env:MYSQL_PWD, Env:DB_PASSWORD, Env:DB_USER, Env:DB_URL, Env:SPRING_PROFILES_ACTIVE, Env:WECHAT_APP_SECRET -ErrorAction SilentlyContinue
}
