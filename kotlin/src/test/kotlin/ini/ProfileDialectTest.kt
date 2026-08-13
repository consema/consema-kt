// Profile dialect coverage: the behaviors that distinguish the three
// profiles beyond the golden transcriptions.
//
// Authority: RFC 0009 §5-§7 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md:148-
// 252) and the Rust crate tests (consema-ini/src/lib.rs:713-944). The L5
// conformance runner executes the shared vectors directly; these tests are
// the L2 intent documents.

package ini

import consema.document.FormationStatus
import consema.ini.IniFormationException
import consema.ini.IniProfile
import consema.ini.IniQuoteStyle
import consema.ini.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileDialectTest {

    /** RFC 0009 §5: `#` is not a portable comment; `;` is. */
    @Test
    fun portableCommentMarkerIsSemicolonOnly() {
        val semicolon = parse("; note\n[s]\nk=1\n".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)
        assertEquals(FormationStatus.Complete, semicolon.formationStatus())

        val hash = parse("# note\n[s]\nk=1\n".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)
        assertEquals(FormationStatus.Recovered, hash.formationStatus())
        assertEquals("ini.parse.missing-delimiter@1", hash.errorLines()[0].code)
    }

    /** RFC 0009 §5: portable section headers at EOF without a line break
     * are malformed (parser.rs:353-357), and the portable document requires
     * at least one section. */
    @Test
    fun portableSectionRequiresSectionAndLineBreak() {
        val eofHeader = parse("[s]".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)
        assertEquals(FormationStatus.Recovered, eofHeader.formationStatus())
        assertEquals("ini.parse.malformed-section@1", eofHeader.errorLines()[0].code)

        val commentOnly = parse("; only\n".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)
        assertEquals(FormationStatus.Recovered, commentOnly.formationStatus())
        assertTrue(commentOnly.diagnostics().any { it.code == "ini.parse.missing-section@1" })
    }

    /** RFC 0009 §6: the Windows profile accepts `;` comments, trims
     * surrounding whitespace, and treats `#` as content (it is a valid
     * name/value character); an unquoted value retains its exact scalar
     * content, including surrounding spaces. */
    @Test
    fun windowsTriviaAndHashContent() {
        val source = "[S] \r\n  key = #value \r\n; comment\r\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.WindowsV1)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals("S", document.sections()[0].name)
        assertEquals("key", document.entries()[0].key)
        assertEquals(" #value ", document.entries()[0].value)
        // The comment line is not a logical record: section plus entry.
        assertEquals(2, document.logicalLines().size)
    }

    /** RFC 0009 §6: exactly single- or double-quoted values lose the outer
     * marks; quotes inside an otherwise unquoted value are ordinary
     * content. */
    @Test
    fun windowsQuoteSemantics() {
        val document = parse(
            "[S]\r\na='single'\r\nb=\"double\"\r\nc=pre\"mid\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )
        assertEquals("single", document.entries()[0].value)
        assertEquals(IniQuoteStyle.Single, document.entries()[0].quoteStyle)
        assertEquals("double", document.entries()[1].value)
        assertEquals(IniQuoteStyle.Double, document.entries()[1].quoteStyle)
        assertEquals("pre\"mid", document.entries()[2].value)
        assertEquals(IniQuoteStyle.None, document.entries()[2].quoteStyle)
    }

    /** RFC 0009 §6: an entry outside a section is invalid under the
     * Windows profile. */
    @Test
    fun windowsGlobalEntryIsInvalid() {
        val document = parse("k=1\r\n".toByteArray(Charsets.UTF_8), IniProfile.WindowsV1)
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals("ini.parse.missing-section@1", document.errorLines()[0].code)
    }

    /** RFC 0009 §3.1: a BOM is a fatal profile-encoding failure for the
     * portable profile; RFC 0009 §3.3: the Python profile accepts any
     * unambiguous complete text source, including a UTF-8 BOM. */
    @Test
    fun bomRulesPerProfile() {
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
            "[s]\nk=1\n".toByteArray(Charsets.UTF_8)
        val portable = assertFailsWith<IniFormationException> {
            parse(bom, IniProfile.PortableV1)
        }
        assertEquals("ini.profile.encoding@1", portable.code)

        val python = parse(bom, IniProfile.PythonConfigParserV1)
        assertEquals(FormationStatus.Complete, python.formationStatus())
        assertTrue(python.losslessSyntaxKinds().first() == consema.ini.IniSyntaxKind.Bom)
    }

    /** RFC 0009 §7: `=` and `:` are both Python delimiters; `#` and `;`
     * prefix comment lines after indentation; inline markers are value
     * content. */
    @Test
    fun pythonDelimitersAndComments() {
        val source = "[S]\n; leading\n  # indented comment\nkey: value\nother = #inline ;inline\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.PythonConfigParserV1)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(listOf("key", "other"), document.entries().map { it.key })
        assertEquals(listOf("value", "#inline ;inline"), document.entries().map { it.value })
        // One section plus two entries; comment lines are not records.
        assertEquals(3, document.logicalLines().size)
    }

    /** RFC 0009 §7: `allow_no_value=False` — a bare option is invalid,
     * while `option=` contains an Empty string. */
    @Test
    fun pythonBareOptionIsInvalidButEmptyIsValid() {
        val document = parse(
            "[S]\nbare\nkey=\nother:\n".toByteArray(Charsets.UTF_8),
            IniProfile.PythonConfigParserV1,
        )
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals(2, document.entries().size)
        assertEquals(1, document.errorLines().size)
        assertEquals("ini.parse.missing-delimiter@1", document.errorLines()[0].code)
        assertEquals("", document.entries()[0].value)
        assertEquals(consema.ini.IniValueState.Empty, document.entries()[0].valueState)
    }

    /** RFC 0009 §7: a more-indented line that never joins an entry is an
     * invalid continuation. */
    @Test
    fun pythonStrayIndentIsInvalidContinuation() {
        val document = parse(
            "[S]\n  bare\n".toByteArray(Charsets.UTF_8),
            IniProfile.PythonConfigParserV1,
        )
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals("ini.parse.invalid-continuation@1", document.errorLines()[0].code)
    }

    /** RFC 0009 §7: the exact section name `DEFAULT` has the DefaultSection
     * role, but its entries are never merged into other sections. */
    @Test
    fun pythonDefaultSectionRoleWithoutMerge() {
        val source = "[DEFAULT]\nbase=1\n[S]\nvalue=2\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.PythonConfigParserV1)
        assertEquals(2, document.sections().size)
        assertTrue(document.sections()[0].isDefault)
        assertTrue(!document.sections()[1].isDefault)
        assertEquals(1, document.entries().filter { it.section == document.sections()[1].nodeRef }.size)
    }

    /** RFC 0009 §3.2: an explicit code page is required for non-ASCII
     * bytes; the ASCII-only fallback of the Windows profile is exact. */
    @Test
    fun windowsAsciiOnlyFallbackAndCodePageRequirement() {
        val ascii = parse("[s]\nk=v\n".toByteArray(Charsets.UTF_8), IniProfile.WindowsV1)
        assertEquals(FormationStatus.Complete, ascii.formationStatus())
        assertEquals("Utf8", ascii.source().encodingFacts.selected.asStr())

        val fatal = assertFailsWith<IniFormationException> {
            parse("[s]\nk=é\n".toByteArray(Charsets.UTF_8), IniProfile.WindowsV1)
        }
        assertEquals("ini.profile.encoding@1", fatal.code)
    }

    /** RFC 0009 §5: the portable profile rejects non-portable value
     * characters (quote, backslash, colon, `#`, `;`, control). */
    @Test
    fun portableValueCharacterRestrictions() {
        val document = parse(
            "[s]\nk=bad:value\n".toByteArray(Charsets.UTF_8),
            IniProfile.PortableV1,
        )
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals("ini.parse.invalid-character@1", document.errorLines()[0].code)
    }
}
