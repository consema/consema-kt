// Cross-language PVCE/PGCE byte-parity harness (roadmap §16.1 hard gate:
// "Rust 与 Go 的 PVCE/PGCE bytes 完全一致", extended to Kotlin).
//
// The Rust encoder is the single byte authority (consema-rs/consema-pvce,
// consema-rs/consema-graph). The Kotlin side never imports or calls Rust: the
// shared input set (conformance/differential/cases.json) is encoded with
// the Kotlin codecs (consema.core.Pvce / consema.graph.Pgce) and compared
// byte for byte with the Rust golden files produced by the Rust example
// (consema-rs/consema-conformance/examples/emit_parity_bytes.rs) — one
// `<case-id>.hex` per case — plus the bidirectional direction (Rust bytes
// decode under the Kotlin decoders and re-encode byte-identically).
// Orchestration: scripts/kotlin-verify-byte-parity.ps1 provisions the golden
// directory; the test reads it via CONSEMA_DIFFERENTIAL_RUST_DIR.
//
// Mirrors consema-go/go/conformance/differential/differential_test.go (the Go harness
// is the cross-reference; the Kotlin encoder API is the Kotlin-idiomatic
// surface of the same codecs).

package consema.differential

import consema.core.DecodeLimits
import consema.core.PortableValue
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.core.decodePvce
import consema.core.encodePvce
import consema.core.equal as coreEqual
import consema.graph.Builder
import consema.graph.Graph
import consema.graph.GraphLimits
import consema.graph.MappingEntry
import consema.graph.NodeId
import consema.graph.PgceLimits
import consema.graph.decodePgce
import consema.graph.encodePgce
import consema.graph.equal as graphEqual
import consema.protocol.ProtocolLimits
import consema.protocol.decodeJson
import java.io.File

/** The frozen manifest id of the byte-parity input set. */
const val PARITY_MANIFEST = "consema.differential.byte-parity@1"

/** The task's lower bound for the input set ("至少 40 个 case"). */
const val PARITY_MIN_CASES = 40

/** One entry of cases.json. */
data class ParityCase(
    val id: String,
    val codec: String, // "pvce" or "pgce"
    val value: String, // pvce transport JSON text ("" when absent)
    val graph: GraphDesc?, // pgce neutral descriptor
    val kinds: List<String>,
)

/** The neutral PortableGraph descriptor of cases.json (the same shape as
 * conformance/vectors/portable-graph-v1.json inputs). */
data class GraphDesc(val roots: List<Int>, val nodes: List<NodeDesc>)

data class NodeDesc(
    val kind: String, // "Scalar", "Sequence", "Mapping"
    val tag: String,
    val content: String,
    val items: List<Int>,
    val entries: List<MappingDesc>,
)

data class MappingDesc(val key: Int, val value: Int)

/** The outcome of one byte-parity run. */
data class ParityReport(
    val passed: Int,
    val failures: List<String>,
    val pvceCount: Int,
    val pgceCount: Int,
) {
    val total: Int get() = passed + failures.size
}

/**
 * Loads and validates the provisioned case set: manifest id, case count
 * lower bound, unique ids, known codecs, decodable PVCE values, buildable
 * PGCE graphs, and fifteen-kind coverage. Throws [IllegalArgumentException]
 * on any violation.
 */
