// The PVCE/1 canonical byte codec.
//
// Byte layout authority: https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs (the frozen Rust
// reference codec), pinned byte-for-byte by conformance/vectors/v1.json:
//   - stream magic is the ASCII octets "PVCE" (lib.rs);
//   - version is minimal unsigned LEB128 1 (lib.rs);
//   - integer sign octets are 0 (zero), 1 (positive), 2 (negative)
//     (lib.rs);
//   - all unsigned lengths/counts/tags are minimal unsigned LEB128
//     (lib.rs);
//   - record tags (lib.rs): 0x00 Null, 0x01 False, 0x02 True,
//     0x10 Integer, 0x11 Decimal, 0x12 BinaryFloat32, 0x13 BinaryFloat64,
//     0x20 String, 0x21 Bytes, 0x30 Date, 0x31 Time, 0x32 LocalDateTime,
//     0x33 OffsetDateTime, 0x40 Sequence, 0x41 Object, 0x42 EntryMapping
//     (0x7f Extended is outside the closed fifteen-kind model and fails as
//     UNKNOWN_CORE_TAG);
//   - default resource limits (lib.rs): 64 MiB stream,
//     depth 256, 1,000,000 nodes, 1,000,000 container entries, 1 MiB
//     integer magnitude, 64 MiB blob.
//
// Golden vectors transcribed into tests: v1.json pvce.null-vector
// "50564345010000", pvce.negative-integer-vector "5056434501100402020100",
// pvce.object-vector "5056434501410a01200201611003010101".
//
// The encoder emits only canonical forms; the decoder rejects every
// non-canonical form (non-minimal varints, non-canonical integers and
// decimals, trailing bytes/payload/fields, invalid UTF-8, duplicate object
// keys, non-String object keys, unknown tags, invalid temporal fields).
// Failures are [PvceException] with the frozen core.pvce.*@1 codes.

package consema.core

import java.math.BigInteger

/** The PVCE/1 stream magic (ASCII "PVCE", https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
internal val PVCE_MAGIC = byteArrayOf('P'.code.toByte(), 'V'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte())

/** The frozen PVCE/1 version (https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
internal const val PVCE_VERSION: ULong = 1uL

// Record tags (https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs).
internal const val TAG_NULL: ULong = 0x00uL
internal const val TAG_FALSE: ULong = 0x01uL
internal const val TAG_TRUE: ULong = 0x02uL
internal const val TAG_INTEGER: ULong = 0x10uL
internal const val TAG_DECIMAL: ULong = 0x11uL
internal const val TAG_FLOAT32: ULong = 0x12uL
internal const val TAG_FLOAT64: ULong = 0x13uL
internal const val TAG_STRING: ULong = 0x20uL
internal const val TAG_BYTES: ULong = 0x21uL
internal const val TAG_DATE: ULong = 0x30uL
internal const val TAG_TIME: ULong = 0x31uL
internal const val TAG_LOCAL_DATE_TIME: ULong = 0x32uL
internal const val TAG_OFFSET_DATE_TIME: ULong = 0x33uL
internal const val TAG_SEQUENCE: ULong = 0x40uL
internal const val TAG_OBJECT: ULong = 0x41uL
internal const val TAG_ENTRY_MAPPING: ULong = 0x42uL

// Default resource limits (https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs).
private const val DEFAULT_MAX_BYTES = 64 shl 20 // 64 MiB
private const val DEFAULT_MAX_DEPTH = 256
private const val DEFAULT_MAX_NODES = 1_000_000
private const val DEFAULT_MAX_CONTAINER_ENTRIES = 1_000_000
private const val DEFAULT_MAX_INTEGER_BYTES = 1 shl 20 // 1 MiB
private const val DEFAULT_MAX_BLOB_BYTES = 64 shl 20 // 64 MiB

/**
 * The strict PVCE/1 decoder resource limits (the Rust DecodeLimits,
 * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). The zero value rejects every
 * stream; use [DecodeLimits.default].
 */
