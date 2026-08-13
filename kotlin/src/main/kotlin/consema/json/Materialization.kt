// Deterministic PortableValue materialization for JSON-family profiles.
//
// Data authority:
//   - RFC 0004 §3-§8 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:56-218): the common MaterializationRequest v1,
//     ExactOnly representability, the completion algebra, and the
//     provenance direction (portable input locations to the new Document).
//   - RFC 0005 §9 (https://github.com/consema/consema/blob/main/docs/rfcs/0005-json-family-production-v1.md:195-218):
//     styles json5.canonical-compact@1 / json5.canonical-pretty@1; canonical
//     JSON5 deliberately emits the strict-JSON subset for ordinary core
//     values and emits Infinity/-Infinity/NaN/-NaN only for the four frozen
//     BinaryFloat64 bit patterns; canonical strings escape U+2028/U+2029;
//     all output reparses under the exact requested profile and reprojects
//     to the identical PortableValue before completion.
//   - conformance/vectors/json-family-v2.json (json5.materialize.*) pins the
//     golden output bytes and failure names; consema-rs/consema-json/src/
//     materialization.rs is the byte-arbitration authority (writer
//     materialization.rs:154-494, provenance materialization.rs:500-756).
//   - The Kotlin document package owns the completion algebra types
//     (MaterializationResult/CompleteMaterialization/...,
//     kotlin/.../document/Materialization.kt:286-371); the failure-name
//     spellings asserted by the vectors are mapped here.
//
// Kotlin-idiomatic design: a bounded output buffer wraps the JDK byte
// accumulation with explicit checked growth (no third-party dependency), and
// the writer is a single recursive class with exhaustive `when` over the
// closed PortableValue kind set.

package consema.json

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.PortableValue
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvString
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.document.CompleteMaterialization
import consema.document.FailedMaterializationAttempt
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
import consema.document.Span
import java.math.BigInteger

/**
 * Materializes one complete PortableValue into a new immutable JSON or JSONC
 * document (materialization.rs:17-32). A failure contains no Document, no
 * partial bytes, and no provenance that can be mistaken for a result
 * (RFC 0004 §3).
 */
fun materialize(
    value: PortableValue,
    request: MaterializationRequest,
): MaterializationResult<Document> {
    val analyzed = ArrayList<ValuePath>()
    return try {
        MaterializationResult.Complete(materializeComplete(value, request, analyzed))
    } catch (e: MaterializationException) {
        MaterializationResult.Failed(
            FailedMaterializationAttempt(e, MaterializationReport.new(emptyList(), request.limits), analyzed),
        )
    }
}

/** The canonical scalar/container fragment writer used by structural edits
 * (materialization.rs:34-52). */
internal fun canonicalFragment(
    value: PortableValue,
    profile: JsonProfile,
    limits: MaterializationLimits,
): ByteArray {
    val analyzed = ArrayList<ValuePath>()
    val writer = JsonWriter(
        if (profile.isJson5()) JsonStyle.Json5Compact else JsonStyle.Compact,
        NewlinePolicy.None,
        limits,
        analyzed,
    )
    writer.value(value, ValuePath.root(), 0)
    return writer.outputBytes()
}

private fun materializeComplete(
    value: PortableValue,
    request: MaterializationRequest,
    analyzed: ArrayList<ValuePath>,
): CompleteMaterialization<Document> {
    val profile = requestedProfile(request)
    val style = requestedStyle(request, profile)
    if (request.encoding !== SourceEncoding.Utf8) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
    }
    if (style.isPretty() && request.newline == NewlinePolicy.None) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_NEWLINE)
    }

    val writer = JsonWriter(style, request.newline, request.limits, analyzed)
    writer.value(value, ValuePath.root(), 0)
    if (request.newline != NewlinePolicy.None) {
        writer.outputPush(request.newline.bytes())
    }
    val bytes = writer.outputBytes()
    val document = try {
        parse(bytes, profile, parseLimits(request.limits))
    } catch (e: JsonFormationException) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    if (document.formationStatus() != consema.document.FormationStatus.Complete) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }

    val builder = ProvenanceBuilder(document, request.limits)
    builder.collect(value, ValuePath.root(), document.root())
    val provenance = MaterializationProvenanceMap.new(
        builder.entries,
        document.snapshotIdentity,
        request.limits,
    )
    return CompleteMaterialization(
        document = document,
        fidelity = MaterializationFidelity.Exact,
        report = MaterializationReport.new(emptyList(), request.limits),
        provenance = provenance,
    )
}

