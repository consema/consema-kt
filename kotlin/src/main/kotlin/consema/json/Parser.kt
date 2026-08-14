// The byte-exact JSON/JSONC/JSON5 lexer and recovery parser.
//
// Data authority:
//   - RFC 0005 §3-§6 (https://github.com/consema/consema/blob/main/docs/rfcs/0005-json-family-production-v1.md):
//     JSON5 whitespace is the exact frozen scalar set; IdentifierName keys
//     follow ECMAScript ID_Start/ID_Continue with U+200C/U+200D; string
//     escapes and line continuations; number forms and the frozen non-finite
//     bits; malformed escapes/isolated surrogates recover as explicit error
//     regions and never acquire a decoded name.
//   - conformance/vectors/json-family-v2.json pins the recover/complete
//     outcomes and the diagnostic codes case by case.
//   - https://github.com/consema/consema-rs/blob/main/consema-json/src/parser.rs is the byte-arbitration authority
//     (lexing parser.rs, JSON5 lexing parser.rs, number
//     validation parser.rs, string decoding parser.rs,
//     object/array recovery parser.rs, diagnostic sink
//     parser.rs, deterministic sort consema-core/src/diagnostic.rs
//). consema-go/go/json/parser.go is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the lexer emits immutable
// lexemes over byte offsets; JSON5 classification reads UTF-8 scalars
// directly from the byte buffer with an explicit scalar cursor, so spans stay
// byte-exact without host UTF-16 code-unit arithmetic. Unicode identifier
// classification uses the JDK identifier tables as the host approximation of
// the pinned unicode-id-start 1.4.0 (Unicode 17.0.0) table (RFC 0005 §4);
// characters added after the JDK Unicode version need differential
// verification.

package consema.json

import consema.core.PvDecimal
import consema.document.DocumentAuthority
import consema.document.FormationStatus
import consema.document.LosslessStructuralIndex
import consema.document.ParseLimits
import consema.document.SourceSnapshot
import consema.document.StructuralPiece
import consema.document.StructuralPieceKind
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity
import java.math.BigInteger

/**
 * Parses a complete immutable JSON/JSONC/JSON5 document snapshot
 * (parser.rs). Exceeding a configured limit or failing source
 * construction is fatal and throws [JsonFormationException]; lexical and
 * syntactic recovery never throws and produces a Recovered document.
 */
fun parse(
    bytes: ByteArray,
    profile: JsonProfile,
    limits: ParseLimits = ParseLimits.default,
): Document {
    if (bytes.size > limits.maxSourceBytes) {
        throw resourceLimit("source-bytes", bytes.size, limits.maxSourceBytes)
    }
    val source = try {
        SourceSnapshot.fromUtf8(bytes)
    } catch (e: consema.document.SourceException) {
        throw wrapSourceError(e)
    }
    val authority = DocumentAuthority.fresh()
    val sink = DiagnosticSink(limits.maxDiagnostics)
    val lexed = if (profile.isJson5()) {
        lexJson5(bytes, authority, limits, sink)
    } else {
        lex(bytes, profile, authority, limits, sink)
    }
    val syntaxKinds = lexed.lexemes.map { it.classKind.syntaxKind() }
    val pieces = lexed.lexemes.map { lexeme ->
        val kind = when (lexeme.classKind) {
            is LexemeClass.Token -> StructuralPieceKind.Token
            is LexemeClass.Trivia -> StructuralPieceKind.Trivia
            is LexemeClass.Error -> StructuralPieceKind.ErrorRegion
        }
        StructuralPiece(authority.span(lexeme.start, lexeme.end), kind)
    }
    val structuralIndex =
        LosslessStructuralIndex.new(authority.identity, source.len, pieces)

    val parser = Parser(
        source = source.decodedText()!!,
        sourceBytes = bytes,
        profile = profile,
        authority = authority,
        tokens = lexed.tokens,
        sink = sink,
        recovered = lexed.recovered,
        limits = limits,
    )
    val root = parser.parseValue(0)
    if (parser.position < parser.tokens.size) {
        val token = parser.tokens[parser.position]
        parser.syntaxDiagnostic(
            "json.syntax.trailing-content@1",
            token.start,
            parser.tokens.last().end,
        )
        parser.recovered = true
    }
    val formationStatus = if (parser.recovered) FormationStatus.Recovered else FormationStatus.Complete
    val entities = parser.entities
    val diagnostics = sink.finish().sortedWith(deterministicDiagnosticOrder)
    return Document(
        authority = authority,
        source = source,
        profile = profile,
        structuralIndex = structuralIndex,
        syntaxKinds = syntaxKinds,
        formationStatus = formationStatus,
        diagnosticsList = diagnostics,
        entities = entities,
        rootIndex = root,
        parseLimits = limits,
    )
}

/** Wraps a source construction failure with the frozen code mapping of
 * FatalFormationFailure::source_error (lib.rs). */
private fun wrapSourceError(error: consema.document.SourceException): JsonFormationException =
    when (error.kind) {
        consema.document.SourceErrorKind.INVALID_UTF8 ->
            JsonFormationException(
                "core.source.invalid-utf8@1",
                "json parse: source is not valid UTF-8 at byte ${error.byteOffset}",
                cause = error,
            )

        consema.document.SourceErrorKind.INVALID_SEQUENCE ->
            JsonFormationException(
                "core.source.invalid-sequence@1",
                "json parse: invalid source sequence",
                cause = error,
            )

        consema.document.SourceErrorKind.ENCODING_CONFLICT ->
            JsonFormationException(
                "core.source.encoding-conflict@1",
                "json parse: source encoding facts conflict",
                cause = error,
            )

        consema.document.SourceErrorKind.UNSUPPORTED_BOM ->
            JsonFormationException(
                "core.source.unsupported-bom@1",
                "json parse: unsupported byte-order mark",
                cause = error,
            )

        consema.document.SourceErrorKind.RESOURCE_LIMIT, consema.document.SourceErrorKind.OFFSET_OVERFLOW ->
            JsonFormationException(
                "core.source.resource-limit@1",
                "json parse: source construction limit reached",
                name = error.name ?: "",
                observed = error.observed,
                limit = error.limit,
                cause = error,
            )
    }

/** Deterministic diagnostic order (consema-core/src/diagnostic.rs):
 * primary start (missing primary sorts last), category, code, occurrence. */
internal val deterministicDiagnosticOrder: Comparator<Diagnostic> =
    compareBy<Diagnostic> { it.primary?.startByte ?: ULong.MAX_VALUE }
        .thenBy { it.category.ordinal }
        .thenBy { it.code }
        .thenBy { it.occurrence }

// ---------------------------------------------------------------------------
// Lexing
// ---------------------------------------------------------------------------

internal enum class TokenKind {
    LeftBrace,
    RightBrace,
    LeftBracket,
    RightBracket,
    Colon,
    Comma,
    String,
    Identifier,
    Number,
    True,
    False,
    Null,
}

