// Frozen limit defaults tests.
//
// Data authority: crates/consema-document/src/lib.rs:614-639 (ParseLimits),
// materialization.rs:80-105 (MaterializationLimits), source.rs:381-409
// (SourceLimits), source_patch.rs:8-27 (SourcePatchLimits); cross-checked
// against go/document/limits.go:4-58 and go/document/source.go:27-43
// (identical numbers). RFC 0016 §5.1: ParseLimits mirrors the Rust defaults.

package document

import consema.document.MaterializationLimits
import consema.document.ParseLimits
import consema.document.SourceLimits
import consema.document.SourcePatchLimits
import kotlin.test.Test
import kotlin.test.assertEquals

class LimitsTest {

    /** lib.rs:629-639: 64 MiB source bytes, depth 256, 2,000,000 tokens,
     * 1,000,000 nodes, 10,000 diagnostics. */
    @Test
    fun parseLimitsDefaultsAreFrozen() {
        val limits = ParseLimits.default
        assertEquals(64 shl 20, limits.maxSourceBytes)
        assertEquals(256, limits.maxNestingDepth)
        assertEquals(2_000_000, limits.maxTokenCount)
        assertEquals(1_000_000, limits.maxNodeCount)
        assertEquals(10_000, limits.maxDiagnostics)
    }

    /** materialization.rs:95-105: 1,000,000 input nodes, 64 MiB output
     * bytes, depth 256, 100,000 report entries, 2,000,000 provenance
     * entries. */
    @Test
    fun materializationLimitsDefaultsAreFrozen() {
        val limits = MaterializationLimits.default
        assertEquals(1_000_000, limits.maxInputNodes)
        assertEquals(64 shl 20, limits.maxOutputBytes)
        assertEquals(256, limits.maxDepth)
        assertEquals(100_000, limits.maxReportEntries)
        assertEquals(2_000_000, limits.maxProvenanceEntries)
    }

    /** source.rs:401-409: 64 MiB raw bytes, 128 MiB decoded UTF-8 bytes,
     * 64 MiB decoded scalars. */
    @Test
    fun sourceLimitsDefaultsAreFrozen() {
        val limits = SourceLimits.default
        assertEquals(64 shl 20, limits.maxRawBytes)
        assertEquals(128 shl 20, limits.maxDecodedUtf8Bytes)
        assertEquals(64 shl 20, limits.maxDecodedScalars)
        // The compatibility limits (source.rs:392-399).
        assertEquals(Int.MAX_VALUE, SourceLimits.UNBOUNDED.maxRawBytes)
        assertEquals(Int.MAX_VALUE, SourceLimits.UNBOUNDED.maxDecodedUtf8Bytes)
        assertEquals(Int.MAX_VALUE, SourceLimits.UNBOUNDED.maxDecodedScalars)
    }

    /** source_patch.rs:19-27: default source limits, 100,000 replacements,
     * 128 MiB patch bytes. */
    @Test
    fun sourcePatchLimitsDefaultsAreFrozen() {
        val limits = SourcePatchLimits.default
        assertEquals(SourceLimits.default, limits.source)
        assertEquals(100_000, limits.maxReplacements)
        assertEquals(128 shl 20, limits.maxPatchBytes)
    }
}
