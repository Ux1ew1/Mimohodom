$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
New-Item -ItemType Directory -Force "$projectRoot/.tools/manager-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d "$projectRoot/.tools/manager-tests" "$projectRoot/app/src/main/java/app/fastnotes/Note.java" "$projectRoot/app/src/main/java/app/fastnotes/ManagerAnalysis.java" "$projectRoot/tests/ManagerTest.java" "$projectRoot/app/src/main/java/app/fastnotes/ReviewAnalysis.java" "$projectRoot/app/src/main/java/app/fastnotes/Analysis.java" "$projectRoot/app/src/main/java/app/fastnotes/Metadata.java" "$projectRoot/app/src/main/java/app/fastnotes/Retrieval.java" "$projectRoot/tests/ReviewTest.java" "$projectRoot/app/src/main/java/app/fastnotes/ReviewPolicy.java" "$projectRoot/app/src/main/java/app/fastnotes/SummaryAnalysis.java" "$projectRoot/app/src/main/java/app/fastnotes/TopicSelection.java" "$projectRoot/tests/SummaryTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Manager test compilation failed' }
& "$javaRoot/bin/java.exe" -cp "$projectRoot/.tools/manager-tests;$jsonJar" app.fastnotes.ManagerTest
if ($LASTEXITCODE -ne 0) { throw 'Manager tests failed' }

& "$javaRoot/bin/java.exe" -cp "$projectRoot/.tools/manager-tests;$jsonJar" app.fastnotes.ReviewTest
if ($LASTEXITCODE -ne 0) { throw 'Unified review tests failed' }

& "$javaRoot/bin/java.exe" -cp "$projectRoot/.tools/manager-tests;$jsonJar" app.fastnotes.SummaryTest
if ($LASTEXITCODE -ne 0) { throw "Summary tests failed" }