data class DecodeLimits(
    /** Maximum complete stream bytes. */
    val maxBytes: Int,
    /** Maximum nested container depth. */
    val maxDepth: Int,
    /** Maximum total core records. */
    val maxNodes: Int,
    /** Maximum entries in one container. */
    val maxContainerEntries: Int,
    /** Maximum arbitrary integer magnitude bytes. */
    val maxIntegerBytes: Int,
    /** Maximum String or Bytes payload bytes. */
    val maxBlobBytes: Int,
) {
    companion object {
        /** The frozen defaults (64 MiB stream, depth 256, 1,000,000 nodes,
         * 1,000,000 container entries, 1 MiB integer magnitude, 64 MiB
         * blob). */
        val default = DecodeLimits(
            maxBytes = DEFAULT_MAX_BYTES,
            maxDepth = DEFAULT_MAX_DEPTH,
            maxNodes = DEFAULT_MAX_NODES,
            maxContainerEntries = DEFAULT_MAX_CONTAINER_ENTRIES,
            maxIntegerBytes = DEFAULT_MAX_INTEGER_BYTES,
            maxBlobBytes = DEFAULT_MAX_BLOB_BYTES,
        )
    }
}

/**
 * The bounded PVCE/1 encoder resource limits (the Rust EncodeLimits,
 * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). The zero value rejects every
 * value; use [EncodeLimits.default].
 */
data class EncodeLimits(
    /** Maximum complete stream bytes. */
    val maxBytes: Int,
    /** Maximum nested container depth. */
    val maxDepth: Int,
    /** Maximum total core records. */
    val maxNodes: Int,
    /** Maximum entries in one container. */
    val maxContainerEntries: Int,
    /** Maximum arbitrary integer magnitude bytes. */
    val maxIntegerBytes: Int,
    /** Maximum String or Bytes payload bytes. */
    val maxBlobBytes: Int,
) {
    companion object {
        /** The frozen defaults (identical to [DecodeLimits.default]). */
        val default = EncodeLimits(
            maxBytes = DEFAULT_MAX_BYTES,
            maxDepth = DEFAULT_MAX_DEPTH,
            maxNodes = DEFAULT_MAX_NODES,
            maxContainerEntries = DEFAULT_MAX_CONTAINER_ENTRIES,
            maxIntegerBytes = DEFAULT_MAX_INTEGER_BYTES,
            maxBlobBytes = DEFAULT_MAX_BLOB_BYTES,
        )
    }
}

/** Encodes one value as a complete canonical PVCE/1 stream. The bytes are
 * byte-identical to the Rust codec's output. */
fun encodePvce(value: PortableValue): ByteArray {
    val out = ArrayList<Byte>(32)
    for (octet in PVCE_MAGIC) out.add(octet)
    appendVarint(out, PVCE_VERSION)
    encodeRecordInto(out, value)
    return out.toByteArray()
}

/** Encodes one value after measuring it against explicit resource limits
 * (the Rust encode_bounded, https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). It
 * never truncates: exceeding any limit throws a resource-limit exception
 * with no partial output. */
fun encodePvceBounded(value: PortableValue, limits: EncodeLimits): ByteArray {
    val sizer = Sizer(limits)
    val record = sizer.recordSize(value, 0)
    val total = PVCE_MAGIC.size + 1 + record
    if (total > limits.maxBytes) {
        throw resourceLimit("stream-bytes")
    }
    return encodePvce(value)
}

/** Strictly decodes one canonical PVCE/1 stream (RFC 0016 §4.2). The
 * decoder covers the closed fifteen-kind model; only extended (0x7f)
 * records fail with UNKNOWN_CORE_TAG. Non-canonical input fails with the
 * matching kind. */
fun decodePvce(stream: ByteArray, limits: DecodeLimits): PortableValue {
    if (stream.size > limits.maxBytes) {
        throw resourceLimit("stream-bytes")
    }
    val r = Reader(stream, limits)
    val magic = r.take(PVCE_MAGIC.size)
    if (!magic.contentEquals(PVCE_MAGIC)) {
        throw PvceException(PvceErrorKind.INVALID_MAGIC, "core: PVCE/1 stream magic did not match \"PVCE\"")
    }
    val version = r.varint()
    if (version != PVCE_VERSION) {
        throw PvceException(
            PvceErrorKind.UNSUPPORTED_VERSION,
            "core: PVCE/1 unsupported version $version (want 1)",
            value = version.toLong(),
        )
    }
    val record = r.record()
    val value = r.decodeRecord(record.first, record.second, 0)
    if (r.offset != r.bytes.size) {
        throw PvceException(PvceErrorKind.TRAILING_BYTES, "core: PVCE/1 trailing bytes after the root record")
    }
    return value
}

