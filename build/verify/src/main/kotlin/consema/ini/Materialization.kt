// Deterministic PortableValue materialization for explicit INI profiles.
//
// Data authority:
//   - RFC 0004 §3-§8 (docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:56-218): the common MaterializationRequest v1,
//     ExactOnly representability, the completion algebra, and the
//     provenance direction (portable input locations to the new Document).
//   - RFC 0009 §11 (docs/rfcs/0009-ini-family-profiles-v1.md:387-435): the
//     canonical styles ini.portable-canonical@1 / ini.windows-canonical@1 /
//     ini.python-configparser-canonical@1; the exact request combinations
//     (portable UTF-8 + LF, windows UTF-16LE + BOM or one explicit
//     registered code page + CRLF, python one non-Binary registered text
//     encoding + LF); strict encoding with whole-operation failure on
//     unrepresentable scalars; both levels consistently a nested EntryMapping
//     or nested Object of Strings; Object input cannot fabricate Windows
//     case-equivalent collisions; Python stored values whose per-line edge
//     whitespace or terminal empty line would be normalized away are
//     unrepresentable; all styles reparse under the exact target profile and
//     reproject under the request's policy before success.
//   - conformance/vectors/ini-v1.json (materialization.*) pins the golden
//     output bytes and failure names; crates/consema-ini/src/
//     materialization.rs is the byte-arbitration authority (writer
//     materialization.rs:191-461, encoding materialization.rs:724-850,
//     closure materialization.rs:489-535, provenance materialization.rs:
//     537-677).
//   - The Kotlin document package owns the completion algebra types
//     (kotlin/.../document/Materialization.kt:286-371).
//
// Kotlin-idiomatic design: a bounded output buffer wraps the JDK byte
// accumulation with explicit checked growth; the writer is a single
// recursive class with exhaustive `when` over the closed PortableValue
// kind set; the closure equality is an explicit content comparison because
// the core container classes use reference equality.

package consema.ini

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.Kind
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat32
import consema.core.PvBinaryFloat64
import consema.core.PvBytes
import consema.core.PvDecimal
import consema.core.PvEntryMapping
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
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
import consema.document.SourceEncoding
import consema.document.Span
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Materializes one complete nested String mapping into a new immutable INI
 * document (materialization.rs:26-41). A failure contains no Document, no
 * partial bytes, and no provenance that can be mistaken for a result
 * (RFC 0004 §3).
 */
fun materialize(
    value: PortableValue,
    request: MaterializationRequest,
): MaterializationResult<IniDocument> {
    val analyzed = ArrayList<ValuePath>()
    return try {
        MaterializationResult.Complete(materializeComplete(value, request, null, analyzed))
    } catch (e: MaterializationException) {
        MaterializationResult.Failed(
            FailedMaterializationAttempt(e, MaterializationReport.new(emptyList(), request.limits), analyzed),
        )
    }
}

/**
 * Materializes one nested String mapping through one explicit Windows code
 * page (RFC 0009 §3.2, §11). The shared [MaterializationRequest] cannot yet
 * express a code page because the document package's v1 SourceEncoding set
 * has no WindowsCodePage member (the source-v2 extension belongs to the L2
 * properties milestone, kotlin/.../document/Encoding.kt:18-25); this
 * overload takes the code page explicitly and requires the windows profile
 * with the windows-canonical style and CRLF.
 */
fun materialize(
    value: PortableValue,
    request: MaterializationRequest,
    codePage: IniWindowsCodePage,
): MaterializationResult<IniDocument> {
    val analyzed = ArrayList<ValuePath>()
    return try {
        MaterializationResult.Complete(
            materializeComplete(
                value,
                request,
                IniSourceEncoding.WindowsCodePage(codePage),
                analyzed,
            ),
        )
    } catch (e: MaterializationException) {
        MaterializationResult.Failed(
            FailedMaterializationAttempt(e, MaterializationReport.new(emptyList(), request.limits), analyzed),
        )
    }
}

