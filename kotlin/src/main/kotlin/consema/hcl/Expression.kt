// The unevaluated HCL expression model: kind, exact source span, ordered
// children, canonical decimals, template parts, and the literal-complete
// predicate.
//
// Data authority:
//   - RFC 0014 §4.3-§4.6 (https://github.com/consema/consema/blob/main/docs/rfcs/0014-hcl-family-profiles-v1.md:215-357):
//     the frozen expression grammar, operator precedence, templates,
//     heredocs, constructors, and for-expressions.
//   - RFC 0014 §6 (:395-446): an expression is a first-class native role
//     retained as an AST (kind, ordered children, exact spans) with its
//     exact source text derived from the immutable source span; structural
//     equality is recursive over kind and children (number equality is
//     canonical-decimal equality); unevaluated is the default contract.
//   - RFC 0014 §8.1 (:511-537): the literal-complete boundary — a purely
//     syntactic predicate; no arithmetic is ever computed (hard gate 1).
//   - consema-rs/consema-hcl/src/expression.rs pins the exact shapes and
//     spellings: HclExpressionKind (:200-312), HclExpressionKindName
//     (:564-650, "number"/"boolean"/"null"/"template"/"function-call"/
//     "variable-ref"/"traversal"/"unary"/"binary"/"conditional"/"for-tuple"/
//     "for-object"/"tuple"/"object"/"parenthesized"), the kind family
//     spelling of the `hcl.expression@1` record (projection.rs:996-1040:
//     "variable" for VariableRef|Traversal, "for" for ForTuple|ForObject),
//     canonical_decimal (:737-851), the operator spellings (:856-956), and
//     the traversal/template/heredoc/for/object shapes (:958-1504).
//   - The structural fingerprint is the FNV-1a 64-bit hash over the
//     canonical structural serialization defined by the materialization
//     codec (materialization.rs:1496-1758), the shared adaptation point of
//     the `hcl.expression@1` payload (projection.rs:581-592).
//   - consema-go/go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the closed kind set is a
// sealed class hierarchy — exhaustive `when` can never meet an unknown kind;
// every node carries its exact raw-byte span and immutable ordered children.

package consema.hcl

import consema.document.Span
import java.math.BigInteger

/** Unary operator set; exactly `-` and `!` exist, and unary `+` is a grammar
 * error (RFC 0014 §4.3; expression.rs:856-861). */
enum class HclUnaryOp(val spelling: String) {
    /** `-` negation. */
    Minus("-"),

    /** `!` logical not. */
    Not("!"),
    ;

    companion object {
        /** Resolves one operator spelling (expression.rs:875-881). */
        fun fromName(name: String): HclUnaryOp? = entries.firstOrNull { it.spelling == name }
    }
}

/** Binary operator set, frozen by the RFC 0014 §4.3 precedence table
 * (expression.rs:886-913). */
enum class HclBinaryOp(val spelling: String, val precedence: Int) {
    /** `==` equality. */
    Equal("==", 4),

    /** `!=` inequality. */
    NotEqual("!=", 4),

    /** `<` less than. */
    Less("<", 3),

    /** `>` greater than. */
    Greater(">", 3),

    /** `<=` less than or equal. */
    LessEqual("<=", 3),

    /** `>=` greater than or equal. */
    GreaterEqual(">=", 3),

    /** `+` addition. */
    Add("+", 2),

    /** `-` subtraction. */
    Subtract("-", 2),

    /** `*` multiplication. */
    Multiply("*", 1),

    /** `/` division. */
    Divide("/", 1),

    /** `%` modulo. */
    Modulo("%", 1),

    /** `&&` logical and. */
    And("&&", 5),

    /** `||` logical or. */
    Or("||", 6),
    ;

    companion object {
        /** Resolves one operator spelling (expression.rs:938-955). */
        fun fromName(name: String): HclBinaryOp? = entries.firstOrNull { it.spelling == name }
    }
}

/** Traversal root; keyword spellings are dual-read roots, behaving as if
 * they were references to variables of those names without ever being
 * evaluated (RFC 0014 §4.1; expression.rs:962-969). */
sealed class HclTraversalRoot {
    /** A variable-name root. */
    data class Variable(val name: kotlin.String) : HclTraversalRoot()

    /** The `true`/`false` keyword as a static traversal root. */
    data class Boolean(val value: kotlin.Boolean) : HclTraversalRoot()

    /** The `null` keyword as a static traversal root. */
    data object Null : HclTraversalRoot()
}

