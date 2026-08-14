// The Properties source contract: explicit Reader encodings, InputStream
// Latin-1, published Windows code pages, and the decoded-text carrier.
//
// Data authority (language-neutral sources first):
//   - RFC 0010 §3 (https://github.com/consema/consema/blob/main/docs/rfcs/0010-java-properties-profiles-v1.md):
//     reader@1 operates on an explicitly decoded character source with the
//     charset chosen outside `load(Reader)`; latin1@1 maps every input byte
//     to the same-numbered ISO-8859-1 character with BOM bytes as ordinary
//     content (source-v2 BomPolicy::TreatAsContent); no locale or
//     platform-default guessing.
//   - conformance/vectors/java-properties-v1.json formation.reader-explicit-
//     encodings (lines 40-49) pins Reader UTF-8/UTF-16LE/UTF-16BE/
//     WindowsCodePage(1252) decoding and formation.latin1-byte-and-bom-
//     content (lines 50-54) pins Latin-1 BOM-as-content.
//   - https://github.com/consema/consema-rs/blob/main/consema-properties/src/parser.rs pins the encoding
//     request construction and the profile/encoding validation
//     (java-properties.source.profile-encoding@1); the published Windows
//     code-page registry is the Rust SourceEncoding::WindowsCodePage set
//     (https://github.com/consema/consema-rs/blob/main/consema-document/src/materialization.rs: 874, 932,
//     936, 949, 950, 1250-1258, 65001). consema-go/go/properties is a cross-reference
//     only.
//
// Kotlin-idiomatic design (NOT a translation): the source selection is a
// closed sealed class; Windows code pages decode through the JDK built-in
// charsets except 1252, whose 0x80-0x9F table is transcribed exactly from
// the WHATWG windows-1252 mapping (the encoding_rs WINDOWS_1252 table) so
// the pinned vector bytes stay deterministic. The decoded-text carrier
// [PropertiesText] maps raw bytes to the UTF-8-byte coordinates of the
// decoded view for both the snapshot-backed and code-page paths.

package consema.properties

import consema.document.BomPolicy
import consema.document.DecodedOffset
import consema.document.EncodingRequest
import consema.document.SourceEncoding
import consema.document.SourceLimits
import consema.document.SourceSnapshot
import consema.document.Span
import java.nio.charset.Charset

/**
 * Explicit source contract; no extension, locale, or platform default is
 * consulted (RFC 0010 §3; lib.rs). The profile is always selected by
 * the caller; a `.properties` extension never chooses between Reader and
 * Latin-1 semantics.
 */
sealed class PropertiesEncoding {
    /** Reader input decoded through this exact published text encoding. */
    data class Reader(val encoding: SourceEncoding) : PropertiesEncoding()

    /** InputStream-compatible one-byte ISO-8859-1 mapping with BOM bytes as
     * content. */
    data object Latin1 : PropertiesEncoding()

    /** Reader input decoded through one explicit published Windows code
     * page. */
    data class WindowsCodePage(val number: Int) : PropertiesEncoding()
}

/**
 * One published Windows code page of the portable registry (the Rust
 * WindowsCodePage registry; materialization.rs).
 */
data class WindowsCodePage(val number: Int) {
    companion object {
        /** The published registry (materialization.rs). */
        private val PUBLISHED = setOf(874, 932, 936, 949, 950, 1250, 1251, 1252, 1253, 1254, 1255, 1256, 1257, 1258, 65001)

        /** Resolves one published code page (the Rust from_number). */
        fun fromNumber(number: Int): WindowsCodePage? =
            if (number in PUBLISHED) WindowsCodePage(number) else null
    }

    init {
        require(number in PUBLISHED) { "Windows code page $number is not in the portable registry" }
    }

    /** The exact WHATWG windows-1252 decode of byte 0x80..0x9F (encoding_rs
     * WINDOWS_1252; all other bytes map to the same-numbered Latin-1
     * scalar). */
    internal fun decodeByte(byte: Int): Char {
        val scalar = if (byte in 0x80..0x9f) {
            CP1252_HIGH[byte - 0x80]
        } else {
            byte
        }
        return scalar.toChar()
    }

