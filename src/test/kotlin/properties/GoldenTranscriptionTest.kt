// Golden transcriptions of conformance/vectors/java-properties-v1.json
// formation cases.
//
// Each test transcribes one vector case (input.source / expected.*) VERBATIM
// from conformance/vectors/java-properties-v1.json and asserts the
// language-neutral facts the Rust/Go differential runners assert
// (crates/consema-conformance/src/java_properties_v1.rs). The case id is
// cited on every test.
//
// This file is an intent document: the toolchain is not verified yet, so
// these tests pin the intent; they run at the L2 verification gate.

package properties

import consema.document.FormationStatus
import consema.properties.JavaStringStatus
import consema.properties.PropertiesEscapeKind
import consema.properties.PropertiesSyntaxKind
import consema.properties.PropertiesValueState
import consema.properties.parseReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoldenTranscriptionTest {

    /** Vector case formation.reader-lines-escapes-duplicates
     * (java-properties-v1.json:5-9): comment, escaped spaces, continuation,
     * Unicode escape, duplicate group, implicit/explicit empty states;
     * Complete with exact natural/logical/property/comment/escape counts and
     * exact coverage. */
    @Test
    fun readerLinesEscapesDuplicates() {
        val source = "  # retained comment\\\r\n" +
            "key\\ with\\ spaces : first\\\r\n" +
            " \tsecond\\u0021\n" +
            "dup=first\rdup:last\n" +
            "empty\nexplicit="
        val document = parseReader(source.toByteArray(Charsets.UTF_8), consema.document.SourceEncoding.Utf8)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        assertEquals(7, document.naturalLines().size)
        assertEquals(5, document.logicalLines().size)
        assertEquals(1, document.comments().size)
        assertEquals(5, document.properties().size)
        assertEquals(3, document.escapes().size)

        val properties = document.properties()
        assertEquals("key with spaces", properties[0].key().toUnicode())
        assertEquals("firstsecond!", properties[0].value().toUnicode())
        assertEquals(PropertiesValueState.Present, properties[0].valueState())
        assertEquals(1, properties[0].keyFragments().size)
        assertEquals(2, properties[0].valueFragments().size)
        assertEquals(3, properties[0].escapes().size)

        assertEquals("first", properties[1].value().toUnicode())
        assertEquals("last", properties[2].value().toUnicode())
        assertEquals(properties[1].duplicateGroup(), properties[2].duplicateGroup())
        assertNotNull(properties[1].duplicateGroup())

        assertEquals(PropertiesValueState.ImplicitEmpty, properties[3].valueState())
        assertEquals(PropertiesValueState.ExplicitEmpty, properties[4].valueState())

        val kinds = document.losslessSyntaxKinds()
        assertTrue(PropertiesSyntaxKind.ContinuationMarker in kinds)
        assertTrue(PropertiesSyntaxKind.EscapeMarker in kinds)

        val pieces = document.losslessStructuralIndex().pieces()
        assertEquals(0, pieces.first().span.startByte)
        assertEquals(source.length, pieces.last().span.endByte)
        for (index in 1 until pieces.size) {
            assertEquals(pieces[index - 1].span.endByte, pieces[index].span.startByte)
        }
    }

    /** Vector case formation.continuation-and-backslash-parity
     * (java-properties-v1.json:20-29): even/odd terminal backslashes and the
     * JDK end-of-source rule; the value hex facts are UTF16BE/1. */
    @Test
    fun continuationAndBackslashParity() {
        val cases = listOf(
            "key=value\\" to "00760061006c00750065",
            "key=value\\\\" to "00760061006c00750065005c",
            "key=first\\\n  second" to "00660069007200730074007300650063006f006e0064",
            "key=\\u00\\\n 41" to "0041",
        )
        for ((source, valueHex) in cases) {
            val document = parseReader(source.toByteArray(Charsets.UTF_8), consema.document.SourceEncoding.Utf8)
            assertEquals(FormationStatus.Complete, document.formationStatus())
            assertEquals(1, document.properties().size)
            assertEquals(1, document.logicalLines().size)
            assertEquals(
                valueHex,
                document.properties()[0].value().utf16beBytes().toHexString(),
            )
            assertEquals(source, document.render().toString(Charsets.UTF_8))
        }
    }

    /** Vector case formation.escape-and-java-utf16-matrix
     * (java-properties-v1.json:30-34): named/backslash/dropped/Unicode escape
     * kinds, exact UTF16BE/1 values, and surrogate well-formedness statuses. */
    @Test
    fun escapeAndJavaUtf16Matrix() {
        val source = "named=\\t\\n\\r\\f\n" +
            "slash=\\\\\n" +
            "dropped=\\q\n" +
            "nonrecursive=\\u005Cu0041\n" +
            "pair=\\uD83D\\uDE00\n" +
            "high=\\uD800\n" +
            "low=\\uDC00\n" +
            "high-before=\\uD800A\n" +
            "low-after=A\\uDC00\n"
        val document = parseReader(source.toByteArray(Charsets.UTF_8), consema.document.SourceEncoding.Utf8)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        val expectedHex = listOf(
            "0009000a000d000c",
            "005c",
            "0071",
            "005c00750030003000340031",
            "d83dde00",
            "d800",
            "dc00",
            "d8000041",
            "0041dc00",
        )
        assertEquals(
            expectedHex,
            document.properties().map { it.value().utf16beBytes().toHexString() },
        )
        val expectedStatuses = listOf(
            JavaStringStatus.WellFormedUnicode,
            JavaStringStatus.WellFormedUnicode,
            JavaStringStatus.WellFormedUnicode,
            JavaStringStatus.WellFormedUnicode,
            JavaStringStatus.WellFormedUnicode,
            JavaStringStatus.UnpairedSurrogate,
            JavaStringStatus.UnpairedSurrogate,
            JavaStringStatus.UnpairedSurrogate,
            JavaStringStatus.UnpairedSurrogate,
        )
        assertEquals(expectedStatuses, document.properties().map { it.value().status })
        // The escape kinds in key-then-value decode order across the whole
        // document (the vector escape_kinds fact).
        assertEquals(
            listOf(
                PropertiesEscapeKind.Named,
                PropertiesEscapeKind.Named,
                PropertiesEscapeKind.Named,
                PropertiesEscapeKind.Named,
                PropertiesEscapeKind.Backslash,
                PropertiesEscapeKind.DroppedBackslash,
                PropertiesEscapeKind.Unicode,
                PropertiesEscapeKind.Unicode,
                PropertiesEscapeKind.Unicode,
                PropertiesEscapeKind.Unicode,
                PropertiesEscapeKind.Unicode,
                PropertiesEscapeKind.Unicode,
                PropertiesEscapeKind.Unicode,
            ),
            document.escapes().map { it.kind() },
        )
    }

    /** Vector case formation.malformed-unicode-recovery-matrix
     * (java-properties-v1.json:35-39): every malformed variant recovers with
     * exactly one diagnostic and no property; uppercase \U is a dropped
     * backslash and the line is Complete. */
    @Test
    fun malformedUnicodeRecoveryMatrix() {
        val samples = listOf("a=\\u", "a=\\u1", "a=\\u12", "a=\\u123", "a=\\u12G4")
        for (source in samples) {
            val document = parseReader(source.toByteArray(Charsets.UTF_8), consema.document.SourceEncoding.Utf8)
            assertEquals(FormationStatus.Recovered, document.formationStatus())
            assertEquals(0, document.properties().size)
            assertEquals(1, document.errorLines().size)
            assertEquals(1, document.diagnostics().size)
            assertEquals(
                "java-properties.parse.malformed-unicode-escape@1",
                document.errorLines()[0].code(),
            )
        }
        val uppercase = parseReader("a=\\U0041".toByteArray(Charsets.UTF_8), consema.document.SourceEncoding.Utf8)
        assertEquals(FormationStatus.Complete, uppercase.formationStatus())
        assertEquals(1, uppercase.properties().size)
        assertEquals("U0041", uppercase.properties()[0].value().toUnicode())
    }

    /** Vector case formation.mixed-line-terminators
     * (java-properties-v1.json:15-19): LF, CR, CRLF, and EOF natural lines
     * are distinct and exactly covered. */
    @Test
    fun mixedLineTerminators() {
        val source = "a=1\nb=2\rc=3\r\nd=4"
        val document = parseReader(source.toByteArray(Charsets.UTF_8), consema.document.SourceEncoding.Utf8)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(4, document.naturalLines().size)
        assertEquals(4, document.logicalLines().size)
        assertEquals(4, document.properties().size)
        val terminators = document.naturalLines().map { line ->
            when (line.lineBreakSpan()?.let { span ->
                document.render().copyOfRange(span.startByte, span.endByte)
                    .toString(Charsets.US_ASCII)
            }) {
                "\n" -> "Lf"
                "\r" -> "Cr"
                "\r\n" -> "CrLf"
                null -> "Eof"
                else -> "Other"
            }
        }
        assertEquals(listOf("Lf", "Cr", "CrLf", "Eof"), terminators)
        assertEquals(source.length, document.render().size)
    }

    /** Vector case formation.recovery-never-publishes-partial-operation
     * (java-properties-v1.json:55-59): valid records before and after a
     * malformed escape remain inspectable but the document is Recovered. */
    @Test
    fun recoveryNeverPublishesPartialOperation() {
        val source = "good=ok\nbad=\\u12G4\nafter=yes"
        val document = parseReader(source.toByteArray(Charsets.UTF_8), consema.document.SourceEncoding.Utf8)
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals(listOf("good", "after"), document.properties().map { it.key().toUnicode() })
        assertEquals(1, document.errorLines().size)
        assertEquals(
            "java-properties.parse.malformed-unicode-escape@1",
            document.errorLines()[0].code(),
        )
    }

    /** Vector case formation.empty-blank-comment-empty-key
     * (java-properties-v1.json:10-14): empty sources, blanks, `#`/`!`
     * comments, implicit/explicit empty, and empty keys. */
    @Test
    fun emptyBlankCommentEmptyKey() {
        val samples = listOf("", "\n", "# comment\n", "! comment\r", "implicit", "explicit=", "=value", "a=1\nb=2\n")
        val expectedProperties = listOf(0, 0, 0, 0, 1, 1, 1, 2)
        val expectedComments = listOf(0, 0, 1, 1, 0, 0, 0, 0)
        for ((index, source) in samples.withIndex()) {
            val document = parseReader(source.toByteArray(Charsets.UTF_8), consema.document.SourceEncoding.Utf8)
            assertEquals(FormationStatus.Complete, document.formationStatus(), source)
            assertEquals(expectedProperties[index], document.properties().size, source)
            assertEquals(expectedComments[index], document.comments().size, source)
        }
    }
}

/** Lowercase hex of exact bytes (the vector hex facts). */
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
