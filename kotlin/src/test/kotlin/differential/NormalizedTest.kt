// The Kotlin test driver of the cross-language normalized-result differential
// harness (L5, bidirectional;
// https://github.com/consema/consema/blob/main/docs/five-language-ci-design.md §3.3; the Go precedent
// consema-go/go/conformance/differential/normalized/normalized_test.go).
//
// TestCaseFileIntegrity always runs and guards the provisioned case set
// (manifest id, case count, unique ids, schema validity), so any test run
// protects the input set even without the orchestrator.
//
// TestDifferentialNormalized skips without the environment variable
// (documented skip, never silent) and runs only when
// scripts/kotlin-verify-normalized-differential.ps1 provisioned the Rust
// evidence directory: the Kotlin SDK executes the same input set, the two
// normalized results are compared field by field, and every divergence is
// reported as case id + field + both values.
//
// The harness is bidirectional (roadmap §16.6 line 1548): the test also
// emits the Kotlin-side evidence files for the same input set (one
// `<case-id>.txt` per case, the same line-oriented key=value format the
// forward direction reads) into CONSEMA_DIFFERENTIAL_NORMALIZED_KT_DIR, and
// the Rust example's consume mode compares them with its own results.
// TestEmitFormatConsistency always runs and proves the emitted files
// round-trip through the forward reader.

package differential

import consema.differential.NORMALIZED_MANIFEST
import consema.differential.compareFacts
import consema.differential.loadNormalizedCaseFile
import consema.differential.runNormalizedCase
import consema.differential.splitEvidenceLines
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class NormalizedTest {

    private fun repoRoot(): File =
        File(consema.conformance.resolveRepoRoot())

    private fun caseFile(): File =
        File(repoRoot(), "conformance/differential/normalized/cases.json")

    @Test
    fun caseFileIntegrity() {
        val cases = loadNormalizedCaseFile(caseFile())
        assertEquals(108, cases.size, "case count must stay 108 (frozen test data, measured from cases.json)")
        // Sanity: every case runs without goldens and emits a well-formed
        // fact set (the integrity run keeps the input set honest).
        for (case in cases) {
            val lines = runNormalizedCase(case)
            assertTrue(lines.isNotEmpty(), "case ${case.id}: emitted no facts")
            val keys = HashSet<String>()
            for (line in lines) {
                val (key, value) = consema.differential.splitFact(line)
                    ?: fail("case ${case.id}: malformed fact line $line")
                assertTrue(key.isNotEmpty(), "case ${case.id}: empty fact key")
                assertTrue(keys.add(key), "case ${case.id}: duplicate fact key $key")
            }
        }
    }

    /** Emits the Kotlin-side evidence files (the reverse direction). */
    private fun emitFactsToDir(cases: List<consema.differential.NormalizedCase>, dir: File): Int {
        dir.mkdirs()
        var emitted = 0
        for (case in cases) {
            val lines = runNormalizedCase(case)
            val content = buildString {
                for (line in lines) {
                    append(line)
                    append('\n')
                }
            }
            File(dir, "${case.id}.txt").writeText(content, Charsets.UTF_8)
            emitted++
        }
        return emitted
    }

    @Test
    fun differentialNormalized() {
        val cases = loadNormalizedCaseFile(caseFile())
        val rustDirEnv = System.getenv("CONSEMA_DIFFERENTIAL_NORMALIZED_RUST_DIR")
        if (rustDirEnv.isNullOrEmpty()) {
            println(
                "[SKIP] CONSEMA_DIFFERENTIAL_NORMALIZED_RUST_DIR is not set: " +
                    "run scripts/kotlin-verify-normalized-differential.ps1 to provision the Rust evidence files",
            )
            return
        }
        val rustDir = File(rustDirEnv)
        assertTrue(rustDir.isDirectory, "rust evidence directory $rustDirEnv is not a directory")
        // Every evidence file must correspond to a case (case file drift).
        val knownIDs = cases.map { it.id }.toSet()
        for (entry in rustDir.listFiles()!!) {
            if (entry.isDirectory) {
                continue
            }
            val id = entry.name.removeSuffix(".txt")
            assertTrue(id in knownIDs, "rust evidence file ${entry.name} does not correspond to any case")
        }
        val ktDirEnv = System.getenv("CONSEMA_DIFFERENTIAL_NORMALIZED_KT_DIR")
        val ktDir = if (ktDirEnv.isNullOrEmpty()) null else File(ktDirEnv)

        var passed = 0
        val failures = ArrayList<String>()
        for (case in cases) {
            val kotlinLines = runNormalizedCase(case)
            val rustText = File(rustDir, "${case.id}.txt").readText()
            val rustLines = splitEvidenceLines(rustText)
            val fieldFailures = compareFacts(case.id, kotlinLines, rustLines)
            if (fieldFailures.isEmpty()) {
                passed++
            } else {
                failures.addAll(fieldFailures)
            }
        }
        for (failure in failures) {
            println("FAIL: $failure")
        }
        println("normalized-result differential: $passed/${cases.size} equal")
        if (failures.isNotEmpty()) {
            fail("${failures.size} normalized-result mismatches (see FAIL lines)")
        }
        if (ktDir != null) {
            val emitted = emitFactsToDir(cases, ktDir)
            println("emitted $emitted Kotlin normalized results into ${ktDir.path}")
        } else {
            println("[SKIP] CONSEMA_DIFFERENTIAL_NORMALIZED_KT_DIR is not set: Kotlin evidence files not emitted")
        }
    }

    @Test
    fun emitFormatConsistency() {
        val cases = loadNormalizedCaseFile(caseFile())
        val dir = File.createTempFile("kt-normalized-consistency", "").let { temp ->
            temp.delete()
            File(temp.parentFile, "kt-normalized-consistency-" + System.nanoTime())
        }
        try {
            val emitted = emitFactsToDir(cases, dir)
            assertEquals(cases.size, emitted, "emitted case count")
            for (case in cases) {
                val lines = splitEvidenceLines(File(dir, "${case.id}.txt").readText())
                val computed = runNormalizedCase(case)
                val fieldFailures = compareFacts(case.id, computed, lines)
                for (failure in fieldFailures) {
                    println("FAIL: $failure")
                }
                assertTrue(fieldFailures.isEmpty(), "case ${case.id}: emitted format does not round-trip")
            }
            println("emitted format round-trips through the forward reader for $emitted/${cases.size} cases")
        } finally {
            dir.deleteRecursively()
        }
    }
}
