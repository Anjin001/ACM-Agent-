$ErrorActionPreference = 'Stop'

function Find-JavaHome {
    $candidates = @($env:JAVA_HOME)
    $jetbrains = Get-ChildItem -Path 'C:/Program Files/JetBrains/*/jbr' -Directory -ErrorAction SilentlyContinue
    $candidates += @($jetbrains | ForEach-Object { $_.FullName })
    $candidates += @(Get-ChildItem -Path 'C:/Program Files/Java/*','C:/Program Files/Eclipse Adoptium/*','C:/Program Files/Microsoft/jdk*' -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath "$candidate/bin/javac.exe")) {
            $version = (Get-Item -LiteralPath "$candidate/bin/javac.exe").VersionInfo.ProductVersion
            if ($version -match '^(\d+)' -and [int]$Matches[1] -ge 17) { return $candidate }
        }
    }
    throw 'JDK 17+ is required. Install a JDK or set JAVA_HOME, then retry.'
}

function Find-Maven {
    $command = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $cache = Join-Path $env:USERPROFILE '.cache/acm-coach-tools/maven'
    $cached = Get-ChildItem -Path "$cache/*/bin/mvn.cmd" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cached) { return $cached.FullName }
    Write-Host 'Downloading Maven 3.9.11 to the user cache...'
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    $archive = Join-Path $cache 'apache-maven.zip'
    & curl.exe -L --fail --max-time 120 --silent --show-error 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' -o $archive
    if ($LASTEXITCODE -ne 0) { throw 'Maven download failed. Install Maven manually and retry.' }
    Expand-Archive -LiteralPath $archive -DestinationPath $cache -Force
    return (Join-Path $cache 'apache-maven-3.9.11/bin/mvn.cmd')
}
