$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/attention-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/AttentionPolicy.java",
  "$projectRoot/tests/AttentionPolicyTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/attention-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Attention test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/attention-tests" app.fastnotes.AttentionPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Attention tests failed' }
