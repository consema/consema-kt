// The CLI machine-protocol payloads of RFC 0015 §4/§8/§9.
//
// Data authority: RFC 0015 (https://github.com/consema/consema/blob/main/docs/rfcs/0015-cli-machine-protocol-and-batch-
// apply-v1.md) and https://github.com/consema/consema-rs/blob/main/consema-protocol/src/cli.rs — the command set
// (CliCommand), the envelope (CliOutputMessage), the batch-plan manifest
// (BatchPlanMessage), the batch-result manifest (BatchResultMessage), the
// SemVer shape (is_semantic_version) — symbol-named anchors so each fact
// is locatable in the thousand-line file and a refactor out of cli.rs
// yields a visible drift signal. Every decoder re-validates the cross constraints
// (closed command and exit-class sets, payload-schema/command consistency,
// redaction consistency, digest equality, per-status presence rules)
// instead of trusting the schema discriminator. consema-go/go/protocol/cli.go is a
// cross-reference.
//
// Bytes note: the core.source-patch@2 record nested in a planned batch-plan
// entry carries Bytes leaves (replacement original/replacement content);
// both codec paths carry replacement records with full byte fidelity.

package consema.protocol

import consema.core.PvArray
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import java.security.MessageDigest

/** One of the eleven formal CLI commands (RFC 0015 §6.1). */
enum class CliCommand(val wireName: String) {
    Inspect("inspect"),
    Capabilities("capabilities"),
    Query("query"),
    Project("project"),
    Materialize("materialize"),
    Convert("convert"),
    Edit("edit"),
    Plan("plan"),
    Apply("apply"),
    Conformance("conformance"),
    Explain("explain"),
}

/** Parses one canonical command name into the closed command set. */
fun parseCliCommand(name: String): CliCommand? =
    CliCommand.entries.firstOrNull { it.wireName == name }

/** The payload schemas the command may carry (RFC 0015 §6.1 table;
 * cli.rs). */
fun CliCommand.payloadSchemas(): List<String> = when (this) {
    CliCommand.Inspect -> listOf("cli.inspect@1")
    CliCommand.Capabilities -> listOf("cli.capabilities@1")
    CliCommand.Query -> listOf(
        "core.query-result@1",
        "core.ini-query-result@1",
        "core.java-properties-query-result@1",
        "core.yaml-query-result@1",
        "core.graph-query-result@1",
    )
    CliCommand.Project -> listOf("core.projection-result@1")
    CliCommand.Materialize -> listOf("core.materialization-result@2")
    CliCommand.Convert -> listOf("cli.convert@1")
    CliCommand.Edit -> listOf("cli.edit@1")
    CliCommand.Plan -> listOf("core.batch-plan@1")
    CliCommand.Apply -> listOf("core.batch-result@1")
    CliCommand.Conformance -> listOf("cli.conformance@1")
    CliCommand.Explain -> listOf("cli.explain@1")
}

/** Carries the envelope redaction facts (RFC 0015 §11.3; cli.rs). */
data class Redaction(
    /** Whether any value was replaced by the `$REDACTED$` placeholder. */
    val redacted: Boolean,
    /** The number of values replaced in this output. */
    val count: ULong,
) {
    /** Validates the `redacted == (count > 0)` invariant. */
    companion object {
        fun of(redacted: Boolean, count: ULong): Redaction {
            if (redacted != (count > 0uL)) {
                throw invalid("$.redaction", "redacted must equal (count > 0)")
            }
            return Redaction(redacted, count)
        }
    }
}

/**
 * The stable SHA-256 identity of exact raw source bytes
 * (consema-document/src/source.rs). The document milestone owns the
 * full source model; this is the wire-form digest used by the CLI records.
 */
class ContentDigest private constructor(val bytes: ByteArray) {

    companion object {
        /** Computes the digest of exact raw bytes. */
        fun of(data: ByteArray): ContentDigest =
            ContentDigest(MessageDigest.getInstance("SHA-256").digest(data))

        /** Constructs a digest from an already decoded 32-byte record. */
        fun fromBytes(bytes: ByteArray): ContentDigest = ContentDigest(bytes.copyOf())
    }

