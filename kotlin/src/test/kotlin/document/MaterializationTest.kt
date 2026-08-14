// MaterializationRequest and provenance tests.
//
// Data authority: RFC 0004 §3 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
// structural-edit-v1.md) — the common request fields and the closed
// v1 policies (ExactOnly representability, RequireObject /
// UniqueStringEntriesToObject mapping policy, None|Lf|CrLf newline);
// https://github.com/consema/consema-rs/blob/main/consema-document/src/materialization.rs (request defaults),
// materialization.rs (newline bytes), materialization.rs
// (provenance validation), materialization.rs (failure codes).

package document

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.Kind
import consema.core.ValuePath
import consema.document.CompleteMaterialization
import consema.document.DocumentAuthority
import consema.document.MaterializationException
import consema.document.MaterializationFailureKind
import consema.document.MaterializationFidelity
import consema.document.MaterializationInputLocation
import consema.document.MaterializationLimits
import consema.document.MaterializationProvenanceEntry
import consema.document.MaterializationProvenanceMap
import consema.document.MaterializationRelation
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.MaterializedOrigin
import consema.document.MappingPolicy
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.document.RepresentabilityPolicy
import consema.document.SourceEncoding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MaterializationTest {

    private val profile = ProfileId("json.strict", 1)
    private val style = MaterializationStyleId("json.canonical-pretty", 1)

    /** RFC 0004 §4 freezes these style IDs and target profiles. */
    @Test
    fun frozenStyleIdsAndProfiles() {
        assertEquals("json.canonical-compact@1", MaterializationStyleId("json.canonical-compact", 1).let { "${it.id}@${it.version}" })
        assertEquals("json.canonical-pretty@1", MaterializationStyleId("json.canonical-pretty", 1).let { "${it.id}@${it.version}" })
        assertEquals("toml.canonical-document@1", MaterializationStyleId("toml.canonical-document", 1).let { "${it.id}@${it.version}" })
        assertEquals("json.strict@1", ProfileId("json.strict", 1).let { "${it.id}@${it.version}" })
        assertEquals("jsonc.bounded@1", ProfileId("jsonc.bounded", 1).let { "${it.id}@${it.version}" })
        assertEquals("toml.1.0@1", ProfileId("toml.1.0", 1).let { "${it.id}@${it.version}" })
    }

    /** materialization.rs: the strict request defaults are UTF-8,
     * LF, RequireObject, ExactOnly, and the frozen limits. */
    @Test
    fun requestDefaultsAreStrict() {
        val request = MaterializationRequest.new(profile, style)
        assertEquals(profile, request.targetProfile)
        assertEquals(style, request.style)
        assertEquals(SourceEncoding.Utf8, request.encoding)
        assertEquals(NewlinePolicy.Lf, request.newline)
        assertEquals(MappingPolicy.RequireObject, request.mappingPolicy)
        assertEquals(RepresentabilityPolicy.ExactOnly, request.representability)
        assertEquals(MaterializationLimits.default, request.limits)
    }

    /** materialization.rs: the exact newline bytes. */
    @Test
    fun newlineBytesAreExact() {
        assertTrue(NewlinePolicy.None.bytes().isEmpty())
        assertTrue(NewlinePolicy.Lf.bytes().contentEquals(byteArrayOf(0x0a)))
        assertTrue(NewlinePolicy.CrLf.bytes().contentEquals(byteArrayOf(0x0d, 0x0a)))
    }

    /** RFC 0004 §3: UniqueStringEntriesToObject is an explicit, reportable
     * conversion; RequireObject is the default. */
    @Test
    fun mappingPoliciesAreClosed() {
        assertEquals(2, MappingPolicy.entries.size)
        assertEquals(MappingPolicy.RequireObject, MappingPolicy.entries[0])
        assertEquals(MappingPolicy.UniqueStringEntriesToObject, MappingPolicy.entries[1])
        assertEquals(1, RepresentabilityPolicy.entries.size)
        assertEquals(RepresentabilityPolicy.ExactOnly, RepresentabilityPolicy.entries[0])
    }

    /** RFC 0004 §7: fidelity is closed binary Exact | Transformed. */
    @Test
    fun fidelityIsClosedBinary() {
        assertEquals(listOf(MaterializationFidelity.Exact, MaterializationFidelity.Transformed), MaterializationFidelity.entries)
    }

    /** materialization.rs: every explicit policy is kept. */
    @Test
    fun requestKeepsEveryExplicitPolicy() {
        val request = MaterializationRequest.new(profile, style)
            .withEncoding(SourceEncoding.Utf8)
            .withNewline(NewlinePolicy.CrLf)
            .withMappingPolicy(MappingPolicy.UniqueStringEntriesToObject)
            .withLimits(MaterializationLimits(maxOutputBytes = 10, maxInputNodes = 10, maxDepth = 2, maxReportEntries = 1, maxProvenanceEntries = 1))
        assertEquals(NewlinePolicy.CrLf, request.newline)
        assertEquals(MappingPolicy.UniqueStringEntriesToObject, request.mappingPolicy)
        assertEquals(10, request.limits.maxOutputBytes)
        assertEquals(10, request.limits.maxInputNodes)
    }

    /** materialization.rs: provenance is target-bound, requires
     * non-empty outputs, and enforces its combined limit. */
    @Test
    fun provenanceIsTargetBoundAndLimited() {
        val target = DocumentAuthority.fresh()
        val origin = MaterializedOrigin(
            snapshot = target.identity,
            node = target.nodeRef(0, consema.document.NodeRole.Value),
            span = target.span(0, 1),
            relation = MaterializationRelation.Direct,
        )
        val entry = MaterializationProvenanceEntry(
            input = MaterializationInputLocation.Association(
                AssociationLocation(ValuePath.root(), 0, AssociationRole.ObjectEntry),
            ),
            outputs = listOf(origin),
        )
        val map = MaterializationProvenanceMap.new(
            listOf(entry),
            target.identity,
            MaterializationLimits.default,
        )
        assertEquals(listOf(entry), map.entries())

        // RFC 0004 §8: missing locators fail; identities are not silently
        // dropped.
        val emptyOutputs = assertFailsWith<MaterializationException> {
            MaterializationProvenanceMap.new(
                listOf(
                    MaterializationProvenanceEntry(
                        input = MaterializationInputLocation.Value(ValuePath.root()),
                        outputs = emptyList(),
                    ),
                ),
                target.identity,
                MaterializationLimits.default,
            )
        }
        assertEquals(MaterializationFailureKind.INVALID_REQUEST, emptyOutputs.kind)
        assertEquals("core.materialization.invalid-request@1", emptyOutputs.code)

        // An origin bound to another snapshot is refused.
        val other = DocumentAuthority.fresh()
        val foreign = MaterializedOrigin(
            snapshot = other.identity,
            node = other.nodeRef(0, consema.document.NodeRole.Value),
            span = other.span(0, 1),
            relation = MaterializationRelation.Generated,
        )
        val foreignError = assertFailsWith<MaterializationException> {
            MaterializationProvenanceMap.new(
                listOf(
                    MaterializationProvenanceEntry(
                        input = MaterializationInputLocation.Value(ValuePath.root()),
                        outputs = listOf(foreign),
                    ),
                ),
                target.identity,
                MaterializationLimits.default,
            )
        }
        assertEquals(MaterializationFailureKind.INVALID_REQUEST, foreignError.kind)
    }

    /** materialization.rs: the provenance limit counts entries and
     * origins combined. */
    @Test
    fun provenanceCombinedLimitIsEnforced() {
        val target = DocumentAuthority.fresh()
        val limits = MaterializationLimits(
            maxInputNodes = 10, maxOutputBytes = 10, maxDepth = 2,
            maxReportEntries = 10, maxProvenanceEntries = 1,
        )
        val entries = listOf(
            MaterializationProvenanceEntry(
                input = MaterializationInputLocation.Value(ValuePath.root()),
                outputs = listOf(
                    MaterializedOrigin(target.identity, target.nodeRef(0, consema.document.NodeRole.Value), target.span(0, 1), MaterializationRelation.Direct),
                    MaterializedOrigin(target.identity, target.nodeRef(1, consema.document.NodeRole.Value), target.span(1, 2), MaterializationRelation.Direct),
                ),
            ),
        )
        val error = assertFailsWith<MaterializationException> {
            MaterializationProvenanceMap.new(entries, target.identity, limits)
        }
        assertEquals(MaterializationFailureKind.RESOURCE_LIMIT, error.kind)
        assertEquals("core.materialization.resource-limit@1", error.code)
    }

    /** RFC 0004 §7 + materialization.rs: the result algebra is
     * exactly Complete or Failed. */
    @Test
    fun resultAlgebraIsClosed() {
        val result: MaterializationResult<String> =
            MaterializationResult.Complete(
                CompleteMaterialization(
                    document = "<target>",
                    fidelity = MaterializationFidelity.Exact,
                    report = consema.document.MaterializationReport.new(emptyList(), MaterializationLimits.default),
                    provenance = MaterializationProvenanceMap.new(emptyList(), DocumentAuthority.fresh().identity, MaterializationLimits.default),
                ),
            )
        assertIs<MaterializationResult.Complete<String>>(result)
        assertEquals(MaterializationFidelity.Exact, result.materialization.fidelity)
    }

    /** materialization.rs: every failure kind carries its frozen
     * registered code. */
    @Test
    fun failureKindsCarryFrozenCodes() {
        assertEquals(
            listOf(
                "core.materialization.invalid-request@1",
                "core.materialization.unsupported-profile@1",
                "core.materialization.unsupported-style@1",
                "core.materialization.unsupported-encoding@1",
                "core.materialization.unsupported-newline@1",
                "core.materialization.unrepresentable@1",
                "core.materialization.resource-limit@1",
                "core.materialization.formation-failed@1",
            ),
            MaterializationFailureKind.entries.map { it.code },
        )
        val unrepresentable = MaterializationException(
            MaterializationFailureKind.UNREPRESENTABLE,
            path = ValuePath.root(),
            valueKind = Kind.Bytes,
        )
        assertEquals("core.materialization.unrepresentable@1", unrepresentable.code)
        assertEquals(Kind.Bytes, unrepresentable.valueKind)
    }
}
