// The lossless TOML tokenizer: exhaustive token/trivia byte coverage.
//
// Data authority:
//   - crates/consema-toml/src/parser.rs:360-431 (tokenize): whitespace
//     (space/tab), newline (LF, CRLF, or bare CR), `#` comment to EOL,
//     single String piece per `'`/`"` token (the string_end approximation,
//     parser.rs:480-499), one piece per punctuation byte, and Bare runs
//     otherwise; every piece is checked against max_token_count BEFORE it is
//     pushed (parser.rs:413-420).
//   - crates/consema-toml/src/parser.rs:433-461 (preflight_delimiter_nesting):
//     `[`/`{` increment and `]`/`}` decrement a depth counter over Token
//     pieces only; exceeding max_nesting_depth fails with
//     resource_limit("nesting_depth", depth, max) BEFORE any syntax parse.
//   - conformance/vectors/syntax-query-v1.json syntax.toml.* cases pin the
//     piece ordinal arithmetic ("a = 1 # note\nb = 2\n": Comment ordinal 6,
//     Newline ordinals 7 and 13) and the kind spellings.
//   - go/toml is a cross-reference only.
//
// Kotlin-idiomatic design: the lexer is a pure function over the decoded
// text returning paired piece spans and kinds; validation of the lossless
// coverage invariant (no gap/no overlap/final length) is delegated to the
// document-domain [LosslessStructuralIndex.new].

package consema.toml

import consema.document.DocumentAuthority
import consema.document.LosslessStructuralIndex
import consema.document.ParseLimits
import consema.document.StructuralPiece
import consema.document.StructuralPieceKind

/** The lexer result: exhaustive ordered piece spans and their syntax kinds,
 * both in raw source order. */
internal class LexedSource(
    val pieces: List<StructuralPiece>,
    val kinds: List<TomlSyntaxKind>,
    val structuralIndex: LosslessStructuralIndex,
)

/**
 * Tokenizes the complete decoded text into exhaustive token/trivia pieces
 * (parser.rs:360-431) and runs the delimiter nesting preflight
 * (parser.rs:433-461). Any limit failure throws [TomlFormationException].
 */
internal fun tokenize(
    text: String,
    authority: DocumentAuthority,
    sourceLen: Int,
    limits: ParseLimits,
): LexedSource {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val pieces = ArrayList<StructuralPiece>()
    val kinds = ArrayList<TomlSyntaxKind>()
    var cursor = 0
    while (cursor < bytes.size) {
        val (end, pieceKind, syntaxKind) = when {
            bytes[cursor] == ' '.code.toByte() || bytes[cursor] == '\t'.code.toByte() -> {
                var end = cursor + 1
                while (end < bytes.size &&
                    (bytes[end] == ' '.code.toByte() || bytes[end] == '\t'.code.toByte())
                ) {
                    end += 1
                }
                Triple(end, StructuralPieceKind.Trivia, TomlSyntaxKind.Whitespace)
            }
            bytes[cursor] == '\r'.code.toByte() || bytes[cursor] == '\n'.code.toByte() -> {
                val end = if (bytes[cursor] == '\r'.code.toByte() &&
                    cursor + 1 < bytes.size && bytes[cursor + 1] == '\n'.code.toByte()
                ) {
                    cursor + 2
                } else {
                    cursor + 1
                }
                Triple(end, StructuralPieceKind.Trivia, TomlSyntaxKind.Newline)
            }
            bytes[cursor] == '#'.code.toByte() -> {
                var end = cursor + 1
                while (end < bytes.size &&
                    bytes[end] != '\r'.code.toByte() && bytes[end] != '\n'.code.toByte()
                ) {
                    end += 1
                }
                Triple(end, StructuralPieceKind.Trivia, TomlSyntaxKind.Comment)
            }
            bytes[cursor] == '\''.code.toByte() || bytes[cursor] == '"'.code.toByte() -> {
                Triple(
                    stringEnd(bytes, cursor),
                    StructuralPieceKind.Token,
                    TomlSyntaxKind.String,
                )
            }
            isPunctuation(bytes[cursor]) -> {
                Triple(
                    cursor + 1,
                    StructuralPieceKind.Token,
                    punctuationKind(bytes[cursor]),
                )
            }
            else -> {
                var end = cursor + 1
                while (end < bytes.size &&
                    !isAsciiWhitespace(bytes[end]) &&
                    bytes[end] != '#'.code.toByte() &&
                    !isPunctuation(bytes[end]) &&
                    bytes[end] != '\''.code.toByte() && bytes[end] != '"'.code.toByte()
                ) {
                    end += 1
                }
                Triple(end, StructuralPieceKind.Token, TomlSyntaxKind.Bare)
            }
        }
        val observed = pieces.size + 1
        if (observed > limits.maxTokenCount) {
            throw TomlFormationException(
                listOf(resourceLimitDiagnostic("token_count", observed, limits.maxTokenCount)),
            )
        }
        pieces.add(
            StructuralPiece(
                authority.span(cursor, end),
                pieceKind,
            ),
        )
        kinds.add(syntaxKind)
        cursor = end
    }
    preflightDelimiterNesting(bytes, pieces, limits)
    val structuralIndex = LosslessStructuralIndex.new(authority.identity, sourceLen, pieces)
    return LexedSource(pieces, kinds, structuralIndex)
}

