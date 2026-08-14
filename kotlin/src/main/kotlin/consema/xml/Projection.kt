// XML projection targets and explicit mapping policies (RFC 0012 §9).
//
// Data authority:
//   - RFC 0012 §9 (https://github.com/consema/consema/blob/main/docs/rfcs/0012-xml-1.0-safe-profile-v1.md): the
//     exact default target `xml.projection.element-tree@1` produces a
//     versioned `xml.element-tree@1` PortableValue record containing
//     declaration facts, admitted internal entity declarations, one
//     namespace-aware root, ordered namespace declarations, ordered
//     attributes, ordered mixed content, exact text/reference fragments,
//     CDATA, comments, and PI; `xml.projection.text-content@1` is always
//     Transformed; `xml.projection.simple-entry-mapping@1` admits only
//     policy-explicit subtrees; there is no xml-to-json-default, automatic
//     attribute `@` prefix, text `#text` key, or namespace stripping.
//   - conformance/vectors/xml-1-0-safe-v1.json cases xml.projection.* pin
//     the record spelling and the recovered-document failure.
//   - https://github.com/consema/consema-rs/blob/main/consema-xml/src/projection.rs is the byte-arbitration
//     authority: targets (projection.rs), policies (projection.rs), ProjectionRequest (projection.rs), limits
//     (projection.rs), the completion algebra (projection.rs), ProjectionFailure codes (projection.rs), the
//     element-tree record (projection.rs), content items
//     (projection.rs), text content (projection.rs), and
//     the entry mapping (projection.rs).
//   - RFC 0004 §7-§8 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-structural-edit-v1.md) pins the completion
//     algebra and the provenance direction (portable locations to source
//     origins).
//
// Kotlin-idiomatic design: the completion algebra is a sealed class, so
// exhaustive `when` over Complete/Failed can never meet an unknown outcome;
// failures carry their frozen xml.* code via [ProjectionFailure.code];
// the element-tree record is built with the core ObjectBuilder.

package consema.xml

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.EntryMappingBuilder
import consema.core.ObjectBuilder
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvNull
import consema.core.PvString
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.document.FormationStatus
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.SnapshotIdentity
import consema.document.Span
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/** Versioned XML projection target (projection.rs). */
enum class ProjectionTarget {
    /** Exact `xml.element-tree@1` record projection. */
    ElementTreeV1,

    /** Always-transformed descendant text content. */
    TextContentV1,

    /** Explicit-policy entry mapping of a selected subtree. */
    SimpleEntryMappingV1,
}

/** Descendant text inclusion for [ProjectionTarget.TextContentV1]
 * (projection.rs). */
enum class TextContentInclude {
    /** Include descendant text and CDATA occurrences. */
    TextAndCdata,

    /** Include descendant text only; CDATA is reported as discarded. */
    TextOnly,
}

/** Attribute handling for [ProjectionTarget.SimpleEntryMappingV1]
 * (projection.rs). */
enum class AttributePolicy {
    /** Reject the projection when any attribute is present. */
    RejectAttributes,

    /** Ignore every attribute and report each as discarded. */
    IgnoreAttributes,

    /** Prefix attribute keys with `@`. */
    PrefixAttributeKeys,
}

/** Text child handling for [ProjectionTarget.SimpleEntryMappingV1]
 * (projection.rs). */
enum class TextKeyPolicy {
    /** Reject the projection when any non-whitespace text is present. */
    RejectText,

    /** Discard text and report it as discarded. */
    IgnoreText,
}

/** Repeated expanded-child-name handling for
 * [ProjectionTarget.SimpleEntryMappingV1] (projection.rs). */
enum class RepeatedChildPolicy {
    /** Reject every repeated expanded child name. */
    Reject,

    /** Retain the first occurrence in document order. */
    First,

    /** Retain the last occurrence. */
    Last,
}

/** Entry-key spelling for [ProjectionTarget.SimpleEntryMappingV1]
 * (projection.rs). */
enum class ExpandedNameKeyPolicy {
    /** Key is the local name; namespace collisions must be resolved by
     * another policy or the projection fails. */
    LocalOnly,

    /** Key is the lexical `prefix:local` spelling. */
    PrefixedSpelling,

    /** Key is the `{uri}local` spelling; absent namespace is `{}local`. */
    UriBracketed,
}

/** Collision resolution direction shared by both entry policies
 * (projection.rs). */
private enum class KeepPolicy {
    Reject,
    First,
    Last,
}

/** Explicit mapping behavior for [ProjectionTarget.SimpleEntryMappingV1]
 * (projection.rs). */
enum class CollisionPolicy {
    /** Reject every collision. */
    Reject,

    /** Retain the first occurrence in document order. */
    First,

    /** Retain the last occurrence. */
    Last,
}

/** Explicit XML projection request; every policy is mandatory
 * (projection.rs). */
