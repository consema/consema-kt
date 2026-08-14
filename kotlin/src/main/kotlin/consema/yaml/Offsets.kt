// One-pass decoded-scalar to raw-byte offset resolution.
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-yaml/src/offsets.rs pins the single
// forward walk that resolves every lexeme and node boundary in non-decreasing
// order (constant-width per-scalar raw advances for the only encodings the
// YAML parse can select: UTF-8 and BOM-detected UTF-16). Lookups may be
// repeated and need not be sorted; a lookup behind the cursor restarts the
// walk. The Kotlin SourceSnapshot boundary index
// (kotlin/src/main/kotlin/consema/document/Source.kt) resolves any single boundary, but
// the YAML pipeline resolves O(pieces + nodes) boundaries, so the shared
// walk keeps the parse linear.
//
// Kotlin-idiomatic design: a small stateful resolver owned by one parse
// (never shared across parses), with the exact raw advances of offsets.rs
// .

package consema.yaml

import consema.document.SourceEncoding
import consema.document.SourceSnapshot

/** Resolves decoded Unicode scalar offsets to exact raw byte offsets
 * (offsets.rs). */
internal class RawByteResolver(source: SourceSnapshot) {
    private val text: String = source.decodedText()
        ?: throw YamlFormationException("yaml.parse.syntax@1", "yaml: no decoded text")
    private val encoding: SourceEncoding = source.encodingFacts.selected
    private var scalar = 0
    private var rawByte = 0
    private var utf8Byte = 0
    private var textIndex = 0

    /** Exact raw byte offset of one decoded scalar boundary. */
    fun resolve(target: Int): Int {
        advanceTo(target)
        return rawByte
    }

    /** Decoded-text byte offset of one decoded scalar boundary. */
    fun decodedByteAt(target: Int): Int {
        advanceTo(target)
        return utf8Byte
    }

    private fun advanceTo(target: Int) {
        if (target < scalar) {
            scalar = 0
            rawByte = 0
            utf8Byte = 0
            textIndex = 0
        }
        var remaining = target - scalar
        while (remaining > 0 && textIndex < text.length) {
            val codePoint = text.codePointAt(textIndex)
            val charCount = Character.charCount(codePoint)
            rawByte += when (encoding) {
                SourceEncoding.Utf8 -> utf8Length(codePoint)
                SourceEncoding.Utf16Le, SourceEncoding.Utf16Be -> charCount * 2
                else ->
                    throw YamlFormationException(
                        "yaml.parse.syntax@1",
                        "yaml: unsupported encoding for scalar mapping",
                    )
            }
            utf8Byte += utf8Length(codePoint)
            textIndex += charCount
            scalar++
            remaining--
        }
    }
}

/** UTF-8 byte length of one code point (the Rust char.len_utf8). */
internal fun utf8Length(scalar: Int): Int =
    when {
        scalar < 0x80 -> 1
        scalar < 0x800 -> 2
        scalar < 0x1_0000 -> 3
        else -> 4
    }

/** Saturated multiplication (the Rust saturating_mul). */
internal fun Int.saturatingMul(right: Int): Int =
    if (right != 0 && this > Int.MAX_VALUE / right) Int.MAX_VALUE else this * right

/** Saturated addition (the Rust saturating_add). */
internal fun Int.saturatingAdd(right: Int): Int =
    if (this > Int.MAX_VALUE - right) Int.MAX_VALUE else this + right
