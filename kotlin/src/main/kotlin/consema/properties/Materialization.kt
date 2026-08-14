// Deterministic PortableValue materialization for exact Java Properties
// profiles.
//
// Data authority:
//   - RFC 0004 §3-§8 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md): the common MaterializationRequest v1,
//     ExactOnly representability, the completion algebra, and the
//     provenance direction (portable input locations to the new Document).
//   - RFC 0010 §12 (https://github.com/consema/consema/blob/main/docs/rfcs/0010-java-properties-profiles-v1.md):
//     the canonical styles java-properties.reader-canonical@1 and
//     java-properties.latin1-canonical@1; both emit `key=value` in input
//     order with an explicitly selected newline and deterministic escaping
//     (backslash, control characters, key spaces, leading value spaces,
//     `#`, `!`, `=`, `:`; uppercase four-digit Unicode escapes per UTF-16
//     code unit); Latin-1 canonical output uses named escapes for tab/LF/CR/
//     form feed and `\uXXXX` for other units below U+0020 or above U+007E,
//     so a supplementary scalar becomes its surrogate-pair escapes; no BOM
//     is generated for Latin-1; every result reparses under the exact target
//     profile and reprojects under the request's policy (closure).
//   - conformance/vectors/java-properties-v1.json pins the golden output
//     bytes and failure names (materialization.canonical-styles-encodings-
//     and-closure, lines 90-99; materialization.atomic-failures-and-limits,
//     lines 100-104).
//   - https://github.com/consema/consema-rs/blob/main/consema-properties/src/materialization.rs is the byte-
//     arbitration authority (writer materialization.rs, closure
//     materialization.rs, provenance materialization.rs,
//     encoding materialization.rs, parse limits materialization.rs
//). consema-go/go/properties/materialization.go is a cross-reference only.
//   - The Kotlin document package owns the completion algebra types
//     (MaterializationResult/CompleteMaterialization/...,
//     kotlin/src/main/kotlin/consema/document/Materialization.kt). Windows code pages
//     are not representable in the document-layer closed v1 SourceEncoding,
//     so this package exposes the code-page materialization as an explicit
//     overload ([materialize] with [WindowsCodePage]).
//
// Kotlin-idiomatic design: a bounded text accumulator tracks UTF-8 byte
// growth (the Rust String::len budget), and the writer is a single class
// with exhaustive `when` over the closed PortableValue kind set.

package consema.properties

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.PortableValue
import consema.core.PvEntryMapping
import consema.core.PvObject
import consema.core.PvString
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.core.equal
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
import java.nio.charset.CodingErrorAction

/**
 * Materializes one complete PortableValue into a new immutable Java
 * Properties document under the exact target profile and canonical style
 * (materialization.rs). A failure contains no Document, no partial
 * bytes, and no provenance that can be mistaken for a result (RFC 0004 §3).
 */
fun materialize(
    value: PortableValue,
    request: MaterializationRequest,
): MaterializationResult<Document> =
    materializeInternal(value, request, null)

/**
 * Materializes a Reader-profile document in one explicit published Windows
 * code page (materialization.rs). The document-layer request cannot
 * carry code pages (its SourceEncoding is the closed v1 set), so the code
 * page is an explicit overload parameter; the profile must be
 * java-properties.reader@1 or the request fails with UnsupportedEncoding.
 */
fun materialize(
    value: PortableValue,
    request: MaterializationRequest,
    codePage: WindowsCodePage,
): MaterializationResult<Document> =
    materializeInternal(value, request, codePage)

private fun materializeInternal(
    value: PortableValue,
    request: MaterializationRequest,
    codePage: WindowsCodePage?,
): MaterializationResult<Document> {
    val analyzed = ArrayList<ValuePath>()
    return try {
        MaterializationResult.Complete(materializeComplete(value, request, codePage, analyzed))
    } catch (e: MaterializationException) {
        MaterializationResult.Failed(
            FailedMaterializationAttempt(
                e,
                MaterializationReport.new(emptyList(), request.limits),
                analyzed,
            ),
        )
    }
}

