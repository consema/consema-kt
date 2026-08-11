// The semantic-model v5/v6 protocol record tests.
//
// Data authority (language-neutral sources first):
//   - crates/consema-protocol/src/portable_graph.rs, graph_query.rs,
//     graph_projection.rs, yaml_query.rs, line_query.rs, source.rs,
//     materialization.rs (the record contracts this package transcribes).
//   - conformance/vectors/semantic-model-v5.json and semantic-model-v6.json
//     (the shared vector behaviors pinned below).
//
// Kotlin-idiomatic design: each test exercises the record through its
// strict fromValue/toValue codecs and the frozen failure codes.

package consema.protocol

import consema.core.Entry
import consema.core.PvArray
import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import consema.core.equal
import consema.document.DecodedOffset
import consema.document.MaterializationLimits
import consema.document.MaterializationStyleId
import consema.document.ProfileId
import consema.graph.Builder
import consema.graph.GraphLimits
import consema.graph.MappingEntry
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordsV5V6Test {

    // ------------------------------------------------------------------
    // core.portable-graph@1 (portable_graph.rs).
    // ------------------------------------------------------------------

    private fun sharedGraph(): consema.graph.Graph {
        val builder = Builder.withLimits(GraphLimits.default)
        val shared = builder.reserveNode()
        val mapping = builder.reserveNode()
        val sequence = builder.reserveNode()
        builder.defineScalar(shared, "tag:yaml.org,2002:str", "key")
        builder.defineMapping(
            mapping,
            "tag:yaml.org,2002:map",
            listOf(MappingEntry(shared, sequence), MappingEntry(shared, mapping)),
        )
        builder.defineSequence(sequence, "tag:yaml.org,2002:seq", listOf(shared, mapping))
        builder.pushRoot(mapping)
        builder.pushRoot(sequence)
        return builder.build()
    }

    @Test
    fun portableGraphRoundTripsWithCanonicalIds() {
        val message = PortableGraphMessage.fromGraph(sharedGraph(), consema.graph.PgceLimits.default)
        val decoded = PortableGraphMessage.fromValue(message.toValue(), consema.graph.PgceLimits.default)
        assertTrue(consema.graph.equal(decoded.graph(), message.graph()), "graph identity")
        assertTrue(decoded.pgce().contentEquals(message.pgce()), "PGCE byte identity")
        // The readable node records carry canonical array-index IDs.
        val nodes = (message.toValue() as PvObject).get("nodes") as PvArray
        for ((index, node) in nodes.items().withIndex()) {
            val id = ((node as PvObject).get("id") as PvInteger).value
            assertEquals(BigInteger.valueOf(index.toLong()), id, "node $index id")
        }
    }

    @Test
    fun portableGraphReadablePgceDisagreementIsRejected() {
        val message = PortableGraphMessage.fromGraph(sharedGraph(), consema.graph.PgceLimits.default)
        val value = message.toValue() as PvObject
        val nodes = (value.get("nodes") as PvArray).items().map { it }
        val changedNodes = PvArray(nodes.mapIndexed { index, node ->
            if (index == 1) {
                // Canonical order is [mapping, scalar, sequence]; the scalar
                // record carries the canonical_content member.
                replaceField(node as PvObject, "canonical_content", PvString("changed"))
            } else {
                node
            }
        })
        val changed = replaceField(value, "nodes", changedNodes)
        val failure = assertFailsWith<ProtocolException> {
            PortableGraphMessage.fromValue(changed, consema.graph.PgceLimits.default)
        }
        assertEquals(ProtocolErrorKind.INVALID_VALUE, failure.kind)
    }

    @Test
    fun portableGraphExplicitNodeLimitAppliesBeforeAllocation() {
        val message = PortableGraphMessage.fromGraph(sharedGraph(), consema.graph.PgceLimits.default)
        val limits = consema.graph.PgceLimits.default.copy(maxNodes = 1)
        val failure = assertFailsWith<ProtocolException> {
            PortableGraphMessage.fromValue(message.toValue(), limits)
        }
        assertEquals(ProtocolErrorKind.RESOURCE_LIMIT, failure.kind)
    }

    // ------------------------------------------------------------------
    // core.graph-query-result@1 (graph_query.rs).
    // ------------------------------------------------------------------

    @Test
    fun graphQueryResultMappingRoundTrips() {
        val builder = Builder.withLimits(GraphLimits.default)
        val key = builder.reserveNode()
        val mapping = builder.reserveNode()
        val value = builder.reserveNode()
        builder.defineScalar(key, "tag:yaml.org,2002:str", "key")
        builder.defineScalar(value, "tag:yaml.org,2002:str", "value")
        builder.defineMapping(mapping, "tag:yaml.org,2002:map", listOf(MappingEntry(key, value)))
        builder.pushRoot(mapping)
        val graph = PortableGraphMessage.fromGraph(builder.build(), consema.graph.PgceLimits.default)
        val completion = Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null)
        val match = GraphQueryMatchMessage.MappingEntry(0uL, 0uL, 1uL, 2uL)
        val result = GraphQueryResultMessage.new(
            Domains.portableGraphV1(),
            Roles.GRAPH_MAPPING_ENTRY,
            graph,
            listOf(match),
            completion,
            emptyList(),
        )
        val decoded = GraphQueryResultMessage.fromValue(result.toValue())
        assertTrue(equal(decoded.toValue(), result.toValue()), "graph query result round trip")
    }

    @Test
    fun graphQueryResultDanglingAssociationIsRejected() {
        val builder = Builder.withLimits(GraphLimits.default)
        val sequence = builder.reserveNode()
        val scalar = builder.reserveNode()
        builder.defineScalar(scalar, "tag:yaml.org,2002:str", "x")
        builder.defineSequence(sequence, "tag:yaml.org,2002:seq", listOf(scalar))
        builder.pushRoot(sequence)
        val graph = PortableGraphMessage.fromGraph(builder.build(), consema.graph.PgceLimits.default)
        val completion = Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null)
        val failure = assertFailsWith<ProtocolException> {
            GraphQueryResultMessage.new(
                Domains.portableGraphV1(),
                Roles.GRAPH_SEQUENCE_ELEMENT,
                graph,
                listOf(GraphQueryMatchMessage.SequenceElement(0uL, 1uL, 1uL)),
                completion,
                emptyList(),
            )
        }
        assertEquals(ProtocolErrorKind.INVALID_VALUE, failure.kind)
    }

    // ------------------------------------------------------------------
    // core.graph-projection-result@1 (graph_projection.rs).
    // ------------------------------------------------------------------

    @Test
    fun graphProjectionResultRoundTripsWithProvenance() {
        val builder = Builder.withLimits(GraphLimits.default)
        val key = builder.reserveNode()
        val mapping = builder.reserveNode()
        val value = builder.reserveNode()
        builder.defineScalar(key, "tag:yaml.org,2002:str", "key")
        builder.defineScalar(value, "tag:yaml.org,2002:str", "value")
        builder.defineMapping(mapping, "tag:yaml.org,2002:map", listOf(MappingEntry(key, value)))
        builder.pushRoot(mapping)
        val graph = PortableGraphMessage.fromGraph(builder.build(), consema.graph.PgceLimits.default)
        val origin = GraphSourceOriginMessage.new(
            "source:yaml",
            "yaml:node:0",
            0uL,
            3uL,
            GraphProvenanceRelationMessage.Direct,
        )
        val provenance = GraphProvenanceMapMessage.new(
            listOf(
                GraphProvenanceEntryMessage(GraphProjectedLocationMessage.Root(0uL), listOf(origin)),
                GraphProvenanceEntryMessage(GraphProjectedLocationMessage.Node(0uL), listOf(origin)),
                GraphProvenanceEntryMessage(
                    GraphProjectedLocationMessage.MappingValue(0uL, 0uL),
                    listOf(origin),
                ),
            ),
        )
        val result = GraphProjectionResultMessage.new(
            Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null),
            graph,
            provenance,
            emptyList(),
        )
        val decoded = GraphProjectionResultMessage.fromValue(result.toValue())
        assertTrue(equal(decoded.toValue(), result.toValue()), "graph projection result round trip")
    }

    @Test
    fun graphProvenanceUnsortedLocationsAreRejected() {
        val origin = GraphSourceOriginMessage.new("source:yaml", "yaml:node:0", 0uL, 3uL, GraphProvenanceRelationMessage.Direct)
        val failure = assertFailsWith<ProtocolException> {
            GraphProvenanceMapMessage.new(
                listOf(
                    GraphProvenanceEntryMessage(GraphProjectedLocationMessage.Node(0uL), listOf(origin)),
                    GraphProvenanceEntryMessage(GraphProjectedLocationMessage.Root(0uL), listOf(origin)),
                ),
            )
        }
        assertEquals(ProtocolErrorKind.INVALID_VALUE, failure.kind)
    }

    // ------------------------------------------------------------------
    // core.yaml-query-result@1 (yaml_query.rs).
    // ------------------------------------------------------------------

    @Test
    fun yamlQueryEveryRoleRoundTripsInItsExactDomain() {
        val roles = listOf(
            Roles.YAML_STREAM,
            Roles.YAML_DOCUMENT,
            Roles.YAML_NODE,
            Roles.YAML_MAPPING_ENTRY,
            Roles.YAML_SEQUENCE_ELEMENT,
            Roles.YAML_ANCHOR_DEFINITION,
            Roles.YAML_ALIAS_OCCURRENCE,
            Roles.YAML_SYNTAX_PIECE,
        )
        for (role in roles) {
            val domain = if (role == Roles.YAML_SYNTAX_PIECE) {
                Domains.yamlLosslessSyntaxV1()
            } else {
                Domains.yamlNativeV1()
            }
            val locator = YamlMatchLocator.new("source:yaml", "yaml:node:0", role, 0uL)
            val result = YamlQueryResultMessage.new(
                domain,
                role,
                listOf(locator),
                Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null),
                emptyList(),
            )
            assertTrue(
                equal(YamlQueryResultMessage.fromValue(result.toValue()).toValue(), result.toValue()),
                "role $role round trip",
            )
        }
    }

    @Test
    fun yamlQueryDomainRoleMismatchAndNonIncreasingOrdinalsFail() {
        assertFailsWith<ProtocolException> {
            YamlQueryResultMessage.new(
                Domains.yamlNativeV1(),
                Roles.YAML_SYNTAX_PIECE,
                emptyList(),
                Completion.new(CompletionStatus.SUCCESS, 0, 0, null, null),
                emptyList(),
            )
        }
        assertFailsWith<ProtocolException> {
            YamlQueryResultMessage.new(
                Domains.yamlNativeV1(),
                Roles.YAML_NODE,
                listOf(
                    YamlMatchLocator.new("source:yaml", "yaml:node:0", Roles.YAML_NODE, 0uL),
                    YamlMatchLocator.new("source:yaml", "yaml:node:1", Roles.YAML_NODE, 0uL),
                ),
                Completion.new(CompletionStatus.SUCCESS, 2, 2, null, null),
                emptyList(),
            )
        }
    }

    @Test
    fun rawYamlNodeNeverCrossesTheWire() {
        val failure = assertFailsWith<ProtocolException> { YamlMatchLocator.fromProcessLocal() }
        assertEquals(ProtocolErrorKind.PROCESS_LOCAL_HANDLE, failure.kind)
    }

    // ------------------------------------------------------------------
    // core.ini-query-result@1 / core.java-properties-query-result@1
    // (line_query.rs).
    // ------------------------------------------------------------------

    @Test
    fun iniAndPropertiesEveryRoleRoundTrips() {
        val iniRoles = listOf(
            Roles.INI_DOCUMENT,
            Roles.INI_PHYSICAL_LINE,
            Roles.INI_LOGICAL_LINE,
            Roles.INI_SECTION,
            Roles.INI_DEFAULT_SECTION,
            Roles.INI_ENTRY,
            Roles.INI_ERROR_LINE,
            Roles.INI_SYNTAX_PIECE,
        )
        for (role in iniRoles) {
            val domain = if (role == Roles.INI_SYNTAX_PIECE) {
                Domains.iniLosslessSyntaxV1()
            } else {
                Domains.iniNativeV1()
            }
            val result = IniQueryResultMessage.new(
                domain,
                role,
                listOf(IniMatchLocator.new("source:ini", "ini:node:0", role, 0uL)),
                Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null),
                emptyList(),
            )
            assertTrue(
                equal(IniQueryResultMessage.fromValue(result.toValue()).toValue(), result.toValue()),
                "INI role $role round trip",
            )
        }
        val propertiesRoles = listOf(
            Roles.PROPERTIES_DOCUMENT,
            Roles.PROPERTIES_NATURAL_LINE,
            Roles.PROPERTIES_LOGICAL_LINE,
            Roles.PROPERTIES_PROPERTY,
            Roles.PROPERTIES_COMMENT,
            Roles.PROPERTIES_ESCAPE,
            Roles.PROPERTIES_ERROR_LINE,
            Roles.PROPERTIES_SYNTAX_PIECE,
        )
        for (role in propertiesRoles) {
            val domain = if (role == Roles.PROPERTIES_SYNTAX_PIECE) {
                Domains.javaPropertiesLosslessSyntaxV1()
            } else {
                Domains.javaPropertiesNativeV1()
            }
            val result = JavaPropertiesQueryResultMessage.new(
                domain,
                role,
                listOf(JavaPropertiesMatchLocator.new("source:properties", "property:0", role, 0uL)),
                Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null),
                emptyList(),
            )
            assertTrue(
                equal(
                    JavaPropertiesQueryResultMessage.fromValue(result.toValue()).toValue(),
                    result.toValue(),
                ),
                "Properties role $role round trip",
            )
        }
    }

    @Test
    fun lineQueryDomainRoleAndOrdinalRejections() {
        val mismatch = assertFailsWith<ProtocolException> {
            IniQueryResultMessage.new(
                Domains.iniNativeV1(),
                Roles.INI_SYNTAX_PIECE,
                emptyList(),
                Completion.new(CompletionStatus.SUCCESS, 0, 0, null, null),
                emptyList(),
            )
        }
        assertEquals(ProtocolErrorKind.INVALID_VALUE, mismatch.kind)
        val duplicate = assertFailsWith<ProtocolException> {
            JavaPropertiesQueryResultMessage.new(
                Domains.javaPropertiesNativeV1(),
                Roles.PROPERTIES_PROPERTY,
                listOf(
                    JavaPropertiesMatchLocator.new("source:p", "property:0", Roles.PROPERTIES_PROPERTY, 1uL),
                    JavaPropertiesMatchLocator.new("source:p", "property:1", Roles.PROPERTIES_PROPERTY, 1uL),
                ),
                Completion.new(CompletionStatus.SUCCESS, 2, 2, null, null),
                emptyList(),
            )
        }
        assertEquals(ProtocolErrorKind.INVALID_VALUE, duplicate.kind)
    }

    // ------------------------------------------------------------------
    // core.source-snapshot@2 (source.rs) — code pages and BOM policies.
    // ------------------------------------------------------------------

    @Test
    fun sourceSnapshotV2CodePageBoundaries() {
        val snapshot = SourceSnapshotV2.fromRaw(
            byteArrayOf(0x82.toByte(), 0xa0.toByte(), 0x41),
            V2EncodingRequest.new(SourceEncoding("WindowsCodePage", 932))
                .withBomPolicy("TreatAsContent"),
            SourceLimits.default,
        )
        assertEquals("あA", snapshot.decodedText())
        val wire = SourceSnapshotMessageV2.fromSnapshot(snapshot).toValue()
        val decoded = SourceSnapshotMessageV2.fromValue(wire, SourceLimits.default)
        assertEquals("あA", decoded.snapshot().decodedText())
        // Raw byte boundaries: 0, 2 (after the two-byte scalar), 3 (end).
        assertTrue(decoded.snapshot().decodedPosition(0).rawByte == 0)
        assertTrue(decoded.snapshot().decodedPosition(2).rawByte == 2)
        assertTrue(decoded.snapshot().decodedPosition(3).rawByte == 3)
        assertFailsWith<SourceLocationException> { decoded.snapshot().decodedPosition(1) }
        assertEquals(2, decoded.snapshot().rawByteAt(DecodedOffset.UnicodeScalar(1)))
    }

    @Test
    fun sourceSnapshotV2BomPoliciesStayDistinct() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0x78)
        val detected = SourceSnapshotV2.fromRaw(
            bytes,
            V2EncodingRequest.new(SourceEncoding("Latin1", null)),
            SourceLimits.default,
        )
        val content = SourceSnapshotV2.fromRaw(
            bytes,
            V2EncodingRequest.new(SourceEncoding("Latin1", null)).withBomPolicy("TreatAsContent"),
            SourceLimits.default,
        )
        assertEquals("﻿x", detected.decodedText())
        assertEquals("ï»¿x", content.decodedText())
        assertEquals("DetectUnicode", detected.encodingFacts.bomPolicy)
        assertEquals("TreatAsContent", content.encodingFacts.bomPolicy)
    }

    @Test
    fun sourceSnapshotV2RejectsClaimedDigestMismatch() {
        val snapshot = SourceSnapshotV2.fromRaw(
            byteArrayOf(0x80.toByte(), 0x41),
            V2EncodingRequest.new(SourceEncoding("WindowsCodePage", 1252))
                .withBomPolicy("TreatAsContent"),
            SourceLimits.default,
        )
        val encoded = SourceSnapshotMessageV2.fromSnapshot(snapshot).toValue() as PvObject
        val digest = encoded.get("digest") as PvObject
        val changed = replaceField(encoded, "digest", replaceField(digest, "hex", PvString("0".repeat(64))))
        val failure = assertFailsWith<ProtocolException> {
            SourceSnapshotMessageV2.fromValue(changed, SourceLimits.default)
        }
        assertEquals(ProtocolErrorKind.INVALID_VALUE, failure.kind)
        assertEquals("$.digest", failure.path)
    }

    // ------------------------------------------------------------------
    // core.source-patch@2 (source.rs) — atomic apply.
    // ------------------------------------------------------------------

    @Test
    fun sourcePatchV2AppliesAtomically() {
        val base = SourceSnapshotV2.fromRaw(
            byteArrayOf(0x6b, 0x3d, 0x31),
            V2EncodingRequest.new(SourceEncoding("WindowsCodePage", 1252))
                .withBomPolicy("TreatAsContent"),
            SourceLimits.default,
        )
        val patch = SourcePatchV2.create(
            base,
            listOf(
                SourceReplacementV2(
                    oldStart = 2,
                    oldEnd = 3,
                    original = base.bytes().copyOfRange(2, 3),
                    replacement = byteArrayOf(0x32),
                    redactOriginal = false,
                    redactReplacement = false,
                ),
            ),
            emptyMap(),
            SourcePatchLimits.default,
        )
        val wire = SourcePatchMessageV2.fromPatch(patch).toValue()
        val decoded = SourcePatchMessageV2.fromValue(wire, SourcePatchLimits.default)
        val target = decoded.patch().apply(base, SourcePatchLimits.default)
        assertTrue(target.bytes().contentEquals(byteArrayOf(0x6b, 0x3d, 0x32)), "target bytes")
        val wrong = SourceSnapshotV2.fromRaw(
            "wrong".toByteArray(Charsets.UTF_8),
            V2EncodingRequest.new(SourceEncoding("WindowsCodePage", 1252))
                .withBomPolicy("TreatAsContent"),
            SourceLimits.default,
        )
        val failure = assertFailsWith<SourcePatchV2Exception> {
            decoded.patch().apply(wrong, SourcePatchLimits.default)
        }
        assertEquals("core.source.patch-base-mismatch@1", failure.code)
    }

    // ------------------------------------------------------------------
    // core.materialization-request@2 / core.materialization-result@2.
    // ------------------------------------------------------------------

    @Test
    fun materializationRequestV2RoundTripsCodePageEncoding() {
        val request = MaterializationRequestFacts(
            targetProfile = ProfileId("ini.windows", 1),
            style = MaterializationStyleId("ini.windows-canonical", 1),
            encoding = SourceEncoding("WindowsCodePage", 1252),
            newline = "CrLf",
            mappingPolicy = "RequireObject",
            representability = "ExactOnly",
            limits = consema.document.MaterializationLimits.default,
        )
        val payload = MaterializationRequestMessageV2.fromFacts(request).toValue()
        val decoded = MaterializationRequestMessageV2.fromValue(payload)
        assertEquals(request, decoded.request())
        val encoding = ((payload as PvObject).get("encoding") as PvObject)
        assertEquals("WindowsCodePage", (encoding.get("kind") as PvString).value)
    }

    @Test
    fun materializationResultV2MixedSnapshotVersionIsRejected() {
        val snapshot = SourceSnapshotV2.fromRaw(
            byteArrayOf(0x6b, 0x3d, 0x80.toByte()),
            V2EncodingRequest.new(SourceEncoding("WindowsCodePage", 1252))
                .withBomPolicy("TreatAsContent"),
            SourceLimits.default,
        )
        val message = MaterializationResultMessageV2.complete(
            ProfileId("ini.windows", 1),
            "target:ini",
            SourceSnapshotMessageV2.fromSnapshot(snapshot),
            "Exact",
            MaterializationReportMessage.empty(),
            MaterializationProvenanceMapMessage.empty(),
        )
        val value = message.toValue() as PvObject
        val outcome = value.get("outcome") as PvObject
        val v1Snapshot = PvObject(
            listOf(
                Entry("schema", PvString("core.source-snapshot@1")),
                Entry("raw_bytes", PvBytes.of(snapshot.bytes())),
                Entry("digest", PvNull),
                Entry("encoding", PvNull),
                Entry("decoded_status", PvString("Available")),
            ),
        )
        val mixed = replaceField(value, "outcome", replaceField(outcome, "snapshot", v1Snapshot))
        val failure = assertFailsWith<ProtocolException> {
            MaterializationResultMessageV2.fromValueWithRegistry(
                mixed,
                ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V6),
            )
        }
        assertEquals(ProtocolErrorKind.SCHEMA_MISMATCH, failure.kind)
    }

    /** Replaces one named member of an Object value, preserving the field
     * order. */
    private fun replaceField(value: PvObject, name: String, replacement: PortableValue): PvObject =
        PvObject(
            value.entries().map { entry ->
                if (entry.key == name) Entry(name, replacement) else entry
            },
        )
}
