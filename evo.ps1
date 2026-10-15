$evoJarPath = Join-Path $PSScriptRoot 'cli/target/evo.jar'
if (-not (Test-Path -LiteralPath $evoJarPath -PathType Leaf)) {
    [Console]::Error.WriteLine('[EVO_NOT_BUILT] Build the CLI as documented in cli/README.md.')
    exit 1
}
$evoJavaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$evoPreviousEncoding = [Console]::OutputEncoding
$evoExitCode = 1
try {
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    & $evoJavaPath '-Xmx4g' '-Dfile.encoding=UTF-8' '-jar' $evoJarPath @args
    $evoExitCode = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $evoPreviousEncoding
}
exit $evoExitCode
