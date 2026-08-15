// Formation of `plist.xml@1` documents: the plist value vocabulary expressed
// as XML 1.0, with byte-exact lossless syntax pieces and Complete/Recovered
// outcomes.
//
// Data authority:
//   - RFC 0013 §2.1, §3, §4 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md): the admitted document-entity encodings
//     (UTF-8 optional BOM; UTF-16LE/BE required BOM), the three-way
//     formation outcome, the DOCTYPE identifier contract, the root contract
//     (`<plist version="1.0">` exactly, one value element), the value
//     element vocabulary, the dictionary/key rules, the integer/real/date/
//     data/string grammars, and the trailing-content rule.
//   - RFC 0013 §8.2 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md): the lossless syntax-kind
//     set and the root-tag partition rule.
//   - conformance/vectors/plist-v1.json (plist.xml-formation.* and
//     plist.query.* XML cases) pins the recover/complete outcomes and the
//     diagnostic codes case by case.
//   - https://github.com/consema/consema-rs/blob/main/consema-plist/src/parser_xml.rs is the byte-arbitration
//     authority (element/attribute rules parser_xml.rs, value
//     building parser_xml.rs, text/reference resolution
//     parser_xml.rs, gap assembly parser_xml.rs);
//     consema-go/go/plist is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): a single self-contained
// scanner over the decoded text tracks UTF-16 unit offsets and resolves raw
// byte spans through the SourceSnapshot boundary index (RFC 0003 §5); piece
// classification is the closed PlistSyntaxKind enum; value entities are
// appended in close-tag order so entity indices equal post-order ranks.

package consema.plist

import consema.document.DecodedOffset
import consema.document.DocumentAuthority
import consema.document.FormationStatus
import consema.document.LosslessStructuralIndex
import consema.document.SourceEncoding
import consema.document.SourceLimits
import consema.document.SourceSnapshot
import consema.document.StructuralPiece
import consema.document.StructuralPieceKind
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity
import java.math.BigInteger
import java.util.ArrayDeque

/**
 * Explicit source-encoding selection for plist formation (RFC 0013 §2;
 * https://github.com/consema/consema-rs/blob/main/consema-plist/src/lib.rs). For the XML profile the selection
 * follows the RFC 0012 source contract: no-BOM source defaults to UTF-8, and
 * an explicit caller choice is evidence, not permission to contradict a BOM
 * or a declaration. The binary profile has no text encoding; only
 * [PlistEncodingSelection.ProfileDefault] and Explicit(Binary) are
 * consistent with it.
 */
sealed class PlistEncodingSelection {
    /** Apply only the frozen profile default and BOM rules. */
    data object ProfileDefault : PlistEncodingSelection()

    /** One caller-selected document-entity encoding (XML profile), or
     * [SourceEncoding.Binary] (binary profile). */
    data class Explicit(val encoding: SourceEncoding) : PlistEncodingSelection()
}

/**
 * Forms one `plist.xml@1` or `plist.binary@1` document from raw bytes
 * (RFC 0013 §1, §3; lib.rs). The profile selects the representation;
 * neither the `bplist00` magic number nor a `.plist` extension selects a
 * profile. Fatal failures (limits, source-construction conflicts, encoding
 * conflicts) throw [PlistFormationException]; syntax and value-grammar
 * recovery never throws and produces a Recovered document.
 */
fun parse(
    bytes: ByteArray,
    profile: PlistProfile,
    selection: PlistEncodingSelection = PlistEncodingSelection.ProfileDefault,
    limits: PlistParseLimits = PlistParseLimits.default,
): Document =
    when (profile) {
        PlistProfile.XmlV1 -> parseXml(bytes, selection, limits)
        PlistProfile.BinaryV1 -> parseBinaryEntry(bytes, selection, limits)
    }

/** XML profile formation entry (lib.rs). */
internal fun parseXml(
    bytes: ByteArray,
    selection: PlistEncodingSelection,
    limits: PlistParseLimits,
): Document {
    if (bytes.size > limits.common.maxSourceBytes) {
        throw commonLimit("source-bytes", bytes.size, limits.common.maxSourceBytes)
    }
    if (selection is PlistEncodingSelection.Explicit &&
        selection.encoding !== SourceEncoding.Utf8 &&
        selection.encoding !== SourceEncoding.Utf16Le &&
        selection.encoding !== SourceEncoding.Utf16Be
    ) {
        throw PlistFormationException(
            PlistCodes.XML_ENCODING,
            "plist xml parse: incompatible encoding selection",
        )
    }
    val request = consema.document.EncodingRequest.new(SourceEncoding.Utf8)
        .let { base ->
            when (selection) {
                is PlistEncodingSelection.Explicit -> base.withCallerOverride(selection.encoding)
                PlistEncodingSelection.ProfileDefault -> base
            }
        }
    val source = try {
        SourceSnapshot.fromRaw(
            bytes,
            request,
            SourceLimits(
                maxRawBytes = limits.common.maxSourceBytes,
                maxDecodedUtf8Bytes = limits.maxDecodedUtf8Bytes,
                maxDecodedScalars = limits.maxDecodedScalars,
            ),
        )
    } catch (e: consema.document.SourceException) {
        throw wrapSourceError(e)
    }
    val authority = DocumentAuthority.fresh()
    return XmlParser(source, authority, limits).parse()
}

/** Wraps a source-construction failure with the frozen code mapping of
 * FatalFormationFailure::source_error (RFC 0016 §5.1). */
private fun wrapSourceError(error: consema.document.SourceException): PlistFormationException =
    when (error.kind) {
        consema.document.SourceErrorKind.INVALID_UTF8, consema.document.SourceErrorKind.INVALID_SEQUENCE ->
            PlistFormationException(
                "core.source.invalid-sequence@1",
                "plist xml parse: invalid source sequence",
                cause = error,
            )

        consema.document.SourceErrorKind.ENCODING_CONFLICT ->
            PlistFormationException(
                "core.source.encoding-conflict@1",
                "plist xml parse: source encoding facts conflict",
                cause = error,
            )

        consema.document.SourceErrorKind.UNSUPPORTED_BOM ->
            PlistFormationException(
                "core.source.unsupported-bom@1",
                "plist xml parse: unsupported byte-order mark",
                cause = error,
            )

        consema.document.SourceErrorKind.RESOURCE_LIMIT ->
            PlistFormationException(
                "core.source.resource-limit@1",
                "plist xml parse: source construction limit",
                name = error.name ?: "",
                observed = error.observed,
                limit = error.limit,
                cause = error,
            )

        consema.document.SourceErrorKind.OFFSET_OVERFLOW ->
            PlistFormationException(
                "core.source.resource-limit@1",
                "plist xml parse: source coordinates overflow",
                cause = error,
            )
    }

/** One known plist value element kind (RFC 0013 §4.3). */
internal enum class ElementKind {
    Plist,
    Dict,
    Array,
    String,
    Integer,
    Real,
    True,
    False,
    Date,
    Data,
    Key,
    ;

    fun openKind(): PlistSyntaxKind =
        when (this) {
            Plist -> PlistSyntaxKind.PlistOpen
            Dict -> PlistSyntaxKind.DictOpen
            Array -> PlistSyntaxKind.ArrayOpen
            String -> PlistSyntaxKind.StringOpen
            Integer -> PlistSyntaxKind.IntegerOpen
            Real -> PlistSyntaxKind.RealOpen
            True -> PlistSyntaxKind.True
            False -> PlistSyntaxKind.False
            Date -> PlistSyntaxKind.DateOpen
            Data -> PlistSyntaxKind.DataOpen
            Key -> PlistSyntaxKind.KeyOpen
        }

    fun closeKind(): PlistSyntaxKind =
        when (this) {
            Plist -> PlistSyntaxKind.PlistClose
            Dict -> PlistSyntaxKind.DictClose
            Array -> PlistSyntaxKind.ArrayClose
            String -> PlistSyntaxKind.StringClose
            Integer -> PlistSyntaxKind.IntegerClose
            Real -> PlistSyntaxKind.RealClose
            True -> PlistSyntaxKind.True
            False -> PlistSyntaxKind.False
            Date -> PlistSyntaxKind.DateClose
            Data -> PlistSyntaxKind.DataClose
            Key -> PlistSyntaxKind.KeyClose
        }

