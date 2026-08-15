// Two-stage explicit projection: Document -> PortableGraph with provenance,
// and Document -> PortableValue with fidelity, report, and provenance.
//
// Data authority:
//   - RFC 0007 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0007-yaml-family-profiles-and-safety-v1.md): yaml.projection.best-exact-graph@1 is the default YAML
//     target (standard resolved tags, arbitrary keys, association order,
//     sharing, cycles); yaml.projection.best-exact-value@1 defaults to
//     stream RequireExactlyOneDocument, sharing Reject, cycle Reject, tag
//     RequireKnownPortableTag, mapping BestExactObjectOrEntryMapping, alias
//     expansion Disabled; the frozen Rust request names (SharingPolicy,
//     TagPolicy, MappingPolicy); graph provenance distinguishes roots,
//     nodes, sequence edges, mapping-key edges, and mapping-value edges;
//     alias occurrences are Reference origins; failure carries no partial
//     value or provenance.
//   - RFC 0004 §7-§8 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md) pins the completion algebra and the
//     provenance direction (portable locations to source origins).
//   - conformance/vectors/yaml-v1.json pins the per-case outcomes
//     (projection.sharing-policy, projection.cycle, projection.tag-policy,
//     projection.mapping-policy, projection.graph-provenance,
//     resource.graph-provenance, graph.shared-cycle).
//   - https://github.com/consema/consema-rs/blob/main/consema-yaml/src/native.rs (graph projection with
//     canonical ids) and https://github.com/consema/consema-rs/blob/main/consema-yaml/src/projection.rs are the
//     byte-arbitration authorities (requests projection.rs, failure
//     codes projection.rs and 478-520, graph provenance
//     projection.rs, value projection projection.rs).
//   - Value paths come from the L0 core agent (consema.core.ValuePath /
//     ValuePathSegment / AssociationLocation / AssociationRole mirroring
//     https://github.com/consema/consema-rs/blob/main/consema-core/src/location.rs; the dependency is declared
//     by kotlin/src/main/kotlin/consema/document/Materialization.kt).
//
// Kotlin-idiomatic design: the completion algebra is a sealed class, so
// exhaustive `when` over Complete/Failed can never meet an unknown outcome;
// failures carry their frozen registered code via the [valueProjectionCode]
// and [graphProjectionCode] mappings (projection.rs).

package consema.yaml

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.EntryMappingBuilder
import consema.core.ObjectBuilder
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvDate
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvOffsetDateTime
import consema.core.PvString
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.graph.Builder
import consema.graph.Graph
import consema.graph.GraphException
import consema.graph.GraphErrorKind
import consema.graph.GraphLimits
import consema.graph.MappingEntry
import consema.graph.NodeId
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.ParseLimits
import consema.document.SnapshotIdentity
import consema.document.Span
import java.math.BigInteger

/** Graph projection resource contract (projection.rs). */
data class GraphProjectionLimits(
    /** PortableGraph construction and traversal limits. */
    val graph: GraphLimits,
    /** Maximum projected-location plus origin records. */
    val maxProvenanceEntries: Int,
) {
    companion object {
        /** The frozen defaults (projection.rs). */
        val default = GraphProjectionLimits(
            graph = GraphLimits.default,
            maxProvenanceEntries = 2_000_000,
        )
    }
}

/** Immutable `yaml.projection.best-exact-graph@1` request
 * (projection.rs). */
class GraphProjectionRequest private constructor(val limits: GraphProjectionLimits) {
    companion object {
        /** Creates the frozen exact graph request with default limits
         * (projection.rs). */
        fun bestExactV1(): GraphProjectionRequest = GraphProjectionRequest(GraphProjectionLimits.default)
    }

    /** Replaces all graph projection limits (projection.rs). */
    fun withLimits(limits: GraphProjectionLimits): GraphProjectionRequest =
        GraphProjectionRequest(limits)
}

/** One exact projected graph location (projection.rs). */
sealed class GraphProjectedLocation {
    /** Ordered root occurrence. */
    data class Root(val ordinal: Long) : GraphProjectedLocation()

    /** Graph node identity. */
    data class Node(val node: NodeId) : GraphProjectedLocation()

    /** Ordered sequence edge. */
    data class SequenceElement(
        /** Parent sequence node. */
        val parent: NodeId,
        /** Direct element ordinal. */
        val ordinal: Long,
    ) : GraphProjectedLocation()

    /** Ordered mapping key edge. */
    data class MappingKey(
        /** Parent mapping node. */
        val parent: NodeId,
        /** Direct association ordinal. */
        val ordinal: Long,
    ) : GraphProjectedLocation()

    /** Ordered mapping value edge. */
    data class MappingValue(
        /** Parent mapping node. */
        val parent: NodeId,
        /** Direct association ordinal. */
        val ordinal: Long,
    ) : GraphProjectedLocation()
}

/** Source relation shared by graph and tree projection provenance
 * (projection.rs). */
enum class ProvenanceRelation {
    /** Direct native semantic origin. */
    Direct,

