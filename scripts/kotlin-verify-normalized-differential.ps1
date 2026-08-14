param(
    [string]$CaseFile = '',
    [string]$OutDir = '',
    # consema-rs checkout directory (multi-repo mode); default: <repo
    # root>\consema-rs (CI layout) or a sibling consema-rs checkout (G109)
    [string]$RustWorkspace = ''
)

# ---------------------------------------------------------------------------
# Cross-language normalized-result differential verification — Kotlin side
# (L5; https://github.com/consema/consema/blob/main/docs/five-language-ci-design.md §3.3; the Go
# precedent https://github.com/consema/consema-go/blob/main/scripts/go-verify-normalized-differential.ps1).
#
# Bidirectional pipeline (Kotlin never imports or calls Rust, RFC 0016 §1.1):
#   1. builds the minimal Rust evidence example
#      (consema-conformance/examples/emit_normalized_results.rs);
#   2. forward direction: runs it over the provisioned case set
#      (conformance/differential/normalized/cases.json, the shared
#      single-authority case directory of the consema repository) into
#      <OutDir> as
#      one `<case-id>.txt` normalized-facts file per case;
#   3. forward comparison + reverse emission: compiles the Kotlin main +
#      differential tests and runs them through the temp main() test runner
#      with CONSEMA_DIFFERENTIAL_NORMALIZED_RUST_DIR set, which computes the
#      Kotlin normalized results for the same input set and compares them
#      field by field with the Rust evidence files (case id + field + both
#      values on divergence), and emits the Kotlin-side evidence files into
#      CONSEMA_DIFFERENTIAL_NORMALIZED_KT_DIR;
#   4. reverse direction: runs the Rust example's consume mode
#      (`--consume <kt-evidence-dir>`), which recomputes the Rust results
#      and compares them field by field with the Kotlin evidence files.
#
# Any divergence in either direction exits non-zero: forward via the Kotlin
# test, reverse via the consume mode's exit 1.
#
# Requirements: cargo (or $env:CONSEMA_CARGO), a JDK 17 (or
# $env:CONSEMA_JAVA_HOME), and a Kotlin compiler distribution (or
# $env:CONSEMA_KOTLINC); the Rust workspace is the consema-rs checkout
# (<repo root>\consema-rs by default, -RustWorkspace overrides). Windows
# PowerShell 5.1 compatible, no third-party dependencies.
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
    $CaseFile = Join-Path $workspaceRoot 'conformance\differential\normalized\cases.json'
}
if (-not (Test-Path $CaseFile)) {
    Write-Error "normalized differential case file not found: $CaseFile"
    exit 1
}
# UTF8 explicit: PowerShell 5.1 Get-Content defaults to the ANSI codepage.
$cases = Get-Content $CaseFile -Raw -Encoding UTF8 | ConvertFrom-Json
$caseCount = @($cases.cases).Count
if ($caseCount -ne 108) {
    Write-Error "normalized differential case file has $caseCount cases, want exactly 108 (the frozen NormalizedTest.caseFileIntegrity count)"
    exit 1
}

# --- Rust side ---------------------------------------------------------------
$cargo = if ($env:CONSEMA_CARGO) { $env:CONSEMA_CARGO } else { 'cargo' }
if (-not (Get-Command $cargo -ErrorAction SilentlyContinue)) {
    Write-Error "cargo is not available ('$cargo')"
    exit 1
}
Write-Host "[1/4] building the Rust evidence example (emit_normalized_results)..."
# Windows PowerShell 5.1 routes native stderr through the error stream under
# $ErrorActionPreference='Stop'; relax around cargo (its progress lines are
# stderr) and judge success by $LASTEXITCODE only.
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
Push-Location $RustWorkspace
try {
    & $cargo build --locked -p consema-conformance --example emit_normalized_results
    $buildCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
$ErrorActionPreference = $previousEap
if ($buildCode -ne 0) { exit $buildCode }

$targetDir = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $RustWorkspace 'target' }
$example = Join-Path $targetDir 'debug\examples\emit_normalized_results.exe'
if (-not (Test-Path $example)) {
    Write-Error "Rust example binary not found: $example"
    exit 1
}
if ($OutDir -eq '') {
    $OutDir = Join-Path $targetDir "kotlin-differential-normalized-$nonce"
}
# The env vars are consumed by the Kotlin test, so they must be absolute.
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force $OutDir | Out-Null

# --- forward direction: Rust emits, Kotlin compares --------------------------
Write-Host "[2/4] forward: running the Rust example over $caseCount cases -> $OutDir"
& $example $CaseFile $OutDir
if ($LASTEXITCODE -ne 0) {
    Write-Error "emit_normalized_results failed (exit $LASTEXITCODE)"
    exit $LASTEXITCODE
}

