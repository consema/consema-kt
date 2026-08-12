// Profile scalar resolution: frozen YAML 1.2 Core schema and YAML 1.1
// compatibility implicit forms, explicit standard-tag validation, and the
// canonical scalar spellings.
//
// Data authority:
//   - RFC 0007 §5-§6 (docs/rfcs/0007-yaml-family-profiles-and-safety-v1.md:
//     98-166) freezes the resolved tag set, the canonical content rules
//     (null "", booleans true/false, unbounded base-10 integers, normalized
//     decimal coefficient/exponent, .inf/-.inf/.nan, decoded strings), the
//     1.1 frozen implicit forms, and the exact UTC timestamp rule.
//   - conformance/vectors/yaml-v1.json cases profile.yaml12-scalars (lines
//     5-9) and profile.yaml11-scalars (lines 10-14) pin the per-profile
//     kind/canonical facts byte-for-byte.
//   - crates/consema-yaml/src/native.rs:746-1146 is the byte-arbitration
//     authority for every lexical rule (parse_null/bool/integer/float,
//     timestamp canonicalization, base64 validation); native.rs:565-716 pins
//     resolve_scalar / resolve_explicit / resolve_implicit. go/yaml/scalar.go
//     is a cross-reference only.
//
// Kotlin-idiomatic design: pure functions over String with java.math.BigInteger
// arbitrary precision; the canonical spellings are exact strings, never host
// number formatting.

package consema.yaml

import consema.core.PvDecimal
import java.math.BigInteger

// The standard resolved tag identifiers (native.rs:17-31).
internal const val TAG_NULL: String = "tag:yaml.org,2002:null"
internal const val TAG_BOOL: String = "tag:yaml.org,2002:bool"
internal const val TAG_INT: String = "tag:yaml.org,2002:int"
internal const val TAG_FLOAT: String = "tag:yaml.org,2002:float"
internal const val TAG_STR: String = "tag:yaml.org,2002:str"
internal const val TAG_SEQ: String = "tag:yaml.org,2002:seq"
internal const val TAG_MAP: String = "tag:yaml.org,2002:map"
internal const val TAG_TIMESTAMP: String = "tag:yaml.org,2002:timestamp"
internal const val TAG_BINARY: String = "tag:yaml.org,2002:binary"
internal const val TAG_MERGE: String = "tag:yaml.org,2002:merge"
internal const val TAG_OMAP: String = "tag:yaml.org,2002:omap"
internal const val TAG_PAIRS: String = "tag:yaml.org,2002:pairs"
internal const val TAG_SET: String = "tag:yaml.org,2002:set"
internal const val TAG_VALUE: String = "tag:yaml.org,2002:value"
internal const val TAG_YAML: String = "tag:yaml.org,2002:yaml"

/** One resolved scalar fact: resolved tag, decoded content, canonical
 * content, kind, and presentation style (native.rs:62-68). */
internal class NativeScalar(
    val decoded: String,
    val canonical: String,
    val kind: YamlScalarKind,
    val style: YamlScalarStyle,
)

/** Resolves one explicit-tag or implicit plain scalar (native.rs:565-716). */
internal fun resolveScalar(
    decoded: String,
    style: YamlScalarStyle,
    explicitTag: String?,
    profile: YamlProfile,
): Pair<String, NativeScalar> {
    if (explicitTag != null) {
        if (isStandardCollectionTag(explicitTag)) {
            throw nativeFailure("yaml.tag.kind-mismatch@1")
        }
        if (explicitTag == "!" || explicitTag == TAG_STR) {
            return TAG_STR to NativeScalar(decoded, decoded, YamlScalarKind.String, style)
        }
        if (explicitTag == TAG_NULL) {
            return resolveExplicit(decoded, style, TAG_NULL, YamlScalarKind.Null, profile)
        }
        if (explicitTag == TAG_BOOL) {
            return resolveExplicit(decoded, style, TAG_BOOL, YamlScalarKind.Boolean, profile)
        }
        if (explicitTag == TAG_INT) {
            return resolveExplicit(decoded, style, TAG_INT, YamlScalarKind.Integer, profile)
        }
        if (explicitTag == TAG_FLOAT) {
            return resolveExplicit(decoded, style, TAG_FLOAT, YamlScalarKind.Float, profile)
        }
        if (explicitTag == TAG_TIMESTAMP) {
            val canonical = parseTimestamp(decoded)
                ?: throw nativeFailure("yaml.scalar.invalid-explicit-tag@1")
            return explicitTag to NativeScalar(decoded, canonical, YamlScalarKind.Timestamp, style)
        }
        if (explicitTag == TAG_BINARY) {
            val canonical = canonicalBase64(decoded)
                ?: throw nativeFailure("yaml.scalar.invalid-explicit-tag@1")
            return explicitTag to NativeScalar(decoded, canonical, YamlScalarKind.Binary, style)
        }
        if (explicitTag == TAG_MERGE || explicitTag == TAG_VALUE || explicitTag == TAG_YAML) {
            return explicitTag to NativeScalar(decoded, decoded, YamlScalarKind.Tagged, style)
        }
        return explicitTag to NativeScalar(decoded, decoded, YamlScalarKind.Custom, style)
    }
    if (style != YamlScalarStyle.Plain) {
        return TAG_STR to NativeScalar(decoded, decoded, YamlScalarKind.String, style)
    }
    return resolveImplicit(decoded, style, profile)
}

