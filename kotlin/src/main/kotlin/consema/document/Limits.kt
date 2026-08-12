// Parse and materialization resource limits.
//
// Data authority:
//   - crates/consema-document/src/lib.rs:614-639 (ParseLimits fields and the
//     frozen defaults: 64 MiB source, depth 256, 2M tokens, 1M nodes, 10k
//     diagnostics). RFC 0016 §5.1 (docs/rfcs/0016-go-api-mapping-v1.md:176):
//     "Parse limits: ParseLimits (and per-family limits) mirror the Rust
//     defaults; exceeding a limit is a ResourceLimit error carrying the
//     frozen limit code".
//   - crates/consema-document/src/materialization.rs:80-105
//     (MaterializationLimits fields and the frozen defaults: 1M input nodes,
//     64 MiB output bytes, depth 256, 100k report entries, 2M provenance
//     entries). RFC 0004 §3 (docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:73-84) freezes the closed v1 limit fields.
//   - go/document/limits.go:4-58 is a cross-reference only (identical
//     numbers).
//
// NOTE: the field set is taken from the authority above — ParseLimits has
// five fields (max_source_bytes, max_nesting_depth, max_token_count,
// max_node_count, max_diagnostics), NOT the max_input_nodes/depth/
// amplification triples of any draft; there is no "amplification" field in
// the frozen contract.

package consema.document

/**
 * Parse resource limits; exceeding one is a fatal formation failure
 * (lib.rs:614-627). Exceeding a limit is a ResourceLimit error carrying the
 * frozen limit code (RFC 0016 §5.1).
 */
data class ParseLimits(
    /** Maximum source bytes. */
    val maxSourceBytes: Int,
    /** Maximum syntax nesting. */
    val maxNestingDepth: Int,
    /** Maximum tokens plus trivia/error regions. */
    val maxTokenCount: Int,
    /** Maximum format syntax nodes. */
    val maxNodeCount: Int,
    /** Maximum diagnostics before an explicit truncation marker. */
    val maxDiagnostics: Int,
) {
    companion object {
        /** The frozen defaults (lib.rs:629-639): 64 MiB source bytes,
         * depth 256, 2,000,000 tokens, 1,000,000 nodes, 10,000
         * diagnostics. */
        val default = ParseLimits(
            maxSourceBytes = 64 shl 20,
            maxNestingDepth = 256,
            maxTokenCount = 2_000_000,
            maxNodeCount = 1_000_000,
            maxDiagnostics = 10_000,
        )
    }
}

/**
 * Resource limits for one complete materialization (RFC 0004 §3;
 * materialization.rs:80-93). All limits apply before or during allocation;
 * a failure returns no Document, no partial bytes, and no provenance that
 * can be mistaken for a result (RFC 0004 §3).
 */
data class MaterializationLimits(
    /** Maximum input PortableValue nodes visited. */
    val maxInputNodes: Int,
    /** Maximum raw output bytes. */
    val maxOutputBytes: Int,
    /** Maximum recursive container depth. */
    val maxDepth: Int,
    /** Maximum structured report events. */
    val maxReportEntries: Int,
    /** Maximum provenance entries and origins combined. */
    val maxProvenanceEntries: Int,
) {
    companion object {
        /** The frozen defaults (materialization.rs:95-105): 1,000,000 input
         * nodes, 64 MiB output bytes, depth 256, 100,000 report entries,
         * 2,000,000 provenance entries. */
        val default = MaterializationLimits(
            maxInputNodes = 1_000_000,
            maxOutputBytes = 64 shl 20,
            maxDepth = 256,
            maxReportEntries = 100_000,
            maxProvenanceEntries = 2_000_000,
        )
    }
}
