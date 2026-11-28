$evoJarPath = Join-Path $PSScriptRoot 'cli/target/evo.jar'
if (-not (Test-Path -LiteralPath $evoJarPath -PathType Leaf)) {
    [Console]::Error.WriteLine('[EVO_NOT_BUILT] Build the CLI as documented in cli/README.md.')
    exit 1
}
if ($env:EVO_NO_STALE_WARN -ne '1' -and ($args -notcontains '--json')) {
    $evoJarTime = (Get-Item -LiteralPath $evoJarPath).LastWriteTimeUtc
    $evoSrcDirs = Get-ChildItem -LiteralPath $PSScriptRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -in @('analyzer', 'analyzer-execution', 'analyzer-filesystem', 'analyzer-javaparser', 'analyzer-maven', 'analyzer-treesitter', 'backend', 'cli') } |
        ForEach-Object { Join-Path $_.FullName 'src/main' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Container }
    $evoStaleFile = if ($evoSrcDirs) {
        Get-ChildItem -Path $evoSrcDirs -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTimeUtc -gt $evoJarTime } |
            Select-Object -First 1
    }
    if ($null -ne $evoStaleFile) {
        [Console]::Error.WriteLine("[EVO_STALE_JAR] Warning: $($evoStaleFile.Name) is newer than cli/target/evo.jar. Rebuild with: .\mvnw.cmd -o -B -ntp -pl .,analyzer,analyzer-filesystem,analyzer-javaparser,analyzer-maven,analyzer-treesitter,analyzer-execution,backend,cli '-DskipTests' '-Dmaven.compiler.useIncrementalCompilation=false' package")
    }
}
$evoJavaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$evoPreviousEncoding = [Console]::OutputEncoding
$evoExitCode = 1
try {
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    & $evoJavaPath '-Dfile.encoding=UTF-8' '-jar' $evoJarPath @args
    $evoExitCode = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $evoPreviousEncoding
}
exit $evoExitCode
