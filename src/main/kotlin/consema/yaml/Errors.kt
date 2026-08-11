// Typed failures of YAML-family formation and access, and the internal
// diagnostic factory.
//
// Data authority:
//   - crates/consema-document/src/lib.rs:643-790 pins FatalFormationFailure
//     and its code mapping: resource limits use "core.parse.resource-limit@1"
//     (lib.rs:771-776), source construction failures map through
//     FatalFormationFailure::source_error (lib.rs:676-707).
//   - The YAML-specific registered codes are frozen by
//     crates/consema-protocol/src/error_registry.rs:729-932 (yaml.alias.*,
//     yaml.anchor.*, yaml.edit.*, yaml.mapping.*, yaml.materialization.*,
//     yaml.native.*, yaml.parse.syntax@1 at :850-854, yaml.profile.*,
//     yaml.projection.*, yaml.scalar.*, yaml.tag.*) and transcribed in
//     kotlin/.../protocol/ErrorRegistry.kt:291-324.
//   - crates/consema-yaml/src/lib.rs:789-858 maps version-directive and
//     backend-syntax failures (yaml.profile.version-directive@1 at
//     lib.rs:811-827; yaml.parse.syntax@1 at lib.rs:849-855);
//     crates/consema-yaml/src/native.rs:1148-1157 maps the composition
//     failures (yaml.native.*, yaml.anchor.*, yaml.alias.*,
//     yaml.mapping.missing-value@1, yaml.tag.kind-mismatch@1,
//     yaml.scalar.invalid-explicit-tag@1).
//   - RFC 0016 §6 (docs/rfcs/0016-go-api-mapping-v1.md:194-200): SDK errors
//     carry the stable registered code; error text is human presentation only.
//
// Kotlin-idiomatic design: fatal formation failure is a typed exception
// carrying the frozen registered code (the established
// consema.core/consema.document style); composition failures are thrown as
// [YamlFormationException] so the parse entry point can attach the exact
// source location when one is available.

package consema.yaml

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
 * YAML-family codes (kotlin/.../protocol/ErrorRegistry.kt:291-324). The L5
 * conformance runner may rebind per-suite registry versions.
 */
internal val YAML_DIAGNOSTIC_REGISTRY: ErrorCodeRegistry =
    ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)

/**
 * The fatal formation failure (lib.rs:643-663). Exceeding a parse limit is a
 * ResourceLimit failure carrying the frozen limit code (RFC 0016 §5.1). A
 * fatal failure returns no Document and no partial snapshot (RFC 0007 §4).
 */
class YamlFormationException(
    /** The frozen registered code of the failure. */
    val code: String,
    message: String,
    /** Stable limit name (RESOURCE_LIMIT). */
    val name: String = "",
    /** Observed amount (RESOURCE_LIMIT). */
    val observed: Int? = null,
    /** Configured maximum (RESOURCE_LIMIT). */
    val limit: Int? = null,
    /** Exact decoded-scalar offset of a syntax failure. */
    val scalarOffset: Int? = null,
    /** Wrapped source construction failure. */
    override val cause: Exception? = null,
) : Exception(message, cause)

/** Resource-limit fatal failure (lib.rs:771-790; error_registry.rs:38-43). */
internal fun resourceLimit(name: String, observed: Int, limit: Int): YamlFormationException =
    YamlFormationException(
        "core.parse.resource-limit@1",
        "yaml parse: $name limit reached ($observed > $limit)",
        name = name,
        observed = observed,
        limit = limit,
    )

/** One composition failure thrown with its frozen native code (native.rs:
 * 1148-1157). */
internal fun nativeFailure(code: String): YamlFormationException =
    YamlFormationException(code, "yaml native: $code")

/**
 * Stable typed YAML access failure (lib.rs:612-621 equivalent for YAML
 * handles). The [name] spellings are the language-neutral comparison facts;
 * these names are NOT registered error codes.
 */
enum class YamlAccessErrorKind {
    /** NodeRef belongs to another snapshot. */
    WrongSnapshot,

    /** NodeRef role cannot be used by this operation. */
    WrongRole,

    /** Index is not present in this snapshot. */
    UnknownNode,
}

/** The typed YAML access failure. */
class YamlAccessException(val kind: YamlAccessErrorKind) :
    Exception("yaml access: ${kind.name}")

/**
 * Builds one snapshot-bound diagnostic in the `core.diagnostic@1` shape
 * (the Kotlin `core.diagnostic@1` source_id field requires a stable string;
 * the Rust `DiagnosticLocation` embeds the snapshot ordinal, so the Kotlin
 * identity ordinal is used as the stable caller source id).
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
        YAML_DIAGNOSTIC_REGISTRY,
    )
}
