// INI-specific parse and recovery resource limits.
//
// Data authority:
//   - RFC 0009 §13 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md:475-489):
//     IniParseLimits bounds raw/decoded bytes, scalar/boundary counts,
//     physical/logical lines and their byte/scalar maxima, continuation
//     physical-line count, sections/entries/duplicate-group members, syntax
//     pieces, diagnostics, and recovery regions; limit failure never returns
//     a truncated Complete Document.
//   - consema-rs/consema-ini/src/lib.rs:67-119 pins the fields and the frozen
//     defaults; consema-rs/consema-document/src/lib.rs:614-639 pins the common
//     ParseLimits defaults (64 MiB source, depth 256, 2M tokens, 1M nodes,
//     10k diagnostics). consema-go/go/ini/limits.go is a cross-reference only.

package consema.ini

import consema.document.ParseLimits

/**
 * INI-specific parse and recovery limits (lib.rs:67-98). Exceeding one is a
 * fatal formation failure carrying the frozen limit code
 * (RFC 0016 §5.1: ParseLimits and per-family limits mirror the Rust
 * defaults; exceeding a limit is a ResourceLimit error).
 */
data class IniParseLimits(
    /** Common source, node, piece, nesting, and diagnostic limits. */
    val common: ParseLimits,
    /** Maximum decoded UTF-8 bytes. */
    val maxDecodedUtf8Bytes: Int,
    /** Maximum decoded Unicode scalars and coordinate steps. */
    val maxDecodedScalars: Int,
    /** Maximum physical source lines. */
    val maxPhysicalLines: Int,
    /** Maximum raw bytes in one physical line. */
    val maxPhysicalLineBytes: Int,
    /** Maximum decoded scalars in one physical line. */
    val maxPhysicalLineScalars: Int,
    /** Maximum logical records. */
    val maxLogicalLines: Int,
    /** Maximum raw bytes owned by one logical record. */
    val maxLogicalLineBytes: Int,
    /** Maximum decoded scalars in one logical record. */
    val maxLogicalLineScalars: Int,
    /** Maximum continuation physical lines per Python entry. */
    val maxContinuationLines: Int,
    /** Maximum section occurrences. */
    val maxSections: Int,
    /** Maximum entry occurrences. */
    val maxEntries: Int,
    /** Maximum members in one duplicate or case-equivalence group. */
    val maxDuplicateGroupMembers: Int,
    /** Maximum recovered error lines. */
    val maxRecoveryRegions: Int,
) {
    companion object {
        /** The frozen defaults (lib.rs:100-119). */
        val default = IniParseLimits(
            common = ParseLimits.default,
            maxDecodedUtf8Bytes = 128 shl 20,
            maxDecodedScalars = 64 shl 20,
            maxPhysicalLines = 2_000_000,
            maxPhysicalLineBytes = 4 shl 20,
            maxPhysicalLineScalars = 2_000_000,
            maxLogicalLines = 2_000_000,
            maxLogicalLineBytes = 16 shl 20,
            maxLogicalLineScalars = 8_000_000,
            maxContinuationLines = 100_000,
            maxSections = 1_000_000,
            maxEntries = 1_000_000,
            maxDuplicateGroupMembers = 100_000,
            maxRecoveryRegions = 100_000,
        )
    }
}
