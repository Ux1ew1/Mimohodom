$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/chat-feed-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/ChatFeed.java",
  "$projectRoot/app/src/main/java/app/fastnotes/ChatOrigin.java",
  "$projectRoot/app/src/main/java/app/fastnotes/ChatMemo.java",
  "$projectRoot/tests/ChatFeedTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/chat-feed-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Chat feed test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/chat-feed-tests" app.fastnotes.ChatFeedTest
if ($LASTEXITCODE -ne 0) { throw 'Chat feed tests failed' }