    /** The digest algorithm identifier frozen by the v1 source contract. */
    fun algorithm(): String = "sha256"

    /** The exact 32 digest bytes (a copy). */
    fun content(): ByteArray = bytes.copyOf()

    /** The lowercase hexadecimal representation. */
    fun hex(): String = bytes.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean = other is ContentDigest && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

/** A stable format-operation contract identity
 * (consema-document operation_registry.rs). */
data class FormatOperationId(val id: String, val version: Int)

/**
 * One safe, content-free summary of a declared edit operation
 * (consema-document edit_plan.rs). Summary values must not contain
 * raw edited values.
 */
data class EditOperationSummary(
    /** The exact immutable operation ID/version. */
    val operation: FormatOperationId,
    /** The stable sorted safe summary fields. */
    val summary: Map<String, String>,
) {
    /** Validates a bounded summary. */
    companion object {
        fun of(operation: FormatOperationId, summary: Map<String, String>): EditOperationSummary {
            if (summary.size > 64) {
                throw invalid("$.files[].operations", "invalid operation summary")
            }
            for ((name, value) in summary) {
                if (!validSummaryName(name) || value.isEmpty() || value.length > 1024) {
                    throw invalid("$.files[].operations", "invalid operation summary")
                }
            }
            return EditOperationSummary(operation, summary.toMap())
        }

        private fun validSummaryName(name: String): Boolean {
            if (name.isEmpty() || name.length > 64) {
                return false
            }
            for (character in name) {
                if (!character.isLowerCase() && !character.isDigit() && character != '_') {
                    return false
                }
            }
            return true
        }

        /** Strictly decodes one operation summary record (cli.rs). */
        fun fromValue(value: PortableValue, path: String): EditOperationSummary {
            val fields = exactFields(value, listOf("operation", "summary"), path)
            val reference = exactFields(fields[0], listOf("id", "version"), "$path.operation")
            val id = stringOf(reference[0], "$path.operation.id")
            val version = unsigned32(reference[1], "$path.operation.version")
            val summary = stringMapFromObject(fields[1], "$path.summary")
            return of(FormatOperationId(id, version), summary)
        }
    }

    /** Encodes one operation summary record. */
    fun toValue(): PortableValue = PvObject(
        listOf(
            consema.core.Entry(
                "operation",
                referenceValue(operation.id, operation.version),
            ),
            consema.core.Entry("summary", stringMapObject(summary)),
        ),
    )
}

/** One file-level status in a core.batch-plan@1 manifest (RFC 0015 §8.2;
 * cli.rs). */
enum class BatchPlanFileStatus(val wireName: String) {
    /** The file planned successfully; profile, source_digest, operations,
     * and source_patch are present. */
    Planned("planned"),

