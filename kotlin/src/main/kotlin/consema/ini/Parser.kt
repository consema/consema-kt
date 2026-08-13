// The byte-exact three-profile INI scanner and recovery parser.
//
// Data authority:
//   - RFC 0009 §4-§7 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md:118-252):
//     Complete | Recovered | FatalFormationFailure; the portable grammar and
//     its deliberate restrictions; the Windows section plus key=string
//     model, `;` comments, exact single/double-quoted values, ASCII
//     case-insensitive equivalence, and ordered ambiguity facts; the Python
//     `=`/`:` delimiters, `#`/`;` comments, indentation continuation with
//     empty_lines_in_values, strict duplicates, lowercase optionxform, and
//     the exact DEFAULT section role.
//   - conformance/vectors/ini-v1.json pins the formation outcomes and the
//     diagnostic codes case by case (formation.*, resource.formation-limit-
//     matrix).
//   - consema-rs/consema-ini/src/parser.rs is the byte-arbitration authority
//     (physical-line scan parser.rs:228-301, per-line parse parser.rs:303-
//     578, continuation parser.rs:580-747, records parser.rs:749-867,
//     recovery parser.rs:869-905, pieces parser.rs:907-1125, duplicate
//     groups parser.rs:1212-1304). consema-go/go/ini/parser.go is a cross-reference
//     only.
//
// Kotlin-idiomatic design (NOT a translation): scanning works over the
// decoded UTF-8 byte buffer with an explicit scalar cursor, so spans stay
// byte-exact without host UTF-16 code-unit arithmetic; the parser is a
// single immutable-snapshot class whose mutation is confined to the parse
// call, and recovered records never acquire native semantics.

package consema.ini

import consema.document.DecodedOffset
import consema.document.DocumentAuthority
import consema.document.FormationStatus
import consema.document.LocationException
import consema.document.LosslessStructuralIndex
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.SourceLimits
import consema.document.Span
import consema.document.StructuralPiece
import consema.document.StructuralPieceKind
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/**
 * Parses a complete immutable INI snapshot under exactly one selected
 * profile (lib.rs:663-671; parser.rs:16-35). Source construction failures,
 * profile-encoding conflicts, and exceeding a configured limit are fatal and
 * throw [IniFormationException]; lexical and syntactic recovery never throws
 * and produces a Recovered document.
 */
fun parse(
    bytes: ByteArray,
    profile: IniProfile,
    encoding: IniEncodingSelection = IniEncodingSelection.ProfileDefault,
    limits: IniParseLimits = IniParseLimits.default,
): IniDocument {
    val source = try {
        IniSource.fromRaw(
            bytes,
            encodingRequest(profile, encoding),
            SourceLimits(
                maxRawBytes = limits.common.maxSourceBytes,
                maxDecodedUtf8Bytes = limits.maxDecodedUtf8Bytes,
                maxDecodedScalars = limits.maxDecodedScalars,
            ),
        )
    } catch (e: IniSourceException) {
        throw wrapSourceError(e)
    }
    validateProfileEncoding(source, profile, encoding)
    return Parser(source, profile, limits).parse()
}

/** Builds the source-encoding request of one profile/selection pair
 * (parser.rs:37-59). */
private fun encodingRequest(profile: IniProfile, selection: IniEncodingSelection): IniEncodingRequest {
    val selected = when (selection) {
        IniEncodingSelection.ProfileDefault -> IniSourceEncoding.Utf8
        is IniEncodingSelection.Explicit -> selection.encoding
    }
    // The portable profile-encoding rejection fires before any source
    // construction, matching the Rust ordering (parser.rs:55-57).
    if (profile == IniProfile.PortableV1 && selected !== IniSourceEncoding.Utf8) {
        throw IniFormationException(
            "ini.profile.encoding@1",
            "ini parse: portable profile requires UTF-8",
        )
    }
    if (selected is IniSourceEncoding.WindowsCodePage) {
        return IniEncodingRequest(
            IniSourceEncoding.Utf8,
            consema.document.BomPolicy.TreatAsContent,
            selected,
        )
    }
    return when (selection) {
        IniEncodingSelection.ProfileDefault -> IniEncodingRequest.new()
        is IniEncodingSelection.Explicit ->
            IniEncodingRequest.withCallerOverride(selected)
    }
}

/** Applies the frozen profile-encoding validation (parser.rs:61-94). */
private fun validateProfileEncoding(
    source: IniSource,
    profile: IniProfile,
    selection: IniEncodingSelection,
) {
    val facts = source.encodingFacts
    val valid = when (profile) {
        IniProfile.PortableV1 ->
            facts.selected === IniSourceEncoding.Utf8 && facts.bom == null

        IniProfile.WindowsV1 -> when (selection) {
            IniEncodingSelection.ProfileDefault ->
                (facts.selected === IniSourceEncoding.Utf16Le &&
                    facts.bom == consema.document.BomKind.Utf16Le) ||
                    (facts.selected === IniSourceEncoding.Utf8 &&
                        facts.bom == null &&
                        source.bytes().all { it.toInt() and 0xff < 0x80 })

            is IniEncodingSelection.Explicit ->
                when (val explicit = selection.encoding) {
                    IniSourceEncoding.Utf16Le ->
                        facts.selected === IniSourceEncoding.Utf16Le &&
                            facts.bom == consema.document.BomKind.Utf16Le

                    is IniSourceEncoding.WindowsCodePage ->
                        facts.selected == explicit &&
                            facts.bomPolicy == consema.document.BomPolicy.TreatAsContent &&
                            facts.bom == null

                    else -> false
                }
        }

        IniProfile.PythonConfigParserV1 ->
            // Any complete text source; Binary cannot be expressed in the
            // INI encoding vocabulary (parser.rs:87).
            true
    }
    if (!valid) {
        throw IniFormationException(
            "ini.profile.encoding@1",
            "ini parse: source encoding conflicts with the selected profile",
        )
    }
}

/** Wraps a source construction failure with the frozen code mapping of
 * FatalFormationFailure::source_error (lib.rs:676-707). */
