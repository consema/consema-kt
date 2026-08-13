// The INI family's immutable source facts: exact raw bytes, explicit
// encoding resolution, strict decoding (including Windows code pages), and
// exact decoded-boundary mapping.
//
// Data authority (language-neutral sources first):
//   - RFC 0003 §3-§6 (https://github.com/consema/consema/blob/main/docs/rfcs/0003-source-syntax-query-and-patch-v1.md:45-
//     148): content digest over exact raw bytes; the closed v1 encoding set;
//     the decoded boundary tuple (raw_byte, decoded_utf8_byte,
//     unicode_scalar_offset, utf16_code_unit_offset); only scalar boundaries
//     are addressable; BOM bytes remain part of the raw source and digest
//     and are retained as leading U+FEFF in the decoded view.
//   - RFC 0009 §3 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md:68-116):
//     portable accepts UTF-8 without BOM over the ASCII horizontal tab /
//     printable subset; windows accepts UTF-16LE with an initial BOM or an
//     explicitly selected Windows code page (BomPolicy::TreatAsContent,
//     invalid byte sequences rejected, the chosen code page and BOM facts
//     observable); python accepts any complete text source when the caller
//     or a BOM selected the encoding unambiguously.
//   - consema-rs/consema-ini/src/parser.rs:37-104 pins encoding_request and
//     validate_profile_encoding (the ini.profile.encoding@1 failure);
//     consema-rs/consema-document/src/source.rs:1016-1067 pins the checkpointed
//     boundary index and the per-scalar RawBoundaryStep array used for
//     variable-width code pages (source.rs:966-1014).
//   - The JDK charset tables approximate the encoding_rs tables the Rust
//     decoder pins (consema-rs/consema-ini/src/materialization.rs:831-850):
//     874/1250-1258 -> windows-874/windows-125x, 932 -> windows-31j,
//     936 -> GBK, 949 -> EUC-KR, 950 -> Big5, 65001 -> UTF-8. DBCS edge
//     mappings need differential verification (盲写纪律: no gates claimed).
//
// Kotlin-idiomatic design: decoding happens in one pass over raw bytes and
// produces the decoded text plus the per-scalar raw widths needed for exact
// span mapping (the Kotlin analogue of the Rust RawBoundaryStep array);
// v1-representable encodings additionally carry a consema.document
// SourceSnapshot so the edit layer can derive SourcePatch and
// UntouchedByteProof through the shared document contract.

package consema.ini

import consema.document.BomKind
import consema.document.BomPolicy
import consema.document.ContentDigest
import consema.document.DecodedOffset
import consema.document.DecodedPosition
import consema.document.EncodingRequest
import consema.document.LocationErrorKind
import consema.document.LocationException
import consema.document.SourceEncoding
import consema.document.SourceErrorKind
import consema.document.SourceLimits
import consema.document.SourceSnapshot
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/** Checkpoint stride of the decoded boundary index (source.rs:13). */
private const val CHECKPOINT_STRIDE = 256

/**
 * Stable INI source-construction failure (the source-error surface of
 * FatalFormationFailure, source.rs:669-725). The [kind] reuses the document
 * package's closed kind set so the code mapping stays byte-identical
 * (kotlin/.../document/Source.kt:44-67).
 */
class IniSourceException(
    val kind: SourceErrorKind,
    message: String? = null,
    /** Selected encoding of an invalid sequence. */
    val encoding: IniSourceEncoding? = null,
    /** First byte at which a valid sequence could not be formed. */
    val byteOffset: Int? = null,
    /** Stable unsupported marker identifier (UNSUPPORTED_BOM). */
    val bomKind: consema.document.UnsupportedBomKind? = null,
    /** Stable limit name (RESOURCE_LIMIT). */
    val name: String? = null,
    /** Observed amount (RESOURCE_LIMIT). */
    val observed: Int? = null,
    /** Configured maximum (RESOURCE_LIMIT). */
    val limit: Int? = null,
) : Exception(message ?: "ini source: ${kind.code}") {
    /** The frozen registered code of the failure. */
    val code: String
        get() = kind.code
}

/** Complete, auditable result of the INI encoding resolution (RFC 0009 §3;
 * parser.rs:37-94). The v1 spellings the vectors assert are
 * [IniEncodingFacts.selected].asStr() ("Utf16Le", "WindowsCodePage(1252)")
 * and [bomPolicy].name ("TreatAsContent"). */