internal data class Token(val kind: TokenKind, val start: Int, val end: Int)

internal data class Lexeme(val start: Int, val end: Int, val classKind: LexemeClass)

internal sealed class LexemeClass {
    data class Token(val kind: TokenKind) : LexemeClass()

    data class Trivia(val kind: JsonSyntaxKind) : LexemeClass()

    data object Error : LexemeClass()

    fun syntaxKind(): JsonSyntaxKind =
        when (this) {
            is Token -> when (kind) {
                TokenKind.LeftBrace -> JsonSyntaxKind.LeftBrace
                TokenKind.RightBrace -> JsonSyntaxKind.RightBrace
                TokenKind.LeftBracket -> JsonSyntaxKind.LeftBracket
                TokenKind.RightBracket -> JsonSyntaxKind.RightBracket
                TokenKind.Colon -> JsonSyntaxKind.Colon
                TokenKind.Comma -> JsonSyntaxKind.Comma
                TokenKind.String -> JsonSyntaxKind.String
                TokenKind.Identifier -> JsonSyntaxKind.Identifier
                TokenKind.Number -> JsonSyntaxKind.Number
                TokenKind.True -> JsonSyntaxKind.True
                TokenKind.False -> JsonSyntaxKind.False
                TokenKind.Null -> JsonSyntaxKind.Null
            }

            is Trivia -> kind
            Error -> JsonSyntaxKind.ErrorRegion
        }
}

internal class Lexed(
    val lexemes: List<Lexeme>,
    val tokens: List<Token>,
    val recovered: Boolean,
)

/** Lexes strict JSON and JSONC on exact UTF-8 bytes (parser.rs). */
private fun lex(
    bytes: ByteArray,
    profile: JsonProfile,
    authority: DocumentAuthority,
    limits: ParseLimits,
    sink: DiagnosticSink,
): Lexed {
    val lexemes = ArrayList<Lexeme>()
    val tokens = ArrayList<Token>()
    var offset = 0
    var recovered = false
    if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) {
        lexemes.add(Lexeme(0, 3, LexemeClass.Trivia(JsonSyntaxKind.Bom)))
        if (profile == JsonProfile.StrictV1) {
            sink.push(
                sourceDiagnostic(
                    authority,
                    "json.strict.leading-bom@1",
                    DiagnosticCategory.Conformance,
                    Severity.Warning,
                    0,
                    3,
                    sink.nextOccurrence(),
                ),
            )
        }
        offset = 3
    }
    while (offset < bytes.size) {
        val start = offset
        val octet = bytes[offset].toInt() and 0xff
        var classKind: LexemeClass = when {
            octet == 0x20 || octet == 0x09 || octet == 0x0d || octet == 0x0a -> {
                offset += 1
                while (offset < bytes.size) {
                    val next = bytes[offset].toInt() and 0xff
                    if (next != 0x20 && next != 0x09 && next != 0x0d && next != 0x0a) break
                    offset += 1
                }
                LexemeClass.Trivia(JsonSyntaxKind.Whitespace)
            }
            octet == 0x2f && offset + 1 < bytes.size && bytes[offset + 1] == 0x2f.toByte() -> {
                offset += 2
                while (offset < bytes.size) {
                    val next = bytes[offset].toInt() and 0xff
                    if (next == 0x0d || next == 0x0a) break
                    offset += 1
                }
                if (!profile.permitsJsoncExtensions()) {
                    recovered = true
                    sink.push(
                        sourceDiagnostic(
                            authority,
                            "json.strict.comment-not-allowed@1",
                            DiagnosticCategory.Conformance,
                            Severity.Error,
                            start,
                            offset,
                            sink.nextOccurrence(),
                        ),
                    )
                }
                LexemeClass.Trivia(JsonSyntaxKind.LineComment)
            }
            octet == 0x2f && offset + 1 < bytes.size && bytes[offset + 1] == 0x2a.toByte() -> {
                offset += 2
                var closed = false
                while (offset + 1 < bytes.size) {
                    if (bytes[offset] == 0x2a.toByte() && bytes[offset + 1] == 0x2f.toByte()) {
                        offset += 2
                        closed = true
                        break
                    }
                    offset += 1
                }
                if (closed) {
                    if (!profile.permitsJsoncExtensions()) {
                        recovered = true
                        sink.push(
                            sourceDiagnostic(
                                authority,
                                "json.strict.comment-not-allowed@1",
                                DiagnosticCategory.Conformance,
                                Severity.Error,
                                start,
                                offset,
                                sink.nextOccurrence(),
                            ),
                        )
                    }
                    LexemeClass.Trivia(JsonSyntaxKind.BlockComment)
                } else {
                    offset = bytes.size
                    recovered = true
                    sink.push(
                        sourceDiagnostic(
                            authority,
                            "json.syntax.unterminated-block-comment@1",
                            DiagnosticCategory.Syntax,
                            Severity.Error,
                            start,
                            offset,
                            sink.nextOccurrence(),
                        ),
                    )
                    LexemeClass.Error
                }
            }
            octet == 0x7b -> {
                offset += 1
                LexemeClass.Token(TokenKind.LeftBrace)
            }
            octet == 0x7d -> {
                offset += 1
                LexemeClass.Token(TokenKind.RightBrace)
            }
            octet == 0x5b -> {
                offset += 1
                LexemeClass.Token(TokenKind.LeftBracket)
            }
            octet == 0x5d -> {
                offset += 1
                LexemeClass.Token(TokenKind.RightBracket)
            }
            octet == 0x3a -> {
                offset += 1
                LexemeClass.Token(TokenKind.Colon)
            }
            octet == 0x2c -> {
                offset += 1
                LexemeClass.Token(TokenKind.Comma)
            }
            octet == 0x22 -> {
                offset += 1
                var escaped = false
                var closed = false
                while (offset < bytes.size) {
                    val next = bytes[offset].toInt() and 0xff
                    offset += 1
                    if (escaped) {
                        escaped = false
                    } else if (next == 0x5c) {
                        escaped = true
                    } else if (next == 0x22) {
                        closed = true
                        break
                    }
                }
                if (closed) {
                    LexemeClass.Token(TokenKind.String)
                } else {
                    recovered = true
                    sink.push(
                        sourceDiagnostic(
                            authority,
                            "json.syntax.unterminated-string@1",
                            DiagnosticCategory.Syntax,
                            Severity.Error,
                            start,
                            offset,
                            sink.nextOccurrence(),
                        ),
                    )
                    LexemeClass.Error
                }
            }
            octet == 0x2d || octet in 0x30..0x39 -> {
                offset += 1
                while (offset < bytes.size) {
                    val next = bytes[offset].toInt() and 0xff
                    if (!(next in 0x30..0x39 || next == 0x2b || next == 0x2d ||
                            next == 0x2e || next == 0x65 || next == 0x45)
                    ) {
                        break
                    }
                    offset += 1
                }
                if (validJsonNumber(bytes, start, offset)) {
                    LexemeClass.Token(TokenKind.Number)
                } else {
                    recovered = true
                    sink.push(
                        sourceDiagnostic(
                            authority,
                            "json.syntax.invalid-number@1",
                            DiagnosticCategory.Syntax,
                            Severity.Error,
                            start,
                            offset,
                            sink.nextOccurrence(),
                        ),
                    )
                    LexemeClass.Error
                }
            }
            octet in 0x61..0x7a || octet in 0x41..0x5a || octet == 0x5f -> {
                offset += 1
                while (offset < bytes.size) {
                    val next = bytes[offset].toInt() and 0xff
                    if (!(next in 0x61..0x7a || next in 0x41..0x5a ||
                            next in 0x30..0x39 || next == 0x5f)
                    ) {
                        break
                    }
                    offset += 1
                }
                when (String(bytes, start, offset - start, Charsets.US_ASCII)) {
                    "true" -> LexemeClass.Token(TokenKind.True)
                    "false" -> LexemeClass.Token(TokenKind.False)
                    "null" -> LexemeClass.Token(TokenKind.Null)
                    else -> {
                        recovered = true
                        sink.push(
                            sourceDiagnostic(
                                authority,
                                "json.syntax.unexpected-word@1",
                                DiagnosticCategory.Syntax,
                                Severity.Error,
                                start,
                                offset,
                                sink.nextOccurrence(),
                            ),
                        )
                        LexemeClass.Error
                    }
                }
            }
            else -> {
                val width = utf8Width(octet)
                offset = (offset + width).coerceAtMost(bytes.size)
                recovered = true
                sink.push(
                    sourceDiagnostic(
                        authority,
                        "json.syntax.unexpected-character@1",
                        DiagnosticCategory.Syntax,
                        Severity.Error,
                        start,
                        offset,
                        sink.nextOccurrence(),
                    ),
                )
                LexemeClass.Error
            }
        }
        lexemes.add(Lexeme(start, offset, classKind))
        if (classKind is LexemeClass.Token) {
            tokens.add(Token(classKind.kind, start, offset))
        }
        if (lexemes.size > limits.maxTokenCount) {
            throw resourceLimit("token-count", lexemes.size, limits.maxTokenCount)
        }
    }
    return Lexed(lexemes, tokens, recovered)
}