/** The canonical entry text used by structural edits (edit.rs:1101-1167;
 * materialization.rs:384-461). Returns the decoded text including the
 * profile newline; the edit layer encodes it in the document's selected
 * encoding. */
internal fun canonicalEntryText(
    profile: IniProfile,
    key: String,
    value: String,
    max: Int,
): String {
    val writer = Writer(
        profile,
        IniSourceEncoding.Utf8,
        MaterializationLimits(
            maxInputNodes = max,
            maxOutputBytes = max,
            maxDepth = 0,
            maxReportEntries = 0,
            maxProvenanceEntries = 0,
        ),
        ArrayList(),
        max,
    )
    writer.writeEntry(key, value)
    return writer.output.finish()
}

private fun materializeComplete(
    value: PortableValue,
    request: MaterializationRequest,
    codePageOverride: IniSourceEncoding.WindowsCodePage?,
    analyzed: ArrayList<ValuePath>,
): CompleteMaterialization<IniDocument> {
    val profile = requestedProfile(request)
    val encoding = requestedEncoding(request, codePageOverride)
    validateRequest(request, profile, encoding)
    val utf8Budget = textBudget(encoding, request.limits.maxOutputBytes)
    val writer = Writer(profile, encoding, request.limits, analyzed, utf8Budget)
    val sections = writer.document(value, ValuePath.root(), 0)
    val text = writer.output.finish()
    val bytes = encodeText(text, encoding, request.limits.maxOutputBytes)
    val selection = parseEncodingSelection(profile, encoding)
    val document = try {
        parse(bytes, profile, selection, parseLimits(request.limits))
    } catch (e: IniFormationException) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    if (document.formationStatus() != consema.document.FormationStatus.Complete) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    verifyClosure(value, request, document)
    val provenance = buildProvenance(value, sections, document, request.limits)
    return CompleteMaterialization(
        document = document,
        fidelity = MaterializationFidelity.Exact,
        report = MaterializationReport.new(emptyList(), request.limits),
        provenance = provenance,
    )
}

/** Resolves the target profile (materialization.rs:77-89). */
private fun requestedProfile(request: MaterializationRequest): IniProfile =
    when {
        request.targetProfile.id == "ini.portable" && request.targetProfile.version == 1 ->
            IniProfile.PortableV1

        request.targetProfile.id == "ini.windows" && request.targetProfile.version == 1 ->
            IniProfile.WindowsV1

        request.targetProfile.id == "ini.python-configparser" && request.targetProfile.version == 1 ->
            IniProfile.PythonConfigParserV1

        else -> throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_PROFILE)
    }

/** Resolves the effective INI encoding (materialization.rs:115-125). */
private fun requestedEncoding(
    request: MaterializationRequest,
    codePageOverride: IniSourceEncoding.WindowsCodePage?,
): IniSourceEncoding {
    if (codePageOverride != null) {
        return codePageOverride
    }
    return when (request.encoding) {
        SourceEncoding.Utf8 -> IniSourceEncoding.Utf8
        SourceEncoding.Utf16Le -> IniSourceEncoding.Utf16Le
        SourceEncoding.Utf16Be -> IniSourceEncoding.Utf16Be
        SourceEncoding.Latin1 -> IniSourceEncoding.Latin1
        SourceEncoding.Binary -> throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
    }
}

/** Validates the style/newline/encoding combination (materialization.rs:91-127). */
private fun validateRequest(
    request: MaterializationRequest,
    profile: IniProfile,
    encoding: IniSourceEncoding,
) {
    val styleMatches = when {
        profile == IniProfile.PortableV1 ->
            request.style.id == "ini.portable-canonical" && request.style.version == 1
        profile == IniProfile.WindowsV1 ->
            request.style.id == "ini.windows-canonical" && request.style.version == 1
        else ->
            request.style.id == "ini.python-configparser-canonical" && request.style.version == 1
    }
    if (!styleMatches) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_STYLE)
    }
    val expectedNewline = when (profile) {
        IniProfile.WindowsV1 -> NewlinePolicy.CrLf
        IniProfile.PortableV1, IniProfile.PythonConfigParserV1 -> NewlinePolicy.Lf
    }
    if (request.newline != expectedNewline) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_NEWLINE)
    }
    val encodingValid = when (profile) {
        IniProfile.PortableV1 -> encoding === IniSourceEncoding.Utf8
        IniProfile.WindowsV1 ->
            encoding === IniSourceEncoding.Utf16Le || encoding is IniSourceEncoding.WindowsCodePage
        IniProfile.PythonConfigParserV1 -> true
    }
    if (!encodingValid) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
    }
}

