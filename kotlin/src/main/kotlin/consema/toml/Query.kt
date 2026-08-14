// TOML native semantic and lossless syntax query execution.
//
// Data authority:
//   - RFC 0001 §4 (https://github.com/consema/consema/blob/main/docs/rfcs/0001-toml-1.0-profile.md): the frozen
//     domain `toml.native-semantic-query@1` with the six standard operators
//     (toml.try-table-entries@1, toml.entry-name-equals@1, toml.entry-item@1,
//     toml.try-array-elements@1, toml.array-element-item@1, plus
//     core.take@1 and core.distinct-by-identity@1); validation completes
//     before execution.
//   - https://github.com/consema/consema-rs/blob/main/consema-toml/src/query.rs (TomlMatch, TomlSyntaxMatch),
//     :88-180 (execute_toml_query / execute_toml_syntax_query and cursors),
//     :182-488 (Context::step with the frozen QueryLimits defaults
//     max_steps 100_000 / max_results 100_000, the expression evaluator,
//     the operator implementations, and apply_selection), and the domain
//     checks. The frozen failure codes are the registered core.query.*@1
//     codes of consema-protocol (QueryFailureException).
//   - conformance/vectors/syntax-query-v1.json syntax.toml.* cases (lines
//) pin the match facts (kind, text, ordinal, TomlSyntaxPiece
//     role) and the failure codes.
//   - consema-go/go/toml/query.go is a cross-reference only.
//
// Kotlin-idiomatic design: definition validation and capability binding
// belong to consema.protocol (QueryValidate.kt); this module executes a
// bound [ExecutableQuery] against one immutable snapshot. The execution
// support types (limits, cancellation, result, cursor) are family-owned
// stand-ins for the consema.protocol types that land with the L0/L4
// milestones; the signatures migrate onto them unchanged.

package consema.toml

import consema.core.PvInteger
import consema.core.PvString
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.Span
import consema.protocol.ExecutableQuery
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind
import consema.protocol.QuerySelection
import java.util.concurrent.atomic.AtomicBoolean

/** Owned snapshot-bound TOML native semantic query match (query.rs). */
sealed class TomlMatch {
    /** TOML native item. */
    data class Item(
        /** Exact item identity. */
        val node: NodeRef,
        /** Native item category. */
        val kind: TomlItemKind,
    ) : TomlMatch()

    /** Ordered table or inline-table entry. */
    data class Entry(
        /** Zero-based direct entry ordinal. */
        val ordinal: Int,
        /** Decoded direct key segment. */
        val name: String,
        /** Key identity. */
        val key: NodeRef,
        /** Associated item identity. */
        val item: NodeRef,
        /** Association identity. */
        val entry: NodeRef,
    ) : TomlMatch()

    /** Ordered array or array-of-tables element. */
    data class ArrayElement(
        /** Zero-based direct element ordinal. */
        val ordinal: Int,
        /** Association identity. */
        val element: NodeRef,
        /** Associated item identity. */
        val item: NodeRef,
    ) : TomlMatch()

    internal fun identity(): NodeRef = when (this) {
        is Item -> node
        is Entry -> entry
        is ArrayElement -> element
    }
}

/** Owned snapshot-bound TOML lossless syntax query match (query.rs). */
data class TomlSyntaxMatch(
    /** Process-local syntax-piece identity. */
    val nodeRef: NodeRef,
    /** Exact raw source span. */
    val span: Span,
    /** Format-specific lossless kind. */
    val kind: TomlSyntaxKind,
    /** Zero-based source-order position. */
    val ordinal: Int,
)

/** Query execution resource limits (query.rs: the frozen
 * defaults are 100,000 steps and 100,000 results). */
data class TomlQueryLimits(
    /** Maximum evaluation steps. */
    val maxSteps: Int,
    /** Maximum intermediate and final result count. */
    val maxResults: Int,
) {
    companion object {
        /** The frozen defaults (query.rs). */
        val default = TomlQueryLimits(maxSteps = 100_000, maxResults = 100_000)
    }
}

/** Cooperative execution cancellation (query.rs). */
class TomlCancellationToken {
    private val cancelled = AtomicBoolean(false)

    /** Requests cancellation. */
    fun cancel() {
        cancelled.set(true)
    }