/** Resolves one explicit standard scalar tag with grammar validation
 * (native.rs:655-673). */
private fun resolveExplicit(
    decoded: String,
    style: YamlScalarStyle,
    tag: String,
    kind: YamlScalarKind,
    profile: YamlProfile,
): Pair<String, NativeScalar> {
    val canonical: String = when (kind) {
        YamlScalarKind.Null -> if (parseNull(decoded)) "" else null
        YamlScalarKind.Boolean -> parseBool(decoded, profile)
        YamlScalarKind.Integer -> parseInteger(decoded, profile)
        YamlScalarKind.Float -> parseFloat(decoded, profile)
        else -> error("remaining scalar kinds are handled before explicit-tag formation")
    }
        ?: throw nativeFailure("yaml.scalar.invalid-explicit-tag@1")
    return tag to NativeScalar(decoded, canonical, kind, style)
}

/** Applies the frozen implicit scalar resolution (native.rs:675-716). */
private fun resolveImplicit(
    decoded: String,
    style: YamlScalarStyle,
    profile: YamlProfile,
): Pair<String, NativeScalar> {
    if (parseNull(decoded)) {
        return TAG_NULL to NativeScalar(decoded, "", YamlScalarKind.Null, style)
    }
    parseBool(decoded, profile)?.let {
        return TAG_BOOL to NativeScalar(decoded, it, YamlScalarKind.Boolean, style)
    }
    parseInteger(decoded, profile)?.let {
        return TAG_INT to NativeScalar(decoded, it, YamlScalarKind.Integer, style)
    }
    parseFloat(decoded, profile)?.let {
        return TAG_FLOAT to NativeScalar(decoded, it, YamlScalarKind.Float, style)
    }
    if (profile == YamlProfile.Yaml11CompatV1) {
        parseTimestamp(decoded)?.let {
            return TAG_TIMESTAMP to NativeScalar(decoded, it, YamlScalarKind.Timestamp, style)
        }
    }
    return TAG_STR to NativeScalar(decoded, decoded, YamlScalarKind.String, style)
}

/** Resolves an explicit collection tag against the expected default tag
 * (native.rs:541-563). */
internal fun resolveCollectionTag(
    explicit: String?,
    expected: String,
): String {
    if (explicit == null) {
        return expected
    }
    if (explicit == "!") {
        return expected
    }
    val validCollection = when (expected) {
        TAG_SEQ -> explicit == TAG_SEQ || explicit == TAG_OMAP || explicit == TAG_PAIRS
        TAG_MAP -> explicit == TAG_MAP || explicit == TAG_SET
        else -> false
    }
    if ((isStandardCollectionTag(explicit) && !validCollection) ||
        isStandardScalarTag(explicit)
    ) {
        throw nativeFailure("yaml.tag.kind-mismatch@1")
    }
    return explicit
}

internal fun isStandardCollectionTag(tag: String): Boolean =
    tag == TAG_SEQ || tag == TAG_MAP || tag == TAG_OMAP || tag == TAG_PAIRS || tag == TAG_SET

