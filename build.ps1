param([string]$Maven = 'mvn', [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome; $env:Path = "$JavaHome\bin;$env:Path" }
& $Maven -B clean verify
if ($LASTEXITCODE -ne 0) { throw "Maven build failed ($LASTEXITCODE)" }