/** The reparse encoding selection (materialization.rs:129-135). */
private fun parseEncodingSelection(
    profile: IniProfile,
    encoding: IniSourceEncoding,
): IniEncodingSelection =
    when {
        (profile == IniProfile.PortableV1 || profile == IniProfile.PythonConfigParserV1) &&
            encoding === IniSourceEncoding.Utf8 -> IniEncodingSelection.ProfileDefault

        profile == IniProfile.WindowsV1 && encoding === IniSourceEncoding.Utf16Le ->
            IniEncodingSelection.ProfileDefault

        else -> IniEncodingSelection.Explicit(encoding)
    }

/** Derives the closure parse limits from the materialization limits
 * (materialization.rs:137-160). */
private fun parseLimits(limits: MaterializationLimits): IniParseLimits =
    IniParseLimits(
        common = consema.document.ParseLimits(
            maxSourceBytes = limits.maxOutputBytes,
            maxNestingDepth = limits.maxDepth,
            maxTokenCount = limits.maxOutputBytes,
            maxNodeCount = limits.maxOutputBytes,
            maxDiagnostics = limits.maxReportEntries,
        ),
        maxDecodedUtf8Bytes = limits.maxOutputBytes.saturatingMul(3),
        maxDecodedScalars = limits.maxOutputBytes,
        maxPhysicalLines = limits.maxOutputBytes,
        maxPhysicalLineBytes = limits.maxOutputBytes,
        maxPhysicalLineScalars = limits.maxOutputBytes,
        maxLogicalLines = limits.maxInputNodes,
        maxLogicalLineBytes = limits.maxOutputBytes,
        maxLogicalLineScalars = limits.maxOutputBytes,
        maxContinuationLines = limits.maxOutputBytes,
        maxSections = limits.maxInputNodes,
        maxEntries = limits.maxInputNodes,
        maxDuplicateGroupMembers = limits.maxInputNodes,
        maxRecoveryRegions = limits.maxReportEntries,
    )

/** The mapping shape of one input level (materialization.rs:162-166). */
private enum class MappingShape {
    Object,
    EntryMapping,
}

/** One input entry with its provenance locations (materialization.rs:168-173). */
private data class InputEntry(
    val association: MaterializationInputLocation,
    val key: MaterializationInputLocation,
    val value: MaterializationInputLocation,
)

/** One input section with its provenance locations (materialization.rs:175-181). */
private data class InputSection(
    val association: MaterializationInputLocation,
    val key: MaterializationInputLocation,
    val value: MaterializationInputLocation,
    val entries: List<InputEntry>,
)

/** One mapping item with its locations (materialization.rs:183-189). */
private data class MappingItem(
    val key: String,
    val value: PortableValue,
    val association: MaterializationInputLocation,
    val keyLocation: MaterializationInputLocation,
    val valuePath: ValuePath,
)

