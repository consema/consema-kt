// HCL native semantic and lossless syntax query execution.
//
// Data authority:
//   - RFC 0014 §7 (https://github.com/consema/consema/blob/main/docs/rfcs/0014-hcl-family-profiles-v1.md:448-507): the
//     frozen domains `hcl.native-semantic-query@1` (the operator table of
//     §7.1) and `hcl.lossless-syntax-query@1` (the exact kind and
//     decoded-text filters of §7.2); results preserve source order;
//     `hcl.attribute-literal-value@1` is a family of typed accessors
//     (`as-string`, `as-integer`, `as-real`, `as-boolean-is`, `as-null-is`)
//     that validate literal-completeness and the requested type before
//     returning; a non-literal expression is `hcl.query.non-literal@1` and
//     a type mismatch is `hcl.query.type-mismatch@1` — never a null, empty,
//     or converted result (query.rs:802-803); `hcl.error-regions@1` exposes
//     the ordered error regions of a Recovered document, one match per
//     `hcl.parse.*@1`-coded region in source order; no operator evaluates
//     anything (hard gate 1).
//   - conformance/vectors/hcl-v1.json hcl.query.* cases pin the match
//     facts (kind, text, literal, value, ordinal) and the failure codes.
//   - https://github.com/consema/consema-rs/blob/main/consema-hcl/src/query.rs pins the operator semantics;
//     kotlin/src/main/kotlin/consema/protocol/QueryValidate.kt pins the validated operator
//     table (QueryValidate.kt:377-425) and role typing
//     (QueryValidate.kt:578-628); the frozen roles are the Hcl* spellings
//     of protocol/Query.kt:89-96.
//   - consema-go/go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design: definition validation and capability binding
// belong to consema.protocol; this module executes a bound [ExecutableQuery]
// against one immutable snapshot, mirroring the toml family execution
// module (toml/Query.kt).

package consema.hcl

import consema.core.PvBoolean
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvString
import consema.core.PortableValue
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

/** Owned snapshot-bound HCL native semantic query match (query.rs:9-41). */
sealed class HclMatch {
    /** A body: the root or a nested block body. */
    data class Body(
        /** Exact body identity. */
        val node: NodeRef,
        val handle: HclBodyHandle,
    ) : HclMatch()

    /** An attribute occurrence. */
    data class Attribute(
        val node: NodeRef,
        val handle: HclAttributeHandle,
    ) : HclMatch()

    /** A block occurrence. */
    data class Block(
        val node: NodeRef,
        val handle: HclBlockHandle,
    ) : HclMatch()

    /** A block label occurrence. */
    data class BlockLabel(
        val node: NodeRef,
        val handle: HclBlockLabelHandle,
    ) : HclMatch()

    /** An expression AST node. */
    data class Expression(
        val node: NodeRef,
        val handle: HclExpressionHandle,
    ) : HclMatch()

    /** An ordered template part. */
    data class TemplatePart(
        val node: NodeRef,
        val part: HclTemplatePart,
    ) : HclMatch()

    /** A typed literal accessor result (hcl.attribute-literal-value@1). */
    data class LiteralValue(
        val node: NodeRef,
        val handle: HclExpressionHandle,
        val value: PortableValue,
    ) : HclMatch()

    /** One recovered error region of a Recovered document (RFC 0014 §7.1);
     * [position] is the zero-based source-order ordinal among the regions
     * (hcl-v1.json hcl.query.error-regions). */
    data class ErrorRegion(
        val node: NodeRef,
        val region: HclErrorRegion,
        val position: Int,
    ) : HclMatch()

    internal fun identity(): NodeRef = when (this) {
        is Body -> node
        is Attribute -> node
        is Block -> node
        is BlockLabel -> node
        is Expression -> node
        is TemplatePart -> node
        is LiteralValue -> node
        is ErrorRegion -> node
    }

