// Exact Java UTF-16 string semantics for the Properties native model.
//
// Data authority (language-neutral sources first):
//   - RFC 0010 §4 (docs/rfcs/0010-java-properties-profiles-v1.md:108-131):
//     Java String is an ordered sequence of UTF-16 code units, not a
//     guarantee of well-formed Unicode scalar values; the native JavaString
//     value is an immutable sequence of code units with strict
//     equality/hash, a bounded WellFormedUnicode | UnpairedSurrogate
//     validation, conversion to a Unicode String only when well formed, and
//     canonical UTF16BE/1 bytes for protocol and test vectors.
//   - conformance/vectors/java-properties-v1.json pins the UTF16BE/1 hex
//     facts (value_utf16be_hex / key_utf16be_hex) and the statuses
//     (formation.escape-and-java-utf16-matrix, lines 30-34).
//   - crates/consema-properties/src/lib.rs:124-206 (JavaStringStatus,
//     JavaString, JavaStringConversionError) pins the shapes; the
//     classification scan is lib.rs:814-830. go/properties is a
//     cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): a Kotlin Char IS one UTF-16
// code unit, so the exact units are stored as a CharArray (defensive copies
// at the boundary); an unpaired surrogate is ordinary content here and only
// blocks [toUnicode].

package consema.properties

/** Whether exact Java UTF-16 units form Unicode scalar text (lib.rs:124-131). */
enum class JavaStringStatus {
    /** Every surrogate participates in one adjacent high/low pair. */
    WellFormedUnicode,

    /** At least one surrogate is unpaired. */
    UnpairedSurrogate,
}

/** An exact Java string cannot enter a Unicode-only host string
 * (lib.rs:196-206). */
class JavaStringConversionException :
    Exception("Java UTF-16 string contains an unpaired surrogate")

/**
 * Exact Java string content as an immutable UTF-16 code-unit sequence
 * (RFC 0010 §4; lib.rs:133-194). Strict equality and hashing are over the
 * exact code units, so an unpaired surrogate never compares equal to a
 * replacement character.
 */
class JavaString private constructor(
    private val units: CharArray,
    /** Exact surrogate pairing status. */
    val status: JavaStringStatus,
) {
    companion object {
        /** Creates exact Java content and computes surrogate well-formedness
         * (lib.rs:141-147). */
        fun fromCodeUnits(units: CharArray): JavaString =
            JavaString(units.copyOf(), classifyJavaString(units))

        /** Converts one valid Unicode scalar string to its exact UTF-16 units
         * (lib.rs:149-153). */
        fun fromUnicode(value: String): JavaString = fromCodeUnits(value.toCharArray())
    }

    /** Exact ordered Java UTF-16 code units; returns a defensive copy
     * (lib.rs:155-159). */
    fun codeUnits(): CharArray = units.copyOf()

    internal fun rawUnits(): CharArray = units

    /** Canonical BOM-free big-endian `UTF16BE/1` bytes (lib.rs:161-168). */
    fun utf16beBytes(): ByteArray {
        val bytes = ByteArray(units.size * 2)
        for (i in units.indices) {
            val unit = units[i].code
            bytes[i * 2] = (unit ushr 8).toByte()
            bytes[i * 2 + 1] = (unit and 0xff).toByte()
        }
        return bytes
    }

    /** Exact code-unit count. */
    val length: Int
        get() = units.size

    /** Converts only well-formed Java content to a Unicode String
     * (lib.rs:176-179). */
    fun toUnicode(): String {
        if (status == JavaStringStatus.UnpairedSurrogate) {
            throw JavaStringConversionException()
        }
        return String(units)
    }

    override fun equals(other: Any?): Boolean =
        other is JavaString && units.contentEquals(other.units)

    override fun hashCode(): Int = units.contentHashCode()

    override fun toString(): String =
        "JavaString(${units.size} code units, $status)"
}

/** Classifies surrogate pairing (lib.rs:814-830). */
private fun classifyJavaString(units: CharArray): JavaStringStatus {
    var index = 0
    while (index < units.size) {
        val unit = units[index].code
        if (unit in 0xd800..0xdbff) {
            val next = units.getOrNull(index + 1)?.code
            if (next != null && next in 0xdc00..0xdfff) {
                index += 2
                continue
            }
            return JavaStringStatus.UnpairedSurrogate
        }
        if (unit in 0xdc00..0xdfff) {
            return JavaStringStatus.UnpairedSurrogate
        }
        index += 1
    }
    return JavaStringStatus.WellFormedUnicode
}
