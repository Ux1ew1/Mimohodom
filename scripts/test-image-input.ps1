$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/image-input-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/image-input-tests" "$projectRoot/app/src/main/java/app/fastnotes/ShareParser.java" "$projectRoot/app/src/main/java/app/fastnotes/CropMath.java" "$projectRoot/tests/ImageInputTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Image input test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/image-input-tests" app.fastnotes.ImageInputTest
if ($LASTEXITCODE -ne 0) { throw 'Image input tests failed' }
