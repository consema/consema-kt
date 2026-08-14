// The lossless YAML presentation scanner: every decoded scalar boundary is
// classified as one closed syntax kind, and anchor/alias names are captured
// with exact spans.
//
// Data authority:
//   - RFC 0007 §7 (https://github.com/consema/consema/blob/main/docs/rfcs/0007-yaml-family-profiles-and-safety-v1.md
//): the lossless Document retains comments, whitespace, line
//     breaks, directives, markers, styles, and exhaustive non-overlapping
//     raw-byte coverage; the syntax kinds are stable style subfacts.
//   - conformance/vectors/yaml-v1.json cases syntax.styles-and-trivia
//     (piece_count 48 with the exact required kinds, lines 31-34) and
//     query.syntax-comments (Comment ordinals [5, 12], lines 61-64) pin the
//     exact piece segmentation byte-for-byte.
//   - https://github.com/consema/consema-rs/blob/main/consema-yaml/src/syntax.rs is the byte-arbitration
//     authority for the lexeme rules (plain-line continuation, node-property
//     characters inside plain scalars, block-scalar content regions
//     including their trailing line break, quote scanning, and the
//     indicator set); the regression vector regression.plain-property-
//     characters (yaml-v1.json:136-139) pins the multiline plain behavior.
//
// Kotlin-idiomatic design: a single pass over decoded code points producing
// an immutable [Tokenized] result; the scanner never interprets grammar, it
// only classifies bytes (the parser owns grammar).

package consema.yaml

import consema.document.DocumentAuthority
import consema.document.LosslessStructuralIndex
import consema.document.SourceSnapshot
import consema.document.StructuralPiece
import consema.document.StructuralPieceKind

/** One classified lexeme over decoded scalar offsets (syntax.rs). */
internal class Lexeme(
    val start: Int,
    val end: Int,
    val kind: YamlSyntaxKind,
)

/** One named anchor or alias occurrence with its exact raw span
 * (syntax.rs). */
internal class NamedOccurrence(
    val name: String,
    val span: consema.document.Span,
)

/** The scanned lossless facts (syntax.rs). */
internal class Tokenized(
    val index: LosslessStructuralIndex,
    val kinds: List<YamlSyntaxKind>,
    val anchors: List<NamedOccurrence>,
    val aliases: List<NamedOccurrence>,
)

/**
 * Scans the complete decoded source into lossless lexemes, resolving every
 * boundary to exact raw bytes through one forward walk (syntax.rs;
 * offsets.rs). Throws [YamlFormationException] on the token limit.
 */
internal fun tokenize(
    source: SourceSnapshot,
    authority: DocumentAuthority,
    maxTokens: Int,
): Tokenized {
    val text = source.decodedText()
        ?: throw YamlFormationException("yaml.parse.syntax@1", "yaml: no decoded text")
    val chars = text.codePoints().toArray()
    val scanner = Scanner(chars, maxTokens)
    val lexemes = scanner.scan()
    val pieces = ArrayList<StructuralPiece>(lexemes.size)
    val kinds = ArrayList<YamlSyntaxKind>(lexemes.size)
    val anchors = ArrayList<NamedOccurrence>()
    val aliases = ArrayList<NamedOccurrence>()
    val raw = RawByteResolver(source)
    for (lexeme in lexemes) {
        val start = raw.resolve(lexeme.start)
        val end = raw.resolve(lexeme.end)
        val span = authority.span(start, end)
        pieces.add(
            StructuralPiece(
                span,
                if (lexeme.kind.isTrivia()) {
                    StructuralPieceKind.Trivia
                } else if (lexeme.kind == YamlSyntaxKind.ErrorRegion) {
                    StructuralPieceKind.ErrorRegion
                } else {
                    StructuralPieceKind.Token
                },
            ),
        )
        kinds.add(lexeme.kind)
        if (lexeme.kind == YamlSyntaxKind.Anchor || lexeme.kind == YamlSyntaxKind.Alias) {
            val name = chars.copyOfRange(lexeme.start + 1, lexeme.end)
                .map { it.toChar() }
                .joinToString("")
            if (lexeme.kind == YamlSyntaxKind.Anchor) {
                anchors.add(NamedOccurrence(name, span))
            } else {
                aliases.add(NamedOccurrence(name, span))
            }
        }
    }
    val index = LosslessStructuralIndex.new(authority.identity, source.len, pieces)
    return Tokenized(index, kinds, anchors, aliases)
}

