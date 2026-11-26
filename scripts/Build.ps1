$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Tools.ps1"
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Find-JavaHome
$maven = Find-Maven

Push-Location "$projectRoot/frontend"
try {
    # Skip npm ci when the lockfile is unchanged: reinstalling wipes node_modules and is slow.
    $lock = "$projectRoot/frontend/package-lock.json"
    $marker = "$projectRoot/frontend/node_modules/.xunlian-lock"
    $hash = if (Test-Path -LiteralPath $lock) { (Get-FileHash -Algorithm SHA256 -LiteralPath $lock).Hash } else { '' }
    $installed = Test-Path -LiteralPath $marker
    $current = if ($installed) { (Get-Content -LiteralPath $marker -Raw).Trim() } else { '' }
    $healthy = Test-Path -LiteralPath "$projectRoot/frontend/node_modules/.bin/vue-tsc.cmd"
    if (-not $installed -or $current -ne $hash) {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        Set-Content -LiteralPath $marker -Value $hash -Encoding utf8
    } elseif (-not $healthy) {
        # 上一次安装被中断时补全，不整体清空 node_modules。
        & npm.cmd install --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency repair failed.' }
    } else {
        Write-Host 'Frontend dependencies unchanged, skipping npm ci.'
    }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    $static = "$projectRoot/backend/src/main/resources/static"
    New-Item -ItemType Directory -Force -Path $static | Out-Null
    Copy-Item -Path "$projectRoot/frontend/dist/*" -Destination $static -Recurse -Force
} finally { Pop-Location }

Push-Location "$projectRoot/backend"
try {
    & $maven --no-transfer-progress clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend build or verification failed.' }
} finally { Pop-Location }
Write-Host 'Build passed. Run scripts/Start.ps1 to start Xunlian.'
