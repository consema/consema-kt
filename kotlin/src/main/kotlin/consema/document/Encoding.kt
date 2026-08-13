// Source encoding facts: the closed v1 encoding set, BOM detection, the
// frozen resolution priority, and construction limits.
//
// Data authority (language-neutral sources first):
//   - RFC 0003 §4 (https://github.com/consema/consema/blob/main/docs/rfcs/0003-source-syntax-query-and-patch-v1.md:66-123)
//     freezes the closed v1 encoding IDs (Binary, Utf8, Utf16Le, Utf16Be,
//     Latin1), the resolution inputs (profile_default, bom, declaration,
//     caller_override, selected), and the priority order
//     caller_override -> declaration -> bom -> profile_default; any two
//     present disagreeing facts produce EncodingConflict.
//   - conformance/vectors/source-v1.json cases source.encoding.* (lines
//     24-82) pin the wire spellings ("utf-8", "utf-16le", "utf-16be",
//     "latin-1", "binary") and the rejection codes.
//   - consema-rs/consema-document/src/source.rs:121-409 pins the shapes and the
//     resolution/decoding rules; consema-go/go/document/encoding.go is a cross-reference
//     only.
//
// Source contract v2 (0.8.0, the java-properties family) extends this set
// with WindowsCodePage and BomPolicy::TreatAsContent
// (consema-rs/consema-protocol/src/error_registry.rs:967-977 registers
// core.source.code-page-required@1 / core.source.unsupported-code-page@1).
// The WindowsCodePage extension ships in the ini family
// (consema/ini/Profile.kt IniWindowsCodePage) and is not part of this v1
// source surface; BomPolicy is nevertheless present here because the v1
// resolution rule already distinguishes BOM evidence from content
// (source.rs:727-738).

package consema.document

/**
 * Closed source encoding set supported by source contracts v1 (RFC 0003
 * §4.1; source.rs:121-155). The wire spellings are the exact strings the
 * shared vectors use (source-v1.json source.encoding.* cases).
 */
sealed class SourceEncoding {
    /** Opaque bytes without a decoded-text view. */
    data object Binary : SourceEncoding()

    /** Unicode UTF-8. */
    data object Utf8 : SourceEncoding()

    /** Unicode UTF-16 with little-endian code units. */
    data object Utf16Le : SourceEncoding()

    /** Unicode UTF-16 with big-endian code units. */
    data object Utf16Be : SourceEncoding()

    /** ISO-8859-1 byte-to-scalar mapping. It is not Windows-1252
     * (RFC 0003 §4.1). */
    data object Latin1 : SourceEncoding()

    /** Stable wire identifier (source.rs:141-150; the exact strings used by
     * source-v1.json). */
    fun asStr(): String =
        when (this) {
            Binary -> "binary"
            Utf8 -> "utf-8"
            Utf16Le -> "utf-16le"
            Utf16Be -> "utf-16be"
            Latin1 -> "latin-1"
        }

    internal fun isText(): Boolean = this !== Binary
}

/** Recognized Unicode byte-order mark (source.rs:167-187). */
enum class BomKind {
    /** EF BB BF. */
    Utf8,

    /** FF FE. */
    Utf16Le,

    /** FE FF. */
    Utf16Be,
    ;

    /** Encoding asserted by this marker (source.rs:179-186). */
    fun encoding(): SourceEncoding =
        when (this) {
            Utf8 -> SourceEncoding.Utf8
            Utf16Le -> SourceEncoding.Utf16Le
            Utf16Be -> SourceEncoding.Utf16Be
        }
}

/** Recognized but unsupported Unicode marker (source.rs:719-725). */
enum class UnsupportedBomKind {
    /** FF FE 00 00. */
    Utf32Le,

    /** 00 00 FE FF. */
    Utf32Be,
}

/** Whether marker-shaped leading bytes participate in Unicode BOM resolution
 * (source.rs:157-164). The v1 rule is [DetectUnicode]; TreatAsContent is the
 * source-v2 escape used by code-page sources. */
enum class BomPolicy {
    /** Detect UTF-8/UTF-16 BOMs using the frozen source-v1 rule. */
    DetectUnicode,

    /** Decode all bytes as content under the explicitly selected encoding. */
    TreatAsContent,
}

/**
 * Caller inputs to deterministic encoding resolution (RFC 0003 §4.2;
 * source.rs:189-260). The selected encoding is the first present value in
 * this priority order: caller_override -> declaration -> bom ->
 * profile_default (RFC 0003 §4.2; source.rs:769-773).
 */
