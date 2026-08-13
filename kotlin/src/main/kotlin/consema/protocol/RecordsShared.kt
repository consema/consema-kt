// Shared record facts of the v5/v6 protocol records: the completion
// terminal and the external match locator helpers.
//
// Data authority (language-neutral sources first):
//   - consema-rs/consema-protocol/src/execution.rs:40-187 (Completion: the
//     status-specific invariant table and the fixed core.completion@1 wire
//     form).
//   - consema-rs/consema-protocol/src/query.rs:449-598 (the semantic-model v5/v6
//     query result records all carry one Completion) and line_query.rs /
//     yaml_query.rs / graph_query.rs (the locator identities).
//   - conformance/vectors/semantic-model-v5.json and semantic-model-v6.json
//     pin the round-trip and rejection behaviors.
//
// Kotlin-idiomatic design: an immutable Completion value validated exactly
// once at construction; the failure code is validated against the explicit
// semantic-model registry exactly as the Rust constructor does.

package consema.protocol

import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue

/** The closed completion status spellings (execution.rs:25-38). */
object CompletionStatus {
    const val SUCCESS = "Success"
    const val CANCELLED = "Cancelled"
    const val RESOURCE_LIMITED = "ResourceLimited"
    const val FAILED = "Failed"
    const val UNSUPPORTED = "Unsupported"
    const val NOT_APPLICABLE = "NotApplicable"
}

/**
 * The `core.completion@1` control-flow facts (execution.rs:40-187): the
 * terminal status, the processed/produced counts, and the status-consistent
 * limit or failure facts. Construction validates the status-specific
 * invariant table and the failure code against one explicit semantic-model
 * registry.
 */
class Completion private constructor(
    /** The terminal status spelling. */
    val status: String,
    /** Work items consumed before the terminal state. */
    val processed: Long,
    /** Complete or locally discovered output count. */
    val produced: Long,
    /** The limit that stopped execution; null unless ResourceLimited. */
    val limitName: String?,
    /** The stable terminal failure code; null unless Failed-like. */
    val failureCode: String?,
) {
    companion object {
        /** Validates completion facts against one explicit semantic-model
         * registry (execution.rs:70-107). */
        fun newWithRegistry(
            status: String,
            processed: Long,
            produced: Long,
            limitName: String?,
            failureCode: String?,
            registry: ErrorCodeRegistry,
        ): Completion {
            if (failureCode != null) {
                registry.validate(failureCode)
            }
            val valid = when (status) {
                CompletionStatus.SUCCESS, CompletionStatus.CANCELLED ->
                    limitName == null && failureCode == null
                CompletionStatus.RESOURCE_LIMITED ->
                    limitName != null && limitName.isNotEmpty() && failureCode == null
                CompletionStatus.FAILED, CompletionStatus.UNSUPPORTED,
                CompletionStatus.NOT_APPLICABLE ->
                    limitName == null && failureCode != null && failureCode.isNotEmpty()
                else -> false
            }
            if (!valid) {
                throw invalid("$", "completion status contradicts limit/failure fields")
            }
            return Completion(status, processed, produced, limitName, failureCode)
        }

        /** Validates completion facts against the v1 registry
         * (execution.rs:52-67). */
        fun new(
            status: String,
            processed: Long,
            produced: Long,
            limitName: String?,
            failureCode: String?,
        ): Completion =
            newWithRegistry(
                status, processed, produced, limitName, failureCode,
                ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V1),
            )

        /** Strictly decodes `core.completion@1` under one explicit registry
         * (execution.rs:160-186). */
        fun fromValueWithRegistry(value: PortableValue, registry: ErrorCodeRegistry): Completion {
            val fields = schemaFields(
                value,
                "core.completion@1",
                listOf(
                    "schema", "status", "processed", "produced", "limit_name",
                    "failure_code",
                ),
                "$",
            )
            return newWithRegistry(
                stringOf(fields[1], "$.status"),
                unsigned64(fields[2], "$.processed").toLong(),
                unsigned64(fields[3], "$.produced").toLong(),
                optionalString(fields[4], "$.limit_name"),
                optionalString(fields[5], "$.failure_code"),
                registry,
            )
        }
    }

    /** Encodes `core.completion@1` (execution.rs:141-153). */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.completion@1")),
                consema.core.Entry("status", PvString(status)),
                consema.core.Entry("processed", integerValue(processed.toULong())),
                consema.core.Entry("produced", integerValue(produced.toULong())),
                consema.core.Entry("limit_name", nullableString(limitName)),
                consema.core.Entry("failure_code", nullableString(failureCode)),
            ),
        )
}
