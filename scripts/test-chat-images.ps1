$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/chat-images-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/ImageList.java",
  "$projectRoot/app/src/main/java/app/fastnotes/ChatImages.java",
  "$projectRoot/tests/ChatImagesTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/chat-images-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Chat images test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/chat-images-tests" app.fastnotes.ChatImagesTest
if ($LASTEXITCODE -ne 0) { throw 'Chat images tests failed' }
