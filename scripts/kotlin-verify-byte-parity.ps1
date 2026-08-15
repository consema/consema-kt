param(
    [string]$CaseFile = '',
    [string]$OutDir = '',
    # consema-rs checkout directory (multi-repo mode); default: <repo
    # root>\consema-rs (CI layout) or a sibling consema-rs checkout (G109)
    [string]$RustWorkspace = ''
)

# ---------------------------------------------------------------------------
# Cross-language PVCE/PGCE byte-parity verification — Kotlin side
# (L5; https://github.com/consema/consema/blob/main/docs/five-language-ci-design.md §3.2; the Go
# precedent https://github.com/consema/consema-go/blob/main/scripts/go-verify-byte-parity.ps1).
#
# Pipeline (Kotlin never imports or calls Rust, RFC 0016 §1.1):
#   1. builds the minimal Rust encoder example
#      (consema-conformance/examples/emit_parity_bytes.rs);
#   2. runs it over the provisioned case set
#      (conformance/differential/cases.json, the shared single-authority
#      case directory of the consema repository) into <OutDir> as one
#      `<case-id>.hex` file per case;
#   3. compiles the Kotlin main + differential tests with the direct JVM
#      K2JVMCompiler and runs them through the temp main() test runner with
#      CONSEMA_DIFFERENTIAL_RUST_DIR set: the Kotlin codecs encode the same
#      input set, and the bytes are compared byte for byte with the Rust
#      golden files, plus the bidirectional direction (Rust bytes -> Kotlin
#      decode -> Kotlin re-encode).
#
# Requirements: cargo (or $env:CONSEMA_CARGO), a JDK 17 (or
# $env:CONSEMA_JAVA_HOME), and a Kotlin compiler distribution (or
# $env:CONSEMA_KOTLINC); the Rust workspace is the consema-rs checkout
# (<repo root>\consema-rs by default, -RustWorkspace overrides). Windows
# PowerShell 5.1 compatible, no third-party dependencies. Windows-only:
# the toolchain/emitter paths are the Windows-native layout (bin\java.exe,
# lib\kotlin-compiler.jar, debug\examples\emit_parity_bytes.exe) — the
# scripts must run under PowerShell on Windows (CI: windows-latest); on
# POSIX hosts the direct-compile paths of ci-kotlin.yml apply.
#
# NOTE: CONSEMA_JAVA_HOME defaults to $env:JAVA_HOME when unset — no
# machine-coupled path is baked in. CONSEMA_KOTLINC has no generic
# default, so every environment must set it (and CONSEMA_JAVA_HOME when
# JAVA_HOME is unset). A missing toolchain fails with a clear message —
# the script never silently falls back to a wrong toolchain.
# ---------------------------------------------------------------------------

$ErrorActionPreference = 'Stop'
# Per-invocation unique directory suffix (G44, 2026-08-14): a fixed shared
# capture/evidence/output/workDir path would let two concurrent runs
# truncate or interleave each other's files and flip the SKIPPED/PASSED
# verdicts; every default TEMP/target path below carries this nonce.
$nonce = [Guid]::NewGuid().ToString('N')
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$kotlinDir = Join-Path $workspaceRoot 'kotlin'
# The Rust emitter workspace lives in the consema-rs repository checkout
# (multi-repo mode): this repository carries the Kotlin implementation only.
# Default resolution (G109, adversarial audit 2026-08-13 — the old default
# only matched the CI nested layout): <repo root>\consema-rs (CI) first,
# then a sibling consema-rs checkout; -RustWorkspace overrides either.
if (-not $RustWorkspace) {
    $nested = Join-Path $workspaceRoot 'consema-rs'
    $sibling = Join-Path (Split-Path -Parent $workspaceRoot) 'consema-rs'
    if (Test-Path (Join-Path $nested 'Cargo.toml')) {
        $RustWorkspace = $nested
    }
    elseif (Test-Path (Join-Path $sibling 'Cargo.toml')) {
        $RustWorkspace = $sibling
    }
    else {
        Write-Error "consema-rs checkout not found: tried $nested (CI multi-repo mode) and $sibling (side-by-side layout); pass -RustWorkspace explicitly"
        exit 1
    }
}
$RustWorkspace = [IO.Path]::GetFullPath($RustWorkspace)

