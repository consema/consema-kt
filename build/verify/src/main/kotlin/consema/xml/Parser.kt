// XML formation: source facts, tokenization, native tree, safe DTD subset,
// bounded entity expansion, recovery, and exhaustive piece coverage
// (RFC 0012 §2-4, §6-7, §12-13).
//
// Data authority:
//   - RFC 0012 §2 (docs/rfcs/0012-xml-1.0-safe-profile-v1.md:46-81) pins the
//     source/encoding table; §3 (0012-...:83-130) the safe DTD/entity
//     boundary; §4 (0012-...:132-166) Complete/Recovered formation and
//     deterministic recovery at markup boundaries; §6 (0012-...:228-256)
//     text/CDATA/reference facts; §7 (0012-...:258-282) exhaustive piece
//     coverage.
//   - crates/consema-xml/src/parser.rs is the byte-arbitration authority:
//     the parse entry (parser.rs:22-46), encoding resolution (parser.rs:
//     48-108), the token dispatch (parser.rs:287-332), the declaration
//     handler (parser.rs:334-503), PI (parser.rs:505-579), comment
//     (parser.rs:581-644), doctype handlers (parser.rs:646-911), element
//     start/attribute/finalize (parser.rs:913-1174), element end and frame
//     close (parser.rs:1176-1305), text/CDATA (parser.rs:1307-1458), text
//     fragments and reference resolution (parser.rs:1460-1729), recovery
//     (parser.rs:1731-1790), and finish/gap filling (parser.rs:1792-1914).
//   - The tokenizer follows the xmlparser 0.13.6 token stream contract that
//     the Rust parser consumes (RFC 0012 §13, 0012-...:435-453); go/xml/
//     parser.go is the cross-reference confirming the token boundaries and
//     the tokenizer-error behavior: "A tokenizer error jumps the stream to
//     the end of the document (xmlparser Stream::jump_to_end), so the
//     recovery region is always the final byte and tokenization stops"
//     (go/xml/parser.go:153-161), which is why the Rust recovery loop reads
//     `tokenizer.stream().pos()` as the document end (parser.rs:255-268).
//
// Kotlin-idiomatic design (NOT a translation): a hand-rolled deterministic
// tokenizer over the decoded UTF-8 byte view (the Kotlin SourceSnapshot
// decodes at construction and the tokenizer works on byte offsets, so raw
// spans stay byte-exact for UTF-16 sources through the source boundary
// index, parser.rs:2022-2068); handlers mirror the Rust parser semantics;
// recovery is explicit and deterministic.

package consema.xml

import consema.document.BomPolicy
import consema.document.DecodedOffset
import consema.document.DocumentAuthority
import consema.document.EncodingRequest
import consema.document.FormationStatus
import consema.document.LosslessStructuralIndex
import consema.document.SourceEncoding
import consema.document.SourceLimits
import consema.document.SourceSnapshot
import consema.document.Span
import consema.document.StructuralPiece
import consema.document.StructuralPieceKind
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity
import java.nio.charset.StandardCharsets

/**
 * Forms one `xml.1.0-safe@1` document from a complete document entity
 * (parser.rs:22-46; lib.rs:174-186). The Profile is selected before
 * formation and never by extension; the parser consumes the supplied bytes
 * and opens no other entity, file, URI, network connection, registry,
 * classpath, or catalog. Source/encoding/limit failures are fatal and throw
 * [XmlFormationException]; syntax, well-formedness, namespace, safe-DTD, and
 * entity errors form a Recovered document when the complete source can still
 * be covered (RFC 0012 §4).
 */
fun parse(
    bytes: ByteArray,
    profile: XmlProfile,
    selection: XmlEncodingSelection,
    limits: XmlParseLimits,
): Document {
    if (profile != XmlProfile.SafeV1) {
        throw profileFailure("xml.profile.unknown@1")
    }
    val request = encodingRequest(selection)
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
    validateProfileEncoding(source, selection)
    val decoded = source.decodedText()
        ?: throw profileFailure("xml.source.decoding@1")
    val decodedUtf8 = decoded.toByteArray(StandardCharsets.UTF_8)
    return Parser(source, decodedUtf8, limits).parse()
}

/** Resolves the source encoding request under the RFC 0012 §2 table
 * (parser.rs:55-80). */
private fun encodingRequest(selection: XmlEncodingSelection): EncodingRequest =
    when (selection) {
        XmlEncodingSelection.ProfileDefault ->
            EncodingRequest.new(SourceEncoding.Utf8).withBomPolicy(BomPolicy.DetectUnicode)

        is XmlEncodingSelection.Explicit -> {
            val admitted = selection.encoding === SourceEncoding.Utf8 ||
                selection.encoding === SourceEncoding.Utf16Le ||
                selection.encoding === SourceEncoding.Utf16Be
            if (!admitted) {
                // UTF-32, Latin-1, Windows code pages, and other IANA
                // encodings are explicit v1 Profile exclusions.
                throw profileFailure("xml.profile.encoding@1")
            }
            EncodingRequest.new(SourceEncoding.Utf8).withCallerOverride(selection.encoding)
        }
    }

/** Verifies the resolved source facts under the profile table
 * (parser.rs:82-108). */
private fun validateProfileEncoding(source: SourceSnapshot, selection: XmlEncodingSelection) {
    val facts = source.encodingFacts
    val valid = when (selection) {
        XmlEncodingSelection.ProfileDefault ->
            facts.selected === SourceEncoding.Utf8 ||
                facts.selected === SourceEncoding.Utf16Le ||
                facts.selected === SourceEncoding.Utf16Be

        is XmlEncodingSelection.Explicit ->
            when (selection.encoding) {
                SourceEncoding.Utf8 -> facts.selected === SourceEncoding.Utf8
                SourceEncoding.Utf16Le ->
                    facts.selected === SourceEncoding.Utf16Le &&
                        facts.bom?.encoding() === SourceEncoding.Utf16Le

                SourceEncoding.Utf16Be ->
                    facts.selected === SourceEncoding.Utf16Be &&
                        facts.bom?.encoding() === SourceEncoding.Utf16Be

                else -> false
            }
    }
    if (!valid) {
        throw profileFailure("xml.profile.encoding@1")
    }
}

/** Wraps a source construction failure with the frozen code mapping of
 * FatalFormationFailure::source_error (consema-document lib.rs:676-707;
 * the json family transcription kotlin/.../json/Parser.kt:117-158). */
private fun wrapSourceError(error: consema.document.SourceException): XmlFormationException =
    when (error.kind) {
        consema.document.SourceErrorKind.INVALID_UTF8 ->
            XmlFormationException(
                "core.source.invalid-utf8@1",
                "xml parse: source is not valid UTF-8 at byte ${error.byteOffset}",
                cause = error,
            )

        consema.document.SourceErrorKind.INVALID_SEQUENCE ->
            XmlFormationException(
                "core.source.invalid-sequence@1",
                "xml parse: invalid source sequence",
                cause = error,
            )

        consema.document.SourceErrorKind.ENCODING_CONFLICT ->
            XmlFormationException(
                "core.source.encoding-conflict@1",
                "xml parse: source encoding facts conflict",
                cause = error,
            )

        consema.document.SourceErrorKind.UNSUPPORTED_BOM ->
            XmlFormationException(
                "core.source.unsupported-bom@1",
                "xml parse: unsupported byte-order mark",
                cause = error,
            )

        consema.document.SourceErrorKind.RESOURCE_LIMIT,
        consema.document.SourceErrorKind.OFFSET_OVERFLOW,
        ->
            XmlFormationException(
                "core.source.resource-limit@1",
                "xml parse: source construction limit reached",
                name = error.name ?: "",
                observed = error.observed,
                limit = error.limit,
                cause = error,
            )
    }

/** Fatal profile or limit failure (parser.rs:120-128). */
private fun profileFailure(code: String): XmlFormationException =
    XmlFormationException(code, "xml: formation profile or limit failure $code")

// ---------------------------------------------------------------------------
// Tokens
// ---------------------------------------------------------------------------

/** One tokenizer token; spans are decoded UTF-8 byte offsets. */
private sealed class XmlToken {
    data class Declaration(
        val start: Int,
        val end: Int,
        val versionStart: Int,
        val versionEnd: Int,
        val encodingStart: Int,
        val encodingEnd: Int,
        val standalone: Boolean,
        val hasStandalone: Boolean,
    ) : XmlToken()

    data class ProcessingInstruction(
        val start: Int,
        val end: Int,
        val targetStart: Int,
        val targetEnd: Int,
        val contentStart: Int,
        val contentEnd: Int,
    ) : XmlToken()

    data class Comment(
        val start: Int,
        val end: Int,
        val valueStart: Int,
        val valueEnd: Int,
    ) : XmlToken()

    data class DtdStart(
        val start: Int,
        val end: Int,
        val nameStart: Int,
        val nameEnd: Int,
        val external: Boolean,
    ) : XmlToken()

    data class EmptyDtd(
        val start: Int,
        val end: Int,
        val nameStart: Int,
        val nameEnd: Int,
        val external: Boolean,
    ) : XmlToken()

    data class EntityDeclaration(
        val start: Int,
        val end: Int,
        val nameStart: Int,
        val nameEnd: Int,
        val valueStart: Int,
        val valueEnd: Int,
        val hasValue: Boolean,
        val external: Boolean,
    ) : XmlToken()

    data class DtdEnd(val start: Int, val end: Int) : XmlToken()

    data class ElementStart(
        val start: Int,
        val end: Int,
        val prefixStart: Int,
        val prefixEnd: Int,
        val localStart: Int,
        val localEnd: Int,
    ) : XmlToken()

    data class Attribute(
        val start: Int,
        val end: Int,
        val prefixStart: Int,
        val prefixEnd: Int,
        val localStart: Int,
        val localEnd: Int,
        val valueStart: Int,
        val valueEnd: Int,
    ) : XmlToken()

    data class ElementOpenEnd(val start: Int, val end: Int) : XmlToken()

    data class ElementEmptyEnd(val start: Int, val end: Int) : XmlToken()

    data class ElementCloseEnd(
        val start: Int,
        val end: Int,
        val prefixStart: Int,
        val prefixEnd: Int,
        val localStart: Int,
        val localEnd: Int,
    ) : XmlToken()

    data class Text(val start: Int, val end: Int) : XmlToken()

    data class Cdata(
        val start: Int,
        val end: Int,
        val valueStart: Int,
        val valueEnd: Int,
    ) : XmlToken()
}

/** One tokenizer failure. Per the xmlparser 0.13.6 contract the failing
 * stream jumps to the end of the document, so the position is not used for
 * the recovery region (go/xml/parser.go:153-161; parser.rs:255-268). */
private class TokenizerError : Exception()

// ---------------------------------------------------------------------------
// Tokenizer
// ---------------------------------------------------------------------------

/**
 * Deterministic XML 1.0 tokenizer over the decoded UTF-8 byte view,
 * reproducing the xmlparser 0.13.6 token stream contract consumed by the
 * Rust parser (RFC 0012 §13). Token errors throw [TokenizerError]; the
 * parse loop covers the final byte as the recovery region and stops
 * (go/xml/parser.go:153-161).
 */
