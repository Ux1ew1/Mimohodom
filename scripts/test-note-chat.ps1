$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
if (-not (Test-Path $jsonJar)) { throw 'Test dependency missing: org.json:json:20240303 (.tools/json.jar)' }
New-Item -ItemType Directory -Force "$projectRoot/.tools/note-chat-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/PlanTree.java",
  "$projectRoot/app/src/main/java/app/fastnotes/PlanChat.java",
  "$projectRoot/app/src/main/java/app/fastnotes/NoteEdit.java",
  "$projectRoot/app/src/main/java/app/fastnotes/ChatOrigin.java",
  "$projectRoot/app/src/main/java/app/fastnotes/ReminderPolicy.java",
  "$projectRoot/app/src/main/java/app/fastnotes/ReminderBatch.java",
  "$projectRoot/tests/NoteChatTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d "$projectRoot/.tools/note-chat-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Note chat test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/note-chat-tests;$jsonJar" app.fastnotes.NoteChatTest
if ($LASTEXITCODE -ne 0) { throw 'Note chat tests failed' }