    /** The file failed to plan; failure_code and diagnostics are
     * present. */
    Failed("failed"),
}

/**
 * One file entry of a core.batch-plan@1 manifest (cli.rs).
 * Construction validates the per-status presence rules and the
 * `source_digest == source_patch.base_digest` cross constraint
 * (cli.rs).
 */
class BatchPlanFileEntry private constructor(
    val path: String,
    val status: BatchPlanFileStatus,
    val profile: ProfileReference?,
    val sourceDigest: ContentDigest?,
    val operations: List<EditOperationSummary>?,
    val sourcePatch: SourcePatch?,
    val failureCode: String?,
    val diagnostics: List<Diagnostic>?,
) {
    companion object {
        fun of(
            path: String,
            status: BatchPlanFileStatus,
            profile: ProfileReference?,
            sourceDigest: ContentDigest?,
            operations: List<EditOperationSummary>?,
            sourcePatch: SourcePatch?,
            failureCode: String?,
            diagnostics: List<Diagnostic>?,
            registry: ErrorCodeRegistry,
        ): BatchPlanFileEntry {
            if (path.isEmpty() || path.length > 1024) {
                throw invalid("$.files[].path", "invalid path")
            }
            // Revalidate the bounded operation summaries (an invalid
            // summary throws at construction).
            operations?.forEach { operation ->
                EditOperationSummary.of(operation.operation, operation.summary)
            }
            when (status) {
                BatchPlanFileStatus.Planned -> {
                    if (profile == null || sourceDigest == null || operations == null || sourcePatch == null) {
                        throw invalid(
                            "$.files[]",
                            "planned entries require profile, source_digest, operations, and source_patch",
                        )
                    }
                    if (failureCode != null || diagnostics != null) {
                        throw invalid(
                            "$.files[]",
                            "planned entries cannot carry failure_code or diagnostics",
                        )
                    }
                    if (sourceDigest != sourcePatch.baseDigest) {
                        throw invalid(
                            "$.files[].source_digest",
                            "source_digest must equal source_patch.base_digest",
                        )
                    }
                }
                BatchPlanFileStatus.Failed -> {
                    if (profile != null || sourceDigest != null || operations != null || sourcePatch != null) {
                        throw invalid("$.files[]", "failed entries cannot carry planning facts")
                    }
                    if (failureCode == null || failureCode.isEmpty()) {
                        throw invalid("$.files[].failure_code", "failed entries require a failure_code")
                    }
                    if (diagnostics == null) {
                        throw invalid("$.files[].diagnostics", "failed entries require a diagnostics sequence")
                    }
                }
            }
            diagnostics?.forEachIndexed { index, diagnostic ->
                // The registry binding check mirrors the Rust
                // DiagnosticMessage::from_value_with_registry re-validation
                // (cli.rs).
                try {
                    validateDiagnosticCode(diagnostic.code, diagnostic.category, registry)
                } catch (e: ProtocolException) {
                    throw ProtocolException(
                        e.kind,
                        "$.files[].diagnostics[$index]",
                        e.detail,
                    )
                }
            }
            return BatchPlanFileEntry(
                path, status, profile, sourceDigest, operations,
                sourcePatch, failureCode, diagnostics,
            )
        }
    }
}

/**
 * The full core.batch-plan@1 manifest (RFC 0015 §8; cli.rs).
 */
class BatchPlanMessage private constructor(
    val productVersion: String,
    val files: List<BatchPlanFileEntry>,
) {
    companion object {
        /** Validates the manifest fields and every file entry under the
         * semantic-model v7 error registry. */
        fun of(productVersion: String, files: List<BatchPlanFileEntry>): BatchPlanMessage {
            if (productVersion.isEmpty()) {
                throw invalid("$.product_version", "product_version cannot be empty")
            }
            files.forEachIndexed { index, entry ->
                revalidatePlanEntry(entry, index, ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7))
            }
            return BatchPlanMessage(productVersion, files)
        }

        /** Strictly decodes core.batch-plan@1 under the semantic-model v7
         * error registry (cli.rs). */
        fun fromValue(value: PortableValue): BatchPlanMessage =
            fromValueWithRegistry(value, ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7))

        /** Strictly decodes the manifest and re-verifies every cross
         * constraint under one explicit registry and one explicit
         * source-patch replacement budget. */
        fun fromValueWithRegistry(
            value: PortableValue,
            registry: ErrorCodeRegistry,
            patchLimits: SourcePatchLimits = SourcePatchLimits.default,
        ): BatchPlanMessage {
            val fields = schemaFields(
                value,
                "core.batch-plan@1",
                listOf("schema", "product_version", "command", "files"),
                "$",
            )
            val command = stringOf(fields[2], "$.command")
            if (command != "plan") {
                throw invalid("$.command", "expected command \"plan\"")
            }
            val fileValues = sequenceOf(fields[3], "$.files")
            val files = fileValues.mapIndexed { index, item ->
                parsePlanEntry(item, index, registry, patchLimits)
            }
            val productVersion = stringOf(fields[1], "$.product_version")
            if (productVersion.isEmpty()) {
                throw invalid("$.product_version", "product_version cannot be empty")
            }
            files.forEachIndexed { index, entry ->
                revalidatePlanEntry(entry, index, registry)
            }
            return BatchPlanMessage(productVersion, files)
        }
    }

    /** Encodes the fixed core.batch-plan@1 schema as a PortableValue tree.
     * Source-patch replacement bytes travel as Bytes leaves with full
     * fidelity. */
    fun toValue(): PortableValue {
        val fileValues = files.mapIndexed { index, entry -> planEntryValue(entry, index) }
        return PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.batch-plan@1")),
                consema.core.Entry("product_version", PvString(productVersion)),
                consema.core.Entry("command", PvString("plan")),
                consema.core.Entry("files", PvArray(fileValues)),
            ),
        )
    }
}

