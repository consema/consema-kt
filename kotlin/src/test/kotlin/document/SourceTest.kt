// SourceSnapshot behavior tests, transcribed from conformance/vectors/
// source-v1.json (the language-neutral vector suite, capability
// core.source.snapshot@1 / core.source.encoding@1 /
// core.source.decoded-location@1 / core.source.limits@1). Every golden
// case cites its vector case id.
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package document

import consema.document.DecodedOffset
import consema.document.DecodedPosition
import consema.document.EncodingRequest
import consema.document.LocationErrorKind
import consema.document.LocationException
import consema.document.SourceEncoding
import consema.document.SourceErrorKind
import consema.document.SourceException
import consema.document.SourceLimits
import consema.document.SourceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceTest {

    private fun snapshot(rawHex: String, request: EncodingRequest, limits: SourceLimits = SourceLimits.default) =
        SourceSnapshot.fromRaw(hexToBytes(rawHex), request, limits)

    /** Vector case source.encoding.utf8-roundtrip (source-v1.json:24-28):
     * raw bytes, selected encoding, and decoded UTF-8 bytes all round-trip
     * exactly; the BOM stays in the raw source AND in the decoded view as
     * leading U+FEFF (RFC 0003 §4.3). */
    @Test
    fun utf8RoundTrip() {
        val snapshot = snapshot("efbbbf41f09f9880", EncodingRequest.new(SourceEncoding.Utf8))
        assertEquals("efbbbf41f09f9880", bytesToHex(snapshot.bytes()))
        assertEquals("utf-8", snapshot.encodingFacts.selected.asStr())
        assertEquals("efbbbf41f09f9880", bytesToHex(snapshot.decodedText()!!.toByteArray(Charsets.UTF_8)))
        // The BOM is retained as leading U+FEFF in the decoded view (RFC
        // 0003 §4.3), so the decoded text is U+FEFF "A" U+1F600.
        assertEquals("\uFEFFA\uD83D\uDE00", snapshot.decodedText())
    }

    /** Vector case source.encoding.utf16le-roundtrip (source-v1.json:30-34). */
    @Test
    fun utf16LeRoundTrip() {
        val snapshot = snapshot("fffe41003dd800de", EncodingRequest.new(SourceEncoding.Utf16Le))
        assertEquals("fffe41003dd800de", bytesToHex(snapshot.bytes()))
        assertEquals("utf-16le", snapshot.encodingFacts.selected.asStr())
        assertEquals("efbbbf41f09f9880", bytesToHex(snapshot.decodedText()!!.toByteArray(Charsets.UTF_8)))
    }

    /** Vector case source.encoding.utf16be-roundtrip (source-v1.json:36-40). */
    @Test
    fun utf16BeRoundTrip() {
        val snapshot = snapshot("feff0041d83dde00", EncodingRequest.new(SourceEncoding.Utf16Be))
        assertEquals("feff0041d83dde00", bytesToHex(snapshot.bytes()))
        assertEquals("utf-16be", snapshot.encodingFacts.selected.asStr())
        assertEquals("efbbbf41f09f9880", bytesToHex(snapshot.decodedText()!!.toByteArray(Charsets.UTF_8)))
    }

    /** Vector case source.encoding.latin1-roundtrip (source-v1.json:42-46):
     * é (0xe9) and ÿ (0xff) decode to U+00E9/U+00FF, which re-encode as two
     * UTF-8 bytes each. */
    @Test
    fun latin1RoundTrip() {
        val snapshot = snapshot("41e9ff", EncodingRequest.new(SourceEncoding.Latin1))
        assertEquals("41e9ff", bytesToHex(snapshot.bytes()))
        assertEquals("latin-1", snapshot.encodingFacts.selected.asStr())
        assertEquals("41c3a9c3bf", bytesToHex(snapshot.decodedText()!!.toByteArray(Charsets.UTF_8)))
        assertEquals("Aéÿ", snapshot.decodedText())
    }

    /** Vector case source.encoding.binary-roundtrip (source-v1.json:48-52):
     * an opaque binary source has no decoded text. */
    @Test
    fun binaryRoundTrip() {
        val snapshot = snapshot("fffe0000", EncodingRequest.new(SourceEncoding.Binary))
        assertEquals("fffe0000", bytesToHex(snapshot.bytes()))
        assertEquals("binary", snapshot.encodingFacts.selected.asStr())
        assertNull(snapshot.decodedText())
    }

    /** Vector case source.encoding.bom-declaration-conflict
     * (source-v1.json:54-58): a UTF-8 BOM and a UTF-16LE declaration are
     * both present and disagree -> core.source.encoding-conflict@1. */
    @Test
    fun bomDeclarationConflictIsRejected() {
        val error = assertFailsWith<SourceException> {
            snapshot("efbbbf41", EncodingRequest.new(SourceEncoding.Utf8).withDeclaration(SourceEncoding.Utf16Le))
        }
        assertEquals(SourceErrorKind.ENCODING_CONFLICT, error.kind)
        assertEquals("core.source.encoding-conflict@1", error.code)
    }

    /** Vector case source.encoding.declaration-caller-conflict
     * (source-v1.json:60-64). */
    @Test
    fun declarationCallerConflictIsRejected() {
        val error = assertFailsWith<SourceException> {
            snapshot(
                "41",
                EncodingRequest.new(SourceEncoding.Utf8)
                    .withDeclaration(SourceEncoding.Utf8)
                    .withCallerOverride(SourceEncoding.Latin1),
            )
        }
        assertEquals("core.source.encoding-conflict@1", error.code)
    }

    /** Vector case source.encoding.reject-utf32-bom (source-v1.json:66-70):
     * a UTF-32 BOM is recognized but unsupported in v1. */
    @Test
    fun utf32BomIsRejected() {
        val error = assertFailsWith<SourceException> {
            snapshot("fffe0000", EncodingRequest.new(SourceEncoding.Utf8))
        }
        assertEquals(SourceErrorKind.UNSUPPORTED_BOM, error.kind)
        assertEquals("core.source.unsupported-bom@1", error.code)
    }

    /** Vector case source.encoding.reject-utf16-odd (source-v1.json:72-76):
     * odd-length UTF-16 is an invalid sequence. */
    @Test
    fun oddLengthUtf16IsRejected() {
        val error = assertFailsWith<SourceException> {
            snapshot("4100ff", EncodingRequest.new(SourceEncoding.Utf16Le))
        }
        assertEquals(SourceErrorKind.INVALID_SEQUENCE, error.kind)
        assertEquals("core.source.invalid-sequence@1", error.code)
        assertEquals(2, error.byteOffset)
    }

    /** Vector case source.encoding.reject-utf16-surrogate
     * (source-v1.json:78-82): a high surrogate not followed by a low
     * surrogate is an invalid sequence. */
    @Test
    fun isolatedUtf16SurrogateIsRejected() {
        val error = assertFailsWith<SourceException> {
            snapshot("3dd84100", EncodingRequest.new(SourceEncoding.Utf16Le))
        }
        assertEquals(SourceErrorKind.INVALID_SEQUENCE, error.kind)
        assertEquals("core.source.invalid-sequence@1", error.code)
        assertEquals(0, error.byteOffset)
    }

    /** Vector case source.location.utf8-boundaries (source-v1.json:84-88):
     * raw 41 f0 9f 98 80 42 = "A" U+1F600 "B"; raw byte 5 is the boundary
     * after the scalar, raw byte 2 lies inside the scalar, and the UTF-16
     * offset 2 lies inside the surrogate pair. */
    @Test
    fun utf8LocationBoundaries() {
        val snapshot = snapshot("41f09f988042", EncodingRequest.new(SourceEncoding.Utf8))
        assertEquals(
            DecodedPosition(rawByte = 5, decodedUtf8Byte = 5, unicodeScalarOffset = 2, utf16CodeUnitOffset = 3),
            snapshot.decodedPosition(5),
        )
        // Every decoded coordinate maps back to the exact raw byte.
        assertEquals(5, snapshot.rawByteAt(DecodedOffset.Utf8Byte(5)))
        assertEquals(5, snapshot.rawByteAt(DecodedOffset.UnicodeScalar(2)))
        assertEquals(5, snapshot.rawByteAt(DecodedOffset.Utf16CodeUnit(3)))
        // A raw offset inside one encoded scalar is rejected, never rounded.
        val inside = assertFailsWith<LocationException> { snapshot.decodedPosition(2) }
        assertEquals(LocationErrorKind.NotDecodedBoundary, inside.kind)
        // A decoded offset inside one scalar's UTF-8 or UTF-16 representation
        // is rejected.
        val utf16Inside = assertFailsWith<LocationException> { snapshot.rawByteAt(DecodedOffset.Utf16CodeUnit(2)) }
        assertEquals(LocationErrorKind.DecodedOffsetNotBoundary, utf16Inside.kind)
        val utf8Inside = assertFailsWith<LocationException> { snapshot.rawByteAt(DecodedOffset.Utf8Byte(2)) }
        assertEquals(LocationErrorKind.DecodedOffsetNotBoundary, utf8Inside.kind)
        // The terminal boundary is addressable.
        assertEquals(
            DecodedPosition(rawByte = 6, decodedUtf8Byte = 6, unicodeScalarOffset = 3, utf16CodeUnitOffset = 4),
            snapshot.decodedPosition(6),
        )
    }

    /** Vector case source.location.utf16-boundaries (source-v1.json:90-94):
     * raw 41 00 3d d8 00 de 42 00 (UTF-16LE); raw byte 6 is the boundary
     * after the surrogate pair. */
    @Test
    fun utf16LocationBoundaries() {
        val snapshot = snapshot("41003dd800de4200", EncodingRequest.new(SourceEncoding.Utf16Le))
        assertEquals(
            DecodedPosition(rawByte = 6, decodedUtf8Byte = 5, unicodeScalarOffset = 2, utf16CodeUnitOffset = 3),
            snapshot.decodedPosition(6),
        )
        assertEquals(6, snapshot.rawByteAt(DecodedOffset.Utf16CodeUnit(3)))
        val inside = assertFailsWith<LocationException> { snapshot.decodedPosition(3) }
        assertEquals(LocationErrorKind.NotDecodedBoundary, inside.kind)
        val utf16Inside = assertFailsWith<LocationException> { snapshot.rawByteAt(DecodedOffset.Utf16CodeUnit(2)) }
        assertEquals(LocationErrorKind.DecodedOffsetNotBoundary, utf16Inside.kind)
    }

    /** Vector case source.location.binary-no-text (source-v1.json:96-100):
     * binary sources have no decoded coordinates; the expected code is the
     * unregistered name "NoDecodedText". */
    @Test
    fun binarySourceHasNoDecodedLocations() {
        val snapshot = snapshot("00ff", EncodingRequest.new(SourceEncoding.Binary))
        val error = assertFailsWith<LocationException> { snapshot.decodedPosition(0) }
        assertEquals(LocationErrorKind.NoDecodedText, error.kind)
        assertEquals("NoDecodedText", error.kind.name)
        val reverse = assertFailsWith<LocationException> { snapshot.rawByteAt(DecodedOffset.UnicodeScalar(0)) }
        assertEquals(LocationErrorKind.NoDecodedText, reverse.kind)
    }

    /** Vector case source.resource.raw-limit (source-v1.json:156-160):
     * max_raw_bytes 1 against 2 raw bytes -> core.source.resource-limit@1. */
    @Test
    fun rawByteLimitIsEnforcedBeforeDecoding() {
        val error = assertFailsWith<SourceException> {
            snapshot("6162", EncodingRequest.new(SourceEncoding.Utf8), SourceLimits(maxRawBytes = 1, maxDecodedUtf8Bytes = Int.MAX_VALUE, maxDecodedScalars = Int.MAX_VALUE))
        }
        assertEquals(SourceErrorKind.RESOURCE_LIMIT, error.kind)
        assertEquals("core.source.resource-limit@1", error.code)
        assertEquals("raw-bytes", error.name)
    }

    /** Vector case source.resource.decoded-limit (source-v1.json:162-166):
     * é in latin-1 expands to 2 UTF-8 bytes; max_decoded_utf8_bytes 1 ->
     * core.source.resource-limit@1. */
    @Test
    fun decodedUtf8LimitIsEnforced() {
        val error = assertFailsWith<SourceException> {
            snapshot("e9", EncodingRequest.new(SourceEncoding.Latin1), SourceLimits(maxRawBytes = Int.MAX_VALUE, maxDecodedUtf8Bytes = 1, maxDecodedScalars = Int.MAX_VALUE))
        }
        assertEquals(SourceErrorKind.RESOURCE_LIMIT, error.kind)
        assertEquals("core.source.resource-limit@1", error.code)
        assertEquals("decoded-utf8-bytes", error.name)
    }

    /** Byte-exactness across encodings: the raw bytes are never
     * transformed, even when the decoded view differs (RFC 0003 §3). */
    @Test
    fun rawBytesAreByteExactRegardlessOfEncoding() {
        val raw = "fffe41003dd800de"
        val snapshot = snapshot(raw, EncodingRequest.new(SourceEncoding.Utf16Le))
        assertTrue(snapshot.bytes().contentEquals(hexToBytes(raw)))
        assertEquals(snapshot.digest, consema.document.ContentDigest.of(hexToBytes(raw)))
    }

    /** fromBinary never interprets a BOM (source.rs): the UTF-32
     * marker bytes are content. */
    @Test
    fun fromBinaryKeepsBomShapedBytesAsContent() {
        val snapshot = SourceSnapshot.fromBinary(hexToBytes("fffe0000"))
        assertEquals("fffe0000", bytesToHex(snapshot.bytes()))
        assertNull(snapshot.decodedText())
        assertEquals("binary", snapshot.encodingFacts.selected.asStr())
    }

    /** RFC 0003 §4.2: a Binary profile default with any explicit text fact
     * is an EncodingConflict. */
    @Test
    fun binaryDefaultWithTextFactIsAConflict() {
        val error = assertFailsWith<SourceException> {
            snapshot(
                "41",
                EncodingRequest.binary().withCallerOverride(SourceEncoding.Utf8),
            )
        }
        assertEquals("core.source.encoding-conflict@1", error.code)
    }
}