    /** Alias edge referring to a shared representation node. */
    Reference,

    /** Alias edge explicitly duplicated into a PortableValue tree. */
    Expanded,

    /** A tag was explicitly removed by policy. */
    TagStripped,
}

/** One exact YAML source origin (projection.rs). */
data class SourceOrigin(
    /** Owning source snapshot. */
    val snapshot: SnapshotIdentity,
    /** Exact structural identity. */
    val node: NodeRef,
    /** Exact raw source span. */
    val span: Span,
    /** Source-to-result relation. */
    val relation: ProvenanceRelation,
)

/** One graph provenance multimap entry (projection.rs). */
data class GraphProvenanceEntry(
    /** Projected graph location. */
    val projected: GraphProjectedLocation,
    /** One or more exact YAML origins. */
    val origins: List<SourceOrigin>,
)

/** Complete deterministic graph provenance multimap (projection.rs). */
class GraphProvenanceMap internal constructor(
    internal val entriesList: List<GraphProvenanceEntry>,
) {
    /** Entries in root/node/association construction order. */
    fun entries(): List<GraphProvenanceEntry> = entriesList

    override fun equals(other: Any?): Boolean =
        other is GraphProvenanceMap && entriesList == other.entriesList

    override fun hashCode(): Int = entriesList.hashCode()
}

/** Complete exact graph projection (projection.rs). */
data class CompleteGraphProjection(
    /** Complete immutable graph. */
    val graph: Graph,
    /** Complete native-to-graph provenance. */
    val provenance: GraphProvenanceMap,
)

/** Graph projection failure; no graph or provenance is returned
 * (projection.rs). */
sealed class GraphProjectionFailure {
    /** Custom tag has no published graph canonical semantics. */
    data class UnsupportedTag(val tag: String) : GraphProjectionFailure()

    /** Graph construction failed atomically. */
    data class Graph(val cause: GraphException) : GraphProjectionFailure()

    /** Provenance resource limit was exceeded atomically. */
    data object ProvenanceLimit : GraphProjectionFailure()
}

/** Stable diagnostic code for exact graph projection (projection.rs). */
fun graphProjectionCode(failure: GraphProjectionFailure): String =
    when (failure) {
        is GraphProjectionFailure.UnsupportedTag -> "yaml.projection.unsupported-tag@1"
        is GraphProjectionFailure.Graph ->
            if (failure.cause.kind == GraphErrorKind.RESOURCE_LIMIT ||
                failure.cause.kind == GraphErrorKind.SIZE_OVERFLOW
            ) {
                "yaml.projection.resource-limit@1"
            } else {
                "yaml.projection.graph-invalid@1"
            }
        is GraphProjectionFailure.ProvenanceLimit -> "yaml.projection.provenance-limit@1"
    }

/** The typed graph projection failure. */
class GraphProjectionException(val failure: GraphProjectionFailure) :
    Exception("yaml graph projection: ${graphProjectionCode(failure)}")

/** Explicit YAML graph-sharing policy for PortableValue projection
 * (projection.rs). */
enum class SharingPolicy {
    /** Sharing and aliases fail; graph identity is never silently
     * discarded. */
    Reject,

    /** Acyclic sharing is duplicated and reported; cycles still fail. */
    DuplicateAcyclic,
}

/** Explicit YAML tag policy for PortableValue projection
 * (projection.rs). */
enum class TagPolicy {
    /** Only tags with a frozen exact PortableValue lowering are accepted. */
    RequireKnownPortableTag,

    /** Unsupported standard and custom tags are removed and reported. */
    StripToNodeKind,
}

/** YAML mapping-to-tree selection policy (projection.rs). */
enum class MappingPolicy {
    /** Use Object only for unique string keys, otherwise EntryMapping. */
    BestExactObjectOrEntryMapping,

    /** Require every mapping to satisfy unique-string Object invariants. */
    RequireObject,

    /** Preserve every mapping as ordered EntryMapping. */
    RequireEntryMapping,
}

/** PortableValue projection resource contract (projection.rs). */
data class ValueProjectionLimits(
    /** Maximum projected native/value node visits. */
    val maxValueNodes: Int,
    /** Maximum recursive graph depth. */
    val maxDepth: Int,
    /** Maximum report events. */
    val maxReportEntries: Int,
    /** Maximum projected-location plus origin records. */
    val maxProvenanceEntries: Int,
    /** Maximum output-node visits divided by unique native nodes. */
    val maxAmplificationRatio: Int,
) {
    companion object {
        /** The frozen defaults (projection.rs): 1,000,000 value
         * nodes, depth 256, 100,000 report entries, 2,000,000 provenance
         * entries, amplification ratio 16. */
        val default = ValueProjectionLimits(
            maxValueNodes = 1_000_000,
            maxDepth = 256,
            maxReportEntries = 100_000,
            maxProvenanceEntries = 2_000_000,
            maxAmplificationRatio = 16,
        )
    }
}

/** Immutable `yaml.projection.best-exact-value@1` request
 * (projection.rs). */
