// The complete TOML 1.0 parser and entity builder.
//
// Data authority:
//   - RFC 0001 §2-§3 (docs/rfcs/0001-toml-1.0-profile.md): the closed native
//     item set, dotted-key layered entries, deterministic order and exact
//     spans, fatal syntax failures carrying `toml.parse.syntax@1` with a
//     provable minimal span, and the four parse limits applied in the frozen
//     order (source bytes, UTF-8, syntax, token/node/depth limits).
//   - TOML 1.0.0 grammar; the semantic state machine (dotted keys, standard/
//     implicit/dotted tables, arrays of tables, inline-table extension
//     prohibition, implicit-table reuse with remove-and-reinsert) follows
//     toml_edit 0.22.27 (the frozen Rust backend, IMPLEMENTATION.md:104) as
//     transcribed by the Go parser (go/toml/parser.go:280-2030, a
//     cross-reference only): parseDocument BOM skip, parseComment/lineTrailing
//     trivia rules, parseKey dotted keys, parseKeyval with the dotted-vs-table
//     conflict checks, descendPath, parseHeader with the implicit-reuse rule,
//     finalize with AOT append, parseValue dispatch order, the four string
//     forms with mlb-escaped-nl, tryDateTime/tryFloatToken/trySpecialFloat/
//     parseIntToken number grammar, parseArray, parseInlineTableValue.
//   - crates/consema-toml/src/parser.rs:84-337 (EntityBuilder): the exact
//     entity order (item first, then per entry key/item/entry), the span
//     fallback rules, and the node-count/nesting limits.
//
// Kotlin-idiomatic design: the parser consumes the UTF-8 bytes directly
// (TOML offsets are raw byte offsets, RFC 0003 §5; Go byte strings behave
// identically). It produces a mutable parse tree, then an immutable entity
// list exactly in the Rust builder order; every span stays in [0, sourceLen].

package consema.toml

import consema.document.DocumentAuthority
import consema.document.NodeRole
import consema.document.ParseLimits
import consema.document.Span

/** One raw byte range. */
internal data class SpanRange(val start: Int, val end: Int)

/** The parse-tree table with toml_edit-equivalent semantics: ordered items,
 * dotted/implicit flavors, inline flag, and remove-and-reinsert behavior of
 * implicit-table reuse. */
internal class PTable(
    var name: String,
    var keySpan: SpanRange,
    var span: SpanRange,
    var hasSpan: Boolean,
    val items: MutableList<PItem> = ArrayList(),
    val byName: MutableMap<String, Int> = HashMap(),
    var isInline: Boolean = false,
    var implicit: Boolean = false,
    var dotted: Boolean = false,
)

internal enum class PItemKind { KEYVAL, SUBTABLE, AOT }

internal class PItem(
    var name: String,
    var keySpan: SpanRange,
    var kind: PItemKind,
    var value: PValue? = null,
    var table: PTable? = null,
    var aot: PAot? = null,
)

internal class PAot(
    var name: String,
    var keySpan: SpanRange,
    var span: SpanRange,
    var hasSpan: Boolean,
    val elements: MutableList<PTable> = ArrayList(),
)

/** One parsed value with its exact raw span. */
internal class PValue(
    var span: SpanRange,
    var kind: PValueKind,
    var str: String = "",
    var integer: Long = 0,
    var bits: Long = 0,
    var boolean: Boolean = false,
    var dateTime: TomlDateTime = TomlDateTime(null, null, null),
    val array: MutableList<PValue> = ArrayList(),
    var table: PTable? = null,
)

internal enum class PValueKind { STRING, INTEGER, FLOAT, BOOLEAN, DATETIME, ARRAY, INLINE_TABLE }

/** The byte-level TOML 1.0 scanner (the Go parser transcription is the
 * cross-reference; the toml_edit parser sources are the behavior
 * authority). */
internal class Parser(private val src: ByteArray) {
    var pos: Int = 0
    private var root: PTable
    private var current: PTable
    private var currentPath: MutableList<String> = ArrayList()

    init {
        root = PTable("", SpanRange(0, 0), SpanRange(0, 0), false)
        current = root
        // An optional BOM is skipped at the document start
        // (toml_edit parser/document.rs `opt(b"\xEF\xBB\xBF")`).
        if (src.size >= 3 && src[0] == 0xEF.toByte() && src[1] == 0xBB.toByte() &&
            src[2] == 0xBF.toByte()
        ) {
            pos = 3
        }
    }

    fun parseDocument(): PTable {
        while (true) {
            skipWS()
            if (pos >= src.size) {
                break
            }
            when (src[pos]) {
                '#'.code.toByte() -> parseComment()
                '\n'.code.toByte() -> pos += 1
                '\r'.code.toByte() -> {
                    if (pos + 1 >= src.size || src[pos + 1] != '\n'.code.toByte()) {
                        throw ParseError(pos, pos + 1, "expected `\\n` after `\\r`")
                    }
                    pos += 2
                }
                '['.code.toByte() -> parseHeader()
                else -> parseKeyval()
            }
        }
        finalize()
        return root
    }

    private fun skipWS() {
        while (pos < src.size && (src[pos] == ' '.code.toByte() || src[pos] == '\t'.code.toByte())) {
            pos += 1
        }
    }

    private fun errorAt(start: Int, end: Int, reason: String): ParseError =
        ParseError(start, end, reason)

    /** Consumes `#` plus non-EOL content. */
    private fun commentContent() {
        pos += 1 // '#'
        while (pos < src.size) {
            val c = src[pos]
            if (c == '\n'.code.toByte() || c == '\r'.code.toByte()) {
                return
            }
            if (c == '\t'.code.toByte() || (c >= 0x20 && c <= 0x7E) || (c.toInt() and 0xff) >= 0x80) {
                pos += 1
                continue
            }
            throw errorAt(pos, pos + 1, "control character in comment")
        }
    }

    /** Consumes a comment and requires a line ending (toml_edit
     * parser/document.rs parse_comment). */
    private fun parseComment() {
        commentContent()
        lineEnding()
    }

    /** Requires a newline (LF or CRLF) or EOF. */
    private fun lineEnding() {
        if (pos >= src.size) {
            return
        }
        when (src[pos]) {
            '\n'.code.toByte() -> {
                pos += 1
                return
            }
            '\r'.code.toByte() -> {
                if (pos + 1 < src.size && src[pos + 1] == '\n'.code.toByte()) {
                    pos += 2
                    return
                }
            }
        }
        throw errorAt(pos, pos + 1, "expected newline")
    }

    /** Consumes ws and an optional comment, then requires a line ending
     * (toml_edit parser/trivia.rs line-trailing). */
    private fun lineTrailing() {
        skipWS()
        if (pos < src.size && src[pos] == '#'.code.toByte()) {
            commentContent()
        }
        lineEnding()
    }

    /** Consumes ws, comments, and newlines (toml_edit parser/trivia.rs
     * ws-comment-newline), used inside arrays. */
    private fun wsCommentNewline() {
        while (true) {
            skipWS()
            if (pos >= src.size) {
                return
            }
            when (src[pos]) {
                '#'.code.toByte() -> {
                    commentContent()
                    lineEnding()
                }
                '\n'.code.toByte() -> pos += 1
                '\r'.code.toByte() -> {
                    if (pos + 1 >= src.size || src[pos + 1] != '\n'.code.toByte()) {
                        throw errorAt(pos, pos + 1, "expected `\\n` after `\\r`")
                    }
                    pos += 2
                }
                else -> return
            }
        }
    }

