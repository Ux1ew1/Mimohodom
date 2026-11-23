$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/deck-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/DeckGeometry.java",
  "$projectRoot/tests/DeckGeometryTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/deck-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Deck test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/deck-tests" app.fastnotes.DeckGeometryTest
if ($LASTEXITCODE -ne 0) { throw 'Deck tests failed' }