/** The bounded canonical writer (materialization.rs:191-461). */
private class Writer(
    private val profile: IniProfile,
    private val encoding: IniSourceEncoding,
    private val limits: MaterializationLimits,
    private val analyzed: ArrayList<ValuePath>,
    maxTextBytes: Int,
) {
    private var inputNodes = 0
    val output = BoundedText(maxTextBytes)

    fun document(value: PortableValue, path: ValuePath, depth: Int): List<InputSection> {
        val (shape, outer) = mappingItems(value, path, depth)
        if (shape == MappingShape.Object && profile == IniProfile.WindowsV1) {
            rejectCaseEquivalentObjectNames(outer)
        }
        val sections = ArrayList<InputSection>(outer.size)
        for (section in outer) {
            validateSectionName(section.key)
            output.pushChar('[')
            output.pushStr(section.key)
            output.pushChar(']')
            newline()
            val (entryShape, entries) = mappingItems(section.value, section.valuePath, depth + 1)
            if (entryShape == MappingShape.Object && profile == IniProfile.WindowsV1) {
                rejectCaseEquivalentObjectNames(entries)
            }
            val inputEntries = ArrayList<InputEntry>(entries.size)
            for (entry in entries) {
                validateKey(entry.key)
                analyze(entry.valuePath, depth + 2)
                val stringValue = entry.value as? PvString
                    ?: throw MaterializationException(
                        MaterializationFailureKind.UNREPRESENTABLE,
                        path = entry.valuePath,
                        valueKind = entry.value.kind,
                    )
                writeEntry(entry.key, stringValue.value)
                inputEntries.add(
                    InputEntry(
                        association = entry.association,
                        key = entry.keyLocation,
                        value = MaterializationInputLocation.Value(entry.valuePath),
                    ),
                )
            }
            sections.add(
                InputSection(
                    association = section.association,
                    key = section.keyLocation,
                    value = MaterializationInputLocation.Value(section.valuePath),
                    entries = inputEntries,
                ),
            )
        }
        return sections
    }

    private fun mappingItems(
        value: PortableValue,
        path: ValuePath,
        depth: Int,
    ): Pair<MappingShape, List<MappingItem>> {
        analyze(path, depth)
        val length = when (value) {
            is PvObject -> value.entries().size
            is PvEntryMapping -> value.entries().size
            else -> throw MaterializationException(
                MaterializationFailureKind.UNREPRESENTABLE,
                path = path,
                valueKind = value.kind,
            )
        }
        if (length > limits.maxInputNodes) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "input-nodes",
            )
        }
        val items = ArrayList<MappingItem>(length)
        if (value is PvObject) {
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
            return MappingShape.Object to items
        }
        for ((index, entry) in (value as PvEntryMapping).entries().withIndex()) {
            val ordinal = index.toLong()
            val keyPath = path.child(ValuePathSegment.EntryKey(ordinal))
            analyze(keyPath, depth + 1)
            val key = entry.key as? PvString
                ?: throw MaterializationException(
                    MaterializationFailureKind.UNREPRESENTABLE,
                    path = keyPath,
                    valueKind = entry.key.kind,
                )
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
        return MappingShape.EntryMapping to items
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

    private fun validateSectionName(value: String) {
        val valid = when (profile) {
            IniProfile.PortableV1 -> value.isNotEmpty() && value.all { isPortableNameChar(it) }
            IniProfile.WindowsV1 -> value.isNotEmpty() && value.all { isWindowsNameChar(it) }
            IniProfile.PythonConfigParserV1 ->
                value.isNotEmpty() && value.none { it.code == 0 || it == '\r' || it == '\n' }
        }
        if (!valid) {
            throw MaterializationException(
                MaterializationFailureKind.INVALID_REQUEST,
                reason = "section name is not representable",
            )
        }
    }

    private fun validateKey(value: String) {
        val valid = when (profile) {
            IniProfile.PortableV1 -> value.isNotEmpty() && value.all { isPortableNameChar(it) }
            IniProfile.WindowsV1 ->
                value.isNotEmpty() &&
                    value.trim(' ', '\t') == value &&
                    value.all { isWindowsNameChar(it) }

            IniProfile.PythonConfigParserV1 ->
                value.isNotEmpty() &&
                    value.trim(' ', '\t') == value &&
                    value.none { it.code == 0 || it == '\r' || it == '\n' || it == '=' || it == ':' }
        }
        if (!valid) {
            throw MaterializationException(
                MaterializationFailureKind.INVALID_REQUEST,
                reason = "entry key is not representable",
            )
        }
    }

    fun writeEntry(key: String, value: String) {
        when (profile) {
            IniProfile.PortableV1 -> {
                if (!value.all { isPortableValueChar(it) }) {
                    throw MaterializationException(
                        MaterializationFailureKind.INVALID_REQUEST,
                        reason = "portable value is not representable",
                    )
                }
                output.pushStr(key)
                output.pushChar('=')
                output.pushStr(value)
                newline()
            }
            IniProfile.WindowsV1 -> {
                if (value.any { it.code == 0 || it == '\r' || it == '\n' }) {
                    throw MaterializationException(
                        MaterializationFailureKind.INVALID_REQUEST,
                        reason = "Windows value is not representable",
                    )
                }
                output.pushStr(key)
                output.pushChar('=')
                if (windowsValueNeedsQuotes(value)) {
                    val quote = if (value.startsWith("\"") && value.endsWith("\"")) '\'' else '"'
                    output.pushChar(quote)
                    output.pushStr(value)
                    output.pushChar(quote)
                } else {
                    output.pushStr(value)
                }
                newline()
            }
            IniProfile.PythonConfigParserV1 -> writePythonEntry(key, value)
        }
    }

    private fun writePythonEntry(key: String, value: String) {
        if (value.any { it.code == 0 || it == '\r' }) {
            throw MaterializationException(
                MaterializationFailureKind.INVALID_REQUEST,
                reason = "Python value is not representable",
            )
        }
        if (value.endsWith('\n')) {
            throw MaterializationException(
                MaterializationFailureKind.INVALID_REQUEST,
                reason = "trailing empty Python value line is not representable",
            )
        }
        val lines = value.split('\n')
        validatePythonValueLine(lines[0])
        output.pushStr(key)
        output.pushStr(" =")
        if (lines[0].isNotEmpty()) {
            output.pushChar(' ')
            output.pushStr(lines[0])
        }
        newline()
        for (line in lines.drop(1)) {
            validatePythonValueLine(line)
            if (line.isNotEmpty()) {
                output.pushStr("    ")
            }
            output.pushStr(line)
            newline()
        }
    }

    private fun newline() {
        output.pushStr(if (profile == IniProfile.WindowsV1) "\r\n" else "\n")
    }
}