    /** One decoded key segment with its raw token span. */
    private class KeyPart(val name: String, val span: SpanRange)

    /** Parses a dotted key (toml_edit parser/key.rs): simple keys joined by
     * dots, with whitespace allowed around each dot. */
    private fun parseKey(): List<KeyPart> {
        val parts = ArrayList<KeyPart>()
        while (true) {
            val part = parseSimpleKey()
            parts.add(part)
            skipWS()
            if (pos >= src.size || src[pos] != '.'.code.toByte()) {
                return parts
            }
            pos += 1
            skipWS()
        }
    }

    /** Parses a bare, basic-string, or literal-string key (single-line forms
     * only). */
    private fun parseSimpleKey(): KeyPart {
        if (pos >= src.size) {
            throw errorAt(pos, pos, "expected key")
        }
        val start = pos
        when (src[pos]) {
            '"'.code.toByte() -> {
                val value = parseBasicString()
                return KeyPart(value, SpanRange(start, pos))
            }
            '\''.code.toByte() -> {
                val value = parseLiteralString()
                return KeyPart(value, SpanRange(start, pos))
            }
        }
        while (pos < src.size) {
            val c = src[pos]
            if ((c >= 'A'.code.toByte() && c <= 'Z'.code.toByte()) ||
                (c >= 'a'.code.toByte() && c <= 'z'.code.toByte()) ||
                (c >= '0'.code.toByte() && c <= '9'.code.toByte()) ||
                c == '-'.code.toByte() || c == '_'.code.toByte()
            ) {
                pos += 1
                continue
            }
            break
        }
        if (pos == start) {
            throw errorAt(pos, pos + 1, "expected key")
        }
        return KeyPart(utf8(src, start, pos), SpanRange(start, pos))
    }

    /** Parses one key-value line and applies the toml_edit keyval semantics
     * (toml_edit parser/state.rs on_keyval). */
    private fun parseKeyval() {
        val parts = parseKey()
        if (pos >= src.size || src[pos] != '='.code.toByte()) {
            throw errorAt(pos, pos + 1, "expected `=`")
        }
        pos += 1
        skipWS()
        val value = parseValue()
        lineTrailing()
        val leaf = parts[parts.size - 1]
        val table = descendPath(current, parts.subList(0, parts.size - 1), dotted = true)
        if (table.dotted == (parts.size == 1)) {
            throw errorAt(
                leaf.span.start, leaf.span.end,
                "dotted key redefines a defined table",
            )
        }
        if (table.byName.containsKey(leaf.name)) {
            throw errorAt(leaf.span.start, leaf.span.end, "duplicate key `" + leaf.name + "`")
        }
        table.items.add(
            PItem(leaf.name, leaf.span, PItemKind.KEYVAL, value = value),
        )
        table.byName[leaf.name] = table.items.size - 1
        if (table.hasSpan) {
            table.span = SpanRange(table.span.start, value.span.end)
        }
    }

    /** Walks the dotted path, creating implicit (and, for keyval paths,
     * dotted) tables as needed (toml_edit parser/state.rs descend_path). */
    private fun descendPath(table: PTable, path: List<KeyPart>, dotted: Boolean): PTable {
        var currentTable = table
        for ((index, part) in path.withIndex()) {
            val position = currentTable.byName[part.name]
            if (position == null) {
                val child = PTable(
                    part.name, part.span, part.span, false,
                    implicit = true, dotted = dotted,
                )
                currentTable.items.add(
                    PItem(part.name, part.span, PItemKind.SUBTABLE, table = child),
                )
                currentTable.byName[part.name] = currentTable.items.size - 1
                currentTable = child
                continue
            }
            val item = currentTable.items[position]
            when (item.kind) {
                PItemKind.KEYVAL -> throw errorAt(
                    part.span.start, part.span.end,
                    "dotted key `" + path.subList(0, index + 1).joinToString(".") { it.name } +
                        "` attempted to extend non-table type (" + valueTypeName(item.value!!.kind) + ")",
                )
                PItemKind.AOT -> {
                    val array = item.aot!!
                    if (array.elements.isEmpty()) {
                        throw errorAt(part.span.start, part.span.end, "empty array of tables")
                    }
                    currentTable = array.elements[array.elements.size - 1]
                }
                PItemKind.SUBTABLE -> {
                    val child = item.table!!
                    if (dotted && !child.implicit) {
                        throw errorAt(
                            part.span.start, part.span.end,
                            "dotted key `" + path.subList(0, index + 1).joinToString(".") { it.name } +
                                "` redefines a defined table",
                        )
                    }
                    currentTable = child
                }
            }
        }
        return currentTable
    }

    private fun valueTypeName(kind: PValueKind): String =
        when (kind) {
            PValueKind.STRING -> "string"
            PValueKind.INTEGER -> "integer"
            PValueKind.FLOAT -> "float"
            PValueKind.BOOLEAN -> "boolean"
            PValueKind.DATETIME -> "datetime"
            PValueKind.ARRAY -> "array"
            PValueKind.INLINE_TABLE -> "inline table"
        }

    /** Parses a standard `[a.b]` or array-of-tables `[[a.b]]` header and
     * switches the current table (toml_edit parser/state.rs
     * on_std_header/on_array_header). */
    private fun parseHeader() {
        val start = pos
        val arrayTable = pos + 1 < src.size && src[pos + 1] == '['.code.toByte()
        pos += if (arrayTable) 2 else 1
        skipWS()
        val parts = parseKey()
        if (parts.isEmpty()) {
            throw errorAt(start, pos, "empty table header")
        }
        skipWS()
        if (arrayTable) {
            if (pos + 1 >= src.size || src[pos] != ']'.code.toByte() || src[pos + 1] != ']'.code.toByte()) {
                throw errorAt(pos, pos + 1, "expected `]]`")
            }
            pos += 2
        } else {
            if (pos >= src.size || src[pos] != ']'.code.toByte()) {
                throw errorAt(pos, pos + 1, "expected `]`")
            }
            pos += 1
        }
        lineTrailing()
        val headerSpan = SpanRange(start, pos)

        // Finalize the previous current table first.
        finalize()
        val leaf = parts[parts.size - 1]
        val parent = descendPath(root, parts.subList(0, parts.size - 1), dotted = false)
        if (arrayTable) {
            val position = parent.byName[leaf.name]
            val array: PAot
            if (position != null) {
                if (parent.items[position].kind != PItemKind.AOT) {
                    throw errorAt(
                        leaf.span.start, leaf.span.end,
                        "duplicate key `" + leaf.name + "`",
                    )
                }
                array = parent.items[position].aot!!
            } else {
                array = PAot(leaf.name, leaf.span, SpanRange(0, 0), false)
                parent.items.add(
                    PItem(leaf.name, leaf.span, PItemKind.AOT, aot = array),
                )
                parent.byName[leaf.name] = parent.items.size - 1
            }
            // The element table is appended to the array at finalize
            // (toml_edit finalize_table), keeping the array span computation
            // intact.
            val table = PTable(
                leaf.name, leaf.span, headerSpan, true,
            )
            current = table
            currentPath = parts.map { it.name }.toMutableList()
            return
        }
        val position = parent.byName[leaf.name]
        if (position != null) {
            val item = parent.items[position]
            if (item.kind != PItemKind.SUBTABLE || !item.table!!.implicit || item.table!!.dotted) {
                throw errorAt(leaf.span.start, leaf.span.end, "duplicate key `" + leaf.name + "`")
            }
            // Reuse the implicit table (its children are preserved); it is
            // removed now and reinserted at finalize, moving it to the end of
            // the parent's items (toml_edit start_table remove/reinsert).
            val table = item.table!!
            table.implicit = false
            table.dotted = false
            table.span = headerSpan
            table.hasSpan = true
            parent.items.removeAt(position)
            rebuildByName(parent)
            current = table
            currentPath = parts.map { it.name }.toMutableList()
            return
        }
        // The new table is inserted into the parent at finalize (toml_edit
        // finalize_table); inserting at header time would make the finalize
        // see an occupied non-implicit entry.
        val table = PTable(
            leaf.name, leaf.span, headerSpan, true,
        )
        current = table
        currentPath = parts.map { it.name }.toMutableList()
    }

