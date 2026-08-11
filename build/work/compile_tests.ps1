# Compile main + test sources in ONE module (internal visibility) and run
# the tests through a reflective runner (L4 verification scaffolding).
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Users\franck\tools\jdk17\jdk-17.0.20+8"
$kotlinc = "C:\Users\franck\kotlinc\kotlinc"
$root = "C:\Users\franck\Documents\consema\kotlin"
$moduleOut = Join-Path $root "build\work\l4_module"
$runnerOut = Join-Path $root "build\work\l4_runner"
if (Test-Path $moduleOut) { Remove-Item -Recurse -Force $moduleOut }
if (Test-Path $runnerOut) { Remove-Item -Recurse -Force $runnerOut }
New-Item -ItemType Directory -Force $moduleOut | Out-Null
New-Item -ItemType Directory -Force $runnerOut | Out-Null

$lib = Join-Path $root "build\verify\lib"
$testCp = "$lib\kotlin-test-2.2.0.jar"

# Compile the kotlin.test.Test shim into its own classes dir (the plain
# kotlin-test jar carries no Test annotation; the shim needs the
# -Xallow-kotlin-package flag because only the stdlib may declare the
# kotlin package).
$shimOut = Join-Path $root "build\work\l4_shim"
if (Test-Path $shimOut) { Remove-Item -Recurse -Force $shimOut }
New-Item -ItemType Directory -Force $shimOut | Out-Null
$shim = Join-Path $root "build\verify\lib\TestShim.kt"
$output = & "$env:JAVA_HOME\bin\java.exe" -cp "$kotlinc\lib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -Xallow-kotlin-package `
    -classpath "$kotlinc\lib\kotlin-stdlib.jar" `
    -d $shimOut $shim 2>&1
$code = $LASTEXITCODE
if ($code -ne 0) {
    $output | Select-Object -First 40 | ForEach-Object { Write-Host $_ }
    exit $code
}
$testCp = "$lib\kotlin-test-2.2.0.jar;$shimOut"

$ErrorActionPreference = "Continue"
# The kotlin.test.Test annotation lives in the JUnit5 artifact (a typealias
# to org.junit.jupiter.api.Test); the plain kotlin-test jar carries only the
# assertions. The established shim pattern (compiled above with
# -Xallow-kotlin-package) supplies a module-local `kotlin.test.Test`
# annotation so the tests and the reflective runner agree on one annotation
# class; the runtime needs only the plain kotlin-test jar.
$allSources = @(Get-ChildItem (Join-Path $root "src\main\kotlin") -Recurse -Filter *.kt |
    ForEach-Object { $_.FullName }) + @(Get-ChildItem (Join-Path $root "src\test\kotlin") -Recurse -Filter *.kt |
    ForEach-Object { $_.FullName })
$list = Join-Path $root "build\work\l4_all_sources.txt"
$allSources | Set-Content $list -Encoding ascii
$output = & "$env:JAVA_HOME\bin\java.exe" -cp "$kotlinc\lib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect `
    -classpath "$kotlinc\lib\kotlin-stdlib.jar;$testCp" `
    -d $moduleOut "@$list" 2>&1
$code = $LASTEXITCODE
if ($code -ne 0) {
    $output | Set-Content (Join-Path $root "build\work\l4_module.err") -Encoding utf8
    $output | Select-Object -First 100 | ForEach-Object { Write-Host $_ }
    exit $code
}

# Compile the reflective test runner (needs kotlin-test on the classpath).
# Enumerate the COMPILED test classes (a test file may declare several
# top-level classes, e.g. FacadeTest.kt -> RegistryFacadeTest/
# DocumentFacadeTest/ConvertFacadeTest); the runner skips classes without
# @Test methods.
$runnerSrc = Join-Path $root "build\work\TestRunner.kt"
$classes = Get-ChildItem $moduleOut -Recurse -Filter *.class |
    Where-Object {
        $_.Name -notmatch "\$" -and $_.Name -notmatch "Kt\.class$" -and
        $_.Name -ne "TestShim.class" -and $_.Name -match "Test"
    } |
    ForEach-Object {
        $relative = $_.FullName.Substring($moduleOut.Length + 1)
        ($relative -replace "\\", "." -replace "\.class$", "")
    } | Sort-Object -Unique
$classLines = ($classes | ForEach-Object { "        `"$_`"," }) -join "`n"
$runnerBody = @"
import java.lang.reflect.Method
fun main() {
    val classes = listOf(
$classLines
    )
    var passed = 0
    var failed = 0
    var skipped = 0
    val failures = mutableListOf<String>()
    for (name in classes) {
        val clazz = try {
            Class.forName(name)
        } catch (e: Throwable) {
            skipped++
            println("SKIP `$name (class not loadable: `$e)")
            continue
        }
        val methods: List<Method> = clazz.methods.filter { it.isAnnotationPresent(kotlin.test.Test::class.java) }
        if (methods.isEmpty()) {
            skipped++
            println("SKIP `$name (no test methods)")
            continue
        }
        val instance = try {
            clazz.getDeclaredConstructor().newInstance()
        } catch (e: Throwable) {
            skipped++
            println("SKIP `$name (no default constructor: `$e)")
            continue
        }
        for (method in methods) {
            try {
                method.invoke(instance)
                passed++
                println("PASS `$name.`${method.name}")
            } catch (t: Throwable) {
                failed++
                val cause = t.cause ?: t
                println("FAIL `$name.`${method.name}: `$cause")
                failures.add("`$name.`${method.name}: `$cause")
            }
        }
    }
    println("=== `$passed passed, `$failed failed, `$skipped skipped-classes ===")
    if (failures.isNotEmpty()) {
        println("Failures:")
        failures.forEach { println("  `$it") }
    }
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
"@
$runnerBody | Set-Content $runnerSrc -Encoding ascii
$output = & "$env:JAVA_HOME\bin\java.exe" -cp "$kotlinc\lib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect `
    -classpath "$kotlinc\lib\kotlin-stdlib.jar;$testCp" `
    -d $runnerOut $runnerSrc 2>&1
$code = $LASTEXITCODE
if ($code -ne 0) {
    $output | Select-Object -First 80 | ForEach-Object { Write-Host $_ }
    exit $code
}

# Run the tests.
$runCp = "$moduleOut;$runnerOut;$shimOut;$lib\kotlin-test-2.2.0.jar;$kotlinc\lib\kotlin-stdlib.jar"
$output = & "$env:JAVA_HOME\bin\java.exe" -cp $runCp TestRunnerKt 2>&1
$code = $LASTEXITCODE
$output | Select-Object -Last 80 | ForEach-Object { Write-Host $_ }
Write-Host "test run exit: $code"
exit $code