private fun wrapSourceError(error: IniSourceException): IniFormationException =
    when (error.kind) {
        consema.document.SourceErrorKind.INVALID_UTF8 ->
            IniFormationException(
                "core.source.invalid-utf8@1",
                "ini parse: source is not valid UTF-8 at byte ${error.byteOffset}",
                cause = error,
            )

        consema.document.SourceErrorKind.INVALID_SEQUENCE ->
            IniFormationException(
                "core.source.invalid-sequence@1",
                "ini parse: invalid source sequence at byte ${error.byteOffset}",
                cause = error,
            )

        consema.document.SourceErrorKind.ENCODING_CONFLICT ->
            IniFormationException(
                "core.source.encoding-conflict@1",
                "ini parse: source encoding facts conflict",
                cause = error,
            )

        consema.document.SourceErrorKind.UNSUPPORTED_BOM ->
            IniFormationException(
                "core.source.unsupported-bom@1",
                "ini parse: unsupported byte-order mark",
                cause = error,
            )

        consema.document.SourceErrorKind.RESOURCE_LIMIT, consema.document.SourceErrorKind.OFFSET_OVERFLOW ->
            IniFormationException(
                "core.source.resource-limit@1",
                "ini parse: source construction limit reached",
                name = error.name ?: "",
                observed = error.observed,
                limit = error.limit,
                cause = error,
            )
    }

/** One scanned physical line with its decoded UTF-8 byte offsets
 * (parser.rs:106-113). */
private data class ScannedLine(
    val decodedStart: Int,
    val decodedContentEnd: Int,
    val decodedBreakStart: Int,
    val decodedEnd: Int,
    val physicalIndex: Int,
)

/** Active Python entry awaiting continuation lines (parser.rs:115-124). */
private class PythonEntryState(
    val entryIndex: Int,
    val logicalIndex: Int,
    val indent: Int,
    var continuationLines: Int,
    var logicalBytes: Int,
    var logicalScalars: Int,
    val pendingBlankLines: MutableList<Int> = ArrayList(),
)

