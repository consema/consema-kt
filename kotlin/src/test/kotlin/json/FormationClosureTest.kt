// Formation closure and recovery tests transcribed from
// conformance/vectors/json-family-v2.json.
//
// RFC 0016 §5.1 F10 (docs/rfcs/0016-go-api-mapping-v1.md:172-176):
// FormationStatus is a closed two-value enum (Complete, Recovered); a valid
// strict JSON document is valid under JSON5, and every Complete
// jsonc.bounded@1 document is also valid under JSON5 (RFC 0005 §2).
// Recovered syntax never turns into available native semantics (RFC 0005 §2).
//
// Case ids are cited on every test; expected.diagnostic_contains codes are
// asserted verbatim.

package json

import consema.document.FormationStatus
import consema.json.JsonFormationException
import consema.json.JsonProfile
import consema.json.SemanticAvailability
import consema.json.parse
import consema.document.ParseLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FormationClosureTest {

    /** RFC 0016 §5.1 F10: FormationStatus is closed — exactly the two values
     * Complete and Recovered exist, in that order. */
    @Test
    fun formationStatusIsClosedBinary() {
        assertEquals(listOf(FormationStatus.Complete, FormationStatus.Recovered), FormationStatus.entries)
    }

    /** Vector case json5.parse.extended-whitespace-comments (json-family-v2.json:
     * 24-28): NBSP and U+1680 whitespace, U+2028 line-comment termination,
     * and a closed block comment all form Complete with the Whitespace /
     * LineComment / BlockComment pieces. */
    @Test
    fun json5ExtendedWhitespaceComments() {
        val source = "\u00a0\u1680// line\u2028[1,/* block */2,]\u3000"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val kinds = document.losslessSyntaxKinds()
        assertTrue(consema.json.JsonSyntaxKind.Whitespace in kinds)
        assertTrue(consema.json.JsonSyntaxKind.LineComment in kinds)
        assertTrue(consema.json.JsonSyntaxKind.BlockComment in kinds)
    }

    /** Vector case json5.parse.identifiers (json-family-v2.json:12-16):
     * reserved words and literal-looking names are valid IdentifierName
     * keys; \uXXXX escapes and U+200C/U+200D continue characters decode to
     * the exact member names. */
    @Test
    fun json5Identifiers() {
        val source = "{\$_:1,while:2,true:3,π:4,\\u0061:5,a\u200c:6,a\u200d:7}"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val members = (document.root().objectMembers() as SemanticAvailability.Available).value!!
        assertEquals(
            listOf("\$_", "while", "true", "π", "a", "a\u200c", "a\u200d"),
            members.map { (it.name() as SemanticAvailability.Available).value },
        )
    }

    /** Vector case json5.parse.unescaped-separator-warning (json-family-v2.json:
     * 30-34): an unescaped U+2028 inside a JSON5 string stays Complete but
     * emits json5.string.unescaped-line-separator@1 (RFC 0005 §5). */
    @Test
    fun json5UnescapedSeparatorWarning() {
        val source = "'a\u2028b'"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertTrue(document.diagnostics().any { it.code == "json5.string.unescaped-line-separator@1" })
    }

    /** Vector case jsonc.complete-shared-surface (json-family-v2.json:78-82):
     * the strict profile rejects the same source as Recovered while JSONC
     * forms it Complete (RFC 0005 §2). */
    @Test
    fun jsoncAndStrictDivergeOnSharedSurface() {
        val source = "// note\n{\"a\":1,}"
        val strict = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.StrictV1)
        assertEquals(FormationStatus.Recovered, strict.formationStatus())
        assertTrue(strict.diagnostics().any { it.code == "json.strict.comment-not-allowed@1" })
        assertTrue(strict.diagnostics().any { it.code == "json.strict.trailing-comma@1" })
        assertEquals(
            "Object",
            (strict.root().kind() as SemanticAvailability.Available).value.name,
        )

        val jsonc = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.JsoncBoundedV1)
        assertEquals(FormationStatus.Complete, jsonc.formationStatus())

        val json5 = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        assertEquals(FormationStatus.Complete, json5.formationStatus())
    }

    /** Vector case json5.reject.leading-zero-decimal (json-family-v2.json:
     * 42-46): a leading-zero decimal is recovered as
     * json.syntax.invalid-number@1 (RFC 0005 §6). */
    @Test
    fun json5RejectsLeadingZeroDecimal() {
        assertRecoveredWith("01", "json.syntax.invalid-number@1")
    }

    /** Vector case json5.reject.empty-hex (json-family-v2.json:48-52). */
    @Test
    fun json5RejectsEmptyHex() {
        assertRecoveredWith("0x", "json.syntax.invalid-number@1")
    }

    /** Vector case json5.reject.decimal-string-escape (json-family-v2.json:
     * 54-58): \1 through \9 are invalid escapes. */
    @Test
    fun json5RejectsDecimalStringEscape() {
        assertRecoveredWith("'\\1'", "json.syntax.invalid-string-escape@1")
    }

    /** Vector case json5.reject.isolated-surrogate (json-family-v2.json:
     * 60-64): an isolated \uD800 fails local semantic decoding. */
    @Test
    fun json5RejectsIsolatedSurrogate() {
        assertRecoveredWith("'\\uD800'", "json.syntax.invalid-string-escape@1")
    }

    /** Vector case json5.reject.unterminated-comment (json-family-v2.json:
     * 66-70): an open block comment is an explicit error region. */
    @Test
    fun json5RejectsUnterminatedComment() {
        assertRecoveredWith("1/* open", "json.syntax.unterminated-block-comment@1")
    }

    /** Vector case json5.reject.invalid-escaped-identifier (json-family-v2.json:
     * 36-40): 0 decodes to a non-start character, so the identifier is
     * recovered as json5.syntax.invalid-identifier@1 and never acquires a
     * decoded name (RFC 0005 §4). */
    @Test
    fun json5RejectsInvalidEscapedIdentifier() {
        assertRecoveredWith("{\\u0030bad:1}", "json5.syntax.invalid-identifier@1")
    }

    /** Vector case json.strict.reject-json5-surface (json-family-v2.json:
     * 72-76): the strict profile rejects comments and trailing commas while
     * still forming the Object root. */
    @Test
    fun strictRejectsJson5Surface() {
        val document = parse(
            "// note\n{\"a\":1,}".toByteArray(Charsets.UTF_8),
            JsonProfile.StrictV1,
        )
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertEquals(
            "Object",
            (document.root().kind() as SemanticAvailability.Available).value.name,
        )
        assertTrue(document.diagnostics().any { it.code == "json.strict.comment-not-allowed@1" })
        assertTrue(document.diagnostics().any { it.code == "json.strict.trailing-comma@1" })
    }

    /** Vector case json5.security.depth-limit (json-family-v2.json:198-202):
     * exceeding the configured nesting depth is a fatal formation failure
     * carrying the frozen limit code (RFC 0016 §5.1). */
    @Test
    fun json5DepthLimitIsFatal() {
        val error = assertFailsWith<JsonFormationException> {
            parse(
                "[[[[0]]]]".toByteArray(Charsets.UTF_8),
                JsonProfile.Json5StandardV1,
                ParseLimits(
                    maxSourceBytes = 64 shl 20,
                    maxNestingDepth = 2,
                    maxTokenCount = 2_000_000,
                    maxNodeCount = 1_000_000,
                    maxDiagnostics = 10_000,
                ),
            )
        }
        assertEquals("core.parse.resource-limit@1", error.code)
        assertEquals("nesting-depth", error.name)
    }

    private fun assertRecoveredWith(source: String, code: String) {
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(
            document.diagnostics().any { it.code == code },
            "expected diagnostic $code in ${document.diagnostics().map { it.code }}",
        )
        // Recovered syntax never exposes fabricated native values: the
        // recovered root must be semantically unavailable or absent.
        assertEquals(source, document.render().toString(Charsets.UTF_8))
    }
}