/** Counts `[`/`{` vs `]`/`}` over Token pieces only (parser.rs:433-461).
 * Delimiter tokens are single ASCII bytes, so byte comparison is exact. */
private fun preflightDelimiterNesting(
    bytes: ByteArray,
    pieces: List<StructuralPiece>,
    limits: ParseLimits,
) {
    var depth = 0
    for (piece in pieces) {
        if (piece.kind != StructuralPieceKind.Token) {
            continue
        }
        // Only the delimiter punctuation forms single-byte Token pieces; a
        // String piece starts with a quote and a Bare piece never starts
        // with punctuation, so the first byte discriminates exactly.
        val byte = bytes[piece.span.startByte]
        when (byte) {
            '['.code.toByte(), '{'.code.toByte() -> {
                depth += 1
                if (depth > limits.maxNestingDepth) {
                    throw TomlFormationException(
                        listOf(
                            resourceLimitDiagnostic("nesting_depth", depth, limits.maxNestingDepth),
                        ),
                    )
                }
            }
            ']'.code.toByte(), '}'.code.toByte() -> depth -= 1
        }
    }
}

/** The Rust `u8::is_ascii_whitespace` set: space, tab, LF, FF, CR
 * (parser.rs:404). */
private fun isAsciiWhitespace(byte: Byte): Boolean =
    byte == ' '.code.toByte() || byte == '\t'.code.toByte() || byte == '\n'.code.toByte() ||
        byte == 0x0C || byte == '\r'.code.toByte()

private fun isPunctuation(byte: Byte): Boolean =
    byte == '='.code.toByte() || byte == '['.code.toByte() || byte == ']'.code.toByte() ||
        byte == '{'.code.toByte() || byte == '}'.code.toByte() ||
        byte == ','.code.toByte() || byte == '.'.code.toByte()

private fun punctuationKind(byte: Byte): TomlSyntaxKind =
    when (byte) {
        '='.code.toByte() -> TomlSyntaxKind.Equals
        '['.code.toByte() -> TomlSyntaxKind.LeftBracket
        ']'.code.toByte() -> TomlSyntaxKind.RightBracket
        '{'.code.toByte() -> TomlSyntaxKind.LeftBrace
        '}'.code.toByte() -> TomlSyntaxKind.RightBrace
        ','.code.toByte() -> TomlSyntaxKind.Comma
        '.'.code.toByte() -> TomlSyntaxKind.Dot
        else -> error("caller filtered the byte before syntax-kind dispatch")
    }

/** Scans one `'`/`"` string token to its closing quote, treating `\"` as
 * escaped for double-quoted strings (parser.rs:480-499). This is a lossless
 * piece boundary approximation; the real string grammar is validated by the
 * parser. */
private fun stringEnd(bytes: ByteArray, start: Int): Int {
    val quote = bytes[start]
    val triple = start + 3 <= bytes.size &&
        bytes[start] == quote && bytes[start + 1] == quote && bytes[start + 2] == quote
    var cursor = start + if (triple) 3 else 1
    while (cursor < bytes.size) {
        if (quote == '"'.code.toByte() && bytes[cursor] == '\\'.code.toByte()) {
            cursor = (cursor + 2).coerceAtMost(bytes.size)
            continue
        }
        if (triple) {
            if (cursor + 3 <= bytes.size &&
                bytes[cursor] == quote && bytes[cursor + 1] == quote && bytes[cursor + 2] == quote
            ) {
                return cursor + 3
            }
        } else if (bytes[cursor] == quote) {
            return cursor + 1
        }
        cursor += 1
    }
    return bytes.size
}
