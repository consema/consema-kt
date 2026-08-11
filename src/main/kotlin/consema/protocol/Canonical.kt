// The canonical tagged JSON transport `core.portable-value-json@1`.
//
// Data authority: RFC 0015 §3.2 (docs/rfcs/0015-cli-machine-protocol-and-
// batch-apply-v1.md) and RFC 0016 §4.2; the byte-exact reference is the
// Rust transport (crates/consema-protocol/src/value_transport.rs), pinned
// by the shared conformance vectors (protocol-v1.json). The numberToken /
// unicodeEscape rules are transcribed from the Rust parser
// (value_transport.rs:26-75).
//
// The decoder is a strict JSON parser (no comments, no trailing commas,
// duplicate members rejected, canonical string/number forms only, followed
// by a byte-exact canonicality re-encode check). The encoder emits the
// exact canonical bytes: no whitespace, minimal string escapes, integer
// values as decimal strings, byte hex lowercase.
//
// The tagged representation covers the closed fifteen-kind model with the
// exact kind spellings of the language-neutral registry (Null, Boolean,
// Integer, Decimal, BinaryFloat32, BinaryFloat64, String, Bytes, Date,
// Time, LocalDateTime, OffsetDateTime, Sequence, Object, EntryMapping;
// value_transport.rs:175-348).
//
// Kotlin-idiomatic design: the parse tree is a small sealed node hierarchy;
// parse/encode state lives in classes; failures are [ProtocolException].

package consema.protocol

import consema.core.PvArray
import consema.core.PvBinaryFloat32
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvDate
import consema.core.PvDecimal
import consema.core.PvEntryMapping
import consema.core.PvInteger
import consema.core.PvLocalDateTime
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvOffsetDateTime
import consema.core.PvString
import consema.core.PvTime
import consema.core.PortableValue
import java.math.BigInteger

/** The canonical tagged JSON transport schema. */
const val PORTABLE_VALUE_JSON_SCHEMA = "core.portable-value-json@1"

/** One strict-JSON tree node kind. */
private enum class JsonNodeKind { NULL, BOOL, STRING, NUMBER, ARRAY, OBJECT }

/** One ordered object member. */
private class JsonField(val key: String, val value: JsonNode)

/** A strict-JSON parse tree. Numbers keep their raw token text; strings
 * hold decoded text. */
private class JsonNode(
    val kind: JsonNodeKind,
    /** String text or Number raw token. */
    val text: String = "",
    /** Bool value. */
    val truth: Boolean = false,
    /** Array items. */
    val items: MutableList<JsonNode> = ArrayList(),
    /** Object members in source order. */
    val fields: MutableList<JsonField> = ArrayList(),
)

/** The strict JSON parser state. */
private class Parser(private val bytes: ByteArray, private val limits: ProtocolLimits) {
    private var pos = 0
    private var nodes = 0