/** Lexes Standard JSON5 on decoded UTF-8 scalars (parser.rs). */
private fun lexJson5(
    bytes: ByteArray,
    authority: DocumentAuthority,
    limits: ParseLimits,
    sink: DiagnosticSink,
): Lexed {
    val lexemes = ArrayList<Lexeme>()
    val tokens = ArrayList<Token>()
    var offset = 0
    var recovered = false
    if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) {
        lexemes.add(Lexeme(0, 3, LexemeClass.Trivia(JsonSyntaxKind.Bom)))
        offset = 3
    }
    while (offset < bytes.size) {
        val start = offset
        val scalar = readScalar(bytes, offset)
        var classKind: LexemeClass = if (isJson5Whitespace(scalar)) {
            offset += scalarWidth(scalar)
            while (offset < bytes.size && isJson5Whitespace(readScalar(bytes, offset))) {
                offset += scalarWidth(readScalar(bytes, offset))
            }
            LexemeClass.Trivia(JsonSyntaxKind.Whitespace)
        } else if (startsWith(bytes, offset, "//")) {
            offset += 2
            while (offset < bytes.size && !isJson5LineTerminator(readScalar(bytes, offset))) {
                offset += scalarWidth(readScalar(bytes, offset))
            }
            LexemeClass.Trivia(JsonSyntaxKind.LineComment)
        } else if (startsWith(bytes, offset, "/*")) {
            offset += 2
            var closed = false
            while (offset < bytes.size) {
                if (startsWith(bytes, offset, "*/")) {
                    offset += 2
                    closed = true
                    break
                }
                offset += scalarWidth(readScalar(bytes, offset))
            }
            if (closed) {
                LexemeClass.Trivia(JsonSyntaxKind.BlockComment)
            } else {
                recovered = true
                sink.push(
                    sourceDiagnostic(
                        authority,
                        "json.syntax.unterminated-block-comment@1",
                        DiagnosticCategory.Syntax,
                        Severity.Error,
                        start,
                        offset,
                        sink.nextOccurrence(),
                    ),
                )
                LexemeClass.Error
            }
        } else {
            when (scalar) {
                0x7b -> {
                    offset += 1
                    LexemeClass.Token(TokenKind.LeftBrace)
                }
                0x7d -> {
                    offset += 1
                    LexemeClass.Token(TokenKind.RightBrace)
                }
                0x5b -> {
                    offset += 1
                    LexemeClass.Token(TokenKind.LeftBracket)
                }
                0x5d -> {
                    offset += 1
                    LexemeClass.Token(TokenKind.RightBracket)
                }
                0x3a -> {
                    offset += 1
                    LexemeClass.Token(TokenKind.Colon)
                }
                0x2c -> {
                    offset += 1
                    LexemeClass.Token(TokenKind.Comma)
                }
                0x27, 0x22 -> {
                    val quote = scalar
                    offset += scalarWidth(scalar)
                    var closed = false
                    while (offset < bytes.size) {
                        val current = readScalar(bytes, offset)
                        offset += scalarWidth(current)
                        if (current == 0x5c) {
                            if (offset < bytes.size) {
                                val escaped = readScalar(bytes, offset)
                                offset += scalarWidth(escaped)
                                if (escaped == 0x0d && startsWith(bytes, offset, "\n")) {
                                    offset += 1
                                }
                            }
                        } else if (current == quote) {
                            closed = true
                            break
                        }
                    }
                    if (closed) {
                        LexemeClass.Token(TokenKind.String)
                    } else {
                        recovered = true
                        sink.push(
                            sourceDiagnostic(
                                authority,
                                "json.syntax.unterminated-string@1",
                                DiagnosticCategory.Syntax,
                                Severity.Error,
                                start,
                                offset,
                                sink.nextOccurrence(),
                            ),
                        )
                        LexemeClass.Error
                    }
                }
                else -> {
                    val isNumberStart = scalar == 0x2b || scalar == 0x2d ||
                        scalar in 0x30..0x39 ||
                        (scalar == 0x2e && offset + 1 < bytes.size &&
                            readScalar(bytes, offset + 1) in 0x30..0x39)
                    if (isNumberStart) {
                        offset = scanJson5NumberCandidate(bytes, offset)
                        val text = String(bytes, start, offset - start, Charsets.UTF_8)
                        if (validJson5Number(text)) {
                            LexemeClass.Token(TokenKind.Number)
                        } else {
                            recovered = true
                            sink.push(
                                sourceDiagnostic(
                                    authority,
                                    "json.syntax.invalid-number@1",
                                    DiagnosticCategory.Syntax,
                                    Severity.Error,
                                    start,
                                    offset,
                                    sink.nextOccurrence(),
                                ),
                            )
                            LexemeClass.Error
                        }
                    } else if (scalar == 0x5c || isJson5IdentifierStart(scalar)) {
                        val scan = scanJson5Identifier(bytes, start)
                        offset = scan.first
                        if (scan.second) {
                            LexemeClass.Token(TokenKind.Identifier)
                        } else {
                            recovered = true
                            sink.push(
                                sourceDiagnostic(
                                    authority,
                                    "json5.syntax.invalid-identifier@1",
                                    DiagnosticCategory.Syntax,
                                    Severity.Error,
                                    start,
                                    offset,
                                    sink.nextOccurrence(),
                                ),
                            )
                            LexemeClass.Error
                        }
                    } else {
                        offset += scalarWidth(scalar)
                        recovered = true
                        sink.push(
                            sourceDiagnostic(
                                authority,
                                "json.syntax.unexpected-character@1",
                                DiagnosticCategory.Syntax,
                                Severity.Error,
                                start,
                                offset,
                                sink.nextOccurrence(),
                            ),
                        )
                        LexemeClass.Error
                    }
                }
            }
        }
        lexemes.add(Lexeme(start, offset, classKind))
        if (classKind is LexemeClass.Token) {
            tokens.add(Token(classKind.kind, start, offset))
        }
        if (lexemes.size > limits.maxTokenCount) {
            throw resourceLimit("token-count", lexemes.size, limits.maxTokenCount)
        }
    }
    return Lexed(lexemes, tokens, recovered)
}

