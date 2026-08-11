// Temporary debug runner for the conformance v1 suite.
import consema.conformance.Runner

fun main() {
    val repo = consema.conformance.resolveRepoRoot()
    val runner = Runner(
        vectorsDir = "$repo/conformance/vectors",
        fixturesDir = "$repo/conformance/fixtures",
        manifestPath = "$repo/docs/fc-manifest-0.13.0.json",
    )
    val digest = runner.verifyVectorsDigest()
    println("digest ok=${digest.ok} computed=${digest.computed} recorded=${digest.recorded} suites=${digest.suites} cases=${digest.cases}")
    val report = runner.run()
    for (suite in report.suites) {
        println("SUITE ${suite.suite}: passed=${suite.passed.size} skipped=${suite.skipped.size} failed=${suite.failed.size}")
        for (failure in suite.failed) {
            println("  FAIL ${failure.id}: ${failure.message}")
        }
        for (skip in suite.skipped.take(3)) {
            println("  SKIP ${skip.id}: ${skip.capability} (${skip.reason})")
        }
    }
    println("TOTAL passed=${report.passed} skipped=${report.skipped} failed=${report.failed} total=${report.total}")
}
