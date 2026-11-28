param(
    [switch]$Docker,
    [int]$Port = 8080
)

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

if ($Docker) {
    $env:EVO_PORT = "$Port"
    Write-Host "Starting Online Evo Web App via Docker Compose on http://localhost:$Port ..." -ForegroundColor Cyan
    docker compose up -d --build
    exit $LASTEXITCODE
}

$workbenchJar = Join-Path $PSScriptRoot 'backend/target/evo-workbench.jar'
if (-not (Test-Path -LiteralPath $workbenchJar)) {
    Write-Error "Missing standalone web app JAR: $workbenchJar`nRun: .\mvnw.cmd -o -B -ntp -pl .,backend -am -DskipTests package"
    exit 1
}

$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
$jdkPath = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21.0.12.1' }
$m2Cache = if ($env:EVO_M2_REPOSITORY) { $env:EVO_M2_REPOSITORY } else { Join-Path $env:USERPROFILE '.m2\repository' }
$stateDir = if ($env:EVO_STATE_DIR) { $env:EVO_STATE_DIR } else { Join-Path $PSScriptRoot '.evo\online-store' }
New-Item -ItemType Directory -Path $stateDir -Force | Out-Null

$sqliteTmp = Join-Path $stateDir 'sqlite-tmp'
New-Item -ItemType Directory -Path $sqliteTmp -Force | Out-Null

$bindHost = if ($env:EVO_BIND_HOST) { $env:EVO_BIND_HOST } else { '127.0.0.1' }
$heapOpts = if ($env:EVO_HEAP) { $env:EVO_HEAP -split '\s+' } else { @('-Xms1g', '-Xmx6g') }

Write-Host "Starting Online Evo Web App on http://${bindHost}:$Port (state: $stateDir) ..." -ForegroundColor Cyan

& $javaBin @heapOpts -XX:+UseG1GC -XX:SoftRefLRUPolicyMSPerMB=1 '-Dfile.encoding=UTF-8' "-Dorg.sqlite.tmpdir=$sqliteTmp" `
    -jar $workbenchJar --online --host $bindHost --port $Port --state-dir $stateDir --jdk $jdkPath --release 21 --maven-cache $m2Cache
