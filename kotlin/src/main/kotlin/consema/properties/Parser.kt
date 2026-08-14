// The byte-exact Java Properties Reader/Latin-1 scanner and recovery parser.
//
// Data authority:
//   - RFC 0010 §5-§8 (https://github.com/consema/consema/blob/main/docs/rfcs/0010-java-properties-profiles-v1.md:132-235):
//     natural/logical lines; continuation by an odd run of terminal
//     backslashes with the JDK end-of-source rule; the key/separator/element
//     grammar (leading whitespace, unescaped `=`/`:`/whitespace terminates
//     the raw key, optional separator, exact left-to-right escape
//     processing); malformed Unicode escapes form deterministic recovery
//     records and never publish a partial property.
//   - conformance/vectors/java-properties-v1.json pins the per-case
//     formations, counts, hex values, statuses, and codes.
//   - https://github.com/consema/consema-rs/blob/main/consema-properties/src/parser.rs is the byte-arbitration
//     authority (atoms parser.rs:93-99, natural-line scan parser.rs:230-298,
//     logical-line assembly parser.rs:352-469, key/split parser.rs:471-507,
//     escape decoding parser.rs:909-996, recovery parser.rs:626-666,
//     duplicate groups parser.rs:668-696, coverage parser.rs:698-729).
//     consema-go/go/properties/parser.go is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the parser works over
// immutable atom records (decoded scalar + exact raw span) with a mutable
// syntax classification that the piece builder collapses into the lossless
// coverage index. Kotlin Strings iterate by UTF-16 code units, so the atom
// carries the Unicode SCALAR (a Rust char analogue) and the decode step
// re-expands a supplementary scalar to its two UTF-16 code units; entity
// indices are Int, node ordinals are Long (the Rust u64 index), and every
// fatal limit failure throws the typed [PropertiesFormationException]
// carrying the frozen code and limit name.

package consema.properties

import consema.document.DocumentAuthority
import consema.document.FormationStatus
import consema.document.LosslessStructuralIndex
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.SourceSnapshot
import consema.document.Span
import consema.document.StructuralPiece
import consema.document.StructuralPieceKind
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/**
 * Parses one immutable Java Properties snapshot under one exact
 * profile/source contract (parser.rs:17-36). Exceeding a configured limit
 * or failing source construction is fatal and throws
 * [PropertiesFormationException]; malformed Unicode escapes recover as
 * deterministic error lines and produce a Recovered document.
 */
fun parse(
    bytes: ByteArray,
    profile: PropertiesProfile,
    encoding: PropertiesEncoding,
    limits: PropertiesParseLimits = PropertiesParseLimits.default,
): Document {
    val (source, text) = buildPropertiesSource(bytes, profile, encoding, limits)
    val authority = DocumentAuthority.fresh()
    return Parser(source, text, encoding, profile, limits, authority).parse()
}

/** Parses Reader input using one explicit published text encoding
 * (lib.rs:788-799). */
fun parseReader(
    bytes: ByteArray,
    encoding: consema.document.SourceEncoding,
    limits: PropertiesParseLimits = PropertiesParseLimits.default,
): Document =
    parse(bytes, PropertiesProfile.ReaderV1, PropertiesEncoding.Reader(encoding), limits)

/** Parses InputStream-compatible Latin-1 bytes with marker bytes as content
 * (lib.rs:801-812). */
fun parseLatin1(
    bytes: ByteArray,
    limits: PropertiesParseLimits = PropertiesParseLimits.default,
): Document =
    parse(bytes, PropertiesProfile.Latin1V1, PropertiesEncoding.Latin1, limits)

/** One decoded scalar with its exact raw span and mutable syntax class
 * (parser.rs:93-99). The scalar is a Unicode code point (the Rust char
 * analogue), so a supplementary scalar occupies one atom and two UTF-16
 * code units. */
internal data class Atom(
    val ch: Int,
    val rawStart: Int,
    val rawEnd: Int,
    var syntax: PropertiesSyntaxKind? = null,
)

/** One scanned natural line over atom coordinates (parser.rs:101-107). */
internal data class ScannedLine(
    val atomStart: Int,
    val atomContentEnd: Int,
    val atomEnd: Int,
    val naturalIndex: Int,
)

