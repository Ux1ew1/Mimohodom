$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/markup-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/Markup.java",
  "$projectRoot/tests/MarkupTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/markup-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Markup test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/markup-tests" app.fastnotes.MarkupTest
if ($LASTEXITCODE -ne 0) { throw 'Markup tests failed' }
