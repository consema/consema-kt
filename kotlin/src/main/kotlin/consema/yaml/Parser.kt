// The YAML formation pipeline: profile-directive validation, the grammar
// parser producing a backend event stream, and composition into the
// immutable native model.
//
// Data authority:
//   - RFC 0007 §3-§4 (https://github.com/consema/consema/blob/main/docs/rfcs/0007-yaml-family-profiles-and-safety-v1.md:
//     54-97): both profiles accept UTF-8 (with or without BOM) and
//     UTF-16LE/BE with BOM; formation states Complete | Recovered |
//     FatalFormationFailure; backend success is never sufficient evidence
//     for a Complete Document.
//   - RFC 0007 §8 (…:194-213) pins the composition rules: reserve graph
//     identity when a node starts, register an anchor before descending,
//     resolve an alias to the most recent preceding anchor, never expand
//     aliases, permit backward self/mutual cycles.
//   - consema-rs/consema-yaml/src/lib.rs:259-320 (parse entry), lib.rs:789-858
//     (version-directive validation and backend failure mapping),
//     consema-rs/consema-yaml/src/backend.rs:71-176 (event surface, depth and
//     event limits), consema-rs/consema-yaml/src/native.rs:111-508 (composition)
//     and native.rs:510-539 (the empty-plain-scalar placeholder rewrite)
//     are the byte-arbitration authority for every event shape, span
//     convention, and failure code; consema-go/go/yaml/parser.go is a cross-reference
//     only.
//   - conformance/vectors/yaml-v1.json pins the observable outcomes
//     (stream.empty, stream.multi-document, formation.undefined-alias,
//     graph.shared-cycle, regression.plain-property-characters).
//
// Kotlin-idiomatic design: the grammar is a single recursive-descent class
// over decoded code points (no third-party parser; the reference pins
// saphyr-parser but Consema owns the grammar pipeline), producing the
// immutable backend event list that composition consumes. Event spans are
// decoded-scalar offsets in the full decoded text (including any BOM, which
// the parser skips before content), exactly like the Rust scalar_offset_base
// convention (backend.rs:138-141).

package consema.yaml

import consema.document.DocumentAuthority
import consema.document.EncodingRequest
import consema.document.ParseLimits
import consema.document.SourceEncoding
import consema.document.SourceLimits
import consema.document.SourceSnapshot

/** Backend event span over decoded scalar offsets (backend.rs:3-7). */
internal class BackendSpan(val startScalar: Int, val endScalar: Int)

/** Backend scalar presentation style (backend.rs:9-16). */
internal enum class BackendScalarStyle { Plain, SingleQuoted, DoubleQuoted, Literal, Folded }

/** Backend tag facts; the resolved tag URI is prefix + suffix
 * (backend.rs:18-22). */
internal class BackendTag(val prefix: String, val suffix: String)

/** The closed backend event surface (backend.rs:24-57). */
internal sealed class BackendEventKind {
    data object StreamStart : BackendEventKind()
    data object StreamEnd : BackendEventKind()
    data class DocumentStart(val explicit: Boolean) : BackendEventKind()
    data object DocumentEnd : BackendEventKind()
    data class Alias(val anchorId: Int) : BackendEventKind()
    data class Scalar(
        val decoded: String,
        val style: BackendScalarStyle,
        val anchorId: Int?,
        val tag: BackendTag?,
    ) : BackendEventKind()
    data class SequenceStart(val anchorId: Int?, val tag: BackendTag?) : BackendEventKind()
    data object SequenceEnd : BackendEventKind()
    data class MappingStart(val anchorId: Int?, val tag: BackendTag?) : BackendEventKind()
    data object MappingEnd : BackendEventKind()
}

/** One backend event with its decoded-scalar span (backend.rs:54-57). */
internal class BackendEvent(val kind: BackendEventKind, val span: BackendSpan)

/**
 * Parses one exact YAML stream into a complete immutable Document snapshot
 * (lib.rs:259-320). Exceeding a configured limit or failing source
 * construction is fatal and throws [YamlFormationException]; grammar and
 * composition failures throw the frozen registered codes
 * (yaml.profile.version-directive@1, yaml.parse.syntax@1, yaml.native.*,
 * yaml.anchor.*, yaml.alias.*, yaml.mapping.missing-value@1,
 * yaml.tag.kind-mismatch@1, yaml.scalar.invalid-explicit-tag@1). A fatal
 * failure returns no Document and no partial snapshot (RFC 0007 §4).
 */
fun parse(
    bytes: ByteArray,
    profile: YamlProfile,
    limits: ParseLimits = ParseLimits.default,
): Document {
    if (bytes.size > limits.maxSourceBytes) {
        throw resourceLimit("source-bytes", bytes.size, limits.maxSourceBytes)
    }
    val source = try {
        SourceSnapshot.fromRaw(
            bytes,
            EncodingRequest.new(SourceEncoding.Utf8),
            SourceLimits(
                maxRawBytes = limits.maxSourceBytes,
                maxDecodedUtf8Bytes = limits.maxSourceBytes.saturatingMul(2),
                maxDecodedScalars = limits.maxSourceBytes,
            ),
        )
    } catch (e: consema.document.SourceException) {
        throw YamlFormationException(e.code, "yaml source: ${e.code}", cause = e)
    }
    val text = source.decodedText()
        ?: throw YamlFormationException("yaml.parse.syntax@1", "yaml: no decoded text")
    validateVersionDirectives(text, profile)
    val events = parseEvents(text, profile, limits)
    val documentCount = events.count { it.kind is BackendEventKind.DocumentStart }
    val authority = DocumentAuthority.fresh()
    val tokenized = tokenize(source, authority, limits.maxTokenCount)
    val native = compose(events, source, authority, profile, tokenized, limits)
    return Document(
        authority = authority,
        source = source,
        profile = profile,
        structuralIndex = tokenized.index,
        syntaxKinds = tokenized.kinds,
        native = native,
        streamDocuments = documentCount,
        parseLimits = limits,
    )
}

/** Validates every `%YAML` directive against the selected profile before
 * grammar parsing (lib.rs:789-831). A conflicting version is fatal with
 * yaml.profile.version-directive@1 and the frozen arguments. */