class ValueProjectionRequest private constructor(
    /** Selected sharing policy. */
    val sharing: SharingPolicy,
    /** Selected tag policy. */
    val tags: TagPolicy,
    /** Selected mapping policy. */
    val mapping: MappingPolicy,
    /** Exact limits. */
    val limits: ValueProjectionLimits,
) {
    companion object {
        /** Frozen default: one document, no sharing/cycles, known tags,
         * exact-first mapping (projection.rs). */
        fun bestExactV1(): ValueProjectionRequest = ValueProjectionRequest(
            sharing = SharingPolicy.Reject,
            tags = TagPolicy.RequireKnownPortableTag,
            mapping = MappingPolicy.BestExactObjectOrEntryMapping,
            limits = ValueProjectionLimits.default,
        )
    }

    /** Explicitly replaces the sharing policy. */
    fun withSharing(policy: SharingPolicy): ValueProjectionRequest =
        ValueProjectionRequest(policy, tags, mapping, limits)

    /** Explicitly replaces the tag policy. */
    fun withTags(policy: TagPolicy): ValueProjectionRequest =
        ValueProjectionRequest(sharing, policy, mapping, limits)

    /** Explicitly replaces the mapping policy. */
    fun withMapping(policy: MappingPolicy): ValueProjectionRequest =
        ValueProjectionRequest(sharing, tags, policy, limits)

    /** Replaces all value projection limits. */
    fun withLimits(limits: ValueProjectionLimits): ValueProjectionRequest =
        ValueProjectionRequest(sharing, tags, mapping, limits)
}

/** Projection fidelity classification (projection.rs). */
enum class Fidelity {
    /** Target completely represents all covered semantics. */
    Exact,

    /** Explicit policy performed a declared structural transformation. */
    Transformed,

    /** Explicit policy discarded an unrecoverable source fact. */
    Lossy,
}

/** One PortableValue or association location (projection.rs). */
sealed class ProjectedLocation {
    /** Portable value path. */
    data class Value(val path: ValuePath) : ProjectedLocation()

    /** Portable association location. */
    data class Association(val location: AssociationLocation) : ProjectedLocation()
}

/** One PortableValue provenance entry (projection.rs). */
data class ProvenanceEntry(
    /** Projected tree location. */
    val projected: ProjectedLocation,
    /** One or more exact YAML origins. */
    val origins: List<SourceOrigin>,
)

/** Complete deterministic PortableValue provenance multimap
 * (projection.rs). */
class ProvenanceMap internal constructor(
    internal val entriesList: List<ProvenanceEntry>,
) {
    /** Entries in deterministic projection order. */
    fun entries(): List<ProvenanceEntry> = entriesList

    override fun equals(other: Any?): Boolean =
        other is ProvenanceMap && entriesList == other.entriesList

    override fun hashCode(): Int = entriesList.hashCode()
}

/** Structured YAML value projection event category (projection.rs). */
enum class ProjectionEventKind {
    /** Shared graph identity was explicitly duplicated into a tree. */
    SharingDuplicated,

    /** Unsupported tag was explicitly removed. */
    TagStripped,
}

/** One machine-readable projection transformation/loss event
 * (projection.rs). */
data class ProjectionEvent(
    /** Stable event category. */
    val kind: ProjectionEventKind,
    /** Policy that authorized the event. */
    val policy: String,
    /** Exact source identity. */
    val source: NodeRef,
    /** Projected value location. */
    val projected: ValuePath,
    /** Stable old semantic category. */
    val oldCategory: String,
    /** Stable new semantic category. */
    val newCategory: String,
    /** Whether output plus contract can recover the fact. */
    val reversible: Boolean,
    /** Fidelity impact. */
    val loss: Fidelity,
)

/** Complete ordered value projection report (projection.rs). */
class ProjectionReport internal constructor(
    internal val eventsList: List<ProjectionEvent>,
) {
    /** Events in deterministic traversal order. */
    fun events(): List<ProjectionEvent> = eventsList

    override fun equals(other: Any?): Boolean =
        other is ProjectionReport && eventsList == other.eventsList

    override fun hashCode(): Int = eventsList.hashCode()
}

/** Complete successful PortableValue projection (projection.rs). */
data class CompleteValueProjection(
    /** Complete immutable tree value. */
    val value: PortableValue,
    /** Worst fidelity of the complete operation. */
    val fidelity: Fidelity,
    /** Explicit transformation/loss report. */
    val report: ProjectionReport,
    /** Complete source-to-tree provenance. */
    val provenance: ProvenanceMap,
)

/** Value projection failure; no partial value or provenance is returned
 * (projection.rs). */
sealed class ValueProjectionFailure {
    /** Stream does not contain exactly one document. */
    data class DocumentCardinality(val actual: Int) : ValueProjectionFailure()

    /** Representation cycle cannot enter a tree. */
    data class Cycle(val node: NodeRef) : ValueProjectionFailure()

    /** Shared identity requires explicit duplication authorization. */
    data class Sharing(val node: NodeRef) : ValueProjectionFailure()