/** One static traversal step (RFC 0014 §4.3; expression.rs:978-1003).
 * Attribute steps admit identifiers only: the numeric form `foo.0` is a
 * grammar error (RFC 0014 §12 D-5). */
sealed class HclTraversalStep {

    /** `.Identifier` attribute step. */
    data class GetAttr(
        val name: String,
        /** Exact span of the step, including the dot. */
        val span: Span,
    ) : HclTraversalStep()

    /** `[Expression]` index step. */
    data class Index(
        val key: HclExpression,
        /** Exact span of the step, including the brackets. */
        val span: Span,
    ) : HclTraversalStep()

    /** `. * GetAttr*` attribute splat. */
    data class AttrSplat(val steps: List<HclTraversalStep>) : HclTraversalStep()

    /** `[ * ] (GetAttr | Index)*` full splat. */
    data class FullSplat(val steps: List<HclTraversalStep>) : HclTraversalStep()
}

/** One template directive kind (RFC 0014 §4.4; expression.rs:1138-1155).
 * The single-identifier for-directive `%{ for x in list }` is valid (RFC
 * 0014 §12 D-7). */
sealed class HclDirectiveKind {
    /** `%{ if Expression }`. */
    data class If(val condition: HclExpression) : HclDirectiveKind()

    /** `%{ else }`. */
    data object Else : HclDirectiveKind()

    /** `%{ endif }`. */
    data object EndIf : HclDirectiveKind()

    /** `%{ for Identifier , Identifier in Expression }` (key optional). */
    data class For(val intro: HclForIntro) : HclDirectiveKind()

    /** `%{ endfor }`. */
    data object EndFor : HclDirectiveKind()
}

/** One ordered template part (RFC 0014 §6; expression.rs:1052-1077). */
sealed class HclTemplatePart {
    /** Exact span of the whole part, including delimiters and strip
     * markers (expression.rs:1079-1089). */
    abstract val span: Span

    /** Literal text; escaped `$${` and `%%{` sequences decode to literal
     * `${`/`%{` text and count as literal text (RFC 0014 §4.4). */
    data class Literal(
        /** Exact span of the literal run, including escapes. */
        override val span: Span,
        /** Escape-decoded literal text. */
        val text: kotlin.String,
    ) : HclTemplatePart()

    /** An interpolation `${ Expression }` with optional `~` strip markers. */
    data class Interpolation(
        /** Exact span of the whole interpolation, including delimiters and
         * strip markers. */
        override val span: Span,
        val expression: HclExpression,
    ) : HclTemplatePart()

    /** A directive `%{ if }`/`%{ else }`/`%{ endif }`/`%{ for }`/`%{ endfor }`. */
    data class Directive(
        /** Exact span of the whole directive, including delimiters and
         * strip markers. */
        override val span: Span,
        val kind: HclDirectiveKind,
    ) : HclTemplatePart()
}

/** Heredoc mode fact: `<<` or `<<-` (RFC 0014 §4.5; expression.rs:1159-1165). */
enum class HclHeredocMode(val spelling: String) {
    /** `<<` plain heredoc: no indentation stripping. */
    Plain("<<"),

    /** `<<-` strip-indent heredoc: the literal value removes the minimum
     * number of leading spaces from each line's leading literal text. */
    StripIndent("<<-"),
}

/** Heredoc representation facts of one template (RFC 0014 §4.5, §6;
 * expression.rs:1186-1234). Structural equality compares the mode and
 * marker spelling only (expression.rs:1236-1242). */
data class HclHeredocFacts(
    /** Heredoc mode (`<<` or `<<-`). */
    val mode: HclHeredocMode,
    /** Bare identifier marker spelling. */
    val marker: String,
    /** Exact span of the marker identifier. */
    val markerSpan: Span,
    /** Exact span of the closing marker line, or null for an unterminated
     * heredoc (RFC 0014 §3). */
    val closingSpan: Span?,
)

/** One function-call argument with its expansion marker fact (RFC 0014
 * §4.3; expression.rs:1254-1277). */
data class HclCallArg(
    val expression: HclExpression,
    /** `...` expansion marker fact; only the final argument may carry it. */
    val expand: Boolean,
)

/** The `for` introduction of a for-expression or for-directive (RFC 0014
 * §4.6; expression.rs:1283-1331). */
data class HclForIntro(
    /** Optional key identifier; null is the single-identifier form (RFC
     * 0014 §12 D-7). */
    val key: String?,
    /** Value identifier. */
    val value: String,
    /** Collection expression. */
    val collection: HclExpression,
    /** Exact span of the whole introduction, including `for ... in ...:`. */
    val span: Span,
)