private class XmlTokenizer(
    private val decoded: ByteArray,
    private var pos: Int,
    /** Whether an element frame is currently open. */
    private val inElement: () -> Boolean,
    /** Whether the document element has been formed (AfterElements). */
    private val afterRoot: () -> Boolean,
) {
    private val pending = ArrayDeque<XmlToken>()

    /** Returns the next token, or null at end of stream. */
    fun next(): XmlToken? {
        pending.removeFirstOrNull()?.let { return it }
        if (pos >= decoded.size) {
            return null
        }
        if (pos == 0) {
            // The decoded text retains a leading BOM as U+FEFF, which the
            // tokenizer skips inside its stream.
            if (isBom()) {
                pos = 3
                if (pos >= decoded.size) {
                    return null
                }
            }
        }
        return if (inElement()) {
            scanElementContent(pos)
        } else {
            scanTopLevel(pos)
        }
    }

    private fun isBom(): Boolean =
        decoded.size >= 3 && decoded[0] == 0xef.toByte() &&
            decoded[1] == 0xbb.toByte() && decoded[2] == 0xbf.toByte()

    /** The stream-start position after an optional BOM. */
    private fun atStreamStart(candidate: Int): Boolean =
        candidate == (if (isBom()) 3 else 0)

    private fun scanTopLevel(start: Int): XmlToken {
        if (isSpace(decoded[start])) {
            pos = skipSpaces(start)
            return next() ?: return null
        }
        return when {
            startsWith(start, "<!DOCTYPE") -> {
                if (afterRoot()) {
                    // AfterElements rejects a second DOCTYPE.
                    throw TokenizerError()
                }
                scanDoctype(start)
            }
            startsWith(start, "<!--") -> scanComment(start)
            startsWith(start, "<?") -> {
                if (startsWith(start, "<?xml ") && !atStreamStart(start)) {
                    // `<?xml ` is only legal at the very start of the stream.
                    throw TokenizerError()
                }
                scanQuestion(start)
            }
            startsWith(start, "<!") -> throw TokenizerError()
            startsWith(start, "</") -> throw TokenizerError()
            decoded[start] == '<'.code.toByte() -> {
                if (afterRoot()) {
                    // AfterElements rejects any further root-level markup.
                    throw TokenizerError()
                }
                scanStartTag(start)
            }
            else -> throw TokenizerError()
        }
    }

    private fun scanElementContent(start: Int): XmlToken =
        when {
            startsWith(start, "<?") -> {
                if (startsWith(start, "<?xml ")) {
                    throw TokenizerError()
                }
                scanQuestion(start)
            }
            startsWith(start, "<!--") -> scanComment(start)
            startsWith(start, "<![CDATA[") -> scanCdata(start)
            startsWith(start, "<!") -> throw TokenizerError()
            startsWith(start, "</") -> scanEndTag(start)
            decoded[start] == '<'.code.toByte() -> scanStartTag(start)
            else -> scanText(start)
        }

    private fun scanText(start: Int): XmlToken {
        var next = start
        while (next < decoded.size && decoded[next] != '<'.code.toByte()) {
            next += 1
        }
        // According to the spec, `]]>` must not appear inside a Text node.
        if (indexOf(decoded, start, "]]>") in start until next) {
            throw TokenizerError()
        }
        pos = next
        return XmlToken.Text(start, next)
    }

    private fun scanQuestion(start: Int): XmlToken {
        val atStart = atStreamStart(start)
        var cursor = start + 2
        val (nameStart, nameEnd, ok) = scanName(cursor)
        if (!ok) {
            throw TokenizerError()
        }
        // The declaration is recognized only by the exact `<?xml ` spelling
        // at the stream start (the xmlparser Declaration state); `<?xml`
        // without the trailing space is a PI with target `xml`.
        if (atStart && decodedEquals(nameStart, nameEnd, "xml") &&
            nameEnd < decoded.size && isSpace(decoded[nameEnd])
        ) {
            return scanDeclaration(start, nameStart, nameEnd)
        }
        // Processing instruction: optional whitespace then content to `?>`.
        val targetStart = nameStart
        val targetEnd = nameEnd
        var contentStart = nameEnd
        while (contentStart < decoded.size && isSpace(decoded[contentStart])) {
            contentStart += 1
        }
        val closeAt = indexOf(decoded, contentStart, "?>")
        if (closeAt < 0) {
            throw TokenizerError()
        }
        pos = closeAt + 2
        return XmlToken.ProcessingInstruction(
            start, closeAt + 2, targetStart, targetEnd, contentStart, closeAt,
        )
    }

    /** Scans the fixed declaration grammar `<?xml` S version Eq value
     * (S encoding Eq value)? (S standalone Eq value)? S? `?>` (go/xml/
     * parser.go:357-397). */
    private fun scanDeclaration(start: Int, nameStart: Int, nameEnd: Int): XmlToken {
        var cursor = skipSpaces(nameEnd)
        val version = scanPseudoAttribute(cursor)
        if (!version.ok || version.name != "version") {
            throw TokenizerError()
        }
        cursor = skipSpaces(version.after)
        var encoding = ScanPseudoAttributeResult.NONE
        val encodingScan = scanPseudoAttribute(cursor)
        if (encodingScan.ok && encodingScan.name == "encoding") {
            encoding = encodingScan
            cursor = skipSpaces(encodingScan.after)
        }
        var standalone = false
        var hasStandalone = false
        val standaloneScan = scanPseudoAttribute(cursor)
        if (standaloneScan.ok) {
            if (standaloneScan.name != "standalone") {
                throw TokenizerError()
            }
            val value = decodedSlice(standaloneScan.valueStart, standaloneScan.valueEnd)
            if (value != "yes" && value != "no") {
                throw TokenizerError()
            }
            standalone = value == "yes"
            hasStandalone = true
            cursor = skipSpaces(standaloneScan.after)
        }
        if (!startsWith(cursor, "?>")) {
            throw TokenizerError()
        }
        pos = cursor + 2
        return XmlToken.Declaration(
            start,
            cursor + 2,
            version.valueStart,
            version.valueEnd,
            encoding.valueStart,
            encoding.valueEnd,
            standalone,
            hasStandalone,
        )
    }

    /** One scanned pseudo-attribute of the XML declaration. */
    private data class ScanPseudoAttributeResult(
        val name: String,
        val valueStart: Int,
        val valueEnd: Int,
        val after: Int,
        val ok: Boolean,
    ) {
        companion object {
            val NONE = ScanPseudoAttributeResult("", 0, 0, 0, false)
        }
    }

    /** Scans `name = "value"` returning the name, the value span, and the
     * position after the closing quote. */
    private fun scanPseudoAttribute(cursor: Int): ScanPseudoAttributeResult {
        val (nameStart, nameEnd, ok) = scanName(cursor)
        if (!ok) {
            return ScanPseudoAttributeResult.NONE
        }
        val name = decodedSlice(nameStart, nameEnd)
        var at = skipSpaces(nameEnd)
        if (at >= decoded.size || decoded[at] != '='.code.toByte()) {
            return ScanPseudoAttributeResult.NONE
        }
        at += 1
        at = skipSpaces(at)
        if (at >= decoded.size ||
            (decoded[at] != '"'.code.toByte() && decoded[at] != '\''.code.toByte())
        ) {
            return ScanPseudoAttributeResult.NONE
        }
        val quote = decoded[at]
        at += 1
        val valueStart = at
        val closeAt = indexOfByte(decoded, at, quote)
        if (closeAt < 0) {
            return ScanPseudoAttributeResult.NONE
        }
        return ScanPseudoAttributeResult(name, valueStart, closeAt, closeAt + 1, true)
    }

    private fun scanComment(start: Int): XmlToken {
        val closeAt = indexOf(decoded, start + 4, "-->")
        if (closeAt < 0) {
            throw TokenizerError()
        }
        pos = closeAt + 3
        return XmlToken.Comment(start, closeAt + 3, start + 4, closeAt)
    }

    private fun scanCdata(start: Int): XmlToken {
        val closeAt = indexOf(decoded, start + 9, "]]>")
        if (closeAt < 0) {
            throw TokenizerError()
        }
        pos = closeAt + 3
        return XmlToken.Cdata(start, closeAt + 3, start + 9, closeAt)
    }

    /** Scans `<!DOCTYPE name (external-id)? (">" | "[" … "]")`. */
    private fun scanDoctype(start: Int): XmlToken {
        var cursor = skipSpaces(start + 9)
        val (nameStart, nameEnd, ok) = scanName(cursor)
        if (!ok) {
            throw TokenizerError()
        }
        cursor = skipSpaces(nameEnd)
        var external = false
        if (startsWith(cursor, "SYSTEM") || startsWith(cursor, "PUBLIC")) {
            external = true
            cursor = skipSpaces(cursor + 6)
            if (cursor >= decoded.size ||
                (decoded[cursor] != '"'.code.toByte() && decoded[cursor] != '\''.code.toByte())
            ) {
                throw TokenizerError()
            }
            val quote = decoded[cursor]
            val closeAt = indexOfByte(decoded, cursor + 1, quote)
            if (closeAt < 0) {
                throw TokenizerError()
            }
            cursor = skipSpaces(closeAt + 1)
        }
        if (cursor < decoded.size && decoded[cursor] == '['.code.toByte()) {
            val subsetEnd = scanSubset(cursor + 1)
            pos = subsetEnd
            return XmlToken.DtdStart(start, cursor, nameStart, nameEnd, external)
        }
        if (cursor < decoded.size && decoded[cursor] == '>'.code.toByte()) {
            pos = cursor + 1
            return XmlToken.EmptyDtd(start, cursor + 1, nameStart, nameEnd, external)
        }
        throw TokenizerError()
    }

    /** Scans the internal DTD subset until `]>`, queueing the admitted
     * subset tokens. The DtdEnd token span covers `]` plus any skipped
     * spaces plus `>` (the xmlparser Dtd state, lib.rs:448-466). */
    private fun scanSubset(start: Int): Int {
        var cursor = start
        while (true) {
            if (cursor >= decoded.size) {
                throw TokenizerError()
            }
            when {
                decoded[cursor] == ']'.code.toByte() -> {
                    // DTD ends with ']' S? '>', therefore we have to skip
                    // possible spaces.
                    var after = cursor + 1
                    while (after < decoded.size && isSpace(decoded[after])) {
                        after += 1
                    }
                    if (after >= decoded.size || decoded[after] != '>'.code.toByte()) {
                        throw TokenizerError()
                    }
                    pending.addLast(XmlToken.DtdEnd(cursor, after + 1))
                    return after + 1
                }

                isSpace(decoded[cursor]) -> cursor += 1

                startsWith(cursor, "<!--") -> {
                    val closeAt = indexOf(decoded, cursor + 4, "-->")
                    if (closeAt < 0) {
                        throw TokenizerError()
                    }
                    pending.addLast(XmlToken.Comment(cursor, closeAt + 3, cursor + 4, closeAt))
                    cursor = closeAt + 3
                }

                startsWith(cursor, "<?") -> {
                    val closeAt = indexOf(decoded, cursor + 2, "?>")
                    if (closeAt < 0) {
                        throw TokenizerError()
                    }
                    var contentStart = cursor + 2
                    val (targetStart, targetEnd, ok) = scanName(contentStart)
                    if (!ok) {
                        throw TokenizerError()
                    }
                    contentStart = targetEnd
                    while (contentStart < closeAt && isSpace(decoded[contentStart])) {
                        contentStart += 1
                    }
                    pending.addLast(
                        XmlToken.ProcessingInstruction(
                            cursor, closeAt + 2, targetStart, targetEnd, contentStart, closeAt,
                        ),
                    )
                    cursor = closeAt + 2
                }

                startsWith(cursor, "<!ENTITY") -> {
                    cursor = scanEntityDeclaration(cursor)
                }

                startsWith(cursor, "<!ELEMENT"),
                startsWith(cursor, "<!ATTLIST"),
                startsWith(cursor, "<!NOTATION"),
                ->
                    // Excluded validation declarations are consumed by the
                    // tokenizer and flagged from the subset text at the DTD
                    // end (scan_excluded_dtd_markup, parser.rs:865-911).
                    {
                        val closeAt = indexOfByte(decoded, cursor, '>'.code.toByte())
                        if (closeAt < 0) {
                            throw TokenizerError()
                        }
                        cursor = closeAt + 1
                    }

                else -> throw TokenizerError()
            }
        }
    }

    /** Scans `<!ENTITY S? (% S)? name (value | SYSTEM/PUBLIC …) >`. */
    private fun scanEntityDeclaration(start: Int): Int {
        var cursor = skipSpaces(start + 8)
        if (cursor < decoded.size && decoded[cursor] == '%'.code.toByte()) {
            cursor = skipSpaces(cursor + 1)
        }
        val (nameStart, nameEnd, ok) = scanName(cursor)
        if (!ok) {
            throw TokenizerError()
        }
        cursor = skipSpaces(nameEnd)
        var hasValue = true
        var external = false
        var valueStart = 0
        var valueEnd = 0
        if (startsWith(cursor, "SYSTEM") || startsWith(cursor, "PUBLIC")) {
            hasValue = false
            external = true
            cursor = skipSpaces(cursor + 6)
            if (cursor >= decoded.size ||
                (decoded[cursor] != '"'.code.toByte() && decoded[cursor] != '\''.code.toByte())
            ) {
                throw TokenizerError()
            }
            val quote = decoded[cursor]
            val closeAt = indexOfByte(decoded, cursor + 1, quote)
            if (closeAt < 0) {
                throw TokenizerError()
            }
            cursor = skipSpaces(closeAt + 1)
        } else {
            if (cursor >= decoded.size ||
                (decoded[cursor] != '"'.code.toByte() && decoded[cursor] != '\''.code.toByte())
            ) {
                throw TokenizerError()
            }
            val quote = decoded[cursor]
            cursor += 1
            valueStart = cursor
            val closeAt = indexOfByte(decoded, cursor, quote)
            if (closeAt < 0) {
                throw TokenizerError()
            }
            valueEnd = closeAt
            cursor = skipSpaces(closeAt + 1)
        }
        if (cursor >= decoded.size || decoded[cursor] != '>'.code.toByte()) {
            throw TokenizerError()
        }
        pending.addLast(
            XmlToken.EntityDeclaration(
                start, cursor + 1, nameStart, nameEnd, valueStart, valueEnd, hasValue, external,
            ),
        )
        return cursor + 1
    }

    private fun scanEndTag(start: Int): XmlToken {
        var cursor = start + 2
        val (localStart, localEnd, prefixStart, prefixEnd, ok) = scanQName(cursor)
        if (!ok) {
            throw TokenizerError()
        }
        cursor = skipSpaces(localEnd)
        if (cursor >= decoded.size || decoded[cursor] != '>'.code.toByte()) {
            throw TokenizerError()
        }
        pos = cursor + 1
        return XmlToken.ElementCloseEnd(start, cursor + 1, prefixStart, prefixEnd, localStart, localEnd)
    }

    private fun scanStartTag(start: Int): XmlToken {
        var cursor = start + 1
        val (localStart, localEnd, prefixStart, prefixEnd, ok) = scanQName(cursor)
        if (!ok) {
            throw TokenizerError()
        }
        val next = scanTagTail(localEnd)
        if (next - 1 < decoded.size && decoded[next - 1] == '>'.code.toByte()) {
            if (next >= 2 && decoded[next - 2] == '/'.code.toByte()) {
                pending.addLast(XmlToken.ElementEmptyEnd(next - 2, next))
            } else {
                pending.addLast(XmlToken.ElementOpenEnd(next - 1, next))
            }
        }
        pos = next
        return XmlToken.ElementStart(start, localEnd, prefixStart, prefixEnd, localStart, localEnd)
    }

    /** Scans the rest of one start tag including its attributes and returns
     * the position after the closing `>` or `/>`. */
    private fun scanTagTail(start: Int): Int {
        var cursor = start
        while (true) {
            if (cursor >= decoded.size) {
                throw TokenizerError()
            }
            when (decoded[cursor]) {
                '>'.code.toByte() -> return cursor + 1
                '/'.code.toByte() -> {
                    if (cursor + 1 < decoded.size && decoded[cursor + 1] == '>'.code.toByte()) {
                        return cursor + 2
                    }
                    throw TokenizerError()
                }
                ' '.code.toByte(), '\t'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte() ->
                    cursor += 1

                else -> cursor = scanAttribute(cursor)
            }
        }
    }

    /** Scans one `name S? = S? "value"` attribute and queues its token. */
    private fun scanAttribute(start: Int): Int {
        val (localStart, localEnd, prefixStart, prefixEnd, ok) = scanQName(start)
        if (!ok) {
            throw TokenizerError()
        }
        var attrCursor = skipSpaces(localEnd)
        if (attrCursor >= decoded.size || decoded[attrCursor] != '='.code.toByte()) {
            throw TokenizerError()
        }
        attrCursor += 1
        attrCursor = skipSpaces(attrCursor)
        if (attrCursor >= decoded.size ||
            (decoded[attrCursor] != '"'.code.toByte() && decoded[attrCursor] != '\''.code.toByte())
        ) {
            throw TokenizerError()
        }
        val quote = decoded[attrCursor]
        attrCursor += 1
        val valueStart = attrCursor
        while (attrCursor < decoded.size) {
            if (decoded[attrCursor] == quote) {
                break
            }
            if (decoded[attrCursor] == '<'.code.toByte()) {
                throw TokenizerError()
            }
            attrCursor += 1
        }
        if (attrCursor >= decoded.size) {
            throw TokenizerError()
        }
        pending.addLast(
            XmlToken.Attribute(
                localStart, attrCursor + 1, prefixStart, prefixEnd, localStart, localEnd,
                valueStart, attrCursor,
            ),
        )
        return attrCursor + 1
    }

    /** Scans one name possibly split at the first colon; the local span
     * starts after the colon. */
    private fun scanQName(start: Int): QNameScan {
        val (nameStart, nameEnd, ok) = scanName(start)
        if (!ok) {
            return QNameScan(0, 0, 0, 0, false)
        }
        val colon = indexOfByte(decoded, nameStart, ':'.code.toByte())
        if (colon < 0 || colon >= nameEnd) {
            return QNameScan(nameStart, nameEnd, 0, 0, true)
        }
        if (indexOfByte(decoded, colon + 1, ':'.code.toByte()) < nameEnd) {
            return QNameScan(0, 0, 0, 0, false)
        }
        return QNameScan(colon + 1, nameEnd, nameStart, colon, true)
    }

    private data class QNameScan(
        val localStart: Int,
        val localEnd: Int,
        val prefixStart: Int,
        val prefixEnd: Int,
        val ok: Boolean,
    )

    /** Scans one XML name at cursor (XML 1.0 Fifth Edition Name productions;
     * the isNameStart/isNameChar tables of go/xml/parser.go:832-887). */
    private fun scanName(start: Int): Triple<Int, Int, Boolean> {
        if (start >= decoded.size) {
            return Triple(0, 0, false)
        }
        val (first, firstWidth) = decodeScalar(decoded, start)
        if (!isNameStart(first)) {
            return Triple(0, 0, false)
        }
        var cursor = start + firstWidth
        while (cursor < decoded.size) {
            val (r, width) = decodeScalar(decoded, cursor)
            if (!isNameChar(r)) {
                break
            }
            cursor += width
        }
        return Triple(start, cursor, true)
    }

    private fun skipSpaces(start: Int): Int {
        var cursor = start
        while (cursor < decoded.size && isSpace(decoded[cursor])) {
            cursor += 1
        }
        return cursor
    }

    private fun startsWith(start: Int, prefix: String): Boolean {
        if (start + prefix.length > decoded.size) {
            return false
        }
        for (i in prefix.indices) {
            if (decoded[start + i] != prefix[i].code.toByte()) {
                return false
            }
        }
        return true
    }

    private fun decodedEquals(start: Int, end: Int, expected: String): Boolean {
        if (end - start != expected.length) {
            return false
        }
        for (i in expected.indices) {
            if (decoded[start + i] != expected[i].code.toByte()) {
                return false
            }
        }
        return true
    }

    private fun decodedSlice(start: Int, end: Int): String =
        String(decoded, start, end - start, StandardCharsets.UTF_8)

    companion object {
        private fun isSpace(byte: Byte): Boolean =
            byte == ' '.code.toByte() || byte == '\t'.code.toByte() ||
                byte == '\n'.code.toByte() || byte == '\r'.code.toByte()

        private fun indexOf(bytes: ByteArray, from: Int, needle: String): Int {
            if (needle.isEmpty() || from > bytes.size) {
                return -1
            }
            val first = needle[0].code.toByte()
            var cursor = from
            while (cursor <= bytes.size - needle.length) {
                if (bytes[cursor] == first) {
                    var match = true
                    for (i in 1 until needle.length) {
                        if (bytes[cursor + i] != needle[i].code.toByte()) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        return cursor
                    }
                }
                cursor += 1
            }
            return -1
        }

        private fun indexOfByte(bytes: ByteArray, from: Int, needle: Byte): Int {
            var cursor = from
            while (cursor < bytes.size) {
                if (bytes[cursor] == needle) {
                    return cursor
                }
                cursor += 1
            }
            return -1
        }

        /** Decodes one UTF-8 scalar at a boundary; returns the code point
         * and its byte width. */
        private fun decodeScalar(bytes: ByteArray, index: Int): Pair<Int, Int> {
            val first = bytes[index].toInt() and 0xff
            return when {
                first < 0x80 -> first to 1
                first in 0xc2..0xdf ->
                    ((first and 0x1f) shl 6) or (bytes[index + 1].toInt() and 0x3f) to 2

                first in 0xe0..0xef ->
                    (
                        ((first and 0x0f) shl 12) or
                            ((bytes[index + 1].toInt() and 0x3f) shl 6) or
                            (bytes[index + 2].toInt() and 0x3f)
                        ) to 3

                else ->
                    (
                        ((first and 0x07) shl 18) or
                            ((bytes[index + 1].toInt() and 0x3f) shl 12) or
                            ((bytes[index + 2].toInt() and 0x3f) shl 6) or
                            (bytes[index + 3].toInt() and 0x3f)
                        ) to 4
            }
        }

        /** XML 1.0 Fifth Edition NameStartChar. */
        fun isNameStart(r: Int): Boolean =
            when {
                r == ':'.code || r == '_'.code -> true
                r in 'A'.code..'Z'.code -> true
                r in 'a'.code..'z'.code -> true
                r in 0xC0..0xD6 -> true
                r in 0xD8..0xF6 -> true
                r in 0xF8..0x2FF -> true
                r in 0x370..0x37D -> true
                r in 0x37F..0x1FFF -> true
                r in 0x200C..0x200D -> true
                r in 0x2070..0x218F -> true
                r in 0x2C00..0x2FEF -> true
                r in 0x3001..0xD7FF -> true
                r in 0xF900..0xFDCF -> true
                r in 0xFDF0..0xFFFD -> true
                r in 0x10000..0xEFFFF -> true
                else -> false
            }

        /** XML 1.0 Fifth Edition NameChar. */
        fun isNameChar(r: Int): Boolean =
            isNameStart(r) ||
                when (r) {
                    '-'.code, '.'.code -> true
                    in '0'.code..'9'.code -> true
                    0xB7 -> true
                    in 0x300..0x36F -> true
                    in 0x203F..0x2040 -> true
                    else -> false
                }
    }
}

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

