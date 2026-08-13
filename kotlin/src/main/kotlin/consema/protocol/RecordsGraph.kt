// The semantic-model v5 graph protocol records: the validated
// core.portable-graph@1 message and the graph query/projection result
// records.
//
// Data authority (language-neutral sources first):
//   - consema-rs/consema-protocol/src/portable_graph.rs (PortableGraphMessage:
//     the canonical readable layout, the strict from_value cross-validation
//     of readable graph and PGCE/1 bytes, and the limit pre-checks).
//   - consema-rs/consema-protocol/src/graph_query.rs (GraphQueryResultMessage and
//     the canonical-ID match records).
//   - consema-rs/consema-protocol/src/graph_projection.rs (GraphProjectionResult-
//     Message, GraphProvenanceMapMessage, and the projected locations).
//   - conformance/vectors/semantic-model-v5.json pins the round-trip and
//     rejection behaviors.
//
// Kotlin-idiomatic design: immutable message classes with strict
// fromValue/toValue record codecs over the graph package's immutable Graph
// model; the canonical wire layout is computed here (the graph package
// exposes no layout API to consumers).

package consema.protocol

import consema.core.PvArray
import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import consema.graph.Builder
import consema.graph.Graph
import consema.graph.GraphException
import consema.graph.MappingEntry
import consema.graph.NodeId
import consema.graph.NodeKind
import consema.graph.PgceException
import consema.graph.PgceLimits
import consema.graph.decodePgce
import consema.graph.encodePgceBounded
import java.math.BigInteger

/** The canonical `core.portable-graph@1` readable value and its exact
 * PGCE/1 bytes (portable_graph.rs:15-192). */
class PortableGraphMessage private constructor(
    private val graph: Graph,
    private val pgce: ByteArray,
) {
    companion object {
        /** Canonically encodes one complete graph under explicit PGCE limits
         * (portable_graph.rs:24-27). */
        fun fromGraph(graph: Graph, limits: PgceLimits): PortableGraphMessage {
            val pgce = try {
                encodePgceBounded(graph, limits)
            } catch (e: PgceException) {
                throw mapPgceResource(e)
            }
            return PortableGraphMessage(graph, pgce)
        }

        /** Strictly decodes and cross-validates the readable graph and
         * PGCE/1 forms (portable_graph.rs:106-173). */
        fun fromValue(value: PortableValue, limits: PgceLimits): PortableGraphMessage {
            val fields = schemaFields(
                value,
                "core.portable-graph@1",
                listOf("schema", "encoding", "roots", "nodes", "pgce"),
                "$",
            )
            if (stringOf(fields[1], "$.encoding") != "PGCE/1") {
                throw invalid("$.encoding", "expected PGCE/1")
            }
            val rootValues = sequenceOf(fields[2], "$.roots")
            val nodeValues = sequenceOf(fields[3], "$.nodes")
            checkCount("$.roots", rootValues.size, limits.maxRoots)
            checkCount("$.nodes", nodeValues.size, limits.maxNodes)
            val pgceValue = fields[4] as? PvBytes
                ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$.pgce", "expected Bytes")
            val pgce = pgceValue.content()
            checkCount("$.pgce", pgce.size, limits.maxStreamBytes)

            val builder = Builder.withLimits(limits.graphLimits())
            val ids = ArrayList<NodeId>(nodeValues.size)
            for (i in nodeValues.indices) {
                ids.add(builder.reserveNode())
            }
            for ((index, record) in nodeValues.withIndex()) {
                defineRecord(builder, ids, index, record, limits)
            }
            for ((index, root) in rootValues.withIndex()) {
                val canonical = unsigned64(root, "$.roots[$index]")
                val rootId = resolveId(ids, canonical, "$.roots[$index]")
                builder.pushRoot(rootId)
            }
            val decodedGraph = try {
                builder.build()
            } catch (e: GraphException) {
                throw mapBuildError(e)
            }
            if (canonicalLayout(decodedGraph).order != ids) {
                throw invalid("$.nodes", "node records are not in canonical first-discovery order")
            }
            val decoded = try {
                decodePgce(pgce, limits)
            } catch (e: PgceException) {
                throw mapPgceDecode(e)
            }
            if (!consema.graph.equal(decodedGraph, decoded)) {
                throw invalid("$", "readable graph and PGCE graph are not strictly equal")
            }
            val canonical = try {
                encodePgceBounded(decodedGraph, limits)
            } catch (e: PgceException) {
                throw mapPgceResource(e)
            }
            if (!canonical.contentEquals(pgce)) {
                throw invalid("$.pgce", "PGCE bytes disagree with readable graph")
            }
            return PortableGraphMessage(decodedGraph, canonical)
        }
    }

    /** Complete immutable graph. */
    fun graph(): Graph = graph

    /** Exact canonical PGCE/1 bytes. */
    fun pgce(): ByteArray = pgce.copyOf()

    /** Encodes the fixed readable graph plus PGCE schema
     * (portable_graph.rs:41-104). */
    fun toValue(): PortableValue {
        val layout = canonicalLayout(graph)
        val roots = PvArray(graph.roots().map { integerValue((layout.ids[it] ?: error("root has no canonical ID")).toULong()) })
        val nodes = PvArray(layout.order.mapIndexed { index, id ->
            val node = graph.node(id) ?: error("completed graph node resolves as null")
            when (node.kind) {
                NodeKind.Scalar -> PvObject(
                    listOf(
                        consema.core.Entry("id", integerValue(index.toULong())),
                        consema.core.Entry("kind", PvString("Scalar")),
                        consema.core.Entry("tag", PvString(node.tag)),
                        consema.core.Entry(
                            "canonical_content",
                            PvString(node.scalarContent() ?: error("scalar kind has content")),
                        ),
                    ),
                )
                NodeKind.Sequence -> PvObject(
                    listOf(
                        consema.core.Entry("id", integerValue(index.toULong())),
                        consema.core.Entry("kind", PvString("Sequence")),
                        consema.core.Entry("tag", PvString(node.tag)),
                        consema.core.Entry(
                            "items",
                            PvArray(
                                (node.sequenceItems() ?: error("sequence kind has items")).map {
                                    integerValue((layout.ids[it] ?: error("item has no canonical ID")).toULong())
                                },
                            ),
                        ),
                    ),
                )
                NodeKind.Mapping -> PvObject(
                    listOf(
                        consema.core.Entry("id", integerValue(index.toULong())),
                        consema.core.Entry("kind", PvString("Mapping")),
                        consema.core.Entry("tag", PvString(node.tag)),
                        consema.core.Entry(
                            "entries",
                            PvArray(
                                (node.mappingEntries() ?: error("mapping kind has entries")).map { entry ->
                                    PvObject(
                                        listOf(
                                            consema.core.Entry(
                                                "key",
                                                integerValue((layout.ids[entry.key] ?: error("key has no canonical ID")).toULong()),
                                            ),
                                            consema.core.Entry(
                                                "value",
                                                integerValue((layout.ids[entry.value] ?: error("value has no canonical ID")).toULong()),
                                            ),
                                        ),
                                    )
                                },
                            ),
                        ),
                    ),
                )
            }
        })
        return PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.portable-graph@1")),
                consema.core.Entry("encoding", PvString("PGCE/1")),
                consema.core.Entry("roots", roots),
                consema.core.Entry("nodes", nodes),
                consema.core.Entry("pgce", PvBytes.of(pgce)),
            ),
        )
    }

    /** The canonical first-discovery order and wire IDs of the graph
     * (portable_graph.rs:200-238). */
    internal fun wireLayout(): CanonicalLayout = canonicalLayout(graph)
}

