param([Parameter(Mandatory=$true)][string]$OutputDirectory,[switch]$Focused)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $taskOutput) { throw 'Choose a new output directory; verification records are immutable.' }
New-Item -ItemType Directory -Path $taskOutput | Out-Null
Push-Location $taskRoot
try {
    $taskModules = @('analyzer','analyzer-maven','analyzer-filesystem','analyzer-javaparser','backend')
    $taskInputs = @('pom.xml','mvnw.cmd','.mvn/wrapper/maven-wrapper.properties')
    foreach ($taskModule in $taskModules) {
        $taskInputs += "$taskModule/pom.xml"
        $taskInputs += Get-ChildItem "$taskModule/src" -Recurse -File | ForEach-Object { [IO.Path]::GetRelativePath($taskRoot,$_.FullName).Replace('\','/') }
    }
    function Get-InputHashes {
        @($taskInputs | Sort-Object -Unique | ForEach-Object {
            [ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
        })
    }
    $taskBefore = Get-InputHashes
    $taskBefore | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $taskOutput 'input-hashes.json') -Encoding utf8
    $taskStart = [DateTime]::UtcNow
    if ($Focused) {
        $taskCommand='.\mvnw.cmd -q -pl analyzer-javaparser -Denforcer.skip=true -Dtest=SourceToSpringPlanTest test'
        & .\mvnw.cmd -q -pl analyzer-javaparser '-Denforcer.skip=true' '-Dtest=SourceToSpringPlanTest' test *> (Join-Path $taskOutput 'verify.log')
    } else {
        $taskCommand='.\mvnw.cmd -q verify'
        & .\mvnw.cmd -q verify *> (Join-Path $taskOutput 'verify.log')
    }
    $taskExit = $LASTEXITCODE
    $taskFinish = [DateTime]::UtcNow
    $taskAfter = Get-InputHashes
    $taskStable = ($taskBefore | ConvertTo-Json -Depth 5 -Compress) -ceq ($taskAfter | ConvertTo-Json -Depth 5 -Compress)
    $taskSuites = @(); $taskStale = @()
    foreach ($taskModule in $taskModules) {
        foreach ($taskReport in Get-ChildItem "$taskModule/target/surefire-reports/TEST-*.xml") {
            $taskPath = [IO.Path]::GetRelativePath($taskRoot,$taskReport.FullName).Replace('\','/')
            if ($taskReport.LastWriteTimeUtc -lt $taskStart) { $taskStale += $taskPath; continue }
            [xml]$taskXml = Get-Content -LiteralPath $taskReport.FullName -Raw
            $taskSuite = $taskXml.testsuite
            $taskSuites += [ordered]@{name=$taskSuite.name;tests=[int]$taskSuite.tests;failures=[int]$taskSuite.failures;
                errors=[int]$taskSuite.errors;skipped=[int]$taskSuite.skipped;seconds=[double]$taskSuite.time;
                report=$taskPath;sha256=(Get-FileHash $taskReport.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
    }
    $taskSuiteRecords = @($taskSuites | ForEach-Object { [pscustomobject]$_ })
    $taskResult = [ordered]@{command=$taskCommand;baseCommit=(git rev-parse HEAD);enforcerEnabled=(!$Focused);
        startedUtc=$taskStart.ToString('o');finishedUtc=$taskFinish.ToString('o');exitCode=$taskExit;
        inputCount=$taskBefore.Count;inputsUnchanged=$taskStable;suiteCount=$taskSuites.Count;
        tests=($taskSuiteRecords | Measure-Object tests -Sum).Sum;failures=($taskSuiteRecords | Measure-Object failures -Sum).Sum;
        errors=($taskSuiteRecords | Measure-Object errors -Sum).Sum;skipped=($taskSuiteRecords | Measure-Object skipped -Sum).Sum;
        excludedStaleReports=$taskStale;suites=$taskSuites}
    $taskResult | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $taskOutput 'verification.json') -Encoding utf8
    [pscustomobject]$taskResult | Select-Object command,exitCode,inputCount,inputsUnchanged,suiteCount,tests,failures,errors,skipped | ConvertTo-Json -Compress
    if (!$taskStable -or $taskSuites.Count -eq 0) { exit 2 }
    exit $taskExit
} finally { Pop-Location }