fun loadParityCaseFile(file: File): List<ParityCase> {
    val root = loadCaseFile(file)
    val manifest = objectString(root, "manifest", "case file")
    require(manifest == PARITY_MANIFEST) {
        "cases.json manifest = $manifest, want $PARITY_MANIFEST"
    }
    val caseValues = objectArray(root, "cases", "case file")
    require(caseValues.size >= PARITY_MIN_CASES) {
        "cases.json has ${caseValues.size} cases, want >= $PARITY_MIN_CASES (the differential input set)"
    }
    val seen = HashSet<String>()
    val kinds = HashSet<String>()
    val cases = ArrayList<ParityCase>(caseValues.size)
    for (value in caseValues) {
        val fields = value as? PvObject ?: error("case must be an Object")
        val id = objectString(fields, "id", "case")
        require(id.isNotEmpty()) { "case with an empty id" }
        require(seen.add(id)) { "duplicate case id $id" }
        val codec = objectString(fields, "codec", "case $id")
        val kindsValue = objectArray(fields, "kinds", "case $id")
            .map { (it as? PvString)?.value ?: error("case $id: kinds items must be Strings") }
        kinds.addAll(kindsValue)
        when (codec) {
            "pvce" -> {
                val valueText = objectString(fields, "value", "case $id")
                require(valueText.isNotEmpty()) { "case $id: pvce case without a value" }
                // The strict canonicality check (parse + re-encode) keeps the
                // file's transport JSON honest; the Rust side must accept the
                // same text.
                decodeJson(valueText.toByteArray(Charsets.UTF_8), ProtocolLimits.default)
                cases.add(ParityCase(id, codec, valueText, null, kindsValue))
            }
            "pgce" -> {
                val graphDesc = objectObjectOr(fields, "graph") ?: error("case $id: pgce case without a graph")
                val desc = parseGraphDesc(graphDesc, id)
                buildParityGraph(desc, id) // validate buildability
                cases.add(ParityCase(id, codec, "", desc, kindsValue))
            }
            else -> error("case $id: unknown codec $codec")
        }
    }
    for (kind in allKindNames) {
        require(kind in kinds) { "case set does not cover kind $kind (kinds metadata)" }
    }
    return cases
}

/** Parses one neutral graph descriptor. */
fun parseGraphDesc(graph: PvObject, id: String): GraphDesc {
    val nodes = objectArray(graph, "nodes", "case $id")
        .map { node ->
            val nodeFields = node as? PvObject ?: error("case $id: graph node must be an Object")
            NodeDesc(
                kind = objectString(nodeFields, "kind", "case $id node"),
                tag = objectString(nodeFields, "tag", "case $id node"),
                content = objectStringOr(nodeFields, "content"),
                items = objectIntArrayOr(nodeFields, "items") ?: emptyList(),
                entries = (objectArrayOr(nodeFields, "entries") ?: emptyList()).map { entry ->
                    val entryFields = entry as? PvObject ?: error("case $id: mapping entry must be an Object")
                    MappingDesc(
                        key = objectInt(entryFields, "key", "case $id entry"),
                        value = objectInt(entryFields, "value", "case $id entry"),
                    )
                },
            )
        }
    val roots = objectArray(graph, "roots", "case $id")
        .map { (it as? PvInteger)?.value?.toInt() ?: error("case $id: roots must be Integers") }
    return GraphDesc(roots, nodes)
}

/**
 * Builds the graph of a neutral descriptor (the mirror of the Rust runner's
 * graph_from_value and the Go harness's buildGraph).
 */
fun buildParityGraph(desc: GraphDesc): Graph = buildParityGraph(desc, "case")

/** Builds the graph of a neutral descriptor; [id] is used for error text. */
fun buildParityGraph(desc: GraphDesc, id: String): Graph {
    val builder = Builder.withLimits(GraphLimits.default)
    val ids = ArrayList<NodeId>(desc.nodes.size)
    for (index in desc.nodes.indices) {
        ids.add(builder.reserveNode())
    }
    fun ref(index: Int): NodeId {
        require(index >= 0 && index < ids.size) {
            "case $id: node reference $index out of range (0..${ids.size - 1})"
        }
        return ids[index]
    }
    for ((index, node) in desc.nodes.withIndex()) {
        when (node.kind) {
            "Scalar" -> builder.defineScalar(ids[index], node.tag, node.content)
            "Sequence" -> builder.defineSequence(ids[index], node.tag, node.items.map(::ref))
            "Mapping" -> builder.defineMapping(
                ids[index],
                node.tag,
                node.entries.map { MappingEntry(ref(it.key), ref(it.value)) },
            )
            else -> error("case $id: unknown node kind ${node.kind}")
        }
    }
    for (index in desc.roots) {
        builder.pushRoot(ref(index))
    }
    return builder.build()
}