    private fun rebuildByName(table: PTable) {
        table.byName.clear()
        for ((index, item) in table.items.withIndex()) {
            table.byName[item.name] = index
        }
    }

    /** Inserts the current table into its parent (toml_edit parser/state.rs
     * finalize_table); for arrays of tables it appends the element table to
     * the array and extends the array span. */
    private fun finalize() {
        val table = current
        if (table === root) {
            return
        }
        val path = currentPath
        val parent = descendPath(root, path.subList(0, path.size - 1).map { KeyPart(it, SpanRange(0, 0)) }, dotted = false)
        val leaf = path[path.size - 1]
        val position = parent.byName[leaf]
        if (position == null) {
            parent.items.add(
                PItem(leaf, table.keySpan, PItemKind.SUBTABLE, table = table),
            )
            parent.byName[leaf] = parent.items.size - 1
        } else {
            val item = parent.items[position]
            when {
                item.kind == PItemKind.SUBTABLE && item.table!!.implicit -> {
                    item.table = table
                    item.keySpan = table.keySpan
                }
                item.kind == PItemKind.AOT -> {
                    val array = item.aot!!
                    array.elements.add(table)
                    if (array.elements.size == 1) {
                        array.span = table.span
                        array.hasSpan = true
                    } else {
                        array.span = SpanRange(array.span.start, table.span.end)
                    }
                    current = root
                    currentPath = ArrayList()
                    return
                }
                else -> throw errorAt(0, 0, "duplicate key `" + leaf + "`")
            }
        }
        current = root
        currentPath = ArrayList()
    }

    /** Parses one complete value (toml_edit parser/value.rs): strings,
     * booleans, arrays, inline tables, date-times, floats, and integers, in
     * the toml_edit dispatch order. */
    private fun parseValue(): PValue {
        if (pos >= src.size) {
            throw errorAt(pos, pos, "expected value")
        }
        val start = pos
        when (val c = src[pos]) {
            '"'.code.toByte(), '\''.code.toByte() -> return parseStringValue(start)
            '['.code.toByte() -> return parseArray(start)
            '{'.code.toByte() -> return parseInlineTableValue(start)
            '+'.code.toByte(), '-'.code.toByte(),
            '0'.code.toByte(), '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(),
            '4'.code.toByte(), '5'.code.toByte(), '6'.code.toByte(), '7'.code.toByte(),
            '8'.code.toByte(), '9'.code.toByte() -> return parseNumberValue(start)
            '_'.code.toByte() -> throw errorAt(pos, pos + 1, "expected leading digit")
            '.'.code.toByte() -> throw errorAt(pos, pos + 1, "expected leading digit")
            't'.code.toByte() -> {
                if (hasPrefix(src, pos, "true")) {
                    pos += 4
                    return PValue(SpanRange(start, pos), PValueKind.BOOLEAN, boolean = true)
                }
                throw errorAt(pos, pos + 1, "expected string value")
            }
            'f'.code.toByte() -> {
                if (hasPrefix(src, pos, "false")) {
                    pos += 5
                    return PValue(SpanRange(start, pos), PValueKind.BOOLEAN, boolean = false)
                }
                throw errorAt(pos, pos + 1, "expected string value")
            }
            'i'.code.toByte() -> {
                if (hasPrefix(src, pos, "inf")) {
                    pos += 3
                    return PValue(
                        SpanRange(start, pos), PValueKind.FLOAT,
                        bits = 0x7ff0000000000000L,
                    )
                }
                throw errorAt(pos, pos + 1, "expected string value")
            }
            'n'.code.toByte() -> {
                if (hasPrefix(src, pos, "nan")) {
                    pos += 3
                    return PValue(
                        SpanRange(start, pos), PValueKind.FLOAT,
                        bits = 0x7ff8000000000000L,
                    )
                }
                throw errorAt(pos, pos + 1, "expected string value")
            }
            else -> throw errorAt(pos, pos + 1, "expected string value")
        }
    }

    private fun parseStringValue(start: Int): PValue {
        val value = parseStringToken()
        return PValue(SpanRange(start, pos), PValueKind.STRING, str = value)
    }

    /** Dispatches on the opening quote and returns the decoded value. */
    private fun parseStringToken(): String {
        if (hasPrefix(src, pos, "\"\"\"")) {
            return parseMultilineBasicString()
        }
        if (hasPrefix(src, pos, "'''")) {
            return parseMultilineLiteralString()
        }
        if (src[pos] == '"'.code.toByte()) {
            return parseBasicString()
        }
        return parseLiteralString()
    }

    /** Parses a single-line basic string; the caller reads the end offset
     * from [pos]. */
    private fun parseBasicString(): String {
        val start = pos
        pos += 1 // opening quote
        val output = ByteArrayOutputStreamKt()
        while (true) {
            if (pos >= src.size) {
                throw errorAt(start, pos, "unterminated basic string")
            }
            val c = src[pos]
            when {
                c == '"'.code.toByte() -> {
                    pos += 1
                    return output.utf8()
                }
                c == '\\'.code.toByte() -> output.appendCodepoint(parseEscape())
                c == '\t'.code.toByte() || (c >= 0x20 && c <= 0x21) ||
                    (c >= 0x23 && c <= 0x5B) || (c >= 0x5D && c <= 0x7E) ||
                    (c.toInt() and 0xff) >= 0x80 -> {
                    pos += 1
                    output.write(c)
                }
                else -> throw errorAt(pos, pos + 1, "invalid basic string character")
            }
        }
    }

    /** Parses one `\` escape sequence. */
    private fun parseEscape(): Int {
        pos += 1 // backslash
        if (pos >= src.size) {
            throw errorAt(pos - 1, pos, "unterminated escape sequence")
        }
        val c = src[pos]
        pos += 1
        return when (c) {
            'b'.code.toByte() -> 0x08
            't'.code.toByte() -> 0x09
            'n'.code.toByte() -> 0x0A
            'f'.code.toByte() -> 0x0C
            'r'.code.toByte() -> 0x0D
            '"'.code.toByte() -> 0x22
            '\\'.code.toByte() -> 0x5C
            'u'.code.toByte() -> parseHexEscape(4)
            'U'.code.toByte() -> parseHexEscape(8)
            else -> throw errorAt(pos - 2, pos, "invalid escape sequence")
        }
    }

    private fun parseHexEscape(digits: Int): Int {
        if (pos + digits > src.size) {
            throw errorAt(pos, pos, "invalid unicode escape")
        }
        var value = 0
        for (i in 0 until digits) {
            val c = src[pos + i]
            val digit = hexValue(c)
            if (digit < 0) {
                throw errorAt(pos, pos + digits, "invalid unicode escape")
            }
            value = value * 16 + digit
        }
        pos += digits
        val scalar = value
        if (scalar > 0x10FFFF || (scalar >= 0xD800 && scalar <= 0xDFFF)) {
            throw errorAt(pos - digits, pos, "unicode escape is not a scalar value")
        }
        return scalar
    }