    /** Whether cancellation was requested. */
    fun isCancelled(): Boolean = cancelled.get()
}

/** The terminal status of a completed query execution (the Rust
 * TerminalStatus). */
enum class TomlQueryTerminal {
    /** All matches were produced. */
    Completed,

    /** Execution was cancelled. */
    Cancelled,

    /** Execution failed. */
    Failed,
}

/** The complete result of one query execution (query.rs). */
class TomlQueryExecution<T> private constructor(
    private val matches: List<T>,
    val terminal: TomlQueryTerminal,
) {
    companion object {
        /** Creates a completed execution. */
        fun <T> completed(matches: List<T>): TomlQueryExecution<T> =
            TomlQueryExecution(matches, TomlQueryTerminal.Completed)
    }

    /** Deterministic ordered matches. */
    fun matches(): List<T> = matches
}

/** Ordered query-result cursor with deterministic yields (query.rs).
 * The conformance surface is the terminal behavior. */
class TomlOrderedQueryCursor<T> private constructor(
    private val values: List<T>,
    private val cancellation: TomlCancellationToken?,
) {
    companion object {
        /** Creates a cursor over the complete ordered result. */
        fun <T> new(values: List<T>): TomlOrderedQueryCursor<T> =
            TomlOrderedQueryCursor(values, null)

        /** Creates a cursor that yields remaining values until cancellation. */
        fun <T> withCancellation(
            values: List<T>,
            cancellation: TomlCancellationToken,
        ): TomlOrderedQueryCursor<T> = TomlOrderedQueryCursor(values, cancellation)
    }

    /** Yields the deterministic remaining matches. */
    fun yieldAll(): List<T> {
        if (cancellation != null && cancellation.isCancelled()) {
            return values.take(1)
        }
        return values
    }
}

/** Executes a validated TOML native semantic query against one immutable
 * snapshot (query.rs). The domain must be
 * `toml.native-semantic-query@1`; violations throw the frozen
 * core.query.domain-mismatch@1. */
fun executeTomlQuery(
    executable: ExecutableQuery,
    document: TomlDocument,
    limits: TomlQueryLimits,
    cancellation: TomlCancellationToken,
): TomlQueryExecution<TomlMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "toml.native-semantic-query" || definition.domain.version != 1) {
        throw QueryFailureException(
            QueryFailureKind.DOMAIN_MISMATCH,
            domain = definition.domain,
        )
    }
    val context = TomlQueryContext(document, limits, cancellation)
    context.step(0)
    val input = listOf(context.itemMatch(document.rootIndex))
    val matches = executeExpression(definition.expression, input, context)
    val selected = applySelection(matches, definition.selection)
    return TomlQueryExecution.completed(selected)
}

/** Executes a TOML lossless syntax query against every source piece in raw
 * order (query.rs). The domain must be
 * `toml.lossless-syntax-query@1`. */
fun executeTomlSyntaxQuery(
    executable: ExecutableQuery,
    document: TomlDocument,
    limits: TomlQueryLimits,
    cancellation: TomlCancellationToken,
): TomlQueryExecution<TomlSyntaxMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "toml.lossless-syntax-query" || definition.domain.version != 1) {
        throw QueryFailureException(
            QueryFailureKind.DOMAIN_MISMATCH,
            domain = definition.domain,
        )
    }
    val context = TomlQueryContext(document, limits, cancellation)
    val pieces = document.structuralIndex.pieces()
    context.step(pieces.size)
    val input = pieces.indices.map { ordinal ->
        val piece = pieces[ordinal]
        TomlSyntaxMatch(
            nodeRef = document.authority.nodeRef(ordinal.toLong(), NodeRole.TomlSyntaxPiece),
            span = piece.span,
            kind = document.syntaxKinds[ordinal],
            ordinal = ordinal,
        )
    }
    val matches = executeSyntaxExpression(definition.expression, input, context)
    val selected = applySelection(matches, definition.selection)
    return TomlQueryExecution.completed(selected)
}

/** The shared execution context: step/results limits and cancellation
 * (query.rs). */