    /** Strictly parses one complete JSON document. Any syntax error,
     * duplicate member, or trailing content yields KindInvalidJson;
     * parse-level resource bounds use the mapped limits of the reference
     * path (max_depth*4+8, max_nodes*16+32). */
    fun parseDocument(): JsonNode {
        if (bytes.size > limits.maxBytes) {
            throw resource("$", "transport bytes")
        }
        val node = value(0, "$")
        skipWhitespace()
        if (pos != bytes.size) {
            throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "trailing content")
        }
        return node
    }

    private fun skipWhitespace() {
        while (pos < bytes.size) {
            when (bytes[pos]) {
                ' '.code.toByte(), '\t'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte() -> pos++
                else -> return
            }
        }
    }

    private fun value(depth: Int, path: String): JsonNode {
        if (depth > limits.maxDepth * 4 + 8) {
            throw resource(path, "nesting depth")
        }
        nodes++
        if (nodes > limits.maxNodes * 16 + 32) {
            throw resource(path, "value nodes")
        }
        skipWhitespace()
        if (pos >= bytes.size) {
            throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "expected a value")
        }
        return when (bytes[pos].toInt().toChar()) {
            '{' -> objectValue(depth, path)
            '[' -> arrayValue(depth, path)
            '"' -> {
                val text = stringToken(path)
                if (text.toByteArray(Charsets.UTF_8).size > limits.maxBlobBytes) {
                    throw resource(path, "string bytes")
                }
                JsonNode(JsonNodeKind.STRING, text = text)
            }
            't' -> {
                if (literal("true")) {
                    JsonNode(JsonNodeKind.BOOL, truth = true)
                } else {
                    throw invalidJson()
                }
            }
            'f' -> {
                if (literal("false")) {
                    JsonNode(JsonNodeKind.BOOL, truth = false)
                } else {
                    throw invalidJson()
                }
            }
            'n' -> {
                if (literal("null")) {
                    JsonNode(JsonNodeKind.NULL)
                } else {
                    throw invalidJson()
                }
            }
            else -> {
                val character = bytes[pos].toInt().toChar()
                if (character == '-' || character.isDigit()) {
                    JsonNode(JsonNodeKind.NUMBER, text = numberToken())
                } else {
                    throw invalidJson()
                }
            }
        }
    }

    private fun invalidJson(): ProtocolException =
        protocolError(ProtocolErrorKind.INVALID_JSON, "$", "unexpected character")

    /** Consumes one exact word and reports success. */
    private fun literal(word: String): Boolean {
        if (bytes.size - pos < word.length || String(bytes, pos, word.length, Charsets.UTF_8) != word) {
            return false
        }
        pos += word.length
        return true
    }

    /** Parses a JSON object, rejecting duplicate member names. */
    private fun objectValue(depth: Int, path: String): JsonNode {
        pos++ // '{'
        val node = JsonNode(JsonNodeKind.OBJECT)
        skipWhitespace()
        if (pos < bytes.size && bytes[pos].toInt().toChar() == '}') {
            pos++
            return node
        }
        val seen = HashSet<String>()
        while (true) {
            skipWhitespace()
            val key = stringToken(path)
            if (!seen.add(key)) {
                throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "duplicate member name")
            }
            if (key.toByteArray(Charsets.UTF_8).size > limits.maxBlobBytes) {
                throw resource(path, "string bytes")
            }
            skipWhitespace()
            if (pos >= bytes.size || bytes[pos].toInt().toChar() != ':') {
                throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "expected ':'")
            }
            pos++
            node.fields.add(JsonField(key, value(depth + 1, path)))
            skipWhitespace()
            if (pos >= bytes.size) {
                throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "unterminated object")
            }
            when (bytes[pos].toInt().toChar()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return node
                }
                else -> throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "expected ',' or '}'")
            }
        }
    }

    /** Parses a JSON array. */
    private fun arrayValue(depth: Int, path: String): JsonNode {
        pos++ // '['
        val node = JsonNode(JsonNodeKind.ARRAY)
        skipWhitespace()
        if (pos < bytes.size && bytes[pos].toInt().toChar() == ']') {
            pos++
            return node
        }
        while (true) {
            node.items.add(value(depth + 1, path))
            skipWhitespace()
            if (pos >= bytes.size) {
                throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "unterminated array")
            }
            when (bytes[pos].toInt().toChar()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return node
                }
                else -> throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "expected ',' or ']'")
            }
        }
    }

    /** Parses one JSON string token (with escapes and surrogate pairs) and
     * returns its decoded text. */
    private fun stringToken(path: String): String {
        pos++ // opening quote
        val builder = StringBuilder()
        while (true) {
            if (pos >= bytes.size) {
                throw protocolError(ProtocolErrorKind.INVALID_JSON, path, "unterminated string")
            }
            val octet = bytes[pos]
            when {
                octet == '"'.code.toByte() -> {
                    pos++
                    return builder.toString()
                }
                octet == '\\'.code.toByte() -> {
                    pos++
                    if (pos >= bytes.size) {
                        throw protocolError(ProtocolErrorKind.INVALID_JSON, path, "unterminated escape")
                    }
                    when (bytes[pos].toInt().toChar()) {
                        '"', '\\', '/' -> {
                            builder.append(bytes[pos].toInt().toChar())
                            pos++
                        }
                        'b' -> {
                            builder.append('\b')
                            pos++
                        }
                        'f' -> {
                            builder.append('\u000C')
                            pos++
                        }
                        'n' -> {
                            builder.append('\n')
                            pos++
                        }
                        'r' -> {
                            builder.append('\r')
                            pos++
                        }
                        't' -> {
                            builder.append('\t')
                            pos++
                        }
                        'u' -> {
                            pos++ // advance past the 'u' to the first hex digit
                            builder.append(unicodeEscape(path))
                        }
                        else -> throw protocolError(ProtocolErrorKind.INVALID_JSON, path, "invalid escape")
                    }
                }
                (octet.toInt() and 0xff) < 0x20 -> throw protocolError(ProtocolErrorKind.INVALID_JSON, path, "raw control character")
                else -> {
                    // Copy one full UTF-8 sequence; a partial sequence is
                    // invalid JSON text and must not be silently replaced.
                    val start = pos
                    pos++
                    while (pos < bytes.size && bytes[pos].toInt() and 0xc0 == 0x80) {
                        pos++
                    }
                    val text = String(bytes, start, pos - start, Charsets.UTF_8)
                    if (!consema.core.isValidUtf8(bytes.copyOfRange(start, pos))) {
                        throw protocolError(ProtocolErrorKind.INVALID_JSON, path, "invalid UTF-8")
                    }
                    builder.append(text)
                }
            }
        }
    }

    /** Decodes one \uXXXX escape, combining surrogate pairs. */
    private fun unicodeEscape(path: String): Char {
        val value = hexQuad(path)
        if (value in 0xd800..0xdbff) {
            // High surrogate: require a following \uDC00-\uDFFF.
            if (pos + 1 < bytes.size && bytes[pos] == '\\'.code.toByte() && bytes[pos + 1] == 'u'.code.toByte()) {
                pos += 2
                val low = hexQuad(path)
                if (low !in 0xdc00..0xdfff) {
                    throw protocolError(ProtocolErrorKind.INVALID_JSON, path, "invalid surrogate pair")
                }
                val codePoint = 0x10000 + ((value - 0xd800) shl 10) + (low - 0xdc00)
                return codePoint.toChar()
            }
            throw protocolError(ProtocolErrorKind.INVALID_JSON, path, "lone high surrogate")
        }
        if (value in 0xdc00..0xdfff) {
            throw protocolError(ProtocolErrorKind.INVALID_JSON, path, "lone low surrogate")
        }
        return value.toChar()
    }

    /** Parses exactly four hexadecimal digits. */
    private fun hexQuad(path: String): Int {
        if (pos + 4 > bytes.size) {
            throw protocolError(ProtocolErrorKind.INVALID_JSON, path, "truncated \\u escape")
        }
        var value = 0
        for (index in 0 until 4) {
            val digit = bytes[pos + index].toInt().toChar()
            value = value shl 4
            value = when {
                digit.isDigit() -> value or (digit - '0')
                digit in 'a'..'f' -> value or (digit - 'a' + 10)
                digit in 'A'..'F' -> value or (digit - 'A' + 10)
                else -> throw protocolError(ProtocolErrorKind.INVALID_JSON, path, "invalid \\u escape")
            }
        }
        pos += 4
        return value
    }

    /** Parses one strict JSON number token and returns its raw text. */
    private fun numberToken(): String {
        val start = pos
        if (bytes[pos].toInt().toChar() == '-') {
            pos++
        }
        // Integer part.
        if (pos >= bytes.size) {
            throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "invalid number")
        }
        if (bytes[pos] == '0'.code.toByte()) {
            pos++
        } else if (bytes[pos].toInt().toChar() in '1'..'9') {
            while (pos < bytes.size && bytes[pos].toInt().toChar().isDigit()) {
                pos++
            }
        } else {
            throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "invalid number")
        }
        // Fraction part.
        if (pos < bytes.size && bytes[pos] == '.'.code.toByte()) {
            pos++
            if (pos >= bytes.size || !bytes[pos].toInt().toChar().isDigit()) {
                throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "invalid number fraction")
            }
            while (pos < bytes.size && bytes[pos].toInt().toChar().isDigit()) {
                pos++
            }
        }
        // Exponent part.
        if (pos < bytes.size && (bytes[pos] == 'e'.code.toByte() || bytes[pos] == 'E'.code.toByte())) {
            pos++
            if (pos < bytes.size && (bytes[pos] == '+'.code.toByte() || bytes[pos] == '-'.code.toByte())) {
                pos++
            }
            if (pos >= bytes.size || !bytes[pos].toInt().toChar().isDigit()) {
                throw protocolError(ProtocolErrorKind.INVALID_JSON, "$", "invalid number exponent")
            }
            while (pos < bytes.size && bytes[pos].toInt().toChar().isDigit()) {
                pos++
            }
        }
        return String(bytes, start, pos - start, Charsets.UTF_8)
    }
}

