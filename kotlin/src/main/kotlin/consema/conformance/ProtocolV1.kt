// The `consema.protocol.conformance@1` suite runner
// (conformance/vectors/protocol-v1.json).
//
// Data authority: consema-rs/consema-conformance/src/protocol_v1.rs (the
// per-case dispatch and every construction is transcribed from the Rust
// handlers; the vector file carries the contract metadata but the facts are
// pinned in the handlers). The record wire shapes are transcribed from
// consema-rs/consema-protocol/src/execution.rs (Completion, ExecutionPolicy,
// CancellationRequest), change.rs (ChangeSetMessage), projection.rs
// (ProjectionPolicy/Rule/RequestMessage/ReportMessage/ResultMessage and the
// provenance records), query.rs (QueryResultMessage), and diagnostic.rs.
//
// Kotlin note: the record types of the payload contracts (completion,
// change-set, projection-*, provenance, query-result, cancellation,
// execution-policy) all ship in the Kotlin protocol package (RecordsShared.kt
// and siblings); the cases dispatch through their typed record surfaces,
// and the common envelope (ProtocolMessage.of/fromValue) plus the canonical
// transports (encodeJson/decodeJson, encodePvce/decodePvce) carry the
// dual-transport roundtrips. ProtocolMessage equality is contract +
// payload-value equality, mirroring the Rust derived Eq.
//
// Both ProcessLocalHandle rejection cases are implemented, not skipped:
// the runner dispatches protocol.diagnostic.require-source-binding and
// protocol.query.reject-native-handle to the strict wire surfaces, which
// refuse with core.protocol.process-local-handle@1 (requireSourceBinding /
// rejectNativeHandle below). The runner records zero skipped cases, and
// ConformanceRunnerTest asserts "no documented skips" (0 skipped).

package consema.conformance

import consema.core.Entry
import consema.core.ObjectBuilder
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat32
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvDate
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvLocalDateTime
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvOffsetDateTime
import consema.core.PvString
import consema.core.PvTime
import consema.core.equal
import consema.json.EditFailureException
import consema.json.EditTransactionBuilder
import consema.json.JsonProfile
import consema.json.RepresentationPolicy
import consema.json.commit
import consema.json.parse
import consema.protocol.CapabilityDeclaration
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.ContractId
import consema.protocol.ContractRegistry
import consema.protocol.ContractRegistryVersion
import consema.protocol.ContractStability
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.DiagnosticSourceBinding
import consema.protocol.Domains
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.ImplementationSupport
import consema.protocol.NativeMatchLocator
import consema.protocol.OperatorCall
import consema.protocol.Precondition
import consema.protocol.ProfileDescriptor
import consema.protocol.ProtocolErrorKind
import consema.protocol.ProtocolException
import consema.protocol.ProtocolLimits
import consema.protocol.ProtocolMessage
import consema.protocol.QueryDefinition
import consema.protocol.QueryDomain
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureKind
import consema.protocol.RegistryManifest
import consema.protocol.Roles
import consema.protocol.Severity
import consema.protocol.SupportKind
import consema.protocol.VerificationStatus
import consema.protocol.decodeJson
import consema.protocol.decodePvce
import consema.protocol.encodeJson
import consema.protocol.encodePvce
import consema.protocol.errorCodeManifestValueFor
import consema.protocol.exactFields
import consema.protocol.integerValue
import consema.protocol.invalid
import consema.protocol.nullableString
import consema.protocol.optionalString
import consema.protocol.parseContractReference
import consema.protocol.protocolError
import consema.protocol.referenceValue
import consema.protocol.schemaFields
import consema.protocol.sequenceOf
import consema.protocol.stringMapObject
import consema.protocol.stringOf
import consema.protocol.unsigned32
import consema.protocol.unsigned64
import java.math.BigInteger

/** Runs the `consema.protocol.conformance@1` suite. */
fun runProtocolV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runProtocolV1Case(case)
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

private fun runProtocolV1Case(case: CaseData) {
    when (case.id) {
        "protocol.diagnostic.require-source-binding" -> requireSourceBinding()
        "protocol.query.reject-native-handle" -> rejectNativeHandle()
        "protocol.json.null-vector" -> jsonNullVector()
        "protocol.json.all-kinds-roundtrip" -> jsonAllKinds()
        "protocol.json.reject-whitespace" -> rejectWhitespace()
        "protocol.json.reject-alternate-escape" -> rejectAlternateEscape()
        "protocol.json.reject-unknown-field" -> rejectUnknownField()
        "protocol.pvce.roundtrip-equivalent" -> pvceEquivalent()
        "protocol.resource.depth-limit" -> depthLimit()
        "protocol.envelope.dual-transport" -> envelopeDualTransport()
        "protocol.envelope.all-payloads-dual-transport" -> allPayloadsDualTransport()
        "protocol.envelope.reject-unknown-contract" -> rejectUnknownContract()
        "protocol.envelope.reject-schema-mismatch" -> rejectSchemaMismatch()
        "protocol.envelope.reject-schema-only-payload" -> rejectSchemaOnlyPayload()
        "protocol.envelope.reject-nested-envelope" -> rejectNestedEnvelope()
        "protocol.envelope.reject-semantic-model-identity" -> rejectSemanticModelIdentity()
        "protocol.profile.roundtrip" -> profileRoundtrip()
        "protocol.capability.conditional-roundtrip" -> capabilityRoundtrip()
        "protocol.capability.reject-contradiction" -> capabilityContradiction()
        "protocol.diagnostic.reject-category-registry-mismatch" -> diagnosticCategoryMismatch()
        "protocol.completion.reject-contradiction" -> completionContradiction()
        "protocol.completion.reject-unregistered-failure-code" -> completionUnregisteredCode()
        "protocol.query.definition-envelope" -> queryDefinitionEnvelope()
        "protocol.query.portable-result" -> queryPortableResult()
        "protocol.projection.request-roundtrip" -> projectionRequestRoundtrip()
        "protocol.projection.no-partial-value" -> projectionNoPartial()
        "protocol.projection.reject-unregistered-event-code" -> projectionUnregisteredCode()
        "protocol.provenance.externalized-roundtrip" -> provenanceRoundtrip()
        "protocol.change-set.actual-edit-roundtrip" -> changeSetRoundtrip()
        "protocol.registry.current-roundtrip" -> registryRoundtrip()
        "protocol.registry.error-code-schema" -> errorCodeSchema()
        "protocol.errors.query-codes-registered" -> queryCodesRegistered()
        else -> fail("runner does not recognize published case")
    }
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}

