// Versioned plist native-semantic, lossless-syntax, and binary-structure
// query execution.
//
// Data authority:
//   - RFC 0013 §8.1 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md:539-558):
//     the native domain operator set, source-order results, exact Unicode
//     key comparison without case folding, duplicate-key-group expansion,
//     and typed accessors that validate the value type before returning (a
//     type mismatch is a query failure, never a null or converted result).
//   - RFC 0013 §8.2 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-...md:560-582): the lossless syntax
//     domain provides exact kind and decoded-text filters over pieces.
//   - RFC 0013 §8.3 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-...md:584-596): the binary structure
//     domain exposes the object/offset/reference/trailer facts with exact
//     byte spans; the domain exists only for the `plist.binary@1`
//     representation (hard gate 1: no invented text trivia).
//   - conformance/vectors/plist-v1.json (plist.query.*) pins the match
//     facts and the terminal states; consema-rs/consema-plist/src/query.rs is
//     the byte-arbitration authority (native operators query.rs:333-660,
//     binary operators query.rs:1330-1511, selection query.rs:440-459).
//   - The operator table and role validation live in the protocol package
//     (kotlin/.../protocol/QueryValidate.kt:330-375); this file executes
//     validated definitions.
//
// Kotlin-idiomatic design: execution returns a closed sealed outcome
// (Completed with the ordered matches, or Failed with the frozen
// `plist.query.*@1` code), so the terminal state asserted by the vectors is
// a first-class value; the operator interpreter is a flat list transformer
// with a step/result budget and cooperative cancellation.

package consema.plist

import consema.core.PvBoolean
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
import java.util.concurrent.atomic.AtomicBoolean

/** Query resource limits (query.rs:2967-2981; the json-family precedent
 * kotlin/.../json/Query.kt:44-56). */
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

/** One typed fact carried by a typed native accessor match. */
sealed class TypedValue {
    data class Integer(val value: Long) : TypedValue()

    data class Real(val value: Double) : TypedValue()

    data class StringV(val value: String) : TypedValue()

    data class Data(val hex: String) : TypedValue()

    data class Date(val seconds: Double) : TypedValue()

    data class Uid(val value: Long) : TypedValue()

    data class BooleanV(val value: Boolean) : TypedValue()
}

/** Owned snapshot-bound plist native semantic query match (query.rs:11-43;
 * RFC 0013 §8.1). */
sealed class PlistMatch {
    /** A native value. */
    data class Value(
        /** Exact value identity. */
        val node: NodeRef,
        /** Native kind when proven. */
        val kind: PlistValueKind?,
        /** Typed fact after a typed accessor. */
        val typed: TypedValue? = null,
        internal val index: Int,
    ) : PlistMatch()

    /** One dictionary association with duplicate identity preserved. */
    data class DictEntry(
        /** Zero-based association ordinal. */
        val ordinal: Int,
        /** Key identity. */
        val key: NodeRef,
        /** Value identity. */
        val value: NodeRef,
        /** Association identity. */
        val entry: NodeRef,
        internal val index: Int,
    ) : PlistMatch()

    /** One dictionary key. */
    data class Key(
        /** Exact key identity. */
        val node: NodeRef,
        /** Decoded key text when well formed. */
        val text: String?,
        internal val index: Int,
    ) : PlistMatch()

    /** One array element. */
    data class ArrayElement(
        /** Zero-based element ordinal. */
        val ordinal: Int,
        /** Association identity. */
        val element: NodeRef,
        /** Value identity. */
        val value: NodeRef,
        internal val index: Int,
    ) : PlistMatch()
}

/** Owned snapshot-bound plist lossless syntax query match (RFC 0013 §8.2;
 * query.rs:55-88). */
data class PlistSyntaxMatch(
    /** Process-local syntax-piece identity. */
    val node: NodeRef,
    /** Exact raw source span. */
    val span: Span,
    /** Format-specific lossless kind. */
    val kind: PlistSyntaxKind,
    /** Zero-based source-order position. */
    val ordinal: Int,
)

/** Owned snapshot-bound plist binary structure query match (query.rs:53-171;
 * RFC 0013 §8.3). */