/**
 * Runs the byte-parity comparison: encodes every case with the Kotlin
 * codecs and compares the bytes with the Rust golden files; checks the
 * bidirectional direction (Rust bytes decode under the Kotlin decoders and
 * re-encode byte-identically). Returns the per-case outcome.
 */
fun runByteParity(cases: List<ParityCase>, rustDir: File?): ParityReport {
    val failures = ArrayList<String>()
    var passed = 0
    var pvceCount = 0
    var pgceCount = 0
    for (case in cases) {
        val rustBytes = if (rustDir != null) {
            val text = File(rustDir, "${case.id}.hex").readText()
            try {
                unhex(text)
            } catch (e: Exception) {
                failures.add("case ${case.id}: Rust byte file is not valid hex: ${e.message}")
                continue
            }
        } else {
            ByteArray(0)
        }
        when (case.codec) {
            "pvce" -> {
                pvceCount++
                val value = decodeJson(case.value.toByteArray(Charsets.UTF_8), ProtocolLimits.default)
                val kotlinBytes = encodePvce(value)
                if (rustDir == null) {
                    passed++
                    continue
                }
                if (!kotlinBytes.contentEquals(rustBytes)) {
                    failures.add(firstDiff(case.id, "pvce", kotlinBytes, rustBytes))
                    continue
                }
                // Bidirectional: Rust bytes decode under the Kotlin decoder
                // and re-encode byte-identically (roadmap §16.1 gate).
                val decoded = try {
                    decodePvce(rustBytes, DecodeLimits.default)
                } catch (e: Exception) {
                    failures.add("case ${case.id}: Kotlin cannot decode the Rust PVCE bytes: ${e.message}")
                    continue
                }
                if (!coreEqual(decoded, value)) {
                    failures.add("case ${case.id}: Kotlin decode of the Rust PVCE bytes is not Equal to the source value")
                    continue
                }
                val reEncoded = encodePvce(decoded)
                if (!reEncoded.contentEquals(rustBytes)) {
                    failures.add(firstDiff(case.id, "pvce-rust->kotlin->re-encode", reEncoded, rustBytes))
                    continue
                }
                passed++
            }
            "pgce" -> {
                pgceCount++
                val graph = buildParityGraph(case.graph!!, case.id)
                val kotlinBytes = encodePgce(graph)
                if (rustDir == null) {
                    passed++
                    continue
                }
                if (!kotlinBytes.contentEquals(rustBytes)) {
                    failures.add(firstDiff(case.id, "pgce", kotlinBytes, rustBytes))
                    continue
                }
                // Bidirectional: Rust bytes decode under the Kotlin decoder,
                // Equal the original graph, and re-encode byte-identically.
                val decoded = try {
                    decodePgce(rustBytes, PgceLimits.default)
                } catch (e: Exception) {
                    failures.add("case ${case.id}: Kotlin cannot decode the Rust PGCE bytes: ${e.message}")
                    continue
                }
                if (!graphEqual(decoded, graph)) {
                    failures.add("case ${case.id}: Kotlin decode of the Rust PGCE bytes is not Equal to the source graph")
                    continue
                }
                val reEncoded = encodePgce(decoded)
                if (!reEncoded.contentEquals(rustBytes)) {
                    failures.add(firstDiff(case.id, "pgce-rust->kotlin->re-encode", reEncoded, rustBytes))
                    continue
                }
                passed++
            }
        }
    }
    return ParityReport(passed, failures, pvceCount, pgceCount)
}

/** Reports a byte-level difference with the first differing offset and the
 * full hex of both sides (the Go firstDiff mirror). */
fun firstDiff(id: String, direction: String, kotlinBytes: ByteArray, rustBytes: ByteArray): String {
    var index = 0
    while (index < kotlinBytes.size && index < rustBytes.size && kotlinBytes[index] == rustBytes[index]) {
        index++
    }
    return "case $id ($direction): Kotlin ${kotlinBytes.size} bytes, Rust ${rustBytes.size} bytes, " +
        "first difference at offset $index\n  Kotlin: ${hex(kotlinBytes)}\n  Rust:   ${hex(rustBytes)}"
}
