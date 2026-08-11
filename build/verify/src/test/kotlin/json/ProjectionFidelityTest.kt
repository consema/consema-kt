// Projection fidelity and conversion-closure tests transcribed from
// conformance/vectors/json-family-v2.json.
//
// Vector case json5.projection.duplicates-nonfinite (json-family-v2.json:
// 126-130) pins the EntryMapping result with the frozen binary bits; vector
// case json5.projection.old-target-rejected (json-family-v2.json:132-136)
// pins target-not-applicable; vector case json5.convert.finite-to-strict
// (json-family-v2.json:156-160) pins the dialect-conversion closure
// (projection + materialization composition, RFC 0005 §9).

package json

import consema.core.PvEntryMapping
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.json.Fidelity
import consema.json.JsonProfile
import consema.json.ProjectionRequest
import consema.json.ProjectionResult
import consema.json.ProjectionTarget
import consema.json.SemanticAvailability
import consema.json.materialize
import consema.json.parse
import consema.json.project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionFidelityTest {

    /** Vector case json5.projection.duplicates-nonfinite (json-family-v2.json:
     * 126-130): duplicate names map exactly to an EntryMapping under
     * json5.projection.best-exact-core@1 with fidelity Transformed and the
     * frozen non-finite bits preserved (RFC 0005 §8). */
    @Test
    fun duplicatesProjectToEntryMappingWithFrozenBits() {
        val document = parse(
            "{a:Infinity,a:-NaN}".toByteArray(Charsets.UTF_8),
            JsonProfile.Json5StandardV1,
        )
        val request = ProjectionRequest.builder(ProjectionTarget.Json5BestExactCoreV1).build()
        val result = document.project(request)
        val projection = result as ProjectionResult.Complete

        assertEquals(Fidelity.Transformed, projection.projection.fidelity)
        val mapping = projection.projection.value as? PvEntryMapping
        assertEquals(2, mapping!!.size())
        assertEquals(
            listOf("7ff0000000000000", "fff8000000000000"),
            mapping.entries().map { "%016x".format((it.value as consema.core.PvBinaryFloat64).bits) },
        )
        // The structure-reencoded report event is explicit and reversible.
        assertEquals(
            "StructureReencoded",
            projection.projection.report.events().single().kind.name,
        )
        // Association provenance covers both duplicate members exactly.
        assertEquals(
            2,
            projection.projection.provenance.entries()
                .filter { it.projected is consema.json.ProjectedLocation.Association }
                .size,
        )
    }

    /** Vector case json5.projection.old-target-rejected (json-family-v2.json:
     * 132-136): the frozen json.projection.best-exact-core@1 target does not
     * apply to a JSON5 document — target-not-applicable, never a silent
     * widening (RFC 0005 §8). */
    @Test
    fun oldTargetRejectedForJson5() {
        val document = parse("{a:1}".toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        val request = ProjectionRequest.builder(ProjectionTarget.BestExactCoreV1).build()
        val result = document.project(request)
        val failed = result as ProjectionResult.Failed
        assertTrue(failed.attempt.diagnostics.any { it.code == "core.projection.target-not-applicable@1" })
    }

    /** Vector case json5.convert.finite-to-strict (json-family-v2.json:
     * 156-160): projection of the JSON5 document followed by strict
     * materialization reproduces the canonical strict output — the two-stage
     * conversion closure with fidelity Exact (RFC 0005 §9). */
    @Test
    fun json5ToStrictConversionClosure() {
        val document = parse(
            "{service:{port:8080,},}".toByteArray(Charsets.UTF_8),
            JsonProfile.Json5StandardV1,
        )
        val projectionRequest =
            ProjectionRequest.builder(ProjectionTarget.Json5BestExactCoreV1).build()
        val projection = document.project(projectionRequest) as ProjectionResult.Complete

        val materializationRequest = MaterializationRequest.new(
            ProfileId("json.strict", 1),
            MaterializationStyleId("json.canonical-compact", 1),
        ).withNewline(NewlinePolicy.None)
        val materialization = materialize(projection.projection.value, materializationRequest)
        val complete = materialization as MaterializationResult.Complete

        assertEquals(
            "{\"service\":{\"port\":8080}}",
            complete.materialization.document.render().toString(Charsets.UTF_8),
        )
        assertEquals(consema.document.MaterializationFidelity.Exact, complete.materialization.fidelity)

        // Closure: the strict document reprojects to the identical value.
        val strictRequest =
            ProjectionRequest.builder(ProjectionTarget.BestExactCoreV1).build()
        val reprojection =
            complete.materialization.document.project(strictRequest) as ProjectionResult.Complete
        assertEquals(projection.projection.value, reprojection.projection.value)
    }

    /** Vector case projection.object-reject-duplicates (v1.json:95-99):
     * ProjectAsObject under the conservative Reject policy fails with
     * json.projection.duplicate-keys@1 and no partial value. */
    @Test
    fun objectProjectionRejectsDuplicates() {
        val document = parse(
            "{\"a\":1,\"a\":2}".toByteArray(Charsets.UTF_8),
            JsonProfile.StrictV1,
        )
        val request = ProjectionRequest.builder(ProjectionTarget.ProjectAsObjectV1).build()
        val result = document.project(request)
        val failed = result as ProjectionResult.Failed
        assertTrue(failed.attempt.diagnostics.any { it.code == "json.projection.duplicate-keys@1" })
    }

    /** Vector case projection.object-last-wins (v1.json:101-105): an
     * explicit LastWins policy is Lossy with DuplicateCollapsed events and
     * key provenance of the retained occurrence. */
    @Test
    fun objectProjectionLastWinsIsExplicitLoss() {
        val document = parse(
            "{\"a\":1,\"a\":2}".toByteArray(Charsets.UTF_8),
            JsonProfile.StrictV1,
        )
        val request = ProjectionRequest.builder(ProjectionTarget.ProjectAsObjectV1)
            .globalDuplicatePolicy(consema.json.DuplicateKeyPolicy.LastWins)
            .build()
        val result = document.project(request)
        val projection = (result as ProjectionResult.Complete).projection
        assertEquals(Fidelity.Lossy, projection.fidelity)
        assertEquals(
            "DuplicateCollapsed",
            projection.report.events().single().kind.name,
        )
        val objectValue = projection.value as consema.core.PvObject
        assertEquals(1, objectValue.size())
        assertEquals("2", (objectValue.get("a") as consema.core.PvInteger).value.toString())
        assertEquals(
            1,
            projection.provenance.entries()
                .filter {
                    val location = it.projected as? consema.json.ProjectedLocation.Association
                    location != null &&
                        location.location.role == consema.core.AssociationRole.ObjectKey
                }
                .size,
        )
    }

    /** Recovered documents never reach projection: the Recovered gate emits
     * json.projection.incomplete-document@1 (projection.rs:754-757; 0.13.0
     * audit finding F3). */
    @Test
    fun recoveredDocumentsAreRejectedAtProjection() {
        val document = parse(
            "{\"a\"1,...}".toByteArray(Charsets.UTF_8),
            JsonProfile.StrictV1,
        )
        assertEquals(consema.document.FormationStatus.Recovered, document.formationStatus())
        val request = ProjectionRequest.builder(ProjectionTarget.BestExactCoreV1).build()
        val result = document.project(request)
        val failed = result as ProjectionResult.Failed
        assertTrue(failed.attempt.diagnostics.any { it.code == "json.projection.incomplete-document@1" })
    }

    /** Vector case json5.projection.duplicates-nonfinite also asserts the
     * JSON5 root kind stays available for native query. */
    @Test
    fun json5RootKindIsAvailable() {
        val document = parse("-Infinity".toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        assertEquals(
            "BinaryFloat64",
            (document.root().kind() as SemanticAvailability.Available).value.name,
        )
    }
}
