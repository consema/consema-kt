// Immutable raw source snapshots: byte ownership, content digest, explicit
// encoding resolution, decoded text, and exact boundary mapping.
//
// Data authority (language-neutral sources first):
//   - RFC 0003 §3-§5 (https://github.com/consema/consema/blob/main/docs/rfcs/0003-source-syntax-query-and-patch-v1.md):
//     content digest over exact raw bytes; closed v1 encoding set; decoded
//     boundary tuple (raw_byte, decoded_utf8_byte, unicode_scalar_offset,
//     utf16_code_unit_offset); only scalar boundaries are addressable; BOM
//     bytes remain part of the raw source and digest and are retained as
//     leading U+FEFF in the decoded view.
//   - conformance/vectors/source-v1.json (cases source.digest.*,
//     source.encoding.*, source.location.*, source.resource.*, lines 4-172)
//     pins the byte-exact behaviors and rejection codes.
//   - https://github.com/consema/consema-rs/blob/main/consema-document/src/source.rs (SourceSnapshot),
//     source.rs (SourceError, UnsupportedBomKind), and the decode
//     rules source.rs; https://github.com/consema/consema-rs/blob/main/consema-conformance/src/source_v1.rs
// maps SourceError variants to the registered codes.
//   - consema-go/go/document/source.go is a cross-reference only.
//
// The registered error codes (https://github.com/consema/consema-rs/blob/main/consema-protocol/src/error_registry.rs
// 207, 366-410; transcribed into kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt,
//):
//   core.source.invalid-sequence@1  (InvalidUtf8 and InvalidSequence)
//   core.source.encoding-conflict@1
//   core.source.unsupported-bom@1
//   core.source.resource-limit@1    (ResourceLimit and OffsetOverflow)
// The mapping is the conformance runner's source_error_code
// (source_v1.rs).
//
// Kotlin-idiomatic design: construction failures are typed exceptions
// carrying the frozen `code` (the established consema.core/consema.protocol
// style), never checked results; the decoded text is validated exactly once
// at construction and retained (the Rust DecodedStorage, source.rs).

package consema.document

import java.nio.charset.StandardCharsets

/** Checkpoint stride of the decoded boundary index (source.rs). */
private const val CHECKPOINT_STRIDE = 256

/** Stable source construction failure kinds and their frozen registered
 * codes (source_v1.rs; error_registry.rs). */
enum class SourceErrorKind(val code: String) {
    /** Compatibility failure of SourceSnapshot.fromUtf8 (source.rs);
     * maps to the registered invalid-sequence code (source_v1.rs). */
    INVALID_UTF8("core.source.invalid-sequence@1"),

    /** Raw bytes are not a valid sequence in the selected encoding
     * (source.rs). */
    INVALID_SEQUENCE("core.source.invalid-sequence@1"),

    /** BOM, declaration, and caller inputs made contradictory assertions
     * (source.rs; RFC 0003 §4.2). */
    ENCODING_CONFLICT("core.source.encoding-conflict@1"),

    /** A UTF-32 byte-order mark is recognized but unsupported by v1
     * (source.rs; RFC 0003 §4.2). */
    UNSUPPORTED_BOM("core.source.unsupported-bom@1"),

    /** A configured construction bound was exceeded (source.rs). */
    RESOURCE_LIMIT("core.source.resource-limit@1"),

    /** Coordinate arithmetic exceeded the host representation
     * (source.rs). */
    OFFSET_OVERFLOW("core.source.resource-limit@1"),
}

/**
 * The typed source-construction failure. The stable [code] is always the
 * registered code, so cross-language error-code parity holds (RFC 0016 §6);
 * error text is human presentation only and never participates in
 * conformance comparison.
 */
