// Deterministic PortableValue materialization for TOML 1.0.
//
// Data authority:
//   - RFC 0004 §3-§4, §6-§8 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md): the common MaterializationRequest, the frozen
//     target profile toml.1.0@1 and style toml.canonical-document@1 (one
//     assignment per root object entry, nested objects as deterministic
//     inline tables, one final newline; UTF-8 with Lf or CrLf only), the
//     TOML representability closure (Boolean Integer BinaryFloat64 String
//     Date Time LocalDateTime OffsetDateTime Sequence Object; Integer must
//     fit signed 64-bit; canonical NaN payloads only; temporal fields must
//     satisfy TOML precision/offset constraints exactly), the completion
//     algebra (Complete | Failed with no partial bytes), and the
//     provenance directions.
//   - https://github.com/consema/consema-rs/blob/main/consema-toml/src/materialization.rs (materialize), :53-99
//     (materialize_complete and requested_contract), :101-176 (PreparedRoot
//     and the explicit UniqueStringEntriesToObject conversion with the
//     core.materialization.mapping-transformed@1 event), :178-186 (the
//     derived parse limits), :188-547 (TomlWriter), :549-605 (BoundedOutput),
//     :613-884 (ProvenanceBuilder), :866-884 (scalar_kind_matches).
//   - conformance/vectors/operations-v1.json cases operations.v1.materialize-
//     toml-* (lines 60-94) pin the canonical outputs, the Transformed
//     conversion event, and the rejection codes; toml-v1.json
//     toml.corpus.* pins reparse-and-project closure.
//   - consema-go/go/toml/materialization.go is a cross-reference only.
//
// Kotlin-idiomatic design: the family consumes the document-domain
// MaterializationResult algebra; failures are the typed
// MaterializationException of the document package carrying the frozen
// core.materialization.*@1 codes. The writer is a bounded byte
// accumulator; the provenance builder maps input locations to the
// reparsed document's native handles.

package consema.toml

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.Kind
import consema.core.PortableValue
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvOffsetDateTime
import consema.core.PvString
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.document.CompleteMaterialization
import consema.document.FailedMaterializationAttempt
import consema.document.MappingPolicy
import consema.document.MaterializationException
import consema.document.MaterializationFailureKind
import consema.document.MaterializationFidelity
import consema.document.MaterializationInputLocation
import consema.document.MaterializationLimits
import consema.document.MaterializationProvenanceEntry
import consema.document.MaterializationProvenanceMap
import consema.document.MaterializationRelation
import consema.document.MaterializationReport
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializedOrigin
import consema.document.NewlinePolicy
import consema.document.ParseLimits
import consema.document.SourceEncoding
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.Severity

/** The current frozen error-code registry used to construct transferable
 * report events (the v7 registry of error_registry.rs). */
private val CURRENT_REGISTRY: ErrorCodeRegistry =
    ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)

/**
 * Materializes one complete PortableValue into a new immutable TOML
 * document (materialization.rs). Failed attempts contain no Document
 * and no partial output bytes (RFC 0004 §7).
 */
fun materialize(
    value: PortableValue,
    request: MaterializationRequest,
): MaterializationResult<TomlDocument> {
    val attempt = MaterializationAttempt()
    return try {
        val complete = materializeComplete(value, request, attempt)
        MaterializationResult.Complete(complete)
    } catch (e: MaterializationException) {
        MaterializationResult.Failed(
            FailedMaterializationAttempt(
                failure = e,
                report = attempt.report,
                analyzedInputPaths = attempt.analyzed,
            ),
        )
    }
}

/** Renders one canonical TOML value fragment for structural editing
 * (materialization.rs): a complete scalar/array/inline-table value
 * with no surrounding key. */
internal fun canonicalFragment(
    value: PortableValue,
    limits: MaterializationLimits,
): ByteArray {
    val writer = TomlWriter(NewlinePolicy.Lf, limits)
    writer.value(value, ValuePath.root(), 0)
    return writer.finish()
}

private class MaterializationAttempt {
    var report: MaterializationReport = MaterializationReport.new(emptyList(), MaterializationLimits.default)
    val analyzed: MutableList<ValuePath> = ArrayList()
}

