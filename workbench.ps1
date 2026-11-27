$PSScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

# Load .env if present
$envFile = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#') -and $line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
}

# Paths and configuration
$evoJar = Join-Path $PSScriptRoot 'cli/target/evo.jar'
if (-not (Test-Path -LiteralPath $evoJar)) {
    $evoJar = Join-Path $PSScriptRoot 'cli/target/upgrade-verify/evo.jar'
}

$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
$jdkPath = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21.0.12.1' }
$m2Cache = Join-Path $env:USERPROFILE '.m2\repository'
$storeDir = 'D:\UIT\evo-store\analysis'
if (-not (Test-Path $storeDir)) {
    $storeDir = Join-Path $PSScriptRoot '.evo\analysis'
    New-Item -ItemType Directory -Path $storeDir -Force | Out-Null
}

$port = if ($args.Length -ge 1) { $args[0] } else { 8080 }

Write-Host "Starting Architecture Workbench on http://localhost:$port (store: $storeDir) ..." -ForegroundColor Cyan

$sqliteTmp = Join-Path $PSScriptRoot '.evo\sqlite-tmp'
if (-not (Test-Path $sqliteTmp)) {
    New-Item -ItemType Directory -Path $sqliteTmp -Force | Out-Null
}

$heapOpts = if ($env:EVO_HEAP) { $env:EVO_HEAP -split '\s+' } else { @("-Xms1g", "-Xmx8g") }

& $javaBin @heapOpts -XX:+UseG1GC -XX:SoftRefLRUPolicyMSPerMB=1 "-Dfile.encoding=UTF-8" "-Dorg.sqlite.tmpdir=$sqliteTmp" -cp $evoJar com.evolution.backend.analysis.WorkbenchApiMain `
    $PSScriptRoot 'urn:local:evo' $jdkPath 21 $m2Cache $storeDir $port
