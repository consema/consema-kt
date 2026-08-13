// The transferable `core.diagnostic@1` record.
//
// Data authority: RFC 0016 §6 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md:194-):
// "unknown code or category contradiction is a protocol error"; the record
// shape follows consema-rs/consema-protocol/src/diagnostic.rs (construction
// validation at diagnostic.rs:336-351). Construction validates the code
// against the frozen error registry and the category against the registry
// record. consema-go/go/protocol/diagnostic.go is a cross-reference.

package consema.protocol

import consema.core.PvArray
import consema.core.PvBytes
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue

/** The presentation severity of one diagnostic. */
enum class Severity(val wireName: String) {
    Info("Info"),
    Warning("Warning"),
    Error("Error"),
}

/** Parses one canonical severity spelling. */
fun parseSeverity(name: String): Severity =
    Severity.entries.firstOrNull { it.wireName == name }
        ?: throw invalid("$.severity", "unknown diagnostic severity")

/** Classifies whether a fix can be applied without additional judgment. */
enum class FixApplicability(val wireName: String) {
    MachineApplicable("MachineApplicable"),
    MaybeApplicable("MaybeApplicable"),
    Manual("Manual"),
}

/** Parses one canonical applicability spelling. */
fun parseFixApplicability(name: String): FixApplicability =
    FixApplicability.entries.firstOrNull { it.wireName == name }
        ?: throw invalid("$.fixes[].applicability", "unknown fix applicability")

/**
 * A transferable source location bound to a caller-assigned stable source
 * ID (diagnostic.rs:13-59).
 */
data class SourceLocation(
    /** The caller-assigned stable source identity. */
    val sourceId: String,
    /** The inclusive start byte. */
    val startByte: ULong,
    /** The exclusive end byte. */
    val endByte: ULong,
) {
    /** Validates one half-open source range. */
    companion object {
        fun of(sourceId: String, startByte: ULong, endByte: ULong): SourceLocation {
            if (sourceId.isEmpty() || sourceId.length > 1024 || startByte > endByte) {
                throw invalid("$.location", "source ID or half-open byte range is invalid")
            }
            return SourceLocation(sourceId, startByte, endByte)
        }
    }
}

/** A related transferable source location with its stable relationship
 * role. */
data class RelatedSourceLocation(val role: String, val location: SourceLocation)

/**
 * An explicit source replacement proposal; never an implicit write.
 */
data class FixProposal(
    /** The stable namespaced fix ID. */
    val id: String,
    /** The applicability classification. */
    val applicability: FixApplicability,
    /** The optional target source range. */
    val location: SourceLocation?,
    /** The exact replacement bytes. */
    val replacement: ByteArray,
)

/**
 * The full `core.diagnostic@1` record independent from control-flow status
 * (RFC 0016 §6). Construction validates the code against the frozen error
 * registry; unknown codes and category contradictions are protocol errors.
 */
