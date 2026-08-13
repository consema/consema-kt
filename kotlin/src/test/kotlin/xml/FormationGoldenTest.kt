// Golden formation transcriptions from conformance/vectors/
// xml-1-0-safe-v1.json (cases xml.formation.*), plus the byte-exact span
// and entity-denial intent checks required by the L3 xml milestone.
//
// Data authority: conformance/vectors/xml-1-0-safe-v1.json is the
// language-neutral pinned suite (aggregate digest cfd6e296 across Rust and
// Go); every case id below is cited verbatim. The recovery diagnostics are
// the frozen xml.* codes of consema-rs/consema-xml/src/parser.rs (each cited in
// the test). The UTF-16 golden bytes are transcribed VERBATIM from the
// vector's render_hex field (case xml.formation.utf16le-with-bom).

package xml

import consema.document.FormationStatus
import consema.xml.XmlEncodingSelection
import consema.xml.XmlParseLimits
import consema.xml.XmlProfile
import consema.xml.parse
import consema.xml.textSemantic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FormationGoldenTest {

    private fun parseUtf8(source: String): consema.xml.Document =
        parse(
            source.toByteArray(Charsets.UTF_8),
            XmlProfile.SafeV1,
            XmlEncodingSelection.ProfileDefault,
            XmlParseLimits.default,
        )

    private fun utf16LeBytes(source: String): ByteArray {
        val bytes = ByteArray(2 + source.length * 2)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xFE.toByte()
        for (i in source.indices) {
            val unit = source[i].code
            bytes[2 + i * 2] = (unit and 0xff).toByte()
            bytes[2 + i * 2 + 1] = ((unit ushr 8) and 0xff).toByte()
        }
        return bytes
    }

    @Test
    fun `basic complete document is byte exact`() {
        // Case xml.formation.basic-complete (xml-1-0-safe-v1.json:5-16).
        val source = "<root a=\"1\"><child>t</child></root>"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, String(document.render(), Charsets.UTF_8))
        assertTrue(document.diagnostics().isEmpty(), "no diagnostics expected")
        val root = assertNotNull(document.root())
        assertEquals("root", root.qname().local)
        assertEquals(1, root.attributes().size)
        assertEquals("1", root.attributes()[0].normalizedValue)
        assertEquals(1, root.children().size)
        val child = assertNotNull(root.children()[0].element())
        assertEquals("child", child.qname().local)
    }

    @Test
    fun `internal entity expansion is complete and resolves`() {
        // Case xml.formation.internal-entity-expansion
        // (xml-1-0-safe-v1.json:54-64).
        val source = "<!DOCTYPE root [<!ENTITY greeting \"hello\">]><root>&greeting;</root>"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, String(document.render(), Charsets.UTF_8))
        val doctype = assertNotNull(document.doctype())
        assertEquals(1, doctype.entities.size)
        assertEquals("greeting", doctype.entities[0].name)
        val root = assertNotNull(document.root())
        val text = assertNotNull(root.children()[0].text())
        assertEquals("hello", textSemantic(text))
        val fragment = assertNotNull(text.fragments.singleOrNull() as? consema.xml.ReferenceFragment.GeneralEntity)
        assertEquals("greeting", fragment.name)
        assertEquals("hello", fragment.resolved)
    }

    @Test
    fun `mixed content order is preserved with all child kinds`() {
        // Case xml.formation.mixed-content-order (xml-1-0-safe-v1.json:65-76).
        val source = "<root>a<child/>b<![CDATA[c]]><!--d--><?pi e?>f</root>"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, String(document.render(), Charsets.UTF_8))
        val root = assertNotNull(document.root())
        val children = root.children()
        // Seven children: text "a", element child, text "b", CDATA, comment,
        // PI, and the final text "f".
        assertEquals(7, children.size)
        assertEquals("a", textSemantic(assertNotNull(children[0].text())))
        assertEquals("child", assertNotNull(children[1].element()).qname().local)
        assertEquals("b", textSemantic(assertNotNull(children[2].text())))
        assertEquals("c", assertNotNull(children[3].cdata()).text)
        assertEquals("d", assertNotNull(children[4].comment()).text)
        val pi = assertNotNull(children[5].processingInstruction())
        assertEquals("pi", pi.target)
        assertEquals("e", pi.content?.second)
        // The final text occurrence is the 7th child (0-based index 6).
        val texts = children.mapNotNull { it.text() }
        assertEquals(listOf("a", "b", "f"), texts.map { textSemantic(it) })
    }

    @Test
    fun `crlf source renders byte exact and normalizes semantically`() {
        // Case xml.formation.crlf-semantic-normalization
        // (xml-1-0-safe-v1.json:77-88).
        val source = "<root>line1\r\nline2</root>"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, String(document.render(), Charsets.UTF_8))
        val text = assertNotNull(assertNotNull(document.root()).children()[0].text())
        assertEquals("line1\nline2", textSemantic(text))
    }

    @Test
    fun `utf16le source with bom is byte exact against the golden hex`() {
        // Case xml.formation.utf16le-with-bom (xml-1-0-safe-v1.json:90-101);
        // render_hex transcribed verbatim: fffe3c0072006f006f0074003e002d4e8765
        // 3c002f0072006f006f0074003e00.
        val source = "<root>中文</root>"
        val bytes = utf16LeBytes(source)
        val document = parse(
            bytes,
            XmlProfile.SafeV1,
            XmlEncodingSelection.ProfileDefault,
            XmlParseLimits.default,
        )
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val expectedHex = "fffe3c0072006f006f0074003e002d4e87653c002f0072006f006f0074003e00"
        val actualHex = document.render().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        assertEquals(expectedHex, actualHex)
        val root = assertNotNull(document.root())
        val text = assertNotNull(root.children()[0].text())
        assertEquals("中文", textSemantic(text))
        // Byte-exact span check: the text occurrence covers exactly the raw
        // bytes of the decoded content under the source index, decoded with
        // the source encoding (the Rust runner's decode_utf16,
        // xml_v1.rs:835-853).
        val span = text.span
        val spanBytes = document.source().rawBytes().copyOfRange(span.startByte, span.endByte)
        assertEquals("中文", String(spanBytes, Charsets.UTF_16LE))
    }

    @Test
    fun `duplicate expanded attribute is recovered with the frozen code`() {
        // Case xml.formation.duplicate-expanded-attribute-recovered
        // (xml-1-0-safe-v1.json:103-113).
        val source = "<root xmlns:p=\"urn:u\" xmlns:q=\"urn:u\" p:a=\"1\" q:a=\"2\"/>"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(
            document.diagnostics().any { it.code == "xml.namespace.duplicate-attribute@1" },
            "diagnostics: ${document.diagnostics().map { it.code }}",
        )
    }

    @Test
    fun `unbound prefix is recovered with the frozen code`() {
        // Case xml.formation.unbound-prefix-recovered
        // (xml-1-0-safe-v1.json:115-125).
        val source = "<p:root/>"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(
            document.diagnostics().any { it.code == "xml.namespace.unbound-prefix@1" },
            "diagnostics: ${document.diagnostics().map { it.code }}",
        )
    }

    @Test
    fun `missing root is recovered with the frozen code`() {
        // Case xml.formation.missing-root-recovered
        // (xml-1-0-safe-v1.json:151-161).
        val source = "<?xml version=\"1.0\"?><!-- nothing -->"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        assertTrue(
            document.diagnostics().any { it.code == "xml.tree.missing-root@1" },
            "diagnostics: ${document.diagnostics().map { it.code }}",
        )
        assertEquals("1.0", document.declaration()?.version)
    }

    @Test
    fun `doctype comment is not excluded markup`() {
        // Case xml.formation.dtd-comment-not-excluded-markup
        // (xml-1-0-safe-v1.json:162-173).
        val source = "<!DOCTYPE root [<!-- <!ELEMENT not-a-decl> -->]><root/>"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, String(document.render(), Charsets.UTF_8))
        assertTrue(document.diagnostics().isEmpty())
    }

    @Test
    fun `default namespace applies to elements and not attributes`() {
        // Case xml.formation.default-namespace-on-elements
        // (xml-1-0-safe-v1.json:17-28).
        val source = "<root xmlns=\"urn:app\" version=\"1\"><child/></root>"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val root = assertNotNull(document.root())
        assertEquals("urn:app", root.expanded()?.namespace)
        val attribute = root.attributes().single()
        assertEquals(null, attribute.expanded?.namespace)
        assertEquals("version", attribute.expanded?.local)
        val child = assertNotNull(root.children()[0].element())
        assertEquals("urn:app", child.expanded()?.namespace)
    }

    @Test
    fun `prefixed namespace resolution follows the vector case`() {
        // Case xml.formation.prefixed-namespace-resolution
        // (xml-1-0-safe-v1.json:29-40).
        val source = "<p:root xmlns:p=\"urn:one\"><p:child xmlns:q=\"urn:two\" q:attr=\"x\"/></p:root>"
        val document = parseUtf8(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(source, String(document.render(), Charsets.UTF_8))
        val root = assertNotNull(document.root())
        assertEquals("urn:one", root.expanded()?.namespace)
        assertEquals("root", root.expanded()?.local)
        val child = assertNotNull(root.children()[0].element())
        assertEquals("urn:one", child.expanded()?.namespace)
        val childAttribute = child.attributes().single()
        assertEquals("urn:two", childAttribute.expanded?.namespace)
        assertEquals("x", childAttribute.normalizedValue)
    }
}
