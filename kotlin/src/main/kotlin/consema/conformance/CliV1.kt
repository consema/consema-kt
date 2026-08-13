// The `consema.cli.conformance@1` suite runner (conformance/vectors/cli-v1.json).
//
// Data authority: consema-rs/consema-conformance/src/cli_v1.rs (the per-case
// dispatch is transcribed from the Rust handlers; the Rust runner is the
// dispatch authority for every cli case); the vector file itself drives every
// input and expectation (conformance/README.md rules 3-4). The protocol
// payloads are the Kotlin `consema.protocol` CLI records (Cli.kt, ExitClass.kt,
// Errors.kt, Canonical.kt, Source.kt), which mirror the Rust consema-protocol
// v7 types. consema-go/go/conformance/cli_v1.go is a cross-reference only.
//
// The six capability families are covered: cli.envelope@1 (canonical
// transport decode, byte-exact re-encode, PVCE dual-transport equivalence,
// fixed-field facts, strict rejection codes), cli.exit-code@1 (class table
// and family matrix classification), cli.batch-plan@1 / cli.batch-result@1
// (manifest state machines, digest constraints, rejection codes), the
// cli.redaction@1 record contract, and the cli.limit@1 resource budgets.

package consema.conformance

import consema.core.PortableValue
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.core.equal
import consema.protocol.BatchPlanFileEntry
import consema.protocol.BatchPlanFileStatus
import consema.protocol.BatchPlanMessage
import consema.protocol.BatchResultFileStatus
import consema.protocol.BatchResultMessage
import consema.protocol.CliOutputMessage
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.ExitClass
import consema.protocol.ProtocolErrorKind
import consema.protocol.ProtocolException
import consema.protocol.ProtocolLimits
import consema.protocol.Redaction
import consema.protocol.SourcePatchLimits
import consema.protocol.classifyErrorCode
import consema.protocol.decodeJson
import consema.protocol.exitCode
import consema.protocol.decodePvce
import consema.protocol.encodeJson
import consema.protocol.encodePvce
import consema.protocol.parseExitClass

/** Runs the `consema.cli.conformance@1` suite. */
fun runCliV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runCliV1Case(case)
            passed.add(case.id)
        } catch (e: CaseFailureException) {
            failed.add(CaseFailure(case.id, e.message ?: "expected behavior did not match"))
        }
    }
    return SuiteReport(
        suite = data.suite,
        semanticModel = data.semanticModel,
        expectedCases = data.cases.size,
        passed = passed,
        skipped = skipped,
        failed = failed,
    )
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}

private fun runCliV1Case(case: CaseData) {
    when (case.id) {
        // cli.envelope@1
        "cli.envelope.rfc-canonical-bytes",
        "cli.envelope.dual-transport-equivalence",
        "cli.envelope.closed-exit-class-set",
        "cli.envelope.conformance-failure-semantics",
        "cli.envelope.reject-redaction-invariant",
        "cli.envelope.reject-product-version-shape",
        "cli.envelope.reject-command-payload-mismatch",
        "cli.envelope.reject-whitespace",
        "cli.envelope.reject-reordered-fields",
        "cli.envelope.reject-unknown-field",
        "cli.envelope.reject-rfc-typo",
        -> runEnvelope(case)
        // cli.exit-code@1
        "cli.exit-code.class-table",
        "cli.exit-code.usage-family",
        "cli.exit-code.data-family",
        "cli.exit-code.limit-family",
        "cli.exit-code.precondition-family",
        "cli.exit-code.internal-and-unlisted",
        -> runExitCode(case)
        // cli.batch-plan@1
        "cli.batch-plan.rfc-normative-record",
        "cli.batch-plan.mixed-statuses-record",
        "cli.batch-plan.dual-transport",
        "cli.batch-plan.reject-source-digest-mismatch",
        "cli.batch-plan.reject-planned-without-patch",
        "cli.batch-plan.reject-command-fixed",
        "cli.batch-plan.reject-failed-without-diagnostics",
        "cli.batch-plan.reject-failed-with-planning-facts",
        -> runBatchPlan(case)
        // cli.batch-result@1
        "cli.batch-result.rfc-normative-entry",
        "cli.batch-result.all-statuses-roundtrip",
        "cli.batch-result.dual-transport",
        "cli.batch-result.reject-completed-without-digest",
        "cli.batch-result.reject-failed-without-code",
        "cli.batch-result.reject-pending-with-facts",
        "cli.batch-result.reject-unknown-status",
        "cli.batch-result.recovery-three-way-rule",
        -> runBatchResult(case)
        // cli.redaction@1
        "cli.redaction.redaction-facts-embedding",
        "cli.redaction.record-invariant-matrix",
        "cli.redaction.placeholder-value-passthrough",
        "cli.redaction.plan-bytes-never-redacted",
        -> runRedaction(case)
        // cli.limit@1
        "cli.limit.protocol-budget",
        "cli.limit.manifest-size-budget",
        "cli.limit.patch-replacement-budget",
        -> runLimit(case)
        else -> fail("runner does not recognize published CLI case")
    }
}