private fun materializeComplete(
    value: PortableValue,
    request: MaterializationRequest,
    attempt: MaterializationAttempt,
): CompleteMaterialization<TomlDocument> {
    requestedContract(request)
    val prepared = prepareRoot(value, request)
    attempt.report = prepared.report
    val writer = TomlWriter(request.newline, request.limits)
    writer.root(prepared.entries, prepared.isMapping)
    val bytes = writer.finish()

    val document = try {
        parse(bytes, TomlProfile.TOML_1_0_V1, parseLimits(request.limits))
    } catch (e: TomlFormationException) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }

    val provenanceBuilder = ProvenanceBuilder(document, request.limits)
    provenanceBuilder.collect(value, ValuePath.root(), document.root())
    val provenance = MaterializationProvenanceMap.new(
        provenanceBuilder.entries,
        document.snapshotIdentity,
        request.limits,
    )
    return CompleteMaterialization(
        document = document,
        fidelity = prepared.fidelity,
        report = attempt.report,
        provenance = provenance,
    )
}

/** Validates the frozen request contract (materialization.rs):
 * toml.1.0@1, toml.canonical-document@1, UTF-8, Lf|CrLf. */
private fun requestedContract(request: MaterializationRequest) {
    if (request.targetProfile.id != "toml.1.0" || request.targetProfile.version != 1) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_PROFILE)
    }
    if (request.style.id != "toml.canonical-document" || request.style.version != 1) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_STYLE)
    }
    if (request.encoding != SourceEncoding.Utf8) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
    }
    if (request.newline != NewlinePolicy.Lf && request.newline != NewlinePolicy.CrLf) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_NEWLINE)
    }
}

/** The prepared root: the entry list and the whole-operation fidelity
 * (materialization.rs). */
private class PreparedRoot(
    val entries: List<RootEntry>,
    val isMapping: Boolean,
    val fidelity: MaterializationFidelity,
    val report: MaterializationReport,
)

/** One root entry with its input path. */
internal class RootEntry(
    val key: String,
    val value: PortableValue,
    val path: ValuePath,
)

/** Prepares the root object or the explicitly authorized mapping
 * conversion (materialization.rs). */
private fun prepareRoot(
    value: PortableValue,
    request: MaterializationRequest,
): PreparedRoot {
    val entries: List<RootEntry>
    val isMapping: Boolean
    var report = MaterializationReport.new(emptyList(), request.limits)
    val path = ValuePath.root()
    if (value is consema.core.PvObject) {
        entries = value.entries().map { entry ->
            RootEntry(entry.key, entry.value, path.child(ValuePathSegment.ObjectValue(entry.key)))
        }
        isMapping = false
    } else if (value is consema.core.PvEntryMapping) {
        if (request.mappingPolicy != MappingPolicy.UniqueStringEntriesToObject) {
            throw MaterializationException(
                MaterializationFailureKind.UNREPRESENTABLE,
                path = path,
                valueKind = Kind.EntryMapping,
            )
        }
        if (value.size() > request.limits.maxInputNodes) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "input-nodes",
            )
        }
        val seen = HashSet<String>()
        val converted = ArrayList<RootEntry>()
        for ((index, entry) in value.entries().withIndex()) {
            val ordinal = index.toLong()
            val keyPath = path.child(ValuePathSegment.EntryKey(ordinal))
            val keyValue = entry.key
            if (keyValue !is PvString) {
                throw MaterializationException(
                    MaterializationFailureKind.UNREPRESENTABLE,
                    path = keyPath,
                    valueKind = keyValue.kind,
                )
            }
            if (!seen.add(keyValue.value)) {
                throw MaterializationException(
                    MaterializationFailureKind.UNREPRESENTABLE,
                    path = keyPath,
                    valueKind = Kind.String,
                )
            }
            converted.add(
                RootEntry(
                    keyValue.value,
                    entry.value,
                    path.child(ValuePathSegment.EntryValue(ordinal)),
                ),
            )
        }
        val event = Diagnostic.of(
            code = "core.materialization.mapping-transformed@1",
            category = DiagnosticCategory.Materialization,
            severity = Severity.Info,
            primary = null,
            related = emptyList(),
            arguments = mapOf(
                "from" to "EntryMapping",
                "policy" to "UniqueStringEntriesToObject",
                "to" to "Object",
            ),
            notes = emptyList(),
            fixes = emptyList(),
            occurrence = 0uL,
            registry = CURRENT_REGISTRY,
        )
        report = MaterializationReport.new(listOf(event), request.limits)
        entries = converted
        isMapping = true
    } else {
        throw MaterializationException(
            MaterializationFailureKind.UNREPRESENTABLE,
            path = path,
            valueKind = value.kind,
        )
    }
    val fidelity = if (isMapping) {
        MaterializationFidelity.Transformed
    } else {
        MaterializationFidelity.Exact
    }
    return PreparedRoot(entries, isMapping, fidelity, report)
}