    /** The exact windows-1252 encode of one scalar, or null when the code
     * page cannot represent it. */
    internal fun encodeChar(ch: Char): Int? {
        val code = ch.code
        return when {
            code <= 0x7f || code in 0xa0..0xff -> code
            else -> CP1252_REVERSE[code] ?: return null
        }
    }

    /** The JDK charset for this code page (65001 is UTF-8). Code pages other
     * than 1252 use the JDK built-in charsets as the host approximation of
     * the encoding_rs tables; the pinned vectors exercise only 1252, which
     * uses the exact tables above. */
    internal fun charset(): Charset =
        when (number) {
            65001 -> Charsets.UTF_8
            874 -> Charset.forName("windows-874")
            932 -> Charset.forName("windows-932")
            936 -> Charset.forName("windows-936")
            949 -> Charset.forName("windows-949")
            950 -> Charset.forName("windows-950")
            1250 -> Charset.forName("windows-1250")
            1251 -> Charset.forName("windows-1251")
            1252 -> Charset.forName("windows-1252")
            1253 -> Charset.forName("windows-1253")
            1254 -> Charset.forName("windows-1254")
            1255 -> Charset.forName("windows-1255")
            1256 -> Charset.forName("windows-1256")
            1257 -> Charset.forName("windows-1257")
            1258 -> Charset.forName("windows-1258")
            else -> error("published code page")
        }
}

/** WHATWG windows-1252 decoding of bytes 0x80..0x9F (encoding_rs
 * WINDOWS_1252; undefined bytes map to the same-numbered C1 controls). */
private val CP1252_HIGH: IntArray = intArrayOf(
    0x20ac, 0x0081, 0x201a, 0x0192, 0x201e, 0x2026, 0x2020, 0x2021,
    0x02c6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008d, 0x017d, 0x008f,
    0x0090, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014,
    0x02dc, 0x2122, 0x0161, 0x203a, 0x0153, 0x009d, 0x017e, 0x0178,
)

/** Reverse windows-1252 table: scalar -> byte for the 0x80..0x9F range. */
private val CP1252_REVERSE: Map<Int, Int> = buildMap {
    for (index in CP1252_HIGH.indices) {
        put(CP1252_HIGH[index], 0x80 + index)
    }
}

/**
 * Decoded-text carrier shared by the parser, query, and edit surfaces.
 *
 * For the snapshot-backed paths (Reader UTF-8/UTF-16, Latin-1) the decoded
 * text and the raw<->decoded boundary mapping come from the SourceSnapshot;
 * for Windows code pages the text is decoded by this package (the document
 * layer's closed v1 encoding set has no code-page decoding) and every raw
 * byte maps to exactly one decoded scalar, so the boundary index is a
 * per-char table (RFC 0003 §5 boundary semantics, kotlin/src/main/kotlin/consema/document/
 * Source.kt).
 */
