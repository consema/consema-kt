// Versioned INI native-semantic and lossless-syntax query execution.
//
// Data authority:
//   - RFC 0009 §9 (docs/rfcs/0009-ini-family-profiles-v1.md:286-345): the
//     native operator schemas (ini.document-sections@1 through
//     ini.logical-lines@1), the exact comparison modes OriginalExact |
//     ProfileEquivalent and the exact state values Missing | Empty |
//     Present, duplicate-group expansion semantics, and the syntax
//     operators ini.syntax-kind-is@1 / ini.syntax-text-equals@1 with text
//     comparison over the decoded Unicode scalar text of the exact piece
//     span; domain/operator/parameter/role validation happens before the
//     first result; ordered selection, Concat, StructureOrderMerge, limits,
//     cancellation, and terminal-state rules apply.
//   - conformance/vectors/ini-v1.json (query.*) pins the match order, the
//     duplicate-group facts, the syntax-kind ordinals, and the
//     resource-limit behavior; crates/consema-ini/src/query.rs is the
//     byte-arbitration authority (execution query.rs:117-218, operators
//     query.rs:421-625, source order query.rs:627-659, decoded text
//     query.rs:661-676); consema-core/src/query.rs:2967-2993 pins QueryLimits
//     defaults and the CancellationToken shape.
//   - The operator table and argument vocabularies live in the protocol
//     package (kotlin/.../protocol/QueryValidate.kt:108-137, 721-730,
//     827-846, 929-935) and validate INI queries before execution.
//
// Kotlin-idiomatic design: execution throws the protocol package's typed
// [consema.protocol.QueryFailureException] carrying the registered code
// (query_failure_code mapping, error_registry.rs:1515-1529); the cursor
// terminal contract (RFC 0003 §9) is a synchronous complete result list
// here, with the terminal state always Completed after a successful
// execution.

package consema.ini

import consema.core.PvInteger
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

/** Owned snapshot-bound INI native semantic query match (query.rs:9-67). */
sealed class IniMatch {
    /** Complete INI document. */
    data class Document(
        /** Root document identity. */
        val node: NodeRef,
    ) : IniMatch()

    /** One distinct section occurrence. */
    data class Section(
        /** Zero-based source-order section ordinal. */
        val ordinal: Int,
        /** Section occurrence identity. */
        val node: NodeRef,
        /** Original section name. */
        val name: String,
        /** Profile comparison name. */
        val comparisonName: String,
        /** Whether this is Python's exact default section. */
        val isDefault: Boolean,
        /** Duplicate/case-equivalence group, when present. */
        val duplicateGroup: Int?,
    ) : IniMatch()

    /** One distinct entry occurrence. */
    data class Entry(
        /** Zero-based source-order entry ordinal. */
        val ordinal: Int,
        /** Entry occurrence identity. */
        val node: NodeRef,
        /** Owning section occurrence. */
        val section: NodeRef,
        /** Original key spelling. */
        val key: String,
        /** Profile comparison key. */
        val comparisonKey: String,
        /** Missing, empty, or present value fact. */
        val valueState: IniValueState,
        /** Duplicate/case-equivalence group, when present. */
        val duplicateGroup: Int?,
    ) : IniMatch()

    /** One exact physical source line. */
    data class PhysicalLine(
        /** Zero-based source-order physical-line ordinal. */
        val ordinal: Int,
        /** Physical-line identity. */
        val node: NodeRef,
        /** Complete raw line span including its line break. */
        val span: Span,
    ) : IniMatch()

    /** One logical INI record. */
    data class LogicalLine(
        /** Zero-based logical-record ordinal. */
        val ordinal: Int,
        /** Logical-line identity. */
        val node: NodeRef,
        /** Logical record kind. */
        val kind: IniLogicalLineKind,
    ) : IniMatch()

    internal fun identity(): NodeRef =
        when (this) {
            is Document -> node
            is Section -> node
            is Entry -> node
            is PhysicalLine -> node
            is LogicalLine -> node
        }
}

/** Owned snapshot-bound INI lossless syntax query match (query.rs:81-114). */
data class IniSyntaxMatch(
    /** Process-local syntax-piece identity. */
    val node: NodeRef,
    /** Exact raw source span. */
    val span: Span,
    /** Format-specific lossless kind. */
    val kind: IniSyntaxKind,
    /** Zero-based source-order position. */
    val ordinal: Int,
)

/**
 * Executes a validated INI native semantic query against one immutable
 * snapshot (query.rs:117-143). The document is the first standard result.
 */
fun executeIniQuery(
    executable: ExecutableQuery,
    document: IniDocument,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): List<IniMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "ini.native-semantic-query" ||
        definition.domain.version != 1
    ) {
        throw QueryFailureException(
            QueryFailureKind.DOMAIN_MISMATCH,
            domain = definition.domain,
        )
    }
    val context = Context(document, limits, cancellation)
    context.step(1)
    val input = listOf(IniMatch.Document(document.nodeRef()))
    val matches = executeExpression(definition.expression, input, context)
    return applySelection(matches, definition.selection)
}

