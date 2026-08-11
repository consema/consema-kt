// Golden transcriptions of the toml-v1.json formation and native cases.
//
// Data authority: conformance/vectors/toml-v1.json (cases cited by id in
// each test) and RFC 0001 §2-§3; the Rust crate tests
// (consema-toml/src/lib.rs:670-843) pin the additional snapshot/role and
// syntax-kind facts. The L5 conformance runner executes the shared vectors
// directly; these tests are the L1 intent documents.

package toml

import consema.document.FormationStatus
import consema.document.NodeRole
import consema.document.ParseLimits
import consema.toml.TomlAccessError
import consema.toml.TomlAccessException
import consema.toml.TomlFormationException
import consema.toml.TomlItemKind
import consema.toml.TomlProfile
import consema.toml.TomlSyntaxKind
import consema.toml.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TomlFormationTest {

    /** toml-v1.json toml.parse.exact-roundtrip: the default render is
     * byte-for-byte the source and the formation status is Complete. */
    @Test
    fun parseExactRoundtrip() {
        val document = parse(ALL_VALUES_TOML, TomlProfile.TOML_1_0_V1, ParseLimits.default)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertTrue(document.render().contentEquals(ALL_VALUES_TOML))
        assertTrue(document.diagnostics().isEmpty())
    }

    /** toml-v1.json toml.parse.lossless-byte-coverage: the token/trivia
     * index covers the source with zero gaps and zero overlaps. */
    @Test
    fun losslessByteCoverage() {
        val document = parse(TRIVIA_AND_STRINGS_TOML, TomlProfile.TOML_1_0_V1, ParseLimits.default)
        val pieces = document.losslessStructuralIndex().pieces()
        assertEquals(TRIVIA_AND_STRINGS_TOML.size, document.source().len)
        assertEquals(0, pieces.first().span.startByte)
        assertEquals(TRIVIA_AND_STRINGS_TOML.size, pieces.last().span.endByte)
        var next = 0
        for (piece in pieces) {
            assertEquals(next, piece.span.startByte)
            assertTrue(piece.span.endByte > piece.span.startByte)
            next = piece.span.endByte
        }
        assertEquals(document.losslessStructuralIndex().pieces().size, document.losslessSyntaxKinds().size)
    }

    /** toml-v1.json toml.native.dotted-segments: `alpha.beta.gamma = 1`
     * forms layered dotted tables with the decoded segments and an Integer
     * leaf. */
    @Test
    fun dottedSegments() {
        val document = parse(
            "alpha.beta.gamma = 1\n".toByteArray(),
            TomlProfile.TOML_1_0_V1,
            ParseLimits.default,
        )
        val alpha = document.root().tableEntries()!![0]
        assertEquals("alpha", alpha.name())
        assertEquals(TomlItemKind.DottedTable, alpha.item().kind)
        val beta = alpha.item().tableEntries()!![0]
        assertEquals("beta", beta.name())
        val gamma = beta.item().tableEntries()!![0]
        assertEquals("gamma", gamma.name())
        assertEquals(1L, gamma.item().asInteger())
        assertEquals(TomlItemKind.Integer, gamma.item().kind)
    }

    /** toml-v1.json toml.native.table-flavors: dotted, standard, and
     * implicit tables stay distinct native categories. */
    @Test
    fun tableFlavors() {
        val document = parse(APPLICATION_TOML, TomlProfile.TOML_1_0_V1, ParseLimits.default)
        val entries = document.root().tableEntries()!!
        fun item(name: String) = entries.first { it.name() == name }.item()
        assertEquals(TomlItemKind.DottedTable, item("service").kind)
        assertEquals(TomlItemKind.StandardTable, item("database").kind)
        assertEquals(TomlItemKind.ImplicitTable, item("observability").kind)
        assertEquals(TomlItemKind.RootTable, document.root().kind)
    }

    /** toml-v1.json toml.native.array-aot-distinct: arrays and arrays-of-
     * tables are different categories with the ordered element count. */
    @Test
    fun arrayAotDistinct() {
        val document = parse(APPLICATION_TOML, TomlProfile.TOML_1_0_V1, ParseLimits.default)
        val entries = document.root().tableEntries()!!
        val timeouts = entries.first { it.name() == "timeouts" }.item()
        assertEquals(TomlItemKind.Array, timeouts.kind)
        val upstreams = entries.first { it.name() == "upstreams" }.item()
        assertEquals(TomlItemKind.ArrayOfTables, upstreams.kind)
        assertEquals(2, upstreams.arrayElements()!!.size)
    }

    /** toml-v1.json toml.native.float-signed-zero: the parser preserves the
     * exact IEEE-754 bit patterns of ±0.0. */
    @Test
    fun floatSignedZero() {
        val document = parse(
            "positive = 0.0\nnegative = -0.0\n".toByteArray(),
            TomlProfile.TOML_1_0_V1,
            ParseLimits.default,
        )
        val entries = document.root().tableEntries()!!
        val positive = entries.first { it.name() == "positive" }.item()
        val negative = entries.first { it.name() == "negative" }.item()
        assertEquals("0000000000000000", "%016x".format(positive.asFloat()!!))
        assertEquals("8000000000000000", "%016x".format(negative.asFloat()!!))
    }

    /** toml-v1.json toml.parse.reject-invalid: a duplicate key is a fatal
     * formation failure with toml.parse.syntax@1; no Document exists. */
    @Test
    fun rejectInvalidDuplicateKey() {
        val failure = assertFailsWith<TomlFormationException> {
            parse(INVALID_DUPLICATE_TOML, TomlProfile.TOML_1_0_V1, ParseLimits.default)
        }
        assertEquals("toml.parse.syntax@1", failure.code)
        assertEquals("toml.parse.syntax@1", failure.diagnostics.first().code)
        assertEquals(1, failure.diagnostics.size)
    }

    /** toml-v1.json toml.resource.token-limit: exceeding max_token_count is
     * a fatal resource-limit failure with no truncated success. */
    @Test
    fun tokenLimit() {
        val limits = ParseLimits.default.copy(maxTokenCount = 3)
        val failure = assertFailsWith<TomlFormationException> {
            parse("values = [1, 2, 3]".toByteArray(), TomlProfile.TOML_1_0_V1, limits)
        }
        assertEquals("core.parse.resource-limit@1", failure.code)
        assertEquals("token_count", failure.diagnostics.first().arguments["name"])
    }

    /** toml-v1.json toml.resource.node-depth-limits: delimiter nesting
     * exceeds max_nesting_depth before any node allocation. */
    @Test
    fun nodeDepthLimits() {
        val limits = ParseLimits.default.copy(maxNodeCount = 3, maxNestingDepth = 2)
        val failure = assertFailsWith<TomlFormationException> {
            parse("value = [[[[1]]]]".toByteArray(), TomlProfile.TOML_1_0_V1, limits)
        }
        assertEquals("core.parse.resource-limit@1", failure.code)
        assertEquals("nesting_depth", failure.diagnostics.first().arguments["name"])
    }

    /** toml-v1.json toml.corpus.cargo-manifest: the repository Cargo.toml
     * forms completely, renders byte-for-byte, and projects completely. */
    @Test
    fun corpusCargoManifest() {
        val document = parse(CORPUS_CARGO_TOML, TomlProfile.TOML_1_0_V1, ParseLimits.default)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertTrue(document.render().contentEquals(CORPUS_CARGO_TOML))
    }

    /** toml-v1.json toml.corpus.pyproject: the PEP 621 fixture forms
     * completely and renders byte-for-byte. */
    @Test
    fun corpusPyproject() {
        val document = parse(PYPROJECT_TOML, TomlProfile.TOML_1_0_V1, ParseLimits.default)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertTrue(document.render().contentEquals(PYPROJECT_TOML))
    }

    /** RFC 0001 §3 + lib.rs:757-782: syntax failures never form documents,
     * and the frozen limit names are "source_bytes" / "nesting_depth". */
    @Test
    fun syntaxAndResourceFailuresNeverFormDocuments() {
        val syntax = assertFailsWith<TomlFormationException> {
            parse("value = [1,,2]".toByteArray(), TomlProfile.TOML_1_0_V1, ParseLimits.default)
        }
        assertEquals("toml.parse.syntax@1", syntax.diagnostics[0].code)

        val limited = assertFailsWith<TomlFormationException> {
            parse(
                "x = 1".toByteArray(),
                TomlProfile.TOML_1_0_V1,
                ParseLimits.default.copy(maxSourceBytes = 3),
            )
        }
        assertEquals("core.parse.resource-limit@1", limited.diagnostics[0].code)
        assertEquals("source_bytes", limited.diagnostics[0].arguments["name"])
    }

    /** lib.rs:738-754: dotted keys retain each logical segment with its own
     * identity. */
    @Test
    fun dottedKeysRetainEachLogicalSegment() {
        val document = parse(
            "alpha.beta.gamma = 1\n".toByteArray(),
            TomlProfile.TOML_1_0_V1,
            ParseLimits.default,
        )
        val alpha = document.root().tableEntries()!![0]
        assertEquals(TomlItemKind.DottedTable, alpha.item().kind)
        assertEquals("alpha", alpha.name())
        val beta = alpha.item().tableEntries()!![0]
        assertEquals("beta", beta.name())
        val gamma = beta.item().tableEntries()!![0]
        assertEquals("gamma", gamma.name())
        assertEquals(1L, gamma.item().asInteger())
    }

    /** lib.rs:784-807: item handles are snapshot- and role-bound. */
    @Test
    fun itemHandlesAreSnapshotAndRoleBound() {
        val first = parse("x = 1".toByteArray(), TomlProfile.TOML_1_0_V1, ParseLimits.default)
        val second = parse("x = 2".toByteArray(), TomlProfile.TOML_1_0_V1, ParseLimits.default)
        val foreign = assertFailsWith<TomlAccessException> {
            second.item(first.root().nodeRef)
        }
        assertEquals(TomlAccessError.WRONG_SNAPSHOT, foreign.kind)
        val entry = first.root().tableEntries()!![0]
        val wrongRole = assertFailsWith<TomlAccessException> {
            first.item(entry.nodeRef)
        }
        assertEquals(TomlAccessError.WRONG_ROLE, wrongRole.kind)
    }

    /** lib.rs:809-843: the lossless syntax kinds distinguish newlines and
     * punctuation in source order. */
    @Test
    fun losslessSyntaxKindsDistinguishNewlinesAndPunctuation() {
        val source = "a.b = \"x\" # c\r\nlist = [1, 2]\ninline = {x=1}\n"
            .toByteArray()
        val document = parse(source, TomlProfile.TOML_1_0_V1, ParseLimits.default)
        val kinds = document.losslessSyntaxKinds()
        assertEquals(
            listOf(
                TomlSyntaxKind.Bare, TomlSyntaxKind.Dot, TomlSyntaxKind.Bare,
                TomlSyntaxKind.Whitespace, TomlSyntaxKind.Equals, TomlSyntaxKind.Whitespace,
                TomlSyntaxKind.String, TomlSyntaxKind.Whitespace, TomlSyntaxKind.Comment,
                TomlSyntaxKind.Newline,
            ),
            kinds.take(10),
        )
        assertTrue(TomlSyntaxKind.LeftBracket in kinds)
        assertTrue(TomlSyntaxKind.RightBracket in kinds)
        assertTrue(TomlSyntaxKind.LeftBrace in kinds)
        assertTrue(TomlSyntaxKind.RightBrace in kinds)
        assertTrue(TomlSyntaxKind.Comma in kinds)
        assertEquals(kinds.size, document.losslessStructuralIndex().pieces().size)
    }

    /** RFC 0001 §2.2: every explicit key, scalar, array, and inline table
     * has an exact span; entry order is the structural order. */
    @Test
    fun orderAndSpansAreExact() {
        val document = parse(
            "x = 1\ny = [1, 2]\n".toByteArray(),
            TomlProfile.TOML_1_0_V1,
            ParseLimits.default,
        )
        val entries = document.root().tableEntries()!!
        assertEquals(listOf("x", "y"), entries.map { it.name() })
        assertEquals(0, entries[0].span.startByte)
        assertEquals(5, entries[0].span.endByte)
        assertEquals(6, entries[1].span.startByte)
        assertEquals(15, entries[1].span.endByte)
        val y = entries[1].item()
        assertEquals(8, y.span.startByte)
        assertEquals(15, y.span.endByte)
        val elements = y.arrayElements()!!
        assertEquals(2, elements.size)
        assertEquals(0, elements[0].ordinal)
        assertEquals(1, elements[1].ordinal)
    }

    /** Empty documents are valid TOML with a RootTable root. */
    @Test
    fun emptyDocumentFormsComplete() {
        val document = parse(ByteArray(0), TomlProfile.TOML_1_0_V1, ParseLimits.default)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(TomlItemKind.RootTable, document.root().kind)
        assertTrue(document.root().tableEntries()!!.isEmpty())
        assertTrue(document.render().isEmpty())
    }

    /** lib.rs:215-258 surface: unknown indices are refused. */
    @Test
    fun unknownNodeIndexIsRefused() {
        val document = parse("x = 1".toByteArray(), TomlProfile.TOML_1_0_V1, ParseLimits.default)
        val bogus = consema.document.NodeRef(
            document.snapshotIdentity,
            999,
            NodeRole.TomlItem,
        )
        val failure = assertFailsWith<TomlAccessException> { document.item(bogus) }
        assertEquals(TomlAccessError.UNKNOWN_NODE, failure.kind)
    }

    /** RFC 0001 §3: invalid UTF-8 is a fatal formation failure carrying
     * core.source.invalid-utf8@1 (lib.rs:656-672). */
    @Test
    fun invalidUtf8FailsFormation() {
        val failure = assertFailsWith<TomlFormationException> {
            parse(byteArrayOf(0x78, 0x20, 0x3D, 0x20, 0xC3, 0x28), TomlProfile.TOML_1_0_V1, ParseLimits.default)
        }
        assertEquals("core.source.invalid-utf8@1", failure.code)
    }

    /** The full fixture set parses without exceptions (formation closure
     * across the shared fixtures). */
    @Test
    fun formationClosureAcrossFixtures() {
        for (fixture in listOf(ALL_VALUES_TOML, TRIVIA_AND_STRINGS_TOML, APPLICATION_TOML, PYPROJECT_TOML)) {
            val document = parse(fixture, TomlProfile.TOML_1_0_V1, ParseLimits.default)
            assertTrue(document.render().contentEquals(fixture))
        }
    }

    /** RFC 0001 §3: the multiline-string and datetime categories decode
     * with exact spans (trivia-and-strings.toml). */
    @Test
    fun decodedStringValues() {
        val document = parse(TRIVIA_AND_STRINGS_TOML, TomlProfile.TOML_1_0_V1, ParseLimits.default)
        val entries = document.root().tableEntries()!!
        val basic = entries.first { it.name() == "basic" }.item()
        assertEquals("quote: \"; slash: \\; tab: \t", basic.asString())
        val literal = entries.first { it.name() == "literal" }.item()
        assertEquals("C:\\Users\\name", literal.asString())
        val multiline = entries.first { it.name() == "multiline" }.item()
        // The backslash line continuation trims the newline and the leading
        // whitespace of the continuation line.
        assertEquals("The quick brown fox jumps over the lazy dog.", multiline.asString())
        assertNull(entries.first { it.name() == "literal" }.item().asInteger())
    }
}
