// Projection tests: exact EntryMapping preservation, explicit Object
// collapse, provenance relations, and limit failures.
//
// Authority: RFC 0009 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md:347-385)
// and the vector cases projection.exact-duplicate-entry-mapping,
// projection.explicit-object-collapse, and
// projection.fragmented-value-provenance (ini-v1.json:60-73);
// https://github.com/consema/consema-rs/blob/main/consema-ini/src/projection.rs is the byte-arbitration authority.

package ini

import consema.core.AssociationRole
import consema.core.PvEntryMapping
import consema.core.PvObject
import consema.core.PvString
import consema.ini.CollisionPolicy
import consema.ini.Fidelity
import consema.ini.NameComparison
import consema.ini.ProjectionEventKind
import consema.ini.ProjectionLimits
import consema.ini.ProjectionRequest
import consema.ini.ProjectionResult
import consema.ini.ProvenanceRelation
import consema.ini.IniProfile
import consema.ini.parse
import consema.ini.project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionTest {

    /** Vector case projection.exact-duplicate-entry-mapping (ini-v1.json:
     * 60-62): duplicate sections and keys remain duplicate associations in
     * order with Exact fidelity, zero events, and EntryMappingEntry
     * provenance. */
    @Test
    fun exactDuplicateEntryMapping() {
        val source = "[Main]\r\nName=one\r\nname=two\r\n[main]\r\nOther=three\r\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.WindowsV1)

        val result = document.project(ProjectionRequest.bestExactEntryMapping())
        val complete = result as ProjectionResult.Complete
        assertEquals(Fidelity.Exact, complete.projection.fidelity)
        assertEquals(0, complete.projection.report.events().size)

        val outer = complete.projection.value as PvEntryMapping
        assertEquals(listOf("Main", "main"), outer.entries().map { (it.key as PvString).value })
        val first = outer.entries()[0].value as PvEntryMapping
        assertEquals(listOf("Name", "name"), first.entries().map { (it.key as PvString).value })
        assertEquals(listOf("one", "two"), first.entries().map { (it.value as PvString).value })

        assertTrue(
            complete.projection.provenance.entries().any { entry ->
                entry.projected is consema.ini.ProjectedLocation.Association &&
                    (entry.projected as consema.ini.ProjectedLocation.Association)
                        .location.role == AssociationRole.EntryMappingEntry
            },
        )
    }

    /** Vector case projection.explicit-object-collapse (ini-v1.json:64-67):
     * ProfileEquivalent + Reject fails; First keeps the first occurrence
     * with two Transformed events; Last keeps the last occurrence; the
     * collapsed associations carry Collapsed provenance. */
    @Test
    fun explicitObjectCollapse() {
        val source = "[Main]\r\nName=one\r\nname=two\r\n[main]\r\nOther=three\r\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.WindowsV1)

        val rejected = document.project(
            ProjectionRequest.requireObject(NameComparison.ProfileEquivalent, CollisionPolicy.Reject),
        )
        assertTrue(rejected is ProjectionResult.Failed)

        val first = document.project(
            ProjectionRequest.requireObject(NameComparison.ProfileEquivalent, CollisionPolicy.First),
        ) as ProjectionResult.Complete
        assertEquals(Fidelity.Transformed, first.projection.fidelity)
        assertEquals(2, first.projection.report.events().size)
        assertTrue(
            first.projection.report.events().any {
                it.kind == ProjectionEventKind.SectionCollisionCollapsed
            },
        )
        assertTrue(
            first.projection.report.events().any {
                it.kind == ProjectionEventKind.EntryCollisionCollapsed
            },
        )
        val firstOuter = first.projection.value as PvObject
        assertEquals(1, firstOuter.entries().size)
        assertEquals("Main", firstOuter.entries()[0].key)
        val firstInner = firstOuter.entries()[0].value as PvObject
        assertEquals("Name", firstInner.entries()[0].key)
        assertEquals("one", (firstInner.entries()[0].value as PvString).value)

        val last = document.project(
            ProjectionRequest.requireObject(NameComparison.ProfileEquivalent, CollisionPolicy.Last),
        ) as ProjectionResult.Complete
        val lastOuter = last.projection.value as PvObject
        assertEquals("main", lastOuter.entries()[0].key)
        val lastInner = lastOuter.entries()[0].value as PvObject
        assertEquals("Other", lastInner.entries()[0].key)
        assertEquals("three", (lastInner.entries()[0].value as PvString).value)

        assertTrue(
            first.projection.provenance.entries().any { entry ->
                entry.origins.any { it.relation == ProvenanceRelation.Collapsed }
            },
        )
    }

    /** Vector case projection.explicit-object-collapse: OriginalExact +
     * Reject succeeds for distinctly spelled names (no fabricated
     * collision). */
    @Test
    fun objectProjectionOriginalExactSucceeds() {
        val source = "[Main]\r\nName=one\r\nname=two\r\n[main]\r\nOther=three\r\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.WindowsV1)
        val result = document.project(
            ProjectionRequest.requireObject(NameComparison.OriginalExact, CollisionPolicy.Reject),
        ) as ProjectionResult.Complete
        assertEquals(Fidelity.Exact, result.projection.fidelity)
        assertEquals(2, (result.projection.value as PvObject).entries().size)
    }

    /** Vector case projection.fragmented-value-provenance (ini-v1.json:69-
     * 72): Python continuation fragments and Windows quote-derived content
     * have distinct provenance relations. */
    @Test
    fun fragmentedValueProvenance() {
        val python = parse(
            "[s]\nkey = first\n  second\n".toByteArray(Charsets.UTF_8),
            IniProfile.PythonConfigParserV1,
        )
        val pythonProjection = python.project(ProjectionRequest.bestExactEntryMapping())
            as ProjectionResult.Complete
        assertTrue(
            pythonProjection.projection.provenance.entries().any { entry ->
                entry.origins.any { it.relation == ProvenanceRelation.ContinuationFragment }
            },
        )

        val windows = parse(
            "[s]\r\nk=\" value \"\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )
        val windowsProjection = windows.project(ProjectionRequest.bestExactEntryMapping())
            as ProjectionResult.Complete
        assertTrue(
            windowsProjection.projection.provenance.entries().any { entry ->
                entry.origins.any { it.relation == ProvenanceRelation.QuoteDerived }
            },
        )
    }

    /** Vector case projection.exact-duplicate-entry-mapping: the Python
     * default section stays an ordinary association whose provenance
     * carries the DefaultSection role. */
    @Test
    fun pythonDefaultSectionIsOrdinaryAssociation() {
        val document = parse(
            "[DEFAULT]\nbase=1\n[s]\nvalue=2\n".toByteArray(Charsets.UTF_8),
            IniProfile.PythonConfigParserV1,
        )
        val projection = document.project(ProjectionRequest.bestExactEntryMapping())
            as ProjectionResult.Complete
        val outer = projection.projection.value as PvEntryMapping
        assertEquals(2, outer.entries().size)
        assertEquals("DEFAULT", (outer.entries()[0].key as PvString).value)
        assertTrue(
            projection.projection.provenance.entries().any { entry ->
                entry.origins.any { it.node.role == consema.document.NodeRole.IniDefaultSection }
            },
        )
    }

    /** Vector case resource.projection-limit-matrix (ini-v1.json:131-133):
     * max_source_associations, max_value_nodes, and max_provenance_units
     * each fail with core.projection.resource-limit@1. */
    @Test
    fun projectionLimitsFailWithoutValues() {
        val document = parse("[s]\na=1\n".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)
        val limitSets = listOf(
            ProjectionLimits(maxSourceAssociations = 1, maxValueNodes = 2_000_000, maxReportEntries = 100_000, maxProvenanceUnits = 4_000_000),
            ProjectionLimits(maxSourceAssociations = 2_000_000, maxValueNodes = 1, maxReportEntries = 100_000, maxProvenanceUnits = 4_000_000),
            ProjectionLimits(maxSourceAssociations = 2_000_000, maxValueNodes = 2_000_000, maxReportEntries = 100_000, maxProvenanceUnits = 1),
        )
        for (limits in limitSets) {
            val result = document.project(
                ProjectionRequest.bestExactEntryMapping().withLimits(limits),
            )
            val failed = result as ProjectionResult.Failed
            assertEquals("core.projection.resource-limit@1", failed.attempt.diagnostics[0].code)
        }
    }
}