private fun materializeComplete(
    value: PortableValue,
    request: MaterializationRequest,
    codePage: WindowsCodePage?,
    analyzed: ArrayList<ValuePath>,
): CompleteMaterialization<Document> {
    val profile = requestedProfile(request)
    val outputEncoding = resolveOutputEncoding(request, profile, codePage)
    val textLimit = textBudget(outputEncoding, request.limits.maxOutputBytes)
    val writer = Writer(
        profile = profile,
        newline = request.newline,
        limits = request.limits,
        textLimit = textLimit,
        analyzed = analyzed,
    )
    val inputEntries = writer.document(value, ValuePath.root(), 0)
    val text = writer.finish()
    val bytes = encodeText(text, outputEncoding, request.limits.maxOutputBytes)
    val selection = when (profile) {
        PropertiesProfile.ReaderV1 -> when (outputEncoding) {
            is OutputEncoding.CodePage -> PropertiesEncoding.WindowsCodePage(outputEncoding.number)
            else -> PropertiesEncoding.Reader(request.encoding)
        }
        PropertiesProfile.Latin1V1 -> PropertiesEncoding.Latin1
    }
    val document = try {
        parse(bytes, profile, selection, parseLimits(request.limits))
    } catch (e: PropertiesFormationException) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    if (document.formationStatus != consema.document.FormationStatus.Complete) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    verifyClosure(value, request, document)
    val provenance = buildProvenance(inputEntries, document, request.limits)
    return CompleteMaterialization(
        document = document,
        fidelity = MaterializationFidelity.Exact,
        report = MaterializationReport.new(emptyList(), request.limits),
        provenance = provenance,
    )
}

/** The exact target profile (materialization.rs). */
private fun requestedProfile(request: MaterializationRequest): PropertiesProfile =
    when {
        request.targetProfile.id == "java-properties.reader" && request.targetProfile.version == 1 ->
            PropertiesProfile.ReaderV1

        request.targetProfile.id == "java-properties.latin1" && request.targetProfile.version == 1 ->
            PropertiesProfile.Latin1V1

        else -> throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_PROFILE)
    }

/** The resolved output encoding of one request (materialization.rs).
 * The edit surface reuses the same encoding for replacement fragments
 * (kotlin/src/main/kotlin/consema/properties/Edit.kt:sourceOutputEncoding). */
internal sealed class OutputEncoding {
    data object Utf8 : OutputEncoding()

    data object Utf16Le : OutputEncoding()

    data object Utf16Be : OutputEncoding()

    data object Latin1 : OutputEncoding()

    data class CodePage(val number: Int) : OutputEncoding()
}

private fun resolveOutputEncoding(
    request: MaterializationRequest,
    profile: PropertiesProfile,
    codePage: WindowsCodePage?,
): OutputEncoding {
    val styleMatches = when (profile) {
        PropertiesProfile.ReaderV1 ->
            request.style.id == "java-properties.reader-canonical" && request.style.version == 1
        PropertiesProfile.Latin1V1 ->
            request.style.id == "java-properties.latin1-canonical" && request.style.version == 1
    }
    if (!styleMatches) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_STYLE)
    }
    if (request.newline != NewlinePolicy.Lf && request.newline != NewlinePolicy.CrLf) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_NEWLINE)
    }
    if (codePage != null) {
        if (profile != PropertiesProfile.ReaderV1) {
            throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
        }
        return OutputEncoding.CodePage(codePage.number)
    }
    return when {
        request.encoding === SourceEncoding.Binary ->
            throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)

        profile == PropertiesProfile.Latin1V1 && request.encoding !== SourceEncoding.Latin1 ->
            throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)

        request.encoding === SourceEncoding.Utf8 -> OutputEncoding.Utf8
        request.encoding === SourceEncoding.Utf16Le -> OutputEncoding.Utf16Le
        request.encoding === SourceEncoding.Utf16Be -> OutputEncoding.Utf16Be
        request.encoding === SourceEncoding.Latin1 -> OutputEncoding.Latin1
        else -> error("text encodings are closed")
    }
}

/** Derives the closure parse limits from the materialization limits
 * (materialization.rs). */
