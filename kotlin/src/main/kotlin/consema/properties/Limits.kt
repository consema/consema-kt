// Java Properties parse, projection, and edit resource limits.
//
// Data authority (language-neutral sources first):
//   - RFC 0010 §14 (https://github.com/consema/consema/blob/main/docs/rfcs/0010-java-properties-profiles-v1.md:415-425):
//     PropertiesParseLimits bounds raw and decoded source bytes/scalars,
//     natural-line count and maximum natural-line bytes/scalars, logical-line
//     count/constituents/assembled size, property/comment/escape/Unicode-
//     escape counts, Java code units per key/value and in total,
//     duplicate-group members, and recovery regions.
//   - conformance/vectors/java-properties-v1.json resource.formation-limit-
//     matrix (lines 115-140) and resource.projection-limit-matrix
//     (lines 141-145) pin every limit name and the fatal/no-partial outcome.
//   - consema-rs/consema-properties/src/lib.rs:62-122 (PropertiesParseLimits and
//     the frozen defaults) and projection.rs:84-106 (ProjectionLimits and the
//     frozen defaults) are the byte-arbitration authorities.
//     consema-go/go/properties is a cross-reference only.
//
// Kotlin-idiomatic design: immutable data classes; the default companion
// values are transcribed verbatim from lib.rs:100-122 and projection.rs:
// 97-106 (Rust usize defaults fit the Kotlin Int range).

package consema.properties

import consema.document.ParseLimits

/**
 * Java Properties parse and recovery limits (lib.rs:62-98). Exceeding one is
 * a fatal formation failure carrying `core.parse.resource-limit@1` and the
 * stable limit name (RFC 0016 §5.1; parser.rs:830-845).
 */
data class PropertiesParseLimits(
    /** Common source, node, piece, and diagnostic limits. */
    val common: ParseLimits,
    /** Maximum decoded UTF-8 bytes in the source snapshot. */
    val maxDecodedUtf8Bytes: Int,
    /** Maximum decoded Unicode scalars and coordinate steps. */
    val maxDecodedScalars: Int,
    /** Maximum natural source lines. */
    val maxNaturalLines: Int,
    /** Maximum raw bytes in one natural line. */
    val maxNaturalLineBytes: Int,
    /** Maximum decoded scalars in one natural line. */
    val maxNaturalLineScalars: Int,
    /** Maximum logical property or error lines. */
    val maxLogicalLines: Int,
    /** Maximum natural-line constituents in one logical line. */
    val maxLogicalLineNaturalLines: Int,
    /** Maximum decoded source scalars assembled into one logical line. */
    val maxLogicalLineScalars: Int,
    /** Maximum property occurrences. */
    val maxProperties: Int,
    /** Maximum comment occurrences. */
    val maxComments: Int,
    /** Maximum escape occurrences. */
    val maxEscapes: Int,
    /** Maximum Unicode escape occurrences. */
    val maxUnicodeEscapes: Int,
    /** Maximum Java UTF-16 code units in one key or value. */
    val maxJavaCodeUnitsPerString: Int,
    /** Maximum Java UTF-16 code units across the document. */
    val maxTotalJavaCodeUnits: Int,
    /** Maximum members in one duplicate-key group. */
    val maxDuplicateGroupMembers: Int,
    /** Maximum recovered error lines. */
    val maxRecoveryRegions: Int,
) {
    companion object {
        /** The frozen defaults (lib.rs:100-122). */
        val default = PropertiesParseLimits(
            common = ParseLimits.default,
            maxDecodedUtf8Bytes = 128 shl 20,
            maxDecodedScalars = 64 shl 20,
            maxNaturalLines = 2_000_000,
            maxNaturalLineBytes = 4 shl 20,
            maxNaturalLineScalars = 2_000_000,
            maxLogicalLines = 2_000_000,
            maxLogicalLineNaturalLines = 100_000,
            maxLogicalLineScalars = 16 shl 20,
            maxProperties = 2_000_000,
            maxComments = 2_000_000,
            maxEscapes = 8_000_000,
            maxUnicodeEscapes = 8_000_000,
            maxJavaCodeUnitsPerString = 16 shl 20,
            maxTotalJavaCodeUnits = 64 shl 20,
            maxDuplicateGroupMembers = 1_000_000,
            maxRecoveryRegions = 100_000,
        )
    }
}
