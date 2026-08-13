// Typed protocol failures.
//
// Data authority: the frozen `core.protocol.*@1` codes are registered in the
// semantic-model v1 error registry (consema-rs/consema-protocol/src/
// error_registry.rs:72-90 area; see ErrorRegistry.kt) and mapped from the
// Rust ProtocolErrorKind (consema-rs/consema-protocol/src/error.rs).
// consema-go/go/protocol/errors.go is a cross-reference.
//
// Kotlin-idiomatic error handling: transport and record-level failures are
// [ProtocolException] carrying the frozen `code` and a JSON-pointer-ish
// [path] ("$.files[0].source_digest") mirroring the error paths so that the
// shared vectors' error_path facts match.

package consema.protocol

/**
 * The strict protocol failures shared by the canonical JSON and PVCE/1
 * transports and the fixed-field record decoders. Each kind maps to one
 * frozen registered code (see [ProtocolException.code]).
 */
enum class ProtocolErrorKind(val code: String) {
    /** The transport document is not valid JSON. */
    INVALID_JSON("core.protocol.invalid-json@1"),

    /** The document is valid JSON but not the canonical byte form. */
    NON_CANONICAL_JSON("core.protocol.non-canonical-json@1"),

    /** The PVCE/1 stream is structurally invalid, or carries a record with
     * no representation in the closed fifteen-kind model (only the extended
     * 0x7f record). */
    INVALID_PVCE("core.protocol.invalid-pvce@1"),

    /** The contract ID or version is not registered. */
    UNKNOWN_CONTRACT("core.protocol.unknown-contract@1"),

    /** The schema discriminator or field order does not match the fixed
     * record schema. */
    SCHEMA_MISMATCH("core.protocol.schema-mismatch@1"),

    /** A fixed schema contains an undeclared field. */
    UNKNOWN_FIELD("core.protocol.unknown-field@1"),

    /** A required field is absent. */
    MISSING_FIELD("core.protocol.missing-field@1"),

    /** A field has the wrong value type. */
    WRONG_TYPE("core.protocol.wrong-type@1"),

    /** A field value violates its invariant. */
    INVALID_VALUE("core.protocol.invalid-value@1"),

    /** A protocol resource limit was reached. */
    RESOURCE_LIMIT("core.protocol.resource-limit@1"),

    /** A process-local handle cannot cross the wire. */
    PROCESS_LOCAL_HANDLE("core.protocol.process-local-handle@1"),
}

/**
 * The typed protocol failure (transport or record level). The stable [code]
 * is always the registered code, so cross-language error-code parity holds
 * (RFC 0016 §6). [path] names the failing JSON-pointer-ish location
 * ("$.files[0].source_digest"); [detail] is the human-facing explanation,
 * never part of conformance comparison.
 */
class ProtocolException(
    val kind: ProtocolErrorKind,
    val path: String,
    val detail: String,
) : Exception("protocol: ${kind.code} at $path: $detail")

/** The frozen resource-limit protocol code (RFC 0015 §5.2:
 * `core.protocol.resource-limit@1` classifies as exit 3). */
fun resourceLimitCode(): String = ProtocolErrorKind.RESOURCE_LIMIT.code

/** Builds the INVALID_VALUE protocol failure (crate::schema::invalid). */
internal fun invalid(path: String, detail: String): ProtocolException =
    ProtocolException(ProtocolErrorKind.INVALID_VALUE, path, detail)

/** Builds the RESOURCE_LIMIT protocol failure. */
internal fun resource(path: String, detail: String): ProtocolException =
    ProtocolException(ProtocolErrorKind.RESOURCE_LIMIT, path, detail)

/** Builds a protocol failure with an explicit kind. */
internal fun protocolError(kind: ProtocolErrorKind, path: String, detail: String): ProtocolException =
    ProtocolException(kind, path, detail)