internal fun validateVersionDirectives(text: String, profile: YamlProfile) {
    for ((index, rawLine) in text.lines().withIndex()) {
        val line = rawLine.removeSuffix("\r").removePrefix("\uFEFF")
        if (!line.startsWith("%YAML")) {
            continue
        }
        if (line.length == "%YAML".length ||
            !isSeparationChar(line["%YAML".length])
        ) {
            continue
        }
        val version = line.substring("%YAML".length)
            .trimStart(' ', '\t')
            .split(' ', '\t', '#')
            .firstOrNull()
            .orEmpty()
        if (version != profile.acceptedVersion()) {
            throw YamlFormationException(
                "yaml.profile.version-directive@1",
                "yaml: version directive $version conflicts with profile ${profile.id().id}",
            )
        }
    }
}

private fun isSeparationChar(value: Char): Boolean =
    value == ' ' || value == '\t' || value == '\r' || value == '\n'

/**
 * Parses the complete stream into backend events with decoded-scalar spans
 * (backend.rs:71-176). Grammar failures throw yaml.parse.syntax@1; the
 * nesting-depth and syntax-event limits are resource failures.
 */
internal fun parseEvents(
    text: String,
    profile: YamlProfile,
    limits: ParseLimits,
): List<BackendEvent> = EventParser(text, profile, limits).parse()

/** One parsed property group attached to a node. */
private class Properties(
    val anchor: PropertySpan? = null,
    val alias: PropertySpan? = null,
    val tag: BackendTag? = null,
    val start: Int,
)

private class PropertySpan(val start: Int, val end: Int, val name: String)

