// Golden syntax-query and native-query transcriptions from
// conformance/vectors/xml-1-0-safe-v1.json (cases xml.syntax-query.* and
// xml.native-query.*).
//
// Data authority: the operator spellings, the match order, and the ordinal
// facts are pinned by the vector cases; the conformance runner builds the
// filter chain exactly as these tests do (https://github.com/consema/consema-rs/blob/main/consema-conformance/src/
// xml_v1.rs). The syntax-piece ordinal is the zero-based source
// order of the piece in the exhaustive lossless index (RFC 0012 §7).

package xml

import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.Domains
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryExpression
import consema.core.PvString
import consema.xml.CancellationToken
import consema.xml.QueryLimits
import consema.xml.XmlEncodingSelection
import consema.xml.XmlParseLimits
import consema.xml.XmlProfile
import consema.xml.executeXmlQuery
import consema.xml.executeXmlSyntaxQuery
import consema.xml.parse
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryTest {

    private fun parseUtf8(source: String): consema.xml.Document =
        parse(
            source.toByteArray(Charsets.UTF_8),
            XmlProfile.SafeV1,
            XmlEncodingSelection.ProfileDefault,
            XmlParseLimits.default,
        )

    private fun capabilities(): CapabilitySet =
        CapabilitySet().apply {
            insert(CapabilityId("core.query.ordered-results", 1))
        }

    private fun syntaxExecutable(vararg filters: OperatorCall): ExecutableQuery =
        ExecutableQuery.bind(
            QueryDefinition(Domains.xmlLosslessSyntaxV1())
                .withExpression(filters.fold(QueryExpression(ExpressionKind.Input)) { expression, filter ->
                    expression.then(filter)
                })
                .validate(),
            capabilities(),
        )

    private fun nativeExecutable(vararg filters: OperatorCall): ExecutableQuery =
        ExecutableQuery.bind(
            QueryDefinition(Domains.xmlNativeV1())
                .withExpression(filters.fold(QueryExpression(ExpressionKind.Input)) { expression, filter ->
                    expression.then(filter)
                })
                .validate(),
            capabilities(),
        )

    @Test
    fun `syntax query kind filter matches local names with exact ordinals`() {
        // Case xml.syntax-query.kind-and-text-filter (xml-1-0-safe-v1.json). NOTE: the vector's informational ordinal column says
        // "10" for the second match, but the language-neutral runner asserts
        // kind and text only (https://github.com/consema/consema-rs/blob/main/consema-conformance/src/xml_v1.rs), and the byte authority emits a Whitespace gap piece
        // between the QName and the attribute (probed against the Rust:
        // pieces tag-open 0, local-name 1, whitespace 2, attribute-name 3,
        // equals 4, quote 5, attribute-value 6, quote 7, tag-close 8, text
        // 9, end-tag-open 10, local-name 11, tag-close 12), so the second
        // local-name match ordinal is 11.
        val document = parseUtf8("<root a=\"1\">t</root>")
        val matches = executeXmlSyntaxQuery(
            syntaxExecutable(
                OperatorCall("xml.syntax-kind-is", 1).withArgument("kind", PvString("local-name")),
            ),
            document,
            QueryLimits.default,
            CancellationToken(),
        )
        assertEquals(2, matches.size)
        assertEquals(listOf("root", "root"), matches.map { match ->
            String(
                document.source().rawBytes().copyOfRange(match.span.startByte, match.span.endByte),
                Charsets.UTF_8,
            )
        })
        assertEquals(listOf(1, 11), matches.map { it.ordinal })
    }

    @Test
    fun `syntax query matches the entity reference piece`() {
        // Case xml.syntax-query.entity-reference-kind (xml-1-0-safe-v1.json).
        val document = parseUtf8("<root>&lt;</root>")
        val matches = executeXmlSyntaxQuery(
            syntaxExecutable(
                OperatorCall("xml.syntax-kind-is", 1)
                    .withArgument("kind", PvString("entity-reference")),
            ),
            document,
            QueryLimits.default,
            CancellationToken(),
        )
        assertEquals(1, matches.size)
        assertEquals("&lt;", String(
            document.source().rawBytes().copyOfRange(matches[0].span.startByte, matches[0].span.endByte),
            Charsets.UTF_8,
        ))
        assertEquals(3, matches[0].ordinal)
    }

    @Test
    fun `syntax query matches the attribute value piece`() {
        // Case xml.syntax-query.attribute-value-kind (xml-1-0-safe-v1.json).
        val document = parseUtf8("<root a=\"1\"/>")
        val matches = executeXmlSyntaxQuery(
            syntaxExecutable(
                OperatorCall("xml.syntax-kind-is", 1)
                    .withArgument("kind", PvString("attribute-value")),
            ),
            document,
            QueryLimits.default,
            CancellationToken(),
        )
        assertEquals(1, matches.size)
        assertEquals("1", String(
            document.source().rawBytes().copyOfRange(matches[0].span.startByte, matches[0].span.endByte),
            Charsets.UTF_8,
        ))
        assertEquals(6, matches[0].ordinal)
    }

    @Test
    fun `native query returns attributes and values`() {
        // Case xml.native-query.attributes-and-values (xml-1-0-safe-v1.json).
        val document = parseUtf8("<root a=\"1\"/>")
        val matches = executeXmlQuery(
            nativeExecutable(
                OperatorCall("xml.document-root", 1),
                OperatorCall("xml.element-attributes", 1),
            ),
            document,
            QueryLimits.default,
            CancellationToken(),
        )
        assertEquals(1, matches.size)
        val attribute = matches[0] as consema.xml.XmlMatch.Attribute
        assertEquals("a", attribute.local)
        assertEquals("1", attribute.value)
    }

    @Test
    fun `native query traverses descendants in document order`() {
        // Case xml.native-query.descendants-order (xml-1-0-safe-v1.json).
        val document = parseUtf8("<root><a/><b/><c/></root>")
        val matches = executeXmlQuery(
            nativeExecutable(
                OperatorCall("xml.document-root", 1),
                OperatorCall("xml.element-descendants", 1),
            ),
            document,
            QueryLimits.default,
            CancellationToken(),
        )
        assertEquals(3, matches.size)
        assertEquals(
            listOf("a", "b", "c"),
            matches.map { (it as consema.xml.XmlMatch.Element).local },
        )
    }
}
