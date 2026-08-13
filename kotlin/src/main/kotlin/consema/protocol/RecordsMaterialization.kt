// The materialization request and result protocol records (v1 and v2).
//
// Data authority (language-neutral sources first):
//   - consema-rs/consema-protocol/src/materialization.rs:15-179 (the request
//     records: the fixed request schema with the profile reference, style
//     reference, encoding record, newline/mapping/representability
//     spellings, and the limits record; v1 encodes the encoding as a String
//     and rejects Windows code pages).
//   - materialization.rs:190-279 (MaterializationReportMessage), 327-535
//     (MaterializationProvenanceMapMessage), 537-600 (the failure message),
//     832-999 (MaterializationResultMessageV2 with the source-v2 outcome).
//   - consema-rs/consema-protocol/src/query.rs:441-540 (the ValuePath and
//     AssociationLocation wire forms).
//   - conformance/vectors/semantic-model-v6.json pins the round-trips and
//     the exact-version dispatch rejection.
//
// Kotlin-idiomatic design: immutable protocol-level fact classes; the v2
// records carry the protocol SourceEncoding (which supports Windows code
// pages) instead of the document package's closed v1 set.

package consema.protocol

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.PvArray
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.document.MaterializationLimits
import consema.document.MaterializationStyleId
import consema.document.ProfileId

// ---------------------------------------------------------------------------
// core.materialization-request@1 / @2 (materialization.rs:15-108).
// ---------------------------------------------------------------------------

/** The protocol-level materialization request facts: the profile and style
 * references, the output encoding (the protocol SourceEncoding supports
 * Windows code pages), and the exact policies and limits. */
data class MaterializationRequestFacts internal constructor(
    /** Exact target Profile. */
    val targetProfile: ProfileId,
    /** Exact versioned target style. */
    val style: MaterializationStyleId,
    /** Selected output encoding. */
    val encoding: SourceEncoding,
    /** Selected newline behavior. */
    val newline: String,
    /** Ordered-mapping behavior. */
    val mappingPolicy: String,
    /** Representability behavior. */
    val representability: String,
    /** Resource limits. */
    val limits: MaterializationLimits,
) {
    companion object {
        /** Builds the request facts from the common document request. */
        fun fromDocument(request: consema.document.MaterializationRequest): MaterializationRequestFacts =
            MaterializationRequestFacts(
                targetProfile = request.targetProfile,
                style = request.style,
                encoding = sourceEncodingOf(request.encoding),
                newline = when (request.newline) {
                    consema.document.NewlinePolicy.None -> "None"
                    consema.document.NewlinePolicy.Lf -> "Lf"
                    consema.document.NewlinePolicy.CrLf -> "CrLf"
                },
                mappingPolicy = when (request.mappingPolicy) {
                    consema.document.MappingPolicy.RequireObject -> "RequireObject"
                    consema.document.MappingPolicy.UniqueStringEntriesToObject ->
                        "UniqueStringEntriesToObject"
                },
                representability = "ExactOnly",
                limits = request.limits,
            )

        /** Maps one document encoding to the protocol wire encoding. */
        fun sourceEncodingOf(encoding: consema.document.SourceEncoding): SourceEncoding =
            when (encoding) {
                consema.document.SourceEncoding.Binary -> SourceEncoding("Binary", null)
                consema.document.SourceEncoding.Utf8 -> SourceEncoding("Utf8", null)
                consema.document.SourceEncoding.Utf16Le -> SourceEncoding("Utf16Le", null)
                consema.document.SourceEncoding.Utf16Be -> SourceEncoding("Utf16Be", null)
                consema.document.SourceEncoding.Latin1 -> SourceEncoding("Latin1", null)
            }
    }

    /** Builds the common document request carrying these facts. */
    fun toDocumentRequest(): consema.document.MaterializationRequest =
        consema.document.MaterializationRequest.new(targetProfile, style)
            .withEncoding(documentEncoding())
            .withNewline(newlinePolicy())
            .withMappingPolicy(mappingPolicy())
            .withLimits(limits)

    private fun documentEncoding(): consema.document.SourceEncoding = when (encoding.kind) {
        "Binary" -> consema.document.SourceEncoding.Binary
        "Utf8" -> consema.document.SourceEncoding.Utf8
        "Utf16Le" -> consema.document.SourceEncoding.Utf16Le
        "Utf16Be" -> consema.document.SourceEncoding.Utf16Be
        "Latin1" -> consema.document.SourceEncoding.Latin1
        else -> throw invalid("$.encoding", "document request cannot carry a Windows code page")
    }

    private fun newlinePolicy(): consema.document.NewlinePolicy = when (newline) {
        "None" -> consema.document.NewlinePolicy.None
        "Lf" -> consema.document.NewlinePolicy.Lf
        "CrLf" -> consema.document.NewlinePolicy.CrLf
        else -> throw invalid("$.newline", "unknown newline policy")
    }

    private fun mappingPolicy(): consema.document.MappingPolicy = when (mappingPolicy) {
        "RequireObject" -> consema.document.MappingPolicy.RequireObject
        "UniqueStringEntriesToObject" -> consema.document.MappingPolicy.UniqueStringEntriesToObject
        else -> throw invalid("$.mapping_policy", "unknown mapping policy")
    }
}