/** One decoded code point with its scalar offset, or -1 past the end. */
private class Cursor(val chars: IntArray) {
    var offset = 0
    var lineStart = 0

    fun current(): Int =
        if (offset < chars.size) chars[offset] else -1

    fun at(relative: Int): Int =
        if (offset + relative < chars.size) chars[offset + relative] else -1
}

/** The single-pass presentation scanner (syntax.rs). */
internal class Scanner(private val chars: IntArray, private val maxTokens: Int) {
    private val cursor = Cursor(chars)
    private val output = ArrayList<Lexeme>()
    private var pendingBlockParentIndent: Int? = null
    private var plainLineActive = false
    private var plainParentIndent: Int? = null

    fun scan(): List<Lexeme> {
        while (cursor.offset < chars.size) {
            if (cursor.offset == cursor.lineStart &&
                pendingBlockParentIndent != null &&
                scanBlockContent()
            ) {
                continue
            }
            val start = cursor.offset
            val current = cursor.current()
            if (!isSeparation(current) &&
                !plainLineActive &&
                plainParentIndent != null
            ) {
                if (lineIndent() > plainParentIndent!! && !startsIndentedStructure()) {
                    takeUntilBreak()
                    push(start, cursor.offset, YamlSyntaxKind.PlainScalar)
                    plainLineActive = true
                    continue
                }
                plainParentIndent = null
            }
            when {
                current == 0xFEFF -> {
                    cursor.offset += 1
                    push(start, cursor.offset, YamlSyntaxKind.Bom)
                    endPlainScalar()
                    if (start == cursor.lineStart) {
                        cursor.lineStart = cursor.offset
                    }
                }
                current == ' '.code || current == '\t'.code -> {
                    takeWhile { it == ' '.code || it == '\t'.code }
                    push(start, cursor.offset, YamlSyntaxKind.Whitespace)
                }
                current == '\r'.code || current == '\n'.code -> scanNewline(start)
                current == '#'.code -> {
                    takeUntilBreak()
                    push(start, cursor.offset, YamlSyntaxKind.Comment)
                    endPlainScalar()
                }
                atDirective() -> {
                    takeUntilBreak()
                    push(start, cursor.offset, YamlSyntaxKind.Directive)
                    endPlainScalar()
                }
                atDocumentIndicator('-'.code, '-'.code, '-'.code) -> {
                    cursor.offset += 3
                    push(start, cursor.offset, YamlSyntaxKind.DocumentStart)
                    endPlainScalar()
                }
                atDocumentIndicator('.'.code, '.'.code, '.'.code) -> {
                    cursor.offset += 3
                    push(start, cursor.offset, YamlSyntaxKind.DocumentEnd)
                    endPlainScalar()
                }
                current == '\''.code || current == '"'.code -> {
                    scanQuoted(current)
                    push(
                        start,
                        cursor.offset,
                        if (current == '\''.code) {
                            YamlSyntaxKind.SingleQuotedScalar
                        } else {
                            YamlSyntaxKind.DoubleQuotedScalar
                        },
                    )
                    endPlainScalar()
                }
                (current == '|'.code || current == '>'.code) && isBlockHeader() -> {
                    val parentIndent = lineIndent()
                    takeUntilBreak()
                    push(
                        start,
                        cursor.offset,
                        if (current == '|'.code) {
                            YamlSyntaxKind.LiteralBlockHeader
                        } else {
                            YamlSyntaxKind.FoldedBlockHeader
                        },
                    )
                    pendingBlockParentIndent = parentIndent
                    endPlainScalar()
                }
                (current == '&'.code || current == '*'.code || current == '!'.code) &&
                    !plainLineActive -> {
                    cursor.offset += 1
                    takeWhile { !isSeparation(it) && !isFlowIndicator(it) }
                    push(
                        start,
                        cursor.offset,
                        when (current) {
                            '&'.code -> YamlSyntaxKind.Anchor
                            '*'.code -> YamlSyntaxKind.Alias
                            else -> YamlSyntaxKind.Tag
                        },
                    )
                    endPlainScalar()
                }
                indicatorKind() != null -> {
                    val kind = indicatorKind()!!
                    cursor.offset += 1
                    push(start, cursor.offset, kind)
                    endPlainScalar()
                }
                else -> {
                    scanPlain()
                    push(start, cursor.offset, YamlSyntaxKind.PlainScalar)
                    if (!plainLineActive) {
                        plainParentIndent = lineIndent()
                    }
                    plainLineActive = true
                }
            }
        }
        return output
    }