/** Tracks the resource counts of a value-tree decode. */
private class DecodeState(val limits: ProtocolLimits) {
    private var nodes = 0

    fun node(depth: Int, path: String) {
        if (depth > limits.maxDepth) {
            throw resource(path, "nesting depth")
        }
        nodes++
        if (nodes > limits.maxNodes) {
            throw resource(path, "value nodes")
        }
    }

    fun container(count: Int, path: String) {
        if (count > limits.maxContainerEntries) {
            throw resource(path, "container entries")
        }
    }
}

/**
 * Encodes a PortableValue as canonical `core.portable-value-json@1` bytes,
 * byte-identical to the Rust encoder
 * (crates/consema-protocol/src/value_transport.rs:12-23).
 */
fun encodeJson(value: PortableValue, limits: ProtocolLimits): ByteArray {
    val encoder = JsonEncoder(limits)
    encoder.push("{\"schema\":\"$PORTABLE_VALUE_JSON_SCHEMA\",\"value\":")
    encoder.value(valueToNode(value), 0, "$.value")
    encoder.push("}")
    return encoder.output.toString().toByteArray(Charsets.UTF_8)
}

/**
 * Strictly decodes canonical `core.portable-value-json@1` bytes and returns
 * the transported PortableValue. The record decode runs before the
 * canonicality re-encode check, matching the reference ordering (a
 * resource-limit or field error is reported before a non-canonical form).
 */
fun decodeJson(bytes: ByteArray, limits: ProtocolLimits): PortableValue {
    val node = Parser(bytes, limits).parseDocument()
    val fields = jsonObjectExact(node, listOf("schema", "value"), "$")
    val schema = jsonStringOf(fields[0], "$.schema")
    if (schema != PORTABLE_VALUE_JSON_SCHEMA) {
        throw protocolError(ProtocolErrorKind.SCHEMA_MISMATCH, "$.schema", "unexpected transport schema")
    }
    val state = DecodeState(limits)
    val value = nodeToValue(fields[1], 0, "$.value", state)
    ensureCanonical(node, bytes, limits)
    return value
}

/** Re-encodes the parsed document's value and requires byte equality with
 * the input (the reference re-encode canonicality check,
 * value_transport.rs:66-73). Re-encoding works on the parse tree, which
 * preserves field order and decoded text; any valid-but-non-canonical form
 * (whitespace, alternate escapes, reordered fields, non-minimal numbers)
 * therefore differs. */
private fun ensureCanonical(node: JsonNode, input: ByteArray, limits: ProtocolLimits) {
    val valueNode = node.fields.firstOrNull { it.key == "value" }?.value ?: return
    val encoder = JsonEncoder(limits)
    encoder.push("{\"schema\":\"$PORTABLE_VALUE_JSON_SCHEMA\",\"value\":")
    encoder.value(valueNode, 0, "$.value")
    encoder.push("}")
    if (encoder.output.toString() != String(input, Charsets.UTF_8)) {
        throw protocolError(
            ProtocolErrorKind.NON_CANONICAL_JSON,
            "$",
            "input is valid but not the canonical JSON byte form",
        )
    }
}

