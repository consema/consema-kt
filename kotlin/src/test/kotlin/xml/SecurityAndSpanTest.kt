// Security boundary and span-exactness intent checks for the xml family.
//
// Data authority:
//   - RFC 0012 §3 (https://github.com/consema/consema/blob/main/docs/rfcs/0012-xml-1.0-safe-profile-v1.md:83-130):
//     entity deny-by-default — no external entity expansion, no external
//     subset fetch, no markup-generating replacement text; the parser never
//     opens another entity, file, URI, network connection, registry,
//     classpath, or catalog (RFC 0012 §1).
//   - The recovered codes are the frozen xml.* codes of
//     https://github.com/consema/consema-rs/blob/main/consema-xml/src/parser.rs (each cited in the test).
//   - conformance/vectors/xml-1-0-safe-v1.json cases xml.formation.* and
//     xml.limit.* pin the status and diagnostic outcomes.

package xml

import consema.document.FormationStatus
import consema.xml.ReferenceFragment
import consema.xml.XmlEncodingSelection
import consema.xml.XmlParseLimits
import consema.xml.XmlProfile
import consema.xml.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecurityAndSpanTest {

    private fun parseUtf8(source: String, limits: XmlParseLimits = XmlParseLimits.default):
        consema.xml.Document =
        parse(
            source.toByteArray(Charsets.UTF_8),
            XmlProfile.SafeV1,
            XmlEncodingSelection.ProfileDefault,
            limits,
        )

    private fun codes(document: consema.xml.Document): List<String> =
        document.diagnostics().map { it.code }

    @Test
    fun `external subset is denied without any io`() {
        // Case xml.formation.external-subset-recovered
        // (xml-1-0-safe-v1.json:127-137): the SYSTEM identifier is never
        // fetched; formation is Recovered with xml.dtd.external-subset@1.
        val document = parseUtf8("<!DOCTYPE root SYSTEM \"http://evil.example/x.dtd\"><root/>")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue("xml.dtd.external-subset@1" in codes(document), codes(document).toString())
    }

    @Test
    fun `unknown entity references never expand`() {
        // Case xml.formation.unknown-entity-recovered
        // (xml-1-0-safe-v1.json:139-149): an unknown reference produces no
        // partial native text and recovers with xml.entity.unknown@1.
        val document = parseUtf8("<root>&unknown;</root>")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue("xml.entity.unknown@1" in codes(document), codes(document).toString())
        val root = assertNotNull(document.root())
        val text = assertNotNull(root.children()[0].text())
        // No fragment was resolved; the occurrence exists with empty
        // fragments and its span still covers the reference bytes.
        assertTrue(text.fragments.isEmpty())
        val spanBytes = document.source().rawBytes().copyOfRange(text.span.startByte, text.span.endByte)
        assertEquals("&unknown;", String(spanBytes, Charsets.UTF_8))
    }

    @Test
    fun `markup generating entity replacement is denied`() {
        // RFC 0012 §3: an internal entity whose replacement text can create
        // markup never triggers fallback behavior (parser.rs:787-790,
        // xml.entity.markup@1).
        val document = parseUtf8("<!DOCTYPE root [<!ENTITY x \"a<b\">]><root>&x;</root>")
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue("xml.entity.markup@1" in codes(document), codes(document).toString())
    }

    @Test
    fun `entity amplification ratio bounds expansion`() {
        // Case xml.limit.entity-amplification-recovered
        // (xml-1-0-safe-v1.json:568-579): ratio 2 with 6 references of a
        // 20-byte replacement breaches the amplification budget
        // (parser.rs:1751-1757, xml.entity.amplification@1).
        val limits = XmlParseLimits.default.copy(maxEntityAmplificationRatio = 2)
        val document = parseUtf8(
            "<!DOCTYPE root [<!ENTITY a \"xxxxxxxxxxxxxxxxxxxx\">]><root>&a;&a;&a;&a;&a;&a;</root>",
            limits,
        )
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue("xml.entity.amplification@1" in codes(document), codes(document).toString())
    }

    @Test
    fun `mixed content budget drops children with a diagnostic`() {
        // Case xml.limit.mixed-content-diagnostic (xml-1-0-safe-v1.json:
        // 580-592): max_mixed_content_items 1 drops the child element with
        // xml.limit.mixed-content@1.
        val limits = XmlParseLimits.default.copy(maxMixedContentItems = 1)
        val document = parseUtf8("<root>a<child/></root>", limits)
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue("xml.limit.mixed-content@1" in codes(document), codes(document).toString())
    }

    @Test
    fun `byte exact spans cover every raw byte once`() {
        // RFC 0012 §4/§7: every non-empty raw byte belongs to exactly one
        // ordered structural piece; the lossless index validates the
        // no-gap/no-overlap/final-length invariant (RFC 0003 §7).
        val source = "<root a=\"1\"><child>t</child></root>"
        val document = parseUtf8(source)
        val pieces = document.losslessStructuralIndex().pieces()
        var next = 0
        for (piece in pieces) {
            assertEquals(next, piece.span.startByte, "piece coverage must be gapless")
            assertTrue(piece.span.endByte > piece.span.startByte, "pieces are non-empty")
            next = piece.span.endByte
        }
        assertEquals(document.source().len, next, "pieces must cover the full source")
        assertEquals(
            pieces.size,
            document.losslessSyntaxKinds().size,
            "one kind per piece in the same source order",
        )
    }

    @Test
    fun `predefined entities resolve with their xml meanings`() {
        // Case xml.formation.predefined-and-character-references
        // (xml-1-0-safe-v1.json:41-52): lt, amp, and the decimal character
        // reference resolve; render is byte-exact.
        val source = "<root>a &lt; b &amp; c &#65;</root>"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, String(document.render(), Charsets.UTF_8))
        val root = assertNotNull(document.root())
        val text = assertNotNull(root.children()[0].text())
        val fragments = text.fragments
        assertEquals(6, fragments.size)
        assertEquals("a ", (fragments[0] as ReferenceFragment.Literal).text)
        assertEquals("<", (fragments[1] as ReferenceFragment.PredefinedEntity).resolved)
        assertEquals(" b ", (fragments[2] as ReferenceFragment.Literal).text)
        assertEquals("&", (fragments[3] as ReferenceFragment.PredefinedEntity).resolved)
        assertEquals(" c ", (fragments[4] as ReferenceFragment.Literal).text)
        assertEquals('A', (fragments[5] as ReferenceFragment.CharacterReference).resolved)
        assertEquals("a < b & c A", consema.xml.textSemantic(text))
    }
}