private fun parseLimits(limits: MaterializationLimits): PropertiesParseLimits =
    PropertiesParseLimits(
        common = ParseLimits(
            maxSourceBytes = limits.maxOutputBytes,
            maxNestingDepth = limits.maxDepth,
            maxTokenCount = limits.maxOutputBytes,
            maxNodeCount = saturatingMulAdd(limits.maxOutputBytes, 2, 1),
            maxDiagnostics = limits.maxReportEntries,
        ),
        maxDecodedUtf8Bytes = saturatingMul(limits.maxOutputBytes, 3),
        maxDecodedScalars = saturatingMul(limits.maxOutputBytes, 2),
        maxNaturalLines = limits.maxInputNodes,
        maxNaturalLineBytes = limits.maxOutputBytes,
        maxNaturalLineScalars = limits.maxOutputBytes,
        maxLogicalLines = limits.maxInputNodes,
        maxLogicalLineNaturalLines = 1,
        maxLogicalLineScalars = limits.maxOutputBytes,
        maxProperties = limits.maxInputNodes,
        maxComments = 0,
        maxEscapes = limits.maxOutputBytes,
        maxUnicodeEscapes = limits.maxOutputBytes,
        maxJavaCodeUnitsPerString = limits.maxOutputBytes,
        maxTotalJavaCodeUnits = saturatingMul(limits.maxOutputBytes, 2),
        maxDuplicateGroupMembers = limits.maxInputNodes,
        maxRecoveryRegions = limits.maxReportEntries,
    )

private fun saturatingMul(left: Int, right: Int): Int =
    (left.toLong() * right).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private fun saturatingMulAdd(left: Int, right: Int, add: Int): Int =
    (left.toLong() * right + add).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** The decoded-text budget of one output encoding (materialization.rs). */
private fun textBudget(encoding: OutputEncoding, maxOutputBytes: Int): Int =
    when (encoding) {
        OutputEncoding.Utf8 -> maxOutputBytes
        OutputEncoding.Utf16Le, OutputEncoding.Utf16Be, OutputEncoding.Latin1 ->
            saturatingMul(maxOutputBytes, 2)

        is OutputEncoding.CodePage ->
            if (encoding.number != 65001) saturatingMul(maxOutputBytes, 3) else maxOutputBytes
    }

/** One input mapping entry and its input locations (materialization.rs). */
private data class InputEntry(
    val association: MaterializationInputLocation,
    val key: MaterializationInputLocation,
    val value: MaterializationInputLocation,
)

/** One prepared key/value pair with its input locations. */
private data class MappingItem(
    val key: String,
    val value: PortableValue,
    val association: MaterializationInputLocation,
    val keyLocation: MaterializationInputLocation,
    val valuePath: ValuePath,
)

