// The immutable PortableGraph model (Kotlin).
//
// Data authority: RFC 0006 (https://github.com/consema/consema/blob/main/docs/rfcs/0006-portable-graph-and-pgce-v1.md)
// §2-§4, pinned by conformance/vectors/portable-graph-v1.json
// (pgce.empty-vector, pgce.scalar-vector, graph.isomorphic-builder-numbering,
// graph.sharing-is-not-duplication, pgce.cycle-roundtrip); the Rust crate
// https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs is the byte/limits arbiter (GraphLimits
// default lib.rs). consema-go/go/graph/graph.go is a cross-reference only.
//
// The model is independent from the closed fifteen-kind PortableValue model:
// scalar nodes carry resolved tag identifiers and canonical content strings
// (RFC 0006 §2), and the graph layer introduces no value kinds of its own.
//
// Kotlin-idiomatic design: a [Graph] is an immutable value; a [Builder]
// reserves/defines/roots nodes and [Builder.build] validates all references,
// reachability, and traversal depth before freezing. Failures are
// [GraphException] carrying the frozen core.graph.*@1 codes.

package consema.graph

import java.util.concurrent.atomic.AtomicLong

/** The stable node kinds in PortableGraph@1 (RFC 0006 §2). */
enum class NodeKind {
    /** A tagged canonical scalar content node. */
    Scalar,

    /** An ordered node-reference node. */
    Sequence,

    /** An ordered key/value graph-association node. */
    Mapping,
}

/**
 * A graph-local node identity assigned by a [Builder] (RFC 0006 §2:
 * "GraphNodeId is meaningful only inside one immutable graph"). IDs are
 * valid only for the graph built by that builder, and their numeric values
 * are not part of strict graph equality or canonical encoding (RFC 0006
 * §4), so IDs must never be compared across builders or graphs.
 */
data class NodeId internal constructor(internal val graph: Long, internal val index: Int) {
    /** The builder-local numeric representation (the Rust
     * GraphNodeId::as_u64, https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs). */
    fun asUint64(): ULong = index.toULong()
}

/** One ordered mapping association with arbitrary graph-node key and value
 * (RFC 0006 §2: "mapping keys are arbitrary graph nodes"). Duplicate
 * associations and association order are value semantics. */
data class MappingEntry(val key: NodeId, val value: NodeId)

/**
 * One immutable tagged graph node as returned by [Graph.node]. A scalar
 * node carries the producer's canonical content for its tag; the graph
 * layer treats it as an exact UTF-8 string (RFC 0006 §2).
 */
class GraphNode internal constructor(
    val tag: String,
    val kind: NodeKind,
    internal val scalar: String,
    internal val items: List<NodeId>,
    internal val entries: List<MappingEntry>,
) {
    /** The canonical scalar content when this is a scalar node. */
    fun scalarContent(): String? = if (kind == NodeKind.Scalar) scalar else null

    /** The ordered item references when this is a sequence node. */
    fun sequenceItems(): List<NodeId>? = if (kind == NodeKind.Sequence) items else null

    /** The ordered associations when this is a mapping node. */
    fun mappingEntries(): List<MappingEntry>? = if (kind == NodeKind.Mapping) entries else null
}

/** The internal immutable node storage. */
internal class NodeData(
    val tag: String,
    val kind: NodeKind,
    val scalar: String,
    val items: List<NodeId>,
    val entries: List<MappingEntry>,
)

/**
 * The resource bounds for graph construction and traversal (RFC 0006 §6;
 * the Rust GraphLimits, https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs). The zero
 * value rejects every reservation and root; use [GraphLimits.default].
 */