internal class PropertiesText(
    /** The decoded text view (validated exactly once at construction). */
    val text: String,
    private val rawToDecoded: (Int) -> Int,
    private val decodedToRaw: (Int) -> Int,
) {
    /** The exact UTF-8 representation of the decoded text; the decoded
     * offsets are byte offsets into this array (the Rust
     * decoded_span_text, query.rs). */
    private val decodedUtf8 = text.toByteArray(Charsets.UTF_8)

    /** Decoded UTF-8 byte offset -> decoded char index (one entry per
     * scalar boundary, plus the terminal). */
    private val byteToChar: IntArray = run {
        val map = IntArray(decodedUtf8.size + 1)
        var byte = 0
        for ((charIndex, character) in text.withIndex()) {
            map[byte] = charIndex
            byte += utf8Length(character.code)
        }
        map[byte] = text.length
        map
    }
    /** Decoded UTF-8 byte offset of one raw-byte boundary. */
    fun decodedUtf8At(rawByte: Int): Int = rawToDecoded(rawByte)

    /** Raw byte offset of one decoded UTF-8 byte boundary. */
    fun rawByteAt(decodedUtf8Byte: Int): Int = decodedToRaw(decodedUtf8Byte)

    /** Decoded text of one raw span (the Rust decoded_span_text,
     * query.rs). */
    fun spanText(span: Span): String {
        val start = rawToDecoded(span.startByte)
        val end = rawToDecoded(span.endByte)
        return String(decodedUtf8, start, end - start, Charsets.UTF_8)
    }

    /** Whether the decoded text up to one raw boundary ends with a line
     * terminator (the Rust is_line_boundary, edit.rs). */
    fun endsWithLineBreak(rawByte: Int): Boolean {
        val end = byteToChar[rawToDecoded(rawByte)]
        return end > 0 && (text[end - 1] == '\r' || text[end - 1] == '\n')
    }

    companion object {
        /** The snapshot-backed carrier (parser.rs). */
        fun ofSnapshot(source: SourceSnapshot): PropertiesText {
            val text = source.decodedText()
                ?: error("Properties source profiles always select text decoding")
            return PropertiesText(
                text = text,
                rawToDecoded = { raw -> source.decodedPosition(raw).decodedUtf8Byte },
                decodedToRaw = { decoded -> source.rawByteAt(DecodedOffset.Utf8Byte(decoded)) },
            )
        }

        /** The code-page carrier: one raw byte per decoded scalar. */
        fun ofCodePageText(text: String): PropertiesText {
            val utf8Starts = IntArray(text.length)
            val utf8ToChar = HashMap<Int, Int>()
            var utf8 = 0
            for (index in text.indices) {
                utf8Starts[index] = utf8
                utf8ToChar[utf8] = index
                utf8 += utf8Length(text[index].code)
            }
            utf8ToChar[utf8] = text.length
            val totalUtf8 = utf8
            return PropertiesText(
                text = text,
                rawToDecoded = { raw ->
                    if (raw == utf8Starts.size) totalUtf8 else utf8Starts[raw]
                },
                decodedToRaw = { decoded ->
                    utf8ToChar[decoded] ?: error("code-page boundary is always addressable")
                },
            )
        }
    }
}

/** UTF-8 byte length of one scalar (the Rust char::len_utf8). */
internal fun utf8Length(scalar: Int): Int =
    when {
        scalar < 0x80 -> 1
        scalar < 0x800 -> 2
        scalar < 0x10000 -> 3
        else -> 4
    }

/**
 * Constructs the bounded SourceSnapshot for one exact profile/selection
 * (parser.rs). The selection is validated against the profile FIRST
 * (java-properties.source.profile-encoding@1, parser.rs); source
 * construction failures map through the frozen core.source.* codes
 * (FatalFormationFailure::source_error, source_v1.rs).
 */
internal fun buildPropertiesSource(
    bytes: ByteArray,
    profile: PropertiesProfile,
    encoding: PropertiesEncoding,
    limits: PropertiesParseLimits,
): Pair<SourceSnapshot, PropertiesText> {
    validateSelection(profile, encoding)
    val sourceLimits = SourceLimits(
        maxRawBytes = limits.common.maxSourceBytes,
        maxDecodedUtf8Bytes = limits.maxDecodedUtf8Bytes,
        maxDecodedScalars = limits.maxDecodedScalars,
    )
    return when (encoding) {
        is PropertiesEncoding.Reader -> {
            val request = EncodingRequest.new(encoding.encoding)
                .withCallerOverride(encoding.encoding)
            val source = constructSource(bytes, request, sourceLimits)
            source to PropertiesText.ofSnapshot(source)
        }
        PropertiesEncoding.Latin1 -> {
            val request = EncodingRequest.new(SourceEncoding.Latin1)
                .withCallerOverride(SourceEncoding.Latin1)
                .withBomPolicy(BomPolicy.TreatAsContent)
            val source = constructSource(bytes, request, sourceLimits)
            source to PropertiesText.ofSnapshot(source)
        }
        is PropertiesEncoding.WindowsCodePage -> {
            // The published registry check (the Rust WindowsCodePage
            // from_number; error_registry.rs registers
            // core.source.unsupported-code-page@1).
            if (WindowsCodePage.fromNumber(encoding.number) == null) {
                throw PropertiesFormationException(
                    "core.source.unsupported-code-page@1",
                    "properties: Windows code page ${encoding.number} is not in the portable registry",
                )
            }
            // The Reader source contract keeps the frozen DetectUnicode BOM
            // rule (encoding_request, parser.rs): marker-shaped
            // prefixes are BOM evidence and conflict with the code page
            // before any decoding (source.rs, resolveEncoding).
            rejectUnicodeBomEvidence(bytes)
            val text = decodeCodePage(bytes, encoding.number)
            // A code page is a byte-per-scalar text encoding; the snapshot
            // carries the exact raw bytes under the v1 Latin-1 mapping so
            // render/digest/spans stay authoritative while this package's
            // decoded view is the code-page text.
            val request = EncodingRequest.new(SourceEncoding.Latin1)
                .withCallerOverride(SourceEncoding.Latin1)
                .withBomPolicy(BomPolicy.TreatAsContent)
            val source = constructSource(bytes, request, sourceLimits)
            source to PropertiesText.ofCodePageText(text)
        }
    }
}

