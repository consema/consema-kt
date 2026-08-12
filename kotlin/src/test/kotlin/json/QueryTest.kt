// Versioned JSON query execution tests.
//
// Vector case json5.query.syntax-v2-identifier (json-family-v2.json:114-118)
// pins the Identifier matches and the v1 domain rejection for JSON5; vector
// case json5.query.native-v2-binary (json-family-v2.json:120-124) pins the
// BinaryFloat64 native root under domain v2; the syntax-query-v1.json json
// cases (lines 5-52) pin kind/text matching, ordinals, selection, and the
// limit/cancellation codes (RFC 0005 §7, RFC 0003 §8).

package json

import consema.core.PvString
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.Domains
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind
import consema.json.CancellationToken
import consema.json.JsonProfile
import consema.json.JsonSyntaxKind
import consema.json.QueryLimits
import consema.json.executeJsonQuery
import consema.json.executeJsonSyntaxQuery
import consema.json.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QueryTest {

    private fun capabilities(): CapabilitySet =
        CapabilitySet().apply {
            insert(CapabilityId("core.query.ordered-results", 1))
        }

    private fun textOf(document: consema.json.Document, match: consema.json.JsonSyntaxMatch): String =
        document.render()
            .copyOfRange(match.span.startByte, match.span.endByte)
            .toString(Charsets.UTF_8)

    /** Vector case json5.query.syntax-v2-identifier (json-family-v2.json:
     * 114-118): under the v2 lossless domain, both unquoted keys are
     * Identifier pieces in source order; the v1 domain is rejected for a
     * JSON5 document (RFC 0005 §7). */
    @Test
    fun syntaxV2MatchesIdentifiersAndRejectsV1() {
        val document = parse("{key:1,true:2}".toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        val query = QueryDefinition(Domains.jsonLosslessSyntaxV2())
            .withExpression(
                QueryExpression(ExpressionKind.Input).then(
                    OperatorCall("json.syntax-kind-is", 1)
                        .withArgument("kind", PvString("Identifier")),
                ),
            )
            .validate()
            .bind(capabilities())

        val matches = executeJsonSyntaxQuery(query, document)
        assertEquals(listOf("key", "true"), matches.map { textOf(document, it) })
        assertEquals(listOf(JsonSyntaxKind.Identifier, JsonSyntaxKind.Identifier), matches.map { it.kind })
        assertTrue(matches[0].ordinal < matches[1].ordinal)

        val v1 = QueryDefinition(Domains.jsonLosslessSyntaxV1())
            .validate()
            .bind(capabilities())
        val error = assertFailsWith<QueryFailureException> {
            executeJsonSyntaxQuery(v1, document)
        }
        assertEquals(QueryFailureKind.DOMAIN_MISMATCH, error.kind)
    }

    /** Vector case json5.query.native-v2-binary (json-family-v2.json:120-124):
     * the v2 native domain exposes the BinaryFloat64 root; the v1 domain is
     * rejected. */
    @Test
    fun nativeV2ExposesBinaryFloat64AndRejectsV1() {
        val document = parse("-Infinity".toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        val v2 = QueryDefinition(Domains.jsonNativeV2())
            .validate()
            .bind(capabilities())
        val matches = executeJsonQuery(v2, document)
        assertEquals(1, matches.size)
        val value = matches.single() as consema.json.JsonMatch.Value
        assertEquals("BinaryFloat64", value.kind!!.name)

        val v1 = QueryDefinition(Domains.jsonNativeV1())
            .validate()
            .bind(capabilities())
        val error = assertFailsWith<QueryFailureException> {
            executeJsonQuery(v1, document)
        }
        assertEquals(QueryFailureKind.DOMAIN_MISMATCH, error.kind)
    }

    /** Vector case syntax.json.kind-string (syntax-query-v1.json:11-16): the
     * kind filter matches complete string tokens including the quotes, with
     * the exact source ordinals. */
    @Test
    fun syntaxKindStringMatchesWithOrdinals() {
        val document = parse(
            "{\"a\":\"b\"}".toByteArray(Charsets.UTF_8),
            JsonProfile.StrictV1,
        )
        val query = QueryDefinition(Domains.jsonLosslessSyntaxV1())
            .withExpression(
                QueryExpression(ExpressionKind.Input).then(
                    OperatorCall("json.syntax-kind-is", 1)
                        .withArgument("kind", PvString("String")),
                ),
            )
            .validate()
            .bind(capabilities())
        val matches = executeJsonSyntaxQuery(query, document)
        assertEquals(listOf("\"a\"", "\"b\""), matches.map { textOf(document, it) })
        assertEquals(listOf(1, 3), matches.map { it.ordinal })
        assertEquals(
            consema.document.NodeRole.JsonSyntaxPiece,
            matches[0].node.role,
        )
    }

    /** Vector case syntax.json.text-equals (syntax-query-v1.json:18-22): the
     * text filter compares the exact raw source bytes. */
    @Test
    fun syntaxTextEqualsMatchesExactBytes() {
        val document = parse("[1,2,1]".toByteArray(Charsets.UTF_8), JsonProfile.StrictV1)
        val query = QueryDefinition(Domains.jsonLosslessSyntaxV1())
            .withExpression(
                QueryExpression(ExpressionKind.Input).then(
                    OperatorCall("json.syntax-text-equals", 1)
                        .withArgument("text", PvString("1")),
                ),
            )
            .validate()
            .bind(capabilities())
        val matches = executeJsonSyntaxQuery(query, document)
        assertEquals(listOf(1, 5), matches.map { it.ordinal })
        assertEquals(
            listOf(JsonSyntaxKind.Number, JsonSyntaxKind.Number),
            matches.map { it.kind },
        )
    }

    /** Vector case syntax.json.selection-last (syntax-query-v1.json:30-34):
     * the Last selection returns only the final match. */
    @Test
    fun syntaxSelectionLast() {
        val document = parse("[1,2,1]".toByteArray(Charsets.UTF_8), JsonProfile.StrictV1)
        val query = QueryDefinition(Domains.jsonLosslessSyntaxV1())
            .withExpression(
                QueryExpression(ExpressionKind.Input).then(
                    OperatorCall("json.syntax-kind-is", 1)
                        .withArgument("kind", PvString("Number")),
                ),
            )
            .withSelection(consema.protocol.QuerySelection.Last)
            .validate()
            .bind(capabilities())
        val matches = executeJsonSyntaxQuery(query, document)
        assertEquals(listOf(5), matches.map { it.ordinal })
    }

    /** Vector case syntax.json.result-limit (syntax-query-v1.json:36-40): a
     * zero result limit fails with core.query.resource-limit@1. */
    @Test
    fun syntaxResultLimitFails() {
        val document = parse("[1]".toByteArray(Charsets.UTF_8), JsonProfile.StrictV1)
        val query = QueryDefinition(Domains.jsonLosslessSyntaxV1())
            .validate()
            .bind(capabilities())
        val error = assertFailsWith<QueryFailureException> {
            executeJsonSyntaxQuery(query, document, QueryLimits(maxSteps = 100_000, maxResults = 0))
        }
        assertEquals(QueryFailureKind.RESOURCE_LIMIT, error.kind)
    }

    /** Vector case syntax.json.cancelled (syntax-query-v1.json:42-46):
     * cancellation fails with core.query.cancelled@1. */
    @Test
    fun syntaxCancelledFails() {
        val document = parse("[1]".toByteArray(Charsets.UTF_8), JsonProfile.StrictV1)
        val query = QueryDefinition(Domains.jsonLosslessSyntaxV1())
            .validate()
            .bind(capabilities())
        val cancellation = CancellationToken()
        cancellation.cancel()
        val error = assertFailsWith<QueryFailureException> {
            executeJsonSyntaxQuery(query, document, cancellation = cancellation)
        }
        assertEquals(QueryFailureKind.CANCELLED, error.kind)
    }

    /** Vector case syntax.json.reject-invalid-kind (syntax-query-v1.json:
     * 48-52): an unregistered kind name is an invalid-argument failure, not
     * an empty result. */
    @Test
    fun syntaxRejectsInvalidKindName() {
        val document = parse("[1]".toByteArray(Charsets.UTF_8), JsonProfile.StrictV1)
        val error = assertFailsWith<QueryFailureException> {
            QueryDefinition(Domains.jsonLosslessSyntaxV1())
                .withExpression(
                    QueryExpression(ExpressionKind.Input).then(
                        OperatorCall("json.syntax-kind-is", 1)
                            .withArgument("kind", PvString("number")),
                    ),
                )
                .validate()
        }
        assertEquals(QueryFailureKind.INVALID_ARGUMENT, error.kind)
    }

    /** Vector case query.json-duplicate-order (v1.json:77-81): duplicate
     * members keep source order and distinct identity under the native
     * domain. */
    @Test
    fun duplicateMembersKeepSourceOrderAndIdentity() {
        val document = parse(
            "{\"a\":1,\"a\":2,\"b\":3}".toByteArray(Charsets.UTF_8),
            JsonProfile.StrictV1,
        )
        val query = QueryDefinition(Domains.jsonNativeV1())
            .withExpression(
                QueryExpression(ExpressionKind.Input)
                    .then(OperatorCall("json.try-object-members", 1))
                    .then(
                        OperatorCall("json.member-name-equals", 1)
                            .withArgument("name", PvString("a")),
                    ),
            )
            .validate()
            .bind(capabilities())
        val matches = executeJsonQuery(query, document)
        assertEquals(2, matches.size)
        val members = matches.map { it as consema.json.JsonMatch.ObjectMember }
        assertEquals(listOf(0, 1), members.map { it.ordinal })
        assertTrue(members[0].member != members[1].member)
    }
}

/** Local chaining convenience for the ExecutableQuery companion bind
 * (the family tests bind validated definitions to capabilities). */
private fun consema.protocol.ValidatedQuery.bind(
    capabilities: CapabilitySet,
): consema.protocol.ExecutableQuery =
    consema.protocol.ExecutableQuery.bind(this, capabilities)