/** One file-level status in a core.batch-result@1 manifest (RFC 0015 §9.2;
 * cli.rs). */
enum class BatchResultFileStatus(val wireName: String) {
    /** The file was rewritten and its target digest was verified. */
    Completed("completed"),

    /** The file failed; failure_code is present. */
    Failed("failed"),

    /** The file was pending when the manifest was written (interruption). */
    Pending("pending"),

    /** The current bytes no longer match the planned base digest. */
    SkippedStale("skipped-stale"),
}

/**
 * One result entry of a core.batch-result@1 manifest (cli.rs).
 * Construction validates the per-status presence rules and the closed
 * status set (cli.rs).
 */
class BatchResultFileEntry private constructor(
    val path: String,
    val status: BatchResultFileStatus,
    val failureCode: String?,
    val targetDigest: ContentDigest?,
    val redacted: Boolean,
) {
    companion object {
        fun of(
            path: String,
            status: BatchResultFileStatus,
            failureCode: String?,
            targetDigest: ContentDigest?,
            redacted: Boolean,
        ): BatchResultFileEntry {
            if (path.isEmpty() || path.length > 1024) {
                throw invalid("$.files[].path", "invalid path")
            }
            when (status) {
                BatchResultFileStatus.Completed -> {
                    if (failureCode != null || targetDigest == null) {
                        throw invalid(
                            "$.files[]",
                            "completed entries require a target_digest and no failure_code",
                        )
                    }
                }
                BatchResultFileStatus.Failed, BatchResultFileStatus.SkippedStale -> {
                    if (failureCode == null || failureCode.isEmpty() || targetDigest != null) {
                        throw invalid(
                            "$.files[]",
                            "failed or skipped-stale entries require a failure_code and no target_digest",
                        )
                    }
                }
                BatchResultFileStatus.Pending -> {
                    if (failureCode != null || targetDigest != null) {
                        throw invalid(
                            "$.files[]",
                            "pending entries cannot carry failure_code or target_digest",
                        )
                    }
                }
            }
            return BatchResultFileEntry(path, status, failureCode, targetDigest, redacted)
        }

        /** Strictly decodes one result entry (cli.rs). */
        fun fromValue(value: PortableValue, path: String): BatchResultFileEntry {
            val fields = exactFields(
                value,
                listOf("path", "status", "failure_code", "target_digest", "redacted"),
                path,
            )
            val statusName = stringOf(fields[1], "$path.status")
            val status = BatchResultFileStatus.entries.firstOrNull { it.wireName == statusName }
                ?: throw invalid("$path.status", "unknown result file status")
            val failureCode = optionalString(fields[2], "$path.failure_code")
            val targetDigest = if (fields[3] is PvNull) null else parseDigest(fields[3], "$path.target_digest")
            val redacted = booleanOf(fields[4], "$path.redacted")
            val pathText = stringOf(fields[0], "$path.path")
            return of(pathText, status, failureCode, targetDigest, redacted)
        }
    }

    /** Encodes one result entry (cli.rs). */
    fun toValue(): PortableValue = PvObject(
        listOf(
            consema.core.Entry("path", PvString(path)),
            consema.core.Entry("status", PvString(status.wireName)),
            consema.core.Entry("failure_code", nullableString(failureCode)),
            consema.core.Entry(
                "target_digest",
                if (targetDigest == null) PvNull else digestValue(targetDigest),
            ),
            consema.core.Entry("redacted", PvBoolean(redacted)),
        ),
    )
}