sealed class PlistBinaryMatch {
    /** One proven object-table entry fact. */
    data class Object(
        /** Process-local fact identity. */
        val node: NodeRef,
        /** Object-table ordinal. */
        val index: Int,
        /** Marker byte offset. */
        val offset: Int,
        /** Marker byte. */
        val marker: Int,
        /** Exact marker-through-payload range. */
        val span: Span,
    ) : PlistBinaryMatch()

    /** One validated offset-table entry fact. */
    data class Offset(
        /** Process-local fact identity. */
        val node: NodeRef,
        /** Object-table ordinal of this entry. */
        val index: Int,
        /** Decoded absolute marker offset. */
        val offset: Int,
        /** Exact entry range inside the offset table. */
        val span: Span,
    ) : PlistBinaryMatch()

    /** One decoded reference fact. */
    data class Ref(
        /** Process-local fact identity. */
        val node: NodeRef,
        /** Ordinal of this fact. */
        val index: Int,
        /** Referencing object index. */
        val owner: Int,
        /** Position within the owner's reference block. */
        val position: Int,
        /** Decoded target object index. */
        val target: Int,
        /** Exact byte range of the reference. */
        val span: Span,
    ) : PlistBinaryMatch()

    /** The trailer field facts. */
    data class Trailer(
        /** Process-local fact identity. */
        val node: NodeRef,
        /** Trailer facts. */
        val facts: BinaryTrailerFacts,
    ) : PlistBinaryMatch()

    /** The trailer's top object with its ordered reference facts. */
    data class TopObject(
        /** Process-local fact identity. */
        val node: NodeRef,
        /** The top object fact. */
        val topObject: Object,
        /** Ordered (position, target, span) reference facts of the top
         * object. */
        val refs: List<Triple<Int, Int, Span>>,
    ) : PlistBinaryMatch()
}

/** The closed execution outcome; [Failed] carries the frozen
 * `plist.query.*@1` code the vectors assert (plist_v1.rs:1143-1153). */
sealed class PlistQueryOutcome<out T> {
    /** Successful complete execution with ordered matches. */
    data class Completed<T>(val matches: List<T>) : PlistQueryOutcome<T>()

    /** The query failed; [code] is the frozen plist-family code. */
    data class Failed(val code: String) : PlistQueryOutcome<Nothing>()
}

/** The typed plist query failure thrown for definition-level violations
 * (domain mismatch, unknown operator, argument errors). Execution-level
 * type mismatches return [PlistQueryOutcome.Failed] with
 * [PlistCodes.QUERY_TYPE_MISMATCH]. */
class PlistQueryException(
    val kind: QueryFailureKind,
    message: String = kind.code,
    val domain: String = "",
    val operator: String = "",
    val argument: String = "",
) : Exception(message)

/**
 * Executes a validated plist native semantic query against one immutable
 * snapshot (query.rs:91-125). The root value is the first standard input;
 * the domain is available on both representations. A Recovered document
 * without a provable native graph fails with TargetUnavailable.
 */
fun executePlistNativeQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): PlistQueryOutcome<PlistMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "plist.native-semantic-query" || definition.domain.version != 1) {
        return PlistQueryOutcome.Failed("plist.query.domain-mismatch@1")
    }
    val input = if (document.nativeRoot == null) {
        return PlistQueryOutcome.Failed("plist.query.target-unavailable@1")
    } else {
        listOf(rootValueMatch(document))
    }
    val context = QueryContext(document, limits, cancellation)
    val result = try {
        val matches = context.executeNative(definition.expression, input)
        applySelection(matches, definition.selection)
    } catch (e: PlistQueryFailure) {
        return PlistQueryOutcome.Failed(e.code)
    }
    @Suppress("UNCHECKED_CAST")
    return PlistQueryOutcome.Completed(result as List<PlistMatch>)
}

/**
 * Executes a validated plist lossless syntax query (RFC 0013 §8.2). The
 * domain exists only for the `plist.xml@1` representation (hard gate 1).
 */