data class ProjectionRequest(
    /** Versioned projection target. */
    val target: ProjectionTarget,
    /** Selected subtree identity, when the request targets a subtree. */
    val subtree: Long?,
    /** Descendant text inclusion for TextContentV1. */
    val include: TextContentInclude,
    /** Attribute handling for SimpleEntryMappingV1. */
    val attributes: AttributePolicy,
    /** Text child handling for SimpleEntryMappingV1. */
    val textKey: TextKeyPolicy,
    /** Repeated expanded-child-name handling. */
    val repeatedChild: RepeatedChildPolicy,
    /** Entry-key spelling. */
    val keySpelling: ExpandedNameKeyPolicy,
    /** Collision behavior. */
    val collision: CollisionPolicy,
    /** Resource limits. */
    val limits: ProjectionLimits,
) {
    companion object {
        /** Exact `xml.element-tree@1` record request for the document root
         * (projection.rs). */
        fun elementTree(): ProjectionRequest =
            ProjectionRequest(
                target = ProjectionTarget.ElementTreeV1,
                subtree = null,
                include = TextContentInclude.TextAndCdata,
                attributes = AttributePolicy.RejectAttributes,
                textKey = TextKeyPolicy.RejectText,
                repeatedChild = RepeatedChildPolicy.Reject,
                keySpelling = ExpandedNameKeyPolicy.LocalOnly,
                collision = CollisionPolicy.Reject,
                limits = ProjectionLimits.default,
            )

        /** Explicit `SimpleEntryMappingV1` request over one subtree
         * (projection.rs). */
        fun simpleEntryMapping(
            subtree: NodeRef,
            attributes: AttributePolicy,
            textKey: TextKeyPolicy,
            repeatedChild: RepeatedChildPolicy,
            keySpelling: ExpandedNameKeyPolicy,
            collision: CollisionPolicy,
        ): ProjectionRequest =
            ProjectionRequest(
                target = ProjectionTarget.SimpleEntryMappingV1,
                subtree = subtree.index,
                include = TextContentInclude.TextAndCdata,
                attributes = attributes,
                textKey = textKey,
                repeatedChild = repeatedChild,
                keySpelling = keySpelling,
                collision = collision,
                limits = ProjectionLimits.default,
            )

        /** Explicit `TextContentV1` request over one subtree
         * (projection.rs). */
        fun textContent(subtree: NodeRef, include: TextContentInclude): ProjectionRequest =
            ProjectionRequest(
                target = ProjectionTarget.TextContentV1,
                subtree = subtree.index,
                include = include,
                attributes = AttributePolicy.RejectAttributes,
                textKey = TextKeyPolicy.RejectText,
                repeatedChild = RepeatedChildPolicy.Reject,
                keySpelling = ExpandedNameKeyPolicy.LocalOnly,
                collision = CollisionPolicy.Reject,
                limits = ProjectionLimits.default,
            )
    }
}

/** XML projection resource limits (projection.rs). */
data class ProjectionLimits(
    /** Maximum inspected source nodes. */
    val maxSourceNodes: Int,
    /** Maximum produced PortableValue nodes. */
    val maxValueNodes: Int,
    /** Maximum report events. */
    val maxReportEntries: Int,
    /** Maximum projected locations plus source origins. */
    val maxProvenanceUnits: Int,
) {
    companion object {
        /** The frozen defaults (projection.rs): 2,000,000 source
         * nodes, 2,000,000 value nodes, 100,000 report entries, 4,000,000
         * provenance units. */
        val default = ProjectionLimits(
            maxSourceNodes = 2_000_000,
            maxValueNodes = 2_000_000,
            maxReportEntries = 100_000,
            maxProvenanceUnits = 4_000_000,
        )
    }
}

/** Projection fidelity classification (projection.rs). */
enum class Fidelity {
    /** Target directly represents every native association. */
    Exact,

    /** An explicit reported policy transformed associations. */
    Transformed,

    /** Source facts were irreversibly omitted without a retained source
     * relation. */
    Lossy,
}

/** Projected value or association location (projection.rs). */
sealed class ProjectedLocation {
    /** Portable value location. */
    data class Value(val path: ValuePath) : ProjectedLocation()

    /** Portable association location. */
    data class Association(val location: AssociationLocation) : ProjectedLocation()
}

/** Source-to-projection relation (projection.rs). */
enum class ProvenanceRelation {
    /** Direct native semantic origin. */
    Direct,

    /** Container value derived from a source record. */
    Derived,

    /** Discarded occurrence related to the retained projected occurrence. */
    Collapsed,

    /** Semantic content derived from reference resolution. */
    ReferenceDerived,
}

/** One exact source origin (projection.rs). */
data class SourceOrigin(
    /** Source document snapshot. */
    val snapshot: SnapshotIdentity,
    /** Exact structural identity. */
    val node: NodeRef,
    /** Exact raw source range. */
    val span: Span,
    /** Source relation. */
    val relation: ProvenanceRelation,
)

/** One many-valued provenance entry (projection.rs). */
data class ProvenanceEntry(
    /** Projected value or association. */
    val projected: ProjectedLocation,
    /** Ordered source origins. */
    val origins: List<SourceOrigin>,
)

/** Immutable many-valued provenance mapping (projection.rs). */
class ProvenanceMap private constructor(private val entries: List<ProvenanceEntry>) {
    companion object {
        fun empty(): ProvenanceMap = ProvenanceMap(emptyList())

        internal fun of(entries: List<ProvenanceEntry>): ProvenanceMap = ProvenanceMap(entries)
    }

