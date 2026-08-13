// Transcriptions of the hcl-v1.json formation sample matrices: statuses,
// diagnostic codes, canonical values, and proven attribute names.
//
// Data authority: conformance/vectors/hcl-v1.json cases
// hcl.native-formation.number-matrix (:82-169), .identifiers-keywords
// (:171-224), .unary-compound (:226-278), .operators-precedence (:281-349),
// .source-contract (:387-430), .recovery-matrix (:432-492), .leading-digit-
// rejection (:1684-1707), .invalid-escapes (:1709-1737), .for-key-ambiguity
// (:1751-1779), and the assertion semantics of
// consema-rs/consema-conformance/src/hcl_v1.rs:464-516 (status exact; the
// expected diagnostic code present; canonical value of the first attribute
// expression; proven attribute names exact).

package hcl

import consema.document.FormationStatus
import consema.hcl.HclDiagnostic
import consema.hcl.HclProfile
import consema.hcl.HclLiteralValue
import consema.hcl.literalValue
import consema.hcl.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FormationMatrixTest {

    private fun form(source: String, profile: HclProfile = HclProfile.NATIVE_V1) =
        parse(source.toByteArray(Charsets.UTF_8), profile)

    private fun hasCode(document: consema.hcl.HclDocument, code: String): Boolean =
        document.diagnostics().any { it.code == code }

    /** Vector case hcl.native-formation.number-matrix (hcl-v1.json:82-169):
     * the decimal/exponent matrix and the frozen statuses and codes. */
    @Test
    fun numberMatrix() {
        val samples = listOf(
            "a = 0\n", "a = 42\n", "a = 1.50\n", "a = 1e3\n", "a = 15e-1\n",
            "a = 1E+2\n", "a = 0.5\n", "a = 1e\n", "a = 1.\n", "a = 1.e3\n",
            "a = 0x1F\n", "a = 1_000\n",
        )
        val statuses = listOf(
            FormationStatus.Complete, FormationStatus.Complete, FormationStatus.Complete,
            FormationStatus.Complete, FormationStatus.Complete, FormationStatus.Complete,
            FormationStatus.Complete, FormationStatus.Recovered, FormationStatus.Recovered,
            FormationStatus.Recovered, FormationStatus.Recovered, FormationStatus.Recovered,
        )
        val codes = listOf(
            null, null, null, null, null, null, null,
            "hcl.parse.invalid-number@1", "hcl.parse.newline@1", "hcl.parse.newline@1",
            "hcl.parse.invalid-number@1", "hcl.parse.invalid-number@1",
        )
        val canonicalValues = listOf(
            "0", "42", "1.5", "1000", "1.5", "100", "0.5",
            null, null, null, null, null,
        )
        for ((index, source) in samples.withIndex()) {
            val document = form(source)
            assertEquals(statuses[index], document.formationStatus(), "sample $index: $source")
            if (codes[index] != null) {
                assertTrue(hasCode(document, codes[index]!!), "sample $index missing ${codes[index]}")
            }
            if (canonicalValues[index] != null) {
                val attribute = document.rootBody().attributes().first()
                val literal = literalValue(attribute.expression().expressionValue())
                val canonical = when (literal) {
                    is HclLiteralValue.Integer -> literal.text
                    is HclLiteralValue.Decimal -> literal.text
                    else -> null
                }
                assertEquals(canonicalValues[index], canonical, "sample $index")
            }
        }
    }

    /** Vector case hcl.native-formation.identifiers-keywords (hcl-v1.json:
     * 171-224): the identifier matrix including Unicode, keyword spellings
     * as names, and leading-underscore rejection. */
    @Test
    fun identifiersAndKeywords() {
        val samples = listOf(
            "foo-bar = 1\n", "变量 = 2\n", "true = 1\n", "false = 2\n",
            "null = 3\n", "true { x = 1 }\n", "_foo = 1\n", "a = _bar\n",
        )
        val statuses = listOf(
            FormationStatus.Complete, FormationStatus.Complete, FormationStatus.Complete,
            FormationStatus.Complete, FormationStatus.Complete, FormationStatus.Complete,
            FormationStatus.Recovered, FormationStatus.Recovered,
        )
        val codes = listOf(
            null, null, null, null, null, null,
            "hcl.parse.identifier@1", "hcl.parse.identifier@1",
        )
        for ((index, source) in samples.withIndex()) {
            val document = form(source)
            assertEquals(statuses[index], document.formationStatus(), "sample $index: $source")
            if (codes[index] != null) {
                assertTrue(hasCode(document, codes[index]!!), "sample $index missing ${codes[index]}")
            }
        }
        // `true = 1` is a valid attribute name (RFC 0014 §4.1).
        assertEquals("true", form("true = 1\n").rootBody().attributes().first().name())
        // `foo_bar` stays a valid identifier (underscore is ID_Continue).
        assertEquals(FormationStatus.Complete, form("foo_bar = 1\n").formationStatus())
    }

    /** Vector case hcl.native-formation.unary-compound (hcl-v1.json:226-
     * 278): the unary compound matrix; unary `+` is a grammar error. */
    @Test
    fun unaryCompound() {
        val samples = listOf(
            "a = -1 + 2\n", "a = 2 * -1\n", "a = -1 * 2\n", "a = !!x\n",
            "a = !true\n", "a = - (1 + 2)\n", "a = -x\n", "a = +1\n",
        )
        val statuses = listOf(
            FormationStatus.Complete, FormationStatus.Complete, FormationStatus.Complete,
            FormationStatus.Complete, FormationStatus.Complete, FormationStatus.Complete,
            FormationStatus.Complete, FormationStatus.Recovered,
        )
        val codes = listOf(null, null, null, null, null, null, null, "hcl.parse.expression@1")
        for ((index, source) in samples.withIndex()) {
            val document = form(source)
            assertEquals(statuses[index], document.formationStatus(), "sample $index: $source")
            if (codes[index] != null) {
                assertTrue(hasCode(document, codes[index]!!))
            }
        }
    }

    /** Vector case hcl.native-formation.operators-precedence (hcl-v1.json:
     * 281-349): precedence edges, parens with embedded newlines, trailing
     * commas and `...` in calls, and the `**`/`foo.0`/`foo::bar()`
     * rejections. */
    @Test
    fun operatorsPrecedence() {
        val samples = listOf(
            "a = 1 + 2 * 3\n", "a = (1 + 2) * 3\n", "a = 2 > 1 && 3 <= 3\n",
            "a = x ? y : z\n", "a = -a == b\n", "a = (\n  1 +\n  2\n)\n",
            "a = myfunc(1, 2,)\n", "a = merge(m1, m2...)\n",
            "a = 2 ** 3\n", "a = foo.0\n", "a = foo::bar()\n",
        )
        val statuses = listOf(
            FormationStatus.Complete, FormationStatus.Complete, FormationStatus.Complete,
            FormationStatus.Complete, FormationStatus.Complete, FormationStatus.Complete,
            FormationStatus.Complete, FormationStatus.Complete,
            FormationStatus.Recovered, FormationStatus.Recovered, FormationStatus.Recovered,
        )
        val codes = listOf(
            null, null, null, null, null, null, null, null,
            "hcl.parse.expression@1", "hcl.parse.expression@1", "hcl.parse.invalid-character@1",
        )
        for ((index, source) in samples.withIndex()) {
            val document = form(source)
            assertEquals(statuses[index], document.formationStatus(), "sample $index: $source")
            if (codes[index] != null) {
                assertTrue(hasCode(document, codes[index]!!), "sample $index missing ${codes[index]}")
            }
        }
    }

    /** Vector case hcl.native-formation.source-contract (hcl-v1.json:387-
     * 430): BOM rejection, CRLF acceptance, lone-CR rejection, and the
     * invalid-UTF-8 fatal failure. */
    @Test
    fun sourceContract() {
        val bom = "\uFEFFa = 1\n"
        val bomMid = "a = 1\n\uFEFFb = 2\n"
        val loneCr = "a = 1\rb = 2\n"
        val crlf = "a = 1\r\nb = 2\r\n"
        val lf = "a = 1\nb = 2\n"
        val invalidUtf8 = byteArrayOf(0x61, 0x20, 0x3d, 0x20, 0x31, 0x0a, 0xff.toByte(), 0x0a)

        val bomDocument = form(bom)
        assertEquals(FormationStatus.Recovered, bomDocument.formationStatus())
        assertTrue(hasCode(bomDocument, "hcl.parse.byte-order-mark@1"))

        val bomMidDocument = form(bomMid)
        assertEquals(FormationStatus.Recovered, bomMidDocument.formationStatus())
        assertTrue(hasCode(bomMidDocument, "hcl.parse.byte-order-mark@1"))

        val loneCrDocument = form(loneCr)
        assertEquals(FormationStatus.Recovered, loneCrDocument.formationStatus())
        assertTrue(hasCode(loneCrDocument, "hcl.parse.lone-cr@1"))

        val crlfDocument = form(crlf)
        assertEquals(FormationStatus.Complete, crlfDocument.formationStatus())
        assertEquals(crlf, crlfDocument.render().toString(Charsets.UTF_8))

        val lfDocument = form(lf)
        assertEquals(FormationStatus.Complete, lfDocument.formationStatus())

        val fatal = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(invalidUtf8, HclProfile.NATIVE_V1)
        }
        assertEquals("hcl.parse.invalid-utf8@1", fatal.code)
    }

    /** Vector case hcl.native-formation.recovery-matrix (hcl-v1.json:432-
     * 492): every recovery boundary and the proven attribute names. */
    @Test
    fun recoveryMatrix() {
        val samples = listOf(
            "a = \"abc\n",
            "a = <<EOT\ncontent\n",
            "a = \"\${ 1 +\"\n",
            "a = [1, 2\n",
            "a = 1 @ 2\nb = 3\n",
            "a = 1 /* one /* two */ still\n",
            "a = <<\"EOT\"\ncontent\nEOT\n",
        )
        val codes = listOf(
            "hcl.parse.unterminated-string@1",
            "hcl.parse.unterminated-heredoc@1",
            "hcl.parse.unterminated-interpolation@1",
            "hcl.parse.expression@1",
            "hcl.parse.invalid-character@1",
            "hcl.parse.newline@1",
            "hcl.parse.expression@1",
        )
        for ((index, source) in samples.withIndex()) {
            val document = form(source)
            assertEquals(FormationStatus.Recovered, document.formationStatus(), "sample $index: $source")
            assertTrue(hasCode(document, codes[index]), "sample $index missing ${codes[index]}")
        }
        // The proven attribute names of sample 5 (hcl-v1.json:479-490).
        val recovered = form(samples[4])
        assertEquals(listOf("a", "b"), recovered.rootBody().attributes().map { it.name() })
        // Every sample with a lexer- or parser-produced error region exposes
        // it as a document-level fact (RFC 0014 §7.1). Sample 6 is a proven
        // attribute with only a missing-terminator diagnostic — the
        // `hcl.parse.newline@1` diagnostic has no error region (RFC 0014
        // §3: recovery happens only at deterministic boundaries).
        for ((index, source) in samples.withIndex()) {
            if (index == 5) {
                continue
            }
            val document = form(source)
            assertTrue(document.errorRegions().isNotEmpty(), "sample $index has no error regions")
        }
    }

    /** Vector case hcl.native-formation.leading-digit-rejection
     * (hcl-v1.json:1684-1707). */
    @Test
    fun leadingDigitRejection() {
        val document = form("1abc = 1\n")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(hasCode(document, "hcl.parse.invalid-number@1"))

        val expression = form("a = 1abc\n")
        assertEquals(FormationStatus.Recovered, expression.formationStatus())
        assertTrue(hasCode(expression, "hcl.parse.expression@1"))
    }

    /** Vector case hcl.native-formation.invalid-escapes (hcl-v1.json:1709-
     * 1737). */
    @Test
    fun invalidEscapes() {
        for (source in listOf("a = \"bad \\q\"\n", "a = \"\\u12\"\n", "a = \"\\U00110000\"\n")) {
            val document = form(source)
            assertEquals(FormationStatus.Recovered, document.formationStatus(), "source: $source")
            assertTrue(hasCode(document, "hcl.parse.invalid-escape@1"), "source: $source")
        }
    }

    /** Vector case hcl.native-formation.for-key-ambiguity (hcl-v1.json:
     * 1751-1779): a literal `for` key must be parenthesized or quoted. */
    @Test
    fun forKeyAmbiguity() {
        val bare = form("a = { for = 1 }\n")
        assertEquals(FormationStatus.Recovered, bare.formationStatus())
        assertTrue(hasCode(bare, "hcl.parse.expression@1"))

        for (source in listOf("a = { (for) = 1 }\n", "a = { \"for\" = 1 }\n")) {
            val document = form(source)
            assertEquals(FormationStatus.Complete, document.formationStatus(), "source: $source")
        }
    }

    /** Vector case hcl.native-formation.directive-strip-markers (hcl-v1.json:
     * 1739-1748): `~` strip markers on directives. */
    @Test
    fun directiveStripMarkers() {
        val source = "a = \"%{~ if x ~}yes%{ endif }\"\nb = \"%{ for k, v in m ~}\${k}%{ endfor }\"\n"
        val document = form(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
    }

    /** The `hcl.parse.duplicate-attribute@1` rule (RFC 0014 §3; hcl-v1.json
     * hcl.native-formation.duplicate-attribute :33-44): Recovered, and the
     * duplicate stays a proven syntax piece but never a native attribute. */
    @Test
    fun duplicateAttribute() {
        val document = form("a = 1\na = 2\nb = 3\n")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(hasCode(document, "hcl.parse.duplicate-attribute@1"))
        assertEquals(listOf("a", "b"), document.rootBody().attributes().map { it.name() })
    }

    /** The `hcl.query.error-regions` input (hcl-v1.json:863-868): an
     * unterminated block produces one `hcl.parse.block@1` error region. */
    @Test
    fun unterminatedBlockErrorRegion() {
        val document = form("a = 1\nb {\n")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        val regions = document.errorRegions()
        assertEquals(1, regions.size)
        assertEquals("hcl.parse.block@1", regions[0].code)
    }

    /** The fatal limit paths of the hcl.limit.* cases (hcl-v1.json:1781-
     * 1970) carry the frozen `hcl.limit.*@1` codes. */
    @Test
    fun limitFailuresAreFatal() {
        fun limited(maxExpressionDepth: Int = 24, maxBodyDepth: Int = 128,
                    maxNumberDigits: Int = 100_000, maxAttributeCount: Int = 1_000_000,
                    maxBlockCount: Int = 1_000_000, maxBodyItemCount: Int = 1_000_000,
                    maxLabelCount: Int = 1_000_000, maxTemplateLen: Int = 16 shl 20,
                    maxHeredocBytes: Int = 16 shl 20, maxTupleElements: Int = 1_000_000,
                    maxObjectEntries: Int = 1_000_000): consema.hcl.HclParseLimits =
            consema.hcl.HclParseLimits.default.copy(
                maxExpressionDepth = maxExpressionDepth,
                maxBodyDepth = maxBodyDepth,
                maxNumberDigits = maxNumberDigits,
                maxAttributeCount = maxAttributeCount,
                maxBlockCount = maxBlockCount,
                maxBodyItemCount = maxBodyItemCount,
                maxLabelCount = maxLabelCount,
                maxTemplateLen = maxTemplateLen,
                maxHeredocBytes = maxHeredocBytes,
                maxTupleElements = maxTupleElements,
                maxObjectEntries = maxObjectEntries,
            )

        // hcl.limit.expression-depth (hcl-v1.json:1782-1794).
        val exprDepth = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a = (((1)))\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxExpressionDepth = 3),
            )
        }
        assertEquals("hcl.limit.expression-depth@1", exprDepth.code)

        // hcl.limit.binary-chain-depth (hcl-v1.json:1796-1809).
        val binaryChain = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a = 1 + 1 + 1 + 1 + 1\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxExpressionDepth = 3),
            )
        }
        assertEquals("hcl.limit.expression-depth@1", binaryChain.code)

        // hcl.limit.body-nesting (hcl-v1.json:1811-1824).
        val bodyNesting = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a = 1\nb {\nc {\nd = 1\n}\n}\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxBodyDepth = 2),
            )
        }
        assertEquals("hcl.limit.body-depth@1", bodyNesting.code)

        // hcl.limit.number-digits (hcl-v1.json:1826-1839).
        val numberDigits = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a = 1e10\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxNumberDigits = 5),
            )
        }
        assertEquals("hcl.limit.number-digits@1", numberDigits.code)

        // hcl.limit.arithmetic-overflow (hcl-v1.json:1841-1851).
        val overflow = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a = 1e99999999999999999999\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
            )
        }
        assertEquals("hcl.limit.number-digits@1", overflow.code)

        // hcl.limit.attribute-count (hcl-v1.json:1853-1866).
        val attributeCount = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a = 1\nb = 2\nc = 3\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxAttributeCount = 2),
            )
        }
        assertEquals("hcl.limit.attribute-count@1", attributeCount.code)

        // hcl.limit.block-count (hcl-v1.json:1868-1881).
        val blockCount = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a {\n}\nb {\n}\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxBlockCount = 1),
            )
        }
        assertEquals("hcl.limit.block-count@1", blockCount.code)

        // hcl.limit.body-item-count (hcl-v1.json:1883-1896).
        val bodyItemCount = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a = 1\nb = 2\nc = 3\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxBodyItemCount = 2),
            )
        }
        assertEquals("hcl.limit.body-item-count@1", bodyItemCount.code)

        // hcl.limit.label-count (hcl-v1.json:1898-1911).
        val labelCount = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "b \"x\" \"y\" {\n}\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxLabelCount = 1),
            )
        }
        assertEquals("hcl.limit.label-count@1", labelCount.code)

        // hcl.limit.template-size (hcl-v1.json:1913-1926).
        val templateSize = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a = \"xxxxxxxxxxxxxxxxxxxxxxxxxx\"\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxTemplateLen = 8),
            )
        }
        assertEquals("hcl.limit.template-len@1", templateSize.code)

        // hcl.limit.heredoc-size (hcl-v1.json:1928-1941).
        val heredocSize = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "h = <<E\none\ntwo\nthree\nE\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxHeredocBytes = 12),
            )
        }
        assertEquals("hcl.limit.heredoc-bytes@1", heredocSize.code)

        // hcl.limit.tuple-elements (hcl-v1.json:1943-1956).
        val tupleElements = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a = [1, 2, 3]\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxTupleElements = 2),
            )
        }
        assertEquals("hcl.limit.tuple-elements@1", tupleElements.code)

        // hcl.limit.object-entries (hcl-v1.json:1958-1971).
        val objectEntries = kotlin.test.assertFailsWith<consema.hcl.HclFormationException> {
            parse(
                "a = {x = 1, y = 2, z = 3}\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
                limited(maxObjectEntries = 2),
            )
        }
        assertEquals("hcl.limit.object-entries@1", objectEntries.code)
    }
}
