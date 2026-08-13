// The semantic-model v6 source records: the verified core.source-snapshot@2
// and core.source-patch@2 messages with the source-v2 encoding facts
// (Windows code pages included).
//
// Data authority (language-neutral sources first):
//   - consema-rs/consema-protocol/src/source.rs:99-146 (SourceSnapshotMessageV2:
//     the fixed v2 wire schema, the encoding facts v2 record, and the strict
//     from_value re-verification of digest, encoding facts, and decoded
//     status).
//   - source.rs:196-239 (SourcePatchMessageV2), source.rs:598-631 (the v2
//     encoding facts), and consema-rs/consema-document/src/source.rs (the
//     resolution priority and the Windows code-page registry).
//   - conformance/vectors/semantic-model-v6.json pins the code-page
//     boundaries, BOM policies, digest rejection, and patch application.
//
// Kotlin-idiomatic design: the protocol record carries its own verified v2
// snapshot (the document package's SourceSnapshot is the closed v1 encoding
// set); code pages decode through the JDK's frozen charsets, and the decoded
// boundary index mirrors the document milestone's checkpointed index.

package consema.protocol

import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import consema.document.DecodedOffset
import consema.document.DecodedPosition
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** The source-v2 construction failure kinds mapped to the frozen protocol
 * failure vocabulary (source.rs:821-831). */
private enum class V2SourceError {
    INVALID_SEQUENCE,
    ENCODING_CONFLICT,
    UNSUPPORTED_BOM,
    RESOURCE_LIMIT,
}

private class V2SourceException(
    val error: V2SourceError,
    message: String,
) : Exception(message)

/** One verified source-v2 snapshot: exact raw bytes plus the explicitly
 * resolved encoding facts and the decoded boundary index. */
class SourceSnapshotV2 private constructor(
    private val raw: ByteArray,
    /** Stable SHA-256 identity of the exact retained bytes. */
    val digest: ContentDigest,
    /** Complete source-v2 encoding facts. */
    val encodingFacts: EncodingFacts,
    private val decoded: String?,
    private val decodedUtf8: ByteArray?,
    private val checkpoints: List<DecodedPosition>?,
    private val terminal: DecodedPosition?,
) {
    companion object {
        /**
         * Builds a v2 snapshot from raw bytes under the source-v2 resolution
         * request (source.rs:488-550 as mirrored for the v2 facts).
         */
        fun fromRaw(
            bytes: ByteArray,
            request: V2EncodingRequest,
            limits: SourceLimits,
        ): SourceSnapshotV2 {
            checkLimit("raw-bytes", bytes.size, limits.maxRawBytes)
            val facts = resolveV2Encoding(bytes, request)
            val digest = ContentDigest.of(bytes)
            val selected = facts.selected
                ?: throw invalid("$.encoding.selected", "selected encoding is absent")
            val (decoded, decodedUtf8) = decodeV2(selected, bytes, limits)
            val index = if (decodedUtf8 == null) {
                null
            } else {
                buildV2Index(decodedUtf8, selected, limits)
            }
            return SourceSnapshotV2(
                bytes.copyOf(),
                digest,
                facts,
                decoded,
                decodedUtf8,
                index?.first,
                index?.second,
            )
        }

        /** Compatibility constructor for exact UTF-8 sources. */
        fun fromUtf8(bytes: ByteArray): SourceSnapshotV2 =
            fromRaw(
                bytes,
                V2EncodingRequest.new(SourceEncoding("Utf8", null))
                    .withCallerOverride(SourceEncoding("Utf8", null)),
                SourceLimits.default,
            )
    }

    /** Exact retained source bytes; returns a defensive copy. */
    fun bytes(): ByteArray = raw.copyOf()

    /** Exact retained source bytes for internal use (no copy). */
    internal fun rawBytes(): ByteArray = raw

    /** Decoded text, or null for an opaque binary source. */
    fun decodedText(): String? = decoded

    /** The raw byte length. */
    val len: Int
        get() = raw.size

    /** Resolves one raw byte offset only when it is a decoded scalar
     * boundary (source.rs:622-641). */
    fun decodedPosition(rawByte: Int): DecodedPosition {
        if (rawByte < 0 || rawByte > raw.size) {
            throw SourceLocationException(SourceLocationErrorKind.OUT_OF_BOUNDS)
        }
        val utf8 = decodedUtf8 ?: throw SourceLocationException(SourceLocationErrorKind.NO_DECODED_TEXT)
        val checkpoints = checkpoints ?: throw SourceLocationException(SourceLocationErrorKind.NO_DECODED_TEXT)
        val selected = encodingFacts.selected
            ?: throw SourceLocationException(SourceLocationErrorKind.NO_DECODED_TEXT)
        val start = lastCheckpoint(checkpoints) { it.rawByte <= rawByte }
        return scanToRaw(utf8, selected, start, rawByte)
    }

    /** Resolves one decoded offset only when it denotes a scalar boundary. */
    fun rawByteAt(offset: DecodedOffset): Int {
        val utf8 = decodedUtf8 ?: throw SourceLocationException(SourceLocationErrorKind.NO_DECODED_TEXT)
        val terminal = terminal ?: throw SourceLocationException(SourceLocationErrorKind.NO_DECODED_TEXT)
        val checkpoints = checkpoints ?: throw SourceLocationException(SourceLocationErrorKind.NO_DECODED_TEXT)
        val selected = encodingFacts.selected
            ?: throw SourceLocationException(SourceLocationErrorKind.NO_DECODED_TEXT)
        val requested = offset.requestValue
        if (requested < 0 || requested > offset.component(terminal)) {
            throw SourceLocationException(SourceLocationErrorKind.OUT_OF_BOUNDS)
        }
        val start = lastCheckpoint(checkpoints) { offset.component(it) <= requested }
        return scanToDecoded(utf8, selected, start, offset)
    }

    override fun equals(other: Any?): Boolean =
        other is SourceSnapshotV2 &&
            raw.contentEquals(other.raw) &&
            encodingFacts == other.encodingFacts &&
            decoded == other.decoded

    override fun hashCode(): Int {
        var result = raw.contentHashCode()
        result = 31 * result + encodingFacts.hashCode()
        result = 31 * result + (decoded?.hashCode() ?: 0)
        return result
    }
}