/** One object-constructor key (RFC 0014 §4.6; expression.rs:1355-1364). */
sealed class HclObjectKey {
    /** Bare identifier key. */
    data class Identifier(val name: String) : HclObjectKey()

    /** Number-literal key. */
    data class Number(val number: HclNumber) : HclObjectKey()

    /** Quoted-template key. */
    data class Template(val template: HclTemplateKey) : HclObjectKey()

    /** Parenthesized-expression key. */
    data class Paren(val inner: HclExpression) : HclObjectKey()
}

/** A quoted-template object key (RFC 0014 §4.6; expression.rs:1405-1428). */
data class HclTemplateKey(
    /** Ordered parts, including the quote delimiters' span facts. */
    val parts: List<HclTemplatePart>,
    /** Exact span of the key, including the quotes. */
    val span: Span,
)

/** Object-constructor key/value separator source fact (RFC 0014 §4.6;
 * expression.rs:1488-1504). */
enum class HclObjectSeparator(val spelling: String) {
    /** `=`. */
    Equals("="),

    /** `:`. */
    Colon(":"),
}

/** One ordered object-constructor entry (RFC 0014 §4.6;
 * expression.rs:1450-1484). Duplicate keys are preserved as ordered native
 * facts and are never collapsed. */
data class HclObjectEntry(
    val key: HclObjectKey,
    val separator: HclObjectSeparator,
    val value: HclExpression,
)

/** A decimal number literal with its exact spelling span and canonical
 * decimal value (RFC 0014 §4.1, §6; expression.rs:658-703). Equality is
 * canonical-decimal equality (expression.rs:705-709). */
data class HclNumber(
    /** Exact source span of the number spelling. */
    val span: Span,
    /** Canonical decimal spelling: no leading zeros, no trailing fraction
     * zeros, exponent folded into the decimal point position, `"0"` for
     * zero (RFC 0014 §9). */
    val canonicalDecimal: String,
) {
    override fun equals(other: Any?): Boolean =
        other is HclNumber && canonicalDecimal == other.canonicalDecimal

    override fun hashCode(): Int = canonicalDecimal.hashCode()
}

/**
 * One unevaluated HCL expression node: an exact raw-byte [span] and a
 * closed [kind] (RFC 0014 §6). The exact source text is derived from the
 * span; the AST and the raw text are always both available.
 */
data class HclExpression(
    /** Exact source span. */
    val span: Span,
    /** Closed expression kind. */
    val kind: HclExpressionKind,
)

/** Closed unevaluated expression kind set (RFC 0014 §4.3-§4.6;
 * expression.rs:200-312). */
sealed class HclExpressionKind {
    /** A decimal number literal with its exact spelling and canonical
     * decimal value (RFC 0014 §4.1, §6). */
    data class Number(val number: HclNumber) : HclExpressionKind()

    /** The `true` or `false` keyword literal (RFC 0014 §4.3). */
    data class Boolean(val value: kotlin.Boolean) : HclExpressionKind()

    /** The `null` literal (RFC 0014 §4.3). */
    data object Null : HclExpressionKind()

    /** A quoted template or heredoc with ordered parts (RFC 0014
     * §4.4-§4.5). */
    data class Template(
        val parts: List<HclTemplatePart>,
        /** Heredoc representation facts; null for quoted templates. */
        val heredoc: HclHeredocFacts?,
    ) : HclExpressionKind()

    /** A function call `name(args)`; the name is a plain identifier only —
     * the namespaced `foo::bar()` form is a grammar error (RFC 0014 §4.3,
     * §12 D-6). */
    data class FunctionCall(
        /** Function name. */
        val name: String,
        /** Exact span of the name identifier. */
        val nameSpan: Span,
        /** Ordered arguments, each with its `...` expansion marker fact. */
        val args: List<HclCallArg>,
    ) : HclExpressionKind()

    /** A variable reference: a traversal root with no steps (RFC 0014 §4.1,
     * §4.3). */
    data class VariableRef(val name: String) : HclExpressionKind()

    /** A static traversal: a root followed by attribute, index, and splat
     * steps; never resolved (RFC 0014 §4.1, §4.3). */
    data class Traversal(
        val root: HclTraversalRoot,
        val steps: List<HclTraversalStep>,
    ) : HclExpressionKind()

