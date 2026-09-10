[CmdletBinding()]
param(
    [string]$TargetRepository = "$PSScriptRoot\temp_repo\spring-petclinic",
    [string]$RepositoryCoordinate = "https://github.com/spring-projects/spring-petclinic.git",
    [string]$RepositoryLabel = "spring-petclinic",
    [int]$FallbackPlatformRelease = 17,
    [string]$MavenRepository = "",
    [string]$JdkHome = "",
    [string[]]$RemotePomRepositories = @("https://repo.maven.apache.org/maven2/"),
    [string]$DocumentationDirectory = "$PSScriptRoot\..\docs\reproducibility\g2-petclinic-check-2026-09-09",
    [switch]$Publish
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($MavenRepository)) {
    if (![string]::IsNullOrWhiteSpace($env:G2_MAVEN_REPOSITORY)) {
        $MavenRepository = $env:G2_MAVEN_REPOSITORY
    } elseif (![string]::IsNullOrWhiteSpace($env:MAVEN_USER_HOME)) {
        $MavenRepository = Join-Path $env:MAVEN_USER_HOME 'repository'
    } elseif (![string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        $MavenRepository = Join-Path $env:USERPROFILE '.m2\repository'
    } else {
        throw 'Specify -MavenRepository or G2_MAVEN_REPOSITORY; no user profile is available'
    }
}
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    if (![string]::IsNullOrWhiteSpace($env:G2_JDK_HOME)) {
        $JdkHome = $env:G2_JDK_HOME
    } elseif (![string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $JdkHome = $env:JAVA_HOME
    } else {
        throw 'Specify -JdkHome or G2_JDK_HOME/JAVA_HOME'
    }
}

$repoRoot = (Resolve-Path -LiteralPath "$PSScriptRoot\..").Path
$benchmarkDir = Join-Path $repoRoot "benchmarks\g2-pipeline"
$targetRepo = (Resolve-Path -LiteralPath $TargetRepository).Path
$mavenRepo = (Resolve-Path -LiteralPath $MavenRepository).Path
$configuredJdk = (Resolve-Path -LiteralPath $JdkHome).Path
$safeLabel = $RepositoryLabel -replace '[^A-Za-z0-9._-]', '-'
$outputDir = Join-Path $benchmarkDir "target\g2-output-$safeLabel"
$artifactCacheBase = Join-Path $outputDir 'dependency-caches'
$docsDir = [System.IO.Path]::GetFullPath($DocumentationDirectory)
$wrapper = Join-Path $repoRoot 'mvnw.cmd'
$mavenUserHome = Split-Path -Parent $mavenRepo
$revision = (& git -C $targetRepo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($revision)) { throw 'Cannot determine target revision' }
$targetStatus = @(& git -C $targetRepo status --short)
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect target repository status' }
if ($targetStatus.Count -ne 0) { throw "Target repository must be clean: $targetRepo" }

$env:MAVEN_USER_HOME = $mavenUserHome
Write-Host "1. Installing trusted platform artifacts with the pinned Maven Wrapper..."
& $wrapper @('-o', '-B', '-ntp', "-Dmaven.repo.local=$($mavenRepo.Replace('\', '/'))", 'install', '-DskipTests')
if ($LASTEXITCODE -ne 0) { throw 'Failed to install platform artifacts' }

Write-Host "2. Testing and compiling the standalone benchmark runner..."
& $wrapper @('-o', '-B', '-ntp', "-Dmaven.repo.local=$($mavenRepo.Replace('\', '/'))", '-f', "$benchmarkDir\pom.xml", 'clean', 'test')
if ($LASTEXITCODE -ne 0) { throw 'Failed to test benchmark project' }

$env:G2_BENCHMARK_REPOSITORY_ROOT = $targetRepo
$env:G2_BENCHMARK_OUTPUT_DIRECTORY = $outputDir
$env:G2_BENCHMARK_REPOSITORY_COORDINATE = $RepositoryCoordinate
$env:G2_BENCHMARK_REVISION = $revision
$env:G2_BENCHMARK_MAVEN_CACHE = $artifactCacheBase
$env:G2_BENCHMARK_JDK_HOME = $configuredJdk
$env:G2_BENCHMARK_FALLBACK_PLATFORM_RELEASE = [string]$FallbackPlatformRelease
$env:G2_BENCHMARK_REMOTE_POM_REPOSITORIES = $RemotePomRepositories -join '|'
Write-Host "3. Executing the passive G2 pipeline for $RepositoryLabel at $revision..."
& $wrapper @('-o', '-B', '-ntp', "-Dmaven.repo.local=$($mavenRepo.Replace('\', '/'))", '-f', "$benchmarkDir\pom.xml",
    'exec:java', '-Dexec.mainClass=com.evolution.benchmark.G2BenchmarkRunner')
if ($LASTEXITCODE -ne 0) { throw 'Benchmark execution failed' }

$candidateReport = Join-Path $outputDir 'README.md'
$env:G2_REPORT_METRICS_DIRECTORY = $outputDir
$env:G2_REPORT_OUTPUT_FILE = $candidateReport
$env:G2_REPORT_REPOSITORY_LABEL = $RepositoryLabel
Write-Host '4. Rendering the checkpoint report...'
& $wrapper @('-o', '-B', '-ntp', "-Dmaven.repo.local=$($mavenRepo.Replace('\', '/'))", '-f', "$benchmarkDir\pom.xml",
    'exec:java', '-Dexec.mainClass=com.evolution.benchmark.G2ReportRenderer')
if ($LASTEXITCODE -ne 0) { throw 'Report rendering failed' }

if ($Publish) {
    Write-Host "5. Publishing regenerated evidence to $docsDir..."
    if (!(Test-Path -LiteralPath $docsDir)) { New-Item -ItemType Directory -Force -Path $docsDir | Out-Null }
    Copy-Item -LiteralPath $candidateReport -Destination (Join-Path $docsDir 'README.md') -Force
    Get-ChildItem -LiteralPath $outputDir -File | Where-Object { $_.Extension -in '.json', '.tsv' } |
        Copy-Item -Destination $docsDir -Force
} else {
    Write-Host "Dry run complete. Raw artifacts: $outputDir"
}
