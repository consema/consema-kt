// Shared case-file loading and fact-vocabulary helpers of the cross-language
// differential harnesses (byte parity / normalized / protocol exchange).
//
// The differential input sets live at conformance/differential/ of the
// consema repository (single authority, https://github.com/consema/consema/blob/main/docs/five-language-ci-design.md
// §3.5: cases.json 68, normalized/cases.json 108, protocol-exchange/
// cases.json 83; manifest ids consema.differential.byte-parity@1 /
// normalized@1 / protocol-exchange@1). They are language-neutral JSON
// documents, so every
// side reads the same text: like the Rust examples
// (consema-rs/consema-conformance/examples/emit_parity_bytes.rs:49-64), this
// harness parses the case file with the strict JSON parser and projects it
// to the best-exact core value — no second authority.
//
// The fact vocabulary (the key=value lines and the escape() function) is
// mirrored verbatim by the Rust examples and the Go harness
// (consema-go/go/conformance/differential/normalized/runner.go escape/join); error
// texts never participate in any comparison (RFC 0016 §6).

package consema.differential

import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.document.ParseLimits
import consema.json.JsonProfile
import consema.json.ProjectionRequest
import consema.json.ProjectionResult
import consema.json.ProjectionTarget
import consema.json.parse
import consema.json.project
import java.io.File

/** The fifteen-kind vocabulary of the byte-parity case file's "kinds"
 * metadata (the core Kind names of RFC 0016 §4.1). */
val allKindNames: List<String> = listOf(
    "Null", "Boolean", "String", "Integer", "Decimal",
    "BinaryFloat32", "BinaryFloat64", "Bytes", "Date", "Time",
    "LocalDateTime", "OffsetDateTime", "Array", "Object", "EntryMapping",
)

/**
 * Parses one differential case file with the strict JSON parser and
 * projects it to the best-exact core value (the Rust example's pipeline:
 * emit_parity_bytes.rs:49-64). The root must be an Object.
 */
fun loadCaseFile(file: File): PvObject {
    val document = parse(file.readBytes(), JsonProfile.StrictV1, ParseLimits.default)
    val request = ProjectionRequest.builder(ProjectionTarget.BestExactCoreV1).build()
    val root = when (val result = document.project(request)) {
        is ProjectionResult.Complete -> result.projection.value
        is ProjectionResult.Failed -> error("case file projection failed: ${result.attempt.diagnostics.firstOrNull()?.code}")
    }
    return root as? PvObject ?: error("case file root must be an Object")
}

/** Reads one required string field. */
fun objectString(fields: PvObject, key: String, path: String): String =
    (fields.get(key) as? PvString)?.value
        ?: error("$path.$key must be a String")

/** Reads one optional string field ("" when absent or null). */
fun objectStringOr(fields: PvObject, key: String): String =
    (fields.get(key) as? PvString)?.value ?: ""

/** Reads one optional integer field (null when absent or null). */
fun objectIntOr(fields: PvObject, key: String): Int? =
    (fields.get(key) as? PvInteger)?.value?.toInt()

/** Reads one required integer field. */
fun objectInt(fields: PvObject, key: String, path: String): Int =
    (fields.get(key) as? PvInteger)?.value?.toInt()
        ?: error("$path.$key must be an Integer")

/** Reads one optional boolean field (null when absent). */
fun objectBooleanOr(fields: PvObject, key: String): Boolean? =
    (fields.get(key) as? PvBoolean)?.value

/** Reads one optional object field. */
fun objectObjectOr(fields: PvObject, key: String): PvObject? =
    fields.get(key) as? PvObject

/** Reads one optional array field (null when absent or null). */
fun objectArrayOr(fields: PvObject, key: String): List<PortableValue>? =
    (fields.get(key) as? PvArray)?.items()

/** Reads one required array field. */
fun objectArray(fields: PvObject, key: String, path: String): List<PortableValue> =
    (fields.get(key) as? PvArray)?.items()
        ?: error("$path.$key must be a Sequence")

/** Reads one optional string array field. */
fun objectStringArrayOr(fields: PvObject, key: String): List<String>? =
    objectArrayOr(fields, key)?.map { item ->
        (item as? PvString)?.value ?: error("$key items must be Strings")
    }

/** Reads one optional int array field. */
fun objectIntArrayOr(fields: PvObject, key: String): List<Int>? =
    objectArrayOr(fields, key)?.map { item ->
        (item as? PvInteger)?.value?.toInt() ?: error("$key items must be Integers")
    }

/** Whether the field is present and not null. */
fun objectPresent(fields: PvObject, key: String): Boolean =
    fields.get(key) != null && fields.get(key) !is PvNull

// ---------------------------------------------------------------------------
// Fact vocabulary helpers (the Rust example mirrors these exactly)
// ---------------------------------------------------------------------------

/** The ordered key=value fact set of one case. The key set is fixed: every
 * document case emits exactly the same keys in the same order, so a missing
 * or extra key is itself a differential failure. */
class Facts {
    private val lines = ArrayList<String>()

    /** Appends one fact line. */
    fun set(key: String, value: String) {
        lines.add("$key=$value")
    }

    /** The ordered fact lines. */
    fun lines(): List<String> = lines
}

/**
 * Renders one text value for an evidence file: JSON string escaping (short
 * escapes for the JSON whitespace set, `\u00xx` lowercase hex for the other
 * control characters, everything else passed through as UTF-8). The Go
 * harness and the Rust example implement the identical function
 * (consema-go/go/conformance/differential/normalized/runner.go:233-295). The evidence
 * vocabulary is compared over bytes, so valid Unicode text must render
 * identically on every side; the consema-go/go/Rust lossy-invalid-UTF-8 branch is
 * unreachable here because Kotlin text values are always valid Unicode and
 * every byte-level source fact is escaped the same way on both sides.
 */
fun escape(text: String): String {
    val output = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val width = Character.charCount(codePoint)
        when (codePoint) {
            '"'.code -> output.append("\\\"")
            '\\'.code -> output.append("\\\\")
            '\b'.code -> output.append("\\b")
            '\u000C'.code -> output.append("\\f")
            '\n'.code -> output.append("\\n")
            '\r'.code -> output.append("\\r")
            '\t'.code -> output.append("\\t")
            else -> {
                if (codePoint < 0x20) {
                    output.append("\\u%04x".format(codePoint))
                } else {
                    output.appendCodePoint(codePoint)
                }
            }
        }
        index += width
    }
    return output.toString()
}

/** Renders one ordered list into the `|`-joined fact vocabulary. */
fun join(items: List<String>): String = items.joinToString("|")

/** Lowercase hex of the bytes (the runner's `hex`, the Rust example's
 * `hex`). */
fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

/** Decodes lowercase or uppercase hex (whitespace-trimmed). */
fun unhex(text: String): ByteArray {
    val clean = text.trim().filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "hex text must have an even length" }
    val bytes = ByteArray(clean.length / 2)
    for (index in bytes.indices) {
        bytes[index] = clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return bytes
}

/** Splits one fact line into its key and value. */
fun splitFact(line: String): Pair<String, String>? {
    val index = line.indexOf('=')
    if (index < 0) {
        return null
    }
    return line.substring(0, index) to line.substring(index + 1)
}

/** Splits one evidence file into fact lines (the shared reader of both
 * directions; the Rust example's consume mode mirrors it). */
fun splitEvidenceLines(text: String): List<String> {
    val content = text.trimEnd('\r', '\n')
    if (content.isEmpty()) {
        return emptyList()
    }
    return content.split("\n")
}
