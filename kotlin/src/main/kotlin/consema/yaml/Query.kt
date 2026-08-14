// Versioned YAML native-semantic and lossless-syntax query execution.
//
// Data authority:
//   - RFC 0007 §9 (https://github.com/consema/consema/blob/main/docs/rfcs/0007-yaml-family-profiles-and-safety-v1.md): domains yaml.native-semantic-query@1 and
//     yaml.lossless-syntax-query@1; native roles Stream, Document, Node,
//     MappingEntry, SequenceElement, AnchorDefinition, AliasOccurrence; the
//     frozen v1 operator surface; every match carries a snapshot-bound role
//     and exact raw span; syntax text comparison uses decoded Unicode text
//     while retaining raw byte spans.
//   - RFC 0003 §8 (https://github.com/consema/consema/blob/main/docs/rfcs/0003-source-syntax-query-and-patch-v1.md): the standard input sequence is every lossless syntax piece
//     in raw source order; each match carries its NodeRef, raw Span,
//     format-specific kind, and source ordinal.
//   - conformance/vectors/yaml-v1.json pins the query facts
//     (query.mapping-entries, query.alias-target, query.syntax-comments,
//     query.resource-limit at lines 50-69).
//   - https://github.com/consema/consema-rs/blob/main/consema-yaml/src/query.rs is the byte-arbitration authority
//     (matches query.rs, execution query.rs, operators
//     query.rs, selection query.rs); consema-core/src/
//     query.rs pins QueryLimits defaults (max_steps 100_000,
//     max_results 100_000).
//
// Kotlin-idiomatic design: execution throws the protocol package's typed
// [consema.protocol.QueryFailureException] carrying the registered code
// (query_failure_code mapping, error_registry.rs); matches are a
// sealed class so exhaustive `when` can never meet an unknown match.

package consema.yaml

import consema.core.PvString
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.Span
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind
import consema.protocol.QuerySelection
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Query resource limits (query.rs). */
data class QueryLimits(
    /** Maximum operator steps. */
    val maxSteps: Int,
    /** Maximum complete results buffered by an operator. */
    val maxResults: Int,
) {
    companion object {
        /** The frozen defaults (query.rs): 100,000 steps and
         * 100,000 results. */
        val default = QueryLimits(maxSteps = 100_000, maxResults = 100_000)
    }
}

/** Cooperative cancellation flag (query.rs). */
class CancellationToken {
    private val cancelled = AtomicBoolean(false)

    /** Whether execution was cancelled. */
    fun isCancelled(): Boolean = cancelled.get()

    /** Requests cancellation; queries check the flag at operator steps. */
    fun cancel() {
        cancelled.set(true)
    }
}

/** Owned snapshot-bound YAML native semantic query match (query.rs). */
sealed class YamlMatch {
    /** Complete YAML serialization stream. */
    data class Stream(
        /** Stream identity. */
        val stream: NodeRef,
        /** Exact raw source span. */
        val span: Span,
        /** Number of independent documents. */
        val documentCount: Int,
    ) : YamlMatch()

    /** One independent document. */
    data class Document(
        /** Zero-based stream ordinal. */
        val ordinal: Int,
        /** Document identity. */
        val document: NodeRef,
        /** Representation root identity. */
        val root: NodeRef,
        /** Raw presentation span. */
        val span: Span,
    ) : YamlMatch()

    /** One YAML representation node. */
    data class Node(
        /** Representation identity. */
        val node: NodeRef,
        /** Scalar, sequence, or mapping. */
        val kind: YamlNodeKind,
        /** Resolved global tag URI. */
        val tag: String,
        /** Scalar category when the node is scalar. */
        val scalarKind: YamlScalarKind?,
        /** Canonical scalar content when the node is scalar. */
        val canonical: String?,
        /** Defining anchor name, when present. */
        val anchor: String?,
        /** Raw representation span. */
        val span: Span,
    ) : YamlMatch()

    /** One ordered mapping association. */
    data class MappingEntry(
        /** Zero-based direct association ordinal. */
        val ordinal: Int,
        /** Association identity. */
        val entry: NodeRef,
        /** Arbitrary key representation identity. */
        val key: NodeRef,
        /** Value representation identity. */
        val value: NodeRef,
        /** Raw association span. */
        val span: Span,
    ) : YamlMatch()

