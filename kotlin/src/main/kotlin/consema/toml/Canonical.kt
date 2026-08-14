// The frozen canonical TOML 1.0 scalar spellings.
//
// Data authority:
//   - https://github.com/consema/consema-rs/blob/main/consema-toml/src/edit.rs:1516-1636 (canonical_string,
//     canonical_float, canonical_date, canonical_time, canonical_local_
//     datetime, canonical_offset_datetime, exact_nanoseconds) and
//     https://github.com/consema/consema-rs/blob/main/consema-toml/src/materialization.rs:353-407 (write_string,
//     write_float, write_date, write_time) pin the deterministic canonical
//     representations used by both materialization and every structural
//     edit insertion.
//   - RFC 0004 §6: TOML materialization requires a legal deterministic TOML
//     representation without losing payload semantics; canonical NaN
//     payloads (0x7ff8000000000000 / 0xfff8000000000000) are representable,
//     non-canonical NaN payloads fail.
//   - consema-go/go/toml/materialization.go and edit.go are cross-references only.
//
// Kotlin-idiomatic design: the shortest-round-trip float spelling of the
// Rust `f64::to_string` (never exponent notation) is reproduced from the
// JDK's shortest Double.toString by expanding through BigDecimal
// toPlainString; the exact bit patterns of -0.0, ±inf, and the two
// canonical NaNs are special-cased.

package consema.toml

import consema.core.PvDecimal
import java.math.BigDecimal
import java.math.BigInteger

/** The canonical basic-string spelling (edit.rs:1516-1537): `\b \t \n \f
 * \r \" \\` short escapes and `\uXXXX` for U+0000..U+001F and U+007F; all
 * other scalars are emitted as UTF-8. */
internal fun canonicalString(value: String): String {
    val output = StringBuilder(value.length + 2)
    output.append('"')
    for (character in value) {
        when (character.code) {
            0x08 -> output.append("\\b")
            0x09 -> output.append("\\t")
            0x0A -> output.append("\\n")
            0x0C -> output.append("\\f")
            0x0D -> output.append("\\r")
            0x22 -> output.append("\\\"")
            0x5C -> output.append("\\\\")
            in 0x00..0x1F, 0x7F ->
                output.append(String.format(java.util.Locale.ROOT, "\\u%04X", character.code))
            else -> output.append(character)
        }
    }
    output.append('"')
    return output.toString()
}

/**
 * The canonical binary64 spelling, or null when the payload has no legal
 * deterministic TOML representation (edit.rs:1539-1560; RFC 0004 §6).
 * Canonical NaNs are `nan`/`-nan`; all other NaN payloads fail. The sign
 * of zero is preserved. Finite values use the shortest-round-trip decimal
 * in plain (non-exponent) notation, suffixed with `.0` when integral.
 */
internal fun canonicalFloatText(bits: Long): String? {
    if (bits == java.lang.Double.doubleToRawLongBits(Double.NaN)) return "nan"
    if (bits == java.lang.Double.doubleToRawLongBits(-Double.NaN)) return "-nan"
    if (bits == java.lang.Double.doubleToRawLongBits(Double.POSITIVE_INFINITY)) return "inf"
    if (bits == java.lang.Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY)) return "-inf"
    if (bits == Long.MIN_VALUE) return "-0.0"
    val value = java.lang.Double.longBitsToDouble(bits)
    if (value.isNaN()) {
        // Any other NaN payload is not canonically representable
        // (materialization.rs:389-394).
        return null
    }
    // The JDK toString is the correctly-rounded shortest representation;
    // expanding through BigDecimal reproduces the Rust f64 Display digit
    // sequence without exponent notation.
    var text = BigDecimal(value.toString()).toPlainString()
    if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
        text += ".0"
    }
    return text
}

/** The canonical date spelling `YYYY-MM-DD`, or null outside the frozen
 * 0..=9999 year range (edit.rs:1562-1568). */
internal fun canonicalDateText(year: Int, month: Int, day: Int): String? {
    if (year < 0 || year > 9999) {
        return null
    }
    return "%04d-%02d-%02d".format(java.util.Locale.ROOT, year, month, day)
}

/** The canonical time spelling `HH:MM:SS` with the minimal exact
 * fractional digits (edit.rs:1570-1587; materialization.rs:426-448).
 * [nanoseconds] must be the exact_nanoseconds result (0 when the fraction
 * is zero). */
internal fun canonicalTimeText(hour: Int, minute: Int, second: Int, nanoseconds: Long): String {
    var fraction = "%09d".format(java.util.Locale.ROOT, nanoseconds)
    while (fraction.endsWith('0')) {
        fraction = fraction.dropLast(1)
    }
    val base = "%02d:%02d:%02d".format(java.util.Locale.ROOT, hour, minute, second)
    return if (fraction.isEmpty()) {
        base
    } else {
        "$base.$fraction"
    }
}

/** The canonical local date-time spelling `YYYY-MM-DDTHH:MM:SS...`
 * (edit.rs:1589-1595). */
internal fun canonicalLocalDateTimeText(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    nanoseconds: Long,
): String? = canonicalDateText(year, month, day)?.let { date ->
    "$date" + "T" + canonicalTimeText(hour, minute, second, nanoseconds)
}

/** The canonical offset date-time spelling with `Z` for zero and `±HH:MM`
 * otherwise; offsets with a non-whole minute or |offset| >= 24 hours fail
 * (edit.rs:1597-1616). */
internal fun canonicalOffsetDateTimeText(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    nanoseconds: Long,
    offsetSeconds: Int,
): String? {
    val local = canonicalLocalDateTimeText(year, month, day, hour, minute, second, nanoseconds)
        ?: return null
    if (offsetSeconds == 0) {
        return "${local}Z"
    }
    if (offsetSeconds % 60 != 0) {
        return null
    }
    val minutes = offsetSeconds / 60
    if (minutes >= 24 * 60 || minutes <= -24 * 60) {
        return null
    }
    val sign = if (minutes < 0) '-' else '+'
    val magnitude = if (minutes < 0) -minutes else minutes
    return "$local$sign%02d:%02d".format(java.util.Locale.ROOT, magnitude / 60, magnitude % 60)
}

/**
 * The exact nanosecond count of a fractional-second decimal, or null when
 * the decimal is not an exact nanosecond fraction (edit.rs:1618-1636;
 * materialization.rs:549-567): coefficient zero maps to zero; the exponent
 * must be in -9..0, the scaled coefficient must fit, and the result must
 * be below 10^9.
 */
internal fun exactNanoseconds(fraction: PvDecimal): Long? {
    if (fraction.coefficient == BigInteger.ZERO) {
        return 0
    }
    val exponent = try {
        fraction.exponent.longValueExact()
    } catch (e: ArithmeticException) {
        return null
    }
    if (exponent !in -9..0) {
        return null
    }
    var nanoseconds = try {
        fraction.coefficient.longValueExact()
    } catch (e: ArithmeticException) {
        return null
    }
    if (nanoseconds < 0) {
        return null
    }
    for (step in 0 until (exponent + 9).toInt()) {
        nanoseconds = try {
            Math.multiplyExact(nanoseconds, 10L)
        } catch (e: ArithmeticException) {
            return null
        }
    }
    if (nanoseconds >= 1_000_000_000) {
        return null
    }
    return nanoseconds
}
