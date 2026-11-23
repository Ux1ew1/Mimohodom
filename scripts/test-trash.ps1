$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/trash-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/TrashPolicy.java",
  "$projectRoot/tests/TrashPolicyTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/trash-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Trash test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/trash-tests" app.fastnotes.TrashPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Trash tests failed' }
