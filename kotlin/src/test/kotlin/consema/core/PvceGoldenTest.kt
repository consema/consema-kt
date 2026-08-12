// PVCE/1 golden byte tests — intent documents.
//
// The hex vectors below are transcribed VERBATIM from
// conformance/vectors/v1.json (the language-neutral machine-readable
// vectors shared by all implementations):
//   - pvce.null-vector           "50564345010000"
//   - pvce.negative-integer-vector "5056434501100402020100"
//   - pvce.object-vector         "5056434501410a01200201611003010101"
//   - pvce.reject-nonminimal-varint input "5056434581000000" ->
//     NonCanonicalVarint
// These tests run once the toolchain is ready (START GATE,
// docs/multi-language-implementation-plan.md §7).

package consema.core

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private fun hexBytes(text: String): ByteArray {
    require(text.length % 2 == 0)
    return ByteArray(text.length / 2) { index ->
        ((Character.digit(text[index * 2], 16) shl 4) or
            Character.digit(text[index * 2 + 1], 16)).toByte()
    }
}

class PvceGoldenTest {

    @Test
    fun nullVector() {
        // conformance/vectors/v1.json pvce.null-vector.
        assertContentEquals(hexBytes("50564345010000"), encodePvce(PvNull))
        assertEquals(PvNull, decodePvce(hexBytes("50564345010000"), DecodeLimits.default))
    }

    @Test
    fun negativeIntegerVector() {
        // conformance/vectors/v1.json pvce.negative-integer-vector.
        val value = PvInteger(BigInteger("-256"))
        assertContentEquals(hexBytes("5056434501100402020100"), encodePvce(value))
        assertEquals(value, decodePvce(hexBytes("5056434501100402020100"), DecodeLimits.default))
    }

    @Test
    fun objectVector() {
        // conformance/vectors/v1.json pvce.object-vector: {"a": {"integer": "1"}}.
        val value = ObjectBuilder()
            .insert("a", PvInteger(BigInteger.ONE))
            .build()
        assertContentEquals(hexBytes("5056434501410a01200201611003010101"), encodePvce(value))
        val decoded = decodePvce(hexBytes("5056434501410a01200201611003010101"), DecodeLimits.default)
        assertEquals(true, equal(value, decoded))
    }

    @Test
    fun rejectNonMinimalVarint() {
        // conformance/vectors/v1.json pvce.reject-nonminimal-varint:
        // "5056434581000000" fails as NonCanonicalVarint.
        val error = assertFailsWith<PvceException> {
            decodePvce(hexBytes("5056434581000000"), DecodeLimits.default)
        }
        assertEquals(PvceErrorKind.NON_CANONICAL_VARINT, error.kind)
        assertEquals("core.pvce.non-canonical-varint@1", error.code)
    }

    @Test
    fun everyKindRoundTrips() {
        val date = PvDate.of(BigInteger("-12345"), 2, 28)
        val time = PvTime.of(23, 59, 58, PvDecimal.of(BigInteger("125"), BigInteger("-3")))
        val local = PvLocalDateTime(date, time)
        val offset = PvOffsetDateTime.of(local, -23 * 60 * 60)
        val mapping = EntryMappingBuilder()
            .push(PvBoolean(true), PvNull)
            .build()
        val objectValue = ObjectBuilder()
            .insert("a", PvInteger(BigInteger.ONE))
            .insert("b", PvString("中"))
            .build()
        val sequence = PvArray(
            listOf(
                PvNull,
                PvBoolean(false),
                PvInteger(BigInteger("123456789012345678901234567890")),
                PvDecimal.of(BigInteger.ONE, BigInteger("-999")),
                PvBinaryFloat32(0x7fc00001),
                PvBinaryFloat64(Long.MIN_VALUE),
                PvString("é"),
                PvBytes.of(byteArrayOf(0, 0xff.toByte())),
                date,
                time,
                local,
                offset,
                mapping,
            ),
        )
        val bytes = encodePvce(sequence)
        assertEquals(true, equal(sequence, decodePvce(bytes, DecodeLimits.default)))
        // The round-trip is byte-stable.
        assertContentEquals(bytes, encodePvce(decodePvce(bytes, DecodeLimits.default)))
    }

    @Test
    fun strictEqualityAndHash() {
        // conformance/vectors/v1.json value.decimal-normalization:
        // "1.00" and "10e-1" are strictly equal with equal hashes.
        val a = PvDecimal.of(BigInteger("100"), BigInteger("-2"))
        val b = PvDecimal.of(BigInteger("10"), BigInteger("-1"))
        assertEquals(PvDecimal.of(BigInteger.ONE, BigInteger.ZERO), a)
        assertEquals(a, b)
        assertEquals(hash(a), hash(b))

        // conformance/vectors/v1.json value.float-signed-zero: +0.0 != -0.0.
        val plusZero = PvBinaryFloat64(0x0000000000000000L)
        val minusZero = PvBinaryFloat64(Long.MIN_VALUE)
        assertEquals(false, equal(plusZero, minusZero))
        assertEquals(true, equal(plusZero, plusZero))
    }

    @Test
    fun encodeBlobLimit() {
        // conformance/vectors/v1.json pvce.encode-blob-limit: string "12345"
        // with max_blob_bytes 4 fails as ResourceLimit.
        val limits = EncodeLimits.default.copy(maxBlobBytes = 4)
        val error = assertFailsWith<PvceException> {
            encodePvceBounded(PvString("12345"), limits)
        }
        assertEquals(PvceErrorKind.RESOURCE_LIMIT, error.kind)
        assertEquals("blob-bytes", error.field)
    }
}
