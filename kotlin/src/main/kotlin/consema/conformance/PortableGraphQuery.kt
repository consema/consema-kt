// The conformance-runner portable-graph query executor.
//
// Data authority (language-neutral sources first):
//   - consema-rs/consema-conformance/src/portable_graph_v1.rs:184-219 (the
//     graph.query cases: the Input expression over a graph yields the root
//     nodes, the pipeline operators run in order, and the final matches are
//     compared by builder node ID).
//   - consema-core/src/query.rs (the operator semantics this executor
//     reproduces: graph.reachable-nodes@1 yields the reachable closure in
//     canonical first-discovery order with identity deduplication,
//     graph.try-sequence-elements@1 yields the elements of a Sequence and
//     nothing for other kinds, graph.sequence-element-node@1 extracts the
//     node of a sequence-element match, core.distinct-by-identity@1 keeps
//     the first occurrence of every identity).
//   - conformance/vectors/portable-graph-v1.json (query.reachable-canonical-
//     order, query.distinct-shared-identity).
//
// Kotlin-idiomatic design: a small deterministic executor in the conformance
// package (the Kotlin graph package owns the immutable Graph model but no
// query executor); matches carry graph-local node identities, and the final
// node IDs are the builder-local numeric IDs the vectors assert.

package consema.conformance

import consema.graph.Graph
import consema.graph.NodeId
import consema.graph.NodeKind
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException

/** One graph query match with graph-local identities. */
internal sealed class GraphQueryMatch {
    /** One graph node. */
    data class Node(val node: NodeId) : GraphQueryMatch()

    /** One direct sequence association. */
    data class SequenceElement(val parent: NodeId, val ordinal: Int, val node: NodeId) :
        GraphQueryMatch()

    /** One direct mapping association. */
    data class MappingEntry(val parent: NodeId, val ordinal: Int, val key: NodeId, val value: NodeId) :
        GraphQueryMatch()
}

/**
 * Evaluates one validated portable-graph query over one graph and returns
 * the ordered final matches (the graph.query execution path,
 * portable_graph_v1.rs:184-219). The Input expression yields the ordered
 * root nodes; each pipeline operator transforms the match stream.
 */
internal fun executeGraphQuery(
    definition: QueryDefinition,
    graph: Graph,
): List<GraphQueryMatch> {
    // The Apply chain nests pipeline order innermost first
    // (Input.then(op1).then(op2) is Apply(op2, Apply(op1, Input))), so the
    // operators execute from the end of the collected chain.
    val chain = ArrayList<consema.protocol.OperatorCall>()
    var current = definition.expression
    while (current.kind == ExpressionKind.Apply) {
        chain.add(current.operator!!)
        current = current.input!!
    }
    if (current.kind != ExpressionKind.Input) {
        throw QueryFailureException(
            consema.protocol.QueryFailureKind.INVALID_ARGUMENT,
            argument = "expression",
        )
    }
    var matches: List<GraphQueryMatch> = graph.roots().map { GraphQueryMatch.Node(it) }
    for (index in chain.indices.reversed()) {
        val operator = chain[index]
        val next = ArrayList<GraphQueryMatch>()
        for (match in matches) {
            next.addAll(applyGraphOperator(operator.id, match, graph))
        }
        matches = applyGraphSelection(next, operator.id)
    }
    return matches
}

/** Applies one graph operator to one match (query.rs operator table). */
private fun applyGraphOperator(id: String, match: GraphQueryMatch, graph: Graph): List<GraphQueryMatch> =
    when (id) {
        "graph.reachable-nodes" -> reachableNodes(match, graph)
        "graph.try-sequence-elements" -> when (match) {
            is GraphQueryMatch.Node -> {
                val node = graph.node(match.node) ?: return emptyList()
                if (node.kind != NodeKind.Sequence) {
                    emptyList()
                } else {
                    (node.sequenceItems() ?: emptyList()).mapIndexed { ordinal, item ->
                        GraphQueryMatch.SequenceElement(match.node, ordinal, item)
                    }
                }
            }
            else -> emptyList()
        }
        "graph.sequence-element-node" -> when (match) {
            is GraphQueryMatch.SequenceElement -> listOf(GraphQueryMatch.Node(match.node))
            else -> emptyList()
        }
        "core.distinct-by-identity" -> listOf(match)
        else -> throw QueryFailureException(
            consema.protocol.QueryFailureKind.UNKNOWN_OPERATOR,
            operator = id,
            version = 1,
        )
    }