/**
 * The full core.batch-result@1 manifest (RFC 0015 §9; cli.rs).
 */
class BatchResultMessage private constructor(
    val productVersion: String,
    val files: List<BatchResultFileEntry>,
) {
    companion object {
        /** Validates the manifest fields and every result entry. */
        fun of(productVersion: String, files: List<BatchResultFileEntry>): BatchResultMessage {
            if (productVersion.isEmpty()) {
                throw invalid("$.product_version", "product_version cannot be empty")
            }
            return BatchResultMessage(productVersion, files)
        }

        /** Strictly decodes core.batch-result@1 (cli.rs). */
        fun fromValue(value: PortableValue): BatchResultMessage {
            val fields = schemaFields(
                value,
                "core.batch-result@1",
                listOf("schema", "product_version", "command", "files"),
                "$",
            )
            val command = stringOf(fields[2], "$.command")
            if (command != "apply") {
                throw invalid("$.command", "expected command \"apply\"")
            }
            val fileValues = sequenceOf(fields[3], "$.files")
            val files = fileValues.mapIndexed { index, item ->
                BatchResultFileEntry.fromValue(item, "$.files[$index]")
            }
            val productVersion = stringOf(fields[1], "$.product_version")
            return of(productVersion, files)
        }
    }

    /** Encodes the fixed core.batch-result@1 schema. */
    fun toValue(): PortableValue = PvObject(
        listOf(
            consema.core.Entry("schema", PvString("core.batch-result@1")),
            consema.core.Entry("product_version", PvString(productVersion)),
            consema.core.Entry("command", PvString("apply")),
            consema.core.Entry("files", PvArray(files.map { it.toValue() })),
        ),
    )
}

/**
 * The full core.cli-output@1 machine envelope (RFC 0015 §4; cli.rs).
 * Construction validates command/exit-class closure, product-version shape,
 * payload schema consistency, diagnostic registry binding, and redaction
 * facts (cli.rs).
 */