/** Reads one UTF-8 scalar at a byte boundary; the source is validated UTF-8
 * by construction. */
private fun readScalar(bytes: ByteArray, offset: Int): Int {
    val first = bytes[offset].toInt() and 0xff
    return when {
        first < 0x80 -> first
        first in 0xc2..0xdf ->
            ((first and 0x1f) shl 6) or (bytes[offset + 1].toInt() and 0x3f)

        first in 0xe0..0xef ->
            ((first and 0x0f) shl 12) or
                ((bytes[offset + 1].toInt() and 0x3f) shl 6) or
                (bytes[offset + 2].toInt() and 0x3f)

        else ->
            ((first and 0x07) shl 18) or
                ((bytes[offset + 1].toInt() and 0x3f) shl 12) or
                ((bytes[offset + 2].toInt() and 0x3f) shl 6) or
                (bytes[offset + 3].toInt() and 0x3f)
    }
}

/** UTF-8 byte width of one scalar (the Rust char::len_utf8). */
private fun scalarWidth(scalar: Int): Int =
    when {
        scalar < 0x80 -> 1
        scalar < 0x800 -> 2
        scalar < 0x10000 -> 3
        else -> 4
    }

private fun startsWith(bytes: ByteArray, offset: Int, prefix: String): Boolean {
    val target = prefix.toByteArray(Charsets.UTF_8)
    if (offset + target.size > bytes.size) return false
    for (i in target.indices) {
        if (bytes[offset + i] != target[i]) return false
    }
    return true
}

/** The JSON5 line terminators (parser.rs). */
internal fun isJson5LineTerminator(scalar: Int): Boolean =
    scalar == 0x0a || scalar == 0x0d || scalar == 0x2028 || scalar == 0x2029

/** The exact JSON5 whitespace union (parser.rs; RFC 0005 §3). */
internal fun isJson5Whitespace(scalar: Int): Boolean =
    scalar == 0x09 || scalar == 0x0a || scalar == 0x0b || scalar == 0x0c || scalar == 0x0d ||
        scalar == 0x20 || scalar == 0xa0 || scalar == 0x1680 ||
        scalar in 0x2000..0x200a || scalar == 0x2028 || scalar == 0x2029 ||
        scalar == 0x202f || scalar == 0x205f || scalar == 0x3000 || scalar == 0xfeff

/**
 * JSON5 IdentifierName start
 * (https://github.com/consema/consema-rs/blob/main/consema-json/src/parser.rs 的 is_json5_identifier_start):
 * `$`, `_`, or Unicode
 * ID_Start. The JDK identifier tables approximate the pinned unicode-id-start
 * 1.4.0 (Unicode 17.0.0) table (RFC 0005 §4); newer-script characters need
 * differential verification.
 */
internal fun isJson5IdentifierStart(scalar: Int): Boolean =
    scalar == 0x24 || scalar == 0x5f || Character.isUnicodeIdentifierStart(scalar)

/** JSON5 IdentifierName continue (parser.rs): start characters,
 * Unicode ID_Continue, U+200C, or U+200D. */
internal fun isJson5IdentifierContinue(scalar: Int): Boolean =
    scalar == 0x24 || scalar == 0x5f || scalar == 0x200c || scalar == 0x200d ||
        Character.isUnicodeIdentifierPart(scalar)

/** Scans one JSON5 IdentifierName candidate, decoding `\uXXXX` escapes
 * (parser.rs). Returns (end, valid). */
private fun scanJson5Identifier(bytes: ByteArray, start: Int): Pair<Int, Boolean> {
    var offset = start
    var first = true
    var valid = true
    while (offset < bytes.size) {
        val scalar = readScalar(bytes, offset)
        val (decoded, width) = if (scalar == 0x5c) {
            val decoded = decodeIdentifierEscape(bytes, offset)
            if (decoded != null) {
                decoded to 6
            } else {
                valid = false
                offset = scanJson5InvalidWord(bytes, offset)
                break
            }
        } else {
            scalar to scalarWidth(scalar)
        }
        val permitted = if (first) {
            isJson5IdentifierStart(decoded)
        } else {
            isJson5IdentifierContinue(decoded)
        }
        if (!permitted) {
            if (first || scalar == 0x5c) {
                valid = false
                offset = scanJson5InvalidWord(bytes, offset)
            }
            break
        }
        offset += width
        first = false
    }
    return Pair(offset, valid && !first)
}

/** Extends an invalid identifier word to the next delimiter (parser.rs). */
private fun scanJson5InvalidWord(bytes: ByteArray, start: Int): Int {
    var offset = start
    while (offset < bytes.size) {
        val scalar = readScalar(bytes, offset)
        if (isJson5Whitespace(scalar) || scalar == 0x7b || scalar == 0x7d ||
            scalar == 0x5b || scalar == 0x5d || scalar == 0x3a || scalar == 0x2c ||
            scalar == 0x2f || scalar == 0x27 || scalar == 0x22
        ) {
            break
        }
        offset += scalarWidth(scalar)
    }
    return (offset).coerceAtLeast(start + 1)
}

/** Decodes one `\uXXXX` identifier escape from the raw byte buffer
 * (parser.rs). */
private fun decodeIdentifierEscape(bytes: ByteArray, offset: Int): Int? {
    if (offset + 5 >= bytes.size) return null
    if (bytes[offset] != 0x5c.toByte() || bytes[offset + 1] != 0x75.toByte()) return null
    var value = 0
    for (i in 0 until 4) {
        val digit = hexDigit(bytes[offset + 2 + i].toInt() and 0xff) ?: return null
        value = value * 16 + digit
    }
    return if (Character.isValidCodePoint(value)) value else null
}

