// Golden transcriptions of the syntax-query and native-query vector cases.
//
// Data authority: conformance/vectors/syntax-query-v1.json (syntax.toml.*
// cases, lines 54-99), conformance/vectors/toml-v1.json (toml.query.
// nested-entry-order, toml.query.aot-element-order), RFC 0001 §4, and the
// Rust crate query tests (consema-toml/src/query.rs:490-652). The L5
// conformance runner executes the shared vectors directly; these tests are
// the L1 intent documents.

package toml

import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.Domains
import consema.protocol.ExecutableQuery
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind
import consema.protocol.QuerySelection
import consema.core.PvInteger
import consema.core.PvString
import consema.document.ParseLimits
import consema.toml.TomlCancellationToken
import consema.toml.TomlItemKind
import consema.toml.TomlMatch
import consema.toml.TomlProfile
import consema.toml.TomlQueryLimits
import consema.toml.TomlSyntaxKind
import consema.toml.executeTomlQuery
import consema.toml.executeTomlSyntaxQuery
import consema.toml.parse
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TomlQueryTest {

    private fun capabilities(): CapabilitySet =
        CapabilitySet().apply { insert(CapabilityId("core.query.ordered-results", 1)) }

    private fun document(source: String) =
        parse(source.toByteArray(), TomlProfile.TOML_1_0_V1, ParseLimits.default)

    private fun nativeExecutable(expression: QueryExpression): ExecutableQuery =
        QueryDefinition(Domains.tomlNativeV1())
            .withExpression(expression)
            .validate()
            .let { ExecutableQuery.bind(it, capabilities()) }

    private fun syntaxExecutable(expression: QueryExpression, selection: QuerySelection = QuerySelection.All): ExecutableQuery =
        QueryDefinition(Domains.tomlLosslessSyntaxV1())
            .withExpression(expression)
            .withSelection(selection)
            .validate()
            .let { ExecutableQuery.bind(it, capabilities()) }

    private fun nativeMatches(expression: QueryExpression, source: String) =
        executeTomlQuery(
            nativeExecutable(expression),
            document(source),
            TomlQueryLimits.default,
            TomlCancellationToken(),
        ).matches()

    /** syntax-query-v1.json syntax.toml.kind-text-order: StructureOrderMerge
     * preserves source order across kind and text filters with the pinned
     * ordinals. */
    @Test
    fun syntaxKindTextOrder() {
        val newlines = QueryExpression.Input.then(
            OperatorCall("toml.syntax-kind-is", 1).withArgument("kind", PvString("Newline")),
        )
        val comment = QueryExpression.Input.then(
            OperatorCall("toml.syntax-text-equals", 1).withArgument("text", PvString("# note")),
        )
        val result = executeTomlSyntaxQuery(
            syntaxExecutable(QueryExpression(consema.protocol.ExpressionKind.StructureOrderMerge, branches = listOf(newlines, comment))),
            document("a = 1 # note\nb = 2\n"),
            TomlQueryLimits.default,
            TomlCancellationToken(),
        )
        assertEquals(
            listOf(
                TomlSyntaxKind.Comment to "# note",
                TomlSyntaxKind.Newline to "\n",
                TomlSyntaxKind.Newline to "\n",
            ),
            result.matches().map { it.kind to it.text() },
        )
        assertEquals(listOf(6, 7, 13), result.matches().map { it.ordinal })
    }

    /** syntax-query-v1.json syntax.toml.kind-bare: the Bare filter matches
     * keys and value fragments with the pinned ordinals. */
    @Test
    fun syntaxKindBare() {
        val expression = QueryExpression.Input.then(
            OperatorCall("toml.syntax-kind-is", 1).withArgument("kind", PvString("Bare")),
        )
        val result = executeTomlSyntaxQuery(
            syntaxExecutable(expression),
            document("a = 1 # note\nb = 2\n"),
            TomlQueryLimits.default,
            TomlCancellationToken(),
        )
        assertEquals(listOf(0, 4, 8, 12), result.matches().map { it.ordinal })
        assertEquals(listOf("a", "1", "b", "2"), result.matches().map { it.text() })
    }

    /** syntax-query-v1.json syntax.toml.text-equals: text equality matches
     * the exact piece bytes. */
    @Test
    fun syntaxTextEquals() {
        val expression = QueryExpression.Input.then(
            OperatorCall("toml.syntax-text-equals", 1).withArgument("text", PvString("=")),
        )
        val result = executeTomlSyntaxQuery(
            syntaxExecutable(expression),
            document("a=1\nb=2\n"),
            TomlQueryLimits.default,
            TomlCancellationToken(),
        )
        assertEquals(listOf(1, 5), result.matches().map { it.ordinal })
        assertTrue(result.matches().all { it.kind == TomlSyntaxKind.Equals })
    }

    /** syntax-query-v1.json syntax.toml.selection-first / selection-last:
     * the selection applies to the complete result. */
    @Test
    fun syntaxSelectionFirstAndLast() {
        val newlines = QueryExpression.Input.then(
            OperatorCall("toml.syntax-kind-is", 1).withArgument("kind", PvString("Newline")),
        )
        val first = executeTomlSyntaxQuery(
            syntaxExecutable(newlines, QuerySelection.First),
            document("a=1\nb=2\n"),
            TomlQueryLimits.default,
            TomlCancellationToken(),
        )
        assertEquals(listOf(3), first.matches().map { it.ordinal })
        val last = executeTomlSyntaxQuery(
            syntaxExecutable(newlines, QuerySelection.Last),
            document("a=1\nb=2\n"),
            TomlQueryLimits.default,
            TomlCancellationToken(),
        )
        assertEquals(listOf(7), last.matches().map { it.ordinal })
    }

    /** syntax-query-v1.json syntax.toml.result-limit: a zero result limit
     * fails with core.query.resource-limit@1. */
    @Test
    fun syntaxResultLimit() {
        val expression = QueryExpression.Input.then(
            OperatorCall("core.take", 1).withArgument("count", PvInteger(BigInteger.valueOf(1))),
        )
        val failure = assertFailsWith<QueryFailureException> {
            executeTomlSyntaxQuery(
                syntaxExecutable(expression),
                document("a=1\n"),
                TomlQueryLimits(maxSteps = 100_000, maxResults = 0),
                TomlCancellationToken(),
            )
        }
        assertEquals(QueryFailureKind.RESOURCE_LIMIT, failure.kind)
        assertEquals("core.query.resource-limit@1", failure.kind.code)
    }

    /** syntax-query-v1.json syntax.toml.cancelled: cancellation fails with
     * core.query.cancelled@1. */
    @Test
    fun syntaxCancelled() {
        val cancelled = TomlCancellationToken()
        cancelled.cancel()
        val failure = assertFailsWith<QueryFailureException> {
            executeTomlSyntaxQuery(
                syntaxExecutable(QueryExpression.Input),
                document("a=1\n"),
                TomlQueryLimits.default,
                cancelled,
            )
        }
        assertEquals(QueryFailureKind.CANCELLED, failure.kind)
        assertEquals("core.query.cancelled@1", failure.kind.code)
    }

    /** syntax-query-v1.json syntax.toml.reject-invalid-kind: an unknown
     * kind spelling is a validation failure (the protocol validator) before
     * execution. */
    @Test
    fun syntaxRejectInvalidKind() {
        val failure = assertFailsWith<QueryFailureException> {
            QueryDefinition(Domains.tomlLosslessSyntaxV1())
                .withExpression(
                    QueryExpression.Input.then(
                        OperatorCall("toml.syntax-kind-is", 1).withArgument("kind", PvString("newline")),
                    ),
                )
                .validate()
        }
        assertEquals(QueryFailureKind.INVALID_ARGUMENT, failure.kind)
        assertEquals("core.query.invalid-argument@1", failure.kind.code)
    }

    /** toml-v1.json toml.query.nested-entry-order: navigating into a dotted
     * table yields its entries in structural order. */
    @Test
    fun nestedEntryOrder() {
        val expression = QueryExpression.Input
            .then(OperatorCall("toml.try-table-entries", 1))
            .then(OperatorCall("toml.entry-name-equals", 1).withArgument("name", PvString("service")))
            .then(OperatorCall("toml.entry-item", 1))
            .then(OperatorCall("toml.try-table-entries", 1))
        val matches = nativeMatches(expression, String(APPLICATION_TOML, Charsets.UTF_8))
        assertEquals(listOf("name", "environment", "listen"), matches.map { (it as TomlMatch.Entry).name })
    }

    /** toml-v1.json toml.query.aot-element-order: array-of-tables elements
     * keep their ordinals in source order. */
    @Test
    fun aotElementOrder() {
        val expression = QueryExpression.Input
            .then(OperatorCall("toml.try-table-entries", 1))
            .then(OperatorCall("toml.entry-name-equals", 1).withArgument("name", PvString("upstreams")))
            .then(OperatorCall("toml.entry-item", 1))
            .then(OperatorCall("toml.try-array-elements", 1))
        val matches = nativeMatches(expression, String(APPLICATION_TOML, Charsets.UTF_8))
        assertEquals(listOf(0, 1), matches.map { (it as TomlMatch.ArrayElement).ordinal })
    }

    /** query.rs:516-547: a nested entry query retains the direct TOML
     * roles. */
    @Test
    fun nestedEntryQueryRetainsDirectTomlRoles() {
        val expression = QueryExpression.Input
            .then(OperatorCall("toml.try-table-entries", 1))
            .then(OperatorCall("toml.entry-name-equals", 1).withArgument("name", PvString("server")))
            .then(OperatorCall("toml.entry-item", 1))
            .then(OperatorCall("toml.try-table-entries", 1))
        val matches = nativeMatches(expression, "server.host = 'localhost'\nserver.ports = [80, 443]\n")
        assertEquals(listOf("host", "ports"), matches.map { (it as TomlMatch.Entry).name })
    }

    /** query.rs:550-596: selection and cancellation apply to native
     * queries. */
    @Test
    fun arrayQueryObeysSelectionAndCancellation() {
        val expression = QueryExpression.Input
            .then(OperatorCall("toml.try-table-entries", 1))
            .then(OperatorCall("toml.entry-item", 1))
            .then(OperatorCall("toml.try-array-elements", 1))
            .then(OperatorCall("toml.array-element-item", 1))
        val definition = QueryDefinition(Domains.tomlNativeV1())
            .withExpression(expression)
            .withSelection(QuerySelection.Last)
        val executable = ExecutableQuery.bind(definition.validate(), capabilities())
        val result = executeTomlQuery(
            executable,
            document("values = [1, 2, 3]"),
            TomlQueryLimits.default,
            TomlCancellationToken(),
        )
        assertEquals(1, result.matches().size)
        assertEquals(TomlItemKind.Integer, (result.matches()[0] as TomlMatch.Item).kind)

        val cancelled = TomlCancellationToken()
        cancelled.cancel()
        val failure = assertFailsWith<QueryFailureException> {
            executeTomlQuery(
                executable,
                document("values = [1, 2, 3]"),
                TomlQueryLimits.default,
                cancelled,
            )
        }
        assertEquals(QueryFailureKind.CANCELLED, failure.kind)
    }

    /** query.rs:598-652: the syntax match role is TomlSyntaxPiece and the
     * source text is byte-exact. */
    @Test
    fun syntaxMatchCarriesPieceRoleAndText() {
        val expression = QueryExpression.Input.then(
            OperatorCall("toml.syntax-text-equals", 1).withArgument("text", PvString("# note")),
        )
        val result = executeTomlSyntaxQuery(
            syntaxExecutable(expression),
            document("a = 1 # note\nb = 2\n"),
            TomlQueryLimits.default,
            TomlCancellationToken(),
        )
        assertEquals(1, result.matches().size)
        assertEquals(consema.document.NodeRole.TomlSyntaxPiece, result.matches()[0].nodeRef.role)
        assertEquals("# note", result.matches()[0].text())
    }

    /** query.rs:88-113: a mismatched domain is refused before execution. */
    @Test
    fun domainMismatchIsRefused() {
        val foreign = ExecutableQuery.bind(
            QueryDefinition(Domains.jsonNativeV1())
                .withExpression(QueryExpression.Input)
                .validate(),
            capabilities(),
        )
        val failure = assertFailsWith<QueryFailureException> {
            executeTomlQuery(
                foreign,
                document("a = 1\n"),
                TomlQueryLimits.default,
                TomlCancellationToken(),
            )
        }
        assertEquals(QueryFailureKind.DOMAIN_MISMATCH, failure.kind)
        assertEquals("core.query.domain-mismatch@1", failure.kind.code)
    }

    private fun TomlSyntaxMatch.text(): String {
        val bytes = document("a = 1 # note\nb = 2\n").source().bytes()
        return String(bytes.copyOfRange(span.startByte, span.endByte), Charsets.UTF_8)
    }
}
