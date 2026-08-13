// The conformance-runner portable-value query executor.
//
// Data authority (language-neutral sources first):
//   - consema-rs/consema-conformance/src/lib.rs:641-711 (query.root-result-limit
//     and query.cursor-failure-terminal handlers) pins the exact behaviors
//     this executor reproduces: the bare Input expression yields the root
//     value as one match; `core.try-sequence-elements@1` yields the elements
//     of a Sequence and nothing for other kinds; a completed execution whose
//     match count exceeds `max_results` fails with
//     `core.query.resource-limit@1`; the ordered cursor yields matches up to
//     the limit and then fails with the terminal state Failed.
//   - conformance/vectors/v1.json (query.root-result-limit: max_results 0
//     over the bare Input fails; query.cursor-failure-terminal: five
//     elements with max_results 3 yield 3 then fail) and
//     conformance/vectors/syntax-query-v1.json (syntax.cursor.* cases pin
//     the OrderedQueryCursor terminal semantics: Completed yields all,
//     Cancelled yields one before cancellation, Failed yields all with the
//     Failed terminal).
//   - consema-core/src/query.rs (the OrderedQueryCursor and the
//     execute_portable semantics) is the implementation authority.
//
// Kotlin-idiomatic design: a small deterministic executor in the
// conformance package (the Kotlin family packages execute queries against
// documents, not bare PortableValues); the failures use the shared
// QueryFailureException so the frozen codes stay identical.

package consema.conformance

import consema.core.PvArray
import consema.core.PortableValue
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind
import consema.protocol.QuerySelection

/** Query execution resource limits (the frozen defaults mirror the family
 * QueryLimits defaults, query.rs:2967-2993). */
data class PortableQueryLimits(
    /** Maximum evaluation steps. */
    val maxSteps: Int,
    /** Maximum final result count. */
    val maxResults: Int,
) {
    companion object {
        /** The frozen defaults: 100,000 steps and 100,000 results. */
        val default = PortableQueryLimits(maxSteps = 100_000, maxResults = 100_000)
    }
}

/**
 * Evaluates one validated portable-value query over one complete value and
 * returns the ordered final matches (the non-cursor execution path,
 * execute_portable). A final match count above `max_results` fails with the
 * frozen resource-limit code; the root match of the bare Input expression
 * counts as a result (v1.json query.root-result-limit).
 */
fun executePortableQuery(
    executable: ExecutableQuery,
    value: PortableValue,
    limits: PortableQueryLimits,
): List<PortableValue> {
    val expression = executable.validated.definition.expression
    val matches = evaluateExpression(expression, listOf(value), limits)
    if (matches.size > limits.maxResults) {
        throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
    }
    return applySelection(matches, executable.validated.definition.selection)
}

/** The terminal state of one ordered cursor (query.rs:3008-3046). */
enum class PortableTerminalState {
    /** All matches were produced. */
    Completed,

    /** Execution was cancelled. */
    Cancelled,

    /** Execution failed. */
    Failed,
}

/**
 * The ordered query-result cursor over one portable execution: yields the
 * deterministic matches one at a time; when the `max_results` limit would be
 * exceeded the next match fails once with the resource-limit code and the
 * terminal state becomes Failed (v1.json query.cursor-failure-terminal).
 */
class PortableQueryCursor internal constructor(
    private val matches: List<PortableValue>,
    private val limits: PortableQueryLimits,
    private var terminal: PortableTerminalState = PortableTerminalState.Completed,
) {
    private var index = 0

    /** Yields the next match, or null at the end; a limit failure is
     * reported as [QueryFailureException] with the Failed terminal. */
    fun nextMatch(): PortableValue? {
        if (index >= matches.size) {
            return null
        }
        if (index >= limits.maxResults) {
            terminal = PortableTerminalState.Failed
            throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
        }
        val match = matches[index]
        index += 1
        return match
    }

    /** The terminal state after the stream ended or failed. */
    fun terminalState(): PortableTerminalState = terminal
}