/** The frozen output style (materialization.rs:95-111). */
internal enum class JsonStyle {
    Compact,
    Pretty,
    Json5Compact,
    Json5Pretty,
    ;

    fun isPretty(): Boolean = this == Pretty || this == Json5Pretty

    fun isJson5(): Boolean = this == Json5Compact || this == Json5Pretty
}

/** Resolves the target profile (materialization.rs:113-125). */
private fun requestedProfile(request: MaterializationRequest): JsonProfile =
    when {
        request.targetProfile.id == "json.strict" && request.targetProfile.version == 1 ->
            JsonProfile.StrictV1

        request.targetProfile.id == "jsonc.bounded" && request.targetProfile.version == 1 ->
            JsonProfile.JsoncBoundedV1

        request.targetProfile.id == "json5.standard" && request.targetProfile.version == 1 ->
            JsonProfile.Json5StandardV1

        else -> throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_PROFILE)
    }

/** Resolves the style for the exact profile (materialization.rs:127-142). */
private fun requestedStyle(request: MaterializationRequest, profile: JsonProfile): JsonStyle =
    when {
        (profile == JsonProfile.StrictV1 || profile == JsonProfile.JsoncBoundedV1) &&
            request.style.id == "json.canonical-compact" && request.style.version == 1 ->
            JsonStyle.Compact

        (profile == JsonProfile.StrictV1 || profile == JsonProfile.JsoncBoundedV1) &&
            request.style.id == "json.canonical-pretty" && request.style.version == 1 ->
            JsonStyle.Pretty

        profile == JsonProfile.Json5StandardV1 &&
            request.style.id == "json5.canonical-compact" && request.style.version == 1 ->
            JsonStyle.Json5Compact

        profile == JsonProfile.Json5StandardV1 &&
            request.style.id == "json5.canonical-pretty" && request.style.version == 1 ->
            JsonStyle.Json5Pretty

        else -> throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_STYLE)
    }

/** Derives the closure parse limits from the materialization limits
 * (materialization.rs:144-152). */
private fun parseLimits(limits: MaterializationLimits): ParseLimits =
    ParseLimits(
        maxSourceBytes = limits.maxOutputBytes,
        maxNestingDepth = limits.maxDepth,
        maxTokenCount = limits.maxOutputBytes,
        maxNodeCount = (limits.maxInputNodes.toLong() * 3)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        maxDiagnostics = limits.maxReportEntries,
    )

