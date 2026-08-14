// Reader vs Latin-1 dialect and explicit encoding coverage transcribed
// from conformance/vectors/java-properties-v1.json.
//
// The Reader profile operates on an explicitly decoded character source
// (RFC 0010 §3.1); the Latin-1 profile maps every byte to the same-numbered
// ISO-8859-1 character with BOM bytes as ordinary content (RFC 0010 §3.2).
// Case ids are cited on every test; these tests pin the intent and run at
// the L2 verification gate.
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package properties

import consema.document.FormationStatus
import consema.document.SourceEncoding
import consema.properties.JavaStringStatus
import consema.properties.PropertiesEncoding
import consema.properties.PropertiesProfile
import consema.properties.PropertiesSyntaxKind
import consema.properties.WindowsCodePage
import consema.properties.parse
import consema.properties.parseLatin1
import consema.properties.parseReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialectAndEncodingTest {

    /** Vector case formation.latin1-byte-and-bom-content
     * (java-properties-v1.json:50-54): a UTF-8 BOM byte sequence has no BOM
     * meaning under the Latin-1 profile; every byte is ordinary key content
     * and `bom_syntax` stays false. */
    @Test
    fun latin1ByteAndBomContent() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 'k'.code.toByte(), '='.code.toByte(), 0xff.toByte())
        val document = parseLatin1(bytes)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals("00ef00bb00bf006b", document.properties()[0].key().utf16beBytes().toHexString())
        assertEquals("00ff", document.properties()[0].value().utf16beBytes().toHexString())
        assertNull(document.source().encodingFacts.bom)
        assertTrue(PropertiesSyntaxKind.Bom !in document.losslessSyntaxKinds())
        assertEquals(bytes.toList(), document.render().toList())
    }

    /** Vector case formation.reader-explicit-encodings
     * (java-properties-v1.json:40-49): Reader UTF-8 without BOM, UTF-16LE
     * and UTF-16BE with matching BOMs, and WindowsCodePage(1252), all with
     * byte-exact render identity. */
    @Test
    fun readerExplicitEncodings() {
        // UTF-8 without BOM: "名=值\n"
        val utf8 = hexBytes("e5908d3de580bc0a")
        val utf8Document = parseReader(utf8, SourceEncoding.Utf8)
        assertEquals(FormationStatus.Complete, utf8Document.formationStatus())
        assertEquals("名", utf8Document.properties()[0].key().toUnicode())
        assertEquals("值", utf8Document.properties()[0].value().toUnicode())
        assertNull(utf8Document.source().encodingFacts.bom)
        assertEquals(utf8.toList(), utf8Document.render().toList())

        // UTF-16LE with its matching BOM: "k=v" in UTF-16LE units.
        val utf16Le = hexBytes("fffe6b003d007600")
        val leDocument = parseReader(utf16Le, SourceEncoding.Utf16Le)
        assertEquals(FormationStatus.Complete, leDocument.formationStatus())
        assertEquals("k", leDocument.properties()[0].key().toUnicode())
        assertEquals("v", leDocument.properties()[0].value().toUnicode())
        assertEquals("Utf16Le", leDocument.source().encodingFacts.bom!!.name)
        assertEquals(utf16Le.toList(), leDocument.render().toList())
        assertEquals(PropertiesSyntaxKind.Bom, leDocument.losslessSyntaxKinds().first())

        // UTF-16BE with its matching BOM.
        val utf16Be = hexBytes("feff006b003d0076")
        val beDocument = parseReader(utf16Be, SourceEncoding.Utf16Be)
        assertEquals(FormationStatus.Complete, beDocument.formationStatus())
        assertEquals("k", beDocument.properties()[0].key().toUnicode())
        assertEquals("Utf16Be", beDocument.source().encodingFacts.bom!!.name)

        // WindowsCodePage(1252): "name=café\n" with 0xE9 for é.
        val cp1252 = hexBytes("6e616d653d636166e90a")
        val codePage = WindowsCodePage.fromNumber(1252)!!
        val cpDocument = parse(
            cp1252,
            PropertiesProfile.ReaderV1,
            PropertiesEncoding.WindowsCodePage(codePage.number),
        )
        assertEquals(FormationStatus.Complete, cpDocument.formationStatus())
        assertEquals("name", cpDocument.properties()[0].key().toUnicode())
        assertEquals("café", cpDocument.properties()[0].value().toUnicode())
        assertNull(cpDocument.source().encodingFacts.bom)
        assertEquals(cp1252.toList(), cpDocument.render().toList())
    }

    /** RFC 0010 §3.2 and the profile-encoding validation (parser.rs):
     * the Latin-1 profile rejects Reader selections and vice versa with the
     * frozen java-properties.source.profile-encoding@1 code. */
    @Test
    fun profileAndEncodingSelectionMustMatch() {
        val failure = assertFailsWith<consema.properties.PropertiesFormationException> {
            parse(
                "k=v".toByteArray(Charsets.UTF_8),
                PropertiesProfile.Latin1V1,
                PropertiesEncoding.Reader(SourceEncoding.Utf8),
            )
        }
        assertEquals("java-properties.source.profile-encoding@1", failure.code)
    }

    /** RFC 0010 §4 and vector formation.escape-and-java-utf16-matrix
     * (java-properties-v1.json:30-34): a legal Unicode escape produces an
     * unpaired UTF-16 code unit that is ordinary native content and never a
     * replacement character. */
    @Test
    fun unicodeEscapePreservesAnUnpairedJavaSurrogate() {
        val document = parseReader(
            "key=\\uD800".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val value = document.properties()[0].value()
        assertEquals(0xd800, value.codeUnits()[0].code)
        assertEquals(JavaStringStatus.UnpairedSurrogate, value.status)
        assertFailsWith<consema.properties.JavaStringConversionException> { value.toUnicode() }
    }

    /** RFC 0010 §5: the JDK line-reader end-of-source rule retains a final
     * unmatched backslash as a ContinuationMarker and emits no code unit
     * (parser.rs). */
    @Test
    fun terminalOddBackslashMatchesJdkLineReaderEofRule() {
        val source = "key=value\\".toByteArray(Charsets.UTF_8)
        val document = parseReader(source, SourceEncoding.Utf8)
        assertEquals("value", document.properties()[0].value().toUnicode())
        assertEquals(source.toList(), document.render().toList())
        assertEquals(
            PropertiesSyntaxKind.ContinuationMarker,
            document.losslessSyntaxKinds().last(),
        )
    }
}

/** Decodes a lowercase hex string to exact bytes. */
private fun hexBytes(hex: String): ByteArray {
    val bytes = ByteArray(hex.length / 2)
    for (i in bytes.indices) {
        bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return bytes
}

/** Lowercase hex of exact bytes. */
private fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val hex = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xff
        hex[i * 2] = digits[value ushr 4]
        hex[i * 2 + 1] = digits[value and 0x0f]
    }
    return String(hex)
}
