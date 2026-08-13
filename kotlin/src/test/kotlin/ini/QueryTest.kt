// INI native-semantic and lossless-syntax query execution tests.
//
// Authority: RFC 0009 §9 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md:286-345)
// and the vector cases query.native-order-and-profile-equivalence,
// query.syntax-decoded-structure-order, and
// query.validation-limit-cancellation (ini-v1.json:44-58);
// consema-rs/consema-ini/src/query.rs is the byte-arbitration authority.

package ini

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
import consema.ini.CancellationToken
import consema.ini.IniMatch
import consema.ini.IniProfile
import consema.ini.IniSyntaxKind
import consema.ini.QueryLimits
import consema.protocol.ExecutableQuery
import consema.ini.executeIniQuery
import consema.ini.executeIniSyntaxQuery
import consema.ini.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QueryTest {

    private fun capabilities(): CapabilitySet =
        CapabilitySet().apply {
            insert(CapabilityId("core.query.ordered-results", 1))
        }

    private fun executable(expression: QueryExpression) =
        ExecutableQuery.bind(
            QueryDefinition(Domains.iniNativeV1())
                .withExpression(expression)
                .validate(),
            capabilities(),
        )

    /** Vector case query.native-order-and-profile-equivalence (ini-v1.json:
     * 44-47): sections filtered by the ProfileEquivalent comparison keep
     * source order, the case-equivalent entries share a duplicate group,
     * and the terminal state is Completed. */
    @Test
    fun nativeOrderAndProfileEquivalence() {
        val document = parse(
            "[Main]\r\nName=one\r\nname=two\r\n[Other]\r\nempty=\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )
        val query = executable(
            QueryExpression(ExpressionKind.Input)
                .then(OperatorCall("ini.document-sections", 1))
                .then(
                    OperatorCall("ini.section-name-equals", 1)
                        .withArgument("name", PvString("MAIN"))
                        .withArgument("comparison", PvString("ProfileEquivalent")),
                )
                .then(OperatorCall("ini.section-entries", 1)),
        )
        val matches = executeIniQuery(query, document)
        assertEquals(listOf("Name", "name"), matches.map { (it as IniMatch.Entry).key })
        assertTrue(
            matches.all {
                (it as IniMatch.Entry).duplicateGroup != null
            },
        )
    }

    /** RFC 0009 §9: duplicate-group expands an input occurrence to every
     * same-role occurrence carrying the same group identity, in source
     * order. */
    @Test
    fun duplicateGroupExpandsToAllMembers() {
        val document = parse(
            "[Main]\r\nName=one\r\nname=two\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )
        val query = executable(
            QueryExpression(ExpressionKind.Input)
                .then(OperatorCall("ini.all-entries", 1))
                .then(
                    OperatorCall("ini.entry-key-equals", 1)
                        .withArgument("key", PvString("Name"))
                        .withArgument("comparison", PvString("OriginalExact")),
                )
                .then(OperatorCall("ini.duplicate-group", 1)),
        )
        val matches = executeIniQuery(query, document)
        assertEquals(listOf("Name", "name"), matches.map { (it as IniMatch.Entry).key })
    }

    /** RFC 0009 §9: entry-value-state-is and entry-section resolve
     * ownership back to the owning section. */
    @Test
    fun entryStateAndSectionOwnership() {
        val document = parse(
            "[Main]\r\nName=one\r\n[Other]\r\nempty=\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )
        val query = executable(
            QueryExpression(ExpressionKind.Input)
                .then(OperatorCall("ini.all-entries", 1))
                .then(
                    OperatorCall("ini.entry-value-state-is", 1)
                        .withArgument("state", PvString("Empty")),
                )
                .then(OperatorCall("ini.entry-section", 1)),
        )
        val matches = executeIniQuery(query, document)
        assertEquals(listOf("Other"), matches.map { (it as IniMatch.Section).name })
    }

    /** RFC 0009 §9: the physical-lines and logical-lines operators expose
     * the complete ordered line facts. */
    @Test
    fun physicalAndLogicalLines() {
        val document = parse(
            "[s]\na=1\n".toByteArray(Charsets.UTF_8),
            IniProfile.PortableV1,
        )
        val physical = executeIniQuery(
            executable(
                QueryExpression(ExpressionKind.Input)
                    .then(OperatorCall("ini.physical-lines", 1)),
            ),
            document,
        )
        assertEquals(2, physical.size)
        assertEquals(0, (physical[0] as IniMatch.PhysicalLine).ordinal)

        val logical = executeIniQuery(
            executable(
                QueryExpression(ExpressionKind.Input)
                    .then(OperatorCall("ini.logical-lines", 1)),
            ),
            document,
        )
        assertEquals(2, logical.size)
        assertEquals(
            listOf(consema.ini.IniLogicalLineKind.Section, consema.ini.IniLogicalLineKind.Entry),
            logical.map { (it as IniMatch.LogicalLine).kind },
        )
    }

    /** Vector case query.syntax-decoded-structure-order (ini-v1.json:49-52):
     * the syntax query matches the decoded text of the exact piece span
     * (identical for UTF-16LE and UTF-8 sources) and merges branches by
     * strictly increasing source ordinals. */
    @Test
    fun syntaxDecodedStructureOrder() {
        val utf8Document = parse(
            "[S]\r\nName=\" value \"\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )
        val text = "[S]\r\nName=\" value \"\r\n"
        val utf16 = ByteArray(2 + text.toByteArray(Charsets.UTF_16LE).size)
        utf16[0] = 0xff.toByte()
        utf16[1] = 0xfe.toByte()
        System.arraycopy(text.toByteArray(Charsets.UTF_16LE), 0, utf16, 2, utf16.size - 2)
        val utf16Document = parse(utf16, IniProfile.WindowsV1)

        val quote = QueryExpression(ExpressionKind.Input).then(
            OperatorCall("ini.syntax-kind-is", 1)
                .withArgument("kind", PvString("Quote")),
        )
        val name = QueryExpression(ExpressionKind.Input).then(
            OperatorCall("ini.syntax-text-equals", 1)
                .withArgument("text", PvString("Name")),
        )
        val query = ExecutableQuery.bind(
            QueryDefinition(Domains.iniLosslessSyntaxV1())
                .withExpression(QueryExpression(ExpressionKind.StructureOrderMerge, branches = listOf(quote, name)))
                .validate(),
            capabilities(),
        )

        val kinds = executeIniSyntaxQuery(query, utf8Document).map { it.kind }
        assertEquals(listOf(IniSyntaxKind.EntryKey, IniSyntaxKind.Quote, IniSyntaxKind.Quote), kinds)
        val ordinals = executeIniSyntaxQuery(query, utf8Document).map { it.ordinal }
        assertTrue(ordinals.zipWithNext().all { (left, right) -> left < right })

        // The decoded-text comparison is encoding-independent.
        val utf16Kinds = executeIniSyntaxQuery(query, utf16Document).map { it.kind }
        assertEquals(kinds, utf16Kinds)
    }

    /** Vector case query.validation-limit-cancellation (ini-v1.json:53-57):
     * exceeding max_results fails with core.query.resource-limit@1; a
     * cancelled token fails with core.query.cancelled@1. */
    @Test
    fun limitsAndCancellation() {
        val document = parse(
            "[s]\na=1\nb=2\n".toByteArray(Charsets.UTF_8),
            IniProfile.PortableV1,
        )
        val query = executable(
            QueryExpression(ExpressionKind.Input)
                .then(OperatorCall("ini.all-entries", 1)),
        )
        val limitFailure = assertFailsWith<QueryFailureException> {
            executeIniQuery(query, document, QueryLimits(maxSteps = 100_000, maxResults = 1))
        }
        assertEquals(QueryFailureKind.RESOURCE_LIMIT, limitFailure.kind)

        val cancelled = CancellationToken()
        cancelled.cancel()
        val cancellationFailure = assertFailsWith<QueryFailureException> {
            executeIniQuery(query, document, cancellation = cancelled)
        }
        assertEquals(QueryFailureKind.CANCELLED, cancellationFailure.kind)
    }

    /** RFC 0009 §9: the domain binding rejects a query executed under the
     * wrong domain. */
    @Test
    fun wrongDomainIsRejected() {
        val document = parse("[s]\nk=1\n".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)
        val query = ExecutableQuery.bind(
            QueryDefinition(Domains.iniLosslessSyntaxV1())
                .withExpression(
                    QueryExpression(ExpressionKind.Input)
                        .then(
                            OperatorCall("ini.syntax-kind-is", 1)
                                .withArgument("kind", PvString("Quote")),
                        ),
                )
                .validate(),
            capabilities(),
        )
        val failure = assertFailsWith<QueryFailureException> {
            executeIniQuery(query, document)
        }
        assertEquals(QueryFailureKind.DOMAIN_MISMATCH, failure.kind)
    }
}
