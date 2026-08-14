// Versioned Java Properties native-semantic and lossless-syntax query
// execution.
//
// Data authority:
//   - RFC 0010 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0010-java-properties-profiles-v1.md:269-308):
//     java-properties.native-semantic-query@1 operators
//     (document-properties, natural-lines, logical-lines,
//     logical-line-natural-lines, property-key-equals, property-value-state-
//     is, property-escapes, duplicate-group) and java-properties.lossless-
//     syntax-query@1 filters (syntax-kind-is, syntax-text-equals,
//     syntax-raw-bytes-equals, syntax-utf16be-equals); key matching takes
//     exact UTF-16 code units encoded as UTF16BE/1 and never normalizes;
//     decoded-text matching is available only for well-formed Unicode pieces.
//   - conformance/vectors/java-properties-v1.json pins the match order,
//     duplicate/escape counts, UTF16BE hex keys, ordinals, and the
//     cancellation/limit outcomes.
//   - https://github.com/consema/consema-rs/blob/main/consema-properties/src/query.rs is the byte-arbitration
//     authority (execution query.rs:123-225, operators query.rs:398-607,
//     source order query.rs:609-634, text/boundary helpers query.rs:636-673);
//     consema-core/src/query.rs:2967-2993 pins QueryLimits defaults
//     (max_steps 100_000, max_results 100_000) and the CancellationToken
//     shape.
//   - The operator argument schemas and the UTF16BE/1 validation live in the
//     protocol package (kotlin/src/main/kotlin/consema/protocol/QueryValidate.kt:139-169,
//     213-228, 801-816); execution here consumes only validated operators.
//
// Kotlin-idiomatic design: execution throws the protocol package's typed
// [consema.protocol.QueryFailureException] carrying the registered code; the
// cursor terminal contract (RFC 0003 §9) is a synchronous complete result
// list here (the JSON family precedent, kotlin/src/main/kotlin/consema/json/Query.kt:21-25),
// with the terminal state always Completed after a successful execution and
// cancellation surfacing as a CANCELLED failure.

package consema.properties

import consema.core.PvBytes
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

/** Owned snapshot-bound Java Properties native semantic query match
 * (query.rs:12-86). */
sealed class PropertiesMatch {
    /** Complete Properties document. */
    data class Document(
        /** Root document identity. */
        val node: NodeRef,
    ) : PropertiesMatch()

    /** One duplicate-preserving property association. */
    data class Property(
        /** Zero-based source-order property ordinal. */
        val ordinal: Int,
        /** Property identity. */
        val node: NodeRef,
        /** Owning logical line. */
        val logicalLine: NodeRef,
        /** Exact Java UTF-16 key. */
        val key: JavaString,
        /** Exact Java UTF-16 value. */
        val value: JavaString,
        /** Implicit, explicit-empty, or present state. */
        val valueState: PropertiesValueState,
        /** Exact-key duplicate group, when present. */
        val duplicateGroup: Int?,
    ) : PropertiesMatch()

    /** One exact natural source line. */
    data class NaturalLine(
        /** Zero-based source-order natural-line ordinal. */
        val ordinal: Int,
        /** Natural-line identity. */
        val node: NodeRef,
        /** Complete raw line span including its terminator. */
        val span: Span,
    ) : PropertiesMatch()

    /** One property or recovered-error logical line. */
    data class LogicalLine(
        /** Zero-based logical-line ordinal. */
        val ordinal: Int,
        /** Logical-line identity. */
        val node: NodeRef,
        /** Logical record kind. */
        val kind: PropertiesLogicalLineKind,
    ) : PropertiesMatch()

    /** One retained property escape. */
    data class Escape(
        /** Zero-based source-order escape ordinal. */
        val ordinal: Int,
        /** Escape identity. */
        val node: NodeRef,
        /** Owning property identity. */
        val property: NodeRef,
        /** Whether the output belongs to the property key. */
        val inKey: Boolean,
        /** Escape behavior. */
        val kind: PropertiesEscapeKind,
        /** Complete raw escape range. */
        val span: Span,
        /** Half-open Java UTF-16 output range. */
        val outputStart: Int,
        /** Exclusive Java UTF-16 output boundary. */
        val outputEnd: Int,
    ) : PropertiesMatch()

