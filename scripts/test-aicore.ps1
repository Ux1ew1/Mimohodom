$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
if (-not (Test-Path $jsonJar)) { throw 'Test dependency missing: org.json:json:20240303 (.tools/json.jar)' }
New-Item -ItemType Directory -Force "$projectRoot/.tools/aicore-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d "$projectRoot/.tools/aicore-tests" "$projectRoot/app/src/main/java/app/fastnotes/AiCorePrompt.java" "$projectRoot/tests/AiCorePromptTest.java"
if ($LASTEXITCODE -ne 0) { throw 'AICore test compilation failed' }
& "$javaRoot/bin/java.exe" -cp "$projectRoot/.tools/aicore-tests;$jsonJar" app.fastnotes.AiCorePromptTest
if ($LASTEXITCODE -ne 0) { throw 'AICore tests failed' }