private class Writer(
    private val profile: PropertiesProfile,
    private val newline: NewlinePolicy,
    private val limits: MaterializationLimits,
    private val textLimit: Int,
    private val analyzed: ArrayList<ValuePath>,
) {
    private var inputNodes = 0
    private val output = BoundedText(textLimit)

    fun finish(): String = output.finish()

    /** Writes one `key=value` record per mapping entry (materialization.rs). */
    fun document(value: PortableValue, path: ValuePath, depth: Int): List<InputEntry> {
        val entries = mappingItems(value, path, depth)
        val inputEntries = ArrayList<InputEntry>(entries.size)
        for (entry in entries) {
            analyze(entry.valuePath, depth + 1)
            val stringValue = entry.value as? PvString
                ?: throw unrepresentable(entry.valuePath, entry.value.kind.name)
            writeString(entry.key, true)
            output.pushChar('=')
            writeString(stringValue.value, false)
            output.pushString(
                when (newline) {
                    NewlinePolicy.Lf -> "\n"
                    NewlinePolicy.CrLf -> "\r\n"
                    NewlinePolicy.None -> error("request validation rejects no newline")
                },
            )
            inputEntries.add(
                InputEntry(
                    association = entry.association,
                    key = entry.keyLocation,
                    value = MaterializationInputLocation.Value(entry.valuePath),
                ),
            )
        }
        return inputEntries
    }

    /** Flattens one root Object or EntryMapping (materialization.rs). */
    private fun mappingItems(value: PortableValue, path: ValuePath, depth: Int): List<MappingItem> {
        analyze(path, depth)
        val length = when (value) {
            is PvObject -> value.entries().size
            is PvEntryMapping -> value.entries().size
            else -> throw unrepresentable(path, value.kind.name)
        }
        if (length > limits.maxInputNodes) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "input-nodes",
            )
        }
        val items = ArrayList<MappingItem>(length)
        when (value) {
            is PvObject -> {
                for ((index, entry) in value.entries().withIndex()) {
                    val ordinal = index.toLong()
                    items.add(
                        MappingItem(
                            key = entry.key,
                            value = entry.value,
                            association = MaterializationInputLocation.Association(
                                AssociationLocation(path, ordinal, AssociationRole.ObjectEntry),
                            ),
                            keyLocation = MaterializationInputLocation.Association(
                                AssociationLocation(path, ordinal, AssociationRole.ObjectKey),
                            ),
                            valuePath = path.child(ValuePathSegment.ObjectValue(entry.key)),
                        ),
                    )
                }
            }
            is PvEntryMapping -> {
                for ((index, entry) in value.entries().withIndex()) {
                    val ordinal = index.toLong()
                    val keyPath = path.child(ValuePathSegment.EntryKey(ordinal))
                    analyze(keyPath, depth + 1)
                    val key = entry.key as? PvString
                        ?: throw unrepresentable(keyPath, entry.key.kind.name)
                    items.add(
                        MappingItem(
                            key = key.value,
                            value = entry.value,
                            association = MaterializationInputLocation.Association(
                                AssociationLocation(path, ordinal, AssociationRole.EntryMappingEntry),
                            ),
                            keyLocation = MaterializationInputLocation.Value(keyPath),
                            valuePath = path.child(ValuePathSegment.EntryValue(ordinal)),
                        ),
                    )
                }
            }
            else -> error("mapping kind was checked")
        }
        return items
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

    /** Deterministic escaping (RFC 0010 §12; materialization.rs). */
    private fun writeString(value: String, isKey: Boolean) {
        var leadingValueSpace = !isKey
        for (ch in value) {
            when {
                ch == ' ' && (isKey || leadingValueSpace) -> output.pushString("\\ ")
                ch == '\t' -> output.pushString("\\t")
                ch == '\n' -> output.pushString("\\n")
                ch == '\r' -> output.pushString("\\r")
                ch == '\u000C' -> output.pushString("\\f")
                ch == '\\' -> output.pushString("\\\\")
                ch == '#' || ch == '!' || ch == '=' || ch == ':' -> {
                    output.pushChar('\\')
                    output.pushChar(ch)
                }
                ch.isISOControl() -> writeUnicodeScalar(ch)
                profile == PropertiesProfile.Latin1V1 && ch.code !in 0x20..0x7e ->
                    writeUnicodeScalar(ch)

                else -> output.pushChar(ch)
            }
            if (ch != ' ') {
                leadingValueSpace = false
            }
        }
    }

    /** Uppercase four-digit `\uXXXX` escapes, one per UTF-16 code unit
     * (materialization.rs). */
    private fun writeUnicodeScalar(value: Char) {
        output.pushString("\\u")
        output.pushHexUnit(value.code)
    }

    private fun unrepresentable(path: ValuePath, kind: String): MaterializationException =
        MaterializationException(
            MaterializationFailureKind.UNREPRESENTABLE,
            "materialization: $kind is not representable at $path",
            path = path,
            valueKind = consema.core.Kind.entries.firstOrNull { it.name == kind },
        )
}

/** Bounded UTF-8-byte text accumulation (materialization.rs). */
private class BoundedText(private val maxBytes: Int) {
    private val text = StringBuilder()
    private var utf8Bytes = 0

    fun pushChar(ch: Char) {
        pushString(ch.toString())
    }