/** The canonical wire layout of one graph (portable_graph.rs:194-238). */
internal class CanonicalLayout(
    /** Graph-local node IDs in canonical first-discovery order. */
    val order: List<NodeId>,
    /** Graph-local node ID to canonical wire ID. */
    val ids: Map<NodeId, Int>,
)

/** Computes the canonical first-discovery layout (portable_graph.rs:200-
 * 238). */
internal fun canonicalLayout(graph: Graph): CanonicalLayout {
    val order = ArrayList<NodeId>(graph.nodeCount())
    val ids = HashMap<NodeId, Int>()
    val stack = ArrayDeque<NodeId>()
    for (index in graph.roots().indices.reversed()) {
        stack.addLast(graph.roots()[index])
    }
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (ids.containsKey(id)) {
            continue
        }
        ids[id] = order.size
        order.add(id)
        val node = graph.node(id) ?: error("completed graph node resolves as null")
        when (node.kind) {
            NodeKind.Scalar -> {}
            NodeKind.Sequence -> {
                val items = node.sequenceItems() ?: error("sequence kind has items")
                for (index in items.indices.reversed()) {
                    stack.addLast(items[index])
                }
            }
            NodeKind.Mapping -> {
                val entries = node.mappingEntries() ?: error("mapping kind has entries")
                for (index in entries.indices.reversed()) {
                    stack.addLast(entries[index].value)
                    stack.addLast(entries[index].key)
                }
            }
        }
    }
    return CanonicalLayout(order, ids)
}

