// The Kotlin test driver of the cross-language PVCE/PGCE byte-parity
// harness (roadmap §16.1 hard gate; docs/five-language-ci-design.md §3.2).
//
// TestCaseFileIntegrity always runs and guards the checked-in case set
// (manifest id, case count, unique ids, codecs, kinds coverage), so any
// test run protects the input set even without the orchestrator.
//
// TestDifferentialByteParity skips without the environment variable
// (documented skip, never silent) and runs only when the Rust encoder's
// golden byte files were provisioned (scripts/go-verify-byte-parity.ps1
// builds crates/consema-conformance/examples/emit_parity_bytes.rs and runs
// it over the case set into CONSEMA_DIFFERENTIAL_RUST_DIR): the Kotlin
// codecs encode the same input set, and the bytes are compared byte for
// byte with the Rust golden files, plus the bidirectional direction (Rust
// bytes decode under the Kotlin decoders and re-encode byte-identically).

package differential

import consema.differential.PARITY_MANIFEST
import consema.differential.allKindNames
import consema.differential.loadParityCaseFile
import consema.differential.runByteParity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ByteParityTest {

    private fun repoRoot(): File =
        File(consema.conformance.resolveRepoRoot())

    private fun caseFile(): File =
        File(repoRoot(), "conformance/differential/cases.json")

    @Test
    fun caseFileIntegrity() {
        val cases = loadParityCaseFile(caseFile())
        assertEquals(68, cases.size, "case count must stay 68 (frozen test data, measured from cases.json)")
        val kinds = cases.flatMap { it.kinds }.toSet()
        for (kind in allKindNames) {
            assertTrue(kind in kinds, "case set must cover kind $kind")
        }
        // Sanity: the Kotlin encoders can encode every case without golden
        // bytes (the integrity run keeps the input set honest).
        val report = runByteParity(cases, null)
        assertEquals(cases.size, report.passed, "every case must encode without golden bytes")
        assertEquals(0, report.failures.size, "no encoder failures without golden bytes")
    }

    @Test
    fun differentialByteParity() {
        val cases = loadParityCaseFile(caseFile())
        val rustDir = System.getenv("CONSEMA_DIFFERENTIAL_RUST_DIR")
        if (rustDir.isNullOrEmpty()) {
            println(
                "[SKIP] CONSEMA_DIFFERENTIAL_RUST_DIR is not set: " +
                    "run scripts/go-verify-byte-parity.ps1 to provision the Rust encoder bytes",
            )
            return
        }
        val dir = File(rustDir)
        assertTrue(dir.isDirectory, "rust byte directory $rustDir is not a directory")
        // Every golden file must correspond to a case (case file drift).
        val knownIDs = cases.map { it.id }.toSet()
        for (entry in dir.listFiles()!!) {
            if (entry.isDirectory) {
                continue
            }
            val id = entry.name.removeSuffix(".hex")
            assertTrue(id in knownIDs, "rust byte file ${entry.name} does not correspond to any case")
        }
        val report = runByteParity(cases, dir)
        for (failure in report.failures) {
            println("FAIL: $failure")
        }
        println("byte parity: ${report.passed}/${report.total} equal (${report.pvceCount} pvce, ${report.pgceCount} pgce)")
        if (report.failures.isNotEmpty()) {
            fail("${report.failures.size} byte-parity mismatches (see FAIL lines)")
        }
    }
}
