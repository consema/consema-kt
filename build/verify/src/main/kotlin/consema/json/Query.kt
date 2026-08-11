// Versioned JSON native-semantic and lossless-syntax query execution.
//
// Data authority:
//   - RFC 0005 §7 (docs/rfcs/0005-json-family-production-v1.md:151-172):
//     domains json.native-semantic-query@1|2 and json.lossless-syntax-query@1|2;
//     v2 extends the permitted native kind set with BinaryFloat64 and the
//     syntax kind set with Identifier; strict/JSONC execute either version,
//     JSON5 requires v2; binding validates the domain/kind combination.
//   - RFC 0003 §8 (docs/rfcs/0003-source-syntax-query-and-patch-v1.md:173-248):
//     the standard input sequence is every lossless syntax piece in raw
//     source order; each match carries its NodeRef, raw Span, format-specific
//     kind, and source ordinal; kind names and argument types are validated
//     before the first match.
//   - conformance/vectors/syntax-query-v1.json (json cases, lines 5-52) and
//     json-family-v2.json (json5.query.*) pin the match order/ordinal/text
//     facts; crates/consema-json/src/query.rs is the byte-arbitration
//     authority (execution query.rs:91-305, operators query.rs:307-477,
//     selection query.rs:479-496); consema-core/src/query.rs:2967-2993 pins
//     QueryLimits defaults (max_steps 100_000, max_results 100_000) and the
//     CancellationToken shape.
//
// Kotlin-idiomatic design: execution throws the protocol package's typed
// [consema.protocol.QueryFailureException] carrying the registered code
// (query_failure_code mapping, error_registry.rs:1515-1529); the cursor
// terminal contract (RFC 0003 §9) is a synchronous complete result list here,
// with the terminal state always Completed after a successful execution.

package consema.json

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

/** Query resource limits (query.rs:2967-2981). */
data class QueryLimits(
    /** Maximum operator steps. */
    val maxSteps: Int,
    /** Maximum complete results buffered by an operator. */
    val maxResults: Int,
) {
    companion object {
        /** The frozen defaults (query.rs:2974-2981): 100,000 steps and
         * 100,000 results. */
        val default = QueryLimits(maxSteps = 100_000, maxResults = 100_000)
    }
}

/** Cooperative cancellation flag (query.rs:2984-2993). */
class CancellationToken {
    private val cancelled = AtomicBoolean(false)

    /** Whether execution was cancelled. */
    fun isCancelled(): Boolean = cancelled.get()

    /** Requests cancellation; queries check the flag at operator steps. */
    fun cancel() {
        cancelled.set(true)
    }
}

/** Owned snapshot-bound JSON native semantic query match (query.rs:11-43). */
sealed class JsonMatch {
    /** JSON native value. */
    data class Value(
        /** Exact value identity. */
        val node: NodeRef,
        /** Native category when locally available. */
        val kind: JsonValueKind?,
        internal val index: Int,
    ) : JsonMatch()

    /** Ordered object member with duplicate identity preserved. */
    data class ObjectMember(
        /** Zero-based member ordinal. */
        val ordinal: Int,
        /** Decoded name when available. */
        val name: String?,
        /** Key identity. */
        val key: NodeRef,
        /** Value identity. */
        val value: NodeRef,
        /** Association identity. */
        val member: NodeRef,
        internal val index: Int,
    ) : JsonMatch()

    /** Ordered array element. */
    data class ArrayElement(
        /** Zero-based element ordinal. */
        val ordinal: Int,
        /** Association identity. */
        val element: NodeRef,
        /** Value identity. */
        val value: NodeRef,
        internal val index: Int,
    ) : JsonMatch()

    internal fun identity(): NodeRef =
        when (this) {
            is Value -> node
            is ObjectMember -> member
            is ArrayElement -> element
        }

    internal fun entityIndex(): Int =
        when (this) {
            is Value -> index
            is ObjectMember -> index
            is ArrayElement -> index
        }
}

