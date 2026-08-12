// Encoding-error and malformed-line negatives for the Java Properties
// family (RFC 0010 §2, §3, §5).
//
// Reader sources are validated byte-exact before any scan: invalid UTF-8
// and malformed UTF-16 sequences fail with core.source.invalid-sequence@1,
// BOM/selection conflicts with core.source.encoding-conflict@1, and
// UTF-32 BOMs with core.source.unsupported-bom@1 (encoding.rs / Source.kt;
// source_v1.rs:410-421). The Latin-1 profile maps every byte to the
// same-numbered ISO-8859-1 character, so no byte sequence can fail.
// Malformed Unicode escapes recover as deterministic error lines
// (parser.rs:626-666, 909-996): the whole logical line is one
// `java-properties.parse.malformed-unicode-escape@1` error region, no
// partial property is published, and the surrounding lines survive
// (lib.rs malformed_unicode_escape_recovers_without_partial_property).

package properties

import consema.document.SourceEncoding
import consema.properties.PropertiesEncoding
import consema.properties.PropertiesFormationException
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

class EncodingErrorAndMalformedLineTest {

    /** core.source.invalid-sequence@1: an invalid UTF-8 byte sequence is
     * fatal before any scan (Source.kt INVALID_UTF8). */
    @Test
    fun readerRejectsInvalidUtf8Sequences() {
        val bytes = byteArrayOf('k'.code.toByte(), '='.code.toByte(), 0xc3.toByte(), 0x28.toByte(), '\n'.code.toByte())
        val failure = assertFailsWith<PropertiesFormationException> {
            parseReader(bytes, SourceEncoding.Utf8)
        }
        assertEquals("core.source.invalid-sequence@1", failure.code)
    }

    /** core.source.invalid-sequence@1: an odd-length UTF-16 source is
     * rejected (Source.kt decodeUtf16). */
    @Test
    fun readerUtf16RejectsOddLength() {
        val bytes = hexBytes("fffe6b003d00760041")
        val failure = assertFailsWith<PropertiesFormationException> {
            parseReader(bytes, SourceEncoding.Utf16Le)
        }
        assertEquals("core.source.invalid-sequence@1", failure.code)
    }

    /** core.source.invalid-sequence@1: an isolated UTF-16 high surrogate
     * with no low surrogate is rejected (Source.kt decodeUtf16). */
    @Test
    fun readerUtf16RejectsIsolatedSurrogate() {
        val bytes = hexBytes("fffe6b003dd800")
        val failure = assertFailsWith<PropertiesFormationException> {
            parseReader(bytes, SourceEncoding.Utf16Le)
        }
        assertEquals("core.source.invalid-sequence@1", failure.code)
    }

    /** core.source.encoding-conflict@1: a detected BOM that disagrees with
     * the caller-selected encoding is a conflict, never a guess
     * (Encoding.kt resolveAssertions). */
    @Test
    fun readerRejectsMismatchedBom() {
        // UTF-16LE BOM bytes under a UTF-8 selection.
        val utf16LeBom = hexBytes("fffe6b003d007600")
        val conflict = assertFailsWith<PropertiesFormationException> {
            parseReader(utf16LeBom, SourceEncoding.Utf8)
        }
        assertEquals("core.source.encoding-conflict@1", conflict.code)

        // UTF-16BE BOM bytes under a UTF-16LE selection.
        val utf16BeBom = hexBytes("feff006b003d0076")
        val conflictBe = assertFailsWith<PropertiesFormationException> {
            parseReader(utf16BeBom, SourceEncoding.Utf16Le)
        }
        assertEquals("core.source.encoding-conflict@1", conflictBe.code)
    }

    /** core.source.unsupported-bom@1: UTF-32 BOMs are unsupported in v1
     * and fail before any other resolution step (Encoding.kt detectBom). */
    @Test
    fun readerRejectsUtf32Bom() {
        val utf32Le = hexBytes("fffe00006b0000003d000000")
        val failure = assertFailsWith<PropertiesFormationException> {
            parseReader(utf32Le, SourceEncoding.Utf8)
        }
        assertEquals("core.source.unsupported-bom@1", failure.code)

        val utf32Be = hexBytes("0000feff0000006b")
        val failureBe = assertFailsWith<PropertiesFormationException> {
            parseReader(utf32Be, SourceEncoding.Utf8)
        }
        assertEquals("core.source.unsupported-bom@1", failureBe.code)
    }

    /** Windows code pages keep the frozen DetectUnicode BOM rule: marker
     * shaped prefixes conflict with the code page before any decoding
     * (Encoding.kt rejectUnicodeBomEvidence). */
    @Test
    fun windowsCodePageRejectsUnicodeBomEvidence() {
        val utf8Bom = hexBytes("efbbbf6b3d760a")
        val conflict = assertFailsWith<PropertiesFormationException> {
            parse(utf8Bom, PropertiesProfile.ReaderV1, PropertiesEncoding.WindowsCodePage(1252))
        }
        assertEquals("core.source.encoding-conflict@1", conflict.code)

        val utf32LeBom = hexBytes("fffe00006b3d760a")
        val unsupported = assertFailsWith<PropertiesFormationException> {
            parse(utf32LeBom, PropertiesProfile.ReaderV1, PropertiesEncoding.WindowsCodePage(1252))
        }
        assertEquals("core.source.unsupported-bom@1", unsupported.code)
    }