    private fun hexValue(c: Byte): Int = when (c) {
        in '0'.code.toByte()..'9'.code.toByte() -> c - '0'.code.toByte()
        in 'a'.code.toByte()..'f'.code.toByte() -> c - 'a'.code.toByte() + 10
        in 'A'.code.toByte()..'F'.code.toByte() -> c - 'A'.code.toByte() + 10
        else -> -1
    }

    /** Parses `"""..."""` (toml_edit parser/strings.rs ml-basic-string): the
     * first newline is trimmed, CRLF is normalized to LF, backslash-line-
     * ending continuations are trimmed, and runs of one or two quotes
     * followed by content are literal. */
    private fun parseMultilineBasicString(): String {
        val start = pos
        pos += 3
        if (pos < src.size && (src[pos] == '\n'.code.toByte() ||
                (src[pos] == '\r'.code.toByte() && pos + 1 < src.size && src[pos + 1] == '\n'.code.toByte()))
        ) {
            pos += if (src[pos] == '\r'.code.toByte()) 2 else 1
        }
        val output = ByteArrayOutputStreamKt()
        while (true) {
            if (pos >= src.size) {
                throw errorAt(start, pos, "unterminated multiline basic string")
            }
            val c = src[pos]
            when {
                hasPrefix(src, pos, "\"\"\"") -> {
                    pos += 3
                    return output.utf8()
                }
                c == '"'.code.toByte() && pos + 1 < src.size && src[pos + 1] == '"'.code.toByte() &&
                    (pos + 2 >= src.size || src[pos + 2] != '"'.code.toByte()) -> {
                    output.write('"'.code.toByte())
                    output.write('"'.code.toByte())
                    pos += 2
                }
                c == '"'.code.toByte() && (pos + 1 >= src.size || src[pos + 1] != '"'.code.toByte()) -> {
                    output.write('"'.code.toByte())
                    pos += 1
                }
                c == '\\'.code.toByte() && escapedNewlineAhead() -> parseEscapedNewline()
                c == '\\'.code.toByte() -> output.appendCodepoint(parseEscape())
                c == '\r'.code.toByte() -> {
                    if (pos + 1 >= src.size || src[pos + 1] != '\n'.code.toByte()) {
                        throw errorAt(pos, pos + 1, "invalid multiline basic string character")
                    }
                    pos += 2
                    output.write('\n'.code.toByte())
                }
                c == '\n'.code.toByte() -> {
                    pos += 1
                    output.write('\n'.code.toByte())
                }
                c == '\t'.code.toByte() || (c >= 0x20 && c <= 0x21) ||
                    (c >= 0x23 && c <= 0x5B) || (c >= 0x5D && c <= 0x7E) ||
                    (c.toInt() and 0xff) >= 0x80 -> {
                    pos += 1
                    output.write(c)
                }
                else -> throw errorAt(pos, pos + 1, "invalid multiline basic string character")
            }
        }
    }

    /** Reports whether the backslash at the current position begins a
     * line-ending continuation: `\` ws newline. */
    private fun escapedNewlineAhead(): Boolean {
        var cursor = pos + 1
        while (cursor < src.size && (src[cursor] == ' '.code.toByte() || src[cursor] == '\t'.code.toByte())) {
            cursor += 1
        }
        if (cursor >= src.size) {
            return false
        }
        return src[cursor] == '\n'.code.toByte() ||
            (src[cursor] == '\r'.code.toByte() && cursor + 1 < src.size && src[cursor + 1] == '\n'.code.toByte())
    }

    /** Trims one or more `\` ws newline (wschar/newline)* continuations
     * (toml_edit parser/strings.rs mlb-escaped-nl). */
    private fun parseEscapedNewline() {
        while (true) {
            pos += 1 // backslash
            skipWS()
            if (pos >= src.size) {
                throw errorAt(pos, pos, "unterminated line continuation")
            }
            when (src[pos]) {
                '\n'.code.toByte() -> pos += 1
                '\r'.code.toByte() -> {
                    if (pos + 1 >= src.size || src[pos + 1] != '\n'.code.toByte()) {
                        throw errorAt(pos, pos + 1, "expected `\\n` after `\\r`")
                    }
                    pos += 2
                }
                else -> throw errorAt(pos, pos + 1, "expected newline after `\\`")
            }
            while (pos < src.size) {
                val c = src[pos]
                if (c == ' '.code.toByte() || c == '\t'.code.toByte() || c == '\n'.code.toByte()) {
                    pos += 1
                    continue
                }
                if (c == '\r'.code.toByte() && pos + 1 < src.size && src[pos + 1] == '\n'.code.toByte()) {
                    pos += 2
                    continue
                }
                break
            }
            if (pos >= src.size || src[pos] != '\\'.code.toByte() || !escapedNewlineAhead()) {
                return
            }
        }
    }

    /** Parses a single-line literal string. */
    private fun parseLiteralString(): String {
        val start = pos
        pos += 1 // opening apostrophe
        val contentStart = pos
        while (pos < src.size) {
            val c = src[pos]
            if (c == '\''.code.toByte()) {
                val value = utf8(src, contentStart, pos)
                pos += 1
                return value
            }
            if (c == '\t'.code.toByte() || (c >= 0x20 && c <= 0x26) ||
                (c >= 0x28 && c <= 0x7E) || (c.toInt() and 0xff) >= 0x80
            ) {
                pos += 1
                continue
            }
            throw errorAt(pos, pos + 1, "invalid literal string character")
        }
        throw errorAt(start, pos, "unterminated literal string")
    }

    /** Parses `'''...'''` (toml_edit parser/strings.rs ml-literal-string):
     * the first newline is trimmed and CRLF is normalized to LF; no escapes;
     * runs of one or two apostrophes followed by content are literal. */
    private fun parseMultilineLiteralString(): String {
        val start = pos
        pos += 3
        if (pos < src.size && (src[pos] == '\n'.code.toByte() ||
                (src[pos] == '\r'.code.toByte() && pos + 1 < src.size && src[pos + 1] == '\n'.code.toByte()))
        ) {
            pos += if (src[pos] == '\r'.code.toByte()) 2 else 1
        }
        val contentStart = pos
        while (true) {
            if (pos >= src.size) {
                throw errorAt(start, pos, "unterminated multiline literal string")
            }
            val c = src[pos]
            when {
                hasPrefix(src, pos, "'''") -> {
                    val value = utf8(src, contentStart, pos).replace("\r\n", "\n")
                    pos += 3
                    return value
                }
                c == '\''.code.toByte() && pos + 1 < src.size && src[pos + 1] == '\''.code.toByte() &&
                    (pos + 2 >= src.size || src[pos + 2] != '\''.code.toByte()) -> {
                    pos += 2
                }
                c == '\''.code.toByte() && (pos + 1 >= src.size || src[pos + 1] != '\''.code.toByte()) -> {
                    pos += 1
                }
                c == '\r'.code.toByte() -> {
                    if (pos + 1 >= src.size || src[pos + 1] != '\n'.code.toByte()) {
                        throw errorAt(pos, pos + 1, "invalid multiline literal string character")
                    }
                    pos += 2
                }
                c == '\n'.code.toByte() -> pos += 1
                c == '\t'.code.toByte() || (c >= 0x20 && c <= 0x26) ||
                    (c >= 0x28 && c <= 0x7E) || (c.toInt() and 0xff) >= 0x80 -> pos += 1
                else -> throw errorAt(pos, pos + 1, "invalid multiline literal string character")
            }
        }
    }