/** Writes one tag-length-prefixed record (the Rust write_record,
 * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
private fun encodeRecordInto(out: MutableList<Byte>, value: PortableValue) {
    val (tag, payload) = encodePayload(value)
    appendVarint(out, tag)
    appendVarint(out, payload.size.toULong())
    for (octet in payload) out.add(octet)
}

/** Builds the payload of one record and returns its tag. */
private fun encodePayload(value: PortableValue): Pair<ULong, ByteArray> {
    val payload = ArrayList<Byte>(16)
    val tag: ULong = when (value) {
        is PvNull -> TAG_NULL
        is PvBoolean -> if (value.value) TAG_TRUE else TAG_FALSE
        is PvString -> {
            appendBlob(payload, value.value.toByteArray(Charsets.UTF_8))
            TAG_STRING
        }
        is PvInteger -> {
            appendIntegerPayload(payload, value.value)
            TAG_INTEGER
        }
        is PvDecimal -> {
            appendIntegerField(payload, value.coefficient)
            appendIntegerField(payload, value.exponent)
            TAG_DECIMAL
        }
        is PvBinaryFloat32 -> {
            val bits = value.bits
            payload.add((bits ushr 24).toByte())
            payload.add((bits ushr 16).toByte())
            payload.add((bits ushr 8).toByte())
            payload.add(bits.toByte())
            TAG_FLOAT32
        }
        is PvBinaryFloat64 -> {
            val bits = value.bits
            for (shift in 56 downTo 0 step 8) {
                payload.add((bits ushr shift).toByte())
            }
            TAG_FLOAT64
        }
        is PvBytes -> {
            appendBlob(payload, value.content())
            TAG_BYTES
        }
        is PvDate -> {
            appendIntegerField(payload, value.year)
            payload.add(value.month.toByte())
            payload.add(value.day.toByte())
            TAG_DATE
        }
        is PvTime -> {
            payload.add(value.hour.toByte())
            payload.add(value.minute.toByte())
            payload.add(value.second.toByte())
            appendDecimalField(payload, value.fractionalSecond)
            TAG_TIME
        }
        is PvLocalDateTime -> {
            appendDateField(payload, value.date)
            appendTimeField(payload, value.time)
            TAG_LOCAL_DATE_TIME
        }
        is PvOffsetDateTime -> {
            appendDateField(payload, value.local.date)
            appendTimeField(payload, value.local.time)
            appendIntegerField(payload, BigInteger.valueOf(value.offsetSeconds.toLong()))
            TAG_OFFSET_DATE_TIME
        }
        is PvArray -> {
            appendVarint(payload, value.size().toULong())
            for (item in value.items) {
                encodeRecordInto(payload, item)
            }
            TAG_SEQUENCE
        }
        is PvObject -> {
            appendVarint(payload, value.size().toULong())
            for (entry in value.entries) {
                encodeRecordInto(payload, PvString(entry.key))
                encodeRecordInto(payload, entry.value)
            }
            TAG_OBJECT
        }
        is PvEntryMapping -> {
            appendVarint(payload, value.size().toULong())
            for (entry in value.entries) {
                encodeRecordInto(payload, entry.key)
                encodeRecordInto(payload, entry.value)
            }
            TAG_ENTRY_MAPPING
        }
    }
    return tag to payload.toByteArray()
}

/** Writes the sign octet, the magnitude length varint, and the minimal
 * big-endian magnitude (the Rust encode_integer_payload,
 * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
private fun appendIntegerPayload(out: MutableList<Byte>, value: BigInteger) {
    out.add(
        when (value.signum()) {
            -1 -> 2
            0 -> 0
            else -> 1
        }.toByte(),
    )
    val magnitude = minimalMagnitude(value)
    appendVarint(out, magnitude.size.toULong())
    for (octet in magnitude) out.add(octet)
}

/** Returns the minimal big-endian magnitude octets of [value]. */
internal fun minimalMagnitude(value: BigInteger): ByteArray {
    // Zero has no magnitude bytes (the Rust BigInt::magnitude() is empty);
    // Java's toByteArray() yields a single zero octet, which would encode a
    // non-canonical integer (sign 0 with a non-empty magnitude).
    if (value.signum() == 0) return ByteArray(0)
    var magnitude = value.abs().toByteArray()
    var start = 0
    while (start < magnitude.size - 1 && magnitude[start] == 0.toByte()) {
        start++
    }
    if (start > 0) {
        magnitude = magnitude.copyOfRange(start, magnitude.size)
    }
    return magnitude
}

