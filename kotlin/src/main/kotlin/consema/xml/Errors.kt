// Typed failures of XML formation and the diagnostic factory.
//
// Data authority:
//   - crates/consema-xml/src/parser.rs:44-45 (source_failure), parser.rs:73
//     and 106-108 (profile_failure), parser.rs:110-128 (the two fatal
//     failure constructors), parser.rs:1731-1749 (recover), parser.rs:
//     1759-1786 (recover_error_region), parser.rs:1792-1811 (finish
//     diagnostics), parser.rs:2015-2020 (limit). Fatal failures carry the
//     frozen xml.* codes (`xml.source.decoding@1`, `xml.profile.encoding@1`,
//     `xml.profile.unknown@1`, `xml.limit.*`, `xml.source.span@1`, ...) and
//     source construction failures map through
//     FatalFormationFailure::source_error (crates/consema-document/src/
//     lib.rs:676-707) to the registered core.source.* codes.
//   - RFC 0016 §6 (docs/rfcs/0016-go-api-mapping-v1.md:194-200): SDK errors
//     carry the stable registered code; error text is human presentation
//     only.
//   - crates/consema-core/src/diagnostic.rs:107-123 pins the deterministic
//     diagnostic sort: (primary.start_byte or u64::MAX, category, code,
//     occurrence); the Kotlin DiagnosticCategory enum order
//     (protocol/ErrorRegistry.kt:22-34) is identical to the Rust declaration
//     order (diagnostic.rs:7-33).
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
 * The fatal XML formation failure (parser.rs:110-128; FatalFormationFailure
 * in consema-document lib.rs:643-663). A fatal failure returns no Document
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
 * sourceDiagnostic, kotlin/.../json/Errors.kt:102-130; the Rust
 * DiagnosticLocation embeds the snapshot ordinal, consema-core/src/
 * diagnostic.rs:33-63).
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
 * Deterministic diagnostic order (diagnostic.rs:107-123): primary start
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