class Diagnostic private constructor(
    /** The stable namespaced registered code. */
    val code: String,
    /** The diagnostic category. */
    val category: DiagnosticCategory,
    /** The presentation severity. */
    val severity: Severity,
    /** The optional primary source location. */
    val primary: SourceLocation?,
    /** The related locations in semantic order. */
    val related: List<RelatedSourceLocation>,
    /** The deterministic arguments; the wire form sorts the names. */
    val arguments: Map<String, String>,
    /** The stable note IDs or localized fallback text. */
    val notes: List<String>,
    /** The explicit optional fixes. */
    val fixes: List<FixProposal>,
    /** The final deterministic occurrence ordinal. */
    val occurrence: ULong,
) {
    companion object {
        /** Validates the code/category consistency against the error
         * registry and constructs the diagnostic (the Rust
         * DiagnosticMessage::from_core_with_registry validation,
         * diagnostic.rs:336-351). */
        fun of(
            code: String,
            category: DiagnosticCategory,
            severity: Severity,
            primary: SourceLocation?,
            related: List<RelatedSourceLocation>,
            arguments: Map<String, String>,
            notes: List<String>,
            fixes: List<FixProposal>,
            occurrence: ULong,
            registry: ErrorCodeRegistry,
        ): Diagnostic {
            validateDiagnosticCode(code, category, registry)
            return Diagnostic(
                code, category, severity, primary, related,
                if (arguments.isEmpty()) emptyMap() else arguments.toMap(),
                notes, fixes, occurrence,
            )
        }

        /** Strictly decodes `core.diagnostic@1` under one explicit error
         * registry (diagnostic.rs:252-333). */
        fun fromValue(value: PortableValue, registry: ErrorCodeRegistry): Diagnostic {
            val fields = schemaFields(
                value,
                "core.diagnostic@1",
                listOf(
                    "schema", "code", "category", "severity", "primary", "related",
                    "arguments", "notes", "fixes", "occurrence",
                ),
                "$",
            )
            val code = stringOf(fields[1], "$.code")
            val category = parseDiagnosticCategory(stringOf(fields[2], "$.category"))
            val severity = parseSeverity(stringOf(fields[3], "$.severity"))
            val primary = if (fields[4] is PvNull) null else parseLocation(fields[4], "$.primary")
            val relatedValues = sequenceOf(fields[5], "$.related")
            val related = relatedValues.mapIndexed { index, item ->
                val path = "$.related[$index]"
                val entry = exactFields(item, listOf("role", "location"), path)
                RelatedSourceLocation(
                    stringOf(entry[0], "$path.role"),
                    parseLocation(entry[1], "$path.location"),
                )
            }
            val arguments = stringMapFromObject(fields[6], "$.arguments")
            val noteValues = sequenceOf(fields[7], "$.notes")
            val notes = noteValues.mapIndexed { index, note ->
                stringOf(note, "$.notes[$index]")
            }
            val fixValues = sequenceOf(fields[8], "$.fixes")
            val fixes = fixValues.mapIndexed { index, item ->
                decodeFix(item, "$.fixes[$index]")
            }
            val occurrence = unsigned64(fields[9], "$.occurrence")
            return of(code, category, severity, primary, related, arguments, notes, fixes, occurrence, registry)
        }
    }

    /** Encodes `core.diagnostic@1` (diagnostic.rs:187-250). */
    fun toValue(): PortableValue {
        val relatedValues = related.map { item ->
            PvObject(
                listOf(
                    consema.core.Entry("role", PvString(item.role)),
                    consema.core.Entry("location", locationValue(item.location)),
                ),
            )
        }
        val argumentsObject = stringMapObject(arguments)
        val notesArray = PvArray(notes.map { PvString(it) })
        val fixesArray = PvArray(fixes.map { fix ->
            // The wire replacement field is a Bytes leaf carried with full
            // byte fidelity; an empty replacement encodes as empty Bytes,
            // never Null.
            PvObject(
                listOf(
                    consema.core.Entry("id", PvString(fix.id)),
                    consema.core.Entry("applicability", PvString(fix.applicability.wireName)),
                    consema.core.Entry(
                        "location",
                        if (fix.location == null) PvNull else locationValue(fix.location),
                    ),
                    consema.core.Entry("replacement", PvBytes.of(fix.replacement)),
                ),
            )
        })
        return PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.diagnostic@1")),
                consema.core.Entry("code", PvString(code)),
                consema.core.Entry("category", PvString(category.wireName)),
                consema.core.Entry("severity", PvString(severity.wireName)),
                consema.core.Entry("primary", if (primary == null) PvNull else locationValue(primary)),
                consema.core.Entry("related", relatedValues.let { PvArray(it) }),
                consema.core.Entry("arguments", argumentsObject),
                consema.core.Entry("notes", notesArray),
                consema.core.Entry("fixes", fixesArray),
                consema.core.Entry("occurrence", integerValue(occurrence)),
            ),
        )
    }
}

/** Requires the code to be registered and its category to match the
 * registry record (diagnostic.rs:336-351). */
internal fun validateDiagnosticCode(
    code: String,
    category: DiagnosticCategory,
    registry: ErrorCodeRegistry,
) {
    val descriptor = registry.descriptor(code)
        ?: throw invalid("$.code", "unregistered public code: $code")
    if (descriptor.category != category) {
        throw invalid("$.category", "diagnostic category contradicts the error-code registry")
    }
}

/** Encodes one source location. */
private fun locationValue(location: SourceLocation): PortableValue =
    PvObject(
        listOf(
            consema.core.Entry("source_id", PvString(location.sourceId)),
            consema.core.Entry("start_byte", integerValue(location.startByte)),
            consema.core.Entry("end_byte", integerValue(location.endByte)),
        ),
    )

/** Strictly decodes one source location (diagnostic.rs:386-393). */
private fun parseLocation(value: PortableValue, path: String): SourceLocation {
    val fields = exactFields(value, listOf("source_id", "start_byte", "end_byte"), path)
    val sourceId = stringOf(fields[0], "$path.source_id")
    val startByte = unsigned64(fields[1], "$path.start_byte")
    val endByte = unsigned64(fields[2], "$path.end_byte")
    return SourceLocation.of(sourceId, startByte, endByte)
}

/** Strictly decodes one fix proposal (diagnostic.rs:395-431). The wire
 * replacement field is a Bytes leaf accepted at the value level with full
 * byte fidelity; any other shape (including Null) is a wrong-type error. */
private fun decodeFix(value: PortableValue, path: String): FixProposal {
    val fields = exactFields(
        value,
        listOf("id", "applicability", "location", "replacement"),
        path,
    )
    val id = stringOf(fields[0], "$path.id")
    val applicability = parseFixApplicability(stringOf(fields[1], "$path.applicability"))
    val location = if (fields[2] is PvNull) null else parseLocation(fields[2], "$path.location")
    val replacement = fields[3] as? PvBytes
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.replacement", "expected Bytes")
    return FixProposal(id, applicability, location, replacement.content())
}

/** Encodes a deterministic sorted Object<String, String>. */
internal fun stringMapObject(values: Map<String, String>): PortableValue {
    val entries = values.toSortedMap().map { (key, value) ->
        consema.core.Entry(key, PvString(value))
    }
    return PvObject(entries)
}

/** Decodes an Object<String, String>. */
internal fun stringMapFromObject(value: PortableValue, path: String): Map<String, String> {
    val objectValue = value as? PvObject
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected Object<String, String>")
    val output = LinkedHashMap<String, String>()
    for (entry in objectValue.entries()) {
        output[entry.key] = stringOf(entry.value, "$path.${entry.key}")
    }
    return output
}