/** The canonical encoder with protocol resource checks. */
private class JsonEncoder(private val limits: ProtocolLimits) {
    val output = StringBuilder()
    private var nodes = 0

    fun push(text: String) {
        if (output.length + text.length > limits.maxBytes) {
            throw resource("$", "transport bytes")
        }
        output.append(text)
    }

    private fun node(depth: Int, path: String) {
        if (depth > limits.maxDepth) {
            throw resource(path, "nesting depth")
        }
        nodes++
        if (nodes > limits.maxNodes) {
            throw resource(path, "value nodes")
        }
    }

    private fun container(count: Int, path: String) {
        if (count > limits.maxContainerEntries) {
            throw resource(path, "container entries")
        }
    }

    private fun quoted(value: String, path: String) {
        if (value.toByteArray(Charsets.UTF_8).size > limits.maxBlobBytes) {
            throw resource(path, "string bytes")
        }
        push("\"")
        for (character in value) {
            val escaped = when (character) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\b' -> "\\b"
                '\t' -> "\\t"
                '\n' -> "\\n"
                '\u000C' -> "\\f"
                '\r' -> "\\r"
                else -> {
                    if (character.code < 0x20) {
                        "\\u%04x".format(character.code)
                    } else {
                        push(character.toString())
                        continue
                    }
                }
            }
            push(escaped)
        }
        push("\"")
    }

    private fun integer(value: BigInteger, path: String) {
        if (consema.core.minimalMagnitude(value).size > limits.maxIntegerBytes) {
            throw resource(path, "integer magnitude")
        }
        quoted(value.toString(), path)
    }

    /** Writes one tagged value. The tagged form is a JSON object whose
     * first member is "type"; integers and decimals are normalized to their
     * canonical decimal spellings, strings re-escape from decoded text, and
     * byte hex is lowercased (value_transport.rs:175-348). */
    fun value(node: JsonNode, depth: Int, path: String) {
        node(depth, path)
        if (node.kind != JsonNodeKind.OBJECT || node.fields.isEmpty() || node.fields[0].key != "type") {
            throw invalid(path, "unrepresentable value")
        }
        val kindNode = node.fields[0].value
        if (kindNode.kind != JsonNodeKind.STRING) {
            throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.type", "expected String")
        }
        val kind = kindNode.text
        fun member(name: String): JsonNode? = node.fields.firstOrNull { it.key == name }?.value
        when (kind) {
            "Null" -> push("{\"type\":\"Null\"}")
            "Boolean" -> {
                val valueNode = member("value")
                if (valueNode == null || valueNode.kind != JsonNodeKind.BOOL) {
                    throw invalid(path, "unrepresentable value")
                }
                push(if (valueNode.truth) "{\"type\":\"Boolean\",\"value\":true}" else "{\"type\":\"Boolean\",\"value\":false}")
            }
            "String" -> {
                val text = jsonStringOf(member("value"), "$path.value")
                push("{\"type\":\"String\",\"value\":")
                quoted(text, path)
                push("}")
            }
            "Integer" -> {
                val text = jsonStringOf(member("value"), "$path.value")
                val integer = text.toBigIntegerOrNull()
                    ?: throw invalid("$path.value", "invalid integer")
                push("{\"type\":\"Integer\",\"value\":")
                integer(integer, path)
                push("}")
            }
            "Decimal" -> {
                val coefficientText = jsonStringOf(member("coefficient"), "$path.coefficient")
                val exponentText = jsonStringOf(member("exponent"), "$path.exponent")
                val coefficient = coefficientText.toBigIntegerOrNull()
                    ?: throw invalid("$path.coefficient", "invalid integer")
                val exponent = exponentText.toBigIntegerOrNull()
                    ?: throw invalid("$path.exponent", "invalid integer")
                push("{\"type\":\"Decimal\",\"coefficient\":")
                integer(coefficient, path)
                push(",\"exponent\":")
                integer(exponent, path)
                push("}")
            }
            "BinaryFloat32" -> {
                val bits = jsonStringOf(member("bits"), "$path.bits")
                // The canonical form is eight lowercase hex digits; any
                // other spelling fails the re-encode canonicality check.
                push("{\"type\":\"BinaryFloat32\",\"bits\":")
                quoted(bits.lowercase(), path)
                push("}")
            }
            "BinaryFloat64" -> {
                val bits = jsonStringOf(member("bits"), "$path.bits")
                push("{\"type\":\"BinaryFloat64\",\"bits\":")
                quoted(bits.lowercase(), path)
                push("}")
            }
            "Bytes" -> {
                val hex = jsonStringOf(member("hex"), "$path.hex")
                push("{\"type\":\"Bytes\",\"hex\":")
                quoted(hex.lowercase(), path)
                push("}")
            }
            "Date" -> {
                val yearText = jsonStringOf(member("year"), "$path.year")
                val year = yearText.toBigIntegerOrNull()
                    ?: throw invalid("$path.year", "invalid integer")
                val month = jsonParseU8(member("month") ?: throw invalid("$path.month", "unrepresentable value"), "$path.month", limits)
                val day = jsonParseU8(member("day") ?: throw invalid("$path.day", "unrepresentable value"), "$path.day", limits)
                push("{\"type\":\"Date\",\"year\":")
                integer(year, path)
                push(",\"month\":")
                quoted(month.toString(), path)
                push(",\"day\":")
                quoted(day.toString(), path)
                push("}")
            }
            "Time" -> {
                val hour = jsonParseU8(member("hour") ?: throw invalid("$path.hour", "unrepresentable value"), "$path.hour", limits)
                val minute = jsonParseU8(member("minute") ?: throw invalid("$path.minute", "unrepresentable value"), "$path.minute", limits)
                val second = jsonParseU8(member("second") ?: throw invalid("$path.second", "unrepresentable value"), "$path.second", limits)
                val fraction = member("fraction")
                    ?: throw invalid("$path.fraction", "unrepresentable value")
                push("{\"type\":\"Time\",\"hour\":")
                quoted(hour.toString(), path)
                push(",\"minute\":")
                quoted(minute.toString(), path)
                push(",\"second\":")
                quoted(second.toString(), path)
                push(",\"fraction\":")
                value(fraction, depth + 1, path)
                push("}")
            }
            "LocalDateTime" -> {
                val date = member("date")
                val time = member("time")
                if (date == null || time == null) {
                    throw invalid(path, "unrepresentable value")
                }
                push("{\"type\":\"LocalDateTime\",\"date\":")
                value(date, depth + 1, path)
                push(",\"time\":")
                value(time, depth + 1, path)
                push("}")
            }
            "OffsetDateTime" -> {
                val local = member("local")
                    ?: throw invalid(path, "unrepresentable value")
                val offset = jsonParseI32(member("offset_seconds") ?: throw invalid("$path.offset_seconds", "unrepresentable value"), "$path.offset_seconds", limits)
                push("{\"type\":\"OffsetDateTime\",\"local\":")
                value(local, depth + 1, path)
                push(",\"offset_seconds\":")
                quoted(offset.toString(), path)
                push("}")
            }
            "Sequence" -> {
                val items = member("items")
                if (items == null || items.kind != JsonNodeKind.ARRAY) {
                    throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.items", "expected JSON array")
                }
                container(items.items.size, path)
                push("{\"type\":\"Sequence\",\"items\":[")
                for ((index, item) in items.items.withIndex()) {
                    if (index != 0) {
                        push(",")
                    }
                    value(item, depth + 1, "$path.items[$index]")
                }
                push("]}")
            }
            "Object", "EntryMapping" -> {
                val entries = member("entries")
                if (entries == null || entries.kind != JsonNodeKind.ARRAY) {
                    throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.entries", "expected JSON array")
                }
                container(entries.items.size, path)
                push("{\"type\":\"$kind\",\"entries\":[")
                for ((index, item) in entries.items.withIndex()) {
                    if (index != 0) {
                        push(",")
                    }
                    val entryPath = "$path.entries[$index]"
                    val entryFields = jsonObjectExact(item, listOf("key", "value"), entryPath)
                    push("{\"key\":")
                    if (kind == "Object") {
                        val key = jsonStringOf(entryFields[0], "$entryPath.key")
                        quoted(key, "$entryPath.key")
                    } else {
                        value(entryFields[0], depth + 1, "$entryPath.key")
                    }
                    push(",\"value\":")
                    value(entryFields[1], depth + 1, "$entryPath.value")
                    push("}")
                }
                push("]}")
            }
            else -> throw invalid("$path.type", "unknown value type")
        }
    }
}