class SourceException(
    val kind: SourceErrorKind,
    message: String? = null,
    /** Selected encoding of an invalid sequence. */
    val encoding: SourceEncoding? = null,
    /** First byte at which a valid sequence could not be formed. */
    val byteOffset: Int? = null,
    /** Prefix length that was valid UTF-8 (INVALID_UTF8). */
    val validUpTo: Int? = null,
    /** BOM-derived encoding of a conflict. */
    val bom: SourceEncoding? = null,
    /** Declaration-derived encoding of a conflict. */
    val declaration: SourceEncoding? = null,
    /** Caller-selected encoding of a conflict. */
    val callerOverride: SourceEncoding? = null,
    /** Stable unsupported marker identifier (UNSUPPORTED_BOM). */
    val bomKind: UnsupportedBomKind? = null,
    /** Stable limit name (RESOURCE_LIMIT). */
    val name: String? = null,
    /** Observed amount (RESOURCE_LIMIT). */
    val observed: Int? = null,
    /** Configured maximum (RESOURCE_LIMIT). */
    val limit: Int? = null,
) : Exception(message ?: "source: ${kind.code}") {
    /** The frozen registered code of the failure. */
    val code: String
        get() = kind.code
}

/**
 * Immutable ownership of exact raw bytes plus explicitly derived text facts
 * (RFC 0003 §3-§6; source.rs).
 *
 * The decoder recomputes the digest, reruns encoding resolution and
 * decoding, and requires exact equality with all encoded facts; a peer
 * cannot claim a digest or encoding result that the raw bytes do not produce
 * (RFC 0003 §6). This payload is a complete immutable content fact, not a
 * file path, URI, loader, owner, permission record, or live buffer.
 */