/** The caller inputs to source-v2 encoding resolution (the v2 EncodingRequest
 * mirror: the profile default, BOM policy, declaration, and caller override,
 * all carrying Windows code pages). */
class V2EncodingRequest internal constructor(
    /** Profile fallback (required). */
    val profileDefault: SourceEncoding,
    /** BOM interpretation policy; "DetectUnicode" or "TreatAsContent". */
    val bomPolicy: String,
    /** Normalized in-source declaration. */
    val declaration: SourceEncoding?,
    /** Explicit caller choice. */
    val callerOverride: SourceEncoding?,
) {
    companion object {
        /** Starts with the required profile default. */
        fun new(profileDefault: SourceEncoding): V2EncodingRequest =
            V2EncodingRequest(profileDefault, "DetectUnicode", null, null)
    }

    /** Adds a normalized declaration. */
    fun withDeclaration(declaration: SourceEncoding): V2EncodingRequest =
        V2EncodingRequest(profileDefault, bomPolicy, declaration, callerOverride)

    /** Adds an explicit caller override. */
    fun withCallerOverride(callerOverride: SourceEncoding): V2EncodingRequest =
        V2EncodingRequest(profileDefault, bomPolicy, declaration, callerOverride)

    /** Selects whether leading marker-shaped bytes are BOM evidence or
     * content. */
    fun withBomPolicy(bomPolicy: String): V2EncodingRequest =
        V2EncodingRequest(profileDefault, bomPolicy, declaration, callerOverride)
}

/** The v2 encoding resolution (source.rs:740-782 mirrored for the v2 facts
 * with Windows code pages). */
internal fun resolveV2Encoding(bytes: ByteArray, request: V2EncodingRequest): EncodingFacts {
    val isText: (SourceEncoding?) -> Boolean = { it != null && it.kind != "Binary" }
    val hasExplicitText = isText(request.declaration) || isText(request.callerOverride)
    val interpretBom = request.bomPolicy == "DetectUnicode" &&
        (isText(request.profileDefault) || hasExplicitText)
    val bom = if (interpretBom) detectV2Bom(bytes) else null
    return resolveV2Assertions(request, bom, bom?.let(::bomEncoding))
}

/** Applies the frozen resolution assertions: disagreeing BOM/declaration/
 * caller facts produce an encoding conflict; Binary defaults with explicit
 * text facts conflict; the selected encoding is caller_override ->
 * declaration -> bom -> profile_default. */