class CliOutputMessage private constructor(
    val command: CliCommand,
    val exitClass: ExitClass,
    val productVersion: String,
    val payload: PortableValue,
    val diagnostics: List<Diagnostic>,
    val redaction: Redaction,
) {
    companion object {
        fun of(
            command: CliCommand,
            exitClass: ExitClass,
            productVersion: String,
            payload: PortableValue,
            diagnostics: List<Diagnostic>,
            redaction: Redaction,
        ): CliOutputMessage =
            ofWithRegistry(
                command, exitClass, productVersion, payload, diagnostics, redaction,
                ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7),
            )

        /** Validates the envelope under one explicit semantic-model
         * registry. */
        fun ofWithRegistry(
            command: CliCommand,
            exitClass: ExitClass,
            productVersion: String,
            payload: PortableValue,
            diagnostics: List<Diagnostic>,
            redaction: Redaction,
            registry: ErrorCodeRegistry,
        ): CliOutputMessage {
            if (!isSemanticVersion(productVersion)) {
                throw invalid(
                    "$.product_version",
                    "expected MAJOR.MINOR.PATCH[-prerelease] without leading zeros or build metadata",
                )
            }
            validatePayloadSchema(payload, command)
            diagnostics.forEachIndexed { index, diagnostic ->
                try {
                    validateDiagnosticCode(diagnostic.code, diagnostic.category, registry)
                } catch (e: ProtocolException) {
                    throw ProtocolException(e.kind, "$.diagnostics[$index]", e.detail)
                }
            }
            return CliOutputMessage(command, exitClass, productVersion, payload, diagnostics, redaction)
        }

        /** Strictly decodes core.cli-output@1 under the semantic-model v7
         * error registry (cli.rs). */
        fun fromValue(value: PortableValue): CliOutputMessage =
            fromValueWithRegistry(value, ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7))

        /** Strictly decodes the envelope under one explicit registry. */
        fun fromValueWithRegistry(value: PortableValue, registry: ErrorCodeRegistry): CliOutputMessage {
            val fields = schemaFields(
                value,
                "core.cli-output@1",
                listOf("schema", "command", "exit_class", "product_version", "payload",
                    "diagnostics", "redaction"),
                "$",
            )
            val command = parseCliCommand(stringOf(fields[1], "$.command"))
                ?: throw invalid("$.command", "unknown command")
            val exitClass = parseExitClass(stringOf(fields[2], "$.exit_class"))
                ?: throw invalid("$.exit_class", "unknown exit class")
            val productVersion = stringOf(fields[3], "$.product_version")
            if (!isSemanticVersion(productVersion)) {
                throw invalid(
                    "$.product_version",
                    "expected MAJOR.MINOR.PATCH[-prerelease] without leading zeros or build metadata",
                )
            }
            validatePayloadSchema(fields[4], command)
            val diagnosticValues = sequenceOf(fields[5], "$.diagnostics")
            val diagnostics = diagnosticValues.map { item ->
                Diagnostic.fromValue(item, registry)
            }
            val redactionFields = exactFields(fields[6], listOf("redacted", "count"), "$.redaction")
            val redacted = booleanOf(redactionFields[0], "$.redaction.redacted")
            val count = unsigned64(redactionFields[1], "$.redaction.count")
            val redaction = Redaction.of(redacted, count)
            return ofWithRegistry(
                command, exitClass, productVersion, fields[4], diagnostics, redaction, registry,
            )
        }
    }

    /** Encodes the fixed core.cli-output@1 envelope. */
    fun toValue(): PortableValue = PvObject(
        listOf(
            consema.core.Entry("schema", PvString("core.cli-output@1")),
            consema.core.Entry("command", PvString(command.wireName)),
            consema.core.Entry("exit_class", PvString(exitClass.wireName)),
            consema.core.Entry("product_version", PvString(productVersion)),
            consema.core.Entry("payload", payload),
            consema.core.Entry("diagnostics", PvArray(diagnostics.map { it.toValue() })),
            consema.core.Entry(
                "redaction",
                PvObject(
                    listOf(
                        consema.core.Entry("redacted", PvBoolean(redaction.redacted)),
                        consema.core.Entry("count", integerValue(redaction.count)),
                    ),
                ),
            ),
        ),
    )
}

/** Requires the payload to be an Object whose first field is "schema"
 * carrying one of the command's published schemas (cli.rs). */
private fun validatePayloadSchema(payload: PortableValue, command: CliCommand) {
    val objectValue = payload as? PvObject
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$.payload", "payload must be an Object")
    val entries = objectValue.entries()
    if (entries.isEmpty()) {
        throw protocolError(ProtocolErrorKind.MISSING_FIELD, "$.payload.schema", "payload schema is absent")
    }
    if (entries[0].key != "schema") {
        throw protocolError(ProtocolErrorKind.SCHEMA_MISMATCH, "$.payload", "schema must be the first field")
    }
    val schema = stringOf(entries[0].value, "$.payload.schema")
    if (schema !in command.payloadSchemas()) {
        throw protocolError(
            ProtocolErrorKind.SCHEMA_MISMATCH,
            "$.payload.schema",
            "payload schema $schema is not published by ${command.wireName}",
        )
    }
}

/**
 * Validates the SemVer 2.0 core shape of a product version (RFC 0015 §3.3,
 * 2026-08-10 revision; cli.rs): MAJOR.MINOR.PATCH with an optional
 * dot-separated -prerelease suffix; numeric segments and numeric prerelease
 * identifiers carry no leading zeros; build metadata ('+' suffix) is
 * rejected.
 */