/** Defines one readable graph node record (portable_graph.rs:240-331). */
private fun defineRecord(
    builder: Builder,
    ids: List<NodeId>,
    index: Int,
    value: PortableValue,
    limits: PgceLimits,
) {
    val path = "$.nodes[$index]"
    val entries = (value as? PvObject)?.entries()
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected graph node Object")
    val kind = entries.getOrNull(1)
        ?.takeIf { it.key == "kind" }
        ?.value as? PvString
        ?: throw invalid(path, "kind must be the second String field")
    when (kind.value) {
        "Scalar" -> {
            val fields = exactFields(value, listOf("id", "kind", "tag", "canonical_content"), path)
            validateRecordId(fields[0], index, path)
            builder.defineScalar(
                ids[index],
                stringOf(fields[2], "$path.tag"),
                stringOf(fields[3], "$path.canonical_content"),
            )
        }
        "Sequence" -> {
            val fields = exactFields(value, listOf("id", "kind", "tag", "items"), path)
            validateRecordId(fields[0], index, path)
            val values = sequenceOf(fields[3], "$path.items")
            checkCount("$path.items", values.size, limits.maxContainerEntries)
            val items = values.mapIndexed { ordinal, item ->
                val itemPath = "$path.items[$ordinal]"
                resolveId(ids, unsigned64(item, itemPath), itemPath)
            }
            builder.defineSequence(
                ids[index],
                stringOf(fields[2], "$path.tag"),
                items,
            )
        }
        "Mapping" -> {
            val fields = exactFields(value, listOf("id", "kind", "tag", "entries"), path)
            validateRecordId(fields[0], index, path)
            val values = sequenceOf(fields[3], "$path.entries")
            checkCount("$path.entries", values.size, limits.maxContainerEntries)
            val associations = values.mapIndexed { ordinal, entry ->
                val entryPath = "$path.entries[$ordinal]"
                val entryFields = exactFields(entry, listOf("key", "value"), entryPath)
                val keyPath = "$entryPath.key"
                val valuePath = "$entryPath.value"
                MappingEntry(
                    resolveId(ids, unsigned64(entryFields[0], keyPath), keyPath),
                    resolveId(ids, unsigned64(entryFields[1], valuePath), valuePath),
                )
            }
            builder.defineMapping(
                ids[index],
                stringOf(fields[2], "$path.tag"),
                associations,
            )
        }
        else -> throw invalid("$path.kind", "unknown graph node kind")
    }
}

/** Requires the record id to equal its canonical array index
 * (portable_graph.rs:333-348). */
private fun validateRecordId(value: PortableValue, index: Int, path: String) {
    val observed = unsigned64(value, "$path.id")
    if (observed != index.toULong()) {
        throw invalid("$path.id", "node ID must equal its canonical array index")
    }
}

/** Resolves a canonical wire ID to its graph-local node ID
 * (portable_graph.rs:350-355). */
internal fun resolveId(ids: List<NodeId>, value: ULong, path: String): NodeId {
    val index = value.toLong().toInt()
    return if (value <= Int.MAX_VALUE.toULong() && index in ids.indices) {
        ids[index]
    } else {
        throw invalid(path, "canonical node ID is out of range")
    }
}

private fun checkCount(path: String, observed: Int, limit: Int) {
    if (observed > limit) {
        throw resource(path, "count $observed exceeds $limit")
    }
}

/** Maps a graph construction failure to the protocol failure vocabulary
 * (portable_graph.rs:365-372). */
internal fun mapBuildError(error: GraphException): ProtocolException =
    if (error.kind == consema.graph.GraphErrorKind.RESOURCE_LIMIT ||
        error.kind == consema.graph.GraphErrorKind.SIZE_OVERFLOW
    ) {
        resource("$", "graph construction: ${error.message}")
    } else {
        invalid("$", "invalid graph: ${error.message}")
    }

/** Maps a PGCE decode failure (portable_graph.rs:378-385). */
internal fun mapPgceDecode(error: PgceException): ProtocolException =
    if (error.kind == consema.graph.PgceErrorKind.RESOURCE_LIMIT ||
        error.kind == consema.graph.PgceErrorKind.VARINT_OVERFLOW
    ) {
        resource("$.pgce", "PGCE decoding failed: ${error.message}")
    } else {
        invalid("$.pgce", "invalid PGCE: ${error.message}")
    }

/** Maps a PGCE encode failure (portable_graph.rs:374-376). */
internal fun mapPgceResource(error: PgceException): ProtocolException =
    resource("$.pgce", "PGCE encoding failed: ${error.message}")

// ---------------------------------------------------------------------------
// core.graph-query-result@1 (graph_query.rs).
// ---------------------------------------------------------------------------

/** One graph match expressed only with canonical wire node IDs
 * (graph_query.rs:16-53). */
sealed class GraphQueryMatchMessage {
    /** One graph node. */
    data class Node(val node: ULong) : GraphQueryMatchMessage()

    /** One direct sequence association. */
    data class SequenceElement(
        val parent: ULong,
        val ordinal: ULong,
        val node: ULong,
    ) : GraphQueryMatchMessage()

    /** One direct mapping association. */
    data class MappingEntry(
        val parent: ULong,
        val ordinal: ULong,
        val key: ULong,
        val value: ULong,
    ) : GraphQueryMatchMessage()

    /** The uniform match role of this match (graph_query.rs:45-53). */
    fun role(): String = when (this) {
        is Node -> Roles.GRAPH_NODE
        is SequenceElement -> Roles.GRAPH_SEQUENCE_ELEMENT
        is MappingEntry -> Roles.GRAPH_MAPPING_ENTRY
    }

