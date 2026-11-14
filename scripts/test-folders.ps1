$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
New-Item -ItemType Directory -Force "$projectRoot/.tools/folder-tests" | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -d "$projectRoot/.tools/folder-tests" "$projectRoot/app/src/main/java/app/fastnotes/FolderPolicy.java" "$projectRoot/tests/FolderTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Folder test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/folder-tests" app.fastnotes.FolderTest
if ($LASTEXITCODE -ne 0) { throw 'Folder tests failed' }
& node "$projectRoot/tests/folders-sql.cjs"
if ($LASTEXITCODE -ne 0) { throw 'Folder SQL tests failed' }
