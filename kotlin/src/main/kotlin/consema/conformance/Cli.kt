// The conformance runner CLI entry (Kotlin).
//
// Runs all eighteen shared vector suites, prints a per-suite pass/fail
// report plus the aggregate digest verification, and exits non-zero when
// any suite fails or the digest does not match the Feature-Complete
// Manifest.
//
// Data authority: https://github.com/consema/consema/blob/main/docs/five-language-ci-design.md §2 (each runner owns its
// CLI form; the Go CLI cmd/consema-conformance is the cross-reference
// shape); conformance/README.md 规则 4 (every suite must validate its case
// count).
//
// Usage:
//   consema-conformance [<repo-root>]
// The repository root is the CONSEMA_REPO environment variable when set,
// otherwise the nearest ancestor of the working directory containing both
// conformance/vectors and docs/fc-manifest-0.13.0.json (Runner.resolveRepoRoot).

package consema.conformance

import java.io.File
import kotlin.system.exitProcess

/** Runs every suite and prints the per-suite report. */
fun runAllAndPrint(repoRoot: String): RunReport {
    val runner = Runner(
        vectorsDir = File(repoRoot, "conformance/vectors").path,
        fixturesDir = File(repoRoot, "conformance/fixtures").path,
        manifestPath = File(repoRoot, "docs/fc-manifest-0.13.0.json").path,
    )
    val report = runner.run()
    val digest = report.digest
    println(
        "digest: ${if (digest.ok) "OK" else "MISMATCH"} " +
            "computed=${digest.computed} recorded=${digest.recorded} " +
            "suites=${digest.suites} cases=${digest.cases}",
    )
    for (suite in report.suites) {
        val status = when {
            suite.failed.isNotEmpty() -> "FAIL"
            suite.countAsserted() -> "PASS"
            else -> "FAIL"
        }
        println(
            "$status ${suite.suite.padEnd(42)} " +
                "passed=${suite.passed.size} skipped=${suite.skipped.size} " +
                "failed=${suite.failed.size}",
        )
        for (failure in suite.failed) {
            println("      FAIL ${failure.id}: ${failure.message}")
        }
        for (skip in suite.skipped) {
            println("      SKIP ${skip.id} (${skip.capability}): ${skip.reason}")
        }
    }
    println(
        "total: ${report.total} cases, ${report.passed} passed, " +
            "${report.skipped} skipped, ${report.failed} failed",
    )
    return report
}

/** The conformance CLI entry: per-suite pass/fail. Exit classes per
 * RFC 0015 §5.1 (six classes, codes 0-5; the frozen taxonomy lives in
 * consema.protocol.ExitClass): 0 (success) on a conformant run, 1
 * (usage: an unknown flag or an extra positional argument — the
 * documented usage is `consema-conformance [<repo-root>]`), 2 (data:
 * a non-conformant run means the input data failed). The repository-root
 * resolution failure exits 4 (precondition: the required CONSEMA_REPO or
 * provisioned-tree precondition was not met) instead of an uncaught-
 * exception exit-1 stack — an internal-error-looking exit is never
 * produced on a documented failure path. */
fun main(args: Array<String>) {
    // Wave-4 usage-class fix: the usage exit (1) was previously
    // unreachable — every argument was silently treated as the repo-root
    // positional (an unknown flag ran all 18 suites against a bogus
    // directory and exited 2, and extra positional arguments were
    // silently ignored). Unknown flags and extra arguments are now
    // rejected per RFC 0015 §5.1 (the ts/py runners refuse the same
    // way).
    if (args.size > 1 || (args.isNotEmpty() && args[0].startsWith("-"))) {
        System.err.println("consema-conformance: usage: consema-conformance [<repo-root>]")
        exitProcess(1)
    }
    val repoRoot = if (args.isNotEmpty()) {
        args[0]
    } else {
        try {
            resolveRepoRoot()
        } catch (e: IllegalStateException) {
            System.err.println(
                "consema-conformance: repository root not found - set CONSEMA_REPO or run inside a provisioned checkout",
            )
            exitProcess(4)
        }
    }
    val report = runAllAndPrint(repoRoot)
    exitProcess(if (report.conformant()) 0 else 2)
}