internal class TomlQueryContext(
    internal val document: TomlDocument,
    private val limits: TomlQueryLimits,
    private val cancellation: TomlCancellationToken,
) {
    private var steps = 0

    fun step(results: Int) {
        if (cancellation.isCancelled()) {
            throw QueryFailureException(QueryFailureKind.CANCELLED)
        }
        steps += 1
        if (steps > limits.maxSteps || results > limits.maxResults) {
            throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
        }
    }

    fun itemMatch(index: Int): TomlMatch =
        TomlMatch.Item(document.nodeRef(index, NodeRole.TomlItem), document.itemEntity(index).kind.publicKind())

    fun itemKind(index: Int): TomlItemKind = document.itemEntity(index).kind.publicKind()

    fun entryAt(index: Int): TomlEntry = TomlEntry(document, index)

    fun elementEntity(index: Int): ElementEntity =
        (document.entity(index).kind as? EntityKind.Element)?.element
            ?: error("typed TOML element")
}

/** Evaluates one native expression tree (query.rs). */
internal fun executeExpression(
    expression: consema.protocol.QueryExpression,
    input: List<TomlMatch>,
    context: TomlQueryContext,
): List<TomlMatch> = when (expression.kind) {
    consema.protocol.ExpressionKind.Input -> input
    consema.protocol.ExpressionKind.Apply -> {
        val evaluated = executeExpression(expression.input!!, input, context)
        applyOperator(expression.operator!!, evaluated, context)
    }
    consema.protocol.ExpressionKind.Concat -> {
        val output = ArrayList<TomlMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    consema.protocol.ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<TomlMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
        }
        output.sortWith(
            compareBy<TomlMatch> {
                val index = documentIndex(context, it)
                context.document.entity(index).span.startByte
            }.thenBy {
                val index = documentIndex(context, it)
                context.document.entity(index).span.endByte
            }.thenBy {
                documentIndex(context, it)
            },
        )
        context.step(output.size)
        output
    }
}

private fun documentIndex(context: TomlQueryContext, match: TomlMatch): Int =
    context.document.validateRef(match.identity(), match.identity().role)

