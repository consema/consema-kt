// Duplicate and case-collision group semantics across the three profiles.
//
// Authority: RFC 0009 §5 (portable duplicates make formation Recovered),
// §6 (Windows case-equivalent occurrences are ordered native facts marked
// as an ambiguity set without collapsing), §7 (Python strict duplicates),
// §9 (ini.duplicate-group@1 expansion); the group assignment order and the
// duplicate-section/duplicate-entry/case-collision codes follow
// crates/consema-ini/src/parser.rs:1212-1304. The L5 conformance runner
// executes the shared vectors directly; these tests are the L2 intent
// documents.

package ini

import consema.document.FormationStatus
import consema.protocol.Severity
import consema.ini.IniProfile
import consema.ini.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DuplicateGroupTest {

    /** RFC 0009 §5: exact duplicate keys inside one portable section make
     * formation Recovered with a duplicate-entry diagnostic; both physical
     * occurrences remain observable and share one group identity. */
    @Test
    fun portableDuplicateEntryRecovers() {
        val document = parse("[s]\na=1\na=2\n".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)

        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals(2, document.entries().size)
        val groups = document.entries().map { it.duplicateGroup }
        assertNotNull(groups[0])
        assertEquals(groups[0], groups[1])
        assertTrue(document.diagnostics().any { it.code == "ini.formation.duplicate-entry@1" })
    }

    /** RFC 0009 §5: portable section and key comparison is case-sensitive,
     * so differently cased names are NOT duplicates. */
    @Test
    fun portableComparisonIsCaseSensitive() {
        val document = parse(
            "[S]\nA=1\na=2\n".toByteArray(Charsets.UTF_8),
            IniProfile.PortableV1,
        )
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertTrue(document.entries().all { it.duplicateGroup == null })
    }

    /** RFC 0009 §6: repeated and case-equivalent Windows sections/keys are
     * Complete with Warning diagnostics; the case-collision group is
     * shared but nothing is collapsed. */
    @Test
    fun windowsCaseCollisionIsCompleteAmbiguity() {
        val document = parse(
            "[Main]\r\nName=one\r\nname=two\r\n[main]\r\nOther=three\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(2, document.sections().size)
        assertEquals(3, document.entries().size)
        val sectionGroups = document.sections().map { it.duplicateGroup }
        assertNotNull(sectionGroups[0])
        assertEquals(sectionGroups[0], sectionGroups[1])
        val entryGroups = document.entries().map { it.duplicateGroup }
        assertNotNull(entryGroups[0])
        assertEquals(entryGroups[0], entryGroups[1])
        val collision = document.diagnostics().first { it.code == "ini.formation.case-collision@1" }
        assertEquals(Severity.Warning, collision.severity)
    }

    /** RFC 0009 §5: duplicate section names in the portable profile make
     * formation Recovered with a duplicate-section diagnostic. */
    @Test
    fun portableDuplicateSectionRecovers() {
        val document = parse(
            "[s]\nx=1\n[s]\ny=2\n".toByteArray(Charsets.UTF_8),
            IniProfile.PortableV1,
        )
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals(2, document.sections().size)
        assertNotNull(document.sections()[0].duplicateGroup)
        assertEquals(document.sections()[0].duplicateGroup, document.sections()[1].duplicateGroup)
        assertTrue(document.diagnostics().any { it.code == "ini.formation.duplicate-section@1" })
    }

    /** RFC 0009 §7: Python strict duplicates compare by the lowercase
     * optionxform, so `Key` and `key` collide; duplicate sections recover
     * too. */
    @Test
    fun pythonStrictDuplicatesRecover() {
        val entries = parse(
            "[S]\nKey=1\nkey=2\n".toByteArray(Charsets.UTF_8),
            IniProfile.PythonConfigParserV1,
        )
        assertEquals(FormationStatus.Recovered, entries.formationStatus())
        assertEquals("key", entries.entries()[0].comparisonKey)
        assertEquals(entries.entries()[0].duplicateGroup, entries.entries()[1].duplicateGroup)
        assertTrue(entries.diagnostics().any { it.code == "ini.formation.case-collision@1" })

        val sections = parse(
            "[S]\nx=1\n[S]\ny=2\n".toByteArray(Charsets.UTF_8),
            IniProfile.PythonConfigParserV1,
        )
        assertEquals(FormationStatus.Recovered, sections.formationStatus())
        assertEquals(sections.sections()[0].duplicateGroup, sections.sections()[1].duplicateGroup)
        assertTrue(sections.diagnostics().any { it.code == "ini.formation.duplicate-section@1" })
    }

    /** RFC 0009 §8: duplicate groups never merge across sections in the
     * strict profiles (entry groups are section-scoped). */
    @Test
    fun duplicateGroupsAreSectionScoped() {
        val document = parse(
            "[a]\nk=1\n[b]\nk=2\n".toByteArray(Charsets.UTF_8),
            IniProfile.PortableV1,
        )
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertTrue(document.entries().all { it.duplicateGroup == null })
    }

    /** RFC 0009 §7: `DEFAULT` is a plain section for duplicate grouping
     * purposes (exact duplicates inside it still recover). */
    @Test
    fun defaultSectionDuplicatesStillRecover() {
        val document = parse(
            "[DEFAULT]\nbase=1\nbase=2\n".toByteArray(Charsets.UTF_8),
            IniProfile.PythonConfigParserV1,
        )
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals(document.entries()[0].duplicateGroup, document.entries()[1].duplicateGroup)
    }
}