/** The frozen profile/encoding compatibility (parser.rs). */
private fun validateSelection(
    profile: PropertiesProfile,
    encoding: PropertiesEncoding,
) {
    val valid = when (encoding) {
        is PropertiesEncoding.Reader ->
            profile == PropertiesProfile.ReaderV1 && encoding.encoding != SourceEncoding.Binary
        PropertiesEncoding.Latin1 -> profile == PropertiesProfile.Latin1V1
        is PropertiesEncoding.WindowsCodePage -> profile == PropertiesProfile.ReaderV1
    }
    if (!valid) {
        throw PropertiesFormationException(
            "java-properties.source.profile-encoding@1",
            "properties: profile and encoding selection conflict",
        )
    }
}

/** Wraps a source construction failure with the frozen code mapping of
 * FatalFormationFailure::source_error (parser.rs; source_v1.rs
 *). */
private fun constructSource(
    bytes: ByteArray,
    request: EncodingRequest,
    limits: SourceLimits,
): SourceSnapshot =
    try {
        SourceSnapshot.fromRaw(bytes, request, limits)
    } catch (error: consema.document.SourceException) {
        when (error.kind) {
            consema.document.SourceErrorKind.INVALID_UTF8, consema.document.SourceErrorKind.INVALID_SEQUENCE ->
                throw PropertiesFormationException(
                    "core.source.invalid-sequence@1",
                    "properties: invalid source sequence",
                    cause = error,
                )

            consema.document.SourceErrorKind.ENCODING_CONFLICT ->
                throw PropertiesFormationException(
                    "core.source.encoding-conflict@1",
                    "properties: source encoding facts conflict",
                    cause = error,
                )

            consema.document.SourceErrorKind.UNSUPPORTED_BOM ->
                throw PropertiesFormationException(
                    "core.source.unsupported-bom@1",
                    "properties: unsupported byte-order mark",
                    cause = error,
                )

            consema.document.SourceErrorKind.RESOURCE_LIMIT, consema.document.SourceErrorKind.OFFSET_OVERFLOW ->
                throw PropertiesFormationException(
                    "core.source.resource-limit@1",
                    "properties: source construction limit reached",
                    name = error.name ?: "",
                    observed = error.observed,
                    limit = error.limit,
                    cause = error,
                )
        }
    }

/** Decodes one Windows code page (1252 uses the exact WHATWG table; the
 * other published pages use the JDK charsets). */
internal fun decodeCodePage(bytes: ByteArray, number: Int): String {
    if (number == 1252) {
        val text = CharArray(bytes.size)
        for (i in bytes.indices) {
            text[i] = WindowsCodePage(1252).decodeByte(bytes[i].toInt() and 0xff)
        }
        return String(text)
    }
    return String(bytes, WindowsCodePage(number).charset())
}

/** The frozen DetectUnicode BOM rule for Reader code-page sources
 * (source.rs, detectBom; resolveAssertions, source.rs). */
private fun rejectUnicodeBomEvidence(bytes: ByteArray) {
    if (bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
    ) {
        throw PropertiesFormationException(
            "core.source.unsupported-bom@1",
            "properties: unsupported UTF-32LE byte-order mark",
        )
    }
    if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
        bytes[2] == 0xfe.toByte() && bytes[3] == 0xff.toByte()
    ) {
        throw PropertiesFormationException(
            "core.source.unsupported-bom@1",
            "properties: unsupported UTF-32BE byte-order mark",
        )
    }
    val bom = bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() &&
        bytes[2] == 0xbf.toByte() ||
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ||
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()
    if (bom) {
        throw PropertiesFormationException(
            "core.source.encoding-conflict@1",
            "properties: byte-order mark conflicts with the selected code page",
        )
    }
}
