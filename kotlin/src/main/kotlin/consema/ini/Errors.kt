// Typed failures of INI-family formation and access, and the internal
// diagnostic factory.
//
// Data authority:
//   - https://github.com/consema/consema-rs/blob/main/consema-document/src/lib.rs pins FatalFormationFailure
//     and its code mapping: resource limits use "core.parse.resource-limit@1"
//     (lib.rs), source construction failures map through
//     FatalFormationFailure::source_error (lib.rs) to
//     core.source.invalid-utf8@1 / invalid-sequence@1 / encoding-conflict@1 /
//     unsupported-bom@1 / resource-limit@1; the INI profile-encoding failure
//     is the frozen "ini.profile.encoding@1" (parser.rs).
//   - https://github.com/consema/consema-rs/blob/main/consema-ini/src/parser.rs pins the diagnostic sink:
//     occurrence ordinals, Error severity for recovery, Warning otherwise,
//     and the "diagnostics" limit that fails the whole parse fatally
//     (parser.rs); parser.rs sorts diagnostics
//     deterministically (consema-core/src/diagnostic.rs).
//   - RFC 0016 §6 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md): SDK errors
//     carry the stable registered code; error text is human presentation only.
//   - The registered ini-family codes are transcribed in
//     kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt (v7 registry).
//
// Kotlin-idiomatic design: fatal formation failure is a typed exception
// carrying the frozen registered code (the established consema.core /
// consema.document style); the diagnostic factory binds the current (v7)
// error registry because the v7 array is the ordered superset containing
// every ini-family code.

package consema.ini

import consema.document.DocumentAuthority
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.RelatedSourceLocation
import consema.protocol.Severity
import consema.protocol.SourceLocation

/** Registry bound to every diagnostic this package constructs: the
 * semantic-model v7 registry (187 codes), the ordered superset containing
 * all ini-family codes (kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt). */
internal val INI_DIAGNOSTIC_REGISTRY: ErrorCodeRegistry =
    ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)

/**
 * The fatal formation failure (lib.rs). Exceeding a parse limit is a
 * ResourceLimit failure carrying the frozen limit code; a fatal failure
 * returns no Document and no partial snapshot (RFC 0009 §4).
 */
class IniFormationException(
    /** The frozen registered code of the failure. */
    val code: String,
    message: String,
    /** Stable limit name (RESOURCE_LIMIT). */
    val name: String = "",
    /** Observed amount (RESOURCE_LIMIT). */
    val observed: Int? = null,
    /** Configured maximum (RESOURCE_LIMIT). */
    val limit: Int? = null,
    /** Wrapped source construction failure (SOURCE_* / profile.encoding). */
    override val cause: Exception? = null,
) : Exception(message, cause)

/** Resource-limit fatal failure (lib.rs; error_registry.rs). */
internal fun resourceLimit(name: String, observed: Int, limit: Int): IniFormationException =
    IniFormationException(
        "core.parse.resource-limit@1",
        "ini parse: $name limit reached ($observed > $limit)",
        name = name,
        observed = observed,
        limit = limit,
    )

/**
 * Stable typed INI access failure. The [name] spellings are the
 * language-neutral comparison facts; these names are NOT registered error
 * codes.
 */
enum class IniAccessErrorKind {
    /** NodeRef belongs to another snapshot. */
    WrongSnapshot,

    /** NodeRef role cannot be used by this operation. */
    WrongRole,

    /** Index is not present in this snapshot. */
    UnknownNode,
}

/** The typed INI access failure (lib.rs). */
class IniAccessException(val kind: IniAccessErrorKind) :
    Exception("ini access: ${kind.name}")

/**
 * Builds one snapshot-bound diagnostic in the `core.diagnostic@1` shape.
 * The primary source location uses the process-local snapshot identity as
 * the caller-stable source ID, mirroring the JSON family factory
 * (kotlin/src/main/kotlin/consema/json/Errors.kt).
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
        INI_DIAGNOSTIC_REGISTRY,
    )
}

/**
 * Ordered diagnostic collection with an explicit hard bound
 * (parser.rs). Unlike the JSON family sink, exceeding
 * `max_diagnostics` is FATAL: the INI parser checks the limit before every
 * push (parser.rs) and never emits a truncation marker
 * (conformance/vectors/ini-v1.json resource.formation-limit-matrix
 * max_diagnostics case expects a fatal outcome).
 */
internal class DiagnosticSink(private val max: Int) {
    private val diagnostics = ArrayList<Diagnostic>()
    private var occurrenceCounter = 0uL

    /** The occurrence ordinal the next push will assign (parser.rs). */
    fun nextOccurrence(): ULong = occurrenceCounter

    fun push(diagnostic: Diagnostic) {
        val observed = diagnostics.size + 1
        if (observed > max) {
            throw resourceLimit("diagnostics", observed, max)
        }
        occurrenceCounter = occurrenceCounter.inc()
        diagnostics.add(diagnostic)
    }

    fun finish(): List<Diagnostic> = diagnostics
}

/**
 * Deterministic diagnostic order (consema-core/src/diagnostic.rs):
 * primary start (missing primary sorts last), category, code, occurrence.
 */
internal val deterministicDiagnosticOrder: Comparator<Diagnostic> =
    compareBy<Diagnostic> { it.primary?.startByte ?: ULong.MAX_VALUE }
        .thenBy { it.category.ordinal }
        .thenBy { it.code }
        .thenBy { it.occurrence }
