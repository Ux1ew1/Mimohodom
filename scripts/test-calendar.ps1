$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/calendar-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/CalendarLink.java",
  "$projectRoot/app/src/main/java/app/fastnotes/Note.java",
  "$projectRoot/app/src/main/java/app/fastnotes/ReminderPolicy.java",
  "$projectRoot/tests/CalendarLinkTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/calendar-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Calendar test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/calendar-tests" app.fastnotes.CalendarLinkTest
if ($LASTEXITCODE -ne 0) { throw 'Calendar tests failed' }