/** Converts a core value into the tagged tree form. Every value becomes its
 * tagged representation (an object whose first member is "type"), the form
 * the canonical encoder emits. */
private fun valueToNode(value: PortableValue): JsonNode {
    fun tagged(type: String, vararg fields: Pair<String, JsonNode>): JsonNode {
        val node = JsonNode(JsonNodeKind.OBJECT)
        node.fields.add(JsonField("type", JsonNode(JsonNodeKind.STRING, text = type)))
        for ((key, valueNode) in fields) {
            node.fields.add(JsonField(key, valueNode))
        }
        return node
    }
    fun stringNode(text: String): JsonNode = JsonNode(JsonNodeKind.STRING, text = text)
    return when (value) {
        is PvNull -> tagged("Null")
        is PvBoolean -> tagged("Boolean", "value" to JsonNode(JsonNodeKind.BOOL, truth = value.value))
        is PvString -> tagged("String", "value" to stringNode(value.value))
        is PvInteger -> tagged("Integer", "value" to stringNode(value.value.toString()))
        is PvDecimal -> tagged(
            "Decimal",
            "coefficient" to stringNode(value.coefficient.toString()),
            "exponent" to stringNode(value.exponent.toString()),
        )
        is PvBinaryFloat32 -> tagged("BinaryFloat32", "bits" to stringNode("%08x".format(value.bits)))
        is PvBinaryFloat64 -> tagged("BinaryFloat64", "bits" to stringNode("%016x".format(value.bits)))
        is PvBytes -> tagged("Bytes", "hex" to stringNode(value.content().joinToString("") { "%02x".format(it) }))
        is PvDate -> tagged(
            "Date",
            "year" to stringNode(value.year.toString()),
            "month" to stringNode(value.month.toString()),
            "day" to stringNode(value.day.toString()),
        )
        is PvTime -> tagged(
            "Time",
            "hour" to stringNode(value.hour.toString()),
            "minute" to stringNode(value.minute.toString()),
            "second" to stringNode(value.second.toString()),
            "fraction" to valueToNode(value.fractionalSecond),
        )
        is PvLocalDateTime -> tagged(
            "LocalDateTime",
            "date" to valueToNode(value.date),
            "time" to valueToNode(value.time),
        )
        is PvOffsetDateTime -> tagged(
            "OffsetDateTime",
            "local" to valueToNode(value.local),
            "offset_seconds" to stringNode(value.offsetSeconds.toString()),
        )
        is PvArray -> {
            val items = JsonNode(JsonNodeKind.ARRAY)
            for (item in value.items()) {
                items.items.add(valueToNode(item))
            }
            tagged("Sequence", "items" to items)
        }
        is PvObject -> {
            val entries = JsonNode(JsonNodeKind.ARRAY)
            for (entry in value.entries()) {
                val entryNode = JsonNode(JsonNodeKind.OBJECT)
                entryNode.fields.add(JsonField("key", stringNode(entry.key)))
                entryNode.fields.add(JsonField("value", valueToNode(entry.value)))
                entries.items.add(entryNode)
            }
            tagged("Object", "entries" to entries)
        }
        is PvEntryMapping -> {
            val entries = JsonNode(JsonNodeKind.ARRAY)
            for (entry in value.entries()) {
                val entryNode = JsonNode(JsonNodeKind.OBJECT)
                entryNode.fields.add(JsonField("key", valueToNode(entry.key)))
                entryNode.fields.add(JsonField("value", valueToNode(entry.value)))
                entries.items.add(entryNode)
            }
            tagged("EntryMapping", "entries" to entries)
        }
    }
}