data class IniEncodingFacts(
    /** Profile fallback that participated in resolution. */
    val profileDefault: IniSourceEncoding,
    /** BOM interpretation policy used for this source. */
    val bomPolicy: BomPolicy,
    /** Recognized Unicode byte-order mark. */
    val bom: BomKind?,
    /** Explicit caller override. */
    val callerOverride: IniSourceEncoding?,
    /** Encoding selected by the frozen priority rule. */
    val selected: IniSourceEncoding,
)

/** Caller inputs to the INI encoding resolution (parser.rs:37-59). The
 * selected encoding is the first present value in caller_override -> bom ->
 * profile_default order (RFC 0003 §4.2). */
internal class IniEncodingRequest(
    /** Profile fallback (always Utf8; parser.rs:48). */
    val profileDefault: IniSourceEncoding,
    /** BOM interpretation policy; TreatAsContent for code pages. */
    val bomPolicy: BomPolicy,
    /** Explicit caller choice. */
    val callerOverride: IniSourceEncoding?,
) {
    companion object {
        /** Starts with the frozen profile default (parser.rs:48). */
        fun new(): IniEncodingRequest =
            IniEncodingRequest(IniSourceEncoding.Utf8, BomPolicy.DetectUnicode, null)

        /** Adds an explicit caller override (parser.rs:49-51). */
        fun withCallerOverride(override: IniSourceEncoding): IniEncodingRequest =
            IniEncodingRequest(IniSourceEncoding.Utf8, BomPolicy.DetectUnicode, override)

        /** Selects whether marker-shaped leading bytes are BOM evidence or
         * content (parser.rs:52-54). */
        fun withBomPolicy(policy: BomPolicy): IniEncodingRequest =
            IniEncodingRequest(IniSourceEncoding.Utf8, policy, null)
    }
}

/**
 * Immutable ownership of exact raw bytes plus explicitly derived text facts
 * for one INI snapshot (RFC 0003 §3-§6; RFC 0009 §3). The decoder recomputes
 * the digest, reruns encoding resolution and decoding, and requires exact
 * equality with all encoded facts.
 */