    /** One ordered sequence association. */
    data class SequenceElement(
        /** Zero-based direct association ordinal. */
        val ordinal: Int,
        /** Association identity. */
        val element: NodeRef,
        /** Referenced representation identity. */
        val node: NodeRef,
        /** Raw element occurrence span. */
        val span: Span,
    ) : YamlMatch()

    /** One anchor definition occurrence. */
    data class AnchorDefinition(
        /** Exact anchor name without `&`. */
        val name: String,
        /** Definition occurrence identity. */
        val definition: NodeRef,
        /** Anchored representation identity. */
        val node: NodeRef,
        /** Exact raw `&name` span. */
        val span: Span,
    ) : YamlMatch()

    /** One alias serialization occurrence. */
    data class AliasOccurrence(
        /** Zero-based serialization-order ordinal. */
        val ordinal: Int,
        /** Exact alias name without `*`. */
        val name: String,
        /** Alias occurrence identity. */
        val alias: NodeRef,
        /** Shared target representation identity. */
        val target: NodeRef,
        /** Exact raw `*name` span. */
        val span: Span,
    ) : YamlMatch()

    /** Primary process-local identity for this match (query.rs). */
    fun nodeRef(): NodeRef =
        when (this) {
            is Stream -> stream
            is Document -> document
            is Node -> node
            is MappingEntry -> entry
            is SequenceElement -> element
            is AnchorDefinition -> definition
            is AliasOccurrence -> alias
        }

    /** Exact raw source span associated with the match (query.rs). */
    fun span(): Span =
        when (this) {
            is Stream -> span
            is Document -> span
            is Node -> span
            is MappingEntry -> span
            is SequenceElement -> span
            is AnchorDefinition -> span
            is AliasOccurrence -> span
        }
}

/** Owned snapshot-bound YAML lossless syntax query match (query.rs). */
data class YamlSyntaxMatch(
    /** Process-local syntax-piece identity. */
    val node: NodeRef,
    /** Exact raw source span. */
    val span: Span,
    /** Format-specific lossless kind. */
    val kind: YamlSyntaxKind,
    /** Zero-based source-order position. */
    val ordinal: Int,
)

/**
 * Executes a validated YAML native semantic query against one immutable
 * stream (query.rs). The root input is the stream match; the domain
 * binding rejects other domains with a DomainMismatch failure.
 */
fun executeYamlQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): List<YamlMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "yaml.native-semantic-query" ||
        definition.domain.version != 1
    ) {
        throw QueryFailureException(
            QueryFailureKind.DOMAIN_MISMATCH,
            domain = definition.domain,
        )
    }
    val context = Context(document, limits, cancellation)
    context.step(1)
    val input = listOf(
        YamlMatch.Stream(
            stream = document.streamNodeRef(),
            span = document.streamSpan(),
            documentCount = document.documentCount(),
        ),
    )
    val matches = executeExpression(definition.expression, input, context)
    return applySelection(matches, definition.selection)
}

/**
 * Executes a validated YAML lossless syntax query against every source piece
 * in raw order (query.rs).
 */
fun executeYamlSyntaxQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): List<YamlSyntaxMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "yaml.lossless-syntax-query" ||
        definition.domain.version != 1
    ) {
        throw QueryFailureException(
            QueryFailureKind.DOMAIN_MISMATCH,
            domain = definition.domain,
        )
    }
    val context = Context(document, limits, cancellation)
    val pieces = document.pieces()
    val kinds = document.losslessSyntaxKinds()
    context.step(pieces.size)
    val input = pieces.mapIndexed { ordinal, piece ->
        YamlSyntaxMatch(
            node = document.authority.nodeRef(ordinal.toLong(), NodeRole.YamlSyntaxPiece),
            span = piece.span,
            kind = kinds[ordinal],
            ordinal = ordinal,
        )
    }
    val matches = executeSyntaxExpression(definition.expression, input, context)
    return applySelection(matches, definition.selection)
}