    /** Deterministically ordered projected locations and origins. */
    fun entries(): List<ProvenanceEntry> = entries

    override fun equals(other: Any?): Boolean =
        other is ProvenanceMap && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()
}

/** Projection report category (projection.rs). */
enum class ProjectionEventKind {
    /** Element discarded by policy. */
    ElementDiscarded,

    /** Attribute discarded by policy. */
    AttributeDiscarded,

    /** Text discarded by policy. */
    TextDiscarded,

    /** CDATA discarded by policy. */
    CdataDiscarded,

    /** Comment discarded by policy. */
    CommentDiscarded,

    /** Processing instruction discarded by policy. */
    ProcessingInstructionDiscarded,

    /** Reference distinction collapsed into resolved text. */
    ReferenceCollapsed,

    /** Repeated expanded child name collapsed under policy. */
    ChildCollapsed,

    /** Expanded-name namespace difference collapsed by key spelling. */
    NamespaceCollapsed,
}

/** One explicit transformation event (projection.rs). */
data class ProjectionEvent(
    /** Stable event kind. */
    val kind: ProjectionEventKind,
    /** Discarded source occurrence. */
    val discarded: NodeRef,
    /** Fidelity impact. */
    val impact: Fidelity,
)

/** Complete ordered projection report (projection.rs). */
class ProjectionReport private constructor(private val events: List<ProjectionEvent>) {
    companion object {
        fun empty(): ProjectionReport = ProjectionReport(emptyList())

        internal fun of(events: List<ProjectionEvent>): ProjectionReport = ProjectionReport(events)
    }

    /** Events in deterministic document order. */
    fun events(): List<ProjectionEvent> = events

    override fun equals(other: Any?): Boolean =
        other is ProjectionReport && events == other.events

    override fun hashCode(): Int = events.hashCode()
}

/** Complete successful projection (projection.rs). */
data class CompleteProjection(
    /** Complete immutable projected value. */
    val value: PortableValue,
    /** Worst operation fidelity. */
    val fidelity: Fidelity,
    /** Structured transformation report. */
    val report: ProjectionReport,
    /** Value and association provenance. */
    val provenance: ProvenanceMap,
)

/** Failed projection attempt without a partial value (projection.rs). */
data class FailedProjectionAttempt(
    /** Stable ordered diagnostics. */
    val diagnostics: List<XmlDiagnosticWithArguments>,
    /** Empty report: failed projections publish no partial transformation
     * result. */
    val report: ProjectionReport,
)

/** Projection completion algebra (projection.rs). */
sealed class ProjectionResult {
    /** Complete success. */
    data class Complete(val projection: CompleteProjection) : ProjectionResult()

    /** Failure with no value or provenance map. */
    data class Failed(val attempt: FailedProjectionAttempt) : ProjectionResult()
}

/** Stable XML projection failure (projection.rs). */
sealed class ProjectionFailure : Exception() {
    /** Recovered documents cannot publish partial semantic values. */
    data object RecoveredDocument : ProjectionFailure()

    /** The selected subtree is not an element. */
    data object SubtreeNotElement : ProjectionFailure()

    /** Simple-entry-mapping admission precondition failed. */
    data class MappingAdmission(val reason: String) : ProjectionFailure()

    /** Object collision under `Reject`. */
    data class Collision(val child: NodeRef, val key: String) : ProjectionFailure()

    /** Declared projection resource limit was reached. */
    data class ResourceLimit(val name: String) : ProjectionFailure()

    /** PortableValue construction invariant failed. */
    data object CoreInvariant : ProjectionFailure()

    /** The frozen diagnostic code (projection.rs). */
    fun code(): String =
        when (this) {
            RecoveredDocument -> "xml.projection.recovered-document@1"
            SubtreeNotElement -> "xml.projection.subtree@1"
            is MappingAdmission -> "xml.projection.admission@1"
            is Collision -> "xml.projection.collision@1"
            is ResourceLimit -> "xml.projection.resource-limit@1"
            CoreInvariant -> "xml.projection.core-invariant@1"
        }
}

/** Projects one snapshot under one explicit target and policy contract
 * (projection.rs). */
fun Document.project(request: ProjectionRequest): ProjectionResult {
    if (formationStatus != FormationStatus.Complete) {
        return failedProjection(ProjectionFailure.RecoveredDocument)
    }
    val context = ProjectionContext(this, request.limits)
    val result = when (request.target) {
        ProjectionTarget.ElementTreeV1 -> context.projectElementTree()
        ProjectionTarget.TextContentV1 ->
            context.projectTextContent(request.subtree, request.include)

        ProjectionTarget.SimpleEntryMappingV1 -> context.projectEntryMapping(request)
    }
    return when (result) {
        is ProjectionOutcome.Success -> ProjectionResult.Complete(
            CompleteProjection(
                value = result.value,
                fidelity = result.fidelity,
                report = context.report(),
                provenance = context.provenance(),
            ),
        )
        is ProjectionOutcome.Failure -> failedProjection(result.failure)
    }
}

