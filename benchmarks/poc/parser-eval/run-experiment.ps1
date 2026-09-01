[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$experimentRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$targetRepository = Join-Path $experimentRoot 'target-repo'
$targetSourceRoot = Join-Path $targetRepository 'src\main\java'
$groundTruth = Join-Path $experimentRoot 'ground-truth.json'
$resultsDirectory = Join-Path $experimentRoot 'results'
$expectedCommit = '818c4136ea971c21674525f9053de0d9c7ad8cfe'
$dependencyPlugin = 'org.apache.maven.plugins:maven-dependency-plugin:3.10.0'

function Invoke-Checked {
    param(
        [Parameter(Mandatory)] [string] $Executable,
        [Parameter(Mandatory)] [string[]] $Arguments
    )

    Write-Host ('> ' + $Executable + ' ' + ($Arguments -join ' '))
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable exited with code $LASTEXITCODE"
    }
}

function Assert-SafeResultsPath {
    $resolvedParent = (Resolve-Path -LiteralPath (Split-Path -Parent $resultsDirectory)).Path
    if ($resolvedParent -ne $experimentRoot -or (Split-Path -Leaf $resultsDirectory) -ne 'results') {
        throw "Refusing to clean unexpected results path: $resultsDirectory"
    }
}

$safeDirectory = $targetRepository.Replace('\', '/')
$commit = (& git -c "safe.directory=$safeDirectory" -C $targetRepository rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read the target repository commit'
}
if ($commit -ne $expectedCommit) {
    throw "PetClinic commit mismatch. Expected $expectedCommit but found $commit"
}
$targetStatus = @(& git -c "safe.directory=$safeDirectory" -C $targetRepository status --porcelain)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect the target repository status'
}
if ($targetStatus.Count -ne 0) {
    throw "PetClinic fixture is dirty:`n$($targetStatus -join [Environment]::NewLine)"
}

Assert-SafeResultsPath
if (Test-Path -LiteralPath $resultsDirectory) {
    Remove-Item -Recurse -Force -LiteralPath $resultsDirectory
}
New-Item -ItemType Directory -Path $resultsDirectory | Out-Null

Invoke-Checked 'mvn' @('-q', '-f', (Join-Path $experimentRoot 'pom.xml'), 'clean', 'verify', 'package')

$rawClasspath = Join-Path $resultsDirectory 'config-b-classpath.raw.txt'
$lineClasspath = Join-Path $resultsDirectory 'config-b-classpath.txt'
$dependencyManifest = Join-Path $resultsDirectory 'config-b-dependencies.txt'

Invoke-Checked 'mvn' @(
    '-q',
    '-f', (Join-Path $targetRepository 'pom.xml'),
    "$dependencyPlugin`:build-classpath",
    '-DincludeScope=compile',
    "-Dmdep.outputFile=$rawClasspath"
)
Invoke-Checked 'mvn' @(
    '-q',
    '-f', (Join-Path $targetRepository 'pom.xml'),
    "$dependencyPlugin`:list",
    '-DincludeScope=compile',
    '-DoutputAbsoluteArtifactFilename=true',
    "-DoutputFile=$dependencyManifest",
    '-DappendOutput=false'
)

$rawClasspathText = [IO.File]::ReadAllText($rawClasspath).Trim()
$classpathEntries = [string[]] @($rawClasspathText -split [IO.Path]::PathSeparator | Where-Object { $_ })
if ($classpathEntries.Count -eq 0) {
    throw 'Config B compile classpath is empty'
}
$duplicates = @($classpathEntries | Group-Object | Where-Object { $_.Count -gt 1 })
if ($duplicates.Count -ne 0) {
    throw "Config B classpath contains duplicate paths: $($duplicates.Name -join ', ')"
}
foreach ($entry in $classpathEntries) {
    if (-not (Test-Path -LiteralPath $entry -PathType Leaf) -or -not $entry.EndsWith('.jar')) {
        throw "Invalid Config B classpath entry: $entry"
    }
}
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllLines($lineClasspath, $classpathEntries, $utf8WithoutBom)
Remove-Item -LiteralPath $rawClasspath

$evaluatorJar = Join-Path $experimentRoot 'target\parser-eval-1.0-SNAPSHOT-jar-with-dependencies.jar'
$configAResult = Join-Path $resultsDirectory 'config-a-results.json'
$configBResult = Join-Path $resultsDirectory 'config-b-results.json'

Invoke-Checked 'java' @(
    '-jar', $evaluatorJar,
    $targetSourceRoot,
    $configAResult,
    'CONFIG_A',
    $groundTruth,
    '-',
    $commit
)
Invoke-Checked 'java' @(
    '-jar', $evaluatorJar,
    $targetSourceRoot,
    $configBResult,
    'CONFIG_B',
    $groundTruth,
    $lineClasspath,
    $commit
)

$configA = Get-Content -Raw -LiteralPath $configAResult | ConvertFrom-Json
$configB = Get-Content -Raw -LiteralPath $configBResult | ConvertFrom-Json
if ($configA.summary.failedFiles -ne 0 -or $configB.summary.failedFiles -ne 0) {
    throw 'One or more PetClinic files failed to parse'
}
if ($configA.summary.relationshipErrors -ne 0 -or $configB.summary.relationshipErrors -ne 0) {
    throw 'The evaluator recorded an internal relationship error'
}
if ($configA.summary.relationshipsAttempted -ne $configB.summary.relationshipsAttempted) {
    throw 'Config A and Config B did not attempt the same relationship denominator'
}
if ($configA.groundTruthSummary.failed -ne 0 -or $configB.groundTruthSummary.failed -ne 0) {
    throw 'At least one ground-truth expectation failed'
}
if ($configA.experiment.evaluatorArtifactSha256 -ne $configB.experiment.evaluatorArtifactSha256) {
    throw 'Config A and Config B used different evaluator artifacts'
}
if (@($configA.experiment.classpathManifest).Count -ne 0) {
    throw 'Config A unexpectedly contains dependency JARs'
}
if (@($configB.experiment.classpathManifest).Count -ne $classpathEntries.Count) {
    throw 'Config B provenance does not match the generated compile classpath'
}

[pscustomobject]@{
    Configuration = 'CONFIG_A'
    Attempted = $configA.summary.relationshipsAttempted
    Resolved = $configA.summary.relationshipsResolved
    Unresolved = $configA.summary.relationshipsUnresolved
    GroundTruthPassed = "$($configA.groundTruthSummary.passed)/$($configA.groundTruthSummary.total)"
    ClasspathJars = @($configA.experiment.classpathManifest).Count
}, [pscustomobject]@{
    Configuration = 'CONFIG_B'
    Attempted = $configB.summary.relationshipsAttempted
    Resolved = $configB.summary.relationshipsResolved
    Unresolved = $configB.summary.relationshipsUnresolved
    GroundTruthPassed = "$($configB.groundTruthSummary.passed)/$($configB.groundTruthSummary.total)"
    ClasspathJars = @($configB.experiment.classpathManifest).Count
} | Format-Table -AutoSize
