// Projection transcriptions from conformance/vectors/java-properties-v1.json.
//
// The default target java-properties.projection.best-exact-entry-mapping@1
// preserves every source-ordered association; RequireObject collapses
// duplicates only under an explicitly authorized Lossy policy (RFC 0010
// §11). Case ids are cited on every test; these tests pin the intent and
// run at the L2 verification gate.

package properties

import consema.core.PvEntryMapping
import consema.core.PvObject
import consema.core.PvString
import consema.document.SourceEncoding
import consema.properties.DuplicatePolicy
import consema.properties.Fidelity
import consema.properties.ProjectedLocation
import consema.properties.ProjectionRequest
import consema.properties.ProjectionResult
import consema.properties.ProvenanceRelation
import consema.properties.parseReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionTest {

    /** Vector case projection.exact-duplicates-and-fragments
     * (java-properties-v1.json:75-79): Exact fidelity with duplicate keys
     * preserved, two value fragments for the continued value, escape
     * provenance, and association provenance. */
    @Test
    fun exactDuplicatesAndFragments() {
        val document = parseReader(
            "a\\ key=one\\\n two\\u0021\na\\ key=last\n".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val result = document.project(ProjectionRequest.bestExactEntryMapping())
        val complete = result as ProjectionResult.Complete
        assertEquals(Fidelity.Exact, complete.projection.fidelity)
        assertEquals(0, complete.projection.report.events().size)

        val mapping = complete.projection.value as PvEntryMapping
        assertEquals(2, mapping.entries().size)
        assertEquals("a key", (mapping.entries()[0].key as PvString).value)
        assertEquals("onetwo!", (mapping.entries()[0].value as PvString).value)
        assertEquals("a key", (mapping.entries()[1].key as PvString).value)
        assertEquals("last", (mapping.entries()[1].value as PvString).value)

        val provenance = complete.projection.provenance.entries()
        assertTrue(
            provenance.any { entry ->
                entry.origins.any { it.relation == ProvenanceRelation.EscapeDerived }
            },
        )
        assertTrue(
            provenance.any { entry ->
                entry.origins.count { it.relation == ProvenanceRelation.ValueFragment } == 2
            },
        )
        assertTrue(
            provenance.any { entry ->
                entry.projected is ProjectedLocation.Association
            },
        )
    }

    /** Vector case projection.explicit-jdk-table-collapse
     * (java-properties-v1.json:85-89): RequireUnique rejects with
     * core.projection.target-not-applicable@1; FirstWins retains the first
     * association and reports exactly one duplicate-collapsed event;
     * LastWinsJdkTable matches a newly loaded JDK Properties table. */
    @Test
    fun explicitJdkTableCollapse() {
        val document = parseReader(
            "a=first\nb=middle\na=last\n".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )

        val rejected = document.project(
            ProjectionRequest.requireObject(DuplicatePolicy.RequireUnique),
        ) as ProjectionResult.Failed
        assertEquals(
            "core.projection.target-not-applicable@1",
            rejected.attempt.diagnostics[0].code,
        )

        val first = document.project(
            ProjectionRequest.requireObject(DuplicatePolicy.FirstWins),
        ) as ProjectionResult.Complete
        assertEquals(Fidelity.Lossy, first.projection.fidelity)
        assertEquals(1, first.projection.report.events().size)
        assertEquals(
            "java-properties.projection.duplicate-collapsed@1",
            first.projection.report.events()[0].code,
        )
        val firstObject = first.projection.value as PvObject
        assertEquals(2, firstObject.entries().size)
        assertEquals("a", firstObject.entries()[0].key)
        assertEquals("first", (firstObject.entries()[0].value as PvString).value)
        assertTrue(
            first.projection.provenance.entries().any { entry ->
                entry.origins.any { it.relation == ProvenanceRelation.Collapsed }
            },
        )

        val last = document.project(
            ProjectionRequest.requireObject(DuplicatePolicy.LastWinsJdkTable),
        ) as ProjectionResult.Complete
        val lastObject = last.projection.value as PvObject
        assertEquals(listOf("b", "a"), lastObject.entries().map { it.key })
        assertEquals("last", (lastObject.entries()[1].value as PvString).value)
        assertEquals(1, last.projection.report.events().size)
    }

    /** Vector case projection.unpaired-and-recovered-atomic-failure
     * (java-properties-v1.json:80-84): an unpaired surrogate fails the whole
     * projection atomically with the unpaired-surrogate code and the
     * offending property span; recovered documents fail with
     * java-properties.projection.incomplete-document@1; no partial mapping
     * and no partial report. */
    @Test
    fun unpairedAndRecoveredAtomicFailure() {
        val unpaired = parseReader(
            "a=ok\nb=\\uD800".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val failed = unpaired.project(
            ProjectionRequest.bestExactEntryMapping(),
        ) as ProjectionResult.Failed
        assertEquals(
            "java-properties.projection.unpaired-surrogate@1",
            failed.attempt.diagnostics[0].code,
        )
        assertEquals(5, failed.attempt.diagnostics[0].primary!!.startByte.toInt())
        assertEquals(0, failed.attempt.report.events().size)

        val recovered = parseReader(
            "good=ok\nbad=\\u12G4".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val recoveredFailed = recovered.project(
            ProjectionRequest.bestExactEntryMapping(),
        ) as ProjectionResult.Failed
        assertEquals(
            "java-properties.projection.incomplete-document@1",
            recoveredFailed.attempt.diagnostics[0].code,
        )
        assertEquals(0, recoveredFailed.attempt.report.events().size)
    }

    /** Vector case resource.projection-limit-matrix
     * (java-properties-v1.json:141-145): every projection limit fails with
     * core.projection.resource-limit@1 and no partial output. */
    @Test
    fun projectionLimitMatrix() {
        val complete = parseReader(
            "a=1\n".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val defaults = consema.properties.ProjectionLimits.default
        val limitsList = listOf(
            defaults.copy(maxSourceAssociations = 0),
            defaults.copy(maxValueNodes = 1),
            defaults.copy(maxProvenanceUnits = 1),
        )
        for (limits in limitsList) {
            val failed = complete.project(
                ProjectionRequest.bestExactEntryMapping().withLimits(limits),
            ) as ProjectionResult.Failed
            assertEquals(
                "core.projection.resource-limit@1",
                failed.attempt.diagnostics[0].code,
            )
            assertEquals(0, failed.attempt.report.events().size)
        }

        val duplicate = parseReader(
            "a=1\na=2\n".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val reportLimited = duplicate.project(
            ProjectionRequest.requireObject(DuplicatePolicy.FirstWins)
                .withLimits(defaults.copy(maxReportEntries = 0)),
        ) as ProjectionResult.Failed
        assertEquals(
            "core.projection.resource-limit@1",
            reportLimited.attempt.diagnostics[0].code,
        )
        assertEquals(0, reportLimited.attempt.report.events().size)
    }
}