class SourceSnapshot private constructor(
    private val raw: ByteArray,
    /** Stable SHA-256 identity of exact retained bytes. */
    val digest: ContentDigest,
    /** Complete encoding-resolution facts. */
    val encodingFacts: EncodingFacts,
    private val decoded: String?,
    private val decodedUtf8: ByteArray?,
    private val decodedIndex: DecodedIndex?,
) {
    companion object {
        /**
         * Constructs a source from raw bytes using explicit resolution
         * inputs and limits (source.rs). Throws [SourceException]
         * on limit, encoding-conflict, unsupported-BOM, or
         * invalid-sequence failure; no partial snapshot is returned.
         */
        fun fromRaw(
            bytes: ByteArray,
            request: EncodingRequest,
            limits: SourceLimits = SourceLimits.default,
        ): SourceSnapshot {
            checkLimit("raw-bytes", bytes.size, limits.maxRawBytes)
            val encoding = resolveEncoding(bytes, request)
            val digest = ContentDigest.of(bytes)

            val decoded: String?
            val decodedUtf8: ByteArray?
            when (encoding.selected) {
                SourceEncoding.Binary -> {
                    decoded = null
                    decodedUtf8 = null
                }
                SourceEncoding.Utf8 -> {
                    val validUpTo = utf8ValidUpTo(bytes)
                    if (validUpTo != bytes.size) {
                        throw SourceException(
                            SourceErrorKind.INVALID_SEQUENCE,
                            "source: invalid UTF-8 at byte $validUpTo",
                            encoding = SourceEncoding.Utf8,
                            byteOffset = validUpTo,
                        )
                    }
                    decoded = String(bytes, StandardCharsets.UTF_8)
                    decodedUtf8 = bytes.copyOf()
                }
                SourceEncoding.Utf16Le -> {
                    decoded = decodeUtf16(bytes, littleEndian = true, limits)
                    decodedUtf8 = decoded.toByteArray(StandardCharsets.UTF_8)
                }
                SourceEncoding.Utf16Be -> {
                    decoded = decodeUtf16(bytes, littleEndian = false, limits)
                    decodedUtf8 = decoded.toByteArray(StandardCharsets.UTF_8)
                }
                SourceEncoding.Latin1 -> {
                    decoded = decodeLatin1(bytes, limits)
                    decodedUtf8 = decoded.toByteArray(StandardCharsets.UTF_8)
                }
            }

            val decodedIndex = if (decodedUtf8 == null) {
                null
            } else {
                buildIndex(decodedUtf8, encoding.selected, limits)
            }
            return SourceSnapshot(bytes.copyOf(), digest, encoding, decoded, decodedUtf8, decodedIndex)
        }

        /**
         * Compatibility constructor for exact UTF-8 sources
         * (source.rs). An invalid sequence is reported as the
         * INVALID_UTF8 kind carrying the valid prefix length.
         */
        fun fromUtf8(bytes: ByteArray): SourceSnapshot {
            return try {
                fromRaw(
                    bytes,
                    EncodingRequest.new(SourceEncoding.Utf8)
                        .withCallerOverride(SourceEncoding.Utf8),
                    SourceLimits.UNBOUNDED,
                )
            } catch (e: SourceException) {
                if (e.kind == SourceErrorKind.INVALID_SEQUENCE && e.encoding == SourceEncoding.Utf8) {
                    throw SourceException(
                        SourceErrorKind.INVALID_UTF8,
                        "source: invalid UTF-8 at byte ${e.byteOffset}",
                        validUpTo = e.byteOffset,
                    )
                }
                throw e
            }
        }

        /** Constructs an opaque binary source without decoding or BOM
         * interpretation (source.rs). */
        fun fromBinary(
            bytes: ByteArray,
            limits: SourceLimits = SourceLimits.default,
        ): SourceSnapshot = fromRaw(bytes, EncodingRequest.binary(), limits)
    }

    /** Exact retained source bytes; returns a defensive copy. */
    fun bytes(): ByteArray = raw.copyOf()

    /** Exact retained source bytes for document-internal use (no copy). */
    internal fun rawBytes(): ByteArray = raw

    /**
     * Decoded text, or null for an opaque binary source. The text is fully
     * validated exactly once at construction; each call returns the stored
     * view in O(1) without re-validating the raw bytes (source.rs).
     * The original BOM bytes remain part of the raw source and digest; in
     * the decoded view a recognized text BOM is retained as leading U+FEFF
     * (RFC 0003 §4.3).
     */
    fun decodedText(): String? = decoded

    /** Source byte length. */
    val len: Int
        get() = raw.size

    /** Whether the source is empty. */
    val isEmpty: Boolean
        get() = raw.isEmpty()

    /**
     * Resolves one raw byte offset only when it is a decoded scalar boundary
     * (RFC 0003 §5; source.rs). Throws [LocationException]:
     * OutOfBounds, NoDecodedText, or NotDecodedBoundary.
     */
    fun decodedPosition(rawByte: Int): DecodedPosition {
        // Negative offsets are impossible in the Rust usize surface
        // (source.rs); Kotlin Ints require the explicit guard.
        if (rawByte < 0 || rawByte > raw.size) {
            throw LocationException(LocationErrorKind.OutOfBounds)
        }
        val utf8 = decodedUtf8 ?: throw LocationException(LocationErrorKind.NoDecodedText)
        val index = decodedIndex ?: throw LocationException(LocationErrorKind.NoDecodedText)
        val checkpoint = lastCheckpoint(index.checkpoints) { it.rawByte <= rawByte }
        return scanToRaw(utf8, encodingFacts.selected, checkpoint, rawByte)
    }

    /**
     * Resolves one decoded offset only when it denotes a scalar boundary
     * (RFC 0003 §5; source.rs). Throws [LocationException]:
     * OutOfBounds, NoDecodedText, or DecodedOffsetNotBoundary.
     */
    fun rawByteAt(offset: DecodedOffset): Int {
        val utf8 = decodedUtf8 ?: throw LocationException(LocationErrorKind.NoDecodedText)
        val index = decodedIndex ?: throw LocationException(LocationErrorKind.NoDecodedText)
        val requested = offset.requestValue
        // Negative offsets are impossible in the Rust usize surface
        // (source.rs); Kotlin Ints require the explicit guard.
        if (requested < 0 || requested > offset.component(index.terminal)) {
            throw LocationException(LocationErrorKind.OutOfBounds)
        }
        val checkpoint = lastCheckpoint(index.checkpoints) { offset.component(it) <= requested }
        return scanToDecoded(utf8, encodingFacts.selected, checkpoint, offset)
    }

    override fun equals(other: Any?): Boolean =
        other is SourceSnapshot &&
            raw.contentEquals(other.raw) &&
            encodingFacts == other.encodingFacts &&
            decoded == other.decoded

    override fun hashCode(): Int {
        var result = raw.contentHashCode()
        result = 31 * result + encodingFacts.hashCode()
        result = 31 * result + (decoded?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SourceSnapshot(len=${raw.size}, digest=${digest.toHex()}, selected=${encodingFacts.selected.asStr()})"
}

/** Immutable decoded boundary index: checkpoints every 256 scalars plus the
 * terminal position (source.rs). */
private class DecodedIndex(
    val checkpoints: List<DecodedPosition>,
    val terminal: DecodedPosition,
)

/** Decodes UTF-16 (LE or BE) with strict surrogate validation (source.rs). */
private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean, limits: SourceLimits): String {
    if (bytes.size % 2 != 0) {
        throw SourceException(
            SourceErrorKind.INVALID_SEQUENCE,
            "source: odd-length UTF-16",
            encoding = if (littleEndian) SourceEncoding.Utf16Le else SourceEncoding.Utf16Be,
            byteOffset = bytes.size - 1,
        )
    }
    val encoding = if (littleEndian) SourceEncoding.Utf16Le else SourceEncoding.Utf16Be
    val output = StringBuilder()
    var offset = 0
    var scalars = 0
    // Accumulated UTF-8 byte length of the decoded output (the Rust
    // String::len, source.rs).
    var decodedBytes = 0
    while (offset < bytes.size) {
        val first = readU16(bytes, offset, littleEndian)
        val scalar: Int
        val consumed: Int
        if (first in 0xd800..0xdbff) {
            if (offset + 3 >= bytes.size) {
                throw SourceException(
                    SourceErrorKind.INVALID_SEQUENCE,
                    "source: truncated UTF-16 surrogate pair at byte $offset",
                    encoding = encoding,
                    byteOffset = offset,
                )
            }
            val second = readU16(bytes, offset + 2, littleEndian)
            if (second !in 0xdc00..0xdfff) {
                throw SourceException(
                    SourceErrorKind.INVALID_SEQUENCE,
                    "source: isolated UTF-16 high surrogate at byte $offset",
                    encoding = encoding,
                    byteOffset = offset,
                )
            }
            val high = first - 0xd800
            val low = second - 0xdc00
            scalar = 0x1_0000 + (high shl 10) + low
            consumed = 4
        } else if (first in 0xdc00..0xdfff) {
            throw SourceException(
                SourceErrorKind.INVALID_SEQUENCE,
                "source: isolated UTF-16 low surrogate at byte $offset",
                encoding = encoding,
                byteOffset = offset,
            )
        } else {
            scalar = first
            consumed = 2
        }
        scalars = checkedAdd(scalars, 1)
        checkLimit("decoded-scalars", scalars, limits.maxDecodedScalars)
        decodedBytes = checkedAdd(decodedBytes, utf8Length(scalar))
        checkLimit("decoded-utf8-bytes", decodedBytes, limits.maxDecodedUtf8Bytes)
        output.appendCodePoint(scalar)
        offset += consumed
    }
    return output.toString()
}

/** Decodes ISO-8859-1 bytes to scalars U+0000..U+00FF (source.rs). */
private fun decodeLatin1(bytes: ByteArray, limits: SourceLimits): String {
    checkLimit("decoded-scalars", bytes.size, limits.maxDecodedScalars)
    val output = StringBuilder()
    // Accumulated UTF-8 byte length of the decoded output (source.rs).
    var decodedBytes = 0
    for (byte in bytes) {
        val scalar = byte.toInt() and 0xff
        decodedBytes = checkedAdd(decodedBytes, utf8Length(scalar))
        checkLimit("decoded-utf8-bytes", decodedBytes, limits.maxDecodedUtf8Bytes)
        output.appendCodePoint(scalar)
    }
    return output.toString()
}

/** Builds the checkpointed decoded boundary index (source.rs). */
private fun buildIndex(
    utf8: ByteArray,
    encoding: SourceEncoding,
    limits: SourceLimits,
): DecodedIndex {
    checkLimit("decoded-utf8-bytes", utf8.size, limits.maxDecodedUtf8Bytes)
    var current = DecodedPosition(0, 0, 0, 0)
    val checkpoints = ArrayList<DecodedPosition>()
    checkpoints.add(current)
    var byteIndex = 0
    while (byteIndex < utf8.size) {
        val scalar = readUtf8Scalar(utf8, byteIndex)
        val rawWidth = rawStepWidth(encoding, scalar)
        current = advance(current, scalar, rawWidth)
        checkLimit("decoded-scalars", current.unicodeScalarOffset, limits.maxDecodedScalars)
        if (current.unicodeScalarOffset % CHECKPOINT_STRIDE == 0) {
            checkpoints.add(current)
        }
        byteIndex += utf8Length(scalar)
    }
    if (checkpoints.last() != current) {
        checkpoints.add(current)
    }
    return DecodedIndex(checkpoints, current)
}

/** Raw byte advance of one scalar under the selected encoding
 * (source.rs; v1 encodings are all exact-boundary). */
private fun rawStepWidth(encoding: SourceEncoding, scalar: Int): Int =
    when (encoding) {
        SourceEncoding.Utf8 -> utf8Length(scalar)
        SourceEncoding.Utf16Le, SourceEncoding.Utf16Be -> Character.charCount(scalar) * 2
        SourceEncoding.Latin1 -> 1
        SourceEncoding.Binary -> error("binary source has no decoded locations")
    }

private fun advance(position: DecodedPosition, scalar: Int, rawWidth: Int): DecodedPosition =
    DecodedPosition(
        rawByte = checkedAdd(position.rawByte, rawWidth),
        decodedUtf8Byte = checkedAdd(position.decodedUtf8Byte, utf8Length(scalar)),
        unicodeScalarOffset = checkedAdd(position.unicodeScalarOffset, 1),
        utf16CodeUnitOffset = checkedAdd(position.utf16CodeUnitOffset, Character.charCount(scalar)),
    )

/** Scans scalars from one checkpoint to an exact raw byte boundary
 * (source.rs). */
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
        position = advance(position, scalar, rawStepWidth(encoding, scalar))
        byteIndex += utf8Length(scalar)
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
 * (source.rs). */
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
        position = advance(position, scalar, rawStepWidth(encoding, scalar))
        byteIndex += utf8Length(scalar)
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

/** Last checkpoint satisfying the predicate (source.rs). */
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

/** Strict UTF-8 validation; returns the first invalid byte offset, or the
 * full length when valid (the Rust from_utf8 error.valid_up_to,
 * source.rs). */
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

/** UTF-8 byte length of one code point (the Rust char.len_utf8). */
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

internal fun checkLimit(name: String, observed: Int, limit: Int) {
    if (observed > limit) {
        throw SourceException(
            SourceErrorKind.RESOURCE_LIMIT,
            "source: $name limit reached ($observed > $limit)",
            name = name,
            observed = observed,
            limit = limit,
        )
    }
}

internal fun checkedAdd(left: Int, right: Int): Int =
    if (left > Int.MAX_VALUE - right) {
        throw SourceException(SourceErrorKind.OFFSET_OVERFLOW, "source: coordinate overflow")
    } else {
        left + right
    }