/** Converts a tagged tree node into a core value, applying the protocol
 * limits and covering all fifteen kinds (the reference decode_value,
 * value_transport.rs:392-617). */
private fun nodeToValue(node: JsonNode, depth: Int, path: String, state: DecodeState): PortableValue {
    state.node(depth, path)
    if (node.kind != JsonNodeKind.OBJECT) {
        throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected JSON object")
    }
    if (node.fields.isEmpty()) {
        throw protocolError(ProtocolErrorKind.MISSING_FIELD, "$path.type", "missing value type")
    }
    if (node.fields[0].key != "type") {
        throw protocolError(ProtocolErrorKind.SCHEMA_MISMATCH, path, "type must be the first field")
    }
    val kind = jsonStringOf(node.fields[0].value, "$path.type")
    fun fieldsExact(expected: List<String>) {
        jsonObjectFieldsExact(node, expected, path)
    }
    fun field(index: Int): JsonNode = node.fields[index].value
    return when (kind) {
        "Null" -> {
            fieldsExact(listOf("type"))
            PvNull
        }
        "Boolean" -> {
            fieldsExact(listOf("type", "value"))
            PvBoolean(jsonBooleanOf(field(1), "$path.value"))
        }
        "Integer" -> {
            fieldsExact(listOf("type", "value"))
            PvInteger(jsonParseInteger(field(1), "$path.value", state.limits))
        }
        "Decimal" -> {
            fieldsExact(listOf("type", "coefficient", "exponent"))
            PvDecimal.of(
                jsonParseInteger(field(1), "$path.coefficient", state.limits),
                jsonParseInteger(field(2), "$path.exponent", state.limits),
            )
        }
        "BinaryFloat32" -> {
            fieldsExact(listOf("type", "bits"))
            PvBinaryFloat32(jsonParseHexUint32(field(1), "$path.bits"))
        }
        "BinaryFloat64" -> {
            fieldsExact(listOf("type", "bits"))
            PvBinaryFloat64(jsonParseHexUint64(field(1), "$path.bits"))
        }
        "Bytes" -> {
            fieldsExact(listOf("type", "hex"))
            val hexText = jsonStringOf(field(1), "$path.hex")
            if (hexText.length % 2 != 0) {
                throw invalid("$path.hex", "byte hex length must be even")
            }
            if (hexText.length / 2 > state.limits.maxBlobBytes) {
                throw resource("$path.hex", "bytes")
            }
            PvBytes.of(parseHexBytes(hexText, "$path.hex"))
        }
        "Date" -> {
            fieldsExact(listOf("type", "year", "month", "day"))
            val year = jsonParseInteger(field(1), "$path.year", state.limits)
            val month = jsonParseU8(field(2), "$path.month", state.limits)
            val day = jsonParseU8(field(3), "$path.day", state.limits)
            try {
                PvDate.of(year, month, day)
            } catch (e: consema.core.InvalidTemporalException) {
                throw invalid(path, "invalid date")
            }
        }
        "Time" -> {
            fieldsExact(listOf("type", "hour", "minute", "second", "fraction"))
            val hour = jsonParseU8(field(1), "$path.hour", state.limits)
            val minute = jsonParseU8(field(2), "$path.minute", state.limits)
            val second = jsonParseU8(field(3), "$path.second", state.limits)
            val fractionValue = nodeToValue(field(4), depth + 1, "$path.fraction", state)
            val fraction = fractionValue as? PvDecimal
                ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.fraction", "expected Decimal")
            try {
                PvTime.of(hour, minute, second, fraction)
            } catch (e: consema.core.InvalidTemporalException) {
                throw invalid(path, "invalid time")
            }
        }
        "LocalDateTime" -> {
            fieldsExact(listOf("type", "date", "time"))
            val dateValue = nodeToValue(field(1), depth + 1, "$path.date", state)
            val date = dateValue as? PvDate
                ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.date", "expected Date")
            val timeValue = nodeToValue(field(2), depth + 1, "$path.time", state)
            val time = timeValue as? PvTime
                ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.time", "expected Time")
            PvLocalDateTime(date, time)
        }
        "OffsetDateTime" -> {
            fieldsExact(listOf("type", "local", "offset_seconds"))
            val localValue = nodeToValue(field(1), depth + 1, "$path.local", state)
            val local = localValue as? PvLocalDateTime
                ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.local", "expected LocalDateTime")
            val offset = jsonParseI32(field(2), "$path.offset_seconds", state.limits)
            try {
                PvOffsetDateTime.of(local, offset)
            } catch (e: consema.core.InvalidTemporalException) {
                throw invalid(path, "invalid offset date-time")
            }
        }
        "String" -> {
            fieldsExact(listOf("type", "value"))
            PvString(jsonStringOf(field(1), "$path.value"))
        }
        "Sequence" -> {
            fieldsExact(listOf("type", "items"))
            val itemsNode = field(1)
            if (itemsNode.kind != JsonNodeKind.ARRAY) {
                throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.items", "expected JSON array")
            }
            state.container(itemsNode.items.size, path)
            val items = ArrayList<PortableValue>(itemsNode.items.size)
            for ((index, item) in itemsNode.items.withIndex()) {
                items.add(nodeToValue(item, depth + 1, "$path.items[$index]", state))
            }
            PvArray(items)
        }
        "Object" -> {
            fieldsExact(listOf("type", "entries"))
            val entriesNode = field(1)
            if (entriesNode.kind != JsonNodeKind.ARRAY) {
                throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.entries", "expected JSON array")
            }
            state.container(entriesNode.items.size, path)
            val builder = consema.core.ObjectBuilder()
            for ((index, item) in entriesNode.items.withIndex()) {
                val entryPath = "$path.entries[$index]"
                val entryFields = jsonObjectExact(item, listOf("key", "value"), entryPath)
                val key = jsonStringOf(entryFields[0], "$entryPath.key")
                val entryValue = nodeToValue(entryFields[1], depth + 1, "$entryPath.value", state)
                try {
                    builder.insert(key, entryValue)
                } catch (e: consema.core.DuplicateKeyException) {
                    throw invalid(entryPath, "duplicate object key")
                }
            }
            builder.build()
        }
        "EntryMapping" -> {
            fieldsExact(listOf("type", "entries"))
            val entriesNode = field(1)
            if (entriesNode.kind != JsonNodeKind.ARRAY) {
                throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.entries", "expected JSON array")
            }
            state.container(entriesNode.items.size, path)
            val builder = consema.core.EntryMappingBuilder()
            for ((index, item) in entriesNode.items.withIndex()) {
                val entryPath = "$path.entries[$index]"
                val entryFields = jsonObjectExact(item, listOf("key", "value"), entryPath)
                val key = nodeToValue(entryFields[0], depth + 1, "$entryPath.key", state)
                val entryValue = nodeToValue(entryFields[1], depth + 1, "$entryPath.value", state)
                builder.push(key, entryValue)
            }
            builder.build()
        }
        else -> throw invalid("$path.type", "unknown value type")
    }
}