/** The single-pass three-profile parser (parser.rs:126-147). */
private class Parser(
    private val source: IniSource,
    private val profile: IniProfile,
    private val limits: IniParseLimits,
) {
    private val utf8 = source.decodedUtf8Bytes()
    private val authority = DocumentAuthority.fresh()
    private val rootNode = authority.nodeRef(0, NodeRole.IniDocument)
    private var nextNode = 1L
    private val lines = ArrayList<ScannedLine>()
    private val physicalLines = ArrayList<IniPhysicalLine>()
    private val logicalLines = ArrayList<IniLogicalLine>()
    private val sections = ArrayList<IniSection>()
    private val entries = ArrayList<IniEntry>()
    private val entrySectionIndices = ArrayList<Int>()
    private val errorLines = ArrayList<IniErrorLine>()
    private val pieces = ArrayList<StructuralPiece>()
    private val syntaxKinds = ArrayList<IniSyntaxKind>()
    private val sink = DiagnosticSink(limits.common.maxDiagnostics)
    private var recovered = false
    private var currentSection: Int? = null
    private var pythonEntry: PythonEntryState? = null

    fun parse(): IniDocument {
        scanPhysicalLines()
        pushBom()
        for (lineIndex in lines.indices) {
            parseLine(lineIndex)
            pushLineBreak(lineIndex)
        }
        if (profile == IniProfile.PortableV1 && sections.isEmpty()) {
            val at = source.len
            diagnostic(
                "ini.parse.missing-section@1",
                DiagnosticCategory.Conformance,
                at,
                at,
                recovered = true,
            )
        }
        assignDuplicateGroups()
        val structuralIndex = try {
            LosslessStructuralIndex.new(authority.identity, source.len, pieces)
        } catch (e: LocationException) {
            throw resourceLimit("source-coordinate-coverage", 1, 0)
        }
        val diagnostics = sink.finish().sortedWith(deterministicDiagnosticOrder)
        return IniDocument(
            authority = authority,
            sourceSnapshot = source,
            profile = profile,
            structuralIndex = structuralIndex,
            syntaxKinds = syntaxKinds,
            formationStatus = if (recovered) FormationStatus.Recovered else FormationStatus.Complete,
            diagnosticsList = diagnostics,
            physicalLinesList = physicalLines,
            logicalLinesList = logicalLines,
            sectionsList = sections,
            entriesList = entries,
            errorLinesList = errorLines,
            parseLimits = limits,
            rootNode = rootNode,
        )
    }

    // ------------------------------------------------------------------
    // Physical-line scan (parser.rs:228-301)
    // ------------------------------------------------------------------

    private fun scanPhysicalLines() {
        var start = if (source.encodingFacts.bom != null &&
            utf8.size >= 3 && utf8[0] == 0xef.toByte() && utf8[1] == 0xbb.toByte() &&
            utf8[2] == 0xbf.toByte()
        ) {
            3
        } else {
            0
        }
        val decodedLines = ArrayList<ScannedLine>()
        while (start < utf8.size) {
            val newline = indexOfByte(utf8, 0x0a, start)
            val (contentEnd, breakStart, end) = if (newline >= 0) {
                val bs = if (newline > start && utf8[newline - 1] == 0x0d.toByte()) newline - 1 else newline
                Triple(bs, bs, newline + 1)
            } else {
                Triple(utf8.size, utf8.size, utf8.size)
            }
            val observed = decodedLines.size + 1
            checkLimit("physical-lines", observed, limits.maxPhysicalLines)
            decodedLines.add(
                ScannedLine(start, contentEnd, breakStart, end, 0),
            )
            start = end
        }
        for ((index, scanned) in decodedLines.withIndex()) {
            val fullSpan = rawSpan(scanned.decodedStart, scanned.decodedEnd)
            val contentSpan = rawSpan(scanned.decodedStart, scanned.decodedContentEnd)
            checkLimit("physical-line-bytes", fullSpan.len, limits.maxPhysicalLineBytes)
            checkLimit(
                "physical-line-scalars",
                countScalars(utf8, scanned.decodedStart, scanned.decodedContentEnd),
                limits.maxPhysicalLineScalars,
            )
            val node = issueNode(NodeRole.IniPhysicalLine)
            val lineBreakSpan = if (scanned.decodedBreakStart < scanned.decodedEnd) {
                rawSpan(scanned.decodedBreakStart, scanned.decodedEnd)
            } else {
                null
            }
            val physicalIndex = physicalLines.size
            physicalLines.add(
                IniPhysicalLine(node, fullSpan, contentSpan, lineBreakSpan),
            )
            lines.add(
                ScannedLine(
                    scanned.decodedStart,
                    scanned.decodedContentEnd,
                    scanned.decodedBreakStart,
                    scanned.decodedEnd,
                    physicalIndex,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Per-line dispatch (parser.rs:303-346)
    // ------------------------------------------------------------------

    private fun parseLine(lineIndex: Int) {
        val line = lines[lineIndex]
        val contentStart = line.decodedStart
        val contentEnd = line.decodedContentEnd
        if (containsByte(utf8, 0x00, contentStart, contentEnd) ||
            containsByte(utf8, 0x0d, contentStart, contentEnd)
        ) {
            recoverLine(lineIndex, "ini.parse.invalid-character@1")
            return
        }
        if (profile == IniProfile.PortableV1 &&
            !allPortableLineBytes(utf8, contentStart, contentEnd)
        ) {
            recoverLine(lineIndex, "ini.parse.invalid-character@1")
            return
        }
        if (allHorizontal(utf8, contentStart, contentEnd)) {
            if (contentStart < contentEnd) {
                pushPiece(
                    contentStart,
                    contentEnd,
                    StructuralPieceKind.Trivia,
                    IniSyntaxKind.Whitespace,
                )
            }
            if (profile == IniProfile.PythonConfigParserV1) {
                pythonEntry?.pendingBlankLines?.add(lineIndex)
            }
            return
        }
        val leading = leadingHorizontal(utf8, contentStart, contentEnd)
        val marker = if (leading < contentEnd - contentStart) {
            utf8[contentStart + leading].toInt() and 0xff
        } else {
            -1
        }
        val isComment = when (profile) {
            IniProfile.PortableV1, IniProfile.WindowsV1 -> marker == 0x3b
            IniProfile.PythonConfigParserV1 -> marker == 0x3b || marker == 0x23
        }
        if (isComment) {
            pushComment(lineIndex, leading)
            return
        }
        when (profile) {
            IniProfile.PortableV1 -> parsePortableLine(lineIndex)
            IniProfile.WindowsV1 -> parseWindowsLine(lineIndex)
            IniProfile.PythonConfigParserV1 -> parsePythonLine(lineIndex)
        }
    }

    private fun parsePortableLine(lineIndex: Int) {
        pythonEntry = null
        val line = lines[lineIndex]
        val contentStart = line.decodedStart
        val contentEnd = line.decodedContentEnd
        val length = contentEnd - contentStart
        if (utf8[contentStart] == 0x5b.toByte()) {
            if (line.decodedBreakStart == line.decodedEnd ||
                utf8[contentEnd - 1] != 0x5d.toByte() ||
                length < 3
            ) {
                recoverLine(lineIndex, "ini.parse.malformed-section@1")
                return
            }
            if (!allPortableName(utf8, contentStart + 1, contentEnd - 1)) {
                recoverLine(lineIndex, "ini.parse.invalid-character@1")
                return
            }
            pushSectionSyntax(lineIndex, 0, 1, length - 1, length)
            val name = decodedSubstring(contentStart + 1, contentEnd - 1)
            addSection(lineIndex, 1, length - 1, name, isDefault = false)
        } else {
            val delimiter = indexOfByte(utf8, 0x3d, contentStart, contentEnd)
            if (delimiter < 0) {
                recoverLine(lineIndex, "ini.parse.missing-delimiter@1")
                return
            }
            val keyStart = contentStart
            val keyEnd = delimiter
            val valueStart = delimiter + 1
            val valueEnd = contentEnd
            if (keyStart == keyEnd || !allPortableName(utf8, keyStart, keyEnd)) {
                recoverLine(lineIndex, "ini.parse.invalid-character@1")
                return
            }
            if (!allPortableValue(utf8, valueStart, valueEnd)) {
                recoverLine(lineIndex, "ini.parse.invalid-character@1")
                return
            }
            val sectionIndex = currentSection
                ?: run {
                    recoverLine(lineIndex, "ini.parse.missing-section@1")
                    return
                }
            pushEntrySyntax(
                lineIndex,
                (keyStart - contentStart) until (keyEnd - contentStart),
                (delimiter - contentStart) until (delimiter + 1 - contentStart),
                (delimiter + 1 - contentStart) until (valueEnd - contentStart),
                quote = null,
            )
            val key = decodedSubstring(keyStart, keyEnd)
            val value = decodedSubstring(valueStart, valueEnd)
            addEntry(
                lineIndex,
                sectionIndex,
                keyStart - contentStart,
                keyEnd - contentStart,
                valueStart - contentStart,
                valueEnd - contentStart,
                key,
                value,
                IniQuoteStyle.None,
            )
        }
    }

    private fun parseWindowsLine(lineIndex: Int) {
        pythonEntry = null
        val line = lines[lineIndex]
        val contentStart = line.decodedStart
        val contentEnd = line.decodedContentEnd
        val length = contentEnd - contentStart
        val (trimStart, trimEnd) = trimHorizontalBounds(utf8, contentStart, contentEnd)
        if (utf8[trimStart] == 0x5b.toByte()) {
            if (utf8[trimEnd - 1] != 0x5d.toByte() || trimEnd - trimStart < 3) {
                recoverLine(lineIndex, "ini.parse.malformed-section@1")
                return
            }
            if (!allWindowsName(utf8, trimStart + 1, trimEnd - 1)) {
                recoverLine(lineIndex, "ini.parse.invalid-character@1")
                return
            }
            pushOptionalWhitespace(lineIndex, 0, trimStart - contentStart)
            pushSectionSyntax(
                lineIndex,
                trimStart - contentStart,
                trimStart + 1 - contentStart,
                trimEnd - 1 - contentStart,
                trimEnd - contentStart,
            )
            pushOptionalWhitespace(lineIndex, trimEnd - contentStart, length)
            val name = decodedSubstring(trimStart + 1, trimEnd - 1)
            addSection(
                lineIndex,
                trimStart + 1 - contentStart,
                trimEnd - 1 - contentStart,
                name,
                isDefault = false,
            )
        } else {
            val delimiter = indexOfByte(utf8, 0x3d, trimStart, contentEnd)
            if (delimiter < 0) {
                recoverLine(lineIndex, "ini.parse.missing-delimiter@1")
                return
            }
            val (keyStart, keyEnd) =
                trimHorizontalBounds(utf8, trimStart, delimiter)
            if (keyStart == keyEnd || !allWindowsName(utf8, keyStart, keyEnd)) {
                recoverLine(lineIndex, "ini.parse.invalid-character@1")
                return
            }
            val sectionIndex = currentSection
                ?: run {
                    recoverLine(lineIndex, "ini.parse.missing-section@1")
                    return
                }
            val literalStart = delimiter + 1
            val literalEnd = contentEnd
            val (valueStart, valueEnd, quoteStyle) =
                quotedWindowsValue(utf8, literalStart, literalEnd)
            pushOptionalWhitespace(lineIndex, 0, keyStart - contentStart)
            pushPieceLocal(lineIndex, keyStart - contentStart, keyEnd - contentStart, StructuralPieceKind.Token, IniSyntaxKind.EntryKey)
            pushOptionalWhitespace(lineIndex, keyEnd - contentStart, delimiter - contentStart)
            pushPieceLocal(lineIndex, delimiter - contentStart, delimiter + 1 - contentStart, StructuralPieceKind.Token, IniSyntaxKind.Delimiter)
            pushWindowsValueSyntax(lineIndex, literalStart - contentStart, literalEnd - contentStart, valueStart - contentStart, valueEnd - contentStart, quoteStyle)
            val key = decodedSubstring(keyStart, keyEnd)
            val value = decodedSubstring(valueStart, valueEnd)
            addEntry(
                lineIndex,
                sectionIndex,
                keyStart - contentStart,
                keyEnd - contentStart,
                valueStart - contentStart,
                valueEnd - contentStart,
                key,
                value,
                quoteStyle,
            )
        }
    }

    private fun parsePythonLine(lineIndex: Int) {
        val line = lines[lineIndex]
        val contentStart = line.decodedStart
        val contentEnd = line.decodedContentEnd
        val length = contentEnd - contentStart
        val indent = leadingHorizontal(utf8, contentStart, contentEnd)
        val state = pythonEntry
        if (state != null && indent > state.indent) {
            addPythonContinuation(lineIndex, indent)
            return
        }
        state?.pendingBlankLines?.clear()
        pythonEntry = null
        val (trimStart, trimEnd) = trimHorizontalBounds(utf8, contentStart, contentEnd)
        if (utf8[trimStart] == 0x5b.toByte()) {
            if (utf8[trimEnd - 1] != 0x5d.toByte() || trimEnd - trimStart < 3) {
                recoverLine(lineIndex, "ini.parse.malformed-section@1")
                return
            }
            pushOptionalWhitespace(lineIndex, 0, trimStart - contentStart)
            pushSectionSyntax(
                lineIndex,
                trimStart - contentStart,
                trimStart + 1 - contentStart,
                trimEnd - 1 - contentStart,
                trimEnd - contentStart,
            )
            pushOptionalWhitespace(lineIndex, trimEnd - contentStart, length)
            val name = decodedSubstring(trimStart + 1, trimEnd - 1)
            addSection(
                lineIndex,
                trimStart + 1 - contentStart,
                trimEnd - 1 - contentStart,
                name,
                isDefault = name == "DEFAULT",
            )
            return
        }
        val delimiter = firstPythonDelimiter(utf8, trimStart, contentEnd)
        if (delimiter < 0) {
            val code = if (indent > 0) {
                "ini.parse.invalid-continuation@1"
            } else {
                "ini.parse.missing-delimiter@1"
            }
            recoverLine(lineIndex, code)
            return
        }
        val (keyStart, keyEnd) =
            trimHorizontalBounds(utf8, trimStart, delimiter)
        if (keyStart == keyEnd) {
            recoverLine(lineIndex, "ini.parse.malformed-line@1")
            return
        }
        val sectionIndex = currentSection
            ?: run {
                recoverLine(lineIndex, "ini.parse.missing-section@1")
                return
            }
        val (valueStart, valueEnd) =
            trimHorizontalBounds(utf8, delimiter + 1, contentEnd)
        pushOptionalWhitespace(lineIndex, 0, keyStart - contentStart)
        pushPieceLocal(lineIndex, keyStart - contentStart, keyEnd - contentStart, StructuralPieceKind.Token, IniSyntaxKind.EntryKey)
        pushOptionalWhitespace(lineIndex, keyEnd - contentStart, delimiter - contentStart)
        pushPieceLocal(lineIndex, delimiter - contentStart, delimiter + 1 - contentStart, StructuralPieceKind.Token, IniSyntaxKind.Delimiter)
        pushOptionalWhitespace(lineIndex, delimiter + 1 - contentStart, valueStart - contentStart)
        if (valueStart < valueEnd) {
            pushPieceLocal(lineIndex, valueStart - contentStart, valueEnd - contentStart, StructuralPieceKind.Token, IniSyntaxKind.EntryValue)
        }
        pushOptionalWhitespace(lineIndex, valueEnd - contentStart, length)
        val key = decodedSubstring(keyStart, keyEnd)
        val value = decodedSubstring(valueStart, valueEnd)
        val entryIndex = addEntry(
            lineIndex,
            sectionIndex,
            keyStart - contentStart,
            keyEnd - contentStart,
            valueStart - contentStart,
            valueEnd - contentStart,
            key,
            value,
            IniQuoteStyle.None,
        )
        val logicalNode = entries[entryIndex].logicalLine
        val logicalIndex = logicalLines.indexOfFirst { it.nodeRef == logicalNode }
        val physical = physicalLines[line.physicalIndex]
        pythonEntry = PythonEntryState(
            entryIndex = entryIndex,
            logicalIndex = logicalIndex,
            indent = indent,
            continuationLines = 0,
            logicalBytes = physical.span.len,
            logicalScalars = countScalars(utf8, contentStart, contentEnd),
        )
    }

    // ------------------------------------------------------------------
    // Python continuation (parser.rs:580-747)
    // ------------------------------------------------------------------

    private fun addPythonContinuation(lineIndex: Int, indent: Int) {
        val line = lines[lineIndex]
        val contentStart = line.decodedStart
        val contentEnd = line.decodedContentEnd
        val (valueStart, valueEnd) = trimHorizontalBounds(utf8, contentStart + indent, contentEnd)
        val state = pythonEntry!!
        pythonEntry = null
        val pending = state.pendingBlankLines
        val addedLines = checkedAddChecked(pending.size + 1, "continuation-lines", limits.maxContinuationLines)
        val continuationLines = checkedAddChecked(state.continuationLines + addedLines, "continuation-lines", limits.maxContinuationLines)

        var pendingBytes = 0
        var pendingScalars = 0
        for (pendingIndex in pending) {
            val pendingLine = lines[pendingIndex]
            val physical = physicalLines[pendingLine.physicalIndex]
            pendingBytes = checkedAddChecked(pendingBytes + physical.span.len, "logical-line-bytes", limits.maxLogicalLineBytes)
            pendingScalars = checkedAddChecked(
                pendingScalars + countScalars(utf8, pendingLine.decodedStart, pendingLine.decodedContentEnd),
                "logical-line-scalars",
                limits.maxLogicalLineScalars,
            )
        }
        val physical = physicalLines[line.physicalIndex]
        val logicalBytes = checkedAddChecked(
            state.logicalBytes + pendingBytes + physical.span.len,
            "logical-line-bytes",
            limits.maxLogicalLineBytes,
        )
        val logicalScalars = checkedAddChecked(
            state.logicalScalars + pendingScalars + countScalars(utf8, contentStart, contentEnd),
            "logical-line-scalars",
            limits.maxLogicalLineScalars,
        )
        val fragment = decodedSubstring(valueStart, valueEnd)
        val entry = entries[state.entryIndex]
        // The Rust value.len() counts UTF-8 bytes (parser.rs:680-696);
        // Kotlin String.length counts UTF-16 units, so the byte size is
        // measured explicitly.
        val valueStorageBytes = checkedAddChecked(
            utf8ByteLength(entry.value) + addedLines + utf8ByteLength(fragment),
            "logical-value-storage-bytes",
            limits.maxDecodedUtf8Bytes,
        )
        val joined = StringBuilder(valueStorageBytes)
        joined.append(entry.value)
        for (pendingIndex in pending) {
            val pendingLine = lines[pendingIndex]
            val pendingLogical = logicalLines[state.logicalIndex]
            logicalLines[state.logicalIndex] = pendingLogical.copy(
                physicalLines = pendingLogical.physicalLines +
                    physicalLines[pendingLine.physicalIndex].nodeRef,
            )
            joined.append('\n')
        }
        val mergedLogical = logicalLines[state.logicalIndex]
        logicalLines[state.logicalIndex] = mergedLogical.copy(
            physicalLines = mergedLogical.physicalLines + physical.nodeRef,
        )
        joined.append('\n')
        joined.append(fragment)
        entries[state.entryIndex] = entries[state.entryIndex].copy(
            value = joined.toString(),
            valueState = if (joined.isEmpty()) IniValueState.Empty else IniValueState.Present,
        )
        pushPieceLocal(lineIndex, 0, indent, StructuralPieceKind.Trivia, IniSyntaxKind.ContinuationMarker)
        if (valueStart < valueEnd) {
            pushPieceLocal(lineIndex, valueStart - contentStart, valueEnd - contentStart, StructuralPieceKind.Token, IniSyntaxKind.EntryValue)
        }
        pushOptionalWhitespace(lineIndex, valueEnd - contentStart, contentEnd - contentStart)
        state.continuationLines = continuationLines
        state.logicalBytes = logicalBytes
        state.logicalScalars = logicalScalars
        pending.clear()
        pythonEntry = state
    }

    /** Checked add with an immediate limit failure (parser.rs:594-677). */
    private fun checkedAddChecked(value: Int, name: String, limit: Int): Int {
        if (value > limit) {
            throw resourceLimit(name, value, limit)
        }
        return value
    }

    // ------------------------------------------------------------------
    // Records (parser.rs:749-905)
    // ------------------------------------------------------------------

    private fun addSection(
        lineIndex: Int,
        nameStart: Int,
        nameEnd: Int,
        name: String,
        isDefault: Boolean,
    ) {
        checkLimit("sections", sections.size + 1, limits.maxSections)
        val line = lines[lineIndex]
        val logicalIndex = addLogical(lineIndex, IniLogicalLineKind.Section)
        val role = if (isDefault) NodeRole.IniDefaultSection else NodeRole.IniSection
        val node = issueNode(role)
        val section = IniSection(
            nodeRef = node,
            logicalLine = logicalLines[logicalIndex].nodeRef,
            span = physicalLines[line.physicalIndex].contentSpan,
            nameSpan = rawSpan(line.decodedStart + nameStart, line.decodedStart + nameEnd),
            name = name,
            comparisonName = sectionComparison(name),
            isDefault = isDefault,
            duplicateGroup = null,
        )
        sections.add(section)
        currentSection = sections.size - 1
        pythonEntry = null
    }

    private fun addEntry(
        lineIndex: Int,
        sectionIndex: Int,
        keyStart: Int,
        keyEnd: Int,
        valueStart: Int,
        valueEnd: Int,
        key: String,
        value: String,
        quoteStyle: IniQuoteStyle,
    ): Int {
        checkLimit("entries", entries.size + 1, limits.maxEntries)
        val line = lines[lineIndex]
        val logicalIndex = addLogical(lineIndex, IniLogicalLineKind.Entry)
        val node = issueNode(NodeRole.IniEntry)
        val state = if (value.isEmpty()) IniValueState.Empty else IniValueState.Present
        val entry = IniEntry(
            nodeRef = node,
            logicalLine = logicalLines[logicalIndex].nodeRef,
            section = sections[sectionIndex].nodeRef,
            span = physicalLines[line.physicalIndex].contentSpan,
            keySpan = rawSpan(line.decodedStart + keyStart, line.decodedStart + keyEnd),
            valueSpan = rawSpan(line.decodedStart + valueStart, line.decodedStart + valueEnd),
            key = key,
            comparisonKey = keyComparison(key),
            value = value,
            valueState = state,
            quoteStyle = quoteStyle,
            duplicateGroup = null,
        )
        val entryIndex = entries.size
        entries.add(entry)
        entrySectionIndices.add(sectionIndex)
        return entryIndex
    }

    private fun addLogical(lineIndex: Int, kind: IniLogicalLineKind): Int {
        checkLimit("logical-lines", logicalLines.size + 1, limits.maxLogicalLines)
        val line = lines[lineIndex]
        val physical = physicalLines[line.physicalIndex]
        checkLimit("logical-line-bytes", physical.span.len, limits.maxLogicalLineBytes)
        checkLimit(
            "logical-line-scalars",
            countScalars(utf8, line.decodedStart, line.decodedContentEnd),
            limits.maxLogicalLineScalars,
        )
        val node = issueNode(NodeRole.IniLogicalLine)
        val index = logicalLines.size
        logicalLines.add(IniLogicalLine(node, kind, listOf(physical.nodeRef)))
        return index
    }

    private fun recoverLine(lineIndex: Int, code: String) {
        checkLimit("recovery-regions", errorLines.size + 1, limits.maxRecoveryRegions)
        pythonEntry = null
        val line = lines[lineIndex]
        if (line.decodedStart < line.decodedContentEnd) {
            pushPiece(
                line.decodedStart,
                line.decodedContentEnd,
                StructuralPieceKind.ErrorRegion,
                IniSyntaxKind.ErrorRegion,
            )
        }
        val logicalIndex = addLogical(lineIndex, IniLogicalLineKind.Error)
        val node = issueNode(NodeRole.IniErrorLine)
        val physical = physicalLines[line.physicalIndex]
        errorLines.add(
            IniErrorLine(
                nodeRef = node,
                logicalLine = logicalLines[logicalIndex].nodeRef,
                physicalLine = physical.nodeRef,
                span = physical.contentSpan,
                code = code,
            ),
        )
        diagnostic(
            code,
            // `ini.parse.missing-section@1` is registered Conformance; every
            // other recovery code is Syntax (ErrorRegistry.kt:339-344).
            if (code == "ini.parse.missing-section@1") {
                DiagnosticCategory.Conformance
            } else {
                DiagnosticCategory.Syntax
            },
            physical.contentSpan.startByte,
            physical.contentSpan.endByte,
            recovered = true,
        )
    }

    // ------------------------------------------------------------------
    // Syntax pieces (parser.rs:907-1125)
    // ------------------------------------------------------------------

    private fun pushBom() {
        if (source.encodingFacts.bom != null && utf8.size >= 3 &&
            utf8[0] == 0xef.toByte() && utf8[1] == 0xbb.toByte() && utf8[2] == 0xbf.toByte()
        ) {
            pushPiece(0, 3, StructuralPieceKind.Trivia, IniSyntaxKind.Bom)
        }
    }

    private fun pushComment(lineIndex: Int, leading: Int) {
        val line = lines[lineIndex]
        val length = line.decodedContentEnd - line.decodedStart
        pushOptionalWhitespace(lineIndex, 0, leading)
        pushPieceLocal(
            lineIndex,
            leading,
            leading + 1,
            StructuralPieceKind.Trivia,
            IniSyntaxKind.CommentMarker,
        )
        if (leading + 1 < length) {
            pushPieceLocal(
                lineIndex,
                leading + 1,
                length,
                StructuralPieceKind.Trivia,
                IniSyntaxKind.CommentText,
            )
        }
    }

    private fun pushSectionSyntax(
        lineIndex: Int,
        open: Int,
        nameStart: Int,
        nameEnd: Int,
        closeEnd: Int,
    ) {
        pushPieceLocal(lineIndex, open, nameStart, StructuralPieceKind.Token, IniSyntaxKind.SectionOpen)
        pushPieceLocal(lineIndex, nameStart, nameEnd, StructuralPieceKind.Token, IniSyntaxKind.SectionName)
        pushPieceLocal(lineIndex, nameEnd, closeEnd, StructuralPieceKind.Token, IniSyntaxKind.SectionClose)
    }

    private fun pushEntrySyntax(
        lineIndex: Int,
        key: IntRange,
        delimiter: IntRange,
        value: IntRange,
        quote: Pair<IntRange, IntRange>?,
    ) {
        // The ranges are exclusive (`a until b`); pushPieceLocal skips the
        // empty ranges used as no-op markers.
        pushPieceLocal(lineIndex, key.first, key.last + 1, StructuralPieceKind.Token, IniSyntaxKind.EntryKey)
        pushPieceLocal(lineIndex, delimiter.first, delimiter.last + 1, StructuralPieceKind.Token, IniSyntaxKind.Delimiter)
        if (quote != null) {
            pushPieceLocal(lineIndex, quote.first.first, quote.first.last + 1, StructuralPieceKind.Token, IniSyntaxKind.Quote)
            pushPieceLocal(lineIndex, value.first, value.last + 1, StructuralPieceKind.Token, IniSyntaxKind.EntryValue)
            pushPieceLocal(lineIndex, quote.second.first, quote.second.last + 1, StructuralPieceKind.Token, IniSyntaxKind.Quote)
        } else {
            pushPieceLocal(lineIndex, value.first, value.last + 1, StructuralPieceKind.Token, IniSyntaxKind.EntryValue)
        }
    }

    private fun pushWindowsValueSyntax(
        lineIndex: Int,
        literalStart: Int,
        literalEnd: Int,
        valueStart: Int,
        valueEnd: Int,
        quoteStyle: IniQuoteStyle,
    ) {
        if (quoteStyle == IniQuoteStyle.None) {
            pushEntrySyntax(
                lineIndex,
                0 until 0,
                0 until 0,
                literalStart until literalEnd,
                quote = null,
            )
            return
        }
        pushEntrySyntax(
            lineIndex,
            0 until 0,
            0 until 0,
            valueStart until valueEnd,
            quote = Pair(
                literalStart until valueStart,
                valueEnd until literalEnd,
            ),
        )
    }

    private fun pushLineBreak(lineIndex: Int) {
        val line = lines[lineIndex]
        if (line.decodedBreakStart < line.decodedEnd) {
            pushPiece(
                line.decodedBreakStart,
                line.decodedEnd,
                StructuralPieceKind.Trivia,
                IniSyntaxKind.LineBreak,
            )
        }
    }

    private fun pushOptionalWhitespace(lineIndex: Int, start: Int, end: Int) {
        if (start < end) {
            pushPieceLocal(lineIndex, start, end, StructuralPieceKind.Trivia, IniSyntaxKind.Whitespace)
        }
    }

    private fun pushPieceLocal(
        lineIndex: Int,
        start: Int,
        end: Int,
        kind: StructuralPieceKind,
        syntax: IniSyntaxKind,
    ) {
        if (start >= end) {
            return
        }
        val line = lines[lineIndex]
        pushPiece(line.decodedStart + start, line.decodedStart + end, kind, syntax)
    }

    private fun pushPiece(
        decodedStart: Int,
        decodedEnd: Int,
        kind: StructuralPieceKind,
        syntax: IniSyntaxKind,
    ) {
        val observed = pieces.size + 1
        checkLimit("syntax-pieces", observed, limits.common.maxTokenCount)
        val span = rawSpan(decodedStart, decodedEnd)
        if (span.isEmpty) {
            throw resourceLimit("source-coordinate-coverage", 1, 0)
        }
        pieces.add(StructuralPiece(span, kind))
        syntaxKinds.add(syntax)
    }

    private fun rawSpan(decodedStart: Int, decodedEnd: Int): Span {
        val start = try {
            source.rawByteAt(DecodedOffset.Utf8Byte(decodedStart))
        } catch (e: LocationException) {
            throw resourceLimit("source-coordinate-boundary", 1, 0)
        }
        val end = try {
            source.rawByteAt(DecodedOffset.Utf8Byte(decodedEnd))
        } catch (e: LocationException) {
            throw resourceLimit("source-coordinate-boundary", 1, 0)
        }
        return try {
            authority.span(start, end)
        } catch (e: LocationException) {
            throw resourceLimit("source-coordinate-boundary", 1, 0)
        }
    }

    /** Decoded scalar text of one decoded UTF-8 byte range. */
    private fun decodedSubstring(start: Int, end: Int): String =
        String(utf8, start, end - start, Charsets.UTF_8)

    // ------------------------------------------------------------------
    // Node identity and diagnostics (parser.rs:1132-1195)
    // ------------------------------------------------------------------

    private fun issueNode(role: NodeRole): NodeRef {
        val observed = nextNode + 1
        checkLimit("nodes", observed.toInt(), limits.common.maxNodeCount)
        val node = authority.nodeRef(nextNode, role)
        nextNode = checkedAddLong(nextNode, 1)
        return node
    }

    private fun checkedAddLong(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) {
            throw resourceLimit("node-identity", Int.MAX_VALUE, Int.MAX_VALUE - 1)
        } else {
            left + right
        }

    private fun checkLimit(name: String, observed: Int, limit: Int) {
        if (observed > limit) {
            throw resourceLimit(name, observed, limit)
        }
    }

    private fun diagnostic(
        code: String,
        category: DiagnosticCategory,
        start: Int,
        end: Int,
        recovered: Boolean,
    ) {
        sink.push(
            sourceDiagnostic(
                authority,
                code,
                category,
                if (recovered) Severity.Error else Severity.Warning,
                start,
                end,
                sink.nextOccurrence(),
            ),
        )
        this.recovered = this.recovered || recovered
    }

    // ------------------------------------------------------------------
    // Profile comparison and duplicate groups (parser.rs:1197-1304)
    // ------------------------------------------------------------------

    private fun sectionComparison(name: String): String =
        when (profile) {
            IniProfile.WindowsV1 -> asciiLowercase(name)
            IniProfile.PortableV1, IniProfile.PythonConfigParserV1 -> name
        }

    private fun keyComparison(key: String): String =
        when (profile) {
            IniProfile.PortableV1 -> key
            IniProfile.WindowsV1 -> asciiLowercase(key)
            IniProfile.PythonConfigParserV1 -> optionxform(key)
        }

    private fun assignDuplicateGroups() {
        var nextGroup = 1

        val sectionGroups = sortedMapOf<String, MutableList<Int>>()
        for ((index, section) in sections.withIndex()) {
            sectionGroups.getOrPut(section.comparisonName) { ArrayList() }.add(index)
        }
        for (indices in sectionGroups.values) {
            if (indices.size <= 1) continue
            checkLimit("duplicate-group-members", indices.size, limits.maxDuplicateGroupMembers)
            val group = nextGroup
            nextGroup = nextGroup.inc()
            val first = sections[indices[0]]
            for (index in indices) {
                sections[index] = sections[index].copy(duplicateGroup = group)
            }
            for (index in indices.drop(1)) {
                val span = sections[index].span
                val code = if (sections[index].name == first.name) {
                    "ini.formation.duplicate-section@1"
                } else {
                    "ini.formation.case-collision@1"
                }
                diagnostic(
                    code,
                    DiagnosticCategory.Semantic,
                    span.startByte,
                    span.endByte,
                    recovered = profile != IniProfile.WindowsV1,
                )
            }
        }

        val entryGroups = java.util.TreeMap<Pair<String, String>, MutableList<Int>>(
            compareBy<Pair<String, String>> { it.first }.thenBy { it.second },
        )
        for ((index, entry) in entries.withIndex()) {
            val sectionIndex = entrySectionIndices[index]
            val sectionIdentity = if (profile == IniProfile.WindowsV1) {
                sections[sectionIndex].comparisonName
            } else {
                sectionIndex.toString()
            }
            entryGroups.getOrPut(sectionIdentity to entry.comparisonKey) { ArrayList() }.add(index)
        }
        for (indices in entryGroups.values) {
            if (indices.size <= 1) continue
            checkLimit("duplicate-group-members", indices.size, limits.maxDuplicateGroupMembers)
            val group = nextGroup
            nextGroup = nextGroup.inc()
            val first = entries[indices[0]]
            for (index in indices) {
                entries[index] = entries[index].copy(duplicateGroup = group)
            }
            for (index in indices.drop(1)) {
                val span = entries[index].span
                val code = if (entries[index].key == first.key) {
                    "ini.formation.duplicate-entry@1"
                } else {
                    "ini.formation.case-collision@1"
                }
                diagnostic(
                    code,
                    DiagnosticCategory.Semantic,
                    span.startByte,
                    span.endByte,
                    recovered = profile != IniProfile.WindowsV1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Byte helpers over the decoded UTF-8 buffer (parser.rs:1307-1362)
// ---------------------------------------------------------------------------

private fun isHorizontal(byte: Byte): Boolean = byte == 0x20.toByte() || byte == 0x09.toByte()

private fun allHorizontal(bytes: ByteArray, start: Int, end: Int): Boolean {
    for (index in start until end) {
        if (!isHorizontal(bytes[index])) return false
    }
    return true
}

private fun leadingHorizontal(bytes: ByteArray, start: Int, end: Int): Int {
    var index = start
    while (index < end && isHorizontal(bytes[index])) {
        index += 1
    }
    return index - start
}

private fun trimHorizontalBounds(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int> {
    val leading = leadingHorizontal(bytes, start, end)
    var trailing = end - 1
    while (trailing >= start && isHorizontal(bytes[trailing])) {
        trailing -= 1
    }
    // All-horizontal content trims to the empty range at the slice end,
    // matching the Rust rposition fallback (parser.rs:1314-1321).
    val trimmedEnd = if (trailing < start) end else trailing + 1
    val s = start + leading
    return (s.coerceAtMost(trimmedEnd)) to trimmedEnd
}

private fun indexOfByte(bytes: ByteArray, target: Int, start: Int, end: Int): Int {
    for (index in start until end) {
        if ((bytes[index].toInt() and 0xff) == target) return index
    }
    return -1
}

private fun indexOfByte(bytes: ByteArray, target: Int, start: Int): Int =
    indexOfByte(bytes, target, start, bytes.size)

private fun containsByte(bytes: ByteArray, target: Int, start: Int, end: Int): Boolean =
    indexOfByte(bytes, target, start, end) >= 0

/** UTF-8 byte length of one decoded string (the Rust str::len). */
internal fun utf8ByteLength(value: String): Int = value.toByteArray(Charsets.UTF_8).size

private fun countScalars(bytes: ByteArray, start: Int, end: Int): Int {
    var count = 0
    var index = start
    while (index < end) {
        val first = bytes[index].toInt() and 0xff
        index += when {
            first < 0x80 -> 1
            first in 0xc2..0xdf -> 2
            first in 0xe0..0xef -> 3
            else -> 4
        }
        count += 1
    }
    return count
}

private fun isPortableName(byte: Byte): Boolean {
    val value = byte.toInt() and 0xff
    return (value in 0x30..0x39) || (value in 0x41..0x5a) || (value in 0x61..0x7a) ||
        value == 0x5f || value == 0x2d || value == 0x2e
}

private fun allPortableName(bytes: ByteArray, start: Int, end: Int): Boolean {
    for (index in start until end) {
        if (!isPortableName(bytes[index])) return false
    }
    return true
}

private fun isPortableValue(byte: Byte): Boolean {
    val value = byte.toInt() and 0xff
    return (value in 0x21..0x7e && value !in setOf(0x27, 0x22, 0x5c, 0x3a, 0x23, 0x3b)) ||
        value == 0x20
}

private fun allPortableValue(bytes: ByteArray, start: Int, end: Int): Boolean {
    for (index in start until end) {
        if (!isPortableValue(bytes[index])) return false
    }
    return true
}

private fun allPortableLineBytes(bytes: ByteArray, start: Int, end: Int): Boolean {
    for (index in start until end) {
        val value = bytes[index].toInt() and 0xff
        if (value != 0x09 && value !in 0x20..0x7e) return false
    }
    return true
}

private fun isWindowsName(byte: Byte): Boolean {
    val value = byte.toInt() and 0xff
    return (value in 0x21..0x7e || value == 0x20) && value !in setOf(0x5b, 0x5d, 0x3d, 0x00, 0x0d, 0x0a)
}

private fun allWindowsName(bytes: ByteArray, start: Int, end: Int): Boolean {
    for (index in start until end) {
        if (!isWindowsName(bytes[index])) return false
    }
    return true
}

/** The Windows profile's exact single/double-quoted value rule
 * (parser.rs:1341-1358). */
private fun quotedWindowsValue(
    bytes: ByteArray,
    literalStart: Int,
    literalEnd: Int,
): Triple<Int, Int, IniQuoteStyle> {
    if (literalEnd - literalStart >= 2) {
        val first = bytes[literalStart].toInt() and 0xff
        val last = bytes[literalEnd - 1].toInt() and 0xff
        val style = when {
            first == 0x27 && last == 0x27 -> IniQuoteStyle.Single
            first == 0x22 && last == 0x22 -> IniQuoteStyle.Double
            else -> IniQuoteStyle.None
        }
        if (style != IniQuoteStyle.None) {
            return Triple(literalStart + 1, literalEnd - 1, style)
        }
    }
    return Triple(literalStart, literalEnd, IniQuoteStyle.None)
}

/** The first `=` or `:` byte of the trimmed Python option line
 * (parser.rs:1360-1362). */
private fun firstPythonDelimiter(bytes: ByteArray, start: Int, end: Int): Int {
    for (index in start until end) {
        val value = bytes[index].toInt() and 0xff
        if (value == 0x3d || value == 0x3a) return index
    }
    return -1
}

/** ASCII-only case folding (the Windows profile never folds non-ASCII;
 * parser.rs:1197-1210). */
internal fun asciiLowercase(value: String): String {
    val output = CharArray(value.length)
    for ((index, character) in value.withIndex()) {
        val code = character.code
        output[index] = if (code in 0x41..0x5a) (code + 32).toChar() else character
    }
    return String(output)
}
