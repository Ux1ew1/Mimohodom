$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
New-Item -ItemType Directory -Force "$projectRoot/.tools/day-digest-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/DayDigest.java",
  "$projectRoot/app/src/main/java/app/fastnotes/AgentAction.java",
  "$projectRoot/app/src/main/java/app/fastnotes/AgentPlan.java",
  "$projectRoot/app/src/main/java/app/fastnotes/JsonRepair.java",
  "$projectRoot/app/src/main/java/app/fastnotes/Note.java",
  "$projectRoot/app/src/main/java/app/fastnotes/PlanTree.java",
  "$projectRoot/app/src/main/java/app/fastnotes/ReminderPolicy.java",
  "$projectRoot/tests/DayDigestTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d "$projectRoot/.tools/day-digest-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Day digest test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/day-digest-tests;$jsonJar" app.fastnotes.DayDigestTest
if ($LASTEXITCODE -ne 0) { throw 'Day digest tests failed' }
