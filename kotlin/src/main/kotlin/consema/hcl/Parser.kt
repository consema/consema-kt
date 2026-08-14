// One deterministic parser pass over the frozen lexer token stream: the
// HCL Native Syntax body/attribute/block/expression/template grammar with
// the deterministic recovery boundaries of RFC 0014 §3.
//
// Data authority:
//   - RFC 0014 §3-§6 (https://github.com/consema/consema/blob/main/docs/rfcs/0014-hcl-family-profiles-v1.md:94-446):
//     Complete/Recovered/FatalFormationFailure; recovery happens only at
//     deterministic boundaries and never asserts unproven semantics; the
//     per-body duplicate-attribute rule makes formation Recovered with the
//     duplicate excluded from the native body; the frozen body grammar
//     (§4.2), expression grammar and precedence (§4.3), templates (§4.4),
//     heredocs (§4.5), and constructors/for-expressions (§4.6); the native
//     semantic model (§6).
//   - https://github.com/consema/consema-rs/blob/main/consema-hcl/src/parser.rs pins the parse structure: parse_body
//     (parser.rs:620-727), parse_attribute (parser.rs:729-767), parse_block
//     (parser.rs:769-864), the expression ladder (parser.rs:982-1259), the
//     term layer (parser.rs:1263-1382), traversal steps (parser.rs:1351-
//     1560), call/tuple/object/paren forms, the recovery scan (parser.rs:
//     533-616), and the limit checks (parser.rs:620-863).
//   - The failure codes are pinned in Errors.kt (parser.rs:80-97).
//   - consema-go/go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design: a single-pass cursor parser over immutable
// tokens; outcomes are sealed result types so exhaustive `when` can never
// meet an unknown outcome; the parser never evaluates anything (hard
// gate 1) — literal-completeness is a purely syntactic predicate
// (Expression.kt).

package consema.hcl

import consema.document.DocumentAuthority
import consema.document.FormationStatus
import consema.document.LosslessStructuralIndex
import consema.document.SourceSnapshot
import consema.document.Span
import consema.document.StructuralPiece
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/** The expression context: at the top level of an attribute newlines
 * terminate the expression; nested contexts ignore newlines as whitespace
 * (RFC 0014 §2, §4.3). */
internal enum class ExprMode {
    Top,
    Nested,
}

/** How the body ends: at end of file or before a closing brace. */
private enum class BodyEnd {
    Eof,
    BraceClose,
}

/** Why an attribute failed to parse (parser.rs:300-305). */
private enum class AttributeFailure(val code: String) {
    /** The `=` sign is missing. */
    MissingEquals(HCL_PARSE_ATTRIBUTE),

    /** The expression after `=` is missing or invalid. */
    MissingExpression(HCL_PARSE_EXPRESSION),
}

/** The outcome of one attribute parse (parser.rs:307-311). */
private sealed class AttributeOutcome {
    data class Formed(val attribute: HclAttribute) : AttributeOutcome()
    data class Failed(val failure: AttributeFailure) : AttributeOutcome()
}

/**
 * One formed native document: the immutable source, the ordered diagnostics,
 * the body tree, the ordered error regions, and the lossless index facts.
 */
internal class HclFormed(
    val source: SourceSnapshot,
    val authority: DocumentAuthority,
    val status: FormationStatus,
    val diagnostics: List<HclDiagnostic>,
    val document: HclBody,
    val errorRegions: List<HclErrorRegion>,
    val pieces: List<StructuralPiece>,
    val kinds: List<HclSyntaxKind>,
    val limits: HclParseLimits,
)

/** Forms one document from the lexed tokens (parser.rs:356-387). */
internal fun parseHcl(
    bytes: ByteArray,
    source: SourceSnapshot,
    authority: DocumentAuthority,
    limits: HclParseLimits,
): HclFormed {
    val lexed = lexSource(bytes, authority, limits)
    val parser = Parser(bytes, source, authority, limits, lexed)
    return parser.parse()
}