/** The recursive-descent grammar parser producing backend events. */
private class EventParser(
    private val text: String,
    private val profile: YamlProfile,
    private val limits: ParseLimits,
) {
    private val chars: IntArray = text.codePoints().toArray()
    private val events = ArrayList<BackendEvent>()
    private var position = 0
    private var lineStart = 0
    private var depth = 0
    private var nextAnchorId = 1
    private val anchors = HashMap<String, Int>()
    private val tagHandles = HashMap<String, String>()
    private var sawVersionDirective = false
    private var markerPosition = 0

    fun parse(): List<BackendEvent> {
        push(BackendEventKind.StreamStart, 0, 0)
        skipBom()
        while (true) {
            val directives = parseDirectives()
            val start = position
            val explicit = consumeMarker('-'.code, '-'.code, '-'.code)
            skipSeparationAndComments()
            if (atEnd()) {
                if (explicit || directives) {
                    emitDocument(explicit, start)
                }
                break
            }
            emitDocument(explicit, start)
        }
        push(BackendEventKind.StreamEnd, position, position)
        return events
    }

    private fun emitDocument(explicit: Boolean, start: Int) {
        nextAnchorId = 1
        anchors.clear()
        tagHandles.clear()
        sawVersionDirective = false
        val docStart = if (explicit) markerPosition else start
        push(BackendEventKind.DocumentStart(explicit), docStart, position)
        parseNode(blockIndent = 0, parentIndent = 0)
        consumeMarkerOrEnd()
        push(BackendEventKind.DocumentEnd, position, position)
    }

    private fun skipBom() {
        if (position == 0 && chars.getOrNull(0) == 0xFEFF) {
            position = 1
            lineStart = 1
        }
    }

    /** Parses directive lines at the stream/document head. The profile
     * check already ran; %TAG handles are resolved here (RFC 0007 §5 resets
     * tag directives at each document boundary). */
    private fun parseDirectives(): Boolean {
        var sawAny = false
        while (true) {
            skipBlankAndCommentLines()
            if (atLineStart() && current() == '%'.code) {
                val lineStartPos = position
                val lineEnd = lineContentEnd()
                val line = text.substring(lineStartPos, lineEnd)
                if (line.startsWith("%YAML")) {
                    if (sawVersionDirective) {
                        throw syntaxError(lineStartPos)
                    }
                    sawVersionDirective = true
                } else if (line.startsWith("%TAG")) {
                    val parts = line.substring(4).trim().split(Regex("[ \t]+"))
                    if (parts.size != 2 || !parts[0].endsWith('!') || parts[0].isEmpty()) {
                        throw syntaxError(lineStartPos)
                    }
                    tagHandles[parts[0]] = parts[1]
                }
                sawAny = true
                skipToNextLine()
            } else {
                return sawAny
            }
        }
    }

    private fun consumeMarkerOrEnd() {
        skipSeparationAndComments()
        if (atLineStart() && nextIsSeparationOrEnd(3) &&
            chars.getOrNull(position) == '.'.code &&
            chars.getOrNull(position + 1) == '.'.code &&
            chars.getOrNull(position + 2) == '.'.code
        ) {
            skipToNextLine()
            skipSeparationAndComments()
        }
    }

    /** Parses one block-context node. [blockIndent] is the indentation of
     * nested block collections; [parentIndent] is the indentation of the
     * enclosing collection for plain-scalar continuation. */
    private fun parseNode(blockIndent: Int, parentIndent: Int) {
        skipInlineWhitespace()
        parseNodeWithProperties(blockIndent, parentIndent, parseProperties())
    }

    private fun parseNodeWithProperties(
        blockIndent: Int,
        parentIndent: Int,
        properties: Properties?,
    ) {
        val nodeStart = properties?.start ?: position
        when {
            properties?.alias != null -> {
                val alias = properties.alias
                val target = anchors[alias.name]
                    ?: throw syntaxError(alias.start)
                push(BackendEventKind.Alias(target), alias.start, alias.end)
                return
            }
            properties != null && atLineEndOrComment() -> {
                // Node properties followed by a line end: the node itself is
                // a nested block node on the following lines (the properties
                // belong to that node).
                val nestedIndent = nextContentIndent(blockIndent)
                if (nestedIndent > blockIndent) {
                    skipBlankAndCommentLines()
                    parseNodeWithProperties(nestedIndent, blockIndent, properties)
                } else {
                    parseEmptyScalar(properties, nodeStart)
                }
            }
            current() == '['.code || current() == '{'.code -> {
                if (keyFollowedByColon()) {
                    parseBlockMapping(properties, nodeStart, blockIndent)
                } else if (current() == '['.code) {
                    parseFlowSequence(properties, nodeStart)
                } else {
                    parseFlowMapping(properties, nodeStart)
                }
            }
            current() == '\''.code || current() == '"'.code -> {
                if (keyFollowedByColon()) {
                    parseBlockMapping(properties, nodeStart, blockIndent)
                } else {
                    parseQuoted(properties, nodeStart, single = current() == '\''.code)
                }
            }
            current() == '|'.code || current() == '>'.code -> {
                parseBlockScalar(properties, nodeStart, parentIndent, folded = current() == '>'.code)
            }
            (current() == '-'.code || current() == '?'.code) &&
                atBlockIndicatorPosition() &&
                followedBySeparation(1) &&
                lineIndentAt(position) == blockIndent -> {
                if (current() == '-'.code) {
                    parseBlockSequence(properties, nodeStart, blockIndent)
                } else {
                    parseBlockMapping(properties, nodeStart, blockIndent)
                }
            }
            current() == '#'.code || current() == -1 ||
                current() == '\r'.code || current() == '\n'.code -> {
                parseEmptyScalar(properties, nodeStart)
            }
            else -> {
                if (keyFollowedByColon()) {
                    parseBlockMapping(properties, nodeStart, blockIndent)
                } else {
                    parsePlainScalar(properties, nodeStart, parentIndent, multiline = true)
                }
            }
        }
    }

    private fun atLineEndOrComment(): Boolean =
        lineEndOfCurrent() == position || current() == '#'.code

    /** Whether the current line starts a `---` or `...` document marker
     * (a document boundary in block context). */
    private fun atDocumentMarker(): Boolean {
        if (!atLineStart() || !nextIsSeparationOrEnd(3)) {
            return false
        }
        val first = chars.getOrNull(position) ?: return false
        if (first != '-'.code && first != '.'.code) {
            return false
        }
        return chars.getOrNull(position + 1) == first &&
            chars.getOrNull(position + 2) == first
    }

    /** Whether the node starting at the current position is a block mapping
     * key (a `:` followed by separation or line end on the same line). */
    private fun keyFollowedByColon(): Boolean {
        val current = current()
        if (current == '\''.code || current == '"'.code) {
            val end = scanQuotedEnd(position, current)
            if (end < 0) {
                return false
            }
            return colonAfter(end)
        }
        if (current == '['.code || current == '{'.code) {
            val end = scanFlowEnd(position)
            if (end < 0) {
                return false
            }
            return colonAfter(end)
        }
        var probe = position
        while (probe < chars.size) {
            val character = chars[probe]
            if (character == '\r'.code || character == '\n'.code) {
                return false
            }
            if (character == '#'.code &&
                (probe == position || isSeparation(chars[probe - 1]))
            ) {
                return false
            }
            if (character == ':'.code && nextIsSeparationOrEndAt(probe, 1)) {
                return true
            }
            probe++
        }
        return false
    }

    private fun colonAfter(end: Int): Boolean {
        var probe = end
        while (probe < chars.size &&
            (chars[probe] == ' '.code || chars[probe] == '\t'.code)
        ) {
            probe++
        }
        return chars.getOrNull(probe) == ':'.code &&
            nextIsSeparationOrEndAt(probe, 1)
    }

    /** Scans to the end of a quoted literal; returns -1 when unterminated. */
    private fun scanQuotedEnd(start: Int, quote: Int): Int {
        var probe = start + 1
        while (probe < chars.size) {
            val character = chars[probe]
            if (quote == '"'.code && character == '\\'.code) {
                probe += if (chars.getOrNull(probe + 1) == '\r'.code &&
                    chars.getOrNull(probe + 2) == '\n'.code
                ) {
                    3
                } else if (chars.getOrNull(probe + 1) == '\r'.code ||
                    chars.getOrNull(probe + 1) == '\n'.code
                ) {
                    2
                } else {
                    2
                }
                continue
            }
            if (character == quote) {
                if (quote == '\''.code && chars.getOrNull(probe + 1) == '\''.code) {
                    probe += 2
                    continue
                }
                return probe + 1
            }
            probe++
        }
        return -1
    }

    /** Scans to the end of a flow collection (nesting- and quote-aware);
     * returns -1 when unterminated. */
    private fun scanFlowEnd(start: Int): Int {
        val opening = chars[start]
        val closing = if (opening == '['.code) ']'.code else '}'.code
        var probe = start + 1
        var depth = 1
        while (probe < chars.size) {
            val character = chars[probe]
            when {
                character == '\''.code || character == '"'.code -> {
                    val end = scanQuotedEnd(probe, character)
                    if (end < 0) {
                        return -1
                    }
                    probe = end
                }
                character == opening -> {
                    depth++
                    probe++
                }
                character == closing -> {
                    depth--
                    if (depth == 0) {
                        return probe + 1
                    }
                    probe++
                }
                else -> probe++
            }
        }
        return -1
    }

    /** One optional node property: anchor, alias, or tag (in any order). */
    private fun parseProperties(): Properties? {
        var anchor: PropertySpan? = null
        var alias: PropertySpan? = null
        var tag: BackendTag? = null
        var start = position
        var found = false
        while (true) {
            skipInlineWhitespace()
            val current = current()
            if (current == '&'.code) {
                val propertyStart = position
                position++
                val nameStart = position
                while (position < chars.size && !isSeparation(chars[position]) &&
                    !isFlowIndicator(chars[position])
                ) {
                    position++
                }
                if (position == nameStart) {
                    throw syntaxError(propertyStart)
                }
                val name = text.substring(nameStart, position)
                anchor = PropertySpan(propertyStart, position, name)
                anchors[name] = nextAnchorId++
                if (!found) {
                    start = propertyStart
                }
                found = true
            } else if (current == '*'.code) {
                val propertyStart = position
                position++
                val nameStart = position
                while (position < chars.size && !isSeparation(chars[position]) &&
                    !isFlowIndicator(chars[position])
                ) {
                    position++
                }
                if (position == nameStart) {
                    throw syntaxError(propertyStart)
                }
                val name = text.substring(nameStart, position)
                alias = PropertySpan(propertyStart, position, name)
                if (!found) {
                    start = propertyStart
                }
                found = true
            } else if (current == '!'.code) {
                val propertyStart = position
                tag = parseTag()
                if (!found) {
                    start = propertyStart
                }
                found = true
            } else {
                break
            }
        }
        return if (found) {
            Properties(anchor, alias, tag, start)
        } else {
            null
        }
    }

    /** Parses one tag property and resolves it through the directive table
     * (backend.rs:90-98; saphyr keep_tags(false) semantics). */
    private fun parseTag(): BackendTag {
        val start = position
        position++
        if (chars.getOrNull(position) == '<'.code) {
            position++
            val uriStart = position
            while (position < chars.size && chars[position] != '>'.code) {
                position++
            }
            if (position >= chars.size) {
                throw syntaxError(start)
            }
            val uri = text.substring(uriStart, position)
            position++
            return BackendTag("", uri)
        }
        val handleStart = position
        var foundHandle = false
        while (position < chars.size && !isSeparation(chars[position]) &&
            !isFlowIndicator(chars[position])
        ) {
            if (chars[position] == '!'.code) {
                position++
                foundHandle = true
                break
            }
            position++
        }
        val handle = if (foundHandle) text.substring(start, position) else ""
        if (!foundHandle) {
            // A bare `!` alone is the non-specific tag; `!suffix` is a
            // local tag spelled `!` + suffix.
            val suffix = text.substring(start + 1, position)
            return BackendTag("!", suffix)
        }
        val suffixStart = position
        while (position < chars.size && !isSeparation(chars[position]) &&
            !isFlowIndicator(chars[position])
        ) {
            position++
        }
        val suffix = text.substring(suffixStart, position)
        val prefix = tagHandles[handle] ?: when (handle) {
            "!!" -> "tag:yaml.org,2002:"
            else -> handle
        }
        return BackendTag(prefix, suffix)
    }

    // -- flow context -------------------------------------------------------

    private fun parseFlowSequence(properties: Properties?, nodeStart: Int) {
        enterCollection()
        push(
            BackendEventKind.SequenceStart(
                properties?.anchor?.let { anchors[it.name] },
                properties?.tag,
            ),
            nodeStart,
            position + 1,
        )
        position++
        skipFlowTrivia()
        if (current() == ']'.code) {
            position++
        } else {
            while (true) {
                if (current() == ','.code || current() == -1 ||
                    current() == '\r'.code || current() == '\n'.code
                ) {
                    throw syntaxError(position)
                }
                parseFlowNode()
                skipFlowTrivia()
                if (current() == ','.code) {
                    position++
                    skipFlowTrivia()
                    continue
                }
                if (current() == ']'.code) {
                    position++
                    break
                }
                throw syntaxError(position)
            }
        }
        depth--
        push(BackendEventKind.SequenceEnd, position, position)
    }

    private fun parseFlowMapping(properties: Properties?, nodeStart: Int) {
        enterCollection()
        push(
            BackendEventKind.MappingStart(
                properties?.anchor?.let { anchors[it.name] },
                properties?.tag,
            ),
            nodeStart,
            position + 1,
        )
        position++
        skipFlowTrivia()
        if (current() == '}'.code) {
            position++
        } else {
            while (true) {
                if (current() == ','.code || current() == -1) {
                    throw syntaxError(position)
                }
                if (current() == '?'.code && followedBySeparation(1)) {
                    position++
                    skipFlowTrivia()
                    parseFlowKeyNode()
                    skipFlowTrivia()
                    expectFlowColon()
                } else {
                    parseFlowKeyNode()
                    skipFlowTrivia()
                    expectFlowColon()
                }
                skipFlowTrivia()
                parseFlowNode()
                skipFlowTrivia()
                if (current() == ','.code) {
                    position++
                    skipFlowTrivia()
                    continue
                }
                if (current() == '}'.code) {
                    position++
                    break
                }
                throw syntaxError(position)
            }
        }
        depth--
        push(BackendEventKind.MappingEnd, position, position)
    }

    /** A flow mapping key is a node without collection start (plain,
     * quoted, flow collection, or alias). */
    private fun parseFlowKeyNode() {
        val properties = parseProperties()
        val nodeStart = properties?.start ?: position
        when {
            properties?.alias != null -> {
                val alias = properties.alias
                val target = anchors[alias.name] ?: throw syntaxError(alias.start)
                push(BackendEventKind.Alias(target), alias.start, alias.end)
                return
            }
            current() == '\''.code || current() == '"'.code ->
                parseQuoted(properties, nodeStart, single = current() == '\''.code)
            current() == '['.code -> parseFlowSequence(properties, nodeStart)
            current() == '{'.code -> parseFlowMapping(properties, nodeStart)
            else -> parseFlowPlain(properties, nodeStart)
        }
    }

    private fun expectFlowColon() {
        if (current() == ':'.code) {
            position++
            while (current() == ' '.code || current() == '\t'.code) {
                position++
            }
        } else {
            throw syntaxError(position)
        }
    }

    /** One flow-context node: alias, scalar, or nested collection. */
    private fun parseFlowNode() {
        val properties = parseProperties()
        val nodeStart = properties?.start ?: position
        when {
            properties?.alias != null -> {
                val alias = properties.alias
                val target = anchors[alias.name] ?: throw syntaxError(alias.start)
                push(BackendEventKind.Alias(target), alias.start, alias.end)
                return
            }
            current() == '\''.code || current() == '"'.code ->
                parseQuoted(properties, nodeStart, single = current() == '\''.code)
            current() == '['.code -> parseFlowSequence(properties, nodeStart)
            current() == '{'.code -> parseFlowMapping(properties, nodeStart)
            current() == ']'.code || current() == '}'.code || current() == ','.code ||
                current() == -1 -> parseEmptyScalar(properties, nodeStart)
            else -> parseFlowPlain(properties, nodeStart)
        }
    }

    private fun parseFlowPlain(properties: Properties?, nodeStart: Int) {
        val start = position
        while (position < chars.size) {
            val current = chars[position]
            if (isFlowIndicator(current) || current == '\r'.code || current == '\n'.code) {
                break
            }
            if (current == ':'.code &&
                (isSeparation(chars.getOrNull(position + 1) ?: -1) ||
                    isFlowIndicator(chars.getOrNull(position + 1) ?: -1))
            ) {
                break
            }
            if (current == '#'.code &&
                (position == start || isSeparation(chars[position - 1]))
            ) {
                break
            }
            position++
        }
        val decoded = text.substring(start, position).trim()
        if (decoded.isEmpty()) {
            parseEmptyScalar(properties, nodeStart)
            return
        }
        emitScalar(properties, nodeStart, decoded, BackendScalarStyle.Plain, start, position)
    }

    // -- scalars ------------------------------------------------------------

    private fun parseQuoted(properties: Properties?, nodeStart: Int, single: Boolean) {
        val start = position
        position++
        val content = StringBuilder()
        var pendingBreak = 0
        var closed = false
        while (position < chars.size) {
            val current = chars[position]
            if (single) {
                if (current == '\''.code) {
                    if (chars.getOrNull(position + 1) == '\''.code) {
                        content.append('\'')
                        position += 2
                        continue
                    }
                    position++
                    closed = true
                    break
                }
                if (current == '\r'.code || current == '\n'.code) {
                    consumeLineBreak()
                    pendingBreak++
                    continue
                }
                if (pendingBreak > 0) {
                    appendFolded(content, pendingBreak)
                    pendingBreak = 0
                }
                content.appendCodePoint(current)
                position++
            } else {
                if (current == '"'.code) {
                    position++
                    closed = true
                    break
                }
                if (current == '\\'.code) {
                    if (pendingBreak > 0) {
                        appendFolded(content, pendingBreak)
                        pendingBreak = 0
                    }
                    position++
                    decodeEscape(content)
                    continue
                }
                if (current == '\r'.code || current == '\n'.code) {
                    consumeLineBreak()
                    pendingBreak++
                    continue
                }
                if (pendingBreak > 0) {
                    appendFolded(content, pendingBreak)
                    pendingBreak = 0
                }
                content.appendCodePoint(current)
                position++
            }
        }
        if (!closed) {
            throw syntaxError(start)
        }
        emitScalar(
            properties,
            nodeStart,
            content.toString(),
            if (single) BackendScalarStyle.SingleQuoted else BackendScalarStyle.DoubleQuoted,
            start,
            position,
        )
    }

    private fun appendFolded(content: StringBuilder, breaks: Int) {
        content.append(if (breaks == 1) " " else "\n".repeat(breaks - 1))
    }

    /** Decodes one double-quoted escape sequence (the YAML 1.2 escape set). */
    private fun decodeEscape(content: StringBuilder) {
        if (position >= chars.size) {
            throw syntaxError(position)
        }
        val escape = chars[position]
        position++
        when (escape) {
                '0'.code -> content.append('\u0000')
                'a'.code -> content.append('\u0007')
            'b'.code -> content.append('\b')
            't'.code, '\t'.code -> content.append('\t')
            'n'.code -> content.append('\n')
                'v'.code -> content.append('\u000B')
                'f'.code -> content.append('\u000C')
            'r'.code -> content.append('\r')
                'e'.code -> content.append('\u001B')
            ' '.code -> content.append(' ')
            '"'.code -> content.append('"')
            '/'.code -> content.append('/')
            '\\'.code -> content.append('\\')
            'N'.code -> content.append('')
            '_'.code -> content.append(' ')
            'L'.code -> content.append(' ')
            'P'.code -> content.append(' ')
            'x'.code -> content.appendCodePoint(parseHexEscape(2))
            'u'.code -> content.appendCodePoint(parseHexEscape(4))
            'U'.code -> content.appendCodePoint(parseHexEscape(8))
            '\r'.code, '\n'.code -> consumeLineBreak()
            else -> throw syntaxError(position - 1)
        }
    }

    private fun parseHexEscape(digits: Int): Int {
        var value = 0
        for (index in 0 until digits) {
            val digit = chars.getOrNull(position)?.toChar()?.digitToIntOrNull(16)
                ?: throw syntaxError(position)
            value = value * 16 + digit
            position++
        }
        return value
    }

    /** A block-context plain scalar with optional line folding. */
    private fun parsePlainScalar(
        properties: Properties?,
        nodeStart: Int,
        parentIndent: Int,
        multiline: Boolean,
    ) {
        val start = position
        val content = StringBuilder()
        var pendingBreak = 0
        while (position < chars.size) {
            val current = chars[position]
            if (current == '\r'.code || current == '\n'.code) {
                consumeLineBreak()
                if (!multiline) {
                    break
                }
                val extra = continuePlain(parentIndent)
                if (extra < 0) {
                    break
                }
                pendingBreak += 1 + extra
                continue
            }
            if (current == ':' .code && nextIsSeparationOrEnd(1)) {
                break
            }
            if (current == '#'.code &&
                (position == start || isSeparation(chars[position - 1]))
            ) {
                break
            }
            if (isSeparation(current)) {
                // Separation is content only when the plain scalar resumes
                // with non-terminating content.
                val resumed = plainResumesAfterSeparation()
                if (!resumed) {
                    break
                }
                if (pendingBreak > 0) {
                    appendFolded(content, pendingBreak)
                    pendingBreak = 0
                }
                content.append(' ')
                position++
                continue
            }
            if (pendingBreak > 0) {
                appendFolded(content, pendingBreak)
                pendingBreak = 0
            }
            content.appendCodePoint(current)
            position++
        }
        if (pendingBreak > 0) {
            appendFolded(content, pendingBreak)
        }
        if (content.isEmpty()) {
            parseEmptyScalar(properties, nodeStart)
            return
        }
        emitScalar(properties, nodeStart, content.toString(), BackendScalarStyle.Plain, start, position)
    }

    /** Whether a separation run is followed by content that continues the
     * plain scalar (not a line end, comment, or colon value indicator).
     * Flow indicators are plain-scalar content in block context
     * (`runs-on: ${{ matrix.os }}` is one scalar; parser.ts
     * scanBlockPlainLine), so they never terminate the scalar here. */
    private fun plainResumesAfterSeparation(): Boolean {
        var probe = position
        while (probe < chars.size &&
            (chars[probe] == ' '.code || chars[probe] == '\t'.code)
        ) {
            probe++
        }
        val next = chars.getOrNull(probe) ?: return false
        if (next == '\r'.code || next == '\n'.code || next == '#'.code) {
            return false
        }
        if (next == ':'.code &&
            (isSeparation(chars.getOrNull(probe + 1) ?: -1) ||
                isFlowIndicator(chars.getOrNull(probe + 1) ?: -1))
        ) {
            return false
        }
        return true
    }

    /** Probes the line after a consumed break for plain-scalar continuation.
     * Returns the number of additional blank-line breaks, or -1 when the
     * scalar ends. On continuation the position advances past the blank
     * lines and the continuation line's indentation. */
    private fun continuePlain(parentIndent: Int): Int {
        var probe = position
        var extra = 0
        while (probe < chars.size) {
            val lineEnd = nextLineEndFrom(chars, probe)
            val contentEnd = lineContentEndFrom(chars, probe, lineEnd)
            val blank = isBlankRange(chars, probe, contentEnd)
            if (blank) {
                extra++
                probe = lineEnd
                continue
            }
            break
        }
        if (probe >= chars.size) {
            return -1
        }
        val lineEnd = nextLineEndFrom(chars, probe)
        val contentEnd = lineContentEndFrom(chars, probe, lineEnd)
        if (chars[probe] == '#'.code) {
            return -1
        }
        val indent = leadingSpaces(chars, probe, contentEnd)
        if (indent <= parentIndent) {
            return -1
        }
        if (lineStartsStructureAt(chars, probe, indent, contentEnd)) {
            return -1
        }
        position = probe + indent
        lineStart = probe
        return extra
    }

    /** Whether the line at [lineStartPos] (with [indent] leading spaces,
     * content ending at [contentEnd]) begins a block structure that must
     * not fold into a plain scalar. */
    private fun lineStartsStructureAt(chars: IntArray, lineStartPos: Int, indent: Int, contentEnd: Int): Boolean {
        val first = chars.getOrNull(lineStartPos + indent) ?: return false
        if ((first == '-'.code || first == '?'.code) &&
            isSeparation(chars.getOrNull(lineStartPos + indent + 1) ?: -1)
        ) {
            return true
        }
        var probe = lineStartPos + indent
        while (probe < contentEnd) {
            if (chars[probe] == ':'.code &&
                isSeparation(chars.getOrNull(probe + 1) ?: -1)
            ) {
                return true
            }
            probe++
        }
        return false
    }

    private fun parseEmptyScalar(properties: Properties?, nodeStart: Int) {
        // The backend empty-plain placeholder "~" (native.rs:510-539) with a
        // zero-width span; composition rewrites it to "".
        emitScalar(properties, nodeStart, "~", BackendScalarStyle.Plain, position, position)
    }

    // -- block context ------------------------------------------------------

    private fun parseBlockScalar(
        properties: Properties?,
        nodeStart: Int,
        parentIndent: Int,
        folded: Boolean,
    ) {
        position++
        var chomp = 'c'
        var indentDigit: Int? = null
        while (position < chars.size) {
            val current = chars[position]
            if (current == '+'.code) {
                chomp = 'k'
                position++
            } else if (current == '-'.code) {
                chomp = 's'
                position++
            } else if (current in '1'.code..'9'.code) {
                indentDigit = current - '0'.code
                position++
            } else {
                break
            }
        }
        skipInlineWhitespace()
        if (current() == '#'.code) {
            skipToNextLine()
        } else if (current() == '\r'.code || current() == '\n'.code) {
            consumeLineBreak()
        }
        val contentIndent = if (indentDigit != null) {
            parentIndent + indentDigit
        } else {
            findBlockScalarIndent(parentIndent)
        }
        val lines = ArrayList<String>()
        val moreIndented = ArrayList<Boolean>()
        var contentEnd = position
        if (contentIndent > parentIndent) {
            while (true) {
                if (atEnd()) {
                    break
                }
                val lineEnd = nextLineEndFrom(chars, position)
                val contentEndLine = lineContentEndFrom(chars, position, lineEnd)
                val indent = leadingSpaces(chars, position, contentEndLine)
                val blank = isBlankRange(chars, position, contentEndLine)
                if (!blank && indent < contentIndent) {
                    break
                }
                if (lineEnd > position + indent) {
                    lines.add(text.substring(position + indent, contentEndLine))
                } else {
                    lines.add("")
                }
                moreIndented.add(!blank && indent > contentIndent)
                contentEnd = skipToNextLineFrom(position)
            }
        }
        val content = if (folded) {
            foldBlockLines(lines, moreIndented)
        } else {
            lines.joinToString("\n")
        }
        val decoded = chompBlockScalar(content, lines.size, chomp)
        push(
            BackendEventKind.Scalar(
                decoded,
                if (folded) BackendScalarStyle.Folded else BackendScalarStyle.Literal,
                properties?.anchor?.let { anchors[it.name] },
                properties?.tag,
            ),
            nodeStart,
            contentEnd,
        )
    }

    private fun chompBlockScalar(content: String, lineCount: Int, chomp: Char): String =
        when (chomp) {
            'k' -> if (lineCount > 0) "$content\n" else content
            's' -> content.trimEnd('\n')
            else -> if (lineCount > 0) "$content\n" else content
        }

    /** Folding of `>` block scalars: a break between two non-blank lines
     * folds to a space; blank lines and more-indented lines keep breaks. */
    private fun foldBlockLines(lines: List<String>, moreIndented: List<Boolean>): String {
        if (lines.isEmpty()) {
            return ""
        }
        val output = StringBuilder()
        for (index in lines.indices) {
            if (index > 0) {
                val previousBlank = lines[index - 1].isEmpty()
                val currentBlank = lines[index].isEmpty()
                output.append(
                    if (previousBlank || currentBlank || moreIndented[index - 1] ||
                        moreIndented[index]
                    ) {
                        "\n"
                    } else {
                        " "
                    },
                )
            }
            output.append(lines[index])
        }
        return output.toString()
    }

    private fun findBlockScalarIndent(parentIndent: Int): Int {
        var probe = position
        while (probe < chars.size) {
            val lineEnd = nextLineEndFrom(chars, probe)
            val contentEnd = lineContentEndFrom(chars, probe, lineEnd)
            val indent = leadingSpaces(chars, probe, contentEnd)
            val blank = isBlankRange(chars, probe, contentEnd)
            if (!blank) {
                return if (indent > parentIndent) indent else parentIndent + 1
            }
            probe = lineEnd
        }
        return parentIndent + 1
    }

    private fun parseBlockSequence(properties: Properties?, nodeStart: Int, blockIndent: Int) {
        enterCollection()
        push(
            BackendEventKind.SequenceStart(
                properties?.anchor?.let { anchors[it.name] },
                properties?.tag,
            ),
            nodeStart,
            position + 1,
        )
        while (true) {
            skipBlankAndCommentLines()
            if (atEnd() || lineIndentAt(position) != blockIndent || atDocumentMarker()) {
                break
            }
            // Step past the line's indentation to the `-` indicator; the
            // previous item's parse ends at the next line start, so without
            // this the loop sees the leading spaces and ends the sequence
            // after the first item (the trailing item then mis-parses as a
            // new document and aliases registered in the first document are
            // gone — `- name: ingest` / `settings: *defaults` fixtures).
            skipInlineWhitespace()
            if (current() != '-'.code || !followedBySeparation(1)) {
                break
            }
            position++
            skipInlineWhitespace()
            if (lineEndOfCurrent() == position) {
                val itemIndent = nextContentIndent(blockIndent)
                if (itemIndent <= blockIndent) {
                    parseEmptyScalar(null, position)
                } else {
                    skipBlankAndCommentLines()
                    parseNode(itemIndent, blockIndent)
                }
            } else {
                parseNode(position - lineStart, blockIndent)
            }
        }
        depth--
        push(BackendEventKind.SequenceEnd, position, position)
    }

    private fun parseBlockMapping(properties: Properties?, nodeStart: Int, blockIndent: Int) {
        enterCollection()
        push(
            BackendEventKind.MappingStart(
                properties?.anchor?.let { anchors[it.name] },
                properties?.tag,
            ),
            nodeStart,
            position,
        )
        parseBlockEntry(blockIndent)
        while (true) {
            skipBlankAndCommentLines()
            if (atEnd() || lineIndentAt(position) != blockIndent || atDocumentMarker()) {
                break
            }
            parseBlockEntry(blockIndent)
        }
        depth--
        push(BackendEventKind.MappingEnd, position, position)
    }

    private fun parseBlockEntry(blockIndent: Int) {
        if (current() == '?'.code && followedBySeparation(1)) {
            position++
            skipInlineWhitespace()
            if (lineEndOfCurrent() == position) {
                val keyIndent = nextContentIndent(blockIndent)
                if (keyIndent <= blockIndent) {
                    parseEmptyScalar(null, position)
                } else {
                    skipBlankAndCommentLines()
                    parseNode(keyIndent, blockIndent)
                }
            } else {
                parseBlockKey(blockIndent)
            }
            skipSeparationAndComments()
            if (current() == ':'.code && followedBySeparation(1)) {
                position++
                skipInlineWhitespace()
                parseMappingValue(blockIndent)
            } else {
                parseEmptyScalar(null, position)
            }
        } else {
            parseBlockKey(blockIndent)
            skipSeparationAndComments()
            if (current() == ':'.code && followedBySeparation(1)) {
                position++
                skipInlineWhitespace()
                parseMappingValue(blockIndent)
            } else {
                throw syntaxError(position)
            }
        }
    }

    /** One implicit block mapping key: a single-line node ending before `:`
     * (plain, quoted, or flow), or a nested block node on the following
     * lines when node properties precede a line end. */
    private fun parseBlockKey(blockIndent: Int) {
        parseBlockKeyWithProperties(blockIndent, parseProperties())
    }

    private fun parseBlockKeyWithProperties(blockIndent: Int, properties: Properties?) {
        val nodeStart = properties?.start ?: position
        when {
            properties?.alias != null -> {
                val alias = properties.alias
                val target = anchors[alias.name] ?: throw syntaxError(alias.start)
                push(BackendEventKind.Alias(target), alias.start, alias.end)
                return
            }
            properties != null && atLineEndOrComment() -> {
                val nestedIndent = nextContentIndent(blockIndent)
                if (nestedIndent > blockIndent) {
                    skipBlankAndCommentLines()
                    parseNodeWithProperties(nestedIndent, blockIndent, properties)
                } else {
                    parseEmptyScalar(properties, nodeStart)
                }
            }
            current() == '\''.code || current() == '"'.code ->
                parseQuoted(properties, nodeStart, single = current() == '\''.code)
            current() == '['.code -> parseFlowSequence(properties, nodeStart)
            current() == '{'.code -> parseFlowMapping(properties, nodeStart)
            else -> parsePlainScalar(properties, nodeStart, blockIndent, multiline = false)
        }
    }

    /** The mapping value after `:`: same-line node or a nested block node
     * on the following lines. */
    private fun parseMappingValue(blockIndent: Int) {
        if (lineEndOfCurrent() == position) {
            val valueIndent = nextContentIndent(blockIndent)
            if (valueIndent > blockIndent) {
                skipBlankAndCommentLines()
                parseNode(valueIndent, blockIndent)
            } else {
                parseEmptyScalar(null, position)
            }
        } else {
            when (current()) {
                '#'.code -> parseEmptyScalar(null, position)
                else -> parseNode(blockIndent, blockIndent)
            }
        }
    }

    private fun emitScalar(
        properties: Properties?,
        nodeStart: Int,
        decoded: String,
        style: BackendScalarStyle,
        start: Int,
        end: Int,
    ) {
        push(
            BackendEventKind.Scalar(
                decoded,
                style,
                properties?.anchor?.let { anchors[it.name] },
                properties?.tag,
            ),
            nodeStart,
            end,
        )
    }

    private fun enterCollection() {
        depth++
        if (depth > limits.maxNestingDepth) {
            throw resourceLimit("nesting-depth", depth, limits.maxNestingDepth)
        }
    }

    private fun push(kind: BackendEventKind, start: Int, end: Int) {
        val observed = events.size + 1
        if (observed > limits.maxTokenCount) {
            throw resourceLimit("syntax-events", observed, limits.maxTokenCount)
        }
        events.add(BackendEvent(kind, BackendSpan(start, end)))
    }

    private fun syntaxError(offset: Int): YamlFormationException =
        YamlFormationException(
            "yaml.parse.syntax@1",
            "yaml: syntax error at scalar offset $offset",
            scalarOffset = offset,
        )

    // -- position helpers ---------------------------------------------------

    private fun current(): Int = chars.getOrNull(position) ?: -1

    private fun atEnd(): Boolean = position >= chars.size

    private fun atLineStart(): Boolean = position == lineStart

    /** Whether the current position is the first non-whitespace character of
     * its line (the position of a block indicator or block node start). */
    private fun atBlockIndicatorPosition(): Boolean {
        var probe = lineStart
        while (probe < position &&
            (chars[probe] == ' '.code || chars[probe] == '\t'.code)
        ) {
            probe++
        }
        return probe == position
    }

    private fun lineEndOfCurrent(): Int = lineContentEndFrom(chars, position, nextLineEndFrom(chars, position))

    private fun followedBySeparation(length: Int): Boolean = nextIsSeparationOrEnd(length)

    private fun nextIsSeparationOrEnd(length: Int): Boolean =
        chars.getOrNull(position + length)?.let { isSeparation(it) } ?: true

    private fun nextIsSeparationOrEndAt(at: Int, length: Int): Boolean =
        chars.getOrNull(at + length)?.let { isSeparation(it) } ?: true

    private fun consumeMarker(first: Int, second: Int, third: Int): Boolean {
        if (atLineStart() && chars.getOrNull(position) == first &&
            chars.getOrNull(position + 1) == second &&
            chars.getOrNull(position + 2) == third &&
            nextIsSeparationOrEnd(3)
        ) {
            markerPosition = position
            position += 3
            return true
        }
        return false
    }

    /** Consumes one line break (CRLF, LF, or bare CR). */
    private fun consumeLineBreak() {
        if (chars.getOrNull(position) == '\r'.code) {
            position++
            if (chars.getOrNull(position) == '\n'.code) {
                position++
            }
        } else if (chars.getOrNull(position) == '\n'.code) {
            position++
        }
        lineStart = position
    }

    /** Skips inline spaces and tabs. */
    private fun skipInlineWhitespace() {
        while (current() == ' '.code || current() == '\t'.code) {
            position++
        }
    }

    /** Skips separation and comments (multi-line) at the current position,
     * leaving the parser at the first content char or line start. */
    private fun skipSeparationAndComments() {
        while (true) {
            while (current() == ' '.code || current() == '\t'.code) {
                position++
            }
            if (current() == '#'.code) {
                skipToNextLine()
                continue
            }
            if (current() == '\r'.code || current() == '\n'.code) {
                consumeLineBreak()
                continue
            }
            break
        }
    }

    /** Consumes the rest of the current line including its line break. */
    private fun skipToNextLine() {
        skipToNextLineFrom(position)
        lineStart = position
    }

    /** Consumes the rest of the line starting at [from] including its line
     * break; returns the next line start. */
    private fun skipToNextLineFrom(from: Int): Int {
        var probe = from
        while (probe < chars.size) {
            val character = chars[probe]
            probe++
            if (character == '\r'.code) {
                if (chars.getOrNull(probe) == '\n'.code) {
                    probe++
                }
                break
            }
            if (character == '\n'.code) {
                break
            }
        }
        position = probe
        return probe
    }

    private fun skipFlowTrivia() {
        while (true) {
            while (current() == ' '.code || current() == '\t'.code) {
                position++
            }
            if (current() == '#'.code) {
                skipToNextLine()
                continue
            }
            if (current() == '\r'.code || current() == '\n'.code) {
                consumeLineBreak()
                continue
            }
            break
        }
    }

    private fun skipBlankAndCommentLines() {
        while (true) {
            if (atEnd()) {
                break
            }
            if (current() == '\r'.code || current() == '\n'.code) {
                consumeLineBreak()
                continue
            }
            val contentEnd = lineContentEndFrom(chars, position, nextLineEndFrom(chars, position))
            var probe = position
            while (probe < contentEnd &&
                (chars[probe] == ' '.code || chars[probe] == '\t'.code)
            ) {
                probe++
            }
            if (probe == contentEnd || chars.getOrNull(probe) == '#'.code) {
                skipToNextLine()
                continue
            }
            break
        }
    }

    private fun isBlankLine(start: Int, contentEnd: Int): Boolean =
        isBlankRange(chars, start, contentEnd)

    /** The indent (leading spaces) of the line containing [from]. */
    private fun lineIndentAt(from: Int): Int {
        var lineStartPos = from
        while (lineStartPos > 0 && chars[lineStartPos - 1] != '\r'.code &&
            chars[lineStartPos - 1] != '\n'.code
        ) {
            lineStartPos--
        }
        return leadingSpaces(chars, lineStartPos, lineContentEndFrom(chars, lineStartPos, nextLineEndFrom(chars, lineStartPos)))
    }

    private fun lineContentEnd(): Int =
        lineContentEndFrom(chars, position, nextLineEndFrom(chars, position))

    /** The next non-blank, non-comment content indent at or after the
     * current position; [fallback] when nothing remains. */
    private fun nextContentIndent(fallback: Int): Int {
        var probe = position
        while (probe < chars.size) {
            val lineEnd = nextLineEndFrom(chars, probe)
            val contentEnd = lineContentEndFrom(chars, probe, lineEnd)
            var content = probe
            while (content < contentEnd &&
                (chars[content] == ' '.code || chars[content] == '\t'.code)
            ) {
                content++
            }
            if (content < contentEnd && chars[content] != '#'.code) {
                return content - probe
            }
            probe = lineEnd
        }
        return fallback
    }
}