/** Builds the ordered cursor of one portable execution. */
fun executePortableCursor(
    executable: ExecutableQuery,
    value: PortableValue,
    limits: PortableQueryLimits,
): PortableQueryCursor {
    val expression = executable.validated.definition.expression
    val matches = evaluateExpression(expression, listOf(value), limits)
    return PortableQueryCursor(matches, limits)
}

private fun evaluateExpression(
    expression: consema.protocol.QueryExpression,
    input: List<PortableValue>,
    limits: PortableQueryLimits,
): List<PortableValue> {
    var matches = input
    var steps = 0
    var current = expression
    while (current.kind == ExpressionKind.Apply) {
        steps += 1
        if (steps > limits.maxSteps) {
            throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
        }
        val operator = current.operator!!
        val next = ArrayList<PortableValue>()
        for (match in matches) {
            next.addAll(applyOperator(operator.id, match))
        }
        matches = next
        current = current.input!!
    }
    if (current.kind != ExpressionKind.Input) {
        throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "expression")
    }
    return matches
}

private fun applyOperator(id: String, match: PortableValue): List<PortableValue> =
    when (id) {
        "core.try-sequence-elements" ->
            (match as? PvArray)?.items() ?: emptyList()
        else -> throw QueryFailureException(QueryFailureKind.UNKNOWN_OPERATOR, operator = id, version = 1)
    }

private fun applySelection(matches: List<PortableValue>, selection: QuerySelection): List<PortableValue> =
    when (selection) {
        QuerySelection.All -> matches
        QuerySelection.First -> if (matches.isEmpty()) emptyList() else listOf(matches.first())
        QuerySelection.Last -> if (matches.isEmpty()) emptyList() else listOf(matches.last())
        QuerySelection.ZeroOrOne -> if (matches.size <= 1) matches else emptyList()
        QuerySelection.RequireOne ->
            if (matches.size == 1) {
                matches
            } else {
                throw QueryFailureException(
                    QueryFailureKind.CARDINALITY_VIOLATION,
                    argument = "selection",
                )
            }
    }

/** The conformance `OrderedQueryCursor` of the syntax-query suite
 * (syntax.cursor.* cases): the deterministic ordered cursor over one
 * complete result list with explicit terminal modes. */
class OrderedQueryCursor private constructor(
    private val values: List<PortableValue>,
    private val cancellation: CancellationFlag?,
    private val terminal: PortableTerminalState,
) {
    private var index = 0

    companion object {
        /** A completed cursor over the complete ordered result. */
        fun new(values: List<PortableValue>): OrderedQueryCursor =
            OrderedQueryCursor(values, null, PortableTerminalState.Completed)

        /** A cursor that yields one value before cancellation. */
        fun withCancellation(
            values: List<PortableValue>,
            cancellation: CancellationFlag,
        ): OrderedQueryCursor = OrderedQueryCursor(values, cancellation, PortableTerminalState.Completed)

        /** A cursor pre-seeded with a Failed terminal. */
        fun withTerminal(
            values: List<PortableValue>,
            terminal: PortableTerminalState,
        ): OrderedQueryCursor = OrderedQueryCursor(values, null, terminal)
    }

    /** Yields the next deterministic value, or null at the end. */
    fun next(): PortableValue? {
        if (index >= values.size) {
            return null
        }
        if (cancellation != null) {
            if (cancellation.cancelled) {
                return null
            }
            cancellation.cancelled = true
        }
        val value = values[index]
        index += 1
        return value
    }

    /** The terminal state after the stream ended or was cancelled. */
    fun terminalState(): PortableTerminalState? = terminal
}

/** A simple cancellation flag. */
class CancellationFlag {
    var cancelled: Boolean = false
        internal set

    /** Requests cancellation. */
    fun cancel() {
        cancelled = true
    }
}
