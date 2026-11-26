$ErrorActionPreference = 'Stop'

function Test-JavaHome {
    param([string]$Path)
    if (-not $Path) { return $false }
    if (-not (Test-Path -LiteralPath "$Path/bin/javac.exe")) { return $false }
    $version = (Get-Item -LiteralPath "$Path/bin/javac.exe").VersionInfo.ProductVersion
    if ($version -match '^(\d+)') { return ([int]$Matches[1] -ge 17) }
    return $false
}

function Find-JavaHome {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    # javac/java already on PATH: resolve back to the JDK root
    foreach ($name in @('javac.exe', 'java.exe')) {
        $found = Get-Command $name -ErrorAction SilentlyContinue
        if ($found -and $found.Source) { $candidates.Add((Split-Path (Split-Path $found.Source -Parent) -Parent)) }
    }
    # Registry keys written by most JDK installers
    foreach ($key in @('HKLM:\SOFTWARE\JavaSoft\JDK', 'HKLM:\SOFTWARE\JavaSoft\Java Development Kit', 'HKLM:\SOFTWARE\Eclipse Adoptium\JDK')) {
        Get-ChildItem -Path $key -ErrorAction SilentlyContinue | ForEach-Object {
            $jdkHome = (Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue).JavaHome
            if ($jdkHome) { $candidates.Add($jdkHome) }
        }
    }
    # Common install layouts, including JDKs installed outside C:
    $patterns = @(
        '*/java/*', '*/Java/*', '*/jdk*', '*/JDK*', '*/dev/*', '*/tools/*',
        '*/Program Files/Java/*', '*/Program Files/Eclipse Adoptium/*',
        '*/Program Files/Microsoft/jdk*', '*/Program Files/JetBrains/*/jbr'
    )
    foreach ($drive in @('C:', 'D:', 'E:', 'F:')) {
        if (-not (Test-Path -LiteralPath "$drive/")) { continue }
        foreach ($pattern in $patterns) {
            Get-ChildItem -Path "$drive/$pattern" -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-JavaHome $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
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
