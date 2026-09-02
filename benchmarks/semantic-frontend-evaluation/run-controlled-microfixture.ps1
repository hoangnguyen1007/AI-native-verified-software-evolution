[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
& (Join-Path $root '..\..\mvnw.cmd') -q -f (Join-Path $root 'pom.xml') test exec:java "-Dexec.args=$root"
if ($LASTEXITCODE -ne 0) { throw "evaluation exited with $LASTEXITCODE" }
