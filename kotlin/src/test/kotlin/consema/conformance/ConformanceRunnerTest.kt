// The L4 conformance runner test: 18 suites / 508 cases / aggregate digest.
//
// Data authority (language-neutral sources first):
//   - docs/fc-manifest-0.13.0.json:35-40 (conformance_suite: 18 suites /
//     508 cases / aggregate_sha256 35bebc8d384d71740f7c1a886bc50f4e095ff52f
//     e05d2a407f04b842ee6922fa over the byte-order filename digest lines).
//   - docs/five-language-ci-design.md §2.2 (each runner must assert the
//     case count 18/508 and the aggregate digest inside the runner; the
//     suite-count assertion is per suite).
//   - conformance/README.md:81-82 (rule 4: every suite must validate its
//     case count).
//   - go/conformance/conformance_test.go:106 (cross-reference: the Go
//     runner test asserts the same digest and counts).
//
// The vector files themselves drive every input and expectation; this test
// holds no expectation literals beyond the manifest-pinned counts and
// digest.

package consema.conformance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConformanceRunnerTest {

    private fun runner(): Runner {
        val repo = resolveRepoRoot()
        return Runner(
            vectorsDir = File(repo, "conformance/vectors").path,
            fixturesDir = File(repo, "conformance/fixtures").path,
            manifestPath = File(repo, "docs/fc-manifest-0.13.0.json").path,
        )
    }

    @Test
    fun aggregateDigestMatchesTheManifest() {
        val digest = runner().verifyVectorsDigest()
        assertTrue(
            digest.ok,
            "digest mismatch: computed=${digest.computed} recorded=${digest.recorded} " +
                "suites=${digest.suites} cases=${digest.cases}",
        )
        assertEquals(18, digest.suites, "eighteen vector files")
        assertEquals(508, digest.cases, "five hundred eight cases")
    }

    @Test
    fun allEighteenSuitesPassFiveHundredEightCases() {
        val report = runner().run()
        assertTrue(
            report.digest.ok,
            "digest mismatch: computed=${report.digest.computed} recorded=${report.digest.recorded}",
        )
        assertEquals(18, report.suites.size, "eighteen suites")
        assertEquals(508, report.total, "five hundred eight cases total")
        assertEquals(508, report.passed, "every case passed (no skips)")
        assertEquals(0, report.skipped, "no documented skips")
        assertEquals(0, report.failed, "no failures")
        assertTrue(report.conformant(), "conformance run must be conformant")
    }

    @Test
    fun everySuiteAssertsItsFrozenCaseCount() {
        val report = runner().run()
        for (suite in report.suites) {
            assertTrue(
                suite.countAsserted(),
                "suite ${suite.suite}: count assertion failed " +
                    "(expected ${suite.expectedCases}, " +
                    "passed ${suite.passed.size} + skipped ${suite.skipped.size} + " +
                    "failed ${suite.failed.size})",
            )
            assertEquals(suite.expectedCases, suite.passed.size + suite.skipped.size + suite.failed.size)
        }
        // The per-suite counts sum to the frozen total.
        assertEquals(508, report.suites.sumOf { it.passed.size + it.skipped.size + it.failed.size })
    }
}