class IniSource private constructor(
    private val raw: ByteArray,
    /** Stable SHA-256 identity of exact retained bytes. */
    val digest: ContentDigest,
    /** Complete encoding-resolution facts. */
    val encodingFacts: IniEncodingFacts,
    private val decoded: String,
    private val decodedUtf8: ByteArray,
    private val index: BoundaryIndex,
    /** The document-contract snapshot when the selected encoding is in the
     * v1 set (source-v1 encodings); null for Windows code pages, which the
     * document v2 extension will add (kotlin/.../document/Encoding.kt:18-25). */
    internal val v1Snapshot: SourceSnapshot?,
) {
    companion object {
        /**
         * Constructs an INI source from raw bytes under one explicit
         * resolution and limits (parser.rs:21-35). Throws [IniSourceException]
         * on a limit, encoding-conflict, unsupported-BOM, or invalid-sequence
         * failure; no partial source is returned.
         */
        internal fun fromRaw(
            bytes: ByteArray,
            request: IniEncodingRequest,
            limits: SourceLimits,
        ): IniSource {
            checkLimit("raw-bytes", bytes.size, limits.maxRawBytes)
            val facts = resolveEncoding(bytes, request)
            val digest = ContentDigest.of(bytes)
            val decodedText: String
            val widths: ByteArray
            when (facts.selected) {
                IniSourceEncoding.Utf8 -> {
                    val validUpTo = utf8ValidUpTo(bytes)
                    if (validUpTo != bytes.size) {
                        throw IniSourceException(
                            SourceErrorKind.INVALID_SEQUENCE,
                            "ini source: invalid UTF-8 at byte $validUpTo",
                            encoding = IniSourceEncoding.Utf8,
                            byteOffset = validUpTo,
                        )
                    }
                    decodedText = String(bytes, Charsets.UTF_8)
                    widths = ByteArray(0)
                }
                IniSourceEncoding.Utf16Le, IniSourceEncoding.Utf16Be -> {
                    val decoded = decodeUtf16(bytes, facts.selected, limits)
                    decodedText = decoded.first
                    widths = decoded.second
                }
                IniSourceEncoding.Latin1 -> {
                    decodedText = decodeLatin1(bytes, limits)
                    widths = ByteArray(0)
                }
                is IniSourceEncoding.WindowsCodePage -> {
                    val decoded = decodeCodePage(bytes, facts.selected, limits)
                    decodedText = decoded.first
                    widths = decoded.second
                }
            }
            val decodedUtf8 = decodedText.toByteArray(Charsets.UTF_8)
            checkLimit("decoded-utf8-bytes", decodedUtf8.size, limits.maxDecodedUtf8Bytes)
            val index = buildIndex(decodedText, facts.selected, widths, bytes.size, limits)
            val v1Snapshot = documentSnapshot(bytes, facts)
            return IniSource(bytes.copyOf(), digest, facts, decodedText, decodedUtf8, index, v1Snapshot)
        }
    }

    /** Exact retained source bytes; returns a defensive copy. */
    fun bytes(): ByteArray = raw.copyOf()

    /** Exact retained source bytes for document-internal use (no copy). */
    internal fun rawBytes(): ByteArray = raw

    /** Source byte length. */
    val len: Int
        get() = raw.size

    /** Whether the source is empty. */
    val isEmpty: Boolean
        get() = raw.isEmpty()

    /**
     * Decoded text. The original BOM bytes remain part of the raw source
     * and digest; in the decoded view a recognized text BOM is retained as
     * leading U+FEFF (RFC 0003 §4.3).
     */
    fun decodedText(): String = decoded

    /**
     * Resolves one raw byte offset only when it is a decoded scalar boundary
     * (RFC 0003 §5). Throws [LocationException]: OutOfBounds or
     * NotDecodedBoundary.
     */
    fun decodedPosition(rawByte: Int): DecodedPosition {
        if (rawByte < 0 || rawByte > raw.size) {
            throw LocationException(LocationErrorKind.OutOfBounds)
        }
        val checkpoint = lastCheckpoint { it.rawByte <= rawByte }
        return scanToRaw(checkpoint, rawByte)
    }

    /**
     * Resolves one decoded offset only when it denotes a scalar boundary
     * (RFC 0003 §5). Throws [LocationException]: OutOfBounds or
     * DecodedOffsetNotBoundary.
     */
    fun rawByteAt(offset: DecodedOffset): Int {
        val requested = offset.requestValue
        if (requested < 0 || requested > offset.component(index.terminal)) {
            throw LocationException(LocationErrorKind.OutOfBounds)
        }
        val checkpoint = lastCheckpoint { offset.component(it) <= requested }
        return scanToDecoded(checkpoint, offset)
    }

    /** Exact decoded scalar text of one raw span whose bounds are decoded
     * boundaries (RFC 0009 §9: syntax text comparisons use the decoded
     * Unicode scalar text, not raw encoding bytes). */
    fun decodedTextBetween(startRaw: Int, endRaw: Int): String {
        val start = decodedPosition(startRaw).decodedUtf8Byte
        val end = decodedPosition(endRaw).decodedUtf8Byte
        return String(decodedUtf8, start, end - start, Charsets.UTF_8)
    }

    /** Encoded UTF-8 representation of the decoded text (RFC 0003 §5). */
    fun decodedUtf8Bytes(): ByteArray = decodedUtf8

    /** Last checkpoint satisfying the predicate (source.rs:1082-1088). */
    private fun lastCheckpoint(predicate: (DecodedPosition) -> Boolean): DecodedPosition {
        val checkpoints = index.checkpoints
        var low = 0
        var high = checkpoints.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (predicate(checkpoints[middle])) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return checkpoints[(low - 1).coerceAtLeast(0)]
    }

    /** Scans scalars from one checkpoint to an exact raw byte boundary
     * (source.rs:1090-1116). */
    private fun scanToRaw(start: DecodedPosition, requested: Int): DecodedPosition {
        if (start.rawByte == requested) {
            return start
        }
        var position = start
        var scalarIndex = start.unicodeScalarOffset
        var byteIndex = start.decodedUtf8Byte
        while (byteIndex < decodedUtf8.size) {
            val scalar = readUtf8Scalar(decodedUtf8, byteIndex)
            val width = rawStepWidth(encodingFacts.selected, widthsAt(scalarIndex), scalar)
            position = advance(position, scalar, width)
            byteIndex += utf8Length(scalar)
            scalarIndex += 1
            if (position.rawByte == requested) {
                return position
            }
            if (position.rawByte > requested) {
                throw LocationException(LocationErrorKind.NotDecodedBoundary)
            }
        }
        throw LocationException(LocationErrorKind.OutOfBounds)
    }

    /** Scans scalars from one checkpoint to an exact decoded boundary
     * (source.rs:1118-1150). */
    private fun scanToDecoded(start: DecodedPosition, requested: DecodedOffset): Int {
        val target = requested.requestValue
        if (requested.component(start) == target) {
            return start.rawByte
        }
        var position = start
        var scalarIndex = start.unicodeScalarOffset
        var byteIndex = start.decodedUtf8Byte
        while (byteIndex < decodedUtf8.size) {
            val scalar = readUtf8Scalar(decodedUtf8, byteIndex)
            val width = rawStepWidth(encodingFacts.selected, widthsAt(scalarIndex), scalar)
            position = advance(position, scalar, width)
            byteIndex += utf8Length(scalar)
            scalarIndex += 1
            val observed = requested.component(position)
            if (observed == target) {
                return position.rawByte
            }
            if (observed > target) {
                throw LocationException(LocationErrorKind.DecodedOffsetNotBoundary)
            }
        }
        throw LocationException(LocationErrorKind.OutOfBounds)
    }

    /** Per-scalar raw width for the current scalar (the Rust
     * RawBoundaryStep array, source.rs:966-1014; empty for fixed-width
     * encodings). */
    private fun widthsAt(scalarIndex: Int): Int {
        val widths = index.widths
        return if (widths.isEmpty()) -1 else widths[scalarIndex].toInt()
    }

    override fun equals(other: Any?): Boolean =
        other is IniSource &&
            raw.contentEquals(other.raw) &&
            encodingFacts == other.encodingFacts &&
            decoded == other.decoded

    override fun hashCode(): Int {
        var result = raw.contentHashCode()
        result = 31 * result + encodingFacts.hashCode()
        result = 31 * result + decoded.hashCode()
        return result
    }

    override fun toString(): String =
        "IniSource(len=${raw.size}, digest=${digest.toHex()}, selected=${encodingFacts.selected.asStr()})"
}