/**
 * Executes a validated INI lossless syntax query against every source piece
 * in raw order (query.rs:159-204). Text comparisons use the decoded Unicode
 * scalar text of the exact piece span (RFC 0009 §9).
 */
fun executeIniSyntaxQuery(
    executable: ExecutableQuery,
    document: IniDocument,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): List<IniSyntaxMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "ini.lossless-syntax-query" ||
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
        IniSyntaxMatch(
            node = document.authority.nodeRef(ordinal.toLong(), NodeRole.IniSyntaxPiece),
            span = piece.span,
            kind = kinds[ordinal],
            ordinal = ordinal,
        )
    }
    val matches = executeSyntaxExpression(definition.expression, input, context)
    return applySelection(matches, definition.selection)
}

/** Execution context carrying limits and cancellation (query.rs:220-296). */
private class Context(
    val document: IniDocument,
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

    fun sectionMatch(ordinal: Int): IniMatch {
        val section = document.sectionsList[ordinal]
        return IniMatch.Section(
            ordinal = ordinal,
            node = section.nodeRef,
            name = section.name,
            comparisonName = section.comparisonName,
            isDefault = section.isDefault,
            duplicateGroup = section.duplicateGroup,
        )
    }

    fun entryMatch(ordinal: Int): IniMatch {
        val entry = document.entriesList[ordinal]
        return IniMatch.Entry(
            ordinal = ordinal,
            node = entry.nodeRef,
            section = entry.section,
            key = entry.key,
            comparisonKey = entry.comparisonKey,
            valueState = entry.valueState,
            duplicateGroup = entry.duplicateGroup,
        )
    }
}

private fun executeExpression(
    expression: QueryExpression,
    input: List<IniMatch>,
    context: Context,
): List<IniMatch> = when (expression.kind) {
    ExpressionKind.Input -> input
    ExpressionKind.Apply -> {
        val inner = executeExpression(expression.input!!, input, context)
        applyOperator(expression.operator!!, inner, context)
    }
    ExpressionKind.Concat -> {
        val output = ArrayList<IniMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<IniMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
        }
        output.sortWith(
            compareBy<IniMatch> { sourceOrder(context.document, it).first }
                .thenBy { sourceOrder(context.document, it).second },
        )
        context.step(output.size)
        output
    }
}