internal fun isStandardScalarTag(tag: String): Boolean =
    tag == TAG_NULL || tag == TAG_BOOL || tag == TAG_INT || tag == TAG_FLOAT ||
        tag == TAG_STR || tag == TAG_TIMESTAMP || tag == TAG_BINARY ||
        tag == TAG_MERGE || tag == TAG_VALUE || tag == TAG_YAML

internal fun isStandardGraphTag(tag: String): Boolean =
    isStandardCollectionTag(tag) || isStandardScalarTag(tag)

/** YAML null spellings (native.rs:746-748). */
internal fun parseNull(value: String): Boolean =
    value.isEmpty() || value == "~" || value == "null" || value == "Null" || value == "NULL"

/** YAML boolean resolution; the 1.1 y/n/yes/no/on/off forms are frozen
 * (native.rs:750-766). */
internal fun parseBool(value: String, profile: YamlProfile): String? {
    when (value) {
        "true", "True", "TRUE" -> return "true"
        "false", "False", "FALSE" -> return "false"
    }
    if (profile == YamlProfile.Yaml11CompatV1) {
        when (value) {
            "y", "Y", "yes", "Yes", "YES", "on", "On", "ON" -> return "true"
            "n", "N", "no", "No", "NO", "off", "Off", "OFF" -> return "false"
        }
    }
    return null
}

/** YAML integer resolution with the exact 1.2/1.1 rule order (native.rs:
 * 768-801). Underscores are 1.1-only and must sit between alphanumerics. */
internal fun parseInteger(value: String, profile: YamlProfile): String? {
    val signPair = splitSign(value) ?: return null
    val sign = signPair.first
    val unsigned = signPair.second
    val allowUnderscores = profile == YamlProfile.Yaml11CompatV1
    val cleaned = if (allowUnderscores) {
        validUnderscored(unsigned)?.replace("_", "") ?: return null
    } else if (unsigned.contains('_')) {
        return null
    } else {
        unsigned
    }
    var base = 10
    var digits = cleaned
    if (cleaned.startsWith("0b")) {
        base = 2
        digits = cleaned.substring(2)
    } else if (cleaned.startsWith("0o")) {
        if (profile == YamlProfile.Yaml11CompatV1) {
            return null
        }
        base = 8
        digits = cleaned.substring(2)
    } else if (cleaned.startsWith("0x")) {
        base = 16
        digits = cleaned.substring(2)
    } else if (profile == YamlProfile.Yaml11CompatV1 && cleaned.length > 1 &&
        cleaned.startsWith('0')
    ) {
        base = 8
        digits = cleaned
    } else if (profile == YamlProfile.Yaml11CompatV1 && cleaned.contains(':')) {
        return parseSexagesimalInteger(sign, cleaned)
    }
    val magnitude = parseBaseMagnitude(digits, base) ?: return null
    val value = if (sign < 0) magnitude.negate() else magnitude
    return value.toString()
}

/** YAML float resolution: frozen non-finite spellings, normalized finite
 * decimals, and the 1.1 sexagesimal form (native.rs:803-829). */
internal fun parseFloat(value: String, profile: YamlProfile): String? {
    when (value) {
        ".inf", ".Inf", ".INF", "+.inf", "+.Inf", "+.INF" -> return ".inf"
        "-.inf", "-.Inf", "-.INF" -> return "-.inf"
        ".nan", ".NaN", ".NAN" -> return ".nan"
    }
    val cleaned = if (profile == YamlProfile.Yaml11CompatV1) {
        validUnderscored(value)?.replace("_", "") ?: return null
    } else if (value.contains('_')) {
        return null
    } else {
        value
    }
    if (profile == YamlProfile.Yaml11CompatV1 && cleaned.contains(':')) {
        return parseSexagesimalFloat(cleaned)
    }
    if (!cleaned.contains('.') && !cleaned.contains('e') && !cleaned.contains('E')) {
        return null
    }
    val normalized = normalizeDecimalLexeme(cleaned)
    val decimal = parseJsonNumber(normalized) ?: return null
    return decimalCanonical(decimal)
}

/** Normalizes the 1.2/1.1 decimal lexeme for JSON-number parsing (native.rs:
 * 831-846): strips a leading `+`, inserts `0` before a leading `.`, inserts
 * `0` after a trailing `.` in the mantissa. */