/** Execution context carrying limits and cancellation (query.rs). */
private class Context(
    val document: Document,
    val limits: QueryLimits,
    val cancellation: CancellationToken,
) {
    var steps = 0

    fun step(results: Int) {
        if (cancellation.isCancelled()) {
            throw QueryFailureException(QueryFailureKind.CANCELLED)
        }
        steps = steps.inc()
        if (steps > limits.maxSteps || results > limits.maxResults) {
            throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
        }
    }

    fun nodeMatch(index: Int): YamlMatch {
        val node = document.native.nodes[index]
        val (kind, scalarKind, canonical) = when (val content = node.content) {
            is NativeContent.Scalar -> Triple(
                YamlNodeKind.Scalar,
                content.scalar.kind,
                content.scalar.canonical,
            )
            is NativeContent.Sequence -> Triple(YamlNodeKind.Sequence, null, null)
            is NativeContent.Mapping -> Triple(YamlNodeKind.Mapping, null, null)
        }
        return YamlMatch.Node(
            node = nodeRef(document.authority, index),
            kind = kind,
            tag = node.tag,
            scalarKind = scalarKind,
            canonical = canonical,
            anchor = node.anchor,
            span = node.span,
        )
    }
}

private fun executeExpression(
    expression: QueryExpression,
    input: List<YamlMatch>,
    context: Context,
): List<YamlMatch> = when (expression.kind) {
    ExpressionKind.Input -> input
    ExpressionKind.Apply -> {
        val inner = executeExpression(expression.input!!, input, context)
        applyOperator(expression.operator!!, inner, context)
    }
    ExpressionKind.Concat -> {
        val output = ArrayList<YamlMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<YamlMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
        }
        output.sortWith(
            compareBy<YamlMatch> { it.span().startByte }
                .thenBy { it.span().endByte }
                .thenBy { roleOrder(it.nodeRef().role) }
                .thenBy { it.nodeRef().index.toInt() },
        )
        context.step(output.size)
        output
    }
}