data class GraphLimits(
    /** Maximum ordered roots. */
    val maxRoots: Int,
    /** Maximum graph nodes. */
    val maxNodes: Int,
    /** Maximum sequence-item plus mapping key/value edges. */
    val maxEdges: Int,
    /** Maximum items or associations in one container. */
    val maxContainerEntries: Int,
    /** Maximum UTF-8 bytes in one tag identifier. */
    val maxTagBytes: Int,
    /** Maximum UTF-8 bytes in one scalar's canonical content. */
    val maxScalarBytes: Int,
    /** Maximum first-visit traversal depth (the active traversal path, not
     * alias expansion count; RFC 0006 §6). */
    val maxTraversalDepth: Int,
) {
    companion object {
        /** The frozen defaults (1,000,000 roots, 1,000,000 nodes,
         * 2,000,000 edges, 1,000,000 container entries, 1 MiB tag,
         * 64 MiB scalar, depth 256; https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs
 *). */
        val default = GraphLimits(
            maxRoots = 1_000_000,
            maxNodes = 1_000_000,
            maxEdges = 2_000_000,
            maxContainerEntries = 1_000_000,
            maxTagBytes = 1 shl 20,
            maxScalarBytes = 64 shl 20,
            maxTraversalDepth = 256,
        )
    }
}

/**
 * An immutable rooted, directed, ordered, tagged graph value
 * (PortableGraph; RFC 0006 §2). One graph contains zero or more ordered
 * roots and one closed set of reachable nodes; an empty graph represents an
 * empty stream of roots, not a null scalar. Completed graphs are logically
 * immutable and safe for concurrent reads.
 */
class Graph internal constructor(
    internal val identity: Long,
    internal val roots: List<NodeId>,
    internal val nodes: List<NodeData>,
    internal val edges: Int,
) {
    /** The ordered roots. An empty list represents an empty root stream
     * (RFC 0006 §2). */
    fun roots(): List<NodeId> = roots

    /** The number of reachable graph nodes. */
    fun nodeCount(): Int = nodes.size

    /** The number of sequence-item plus mapping key/value edges. */
    fun edgeCount(): Int = edges

    /** Resolves one graph-local node ID. Returns null when the ID belongs
     * to a different builder or completed graph, or is out of range. */
    fun node(id: NodeId): GraphNode? {
        if (id.graph != identity) {
            return null
        }
        if (id.index < 0 || id.index >= nodes.size) {
            return null
        }
        val n = nodes[id.index]
        return GraphNode(n.tag, n.kind, n.scalar, n.items, n.entries)
    }

    /** The builder-local IDs in builder order. Numeric ID order is not
     * value semantics (the Rust PortableGraph::nodes,
     * https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs). */
    fun nodeIds(): List<NodeId> {
        val ids = ArrayList<NodeId>(nodes.size)
        for (index in nodes.indices) {
            ids.add(NodeId(identity, index))
        }
        return ids
    }
}

/** The RFC 0006 contract name of the immutable graph value; aliases [Graph]
 * so the API freezes the same vocabulary across languages (RFC 0006 §2; the
 * Go `type PortableGraph = Graph` and TS `export type PortableGraph = Graph`
 * counterparts). */
typealias PortableGraph = Graph

/**
 * The mutable reservation/definition lifecycle for one immutable [Graph]
 * (RFC 0006 §3): reserve node identities, define each exactly once as a
 * scalar, sequence, or mapping, add ordered roots, then [build] validates
 * all references, reachability, and traversal depth before freezing the
 * graph. A reserved identity cannot be inspected as a completed node, and
 * build failure returns no partial graph (RFC 0006 §3).
 */
class Builder private constructor(private val identity: Long, private val limits: GraphLimits) {
    private val nodes = ArrayList<NodeData?>()
    private val roots = ArrayList<NodeId>()
    private var edges = 0

    companion object {
        private val nextIdentity = AtomicLong(1)

        /** Creates an empty builder with explicit resource limits. */
        fun withLimits(limits: GraphLimits): Builder = Builder(nextIdentity.getAndIncrement(), limits)

        /** Creates an empty builder with the frozen default limits. */
        fun newBuilder(): Builder = withLimits(GraphLimits.default)
    }

    /** Reserves one graph-local identity for later exact definition. */
    fun reserveNode(): NodeId {
        val observed = nodes.size + 1
        checkLimit("graph-nodes", observed, limits.maxNodes)
        val id = NodeId(identity, nodes.size)
        nodes.add(null)
        return id
    }