private fun failedProjection(failure: ProjectionFailure): ProjectionResult {
    val diagnostic = XmlDiagnosticWithArguments(
        failure.code(),
        DiagnosticCategory.Projection,
        Severity.Error,
        null,
        mapOf("failure" to failure.name()),
        0u,
    )
    return ProjectionResult.Failed(
        FailedProjectionAttempt(listOf(diagnostic), ProjectionReport.empty()),
    )
}

/** The stable failure-kind name used by the diagnostic argument (the Rust
 * Debug rendering of the variant, projection.rs). */
private fun ProjectionFailure.name(): String =
    when (this) {
        ProjectionFailure.RecoveredDocument -> "RecoveredDocument"
        ProjectionFailure.SubtreeNotElement -> "SubtreeNotElement"
        is ProjectionFailure.MappingAdmission -> "MappingAdmission($reason)"
        is ProjectionFailure.Collision -> "Collision"
        is ProjectionFailure.ResourceLimit -> "ResourceLimit(${name})"
        ProjectionFailure.CoreInvariant -> "CoreInvariant"
    }

private sealed class ProjectionOutcome {
    data class Success(val value: PortableValue, val fidelity: Fidelity) : ProjectionOutcome()
    data class Failure(val failure: ProjectionFailure) : ProjectionOutcome()
}

/** Ordered mapping entries with their expanded-name identities
 * (projection.rs). */
private class EntrySet {
    val ordered = ArrayList<Pair<String, PortableValue>>()
    val seen = HashMap<String, Pair<Int, ExpandedName?>>()

    fun commit(ordinal: Int, key: String, value: PortableValue) {
        if (ordinal < ordered.size) {
            ordered[ordinal] = key to value
        } else {
            ordered.add(key to value)
        }
    }
}

