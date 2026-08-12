// The `consema.portable-graph.conformance@1` suite runner
// (conformance/vectors/portable-graph-v1.json).
//
// Data authority: crates/consema-conformance/src/portable_graph_v1.rs (the
// per-case dispatch and every assertion are transcribed from the Rust
// handlers); the vector file itself drives every input and expectation
// (conformance/README.md rules 3-4). The graph model, strict equality,
// deterministic hashing, and the PGCE/1 codec are the Kotlin consema.graph
// package (RFC 0006; the PgceGoldenTest intent documents pin the golden
// bytes). The two portable-graph-query cases execute through the
// conformance graph-query executor (PortableGraphQuery.kt) over the same
// operator semantics the protocol validation table publishes.
// go/graph is a cross-reference only.

package consema.conformance

import consema.core.PvInteger
import consema.core.PvString
import consema.graph.Builder
import consema.graph.Graph
import consema.graph.GraphException
import consema.graph.MappingEntry
import consema.graph.NodeId
import consema.graph.PgceErrorKind
import consema.graph.PgceException
import consema.graph.PgceLimits
import consema.graph.decodePgce
import consema.graph.encodePgce
import consema.graph.encodePgceBounded
import consema.graph.equal
import consema.graph.hash
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.Domains
import consema.protocol.ExecutableQuery
import consema.protocol.QueryDefinition
import consema.protocol.QueryFailureException

/** Runs the `consema.portable-graph.conformance@1` suite. */
fun runPortableGraphV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runPortableGraphV1Case(case)
            passed.add(case.id)
        } catch (e: CaseFailureException) {
            failed.add(CaseFailure(case.id, e.message ?: "expected behavior did not match"))
        }
    }
    return SuiteReport(
        suite = data.suite,
        semanticModel = data.semanticModel,
        expectedCases = data.cases.size,
        passed = passed,
        skipped = skipped,
        failed = failed,
    )
}

private fun runPortableGraphV1Case(case: CaseData) {
    when (case.id) {
        "pgce.empty-vector", "pgce.scalar-vector" -> pgceVector(case)
        "graph.isomorphic-builder-numbering", "graph.sharing-is-not-duplication" -> graphEquality(case)
        "pgce.cycle-roundtrip" -> pgceRoundtrip(case)
        "pgce.reject-nonminimal-varint", "pgce.reject-noncanonical-node-order" -> pgceRejection(case)
        "resource.pgce-stream-limit" -> pgceLimit(case)
        "query.reachable-canonical-order", "query.distinct-shared-identity" -> graphQuery(case)
        else -> fail("runner does not recognize published case")
    }
}

/** The graph query cases (portable_graph_v1.rs:184-219): the pipeline
 * operators run over the graph and the final node IDs are compared by
 * builder-local numeric ID. */
private fun graphQuery(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val pipeline = inputSequence(case, "pipeline") ?: fail("missing input.pipeline")
    val operatorTexts = pipeline.map {
        (it as? PvString)?.value ?: fail("pipeline operator must be String")
    }
    val definition = QueryDefinition(Domains.portableGraphV1())
        .withExpression(graphQueryExpression(operatorTexts))
    val capabilities = CapabilitySet()
    capabilities.insert(CapabilityId("core.query.ordered-results", 1))
    try {
        ExecutableQuery.bind(definition.validate(), capabilities)
    } catch (e: QueryFailureException) {
        fail("query validation failed: ${e.kind.code}")
    }
    val matches = executeGraphQuery(definition, graph)
    val ids = matches.map { graphMatchNodeId(it) }
    val expected = expectedSequence(case, "builder_node_ids") ?: fail("missing expected.builder_node_ids")
    val expectedIds = expected.map {
        (it as? PvInteger)?.value?.toLong()?.toULong() ?: fail("builder_node_ids item must be an unsigned Integer")
    }
    val count = expectedLong(case, "count") ?: fail("missing expected.count")
    ensure(ids == expectedIds && ids.size.toLong() == count)
}

private fun pgceVector(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val expected = expectedString(case, "hex") ?: fail("missing expected.hex")
    ensure(toHex(encodePgce(graph)) == expected)
}

private fun graphEquality(case: CaseData) {
    val left = graphFromInput(case, "left")
    val right = graphFromInput(case, "right")
    val strictEqual = expectedBoolean(case, "strict_equal") ?: fail("missing expected.strict_equal")
    val pgceEqual = expectedBoolean(case, "pgce_equal") ?: fail("missing expected.pgce_equal")
    val hashEqual = expectedBoolean(case, "strict_hash_equal")
    ensure(
        equal(left, right) == strictEqual &&
            (toHex(encodePgce(left)) == toHex(encodePgce(right))) == pgceEqual &&
            (hashEqual == null || (hash(left) == hash(right)) == hashEqual),
    )
}

private fun pgceRoundtrip(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val bytes = encodePgce(graph)
    val decoded = decodePgce(bytes, PgceLimits.default)
    val strictEqual = expectedBoolean(case, "strict_equal") ?: fail("missing expected.strict_equal")
    val byteStable = expectedBoolean(case, "byte_stable") ?: fail("missing expected.byte_stable")
    ensure(
        equal(decoded, graph) == strictEqual &&
            (toHex(encodePgce(decoded)) == toHex(bytes)) == byteStable,
    )
}