    /** Tag has no exact PortableValue lowering. */
    data class UnsupportedTag(val node: NodeRef, val tag: String) : ValueProjectionFailure()

    /** Mapping cannot satisfy an explicitly required Object policy. */
    data class MappingNotObject(val node: NodeRef) : ValueProjectionFailure()

    /** Canonical scalar could not form the promised PortableValue category. */
    data class InvalidCanonicalScalar(val node: NodeRef) : ValueProjectionFailure()

    /** YAML timestamp is valid but outside PortableValue temporal
     * categories. */
    data class UnrepresentableTimestamp(val node: NodeRef) : ValueProjectionFailure()

    /** Declared resource limit was reached. */
    data class ResourceLimit(val name: String) : ValueProjectionFailure()
}

/** Stable diagnostic code for YAML-to-tree projection (projection.rs). */
fun valueProjectionCode(failure: ValueProjectionFailure): String =
    when (failure) {
        is ValueProjectionFailure.DocumentCardinality -> "yaml.projection.document-cardinality@1"
        is ValueProjectionFailure.Cycle -> "yaml.projection.cycle@1"
        is ValueProjectionFailure.Sharing -> "yaml.projection.sharing@1"
        is ValueProjectionFailure.UnsupportedTag -> "yaml.projection.unsupported-tag@1"
        is ValueProjectionFailure.MappingNotObject -> "yaml.projection.mapping-not-object@1"
        is ValueProjectionFailure.InvalidCanonicalScalar -> "yaml.projection.invalid-canonical-scalar@1"
        is ValueProjectionFailure.UnrepresentableTimestamp -> "yaml.projection.unrepresentable-timestamp@1"
        is ValueProjectionFailure.ResourceLimit -> "yaml.projection.resource-limit@1"
    }

/** The typed value projection failure. */
class ValueProjectionException(val failure: ValueProjectionFailure) :
    Exception("yaml value projection: ${valueProjectionCode(failure)}")

/** Complete-or-failed PortableValue projection algebra (projection.rs). */
sealed class ValueProjectionResult {
    /** Complete result. */
    data class Complete(val projection: CompleteValueProjection) : ValueProjectionResult()

    /** Failed result with no partial value, report, or provenance. */
    data class Failed(val failure: ValueProjectionFailure) : ValueProjectionResult()
}

/** Projects all document roots to one exact PortableGraph (native.rs).
 * Unknown/custom tags fail instead of being treated as application
 * constructors or untyped strings; frozen standard repository tags remain
 * exact tagged graph nodes. */
fun Document.projectGraph(limits: GraphLimits = GraphLimits.default): Graph =
    projectGraphWithIds(limits).first

/** Projects all document roots with graph ids for provenance (native.rs). */
internal fun Document.projectGraphWithIds(limits: GraphLimits): Pair<Graph, List<NodeId>> {
    val builder = Builder.withLimits(limits)
    val ids = try {
        List(native.nodes.size) { builder.reserveNode() }
    } catch (e: GraphException) {
        throw GraphProjectionException(GraphProjectionFailure.Graph(e))
    }
    for ((index, node) in native.nodes.withIndex()) {
        if (!isStandardGraphTag(node.tag)) {
            throw GraphProjectionException(
                GraphProjectionFailure.UnsupportedTag(node.tag),
            )
        }
        when (val content = node.content) {
            is NativeContent.Scalar -> try {
                builder.defineScalar(ids[index], node.tag, content.scalar.canonical)
            } catch (e: GraphException) {
                throw GraphProjectionException(GraphProjectionFailure.Graph(e))
            }
            is NativeContent.Sequence -> try {
                builder.defineSequence(
                    ids[index],
                    node.tag,
                    content.items.map { ids[it.node] },
                )
            } catch (e: GraphException) {
                throw GraphProjectionException(GraphProjectionFailure.Graph(e))
            }
            is NativeContent.Mapping -> try {
                builder.defineMapping(
                    ids[index],
                    node.tag,
                    content.entries.map { MappingEntry(ids[it.key], ids[it.value]) },
                )
            } catch (e: GraphException) {
                throw GraphProjectionException(GraphProjectionFailure.Graph(e))
            }
        }
    }
    for (document in native.documents) {
        try {
            builder.pushRoot(ids[document.root])
        } catch (e: GraphException) {
            throw GraphProjectionException(GraphProjectionFailure.Graph(e))
        }
    }
    val graph = try {
        builder.build()
    } catch (e: GraphException) {
        throw GraphProjectionException(GraphProjectionFailure.Graph(e))
    }
    return graph to ids
}

/** Applies exact graph projection with complete node/edge/alias provenance
 * (projection.rs). */
fun Document.projectGraphWithProvenance(
    request: GraphProjectionRequest,
): CompleteGraphProjection {
    val (graph, ids) = projectGraphWithIds(request.limits.graph)
    val builder = GraphProjectionBuilder(this, ids, request.limits.maxProvenanceEntries)
    builder.build()
    return CompleteGraphProjection(graph, builder.map)
}

