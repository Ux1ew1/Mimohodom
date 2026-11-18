$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/reminder-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/reminder-tests" "$projectRoot/app/src/main/java/app/fastnotes/ReminderPolicy.java" "$projectRoot/tests/ReminderPolicyTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Reminder test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/reminder-tests" app.fastnotes.ReminderPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Reminder tests failed' }