    /** Encodes one match record (graph_query.rs:342-371). */
    fun toValue(): PortableValue = when (this) {
        is Node -> PvObject(
            listOf(
                consema.core.Entry("kind", PvString("Node")),
                consema.core.Entry("node", integerValue(node)),
            ),
        )
        is SequenceElement -> PvObject(
            listOf(
                consema.core.Entry("kind", PvString("SequenceElement")),
                consema.core.Entry("parent", integerValue(parent)),
                consema.core.Entry("ordinal", integerValue(ordinal)),
                consema.core.Entry("node", integerValue(node)),
            ),
        )
        is MappingEntry -> PvObject(
            listOf(
                consema.core.Entry("kind", PvString("MappingEntry")),
                consema.core.Entry("parent", integerValue(parent)),
                consema.core.Entry("ordinal", integerValue(ordinal)),
                consema.core.Entry("key", integerValue(key)),
                consema.core.Entry("value", integerValue(value)),
            ),
        )
    }

    companion object {
        /** Strictly decodes one match record (graph_query.rs:373-408). */
        fun fromValue(value: PortableValue, path: String): GraphQueryMatchMessage {
            val entries = (value as? PvObject)?.entries()
                ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected graph match Object")
            val kind = entries.firstOrNull()
                ?.takeIf { it.key == "kind" }
                ?.value as? PvString
                ?: throw invalid(path, "kind must be the first String field")
            return when (kind.value) {
                "Node" -> {
                    val fields = exactFields(value, listOf("kind", "node"), path)
                    Node(unsigned64(fields[1], "$path.node"))
                }
                "SequenceElement" -> {
                    val fields = exactFields(value, listOf("kind", "parent", "ordinal", "node"), path)
                    SequenceElement(
                        unsigned64(fields[1], "$path.parent"),
                        unsigned64(fields[2], "$path.ordinal"),
                        unsigned64(fields[3], "$path.node"),
                    )
                }
                "MappingEntry" -> {
                    val fields = exactFields(value, listOf("kind", "parent", "ordinal", "key", "value"), path)
                    MappingEntry(
                        unsigned64(fields[1], "$path.parent"),
                        unsigned64(fields[2], "$path.ordinal"),
                        unsigned64(fields[3], "$path.key"),
                        unsigned64(fields[4], "$path.value"),
                    )
                }
                else -> throw invalid(path, "unknown graph query match kind")
            }
        }
    }
}

/** The graph roles of the graph-query result records (graph_query.rs:410-
 * 415). */
internal fun isGraphRole(role: String): Boolean =
    role == Roles.GRAPH_NODE ||
        role == Roles.GRAPH_SEQUENCE_ELEMENT ||
        role == Roles.GRAPH_MAPPING_ENTRY

/** Complete or explicitly non-complete `core.graph-query-result@1`
 * (graph_query.rs:56-270). */
class GraphQueryResultMessage private constructor(
    /** Exact query domain. */
    val domain: QueryDomain,
    /** Uniform result role. */
    val role: String,
    /** Complete graph that gives every canonical ID meaning. */
    private val graph: PortableGraphMessage,
    /** Ordered graph matches. */
    val matches: List<GraphQueryMatchMessage>,
    /** Explicit terminal state. */
    val completion: Completion,
    /** Ordered diagnostics. */
    val diagnostics: List<Diagnostic>,
) {
    companion object {
        /** Validates graph binding, uniform match roles, associations, and
         * counts (graph_query.rs:67-99). */
        fun new(
            domain: QueryDomain,
            role: String,
            graph: PortableGraphMessage,
            matches: List<GraphQueryMatchMessage>,
            completion: Completion,
            diagnostics: List<Diagnostic>,
        ): GraphQueryResultMessage {
            if (domain != Domains.portableGraphV1() || !isGraphRole(role)) {
                throw invalid("$", "graph result requires core.portable-graph-query@1 and a graph role")
            }
            if (completion.produced != matches.size.toLong() ||
                matches.any { it.role() != role }
            ) {
                throw invalid("$", "completion count or graph match role is inconsistent")
            }
            validateMatches(graph, matches)
            return GraphQueryResultMessage(domain, role, graph, matches, completion, diagnostics)
        }

        /** Strictly decodes with explicit graph limits and semantic-model
         * registry (graph_query.rs:229-269). */
        fun fromValueWithRegistry(
            value: PortableValue,
            limits: PgceLimits,
            registry: ErrorCodeRegistry,
        ): GraphQueryResultMessage {
            val fields = schemaFields(
                value,
                "core.graph-query-result@1",
                listOf(
                    "schema", "domain_id", "domain_version", "role", "graph",
                    "matches", "completion", "diagnostics",
                ),
                "$",
            )
            val matches = sequenceOf(fields[5], "$.matches")
                .mapIndexed { index, item -> GraphQueryMatchMessage.fromValue(item, "$.matches[$index]") }
            val diagnostics = sequenceOf(fields[7], "$.diagnostics")
                .map { Diagnostic.fromValue(it, registry) }
            return new(
                QueryDomain(stringOf(fields[1], "$.domain_id"), unsigned32(fields[2], "$.domain_version")),
                parseGraphRole(stringOf(fields[3], "$.role")),
                PortableGraphMessage.fromValue(fields[4], limits),
                matches,
                Completion.fromValueWithRegistry(fields[6], registry),
                diagnostics,
            )
        }

        /** Strictly decodes under the v1 registry and default limits. */
        fun fromValue(value: PortableValue): GraphQueryResultMessage =
            fromValueWithRegistry(
                value,
                PgceLimits.default,
                ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V1),
            )
    }

    /** Complete graph that gives every canonical ID meaning. */
    fun graph(): PortableGraphMessage = graph

    /** Encodes `core.graph-query-result@1` (graph_query.rs:189-213). */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.graph-query-result@1")),
                consema.core.Entry("domain_id", PvString(domain.id)),
                consema.core.Entry(
                    "domain_version",
                    PvInteger(BigInteger.valueOf(domain.version.toLong())),
                ),
                consema.core.Entry("role", PvString(role)),
                consema.core.Entry("graph", graph.toValue()),
                consema.core.Entry("matches", PvArray(matches.map { it.toValue() })),
                consema.core.Entry("completion", completion.toValue()),
                consema.core.Entry("diagnostics", PvArray(diagnostics.map { it.toValue() })),
            ),
        )
}