fun executePlistSyntaxQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): PlistQueryOutcome<PlistSyntaxMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "plist.lossless-syntax-query" || definition.domain.version != 1) {
        return PlistQueryOutcome.Failed("plist.query.domain-mismatch@1")
    }
    val index = document.losslessStructuralIndex()
    val kinds = document.losslessSyntaxKinds()
    if (index == null || kinds == null) {
        return PlistQueryOutcome.Failed("plist.query.domain-mismatch@1")
    }
    val pieces = index.pieces()
    val input = pieces.mapIndexed { ordinal, piece ->
        PlistSyntaxMatch(
            node = document.nodeRef(ordinal.toLong(), NodeRole.PlistSyntaxPiece),
            span = piece.span,
            kind = kinds[ordinal],
            ordinal = ordinal,
        )
    }
    val context = QueryContext(document, limits, cancellation)
    val result = try {
        val matches = context.executeSyntax(definition.expression, input)
        applySelection(matches, definition.selection)
    } catch (e: PlistQueryFailure) {
        return PlistQueryOutcome.Failed(e.code)
    }
    @Suppress("UNCHECKED_CAST")
    return PlistQueryOutcome.Completed(result as List<PlistSyntaxMatch>)
}

/**
 * Executes a validated plist binary structure query (RFC 0013 §8.3;
 * query.rs:386-423). The domain exists only for the `plist.binary@1`
 * representation (hard gate 1).
 */
fun executePlistBinaryQuery(
    executable: ExecutableQuery,
    document: Document,
    limits: QueryLimits = QueryLimits.default,
    cancellation: CancellationToken = CancellationToken(),
): PlistQueryOutcome<PlistBinaryMatch> {
    val definition = executable.validated.definition
    if (definition.domain.id != "plist.binary-structure-query" || definition.domain.version != 1) {
        return PlistQueryOutcome.Failed("plist.query.domain-mismatch@1")
    }
    val facts = document.binaryFacts()
    if (facts == null) {
        return PlistQueryOutcome.Failed("plist.query.domain-mismatch@1")
    }
    val context = QueryContext(document, limits, cancellation)
    val result = try {
        val matches = context.executeBinary(definition.expression, emptyList(), facts)
        applySelection(matches, definition.selection)
    } catch (e: PlistQueryFailure) {
        return PlistQueryOutcome.Failed(e.code)
    }
    @Suppress("UNCHECKED_CAST")
    return PlistQueryOutcome.Completed(result as List<PlistBinaryMatch>)
}

/** Execution-time query failure with the frozen plist-family code. */
private class PlistQueryFailure(val code: String) : Exception(code)