/** Transferable `core.materialization-request@1` (materialization.rs:15-67):
 * the fixed-field request schema with a String encoding that cannot carry a
 * Windows code page. */
class MaterializationRequestMessage private constructor(
    private val request: MaterializationRequestFacts,
) {
    companion object {
        /** Copies one validated common request. */
        fun fromRequest(request: consema.document.MaterializationRequest): MaterializationRequestMessage =
            MaterializationRequestMessage(MaterializationRequestFacts.fromDocument(request))

        /** Strictly decodes every request policy and bound. */
        fun fromValue(value: PortableValue): MaterializationRequestMessage {
            val fields = schemaFields(
                value,
                "core.materialization-request@1",
                listOf(
                    "schema", "target_profile", "style", "encoding", "newline",
                    "mapping_policy", "representability", "limits",
                ),
                "$",
            )
            val encodingName = stringOf(fields[3], "$.encoding")
            if (encodingName == "WindowsCodePage") {
                throw invalid("$.encoding", "core.materialization-request@1 does not support Windows code pages")
            }
            val encoding = parseEncodingV1(encodingName, "$.encoding")
            val request = decodeRequestFields(fields, encoding, "$")
            return MaterializationRequestMessage(request)
        }
    }

    /** Exact common request facts. */
    fun request(): MaterializationRequestFacts = request

    /** Encodes the fixed-field request schema. */
    fun toValue(): PortableValue =
        materializationRequestValue("core.materialization-request@1", request, PvString(request.encoding.kind))
}

/** Transferable `core.materialization-request@2` (materialization.rs:69-
 * 108): the exact v2 schema whose encoding member is the full
 * core.source-encoding@1 record. */
class MaterializationRequestMessageV2 private constructor(
    private val request: MaterializationRequestFacts,
) {
    companion object {
        /** Copies one validated common request. */
        fun fromRequest(request: consema.document.MaterializationRequest): MaterializationRequestMessageV2 =
            MaterializationRequestMessageV2(MaterializationRequestFacts.fromDocument(request))

        /** Wraps explicit protocol-level request facts (the source-v2
         * requests carry Windows code pages, which the document request
         * cannot express). */
        fun fromFacts(request: MaterializationRequestFacts): MaterializationRequestMessageV2 =
            MaterializationRequestMessageV2(request)

        /** Strictly decodes every v2 request policy and bound. */
        fun fromValue(value: PortableValue): MaterializationRequestMessageV2 {
            val fields = schemaFields(
                value,
                "core.materialization-request@2",
                listOf(
                    "schema", "target_profile", "style", "encoding", "newline",
                    "mapping_policy", "representability", "limits",
                ),
                "$",
            )
            val encoding = SourceEncoding.fromValue(fields[3], "$.encoding")
            val request = decodeRequestFields(fields, encoding, "$")
            return MaterializationRequestMessageV2(request)
        }
    }

    /** Exact common request facts. */
    fun request(): MaterializationRequestFacts = request

    /** Encodes the exact materialization-request v2 schema. */
    fun toValue(): PortableValue =
        materializationRequestValue("core.materialization-request@2", request, request.encoding.toValue())
}

/** Decodes the shared request fields after the encoding member. */
private fun decodeRequestFields(
    fields: List<PortableValue>,
    encoding: SourceEncoding,
    path: String,
): MaterializationRequestFacts {
    val targetProfile = parseProfile(fields[1], "$path.target_profile")
    val (styleId, styleVersion) = parseReference(fields[2], "$path.style")
    val newline = stringOf(fields[4], "$path.newline")
    val mappingPolicy = stringOf(fields[5], "$path.mapping_policy")
    if (stringOf(fields[6], "$path.representability") != "ExactOnly") {
        throw invalid("$path.representability", "requires ExactOnly")
    }
    return MaterializationRequestFacts(
        targetProfile = targetProfile,
        style = MaterializationStyleId(styleId, styleVersion),
        encoding = encoding,
        newline = newline,
        mappingPolicy = mappingPolicy,
        representability = "ExactOnly",
        limits = parseLimits(fields[7], "$path.limits"),
    )
}

/** Encodes the shared request schema (materialization.rs:110-137). */
private fun materializationRequestValue(
    schema: String,
    request: MaterializationRequestFacts,
    encoding: PortableValue,
): PortableValue =
    PvObject(
        listOf(
            consema.core.Entry("schema", PvString(schema)),
            consema.core.Entry("target_profile", profileValue(request.targetProfile)),
            consema.core.Entry("style", referenceValue(request.style.id, request.style.version)),
            consema.core.Entry("encoding", encoding),
            consema.core.Entry("newline", PvString(request.newline)),
            consema.core.Entry("mapping_policy", PvString(request.mappingPolicy)),
            consema.core.Entry("representability", PvString(request.representability)),
            consema.core.Entry("limits", limitsValue(request.limits)),
        ),
    )

