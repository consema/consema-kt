// The L4 conformance runner test: 18 suites / 519 cases / aggregate digest.
//
// Data authority (language-neutral sources first):
//   - https://github.com/consema/consema/blob/main/docs/fc-manifest-0.13.0.json — digests.conformance_suite
//     (18 suites / 519 cases / aggregate_sha256 cfd6e296da5b22b62d37b076d35
//     bf6bbf58b0678ceddb37eea51a8b47200ab6a over the byte-order filename
//     digest lines).
//   - https://github.com/consema/consema/blob/main/docs/five-language-ci-design.md §2.2 (each runner must assert the
//     case count 18/519 and the aggregate digest inside the runner; the
//     suite-count assertion is per suite).
//   - conformance/README.md 规则 4 (every suite must validate its
//     case count).
//   - https://github.com/consema/consema-go/blob/main/go/conformance/conformance_test.go (cross-reference: the Go
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
        assertEquals(519, digest.cases, "five hundred nineteen cases")
    }

    @Test
    fun allEighteenSuitesPassFiveHundredNineteenCases() {
        val report = runner().run()
        assertTrue(
            report.digest.ok,
            "digest mismatch: computed=${report.digest.computed} recorded=${report.digest.recorded}",
        )
        assertEquals(18, report.suites.size, "eighteen suites")
        assertEquals(519, report.total, "five hundred nineteen cases total")
        assertEquals(519, report.passed, "every case passed (no skips)")
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
        assertEquals(519, report.suites.sumOf { it.passed.size + it.skipped.size + it.failed.size })
    }
}
