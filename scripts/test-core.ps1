$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/core-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/core-tests" "$projectRoot/app/src/main/java/app/fastnotes/Note.java" "$projectRoot/app/src/main/java/app/fastnotes/Retrieval.java" "$projectRoot/app/src/main/java/app/fastnotes/GgufHeader.java" "$projectRoot/app/src/main/java/app/fastnotes/SearchQuery.java" "$projectRoot/tests/CoreTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Core test compilation failed' }
& "$javaRoot/bin/java.exe" -cp "$projectRoot/.tools/core-tests" app.fastnotes.CoreTest
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