    fun pushString(chunk: String) {
        val newLength = utf8Bytes + chunk.toByteArray(Charsets.UTF_8).size
        if (newLength > maxBytes) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "output-bytes",
            )
        }
        text.append(chunk)
        utf8Bytes = newLength
    }

    fun pushHexUnit(unit: Int) {
        val digits = "0123456789ABCDEF"
        text.append(digits[(unit ushr 12) and 0xf])
        text.append(digits[(unit ushr 8) and 0xf])
        text.append(digits[(unit ushr 4) and 0xf])
        text.append(digits[unit and 0xf])
        utf8Bytes += 4
        if (utf8Bytes > maxBytes) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "output-bytes",
            )
        }
    }

    fun finish(): String = text.toString()
}

/** Encodes the canonical text to exact output bytes, adding the UTF-16 BOM
 * only here (materialization.rs). */
private fun encodeText(text: String, encoding: OutputEncoding, maxOutputBytes: Int): ByteArray {
    val bomBytes = when (encoding) {
        OutputEncoding.Utf16Le, OutputEncoding.Utf16Be -> 2
        else -> 0
    }
    val fragmentLimit = maxOutputBytes - bomBytes
    if (fragmentLimit < 0) {
        throw MaterializationException(
            MaterializationFailureKind.RESOURCE_LIMIT,
            name = "output-bytes",
        )
    }
    val fragment = encodeFragment(text, encoding, fragmentLimit)
    if (bomBytes == 0) {
        return fragment
    }
    val output = ByteArray(fragment.size + 2)
    if (encoding == OutputEncoding.Utf16Le) {
        output[0] = 0xff.toByte()
        output[1] = 0xfe.toByte()
    } else {
        output[0] = 0xfe.toByte()
        output[1] = 0xff.toByte()
    }
    System.arraycopy(fragment, 0, output, 2, fragment.size)
    return output
}

/** Encodes one text fragment without a BOM (materialization.rs). */
internal fun encodeFragment(text: String, encoding: OutputEncoding, maxOutputBytes: Int): ByteArray {
    val output: ByteArray = when (encoding) {
        OutputEncoding.Utf8 -> {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > maxOutputBytes) {
                throw MaterializationException(
                    MaterializationFailureKind.RESOURCE_LIMIT,
                    name = "output-bytes",
                )
            }
            bytes
        }
        OutputEncoding.Utf16Le, OutputEncoding.Utf16Be -> {
            val unitCount = text.length
            val length = unitCount * 2
            if (length > maxOutputBytes) {
                throw MaterializationException(
                    MaterializationFailureKind.RESOURCE_LIMIT,
                    name = "output-bytes",
                )
            }
            val bytes = ByteArray(length)
            for (i in text.indices) {
                val unit = text[i].code
                if (encoding == OutputEncoding.Utf16Le) {
                    bytes[i * 2] = (unit and 0xff).toByte()
                    bytes[i * 2 + 1] = (unit ushr 8).toByte()
                } else {
                    bytes[i * 2] = (unit ushr 8).toByte()
                    bytes[i * 2 + 1] = (unit and 0xff).toByte()
                }
            }
            bytes
        }
        OutputEncoding.Latin1 -> {
            if (text.length > maxOutputBytes) {
                throw MaterializationException(
                    MaterializationFailureKind.RESOURCE_LIMIT,
                    name = "output-bytes",
                )
            }
            val bytes = ByteArray(text.length)
            for (i in text.indices) {
                val code = text[i].code
                if (code > 0xff) {
                    throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
                }
                bytes[i] = code.toByte()
            }
            bytes
        }
        is OutputEncoding.CodePage -> encodeCodePage(text, encoding.number, maxOutputBytes)
    }
    if (output.size > maxOutputBytes) {
        throw MaterializationException(
            MaterializationFailureKind.RESOURCE_LIMIT,
            name = "output-bytes",
        )
    }
    return output
}

/** One Windows code-page encode (1252 uses the exact WHATWG table; the
 * other published pages use the JDK charsets, materialization.rs). */
