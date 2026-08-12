// Common immutable contracts for creating a new format document.
//
// Data authority (language-neutral sources first):
//   - RFC 0004 (docs/rfcs/0004-materialization-conversion-and-structural-
//     edit-v1.md) §3 (the common MaterializationRequest v1: target_profile,
//     style, encoding, newline None|Lf|CrLf, mapping_policy
//     RequireObject|UniqueStringEntriesToObject, representability ExactOnly,
//     limits), §7 (completion algebra: Complete{document, fidelity, report,
//     provenance} | Failed{failure, report, analyzed_input_paths}), §8
//     (provenance: Value(ValuePath) | Association(AssociationLocation) input
//     locations; Direct|Reencoded|Generated relations).
//   - crates/consema-document/src/materialization.rs:1-495 pins the shapes,
//     the frozen defaults (materialization.rs:95-105), and the failure
//     codes; go/document/materialization.go is a cross-reference only.
//
// The registered materialization codes (RFC 0004 §17
// docs/rfcs/0004-...md:412-420; error_registry.rs:556-604):
//   core.materialization.invalid-request@1
//   core.materialization.unsupported-profile@1
//   core.materialization.unsupported-style@1
//   core.materialization.unsupported-encoding@1
//   core.materialization.unsupported-newline@1
//   core.materialization.unrepresentable@1
//   core.materialization.resource-limit@1
//   core.materialization.formation-failed@1
//
// Cross-domain dependencies (defined by the L0 core/protocol agents, NOT
// here): consema.core.ValuePath / AssociationLocation / AssociationRole
// mirror crates/consema-core/src/location.rs:1-89; consema.protocol.
// Diagnostic is the RFC 0016 §6 diagnostic record owned by the protocol
// package.

package consema.document

import consema.core.AssociationLocation
import consema.core.Kind
import consema.core.ValuePath
import consema.protocol.Diagnostic

/** Explicit output newline policy (RFC 0004 §3; materialization.rs:41-62). */
enum class NewlinePolicy {
    /** Emit no final or layout newline; only supported by compact
     * profiles. */
    None,

    /** ASCII LF. */
    Lf,

    /** ASCII CR followed by LF. */
    CrLf,
    ;

    /** Exact selected newline bytes (materialization.rs:53-61). */
    fun bytes(): ByteArray =
        when (this) {
            None -> ByteArray(0)
            Lf -> byteArrayOf(0x0a)
            CrLf -> byteArrayOf(0x0d, 0x0a)
        }
}

/** Explicit treatment of ordered mappings at object-only targets
 * (RFC 0004 §3; materialization.rs:64-71). */
enum class MappingPolicy {
    /** Require a native PortableValue Object. */
    RequireObject,

    /** Permit a unique String-key EntryMapping to become an Object and
     * report transformation (RFC 0004 §3: never a default, never collapses
     * duplicates; changes whole-operation fidelity to Transformed). */
    UniqueStringEntriesToObject,
}

/** Closed v1 representability policy (RFC 0004 §3; materialization.rs:73-78).
 * ExactOnly is intentionally the only v1 value: a target-native semantic
 * value must round-trip through that target's published exact projection
 * contract. */
enum class RepresentabilityPolicy {
    /** Reject every value that cannot round-trip through the target's exact
     * projection contract. */
    ExactOnly,
}

/**
 * Complete immutable request for creating one new target document
 * (RFC 0004 §3; materialization.rs:107-203). Materialization consumes one
 * complete PortableValue; it never consumes a format AST, process-local
 * handle, partial projection, or arbitrary bytes (RFC 0004 §3).
 */