    internal fun role(): NodeRole = when (this) {
        is Body -> NodeRole.HclBody
        is Attribute -> NodeRole.HclAttribute
        is Block -> NodeRole.HclBlock
        is BlockLabel -> NodeRole.HclBlockLabel
        is Expression, is LiteralValue -> NodeRole.HclExpression
        is TemplatePart -> NodeRole.HclTemplatePart
        is ErrorRegion -> NodeRole.HclErrorRegion
    }
}

/** Owned snapshot-bound HCL lossless syntax query match (RFC 0014 §7.2). */
data class HclSyntaxMatch(
    /** Process-local syntax-piece identity. */
    val nodeRef: NodeRef,
    /** Exact raw source span. */
    val span: Span,
    /** Format-specific lossless kind. */
    val kind: HclSyntaxKind,
    /** Zero-based source-order position. */
    val ordinal: Int,
)

/** Query execution resource limits (query.rs:2967-2983: the frozen
 * defaults are 100,000 steps and 100,000 results). */
data class HclQueryLimits(
    /** Maximum evaluation steps. */
    val maxSteps: Int,
    /** Maximum intermediate and final result count. */
    val maxResults: Int,
) {
    companion object {
        /** The frozen defaults (query.rs:2977-2978). */
        val default = HclQueryLimits(maxSteps = 100_000, maxResults = 100_000)
    }
}

/** Cooperative execution cancellation (query.rs:2985-3006). */
class HclCancellationToken {
    private val cancelled = AtomicBoolean(false)

    /** Requests cancellation. */
    fun cancel() {
        cancelled.set(true)
    }

    /** Whether cancellation was requested. */
    fun isCancelled(): Boolean = cancelled.get()
}

/** The terminal status of a completed query execution. */
enum class HclQueryTerminal {
    /** All matches were produced. */
    Completed,

    /** Execution was cancelled. */
    Cancelled,

    /** Execution failed. */
    Failed,
}

/** The complete result of one query execution. */
class HclQueryExecution<T> private constructor(
    private val matches: List<T>,
    val terminal: HclQueryTerminal,
) {
    companion object {
        /** Creates a completed execution. */
        fun <T> completed(matches: List<T>): HclQueryExecution<T> =
            HclQueryExecution(matches, HclQueryTerminal.Completed)
    }

    /** Deterministic ordered matches. */
    fun matches(): List<T> = matches
}

/** Executes a validated HCL native semantic query against one immutable
 * snapshot. The domain must be `hcl.native-semantic-query@1`; violations
 * throw the frozen core.query.domain-mismatch@1. */
fun executeHclQuery(
    executable: ExecutableQuery,
    document: HclDocument,
    limits: HclQueryLimits,
    cancellation: HclCancellationToken,
): HclQueryExecution<HclMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "hcl.native-semantic-query" || definition.domain.version != 1) {
        throw QueryFailureException(QueryFailureKind.DOMAIN_MISMATCH, domain = definition.domain)
    }
    val context = HclQueryContext(document, limits, cancellation)
    val input = listOf(context.rootBodyMatch())
    val matches = executeExpression(definition.expression, input, context)
    val selected = applySelection(matches, definition.selection)
    return HclQueryExecution.completed(selected)
}

/** Executes an HCL lossless syntax query against every source piece in raw
 * order. The domain must be `hcl.lossless-syntax-query@1`. */
fun executeHclSyntaxQuery(
    executable: ExecutableQuery,
    document: HclDocument,
    limits: HclQueryLimits,
    cancellation: HclCancellationToken,
): HclQueryExecution<HclSyntaxMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "hcl.lossless-syntax-query" || definition.domain.version != 1) {
        throw QueryFailureException(QueryFailureKind.DOMAIN_MISMATCH, domain = definition.domain)
    }
    val context = HclQueryContext(document, limits, cancellation)
    val pieces = document.losslessStructuralIndex().pieces()
    context.step(pieces.size)
    val input = pieces.indices.map { ordinal ->
        val piece = pieces[ordinal]
        HclSyntaxMatch(
            nodeRef = document.authority.nodeRef(ordinal.toLong(), NodeRole.HclSyntaxPiece),
            span = piece.span,
            kind = document.losslessSyntaxKinds()[ordinal],
            ordinal = ordinal,
        )
    }
    val matches = executeSyntaxExpression(definition.expression, input, context)
    val selected = applySelection(matches, definition.selection)
    return HclQueryExecution.completed(selected)
}