    /** Parses a date-time, float, or integer (toml_edit parser/value.rs
     * dispatch and parser/numbers.rs). */
    private fun parseNumberValue(start: Int): PValue {
        // Date-time first; a cut failure inside it is a hard error.
        val attempt = tryDateTime(start)
        if (attempt.matched) {
            return PValue(
                SpanRange(start, pos), PValueKind.DATETIME, dateTime = attempt.dateTime,
            )
        }
        if (attempt.cut) {
            throw errorAt(start, pos, "invalid date-time")
        }
        // Float: dec-int part with exp and/or fraction, or a special float;
        // the complete token must parse as a finite f64.
        val floatToken = tryFloatToken()
        if (floatToken != null) {
            val text = floatToken.replace("_", "")
            val value = text.toDoubleOrNull()
            if (value == null || value.isInfinite()) {
                throw errorAt(start, pos, "invalid floating-point number")
            }
            return PValue(
                SpanRange(start, pos), PValueKind.FLOAT,
                bits = java.lang.Double.doubleToRawLongBits(value),
            )
        }
        val special = trySpecialFloat(start)
        if (special != null) {
            return special
        }
        // Integer: decimal, hex, octal, or binary.
        val integer = parseIntToken(start)
        return PValue(SpanRange(start, pos), PValueKind.INTEGER, integer = integer)
    }