# --- repo layout sanity ------------------------------------------------------
if (-not (Test-Path (Join-Path $RustWorkspace 'Cargo.toml')) -or
    -not (Test-Path (Join-Path $RustWorkspace 'consema-conformance\Cargo.toml'))) {
    Write-Error "consema-rs workspace not found: $RustWorkspace (checkout consema/consema-rs beside this repository, or pass -RustWorkspace)"
    exit 1
}
if (-not (Test-Path (Join-Path $kotlinDir 'src\main\kotlin\consema\differential'))) {
    Write-Error "Kotlin differential sources not found: $kotlinDir"
    exit 1
}
$javaHome = if ($env:CONSEMA_JAVA_HOME) { $env:CONSEMA_JAVA_HOME } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { '' }
if (-not $javaHome) {
    Write-Error 'JDK 17 not found: set CONSEMA_JAVA_HOME to a JDK 17 installation (or set JAVA_HOME), e.g. $env:CONSEMA_JAVA_HOME = "C:\path\to\jdk-17"'
    exit 1
}
$java = Join-Path $javaHome 'bin\java.exe'
if (-not (Test-Path $java)) {
    Write-Error "JDK 17 not found at '$java' (set CONSEMA_JAVA_HOME to a valid JDK 17 path)"
    exit 1
}
$kotlinc = if ($env:CONSEMA_KOTLINC) { $env:CONSEMA_KOTLINC } else { '' }
if (-not $kotlinc) {
    Write-Error 'Kotlin compiler distribution not found: set CONSEMA_KOTLINC to a kotlinc distribution root, e.g. $env:CONSEMA_KOTLINC = "C:\path\to\kotlinc"'
    exit 1
}
if (-not (Test-Path (Join-Path $kotlinc 'lib\kotlin-compiler.jar'))) {
    Write-Error "Kotlin compiler distribution not found at '$kotlinc' (set CONSEMA_KOTLINC to a valid kotlinc root)"
    exit 1
}
$kotlinTestJar = Join-Path $kotlinc 'lib\kotlin-test.jar'
$kotlinTestJunit5Jar = Join-Path $kotlinc 'lib\kotlin-test-junit5.jar'
if (-not (Test-Path $kotlinTestJar) -or -not (Test-Path $kotlinTestJunit5Jar)) {
    Write-Error "kotlin-test jars not found in '$kotlinc\lib'"
    exit 1
}

# --- case set ----------------------------------------------------------------
if ($CaseFile -eq '') {
    $CaseFile = Join-Path $workspaceRoot 'conformance\differential\cases.json'
}
if (-not (Test-Path $CaseFile)) {
    Write-Error "differential case file not found: $CaseFile"
    exit 1
}
# UTF8 explicit: PowerShell 5.1 Get-Content defaults to the ANSI codepage.
$cases = Get-Content $CaseFile -Raw -Encoding UTF8 | ConvertFrom-Json
$caseCount = @($cases.cases).Count
if ($caseCount -ne 68) {
    Write-Error "differential case file has $caseCount cases, want exactly 68 (the frozen ByteParityTest.caseFileIntegrity count)"
    exit 1
}

# --- Rust side ---------------------------------------------------------------
$cargo = if ($env:CONSEMA_CARGO) { $env:CONSEMA_CARGO } else { 'cargo' }
if (-not (Get-Command $cargo -ErrorAction SilentlyContinue)) {
    Write-Error "cargo is not available ('$cargo')"
    exit 1
}
Write-Host "[1/3] building the Rust encoder example (emit_parity_bytes)..."
# Windows PowerShell 5.1 routes native stderr through the error stream under
# $ErrorActionPreference='Stop'; relax around cargo (its progress lines are
# stderr) and judge success by $LASTEXITCODE only.
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
Push-Location $RustWorkspace
try {
    & $cargo build --locked -p consema-conformance --example emit_parity_bytes
    $buildCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
$ErrorActionPreference = $previousEap
if ($buildCode -ne 0) { exit $buildCode }

$targetDir = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $RustWorkspace 'target' }
$example = Join-Path $targetDir 'debug\examples\emit_parity_bytes.exe'
if (-not (Test-Path $example)) {
    Write-Error "Rust example binary not found: $example"
    exit 1
}
if ($OutDir -eq '') {
    $OutDir = Join-Path $targetDir "kotlin-differential-parity-$nonce"
}
# The env var is consumed by the Kotlin test, so it must be absolute.
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force $OutDir | Out-Null