/** One projection run over one immutable snapshot (projection.rs). */
private class ProjectionContext(
    private val document: Document,
    private val limits: ProjectionLimits,
) {
    private val reportEvents = ArrayList<ProjectionEvent>()
    private val provenanceEntries = ArrayList<ProvenanceEntry>()
    private var valueNodes = 0
    private var sourceNodes = 0

    fun report(): ProjectionReport = ProjectionReport.of(reportEvents.toList())

    fun provenance(): ProvenanceMap = ProvenanceMap.of(provenanceEntries.toList())

    private fun step() {
        sourceNodes += 1
        if (sourceNodes > limits.maxSourceNodes) {
            throw ProjectionFailure.ResourceLimit("max_source_nodes")
        }
    }

    private fun reserveValue(count: Int) {
        valueNodes += count
        if (valueNodes > limits.maxValueNodes) {
            throw ProjectionFailure.ResourceLimit("max_value_nodes")
        }
    }

    private fun event(kind: ProjectionEventKind, discarded: NodeRef, impact: Fidelity) {
        if (reportEvents.size + 1 > limits.maxReportEntries) {
            throw ProjectionFailure.ResourceLimit("max_report_entries")
        }
        reportEvents.add(ProjectionEvent(kind, discarded, impact))
    }

    private fun origin(
        projected: ProjectedLocation,
        node: NodeRef,
        span: Span,
        relation: ProvenanceRelation,
    ) {
        if (provenanceEntries.size + 1 > limits.maxProvenanceUnits) {
            throw ProjectionFailure.ResourceLimit("max_provenance_units")
        }
        provenanceEntries.add(
            ProvenanceEntry(
                projected,
                listOf(SourceOrigin(document.snapshotIdentity, node, span, relation)),
            ),
        )
    }

    private fun elementData(index: Int): XmlElementData = document.elementData(index)

    private fun elementNodeRef(index: Int): NodeRef =
        document.authority.nodeRef(index.toLong(), NodeRole.XmlElement)

    private fun occurrenceNodeRef(ordinal: Long, role: NodeRole): NodeRef =
        document.authority.nodeRef(ordinal, role)

    /** Value path of one item inside an ordered record array
     * (projection.rs). */
    private fun itemPath(container: ValuePath, field: String, index: Int): ValuePath =
        container.child(ValuePathSegment.ObjectValue(field))
            .child(ValuePathSegment.SequenceElement(index.toLong()))

    // -- element tree ---------------------------------------------------------

    /** Exact `xml.element-tree@1` record for the document root
     * (projection.rs). */
    fun projectElementTree(): ProjectionOutcome {
        val root = document.root() ?: return ProjectionOutcome.Failure(
            ProjectionFailure.MappingAdmission("missing root"),
        )
        val rootIndex = root.index
        val builder = ObjectBuilder()
        builder.insert("record", PvString("xml.element-tree@1"))
        document.declaration()?.let { declared ->
            builder.insert("declaration", declarationValue(declared))
        }
        document.doctype()?.let { doctype ->
            if (doctype.entities.isNotEmpty()) {
                val entityList = doctype.entities.map { entity ->
                    ObjectBuilder()
                        .insert("name", PvString(entity.name))
                        .insert("replacement", PvString(entity.replacement))
                        .build()
                }
                builder.insert("entities", PvArray(entityList))
            }
        }
        val rootPath = ValuePath.root().child(ValuePathSegment.ObjectValue("root"))
        val (rootValue, _) = elementValue(rootIndex, rootPath)
        builder.insert("root", rootValue)
        return ProjectionOutcome.Success(builder.build(), Fidelity.Exact)
    }

    private fun declarationValue(declared: XmlDeclarationData): PortableValue {
        val builder = ObjectBuilder()
        builder.insert("version", PvString(declared.version))
        declared.encoding?.let { (_, encoding) ->
            builder.insert("encoding", PvString(encoding))
        }
        declared.standalone?.let { (_, standalone) ->
            builder.insert("standalone", consema.core.PvBoolean(standalone))
        }
        return builder.build()
    }

    /** Recursive element record; `path` is the location of this element
     * record inside the projected value (projection.rs). */
    private fun elementValue(index: Int, path: ValuePath): Pair<PortableValue, Int> {
        step()
        val data = elementData(index)
        val span = data.span
        val namespaces = data.namespaces
        val attributes = data.attributes
        val children = data.children
        val builder = ObjectBuilder()
        val (namespace, local) = data.expanded?.let {
            it.namespace to it.local
        } ?: (null to data.qname.local)
        val name = ObjectBuilder()
            .insert("namespace", if (namespace == null) PvNull else PvString(namespace))
            .insert("local", PvString(local))
            .build()
        builder.insert("expanded-name", name)
        if (namespaces.isNotEmpty()) {
            val list = namespaces.mapIndexed { item, binding ->
                val bindingValue = ObjectBuilder()
                    .insert("prefix", if (binding.prefix == null) PvNull else PvString(binding.prefix))
                    .insert("uri", PvString(binding.uri))
                    .build()
                origin(
                    ProjectedLocation.Value(itemPath(path, "namespaces", item)),
                    occurrenceNodeRef(binding.ordinal, NodeRole.XmlNamespaceBinding),
                    binding.span,
                    ProvenanceRelation.Direct,
                )
                bindingValue
            }
            builder.insert("namespaces", PvArray(list))
        }
        if (attributes.isNotEmpty()) {
            val list = attributes.mapIndexed { item, attribute ->
                val attrNamespace = attribute.expanded?.namespace
                val attrLocal = attribute.expanded?.local ?: attribute.qname.local
                val attrName = ObjectBuilder()
                    .insert("namespace", if (attrNamespace == null) PvNull else PvString(attrNamespace))
                    .insert("local", PvString(attrLocal))
                    .build()
                val attributeValue = ObjectBuilder()
                    .insert("expanded-name", attrName)
                    .insert("value", PvString(attribute.normalizedValue))
                    .build()
                origin(
                    ProjectedLocation.Value(itemPath(path, "attributes", item)),
                    occurrenceNodeRef(attribute.ordinal, NodeRole.XmlAttribute),
                    attribute.span,
                    ProvenanceRelation.Direct,
                )
                attributeValue
            }
            builder.insert("attributes", PvArray(list))
        }
        if (children.isNotEmpty()) {
            val list = children.mapIndexed { item, child ->
                contentValue(child, itemPath(path, "content", item)).first
            }
            builder.insert("content", PvArray(list))
        }
        val value = builder.build()
        reserveValue(1)
        origin(
            ProjectedLocation.Value(path),
            elementNodeRef(index),
            span,
            ProvenanceRelation.Direct,
        )
        return value to index
    }

    /** One ordered content item record; `path` is the item's location
     * (projection.rs). */
    private fun contentValue(index: Int, path: ValuePath): Pair<PortableValue, Int> {
        step()
        return when (val content = document.nodes[index]) {
            is XmlContent.Element -> elementValue(index, path)
            is XmlContent.Text -> {
                val builder = ObjectBuilder()
                builder.insert("kind", PvString("text"))
                val fragments = content.data.fragments.mapIndexed { item, fragment ->
                    val fragmentValue = when (fragment) {
                        is ReferenceFragment.Literal -> ObjectBuilder()
                            .insert("kind", PvString("literal"))
                            .insert("text", PvString(fragment.text))
                            .build()

                        is ReferenceFragment.CharacterReference -> ObjectBuilder()
                            .insert("kind", PvString("character-reference"))
                            .insert("resolved", PvString(fragment.resolved.toString()))
                            .build()

                        is ReferenceFragment.PredefinedEntity -> ObjectBuilder()
                            .insert("kind", PvString("predefined-entity"))
                            .insert("name", PvString(fragment.name))
                            .insert("resolved", PvString(fragment.resolved))
                            .build()

                        is ReferenceFragment.GeneralEntity -> ObjectBuilder()
                            .insert("kind", PvString("general-entity"))
                            .insert("name", PvString(fragment.name))
                            .insert("resolved", PvString(fragment.resolved))
                            .build()
                    }
                    origin(
                        ProjectedLocation.Value(itemPath(path, "fragments", item)),
                        occurrenceNodeRef(content.data.ordinal, NodeRole.XmlEntityReference),
                        fragment.span,
                        ProvenanceRelation.ReferenceDerived,
                    )
                    fragmentValue
                }
                builder.insert("fragments", PvArray(fragments))
                val value = builder.build()
                reserveValue(1)
                origin(
                    ProjectedLocation.Value(path),
                    occurrenceNodeRef(content.data.ordinal, NodeRole.XmlText),
                    content.data.span,
                    ProvenanceRelation.Direct,
                )
                value to index
            }
            is XmlContent.Cdata -> {
                val value = ObjectBuilder()
                    .insert("kind", PvString("cdata"))
                    .insert("text", PvString(content.data.text))
                    .build()
                reserveValue(1)
                origin(
                    ProjectedLocation.Value(path),
                    occurrenceNodeRef(content.data.ordinal, NodeRole.XmlCdata),
                    content.data.span,
                    ProvenanceRelation.Direct,
                )
                value to index
            }
            is XmlContent.Comment -> {
                val value = ObjectBuilder()
                    .insert("kind", PvString("comment"))
                    .insert("text", PvString(content.data.text))
                    .build()
                reserveValue(1)
                origin(
                    ProjectedLocation.Value(path),
                    occurrenceNodeRef(content.data.ordinal, NodeRole.XmlComment),
                    content.data.span,
                    ProvenanceRelation.Direct,
                )
                value to index
            }
            is XmlContent.ProcessingInstruction -> {
                val builder = ObjectBuilder()
                builder.insert("kind", PvString("processing-instruction"))
                builder.insert("target", PvString(content.data.target))
                content.data.content?.let { (_, text) ->
                    builder.insert("content", PvString(text))
                }
                val value = builder.build()
                reserveValue(1)
                origin(
                    ProjectedLocation.Value(path),
                    occurrenceNodeRef(content.data.ordinal, NodeRole.XmlProcessingInstruction),
                    content.data.span,
                    ProvenanceRelation.Direct,
                )
                value to index
            }
            is XmlContent.ErrorRegion -> {
                val value = ObjectBuilder()
                    .insert("kind", PvString("error-region"))
                    .build()
                reserveValue(1)
                origin(
                    ProjectedLocation.Value(path),
                    occurrenceNodeRef(content.data.ordinal, NodeRole.XmlErrorRegion),
                    content.data.span,
                    ProvenanceRelation.Direct,
                )
                value to index
            }
        }
    }

    // -- text content (projection.rs) --------------------------------

    /** Always-transformed descendant text content. */
    fun projectTextContent(subtree: Long?, include: TextContentInclude): ProjectionOutcome {
        val rootIndex = document.root()?.index
            ?: return ProjectionOutcome.Failure(ProjectionFailure.MappingAdmission("missing root"))
        val start = if (subtree != null) {
            subtree.toInt()
        } else {
            rootIndex
        }
        if (document.nodes[start] !is XmlContent.Element) {
            return ProjectionOutcome.Failure(ProjectionFailure.SubtreeNotElement)
        }
        val out = StringBuilder()
        try {
            collectText(start, include, out)
            val value = PvString(out.toString())
            reserveValue(1)
            origin(
                ProjectedLocation.Value(ValuePath.root()),
                elementNodeRef(start),
                elementData(start).span,
                ProvenanceRelation.Derived,
            )
            return ProjectionOutcome.Success(value, Fidelity.Transformed)
        } catch (e: ProjectionFailure) {
            return ProjectionOutcome.Failure(e)
        }
    }

    private fun collectText(index: Int, include: TextContentInclude, out: StringBuilder) {
        val data = elementData(index)
        for (child in data.children) {
            when (val content = document.nodes[child]) {
                is XmlContent.Element -> {
                    event(
                        ProjectionEventKind.ElementDiscarded,
                        elementNodeRef(child),
                        Fidelity.Transformed,
                    )
                    for (attribute in content.data.attributes) {
                        event(
                            ProjectionEventKind.AttributeDiscarded,
                            occurrenceNodeRef(attribute.ordinal, NodeRole.XmlAttribute),
                            Fidelity.Transformed,
                        )
                    }
                    collectText(child, include, out)
                }
                is XmlContent.Text -> {
                    for (fragment in content.data.fragments) {
                        if (fragment is ReferenceFragment.Literal) {
                            continue
                        }
                        event(
                            ProjectionEventKind.ReferenceCollapsed,
                            occurrenceNodeRef(content.data.ordinal, NodeRole.XmlEntityReference),
                            Fidelity.Transformed,
                        )
                    }
                    // Semantic text: line ends are normalized to LF, matching
                    // every other text observation in the crate.
                    out.append(textSemantic(content.data))
                }
                is XmlContent.Cdata -> {
                    if (include == TextContentInclude.TextAndCdata) {
                        out.append(content.data.text)
                    } else {
                        event(
                            ProjectionEventKind.CdataDiscarded,
                            occurrenceNodeRef(content.data.ordinal, NodeRole.XmlCdata),
                            Fidelity.Transformed,
                        )
                    }
                }
                is XmlContent.Comment -> {
                    event(
                        ProjectionEventKind.CommentDiscarded,
                        occurrenceNodeRef(content.data.ordinal, NodeRole.XmlComment),
                        Fidelity.Transformed,
                    )
                }
                is XmlContent.ProcessingInstruction -> {
                    event(
                        ProjectionEventKind.ProcessingInstructionDiscarded,
                        occurrenceNodeRef(content.data.ordinal, NodeRole.XmlProcessingInstruction),
                        Fidelity.Transformed,
                    )
                }
                is XmlContent.ErrorRegion -> {}
            }
        }
    }

    // -- entry mapping (projection.rs) ------------------------------

    /** Explicit-policy entry mapping of one selected subtree. */
    fun projectEntryMapping(request: ProjectionRequest): ProjectionOutcome {
        val rootIndex = document.root()?.index
            ?: return ProjectionOutcome.Failure(ProjectionFailure.MappingAdmission("missing root"))
        val start = if (request.subtree != null) {
            request.subtree.toInt()
        } else {
            rootIndex
        }
        if (document.nodes[start] !is XmlContent.Element) {
            return ProjectionOutcome.Failure(ProjectionFailure.SubtreeNotElement)
        }
        val entries = EntrySet()
        try {
            mapChildren(start, ValuePath.root(), entries, request)
            val builder = EntryMappingBuilder()
            for ((key, value) in entries.ordered) {
                builder.push(PvString(key), value)
            }
            val value = builder.build()
            reserveValue(1)
            return ProjectionOutcome.Success(value, Fidelity.Transformed)
        } catch (e: ProjectionFailure) {
            return ProjectionOutcome.Failure(e)
        }
    }

    private fun keepFromRepeated(policy: RepeatedChildPolicy): KeepPolicy =
        when (policy) {
            RepeatedChildPolicy.Reject -> KeepPolicy.Reject
            RepeatedChildPolicy.First -> KeepPolicy.First
            RepeatedChildPolicy.Last -> KeepPolicy.Last
        }

    private fun keepFromCollision(policy: CollisionPolicy): KeepPolicy =
        when (policy) {
            CollisionPolicy.Reject -> KeepPolicy.Reject
            CollisionPolicy.First -> KeepPolicy.First
            CollisionPolicy.Last -> KeepPolicy.Last
        }

    /**
     * Resolves the entry ordinal under the explicit request policies
     * (projection.rs). A repeated *expanded name* is governed by
     * `repeated_child`; a key collision after key-spelling is governed by
     * `collision`.
     */
    private fun entryOrdinal(
        entries: EntrySet,
        key: String,
        candidate: ExpandedName?,
        request: ProjectionRequest,
        origin: NodeRef,
        collapse: ProjectionEventKind,
    ): Int {
        val keepRepeated = keepFromRepeated(request.repeatedChild)
        val keepCollision = keepFromCollision(request.collision)
        val existing = entries.seen[key]
        if (existing == null) {
            val ordinal = entries.ordered.size
            entries.seen[key] = ordinal to candidate
            return ordinal
        }
        val (position, existingName) = existing
        val repeated = existingName != null && candidate != null && existingName == candidate
        val keep = if (repeated) keepRepeated else keepCollision
        return when (keep) {
            KeepPolicy.Reject -> throw ProjectionFailure.Collision(origin, key)
            KeepPolicy.First, KeepPolicy.Last -> {
                val eventKind = if (repeated) {
                    collapse
                } else {
                    ProjectionEventKind.NamespaceCollapsed
                }
                event(eventKind, origin, Fidelity.Transformed)
                position
            }
        }
    }

    /** Records one committed entry and its value/association provenance
     * (projection.rs). */
    private fun commitEntry(
        entries: EntrySet,
        key: String,
        value: PortableValue,
        ordinal: Int,
        source: Pair<NodeRef, Span>,
        container: ValuePath,
    ) {
        entries.commit(ordinal, key, value)
        reserveValue(1)
        val association = AssociationLocation(
            container,
            ordinal.toLong(),
            AssociationRole.EntryMappingEntry,
        )
        origin(
            ProjectedLocation.Association(association),
            source.first,
            source.second,
            ProvenanceRelation.Direct,
        )
        origin(
            ProjectedLocation.Value(container.child(ValuePathSegment.EntryValue(ordinal.toLong()))),
            source.first,
            source.second,
            ProvenanceRelation.Direct,
        )
    }

    private fun mapChildren(
        element: Int,
        container: ValuePath,
        entries: EntrySet,
        request: ProjectionRequest,
    ) {
        val data = elementData(element)
        if (data.namespaces.isNotEmpty()) {
            throw ProjectionFailure.MappingAdmission("namespace declarations on the mapped element")
        }
        for (attribute in data.attributes) {
            val origin = occurrenceNodeRef(attribute.ordinal, NodeRole.XmlAttribute)
            when (request.attributes) {
                AttributePolicy.RejectAttributes -> throw ProjectionFailure.MappingAdmission(
                    "attributes present under RejectAttributes",
                )
                AttributePolicy.IgnoreAttributes -> {
                    event(
                        ProjectionEventKind.AttributeDiscarded,
                        origin,
                        Fidelity.Transformed,
                    )
                }
                AttributePolicy.PrefixAttributeKeys -> {
                    val key = "@${attribute.qname.local}"
                    val ordinal = entryOrdinal(
                        entries,
                        key,
                        null,
                        request,
                        origin,
                        ProjectionEventKind.AttributeDiscarded,
                    )
                    val value = PvString(attribute.normalizedValue)
                    commitEntry(
                        entries, key, value, ordinal,
                        origin to attribute.span, container,
                    )
                }
            }
        }
        for (child in data.children) {
            when (val content = document.nodes[child]) {
                is XmlContent.Element -> {
                    val childData = content.data
                    val (namespace, local) = childData.expanded?.let {
                        (it.namespace ?: "") to it.local
                    } ?: ("" to childData.qname.local)
                    val key = when (request.keySpelling) {
                        ExpandedNameKeyPolicy.LocalOnly -> local
                        ExpandedNameKeyPolicy.PrefixedSpelling ->
                            childData.qname.qname().asStr()

                        ExpandedNameKeyPolicy.UriBracketed -> "{${namespace}}$local"
                    }
                    val origin = elementNodeRef(child)
                    val ordinal = entryOrdinal(
                        entries,
                        key,
                        childData.expanded,
                        request,
                        origin,
                        ProjectionEventKind.ChildCollapsed,
                    )
                    val hasElementChildren = childData.children.any {
                        document.nodes[it] is XmlContent.Element
                    }
                    val childValue = if (hasElementChildren) {
                        val nestedContainer =
                            container.child(ValuePathSegment.EntryValue(ordinal.toLong()))
                        val nested = EntrySet()
                        mapChildren(child, nestedContainer, nested, request)
                        val nestedBuilder = EntryMappingBuilder()
                        for ((nestedKey, nestedValue) in nested.ordered) {
                            nestedBuilder.push(PvString(nestedKey), nestedValue)
                        }
                        nestedBuilder.build()
                    } else {
                        leafValue(child, request)
                    }
                    commitEntry(
                        entries,
                        key,
                        childValue,
                        ordinal,
                        origin to childData.span,
                        container,
                    )
                }
                is XmlContent.Text -> {
                    val origin = occurrenceNodeRef(content.data.ordinal, NodeRole.XmlText)
                    when (request.textKey) {
                        TextKeyPolicy.RejectText -> {
                            if (textSemantic(content.data).isNotBlank()) {
                                throw ProjectionFailure.MappingAdmission(
                                    "text content under RejectText",
                                )
                            }
                        }
                        TextKeyPolicy.IgnoreText -> {
                            event(
                                ProjectionEventKind.TextDiscarded,
                                origin,
                                Fidelity.Transformed,
                            )
                        }
                    }
                }
                is XmlContent.Cdata -> {
                    when (request.textKey) {
                        TextKeyPolicy.RejectText -> throw ProjectionFailure.MappingAdmission(
                            "CDATA content under RejectText",
                        )
                        TextKeyPolicy.IgnoreText -> {
                            event(
                                ProjectionEventKind.CdataDiscarded,
                                occurrenceNodeRef(content.data.ordinal, NodeRole.XmlCdata),
                                Fidelity.Transformed,
                            )
                        }
                    }
                }
                is XmlContent.Comment -> {
                    event(
                        ProjectionEventKind.CommentDiscarded,
                        occurrenceNodeRef(content.data.ordinal, NodeRole.XmlComment),
                        Fidelity.Transformed,
                    )
                }
                is XmlContent.ProcessingInstruction -> {
                    event(
                        ProjectionEventKind.ProcessingInstructionDiscarded,
                        occurrenceNodeRef(content.data.ordinal, NodeRole.XmlProcessingInstruction),
                        Fidelity.Transformed,
                    )
                }
                is XmlContent.ErrorRegion -> {}
            }
        }
    }

    /** The leaf value of one element without element children
     * (projection.rs). */
    private fun leafValue(element: Int, request: ProjectionRequest): PortableValue {
        val data = elementData(element)
        val text = StringBuilder()
        for (child in data.children) {
            when (val content = document.nodes[child]) {
                is XmlContent.Text -> text.append(textSemantic(content.data))
                is XmlContent.Cdata -> {
                    when (request.textKey) {
                        TextKeyPolicy.RejectText -> throw ProjectionFailure.MappingAdmission(
                            "CDATA content under RejectText",
                        )
                        TextKeyPolicy.IgnoreText -> {
                            event(
                                ProjectionEventKind.CdataDiscarded,
                                occurrenceNodeRef(content.data.ordinal, NodeRole.XmlCdata),
                                Fidelity.Transformed,
                            )
                        }
                    }
                }
                is XmlContent.Comment -> {
                    event(
                        ProjectionEventKind.CommentDiscarded,
                        occurrenceNodeRef(content.data.ordinal, NodeRole.XmlComment),
                        Fidelity.Transformed,
                    )
                }
                is XmlContent.ProcessingInstruction -> {
                    event(
                        ProjectionEventKind.ProcessingInstructionDiscarded,
                        occurrenceNodeRef(content.data.ordinal, NodeRole.XmlProcessingInstruction),
                        Fidelity.Transformed,
                    )
                }
                is XmlContent.Element, is XmlContent.ErrorRegion -> {}
            }
        }
        return PvString(text.toString())
    }
}
