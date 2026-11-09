$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
if (-not (Test-Path $jsonJar)) { throw 'Test dependency missing: org.json:json:20240303 (.tools/json.jar)' }
New-Item -ItemType Directory -Force "$projectRoot/.tools/polza-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp "$projectRoot/.tools/polza-tests;$jsonJar" -d "$projectRoot/.tools/polza-tests" "$projectRoot/app/src/main/java/app/fastnotes/Metadata.java" "$projectRoot/app/src/main/java/app/fastnotes/Note.java" "$projectRoot/app/src/main/java/app/fastnotes/Retrieval.java" "$projectRoot/app/src/main/java/app/fastnotes/Analysis.java" "$projectRoot/app/src/main/java/app/fastnotes/Vision.java" "$projectRoot/tests/VisionTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Vision test compilation failed' }
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d "$projectRoot/.tools/polza-tests" "$projectRoot/app/src/main/java/app/fastnotes/Metadata.java" "$projectRoot/app/src/main/java/app/fastnotes/Note.java" "$projectRoot/app/src/main/java/app/fastnotes/Retrieval.java" "$projectRoot/app/src/main/java/app/fastnotes/Polza.java" "$projectRoot/app/src/main/java/app/fastnotes/Analysis.java" "$projectRoot/tests/PolzaTest.java" "$projectRoot/tests/AnalysisTest.java" "$projectRoot/tests/MetadataTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Polza contract test compilation failed' }
& "$javaRoot/bin/java.exe" -cp "$projectRoot/.tools/polza-tests;$jsonJar" app.fastnotes.PolzaTest
if ($LASTEXITCODE -ne 0) { throw 'Polza contract tests failed' }
& "$javaRoot/bin/java.exe" -cp "$projectRoot/.tools/polza-tests;$jsonJar" app.fastnotes.AnalysisTest
if ($LASTEXITCODE -ne 0) { throw 'Background analysis tests failed' }
& "$javaRoot/bin/java.exe" -cp "$projectRoot/.tools/polza-tests;$jsonJar" app.fastnotes.VisionTest
if ($LASTEXITCODE -ne 0) { throw 'Image analysis tests failed' }
& "$javaRoot/bin/java.exe" -cp "$projectRoot/.tools/polza-tests;$jsonJar" app.fastnotes.MetadataTest
if ($LASTEXITCODE -ne 0) { throw 'Metadata tests failed' }