/** The derived parse limits of the generated bytes (materialization.rs
 *). */
internal fun parseLimits(limits: MaterializationLimits): ParseLimits = ParseLimits(
    maxSourceBytes = limits.maxOutputBytes,
    maxNestingDepth = limits.maxDepth,
    maxTokenCount = limits.maxOutputBytes,
    maxNodeCount = (limits.maxInputNodes.toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    maxDiagnostics = limits.maxReportEntries,
)

/** The bounded canonical TOML writer (materialization.rs). */
internal class TomlWriter(
    private val newline: NewlinePolicy,
    private val limits: MaterializationLimits,
) {
    private var inputNodes = 0
    private val output = BoundedOutput(limits.maxOutputBytes)

    /** Writes the root object/mapping as one assignment per entry with the
     * selected final newline (materialization.rs). */
    fun root(entries: List<RootEntry>, isMapping: Boolean) {
        val path = ValuePath.root()
        analyze(path, 0)
        for (entry in entries) {
            writeKey(entry.key)
            output.pushBytes(" = ")
            value(entry.value, entry.path, 1)
            output.pushBytes(newline.bytes())
        }
        if (entries.isEmpty()) {
            output.pushBytes(newline.bytes())
        }
    }

    /** Writes one canonical value (materialization.rs). */
    fun value(value: PortableValue, path: ValuePath, depth: Int) {
        analyze(path, depth)
        when (value) {
            is PvBoolean -> output.pushBytes(if (value.value) "true" else "false")
            is PvInteger -> {
                // Integer must fit TOML's signed 64-bit range (RFC 0004 §6);
                // BigInteger.bitLength of the magnitude distinguishes
                // 2^63-1 from 2^63 exactly.
                if (value.value.abs().bitLength() > 63) {
                    unrepresentable(path, value.kind)
                }
                output.pushBytes(value.value.toString())
            }
            is PvBinaryFloat64 -> writeFloat(value.bits, path)
            is PvString -> writeString(value.value)
            is consema.core.PvDate -> {
                val year = try {
                    value.year.intValueExact()
                } catch (e: ArithmeticException) {
                    -1
                }
                val text = canonicalDateText(
                    year,
                    value.month,
                    value.day,
                ) ?: unrepresentable(path, value.kind)
                output.pushBytes(text)
            }
            is consema.core.PvTime -> writeTime(value, path)
            is consema.core.PvLocalDateTime -> {
                val date = value.date
                val time = value.time
                val year = try {
                    date.year.intValueExact()
                } catch (e: ArithmeticException) {
                    -1
                }
                val text = canonicalLocalDateTimeText(
                    year,
                    date.month,
                    date.day,
                    time.hour,
                    time.minute,
                    time.second,
                    exactNanoseconds(time.fractionalSecond) ?: unrepresentable(path, value.kind),
                ) ?: unrepresentable(path, value.kind)
                output.pushBytes(text)
            }
            is PvOffsetDateTime -> writeOffsetDateTime(value, path)
            is consema.core.PvArray -> writeSequence(value.items(), path, depth)
            is consema.core.PvObject -> writeInlineObject(value.entries(), path, depth)
            else -> unrepresentable(path, value.kind)
        }
    }

    private fun analyze(path: ValuePath, depth: Int) {
        if (depth > limits.maxDepth) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "input-depth",
            )
        }
        inputNodes += 1
        if (inputNodes > limits.maxInputNodes) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "input-nodes",
            )
        }
    }

    private fun writeKey(value: String) {
        writeString(value)
    }

    private fun writeString(value: String) {
        output.pushBytes(canonicalString(value))
    }

    private fun writeFloat(bits: Long, path: ValuePath) {
        val text = canonicalFloatText(bits) ?: unrepresentable(path, Kind.BinaryFloat64)
        output.pushBytes(text)
    }

    private fun writeTime(value: consema.core.PvTime, path: ValuePath) {
        val nanoseconds = exactNanoseconds(value.fractionalSecond)
            ?: unrepresentable(path, Kind.Time)
        output.pushBytes(canonicalTimeText(value.hour, value.minute, value.second, nanoseconds))
    }

    private fun writeOffsetDateTime(value: PvOffsetDateTime, path: ValuePath) {
        val date = value.local.date
        val time = value.local.time
        val year = try {
            date.year.intValueExact()
        } catch (e: ArithmeticException) {
            -1
        }
        val text = canonicalOffsetDateTimeText(
            year,
            date.month,
            date.day,
            time.hour,
            time.minute,
            time.second,
            exactNanoseconds(time.fractionalSecond) ?: unrepresentable(path, value.kind),
            value.offsetSeconds,
        ) ?: unrepresentable(path, value.kind)
        output.pushBytes(text)
    }

    private fun writeSequence(values: List<PortableValue>, path: ValuePath, depth: Int) {
        output.pushBytes("[")
        for ((index, item) in values.withIndex()) {
            if (index != 0) {
                output.pushBytes(", ")
            }
            value(
                item,
                path.child(ValuePathSegment.SequenceElement(index.toLong())),
                depth + 1,
            )
        }
        output.pushBytes("]")
    }

    private fun writeInlineObject(
        entries: List<consema.core.Entry>,
        path: ValuePath,
        depth: Int,
    ) {
        output.pushBytes("{")
        if (entries.isNotEmpty()) {
            output.pushBytes(" ")
        }
        for ((index, entry) in entries.withIndex()) {
            if (index != 0) {
                output.pushBytes(", ")
            }
            writeKey(entry.key)
            output.pushBytes(" = ")
            value(
                entry.value,
                path.child(ValuePathSegment.ObjectValue(entry.key)),
                depth + 1,
            )
        }
        if (entries.isNotEmpty()) {
            output.pushBytes(" ")
        }
        output.pushBytes("}")
    }

    private fun unrepresentable(path: ValuePath, kind: Kind): Nothing =
        throw MaterializationException(
            MaterializationFailureKind.UNREPRESENTABLE,
            path = path,
            valueKind = kind,
        )

    fun finish(): ByteArray = output.finish()
}

