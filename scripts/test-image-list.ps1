$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/image-list-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/image-list-tests" "$projectRoot/app/src/main/java/app/fastnotes/ImageList.java" "$projectRoot/tests/ImageListTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Image list test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/image-list-tests" app.fastnotes.ImageListTest
if ($LASTEXITCODE -ne 0) { throw 'Image list tests failed' }
