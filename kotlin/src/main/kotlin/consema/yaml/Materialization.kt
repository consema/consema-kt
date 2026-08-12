// Canonical PortableGraph and PortableValue materialization for YAML.
//
// Data authority:
//   - RFC 0007 §11 (docs/rfcs/0007-yaml-family-profiles-and-safety-v1.md:
//     303-353): styles yaml.canonical-block@1 and yaml.canonical-flow@1;
//     graph materialization uses canonical graph numbering, emits explicit
//     document starts for every root, and introduces deterministic anchors
//     `&g0`, `&g1`, ... for nodes whose topology requires an alias; a graph
//     node reachable from more than one root fails with
//     yaml.materialization.cross-document-sharing@1; the v1 canonical styles
//     emit every retained standard repository tag explicitly; PortableValue
//     materialization is Exact for Null/Boolean/Integer/Decimal/String/
//     Bytes/Date/minute-offset OffsetDateTime/Sequence/Object/EntryMapping
//     and the three frozen binary64 non-finite values; an EntryMapping whose
//     keys are unique Strings requires the explicit
//     UniqueStringEntriesToObject policy; output is reparsed under the
//     target profile before a Complete result is returned; UTF-16 output
//     always carries the matching BOM; raw encoded bytes are charged to
//     max_output_bytes.
//   - RFC 0004 §3-§8 (docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:56-218) pins the common request, the completion
//     algebra, and the provenance direction.
//   - conformance/vectors/yaml-v1.json pins the golden output bytes
//     (materialization.graph-cycle-flow: "--- &g0 !!seq [!!str \"one\",
//     *g0]\n"; materialization.value-flow: "--- !!map {? !!str \"a\" :
//     !!seq [!!int \"1\", !!bool \"true\"]}\n").
//   - crates/consema-yaml/src/materialization.rs is the byte-arbitration
//     authority (graph writer materialization.rs:430-728, scalar
//     presentation materialization.rs:719-728, quoted escaping
//     materialization.rs:689-709, output encoding materialization.rs:
//     775-819, value preparation materialization.rs:1146-1335, graph
//     conversion materialization.rs:1337-1503, provenance builders
//     materialization.rs:821-1056 and 1611-1824).
//
// Kotlin-idiomatic design: the completion algebra is a sealed class; the
// bounded output buffer is a StringBuilder with checked appends; failures
// carry the frozen registered codes via the [graphMaterializationCode]
// mapping.

package consema.yaml

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.Kind
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvDate
import consema.core.PvDecimal
import consema.core.PvEntryMapping
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvOffsetDateTime
import consema.core.PvString
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.document.CompleteMaterialization
import consema.document.FailedMaterializationAttempt
import consema.document.MappingPolicy
import consema.document.MaterializationException
import consema.document.MaterializationFailureKind
import consema.document.MaterializationFidelity
import consema.document.MaterializationInputLocation
import consema.document.MaterializationLimits
import consema.document.MaterializationProvenanceEntry
import consema.document.MaterializationProvenanceMap
import consema.document.MaterializationRelation
import consema.document.MaterializationReport
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializedOrigin
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ParseLimits
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.graph.Builder
import consema.graph.Graph
import consema.graph.GraphErrorKind
import consema.graph.GraphException
import consema.graph.GraphLimits
import consema.graph.MappingEntry
import consema.graph.NodeId
import consema.graph.equal
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity
import java.math.BigInteger

/** A PortableGraph location consumed by YAML materialization
 * (materialization.rs:32-60). */
sealed class GraphMaterializationInputLocation {
    /** Ordered graph root occurrence. */
    data class Root(val ordinal: Long) : GraphMaterializationInputLocation()

    /** One graph node identity. */
    data class Node(val node: NodeId) : GraphMaterializationInputLocation()

    /** One ordered sequence edge. */
    data class SequenceElement(
        /** Parent sequence node. */
        val parent: NodeId,
        /** Direct element ordinal. */
        val ordinal: Long,
    ) : GraphMaterializationInputLocation()

    /** One ordered mapping-key edge. */
    data class MappingKey(
        /** Parent mapping node. */
        val parent: NodeId,
        /** Direct association ordinal. */
        val ordinal: Long,
    ) : GraphMaterializationInputLocation()

    /** One ordered mapping-value edge. */
    data class MappingValue(
        /** Parent mapping node. */
        val parent: NodeId,
        /** Direct association ordinal. */
        val ordinal: Long,
    ) : GraphMaterializationInputLocation()
}

/** One graph-input location mapped to one or more generated YAML origins
 * (materialization.rs:62-69). */
data class GraphMaterializationProvenanceEntry(
    /** Exact input location. */
    val input: GraphMaterializationInputLocation,
    /** One or more exact output origins. */
    val outputs: List<MaterializedOrigin>,
)

/** Complete deterministic graph-to-YAML provenance multimap
 * (materialization.rs:71-83). */
class GraphMaterializationProvenanceMap internal constructor(
    internal val entriesList: List<GraphMaterializationProvenanceEntry>,
) {
    /** Entries in root, canonical-node, and association traversal order. */
    fun entries(): List<GraphMaterializationProvenanceEntry> = entriesList

    override fun equals(other: Any?): Boolean =
        other is GraphMaterializationProvenanceMap && entriesList == other.entriesList

    override fun hashCode(): Int = entriesList.hashCode()
}

/** Stable PortableGraph-to-YAML materialization failure
 * (materialization.rs:85-117). */
sealed class GraphMaterializationFailure {
    /** A common request, formation, or resource contract failed. */
    data class Materialization(val cause: MaterializationException) : GraphMaterializationFailure()

    /** A custom tag has no published YAML constructor contract. */
    data class UnsupportedTag(val node: NodeId, val tag: String) : GraphMaterializationFailure()

    /** A standard repository tag was attached to the wrong graph-node kind. */
    data class TagKindMismatch(val node: NodeId, val tag: String) : GraphMaterializationFailure()

    /** YAML document-scoped anchors cannot preserve sharing across graph
     * roots. */
    data class CrossDocumentSharing(val node: NodeId) : GraphMaterializationFailure()

    /** Reparse did not reproduce the complete input graph exactly. */
    data object RoundTripMismatch : GraphMaterializationFailure()
}

/** Stable semantic-model v5 diagnostic code for graph-to-YAML
 * materialization (materialization.rs:143-152). */