class EncodingRequest internal constructor(
    /** Profile fallback (required). */
    val profileDefault: SourceEncoding,
    /** BOM interpretation policy; defaults to DetectUnicode. */
    val bomPolicy: BomPolicy,
    /** Normalized in-source declaration supplied by the format layer. */
    val declaration: SourceEncoding?,
    /** Explicit caller choice. */
    val callerOverride: SourceEncoding?,
) {
    companion object {
        /** Starts with the required profile default and no higher-priority
         * facts (source.rs:200-208). */
        fun new(profileDefault: SourceEncoding): EncodingRequest =
            EncodingRequest(profileDefault, BomPolicy.DetectUnicode, null, null)

        /** Opaque-binary request (source.rs:211-214). */
        fun binary(): EncodingRequest = new(SourceEncoding.Binary)
    }

    /** Adds a normalized declaration supplied by the format layer
     * (source.rs:217-221). */
    fun withDeclaration(declaration: SourceEncoding): EncodingRequest =
        EncodingRequest(profileDefault, bomPolicy, declaration, callerOverride)

    /** Adds an explicit caller override (source.rs:224-228). */
    fun withCallerOverride(callerOverride: SourceEncoding): EncodingRequest =
        EncodingRequest(profileDefault, bomPolicy, declaration, callerOverride)

    /** Selects whether leading marker-shaped bytes are BOM evidence or
     * content (source.rs:231-235). */
    fun withBomPolicy(bomPolicy: BomPolicy): EncodingRequest =
        EncodingRequest(profileDefault, bomPolicy, declaration, callerOverride)
}

/**
 * Complete, auditable result of encoding resolution (RFC 0003 §4.2;
 * source.rs:262-379). Equality of two fact records is a language-neutral
 * fact used by SourcePatch application (source_patch.rs:263-275).
 */
data class EncodingFacts(
    /** Profile fallback that participated in resolution. */
    val profileDefault: SourceEncoding,
    /** BOM interpretation policy used for this source. */
    val bomPolicy: BomPolicy,
    /** Recognized byte-order mark. */
    val bom: BomKind?,
    /** Normalized in-source declaration. */
    val declaration: SourceEncoding?,
    /** Explicit caller override. */
    val callerOverride: SourceEncoding?,
    /** Encoding selected by the frozen priority rule. */
    val selected: SourceEncoding,
) {
    companion object {
        /**
         * Validates a structurally complete encoding-facts claim
         * (source.rs:278-300). This proves resolution consistency only; a
         * source decoder must still verify that the claimed BOM is present
         * in the supplied raw bytes.
         */
        fun fromClaim(
            profileDefault: SourceEncoding,
            bom: BomKind?,
            declaration: SourceEncoding?,
            callerOverride: SourceEncoding?,
            selected: SourceEncoding,
        ): EncodingFacts =
            resolveAssertions(
                EncodingRequest(
                    profileDefault,
                    BomPolicy.DetectUnicode,
                    declaration,
                    callerOverride,
                ),
                bom,
                selected,
            )

        /**
         * Validates a source-v2 claim including explicit BOM interpretation
         * (source.rs:303-333).
         */
        fun fromClaimWithBomPolicy(
            profileDefault: SourceEncoding,
            bomPolicy: BomPolicy,
            bom: BomKind?,
            declaration: SourceEncoding?,
            callerOverride: SourceEncoding?,
            selected: SourceEncoding,
        ): EncodingFacts {
            if (bomPolicy == BomPolicy.TreatAsContent && bom != null) {
                throw SourceException(
                    SourceErrorKind.ENCODING_CONFLICT,
                    bom = bom.encoding(),
                    declaration = declaration,
                    callerOverride = callerOverride,
                )
            }
            return resolveAssertions(
                EncodingRequest(profileDefault, bomPolicy, declaration, callerOverride),
                bom,
                selected,
            )
        }
    }

    internal fun resolutionRequest(): EncodingRequest =
        EncodingRequest(profileDefault, bomPolicy, declaration, callerOverride)
}

/**
 * Resource bounds applied while a source snapshot is constructed (RFC 0003
 * §12; source.rs:381-409). Limits apply before or during allocation; a limit
 * failure returns no partial snapshot (RFC 0003 §12).
 */