    private fun push(start: Int, end: Int, kind: YamlSyntaxKind) {
        val observed = output.size + 1
        if (observed > maxTokens) {
            throw resourceLimit("syntax-pieces", observed, maxTokens)
        }
        require(end > start) { "scanner lexemes are non-empty" }
        output.add(Lexeme(start, end, kind))
    }

    private fun scanNewline(start: Int) {
        if (cursor.current() == '\r'.code && cursor.at(1) == '\n'.code) {
            cursor.offset += 2
        } else {
            cursor.offset += 1
        }
        push(start, cursor.offset, YamlSyntaxKind.Newline)
        cursor.lineStart = cursor.offset
        plainLineActive = false
    }

    private fun endPlainScalar() {
        plainLineActive = false
        plainParentIndent = null
    }

    /** Whether a more-indented continuation line starts an indented
     * structure (sequence entry, explicit key, or mapping key) that must
     * not be folded into the plain scalar (syntax.rs). */
    private fun startsIndentedStructure(): Boolean {
        val current = chars.getOrNull(cursor.offset) ?: return false
        if ((current == '-'.code || current == '?'.code) &&
            isSeparation(chars.getOrNull(cursor.offset + 1) ?: -1)
        ) {
            return true
        }
        var probe = cursor.offset
        while (probe < chars.size) {
            val character = chars[probe]
            if (character == '\r'.code || character == '\n'.code || character == '#'.code) {
                return false
            }
            if (character == ':'.code &&
                isSeparation(chars.getOrNull(probe + 1) ?: -1)
            ) {
                return true
            }
            probe++
        }
        return false
    }

    private fun scanQuoted(quote: Int) {
        cursor.offset += 1
        while (cursor.offset < chars.size) {
            val current = chars[cursor.offset]
            cursor.offset += 1
            if (quote == '"'.code && current == '\\'.code && cursor.offset < chars.size) {
                when {
                    chars[cursor.offset] == '\r'.code -> {
                        cursor.offset += 1
                        if (chars.getOrNull(cursor.offset) == '\n'.code) {
                            cursor.offset += 1
                        }
                        cursor.lineStart = cursor.offset
                    }
                    chars[cursor.offset] == '\n'.code -> {
                        cursor.offset += 1
                        cursor.lineStart = cursor.offset
                    }
                    else -> cursor.offset += 1
                }
            } else if (current == quote) {
                if (quote == '\''.code && chars.getOrNull(cursor.offset) == '\''.code) {
                    cursor.offset += 1
                } else {
                    break
                }
            } else if (current == '\n'.code) {
                cursor.lineStart = cursor.offset
            } else if (current == '\r'.code) {
                if (chars.getOrNull(cursor.offset) == '\n'.code) {
                    cursor.offset += 1
                }
                cursor.lineStart = cursor.offset
            }
        }
    }

    private fun scanPlain() {
        cursor.offset += 1
        while (cursor.offset < chars.size) {
            val current = chars[cursor.offset]
            if (isSeparation(current) || isFlowIndicator(current)) {
                break
            }
            if (current == ':'.code) {
                val next = chars.getOrNull(cursor.offset + 1) ?: -1
                if (isSeparation(next) || isFlowIndicator(next)) {
                    break
                }
            }
            cursor.offset += 1
        }
    }

