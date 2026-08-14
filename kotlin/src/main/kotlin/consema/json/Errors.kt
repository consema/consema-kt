// Typed failures of JSON-family formation and access, and the internal
// diagnostic factory.
//
// Data authority:
//   - https://github.com/consema/consema-rs/blob/main/consema-document/src/lib.rs pins FatalFormationFailure
//     and its code mapping: resource limits use "core.parse.resource-limit@1"
//     (lib.rs), source construction failures map through
//     FatalFormationFailure::source_error (lib.rs) to
//     core.source.invalid-utf8@1 / invalid-sequence@1 / encoding-conflict@1 /
//     unsupported-bom@1 / resource-limit@1.
//   - https://github.com/consema/consema-rs/blob/main/consema-json/src/lib.rs pins JsonAccessError
//     (WrongSnapshot, WrongRole, UnknownNode).
//   - The registered codes are transcribed in
//     kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt (core.parse.resource-limit@1 at
//     kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt; core.source.* at kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt).
//   - RFC 0016 §6 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md): SDK errors
//     carry the stable registered code; error text is human presentation only.
//
// Kotlin-idiomatic design: fatal formation failure is a typed exception
// carrying the frozen registered code (the established
// consema.core/consema.document style); the diagnostic factory binds the
// current (v7) error registry because the v7 array is the ordered superset
// containing every JSON-family code (kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt).

package consema.json

import consema.document.DocumentAuthority
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.RelatedSourceLocation
import consema.protocol.Severity
import consema.protocol.SourceLocation

/**
 * Registry bound to every diagnostic this package constructs: the semantic-
 * model v7 registry (187 codes), the ordered superset containing all
 * JSON-family codes (kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt). The L5 conformance runner may
 * rebind per-suite registry versions.
 */
internal val JSON_DIAGNOSTIC_REGISTRY: ErrorCodeRegistry =
    ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)

/**
 * The fatal formation failure (lib.rs). Exceeding a parse limit is a
 * ResourceLimit failure carrying the frozen limit code
 * (RFC 0016 §5.1, https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md). A fatal failure
 * returns no Document and no partial snapshot (RFC 0003 §12).
 */
class JsonFormationException(
    /** The frozen registered code of the failure. */
    val code: String,
    message: String,
    /** Stable limit name (RESOURCE_LIMIT). */
    val name: String = "",
    /** Observed amount (RESOURCE_LIMIT). */
    val observed: Int? = null,
    /** Configured maximum (RESOURCE_LIMIT). */
    val limit: Int? = null,
    /** Wrapped source construction failure (SOURCE_*). */
    override val cause: Exception? = null,
) : Exception(message, cause)

/** Resource-limit fatal failure (lib.rs; error_registry.rs). */
internal fun resourceLimit(name: String, observed: Int, limit: Int): JsonFormationException =
    JsonFormationException(
        "core.parse.resource-limit@1",
        "json parse: $name limit reached ($observed > $limit)",
        name = name,
        observed = observed,
        limit = limit,
    )

/**
 * Stable typed JSON access failure (lib.rs). The [name] spellings are
 * the language-neutral comparison facts; these names are NOT registered error
 * codes.
 */
enum class JsonAccessErrorKind {
    /** NodeRef belongs to another snapshot. */
    WrongSnapshot,

    /** NodeRef role cannot be used by this operation. */
    WrongRole,

    /** Index is not present in this snapshot. */
    UnknownNode,
}

/** The typed JSON access failure (lib.rs). */
class JsonAccessException(val kind: JsonAccessErrorKind) :
    Exception("json access: ${kind.name}")

/**
 * Builds one snapshot-bound diagnostic in the `core.diagnostic@1` shape.
 * The primary source location uses the process-local snapshot identity as the
 * caller-stable source ID (the Kotlin `core.diagnostic@1` source_id field
 * requires a stable string; the Rust `DiagnosticLocation` embeds the snapshot
 * ordinal, lib.rs).
 */
internal fun sourceDiagnostic(
    authority: DocumentAuthority,
    code: String,
    category: DiagnosticCategory,
    severity: Severity,
    start: Int,
    end: Int,
    occurrence: ULong,
    arguments: Map<String, String> = emptyMap(),
    related: List<RelatedSourceLocation> = emptyList(),
): Diagnostic {
    val location = SourceLocation.of(
        authority.identity.asU64.toString(),
        start.toULong(),
        end.toULong(),
    )
    return Diagnostic.of(
        code,
        category,
        severity,
        location,
        related,
        arguments,
        emptyList(),
        emptyList(),
        occurrence,
        JSON_DIAGNOSTIC_REGISTRY,
    )
}
