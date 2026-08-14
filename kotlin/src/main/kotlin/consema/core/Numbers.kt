// The numeric PortableValue kinds: Integer, Decimal, BinaryFloat32,
// BinaryFloat64.
//
// Data authority: RFC 0016 §4.1 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md:
// 134-138); decimal canonicalization follows the Rust Decimal::new
// normalization (https://github.com/consema/consema-rs/blob/main/consema-core/src/value.rs:277-292), cross-checked by
// conformance/vectors/v1.json value.decimal-normalization ("1.00" equals
// "10e-1" — i.e. 1×10^0 vs 1×10^0 after normalization).

package consema.core

import java.math.BigInteger

/**
 * A canonical arbitrary-precision integer. The wrapped value is always
 * canonical: zero has sign 0 and an empty magnitude; a non-zero magnitude is
 * minimal big-endian with no leading zero octets (java.math.BigInteger
 * invariants).
 */
data class PvInteger(val value: BigInteger) : PortableValue() {
    override val kind: Kind get() = Kind.Integer

    companion object {
        /** Wraps [value], copying it (BigInteger is immutable). */
        fun of(value: BigInteger): PvInteger = PvInteger(value)

        /** The zero integer. */
        val ZERO: PvInteger = PvInteger(BigInteger.ZERO)
    }

    /** Returns the base-ten representation. */
    override fun toString(): String = value.toString()
}

/**
 * A canonical exact finite decimal, coefficient × 10^exponent (RFC 0016
 * §4.1; no float round-trip). The canonical form mirrors the Rust Decimal
 * normalization: a zero coefficient has exponent zero, and trailing decimal
 * zeros of the coefficient are stripped into the exponent (10 × 10^0 →
 * 1 × 10^1).
 */
class PvDecimal private constructor(val coefficient: BigInteger, val exponent: BigInteger) :
    PortableValue() {
    override val kind: Kind get() = Kind.Decimal

    companion object {
        /**
         * Builds a canonical decimal. The zero decimal is 0 × 10^0
         * regardless of the given exponent.
         */
        fun of(coefficient: BigInteger, exponent: BigInteger): PvDecimal {
            var c = coefficient
            var e = exponent
            if (c.signum() == 0) {
                return PvDecimal(BigInteger.ZERO, BigInteger.ZERO)
            }
            val ten = BigInteger.TEN
            val one = BigInteger.ONE
            while (true) {
                val division = c.divideAndRemainder(ten)
                if (division[1].signum() != 0) {
                    break
                }
                c = division[0]
                e = e.add(one)
            }
            return PvDecimal(c, e)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PvDecimal &&
            coefficient == other.coefficient &&
            exponent == other.exponent

    override fun hashCode(): Int = 31 * coefficient.hashCode() + exponent.hashCode()
}