    fun isScalar(): Boolean =
        this == String || this == Integer || this == Real || this == Date || this == Data

    companion object {
        fun fromName(name: String): ElementKind? =
            when (name) {
                "plist" -> Plist
                "dict" -> Dict
                "array" -> Array
                "string" -> String
                "integer" -> Integer
                "real" -> Real
                "true" -> True
                "false" -> False
                "date" -> Date
                "data" -> Data
                "key" -> Key
                else -> null
            }
    }
}

/** One open element frame. */
private class Frame(
    val kind: ElementKind?,
    val name: String,
    var tagCursor: Int,
    var valueAllowed: Boolean,
    var selfClosing: Boolean = false,
    var openStartRaw: Int = 0,
    var openEndRaw: Int = 0,
    val content: StringBuilder = StringBuilder(),
    var scalarUnproven: Boolean = false,
    var rootVersion: String? = null,
    val entries: MutableList<Int> = ArrayList(),
    val groups: HashMap<String, Int> = HashMap(),
    val elements: MutableList<Int> = ArrayList(),
    var pendingKey: PendingKey? = null,
    var expectValue: Boolean = false,
    var unknownMarker: Int? = null,
)

/** A pending dictionary key awaiting its value. */
private class PendingKey(
    val keyEntityIndex: Int,
    val keyStartRaw: Int,
    val keyText: String?,
)