// ---------------------------------------------------------------------------
// Envelope
// ---------------------------------------------------------------------------

/// Runs one `cli.envelope@1` case.
///
/// Success cases decode `input.json` strictly, re-encode byte-exactly
/// (canonical bytes and byte-determinism), prove dual-transport equivalence
/// against the pinned PVCE bytes, and assert the fixed-field facts.
/// Rejection cases assert the documented `core.protocol.*` error code (and
/// path) of the strictly rejected bytes.
private fun runEnvelope(case: CaseData) {
    val jsonText = inputString(case, "json") ?: fail("missing input.json")
    val limits = ProtocolLimits.default
    val jsonBytes = jsonText.toByteArray(Charsets.UTF_8)
    val errorCode = expectedString(case, "error_code")
    if (errorCode != null) {
        val error = envelopeRejection(jsonBytes, limits) ?: fail("envelope must be rejected")
        ensure(error.kind.code == errorCode)
        expectedString(case, "error_path")?.let { expectedPath ->
            ensure(error.path == expectedPath)
        }
        return
    }
    val message = try {
        CliOutputMessage.fromValue(decodeJson(jsonBytes, limits))
    } catch (e: ProtocolException) {
        fail("envelope decode: ${e.kind.code} at ${e.path}")
    }
    val reEncoded = encodeJson(message.toValue(), limits)
    ensure(reEncoded.contentEquals(jsonBytes))
    val pvce = encodePvce(message.toValue(), limits)
    expectedString(case, "pvce_hex")?.let { expectedPvce ->
        ensure(toHex(pvce) == expectedPvce)
    }
    val decodedPvce = try {
        CliOutputMessage.fromValue(decodePvce(pvce, limits))
    } catch (e: ProtocolException) {
        fail("envelope PVCE decode: ${e.kind.code} at ${e.path}")
    }
    ensure(equal(decodedPvce.toValue(), message.toValue()))
    ensure(encodeJson(message.toValue(), limits).contentEquals(reEncoded))
    assertEnvelopeFacts(message, case)
}

/** The failure of the strict transport decode and record validation, or
 * null when the envelope decodes. */
private fun envelopeRejection(jsonBytes: ByteArray, limits: ProtocolLimits): ProtocolException? =
    try {
        CliOutputMessage.fromValue(decodeJson(jsonBytes, limits))
        null
    } catch (e: ProtocolException) {
        e
    }