private fun pgceRejection(case: CaseData) {
    val bytes = decodeHex(inputString(case, "hex") ?: fail("missing input.hex"))
        ?: fail("invalid hex")
    val failure = try {
        decodePgce(bytes, PgceLimits.default)
        null
    } catch (e: PgceException) {
        pgceFailureName(e.kind)
    }
    val expected = expectedString(case, "failure") ?: fail("missing expected.failure")
    val partial = expectedBoolean(case, "partial_graph") ?: fail("missing expected.partial_graph")
    ensure(failure == expected && !partial)
}

private fun pgceLimit(case: CaseData) {
    val graph = graphFromInput(case, "graph")
    val maxStreamBytes = (caseInput(case, "max_stream_bytes") as? PvInteger)
        ?.value?.toInt() ?: fail("missing input.max_stream_bytes")
    val limits = PgceLimits.default.copy(maxStreamBytes = maxStreamBytes)
    val failure = try {
        encodePgceBounded(graph, limits)
        null
    } catch (e: PgceException) {
        e
    }
    val expectedFailure = expectedString(case, "failure") ?: fail("missing expected.failure")
    val expectedLimit = expectedString(case, "limit") ?: fail("missing expected.limit")
    val partial = expectedBoolean(case, "partial_bytes") ?: fail("missing expected.partial_bytes")
    ensure(
        failure != null &&
            failure.kind == PgceErrorKind.RESOURCE_LIMIT &&
            pgceFailureName(PgceErrorKind.RESOURCE_LIMIT) == expectedFailure &&
            failure.field == expectedLimit &&
            !partial,
    )
}

/** The Rust PgceEncodeError/PgceDecodeError variant name of one codec
 * failure kind (only the kinds the published vectors assert). */
private fun pgceFailureName(kind: PgceErrorKind): String? =
    when (kind) {
        PgceErrorKind.NON_MINIMAL_VARINT -> "NonMinimalVarint"
        PgceErrorKind.NON_CANONICAL_NODE_ORDER -> "NonCanonicalNodeOrder"
        PgceErrorKind.RESOURCE_LIMIT -> "ResourceLimit"
        else -> null
    }

private fun graphFromInput(case: CaseData, name: String): Graph =
    graphFromValue(caseInput(case, name) ?: fail("missing input.$name"))

/** Builds one PortableGraph from the language-neutral vector descriptor
 * (portable_graph_v1.rs:244-315): a `{nodes, roots}` object whose node
 * records carry `kind`/`tag` and `content`, `items`, or `entries`. */
private fun graphFromValue(value: consema.core.PortableValue): Graph {
    val fields = value as? consema.core.PvObject ?: fail("graph must be Object")
    val nodeValues = (fields.get("nodes") as? consema.core.PvArray)?.items()
        ?: fail("graph.nodes must be Sequence")
    val rootValues = (fields.get("roots") as? consema.core.PvArray)?.items()
        ?: fail("graph.roots must be Sequence")
    val builder = Builder.newBuilder()
    val ids = ArrayList<NodeId>(nodeValues.size)
    for (index in nodeValues.indices) {
        ids.add(tryReserve(builder))
    }
    for ((index, nodeValue) in nodeValues.withIndex()) {
        val node = nodeValue as? consema.core.PvObject ?: fail("graph node must be Object")
        val kind = (node.get("kind") as? PvString)?.value ?: fail("graph node kind must be String")
        val tag = (node.get("tag") as? PvString)?.value ?: fail("graph node tag must be String")
        when (kind) {
            "Scalar" -> {
                val content = (node.get("content") as? PvString)?.value
                    ?: fail("scalar node content must be String")
                tryDefine { builder.defineScalar(ids[index], tag, content) }
            }
            "Sequence" -> {
                val items = (node.get("items") as? consema.core.PvArray)?.items()
                    ?: fail("sequence.items must be Sequence")
                tryDefine { builder.defineSequence(ids[index], tag, items.map { graphReference(it, ids) }) }
            }
            "Mapping" -> {
                val entries = (node.get("entries") as? consema.core.PvArray)?.items()
                    ?: fail("mapping.entries must be Sequence")
                val mappingEntries = entries.map { entry ->
                    val entryFields = entry as? consema.core.PvObject ?: fail("mapping entry must be Object")
                    MappingEntry(
                        graphReference(entryFields.get("key") ?: fail("mapping entry key missing"), ids),
                        graphReference(entryFields.get("value") ?: fail("mapping entry value missing"), ids),
                    )
                }
                tryDefine { builder.defineMapping(ids[index], tag, mappingEntries) }
            }
            else -> fail("unknown graph node kind")
        }
    }
    for (root in rootValues) {
        tryDefine { builder.pushRoot(graphReference(root, ids)) }
    }
    return try {
        builder.build()
    } catch (e: GraphException) {
        fail("graph build failed: ${e.code}")
    }
}

private fun tryReserve(builder: Builder): NodeId =
    try {
        builder.reserveNode()
    } catch (e: GraphException) {
        fail("graph node reservation failed: ${e.code}")
    }

private fun tryDefine(definition: () -> Unit) {
    try {
        definition()
    } catch (e: GraphException) {
        fail("graph definition failed: ${e.code}")
    }
}

private fun graphReference(value: consema.core.PortableValue, ids: List<NodeId>): NodeId {
    val index = (value as? PvInteger)?.value?.toInt() ?: fail("graph reference must be an Integer")
    return ids.getOrNull(index) ?: fail("graph reference out of range")
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}