internal fun normalizeDecimalLexeme(value: String): String {
    var text = value
    if (text.startsWith('+')) {
        text = text.substring(1)
    }
    if (text.startsWith("-.")) {
        text = "-0" + text.substring(1)
    } else if (text.startsWith('.')) {
        text = "0" + text
    }
    val exponent = text.indexOfFirst { it == 'e' || it == 'E' }
        .let { if (it < 0) text.length else it }
    if (text.substring(0, exponent).endsWith('.')) {
        text = text.substring(0, exponent) + "0" + text.substring(exponent)
    }
    return text
}

/** Canonical finite-decimal spelling: `coefficient` or `coefficient` +
 * `e` + `exponent` (native.rs:914-920; RFC 0007 §5). */
internal fun decimalCanonical(decimal: PvDecimal): String =
    if (decimal.exponent.signum() == 0) {
        decimal.coefficient.toString()
    } else {
        "${decimal.coefficient}e${decimal.exponent}"
    }

/** Strict JSON-number parsing to a normalized exact decimal (the Rust
 * Decimal::parse_json_number, native.rs:826-829). Returns null when the
 * lexeme is not a valid JSON number. */
internal fun parseJsonNumber(text: String): PvDecimal? {
    var index = 0
    var negative = false
    if (index < text.length && (text[index] == '-' || text[index] == '+')) {
        negative = text[index] == '-'
        index++
    }
    val integerStart = index
    while (index < text.length && text[index].isDigit()) {
        index++
    }
    val integerDigits = text.substring(integerStart, index)
    var fractionDigits = ""
    if (index < text.length && text[index] == '.') {
        index++
        val fractionStart = index
        while (index < text.length && text[index].isDigit()) {
            index++
        }
        fractionDigits = text.substring(fractionStart, index)
    }
    var exponentValue = BigInteger.ZERO
    if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
        index++
        var exponentNegative = false
        if (index < text.length && (text[index] == '-' || text[index] == '+')) {
            exponentNegative = text[index] == '-'
            index++
        }
        val exponentStart = index
        while (index < text.length && text[index].isDigit()) {
            index++
        }
        val exponentDigits = text.substring(exponentStart, index)
        if (exponentDigits.isEmpty() || fractionDigits.isEmpty() && integerDigits.isEmpty()) {
            return null
        }
        exponentValue = BigInteger(exponentDigits).let {
            if (exponentNegative) it.negate() else it
        }
    }
    if (index != text.length || integerDigits.isEmpty() && fractionDigits.isEmpty()) {
        return null
    }
    val coefficientText = integerDigits + fractionDigits
    val coefficient = BigInteger(coefficientText)
    val exponent = exponentValue.subtract(BigInteger.valueOf(fractionDigits.length.toLong()))
    val signed = if (negative) coefficient.negate() else coefficient
    return PvDecimal.of(signed, exponent)
}

/** YAML 1.1 sexagesimal integer `[0-9]+(:[0-5][0-9])+` (native.rs:848-870). */
private fun parseSexagesimalInteger(sign: Int, value: String): String? {
    val parts = value.split(':')
    val first = parts[0]
    if (first.isEmpty() || !first.all { it.isDigit() }) {
        return null
    }
    var magnitude = parseBaseMagnitude(first, 10) ?: return null
    var count = 0
    for (part in parts.subList(1, parts.size)) {
        val component = part.toIntOrNull() ?: return null
        if (component > 59 || part.isEmpty() || part.length > 2) {
            return null
        }
        magnitude = magnitude.multiply(BigInteger.valueOf(60)).add(BigInteger.valueOf(component.toLong()))
        count++
    }
    if (count == 0) {
        return null
    }
    val valueOut = if (sign < 0) magnitude.negate() else magnitude
    return valueOut.toString()
}

/** YAML 1.1 sexagesimal float `[0-9]+(:[0-5][0-9])+:[0-9]+\.[0-9]+`
 * (native.rs:872-912). */