    /** Appends one ordered graph root. */
    fun pushRoot(id: NodeId) {
        requireReserved(id)
        checkLimit("graph-roots", roots.size + 1, limits.maxRoots)
        roots.add(id)
    }

    /** Defines one reserved scalar node exactly once, with a resolved tag
     * and the producer's canonical content (RFC 0006 §2). Both the tag and
     * the canonical content must be valid UTF-8. */
    fun defineScalar(id: NodeId, tag: String, canonicalContent: String) {
        validateTag(tag)
        checkLimit("scalar-bytes", canonicalContent.toByteArray(Charsets.UTF_8).size, limits.maxScalarBytes)
        define(id, NodeData(tag, NodeKind.Scalar, canonicalContent, emptyList(), emptyList()), 0)
    }

    /** Defines one reserved ordered sequence node exactly once. */
    fun defineSequence(id: NodeId, tag: String, items: List<NodeId>) {
        validateTag(tag)
        checkLimit("container-entries", items.size, limits.maxContainerEntries)
        for (item in items) {
            requireReserved(item)
        }
        define(id, NodeData(tag, NodeKind.Sequence, "", items.toList(), emptyList()), items.size)
    }

    /** Defines one reserved ordered mapping node exactly once. */
    fun defineMapping(id: NodeId, tag: String, entries: List<MappingEntry>) {
        validateTag(tag)
        checkLimit("container-entries", entries.size, limits.maxContainerEntries)
        for (entry in entries) {
            requireReserved(entry.key)
            requireReserved(entry.value)
        }
        // A mapping association contributes a key and a value edge; the
        // container limit bounds this product, so it cannot overflow.
        define(id, NodeData(tag, NodeKind.Mapping, "", emptyList(), entries.toList()), entries.size * 2)
    }

    /** Stores one node after checking duplicate definition and the edge
     * limit (the Rust GraphBuilder::define, https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs
 *). */
    private fun define(id: NodeId, node: NodeData, newEdges: Int) {
        val index = requireReserved(id)
        if (nodes[index] != null) {
            throw GraphException(
                GraphErrorKind.DUPLICATE_DEFINITION,
                "graph: node ${id.index} defined more than once",
                id = id,
            )
        }
        edges += newEdges
        checkLimit("graph-edges", edges, limits.maxEdges)
        nodes[index] = node
    }

    /** Validates that [id] belongs to this builder and is within the
     * reserved range (the Rust GraphBuilder::require_reserved,
     * https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs). Returns the node index. */
    private fun requireReserved(id: NodeId): Int {
        if (id.graph != identity) {
            throw GraphException(
                GraphErrorKind.WRONG_GRAPH,
                "graph: node ID belongs to a different builder or completed graph",
                id = id,
            )
        }
        if (id.index < 0 || id.index >= nodes.size) {
            throw GraphException(
                GraphErrorKind.UNKNOWN_NODE,
                "graph: node ${id.index} was not reserved by this builder",
                id = id,
            )
        }
        return id.index
    }

    /** Rejects empty tags, tags containing ASCII control or whitespace, and
     * tags that are not valid UTF-8 (the Rust validate_tag,
     * https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs plus the Arc<str> invariant;
     * RFC 0006 §2). */
    private fun validateTag(tag: String) {
        if (tag.isEmpty() || hasInvalidTagChar(tag)) {
            throw GraphException(
                GraphErrorKind.INVALID_TAG,
                "graph: tag is empty or contains ASCII control or whitespace",
            )
        }
        checkLimit("tag-bytes", tag.toByteArray(Charsets.UTF_8).size, limits.maxTagBytes)
    }

    private fun hasInvalidTagChar(tag: String): Boolean {
        for (character in tag) {
            if (character.code < 0x20 || character.code == 0x7f) {
                return true
            }
            if (character == ' ' || character == '\t' || character == '\n' ||
                character == '\u000c' || character == '\r'
            ) {
                return true
            }
        }
        return false
    }

