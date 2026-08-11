// One-pass decoded-scalar to raw-byte offset resolution.
//
// Data authority: crates/consema-yaml/src/offsets.rs:1-80 pins the single
// forward walk that resolves every lexeme and node boundary in non-decreasing
// order (constant-width per-scalar raw advances for the only encodings the
// YAML parse can select: UTF-8 and BOM-detected UTF-16). Lookups may be
// repeated and need not be sorted; a lookup behind the cursor restarts the
// walk. The Kotlin SourceSnapshot boundary index
// (kotlin/.../document/Source.kt:244-296) resolves any single boundary, but
// the YAML pipeline resolves O(pieces + nodes) boundaries, so the shared
// walk keeps the parse linear.
//
// Kotlin-idiomatic design: a small stateful resolver owned by one parse
// (never shared across parses), with the exact raw advances of offsets.rs:
// 67-78.

package consema.yaml

import consema.document.SourceEncoding
import consema.document.SourceSnapshot

/** Resolves decoded Unicode scalar offsets to exact raw byte offsets
 * (offsets.rs:15-80). */
internal class RawByteResolver(source: SourceSnapshot) {
    private val text: String = source.decodedText()
        ?: throw YamlFormationException("yaml.parse.syntax@1", "yaml: no decoded text")
    private val encoding: SourceEncoding = source.encodingFacts.selected
    private var scalar = 0
    private var rawByte = 0
    private var utf8Byte = 0

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
        }
        var index = utf8Byte
        var remaining = target - scalar
        while (remaining > 0 && index < text.length) {
            val codePoint = text.codePointAt(index)
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
            index += charCount
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