    /** Parses `[+-]? inf|nan`. */
    private fun trySpecialFloat(start: Int): PValue? {
        var cursor = pos
        var negative = false
        if (cursor < src.size && (src[cursor] == '+'.code.toByte() || src[cursor] == '-'.code.toByte())) {
            negative = src[cursor] == '-'.code.toByte()
            cursor += 1
        }
        if (cursor + 3 > src.size) {
            return null
        }
        if (hasPrefix(src, cursor, "inf")) {
            pos = cursor + 3
            return PValue(
                SpanRange(start, pos), PValueKind.FLOAT,
                bits = if (negative) {
                    java.lang.Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY)
                } else {
                    java.lang.Double.doubleToRawLongBits(Double.POSITIVE_INFINITY)
                },
            )
        }
        if (hasPrefix(src, cursor, "nan")) {
            pos = cursor + 3
            return PValue(
                SpanRange(start, pos), PValueKind.FLOAT,
                bits = if (negative) {
                    java.lang.Double.doubleToRawLongBits(-Double.NaN)
                } else {
                    java.lang.Double.doubleToRawLongBits(Double.NaN)
                },
            )
        }
        return null
    }

    /** One datetime attempt result: matched, cut (hard failure inside the
     * date/time/offset part), or neither (clean backtrack). */
    private class DateTimeAttempt(
        val matched: Boolean,
        val cut: Boolean,
        val dateTime: TomlDateTime = TomlDateTime(null, null, null),
    )

    /** Attempts the RFC 3339 date-time grammar (toml_edit
     * parser/datetime.rs). */
    private fun tryDateTime(start: Int): DateTimeAttempt {
        if (pos + 10 > src.size) {
            return tryLocalTime(start)
        }
        val year = asciiInt(src, pos, pos + 4)
        if (year == null) {
            return tryLocalTime(start)
        }
        if (src[pos + 4] != '-'.code.toByte()) {
            return tryLocalTime(start)
        }
        val month = asciiInt(src, pos + 5, pos + 7)
        if (month == null || month < 1 || month > 12) {
            return DateTimeAttempt(false, true)
        }
        if (src[pos + 7] != '-'.code.toByte()) {
            return DateTimeAttempt(false, true)
        }
        val day = asciiInt(src, pos + 8, pos + 10)
        if (day == null || day < 1 || day > 31 || day > daysInMonth(year, month)) {
            return DateTimeAttempt(false, true)
        }
        val date = TomlDate(year, month, day)
        pos += 10
        // Optional time after T/t/space.
        if (pos < src.size &&
            (src[pos] == 'T'.code.toByte() || src[pos] == 't'.code.toByte() || src[pos] == ' '.code.toByte())
        ) {
            val saved = pos
            pos += 1
            val time = tryPartialTime()
            if (!time.matched) {
                if (time.cut) {
                    return DateTimeAttempt(false, true)
                }
                // Clean backtrack: restore before the delimiter so the value
                // is the date alone (toml_edit opt semantics).
                pos = saved
                return DateTimeAttempt(true, false, TomlDateTime(date, null, null))
            }
            val offset = tryTimeOffset()
            if (offset.err != null) {
                throw offset.err!!
            }
            if (offset.cut) {
                return DateTimeAttempt(false, true)
            }
            return DateTimeAttempt(true, false, TomlDateTime(date, time.time, offset.offset))
        }
        return DateTimeAttempt(true, false, TomlDateTime(date, null, null))
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        return when {
            month == 2 && leap -> 29
            month == 2 -> 28
            month == 4 || month == 6 || month == 9 || month == 11 -> 30
            else -> 31
        }
    }

    /** Attempts a bare local time value. */
    private fun tryLocalTime(start: Int): DateTimeAttempt {
        val time = tryPartialTime()
        if (!time.matched) {
            return DateTimeAttempt(false, time.cut)
        }
        return DateTimeAttempt(true, false, TomlDateTime(null, time.time, null))
    }

    /** One partial-time attempt: matched, cut (hard failure after the first
     * two digits and colon), or neither. */
    private class TimeAttempt(val matched: Boolean, val cut: Boolean, val time: TomlTime? = null)

    /** Parses HH:MM:SS with an optional fraction. */
    private fun tryPartialTime(): TimeAttempt {
        if (pos + 2 > src.size) {
            return TimeAttempt(false, false)
        }
        val hour = asciiInt(src, pos, pos + 2)
        if (hour == null || hour > 23) {
            return TimeAttempt(false, false)
        }
        if (pos + 2 >= src.size || src[pos + 2] != ':'.code.toByte()) {
            return TimeAttempt(false, false)
        }
        pos += 3
        if (pos + 2 > src.size) {
            return TimeAttempt(false, true)
        }
        val minute = asciiInt(src, pos, pos + 2)
        if (minute == null || minute > 59) {
            return TimeAttempt(false, true)
        }
        if (pos + 2 >= src.size || src[pos + 2] != ':'.code.toByte()) {
            return TimeAttempt(false, true)
        }
        pos += 3
        if (pos + 2 > src.size) {
            return TimeAttempt(false, true)
        }
        val second = asciiInt(src, pos, pos + 2)
        if (second == null || second > 60) {
            return TimeAttempt(false, true)
        }
        pos += 2
        var nanosecond = 0L
        if (pos < src.size && src[pos] == '.'.code.toByte()) {
            var cursor = pos + 1
            val digitsStart = cursor
            while (cursor < src.size && src[cursor] >= '0'.code.toByte() && src[cursor] <= '9'.code.toByte()) {
                cursor += 1
            }
            if (cursor == digitsStart) {
                return TimeAttempt(false, true)
            }
            val fraction = utf8(src, digitsStart, cursor)
            val truncated = if (fraction.length > 9) fraction.substring(0, 9) else fraction
            val value = truncated.toLongOrNull()
            if (value == null) {
                return TimeAttempt(false, true)
            }
            var scaled = value
            for (index in truncated.length until 9) {
                scaled *= 10
            }
            nanosecond = scaled
            pos = cursor
        }
        return TimeAttempt(
            true, false,
            TomlTime(hour, minute, second, nanosecond),
        )
    }

    /** One offset attempt: offset, cut, or a hard error. */
    private class OffsetAttempt(val offset: TomlOffset?, val cut: Boolean, val err: ParseError?)

    /** Parses an optional `Z` or `±HH:MM` offset; cut reports a hard failure
     * inside a numeric offset. */
    private fun tryTimeOffset(): OffsetAttempt {
        if (pos >= src.size) {
            return OffsetAttempt(null, false, null)
        }
        when (src[pos]) {
            'Z'.code.toByte(), 'z'.code.toByte() -> {
                pos += 1
                return OffsetAttempt(TomlOffset.Z, false, null)
            }
            '+'.code.toByte(), '-'.code.toByte() -> {
                val sign = src[pos]
                if (pos + 6 > src.size) {
                    return OffsetAttempt(null, true, null)
                }
                val hour = asciiInt(src, pos + 1, pos + 3)
                if (hour == null || hour > 23) {
                    return OffsetAttempt(null, true, null)
                }
                if (src[pos + 3] != ':'.code.toByte()) {
                    return OffsetAttempt(null, true, null)
                }
                val minute = asciiInt(src, pos + 4, pos + 6)
                if (minute == null || minute > 59) {
                    return OffsetAttempt(null, true, null)
                }
                var minutes = hour * 60 + minute
                if (sign == '-'.code.toByte()) {
                    minutes = -minutes
                }
                pos += 6
                return OffsetAttempt(TomlOffset.CustomMinutes(minutes), false, null)
            }
        }
        return OffsetAttempt(null, false, null)
    }

    /** Attempts the float grammar and reports the consumed token, or null
     * (toml_edit parser/numbers.rs float). */
    private fun tryFloatToken(): String? {
        val saved = pos
        if (!tryDecIntPart()) {
            pos = saved
            return null
        }
        if (pos < src.size && (src[pos] == 'e'.code.toByte() || src[pos] == 'E'.code.toByte())) {
            pos += 1
            if (pos < src.size && (src[pos] == '+'.code.toByte() || src[pos] == '-'.code.toByte())) {
                pos += 1
            }
            if (!tryZeroPrefixableInt()) {
                pos = saved
                return null
            }
            return utf8(src, saved, pos)
        }
        if (pos < src.size && src[pos] == '.'.code.toByte()) {
            pos += 1
            if (!tryZeroPrefixableInt()) {
                pos = saved
                return null
            }
            if (pos < src.size && (src[pos] == 'e'.code.toByte() || src[pos] == 'E'.code.toByte())) {
                pos += 1
                if (pos < src.size && (src[pos] == '+'.code.toByte() || src[pos] == '-'.code.toByte())) {
                    pos += 1
                }
                if (!tryZeroPrefixableInt()) {
                    pos = saved
                    return null
                }
            }
            return utf8(src, saved, pos)
        }
        pos = saved
        return null
    }

    /** Matches `[+-]? (0 | [1-9][0-9_]* with underscore rules)` (toml_edit
     * parser/numbers.rs dec-int: the single `0` token never extends). */
    private fun tryDecIntPart(): Boolean {
        val saved = pos
        if (pos < src.size && (src[pos] == '+'.code.toByte() || src[pos] == '-'.code.toByte())) {
            pos += 1
        }
        if (pos >= src.size) {
            pos = saved
            return false
        }
        val c = src[pos]
        if (c == '0'.code.toByte()) {
            pos += 1
            return true
        }
        if (c < '1'.code.toByte() || c > '9'.code.toByte()) {
            pos = saved
            return false
        }
        pos += 1
        while (pos < src.size) {
            val current = src[pos]
            if (current >= '0'.code.toByte() && current <= '9'.code.toByte()) {
                pos += 1
                continue
            }
            if (current == '_'.code.toByte()) {
                if (pos + 1 >= src.size || src[pos + 1] < '0'.code.toByte() || src[pos + 1] > '9'.code.toByte()) {
                    pos = saved
                    return false
                }
                pos += 2
                continue
            }
            break
        }
        return true
    }

    /** Matches `[0-9]([0-9]|_[0-9])*`. */
    private fun tryZeroPrefixableInt(): Boolean {
        val saved = pos
        if (pos >= src.size || src[pos] < '0'.code.toByte() || src[pos] > '9'.code.toByte()) {
            return false
        }
        pos += 1
        while (pos < src.size) {
            val c = src[pos]
            if (c >= '0'.code.toByte() && c <= '9'.code.toByte()) {
                pos += 1
                continue
            }
            if (c == '_'.code.toByte()) {
                if (pos + 1 >= src.size || src[pos + 1] < '0'.code.toByte() || src[pos + 1] > '9'.code.toByte()) {
                    pos = saved
                    return false
                }
                pos += 2
                continue
            }
            break
        }
        return true
    }

    /** Parses a decimal, hex, octal, or binary integer (toml_edit
     * parser/numbers.rs integer); the token's digits must fit i64. */
    private fun parseIntToken(start: Int): Long {
        val saved = pos
        if (pos + 2 <= src.size && src[pos] == '0'.code.toByte()) {
            val base: Int
            when (src[pos + 1]) {
                'x'.code.toByte() -> base = 16
                'o'.code.toByte() -> base = 8
                'b'.code.toByte() -> base = 2
                else -> base = 0
            }
            if (base != 0) {
                pos += 2
                val digitsStart = pos
                // The first digit after the prefix is mandatory; underscores
                // may only separate digits (toml_edit parser/numbers.rs
                // hex-int: `hexdig *( hexdig / underscore hexdig )`).
                if (pos >= src.size || !isDigitBase(src[pos], base)) {
                    pos = saved
                    throw errorAt(pos, pos + 1, "invalid integer")
                }
                pos += 1
                while (pos < src.size) {
                    val c = src[pos]
                    if (isDigitBase(c, base)) {
                        pos += 1
                        continue
                    }
                    if (c == '_'.code.toByte()) {
                        if (pos + 1 >= src.size || !isDigitBase(src[pos + 1], base)) {
                            pos = saved
                            throw errorAt(pos, pos + 1, "invalid integer")
                        }
                        pos += 2
                        continue
                    }
                    break
                }
                val text = utf8(src, digitsStart, pos).replace("_", "")
                val value = text.toLongOrNull(base)
                if (value == null) {
                    pos = saved
                    throw errorAt(pos, pos + 1, "number too large to fit in target type")
                }
                return value
            }
        }
        if (!tryDecIntPart()) {
            pos = saved
            throw errorAt(pos, pos + 1, "invalid integer")
        }
        val text = utf8(src, saved, pos).replace("_", "")
        val value = text.toLongOrNull(10)
        if (value == null) {
            pos = saved
            throw errorAt(pos, pos + 1, "number too large to fit in target type")
        }
        return value
    }

    private fun isDigitBase(c: Byte, base: Int): Boolean = when (base) {
        16 -> (c >= '0'.code.toByte() && c <= '9'.code.toByte()) ||
            (c >= 'a'.code.toByte() && c <= 'f'.code.toByte()) ||
            (c >= 'A'.code.toByte() && c <= 'F'.code.toByte())
        8 -> c >= '0'.code.toByte() && c <= '7'.code.toByte()
        2 -> c == '0'.code.toByte() || c == '1'.code.toByte()
        else -> c >= '0'.code.toByte() && c <= '9'.code.toByte()
    }

    /** Parses `[` values `]` with comments, newlines, and an optional
     * trailing comma (toml_edit parser/array.rs). */
    private fun parseArray(start: Int): PValue {
        pos += 1 // '['
        wsCommentNewline()
        val array = PValue(SpanRange(start, 0), PValueKind.ARRAY)
        if (pos < src.size && src[pos] == ']'.code.toByte()) {
            pos += 1
            array.span = SpanRange(start, pos)
            return array
        }
        while (true) {
            val value = parseValue()
            array.array.add(value)
            wsCommentNewline()
            if (pos >= src.size || src[pos] != ','.code.toByte()) {
                if (pos >= src.size || src[pos] != ']'.code.toByte()) {
                    throw errorAt(pos, pos + 1, "expected `,` or `]`")
                }
                pos += 1
                array.span = SpanRange(start, pos)
                return array
            }
            pos += 1
            wsCommentNewline()
            if (pos < src.size && src[pos] == ']'.code.toByte()) {
                pos += 1
                array.span = SpanRange(start, pos)
                return array
            }
        }
    }

    /** Parses `{` keyvals `}` (toml_edit parser/inline_table.rs): no
     * trailing comma, no newlines outside values, dotted keys with the same
     * duplicate semantics. */
    private fun parseInlineTableValue(start: Int): PValue {
        pos += 1 // '{'
        skipWS()
        val table = PTable("", SpanRange(0, 0), SpanRange(0, 0), false, isInline = true)
        if (pos < src.size && src[pos] == '}'.code.toByte()) {
            pos += 1
            return PValue(SpanRange(start, pos), PValueKind.INLINE_TABLE, table = table)
        }
        while (true) {
            val parts = parseKey()
            if (pos >= src.size || src[pos] != '='.code.toByte()) {
                throw errorAt(pos, pos + 1, "expected `=`")
            }
            pos += 1
            skipWS()
            val value = parseValue()
            val leaf = parts[parts.size - 1]
            val child = descendInlinePath(table, parts.subList(0, parts.size - 1))
            if (child.dotted == (parts.size == 1)) {
                throw errorAt(
                    leaf.span.start, leaf.span.end,
                    "dotted key redefines a defined table",
                )
            }
            if (child.byName.containsKey(leaf.name)) {
                throw errorAt(leaf.span.start, leaf.span.end, "duplicate key `" + leaf.name + "`")
            }
            child.items.add(
                PItem(leaf.name, leaf.span, PItemKind.KEYVAL, value = value),
            )
            child.byName[leaf.name] = child.items.size - 1
            skipWS()
            if (pos >= src.size) {
                throw errorAt(start, pos, "unterminated inline table")
            }
            if (src[pos] == ','.code.toByte()) {
                pos += 1
                skipWS()
                if (pos >= src.size || src[pos] == '}'.code.toByte()) {
                    throw errorAt(pos, pos + 1, "expected key after `,`")
                }
                continue
            }
            if (src[pos] == '}'.code.toByte()) {
                pos += 1
                return PValue(SpanRange(start, pos), PValueKind.INLINE_TABLE, table = table)
            }
            throw errorAt(pos, pos + 1, "expected `,` or `}`")
        }
    }

    /** Applies the inline-table dotted-key semantics (toml_edit
     * parser/inline_table.rs descend_path). */
    private fun descendInlinePath(table: PTable, path: List<KeyPart>): PTable {
        var currentTable = table
        for (part in path) {
            val position = currentTable.byName[part.name]
            if (position == null) {
                val child = PTable(
                    part.name, part.span, part.span, false,
                    isInline = true, implicit = true, dotted = true,
                )
                currentTable.items.add(
                    PItem(
                        part.name, part.span, PItemKind.KEYVAL,
                        value = PValue(SpanRange(0, 0), PValueKind.INLINE_TABLE, table = child),
                    ),
                )
                currentTable.byName[part.name] = currentTable.items.size - 1
                currentTable = child
                continue
            }
            val item = currentTable.items[position]
            if (item.kind != PItemKind.KEYVAL || item.value!!.kind != PValueKind.INLINE_TABLE) {
                throw errorAt(
                    part.span.start, part.span.end,
                    "dotted key `" + part.name + "` attempted to extend non-table type",
                )
            }
            val child = item.value!!.table!!
            if (!child.implicit) {
                throw errorAt(
                    part.span.start, part.span.end,
                    "dotted key `" + part.name + "` redefines a defined table",
                )
            }
            currentTable = child
        }
        return currentTable
    }
}