private fun parseSexagesimalFloat(value: String): String? {
    val signPair = splitSign(value) ?: return null
    val sign = signPair.first
    val unsigned = signPair.second
    val parts = unsigned.split(':').toMutableList()
    val last = parts.removeAt(parts.size - 1)
    val dot = last.indexOf('.')
    if (dot < 0) {
        return null
    }
    val whole = last.substring(0, dot)
    val fraction = last.substring(dot + 1)
    if (fraction.isEmpty() || !fraction.all { it.isDigit() }) {
        return null
    }
    var magnitude = BigInteger.ZERO
    var magnitudeSet = false
    for ((index, part) in parts.withIndex()) {
        val component = part.toLongOrNull() ?: return null
        if (index > 0 && component > 59) {
            return null
        }
        if (index == 0) {
            magnitude = parseBaseMagnitude(part, 10) ?: return null
            magnitudeSet = true
        } else {
            magnitude = magnitude.multiply(BigInteger.valueOf(60)).add(BigInteger.valueOf(component))
        }
    }
    if (parts.isEmpty() || !magnitudeSet) {
        return null
    }
    val wholeValue = whole.toIntOrNull() ?: return null
    if (wholeValue > 59) {
        return null
    }
    magnitude = magnitude.multiply(BigInteger.valueOf(60)).add(BigInteger.valueOf(wholeValue.toLong()))
    val wholeText = magnitude.toString()
    val coefficientText = (if (sign < 0) "-" else "") + wholeText + fraction
    val coefficient = BigInteger(coefficientText)
    return decimalCanonical(
        PvDecimal.of(coefficient, BigInteger.valueOf(-fraction.length.toLong())),
    )
}

private fun splitSign(value: String): Pair<Int, String>? {
    if (value.isEmpty()) {
        return null
    }
    return when (value[0]) {
        '-' -> -1 to value.substring(1)
        '+' -> 1 to value.substring(1)
        else -> 1 to value
    }
}

/** Underscores are valid only between alphanumeric characters (native.rs:
 * 930-943). */
private fun validUnderscored(value: String): String? {
    for (index in value.indices) {
        if (value[index] == '_' &&
            (index == 0 || index + 1 == value.length ||
                !value[index - 1].isLetterOrDigit() || !value[index + 1].isLetterOrDigit())
        ) {
            return null
        }
    }
    return value
}

/** Parses one non-empty base-N digit string (native.rs:945-954). */
private fun parseBaseMagnitude(value: String, base: Int): BigInteger? {
    if (value.isEmpty()) {
        return null
    }
    if (!value.all { it.digitToIntOrNull(base) != null }) {
        return null
    }
    return BigInteger(value, base)
}

/** YAML 1.1 timestamp validation and canonicalization (native.rs:969-1075).
 * A timestamp with no zone follows the published 1.1 UTC rule and records
 * `Z` (RFC 0007 §6). */
internal fun parseTimestamp(value: String): String? {
    if (!value.all { it.code < 0x80 } || value.length < 10) {
        return null
    }
    val date = value.substring(0, 10)
    if (!validDate(date)) {
        return null
    }
    if (value.length == 10) {
        return value
    }
    return canonicalTimestamp(value)
}

private fun validDate(value: String): Boolean {
    if (value.length != 10 || value[4] != '-' || value[7] != '-') {
        return false
    }
    val year = value.substring(0, 4).toIntOrNull() ?: return false
    val month = value.substring(5, 7).toIntOrNull() ?: return false
    val day = value.substring(8, 10).toIntOrNull() ?: return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val maxDay = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (leap) 29 else 28
        else -> return false
    }
    return day != 0 && day <= maxDay
}

private fun canonicalTimestamp(value: String): String? {
    var rest = value.substring(10)
    rest = rest.trimStart(' ', '\t', 'T', 't')
    val hourPair = takeOneOrTwoDigits(rest) ?: return null
    val hour = hourPair.first
    rest = hourPair.second
    rest = rest.removePrefix(":") ?: return null
    val minutePair = takeTwoDigits(rest) ?: return null
    val minute = minutePair.first
    rest = minutePair.second
    rest = rest.removePrefix(":") ?: return null
    val secondPair = takeTwoDigits(rest) ?: return null
    val second = secondPair.first
    rest = secondPair.second
    if (hour > 23 || minute > 59 || second > 60) {
        return null
    }
    var fraction = ""
    if (rest.startsWith('.')) {
        val afterDot = rest.substring(1)
        val length = afterDot.takeWhile { it.isDigit() }.length
        if (length == 0) {
            return null
        }
        fraction = afterDot.substring(0, length)
        rest = afterDot.substring(length)
    }
    rest = rest.trimStart(' ', '\t')
    val zone = if (rest.isEmpty() || rest == "Z" || rest == "z") {
        "Z"
    } else {
        canonicalZone(rest) ?: return null
    }
    val fractionText = if (fraction.isEmpty()) "" else ".$fraction"
    return "%sT%02d:%02d:%02d%s%s".format(
        java.util.Locale.ROOT,
        value.substring(0, 10), hour, minute, second, fractionText, zone,
    )
}

