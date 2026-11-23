$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$out = "$projectRoot/.tools/semantic-tests"
New-Item -ItemType Directory -Force $out | Out-Null
$src = "$projectRoot/app/src/main/java/app/fastnotes"
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d $out "$src/Note.java" "$src/Retrieval.java" "$src/SemanticEmbedding.java" "$src/DuplicateFinder.java" "$projectRoot/tests/SemanticEmbeddingTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Semantic test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp $out app.fastnotes.SemanticEmbeddingTest
if ($LASTEXITCODE -ne 0) { throw 'Semantic tests failed' }
Push-Location $projectRoot
try {
  & node tests/semantic-sql.cjs
  if ($LASTEXITCODE -ne 0) { throw 'Semantic SQL tests failed' }
}
finally { Pop-Location }
