// Temporary debug harness (build/work only).
import consema.ini.IniProfile
import consema.ini.IniSourceEncoding
import consema.ini.IniWindowsCodePage
import consema.ini.IniEncodingSelection
import consema.ini.parse

fun main() {
    val bytes = hexToBytes("5b735d0d0a6b3d80")
    try {
        val doc = parse(
            bytes,
            IniProfile.WindowsV1,
            IniEncodingSelection.Explicit(
                IniSourceEncoding.WindowsCodePage(IniWindowsCodePage.fromNumber(1252)!!),
            ),
        )
        println("PARSE OK")
    } catch (t: Throwable) {
        println("PARSE FAILED: ${t.message}")
        val cause = t.cause
        if (cause != null) {
            println("CAUSE: ${cause::class.simpleName}: ${cause.message}")
            cause.stackTrace.filter { it.className.startsWith("consema") }.forEach { println("  at $it") }
        }
        t.stackTrace.filter { it.className.startsWith("consema") }.forEach { println("  at $it") }
    }
}

private fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
}