/// Asserts the optional fixed-field facts of one decoded envelope.
private fun assertEnvelopeFacts(message: CliOutputMessage, case: CaseData) {
    expectedString(case, "command")?.let { command ->
        ensure(message.command.wireName == command)
    }
    expectedString(case, "exit_class")?.let { exitClass ->
        ensure(message.exitClass.wireName == exitClass)
    }
    expectedString(case, "product_version")?.let { productVersion ->
        ensure(message.productVersion == productVersion)
    }
    expectedString(case, "payload_schema")?.let { payloadSchema ->
        val payload = message.payload as? PvObject ?: fail("payload has no schema first field")
        val first = payload.entries().firstOrNull() ?: fail("payload has no schema first field")
        val actual = (first.value as? PvString)?.value ?: fail("payload has no schema first field")
        ensure(actual == payloadSchema)
    }
    expectedBoolean(case, "redacted")?.let { redacted ->
        ensure(message.redaction.redacted == redacted)
    }
    expectedLong(case, "count")?.let { count ->
        ensure(message.redaction.count.toLong() == count)
    }
    expectedLong(case, "diagnostics_count")?.let { diagnosticsCount ->
        ensure(message.diagnostics.size.toLong() == diagnosticsCount)
    }
    expectedString(case, "diagnostic_code")?.let { code ->
        ensure(message.diagnostics.any { it.code == code })
    }
}

// ---------------------------------------------------------------------------
// Exit classification
// ---------------------------------------------------------------------------

/// Runs one `cli.exit-code@1` case.
///
/// The class-table case carries `input.names` and `input.codes` (the closed
/// RFC 0015 §5.1 table); the family-matrix cases carry `input.codes` and
/// `expected.classes` (the exhaustive RFC 0015 §5.2 family mapping).
private fun runExitCode(case: CaseData) {
    val names = inputSequence(case, "names")
    if (names != null) {
        val codes = inputSequence(case, "codes") ?: fail("missing input.codes")
        ensure(names.size == codes.size)
        for ((index, nameValue) in names.withIndex()) {
            val name = (nameValue as? PvString)?.value ?: fail("input.names items must be strings")
            val expectedCode = (codes[index] as? PvInteger)?.value?.toLong()
                ?: fail("input.codes items must be integers")
            val exitClass = parseExitClass(name) ?: fail("unknown class $name")
            ensure(exitClass.exitCode().toLong() == expectedCode)
        }
        return
    }
    val codes = inputSequence(case, "codes") ?: fail("missing input.codes")
    val classes = expectedSequence(case, "classes") ?: fail("missing expected.classes")
    ensure(codes.size == classes.size)
    for ((index, codeValue) in codes.withIndex()) {
        val code = (codeValue as? PvString)?.value ?: fail("input.codes items must be strings")
        val expected = (classes[index] as? PvString)?.value
            ?: fail("expected.classes items must be strings")
        ensure(classifyErrorCode(code).wireName == expected)
    }
}

// ---------------------------------------------------------------------------
// Batch plan
// ---------------------------------------------------------------------------

private fun plannedPlanEntry(plan: BatchPlanMessage): BatchPlanFileEntry =
    plan.files.firstOrNull { it.status == BatchPlanFileStatus.Planned } ?: fail("no planned entry")