    /** A unary operation. Only `-` and `!` exist; unary `+` is a grammar
     * error, and unary operators bind at the term layer above every binary
     * operator (RFC 0014 §4.3). */
    data class Unary(
        val op: HclUnaryOp,
        val operand: HclExpression,
    ) : HclExpressionKind()

    /** A binary operation; left-associative within its precedence level
     * (RFC 0014 §4.3). */
    data class Binary(
        val op: HclBinaryOp,
        val lhs: HclExpression,
        val rhs: HclExpression,
    ) : HclExpressionKind()

    /** The conditional `condition ? then : else` production, which never
     * binds tighter than `||` (RFC 0014 §4.3). */
    data class Conditional(
        val condition: HclExpression,
        val then: HclExpression,
        val elseExpr: HclExpression,
    ) : HclExpressionKind()

    /** A tuple for-expression `[for ... : value if cond]`; no iteration is
     * ever performed (RFC 0014 §4.6, §6). */
    data class ForTuple(
        val intro: HclForIntro,
        val value: HclExpression,
        val condition: HclExpression?,
    ) : HclExpressionKind()

    /** An object for-expression `{for ... : key => value ... if cond}`; the
     * `...` grouping marker is a source fact (RFC 0014 §4.6, §6). */
    data class ForObject(
        val intro: HclForIntro,
        val key: HclExpression,
        val value: HclExpression,
        /** `...` grouping marker fact. */
        val grouping: kotlin.Boolean,
        val condition: HclExpression?,
    ) : HclExpressionKind()

    /** A tuple constructor; elements are ordered, separated by comma or
     * newline, with a trailing comma admitted (RFC 0014 §4.6). */
    data class Tuple(val elements: List<HclExpression>) : HclExpressionKind()

    /** An object constructor; entries are ordered and duplicate keys are
     * preserved, never collapsed (RFC 0014 §4.6, §6). */
    data class Object(val entries: List<HclObjectEntry>) : HclExpressionKind()

    /** A parenthesized expression `(expr)` (RFC 0014 §4.3). */
    data class Paren(val inner: HclExpression) : HclExpressionKind()

    /** Closed payload-free kind name (RFC 0014 §7.1
     * `hcl.expression-kind-is@1`; expression.rs:314-335). */
    val kindName: HclExpressionKindName
        get() = when (this) {
            is Number -> HclExpressionKindName.NUMBER
            is Boolean -> HclExpressionKindName.BOOLEAN
            is Null -> HclExpressionKindName.NULL
            is Template -> HclExpressionKindName.TEMPLATE
            is FunctionCall -> HclExpressionKindName.FUNCTION_CALL
            is VariableRef -> HclExpressionKindName.VARIABLE_REF
            is Traversal -> HclExpressionKindName.TRAVERSAL
            is Unary -> HclExpressionKindName.UNARY
            is Binary -> HclExpressionKindName.BINARY
            is Conditional -> HclExpressionKindName.CONDITIONAL
            is ForTuple -> HclExpressionKindName.FOR_TUPLE
            is ForObject -> HclExpressionKindName.FOR_OBJECT
            is Tuple -> HclExpressionKindName.TUPLE
            is Object -> HclExpressionKindName.OBJECT
            is Paren -> HclExpressionKindName.PARENTHESIZED
        }

    /** Kind family spelling of the `hcl.expression@1` record (RFC 0014
     * §4.1, §4.6, §8.2; projection.rs:996-1020). */
    val kindFamily: String
        get() = when (this) {
            is Number -> "number"
            is Boolean -> "boolean"
            is Null -> "null"
            is Template -> "template"
            is FunctionCall -> "function-call"
            is VariableRef, is Traversal -> "variable"
            is Unary -> "unary"
            is Binary -> "binary"
            is Conditional -> "conditional"
            is ForTuple, is ForObject -> "for"
            is Tuple -> "tuple"
            is Object -> "object"
            is Paren -> "parenthesized"
        }

    /** Ordered direct child expressions (expression.rs:89-180). */
    val children: List<HclExpression>
        get() = when (this) {
            is Number, is Boolean, is Null -> emptyList()
            is Template -> parts.mapNotNull { part ->
                when (part) {
                    is HclTemplatePart.Interpolation -> part.expression
                    is HclTemplatePart.Directive -> when (val kind = part.kind) {
                        is HclDirectiveKind.If -> kind.condition
                        is HclDirectiveKind.For -> kind.intro.collection
                        else -> null
                    }
                    is HclTemplatePart.Literal -> null
                }
            }
            is FunctionCall -> args.map { it.expression }
            is VariableRef -> emptyList()
            is Traversal -> steps.mapNotNull { step ->
                when (step) {
                    is HclTraversalStep.Index -> step.key
                    else -> null
                }
            }
            is Unary -> listOf(operand)
            is Binary -> listOf(lhs, rhs)
            is Conditional -> listOf(condition, then, elseExpr)
            is ForTuple -> listOf(intro.collection, value) + (condition?.let { listOf(it) } ?: emptyList())
            is ForObject ->
                listOf(intro.collection, key, value) + (condition?.let { listOf(it) } ?: emptyList())

            is Tuple -> elements
            is Object -> entries.map { it.value }
            is Paren -> listOf(inner)
        }
}