private fun resolveV2Assertions(
    request: V2EncodingRequest,
    bom: String?,
    bomEncoding: SourceEncoding?,
): EncodingFacts {
    if (request.profileDefault.kind == "Binary" &&
        (request.declaration?.kind != null && request.declaration.kind != "Binary" ||
            request.callerOverride?.kind != null && request.callerOverride.kind != "Binary")
    ) {
        throw V2SourceException(V2SourceError.ENCODING_CONFLICT, "source: Binary profile default with explicit text fact")
    }
    if (request.bomPolicy == "TreatAsContent" && bom != null) {
        throw V2SourceException(V2SourceError.ENCODING_CONFLICT, "source: TreatAsContent with a detected BOM")
    }
    val assertions = listOfNotNull(bomEncoding, request.declaration, request.callerOverride)
    val first = assertions.firstOrNull()
    if (first != null && assertions.any { it != first }) {
        throw V2SourceException(V2SourceError.ENCODING_CONFLICT, "source: BOM/declaration/caller disagreement")
    }
    val selected = request.callerOverride ?: request.declaration ?: bomEncoding ?: request.profileDefault
    return EncodingFacts(
        profileDefault = request.profileDefault,
        bomPolicy = request.bomPolicy,
        bom = bom,
        declaration = request.declaration,
        callerOverride = request.callerOverride,
        selected = selected,
    )
}

/** Detects a Unicode BOM from raw bytes (source.rs:784-804). */
private fun detectV2Bom(bytes: ByteArray): String? {
    if (bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
    ) {
        throw V2SourceException(V2SourceError.UNSUPPORTED_BOM, "source: unsupported UTF-32LE BOM")
    }
    if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
        bytes[2] == 0xfe.toByte() && bytes[3] == 0xff.toByte()
    ) {
        throw V2SourceException(V2SourceError.UNSUPPORTED_BOM, "source: unsupported UTF-32BE BOM")
    }
    return when {
        bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte() -> "Utf8"
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> "Utf16Le"
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> "Utf16Be"
        else -> null
    }
}

private fun bomEncoding(bom: String): SourceEncoding = when (bom) {
    "Utf8" -> SourceEncoding("Utf8", null)
    "Utf16Le" -> SourceEncoding("Utf16Le", null)
    else -> SourceEncoding("Utf16Be", null)
}

/** Decodes the raw bytes under the selected encoding (the v2 decode set:
 * the v1 encodings plus the Windows code pages). */
private fun decodeV2(
    encoding: SourceEncoding,
    bytes: ByteArray,
    limits: SourceLimits,
): Pair<String?, ByteArray?> {
    if (encoding.kind == "Binary") {
        return null to null
    }
    val (decoded, decodedUtf8) = when (encoding.kind) {
        "Utf8" -> {
            val validUpTo = utf8ValidUpTo(bytes)
            if (validUpTo != bytes.size) {
                throw V2SourceException(
                    V2SourceError.INVALID_SEQUENCE,
                    "source: invalid UTF-8 at byte $validUpTo",
                )
            }
            val text = String(bytes, StandardCharsets.UTF_8)
            text to bytes.copyOf()
        }
        "Utf16Le" -> decodeUtf16V2(bytes, littleEndian = true, limits)
        "Utf16Be" -> decodeUtf16V2(bytes, littleEndian = false, limits)
        "Latin1" -> decodeLatin1V2(bytes, limits)
        "WindowsCodePage" -> decodeCodePage(encoding.windowsCodePage ?: throw invalid("$.encoding", "missing code page"), bytes, limits)
        else -> throw V2SourceException(V2SourceError.INVALID_SEQUENCE, "source: unknown encoding kind")
    }
    checkLimit("decoded-utf8-bytes", decodedUtf8.size, limits.maxDecodedUtf8Bytes)
    return decoded to decodedUtf8
}

private fun decodeUtf16V2(bytes: ByteArray, littleEndian: Boolean, limits: SourceLimits): Pair<String, ByteArray> {
    if (bytes.size % 2 != 0) {
        throw V2SourceException(V2SourceError.INVALID_SEQUENCE, "source: odd-length UTF-16")
    }
    val output = StringBuilder()
    var offset = 0
    var scalars = 0
    var decodedBytes = 0
    while (offset < bytes.size) {
        val first = readU16(bytes, offset, littleEndian)
        val scalar: Int
        val consumed: Int
        if (first in 0xd800..0xdbff) {
            if (offset + 3 >= bytes.size) {
                throw V2SourceException(V2SourceError.INVALID_SEQUENCE, "source: truncated UTF-16 surrogate pair")
            }
            val second = readU16(bytes, offset + 2, littleEndian)
            if (second !in 0xdc00..0xdfff) {
                throw V2SourceException(V2SourceError.INVALID_SEQUENCE, "source: isolated UTF-16 high surrogate")
            }
            scalar = 0x1_0000 + ((first - 0xd800) shl 10) + (second - 0xdc00)
            consumed = 4
        } else if (first in 0xdc00..0xdfff) {
            throw V2SourceException(V2SourceError.INVALID_SEQUENCE, "source: isolated UTF-16 low surrogate")
        } else {
            scalar = first
            consumed = 2
        }
        scalars = checkedAddV2(scalars, 1)
        checkLimit("decoded-scalars", scalars, limits.maxDecodedScalars)
        decodedBytes = checkedAddV2(decodedBytes, utf8Length(scalar))
        checkLimit("decoded-utf8-bytes", decodedBytes, limits.maxDecodedUtf8Bytes)
        output.appendCodePoint(scalar)
        offset += consumed
    }
    return output.toString() to output.toString().toByteArray(StandardCharsets.UTF_8)
}

