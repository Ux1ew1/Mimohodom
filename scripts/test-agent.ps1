$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/agent-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/agent-tests" "$projectRoot/app/src/agent/java/app/fastnotes/AgentCommand.java" "$projectRoot/tests/AgentCommandTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Agent command test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/agent-tests" app.fastnotes.AgentCommandTest
if ($LASTEXITCODE -ne 0) { throw 'Agent command tests failed' }
# Клиент: правила кавычек должны совпадать с Java-разбором
node "$projectRoot/tests/agent-client.cjs"
if ($LASTEXITCODE -ne 0) { throw 'Agent client tests failed' }