fun graphMaterializationCode(failure: GraphMaterializationFailure): String =
    when (failure) {
        is GraphMaterializationFailure.Materialization -> failure.cause.code
        is GraphMaterializationFailure.UnsupportedTag -> "yaml.materialization.unsupported-tag@1"
        is GraphMaterializationFailure.TagKindMismatch -> "yaml.materialization.tag-kind-mismatch@1"
        is GraphMaterializationFailure.CrossDocumentSharing -> "yaml.materialization.cross-document-sharing@1"
        is GraphMaterializationFailure.RoundTripMismatch -> "yaml.materialization.round-trip-mismatch@1"
    }

/** The typed graph materialization failure. */
class GraphMaterializationException(val failure: GraphMaterializationFailure) :
    Exception("yaml graph materialization: ${graphMaterializationCode(failure)}")

/** Failed graph attempt without a Document or partial output bytes
 * (materialization.rs:160-167). */
data class FailedGraphMaterializationAttempt(
    /** Stable failure. */
    val failure: GraphMaterializationFailure,
    /** Canonical input nodes analyzed before failure. */
    val analyzedInputNodes: List<NodeId>,
)

/** Complete exact PortableGraph-to-YAML materialization
 * (materialization.rs:169-180). */
data class CompleteGraphMaterialization(
    /** Newly formed immutable YAML stream. */
    val document: Document,
    /** Always Exact for the published graph contract. */
    val fidelity: MaterializationFidelity,
    /** Complete structured report. */
    val report: MaterializationReport,
    /** Complete graph-input-to-YAML provenance. */
    val provenance: GraphMaterializationProvenanceMap,
)

/** Closed graph materialization completion algebra (materialization.rs:
 * 182-189). */
sealed class GraphMaterializationResult {
    /** Complete success with every required artifact. */
    data class Complete(val materialization: CompleteGraphMaterialization) : GraphMaterializationResult()

    /** Atomic failure without a candidate document. */
    data class Failed(val attempt: FailedGraphMaterializationAttempt) : GraphMaterializationResult()
}

/** Materializes one complete PortableGraph as a canonical YAML stream
 * (materialization.rs:191-205). */
fun materializeGraph(
    graph: Graph,
    request: MaterializationRequest,
): GraphMaterializationResult {
    val analyzed = ArrayList<NodeId>()
    return try {
        GraphMaterializationResult.Complete(materializeGraphComplete(graph, request, analyzed))
    } catch (e: GraphMaterializationException) {
        GraphMaterializationResult.Failed(
            FailedGraphMaterializationAttempt(e.failure, analyzed),
        )
    }
}

private fun materializeGraphComplete(
    graph: Graph,
    request: MaterializationRequest,
    analyzed: MutableList<NodeId>,
): CompleteGraphMaterialization {
    val profile = requestedProfile(request)
    val style = requestedStyle(request)
    requestedOutputContract(request)
    val layout = GraphLayout.analyze(graph, request.limits)
    val writer = GraphWriter(graph, layout, style, request, analyzed)
    writer.stream()
    val raw = encodeOutput(
        writer.output.toString(),
        request.encoding,
        request.limits.maxOutputBytes,
    )
    val document = try {
        parse(raw, profile, parseLimits(request.limits))
    } catch (e: YamlFormationException) {
        throw GraphMaterializationException(
            GraphMaterializationFailure.Materialization(
                MaterializationException(
                    MaterializationFailureKind.FORMATION_FAILED,
                    "yaml: generated bytes did not form a document",
                ),
            ),
        )
    }
    val reparsed = try {
        document.projectGraph()
    } catch (e: GraphProjectionException) {
        throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
    }
    if (!equal(reparsed, graph)) {
        throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
    }
    val provenance = collectGraphProvenance(graph, document, request.limits)
    return CompleteGraphMaterialization(
        document = document,
        fidelity = MaterializationFidelity.Exact,
        report = MaterializationReport.new(emptyList(), request.limits),
        provenance = provenance,
    )
}

private enum class YamlStyle { Block, Flow }

private fun requestedProfile(request: MaterializationRequest): YamlProfile =
    when {
        request.targetProfile == ProfileId("yaml.1.2-core", 1) -> YamlProfile.Yaml12CoreV1
        request.targetProfile == ProfileId("yaml.1.1-compat", 1) -> YamlProfile.Yaml11CompatV1
        else -> throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_PROFILE)
    }

private fun requestedStyle(request: MaterializationRequest): YamlStyle =
    when {
        request.style == MaterializationStyleId("yaml.canonical-block", 1) -> YamlStyle.Block
        request.style == MaterializationStyleId("yaml.canonical-flow", 1) -> YamlStyle.Flow
        else -> throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_STYLE)
    }

private fun requestedOutputContract(request: MaterializationRequest) {
    if (request.encoding != SourceEncoding.Utf8 &&
        request.encoding != SourceEncoding.Utf16Le &&
        request.encoding != SourceEncoding.Utf16Be
    ) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
    }
    if (request.newline == NewlinePolicy.None) {
        throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_NEWLINE)
    }
}

private fun parseLimits(limits: MaterializationLimits): ParseLimits = ParseLimits(
    maxSourceBytes = limits.maxOutputBytes,
    maxNestingDepth = limits.maxDepth,
    maxTokenCount = limits.maxOutputBytes,
    maxNodeCount = limits.maxInputNodes.saturatingMul(4),
    maxDiagnostics = limits.maxReportEntries,
)

/** The canonical graph layout: which nodes need anchors and their
 * deterministic `g{index}` names (materialization.rs:292-401). */