/** Immutable decoded boundary index: checkpoints every 256 scalars plus the
 * terminal position, and the per-scalar raw widths of variable-width code
 * pages (source.rs:1016-1067). */
private class BoundaryIndex(
    val checkpoints: List<DecodedPosition>,
    val terminal: DecodedPosition,
    /** Per-scalar raw widths; empty for fixed-width encodings. */
    val widths: ByteArray,
)

/**
 * Applies the frozen resolution rule (parser.rs:37-59; RFC 0003 §4.2):
 * caller_override -> bom -> profile_default; any two present disagreeing
 * facts produce EncodingConflict; BOM detection runs only under
 * DetectUnicode when the profile default or an explicit fact asks for text.
 */
private fun resolveEncoding(bytes: ByteArray, request: IniEncodingRequest): IniEncodingFacts {
    // The INI profile default is always UTF-8 text (parser.rs:48) and the
    // encoding vocabulary has no Binary member (the parser.rs:45-47 Binary
    // rejection is structural), so BOM detection runs under DetectUnicode.
    val bom = if (request.bomPolicy == BomPolicy.DetectUnicode) detectBom(bytes) else null
    val bomEncoding = bom?.let { bomEncodingOf(it) }
    val assertions = listOfNotNull(bomEncoding, request.callerOverride)
    val first = assertions.firstOrNull()
    if (first != null && assertions.any { it != first }) {
        throw IniSourceException(
            SourceErrorKind.ENCODING_CONFLICT,
            "ini source: BOM and caller encoding facts conflict",
            bomKind = null,
            encoding = request.callerOverride,
        )
    }
    val selected = request.callerOverride ?: bomEncoding ?: request.profileDefault
    return IniEncodingFacts(
        profileDefault = request.profileDefault,
        bomPolicy = request.bomPolicy,
        bom = bom,
        callerOverride = request.callerOverride,
        selected = selected,
    )
}

private fun bomEncodingOf(kind: BomKind): IniSourceEncoding =
    when (kind) {
        BomKind.Utf8 -> IniSourceEncoding.Utf8
        BomKind.Utf16Le -> IniSourceEncoding.Utf16Le
        BomKind.Utf16Be -> IniSourceEncoding.Utf16Be
    }

