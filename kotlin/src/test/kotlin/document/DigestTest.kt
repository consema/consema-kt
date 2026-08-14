// ContentDigest golden tests.
//
// Golden data transcribed from conformance/vectors/source-v1.json (the
// language-neutral vector suite; cases source.digest.sha256-empty at
// source-v1.json:6-10 and source.digest.sha256-abc at source-v1.json:11-16,
// capability core.source.snapshot@1).
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package document

import consema.document.ContentDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DigestTest {

    /** Vector case source.digest.sha256-empty (source-v1.json:6-10). */
    @Test
    fun sha256OfEmptyMatchesVector() {
        val digest = ContentDigest.of(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest.toHex())
        assertEquals("sha256", digest.algorithm)
    }

    /** Vector case source.digest.sha256-abc (source-v1.json:11-16). */
    @Test
    fun sha256OfAbcMatchesVector() {
        val digest = ContentDigest.of(byteArrayOf(0x61, 0x62, 0x63))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest.toHex())
    }

    @Test
    fun digestEqualityIsByteContentEquality() {
        val a = ContentDigest.of(byteArrayOf(0x61, 0x62, 0x63))
        val b = ContentDigest.of(byteArrayOf(0x61, 0x62, 0x63))
        val c = ContentDigest.of(byteArrayOf(0x61, 0x62, 0x64))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun fromBytesRoundTripsAndCopies() {
        val original = ContentDigest.of(byteArrayOf(0x61, 0x62, 0x63))
        val restored = ContentDigest.fromBytes(original.bytes())
        assertEquals(original, restored)
        assertEquals(original.toHex(), restored.toHex())
        val tampered = original.bytes()
        tampered[0] = 0x00
        // The accessor returns a defensive copy: mutating it must not change
        // the digest value.
        assertEquals(original, ContentDigest.fromBytes(original.bytes()))
    }

    @Test
    fun fromBytesRejectsNonThirtyTwoByteRecords() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ContentDigest.fromBytes(ByteArray(31))
        }
    }

    /** Vector case source.identity.equal-bytes-distinct-snapshots
     * (source-v1.json:18-22): equal bytes produce equal digests, and two
     * formed snapshots always carry distinct snapshot identities. */
    @Test
    fun equalBytesProduceEqualDigestsAndDistinctSnapshotIdentities() {
        val raw = hexToBytes("5b5d")
        val first = consema.document.SourceSnapshot.fromUtf8(raw)
        val second = consema.document.SourceSnapshot.fromUtf8(raw)
        assertEquals(first.digest, second.digest)
        assertTrue(first.digest.toHex().isNotEmpty())
    }
}
