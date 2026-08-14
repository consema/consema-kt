// Typed formation failures of the TOML family.
//
// Data authority:
//   - RFC 0016 §5.1 F10 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md): a
//     formation failure carries the ordered diagnostics (code, category,
//     severity, span, arguments, notes, occurrence) with registry-bound
//     validation; TOML forms no partial Document.
//   - The frozen toml-family codes (https://github.com/consema/consema-rs/blob/main/consema-protocol/src/
//     error_registry.rs; transcribed verbatim into
//     kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt):
//       toml.edit.representation-fallback@1    Edit       0.2.0 (error_registry.rs)
//       toml.parse.syntax@1                    Syntax     0.2.0 (error_registry.rs)
//       toml.projection.core-invariant@1       Projection 0.2.0 (error_registry.rs)
//       toml.projection.unrepresentable-datetime@1 Projection 0.2.0 (error_registry.rs)
//   - The core codes used by formation (consema-document/src/lib.rs):
//       core.parse.resource-limit@1   (resource_limit, lib.rs)
//       core.source.invalid-utf8@1    (invalid_utf8, lib.rs)
//       core.source.invalid-sequence@1, encoding-conflict@1,
//       core.source.unsupported-bom@1, core.source.resource-limit@1
//       (source_error, lib.rs)
//   - The projection failure codes (consema-toml/src/projection.rs):
//       toml.projection.unrepresentable-datetime@1 / core.projection.
//       resource-limit@1 (argument "limit") / toml.projection.core-invariant@1.
//   - The edit codes (consema-toml/src/edit.rs, the StableFailure
//       diagnostic_code mapping; RFC 0004 §17 registers core.edit.*@1 in
//       error_registry.rs v3): core.edit.wrong-snapshot@1, wrong-role@1,
//       unsupported-value@1, invalid-literal@1, representation-incompatible@1,
//       exact-literal-requires-literal@1, conflicting-edits@1, target-not-
//       found@1, duplicate-key@1, operation-unsupported@1, resource-limit@1,
//       formation-failed@1.
//
// Kotlin-idiomatic design: the family carries its own immutable diagnostic
// record because the protocol `Diagnostic` primary location is wire-shaped
// (a caller-stable source ID is mandatory, kotlin/src/main/kotlin/consema/protocol/Diagnostic.kt) while
// formation/projection/edit diagnostics are snapshot-bound byte spans
// (DiagnosticLocation { snapshot: None, start_byte, end_byte } in the Rust
// crate). [TomlDiagnostic.toProtocolDiagnostic] maps a record to the
// transferable `core.diagnostic@1` form once a caller supplies the stable
// source ID and an error-code registry.

package consema.toml

import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.ErrorCodeRegistry
import consema.protocol.Severity
import consema.protocol.SourceLocation

/** The frozen toml-family edit-fallback code (error_registry.rs;
 * kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt). */
const val TOML_EDIT_REPRESENTATION_FALLBACK = "toml.edit.representation-fallback@1"

/** The frozen toml-family syntax code (error_registry.rs;
 * kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt). */
const val TOML_PARSE_SYNTAX = "toml.parse.syntax@1"

/** The frozen toml-family projection invariant code (error_registry.rs
 * ; kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt). */
const val TOML_PROJECTION_CORE_INVARIANT = "toml.projection.core-invariant@1"

/** The frozen toml-family projection temporal code (error_registry.rs
 * ; kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt). */
const val TOML_PROJECTION_UNREPRESENTABLE_DATETIME =
    "toml.projection.unrepresentable-datetime@1"

/** The frozen core formation resource-limit code (lib.rs). */
const val CORE_PARSE_RESOURCE_LIMIT = "core.parse.resource-limit@1"

/** The frozen core invalid-UTF-8 code (lib.rs). */
const val CORE_SOURCE_INVALID_UTF8 = "core.source.invalid-utf8@1"

/**
 * One ordered formation/projection/edit diagnostic with a snapshot-bound
 * primary byte span (RFC 0016 §5.1 F10). The [code], [category],
 * [severity], and [occurrence] are the frozen language-neutral facts; human
 * wording never participates in conformance comparison (RFC 0016 §6).
 */