/** Builds the graph provenance multimap (projection.rs). */
private class GraphProjectionBuilder(
    private val document: Document,
    private val ids: List<NodeId>,
    private val maxEntries: Int,
) {
    private var units = 0
    private val entries = ArrayList<GraphProvenanceEntry>()
    private val index = HashMap<GraphProjectedLocation, Int>()

    val map: GraphProvenanceMap
        get() = GraphProvenanceMap(entries)

    fun build() {
        for ((ordinal, nativeDocument) in document.native.documents.withIndex()) {
            add(
                GraphProjectedLocation.Root(ordinal.toLong()),
                SourceOrigin(
                    snapshot = document.snapshotIdentity,
                    node = document.authority.nodeRef(ordinal.toLong(), NodeRole.YamlDocument),
                    span = nativeDocument.span,
                    relation = ProvenanceRelation.Direct,
                ),
            )
        }
        for ((index, node) in document.native.nodes.withIndex()) {
            add(
                GraphProjectedLocation.Node(ids[index]),
                SourceOrigin(
                    snapshot = document.snapshotIdentity,
                    node = nodeRef(document.authority, index),
                    span = node.span,
                    relation = ProvenanceRelation.Direct,
                ),
            )
            when (val content = node.content) {
                is NativeContent.Scalar -> {}
                is NativeContent.Sequence -> {
                    for ((ordinal, item) in content.items.withIndex()) {
                        val location = GraphProjectedLocation.SequenceElement(
                            parent = ids[index],
                            ordinal = ordinal.toLong(),
                        )
                        add(
                            location,
                            SourceOrigin(
                                snapshot = document.snapshotIdentity,
                                node = document.authority.nodeRef(
                                    item.identity,
                                    NodeRole.YamlSequenceElement,
                                ),
                                span = item.span,
                                relation = ProvenanceRelation.Direct,
                            ),
                        )
                        if (item.alias != null) {
                            addAlias(location, item.alias!!, ProvenanceRelation.Reference)
                        }
                    }
                }
                is NativeContent.Mapping -> {
                    for ((ordinal, entry) in content.entries.withIndex()) {
                        for ((location, alias) in listOf(
                            GraphProjectedLocation.MappingKey(
                                parent = ids[index],
                                ordinal = ordinal.toLong(),
                            ) to entry.keyAlias,
                            GraphProjectedLocation.MappingValue(
                                parent = ids[index],
                                ordinal = ordinal.toLong(),
                            ) to entry.valueAlias,
                        )) {
                            add(
                                location,
                                SourceOrigin(
                                    snapshot = document.snapshotIdentity,
                                    node = document.authority.nodeRef(
                                        entry.identity,
                                        NodeRole.YamlMappingEntry,
                                    ),
                                    span = entry.span,
                                    relation = ProvenanceRelation.Direct,
                                ),
                            )
                            if (alias != null) {
                                addAlias(location, alias, ProvenanceRelation.Reference)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addAlias(
        location: GraphProjectedLocation,
        ordinal: Int,
        relation: ProvenanceRelation,
    ) {
        val alias = document.native.aliases[ordinal]
        add(
            location,
            SourceOrigin(
                snapshot = document.snapshotIdentity,
                node = document.authority.nodeRef(alias.identity, NodeRole.YamlAlias),
                span = alias.span,
                relation = relation,
            ),
        )
    }

    private fun add(projected: GraphProjectedLocation, origin: SourceOrigin) {
        val existing = index[projected]
        val observed = units + if (existing != null) 1 else 2
        if (observed > maxEntries) {
            throw GraphProjectionException(GraphProjectionFailure.ProvenanceLimit)
        }
        units = observed
        if (existing != null) {
            val entry = entries[existing]
            entries[existing] = entry.copy(origins = entry.origins + origin)
        } else {
            val position = entries.size
            entries.add(GraphProvenanceEntry(projected, mutableListOf(origin)))
            index[projected] = position
        }
    }
}

/** Applies explicit YAML-to-PortableValue tree projection (projection.rs). */
fun Document.projectValue(request: ValueProjectionRequest): ValueProjectionResult {
    if (documentCount() != 1) {
        return ValueProjectionResult.Failed(
            ValueProjectionFailure.DocumentCardinality(documentCount()),
        )
    }
    if (request.limits.maxAmplificationRatio == 0) {
        return ValueProjectionResult.Failed(
            ValueProjectionFailure.ResourceLimit("max_amplification_ratio"),
        )
    }
    val context = ValueContext(this, request)
    val root = native.documents[0].root
    return try {
        val value = context.projectNode(root, ValuePath.root(), 0, null)
        val maximum = context.seen.size.saturatingMul(request.limits.maxAmplificationRatio)
        if (context.visits > maximum) {
            ValueProjectionResult.Failed(
                ValueProjectionFailure.ResourceLimit("max_amplification_ratio"),
            )
        } else {
            ValueProjectionResult.Complete(
                CompleteValueProjection(
                    value = value,
                    fidelity = context.fidelity,
                    report = ProjectionReport(context.report),
                    provenance = ProvenanceMap(context.provenance),
                ),
            )
        }
    } catch (e: ValueProjectionException) {
        ValueProjectionResult.Failed(e.failure)
    }
}

private class ValueContext(
    private val document: Document,
    private val request: ValueProjectionRequest,
) {
    /** Unique native nodes visited by the projection (used for the
     * amplification-ratio check, projection.rs). */
    val seen = HashSet<Int>()
    private val stack = HashSet<Int>()
    var visits = 0
    private var provenanceUnits = 0
    private val reportList = ArrayList<ProjectionEvent>()
    private val provenanceList = ArrayList<ProvenanceEntry>()
    private val provenanceIndex = HashMap<ProjectedLocation, Int>()
    var fidelity: Fidelity = Fidelity.Exact

    val report: List<ProjectionEvent>
        get() = reportList

    val provenance: List<ProvenanceEntry>
        get() = provenanceList

    fun projectNode(
        index: Int,
        path: ValuePath,
        depth: Int,
        incomingAlias: Int?,
    ): PortableValue {
        if (depth > request.limits.maxDepth) {
            throw ValueProjectionException(ValueProjectionFailure.ResourceLimit("max_depth"))
        }
        visits++
        if (visits > request.limits.maxValueNodes) {
            throw ValueProjectionException(ValueProjectionFailure.ResourceLimit("max_value_nodes"))
        }
        val nodeRef = nodeRef(document.authority, index)
        if (stack.contains(index)) {
            throw ValueProjectionException(ValueProjectionFailure.Cycle(nodeRef))
        }
        if (seen.contains(index)) {
            if (request.sharing == SharingPolicy.Reject) {
                throw ValueProjectionException(ValueProjectionFailure.Sharing(nodeRef))
            }
            event(
                ProjectionEventKind.SharingDuplicated,
                "DuplicateAcyclicSharing@1",
                incomingAlias?.let { aliasRef(it) } ?: nodeRef,
                path,
                "SharedGraphNode",
                "DuplicatedTreeValue",
                false,
                Fidelity.Transformed,
            )
        }
        seen.add(index)
        stack.add(index)
        val node = document.native.nodes[index]
        val supportedTag = isPortableTag(node.tag, node.content)
        if (!supportedTag) {
            if (request.tags == TagPolicy.RequireKnownPortableTag) {
                throw ValueProjectionException(
                    ValueProjectionFailure.UnsupportedTag(nodeRef, node.tag),
                )
            }
            event(
                ProjectionEventKind.TagStripped,
                "StripToNodeKind@1",
                nodeRef,
                path,
                node.tag,
                nodeKindName(node.content),
                false,
                Fidelity.Lossy,
            )
        }
        val value = when (val content = node.content) {
            is NativeContent.Scalar -> projectScalar(index, content.scalar, supportedTag)
            is NativeContent.Sequence -> {
                val builder = ArrayList<PortableValue>()
                for ((ordinal, item) in content.items.withIndex()) {
                    val child = path.child(ValuePathSegment.SequenceElement(ordinal.toLong()))
                    builder.add(projectNode(item.node, child, depth + 1, item.alias))
                    addOrigin(
                        ProjectedLocation.Value(child),
                        document.authority.nodeRef(item.identity, NodeRole.YamlSequenceElement),
                        item.span,
                        ProvenanceRelation.Direct,
                    )
                }
                PvArray(builder)
            }
            is NativeContent.Mapping -> projectMapping(index, content.entries, path, depth)
        }
        stack.remove(index)
        addOrigin(
            ProjectedLocation.Value(path),
            nodeRef,
            node.span,
            if (supportedTag) ProvenanceRelation.Direct else ProvenanceRelation.TagStripped,
        )
        if (incomingAlias != null) {
            val alias = document.native.aliases[incomingAlias]
            addOrigin(
                ProjectedLocation.Value(path),
                document.authority.nodeRef(alias.identity, NodeRole.YamlAlias),
                alias.span,
                ProvenanceRelation.Expanded,
            )
        }
        return value
    }

    private fun projectMapping(
        index: Int,
        entries: List<NativeMappingEntry>,
        path: ValuePath,
        depth: Int,
    ): PortableValue {
        val objectNames = objectNames(entries)
        val useObject = when (request.mapping) {
            MappingPolicy.BestExactObjectOrEntryMapping -> objectNames != null
            MappingPolicy.RequireObject -> {
                if (objectNames == null) {
                    throw ValueProjectionException(
                        ValueProjectionFailure.MappingNotObject(nodeRef(document.authority, index)),
                    )
                }
                true
            }
            MappingPolicy.RequireEntryMapping -> false
        }
        if (useObject) {
            val names = objectNames!!
            val builder = ObjectBuilder()
            for ((ordinal, entry) in entries.withIndex()) {
                visitObjectKey(entry.key, entry.keyAlias, path)
                val child = path.child(ValuePathSegment.ObjectValue(names[ordinal]))
                builder.insert(
                    names[ordinal],
                    projectNode(entry.value, child, depth + 1, entry.valueAlias),
                )
                addMappingOrigins(path, ordinal, entry, true)
            }
            return builder.build()
        }
        val builder = EntryMappingBuilder()
        for ((ordinal, entry) in entries.withIndex()) {
            val keyPath = path.child(ValuePathSegment.EntryKey(ordinal.toLong()))
            val valuePath = path.child(ValuePathSegment.EntryValue(ordinal.toLong()))
            val key = projectNode(entry.key, keyPath, depth + 1, entry.keyAlias)
            val value = projectNode(entry.value, valuePath, depth + 1, entry.valueAlias)
            builder.push(key, value)
            addMappingOrigins(path, ordinal, entry, false)
        }
        return builder.build()
    }

    private fun visitObjectKey(index: Int, alias: Int?, path: ValuePath) {
        val node = nodeRef(document.authority, index)
        if (stack.contains(index)) {
            throw ValueProjectionException(ValueProjectionFailure.Cycle(node))
        }
        if (seen.contains(index)) {
            if (request.sharing == SharingPolicy.Reject) {
                throw ValueProjectionException(ValueProjectionFailure.Sharing(node))
            }
            event(
                ProjectionEventKind.SharingDuplicated,
                "DuplicateAcyclicSharing@1",
                alias?.let { aliasRef(it) } ?: node,
                path,
                "SharedGraphNode",
                "DuplicatedObjectKey",
                false,
                Fidelity.Transformed,
            )
        }
        seen.add(index)
        visits++
        if (visits > request.limits.maxValueNodes) {
            throw ValueProjectionException(ValueProjectionFailure.ResourceLimit("max_value_nodes"))
        }
    }

    private fun projectScalar(
        index: Int,
        scalar: NativeScalar,
        supportedTag: Boolean,
    ): PortableValue {
        val node = nodeRef(document.authority, index)
        if (!supportedTag) {
            return PvString(scalar.decoded)
        }
        return when (scalar.kind) {
            YamlScalarKind.Null -> PvNull
            YamlScalarKind.Boolean -> when (scalar.canonical) {
                "true" -> PvBoolean(true)
                "false" -> PvBoolean(false)
                else -> throw ValueProjectionException(
                    ValueProjectionFailure.InvalidCanonicalScalar(node),
                )
            }
            YamlScalarKind.Integer -> {
                val value = scalar.canonical.toBigIntegerOrNull()
                    ?: throw ValueProjectionException(
                        ValueProjectionFailure.InvalidCanonicalScalar(node),
                    )
                PvInteger(value)
            }
            YamlScalarKind.Float -> when (scalar.canonical) {
                ".inf" -> PvBinaryFloat64(0x7ff0_0000_0000_0000L)
                "-.inf" -> PvBinaryFloat64(-0x10_0000_0000_0000L)
                ".nan" -> PvBinaryFloat64(0x7ff8_0000_0000_0000L)
                else -> {
                    val decimal = parseJsonNumber(scalar.canonical, ParseLimits.default.maxNumberDigits)
                        ?: throw ValueProjectionException(
                            ValueProjectionFailure.InvalidCanonicalScalar(node),
                        )
                    decimal
                }
            }
            YamlScalarKind.String -> PvString(scalar.canonical)
            YamlScalarKind.Binary -> {
                val bytes = decodeBase64(scalar.canonical)
                    ?: throw ValueProjectionException(
                        ValueProjectionFailure.InvalidCanonicalScalar(node),
                    )
                PvBytes.of(bytes)
            }
            YamlScalarKind.Timestamp -> {
                val value = projectTimestamp(scalar.canonical)
                    ?: throw ValueProjectionException(
                        ValueProjectionFailure.UnrepresentableTimestamp(node),
                    )
                value
            }
            YamlScalarKind.Custom, YamlScalarKind.Tagged -> PvString(scalar.decoded)
        }
    }

    private fun addMappingOrigins(
        path: ValuePath,
        ordinal: Int,
        entry: NativeMappingEntry,
        object_: Boolean,
    ) {
        val association = AssociationLocation(
            path,
            ordinal.toLong(),
            if (object_) AssociationRole.ObjectEntry else AssociationRole.EntryMappingEntry,
        )
        addOrigin(
            ProjectedLocation.Association(association),
            document.authority.nodeRef(entry.identity, NodeRole.YamlMappingEntry),
            entry.span,
            ProvenanceRelation.Direct,
        )
        if (object_) {
            val keyLocation = ProjectedLocation.Association(
                AssociationLocation(path, ordinal.toLong(), AssociationRole.ObjectKey),
            )
            val key = document.native.nodes[entry.key]
            addOrigin(
                keyLocation,
                nodeRef(document.authority, entry.key),
                key.span,
                ProvenanceRelation.Direct,
            )
            if (entry.keyAlias != null) {
                val alias = document.native.aliases[entry.keyAlias!!]
                addOrigin(
                    keyLocation,
                    document.authority.nodeRef(alias.identity, NodeRole.YamlAlias),
                    alias.span,
                    ProvenanceRelation.Expanded,
                )
            }
        }
    }

    private fun event(
        kind: ProjectionEventKind,
        policy: String,
        source: NodeRef,
        path: ValuePath,
        oldCategory: String,
        newCategory: String,
        reversible: Boolean,
        loss: Fidelity,
    ) {
        val observed = reportList.size + 1
        if (observed > request.limits.maxReportEntries) {
            throw ValueProjectionException(
                ValueProjectionFailure.ResourceLimit("max_report_entries"),
            )
        }
        reportList.add(
            ProjectionEvent(kind, policy, source, path, oldCategory, newCategory, reversible, loss),
        )
        if (loss.ordinal > fidelity.ordinal) {
            fidelity = loss
        }
    }

    private fun addOrigin(
        projected: ProjectedLocation,
        node: NodeRef,
        span: Span,
        relation: ProvenanceRelation,
    ) {
        val existing = provenanceIndex[projected]
        val observed = provenanceUnits + if (existing != null) 1 else 2
        if (observed > request.limits.maxProvenanceEntries) {
            throw ValueProjectionException(
                ValueProjectionFailure.ResourceLimit("max_provenance_entries"),
            )
        }
        provenanceUnits = observed
        val origin = SourceOrigin(document.snapshotIdentity, node, span, relation)
        if (existing != null) {
            val entry = provenanceList[existing]
            provenanceList[existing] = entry.copy(origins = entry.origins + origin)
        } else {
            val position = provenanceList.size
            provenanceList.add(ProvenanceEntry(projected, mutableListOf(origin)))
            provenanceIndex[projected] = position
        }
    }

    private fun aliasRef(ordinal: Int): NodeRef {
        val alias = document.native.aliases[ordinal]
        return document.authority.nodeRef(alias.identity, NodeRole.YamlAlias)
    }

    private fun objectNames(entries: List<NativeMappingEntry>): List<String>? {
        val seen = HashSet<String>()
        val names = ArrayList<String>(entries.size)
        for (entry in entries) {
            val key = document.native.nodes[entry.key]
            val content = key.content as? NativeContent.Scalar ?: return null
            if (key.tag != TAG_STR) {
                return null
            }
            val name = content.scalar.canonical
            if (!seen.add(name)) {
                return null
            }
            names.add(name)
        }
        return names
    }
}

/** Whether a tag has a frozen exact PortableValue lowering for its node
 * kind (projection.rs). */
private fun isPortableTag(tag: String, content: NativeContent): Boolean =
    when (content) {
        is NativeContent.Scalar ->
            tag == TAG_NULL || tag == TAG_BOOL || tag == TAG_INT || tag == TAG_FLOAT ||
                tag == TAG_STR || tag == TAG_TIMESTAMP || tag == TAG_BINARY
        is NativeContent.Sequence -> tag == TAG_SEQ
        is NativeContent.Mapping -> tag == TAG_MAP
    }

private fun nodeKindName(content: NativeContent): String =
    when (content) {
        is NativeContent.Scalar -> "Scalar"
        is NativeContent.Sequence -> "Sequence"
        is NativeContent.Mapping -> "Mapping"
    }

/** Timestamp lowering to the exact core temporal categories
 * (projection.rs). */
private fun projectTimestamp(value: String): PortableValue? {
    val year = value.substring(0, 4).toBigIntegerOrNull() ?: return null
    val month = value.substring(5, 7).toIntOrNull() ?: return null
    val day = value.substring(8, 10).toIntOrNull() ?: return null
    val date = try {
        PvDate.of(year, month, day)
    } catch (e: Exception) {
        return null
    }
    if (value.length == 10) {
        return date
    }
    val timeStart = 11
    val hour = value.substring(timeStart, timeStart + 2).toIntOrNull() ?: return null
    val minute = value.substring(timeStart + 3, timeStart + 5).toIntOrNull() ?: return null
    val second = value.substring(timeStart + 6, timeStart + 8).toIntOrNull() ?: return null
    val tail = value.substring(timeStart + 8)
    val zoneStart = tail.indexOfFirst { it == 'Z' || it == '+' || it == '-' }
    if (zoneStart < 0) {
        return null
    }
    val fraction = if (zoneStart == 0) {
        consema.core.PvDecimal.of(BigInteger.ZERO, BigInteger.ZERO)
    } else {
        parseJsonNumber("0" + tail.substring(0, zoneStart), ParseLimits.default.maxNumberDigits) ?: return null
    }
    val time = try {
        consema.core.PvTime.of(hour, minute, second, fraction)
    } catch (e: Exception) {
        return null
    }
    val local = consema.core.PvLocalDateTime(date, time)
    val zone = tail.substring(zoneStart)
    val offset = if (zone == "Z") {
        0
    } else {
        val sign = if (zone.startsWith('-')) -1 else 1
        val hours = zone.substring(1, 3).toIntOrNull() ?: return null
        val minutes = zone.substring(4, 6).toIntOrNull() ?: return null
        sign * (hours * 3600 + minutes * 60)
    }
    return try {
        PvOffsetDateTime.of(local, offset)
    } catch (e: Exception) {
        null
    }
}