private fun decodeLatin1V2(bytes: ByteArray, limits: SourceLimits): Pair<String, ByteArray> {
    checkLimit("decoded-scalars", bytes.size, limits.maxDecodedScalars)
    val output = StringBuilder()
    var decodedBytes = 0
    for (byte in bytes) {
        val scalar = byte.toInt() and 0xff
        decodedBytes = checkedAddV2(decodedBytes, utf8Length(scalar))
        checkLimit("decoded-utf8-bytes", decodedBytes, limits.maxDecodedUtf8Bytes)
        output.appendCodePoint(scalar)
    }
    return output.toString() to output.toString().toByteArray(StandardCharsets.UTF_8)
}

/** Decodes one Windows code page through the JDK's frozen charset. */
private fun decodeCodePage(page: Int, bytes: ByteArray, limits: SourceLimits): Pair<String, ByteArray> {
    val charset = codePageCharset(page)
        ?: throw V2SourceException(V2SourceError.INVALID_SEQUENCE, "source: unsupported code page $page")
    val decoder = charset.newDecoder()
    val decoded = try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw V2SourceException(V2SourceError.INVALID_SEQUENCE, "source: invalid sequence for code page $page")
    }
    checkLimit("decoded-scalars", decoded.codePointCount(0, decoded.length), limits.maxDecodedScalars)
    val utf8 = decoded.toByteArray(StandardCharsets.UTF_8)
    return decoded to utf8
}

/** The JDK charset of one published Windows code page. */
internal fun codePageCharset(page: Int): Charset? = try {
    when (page) {
        874 -> Charset.forName("x-windows-874")
        932 -> Charset.forName("windows-31j")
        936 -> Charset.forName("GBK")
        949 -> Charset.forName("x-windows-949")
        950 -> Charset.forName("x-windows-950")
        1250 -> Charset.forName("windows-1250")
        1251 -> Charset.forName("windows-1251")
        1252 -> Charset.forName("windows-1252")
        1253 -> Charset.forName("windows-1253")
        1254 -> Charset.forName("windows-1254")
        1255 -> Charset.forName("windows-1255")
        1256 -> Charset.forName("windows-1256")
        1257 -> Charset.forName("windows-1257")
        1258 -> Charset.forName("windows-1258")
        65001 -> StandardCharsets.UTF_8
        else -> null
    }
} catch (e: java.nio.charset.UnsupportedCharsetException) {
    null
}

/** Builds the checkpointed decoded boundary index (document source.rs:
 * 1016-1067 mirrored for the v2 encodings). */
private fun buildV2Index(
    utf8: ByteArray,
    encoding: SourceEncoding,
    limits: SourceLimits,
): Pair<List<DecodedPosition>, DecodedPosition> {
    checkLimit("decoded-utf8-bytes", utf8.size, limits.maxDecodedUtf8Bytes)
    var current = DecodedPosition(0, 0, 0, 0)
    val checkpoints = ArrayList<DecodedPosition>()
    checkpoints.add(current)
    var byteIndex = 0
    while (byteIndex < utf8.size) {
        val scalar = readUtf8Scalar(utf8, byteIndex)
        val rawWidth = rawStepWidthV2(encoding, scalar, utf8, byteIndex)
        current = advanceV2(current, scalar, rawWidth)
        checkLimit("decoded-scalars", current.unicodeScalarOffset, limits.maxDecodedScalars)
        if (current.unicodeScalarOffset % 256 == 0) {
            checkpoints.add(current)
        }
        byteIndex += utf8Length(scalar)
    }
    if (checkpoints.last() != current) {
        checkpoints.add(current)
    }
    return checkpoints to current
}

/** The raw byte advance of one decoded scalar under the selected encoding.
 * The multi-byte code pages (932/936/949/950) use the decoded text width in
 * the source encoding, computed from the UTF-8 width of the scalar. */