class MaterializationRequest private constructor(
    /** Exact target Profile. */
    val targetProfile: ProfileId,
    /** Exact versioned target style. */
    val style: MaterializationStyleId,
    /** Selected output encoding. */
    val encoding: SourceEncoding,
    /** Selected newline behavior. */
    val newline: NewlinePolicy,
    /** Ordered-mapping behavior. */
    val mappingPolicy: MappingPolicy,
    /** Representability behavior. */
    val representability: RepresentabilityPolicy,
    /** Resource limits. */
    val limits: MaterializationLimits,
) {
    companion object {
        /** Creates a strict request with UTF-8, LF, Object-only, and
         * ExactOnly defaults (materialization.rs:120-132). */
        fun new(targetProfile: ProfileId, style: MaterializationStyleId): MaterializationRequest =
            MaterializationRequest(
                targetProfile = targetProfile,
                style = style,
                encoding = SourceEncoding.Utf8,
                newline = NewlinePolicy.Lf,
                mappingPolicy = MappingPolicy.RequireObject,
                representability = RepresentabilityPolicy.ExactOnly,
                limits = MaterializationLimits.default,
            )
    }

    /** Selects an explicit output encoding (materialization.rs:134-139). */
    fun withEncoding(encoding: SourceEncoding): MaterializationRequest =
        MaterializationRequest(targetProfile, style, encoding, newline, mappingPolicy, representability, limits)

    /** Selects an explicit newline policy (materialization.rs:140-146). */
    fun withNewline(newline: NewlinePolicy): MaterializationRequest =
        MaterializationRequest(targetProfile, style, encoding, newline, mappingPolicy, representability, limits)

    /** Selects explicit ordered-mapping behavior (materialization.rs:147-153). */
    fun withMappingPolicy(policy: MappingPolicy): MaterializationRequest =
        MaterializationRequest(targetProfile, style, encoding, newline, policy, representability, limits)

    /** Replaces immutable materialization limits (materialization.rs:154-160). */
    fun withLimits(limits: MaterializationLimits): MaterializationRequest =
        MaterializationRequest(targetProfile, style, encoding, newline, mappingPolicy, representability, limits)
}

/** Whole-operation semantic fidelity (materialization.rs:205-212). */
enum class MaterializationFidelity {
    /** Target projection reproduces the same portable representation. */
    Exact,

    /** An explicitly authorized, reportable representation conversion
     * occurred. */
    Transformed,
}

/**
 * Complete ordered materialization report (materialization.rs:214-237).
 * Report events are stable, ordered, machine-readable diagnostics; human
 * wording is not a contract (RFC 0004 §7).
 */
class MaterializationReport private constructor(private val events: List<Diagnostic>) {
    companion object {
        /** Creates a report after enforcing its configured event limit
         * (materialization.rs:222-229). */
        fun new(events: List<Diagnostic>, limits: MaterializationLimits): MaterializationReport {
            if (events.size > limits.maxReportEntries) {
                throw MaterializationException(
                    MaterializationFailureKind.RESOURCE_LIMIT,
                    name = "report-entries",
                )
            }
            return MaterializationReport(events)
        }
    }

    /** Ordered structured events. */
    fun events(): List<Diagnostic> = events

    override fun equals(other: Any?): Boolean =
        other is MaterializationReport && events == other.events

    override fun hashCode(): Int = events.hashCode()
}

/** Portable input value or association location (RFC 0004 §8;
 * materialization.rs:239-246). */
sealed class MaterializationInputLocation {
    /** Portable value location. */
    data class Value(val path: ValuePath) : MaterializationInputLocation()

    /** Portable association location. */
    data class Association(val location: AssociationLocation) : MaterializationInputLocation()
}

/** Relationship from portable input fact to generated target syntax
 * (RFC 0004 §8; materialization.rs:248-258). */
enum class MaterializationRelation {
    /** Direct exact semantic representation. */
    Direct,

    /** Deterministic target-native re-encoding. */
    Reencoded,

    /** Syntax generated without a one-to-one input location. */
    Generated,
}

/** One exact output origin in the newly materialized snapshot (RFC 0004 §8;
 * materialization.rs:259-270). */
data class MaterializedOrigin(
    /** Target snapshot identity. */
    val snapshot: SnapshotIdentity,
    /** Target structural identity. */
    val node: NodeRef,
    /** Exact target raw span. */
    val span: Span,
    /** Input-to-output relationship. */
    val relation: MaterializationRelation,
)

/** One input location mapped to one or more target origins (RFC 0004 §8;
 * materialization.rs:272-279). */
data class MaterializationProvenanceEntry(
    /** Portable input location. */
    val input: MaterializationInputLocation,
    /** One or more target origins. */
    val outputs: List<MaterializedOrigin>,
)

/**
 * Complete input-to-output provenance map (RFC 0004 §8;
 * materialization.rs:281-325). Materialization provenance points from
 * portable input locations to the new Document; it is not the reverse-
 * direction Projection provenance map. Missing locators fail; identities are
 * not silently dropped (RFC 0004 §8).
 */