/**
 * Detects a Unicode BOM from raw bytes (RFC 0003 §4.2). UTF-32 BOMs are
 * explicitly unsupported and fail with UnsupportedBom before any other
 * resolution step.
 */
private fun detectBom(bytes: ByteArray): BomKind? {
    if (bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
    ) {
        throw IniSourceException(
            SourceErrorKind.UNSUPPORTED_BOM,
            bomKind = consema.document.UnsupportedBomKind.Utf32Le,
        )
    }
    if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
        bytes[2] == 0xfe.toByte() && bytes[3] == 0xff.toByte()
    ) {
        throw IniSourceException(
            SourceErrorKind.UNSUPPORTED_BOM,
            bomKind = consema.document.UnsupportedBomKind.Utf32Be,
        )
    }
    return when {
        bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte() -> BomKind.Utf8

        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> BomKind.Utf16Le

        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> BomKind.Utf16Be

        else -> null
    }
}

/**
 * Decodes UTF-16 (LE or BE) with strict surrogate validation. Returns the
 * decoded text and the per-scalar raw widths (2 or 4 bytes per scalar).
 */
private fun decodeUtf16(
    bytes: ByteArray,
    encoding: IniSourceEncoding,
    limits: SourceLimits,
): Pair<String, ByteArray> {
    if (bytes.size % 2 != 0) {
        throw IniSourceException(
            SourceErrorKind.INVALID_SEQUENCE,
            "ini source: odd-length UTF-16",
            encoding = encoding,
            byteOffset = bytes.size - 1,
        )
    }
    val littleEndian = encoding === IniSourceEncoding.Utf16Le
    val output = StringBuilder(bytes.size / 2)
    val widths = ByteArray(bytes.size / 2)
    var offset = 0
    var scalars = 0
    var decodedBytes = 0
    while (offset < bytes.size) {
        val first = readU16(bytes, offset, littleEndian)
        val scalar: Int
        val consumed: Int
        if (first in 0xd800..0xdbff) {
            if (offset + 3 >= bytes.size) {
                throw IniSourceException(
                    SourceErrorKind.INVALID_SEQUENCE,
                    "ini source: truncated UTF-16 surrogate pair at byte $offset",
                    encoding = encoding,
                    byteOffset = offset,
                )
            }
            val second = readU16(bytes, offset + 2, littleEndian)
            if (second !in 0xdc00..0xdfff) {
                throw IniSourceException(
                    SourceErrorKind.INVALID_SEQUENCE,
                    "ini source: isolated UTF-16 high surrogate at byte $offset",
                    encoding = encoding,
                    byteOffset = offset,
                )
            }
            scalar = 0x1_0000 + ((first - 0xd800) shl 10) + (second - 0xdc00)
            consumed = 4
        } else if (first in 0xdc00..0xdfff) {
            throw IniSourceException(
                SourceErrorKind.INVALID_SEQUENCE,
                "ini source: isolated UTF-16 low surrogate at byte $offset",
                encoding = encoding,
                byteOffset = offset,
            )
        } else {
            scalar = first
            consumed = 2
        }
        widths[scalars] = consumed.toByte()
        scalars = checkedAdd(scalars, 1)
        checkLimit("decoded-scalars", scalars, limits.maxDecodedScalars)
        decodedBytes = checkedAdd(decodedBytes, utf8Length(scalar))
        checkLimit("decoded-utf8-bytes", decodedBytes, limits.maxDecodedUtf8Bytes)
        output.appendCodePoint(scalar)
        offset += consumed
    }
    return output.toString() to widths
}

/** Decodes ISO-8859-1 bytes to scalars U+0000..U+00FF; one raw byte per
 * scalar. */
private fun decodeLatin1(bytes: ByteArray, limits: SourceLimits): String {
    checkLimit("decoded-scalars", bytes.size, limits.maxDecodedScalars)
    val output = StringBuilder(bytes.size)
    var decodedBytes = 0
    for (byte in bytes) {
        val scalar = byte.toInt() and 0xff
        decodedBytes = checkedAdd(decodedBytes, utf8Length(scalar))
        checkLimit("decoded-utf8-bytes", decodedBytes, limits.maxDecodedUtf8Bytes)
        output.appendCodePoint(scalar)
    }
    return output.toString()
}