/** The shared execution context: step/results limits and cancellation. */
internal class HclQueryContext(
    internal val document: HclDocument,
    private val limits: HclQueryLimits,
    private val cancellation: HclCancellationToken,
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

    fun rootBodyMatch(): HclMatch =
        HclMatch.Body(
            document.nodeRef(document.rootBodyIndex, NodeRole.HclBody),
            HclBodyHandle(document, document.rootBodyIndex),
        )
}

/** Evaluates one native expression tree. */
internal fun executeExpression(
    expression: QueryExpression,
    input: List<HclMatch>,
    context: HclQueryContext,
): List<HclMatch> = when (expression.kind) {
    ExpressionKind.Input -> input
    ExpressionKind.Apply -> {
        val evaluated = executeExpression(expression.input!!, input, context)
        applyOperator(expression.operator!!, evaluated, context)
    }
    ExpressionKind.Concat -> {
        val output = ArrayList<HclMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<HclMatch>()
        for (branch in expression.branches) {
            output.addAll(executeExpression(branch, input, context))
        }
        output.sortWith(
            compareBy<HclMatch> { entityStartByte(context, it) }
                .thenBy { it.identity().index },
        )
        context.step(output.size)
        output
    }
}

/** The source start byte of one native match, used by structure-order
 * merges (bodies resolve through their first item). */
private fun entityStartByte(context: HclQueryContext, match: HclMatch): Int {
    val entity = context.document.entity(match.identity().index.toInt())
    return when (entity) {
        is HclEntity.Body -> entity.items.firstOrNull()?.let { rank ->
            entityStartByteOfRank(context, rank)
        } ?: Int.MAX_VALUE
        is HclEntity.Attribute -> entity.attribute.nameSpan.startByte
        is HclEntity.Block -> entity.block.span.startByte
        is HclEntity.BlockLabel -> entity.label.span.startByte
        is HclEntity.Expression -> entity.expression.span.startByte
        is HclEntity.ErrorRegion -> entity.region.span.startByte
    }
}

/** The source start byte of the entity at [rank] (bodies resolve through
 * their first item). */
private fun entityStartByteOfRank(context: HclQueryContext, rank: Int): Int {
    val entity = context.document.entity(rank)
    return when (entity) {
        is HclEntity.Body -> entity.items.firstOrNull()?.let { entityStartByteOfRank(context, it) }
            ?: Int.MAX_VALUE
        is HclEntity.Attribute -> entity.attribute.nameSpan.startByte
        is HclEntity.Block -> entity.block.span.startByte
        is HclEntity.BlockLabel -> entity.label.span.startByte
        is HclEntity.Expression -> entity.expression.span.startByte
        is HclEntity.ErrorRegion -> entity.region.span.startByte
    }
}

/** Evaluates one lossless syntax expression tree. */
internal fun executeSyntaxExpression(
    expression: QueryExpression,
    input: List<HclSyntaxMatch>,
    context: HclQueryContext,
): List<HclSyntaxMatch> = when (expression.kind) {
    ExpressionKind.Input -> input
    ExpressionKind.Apply -> {
        val evaluated = executeSyntaxExpression(expression.input!!, input, context)
        applySyntaxOperator(expression.operator!!, evaluated, context)
    }
    ExpressionKind.Concat -> {
        val output = ArrayList<HclSyntaxMatch>()
        for (branch in expression.branches) {
            output.addAll(executeSyntaxExpression(branch, input, context))
            context.step(output.size)
        }
        output
    }
    ExpressionKind.StructureOrderMerge -> {
        val output = ArrayList<HclSyntaxMatch>()
        for (branch in expression.branches) {
            output.addAll(executeSyntaxExpression(branch, input, context))
        }
        output.sortBy { it.ordinal }
        context.step(output.size)
        output
    }
}

