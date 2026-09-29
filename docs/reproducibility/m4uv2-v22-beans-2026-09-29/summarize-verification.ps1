param([Parameter(Mandatory=$true)][string]$Record,[Parameter(Mandatory=$true)][string]$OutputFile)
$ErrorActionPreference='Stop'
if (Test-Path -LiteralPath $OutputFile) { throw 'Choose a new summary file; prior evidence is immutable.' }
$taskRecord=Get-Content -LiteralPath $Record -Raw | ConvertFrom-Json
$taskSummary=[ordered]@{sourceRecord=[IO.Path]::GetFileName($Record);
    sourceSha256=(Get-FileHash -LiteralPath $Record -Algorithm SHA256).Hash.ToLowerInvariant();
    exitCode=$taskRecord.exitCode;inputsUnchanged=$taskRecord.inputsUnchanged;inputCount=$taskRecord.inputCount;
    suites=@($taskRecord.suites).Count;tests=($taskRecord.suites | Measure-Object tests -Sum).Sum;
    failures=($taskRecord.suites | Measure-Object failures -Sum).Sum;errors=($taskRecord.suites | Measure-Object errors -Sum).Sum;
    skipped=($taskRecord.suites | Measure-Object skipped -Sum).Sum}
$taskSummary | ConvertTo-Json | Set-Content -LiteralPath $OutputFile -Encoding utf8
$taskSummary | ConvertTo-Json -Compress
