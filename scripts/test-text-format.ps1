$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/text-format-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/TextFormat.java",
  "$projectRoot/tests/TextFormatTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/text-format-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Text format test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/text-format-tests" app.fastnotes.TextFormatTest
if ($LASTEXITCODE -ne 0) { throw 'Text format tests failed' }