class MaterializationProvenanceMap private constructor(
    private val entries: List<MaterializationProvenanceEntry>,
) {
    companion object {
        /** Validates snapshot binding, non-empty outputs, and configured
         * size (materialization.rs:288-318). */
        fun new(
            entries: List<MaterializationProvenanceEntry>,
            target: SnapshotIdentity,
            limits: MaterializationLimits,
        ): MaterializationProvenanceMap {
            var units = entries.size
            for (entry in entries) {
                if (entry.outputs.isEmpty()) {
                    throw MaterializationException(
                        MaterializationFailureKind.INVALID_REQUEST,
                        reason = "provenance entry has no output",
                    )
                }
                units = if (units > Int.MAX_VALUE - entry.outputs.size) {
                    throw MaterializationException(
                        MaterializationFailureKind.RESOURCE_LIMIT,
                        name = "provenance-entries",
                    )
                } else {
                    units + entry.outputs.size
                }
                if (entry.outputs.any {
                        it.snapshot != target ||
                            it.node.snapshot != target ||
                            it.span.snapshot != target
                    }
                ) {
                    throw MaterializationException(
                        MaterializationFailureKind.INVALID_REQUEST,
                        reason = "provenance origin uses another snapshot",
                    )
                }
            }
            if (units > limits.maxProvenanceEntries) {
                throw MaterializationException(
                    MaterializationFailureKind.RESOURCE_LIMIT,
                    name = "provenance-entries",
                )
            }
            return MaterializationProvenanceMap(entries)
        }
    }

    /** Deterministically ordered provenance entries. */
    fun entries(): List<MaterializationProvenanceEntry> = entries

    override fun equals(other: Any?): Boolean =
        other is MaterializationProvenanceMap && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()
}

/** Stable materialization failure kinds and their frozen registered codes
 * (materialization.rs:327-351; RFC 0004 §17). */
enum class MaterializationFailureKind(val code: String) {
    /** Request fields contradict the target contract. */
    INVALID_REQUEST("core.materialization.invalid-request@1"),

    /** Target profile is unavailable. */
    UNSUPPORTED_PROFILE("core.materialization.unsupported-profile@1"),

    /** Style is unavailable for the target profile. */
    UNSUPPORTED_STYLE("core.materialization.unsupported-style@1"),

    /** Encoding is unavailable for the target profile. */
    UNSUPPORTED_ENCODING("core.materialization.unsupported-encoding@1"),

    /** Newline policy is unavailable for the selected style. */
    UNSUPPORTED_NEWLINE("core.materialization.unsupported-newline@1"),

    /** One complete input value cannot be represented. */
    UNREPRESENTABLE("core.materialization.unrepresentable@1"),

    /** A configured limit was reached. */
    RESOURCE_LIMIT("core.materialization.resource-limit@1"),

    /** Generated bytes did not form a target document. */
    FORMATION_FAILED("core.materialization.formation-failed@1"),
}

/**
 * The typed materialization failure. The stable [code] is always the
 * registered code (RFC 0016 §6). [reason] is the stable reason string of
 * the Rust INVALID_REQUEST/ResourceLimit variants; [path] and [valueKind]
 * identify the unrepresentable input; [name] is the stable limit name.
 */
class MaterializationException(
    val kind: MaterializationFailureKind,
    message: String? = null,
    val reason: String = "",
    val path: ValuePath? = null,
    val valueKind: Kind? = null,
    val name: String = "",
) : Exception(message ?: "materialization: ${kind.code}") {
    /** The frozen registered code of the failure. */
    val code: String
        get() = kind.code
}

/** Failed attempt without a Document or partial output bytes (RFC 0004 §7;
 * materialization.rs:393-402). */
data class FailedMaterializationAttempt(
    /** Stable failure. */
    val failure: MaterializationException,
    /** Events discovered before failure. */
    val report: MaterializationReport,
    /** Stable input paths analyzed before failure. */
    val analyzedInputPaths: List<ValuePath>,
)

/**
 * Complete successful materialization; its document and audit facts are
 * never partial (RFC 0004 §7; materialization.rs:404-415).
 */
data class CompleteMaterialization<D>(
    /** Newly formed immutable target document. */
    val document: D,
    /** Worst fidelity of the whole operation. */
    val fidelity: MaterializationFidelity,
    /** Complete ordered transformation report. */
    val report: MaterializationReport,
    /** Complete portable-input-to-target provenance. */
    val provenance: MaterializationProvenanceMap,
)

/**
 * Closed materialization completion algebra (RFC 0004 §7;
 * materialization.rs:417-424): exactly one of Complete or Failed; failed
 * attempts contain no Document and no partial output bytes.
 */
sealed class MaterializationResult<out D> {
    /** Complete success with every required artifact. */
    data class Complete<D>(val materialization: CompleteMaterialization<D>) : MaterializationResult<D>()

    /** Failed attempt without a Document or partial output bytes. */
    data class Failed(val attempt: FailedMaterializationAttempt) : MaterializationResult<Nothing>()
}