internal fun parseEncodingV1(name: String, path: String): SourceEncoding {
    if (name == "WindowsCodePage") {
        throw invalid(path, "core source v1 does not support Windows code pages")
    }
    return when (name) {
        "Binary", "Utf8", "Utf16Le", "Utf16Be", "Latin1" -> SourceEncoding(name, null)
        else -> throw invalid(path, "unknown source encoding kind")
    }
}

internal fun profileValue(profile: ProfileId): PortableValue = referenceValue(profile.id, profile.version)

internal fun parseProfile(value: PortableValue, path: String): ProfileId {
    val fields = exactFields(value, listOf("id", "version"), path)
    val id = stringOf(fields[0], "$path.id")
    val version = unsigned32(fields[1], "$path.version")
    return ProfileId(id, version)
}

internal fun parseReference(value: PortableValue, path: String): Pair<String, Int> {
    val fields = exactFields(value, listOf("id", "version"), path)
    val id = stringOf(fields[0], "$path.id")
    val version = unsigned32(fields[1], "$path.version")
    // The Rust parse_reference builds a ContractId, whose constructor rejects
    // a zero version (contract.rs:22-25); the wire codec must reject the same
    // record instead of accepting it.
    if (version == 0) {
        throw invalid("$path.version", "version must be non-zero")
    }
    return id to version
}

internal fun limitsValue(limits: MaterializationLimits): PortableValue =
    PvObject(
        listOf(
            consema.core.Entry("max_input_nodes", integerValue(limits.maxInputNodes.toULong())),
            consema.core.Entry("max_output_bytes", integerValue(limits.maxOutputBytes.toULong())),
            consema.core.Entry("max_depth", integerValue(limits.maxDepth.toULong())),
            consema.core.Entry("max_report_entries", integerValue(limits.maxReportEntries.toULong())),
            consema.core.Entry(
                "max_provenance_entries",
                integerValue(limits.maxProvenanceEntries.toULong()),
            ),
        ),
    )

internal fun parseLimits(value: PortableValue, path: String): MaterializationLimits {
    val fields = exactFields(
        value,
        listOf(
            "max_input_nodes", "max_output_bytes", "max_depth",
            "max_report_entries", "max_provenance_entries",
        ),
        path,
    )
    return MaterializationLimits(
        maxInputNodes = unsigned64(fields[0], "$path.max_input_nodes").toInt(),
        maxOutputBytes = unsigned64(fields[1], "$path.max_output_bytes").toInt(),
        maxDepth = unsigned64(fields[2], "$path.max_depth").toInt(),
        maxReportEntries = unsigned64(fields[3], "$path.max_report_entries").toInt(),
        maxProvenanceEntries = unsigned64(fields[4], "$path.max_provenance_entries").toInt(),
    )
}

// ---------------------------------------------------------------------------
// core.materialization-report@1 (materialization.rs:190-279).
// ---------------------------------------------------------------------------

/** Ordered `core.materialization-report@1` diagnostics (materialization.rs:
 * 190-279). */
class MaterializationReportMessage private constructor(
    /** Ordered materialization events. */
    val events: List<Diagnostic>,
) {
    companion object {
        /** The empty report. */
        fun empty(): MaterializationReportMessage = MaterializationReportMessage(emptyList())

        /** Validates all events against one explicit semantic-model registry. */
        fun newWithRegistry(
            events: List<Diagnostic>,
            registry: ErrorCodeRegistry,
        ): MaterializationReportMessage {
            for (event in events) {
                Diagnostic.fromValue(event.toValue(), registry)
            }
            return MaterializationReportMessage(events)
        }

        /** Strictly decodes events under one explicit semantic-model registry. */
        fun fromValueWithRegistry(
            value: PortableValue,
            registry: ErrorCodeRegistry,
        ): MaterializationReportMessage {
            val fields = schemaFields(value, "core.materialization-report@1", listOf("schema", "events"), "$")
            val events = sequenceOf(fields[1], "$.events")
                .map { Diagnostic.fromValue(it, registry) }
            return newWithRegistry(events, registry)
        }
    }

    /** Encodes the fixed report schema. */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.materialization-report@1")),
                consema.core.Entry("events", PvArray(events.map { it.toValue() })),
            ),
        )
}

// ---------------------------------------------------------------------------
// core.materialization-provenance-map@1 (materialization.rs:281-535).
// ---------------------------------------------------------------------------

/** Relationship from portable input to target syntax. */
enum class MaterializationRelationMessage(val wireName: String) {
    /** Direct exact semantic representation. */
    Direct("Direct"),

    /** Deterministic target-native re-encoding. */
    Reencoded("Reencoded"),

    /** Generated target syntax. */
    Generated("Generated");

    companion object {
        fun fromName(name: String, path: String): MaterializationRelationMessage =
            when (name) {
                "Direct" -> Direct
                "Reencoded" -> Reencoded
                "Generated" -> Generated
                else -> throw invalid(path, "unknown materialization relation")
            }
    }
}

/** Portable input location in materialization provenance
 * (materialization.rs:281-289). */
sealed class MaterializationInputLocationMessage {
    /** Portable value path. */
    data class Value(val path: ValuePath) : MaterializationInputLocationMessage()

    /** Portable association location. */
    data class Association(val location: AssociationLocation) : MaterializationInputLocationMessage()