/** One deterministic parser pass (parser.rs:314-387). */
private class Parser(
    private val bytes: ByteArray,
    private val source: SourceSnapshot,
    private val authority: DocumentAuthority,
    private val limits: HclParseLimits,
    private val lexed: LexedSource,
) {
    private val tokens: List<HclToken> = lexed.tokens
    private var pos: Int = 0
    private val diagnostics = ArrayList<HclDiagnostic>(lexed.diagnostics)
    private val errorRegions = ArrayList<HclErrorRegion>(lexed.errorRegions)
    private var recovered: Boolean = lexed.recovered
    /** Brackets opened by the expression parser but never closed; taken by
     * the recovery scan when the enclosing item fails (RFC 0014 §3). */
    private val brackets = ArrayList<HclTokenKind>()

    fun parse(): HclFormed {
        val body = parseBody(1, BodyEnd.Eof)
        val status = if (recovered) FormationStatus.Recovered else FormationStatus.Complete
        errorRegions.sortBy { it.span.startByte }
        return HclFormed(
            source = source,
            authority = authority,
            status = status,
            diagnostics = diagnostics,
            document = body,
            errorRegions = errorRegions,
            pieces = lexed.pieces ?: emptyList(),
            kinds = lexed.kinds ?: emptyList(),
            limits = limits,
        )
    }

    // -- token cursor ------------------------------------------------------

    private fun peek(): HclToken = tokens[pos.coerceAtMost(tokens.size - 1)]

    private fun peekKind(): HclTokenKind = peek().kind

    private fun advance(): HclToken {
        val token = peek()
        if (token.kind != HclTokenKind.Eof) {
            pos += 1
        }
        return token
    }

    private fun at(kind: HclTokenKind): Boolean = peekKind() == kind

    private fun eat(kind: HclTokenKind): HclToken? =
        if (at(kind)) advance() else null

    /** Exact token text decoded from the raw bytes at the token's byte
     * span (byte offsets are the frozen raw-byte spans; the decoded text is
     * UTF-8, so decoding the byte range yields the exact token text). */
    private fun text(token: HclToken): String =
        String(bytes.copyOfRange(token.start, token.end), Charsets.UTF_8)

    private fun span(start: Int, end: Int): Span {
        if (start > end || end > source.len) {
            throw coordinatesFailure()
        }
        return try {
            authority.span(start, end)
        } catch (e: consema.document.LocationException) {
            throw coordinatesFailure()
        }
    }

    /** One snapshot-bound span of one token. */
    private fun tokenSpan(token: HclToken): Span = span(token.start, token.end)

    /** Skips whitespace and inline comments (inline comments may span lines
     * but count as whitespace; RFC 0014 §4.1). */
    private fun skipTrivia() {
        while (peekKind() == HclTokenKind.Whitespace || peekKind() == HclTokenKind.InlineComment) {
            pos += 1
        }
    }

    /** Skips all trivia, including newlines and line comments. */
    private fun skipStructural() {
        while (peekKind() == HclTokenKind.Whitespace || peekKind() == HclTokenKind.InlineComment ||
            peekKind() == HclTokenKind.LineBreak || peekKind() == HclTokenKind.LineComment
        ) {
            pos += 1
        }
    }

    private fun skipExpressionTrivia(mode: ExprMode) {
        if (mode == ExprMode.Top) {
            skipTrivia()
        } else {
            skipStructural()
        }
    }

    // -- diagnostics and recovery ------------------------------------------

    /** Records one recovery diagnostic and marks the parse Recovered. */
    private fun diagnose(code: String, span: Span) {
        recovered = true
        diagnostics.add(
            HclDiagnostic(
                code = code,
                category = DiagnosticCategory.Syntax,
                severity = Severity.Error,
                startByte = span.startByte,
                endByte = span.endByte,
                arguments = emptyMap(),
                notes = emptyList(),
                occurrence = 0,
            ),
        )
    }

    /** Emits one error region with its diagnostic; a zero-length region
     * publishes the diagnostic only, never an empty piece (parser.rs:
     * 483-504). */
    private fun emitErrorRegion(start: Int, end: Int, code: String) {
        recovered = true
        val regionSpan = span(start, end)
        diagnostics.add(
            HclDiagnostic(
                code = code,
                category = DiagnosticCategory.Syntax,
                severity = Severity.Error,
                startByte = start,
                endByte = end,
                arguments = emptyMap(),
                notes = emptyList(),
                occurrence = 0,
            ),
        )
        if (end > start) {
            errorRegions.add(HclErrorRegion(regionSpan, code))
            if (errorRegions.size > limits.maxRecoveryRegions) {
                throw limitFailure("recovery-regions", errorRegions.size, limits.maxRecoveryRegions)
            }
            if (errorRegions.size > limits.maxErrorRegions) {
                throw limitFailure("error-regions", errorRegions.size, limits.maxErrorRegions)
            }
        }
    }

    /** Fails one body item: emits the error region from the item start to
     * the deterministic recovery boundary and advances past the region
     * (parser.rs:533-537). */
    private fun failItem(start: Int, code: String) {
        val boundary = scanRecovery(brackets.toList())
        brackets.clear()
        emitErrorRegion(start, boundary, code)
    }

    /** Scans forward from the current token to the recovery boundary and
     * advances `pos` to the boundary token (parser.rs:549-588). */
    private fun scanRecovery(stack: List<HclTokenKind>): Int {
        var remaining = stack.toMutableList()
        loop@ while (true) {
            val token = peek()
            when (token.kind) {
                HclTokenKind.Eof -> return source.len
                HclTokenKind.LineBreak, HclTokenKind.LineComment -> {
                    if (remaining.isEmpty()) {
                        return token.start
                    }
                    pos += 1
                }
                HclTokenKind.BraceOpen, HclTokenKind.BracketOpen, HclTokenKind.ParenOpen -> {
                    remaining.add(token.kind)
                    pos += 1
                }
                HclTokenKind.BraceClose, HclTokenKind.BracketClose, HclTokenKind.ParenClose -> {
                    if (remaining.isEmpty()) {
                        return token.start
                    }
                    if (matchesDelim(remaining.last(), token.kind)) {
                        remaining.removeAt(remaining.size - 1)
                        if (remaining.isEmpty()) {
                            pos += 1
                            return token.end
                        }
                    } else {
                        remaining.removeAt(remaining.size - 1)
                    }
                    pos += 1
                }
                else -> pos += 1
            }
        }
    }

    /** Consumes tokens through the next `}` at brace depth zero and returns
     * its end byte; null at end of file (parser.rs:593-616). */
    private fun scanToCloseBrace(): Int? {
        var braces = 0
        while (true) {
            val token = peek()
            when (token.kind) {
                HclTokenKind.Eof -> return null
                HclTokenKind.BraceOpen -> {
                    braces += 1
                    pos += 1
                }
                HclTokenKind.BraceClose -> {
                    if (braces == 0) {
                        pos += 1
                        return token.end
                    }
                    braces -= 1
                    pos += 1
                }
                else -> pos += 1
            }
        }
    }

    // -- body grammar (parser.rs:620-727) ----------------------------------

    private fun parseBody(depth: Int, end: BodyEnd): HclBody {
        if (depth > limits.maxBodyDepth) {
            throw limitFailure("body-depth", depth, limits.maxBodyDepth)
        }
        val items = ArrayList<HclBodyItem>()
        var attributeCount = 0
        var blockCount = 0
        var itemCount = 0
        val names = HashSet<String>()
        while (true) {
            skipStructural()
            val token = peek()
            when (token.kind) {
                HclTokenKind.Eof -> break
                HclTokenKind.BraceClose -> {
                    if (end == BodyEnd.BraceClose) {
                        break
                    }
                    // An orphan closing delimiter at this body level: it
                    // closes no open construct, so it is consumed with a
                    // diagnostic instead of starting an item.
                    diagnose(HCL_PARSE_ITEM, tokenSpan(token))
                    advance()
                }
                HclTokenKind.Identifier -> {
                    val nameToken = advance()
                    val name = text(nameToken)
                    skipTrivia()
                    when (peekKind()) {
                        HclTokenKind.Equals -> {
                            itemCount += 1
                            attributeCount += 1
                            if (itemCount > limits.maxBodyItemCount) {
                                throw limitFailure("body-item-count", itemCount, limits.maxBodyItemCount)
                            }
                            if (attributeCount > limits.maxAttributeCount) {
                                throw limitFailure("attribute-count", attributeCount, limits.maxAttributeCount)
                            }
                            when (val outcome = parseAttribute(nameToken, name, singleLine = false)) {
                                is AttributeOutcome.Formed -> {
                                    if (names.add(name)) {
                                        items.add(HclBodyItem.Attribute(outcome.attribute))
                                    } else {
                                        // The duplicate stays a proven syntax
                                        // piece but never a native attribute
                                        // (RFC 0014 §3).
                                        diagnose(HCL_PARSE_DUPLICATE_ATTRIBUTE, tokenSpan(nameToken))
                                    }
                                }
                                is AttributeOutcome.Failed -> {
                                    failItem(nameToken.start, outcome.failure.code)
                                }
                            }
                        }
                        HclTokenKind.StringOpen, HclTokenKind.BraceOpen, HclTokenKind.Identifier -> {
                            itemCount += 1
                            blockCount += 1
                            if (itemCount > limits.maxBodyItemCount) {
                                throw limitFailure("body-item-count", itemCount, limits.maxBodyItemCount)
                            }
                            if (blockCount > limits.maxBlockCount) {
                                throw limitFailure("block-count", blockCount, limits.maxBlockCount)
                            }
                            val block = parseBlock(nameToken, depth)
                            if (block != null) {
                                items.add(HclBodyItem.Block(block))
                            }
                        }
                        else -> failItem(nameToken.start, HCL_PARSE_ITEM)
                    }
                }
                else -> {
                    if (token.kind == HclTokenKind.BraceClose ||
                        token.kind == HclTokenKind.BracketClose ||
                        token.kind == HclTokenKind.ParenClose
                    ) {
                        diagnose(HCL_PARSE_ITEM, tokenSpan(token))
                        advance()
                    } else {
                        failItem(token.start, HCL_PARSE_ITEM)
                    }
                }
            }
        }
        return HclBody(items)
    }

    private fun parseAttribute(
        nameToken: HclToken,
        name: String,
        singleLine: Boolean,
    ): AttributeOutcome {
        skipTrivia()
        val equals = eat(HclTokenKind.Equals)
            ?: return AttributeOutcome.Failed(AttributeFailure.MissingEquals)
        skipTrivia()
        val expression = parseExpression(ExprMode.Top, 0)
            ?: return AttributeOutcome.Failed(AttributeFailure.MissingExpression)
        if (!singleLine) {
            skipTrivia()
            when (peekKind()) {
                HclTokenKind.LineBreak, HclTokenKind.LineComment, HclTokenKind.Eof -> Unit
                else -> {
                    // The attribute is proven; only its terminator is
                    // missing (RFC 0014 §2, §12 D-9).
                    diagnose(HCL_PARSE_NEWLINE, tokenSpan(peek()))
                    scanRecovery(emptyList())
                }
            }
        }
        return AttributeOutcome.Formed(
            HclAttribute(
                name = name,
                nameSpan = tokenSpan(nameToken),
                equalsSpan = tokenSpan(equals),
                expression = expression,
            ),
        )
    }

    private fun parseBlock(typeToken: HclToken, depth: Int): HclBlock? {
        val blockStart = typeToken.start
        val blockType = text(typeToken)
        val labels = ArrayList<HclBlockLabel>()
        while (true) {
            skipTrivia()
            when (peekKind()) {
                HclTokenKind.Identifier -> {
                    val token = advance()
                    labels.add(HclBlockLabel(text(token), tokenSpan(token), quoted = false))
                    if (labels.size > limits.maxLabelCount) {
                        throw limitFailure("label-count", labels.size, limits.maxLabelCount)
                    }
                }
                HclTokenKind.StringOpen -> {
                    val label = parseQuotedLabel()
                    if (label == null) {
                        failItem(blockStart, HCL_PARSE_LABEL)
                        return null
                    }
                    labels.add(label)
                    if (labels.size > limits.maxLabelCount) {
                        throw limitFailure("label-count", labels.size, limits.maxLabelCount)
                    }
                }
                HclTokenKind.BraceOpen -> break
                else -> {
                    failItem(blockStart, HCL_PARSE_BLOCK)
                    return null
                }
            }
        }
        advance() // open brace
        skipTrivia()
        val formed: Pair<HclBody, Int>
        when (peekKind()) {
            HclTokenKind.LineBreak, HclTokenKind.LineComment -> {
                skipStructural()
                val body = parseBody(depth + 1, BodyEnd.BraceClose)
                if (at(HclTokenKind.BraceClose)) {
                    val close = advance()
                    formed = body to close.end
                } else {
                    failItem(blockStart, HCL_PARSE_BLOCK)
                    return null
                }
            }
            HclTokenKind.BraceClose -> {
                val close = advance()
                formed = HclBody(emptyList()) to close.end
            }
            HclTokenKind.Eof -> {
                failItem(blockStart, HCL_PARSE_BLOCK)
                return null
            }
            else -> {
                val oneLine = parseOneLineBody(blockStart)
                if (oneLine == null) {
                    return null
                }
                formed = oneLine
            }
        }
        val (body, closeEnd) = formed
        skipTrivia()
        when (peekKind()) {
            HclTokenKind.LineBreak, HclTokenKind.LineComment, HclTokenKind.Eof -> Unit
            else -> {
                diagnose(HCL_PARSE_NEWLINE, tokenSpan(peek()))
                scanRecovery(emptyList())
            }
        }
        return HclBlock(
            blockType = blockType,
            labels = labels,
            body = body,
            span = span(blockStart, closeEnd),
        )
    }

    /** Parses one quoted block label: a quoted literal string without any
     * interpolation or directive sequence (RFC 0014 §4.2). Null when the
     * template is unterminated (already recovered at the lexer) or contains
     * a template sequence. */
    private fun parseQuotedLabel(): HclBlockLabel? {
        val open = advance()
        val labelText = StringBuilder()
        while (true) {
            val token = peek()
            when (token.kind) {
                HclTokenKind.StringContent -> {
                    advance()
                    labelText.append(decodeQuotedLiteral(text(token)))
                }
                HclTokenKind.StringClose -> {
                    val close = advance()
                    return HclBlockLabel(
                        text = labelText.toString(),
                        span = span(open.start, close.end),
                        quoted = true,
                    )
                }
                HclTokenKind.ErrorRegion, HclTokenKind.Eof -> {
                    // Unterminated at the lexer; the lexer already published
                    // its diagnostic.
                    return null
                }
                else -> {
                    diagnose(HCL_PARSE_LABEL, tokenSpan(token))
                    return null
                }
            }
        }
    }

    /** Parses the one-line block body `{ (Identifier "=" Expression)? }`
     * (parser.rs:900-978). */
    private fun parseOneLineBody(blockStart: Int): Pair<HclBody, Int>? {
        if (peekKind() == HclTokenKind.Identifier) {
            val nameToken = peek()
            val name = text(nameToken)
            advance()
            skipTrivia()
            if (at(HclTokenKind.Equals)) {
                when (val outcome = parseAttribute(nameToken, name, singleLine = true)) {
                    is AttributeOutcome.Formed -> {
                        skipTrivia()
                        if (at(HclTokenKind.BraceClose)) {
                            val close = advance()
                            return HclBody(listOf(HclBodyItem.Attribute(outcome.attribute))) to close.end
                        }
                        // The attribute is proven but the brace is missing.
                        diagnose(HCL_PARSE_BLOCK, tokenSpan(peek()))
                        val closeEnd = scanToCloseBrace()
                        if (closeEnd == null) {
                            failItem(blockStart, HCL_PARSE_BLOCK)
                            return null
                        }
                        return HclBody(listOf(HclBodyItem.Attribute(outcome.attribute))) to closeEnd
                    }
                    is AttributeOutcome.Failed -> {
                        failItem(nameToken.start, outcome.failure.code)
                        val closeEnd = scanToCloseBrace()
                        if (closeEnd == null) {
                            failItem(blockStart, HCL_PARSE_BLOCK)
                            return null
                        }
                        return HclBody(emptyList()) to closeEnd
                    }
                }
            }
        }
        diagnose(HCL_PARSE_BLOCK, tokenSpan(peek()))
        val closeEnd = scanToCloseBrace()
        if (closeEnd == null) {
            failItem(blockStart, HCL_PARSE_BLOCK)
            return null
        }
        return HclBody(emptyList()) to closeEnd
    }

    // -- expression grammar (parser.rs:982-1382) ---------------------------

    private fun parseExpression(mode: ExprMode, depth: Int): HclExpression? {
        if (depth >= limits.maxExpressionDepth) {
            throw limitFailure("expression-depth", depth + 1, limits.maxExpressionDepth)
        }
        return parseConditional(mode, depth)
    }

    private fun parseConditional(mode: ExprMode, depth: Int): HclExpression? {
        val condition = parseOr(mode, depth) ?: return null
        skipTrivia()
        if (!at(HclTokenKind.QuestionMark)) {
            return condition
        }
        advance()
        val then = parseConditional(mode, depth + 1) ?: return null
        skipTrivia()
        if (eat(HclTokenKind.Colon) == null) {
            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
            return null
        }
        val elseExpr = parseConditional(mode, depth + 1) ?: return null
        return HclExpression(
            span = span(condition.span.startByte, elseExpr.span.endByte),
            kind = HclExpressionKind.Conditional(condition, then, elseExpr),
        )
    }

    /** One left-associative binary level; the precedence ladder is `||`,
     * `&&`, `==`/`!=`, `<`/`>`/`<=`/`>=`, `+`/`-`, `*`/`/`/`%` (RFC 0014
     * §4.3; parser.rs:1036-1242). The chain length is bounded by the
     * expression depth. */
    private fun parseOr(mode: ExprMode, depth: Int): HclExpression? =
        parseBinaryLevel(mode, depth, { it == HclTokenKind.OpOr }, { HclBinaryOp.Or }, ::parseAnd)

    private fun parseAnd(mode: ExprMode, depth: Int): HclExpression? =
        parseBinaryLevel(mode, depth, { it == HclTokenKind.OpAnd }, { HclBinaryOp.And }, ::parseEquality)

    private fun parseEquality(mode: ExprMode, depth: Int): HclExpression? =
        parseBinaryLevel(mode, depth, { kind ->
            kind == HclTokenKind.OpEqual || kind == HclTokenKind.OpNotEqual
        }, { kind ->
            if (kind == HclTokenKind.OpEqual) HclBinaryOp.Equal else HclBinaryOp.NotEqual
        }, ::parseRelational)

    private fun parseRelational(mode: ExprMode, depth: Int): HclExpression? =
        parseBinaryLevel(mode, depth, { kind ->
            kind == HclTokenKind.OpLess || kind == HclTokenKind.OpGreater ||
                kind == HclTokenKind.OpLessEqual || kind == HclTokenKind.OpGreaterEqual
        }, { kind ->
            when (kind) {
                HclTokenKind.OpLess -> HclBinaryOp.Less
                HclTokenKind.OpGreater -> HclBinaryOp.Greater
                HclTokenKind.OpLessEqual -> HclBinaryOp.LessEqual
                else -> HclBinaryOp.GreaterEqual
            }
        }, ::parseAdditive)

    private fun parseAdditive(mode: ExprMode, depth: Int): HclExpression? =
        parseBinaryLevel(mode, depth, { kind ->
            kind == HclTokenKind.OpAdd || kind == HclTokenKind.OpSubtract
        }, { kind ->
            if (kind == HclTokenKind.OpAdd) HclBinaryOp.Add else HclBinaryOp.Subtract
        }, ::parseMultiplicative)

    private fun parseMultiplicative(mode: ExprMode, depth: Int): HclExpression? =
        parseBinaryLevel(mode, depth, { kind ->
            kind == HclTokenKind.Star || kind == HclTokenKind.OpDivide ||
                kind == HclTokenKind.OpModulo
        }, { kind ->
            when (kind) {
                HclTokenKind.Star -> HclBinaryOp.Multiply
                HclTokenKind.OpDivide -> HclBinaryOp.Divide
                else -> HclBinaryOp.Modulo
            }
        }, { modeInner, depthInner -> parseTerm(modeInner, depthInner) })

    private inline fun parseBinaryLevel(
        mode: ExprMode,
        depth: Int,
        matches: (HclTokenKind) -> Boolean,
        resolve: (HclTokenKind) -> HclBinaryOp,
        lower: (ExprMode, Int) -> HclExpression?,
    ): HclExpression? {
        var lhs = lower(mode, depth) ?: return null
        var chain = 0
        while (true) {
            skipTrivia()
            val kind = peekKind()
            if (!matches(kind)) {
                break
            }
            chain += 1
            if (chain > limits.maxExpressionDepth) {
                throw limitFailure("expression-depth", chain, limits.maxExpressionDepth)
            }
            advance()
            skipExpressionTrivia(mode)
            val rhs = lower(mode, depth) ?: return null
            lhs = HclExpression(
                span = span(lhs.span.startByte, rhs.span.endByte),
                kind = HclExpressionKind.Binary(resolve(kind), lhs, rhs),
            )
        }
        return lhs
    }

    /** The term layer: unary chains over the base term and its postfix
     * traversal steps (RFC 0014 §4.3; parser.rs:1263-1382). */
    private fun parseTerm(mode: ExprMode, depth: Int): HclExpression? {
        if (depth >= limits.maxExpressionDepth) {
            throw limitFailure("expression-depth", depth + 1, limits.maxExpressionDepth)
        }
        skipExpressionTrivia(mode)
        val token = peek()
        return when (token.kind) {
            HclTokenKind.OpSubtract, HclTokenKind.OpNot -> {
                val opToken = advance()
                val op = if (opToken.kind == HclTokenKind.OpSubtract) {
                    HclUnaryOp.Minus
                } else {
                    HclUnaryOp.Not
                }
                val operand = parseTerm(mode, depth + 1) ?: return null
                HclExpression(
                    span = span(opToken.start, operand.span.endByte),
                    kind = HclExpressionKind.Unary(op, operand),
                )
            }
            HclTokenKind.Number -> {
                val numberToken = advance()
                HclExpression(
                    span = tokenSpan(numberToken),
                    kind = HclExpressionKind.Number(number(numberToken)),
                )
            }
            HclTokenKind.StringOpen -> parseQuotedTemplate(depth)
            HclTokenKind.HeredocOpen -> parseHeredoc(depth)
            HclTokenKind.ParenOpen -> parseParen(depth)
            HclTokenKind.BracketOpen -> parseBracket(depth)
            HclTokenKind.BraceOpen -> parseBrace(depth)
            HclTokenKind.Identifier -> parseIdentifierTerm(mode, depth)
            else -> {
                diagnose(HCL_PARSE_EXPRESSION, tokenSpan(token))
                null
            }
        }
    }

    private fun number(token: HclToken): HclNumber {
        val spelling = text(token)
        val canonical = canonicalDecimalBounded(spelling, limits.maxNumberDigits)
            ?: throw limitFailure("number-digits", Int.MAX_VALUE, limits.maxNumberDigits)
        val digits = canonical.count { it.isDigit() }
        if (digits > limits.maxNumberDigits) {
            throw limitFailure("number-digits", digits, limits.maxNumberDigits)
        }
        return HclNumber(tokenSpan(token), canonical)
    }

    /** One identifier term: a variable reference, a keyword literal, a
     * function call, or a static traversal (RFC 0014 §4.1, §4.3;
     * parser.rs:1351-1560). */
    private fun parseIdentifierTerm(mode: ExprMode, depth: Int): HclExpression? {
        val nameToken = peek()
        val name = text(nameToken)
        advance()
        skipExpressionTrivia(mode)
        if (at(HclTokenKind.ParenOpen)) {
            return parseCall(nameToken, depth)
        }
        val base: HclExpressionKind = when (name) {
            "true" -> HclExpressionKind.Boolean(true)
            "false" -> HclExpressionKind.Boolean(false)
            "null" -> HclExpressionKind.Null
            else -> HclExpressionKind.VariableRef(name)
        }
        val steps = ArrayList<HclTraversalStep>()
        var end = nameToken.end
        while (true) {
            skipExpressionTrivia(mode)
            when (peekKind()) {
                HclTokenKind.Dot -> {
                    val dot = advance()
                    skipExpressionTrivia(mode)
                    when (peekKind()) {
                        HclTokenKind.Identifier -> {
                            val ident = advance()
                            steps.add(
                                HclTraversalStep.GetAttr(
                                    name = text(ident),
                                    span = span(dot.start, ident.end),
                                ),
                            )
                            end = ident.end
                        }
                        HclTokenKind.Star -> {
                            // Attribute splat `. * GetAttr*`.
                            val star = advance()
                            end = star.end
                            val nested = ArrayList<HclTraversalStep>()
                            while (true) {
                                skipExpressionTrivia(mode)
                                if (!at(HclTokenKind.Dot)) {
                                    break
                                }
                                val ndot = advance()
                                skipExpressionTrivia(mode)
                                if (!at(HclTokenKind.Identifier)) {
                                    diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                                    return null
                                }
                                val nident = advance()
                                nested.add(
                                    HclTraversalStep.GetAttr(
                                        name = text(nident),
                                        span = span(ndot.start, nident.end),
                                    ),
                                )
                                end = nident.end
                            }
                            steps.add(HclTraversalStep.AttrSplat(nested))
                        }
                        else -> {
                            // D-5: `foo.0` is rejected — `GetAttr = "."
                            // Identifier` admits identifiers only (RFC 0014
                            // §12).
                            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                            return null
                        }
                    }
                }
                HclTokenKind.BracketOpen -> {
                    brackets.add(HclTokenKind.BracketOpen)
                    val open = advance()
                    skipStructural()
                    if (at(HclTokenKind.Star)) {
                        // Full splat `[ * ] (GetAttr | Index)*`.
                        advance()
                        skipStructural()
                        if (!at(HclTokenKind.BracketClose)) {
                            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                            return null
                        }
                        val close = advance()
                        end = close.end
                        val nested = ArrayList<HclTraversalStep>()
                        while (true) {
                            skipExpressionTrivia(mode)
                            if (at(HclTokenKind.Dot)) {
                                val dot = advance()
                                skipExpressionTrivia(mode)
                                if (!at(HclTokenKind.Identifier)) {
                                    diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                                    return null
                                }
                                val ident = advance()
                                nested.add(
                                    HclTraversalStep.GetAttr(
                                        name = text(ident),
                                        span = span(dot.start, ident.end),
                                    ),
                                )
                                end = ident.end
                            } else if (at(HclTokenKind.BracketOpen)) {
                                val indexOpen = advance()
                                brackets.add(HclTokenKind.BracketOpen)
                                skipStructural()
                                val key = parseExpression(ExprMode.Nested, depth + 1) ?: return null
                                skipStructural()
                                if (!at(HclTokenKind.BracketClose)) {
                                    diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                                    return null
                                }
                                val indexClose = advance()
                                brackets.removeAt(brackets.size - 1)
                                nested.add(
                                    HclTraversalStep.Index(
                                        key = key,
                                        span = span(indexOpen.start, indexClose.end),
                                    ),
                                )
                                end = indexClose.end
                            } else {
                                break
                            }
                        }
                        steps.add(HclTraversalStep.FullSplat(nested))
                        brackets.removeAt(brackets.size - 1)
                    } else {
                        // Index step `[ Expression ]`.
                        val key = parseExpression(ExprMode.Nested, depth + 1) ?: return null
                        skipStructural()
                        if (!at(HclTokenKind.BracketClose)) {
                            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                            return null
                        }
                        val close = advance()
                        brackets.removeAt(brackets.size - 1)
                        steps.add(
                            HclTraversalStep.Index(
                                key = key,
                                span = span(open.start, close.end),
                            ),
                        )
                        end = close.end
                    }
                }
                else -> break
            }
        }
        val kind = if (steps.isEmpty()) {
            base
        } else {
            val root = when (base) {
                is HclExpressionKind.Boolean -> HclTraversalRoot.Boolean(base.value)
                is HclExpressionKind.Null -> HclTraversalRoot.Null
                is HclExpressionKind.VariableRef -> HclTraversalRoot.Variable(base.name)
                else -> error("identifier term base kinds are closed")
            }
            HclExpressionKind.Traversal(root, steps)
        }
        return HclExpression(span = span(nameToken.start, end), kind = kind)
    }

    /** One function call `name(args)`; the name is a plain identifier only
     * (RFC 0014 §4.3; parser.rs:1561-1685). */
    private fun parseCall(nameToken: HclToken, depth: Int): HclExpression? {
        brackets.add(HclTokenKind.ParenOpen)
        advance() // open paren
        skipStructural()
        val args = ArrayList<HclCallArg>()
        if (at(HclTokenKind.ParenClose)) {
            val close = advance()
            brackets.removeAt(brackets.size - 1)
            return HclExpression(
                span = span(nameToken.start, close.end),
                kind = HclExpressionKind.FunctionCall(text(nameToken), tokenSpan(nameToken), args),
            )
        }
        while (true) {
            val expression = parseExpression(ExprMode.Nested, depth + 1) ?: return null
            var expand = false
            skipStructural()
            if (at(HclTokenKind.Ellipsis)) {
                // `...` expansion marker; only the final argument may carry
                // it.
                advance()
                expand = true
            }
            args.add(HclCallArg(expression, expand))
            skipStructural()
            when (peekKind()) {
                HclTokenKind.Comma -> {
                    advance()
                    skipStructural()
                    if (at(HclTokenKind.ParenClose)) {
                        val close = advance()
                        brackets.removeAt(brackets.size - 1)
                        return HclExpression(
                            span = span(nameToken.start, close.end),
                            kind = HclExpressionKind.FunctionCall(text(nameToken), tokenSpan(nameToken), args),
                        )
                    }
                }
                HclTokenKind.ParenClose -> {
                    val close = advance()
                    brackets.removeAt(brackets.size - 1)
                    return HclExpression(
                        span = span(nameToken.start, close.end),
                        kind = HclExpressionKind.FunctionCall(text(nameToken), tokenSpan(nameToken), args),
                    )
                }
                else -> {
                    diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                    return null
                }
            }
        }
    }

    /** A parenthesized expression; parentheses ignore newlines as
     * whitespace (RFC 0014 §4.3). */
    private fun parseParen(depth: Int): HclExpression? {
        brackets.add(HclTokenKind.ParenOpen)
        val open = advance()
        skipStructural()
        val inner = parseExpression(ExprMode.Nested, depth + 1) ?: return null
        skipStructural()
        if (!at(HclTokenKind.ParenClose)) {
            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
            return null
        }
        val close = advance()
        brackets.removeAt(brackets.size - 1)
        return HclExpression(
            span = span(open.start, close.end),
            kind = HclExpressionKind.Paren(inner),
        )
    }

    /** A tuple constructor or tuple for-expression (RFC 0014 §4.6). */
    private fun parseBracket(depth: Int): HclExpression? {
        brackets.add(HclTokenKind.BracketOpen)
        val open = advance()
        skipStructural()
        if (at(HclTokenKind.Identifier) && text(peek()) == "for") {
            // The for-expression interpretation has priority over a first
            // tuple element spelled `for` (RFC 0014 §4.6).
            val forStart = peek().start
            val intro = parseForIntro(depth + 1) ?: return null
            val value = parseExpression(ExprMode.Nested, depth + 1) ?: return null
            var condition: HclExpression? = null
            skipStructural()
            if (at(HclTokenKind.Identifier) && text(peek()) == "if") {
                advance()
                condition = parseExpression(ExprMode.Nested, depth + 1) ?: return null
                skipStructural()
            }
            if (!at(HclTokenKind.BracketClose)) {
                diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                return null
            }
            val close = advance()
            brackets.removeAt(brackets.size - 1)
            val extent = close.end - forStart
            if (extent > limits.maxForExtent) {
                throw limitFailure("for-extent", extent, limits.maxForExtent)
            }
            return HclExpression(
                span = span(open.start, close.end),
                kind = HclExpressionKind.ForTuple(intro, value, condition),
            )
        }
        val elements = ArrayList<HclExpression>()
        if (at(HclTokenKind.BracketClose)) {
            val close = advance()
            brackets.removeAt(brackets.size - 1)
            return HclExpression(
                span = span(open.start, close.end),
                kind = HclExpressionKind.Tuple(elements),
            )
        }
        while (true) {
            val element = parseExpression(ExprMode.Nested, depth + 1) ?: return null
            elements.add(element)
            if (elements.size > limits.maxTupleElements) {
                throw limitFailure("tuple-elements", elements.size, limits.maxTupleElements)
            }
            skipStructural()
            when (peekKind()) {
                HclTokenKind.Comma -> {
                    advance()
                    skipStructural()
                    if (at(HclTokenKind.BracketClose)) {
                        val close = advance()
                        brackets.removeAt(brackets.size - 1)
                        return HclExpression(
                            span = span(open.start, close.end),
                            kind = HclExpressionKind.Tuple(elements),
                        )
                    }
                }
                HclTokenKind.BracketClose -> {
                    val close = advance()
                    brackets.removeAt(brackets.size - 1)
                    return HclExpression(
                        span = span(open.start, close.end),
                        kind = HclExpressionKind.Tuple(elements),
                    )
                }
                else -> {
                    // A newline (or line comment) separates tuple elements
                    // without a comma (RFC 0014 §4.6).
                    continue
                }
            }
        }
    }

    /** An object constructor or object for-expression (RFC 0014 §4.6). */
    private fun parseBrace(depth: Int): HclExpression? {
        brackets.add(HclTokenKind.BraceOpen)
        val open = advance()
        skipStructural()
        if (at(HclTokenKind.Identifier) && text(peek()) == "for") {
            val forStart = peek().start
            val intro = parseForIntro(depth + 1) ?: return null
            val key = parseExpression(ExprMode.Nested, depth + 1) ?: return null
            skipStructural()
            if (!at(HclTokenKind.Arrow)) {
                diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                return null
            }
            advance()
            val value = parseExpression(ExprMode.Nested, depth + 1) ?: return null
            var grouping = false
            skipStructural()
            if (at(HclTokenKind.Ellipsis)) {
                advance()
                grouping = true
                skipStructural()
            }
            var condition: HclExpression? = null
            if (at(HclTokenKind.Identifier) && text(peek()) == "if") {
                advance()
                condition = parseExpression(ExprMode.Nested, depth + 1) ?: return null
                skipStructural()
            }
            if (!at(HclTokenKind.BraceClose)) {
                diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                return null
            }
            val close = advance()
            brackets.removeAt(brackets.size - 1)
            val extent = close.end - forStart
            if (extent > limits.maxForExtent) {
                throw limitFailure("for-extent", extent, limits.maxForExtent)
            }
            return HclExpression(
                span = span(open.start, close.end),
                kind = HclExpressionKind.ForObject(intro, key, value, grouping, condition),
            )
        }
        val entries = ArrayList<HclObjectEntry>()
        if (at(HclTokenKind.BraceClose)) {
            val close = advance()
            brackets.removeAt(brackets.size - 1)
            return HclExpression(
                span = span(open.start, close.end),
                kind = HclExpressionKind.Object(entries),
            )
        }
        while (true) {
            val key = parseObjectKey(depth + 1) ?: return null
            skipTrivia()
            val separator = when (peekKind()) {
                HclTokenKind.Equals -> HclObjectSeparator.Equals
                HclTokenKind.Colon -> HclObjectSeparator.Colon
                else -> {
                    diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                    return null
                }
            }
            advance()
            skipExpressionTrivia(ExprMode.Nested)
            val value = parseExpression(ExprMode.Nested, depth + 1) ?: return null
            entries.add(HclObjectEntry(key, separator, value))
            if (entries.size > limits.maxObjectEntries) {
                throw limitFailure("object-entries", entries.size, limits.maxObjectEntries)
            }
            skipStructural()
            when (peekKind()) {
                HclTokenKind.Comma -> {
                    advance()
                    skipStructural()
                    if (at(HclTokenKind.BraceClose)) {
                        val close = advance()
                        brackets.removeAt(brackets.size - 1)
                        return HclExpression(
                            span = span(open.start, close.end),
                            kind = HclExpressionKind.Object(entries),
                        )
                    }
                }
                HclTokenKind.BraceClose -> {
                    val close = advance()
                    brackets.removeAt(brackets.size - 1)
                    return HclExpression(
                        span = span(open.start, close.end),
                        kind = HclExpressionKind.Object(entries),
                    )
                }
                else -> {
                    // A newline (or line comment) separates object entries
                    // without a comma (RFC 0014 §4.6).
                    continue
                }
            }
        }
    }

    /** One object-constructor key: an identifier (literal name), a number
     * literal, a quoted template, or a parenthesized expression (RFC 0014
     * §4.6). */
    private fun parseObjectKey(depth: Int): HclObjectKey? {
        skipExpressionTrivia(ExprMode.Nested)
        return when (peekKind()) {
            HclTokenKind.Identifier -> HclObjectKey.Identifier(text(advance()))
            HclTokenKind.Number -> {
                val token = advance()
                HclObjectKey.Number(number(token))
            }
            HclTokenKind.StringOpen -> {
                val open = advance()
                val parsed = parseQuotedTemplateParts(depth)
                if (parsed == null) {
                    null
                } else {
                    HclObjectKey.Template(
                        HclTemplateKey(parsed.first, span(open.start, parsed.second)),
                    )
                }
            }
            HclTokenKind.ParenOpen -> {
                brackets.add(HclTokenKind.ParenOpen)
                val open = advance()
                skipStructural()
                val inner = parseExpression(ExprMode.Nested, depth + 1) ?: return null
                skipStructural()
                if (!at(HclTokenKind.ParenClose)) {
                    diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                    return null
                }
                val close = advance()
                brackets.removeAt(brackets.size - 1)
                HclObjectKey.Paren(
                    HclExpression(span = span(open.start, close.end), kind = HclExpressionKind.Paren(inner)),
                )
            }
            else -> {
                diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                null
            }
        }
    }

    /** The `for` introduction `for Identifier (, Identifier)? in Expression
     * :` (RFC 0014 §4.6). */
    private fun parseForIntro(depth: Int): HclForIntro? {
        val forStart = peek().start
        advance() // `for`
        skipStructural()
        if (!at(HclTokenKind.Identifier)) {
            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
            return null
        }
        val first = text(advance())
        var key: String? = null
        var value = first
        skipStructural()
        if (at(HclTokenKind.Comma)) {
            advance()
            skipStructural()
            if (!at(HclTokenKind.Identifier)) {
                diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                return null
            }
            key = first
            value = text(advance())
            skipStructural()
        }
        if (!at(HclTokenKind.Identifier) || text(peek()) != "in") {
            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
            return null
        }
        advance()
        val collection = parseExpression(ExprMode.Nested, depth + 1) ?: return null
        skipStructural()
        if (!at(HclTokenKind.Colon)) {
            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
            return null
        }
        val colon = advance()
        return HclForIntro(
            key = key,
            value = value,
            collection = collection,
            span = span(forStart, colon.end),
        )
    }

    /** A quoted template with ordered parts (RFC 0014 §4.4). */
    private fun parseQuotedTemplate(depth: Int): HclExpression? {
        val open = advance()
        val parsed = parseQuotedTemplateParts(depth)
        if (parsed == null) {
            return null
        }
        return HclExpression(
            span = span(open.start, parsed.second),
            kind = HclExpressionKind.Template(parsed.first, heredoc = null),
        )
    }

    /** Consumes the interior of an opened quoted template and returns its
     * ordered parts; null when the template is unterminated (already
     * recovered at the lexer). */
    private fun parseQuotedTemplateParts(depth: Int): Pair<List<HclTemplatePart>, Int>? {
        val parts = ArrayList<HclTemplatePart>()
        while (true) {
            when (peekKind()) {
                HclTokenKind.StringContent -> {
                    val token = advance()
                    parts.add(
                        HclTemplatePart.Literal(
                            span = tokenSpan(token),
                            text = decodeQuotedLiteral(text(token)),
                        ),
                    )
                }
                HclTokenKind.InterpolationOpen -> {
                    parts.add(parseInterpolationPart(depth) ?: return null)
                }
                HclTokenKind.DirectiveOpen -> {
                    parts.add(parseDirectivePart(depth) ?: return null)
                }
                HclTokenKind.StringClose -> {
                    val close = advance()
                    return parts to close.end
                }
                HclTokenKind.ErrorRegion, HclTokenKind.Eof -> {
                    // Unterminated at the lexer; the lexer already published
                    // its diagnostic.
                    return null
                }
                else -> {
                    diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                    return null
                }
            }
        }
    }

    /** One interpolation `${ Expression }` part, including the strip-marker
     * span facts (RFC 0014 §4.4). */
    private fun parseInterpolationPart(depth: Int): HclTemplatePart? {
        val open = advance()
        val content = peek()
        if (content.kind != HclTokenKind.InterpolationContent) {
            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(content))
            return null
        }
        advance()
        val expression = parseRegionExpression(content.start, content.end, depth + 1)
        if (expression == null) {
            return null
        }
        val close = peek()
        if (close.kind != HclTokenKind.InterpolationClose) {
            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(close))
            return null
        }
        advance()
        return HclTemplatePart.Interpolation(
            span = span(open.start, close.end),
            expression = expression,
        )
    }

    /** One directive `%{ if }`/`%{ else }`/`%{ endif }`/`%{ for }`/
     * `%{ endfor }` part (RFC 0014 §4.4). */
    private fun parseDirectivePart(depth: Int): HclTemplatePart? {
        val open = advance()
        val content = peek()
        if (content.kind != HclTokenKind.DirectiveContent) {
            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(content))
            return null
        }
        advance()
        val kind = parseDirectiveKind(content.start, content.end, depth + 1)
        if (kind == null) {
            return null
        }
        val close = peek()
        if (close.kind != HclTokenKind.DirectiveClose) {
            diagnose(HCL_PARSE_EXPRESSION, tokenSpan(close))
            return null
        }
        advance()
        return HclTemplatePart.Directive(
            span = span(open.start, close.end),
            kind = kind,
        )
    }

    /** Parses one directive interior region (parser.rs:1930-2010). */
    private fun parseDirectiveKind(start: Int, end: Int, depth: Int): HclDirectiveKind? {
        val region = lexRegion(bytes, authority, start, end, limits)
        mergeRegion(region)
        val parser = RegionParser(bytes, source, authority, limits, region)
        parser.skipStructural()
        val head = parser.peek()
        if (head.kind != HclTokenKind.Identifier) {
            diagnose(HCL_PARSE_DIRECTIVE, tokenSpan(head))
            return null
        }
        return when (parser.text(head)) {
            "if" -> {
                parser.advance()
                val condition = parser.parseRegionExpression() ?: run {
                    diagnose(HCL_PARSE_DIRECTIVE, tokenSpan(head))
                    null
                } ?: return null
                HclDirectiveKind.If(condition)
            }
            "else" -> {
                parser.advance()
                HclDirectiveKind.Else
            }
            "endif" -> {
                parser.advance()
                HclDirectiveKind.EndIf
            }
            "for" -> {
                parser.advance()
                val intro = parser.parseForIntro() ?: run {
                    diagnose(HCL_PARSE_DIRECTIVE, tokenSpan(head))
                    null
                } ?: return null
                HclDirectiveKind.For(intro)
            }
            "endfor" -> {
                parser.advance()
                HclDirectiveKind.EndFor
            }
            else -> {
                diagnose(HCL_PARSE_DIRECTIVE, tokenSpan(head))
                null
            }
        }
    }

    /** A heredoc template with its representation facts (RFC 0014 §4.5). */
    private fun parseHeredoc(depth: Int): HclExpression? {
        val open = advance()
        val openSpan = tokenSpan(open)
        val heredocText = text(open)
        val mode = if (heredocText.startsWith("<<-")) {
            HclHeredocMode.StripIndent
        } else {
            HclHeredocMode.Plain
        }
        val markerStart = open.start + if (mode == HclHeredocMode.StripIndent) 3 else 2
        // `heredocText` is the decoded text of the whole open token
        // `<<MARKER` / `<<-MARKER`; the marker is the token text after the
        // `<<` / `<<-` prefix (byte offsets relative to the token start).
        val marker = heredocText.substring(markerStart - open.start, open.end - open.start)
        val parts = ArrayList<HclTemplatePart>()
        var closingSpan: Span? = null
        while (true) {
            when (peekKind()) {
                HclTokenKind.HeredocContent -> {
                    val token = advance()
                    parts.addAll(
                        scanHeredocContentParts(token.start, token.end, depth + 1)
                            ?: return null,
                    )
                }
                HclTokenKind.InterpolationOpen -> {
                    parts.add(parseInterpolationPart(depth) ?: return null)
                }
                HclTokenKind.DirectiveOpen -> {
                    parts.add(parseDirectivePart(depth) ?: return null)
                }
                HclTokenKind.LineBreak, HclTokenKind.Whitespace -> {
                    // Heredoc content lines are separated by LineBreak
                    // pieces; the newline joins the preceding literal line
                    // in the template's literal value (RFC 0014 §4.5).
                    if (peekKind() == HclTokenKind.LineBreak && parts.isNotEmpty()) {
                        val last = parts[parts.size - 1]
                        if (last is HclTemplatePart.Literal) {
                            parts[parts.size - 1] = last.copy(text = last.text + "\n")
                        }
                    }
                    advance()
                }
                HclTokenKind.HeredocClose -> {
                    val close = advance()
                    closingSpan = tokenSpan(close)
                    return HclExpression(
                        span = span(open.start, close.end),
                        kind = HclExpressionKind.Template(
                            parts,
                            HclHeredocFacts(mode, marker, openSpan, closingSpan),
                        ),
                    )
                }
                HclTokenKind.ErrorRegion, HclTokenKind.Eof -> {
                    // Unterminated at the lexer; the lexer already published
                    // its diagnostic.
                    return null
                }
                else -> {
                    diagnose(HCL_PARSE_EXPRESSION, tokenSpan(peek()))
                    return null
                }
            }
        }
    }

    /** Scans one heredoc content line region for template sequences and
     * produces its ordered parts (RFC 0014 §4.5). */
    private fun scanHeredocContentParts(start: Int, end: Int, depth: Int): List<HclTemplatePart>? {
        val parts = ArrayList<HclTemplatePart>()
        var cursor = start
        while (cursor < end) {
            if (cursor + 2 <= end && bytes[cursor] == '$'.code.toByte() &&
                bytes[cursor + 1] == '{'.code.toByte()
            ) {
                // An interpolation inside heredoc content: the interior is
                // `[cursor + 2, close)`. Since the lexer emits heredoc
                // content lines as single pieces, the sequence is re-scanned
                // here.
                val close = findTemplateClose(cursor + 2, end, directive = false)
                    ?: run {
                        diagnose(HCL_PARSE_UNTERMINATED_INTERPOLATION, span(cursor, end))
                        null
                    } ?: return null
                val expression = parseRegionExpression(cursor + 2, close, depth + 1)
                if (expression == null) {
                    return null
                }
                parts.add(
                    HclTemplatePart.Interpolation(
                        span = span(cursor, close + 1),
                        expression = expression,
                    ),
                )
                cursor = close + 1
            } else if (cursor + 2 <= end && bytes[cursor] == '%'.code.toByte() &&
                bytes[cursor + 1] == '{'.code.toByte()
            ) {
                val close = findTemplateClose(cursor + 2, end, directive = true)
                    ?: run {
                        diagnose(HCL_PARSE_UNTERMINATED_DIRECTIVE, span(cursor, end))
                        null
                    } ?: return null
                val kind = parseDirectiveKind(cursor + 2, close, depth + 1)
                if (kind == null) {
                    return null
                }
                parts.add(HclTemplatePart.Directive(span = span(cursor, close + 1), kind = kind))
                cursor = close + 1
            } else if (cursor + 2 <= end && bytes[cursor] == '$'.code.toByte() &&
                bytes[cursor + 1] == '$'.code.toByte() && bytes[cursor + 2] == '{'.code.toByte()
            ) {
                // `$${` is escaped literal `${` text.
                parts.add(
                    HclTemplatePart.Literal(
                        span = span(cursor, cursor + 3),
                        text = "\$" + "{",
                    ),
                )
                cursor += 3
            } else if (cursor + 2 <= end && bytes[cursor] == '%'.code.toByte() &&
                bytes[cursor + 1] == '%'.code.toByte() && bytes[cursor + 2] == '{'.code.toByte()
            ) {
                parts.add(
                    HclTemplatePart.Literal(
                        span = span(cursor, cursor + 3),
                        text = "%{",
                    ),
                )
                cursor += 3
            } else {
                val literalStart = cursor
                while (cursor < end) {
                    if (bytes[cursor] == '$'.code.toByte() || bytes[cursor] == '%'.code.toByte()) {
                        break
                    }
                    cursor += 1
                }
                parts.add(
                    HclTemplatePart.Literal(
                        span = span(literalStart, cursor),
                        text = String(bytes.copyOfRange(literalStart, cursor), Charsets.UTF_8),
                    ),
                )
            }
        }
        return parts
    }

    /** Finds the `}` (or `~}`) closing an interpolation/directive interior
     * at brace depth zero within `[start, end)`. */
    private fun findTemplateClose(start: Int, end: Int, directive: Boolean): Int? {
        var depth = 0
        var cursor = start
        while (cursor < end) {
            when (bytes[cursor]) {
                '{'.code.toByte() -> depth += 1
                '}'.code.toByte() -> {
                    if (depth == 0) {
                        return cursor
                    }
                    depth -= 1
                }
                '~'.code.toByte() -> {
                    if (depth == 0 && cursor + 1 < end && bytes[cursor + 1] == '}'.code.toByte()) {
                        return cursor
                    }
                }
                else -> Unit
            }
            cursor += 1
        }
        return null
    }

    /** Parses one expression from an interpolation interior region, merging
     * the region's diagnostics and error regions into this parse
     * (parser.rs:2220-2266). */
    private fun parseRegionExpression(start: Int, end: Int, depth: Int): HclExpression? {
        if (depth >= limits.maxExpressionDepth) {
            throw limitFailure("expression-depth", depth + 1, limits.maxExpressionDepth)
        }
        val region = lexRegion(bytes, authority, start, end, limits)
        mergeRegion(region)
        val parser = RegionParser(bytes, source, authority, limits, region)
        return parser.parseRegionExpression()
    }

    private fun mergeRegion(region: LexedSource) {
        if (region.recovered) {
            recovered = true
        }
        diagnostics.addAll(region.diagnostics)
        errorRegions.addAll(region.errorRegions)
        if (errorRegions.size > limits.maxRecoveryRegions) {
            throw limitFailure("recovery-regions", errorRegions.size, limits.maxRecoveryRegions)
        }
        if (errorRegions.size > limits.maxErrorRegions) {
            throw limitFailure("error-regions", errorRegions.size, limits.maxErrorRegions)
        }
    }

    /** One fatal limit failure; no partial Document exists (RFC 0014 §11,
     * hard gate 4). */
    private fun limitFailure(name: String, observed: Int, limit: Int): HclFormationException =
        HclFormationException(
            listOf(hclLimitDiagnostic(limitCode(name), name, observed, limit)),
        )

    /** The frozen `hcl.limit.*@1` code of one limit name (parser.rs:4074-
     * 4330). */
    private fun limitCode(name: String): String = when (name) {
        "expression-depth" -> HCL_LIMIT_EXPRESSION_DEPTH
        "body-depth" -> HCL_LIMIT_BODY_DEPTH
        "template-depth" -> HCL_LIMIT_TEMPLATE_DEPTH
        "attribute-count" -> HCL_LIMIT_ATTRIBUTE_COUNT
        "block-count" -> HCL_LIMIT_BLOCK_COUNT
        "body-item-count" -> HCL_LIMIT_BODY_ITEM_COUNT
        "label-count" -> HCL_LIMIT_LABEL_COUNT
        "number-digits" -> HCL_LIMIT_NUMBER_DIGITS
        "tuple-elements" -> HCL_LIMIT_TUPLE_ELEMENTS
        "object-entries" -> HCL_LIMIT_OBJECT_ENTRIES
        "for-extent" -> HCL_LIMIT_FOR_EXTENT
        "recovery-regions" -> HCL_LIMIT_RECOVERY_REGIONS
        "error-regions" -> HCL_LIMIT_ERROR_REGIONS
        else -> HCL_LIMIT_OFFSET_OVERFLOW
    }

    private fun coordinatesFailure(): HclFormationException =
        HclFormationException(
            listOf(
                HclDiagnostic(
                    code = HCL_PARSE_COORDINATES,
                    category = DiagnosticCategory.Syntax,
                    severity = Severity.Error,
                    startByte = null,
                    endByte = null,
                    arguments = emptyMap(),
                    notes = emptyList(),
                    occurrence = 0,
                ),
            ),
        )

    private fun matchesDelim(open: HclTokenKind, close: HclTokenKind): Boolean =
        (open == HclTokenKind.BraceOpen && close == HclTokenKind.BraceClose) ||
            (open == HclTokenKind.BracketOpen && close == HclTokenKind.BracketClose) ||
            (open == HclTokenKind.ParenOpen && close == HclTokenKind.ParenClose)
}