/** The reachable closure of one match in canonical first-discovery order
 * with identity deduplication (the graph.reachable-nodes@1 semantics). */
private fun reachableNodes(match: GraphQueryMatch, graph: Graph): List<GraphQueryMatch> {
    val root = when (match) {
        is GraphQueryMatch.Node -> match.node
        is GraphQueryMatch.SequenceElement -> match.node
        is GraphQueryMatch.MappingEntry -> match.value
    }
    if (graph.node(root) == null) {
        return emptyList()
    }
    val order = ArrayList<NodeId>()
    val visited = HashSet<NodeId>()
    val stack = ArrayDeque<NodeId>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (!visited.add(id)) {
            continue
        }
        order.add(id)
        val node = graph.node(id) ?: continue
        when (node.kind) {
            NodeKind.Scalar -> {}
            NodeKind.Sequence -> {
                val items = node.sequenceItems() ?: emptyList()
                for (index in items.indices.reversed()) {
                    stack.addLast(items[index])
                }
            }
            NodeKind.Mapping -> {
                val entries = node.mappingEntries() ?: emptyList()
                for (index in entries.indices.reversed()) {
                    stack.addLast(entries[index].value)
                    stack.addLast(entries[index].key)
                }
            }
        }
    }
    return order.map { GraphQueryMatch.Node(it) }
}

/** Applies the identity deduplication of the operators that publish unique
 * node identities: core.distinct-by-identity@1 and the reachable closure of
 * graph.reachable-nodes@1 (the reachable sets of the input matches are
 * merged without duplicates; the vector query.reachable-canonical-order
 * pins the merged canonical order). */
private fun applyGraphSelection(matches: List<GraphQueryMatch>, operatorId: String): List<GraphQueryMatch> =
    if (operatorId != "core.distinct-by-identity" && operatorId != "graph.reachable-nodes") {
        matches
    } else {
        val seen = HashSet<NodeId>()
        val kept = ArrayList<GraphQueryMatch>()
        for (match in matches) {
            val identity = when (match) {
                is GraphQueryMatch.Node -> match.node
                is GraphQueryMatch.SequenceElement -> match.node
                is GraphQueryMatch.MappingEntry -> match.value
            }
            if (seen.add(identity)) {
                kept.add(match)
            }
        }
        kept
    }

/** Builds one pipeline expression from the vector's operator spellings
 * (portable_graph_v1.rs:189-193). */
internal fun graphQueryExpression(pipeline: List<String>): QueryExpression {
    var expression = QueryExpression(ExpressionKind.Input)
    for (operator in pipeline) {
        val (id, version) = parseGraphOperator(operator)
        expression = expression.then(OperatorCall(id, version))
    }
    return expression
}

/** Parses one `name@version` operator spelling. */
private fun parseGraphOperator(text: String): Pair<String, Int> {
    val at = text.lastIndexOf('@')
    if (at <= 0) {
        throw QueryFailureException(
            consema.protocol.QueryFailureKind.UNKNOWN_OPERATOR,
            operator = text,
            version = 1,
        )
    }
    val version = text.substring(at + 1).toIntOrNull()
        ?: throw QueryFailureException(
            consema.protocol.QueryFailureKind.UNKNOWN_OPERATOR,
            operator = text,
            version = 1,
        )
    return text.substring(0, at) to version
}

/** The builder-local numeric ID of one final node match
 * (portable_graph_v1.rs:206-213). */
internal fun graphMatchNodeId(match: GraphQueryMatch): ULong = when (match) {
    is GraphQueryMatch.Node -> match.node.asUint64()
    is GraphQueryMatch.SequenceElement -> match.node.asUint64()
    is GraphQueryMatch.MappingEntry -> match.value.asUint64()
}
