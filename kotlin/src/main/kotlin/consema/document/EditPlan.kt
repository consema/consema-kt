// Transferable dry-run facts for one fully validated edit transaction.
//
// Data authority:
//   - RFC 0004 §14 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-structural-
//     edit-v1.md:338-356) freezes the dry-run EditPlan v1: dry-run performs
//     every deterministic validation and byte-planning step except publishing
//     a new Document; its transferable form contains schema
//     core.edit-plan@1, source_id, base_digest, profile, operations, exact
//     SourcePatch replacement facts, precomputed target_digest, and an
//     ordered report; a dry-run plan is not authority to write a file and is
//     never applied without rechecking base digest and every original-byte
//     precondition.
//   - consema-rs/consema-document/src/edit_plan.rs:1-273 pins the shapes and
//     the validation bounds (source_id non-empty and <= 1024 characters;
//     summary argument names lowercase/digit/underscore <= 64, values
//     non-empty <= 1024, at most 64 arguments; operation metadata
//     "operation.{index}" keys must match the ordered operation IDs).
//   - consema-go/go/document/edit_plan.go is a cross-reference only.
//
// The operation IDs referenced by [EditOperationSummary] are the frozen
// format operation registrations of RFC 0004 §10 (json.edit.*@1,
// toml.edit.*@1, https://github.com/consema/consema/blob/main/docs/rfcs/0004-...md:247-266); the full
// FormatOperationRegistry is not shipped in Kotlin (recorded gap, six-repo
// audit G090; the facade publishes per-profile operation registries,
// CapabilityParity.kt).

package consema.document

import consema.protocol.Diagnostic

/** Caller-stable source identity used by a transferable edit plan
 * (RFC 0004 §14; edit_plan.rs:13-31). */
class EditPlanSourceId private constructor(private val value: String) {
    companion object {
        /** Validates one non-empty bounded external source identity
         * (edit_plan.rs:18-24): non-empty and at most 1024 characters. */
        fun new(value: String): EditPlanSourceId {
            if (value.isEmpty() || value.length > 1024) {
                throw EditPlanException(EditPlanErrorKind.INVALID_SOURCE_ID)
            }
            return EditPlanSourceId(value)
        }
    }

    /** Exact caller-stable source identity. */
    fun asStr(): String = value

    override fun equals(other: Any?): Boolean = other is EditPlanSourceId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One safe, content-free summary of a declared edit operation (RFC 0004
 * §14; edit_plan.rs:33-70). A summary must not contain raw edited values. */
class EditOperationSummary private constructor(
    /** Exact immutable operation ID/version. */
    val operation: FormatOperationId,
    /** Stable sorted safe summary fields. */
    val arguments: Map<String, String>,
) {
    companion object {
        /** Validates a bounded summary that must not contain raw edited
         * values (edit_plan.rs:44-58): at most 64 arguments; argument names
         * are non-empty lowercase/digit/underscore strings of at most 64
         * characters; values are non-empty strings of at most 1024
         * characters (edit_plan.rs:221-227). */
        fun new(operation: FormatOperationId, arguments: Map<String, String>): EditOperationSummary {
            if (arguments.size > 64 ||
                arguments.any { (name, value) ->
                    !validSummaryName(name) || value.isEmpty() || value.length > 1024
                }
            ) {
                throw EditPlanException(EditPlanErrorKind.INVALID_OPERATION_SUMMARY)
            }
            return EditOperationSummary(operation, arguments.toSortedMap())
        }
    }

    override fun equals(other: Any?): Boolean =
        other is EditOperationSummary &&
            operation == other.operation &&
            arguments == other.arguments

    override fun hashCode(): Int = 31 * operation.hashCode() + arguments.hashCode()

    override fun toString(): String =
        "EditOperationSummary(operation=$operation, arguments=$arguments)"
}

private fun validSummaryName(name: String): Boolean =
    name.isNotEmpty() &&
        name.length <= 64 &&
        name.all { it.isLowerCase() || it.isDigit() || it == '_' }

/** Edit-plan construction failure kinds (edit_plan.rs:200-211). These are
 * construction-time failures of a transferable plan; they carry no
 * registered error code. */
enum class EditPlanErrorKind {
    /** External source identity is empty or exceeds the frozen bound. */
    INVALID_SOURCE_ID,

    /** A summary key/value is invalid or exceeds its frozen bound. */
    INVALID_OPERATION_SUMMARY,