/** Validates every match against the graph structure
 * (graph_query.rs:272-329). */
private fun validateMatches(
    message: PortableGraphMessage,
    matches: List<GraphQueryMatchMessage>,
) {
    val layout = message.wireLayout()
    val graph = message.graph()
    for ((index, item) in matches.withIndex()) {
        val path = "$.matches[$index]"
        when (item) {
            is GraphQueryMatchMessage.Node -> {
                resolveId(layout.order, item.node, "$path.node")
            }
            is GraphQueryMatchMessage.SequenceElement -> {
                val parentId = resolveId(layout.order, item.parent, "$path.parent")
                val nodeId = resolveId(layout.order, item.node, "$path.node")
                val parent = graph.node(parentId) ?: error("canonical graph ID resolves")
                val expected = item.ordinal.toLong().toInt().let { ordinal ->
                    if (item.ordinal <= Int.MAX_VALUE.toULong()) {
                        parent.sequenceItems()?.getOrNull(ordinal)
                    } else {
                        null
                    }
                }
                if (expected != nodeId) {
                    throw invalid(path, "sequence association does not match graph")
                }
            }
            is GraphQueryMatchMessage.MappingEntry -> {
                val parentId = resolveId(layout.order, item.parent, "$path.parent")
                val keyId = resolveId(layout.order, item.key, "$path.key")
                val valueId = resolveId(layout.order, item.value, "$path.value")
                val parent = graph.node(parentId) ?: error("canonical graph ID resolves")
                val entry = if (item.ordinal <= Int.MAX_VALUE.toULong()) {
                    parent.mappingEntries()?.getOrNull(item.ordinal.toLong().toInt())
                } else {
                    null
                }
                if (entry == null || entry.key != keyId || entry.value != valueId) {
                    throw invalid(path, "mapping association does not match graph")
                }
            }
        }
    }
}

/** Parses one graph query role spelling (graph_query.rs:426-433). */
internal fun parseGraphRole(value: String): String =
    when (value) {
        Roles.GRAPH_NODE, Roles.GRAPH_SEQUENCE_ELEMENT, Roles.GRAPH_MAPPING_ENTRY -> value
        else -> throw invalid("$.role", "unknown graph query match role")
    }

// ---------------------------------------------------------------------------
// core.graph-provenance-map@1 (graph_projection.rs:119-212).
// ---------------------------------------------------------------------------

/** One projected PortableGraph location expressed with canonical node IDs
 * (graph_projection.rs:16-43). */
sealed class GraphProjectedLocationMessage {
    /** Ordered root occurrence. */
    data class Root(val ordinal: ULong) : GraphProjectedLocationMessage()

    /** One graph node. */
    data class Node(val node: ULong) : GraphProjectedLocationMessage()

    /** One ordered sequence edge. */
    data class SequenceElement(val parent: ULong, val ordinal: ULong) :
        GraphProjectedLocationMessage()

    /** One ordered mapping key edge. */
    data class MappingKey(val parent: ULong, val ordinal: ULong) :
        GraphProjectedLocationMessage()

    /** One ordered mapping value edge. */
    data class MappingValue(val parent: ULong, val ordinal: ULong) :
        GraphProjectedLocationMessage()