    internal fun identity(): NodeRef =
        when (this) {
            is Document -> node
            is Property -> node
            is NaturalLine -> node
            is LogicalLine -> node
            is Escape -> node
        }
}

/** Owned snapshot-bound Java Properties lossless syntax query match
 * (query.rs:88-121). */
data class PropertiesSyntaxMatch(
    /** Process-local syntax-piece identity. */
    val node: NodeRef,
    /** Exact raw source span. */
    val span: Span,
    /** Format-specific lossless kind. */
    val kind: PropertiesSyntaxKind,
    /** Zero-based source-order position. */
    val ordinal: Int,
)

/**
 * Executes a validated Properties native semantic query against one
 * immutable snapshot (query.rs:123-150). The root Document is the first
 * standard result; it must not bypass result limits.
 */
fun executePropertiesQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): List<PropertiesMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "java-properties.native-semantic-query" ||
        definition.domain.version != 1
    ) {
        throw QueryFailureException(
            QueryFailureKind.DOMAIN_MISMATCH,
            domain = definition.domain,
        )
    }
    val context = QueryContext(document, limits, cancellation)
    context.step(1)
    val input = listOf(PropertiesMatch.Document(node = document.nodeRef()))
    val matches = executeExpression(definition.expression, input, context)
    return applySelection(matches, definition.selection)
}

/**
 * Executes a validated Properties lossless syntax query against every source
 * piece in raw order (query.rs:166-211). Matches carry the format-owned kind
 * and the source ordinal.
 */
fun executePropertiesSyntaxQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): List<PropertiesSyntaxMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "java-properties.lossless-syntax-query" ||
        definition.domain.version != 1
    ) {
        throw QueryFailureException(
            QueryFailureKind.DOMAIN_MISMATCH,
            domain = definition.domain,
        )
    }
    val context = QueryContext(document, limits, cancellation)
    val pieces = document.pieces()
    val kinds = document.losslessSyntaxKinds()
    context.step(pieces.size)
    val input = pieces.mapIndexed { ordinal, piece ->
        PropertiesSyntaxMatch(
            node = document.authority.nodeRef(ordinal.toLong(), NodeRole.PropertiesSyntaxPiece),
            span = piece.span,
            kind = kinds[ordinal],
            ordinal = ordinal,
        )
    }
    val matches = executeSyntaxExpression(definition.expression, input, context)
    return applySelection(matches, definition.selection)
}

/** Execution context carrying limits and cancellation (query.rs:227-324). */
private class QueryContext(
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

    fun propertyMatch(ordinal: Int): PropertiesMatch {
        val property = document.propertyEntity(ordinal)
        return PropertiesMatch.Property(
            ordinal = ordinal,
            node = property.node,
            logicalLine = property.logicalLineIndex.let {
                document.logicalLineEntity(it).node
            },
            key = property.key,
            value = property.value,
            valueState = property.valueState,
            duplicateGroup = property.duplicateGroup,
        )
    }

    fun naturalLineMatch(ordinal: Int): PropertiesMatch {
        val line = document.naturalLineEntity(ordinal)
        return PropertiesMatch.NaturalLine(
            ordinal = ordinal,
            node = line.node,
            span = line.span,
        )
    }

    fun logicalLineMatch(ordinal: Int): PropertiesMatch {
        val line = document.logicalLineEntity(ordinal)
        return PropertiesMatch.LogicalLine(
            ordinal = ordinal,
            node = line.node,
            kind = line.kind,
        )
    }

    fun escapeMatch(ordinal: Int): PropertiesMatch {
        val escape = document.escapeEntity(ordinal)
        return PropertiesMatch.Escape(
            ordinal = ordinal,
            node = escape.node,
            property = document.propertyEntity(escape.propertyIndex).node,
            inKey = escape.inKey,
            kind = escape.kind,
            span = escape.span,
            outputStart = escape.outputStart,
            outputEnd = escape.outputEnd,
        )
    }
}