/// Runs one `cli.batch-plan@1` case.
///
/// The record travels as `input.json` (the canonical tagged transport bytes).
/// Success cases decode strictly, re-encode byte-exactly, prove PVCE
/// equivalence, and assert the status/presence facts; rejection cases assert
/// the documented error code and path of the tampered record.
private fun runBatchPlan(case: CaseData) {
    val jsonText = inputString(case, "json") ?: fail("missing input.json")
    val limits = ProtocolLimits.default
    val jsonBytes = jsonText.toByteArray(Charsets.UTF_8)
    val record = try {
        decodeJson(jsonBytes, limits)
    } catch (e: ProtocolException) {
        fail("plan transport decode: ${e.kind.code} at ${e.path}")
    }
    val errorCode = expectedString(case, "error_code")
    if (errorCode != null) {
        val error = try {
            BatchPlanMessage.fromValue(record)
            null
        } catch (e: ProtocolException) {
            e
        } ?: fail("plan record must be rejected")
        ensure(error.kind.code == errorCode)
        expectedString(case, "error_path")?.let { expectedPath ->
            ensure(error.path == expectedPath)
        }
        return
    }
    val plan = try {
        BatchPlanMessage.fromValue(record)
    } catch (e: ProtocolException) {
        fail("plan decode: ${e.kind.code} at ${e.path}")
    }
    ensure(encodeJson(record, limits).contentEquals(jsonBytes))
    ensure(equal(plan.toValue(), record))
    expectedString(case, "pvce_hex")?.let { expectedPvce ->
        ensure(toHex(encodePvce(record, limits)) == expectedPvce)
    }
    expectedString(case, "product_version")?.let { productVersion ->
        ensure(plan.productVersion == productVersion)
    }
    expectedSequence(case, "statuses")?.let { statuses ->
        ensure(plan.files.size == statuses.size)
        for ((entry, expectedValue) in plan.files.zip(statuses)) {
            val expected = (expectedValue as? PvString)?.value
                ?: fail("expected.statuses items must be strings")
            val actual = when (entry.status) {
                BatchPlanFileStatus.Planned -> "planned"
                BatchPlanFileStatus.Failed -> "failed"
            }
            ensure(actual == expected)
        }
    }
    expectedString(case, "source_digest_hex")?.let { digest ->
        val entry = plannedPlanEntry(plan)
        ensure(entry.sourceDigest?.hex() == digest)
    }
    expectedString(case, "target_digest_hex")?.let { digest ->
        val entry = plannedPlanEntry(plan)
        val patch = entry.sourcePatch ?: fail("planned entry without source_patch")
        ensure(patch.targetDigest.hex() == digest)
    }
    expectedString(case, "failure_code")?.let { code ->
        val entry = plan.files.firstOrNull { it.status == BatchPlanFileStatus.Failed }
            ?: fail("no failed plan entry")
        ensure(entry.failureCode == code)
    }
}

// ---------------------------------------------------------------------------
// Batch result
// ---------------------------------------------------------------------------

/// Runs one `cli.batch-result@1` case.
///
/// The record travels as `input.json` (the canonical tagged transport bytes).
/// Success cases decode strictly, re-encode byte-exactly, prove PVCE
/// equivalence, and assert the status/presence facts; rejection cases assert
/// the documented error code and path. The recovery case carries
/// `input.branches` and pins the RFC 0015 §9.4 three-way rule data-driven.
private fun runBatchResult(case: CaseData) {
    if (caseInput(case, "branches") != null) {
        runRecoveryRule(case)
        return
    }
    val jsonText = inputString(case, "json") ?: fail("missing input.json")
    val limits = ProtocolLimits.default
    val jsonBytes = jsonText.toByteArray(Charsets.UTF_8)
    val record = try {
        decodeJson(jsonBytes, limits)
    } catch (e: ProtocolException) {
        fail("result transport decode: ${e.kind.code} at ${e.path}")
    }
    val errorCode = expectedString(case, "error_code")
    if (errorCode != null) {
        val error = try {
            BatchResultMessage.fromValue(record)
            null
        } catch (e: ProtocolException) {
            e
        } ?: fail("result record must be rejected")
        ensure(error.kind.code == errorCode)
        expectedString(case, "error_path")?.let { expectedPath ->
            ensure(error.path == expectedPath)
        }
        return
    }
    val result = try {
        BatchResultMessage.fromValue(record)
    } catch (e: ProtocolException) {
        fail("result decode: ${e.kind.code} at ${e.path}")
    }
    ensure(encodeJson(record, limits).contentEquals(jsonBytes))
    ensure(equal(result.toValue(), record))
    expectedString(case, "pvce_hex")?.let { expectedPvce ->
        ensure(toHex(encodePvce(record, limits)) == expectedPvce)
    }
    expectedString(case, "product_version")?.let { productVersion ->
        ensure(result.productVersion == productVersion)
    }
    expectedSequence(case, "statuses")?.let { statuses ->
        ensure(result.files.size == statuses.size)
        for ((entry, expectedValue) in result.files.zip(statuses)) {
            val expected = (expectedValue as? PvString)?.value
                ?: fail("expected.statuses items must be strings")
            val actual = when (entry.status) {
                BatchResultFileStatus.Completed -> "completed"
                BatchResultFileStatus.Failed -> "failed"
                BatchResultFileStatus.Pending -> "pending"
                BatchResultFileStatus.SkippedStale -> "skipped-stale"
            }
            ensure(actual == expected)
        }
    }
    expectedString(case, "target_digest_hex")?.let { digest ->
        val entry = result.files.firstOrNull { it.status == BatchResultFileStatus.Completed }
            ?: fail("no completed result entry")
        ensure(entry.targetDigest?.hex() == digest)
    }
    expectedBoolean(case, "redacted")?.let { redacted ->
        val entry = result.files.firstOrNull() ?: fail("no result entries")
        ensure(entry.redacted == redacted)
    }
    expectedString(case, "failure_code")?.let { code ->
        val entry = result.files.firstOrNull {
            it.status == BatchResultFileStatus.Failed ||
                it.status == BatchResultFileStatus.SkippedStale
        } ?: fail("no failed result entry")
        ensure(entry.failureCode == code)
    }
}

