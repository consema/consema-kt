// Golden edit transcriptions from conformance/vectors/xml-1-0-safe-v1.json
// (cases xml.edit.*).
//
// Data authority: the operation semantics and the render outcomes are
// pinned by the vector cases; the conformance runner resolves name/ordinal
// selectors to NodeRefs exactly as these tests do
// (crates/consema-conformance/src/xml_v1.rs:581-813). The operation IDs
// are the frozen xml.edit.*@1 registrations
// (crates/consema-xml/src/operation_registry.rs:16-75).

package xml

import consema.xml.AttributePlacement
import consema.xml.ContentPlacement
import consema.xml.EditTransactionBuilder
import consema.xml.NameFacts
import consema.xml.XmlEncodingSelection
import consema.xml.XmlParseLimits
import consema.xml.XmlProfile
import consema.xml.commit
import consema.xml.dryRun
import consema.xml.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EditTest {

    private fun parseUtf8(source: String): consema.xml.Document =
        parse(
            source.toByteArray(Charsets.UTF_8),
            XmlProfile.SafeV1,
            XmlEncodingSelection.ProfileDefault,
            XmlParseLimits.default,
        )

    private fun render(document: consema.xml.Document): String =
        String(document.render(), Charsets.UTF_8)

    private fun elementRef(document: consema.xml.Document, local: String): consema.document.NodeRef {
        val index = document.nodes().indexOfFirst { content ->
            content is consema.xml.XmlContent.Element && content.data.qname.local == local
        }
        require(index >= 0) { "element $local not found" }
        return document.occurrenceNodeRef(index.toLong(), consema.document.NodeRole.XmlElement)
    }

    private fun attributeRef(document: consema.xml.Document, local: String): consema.document.NodeRef {
        val ordinal = document.nodes()
            .mapNotNull { content -> (content as? consema.xml.XmlContent.Element)?.data }
            .flatMap { it.attributes }
            .first { it.qname.local == local }
            .ordinal
        return document.occurrenceNodeRef(ordinal, consema.document.NodeRole.XmlAttribute)
    }

    private fun textRef(document: consema.xml.Document, ordinal: Long): consema.document.NodeRef {
        // The NodeRef index is the text occurrence ordinal (the Rust runner
        // find_text, xml_v1.rs:780-795: occurrence_node_ref(data.ordinal,
        // NodeRole::XmlText)).
        val text = document.nodes()
            .mapNotNull { content -> (content as? consema.xml.XmlContent.Text)?.data }
            .first { it.ordinal == ordinal }
        return document.occurrenceNodeRef(text.ordinal, consema.document.NodeRole.XmlText)
    }

    @Test
    fun `set attribute value replaces the value span`() {
        // Case xml.edit.set-attribute-value (xml-1-0-safe-v1.json:437-453).
        val document = parseUtf8("<root a=\"1\"/>")
        val transaction = EditTransactionBuilder.new(document)
            .setAttributeValue(attributeRef(document, "a"), "2")
            .build()
        assertEquals("<root a=\"2\"/>", render(document.commit(transaction).document))
    }

    @Test
    fun `insert and remove element compose in one transaction`() {
        // Case xml.edit.insert-and-remove-element (xml-1-0-safe-v1.json:
        // 455-475).
        val document = parseUtf8("<root><a/></root>")
        val transaction = EditTransactionBuilder.new(document)
            .insertElement(
                assertIs<consema.xml.XmlElement>(document.root()).nodeRef(),
                NameFacts(null, "x", null),
                "c",
                ContentPlacement.End,
            )
            .removeElement(elementRef(document, "a"))
            .build()
        assertEquals("<root><x>c</x></root>", render(document.commit(transaction).document))
    }

    @Test
    fun `rename element rewrites both tags`() {
        // Case xml.edit.rename-element-both-tags (xml-1-0-safe-v1.json:
        // 477-493).
        val document = parseUtf8("<old><child>t</child></old>")
        val transaction = EditTransactionBuilder.new(document)
            .renameElement(elementRef(document, "old"), NameFacts(null, "new", null))
            .build()
        assertEquals("<new><child>t</child></new>", render(document.commit(transaction).document))
    }

    @Test
    fun `insert attribute at end of an empty element`() {
        // Case xml.edit.insert-attribute-end (xml-1-0-safe-v1.json:495-513).
        val document = parseUtf8("<root a=\"1\"/>")
        val transaction = EditTransactionBuilder.new(document)
            .insertAttribute(
                assertIs<consema.xml.XmlElement>(document.root()).nodeRef(),
                NameFacts(null, "b", null),
                "2",
                AttributePlacement.End,
            )
            .build()
        assertEquals("<root a=\"1\" b=\"2\"/>", render(document.commit(transaction).document))
    }

    @Test
    fun `remove attribute consumes its leading whitespace`() {
        // Case xml.edit.remove-attribute (xml-1-0-safe-v1.json:515-530).
        val document = parseUtf8("<root a=\"1\" b=\"2\"/>")
        val transaction = EditTransactionBuilder.new(document)
            .removeAttribute(attributeRef(document, "b"))
            .build()
        assertEquals("<root a=\"1\"/>", render(document.commit(transaction).document))
    }

    @Test
    fun `replace text targets the exact occurrence ordinal`() {
        // Case xml.edit.replace-text-occurrence (xml-1-0-safe-v1.json:
        // 532-548). The operation targets a text occurrence (RoleXmlText);
        // CDATA is never a replacement target (RFC 0012 §11).
        val document = parseUtf8("<root><a>one</a><b>two</b></root>")
        val transaction = EditTransactionBuilder.new(document)
            .replaceText(textRef(document, 1), "TWO")
            .build()
        assertEquals("<root><a>one</a><b>TWO</b></root>", render(document.commit(transaction).document))
    }

    @Test
    fun `rename attribute preserves the value`() {
        // Case xml.edit.rename-attribute (xml-1-0-safe-v1.json:549-565).
        val document = parseUtf8("<root a=\"1\"/>")
        val transaction = EditTransactionBuilder.new(document)
            .renameAttribute(attributeRef(document, "a"), NameFacts(null, "renamed", null))
            .build()
        assertEquals("<root renamed=\"1\"/>", render(document.commit(transaction).document))
    }

    @Test
    fun `dry run matches commit replacement sets and digest`() {
        // RFC 0004 §14/§20: dry-run and commit produce the same replacement
        // set and target digest.
        val document = parseUtf8("<root a=\"1\"/>")
        val transaction = EditTransactionBuilder.new(document)
            .setAttributeValue(attributeRef(document, "a"), "2")
            .build()
        val commit = document.commit(transaction)
        val plan = document.dryRun(transaction, consema.document.EditPlanSourceId.new("test"))
        assertEquals(commit.sourcePatch.baseDigest, plan.baseDigest)
        assertEquals(commit.sourcePatch.targetDigest, plan.targetDigest)
        assertEquals(commit.sourcePatch.replacements(), plan.replacements())
    }
}
