$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$json = "$projectRoot/.tools/json.jar"
if (-not (Test-Path $json)) { throw 'Missing .tools/json.jar' }
$out = "$projectRoot/.tools/video-tests"
New-Item -ItemType Directory -Force $out | Out-Null
$src = "$projectRoot/app/src/main/java/app/fastnotes"
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $json -d $out "$src/YoutubeLink.java" "$src/VideoAnalysis.java" "$src/Metadata.java" "$src/Retrieval.java" "$src/Note.java" "$projectRoot/tests/VideoTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Video test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$out;$json" app.fastnotes.VideoTest
if ($LASTEXITCODE -ne 0) { throw 'Video tests failed' }
