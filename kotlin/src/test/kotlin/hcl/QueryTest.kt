// Transcriptions of the conformance/vectors/hcl-v1.json hcl.query.* cases
// (:566-887): the native semantic query domain and the lossless syntax
// query domain.
//
// The definition validation and capability binding come from
// consema.protocol (QueryValidate.kt:377-425); this test executes the
// bound query against one immutable document, mirroring the conformance
// runner (https://github.com/consema/consema-rs/blob/main/consema-conformance/src/hcl_v1.rs:613-632).

package hcl

import consema.core.PvInteger
import consema.document.FormationStatus
import consema.hcl.HclCancellationToken
import consema.hcl.HclMatch
import consema.hcl.HclProfile
import consema.hcl.HclQueryException
import consema.hcl.HclQueryLimits
import consema.hcl.executeHclQuery
import consema.hcl.executeHclSyntaxQuery
import consema.hcl.parse
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryDomain
import consema.protocol.QueryExpression
import consema.protocol.QuerySelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QueryTest {

    private fun capabilities(): CapabilitySet =
        CapabilitySet().apply { insert(CapabilityId("core.query.ordered-results", 1)) }

    private fun nativeQuery(source: String, filters: List<OperatorCall>): List<HclMatch> {
        val document = parse(source.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val definition = QueryDefinition(QueryDomain("hcl.native-semantic-query", 1))
        var expression = QueryExpression(consema.protocol.ExpressionKind.Input)
        for (filter in filters) {
            expression = expression.then(filter)
        }
        definition.withExpression(expression).withSelection(QuerySelection.All)
        val executable = definition.validate().let { validated ->
            consema.protocol.ExecutableQuery.bind(validated, capabilities())
        }
        val execution = executeHclQuery(
            executable,
            document,
            HclQueryLimits.default,
            HclCancellationToken(),
        )
        return execution.matches()
    }

    private fun op(id: String, argument: String? = null): OperatorCall {
        val call = OperatorCall(id, 1)
        if (argument != null) {
            call.withArgument("argument", consema.core.PvString(argument))
        }
        return call
    }

    private fun typedOp(id: String, name: String, argument: String): OperatorCall {
        val call = OperatorCall(id, 1)
        call.withArgument(name, consema.core.PvString(argument))
        return call
    }

    /** Vector case hcl.query.native-body-walk (hcl-v1.json:566-608): the
     * document-body -> body-attributes -> name-equals -> expression ->
     * literal -> kind-is -> text chain yields one number match. */
    @Test
    fun nativeBodyWalk() {
        val matches = nativeQuery(
            Golden.QUERY_NATIVE,
            listOf(
                op("hcl.document-body"),
                op("hcl.body-attributes"),
                typedOp("hcl.attribute-name-equals", "name", "count"),
                op("hcl.attribute-expression"),
                op("hcl.expression-is-literal"),
                typedOp("hcl.expression-kind-is", "kind", "number"),
                op("hcl.expression-text"),
            ),
        )
        assertEquals(1, matches.size)
        val expression = assertIs<HclMatch.Expression>(matches[0])
        assertEquals("number", expression.handle.kindName())
        assertEquals("3", expression.handle.text())
        assertTrue(expression.handle.isLiteral())
    }

    /** Vector case hcl.query.blocks-and-labels (hcl-v1.json:610-688): label
     * matches with the quote fact, and a nested-body walk. */
    @Test
    fun blocksAndLabels() {
        // Sample 1: labels.
        val labelMatches = nativeQuery(
            Golden.QUERY_NATIVE,
            listOf(
                op("hcl.document-body"),
                op("hcl.body-blocks"),
                typedOp("hcl.block-type-equals", "type", "server"),
                op("hcl.block-labels"),
                typedOp("hcl.block-label-equals", "label", "web"),
            ),
        )
        assertEquals(1, labelMatches.size)
        val label = assertIs<HclMatch.BlockLabel>(labelMatches[0])
        assertEquals("web", label.handle.text())
        assertTrue(label.handle.quoted())

        // Sample 2: the nested body walk.
        val nestedMatches = nativeQuery(
            Golden.QUERY_NATIVE,
            listOf(
                op("hcl.document-body"),
                op("hcl.body-blocks"),
                typedOp("hcl.block-type-equals", "type", "server"),
                op("hcl.block-nested-body"),
                op("hcl.body-attributes"),
                typedOp("hcl.attribute-name-equals", "name", "port"),
                op("hcl.attribute-expression"),
                op("hcl.expression-text"),
            ),
        )
        assertEquals(1, nestedMatches.size)
        val port = assertIs<HclMatch.Expression>(nestedMatches[0])
        assertEquals("number", port.handle.kindName())
        assertEquals("8080", port.handle.text())
    }

    /** Vector case hcl.query.literal-accessors (hcl-v1.json:690-812): the
     * typed accessor family succeeds or fails with the frozen codes. */
    @Test
    fun literalAccessors() {
        val integer = nativeQuery(
            "count = 42\n",
            listOf(
                op("hcl.document-body"),
                op("hcl.body-attributes"),
                typedOp("hcl.attribute-name-equals", "name", "count"),
                op("hcl.attribute-expression"),
                typedOp("hcl.attribute-literal-value", "accessor", "as-integer"),
            ),
        )
        assertEquals(1, integer.size)
        val value = assertIs<HclMatch.LiteralValue>(integer[0])
        assertEquals(PvInteger(java.math.BigInteger("42")), value.value)

        // `name = "x"` with as-integer: type mismatch -> hcl.query.type-mismatch@1.
        val mismatch = kotlin.test.assertFailsWith<HclQueryException> {
            nativeQuery(
                "name = \"x\"\n",
                listOf(
                    op("hcl.document-body"),
                    op("hcl.body-attributes"),
                    typedOp("hcl.attribute-name-equals", "name", "name"),
                    op("hcl.attribute-expression"),
                    typedOp("hcl.attribute-literal-value", "accessor", "as-integer"),
                ),
            )
        }
        assertEquals("hcl.query.type-mismatch@1", mismatch.code)

        // `name = var.name` with as-string: non-literal -> hcl.query.non-literal@1.
        val nonLiteral = kotlin.test.assertFailsWith<HclQueryException> {
            nativeQuery(
                "name = var.name\n",
                listOf(
                    op("hcl.document-body"),
                    op("hcl.body-attributes"),
                    typedOp("hcl.attribute-name-equals", "name", "name"),
                    op("hcl.attribute-expression"),
                    typedOp("hcl.attribute-literal-value", "accessor", "as-string"),
                ),
            )
        }
        assertEquals("hcl.query.non-literal@1", nonLiteral.code)

        // `enabled = true` with as-boolean-is.
        val boolean = nativeQuery(
            "enabled = true\n",
            listOf(
                op("hcl.document-body"),
                op("hcl.body-attributes"),
                typedOp("hcl.attribute-name-equals", "name", "enabled"),
                op("hcl.attribute-expression"),
                typedOp("hcl.attribute-literal-value", "accessor", "as-boolean-is"),
            ),
        )
        val booleanValue = assertIs<HclMatch.LiteralValue>(boolean[0])
        assertEquals(consema.core.PvBoolean(true), booleanValue.value)
    }

    /** Vector case hcl.query.lossless-kind-filter (hcl-v1.json:814-861):
     * exact kind and ordinal facts of the lossless syntax pieces. */
    @Test
    fun losslessKindFilter() {
        val document = parse(
            "# c\nregion = \"us-east-1\"\n".toByteArray(Charsets.UTF_8),
            HclProfile.NATIVE_V1,
        )
        fun syntaxQuery(kind: String): List<consema.hcl.HclSyntaxMatch> {
            val definition = QueryDefinition(QueryDomain("hcl.lossless-syntax-query", 1))
            val filter = OperatorCall("hcl.syntax-kind-is", 1)
            filter.withArgument("kind", consema.core.PvString(kind))
            definition.withExpression(
                QueryExpression(consema.protocol.ExpressionKind.Input).then(filter),
            )
            val executable = definition.validate().let { validated ->
                consema.protocol.ExecutableQuery.bind(validated, capabilities())
            }
            return executeHclSyntaxQuery(
                executable,
                document,
                HclQueryLimits.default,
                HclCancellationToken(),
            ).matches()
        }

        fun text(match: consema.hcl.HclSyntaxMatch): String =
            document.source().decodedText()!!
                .substring(match.span.startByte, match.span.endByte)

        val comments = syntaxQuery("LineComment")
        assertEquals(1, comments.size)
        assertEquals("LineComment", comments[0].kind.asStr())
        assertEquals("# c", text(comments[0]))
        assertEquals(0, comments[0].ordinal)

        val strings = syntaxQuery("StringContent")
        assertEquals(1, strings.size)
        assertEquals("StringContent", strings[0].kind.asStr())
        assertEquals("us-east-1", text(strings[0]))
        assertEquals(7, strings[0].ordinal)
    }

    /** Vector case hcl.query.error-regions (hcl-v1.json:863-887): the
     * ordered error regions of a Recovered document, one match per
     * `hcl.parse.*@1`-coded region in source order. */
    @Test
    fun errorRegions() {
        val document = parse("a = 1\nb {\n".toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)
        assertEquals(FormationStatus.Recovered, document.formationStatus())
        val definition = QueryDefinition(QueryDomain("hcl.native-semantic-query", 1))
        definition.withExpression(
            QueryExpression(consema.protocol.ExpressionKind.Input)
                .then(OperatorCall("hcl.document-body", 1))
                .then(OperatorCall("hcl.error-regions", 1)),
        )
        val executable = definition.validate().let { validated ->
            consema.protocol.ExecutableQuery.bind(validated, capabilities())
        }
        val matches = executeHclQuery(
            executable,
            document,
            HclQueryLimits.default,
            HclCancellationToken(),
        ).matches()
        assertEquals(1, matches.size)
        val region = assertIs<HclMatch.ErrorRegion>(matches[0])
        assertEquals("hcl.parse.block@1", region.region.code)
        assertEquals(0, region.position)
    }
}
