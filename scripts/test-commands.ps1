$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
$out = "$projectRoot/.tools/command-tests"
New-Item -ItemType Directory -Force $out | Out-Null
$src = "$projectRoot/app/src/main/java/app/fastnotes"
$sources = @(
  "$src/AgentAction.java", "$src/AgentPlan.java", "$src/JsonRepair.java",
  "$src/AgentJournal.java", "$src/CommandAnalysis.java", "$src/CommandDraft.java",
  "$src/FolderPolicy.java", "$src/Metadata.java", "$src/Retrieval.java",
  "$src/Note.java", "$src/PlanTree.java", "$src/ReminderPolicy.java",
  "$projectRoot/tests/CommandAnalysisTest.java", "$projectRoot/tests/CommandExecutorTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'Command test compilation failed' }
foreach ($test in 'CommandAnalysisTest', 'CommandExecutorTest') {
  & "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$out;$jsonJar" "app.fastnotes.$test"
  if ($LASTEXITCODE -ne 0) { throw "$test failed" }
}
Push-Location $projectRoot
try {
  & node tests/commands-sql.cjs
  if ($LASTEXITCODE -ne 0) { throw 'Command SQL tests failed' }
}
finally { Pop-Location }