private class GraphLayout(val anchorNames: Map<NodeId, Int>) {
    companion object {
        fun analyze(graph: Graph, limits: MaterializationLimits): GraphLayout {
            val canonical = ArrayList<NodeId>(graph.nodeCount())
            val canonicalIds = HashMap<NodeId, Int>()
            val stack = ArrayDeque<Pair<NodeId, Int>>()
            for (root in graph.roots().asReversed()) {
                stack.addLast(root to 0)
            }
            while (stack.isNotEmpty()) {
                val (id, depth) = stack.removeLast()
                if (canonicalIds.containsKey(id)) {
                    continue
                }
                if (depth > limits.maxDepth) {
                    throw graphResourceLimit("input-depth")
                }
                if (canonical.size >= limits.maxInputNodes) {
                    throw graphResourceLimit("input-nodes")
                }
                val node = graph.node(id)
                    ?: throw MaterializationException(
                        MaterializationFailureKind.INVALID_REQUEST,
                        reason = "foreign graph node",
                    )
                validateTagKind(id, node.tag, node.kind)
                canonicalIds[id] = canonical.size
                canonical.add(id)
                val childDepth = depth + 1
                when (node.kind) {
                    consema.graph.NodeKind.Scalar -> {}
                    consema.graph.NodeKind.Sequence -> {
                        val items = node.sequenceItems()!!
                        for (item in items.asReversed()) {
                            stack.addLast(item to childDepth)
                        }
                    }
                    consema.graph.NodeKind.Mapping -> {
                        val entries = node.mappingEntries()!!
                        for (entry in entries.asReversed()) {
                            stack.addLast(entry.value to childDepth)
                            stack.addLast(entry.key to childDepth)
                        }
                    }
                }
            }

            val documentOwner = HashMap<NodeId, Int>()
            val occurrences = HashMap<NodeId, Int>()
            for ((rootOrdinal, root) in graph.roots().withIndex()) {
                val seen = HashSet<NodeId>()
                val pending = ArrayDeque<NodeId>()
                pending.addLast(root)
                occurrences[root] = (occurrences[root] ?: 0) + 1
                while (pending.isNotEmpty()) {
                    val id = pending.removeLast()
                    if (!seen.add(id)) {
                        continue
                    }
                    if (documentOwner.put(id, rootOrdinal) != null) {
                        throw GraphMaterializationException(
                            GraphMaterializationFailure.CrossDocumentSharing(id),
                        )
                    }
                    val node = graph.node(id)!!
                    when (node.kind) {
                        consema.graph.NodeKind.Scalar -> {}
                        consema.graph.NodeKind.Sequence -> {
                            for (child in node.sequenceItems()!!) {
                                occurrences[child] = (occurrences[child] ?: 0) + 1
                                pending.addLast(child)
                            }
                        }
                        consema.graph.NodeKind.Mapping -> {
                            for (entry in node.mappingEntries()!!) {
                                for (child in listOf(entry.key, entry.value)) {
                                    occurrences[child] = (occurrences[child] ?: 0) + 1
                                    pending.addLast(child)
                                }
                            }
                        }
                    }
                }
            }
            val anchorNames = HashMap<NodeId, Int>()
            var anchor = 0
            for (id in canonical) {
                if ((occurrences[id] ?: 0) > 1) {
                    anchorNames[id] = anchor
                    anchor++
                }
            }
            return GraphLayout(anchorNames)
        }
    }
}

private fun validateTagKind(node: NodeId, tag: String, kind: consema.graph.NodeKind) {
    val compatible = when (tag) {
        TAG_NULL, TAG_BOOL, TAG_INT, TAG_FLOAT, TAG_STR, TAG_TIMESTAMP, TAG_BINARY,
        TAG_MERGE, TAG_VALUE, TAG_YAML -> kind == consema.graph.NodeKind.Scalar
        TAG_SEQ, TAG_OMAP, TAG_PAIRS -> kind == consema.graph.NodeKind.Sequence
        TAG_MAP, TAG_SET -> kind == consema.graph.NodeKind.Mapping
        else -> {
            throw GraphMaterializationException(
                GraphMaterializationFailure.UnsupportedTag(node, tag),
            )
        }
    }
    if (!compatible) {
        throw GraphMaterializationException(
            GraphMaterializationFailure.TagKindMismatch(node, tag),
        )
    }
}

private fun graphResourceLimit(name: String): GraphMaterializationException =
    GraphMaterializationException(
        GraphMaterializationFailure.Materialization(
            MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                "yaml materialization: $name limit reached",
                name = name,
            ),
        ),
    )