/** Evaluates one lossless syntax expression tree (query.rs). */
internal fun executeSyntaxExpression(
    expression: consema.protocol.QueryExpression,
    input: List<TomlSyntaxMatch>,
    context: TomlQueryContext,
): List<TomlSyntaxMatch> = when (expression.kind) {
    consema.protocol.ExpressionKind.Input -> input
    consema.protocol.ExpressionKind.Apply -> {
        val evaluated = executeSyntaxExpression(expression.input!!, input, context)
        applySyntaxOperator(expression.operator!!, evaluated, context)
    }
    consema.protocol.ExpressionKind.Concat -> {
        val output = ArrayList<TomlSyntaxMatch>()
        for (branch in expression.branches) {
            output.addAll(executeSyntaxExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    consema.protocol.ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<TomlSyntaxMatch>()
        for (branch in expression.branches) {
            output.addAll(executeSyntaxExpression(branch, input, context))
        }
        output.sortBy { it.ordinal }
        context.step(output.size)
        output
    }
}

/** Applies one validated native operator (query.rs). */
internal fun applyOperator(
    operator: consema.protocol.OperatorCall,
    input: List<TomlMatch>,
    context: TomlQueryContext,
): List<TomlMatch> {
    val output = ArrayList<TomlMatch>()
    when (operator.id) {
        "toml.try-table-entries" -> {
            for (match in input) {
                if (match !is TomlMatch.Item) {
                    continue
                }
                val index = context.document.validateRef(match.node, NodeRole.TomlItem)
                val kind = context.document.itemEntity(index).kind
                val entries = when (kind) {
                    is InternalItemKind.Table -> kind.entries
                    is InternalItemKind.InlineTable -> kind.entries
                    else -> continue
                }
                for (entryIndex in entries) {
                    val entry = context.entryAt(entryIndex)
                    output.add(
                        TomlMatch.Entry(
                            ordinal = entry.ordinal,
                            name = entry.name(),
                            key = entry.keyNodeRef,
                            item = entry.itemNodeRef,
                            entry = entry.nodeRef,
                        ),
                    )
                }
            }
        }
        "toml.entry-name-equals" -> {
            val expected = (operator.arguments["name"] as PvString).value
            output.addAll(
                input.filter { it is TomlMatch.Entry && it.name == expected },
            )
        }
        "toml.entry-item" -> {
            for (match in input) {
                if (match is TomlMatch.Entry) {
                    val index = context.document.validateRef(match.item, NodeRole.TomlItem)
                    output.add(context.itemMatch(index))
                }
            }
        }
        "toml.try-array-elements" -> {
            for (match in input) {
                if (match !is TomlMatch.Item) {
                    continue
                }
                val index = context.document.validateRef(match.node, NodeRole.TomlItem)
                val kind = context.document.itemEntity(index).kind
                val elements = when (kind) {
                    is InternalItemKind.Array -> kind.elements
                    is InternalItemKind.ArrayOfTables -> kind.elements
                    else -> continue
                }
                for (elementIndex in elements) {
                    val element = context.elementEntity(elementIndex)
                    output.add(
                        TomlMatch.ArrayElement(
                            ordinal = element.ordinal,
                            element = context.document.nodeRef(elementIndex, NodeRole.TomlArrayElement),
                            item = context.document.nodeRef(element.item, NodeRole.TomlItem),
                        ),
                    )
                }
            }
        }
        "toml.array-element-item" -> {
            for (match in input) {
                if (match is TomlMatch.ArrayElement) {
                    val index = context.document.validateRef(match.item, NodeRole.TomlItem)
                    output.add(context.itemMatch(index))
                }
            }
        }
        "core.take" -> {
            val count = (operator.arguments["count"] as PvInteger).value.toInt()
            output.addAll(input.take(count))
        }
        "core.distinct-by-identity" -> {
            val seen = HashSet<NodeRef>()
            for (match in input) {
                if (seen.add(match.identity())) {
                    output.add(match)
                }
            }
        }
        else -> error("validated TOML operator")
    }
    context.step(output.size)
    return output
}

/** Applies one validated lossless syntax operator (query.rs). */
internal fun applySyntaxOperator(
    operator: consema.protocol.OperatorCall,
    input: List<TomlSyntaxMatch>,
    context: TomlQueryContext,
): List<TomlSyntaxMatch> {
    val output = ArrayList<TomlSyntaxMatch>()
    when (operator.id) {
        "toml.syntax-kind-is" -> {
            val expected = TomlSyntaxKind.fromName((operator.arguments["kind"] as PvString).value)
                ?: error("kind name was validated before binding")
            output.addAll(input.filter { it.kind == expected })
        }
        "toml.syntax-text-equals" -> {
            val expected = (operator.arguments["text"] as PvString).value
                .toByteArray(Charsets.UTF_8)
            output.addAll(
                input.filter { item ->
                    context.document.source.rawBytes()
                        .copyOfRange(item.span.startByte, item.span.endByte)
                        .contentEquals(expected)
                },
            )
        }
        "core.take" -> {
            val count = (operator.arguments["count"] as PvInteger).value.toInt()
            output.addAll(input.take(count))
        }
        "core.distinct-by-identity" -> {
            val seen = HashSet<NodeRef>()
            for (match in input) {
                if (seen.add(match.nodeRef)) {
                    output.add(match)
                }
            }
        }
        else -> error("validated TOML syntax operator")
    }
    context.step(output.size)
    return output
}

/** Applies the cardinality selection to the complete standard result
 * (query.rs). */
internal fun <T> applySelection(values: List<T>, selection: QuerySelection): List<T> =
    when (selection) {
        QuerySelection.All -> values
        QuerySelection.First -> values.take(1)
        QuerySelection.Last -> values.takeLast(1)
        QuerySelection.ZeroOrOne -> {
            if (values.size <= 1) {
                values
            } else {
                throw QueryFailureException(
                    QueryFailureKind.CARDINALITY_VIOLATION,
                    argument = selection.wireName,
                    expectedKind = "at most one",
                    expectedRole = values.size.toString(),
                )
            }
        }
        QuerySelection.RequireOne -> {
            if (values.size == 1) {
                values
            } else {
                throw QueryFailureException(
                    QueryFailureKind.CARDINALITY_VIOLATION,
                    argument = selection.wireName,
                    expectedKind = "exactly one",
                    expectedRole = values.size.toString(),
                )
            }
        }
    }