    /** Consumes one indented block-scalar content region (including its
     * trailing line break) at a line start (syntax.rs). */
    private fun scanBlockContent(): Boolean {
        val parentIndent = pendingBlockParentIndent!!
        val start = cursor.offset
        var probe = start
        var acceptedEnd = start
        while (probe < chars.size) {
            val lineEnd = nextLineEnd(chars, probe)
            val contentEnd = lineContentEnd(chars, probe, lineEnd)
            var indent = 0
            while (probe + indent < contentEnd && chars[probe + indent] == ' '.code) {
                indent++
            }
            val blank = (probe + indent..<contentEnd).all { index ->
                chars[index] == ' '.code || chars[index] == '\t'.code
            }
            if (!blank && indent <= parentIndent) {
                break
            }
            acceptedEnd = lineEnd
            probe = lineEnd
        }
        pendingBlockParentIndent = null
        if (acceptedEnd == start) {
            return false
        }
        cursor.offset = acceptedEnd
        cursor.lineStart = acceptedEnd
        push(start, acceptedEnd, YamlSyntaxKind.BlockScalarContent)
        return true
    }

    private fun indicatorKind(): YamlSyntaxKind? {
        val current = chars.getOrNull(cursor.offset) ?: return null
        return when (current) {
            '['.code -> YamlSyntaxKind.FlowSequenceStart
            ']'.code -> YamlSyntaxKind.FlowSequenceEnd
            '{'.code -> YamlSyntaxKind.FlowMappingStart
            '}'.code -> YamlSyntaxKind.FlowMappingEnd
            ','.code -> YamlSyntaxKind.FlowEntry
            '-'.code -> if (followedBySeparation(1)) {
                YamlSyntaxKind.SequenceEntry
            } else {
                null
            }
            '?'.code -> if (followedBySeparation(1)) {
                YamlSyntaxKind.ExplicitKey
            } else {
                null
            }
            ':'.code -> if (followedBySeparation(1)) {
                YamlSyntaxKind.MappingValue
            } else {
                null
            }
            else -> null
        }
    }

    private fun atDirective(): Boolean =
        cursor.offset == cursor.lineStart && cursor.current() == '%'.code

    private fun atDocumentIndicator(a: Int, b: Int, c: Int): Boolean =
        cursor.offset == cursor.lineStart &&
            chars.getOrNull(cursor.offset) == a &&
            chars.getOrNull(cursor.offset + 1) == b &&
            chars.getOrNull(cursor.offset + 2) == c &&
            followedBySeparation(3)

    private fun followedBySeparation(length: Int): Boolean =
        isSeparation(chars.getOrNull(cursor.offset + length) ?: -1)

    private fun isBlockHeader(): Boolean {
        var probe = cursor.offset + 1
        while (probe < chars.size && chars[probe] != '\r'.code && chars[probe] != '\n'.code) {
            val character = chars[probe]
            if (character != '+'.code && character != '-'.code &&
                (character < '0'.code || character > '9'.code) &&
                character != ' '.code && character != '\t'.code && character != '#'.code
            ) {
                return false
            }
            probe++
        }
        return true
    }

    private fun lineIndent(): Int {
        var indent = 0
        while (cursor.lineStart + indent < cursor.offset &&
            chars[cursor.lineStart + indent] == ' '.code
        ) {
            indent++
        }
        return indent
    }

    private fun takeUntilBreak() {
        takeWhile { it != '\r'.code && it != '\n'.code }
    }

    private fun takeWhile(predicate: (Int) -> Boolean) {
        while (cursor.offset < chars.size && predicate(chars[cursor.offset])) {
            cursor.offset += 1
        }
    }
}

private fun isSeparation(value: Int): Boolean =
    value == ' '.code || value == '\t'.code || value == '\r'.code || value == '\n'.code

private fun isFlowIndicator(value: Int): Boolean =
    value == '['.code || value == ']'.code || value == '{'.code ||
        value == '}'.code || value == ','.code

private fun nextLineEnd(chars: IntArray, start: Int): Int {
    var probe = start
    while (probe < chars.size && chars[probe] != '\r'.code && chars[probe] != '\n'.code) {
        probe++
    }
    if (probe < chars.size && chars[probe] == '\r'.code) {
        probe++
        if (probe < chars.size && chars[probe] == '\n'.code) {
            probe++
        }
    } else if (probe < chars.size && chars[probe] == '\n'.code) {
        probe++
    }
    return probe
}

private fun lineContentEnd(chars: IntArray, start: Int, lineEnd: Int): Int {
    var end = lineEnd
    if (end > start && chars[end - 1] == '\n'.code) {
        end--
    }
    if (end > start && chars[end - 1] == '\r'.code) {
        end--
    }
    return end
}