/** The bounded byte accumulator (materialization.rs). */
private class BoundedOutput(private val max: Int) {
    private val bytes = java.io.ByteArrayOutputStream()

    fun pushBytes(text: String) {
        pushBytes(text.toByteArray(Charsets.UTF_8))
    }

    fun pushBytes(encoded: ByteArray) {
        val newLength = bytes.size() + encoded.size
        if (newLength > max) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "output-bytes",
            )
        }
        bytes.write(encoded)
    }

    fun finish(): ByteArray = bytes.toByteArray()
}

/** Builds the input-to-output provenance against the reparsed document
 * (materialization.rs). */
internal class ProvenanceBuilder(
    private val document: TomlDocument,
    private val limits: MaterializationLimits,
) {
    private var units = 0
    val entries: MutableList<MaterializationProvenanceEntry> = ArrayList()

    fun collect(input: PortableValue, path: ValuePath, output: TomlItem) {
        val relation = if (input.kind == Kind.EntryMapping) {
            MaterializationRelation.Reencoded
        } else {
            MaterializationRelation.Direct
        }
        pushOrigin(
            MaterializationInputLocation.Value(path),
            origin(output.nodeRef, output.span, relation),
        )
        when (input) {
            is consema.core.PvArray -> {
                if (output.kind != TomlItemKind.Array) {
                    throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                }
                val elements = output.arrayElements()
                    ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                if (input.size() != elements.size) {
                    throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                }
                for ((index, element) in elements.withIndex()) {
                    val childPath = path.child(ValuePathSegment.SequenceElement(index.toLong()))
                    collect(input.at(index), childPath, element.item())
                    addOutput(
                        MaterializationInputLocation.Value(childPath),
                        origin(element.nodeRef, element.span, MaterializationRelation.Generated),
                    )
                }
            }
            is consema.core.PvObject -> collectObject(input.entries(), path, output)
            is consema.core.PvEntryMapping -> collectMapping(input.entries(), path, output)
            else -> {
                if (!scalarKindMatches(input.kind, output.kind)) {
                    throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                }
            }
        }
    }

    private fun collectObject(
        inputs: List<consema.core.Entry>,
        path: ValuePath,
        output: TomlItem,
    ) {
        val entries = output.tableEntries()
            ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        if (inputs.size != entries.size) {
            throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        }
        for ((index, pair) in inputs.zip(entries).withIndex()) {
            val input = pair.first
            val entry = pair.second
            if (input.key != entry.name()) {
                throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
            }
            val ordinal = index.toLong()
            pushOrigin(
                MaterializationInputLocation.Association(
                    AssociationLocation(path, ordinal, AssociationRole.ObjectEntry),
                ),
                origin(entry.nodeRef, entry.span, MaterializationRelation.Direct),
            )
            pushOrigin(
                MaterializationInputLocation.Association(
                    AssociationLocation(path, ordinal, AssociationRole.ObjectKey),
                ),
                origin(entry.keyNodeRef, entry.keySpan, MaterializationRelation.Direct),
            )
            collect(
                input.value,
                path.child(ValuePathSegment.ObjectValue(input.key)),
                entry.item(),
            )
        }
    }

    private fun collectMapping(
        inputs: List<consema.core.EntryMappingEntry>,
        path: ValuePath,
        output: TomlItem,
    ) {
        val entries = output.tableEntries()
            ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        if (inputs.size != entries.size) {
            throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        }
        for ((index, pair) in inputs.zip(entries).withIndex()) {
            val input = pair.first
            val entry = pair.second
            val keyValue = input.key
            if (keyValue !is PvString || keyValue.value != entry.name()) {
                throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
            }
            val ordinal = index.toLong()
            pushOrigin(
                MaterializationInputLocation.Association(
                    AssociationLocation(path, ordinal, AssociationRole.EntryMappingEntry),
                ),
                origin(entry.nodeRef, entry.span, MaterializationRelation.Reencoded),
            )
            pushOrigin(
                MaterializationInputLocation.Value(path.child(ValuePathSegment.EntryKey(ordinal))),
                origin(entry.keyNodeRef, entry.keySpan, MaterializationRelation.Reencoded),
            )
            collect(
                input.value,
                path.child(ValuePathSegment.EntryValue(ordinal)),
                entry.item(),
            )
        }
    }

    private fun origin(
        node: consema.document.NodeRef,
        span: consema.document.Span,
        relation: MaterializationRelation,
    ): MaterializedOrigin = MaterializedOrigin(document.snapshotIdentity, node, span, relation)

    private fun pushOrigin(
        input: MaterializationInputLocation,
        output: MaterializedOrigin,
    ) {
        units += 2
        if (units > limits.maxProvenanceEntries) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "provenance-entries",
            )
        }
        entries.add(MaterializationProvenanceEntry(input, listOf(output)))
    }

    private fun addOutput(
        input: MaterializationInputLocation,
        output: MaterializedOrigin,
    ) {
        units += 1
        if (units > limits.maxProvenanceEntries) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "provenance-entries",
            )
        }
        val entry = entries.firstOrNull { it.input == input }
            ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        entries[entries.indexOf(entry)] =
            MaterializationProvenanceEntry(input, entry.outputs + output)
    }
}

/** The scalar kind correspondence of the exact projection contract
 * (materialization.rs). */
internal fun scalarKindMatches(input: Kind, output: TomlItemKind): Boolean =
    when (input) {
        Kind.String -> output == TomlItemKind.String
        Kind.Integer -> output == TomlItemKind.Integer
        Kind.BinaryFloat64 -> output == TomlItemKind.Float
        Kind.Boolean -> output == TomlItemKind.Boolean
        Kind.Date -> output == TomlItemKind.LocalDate
        Kind.Time -> output == TomlItemKind.LocalTime
        Kind.LocalDateTime -> output == TomlItemKind.LocalDateTime
        Kind.OffsetDateTime -> output == TomlItemKind.OffsetDateTime
        else -> false
    }