/** Closed payload-free expression kind name set (RFC 0014 §7.1
 * `hcl.expression-kind-is@1`; expression.rs:564-650). */
enum class HclExpressionKindName(val spelling: String) {
    /** Number literal. */
    NUMBER("number"),

    /** `true`/`false` keyword literal. */
    BOOLEAN("boolean"),

    /** `null` literal. */
    NULL("null"),

    /** Quoted template or heredoc. */
    TEMPLATE("template"),

    /** Function call. */
    FUNCTION_CALL("function-call"),

    /** Variable reference (traversal root with no steps). */
    VARIABLE_REF("variable-ref"),

    /** Static traversal with steps. */
    TRAVERSAL("traversal"),

    /** Unary `-`/`!` operation. */
    UNARY("unary"),

    /** Binary operation. */
    BINARY("binary"),

    /** Conditional `? :`. */
    CONDITIONAL("conditional"),

    /** Tuple for-expression. */
    FOR_TUPLE("for-tuple"),

    /** Object for-expression. */
    FOR_OBJECT("for-object"),

    /** Tuple constructor. */
    TUPLE("tuple"),

    /** Object constructor. */
    OBJECT("object"),

    /** Parenthesized expression. */
    PARENTHESIZED("parenthesized"),
    ;

    companion object {
        /** Resolves one stable kind spelling (expression.rs:622-648). */
        fun fromName(name: String): HclExpressionKindName? =
            entries.firstOrNull { it.spelling == name }
    }
}

/**
 * Normalizes one decimal number spelling to its canonical form by pure
 * decimal string arithmetic — zero floating-point computation (hard
 * gate 1; expression.rs:737-851).
 *
 * The grammar is frozen by RFC 0014 §4.1: `decimal+ ("." decimal+)?
 * (expmark decimal+)?` with `expmark = ("e" | "E") ("+" | "-")?`, no
 * leading sign. The canonical form strips leading zeros, strips trailing
 * fraction zeros, and folds the exponent into the decimal point position:
 * `"1.50"` and `"15e-1"` both normalize to `"1.5"`, `"1e3"` to `"1000"`,
 * and every zero spelling to `"0"` (RFC 0014 §6, §9). Returns null for a
 * grammar violation or a canonical spelling that would exceed [maxDigits].
 */
internal fun canonicalDecimalBounded(spelling: String, maxDigits: Int): String? {
    var index = 0
    while (index < spelling.length && spelling[index].isDigit()) {
        index += 1
    }
    val integerLen = index
    if (integerLen == 0) {
        return null
    }
    var fractionLen = 0
    if (index < spelling.length && spelling[index] == '.') {
        index += 1
        val fractionStart = index
        while (index < spelling.length && spelling[index].isDigit()) {
            index += 1
        }
        fractionLen = index - fractionStart
        if (fractionLen == 0) {
            return null
        }
    }
    var exponent = 0L
    if (index < spelling.length && (spelling[index] == 'e' || spelling[index] == 'E')) {
        index += 1
        var negative = false
        if (index < spelling.length && (spelling[index] == '+' || spelling[index] == '-')) {
            negative = spelling[index] == '-'
            index += 1
        }
        val exponentStart = index
        while (index < spelling.length && spelling[index].isDigit()) {
            index += 1
        }
        if (index == exponentStart) {
            return null
        }
        val magnitude = spelling.substring(exponentStart, index).toLongOrNull() ?: return null
        exponent = if (negative) -magnitude else magnitude
    }
    if (index != spelling.length) {
        return null
    }
    // The value is the concatenated digits with the decimal point after
    // `integerLen + exponent` digits.
    val digits = spelling.substring(0, integerLen) +
        (if (fractionLen > 0) spelling.substring(integerLen + 1, integerLen + 1 + fractionLen) else "")
    val stripped = digits.trimStart('0')
    val point = try {
        Math.addExact(integerLen.toLong(), exponent) -
            (digits.length - stripped.length)
    } catch (e: ArithmeticException) {
        return null
    }
    if (stripped.isEmpty()) {
        return "0"
    }
    val out = StringBuilder()
    if (point <= 0) {
        // `0.` plus `zeros` zero digits plus the significant digits, with
        // trailing fraction zeros trimmed (expression.rs:806-826).
        val zeros = try {
            Math.negateExact(point)
        } catch (e: ArithmeticException) {
            return null
        }
        val trimmed = stripped.trimEnd('0')
        if (zeros + trimmed.length + 1 > maxDigits) {
            return null
        }
        out.append("0.")
        repeat(zeros.toInt()) { out.append('0') }
        out.append(stripped)
        while (out.length > 2 && out[out.length - 1] == '0') {
            out.setLength(out.length - 1)
        }
    } else {
        val positive = point
        if (positive >= stripped.length) {
            // The canonical spelling is the significant digits followed by
            // `positive - stripped.length` zeros (expression.rs:827-840).
            if (positive > maxDigits) {
                return null
            }
            out.append(stripped)
            repeat((positive - stripped.length).toInt()) { out.append('0') }
        } else {
            out.append(stripped, 0, positive.toInt())
            val fraction = stripped.substring(positive.toInt()).trimEnd('0')
            if (fraction.isNotEmpty()) {
                out.append('.')
                out.append(fraction)
            }
        }
    }
    return out.toString()
}

