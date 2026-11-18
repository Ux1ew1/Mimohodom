$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$json = "$projectRoot/.tools/json.jar"
if (-not (Test-Path $json)) { throw 'Missing .tools/json.jar' }
$out = "$projectRoot/.tools/folder-tests"
New-Item -ItemType Directory -Force $out | Out-Null
$src = "$projectRoot/app/src/main/java/app/fastnotes"
$files = @(
  "$src/FolderPolicy.java", "$src/FolderSuggestion.java", "$src/Metadata.java",
  "$src/Retrieval.java", "$src/Note.java",
  "$projectRoot/tests/FolderTest.java", "$projectRoot/tests/FolderSuggestionTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $json -d $out @files
if ($LASTEXITCODE -ne 0) { throw 'Folder test compilation failed' }
foreach ($test in 'FolderTest', 'FolderSuggestionTest') {
  & "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$out;$json" "app.fastnotes.$test"
  if ($LASTEXITCODE -ne 0) { throw "$test failed" }
}
& node "$projectRoot/tests/folders-sql.cjs"
if ($LASTEXITCODE -ne 0) { throw 'Folder SQL tests failed' }
