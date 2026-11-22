$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
New-Item -ItemType Directory -Force "$projectRoot/.tools/agent-action-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/AgentAction.java",
  "$projectRoot/app/src/main/java/app/fastnotes/AgentPlan.java",
  "$projectRoot/app/src/main/java/app/fastnotes/JsonRepair.java",
  "$projectRoot/app/src/main/java/app/fastnotes/AgentJournal.java",
  "$projectRoot/app/src/main/java/app/fastnotes/DeckPolicy.java",
  "$projectRoot/app/src/main/java/app/fastnotes/FolderPolicy.java",
  "$projectRoot/app/src/main/java/app/fastnotes/Note.java",
  "$projectRoot/app/src/main/java/app/fastnotes/PlanTree.java",
  "$projectRoot/app/src/main/java/app/fastnotes/ReminderPolicy.java",
  "$projectRoot/tests/AgentActionTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d "$projectRoot/.tools/agent-action-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Agent action test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/agent-action-tests;$jsonJar" app.fastnotes.AgentActionTest
if ($LASTEXITCODE -ne 0) { throw 'Agent action tests failed' }