/** Normalizes one number spelling with the frozen default digit budget
 * (RFC 0014 §11; expression.rs:736-739). */
internal fun canonicalDecimal(spelling: String): String? =
    canonicalDecimalBounded(spelling, HclParseLimits.default.maxNumberDigits)

/** Renders the canonical decimal string of a coefficient × 10^exponent
 * pair (the record-materialization path of RFC 0014 §9). */
internal fun canonicalDecimalString(coefficient: BigInteger, exponent: Int): String {
    if (coefficient.signum() == 0) {
        return "0"
    }
    val digits = coefficient.abs().toString()
    val point = digits.length + exponent
    val out = StringBuilder()
    if (coefficient.signum() < 0) {
        out.append('-')
    }
    if (point <= 0) {
        out.append("0.")
        repeat(-point) { out.append('0') }
        out.append(digits.trimEnd('0').ifEmpty { "0" })
    } else if (point >= digits.length) {
        out.append(digits)
        repeat(point - digits.length) { out.append('0') }
    } else {
        out.append(digits, 0, point)
        val fraction = digits.substring(point).trimEnd('0')
        if (fraction.isNotEmpty()) {
            out.append('.')
            out.append(fraction)
        }
    }
    return out.toString()
}

/**
 * Whether an expression is literal-complete: its value is uniquely
 * determined by the source text alone — no evaluation, no context (RFC
 * 0014 §8.1). The boundary is deliberately purely syntactic: it is
 * decidable without any evaluator, and no arithmetic is ever computed (hard
 * gate 1).
 */
fun isLiteralComplete(expression: HclExpression): Boolean =
    when (val kind = expression.kind) {
        is HclExpressionKind.Number,
        is HclExpressionKind.Boolean,
        is HclExpressionKind.Null,
        -> true

        is HclExpressionKind.Template ->
            kind.parts.all { part ->
                when (part) {
                    is HclTemplatePart.Literal -> true
                    is HclTemplatePart.Interpolation, is HclTemplatePart.Directive -> false
                }
            }

        is HclExpressionKind.Unary ->
            kind.op == HclUnaryOp.Minus && kind.operand.kind is HclExpressionKind.Number

        is HclExpressionKind.Paren -> isLiteralComplete(kind.inner)

        is HclExpressionKind.Tuple -> kind.elements.all(::isLiteralComplete)

        is HclExpressionKind.Object -> kind.entries.all { entry ->
            val keyOk = when (val key = entry.key) {
                is HclObjectKey.Identifier -> true
                is HclObjectKey.Number -> true
                is HclObjectKey.Template ->
                    key.template.parts.all { part -> part is HclTemplatePart.Literal }

                is HclObjectKey.Paren -> isLiteralComplete(key.inner)
            }
            keyOk && isLiteralComplete(entry.value)
        }

        is HclExpressionKind.FunctionCall,
        is HclExpressionKind.VariableRef,
        is HclExpressionKind.Traversal,
        is HclExpressionKind.Binary,
        is HclExpressionKind.Conditional,
        is HclExpressionKind.ForTuple,
        is HclExpressionKind.ForObject,
        -> false
    }