/**
 * One expression/directive parser over a bounded region token stream; the
 * region tokens carry exact spans bound to the same authority but do not
 * form a source-covering index (lexer.rs:316-333).
 */
private class RegionParser(
    private val bytes: ByteArray,
    private val source: SourceSnapshot,
    private val authority: DocumentAuthority,
    private val limits: HclParseLimits,
    private val region: LexedSource,
) {
    private val tokens: List<HclToken> = region.tokens
    private var pos: Int = 0

    /** One snapshot-bound span of one token. */
    private fun tokenSpan(token: HclToken): Span = span(token.start, token.end)

    fun peek(): HclToken = tokens[pos.coerceAtMost(tokens.size - 1)]

    fun advance(): HclToken {
        val token = peek()
        if (token.kind != HclTokenKind.Eof) {
            pos += 1
        }
        return token
    }

    fun text(token: HclToken): String =
        String(bytes.copyOfRange(token.start, token.end), Charsets.UTF_8)

    private fun at(kind: HclTokenKind): Boolean = peek().kind == kind

    internal fun skipStructural() {
        while (peek().kind == HclTokenKind.Whitespace ||
            peek().kind == HclTokenKind.InlineComment ||
            peek().kind == HclTokenKind.LineBreak ||
            peek().kind == HclTokenKind.LineComment
        ) {
            pos += 1
        }
    }

    /** Parses one complete expression from the region. */
    fun parseRegionExpression(): HclExpression? {
        skipStructural()
        val expression = parseConditional(0) ?: return null
        skipStructural()
        if (peek().kind != HclTokenKind.Eof) {
            failed = true
            return null
        }
        return expression
    }

    /** Records one region failure; the enclosing template parse turns it
     * into a Recovered `hcl.parse.expression@1` diagnostic — never a fatal
     * formation failure. */
    private var failed = false

    private fun diagnose(token: HclToken) {
        failed = true
    }

    /** The `for` introduction of a for-directive interior: `for Identifier
     * (, Identifier)? in Expression` with NO colon — the directive closes at
     * its `}` (RFC 0014 §4.4). */
    fun parseForIntro(): HclForIntro? {
        val forStart = peek().start
        skipStructural()
        if (!at(HclTokenKind.Identifier)) {
            diagnose(peek())
            return null
        }
        val first = text(advance())
        var key: String? = null
        var value = first
        skipStructural()
        if (at(HclTokenKind.Comma)) {
            advance()
            skipStructural()
            if (!at(HclTokenKind.Identifier)) {
                diagnose(peek())
                return null
            }
            key = first
            value = text(advance())
            skipStructural()
        }
        if (!at(HclTokenKind.Identifier) || text(peek()) != "in") {
            diagnose(peek())
            return null
        }
        advance()
        val collection = parseConditional(0) ?: return null
        skipStructural()
        val end = peek()
        val introEnd = if (end.kind == HclTokenKind.Eof) end.start else end.start
        return HclForIntro(
            key = key,
            value = value,
            collection = collection,
            span = span(forStart, introEnd),
        )
    }

    private fun parseConditional(depth: Int): HclExpression? {
        if (depth >= limits.maxExpressionDepth) {
            throw HclFormationException(
                listOf(
                    hclLimitDiagnostic(HCL_LIMIT_EXPRESSION_DEPTH, "expression-depth", depth + 1, limits.maxExpressionDepth),
                ),
            )
        }
        val condition = parseOr(depth) ?: return null
        skipStructural()
        if (!at(HclTokenKind.QuestionMark)) {
            return condition
        }
        advance()
        val then = parseConditional(depth + 1) ?: return null
        skipStructural()
        if (!at(HclTokenKind.Colon)) {
            diagnose(peek())
            return null
        }
        advance()
        val elseExpr = parseConditional(depth + 1) ?: return null
        return HclExpression(
            span = span(condition.span.startByte, elseExpr.span.endByte),
            kind = HclExpressionKind.Conditional(condition, then, elseExpr),
        )
    }

    private fun parseOr(depth: Int): HclExpression? =
        parseBinaryLevel(depth, { it == HclTokenKind.OpOr }, { HclBinaryOp.Or }, ::parseAnd)

    private fun parseAnd(depth: Int): HclExpression? =
        parseBinaryLevel(depth, { it == HclTokenKind.OpAnd }, { HclBinaryOp.And }, ::parseEquality)

    private fun parseEquality(depth: Int): HclExpression? =
        parseBinaryLevel(depth, { kind ->
            kind == HclTokenKind.OpEqual || kind == HclTokenKind.OpNotEqual
        }, { kind ->
            if (kind == HclTokenKind.OpEqual) HclBinaryOp.Equal else HclBinaryOp.NotEqual
        }, ::parseRelational)

    private fun parseRelational(depth: Int): HclExpression? =
        parseBinaryLevel(depth, { kind ->
            kind == HclTokenKind.OpLess || kind == HclTokenKind.OpGreater ||
                kind == HclTokenKind.OpLessEqual || kind == HclTokenKind.OpGreaterEqual
        }, { kind ->
            when (kind) {
                HclTokenKind.OpLess -> HclBinaryOp.Less
                HclTokenKind.OpGreater -> HclBinaryOp.Greater
                HclTokenKind.OpLessEqual -> HclBinaryOp.LessEqual
                else -> HclBinaryOp.GreaterEqual
            }
        }, ::parseAdditive)

    private fun parseAdditive(depth: Int): HclExpression? =
        parseBinaryLevel(depth, { kind ->
            kind == HclTokenKind.OpAdd || kind == HclTokenKind.OpSubtract
        }, { kind ->
            if (kind == HclTokenKind.OpAdd) HclBinaryOp.Add else HclBinaryOp.Subtract
        }, ::parseMultiplicative)

    private fun parseMultiplicative(depth: Int): HclExpression? =
        parseBinaryLevel(depth, { kind ->
            kind == HclTokenKind.Star || kind == HclTokenKind.OpDivide ||
                kind == HclTokenKind.OpModulo
        }, { kind ->
            when (kind) {
                HclTokenKind.Star -> HclBinaryOp.Multiply
                HclTokenKind.OpDivide -> HclBinaryOp.Divide
                else -> HclBinaryOp.Modulo
            }
        }, ::parseTerm)

    private inline fun parseBinaryLevel(
        depth: Int,
        matches: (HclTokenKind) -> Boolean,
        resolve: (HclTokenKind) -> HclBinaryOp,
        lower: (Int) -> HclExpression?,
    ): HclExpression? {
        var lhs = lower(depth) ?: return null
        var chain = 0
        while (true) {
            skipStructural()
            val kind = peek().kind
            if (!matches(kind)) {
                break
            }
            chain += 1
            if (chain > limits.maxExpressionDepth) {
                throw HclFormationException(
                    listOf(
                        hclLimitDiagnostic(HCL_LIMIT_EXPRESSION_DEPTH, "expression-depth", chain, limits.maxExpressionDepth),
                    ),
                )
            }
            advance()
            skipStructural()
            val rhs = lower(depth) ?: return null
            lhs = HclExpression(
                span = span(lhs.span.startByte, rhs.span.endByte),
                kind = HclExpressionKind.Binary(resolve(kind), lhs, rhs),
            )
        }
        return lhs
    }

    private fun parseTerm(depth: Int): HclExpression? {
        if (depth >= limits.maxExpressionDepth) {
            throw HclFormationException(
                listOf(
                    hclLimitDiagnostic(HCL_LIMIT_EXPRESSION_DEPTH, "expression-depth", depth + 1, limits.maxExpressionDepth),
                ),
            )
        }
        skipStructural()
        val token = peek()
        return when (token.kind) {
            HclTokenKind.OpSubtract, HclTokenKind.OpNot -> {
                val opToken = advance()
                val op = if (opToken.kind == HclTokenKind.OpSubtract) {
                    HclUnaryOp.Minus
                } else {
                    HclUnaryOp.Not
                }
                val operand = parseTerm(depth + 1) ?: return null
                HclExpression(
                    span = span(opToken.start, operand.span.endByte),
                    kind = HclExpressionKind.Unary(op, operand),
                )
            }
            HclTokenKind.Number -> {
                val numberToken = advance()
                HclExpression(
                    span = tokenSpan(numberToken),
                    kind = HclExpressionKind.Number(number(numberToken)),
                )
            }
            HclTokenKind.Identifier -> {
                val nameToken = peek()
                val name = text(nameToken)
                advance()
                skipStructural()
                if (at(HclTokenKind.ParenOpen)) {
                    parseCall(nameToken, depth)
                } else {
                    val base: HclExpressionKind = when (name) {
                        "true" -> HclExpressionKind.Boolean(true)
                        "false" -> HclExpressionKind.Boolean(false)
                        "null" -> HclExpressionKind.Null
                        else -> HclExpressionKind.VariableRef(name)
                    }
                    // Static traversal steps (RFC 0014 §4.3).
                    val steps = ArrayList<HclTraversalStep>()
                    var end = nameToken.end
                    while (true) {
                        skipStructural()
                        if (at(HclTokenKind.Dot)) {
                            val dot = advance()
                            skipStructural()
                            if (!at(HclTokenKind.Identifier)) {
                                diagnose(peek())
                                return null
                            }
                            val ident = advance()
                            steps.add(
                                HclTraversalStep.GetAttr(
                                    name = text(ident),
                                    span = span(dot.start, ident.end),
                                ),
                            )
                            end = ident.end
                        } else if (at(HclTokenKind.BracketOpen)) {
                            advance()
                            skipStructural()
                            val key = parseConditional(depth + 1) ?: return null
                            skipStructural()
                            if (!at(HclTokenKind.BracketClose)) {
                                diagnose(peek())
                                return null
                            }
                            val close = advance()
                            steps.add(
                                HclTraversalStep.Index(
                                    key = key,
                                    span = span(nameToken.start, close.end),
                                ),
                            )
                            end = close.end
                        } else {
                            break
                        }
                    }
                    val kind = if (steps.isEmpty()) {
                        base
                    } else {
                        val root = when (base) {
                            is HclExpressionKind.Boolean -> HclTraversalRoot.Boolean(base.value)
                            is HclExpressionKind.Null -> HclTraversalRoot.Null
                            is HclExpressionKind.VariableRef -> HclTraversalRoot.Variable(base.name)
                            else -> error("identifier term base kinds are closed")
                        }
                        HclExpressionKind.Traversal(root, steps)
                    }
                    HclExpression(
                        span = span(nameToken.start, end),
                        kind = kind,
                    )
                }
            }
            HclTokenKind.ParenOpen -> {
                advance()
                skipStructural()
                val inner = parseConditional(depth + 1) ?: return null
                skipStructural()
                if (!at(HclTokenKind.ParenClose)) {
                    diagnose(peek())
                    return null
                }
                val close = advance()
                HclExpression(
                    span = span(token.start, close.end),
                    kind = HclExpressionKind.Paren(inner),
                )
            }
            HclTokenKind.BracketOpen -> {
                advance()
                skipStructural()
                val elements = ArrayList<HclExpression>()
                if (!at(HclTokenKind.BracketClose)) {
                    while (true) {
                        val element = parseConditional(depth + 1) ?: return null
                        elements.add(element)
                        if (elements.size > limits.maxTupleElements) {
                            throw HclFormationException(
                                listOf(
                                    hclLimitDiagnostic(HCL_LIMIT_TUPLE_ELEMENTS, "tuple-elements", elements.size, limits.maxTupleElements),
                                ),
                            )
                        }
                        skipStructural()
                        if (at(HclTokenKind.Comma)) {
                            advance()
                            skipStructural()
                            if (at(HclTokenKind.BracketClose)) {
                                break
                            }
                        } else if (at(HclTokenKind.BracketClose)) {
                            break
                        } else {
                            // A newline separates tuple elements without a
                            // comma (RFC 0014 §4.6).
                            continue
                        }
                    }
                }
                val close = advance()
                HclExpression(
                    span = span(token.start, close.end),
                    kind = HclExpressionKind.Tuple(elements),
                )
            }
            else -> {
                diagnose(token)
                null
            }
        }
    }

    private fun parseCall(nameToken: HclToken, depth: Int): HclExpression? {
        advance() // open paren
        skipStructural()
        val args = ArrayList<HclCallArg>()
        if (at(HclTokenKind.ParenClose)) {
            val close = advance()
            return HclExpression(
                span = span(nameToken.start, close.end),
                kind = HclExpressionKind.FunctionCall(text(nameToken), tokenSpan(nameToken), args),
            )
        }
        while (true) {
            val expression = parseConditional(depth + 1) ?: return null
            var expand = false
            skipStructural()
            if (at(HclTokenKind.Ellipsis)) {
                advance()
                expand = true
            }
            args.add(HclCallArg(expression, expand))
            skipStructural()
            when (peek().kind) {
                HclTokenKind.Comma -> {
                    advance()
                    skipStructural()
                    if (at(HclTokenKind.ParenClose)) {
                        val close = advance()
                        return HclExpression(
                            span = span(nameToken.start, close.end),
                            kind = HclExpressionKind.FunctionCall(text(nameToken), tokenSpan(nameToken), args),
                        )
                    }
                }
                HclTokenKind.ParenClose -> {
                    val close = advance()
                    return HclExpression(
                        span = span(nameToken.start, close.end),
                        kind = HclExpressionKind.FunctionCall(text(nameToken), tokenSpan(nameToken), args),
                    )
                }
                else -> {
                    diagnose(peek())
                    return null
                }
            }
        }
    }

    private fun number(token: HclToken): HclNumber {
        val spelling = text(token)
        val canonical = canonicalDecimalBounded(spelling, limits.maxNumberDigits)
            ?: throw HclFormationException(
                listOf(hclLimitDiagnostic(HCL_LIMIT_NUMBER_DIGITS, "number-digits", Int.MAX_VALUE, limits.maxNumberDigits)),
            )
        val digits = canonical.count { it.isDigit() }
        if (digits > limits.maxNumberDigits) {
            throw HclFormationException(
                listOf(hclLimitDiagnostic(HCL_LIMIT_NUMBER_DIGITS, "number-digits", digits, limits.maxNumberDigits)),
            )
        }
        return HclNumber(tokenSpan(token), canonical)
    }

    private fun span(start: Int, end: Int): Span {
        if (start > end || end > source.len) {
            throw HclFormationException(
                listOf(
                    HclDiagnostic(
                        code = HCL_PARSE_COORDINATES,
                        category = DiagnosticCategory.Syntax,
                        severity = Severity.Error,
                        startByte = null,
                        endByte = null,
                        arguments = emptyMap(),
                        notes = emptyList(),
                        occurrence = 0,
                    ),
                ),
            )
        }
        return try {
            authority.span(start, end)
        } catch (e: consema.document.LocationException) {
            throw HclFormationException(
                listOf(
                    HclDiagnostic(
                        code = HCL_PARSE_COORDINATES,
                        category = DiagnosticCategory.Syntax,
                        severity = Severity.Error,
                        startByte = null,
                        endByte = null,
                        arguments = emptyMap(),
                        notes = emptyList(),
                        occurrence = 0,
                    ),
                ),
            )
        }
    }
}

