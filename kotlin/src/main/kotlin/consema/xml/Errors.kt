// Typed failures of XML formation and the diagnostic factory.
//
// Data authority:
//   - https://github.com/consema/consema-rs/blob/main/consema-xml/src/parser.rs (source_failure), parser.rs
//     and 106-108 (profile_failure), parser.rs (the two fatal
//     failure constructors), parser.rs (recover), parser.rs
// (recover_error_region), parser.rs (finish
//     diagnostics), parser.rs (limit). Fatal failures carry the
//     frozen xml.* codes (`xml.source.decoding@1`, `xml.profile.encoding@1`,
//     `xml.profile.unknown@1`, `xml.limit.*`, `xml.source.span@1`, ...) and
//     source construction failures map through
//     FatalFormationFailure::source_error (https://github.com/consema/consema-rs/blob/main/consema-document/src/
//     lib.rs) to the registered core.source.* codes.
//   - RFC 0016 §6 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md): SDK errors
//     carry the stable registered code; error text is human presentation
//     only.
//   - https://github.com/consema/consema-rs/blob/main/consema-core/src/diagnostic.rs pins the deterministic
//     diagnostic sort: (primary.start_byte or u64::MAX, category, code,
//     occurrence); the Kotlin DiagnosticCategory enum order
//     (kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt) is identical to the Rust declaration
//     order (diagnostic.rs).
//
// Kotlin-idiomatic design: fatal formation failure is a typed exception
// carrying the frozen code (the established consema.document/consema.json
// style); the xml diagnostic factory constructs [XmlDiagnostic] records
// directly because the xml.* codes are part of the xml.1.0-safe@1 contract
// and not registered in the consema-protocol core registry (RFC 0012 §12;
// see Document.kt header).

package consema.xml

import consema.document.DocumentAuthority
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity
import consema.protocol.SourceLocation

/**
 * The fatal XML formation failure (parser.rs; FatalFormationFailure
 * in consema-document lib.rs). A fatal failure returns no Document
 * and no partial snapshot (RFC 0003 §12). Exceeding a parse limit is a
 * ResourceLimit failure carrying the frozen limit code (RFC 0016 §5.1).
 */
class XmlFormationException(
    /** The frozen code of the failure. */
    val code: String,
    message: String,
    /** Stable limit name (xml.limit.*). */
    val name: String = "",
    /** Observed amount (xml.limit.*). */
    val observed: Int? = null,
    /** Configured maximum (xml.limit.*). */
    val limit: Int? = null,
    /** Wrapped source construction failure (SOURCE_*). */
    override val cause: Exception? = null,
) : Exception(message, cause)

/**
 * Builds one snapshot-bound [XmlDiagnostic] in the `core.diagnostic@1`
 * shape. The primary source location uses the process-local snapshot
 * identity as the caller-stable source ID (mirroring the json family
 * sourceDiagnostic, kotlin/src/main/kotlin/consema/json/Errors.kt; the Rust
 * DiagnosticLocation embeds the snapshot ordinal, consema-core/src/
 * diagnostic.rs).
 */
internal fun sourceDiagnostic(
    authority: DocumentAuthority,
    code: String,
    category: DiagnosticCategory,
    severity: Severity,
    start: Int,
    end: Int,
    occurrence: ULong,
): XmlDiagnostic {
    val location = SourceLocation.of(
        authority.identity.asU64.toString(),
        start.toULong(),
        end.toULong(),
    )
    return XmlDiagnostic(code, category, severity, location, occurrence)
}

/**
 * Deterministic diagnostic order (diagnostic.rs): primary start
 * byte (documents without a primary sort last), then category, code, then
 * the final occurrence ordinal.
 */
internal fun deterministicDiagnosticOrder(
    left: XmlDiagnostic,
    right: XmlDiagnostic,
): Int {
    val leftStart = left.primary?.startByte ?: ULong.MAX_VALUE
    val rightStart = right.primary?.startByte ?: ULong.MAX_VALUE
    return leftStart.compareTo(rightStart)
        .takeIf { it != 0 }
        ?: left.category.ordinal.compareTo(right.category.ordinal)
            .takeIf { it != 0 }
            ?: left.code.compareTo(right.code)
                .takeIf { it != 0 }
                ?: left.occurrence.compareTo(right.occurrence)
}

/** The same deterministic order for the argument-carrying variant. */
internal fun deterministicDiagnosticOrderWithArguments(
    left: XmlDiagnosticWithArguments,
    right: XmlDiagnosticWithArguments,
): Int {
    val leftStart = left.primary?.startByte ?: ULong.MAX_VALUE
    val rightStart = right.primary?.startByte ?: ULong.MAX_VALUE
    return leftStart.compareTo(rightStart)
        .takeIf { it != 0 }
        ?: left.category.ordinal.compareTo(right.category.ordinal)
            .takeIf { it != 0 }
            ?: left.code.compareTo(right.code)
                .takeIf { it != 0 }
                ?: left.occurrence.compareTo(right.occurrence)
}