/** protocol.diagnostic.require-source-binding (protocol_v1.rs:511-527). */
private fun requireSourceBinding() {
    val failure = try {
        DiagnosticSourceBinding.requireTransferableSource()
        null
    } catch (e: ProtocolException) {
        e
    }
    ensure(failure != null && failure.kind == ProtocolErrorKind.PROCESS_LOCAL_HANDLE)
}

/** protocol.query.reject-native-handle (protocol_v1.rs:612-618). */
private fun rejectNativeHandle() {
    val failure = try {
        NativeMatchLocator.fromProcessLocal()
        null
    } catch (e: ProtocolException) {
        e
    }
    ensure(failure != null && failure.kind == ProtocolErrorKind.PROCESS_LOCAL_HANDLE)
}

private fun portableCapabilities(): CapabilitySet {
    val set = CapabilitySet()
    set.insert(CapabilityId("core.query.ordered-results", 1))
    return set
}

// ---------------------------------------------------------------------------
// Portable-value transports.
// ---------------------------------------------------------------------------

private fun jsonNullVector() {
    val expected =
        """{"schema":"core.portable-value-json@1","value":{"type":"Null"}}"""
    ensure(
        encodeJson(PvNull, ProtocolLimits.default)
            .contentEquals(expected.toByteArray(Charsets.UTF_8)),
    )
}

private fun allKinds(): PortableValue {
    val date = PvDate.of(BigInteger("2026"), 8, 4)
    val time = PvTime.of(1, 2, 3, PvDecimal.of(BigInteger("4"), BigInteger("-1")))
    val local = PvLocalDateTime(date, time)
    val offset = PvOffsetDateTime.of(local, 3600)
    val obj = ObjectBuilder()
    obj.insert("k", PvNull)
    val mapping = consema.core.EntryMappingBuilder()
    mapping.push(PvInteger(BigInteger.ONE), PvString("v"))
    return PvArray(
        listOf(
            PvNull,
            PvBoolean(true),
            PvInteger(BigInteger("12345678901234567890")),
            PvDecimal.of(BigInteger("12"), BigInteger("-1")),
            PvBinaryFloat32(0x7fc00001),
            PvBinaryFloat64(1L shl 63),
            PvString("文本"),
            PvBytes.of(byteArrayOf(0, 0xff.toByte())),
            date,
            time,
            local,
            offset,
            PvArray(emptyList()),
            obj.build(),
            mapping.build(),
        ),
    )
}

private fun jsonAllKinds() {
    val value = allKinds()
    val limits = ProtocolLimits.default
    ensure(equal(decodeJson(encodeJson(value, limits), limits), value))
}

private fun jsonDecodeFailure(bytes: ByteArray): ProtocolErrorKind? =
    try {
        decodeJson(bytes, ProtocolLimits.default)
        null
    } catch (e: ProtocolException) {
        e.kind
    }

private fun rejectWhitespace() {
    ensure(
        jsonDecodeFailure(
            " {\"schema\":\"core.portable-value-json@1\",\"value\":{\"type\":\"Null\"}}"
                .toByteArray(Charsets.UTF_8),
        ) == ProtocolErrorKind.NON_CANONICAL_JSON,
    )
}

private fun rejectAlternateEscape() {
    // The alternate u escape decodes to "x" but is not the canonical byte
    // form (the canonical re-encode check rejects it).
    val json =
        """{"schema":"core.portable-value-json@1","value":{"type":"String","value":"""" +
            "\\u0078" + """"}}"""
    ensure(
        jsonDecodeFailure(json.toByteArray(Charsets.UTF_8)) ==
            ProtocolErrorKind.NON_CANONICAL_JSON,
    )
}

private fun rejectUnknownField() {
    ensure(
        jsonDecodeFailure(
            """{"schema":"core.portable-value-json@1","value":{"type":"Null","x":true}}"""
                .toByteArray(Charsets.UTF_8),
        ) == ProtocolErrorKind.UNKNOWN_FIELD,
    )
}

private fun pvceEquivalent() {
    val value = allKinds()
    val limits = ProtocolLimits.default
    ensure(equal(decodePvce(encodePvce(value, limits), limits), value))
}

private fun depthLimit() {
    val limits = ProtocolLimits(
        maxBytes = ProtocolLimits.default.maxBytes,
        maxDepth = 0,
        maxNodes = ProtocolLimits.default.maxNodes,
        maxContainerEntries = ProtocolLimits.default.maxContainerEntries,
        maxBlobBytes = ProtocolLimits.default.maxBlobBytes,
        maxIntegerBytes = ProtocolLimits.default.maxIntegerBytes,
    )
    val failure = try {
        encodeJson(PvArray(listOf(PvNull)), limits)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.RESOURCE_LIMIT)
}

// ---------------------------------------------------------------------------
// Common envelope and payload wire records.
// ---------------------------------------------------------------------------

private val v1Registry: ContractRegistry =
    ContractRegistry.forVersion(ContractRegistryVersion.V1)

private val v1Errors: ErrorCodeRegistry =
    ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V1)

private fun envelopeJson(
    message: ProtocolMessage,
    registry: ContractRegistry,
    limits: ProtocolLimits,
): ProtocolMessage =
    ProtocolMessage.fromValue(decodeJson(encodeJson(message.toValue(), limits), limits), registry)

private fun envelopePvce(
    message: ProtocolMessage,
    registry: ContractRegistry,
    limits: ProtocolLimits,
): ProtocolMessage =
    ProtocolMessage.fromValue(decodePvce(encodePvce(message.toValue(), limits), limits), registry)

private fun messageEquals(a: ProtocolMessage, b: ProtocolMessage): Boolean =
    a.contract == b.contract && equal(a.payload, b.payload)

/** Validates completion facts against the v1 registry and encodes
 * `core.completion@1` (execution.rs:50-153). */
private fun completionValue(
    status: String,
    processed: Int,
    produced: Int,
    limitName: String?,
    failureCode: String?,
): PortableValue {
    if (failureCode != null) {
        v1Errors.validate(failureCode)
    }
    val valid = when (status) {
        "Success", "Cancelled" -> limitName == null && failureCode == null
        "ResourceLimited" ->
            limitName != null && limitName.isNotEmpty() && failureCode == null
        "Failed", "Unsupported", "NotApplicable" ->
            limitName == null && failureCode != null && failureCode.isNotEmpty()
        else -> false
    }
    if (!valid) {
        throw invalid("$", "completion status contradicts limit/failure fields")
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.completion@1")),
            Entry("status", PvString(status)),
            Entry("processed", integerValue(processed.toULong())),
            Entry("produced", integerValue(produced.toULong())),
            Entry("limit_name", nullableString(limitName)),
            Entry("failure_code", nullableString(failureCode)),
        ),
    )
}

private fun cancellationRequestValue(requestId: String, reason: String?): PortableValue {
    if (requestId.isEmpty() || requestId.length > 1024) {
        throw invalid("$.request_id", "invalid request ID")
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.cancellation-request@1")),
            Entry("request_id", PvString(requestId)),
            Entry("reason", nullableString(reason)),
        ),
    )
}

private fun validLimitName(name: String): Boolean =
    name.isNotEmpty() && name.length <= 255 &&
        name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

private fun executionPolicyValue(
    limits: Map<String, Long>,
    cancellationRequestId: String?,
): PortableValue {
    for (name in limits.keys) {
        if (!validLimitName(name)) {
            throw invalid("$.limits", "limit names must be stable lowercase identifiers")
        }
    }
    if (cancellationRequestId != null &&
        (cancellationRequestId.isEmpty() || cancellationRequestId.length > 1024)
    ) {
        throw invalid("$.cancellation_request_id", "invalid cancellation request ID")
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.execution-policy@1")),
            Entry(
                "limits",
                PvObject(
                    limits.toSortedMap().map { (name, value) ->
                        Entry(name, PvInteger(BigInteger.valueOf(value)))
                    },
                ),
            ),
            Entry("cancellation_request_id", nullableString(cancellationRequestId)),
        ),
    )
}

private fun diagnosticMessageValue(): PortableValue =
    Diagnostic.of(
        "json.syntax.expected-value@1",
        DiagnosticCategory.Syntax,
        Severity.Error,
        null,
        emptyList(),
        emptyMap(),
        emptyList(),
        emptyList(),
        0uL,
        v1Errors,
    ).toValue()

private fun sourceEditValue(
    oldStart: Int,
    oldEnd: Int,
    newStart: Int,
    newEnd: Int,
    replacement: ByteArray,
): PortableValue {
    if (oldStart > oldEnd || newStart > newEnd) {
        throw invalid("$.source_edits", "edit ranges must be half-open and ordered")
    }
    return PvObject(
        listOf(
            Entry("old_start", integerValue(oldStart.toULong())),
            Entry("old_end", integerValue(oldEnd.toULong())),
            Entry("new_start", integerValue(newStart.toULong())),
            Entry("new_end", integerValue(newEnd.toULong())),
            Entry("replacement", PvBytes.of(replacement)),
        ),
    )
}

private fun sourceEditFromValue(value: PortableValue): PortableValue {
    val fields = exactFields(
        value,
        listOf("old_start", "old_end", "new_start", "new_end", "replacement"),
        "$.source_edits",
    )
    val replacement = fields[4] as? PvBytes
        ?: throw protocolError(
            ProtocolErrorKind.WRONG_TYPE,
            "$.source_edits.replacement",
            "expected Bytes",
        )
    return sourceEditValue(
        unsigned64(fields[0], "$.source_edits.old_start").toInt(),
        unsigned64(fields[1], "$.source_edits.old_end").toInt(),
        unsigned64(fields[2], "$.source_edits.new_start").toInt(),
        unsigned64(fields[3], "$.source_edits.new_end").toInt(),
        replacement.content(),
    )
}

private fun nodeMappingValue(
    oldLocators: List<String>,
    newLocators: List<String>,
    status: String,
    reason: String?,
): PortableValue {
    val needsReason = status == "Deleted" || status == "Split" ||
        status == "Merged" || status == "Unmapped"
    val hasReason = reason != null && reason.isNotEmpty() && reason.length <= 1024
    if (needsReason != hasReason) {
        throw invalid("$.node_mapping", "mapping topology or reason contradicts status")
    }
    return PvObject(
        listOf(
            Entry("old_locators", PvArray(oldLocators.map { PvString(it) })),
            Entry("new_locators", PvArray(newLocators.map { PvString(it) })),
            Entry("status", PvString(status)),
            Entry("reason", nullableString(reason)),
        ),
    )
}

private fun nodeMappingFromValue(value: PortableValue): PortableValue {
    val fields = exactFields(
        value,
        listOf("old_locators", "new_locators", "status", "reason"),
        "$.node_mappings",
    )
    return nodeMappingValue(
        stringSequenceOf(fields[0], "$.node_mappings.old_locators"),
        stringSequenceOf(fields[1], "$.node_mappings.new_locators"),
        stringOf(fields[2], "$.node_mappings.status"),
        optionalString(fields[3], "$.node_mappings.reason"),
    )
}

private fun stringSequenceOf(value: PortableValue, path: String): List<String> =
    sequenceOf(value, path).mapIndexed { index, item ->
        stringOf(item, "$path[$index]")
    }

private fun changeSetValue(
    oldSourceId: String,
    newSourceId: String,
    sourceEdits: List<PortableValue>,
    nodeMappings: List<PortableValue>,
    diagnostics: List<PortableValue>,
): PortableValue {
    if (oldSourceId.isEmpty() || newSourceId.isEmpty() ||
        oldSourceId.length > 1024 || newSourceId.length > 1024
    ) {
        throw invalid("$", "source IDs must be non-empty and bounded")
    }
    data class EditRange(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int)
    val ranges = sourceEdits.map { value ->
        val fields = exactFields(
            value,
            listOf("old_start", "old_end", "new_start", "new_end", "replacement"),
            "$.source_edits",
        )
        EditRange(
            unsigned64(fields[0], "$.source_edits.old_start").toInt(),
            unsigned64(fields[1], "$.source_edits.old_end").toInt(),
            unsigned64(fields[2], "$.source_edits.new_start").toInt(),
            unsigned64(fields[3], "$.source_edits.new_end").toInt(),
        )
    }
    if (ranges.zipWithNext().any { (left, right) ->
            left.oldEnd > right.oldStart || left.newEnd > right.newStart
        }
    ) {
        throw invalid(
            "$.source_edits",
            "edits must be ordered and non-overlapping in both snapshots",
        )
    }
    val oldLocators = nodeMappings.flatMap { value ->
        val fields = exactFields(
            value,
            listOf("old_locators", "new_locators", "status", "reason"),
            "$.node_mappings",
        )
        stringSequenceOf(fields[0], "$.node_mappings.old_locators")
    }
    if (oldLocators.toSet().size != oldLocators.size) {
        throw invalid(
            "$.node_mappings",
            "an old locator may participate in only one mapping fact",
        )
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.change-set@1")),
            Entry("old_source_id", PvString(oldSourceId)),
            Entry("new_source_id", PvString(newSourceId)),
            Entry("source_edits", PvArray(sourceEdits)),
            Entry("node_mappings", PvArray(nodeMappings)),
            Entry("diagnostics", PvArray(diagnostics)),
        ),
    )
}

private fun changeSetFromValue(value: PortableValue): PortableValue {
    val fields = schemaFields(
        value,
        "core.change-set@1",
        listOf(
            "schema", "old_source_id", "new_source_id", "source_edits",
            "node_mappings", "diagnostics",
        ),
        "$",
    )
    val sourceEdits = sequenceOf(fields[3], "$.source_edits")
        .mapIndexed { index, item -> sourceEditFromValue(item) }
    val nodeMappings = sequenceOf(fields[4], "$.node_mappings")
        .mapIndexed { index, item -> nodeMappingFromValue(item) }
    val diagnostics = sequenceOf(fields[5], "$.diagnostics")
        .mapIndexed { index, item ->
            Diagnostic.fromValue(item, v1Errors).toValue()
        }
    return changeSetValue(
        stringOf(fields[1], "$.old_source_id"),
        stringOf(fields[2], "$.new_source_id"),
        sourceEdits,
        nodeMappings,
        diagnostics,
    )
}

private fun policyValue(
    id: String,
    version: Int,
    arguments: Map<String, PortableValue>,
): PortableValue = PvObject(
    listOf(
        Entry("id", PvString(id)),
        Entry("version", PvInteger(BigInteger.valueOf(version.toLong()))),
        Entry(
            "arguments",
            PvObject(arguments.toSortedMap().map { (name, argument) ->
                Entry(name, argument)
            }),
        ),
    ),
)

private fun policyFromValue(value: PortableValue, path: String): PortableValue {
    val fields = exactFields(value, listOf("id", "version", "arguments"), path)
    val argumentsValue = fields[2] as? PvObject
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.arguments", "expected Object")
    val arguments = LinkedHashMap<String, PortableValue>()
    for (entry in argumentsValue.entries()) {
        arguments[entry.key] = entry.value
    }
    return policyValue(
        stringOf(fields[0], "$path.id"),
        unsigned32(fields[1], "$path.version"),
        arguments,
    )
}

private fun scopeGlobalValue(): PortableValue =
    PvObject(listOf(Entry("kind", PvString("Global"))))

private fun scopeFromValue(value: PortableValue, path: String): PortableValue {
    val fields = exactFields(value, listOf("kind"), path)
    if (stringOf(fields[0], "$path.kind") != "Global") {
        throw invalid(path, "unknown projection scope")
    }
    return scopeGlobalValue()
}

private fun ruleValue(
    ruleId: String,
    scope: PortableValue,
    priority: Int,
    policy: PortableValue,
): PortableValue = PvObject(
    listOf(
        Entry("rule_id", PvString(ruleId)),
        Entry("scope", scope),
        Entry("priority", PvInteger(BigInteger.valueOf(priority.toLong()))),
        Entry("policy", policy),
    ),
)

private fun ruleFromValue(value: PortableValue, path: String): PortableValue {
    val fields = exactFields(value, listOf("rule_id", "scope", "priority", "policy"), path)
    val priority = unsigned32(fields[2], "$path.priority")
    return ruleValue(
        stringOf(fields[0], "$path.rule_id"),
        scopeFromValue(fields[1], "$path.scope"),
        priority,
        policyFromValue(fields[3], "$path.policy"),
    )
}

private fun ruleIdOf(value: PortableValue): String {
    val fields = exactFields(value, listOf("rule_id", "scope", "priority", "policy"), "$.rules")
    return stringOf(fields[0], "$.rules.rule_id")
}

private fun projectionRequestValue(
    target: ContractId,
    defaultPolicy: PortableValue,
    rules: List<PortableValue>,
    limits: Map<String, Long>,
): PortableValue {
    val ruleIds = rules.map { ruleIdOf(it) }
    if (ruleIds.toSet().size != ruleIds.size ||
        ruleIds.any { it.isEmpty() || it.length > 255 }
    ) {
        throw invalid("$.rules", "rule IDs must be non-empty and unique")
    }
    for (name in limits.keys) {
        if (!validLimitName(name)) {
            throw invalid("$.limits", "limit names must be stable lowercase identifiers")
        }
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.projection-request@1")),
            Entry("target", referenceValue(target.id, target.version)),
            Entry("default_policy", defaultPolicy),
            Entry("rules", PvArray(rules)),
            Entry(
                "limits",
                PvObject(
                    limits.toSortedMap().map { (name, value) ->
                        Entry(name, PvInteger(BigInteger.valueOf(value)))
                    },
                ),
            ),
        ),
    )
}

private fun projectionRequestFromValue(value: PortableValue): PortableValue {
    val fields = schemaFields(
        value,
        "core.projection-request@1",
        listOf("schema", "target", "default_policy", "rules", "limits"),
        "$",
    )
    val target = parseContractReference(fields[1], "$.target")
    val defaultPolicy = policyFromValue(fields[2], "$.default_policy")
    val rules = sequenceOf(fields[3], "$.rules")
        .mapIndexed { index, item -> ruleFromValue(item, "$.rules[$index]") }
    val limitsValue = fields[4] as? PvObject
        ?: throw protocolError(
            ProtocolErrorKind.WRONG_TYPE,
            "$.limits",
            "expected Object<String, Integer>",
        )
    val limits = LinkedHashMap<String, Long>()
    for (entry in limitsValue.entries()) {
        val number = (entry.value as? PvInteger)?.value
            ?: throw protocolError(
                ProtocolErrorKind.WRONG_TYPE,
                "$.limits.${entry.key}",
                "expected Integer",
            )
        limits[entry.key] = number.toLong()
    }
    return projectionRequestValue(target, defaultPolicy, rules, limits)
}

private fun projectionEventValue(
    code: String,
    policyRuleId: String?,
    sourceLocations: List<PortableValue>,
    projectedLocation: PortableValue?,
    oldCategory: String?,
    newCategory: String?,
    reversible: Boolean,
    lossClassification: String,
    arguments: Map<String, String>,
): PortableValue {
    v1Errors.validate(code)
    if (code.isEmpty() ||
        (lossClassification == "Lossy" && reversible) ||
        (lossClassification == "Reversible" && !reversible)
    ) {
        throw invalid("$.events", "projection event fields are contradictory")
    }
    return PvObject(
        listOf(
            Entry("code", PvString(code)),
            Entry("policy_rule_id", nullableString(policyRuleId)),
            Entry("source_locations", PvArray(sourceLocations)),
            Entry("projected_location", projectedLocation ?: PvNull),
            Entry("old_category", nullableString(oldCategory)),
            Entry("new_category", nullableString(newCategory)),
            Entry("reversible", PvBoolean(reversible)),
            Entry("loss_classification", PvString(lossClassification)),
            Entry("arguments", stringMapObject(arguments)),
        ),
    )
}

private fun projectionReportValue(events: List<PortableValue>): PortableValue = PvObject(
    listOf(
        Entry("schema", PvString("core.projection-report@1")),
        Entry("events", PvArray(events)),
    ),
)

private fun reportHasLossyEvent(report: PortableValue): Boolean {
    val events = (report as? PvObject)?.get("events") as? PvArray ?: return false
    return events.items().any { item ->
        (item as? PvObject)?.get("loss_classification") as? PvString ==
            PvString("Lossy")
    }
}

private fun provenanceHasEntries(provenance: PortableValue): Boolean {
    val entries = (provenance as? PvObject)?.get("entries") as? PvArray ?: return false
    return entries.items().isNotEmpty()
}

private fun projectionResultValue(
    completion: PortableValue,
    value: PortableValue?,
    fidelity: String?,
    report: PortableValue,
    provenance: PortableValue,
    diagnostics: List<PortableValue>,
): PortableValue {
    val status = (completion as? PvObject)?.get("status") as? PvString
        ?: throw invalid("$.completion", "completion status is absent")
    val success = status.value == "Success"
    if (success != (value != null) || success != (fidelity != null)) {
        throw invalid("$", "only successful projection may carry value and fidelity")
    }
    if (fidelity == "Lossy" && !reportHasLossyEvent(report)) {
        throw invalid("$.report", "Lossy fidelity requires an explicit lossy event")
    }
    if (!success && provenanceHasEntries(provenance)) {
        throw invalid("$.provenance", "failed projection cannot claim completed provenance")
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.projection-result@1")),
            Entry("completion", completion),
            Entry(
                "value",
                if (value == null) {
                    PvNull
                } else {
                    PvObject(listOf(Entry("portable_value", value)))
                },
            ),
            Entry("fidelity", if (fidelity == null) PvNull else PvString(fidelity)),
            Entry("report", report),
            Entry("provenance", provenance),
            Entry("diagnostics", PvArray(diagnostics)),
        ),
    )
}

private fun sourceOriginValue(
    sourceId: String,
    nodeLocator: String?,
    startByte: Int,
    endByte: Int,
    relation: String,
): PortableValue {
    if (sourceId.isEmpty() || sourceId.length > 1024 || startByte > endByte ||
        (nodeLocator != null && (nodeLocator.isEmpty() || nodeLocator.length > 4096))
    ) {
        throw invalid("$.origin", "invalid source identity, locator, or range")
    }
    return PvObject(
        listOf(
            Entry("source_id", PvString(sourceId)),
            Entry("node_locator", nullableString(nodeLocator)),
            Entry("start_byte", integerValue(startByte.toULong())),
            Entry("end_byte", integerValue(endByte.toULong())),
            Entry("relation", PvString(relation)),
        ),
    )
}

private fun sourceOriginFromValue(value: PortableValue): PortableValue {
    val fields = exactFields(
        value,
        listOf("source_id", "node_locator", "start_byte", "end_byte", "relation"),
        "$.entries.origins",
    )
    return sourceOriginValue(
        stringOf(fields[0], "$.entries.origins.source_id"),
        optionalString(fields[1], "$.entries.origins.node_locator"),
        unsigned64(fields[2], "$.entries.origins.start_byte").toInt(),
        unsigned64(fields[3], "$.entries.origins.end_byte").toInt(),
        stringOf(fields[4], "$.entries.origins.relation"),
    )
}

private fun valuePathRootValue(): PortableValue =
    PvObject(listOf(Entry("segments", PvArray(emptyList()))))

private fun projectedLocationFromValue(value: PortableValue): PortableValue {
    val fields = exactFields(value, listOf("kind", "value"), "$.entries.projected")
    if (stringOf(fields[0], "$.entries.projected.kind") != "ValuePath") {
        throw invalid("$.entries.projected", "unknown projected location")
    }
    exactFields(fields[1], listOf("segments"), "$.entries.projected.value")
    return PvObject(
        listOf(
            Entry("kind", PvString("ValuePath")),
            Entry("value", fields[1]),
        ),
    )
}

private fun provenanceMapValue(entries: List<PortableValue>): PortableValue {
    for (entry in entries) {
        val fields = exactFields(entry, listOf("projected", "origins"), "$.entries")
        val origins = sequenceOf(fields[1], "$.entries.origins")
        if (origins.isEmpty()) {
            throw invalid("$.entries", "provenance locations must have origins")
        }
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.provenance-map@1")),
            Entry("entries", PvArray(entries)),
        ),
    )
}

private fun provenanceMapFromValue(value: PortableValue): PortableValue {
    val fields = schemaFields(value, "core.provenance-map@1", listOf("schema", "entries"), "$")
    val entries = sequenceOf(fields[1], "$.entries").map { entry ->
        val entryFields = exactFields(entry, listOf("projected", "origins"), "$.entries")
        PvObject(
            listOf(
                Entry("projected", projectedLocationFromValue(entryFields[0])),
                Entry(
                    "origins",
                    PvArray(
                        sequenceOf(entryFields[1], "$.entries.origins")
                            .map { sourceOriginFromValue(it) },
                    ),
                ),
            ),
        )
    }
    return provenanceMapValue(entries)
}

private fun isV1Role(role: String): Boolean =
    role == Roles.VALUE || role == Roles.OBJECT_ENTRY || role == Roles.ENTRY_MAPPING_ENTRY

private fun valueMatchValue(value: PortableValue): PortableValue = PvObject(
    listOf(
        Entry("kind", PvString("Value")),
        Entry("path", valuePathRootValue()),
        Entry("value", value),
    ),
)

private fun queryResultValue(
    domain: QueryDomain,
    role: String,
    matches: List<PortableValue>,
    completion: PortableValue,
    diagnostics: List<PortableValue>,
): PortableValue {
    if (!isV1Role(role)) {
        throw invalid("$.role", "role is not published by core.query-result@1")
    }
    val completionFields = completion as? PvObject
        ?: throw invalid("$.completion", "completion must be Object")
    val produced = (completionFields.get("produced") as? PvInteger)?.value?.toLong()
        ?: throw invalid("$.completion.produced", "expected Integer")
    if (produced != matches.size.toLong()) {
        throw invalid("$", "completion count or match role is inconsistent")
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.query-result@1")),
            Entry("domain_id", PvString(domain.id)),
            Entry("domain_version", PvInteger(BigInteger.valueOf(domain.version.toLong()))),
            Entry("role", PvString(role)),
            Entry("matches", PvArray(matches.map { valueMatchValue(it) })),
            Entry("completion", completion),
            Entry("diagnostics", PvArray(diagnostics)),
        ),
    )
}

private fun queryResultFromValue(value: PortableValue): PortableValue {
    val fields = schemaFields(
        value,
        "core.query-result@1",
        listOf(
            "schema", "domain_id", "domain_version", "role", "matches",
            "completion", "diagnostics",
        ),
        "$",
    )
    val domainId = stringOf(fields[1], "$.domain_id")
    val domainVersion = unsigned32(fields[2], "$.domain_version")
    val role = stringOf(fields[3], "$.role")
    if (!isV1Role(role)) {
        throw invalid("$.role", "role is not published by core.query-result@1")
    }
    val matches = sequenceOf(fields[4], "$.matches").map { item ->
        val matchFields = exactFields(item, listOf("kind", "path", "value"), "$.matches")
        if (stringOf(matchFields[0], "$.matches.kind") != "Value") {
            throw invalid("$.matches", "unknown query match kind")
        }
        exactFields(matchFields[1], listOf("segments"), "$.matches.path")
        // The wire match record wraps the value; from_value unwraps it so
        // the rebuilt record wraps it exactly once (the Rust
        // QueryResultMessage::from_value mirror).
        matchFields[2]
    }
    val completionFields = schemaFields(
        fields[5],
        "core.completion@1",
        listOf(
            "schema", "status", "processed", "produced", "limit_name", "failure_code",
        ),
        "$.completion",
    )
    val produced = unsigned64(completionFields[3], "$.completion.produced").toInt()
    if (produced != matches.size) {
        throw invalid("$", "completion count or match role is inconsistent")
    }
    val completion = completionValue(
        stringOf(completionFields[1], "$.completion.status"),
        unsigned64(completionFields[2], "$.completion.processed").toInt(),
        produced,
        optionalString(completionFields[4], "$.completion.limit_name"),
        optionalString(completionFields[5], "$.completion.failure_code"),
    )
    val diagnostics = sequenceOf(fields[6], "$.diagnostics")
        .mapIndexed { index, item ->
            Diagnostic.fromValue(item, v1Errors).toValue()
        }
    return queryResultValue(
        QueryDomain(domainId, domainVersion),
        role,
        matches,
        completion,
        diagnostics,
    )
}

// ---------------------------------------------------------------------------
// Cases.
// ---------------------------------------------------------------------------

private fun envelopeDualTransport() {
    val registry = v1Registry
    val message = ProtocolMessage.of(
        ContractId("core.completion", 1),
        completionValue("Success", 1, 1, null, null),
        registry,
    )
    val limits = ProtocolLimits.default
    ensure(
        messageEquals(envelopeJson(message, registry, limits), message) &&
            messageEquals(envelopePvce(message, registry, limits), message),
    )
}

private fun allPayloadsDualTransport() {
    val registry = v1Registry
    val profile = ProfileDescriptor.of(
        "toml",
        1,
        "toml.1.0",
        1,
        null,
        emptyList(),
        emptyList(),
    ).toValue()
    val capability = CapabilityDeclaration.of(
        CapabilityId("core.query.ordered-results", 1),
        ImplementationSupport(SupportKind.Conformant, emptyList()),
        VerificationStatus.SelfDeclared,
        null,
    ).toValue()
    val projectionPolicy = policyValue("core.projection.exact-or-reject", 1, emptyMap())
    val projectionRequest = projectionRequestValue(
        ContractId("json.projection.best-exact-core", 1),
        projectionPolicy,
        emptyList(),
        emptyMap(),
    )
    val completion = completionValue("Success", 0, 0, null, null)
    val queryDefinition = QueryDefinition(Domains.portableValueV1()).toProtocolValue()
    val queryResult = queryResultValue(
        Domains.portableValueV1(),
        Roles.VALUE,
        emptyList(),
        completion,
        emptyList(),
    )
    val projectionResult = projectionResultValue(
        completion,
        PvNull,
        "Exact",
        projectionReportValue(emptyList()),
        provenanceMapValue(emptyList()),
        emptyList(),
    )
    val payloads: List<Pair<ContractId, PortableValue>> = listOf(
        ContractId("core.cancellation-request", 1) to
            cancellationRequestValue("request:1", null),
        ContractId("core.capability-declaration", 1) to capability,
        ContractId("core.change-set", 1) to
            changeSetValue("source:old", "source:new", emptyList(), emptyList(), emptyList()),
        ContractId("core.completion", 1) to completion,
        ContractId("core.diagnostic", 1) to diagnosticMessageValue(),
        ContractId("core.error-code-registry", 1) to errorCodeManifestValueFor(v1Errors),
        ContractId("core.execution-policy", 1) to executionPolicyValue(emptyMap(), null),
        ContractId("core.profile-descriptor", 1) to profile,
        ContractId("core.projection-report", 1) to projectionReportValue(emptyList()),
        ContractId("core.projection-request", 1) to projectionRequest,
        ContractId("core.projection-result", 1) to projectionResult,
        ContractId("core.provenance-map", 1) to provenanceMapValue(emptyList()),
        ContractId("core.query-definition", 1) to queryDefinition,
        ContractId("core.query-result", 1) to queryResult,
        ContractId("core.registry-manifest", 1) to
            RegistryManifest.of(1, registry, v1Errors).toValue(),
    )
    val expected = registry.contracts()
        .filter { it.stability == ContractStability.Stable }
        .map { "${it.id}@${it.version}" }
        .toSet()
    val actual = payloads.map { it.first.schema() }.toSet()
    if (actual != expected || payloads.size != 15) {
        fail("dual-transport samples do not exactly cover the stable registry")
    }
    val limits = ProtocolLimits.default
    for ((contract, payload) in payloads) {
        val message = ProtocolMessage.of(contract, payload, registry)
        if (!messageEquals(envelopeJson(message, registry, limits), message) ||
            !messageEquals(envelopePvce(message, registry, limits), message)
        ) {
            fail("dual-transport mismatch for ${message.contract.schema()}")
        }
    }
}

private fun rejectUnknownContract() {
    val payload = PvObject(listOf(Entry("schema", PvString("example.unknown@1"))))
    val failure = try {
        ProtocolMessage.of(ContractId("example.unknown", 1), payload, v1Registry)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.UNKNOWN_CONTRACT)
}

private fun rejectSchemaMismatch() {
    val failure = try {
        ProtocolMessage.of(
            ContractId("core.diagnostic", 1),
            completionValue("Success", 1, 1, null, null),
            v1Registry,
        )
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.SCHEMA_MISMATCH)
}

private fun rejectSchemaOnlyPayload() {
    val payload = PvObject(
        listOf(
            Entry("schema", PvString("core.diagnostic@1")),
            Entry("placeholder", PvNull),
        ),
    )
    val failure = try {
        ProtocolMessage.of(ContractId("core.diagnostic", 1), payload, v1Registry)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.UNKNOWN_FIELD)
}

private fun rejectNestedEnvelope() {
    val payload = PvObject(listOf(Entry("schema", PvString("core.protocol-message@1"))))
    val failure = try {
        ProtocolMessage.of(ContractId("core.protocol-message", 1), payload, v1Registry)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.INVALID_VALUE)
}

private fun rejectSemanticModelIdentity() {
    val payload = PvObject(listOf(Entry("schema", PvString("core.semantic-model@1"))))
    val failure = try {
        ProtocolMessage.of(ContractId("core.semantic-model", 1), payload, v1Registry)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.UNKNOWN_CONTRACT)
}

private fun profileRoundtrip() {
    val profile = ProfileDescriptor.of(
        "toml",
        1,
        "toml.1.0",
        1,
        null,
        listOf("toml.datetime"),
        listOf(CapabilityId("core.document.exact-roundtrip", 1)),
    )
    val decoded = ProfileDescriptor.fromValue(profile.toValue())
    ensure(
        decoded.formatFamilyId == profile.formatFamilyId &&
            decoded.formatFamilyVersion == profile.formatFamilyVersion &&
            decoded.profileId == profile.profileId &&
            decoded.profileVersion == profile.profileVersion &&
            decoded.baseProfile == profile.baseProfile &&
            decoded.differences == profile.differences &&
            decoded.requiredCapabilities == profile.requiredCapabilities,
    )
}

private fun capabilityRoundtrip() {
    val declaration = CapabilityDeclaration.of(
        CapabilityId("toml.projection.best-exact-core", 1),
        ImplementationSupport(
            SupportKind.Conditional,
            listOf(Precondition("profile", "toml.1.0@1")),
        ),
        VerificationStatus.Verified,
        "consema.protocol.conformance",
    )
    val decoded = CapabilityDeclaration.fromValue(declaration.toValue())
    ensure(
        decoded.capability == declaration.capability &&
            decoded.support == declaration.support &&
            decoded.verification == declaration.verification &&
            decoded.suiteId == declaration.suiteId,
    )
}

private fun capabilityContradiction() {
    val failure = try {
        CapabilityDeclaration.of(
            CapabilityId("core.query.ordered-results", 1),
            ImplementationSupport(SupportKind.Conditional, emptyList()),
            VerificationStatus.Unverified,
            null,
        )
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.INVALID_VALUE)
}

private fun diagnosticCategoryMismatch() {
    val payload = PvObject(
        listOf(
            Entry("schema", PvString("core.diagnostic@1")),
            Entry("code", PvString("json.object.duplicate-member@1")),
            Entry("category", PvString("Syntax")),
            Entry("severity", PvString("Error")),
            Entry("primary", PvNull),
            Entry("related", PvArray(emptyList())),
            Entry("arguments", PvObject(emptyList())),
            Entry("notes", PvArray(emptyList())),
            Entry("fixes", PvArray(emptyList())),
            Entry("occurrence", PvInteger(BigInteger.ZERO)),
        ),
    )
    val failure = try {
        Diagnostic.fromValue(payload, v1Errors)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.INVALID_VALUE)
}

private fun completionContradiction() {
    val failure = try {
        completionValue("Success", 1, 1, "max_steps", null)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.INVALID_VALUE)
}