    /** Encodes the input location record (materialization.rs:1487-1498). */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("kind", PvString(if (this is Value) "Value" else "Association")),
                consema.core.Entry(
                    "value",
                    if (this is Value) {
                        pathValue(path)
                    } else {
                        associationValue((this as Association).location)
                    },
                ),
            ),
        )

    companion object {
        /** Strictly decodes one input location record. */
        fun fromValue(value: PortableValue, path: String): MaterializationInputLocationMessage {
            val fields = exactFields(value, listOf("kind", "value"), path)
            return when (stringOf(fields[0], "$path.kind")) {
                "Value" -> Value(parsePath(fields[1], "$path.value"))
                "Association" -> Association(parseAssociation(fields[1], "$path.value"))
                else -> throw invalid(path, "unknown input location kind")
            }
        }
    }
}

/** One transferable target origin with caller-stable identities
 * (materialization.rs:301-314). */
data class MaterializedOriginMessage(
    /** Caller-stable target source identity. */
    val targetSourceId: String,
    /** Caller-stable target node locator. */
    val targetNodeLocator: String,
    /** Inclusive target raw-byte start. */
    val startByte: ULong,
    /** Exclusive target raw-byte end. */
    val endByte: ULong,
    /** Input-to-output relation. */
    val relation: MaterializationRelationMessage,
)

/** One portable input location and all exact target origins
 * (materialization.rs:316-323). */
data class MaterializationProvenanceEntryMessage(
    /** Portable input location. */
    val input: MaterializationInputLocationMessage,
    /** Non-empty ordered target origins. */
    val outputs: List<MaterializedOriginMessage>,
)

/** Transferable `core.materialization-provenance-map@1`
 * (materialization.rs:325-535). */