/** Applies one validated native operator (query.rs:551-592). */
internal fun applyOperator(
    operator: OperatorCall,
    input: List<HclMatch>,
    context: HclQueryContext,
): List<HclMatch> {
    val output = ArrayList<HclMatch>()
    when (operator.id) {
        "hcl.document-body" -> {
            // The document's root body, emitted once from any non-empty
            // input.
            if (input.isNotEmpty()) {
                output.add(context.rootBodyMatch())
            }
        }
        "hcl.body-items" -> {
            for (match in input) {
                if (match is HclMatch.Body) {
                    for (item in match.handle.items()) {
                        when (item) {
                            is HclBodyItemHandle.Attribute -> output.add(
                                HclMatch.Attribute(item.handle.nodeRef, item.handle),
                            )
                            is HclBodyItemHandle.Block -> output.add(
                                HclMatch.Block(item.handle.nodeRef, item.handle),
                            )
                        }
                    }
                }
            }
        }
        "hcl.body-attributes" -> {
            for (match in input) {
                if (match is HclMatch.Body) {
                    for (attribute in match.handle.attributes()) {
                        output.add(HclMatch.Attribute(attribute.nodeRef, attribute))
                    }
                }
            }
        }
        "hcl.body-blocks" -> {
            for (match in input) {
                if (match is HclMatch.Body) {
                    for (block in match.handle.blocks()) {
                        output.add(HclMatch.Block(block.nodeRef, block))
                    }
                }
            }
        }
        "hcl.body-block-type-equals" -> {
            val expected = stringArgument(operator, "type")
            for (match in input) {
                if (match is HclMatch.Body) {
                    for (block in match.handle.blocks()) {
                        if (block.blockType() == expected) {
                            output.add(HclMatch.Block(block.nodeRef, block))
                        }
                    }
                }
            }
        }
        "hcl.attribute-name" -> {
            // Keeps every attribute match, selecting the name fact.
            for (match in input) {
                if (match is HclMatch.Attribute) {
                    output.add(match)
                }
            }
        }
        "hcl.attribute-name-equals" -> {
            val expected = stringArgument(operator, "name")
            for (match in input) {
                if (match is HclMatch.Attribute && match.handle.name() == expected) {
                    output.add(match)
                }
            }
        }
        "hcl.attribute-expression" -> {
            for (match in input) {
                if (match is HclMatch.Attribute) {
                    val handle = match.handle.expression()
                    output.add(HclMatch.Expression(handle.nodeRef, handle))
                }
            }
        }
        "hcl.attribute-literal-value" -> {
            val accessor = stringArgument(operator, "accessor")
            for (match in input) {
                val handle = when (match) {
                    is HclMatch.Expression -> match.handle
                    is HclMatch.Attribute -> match.handle.expression()
                    else -> continue
                }
                val value = literalAccessorValue(handle, accessor)
                    ?: continue
                output.add(HclMatch.LiteralValue(handle.nodeRef, handle, value))
            }
        }
        "hcl.block-type" -> {
            for (match in input) {
                if (match is HclMatch.Block) {
                    output.add(match)
                }
            }
        }
        "hcl.block-type-equals" -> {
            val expected = stringArgument(operator, "type")
            for (match in input) {
                if (match is HclMatch.Block && match.handle.blockType() == expected) {
                    output.add(match)
                }
            }
        }
        "hcl.block-labels" -> {
            for (match in input) {
                if (match is HclMatch.Block) {
                    for (label in match.handle.labels()) {
                        output.add(HclMatch.BlockLabel(label.nodeRef, label))
                    }
                }
            }
        }
        "hcl.block-label-equals" -> {
            val expected = stringArgument(operator, "label")
            for (match in input) {
                if (match is HclMatch.BlockLabel && match.handle.text() == expected) {
                    output.add(match)
                }
            }
        }
        "hcl.block-nested-body" -> {
            for (match in input) {
                if (match is HclMatch.Block) {
                    val body = match.handle.body()
                    output.add(HclMatch.Body(body.nodeRef, body))
                }
            }
        }
        "hcl.expression-kind-is" -> {
            val expected = stringArgument(operator, "kind")
            for (match in input) {
                if (match is HclMatch.Expression && match.handle.kindName() == expected) {
                    output.add(match)
                }
            }
        }
        "hcl.expression-is-literal" -> {
            for (match in input) {
                if (match is HclMatch.Expression && match.handle.isLiteral()) {
                    output.add(match)
                }
            }
        }
        "hcl.expression-text" -> {
            for (match in input) {
                if (match is HclMatch.Expression) {
                    output.add(match)
                }
            }
        }
        "hcl.expression-children" -> {
            for (match in input) {
                if (match is HclMatch.Expression) {
                    for (child in match.handle.children()) {
                        output.add(HclMatch.Expression(child.nodeRef, child))
                    }
                }
            }
        }
        "hcl.template-parts" -> {
            for (match in input) {
                if (match is HclMatch.Expression) {
                    val kind = match.handle.kind()
                    if (kind is HclExpressionKind.Template) {
                        for (part in kind.parts) {
                            output.add(
                                HclMatch.TemplatePart(
                                    node = context.document.authority.nodeRef(
                                        templatePartRank(context, part),
                                        NodeRole.HclTemplatePart,
                                    ),
                                    part = part,
                                ),
                            )
                        }
                    }
                }
            }
        }
        "hcl.tuple-elements" -> {
            for (match in input) {
                if (match is HclMatch.Expression) {
                    val kind = match.handle.kind()
                    if (kind is HclExpressionKind.Tuple) {
                        for (element in kind.elements) {
                            val index = context.document.entities.indexOfFirst { entity ->
                                entity is HclEntity.Expression && entity.expression === element
                            }
                            if (index >= 0) {
                                output.add(
                                    HclMatch.Expression(
                                        context.document.nodeRef(index, NodeRole.HclExpression),
                                        HclExpressionHandle(context.document, index),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        "hcl.object-entries" -> {
            for (match in input) {
                if (match is HclMatch.Expression) {
                    val kind = match.handle.kind()
                    if (kind is HclExpressionKind.Object) {
                        for (entry in kind.entries) {
                            val index = context.document.entities.indexOfFirst { entity ->
                                entity is HclEntity.Expression && entity.expression === entry.value
                            }
                            if (index >= 0) {
                                output.add(
                                    HclMatch.Expression(
                                        context.document.nodeRef(index, NodeRole.HclExpression),
                                        HclExpressionHandle(context.document, index),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        "hcl.error-regions" -> {
            val regions = context.document.errorRegions()
            for ((position, region) in regions.withIndex()) {
                output.add(
                    HclMatch.ErrorRegion(
                        node = context.document.authority.nodeRef(
                            position.toLong(),
                            NodeRole.HclErrorRegion,
                        ),
                        region = region,
                        position = position,
                    ),
                )
            }
        }
        "core.take" -> {
            val count = (operator.arguments["count"] as? consema.core.PvInteger)?.value
                ?: throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "count",
                )
            output.addAll(input.take(count.toInt()))
        }
        "core.distinct-by-identity" -> {
            val seen = HashSet<NodeRef>()
            for (match in input) {
                if (seen.add(match.identity())) {
                    output.add(match)
                }
            }
        }
        else -> throw QueryFailureException(QueryFailureKind.UNKNOWN_OPERATOR, operator = operator.id)
    }
    context.step(output.size)
    return output
}

/** A stable rank of one template part, derived from its span; template
 * parts are not first-class arena entities, so the identity is
 * span-anchored (RFC 0014 §7.1). */
private fun templatePartRank(context: HclQueryContext, part: HclTemplatePart): Long {
    val start = part.span.startByte.toLong()
    val end = part.span.endByte.toLong()
    return (start shl 32) or (end and 0xffff_ffffL)
}

/** The typed literal accessor family (RFC 0014 §7.1; query.rs:805-855):
 * each accessor validates literal-completeness and the requested type; a
 * non-literal expression throws the frozen `hcl.query.non-literal@1` and a
 * type mismatch the frozen `hcl.query.type-mismatch@1` — never a null,
 * empty, or converted result (query.rs:802-803). */
internal fun literalAccessorValue(
    handle: HclExpressionHandle,
    accessor: String,
): PortableValue? {
    val value = literalValue(handle.expressionValue()) ?: throw queryNonLiteral()
    return when (accessor) {
        "as-string" -> {
            when (value) {
                is HclLiteralValue.String -> PvString(value.text)
                else -> throw queryTypeMismatch()
            }
        }
        "as-integer" -> {
            when (value) {
                is HclLiteralValue.Integer -> PvInteger(BigInteger(value.text))
                else -> throw queryTypeMismatch()
            }
        }
        "as-real" -> {
            when (value) {
                is HclLiteralValue.Decimal -> decimalFromCanonical(value.text)
                else -> throw queryTypeMismatch()
            }
        }
        "as-boolean-is" -> {
            when (value) {
                is HclLiteralValue.Boolean -> PvBoolean(value.value)
                else -> throw queryTypeMismatch()
            }
        }
        "as-null-is" -> {
            when (value) {
                is HclLiteralValue.Null -> PvNull
                else -> throw queryTypeMismatch()
            }
        }
        else -> throw QueryFailureException(
            QueryFailureKind.INVALID_ARGUMENT,
            operator = "hcl.attribute-literal-value",
            argument = "accessor",
        )
    }
}

/** The frozen `hcl.query.non-literal@1` failure (query.rs:802). */
internal fun queryNonLiteral(): HclQueryException =
    HclQueryException(HCL_QUERY_NON_LITERAL, QueryFailureKind.TARGET_UNAVAILABLE)

/** The frozen `hcl.query.type-mismatch@1` failure (query.rs:803). */
internal fun queryTypeMismatch(): HclQueryException =
    HclQueryException(HCL_QUERY_TYPE_MISMATCH, QueryFailureKind.REQUIRED_TYPE_MISMATCH)

/** Converts one canonical decimal spelling to its PortableValue Decimal
 * member (coefficient × 10^exponent). */
internal fun decimalFromCanonical(canonical: String): PvDecimal {
    val negative = canonical.startsWith("-")
    val body = if (negative) canonical.substring(1) else canonical
    val dot = body.indexOf('.')
    val digits: String
    val exponent: Int
    if (dot < 0) {
        digits = body
        exponent = 0
    } else {
        digits = body.removeRange(dot, dot + 1)
        exponent = -(body.length - dot - 1)
    }
    val coefficient = BigInteger(digits)
    return PvDecimal.of(if (negative) coefficient.negate() else coefficient, BigInteger.valueOf(exponent.toLong()))
}

/** The typed literal projection of a literal-complete expression (RFC 0014
 * §8.2; expression.rs:1596-1712). Null when the expression is derived — a
 * non-literal failure, never a null/empty/converted result. */
internal fun literalValue(expression: HclExpression): HclLiteralValue? {
    val kind = expression.kind
    return when (kind) {
    is HclExpressionKind.Number -> numberLiteral(kind.number.canonicalDecimal)
    is HclExpressionKind.Boolean -> HclLiteralValue.Boolean(kind.value)
    is HclExpressionKind.Null -> HclLiteralValue.Null
    is HclExpressionKind.Template -> {
        val text = StringBuilder()
        for (part in kind.parts) {
            when (part) {
                is HclTemplatePart.Literal -> text.append(part.text)
                is HclTemplatePart.Interpolation, is HclTemplatePart.Directive -> return null
            }
        }
        val content = if (kind.heredoc?.mode == HclHeredocMode.StripIndent) {
            stripHeredocIndentation(text.toString())
        } else {
            text.toString()
        }
        HclLiteralValue.String(content)
    }
    is HclExpressionKind.Tuple -> {
        val values = kind.elements.map { literalValue(it) ?: return null }
        HclLiteralValue.Tuple(values)
    }
    is HclExpressionKind.Object -> {
        val entries = kind.entries.map { entry ->
            val key = when (val key = entry.key) {
                is HclObjectKey.Identifier -> HclLiteralKey.Identifier(key.name)
                is HclObjectKey.Number -> HclLiteralKey.Number(key.number.canonicalDecimal)
                is HclObjectKey.Template -> {
                    val keyText = StringBuilder()
                    for (part in key.template.parts) {
                        when (part) {
                            is HclTemplatePart.Literal -> keyText.append(part.text)
                            is HclTemplatePart.Interpolation, is HclTemplatePart.Directive ->
                                return null
                        }
                    }
                    HclLiteralKey.String(keyText.toString())
                }
                is HclObjectKey.Paren -> HclLiteralKey.Value(literalValue(key.inner) ?: return null)
            }
            HclLiteralObjectEntry(key, literalValue(entry.value) ?: return null)
        }
        HclLiteralValue.Object(entries)
    }
    is HclExpressionKind.Unary -> {
        if (kind.op == HclUnaryOp.Minus && kind.operand.kind is HclExpressionKind.Number) {
            val number = kind.operand.kind as HclExpressionKind.Number
            val canonical = number.number.canonicalDecimal
            val value = if (canonical == "0") canonical else "-$canonical"
            numberLiteral(value)
        } else {
            null
        }
    }
    is HclExpressionKind.Paren -> literalValue(kind.inner)
    is HclExpressionKind.FunctionCall,
    is HclExpressionKind.VariableRef,
    is HclExpressionKind.Traversal,
    is HclExpressionKind.Binary,
    is HclExpressionKind.Conditional,
    is HclExpressionKind.ForTuple,
    is HclExpressionKind.ForObject,
    -> null
    }
}

/** The typed literal value of RFC 0014 §8.2 (expression.rs:1722-1741). */
internal sealed class HclLiteralValue {
    /** Integer value: canonical decimal without a fraction, optional
     * leading `-`. */
    data class Integer(val text: kotlin.String) : HclLiteralValue()

    /** Real value: canonical decimal with a fraction, optional leading
     * `-`. */
    data class Decimal(val text: kotlin.String) : HclLiteralValue()

    /** String value with exact decoded code points, including the `<<-`
     * indentation-stripped heredoc content. */
    data class String(val text: kotlin.String) : HclLiteralValue()

    /** Boolean value. */
    data class Boolean(val value: kotlin.Boolean) : HclLiteralValue()

    /** Null value. */
    data object Null : HclLiteralValue()

    /** Ordered tuple of literal values. */
    data class Tuple(val elements: List<HclLiteralValue>) : HclLiteralValue()

    /** Ordered object entries; duplicate keys are preserved. */
    data class Object(val entries: List<HclLiteralObjectEntry>) : HclLiteralValue()
}

/** One ordered object literal entry (expression.rs:1743-1748). */
internal data class HclLiteralObjectEntry(
    val key: HclLiteralKey,
    val value: HclLiteralValue,
)

/** One object literal key (expression.rs:1764-1800). */
internal sealed class HclLiteralKey {
    /** Bare identifier key. */
    data class Identifier(val name: kotlin.String) : HclLiteralKey()

    /** Number-literal key with its canonical decimal spelling. */
    data class Number(val canonical: kotlin.String) : HclLiteralKey()

    /** Quoted-template key with its decoded text. */
    data class String(val text: kotlin.String) : HclLiteralKey()

    /** Parenthesized-expression key with its literal value. */
    data class Value(val value: HclLiteralValue) : HclLiteralKey()
}

private fun numberLiteral(canonical: String): HclLiteralValue =
    if (canonical.contains('.')) {
        HclLiteralValue.Decimal(canonical)
    } else {
        HclLiteralValue.Integer(canonical)
    }

/** Applies the `<<-` indentation stripping: removes the minimum number of
 * leading spaces from each line's leading literal text (RFC 0014 §4.5;
 * expression.rs:1692-1712). */
internal fun stripHeredocIndentation(text: String): String {
    var minimum: Int? = null
    for (line in text.split('\n')) {
        if (line.isEmpty()) {
            continue
        }
        val indent = line.takeWhile { it == ' ' }.length
        minimum = minimum?.let { minOf(it, indent) } ?: indent
    }
    val min = minimum ?: return ""
    val out = StringBuilder()
    for ((index, line) in text.split('\n').withIndex()) {
        if (index > 0) {
            out.append('\n')
        }
        out.append(line.substring(minOf(min, line.length)))
    }
    return out.toString()
}

/** Applies one validated lossless syntax operator (RFC 0014 §7.2). */
internal fun applySyntaxOperator(
    operator: OperatorCall,
    input: List<HclSyntaxMatch>,
    context: HclQueryContext,
): List<HclSyntaxMatch> {
    val output = ArrayList<HclSyntaxMatch>()
    when (operator.id) {
        "hcl.syntax-kind-is" -> {
            val expected = stringArgument(operator, "kind")
            for (match in input) {
                if (match.kind.asStr() == expected) {
                    output.add(match)
                }
            }
        }
        "hcl.syntax-text-equals" -> {
            val expected = stringArgument(operator, "text")
            val text = context.document.source.decodedText() ?: ""
            for (match in input) {
                if (text.substring(match.span.startByte, match.span.endByte) == expected) {
                    output.add(match)
                }
            }
        }
        "core.take" -> {
            val count = (operator.arguments["count"] as? consema.core.PvInteger)?.value
                ?: throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "count",
                )
            output.addAll(input.take(count.toInt()))
        }
        "core.distinct-by-identity" -> {
            val seen = HashSet<NodeRef>()
            for (match in input) {
                if (seen.add(match.nodeRef)) {
                    output.add(match)
                }
            }
        }
        else -> throw QueryFailureException(QueryFailureKind.UNKNOWN_OPERATOR, operator = operator.id)
    }
    context.step(output.size)
    return output
}

/** Reads one validated String operator argument. */
private fun stringArgument(operator: OperatorCall, name: String): String =
    (operator.arguments[name] as? PvString)?.value
        ?: throw QueryFailureException(
            QueryFailureKind.INVALID_ARGUMENT,
            operator = operator.id,
            argument = name,
        )

/** Applies the cardinality selection to the complete standard result
 * sequence. */
private fun <T> applySelection(matches: List<T>, selection: QuerySelection): List<T> =
    when (selection) {
        QuerySelection.All -> matches
        QuerySelection.First -> matches.take(1)
        QuerySelection.Last -> matches.takeLast(1)
        QuerySelection.ZeroOrOne -> {
            if (matches.size > 1) {
                throw QueryFailureException(QueryFailureKind.CARDINALITY_VIOLATION)
            }
            matches
        }
        QuerySelection.RequireOne -> {
            if (matches.size != 1) {
                throw QueryFailureException(QueryFailureKind.CARDINALITY_VIOLATION)
            }
            matches
        }
    }
