// Typed graph construction and PGCE/1 codec failures.
//
// Data authority: the frozen codes are transcribed from the Rust
// StableFailure mappings (consema-rs/consema-graph/src/lib.rs:228-242 for
// graph_build_error_code; consema-rs/consema-graph/src/pgce.rs:162-216 for the
// PGCE decode/encode codes). Verified against the public error-code registry
// (consema-rs/consema-protocol/src/error_registry.rs registers
// core.graph.invalid@1, core.graph.resource-limit@1, core.pgce.invalid@1,
// core.pgce.non-canonical@1, core.pgce.resource-limit@1,
// core.pgce.unsupported-version@1). consema-go/go/graph/errors.go is a cross-reference.
//
// Kotlin-idiomatic error handling: failures are exceptions carrying the
// frozen `code`; error text is human presentation only (RFC 0016 §6).

package consema.graph

/**
 * The stable graph construction failures (the Rust GraphBuildError,
 * consema-rs/consema-graph/src/lib.rs:192-218). Every kind maps to one frozen
 * registered code (see [GraphException.code]).
 */
enum class GraphErrorKind {
    /** A configured resource bound was exceeded; [GraphException.field]
     * names the limit ("graph-nodes", "graph-roots", "graph-edges",
     * "container-entries", "tag-bytes", "scalar-bytes",
     * "traversal-depth"). */
    RESOURCE_LIMIT,

    /** A count or index exceeded the host representation. Kotlin Int
     * arithmetic cannot overflow for any graph constructible under the
     * limits, so this kind is retained for API parity with the Rust error
     * surface and never fires in practice. */
    SIZE_OVERFLOW,

    /** A graph-local node ID was not reserved by this builder. */
    UNKNOWN_NODE,

    /** A node ID belonged to a different builder or completed graph. */
    WRONG_GRAPH,

    /** One reserved node was defined more than once. */
    DUPLICATE_DEFINITION,

    /** One reserved node had no definition at build time. */
    UNDEFINED_NODE,

    /** A defined node was not reachable from any root. */
    UNREACHABLE_NODE,

    /** A tag was empty or contained ASCII control or whitespace. */
    INVALID_TAG,
}

/**
 * The typed graph construction failure. The stable [code] is always the
 * registered code, so cross-language error-code parity holds (RFC 0016 §6).
 */
class GraphException(
    val kind: GraphErrorKind,
    message: String,
    /** Names the resource-limit field (see [GraphErrorKind.RESOURCE_LIMIT]);
     * empty otherwise. */
    val field: String = "",
    /** The observed amount for [GraphErrorKind.RESOURCE_LIMIT]. */
    val observed: Int = 0,
    /** The configured maximum for [GraphErrorKind.RESOURCE_LIMIT]. */
    val limit: Int = 0,
    /** The offending graph-local node ID for the node-specific failures;
     * null otherwise. */
    val id: NodeId? = null,
) : Exception(message) {
    /** The frozen registered code (consema-rs/consema-graph/src/lib.rs:
     * 228-242): "core.graph.resource-limit@1" or "core.graph.invalid@1". */
    val code: String
        get() = when (kind) {
            GraphErrorKind.RESOURCE_LIMIT, GraphErrorKind.SIZE_OVERFLOW ->
                "core.graph.resource-limit@1"
            else -> "core.graph.invalid@1"
        }
}

/**
 * The strict PGCE/1 failures of the encoder and decoder (the Rust
 * PgceEncodeError and PgceDecodeError, consema-rs/consema-graph/src/pgce.rs:
 * 70-152).
 */
enum class PgceErrorKind {
    /** The stream magic did not match "PGCE". */
    INVALID_MAGIC,

    /** The encoding version is not 1. */
    UNSUPPORTED_VERSION,

    /** Input ended inside a required field. */
    UNEXPECTED_END,

    /** A varint was not the shortest representation of its value. */
    NON_MINIMAL_VARINT,

    /** A varint or host-size conversion overflowed. */
    VARINT_OVERFLOW,

    /** A node record octet is not assigned by PGCE/1. */
    UNKNOWN_NODE_KIND,

    /** A length-delimited string was not UTF-8. */
    INVALID_UTF8,

    /** A tag was empty or contained ASCII control or whitespace. */
    INVALID_TAG,

    /** A root or edge referenced a node outside node_count; [PgceException
     * .value] carries the offending reference. */
    REFERENCE_OUT_OF_RANGE,

    /** Wire IDs were not assigned in canonical first-discovery order. */
    NON_CANONICAL_NODE_ORDER,

    /** Bytes followed the one complete graph. */
    TRAILING_BYTES,

    /** A structurally decoded graph violated graph construction invariants;
     * [PgceException.cause] carries the [GraphException]. */
    INVALID_GRAPH,

    /** Re-encoding produced different bytes (RFC 0006 §5
     * defense-in-depth rule). */
    NON_CANONICAL_ENCODING,

    /** A declared resource limit was reached; [PgceException.field] names
     * the limit ("stream-bytes", "graph-roots", "graph-nodes",
     * "graph-edges", "container-entries", "tag-bytes", "scalar-bytes",
     * "traversal-depth"). */
    RESOURCE_LIMIT,
}

/**
 * The typed PGCE/1 codec failure (encode or decode). The stable [code] is
 * always the registered code, so cross-language error-code parity holds
 * (RFC 0016 §6).
 */
class PgceException(
    val kind: PgceErrorKind,
    message: String,
    /** Names the resource-limit field; empty otherwise. */
    val field: String = "",
    /** Carries the offending version, node-kind octet, or reference for
     * context; 0 otherwise. */
    val value: ULong = 0uL,
    /** Carries the wrapped [GraphException] for
     * [PgceErrorKind.INVALID_GRAPH]; null otherwise. */
    override val cause: GraphException? = null,
) : Exception(message) {
    /** The frozen registered code (consema-rs/consema-graph/src/pgce.rs:
     * 162-216). */
    val code: String
        get() = when (kind) {
            PgceErrorKind.RESOURCE_LIMIT -> "core.pgce.resource-limit@1"
            PgceErrorKind.UNSUPPORTED_VERSION -> "core.pgce.unsupported-version@1"
            PgceErrorKind.NON_MINIMAL_VARINT,
            PgceErrorKind.NON_CANONICAL_NODE_ORDER,
            PgceErrorKind.NON_CANONICAL_ENCODING -> "core.pgce.non-canonical@1"
            PgceErrorKind.INVALID_GRAPH ->
                if (cause?.kind == GraphErrorKind.RESOURCE_LIMIT ||
                    cause?.kind == GraphErrorKind.SIZE_OVERFLOW
                ) {
                    "core.pgce.resource-limit@1"
                } else {
                    "core.pgce.invalid@1"
                }
            else -> "core.pgce.invalid@1"
        }
}

/** Builds the typed resource-limit exception for one field name. */
internal fun pgceResourceLimit(field: String): PgceException =
    PgceException(PgceErrorKind.RESOURCE_LIMIT, "graph: PGCE/1 resource limit: $field", field = field)
