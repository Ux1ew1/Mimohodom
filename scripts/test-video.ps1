$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$json = "$projectRoot/.tools/json.jar"
if (-not (Test-Path $json)) { throw 'Missing .tools/json.jar' }
$out = "$projectRoot/.tools/video-tests"
New-Item -ItemType Directory -Force $out | Out-Null
$src = "$projectRoot/app/src/main/java/app/fastnotes"
$files = @(
  "$src/YoutubeLink.java", "$src/VideoAnalysis.java", "$src/VideoMerge.java",
  "$src/TopicSelection.java", "$src/ManagerAnalysis.java", "$src/Metadata.java",
  "$src/Retrieval.java", "$src/Note.java",
  "$projectRoot/tests/VideoTest.java", "$projectRoot/tests/VideoMergeTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $json -d $out @files
if ($LASTEXITCODE -ne 0) { throw 'Video test compilation failed' }
foreach ($test in 'VideoTest', 'VideoMergeTest') {
  & "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$out;$json" "app.fastnotes.$test"
  if ($LASTEXITCODE -ne 0) { throw "$test failed" }
}