/**
 * The structural fingerprint of one expression: a 64-bit FNV-1a hash over
 * the canonical structural serialization (RFC 0014 §8.2;
 * materialization.rs:1496-1516). The serialization covers the frozen
 * structural equality of RFC 0014 §6 and never source spans or identities,
 * so structurally equal expressions always carry the same fingerprint.
 */
fun structuralFingerprint(expression: HclExpression): ULong {
    val bytes = ArrayList<Byte>()
    writeExpressionStructure(expression, bytes)
    var hash = 0xcbf2_9ce4_8422_2325UL
    for (byte in bytes) {
        hash = hash xor byte.toULong()
        hash *= 0x0000_0100_0000_01b3UL
    }
    return hash
}

/** The closed family spelling set of the `hcl.expression@1` record kind
 * (projection.rs:1022-1040). */
internal val EXPRESSION_KIND_FAMILY_SPELLINGS: Set<String> = setOf(
    "number", "boolean", "null", "template", "function-call", "variable",
    "unary", "binary", "conditional", "for", "tuple", "object",
    "parenthesized",
)

private fun pushText(text: String, out: ArrayList<Byte>) {
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        out.add(byte)
    }
}

private fun pushByte(value: Int, out: ArrayList<Byte>) {
    out.add(value.toByte())
}

/** Appends the canonical structural serialization of one expression
 * (materialization.rs:1524-1667). */
private fun writeExpressionStructure(expression: HclExpression, out: ArrayList<Byte>) {
    when (val kind = expression.kind) {
        is HclExpressionKind.Number -> {
            pushByte('N'.code, out)
            pushText(kind.number.canonicalDecimal, out)
        }
        is HclExpressionKind.Boolean -> {
            pushByte('B'.code, out)
            pushByte(if (kind.value) 1 else 0, out)
        }
        is HclExpressionKind.Null -> pushByte('Z'.code, out)
        is HclExpressionKind.Template -> {
            pushByte('T'.code, out)
            val heredoc = kind.heredoc
            if (heredoc != null) {
                pushByte('H'.code, out)
                pushText(heredoc.mode.spelling, out)
                pushText(heredoc.marker, out)
            } else {
                pushByte('Q'.code, out)
            }
            for (part in kind.parts) {
                when (part) {
                    is HclTemplatePart.Literal -> {
                        pushByte('L'.code, out)
                        pushText(part.text, out)
                    }
                    is HclTemplatePart.Interpolation -> {
                        pushByte('I'.code, out)
                        writeExpressionStructure(part.expression, out)
                    }
                    is HclTemplatePart.Directive -> {
                        pushByte('D'.code, out)
                        writeDirectiveStructure(part.kind, out)
                    }
                }
            }
        }
        is HclExpressionKind.FunctionCall -> {
            pushByte('F'.code, out)
            pushText(kind.name, out)
            for (argument in kind.args) {
                pushByte(if (argument.expand) 'X'.code else 'x'.code, out)
                writeExpressionStructure(argument.expression, out)
            }
        }
        is HclExpressionKind.VariableRef -> {
            pushByte('V'.code, out)
            pushText(kind.name, out)
        }
        is HclExpressionKind.Traversal -> {
            pushByte('R'.code, out)
            when (val root = kind.root) {
                is HclTraversalRoot.Variable -> {
                    pushByte('v'.code, out)
                    pushText(root.name, out)
                }
                is HclTraversalRoot.Boolean -> {
                    pushByte('b'.code, out)
                    pushByte(if (root.value) 1 else 0, out)
                }
                is HclTraversalRoot.Null -> pushByte('n'.code, out)
            }
            for (step in kind.steps) {
                writeTraversalStep(step, out)
            }
        }
        is HclExpressionKind.Unary -> {
            pushByte('U'.code, out)
            pushText(kind.op.spelling, out)
            writeExpressionStructure(kind.operand, out)
        }
        is HclExpressionKind.Binary -> {
            pushByte('W'.code, out)
            pushText(kind.op.spelling, out)
            writeExpressionStructure(kind.lhs, out)
            writeExpressionStructure(kind.rhs, out)
        }
        is HclExpressionKind.Conditional -> {
            pushByte('C'.code, out)
            writeExpressionStructure(kind.condition, out)
            writeExpressionStructure(kind.then, out)
            writeExpressionStructure(kind.elseExpr, out)
        }
        is HclExpressionKind.ForTuple -> {
            pushByte('P'.code, out)
            writeForIntro(kind.intro, out)
            writeExpressionStructure(kind.value, out)
            val condition = kind.condition
            if (condition != null) {
                pushByte('c'.code, out)
                writeExpressionStructure(condition, out)
            } else {
                pushByte('n'.code, out)
            }
        }
        is HclExpressionKind.ForObject -> {
            pushByte('O'.code, out)
            writeForIntro(kind.intro, out)
            writeExpressionStructure(kind.key, out)
            writeExpressionStructure(kind.value, out)
            pushByte(if (kind.grouping) 'g'.code else 'n'.code, out)
            val condition = kind.condition
            if (condition != null) {
                pushByte('c'.code, out)
                writeExpressionStructure(condition, out)
            } else {
                pushByte('n'.code, out)
            }
        }
        is HclExpressionKind.Tuple -> {
            pushByte('L'.code, out)
            for (element in kind.elements) {
                writeExpressionStructure(element, out)
            }
        }
        is HclExpressionKind.Object -> {
            pushByte('M'.code, out)
            for (entry in kind.entries) {
                writeObjectKeyStructure(entry.key, out)
                writeExpressionStructure(entry.value, out)
            }
        }
        is HclExpressionKind.Paren -> {
            pushByte('('.code, out)
            writeExpressionStructure(kind.inner, out)
        }
    }
}