    /** core.source.unsupported-code-page@1: a number outside the portable
     * registry is rejected (Encoding.kt buildPropertiesSource). */
    @Test
    fun windowsCodePageRejectsUnknownNumber() {
        assertNull(WindowsCodePage.fromNumber(999))
        val failure = assertFailsWith<PropertiesFormationException> {
            parse("k=v".toByteArray(Charsets.UTF_8), PropertiesProfile.ReaderV1,
                PropertiesEncoding.WindowsCodePage(999))
        }
        assertEquals("core.source.unsupported-code-page@1", failure.code)
    }

    /** The Latin-1 profile has no encoding errors: every byte is the
     * same-numbered ISO-8859-1 character, including control bytes and BOM
     * marker shapes (RFC 0010 §3.2). */
    @Test
    fun latin1AdmitsEveryByteSequence() {
        val bytes = byteArrayOf(
            'k'.code.toByte(), '='.code.toByte(),
            0x80.toByte(), 0x81.toByte(), 0xfe.toByte(), 0xff.toByte(),
            '\n'.code.toByte(),
        )
        val document = parseLatin1(bytes)
        assertEquals("k", document.properties()[0].key().toUnicode())
        assertEquals("0080008100fe00ff", document.properties()[0].value().utf16beBytes().toHexString())
        assertNull(document.source().encodingFacts.bom)
        assertTrue(PropertiesSyntaxKind.Bom !in document.losslessSyntaxKinds())
        assertEquals(bytes.toList(), document.render().toList())
    }

    /** Transcribed from lib.rs
     * malformed_unicode_escape_recovers_without_partial_property: the
     * malformed escape line is one error line, no partial property is
     * published, the other lines survive, and the render stays byte-exact. */
    @Test
    fun malformedUnicodeEscapeRecoversWithoutPartialProperty() {
        val source = "good=ok\nbad=\\u12G4\nafter=yes".toByteArray(Charsets.UTF_8)
        val document = parseReader(source, SourceEncoding.Utf8)
        assertEquals(2, document.properties().size)
        assertEquals("ok", document.properties()[0].value().toUnicode())
        assertEquals("yes", document.properties()[1].value().toUnicode())
        assertEquals(1, document.errorLines().size)
        assertEquals("java-properties.parse.malformed-unicode-escape@1",
            document.errorLines()[0].code())
        assertEquals(3, document.logicalLines().size)
        assertEquals("java-properties.parse.malformed-unicode-escape@1",
            document.diagnostics()[0].code)
        assertTrue(PropertiesSyntaxKind.ErrorRegion in document.losslessSyntaxKinds())
        assertEquals(source.toList(), document.render().toList())
    }

    /** A malformed escape in the key position recovers the same way: the
     * whole logical line is dropped and the next property survives. */
    @Test
    fun malformedEscapeInKeyRecovers() {
        val source = "bad\\u12G4key=value\ngood=ok\n".toByteArray(Charsets.UTF_8)
        val document = parseReader(source, SourceEncoding.Utf8)
        assertEquals(1, document.properties().size)
        assertEquals("good", document.properties()[0].key().toUnicode())
        assertEquals(1, document.errorLines().size)
        assertEquals("java-properties.parse.malformed-unicode-escape@1",
            document.errorLines()[0].code())
    }

    /** Each malformed line recovers independently with its own error line
     * and diagnostic. */
    @Test
    fun multipleMalformedLinesRecoverIndependently() {
        val source = "a=\\u12G4\nb=\\uZZZZ\nc=3\n".toByteArray(Charsets.UTF_8)
        val document = parseReader(source, SourceEncoding.Utf8)
        assertEquals(1, document.properties().size)
        assertEquals("c", document.properties()[0].key().toUnicode())
        assertEquals(2, document.errorLines().size)
        assertEquals(2, document.diagnostics().size)
        for (diagnostic in document.diagnostics()) {
            assertEquals("java-properties.parse.malformed-unicode-escape@1", diagnostic.code)
        }
    }

    /** A truncated escape at the end of a line and at end of file are
     * malformed: the line is dropped and the next line survives. */
    @Test
    fun truncatedEscapeRecovers() {
        val source = "a=\\u12\nb=2\n".toByteArray(Charsets.UTF_8)
        val document = parseReader(source, SourceEncoding.Utf8)
        assertEquals(1, document.properties().size)
        assertEquals("b", document.properties()[0].key().toUnicode())
        assertEquals(1, document.errorLines().size)

        val eof = "a=\\u".toByteArray(Charsets.UTF_8)
        val documentEof = parseReader(eof, SourceEncoding.Utf8)
        assertEquals(0, documentEof.properties().size)
        assertEquals(1, documentEof.errorLines().size)
        assertEquals("java-properties.parse.malformed-unicode-escape@1",
            documentEof.errorLines()[0].code())
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