/** Returns the fields of an object node in source order, validating the
 * declared name set (if any) and canonical order. */
private fun jsonObjectExact(node: JsonNode, expected: List<String>, path: String): List<JsonNode> {
    if (node.kind != JsonNodeKind.OBJECT) {
        throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected JSON object")
    }
    val names = node.fields.map { it.key }
    val values = node.fields.map { it.value }
    for (name in names) {
        if (name !in expected) {
            throw protocolError(
                ProtocolErrorKind.UNKNOWN_FIELD,
                "$path.$name",
                "field is not declared by the fixed schema",
            )
        }
    }
    for (name in expected) {
        if (name !in names) {
            throw protocolError(ProtocolErrorKind.MISSING_FIELD, "$path.$name", "required field is absent")
        }
    }
    if (names != expected) {
        throw protocolError(ProtocolErrorKind.SCHEMA_MISMATCH, path, "fields are duplicated or not in canonical order")
    }
    return values
}

/** Validates the exact member set of a tagged value object. */
private fun jsonObjectFieldsExact(node: JsonNode, expected: List<String>, path: String) {
    if (node.kind != JsonNodeKind.OBJECT) {
        throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected JSON object")
    }
    val names = node.fields.map { it.key }
    for (name in names) {
        if (name !in expected) {
            throw protocolError(
                ProtocolErrorKind.UNKNOWN_FIELD,
                "$path.$name",
                "field is not declared by the fixed schema",
            )
        }
    }
    for (name in expected) {
        if (name !in names) {
            throw protocolError(ProtocolErrorKind.MISSING_FIELD, "$path.$name", "required field is absent")
        }
    }
    if (names != expected) {
        throw protocolError(ProtocolErrorKind.SCHEMA_MISMATCH, path, "fields are duplicated or not in canonical order")
    }
}

/** Reads a string member value. */
private fun jsonStringOf(node: JsonNode?, path: String): String {
    if (node == null || node.kind != JsonNodeKind.STRING) {
        throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected JSON string")
    }
    return node.text
}

/** Reads a boolean member value. */
private fun jsonBooleanOf(node: JsonNode, path: String): Boolean {
    if (node.kind != JsonNodeKind.BOOL) {
        throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected JSON boolean")
    }
    return node.truth
}

/** Parses a decimal string into a big integer with the protocol integer
 * limits (value_transport.rs:793-807). */
