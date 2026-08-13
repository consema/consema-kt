// Cross-representation conversion tests of the plist family: byte-exact
// XML -> binary and binary -> XML conversion, canonical round-trip
// fixed points, and the plist.conversion.* vector facts.
//
// Data authority:
//   - RFC 0013 §7 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md:512-538):
//     conversion is exact when every native fact is expressible in the
//     target representation and fails atomically otherwise.
//   - consema-rs/consema-plist/src/document.rs:494-551 (XML -> binary writer)
//     and document.rs:559-593 (binary -> XML writer) pin the canonical
//     outputs; consema-go/go/plist is a cross-reference only.
//   - conformance/vectors/plist-v1.json cases plist.conversion.* pin the
//     native-model facts; the minimal-document vector hex
//     (plist.binary-formation.minimal-document, plist-v1.json:451-467) is
//     the byte-exact target of the empty-string conversion.
//
// This file runs in the verified toolchain gate (kotlin-gates gradlew
// test / the scripts/kotlin-verify-*.ps1 direct path): the toolchain is
// verified and this file is executed.

package plist

import consema.document.FormationStatus
import consema.plist.Document
import consema.plist.PlistProfile
import consema.plist.convertTo
import consema.plist.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversionTest {

    private fun xml(source: String): Document =
        parse(source.toByteArray(Charsets.UTF_8), PlistProfile.XmlV1)

    private fun binary(hex: String): Document = parse(hexToBytes(hex), PlistProfile.BinaryV1)

    private fun Document.hex(): String = render().joinToString("") { "%02x".format(it) }

    /** The empty-string XML converts to the exact minimal binary document
     * (plist-v1.json:451-467): one 0x50 object, offset table [8], and the
     * 32-byte trailer with numObjects 1, topObject 0, offsetTableOffset 9. */
    @Test
    fun xmlToBinaryMinimalByteExact() {
        val document = xml("<plist version=\"1.0\"><string></string></plist>")
        assertEquals(FormationStatus.Complete, document.formationStatus())

        val converted = document.convertTo(PlistProfile.BinaryV1)
        assertTrue(converted.report.representationChanged())
        assertEquals(FormationStatus.Complete, converted.document.formationStatus())
        assertEquals(
            "62706c697374303050080000000000000101000000000000000100000000000000000000000000000009",
            converted.document.hex(),
        )
        // The converted snapshot is itself the minimal vector document.
        assertEquals(1L, converted.document.binaryFacts()!!.trailer.numObjects)
        assertEquals(0L, converted.document.binaryFacts()!!.trailer.topObject)
        assertEquals(9L, converted.document.binaryFacts()!!.trailer.offsetTableOffset)
    }

    /** The minimal binary document converts to the canonical XML render,
     * byte-exact (document.rs:559-593: header, doctype, root scalar at
     * depth 0, closing plist tag). */
    @Test
    fun binaryToXmlMinimalByteExact() {
        val document = binary("62706c697374303050080000000000000101000000000000000100000000000000000000000000000009")
        assertEquals(FormationStatus.Complete, document.formationStatus())

        val converted = document.convertTo(PlistProfile.XmlV1)
        assertTrue(converted.report.representationChanged())
        assertEquals(FormationStatus.Complete, converted.document.formationStatus())
        val expected =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n" +
                "<string></string>\n" +
                "</plist>\n"
        assertEquals(expected, converted.document.render().toString(Charsets.UTF_8))
        // The conversion output reparses Complete (RFC 0013 §7 closure).
        val reparsed = parse(converted.document.render(), PlistProfile.XmlV1)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus())
        assertEquals("", reparsed.root().asString()!!.toUnicode())
    }

    /** XML -> binary -> XML is a byte-exact fixed point after the first
     * conversion: the canonical XML render is stable (RFC 0013 §7). */
    @Test
    fun xmlToBinaryToXmlRoundTripByteExact() {
        val source =
            "<plist version=\"1.0\"><dict>" +
                "<key>name</key><string>Consema</string>" +
                "<key>count</key><integer>0x2A</integer>" +
                "<key>ratio</key><real>1.5e3</real>" +
                "<key>enabled</key><true/>" +
                "<key>payload</key><data>AQID</data>" +
                "<key>born</key><date>2023-01-01T00:00:00Z</date>" +
                "<key>tags</key><array><string>a</string><string>b</string></array>" +
                "</dict></plist>"
        val xmlDocument = xml(source)
        assertEquals(FormationStatus.Complete, xmlDocument.formationStatus())

        val first = xmlDocument.convertTo(PlistProfile.BinaryV1)
        val back = first.document.convertTo(PlistProfile.XmlV1)
        val second = back.document.convertTo(PlistProfile.BinaryV1)
        val again = second.document.convertTo(PlistProfile.XmlV1)

        // The canonical fixed point: the second XML equals the first
        // converted XML byte-exactly, and the second binary equals the
        // first converted binary byte-exactly.
        assertEquals(back.document.hex(), again.document.hex())
        assertEquals(first.document.hex(), second.document.hex())
        assertEquals(FormationStatus.Complete, back.document.formationStatus())
        assertEquals(
            listOf("name", "count", "ratio", "enabled", "payload", "born", "tags"),
            back.document.root().dictEntries()!!.map { it.key()!!.toUnicode()!! },
        )
    }

    /** Binary -> XML -> binary is a byte-exact fixed point for a canonical
     * binary source (the materialized canonical table has no shared
     * objects, so the conversion writer reproduces it exactly). */
    @Test
    fun binaryToXmlToBinaryRoundTripByteExact() {
        // The canonical dict {a: 1} (the queryBinaryStructure vector hex).
        val sourceHex = "62706c6973743030d1010251611001080b0d000000000000010100000000000000030000000000000000000000000000000f"
        val binaryDocument = binary(sourceHex)
        assertEquals(FormationStatus.Complete, binaryDocument.formationStatus())

        val first = binaryDocument.convertTo(PlistProfile.XmlV1)
        val back = first.document.convertTo(PlistProfile.BinaryV1)
        val second = back.document.convertTo(PlistProfile.XmlV1)
        val again = second.document.convertTo(PlistProfile.BinaryV1)

        assertEquals(first.document.hex(), second.document.hex())
        assertEquals(back.document.hex(), again.document.hex())
        assertEquals(FormationStatus.Complete, back.document.formationStatus())
        assertEquals("dict", back.document.root().kind()?.kindName())
        assertEquals(listOf("a"), back.document.root().dictEntries()!!.map { it.key()!!.toUnicode()!! })
        assertEquals(1L, back.document.root().dictEntries()!![0].value().asInteger())
    }

    /** Vector case plist.conversion.duplicate-keys-preserved (plist-v1.json:
     * 1624-1643): duplicate keys keep their physical association order
     * across the conversion. */
    @Test
    fun duplicateKeysPreservedAcrossConversion() {
        val source = "<plist version=\"1.0\"><dict><key>a</key><integer>1</integer><key>a</key><integer>2</integer><key>b</key><integer>3</integer></dict></plist>"
        val document = xml(source)
        val converted = document.convertTo(PlistProfile.BinaryV1)
        assertEquals(FormationStatus.Complete, converted.document.formationStatus())

        val back = converted.document.convertTo(PlistProfile.XmlV1)
        assertEquals(
            listOf("a", "a", "b"),
            back.document.root().dictEntries()!!.map { it.key()!!.toUnicode()!! },
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            back.document.root().dictEntries()!!.map { it.value().asInteger()!! },
        )
    }

    /** Conversion is exact for every value kind expressible in both
     * representations: the typed facts survive the binary -> XML ->
     * binary chain. */
    @Test
    fun typedFactsSurviveConversion() {
        val source =
            "<plist version=\"1.0\"><dict>" +
                "<key>i</key><integer>-7</integer>" +
                "<key>r</key><real>1.5</real>" +
                "<key>t</key><true/>" +
                "<key>f</key><false/>" +
                "<key>s</key><string>a &amp; b</string>" +
                "</dict></plist>"
        val converted = xml(source).convertTo(PlistProfile.BinaryV1)
        val back = converted.document.convertTo(PlistProfile.XmlV1)

        val entries = back.document.root().dictEntries()!!
        assertEquals(-7L, entries[0].value().asInteger())
        assertEquals(1.5, entries[1].value().asReal()!!.asDouble())
        assertEquals(true, entries[2].value().asBoolean())
        assertEquals(false, entries[3].value().asBoolean())
        assertEquals("a & b", entries[4].value().asString()!!.toUnicode())
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }
}