/** Parses the complete source into the parse tree; any syntax violation
 * throws [ParseError]. */
internal fun parseTree(src: ByteArray): PTable = Parser(src).parseDocument()

private fun hasPrefix(src: ByteArray, offset: Int, prefix: String): Boolean {
    if (offset + prefix.length > src.size) {
        return false
    }
    for (i in prefix.indices) {
        if (src[offset + i] != prefix[i].code.toByte()) {
            return false
        }
    }
    return true
}

/** Parses a fixed-width ASCII digit run; null when any character is not a
 * digit. */
private fun asciiInt(src: ByteArray, start: Int, end: Int): Int? {
    if (end > src.size || start < 0 || start >= end) {
        return null
    }
    var value = 0
    for (i in start until end) {
        val c = src[i]
        if (c < '0'.code.toByte() || c > '9'.code.toByte()) {
            return null
        }
        value = value * 10 + (c - '0'.code.toByte())
    }
    return value
}

private fun utf8(src: ByteArray, start: Int, end: Int): String =
    String(src, start, end - start, Charsets.UTF_8)

/** A tiny UTF-8 byte accumulator for decoded string values. */
private class ByteArrayOutputStreamKt {
    private val bytes = java.io.ByteArrayOutputStream()

    fun write(byte: Byte) {
        bytes.write(byte.toInt() and 0xff)
    }

    fun appendCodepoint(scalar: Int) {
        bytes.write(String(Character.toChars(scalar)).toByteArray(Charsets.UTF_8))
    }

    fun utf8(): String = String(bytes.toByteArray(), Charsets.UTF_8)
}

// ---------------------------------------------------------------------------
// Entity building (consema-toml/src/parser.rs:84-337)

/** The parse-tree to immutable-entity converter. The entity order is the
 * exact Rust order: an item entity first, then for every association a key
 * entity, the child item, and the entry entity (parser.rs:190-243). */