data class TomlDiagnostic(
    /** The frozen registered code. */
    val code: String,
    /** The registered category. */
    val category: DiagnosticCategory,
    /** The presentation severity. */
    val severity: Severity,
    /** Inclusive start byte of the primary span over the raw source, or
     * null when no span applies. */
    val startByte: Int?,
    /** Exclusive end byte of the primary span, or null. */
    val endByte: Int?,
    /** Deterministic stable arguments. */
    val arguments: Map<String, String>,
    /** Stable note texts. */
    val notes: List<String>,
    /** The deterministic occurrence ordinal. */
    val occurrence: Long,
) {
    /**
     * Maps to the transferable `core.diagnostic@1` record with a
     * caller-supplied stable source identity (RFC 0016 §5.1 F10: the
     * registry-bound validation of the protocol layer applies).
     */
    fun toProtocolDiagnostic(
        sourceId: String,
        registry: ErrorCodeRegistry,
    ): Diagnostic {
        val primary = if (startByte == null || endByte == null) {
            null
        } else {
            SourceLocation.of(
                sourceId,
                startByte.toLong().toULong(),
                endByte.toLong().toULong(),
            )
        }
        return Diagnostic.of(
            code = code,
            category = category,
            severity = severity,
            primary = primary,
            related = emptyList(),
            arguments = arguments,
            notes = notes,
            fixes = emptyList(),
            occurrence = occurrence.toULong(),
            registry = registry,
        )
    }
}

/**
 * The typed fatal formation failure; no Document exists (RFC 0001 §3;
 * FatalFormationFailure, consema-document/src/lib.rs). The stable
 * [code] is the first diagnostic's frozen registered code.
 */
class TomlFormationException(
    /** Ordered diagnostics explaining why no Document exists
     * (lib.rs). */
    val diagnostics: List<TomlDiagnostic>,
) : Exception("toml formation: ${diagnostics.firstOrNull()?.code}") {
    /** The frozen registered code of the first diagnostic. */
    val code: String
        get() = diagnostics.first().code
}

/** Builds one resource-limit diagnostic (lib.rs: code
 * core.parse.resource-limit@1 with arguments limit/name/observed). */
internal fun resourceLimitDiagnostic(
    name: String,
    observed: Int,
    limit: Int,
): TomlDiagnostic = TomlDiagnostic(
    code = CORE_PARSE_RESOURCE_LIMIT,
    category = DiagnosticCategory.Resource,
    severity = Severity.Error,
    startByte = null,
    endByte = null,
    arguments = mapOf(
        "limit" to limit.toString(),
        "name" to name,
        "observed" to observed.toString(),
    ),
    notes = emptyList(),
    occurrence = 0,
)

/** Builds the invalid-UTF-8 diagnostic (lib.rs: primary at the
 * valid prefix boundary). */
internal fun invalidUtf8Diagnostic(validUpTo: Int): TomlDiagnostic = TomlDiagnostic(
    code = CORE_SOURCE_INVALID_UTF8,
    category = DiagnosticCategory.Lexical,
    severity = Severity.Error,
    startByte = validUpTo,
    endByte = validUpTo,
    arguments = emptyMap(),
    notes = emptyList(),
    occurrence = 0,
)

/** Builds one syntax diagnostic (consema-toml/src/parser.rs: code
 * toml.parse.syntax@1 with the stable `parser_reason` argument and the
 * provable minimal primary span). */
internal fun syntaxDiagnostic(
    startByte: Int,
    endByte: Int,
    reason: String,
): TomlDiagnostic = TomlDiagnostic(
    code = TOML_PARSE_SYNTAX,
    category = DiagnosticCategory.Syntax,
    severity = Severity.Error,
    startByte = startByte,
    endByte = endByte,
    arguments = mapOf("parser_reason" to reason),
    notes = emptyList(),
    occurrence = 0,
)

/** One parser error: a provable minimal span plus a stable reason
 * (RFC 0001 §3; the Go parser's parseError is a cross-reference). */
internal data class ParseError(
    val startByte: Int,
    val endByte: Int,
    val reason: String,
) : Exception(reason)