/** The self-contained XML scanner and recovery parser. */
internal class XmlParser(
    private val source: SourceSnapshot,
    private val authority: DocumentAuthority,
    private val limits: PlistParseLimits,
) {
    private val text: String = source.decodedText() ?: error("plist xml requires decoded text")
    private var pos = 0
    private val pieces = ArrayList<Pair<SpanPair, PlistSyntaxKind>>()
    private val diagnostics = ArrayList<PlistDiagnostic>()
    private var recovered = false
    private val entities = ArrayList<Entity>()
    private var occurrence = 0L
    private var unknownDepth = 0
    private var anyTopLevel = false
    private var plistRootSeen = false
    private var rootValueCount = 0
    private var rootValueRef: Int? = null
    private val stack = ArrayDeque<Frame>()
    private var rootClosed = false

    fun parse(): Document {
        coverBom()
        while (pos < text.length) {
            if (unknownDepth > 0) {
                // Everything inside an unknown subtree is skipped; the whole
                // tail becomes one error region at close or finish.
                pos += 1
                continue
            }
            if (text[pos] != '<') {
                scanText()
                continue
            }
            when {
                text.startsWith("<!--", pos) -> comment()
                text.startsWith("<![CDATA[", pos) -> cdata()
                text.startsWith("<?", pos) -> {
                    if (text.startsWith("<?xml", pos) && isNameBoundary(text, pos + 5)) {
                        declaration()
                    } else {
                        processingInstruction()
                    }
                }
                text.startsWith("<!DOCTYPE", pos) && isNameBoundary(text, pos + 9) -> doctype()
                text.startsWith("</", pos) -> closeTag()
                else -> openTag()
            }
        }
        return finish()
    }

    // ------------------------------------------------------------------
    // Text scanning
    // ------------------------------------------------------------------

    /** One text run: scalar content accumulates (with reference resolution),
     * container/outside text is trivia or a recovery diagnostic
     * (parser_xml.rs). */
    private fun scanText() {
        val start = pos
        skipUntil('<')
        val end = pos
        val frame = stack.peek()
        val kind = frame?.kind
        when {
            stack.isEmpty() -> {
                if (isWhitespaceOnly(start, end)) {
                    pushWhitespacePieces(start, end)
                } else if (rootClosed) {
                    // Trailing content after `</plist>` (RFC 0013 §4.10).
                    recoverErrorRegion(start, end)
                } else {
                    errorRegionDiagnostic(start, end, PlistCodes.PARSE_TEXT_OUTSIDE_VALUE,
                        DiagnosticCategory.Syntax)
                }
            }
            kind == ElementKind.True || kind == ElementKind.False -> {
                if (isWhitespaceOnly(start, end)) {
                    pushWhitespacePieces(start, end)
                } else {
                    errorRegionDiagnostic(start, end, PlistCodes.PARSE_BOOLEAN_CONTENT,
                        DiagnosticCategory.Syntax)
                    frame!!.scalarUnproven = true
                }
            }
            kind != ElementKind.Dict && kind != ElementKind.Array && kind != ElementKind.Plist -> {
                frame!!.content.append(resolveFragments(start, end, emitPieces = true))
            }
            else -> {
                if (isWhitespaceOnly(start, end)) {
                    pushWhitespacePieces(start, end)
                } else {
                    errorRegionDiagnostic(start, end, PlistCodes.PARSE_TEXT_OUTSIDE_VALUE,
                        DiagnosticCategory.Syntax)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Markup handlers
    // ------------------------------------------------------------------

    /** `<!-- ... -->` trivia pieces (parser_xml.rs). */
    private fun comment() {
        val openStart = pos
        val closeAt = text.indexOf("-->", pos + 4)
        if (closeAt < 0) {
            recoverErrorRegion(openStart, text.length)
            pos = text.length
            return
        }
        pos = closeAt + 3
        pushPiece(openStart, openStart + 4, PlistSyntaxKind.CommentOpen, StructuralPieceKind.Trivia)
        if (closeAt > openStart + 4) {
            pushPiece(openStart + 4, closeAt, PlistSyntaxKind.CommentText, StructuralPieceKind.Trivia)
        }
        pushPiece(closeAt, closeAt + 3, PlistSyntaxKind.CommentClose, StructuralPieceKind.Trivia)
    }

    /** `<![CDATA[ ... ]]>` (parser_xml.rs). */
    private fun cdata() {
        val openStart = pos
        val closeAt = text.indexOf("]]>", pos + 9)
        if (closeAt < 0) {
            recoverErrorRegion(openStart, text.length)
            pos = text.length
            return
        }
        pos = closeAt + 3
        val frame = stack.peek()
        val kind = frame?.kind
        when {
            kind == null || kind == ElementKind.Dict || kind == ElementKind.Array ||
                kind == ElementKind.Plist ->
                errorRegionDiagnostic(openStart, pos, PlistCodes.PARSE_TEXT_OUTSIDE_VALUE,
                    DiagnosticCategory.Syntax)

            kind == ElementKind.True || kind == ElementKind.False -> {
                errorRegionDiagnostic(openStart, pos, PlistCodes.PARSE_BOOLEAN_CONTENT,
                    DiagnosticCategory.Syntax)
                frame!!.scalarUnproven = true
            }
            else -> {
                pushPiece(openStart, openStart + 9, PlistSyntaxKind.CdataOpen, StructuralPieceKind.Token)
                if (closeAt > openStart + 9) {
                    pushPiece(openStart + 9, closeAt, PlistSyntaxKind.CdataText, StructuralPieceKind.Token)
                }
                pushPiece(closeAt, closeAt + 3, PlistSyntaxKind.CdataClose, StructuralPieceKind.Token)
                frame!!.content.append(normalizeLineEnds(text.substring(openStart + 9, closeAt)))
            }
        }
    }

    /** `<?xml ... ?>` declaration (parser_xml.rs). */
    private fun declaration() {
        val openStart = pos
        val closeAt = text.indexOf("?>", pos + 5)
        if (closeAt < 0) {
            recoverErrorRegion(openStart, text.length)
            pos = text.length
            return
        }
        pos = closeAt + 2
        pushPiece(openStart, openStart + 5, PlistSyntaxKind.DeclarationOpen, StructuralPieceKind.Token)
        var cursor = openStart + 5
        var version: String? = null
        var declaredEncoding: String? = null
        while (true) {
            cursor = skipDeclarationSpaces(cursor, closeAt)
            if (cursor >= closeAt) {
                break
            }
            val nameStart = cursor
            while (cursor < closeAt && isNameChar(text[cursor])) {
                cursor += 1
            }
            val nameEnd = cursor
            val name = text.substring(nameStart, nameEnd)
            cursor = skipDeclarationSpaces(cursor, closeAt)
            if (cursor < closeAt && text[cursor] == '=') {
                cursor += 1
                cursor = skipDeclarationSpaces(cursor, closeAt)
            }
            val value = if (cursor < closeAt && (text[cursor] == '"' || text[cursor] == '\'')) {
                val quote = text[cursor]
                val valueStart = cursor
                cursor += 1
                while (cursor < closeAt && text[cursor] != quote) {
                    cursor += 1
                }
                val valueText = text.substring(valueStart + 1, cursor)
                if (cursor < closeAt) {
                    cursor += 1
                }
                pushPiece(valueStart, cursor, PlistSyntaxKind.DeclarationValue, StructuralPieceKind.Token)
                valueText
            } else {
                ""
            }
            when (name) {
                "version" -> {
                    pushPiece(nameStart, nameEnd, PlistSyntaxKind.DeclarationName,
                        StructuralPieceKind.Token)
                    version = value
                }
                "encoding" -> {
                    pushPiece(nameStart, nameEnd, PlistSyntaxKind.DeclarationName,
                        StructuralPieceKind.Token)
                    declaredEncoding = value
                }
                "standalone" -> {
                    pushPiece(nameStart, nameEnd, PlistSyntaxKind.DeclarationName,
                        StructuralPieceKind.Token)
                }
                else -> {}
            }
        }
        if (version != null && version != "1.0") {
            recover(PlistCodes.PARSE_DECLARATION_VERSION, DiagnosticCategory.Syntax,
                arguments = mapOf("version" to version!!))
        }
        if (declaredEncoding != null) {
            val upper = declaredEncoding.uppercase()
            val selected = source.encodingFacts.selected
            val agrees = when (selected) {
                SourceEncoding.Utf8 -> upper == "UTF-8"
                SourceEncoding.Utf16Le -> upper == "UTF-16" || upper == "UTF-16LE"
                SourceEncoding.Utf16Be -> upper == "UTF-16" || upper == "UTF-16BE"
                else -> false
            }
            if (!agrees) {
                recover(PlistCodes.PARSE_DECLARATION_CONFLICT, DiagnosticCategory.Encoding,
                    arguments = mapOf("declared" to declaredEncoding!!, "selected" to selected.asStr()))
            }
        }
        pushPiece(closeAt, closeAt + 2, PlistSyntaxKind.DeclarationClose, StructuralPieceKind.Token)
    }

    /** `<?target ... ?>` processing instruction (parser_xml.rs). */
    private fun processingInstruction() {
        val openStart = pos
        val closeAt = text.indexOf("?>", pos + 2)
        if (closeAt < 0) {
            recoverErrorRegion(openStart, text.length)
            pos = text.length
            return
        }
        pos = closeAt + 2
        pushPiece(openStart, openStart + 2, PlistSyntaxKind.ProcessingInstructionOpen,
            StructuralPieceKind.Trivia)
        var cursor = openStart + 2
        while (cursor < closeAt && (text[cursor] == ' ' || text[cursor] == '\t')) {
            cursor += 1
        }
        val targetStart = cursor
        while (cursor < closeAt && isNameChar(text[cursor])) {
            cursor += 1
        }
        val target = text.substring(targetStart, cursor)
        if (target.isNotEmpty()) {
            pushPiece(targetStart, cursor, PlistSyntaxKind.ProcessingInstructionTarget,
                StructuralPieceKind.Trivia)
        }
        if (target.equals("xml", ignoreCase = true)) {
            recover(PlistCodes.PARSE_PI_TARGET, DiagnosticCategory.Syntax)
        }
        if (cursor < closeAt) {
            pushPiece(cursor, closeAt, PlistSyntaxKind.ProcessingInstructionContent,
                StructuralPieceKind.Trivia)
        }
        pushPiece(closeAt, closeAt + 2, PlistSyntaxKind.ProcessingInstructionClose,
            StructuralPieceKind.Trivia)
    }

    /** `<!DOCTYPE ...>` with the frozen Apple identifier contract
     * (parser_xml.rs; RFC 0013 §4.1). */
    private fun doctype() {
        val openStart = pos
        val closeAt = findDoctypeEnd(openStart)
        if (closeAt < 0) {
            recoverErrorRegion(openStart, text.length)
            pos = text.length
            return
        }
        val bodyEnd = closeAt
        pos = closeAt + 1
        pushPiece(openStart, openStart + 9, PlistSyntaxKind.DoctypeOpen, StructuralPieceKind.Token)
        val body = text.substring(openStart + 9, bodyEnd)
        validateDoctype(body)
        if (body.contains('[')) {
            recover(PlistCodes.PARSE_DOCTYPE_SUBSET, DiagnosticCategory.Syntax)
        }
        if (bodyEnd > openStart + 9) {
            pushPiece(openStart + 9, bodyEnd, PlistSyntaxKind.DoctypeBody, StructuralPieceKind.Token)
        }
        pushPiece(bodyEnd, bodyEnd + 1, PlistSyntaxKind.DoctypeClose, StructuralPieceKind.Token)
    }

    /** Locates the `>` that ends the DOCTYPE, honoring the internal subset's
     * `]` and quoted strings. */
    private fun findDoctypeEnd(start: Int): Int {
        var cursor = start + 9
        var inQuote = false
        var quoteChar = ' '
        var bracketDepth = 0
        while (cursor < text.length) {
            val ch = text[cursor]
            if (inQuote) {
                if (ch == quoteChar) inQuote = false
            } else if (ch == '"' || ch == '\'') {
                inQuote = true
                quoteChar = ch
            } else if (ch == '[') {
                bracketDepth += 1
            } else if (ch == ']') {
                bracketDepth -= 1
            } else if (ch == '>' && bracketDepth <= 0) {
                return cursor
            }
            cursor += 1
        }
        return -1
    }

    /** Validates the DOCTYPE body against the frozen Apple identifier
     * (RFC 0013 §4.1; parser_xml.rs). */
    private fun validateDoctype(body: String) {
        var cursor = 0
        cursor = skipWs(body, cursor)
        val nameStart = cursor
        while (cursor < body.length && isNameChar(body[cursor])) {
            cursor += 1
        }
        val name = body.substring(nameStart, cursor)
        if (name != "plist") {
            recover(PlistCodes.PARSE_DOCTYPE, DiagnosticCategory.Syntax,
                arguments = mapOf("name" to name))
        }
        cursor = skipWs(body, cursor)
        val kindStart = cursor
        while (cursor < body.length && isNameChar(body[cursor])) {
            cursor += 1
        }
        val kind = body.substring(kindStart, cursor)
        when (kind) {
            "PUBLIC" -> {
                cursor = skipWs(body, cursor)
                val publicId = readQuoted(body, cursor)
                if (publicId == null) {
                    recover(PlistCodes.PARSE_DOCTYPE, DiagnosticCategory.Syntax)
                    return
                }
                cursor = skipWs(body, publicId.second)
                val systemId = readQuoted(body, cursor)
                if (systemId == null) {
                    recover(PlistCodes.PARSE_DOCTYPE, DiagnosticCategory.Syntax)
                    return
                }
                if (publicId.first != "-//Apple//DTD PLIST 1.0//EN" ||
                    systemId.first != "http://www.apple.com/DTDs/PropertyList-1.0.dtd"
                ) {
                    recover(PlistCodes.PARSE_DOCTYPE, DiagnosticCategory.Syntax)
                }
            }
            "SYSTEM" -> recover(PlistCodes.PARSE_DOCTYPE, DiagnosticCategory.Syntax)
            else -> if (kind.isNotEmpty()) {
                recover(PlistCodes.PARSE_DOCTYPE, DiagnosticCategory.Syntax)
            }
        }
    }

    /** `</name>` close tag (parser_xml.rs). */
    private fun closeTag() {
        val openStart = pos
        val end = findTagEnd(openStart + 2)
        if (end < 0) {
            recoverErrorRegion(openStart, text.length)
            pos = text.length
            return
        }
        pos = end + 1
        val closeName = text.substring(openStart + 2, end).trim()
        val frame = stack.peek()
        if (frame != null && frame.kind != null && frame.name != closeName) {
            recover(
                PlistCodes.PARSE_MISMATCHED_END_TAG,
                DiagnosticCategory.Syntax,
                arguments = mapOf("expected" to frame.name, "found" to closeName),
            )
        }
        if (unknownDepth == 0 && frame != null) {
            val kindPiece = frame.kind?.closeKind() ?: PlistSyntaxKind.ErrorRegion
            pushPiece(openStart, end + 1, kindPiece, StructuralPieceKind.Token)
        }
        closeFrame(end + 1)
    }

    /** `<name ...>` or `<name .../>` open tag (parser_xml.rs). */
    private fun openTag() {
        val openStart = pos
        pos += 1
        val nameStart = pos
        while (pos < text.length && isNameChar(text[pos])) {
            pos += 1
        }
        val nameEnd = pos
        val rawName = text.substring(nameStart, nameEnd)
        val prefixed = rawName.contains(':')
        val name = rawName.substringAfterLast(':')
        val isRoot = stack.isEmpty() && !rootClosed
        val known = ElementKind.fromName(name)
        val isUnknown = prefixed || (isRoot && known != ElementKind.Plist) ||
            (!isRoot && known == ElementKind.Plist) || known == null
        if (isRoot) {
            anyTopLevel = true
        }
        if (isRoot && !prefixed && known == ElementKind.Plist) {
            plistRootSeen = true
        }
        if (rootClosed) {
            // Content after `</plist>`: only whitespace, comments, and PIs
            // are admitted (RFC 0013 §4.10).
            val tagEnd = findTagEnd(pos)
            if (tagEnd < 0) {
                recoverErrorRegion(openStart, text.length)
                pos = text.length
                return
            }
            recoverErrorRegion(openStart, tagEnd + 1)
            pos = tagEnd + 1
            return
        }

        // Placement checks (parser_xml.rs).
        var valueAllowed = !isUnknown
        var scalarViolation = false
        if (!isUnknown) {
            val parent = stack.peek()
            val parentKind = parent?.kind
            val parentAllowed = parent == null || parent.valueAllowed
            val parentExpectValue = parent?.expectValue == true
            val parentScalar = parentKind != null && parentKind.isScalar()
            valueAllowed = parentAllowed
            when (known) {
                ElementKind.Plist -> {}
                ElementKind.Key -> when (parentKind) {
                    ElementKind.Dict -> if (parentAllowed && parentExpectValue) {
                        recover(PlistCodes.PARSE_DICT_MISSING_VALUE, DiagnosticCategory.Syntax)
                    }
                    ElementKind.Plist, ElementKind.Array -> recover(
                        PlistCodes.PARSE_KEY_OUTSIDE_DICT,
                        DiagnosticCategory.Syntax,
                        arguments = mapOf("name" to name),
                    )
                    null -> {}
                    else -> scalarViolation = true
                }
                ElementKind.Dict, ElementKind.Array -> if (parentScalar) scalarViolation = true
                else -> when (parentKind) {
                    ElementKind.Dict -> if (parentAllowed && !parentExpectValue) {
                        recover(
                            PlistCodes.PARSE_DICT_KEY,
                            DiagnosticCategory.Syntax,
                            arguments = mapOf("element" to name),
                        )
                    }
                    ElementKind.Plist, ElementKind.Array -> {}
                    null -> {}
                    else -> scalarViolation = true
                }
            }
        }
        if (scalarViolation) {
            recover(
                PlistCodes.PARSE_SCALAR_CONTENT,
                DiagnosticCategory.Syntax,
                arguments = mapOf("element" to name),
            )
            stack.peek()?.scalarUnproven = true
            valueAllowed = false
        }
        if (isUnknown && unknownDepth == 0) {
            recover(
                PlistCodes.PARSE_ELEMENT_NAME,
                DiagnosticCategory.Syntax,
                arguments = mapOf("name" to rawName),
            )
        }
        if (unknownDepth == 0 && !isUnknown) {
            pushPiece(openStart, nameEnd, known!!.openKind(), StructuralPieceKind.Token)
        }
        val frame = Frame(
            kind = known,
            name = rawName,
            tagCursor = pos,
            valueAllowed = valueAllowed,
        )
        frame.openStartRaw = rawAt(openStart)
        if (isUnknown && unknownDepth == 0) {
            frame.unknownMarker = openStart
        }
        stack.push(frame)
        if (isUnknown) {
            unknownDepth += 1
            // Consume the tag without pieces; the subtree closes at its close
            // tag or at finish.
            while (pos < text.length && text[pos] != '>') {
                pos += 1
            }
            if (pos < text.length) {
                pos += 1
            }
            return
        }

        // Attributes (parser_xml.rs).
        var tagSelfClosing = false
        while (pos < text.length && text[pos] != '>') {
            val wsStart = pos
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' ||
                    text[pos] == '\r' || text[pos] == '\n')
            ) {
                pos += 1
            }
            if (wsStart < pos) {
                pushWhitespacePieces(wsStart, pos)
            }
            if (pos >= text.length || text[pos] == '>') {
                break
            }
            if (text[pos] == '/') {
                pos += 1
                tagSelfClosing = true
                break
            }
            val attrNameStart = pos
            while (pos < text.length && isNameChar(text[pos])) {
                pos += 1
            }
            val attrNameEnd = pos
            val attrName = text.substring(attrNameStart, attrNameEnd)
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' ||
                    text[pos] == '\r' || text[pos] == '\n')
            ) {
                pos += 1
            }
            if (pos < text.length && text[pos] == '=') {
                pos += 1
                while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' ||
                        text[pos] == '\r' || text[pos] == '\n')
                ) {
                    pos += 1
                }
            }
            val valueStart = pos
            if (pos < text.length && (text[pos] == '"' || text[pos] == '\'')) {
                val quote = text[pos]
                pos += 1
                while (pos < text.length && text[pos] != quote) {
                    pos += 1
                }
                if (pos < text.length) {
                    pos += 1
                }
            }
            val valueEnd = pos
            val isRootFrame = isRoot && frame.kind == ElementKind.Plist
            val versionUnset = frame.rootVersion == null
            val isVersion = isRootFrame && versionUnset && attrName == "version"
            if (isVersion) {
                pushPiece(attrNameStart, attrNameEnd, PlistSyntaxKind.PlistVersionName,
                    StructuralPieceKind.Token)
                val eqAt = findEquals(attrNameEnd, valueStart)
                pushPiece(eqAt, valueEnd, PlistSyntaxKind.PlistVersionValue, StructuralPieceKind.Token)
                val rawValue = if (valueEnd > valueStart + 1) {
                    text.substring(valueStart + 1, valueEnd - 1)
                } else {
                    ""
                }
                val normalized = normalizeAttributeValue(rawValue)
                if (normalized != "1.0") {
                    recover(
                        PlistCodes.PARSE_ROOT_VERSION,
                        DiagnosticCategory.Syntax,
                        arguments = mapOf("version" to normalized),
                    )
                }
                frame.rootVersion = normalized
            } else {
                pushPiece(attrNameStart, valueEnd, PlistSyntaxKind.ErrorRegion,
                    StructuralPieceKind.ErrorRegion)
                val code = if (isRootFrame) {
                    PlistCodes.PARSE_ROOT_ATTRIBUTE
                } else {
                    PlistCodes.PARSE_ELEMENT_ATTRIBUTE
                }
                recover(code, DiagnosticCategory.Syntax, arguments = mapOf("name" to attrName))
            }
        }
        val selfClosing = tagSelfClosing
        if (pos < text.length && text[pos] == '>') {
            pos += 1
        }
        val tagEnd = pos
        if (selfClosing) {
            // The `/>` is one piece of the close kind; the open kind has no
            // separate `>` piece (parser_xml.rs).
            pushPiece(tagEnd - 2, tagEnd, frame.kind!!.closeKind(), StructuralPieceKind.Token)
        } else {
            pushPiece(tagEnd - 1, tagEnd, frame.kind!!.openKind(), StructuralPieceKind.Token)
        }
        frame.tagCursor = tagEnd
        frame.openEndRaw = rawAt(tagEnd)
        if (selfClosing) {
            frame.selfClosing = true
            if (isRoot && frame.kind == ElementKind.Plist && frame.rootVersion == null) {
                recover(PlistCodes.PARSE_ROOT_VERSION, DiagnosticCategory.Syntax,
                    arguments = mapOf("version" to "<missing>"))
            }
            closeFrame(tagEnd)
        } else if (isRoot && frame.kind == ElementKind.Plist && frame.rootVersion == null) {
            recover(PlistCodes.PARSE_ROOT_VERSION, DiagnosticCategory.Syntax,
                arguments = mapOf("version" to "<missing>"))
        }
    }

    // ------------------------------------------------------------------
    // Frame close and value building
    // ------------------------------------------------------------------

    private fun closeFrame(end: Int) {
        val frame = stack.poll()
        if (frame == null) {
            recover(PlistCodes.PARSE_EXTRA_END_TAG, DiagnosticCategory.Syntax)
            return
        }
        if (frame.unknownMarker != null) {
            pushPiece(frame.unknownMarker!!, end, PlistSyntaxKind.ErrorRegion,
                StructuralPieceKind.ErrorRegion)
        }
        val kind = frame.kind
        if (kind == null) {
            unknownDepth -= 1
            return
        }
        if (kind == ElementKind.Key) {
            val units = encodeUtf16(frame.content.toString())
            checkLimitUnits(units.size)
            if (frame.valueAllowed) {
                val native = if (frame.scalarUnproven) {
                    null
                } else {
                    NativeValue.StringV(PlistString.fromCodeUnits(units))
                }
                val keyEntity = addEntity(
                    Entity.Value(
                        ValueEntity(
                            authority.span(frame.openStartRaw, rawAt(end)),
                            native,
                            isKey = true,
                        ),
                    ),
                )
                val parent = stack.peek()
                if (parent != null && parent.valueAllowed && parent.kind == ElementKind.Dict) {
                    parent.pendingKey = PendingKey(
                        keyEntity,
                        frame.openStartRaw,
                        (native as? NativeValue.StringV)?.string?.toUnicode(),
                    )
                    parent.expectValue = true
                }
            }
            return
        }
        val valueEntityIndex = if (frame.valueAllowed) {
            buildValue(frame, end)
        } else {
            null
        }
        var missingValue = false
        val parent = stack.peek()
        if (parent != null) {
            when (parent.kind) {
                ElementKind.Plist -> {
                    rootValueCount += 1
                    if (valueEntityIndex != null && rootValueRef == null) {
                        rootValueRef = valueEntityIndex
                    }
                }
                ElementKind.Dict -> {
                    if (parent.expectValue) {
                        parent.expectValue = false
                        val pending = parent.pendingKey
                        parent.pendingKey = null
                        when {
                            pending == null -> missingValue = true
                            valueEntityIndex == null -> missingValue = true
                            else -> {
                                val groupKey = pending.keyText ?: ""
                                val group = parent.groups.getOrPut(groupKey) { 0 } + 1
                                parent.groups[groupKey] = group
                                if (group > limits.maxDuplicateKeyGroupMembers) {
                                    throw plistLimit("duplicate-key-group", group,
                                        limits.maxDuplicateKeyGroupMembers)
                                }
                                if (parent.entries.size >= limits.maxDictEntries) {
                                    throw plistLimit("dict-entries", parent.entries.size + 1,
                                        limits.maxDictEntries)
                                }
                                val entryIndex = entities.size
                                addEntity(
                                    Entity.DictEntry(
                                        DictEntryEntity(
                                            authority.span(pending.keyStartRaw, rawAt(end)),
                                            pending.keyEntityIndex,
                                            valueEntityIndex,
                                            parent.entries.size,
                                        ),
                                    ),
                                )
                                parent.entries.add(entryIndex)
                            }
                        }
                    }
                }
                ElementKind.Array -> {
                    if (valueEntityIndex != null) {
                        if (parent.elements.size >= limits.maxArrayElements) {
                            throw plistLimit("array-elements", parent.elements.size + 1,
                                limits.maxArrayElements)
                        }
                        val elementIndex = entities.size
                        addEntity(
                            Entity.ArrayElement(
                                ArrayElementEntity(
                                    authority.span(frame.openStartRaw, rawAt(end)),
                                    valueEntityIndex,
                                    parent.elements.size,
                                ),
                            ),
                        )
                        parent.elements.add(elementIndex)
                    }
                }
                else -> {}
            }
        }
        if (missingValue) {
            recover(PlistCodes.PARSE_DICT_MISSING_VALUE, DiagnosticCategory.Syntax)
        }
        if (kind == ElementKind.Plist) {
            rootClosed = true
        }
    }

    /** Builds the native value of one closing element and adds its entity
     * (parser_xml.rs). Returns the entity index, or null when the
     * value is unproven. The entity span is the full element, open tag
     * through close tag (the edit layer replaces whole elements; the span
     * convention matches the Rust edit layout, edit.rs). */
    private fun buildValue(frame: Frame, end: Int): Int? {
        val endRaw = rawAt(end)
        val startRaw = frame.openStartRaw
        val span = authority.span(startRaw, endRaw)
        val native: NativeValue? = when (frame.kind) {
            ElementKind.Dict -> {
                if (frame.expectValue) {
                    recover(PlistCodes.PARSE_DICT_MISSING_VALUE, DiagnosticCategory.Syntax)
                }
                NativeValue.Dict(frame.entries.toList())
            }
            ElementKind.Array -> NativeValue.Array(frame.elements.toList())
            ElementKind.String, ElementKind.Key -> {
                if (frame.scalarUnproven) {
                    null
                } else {
                    val units = encodeUtf16(frame.content.toString())
                    checkLimitUnits(units.size)
                    NativeValue.StringV(PlistString.fromCodeUnits(units))
                }
            }
            ElementKind.Integer -> {
                if (frame.scalarUnproven) {
                    null
                } else {
                    val content = trimXmlWhitespace(frame.content.toString())
                    if (content.isEmpty()) {
                        recover(PlistCodes.PARSE_EMPTY_VALUE, DiagnosticCategory.Syntax,
                            arguments = mapOf("element" to "integer"))
                        null
                    } else {
                        parseInteger(content)?.let { NativeValue.Integer(it) } ?: run {
                            recover(PlistCodes.PARSE_INTEGER, DiagnosticCategory.Syntax)
                            null
                        }
                    }
                }
            }
            ElementKind.Real -> {
                if (frame.scalarUnproven) {
                    null
                } else {
                    val content = trimXmlWhitespace(frame.content.toString())
                    if (content.isEmpty()) {
                        recover(PlistCodes.PARSE_EMPTY_VALUE, DiagnosticCategory.Syntax,
                            arguments = mapOf("element" to "real"))
                        null
                    } else {
                        parseReal(content)?.let { NativeValue.Real(PlistReal.double(it.toBits())) }
                            ?: run {
                                recover(PlistCodes.PARSE_REAL, DiagnosticCategory.Syntax)
                                null
                            }
                    }
                }
            }
            ElementKind.Date -> {
                if (frame.scalarUnproven) {
                    null
                } else {
                    val content = trimXmlWhitespace(frame.content.toString())
                    if (content.isEmpty()) {
                        recover(PlistCodes.PARSE_EMPTY_VALUE, DiagnosticCategory.Syntax,
                            arguments = mapOf("element" to "date"))
                        null
                    } else {
                        parseDate(content)?.let { NativeValue.Date(it) } ?: run {
                            recover(PlistCodes.PARSE_DATE, DiagnosticCategory.Syntax)
                            null
                        }
                    }
                }
            }
            ElementKind.Data -> {
                if (frame.scalarUnproven) {
                    null
                } else {
                    val content = frame.content.toString()
                    if (content.isEmpty() && frame.selfClosing) {
                        recover(PlistCodes.PARSE_EMPTY_VALUE, DiagnosticCategory.Syntax,
                            arguments = mapOf("element" to "data"))
                        null
                    } else {
                        decodeBase64(content)?.let { NativeValue.Data(PlistData.fromBytes(it)) }
                            ?: run {
                                recover(PlistCodes.PARSE_DATA, DiagnosticCategory.Syntax)
                                null
                            }
                    }
                }
            }
            ElementKind.True -> if (frame.scalarUnproven) null else NativeValue.BooleanV(true)
            ElementKind.False -> if (frame.scalarUnproven) null else NativeValue.BooleanV(false)
            ElementKind.Plist -> null
            null -> null
        }
        if (native == null) {
            return null
        }
        return addEntity(Entity.Value(ValueEntity(span, native)))
    }

    private fun addEntity(entity: Entity): Int {
        if (entities.size >= limits.maxObjectCount) {
            throw plistLimit("object-count", entities.size + 1, limits.maxObjectCount)
        }
        entities.add(entity)
        return entities.size - 1
    }

    // ------------------------------------------------------------------
    // Text and reference resolution
    // ------------------------------------------------------------------

    /** Splits one decoded run into Text/CharacterReference/EntityReference
     * pieces and returns the resolved normalized content (parser_xml.rs). */
    private fun resolveFragments(start: Int, end: Int, emitPieces: Boolean): String =
        resolveFragmentSegment(text.substring(start, end), start, emitPieces)

    private fun resolveFragmentSegment(segment: String, baseUnit: Int, emitPieces: Boolean): String {
        if ('&' !in segment) {
            if (emitPieces && segment.isNotEmpty()) {
                pushPiece(baseUnit, baseUnit + segment.length, PlistSyntaxKind.Text,
                    StructuralPieceKind.Token)
            }
            return normalizeLineEnds(segment)
        }
        val content = StringBuilder()
        var cursor = 0
        var index = 0
        while (index < segment.length) {
            val at = segment.indexOf('&', index)
            if (at < 0) {
                break
            }
            if (at > cursor) {
                if (emitPieces) {
                    pushPiece(baseUnit + cursor, baseUnit + at, PlistSyntaxKind.Text,
                        StructuralPieceKind.Token)
                }
                content.append(normalizeLineEnds(segment.substring(cursor, at)))
            }
            val semi = segment.indexOf(';', at + 1)
            if (semi < 0) {
                // Unterminated reference: recover and keep the rest literal.
                recover(PlistCodes.PARSE_REFERENCE, DiagnosticCategory.Syntax)
                if (emitPieces) {
                    pushPiece(baseUnit + at, baseUnit + segment.length, PlistSyntaxKind.Text,
                        StructuralPieceKind.Token)
                }
                content.append(normalizeLineEnds(segment.substring(at)))
                return content.toString()
            }
            val body = segment.substring(at + 1, semi)
            val resolved = resolveReference(body)
            if (resolved != null) {
                if (emitPieces) {
                    val kind = if (body.startsWith('#')) {
                        PlistSyntaxKind.CharacterReference
                    } else {
                        PlistSyntaxKind.EntityReference
                    }
                    pushPiece(baseUnit + at, baseUnit + semi + 1, kind, StructuralPieceKind.Token)
                }
                content.append(resolved)
            }
            cursor = semi + 1
            index = semi + 1
        }
        if (cursor < segment.length) {
            if (emitPieces) {
                pushPiece(baseUnit + cursor, baseUnit + segment.length, PlistSyntaxKind.Text,
                    StructuralPieceKind.Token)
            }
            content.append(normalizeLineEnds(segment.substring(cursor)))
        }
        return content.toString()
    }

    /** Resolves one `&...;` reference body; null is a recovered failure that
     * contributes nothing (parser_xml.rs). */
    private fun resolveReference(body: String): Char? {
        if (body.startsWith('#')) {
            val digits = body.substring(1)
            val isHex = digits.startsWith('x') || digits.startsWith('X')
            val valueDigits = if (isHex) digits.substring(1) else digits
            val valid = if (isHex) {
                valueDigits.isNotEmpty() &&
                    valueDigits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            } else {
                valueDigits.isNotEmpty() && valueDigits.all { it.isDigit() }
            }
            val value = if (valid) {
                valueDigits.toLongOrNull(if (isHex) 16 else 10)
            } else {
                null
            }
            val resolved = value?.takeIf { it in 0..0x10FFFF }?.toInt()?.toChar()
                ?.takeIf { isXmlChar(it) }
            if (resolved == null) {
                recover(PlistCodes.PARSE_REFERENCE, DiagnosticCategory.Syntax)
            }
            return resolved
        }
        if (body.isEmpty()) {
            recover(PlistCodes.PARSE_REFERENCE, DiagnosticCategory.Syntax)
            return null
        }
        return when (body) {
            "lt" -> '<'
            "gt" -> '>'
            "amp" -> '&'
            "apos" -> '\''
            "quot" -> '"'
            else -> {
                recover(PlistCodes.PARSE_ENTITY, DiagnosticCategory.Conformance,
                    arguments = mapOf("name" to body))
                null
            }
        }
    }

    // ------------------------------------------------------------------
    // Value grammars (RFC 0013 §4.5-§4.8; parser_xml.rs)
    // ------------------------------------------------------------------

    /** Integer grammar: `S*(-|+)?S*[0-9]+` or `S*(-|+)?S*0[xX][0-9a-fA-F]+`,
     * signed 64-bit range (parser_xml.rs). */
    internal fun parseInteger(content: String): Long? {
        var index = 0
        var negative = false
        if (content.isNotEmpty() && (content[0] == '-' || content[0] == '+')) {
            negative = content[0] == '-'
            index = 1
        }
        while (index < content.length && isWsChar(content[index])) {
            index += 1
        }
        val hex = index + 1 < content.length && content[index] == '0' &&
            (content[index + 1] == 'x' || content[index + 1] == 'X')
        val start = if (hex) index + 2 else index
        var end = start
        while (end < content.length && if (hex) {
                content[end].isDigit() || content[end].lowercaseChar() in 'a'..'f'
            } else {
                content[end].isDigit()
            }
        ) {
            end += 1
        }
        if (end == start) {
            return null
        }
        while (end < content.length && isWsChar(content[end])) {
            end += 1
        }
        if (end != content.length) {
            return null
        }
        val digits = content.substring(start, end)
        // The per-parser number-digit cap (common ParseLimits.
        // maxNumberDigits, default 100,000 — the json/hcl checkNumberDigits
        // shape, wave 4/5): the check runs before the O(N²) BigInteger
        // construction, and an over-limit literal is a fatal
        // resource-limit failure (RFC 0016 §5.1) — never a crash and never
        // a silent recovery to a Recovered document (hard gate 4).
        if (digits.length > limits.common.maxNumberDigits) {
            throw commonLimit("number-digits", digits.length, limits.common.maxNumberDigits)
        }
        val magnitude = try {
            BigInteger(digits, if (hex) 16 else 10)
        } catch (e: NumberFormatException) {
            return null
        }
        val twoTo63 = BigInteger.ONE.shiftLeft(63)
        return if (negative) {
            when {
                magnitude > twoTo63 -> null
                magnitude == twoTo63 -> Long.MIN_VALUE
                else -> -magnitude.toLong()
            }
        } else {
            if (magnitude >= twoTo63) null else magnitude.toLong()
        }
    }

    /** Real grammar: optional sign, digits, optional fraction, optional
     * exponent, plus the special spellings (parser_xml.rs). */
    internal fun parseReal(content: String): Double? {
        val lower = content.lowercase()
        when (lower) {
            "nan" -> return Double.NaN
            "inf", "+inf", "infinity", "+infinity" -> return Double.POSITIVE_INFINITY
            "-inf", "-infinity" -> return Double.NEGATIVE_INFINITY
        }
        var index = 0
        if (content.isNotEmpty() && (content[0] == '+' || content[0] == '-')) {
            index += 1
        }
        val digitsStart = index
        while (index < content.length && content[index].isDigit()) {
            index += 1
        }
        if (index == digitsStart) {
            return null
        }
        if (index < content.length && content[index] == '.') {
            index += 1
            val fractionStart = index
            while (index < content.length && content[index].isDigit()) {
                index += 1
            }
            if (index == fractionStart) {
                return null
            }
        }
        if (index < content.length && (content[index] == 'e' || content[index] == 'E')) {
            index += 1
            if (index < content.length && (content[index] == '+' || content[index] == '-')) {
                index += 1
            }
            val exponentStart = index
            while (index < content.length && content[index].isDigit()) {
                index += 1
            }
            if (index == exponentStart) {
                return null
            }
        }
        if (index != content.length) {
            return null
        }
        return content.toDoubleOrNull()
    }

    /** Date grammar: `[-]YYYY-MM-DDTHH:MM:SSZ` with calendar validation;
     * the value is exact double seconds since the plist epoch (RFC 0013
     * §4.7; parser_xml.rs). */
    internal fun parseDate(content: String): Double? {
        val match = DATE_PATTERN.matchEntire(content) ?: return null
        val negative = content.startsWith('-')
        val yearDigits = match.groupValues[2]
        val month = match.groupValues[3].toInt()
        val day = match.groupValues[4].toInt()
        val hour = match.groupValues[5].toInt()
        val minute = match.groupValues[6].toInt()
        val second = match.groupValues[7].toInt()
        val yearMag = yearDigits.toLongOrNull() ?: return null
        if (yearMag > 0xFFFF_FFFFL) {
            return null
        }
        val year = if (negative) -yearMag else yearMag
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }
        if (day > daysInMonth(year, month)) {
            return null
        }
        val days = daysFromCivil(year, month, day)
        val unix = days * 86400L + hour * 3600L + minute * 60L + second
        return unix.toDouble() - PLIST_EPOCH_OFFSET_UNIX
    }

    private fun daysInMonth(year: Long, month: Int): Int =
        when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 0
        }

    private fun isLeapYear(year: Long): Boolean =
        (year % 4 == 0L && year % 100 != 0L) || year % 400 == 0L

    /** Days since 1970-01-01 (proleptic Gregorian; the inverse of the
     * materializer's civil_from_days). */
    private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
        var y = year
        if (month <= 2) {
            y -= 1
        }
        val era = if (y >= 0) y / 400 else (y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Strict base64 with the standard alphabet, exact padding, and admitted
     * ASCII whitespace between characters (RFC 0013 §4.8). */
    internal fun decodeBase64(content: String): ByteArray? {
        val cleaned = content.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
        if (cleaned.isEmpty()) {
            return ByteArray(0)
        }
        if (cleaned.length % 4 != 0) {
            return null
        }
        val output = ByteArray(cleaned.length / 4 * 3)
        var out = 0
        var index = 0
        while (index < cleaned.length) {
            val a = base64Value(cleaned[index]) ?: return null
            val b = base64Value(cleaned[index + 1]) ?: return null
            val c = base64Value(cleaned[index + 2])
            val d = base64Value(cleaned[index + 3])
            if (c == null && d != null) {
                return null
            }
            val triple = (a shl 18) or (b shl 12) or ((c ?: 0) shl 6) or (d ?: 0)
            if (c == null) {
                output[out++] = ((triple ushr 16) and 0xFF).toByte()
            } else if (d == null) {
                output[out++] = ((triple ushr 16) and 0xFF).toByte()
                output[out++] = ((triple ushr 8) and 0xFF).toByte()
            } else {
                output[out++] = ((triple ushr 16) and 0xFF).toByte()
                output[out++] = ((triple ushr 8) and 0xFF).toByte()
                output[out++] = (triple and 0xFF).toByte()
            }
            index += 4
        }
        return output.copyOf(out)
    }

    private fun base64Value(ch: Char): Int? =
        when (ch) {
            in 'A'..'Z' -> ch.code - 'A'.code
            in 'a'..'z' -> ch.code - 'a'.code + 26
            in '0'..'9' -> ch.code - '0'.code + 52
            '+' -> 62
            '/' -> 63
            else -> null
        }

    // ------------------------------------------------------------------
    // Finish
    // ------------------------------------------------------------------

    private fun finish(): Document {
        // Unclosed elements and unclosed unknown subtrees (parser_xml.rs).
        while (stack.isNotEmpty()) {
            val frame = stack.poll()
            if (frame.kind != null) {
                recover(PlistCodes.PARSE_UNCLOSED_ELEMENT, DiagnosticCategory.Syntax,
                    arguments = mapOf("element" to frame.name))
            }
            if (frame.unknownMarker != null) {
                pushPiece(frame.unknownMarker!!, text.length, PlistSyntaxKind.ErrorRegion,
                    StructuralPieceKind.ErrorRegion)
            }
        }
        if (!anyTopLevel) {
            recover(PlistCodes.PARSE_MISSING_ROOT, DiagnosticCategory.Syntax)
        }
        // Native document eligibility: exactly one proven root value.
        var nativeRoot: NativeValue? = null
        var rootIndex = -1
        if (plistRootSeen) {
            when (rootValueCount) {
                0 -> recover(PlistCodes.PARSE_ROOT_VALUE_COUNT, DiagnosticCategory.Syntax,
                    arguments = mapOf("count" to "0"))
                1 -> {
                    val root = rootValueRef
                    if (root != null) {
                        nativeRoot = (entities[root] as Entity.Value).entity.native
                        rootIndex = root
                    }
                }
                else -> recover(PlistCodes.PARSE_ROOT_VALUE_COUNT, DiagnosticCategory.Syntax,
                    arguments = mapOf("count" to rootValueCount.toString()))
            }
        }
        val status = if (recovered) FormationStatus.Recovered else FormationStatus.Complete
        val sourceLen = source.len

        // Piece gap assembly and ordering (parser_xml.rs).
        val sorted = pieces.sortedBy { it.first.start }
        val finalPairs = ArrayList<Pair<SpanPair, PlistSyntaxKind>>(sorted.size + 8)
        var next = 0
        for ((spanPair, kind) in sorted) {
            if (spanPair.start > next) {
                finalPairs.add(
                    SpanPair(next, spanPair.start) to
                        (if (recovered) PlistSyntaxKind.ErrorRegion else PlistSyntaxKind.Whitespace),
                )
            }
            next = spanPair.end
            finalPairs.add(spanPair to kind)
        }
        if (next < sourceLen) {
            finalPairs.add(
                SpanPair(next, sourceLen) to
                    (if (recovered) PlistSyntaxKind.ErrorRegion else PlistSyntaxKind.Whitespace),
            )
        }
        finalPairs.sortBy { it.first.start }
        val structuralPieces = ArrayList<StructuralPiece>(finalPairs.size)
        val syntaxKinds = ArrayList<PlistSyntaxKind>(finalPairs.size)
        for ((spanPair, kind) in finalPairs) {
            structuralPieces.add(
                StructuralPiece(authority.span(spanPair.start, spanPair.end), structuralKindOf(kind)),
            )
            syntaxKinds.add(kind)
        }
        val errorRegions = structuralPieces.count { it.kind == StructuralPieceKind.ErrorRegion }
        if (errorRegions > limits.maxRecoveryRegions) {
            throw plistLimit("recovery-regions", errorRegions, limits.maxRecoveryRegions)
        }
        val structuralIndex = try {
            LosslessStructuralIndex.new(authority.identity, sourceLen, structuralPieces)
        } catch (e: consema.document.LocationException) {
            // The failure fact is carried by the typed exception; no
            // per-piece stderr output — the parser is an SDK library and
            // embedded applications must not receive unsuppressible output
            // (configurations routinely contain credentials; never dump
            // piece bytes).
            throw PlistFormationException(
                PlistCodes.XML_COVERAGE,
                "plist xml parse: structural coverage failure",
            )
        }
        return Document(
            authority = authority,
            source = source,
            profile = PlistProfile.XmlV1,
            losslessIndex = structuralIndex,
            binaryIndex = null,
            formationStatus = status,
            diagnosticsList = diagnostics,
            entities = entities,
            rootIndex = rootIndex,
            nativeRoot = nativeRoot,
            syntaxKinds = syntaxKinds,
            binaryFacts = null,
            parseLimits = limits,
        )
    }

    private fun structuralKindOf(kind: PlistSyntaxKind): StructuralPieceKind =
        when (kind) {
            PlistSyntaxKind.Bom, PlistSyntaxKind.Whitespace, PlistSyntaxKind.LineBreak,
            PlistSyntaxKind.CommentOpen, PlistSyntaxKind.CommentText, PlistSyntaxKind.CommentClose,
            PlistSyntaxKind.ProcessingInstructionOpen, PlistSyntaxKind.ProcessingInstructionTarget,
            PlistSyntaxKind.ProcessingInstructionContent, PlistSyntaxKind.ProcessingInstructionClose ->
                StructuralPieceKind.Trivia

            PlistSyntaxKind.ErrorRegion -> StructuralPieceKind.ErrorRegion
            else -> StructuralPieceKind.Token
        }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Raw span pair in raw byte offsets. */
    private data class SpanPair(val start: Int, val end: Int)

    private fun rawAt(unit: Int): Int =
        try {
            source.rawByteAt(DecodedOffset.Utf16CodeUnit(unit))
        } catch (e: consema.document.LocationException) {
            throw PlistFormationException(
                PlistCodes.XML_COORDINATES,
                "plist xml parse: impossible source coordinates",
            )
        }

    private fun pushPiece(
        startUnit: Int,
        endUnit: Int,
        kind: PlistSyntaxKind,
        structural: StructuralPieceKind,
    ) {
        if (endUnit <= startUnit) {
            return
        }
        if (pieces.size >= limits.maxSyntaxPieces) {
            throw plistLimit("syntax-pieces", pieces.size + 1, limits.maxSyntaxPieces)
        }
        pieces.add(SpanPair(rawAt(startUnit), rawAt(endUnit)) to kind)
    }

    private fun pushWhitespacePieces(start: Int, end: Int) {
        var cursor = start
        while (cursor < end) {
            val ch = text[cursor]
            if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') {
                // Defensive: unproven bytes become an error region.
                val runStart = cursor
                while (cursor < end && text[cursor] != ' ' && text[cursor] != '\t' &&
                    text[cursor] != '\r' && text[cursor] != '\n'
                ) {
                    cursor += 1
                }
                pushPiece(runStart, cursor, PlistSyntaxKind.ErrorRegion, StructuralPieceKind.ErrorRegion)
                continue
            }
            val lineBreak = ch == '\r' || ch == '\n'
            val runStart = cursor
            cursor += if (ch == '\r' && cursor + 1 < end && text[cursor + 1] == '\n') 2 else 1
            while (cursor < end && (text[cursor] == '\r' || text[cursor] == '\n') == lineBreak) {
                cursor += 1
            }
            pushPiece(
                runStart,
                cursor,
                if (lineBreak) PlistSyntaxKind.LineBreak else PlistSyntaxKind.Whitespace,
                StructuralPieceKind.Trivia,
            )
        }
    }

    /** Error region plus well-formedness recovery (parser_xml.rs). */
    private fun recoverErrorRegion(start: Int, end: Int) {
        if (unknownDepth == 0) {
            pushPiece(start, end, PlistSyntaxKind.ErrorRegion, StructuralPieceKind.ErrorRegion)
        }
        recover(PlistCodes.PARSE_WELL_FORMEDNESS, DiagnosticCategory.Syntax)
    }

    private fun errorRegionDiagnostic(
        start: Int,
        end: Int,
        code: String,
        category: DiagnosticCategory,
    ) {
        pushPiece(start, end, PlistSyntaxKind.ErrorRegion, StructuralPieceKind.ErrorRegion)
        recover(code, category)
    }

    private fun recover(
        code: String,
        category: DiagnosticCategory,
        arguments: Map<String, String> = emptyMap(),
    ) {
        recovered = true
        diagnostics.add(
            PlistDiagnostic(
                code = code,
                category = category,
                severity = Severity.Error,
                startByte = null,
                endByte = null,
                arguments = arguments,
                notes = emptyList(),
                occurrence = occurrence++,
            ),
        )
    }

    private fun coverBom() {
        val bom = source.encodingFacts.bom
        if (bom != null) {
            val len = when (bom) {
                consema.document.BomKind.Utf8 -> 3
                consema.document.BomKind.Utf16Le, consema.document.BomKind.Utf16Be -> 2
            }
            if (len > 0) {
                // The BOM piece covers the exact raw marker bytes (0..len);
                // the unit-to-raw mapping would misplace it for UTF-16, whose
                // leading FEFF occupies 2 raw bytes (RFC 0003 §4.3).
                if (pieces.size >= limits.maxSyntaxPieces) {
                    throw plistLimit("syntax-pieces", pieces.size + 1, limits.maxSyntaxPieces)
                }
                pieces.add(SpanPair(0, len) to PlistSyntaxKind.Bom)
            }
            if (text.startsWith('\uFEFF')) {
                pos = 1
            }
        }
    }

    private fun isWhitespaceOnly(start: Int, end: Int): Boolean {
        for (index in start until end) {
            if (!isWsChar(text[index])) {
                return false
            }
        }
        return true
    }

    private fun skipUntil(target: Char) {
        while (pos < text.length && text[pos] != target) {
            pos += 1
        }
    }

    private fun skipDeclarationSpaces(cursor: Int, end: Int): Int {
        var at = cursor
        while (at < end && (text[at] == ' ' || text[at] == '\t' ||
                text[at] == '\r' || text[at] == '\n')
        ) {
            at += 1
        }
        return at
    }

    private fun skipWs(body: String, cursor: Int): Int {
        var at = cursor
        while (at < body.length && isWsChar(body[at])) {
            at += 1
        }
        return at
    }

    private fun readQuoted(body: String, cursor: Int): Pair<String, Int>? {
        if (cursor >= body.length || (body[cursor] != '"' && body[cursor] != '\'')) {
            return null
        }
        val quote = body[cursor]
        val end = body.indexOf(quote, cursor + 1)
        if (end < 0) {
            return null
        }
        return body.substring(cursor + 1, end) to (end + 1)
    }

    private fun findTagEnd(from: Int): Int {
        var cursor = from
        while (cursor < text.length && text[cursor] != '>') {
            cursor += 1
        }
        return if (cursor < text.length) cursor else -1
    }

    private fun findEquals(from: Int, valueStart: Int): Int {
        for (index in from until valueStart) {
            if (text[index] == '=') {
                return index
            }
        }
        return valueStart
    }

    /** XML attribute normalization: references resolve and whitespace runs
     * collapse to one space (parser_xml.rs). */
    private fun normalizeAttributeValue(value: String): String {
        val resolved = resolveFragmentSegment(value, 0, emitPieces = false)
        return resolved.trim().replace(Regex("\\s+"), " ")
    }

    private fun checkLimitUnits(count: Int) {
        if (count > limits.maxStringCodeUnits) {
            throw plistLimit("string-code-units", count, limits.maxStringCodeUnits)
        }
    }

    private fun isNameBoundary(text: String, index: Int): Boolean =
        index >= text.length || !isNameChar(text[index])

    private fun isNameChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == ':' ||
            ch.code in 0x00C0..0x00D6 || ch.code in 0x00D8..0x00F6 || ch.code in 0x00F8..0x02FF ||
            ch.code in 0x0370..0x037D || ch.code in 0x200C..0x200D || ch.code in 0x2070..0x218F ||
            ch.code in 0x2C00..0x2FEF || ch.code in 0x3001..0xD7FF || ch.code in 0xF900..0xFDCF ||
            ch.code in 0xFDF0..0xFFFD

    private fun isWsChar(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n'

    /** XML-whitespace-only trim (the grammar's `S` set, parser_xml.rs). */
    private fun trimXmlWhitespace(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && isWsChar(value[start])) {
            start += 1
        }
        while (end > start && isWsChar(value[end - 1])) {
            end -= 1
        }
        return value.substring(start, end)
    }

    private fun normalizeLineEnds(value: String): String {
        if ('\r' !in value) {
            return value
        }
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '\r') {
                builder.append('\n')
                if (index + 1 < value.length && value[index + 1] == '\n') {
                    index += 1
                }
            } else {
                builder.append(ch)
            }
            index += 1
        }
        return builder.toString()
    }

    private fun isXmlChar(ch: Char): Boolean =
        ch.code == 0x9 || ch.code == 0xA || ch.code == 0xD ||
            ch.code in 0x20..0xD7FF || ch.code in 0xE000..0xFFFD

    companion object {
        /** `[-]YYYY-MM-DDTHH:MM:SSZ` (RFC 0013 §4.7). */
        private val DATE_PATTERN =
            Regex("^(-?)([0-9]+)-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})Z$")

        /** Seconds from the Unix epoch to `2001-01-01T00:00:00Z` (RFC 0013
         * §5.5; native.rs PLIST_EPOCH_OFFSET_UNIX). */
        internal const val PLIST_EPOCH_OFFSET_UNIX: Double = 978307200.0
    }
}