private class JsonWriter(
    private val style: JsonStyle,
    private val newline: NewlinePolicy,
    private val limits: MaterializationLimits,
    private val analyzed: ArrayList<ValuePath>,
) {
    private var inputNodes = 0
    private val output = BoundedOutput(limits.maxOutputBytes)

    fun outputPush(bytes: ByteArray) = output.pushBytes(bytes)

    fun outputBytes(): ByteArray = output.finish()

    fun value(value: PortableValue, path: ValuePath, depth: Int) {
        analyze(path, depth)
        when (value) {
            is PvNull -> output.pushBytes("null".toByteArray(Charsets.US_ASCII))
            is PvBoolean -> output.pushBytes(
                (if (value.value) "true" else "false").toByteArray(Charsets.US_ASCII),
            )
            is PvInteger -> writeInteger(value.value)
            is PvDecimal -> writeDecimal(value)
            is PvBinaryFloat64 -> {
                if (!style.isJson5()) {
                    throw unrepresentable(path, value.kind.name)
                }
                writeBinaryFloat64(value.bits, path)
            }
            is PvString -> writeString(value.value)
            is consema.core.PvArray -> writeSequence(value.items(), path, depth)
            is consema.core.PvObject -> writeObject(value.entries(), path, depth)
            is consema.core.PvEntryMapping -> writeEntryMapping(value.entries(), path, depth)
            else -> throw unrepresentable(path, value.kind.name)
        }
    }

    private fun writeInteger(value: BigInteger) {
        output.pushBytes(value.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun writeDecimal(value: PvDecimal) {
        // The canonical exact decimal spelling is coefficient e exponent
        // (materialization.rs:257-268).
        output.pushBytes(
            "${value.coefficient}e${value.exponent}".toByteArray(Charsets.US_ASCII),
        )
    }

    private fun writeString(value: String) {
        output.pushByte(0x22)
        for (character in value) {
            val code = character.code
            when {
                code == 0x22 -> output.pushBytes("\\\"".toByteArray(Charsets.US_ASCII))
                code == 0x5c -> output.pushBytes("\\\\".toByteArray(Charsets.US_ASCII))
                code == 0x08 -> output.pushBytes("\\b".toByteArray(Charsets.US_ASCII))
                code == 0x0c -> output.pushBytes("\\f".toByteArray(Charsets.US_ASCII))
                code == 0x0a -> output.pushBytes("\\n".toByteArray(Charsets.US_ASCII))
                code == 0x0d -> output.pushBytes("\\r".toByteArray(Charsets.US_ASCII))
                code == 0x09 -> output.pushBytes("\\t".toByteArray(Charsets.US_ASCII))
                code in 0x00..0x1f -> output.pushBytes(
                    "\\u%04x".format(code).toByteArray(Charsets.US_ASCII),
                )
                code == 0x2028 || code == 0x2029 -> {
                    // Canonical JSON5 escapes the line separators; the
                    // strict/JSONC styles emit them raw (materialization.rs:
                    // 285-288).
                    if (style.isJson5()) {
                        output.pushBytes(
                            "\\u%04x".format(code).toByteArray(Charsets.US_ASCII),
                        )
                    } else {
                        output.pushBytes(character.toString().toByteArray(Charsets.UTF_8))
                    }
                }
                else -> output.pushBytes(character.toString().toByteArray(Charsets.UTF_8))
            }
        }
        output.pushByte(0x22)
    }

    private fun writeBinaryFloat64(bits: Long, path: ValuePath) {
        // Only the four frozen JSON5 non-finite spellings are representable
        // (materialization.rs:299-317; RFC 0005 §6, §9).
        val spelling: String = when (bits) {
            java.lang.Double.doubleToRawLongBits(Double.POSITIVE_INFINITY) -> "Infinity"
            java.lang.Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY) -> "-Infinity"
            java.lang.Double.doubleToRawLongBits(Double.NaN) -> "NaN"
            java.lang.Double.doubleToRawLongBits(-Double.NaN) -> "-NaN"
            else -> throw unrepresentable(path, "BinaryFloat64")
        }
        output.pushBytes(spelling.toByteArray(Charsets.US_ASCII))
    }

    private fun writeSequence(values: List<PortableValue>, path: ValuePath, depth: Int) {
        output.pushByte(0x5b)
        if (values.isNotEmpty() && style.isPretty()) {
            layoutNewline(depth + 1)
        }
        for ((index, value) in values.withIndex()) {
            if (index != 0) {
                output.pushByte(0x2c)
                if (style.isPretty()) {
                    layoutNewline(depth + 1)
                }
            }
            this.value(
                value,
                path.child(ValuePathSegment.SequenceElement(index.toLong())),
                depth + 1,
            )
        }
        if (values.isNotEmpty() && style.isPretty()) {
            layoutNewline(depth)
        }
        output.pushByte(0x5d)
    }

    private fun writeObject(entries: List<consema.core.Entry>, path: ValuePath, depth: Int) {
        output.pushByte(0x7b)
        if (entries.isNotEmpty() && style.isPretty()) {
            layoutNewline(depth + 1)
        }
        for ((index, entry) in entries.withIndex()) {
            memberSeparator(index, depth)
            writeString(entry.key)
            output.pushByte(0x3a)
            if (style.isPretty()) {
                output.pushByte(0x20)
            }
            this.value(
                entry.value,
                path.child(ValuePathSegment.ObjectValue(entry.key)),
                depth + 1,
            )
        }
        if (entries.isNotEmpty() && style.isPretty()) {
            layoutNewline(depth)
        }
        output.pushByte(0x7d)
    }

    private fun writeEntryMapping(
        entries: List<consema.core.EntryMappingEntry>,
        path: ValuePath,
        depth: Int,
    ) {
        output.pushByte(0x7b)
        if (entries.isNotEmpty() && style.isPretty()) {
            layoutNewline(depth + 1)
        }
        for ((index, entry) in entries.withIndex()) {
            memberSeparator(index, depth)
            val ordinal = index.toLong()
            val keyPath = path.child(ValuePathSegment.EntryKey(ordinal))
            analyze(keyPath, depth + 1)
            val key = entry.key as? PvString
                ?: throw unrepresentable(keyPath, entry.key.kind.name)
            writeString(key.value)
            output.pushByte(0x3a)
            if (style.isPretty()) {
                output.pushByte(0x20)
            }
            this.value(
                entry.value,
                path.child(ValuePathSegment.EntryValue(ordinal)),
                depth + 1,
            )
        }
        if (entries.isNotEmpty() && style.isPretty()) {
            layoutNewline(depth)
        }
        output.pushByte(0x7d)
    }

    private fun analyze(path: ValuePath, depth: Int) {
        if (depth > limits.maxDepth) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "input-depth",
            )
        }
        inputNodes = inputNodes.inc()
        if (inputNodes > limits.maxInputNodes) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "input-nodes",
            )
        }
        analyzed.add(path)
    }

    private fun memberSeparator(index: Int, depth: Int) {
        if (index != 0) {
            output.pushByte(0x2c)
            if (style.isPretty()) {
                layoutNewline(depth + 1)
            }
        }
    }

    private fun layoutNewline(depth: Int) {
        output.pushBytes(newline.bytes())
        repeat(depth) {
            output.pushBytes("  ".toByteArray(Charsets.US_ASCII))
        }
    }

    private fun unrepresentable(path: ValuePath, kind: String): MaterializationException =
        MaterializationException(
            MaterializationFailureKind.UNREPRESENTABLE,
            "materialization: $kind is not representable at $path",
            path = path,
            valueKind = consema.core.Kind.entries.firstOrNull { it.name == kind },
        )
}

