// Golden transcriptions of conformance/vectors/ini-v1.json formation cases.
//
// Each test transcribes one vector case (input.* / expected.*) VERBATIM
// from conformance/vectors/ini-v1.json and asserts the language-neutral
// facts the Rust/Go differential runners assert. The case id is cited on
// every test.
//
// This file runs in the verified toolchain gate (kotlin-gates gradlew
// test / the scripts/kotlin-verify-*.ps1 direct path): the toolchain is
// verified and this file is executed.
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package ini

import consema.document.FormationStatus
import consema.document.NodeRole
import consema.ini.IniFormationException
import consema.ini.IniProfile
import consema.ini.IniQuoteStyle
import consema.ini.IniValueState
import consema.ini.commit
import consema.ini.parse
import consema.ini.project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Decodes the vector `source_hex` spellings. */
private fun hexToBytes(hex: String): ByteArray {
    val output = ByteArray(hex.length / 2)
    for (i in output.indices) {
        output[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return output
}

class FormationGoldenTest {

    /** Vector case formation.portable-lossless (ini-v1.json:6-8): CRLF and
     * LF mixed newlines, a `;` comment, an Empty value, byte-exact render,
     * and exhaustive coverage; comment lines are not logical records. */
    @Test
    fun portableLossless() {
        val source = "; heading\r\n[core]\r\nname=value\nempty="
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.PortableV1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
        assertEquals(4, document.physicalLines().size)
        assertEquals(3, document.logicalLines().size)
        assertEquals(listOf("core"), document.sections().map { it.name })
        assertEquals(listOf("name", "empty"), document.entries().map { it.key })
        assertEquals(listOf("value", ""), document.entries().map { it.value })
        assertEquals(
            listOf(IniValueState.Present, IniValueState.Empty),
            document.entries().map { it.valueState },
        )
        assertExactCoverage(document)
    }

    /** Vector case formation.profile-counterexample-matrix (ini-v1.json:11-
     * 17): the same bytes under the three profiles give Complete / Recovered
     * / Fatal per profile; the windows `é` sample is a fatal
     * ini.profile.encoding@1 failure because no code page was selected. */
    @Test
    fun profileCounterexampleMatrix() {
        val plain = "[s]\nkey=value\n".toByteArray(Charsets.UTF_8)
        val colon = "[s]\nkey:value\n".toByteArray(Charsets.UTF_8)
        val accent = "[s]\nkey=é\n".toByteArray(Charsets.UTF_8)

        assertEquals(FormationStatus.Complete, parse(plain, IniProfile.PortableV1).formationStatus())
        assertEquals(FormationStatus.Complete, parse(plain, IniProfile.WindowsV1).formationStatus())
        assertEquals(FormationStatus.Complete, parse(plain, IniProfile.PythonConfigParserV1).formationStatus())

        assertEquals(FormationStatus.Recovered, parse(colon, IniProfile.PortableV1).formationStatus())
        assertEquals(FormationStatus.Recovered, parse(colon, IniProfile.WindowsV1).formationStatus())
        assertEquals(FormationStatus.Complete, parse(colon, IniProfile.PythonConfigParserV1).formationStatus())

        assertEquals(FormationStatus.Recovered, parse(accent, IniProfile.PortableV1).formationStatus())
        val fatal = assertFailsWith<IniFormationException> {
            parse(accent, IniProfile.WindowsV1)
        }
        assertEquals("ini.profile.encoding@1", fatal.code)
        assertEquals(FormationStatus.Complete, parse(accent, IniProfile.PythonConfigParserV1).formationStatus())
    }

    /** Vector case formation.windows-utf16-case-and-quote (ini-v1.json:20-
     * 22): UTF-16LE with BOM, trimmed keys, a Double-quoted value whose
     * semantic content keeps its spaces, ASCII case-equivalent comparison
     * names, and the case-collision diagnostic. */
    @Test
    fun windowsUtf16CaseAndQuote() {
        val sourceHex = "fffe5b004d00610069006e005d000d000a0020004e0061006d00650020003d0022002000760061006c0075006500200022000d000a005b006d00610069006e005d000d000a004e0041004d0045003d00740077006f00"
        val document = parse(hexToBytes(sourceHex), IniProfile.WindowsV1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals("Utf16Le", document.source().encodingFacts.selected.asStr())
        assertEquals(listOf("Main", "main"), document.sections().map { it.name })
        assertEquals("main", document.sections()[0].comparisonName)
        assertEquals(listOf("Name", "NAME"), document.entries().map { it.key })
        assertEquals("name", document.entries()[0].comparisonKey)
        assertEquals(listOf(" value ", "two"), document.entries().map { it.value })
        assertEquals(IniQuoteStyle.Double, document.entries()[0].quoteStyle)
        assertTrue(document.diagnostics().any { it.code == "ini.formation.case-collision@1" })
        assertExactCoverage(document)
    }

    /** Vector case formation.windows-explicit-code-page (ini-v1.json:24-27):
     * byte 0x80 under cp1252 is U+20AC, the BOM policy is TreatAsContent,
     * and the source is fully covered. */
    @Test
    fun windowsExplicitCodePage() {
        val bytes = hexToBytes("5b735d0d0a6b3d80")
        val document = parse(
            bytes,
            IniProfile.WindowsV1,
            consema.ini.IniEncodingSelection.Explicit(
                consema.ini.IniSourceEncoding.WindowsCodePage(
                    consema.ini.IniWindowsCodePage.fromNumber(1252)!!,
                ),
            ),
        )

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals("€", document.entries()[0].value)
        assertEquals("WindowsCodePage(1252)", document.source().encodingFacts.selected.asStr())
        assertEquals("TreatAsContent", document.source().encodingFacts.bomPolicy.name)
        assertExactCoverage(document)
    }

    /** Vector case formation.python-default-continuation-raw
     * (ini-v1.json:30-32): the DEFAULT section role, `:` delimiter,
     * more-indented continuation with an embedded blank line, raw
     * interpolation markers, and literal `#`/`;` inside values. */
    @Test
    fun pythonDefaultContinuationRaw() {
        val source = "[DEFAULT]\nRoot = raw%(x)s\n[Sec]\nKey: first\n    second\n\n    third\nOther = #literal ;literal"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.PythonConfigParserV1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertTrue(document.sections()[0].isDefault)
        assertEquals(NodeRole.IniDefaultSection, document.sections()[0].nodeRef.role)
        assertEquals(
            listOf("root", "key", "other"),
            document.entries().map { it.comparisonKey },
        )
        assertEquals(
            listOf("raw%(x)s", "first\nsecond\n\nthird", "#literal ;literal"),
            document.entries().map { it.value },
        )
        val continuation = document.logicalLine(document.entries()[1].logicalLine)
        assertEquals(4, continuation.physicalLines.size)
        assertExactCoverage(document)
    }

    /** Vector case formation.python-unicode16-optionxform (ini-v1.json:34-
     * 37): U+0130 and "i" + U+0307 fold to the same comparison key under
     * the pinned Unicode 16.0 optionxform, forming a duplicate group with a
     * case-collision diagnostic. */
    @Test
    fun pythonUnicode16Optionxform() {
        val source = "[S]\n\u0130=1\ni\u0307=2\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.PythonConfigParserV1)

        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals(listOf("i\u0307", "i\u0307"), document.entries().map { it.comparisonKey })
        val groups = document.entries().map { it.duplicateGroup }
        assertTrue(groups[0] != null && groups[0] == groups[1])
        assertTrue(document.diagnostics().any { it.code == "ini.formation.case-collision@1" })
    }

    /** Vector case formation.recovery-never-fabricates-entry (ini-v1.json:
 *): a bare line recovers as one error record with the
     * missing-delimiter code, never as an entry; projection and edit both
     * refuse the recovered document with their frozen codes. */
    @Test
    fun recoveryNeverFabricatesEntry() {
        val document = parse("[s]\nbare\n".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)

        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals(0, document.entries().size)
        assertEquals(1, document.errorLines().size)
        assertEquals("ini.parse.missing-delimiter@1", document.errorLines()[0].code)
        assertEquals("ini.parse.missing-delimiter@1", document.diagnostics()[0].code)

        val projection = document.project(consema.ini.ProjectionRequest.bestExactEntryMapping())
        val failed = projection as consema.ini.ProjectionResult.Failed
        assertEquals(
            "ini.projection.incomplete-document@1",
            failed.attempt.diagnostics[0].code,
        )

        val transaction = consema.ini.EditTransactionBuilder.new(document).build()
        val editFailure = assertFailsWith<consema.ini.EditFailureException> {
            document.commit(transaction)
        }
        assertEquals("core.edit.incomplete-target@1", editFailure.failure.diagnosticCode())
    }

    /** The exact-coverage invariant of the vectors: first piece starts at
     * 0, pieces are adjacent, and the last piece ends at the source
     * length. */
    private fun assertExactCoverage(document: consema.ini.IniDocument) {
        val pieces = document.losslessStructuralIndex().pieces()
        assertEquals(document.losslessSyntaxKinds().size, pieces.size)
        assertEquals(0, pieces.first().span.startByte)
        assertEquals(document.source().len, pieces.last().span.endByte)
        var next = 0
        for (piece in pieces) {
            assertEquals(next, piece.span.startByte)
            assertTrue(piece.span.endByte > piece.span.startByte)
            next = piece.span.endByte
        }
    }
}