private fun validatePythonValueLine(line: String) {
    if (line.trim(' ', '\t') != line) {
        throw MaterializationException(
            MaterializationFailureKind.INVALID_REQUEST,
            reason = "Python value line edge whitespace is not representable",
        )
    }
}

/** Object input cannot fabricate Windows case-equivalent collisions
 * (RFC 0009 §11; materialization.rs:473-487). */
private fun rejectCaseEquivalentObjectNames(items: List<MappingItem>) {
    val seen = HashSet<String>()
    if (items.any { !seen.add(asciiLowercase(it.key)) }) {
        throw MaterializationException(
            MaterializationFailureKind.INVALID_REQUEST,
            reason = "Object cannot fabricate Windows case-equivalent collisions",
        )
    }
}

/** The exact closure reparse-and-reproject check (materialization.rs:489-535). */
private fun verifyClosure(
    input: PortableValue,
    request: MaterializationRequest,
    document: IniDocument,
) {
    val projectionLimits = ProjectionLimits(
        maxSourceAssociations = request.limits.maxInputNodes,
        maxValueNodes = request.limits.maxInputNodes,
        maxReportEntries = request.limits.maxReportEntries,
        maxProvenanceUnits = request.limits.maxProvenanceEntries,
    )
    val projection = if (input.kind == Kind.Object) {
        document.project(
            ProjectionRequest.requireObject(NameComparison.OriginalExact, CollisionPolicy.Reject)
                .withLimits(projectionLimits),
        )
    } else {
        document.project(
            ProjectionRequest.bestExactEntryMapping().withLimits(projectionLimits),
        )
    }
    when (projection) {
        is ProjectionResult.Complete ->
            if (!portableValuesEqual(projection.projection.value, input)) {
                throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
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

/** Input-to-output provenance collection (materialization.rs:537-677). */
private fun buildProvenance(
    input: PortableValue,
    sections: List<InputSection>,
    document: IniDocument,
    limits: MaterializationLimits,
): MaterializationProvenanceMap {
    val entries = ArrayList<MaterializationProvenanceEntry>()
    entries.add(
        provenanceEntry(
            MaterializationInputLocation.Value(ValuePath.root()),
            document.rootNode,
            document.authority.span(0, document.sourceSnapshot.len),
            document,
            MaterializationRelation.Reencoded,
        ),
    )
    var entryOffset = 0
    for ((sectionIndex, inputSection) in sections.withIndex()) {
        val section = document.sectionsList.getOrNull(sectionIndex)
            ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        entries.add(
            provenanceEntry(
                inputSection.association,
                section.nodeRef,
                section.span,
                document,
                MaterializationRelation.Reencoded,
            ),
        )
        entries.add(
            provenanceEntry(
                inputSection.key,
                section.nodeRef,
                section.nameSpan,
                document,
                MaterializationRelation.Reencoded,
            ),
        )
        entries.add(
            provenanceEntry(
                inputSection.value,
                section.nodeRef,
                section.span,
                document,
                MaterializationRelation.Generated,
            ),
        )
        for (inputEntry in inputSection.entries) {
            val entry = document.entriesList.getOrNull(entryOffset)
                ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
            if (entry.section != section.nodeRef) {
                throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
            }
            entries.add(
                provenanceEntry(
                    inputEntry.association,
                    entry.nodeRef,
                    entry.span,
                    document,
                    MaterializationRelation.Reencoded,
                ),
            )
            entries.add(
                provenanceEntry(
                    inputEntry.key,
                    entry.nodeRef,
                    entry.keySpan,
                    document,
                    MaterializationRelation.Reencoded,
                ),
            )
            val valueOutputs = ArrayList<MaterializedOrigin>()
            valueOutputs.add(
                MaterializedOrigin(
                    document.snapshotIdentity,
                    entry.nodeRef,
                    entry.valueSpan,
                    MaterializationRelation.Reencoded,
                ),
            )
            appendContinuationOutputs(document, entry, valueOutputs)
            entries.add(MaterializationProvenanceEntry(inputEntry.value, valueOutputs))
            entryOffset += 1
        }
    }
    if (entryOffset != document.entriesList.size ||
        (input.kind != Kind.Object && input.kind != Kind.EntryMapping)
    ) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    return MaterializationProvenanceMap.new(entries, document.snapshotIdentity, limits)
}

/** Continuation value fragments join the value's output origins
 * (materialization.rs:629-659). */
private fun appendContinuationOutputs(
    document: IniDocument,
    entry: IniEntry,
    output: MutableList<MaterializedOrigin>,
) {
    val logical = document.logicalLinesList.firstOrNull { it.nodeRef == entry.logicalLine }
        ?: return
    val pieces = document.pieces()
    val kinds = document.losslessSyntaxKinds()
    for (physicalNode in logical.physicalLines.drop(1)) {
        val physical = try {
            document.physicalLine(physicalNode)
        } catch (e: IniAccessException) {
            continue
        }
        val contentStart = physical.contentSpan.startByte
        val contentEnd = physical.contentSpan.endByte
        val start = pieces.indexOfFirst { it.span.endByte > contentStart }
            .let { if (it < 0) pieces.size else it }
        for (ordinal in start until pieces.size) {
            val piece = pieces[ordinal]
            if (piece.span.startByte >= contentEnd) {
                break
            }
            if (kinds[ordinal] == IniSyntaxKind.EntryValue) {
                output.add(
                    MaterializedOrigin(
                        document.snapshotIdentity,
                        entry.nodeRef,
                        piece.span,
                        MaterializationRelation.Reencoded,
                    ),
                )
            }
        }
    }
}

private fun provenanceEntry(
    input: MaterializationInputLocation,
    node: consema.document.NodeRef,
    span: Span,
    document: IniDocument,
    relation: MaterializationRelation,
): MaterializationProvenanceEntry =
    MaterializationProvenanceEntry(
        input,
        listOf(
            MaterializedOrigin(document.snapshotIdentity, node, span, relation),
        ),
    )

// ---------------------------------------------------------------------------
// Output encoding (materialization.rs:679-850)
// ---------------------------------------------------------------------------

/** Bounded decoded-text accumulation (materialization.rs:679-722). The
 * bound counts UTF-8 bytes (the Rust String::len), not host UTF-16 units. */
private class BoundedText(private val maxBytes: Int) {
    private val text = StringBuilder()
    private var byteCount = 0

    fun pushStr(value: String) {
        val bytes = utf8ByteLength(value)
        if (byteCount + bytes > maxBytes) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                name = "output-bytes",
            )
        }
        byteCount += bytes
        text.append(value)
    }

    fun pushChar(value: Char) {
        pushStr(value.toString())
    }

    fun finish(): String = text.toString()
}

/** The decoded-text budget of one output encoding (materialization.rs:724-738). */
private fun textBudget(encoding: IniSourceEncoding, maxOutputBytes: Int): Int =
    when (encoding) {
        IniSourceEncoding.Utf16Le, IniSourceEncoding.Utf16Be, IniSourceEncoding.Latin1 ->
            maxOutputBytes.saturatingMul(2)

        is IniSourceEncoding.WindowsCodePage ->
            if (encoding.codePage.number != 65001) {
                maxOutputBytes.saturatingMul(3)
            } else {
                maxOutputBytes
            }

        IniSourceEncoding.Utf8 -> maxOutputBytes
    }

/** Encodes the final text with the encoding's BOM prefix (materialization.rs:740-768). */
private fun encodeText(text: String, encoding: IniSourceEncoding, maxOutputBytes: Int): ByteArray {
    val bomBytes = if (encoding === IniSourceEncoding.Utf16Le ||
        encoding === IniSourceEncoding.Utf16Be
    ) {
        2
    } else {
        0
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
    output[0] = if (encoding === IniSourceEncoding.Utf16Le) 0xff.toByte() else 0xfe.toByte()
    output[1] = if (encoding === IniSourceEncoding.Utf16Le) 0xfe.toByte() else 0xff.toByte()
    System.arraycopy(fragment, 0, output, 2, fragment.size)
    return output
}

/** Strict encoding of one text fragment (materialization.rs:770-829). */
internal fun encodeFragment(
    text: String,
    encoding: IniSourceEncoding,
    maxOutputBytes: Int,
): ByteArray {
    val output: ByteArray = when (encoding) {
        IniSourceEncoding.Utf8 -> text.toByteArray(Charsets.UTF_8)
        IniSourceEncoding.Utf16Le, IniSourceEncoding.Utf16Be -> {
            val units = text.length
            val length = units * 2
            if (length > maxOutputBytes) {
                throw MaterializationException(
                    MaterializationFailureKind.RESOURCE_LIMIT,
                    name = "output-bytes",
                )
            }
            val bytes = ByteArray(length)
            var offset = 0
            for (unit in text) {
                val value = unit.code
                if (encoding === IniSourceEncoding.Utf16Le) {
                    bytes[offset] = (value and 0xff).toByte()
                    bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
                } else {
                    bytes[offset] = ((value ushr 8) and 0xff).toByte()
                    bytes[offset + 1] = (value and 0xff).toByte()
                }
                offset += 2
            }
            bytes
        }
        IniSourceEncoding.Latin1 -> {
            if (text.length > maxOutputBytes) {
                throw MaterializationException(
                    MaterializationFailureKind.RESOURCE_LIMIT,
                    name = "output-bytes",
                )
            }
            val bytes = ByteArray(text.length)
            for ((index, character) in text.withIndex()) {
                val code = character.code
                if (code > 0xff) {
                    throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
                }
                bytes[index] = code.toByte()
            }
            bytes
        }
        is IniSourceEncoding.WindowsCodePage -> {
            val charset = codePageCharsetPublic(encoding.codePage.number)
            val encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                val buffer = encoder.encode(CharBuffer.wrap(text))
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                bytes
            } catch (e: CharacterCodingException) {
                throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
            }
        }
    }
    if (output.size > maxOutputBytes) {
        throw MaterializationException(
            MaterializationFailureKind.RESOURCE_LIMIT,
            name = "output-bytes",
        )
    }
    return output
}

/** The JDK charset approximating one published Windows code page
 * (materialization.rs:831-850). */
private fun codePageCharsetPublic(number: Int): Charset =
    when (number) {
        874 -> Charset.forName("x-windows-874")
        932 -> Charset.forName("windows-31j")
        936 -> Charset.forName("GBK")
        949 -> Charset.forName("EUC-KR")
        950 -> Charset.forName("Big5")
        65001 -> Charsets.UTF_8
        in 1250..1258 -> Charset.forName("windows-$number")
        else -> throw IllegalStateException("IniWindowsCodePage rejects unpublished values")
    }

/** Whether a Windows canonical value needs deterministic quoting
 * (materialization.rs:874-888; RFC 0009 §11). */
internal fun windowsValueNeedsQuotes(value: String): Boolean =
    value.firstOrNull()?.let { it == ' ' || it == '\t' } == true ||
        value.lastOrNull()?.let { it == ' ' || it == '\t' } == true ||
        (value.length >= 2 &&
            ((value.first() == '\'' && value.last() == '\'') ||
                (value.first() == '"' && value.last() == '"')))

// ---------------------------------------------------------------------------
// Portable character rules (materialization.rs:860-872)
// ---------------------------------------------------------------------------

private fun isPortableNameChar(character: Char): Boolean {
    val value = character.code
    return (value in 0x30..0x39) || (value in 0x41..0x5a) || (value in 0x61..0x7a) ||
        value == 0x5f || value == 0x2d || value == 0x2e
}

private fun isPortableValueChar(character: Char): Boolean {
    val value = character.code
    return (value in 0x21..0x7e && value !in setOf(0x27, 0x22, 0x5c, 0x3a, 0x23, 0x3b)) ||
        value == 0x20
}

private fun isWindowsNameChar(character: Char): Boolean {
    val value = character.code
    return (value in 0x21..0x7e || value == 0x20) && value !in setOf(0x5b, 0x5d, 0x3d, 0x00, 0x0d, 0x0a)
}

/** Content equality of two portable values. The core container classes
 * (PvObject, PvArray, PvEntryMapping) use reference equality
 * (kotlin/.../core/Value.kt:159-254), so the INI closure check compares
 * recursively; the kinds INI can never produce (temporal values) fall back
 * to their own equality. */
internal fun portableValuesEqual(left: PortableValue, right: PortableValue): Boolean {
    if (left === right) return true
    if (left.kind != right.kind) return false
    return when (left) {
        is PvNull -> true
        is PvBoolean -> left.value == (right as PvBoolean).value
        is PvString -> left.value == (right as PvString).value
        is PvInteger -> left.value == (right as PvInteger).value
        is PvDecimal -> {
            val other = right as PvDecimal
            left.coefficient == other.coefficient && left.exponent == other.exponent
        }
        is PvBinaryFloat32 -> left.bits == (right as PvBinaryFloat32).bits
        is PvBinaryFloat64 -> left.bits == (right as PvBinaryFloat64).bits
        is PvBytes -> left.content().contentEquals((right as PvBytes).content())
        is PvArray -> {
            val other = right as PvArray
            left.items().size == other.items().size &&
                left.items().zip(other.items()).all { (a, b) -> portableValuesEqual(a, b) }
        }
        is PvObject -> {
            val other = right as PvObject
            left.entries().size == other.entries().size &&
                left.entries().zip(other.entries()).all { (a, b) ->
                    a.key == b.key && portableValuesEqual(a.value, b.value)
                }
        }
        is PvEntryMapping -> {
            val other = right as PvEntryMapping
            left.entries().size == other.entries().size &&
                left.entries().zip(other.entries()).all { (a, b) ->
                    portableValuesEqual(a.key, b.key) && portableValuesEqual(a.value, b.value)
                }
        }
        else -> left == right
    }
}

/** The frozen failure-name spellings asserted by the shared vectors
 * (ini_v1.rs; materialization.rs failure kinds). */
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