/** One native operator step over the ordered match list. */
private class QueryContext(
    private val document: Document,
    private val limits: QueryLimits,
    private val cancellation: CancellationToken,
) {
    private var steps = 0

    private fun step(results: Int) {
        if (cancellation.isCancelled()) {
            throw PlistQueryFailure("plist.query.cancelled@1")
        }
        steps += 1
        if (steps > limits.maxSteps || results > limits.maxResults) {
            throw PlistQueryFailure("plist.query.resource-limit@1")
        }
    }

    private fun push(output: MutableList<Any>, value: Any) {
        if (output.size + 1 > limits.maxResults) {
            throw PlistQueryFailure("plist.query.resource-limit@1")
        }
        output.add(value)
    }

    private fun append(output: MutableList<Any>, values: List<Any>) {
        if (output.size + values.size > limits.maxResults) {
            throw PlistQueryFailure("plist.query.resource-limit@1")
        }
        output.addAll(values)
    }

    // ------------------------------------------------------------------
    // Native domain
    // ------------------------------------------------------------------

    fun executeNative(expression: QueryExpression, input: List<Any>): List<Any> =
        when (expression.kind) {
            ExpressionKind.Input -> input
            ExpressionKind.Apply -> {
                val actualInput = executeNative(expression.input!!, input)
                applyNativeOperator(expression.operator!!, actualInput)
            }
            ExpressionKind.Concat, ExpressionKind.StructureOrderMerge -> {
                val output = ArrayList<Any>()
                for (branch in expression.branches) {
                    append(output, executeNative(branch, input))
                }
                output
            }
        }

    private fun applyNativeOperator(operator: OperatorCall, input: List<Any>): List<Any> {
        step(input.size)
        val output = ArrayList<Any>()
        when (operator.id) {
            "plist.document-root" -> {
                append(output, input)
            }
            "plist.dict-entries" -> {
                for (item in input) {
                    val value = item as? PlistMatch.Value ?: continue
                    val entries = document.valueEntity(value.index).native
                        ?.let { it as? NativeValue.Dict } ?: continue
                    for (entryIndex in entries.entries) {
                        push(output, dictEntryMatch(entryIndex))
                    }
                }
            }
            "plist.dict-entry-key" -> {
                for (item in input) {
                    val entry = item as? PlistMatch.DictEntry ?: continue
                    push(output, keyMatch(entry.index))
                }
            }
            "plist.dict-entry-value" -> {
                for (item in input) {
                    val entry = item as? PlistMatch.DictEntry ?: continue
                    val entity = document.dictEntryEntity(entry.index)
                    push(output, valueMatch(entity.valueIndex))
                }
            }
            "plist.dict-key-equals" -> {
                val argument = operator.arguments["key"] as? PvString
                    ?: throw PlistQueryFailure("plist.query.invalid-argument@1")
                for (item in input) {
                    val entry = item as? PlistMatch.DictEntry ?: continue
                    if (keyTextOf(entry.index) == argument.value) {
                        push(output, entry)
                    }
                }
            }
            "plist.duplicate-key-group" -> {
                for (item in input) {
                    val entry = item as? PlistMatch.DictEntry ?: continue
                    val text = keyTextOf(entry.index) ?: continue
                    for (groupEntry in allEntriesWithKey(text)) {
                        push(output, dictEntryMatch(groupEntry))
                    }
                }
            }
            "plist.array-elements" -> {
                for (item in input) {
                    val value = item as? PlistMatch.Value ?: continue
                    val elements = document.valueEntity(value.index).native
                        ?.let { it as? NativeValue.Array } ?: continue
                    for (elementIndex in elements.elements) {
                        push(output, arrayElementMatch(elementIndex))
                    }
                }
            }
            "plist.value-type-is" -> {
                val argument = operator.arguments["kind"] as? PvString
                    ?: throw PlistQueryFailure("plist.query.invalid-argument@1")
                val kind = PlistValueKind.fromName(argument.value)
                    ?: throw PlistQueryFailure("plist.query.invalid-argument@1")
                for (item in input) {
                    val value = item as? PlistMatch.Value ?: continue
                    if (value.kind == kind) {
                        push(output, value)
                    }
                }
            }
            "plist.value-as-integer" -> {
                for (item in input) {
                    val value = item as? PlistMatch.Value
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    val native = document.valueEntity(value.index).native
                    val typed = (native as? NativeValue.Integer)?.value
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    push(output, PlistMatch.Value(value.node, PlistValueKind.Integer,
                        TypedValue.Integer(typed), value.index))
                }
            }
            "plist.value-as-real" -> {
                for (item in input) {
                    val value = item as? PlistMatch.Value
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    val typed = (document.valueEntity(value.index).native as? NativeValue.Real)?.real
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    push(output, PlistMatch.Value(value.node, PlistValueKind.Real,
                        TypedValue.Real(typed.asDouble()), value.index))
                }
            }
            "plist.value-as-string" -> {
                for (item in input) {
                    val value = item as? PlistMatch.Value
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    val native = document.valueEntity(value.index).native
                    val string = (native as? NativeValue.StringV)?.string
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    val text = string.toUnicode()
                        ?: throw PlistQueryFailure("plist.query.target-unavailable@1")
                    push(output, PlistMatch.Value(value.node, PlistValueKind.String,
                        TypedValue.StringV(text), value.index))
                }
            }
            "plist.value-as-data" -> {
                for (item in input) {
                    val value = item as? PlistMatch.Value
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    val typed = (document.valueEntity(value.index).native as? NativeValue.Data)?.data
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    push(output, PlistMatch.Value(value.node, PlistValueKind.Data,
                        TypedValue.Data(typed.bytes().joinToString("") { "%02x".format(it) }),
                        value.index))
                }
            }
            "plist.value-as-date" -> {
                for (item in input) {
                    val value = item as? PlistMatch.Value
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    val typed = (document.valueEntity(value.index).native as? NativeValue.Date)?.seconds
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    push(output, PlistMatch.Value(value.node, PlistValueKind.Date,
                        TypedValue.Date(typed), value.index))
                }
            }
            "plist.value-as-uid" -> {
                for (item in input) {
                    val value = item as? PlistMatch.Value
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    val typed = (document.valueEntity(value.index).native as? NativeValue.Uid)?.uid
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    push(output, PlistMatch.Value(value.node, PlistValueKind.Uid,
                        TypedValue.Uid(typed.toLong()), value.index))
                }
            }
            "plist.value-as-boolean-is" -> {
                val argument = operator.arguments["value"] as? PvBoolean
                    ?: throw PlistQueryFailure("plist.query.invalid-argument@1")
                for (item in input) {
                    val value = item as? PlistMatch.Value
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    val typed = (document.valueEntity(value.index).native as? NativeValue.BooleanV)?.value
                        ?: throw PlistQueryFailure(PlistCodes.QUERY_TYPE_MISMATCH)
                    if (typed == argument.value) {
                        push(output, PlistMatch.Value(value.node, PlistValueKind.Boolean,
                            TypedValue.BooleanV(typed), value.index))
                    }
                }
            }
            "core.take" -> {
                val count = (operator.arguments["count"] as? consema.core.PvInteger)?.value?.toInt()
                    ?: throw PlistQueryFailure("plist.query.invalid-argument@1")
                append(output, input.take(count))
            }
            "core.distinct-by-identity" -> {
                val seen = HashSet<Long>()
                for (item in input) {
                    val identity = matchIdentity(item)
                    if (identity == null || seen.add(identity)) {
                        push(output, item)
                    }
                }
            }
            else -> throw PlistQueryFailure("plist.query.unknown-operator@1")
        }
        return output
    }

    // ------------------------------------------------------------------
    // Syntax domain
    // ------------------------------------------------------------------

    fun executeSyntax(expression: QueryExpression, input: List<Any>): List<Any> =
        when (expression.kind) {
            ExpressionKind.Input -> input
            ExpressionKind.Apply -> {
                val actualInput = executeSyntax(expression.input!!, input)
                applySyntaxOperator(expression.operator!!, actualInput)
            }
            ExpressionKind.Concat, ExpressionKind.StructureOrderMerge -> {
                val output = ArrayList<Any>()
                for (branch in expression.branches) {
                    append(output, executeSyntax(branch, input))
                }
                output
            }
        }

    private fun applySyntaxOperator(operator: OperatorCall, input: List<Any>): List<Any> {
        step(input.size)
        val output = ArrayList<Any>()
        when (operator.id) {
            "plist.syntax-kind-is" -> {
                val argument = operator.arguments["kind"] as? PvString
                    ?: throw PlistQueryFailure("plist.query.invalid-argument@1")
                for (item in input) {
                    val match = item as? PlistSyntaxMatch ?: continue
                    if (match.kind.wireName() == argument.value) {
                        push(output, match)
                    }
                }
            }
            "plist.syntax-text-equals" -> {
                val argument = operator.arguments["text"] as? PvString
                    ?: throw PlistQueryFailure("plist.query.invalid-argument@1")
                for (item in input) {
                    val match = item as? PlistSyntaxMatch ?: continue
                    val text = document.source().bytes()
                        .copyOfRange(match.span.startByte, match.span.endByte)
                        .toString(Charsets.UTF_8)
                    if (text == argument.value) {
                        push(output, match)
                    }
                }
            }
            "core.take" -> {
                val count = (operator.arguments["count"] as? consema.core.PvInteger)?.value?.toInt()
                    ?: throw PlistQueryFailure("plist.query.invalid-argument@1")
                append(output, input.take(count))
            }
            "core.distinct-by-identity" -> {
                val seen = HashSet<Long>()
                for (item in input) {
                    val match = item as? PlistSyntaxMatch ?: continue
                    val identity = match.node.index
                    if (seen.add(identity)) {
                        push(output, match)
                    }
                }
            }
            else -> throw PlistQueryFailure("plist.query.unknown-operator@1")
        }
        return output
    }

    // ------------------------------------------------------------------
    // Binary structure domain
    // ------------------------------------------------------------------

    fun executeBinary(
        expression: QueryExpression,
        input: List<Any>,
        facts: BinaryFacts,
    ): List<Any> =
        when (expression.kind) {
            ExpressionKind.Input -> input
            ExpressionKind.Apply -> {
                val actualInput = executeBinary(expression.input!!, input, facts)
                applyBinaryOperator(expression.operator!!, actualInput, facts)
            }
            ExpressionKind.Concat, ExpressionKind.StructureOrderMerge -> {
                val output = ArrayList<Any>()
                for (branch in expression.branches) {
                    append(output, executeBinary(branch, input, facts))
                }
                output
            }
        }

    private fun applyBinaryOperator(
        operator: OperatorCall,
        input: List<Any>,
        facts: BinaryFacts,
    ): List<Any> {
        step(input.size)
        val output = ArrayList<Any>()
        when (operator.id) {
            "plist.object-table" -> {
                for ((ordinal, fact) in facts.objects.withIndex()) {
                    push(output, objectMatch(facts, ordinal, fact))
                }
            }
            "plist.top-object" -> {
                val top = facts.trailer.topObject
                val fact = facts.objects.firstOrNull { it.index.toLong() == top } ?: return output
                val refs = facts.refs
                    .filter { it.owner == fact.index }
                    .map { Triple(it.position, it.target, it.span) }
                push(
                    output,
                    PlistBinaryMatch.TopObject(
                        node = document.nodeRef(
                            (facts.objects.size + facts.offsets.size + facts.refs.size + 1).toLong(),
                            NodeRole.BinaryRegion,
                        ),
                        topObject = objectMatch(facts, fact.index, fact),
                        refs = refs,
                    ),
                )
            }
            "plist.object-offset", "plist.offset-table" -> {
                for ((ordinal, fact) in facts.offsets.withIndex()) {
                    push(
                        output,
                        PlistBinaryMatch.Offset(
                            node = document.nodeRef(
                                (facts.objects.size + ordinal).toLong(),
                                NodeRole.BinaryRegion,
                            ),
                            index = fact.index,
                            offset = fact.offset,
                            span = fact.span,
                        ),
                    )
                }
            }
            "plist.object-refs" -> {
                for ((ordinal, fact) in facts.refs.withIndex()) {
                    push(
                        output,
                        PlistBinaryMatch.Ref(
                            node = document.nodeRef(
                                (facts.objects.size + facts.offsets.size + ordinal).toLong(),
                                NodeRole.BinaryRegion,
                            ),
                            index = ordinal,
                            owner = fact.owner,
                            position = fact.position,
                            target = fact.target,
                            span = fact.span,
                        ),
                    )
                }
            }
            "plist.trailer-facts" -> {
                push(
                    output,
                    PlistBinaryMatch.Trailer(
                        node = document.nodeRef(
                            (facts.objects.size + facts.offsets.size + facts.refs.size).toLong(),
                            NodeRole.BinaryRegion,
                        ),
                        facts = facts.trailer,
                    ),
                )
            }
            "core.take" -> {
                val count = (operator.arguments["count"] as? consema.core.PvInteger)?.value?.toInt()
                    ?: throw PlistQueryFailure("plist.query.invalid-argument@1")
                append(output, input.take(count))
            }
            "core.distinct-by-identity" -> {
                val seen = HashSet<Long>()
                for (item in input) {
                    val match = item as? PlistBinaryMatch
                    val identity = when (match) {
                        is PlistBinaryMatch.Object -> match.node.index
                        is PlistBinaryMatch.Offset -> match.node.index
                        is PlistBinaryMatch.Ref -> match.node.index
                        is PlistBinaryMatch.Trailer -> match.node.index
                        is PlistBinaryMatch.TopObject -> match.node.index
                        null -> null
                    }
                    if (identity == null || seen.add(identity)) {
                        push(output, item)
                    }
                }
            }
            else -> throw PlistQueryFailure("plist.query.unknown-operator@1")
        }
        return output
    }

    private fun objectMatch(facts: BinaryFacts, ordinal: Int, fact: BinaryObjectFact): PlistBinaryMatch.Object =
        PlistBinaryMatch.Object(
            node = document.nodeRef(ordinal.toLong(), NodeRole.BinaryRegion),
            index = fact.index,
            offset = fact.offset,
            marker = fact.marker,
            span = fact.span,
        )

    // ------------------------------------------------------------------
    // Match construction and helpers
    // ------------------------------------------------------------------

    private fun valueMatch(index: Int): PlistMatch.Value =
        PlistMatch.Value(
            node = document.nodeRef(index.toLong(), NodeRole.PlistValue),
            kind = document.valueEntity(index).native?.kind(),
            index = index,
        )

    private fun dictEntryMatch(index: Int): PlistMatch.DictEntry {
        val entity = document.dictEntryEntity(index)
        return PlistMatch.DictEntry(
            ordinal = entity.ordinal,
            key = document.nodeRef(entity.keyIndex.toLong(), NodeRole.PlistKey),
            value = document.nodeRef(entity.valueIndex.toLong(), NodeRole.PlistValue),
            entry = document.nodeRef(index.toLong(), NodeRole.PlistDictEntry),
            index = index,
        )
    }

    private fun keyMatch(index: Int): PlistMatch.Key {
        val native = document.valueEntity(index).native as? NativeValue.StringV
        return PlistMatch.Key(
            node = document.nodeRef(index.toLong(), NodeRole.PlistKey),
            text = native?.string?.toUnicode(),
            index = index,
        )
    }

    private fun arrayElementMatch(index: Int): PlistMatch.ArrayElement {
        val entity = document.arrayElementEntity(index)
        return PlistMatch.ArrayElement(
            ordinal = entity.ordinal,
            element = document.nodeRef(index.toLong(), NodeRole.PlistArrayElement),
            value = document.nodeRef(entity.valueIndex.toLong(), NodeRole.PlistValue),
            index = index,
        )
    }

    private fun keyTextOf(entryIndex: Int): String? {
        val entity = document.dictEntryEntity(entryIndex)
        return (document.valueEntity(entity.keyIndex).native as? NativeValue.StringV)
            ?.string?.toUnicode()
    }

    private fun allEntriesWithKey(text: String): List<Int> {
        val output = ArrayList<Int>()
        for ((index, entity) in document.entities.withIndex()) {
            if (entity is Entity.DictEntry) {
                val entryEntity = entity.entity
                val key = (document.valueEntity(entryEntity.keyIndex).native as? NativeValue.StringV)
                    ?.string?.toUnicode()
                if (key == text) {
                    output.add(index)
                }
            }
        }
        return output
    }

    private fun matchIdentity(item: Any): Long? =
        when (item) {
            is PlistMatch.Value -> item.node.index
            is PlistMatch.DictEntry -> item.entry.index
            is PlistMatch.Key -> item.node.index
            is PlistMatch.ArrayElement -> item.element.index
            is PlistSyntaxMatch -> item.node.index
            is PlistBinaryMatch.Object -> item.node.index
            is PlistBinaryMatch.Offset -> item.node.index
            is PlistBinaryMatch.Ref -> item.node.index
            is PlistBinaryMatch.Trailer -> item.node.index
            is PlistBinaryMatch.TopObject -> item.node.index
            else -> null
        }
}

/** The document root as the first native-domain input match. */
private fun rootValueMatch(document: Document): PlistMatch.Value =
    PlistMatch.Value(
        node = document.nodeRef(document.rootIndex.toLong(), NodeRole.PlistValue),
        kind = document.valueEntity(document.rootIndex).native?.kind(),
        index = document.rootIndex,
    )

/** Applies the validated cardinality selection to a complete standard result
 * sequence (query.rs:440-459). */
private fun applySelection(values: List<Any>, selection: QuerySelection): List<Any> =
    when (selection) {
        QuerySelection.All -> values
        QuerySelection.First -> values.take(1)
        QuerySelection.Last -> values.takeLast(1)
        QuerySelection.ZeroOrOne -> if (values.size <= 1) values else {
            throw PlistQueryFailure("plist.query.cardinality-violation@1")
        }
        QuerySelection.RequireOne -> if (values.size == 1) values else {
            throw PlistQueryFailure("plist.query.cardinality-violation@1")
        }
    }
