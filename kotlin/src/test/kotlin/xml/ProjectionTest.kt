// Golden projection transcriptions from conformance/vectors/
// xml-1-0-safe-v1.json (cases xml.projection.*).
//
// Data authority: the `xml.element-tree@1` record shape is pinned by the
// vector cases and by https://github.com/consema/consema-rs/blob/main/consema-xml/src/projection.rs; the
// recovered-document failure code xml.projection.recovered-document@1 is
// pinned by case xml.projection.recovered-never-projects
// (xml-1-0-safe-v1.json:341-350).

package xml

import consema.core.PvObject
import consema.core.PvString
import consema.xml.ProjectionRequest
import consema.xml.ProjectionResult
import consema.xml.XmlEncodingSelection
import consema.xml.project
import consema.xml.XmlParseLimits
import consema.xml.XmlProfile
import consema.xml.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectionTest {

    private fun parseUtf8(source: String): consema.xml.Document =
        parse(
            source.toByteArray(Charsets.UTF_8),
            XmlProfile.SafeV1,
            XmlEncodingSelection.ProfileDefault,
            XmlParseLimits.default,
        )

    private fun rootObject(value: consema.core.PortableValue): PvObject =
        assertIs<PvObject>(
            value,
            "projected value must be an object",
        ).get("root").let { assertIs<PvObject>(it) }

    @Test
    fun `element tree record projects the exact root facts`() {
        // Case xml.projection.element-tree-record (xml-1-0-safe-v1.json:
 //).
        val document = parseUtf8("<root a=\"1\"><child>t</child></root>")
        val result = document.project(ProjectionRequest.elementTree())
        val projection = assertIs<ProjectionResult.Complete>(result).projection
        val record = assertIs<PvObject>(projection.value)
        assertEquals(
            PvString("xml.element-tree@1"),
            record.get("record"),
        )
        val root = rootObject(projection.value)
        val name = assertIs<PvObject>(root.get("expanded-name"))
        assertIs<consema.core.PvNull>(name.get("namespace"))
        assertEquals(PvString("root"), name.get("local"))
        val attributes = assertIs<consema.core.PvArray>(root.get("attributes"))
        val attribute = assertIs<PvObject>(attributes.at(0))
        assertEquals(PvString("1"), attribute.get("value"))
        val content = assertIs<consema.core.PvArray>(root.get("content"))
        val child = assertIs<PvObject>(content.at(0))
        assertNotNull(child.get("expanded-name"), "element content has an expanded-name")
        assertEquals(consema.xml.Fidelity.Exact, projection.fidelity)
    }

    @Test
    fun `namespace record projects the resolved uri`() {
        // Case xml.projection.namespace-record (xml-1-0-safe-v1.json:
 //).
        val document = parseUtf8("<p:root xmlns:p=\"urn:p\"/>")
        val result = document.project(ProjectionRequest.elementTree())
        val projection = assertIs<ProjectionResult.Complete>(result).projection
        val root = rootObject(projection.value)
        val name = assertIs<PvObject>(root.get("expanded-name"))
        assertEquals(PvString("urn:p"), name.get("namespace"))
        assertEquals(PvString("root"), name.get("local"))
    }

    @Test
    fun `recovered documents never project`() {
        // Case xml.projection.recovered-never-projects
        // (xml-1-0-safe-v1.json:341-350).
        val document = parseUtf8("<p:root/>")
        val result = document.project(ProjectionRequest.elementTree())
        val failed = assertIs<ProjectionResult.Failed>(result).attempt
        assertEquals("xml.projection.recovered-document@1", failed.diagnostics.first().code)
    }

    @Test
    fun `text content projection is transformed and normalized`() {
        // RFC 0012 §9: text-content projection is always Transformed and
        // requires a policy for including descendant text and CDATA.
        val document = parseUtf8("<root>a<child>t</child><![CDATA[c]]></root>")
        val result = document.project(
            ProjectionRequest.textContent(
                assertNotNull(document.root()).nodeRef(),
                consema.xml.TextContentInclude.TextAndCdata,
            ),
        )
        val projection = assertIs<ProjectionResult.Complete>(result).projection
        assertEquals(consema.xml.Fidelity.Transformed, projection.fidelity)
        assertEquals(PvString("atc"), projection.value)
        assertTrue(
            projection.report.events().any { it.kind == consema.xml.ProjectionEventKind.ElementDiscarded },
        )
    }
}