/** Bounded byte accumulation (materialization.rs:456-498). */
private class BoundedOutput(private val max: Int) {
    private val bytes = java.io.ByteArrayOutputStream()

    fun pushByte(byte: Int) {
        checkGrowth(1)
        bytes.write(byte)
    }

    fun pushBytes(chunk: ByteArray) {
        checkGrowth(chunk.size)
        bytes.write(chunk, 0, chunk.size)
    }

    private fun checkGrowth(count: Int) {
        val newLength = bytes.size() + count
        if (newLength > max) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "output-bytes",
            )
        }
    }

    fun finish(): ByteArray = bytes.toByteArray()
}

/** Input-to-output provenance collection (materialization.rs:500-756). */
private class ProvenanceBuilder(
    private val document: Document,
    private val limits: MaterializationLimits,
) {
    val entries = ArrayList<MaterializationProvenanceEntry>()
    private var units = 0

    fun collect(input: PortableValue, path: ValuePath, output: JsonValue) {
        val expectedKind: JsonValueKind = when (input) {
            is PvNull -> JsonValueKind.Null
            is PvBoolean -> JsonValueKind.Boolean
            is PvInteger -> JsonValueKind.Integer
            is PvDecimal -> JsonValueKind.Decimal
            is PvBinaryFloat64 -> JsonValueKind.BinaryFloat64
            is PvString -> JsonValueKind.String
            is consema.core.PvArray -> JsonValueKind.Array
            is consema.core.PvObject, is consema.core.PvEntryMapping -> JsonValueKind.Object
            else -> throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        }
        val outputKind = when (val kind = output.kind()) {
            is SemanticAvailability.Available -> kind.value
            is SemanticAvailability.Unavailable -> {
                throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
            }
        }
        if (outputKind != expectedKind) {
            throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        }
        if (input is PvBinaryFloat64) {
            val outputBits = when (val bits = output.asBinaryFloat64()) {
                is SemanticAvailability.Available -> bits.value
                is SemanticAvailability.Unavailable -> {
                    throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                }
            }
            if (outputBits != input.bits) {
                throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
            }
        }
        pushOrigin(
            MaterializationInputLocation.Value(path),
            origin(output.nodeRef(), output.span(), MaterializationRelation.Direct),
        )
        when (input) {
            is consema.core.PvArray -> {
                val values = input.items()
                val elements = availableElements(output)
                if (values.size != elements.size) {
                    throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                }
                for ((index, element) in elements.withIndex()) {
                    val childPath =
                        path.child(ValuePathSegment.SequenceElement(index.toLong()))
                    collect(values[index], childPath, element.value())
                    addOutput(
                        MaterializationInputLocation.Value(childPath),
                        origin(
                            element.nodeRef(),
                            element.span(),
                            MaterializationRelation.Generated,
                        ),
                    )
                }
            }
            is consema.core.PvObject -> {
                val entriesList = input.entries()
                val members = availableMembers(output)
                if (entriesList.size != members.size) {
                    throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                }
                for ((index, member) in members.withIndex()) {
                    val entry = entriesList[index]
                    val memberName = when (val name = member.name()) {
                        is SemanticAvailability.Available -> name.value
                        is SemanticAvailability.Unavailable -> {
                            throw MaterializationException(
                                MaterializationFailureKind.FORMATION_FAILED,
                            )
                        }
                    }
                    if (memberName != entry.key) {
                        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                    }
                    val ordinal = index.toLong()
                    pushOrigin(
                        MaterializationInputLocation.Association(
                            AssociationLocation(path, ordinal, AssociationRole.ObjectEntry),
                        ),
                        origin(member.nodeRef(), member.span(), MaterializationRelation.Direct),
                    )
                    pushOrigin(
                        MaterializationInputLocation.Association(
                            AssociationLocation(path, ordinal, AssociationRole.ObjectKey),
                        ),
                        origin(
                            member.keyNodeRef(),
                            document.span(document.memberEntity(member.index).key),
                            MaterializationRelation.Direct,
                        ),
                    )
                    collect(
                        entry.value,
                        path.child(ValuePathSegment.ObjectValue(entry.key)),
                        member.value(),
                    )
                }
            }
            is consema.core.PvEntryMapping -> {
                val entriesList = input.entries()
                val members = availableMembers(output)
                if (entriesList.size != members.size) {
                    throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                }
                for ((index, member) in members.withIndex()) {
                    val entry = entriesList[index]
                    val ordinal = index.toLong()
                    val key = entry.key as? PvString
                        ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                    val memberName = when (val name = member.name()) {
                        is SemanticAvailability.Available -> name.value
                        is SemanticAvailability.Unavailable -> {
                            throw MaterializationException(
                                MaterializationFailureKind.FORMATION_FAILED,
                            )
                        }
                    }
                    if (memberName != key.value) {
                        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                    }
                    pushOrigin(
                        MaterializationInputLocation.Association(
                            AssociationLocation(path, ordinal, AssociationRole.EntryMappingEntry),
                        ),
                        origin(member.nodeRef(), member.span(), MaterializationRelation.Direct),
                    )
                    pushOrigin(
                        MaterializationInputLocation.Value(
                            path.child(ValuePathSegment.EntryKey(ordinal)),
                        ),
                        origin(
                            member.keyNodeRef(),
                            document.span(document.memberEntity(member.index).key),
                            MaterializationRelation.Reencoded,
                        ),
                    )
                    collect(
                        entry.value,
                        path.child(ValuePathSegment.EntryValue(ordinal)),
                        member.value(),
                    )
                }
            }
            else -> {}
        }
    }

    private fun origin(node: consema.document.NodeRef, span: Span, relation: MaterializationRelation) =
        MaterializedOrigin(document.snapshotIdentity, node, span, relation)

    private fun pushOrigin(input: MaterializationInputLocation, output: MaterializedOrigin) {
        units = checkedProvenanceUnits(units, 2)
        if (units > limits.maxProvenanceEntries) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "provenance-entries",
            )
        }
        entries.add(MaterializationProvenanceEntry(input, listOf(output)))
    }

    private fun addOutput(input: MaterializationInputLocation, output: MaterializedOrigin) {
        units = checkedProvenanceUnits(units, 1)
        if (units > limits.maxProvenanceEntries) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "provenance-entries",
            )
        }
        val entry = entries.firstOrNull { it.input == input }
            ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        val outputs = entry.outputs.toMutableList()
        outputs.add(output)
        entries[entries.indexOf(entry)] =
            MaterializationProvenanceEntry(input, outputs)
    }

    /** Checked provenance-unit arithmetic (materialization.rs:703-708). */
    private fun checkedProvenanceUnits(left: Int, right: Int): Int {
        if (left > Int.MAX_VALUE - right) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "provenance-entries",
            )
        }
        return left + right
    }

    private fun availableElements(output: JsonValue): List<JsonArrayElement> =
        when (val elements = output.arrayElements()) {
            is SemanticAvailability.Available -> elements.value
                ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)

            is SemanticAvailability.Unavailable ->
                throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        }

    private fun availableMembers(output: JsonValue): List<JsonObjectMember> =
        when (val members = output.objectMembers()) {
            is SemanticAvailability.Available -> members.value
                ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)

            is SemanticAvailability.Unavailable ->
                throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        }
}

/** The frozen failure-name spellings asserted by the shared vectors
 * (json_family_v2.rs:887-898, materialization.rs:327-351). */
internal fun materializationFailureName(kind: MaterializationFailureKind): String =
    when (kind) {
        MaterializationFailureKind.INVALID_REQUEST -> "InvalidRequest"
        MaterializationFailureKind.UNSUPPORTED_PROFILE -> "UnsupportedProfile"
        MaterializationFailureKind.UNSUPPORTED_STYLE -> "UnsupportedStyle"
        MaterializationFailureKind.UNSUPPORTED_ENCODING -> "UnsupportedEncoding"
        MaterializationFailureKind.UNSUPPORTED_NEWLINE -> "UnsupportedNewline"
        MaterializationFailureKind.UNREPRESENTABLE -> "Unrepresentable"
        MaterializationFailureKind.RESOURCE_LIMIT -> "ResourceLimit"
        MaterializationFailureKind.FORMATION_FAILED -> "FormationFailed"
    }