private fun rawStepWidthV2(encoding: SourceEncoding, scalar: Int, utf8: ByteArray, byteIndex: Int): Int =
    when (encoding.kind) {
        "Utf8" -> utf8Length(scalar)
        "Utf16Le", "Utf16Be" -> Character.charCount(scalar) * 2
        "Latin1" -> 1
        "WindowsCodePage" -> {
            val page = encoding.windowsCodePage ?: 1
            if (page == 932 || page == 936 || page == 949 || page == 950) {
                // Re-encode the scalar in the source charset to learn its
                // exact byte width (1 or 2 bytes for these pages).
                val text = String(utf8, byteIndex, utf8Length(scalar), StandardCharsets.UTF_8)
                val charset = codePageCharset(page)
                if (charset == null) 1 else text.toByteArray(charset).size
            } else {
                1
            }
        }
        else -> error("binary source has no decoded locations")
    }

private fun advanceV2(position: DecodedPosition, scalar: Int, rawWidth: Int): DecodedPosition =
    DecodedPosition(
        rawByte = checkedAddV2(position.rawByte, rawWidth),
        decodedUtf8Byte = checkedAddV2(position.decodedUtf8Byte, utf8Length(scalar)),
        unicodeScalarOffset = checkedAddV2(position.unicodeScalarOffset, 1),
        utf16CodeUnitOffset = checkedAddV2(position.utf16CodeUnitOffset, Character.charCount(scalar)),
    )

private fun scanToRaw(
    utf8: ByteArray,
    encoding: SourceEncoding,
    start: DecodedPosition,
    requested: Int,
): DecodedPosition {
    if (start.rawByte == requested) {
        return start
    }
    var position = start
    var byteIndex = start.decodedUtf8Byte
    while (byteIndex < utf8.size) {
        val scalar = readUtf8Scalar(utf8, byteIndex)
        position = advanceV2(position, scalar, rawStepWidthV2(encoding, scalar, utf8, byteIndex))
        byteIndex += utf8Length(scalar)
        if (position.rawByte == requested) {
            return position
        }
        if (position.rawByte > requested) {
            throw SourceLocationException(SourceLocationErrorKind.NOT_DECODED_BOUNDARY)
        }
    }
    throw SourceLocationException(SourceLocationErrorKind.OUT_OF_BOUNDS)
}

private fun scanToDecoded(
    utf8: ByteArray,
    encoding: SourceEncoding,
    start: DecodedPosition,
    requested: DecodedOffset,
): Int {
    val target = requested.requestValue
    if (requested.component(start) == target) {
        return start.rawByte
    }
    var position = start
    var byteIndex = start.decodedUtf8Byte
    while (byteIndex < utf8.size) {
        val scalar = readUtf8Scalar(utf8, byteIndex)
        position = advanceV2(position, scalar, rawStepWidthV2(encoding, scalar, utf8, byteIndex))
        byteIndex += utf8Length(scalar)
        val observed = requested.component(position)
        if (observed == target) {
            return position.rawByte
        }
        if (observed > target) {
            throw SourceLocationException(SourceLocationErrorKind.DECODED_OFFSET_NOT_BOUNDARY)
        }
    }
    throw SourceLocationException(SourceLocationErrorKind.OUT_OF_BOUNDS)
}