    /** Operation ordering disagrees with the exact SourcePatch metadata. */
    OPERATION_METADATA_MISMATCH,
}

/** The typed edit-plan construction failure; [index] names the first
 * mismatching operation index where applicable. */
class EditPlanException(val kind: EditPlanErrorKind, val index: Int = -1) :
    Exception(
        "edit-plan: $kind" + (if (index >= 0) " at operation $index" else ""),
    )

/**
 * Fully validated dry-run plan; possessing it does not authorize a write
 * (RFC 0004 §14; edit_plan.rs:72-197). Dry-run and commit produce the same
 * replacement set and target digest (RFC 0004 §20); the plan is never
 * applied without rechecking base digest and every original-byte
 * precondition.
 */
class EditPlan private constructor(
    /** Caller-stable source identity. */
    val sourceId: EditPlanSourceId,
    /** Exact profile under which the target was validated. */
    val profile: ProfileId,
    private val operations: List<EditOperationSummary>,
    private val patch: SourcePatch,
    private val report: List<Diagnostic>,
) {
    companion object {
        /** Closes a plan only when its ordered operation metadata matches
         * its exact patch (edit_plan.rs:84-121): every "operation.{index}"
         * metadata key of the patch must equal the ordered operation IDs,
         * and their count must match the operations list. */
        fun new(
            sourceId: EditPlanSourceId,
            profile: ProfileId,
            operations: List<EditOperationSummary>,
            patch: SourcePatch,
            report: List<Diagnostic>,
        ): EditPlan {
            for ((index, operation) in operations.withIndex()) {
                if (patch.metadata()["operation.$index"] != operation.operation.toString()) {
                    throw EditPlanException(EditPlanErrorKind.OPERATION_METADATA_MISMATCH, index)
                }
            }
            val operationKeys = patch.metadata().keys.filter { it.startsWith("operation.") }
            if (operationKeys.isNotEmpty() && operationKeys.size != operations.size) {
                throw EditPlanException(
                    EditPlanErrorKind.OPERATION_METADATA_MISMATCH,
                    operations.size,
                )
            }
            return EditPlan(sourceId, profile, operations, patch, report)
        }
    }

    /** Required base content identity (RFC 0004 §14). */
    val baseDigest: ContentDigest
        get() = patch.baseDigest

    /** Ordered declared operations with content-free summaries. */
    fun operations(): List<EditOperationSummary> = operations

    /** Exact replacement facts, including review redaction flags
     * (RFC 0004 §14). */
    fun replacements(): List<SourceReplacement> = patch.replacements()

    /** Precomputed exact target content identity (RFC 0004 §14). */
    val targetDigest: ContentDigest
        get() = patch.targetDigest

    /** Complete ordered edit report. */
    fun report(): List<Diagnostic> = report

    /** Underlying patch whose application rechecks digest and every
     * original-byte precondition (RFC 0004 §14). */
    val sourcePatch: SourcePatch
        get() = patch

    /**
     * Redacts every original/replacement payload from review/debug
     * presentation (edit_plan.rs:173-183). This does not remove bytes
     * required to apply and verify the plan's SourcePatch; secrets use the
     * SourcePatch redaction rules (RFC 0004 §14).
     */
    fun withAllReplacementsRedacted(
        redactOriginal: Boolean,
        redactReplacement: Boolean,
    ): EditPlan =
        EditPlan(
            sourceId,
            profile,
            operations,
            patch.withAllReplacementsRedacted(redactOriginal, redactReplacement),
            report,
        )

    /** Redacts one exact replacement from review/debug presentation
     * (edit_plan.rs:185-196). */
    fun withReplacementRedacted(
        index: Int,
        redactOriginal: Boolean,
        redactReplacement: Boolean,
    ): EditPlan =
        EditPlan(
            sourceId,
            profile,
            operations,
            patch.withReplacementRedacted(index, redactOriginal, redactReplacement),
            report,
        )

    override fun equals(other: Any?): Boolean =
        other is EditPlan &&
            sourceId == other.sourceId &&
            profile == other.profile &&
            operations == other.operations &&
            patch == other.patch &&
            report == other.report

    override fun hashCode(): Int {
        var result = sourceId.hashCode()
        result = 31 * result + profile.hashCode()
        result = 31 * result + operations.hashCode()
        result = 31 * result + patch.hashCode()
        result = 31 * result + report.hashCode()
        return result
    }
}
