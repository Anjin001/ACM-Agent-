$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Tools.ps1"
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Find-JavaHome
$jar = "$projectRoot/backend/target/acm-coach-0.1.0.jar"
if (-not (Test-Path -LiteralPath $jar)) { & "$PSScriptRoot/Build.ps1" }
Write-Host 'Xunlian is starting at http://127.0.0.1:18080'
Write-Host 'Keep this terminal open. Press Ctrl+C to stop.'
Push-Location $projectRoot
try { & "$env:JAVA_HOME/bin/java.exe" -jar $jar }
finally { Pop-Location }
