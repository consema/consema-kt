// UTF-8 validity helpers.
//
// The PVCE/1 decoder must reject string bytes that are not valid UTF-8
// (consema-rs/consema-pvce/src/lib.rs:755-759). Kotlin's String(byteArray)
// replaces malformed sequences, so validity is checked explicitly with a
// java.nio.charset decoder in REPORT mode (standard library only).

package consema.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reports whether [bytes] is a valid UTF-8 sequence.
 */
fun isValidUtf8(bytes: ByteArray): Boolean {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: java.nio.charset.CharacterCodingException) {
        false
    }
}

/**
 * Decodes [bytes] as UTF-8, throwing [PvceException] with
 * [PvceErrorKind.INVALID_UTF8] when the bytes are not valid UTF-8.
 */
internal fun decodeUtf8Strict(bytes: ByteArray): String {
    if (!isValidUtf8(bytes)) {
        throw PvceException(PvceErrorKind.INVALID_UTF8, "core: PVCE/1 string bytes are not valid UTF-8")
    }
    return String(bytes, StandardCharsets.UTF_8)
}