/** Scans a JSON5 number candidate (parser.rs). */
private fun scanJson5NumberCandidate(bytes: ByteArray, start: Int): Int {
    var offset = start
    while (offset < bytes.size) {
        val scalar = readScalar(bytes, offset)
        if (!(scalar.isAsciiAlphanumeric() || scalar == 0x2b || scalar == 0x2d ||
                scalar == 0x2e || scalar == 0x5f)
        ) {
            break
        }
        offset += scalarWidth(scalar)
    }
    return offset
}

private fun Int.isAsciiAlphanumeric(): Boolean =
    (this in 0x30..0x39) || (this in 0x41..0x5a) || (this in 0x61..0x7a)

/** Validates one complete strict JSON number (parser.rs). */
private fun validJsonNumber(bytes: ByteArray, start: Int, end: Int): Boolean {
    var index = start
    if (index < end && bytes[index] == 0x2d.toByte()) {
        index += 1
    }
    when {
        index < end && bytes[index] == 0x30.toByte() -> index += 1
        index < end && bytes[index] in 0x31.toByte()..0x39.toByte() -> {
            index += 1
            while (index < end && bytes[index] in 0x30.toByte()..0x39.toByte()) {
                index += 1
            }
        }
        else -> return false
    }
    if (index < end && bytes[index] == 0x2e.toByte()) {
        index += 1
        val fractionStart = index
        while (index < end && bytes[index] in 0x30.toByte()..0x39.toByte()) {
            index += 1
        }
        if (index == fractionStart) return false
    }
    if (index < end && (bytes[index] == 0x65.toByte() || bytes[index] == 0x45.toByte())) {
        index += 1
        if (index < end && (bytes[index] == 0x2b.toByte() || bytes[index] == 0x2d.toByte())) {
            index += 1
        }
        val exponentStart = index
        while (index < end && bytes[index] in 0x30.toByte()..0x39.toByte()) {
            index += 1
        }
        if (index == exponentStart) return false
    }
    return index == end
}

/** Validates one complete JSON5 number (parser.rs). */
internal fun validJson5Number(text: String): Boolean {
    var unsigned = text
    if (unsigned.startsWith("+") || unsigned.startsWith("-")) {
        unsigned = unsigned.substring(1)
    }
    if (unsigned == "Infinity" || unsigned == "NaN") {
        return true
    }
    if (unsigned.startsWith("0x") || unsigned.startsWith("0X")) {
        val hex = unsigned.substring(2)
        return hex.isNotEmpty() && hex.all {
            it.isDigit() || it in 'a'..'f' || it in 'A'..'F'
        }
    }
    var index = 0
    val length = unsigned.length
    if (index < length && unsigned[index] == '.') {
        index += 1
        val start = index
        while (index < length && unsigned[index] in '0'..'9') {
            index += 1
        }
        if (index == start) return false
    } else {
        when {
            index < length && unsigned[index] == '0' -> {
                index += 1
                if (index < length && unsigned[index] in '0'..'9') {
                    return false
                }
            }
            index < length && unsigned[index] in '1'..'9' -> {
                index += 1
                while (index < length && unsigned[index] in '0'..'9') {
                    index += 1
                }
            }
            else -> return false
        }
        if (index < length && unsigned[index] == '.') {
            index += 1
            while (index < length && unsigned[index] in '0'..'9') {
                index += 1
            }
        }
    }
    if (index < length && (unsigned[index] == 'e' || unsigned[index] == 'E')) {
        index += 1
        if (index < length && (unsigned[index] == '+' || unsigned[index] == '-')) {
            index += 1
        }
        val exponentStart = index
        while (index < length && unsigned[index] in '0'..'9') {
            index += 1
        }
        if (index == exponentStart) return false
    }
    return index == length
}

private fun utf8Width(leading: Int): Int =
    when {
        leading in 0x00..0x7f -> 1
        leading in 0xc0..0xdf -> 2
        leading in 0xe0..0xef -> 3
        else -> 4
    }

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

