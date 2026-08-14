// The self-owned HCL tokenizer: one exhaustive token/trivia/error-region
// pass over the raw source plus the lossless 30-kind piece index (RFC 0014
// §2, §7.2).
//
// Data authority:
//   - https://github.com/consema/consema-rs/blob/main/consema-hcl/src/lexer.rs pins the token kind set (lexer.rs
//     245), the token-to-piece kind mapping (lexer.rs), the
//     structural classification (lexer.rs), the main scan (lexer.rs
// : operators, `::` as invalid-character, `_` as identifier@1,
//     BOM as byte-order-mark@1, lone CR as lone-cr@1), the number grammar
//     (lexer.rs), the quoted-template scan (lexer.rs
//     escapes, `$${`/`%%{` literals, unterminated-string@1 recovery to the
//     newline), the heredoc scan (lexer.rs: TrimSpace closing-line
//     match, per-line content runs, heredoc-bytes/heredoc-lines limits), the
//     interpolation/directive absorption (lexer.rs), and the
//     template frames (lexer.rs).
//   - RFC 0014 §2 (https://github.com/consema/consema/blob/main/docs/rfcs/0014-hcl-family-profiles-v1.md) freezes
//     the UTF-8-only source contract: newline is exactly LF or CRLF, a lone
//     CR is Recovered, a leading BOM is Recovered.
//   - RFC 0014 §4.1 (:147-186) freezes the identifier (UAX #31 with `-`
//     continuation; `_` is not an ID_Start), number, comment, whitespace,
//     and traversal facts.
//   - consema-go/go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design: the lexer is a state machine over the raw bytes
// with an explicit template frame stack (quoted / heredoc / absorbed
// interpolation); byte offsets are the frozen half-open raw-byte spans;
// every emit checks the frozen token/piece limits BEFORE pushing (hard
// gate 4). A second mode lexes a bounded region (an interpolation or
// directive interior) into tokens only, without pieces, for the parser.

package consema.hcl

import consema.document.DocumentAuthority
import consema.document.LosslessStructuralIndex
import consema.document.StructuralPiece
import consema.document.StructuralPieceKind
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/**
 * Closed token kind set of the self-owned HCL tokenizer (RFC 0014 §2, §4.1;
 * lexer.rs). The set is richer than the 30-piece [HclSyntaxKind]
 * closure: operator spellings, the exact trivia runs, and the zero-length
 * `Eof` terminal are token facts.
 */
internal enum class HclTokenKind {
    Whitespace,
    LineBreak,
    LineComment,
    InlineComment,
    Identifier,
    Number,
    Equals,
    StringOpen,
    StringContent,
    StringClose,
    InterpolationOpen,
    InterpolationContent,
    InterpolationClose,
    DirectiveOpen,
    DirectiveContent,
    DirectiveClose,
    HeredocOpen,
    HeredocContent,
    HeredocClose,
    Dot,
    Comma,
    Colon,
    QuestionMark,
    Arrow,
    Ellipsis,
    Star,
    BraceOpen,
    BraceClose,
    BracketOpen,
    BracketClose,
    ParenOpen,
    ParenClose,
    OpEqual,
    OpNotEqual,
    OpLess,
    OpGreater,
    OpLessEqual,
    OpGreaterEqual,
    OpAdd,
    OpSubtract,
    OpNot,
    OpDivide,
    OpModulo,
    OpAnd,
    OpOr,
    ErrorRegion,
    Eof,
    ;

    /** The closed lossless syntax kind of this token; null for the
     * zero-length `Eof` terminal, which has no piece (RFC 0014 §7.2;
     * lexer.rs). */
    fun syntaxKind(): HclSyntaxKind? = when (this) {
        Whitespace -> HclSyntaxKind.Whitespace
        LineBreak -> HclSyntaxKind.LineBreak
        LineComment -> HclSyntaxKind.LineComment
        InlineComment -> HclSyntaxKind.InlineComment
        Identifier -> HclSyntaxKind.Identifier
        Number -> HclSyntaxKind.Number
        Equals -> HclSyntaxKind.Equals
        StringOpen -> HclSyntaxKind.StringOpen
        StringContent -> HclSyntaxKind.StringContent
        StringClose -> HclSyntaxKind.StringClose
        InterpolationOpen -> HclSyntaxKind.InterpolationOpen
        InterpolationContent -> HclSyntaxKind.InterpolationContent
        InterpolationClose -> HclSyntaxKind.InterpolationClose
        DirectiveOpen -> HclSyntaxKind.DirectiveOpen
        DirectiveContent -> HclSyntaxKind.DirectiveContent
        DirectiveClose -> HclSyntaxKind.DirectiveClose
        HeredocOpen -> HclSyntaxKind.HeredocOpen
        HeredocContent -> HclSyntaxKind.HeredocContent
        HeredocClose -> HclSyntaxKind.HeredocClose
        BraceOpen -> HclSyntaxKind.BraceOpen
        BraceClose -> HclSyntaxKind.BraceClose
        BracketOpen -> HclSyntaxKind.BracketOpen
        BracketClose -> HclSyntaxKind.BracketClose
        ParenOpen -> HclSyntaxKind.ParenOpen
        ParenClose -> HclSyntaxKind.ParenClose
        Comma -> HclSyntaxKind.Comma
        Colon -> HclSyntaxKind.Colon
        QuestionMark -> HclSyntaxKind.QuestionMark
        Dot, Arrow, Ellipsis, Star, OpEqual, OpNotEqual, OpLess, OpGreater,
        OpLessEqual, OpGreaterEqual, OpAdd, OpSubtract, OpNot, OpDivide,
        OpModulo, OpAnd, OpOr,
        -> HclSyntaxKind.Operator
        ErrorRegion -> HclSyntaxKind.ErrorRegion
        Eof -> null
    }

    /** The structural classification of this token's piece (lexer.rs
 *). */
    fun structuralKind(): StructuralPieceKind = when (this) {
        Whitespace, LineBreak, LineComment, InlineComment ->
            StructuralPieceKind.Trivia
        ErrorRegion -> StructuralPieceKind.ErrorRegion
        else -> StructuralPieceKind.Token
    }
}

/** One exact half-open token span over the raw source. */
internal data class HclToken(
    val kind: HclTokenKind,
    val start: Int,
    val end: Int,
)