internal fun isSemanticVersion(version: String): Boolean {
    if (version.contains('+')) {
        return false
    }
    val core: String
    val prerelease: String?
    val dash = version.indexOf('-')
    if (dash >= 0) {
        core = version.substring(0, dash)
        prerelease = version.substring(dash + 1)
    } else {
        core = version
        prerelease = null
    }
    if (!numericCore(core)) {
        return false
    }
    if (prerelease == null) {
        return true
    }
    if (prerelease.isEmpty()) {
        return false
    }
    for (identifier in prerelease.split('.')) {
        if (!prereleaseIdentifier(identifier)) {
            return false
        }
    }
    return true
}

/** Reports whether [text] is exactly three dot-separated numeric segments
 * without leading zeros (the MAJOR.MINOR.PATCH core). */
private fun numericCore(text: String): Boolean {
    val segments = text.split('.')
    if (segments.size != 3) {
        return false
    }
    return segments.all { numericSegment(it) }
}

/** Reports whether one segment is a non-empty digit run without a leading
 * zero (single "0" is allowed). */
private fun numericSegment(segment: String): Boolean {
    if (segment.isEmpty() || segment.any { !it.isDigit() }) {
        return false
    }
    return segment.length == 1 || segment[0] != '0'
}

/** Reports whether one SemVer prerelease identifier is well-formed:
 * non-empty and ASCII alphanumeric or hyphen only; numeric identifiers
 * must not carry leading zeros. */
private fun prereleaseIdentifier(identifier: String): Boolean {
    if (identifier.isEmpty()) {
        return false
    }
    var numeric = true
    for (character in identifier) {
        if (!character.isDigit()) {
            numeric = false
            if (!(character.isLowerCase() || character.isUpperCase() || character == '-')) {
                return false
            }
        }
    }
    if (numeric && identifier.length > 1 && identifier[0] == '0') {
        return false
    }
    return true
}

/** Re-verifies the entry-level cross constraints of a manifest
 * (cli.rs). */
private fun revalidatePlanEntry(
    entry: BatchPlanFileEntry,
    index: Int,
    registry: ErrorCodeRegistry,
): BatchPlanFileEntry {
    val path = "$.files[$index]"
    when (entry.status) {
        BatchPlanFileStatus.Planned -> {
            if (entry.profile == null || entry.sourceDigest == null ||
                entry.operations == null || entry.sourcePatch == null
            ) {
                throw invalid(path, "planned entries require all planning facts")
            }
            if (entry.sourceDigest != entry.sourcePatch.baseDigest) {
                throw invalid(path + ".source_digest", "source_digest must equal source_patch.base_digest")
            }
        }
        BatchPlanFileStatus.Failed -> {
            if (entry.failureCode == null || entry.failureCode.isEmpty() || entry.diagnostics == null) {
                throw invalid(path, "failed entries require failure_code and diagnostics")
            }
        }
    }
    entry.diagnostics?.forEachIndexed { diagnosticIndex, diagnostic ->
        try {
            validateDiagnosticCode(diagnostic.code, diagnostic.category, registry)
        } catch (e: ProtocolException) {
            throw ProtocolException(e.kind, "$path.diagnostics[$diagnosticIndex]", e.detail)
        }
    }
    return entry
}

/** Encodes one plan entry as a PortableValue tree (cli.rs).
 * Source-patch replacement records travel as Bytes leaves with full
 * fidelity. */
private fun planEntryValue(entry: BatchPlanFileEntry, index: Int): PortableValue {
    val sourcePatchValue = entry.sourcePatch?.toValue()
    val operationsValue = entry.operations?.let { operations ->
        PvArray(operations.map { it.toValue() })
    }
    val diagnosticsValue = entry.diagnostics?.let { diagnostics ->
        PvArray(diagnostics.map { it.toValue() })
    }
    return PvObject(
        listOf(
            consema.core.Entry("path", PvString(entry.path)),
            consema.core.Entry("status", PvString(entry.status.wireName)),
            consema.core.Entry(
                "profile",
                if (entry.profile == null) PvNull else referenceValue(entry.profile.id, entry.profile.version),
            ),
            consema.core.Entry(
                "source_digest",
                if (entry.sourceDigest == null) PvNull else digestValue(entry.sourceDigest),
            ),
            consema.core.Entry("operations", operationsValue ?: PvNull),
            consema.core.Entry("source_patch", sourcePatchValue ?: PvNull),
            consema.core.Entry("failure_code", nullableString(entry.failureCode)),
            consema.core.Entry("diagnostics", diagnosticsValue ?: PvNull),
        ),
    )
}