private fun canonicalZone(value: String): String? {
    val sign = when (value.firstOrNull()) {
        '+' -> '+'
        '-' -> '-'
        else -> return null
    }
    var rest = value.substring(1)
    val hourPair = takeOneOrTwoDigits(rest) ?: return null
    val hour = hourPair.first
    rest = hourPair.second
    if (rest.startsWith(':')) {
        rest = rest.substring(1)
    }
    var minute = 0
    if (rest.isNotEmpty()) {
        val minutePair = takeTwoDigits(rest) ?: return null
        minute = minutePair.first
        if (minutePair.second.isNotEmpty()) {
            return null
        }
    }
    if (hour > 23 || minute > 59) {
        return null
    }
    return "%c%02d:%02d".format(java.util.Locale.ROOT, sign, hour, minute)
}

private fun takeTwoDigits(value: String): Pair<Int, String>? {
    if (value.length < 2 || !value.substring(0, 2).all { it.isDigit() }) {
        return null
    }
    return value.substring(0, 2).toInt() to value.substring(2)
}

private fun takeOneOrTwoDigits(value: String): Pair<Int, String>? {
    val count = value.take(2).takeWhile { it.isDigit() }.length
    if (count == 0) {
        return null
    }
    return value.substring(0, count).toInt() to value.substring(count)
}

/** YAML `!!binary` canonicalization: whitespace-free base64 with strict
 * alphabet, length, and trailing-zero-bit validation (native.rs:1077-1111). */
internal fun canonicalBase64(value: String): String? {
    val cleaned = value.filter { !it.isWhitespace() }
    val padding = cleaned.takeLastWhile { it == '=' }.length
    if (cleaned.length % 4 != 0 ||
        !cleaned.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' } ||
        cleaned.take(cleaned.length - padding).any { it == '=' } ||
        padding > 2
    ) {
        return null
    }
    if (padding > 0) {
        val lastSignificant = base64Value(cleaned[cleaned.length - padding - 1]) ?: return null
        val unusedMask = if (padding == 1) 0b0000_0011 else 0b0000_1111
        if (lastSignificant and unusedMask != 0) {
            return null
        }
    }
    return cleaned
}

private fun base64Value(value: Char): Int? =
    when (value) {
        in 'A'..'Z' -> value.code - 'A'.code
        in 'a'..'z' -> value.code - 'a'.code + 26
        in '0'..'9' -> value.code - '0'.code + 52
        '+' -> 62
        '/' -> 63
        else -> null
    }

/** Decodes one canonical base64 scalar to bytes (projection.rs:1191-1217). */
internal fun decodeBase64(value: String): ByteArray? {
    val bytes = value.toByteArray(Charsets.US_ASCII)
    val output = ArrayList<Byte>(bytes.size / 4 * 3)
    var index = 0
    while (index + 4 <= bytes.size) {
        val a = base64Value(bytes[index].toInt().toChar()) ?: return null
        val b = base64Value(bytes[index + 1].toInt().toChar()) ?: return null
        val c = if (bytes[index + 2].toInt() == '='.code) {
            0
        } else {
            base64Value(bytes[index + 2].toInt().toChar()) ?: return null
        }
        val d = if (bytes[index + 3].toInt() == '='.code) {
            0
        } else {
            base64Value(bytes[index + 3].toInt().toChar()) ?: return null
        }
        val combined = (a shl 18) or (b shl 12) or (c shl 6) or d
        output.add((combined shr 16).toByte())
        if (bytes[index + 2].toInt() != '='.code) {
            output.add((combined shr 8).toByte())
        }
        if (bytes[index + 3].toInt() != '='.code) {
            output.add(combined.toByte())
        }
        index += 4
    }
    return output.toByteArray()
}
