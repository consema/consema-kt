# Compile the Kotlin main sources (L4 verification scaffolding).
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Users\franck\tools\jdk17\jdk-17.0.20+8"
$kotlinc = "C:\Users\franck\kotlinc\kotlinc"
$root = "C:\Users\franck\Documents\consema\kotlin"
$src = Join-Path $root "src\main\kotlin"
$out = Join-Path $root "build\work\l4_main"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
$sources = Get-ChildItem $src -Recurse -Filter *.kt | ForEach-Object { $_.FullName }
$list = Join-Path $root "build\work\l4_main_sources.txt"
$sources | Set-Content $list -Encoding ascii
$ErrorActionPreference = "Continue"
$output = & "$env:JAVA_HOME\bin\java.exe" -cp "$kotlinc\lib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect `
    -classpath "$kotlinc\lib\kotlin-stdlib.jar" `
    -d $out "@$list" 2>&1
$code = $LASTEXITCODE
$output | Set-Content (Join-Path $root "build\work\l4_main.err") -Encoding utf8
Write-Host "compile exit: $code"
$output | Select-Object -First 80 | ForEach-Object { Write-Host $_ }
exit $code
