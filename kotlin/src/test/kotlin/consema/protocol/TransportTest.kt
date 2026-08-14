// Protocol transport and CLI-record tests.
//
// The canonical JSON transport follows RFC 0015 §3.2 / RFC 0016 §4.2 (the
// shared protocol-v1.json vectors exercise it); the CLI record shapes follow
// RFC 0015 §4/§8/§9. These tests run in the committed CI (kotlin-gates,
// gradlew test).

package consema.protocol

import consema.core.PvArray
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.equal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransportTest {

    @Test
    fun canonicalJsonRoundTripsAllKinds() {
        val limits = ProtocolLimits.default
        // A tagged value tree of several kinds.
        val tagged: consema.core.PortableValue = PvArray(
            listOf(
                PvNull,
                PvBoolean(true),
                PvString("héllo"),
                PvInteger(BigInteger("12345678901234567890")),
                PvBytes.of(byteArrayOf(0, 0xff.toByte(), 0x41)),
            ),
        )
        val bytes = encodeJson(tagged, limits)
        val decoded = decodeJson(bytes, limits)
        assertEquals(true, equal(tagged, decoded))
        // Byte-exact canonicality: decoding a non-canonical form (space)
        // fails.
        val nonCanonical = String(bytes, Charsets.UTF_8).replace("{\"type\":\"Null\"", "{\"type\": \"Null\"")
        assertFailsWith<ProtocolException> {
            decodeJson(nonCanonical.toByteArray(Charsets.UTF_8), limits)
        }
    }

    @Test
    fun protocolMessageEnvelopeRoundTrips() {
        val registry = ContractRegistry.forVersion(ContractRegistryVersion.V7)
        val payload = PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.profile-descriptor@1")),
                consema.core.Entry("format_family_id", PvString("json")),
                consema.core.Entry("format_family_version", PvInteger(BigInteger.ONE)),
                consema.core.Entry("profile_id", PvString("json.strict")),
                consema.core.Entry("profile_version", PvInteger(BigInteger.ONE)),
                consema.core.Entry("base_profile", PvNull),
                consema.core.Entry("differences", PvArray(emptyList())),
                consema.core.Entry("required_capabilities", PvArray(emptyList())),
            ),
        )
        val message = ProtocolMessage.of(ContractId("core.profile-descriptor", 1), payload, registry)
        val value = message.toValue()
        val decoded = ProtocolMessage.fromValue(value, registry)
        assertEquals("core.profile-descriptor", decoded.contract.id)
        assertEquals(1, decoded.contract.version)
        assertEquals(true, equal(payload, decoded.payload))
    }

    @Test
    fun cliOutputRoundTrips() {
        val registry = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)
        val diagnostic = Diagnostic.of(
            code = "json.syntax.missing-object-close@1",
            category = DiagnosticCategory.Syntax,
            severity = Severity.Error,
            primary = SourceLocation.of("file.json", 0uL, 8uL),
            related = emptyList(),
            arguments = mapOf("line" to "1"),
            notes = emptyList(),
            fixes = emptyList(),
            occurrence = 0uL,
            registry = registry,
        )
        val payload = PvObject(
            listOf(
                consema.core.Entry("schema", PvString("cli.inspect@1")),
            ),
        )
        val message = CliOutputMessage.of(
            command = CliCommand.Inspect,
            exitClass = ExitClass.Success,
            productVersion = "1.0.0",
            payload = payload,
            diagnostics = listOf(diagnostic),
            redaction = Redaction.of(false, 0uL),
        )
        val decoded = CliOutputMessage.fromValue(message.toValue())
        assertEquals(CliCommand.Inspect, decoded.command)
        assertEquals(ExitClass.Success, decoded.exitClass)
        assertEquals(1, decoded.diagnostics.size)
        assertEquals("json.syntax.missing-object-close@1", decoded.diagnostics[0].code)
    }

    @Test
    fun batchPlanPresenceAndDigestConstraints() {
        val registry = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)
        // A planned entry requires source_digest == source_patch.base_digest.
        val base = ContentDigest.of(byteArrayOf(1, 2, 3))
        val target = ContentDigest.of(byteArrayOf(4, 5, 6))
        val patch = SourcePatch(
            baseDigest = base,
            targetDigest = target,
            encoding = EncodingFacts(
                profileDefault = null,
                bomPolicy = "DetectUnicode",
                bom = null,
                declaration = null,
                callerOverride = null,
                selected = SourceEncoding("Utf8", null),
            ),
            replacements = emptyList(),
            metadata = emptyMap(),
        )
        val entry = BatchPlanFileEntry.of(
            path = "a.json",
            status = BatchPlanFileStatus.Planned,
            profile = ProfileReference("json.strict", 1),
            sourceDigest = base,
            operations = emptyList(),
            sourcePatch = patch,
            failureCode = null,
            diagnostics = null,
            registry = registry,
        )
        val manifest = BatchPlanMessage.of("1.0.0", listOf(entry))
        val decoded = BatchPlanMessage.fromValue(manifest.toValue())
        assertEquals(1, decoded.files.size)
        assertEquals(BatchPlanFileStatus.Planned, decoded.files[0].status)

        // Digest mismatch is rejected.
        val mismatched = patch.copy(baseDigest = ContentDigest.of(byteArrayOf(9)))
        assertFailsWith<ProtocolException> {
            BatchPlanFileEntry.of(
                path = "a.json",
                status = BatchPlanFileStatus.Planned,
                profile = ProfileReference("json.strict", 1),
                sourceDigest = base,
                operations = emptyList(),
                sourcePatch = mismatched,
                failureCode = null,
                diagnostics = null,
                registry = registry,
            )
        }
    }

    @Test
    fun exitClassClassification() {
        assertEquals(ExitClass.Usage, classifyErrorCode("cli.usage.unknown-command@1"))
        assertEquals(ExitClass.Data, classifyErrorCode("cli.data.io@1"))
        assertEquals(ExitClass.Limit, classifyErrorCode("cli.limit.file-size@1"))
        assertEquals(ExitClass.Limit, classifyErrorCode("core.protocol.resource-limit@1"))
        assertEquals(ExitClass.Precondition, classifyErrorCode("core.source.patch-base-mismatch@1"))
        assertEquals(ExitClass.Precondition, classifyErrorCode("core.edit.conflicting-edits@1"))
        assertEquals(ExitClass.Internal, classifyErrorCode("cli.internal.unclassified@1"))
        assertEquals(ExitClass.Data, classifyErrorCode("json.syntax.invalid-number@1"))
        assertEquals(1, ExitClass.Usage.exitCode())
        assertEquals(5, ExitClass.Internal.exitCode())
    }

    @Test
    fun queryValidation() {
        // A valid pipeline validates; a role mismatch is rejected
        // (conformance/vectors/v1.json query.reject-role-mismatch).
        val definition = QueryDefinition(Domains.portableValueV1())
        val operator = OperatorCall("core.try-sequence-elements", 1)
        val validated = definition.withExpression(definition.expression.then(operator)).validate()
        assertEquals(Roles.VALUE, validated.outputRole)

        val bad = QueryDefinition(Domains.portableValueV1())
        val error = assertFailsWith<QueryFailureException> {
            bad.withExpression(bad.expression.then(OperatorCall("core.object-entry-value", 1))).validate()
        }
        assertEquals(QueryFailureKind.INVALID_OPERATOR_COMPOSITION, error.kind)
    }

    @Test
    fun windowsCodePageRegistry() {
        assertEquals("WindowsCodePage", windowsCodePageFromNumber(932)?.kind)
        assertEquals(932, windowsCodePageFromNumber(932)?.windowsCodePage)
        assertEquals(null, windowsCodePageFromNumber(9999))
    }
}