/** Writes a length-prefixed integer payload (the Rust encode_integer_field,
 * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
private fun appendIntegerField(out: MutableList<Byte>, value: BigInteger) {
    val field = ArrayList<Byte>(8)
    appendIntegerPayload(field, value)
    appendVarint(out, field.size.toULong())
    for (octet in field) out.add(octet)
}

/** Writes a length-prefixed decimal payload (the Rust encode_decimal_field,
 * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
private fun appendDecimalField(out: MutableList<Byte>, value: PvDecimal) {
    val field = ArrayList<Byte>(16)
    appendIntegerField(field, value.coefficient)
    appendIntegerField(field, value.exponent)
    appendVarint(out, field.size.toULong())
    for (octet in field) out.add(octet)
}

/** Writes a length-prefixed date payload (the Rust encode_date_field,
 * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
private fun appendDateField(out: MutableList<Byte>, value: PvDate) {
    val field = ArrayList<Byte>(8)
    appendIntegerField(field, value.year)
    field.add(value.month.toByte())
    field.add(value.day.toByte())
    appendVarint(out, field.size.toULong())
    for (octet in field) out.add(octet)
}

/** Writes a length-prefixed time payload (the Rust encode_time_field,
 * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
private fun appendTimeField(out: MutableList<Byte>, value: PvTime) {
    val field = ArrayList<Byte>(16)
    field.add(value.hour.toByte())
    field.add(value.minute.toByte())
    field.add(value.second.toByte())
    appendDecimalField(field, value.fractionalSecond)
    appendVarint(out, field.size.toULong())
    for (octet in field) out.add(octet)
}

/** Writes a length-prefixed byte string (the Rust encode_blob,
 * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
private fun appendBlob(out: MutableList<Byte>, bytes: ByteArray) {
    appendVarint(out, bytes.size.toULong())
    for (octet in bytes) out.add(octet)
}

/** Writes the minimal unsigned LEB128 encoding of [value] (the Rust
 * write_varint, https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
private fun appendVarint(out: MutableList<Byte>, value: ULong) {
    var remaining = value
    while (true) {
        var octet = (remaining and 0x7fuL).toByte()
        remaining = remaining shr 7
        if (remaining != 0uL) {
            octet = (octet.toInt() or 0x80).toByte()
        }
        out.add(octet)
        if (remaining == 0uL) {
            return
        }
    }
}

/** Returns the encoded length of [value] as a minimal unsigned LEB128 (the
 * Rust const varint_size, https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
internal fun varintSize(value: ULong): Int {
    var size = 1
    var remaining = value
    while (remaining >= 0x80uL) {
        remaining = remaining shr 7
        size++
    }
    return size
}

/** The strict streaming decoder over one PVCE/1 stream or payload (the Rust
 * Reader, https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
internal class Reader(val bytes: ByteArray, val limits: DecodeLimits) {
    var offset = 0
        private set

    /** The accumulated core-record count; shared between a reader and its
     * child readers so the value-nodes limit counts the whole stream. */
    var nodes = 0

    /** Consumes [count] octets. */
    fun take(count: Int): ByteArray {
        if (count < 0 || offset + count > bytes.size) {
            throw PvceException(PvceErrorKind.UNEXPECTED_END, "core: PVCE/1 input ended inside a required field")
        }
        val value = bytes.copyOfRange(offset, offset + count)
        offset += count
        return value
    }

    /** Consumes one octet. */
    fun octet(): Byte = take(1)[0]

    /** Reads one unsigned varint, rejecting non-minimal encodings and
     * 64-bit overflow (the Rust Reader::varint, lib.rs). */
    fun varint(): ULong {
        val start = offset
        var value = 0uL
        var shift = 0
        while (shift <= 63) {
            val octet = octet()
            val low = (octet.toInt() and 0x7f).toULong()
            if (shift == 63 && low > 1uL) {
                throw PvceException(PvceErrorKind.VARINT_OVERFLOW, "core: PVCE/1 unsigned varint exceeded 64 bits")
            }
            value = value or (low shl shift)
            if (octet.toInt() and 0x80 == 0) {
                if (offset - start > 1 && low == 0uL) {
                    throw PvceException(
                        PvceErrorKind.NON_CANONICAL_VARINT,
                        "core: PVCE/1 non-canonical (non-minimal) unsigned varint",
                    )
                }
                return value
            }
            shift += 7
        }
        throw PvceException(PvceErrorKind.VARINT_OVERFLOW, "core: PVCE/1 unsigned varint exceeded 64 bits")
    }

    /** Reads one varint length and enforces the named limit (the Rust
     * Reader::length, lib.rs). */
    fun length(limit: Int, name: String): Int {
        val value = varint()
        if (value > limit.toULong()) {
            throw resourceLimit(name)
        }
        return value.toInt()
    }

    /** Reads one tag-length-prefixed record (the Rust Reader::record,
     * lib.rs). The payload length is bounded by MaxBytes
     * ("record-bytes"), exactly as in the Rust decoder. */
    fun record(): Pair<ULong, ByteArray> {
        val tag = varint()
        val n = length(limits.maxBytes, "record-bytes")
        return tag to take(n)
    }

    /** Decodes one record whose payload is already delimited (the Rust
     * decode_core_record, https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs): it
     * enforces the depth and node limits, decodes the payload, and rejects
     * trailing payload bytes. */
    fun decodeRecord(tag: ULong, payload: ByteArray, depth: Int): PortableValue {
        if (depth > limits.maxDepth) {
            throw resourceLimit("nesting-depth")
        }
        nodes++
        if (nodes > limits.maxNodes) {
            throw resourceLimit("value-nodes")
        }
        val child = Reader(payload, limits)
        child.nodes = nodes
        val value = child.decodePayload(tag, depth)
        if (child.offset != child.bytes.size) {
            throw PvceException(
                PvceErrorKind.TRAILING_PAYLOAD,
                "core: PVCE/1 trailing payload bytes after record tag 0x${tag.toString(16)}",
                value = tag.toLong(),
            )
        }
        nodes = child.nodes
        return value
    }

    /** Decodes the payload of one record with the given tag. */
    private fun decodePayload(tag: ULong, depth: Int): PortableValue {
        return when (tag) {
            TAG_NULL -> {
                requireEmptyPayload(tag)
                PvNull
            }
            TAG_FALSE -> {
                requireEmptyPayload(tag)
                PvBoolean(false)
            }
            TAG_TRUE -> {
                requireEmptyPayload(tag)
                PvBoolean(true)
            }
            TAG_INTEGER -> decodeInteger()
            TAG_DECIMAL -> decodeDecimal()
            TAG_FLOAT32 -> {
                if (bytes.size != 4) {
                    throw invalidPayload(tag)
                }
                val octets = take(4)
                val bits = ((octets[0].toInt() and 0xff) shl 24) or
                    ((octets[1].toInt() and 0xff) shl 16) or
                    ((octets[2].toInt() and 0xff) shl 8) or
                    (octets[3].toInt() and 0xff)
                PvBinaryFloat32(bits)
            }
            TAG_FLOAT64 -> {
                if (bytes.size != 8) {
                    throw invalidPayload(tag)
                }
                val octets = take(8)
                var bits = 0L
                for (octet in octets) {
                    bits = (bits shl 8) or (octet.toLong() and 0xff)
                }
                PvBinaryFloat64(bits)
            }
            TAG_STRING -> decodeUtf8Strict(decodeBlob()).let { PvString(it) }
            TAG_BYTES -> PvBytes.of(decodeBlob())
            TAG_DATE -> decodeDate()
            TAG_TIME -> decodeTime()
            TAG_LOCAL_DATE_TIME -> PvLocalDateTime(decodeDateField(), decodeTimeField())
            TAG_OFFSET_DATE_TIME -> {
                val date = decodeDateField()
                val time = decodeTimeField()
                val offset = decodeIntegerField()
                val offsetSeconds = offsetToI32(offset)
                    ?: throw PvceException(
                        PvceErrorKind.INVALID_TEMPORAL,
                        "core: PVCE/1 date, time, or offset fields are outside the supported ranges",
                    )
                try {
                    PvOffsetDateTime.of(PvLocalDateTime(date, time), offsetSeconds)
                } catch (e: InvalidTemporalException) {
                    throw PvceException(
                        PvceErrorKind.INVALID_TEMPORAL,
                        "core: PVCE/1 date, time, or offset fields are outside the supported ranges",
                    )
                }
            }
            TAG_SEQUENCE -> {
                val count = length(limits.maxContainerEntries, "container-entries")
                val items = ArrayList<PortableValue>(count)
                repeat(count) {
                    val child = record()
                    items.add(decodeRecord(child.first, child.second, depth + 1))
                }
                PvArray(items)
            }
            TAG_OBJECT -> {
                val count = length(limits.maxContainerEntries, "container-entries")
                val builder = ObjectBuilder()
                repeat(count) {
                    val keyRecord = record()
                    if (keyRecord.first != TAG_STRING) {
                        throw PvceException(
                            PvceErrorKind.OBJECT_KEY_NOT_STRING,
                            "core: PVCE/1 object key record is not a String record",
                        )
                    }
                    val keyValue = decodeRecord(keyRecord.first, keyRecord.second, depth + 1)
                    val valueRecord = record()
                    val item = decodeRecord(valueRecord.first, valueRecord.second, depth + 1)
                    // Decoded key values are always non-null Strings, so the
                    // only insert failure is a duplicate key.
                    try {
                        builder.insert((keyValue as PvString).value, item)
                    } catch (e: DuplicateKeyException) {
                        throw PvceException(
                            PvceErrorKind.DUPLICATE_OBJECT_KEY,
                            "core: PVCE/1 object contains a duplicate key",
                        )
                    }
                }
                builder.build()
            }
            TAG_ENTRY_MAPPING -> {
                val count = length(limits.maxContainerEntries, "container-entries")
                val builder = EntryMappingBuilder()
                repeat(count) {
                    val keyRecord = record()
                    val key = decodeRecord(keyRecord.first, keyRecord.second, depth + 1)
                    val valueRecord = record()
                    val value = decodeRecord(valueRecord.first, valueRecord.second, depth + 1)
                    builder.push(key, value)
                }
                builder.build()
            }
            else -> throw PvceException(
                PvceErrorKind.UNKNOWN_CORE_TAG,
                "core: PVCE/1 unknown core tag 0x${tag.toString(16)}",
                value = tag.toLong(),
            )
        }
    }

    private fun requireEmptyPayload(tag: ULong) {
        if (bytes.isNotEmpty()) {
            throw invalidPayload(tag)
        }
    }

    private fun invalidPayload(tag: ULong): PvceException =
        PvceException(
            PvceErrorKind.INVALID_PAYLOAD,
            "core: PVCE/1 invalid payload for record tag 0x${tag.toString(16)}",
            value = tag.toLong(),
        )

    /** Decodes one integer payload (the Rust decode_integer_payload,
     * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
    private fun decodeInteger(): PvInteger {
        val sign = octet()
        val n = length(limits.maxIntegerBytes, "integer-bytes")
        val magnitude = take(n)
        return when {
            sign == 0.toByte() && magnitude.isEmpty() -> PvInteger.ZERO
            sign == 0.toByte() -> throw PvceException(
                PvceErrorKind.NON_CANONICAL_INTEGER,
                "core: PVCE/1 non-canonical integer representation",
            )
            sign != 1.toByte() && sign != 2.toByte() -> throw PvceException(
                PvceErrorKind.INVALID_INTEGER_SIGN,
                "core: PVCE/1 invalid integer sign octet ${sign.toInt()}",
                value = (sign.toInt() and 0xff).toLong(),
            )
            magnitude.isEmpty() || magnitude[0] == 0.toByte() -> throw PvceException(
                PvceErrorKind.NON_CANONICAL_INTEGER,
                "core: PVCE/1 non-canonical integer representation",
            )
            else -> {
                var value = BigInteger(1, magnitude)
                if (sign == 2.toByte()) {
                    value = value.negate()
                }
                PvInteger.of(value)
            }
        }
    }

    /** Decodes one length-prefixed integer field (the Rust
     * decode_integer_field, https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
    private fun decodeIntegerField(): PvInteger {
        val n = length(limits.maxIntegerBytes + 16, "integer-field")
        val payload = take(n)
        val field = Reader(payload, limits)
        field.nodes = nodes
        val value = field.decodeInteger()
        if (field.offset != field.bytes.size) {
            throw PvceException(PvceErrorKind.TRAILING_FIELD, "core: PVCE/1 trailing bytes after a nested field")
        }
        nodes = field.nodes
        return value
    }

    /** Decodes one decimal payload and rejects unnormalized
     * coefficient/exponent pairs (the Rust decode_decimal_payload,
     * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
    private fun decodeDecimal(): PvDecimal {
        val coefficient = decodeIntegerField()
        val exponent = decodeIntegerField()
        val decimal = PvDecimal.of(coefficient.value, exponent.value)
        if (decimal.coefficient != coefficient.value || decimal.exponent != exponent.value) {
            throw PvceException(
                PvceErrorKind.NON_CANONICAL_DECIMAL,
                "core: PVCE/1 non-canonical decimal representation",
            )
        }
        return decimal
    }

    /** Decodes one length-prefixed decimal field (the Rust
     * decode_decimal_field, https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
    private fun decodeDecimalField(): PvDecimal {
        val n = length(limits.maxIntegerBytes * 2 + 32, "decimal-field")
        val payload = take(n)
        val field = Reader(payload, limits)
        field.nodes = nodes
        val value = field.decodeDecimal()
        if (field.offset != field.bytes.size) {
            throw PvceException(PvceErrorKind.TRAILING_FIELD, "core: PVCE/1 trailing bytes after a nested field")
        }
        nodes = field.nodes
        return value
    }

    /** Decodes one date payload (the Rust decode_date_payload,
     * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). Invalid calendar fields map
     * to INVALID_TEMPORAL. */
    private fun decodeDate(): PvDate {
        val year = decodeIntegerField()
        val month = octet()
        val day = octet()
        return try {
            PvDate.of(year.value, month.toInt() and 0xff, day.toInt() and 0xff)
        } catch (e: InvalidTemporalException) {
            throw PvceException(
                PvceErrorKind.INVALID_TEMPORAL,
                "core: PVCE/1 date, time, or offset fields are outside the supported ranges",
            )
        }
    }

    /** Decodes one length-prefixed date field (the Rust decode_date_field,
     * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
    private fun decodeDateField(): PvDate {
        val n = length(limits.maxIntegerBytes + 32, "date-field")
        val payload = take(n)
        val field = Reader(payload, limits)
        field.nodes = nodes
        val value = field.decodeDate()
        if (field.offset != field.bytes.size) {
            throw PvceException(PvceErrorKind.TRAILING_FIELD, "core: PVCE/1 trailing bytes after a nested field")
        }
        nodes = field.nodes
        return value
    }

    /** Decodes one time payload (the Rust decode_time_payload,
     * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
    private fun decodeTime(): PvTime {
        val hour = octet()
        val minute = octet()
        val second = octet()
        val fraction = decodeDecimalField()
        return try {
            PvTime.of(hour.toInt() and 0xff, minute.toInt() and 0xff, second.toInt() and 0xff, fraction)
        } catch (e: InvalidTemporalException) {
            throw PvceException(
                PvceErrorKind.INVALID_TEMPORAL,
                "core: PVCE/1 date, time, or offset fields are outside the supported ranges",
            )
        }
    }

    /** Decodes one length-prefixed time field (the Rust decode_time_field,
     * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
    private fun decodeTimeField(): PvTime {
        val n = length(limits.maxIntegerBytes * 2 + 64, "time-field")
        val payload = take(n)
        val field = Reader(payload, limits)
        field.nodes = nodes
        val value = field.decodeTime()
        if (field.offset != field.bytes.size) {
            throw PvceException(PvceErrorKind.TRAILING_FIELD, "core: PVCE/1 trailing bytes after a nested field")
        }
        nodes = field.nodes
        return value
    }

    /** Decodes one length-prefixed byte string (the Rust decode_blob,
     * https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
    private fun decodeBlob(): ByteArray {
        val n = length(limits.maxBlobBytes, "blob-bytes")
        return take(n)
    }
}

/** Converts a decoded offset integer to an Int (the Rust
 * to_i64().and_then(i32::try_from), https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
private fun offsetToI32(offset: PvInteger): Int? {
    val value = offset.value
    if (value.bitLength() > 31) {
        return null
    }
    return value.toInt()
}

/** Measures a value's canonical PVCE/1 stream size under encode limits
 * without producing bytes (the Rust Sizer, https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs). */