Write-Host "[2/3] running the Rust encoder over $caseCount cases -> $OutDir"
& $example $CaseFile $OutDir
if ($LASTEXITCODE -ne 0) {
    Write-Error "emit_parity_bytes failed (exit $LASTEXITCODE)"
    exit $LASTEXITCODE
}

# --- Kotlin side -------------------------------------------------------------
$workDir = Join-Path $targetDir "kotlin-verify-parity-$nonce"
$mainOut = Join-Path $workDir 'main'
$runnerOut = Join-Path $workDir 'runner'
if (Test-Path $workDir) { Remove-Item $workDir -Recurse -Force }
New-Item -ItemType Directory -Force $mainOut | Out-Null
New-Item -ItemType Directory -Force $runnerOut | Out-Null

# The junit-jupiter-api jar is required at test compile time (kotlin.test
# resolves @Test through the kotlin-test-junit5 typealias to
# org.junit.jupiter.api.Test). Reuse the provisioned copy, else fetch it.
$junitJar = Join-Path $kotlinDir 'build\verify\lib\junit-jupiter-api-5.10.2.jar'
if (-not (Test-Path $junitJar)) {
    $junitDir = Join-Path $workDir 'lib'
    New-Item -ItemType Directory -Force $junitDir | Out-Null
    $junitJar = Join-Path $junitDir 'junit-jupiter-api-5.10.2.jar'
    if (-not (Test-Path $junitJar)) {
        Write-Host "downloading junit-jupiter-api-5.10.2.jar (needed by kotlin-test-junit5)..."
        try {
            Invoke-WebRequest -UseBasicParsing `
                'https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter-api/5.10.2/junit-jupiter-api-5.10.2.jar' `
                -OutFile $junitJar
        }
        catch {
            Write-Error 'cannot fetch junit-jupiter-api-5.10.2.jar (set up kotlin/build/verify/lib or a network path)'
            exit 1
        }
    }
}
# Pinned upstream artifact (Maven Central, 5.10.2): verify the jar against
# the pinned sha256 on every use — a reused local copy (e.g. an existing
# kotlin/build/verify/lib jar) or a poisoned one fails the script instead of
# being silently used.
$expectedJunitSha256 = 'afff77c186cd317275803872fa5133aa801fd6ac40bd91c78a6cf8009b4b17cc'
$actualJunitSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $junitJar).Hash
if ($actualJunitSha256 -ne $expectedJunitSha256) {
    Write-Error "junit-jupiter-api-5.10.2.jar sha256 mismatch (got $actualJunitSha256, want $expectedJunitSha256)"
    exit 1
}

Write-Host "[3/3] compiling the Kotlin side and running the differential test..."
$env:CONSEMA_REPO = $workspaceRoot
$env:CONSEMA_DIFFERENTIAL_RUST_DIR = $OutDir
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'

# 1. main sources -> $mainOut
& $java -Xmx2g -cp "$kotlinc\lib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -jvm-target 17 -d $mainOut (Join-Path $kotlinDir 'src\main\kotlin')
$compileCode = $LASTEXITCODE
if ($compileCode -ne 0) {
    Write-Error "Kotlin main compile failed (exit $compileCode)"
    exit $compileCode
}