private fun executeSyntaxExpression(
    expression: QueryExpression,
    input: List<IniSyntaxMatch>,
    context: Context,
): List<IniSyntaxMatch> = when (expression.kind) {
    ExpressionKind.Input -> input
    ExpressionKind.Apply -> {
        val inner = executeSyntaxExpression(expression.input!!, input, context)
        applySyntaxOperator(expression.operator!!, inner, context)
    }
    ExpressionKind.Concat -> {
        val output = ArrayList<IniSyntaxMatch>()
        for (branch in expression.branches) {
            output.addAll(executeSyntaxExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<IniSyntaxMatch>()
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
    input: List<IniSyntaxMatch>,
    context: Context,
): List<IniSyntaxMatch> {
    val output: List<IniSyntaxMatch> = when (operator.id) {
        "ini.syntax-kind-is" -> {
            val expected = IniSyntaxKind.fromName(
                operatorArgumentString(operator, "kind"),
            ) ?: error("kind name was validated before binding")
            input.filter { it.kind == expected }
        }
        "ini.syntax-text-equals" -> {
            val expected = operatorArgumentString(operator, "text")
            input.filter { item ->
                context.document.sourceSnapshot.decodedTextBetween(item.span.startByte, item.span.endByte) == expected
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
        else -> error("validated INI syntax operator")
    }
    context.step(output.size)
    return output
}

private fun applyOperator(
    operator: OperatorCall,
    input: List<IniMatch>,
    context: Context,
): List<IniMatch> {
    val output = ArrayList<IniMatch>()
    when (operator.id) {
        "ini.document-sections" -> {
            for (item in input) {
                if (item is IniMatch.Document) {
                    for (ordinal in context.document.sectionsList.indices) {
                        pushMatch(context, output, context.sectionMatch(ordinal))
                    }
                }
            }
        }
        "ini.section-entries" -> {
            for (item in input) {
                if (item is IniMatch.Section) {
                    for ((ordinal, entry) in context.document.entriesList.withIndex()) {
                        if (entry.section == item.node) {
                            pushMatch(context, output, context.entryMatch(ordinal))
                        }
                    }
                }
            }
        }
        "ini.all-entries" -> {
            for (item in input) {
                if (item is IniMatch.Document) {
                    for (ordinal in context.document.entriesList.indices) {
                        pushMatch(context, output, context.entryMatch(ordinal))
                    }
                }
            }
        }
        "ini.entry-section" -> {
            for (item in input) {
                if (item is IniMatch.Entry) {
                    val ordinal = context.document.sectionsList
                        .indexOfFirst { it.nodeRef == item.section }
                    if (ordinal >= 0) {
                        pushMatch(context, output, context.sectionMatch(ordinal))
                    }
                }
            }
        }
        "ini.section-name-equals" -> {
            val expected = operatorArgumentString(operator, "name")
            val comparison = operatorArgumentString(operator, "comparison")
            val equivalent = sectionComparisonName(context.document.profile, expected)
            for (item in input) {
                val matches = when (item) {
                    is IniMatch.Section -> {
                        if (comparison == "OriginalExact") {
                            item.name == expected
                        } else {
                            item.comparisonName == equivalent
                        }
                    }
                    else -> false
                }
                if (matches) {
                    pushMatch(context, output, item)
                }
            }
        }
        "ini.entry-key-equals" -> {
            val expected = operatorArgumentString(operator, "key")
            val comparison = operatorArgumentString(operator, "comparison")
            val equivalent = keyComparisonName(context.document.profile, expected)
            for (item in input) {
                val matches = when (item) {
                    is IniMatch.Entry -> {
                        if (comparison == "OriginalExact") {
                            item.key == expected
                        } else {
                            item.comparisonKey == equivalent
                        }
                    }
                    else -> false
                }
                if (matches) {
                    pushMatch(context, output, item)
                }
            }
        }
        "ini.entry-value-state-is" -> {
            val expected = when (operatorArgumentString(operator, "state")) {
                "Missing" -> IniValueState.Missing
                "Empty" -> IniValueState.Empty
                "Present" -> IniValueState.Present
                else -> error("state was validated before binding")
            }
            for (item in input) {
                if (item is IniMatch.Entry && item.valueState == expected) {
                    pushMatch(context, output, item)
                }
            }
        }
        "ini.duplicate-group" -> {
            for (item in input) {
                when (item) {
                    is IniMatch.Section -> {
                        val group = item.duplicateGroup ?: continue
                        for (ordinal in context.document.sectionsList.indices) {
                            if (context.document.sectionsList[ordinal].duplicateGroup == group) {
                                pushMatch(context, output, context.sectionMatch(ordinal))
                            }
                        }
                    }
                    is IniMatch.Entry -> {
                        val group = item.duplicateGroup ?: continue
                        for (ordinal in context.document.entriesList.indices) {
                            if (context.document.entriesList[ordinal].duplicateGroup == group) {
                                pushMatch(context, output, context.entryMatch(ordinal))
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        "ini.physical-lines" -> {
            for (item in input) {
                if (item is IniMatch.Document) {
                    for ((ordinal, line) in context.document.physicalLinesList.withIndex()) {
                        pushMatch(
                            context,
                            output,
                            IniMatch.PhysicalLine(ordinal, line.nodeRef, line.span),
                        )
                    }
                }
            }
        }
        "ini.logical-lines" -> {
            for (item in input) {
                if (item is IniMatch.Document) {
                    for ((ordinal, line) in context.document.logicalLinesList.withIndex()) {
                        pushMatch(
                            context,
                            output,
                            IniMatch.LogicalLine(ordinal, line.nodeRef, line.kind),
                        )
                    }
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
        else -> error("validated INI operator")
    }
    context.step(output.size)
    return output
}

/** Push with the buffered-result limit (query.rs:242-255). */
private fun pushMatch(context: Context, output: ArrayList<IniMatch>, match: IniMatch) {
    val observed = output.size + 1
    if (observed > context.limits.maxResults) {
        throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
    }
    output.add(match)
}

/** The stable source-order key of one native match (query.rs:627-659). */
private fun sourceOrder(document: IniDocument, item: IniMatch): Pair<Int, Int> =
    when (item) {
        is IniMatch.Document -> 0 to 0
        is IniMatch.Section -> document.sectionsList[item.ordinal].span.startByte to item.ordinal
        is IniMatch.Entry -> document.entriesList[item.ordinal].span.startByte to item.ordinal
        is IniMatch.PhysicalLine -> item.span.startByte to item.ordinal
        is IniMatch.LogicalLine -> {
            val logical = document.logicalLinesList[item.ordinal]
            val start = logical.physicalLines.firstOrNull()?.let { first ->
                try {
                    document.physicalLine(first).span.startByte
                } catch (e: IniAccessException) {
                    0
                }
            } ?: 0
            start to item.ordinal
        }
    }

private fun sectionComparisonName(profile: IniProfile, name: String): String =
    when (profile) {
        IniProfile.WindowsV1 -> asciiLowercase(name)
        IniProfile.PortableV1, IniProfile.PythonConfigParserV1 -> name
    }

private fun keyComparisonName(profile: IniProfile, key: String): String =
    when (profile) {
        IniProfile.PortableV1 -> key
        IniProfile.WindowsV1 -> asciiLowercase(key)
        IniProfile.PythonConfigParserV1 -> optionxform(key)
    }

/** Applies the cardinality selection (query.rs:693-710). */
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
    val integer = value as? PvInteger ?: error("validated argument: $name")
    return integer.value
}