/**
 * Result of one lexer pass: the ordered token stream (ending with the
 * zero-length `Eof` terminal), the recovered error regions in source order,
 * the ordered diagnostics, and — for a whole-source lex — the lossless
 * 30-kind piece index (RFC 0014 §2, §7.2). `syntax` is present for a
 * whole-source lex and absent for a region lex (an interpolation interior),
 * whose tokens still carry exact spans but do not form a source-covering
 * index (lexer.rs).
 */
internal class LexedSource(
    val sourceLen: Int,
    val tokens: List<HclToken>,
    val errorRegions: List<HclErrorRegion>,
    val diagnostics: List<HclDiagnostic>,
    val recovered: Boolean,
    val pieces: List<StructuralPiece>?,
    val kinds: List<HclSyntaxKind>?,
    val structuralIndex: LosslessStructuralIndex?,
)

/**
 * Runs one whole-source lex over the complete decoded text
 * (lexer.rs). Any limit failure throws
 * [HclFormationException]; the lossless coverage invariant is validated by
 * [LosslessStructuralIndex.new].
 */
internal fun lexSource(
    bytes: ByteArray,
    authority: DocumentAuthority,
    limits: HclParseLimits,
): LexedSource {
    val lexer = Lexer(bytes, authority, limits, recordErrorTokens = true)
    lexer.run()
    lexer.pushEof()
    val pieces = ArrayList<StructuralPiece>(lexer.tokens.size)
    val kinds = ArrayList<HclSyntaxKind>(lexer.tokens.size)
    for (token in lexer.tokens) {
        val kind = token.kind.syntaxKind() ?: continue
        pieces.add(
            StructuralPiece(
                authority.span(token.start, token.end),
                token.kind.structuralKind(),
            ),
        )
        kinds.add(kind)
    }
    val structuralIndex = LosslessStructuralIndex.new(authority.identity, bytes.size, pieces)
    return LexedSource(
        sourceLen = bytes.size,
        tokens = lexer.tokens,
        errorRegions = lexer.errorRegions,
        diagnostics = lexer.diagnostics,
        recovered = lexer.recovered,
        pieces = pieces,
        kinds = kinds,
        structuralIndex = structuralIndex,
    )
}

/**
 * Runs one region lex over `[start, end)` of the source — an interpolation
 * or directive interior — producing tokens without pieces (the Rust
 * `lex_region`; lexer.rs). Region failures never emit pieces, but
 * diagnostics and error regions are recorded for the parser to merge; the
 * region spans bind to the same [authority] as the enclosing document.
 */
internal fun lexRegion(
    bytes: ByteArray,
    authority: DocumentAuthority,
    start: Int,
    end: Int,
    limits: HclParseLimits,
): LexedSource {
    val lexer = Lexer(bytes, authority, limits, recordErrorTokens = false, start = start, end = end)
    lexer.run()
    lexer.pushEof()
    return LexedSource(
        sourceLen = end,
        tokens = lexer.tokens,
        errorRegions = lexer.errorRegions,
        diagnostics = lexer.diagnostics,
        recovered = lexer.recovered,
        pieces = null,
        kinds = null,
        structuralIndex = null,
    )
}

/** One open template frame (lexer.rs). */
private sealed class Frame {
    /** An open quoted template; buffered tokens are flushed at the close. */
    data class Quoted(
        val open: Int,
        val buffer: MutableList<HclToken>,
        var interpolations: Int,
    ) : Frame()

    /** An open heredoc. */
    data class Heredoc(
        val contentStart: Int,
        val marker: String,
        val buffer: MutableList<HclToken>,
        var interpolations: Int,
        var lines: Int,
    ) : Frame()

    /** An absorbed interpolation or directive interior. */
    data class Interp(
        val directive: Boolean,
        val interiorStart: Int,
        var depth: Int,
    ) : Frame()
}

/**
 * The single-pass state machine (RFC 0014 §2, §4.1; lexer.rs).
 * Byte offsets are the frozen half-open raw-byte spans; under the UTF-8-only
 * source contract the decoded offsets equal raw offsets.
 */