data class SourceLimits(
    /** Maximum retained raw bytes. */
    val maxRawBytes: Int,
    /** Maximum decoded UTF-8 bytes. */
    val maxDecodedUtf8Bytes: Int,
    /** Maximum decoded Unicode scalar values. */
    val maxDecodedScalars: Int,
) {
    companion object {
        /** Compatibility limits for already-bounded format parsers
         * (source.rs:392-399). */
        val UNBOUNDED = SourceLimits(
            maxRawBytes = Int.MAX_VALUE,
            maxDecodedUtf8Bytes = Int.MAX_VALUE,
            maxDecodedScalars = Int.MAX_VALUE,
        )

        /** The frozen defaults (source.rs:401-409): 64 MiB raw bytes,
         * 128 MiB decoded UTF-8 bytes, 64 MiB decoded scalars. */
        val default = SourceLimits(
            maxRawBytes = 64 shl 20,
            maxDecodedUtf8Bytes = 128 shl 20,
            maxDecodedScalars = 64 shl 20,
        )
    }
}

/**
 * Resolves the encoding facts for one byte buffer (source.rs:727-738).
 * BOM detection runs only when the selected policy is DetectUnicode and the
 * profile default or an explicit text fact asks for text.
 */
internal fun resolveEncoding(bytes: ByteArray, request: EncodingRequest): EncodingFacts {
    val hasExplicitText = request.declaration?.isText() == true ||
        request.callerOverride?.isText() == true
    val interpretBom = request.bomPolicy == BomPolicy.DetectUnicode &&
        (request.profileDefault.isText() || hasExplicitText)
    val bom = if (interpretBom) detectBom(bytes) else null
    return resolveAssertions(request, bom, request.callerOverride ?: request.declaration ?: bom?.encoding() ?: request.profileDefault)
}

/**
 * Applies the frozen resolution assertions (source.rs:740-782): any two
 * present BOM/declaration/caller facts that disagree produce
 * EncodingConflict; the resolver never guesses. A Binary profile default
 * with any explicit text fact is a conflict. The selected encoding is the
 * first present value in caller_override -> declaration -> bom ->
 * profile_default order.
 */
private fun resolveAssertions(
    request: EncodingRequest,
    bom: BomKind?,
    expectedSelected: SourceEncoding,
): EncodingFacts {
    if (request.profileDefault === SourceEncoding.Binary &&
        (request.declaration?.isText() == true || request.callerOverride?.isText() == true)
    ) {
        throw SourceException(
            SourceErrorKind.ENCODING_CONFLICT,
            bom = bom?.encoding(),
            declaration = request.declaration,
            callerOverride = request.callerOverride,
        )
    }
    val bomEncoding = bom?.encoding()
    val assertions = listOfNotNull(bomEncoding, request.declaration, request.callerOverride)
    val first = assertions.firstOrNull()
    if (first != null && assertions.any { it != first }) {
        throw SourceException(
            SourceErrorKind.ENCODING_CONFLICT,
            bom = bomEncoding,
            declaration = request.declaration,
            callerOverride = request.callerOverride,
        )
    }
    val selected = request.callerOverride ?: request.declaration ?: bomEncoding ?: request.profileDefault
    if (selected != expectedSelected) {
        throw SourceException(
            SourceErrorKind.ENCODING_CONFLICT,
            bom = bomEncoding,
            declaration = request.declaration,
            callerOverride = request.callerOverride,
        )
    }
    return EncodingFacts(
        profileDefault = request.profileDefault,
        bomPolicy = request.bomPolicy,
        bom = bom,
        declaration = request.declaration,
        callerOverride = request.callerOverride,
        selected = selected,
    )
}

/**
 * Detects a Unicode BOM from raw bytes (source.rs:784-804). UTF-32 BOMs are
 * explicitly unsupported in v1 (RFC 0003 §4.2) and fail with UnsupportedBom
 * before any other resolution step.
 */
private fun detectBom(bytes: ByteArray): BomKind? {
    if (bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
    ) {
        throw SourceException(SourceErrorKind.UNSUPPORTED_BOM, bomKind = UnsupportedBomKind.Utf32Le)
    }
    if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
        bytes[2] == 0xfe.toByte() && bytes[3] == 0xff.toByte()
    ) {
        throw SourceException(SourceErrorKind.UNSUPPORTED_BOM, bomKind = UnsupportedBomKind.Utf32Be)
    }
    return when {
        bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte() -> BomKind.Utf8

        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> BomKind.Utf16Le

        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> BomKind.Utf16Be

        else -> null
    }
}
