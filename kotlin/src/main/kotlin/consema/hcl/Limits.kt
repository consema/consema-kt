// HCL-specific formation, structure, recovery, and report limits.
//
// Data authority:
//   - RFC 0014 §11 (https://github.com/consema/consema/blob/main/docs/rfcs/0014-hcl-family-profiles-v1.md): the
//     `HclParseLimits` bounds at least raw bytes and decoded scalars, body
//     nesting depth (blocks), expression depth, and template nesting depth,
//     attribute/block/label/body-item counts, identifier/string/number/
//     template/heredoc byte lengths, tuple/object element counts and
//     for-expression extent, recovery/error region and diagnostics counts,
//     and projection/materialization/edit node counts and report events.
//     All size arithmetic is checked before allocation, and limit failure
//     never masquerades as an empty body, truncated expression, shortened
//     query, partial target, or successful edit (hard gate 4).
//   - https://github.com/consema/consema-rs/blob/main/consema-hcl/src/lib.rs pins the exact field set and the
//     frozen R-3 defaults (lib.rs: 64 MiB source, 128 body depth,
//     24 expression depth, 256 template depth, ...); the conformance vectors
//     hcl.limit.* pin the frozen limit-code spellings
//     (`hcl.limit.expression-depth@1`, `hcl.limit.body-depth@1`,
//     `hcl.limit.number-digits@1`, `hcl.limit.attribute-count@1`,
//     `hcl.limit.block-count@1`, `hcl.limit.body-item-count@1`,
//     `hcl.limit.label-count@1`, `hcl.limit.template-len@1`,
//     `hcl.limit.heredoc-bytes@1`, `hcl.limit.tuple-elements@1`,
//     `hcl.limit.object-entries@1`).
//   - consema-go/go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design: one immutable data class with the common
// consema.document.ParseLimits embedded, exactly like the Rust
// `HclParseLimits { common: ParseLimits, ... }` shape (lib.rs).

package consema.hcl

import consema.document.ParseLimits

/**
 * HCL-specific formation, structure, recovery, and report limits (RFC 0014
 * §11; lib.rs). The common limits bound source bytes, generic
 * nesting, token and node counts, and diagnostics; the flat fields bound the
 * HCL-specific facts. Every limit failure is a fatal formation failure or an
 * atomic operation failure (hard gate 4).
 */
data class HclParseLimits(
    /** Common source, nesting, token, node, and diagnostic limits; includes
     * `max_source_bytes` and `max_diagnostics` (lib.rs). */
    val common: ParseLimits,

    /** Maximum decoded UTF-8 bytes. */
    val maxDecodedUtf8Bytes: Int,

    /** Maximum decoded Unicode scalars. */
    val maxDecodedScalars: Int,

    /** Maximum body nesting depth (block nesting; the root body is depth 1). */
    val maxBodyDepth: Int,

    /** Maximum expression depth (the parse recursion budget). */
    val maxExpressionDepth: Int,

    /** Maximum template nesting depth (interpolations and directives may
     * contain nested templates). */
    val maxTemplateDepth: Int,

    /** Maximum attributes in one body. */
    val maxAttributeCount: Int,

    /** Maximum blocks in one body. */
    val maxBlockCount: Int,

    /** Maximum labels on one block. */
    val maxLabelCount: Int,

    /** Maximum body items (attributes plus blocks) in one body. */
    val maxBodyItemCount: Int,

    /** Maximum identifier byte length (attributes, blocks, labels,
     * variables, and functions). */
    val maxIdentifierLen: Int,

    /** Maximum quoted-template byte length. */
    val maxStringLen: Int,

    /** Maximum canonical-decimal digit count of one number. */
    val maxNumberDigits: Int,

    /** Maximum template (quoted or heredoc content) byte length. */
    val maxTemplateLen: Int,

    /** Maximum interpolation or directive sequences in one template. */
    val maxTemplateInterpolations: Int,

    /** Maximum lines in one heredoc. */
    val maxHeredocLines: Int,

    /** Maximum heredoc bytes; bounds the error region of an unterminated
     * heredoc (RFC 0014 §3, §11). */
    val maxHeredocBytes: Int,

    /** Maximum elements in one tuple constructor. */
    val maxTupleElements: Int,

    /** Maximum entries in one object constructor. */
    val maxObjectEntries: Int,

    /** Maximum extent of one for-expression. */
    val maxForExtent: Int,

    /** Maximum recovery regions in one document. */
    val maxRecoveryRegions: Int,

    /** Maximum error regions in one document. */
    val maxErrorRegions: Int,

    /** Maximum lossless syntax pieces in one document (RFC 0014 §7.2). */
    val maxSyntaxPieces: Int,

    /** Maximum projection, materialization, or edit report events. */
    val maxReportEvents: Int,
) {
    companion object {
        /** The frozen R-3 defaults (lib.rs): 64 MiB source bytes,
         * 128 body levels, 24 expression levels, 256 template levels, and
         * generous flat count limits. */
        val default = HclParseLimits(
            common = ParseLimits.default,
            maxDecodedUtf8Bytes = 128 shl 20,
            maxDecodedScalars = 64 shl 20,
            maxBodyDepth = 128,
            maxExpressionDepth = 24,
            maxTemplateDepth = 256,
            maxAttributeCount = 1_000_000,
            maxBlockCount = 1_000_000,
            maxLabelCount = 1_000_000,
            maxBodyItemCount = 1_000_000,
            maxIdentifierLen = 1024,
            maxStringLen = 16 shl 20,
            maxNumberDigits = 100_000,
            maxTemplateLen = 16 shl 20,
            maxTemplateInterpolations = 1_000_000,
            maxHeredocLines = 1_000_000,
            maxHeredocBytes = 16 shl 20,
            maxTupleElements = 1_000_000,
            maxObjectEntries = 1_000_000,
            maxForExtent = 1_000_000,
            maxRecoveryRegions = 100_000,
            maxErrorRegions = 100_000,
            maxSyntaxPieces = 2_000_000,
            maxReportEvents = 100_000,
        )
    }
}
