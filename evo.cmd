@echo off
setlocal
set "EVO_JAR=%~dp0cli\target\evo.jar"
if not exist "%EVO_JAR%" (
  echo [EVO_NOT_BUILT] Build the CLI as documented in cli/README.md. 1>&2
  exit /b 1
)
rem Match EvoMain's UTF-8 streams, then restore the caller's console code page.
for /f "tokens=2 delims=:" %%C in ('chcp') do set "EVO_CODEPAGE=%%C"
chcp 65001 >nul 2>&1
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -Xmx4g -Dfile.encoding=UTF-8 -jar "%EVO_JAR%" %*
) else (
  java -Xmx4g -Dfile.encoding=UTF-8 -jar "%EVO_JAR%" %*
)
set "EVO_EXIT=%errorlevel%"
if defined EVO_CODEPAGE chcp %EVO_CODEPAGE% >nul 2>&1
exit /b %EVO_EXIT%