/** The canonical graph writer (materialization.rs:430-717). */
private class GraphWriter(
    private val graph: Graph,
    private val layout: GraphLayout,
    private val style: YamlStyle,
    private val request: MaterializationRequest,
    private val analyzed: MutableList<NodeId>,
) {
    val output = StringBuilder()
    private val newline: String = when (request.newline) {
        NewlinePolicy.Lf -> "\n"
        NewlinePolicy.CrLf -> "\r\n"
        NewlinePolicy.None -> error("validated request")
    }
    private val emitted = HashSet<NodeId>()

    fun stream() {
        for ((ordinal, root) in graph.roots().withIndex()) {
            if (ordinal != 0) {
                pushStr(newline)
            }
            emitted.clear()
            pushStr("---")
            when (style) {
                YamlStyle.Block -> blockAfterIndicator(root, 0, 0)
                YamlStyle.Flow -> {
                    pushChar(' ')
                    flowNode(root, 0)
                }
            }
            pushStr(newline)
        }
    }

    private fun flowNode(id: NodeId, depth: Int) {
        if (writeAliasIfEmitted(id)) {
            return
        }
        beginDefinition(id, depth)
        writeProperties(id)
        val node = graph.node(id)!!
        when (node.kind) {
            consema.graph.NodeKind.Scalar -> {
                pushChar(' ')
                writeQuoted(scalarPresentation(node.tag, node.scalarContent()!!))
            }
            consema.graph.NodeKind.Sequence -> {
                pushStr(" [")
                for ((index, child) in node.sequenceItems()!!.withIndex()) {
                    if (index != 0) {
                        pushStr(", ")
                    }
                    flowNode(child, depth + 1)
                }
                pushChar(']')
            }
            consema.graph.NodeKind.Mapping -> {
                pushStr(" {")
                for ((index, entry) in node.mappingEntries()!!.withIndex()) {
                    if (index != 0) {
                        pushStr(", ")
                    }
                    pushStr("? ")
                    flowNode(entry.key, depth + 1)
                    pushStr(" : ")
                    flowNode(entry.value, depth + 1)
                }
                pushChar('}')
            }
        }
    }

    private fun blockAfterIndicator(id: NodeId, childIndent: Int, depth: Int) {
        if (emitted.contains(id)) {
            pushChar(' ')
            writeAlias(id)
            return
        }
        val node = graph.node(id)!!
        val block = when (node.kind) {
            consema.graph.NodeKind.Scalar -> false
            consema.graph.NodeKind.Sequence -> node.sequenceItems()!!.isNotEmpty()
            consema.graph.NodeKind.Mapping -> node.mappingEntries()!!.isNotEmpty()
        }
        beginDefinition(id, depth)
        pushChar(' ')
        writeProperties(id)
        if (block) {
            pushStr(newline)
            blockContent(id, childIndent, depth)
        } else {
            when (node.kind) {
                consema.graph.NodeKind.Scalar -> {
                    pushChar(' ')
                    writeQuoted(scalarPresentation(node.tag, node.scalarContent()!!))
                }
                consema.graph.NodeKind.Sequence -> pushStr(" []")
                consema.graph.NodeKind.Mapping -> pushStr(" {}")
            }
        }
    }

    private fun blockContent(id: NodeId, indent: Int, depth: Int) {
        val node = graph.node(id)!!
        when (node.kind) {
            consema.graph.NodeKind.Scalar ->
                throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
            consema.graph.NodeKind.Sequence -> {
                val items = node.sequenceItems()!!
                for ((index, child) in items.withIndex()) {
                    if (index != 0) {
                        pushStr(newline)
                    }
                    pushIndent(indent)
                    pushChar('-')
                    blockAfterIndicator(child, indent + 2, depth + 1)
                }
            }
            consema.graph.NodeKind.Mapping -> {
                val entries = node.mappingEntries()!!
                for ((index, entry) in entries.withIndex()) {
                    if (index != 0) {
                        pushStr(newline)
                    }
                    pushIndent(indent)
                    pushChar('?')
                    blockAfterIndicator(entry.key, indent + 2, depth + 1)
                    pushStr(newline)
                    pushIndent(indent)
                    pushChar(':')
                    blockAfterIndicator(entry.value, indent + 2, depth + 1)
                }
            }
        }
    }

    private fun beginDefinition(id: NodeId, depth: Int) {
        if (depth > request.limits.maxDepth) {
            throw graphResourceLimit("input-depth")
        }
        if (!emitted.add(id)) {
            throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
        }
        if (analyzed.size >= request.limits.maxInputNodes) {
            throw graphResourceLimit("input-nodes")
        }
        analyzed.add(id)
    }

    private fun writeAliasIfEmitted(id: NodeId): Boolean {
        if (emitted.contains(id)) {
            writeAlias(id)
            return true
        }
        return false
    }

    private fun writeAlias(id: NodeId) {
        val anchor = layout.anchorNames[id]
            ?: throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
        pushStr("*g$anchor")
    }

    private fun writeProperties(id: NodeId) {
        layout.anchorNames[id]?.let { pushStr("&g$it ") }
        val tag = graph.node(id)!!.tag
        val suffix = tag.removePrefix("tag:yaml.org,2002:")
            ?: throw GraphMaterializationException(
                GraphMaterializationFailure.UnsupportedTag(id, tag),
            )
        pushStr("!!$suffix")
    }

    /** The canonical double-quoted scalar spelling (materialization.rs:
     * 689-709). */
    private fun writeQuoted(value: String) {
        pushChar('"')
        for (character in value) {
            when (character.code) {
                0x22 -> pushStr("\\\"")
                0x5c -> pushStr("\\\\")
                0x08 -> pushStr("\\b")
                0x09 -> pushStr("\\t")
                0x0a -> pushStr("\\n")
                0x0c -> pushStr("\\f")
                0x0d -> pushStr("\\r")
                in 0x00..0x1f, 0x7f ->
                    pushStr("\\u%04x".format(java.util.Locale.ROOT, character.code))
                else -> pushChar(character)
            }
        }
        pushChar('"')
    }

    private fun pushIndent(spaces: Int) {
        repeat(spaces) { pushChar(' ') }
    }

    private fun pushStr(value: String) {
        if (output.length > request.limits.maxOutputBytes - value.length) {
            throw graphResourceLimit("output-bytes")
        }
        output.append(value)
    }

    private fun pushChar(value: Char) {
        if (output.length >= request.limits.maxOutputBytes) {
            throw graphResourceLimit("output-bytes")
        }
        output.append(value)
    }
}

/** The canonical scalar presentation: floats that would lose their decimal
 * nature get an explicit `e0` (materialization.rs:719-728). */
internal fun scalarPresentation(tag: String, canonical: String): String =
    if (tag == TAG_FLOAT &&
        canonical != ".inf" && canonical != "-.inf" && canonical != ".nan" &&
        !canonical.contains('.') && !canonical.contains('e') && !canonical.contains('E')
    ) {
        "${canonical}e0"
    } else {
        canonical
    }

/** Encodes the generated text under the selected encoding with the matching
 * BOM for UTF-16 (materialization.rs:775-819). */
internal fun encodeOutput(text: String, encoding: SourceEncoding, max: Int): ByteArray =
    when (encoding) {
        SourceEncoding.Utf8 -> {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > max) {
                throw graphResourceLimit("output-bytes")
            }
            bytes
        }
        SourceEncoding.Utf16Le, SourceEncoding.Utf16Be -> {
            val units = text.length
            val length = units.saturatingMul(2).saturatingAdd(2)
            if (length > max) {
                throw graphResourceLimit("output-bytes")
            }
            val output = ArrayList<Byte>(length)
            if (encoding == SourceEncoding.Utf16Le) {
                output.add(0xff.toByte())
                output.add(0xfe.toByte())
            } else {
                output.add(0xfe.toByte())
                output.add(0xff.toByte())
            }
            for (index in text.indices) {
                val unit = text[index].code
                if (encoding == SourceEncoding.Utf16Le) {
                    output.add((unit and 0xff).toByte())
                    output.add((unit shr 8).toByte())
                } else {
                    output.add((unit shr 8).toByte())
                    output.add((unit and 0xff).toByte())
                }
            }
            output.toByteArray()
        }
        else -> throw MaterializationException(MaterializationFailureKind.UNSUPPORTED_ENCODING)
    }