private fun executeExpression(
    expression: QueryExpression,
    input: List<PropertiesMatch>,
    context: QueryContext,
): List<PropertiesMatch> = when (expression.kind) {
    ExpressionKind.Input -> input
    ExpressionKind.Apply -> {
        val inner = executeExpression(expression.input!!, input, context)
        applyOperator(expression.operator!!, inner, context)
    }
    ExpressionKind.Concat -> {
        val output = ArrayList<PropertiesMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<PropertiesMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
        }
        output.sortWith(
            compareBy<PropertiesMatch> { sourceOrder(context.document, it).first }
                .thenBy { sourceOrder(context.document, it).second },
        )
        context.step(output.size)
        output
    }
}

private fun executeSyntaxExpression(
    expression: QueryExpression,
    input: List<PropertiesSyntaxMatch>,
    context: QueryContext,
): List<PropertiesSyntaxMatch> = when (expression.kind) {
    ExpressionKind.Input -> input
    ExpressionKind.Apply -> {
        val inner = executeSyntaxExpression(expression.input!!, input, context)
        applySyntaxOperator(expression.operator!!, inner, context)
    }
    ExpressionKind.Concat -> {
        val output = ArrayList<PropertiesSyntaxMatch>()
        for (branch in expression.branches) {
            output.addAll(executeSyntaxExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<PropertiesSyntaxMatch>()
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
    input: List<PropertiesSyntaxMatch>,
    context: QueryContext,
): List<PropertiesSyntaxMatch> {
    val output: List<PropertiesSyntaxMatch> = when (operator.id) {
        "properties.syntax-kind-is" -> {
            val expected = PropertiesSyntaxKind.fromName(
                operatorArgumentString(operator, "kind"),
            ) ?: error("kind name was validated before binding")
            input.filter { it.kind == expected }
        }
        "properties.syntax-text-equals" -> {
            val expected = operatorArgumentString(operator, "text")
            input.filter { item ->
                context.document.text.spanText(item.span) == expected
            }
        }
        "properties.syntax-raw-bytes-equals" -> {
            val expected = operatorArgumentBytes(operator, "bytes")
            input.filter { item ->
                context.document.source.bytes()
                    .copyOfRange(item.span.startByte, item.span.endByte)
                    .contentEquals(expected)
            }
        }
        "properties.syntax-utf16be-equals" -> {
            val expected = operatorArgumentBytes(operator, "code_units")
            input.filter { item ->
                unicodeTextEqualsUtf16Be(
                    context.document.text.spanText(item.span),
                    expected,
                )
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
        else -> error("validated Properties syntax operator")
    }
    context.step(output.size)
    return output
}

private fun applyOperator(
    operator: OperatorCall,
    input: List<PropertiesMatch>,
    context: QueryContext,
): List<PropertiesMatch> {
    val output = ArrayList<PropertiesMatch>()
    when (operator.id) {
        "properties.document-properties" -> {
            for (item in input) {
                if (item is PropertiesMatch.Document) {
                    for (ordinal in context.document.propertyEntities.indices) {
                        pushResult(output, context.propertyMatch(ordinal), context)
                    }
                }
            }
        }
        "properties.natural-lines" -> {
            for (item in input) {
                if (item is PropertiesMatch.Document) {
                    for (ordinal in context.document.naturalLineEntities.indices) {
                        pushResult(output, context.naturalLineMatch(ordinal), context)
                    }
                }
            }
        }
        "properties.logical-lines" -> {
            for (item in input) {
                if (item is PropertiesMatch.Document) {
                    for (ordinal in context.document.logicalLineEntities.indices) {
                        pushResult(output, context.logicalLineMatch(ordinal), context)
                    }
                }
            }
        }
        "properties.logical-line-natural-lines" -> {
            for (item in input) {
                if (item is PropertiesMatch.LogicalLine) {
                    val logical = context.document.logicalLineEntity(item.ordinal)
                    for (naturalIndex in logical.naturalLineIndices) {
                        pushResult(output, context.naturalLineMatch(naturalIndex), context)
                    }
                }
            }
        }
        "properties.property-key-equals" -> {
            val expected = operatorArgumentBytes(operator, "key")
            for (item in input) {
                if (item is PropertiesMatch.Property && javaStringEqualsUtf16Be(item.key, expected)) {
                    pushResult(output, item, context)
                }
            }
        }
        "properties.property-value-state-is" -> {
            val expected = when (operatorArgumentString(operator, "state")) {
                "ImplicitEmpty" -> PropertiesValueState.ImplicitEmpty
                "ExplicitEmpty" -> PropertiesValueState.ExplicitEmpty
                "Present" -> PropertiesValueState.Present
                else -> error("state was validated before binding")
            }
            for (item in input) {
                if (item is PropertiesMatch.Property && item.valueState == expected) {
                    pushResult(output, item, context)
                }
            }
        }
        "properties.property-escapes" -> {
            for (item in input) {
                if (item is PropertiesMatch.Property) {
                    for ((ordinal, escape) in context.document.escapeEntities.withIndex()) {
                        if (context.document.propertyEntity(escape.propertyIndex).node == item.node) {
                            pushResult(output, context.escapeMatch(ordinal), context)
                        }
                    }
                }
            }
        }
        "properties.duplicate-group" -> {
            for (item in input) {
                if (item is PropertiesMatch.Property && item.duplicateGroup != null) {
                    for (ordinal in context.document.propertyEntities.indices) {
                        if (context.document.propertyEntity(ordinal).duplicateGroup ==
                            item.duplicateGroup
                        ) {
                            pushResult(output, context.propertyMatch(ordinal), context)
                        }
                    }
                }
            }
        }
        "core.take" -> {
            val count = operatorArgumentInteger(operator, "count").toInt()
            for (item in input.take(count)) {
                pushResult(output, item, context)
            }
        }
        "core.distinct-by-identity" -> {
            val seen = HashSet<NodeRef>()
            for (item in input) {
                if (seen.add(item.identity())) {
                    pushResult(output, item, context)
                }
            }
        }
        else -> error("validated Properties native operator")
    }
    context.step(output.size)
    return output
}

private fun pushResult(output: ArrayList<PropertiesMatch>, value: PropertiesMatch, context: QueryContext) {
    if (output.size + 1 > context.limits.maxResults) {
        throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
    }
    output.add(value)
}

/** The deterministic source-order key of one match (query.rs:609-634). */
private fun sourceOrder(document: Document, item: PropertiesMatch): Pair<Int, Int> =
    when (item) {
        is PropertiesMatch.Document -> 0 to 0
        is PropertiesMatch.Property -> {
            val entity = document.propertyEntity(item.ordinal)
            entity.span.startByte to item.ordinal
        }
        is PropertiesMatch.NaturalLine -> item.span.startByte to item.ordinal
        is PropertiesMatch.LogicalLine -> {
            val logical = document.logicalLineEntity(item.ordinal)
            val start = logical.naturalLineIndices.firstOrNull()
                ?.let { document.naturalLineEntity(it).span.startByte }
                ?: 0
            start to item.ordinal
        }
        is PropertiesMatch.Escape -> item.span.startByte to item.ordinal
    }

/** Exact UTF16BE/1 comparison of one Java string (query.rs:653-660). */
private fun javaStringEqualsUtf16Be(value: JavaString, expected: ByteArray): Boolean {
    if (value.length * 2 != expected.size) {
        return false
    }
    val units = value.rawUnits()
    for (i in units.indices) {
        val unit = units[i].code
        if (expected[i * 2].toInt() and 0xff != (unit ushr 8) ||
            expected[i * 2 + 1].toInt() and 0xff != (unit and 0xff)
        ) {
            return false
        }
    }
    return true
}

/** Exact UTF16BE/1 comparison of one well-formed decoded span text
 * (query.rs:662-673). */
private fun unicodeTextEqualsUtf16Be(value: String, expected: ByteArray): Boolean {
    var index = 0
    for (unit in value.toCharArray()) {
        if (index + 2 > expected.size) {
            return false
        }
        val code = unit.code
        if (expected[index].toInt() and 0xff != (code ushr 8) ||
            expected[index + 1].toInt() and 0xff != (code and 0xff)
        ) {
            return false
        }
        index += 2
    }
    return index == expected.size
}

/** Applies the cardinality selection (query.rs:675-692). */
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

private fun operatorArgumentBytes(operator: OperatorCall, name: String): ByteArray =
    (operator.arguments[name] as? PvBytes)?.content() ?: error("validated argument: $name")

private fun operatorArgumentInteger(operator: OperatorCall, name: String): BigInteger {
    val value = operator.arguments[name] ?: error("validated argument: $name")
    val integer = value as? PvInteger ?: error("validated argument: $name")
    return integer.value
}
