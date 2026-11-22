$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
if (-not (Test-Path $jsonJar)) { throw 'Test dependency missing: org.json:json:20240303 (.tools/json.jar)' }
New-Item -ItemType Directory -Force "$projectRoot/.tools/import-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/NotesImport.java",
  "$projectRoot/app/src/main/java/app/fastnotes/Note.java",
  "$projectRoot/app/src/main/java/app/fastnotes/FolderPolicy.java",
  "$projectRoot/app/src/main/java/app/fastnotes/ReminderPolicy.java",
  "$projectRoot/app/src/main/java/app/fastnotes/PlanTree.java",
  "$projectRoot/app/src/main/java/app/fastnotes/Metadata.java",
  "$projectRoot/app/src/main/java/app/fastnotes/Retrieval.java",
  "$projectRoot/tests/NotesImportTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d "$projectRoot/.tools/import-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Import test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/import-tests;$jsonJar" app.fastnotes.NotesImportTest
if ($LASTEXITCODE -ne 0) { throw 'Import tests failed' }
