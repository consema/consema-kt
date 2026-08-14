// The Kotlin test driver of the cross-language protocol exchange harness
// (L5; https://github.com/consema/consema/blob/main/docs/five-language-ci-design.md §3.4; the Go
// precedent consema-go/go/conformance/differential/protocol-exchange/exchange_test.go).
//
// TestCaseFileIntegrity always runs and guards the provisioned case set
// (manifest id, case count, unique ids, records, per-record accept/reject
// coverage, canonical transport JSON, registered expected codes), so any
// test run protects the input set even without the orchestrator.
//
// TestProtocolExchange skips without the environment variables (documented
// skip, never silent) and runs only when
// scripts/kotlin-verify-protocol-exchange.ps1 provisioned the Rust encoder
// files: the Kotlin codecs decode and re-encode the same input set on both
// transports, the cross-language bytes are compared byte for byte, the Rust
// bytes decode under the Kotlin typed record codec to equivalent records and
// re-encode byte-identically, rejection cases reject with the same
// registered code, and the Kotlin-side encoder files are emitted into
// CONSEMA_EXCHANGE_KOTLIN_DIR (which the Rust example's --verify mode
// closes over; wave-4 R50 renamed from CONSEMA_EXCHANGE_KT_DIR).

package differential

import consema.differential.EXCHANGE_MANIFEST
import consema.differential.loadExchangeCaseFile
import consema.differential.runExchange
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ExchangeTest {

    private fun repoRoot(): File =
        File(consema.conformance.resolveRepoRoot())

    private fun caseFile(): File =
        File(repoRoot(), "conformance/differential/protocol-exchange/cases.json")

    @Test
    fun caseFileIntegrity() {
        val cases = loadExchangeCaseFile(caseFile())
        assertEquals(83, cases.size, "case count must stay 83 (frozen test data, measured from cases.json)")
        // The loader already verified the canonicality and the per-record
        // accept/reject coverage; the integrity run keeps the input set
        // honest without golden bytes.
        println("exchange case file integrity: ${cases.size} cases (manifest $EXCHANGE_MANIFEST)")
    }

    @Test
    fun protocolExchange() {
        val cases = loadExchangeCaseFile(caseFile())
        val rustDirEnv = System.getenv("CONSEMA_EXCHANGE_RUST_DIR")
        if (rustDirEnv.isNullOrEmpty()) {
            println(
                "[SKIP] CONSEMA_EXCHANGE_RUST_DIR is not set: " +
                    "run scripts/kotlin-verify-protocol-exchange.ps1 to provision the Rust side",
            )
            return
        }
        val rustDir = File(rustDirEnv)
        assertTrue(rustDir.isDirectory, "rust exchange directory $rustDirEnv is not a directory")
        // wave-4 R50: the language-suffixed env-var name
        // (five-language-ci-design.md §3.4/§7.2; renamed from
        // CONSEMA_EXCHANGE_KT_DIR).
        val ktDirEnv = System.getenv("CONSEMA_EXCHANGE_KOTLIN_DIR")
        val ktDir = if (ktDirEnv.isNullOrEmpty()) null else File(ktDirEnv)
        val report = runExchange(cases, rustDir, ktDir)
        for (failure in report.failures) {
            println("FAIL: $failure")
        }
        println(
            "protocol exchange: ${report.acceptPassed}/${report.acceptCount} accept cases and " +
                "${report.rejectPassed}/${report.rejectCount} reject cases verified",
        )
        if (report.failures.isNotEmpty()) {
            fail("${report.failures.size} protocol-exchange failures (see FAIL lines)")
        }
        if (ktDir != null) {
            println("emitted the Kotlin encoder files into ${ktDir.path}")
        } else {
            println("[SKIP] CONSEMA_EXCHANGE_KOTLIN_DIR is not set: Kotlin encoder files not emitted")
        }
    }
}
