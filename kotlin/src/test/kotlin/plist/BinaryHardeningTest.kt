// Binary hardening intent tests: trailer-limit rejection (no false
// Complete), offset/object-reference range hardening, marker exclusion,
// extended sizes, cycles, and non-string dictionary keys.
//
// Data authority:
//   - RFC 0013 §5.11 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md):
//     the mandatory integrity checks run before any object is decoded; a
//     violated check makes the affected construct Recovered rather than
//     inventing facts (RFC 0013 §3: recovery never asserts unproven native
//     semantics).
//   - conformance/vectors/plist-v1.json cases plist.binary-formation.*
//     (header-and-trailer, offset-and-reference, value-integrity, rejected-
//     markers, extended-size-and-cycle) pin the statuses and codes; the
//     hexes below are transcribed VERBATIM from those cases.
//
// This file runs in the verified toolchain gate (kotlin-gates gradlew
// test / the scripts/kotlin-verify-*.ps1 direct path): the toolchain is
// verified and this file is executed.

package plist

import consema.document.FormationStatus
import consema.plist.Document
import consema.plist.PlistProfile
import consema.plist.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinaryHardeningTest {

    /** Vector case plist.binary-formation.header-and-trailer sample 1
     * (plist-v1.json:781): a `bplist01` header recovers with header@1; the
     * remaining constructs are still judged. */
    @Test
    fun headerVersionRejected() {
        val document = formed("62706c697374303150080000000000000101000000000000000100000000000000000000000000000009")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.header@1" })
    }

    /** Vector case plist.binary-formation.header-and-trailer sample 2
     * (plist-v1.json:784): `sortVersion = 0x01` is admitted (RFC 0013
     * §5.10); no false Recovered. */
    @Test
    fun sortVersionOneAccepted() {
        val document = formed("62706c697374303050080000000000010101000000000000000100000000000000000000000000000009")
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(1, document.binaryFacts()!!.trailer.sortVersion)
    }

    /** Vector case plist.binary-formation.header-and-trailer sample 3
     * (plist-v1.json:787): non-zero unused trailer bytes recover with
     * trailer@1 (no false Complete). */
    @Test
    fun trailerUnusedBytesRejected() {
        val document = formed("62706c697374303050080100000000000101000000000000000100000000000000000000000000000009")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.trailer@1" })
        assertFalse(document.hasNativeValue())
    }

    /** Vector case plist.binary-formation.header-and-trailer sample 4
     * (plist-v1.json:790): `numObjects = 0` recovers with trailer@1. */
    @Test
    fun trailerNumObjectsZeroRejected() {
        val document = formed("62706c697374303050080000000000000101000000000000000000000000000000000000000000000009")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.trailer@1" })
    }

    /** Vector case plist.binary-formation.header-and-trailer sample 5
     * (plist-v1.json:793): `topObject >= numObjects` recovers with
     * trailer@1. */
    @Test
    fun trailerTopObjectOutOfRangeRejected() {
        val document = formed("62706c697374303050080000000000000101000000000000000100000000000000010000000000000009")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.trailer@1" })
    }

    /** Vector case plist.binary-formation.header-and-trailer sample 6
     * (plist-v1.json:796): `offsetIntSize = 0` recovers with trailer@1
     * (the offset table cannot be located). */
    @Test
    fun trailerOffsetIntSizeZeroRejected() {
        val document = formed("62706c6973743030514100000000000000080000000000000001000000000000000100000000000000000000000000000009")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.trailer@1" })
    }

    /** Vector case plist.binary-formation.offset-and-reference samples 1-2
     * (plist-v1.json:827-831): offset-table entries outside `[8,
     * offsetTableOffset)` recover with offset-table@1; no false Complete
     * (the vector pins status and code only). */
    @Test
    fun offsetTableEntryOutOfRangeRejected() {
        val below = formed("62706c697374303050500805000000000000010100000000000000020000000000000000000000000000000a")
        assertEquals(FormationStatus.Recovered, below.formationStatus())
        assertTrue(below.diagnostics().any { it.code == "plist.binary.offset-table@1" })

        val atOrAbove = formed("62706c69737430305050080a000000000000010100000000000000020000000000000000000000000000000a")
        assertEquals(FormationStatus.Recovered, atOrAbove.formationStatus())
        assertTrue(atOrAbove.diagnostics().any { it.code == "plist.binary.offset-table@1" })
    }

    /** Vector case plist.binary-formation.offset-and-reference sample 3
     * (plist-v1.json:833): an object reference indexing a nonexistent
     * object recovers with reference@1. */
    @Test
    fun objectReferenceOutOfRangeRejected() {
        val document = formed("62706c6973743030a10250080a000000000000010100000000000000020000000000000000000000000000000b")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.reference@1" })
    }

    /** Vector case plist.binary-formation.rejected-markers (plist-v1.json:
 *): the excluded markers (null, URL, fill, 16-byte integer,
     * UTF-8 string, ordered set, set) recover with marker@1. */
    @Test
    fun excludedMarkersRejected() {
        val samples = listOf(
            "62706c697374303000080000000000000101000000000000000100000000000000000000000000000009",
            "62706c69737430300f080000000000000101000000000000000100000000000000000000000000000009",
            "62706c697374303014080000000000000101000000000000000100000000000000000000000000000009",
            "62706c697374303070080000000000000101000000000000000100000000000000000000000000000009",
            "62706c6973743030b0080000000000000101000000000000000100000000000000000000000000000009",
            "62706c6973743030e0080000000000000101000000000000000100000000000000000000000000000009",
            "62706c69737430300d080000000000000101000000000000000100000000000000000000000000000009",
        )
        for (hex in samples) {
            val document = formed(hex)
            assertEquals(FormationStatus.Recovered, document.formationStatus(), hex)
            assertTrue(document.diagnostics().any { it.code == "plist.binary.marker@1" }, hex)
            assertFalse(document.hasNativeValue(), hex)
        }
    }

    /** Vector case plist.binary-formation.value-integrity sample 1
     * (plist-v1.json:894): a non-finite date payload recovers with date@1. */
    @Test
    fun nonFiniteDateRejected() {
        val document = formed("62706c6973743030337ff8000000000000080000000000000101000000000000000100000000000000000000000000000011")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.date@1" })
    }

    /** Vector case plist.binary-formation.value-integrity sample 2
     * (plist-v1.json:897): a non-string dictionary key recovers with
     * non-string-key@1. */
    @Test
    fun nonStringDictionaryKeyRejected() {
        val document = formed("62706c6973743030d1010210015161080b0d000000000000010100000000000000030000000000000000000000000000000f")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.non-string-key@1" })
    }

    /** Vector case plist.binary-formation.value-integrity sample 3
     * (plist-v1.json:900): an extended-size position holding a non-integer
     * marker recovers with extended-size@1. */
    @Test
    fun extendedSizeMarkerRejected() {
        val document = formed("62706c6973743030af50010101010101010101010101010101010809000000000000010100000000000000020000000000000000000000000000001a")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.extended-size@1" })
    }

    /** Vector case plist.binary-formation.extended-size-and-cycle sample 2
     * (plist-v1.json:870): a self-referencing array recovers with cycle@1
     * (RFC 0013 §5.11 bounded depth plus visited-offset set). */
    @Test
    fun objectCycleRejected() {
        val document = formed("62706c6973743030a10008000000000000010100000000000000010000000000000000000000000000000a")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.cycle@1" })
        assertFalse(document.hasNativeValue())
    }

    /** Vector case plist.binary-formation.uid-matrix sample 4 (plist-v1.json:
     * 658): a UID payload exceeding 32 bits recovers with uid@1. */
    @Test
    fun uidOverflowRejected() {
        val document = formed("62706c697374303084010000000008000000000000010100000000000000010000000000000000000000000000000e")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.uid@1" })
    }

    /** Vector case plist.binary-formation.strings-matrix sample 3
     * (plist-v1.json:612): an ASCII string with a high-bit byte recovers
     * with string@1 (RFC 0013 §5.6 strict check). */
    @Test
    fun asciiStringHighBitRejected() {
        val document = formed("62706c697374303051e908000000000000010100000000000000010000000000000000000000000000000a")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "plist.binary.string@1" })
    }

    /** Vector case plist.binary-formation.extended-size-and-cycle sample 1
     * (plist-v1.json:867): extended sizes are legal and the extended count
     * is an object reference, not an inline value (Complete). */
    @Test
    fun extendedSizeAdmitted() {
        val document = formed("62706c6973743030af101002030405060708090a0b0c0d0e0f10115050505050505050505050505050505008091b1c1d1e1f202122232425262728292a000000000000010100000000000000120000000000000000000000000000002b")
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val root = document.root()
        assertEquals("array", root.kind()?.kindName())
        assertEquals(16, root.arrayElements()!!.size)
    }

    private fun formed(hex: String): Document =
        parse(hexToBytes(hex), PlistProfile.BinaryV1)

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }
}