private fun encodeCodePage(text: String, number: Int, maxOutputBytes: Int): ByteArray {
    if (number == 1252) {
        val codePage = WindowsCodePage(1252)
        val bytes = ByteArray(text.length)
        for (i in text.indices) {
            val byte = codePage.encodeChar(text[i])
                ?: throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
            bytes[i] = byte.toByte()
        }
        if (bytes.size > maxOutputBytes) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "output-bytes",
            )
        }
        return bytes
    }
    val encoder = WindowsCodePage(number).charset()
        .newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val buffer = try {
        encoder.encode(java.nio.CharBuffer.wrap(text))
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
    }
    val bytes = ByteArray(buffer.limit())
    buffer.duplicate().get(bytes)
    if (bytes.size > maxOutputBytes) {
        throw MaterializationException(
            MaterializationFailureKind.RESOURCE_LIMIT,
            name = "output-bytes",
        )
    }
    return bytes
}

/** Exact closure: the materialized document reprojects to the identical
 * portable value under the request's policy (RFC 0010 §12;
 * materialization.rs). */
private fun verifyClosure(
    input: PortableValue,
    request: MaterializationRequest,
    document: Document,
) {
    val projectionLimits = ProjectionLimits(
        maxSourceAssociations = request.limits.maxInputNodes,
        maxValueNodes = saturatingMulAdd(request.limits.maxInputNodes, 2, 1),
        maxReportEntries = request.limits.maxReportEntries,
        maxProvenanceUnits = request.limits.maxProvenanceEntries,
    )
    val projection = if (input is PvObject) {
        document.project(
            ProjectionRequest.requireObject(DuplicatePolicy.RequireUnique)
                .withLimits(projectionLimits),
        )
    } else {
        document.project(
            ProjectionRequest.bestExactEntryMapping().withLimits(projectionLimits),
        )
    }
    when (projection) {
        is ProjectionResult.Complete -> {
            if (!equal(projection.projection.value, input)) {
                throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
            }
        }
        is ProjectionResult.Failed -> {
            val diagnostic = projection.attempt.diagnostics.firstOrNull()
            if (diagnostic != null && diagnostic.code == "core.projection.resource-limit@1") {
                val limit = diagnostic.arguments["limit"]
                val name = when (limit) {
                    "max_source_associations", "max_value_nodes" -> "input-nodes"
                    "max_report_entries" -> "report-entries"
                    "max_provenance_units" -> "provenance-entries"
                    else -> "projection"
                }
                throw MaterializationException(
                    MaterializationFailureKind.RESOURCE_LIMIT,
                    name = name,
                )
            }
            throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        }
    }
}

/** Input-to-output provenance (materialization.rs). */
private fun buildProvenance(
    inputEntries: List<InputEntry>,
    document: Document,
    limits: MaterializationLimits,
): MaterializationProvenanceMap {
    if (inputEntries.size != document.propertyEntities.size) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    val entries = ArrayList<MaterializationProvenanceEntry>(inputEntries.size * 3 + 1)
    val rootSpan = try {
        document.authority.span(0, document.source.len)
    } catch (e: consema.document.LocationException) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    entries.add(
        provenanceEntry(
            MaterializationInputLocation.Value(ValuePath.root()),
            document.rootNode,
            listOf(rootSpan),
            document,
        ),
    )
    for ((input, property) in inputEntries.zip(document.propertyEntities)) {
        entries.add(
            provenanceEntry(
                input.association,
                property.node,
                listOf(property.span),
                document,
            ),
        )
        entries.add(
            provenanceEntry(
                input.key,
                property.node,
                nonemptySpans(property.keyFragments, property.keyAnchor),
                document,
            ),
        )
        entries.add(
            provenanceEntry(
                input.value,
                property.node,
                nonemptySpans(property.valueFragments, property.valueAnchor),
                document,
            ),
        )
    }
    return MaterializationProvenanceMap.new(entries, document.snapshotIdentity, limits)
}

private fun nonemptySpans(fragments: List<Span>, anchor: Span): List<Span> =
    if (fragments.isEmpty()) listOf(anchor) else fragments

private fun provenanceEntry(
    input: MaterializationInputLocation,
    node: consema.document.NodeRef,
    spans: List<Span>,
    document: Document,
): MaterializationProvenanceEntry =
    MaterializationProvenanceEntry(
        input,
        spans.map { span ->
            MaterializedOrigin(
                snapshot = document.snapshotIdentity,
                node = node,
                span = span,
                relation = MaterializationRelation.Reencoded,
            )
        },
    )