/** One namespace declaration seen before start-tag finalization
 * (parser.rs:161-166). */
private class PendingDeclaration(
    val qname: QNameFacts,
    val uri: String,
    val uriSpan: Span,
)

/** One attribute seen before start-tag finalization (parser.rs:151-159). */
private class PendingAttribute(
    val qname: QNameFacts,
    val span: Span,
    val valueSpan: Span,
    val fragments: List<ReferenceFragment>,
    val normalized: String,
    val singleQuote: Boolean,
)

/** One open element frame (parser.rs:168-180). */
private class Frame(
    val start: Int,
    var span: Span,
    val qname: QNameFacts,
    var expanded: ExpandedName?,
    var hasNamespaceError: Boolean,
    var scope: NamespaceScope,
    val namespaces: MutableList<XmlNamespaceBindingData> = mutableListOf(),
    val attributes: MutableList<XmlAttributeData> = mutableListOf(),
    val children: MutableList<Int> = mutableListOf(),
    val pendingDeclarations: MutableList<PendingDeclaration> = mutableListOf(),
    val pendingAttributes: MutableList<PendingAttribute> = mutableListOf(),
)

/**
 * The XML formation engine (parser.rs:182-2074). Immutable inputs, ordered
 * deterministic outputs; recovery only at deterministic markup boundaries.
 */