private fun collectGraphProvenance(
    graph: Graph,
    document: Document,
    limits: MaterializationLimits,
): GraphMaterializationProvenanceMap {
    if (graph.roots().size != document.documentCount()) {
        throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
    }
    val builder = GraphProvenanceBuilder(document, limits)
    for ((index, inputRoot) in graph.roots().withIndex()) {
        val outputDocument = document.document(index)
            ?: throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
        builder.push(
            GraphMaterializationInputLocation.Root(index.toLong()),
            MaterializedOrigin(
                snapshot = document.snapshotIdentity,
                node = outputDocument.nodeRef(),
                span = outputDocument.span(),
                relation = MaterializationRelation.Generated,
            ),
        )
        builder.collectNode(graph, inputRoot, outputDocument.root())
    }
    return GraphMaterializationProvenanceMap(builder.entries)
}

/** Builds the graph-to-YAML provenance multimap (materialization.rs:
 * 860-1056). */
private class GraphProvenanceBuilder(
    private val document: Document,
    private val limits: MaterializationLimits,
) {
    private var units = 0
    private val entriesList = ArrayList<GraphMaterializationProvenanceEntry>()
    private val seen = HashSet<NodeId>()
    private val index = HashMap<GraphMaterializationInputLocation, Int>()

    val entries: List<GraphMaterializationProvenanceEntry>
        get() = entriesList

    fun collectNode(graph: Graph, input: NodeId, output: YamlNode) {
        if (!seen.add(input)) {
            return
        }
        val node = graph.node(input)
            ?: throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
        val expectedKind = when (node.kind) {
            consema.graph.NodeKind.Scalar -> YamlNodeKind.Scalar
            consema.graph.NodeKind.Sequence -> YamlNodeKind.Sequence
            consema.graph.NodeKind.Mapping -> YamlNodeKind.Mapping
        }
        if (output.kind() != expectedKind || output.tag() != node.tag) {
            throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
        }
        if (node.kind == consema.graph.NodeKind.Scalar &&
            output.scalar()?.canonical() != node.scalarContent()
        ) {
            throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
        }
        push(
            GraphMaterializationInputLocation.Node(input),
            origin(output.nodeRef(), output.span(), MaterializationRelation.Direct),
        )
        when (node.kind) {
            consema.graph.NodeKind.Scalar -> {}
            consema.graph.NodeKind.Sequence -> {
                val children = node.sequenceItems()!!
                if (output.sequenceLen() != children.size) {
                    throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
                }
                for ((ordinal, child) in children.withIndex()) {
                    val edge = output.sequenceItem(ordinal)
                        ?: throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
                    val location = GraphMaterializationInputLocation.SequenceElement(
                        parent = input,
                        ordinal = ordinal.toLong(),
                    )
                    push(
                        location,
                        origin(edge.nodeRef(), edge.span(), MaterializationRelation.Direct),
                    )
                    edge.alias()?.let {
                        add(
                            location,
                            origin(it.nodeRef(), it.span(), MaterializationRelation.Reencoded),
                        )
                    }
                    collectNode(graph, child, edge.node())
                }
            }
            consema.graph.NodeKind.Mapping -> {
                val entries = node.mappingEntries()!!
                if (output.mappingLen() != entries.size) {
                    throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
                }
                for ((ordinal, entry) in entries.withIndex()) {
                    val outputEntry = output.mappingEntry(ordinal)
                        ?: throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
                    val ordinalLong = ordinal.toLong()
                    for ((location, alias) in listOf(
                        GraphMaterializationInputLocation.MappingKey(
                            parent = input,
                            ordinal = ordinalLong,
                        ) to outputEntry.keyAlias(),
                        GraphMaterializationInputLocation.MappingValue(
                            parent = input,
                            ordinal = ordinalLong,
                        ) to outputEntry.valueAlias(),
                    )) {
                        push(
                            location,
                            origin(
                                outputEntry.nodeRef(),
                                outputEntry.span(),
                                MaterializationRelation.Direct,
                            ),
                        )
                        alias?.let {
                            add(
                                location,
                                origin(it.nodeRef(), it.span(), MaterializationRelation.Reencoded),
                            )
                        }
                    }
                    collectNode(graph, entry.key, outputEntry.key())
                    collectNode(graph, entry.value, outputEntry.value())
                }
            }
        }
    }

    private fun origin(
        node: consema.document.NodeRef,
        span: consema.document.Span,
        relation: MaterializationRelation,
    ): MaterializedOrigin =
        MaterializedOrigin(document.snapshotIdentity, node, span, relation)

    internal fun push(input: GraphMaterializationInputLocation, output: MaterializedOrigin) {
        units = units + 2
        if (units > limits.maxProvenanceEntries) {
            throw graphResourceLimit("provenance-entries")
        }
        val position = entriesList.size
        entriesList.add(
            GraphMaterializationProvenanceEntry(input, mutableListOf(output)),
        )
        index[input] = position
    }

    private fun add(input: GraphMaterializationInputLocation, output: MaterializedOrigin) {
        units++
        if (units > limits.maxProvenanceEntries) {
            throw graphResourceLimit("provenance-entries")
        }
        val position = index[input]
            ?: throw GraphMaterializationException(GraphMaterializationFailure.RoundTripMismatch)
        val entry = entriesList[position]
        entriesList[position] = entry.copy(outputs = entry.outputs + output)
    }
}

/** Materializes one complete PortableValue into a canonical YAML document
 * (materialization.rs:1058-1078). Exact local Object/EntryMapping
 * reconstruction is verified through the frozen best-exact YAML
 * projection. */
fun materializeValue(
    value: PortableValue,
    request: MaterializationRequest,
): MaterializationResult<Document> {
    val attempt = ValueAttempt()
    return try {
        MaterializationResult.Complete(materializeValueComplete(value, request, attempt))
    } catch (e: MaterializationException) {
        val report = try {
            MaterializationReport.new(attempt.events, request.limits)
        } catch (limitFailure: MaterializationException) {
            // The failed attempt's report degrades to empty when the
            // report limit itself was the failure (the Rust
            // unwrap_or_default, materialization.rs:1071-1075).
            MaterializationReport.new(emptyList(), request.limits)
        }
        MaterializationResult.Failed(
            FailedMaterializationAttempt(
                failure = e,
                report = report,
                analyzedInputPaths = attempt.analyzed,
            ),
        )
    }
}

private class ValueAttempt {
    val analyzed = ArrayList<ValuePath>()
    val events = ArrayList<Diagnostic>()
    var inputNodes = 0
}

