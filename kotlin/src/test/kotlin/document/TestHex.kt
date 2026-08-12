// Shared test helpers for the document domain tests.
//
// Hex transcription helpers used to transcribe the frozen byte vectors of
// conformance/vectors/source-v1.json into tests (盲写纪律: golden bytes are
// transcribed from the vector file, not invented).

package document

/** Decodes a lowercase hex string into bytes (the vector `*_hex` fields). */
internal fun hexToBytes(text: String): ByteArray {
    require(text.length % 2 == 0) { "invalid hex: odd length" }
    val bytes = ByteArray(text.length / 2)
    for (i in bytes.indices) {
        bytes[i] = text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return bytes
}

/** Encodes bytes as lowercase hex. */
internal fun bytesToHex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val hex = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val value = bytes[i].toInt() and 0xff
        hex[i * 2] = digits[value ushr 4]
        hex[i * 2 + 1] = digits[value and 0x0f]
    }
    return String(hex)
}