/** Decodes one quoted-template literal run: `\n` `\r` `\t` `\"` `\\`
 * `\uNNNN` `\UNNNNNNNN`, and the `$${`/`%%{` escapes (RFC 0014 §4.4). */
internal fun decodeQuotedLiteral(spelling: String): String {
    val out = StringBuilder()
    var index = 0
    while (index < spelling.length) {
        val ch = spelling[index]
        if (ch == '$' && index + 2 <= spelling.length && spelling[index + 1] == '$' &&
            index + 3 <= spelling.length && spelling[index + 2] == '{'
        ) {
            // `$${` is escaped literal `${` text (RFC 0014 §4.4).
            out.append("\$")
            out.append('{')
            index += 3
            continue
        }
        if (ch == '%' && index + 2 <= spelling.length && spelling[index + 1] == '%' &&
            index + 3 <= spelling.length && spelling[index + 2] == '{'
        ) {
            out.append("%{")
            index += 3
            continue
        }
        if (ch != '\\') {
            out.append(ch)
            index += 1
            continue
        }
        val escaped = spelling[index + 1]
        when (escaped) {
            'n' -> {
                out.append('\n')
                index += 2
            }
            'r' -> {
                out.append('\r')
                index += 2
            }
            't' -> {
                out.append('\t')
                index += 2
            }
            '"' -> {
                out.append('"')
                index += 2
            }
            '\\' -> {
                out.append('\\')
                index += 2
            }
            'u' -> {
                if (index + 6 <= spelling.length) {
                    val digits = spelling.substring(index + 2, index + 6)
                    out.appendCodePoint(digits.toInt(16))
                    index += 6
                } else {
                    // Invalid escapes are already recovered at the lexer;
                    // the literal keeps the raw text.
                    out.append(escaped)
                    index += 2
                }
            }
            'U' -> {
                if (index + 10 <= spelling.length) {
                    val digits = spelling.substring(index + 2, index + 10)
                    val value = digits.toInt(16)
                    if (value in 0..0x10FFFF) {
                        out.appendCodePoint(value)
                        index += 10
                    } else {
                        out.append(escaped)
                        index += 2
                    }
                } else {
                    out.append(escaped)
                    index += 2
                }
            }
            else -> {
                // Invalid escapes are already recovered at the lexer; the
                // literal keeps the raw text.
                out.append(escaped)
                index += 2
            }
        }
    }
    return out.toString()
}