private fun materializeValueComplete(
    value: PortableValue,
    request: MaterializationRequest,
    attempt: ValueAttempt,
): CompleteMaterialization<Document> {
    requestedProfile(request)
    requestedStyle(request)
    requestedOutputContract(request)
    val prepared = prepareValue(value, ValuePath.root(), 0, request, attempt)
    val graph = valueGraph(prepared, request.limits)
    val graphLimits = request.limits.copy(
        maxInputNodes = request.limits.maxInputNodes.saturatingMul(2).saturatingAdd(1),
    )
    val graphRequest = request.withLimits(graphLimits)
    val graphAnalyzed = ArrayList<NodeId>()
    val graphComplete = try {
        materializeGraphComplete(graph, graphRequest, graphAnalyzed)
    } catch (e: GraphMaterializationException) {
        throw when (val failure = e.failure) {
            is GraphMaterializationFailure.Materialization -> failure.cause
            else -> MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        }
    }
    val document = graphComplete.document
    val projected = when (val result = document.projectValue(ValueProjectionRequest.bestExactV1())) {
        is ValueProjectionResult.Complete -> result.projection
        is ValueProjectionResult.Failed ->
            throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    if (projected.fidelity != Fidelity.Exact ||
        !consema.core.equal(projected.value, prepared)
    ) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    val builder = ValueProvenanceBuilder(document, request)
    builder.collect(value, ValuePath.root(), document.document(0)!!.root())
    val provenance = MaterializationProvenanceMap.new(
        builder.entries,
        document.snapshotIdentity,
        request.limits,
    )
    val report = MaterializationReport.new(attempt.events, request.limits)
    return CompleteMaterialization(
        document = document,
        fidelity = if (report.events().isEmpty()) {
            MaterializationFidelity.Exact
        } else {
            MaterializationFidelity.Transformed
        },
        report = report,
        provenance = provenance,
    )
}

/** Prepares the portable value for exact YAML representation (materialization.rs:1146-1246). */
private fun prepareValue(
    value: PortableValue,
    path: ValuePath,
    depth: Int,
    request: MaterializationRequest,
    attempt: ValueAttempt,
): PortableValue {
    if (depth > request.limits.maxDepth) {
        throw MaterializationException(
            MaterializationFailureKind.RESOURCE_LIMIT,
            "yaml materialization: input-depth limit reached",
            name = "input-depth",
        )
    }
    attempt.inputNodes++
    if (attempt.inputNodes > request.limits.maxInputNodes) {
        throw MaterializationException(
            MaterializationFailureKind.RESOURCE_LIMIT,
            "yaml materialization: input-nodes limit reached",
            name = "input-nodes",
        )
    }
    attempt.analyzed.add(path)
    val childDepth = depth + 1
    return when (value.kind) {
        Kind.Null, Kind.Boolean, Kind.Integer, Kind.Decimal, Kind.String, Kind.Bytes -> value
        Kind.BinaryFloat64 -> {
            val bits = (value as PvBinaryFloat64).bits
            if (bits == 0x7ff0_0000_0000_0000L || bits == -0x10_0000_0000_0000L ||
                bits == 0x7ff8_0000_0000_0000L
            ) {
                value
            } else {
                throw unrepresentable(path, value.kind)
            }
        }
        Kind.Date -> {
            val date = value as PvDate
            if (canonicalDate(date) == null) {
                throw unrepresentable(path, value.kind)
            }
            value
        }
        Kind.OffsetDateTime -> {
            val timestamp = value as PvOffsetDateTime
            if (canonicalOffsetDateTime(timestamp, request.limits.maxOutputBytes) == null) {
                throw unrepresentable(path, value.kind)
            }
            value
        }
        Kind.Sequence -> {
            val items = (value as PvArray).items()
            val output = ArrayList<PortableValue>(items.size)
            for ((index, child) in items.withIndex()) {
                output.add(
                    prepareValue(
                        child,
                        path.child(ValuePathSegment.SequenceElement(index.toLong())),
                        childDepth,
                        request,
                        attempt,
                    ),
                )
            }
            PvArray(output)
        }
        Kind.Object -> {
            val entries = (value as PvObject).entries()
            val output = consema.core.ObjectBuilder()
            for (entry in entries) {
                output.insert(
                    entry.key,
                    prepareValue(
                        entry.value,
                        path.child(ValuePathSegment.ObjectValue(entry.key)),
                        childDepth,
                        request,
                        attempt,
                    ),
                )
            }
            output.build()
        }
        Kind.EntryMapping -> prepareMapping(
            (value as PvEntryMapping).entries(),
            path,
            childDepth,
            request,
            attempt,
        )
        else -> throw unrepresentable(path, value.kind)
    }
}

private fun unrepresentable(path: ValuePath, kind: Kind): MaterializationException =
    MaterializationException(
        MaterializationFailureKind.UNREPRESENTABLE,
        "yaml materialization: $kind is not representable",
        path = path,
        valueKind = kind,
    )

/** EntryMapping preparation with the explicit UniqueStringEntriesToObject
 * conversion (materialization.rs:1248-1335). */
private fun prepareMapping(
    entries: List<consema.core.EntryMappingEntry>,
    path: ValuePath,
    childDepth: Int,
    request: MaterializationRequest,
    attempt: ValueAttempt,
): PortableValue {
    val names = HashSet<String>()
    val objectEligible = entries.all { entry ->
        val name = (entry.key as? PvString)?.value ?: return@all false
        names.add(name)
    }
    if (objectEligible) {
        if (request.mappingPolicy != MappingPolicy.UniqueStringEntriesToObject) {
            throw unrepresentable(path, Kind.EntryMapping)
        }
        val observed = attempt.events.size + 1
        if (observed > request.limits.maxReportEntries) {
            throw MaterializationException(
                MaterializationFailureKind.RESOURCE_LIMIT,
                "yaml materialization: report-entries limit reached",
                name = "report-entries",
            )
        }
        attempt.events.add(
            Diagnostic.of(
                "core.materialization.mapping-transformed@1",
                DiagnosticCategory.Materialization,
                Severity.Info,
                null,
                emptyList(),
                mapOf(
                    "from" to "EntryMapping",
                    "policy" to "UniqueStringEntriesToObject",
                    "to" to "Object",
                    "path" to path.toString(),
                ),
                emptyList(),
                emptyList(),
                attempt.events.size.toULong(),
                YAML_DIAGNOSTIC_REGISTRY,
            ),
        )
    }
    val prepared = ArrayList<Pair<PortableValue, PortableValue>>(entries.size)
    for ((index, entry) in entries.withIndex()) {
        val key = prepareValue(
            entry.key,
            path.child(ValuePathSegment.EntryKey(index.toLong())),
            childDepth,
            request,
            attempt,
        )
        val value = prepareValue(
            entry.value,
            path.child(ValuePathSegment.EntryValue(index.toLong())),
            childDepth,
            request,
            attempt,
        )
        prepared.add(key to value)
    }
    if (objectEligible) {
        val output = consema.core.ObjectBuilder()
        for ((key, value) in prepared) {
            output.insert((key as PvString).value, value)
        }
        return output.build()
    }
    val output = ArrayList<consema.core.EntryMappingEntry>(prepared.size)
    for ((key, value) in prepared) {
        output.add(consema.core.EntryMappingEntry(key, value))
    }
    return PvEntryMapping(output)
}

/** Converts one prepared PortableValue to a single-root graph
 * (materialization.rs:1337-1503). */
private fun valueGraph(value: PortableValue, limits: MaterializationLimits): Graph {
    val maxNodes = limits.maxInputNodes.saturatingMul(2).saturatingAdd(1)
    val builder = Builder.withLimits(
        GraphLimits(
            maxRoots = 1,
            maxNodes = maxNodes,
            maxEdges = maxNodes.saturatingMul(2),
            maxContainerEntries = limits.maxInputNodes,
            maxTagBytes = 64,
            maxScalarBytes = limits.maxOutputBytes,
            maxTraversalDepth = limits.maxDepth,
        ),
    )
    val root = defineValueNode(builder, value, limits.maxOutputBytes)
    builder.pushRoot(root)
    return builder.build()
}

private fun defineValueNode(
    builder: Builder,
    value: PortableValue,
    maxOutputBytes: Int,
): NodeId {
    val id = builder.reserveNode()
    when (value.kind) {
        Kind.Null -> builder.defineScalar(id, TAG_NULL, "")
        Kind.Boolean -> builder.defineScalar(
            id,
            TAG_BOOL,
            if ((value as PvBoolean).value) "true" else "false",
        )
        Kind.Integer -> builder.defineScalar(id, TAG_INT, (value as PvInteger).value.toString())
        Kind.Decimal -> {
            val decimal = value as PvDecimal
            val canonical = decimalCanonical(decimal)
            builder.defineScalar(id, TAG_FLOAT, canonical)
        }
        Kind.BinaryFloat64 -> {
            val canonical = when ((value as PvBinaryFloat64).bits) {
                0x7ff0_0000_0000_0000L -> ".inf"
                -0x10_0000_0000_0000L -> "-.inf"
                0x7ff8_0000_0000_0000L -> ".nan"
                else -> throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
            }
            builder.defineScalar(id, TAG_FLOAT, canonical)
        }
        Kind.String -> builder.defineScalar(id, TAG_STR, (value as PvString).value)
        Kind.Bytes -> builder.defineScalar(
            id,
            TAG_BINARY,
            encodeBase64((value as PvBytes).content(), maxOutputBytes),
        )
        Kind.Date -> {
            val canonical = canonicalDate(value as PvDate)
                ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
            builder.defineScalar(id, TAG_TIMESTAMP, canonical)
        }
        Kind.OffsetDateTime -> {
            val canonical = canonicalOffsetDateTime(value as PvOffsetDateTime, maxOutputBytes)
                ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
            builder.defineScalar(id, TAG_TIMESTAMP, canonical)
        }
        Kind.Sequence -> {
            val children = (value as PvArray).items().map { defineValueNode(builder, it, maxOutputBytes) }
            builder.defineSequence(id, TAG_SEQ, children)
        }
        Kind.Object -> {
            val entries = ArrayList<MappingEntry>()
            for (entry in (value as PvObject).entries()) {
                val key = builder.reserveNode()
                builder.defineScalar(key, TAG_STR, entry.key)
                val child = defineValueNode(builder, entry.value, maxOutputBytes)
                entries.add(MappingEntry(key, child))
            }
            builder.defineMapping(id, TAG_MAP, entries)
        }
        Kind.EntryMapping -> {
            val entries = ArrayList<MappingEntry>()
            for (entry in (value as PvEntryMapping).entries()) {
                val key = defineValueNode(builder, entry.key, maxOutputBytes)
                val child = defineValueNode(builder, entry.value, maxOutputBytes)
                entries.add(MappingEntry(key, child))
            }
            builder.defineMapping(id, TAG_MAP, entries)
        }
        Kind.BinaryFloat32, Kind.Time, Kind.LocalDateTime ->
            throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    return id
}

/** The canonical `YYYY-MM-DD` spelling, or null outside 0..=9999
 * (materialization.rs:1505-1510). */
internal fun canonicalDate(value: PvDate): String? {
    if (value.year.signum() < 0 || value.year > BIGINT_9999) {
        return null
    }
    val year = value.year.toInt()
    return "%04d-%02d-%02d".format(java.util.Locale.ROOT, year, value.month, value.day)
}

private val BIGINT_9999: BigInteger = BigInteger.valueOf(9999)

/** The canonical timestamp spelling with `Z` for zero and `±HH:MM`
 * otherwise (materialization.rs:1512-1543). */
internal fun canonicalOffsetDateTime(
    value: PvOffsetDateTime,
    maxOutputBytes: Int,
): String? {
    val date = canonicalDate(value.local.date) ?: return null
    val time = value.local.time
    val fraction = canonicalFraction(time.fractionalSecond, maxOutputBytes) ?: return null
    val seconds = value.offsetSeconds
    if (seconds % 60 != 0) {
        return null
    }
    val zone = if (seconds == 0) {
        "Z"
    } else {
        val sign = if (seconds < 0) '-' else '+'
        val absolute = if (seconds < 0) -seconds else seconds
        "%c%02d:%02d".format(java.util.Locale.ROOT, sign, absolute / 3600, (absolute % 3600) / 60)
    }
    val output = "%sT%02d:%02d:%02d%s%s".format(
        java.util.Locale.ROOT,
        date, time.hour, time.minute, time.second, fraction, zone,
    )
    if (output.length > maxOutputBytes) {
        throw graphResourceLimit("output-bytes")
    }
    return output
}

/** The minimal exact fractional-second spelling (materialization.rs:
 * 1545-1572). */
internal fun canonicalFraction(value: PvDecimal, max: Int): String? {
    if (value.coefficient.signum() == 0) {
        return ""
    }
    if (value.coefficient.signum() < 0) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    val exponent = if (value.exponent.bitLength() > 63) {
        null
    } else {
        value.exponent.toLong()
    } ?: throw graphResourceLimit("output-bytes")
    val places = if (-exponent in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        (-exponent).toInt()
    } else {
        null
    } ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    val digits = value.coefficient.toString()
    if (exponent >= 0 || digits.length > places) {
        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
    }
    if (places.saturatingAdd(1) > max) {
        throw graphResourceLimit("output-bytes")
    }
    return ".${"0".repeat(places - digits.length)}$digits"
}

/** Standard base64 encoding (materialization.rs:1574-1609). */
internal fun encodeBase64(value: ByteArray, max: Int): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val length = (value.size + 2) / 3 * 4
    if (length > max) {
        throw graphResourceLimit("output-bytes")
    }
    val output = StringBuilder(length)
    var index = 0
    while (index < value.size) {
        val first = value[index].toInt() and 0xff
        val second = if (index + 1 < value.size) value[index + 1].toInt() and 0xff else 0
        val third = if (index + 2 < value.size) value[index + 2].toInt() and 0xff else 0
        output.append(alphabet[first shr 2])
        output.append(alphabet[((first and 0x03) shl 4) or (second shr 4)])
        output.append(
            if (index + 1 < value.size) {
                alphabet[((second and 0x0f) shl 2) or (third shr 6)]
            } else {
                '='
            },
        )
        output.append(if (index + 2 < value.size) alphabet[third and 0x3f] else '=')
        index += 3
    }
    return output.toString()
}

/** Builds the value-to-YAML provenance multimap (materialization.rs:
 * 1611-1824). */
private class ValueProvenanceBuilder(
    private val document: Document,
    private val request: MaterializationRequest,
) {
    private var units = 0
    private val entriesList = ArrayList<MaterializationProvenanceEntry>()
    private val index = HashMap<MaterializationInputLocation, Int>()

    val entries: List<MaterializationProvenanceEntry>
        get() = entriesList

    fun collect(input: PortableValue, path: ValuePath, output: YamlNode) {
        val transformed = input is PvEntryMapping && mappingHasUniqueStringKeys(input)
        push(
            MaterializationInputLocation.Value(path),
            origin(
                output.nodeRef(),
                output.span(),
                if (transformed) MaterializationRelation.Reencoded else MaterializationRelation.Direct,
            ),
        )
        when (input.kind) {
            Kind.Sequence -> {
                val values = (input as PvArray).items()
                if (output.sequenceLen() != values.size) {
                    throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                }
                for ((index, value) in values.withIndex()) {
                    val item = output.sequenceItem(index)
                        ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                    val childPath = path.child(ValuePathSegment.SequenceElement(index.toLong()))
                    collect(value, childPath, item.node())
                    add(
                        MaterializationInputLocation.Value(childPath),
                        origin(item.nodeRef(), item.span(), MaterializationRelation.Generated),
                    )
                }
            }
            Kind.Object -> {
                val values = (input as PvObject).entries()
                if (output.mappingLen() != values.size) {
                    throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                }
                for ((index, value) in values.withIndex()) {
                    val entry = output.mappingEntry(index)
                        ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                    if (entry.key().scalar()?.canonical() != value.key) {
                        throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                    }
                    push(
                        MaterializationInputLocation.Association(
                            AssociationLocation(path, index.toLong(), AssociationRole.ObjectEntry),
                        ),
                        origin(entry.nodeRef(), entry.span(), MaterializationRelation.Direct),
                    )
                    push(
                        MaterializationInputLocation.Association(
                            AssociationLocation(path, index.toLong(), AssociationRole.ObjectKey),
                        ),
                        origin(entry.key().nodeRef(), entry.key().span(), MaterializationRelation.Direct),
                    )
                    collect(
                        value.value,
                        path.child(ValuePathSegment.ObjectValue(value.key)),
                        entry.value(),
                    )
                }
            }
            Kind.EntryMapping -> {
                val values = (input as PvEntryMapping).entries()
                if (output.mappingLen() != values.size) {
                    throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                }
                for ((index, value) in values.withIndex()) {
                    val entry = output.mappingEntry(index)
                        ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
                    push(
                        MaterializationInputLocation.Association(
                            AssociationLocation(path, index.toLong(), AssociationRole.EntryMappingEntry),
                        ),
                        origin(
                            entry.nodeRef(),
                            entry.span(),
                            if (transformed) MaterializationRelation.Reencoded else MaterializationRelation.Direct,
                        ),
                    )
                    collect(
                        value.key,
                        path.child(ValuePathSegment.EntryKey(index.toLong())),
                        entry.key(),
                    )
                    collect(
                        value.value,
                        path.child(ValuePathSegment.EntryValue(index.toLong())),
                        entry.value(),
                    )
                }
            }
            else -> {}
        }
    }

    private fun origin(
        node: consema.document.NodeRef,
        span: consema.document.Span,
        relation: MaterializationRelation,
    ): MaterializedOrigin =
        MaterializedOrigin(document.snapshotIdentity, node, span, relation)

    private fun push(input: MaterializationInputLocation, output: MaterializedOrigin) {
        units = units + 2
        if (units > request.limits.maxProvenanceEntries) {
            throw graphResourceLimit("provenance-entries")
        }
        val position = entriesList.size
        entriesList.add(MaterializationProvenanceEntry(input, mutableListOf(output)))
        index[input] = position
    }

    private fun add(input: MaterializationInputLocation, output: MaterializedOrigin) {
        units++
        if (units > request.limits.maxProvenanceEntries) {
            throw graphResourceLimit("provenance-entries")
        }
        val position = index[input]
            ?: throw MaterializationException(MaterializationFailureKind.FORMATION_FAILED)
        val entry = entriesList[position]
        entriesList[position] = entry.copy(outputs = entry.outputs + output)
    }
}

private fun mappingHasUniqueStringKeys(mapping: PvEntryMapping): Boolean {
    val names = HashSet<String>()
    return mapping.entries().all { entry ->
        val name = (entry.key as? PvString)?.value ?: return@all false
        names.add(name)
    }
}