/** Strictly decodes one plan entry at the value level (cli.rs). */
private fun parsePlanEntry(
    value: PortableValue,
    index: Int,
    registry: ErrorCodeRegistry,
    patchLimits: SourcePatchLimits,
): BatchPlanFileEntry {
    val path = "$.files[$index]"
    val fields = exactFields(
        value,
        listOf("path", "status", "profile", "source_digest", "operations",
            "source_patch", "failure_code", "diagnostics"),
        path,
    )
    val statusName = stringOf(fields[1], "$path.status")
    val status = BatchPlanFileStatus.entries.firstOrNull { it.wireName == statusName }
        ?: throw invalid("$path.status", "unknown plan file status")
    var profile: ProfileReference? = null
    var sourceDigest: ContentDigest? = null
    var operations: List<EditOperationSummary>? = null
    var sourcePatch: SourcePatch? = null
    var failureCode: String? = null
    var diagnostics: List<Diagnostic>? = null
    when (status) {
        BatchPlanFileStatus.Planned -> {
            profile = parseProfileReference(fields[2], "$path.profile")
            sourceDigest = parseDigest(fields[3], "$path.source_digest")
            val operationValues = sequenceOf(fields[4], "$path.operations")
            operations = operationValues.mapIndexed { operationIndex, item ->
                EditOperationSummary.fromValue(item, "$path.operations[$operationIndex]")
            }
            sourcePatch = SourcePatch.fromValue(fields[5], "$path.source_patch", patchLimits)
            if (fields[6] !is PvNull || fields[7] !is PvNull) {
                throw invalid(path, "planned entries cannot carry failure_code or diagnostics")
            }
        }
        BatchPlanFileStatus.Failed -> {
            for (fieldIndex in 2..5) {
                if (fields[fieldIndex] !is PvNull) {
                    throw invalid(path, "failed entries cannot carry planning facts")
                }
            }
            val code = stringOf(fields[6], "$path.failure_code")
            if (code.isEmpty()) {
                throw invalid("$path.failure_code", "failure_code cannot be empty")
            }
            failureCode = code
            val diagnosticValues = sequenceOf(fields[7], "$path.diagnostics")
            diagnostics = diagnosticValues.map { item ->
                Diagnostic.fromValue(item, registry)
            }
        }
    }
    val pathText = stringOf(fields[0], "$path.path")
    return BatchPlanFileEntry.of(
        pathText, status, profile, sourceDigest, operations,
        sourcePatch, failureCode, diagnostics, registry,
    )
}

/** Encodes one digest record (cli.rs). */
internal fun digestValue(digest: ContentDigest): PortableValue = PvObject(
    listOf(
        consema.core.Entry("algorithm", PvString(digest.algorithm())),
        consema.core.Entry("hex", PvString(digest.hex())),
    ),
)

/** Strictly decodes one sha256 digest record (cli.rs). */
internal fun parseDigest(value: PortableValue, path: String): ContentDigest {
    val fields = exactFields(value, listOf("algorithm", "hex"), path)
    val algorithm = stringOf(fields[0], "$path.algorithm")
    if (algorithm != "sha256") {
        throw invalid(path, "expected sha256")
    }
    val hex = stringOf(fields[1], "$path.hex")
    if (hex.length != 64 || hex.any { !it.isDigit() && it !in 'a'..'f' }) {
        throw invalid(path, "invalid lowercase sha256")
    }
    val bytes = ByteArray(32)
    for (index in 0 until 32) {
        val high = hexDigitValue(hex[index * 2])
        val low = hexDigitValue(hex[index * 2 + 1])
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return ContentDigest.fromBytes(bytes)
}