/** One escape occurrence inside a decoded key/value (parser.rs:109-115). */
internal data class EscapeSpec(
    val atomIndices: List<Int>,
    val kind: PropertiesEscapeKind,
    val outputStart: Int,
    val outputEnd: Int,
)

/** One decoded Java string and its escape facts (parser.rs:117-122). */
internal data class DecodedJavaString(
    val units: CharArray,
    val escapes: List<EscapeSpec>,
    val unicodeEscapes: Int,
)

/** One malformed-escape recovery record (parser.rs:124-128). */
internal data class DecodeError(val atomStart: Int, val atomEnd: Int)

/** The closed decode outcome of one key/value range. */
internal sealed class JavaStringDecode {
    /** Complete exact code units and escape facts. */
    data class Complete(val string: DecodedJavaString) : JavaStringDecode()

    /** One malformed Unicode escape with its exact atom range. */
    data class Malformed(val error: DecodeError) : JavaStringDecode()
}

private class Parser(
    private val source: SourceSnapshot,
    private val text: PropertiesText,
    private val sourceEncoding: PropertiesEncoding,
    private val profile: PropertiesProfile,
    private val limits: PropertiesParseLimits,
    private val authority: DocumentAuthority,
) {
    private val rootNode: NodeRef = authority.nodeRef(0, NodeRole.PropertiesDocument)
    private var nextNode: Long = 1
    private val atoms = ArrayList<Atom>()
    private val lines = ArrayList<ScannedLine>()
    private val naturalLineEntities = ArrayList<NaturalLineEntity>()
    private val logicalLineEntities = ArrayList<LogicalLineEntity>()
    private val propertyEntities = ArrayList<PropertyEntity>()
    private val commentEntities = ArrayList<CommentEntity>()
    private val escapeEntities = ArrayList<EscapeEntity>()
    private val errorLineEntities = ArrayList<ErrorLineEntity>()
    private val diagnostics = ArrayList<Diagnostic>()
    private var occurrence: ULong = 0uL
    private var recovered = false
    private var totalJavaUnits = 0
    private var totalUnicodeEscapes = 0

    fun parse(): Document {
        buildAtoms()
        scanNaturalLines()
        var lineIndex = 0
        while (lineIndex < lines.size) {
            if (isBlank(lineIndex)) {
                markLineContent(lineIndex, PropertiesSyntaxKind.Whitespace)
                lineIndex += 1
            } else if (isComment(lineIndex)) {
                addComment(lineIndex)
                lineIndex += 1
            } else {
                lineIndex = addLogicalLine(lineIndex)
            }
        }
        assignDuplicateGroups()
        val (pieces, syntaxKinds) = buildStructuralPieces()
        val structuralIndex = try {
            LosslessStructuralIndex.new(authority.identity, source.len, pieces)
        } catch (e: consema.document.LocationException) {
            throw propertiesResourceLimit("source-coordinate-coverage", 1, 0)
        }
        diagnostics.sortWith(deterministicDiagnosticOrder)
        return Document(
            authority = authority,
            source = source,
            sourceEncoding = sourceEncoding,
            profile = profile,
            text = text,
            structuralIndex = structuralIndex,
            syntaxKindList = syntaxKinds,
            formationStatus = if (recovered) FormationStatus.Recovered else FormationStatus.Complete,
            diagnosticsList = diagnostics,
            naturalLineEntities = naturalLineEntities,
            logicalLineEntities = logicalLineEntities,
            propertyEntities = propertyEntities,
            commentEntities = commentEntities,
            escapeEntities = escapeEntities,
            errorLineEntities = errorLineEntities,
            parseLimits = limits,
            rootNode = rootNode,
        )
    }

    // ------------------------------------------------------------------
    // Atom and natural-line scanning
    // ------------------------------------------------------------------

    /** Builds one atom per decoded scalar with its exact raw span
     * (parser.rs:882-907). Kotlin Strings iterate by UTF-16 code unit, so
     * the scan advances by code point. */
    private fun buildAtoms() {
        val decoded = text.text
        var utf8Cursor = 0
        var index = 0
        while (index < decoded.length) {
            val scalar = decoded.codePointAt(index)
            index += Character.charCount(scalar)
            val rawStart = text.rawByteAt(utf8Cursor)
            utf8Cursor += utf8Length(scalar)
            val rawEnd = text.rawByteAt(utf8Cursor)
            atoms.add(Atom(scalar, rawStart, rawEnd))
        }
    }

    /** Scans natural lines with exact terminators (parser.rs:230-298). */
    private fun scanNaturalLines() {
        var start = 0
        if (source.encodingFacts.bom != null && atoms.firstOrNull()?.ch == 0xfeff) {
            atoms[0].syntax = PropertiesSyntaxKind.Bom
            start = 1
        }
        var cursor = start
        while (cursor < atoms.size) {
            val lineStart = cursor
            while (cursor < atoms.size && atoms[cursor].ch != 0x0d && atoms[cursor].ch != 0x0a) {
                cursor += 1
            }
            val contentEnd = cursor
            if (cursor < atoms.size) {
                if (atoms[cursor].ch == 0x0d &&
                    atoms.getOrNull(cursor + 1)?.ch == 0x0a
                ) {
                    cursor += 2
                } else {
                    cursor += 1
                }
            }
            val end = cursor
            checkLimit("natural-lines", lines.size + 1, limits.maxNaturalLines)
            checkLimit("natural-line-scalars", contentEnd - lineStart, limits.maxNaturalLineScalars)
            val span = atomSpan(lineStart, end)
            checkLimit("natural-line-bytes", span.len, limits.maxNaturalLineBytes)
            val contentSpan = atomSpan(lineStart, contentEnd)
            val lineBreakSpan = if (contentEnd < end) {
                markAtoms(contentEnd until end, PropertiesSyntaxKind.LineBreak)
                atomSpan(contentEnd, end)
            } else {
                null
            }
            val node = issueNode(NodeRole.PropertiesNaturalLine)
            val naturalIndex = naturalLineEntities.size
            naturalLineEntities.add(
                NaturalLineEntity(node, span, contentSpan, lineBreakSpan),
            )
            lines.add(
                ScannedLine(lineStart, contentEnd, end, naturalIndex),
            )
        }
    }

    private fun isBlank(lineIndex: Int): Boolean {
        val line = lines[lineIndex]
        return (line.atomStart until line.atomContentEnd).all { isPropertiesWhitespace(atoms[it].ch) }
    }

    private fun isComment(lineIndex: Int): Boolean {
        val line = lines[lineIndex]
        val first = (line.atomStart until line.atomContentEnd)
            .firstOrNull { !isPropertiesWhitespace(atoms[it].ch) }
        return first != null && (atoms[first].ch == 0x23 || atoms[first].ch == 0x21)
    }

    private fun markLineContent(lineIndex: Int, syntax: PropertiesSyntaxKind) {
        val line = lines[lineIndex]
        markAtoms(line.atomStart until line.atomContentEnd, syntax)
    }

    /** One comment natural line; a comment never continues even if it ends
     * in backslash (RFC 0010 §5; parser.rs:320-350). */
    private fun addComment(lineIndex: Int) {
        checkLimit("comments", commentEntities.size + 1, limits.maxComments)
        val line = lines[lineIndex]
        val markerIndex = (line.atomStart until line.atomContentEnd)
            .first { !isPropertiesWhitespace(atoms[it].ch) }
        markAtoms(line.atomStart until markerIndex, PropertiesSyntaxKind.Whitespace)
        markAtoms(markerIndex until markerIndex + 1, PropertiesSyntaxKind.CommentMarker)
        markAtoms(markerIndex + 1 until line.atomContentEnd, PropertiesSyntaxKind.CommentText)
        val node = issueNode(NodeRole.PropertiesComment)
        commentEntities.add(
            CommentEntity(
                node = node,
                naturalLineIndex = line.naturalIndex,
                span = atomSpan(line.atomStart, line.atomContentEnd),
                marker = atoms[markerIndex].ch.toChar(),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Logical lines
    // ------------------------------------------------------------------

    /** Assembles one property/error logical line across continuation
     * natural lines (parser.rs:352-469). Returns the next line index. */
    private fun addLogicalLine(firstLine: Int): Int {
        checkLimit("logical-lines", logicalLineEntities.size + 1, limits.maxLogicalLines)
        var lineIndex = firstLine
        val naturalIndices = ArrayList<Int>()
        val logicalAtoms = ArrayList<Int>()
        while (true) {
            val line = lines[lineIndex]
            naturalIndices.add(line.naturalIndex)
            checkLimit(
                "logical-line-natural-lines",
                naturalIndices.size,
                limits.maxLogicalLineNaturalLines,
            )
            val leading = if (lineIndex == firstLine) {
                0
            } else {
                (line.atomStart until line.atomContentEnd)
                    .takeWhile { isPropertiesWhitespace(atoms[it].ch) }
                    .count()
            }
            if (leading > 0) {
                markAtoms(
                    line.atomStart until line.atomStart + leading,
                    PropertiesSyntaxKind.Whitespace,
                )
            }
            val slashRun = (line.atomStart + leading until line.atomContentEnd)
                .reversed()
                .takeWhile { atoms[it].ch == 0x5c }
                .count()
            val hasBreak = line.atomContentEnd < line.atomEnd
            val removeTerminalSlash = slashRun % 2 == 1
            val logicalEnd = if (removeTerminalSlash) {
                line.atomContentEnd - 1
            } else {
                line.atomContentEnd
            }
            for (atomIndex in line.atomStart + leading until logicalEnd) {
                logicalAtoms.add(atomIndex)
            }
            checkLimit("logical-line-scalars", logicalAtoms.size, limits.maxLogicalLineScalars)
            if (removeTerminalSlash) {
                markAtoms(
                    logicalEnd until line.atomContentEnd,
                    PropertiesSyntaxKind.ContinuationMarker,
                )
            }
            if (removeTerminalSlash && hasBreak && lineIndex + 1 < lines.size) {
                lineIndex += 1
                continue
            }
            break
        }

        val nextLine = lineIndex + 1
        val logicalNode = issueNode(NodeRole.PropertiesLogicalLine)
        val leading = logicalAtoms
            .takeWhile { isPropertiesWhitespace(atoms[it].ch) }
            .count()
        markLogicalPositions(logicalAtoms, 0 until leading, PropertiesSyntaxKind.Whitespace)
        val (keyStart, keyEnd, valueStart, hadSeparator) =
            splitProperty(logicalAtoms, leading)
        markLogicalPositions(logicalAtoms, keyStart until keyEnd, PropertiesSyntaxKind.Key)
        markLogicalPositions(logicalAtoms, keyEnd until valueStart, PropertiesSyntaxKind.Separator)
        markLogicalPositions(
            logicalAtoms,
            valueStart until logicalAtoms.size,
            PropertiesSyntaxKind.Value,
        )

        val key = decodeJavaString(atoms, logicalAtoms.subList(keyStart, keyEnd))
        val value = decodeJavaString(atoms, logicalAtoms.subList(valueStart, logicalAtoms.size))
        if (key is JavaStringDecode.Complete && value is JavaStringDecode.Complete) {
            finishProperty(
                logicalNode,
                naturalIndices,
                logicalAtoms,
                keyStart until keyEnd,
                valueStart until logicalAtoms.size,
                hadSeparator,
                key.string,
                value.string,
                firstLine,
                lineIndex,
            )
        } else {
            val error = when {
                key is JavaStringDecode.Malformed -> key.error
                value is JavaStringDecode.Malformed -> value.error
                else -> error("at least one decode is malformed")
            }
            recoverLogicalLine(
                logicalNode,
                naturalIndices,
                logicalAtoms,
                firstLine,
                lineIndex,
                error,
            )
        }
        return nextLine
    }

    /** Key/separator/value split over one logical line (parser.rs:471-507). */
    private fun splitProperty(
        logicalAtoms: List<Int>,
        keyStart: Int,
    ): SplitResult {
        var cursor = keyStart
        var escaped = false
        while (cursor < logicalAtoms.size) {
            val ch = atoms[logicalAtoms[cursor]].ch
            if (!escaped &&
                (ch == 0x3d || ch == 0x3a || isPropertiesWhitespace(ch))
            ) {
                break
            }
            if (ch == 0x5c) {
                escaped = !escaped
            } else {
                escaped = false
            }
            cursor += 1
        }
        val keyEnd = cursor
        val hadSeparator = cursor < logicalAtoms.size
        while (cursor < logicalAtoms.size && isPropertiesWhitespace(atoms[logicalAtoms[cursor]].ch)) {
            cursor += 1
        }
        if (cursor < logicalAtoms.size &&
            (atoms[logicalAtoms[cursor]].ch == 0x3d || atoms[logicalAtoms[cursor]].ch == 0x3a)
        ) {
            cursor += 1
        }
        while (cursor < logicalAtoms.size && isPropertiesWhitespace(atoms[logicalAtoms[cursor]].ch)) {
            cursor += 1
        }
        return SplitResult(keyStart, keyEnd, cursor, hadSeparator)
    }

    private data class SplitResult(
        val keyStart: Int,
        val keyEnd: Int,
        val valueStart: Int,
        val hadSeparator: Boolean,
    )

    /** Completes one property occurrence (parser.rs:509-624). */
    private fun finishProperty(
        logicalNode: NodeRef,
        naturalIndices: List<Int>,
        logicalAtoms: List<Int>,
        keyRange: IntRange,
        valueRange: IntRange,
        hadSeparator: Boolean,
        key: DecodedJavaString,
        value: DecodedJavaString,
        firstLine: Int,
        lastLine: Int,
    ) {
        checkLimit("properties", propertyEntities.size + 1, limits.maxProperties)
        checkLimit("java-code-units-per-string", key.units.size, limits.maxJavaCodeUnitsPerString)
        checkLimit("java-code-units-per-string", value.units.size, limits.maxJavaCodeUnitsPerString)
        val addedUnits = key.units.size + value.units.size
        checkLimit("total-java-code-units", totalJavaUnits + addedUnits, limits.maxTotalJavaCodeUnits)
        val addedEscapes = key.escapes.size + value.escapes.size
        val addedUnicodeEscapes = key.unicodeEscapes + value.unicodeEscapes
        checkLimit("escapes", escapeEntities.size + addedEscapes, limits.maxEscapes)
        checkLimit("unicode-escapes", totalUnicodeEscapes + addedUnicodeEscapes, limits.maxUnicodeEscapes)

        val propertyNode = issueNode(NodeRole.PropertiesProperty)
        val propertyIndex = propertyEntities.size
        val escapeIndices = ArrayList<Int>(addedEscapes)
        for ((inKey, spec) in key.escapes.map { true to it } + value.escapes.map { false to it }) {
            val node = issueNode(NodeRole.PropertiesEscape)
            atoms[spec.atomIndices[0]].syntax = PropertiesSyntaxKind.EscapeMarker
            for (atomIndex in spec.atomIndices.drop(1)) {
                atoms[atomIndex].syntax = PropertiesSyntaxKind.EscapeBody
            }
            val escapeStart = spec.atomIndices[0]
            val escapeEnd = spec.atomIndices[spec.atomIndices.size - 1] + 1
            escapeIndices.add(escapeEntities.size)
            escapeEntities.add(
                EscapeEntity(
                    node = node,
                    propertyIndex = propertyIndex,
                    inKey = inKey,
                    kind = spec.kind,
                    span = atomSpan(escapeStart, escapeEnd),
                    outputStart = spec.outputStart,
                    outputEnd = spec.outputEnd,
                ),
            )
        }
        val valueState = if (value.units.isEmpty()) {
            if (hadSeparator) PropertiesValueState.ExplicitEmpty else PropertiesValueState.ImplicitEmpty
        } else {
            PropertiesValueState.Present
        }
        val span = logicalSourceSpan(firstLine, lastLine)
        val keyAnchor = logicalAnchorSpan(logicalAtoms, keyRange.first, span.startByte)
        val valueAnchor = logicalAnchorSpan(logicalAtoms, valueRange.first, span.endByte)
        val keyFragments = fragmentSpans(logicalAtoms, keyRange)
        val valueFragments = fragmentSpans(logicalAtoms, valueRange)
        logicalLineEntities.add(
            LogicalLineEntity(
                node = logicalNode,
                kind = PropertiesLogicalLineKind.Property,
                naturalLineIndices = naturalIndices,
            ),
        )
        propertyEntities.add(
            PropertyEntity(
                node = propertyNode,
                logicalLineIndex = logicalLineEntities.size - 1,
                span = span,
                keyAnchor = keyAnchor,
                valueAnchor = valueAnchor,
                keyFragments = keyFragments,
                valueFragments = valueFragments,
                key = JavaString.fromCodeUnits(key.units),
                value = JavaString.fromCodeUnits(value.units),
                valueState = valueState,
                escapeIndices = escapeIndices,
                duplicateGroup = null,
            ),
        )
        totalJavaUnits += addedUnits
        totalUnicodeEscapes += addedUnicodeEscapes
    }

    /** One recovered malformed logical line (parser.rs:626-666). */
    private fun recoverLogicalLine(
        logicalNode: NodeRef,
        naturalIndices: List<Int>,
        logicalAtoms: List<Int>,
        firstLine: Int,
        lastLine: Int,
        error: DecodeError,
    ) {
        checkLimit("recovery-regions", errorLineEntities.size + 1, limits.maxRecoveryRegions)
        for (atomIndex in logicalAtoms) {
            atoms[atomIndex].syntax = PropertiesSyntaxKind.ErrorRegion
        }
        val span = logicalSourceSpan(firstLine, lastLine)
        val errorSpan = atomSpan(error.atomStart, error.atomEnd)
        val code = "java-properties.parse.malformed-unicode-escape@1"
        val errorNode = issueNode(NodeRole.PropertiesErrorLine)
        logicalLineEntities.add(
            LogicalLineEntity(
                node = logicalNode,
                kind = PropertiesLogicalLineKind.Error,
                naturalLineIndices = naturalIndices,
            ),
        )
        errorLineEntities.add(
            ErrorLineEntity(
                node = errorNode,
                logicalLineIndex = logicalLineEntities.size - 1,
                naturalLineIndices = naturalIndices,
                span = span,
                code = code,
            ),
        )
        pushDiagnostic(
            code,
            DiagnosticCategory.Syntax,
            errorSpan.startByte,
            errorSpan.endByte,
        )
    }

    // ------------------------------------------------------------------
    // Duplicate groups and structural coverage
    // ------------------------------------------------------------------

    /** Deterministic exact-code-unit duplicate groups (parser.rs:668-696). */
    private fun assignDuplicateGroups() {
        val groups = HashMap<JavaString, MutableList<Int>>()
        for ((index, property) in propertyEntities.withIndex()) {
            groups.getOrPut(property.key) { ArrayList() }.add(index)
        }
        var nextGroup = 1
        for ((key, indices) in groups.entries.sortedBy { it.key.utf16beBytes().toHexString() }) {
            if (indices.size <= 1) continue
            checkLimit("duplicate-group-members", indices.size, limits.maxDuplicateGroupMembers)
            for (index in indices) {
                propertyEntities[index] = propertyEntities[index].copy(duplicateGroup = nextGroup)
            }
            nextGroup += 1
        }
    }

    /** Collapses adjacent same-kind atoms into structural pieces
     * (parser.rs:698-729). */
    private fun buildStructuralPieces(): Pair<List<StructuralPiece>, List<PropertiesSyntaxKind>> {
        val pieces = ArrayList<StructuralPiece>()
        val syntaxKinds = ArrayList<PropertiesSyntaxKind>()
        var cursor = 0
        while (cursor < atoms.size) {
            val syntax = atoms[cursor].syntax ?: PropertiesSyntaxKind.ErrorRegion
            val kind = structuralKind(syntax)
            val start = cursor
            cursor += 1
            while (cursor < atoms.size &&
                (atoms[cursor].syntax ?: PropertiesSyntaxKind.ErrorRegion) == syntax &&
                atoms[cursor].rawStart == atoms[cursor - 1].rawEnd
            ) {
                cursor += 1
            }
            checkLimit("syntax-pieces", pieces.size + 1, limits.common.maxTokenCount)
            pieces.add(StructuralPiece(atomSpan(start, cursor), kind))
            syntaxKinds.add(syntax)
        }
        return pieces to syntaxKinds
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private fun markAtoms(range: IntRange, syntax: PropertiesSyntaxKind) {
        for (atomIndex in range) {
            atoms[atomIndex].syntax = syntax
        }
    }

    private fun markLogicalPositions(logicalAtoms: List<Int>, range: IntRange, syntax: PropertiesSyntaxKind) {
        for (position in range) {
            atoms[logicalAtoms[position]].syntax = syntax
        }
    }

    /** Ordered raw fragments of one key/value range, split at continuation
     * gaps (parser.rs:748-769). */
    private fun fragmentSpans(logicalAtoms: List<Int>, range: IntRange): List<Span> {
        if (range.isEmpty()) {
            return emptyList()
        }
        val spans = ArrayList<Span>()
        var fragmentStart = logicalAtoms[range.first]
        var previous = fragmentStart
        for (position in range.drop(1)) {
            val current = logicalAtoms[position]
            if (atoms[current].rawStart != atoms[previous].rawEnd) {
                spans.add(atomSpan(fragmentStart, previous + 1))
                fragmentStart = current
            }
            previous = current
        }
        spans.add(atomSpan(fragmentStart, previous + 1))
        return spans
    }

    /** The complete first-to-last logical source range (parser.rs:771-779). */
    private fun logicalSourceSpan(firstLine: Int, lastLine: Int): Span {
        val first = lines[firstLine]
        val last = lines[lastLine]
        return atomSpan(first.atomStart, last.atomContentEnd)
    }

    /** Zero-width key/value anchor span (parser.rs:781-798). */
    private fun logicalAnchorSpan(
        logicalAtoms: List<Int>,
        position: Int,
        emptyFallback: Int,
    ): Span {
        val raw = logicalAtoms.getOrNull(position)?.let { atoms[it].rawStart }
            ?: logicalAtoms.lastOrNull()?.let { atoms[it].rawEnd }
            ?: emptyFallback
        return try {
            authority.span(raw, raw)
        } catch (e: consema.document.LocationException) {
            throw propertiesResourceLimit("source-coordinate-boundary", 1, 0)
        }
    }

    private fun atomSpan(start: Int, end: Int): Span {
        val rawStart = if (start < atoms.size) {
            atoms[start].rawStart
        } else {
            source.len
        }
        val rawEnd = if (start == end) {
            rawStart
        } else {
            atoms.getOrNull(end - 1)?.rawEnd ?: source.len
        }
        return try {
            authority.span(rawStart, rawEnd)
        } catch (e: consema.document.LocationException) {
            throw propertiesResourceLimit("source-coordinate-boundary", 1, 0)
        }
    }

    private fun issueNode(role: NodeRole): NodeRef {
        val observed = nextNode + 1
        checkLimit("nodes", observed, limits.common.maxNodeCount)
        val node = authority.nodeRef(nextNode, role)
        nextNode += 1
        return node
    }

    private fun checkLimit(name: String, observed: Long, limit: Int) {
        val observedInt = if (observed > Int.MAX_VALUE) Int.MAX_VALUE else observed.toInt()
        if (observedInt > limit) {
            throw propertiesResourceLimit(name, observedInt, limit)
        }
    }

    private fun checkLimit(name: String, observed: Int, limit: Int) {
        if (observed > limit) {
            throw propertiesResourceLimit(name, observed, limit)
        }
    }

    private fun pushDiagnostic(
        code: String,
        category: DiagnosticCategory,
        start: Int,
        end: Int,
    ) {
        checkLimit("diagnostics", diagnostics.size + 1, limits.common.maxDiagnostics)
        diagnostics.add(
            sourceDiagnostic(
                authority,
                code,
                category,
                Severity.Error,
                start,
                end,
                occurrence,
            ),
        )
        occurrence = occurrence.inc()
        recovered = true
    }
}

// ---------------------------------------------------------------------------
// Escape decoding
// ---------------------------------------------------------------------------

/**
 * Decodes one key/value atom range (parser.rs:909-996). A malformed Unicode
 * escape returns its exact atom range for the recovery record.
 */
private fun decodeJavaString(atoms: List<Atom>, atomIndices: List<Int>): JavaStringDecode {
    val units = StringBuilder(atomIndices.size)
    val escapes = ArrayList<EscapeSpec>()
    var unicodeEscapes = 0
    var cursor = 0
    while (cursor < atomIndices.size) {
        val atomIndex = atomIndices[cursor]
        val ch = atoms[atomIndex].ch
        if (ch != 0x5c) {
            // One decoded scalar contributes its exact UTF-16 code units
            // (one for a BMP scalar, a surrogate pair for a supplementary
            // scalar; RFC 0010 §4).
            units.appendCodePoint(ch)
            cursor += 1
            continue
        }
        val nextIndex = atomIndices.getOrNull(cursor + 1)
            ?: return JavaStringDecode.Malformed(DecodeError(atomIndex, atomIndex + 1))
        val next = atoms[nextIndex].ch
        val outputStart = units.length
        val (kind, consumed) = when (next) {
            0x75 -> {
                if (cursor + 6 > atomIndices.size) {
                    return JavaStringDecode.Malformed(
                        DecodeError(atomIndex, atomIndices[atomIndices.size - 1] + 1),
                    )
                }
                var value = 0
                for (digitPosition in cursor + 2 until cursor + 6) {
                    val digitIndex = atomIndices[digitPosition]
                    val digit = hexDigit(atoms[digitIndex].ch)
                        ?: return JavaStringDecode.Malformed(DecodeError(atomIndex, digitIndex + 1))
                    value = (value shl 4) or digit
                }
                units.append(value.toChar())
                unicodeEscapes += 1
                PropertiesEscapeKind.Unicode to 6
            }
            0x74 -> {
                units.append('\t')
                PropertiesEscapeKind.Named to 2
            }
            0x6e -> {
                units.append('\n')
                PropertiesEscapeKind.Named to 2
            }
            0x72 -> {
                units.append('\r')
                PropertiesEscapeKind.Named to 2
            }
            0x66 -> {
                units.append('\u000C')
                PropertiesEscapeKind.Named to 2
            }
            0x5c -> {
                units.append('\\')
                PropertiesEscapeKind.Backslash to 2
            }
            else -> {
                // Before every other character the backslash is silently
                // removed (RFC 0010 §7); a supplementary scalar contributes
                // its surrogate pair.
                units.appendCodePoint(next)
                PropertiesEscapeKind.DroppedBackslash to 2
            }
        }
        escapes.add(
            EscapeSpec(
                atomIndices = atomIndices.subList(cursor, cursor + consumed),
                kind = kind,
                outputStart = outputStart,
                outputEnd = units.length,
            ),
        )
        cursor += consumed
    }
    return JavaStringDecode.Complete(DecodedJavaString(units.toString().toCharArray(), escapes, unicodeEscapes))
}

private fun hexDigit(scalar: Int): Int? =
    when {
        scalar in 0x30..0x39 -> scalar - 0x30
        scalar in 0x61..0x66 -> scalar - 0x61 + 10
        scalar in 0x41..0x46 -> scalar - 0x41 + 10
        else -> null
    }

/** Properties whitespace is exactly space, tab, and form feed (RFC 0010 §5;
 * parser.rs:998-1000). */
internal fun isPropertiesWhitespace(scalar: Int): Boolean =
    scalar == 0x20 || scalar == 0x09 || scalar == 0x0c

/** Piece classification (parser.rs:1002-1017). */
private fun structuralKind(syntax: PropertiesSyntaxKind): StructuralPieceKind =
    when (syntax) {
        PropertiesSyntaxKind.Whitespace,
        PropertiesSyntaxKind.LineBreak,
        PropertiesSyntaxKind.CommentMarker,
        PropertiesSyntaxKind.CommentText,
        -> StructuralPieceKind.Trivia

        PropertiesSyntaxKind.ErrorRegion -> StructuralPieceKind.ErrorRegion

        PropertiesSyntaxKind.Bom,
        PropertiesSyntaxKind.Key,
        PropertiesSyntaxKind.Separator,
        PropertiesSyntaxKind.Value,
        PropertiesSyntaxKind.EscapeMarker,
        PropertiesSyntaxKind.EscapeBody,
        PropertiesSyntaxKind.ContinuationMarker,
        -> StructuralPieceKind.Token
    }

/** Deterministic diagnostic order (consema-core/src/diagnostic.rs:106-123):
 * primary start (missing primary sorts last), category, code, occurrence. */
internal val deterministicDiagnosticOrder: Comparator<Diagnostic> =
    compareBy<Diagnostic> { it.primary?.startByte ?: ULong.MAX_VALUE }
        .thenBy { it.category.ordinal }
        .thenBy { it.code }
        .thenBy { it.occurrence }

private fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val hex = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xff
        hex[i * 2] = digits[value ushr 4]
        hex[i * 2 + 1] = digits[value and 0x0f]
    }
    return String(hex)
}