/// Pins the RFC 0015 §9.4 recovery three-way rule data-driven: the disk-byte
/// branch (`source`/`target`/`other`) maps to the frozen outcome
/// (`redo`/`skip`/`stale`); any branch outside the three-way rule is
/// rejected.
private fun runRecoveryRule(case: CaseData) {
    val branches = inputSequence(case, "branches") ?: fail("missing input.branches")
    for ((index, branch) in branches.withIndex()) {
        val disk = stringField(branch, "disk") ?: fail("missing branch.disk")
        val outcome = stringField(branch, "outcome") ?: fail("missing branch.outcome")
        val expected = when (disk) {
            "source" -> "redo"
            "target" -> "skip"
            "other" -> "stale"
            else -> fail("unknown disk branch $disk")
        }
        ensure(outcome == expected)
    }
    caseInput(case, "illegal_branch")?.let { illegal ->
        val disk = stringField(illegal, "disk") ?: fail("missing illegal_branch.disk")
        ensure(disk != "source" && disk != "target" && disk != "other")
    }
}

// ---------------------------------------------------------------------------
// Redaction contract
// ---------------------------------------------------------------------------

/// Runs one `cli.redaction@1` case.
///
/// Covers the `Redaction` record invariant matrix (`input.samples`), the
/// envelope embedding of redaction facts (`input.json`), the `$REDACTED$`
/// placeholder as an ordinary value that the transport never rewrites
/// (`input.json`), and the presentation-only boundary at the manifest level:
/// patch precondition bytes survive a plan decode/re-encode untouched
/// (`input.record`).
private fun runRedaction(case: CaseData) {
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        for ((index, sample) in samples.withIndex()) {
            val redacted = booleanField(sample, "redacted") ?: fail("missing sample.redacted")
            val count = longField(sample, "count") ?: fail("missing sample.count")
            val valid = booleanField(sample, "valid") ?: fail("missing sample.valid")
            val countValue = if (count >= 0) count.toULong() else fail("sample $index count out of range")
            val accepted = try {
                Redaction.of(redacted, countValue)
                true
            } catch (e: ProtocolException) {
                false
            }
            ensure(accepted == valid)
        }
        return
    }
    val jsonText = inputString(case, "json") ?: fail("missing input.json")
    val limits = ProtocolLimits.default
    val jsonBytes = jsonText.toByteArray(Charsets.UTF_8)
    // The plan-byte case pins the presentation-only boundary on a batch-plan
    // record; the other cases decode the envelope.
    if (expectedString(case, "original_hex") != null) {
        val record = try {
            decodeJson(jsonBytes, limits)
        } catch (e: ProtocolException) {
            fail("plan transport decode: ${e.kind.code} at ${e.path}")
        }
        val plan = try {
            BatchPlanMessage.fromValue(record)
        } catch (e: ProtocolException) {
            fail("plan decode: ${e.kind.code} at ${e.path}")
        }
        val entry = plannedPlanEntry(plan)
        val patch = entry.sourcePatch ?: fail("planned entry without source_patch")
        val replacement = patch.replacements.firstOrNull() ?: fail("no replacement in patch")
        expectedString(case, "original_hex")?.let { originalHex ->
            ensure(toHex(replacement.original) == originalHex)
        }
        expectedString(case, "replacement_hex")?.let { replacementHex ->
            ensure(toHex(replacement.replacement) == replacementHex)
        }
        ensure(equal(plan.toValue(), record))
        ensure(encodeJson(record, limits).contentEquals(jsonBytes))
        return
    }
    val message = try {
        CliOutputMessage.fromValue(decodeJson(jsonBytes, limits))
    } catch (e: ProtocolException) {
        fail("envelope decode: ${e.kind.code} at ${e.path}")
    }
    assertEnvelopeFacts(message, case)
    ensure(encodeJson(message.toValue(), limits).contentEquals(jsonBytes))
    expectedString(case, "placeholder")?.let { placeholder ->
        ensure(payloadContainsString(message.payload, placeholder))
    }
}

