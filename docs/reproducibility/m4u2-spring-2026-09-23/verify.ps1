param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$MavenUserHome,
    [switch]$CollectOnly
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidenceDirectory = $PSScriptRoot
$modules = @('analyzer','analyzer-maven','analyzer-filesystem','analyzer-javaparser','backend')
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$env:MAVEN_USER_HOME = (Resolve-Path -LiteralPath $MavenUserHome).Path
$cache = Join-Path $env:MAVEN_USER_HOME 'repository'
Set-Location -LiteralPath $repository
$utf8 = [System.Text.UTF8Encoding]::new($false)
function Write-Json($name, $value) {
    [System.IO.File]::WriteAllText((Join-Path $evidenceDirectory $name), ($value | ConvertTo-Json -Depth 12) + "`n", $utf8)
}
function Input-Hashes {
    $roots = @($modules | ForEach-Object { "$_/src" }) + @('.mvn')
    $paths = @(& rg --files --hidden @roots) + @($modules | ForEach-Object { "$_/pom.xml" }) + @('pom.xml','mvnw','mvnw.cmd')
    if ($LASTEXITCODE -ne 0) { throw 'Source enumeration failed' }
    @($paths | ForEach-Object { $_.Replace('\','/') } | Sort-Object -Unique | ForEach-Object {
        [ordered]@{ path=$_; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
}
if ($CollectOnly) {
    $execution = Get-Content -LiteralPath (Join-Path $evidenceDirectory 'execution.json') -Raw | ConvertFrom-Json
    $started = ([DateTime]$execution.startedUtc).ToUniversalTime()
    $ended = ([DateTime]$execution.finishedUtc).ToUniversalTime()
    $buildExit = $execution.exitCode
    $inputs = @(Get-Content -LiteralPath (Join-Path $evidenceDirectory 'source-hashes.json') -Raw | ConvertFrom-Json)
} else {
    $inputs = @(Input-Hashes)
    Write-Json 'source-hashes.json' $inputs
    & .\mvnw.cmd --version *> (Join-Path $evidenceDirectory 'toolchain.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Wrapper toolchain check failed' }
    $started = [DateTime]::UtcNow
    & .\mvnw.cmd -B -ntp verify -q "-Dmaven.repo.local=$cache" *> (Join-Path $evidenceDirectory 'verify.log')
    $buildExit = $LASTEXITCODE
    $ended = [DateTime]::UtcNow
    $unchanged = (($inputs | ConvertTo-Json -Depth 5 -Compress) -ceq ((@(Input-Hashes)) | ConvertTo-Json -Depth 5 -Compress))
    Write-Json 'execution.json' ([ordered]@{
        command='.\mvnw.cmd -B -ntp verify -q -Dmaven.repo.local=<MAVEN_USER_HOME>/repository'
        startedUtc=$started.ToString('o'); finishedUtc=$ended.ToString('o'); exitCode=$buildExit
        seconds=($ended-$started).TotalSeconds; enforcerEnabled=$true; cleanBuild=$false
        sourceInputsUnchanged=$unchanged; gitInspected=$false; targetRepositoryExecuted=$false
    })
}
$unchanged = (($inputs | ConvertTo-Json -Depth 5 -Compress) -ceq ((@(Input-Hashes)) | ConvertTo-Json -Depth 5 -Compress))
$reports = @(); $excluded = @()
foreach ($module in $modules) {
    foreach ($file in @(Get-ChildItem -LiteralPath "$module/target/surefire-reports" -Filter 'TEST-*.xml' -File)) {
        [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
        $suite = $report.testsuite
        $row = [pscustomobject][ordered]@{
            module=$module; suite=[string]$suite.name; tests=[int]$suite.tests
            failures=[int]$suite.failures; errors=[int]$suite.errors; skipped=[int]$suite.skipped
            seconds=[double]::Parse([string]$suite.time,[Globalization.CultureInfo]::InvariantCulture)
            fresh=($file.LastWriteTimeUtc -ge $started -and $file.LastWriteTimeUtc -le $ended)
            path="$module/target/surefire-reports/$($file.Name)"
            sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        if ($row.fresh) { $reports += $row } else { $excluded += $row }
    }
}
$expected = @(& rg --files @($modules | ForEach-Object { "$_/src/test" }) -g '*Test.java' | ForEach-Object {
    $package = (Select-String -LiteralPath $_ -Pattern '^package\s+([\w.]+);').Matches.Groups[1].Value
    $class = [System.IO.Path]::GetFileNameWithoutExtension($_)
    "$package.$class"
})
$missing = @($expected | Where-Object { $_ -notin $reports.suite })
$summary = [pscustomobject][ordered]@{
    suites=$reports.Count; tests=($reports | Measure-Object tests -Sum).Sum
    failures=($reports | Measure-Object failures -Sum).Sum; errors=($reports | Measure-Object errors -Sum).Sum
    skipped=($reports | Measure-Object skipped -Sum).Sum
    allReportsFresh=(@($reports | Where-Object { -not $_.fresh }).Count -eq 0)
    expectedSuites=$expected.Count; missingSuites=$missing; sourceInputsUnchanged=$unchanged
    excludedReports=$excluded
    reports=$reports
}
Write-Json 'verification.json' $summary
$artifacts = @(
    'org/springframework/spring-context/6.2.0/spring-context-6.2.0.jar',
    'org/springframework/spring-beans/6.2.0/spring-beans-6.2.0.jar',
    'org/springframework/spring-expression/6.2.0/spring-expression-6.2.0.jar',
    'org/springframework/spring-web/6.2.0/spring-web-6.2.0.jar',
    'org/springframework/boot/spring-boot/3.4.0/spring-boot-3.4.0.jar',
    'org/springframework/boot/spring-boot-autoconfigure/3.4.0/spring-boot-autoconfigure-3.4.0.jar',
    'org/springframework/data/spring-data-commons/3.4.0/spring-data-commons-3.4.0.jar',
    'org/springframework/data/spring-data-jpa/3.4.0/spring-data-jpa-3.4.0.jar',
    'jakarta/inject/jakarta.inject-api/2.0.1/jakarta.inject-api-2.0.1.jar',
    'javax/inject/javax.inject/1/javax.inject-1.jar'
)
Write-Json 'artifact-hashes.json' @($artifacts | ForEach-Object {
    [ordered]@{ cacheRelativePath=$_; sha256=(Get-FileHash -LiteralPath (Join-Path $cache $_) -Algorithm SHA256).Hash.ToLowerInvariant() }
})
$summary | Select-Object suites,tests,failures,errors,skipped,allReportsFresh | ConvertTo-Json -Compress
if ($buildExit -ne 0) { Get-Content -LiteralPath (Join-Path $evidenceDirectory 'verify.log') -Tail 18; exit $buildExit }
if (-not $unchanged -or $missing.Count -gt 0 -or -not $summary.allReportsFresh -or $summary.tests -eq 0 -or $summary.failures -gt 0 -or $summary.errors -gt 0 -or $summary.skipped -gt 0) {
    throw 'Verification evidence is incomplete or has failures/skips'
}