/**
 * Decodes one Windows code page with strict rejection of invalid sequences
 * (RFC 0009 §3.2: "Invalid byte sequences are rejected rather than
 * replaced"). Returns the decoded text and the per-scalar raw widths; the
 * widths are tracked incrementally because DBCS pages (932, 936, 949, 950)
 * consume one or two input bytes per scalar (source.rs:966-1014).
 */
private fun decodeCodePage(
    bytes: ByteArray,
    encoding: IniSourceEncoding.WindowsCodePage,
    limits: SourceLimits,
): Pair<String, ByteArray> {
    val charset = codePageCharset(encoding.codePage.number)
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = ByteBuffer.wrap(bytes)
    // One output char per call so every produced scalar receives the exact
    // input bytes consumed by the decoder (surrogate pairs span two calls).
    val scratch = CharBuffer.allocate(1)
    val output = StringBuilder(bytes.size)
    val widths = ByteArray(bytes.size)
    var pendingWidth = 0
    var widthCount = 0
    var pendingHigh: Char? = null
    var decodedBytes = 0
    var scalars = 0
    while (true) {
        val before = input.position()
        scratch.clear()
        val result = try {
            decoder.decode(input, scratch, false)
        } catch (e: CharacterCodingException) {
            throw IniSourceException(
                SourceErrorKind.INVALID_SEQUENCE,
                "ini source: invalid ${charset.name()} sequence",
                encoding = encoding,
                byteOffset = input.position().coerceAtMost(bytes.size - 1),
            )
        }
        pendingWidth += input.position() - before
        if (!scratch.hasRemaining()) {
            // A char was produced into the cleared single-char scratch; flip
            // it for reading (hasRemaining() is false exactly when the
            // buffer is full).
            scratch.flip()
            val unit = scratch.get()
            val high = pendingHigh
            if (high != null) {
                // The low half of a surrogate pair completes the scalar
                // begun by the previous call; the whole pair owns the
                // accumulated input width.
                output.append(high).append(unit)
                widths[widthCount] = pendingWidth.toByte()
                widthCount += 1
                scalars += 1
                checkLimit("decoded-scalars", scalars, limits.maxDecodedScalars)
                decodedBytes = checkedAdd(decodedBytes, 4)
                checkLimit("decoded-utf8-bytes", decodedBytes, limits.maxDecodedUtf8Bytes)
                pendingHigh = null
                pendingWidth = 0
            } else if (Character.isHighSurrogate(unit)) {
                // The decoder keeps the trailing half of the pair in its
                // input; the pending width is attributed when the pair
                // completes.
                pendingHigh = unit
            } else if (Character.isLowSurrogate(unit)) {
                // The REPORT decoder cannot emit an unpaired low surrogate.
                throw IniSourceException(
                    SourceErrorKind.INVALID_SEQUENCE,
                    "ini source: unpaired low surrogate in ${charset.name()}",
                    encoding = encoding,
                    byteOffset = input.position().coerceAtMost(bytes.size - 1),
                )
            } else {
                output.append(unit)
                widths[widthCount] = pendingWidth.toByte()
                widthCount += 1
                scalars += 1
                checkLimit("decoded-scalars", scalars, limits.maxDecodedScalars)
                decodedBytes = checkedAdd(decodedBytes, utf8Length(unit.code))
                checkLimit("decoded-utf8-bytes", decodedBytes, limits.maxDecodedUtf8Bytes)
                pendingWidth = 0
            }
        }
        if (result.isOverflow) {
            continue
        }
        if (result.isUnderflow && input.hasRemaining()) {
            // The decoder buffered a partial multi-byte sequence; more input
            // will complete it.
            continue
        }
        break
    }
    // Flush the decoder; a trailing incomplete sequence is malformed.
    scratch.clear()
    try {
        decoder.decode(input, scratch, true)
    } catch (e: CharacterCodingException) {
        throw IniSourceException(
            SourceErrorKind.INVALID_SEQUENCE,
            "ini source: truncated ${charset.name()} sequence",
            encoding = encoding,
            byteOffset = bytes.size,
        )
    }
    if (pendingHigh != null) {
        throw IniSourceException(
            SourceErrorKind.INVALID_SEQUENCE,
            "ini source: truncated surrogate pair in ${charset.name()}",
            encoding = encoding,
            byteOffset = bytes.size,
        )
    }
    return output.toString() to widths.copyOf(widthCount)
}

/** The JDK charset approximating one published Windows code page
 * (materialization.rs:831-850). */
