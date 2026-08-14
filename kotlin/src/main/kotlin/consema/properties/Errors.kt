// Typed failures of Java Properties formation and access, and the internal
// diagnostic factory.
//
// Data authority:
//   - The registered properties-family codes are transcribed in
//     kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt from
//     https://github.com/consema/consema-rs/blob/main/consema-protocol/src/error_registry.rs (the twelve
//     0.8.0 codes: java-properties.edit.canonical-fallback@1,
//     java-properties.edit.invalid-placement@1,
//     java-properties.java-string.invalid-wire@1,
//     java-properties.java-string.non-canonical-wire@1,
//     java-properties.materialization.round-trip-mismatch@1,
//     java-properties.parse.malformed-unicode-escape@1,
//     java-properties.profile.mismatch@1,
//     java-properties.projection.duplicate-collapsed@1,
//     java-properties.projection.incomplete-document@1,
//     java-properties.projection.unpaired-surrogate@1,
//     java-properties.query.invalid-code-unit-filter@1,
//     java-properties.source.profile-encoding@1). The java-string.*, round-
//     trip-mismatch, profile.mismatch, and invalid-code-unit-filter codes
//     are wire/conformance-side facts (RFC 0010 §15: stable codes cover
//     Java-string conversion, projection duplicate policy, and protocol
//     exchange); the parse/edit/projection/source codes are raised by this
//     package.
//   - Fatal formation failures use the frozen core.parse.resource-limit@1
//     (error_registry.rs) and core.source.* codes (source_v1.rs), mapped in Encoding.kt.
//   - RFC 0016 §6 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md): SDK errors
//     carry the stable registered code; error text is human presentation only.
//
// Kotlin-idiomatic design: fatal formation failure is a typed exception
// carrying the frozen registered code (the established consema.core/
// consema.document style); the diagnostic factory binds the current (v7)
// error registry because the v7 array is the ordered superset containing
// every properties-family code (kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt).

package consema.properties

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
 * properties-family codes (kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt). The L5 conformance
 * runner may rebind per-suite registry versions.
 */
internal val PROPERTIES_DIAGNOSTIC_REGISTRY: ErrorCodeRegistry =
    ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)

/**
 * The fatal formation failure of the properties parser (the Rust
 * FatalFormationFailure, lib.rs). Exceeding a parse limit is a
 * ResourceLimit failure carrying the frozen limit code and the stable limit
 * name (RFC 0016 §5.1); source construction failures carry their core.source
 * code. A fatal failure returns no Document and no partial snapshot.
 */
class PropertiesFormationException(
    /** The frozen registered code of the failure. */
    val code: String,
    message: String,
    /** Stable limit name (core.parse.resource-limit@1 / core.source.*). */
    val name: String = "",
    /** Observed amount (RESOURCE_LIMIT). */
    val observed: Int? = null,
    /** Configured maximum (RESOURCE_LIMIT). */
    val limit: Int? = null,
    /** Wrapped source construction failure (core.source.*). */
    override val cause: Exception? = null,
) : Exception(message, cause)

/** Resource-limit fatal failure (parser.rs; error_registry.rs). */
internal fun propertiesResourceLimit(name: String, observed: Int, limit: Int): PropertiesFormationException =
    PropertiesFormationException(
        "core.parse.resource-limit@1",
        "properties parse: $name limit reached ($observed > $limit)",
        name = name,
        observed = observed,
        limit = limit,
    )

/**
 * Stable typed properties access failure. The [name] spellings are the
 * language-neutral comparison facts; these names are NOT registered error
 * codes.
 */
enum class PropertiesAccessErrorKind {
    /** NodeRef belongs to another snapshot. */
    WrongSnapshot,

    /** NodeRef role cannot be used by this operation. */
    WrongRole,

    /** Index is not present in this snapshot. */
    UnknownNode,
}

/** The typed properties access failure. */
class PropertiesAccessException(val kind: PropertiesAccessErrorKind) :
    Exception("properties access: ${kind.name}")

/**
 * Builds one snapshot-bound diagnostic in the `core.diagnostic@1` shape.
 * The primary source location uses the process-local snapshot identity as
 * the caller-stable source ID (the same convention as the JSON family,
 * kotlin/src/main/kotlin/consema/json/Errors.kt).
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
        PROPERTIES_DIAGNOSTIC_REGISTRY,
    )
}