/** Owned snapshot-bound JSON lossless syntax query match (query.rs:55-88). */
data class JsonSyntaxMatch(
    /** Process-local syntax-piece identity. */
    val node: NodeRef,
    /** Exact raw source span. */
    val span: Span,
    /** Format-specific lossless kind. */
    val kind: JsonSyntaxKind,
    /** Zero-based source-order position. */
    val ordinal: Int,
)

/**
 * Executes a validated JSON native semantic query against one immutable
 * snapshot (query.rs:91-125). The root is the first standard result; it must
 * not bypass result limits. The domain binding rejects JSON5 documents under
 * domain v1 with a DomainMismatch failure.
 */
fun executeJsonQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): List<JsonMatch> {
    val definition = executable.validated.definition
    val version = definition.domain.version
    if (definition.domain.id != "json.native-semantic-query" ||
        (version != 1 && version != 2) ||
        (document.profile == JsonProfile.Json5StandardV1 && version != 2)
    ) {
        throw QueryFailureException(
            QueryFailureKind.DOMAIN_MISMATCH,
            domain = definition.domain,
        )
    }
    val context = Context(document, limits, cancellation)
    val root = document.root()
    context.step(1)
    val input = listOf(
        JsonMatch.Value(
            node = root.nodeRef(),
            kind = when (val kind = root.kind()) {
                is SemanticAvailability.Available -> kind.value
                is SemanticAvailability.Unavailable -> null
            },
            index = root.rawIndex(),
        ),
    )
    val matches = executeExpression(definition.expression, input, context)
    return applySelection(matches, definition.selection)
}

/**
 * Executes a validated JSON lossless syntax query against every source piece
 * in raw order (query.rs:142-183). Matches carry the format-owned kind and
 * the source ordinal; the domain binding rejects JSON5 documents under
 * domain v1.
 */
fun executeJsonSyntaxQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): List<JsonSyntaxMatch> {
    val definition = executable.validated.definition
    val version = definition.domain.version
    if (definition.domain.id != "json.lossless-syntax-query" ||
        (version != 1 && version != 2) ||
        (document.profile == JsonProfile.Json5StandardV1 && version != 2)
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
        JsonSyntaxMatch(
            node = document.authority.nodeRef(ordinal.toLong(), NodeRole.JsonSyntaxPiece),
            span = piece.span,
            kind = kinds[ordinal],
            ordinal = ordinal,
        )
    }
    val matches = executeSyntaxExpression(definition.expression, input, context)
    return applySelection(matches, definition.selection)
}

/** Execution context carrying limits and cancellation (query.rs:196-228). */
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

    fun valueMatch(index: Int): JsonMatch {
        val value = JsonValue(document, index)
        return JsonMatch.Value(
            node = value.nodeRef(),
            kind = when (val kind = value.kind()) {
                is SemanticAvailability.Available -> kind.value
                is SemanticAvailability.Unavailable -> null
            },
            index = index,
        )
    }
}

private fun executeExpression(
    expression: QueryExpression,
    input: List<JsonMatch>,
    context: Context,
): List<JsonMatch> = when (expression.kind) {
    ExpressionKind.Input -> input
    ExpressionKind.Apply -> {
        val inner = executeExpression(expression.input!!, input, context)
        applyOperator(expression.operator!!, inner, context)
    }
    ExpressionKind.Concat -> {
        val output = ArrayList<JsonMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<JsonMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
        }
        output.sortWith(
            compareBy<JsonMatch> { context.document.entity(it.entityIndex()).span.startByte }
                .thenBy { context.document.entity(it.entityIndex()).span.endByte }
                .thenBy { it.entityIndex() },
        )
        context.step(output.size)
        output
    }
}

