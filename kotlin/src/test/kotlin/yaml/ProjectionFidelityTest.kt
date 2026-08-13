// YAML projection tests: exact graph projection with provenance, value
// projection policies, fidelity and events, and the frozen failure codes.
//
// Data authority: RFC 0007 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0007-yaml-family-profiles-and-
// safety-v1.md:260-302) and the vector cases projection.sharing-policy,
// projection.cycle, projection.tag-policy, projection.mapping-policy,
// projection.graph-provenance, graph.shared-cycle, resource.graph-provenance
// (conformance/vectors/yaml-v1.json:45-49, 70-94, 130-134). The PGCE golden
// hex of graph.shared-cycle is the byte authority
// (consema-rs/consema-graph/src/pgce.rs; transcribed into the Kotlin graph
// PgceGoldenTest.kt).
//
// This file runs in the verified toolchain gate (kotlin-gates gradlew
// test / the scripts/kotlin-verify-*.ps1 direct path): the toolchain is
// verified and this file is executed.

package yaml

import consema.core.PvString
import consema.graph.encodePgce
import consema.yaml.Fidelity
import consema.yaml.GraphProjectionFailure
import consema.yaml.GraphProjectionLimits
import consema.yaml.GraphProjectionRequest
import consema.yaml.MappingPolicy
import consema.yaml.ProjectionEventKind
import consema.yaml.ProvenanceRelation
import consema.yaml.SharingPolicy
import consema.yaml.TagPolicy
import consema.yaml.ValueProjectionLimits
import consema.yaml.ValueProjectionRequest
import consema.yaml.ValueProjectionResult
import consema.yaml.ValueProjectionFailure
import consema.yaml.YamlProfile
import consema.yaml.graphProjectionCode
import consema.yaml.parse
import consema.yaml.projectGraph
import consema.yaml.projectGraphWithProvenance
import consema.yaml.projectValue
import consema.yaml.valueProjectionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionFidelityTest {

    /** Vector case graph.shared-cycle (yaml-v1.json:45-49): the shared
     * self-cycle projects to exactly two nodes and one root, and the PGCE
     * bytes match the golden hex byte-for-byte. */
    @Test
    fun graphSharedCyclePinsPgceBytes() {
        val document = parse("&root [one, *root]\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val graph = document.projectGraph()
        assertEquals(2, graph.nodeCount())
        assertEquals(1, graph.roots().size)
        assertEquals(
            "504743450101020040157461673a79616d6c2e6f72672c323030323a73657102010020157461673a79616d6c2e6f72672c323030323a737472036f6e65",
            encodePgce(graph).toHexString(),
        )
    }

    /** Vector case projection.sharing-policy (yaml-v1.json:70-74): shared
     * identity is rejected by default (yaml.projection.sharing@1) and the
     * explicit DuplicateAcyclic policy reports exactly three duplication
     * events with Transformed fidelity. */
    @Test
    fun sharingPolicyRejectsThenDuplicatesWithEvents() {
        val document = parse("[&x {k: v}, *x]\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val rejected = document.projectValue(ValueProjectionRequest.bestExactV1())
        val failed = rejected as ValueProjectionResult.Failed
        assertEquals("yaml.projection.sharing@1", valueProjectionCode(failed.failure))

        val duplicated = document.projectValue(
            ValueProjectionRequest.bestExactV1().withSharing(SharingPolicy.DuplicateAcyclic),
        )
        val complete = duplicated as ValueProjectionResult.Complete
        assertEquals(Fidelity.Transformed, complete.projection.fidelity)
        assertEquals(3, complete.projection.report.events().size)
        assertTrue(
            complete.projection.report.events().all {
                it.kind == ProjectionEventKind.SharingDuplicated
            },
        )
    }

    /** Vector case projection.cycle (yaml-v1.json:75-79): a cycle never
     * enters a PortableValue tree, even under an explicit duplication
     * policy; the failure code is yaml.projection.cycle@1. */
    @Test
    fun cyclesNeverEnterPortableValues() {
        val document = parse("&x [*x]\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val result = document.projectValue(
            ValueProjectionRequest.bestExactV1().withSharing(SharingPolicy.DuplicateAcyclic),
        )
        val failed = result as ValueProjectionResult.Failed
        assertEquals("yaml.projection.cycle@1", valueProjectionCode(failed.failure))
    }

    /** Vector case projection.tag-policy (yaml-v1.json:80-84): a custom tag
     * fails by default (yaml.projection.unsupported-tag@1) and explicit
     * stripping yields the decoded string with Lossy fidelity. */
    @Test
    fun tagPolicyRejectsThenStrips() {
        val document = parse("!example value\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val rejected = document.projectValue(ValueProjectionRequest.bestExactV1())
        val failed = rejected as ValueProjectionResult.Failed
        assertEquals("yaml.projection.unsupported-tag@1", valueProjectionCode(failed.failure))

        val stripped = document.projectValue(
            ValueProjectionRequest.bestExactV1().withTags(TagPolicy.StripToNodeKind),
        )
        val complete = stripped as ValueProjectionResult.Complete
        assertEquals(PvString("value"), complete.projection.value)
        assertEquals(Fidelity.Lossy, complete.projection.fidelity)
    }

    /** Vector case projection.mapping-policy (yaml-v1.json:85-89): a
     * duplicate-key mapping cannot satisfy an Object policy
     * (yaml.projection.mapping-not-object@1) and RequireEntryMapping
     * preserves both associations. */
    @Test
    fun mappingPolicyPreservesDuplicates() {
        val document = parse("{a: 1, a: 2}\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val rejected = document.projectValue(
            ValueProjectionRequest.bestExactV1().withMapping(MappingPolicy.RequireObject),
        )
        val failed = rejected as ValueProjectionResult.Failed
        assertEquals("yaml.projection.mapping-not-object@1", valueProjectionCode(failed.failure))

        val entryMapping = document.projectValue(
            ValueProjectionRequest.bestExactV1().withMapping(MappingPolicy.RequireEntryMapping),
        )
        val complete = entryMapping as ValueProjectionResult.Complete
        val mapping = complete.projection.value as consema.core.PvEntryMapping
        assertEquals(2, mapping.entries().size)
    }

    /** Vector case projection.graph-provenance (yaml-v1.json:90-94): the
     * graph provenance has one Reference origin (the alias) and two
     * sequence-edge association entries, with Direct relation for the
     * element edges. */
    @Test
    fun graphProvenanceTracksAliasReferences() {
        val document = parse("&root [one, *root]\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val projection = document.projectGraphWithProvenance(GraphProjectionRequest.bestExactV1())
        val entries = projection.provenance.entries()
        val references = entries.sumOf { entry -> entry.origins.count { it.relation == ProvenanceRelation.Reference } }
        val associationEntries = entries.count {
            it.projected is consema.yaml.GraphProjectedLocation.SequenceElement
        }
        assertEquals(1, references)
        assertEquals(2, associationEntries)
        val second = entries.first {
            it.projected is consema.yaml.GraphProjectedLocation.SequenceElement &&
                (it.projected as consema.yaml.GraphProjectedLocation.SequenceElement).ordinal == 1L
        }
        assertEquals(2, second.origins.size)
    }

    /** Vector case resource.graph-provenance (yaml-v1.json:130-134): the
     * provenance limit fails atomically with
     * yaml.projection.provenance-limit@1. */
    @Test
    fun provenanceLimitFailsAtomically() {
        val document = parse("[one, two]\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val request = GraphProjectionRequest.bestExactV1().withLimits(
            GraphProjectionLimits(
                graph = consema.graph.GraphLimits.default,
                maxProvenanceEntries = 1,
            ),
        )
        val error = kotlin.runCatching { document.projectGraphWithProvenance(request) }
            .exceptionOrNull() as consema.yaml.GraphProjectionException
        assertEquals(
            "yaml.projection.provenance-limit@1",
            graphProjectionCode(error.failure),
        )
        assertTrue(error.failure is GraphProjectionFailure.ProvenanceLimit)
    }

    /** RFC 0007 §10: value projection resource limits fail with
     * yaml.projection.resource-limit@1 (the frozen limit name is
     * reported). */
    @Test
    fun valueProjectionLimitsFail() {
        val document = parse("{a: [1, 2]}\n".toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val result = document.projectValue(
            ValueProjectionRequest.bestExactV1().withLimits(
                ValueProjectionLimits(
                    maxValueNodes = 1,
                    maxDepth = 256,
                    maxReportEntries = 100_000,
                    maxProvenanceEntries = 2_000_000,
                    maxAmplificationRatio = 16,
                ),
            ),
        )
        val failed = result as ValueProjectionResult.Failed
        assertEquals("yaml.projection.resource-limit@1", valueProjectionCode(failed.failure))
        assertTrue(failed.failure is consema.yaml.ValueProjectionFailure.ResourceLimit)
    }

    /** RFC 0007 §10: a multi-document stream cannot satisfy a single-value
     * projection (yaml.projection.document-cardinality@1) but still
     * projects to one exact graph. */
    @Test
    fun multiDocumentValueProjectionRejected() {
        val document = parse(
            "---\na\n---\nb\n".toByteArray(Charsets.UTF_8),
            YamlProfile.Yaml12CoreV1,
        )
        val result = document.projectValue(ValueProjectionRequest.bestExactV1())
        val failed = result as ValueProjectionResult.Failed
        assertEquals(
            "yaml.projection.document-cardinality@1",
            valueProjectionCode(failed.failure),
        )
        val graph = document.projectGraph()
        assertEquals(2, graph.roots().size)
    }
}

/** Lowercase hex of one byte array (golden transcription helper). */
private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }
