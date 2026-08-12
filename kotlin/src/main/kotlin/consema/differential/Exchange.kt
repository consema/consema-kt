// The Kotlin side of the cross-language protocol exchange harness
// (milestone 0.19.0 G5.3; docs/five-language-ci-design.md §3.4; the Go
// precedent go/conformance/differential/protocol-exchange/exchange_test.go;
// the Rust example crates/consema-conformance/examples/emit_protocol_exchange.rs
// is the byte authority for the golden files).
//
// For every case (83: 40 accept + 43 reject):
//   - accept cases: both sides decode the canonical transport JSON with the
//     full typed record decoder, re-encode byte-identically on both
//     transports (canonical JSON and PVCE/1), and the cross-language bytes
//     are byte-equal; each side decodes the other side's bytes, the typed
//     record is equivalent (value-tree equality through the typed record
//     codec), and re-encoding is byte-identical;
//   - reject cases: both sides reject the same transport bytes with the
//     same registered error code (core.protocol.*@1). Error text never
//     participates in any comparison (RFC 0016 §6).
//
// The machine schema of the case file is the RFC 0015 protocol schema
// discriminator (core.cli-output@1, ...); it contains no Rust type names.
// The Kotlin side emits its own encoder bytes into
// CONSEMA_EXCHANGE_KT_DIR (`<case-id>.json.hex` / `<case-id>.pvce.hex` /
// `<case-id>.error.txt`), which the Rust example's --verify mode closes
// over (Kotlin encode -> Rust decode direction).

package consema.differential

import consema.core.Entry
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.equal as coreEqual
import consema.protocol.CapabilityDeclaration
import consema.protocol.ProtocolException
import consema.protocol.ProtocolErrorKind
import consema.protocol.ProtocolLimits
import consema.protocol.SourceLimits
import consema.protocol.SourcePatchLimits
import consema.protocol.decodeJson
import consema.protocol.decodePvce
import consema.protocol.encodeJson
import consema.protocol.encodePvce
import consema.protocol.validateErrorCodeManifestValue
import java.io.File
import java.math.BigInteger

/** The frozen manifest id of the exchange input set. */
const val EXCHANGE_MANIFEST = "consema.differential.protocol-exchange@1"

/** The task's lower bound for the input set ("至少 40 个 case"). */
const val EXCHANGE_MIN_CASES = 40

/** The closed record inventory of the exchange set. It is exactly the
 * protocol record surface both implementations decode in full (the Go
 * payload.go dispatch intersect crates/consema-protocol payload.rs
 * dispatch). No Rust type names appear anywhere in the case file. */
val EXCHANGE_ALL_RECORDS: List<String> = listOf(
    "core.batch-plan@1",
    "core.batch-result@1",
    "core.cancellation-request@1",
    "core.capability-declaration@1",
    "core.change-set@1",
    "core.cli-output@1",
    "core.completion@1",
    "core.diagnostic@1",
    "core.error-code-registry@1",
    "core.execution-policy@1",
    "core.graph-projection-result@1",
    "core.graph-provenance-map@1",
    "core.graph-query-result@1",
    "core.ini-query-result@1",
    "core.java-properties-query-result@1",
    "core.java-utf16-string@1",
    "core.materialization-request@2",
    "core.materialization-result@2",
    "core.portable-graph@1",
    "core.portable-value-json@1",
    "core.profile-descriptor@1",
    "core.projection-report@1",
    "core.projection-request@1",
    "core.projection-result@1",
    "core.provenance-map@1",
    "core.query-definition@1",
    "core.query-result@1",
    "core.registry-manifest@1",
    "core.source-encoding@1",
    "core.source-patch@2",
    "core.source-snapshot@2",
    "core.yaml-query-result@1",
)

/** One entry of cases.json. */
data class ExchangeCase(
    val id: String,
    val record: String,
    val json: String,
    val expectedErrorCode: String,
)

/** The outcome of one exchange run. */
data class ExchangeReport(
    val acceptPassed: Int,
    val acceptCount: Int,
    val rejectPassed: Int,
    val rejectCount: Int,
    val failures: List<String>,
)

/** Loads and validates the checked-in case set: manifest id, case count
 * lower bound, unique ids, known records, per-record positive and negative
 * coverage, canonical transport JSON, and registered expected codes. */
fun loadExchangeCaseFile(file: File): List<ExchangeCase> {
    val root = loadCaseFile(file)
    val manifest = objectString(root, "manifest", "case file")
    require(manifest == EXCHANGE_MANIFEST) {
        "cases.json manifest = $manifest, want $EXCHANGE_MANIFEST"
    }
    val caseValues = objectArray(root, "cases", "case file")
    require(caseValues.size >= EXCHANGE_MIN_CASES) {
        "cases.json has ${caseValues.size} cases, want >= $EXCHANGE_MIN_CASES (the differential input set)"
    }
    val known = EXCHANGE_ALL_RECORDS.toSet()
    val coverage = HashMap<String, IntArray>() // record -> {accept, reject}
    val seen = HashSet<String>()
    val limits = ProtocolLimits.default
    val registry = consema.protocol.ErrorCodeRegistry.forVersion(
        consema.protocol.ErrorRegistryVersion.V7,
    )
    val cases = ArrayList<ExchangeCase>(caseValues.size)
    for (value in caseValues) {
        val fields = value as? PvObject ?: error("case must be an Object")
        val id = objectString(fields, "id", "case")
        require(id.isNotEmpty()) { "case with an empty id" }
        require(seen.add(id)) { "duplicate case id $id" }
        val record = objectString(fields, "record", "case $id")
        require(record in known) { "case $id: record $record is not in the exchange inventory" }
        val json = objectString(fields, "json", "case $id")
        val expectedErrorCode = objectObjectOr(fields, "expected")
            ?.let { objectStringOr(it, "error_code") }
            ?: ""
        if (expectedErrorCode.isNotEmpty()) {
            require(registry.contains(expectedErrorCode)) {
                "case $id: expected code $expectedErrorCode is not a registered protocol code"
            }
            coverage.getOrPut(record) { IntArray(2) }[1]++
            cases.add(ExchangeCase(id, record, json, expectedErrorCode))
            continue
        }
        coverage.getOrPut(record) { IntArray(2) }[0]++
        // The strict canonicality check (parse + re-encode) keeps the file's
        // transport JSON honest: the Rust side must accept the same text.
        val valueDecoded = decodeJson(json.toByteArray(Charsets.UTF_8), limits)
        // Accept cases must re-encode byte-identically through the typed
        // record codec on both transports.
        val recordValue = decodeExchangeRecord(record, valueDecoded)
        val reEncoded = encodeJson(recordValue, limits)
        require(reEncoded.contentEquals(json.toByteArray(Charsets.UTF_8))) {
            "case $id: Kotlin typed re-encode is not byte-identical to the case json"
        }
        encodePvce(recordValue, limits)
        cases.add(ExchangeCase(id, record, json, expectedErrorCode))
    }
    for (record in EXCHANGE_ALL_RECORDS) {
        val counts = coverage[record] ?: IntArray(2)
        require(counts[0] > 0 && counts[1] > 0) {
            "record $record has no ${if (counts[0] == 0) "accept" else "reject"} case in the exchange set"
        }
    }
    return cases
}

// ---------------------------------------------------------------------------
// The typed record dispatch (the payload.rs / decodeRecord mirror)
// ---------------------------------------------------------------------------

private val v7Registry: consema.protocol.ErrorCodeRegistry =
    consema.protocol.ErrorCodeRegistry.forVersion(consema.protocol.ErrorRegistryVersion.V7)

private val v6Registry: consema.protocol.ErrorCodeRegistry =
    consema.protocol.ErrorCodeRegistry.forVersion(consema.protocol.ErrorRegistryVersion.V6)

private val v1Registry: consema.protocol.ErrorCodeRegistry =
    consema.protocol.ErrorCodeRegistry.forVersion(consema.protocol.ErrorRegistryVersion.V1)

/**
 * Dispatches one record schema to its full typed record decoder and returns
 * the record's re-encodeable value tree. The dispatch mirrors the
 * payload.rs validate_registered_payload table; the typed decode re-validates
 * every cross constraint. core.portable-value-json@1 has no record-level
 * decoder: the transported value is the record.
 */
fun decodeExchangeRecord(record: String, value: PortableValue): PortableValue = when (record) {
    "core.cli-output@1" -> consema.protocol.CliOutputMessage.fromValueWithRegistry(value, v7Registry).toValue()
    "core.batch-plan@1" -> consema.protocol.BatchPlanMessage.fromValueWithRegistry(value, v7Registry).toValue()
    "core.batch-result@1" -> consema.protocol.BatchResultMessage.fromValue(value).toValue()
    "core.cancellation-request@1" -> exchangeCancellation(value)
    "core.capability-declaration@1" -> CapabilityDeclaration.fromValue(value).toValue()
    "core.change-set@1" -> exchangeChangeSet(value)
    "core.completion@1" -> consema.protocol.Completion.fromValueWithRegistry(value, v7Registry).toValue()
    "core.diagnostic@1" -> consema.protocol.Diagnostic.fromValue(value, v7Registry).toValue()
    "core.error-code-registry@1" -> {
        validateErrorCodeManifestValue(value)
        value
    }
    "core.execution-policy@1" -> exchangeExecutionPolicy(value)
    "core.graph-projection-result@1" -> consema.protocol.GraphProjectionResultMessage.fromValue(value).toValue()
    "core.graph-provenance-map@1" -> consema.protocol.GraphProvenanceMapMessage.fromValue(value).toValue()
    "core.graph-query-result@1" -> consema.protocol.GraphQueryResultMessage.fromValue(value).toValue()
    "core.ini-query-result@1" -> consema.protocol.IniQueryResultMessage.fromValue(value).toValue()
    "core.java-properties-query-result@1" ->
        consema.protocol.JavaPropertiesQueryResultMessage.fromValue(value).toValue()
    "core.java-utf16-string@1" -> exchangeJavaUtf16(value)
    "core.materialization-request@2" -> consema.protocol.MaterializationRequestMessageV2.fromValue(value).toValue()
    "core.materialization-result@2" ->
        // The Rust default registry for this record is v6; mirror it.
        consema.protocol.MaterializationResultMessageV2.fromValueWithRegistry(value, v6Registry).toValue()
    "core.portable-graph@1" ->
        consema.protocol.PortableGraphMessage.fromValue(value, consema.graph.PgceLimits.default).toValue()
    "core.portable-value-json@1" -> value
    "core.profile-descriptor@1" -> consema.protocol.ProfileDescriptor.fromValue(value).toValue()
    "core.projection-report@1" -> exchangeProjectionReport(value)
    "core.projection-request@1" -> exchangeProjectionRequest(value)
    "core.projection-result@1" -> exchangeProjectionResult(value)
    "core.provenance-map@1" -> exchangeProvenanceMap(value)
    "core.query-definition@1" -> {
        // The registry dispatch mirrors the payload.rs mapping: any
        // QueryFailure becomes KindInvalidValue at "$.payload" (the same
        // mapping as validate_registered_payload and the Go payload.go).
        val definition = try {
            consema.protocol.QueryDefinition.fromProtocolValue(value)
        } catch (e: consema.protocol.QueryFailureException) {
            throw consema.protocol.ProtocolException(
                ProtocolErrorKind.INVALID_VALUE,
                "$.payload",
                "invalid query definition: ${e.message}",
            )
        }
        try {
            definition.toProtocolValue()
        } catch (e: consema.protocol.QueryFailureException) {
            throw consema.protocol.ProtocolException(
                ProtocolErrorKind.INVALID_VALUE,
                "$.payload",
                "invalid query definition: ${e.message}",
            )
        }
    }
    "core.query-result@1" -> exchangeQueryResult(value)
    "core.registry-manifest@1" -> consema.protocol.RegistryManifest.fromValue(value).toValue()
    "core.source-encoding@1" -> consema.protocol.SourceEncoding.fromValue(value, "$").toValue()
    "core.source-patch@2" ->
        consema.protocol.SourcePatchMessageV2.fromValue(value, SourcePatchLimits.default).toValue()
    "core.source-snapshot@2" ->
        consema.protocol.SourceSnapshotMessageV2.fromValue(value, SourceLimits.default).toValue()
    "core.yaml-query-result@1" -> consema.protocol.YamlQueryResultMessage.fromValue(value).toValue()
    else -> throw consema.protocol.ProtocolException(
        ProtocolErrorKind.UNKNOWN_CONTRACT,
        "$.contract",
        "record $record is not in the exchange inventory",
    )
}

// ---------------------------------------------------------------------------
// The record decoders the protocol package ships in a later milestone. The
// wire authority is the Rust record codec; the decode/re-encode discipline
// is the fixed-field schema discipline of the protocol package.
// ---------------------------------------------------------------------------

/** Strictly decodes and re-encodes `core.cancellation-request@1`
 * (execution.rs:190-236). */
private fun exchangeCancellation(value: PortableValue): PortableValue {
    val fields = schemaFieldsOf(value, "core.cancellation-request@1", listOf("schema", "request_id", "reason"))
    val requestId = stringOf(fields[1], "$.request_id")
    if (requestId.isEmpty() || requestId.length > 1024) {
        throw invalid("$.request_id", "invalid request ID")
    }
    val reason = optionalString(fields[2], "$.reason")
    return PvObject(
        listOf(
            Entry("schema", PvString("core.cancellation-request@1")),
            Entry("request_id", PvString(requestId)),
            Entry("reason", nullableString(reason)),
        ),
    )
}

/** The stable limit-name rule of the execution-policy record
 * (execution.rs:244-268). */
private fun validLimitName(name: String): Boolean =
    name.isNotEmpty() && name.length <= 255 &&
        name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

/** Strictly decodes and re-encodes `core.execution-policy@1`
 * (execution.rs:240-330). */
private fun exchangeExecutionPolicy(value: PortableValue): PortableValue {
    val fields = schemaFieldsOf(
        value,
        "core.execution-policy@1",
        listOf("schema", "limits", "cancellation_request_id"),
    )
    val limitsObject = fields[1] as? PvObject
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$.limits", "expected Object<String, Integer>")
    val limits = LinkedHashMap<String, Long>()
    for (entry in limitsObject.entries()) {
        if (!validLimitName(entry.key)) {
            throw invalid("$.limits", "limit names must be stable lowercase identifiers")
        }
        val number = (entry.value as? PvInteger)?.value
            ?: throw protocolError(
                ProtocolErrorKind.WRONG_TYPE,
                "$.limits.${entry.key}",
                "expected Integer",
            )
        limits[entry.key] = number.toLong()
    }
    val cancellationRequestId = optionalString(fields[2], "$.cancellation_request_id")
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

/** Strictly decodes and re-encodes `core.change-set@1` (change_set.rs:
 * 30-160): bounded source IDs, ordered non-overlapping source edits, unique
 * old locators, and the node-mapping status/reason invariant table. */
private fun exchangeChangeSet(value: PortableValue): PortableValue {
    val fields = schemaFieldsOf(
        value,
        "core.change-set@1",
        listOf("schema", "old_source_id", "new_source_id", "source_edits", "node_mappings", "diagnostics"),
    )
    val oldSourceId = stringOf(fields[1], "$.old_source_id")
    val newSourceId = stringOf(fields[2], "$.new_source_id")
    if (oldSourceId.isEmpty() || newSourceId.isEmpty() ||
        oldSourceId.length > 1024 || newSourceId.length > 1024
    ) {
        throw invalid("$", "source IDs must be non-empty and bounded")
    }
    data class EditRange(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int)
    val sourceEdits = consema.protocol.sequenceOf(fields[3], "$.source_edits").mapIndexed { index, item ->
        val path = "$.source_edits[$index]"
        val editFields = exactFieldsOf(
            item,
            listOf("old_start", "old_end", "new_start", "new_end", "replacement"),
            path,
        )
        val replacement = editFields[4] as? PvBytes
            ?: throw protocolError(
                ProtocolErrorKind.WRONG_TYPE,
                "$path.replacement",
                "expected Bytes",
            )
        EditRange(
            unsigned64Of(editFields[0], "$path.old_start").toInt(),
            unsigned64Of(editFields[1], "$path.old_end").toInt(),
            unsigned64Of(editFields[2], "$path.new_start").toInt(),
            unsigned64Of(editFields[3], "$path.new_end").toInt(),
        ).also {
            if (it.oldStart > it.oldEnd || it.newStart > it.newEnd) {
                throw invalid("$path", "edit ranges must be half-open and ordered")
            }
        }
        PvObject(
            listOf(
                Entry("old_start", editFields[0]),
                Entry("old_end", editFields[1]),
                Entry("new_start", editFields[2]),
                Entry("new_end", editFields[3]),
                Entry("replacement", replacement),
            ),
        )
    }
    val ranges = sourceEdits.map { editFieldsOf(it) }
    if (ranges.zipWithNext().any { (left, right) ->
            left[0] > right[1] || left[2] > right[3]
        }
    ) {
        throw invalid(
            "$.source_edits",
            "edits must be ordered and non-overlapping in both snapshots",
        )
    }
    val nodeMappings = consema.protocol.sequenceOf(fields[4], "$.node_mappings").mapIndexed { index, item ->
        val path = "$.node_mappings[$index]"
        val mappingFields = exactFieldsOf(
            item,
            listOf("old_locators", "new_locators", "status", "reason"),
            path,
        )
        val oldLocators = stringSequenceOf(mappingFields[0], "$path.old_locators")
        val newLocators = stringSequenceOf(mappingFields[1], "$path.new_locators")
        val status = stringOf(mappingFields[2], "$path.status")
        val reason = optionalString(mappingFields[3], "$path.reason")
        val needsReason = status == "Deleted" || status == "Split" ||
            status == "Merged" || status == "Unmapped"
        val hasReason = reason != null && reason.isNotEmpty() && reason.length <= 1024
        if (needsReason != hasReason) {
            throw invalid("$path", "mapping topology or reason contradicts status")
        }
        PvObject(
            listOf(
                Entry("old_locators", PvArray(oldLocators.map { PvString(it) })),
                Entry("new_locators", PvArray(newLocators.map { PvString(it) })),
                Entry("status", PvString(status)),
                Entry("reason", nullableString(reason)),
            ),
        )
    }
    val oldLocatorSet = nodeMappings.flatMap { mapping ->
        val mappingFields = exactFieldsOf(
            mapping,
            listOf("old_locators", "new_locators", "status", "reason"),
            "$.node_mappings",
        )
        stringSequenceOf(mappingFields[0], "$.node_mappings.old_locators")
    }
    if (oldLocatorSet.toSet().size != oldLocatorSet.size) {
        throw invalid(
            "$.node_mappings",
            "an old locator may participate in only one mapping fact",
        )
    }
    val diagnostics = consema.protocol.sequenceOf(fields[5], "$.diagnostics").map { item ->
        consema.protocol.Diagnostic.fromValue(item, v7Registry).toValue()
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

/** The (old_start, old_end, new_start, new_end) ranges of one re-encoded
 * source edit (the change-set order check). */
private fun editFieldsOf(edit: PortableValue): IntArray {
    val fields = exactFieldsOf(
        edit,
        listOf("old_start", "old_end", "new_start", "new_end", "replacement"),
        "$.source_edits",
    )
    return intArrayOf(
        unsigned64Of(fields[0], "$.source_edits.old_start").toInt(),
        unsigned64Of(fields[1], "$.source_edits.old_end").toInt(),
        unsigned64Of(fields[2], "$.source_edits.new_start").toInt(),
        unsigned64Of(fields[3], "$.source_edits.new_end").toInt(),
    )
}

private fun stringSequenceOf(value: PortableValue, path: String): List<String> =
    consema.protocol.sequenceOf(value, path).mapIndexed { index, item ->
        stringOf(item, "$path[$index]")
    }

/** Strictly decodes and re-encodes `core.java-utf16-string@1`
 * (java_utf16.rs:92-168): the canonical uppercase unit spellings, the
 * UTF16BE/1 byte identity, the exact surrogate-pairing status, and the
 * canonical re-encoding. */
private fun exchangeJavaUtf16(value: PortableValue): PortableValue {
    val fields = schemaFieldsOf(
        value,
        "core.java-utf16-string@1",
        listOf("schema", "encoding", "code_units", "bytes", "unicode_status"),
    )
    if (stringOf(fields[1], "$.encoding") != "UTF16BE/1") {
        throw invalid("$.encoding", "requires the fixed UTF16BE/1 encoding")
    }
    val unitValues = consema.protocol.sequenceOf(fields[2], "$.code_units")
    if (unitValues.size > ProtocolLimits.default.maxContainerEntries) {
        throw resource("$.code_units", "code units exceed the protocol limit")
    }
    val bytes = (fields[3] as? PvBytes)?.content()
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$.bytes", "expected Bytes")
    if (bytes.size > ProtocolLimits.default.maxBlobBytes) {
        throw resource("$.bytes", "byte blob exceeds the protocol limit")
    }
    if (bytes.size % 2 != 0 || bytes.size != unitValues.size * 2) {
        throw invalid("$.bytes", "byte length contradicts the code units")
    }
    val units = IntArray(unitValues.size)
    for ((index, unitValue) in unitValues.withIndex()) {
        val path = "$.code_units[$index]"
        val text = stringOf(unitValue, path)
        val unit = parseJavaUtf16Unit(text) ?: throw invalid(path, "invalid code unit spelling")
        if (bytes[index * 2].toInt() and 0xff != (unit ushr 8) ||
            bytes[index * 2 + 1].toInt() and 0xff != (unit and 0xff)
        ) {
            throw invalid(path, "code unit contradicts the bytes")
        }
        units[index] = unit
    }
    val claimedStatus = stringOf(fields[4], "$.unicode_status")
    val actualStatus = javaUtf16StatusName(
        consema.properties.JavaString.fromCodeUnits(units.map { it.toChar() }.toCharArray()).status,
    )
    if (claimedStatus != actualStatus) {
        throw invalid("$.unicode_status", "status contradicts the code units")
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.java-utf16-string@1")),
            Entry("encoding", PvString("UTF16BE/1")),
            Entry("code_units", PvArray(units.map { PvString("%04X".format(it)) })),
            Entry("bytes", PvBytes.of(bytes)),
            Entry("unicode_status", PvString(claimedStatus)),
        ),
    )
}

/** Parses one code unit only in the canonical uppercase four-hex-digit
 * spelling (java_utf16.rs:181-190). */
private fun parseJavaUtf16Unit(text: String): Int? {
    if (text.length != 4) {
        return null
    }
    for (character in text) {
        if (character.isLowerCase() || Character.digit(character, 16) < 0) {
            return null
        }
    }
    return text.toIntOrNull(16)
}

private fun javaUtf16StatusName(status: consema.properties.JavaStringStatus): String =
    when (status) {
        consema.properties.JavaStringStatus.WellFormedUnicode -> "WellFormedUnicode"
        consema.properties.JavaStringStatus.UnpairedSurrogate -> "UnpairedSurrogate"
    }

/** Strictly decodes and re-encodes `core.projection-request@1`
 * (projection.rs:99-195): the versioned target reference (non-zero version),
 * the default policy, unique bounded rule IDs, and the limit-name rule. */
private fun exchangeProjectionRequest(value: PortableValue): PortableValue {
    val fields = schemaFieldsOf(
        value,
        "core.projection-request@1",
        listOf("schema", "target", "default_policy", "rules", "limits"),
    )
    val target = parseContractReference(fields[1], "$.target")
    val defaultPolicy = exchangePolicy(fields[2], "$.default_policy")
    val rules = consema.protocol.sequenceOf(fields[3], "$.rules").mapIndexed { index, item ->
        exchangeRule(item, "$.rules[$index]")
    }
    val ruleIds = rules.map { ruleIdOf(it) }
    if (ruleIds.toSet().size != ruleIds.size ||
        ruleIds.any { it.isEmpty() || it.length > 255 }
    ) {
        throw invalid("$.rules", "rule IDs must be non-empty and unique")
    }
    val limitsObject = fields[4] as? PvObject
        ?: throw protocolError(
            ProtocolErrorKind.WRONG_TYPE,
            "$.limits",
            "expected Object<String, Integer>",
        )
    val limits = LinkedHashMap<String, Long>()
    for (entry in limitsObject.entries()) {
        if (!validLimitName(entry.key)) {
            throw invalid("$.limits", "limit names must be stable lowercase identifiers")
        }
        val number = (entry.value as? PvInteger)?.value
            ?: throw protocolError(
                ProtocolErrorKind.WRONG_TYPE,
                "$.limits.${entry.key}",
                "expected Integer",
            )
        limits[entry.key] = number.toLong()
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
                    limits.toSortedMap().map { (name, number) ->
                        Entry(name, PvInteger(BigInteger.valueOf(number)))
                    },
                ),
            ),
        ),
    )
}

/** One versioned policy call of the projection records (projection.rs:
 * 60-98). */
private fun exchangePolicy(value: PortableValue, path: String): PortableValue {
    val fields = exactFieldsOf(value, listOf("id", "version", "arguments"), path)
    val reference = parseContractReferenceOf(fields, path)
    val argumentsValue = fields[2] as? PvObject
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.arguments", "expected Object")
    val arguments = LinkedHashMap<String, PortableValue>()
    for (entry in argumentsValue.entries()) {
        arguments[entry.key] = entry.value
    }
    return PvObject(
        listOf(
            Entry("id", PvString(reference.id)),
            Entry("version", PvInteger(BigInteger.valueOf(reference.version.toLong()))),
            Entry(
                "arguments",
                PvObject(arguments.toSortedMap().map { (name, argument) -> Entry(name, argument) }),
            ),
        ),
    )
}

/** One projection rule (projection.rs:99-145). */
private fun exchangeRule(value: PortableValue, path: String): PortableValue {
    val fields = exactFieldsOf(value, listOf("rule_id", "scope", "priority", "policy"), path)
    val scopeFields = exactFieldsOf(fields[1], listOf("kind"), "$path.scope")
    if (stringOf(scopeFields[0], "$path.scope.kind") != "Global") {
        throw invalid("$path.scope", "unknown projection scope")
    }
    return PvObject(
        listOf(
            Entry("rule_id", PvString(stringOf(fields[0], "$path.rule_id"))),
            Entry("scope", PvObject(listOf(Entry("kind", PvString("Global"))))),
            Entry("priority", fields[2]),
            Entry("policy", exchangePolicy(fields[3], "$path.policy")),
        ),
    )
}

/** The rule ID of one decoded rule (the uniqueness check). */
private fun ruleIdOf(rule: PortableValue): String {
    val fields = exactFieldsOf(rule, listOf("rule_id", "scope", "priority", "policy"), "$.rules")
    return stringOf(fields[0], "$.rules.rule_id")
}

/** Strictly decodes one versioned contract reference (projection.rs:56-68):
 * the version must be non-zero (the ContractId constructor rule,
 * contract.rs:22-25). */
private fun parseContractReference(value: PortableValue, path: String): consema.protocol.ContractId {
    val fields = exactFieldsOf(value, listOf("id", "version"), path)
    return parseContractReferenceOf(fields, path)
}

private fun parseContractReferenceOf(
    fields: List<PortableValue>,
    path: String,
): consema.protocol.ContractId =
    consema.protocol.ContractId(stringOf(fields[0], "$path.id"), unsigned32Of(fields[1], "$path.version"))

private fun referenceValue(id: String, version: Int): PortableValue =
    PvObject(
        listOf(
            Entry("id", PvString(id)),
            Entry("version", PvInteger(BigInteger.valueOf(version.toLong()))),
        ),
    )

/** Strictly decodes and re-encodes `core.projection-report@1`
 * (projection.rs:446-521): registered event codes and the
 * loss-classification/reversible invariant table. */
private fun exchangeProjectionReport(value: PortableValue): PortableValue {
    val fields = schemaFieldsOf(value, "core.projection-report@1", listOf("schema", "events"))
    val events = consema.protocol.sequenceOf(fields[1], "$.events").mapIndexed { index, item ->
        exchangeProjectionEvent(item, "$.events[$index]")
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.projection-report@1")),
            Entry("events", PvArray(events)),
        ),
    )
}

/** One projection event (projection.rs:446-483). */
private fun exchangeProjectionEvent(value: PortableValue, path: String): PortableValue {
    val fields = exactFieldsOf(
        value,
        listOf(
            "code", "policy_rule_id", "source_locations", "projected_location",
            "old_category", "new_category", "reversible", "loss_classification", "arguments",
        ),
        path,
    )
    val code = stringOf(fields[0], "$path.code")
    v1Registry.validate(code)
    val reversible = booleanOf(fields[6], "$path.reversible")
    val lossClassification = stringOf(fields[7], "$path.loss_classification")
    if (code.isEmpty() ||
        (lossClassification == "Lossy" && reversible) ||
        (lossClassification == "Reversible" && !reversible)
    ) {
        throw invalid("$path", "projection event fields are contradictory")
    }
    return PvObject(
        listOf(
            Entry("code", PvString(code)),
            Entry("policy_rule_id", fields[1]),
            Entry("source_locations", fields[2]),
            Entry("projected_location", fields[3]),
            Entry("old_category", fields[4]),
            Entry("new_category", fields[5]),
            Entry("reversible", fields[6]),
            Entry("loss_classification", fields[7]),
            Entry("arguments", fields[8]),
        ),
    )
}

/** Strictly decodes and re-encodes `core.projection-result@1`
 * (projection.rs:529-683): the success/value/fidelity invariant, the Lossy
 * fidelity report requirement, and the failed-projection provenance rule. */
private fun exchangeProjectionResult(value: PortableValue): PortableValue {
    val fields = schemaFieldsOf(
        value,
        "core.projection-result@1",
        listOf("schema", "completion", "value", "fidelity", "report", "provenance", "diagnostics"),
    )
    val completion = consema.protocol.Completion.fromValueWithRegistry(fields[1], v1Registry).toValue()
    val status = (fields[1] as? PvObject)?.get("status") as? PvString
        ?: throw invalid("$.completion", "completion status is absent")
    val success = status.value == "Success"
    val valueField = fields[2]
    val fidelityField = fields[3]
    val hasValue = valueField !is PvNull
    val hasFidelity = fidelityField !is PvNull
    if (success != hasValue || success != hasFidelity) {
        throw invalid("$", "only successful projection may carry value and fidelity")
    }
    val report = exchangeProjectionReport(fields[4])
    if (hasFidelity && (fidelityField as PvString).value == "Lossy" && !reportHasLossyEvent(report)) {
        throw invalid("$.report", "Lossy fidelity requires an explicit lossy event")
    }
    val provenance = exchangeProvenanceMap(fields[5])
    if (!success && provenanceHasEntries(provenance)) {
        throw invalid("$.provenance", "failed projection cannot claim completed provenance")
    }
    val diagnostics = consema.protocol.sequenceOf(fields[6], "$.diagnostics").map { item ->
        consema.protocol.Diagnostic.fromValue(item, v1Registry).toValue()
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.projection-result@1")),
            Entry("completion", completion),
            Entry("value", valueField),
            Entry("fidelity", fidelityField),
            Entry("report", report),
            Entry("provenance", provenance),
            Entry("diagnostics", PvArray(diagnostics)),
        ),
    )
}

private fun reportHasLossyEvent(report: PortableValue): Boolean {
    val events = (report as? PvObject)?.get("events") as? PvArray ?: return false
    return events.items().any { item ->
        (item as? PvObject)?.get("loss_classification") as? PvString == PvString("Lossy")
    }
}

private fun provenanceHasEntries(provenance: PortableValue): Boolean {
    val entries = (provenance as? PvObject)?.get("entries") as? PvArray ?: return false
    return entries.items().isNotEmpty()
}

/** Strictly decodes and re-encodes `core.provenance-map@1`
 * (projection.rs:328-443): sorted unique projected locations and non-empty
 * origins. */
private fun exchangeProvenanceMap(value: PortableValue): PortableValue {
    val fields = schemaFieldsOf(value, "core.provenance-map@1", listOf("schema", "entries"))
    val entries = consema.protocol.sequenceOf(fields[1], "$.entries").mapIndexed { index, item ->
        val path = "$.entries[$index]"
        val entryFields = exactFieldsOf(item, listOf("projected", "origins"), path)
        val projectedFields = exactFieldsOf(
            entryFields[0],
            listOf("kind", "value"),
            "$path.projected",
        )
        if (stringOf(projectedFields[0], "$path.projected.kind") != "ValuePath") {
            throw invalid("$path.projected", "unknown projected location")
        }
        exactFieldsOf(projectedFields[1], listOf("segments"), "$path.projected.value")
        val origins = consema.protocol.sequenceOf(entryFields[1], "$path.origins").map { origin ->
            val originFields = exactFieldsOf(
                origin,
                listOf("source_id", "node_locator", "start_byte", "end_byte", "relation"),
                "$path.origins",
            )
            val sourceId = stringOf(originFields[0], "$path.origins.source_id")
            val nodeLocator = optionalString(originFields[1], "$path.origins.node_locator")
            val startByte = unsigned64Of(originFields[2], "$path.origins.start_byte").toInt()
            val endByte = unsigned64Of(originFields[3], "$path.origins.end_byte").toInt()
            val relation = stringOf(originFields[4], "$path.origins.relation")
            if (sourceId.isEmpty() || sourceId.length > 1024 || startByte > endByte ||
                (nodeLocator != null && (nodeLocator.isEmpty() || nodeLocator.length > 4096))
            ) {
                throw invalid("$path.origins", "invalid source identity, locator, or range")
            }
            PvObject(
                listOf(
                    Entry("source_id", PvString(sourceId)),
                    Entry("node_locator", nullableString(nodeLocator)),
                    Entry("start_byte", integerValueOf(startByte)),
                    Entry("end_byte", integerValueOf(endByte)),
                    Entry("relation", PvString(relation)),
                ),
            )
        }
        if (origins.isEmpty()) {
            throw invalid("$path", "provenance locations must have origins")
        }
        PvObject(
            listOf(
                Entry("projected", entryFields[0]),
                Entry("origins", PvArray(origins)),
            ),
        )
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.provenance-map@1")),
            Entry("entries", PvArray(entries)),
        ),
    )
}

private fun integerValueOf(value: Int): PortableValue = PvInteger(BigInteger.valueOf(value.toLong()))

/** The roles published by `core.query-result@1` (query.rs:628-692): the
 * portable roles plus the JSON/TOML native roles; the family roles (graph,
 * YAML, INI, Properties, XML, plist, HCL) are not published. */
private fun isQueryResultV1Role(role: String): Boolean = when (role) {
    "Value", "ObjectEntry", "EntryMappingEntry",
    "JsonValue", "JsonObjectMember", "JsonArrayElement",
    "TomlItem", "TomlEntry", "TomlArrayElement",
    "JsonSyntaxPiece", "TomlSyntaxPiece" -> true
    else -> false
}

private fun parseQueryMatchRole(role: String): String {
    if (!isQueryResultV1Role(role)) {
        throw invalid("$.role", "unknown query match role")
    }
    return role
}

/** Strictly decodes and re-encodes `core.query-result@1` (query.rs:283-350):
 * the published roles, the match role consistency, the completion count, and
 * the strictly increasing native ordinals. */
private fun exchangeQueryResult(value: PortableValue): PortableValue {
    val fields = schemaFieldsOf(
        value,
        "core.query-result@1",
        listOf("schema", "domain_id", "domain_version", "role", "matches", "completion", "diagnostics"),
    )
    val domainId = stringOf(fields[1], "$.domain_id")
    val domainVersion = unsigned32Of(fields[2], "$.domain_version")
    val role = parseQueryMatchRole(stringOf(fields[3], "$.role"))
    val matches = consema.protocol.sequenceOf(fields[4], "$.matches").mapIndexed { index, item ->
        val path = "$.matches[$index]"
        val entries = (item as? PvObject)?.entries()
            ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected match Object")
        val kind = entries.firstOrNull()
            ?.takeIf { it.key == "kind" }
            ?.value as? PvString
            ?: throw invalid(path, "kind must be the first String field")
        when (kind.value) {
            "Value" -> {
                val matchFields = exactFieldsOf(item, listOf("kind", "path", "value"), path)
                exactFieldsOf(matchFields[1], listOf("segments"), "$path.path")
                PvObject(
                    listOf(
                        Entry("kind", PvString("Value")),
                        Entry("path", matchFields[1]),
                        Entry("value", matchFields[2]),
                    ),
                )
            }
            "ObjectEntry" -> {
                val matchFields = exactFieldsOf(
                    item,
                    listOf("kind", "location", "key", "value_path", "value"),
                    path,
                )
                exchangeAssociation(matchFields[1], "$path.location")
                exactFieldsOf(matchFields[3], listOf("segments"), "$path.value_path")
                PvObject(
                    listOf(
                        Entry("kind", PvString("ObjectEntry")),
                        Entry("location", matchFields[1]),
                        Entry("key", matchFields[2]),
                        Entry("value_path", matchFields[3]),
                        Entry("value", matchFields[4]),
                    ),
                )
            }
            "EntryMappingEntry" -> {
                val matchFields = exactFieldsOf(
                    item,
                    listOf("kind", "location", "key_path", "key", "value_path", "value"),
                    path,
                )
                exchangeAssociation(matchFields[1], "$path.location")
                exactFieldsOf(matchFields[2], listOf("segments"), "$path.key_path")
                exactFieldsOf(matchFields[4], listOf("segments"), "$path.value_path")
                PvObject(
                    listOf(
                        Entry("kind", PvString("EntryMappingEntry")),
                        Entry("location", matchFields[1]),
                        Entry("key_path", matchFields[2]),
                        Entry("key", matchFields[3]),
                        Entry("value_path", matchFields[4]),
                        Entry("value", matchFields[5]),
                    ),
                )
            }
            "Native" -> {
                val matchFields = exactFieldsOf(
                    item,
                    listOf("kind", "role", "source_id", "node_locator", "ordinal"),
                    path,
                )
                val matchRole = parseQueryMatchRole(stringOf(matchFields[1], "$path.role"))
                val sourceId = stringOf(matchFields[2], "$path.source_id")
                val nodeLocator = stringOf(matchFields[3], "$path.node_locator")
                if (sourceId.isEmpty() || sourceId.length > 1024 ||
                    nodeLocator.isEmpty() || nodeLocator.length > 4096
                ) {
                    throw invalid("$path", "invalid source identity or locator")
                }
                PvObject(
                    listOf(
                        Entry("kind", PvString("Native")),
                        Entry("role", PvString(matchRole)),
                        Entry("source_id", PvString(sourceId)),
                        Entry("node_locator", PvString(nodeLocator)),
                        Entry("ordinal", matchFields[4]),
                    ),
                )
            }
            else -> throw invalid(path, "unknown query match kind")
        }
    }
    val nativeOrdinals = matches.mapNotNull { match ->
        val entries = (match as? PvObject)?.entries()
        if (entries?.firstOrNull()?.value as? PvString == PvString("Native")) {
            unsigned64Of(entries!![4].value, "$.matches.ordinal").toLong()
        } else {
            null
        }
    }
    if (nativeOrdinals.zipWithNext().any { (left, right) -> left >= right }) {
        throw invalid("$.matches", "native match ordinals must be strictly increasing")
    }
    val completion = consema.protocol.Completion.fromValueWithRegistry(fields[5], v1Registry).toValue()
    val produced = (fields[5] as? PvObject)?.get("produced") as? PvInteger
        ?: throw invalid("$.completion.produced", "expected Integer")
    if (produced.value.toLong() != matches.size.toLong() ||
        matches.any { matchRoleOf(it) != role }
    ) {
        throw invalid("$", "completion count or match role is inconsistent")
    }
    val diagnostics = consema.protocol.sequenceOf(fields[6], "$.diagnostics").map { item ->
        consema.protocol.Diagnostic.fromValue(item, v1Registry).toValue()
    }
    return PvObject(
        listOf(
            Entry("schema", PvString("core.query-result@1")),
            Entry("domain_id", PvString(domainId)),
            Entry("domain_version", PvInteger(BigInteger.valueOf(domainVersion.toLong()))),
            Entry("role", PvString(role)),
            Entry("matches", PvArray(matches)),
            Entry("completion", completion),
            Entry("diagnostics", PvArray(diagnostics)),
        ),
    )
}

/** The record role of one decoded match (the uniform-role check). */
private fun matchRoleOf(match: PortableValue): String {
    val entries = (match as? PvObject)?.entries() ?: return ""
    val kind = entries.firstOrNull()?.value as? PvString ?: return ""
    return if (kind.value == "Native") {
        (entries.getOrNull(1)?.value as? PvString)?.value ?: ""
    } else {
        kind.value
    }
}

/** Strictly checks one association location record (query.rs:525-553). */
private fun exchangeAssociation(value: PortableValue, path: String) {
    val fields = exactFieldsOf(value, listOf("container", "ordinal", "role"), path)
    exactFieldsOf(fields[0], listOf("segments"), "$path.container")
    val role = stringOf(fields[2], "$path.role")
    if (role != "ObjectEntry" && role != "ObjectKey" && role != "EntryMappingEntry") {
        throw invalid("$path.role", "unknown association role")
    }
}

// ---------------------------------------------------------------------------
// Shared schema helpers (the protocol package's fixed-field discipline)
// ---------------------------------------------------------------------------

private fun schemaFieldsOf(value: PortableValue, schema: String, expected: List<String>): List<PortableValue> =
    consema.protocol.schemaFields(value, schema, expected, "$")

private fun exactFieldsOf(value: PortableValue, expected: List<String>, path: String): List<PortableValue> =
    consema.protocol.exactFields(value, expected, path)

private fun stringOf(value: PortableValue, path: String): String =
    consema.protocol.stringOf(value, path)

private fun booleanOf(value: PortableValue, path: String): Boolean =
    consema.protocol.booleanOf(value, path)

private fun unsigned32Of(value: PortableValue, path: String): Int =
    consema.protocol.unsigned32(value, path)

private fun unsigned64Of(value: PortableValue, path: String): ULong =
    consema.protocol.unsigned64(value, path)

private fun nullableString(value: String?): PortableValue =
    consema.protocol.nullableString(value)

private fun optionalString(value: PortableValue, path: String): String? =
    consema.protocol.optionalString(value, path)

private fun invalid(path: String, detail: String): ProtocolException =
    consema.protocol.invalid(path, detail)

private fun resource(path: String, detail: String): ProtocolException =
    consema.protocol.resource(path, detail)

private fun protocolError(kind: ProtocolErrorKind, path: String, detail: String): ProtocolException =
    consema.protocol.protocolError(kind, path, detail)

// ---------------------------------------------------------------------------
// The verification flow
// ---------------------------------------------------------------------------

/** The registered rejection code of one reject case (transport then typed
 * record decoder). */
fun exchangeRejectionCode(case: ExchangeCase, limits: ProtocolLimits): String {
    return try {
        val value = decodeJson(case.json.toByteArray(Charsets.UTF_8), limits)
        decodeExchangeRecord(case.record, value)
        ""
    } catch (e: ProtocolException) {
        e.kind.code
    } catch (e: consema.protocol.QueryFailureException) {
        e.kind.code
    } catch (e: Exception) {
        ""
    }
}

/** Reads one hex byte file. */
private fun readHexFile(dir: File, name: String): ByteArray {
    val text = File(dir, "$name.hex").readText()
    return unhex(text)
}

/** Reads one recorded rejection code file. */
private fun readErrorFile(dir: File, id: String): String =
    File(dir, "$id.error.txt").readText().trim()

/** Reports a byte-level difference with the first differing offset and the
 * full hex of both sides. */
private fun exchangeFirstDiff(id: String, direction: String, kotlinBytes: ByteArray, rustBytes: ByteArray): String {
    var index = 0
    while (index < kotlinBytes.size && index < rustBytes.size && kotlinBytes[index] == rustBytes[index]) {
        index++
    }
    return "case $id ($direction): Kotlin ${kotlinBytes.size} bytes, Rust ${rustBytes.size} bytes, " +
        "first difference at offset $index\n  Kotlin: ${hex(kotlinBytes)}\n  Rust:   ${hex(rustBytes)}"
}

/** Verifies one accept case end to end. */
private fun verifyAcceptCase(
    case: ExchangeCase,
    rustDir: File,
    ktDir: File?,
    limits: ProtocolLimits,
): List<String> {
    val failures = ArrayList<String>()
    val value = try {
        decodeJson(case.json.toByteArray(Charsets.UTF_8), limits)
    } catch (e: Exception) {
        return listOf("case ${case.id}: case json no longer decodes: ${e.message}")
    }
    val recordValue = try {
        decodeExchangeRecord(case.record, value)
    } catch (e: Exception) {
        return listOf("case ${case.id}: Kotlin typed record decode failed: ${e.message}")
    }
    val kotlinJSON = try {
        encodeJson(recordValue, limits)
    } catch (e: Exception) {
        return listOf("case ${case.id}: Kotlin JSON encode failed: ${e.message}")
    }
    val kotlinPVCE = try {
        encodePvce(recordValue, limits)
    } catch (e: Exception) {
        return listOf("case ${case.id}: Kotlin PVCE encode failed: ${e.message}")
    }
    ktDir?.let { dir ->
        File(dir, "${case.id}.json.hex").writeText(hex(kotlinJSON) + "\n")
        File(dir, "${case.id}.pvce.hex").writeText(hex(kotlinPVCE) + "\n")
    }

    // Rust encoder bytes must be byte-equal on both transports.
    val rustJSON = try {
        readHexFile(rustDir, "${case.id}.json")
    } catch (e: Exception) {
        return listOf("case ${case.id}: missing Rust byte file: ${e.message}")
    }
    val rustPVCE = try {
        readHexFile(rustDir, "${case.id}.pvce")
    } catch (e: Exception) {
        return listOf("case ${case.id}: missing Rust byte file: ${e.message}")
    }
    if (!kotlinJSON.contentEquals(rustJSON)) {
        failures.add(exchangeFirstDiff(case.id, "json", kotlinJSON, rustJSON))
    }
    if (!kotlinPVCE.contentEquals(rustPVCE)) {
        failures.add(exchangeFirstDiff(case.id, "pvce", kotlinPVCE, rustPVCE))
    }

    // Rust encode -> Kotlin decode over the JSON transport.
    try {
        val rustValue = decodeJson(rustJSON, limits)
        val rustRecord = decodeExchangeRecord(case.record, rustValue)
        if (!coreEqual(rustRecord, recordValue)) {
            failures.add("case ${case.id}: Kotlin typed decode of the Rust JSON is not equivalent to the case record")
        } else {
            val reEncoded = encodeJson(rustRecord, limits)
            if (!reEncoded.contentEquals(rustJSON)) {
                failures.add("case ${case.id}: Kotlin JSON re-encode of the Rust bytes is not byte-identical")
            }
        }
    } catch (e: Exception) {
        failures.add("case ${case.id}: Kotlin cannot decode the Rust JSON bytes: ${e.message}")
    }

    // Rust encode -> Kotlin decode over the PVCE transport.
    try {
        val rustValue = decodePvce(rustPVCE, limits)
        val rustRecord = decodeExchangeRecord(case.record, rustValue)
        if (!coreEqual(rustRecord, recordValue)) {
            failures.add("case ${case.id}: Kotlin typed decode of the Rust PVCE is not equivalent to the case record")
        } else {
            val reEncoded = encodePvce(rustRecord, limits)
            if (!reEncoded.contentEquals(rustPVCE)) {
                failures.add("case ${case.id}: Kotlin PVCE re-encode of the Rust bytes is not byte-identical")
            }
        }
    } catch (e: Exception) {
        failures.add("case ${case.id}: Kotlin cannot decode the Rust PVCE bytes: ${e.message}")
    }
    return failures
}

/** Verifies one reject case cross-language: the Kotlin side rejects with
 * exactly the expected code (re-verified here), and the Rust side must have
 * recorded the same code. */
private fun verifyRejectCase(
    case: ExchangeCase,
    rustDir: File,
    ktDir: File?,
    limits: ProtocolLimits,
): List<String> {
    val code = exchangeRejectionCode(case, limits)
    if (code != case.expectedErrorCode) {
        return listOf("case ${case.id}: Kotlin rejection code \"$code\" != expected \"${case.expectedErrorCode}\"")
    }
    ktDir?.let { dir ->
        File(dir, "${case.id}.error.txt").writeText(code + "\n")
    }
    val rustCode = try {
        readErrorFile(rustDir, case.id)
    } catch (e: Exception) {
        return listOf("case ${case.id}: missing Rust rejection file: ${e.message}")
    }
    if (rustCode != case.expectedErrorCode) {
        return listOf(
            "case ${case.id}: rejection codes diverge: Kotlin ${case.expectedErrorCode}, Rust $rustCode " +
                "(want ${case.expectedErrorCode})",
        )
    }
    return emptyList()
}

/**
 * Runs the bidirectional exchange: Kotlin bytes vs the Rust golden bytes,
 * Rust bytes -> Kotlin typed decode -> re-encode byte-identically, rejection
 * codes compared, and the Kotlin-side encoder files emitted into [ktDir]
 * (null skips the emission).
 */
fun runExchange(cases: List<ExchangeCase>, rustDir: File, ktDir: File?): ExchangeReport {
    val knownIDs = cases.map { it.id }.toSet()
    for (entry in rustDir.listFiles()!!) {
        if (entry.isDirectory) {
            continue
        }
        var base = entry.name
        base = base.removeSuffix(".json.hex")
        base = base.removeSuffix(".pvce.hex")
        base = base.removeSuffix(".error.txt")
        if (base != entry.name && base !in knownIDs) {
            error("rust file ${entry.name} does not correspond to any case (case file drift?)")
        }
    }
    ktDir?.mkdirs()
    val limits = ProtocolLimits.default
    val failures = ArrayList<String>()
    var acceptPassed = 0
    var acceptCount = 0
    var rejectPassed = 0
    var rejectCount = 0
    for (case in cases) {
        if (case.expectedErrorCode.isNotEmpty()) {
            rejectCount++
            val before = failures.size
            failures.addAll(verifyRejectCase(case, rustDir, ktDir, limits))
            if (failures.size == before) {
                rejectPassed++
            }
            continue
        }
        acceptCount++
        val before = failures.size
        failures.addAll(verifyAcceptCase(case, rustDir, ktDir, limits))
        if (failures.size == before) {
            acceptPassed++
        }
    }
    return ExchangeReport(acceptPassed, acceptCount, rejectPassed, rejectCount, failures)
}