# 2. the temp main() test runner (the committed kotlin/verify/TestShim.kt
# shim was removed 2026-08-13 — its dead `package kotlin.test` annotation
# class would clash with kotlin-test.jar; the runner drives the @Test
# methods directly, so no JUnit platform is needed at runtime,
# kotlin-test.jar only). Wave-4 R52: the runner discovers the @Test
# methods reflectively (annotation qualified name) instead of a
# hand-maintained name list — a new @Test on ByteParityTest runs
# automatically and can never silently stay off the execution face.
$runnerSource = Join-Path $workDir 'TestRunner.kt'
@'
package differential

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var failures = 0
    var runs = 0
    fun run(name: String, block: () -> Unit) {
        runs++
        try {
            block()
            println("PASS $name")
        } catch (e: Throwable) {
            failures++
            println("FAIL $name: ${e.message}")
            e.printStackTrace()
        }
    }
    val tests = ByteParityTest::class.java.declaredMethods
        .filter { m -> m.annotations.any { it.annotationClass.qualifiedName == "org.junit.jupiter.api.Test" } }
        .sortedBy { it.name }
        .map { m -> "ByteParityTest.${m.name}" to { m.invoke(ByteParityTest()); Unit } }
        .toMap()
    for (arg in args) {
        val block = tests[arg] ?: error("unknown test $arg")
        run(arg, block)
    }
    if (args.isEmpty()) {
        for ((name, block) in tests) {
            run(name, block)
        }
    }
    println("tests: $runs run, $failures failed")
    if (runs == 0) {
        println("no tests ran - refusing to pass")
        exitProcess(1)
    }
    if (failures > 0) exitProcess(1)
}
'@ | Set-Content -Path $runnerSource -Encoding UTF8

$testClasspath = "$mainOut;$kotlinTestJar;$kotlinTestJunit5Jar;$junitJar"
& $java -Xmx2g -cp "$kotlinc\lib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -jvm-target 17 -classpath $testClasspath -d $runnerOut `
    (Join-Path $kotlinDir 'src\test\kotlin\differential') $runnerSource
$compileCode = $LASTEXITCODE
if ($compileCode -ne 0) {
    Write-Error "Kotlin test compile failed (exit $compileCode)"
    exit $compileCode
}

# 3. run (kotlin-test.jar at runtime; the junit-jupiter-api jar rides the
# runtime classpath because R52's reflective @Test discovery needs
# org.junit.jupiter.api.Test loadable — without it the JVM silently drops
# the annotation and zero tests are discovered, and the runner's
# "no tests ran" guard then fails the run loudly).
$stdoutFile = Join-Path $workDir 'test.stdout.txt'
$stderrFile = Join-Path $workDir 'test.stderr.txt'
$runtimeClasspath = "$mainOut;$runnerOut;$kotlinc\lib\kotlin-stdlib.jar;$kotlinTestJar;$junitJar"
Push-Location $workspaceRoot
try {
    # R52: no test-name arguments — the runner discovers every @Test method
    # of ByteParityTest itself.
    & $java -Xmx2g -cp $runtimeClasspath differential.TestRunnerKt `
        1> $stdoutFile 2> $stderrFile
    $testCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
$ErrorActionPreference = $previousEap
Get-Content $stdoutFile | ForEach-Object { Write-Host $_ }
if (Test-Path $stderrFile) {
    Get-Content $stderrFile | ForEach-Object { Write-Host $_ }
}

# The parity test must have RUN (not skipped) and passed. Wave-5 G45:
# the skip attribution is derived from the actual marker line — the
# whole-suite stdout can carry a marker from another method of the same
# class (a future legit skip must not misattribute a passing run), so the
# guard only fires when the parity summary is absent.
$output = Get-Content $stdoutFile -Raw
$summary = [regex]::Match($output, 'byte parity: \d+/\d+ equal \(\d+ pvce, \d+ pgce\)')
if (-not $summary.Success) {
    $skip = [regex]::Match($output, '(?m)^\[SKIP\][^\r\n]*CONSEMA_DIFFERENTIAL_RUST_DIR is not set[^\r\n]*')
    if ($skip.Success) {
        Write-Error "the differential test skipped: $($skip.Value.Trim())"
    } else {
        Write-Error "the differential test did not run and printed no documented skip marker (exit $testCode)"
    }
    if ($testCode -eq 0) { exit 1 } else { exit $testCode }
}
if ($testCode -ne 0) {
    exit $testCode
}

Write-Host "RESULT: $($summary.Value)"
Write-Host "byte parity verification complete (exit 0)"
exit 0