private fun executeSyntaxExpression(
    expression: QueryExpression,
    input: List<JsonSyntaxMatch>,
    context: Context,
): List<JsonSyntaxMatch> = when (expression.kind) {
    ExpressionKind.Input -> input
    ExpressionKind.Apply -> {
        val inner = executeSyntaxExpression(expression.input!!, input, context)
        applySyntaxOperator(expression.operator!!, inner, context)
    }
    ExpressionKind.Concat -> {
        val output = ArrayList<JsonSyntaxMatch>()
        for (branch in expression.branches) {
            output.addAll(executeSyntaxExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<JsonSyntaxMatch>()
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
    input: List<JsonSyntaxMatch>,
    context: Context,
): List<JsonSyntaxMatch> {
    val output: List<JsonSyntaxMatch> = when (operator.id) {
        "json.syntax-kind-is" -> {
            val expected = JsonSyntaxKind.fromName(
                operatorArgumentString(operator, "kind"),
            ) ?: error("kind name was validated before binding")
            input.filter { it.kind == expected }
        }
        "json.syntax-text-equals" -> {
            val expected = operatorArgumentString(operator, "text").toByteArray(Charsets.UTF_8)
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
        else -> error("validated JSON syntax operator")
    }
    context.step(output.size)
    return output
}

private fun applyOperator(
    operator: OperatorCall,
    input: List<JsonMatch>,
    context: Context,
): List<JsonMatch> {
    val output = ArrayList<JsonMatch>()
    when (operator.id) {
        "json.try-object-members" -> {
            for (item in input) {
                if (item is JsonMatch.Value) {
                    val index = item.index
                    val kind = context.document.valueEntity(index).kind
                    if (kind is InternalValueKind.Object) {
                        for (memberIndex in kind.members) {
                            val member = JsonObjectMember(context.document, memberIndex)
                            output.add(
                                JsonMatch.ObjectMember(
                                    ordinal = member.ordinal(),
                                    name = when (val name = member.name()) {
                                        is SemanticAvailability.Available -> name.value
                                        is SemanticAvailability.Unavailable -> null
                                    },
                                    key = member.keyNodeRef(),
                                    value = member.valueNodeRef(),
                                    member = member.nodeRef(),
                                    index = memberIndex,
                                ),
                            )
                        }
                    }
                }
            }
        }
        "json.member-name-equals" -> {
            val expected = operatorArgumentString(operator, "name")
            output.addAll(
                input.filter {
                    it is JsonMatch.ObjectMember && it.name == expected
                },
            )
        }
        "json.member-value" -> {
            for (item in input) {
                if (item is JsonMatch.ObjectMember) {
                    val index = context.document.validateRef(item.value, listOf(NodeRole.Value))
                    output.add(context.valueMatch(index))
                }
            }
        }
        "json.try-array-elements" -> {
            for (item in input) {
                if (item is JsonMatch.Value) {
                    val index = item.index
                    val kind = context.document.valueEntity(index).kind
                    if (kind is InternalValueKind.Array) {
                        for (elementIndex in kind.elements) {
                            val element = JsonArrayElement(context.document, elementIndex)
                            output.add(
                                JsonMatch.ArrayElement(
                                    ordinal = element.ordinal(),
                                    element = element.nodeRef(),
                                    value = element.valueNodeRef(),
                                    index = elementIndex,
                                ),
                            )
                        }
                    }
                }
            }
        }
        "json.array-element-value" -> {
            for (item in input) {
                if (item is JsonMatch.ArrayElement) {
                    val index = context.document.validateRef(item.value, listOf(NodeRole.Value))
                    output.add(context.valueMatch(index))
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
                if (seen.add(item.identity())) {
                    output.add(item)
                }
            }
        }
        else -> error("validated JSON operator")
    }
    context.step(output.size)
    return output
}

/** Applies the cardinality selection (query.rs:479-496). */
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

private fun operatorArgumentString(operator: OperatorCall, name: String): String =
    (operator.arguments[name] as? PvString)?.value ?: error("validated argument: $name")

private fun operatorArgumentInteger(operator: OperatorCall, name: String): BigInteger {
    val value = operator.arguments[name] ?: error("validated argument: $name")
    val integer = value as? consema.core.PvInteger ?: error("validated argument: $name")
    return integer.value
}