class MaterializationProvenanceMapMessage private constructor(
    /** Ordered complete provenance entries. */
    val entries: List<MaterializationProvenanceEntryMessage>,
) {
    companion object {
        /** The empty map. */
        fun empty(): MaterializationProvenanceMapMessage =
            MaterializationProvenanceMapMessage(emptyList())

        /** Validates stable identities, non-empty outputs, range order, and
         * locator uniqueness (materialization.rs:332-373). */
        fun new(entries: List<MaterializationProvenanceEntryMessage>): MaterializationProvenanceMapMessage {
            var sourceId: String? = null
            val locatorRanges = HashMap<String, Pair<ULong, ULong>>()
            for ((entryIndex, entry) in entries.withIndex()) {
                if (entry.outputs.isEmpty()) {
                    throw invalid("$.entries[$entryIndex].outputs", "provenance entry requires at least one output")
                }
                for ((outputIndex, output) in entry.outputs.withIndex()) {
                    val path = "$.entries[$entryIndex].outputs[$outputIndex]"
                    if (output.targetSourceId.isEmpty() || output.targetSourceId.length > 1024 ||
                        output.targetNodeLocator.isEmpty() || output.targetNodeLocator.length > 4096 ||
                        output.startByte > output.endByte
                    ) {
                        throw invalid(path, "invalid target origin")
                    }
                    if (sourceId != null && sourceId != output.targetSourceId) {
                        throw invalid(path, "one provenance map must bind one target source")
                    }
                    sourceId = output.targetSourceId
                    val range = output.startByte to output.endByte
                    val previous = locatorRanges.put(output.targetNodeLocator, range)
                    if (previous != null && previous != range) {
                        throw invalid(path, "one target node locator cannot identify contradictory ranges")
                    }
                }
            }
            return MaterializationProvenanceMapMessage(entries)
        }

        /** Strictly decodes external identities and complete ordered
         * mappings (materialization.rs:506-534). */
        fun fromValue(value: PortableValue): MaterializationProvenanceMapMessage {
            val fields = schemaFields(
                value,
                "core.materialization-provenance-map@1",
                listOf("schema", "entries"),
                "$",
            )
            val entries = sequenceOf(fields[1], "$.entries").mapIndexed { entryIndex, entry ->
                val path = "$.entries[$entryIndex]"
                val entryFields = exactFields(entry, listOf("input", "outputs"), path)
                val outputs = sequenceOf(entryFields[1], "$path.outputs").mapIndexed { outputIndex, output ->
                    parseOutput(output, "$path.outputs[$outputIndex]")
                }
                MaterializationProvenanceEntryMessage(
                    input = MaterializationInputLocationMessage.fromValue(entryFields[0], "$path.input"),
                    outputs = outputs,
                )
            }
            return new(entries)
        }
    }

    /** Encodes the fixed provenance schema (materialization.rs:468-504). */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.materialization-provenance-map@1")),
                consema.core.Entry(
                    "entries",
                    PvArray(
                        entries.map { entry ->
                            PvObject(
                                listOf(
                                    consema.core.Entry("input", entry.input.toValue()),
                                    consema.core.Entry(
                                        "outputs",
                                        PvArray(
                                            entry.outputs.map { output ->
                                                PvObject(
                                                    listOf(
                                                        consema.core.Entry(
                                                            "target_source_id",
                                                            PvString(output.targetSourceId),
                                                        ),
                                                        consema.core.Entry(
                                                            "target_node_locator",
                                                            PvString(output.targetNodeLocator),
                                                        ),
                                                        consema.core.Entry(
                                                            "start_byte",
                                                            integerValue(output.startByte),
                                                        ),
                                                        consema.core.Entry(
                                                            "end_byte",
                                                            integerValue(output.endByte),
                                                        ),
                                                        consema.core.Entry(
                                                            "relation",
                                                            PvString(output.relation.wireName),
                                                        ),
                                                    ),
                                                )
                                            },
                                        ),
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            ),
        )
}

private fun parseOutput(value: PortableValue, path: String): MaterializedOriginMessage {
    val fields = exactFields(
        value,
        listOf(
            "target_source_id", "target_node_locator", "start_byte",
            "end_byte", "relation",
        ),
        path,
    )
    return MaterializedOriginMessage(
        targetSourceId = stringOf(fields[0], "$path.target_source_id"),
        targetNodeLocator = stringOf(fields[1], "$path.target_node_locator"),
        startByte = unsigned64(fields[2], "$path.start_byte"),
        endByte = unsigned64(fields[3], "$path.end_byte"),
        relation = MaterializationRelationMessage.fromName(
            stringOf(fields[4], "$path.relation"),
            path,
        ),
    )
}

/** Encodes one ValuePath (query.rs:441-464). */
internal fun pathValue(path: ValuePath): PortableValue =
    PvObject(
        listOf(
            consema.core.Entry(
                "segments",
                PvArray(
                    path.segments().map { segment ->
                        when (segment) {
                            is ValuePathSegment.ObjectValue -> PvObject(
                                listOf(
                                    consema.core.Entry("kind", PvString("ObjectValue")),
                                    consema.core.Entry("key", PvString(segment.name)),
                                ),
                            )
                            is ValuePathSegment.SequenceElement -> PvObject(
                                listOf(
                                    consema.core.Entry("kind", PvString("SequenceElement")),
                                    consema.core.Entry("index", integerValue(segment.index.toULong())),
                                ),
                            )
                            is ValuePathSegment.EntryKey -> PvObject(
                                listOf(
                                    consema.core.Entry("kind", PvString("EntryKey")),
                                    consema.core.Entry("index", integerValue(segment.ordinal.toULong())),
                                ),
                            )
                            is ValuePathSegment.EntryValue -> PvObject(
                                listOf(
                                    consema.core.Entry("kind", PvString("EntryValue")),
                                    consema.core.Entry("index", integerValue(segment.ordinal.toULong())),
                                ),
                            )
                        }
                    },
                ),
            ),
        ),
    )

/** Strictly decodes one ValuePath (query.rs:466-512). */
internal fun parsePath(value: PortableValue, path: String): ValuePath {
    val fields = exactFields(value, listOf("segments"), path)
    var result = ValuePath.root()
    for ((index, segment) in sequenceOf(fields[0], "$path.segments").withIndex()) {
        val segmentPath = "$path.segments[$index]"
        val entries = (segment as? PvObject)?.entries()
            ?: throw protocolError(
                ProtocolErrorKind.WRONG_TYPE,
                segmentPath,
                "expected path segment Object",
            )
        val kind = entries.firstOrNull()
            ?.takeIf { it.key == "kind" }
            ?.value as? PvString
            ?: throw invalid(segmentPath, "missing segment kind")
        val decoded = when (kind.value) {
            "ObjectValue" -> {
                val segmentFields = exactFields(segment, listOf("kind", "key"), segmentPath)
                ValuePathSegment.ObjectValue(stringOf(segmentFields[1], "$segmentPath.key"))
            }
            "SequenceElement", "EntryKey", "EntryValue" -> {
                val segmentFields = exactFields(segment, listOf("kind", "index"), segmentPath)
                val segmentIndex = unsigned64(segmentFields[1], "$segmentPath.index").toLong()
                when (kind.value) {
                    "SequenceElement" -> ValuePathSegment.SequenceElement(segmentIndex)
                    "EntryKey" -> ValuePathSegment.EntryKey(segmentIndex)
                    else -> ValuePathSegment.EntryValue(segmentIndex)
                }
            }
            else -> throw invalid(segmentPath, "unknown path segment")
        }
        result = result.child(decoded)
    }
    return result
}

/** Encodes one AssociationLocation (query.rs:514-523). */
private fun associationValue(location: AssociationLocation): PortableValue =
    PvObject(
        listOf(
            consema.core.Entry("container", pathValue(location.container)),
            consema.core.Entry("ordinal", integerValue(location.ordinal.toULong())),
            consema.core.Entry(
                "role",
                PvString(
                    when (location.role) {
                        AssociationRole.ObjectEntry -> "ObjectEntry"
                        AssociationRole.ObjectKey -> "ObjectKey"
                        AssociationRole.EntryMappingEntry -> "EntryMappingEntry"
                    },
                ),
            ),
        ),
    )

/** Strictly decodes one AssociationLocation (query.rs:525-540). */
private fun parseAssociation(value: PortableValue, path: String): AssociationLocation {
    val fields = exactFields(value, listOf("container", "ordinal", "role"), path)
    val role = when (stringOf(fields[2], "$path.role")) {
        "ObjectEntry" -> AssociationRole.ObjectEntry
        "ObjectKey" -> AssociationRole.ObjectKey
        "EntryMappingEntry" -> AssociationRole.EntryMappingEntry
        else -> throw invalid(path, "unknown association role")
    }
    return AssociationLocation(
        container = parsePath(fields[0], "$path.container"),
        ordinal = unsigned64(fields[1], "$path.ordinal").toLong(),
        role = role,
    )
}

// ---------------------------------------------------------------------------
// core.materialization-result@2 (materialization.rs:832-999).
// ---------------------------------------------------------------------------

/** Stable transferable materialization failure, without partial target
 * bytes (materialization.rs:537-600). */
sealed class MaterializationFailureMessage {
    /** Request fields contradict the target contract. */
    data class InvalidRequest(val detail: String) : MaterializationFailureMessage()

    /** Target profile is unavailable. */
    data object UnsupportedProfile : MaterializationFailureMessage()

    /** Style is unavailable for the target profile. */
    data object UnsupportedStyle : MaterializationFailureMessage()

    /** Encoding is unavailable for the target profile. */
    data object UnsupportedEncoding : MaterializationFailureMessage()

    /** Newline policy is unavailable for the selected style. */
    data object UnsupportedNewline : MaterializationFailureMessage()

    /** One complete input value cannot be represented. */
    data class Unrepresentable(val path: ValuePath, val valueKind: String) :
        MaterializationFailureMessage()

    /** A configured limit was reached. */
    data class ResourceLimit(val limit: String) : MaterializationFailureMessage()

    /** Generated bytes did not form a target document. */
    data object FormationFailed : MaterializationFailureMessage()

    /** The exact public error code registered by semantic-model v3. */
    val code: String
        get() = when (this) {
            is InvalidRequest -> "core.materialization.invalid-request@1"
            UnsupportedProfile -> "core.materialization.unsupported-profile@1"
            UnsupportedStyle -> "core.materialization.unsupported-style@1"
            UnsupportedEncoding -> "core.materialization.unsupported-encoding@1"
            UnsupportedNewline -> "core.materialization.unsupported-newline@1"
            is Unrepresentable -> "core.materialization.unrepresentable@1"
            is ResourceLimit -> "core.materialization.resource-limit@1"
            FormationFailed -> "core.materialization.formation-failed@1"
        }
}

/** Closed transferable materialization completion algebra
 * (materialization.rs:602-627). */
sealed class MaterializationOutcomeMessageV2 {
    /** Complete target snapshot and every required audit fact. */
    data class Complete(
        /** Caller-stable target source identity. */
        val targetSourceId: String,
        /** Verified immutable target source. */
        val snapshot: SourceSnapshotMessageV2,
        /** Whole-operation semantic fidelity. */
        val fidelity: String,
        /** Ordered materialization report. */
        val report: MaterializationReportMessage,
        /** Complete externally bound input-to-target provenance. */
        val provenance: MaterializationProvenanceMapMessage,
    ) : MaterializationOutcomeMessageV2()

    /** Failed attempt with no target bytes or partial provenance. */
    data class Failed(
        /** Stable failure detail. */
        val failure: MaterializationFailureMessage,
        /** Ordered events discovered before failure. */
        val report: MaterializationReportMessage,
        /** Stable input paths analyzed before failure. */
        val analyzedInputPaths: List<ValuePath>,
    ) : MaterializationOutcomeMessageV2()
}

/** Transferable `core.materialization-result@2` (materialization.rs:832-
 * 999). */
class MaterializationResultMessageV2 private constructor(
    /** Exact target Profile. */
    val targetProfile: ProfileId,
    /** Complete or explicitly failed outcome. */
    val outcome: MaterializationOutcomeMessageV2,
) {
    companion object {
        /** Validates a complete source-v2 result and every target binding. */
        fun complete(
            targetProfile: ProfileId,
            targetSourceId: String,
            snapshot: SourceSnapshotMessageV2,
            fidelity: String,
            report: MaterializationReportMessage,
            provenance: MaterializationProvenanceMapMessage,
        ): MaterializationResultMessageV2 =
            new(
                targetProfile,
                MaterializationOutcomeMessageV2.Complete(
                    targetSourceId,
                    snapshot,
                    fidelity,
                    report,
                    provenance,
                ),
            )

        /** Validates a failed result which cannot carry target bytes or
         * provenance. */
        fun failed(
            targetProfile: ProfileId,
            failure: MaterializationFailureMessage,
            report: MaterializationReportMessage,
            analyzedInputPaths: List<ValuePath>,
        ): MaterializationResultMessageV2 =
            new(
                targetProfile,
                MaterializationOutcomeMessageV2.Failed(failure, report, analyzedInputPaths),
            )

        /** Validates the outcome invariants (materialization.rs:878-904,
         * 1001-1040). */
        fun new(
            targetProfile: ProfileId,
            outcome: MaterializationOutcomeMessageV2,
        ): MaterializationResultMessageV2 {
            when (outcome) {
                is MaterializationOutcomeMessageV2.Complete -> {
                    validateSourceId(outcome.targetSourceId, "$.outcome.target_source_id")
                    validateReportSource(outcome.report, outcome.targetSourceId)
                    val snapshotLen = outcome.snapshot.snapshot().rawBytes().size.toLong()
                    for ((entryIndex, entry) in outcome.provenance.entries.withIndex()) {
                        for ((outputIndex, output) in entry.outputs.withIndex()) {
                            if (output.targetSourceId != outcome.targetSourceId ||
                                output.endByte > snapshotLen.toULong()
                            ) {
                                throw invalid(
                                    "$.outcome.provenance.entries[$entryIndex].outputs[$outputIndex]",
                                    "provenance target binding or range contradicts the snapshot",
                                )
                            }
                        }
                    }
                    if (outcome.fidelity == "Transformed" &&
                        outcome.report.events.none { it.code == "core.materialization.mapping-transformed@1" }
                    ) {
                        throw invalid(
                            "$.outcome.report",
                            "Transformed fidelity requires an explicit transformation event",
                        )
                    }
                }
                is MaterializationOutcomeMessageV2.Failed -> {
                    validateReportSource(outcome.report, null)
                }
            }
            return MaterializationResultMessageV2(targetProfile, outcome)
        }

        /** Strictly decodes reports under one explicit semantic-model
         * registry (materialization.rs:931-998). */
        fun fromValueWithRegistry(
            value: PortableValue,
            registry: ErrorCodeRegistry,
        ): MaterializationResultMessageV2 {
            val fields = schemaFields(
                value,
                "core.materialization-result@2",
                listOf("schema", "target_profile", "outcome"),
                "$",
            )
            val targetProfile = parseProfile(fields[1], "$.target_profile")
            val outcomeValue = fields[2] as? PvObject
                ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$.outcome", "expected Object")
            val kind = outcomeValue.entries().firstOrNull { it.key == "kind" }
                ?: throw invalid("$.outcome", "missing kind")
            val outcome = when (stringOf(kind.value, "$.outcome.kind")) {
                "Complete" -> {
                    val outcomeFields = exactFields(
                        fields[2],
                        listOf(
                            "kind", "target_source_id", "snapshot", "fidelity",
                            "report", "provenance",
                        ),
                        "$.outcome",
                    )
                    MaterializationOutcomeMessageV2.Complete(
                        targetSourceId = stringOf(outcomeFields[1], "$.outcome.target_source_id"),
                        snapshot = SourceSnapshotMessageV2.fromValue(outcomeFields[2], SourceLimits.default),
                        fidelity = parseFidelity(stringOf(outcomeFields[3], "$.outcome.fidelity")),
                        report = MaterializationReportMessage.fromValueWithRegistry(outcomeFields[4], registry),
                        provenance = MaterializationProvenanceMapMessage.fromValue(outcomeFields[5]),
                    )
                }
                "Failed" -> {
                    val outcomeFields = exactFields(
                        fields[2],
                        listOf("kind", "failure", "report", "analyzed_input_paths"),
                        "$.outcome",
                    )
                    val analyzedInputPaths = sequenceOf(outcomeFields[3], "$.outcome.analyzed_input_paths")
                        .mapIndexed { index, path ->
                            parsePath(path, "$.outcome.analyzed_input_paths[$index]")
                        }
                    MaterializationOutcomeMessageV2.Failed(
                        failure = parseFailure(outcomeFields[1], "$.outcome.failure", registry),
                        report = MaterializationReportMessage.fromValueWithRegistry(outcomeFields[2], registry),
                        analyzedInputPaths = analyzedInputPaths,
                    )
                }
                else -> throw invalid("$.outcome.kind", "unknown materialization outcome")
            }
            return new(targetProfile, outcome)
        }
    }

    /** Encodes the fixed, explicitly tagged result-v2 schema
     * (materialization.rs:918-929). */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.materialization-result@2")),
                consema.core.Entry("target_profile", profileValue(targetProfile)),
                consema.core.Entry("outcome", outcomeValueV2(outcome)),
            ),
        )
}

private fun outcomeValueV2(outcome: MaterializationOutcomeMessageV2): PortableValue = when (outcome) {
    is MaterializationOutcomeMessageV2.Complete -> PvObject(
        listOf(
            consema.core.Entry("kind", PvString("Complete")),
            consema.core.Entry("target_source_id", PvString(outcome.targetSourceId)),
            consema.core.Entry("snapshot", outcome.snapshot.toValue()),
            consema.core.Entry("fidelity", PvString(outcome.fidelity)),
            consema.core.Entry("report", outcome.report.toValue()),
            consema.core.Entry("provenance", outcome.provenance.toValue()),
        ),
    )
    is MaterializationOutcomeMessageV2.Failed -> PvObject(
        listOf(
            consema.core.Entry("kind", PvString("Failed")),
            consema.core.Entry("failure", failureValue(outcome.failure)),
            consema.core.Entry("report", outcome.report.toValue()),
            consema.core.Entry(
                "analyzed_input_paths",
                PvArray(outcome.analyzedInputPaths.map { pathValue(it) }),
            ),
        ),
    )
}

private fun failureValue(failure: MaterializationFailureMessage): PortableValue = when (failure) {
    is MaterializationFailureMessage.InvalidRequest -> PvObject(
        listOf(
            consema.core.Entry("kind", PvString("InvalidRequest")),
            consema.core.Entry("code", PvString(failure.code)),
            consema.core.Entry("detail", PvString(failure.detail)),
        ),
    )
    is MaterializationFailureMessage.Unrepresentable -> PvObject(
        listOf(
            consema.core.Entry("kind", PvString("Unrepresentable")),
            consema.core.Entry("code", PvString(failure.code)),
            consema.core.Entry("path", pathValue(failure.path)),
            consema.core.Entry("value_kind", PvString(failure.valueKind)),
        ),
    )
    is MaterializationFailureMessage.ResourceLimit -> PvObject(
        listOf(
            consema.core.Entry("kind", PvString("ResourceLimit")),
            consema.core.Entry("code", PvString(failure.code)),
            consema.core.Entry("limit", PvString(failure.limit)),
        ),
    )
    else -> PvObject(
        listOf(
            consema.core.Entry(
                "kind",
                PvString(
                    when (failure) {
                        MaterializationFailureMessage.UnsupportedProfile -> "UnsupportedProfile"
                        MaterializationFailureMessage.UnsupportedStyle -> "UnsupportedStyle"
                        MaterializationFailureMessage.UnsupportedEncoding -> "UnsupportedEncoding"
                        MaterializationFailureMessage.UnsupportedNewline -> "UnsupportedNewline"
                        else -> "FormationFailed"
                    },
                ),
            ),
            consema.core.Entry("code", PvString(failure.code)),
        ),
    )
}

private fun parseFailure(
    value: PortableValue,
    path: String,
    registry: ErrorCodeRegistry,
): MaterializationFailureMessage {
    val entries = (value as? PvObject)?.entries()
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, path, "expected Object")
    val kind = entries.firstOrNull { it.key == "kind" }
        ?: throw invalid(path, "missing kind")
    val kindName = stringOf(kind.value, "$path.kind")
    val failure = when (kindName) {
        "InvalidRequest" -> {
            val fields = exactFields(value, listOf("kind", "code", "detail"), path)
            val detail = stringOf(fields[2], "$path.detail")
            if (detail.isEmpty() || detail.length > 4096) {
                throw invalid(path, "invalid failure detail")
            }
            MaterializationFailureMessage.InvalidRequest(detail)
        }
        "UnsupportedProfile" -> {
            exactFields(value, listOf("kind", "code"), path)
            MaterializationFailureMessage.UnsupportedProfile
        }
        "UnsupportedStyle" -> {
            exactFields(value, listOf("kind", "code"), path)
            MaterializationFailureMessage.UnsupportedStyle
        }
        "UnsupportedEncoding" -> {
            exactFields(value, listOf("kind", "code"), path)
            MaterializationFailureMessage.UnsupportedEncoding
        }
        "UnsupportedNewline" -> {
            exactFields(value, listOf("kind", "code"), path)
            MaterializationFailureMessage.UnsupportedNewline
        }
        "Unrepresentable" -> {
            val fields = exactFields(value, listOf("kind", "code", "path", "value_kind"), path)
            MaterializationFailureMessage.Unrepresentable(
                path = parsePath(fields[2], "$path.path"),
                valueKind = stringOf(fields[3], "$path.value_kind"),
            )
        }
        "ResourceLimit" -> {
            val fields = exactFields(value, listOf("kind", "code", "limit"), path)
            val limit = stringOf(fields[2], "$path.limit")
            if (limit.isEmpty() || limit.length > 256 ||
                !limit.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            ) {
                throw invalid(path, "invalid resource limit ID")
            }
            MaterializationFailureMessage.ResourceLimit(limit)
        }
        "FormationFailed" -> {
            exactFields(value, listOf("kind", "code"), path)
            MaterializationFailureMessage.FormationFailed
        }
        else -> throw invalid(path, "unknown materialization failure")
    }
    val code = entries.firstOrNull { it.key == "code" }
        ?: throw invalid(path, "missing code")
    val codeText = stringOf(code.value, "$path.code")
    registry.validate(codeText)
    if (codeText != failure.code) {
        throw invalid("$path.code", "failure kind contradicts its registered code")
    }
    return failure
}

private fun parseFidelity(value: String): String =
    when (value) {
        "Exact", "Transformed" -> value
        else -> throw invalid("$.outcome.fidelity", "unknown materialization fidelity")
    }

private fun validateSourceId(sourceId: String, path: String) {
    if (sourceId.isEmpty() || sourceId.length > 1024) {
        throw invalid(path, "invalid source ID")
    }
}

private fun validateReportSource(report: MaterializationReportMessage, expected: String?) {
    for ((index, event) in report.events.withIndex()) {
        val sources = ArrayList<String>()
        event.primary?.let { sources.add(it.sourceId) }
        for (related in event.related) {
            sources.add(related.location.sourceId)
        }
        for (fix in event.fixes) {
            fix.location?.let { sources.add(it.sourceId) }
        }
        if (sources.any { expected != it }) {
            throw invalid(
                "$.outcome.report.events[$index].location.source_id",
                "report location contradicts the materialization outcome",
            )
        }
    }
}