private fun codePageCharset(number: Int): Charset =
    when (number) {
        874 -> Charset.forName("x-windows-874")
        932 -> Charset.forName("windows-31j")
        936 -> Charset.forName("GBK")
        949 -> Charset.forName("EUC-KR")
        950 -> Charset.forName("Big5")
        65001 -> Charsets.UTF_8
        in 1250..1258 -> Charset.forName("windows-$number")
        else -> throw IllegalStateException("IniWindowsCodePage rejects unpublished values")
    }

/** Builds the checkpointed decoded boundary index (source.rs:1016-1067). */
private fun buildIndex(
    text: String,
    encoding: IniSourceEncoding,
    widths: ByteArray,
    rawLen: Int,
    limits: SourceLimits,
): BoundaryIndex {
    var current = DecodedPosition(0, 0, 0, 0)
    val checkpoints = ArrayList<DecodedPosition>()
    checkpoints.add(current)
    var scalarIndex = 0
    var charIndex = 0
    while (charIndex < text.length) {
        val scalar = text.codePointAt(charIndex)
        val width = if (widths.isEmpty()) {
            rawStepWidth(encoding, -1, scalar)
        } else {
            widths[scalarIndex].toInt()
        }
        current = advance(current, scalar, width)
        checkLimit("decoded-scalars", current.unicodeScalarOffset, limits.maxDecodedScalars)
        if (current.unicodeScalarOffset % CHECKPOINT_STRIDE == 0) {
            checkpoints.add(current)
        }
        charIndex += Character.charCount(scalar)
        scalarIndex += 1
    }
    if (checkpoints.last() != current) {
        checkpoints.add(current)
    }
    if (current.rawByte != rawLen) {
        throw IniSourceException(
            SourceErrorKind.INVALID_SEQUENCE,
            "ini source: decoded boundary does not cover the raw source",
        )
    }
    return BoundaryIndex(checkpoints, current, widths)
}

/** Raw byte advance of one scalar under the selected encoding
 * (source.rs:1152-1181; widths carry the variable code-page steps). */
private fun rawStepWidth(encoding: IniSourceEncoding, variableWidth: Int, scalar: Int): Int =
    when (encoding) {
        IniSourceEncoding.Utf8 -> utf8Length(scalar)
        IniSourceEncoding.Utf16Le, IniSourceEncoding.Utf16Be -> Character.charCount(scalar) * 2
        IniSourceEncoding.Latin1 -> 1
        is IniSourceEncoding.WindowsCodePage -> variableWidth
    }

private fun advance(position: DecodedPosition, scalar: Int, rawWidth: Int): DecodedPosition =
    DecodedPosition(
        rawByte = checkedAdd(position.rawByte, rawWidth),
        decodedUtf8Byte = checkedAdd(position.decodedUtf8Byte, utf8Length(scalar)),
        unicodeScalarOffset = checkedAdd(position.unicodeScalarOffset, 1),
        utf16CodeUnitOffset = checkedAdd(position.utf16CodeUnitOffset, Character.charCount(scalar)),
    )

/** Strict UTF-8 validation; returns the first invalid byte offset, or the
 * full length when valid. */
private fun utf8ValidUpTo(bytes: ByteArray): Int {
    var i = 0
    while (i < bytes.size) {
        val first = bytes[i].toInt() and 0xff
        when {
            first < 0x80 -> i += 1
            first in 0xc2..0xdf -> {
                if (!isContinuation(bytes, i + 1)) return i
                i += 2
            }
            first == 0xe0 -> {
                if (!isContinuation(bytes, i + 1) || !((bytes[i + 1].toInt() and 0xff) in 0xa0..0xbf)) return i
                if (!isContinuation(bytes, i + 2)) return i
                i += 3
            }
            first in 0xe1..0xec || first in 0xee..0xef -> {
                if (!isContinuation(bytes, i + 1) || !isContinuation(bytes, i + 2)) return i
                i += 3
            }
            first == 0xed -> {
                if (!isContinuation(bytes, i + 1) || !((bytes[i + 1].toInt() and 0xff) in 0x80..0x9f)) return i
                if (!isContinuation(bytes, i + 2)) return i
                i += 3
            }
            first == 0xf0 -> {
                if (!isContinuation(bytes, i + 1) || !((bytes[i + 1].toInt() and 0xff) in 0x90..0xbf)) return i
                if (!isContinuation(bytes, i + 2) || !isContinuation(bytes, i + 3)) return i
                i += 4
            }
            first in 0xf1..0xf3 -> {
                if (!isContinuation(bytes, i + 1) || !isContinuation(bytes, i + 2) ||
                    !isContinuation(bytes, i + 3)
                ) {
                    return i
                }
                i += 4
            }
            first == 0xf4 -> {
                if (!isContinuation(bytes, i + 1) || !((bytes[i + 1].toInt() and 0xff) in 0x80..0x8f)) return i
                if (!isContinuation(bytes, i + 2) || !isContinuation(bytes, i + 3)) return i
                i += 4
            }
            else -> return i
        }
    }
    return bytes.size
}

