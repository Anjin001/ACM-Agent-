$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Tools.ps1"
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Find-JavaHome
$maven = Find-Maven

Push-Location "$projectRoot/frontend"
try {
    & npm.cmd ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
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