/// Whether the exact string appears anywhere in the payload tree (the
/// placeholder contract of RFC 0015 §11.3: a literal `$REDACTED$` value is
/// indistinguishable and the transport never rewrites it).
private fun payloadContainsString(value: PortableValue, needle: String): Boolean = when (value) {
    is PvString -> value.value == needle
    is PvObject -> value.entries().any { payloadContainsString(it.value, needle) }
    is consema.core.PvArray -> value.items().any { payloadContainsString(it, needle) }
    else -> false
}

// ---------------------------------------------------------------------------
// Limits
// ---------------------------------------------------------------------------

/// Runs one `cli.limit@1` case.
///
/// The library-side limit contract: transport budgets (`input.json` under
/// `input.max_bytes`) and the source-patch replacement budget
/// (`input.record` under `SourcePatchLimits`), each raising
/// `core.protocol.resource-limit@1`, which classifies as `limit` (exit 3)
/// per RFC 0015 §5.2. CLI-layer budgets (file size, batch count, `--max-*`
/// overrides) are bin-level and covered by the process-level e2e tests.
private fun runLimit(case: CaseData) {
    val jsonText = inputString(case, "json") ?: fail("missing input.json")
    val limits = ProtocolLimits.default
    val jsonBytes = jsonText.toByteArray(Charsets.UTF_8)
    decodeJson(jsonBytes, limits)
    ensure(classifyErrorCode(ProtocolErrorKind.RESOURCE_LIMIT.code) == ExitClass.Limit)
    // Transport-budget cases carry input.max_bytes; patch-budget cases decode
    // under the frozen replacement budget instead.
    val maxBytesValue = caseInput(case, "max_bytes")
    if (maxBytesValue != null) {
        val maxBytes = (maxBytesValue as? PvInteger)?.value?.toInt()
            ?: fail("input.max_bytes must be an integer")
        val budget = limits.copy(maxBytes = maxBytes)
        val error = try {
            decodeJson(jsonBytes, budget)
            null
        } catch (e: ProtocolException) {
            e
        } ?: fail("payload must exceed the transport budget")
        ensure(error.kind == ProtocolErrorKind.RESOURCE_LIMIT)
        return
    }
    val record = try {
        decodeJson(jsonBytes, limits)
    } catch (e: ProtocolException) {
        fail("transport decode: ${e.kind.code} at ${e.path}")
    }
    val patchLimits = SourcePatchLimits.default.copy(maxReplacements = 0)
    val error = try {
        BatchPlanMessage.fromValueWithRegistry(
            record,
            ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7),
            patchLimits,
        )
        null
    } catch (e: ProtocolException) {
        e
    } ?: fail("plan must exceed the patch replacement budget")
    ensure(error.kind == ProtocolErrorKind.RESOURCE_LIMIT)
}