/** Recursive descent parser over the token stream (parser.rs). */
private class Parser(
    private val source: String,
    private val sourceBytes: ByteArray,
    private val profile: JsonProfile,
    private val authority: DocumentAuthority,
    internal val tokens: List<Token>,
    private val sink: DiagnosticSink,
    internal var recovered: Boolean,
    private val limits: ParseLimits,
) {
    /** Slices one token's exact UTF-8 byte range (token offsets are raw
     * byte offsets; [source] is char-based). */
    private fun tokenText(token: Token): String =
        String(sourceBytes, token.start, token.end - token.start, Charsets.UTF_8)
    internal var position = 0
    internal val entities = ArrayList<Entity>()

    internal fun parseValue(depth: Int): Int {
        if (depth > limits.maxNestingDepth) {
            throw resourceLimit("nesting-depth", depth, limits.maxNestingDepth)
        }
        val token = peek() ?: run {
            val offset = source.length
            syntaxDiagnostic("json.syntax.missing-value@1", offset, offset)
            recovered = true
            return allocValue(
                offset,
                offset,
                null,
                false,
                InternalValueKind.Unavailable(SemanticUnavailable.Missing),
            )
        }
        return when (token.kind) {
            TokenKind.Null -> {
                position += 1
                allocScalar(token, InternalValueKind.Null)
            }
            TokenKind.True -> {
                position += 1
                allocScalar(token, InternalValueKind.Boolean(true))
            }
            TokenKind.False -> {
                position += 1
                allocScalar(token, InternalValueKind.Boolean(false))
            }
            TokenKind.Number -> {
                position += 1
                val text = tokenText(token)
                checkNumberDigits(text)
                val kind = if (profile.isJson5()) {
                    parseJson5Number(text)
                } else if (text.indexOfFirst { it == '.' || it == 'e' || it == 'E' } >= 0) {
                    InternalValueKind.Decimal(parseJsonDecimal(text))
                } else {
                    InternalValueKind.Integer(BigInteger(text))
                }
                allocScalar(token, kind)
            }
            TokenKind.String -> {
                position += 1
                val decoded = decodeJsonString(tokenText(token), profile)
                if (decoded != null) {
                    if (decoded.second) {
                        sink.push(
                            sourceDiagnostic(
                                authority,
                                "json5.string.unescaped-line-separator@1",
                                DiagnosticCategory.Conformance,
                                Severity.Warning,
                                token.start,
                                token.end,
                                sink.nextOccurrence(),
                            ),
                        )
                    }
                    allocScalar(token, InternalValueKind.String(decoded.first))
                } else {
                    syntaxDiagnostic("json.syntax.invalid-string-escape@1", token.start, token.end)
                    recovered = true
                    allocValue(
                        token.start,
                        token.end,
                        LiteralRange(token.start, token.end),
                        true,
                        InternalValueKind.Unavailable(SemanticUnavailable.InvalidLiteral),
                    )
                }
            }
            TokenKind.Identifier -> {
                if (!profile.isJson5()) {
                    // The strict/JSONC lexer never emits Identifier tokens;
                    // this arm mirrors the Rust catch-all expected-value path
                    // (parser.rs).
                    position += 1
                    syntaxDiagnostic("json.syntax.expected-value@1", token.start, token.end)
                    recovered = true
                    allocValue(
                        token.start,
                        token.end,
                        null,
                        false,
                        InternalValueKind.Unavailable(SemanticUnavailable.ErrorRegion),
                    )
                } else {
                    position += 1
                    val text = decodeJson5Identifier(tokenText(token))
                    val kind = when (text) {
                        "null" -> InternalValueKind.Null
                        "true" -> InternalValueKind.Boolean(true)
                        "false" -> InternalValueKind.Boolean(false)
                        "Infinity" -> InternalValueKind.BinaryFloat64(0x7ff0_0000_0000_0000L)
                        "NaN" -> InternalValueKind.BinaryFloat64(0x7ff8_0000_0000_0000L)
                        else -> {
                            syntaxDiagnostic("json.syntax.expected-value@1", token.start, token.end)
                            recovered = true
                            InternalValueKind.Unavailable(SemanticUnavailable.ErrorRegion)
                        }
                    }
                    allocScalar(token, kind)
                }
            }
            TokenKind.LeftBrace -> parseObject(depth)
            TokenKind.LeftBracket -> parseArray(depth)
            else -> {
                position += 1
                syntaxDiagnostic("json.syntax.expected-value@1", token.start, token.end)
                recovered = true
                allocValue(
                    token.start,
                    token.end,
                    null,
                    false,
                    InternalValueKind.Unavailable(SemanticUnavailable.ErrorRegion),
                )
            }
        }
    }

    /** O(N²)-amplification guard for one number token (wave 4): counts the
     * coefficient digits plus the exponent digits of the literal (a JSON5
     * hex literal counts its hex digits) and fails fatally — the frozen
     * ResourceLimit code, never a crash, never a silent truncation
     * (RFC 0016 §5.1) — when the per-parser cap is exceeded. */
    private fun checkNumberDigits(text: String) {
        val digits = numberDigitCount(text)
        if (digits > limits.maxNumberDigits) {
            throw resourceLimit("number-digits", digits, limits.maxNumberDigits)
        }
    }

    private fun parseObject(depth: Int): Int {
        val open = consume(TokenKind.LeftBrace)!!
        val members = ArrayList<Int>()
        val names = HashMap<String, Int>()
        while (true) {
            val close = consume(TokenKind.RightBrace)
            if (close != null) {
                return allocValue(
                    open.start,
                    close.end,
                    null,
                    true,
                    InternalValueKind.Object(members),
                )
            }
            if (peek() == null) {
                break
            }
            val ordinal = members.size
            val key = if (peek()?.let { token ->
                    token.kind == TokenKind.String ||
                        (profile.isJson5() && token.kind == TokenKind.Identifier)
                } == true
            ) {
                parseObjectKey(depth + 1)
            } else {
                val offset = currentOffset()
                syntaxDiagnostic("json.syntax.expected-object-key@1", offset, offset)
                recovered = true
                allocValue(
                    offset,
                    offset,
                    null,
                    false,
                    InternalValueKind.Unavailable(SemanticUnavailable.Missing),
                )
            }
            if (consume(TokenKind.Colon) == null) {
                val offset = currentOffset()
                syntaxDiagnostic("json.syntax.missing-colon@1", offset, offset)
                recovered = true
            }
            val value = parseValue(depth + 1)
            val memberStart = entities[key].span.startByte
            val memberEnd = entities[value].span.endByte
            val member = allocEntity(
                Entity.Member(
                    MemberEntity(
                        authority.span(memberStart, memberEnd),
                        key,
                        value,
                        ordinal,
                    ),
                ),
            )
            members.add(member)
            val keyKind = (entities[key] as? Entity.Value)?.entity?.kind
            if (keyKind is InternalValueKind.String) {
                val name = keyKind.value
                val first = names.put(name, member)
                if (first != null) {
                    val firstSpan = entities[first].span
                    sink.push(
                        sourceDiagnostic(
                            authority,
                            "json.object.duplicate-member@1",
                            DiagnosticCategory.Semantic,
                            Severity.Error,
                            entities[member].span.startByte,
                            entities[member].span.endByte,
                            sink.nextOccurrence(),
                            arguments = mapOf("name" to name),
                            related = listOf(
                                consema.protocol.RelatedSourceLocation(
                                    "first-member",
                                    consema.protocol.SourceLocation.of(
                                        authority.identity.asU64.toString(),
                                        firstSpan.startByte.toULong(),
                                        firstSpan.endByte.toULong(),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
            if (consume(TokenKind.Comma) != null) {
                val next = peek()
                if (next != null && next.kind == TokenKind.RightBrace && !profile.permitsJsoncExtensions()) {
                    sink.push(
                        sourceDiagnostic(
                            authority,
                            "json.strict.trailing-comma@1",
                            DiagnosticCategory.Conformance,
                            Severity.Error,
                            (next.start - 1).coerceAtLeast(0),
                            next.start,
                            sink.nextOccurrence(),
                        ),
                    )
                    recovered = true
                }
                continue
            }
            val next = peek()
            if (next != null && next.kind == TokenKind.RightBrace) {
                continue
            }
            val offset = currentOffset()
            syntaxDiagnostic("json.syntax.missing-comma@1", offset, offset)
            recovered = true
            val next2 = peek()
            if (next2 != null &&
                next2.kind != TokenKind.String && next2.kind != TokenKind.Identifier &&
                next2.kind != TokenKind.RightBrace
            ) {
                position += 1
            }
        }
        val end = source.length
        syntaxDiagnostic("json.syntax.missing-object-close@1", end, end)
        recovered = true
        return allocValue(open.start, end, null, false, InternalValueKind.Object(members))
    }

    private fun parseArray(depth: Int): Int {
        val open = consume(TokenKind.LeftBracket)!!
        val elements = ArrayList<Int>()
        while (true) {
            val close = consume(TokenKind.RightBracket)
            if (close != null) {
                return allocValue(open.start, close.end, null, true, InternalValueKind.Array(elements))
            }
            if (peek() == null) {
                val end = source.length
                syntaxDiagnostic("json.syntax.missing-array-close@1", end, end)
                recovered = true
                return allocValue(open.start, end, null, false, InternalValueKind.Array(elements))
            }
            val ordinal = elements.size
            val value = parseValue(depth + 1)
            val span = entities[value].span
            val element = allocEntity(Entity.Element(ElementEntity(span, value, ordinal)))
            elements.add(element)
            if (consume(TokenKind.Comma) != null) {
                val next = peek()
                if (next != null && next.kind == TokenKind.RightBracket && !profile.permitsJsoncExtensions()) {
                    sink.push(
                        sourceDiagnostic(
                            authority,
                            "json.strict.trailing-comma@1",
                            DiagnosticCategory.Conformance,
                            Severity.Error,
                            (next.start - 1).coerceAtLeast(0),
                            next.start,
                            sink.nextOccurrence(),
                        ),
                    )
                    recovered = true
                }
                continue
            }
            val next = peek()
            if (next != null && next.kind == TokenKind.RightBracket) {
                continue
            }
            val offset = currentOffset()
            syntaxDiagnostic("json.syntax.missing-comma@1", offset, offset)
            recovered = true
        }
    }

    private fun parseObjectKey(depth: Int): Int {
        val token = peek()!!
        if (token.kind == TokenKind.String) {
            return parseValue(depth)
        }
        position += 1
        val name = decodeJson5Identifier(tokenText(token))
        return allocScalar(token, InternalValueKind.String(name))
    }

    private fun allocScalar(token: Token, kind: InternalValueKind): Int =
        allocValue(token.start, token.end, LiteralRange(token.start, token.end), true, kind)

    private fun allocValue(
        start: Int,
        end: Int,
        literal: LiteralRange?,
        complete: Boolean,
        kind: InternalValueKind,
    ): Int =
        allocEntity(
            Entity.Value(
                ValueEntity(
                    authority.span(start, end),
                    literal?.let { authority.span(it.start, it.end) },
                    complete,
                    kind,
                ),
            ),
        )

    private fun allocEntity(entity: Entity): Int {
        if (entities.size >= limits.maxNodeCount) {
            throw resourceLimit("node-count", entities.size + 1, limits.maxNodeCount)
        }
        val index = entities.size
        entities.add(entity)
        return index
    }

    private fun peek(): Token? = tokens.getOrNull(position)

    private fun consume(kind: TokenKind): Token? {
        val token = peek() ?: return null
        if (token.kind == kind) {
            position += 1
            return token
        }
        return null
    }

    private fun currentOffset(): Int = peek()?.start ?: source.length

    internal fun syntaxDiagnostic(code: String, start: Int, end: Int) {
        sink.push(
            sourceDiagnostic(
                authority,
                code,
                DiagnosticCategory.Syntax,
                Severity.Error,
                start,
                end,
                sink.nextOccurrence(),
            ),
        )
    }
}

/** Small immutable literal range holder. */
private data class LiteralRange(val start: Int, val end: Int)

// ---------------------------------------------------------------------------
// Scalar decoding
// ---------------------------------------------------------------------------

/**
 * Decodes one string literal (parser.rs). Returns (decoded,
 * has_unescaped_line_separator) or null on an invalid escape or a control
 * character that is not a legal escape target.
 */
internal fun decodeJsonString(literal: String, profile: JsonProfile): Pair<String, Boolean>? {
    val quote = literal.codePointAt(0)
    if (quote != 0x22 && !(profile.isJson5() && quote == 0x27)) {
        return null
    }
    val quoteWidth = Character.charCount(quote)
    val inner = literal.substring(quoteWidth)
    if (!inner.endsWith(Character.toChars(quote).concatToString())) {
        return null
    }
    val content = inner.substring(0, inner.length - quoteWidth)
    val output = StringBuilder()
    var hasUnescapedLineSeparator = false
    var index = 0
    while (index < content.length) {
        val character = content.codePointAt(index)
        index += Character.charCount(character)
        if (character == 0x5c) {
            if (index >= content.length) return null
            val escaped = content.codePointAt(index)
            index += Character.charCount(escaped)
            when (escaped) {
                0x22 -> output.appendCodePoint(0x22)
                0x27 -> {
                    if (!profile.isJson5()) return null
                    output.appendCodePoint(0x27)
                }
                0x5c -> output.appendCodePoint(0x5c)
                0x2f -> output.appendCodePoint(0x2f)
                0x62 -> output.appendCodePoint(0x08)
                0x66 -> output.appendCodePoint(0x0c)
                0x6e -> output.appendCodePoint(0x0a)
                0x72 -> output.appendCodePoint(0x0d)
                0x74 -> output.appendCodePoint(0x09)
                0x76 -> {
                    if (!profile.isJson5()) return null
                    output.appendCodePoint(0x0b)
                }
                0x30 -> {
                    if (!profile.isJson5()) return null
                    if (index < content.length && content.codePointAt(index) in 0x30..0x39) {
                        return null
                    }
                    output.appendCodePoint(0x00)
                }
                0x78 -> {
                    if (!profile.isJson5()) return null
                    val value = readHexPair(content, index) ?: return null
                    index += 2
                    output.appendCodePoint(value)
                }
                0x75 -> {
                    val first = readHexQuad(content, index) ?: return null
                    index += 4
                    val scalar = if (first in 0xd800..0xdbff) {
                        if (index + 1 >= content.length ||
                            content.codePointAt(index) != 0x5c ||
                            content.codePointAt(index + 1) != 0x75
                        ) {
                            return null
                        }
                        index += 2
                        val second = readHexQuad(content, index) ?: return null
                        index += 4
                        if (second !in 0xdc00..0xdfff) {
                            return null
                        }
                        0x1_0000 + ((first - 0xd800) shl 10) + (second - 0xdc00)
                    } else if (first in 0xdc00..0xdfff) {
                        return null
                    } else {
                        first
                    }
                    if (!Character.isValidCodePoint(scalar)) return null
                    output.appendCodePoint(scalar)
                }
                0x0a, 0x2028, 0x2029 -> {
                    if (!profile.isJson5()) return null
                }
                0x0d -> {
                    if (!profile.isJson5()) return null
                    if (index < content.length && content.codePointAt(index) == 0x0a) {
                        index += 1
                    }
                }
                else -> {
                    if (!profile.isJson5() ||
                        (escaped in 0x30..0x39) ||
                        isJson5LineTerminator(escaped)
                    ) {
                        return null
                    }
                    output.appendCodePoint(escaped)
                }
            }
        } else if (character <= 0x1f) {
            return null
        } else {
            if (character == 0x2028 || character == 0x2029) {
                hasUnescapedLineSeparator = true
            }
            output.appendCodePoint(character)
        }
    }
    return output.toString() to hasUnescapedLineSeparator
}

/** Reads two hex digits (parser.rs). */
private fun readHexPair(content: String, index: Int): Int? {
    var value = 0
    for (i in 0 until 2) {
        if (index + i >= content.length) return null
        val digit = hexDigit(content.codePointAt(index + i)) ?: return null
        value = value * 16 + digit
    }
    return value
}

/** Reads four hex digits (parser.rs). */
private fun readHexQuad(content: String, index: Int): Int? {
    var value = 0
    for (i in 0 until 4) {
        if (index + i >= content.length) return null
        val digit = hexDigit(content.codePointAt(index + i)) ?: return null
        value = value * 16 + digit
    }
    return value
}

private fun hexDigit(scalar: Int): Int? =
    when {
        scalar in 0x30..0x39 -> scalar - 0x30
        scalar in 0x41..0x46 -> scalar - 0x41 + 10
        scalar in 0x61..0x66 -> scalar - 0x61 + 10
        else -> null
    }

/** Decodes one validated JSON5 IdentifierName literal (parser.rs). */
internal fun decodeJson5Identifier(literal: String): String {
    val output = StringBuilder()
    var offset = 0
    var first = true
    while (offset < literal.length) {
        val scalar = literal.codePointAt(offset)
        val (decoded, width) = if (scalar == 0x5c) {
            val value = decodeIdentifierEscapeFromString(literal, offset)
                ?: error("lexer validated identifier")
            value to 6
        } else {
            scalar to Character.charCount(scalar)
        }
        val permitted = if (first) {
            isJson5IdentifierStart(decoded)
        } else {
            isJson5IdentifierContinue(decoded)
        }
        if (!permitted) {
            error("lexer validated identifier")
        }
        output.appendCodePoint(decoded)
        offset += width
        first = false
    }
    return output.toString()
}

/** Decodes one `\uXXXX` escape inside a decoded string (parser.rs). */
private fun decodeIdentifierEscapeFromString(literal: String, offset: Int): Int? {
    if (offset + 5 >= literal.length) return null
    if (literal.codePointAt(offset) != 0x5c || literal.codePointAt(offset + 1) != 0x75) return null
    var value = 0
    for (i in 0 until 4) {
        val digit = hexDigit(literal.codePointAt(offset + 2 + i)) ?: return null
        value = value * 16 + digit
    }
    return if (Character.isValidCodePoint(value)) value else null
}

/** Counts the digit characters of one number token: coefficient digits
 * plus exponent digits. A JSON5 hex literal counts its hex digits (a hex
 * literal has no exponent marker, so 'e'/'E' are unambiguous there). */
private fun numberDigitCount(text: String): Int {
    val hex = text.startsWith("0x") || text.startsWith("0X") ||
        text.startsWith("-0x") || text.startsWith("-0X") ||
        text.startsWith("+0x") || text.startsWith("+0X")
    var count = 0
    for (c in text) {
        if (c in '0'..'9' || (hex && (c in 'a'..'f' || c in 'A'..'F'))) {
            count += 1
        }
    }
    return count
}

/** Decodes one JSON5 number literal to its exact native category
 * (parser.rs). */
internal fun parseJson5Number(text: String): InternalValueKind {
    val negative = text.startsWith("-")
    val unsigned = if (negative) {
        text.substring(1)
    } else {
        if (text.startsWith("+")) text.substring(1) else text
    }
    when (unsigned) {
        "Infinity" -> {
            val bits = if (negative) {
                java.lang.Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY)
            } else {
                java.lang.Double.doubleToRawLongBits(Double.POSITIVE_INFINITY)
            }
            return InternalValueKind.BinaryFloat64(bits)
        }
        "NaN" -> {
            val bits = if (negative) {
                java.lang.Double.doubleToRawLongBits(-Double.NaN)
            } else {
                java.lang.Double.doubleToRawLongBits(Double.NaN)
            }
            return InternalValueKind.BinaryFloat64(bits)
        }
    }
    if (unsigned.startsWith("0x") || unsigned.startsWith("0X")) {
        val hex = unsigned.substring(2)
        var magnitude = BigInteger.ZERO
        val radix = BigInteger.valueOf(16)
        for (digit in hex) {
            magnitude = magnitude.multiply(radix).add(BigInteger.valueOf(digit.digitToInt(16).toLong()))
        }
        return InternalValueKind.Integer(if (negative) magnitude.negate() else magnitude)
    }
    var normalized = if (negative) "-$unsigned" else unsigned
    val signWidth = if (negative) 1 else 0
    if (normalized.length > signWidth && normalized[signWidth] == '.') {
        normalized = normalized.substring(0, signWidth) + "0" + normalized.substring(signWidth)
    }
    val exponentIndex = normalized.indexOfFirst { it == 'e' || it == 'E' }
    val mantissaEnd = if (exponentIndex >= 0) exponentIndex else normalized.length
    if (mantissaEnd > 0 && normalized[mantissaEnd - 1] == '.') {
        normalized = normalized.substring(0, mantissaEnd) + "0" + normalized.substring(mantissaEnd)
    }
    return if (normalized.indexOfFirst { it == '.' || it == 'e' || it == 'E' } >= 0) {
        InternalValueKind.Decimal(parseJsonDecimal(normalized))
    } else {
        InternalValueKind.Integer(BigInteger(normalized))
    }
}

/** Decodes one canonical strict JSON number to an exact Decimal
 * (value.rs: coefficient = sign+whole+fraction, exponent =
 * explicit - fraction.len(), normalized by PvDecimal.of). */
internal fun parseJsonDecimal(text: String): PvDecimal {
    val exponentIndex = text.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (exponentIndex >= 0) text.substring(0, exponentIndex) else text
    val explicit = if (exponentIndex >= 0) text.substring(exponentIndex + 1) else "0"
    val exponent = BigInteger(explicit)
    val negative = mantissa.startsWith("-")
    val unsigned = if (negative) mantissa.substring(1) else mantissa
    val dot = unsigned.indexOf('.')
    val whole = if (dot >= 0) unsigned.substring(0, dot) else unsigned
    val fraction = if (dot >= 0) unsigned.substring(dot + 1) else ""
    val digits = (if (negative) "-" else "") + whole + fraction
    val coefficient = BigInteger(digits)
    return PvDecimal.of(coefficient, exponent.subtract(BigInteger.valueOf(fraction.length.toLong())))
}

// ---------------------------------------------------------------------------
// Diagnostic sink
// ---------------------------------------------------------------------------

/** Ordered diagnostic collection with explicit truncation (parser.rs). */
internal class DiagnosticSink(private val max: Int) {
    private val diagnostics = ArrayList<Diagnostic>()
    private var occurrenceCounter = 0uL
    private var truncated = false

    /** The occurrence ordinal the next push will assign. */
    fun nextOccurrence(): ULong = occurrenceCounter

    fun push(diagnostic: Diagnostic) {
        occurrenceCounter = occurrenceCounter.inc()
        if (diagnostics.size < max) {
            diagnostics.add(diagnostic)
        } else if (!truncated) {
            truncated = true
            diagnostics.add(
                Diagnostic.of(
                    "core.diagnostic.truncated@1",
                    DiagnosticCategory.Resource,
                    Severity.Warning,
                    null,
                    emptyList(),
                    emptyMap(),
                    emptyList(),
                    emptyList(),
                    occurrenceCounter,
                    JSON_DIAGNOSTIC_REGISTRY,
                ),
            )
        }
    }

    fun finish(): List<Diagnostic> = diagnostics
}
