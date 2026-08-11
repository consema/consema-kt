// PGCE/1 golden byte tests — intent documents.
//
// The hex vectors below are transcribed VERBATIM from
// conformance/vectors/portable-graph-v1.json (the language-neutral
// machine-readable vectors):
//   - pgce.empty-vector  "50474345010000"
//   - pgce.scalar-vector "504743450101010020157461673a79616d6c2e6f72672c323030323a7374720178"
//   - pgce.reject-nonminimal-varint input "5047434581000000" ->
//     NonMinimalVarint
//   - graph.isomorphic-builder-numbering: builder numbering is not semantic
//   - pgce.cycle-roundtrip: byte-stable cycle encoding
// These tests run once the toolchain is ready (START GATE).

package consema.graph

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private fun hexBytes(text: String): ByteArray {
    require(text.length % 2 == 0)
    return ByteArray(text.length / 2) { index ->
        ((Character.digit(text[index * 2], 16) shl 4) or
            Character.digit(text[index * 2 + 1], 16)).toByte()
    }
}

private const val YAML_STR = "tag:yaml.org,2002:str"
private const val YAML_SEQ = "tag:yaml.org,2002:seq"

class PgceGoldenTest {

    @Test
    fun emptyVector() {
        // conformance/vectors/portable-graph-v1.json pgce.empty-vector.
        val graph = Builder.newBuilder().build()
        assertContentEquals(hexBytes("50474345010000"), encodePgce(graph))
        assertEquals(0, decodePgce(hexBytes("50474345010000"), PgceLimits.default).nodeCount())
    }

    @Test
    fun scalarVector() {
        // conformance/vectors/portable-graph-v1.json pgce.scalar-vector:
        // roots [0], one Scalar node with tag "tag:yaml.org,2002:str",
        // content "x".
        val builder = Builder.newBuilder()
        val node = builder.reserveNode()
        builder.defineScalar(node, YAML_STR, "x")
        builder.pushRoot(node)
        val graph = builder.build()
        assertContentEquals(
            hexBytes("504743450101010020157461673a79616d6c2e6f72672c323030323a7374720178"),
            encodePgce(graph),
        )
        val decoded = decodePgce(
            hexBytes("504743450101010020157461673a79616d6c2e6f72672c323030323a7374720178"),
            PgceLimits.default,
        )
        assertEquals(true, equal(graph, decoded))
    }

    @Test
    fun rejectNonMinimalVarint() {
        // conformance/vectors/portable-graph-v1.json
        // pgce.reject-nonminimal-varint: "5047434581000000" fails as
        // NonMinimalVarint with no partial graph.
        val error = assertFailsWith<PgceException> {
            decodePgce(hexBytes("5047434581000000"), PgceLimits.default)
        }
        assertEquals(PgceErrorKind.NON_MINIMAL_VARINT, error.kind)
        assertEquals("core.pgce.non-canonical@1", error.code)
    }

    @Test
    fun isomorphicBuilderNumbering() {
        // conformance/vectors/portable-graph-v1.json
        // graph.isomorphic-builder-numbering: the same graph built with
        // different local numbering is strictly equal with equal hashes and
        // byte-identical PGCE.
        fun left(): Graph {
            val builder = Builder.newBuilder()
            val sequence = builder.reserveNode()
            val scalar = builder.reserveNode()
            builder.defineSequence(sequence, YAML_SEQ, listOf(scalar, scalar))
            builder.defineScalar(scalar, YAML_STR, "x")
            builder.pushRoot(sequence)
            return builder.build()
        }
        fun right(): Graph {
            val builder = Builder.newBuilder()
            val scalar = builder.reserveNode()
            val sequence = builder.reserveNode()
            builder.defineScalar(scalar, YAML_STR, "x")
            builder.defineSequence(sequence, YAML_SEQ, listOf(scalar, scalar))
            builder.pushRoot(sequence)
            return builder.build()
        }
        val a = left()
        val b = right()
        assertEquals(true, equal(a, b))
        assertEquals(hash(a), hash(b))
        assertContentEquals(encodePgce(a), encodePgce(b))
    }

    @Test
    fun cycleRoundTrip() {
        // conformance/vectors/portable-graph-v1.json pgce.cycle-roundtrip:
        // one Sequence node referencing itself encodes and decodes with
        // byte-stable output.
        val builder = Builder.newBuilder()
        val node = builder.reserveNode()
        builder.defineSequence(node, YAML_SEQ, listOf(node))
        builder.pushRoot(node)
        val graph = builder.build()
        val bytes = encodePgce(graph)
        val decoded = decodePgce(bytes, PgceLimits.default)
        assertEquals(true, equal(graph, decoded))
        assertContentEquals(bytes, encodePgce(decoded))
    }
}
