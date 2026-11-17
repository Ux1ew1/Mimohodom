$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/prism-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/prism-tests" "$projectRoot/app/src/main/java/app/fastnotes/Prism.java" "$projectRoot/tests/PrismTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Prism test compilation failed' }
& "$javaRoot/bin/java.exe" -cp "$projectRoot/.tools/prism-tests" app.fastnotes.PrismTest
if ($LASTEXITCODE -ne 0) { throw 'Prism tests failed' }