internal class EntityBuilder(
    private val authority: DocumentAuthority,
    private val sourceLen: Int,
    private val limits: ParseLimits,
    private val entities: MutableList<Entity> = ArrayList(),
) {
    private fun add(entity: Entity): Int {
        val observed = entities.size + 1
        if (observed > limits.maxNodeCount) {
            throw TomlFormationException(
                listOf(resourceLimitDiagnostic("node_count", observed, limits.maxNodeCount)),
            )
        }
        val index = entities.size
        entities.add(entity)
        return index
    }

    private fun checkDepth(depth: Int) {
        if (depth > limits.maxNestingDepth) {
            throw TomlFormationException(
                listOf(
                    resourceLimitDiagnostic("nesting_depth", depth, limits.maxNestingDepth),
                ),
            )
        }
    }

    /** Clamps a parser range into the source and creates the snapshot-bound
     * span (the Go entityBuilder.span clamps are the cross-reference). */
    private fun span(range: SpanRange): Span {
        var start = range.start
        var end = range.end
        if (start < 0) {
            start = 0
        }
        if (end > sourceLen) {
            end = sourceLen
        }
        if (start > end) {
            start = end
        }
        return authority.span(start, end)
    }

    private fun addItem(range: SpanRange, item: InternalItemKind): Int =
        add(Entity(span(range), EntityKind.Item(ItemEntity(item))))

    private fun reserveItem(range: SpanRange): Int =
        addItem(range, InternalItemKind.Array(emptyList()))

    private fun replaceItem(index: Int, item: InternalItemKind) {
        entities[index] = Entity(entities[index].span, EntityKind.Item(ItemEntity(item)))
    }

    fun buildTable(table: PTable, root: Boolean, depth: Int, fallback: SpanRange): Int {
        checkDepth(depth)
        val tableRange = when {
            root -> SpanRange(0, sourceLen)
            table.hasSpan -> table.span
            else -> fallback
        }
        val itemIndex = reserveItem(tableRange)
        val entries = ArrayList<Int>()
        for ((ordinal, item) in table.items.withIndex()) {
            val keyRange = item.keySpan
            val keyIndex = add(
                Entity(
                    span(keyRange),
                    EntityKind.Key(KeyEntity(item.name)),
                ),
            )
            val childIndex: Int = when (item.kind) {
                PItemKind.KEYVAL -> buildValue(item.value!!, depth + 1, keyRange)
                PItemKind.SUBTABLE -> buildTable(item.table!!, false, depth + 1, keyRange)
                PItemKind.AOT -> buildAot(item.aot!!, depth + 1, keyRange)
            }
            val childSpan = entities[childIndex].span
            val entryRange = SpanRange(
                minOf(keyRange.start, childSpan.startByte),
                maxOf(keyRange.end, childSpan.endByte),
            )
            val entryIndex = add(
                Entity(
                    span(entryRange),
                    EntityKind.Entry(EntryEntity(ordinal, keyIndex, childIndex)),
                ),
            )
            entries.add(entryIndex)
        }
        val flavor = when {
            root -> TableFlavor.ROOT
            table.dotted -> TableFlavor.DOTTED
            table.implicit -> TableFlavor.IMPLICIT
            else -> TableFlavor.STANDARD
        }
        replaceItem(itemIndex, InternalItemKind.Table(flavor, entries))
        return itemIndex
    }

    fun buildInlineTable(table: PTable, depth: Int, range: SpanRange): Int {
        checkDepth(depth)
        val itemIndex = reserveItem(range)
        val entries = ArrayList<Int>()
        for ((ordinal, item) in table.items.withIndex()) {
            if (item.kind != PItemKind.KEYVAL) {
                throw ParseError(
                    item.keySpan.start, item.keySpan.end,
                    "invalid inline table structure",
                )
            }
            val keyRange = item.keySpan
            val keyIndex = add(
                Entity(
                    span(keyRange),
                    EntityKind.Key(KeyEntity(item.name)),
                ),
            )
            val childIndex = buildValue(item.value!!, depth + 1, keyRange)
            val childSpan = entities[childIndex].span
            val entryRange = SpanRange(
                minOf(keyRange.start, childSpan.startByte),
                maxOf(keyRange.end, childSpan.endByte),
            )
            val entryIndex = add(
                Entity(
                    span(entryRange),
                    EntityKind.Entry(EntryEntity(ordinal, keyIndex, childIndex)),
                ),
            )
            entries.add(entryIndex)
        }
        replaceItem(itemIndex, InternalItemKind.InlineTable(entries))
        return itemIndex
    }

    fun buildValue(value: PValue, depth: Int, fallback: SpanRange): Int {
        checkDepth(depth)
        val range = if (value.span.end == 0) fallback else value.span
        return when (value.kind) {
            PValueKind.ARRAY -> buildArray(value, depth, range)
            PValueKind.INLINE_TABLE -> buildInlineTable(value.table!!, depth, range)
            PValueKind.STRING -> addItem(range, InternalItemKind.String(value.str))
            PValueKind.INTEGER -> addItem(range, InternalItemKind.Integer(value.integer))
            PValueKind.FLOAT -> addItem(range, InternalItemKind.Float(value.bits))
            PValueKind.BOOLEAN -> addItem(range, InternalItemKind.Boolean(value.boolean))
            PValueKind.DATETIME -> addItem(range, InternalItemKind.DateTime(value.dateTime))
        }
    }

    fun buildArray(value: PValue, depth: Int, range: SpanRange): Int {
        checkDepth(depth)
        val itemIndex = reserveItem(range)
        val elements = ArrayList<Int>()
        for ((ordinal, element) in value.array.withIndex()) {
            val valueRange = if (element.span.end == 0) range else element.span
            val childIndex = buildValue(element, depth + 1, valueRange)
            val elementIndex = add(
                Entity(
                    span(element.span),
                    EntityKind.Element(ElementEntity(ordinal, childIndex)),
                ),
            )
            elements.add(elementIndex)
        }
        replaceItem(itemIndex, InternalItemKind.Array(elements))
        return itemIndex
    }

    fun buildAot(array: PAot, depth: Int, fallback: SpanRange): Int {
        checkDepth(depth)
        val range = if (array.hasSpan) array.span else fallback
        val itemIndex = reserveItem(range)
        val elements = ArrayList<Int>()
        for ((ordinal, table) in array.elements.withIndex()) {
            val childIndex = buildTable(table, false, depth + 1, table.span)
            val childSpan = entities[childIndex].span
            val elementIndex = add(
                Entity(
                    childSpan,
                    EntityKind.Element(ElementEntity(ordinal, childIndex)),
                ),
            )
            elements.add(elementIndex)
        }
        replaceItem(itemIndex, InternalItemKind.ArrayOfTables(elements))
        return itemIndex
    }

    fun build(): List<Entity> = entities
}

/** One immutable structural entity in the document (parser.rs:577-589). */
internal data class Entity(val span: Span, val kind: EntityKind)

internal sealed class EntityKind {
    data class Item(val item: ItemEntity) : EntityKind()
    data class Entry(val entry: EntryEntity) : EntityKind()
    data class Key(val key: KeyEntity) : EntityKind()
    data class Element(val element: ElementEntity) : EntityKind()
}

internal data class ItemEntity(val kind: InternalItemKind)

internal sealed class InternalItemKind {
    data class String(val value: kotlin.String) : InternalItemKind()
    data class Integer(val value: Long) : InternalItemKind()
    data class Float(val bits: Long) : InternalItemKind()
    data class Boolean(val value: kotlin.Boolean) : InternalItemKind()
    data class DateTime(val value: TomlDateTime) : InternalItemKind()
    data class Array(val elements: List<Int>) : InternalItemKind()
    data class InlineTable(val entries: List<Int>) : InternalItemKind()
    data class Table(val flavor: TableFlavor, val entries: List<Int>) : InternalItemKind()
    data class ArrayOfTables(val elements: List<Int>) : InternalItemKind()
}

internal enum class TableFlavor { ROOT, STANDARD, IMPLICIT, DOTTED }

internal data class EntryEntity(val ordinal: Int, val key: Int, val item: Int)

internal data class KeyEntity(val name: String)

internal data class ElementEntity(val ordinal: Int, val item: Int)

/** The Rust TomlItem::node_ref role used for item handles (lib.rs:237-239). */
internal fun itemNodeRef(authority: DocumentAuthority, index: Int): consema.document.NodeRef =
    authority.nodeRef(index.toLong(), NodeRole.TomlItem)