private fun completionUnregisteredCode() {
    val failure = try {
        completionValue("Failed", 1, 0, null, "example.failure@1")
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.INVALID_VALUE)
}

private fun queryDefinitionEnvelope() {
    val definition = QueryDefinition(Domains.portableValueV1()).withExpression(
        QueryExpression(ExpressionKind.Input)
            .then(OperatorCall("core.try-sequence-elements", 1)),
    )
    val limits = ProtocolLimits.default
    val before = encodePvce(definition.toProtocolValue(), limits)
    val message = ProtocolMessage.of(
        ContractId("core.query-definition", 1),
        definition.toProtocolValue(),
        v1Registry,
    )
    val decoded = QueryDefinition.fromProtocolValue(message.payload)
    val after = encodePvce(decoded.toProtocolValue(), limits)
    ensure(
        equal(decoded.toProtocolValue(), definition.toProtocolValue()) &&
            before.contentEquals(after),
    )
}

private fun queryPortableResult() {
    val definition = QueryDefinition(Domains.portableValueV1())
    val executable = try {
        ExecutableQuery.bind(definition.validate(), portableCapabilities())
    } catch (e: consema.protocol.QueryFailureException) {
        fail("definition: ${e.kind.code}")
    }
    val matches = executePortableQuery(
        executable,
        PvString("x"),
        PortableQueryLimits.default,
    )
    val completion = completionValue("Success", matches.size, matches.size, null, null)
    val value = queryResultValue(
        Domains.portableValueV1(),
        Roles.VALUE,
        matches,
        completion,
        emptyList(),
    )
    ensure(equal(queryResultFromValue(value), value))
}

private fun projectionRequestRoundtrip() {
    val policy = policyValue("core.projection.exact-or-reject", 1, emptyMap())
    val request = projectionRequestValue(
        ContractId("json.projection.best-exact-core", 1),
        policy,
        listOf(ruleValue("global", scopeGlobalValue(), 0, policy)),
        emptyMap(),
    )
    ensure(equal(projectionRequestFromValue(request), request))
}

private fun projectionNoPartial() {
    val failed = completionValue(
        "Failed",
        1,
        0,
        null,
        "core.projection.target-not-applicable@1",
    )
    val failure = try {
        projectionResultValue(
            failed,
            PvNull,
            "Exact",
            projectionReportValue(emptyList()),
            provenanceMapValue(emptyList()),
            emptyList(),
        )
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.INVALID_VALUE)
}

private fun projectionUnregisteredCode() {
    val failure = try {
        val event = projectionEventValue(
            "example.projection@1",
            null,
            emptyList(),
            null,
            null,
            null,
            false,
            "None",
            emptyMap(),
        )
        projectionReportValue(listOf(event))
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(failure == ProtocolErrorKind.INVALID_VALUE)
}

private fun provenanceRoundtrip() {
    val entry = PvObject(
        listOf(
            Entry(
                "projected",
                PvObject(
                    listOf(
                        Entry("kind", PvString("ValuePath")),
                        Entry("value", valuePathRootValue()),
                    ),
                ),
            ),
            Entry(
                "origins",
                PvArray(listOf(sourceOriginValue("source:one", "toml:root", 0, 1, "Direct"))),
            ),
        ),
    )
    val map = provenanceMapValue(listOf(entry))
    ensure(equal(provenanceMapFromValue(map), map))
}

private fun changeSetRoundtrip() {
    val document = try {
        parse("1".toByteArray(Charsets.UTF_8), JsonProfile.StrictV1, consema.document.ParseLimits.default)
    } catch (e: Exception) {
        fail("parse failed: ${e.message}")
    }
    val builder = EditTransactionBuilder.new(document)
    builder.semanticScalar(
        document.root().nodeRef(),
        PvInteger(BigInteger("2")),
        RepresentationPolicy.CanonicalForProfile,
    )
    val commit = try {
        document.commit(builder.build())
    } catch (e: EditFailureException) {
        fail("commit: ${e.failure.name}")
    }
    var delta = 0L
    val sourceEdits = commit.sourcePatch.replacements().map { edit ->
        val newStart = edit.oldStart + delta
        val newEnd = newStart + edit.replacement().size
        delta += edit.replacement().size - edit.original().size
        sourceEditValue(
            edit.oldStart,
            edit.oldEnd,
            newStart.toInt(),
            newEnd.toInt(),
            edit.replacement(),
        )
    }
    val nodeMappings = listOf(
        nodeMappingValue(
            listOf("json:root:old"),
            listOf("json:root:new"),
            "Replaced",
            null,
        ),
    )
    val message = changeSetValue(
        "source:old",
        "source:new",
        sourceEdits,
        nodeMappings,
        emptyList(),
    )
    val firstEdit = sourceEdits.firstOrNull() as? PvObject ?: fail("source edit missing")
    val replacement = firstEdit.get("replacement") as? PvBytes ?: fail("replacement bytes")
    ensure(
        replacement.content().contentEquals("2".toByteArray(Charsets.UTF_8)) &&
            equal(changeSetFromValue(message), message),
    )
}

private fun registryRoundtrip() {
    val manifest = RegistryManifest.of(1, v1Registry, v1Errors)
    val decoded = RegistryManifest.fromValue(manifest.toValue())
    ensure(
        manifestEqualsFields(decoded, manifest) &&
            decoded.semanticModel == ContractId("core.semantic-model", 1) &&
            decoded.contracts.zipWithNext().all { (left, right) ->
                left.contract < right.contract
            } &&
            decoded.errorCodes.zipWithNext().all { (left, right) -> left.code < right.code },
    )
}

private fun manifestEqualsFields(a: RegistryManifest, b: RegistryManifest): Boolean =
    a.semanticModel == b.semanticModel &&
        a.contracts == b.contracts &&
        a.errorCodes == b.errorCodes

private fun errorCodeSchema() {
    val manifest = errorCodeManifestValueFor(v1Errors)
    consema.protocol.validateErrorCodeManifestValue(manifest)
}

private fun queryCodesRegistered() {
    ensure(QueryFailureKind.entries.all { v1Errors.contains(it.code) })
}