private fun isContinuation(bytes: ByteArray, index: Int): Boolean =
    index < bytes.size && (bytes[index].toInt() and 0xc0) == 0x80

/** Reads one validated UTF-8 scalar at a boundary; returns the code point
 * (the text is validated at construction, so structure is guaranteed). */
private fun readUtf8Scalar(bytes: ByteArray, index: Int): Int {
    val first = bytes[index].toInt() and 0xff
    return when {
        first < 0x80 -> first
        first in 0xc2..0xdf ->
            ((first and 0x1f) shl 6) or (bytes[index + 1].toInt() and 0x3f)

        first in 0xe0..0xef ->
            ((first and 0x0f) shl 12) or
                ((bytes[index + 1].toInt() and 0x3f) shl 6) or
                (bytes[index + 2].toInt() and 0x3f)

        else ->
            ((first and 0x07) shl 18) or
                ((bytes[index + 1].toInt() and 0x3f) shl 12) or
                ((bytes[index + 2].toInt() and 0x3f) shl 6) or
                (bytes[index + 3].toInt() and 0x3f)
    }
}

/** UTF-8 byte length of one code point. */
internal fun utf8Length(scalar: Int): Int =
    when {
        scalar < 0x80 -> 1
        scalar < 0x800 -> 2
        scalar < 0x1_0000 -> 3
        else -> 4
    }

private fun readU16(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
    val pair = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    return if (littleEndian) {
        ((pair and 0xff) shl 8) or (pair ushr 8)
    } else {
        pair
    }
}

internal fun checkLimit(name: String, observed: Int, limit: Int) {
    if (observed > limit) {
        throw IniSourceException(
            SourceErrorKind.RESOURCE_LIMIT,
            "ini source: $name limit reached ($observed > $limit)",
            name = name,
            observed = observed,
            limit = limit,
        )
    }
}

internal fun checkedAdd(left: Int, right: Int): Int =
    if (left > Int.MAX_VALUE - right) {
        throw IniSourceException(SourceErrorKind.OFFSET_OVERFLOW, "ini source: coordinate overflow")
    } else {
        left + right
    }

/**
 * Reconstructs the document-contract snapshot for v1-representable
 * encodings so the edit layer can derive SourcePatch and UntouchedByteProof
 * through the shared contract (kotlin/.../document/Patch.kt); returns null
 * for Windows code pages (document source-v2 gap,
 * kotlin/.../document/Encoding.kt:18-25).
 */
private fun documentSnapshot(bytes: ByteArray, facts: IniEncodingFacts): SourceSnapshot? {
    val override = facts.callerOverride
    val request = when (override) {
        null -> EncodingRequest.new(SourceEncoding.Utf8)
        IniSourceEncoding.Utf8 ->
            EncodingRequest.new(SourceEncoding.Utf8).withCallerOverride(SourceEncoding.Utf8)
        IniSourceEncoding.Utf16Le ->
            EncodingRequest.new(SourceEncoding.Utf8).withCallerOverride(SourceEncoding.Utf16Le)
        IniSourceEncoding.Utf16Be ->
            EncodingRequest.new(SourceEncoding.Utf8).withCallerOverride(SourceEncoding.Utf16Be)
        IniSourceEncoding.Latin1 ->
            EncodingRequest.new(SourceEncoding.Utf8).withCallerOverride(SourceEncoding.Latin1)
        is IniSourceEncoding.WindowsCodePage -> return null
    }
    return try {
        SourceSnapshot.fromRaw(bytes, request, SourceLimits.UNBOUNDED)
    } catch (e: consema.document.SourceException) {
        // The ini source already validated the same bytes; a reconstruction
        // failure is an internal invariant violation.
        null
    }
}