    /** Encodes one location record (graph_projection.rs:411-437). */
    fun toValue(): PortableValue = when (this) {
        is Root -> PvObject(
            listOf(
                consema.core.Entry("kind", PvString("Root")),
                consema.core.Entry("ordinal", integerValue(ordinal)),
            ),
        )
        is Node -> PvObject(
            listOf(
                consema.core.Entry("kind", PvString("Node")),
                consema.core.Entry("node", integerValue(node)),
            ),
        )
        is SequenceElement -> PvObject(
            listOf(
                consema.core.Entry("kind", PvString("SequenceElement")),
                consema.core.Entry("parent", integerValue(parent)),
                consema.core.Entry("ordinal", integerValue(ordinal)),
            ),
        )
        is MappingKey -> PvObject(
            listOf(
                consema.core.Entry("kind", PvString("MappingKey")),
                consema.core.Entry("parent", integerValue(parent)),
                consema.core.Entry("ordinal", integerValue(ordinal)),
            ),
        )
        is MappingValue -> PvObject(
            listOf(
                consema.core.Entry("kind", PvString("MappingValue")),
                consema.core.Entry("parent", integerValue(parent)),
                consema.core.Entry("ordinal", integerValue(ordinal)),
            ),
        )
    }

    companion object {
        /** Strictly decodes one location record (graph_projection.rs:439-
         * 480). */
        fun fromValue(value: PortableValue, path: String): GraphProjectedLocationMessage {
            val entries = (value as? PvObject)?.entries()
                ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected graph location Object")
            val kind = entries.firstOrNull()
                ?.takeIf { it.key == "kind" }
                ?.value as? PvString
                ?: throw invalid(path, "kind must be the first String field")
            return when (kind.value) {
                "Root" -> {
                    val fields = exactFields(value, listOf("kind", "ordinal"), path)
                    Root(unsigned64(fields[1], "$path.ordinal"))
                }
                "Node" -> {
                    val fields = exactFields(value, listOf("kind", "node"), path)
                    Node(unsigned64(fields[1], "$path.node"))
                }
                "SequenceElement", "MappingKey", "MappingValue" -> {
                    val fields = exactFields(value, listOf("kind", "parent", "ordinal"), path)
                    val parent = unsigned64(fields[1], "$path.parent")
                    val ordinal = unsigned64(fields[2], "$path.ordinal")
                    when (kind.value) {
                        "SequenceElement" -> SequenceElement(parent, ordinal)
                        "MappingKey" -> MappingKey(parent, ordinal)
                        else -> MappingValue(parent, ordinal)
                    }
                }
                else -> throw invalid(path, "unknown graph projected location")
            }
        }
    }
}

/** Exact YAML-source relation to a projected graph fact
 * (graph_projection.rs:46-52). */
enum class GraphProvenanceRelationMessage {
    /** Direct native representation origin. */
    Direct,

    /** Alias occurrence referring to a shared graph node. */
    Reference;

    /** The wire spelling. */
    fun wireName(): String = when (this) {
        Direct -> "Direct"
        Reference -> "Reference"
    }

    companion object {
        /** Parses the wire spelling. */
        fun fromName(name: String, path: String): GraphProvenanceRelationMessage =
            when (name) {
                "Direct" -> Direct
                "Reference" -> Reference
                else -> throw invalid(path, "unknown graph provenance relation")
            }
    }
}

/** Transferable graph origin with caller-assigned identities
 * (graph_projection.rs:54-108). */
class GraphSourceOriginMessage private constructor(
    /** Stable source identity. */
    val sourceId: String,
    /** Optional stable caller node locator. */
    val nodeLocator: String?,
    /** Inclusive source byte start. */
    val startByte: ULong,
    /** Exclusive source byte end. */
    val endByte: ULong,
    /** Exact graph provenance relation. */
    val relation: GraphProvenanceRelationMessage,
) {
    companion object {
        /** Validates one externalized graph origin (graph_projection.rs:70-
         * 98). */
        fun new(
            sourceId: String,
            nodeLocator: String?,
            startByte: ULong,
            endByte: ULong,
            relation: GraphProvenanceRelationMessage,
        ): GraphSourceOriginMessage {
            if (sourceId.isEmpty() || sourceId.length > 1024 || startByte > endByte ||
                (nodeLocator != null && (nodeLocator.isEmpty() || nodeLocator.length > 4096))
            ) {
                throw invalid("$.origin", "invalid source identity, locator, or half-open range")
            }
            return GraphSourceOriginMessage(sourceId, nodeLocator, startByte, endByte, relation)
        }

        /** Explicitly refuses an unbound process-local node handle
         * (graph_projection.rs:100-108). */
        fun fromProcessLocal(): GraphSourceOriginMessage =
            throw protocolError(
                ProtocolErrorKind.PROCESS_LOCAL_HANDLE,
                "$.origin.node",
                "NodeRef requires a stable caller locator",
            )

        /** Strictly decodes one origin record (graph_projection.rs:504-
         * 530). */
        fun fromValue(value: PortableValue, path: String): GraphSourceOriginMessage {
            val fields = exactFields(
                value,
                listOf("source_id", "node_locator", "start_byte", "end_byte", "relation"),
                path,
            )
            return new(
                stringOf(fields[0], "$path.source_id"),
                optionalString(fields[1], "$path.node_locator"),
                unsigned64(fields[2], "$path.start_byte"),
                unsigned64(fields[3], "$path.end_byte"),
                GraphProvenanceRelationMessage.fromName(
                    stringOf(fields[4], "$path.relation"),
                    path,
                ),
            )
        }
    }

    /** Encodes one origin record (graph_projection.rs:482-502). */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("source_id", PvString(sourceId)),
                consema.core.Entry("node_locator", nullableString(nodeLocator)),
                consema.core.Entry("start_byte", integerValue(startByte)),
                consema.core.Entry("end_byte", integerValue(endByte)),
                consema.core.Entry("relation", PvString(relation.wireName())),
            ),
        )
}

