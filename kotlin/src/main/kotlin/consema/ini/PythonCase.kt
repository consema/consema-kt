// Pinned Python 3.14 / Unicode 16.0 default `optionxform` semantics.
//
// Data authority:
//   - RFC 0009 §7 (docs/rfcs/0009-ini-family-profiles-v1.md:235-239): option
//     comparison and duplicate detection use the Python 3.14 default
//     lowercase `optionxform`, pinned to Unicode 16.0 independently of the
//     Rust compiler's Unicode tables; original option spelling is retained.
//   - conformance/vectors/ini-v1.json formation.python-unicode16-optionxform
//     pins the U+0130 -> "i" + U+0307 case-expansion duplicate fact.
//   - crates/consema-ini/src/python_case.rs:201-232 pins the mapping rule:
//     a per-scalar simple lowercase mapping with the single special
//     expansion U+0130 -> U+0069 U+0307; the pinned tables are Unicode 16.0
//     (python_case.rs:5-199).
//
// Kotlin-idiomatic design: the JDK's Character.toLowerCase provides the
// per-scalar simple lowercase mapping. The JDK Unicode tables approximate
// the pinned Unicode 16.0 tables (Temurin 17 ships Unicode 13.0); the
// explicit U+0130 special case is applied first because the simple mapping
// of U+0130 is the identity. Newer-script characters need differential
// verification (盲写纪律: no gates claimed).

package consema.ini

/**
 * The Python 3.14 default optionxform: lowercase every scalar under the
 * pinned Unicode 16.0 simple mapping, with the single two-scalar expansion
 * U+0130 -> U+0069 U+0307 (python_case.rs:201-215).
 */
internal fun optionxform(value: String): String {
    val output = StringBuilder(value.length)
    for (character in value) {
        val code = character.code
        if (code == 0x0130) {
            // The two-scalar expansion U+0130 -> U+0069 U+0307
            // (python_case.rs:205-207).
            output.append('i')
            output.appendCodePoint(0x0307)
        } else {
            val lower = Character.toLowerCase(code)
            output.appendCodePoint(if (lower == code) code else lower)
        }
    }
    return output.toString()
}