private class Parser(
    private val source: SourceSnapshot,
    private val decoded: ByteArray,
    private val limits: XmlParseLimits,
) {
    private val authority = DocumentAuthority.fresh()
    private val diagnostics = ArrayList<XmlDiagnostic>()
    private val pieces = ArrayList<StructuralPiece>()
    private val syntaxKinds = ArrayList<XmlSyntaxKind>()
    private val nodes = ArrayList<XmlContent>()
    private val parentOf = ArrayList<Int?>()
    private var nextOrdinal = 0L
    private val entityState = EntityExpansionState()
    private val entities = ArrayList<EntityDeclarationData>()
    private val stack = ArrayList<Frame>()
    private val prolog = ArrayList<XmlPrologItem>()
    private val epilog = ArrayList<XmlPrologItem>()
    private var declaration: XmlDeclarationData? = null
    private var doctype: XmlDoctypeData? = null
    private var doctypeName: QNameFacts? = null
    private var doctypeSpanStart: Int? = null
    private var dtdSubsetStart: Int? = null
    private var externalSubsetRecovered = false
    private var root: Int? = null
    private var recovered = false
    private var errorRegions = 0

    fun parse(): Document {
        coverBom()
        var tokenizer = XmlTokenizer(decoded, 0, { stack.isNotEmpty() }, { root != null })
        while (true) {
            val token = try {
                tokenizer.next()
            } catch (e: TokenizerError) {
                // A tokenizer error jumps the stream to the end of the
                // document (xmlparser Stream::jump_to_end), so the recovery
                // region is always the final byte and tokenization stops
                // (go/xml/parser.go:153-161; parser.rs:255-268).
                val end = decoded.size
                val start = (end - 1).coerceAtLeast(0)
                recoverErrorRegion(start, end)
                break
            } ?: break
            token(token)
        }
        return finish()
    }

    /** Covers a leading BOM as trivia; the tokenizer skips it in decoded
     * text (parser.rs:275-285). */
    private fun coverBom() {
        val bom = source.encodingFacts.bom ?: return
        val len = when (bom) {
            consema.document.BomKind.Utf8 -> 3
            consema.document.BomKind.Utf16Le, consema.document.BomKind.Utf16Be -> 2
        }
        if (len > 0) {
            pushPiece(span(0, len), XmlSyntaxKind.Bom, StructuralPieceKind.Trivia)
        }
    }

    private fun token(token: XmlToken) {
        when (token) {
            is XmlToken.Declaration -> declaration(token)
            is XmlToken.ProcessingInstruction -> processingInstruction(token)
            is XmlToken.Comment -> comment(token)
            is XmlToken.DtdStart -> doctypeStart(token)
            is XmlToken.EmptyDtd -> doctypeEmpty(token)
            is XmlToken.EntityDeclaration -> entityDeclaration(token)
            is XmlToken.DtdEnd -> dtdEnd(token)
            is XmlToken.ElementStart -> elementStart(token)
            is XmlToken.Attribute -> attribute(token)
            is XmlToken.ElementOpenEnd -> elementOpenEnd(token)
            is XmlToken.ElementEmptyEnd -> elementEmptyEnd(token)
            is XmlToken.ElementCloseEnd -> elementCloseEnd(token)
            is XmlToken.Text -> text(token)
            is XmlToken.Cdata -> cdata(token)
        }
    }

    // -- declaration (parser.rs:334-503) --------------------------------------

    private fun declaration(token: XmlToken.Declaration) {
        val raw = rawSpan(token.start, token.end)
        pushPiece(
            rawSpan(token.start, token.start + 5),
            XmlSyntaxKind.DeclarationOpen,
            StructuralPieceKind.Token,
        )
        val standaloneFacts = declarationParts(token)
        if (!decodedEquals(token.versionStart, token.versionEnd, "1.0")) {
            recover(
                "xml.declaration.version@1",
                rawSpan(token.versionStart, token.versionEnd),
                DiagnosticCategory.Syntax,
            )
        }
        val encodingPair = if (token.encodingEnd > token.encodingStart) {
            val encodingRaw = rawSpan(token.encodingStart, token.encodingEnd)
            val upper = decodedSlice(token.encodingStart, token.encodingEnd).uppercase()
            val selected = source.encodingFacts.selected
            val agrees = when (selected) {
                SourceEncoding.Utf8 -> upper == "UTF-8"
                SourceEncoding.Utf16Le -> upper == "UTF-16" || upper == "UTF-16LE"
                SourceEncoding.Utf16Be -> upper == "UTF-16" || upper == "UTF-16BE"
                else -> false
            }
            if (!agrees) {
                recover(
                    "xml.declaration.conflict@1",
                    encodingRaw,
                    DiagnosticCategory.Encoding,
                )
            }
            Pair(encodingRaw, decodedSlice(token.encodingStart, token.encodingEnd))
        } else {
            null
        }
        val declared = XmlDeclarationData(
            span = raw,
            versionSpan = rawSpan(token.versionStart, token.versionEnd),
            version = decodedSlice(token.versionStart, token.versionEnd),
            encoding = encodingPair,
            standalone = standaloneFacts,
        )
        if (declaration != null) {
            recover(
                "xml.declaration.duplicate@1",
                raw,
                DiagnosticCategory.Syntax,
            )
        }
        declaration = declared
    }

    /** Pushes declaration part pieces and locates the standalone value span
     * (parser.rs:398-503). The declaration grammar is fixed, so the walk is
     * deterministic in decoded space; `=`/quote/space bytes between parts
     * remain gaps covered as trivia by the final piece assembly. */
    private fun declarationParts(token: XmlToken.Declaration): Pair<Span, Boolean>? {
        val textEnd = token.end
        var cursor = 5 // past `<?xml`, relative to the declaration span
        fun pushNameValue(name: String, nameStart: Int, valueStart: Int, valueEnd: Int) {
            rawSpanOffset(token.start + nameStart, token.start + nameStart + name.length)?.let {
                pushPiece(it, XmlSyntaxKind.DeclarationName, StructuralPieceKind.Token)
            }
            pushPiece(
                rawSpan(token.start + valueStart, token.start + valueEnd),
                XmlSyntaxKind.DeclarationValue,
                StructuralPieceKind.Token,
            )
        }
        cursor = skipDeclarationSpaces(token.start + cursor) - token.start
        if (startsWithAt(token.start + cursor, "version")) {
            pushNameValue(
                "version", cursor,
                token.versionStart - token.start, token.versionEnd - token.start,
            )
            cursor = token.versionEnd - token.start + 1 // past the closing quote
        }
        if (token.encodingEnd > token.encodingStart) {
            cursor = skipDeclarationSpaces(token.start + cursor) - token.start
            if (startsWithAt(token.start + cursor, "encoding")) {
                pushNameValue(
                    "encoding", cursor,
                    token.encodingStart - token.start, token.encodingEnd - token.start,
                )
                cursor = token.encodingEnd - token.start + 1
            }
        }
        cursor = skipDeclarationSpaces(token.start + cursor) - token.start
        val standaloneFacts = if (token.hasStandalone && startsWithAt(token.start + cursor, "standalone")) {
            rawSpanOffset(token.start + cursor, token.start + cursor + "standalone".length)?.let {
                pushPiece(it, XmlSyntaxKind.DeclarationName, StructuralPieceKind.Token)
            }
            val restStart = token.start + cursor + "standalone".length
            var eq = -1
            var scan = restStart
            while (scan < textEnd && decoded[scan] != '='.code.toByte()) {
                scan += 1
            }
            if (scan < textEnd) {
                eq = scan
            }
            if (eq < 0) {
                null
            } else {
                val valueStart = skipDeclarationSpaces(eq + 1)
                val quote = decoded.getOrNull(valueStart)
                if (quote != '"'.code.toByte() && quote != '\''.code.toByte()) {
                    null
                } else {
                    val valueEnd = indexOfByte(decoded, valueStart + 1, quote!!)
                    if (valueEnd < 0 || valueEnd >= textEnd) {
                        null
                    } else {
                        val valueSpan = rawSpanOffset(valueStart + 1, valueEnd)
                        if (valueSpan == null) {
                            null
                        } else {
                            pushPiece(
                                valueSpan,
                                XmlSyntaxKind.DeclarationValue,
                                StructuralPieceKind.Token,
                            )
                            Pair(valueSpan, token.standalone)
                        }
                    }
                }
            }
        } else {
            null
        }
        if (startsWithAt(textEnd - 2, "?>")) {
            rawSpanOffset(textEnd - 2, textEnd)?.let {
                pushPiece(it, XmlSyntaxKind.DeclarationClose, StructuralPieceKind.Token)
            }
        }
        return standaloneFacts
    }

    // -- processing instruction (parser.rs:505-579) ---------------------------

    private fun processingInstruction(token: XmlToken.ProcessingInstruction) {
        val raw = rawSpan(token.start, token.end)
        val targetRaw = rawSpan(token.targetStart, token.targetEnd)
        if (decodedSlice(token.targetStart, token.targetEnd).equals("xml", ignoreCase = true)) {
            recover("xml.pi.target@1", targetRaw, DiagnosticCategory.Syntax)
        }
        val contentFacts = if (token.contentStart < token.contentEnd) {
            val valueRaw = rawSpan(token.contentStart, token.contentEnd)
            val value = decodedSlice(token.contentStart, token.contentEnd)
            limit("xml.limit.pi@1", utf8Length(value), limits.maxPiLength)
            Pair(valueRaw, value)
        } else {
            null
        }
        if (dtdSubsetStart != null) {
            // A PI inside the internal subset is admitted DTD markup, never a
            // prolog/epilog or element-content occurrence.
            pushPiece(raw, XmlSyntaxKind.DtdMarkup, StructuralPieceKind.Token)
            return
        }
        pushPiece(
            rawSpan(token.start, token.start + 2),
            XmlSyntaxKind.ProcessingInstructionOpen,
            StructuralPieceKind.Token,
        )
        pushPiece(
            targetRaw,
            XmlSyntaxKind.ProcessingInstructionTarget,
            StructuralPieceKind.Token,
        )
        contentFacts?.let { (contentRaw, _) ->
            pushPiece(
                contentRaw,
                XmlSyntaxKind.ProcessingInstructionContent,
                StructuralPieceKind.Token,
            )
        }
        pushPiece(
            rawSpan((token.end - 2).coerceAtLeast(token.start), token.end),
            XmlSyntaxKind.ProcessingInstructionClose,
            StructuralPieceKind.Token,
        )
        val item = XmlPiData(
            ordinal = ordinal(),
            span = raw,
            targetSpan = targetRaw,
            target = decodedSlice(token.targetStart, token.targetEnd),
            content = contentFacts,
        )
        if (stack.isEmpty()) {
            if (root == null) {
                prolog.add(XmlPrologItem.ProcessingInstruction(item))
            } else {
                epilog.add(XmlPrologItem.ProcessingInstruction(item))
            }
        } else {
            pushContent(XmlContent.ProcessingInstruction(item))
        }
    }

    // -- comment (parser.rs:581-644) ------------------------------------------

    private fun comment(token: XmlToken.Comment) {
        val raw = rawSpan(token.start, token.end)
        val value = decodedSlice(token.valueStart, token.valueEnd)
        if (value.contains("--") || value.endsWith('-')) {
            recover(
                "xml.comment.content@1",
                rawSpan(token.valueStart, token.valueEnd),
                DiagnosticCategory.Syntax,
            )
        }
        limit("xml.limit.comment@1", utf8Length(value), limits.maxCommentLength)
        if (dtdSubsetStart != null) {
            // A comment inside the internal subset is admitted DTD markup,
            // never a prolog/epilog or element-content occurrence.
            pushPiece(raw, XmlSyntaxKind.DtdMarkup, StructuralPieceKind.Trivia)
            return
        }
        pushPiece(
            rawSpan(token.start, token.start + 4),
            XmlSyntaxKind.CommentOpen,
            StructuralPieceKind.Trivia,
        )
        val textRaw = rawSpan(token.valueStart, token.valueEnd)
        pushPiece(textRaw, XmlSyntaxKind.CommentText, StructuralPieceKind.Trivia)
        pushPiece(
            rawSpan(token.valueEnd, token.end),
            XmlSyntaxKind.CommentClose,
            StructuralPieceKind.Trivia,
        )
        val item = XmlCommentData(
            ordinal = ordinal(),
            span = raw,
            textSpan = textRaw,
            text = value,
        )
        if (stack.isEmpty()) {
            if (root == null) {
                prolog.add(XmlPrologItem.Comment(item))
            } else {
                epilog.add(XmlPrologItem.Comment(item))
            }
        } else {
            pushContent(XmlContent.Comment(item))
        }
    }

    // -- doctype (parser.rs:646-911) ------------------------------------------

    private fun doctypeStart(token: XmlToken.DtdStart) {
        val raw = rawSpan(token.start, token.end)
        pushDoctypeOpen(raw)
        doctypeCommon(token.nameStart, token.nameEnd, raw)
        if (token.external) {
            externalSubsetRecovered = true
            recover("xml.dtd.external-subset@1", raw, DiagnosticCategory.Conformance)
        }
        doctypeSpanStart = raw.startByte
        dtdSubsetStart = token.end
    }

    private fun doctypeEmpty(token: XmlToken.EmptyDtd) {
        val raw = rawSpan(token.start, token.end)
        pushDoctypeOpen(raw)
        doctypeCommon(token.nameStart, token.nameEnd, raw)
        if (token.external) {
            externalSubsetRecovered = true
            recover("xml.dtd.external-subset@1", raw, DiagnosticCategory.Conformance)
        }
        doctypeSpanStart = raw.startByte
        buildDoctype(raw)
    }

    /** Assembles the immutable DOCTYPE facts once its end is known
     * (parser.rs:691-711). */
    private fun buildDoctype(end: Span) {
        val start = doctypeSpanStart
            ?: throw profileFailure("xml.source.span@1")
        val span = span(start, end.endByte)
        val name = doctypeName ?: throw profileFailure("xml.dtd.name@1")
        // Clone: the declarations must stay live for reference resolution
        // inside the document element after the DTD closes.
        doctype = XmlDoctypeData(
            span = span,
            name = name,
            entities = entities.toList(),
            recovered = externalSubsetRecovered,
        )
    }

    /** Pushes the `<!DOCTYPE` opening piece for a DTD start span
     * (parser.rs:713-718). */
    private fun pushDoctypeOpen(raw: Span) {
        pushPiece(
            span(raw.startByte, raw.startByte + 9),
            XmlSyntaxKind.DoctypeOpen,
            StructuralPieceKind.Token,
        )
    }

    private fun doctypeCommon(nameStart: Int, nameEnd: Int, raw: Span) {
        if (doctype != null) {
            recover(
                "xml.dtd.multiple-doctype@1",
                raw,
                DiagnosticCategory.Syntax,
            )
        }
        val qname = qnameFacts(nameStart, nameEnd)
        limit("xml.limit.qname@1", qname.span.len, limits.maxQnameLength)
        pushPiece(qname.span, XmlSyntaxKind.DoctypeName, StructuralPieceKind.Token)
        doctypeName = qname
    }

    private fun entityDeclaration(token: XmlToken.EntityDeclaration) {
        val raw = rawSpan(token.start, token.end)
        pushPiece(raw, XmlSyntaxKind.DtdMarkup, StructuralPieceKind.Token)
        val text = decodedSlice(token.start, token.end)
        // A parameter entity declaration is spelled `<!ENTITY % name ...`.
        val isParameter = text.length > 8 &&
            text.substring(8).firstOrNull { !it.isAsciiWhitespace() } == '%'
        if (isParameter) {
            recover(
                "xml.dtd.parameter-entity@1",
                raw,
                DiagnosticCategory.Conformance,
            )
            return
        }
        val declaredName = decodedSlice(token.nameStart, token.nameEnd)
        if (token.external) {
            recover("xml.dtd.external-entity@1", raw, DiagnosticCategory.Conformance)
            return
        }
        if (!token.hasValue) {
            return
        }
        val valueText = decodedSlice(token.valueStart, token.valueEnd)
        limit(
            "xml.limit.entity-replacement@1",
            utf8Length(valueText),
            limits.maxAttributeValueLength,
        )
        when (validateReplacementText(valueText)) {
            ReplacementError.ContainsMarkup -> {
                recover("xml.entity.markup@1", raw, DiagnosticCategory.Conformance)
                return
            }
            is ReplacementError.IllegalCharacter -> {
                recover("xml.entity.illegal-character@1", raw, DiagnosticCategory.Syntax)
                return
            }
            null -> {}
        }
        if (valueText.contains('%')) {
            // A `%` inside an entity value is a parameter-entity reference,
            // which the Profile excludes.
            recover("xml.dtd.parameter-entity@1", raw, DiagnosticCategory.Conformance)
            return
        }
        if (predefinedValue(declaredName) != null || declaredName == "xml" || declaredName == "xmlns") {
            recover("xml.entity.reserved-name@1", raw, DiagnosticCategory.Conformance)
            return
        }
        if (entities.any { it.name == declaredName }) {
            recover("xml.entity.duplicate@1", raw, DiagnosticCategory.Syntax)
            return
        }
        val declared = EntityDeclarationData(
            span = raw,
            name = declaredName,
            replacementSpan = rawSpan(token.valueStart, token.valueEnd),
            replacement = valueText,
        )
        val breach = entityState.recordDeclaration(
            utf8Length(valueText),
            valueText.codePointCount(0, valueText.length),
            limits.entityLimits(),
        )
        if (breach != null) {
            entityLimit(breach, raw)
            return
        }
        entities.add(declared)
    }

    private fun dtdEnd(token: XmlToken.DtdEnd) {
        val raw = rawSpan(token.start, token.end)
        pushPiece(raw, XmlSyntaxKind.DoctypeClose, StructuralPieceKind.Token)
        val start = dtdSubsetStart
        if (start != null) {
            val end = token.start
            scanExcludedDtdMarkup(start, end)
            limit("xml.limit.dtd@1", end - start, limits.maxDtdBytes)
            dtdSubsetStart = null
        }
        buildDoctype(raw)
    }

    /** Scans the internal subset raw text for excluded declarations
     * (parser.rs:865-911). Comments are skipped as a whole: their text is
     * character data, so `<!-- <!ELEMENT x> -->` must not be misread as a
     * declaration. All offsets are decoded UTF-8 byte offsets, matching the
     * Rust `&str` scanning (parser.rs:869-910). */
    private fun scanExcludedDtdMarkup(subsetStart: Int, subsetEnd: Int) {
        val markers = listOf("<!ELEMENT", "<!ATTLIST", "<!NOTATION", "<![")
        var searchStart = subsetStart
        while (true) {
            val commentAt = indexOfBounded(decoded, searchStart, subsetEnd, "<!--")
            var bestMarker: Pair<Int, String>? = null
            for (marker in markers) {
                val at = indexOfBounded(decoded, searchStart, subsetEnd, marker)
                if (at >= 0 && (bestMarker == null || at < bestMarker.first)) {
                    bestMarker = at to marker
                }
            }
            when {
                commentAt >= 0 && bestMarker != null && commentAt < bestMarker.first -> {
                    val relativeEnd = indexOfBounded(decoded, commentAt + 4, subsetEnd, "-->")
                    if (relativeEnd < 0) {
                        // An unterminated comment is already a tokenizer
                        // recovery case; nothing further to scan.
                        return
                    }
                    searchStart = relativeEnd + 3
                }
                bestMarker == null -> return
                else -> {
                    val (at, markerText) = bestMarker
                    val raw = rawSpanOffset(at, at + markerText.length) ?: return
                    recover(
                        if (markerText == "<![") {
                            "xml.dtd.conditional-section@1"
                        } else {
                            "xml.dtd.validation-declaration@1"
                        },
                        raw,
                        DiagnosticCategory.Conformance,
                    )
                    searchStart = at + markerText.length
                }
            }
        }
    }

    // -- elements (parser.rs:913-1305) ----------------------------------------

    private fun elementStart(token: XmlToken.ElementStart) {
        val raw = rawSpan(token.start, token.end)
        pushPiece(
            rawSpan(token.start, token.start + 1),
            XmlSyntaxKind.TagOpen,
            StructuralPieceKind.Token,
        )
        pushQNameParts(token.prefixStart, token.prefixEnd, token.localStart, token.localEnd)
        val qname = qnameFactsPair(token.prefixStart, token.prefixEnd, token.localStart, token.localEnd)
        limit("xml.limit.qname@1", qname.span.len, limits.maxQnameLength)
        if (nodes.size >= limits.common.maxNodeCount) {
            throw profileFailure("xml.limit.node@1")
        }
        if (nodes.size >= limits.maxElementCount) {
            throw profileFailure("xml.limit.element@1")
        }
        if (stack.size >= limits.common.maxNestingDepth) {
            throw profileFailure("xml.limit.depth@1")
        }
        // Element-name resolution is deferred to start-tag finalization so
        // that declarations on this very element are in scope (Namespaces
        // 1.0 applies declarations to the whole element regardless of
        // order).
        val scope = stack.lastOrNull()?.scope ?: NamespaceScope.new()
        stack.add(
            Frame(
                start = raw.startByte,
                span = raw,
                qname = qname,
                expanded = null,
                hasNamespaceError = false,
                scope = scope,
            ),
        )
    }

    private fun attribute(token: XmlToken.Attribute) {
        val raw = rawSpan(token.start, token.end)
        val frame = stack.lastOrNull()
            ?: throw profileFailure("xml.syntax.attribute-outside-element@1")
        val declarationCount = frame.pendingDeclarations.size + frame.namespaces.size
        val attributeCount = frame.pendingAttributes.size + frame.attributes.size
        if (attributeCount >= limits.maxAttributeCount ||
            declarationCount >= limits.maxNamespaceDeclarationCount
        ) {
            throw profileFailure("xml.limit.attribute@1")
        }
        val qname = qnameFactsPair(token.prefixStart, token.prefixEnd, token.localStart, token.localEnd)
        val isDeclaration = qname.prefix == "xmlns" ||
            (qname.prefix == null && qname.local == "xmlns")
        // The attribute name is one unit; `xmlns`/`xmlns:p` names are the
        // NamespaceDeclaration kind. QName part pieces are used on element
        // and end-tag names, not here.
        if (isDeclaration) {
            pushPiece(
                qname.span,
                XmlSyntaxKind.NamespaceDeclaration,
                StructuralPieceKind.Token,
            )
        } else {
            pushPiece(
                qname.span,
                XmlSyntaxKind.AttributeName,
                StructuralPieceKind.Token,
            )
        }
        // `=` and the two quote characters are decoded-space offsets; the
        // raw span conversion keeps UTF-16 sources exact.
        val eq = decodedIndexOf(token.localEnd, '='.code.toByte(), token.valueStart)
        if (eq >= 0) {
            pushPiece(
                rawSpan(eq, eq + 1),
                XmlSyntaxKind.Equals,
                StructuralPieceKind.Token,
            )
        }
        val quoteStart = (token.valueStart - 1).coerceAtLeast(token.start)
        pushPiece(
            rawSpan(quoteStart, quoteStart + 1),
            XmlSyntaxKind.Quote,
            StructuralPieceKind.Token,
        )
        // The opening quote is decoded text right before the value span, so
        // single-quote detection is correct for UTF-8 and UTF-16 alike.
        val singleQuote = decoded.getOrNull(quoteStart) == '\''.code.toByte()
        val valueRaw = rawSpan(token.valueStart, token.valueEnd)
        val (fragments, normalized) = valueFragments(token.valueStart, token.valueEnd)
        pushPiece(
            rawSpan(token.valueEnd, token.valueEnd + 1),
            XmlSyntaxKind.Quote,
            StructuralPieceKind.Token,
        )
        val frameAfter = stack.lastOrNull()
            ?: throw profileFailure("xml.syntax.attribute-outside-element@1")
        if (isDeclaration) {
            limit(
                "xml.limit.namespace-uri@1",
                utf8Length(normalized),
                limits.maxNamespaceUriLength,
            )
            frameAfter.pendingDeclarations.add(
                PendingDeclaration(
                    qname = qname,
                    uri = normalized,
                    uriSpan = valueRaw,
                ),
            )
            return
        }
        limit(
            "xml.limit.attribute-value@1",
            utf8Length(normalized),
            limits.maxAttributeValueLength,
        )
        frameAfter.pendingAttributes.add(
            PendingAttribute(
                qname = qname,
                span = raw,
                valueSpan = valueRaw,
                fragments = fragments,
                normalized = normalized,
                singleQuote = singleQuote,
            ),
        )
    }

    /** Resolves element and attribute names once the whole start tag has
     * been read, so declarations on this element apply to every attribute
     * (parser.rs:1065-1174). */
    private fun finalizeStartTag() {
        val frame = stack.lastOrNull() ?: return
        val pendingDeclarations = frame.pendingDeclarations.toList()
        val pendingAttributes = frame.pendingAttributes.toList()
        frame.pendingDeclarations.clear()
        frame.pendingAttributes.clear()
        var scope = frame.scope
        val namespaces = ArrayList<XmlNamespaceBindingData>()
        for (declaration in pendingDeclarations) {
            val prefix = if (declaration.qname.prefix == "xmlns") {
                declaration.qname.local
            } else {
                null
            }
            try {
                scope = scope.declare(prefix, declaration.uri)
                namespaces.add(
                    XmlNamespaceBindingData(
                        ordinal = ordinal(),
                        span = declaration.qname.span,
                        prefix = prefix,
                        uriSpan = declaration.uriSpan,
                        uri = declaration.uri,
                    ),
                )
            } catch (e: NamespaceException) {
                recover(
                    e.error.code(),
                    declaration.qname.span,
                    DiagnosticCategory.Semantic,
                )
            }
        }
        val elementQname = frame.qname
        var expanded: ExpandedName? = null
        var hasNamespaceError = false
        try {
            expanded = scope.resolveElement(elementQname.qname())
        } catch (e: NamespaceException) {
            hasNamespaceError = true
            recover(
                e.error.code(),
                elementQname.span,
                DiagnosticCategory.Semantic,
            )
        }
        val attributes = ArrayList<XmlAttributeData>()
        for (pending in pendingAttributes) {
            var pendingExpanded: ExpandedName? = null
            try {
                pendingExpanded = scope.resolveAttribute(pending.qname.qname())
            } catch (e: NamespaceException) {
                recover(
                    e.error.code(),
                    pending.qname.span,
                    DiagnosticCategory.Semantic,
                )
            }
            var duplicate = false
            if (pendingExpanded != null) {
                duplicate = attributes.any {
                    it.expanded != null && it.expanded == pendingExpanded
                } || namespaces.any { binding ->
                    NamespaceScope.declarationExpandedName(binding.prefix) == pendingExpanded
                }
            }
            if (duplicate) {
                recover(
                    "xml.namespace.duplicate-attribute@1",
                    pending.qname.span,
                    DiagnosticCategory.Semantic,
                )
            }
            attributes.add(
                XmlAttributeData(
                    ordinal = ordinal(),
                    span = pending.span,
                    qname = pending.qname,
                    expanded = pendingExpanded,
                    singleQuote = pending.singleQuote,
                    valueSpan = pending.valueSpan,
                    fragments = pending.fragments,
                    normalizedValue = pending.normalized,
                ),
            )
        }
        val frameAfter = stack.lastOrNull() ?: return
        frameAfter.scope = scope
        frameAfter.namespaces.addAll(namespaces)
        frameAfter.expanded = expanded
        frameAfter.hasNamespaceError = hasNamespaceError
        frameAfter.attributes.addAll(attributes)
    }

    private fun elementOpenEnd(token: XmlToken.ElementOpenEnd) {
        val raw = rawSpan(token.start, token.end)
        pushPiece(raw, XmlSyntaxKind.TagClose, StructuralPieceKind.Token)
        finalizeStartTag()
        val frame = stack.lastOrNull()
        if (frame != null) {
            frame.span = span(frame.start, raw.endByte)
        }
    }

    private fun elementEmptyEnd(token: XmlToken.ElementEmptyEnd) {
        val raw = rawSpan(token.start, token.end)
        pushPiece(raw, XmlSyntaxKind.EmptyElementClose, StructuralPieceKind.Token)
        val frame = stack.lastOrNull()
        if (frame != null) {
            frame.span = span(frame.start, raw.endByte)
        }
        finalizeStartTag()
        closeFrame(raw)
    }

    private fun elementCloseEnd(token: XmlToken.ElementCloseEnd) {
        val raw = rawSpan(token.start, token.end)
        pushPiece(
            rawSpan(token.start, token.start + 2),
            XmlSyntaxKind.EndTagOpen,
            StructuralPieceKind.Token,
        )
        pushQNameParts(token.prefixStart, token.prefixEnd, token.localStart, token.localEnd)
        pushPiece(
            rawSpan((token.end - 1).coerceAtLeast(token.start), token.end),
            XmlSyntaxKind.TagClose,
            StructuralPieceKind.Token,
        )
        val endQname = qnameFactsPair(token.prefixStart, token.prefixEnd, token.localStart, token.localEnd)
        stack.lastOrNull()?.let { frame ->
            if (frame.qname.qname() != endQname.qname()) {
                recover(
                    "xml.tree.mismatched-end-tag@1",
                    endQname.span,
                    DiagnosticCategory.Syntax,
                )
            }
        }
        closeFrame(raw)
    }

    private fun closeFrame(endTagSpan: Span) {
        val frame = stack.removeLastOrNull()
        if (frame == null) {
            // An extra end tag cannot close any proven element; it is a
            // recovery case at a deterministic markup boundary.
            recover(
                "xml.tree.extra-end-tag@1",
                endTagSpan,
                DiagnosticCategory.Syntax,
            )
            return
        }
        val index = nodes.size
        val element = XmlElementData(
            index = index,
            span = frame.span,
            qname = frame.qname,
            expanded = frame.expanded,
            hasNamespaceError = frame.hasNamespaceError,
            scope = frame.scope,
            namespaces = frame.namespaces.toList(),
            attributes = frame.attributes.toList(),
            children = frame.children.toList(),
        )
        // Every child content item attached to this element now knows its
        // parent arena index.
        for (child in element.children) {
            parentOf[child] = index
        }
        parentOf.add(null)
        nodes.add(XmlContent.Element(element))
        val parent = stack.lastOrNull()
        if (parent != null) {
            if (parent.children.size >= limits.maxMixedContentItems) {
                // Child elements respect the same hard mixed-content budget
                // as text/CDATA/comment/PI; dropping publishes a diagnostic
                // and never passes silently.
                recover(
                    "xml.limit.mixed-content@1",
                    nodes[index].span,
                    DiagnosticCategory.Conformance,
                )
            } else {
                parent.children.add(index)
            }
        } else if (root == null) {
            root = index
        } else {
            recover(
                "xml.tree.multiple-roots@1",
                nodes[index].span,
                DiagnosticCategory.Syntax,
            )
        }
    }

    // -- text and CDATA (parser.rs:1307-1458) ---------------------------------

    private fun text(token: XmlToken.Text) {
        val raw = rawSpan(token.start, token.end)
        val value = decodedSlice(token.start, token.end)
        val whitespaceOnly = value.all { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        if (stack.isEmpty()) {
            if (whitespaceOnly) {
                pushWhitespacePieces(token.start, token.end)
                val item = XmlPrologItem.Whitespace(raw)
                if (root == null) {
                    prolog.add(item)
                } else {
                    epilog.add(item)
                }
                return
            }
            // Non-whitespace character data outside the document element is
            // recovered; the piece is an error region and the literal text is
            // still preserved as an orphan text occurrence.
            recover(
                "xml.syntax.text-outside-root@1",
                raw,
                DiagnosticCategory.Syntax,
            )
            pushPiece(raw, XmlSyntaxKind.ErrorRegion, StructuralPieceKind.ErrorRegion)
            val ordinal = ordinal()
            pushContent(
                XmlContent.Text(
                    XmlTextData(
                        ordinal = ordinal,
                        span = raw,
                        fragments = listOf(ReferenceFragment.Literal(raw, value)),
                    ),
                ),
            )
            return
        }
        if (whitespaceOnly) {
            pushWhitespacePieces(token.start, token.end)
        } else {
            val fragments = textFragments(token.start, token.end, XmlSyntaxKind.Text)
            limit("xml.limit.text@1", utf8Length(value), limits.maxTextLength)
            pushContent(
                XmlContent.Text(
                    XmlTextData(
                        ordinal = ordinal(),
                        span = raw,
                        fragments = fragments,
                    ),
                ),
            )
            return
        }
        pushContent(
            XmlContent.Text(
                XmlTextData(
                    ordinal = ordinal(),
                    span = raw,
                    fragments = listOf(ReferenceFragment.Literal(raw, value)),
                ),
            ),
        )
    }

    private fun cdata(token: XmlToken.Cdata) {
        val raw = rawSpan(token.start, token.end)
        pushPiece(
            rawSpan(token.start, token.start + 9),
            XmlSyntaxKind.CdataOpen,
            StructuralPieceKind.Token,
        )
        val textRaw = rawSpan(token.valueStart, token.valueEnd)
        pushPiece(textRaw, XmlSyntaxKind.CdataText, StructuralPieceKind.Token)
        pushPiece(
            rawSpan(token.valueEnd, token.end),
            XmlSyntaxKind.CdataClose,
            StructuralPieceKind.Token,
        )
        val value = decodedSlice(token.valueStart, token.valueEnd)
        limit("xml.limit.cdata@1", utf8Length(value), limits.maxCdataLength)
        pushContent(
            XmlContent.Cdata(
                XmlCdataData(
                    ordinal = ordinal(),
                    span = raw,
                    textSpan = textRaw,
                    text = value,
                ),
            ),
        )
    }

    private fun pushContent(item: XmlContent) {
        val frame = stack.lastOrNull()
        if (frame != null) {
            if (frame.children.size >= limits.maxMixedContentItems) {
                // The item is dropped under the hard budget, never silently:
                // recovery always publishes a diagnostic and the source bytes
                // stay covered by their structural piece.
                recover(
                    "xml.limit.mixed-content@1",
                    item.span,
                    DiagnosticCategory.Conformance,
                )
                return
            }
            frame.children.add(nodes.size)
        }
        // The parent table stays index-parallel with the node arena; the
        // owning element fills the entry when it closes.
        parentOf.add(null)
        nodes.add(item)
    }

    /** Splits one whitespace-only text run into Whitespace and LineBreak
     * pieces; CRLF counts as one line break (parser.rs:1424-1458). */
    private fun pushWhitespacePieces(start: Int, end: Int) {
        var index = start
        while (index < end) {
            val lineBreak = decoded[index] == '\n'.code.toByte() || decoded[index] == '\r'.code.toByte()
            val runStart = index
            index += if (lineBreak) {
                if (decoded[index] == '\r'.code.toByte() && index + 1 < end &&
                    decoded[index + 1] == '\n'.code.toByte()
                ) {
                    2
                } else {
                    1
                }
            } else {
                1
            }
            while (index < end &&
                (decoded[index] == '\n'.code.toByte() || decoded[index] == '\r'.code.toByte()) == lineBreak
            ) {
                index += 1
            }
            pushPiece(
                rawSpan(runStart, index),
                if (lineBreak) XmlSyntaxKind.LineBreak else XmlSyntaxKind.Whitespace,
                StructuralPieceKind.Trivia,
            )
        }
    }

    // -- fragments and references (parser.rs:1460-1729) -----------------------

    /**
     * Splits one text or attribute-value occurrence into reference
     * fragments. Each literal emits a `literalKind` piece (Text in character
     * data, AttributeValue in attribute values) and each admitted reference
     * emits its own EntityReference/CharacterReference piece. Failing
     * references recover with a diagnostic and emit no piece; their spans
     * become error-region gaps in the final assembly (parser.rs:1460-1555).
     */
    private fun textFragments(start: Int, end: Int, literalKind: XmlSyntaxKind): List<ReferenceFragment> {
        if (!containsByte(start, end, '&'.code.toByte())) {
            // Fast path: a single literal covers the whole run; the piece
            // and fragment are identical to the tail logic below.
            val span = rawSpan(start, end)
            pushPiece(span, literalKind, StructuralPieceKind.Token)
            return listOf(ReferenceFragment.Literal(span, decodedSlice(start, end)))
        }
        val fragments = ArrayList<ReferenceFragment>()
        var cursor = 0
        var index = 0
        while (index < end - start) {
            val at = indexOfByte(start + index, '&'.code.toByte())
            if (at < 0 || at >= end) {
                break
            }
            val relative = at - start
            if (relative > cursor) {
                val literalSpan = rawSpan(start + cursor, start + relative)
                pushPiece(literalSpan, literalKind, StructuralPieceKind.Token)
                fragments.add(ReferenceFragment.Literal(literalSpan, decodedSlice(start + cursor, start + relative)))
            }
            val semi = indexOfByte(start + relative + 1, ';'.code.toByte())
            if (semi < 0 || semi >= end) {
                // Unterminated reference: recover and keep the rest literal.
                val restSpan = rawSpan(start + relative, end)
                recover(
                    "xml.reference.malformed@1",
                    restSpan,
                    DiagnosticCategory.Syntax,
                )
                pushPiece(restSpan, literalKind, StructuralPieceKind.Token)
                fragments.add(ReferenceFragment.Literal(restSpan, decodedSlice(start + relative, end)))
                cursor = end - start
                index = end - start
                continue
            }
            val semiRelative = semi - start
            val body = decodedSlice(start + relative + 1, semi)
            val refSpan = rawSpan(start + relative, semi + 1)
            val fragment = resolveReference(body, refSpan, 0)
            if (fragment != null) {
                val kind = when (fragment) {
                    is ReferenceFragment.CharacterReference -> XmlSyntaxKind.CharacterReference
                    is ReferenceFragment.PredefinedEntity,
                    is ReferenceFragment.GeneralEntity,
                    -> XmlSyntaxKind.EntityReference

                    is ReferenceFragment.Literal -> literalKind
                }
                pushPiece(refSpan, kind, StructuralPieceKind.Token)
                fragments.add(fragment)
            }
            cursor = semiRelative + 1
            index = semiRelative + 1
        }
        if (cursor < end - start) {
            val literalSpan = rawSpan(start + cursor, end)
            pushPiece(literalSpan, literalKind, StructuralPieceKind.Token)
            fragments.add(ReferenceFragment.Literal(literalSpan, decodedSlice(start + cursor, end)))
        }
        return fragments
    }

    /**
     * Resolves one `&…;` reference body into a fragment (parser.rs:1557-1645).
     * Both decimal and hexadecimal character references resolve, and both
     * resolve only to legal XML 1.0 characters (RFC 0012 §6,
     * docs/rfcs/0012-...md:236-241; vector case
     * xml.formation.predefined-and-character-references pins `&#65;` as
     * Complete). NOTE: the Rust parser.rs:1579-1584 expression binds the
     * is_xml_char filter to the else branch only (decimal references would
     * never resolve there); the vector and the Go cross-reference
     * (go/xml/parser.go:2015-2034) require both forms, so this
     * implementation follows the vector and filters both forms.
     */
    private fun resolveReference(body: String, refSpan: Span, depth: Int): ReferenceFragment? {
        if (body.startsWith('#')) {
            val digits = body.substring(1)
            val (isHex, value) = if (digits.startsWith('x') || digits.startsWith('X')) {
                val hex = digits.substring(1)
                (
                    hex.isNotEmpty() && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' },
                    hex.toIntOrNull(16),
                    )
            } else {
                (digits.isNotEmpty() && digits.all { it.isDigit() }, digits.toIntOrNull(10))
            }
            val resolved = if (isHex) {
                value?.let { if (it in 0..0x10FFFF) it else null }
            } else {
                value
            }?.let { Char(it) }?.takeIf { isXmlChar(it) }
            if (resolved != null) {
                return ReferenceFragment.CharacterReference(refSpan, resolved)
            }
            recover(
                "xml.reference.invalid-character@1",
                refSpan,
                DiagnosticCategory.Syntax,
            )
            return null
        }
        val predefined = predefinedValue(body)
        if (predefined != null) {
            return ReferenceFragment.PredefinedEntity(refSpan, body, predefined)
        }
        val declared = entities.firstOrNull { it.name == body }
        if (declared == null) {
            recover(
                "xml.entity.unknown@1",
                refSpan,
                DiagnosticCategory.Conformance,
            )
            return null
        }
        val limits = limits.entityLimits()
        val breach = entityState.enterReference(
            utf8Length(declared.replacement),
            declared.replacement.codePointCount(0, declared.replacement.length),
            limits,
        )
        if (breach != null) {
            entityLimit(breach, refSpan)
            return null
        }
        val nested = resolveNested(declared.replacement, refSpan, depth + 1)
        entityState.leaveReference()
        if (nested != null) {
            return ReferenceFragment.GeneralEntity(
                span = refSpan,
                name = body,
                resolved = nested,
                declarationSpan = declared.span,
            )
        }
        recover(
            "xml.entity.cyclic@1",
            refSpan,
            DiagnosticCategory.Conformance,
        )
        return null
    }

    /**
     * Resolves nested references inside one replacement text. Unknown
     * references, cycles, or limit breaches inside replacement text produce
     * no partial native text; the outer reference is rejected
     * (parser.rs:1647-1692).
     */
    private fun resolveNested(replacement: String, sourceSpan: Span, depth: Int): String? {
        if (depth > limits.maxEntityExpansionDepth) {
            return null
        }
        val out = StringBuilder()
        var cursor = 0
        var index = 0
        while (index < replacement.length) {
            val relative = replacement.indexOf('&', index)
            if (relative < 0) {
                break
            }
            val at = relative
            out.append(replacement, cursor, at)
            val semi = replacement.indexOf(';', at + 1)
            if (semi < 0) {
                return null
            }
            val body = replacement.substring(at + 1, semi)
            val fragment = resolveReference(body, sourceSpan, depth)
            when (fragment) {
                is ReferenceFragment.CharacterReference -> out.append(fragment.resolved)
                is ReferenceFragment.PredefinedEntity -> out.append(fragment.resolved)
                is ReferenceFragment.GeneralEntity -> out.append(fragment.resolved)
                is ReferenceFragment.Literal -> out.append(fragment.text)
                null -> return null
            }
            cursor = semi + 1
            index = semi + 1
        }
        out.append(replacement, cursor, replacement.length)
        return out.toString()
    }

    /** Splits an attribute value into fragments and applies XML 1.0 CDATA
     * normalization to the semantic value (parser.rs:1694-1729). */
    private fun valueFragments(start: Int, end: Int): Pair<List<ReferenceFragment>, String> {
        val fragments = textFragments(start, end, XmlSyntaxKind.AttributeValue)
        val normalized = StringBuilder()
        for (fragment in fragments) {
            when (fragment) {
                is ReferenceFragment.Literal -> {
                    for (c in fragment.text) {
                        normalized.append(
                            if (c == '\t' || c == '\n' || c == '\r' || c == ' ') ' ' else c,
                        )
                    }
                }
                is ReferenceFragment.CharacterReference -> normalized.append(fragment.resolved)
                is ReferenceFragment.PredefinedEntity,
                is ReferenceFragment.GeneralEntity,
                -> {
                    for (c in fragment.resolved) {
                        normalized.append(
                            if (c == '\t' || c == '\n' || c == '\r' || c == ' ') ' ' else c,
                        )
                    }
                }
            }
        }
        return fragments to normalized.toString()
    }

    // -- recovery and limits (parser.rs:1731-1790) ----------------------------

    /** Records a recovery diagnostic with its exact failing span
     * (parser.rs:1731-1749). */
    private fun recover(code: String, span: Span, category: DiagnosticCategory) {
        recovered = true
        if (errorRegions >= limits.maxRecoveryRegions) {
            return
        }
        errorRegions += 1
        diagnostics.add(
            sourceDiagnostic(
                authority,
                code,
                category,
                Severity.Error,
                span.startByte,
                span.endByte,
                diagnostics.size.toULong(),
            ),
        )
    }

    private fun entityLimit(breach: ExpansionBreach, span: Span) {
        recover(breach.code(), span, DiagnosticCategory.Conformance)
    }

    /** Covers one tokenizer error region and publishes the well-formedness
     * diagnostic (parser.rs:1759-1786). */
    private fun recoverErrorRegion(start: Int, end: Int) {
        recovered = true
        if (errorRegions >= limits.maxRecoveryRegions) {
            return
        }
        errorRegions += 1
        val rawStart = rawOffset(start)
        val rawEnd = rawOffset(end)
        val span = span(rawStart, rawEnd)
        pushPiece(span, XmlSyntaxKind.ErrorRegion, StructuralPieceKind.ErrorRegion)
        diagnostics.add(
            sourceDiagnostic(
                authority,
                "xml.syntax.well-formedness@1",
                DiagnosticCategory.Syntax,
                Severity.Error,
                span.startByte,
                span.endByte,
                diagnostics.size.toULong(),
            ),
        )
    }

    // -- finish (parser.rs:1792-1914) -----------------------------------------

    private fun finish(): Document {
        if (stack.isNotEmpty()) {
            recovered = true
            diagnostics.add(
                XmlDiagnostic(
                    "xml.tree.unclosed-element@1",
                    DiagnosticCategory.Syntax,
                    Severity.Error,
                    null,
                    diagnostics.size.toULong(),
                ),
            )
        }
        if (root == null) {
            recovered = true
            diagnostics.add(
                XmlDiagnostic(
                    "xml.tree.missing-root@1",
                    DiagnosticCategory.Syntax,
                    Severity.Error,
                    null,
                    diagnostics.size.toULong(),
                ),
            )
        }
        if (root != null && doctypeName != null) {
            val rootData = nodes[root!!] as? XmlContent.Element
                ?: throw profileFailure("xml.tree.root@1")
            if (rootData.data.qname.qname() != doctypeName!!.qname()) {
                recover(
                    "xml.doctype.root-mismatch@1",
                    rootData.data.qname.span,
                    DiagnosticCategory.Syntax,
                )
            }
        }
        val status = if (recovered) FormationStatus.Recovered else FormationStatus.Complete
        val sourceLen = source.len
        // Pair every piece with its kind before any ordering, so sorting can
        // never desynchronize the two parallel arrays.
        val originalPieces = pieces.toList()
        val originalKinds = syntaxKinds.toList()
        pieces.clear()
        syntaxKinds.clear()
        val paired = originalPieces.indices.map { index ->
            Pair(originalPieces[index], originalKinds[index])
        }.sortedBy { it.first.span.startByte }
        val finalPieces = ArrayList<StructuralPiece>(paired.size + 8)
        val finalKinds = ArrayList<XmlSyntaxKind>(paired.size + 8)
        var next = 0
        for ((piece, kind) in paired) {
            val start = piece.span.startByte
            if (start > next) {
                val gap = span(next, start)
                // In a Complete document the tokenizer only skips whitespace;
                // in a Recovered document the gap is unproven content.
                if (recovered) {
                    pushPiece(gap, XmlSyntaxKind.ErrorRegion, StructuralPieceKind.ErrorRegion)
                } else {
                    pushPiece(gap, XmlSyntaxKind.Whitespace, StructuralPieceKind.Trivia)
                }
            }
            next = piece.span.endByte
            finalPieces.add(piece)
            finalKinds.add(kind)
        }
        if (next < sourceLen) {
            val gap = span(next, sourceLen)
            if (recovered) {
                pushPiece(gap, XmlSyntaxKind.ErrorRegion, StructuralPieceKind.ErrorRegion)
            } else {
                pushPiece(gap, XmlSyntaxKind.Whitespace, StructuralPieceKind.Trivia)
            }
        }
        // Gap pieces were pushed in increasing offset order; append them to
        // the final arrays, then pair and sort the complete set once for
        // deterministic output with kinds never desynchronized from pieces.
        for (index in pieces.indices) {
            finalPieces.add(pieces[index])
            finalKinds.add(syntaxKinds[index])
        }
        val finalPaired = finalPieces.indices.map { index ->
            Pair(finalPieces[index], finalKinds[index])
        }.sortedBy { it.first.span.startByte }
        val structural = finalPaired.map { it.first }
        val pairedKinds = finalPaired.map { it.second }
        val index = try {
            LosslessStructuralIndex.new(authority.identity, sourceLen, structural)
        } catch (e: consema.document.LocationException) {
            throw profileFailure("xml.source.coverage@1")
        }
        val sortedDiagnostics = diagnostics.sortedWith(::deterministicDiagnosticOrder)
        return Document(
            authority = authority,
            source = source,
            profile = XmlProfile.SafeV1,
            structuralIndex = index,
            syntaxKindList = pairedKinds,
            formationStatus = status,
            diagnosticsList = sortedDiagnostics,
            declarationData = declaration,
            doctypeData = doctype,
            prologItems = prolog,
            epilogItems = epilog,
            rootIndex = root,
            nodes = nodes,
            parentOf = parentOf,
            parseLimits = limits,
        )
    }

    // -- qname helpers (parser.rs:1916-2007) ----------------------------------

    private fun qnameFacts(start: Int, end: Int): QNameFacts {
        val text = decodedSlice(start, end)
        val raw = rawSpan(start, end)
        val colon = text.indexOf(':')
        if (colon < 0) {
            return QNameFacts(
                prefix = null,
                local = text,
                span = raw,
                prefixSpan = null,
                localSpan = raw,
            )
        }
        val prefix = text.substring(0, colon)
        val local = text.substring(colon + 1)
        return QNameFacts(
            prefix = prefix,
            local = local,
            span = raw,
            prefixSpan = rawSpanOffset(start, start + colon),
            localSpan = rawSpanOffset(start + colon + 1, end),
        )
    }

    /** Pushes the QName part pieces for one element or end-tag name
     * (parser.rs:1944-1976). */
    private fun pushQNameParts(prefixStart: Int, prefixEnd: Int, localStart: Int, localEnd: Int) {
        if (prefixEnd <= prefixStart) {
            pushPiece(
                rawSpan(localStart, localEnd),
                XmlSyntaxKind.LocalName,
                StructuralPieceKind.Token,
            )
        } else {
            pushPiece(
                rawSpan(prefixStart, prefixEnd),
                XmlSyntaxKind.Prefix,
                StructuralPieceKind.Token,
            )
            pushPiece(
                rawSpan(prefixEnd, localStart),
                XmlSyntaxKind.Colon,
                StructuralPieceKind.Token,
            )
            pushPiece(
                rawSpan(localStart, localEnd),
                XmlSyntaxKind.LocalName,
                StructuralPieceKind.Token,
            )
        }
    }

    private fun qnameFactsPair(
        prefixStart: Int,
        prefixEnd: Int,
        localStart: Int,
        localEnd: Int,
    ): QNameFacts {
        val hasPrefix = prefixEnd > prefixStart
        val start = if (hasPrefix) prefixStart else localStart
        val span = rawSpanOffset(start, localEnd)
            ?: throw profileFailure("xml.source.span@1")
        return QNameFacts(
            prefix = if (hasPrefix) decodedSlice(prefixStart, prefixEnd) else null,
            local = decodedSlice(localStart, localEnd),
            span = span,
            prefixSpan = if (hasPrefix) rawSpan(prefixStart, prefixEnd) else null,
            localSpan = rawSpan(localStart, localEnd),
        )
    }

    // -- offsets and pieces (parser.rs:2009-2073) -----------------------------

    private fun ordinal(): Long {
        val ordinal = nextOrdinal
        nextOrdinal += 1
        return ordinal
    }

    private fun limit(code: String, value: Int, max: Int) {
        if (value > max) {
            throw profileFailure(code)
        }
    }

    private fun utf8Length(text: String): Int =
        text.toByteArray(StandardCharsets.UTF_8).size

    private fun rawOffset(decodedOffset: Int): Int {
        if (source.encodingFacts.selected === SourceEncoding.Utf8) {
            return decodedOffset
        }
        return try {
            source.rawByteAt(DecodedOffset.Utf8Byte(decodedOffset))
        } catch (e: consema.document.LocationException) {
            throw profileFailure("xml.source.span@1")
        }
    }

    private fun rawSpanOffset(start: Int, end: Int): Span? {
        if (start < 0 || end < start) {
            return null
        }
        val startRaw = rawOffset(start)
        val endRaw = rawOffset(end)
        return span(startRaw, endRaw)
    }

    private fun rawSpan(start: Int, end: Int): Span =
        rawSpanOffset(start, end) ?: throw profileFailure("xml.source.span@1")

    private fun span(start: Int, end: Int): Span =
        try {
            authority.span(start, end)
        } catch (e: consema.document.LocationException) {
            throw profileFailure("xml.source.span@1")
        }

    private fun pushPiece(span: Span, kind: XmlSyntaxKind, structural: StructuralPieceKind) {
        pieces.add(StructuralPiece(span, structural))
        syntaxKinds.add(kind)
    }

    private fun decodedSlice(start: Int, end: Int): String =
        String(decoded, start, end - start, StandardCharsets.UTF_8)

    private fun decodedEquals(start: Int, end: Int, expected: String): Boolean {
        if (end - start != expected.length) {
            return false
        }
        for (i in expected.indices) {
            if (decoded[start + i] != expected[i].code.toByte()) {
                return false
            }
        }
        return true
    }

    private fun containsByte(start: Int, end: Int, needle: Byte): Boolean {
        for (i in start until end) {
            if (decoded[i] == needle) {
                return true
            }
        }
        return false
    }

    private fun indexOfByte(from: Int, needle: Byte): Int {
        var cursor = from
        while (cursor < decoded.size) {
            if (decoded[cursor] == needle) {
                return cursor
            }
            cursor += 1
        }
        return -1
    }

    /** Finds one ASCII needle within [from, until). */
    private fun indexOfBounded(from: Int, until: Int, needle: String): Int {
        if (needle.isEmpty() || from > until) {
            return -1
        }
        val first = needle[0].code.toByte()
        var cursor = from
        while (cursor <= until - needle.length) {
            if (decoded[cursor] == first) {
                var match = true
                for (i in 1 until needle.length) {
                    if (decoded[cursor + i] != needle[i].code.toByte()) {
                        match = false
                        break
                    }
                }
                if (match) {
                    return cursor
                }
            }
            cursor += 1
        }
        return -1
    }

    private fun decodedIndexOf(from: Int, needle: Byte, until: Int): Int {
        var cursor = from
        while (cursor < until && cursor < decoded.size) {
            if (decoded[cursor] == needle) {
                return cursor
            }
            cursor += 1
        }
        return -1
    }

    private fun startsWithAt(start: Int, prefix: String): Boolean {
        if (start + prefix.length > decoded.size) {
            return false
        }
        for (i in prefix.indices) {
            if (decoded[start + i] != prefix[i].code.toByte()) {
                return false
            }
        }
        return true
    }

    private fun skipDeclarationSpaces(start: Int): Int {
        var cursor = start
        while (cursor < decoded.size &&
            (decoded[cursor] == ' '.code.toByte() || decoded[cursor] == '\t'.code.toByte() ||
                decoded[cursor] == '\n'.code.toByte() || decoded[cursor] == '\r'.code.toByte())
        ) {
            cursor += 1
        }
        return cursor
    }
}