    /** Reports [GraphErrorKind.RESOURCE_LIMIT] when [observed] exceeds
     * [limit] (the Rust check_limit, https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs
 *). */
    private fun checkLimit(name: String, observed: Int, limit: Int) {
        if (observed > limit) {
            throw GraphException(
                GraphErrorKind.RESOURCE_LIMIT,
                "graph: resource limit $name: observed $observed, limit $limit",
                field = name,
                observed = observed,
                limit = limit,
            )
        }
    }

    /** Validates definitions, reachability, and traversal depth, then
     * freezes the graph. Returns no partial graph on failure (RFC 0006
     * §3). */
    fun build(): Graph {
        val completed = ArrayList<NodeData>(nodes.size)
        for ((index, node) in nodes.withIndex()) {
            if (node == null) {
                throw GraphException(
                    GraphErrorKind.UNDEFINED_NODE,
                    "graph: node $index had no definition at build time",
                    id = NodeId(identity, index),
                )
            }
            completed.add(node)
        }
        val (order, _) = canonicalOrder(completed, roots, limits.maxTraversalDepth)
        if (order.size != completed.size) {
            val reachable = BooleanArray(completed.size)
            for (index in order) {
                reachable[index] = true
            }
            for ((index, ok) in reachable.withIndex()) {
                if (!ok) {
                    throw GraphException(
                        GraphErrorKind.UNREACHABLE_NODE,
                        "graph: node $index is not reachable from any root",
                        id = NodeId(identity, index),
                    )
                }
            }
        }
        return Graph(identity, roots.toList(), completed, edges)
    }
}

/**
 * Assigns canonical IDs by deterministic depth-first pre-order (RFC 0006
 * §4): visit roots in root order; when a node is first encountered assign
 * the next ID; for a sequence visit items in order; for a mapping visit
 * each association in order, key before value; an already assigned node is
 * a reference and is not traversed again. [maxDepth] < 0 disables the
 * first-visit depth limit. Returns the original indices in visit order and
 * the canonical ID of every original index (the Rust canonical_order,
 * https://github.com/consema/consema-rs/blob/main/consema-graph/src/lib.rs).
 */
internal fun canonicalOrder(
    nodes: List<NodeData>,
    roots: List<NodeId>,
    maxDepth: Int,
): Pair<IntArray, IntArray> {
    val order = ArrayList<Int>(nodes.size)
    val canonicalIds = IntArray(nodes.size)
    val visited = BooleanArray(nodes.size)
    // A stack of (index, depth) pairs; push in reverse so the first root
    // pops first.
    val stack = ArrayDeque<Pair<Int, Int>>()
    for (i in roots.indices.reversed()) {
        stack.addLast(roots[i].index to 0)
    }
    var next = 0
    while (stack.isNotEmpty()) {
        val (index, depth) = stack.removeLast()
        if (visited[index]) {
            continue
        }
        if (maxDepth >= 0 && depth > maxDepth) {
            throw GraphException(
                GraphErrorKind.RESOURCE_LIMIT,
                "graph: resource limit traversal-depth: observed $depth, limit $maxDepth",
                field = "traversal-depth",
                observed = depth,
                limit = maxDepth,
            )
        }
        visited[index] = true
        order.add(index)
        canonicalIds[index] = next
        next++
        val node = nodes[index]
        val childDepth = depth + 1
        when (node.kind) {
            NodeKind.Scalar -> {}
            NodeKind.Sequence -> {
                // Push reversed so items pop in stored order.
                for (i in node.items.indices.reversed()) {
                    stack.addLast(node.items[i].index to childDepth)
                }
            }
            NodeKind.Mapping -> {
                // Push value then key per association, all reversed, so
                // associations pop in stored order with key before value.
                for (i in node.entries.indices.reversed()) {
                    stack.addLast(node.entries[i].value.index to childDepth)
                    stack.addLast(node.entries[i].key.index to childDepth)
                }
            }
        }
    }
    return IntArray(order.size) { order[it] } to canonicalIds
}
