$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/checkin-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/checkin-tests" "$projectRoot/app/src/main/java/app/fastnotes/CheckIn.java" "$projectRoot/tests/CheckInTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Check-in test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/checkin-tests" app.fastnotes.CheckInTest
if ($LASTEXITCODE -ne 0) { throw 'Check-in tests failed' }