private fun lastCheckpoint(
    checkpoints: List<DecodedPosition>,
    predicate: (DecodedPosition) -> Boolean,
): DecodedPosition {
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

/** The source-v2 location failure kinds. */
enum class SourceLocationErrorKind {
    OUT_OF_BOUNDS,
    NO_DECODED_TEXT,
    NOT_DECODED_BOUNDARY,
    DECODED_OFFSET_NOT_BOUNDARY,
}

class SourceLocationException(val kind: SourceLocationErrorKind) :
    Exception("source location: $kind")

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

private fun readUtf8Scalar(bytes: ByteArray, index: Int): Int {
    val first = bytes[index].toInt() and 0xff
    return when {
        first < 0x80 -> first
        first in 0xc2..0xdf -> ((first and 0x1f) shl 6) or (bytes[index + 1].toInt() and 0x3f)
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

private fun utf8Length(scalar: Int): Int =
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

private fun checkLimit(name: String, observed: Int, limit: Int) {
    if (observed > limit) {
        throw V2SourceException(V2SourceError.RESOURCE_LIMIT, "source: $name limit reached")
    }
}

private fun checkedAddV2(left: Int, right: Int): Int =
    if (left > Int.MAX_VALUE - right) {
        throw V2SourceException(V2SourceError.RESOURCE_LIMIT, "source: coordinate overflow")
    } else {
        left + right
    }

/** Maps a v2 source construction failure to the protocol failure vocabulary
 * (source.rs:821-831). */
private fun mapV2SourceError(e: V2SourceException): ProtocolException =
    when (e.error) {
        V2SourceError.INVALID_SEQUENCE -> invalid("$.raw_bytes", e.message ?: "invalid sequence")
        V2SourceError.ENCODING_CONFLICT -> invalid("$.encoding", e.message ?: "encoding conflict")
        V2SourceError.UNSUPPORTED_BOM -> invalid("$.encoding", e.message ?: "unsupported BOM")
        V2SourceError.RESOURCE_LIMIT -> resource("$.raw_bytes", e.message ?: "resource limit")
    }

// ---------------------------------------------------------------------------
// core.source-snapshot@2 (source.rs:99-146, 241-321).
// ---------------------------------------------------------------------------

/** Verified `core.source-snapshot@2` message (source.rs:99-146). */
class SourceSnapshotMessageV2 private constructor(
    private val snapshot: SourceSnapshotV2,
) {
    companion object {
        /** Copies one immutable snapshot into a source-v2 message. */
        fun fromSnapshot(snapshot: SourceSnapshotV2): SourceSnapshotMessageV2 =
            SourceSnapshotMessageV2(snapshot)

        /** Strictly decodes and re-verifies every source-v2 fact
         * (source.rs:135-145). */
        fun fromValue(value: PortableValue, limits: SourceLimits): SourceSnapshotMessageV2 {
            val fields = schemaFields(
                value,
                "core.source-snapshot@2",
                listOf("schema", "raw_bytes", "digest", "encoding", "decoded_status"),
                "$",
            )
            val raw = fields[1] as? consema.core.PvBytes
                ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$.raw_bytes", "expected Bytes")
            val claimedDigest = parseDigest(fields[2], "$.digest")
            val claimedEncoding = EncodingFacts.fromValue(fields[3], "$.encoding")
            val decodedStatus = stringOf(fields[4], "$.decoded_status")
            if (decodedStatus != "Available" && decodedStatus != "NotText") {
                throw invalid("$.decoded_status", "expected Available or NotText")
            }
            val snapshot = try {
                SourceSnapshotV2.fromRaw(raw.content(), v2RequestOf(claimedEncoding), limits)
            } catch (e: V2SourceException) {
                throw mapV2SourceError(e)
            }
            if (snapshot.digest != claimedDigest) {
                throw invalid("$.digest", "digest does not match raw_bytes")
            }
            if (snapshot.encodingFacts != claimedEncoding) {
                throw invalid("$.encoding", "encoding facts do not match raw_bytes resolution")
            }
            val actualStatus = if (snapshot.decodedText() == null) "NotText" else "Available"
            if (decodedStatus != actualStatus) {
                throw invalid("$.decoded_status", "decoded status contradicts selected encoding")
            }
            return SourceSnapshotMessageV2(snapshot)
        }
    }

    /** Verified immutable source snapshot. */
    fun snapshot(): SourceSnapshotV2 = snapshot

    /** Encodes the exact source-snapshot v2 schema (source.rs:127-133,
     * 241-260). */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.source-snapshot@2")),
                consema.core.Entry("raw_bytes", consema.core.PvBytes.of(snapshot.bytes())),
                consema.core.Entry("digest", digestValue(snapshot.digest)),
                consema.core.Entry("encoding", snapshot.encodingFacts.toValue()),
                consema.core.Entry(
                    "decoded_status",
                    PvString(if (snapshot.decodedText() == null) "NotText" else "Available"),
                ),
            ),
        )
}

/** The resolution request derived from the claimed encoding facts
 * (source.rs:791-802). */
private fun v2RequestOf(facts: EncodingFacts): V2EncodingRequest {
    val profileDefault = facts.profileDefault
        ?: throw invalid("$.encoding.profile_default", "profile default is required")
    return V2EncodingRequest(profileDefault, facts.bomPolicy, facts.declaration, facts.callerOverride)
}

// ---------------------------------------------------------------------------
// core.source-patch@2 (source.rs:196-239, 323-371).
// ---------------------------------------------------------------------------

/** One raw-byte precondition and replacement of a source-patch v2. */
data class SourceReplacementV2(
    /** Inclusive start raw byte. */
    val oldStart: Int,
    /** Exclusive end raw byte. */
    val oldEnd: Int,
    /** Exact bytes required at the old range. */
    val original: ByteArray,
    /** Exact bytes written in place of the old range. */
    val replacement: ByteArray,
    /** Whether review presentation hides the original bytes. */
    val redactOriginal: Boolean,
    /** Whether review presentation hides the replacement bytes. */
    val redactReplacement: Boolean,
)

/** The verified `core.source-patch@2` facts: the base and target digests,
 * the source-v2 encoding facts, the ordered replacements, and the sorted
 * metadata. */
class SourcePatchV2 private constructor(
    /** Required base content identity. */
    val baseDigest: ContentDigest,
    /** Required result content identity. */
    val targetDigest: ContentDigest,
    /** Encoding facts that both base and result must reproduce. */
    val encodingFacts: EncodingFacts,
    /** Ordered structural replacements. */
    val replacements: List<SourceReplacementV2>,
    /** Deterministic sorted metadata map. */
    val metadata: Map<String, String>,
) {
    companion object {
        /** Builds a self-consistent patch against one immutable v2 base
         * snapshot (source_patch.rs:226-251 mirrored). */
        fun create(
            base: SourceSnapshotV2,
            replacements: List<SourceReplacementV2>,
            metadata: Map<String, String>,
            limits: SourcePatchLimits,
        ): SourcePatchV2 {
            validateReplacements(replacements, limits)
            val targetBytes = applyReplacements(base.rawBytes(), replacements, limits)
            val target = try {
                SourceSnapshotV2.fromRaw(targetBytes, v2RequestOf(base.encodingFacts), limits.source)
            } catch (e: V2SourceException) {
                throw mapV2SourceError(e)
            }
            if (target.encodingFacts != base.encodingFacts) {
                throw invalid("$.encoding", "target encoding facts differ from the base")
            }
            return SourcePatchV2(
                baseDigest = base.digest,
                targetDigest = target.digest,
                encodingFacts = base.encodingFacts,
                replacements = replacements,
                metadata = sortedMetadata(metadata),
            )
        }

        /** Strictly decodes the structural patch facts without applying them
         * to a base snapshot. */
        fun fromFacts(
            baseDigest: ContentDigest,
            targetDigest: ContentDigest,
            encoding: EncodingFacts,
            replacements: List<SourceReplacementV2>,
            metadata: Map<String, String>,
            limits: SourcePatchLimits,
        ): SourcePatchV2 {
            validateReplacements(replacements, limits)
            return SourcePatchV2(baseDigest, targetDigest, encoding, replacements, sortedMetadata(metadata))
        }
    }

    /** Applies the patch to one base snapshot and returns the verified
     * target snapshot (source_patch.rs:213-299 mirrored). */
    fun apply(base: SourceSnapshotV2, limits: SourcePatchLimits): SourceSnapshotV2 {
        if (base.digest != baseDigest) {
            throw SourcePatchV2Exception("core.source.patch-base-mismatch@1")
        }
        val targetBytes = applyReplacements(base.rawBytes(), replacements, limits)
        val target = try {
            SourceSnapshotV2.fromRaw(targetBytes, v2RequestOf(base.encodingFacts), limits.source)
        } catch (e: V2SourceException) {
            throw mapV2SourceError(e)
        }
        if (target.digest != targetDigest || target.encodingFacts != encodingFacts) {
            throw SourcePatchV2Exception("core.source.patch-base-mismatch@1")
        }
        return target
    }
}

/** The typed source-patch v2 failure carrying the frozen registered code. */
class SourcePatchV2Exception(val code: String) : Exception("source patch: $code")

private fun validateReplacements(replacements: List<SourceReplacementV2>, limits: SourcePatchLimits) {
    if (replacements.size > limits.maxReplacements) {
        throw invalid("$.replacements", "replacement count exceeds configured limit")
    }
    var patchBytes = 0
    for (replacement in replacements) {
        if (replacement.oldStart > replacement.oldEnd ||
            replacement.original.size != replacement.oldEnd - replacement.oldStart
        ) {
            throw invalid("$.replacements", "invalid replacement range or original length")
        }
        patchBytes += replacement.original.size + replacement.replacement.size
        if (patchBytes > limits.maxPatchBytes) {
            throw invalid("$.replacements", "patch byte budget exceeded")
        }
    }
    if (replacements.zipWithNext().any { (left, right) -> left.oldEnd > right.oldStart }) {
        throw invalid("$.replacements", "replacements must be ordered and non-overlapping")
    }
}

private fun applyReplacements(
    bytes: ByteArray,
    replacements: List<SourceReplacementV2>,
    limits: SourcePatchLimits,
): ByteArray {
    val output = ArrayList<Byte>(bytes.size + 16)
    var position = 0
    for (replacement in replacements) {
        for (i in position until replacement.oldStart) {
            output.add(bytes[i])
        }
        for (i in 0 until replacement.original.size) {
            if (bytes[replacement.oldStart + i] != replacement.original[i]) {
                throw SourcePatchV2Exception("core.source.patch-base-mismatch@1")
            }
        }
        for (byte in replacement.replacement) {
            output.add(byte)
        }
        position = replacement.oldEnd
    }
    for (i in position until bytes.size) {
        output.add(bytes[i])
    }
    val result = ByteArray(output.size)
    for (i in output.indices) {
        result[i] = output[i]
    }
    return result
}

private fun sortedMetadata(metadata: Map<String, String>): Map<String, String> =
    metadata.toSortedMap()

/** Transferable `core.source-patch@2` verification facts (source.rs:196-
 * 239). */
class SourcePatchMessageV2 private constructor(
    private val patch: SourcePatchV2,
) {
    companion object {
        /** Copies one validated source patch into a source-v2 message. */
        fun fromPatch(patch: SourcePatchV2): SourcePatchMessageV2 = SourcePatchMessageV2(patch)

        /** Strictly decodes structural source-patch v2 facts
         * (source.rs:231-238). */
        fun fromValue(value: PortableValue, limits: SourcePatchLimits): SourcePatchMessageV2 {
            val fields = schemaFields(
                value,
                "core.source-patch@2",
                listOf("schema", "base_digest", "target_digest", "encoding", "replacements", "metadata"),
                "$",
            )
            val baseDigest = parseDigest(fields[1], "$.base_digest")
            val targetDigest = parseDigest(fields[2], "$.target_digest")
            val encoding = EncodingFacts.fromValue(fields[3], "$.encoding")
            val replacementValues = sequenceOf(fields[4], "$.replacements")
            if (replacementValues.size > limits.maxReplacements) {
                throw resource("$.replacements", "replacement count exceeds configured limit")
            }
            val replacements = replacementValues.mapIndexed { index, replacementValue ->
                val path = "$.replacements[$index]"
                val replacementFields = exactFields(
                    replacementValue,
                    listOf(
                        "old_start", "old_end", "original", "replacement",
                        "redact_original", "redact_replacement",
                    ),
                    path,
                )
                val original = replacementFields[2] as? consema.core.PvBytes
                    ?: throw protocolError(
                        ProtocolErrorKind.WRONG_TYPE,
                        "$path.original",
                        "expected Bytes",
                    )
                val replacement = replacementFields[3] as? consema.core.PvBytes
                    ?: throw protocolError(
                        ProtocolErrorKind.WRONG_TYPE,
                        "$path.replacement",
                        "expected Bytes",
                    )
                SourceReplacementV2(
                    oldStart = unsigned64(replacementFields[0], "$path.old_start").toInt(),
                    oldEnd = unsigned64(replacementFields[1], "$path.old_end").toInt(),
                    original = original.content(),
                    replacement = replacement.content(),
                    redactOriginal = booleanOf(replacementFields[4], "$path.redact_original"),
                    redactReplacement = booleanOf(replacementFields[5], "$path.redact_replacement"),
                )
            }
            val metadata = stringMapFromObject(fields[5], "$.metadata")
            return SourcePatchMessageV2(
                SourcePatchV2.fromFacts(baseDigest, targetDigest, encoding, replacements, metadata, limits),
            )
        }
    }

    /** Validated source patch. */
    fun patch(): SourcePatchV2 = patch

    /** Encodes the exact source-patch v2 schema (source.rs:222-229,
     * 323-355). */
    fun toValue(): PortableValue {
        val replacementValues = patch.replacements.map { replacement ->
            PvObject(
                listOf(
                    consema.core.Entry("old_start", integerValue(replacement.oldStart.toULong())),
                    consema.core.Entry("old_end", integerValue(replacement.oldEnd.toULong())),
                    consema.core.Entry("original", consema.core.PvBytes.of(replacement.original)),
                    consema.core.Entry("replacement", consema.core.PvBytes.of(replacement.replacement)),
                    consema.core.Entry("redact_original", consema.core.PvBoolean(replacement.redactOriginal)),
                    consema.core.Entry("redact_replacement", consema.core.PvBoolean(replacement.redactReplacement)),
                ),
            )
        }
        return PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.source-patch@2")),
                consema.core.Entry("base_digest", digestValue(patch.baseDigest)),
                consema.core.Entry("target_digest", digestValue(patch.targetDigest)),
                consema.core.Entry("encoding", patch.encodingFacts.toValue()),
                consema.core.Entry("replacements", consema.core.PvArray(replacementValues)),
                consema.core.Entry("metadata", stringMapObject(patch.metadata)),
            ),
        )
    }
}