private fun jsonParseInteger(node: JsonNode, path: String, limits: ProtocolLimits): BigInteger {
    if (node.kind != JsonNodeKind.STRING) {
        throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected JSON string")
    }
    val maxDigits = limits.maxIntegerBytes * 3 + 2
    if (node.text.length > maxDigits) {
        throw resource(path, "integer decimal digits")
    }
    val integer = node.text.toBigIntegerOrNull()
        ?: throw invalid(path, "invalid integer")
    if (consema.core.minimalMagnitude(integer).size > limits.maxIntegerBytes) {
        throw resource(path, "integer magnitude")
    }
    return integer
}

/** Parses exactly eight hexadecimal digits (the reference parse_hex_u32,
 * value_transport.rs:823-828). */
private fun jsonParseHexUint32(node: JsonNode, path: String): Int {
    if (node.kind != JsonNodeKind.STRING) {
        throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected JSON string")
    }
    if (node.text.length != 8) {
        throw invalid(path, "binary32 bits require 8 hexadecimal digits")
    }
    return parseHexBytes(node.text, path).let { bytes ->
        ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
    }
}

/** Parses exactly sixteen hexadecimal digits (the reference parse_hex_u64,
 * value_transport.rs:830-835). */
private fun jsonParseHexUint64(node: JsonNode, path: String): Long {
    if (node.kind != JsonNodeKind.STRING) {
        throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected JSON string")
    }
    if (node.text.length != 16) {
        throw invalid(path, "binary64 bits require 16 hexadecimal digits")
    }
    var bits = 0L
    for (octet in parseHexBytes(node.text, path)) {
        bits = (bits shl 8) or (octet.toLong() and 0xff)
    }
    return bits
}

/** Decodes one hexadecimal digit, or -1. */
internal fun hexDigitValue(digit: Char): Int = when {
    digit.isDigit() -> digit - '0'
    digit in 'a'..'f' -> digit - 'a' + 10
    digit in 'A'..'F' -> digit - 'A' + 10
    else -> -1
}

/** Decodes an even-length lowercase/uppercase hex string. */
private fun parseHexBytes(text: String, path: String): ByteArray {
    val output = ByteArray(text.length / 2)
    for (index in output.indices) {
        val high = hexDigitValue(text[index * 2])
        val low = hexDigitValue(text[index * 2 + 1])
        if (high < 0 || low < 0) {
            throw invalid(path, "invalid byte hex")
        }
        output[index] = ((high shl 4) or low).toByte()
    }
    return output
}

/** Parses a decimal string into a UByte (the reference parse_u8,
 * value_transport.rs:809-814). */
private fun jsonParseU8(node: JsonNode, path: String, limits: ProtocolLimits): Int {
    val integer = jsonParseInteger(node, path, limits)
    if (integer.signum() < 0 || integer.bitLength() > 8) {
        throw invalid(path, "integer is outside u8")
    }
    return integer.toInt()
}

/** Parses a decimal string into an Int (the reference parse_i32,
 * value_transport.rs:816-821). */
private fun jsonParseI32(node: JsonNode, path: String, limits: ProtocolLimits): Int {
    val integer = jsonParseInteger(node, path, limits)
    if (integer.bitLength() > 31) {
        throw invalid(path, "integer is outside i32")
    }
    return integer.toInt()
}

/**
 * Encodes a PortableValue as canonical PVCE/1 under protocol limits
 * (crates/consema-protocol/src/value_transport.rs:78-89). PVCE/1 codec
 * failures map to the protocol registry: resource limits surface as
 * RESOURCE_LIMIT, everything else as INVALID_PVCE.
 */
fun encodePvce(value: PortableValue, limits: ProtocolLimits): ByteArray {
    return try {
        consema.core.encodePvceBounded(
            value,
            consema.core.EncodeLimits(
                maxBytes = limits.maxBytes,
                maxDepth = limits.maxDepth,
                maxNodes = limits.maxNodes,
                maxContainerEntries = limits.maxContainerEntries,
                maxIntegerBytes = limits.maxIntegerBytes,
                maxBlobBytes = limits.maxBlobBytes,
            ),
        )
    } catch (e: consema.core.PvceException) {
        throw mapPvceError(e)
    }
}

/** Strictly decodes canonical PVCE/1 under protocol limits
 * (crates/consema-protocol/src/value_transport.rs:92-112). Records outside
 * the closed fifteen-kind model (only the extended 0x7f record) fail as
 * INVALID_PVCE. */
fun decodePvce(bytes: ByteArray, limits: ProtocolLimits): PortableValue {
    return try {
        consema.core.decodePvce(
            bytes,
            consema.core.DecodeLimits(
                maxBytes = limits.maxBytes,
                maxDepth = limits.maxDepth,
                maxNodes = limits.maxNodes,
                maxContainerEntries = limits.maxContainerEntries,
                maxIntegerBytes = limits.maxIntegerBytes,
                maxBlobBytes = limits.maxBlobBytes,
            ),
        )
    } catch (e: consema.core.PvceException) {
        throw mapPvceError(e)
    }
}

/** Converts a core codec failure into the protocol error kind (the
 * reference decode_pvce mapping, value_transport.rs:104-111). */
private fun mapPvceError(e: consema.core.PvceException): ProtocolException =
    if (e.kind == consema.core.PvceErrorKind.RESOURCE_LIMIT) {
        resource("$", e.message ?: e.code)
    } else {
        protocolError(ProtocolErrorKind.INVALID_PVCE, "$", e.message ?: e.code)
    }
