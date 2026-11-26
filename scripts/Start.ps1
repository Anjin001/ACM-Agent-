param([string]$Port = $env:XUNLIAN_PORT)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Tools.ps1"
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Find-JavaHome
$jar = "$projectRoot/backend/target/acm-coach-0.1.0.jar"
if (-not (Test-Path -LiteralPath $jar)) { & "$PSScriptRoot/Build.ps1" }

if (-not $Port) { $Port = '18080' }
$address = if ($env:SERVER_ADDRESS) { $env:SERVER_ADDRESS } else { '127.0.0.1' }
$occupied = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($occupied) {
    $owner = (Get-Process -Id $occupied[0].OwningProcess -ErrorAction SilentlyContinue).ProcessName
    $detail = ''
    if ($owner) { $detail = " by $owner" }
    throw "Port $Port is already in use$detail. Set `$env:XUNLIAN_PORT to another port, then retry."
}

Write-Host "Xunlian is starting at http://${address}:$Port"
Write-Host 'Keep this terminal open. Press Ctrl+C to stop.'
Push-Location $projectRoot
try { & "$env:JAVA_HOME/bin/java.exe" -jar $jar "--server.port=$Port" "--server.address=$address" }
finally { Pop-Location }