private class Sizer(private val limits: EncodeLimits) {
    private var nodes = 0

    /** Returns the encoded size of one record at the given depth. */
    fun recordSize(value: PortableValue, depth: Int): Int {
        if (depth > limits.maxDepth) {
            throw resourceLimit("nesting-depth")
        }
        nodes++
        if (nodes > limits.maxNodes) {
            throw resourceLimit("value-nodes")
        }
        val (tag, payload) = payloadSize(value, depth)
        return varintSize(tag) + varintSize(payload.toULong()) + payload
    }

    private fun payloadSize(value: PortableValue, depth: Int): Pair<ULong, Int> {
        return when (value) {
            is PvNull -> TAG_NULL to 0
            is PvBoolean -> (if (value.value) TAG_TRUE else TAG_FALSE) to 0
            is PvString -> {
                val n = value.value.toByteArray(Charsets.UTF_8).size
                if (n > limits.maxBlobBytes) {
                    throw resourceLimit("blob-bytes")
                }
                TAG_STRING to (varintSize(n.toULong()) + n)
            }
            is PvInteger -> {
                val n = minimalMagnitude(value.value).size
                if (n > limits.maxIntegerBytes) {
                    throw resourceLimit("integer-bytes")
                }
                TAG_INTEGER to (1 + varintSize(n.toULong()) + n)
            }
            is PvDecimal ->
                TAG_DECIMAL to (integerFieldSize(value.coefficient) + integerFieldSize(value.exponent))
            is PvBinaryFloat32 -> TAG_FLOAT32 to 4
            is PvBinaryFloat64 -> TAG_FLOAT64 to 8
            is PvBytes -> {
                val n = value.content().size
                if (n > limits.maxBlobBytes) {
                    throw resourceLimit("blob-bytes")
                }
                TAG_BYTES to (varintSize(n.toULong()) + n)
            }
            is PvDate -> TAG_DATE to (integerFieldSize(value.year) + 2)
            is PvTime -> TAG_TIME to (3 + decimalFieldSize(value.fractionalSecond))
            is PvLocalDateTime ->
                TAG_LOCAL_DATE_TIME to (dateFieldSize(value.date) + timeFieldSize(value.time))
            is PvOffsetDateTime ->
                TAG_OFFSET_DATE_TIME to
                    (dateFieldSize(value.local.date) +
                        timeFieldSize(value.local.time) +
                        integerFieldSize(BigInteger.valueOf(value.offsetSeconds.toLong())))
            is PvArray -> {
                val n = value.size()
                if (n > limits.maxContainerEntries) {
                    throw resourceLimit("container-entries")
                }
                var payload = varintSize(n.toULong())
                for (item in value.items) {
                    payload += recordSize(item, depth + 1)
                }
                TAG_SEQUENCE to payload
            }
            is PvObject -> {
                val n = value.size()
                if (n > limits.maxContainerEntries) {
                    throw resourceLimit("container-entries")
                }
                var payload = varintSize(n.toULong())
                for (entry in value.entries) {
                    // Object keys are encoded as String records and count as
                    // nodes, exactly as in the Rust Sizer.
                    payload += recordSize(PvString(entry.key), depth + 1)
                    payload += recordSize(entry.value, depth + 1)
                }
                TAG_OBJECT to payload
            }
            is PvEntryMapping -> {
                val n = value.size()
                if (n > limits.maxContainerEntries) {
                    throw resourceLimit("container-entries")
                }
                var payload = varintSize(n.toULong())
                for (entry in value.entries) {
                    payload += recordSize(entry.key, depth + 1)
                    payload += recordSize(entry.value, depth + 1)
                }
                TAG_ENTRY_MAPPING to payload
            }
        }
    }

    private fun integerFieldSize(value: java.math.BigInteger): Int {
        val n = minimalMagnitude(value).size
        if (n > limits.maxIntegerBytes) {
            throw resourceLimit("integer-bytes")
        }
        val payload = 1 + varintSize(n.toULong()) + n
        return varintSize(payload.toULong()) + payload
    }

    private fun decimalFieldSize(value: PvDecimal): Int {
        val payload = integerFieldSize(value.coefficient) + integerFieldSize(value.exponent)
        return varintSize(payload.toULong()) + payload
    }

    private fun dateFieldSize(value: PvDate): Int {
        val payload = integerFieldSize(value.year) + 2
        return varintSize(payload.toULong()) + payload
    }

    private fun timeFieldSize(value: PvTime): Int {
        val payload = 3 + decimalFieldSize(value.fractionalSecond)
        return varintSize(payload.toULong()) + payload
    }
}