private class Lexer(
    private val bytes: ByteArray,
    private val authority: DocumentAuthority,
    private val limits: HclParseLimits,
    private val recordErrorTokens: Boolean,
    private val start: Int = 0,
    private val end: Int = bytes.size,
) {
    private var pos: Int = start
    internal val tokens = ArrayList<HclToken>()
    internal val errorRegions = ArrayList<HclErrorRegion>()
    internal val diagnostics = ArrayList<HclDiagnostic>()
    internal var recovered = false
    private val stack = ArrayList<Frame>()

    /** Runs the scan loop to the region end with the frame stack empty. */
    fun run() {
        while (pos < end) {
            when (val top = stack.lastOrNull()) {
                null -> scanNormal()
                is Frame.Quoted -> scanQuoted(top)
                is Frame.Heredoc -> scanHeredoc(top)
                is Frame.Interp -> scanAbsorb(top)
            }
        }
        if (stack.isNotEmpty()) {
            terminateAtEnd()
        }
    }

    fun pushEof() {
        tokens.add(HclToken(HclTokenKind.Eof, pos, pos))
    }

    // -- the main scan (lexer.rs) ---------------------------------

    private fun scanNormal() {
        val byte = bytes[pos]
        when {
            byte == ' '.code.toByte() || byte == '\t'.code.toByte() -> {
                val runStart = pos
                while (pos < end && (bytes[pos] == ' '.code.toByte() || bytes[pos] == '\t'.code.toByte())) {
                    pos += 1
                }
                emitKind(HclTokenKind.Whitespace, runStart, pos)
            }
            byte == '\n'.code.toByte() -> {
                emitKind(HclTokenKind.LineBreak, pos, pos + 1)
                pos += 1
            }
            byte == '\r'.code.toByte() -> {
                if (pos + 1 < end && bytes[pos + 1] == '\n'.code.toByte()) {
                    emitKind(HclTokenKind.LineBreak, pos, pos + 2)
                    pos += 2
                } else {
                    // A lone CR is not a newline (RFC 0014 §2).
                    emitErrorRegion(pos, pos + 1, HCL_PARSE_LONE_CR, DiagnosticCategory.Lexical)
                    pos += 1
                }
            }
            byte == '/'.code.toByte() && pos + 1 < end && bytes[pos + 1] == '/'.code.toByte() ->
                scanLineComment()
            byte == '#'.code.toByte() -> scanLineComment()
            byte == '/'.code.toByte() && pos + 1 < end && bytes[pos + 1] == '*'.code.toByte() ->
                scanInlineComment()
            byte == '"'.code.toByte() -> openQuoted()
            byte == '<'.code.toByte() -> {
                if (pos + 1 < end && bytes[pos + 1] == '<'.code.toByte()) {
                    openHeredoc()
                } else if (pos + 1 < end && bytes[pos + 1] == '='.code.toByte()) {
                    emitKind(HclTokenKind.OpLessEqual, pos, pos + 2)
                    pos += 2
                } else {
                    emitKind(HclTokenKind.OpLess, pos, pos + 1)
                    pos += 1
                }
            }
            byte == '>'.code.toByte() -> {
                if (pos + 1 < end && bytes[pos + 1] == '='.code.toByte()) {
                    emitKind(HclTokenKind.OpGreaterEqual, pos, pos + 2)
                    pos += 2
                } else {
                    emitKind(HclTokenKind.OpGreater, pos, pos + 1)
                    pos += 1
                }
            }
            byte == '='.code.toByte() -> {
                if (pos + 1 < end && bytes[pos + 1] == '='.code.toByte()) {
                    emitKind(HclTokenKind.OpEqual, pos, pos + 2)
                    pos += 2
                } else if (pos + 1 < end && bytes[pos + 1] == '>'.code.toByte()) {
                    emitKind(HclTokenKind.Arrow, pos, pos + 2)
                    pos += 2
                } else {
                    emitKind(HclTokenKind.Equals, pos, pos + 1)
                    pos += 1
                }
            }
            byte == '!'.code.toByte() -> {
                if (pos + 1 < end && bytes[pos + 1] == '='.code.toByte()) {
                    emitKind(HclTokenKind.OpNotEqual, pos, pos + 2)
                    pos += 2
                } else {
                    emitKind(HclTokenKind.OpNot, pos, pos + 1)
                    pos += 1
                }
            }
            byte == '-'.code.toByte() -> {
                emitKind(HclTokenKind.OpSubtract, pos, pos + 1)
                pos += 1
            }
            byte == '+'.code.toByte() -> {
                emitKind(HclTokenKind.OpAdd, pos, pos + 1)
                pos += 1
            }
            byte == '*'.code.toByte() -> {
                emitKind(HclTokenKind.Star, pos, pos + 1)
                pos += 1
            }
            byte == '%'.code.toByte() -> {
                emitKind(HclTokenKind.OpModulo, pos, pos + 1)
                pos += 1
            }
            byte == '&'.code.toByte() -> {
                if (pos + 1 < end && bytes[pos + 1] == '&'.code.toByte()) {
                    emitKind(HclTokenKind.OpAnd, pos, pos + 2)
                    pos += 2
                } else {
                    emitErrorRegion(pos, pos + 1, HCL_PARSE_INVALID_CHARACTER, DiagnosticCategory.Syntax)
                    pos += 1
                }
            }
            byte == '|'.code.toByte() -> {
                if (pos + 1 < end && bytes[pos + 1] == '|'.code.toByte()) {
                    emitKind(HclTokenKind.OpOr, pos, pos + 2)
                    pos += 2
                } else {
                    emitErrorRegion(pos, pos + 1, HCL_PARSE_INVALID_CHARACTER, DiagnosticCategory.Syntax)
                    pos += 1
                }
            }
            byte == '?'.code.toByte() -> {
                emitKind(HclTokenKind.QuestionMark, pos, pos + 1)
                pos += 1
            }
            byte == ':'.code.toByte() -> {
                if (pos + 1 < end && bytes[pos + 1] == ':'.code.toByte()) {
                    // `::` is never an operator: the namespaced function form
                    // has no spec production (RFC 0014 §12 D-6).
                    emitErrorRegion(pos, pos + 2, HCL_PARSE_INVALID_CHARACTER, DiagnosticCategory.Syntax)
                    pos += 2
                } else {
                    emitKind(HclTokenKind.Colon, pos, pos + 1)
                    pos += 1
                }
            }
            byte == ','.code.toByte() -> {
                emitKind(HclTokenKind.Comma, pos, pos + 1)
                pos += 1
            }
            byte == '.'.code.toByte() -> {
                if (pos + 2 < end && bytes[pos + 1] == '.'.code.toByte() &&
                    bytes[pos + 2] == '.'.code.toByte()
                ) {
                    emitKind(HclTokenKind.Ellipsis, pos, pos + 3)
                    pos += 3
                } else {
                    emitKind(HclTokenKind.Dot, pos, pos + 1)
                    pos += 1
                }
            }
            byte == '{'.code.toByte() -> {
                emitKind(HclTokenKind.BraceOpen, pos, pos + 1)
                pos += 1
            }
            byte == '}'.code.toByte() -> {
                emitKind(HclTokenKind.BraceClose, pos, pos + 1)
                pos += 1
            }
            byte == '['.code.toByte() -> {
                emitKind(HclTokenKind.BracketOpen, pos, pos + 1)
                pos += 1
            }
            byte == ']'.code.toByte() -> {
                emitKind(HclTokenKind.BracketClose, pos, pos + 1)
                pos += 1
            }
            byte == '('.code.toByte() -> {
                emitKind(HclTokenKind.ParenOpen, pos, pos + 1)
                pos += 1
            }
            byte == ')'.code.toByte() -> {
                emitKind(HclTokenKind.ParenClose, pos, pos + 1)
                pos += 1
            }
            byte == '~'.code.toByte() || byte == '\\'.code.toByte() || byte == '$'.code.toByte() -> {
                emitErrorRegion(pos, pos + 1, HCL_PARSE_INVALID_CHARACTER, DiagnosticCategory.Syntax)
                pos += 1
            }
            byte in '0'.code.toByte()..'9'.code.toByte() -> scanNumber()
            else -> {
                val scalar = scalarAt(pos)
                if (scalar == 0xFEFF) {
                    // A BOM is a Profile violation; formation is Recovered
                    // (RFC 0014 §2).
                    emitErrorRegion(pos, pos + 3, HCL_PARSE_BYTE_ORDER_MARK, DiagnosticCategory.Encoding)
                    pos += 3
                } else if (scalar == '_'.code) {
                    // `_` is not an ID_Start (RFC 0014 §4.1, §12 D-4).
                    emitErrorRegion(pos, pos + 1, HCL_PARSE_IDENTIFIER, DiagnosticCategory.Syntax)
                    pos += 1
                } else if (isIdentifierStart(scalar)) {
                    scanIdentifier()
                } else {
                    val width = scalarWidth(scalar)
                    emitErrorRegion(
                        pos,
                        pos + width,
                        HCL_PARSE_INVALID_CHARACTER,
                        DiagnosticCategory.Syntax,
                    )
                    pos += width
                }
            }
        }
    }

    private fun scanLineComment() {
        val runStart = pos
        while (pos < end && bytes[pos] != '\n'.code.toByte() && bytes[pos] != '\r'.code.toByte()) {
            pos += 1
        }
        emitKind(HclTokenKind.LineComment, runStart, pos)
    }

    private fun scanInlineComment() {
        val runStart = pos
        pos += 2
        while (pos + 1 < end && !(bytes[pos] == '*'.code.toByte() && bytes[pos + 1] == '/'.code.toByte())) {
            pos += 1
        }
        if (pos + 1 < end) {
            pos += 2
            emitKind(HclTokenKind.InlineComment, runStart, pos)
        } else {
            // An unterminated comment is one error region (RFC 0014 §4.1).
            emitErrorRegion(runStart, end, HCL_PARSE_UNTERMINATED_COMMENT, DiagnosticCategory.Syntax)
            pos = end
        }
    }

    /** UAX #31 identifier with hyphen continuation (lexer.rs). */
    private fun scanIdentifier() {
        val runStart = pos
        while (pos < end) {
            val scalar = scalarAt(pos)
            if (isIdentifierContinue(scalar) || scalar == '-'.code) {
                pos += scalarWidth(scalar)
            } else {
                break
            }
        }
        val len = pos - runStart
        checkLimit(HCL_LIMIT_IDENTIFIER_LEN, "identifier-len", len, limits.maxIdentifierLen)
        emitKind(HclTokenKind.Identifier, runStart, pos)
    }

    /** Scans one number-shaped run and validates the §4.1 decimal grammar
     * (lexer.rs). */
    private fun scanNumber() {
        val runStart = pos
        while (pos < end && bytes[pos] in '0'.code.toByte()..'9'.code.toByte()) {
            pos += 1
        }
        if (byteAt(0) == '.'.code.toByte() && byteAt(1) != null &&
            bytes[pos + 1] in '0'.code.toByte()..'9'.code.toByte()
        ) {
            pos += 2
            while (pos < end && bytes[pos] in '0'.code.toByte()..'9'.code.toByte()) {
                pos += 1
            }
        }
        if (byteAt(0) == 'e'.code.toByte() || byteAt(0) == 'E'.code.toByte()) {
            val sign = byteAt(1) == '+'.code.toByte() || byteAt(1) == '-'.code.toByte()
            val digitsStart = if (sign) 2 else 1
            val digitAt = if (pos + digitsStart < end) bytes[pos + digitsStart] else 0
            if (digitAt in '0'.code.toByte()..'9'.code.toByte()) {
                pos += 1
                if (sign) {
                    pos += 1
                }
                while (pos < end && bytes[pos] in '0'.code.toByte()..'9'.code.toByte()) {
                    pos += 1
                }
            }
        }
        // A continuation that cannot start a fresh token makes the whole run
        // one invalid number (lexer.rs).
        var extend = pos
        while (extend < end) {
            val scalar = scalarAt(extend)
            if (isIdentifierContinue(scalar)) {
                extend += scalarWidth(scalar)
            } else if (scalar == '.'.code && extend + 1 < end &&
                bytes[extend + 1] in '0'.code.toByte()..'9'.code.toByte()
            ) {
                extend += 2
            } else {
                break
            }
        }
        if (extend > pos) {
            emitErrorRegion(runStart, extend, HCL_PARSE_INVALID_NUMBER, DiagnosticCategory.Syntax)
            pos = extend
        } else {
            emitKind(HclTokenKind.Number, runStart, pos)
        }
    }

    // -- quoted templates (lexer.rs) -----------------

    private fun openQuoted() {
        val open = pos
        pos += 1
        checkTemplateDepth()
        emitKind(HclTokenKind.StringOpen, open, pos)
        stack.add(Frame.Quoted(open, ArrayList(), 0))
    }

    private fun scanQuoted(frame: Frame.Quoted) {
        // Literal runs (escapes and `$${`/`%%{` text included) are emitted
        // as one StringContent token when the run ends (lexer.rs).
        val runStart = pos
        while (pos < end) {
            when (val byte = bytes[pos]) {
                '"'.code.toByte() -> {
                    endRun(runStart, HclTokenKind.StringContent)
                    val closeStart = pos
                    pos += 1
                    checkLimit(HCL_LIMIT_STRING_LEN, "string-len", pos - frame.open, limits.maxStringLen)
                    checkLimit(HCL_LIMIT_TEMPLATE_LEN, "template-len", pos - frame.open, limits.maxTemplateLen)
                    flushBuffer()
                    stack.removeAt(stack.size - 1)
                    emitKind(HclTokenKind.StringClose, closeStart, pos)
                    return
                }
                '$'.code.toByte() -> {
                    if (byteAt(1) == '$'.code.toByte() && byteAt(2) == '{'.code.toByte()) {
                        // `$${` is escaped literal `${` text (RFC 0014 §4.4).
                        pos += 3
                    } else if (byteAt(1) == '{'.code.toByte()) {
                        endRun(runStart, HclTokenKind.StringContent)
                        openInterpolation(directive = false)
                        return
                    } else {
                        pos += 1
                    }
                }
                '%'.code.toByte() -> {
                    if (byteAt(1) == '%'.code.toByte() && byteAt(2) == '{'.code.toByte()) {
                        pos += 3
                    } else if (byteAt(1) == '{'.code.toByte()) {
                        endRun(runStart, HclTokenKind.StringContent)
                        openInterpolation(directive = true)
                        return
                    } else {
                        pos += 1
                    }
                }
                '\\'.code.toByte() -> scanStringEscape()
                '\n'.code.toByte() -> {
                    terminateString(endOfLine())
                    return
                }
                '\r'.code.toByte() -> {
                    // A raw newline is not permitted in a quoted template;
                    // the string terminates at the newline (RFC 0014 §4.4).
                    terminateString(pos)
                    return
                }
                else -> pos += 1
            }
        }
        // End of source with the frame still open.
        terminateString(end)
    }

    /** Ends the current literal run as one content token when non-empty
     * (lexer.rs). */
    private fun endRun(runStart: Int, kind: HclTokenKind) {
        if (pos > runStart) {
            emitKind(kind, runStart, pos)
        }
    }

    /** Handles one backslash escape of a quoted template (RFC 0014 §4.4;
     * lexer.rs). The escape is validated here; the decoded text
     * is produced by the parser's literal decoder. */
    private fun scanStringEscape() {
        when (val next = byteAt(1)) {
            '\n'.code.toByte() -> {
                // A backslash-newline is not an admitted escape and a raw
                // newline is not permitted in a quoted template.
                recover(HCL_PARSE_INVALID_ESCAPE, pos, pos + 2)
                pos += 2
            }
            '\r'.code.toByte() -> {
                if (byteAt(2) == '\n'.code.toByte()) {
                    recover(HCL_PARSE_INVALID_ESCAPE, pos, pos + 3)
                    pos += 3
                } else {
                    recover(HCL_PARSE_INVALID_ESCAPE, pos, pos + 2)
                    pos += 2
                }
            }
            'n'.code.toByte(), 'r'.code.toByte(), 't'.code.toByte(),
            '"'.code.toByte(), '\\'.code.toByte(),
            -> pos += 2
            'u'.code.toByte() -> {
                val width = hexEscapeWidth(4)
                if (width == null) {
                    recover(HCL_PARSE_INVALID_ESCAPE, pos, pos + 2)
                    pos += 2
                } else {
                    pos += width
                }
            }
            'U'.code.toByte() -> {
                val width = hexEscapeWidth(8)
                if (width == null) {
                    recover(HCL_PARSE_INVALID_ESCAPE, pos, pos + 2)
                    pos += 2
                } else {
                    val digits = String(bytes.copyOfRange(pos + 2, pos + width), Charsets.US_ASCII)
                    val value = digits.toIntOrNull(16) ?: 0
                    if (value > 0x10FFFF) {
                        recover(HCL_PARSE_INVALID_ESCAPE, pos, pos + width)
                        pos += width
                    } else {
                        pos += width
                    }
                }
            }
            null -> {
                recover(HCL_PARSE_INVALID_ESCAPE, pos, pos + 1)
                pos += 1
            }
            else -> {
                recover(HCL_PARSE_INVALID_ESCAPE, pos, pos + 2)
                pos += 2
            }
        }
    }

    /** Width of `\u`/`\U` escapes including the introducer; null when the
     * hex digits are missing or invalid (RFC 0014 §4.4). */
    private fun hexEscapeWidth(digits: Int): Int? {
        if (pos + 2 + digits > end) {
            return null
        }
        for (offset in 2 until 2 + digits) {
            val byte = bytes[pos + offset]
            val isHex = byte in '0'.code.toByte()..'9'.code.toByte() ||
                byte in 'a'.code.toByte()..'f'.code.toByte() ||
                byte in 'A'.code.toByte()..'F'.code.toByte()
            if (!isHex) {
                return null
            }
        }
        return 2 + digits
    }

    /** Terminates an unterminated quoted template: the buffered content is
     * discarded and the content becomes one error region to `end`, with
     * `hcl.parse.unterminated-string@1` (RFC 0014 §3; lexer.rs). */
    private fun terminateString(endBoundary: Int) {
        val frame = stack.last() as Frame.Quoted
        checkLimit(HCL_LIMIT_STRING_LEN, "string-len", endBoundary - frame.open, limits.maxStringLen)
        checkLimit(HCL_LIMIT_TEMPLATE_LEN, "template-len", endBoundary - frame.open, limits.maxTemplateLen)
        stack.removeAt(stack.size - 1)
        emitErrorRegion(frame.open + 1, endBoundary, HCL_PARSE_UNTERMINATED_STRING, DiagnosticCategory.Syntax)
        pos = endBoundary
    }

    // -- heredocs (lexer.rs) -------------------------

    private fun openHeredoc() {
        val runStart = pos
        pos += 2
        if (byteAt(0) == '-'.code.toByte()) {
            pos += 1
        }
        val markerStart = pos
        if (pos < end && isIdentifierStart(scalarAt(pos))) {
            while (pos < end) {
                val scalar = scalarAt(pos)
                if (isIdentifierContinue(scalar) || scalar == '-'.code) {
                    pos += scalarWidth(scalar)
                } else {
                    break
                }
            }
            val markerLen = pos - markerStart
            checkLimit(HCL_LIMIT_IDENTIFIER_LEN, "identifier-len", markerLen, limits.maxIdentifierLen)
            val marker = String(bytes.copyOfRange(markerStart, pos), Charsets.UTF_8)
            // The introducer line ends with spaces or tabs and a newline (or
            // end of file); anything else is not a heredoc introduction.
            var lineCursor = pos
            while (lineCursor < end && (bytes[lineCursor] == ' '.code.toByte() ||
                    bytes[lineCursor] == '\t'.code.toByte())
            ) {
                lineCursor += 1
            }
            val newlineOk = lineCursor >= end ||
                bytes[lineCursor] == '\n'.code.toByte() ||
                (bytes[lineCursor] == '\r'.code.toByte() && lineCursor + 1 < end &&
                    bytes[lineCursor + 1] == '\n'.code.toByte())
            if (newlineOk) {
                val mode = if (pos - runStart >= 3 && bytes[runStart + 2] == '-'.code.toByte()) {
                    HclHeredocMode.StripIndent
                } else {
                    HclHeredocMode.Plain
                }
                emitKind(HclTokenKind.HeredocOpen, runStart, pos)
                if (lineCursor > pos) {
                    emitKind(HclTokenKind.Whitespace, pos, lineCursor)
                }
                if (lineCursor < end) {
                    if (bytes[lineCursor] == '\r'.code.toByte()) {
                        emitKind(HclTokenKind.LineBreak, lineCursor, lineCursor + 2)
                        pos = lineCursor + 2
                    } else {
                        emitKind(HclTokenKind.LineBreak, lineCursor, lineCursor + 1)
                        pos = lineCursor + 1
                    }
                } else {
                    pos = lineCursor
                }
                stack.add(Frame.Heredoc(pos, marker, ArrayList(), 0, 0))
                return
            }
        }
        // Not a heredoc introduction: `<<`/`<<-` without a valid marker.
        emitErrorRegion(runStart, pos, HCL_PARSE_HEREDOC_MARKER, DiagnosticCategory.Syntax)
    }

    private fun scanHeredoc(frame: Frame.Heredoc) {
        if (pos >= end) {
            terminateHeredoc(end)
            return
        }
        noteHeredocContent()
        val atLineStart = pos == 0 || bytes[pos - 1] == '\n'.code.toByte()
        val lineEnd = findLineEnd()
        if (atLineStart) {
            val trimmed = String(bytes.copyOfRange(pos, lineEnd), Charsets.UTF_8).trim()
            if (trimmed == frame.marker) {
                // The closing marker line; the whole line is HeredocClose.
                flushBuffer()
                stack.removeAt(stack.size - 1)
                emitKind(HclTokenKind.HeredocClose, pos, lineEnd)
                if (lineEnd < end) {
                    emitKind(HclTokenKind.LineBreak, lineEnd, lineEnd + 1)
                    pos = lineEnd + 1
                } else {
                    pos = lineEnd
                }
                return
            }
        }
        scanHeredocLine(frame, lineEnd)
    }

    /** Scans one heredoc content line: the whole line (including the CR of
     * a line-ending CRLF) is one `HeredocContent` piece; `${`/`%{`
     * sequences stay inside the piece and are re-scanned by the parser
     * (RFC 0014 §4.5; lexer.rs). */
    private fun scanHeredocLine(frame: Frame.Heredoc, lineEnd: Int) {
        val runStart = pos
        while (pos < lineEnd) {
            val byte = bytes[pos]
            if (byte == '\r'.code.toByte()) {
                if (pos + 1 == lineEnd && byteAt(1) == '\n'.code.toByte()) {
                    // The CR of a line-ending CRLF stays inside the
                    // content run; the newline after it is a LineBreak.
                    pos += 1
                } else {
                    emitErrorRegion(pos, pos + 1, HCL_PARSE_LONE_CR, DiagnosticCategory.Lexical)
                    pos += 1
                }
            } else {
                pos += scalarWidth(scalarAt(pos))
            }
        }
        endRun(runStart, HclTokenKind.HeredocContent)
        if (lineEnd < end) {
            emitKind(HclTokenKind.LineBreak, lineEnd, lineEnd + 1)
            pos = lineEnd + 1
        } else {
            pos = lineEnd
        }
        frame.lines += 1
        checkLimit(HCL_LIMIT_HEREDOC_LINES, "heredoc-lines", frame.lines, limits.maxHeredocLines)
        noteHeredocContent()
    }

    /** Terminates an unterminated heredoc: the buffered content is discarded
     * and the content becomes one error region to end of file (bounded by
     * the heredoc size limits), with `hcl.parse.unterminated-heredoc@1`
     * (RFC 0014 §3, §4.5; lexer.rs). */
    private fun terminateHeredoc(endBoundary: Int) {
        val frame = stack.last() as Frame.Heredoc
        stack.removeAt(stack.size - 1)
        emitErrorRegion(frame.contentStart, endBoundary, HCL_PARSE_UNTERMINATED_HEREDOC, DiagnosticCategory.Syntax)
        pos = endBoundary
    }

    private fun noteHeredocContent() {
        val frame = stack.lastOrNull() as? Frame.Heredoc ?: return
        val bytes = pos - frame.contentStart
        checkLimit(HCL_LIMIT_HEREDOC_BYTES, "heredoc-bytes", bytes, limits.maxHeredocBytes)
        checkLimit(HCL_LIMIT_TEMPLATE_LEN, "template-len", bytes, limits.maxTemplateLen)
    }

    // -- interpolation/directive absorption (lexer.rs) --

    private fun openInterpolation(directive: Boolean) {
        val openStart = pos
        pos += 2
        if (byteAt(0) == '~'.code.toByte()) {
            pos += 1
        }
        val top = stack.lastOrNull()
        if (top is Frame.Quoted || top is Frame.Heredoc) {
            val count = if (top is Frame.Quoted) {
                top.interpolations += 1
                top.interpolations
            } else {
                val heredoc = top as Frame.Heredoc
                heredoc.interpolations += 1
                heredoc.interpolations
            }
            checkLimit(
                HCL_LIMIT_TEMPLATE_INTERPOLATIONS,
                "template-interpolations",
                count,
                limits.maxTemplateInterpolations,
            )
        }
        checkTemplateDepth()
        emitKind(
            if (directive) HclTokenKind.DirectiveOpen else HclTokenKind.InterpolationOpen,
            openStart,
            pos,
        )
        stack.add(Frame.Interp(directive, pos, 0))
    }

    private fun scanAbsorb(frame: Frame.Interp) {
        when (val byte = bytes[pos]) {
            '{'.code.toByte() -> {
                frame.depth += 1
                pos += 1
            }
            '}'.code.toByte(), '~'.code.toByte() -> {
                var closeWidth: Int? = null
                if (byte == '~'.code.toByte()) {
                    if (frame.depth == 0 && byteAt(1) == '}'.code.toByte()) {
                        closeWidth = 2
                    }
                } else if (frame.depth == 0) {
                    closeWidth = 1
                }
                if (closeWidth != null) {
                    val closeStart = pos
                    pos += closeWidth
                    val contentKind = if (frame.directive) {
                        HclTokenKind.DirectiveContent
                    } else {
                        HclTokenKind.InterpolationContent
                    }
                    val closeKind = if (frame.directive) {
                        HclTokenKind.DirectiveClose
                    } else {
                        HclTokenKind.InterpolationClose
                    }
                    emitKind(contentKind, frame.interiorStart, closeStart)
                    emitKind(closeKind, closeStart, pos)
                    stack.removeAt(stack.size - 1)
                } else if (byte == '}'.code.toByte()) {
                    frame.depth -= 1
                    pos += 1
                } else {
                    emitErrorRegion(pos, pos + 1, HCL_PARSE_INVALID_CHARACTER, DiagnosticCategory.Syntax)
                    pos += 1
                }
            }
            '"'.code.toByte() -> {
                // A nested quoted template inside the interior is absorbed
                // as part of the content piece; the parser re-lexes the
                // interior.
                pos += 1
                while (pos < end) {
                    val next = bytes[pos]
                    if (next == '\\'.code.toByte() && pos + 1 < end) {
                        pos += 2
                    } else if (next == '"'.code.toByte()) {
                        pos += 1
                        break
                    } else {
                        pos += 1
                    }
                }
            }
            '<'.code.toByte() -> {
                if (byteAt(1) == '<'.code.toByte()) {
                    // A nested heredoc inside the interior is absorbed; the
                    // parser re-lexes the interior.
                    pos += 2
                    if (byteAt(0) == '-'.code.toByte()) {
                        pos += 1
                    }
                    while (pos < end && (isIdentifierContinue(scalarAt(pos)) || bytes[pos] == '-'.code.toByte())) {
                        pos += scalarWidth(scalarAt(pos))
                    }
                } else if (byteAt(1) == '='.code.toByte()) {
                    pos += 2
                } else {
                    pos += 1
                }
            }
            '>'.code.toByte(), '!'.code.toByte() -> {
                if (byteAt(1) == '='.code.toByte()) {
                    pos += 2
                } else {
                    pos += 1
                }
            }
            '='.code.toByte() -> {
                if (byteAt(1) == '='.code.toByte() || byteAt(1) == '>'.code.toByte()) {
                    pos += 2
                } else {
                    pos += 1
                }
            }
            '&'.code.toByte() -> {
                if (byteAt(1) == '&'.code.toByte()) {
                    pos += 2
                } else {
                    recover(HCL_PARSE_INVALID_CHARACTER, pos, pos + 1)
                    pos += 1
                }
            }
            '|'.code.toByte() -> {
                if (byteAt(1) == '|'.code.toByte()) {
                    pos += 2
                } else {
                    recover(HCL_PARSE_INVALID_CHARACTER, pos, pos + 1)
                    pos += 1
                }
            }
            ':'.code.toByte() -> {
                if (byteAt(1) == ':'.code.toByte()) {
                    recover(HCL_PARSE_INVALID_CHARACTER, pos, pos + 2)
                    pos += 2
                } else {
                    pos += 1
                }
            }
            '.'.code.toByte() -> {
                if (byteAt(1) == '.'.code.toByte() && byteAt(2) == '.'.code.toByte()) {
                    pos += 3
                } else {
                    pos += 1
                }
            }
            '+'.code.toByte(), '-'.code.toByte(), '*'.code.toByte(), '%'.code.toByte(),
            '?'.code.toByte(), ','.code.toByte(), '('.code.toByte(), ')'.code.toByte(),
            '['.code.toByte(), ']'.code.toByte(), ' '.code.toByte(), '\t'.code.toByte(),
            -> pos += 1
            '\\'.code.toByte(), '$'.code.toByte() -> {
                recover(HCL_PARSE_INVALID_CHARACTER, pos, pos + 1)
                pos += 1
            }
            '\n'.code.toByte() -> {
                // An unterminated interpolation or directive is an error
                // region covering the remainder of the template (RFC 0014
                // §3).
                val top = stack.lastOrNull()
                if (top is Frame.Interp) {
                    stack.removeAt(stack.size - 1)
                }
                val boundary = if (stack.lastOrNull() is Frame.Quoted) endOfLine() else pos
                emitErrorRegion(
                    frame.interiorStart,
                    boundary,
                    if (frame.directive) HCL_PARSE_UNTERMINATED_DIRECTIVE else HCL_PARSE_UNTERMINATED_INTERPOLATION,
                    DiagnosticCategory.Syntax,
                )
                pos = boundary
            }
            '\r'.code.toByte() -> {
                if (byteAt(1) == '\n'.code.toByte()) {
                    val top = stack.lastOrNull()
                    if (top is Frame.Interp) {
                        stack.removeAt(stack.size - 1)
                    }
                    emitErrorRegion(
                        frame.interiorStart,
                        pos,
                        if (frame.directive) HCL_PARSE_UNTERMINATED_DIRECTIVE else HCL_PARSE_UNTERMINATED_INTERPOLATION,
                        DiagnosticCategory.Syntax,
                    )
                    pos += 1
                } else {
                    recover(HCL_PARSE_LONE_CR, pos, pos + 1, DiagnosticCategory.Lexical)
                    pos += 1
                }
            }
            '/'.code.toByte() -> {
                if (byteAt(1) == '/'.code.toByte() || byteAt(1) == '*'.code.toByte()) {
                    // Comments inside the interior are absorbed; the parser
                    // re-lexes the interior.
                    pos += 2
                    if (bytes[pos - 1] == '/'.code.toByte()) {
                        while (pos < end && bytes[pos] != '\n'.code.toByte() && bytes[pos] != '\r'.code.toByte()) {
                            pos += 1
                        }
                    } else {
                        while (pos + 1 < end && !(bytes[pos] == '*'.code.toByte() && bytes[pos + 1] == '/'.code.toByte())) {
                            pos += 1
                        }
                        if (pos + 1 < end) {
                            pos += 2
                        }
                    }
                } else {
                    pos += 1
                }
            }
            '#'.code.toByte() -> {
                while (pos < end && bytes[pos] != '\n'.code.toByte() && bytes[pos] != '\r'.code.toByte()) {
                    pos += 1
                }
            }
            else -> {
                val scalar = scalarAt(pos)
                if (scalar == 0xFEFF) {
                    recover(HCL_PARSE_BYTE_ORDER_MARK, pos, pos + 3)
                    pos += 3
                } else if (scalar == '_'.code) {
                    recover(HCL_PARSE_IDENTIFIER, pos, pos + 1)
                    pos += 1
                } else {
                    pos += scalarWidth(scalar)
                }
            }
        }
    }

    /** The end of the current line (the newline position or end of source),
     * used as the unterminated-string recovery boundary (RFC 0014 §3). */
    private fun endOfLine(): Int {
        for (index in pos until end) {
            if (bytes[index] == '\n'.code.toByte() || bytes[index] == '\r'.code.toByte()) {
                return index
            }
        }
        return end
    }

    private fun findLineEnd(): Int {
        for (index in pos until end) {
            if (bytes[index] == '\n'.code.toByte()) {
                return index
            }
        }
        return end
    }

    /** Terminates frames still open at the region end (unterminated string
     * at EOF or an unterminated heredoc at EOF). */
    private fun terminateAtEnd() {
        while (stack.isNotEmpty()) {
            when (val top = stack.last()) {
                is Frame.Quoted -> terminateString(end)
                is Frame.Heredoc -> terminateHeredoc(end)
                is Frame.Interp -> {
                    // An unterminated interpolation or directive inside a
                    // template is an error region covering the remainder of
                    // the template (RFC 0014 §3).
                    stack.removeAt(stack.size - 1)
                    emitErrorRegion(
                        top.interiorStart,
                        end,
                        if (top.directive) HCL_PARSE_UNTERMINATED_DIRECTIVE else HCL_PARSE_UNTERMINATED_INTERPOLATION,
                        DiagnosticCategory.Syntax,
                    )
                }
            }
        }
    }

    // -- emission and limits ----------------------------------------------

    /** Emits one token, buffering it when an open quoted/heredoc template
     * owns the current position (lexer.rs). The buffering keys on
     * the bottom frame: tokens emitted inside an absorbed interpolation or
     * directive interior (including the content/close tokens) stay buffered
     * until their enclosing template closes, so the stream stays in source
     * order. */
    private fun emit(token: HclToken) {
        val count = tokens.size + bufferedCount() + 1
        checkLimit(HCL_LIMIT_TOKEN_COUNT, "token-count", count, limits.common.maxTokenCount)
        checkLimit(HCL_LIMIT_SYNTAX_PIECES, "syntax-pieces", count, limits.maxSyntaxPieces)
        when (val bottom = stack.firstOrNull()) {
            is Frame.Quoted -> bottom.buffer.add(token)
            is Frame.Heredoc -> bottom.buffer.add(token)
            else -> tokens.add(token)
        }
    }

    private fun bufferedCount(): Int =
        stack.filterIsInstance<Frame.Quoted>().sumOf { it.buffer.size } +
            stack.filterIsInstance<Frame.Heredoc>().sumOf { it.buffer.size }

    /** Emits one token; tokens are always recorded — the piece index is
     * derived from the token stream afterwards (lexer.rs). */
    private fun emitKind(kind: HclTokenKind, tokenStart: Int, tokenEnd: Int) {
        emit(HclToken(kind, tokenStart, tokenEnd))
    }

    /** Emits one error-region token and records its recovery fact
     * (lexer.rs). Region lexes (interpolation/directive
     * interiors) record the regions and diagnostics for the parser to merge
     * but skip the ErrorRegion token, which the parser treats as a fresh
     * failure. */
    private fun emitErrorRegion(
        regionStart: Int,
        regionEnd: Int,
        code: String,
        category: DiagnosticCategory,
    ) {
        recovered = true
        diagnostics.add(
            HclDiagnostic(
                code = code,
                category = category,
                severity = Severity.Error,
                startByte = regionStart,
                endByte = regionEnd,
                arguments = emptyMap(),
                notes = emptyList(),
                occurrence = 0,
            ),
        )
        if (regionEnd > regionStart) {
            if (recordErrorTokens) {
                emit(HclToken(HclTokenKind.ErrorRegion, regionStart, regionEnd))
            }
            errorRegions.add(HclErrorRegion(authority.span(regionStart, regionEnd), code))
            checkLimit(HCL_LIMIT_RECOVERY_REGIONS, "recovery-regions", errorRegions.size, limits.maxRecoveryRegions)
            checkLimit(HCL_LIMIT_ERROR_REGIONS, "error-regions", errorRegions.size, limits.maxErrorRegions)
        }
    }

    /** Records one recovery diagnostic without a piece (absorbed interiors
     * and zero-length regions; lexer.rs). */
    private fun recover(
        code: String,
        regionStart: Int,
        regionEnd: Int,
        category: DiagnosticCategory = DiagnosticCategory.Syntax,
    ) {
        recovered = true
        diagnostics.add(
            HclDiagnostic(
                code = code,
                category = category,
                severity = Severity.Error,
                startByte = regionStart,
                endByte = regionEnd,
                arguments = emptyMap(),
                notes = emptyList(),
                occurrence = 0,
            ),
        )
    }

    private fun flushBuffer() {
        when (val top = stack.lastOrNull()) {
            is Frame.Quoted -> {
                tokens.addAll(top.buffer)
                top.buffer.clear()
            }
            is Frame.Heredoc -> {
                tokens.addAll(top.buffer)
                top.buffer.clear()
            }
            else -> Unit
        }
    }

    private fun checkTemplateDepth() {
        val depth = stack.size + 1
        checkLimit(HCL_LIMIT_TEMPLATE_DEPTH, "template-depth", depth, limits.maxTemplateDepth)
    }

    /** One fatal limit failure; no partial Document exists (RFC 0014 §11,
     * hard gate 4). */
    private fun checkLimit(code: String, name: String, observed: Int, limit: Int) {
        if (observed > limit) {
            throw HclFormationException(listOf(hclLimitDiagnostic(code, name, observed, limit)))
        }
    }

    // -- byte/scalar helpers ----------------------------------------------

    private fun byteAt(offset: Int): Byte? = if (pos + offset < end) bytes[pos + offset] else null

    /** Decodes one UTF-8 scalar at [at]; positions are always scalar
     * boundaries. */
    private fun scalarAt(at: Int): Int {
        val first = bytes[at].toInt() and 0xff
        return when {
            first < 0x80 -> first
            first in 0xc2..0xdf -> ((first and 0x1f) shl 6) or (bytes[at + 1].toInt() and 0x3f)
            first == 0xe0 -> {
                ((first and 0x0f) shl 12) or ((bytes[at + 1].toInt() and 0x3f) shl 6) or
                    (bytes[at + 2].toInt() and 0x3f)
            }
            first in 0xe1..0xec || first in 0xee..0xef -> {
                ((first and 0x0f) shl 12) or ((bytes[at + 1].toInt() and 0x3f) shl 6) or
                    (bytes[at + 2].toInt() and 0x3f)
            }
            first == 0xed -> {
                ((first and 0x0f) shl 12) or ((bytes[at + 1].toInt() and 0x3f) shl 6) or
                    (bytes[at + 2].toInt() and 0x3f)
            }
            else -> {
                ((first and 0x07) shl 18) or ((bytes[at + 1].toInt() and 0x3f) shl 12) or
                    ((bytes[at + 2].toInt() and 0x3f) shl 6) or (bytes[at + 3].toInt() and 0x3f)
            }
        }
    }

    private fun scalarWidth(scalar: Int): Int = when {
        scalar < 0x80 -> 1
        scalar < 0x800 -> 2
        scalar < 0x10000 -> 3
        else -> 4
    }
}

/** UAX #31 identifier start: `ID_Start` with underscore excluded (RFC 0014
 * §4.1; lexer.rs). */
private fun isIdentifierStart(scalar: Int): Boolean =
    scalar != '_'.code && Character.isUnicodeIdentifierStart(scalar)

/** UAX #31 identifier continuation: `ID_Continue` (underscore included);
 * the hyphen continues identifiers (RFC 0014 §4.1; lexer.rs). */
private fun isIdentifierContinue(scalar: Int): Boolean = Character.isUnicodeIdentifierPart(scalar)