private fun executeSyntaxExpression(
    expression: QueryExpression,
    input: List<YamlSyntaxMatch>,
    context: Context,
): List<YamlSyntaxMatch> = when (expression.kind) {
    ExpressionKind.Input -> input
    ExpressionKind.Apply -> {
        val inner = executeSyntaxExpression(expression.input!!, input, context)
        applySyntaxOperator(expression.operator!!, inner, context)
    }
    ExpressionKind.Concat -> {
        val output = ArrayList<YamlSyntaxMatch>()
        for (branch in expression.branches) {
            output.addAll(executeSyntaxExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<YamlSyntaxMatch>()
        for (branch in expression.branches) {
            output.addAll(executeSyntaxExpression(branch, input, context))
        }
        output.sortBy { it.ordinal }
        context.step(output.size)
        output
    }
}

private fun applySyntaxOperator(
    operator: OperatorCall,
    input: List<YamlSyntaxMatch>,
    context: Context,
): List<YamlSyntaxMatch> {
    val output: List<YamlSyntaxMatch> = when (operator.id) {
        "yaml.syntax-kind-is" -> {
            val expected = YamlSyntaxKind.fromName(
                operatorArgumentString(operator, "kind"),
            ) ?: error("kind name was validated before binding")
            input.filter { it.kind == expected }
        }
        "yaml.syntax-text-equals" -> {
            val expected = encodedText(
                operatorArgumentString(operator, "text"),
                context.document.source.encodingFacts.selected,
            )
            input.filter { item ->
                context.document.source.bytes()
                    .copyOfRange(item.span.startByte, item.span.endByte)
                    .contentEquals(expected)
            }
        }
        "core.take" -> {
            val count = operatorArgumentInteger(operator, "count").toInt()
            input.take(count)
        }
        "core.distinct-by-identity" -> {
            val seen = HashSet<NodeRef>()
            input.filter { seen.add(it.node) }
        }
        else -> error("validated YAML syntax operator")
    }
    context.step(output.size)
    return output
}

private fun applyOperator(
    operator: OperatorCall,
    input: List<YamlMatch>,
    context: Context,
): List<YamlMatch> {
    val output = ArrayList<YamlMatch>()
    when (operator.id) {
        "yaml.documents" -> {
            for (item in input) {
                if (item !is YamlMatch.Stream) {
                    continue
                }
                for ((ordinal, document) in context.document.native.documents.withIndex()) {
                    output.add(
                        YamlMatch.Document(
                            ordinal = ordinal,
                            document = context.document.authority.nodeRef(
                                ordinal.toLong(),
                                NodeRole.YamlDocument,
                            ),
                            root = nodeRef(context.document.authority, document.root),
                            span = document.span,
                        ),
                    )
                }
            }
        }
        "yaml.document-root" -> {
            for (item in input) {
                if (item is YamlMatch.Document) {
                    val index = context.document.validateNodeRef(item.root, listOf(NodeRole.YamlNode))
                    output.add(context.nodeMatch(index))
                }
            }
        }
        "yaml.where-node-kind" -> {
            val expected = operatorArgumentString(operator, "kind")
            output.addAll(
                input.filter {
                    it is YamlMatch.Node && nodeKindName(it.kind) == expected
                },
            )
        }
        "yaml.where-tag" -> {
            val expected = operatorArgumentString(operator, "tag")
            output.addAll(
                input.filter {
                    it is YamlMatch.Node && it.tag == expected
                },
            )
        }
        "yaml.scalar-canonical-equals" -> {
            val expected = operatorArgumentString(operator, "canonical")
            output.addAll(
                input.filter {
                    it is YamlMatch.Node && it.canonical == expected
                },
            )
        }
        "yaml.try-sequence-elements" -> {
            for (item in input) {
                if (item !is YamlMatch.Node) {
                    continue
                }
                val index = context.document.validateNodeRef(item.node, listOf(NodeRole.YamlNode))
                val content = context.document.native.nodes[index].content
                if (content !is NativeContent.Sequence) {
                    continue
                }
                for ((ordinal, element) in content.items.withIndex()) {
                    output.add(
                        YamlMatch.SequenceElement(
                            ordinal = ordinal,
                            element = context.document.authority.nodeRef(
                                element.identity,
                                NodeRole.YamlSequenceElement,
                            ),
                            node = nodeRef(context.document.authority, element.node),
                            span = element.span,
                        ),
                    )
                }
            }
        }
        "yaml.sequence-element-node" -> {
            for (item in input) {
                if (item is YamlMatch.SequenceElement) {
                    val index = context.document.validateNodeRef(item.node, listOf(NodeRole.YamlNode))
                    output.add(context.nodeMatch(index))
                }
            }
        }
        "yaml.try-mapping-entries" -> {
            for (item in input) {
                if (item !is YamlMatch.Node) {
                    continue
                }
                val index = context.document.validateNodeRef(item.node, listOf(NodeRole.YamlNode))
                val content = context.document.native.nodes[index].content
                if (content !is NativeContent.Mapping) {
                    continue
                }
                for ((ordinal, entry) in content.entries.withIndex()) {
                    output.add(
                        YamlMatch.MappingEntry(
                            ordinal = ordinal,
                            entry = context.document.authority.nodeRef(
                                entry.identity,
                                NodeRole.YamlMappingEntry,
                            ),
                            key = nodeRef(context.document.authority, entry.key),
                            value = nodeRef(context.document.authority, entry.value),
                            span = entry.span,
                        ),
                    )
                }
            }
        }
        "yaml.mapping-entry-key", "yaml.mapping-entry-value" -> {
            val takeKey = operator.id == "yaml.mapping-entry-key"
            for (item in input) {
                if (item is YamlMatch.MappingEntry) {
                    val target = if (takeKey) item.key else item.value
                    val index = context.document.validateNodeRef(target, listOf(NodeRole.YamlNode))
                    output.add(context.nodeMatch(index))
                }
            }
        }
        "yaml.anchor-definition" -> {
            for (item in input) {
                if (item !is YamlMatch.Node) {
                    continue
                }
                val index = context.document.validateNodeRef(item.node, listOf(NodeRole.YamlNode))
                val node = context.document.native.nodes[index]
                if (node.anchor != null && node.anchorSpan != null) {
                    output.add(
                        YamlMatch.AnchorDefinition(
                            name = node.anchor!!,
                            definition = context.document.authority.nodeRef(
                                index.toLong(),
                                NodeRole.YamlAnchorDefinition,
                            ),
                            node = item.node,
                            span = node.anchorSpan!!,
                        ),
                    )
                }
            }
        }
        "yaml.anchor-node" -> {
            for (item in input) {
                if (item is YamlMatch.AnchorDefinition) {
                    val index = context.document.validateNodeRef(item.node, listOf(NodeRole.YamlNode))
                    output.add(context.nodeMatch(index))
                }
            }
        }
        "yaml.alias-occurrences" -> {
            for (item in input) {
                if (item !is YamlMatch.Stream) {
                    continue
                }
                for ((ordinal, alias) in context.document.native.aliases.withIndex()) {
                    output.add(
                        YamlMatch.AliasOccurrence(
                            ordinal = ordinal,
                            name = alias.name,
                            alias = context.document.authority.nodeRef(
                                alias.identity,
                                NodeRole.YamlAlias,
                            ),
                            target = nodeRef(context.document.authority, alias.target),
                            span = alias.span,
                        ),
                    )
                }
            }
        }
        "yaml.alias-target" -> {
            for (item in input) {
                if (item is YamlMatch.AliasOccurrence) {
                    val index = context.document.validateNodeRef(item.target, listOf(NodeRole.YamlNode))
                    output.add(context.nodeMatch(index))
                }
            }
        }
        "core.take" -> {
            val count = operatorArgumentInteger(operator, "count").toInt()
            output.addAll(input.take(count))
        }
        "core.distinct-by-identity" -> {
            val seen = HashSet<NodeRef>()
            for (item in input) {
                if (seen.add(item.nodeRef())) {
                    output.add(item)
                }
            }
        }
        else -> error("validated YAML operator")
    }
    context.step(output.size)
    return output
}

/** Applies the cardinality selection (query.rs). */
internal fun <T> applySelection(values: List<T>, selection: QuerySelection): List<T> =
    when (selection) {
        QuerySelection.All -> values
        QuerySelection.First -> values.take(1)
        QuerySelection.Last -> values.lastOrNull()?.let { listOf(it) } ?: emptyList()
        QuerySelection.ZeroOrOne -> {
            if (values.size <= 1) values else throw cardinalityViolation(selection, values.size)
        }
        QuerySelection.RequireOne -> {
            if (values.size == 1) values else throw cardinalityViolation(selection, values.size)
        }
    }

private fun cardinalityViolation(selection: QuerySelection, actual: Int): QueryFailureException =
    QueryFailureException(
        QueryFailureKind.CARDINALITY_VIOLATION,
        "query: selection $selection violated (actual $actual)",
    )

/** Decoded-text comparison bytes under the selected source encoding
 * (query.rs). */
private fun encodedText(value: String, encoding: consema.document.SourceEncoding): ByteArray =
    when (encoding) {
        consema.document.SourceEncoding.Utf8 -> value.toByteArray(Charsets.UTF_8)
        consema.document.SourceEncoding.Utf16Le -> {
            val units = value.encodeToUtf16Array()
            val bytes = ByteArray(units.size * 2)
            for ((index, unit) in units.withIndex()) {
                bytes[index * 2] = (unit.toInt() and 0xff).toByte()
                bytes[index * 2 + 1] = (unit.toInt() shr 8).toByte()
            }
            bytes
        }
        consema.document.SourceEncoding.Utf16Be -> {
            val units = value.encodeToUtf16Array()
            val bytes = ByteArray(units.size * 2)
            for ((index, unit) in units.withIndex()) {
                bytes[index * 2] = (unit.toInt() shr 8).toByte()
                bytes[index * 2 + 1] = (unit.toInt() and 0xff).toByte()
            }
            bytes
        }
        else -> ByteArray(0)
    }

private fun String.encodeToUtf16Array(): ShortArray {
    val units = ShortArray(length)
    for (index in indices) {
        units[index] = this[index].code.toShort()
    }
    return units
}

private fun nodeKindName(kind: YamlNodeKind): String =
    when (kind) {
        YamlNodeKind.Scalar -> "Scalar"
        YamlNodeKind.Sequence -> "Sequence"
        YamlNodeKind.Mapping -> "Mapping"
    }

/** Structural merge order by role (query.rs). */
private fun roleOrder(role: NodeRole): Int =
    when (role) {
        NodeRole.YamlStream -> 0
        NodeRole.YamlDocument -> 1
        NodeRole.YamlMappingEntry, NodeRole.YamlSequenceElement -> 2
        NodeRole.YamlAnchorDefinition -> 3
        NodeRole.YamlAlias -> 4
        NodeRole.YamlNode -> 5
        else -> 6
    }

private fun operatorArgumentString(operator: OperatorCall, name: String): String =
    (operator.arguments[name] as? PvString)?.value ?: error("validated argument: $name")

private fun operatorArgumentInteger(operator: OperatorCall, name: String): BigInteger {
    val value = operator.arguments[name] ?: error("validated argument: $name")
    val integer = value as? consema.core.PvInteger ?: error("validated argument: $name")
    return integer.value
}
