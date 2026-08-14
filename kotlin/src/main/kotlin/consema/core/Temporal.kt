// The four temporal PortableValue kinds: Date, Time, LocalDateTime,
// OffsetDateTime.
//
// Data authority: RFC 0016 §4.1 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md:140-143)
// plus the Rust constructors (https://github.com/consema/consema-rs/blob/main/consema-core/src/value.rs:419-576):
//   - Date uses the proleptic Gregorian calendar with astronomical year
//     numbering; the leap rule operates on the year's absolute magnitude
//     (value.rs:433-434);
//   - Time rejects leap seconds and 24:00:00, and requires the fractional
//     second to be an exact finite decimal in [0, 1) (value.rs:475-492,
//     is_fraction at 337-352);
//   - OffsetDateTime requires |offset_seconds| < 24 * 60 * 60
//     (value.rs:553-563).
//
// Validation happens at construction (Kotlin idiom): an invalid calendar
// date, time, or offset throws [InvalidTemporalException] carrying the
// frozen code "core.pvce.invalid-temporal@1" (the same code the PVCE decoder
// maps construction failures to).

package consema.core

import java.math.BigInteger

/** The frozen invalid-temporal code (https://github.com/consema/consema-rs/blob/main/consema-pvce/src/lib.rs:1082). */
internal const val CODE_INVALID_TEMPORAL = "core.pvce.invalid-temporal@1"

/**
 * Thrown when temporal fields are outside the supported ranges (the PVCE
 * decoder's InvalidTemporal classification).
 */
class InvalidTemporalException(message: String) :
    PvceException(PvceErrorKind.INVALID_TEMPORAL, message)

/**
 * A proleptic Gregorian date with astronomical year numbering. The year is
 * an arbitrary-precision signed integer; [month] is 1-12 and [day] is valid
 * for the month and the year's leap status (leap rule on the year's absolute
 * magnitude, so year -400 is a leap year and year -100 is not).
 */
data class PvDate(val year: BigInteger, val month: Int, val day: Int) : PortableValue() {
    override val kind: Kind get() = Kind.Date

    companion object {
        /** Validates and constructs a date; throws
         * [InvalidTemporalException] for an invalid calendar date. */
        fun of(year: BigInteger, month: Int, day: Int): PvDate {
            val date = PvDate(year, month, day)
            if (!date.validFields()) {
                throw InvalidTemporalException("core: invalid calendar date")
            }
            return date
        }
    }

    private fun validFields(): Boolean {
        if (month < 1 || month > 12) {
            return false
        }
        val magnitude = year.abs()
        fun divisibleBy(divisor: Long): Boolean = magnitude.mod(BigInteger.valueOf(divisor)).signum() == 0
        val leap = divisibleBy(4) && (!divisibleBy(100) || divisibleBy(400))
        val maxDay = when {
            month == 2 && leap -> 29
            month == 2 -> 28
            month == 4 || month == 6 || month == 9 || month == 11 -> 30
            else -> 31
        }
        return day >= 1 && day <= maxDay
    }
}

/**
 * A wall-clock time without leap seconds or 24:00:00. The fractional second
 * is an exact finite decimal in [0, 1).
 */
data class PvTime(
    val hour: Int,
    val minute: Int,
    val second: Int,
    val fractionalSecond: PvDecimal,
) : PortableValue() {
    override val kind: Kind get() = Kind.Time

    companion object {
        /** Validates and constructs a time; throws
         * [InvalidTemporalException] for invalid fields. */
        fun of(hour: Int, minute: Int, second: Int, fraction: PvDecimal): PvTime {
            val time = PvTime(hour, minute, second, fraction)
            if (!time.validFields()) {
                throw InvalidTemporalException("core: invalid time")
            }
            return time
        }
    }

    private fun validFields(): Boolean =
        hour in 0..23 && minute in 0..59 && second in 0..59 && isFraction(fractionalSecond)
}

/** A Date plus a Time without any offset. It is not a timestamp. */
data class PvLocalDateTime(val date: PvDate, val time: PvTime) : PortableValue() {
    override val kind: Kind get() = Kind.LocalDateTime
}

/**
 * A LocalDateTime plus a fixed UTC offset in whole seconds. The offset
 * magnitude is less than 24 hours; the value locates the timeline but never
 * carries an IANA region timezone.
 */
data class PvOffsetDateTime(val local: PvLocalDateTime, val offsetSeconds: Int) : PortableValue() {
    override val kind: Kind get() = Kind.OffsetDateTime

    companion object {
        /** Validates and constructs an offset date-time; throws
         * [InvalidTemporalException] when |offsetSeconds| >= 24 hours. */
        fun of(local: PvLocalDateTime, offsetSeconds: Int): PvOffsetDateTime {
            if (offsetSeconds >= 24 * 60 * 60 || offsetSeconds <= -24 * 60 * 60) {
                throw InvalidTemporalException("core: invalid UTC offset")
            }
            return PvOffsetDateTime(local, offsetSeconds)
        }
    }
}

/**
 * Reports whether the canonical decimal represents a value in [0, 1) (the
 * Rust Decimal::is_fraction, https://github.com/consema/consema-rs/blob/main/consema-core/src/value.rs:337-352): a
 * non-negative coefficient, and either a zero coefficient or an exponent
 * small enough that the coefficient's decimal digits plus the exponent
 * is <= 0.
 */
internal fun isFraction(d: PvDecimal): Boolean {
    val coefficient = d.coefficient
    if (coefficient.signum() < 0) {
        return false
    }
    if (coefficient.signum() == 0) {
        return true
    }
    val exponent = d.exponent
    if (exponent.signum() >= 0) {
        return false
    }
    val digits = BigInteger.valueOf(coefficient.abs().toString().length.toLong())
    return exponent.add(digits).signum() <= 0
}
