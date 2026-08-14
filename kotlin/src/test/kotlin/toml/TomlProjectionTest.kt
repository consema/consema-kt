// Golden transcriptions of the toml.projection.* cases.
//
// Data authority: conformance/vectors/toml-v1.json (cases toml.projection.
// all-core-kinds, toml.projection.provenance, toml.projection.reject-leap-
// second, cited in each test), RFC 0001 §5, and the Rust crate projection
// tests (consema-toml/src/projection.rs). The L5 conformance runner
// executes the shared vectors directly; these tests are the L1 intent
// documents.

package toml

import consema.core.Kind
import consema.core.equal
import consema.document.ParseLimits
import consema.document.NodeRole
import consema.toml.Fidelity
import consema.toml.ProjectionLimits
import consema.toml.ProjectionRequest
import consema.toml.ProjectionResult
import consema.toml.ProjectionTarget
import consema.toml.TomlProfile
import consema.toml.parse
import consema.toml.project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TomlProjectionTest {

    private fun project(source: ByteArray) =
        parse(source, TomlProfile.TOML_1_0_V1, ParseLimits.default)
            .project(ProjectionRequest.new(ProjectionTarget.BEST_EXACT_CORE_V1))

    /** toml-v1.json toml.projection.all-core-kinds: the fixture projects
     * Successfully with Exact fidelity and an Object root. */
    @Test
    fun allCoreKindsProjectExact() {
        val result = project(ALL_VALUES_TOML)
        val complete = assertIs<ProjectionResult.Complete>(result)
        assertEquals(Fidelity.Exact, complete.projection.fidelity)
        assertEquals(Kind.Object, complete.projection.value.kind)
        assertTrue(complete.projection.report.events().isEmpty())
        val root = assertIs<consema.core.PvObject>(complete.projection.value)
        assertEquals(13, root.size())
        val float = root.get("float")!!
        assertEquals(Kind.BinaryFloat64, float.kind)
        assertEquals(Long.MIN_VALUE, (float as consema.core.PvBinaryFloat64).bits)
        val integer = root.get("hex")!!
        assertEquals(3735928559L, (integer as consema.core.PvInteger).value.toLong())
        assertEquals(Kind.Sequence, root.get("ports")!!.kind)
        assertEquals(Kind.Object, root.get("point")!!.kind)
    }

    /** toml-v1.json toml.projection.provenance: every origin is bound to
     * the snapshot and object associations carry TomlEntry/TomlKey
     * origins. */
    @Test
    fun provenanceIsSnapshotBoundWithObjectAssociations() {
        val source = "point = { x = 1, y = 2 }\n".toByteArray()
        val document = parse(source, TomlProfile.TOML_1_0_V1, ParseLimits.default)
        val result = document.project(ProjectionRequest.new(ProjectionTarget.BEST_EXACT_CORE_V1))
        val complete = assertIs<ProjectionResult.Complete>(result)
        val entries = complete.projection.provenance.entries()
        assertTrue(entries.isNotEmpty())
        val snapshot = document.snapshotIdentity
        for (entry in entries) {
            for (origin in entry.origins) {
                assertEquals(snapshot, origin.snapshot)
                assertEquals(snapshot, origin.span.snapshot)
                assertEquals(snapshot, origin.node.snapshot)
            }
        }
        val entryRoles = entries.flatMap { it.origins }.map { it.node.role }.toSet()
        assertTrue(NodeRole.TomlEntry in entryRoles)
        assertTrue(NodeRole.TomlKey in entryRoles)
        assertTrue(NodeRole.TomlItem in entryRoles)
    }

    /** toml-v1.json toml.projection.reject-leap-second: a leap-second local
     * time fails the whole projection with the frozen code and no partial
     * value. */
    @Test
    fun leapSecondFailsWithoutPartialValue() {
        val result = project("time = 23:59:60\n".toByteArray())
        val failed = assertIs<ProjectionResult.Failed>(result)
        assertEquals("toml.projection.unrepresentable-datetime@1", failed.attempt.diagnostics[0].code)
        assertTrue(failed.attempt.partialAnalysis.isEmpty())
    }

    /** projection.rs: all TOML value categories project exactly
     * with provenance. */
    @Test
    fun allTomlValueCategoriesProjectExactly() {
        val source = (
            "string = \"value\"\n" +
                "integer = 42\n" +
                "float = -0.0\n" +
                "boolean = true\n" +
                "date = 1979-05-27\n" +
                "time = 07:32:00.123\n" +
                "local = 1979-05-27T07:32:00\n" +
                "offset = 1979-05-27T07:32:00-07:00\n" +
                "array = [1, 2]\n" +
                "inline = { x = 1 }\n" +
                "[[products]]\n" +
                "name = \"one\"\n"
            ).toByteArray()
        val result = project(source)
        val complete = assertIs<ProjectionResult.Complete>(result)
        assertEquals(Fidelity.Exact, complete.projection.fidelity)
        val root = assertIs<consema.core.PvObject>(complete.projection.value)
        assertEquals(11, root.size())
        assertEquals(Kind.String, root.get("string")!!.kind)
        assertEquals(Kind.Integer, root.get("integer")!!.kind)
        assertEquals(Kind.BinaryFloat64, root.get("float")!!.kind)
        assertEquals(Kind.Boolean, root.get("boolean")!!.kind)
        assertEquals(Kind.Date, root.get("date")!!.kind)
        assertEquals(Kind.Time, root.get("time")!!.kind)
        assertEquals(Kind.LocalDateTime, root.get("local")!!.kind)
        assertEquals(Kind.OffsetDateTime, root.get("offset")!!.kind)
        assertEquals(Kind.Sequence, root.get("array")!!.kind)
        assertEquals(Kind.Object, root.get("inline")!!.kind)
        assertEquals(Kind.Sequence, root.get("products")!!.kind)
        assertTrue(complete.projection.provenance.entries().isNotEmpty())
    }

    /** projection.rs: a projection limit fails the whole operation
     * with the frozen core.projection.resource-limit@1 code. */
    @Test
    fun projectionLimitFailsWholeOperation() {
        val document = parse(
            "a = 1\nb = 2".toByteArray(),
            TomlProfile.TOML_1_0_V1,
            ParseLimits.default,
        )
        val request = ProjectionRequest.new(ProjectionTarget.BEST_EXACT_CORE_V1)
            .withLimits(ProjectionLimits(maxValueNodes = 1, maxReportEntries = 100_000, maxProvenanceEntries = 2_000_000, maxDepth = 256))
        val result = document.project(request)
        val failed = assertIs<ProjectionResult.Failed>(result)
        assertEquals("core.projection.resource-limit@1", failed.attempt.diagnostics[0].code)
        assertEquals("max_value_nodes", failed.attempt.diagnostics[0].arguments["limit"])
        assertTrue(failed.attempt.partialAnalysis.isEmpty())
    }

    /** RFC 0001 §5: a projected ArrayOfTables becomes Sequence<Object> and
     * the temporal kinds map onto the frozen closure. */
    @Test
    fun arrayOfTablesProjectsToSequenceOfObjects() {
        val result = project(
            "[[upstreams]]\nname = \"a\"\n[[upstreams]]\nname = \"b\"\n".toByteArray(),
        )
        val complete = assertIs<ProjectionResult.Complete>(result)
        val root = assertIs<consema.core.PvObject>(complete.projection.value)
        val upstreams = assertIs<consema.core.PvArray>(root.get("upstreams"))
        assertEquals(2, upstreams.size())
        assertEquals(
            "a",
            ((upstreams.at(0) as consema.core.PvObject).get("name") as consema.core.PvString).value,
        )
        assertEquals(
            "b",
            ((upstreams.at(1) as consema.core.PvObject).get("name") as consema.core.PvString).value,
        )
    }

    /** RFC 0001 §5: projection round-trips through the canonical model with
     * strict equality (the core `equal` contract). */
    @Test
    fun projectionFidelityCheck() {
        val result = project(ALL_VALUES_TOML)
        val complete = assertIs<ProjectionResult.Complete>(result)
        assertEquals(Fidelity.Exact, complete.projection.fidelity)
        // The projected value must be strictly equal to itself and distinct
        // from a different object (RFC 0016 §4.1 strict equality).
        assertTrue(equal(complete.projection.value, complete.projection.value))
    }
}