/** One graph location and all ordered source origins
 * (graph_projection.rs:110-117). */
data class GraphProvenanceEntryMessage(
    /** Projected graph location. */
    val projected: GraphProjectedLocationMessage,
    /** One or more source origins. */
    val origins: List<GraphSourceOriginMessage>,
)

/** Sorted unique `core.graph-provenance-map@1` (graph_projection.rs:119-
 * 212). */
class GraphProvenanceMapMessage private constructor(
    private val entries: List<GraphProvenanceEntryMessage>,
) {
    companion object {
        /** Validates canonical location order, uniqueness, and non-empty
         * origins (graph_projection.rs:126-139). */
        fun new(entries: List<GraphProvenanceEntryMessage>): GraphProvenanceMapMessage {
            if (entries.any { it.origins.isEmpty() } ||
                entries.zipWithNext().any { (left, right) ->
                    compareLocations(locationOrdinal(left.projected), locationOrdinal(right.projected)) >= 0
                }
            ) {
                throw invalid(
                    "$.entries",
                    "graph provenance locations must be sorted, unique, and have origins",
                )
            }
            return GraphProvenanceMapMessage(entries)
        }

        /** Strictly decodes one graph provenance map (graph_projection.rs:
         * 184-211). */
        fun fromValue(value: PortableValue): GraphProvenanceMapMessage {
            val fields = schemaFields(value, "core.graph-provenance-map@1", listOf("schema", "entries"), "$")
            val entries = sequenceOf(fields[1], "$.entries").mapIndexed { index, entry ->
                val path = "$.entries[$index]"
                val entryFields = exactFields(entry, listOf("projected", "origins"), path)
                GraphProvenanceEntryMessage(
                    projected = GraphProjectedLocationMessage.fromValue(entryFields[0], "$path.projected"),
                    origins = sequenceOf(entryFields[1], "$path.origins").mapIndexed { originIndex, origin ->
                        GraphSourceOriginMessage.fromValue(origin, "$path.origins[$originIndex]")
                    },
                )
            }
            return new(entries)
        }
    }

    /** Sorted provenance entries. */
    fun entries(): List<GraphProvenanceEntryMessage> = entries

    /** Validates every projected location against one exact graph message
     * (graph_projection.rs:147-159). */
    fun validateAgainst(graph: PortableGraphMessage) {
        val layout = graph.wireLayout()
        for ((index, entry) in entries.withIndex()) {
            validateLocation(graph, layout.order, entry.projected, "$.entries[$index].projected")
        }
    }

    /** Encodes `core.graph-provenance-map@1` (graph_projection.rs:161-182). */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.graph-provenance-map@1")),
                consema.core.Entry(
                    "entries",
                    PvArray(
                        entries.map { entry ->
                            PvObject(
                                listOf(
                                    consema.core.Entry("projected", entry.projected.toValue()),
                                    consema.core.Entry(
                                        "origins",
                                        PvArray(entry.origins.map { it.toValue() }),
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            ),
        )
}

/** Deterministic total order key of one projected location: variant rank
 * first, then the payload fields in declared order (the Rust derived Ord
 * orders the variants first, graph_projection.rs:15-43). */
private fun locationOrdinal(location: GraphProjectedLocationMessage): List<ULong> =
    when (location) {
        is GraphProjectedLocationMessage.Root -> listOf(0uL, location.ordinal)
        is GraphProjectedLocationMessage.Node -> listOf(1uL, location.node)
        is GraphProjectedLocationMessage.SequenceElement ->
            listOf(2uL, location.parent, location.ordinal)
        is GraphProjectedLocationMessage.MappingKey ->
            listOf(3uL, location.parent, location.ordinal)
        is GraphProjectedLocationMessage.MappingValue ->
            listOf(4uL, location.parent, location.ordinal)
    }

/** Lexicographic comparison of two location order keys. */
private fun compareLocations(left: List<ULong>, right: List<ULong>): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        if (left[index] != right[index]) {
            return if (left[index] < right[index]) -1 else 1
        }
    }
    return left.size.compareTo(right.size)
}

/** Validates one projected location against the graph structure
 * (graph_projection.rs:351-398). */
private fun validateLocation(
    graph: PortableGraphMessage,
    canonical: List<NodeId>,
    location: GraphProjectedLocationMessage,
    path: String,
) {
    when (location) {
        is GraphProjectedLocationMessage.Root -> {
            if (location.ordinal >= graph.graph().roots().size.toULong()) {
                throw invalid(path, "root ordinal is out of range")
            }
        }
        is GraphProjectedLocationMessage.Node -> {
            resolveId(canonical, location.node, path)
        }
        is GraphProjectedLocationMessage.SequenceElement -> {
            val parent = resolveId(canonical, location.parent, path)
            val valid = location.ordinal <= Int.MAX_VALUE.toULong() &&
                graph.graph().node(parent)?.sequenceItems()?.size?.let {
                    location.ordinal < it.toULong()
                } == true
            if (!valid) {
                throw invalid(path, "sequence location does not exist")
            }
        }
        is GraphProjectedLocationMessage.MappingKey -> {
            validateMappingLocation(graph, canonical, location.parent, location.ordinal, path)
        }
        is GraphProjectedLocationMessage.MappingValue -> {
            validateMappingLocation(graph, canonical, location.parent, location.ordinal, path)
        }
    }
}

/** Validates one mapping edge location (graph_projection.rs:382-394). */
private fun validateMappingLocation(
    graph: PortableGraphMessage,
    canonical: List<NodeId>,
    parent: ULong,
    ordinal: ULong,
    path: String,
) {
    val parentId = resolveId(canonical, parent, path)
    val valid = ordinal <= Int.MAX_VALUE.toULong() &&
        graph.graph().node(parentId)?.mappingEntries()?.size?.let {
            ordinal < it.toULong()
        } == true
    if (!valid) {
        throw invalid(path, "mapping location does not exist")
    }
}

// ---------------------------------------------------------------------------
// core.graph-projection-result@1 (graph_projection.rs:214-349).
// ---------------------------------------------------------------------------

/** Atomic exact `core.graph-projection-result@1` (graph_projection.rs:214-
 * 349). */
class GraphProjectionResultMessage private constructor(
    /** Explicit terminal state. */
    val completion: Completion,
    private val graph: PortableGraphMessage?,
    /** Complete provenance only on success. */
    val provenance: GraphProvenanceMapMessage,
    /** Ordered diagnostics. */
    val diagnostics: List<Diagnostic>,
) {
    companion object {
        /** Validates atomic success, produced count, and complete graph
         * provenance (graph_projection.rs:224-255). */
        fun new(
            completion: Completion,
            graph: PortableGraphMessage?,
            provenance: GraphProvenanceMapMessage,
            diagnostics: List<Diagnostic>,
        ): GraphProjectionResultMessage {
            val success = completion.status == CompletionStatus.SUCCESS
            if (success != (graph != null) ||
                (success && completion.produced != 1L) ||
                (!success && completion.produced != 0L)
            ) {
                throw invalid("$", "only successful single-result projection may carry a graph")
            }
            if (graph != null) {
                provenance.validateAgainst(graph)
            } else if (provenance.entries().isNotEmpty()) {
                throw invalid("$.provenance", "failed projection cannot claim completed provenance")
            }
            return GraphProjectionResultMessage(completion, graph, provenance, diagnostics)
        }

        /** Strictly decodes with explicit graph limits and semantic-model
         * registry (graph_projection.rs:320-348). */
        fun fromValueWithRegistry(
            value: PortableValue,
            limits: PgceLimits,
            registry: ErrorCodeRegistry,
        ): GraphProjectionResultMessage {
            val fields = schemaFields(
                value,
                "core.graph-projection-result@1",
                listOf("schema", "completion", "graph", "provenance", "diagnostics"),
                "$",
            )
            val graph = if (fields[2] is PvNull) {
                null
            } else {
                val graphValue = exactFields(fields[2], listOf("portable_graph"), "$.graph")[0]
                PortableGraphMessage.fromValue(graphValue, limits)
            }
            val diagnostics = sequenceOf(fields[4], "$.diagnostics")
                .map { Diagnostic.fromValue(it, registry) }
            return new(
                Completion.fromValueWithRegistry(fields[1], registry),
                graph,
                GraphProvenanceMapMessage.fromValue(fields[3]),
                diagnostics,
            )
        }

        /** Strictly decodes under the v1 registry and default limits. */
        fun fromValue(value: PortableValue): GraphProjectionResultMessage =
            fromValueWithRegistry(
                value,
                PgceLimits.default,
                ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V1),
            )
    }

    /** Complete graph only on success. */
    fun graph(): PortableGraphMessage? = graph

    /** Encodes `core.graph-projection-result@1` (graph_projection.rs:281-
     * 305). */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.graph-projection-result@1")),
                consema.core.Entry("completion", completion.toValue()),
                consema.core.Entry(
                    "graph",
                    if (graph == null) {
                        PvNull
                    } else {
                        PvObject(
                            listOf(consema.core.Entry("portable_graph", graph.toValue())),
                        )
                    },
                ),
                consema.core.Entry("provenance", provenance.toValue()),
                consema.core.Entry(
                    "diagnostics",
                    PvArray(diagnostics.map { it.toValue() }),
                ),
            ),
        )
}
