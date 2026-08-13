// Typed PVCE/1 codec failures.
//
// Data authority: the frozen `core.pvce.*@1` codes are transcribed from the
// Rust StableFailure mapping (consema-rs/consema-pvce/src/lib.rs:1062-1087);
// each kind's semantics follow the Rust DecodeError/EncodeError enums
// (lib.rs:981-1028). Note: these codes are NOT entries of the public
// error-code registry (consema-rs/consema-protocol/src/error_registry.rs
// registers core.pgce.* but no core.pvce.* — verified by grep); they are the
// stable diagnostic codes of the codec itself.
//
// Kotlin-idiomatic error handling: the codec reports failures by throwing
// [PvceException] (checked-by-convention exceptions carrying the frozen
// `code`), instead of Go-style error returns. Error text is human
// presentation only (RFC 0016 §6: error text never participates in
// conformance comparison); the `code` property is the normative fact.

package consema.core

/**
 * The strict PVCE/1 failure kinds of the encoder and decoder. Every kind
 * maps to one frozen registered code (see [PvceErrorKind.code]).
 */
enum class PvceErrorKind(val code: String) {
    /** The stream magic did not match "PVCE". */
    INVALID_MAGIC("core.pvce.invalid-magic@1"),

    /** The encoding version is not 1. */
    UNSUPPORTED_VERSION("core.pvce.unsupported-version@1"),

    /** Input ended inside a required field. */
    UNEXPECTED_END("core.pvce.unexpected-end@1"),

    /** Bytes followed the root record. */
    TRAILING_BYTES("core.pvce.trailing-bytes@1"),

    /** Bytes followed a fully decoded record payload. */
    TRAILING_PAYLOAD("core.pvce.trailing-payload@1"),

    /** Bytes followed a fully decoded nested field. */
    TRAILING_FIELD("core.pvce.trailing-field@1"),

    /** An unsigned varint was not shortest-form. */
    NON_CANONICAL_VARINT("core.pvce.non-canonical-varint@1"),

    /** An unsigned varint exceeded 64 bits. */
    VARINT_OVERFLOW("core.pvce.varint-overflow@1"),

    /** A length did not fit the host address space. */
    LENGTH_OVERFLOW("core.pvce.length-overflow@1"),

    /**
     * A declared resource limit was reached; [PvceException.field] names the
     * limit ("stream-bytes", "nesting-depth", "value-nodes",
     * "container-entries", "integer-bytes", "blob-bytes", "record-bytes",
     * "integer-field", "decimal-field", "date-field", "time-field").
     */
    RESOURCE_LIMIT("core.pvce.resource-limit@1"),

    /** The record tag has no representation in the closed fifteen-kind
     * model (including the Rust extended tag 0x7f). */
    UNKNOWN_CORE_TAG("core.pvce.unknown-tag@1"),

    /** A fixed-size payload did not match its tag. */
    INVALID_PAYLOAD("core.pvce.invalid-payload@1"),

    /** The integer sign octet is not in the v1 registry (0 zero, 1
     * positive, 2 negative). */
    INVALID_INTEGER_SIGN("core.pvce.invalid-integer-sign@1"),

    /** The integer representation was not the unique canonical form (zero
     * sign with magnitude, or a leading zero octet). */
    NON_CANONICAL_INTEGER("core.pvce.non-canonical-integer@1"),

    /** The decimal coefficient/exponent pair was not normalized (trailing
     * decimal zeros, or a zero coefficient with a non-zero exponent). */
    NON_CANONICAL_DECIMAL("core.pvce.non-canonical-decimal@1"),

    /** String bytes were not valid UTF-8. */
    INVALID_UTF8("core.pvce.invalid-utf8@1"),

    /** An object key record was not a String record. */
    OBJECT_KEY_NOT_STRING("core.pvce.object-key-not-string@1"),

    /** An object contained a duplicate key. */
    DUPLICATE_OBJECT_KEY("core.pvce.duplicate-object-key@1"),

    /** A nil or otherwise invalid Value was passed to the codec. */
    INVALID_VALUE("core.pvce.invalid-value@1"),

    /** Date, time, or offset fields were outside the supported ranges (the
     * Rust DecodeError::InvalidTemporal and the construction failures,
     * consema-rs/consema-pvce/src/lib.rs:971-979). */
    INVALID_TEMPORAL("core.pvce.invalid-temporal@1"),
}

/**
 * The typed PVCE/1 codec failure (encode or decode). The stable [code] is
 * always the registered code, so cross-language error-code parity holds
 * (RFC 0016 §6).
 */
open class PvceException(
    val kind: PvceErrorKind,
    message: String,
    /** Names the resource-limit field (see [PvceErrorKind.RESOURCE_LIMIT]);
     * empty otherwise. */
    val field: String = "",
    /** Carries the offending tag or version for context; 0 otherwise. */
    val value: Long = 0L,
) : Exception(message) {
    /** The frozen registered code of the failure. */
    val code: String get() = kind.code

    override fun toString(): String = "PvceException($code): $message"
}

/** Builds the typed resource-limit exception for one field name. */
internal fun resourceLimit(field: String): PvceException =
    PvceException(PvceErrorKind.RESOURCE_LIMIT, "core: PVCE/1 resource limit: $field", field = field)

/**
 * Reports a duplicate object key at construction time (the RFC 0002 object
 * contract; RFC 0016 §4.1: "Objects reject duplicate keys at construction
 * time ... maps to a constructor error").
 */
class DuplicateKeyException(val key: String) :
    Exception("core: duplicate object key: $key") {
    /** The frozen registered code "core.pvce.duplicate-object-key@1"
     * (consema-rs/consema-pvce/src/lib.rs:1082). */
    val code: String get() = PvceErrorKind.DUPLICATE_OBJECT_KEY.code
}