private fun nextLineEndFrom(chars: IntArray, start: Int): Int {
    var probe = start
    while (probe < chars.size && chars[probe] != '\r'.code && chars[probe] != '\n'.code) {
        probe++
    }
    if (probe < chars.size && chars[probe] == '\r'.code) {
        probe++
        if (probe < chars.size && chars[probe] == '\n'.code) {
            probe++
        }
    } else if (probe < chars.size && chars[probe] == '\n'.code) {
        probe++
    }
    return probe
}

private fun lineContentEndFrom(chars: IntArray, start: Int, lineEnd: Int): Int {
    var end = lineEnd
    if (end > start && chars[end - 1] == '\n'.code) {
        end--
    }
    if (end > start && chars[end - 1] == '\r'.code) {
        end--
    }
    return end
}

private fun leadingSpaces(chars: IntArray, start: Int, contentEnd: Int): Int {
    var indent = 0
    while (start + indent < contentEnd && chars[start + indent] == ' '.code) {
        indent++
    }
    return indent
}

private fun isBlankRange(chars: IntArray, start: Int, contentEnd: Int): Boolean =
    (start..<contentEnd).all { index ->
        chars[index] == ' '.code || chars[index] == '\t'.code
    }

private fun isSeparation(value: Int): Boolean =
    value == ' '.code || value == '\t'.code || value == '\r'.code || value == '\n'.code

private fun isFlowIndicator(value: Int): Boolean =
    value == '['.code || value == ']'.code || value == '{'.code ||
        value == '}'.code || value == ','.code
