// Golden transcriptions of the materialization vector cases.
//
// Data authority: conformance/vectors/operations-v1.json (operations.v1.
// materialize-toml-*, cited in each test), RFC 0004 §3-§8, and the Rust
// crate materialization tests (consema-toml/src/materialization.rs:886-
// 1115). The L5 conformance runner executes the shared vectors directly;
// these unit tests pin the golden transcriptions in the committed CI.

package toml

import consema.core.Entry
import consema.core.EntryMappingBuilder
import consema.core.Kind
import consema.core.ObjectBuilder
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.core.PvArray
import consema.document.MaterializationFidelity
import consema.document.MaterializationLimits
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.MappingPolicy
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.toml.ProjectionRequest
import consema.toml.ProjectionResult
import consema.toml.ProjectionTarget
import consema.toml.TomlProfile
import consema.toml.materialize
import consema.toml.parse
import consema.toml.project
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TomlMaterializationTest {

    private fun request(newline: NewlinePolicy) = MaterializationRequest.new(
        ProfileId("toml.1.0", 1),
        MaterializationStyleId("toml.canonical-document", 1),
    ).withNewline(newline)

    private fun complete(result: MaterializationResult<consema.toml.TomlDocument>) =
        assertIs<MaterializationResult.Complete<consema.toml.TomlDocument>>(result)

    private fun failed(result: MaterializationResult<consema.toml.TomlDocument>) =
        assertIs<MaterializationResult.Failed>(result)

    /** operations-v1.json operations.v1.materialize-toml-native: the
     * canonical document round-trips through projection to the input with
     * Exact fidelity and provenance. */
    @Test
    fun materializeTomlNative() {
        val source = (
            "name = \"api\"\n" +
                "port = 8080\n" +
                "ratio = 1.5\n" +
                "enabled = true\n" +
                "date = 1979-05-27\n" +
                "time = 07:32:00.123456789\n" +
                "local = 1979-05-27T07:32:00\n" +
                "when = 1979-05-27T07:32:00Z\n" +
                "ports = [80, 443]\n" +
                "meta = { owner = \"ops\" }\n"
            ).toByteArray()
        val document = parse(source, TomlProfile.TOML_1_0_V1, consema.document.ParseLimits.default)
        val projection = document.project(ProjectionRequest.new(ProjectionTarget.BEST_EXACT_CORE_V1))
        val input = assertIs<ProjectionResult.Complete>(projection).projection.value
        val result = complete(materialize(input, request(NewlinePolicy.Lf)))
        assertEquals(MaterializationFidelity.Exact, result.materialization.fidelity)
        assertTrue(result.materialization.provenance.entries().isNotEmpty())
        // The materialized document reparses and reprojects to the input.
        val reprojected = result.materialization.document
            .project(ProjectionRequest.new(ProjectionTarget.BEST_EXACT_CORE_V1))
        val reprojectedComplete = assertIs<ProjectionResult.Complete>(reprojected)
        assertTrue(consema.core.equal(reprojectedComplete.projection.value, input))
    }

    /** operations-v1.json operations.v1.materialize-toml-explicit-mapping:
     * the explicit mapping conversion is reported and changes fidelity to
     * Transformed. */
    @Test
    fun explicitMappingConversionIsReported() {
        val mapping = EntryMappingBuilder()
            .push(PvString("a"), PvBoolean(true))
            .push(PvString("b"), PvInteger(BigInteger.valueOf(2)))
            .build()
        val result = complete(
            materialize(
                mapping,
                request(NewlinePolicy.CrLf).withMappingPolicy(MappingPolicy.UniqueStringEntriesToObject),
            ),
        )
        assertEquals(MaterializationFidelity.Transformed, result.materialization.fidelity)
        assertEquals(1, result.materialization.report.events().size)
        assertEquals(
            "core.materialization.mapping-transformed@1",
            result.materialization.report.events()[0].code,
        )
        assertTrue(result.materialization.document.render().contentEquals("\"a\" = true\r\n\"b\" = 2\r\n".toByteArray()))
    }

    /** operations-v1.json operations.v1.materialize-toml-implicit-mapping-
     * rejected: an EntryMapping root without the explicit policy fails. */
    @Test
    fun implicitMappingRejected() {
        val mapping = EntryMappingBuilder().push(PvString("x"), PvBoolean(true)).build()
        val result = failed(materialize(mapping, request(NewlinePolicy.Lf)))
        assertEquals("core.materialization.unrepresentable@1", result.attempt.failure.code)
        assertEquals(Kind.EntryMapping, result.attempt.failure.valueKind)
        assertTrue(result.attempt.analyzedInputPaths.isEmpty())
    }

    /** operations-v1.json operations.v1.materialize-toml-null-rejected:
     * Null is outside the TOML representability closure. */
    @Test
    fun nullRejected() {
        val root = ObjectBuilder().insert("n", consema.core.PvNull).build()
        val result = failed(materialize(root, request(NewlinePolicy.Lf)))
        assertEquals("core.materialization.unrepresentable@1", result.attempt.failure.code)
        assertEquals(Kind.Null, result.attempt.failure.valueKind)
    }

    /** operations-v1.json operations.v1.materialize-toml-output-limit:
     * exceeding max_output_bytes fails without a Document. */
    @Test
    fun outputLimitFails() {
        val root = ObjectBuilder().insert("name", PvString("api")).build()
        val limited = request(NewlinePolicy.Lf)
            .withLimits(MaterializationLimits(maxOutputBytes = 4, maxInputNodes = 1_000_000, maxDepth = 256, maxReportEntries = 100_000, maxProvenanceEntries = 2_000_000))
        val result = failed(materialize(root, limited))
        assertEquals("core.materialization.resource-limit@1", result.attempt.failure.code)
        assertEquals("output-bytes", result.attempt.failure.name)
    }

    /** materialization.rs:908-959: a full scalar/container/temporal root
     * round-trips with Exact fidelity and a final newline. */
    @Test
    fun canonicalDocumentRoundTripsScalarContainerAndTemporalValues() {
        val date = consema.core.PvDate.of(BigInteger.valueOf(2026), 8, 4)
        val time = consema.core.PvTime.of(
            12, 34, 56,
            PvDecimal.of(BigInteger.valueOf(123), BigInteger.valueOf(-3)),
        )
        val local = consema.core.PvLocalDateTime(date, time)
        val offset = consema.core.PvOffsetDateTime.of(local, 8 * 60 * 60)
        val nested = ObjectBuilder().insert("enabled", PvBoolean(true)).build()
        val sequence = PvArray(listOf(PvInteger(BigInteger.ONE), PvString("two")))
        val root = ObjectBuilder()
            .insert("date", date)
            .insert("time", time)
            .insert("local", local)
            .insert("offset", offset)
            .insert("items", sequence)
            .insert("nested", nested)
            .insert("float", PvBinaryFloat64(java.lang.Double.doubleToRawLongBits(1.5)))
            .insert("nan", PvBinaryFloat64(0x7FF8000000000000L))
            .build()
        val result = complete(materialize(root, request(NewlinePolicy.Lf)))
        assertEquals(MaterializationFidelity.Exact, result.materialization.fidelity)
        assertTrue(
            result.materialization.document.render().lastOrNull() == '\n'.code.toByte(),
        )
        val reprojected = result.materialization.document
            .project(ProjectionRequest.new(ProjectionTarget.BEST_EXACT_CORE_V1))
        val reprojectedComplete = assertIs<ProjectionResult.Complete>(reprojected)
        assertTrue(consema.core.equal(reprojectedComplete.projection.value, root))
    }

    /** materialization.rs:996-1114: unrepresentable values and implicit
     * conversions fail; the request contract is enforced. */
    @Test
    fun unrepresentableValuesAndContractFailures() {
        val tooLarge = PvInteger(BigInteger("9223372036854775808"))
        val tooLargeRoot = ObjectBuilder().insert("value", tooLarge).build()
        assertEquals(
            Kind.Integer,
            failed(materialize(tooLargeRoot, request(NewlinePolicy.Lf))).attempt.failure.valueKind,
        )

        val duplicate = EntryMappingBuilder()
            .push(PvString("x"), PvBoolean(true))
            .push(PvString("x"), PvBoolean(false))
            .build()
        assertEquals(
            Kind.String,
            failed(
                materialize(
                    duplicate,
                    request(NewlinePolicy.Lf).withMappingPolicy(MappingPolicy.UniqueStringEntriesToObject),
                ),
            ).attempt.failure.valueKind,
        )

        val nanRoot = ObjectBuilder()
            .insert("nan", PvBinaryFloat64(0x7FF8000000000001L))
            .build()
        assertEquals(
            Kind.BinaryFloat64,
            failed(materialize(nanRoot, request(NewlinePolicy.Lf))).attempt.failure.valueKind,
        )

        val emptyRoot = ObjectBuilder().build()
        assertEquals(
            "core.materialization.unsupported-newline@1",
            failed(materialize(emptyRoot, request(NewlinePolicy.None))).attempt.failure.code,
        )
        assertEquals(
            "core.materialization.unsupported-encoding@1",
            failed(
                materialize(
                    emptyRoot,
                    request(NewlinePolicy.Lf).withEncoding(SourceEncoding.Utf16Be),
                ),
            ).attempt.failure.code,
        )
    }

    /** RFC 0004 §4: the canonical-document style emits one assignment per
     * root entry with inline tables for nested objects. */
    @Test
    fun canonicalStyleSpelling() {
        val root = PvObject(
            listOf(
                Entry("service", PvObject(listOf(Entry("port", PvInteger(BigInteger.valueOf(8080)))))),
            ),
        )
        val result = complete(materialize(root, request(NewlinePolicy.Lf)))
        assertTrue(
            result.materialization.document.render().contentEquals(
                "\"service\" = { \"port\" = 8080 }\n".toByteArray(),
            ),
        )
    }

    /** RFC 0004 §4: an empty root emits only the final newline. */
    @Test
    fun emptyRootEmitsFinalNewline() {
        val result = complete(materialize(ObjectBuilder().build(), request(NewlinePolicy.Lf)))
        assertTrue(result.materialization.document.render().contentEquals("\n".toByteArray()))
    }

    /** RFC 0004 §8: provenance covers every emitted value and association
     * with Direct relations for object roots. */
    @Test
    fun provenanceCoversValuesAndAssociations() {
        val root = ObjectBuilder()
            .insert("x", PvInteger(BigInteger.valueOf(1)))
            .build()
        val result = complete(materialize(root, request(NewlinePolicy.Lf)))
        val provenance = result.materialization.provenance.entries()
        assertTrue(provenance.isNotEmpty())
        for (entry in provenance) {
            assertTrue(entry.outputs.isNotEmpty())
            assertEquals(result.materialization.document.snapshotIdentity, entry.outputs[0].snapshot)
        }
    }
}