/** Appends the canonical structural serialization of one template
 * directive (materialization.rs:1669-1685). */
private fun writeDirectiveStructure(kind: HclDirectiveKind, out: ArrayList<Byte>) {
    when (kind) {
        is HclDirectiveKind.If -> {
            pushByte('f'.code, out)
            writeExpressionStructure(kind.condition, out)
        }
        is HclDirectiveKind.Else -> pushByte('e'.code, out)
        is HclDirectiveKind.EndIf -> pushByte('E'.code, out)
        is HclDirectiveKind.For -> {
            pushByte('o'.code, out)
            writeForIntro(kind.intro, out)
        }
        is HclDirectiveKind.EndFor -> pushByte('g'.code, out)
    }
}

/** Appends the canonical structural serialization of one `for`
 * introduction (materialization.rs:1687-1699). */
private fun writeForIntro(intro: HclForIntro, out: ArrayList<Byte>) {
    val key = intro.key
    if (key != null) {
        pushByte('k'.code, out)
        pushText(key, out)
    } else {
        pushByte('n'.code, out)
    }
    pushText(intro.value, out)
    writeExpressionStructure(intro.collection, out)
}

/** Appends the canonical structural serialization of one traversal step
 * (materialization.rs:1701-1725). */
private fun writeTraversalStep(step: HclTraversalStep, out: ArrayList<Byte>) {
    when (step) {
        is HclTraversalStep.GetAttr -> {
            pushByte('a'.code, out)
            pushText(step.name, out)
        }
        is HclTraversalStep.Index -> {
            pushByte('i'.code, out)
            writeExpressionStructure(step.key, out)
        }
        is HclTraversalStep.AttrSplat -> {
            pushByte('s'.code, out)
            for (inner in step.steps) {
                writeTraversalStep(inner, out)
            }
        }
        is HclTraversalStep.FullSplat -> {
            pushByte('S'.code, out)
            for (inner in step.steps) {
                writeTraversalStep(inner, out)
            }
        }
    }
}

/** Appends the canonical structural serialization of one object key
 * (materialization.rs:1727-1761). */
private fun writeObjectKeyStructure(key: HclObjectKey, out: ArrayList<Byte>) {
    when (key) {
        is HclObjectKey.Identifier -> {
            pushByte('K'.code, out)
            pushText(key.name, out)
        }
        is HclObjectKey.Number -> {
            pushByte('k'.code, out)
            pushText(key.number.canonicalDecimal, out)
        }
        is HclObjectKey.Template -> {
            pushByte('t'.code, out)
            for (part in key.template.parts) {
                when (part) {
                    is HclTemplatePart.Literal -> {
                        pushByte('l'.code, out)
                        pushText(part.text, out)
                    }
                    is HclTemplatePart.Interpolation -> {
                        pushByte('i'.code, out)
                        writeExpressionStructure(part.expression, out)
                    }
                    is HclTemplatePart.Directive -> {
                        pushByte('d'.code, out)
                        writeDirectiveStructure(part.kind, out)
                    }
                }
            }
        }
        is HclObjectKey.Paren -> {
            pushByte('p'.code, out)
            writeExpressionStructure(key.inner, out)
        }
    }
}