# --- Kotlin side: forward comparison + reverse emission ----------------------
$ktEvidenceDir = Join-Path $targetDir "kotlin-differential-normalized-kt-$nonce"
$ktEvidenceDir = [System.IO.Path]::GetFullPath($ktEvidenceDir)
if (Test-Path $ktEvidenceDir) { Remove-Item $ktEvidenceDir -Recurse -Force }
Write-Host "[3/4] compiling the Kotlin side, comparing the forward direction, and emitting the Kotlin evidence files -> $ktEvidenceDir"
$workDir = Join-Path $targetDir "kotlin-verify-normalized-$nonce"
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

$env:CONSEMA_REPO = $workspaceRoot
$env:CONSEMA_DIFFERENTIAL_NORMALIZED_RUST_DIR = $OutDir
$env:CONSEMA_DIFFERENTIAL_NORMALIZED_KT_DIR = $ktEvidenceDir
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

# 2. the temp main() test runner (the kotlin-test shim pattern: the runner
# drives the @Test methods directly, so no JUnit platform is needed at
# runtime; kotlin-test.jar only).
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
    val tests = mapOf(
        "NormalizedTest.caseFileIntegrity" to { NormalizedTest().caseFileIntegrity() },
        "NormalizedTest.differentialNormalized" to { NormalizedTest().differentialNormalized() },
        "NormalizedTest.emitFormatConsistency" to { NormalizedTest().emitFormatConsistency() },
    )
    for (arg in args) {
        val block = tests[arg] ?: error("unknown test $arg")
        run(arg, block)
    }
    println("tests: $runs run, $failures failed")
    if (runs == 0) {
        println("no tests ran — refusing to pass")
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

# 3. run (kotlin-test.jar only at runtime, the documented shim pattern).
$stdoutFile = Join-Path $workDir 'test.stdout.txt'
$stderrFile = Join-Path $workDir 'test.stderr.txt'
$runtimeClasspath = "$mainOut;$runnerOut;$kotlinc\lib\kotlin-stdlib.jar;$kotlinTestJar"
Push-Location $workspaceRoot
try {
    & $java -Xmx2g -cp $runtimeClasspath differential.TestRunnerKt `
        'NormalizedTest.caseFileIntegrity' 'NormalizedTest.differentialNormalized' `
        'NormalizedTest.emitFormatConsistency' `
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

# The differential test must have RUN (not skipped) and passed; the Kotlin
# emitter must have RUN too.
$output = Get-Content $stdoutFile -Raw
if ($output -match 'CONSEMA_DIFFERENTIAL_NORMALIZED_RUST_DIR is not set') {
    Write-Error 'the differential test skipped: the Rust evidence directory was not provisioned'
    exit 1
}
if ($output -match 'CONSEMA_DIFFERENTIAL_NORMALIZED_KT_DIR is not set') {
    Write-Error 'the Kotlin evidence emitter skipped: the Kotlin evidence directory was not provisioned'
    exit 1
}
$summary = [regex]::Match($output, 'normalized-result differential: \d+/\d+ equal')
if (-not $summary.Success) {
    Write-Error "the Kotlin differential tests did not pass (exit $testCode)"
    if ($testCode -eq 0) { exit 1 } else { exit $testCode }
}
if ($testCode -ne 0) {
    exit $testCode
}
Write-Host "RESULT (forward): $($summary.Value)"

# --- reverse direction: Rust consumes and compares the Kotlin evidence -------
Write-Host "[4/4] reverse: running the Rust consume mode against the Kotlin evidence files ($ktEvidenceDir)"
$reverseLog = Join-Path $workDir 'rust-consume.stdout.txt'
$reverseErr = Join-Path $workDir 'rust-consume.stderr.txt'
# Windows PowerShell 5.1 routes native stderr through the error stream under
# $ErrorActionPreference='Stop' and a 2> redirection turns it into a
# NativeCommandError terminating error — exactly on the failure path whose
# diagnostics we want to capture; relax around the consume call.
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $example $CaseFile $OutDir --consume $ktEvidenceDir 1> $reverseLog 2> $reverseErr
$consumeCode = $LASTEXITCODE
$ErrorActionPreference = $previousEap
Get-Content $reverseLog | ForEach-Object { Write-Host $_ }
if (Test-Path $reverseErr) {
    Get-Content $reverseErr | ForEach-Object { Write-Host $_ }
}
if ($consumeCode -ne 0) {
    Write-Error "the Rust consume mode found divergences or failed (exit $consumeCode)"
    exit $consumeCode
}
$reverseSummary = [regex]::Match((Get-Content $reverseLog -Raw), 'reverse normalized-result differential: \d+/\d+ equal')
if ($reverseSummary.Success) {
    Write-Host "RESULT (reverse): $($reverseSummary.Value)"
} else {
    Write-Error 'cannot find the reverse normalized-result differential summary line in the consume-mode output'
    exit 1
}
Write-Host "bidirectional normalized-result differential verification complete (exit 0)"
exit 0
