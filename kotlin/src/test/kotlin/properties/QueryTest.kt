// Native and lossless syntax query transcriptions from
// conformance/vectors/java-properties-v1.json.
//
// Key matching takes exact UTF-16 code units encoded as UTF16BE/1 and never
// normalizes (RFC 0010 §10). Case ids are cited on every test; these tests
// pin the intent and run at the L2 verification gate.
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package properties

import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvString
import consema.document.SourceEncoding
import consema.properties.CancellationToken
import consema.properties.PropertiesMatch
import consema.properties.PropertiesSyntaxKind
import consema.properties.QueryLimits
import consema.properties.executePropertiesQuery
import consema.properties.executePropertiesSyntaxQuery
import consema.properties.parseReader
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.Domains
import consema.protocol.ExecutableQuery
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QueryTest {

    /** Vector case query.native-duplicates-and-escape-ownership
     * (java-properties-v1.json:60-64): property-key-equals matches exact
     * UTF16BE/1 keys including duplicates; take + duplicate-group expands
     * the whole group; property-escapes owns both key and value escapes. */
    @Test
    fun nativeDuplicatesAndEscapeOwnership() {
        val document = parseReader(
            "a\\ key=one\\u0021\na\\ key=two\nempty\n".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val keyBytes = hexBytes("00610020006b00650079")

        val duplicates = executePropertiesQuery(
            executable(
                QueryExpression(consema.protocol.ExpressionKind.Input)
                    .then(OperatorCall("properties.document-properties", 1))
                    .then(
                        OperatorCall("properties.property-key-equals", 1)
                            .withArgument("key", PvBytes.of(keyBytes)),
                    )
                    .then(
                        OperatorCall("core.take", 1)
                            .withArgument("count", PvInteger(BigInteger.ONE)),
                    )
                    .then(OperatorCall("properties.duplicate-group", 1)),
            ),
            document,
        )
        assertEquals(2, duplicates.size)
        assertTrue(duplicates.all { it is PropertiesMatch.Property })
        assertEquals(
            listOf("a key", "a key"),
            duplicates.map { (it as PropertiesMatch.Property).key.toUnicode() },
        )

        val escapes = executePropertiesQuery(
            executable(
                QueryExpression(consema.protocol.ExpressionKind.Input)
                    .then(OperatorCall("properties.document-properties", 1))
                    .then(
                        OperatorCall("core.take", 1)
                            .withArgument("count", PvInteger(BigInteger.ONE)),
                    )
                    .then(OperatorCall("properties.property-escapes", 1)),
            ),
            document,
        )
        assertEquals(2, escapes.size)
        assertTrue(escapes.all { it is PropertiesMatch.Escape })
        val escapeMatches = escapes.map { it as PropertiesMatch.Escape }
        assertTrue(escapeMatches.any { it.inKey })
        assertTrue(escapeMatches.any { !it.inKey })
    }

    /** Vector case query.logical-and-syntax-order (java-properties-v1.json:
 *): logical-line-natural-lines returns the exact natural
     * constituents in source order; the syntax filters (text, raw bytes,
     * UTF16BE/1) merge into the Key/Value pieces with the exact kinds. */
    @Test
    fun logicalAndSyntaxOrder() {
        val logical = parseReader(
            "k=one\\\r\n two\n".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val constituents = executePropertiesQuery(
            executable(
                QueryExpression(consema.protocol.ExpressionKind.Input)
                    .then(OperatorCall("properties.logical-lines", 1))
                    .then(OperatorCall("properties.logical-line-natural-lines", 1)),
            ),
            logical,
        )
        assertEquals(2, constituents.size)
        assertEquals(
            listOf(0, 1),
            constituents.map { (it as PropertiesMatch.NaturalLine).ordinal },
        )

        val syntax = parseReader(
            "键=值\n".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val text = QueryExpression(consema.protocol.ExpressionKind.Input)
            .then(
                OperatorCall("properties.syntax-text-equals", 1)
                    .withArgument("text", PvString("值")),
            )
        val raw = QueryExpression(consema.protocol.ExpressionKind.Input)
            .then(
                OperatorCall("properties.syntax-raw-bytes-equals", 1)
                    .withArgument("bytes", PvBytes.of(hexBytes("e994ae"))),
            )
        val utf16 = QueryExpression(consema.protocol.ExpressionKind.Input)
            .then(
                OperatorCall("properties.syntax-utf16be-equals", 1)
                    .withArgument("code_units", PvBytes.of(hexBytes("503c"))),
            )
        val merged = executePropertiesSyntaxQuery(
            executable(
                QueryExpression(
                    consema.protocol.ExpressionKind.StructureOrderMerge,
                    branches = listOf(text, raw, utf16),
                ),
                Domains.javaPropertiesLosslessSyntaxV1(),
            ),
            syntax,
        )
        assertEquals(3, merged.size)
        assertEquals(
            listOf(PropertiesSyntaxKind.Key, PropertiesSyntaxKind.Value, PropertiesSyntaxKind.Value),
            merged.map { it.kind },
        )
    }

    /** Vector case query.validation-limit-cancellation
     * (java-properties-v1.json:70-74): an odd-length UTF16BE/1 filter
     * argument is invalid before the first match; the result limit raises
     * core.query.resource-limit@1; cancellation surfaces as CANCELLED. */
    @Test
    fun validationLimitCancellation() {
        // Odd-length (one byte) UTF16BE/1 key filter is invalid
        // (kotlin/src/main/kotlin/consema/protocol/QueryValidate.kt; query.rs).
        val invalid = assertFailsWith<QueryFailureException> {
            QueryDefinition(Domains.javaPropertiesNativeV1())
                .withExpression(
                    QueryExpression(consema.protocol.ExpressionKind.Input)
                        .then(OperatorCall("properties.document-properties", 1))
                        .then(
                            OperatorCall("properties.property-key-equals", 1)
                                .withArgument("key", PvBytes.of(byteArrayOf(0))),
                        ),
                )
                .validate()
        }
        assertEquals(QueryFailureKind.INVALID_ARGUMENT, invalid.kind)
        assertEquals("key", invalid.argument)

        val document = parseReader("a=1\nb=2\n".toByteArray(Charsets.UTF_8), SourceEncoding.Utf8)
        val limited = assertFailsWith<QueryFailureException> {
            executePropertiesQuery(
                executable(
                    QueryExpression(consema.protocol.ExpressionKind.Input)
                        .then(OperatorCall("properties.document-properties", 1)),
                ),
                document,
                QueryLimits(maxSteps = 100, maxResults = 1),
            )
        }
        assertEquals(QueryFailureKind.RESOURCE_LIMIT, limited.kind)

        val cancellation = CancellationToken()
        cancellation.cancel()
        val cancelled = assertFailsWith<QueryFailureException> {
            executePropertiesQuery(
                executable(
                    QueryExpression(consema.protocol.ExpressionKind.Input)
                        .then(OperatorCall("properties.document-properties", 1)),
                ),
                document,
                cancellation = cancellation,
            )
        }
        assertEquals(QueryFailureKind.CANCELLED, cancelled.kind)
    }
}

/** Binds one validated properties native/syntax query to the ordered-results
 * capability (the Rust test helper, query.rs). */
private fun executable(
    expression: QueryExpression,
    domain: consema.protocol.QueryDomain = Domains.javaPropertiesNativeV1(),
): ExecutableQuery {
    val capabilities = CapabilitySet()
    capabilities.insert(CapabilityId("core.query.ordered-results", 1))
    return QueryDefinition(domain)
        .withExpression(expression)
        .validate()
        .let { ExecutableQuery.bind(it, capabilities) }
}

/** Decodes a lowercase hex string to exact bytes. */
private fun hexBytes(hex: String): ByteArray {
    val bytes = ByteArray(hex.length / 2)
    for (i in bytes.indices) {
        bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return bytes
}
