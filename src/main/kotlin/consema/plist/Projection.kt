// Two-stage explicit projection: Document -> the `plist.value-tree@1`
// record (or the require-object target) with fidelity, report, and
// provenance.
//
// Data authority:
//   - RFC 0013 §9 (docs/rfcs/0013-plist-family-profiles-v1.md:598-633): the
//     default exact target `plist.projection.value-tree@1` produces the
//     versioned `plist.value-tree@1` PortableValue record (one root value,
//     ordered dictionary associations, ordered array elements, typed
//     leaves); UIDs project only under an explicit IncludeUid policy into a
//     typed UID member, never disguised as integers; unpaired-surrogate
//     strings fail ordinary projection atomically; the secondary target
//     `plist.projection.require-object@1` converts to a PortableValue Object
//     only when every key is a string and every value is a
//     string/integer/real/boolean, with a versioned Reject | First | Last
//     collision policy; any authorized collapse is Transformed and emits one
//     report event per discarded association while keeping retained and
//     discarded provenance.
//   - RFC 0004 §7-§8 (docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:171-218): the completion algebra and the
//     provenance direction (portable locations to source origins).
//   - conformance/vectors/plist-v1.json (plist.projection.*) pins the
//     per-case outcomes; crates/consema-plist/src/projection.rs is the
//     byte-arbitration authority (targets projection.rs:55-90, request
//     projection.rs:86-162, value-tree encoding projection.rs:572-667,
//     require-object projection.rs:671-830, failure codes projection.rs:
//     355-403).
//
// Kotlin-idiomatic design: the completion algebra is a sealed class, so
// exhaustive `when` over Complete/Failed can never meet an unknown outcome;
// failures carry their frozen plist-family code via the code mapping
// (projection.rs:393-401).

package consema.plist

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.EntryMappingBuilder
import consema.core.ObjectBuilder
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.document.FormationStatus
import consema.document.NodeRef
import consema.document.SnapshotIdentity
import consema.document.Span
import java.math.BigInteger

/** The fixed plist epoch spelling of the date record member (RFC 0013 §9;
 * materialization.rs:147). */
internal const val PLIST_EPOCH_SPELLING = "2001-01-01T00:00:00Z"

/** Versioned projection target contract (projection.rs:55-63). */
enum class ProjectionTarget {
    /** The versioned `plist.value-tree@1` record (RFC 0013 §9). */
    ValueTreeV1,

    /** Unique-key PortableValue Object over the root dictionary under one
     * explicit collision policy (RFC 0013 §9). */
    RequireObjectV1,
}

/** Explicit UID projection policy (projection.rs:65-73; RFC 0013 §9). */
enum class UidPolicy {
    /** Fail on every UID leaf (default). */
    Exclude,

    /** Project UIDs as the typed `{"uid": integer}` member. */
    Include,
}

/** Explicit duplicate-key collision policy (projection.rs:75-83; RFC 0013
 * §9). */
enum class CollisionPolicy {
    /** Fail atomically on the first duplicate key. */
    Reject,

    /** Retain the first occurrence and report every discarded later one. */
    First,

    /** Retain the last occurrence and report every discarded earlier one. */
    Last,
}

/** Plist projection resource limits (projection.rs:159-184). */
data class ProjectionLimits(
    /** Maximum inspected native value nodes. */
    val maxSourceNodes: Int,
    /** Maximum produced PortableValue nodes. */
    val maxValueNodes: Int,
    /** Maximum report events. */
    val maxReportEntries: Int,
    /** Maximum projected locations plus source origins. */
    val maxProvenanceUnits: Int,
) {
    companion object {
        /** The frozen defaults (projection.rs:175-183): 2,000,000 source
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

/** Projection fidelity classification (projection.rs:186-195). */
enum class Fidelity {
    /** Target directly represents every native association. */
    Exact,

    /** An explicit reported policy transformed associations. */
    Transformed,

    /** Source facts were irreversibly omitted without a retained source
     * relation. */
    Lossy,
}

/** Projected value or association location (projection.rs:199-204). */
sealed class ProjectedLocation {
    /** Portable value location. */
    data class Value(val path: ValuePath) : ProjectedLocation()

    /** Portable association location. */
    data class Association(val location: AssociationLocation) : ProjectedLocation()
}

/** Source-to-projection relation (projection.rs:206-217). */
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

/** One exact source origin (projection.rs:219-230). */
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

/** One many-valued provenance mapping entry (projection.rs:232-239). */
data class ProvenanceEntry(
    /** Projected value or association. */
    val projected: ProjectedLocation,
    /** Ordered source origins. */
    val origins: List<SourceOrigin>,
)

/** Immutable many-valued provenance mapping (projection.rs:241-270). */
class ProvenanceMap private constructor(private val entries: List<ProvenanceEntry>) {
    companion object {
        internal fun of(entries: List<ProvenanceEntry>): ProvenanceMap = ProvenanceMap(entries)
    }

    /** Deterministically ordered projected locations and origins. */
    fun entries(): List<ProvenanceEntry> = entries

    override fun equals(other: Any?): Boolean =
        other is ProvenanceMap && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()
}

/** Projection report category (projection.rs:272-278). */
enum class ProjectionEventKind {
    /** One duplicate-key association discarded under a First or Last
     * collision policy. */
    AssociationDiscarded,
}

/** One explicit transformation event (projection.rs:280-291). */
data class ProjectionEvent(
    /** Stable event kind. */
    val kind: ProjectionEventKind,
    /** Discarded source occurrence. */
    val discarded: NodeRef,
    /** Fidelity impact. */
    val impact: Fidelity,
)

/** Complete ordered projection report (projection.rs:293-301). */
class ProjectionReport private constructor(private val events: List<ProjectionEvent>) {
    companion object {
        internal fun new(events: List<ProjectionEvent>): ProjectionReport = ProjectionReport(events)
    }

    /** Ordered transformation events. */
    fun events(): List<ProjectionEvent> = events

    override fun equals(other: Any?): Boolean =
        other is ProjectionReport && events == other.events

    override fun hashCode(): Int = events.hashCode()
}

/** Explicit projection request (projection.rs:86-162; RFC 0013 §9). */
class ProjectionRequest private constructor(
    /** Exact target contract. */
    val target: ProjectionTarget,
    /** UID policy (value-tree target). */
    val uidPolicy: UidPolicy,
    /** Duplicate-key policy (require-object target). */
    val collision: CollisionPolicy,
    /** Resource limits. */
    val limits: ProjectionLimits,
) {
    companion object {
        /** The default exact target with UIDs excluded (projection.rs:96-
         * 105). */
        fun valueTree(): ProjectionRequest =
            ProjectionRequest(ProjectionTarget.ValueTreeV1, UidPolicy.Exclude,
                CollisionPolicy.Reject, ProjectionLimits.default)

        /** The value-tree target with an explicit UID policy (projection.rs:
         * 107-117). */
        fun valueTreeWithUid(policy: UidPolicy): ProjectionRequest =
            ProjectionRequest(ProjectionTarget.ValueTreeV1, policy,
                CollisionPolicy.Reject, ProjectionLimits.default)

        /** The require-object target with one collision policy (projection
         * .rs:119-128). */
        fun requireObject(collision: CollisionPolicy): ProjectionRequest =
            ProjectionRequest(ProjectionTarget.RequireObjectV1, UidPolicy.Exclude,
                collision, ProjectionLimits.default)
    }

    /** Replaces immutable projection limits (projection.rs:130-134). */
    fun withLimits(limits: ProjectionLimits): ProjectionRequest =
        ProjectionRequest(target, uidPolicy, collision, limits)
}

/** Complete successful projection (projection.rs:324-335). */
data class CompleteProjection(
    /** The projected PortableValue record. */
    val value: PortableValue,
    /** Whole-operation fidelity. */
    val fidelity: Fidelity,
    /** Complete ordered report. */
    val report: ProjectionReport,
    /** Complete projected-location-to-source-origin mapping. */
    val provenance: ProvenanceMap,
)

/** Failed projection attempt (projection.rs:337-344). */
data class FailedProjectionAttempt(
    /** Stable failure. */
    val failure: ProjectionFailure,
    /** Events discovered before failure. */
    val report: ProjectionReport,
    /** Stable paths analyzed before failure. */
    val analyzedInputPaths: List<ValuePath>,
)

/** Closed projection completion algebra (projection.rs:346-353). */
sealed class ProjectionResult {
    /** Complete success with every required artifact. */
    data class Complete(val projection: CompleteProjection) : ProjectionResult()

    /** Failed attempt without a partial value. */
    data class Failed(val attempt: FailedProjectionAttempt) : ProjectionResult()
}

/** Stable projection failure (projection.rs:355-375). The [code] is the
 * frozen `plist.projection.*@1` mapping (projection.rs:393-401). */
sealed class ProjectionFailure(val code: String) : Exception(code) {
    /** Recovered documents, or documents without a provable native value,
     * cannot publish partial semantic values. */
    data object IncompleteDocument : ProjectionFailure(PlistCodes.PROJECTION_INCOMPLETE_DOCUMENT)

    /** An unpaired-surrogate string or key cannot enter ordinary Unicode
     * projection. */
    data object UnpairedSurrogate : ProjectionFailure(PlistCodes.PROJECTION_UNPAIRED_SURROGATE)

    /** A duplicate key collided under Reject. */
    data class Collision(val key: String) : ProjectionFailure(PlistCodes.PROJECTION_COLLISION)

    /** A native fact the target cannot represent. */
    data class Unrepresentable(val fact: String) : ProjectionFailure(PlistCodes.PROJECTION_UNREPRESENTABLE)

    /** A declared projection resource limit was reached. */
    data class ResourceLimit(val name: String) : ProjectionFailure(PlistCodes.PROJECTION_RESOURCE_LIMIT)

    /** A PortableValue construction invariant failed. */
    data object CoreInvariant : ProjectionFailure(PlistCodes.PROJECTION_CORE_INVARIANT)
}

/**
 * Projects one complete plist document under one explicit target and policy
 * contract (RFC 0013 §9; projection.rs:412-444). The projection is atomic:
 * a recovered source, an unpaired-surrogate string, an unrepresentable
 * leaf, or a resource limit returns no partial value, provenance, or report
 * (hard gate 3).
 */
fun project(document: Document, request: ProjectionRequest): ProjectionResult {
    if (document.formationStatus != FormationStatus.Complete || document.nativeRoot == null) {
        return failed(ProjectionFailure.IncompleteDocument)
    }
    val context = ProjectionContext(document, request.limits)
    return try {
        when (request.target) {
            ProjectionTarget.ValueTreeV1 ->
                context.projectValueTree(request.uidPolicy)
            ProjectionTarget.RequireObjectV1 ->
                context.projectRequireObject(request.collision)
        }
    } catch (failure: ProjectionFailure) {
        failed(failure, context)
    }
}

private fun failed(
    failure: ProjectionFailure,
    context: ProjectionContext? = null,
): ProjectionResult = ProjectionResult.Failed(
    FailedProjectionAttempt(
        failure = failure,
        report = ProjectionReport.new(context?.events ?: emptyList()),
        analyzedInputPaths = context?.analyzed ?: emptyList(),
    ),
)

/** One projection execution context. */
private class ProjectionContext(
    private val document: Document,
    private val limits: ProjectionLimits,
) {
    val analyzed = ArrayList<ValuePath>()
    val events = ArrayList<ProjectionEvent>()
    private val provenanceEntries = ArrayList<ProvenanceEntry>()
    private var sourceNodes = 0
    private var valueNodes = 0
    private var depth = 0

    private fun stepSource() {
        sourceNodes += 1
        if (sourceNodes > limits.maxSourceNodes) {
            throw ProjectionFailure.ResourceLimit("max_source_nodes")
        }
    }

    private fun reserveValue(count: Int = 1) {
        valueNodes += count
        if (valueNodes > limits.maxValueNodes) {
            throw ProjectionFailure.ResourceLimit("max_value_nodes")
        }
    }

    private fun pushProvenance(projected: ProjectedLocation, origin: SourceOrigin) {
        if (provenanceEntries.size >= limits.maxProvenanceUnits) {
            throw ProjectionFailure.ResourceLimit("max_provenance_units")
        }
        provenanceEntries.add(ProvenanceEntry(projected, listOf(origin)))
    }

    private fun origin(
        projected: ProjectedLocation,
        node: NodeRef,
        relation: ProvenanceRelation,
    ) {
        pushProvenance(
            projected,
            SourceOrigin(document.snapshotIdentity, node, spanOf(node), relation),
        )
    }

    private fun spanOf(node: NodeRef): Span {
        val index = node.index.toInt()
        return if (index in document.entities.indices) {
            document.entities[index].span
        } else {
            document.authority.span(0, 0)
        }
    }

    private fun valueNodeRef(index: Int): NodeRef = document.nodeRef(index.toLong(), consema.document.NodeRole.PlistValue)

    private fun entryNodeRef(index: Int): NodeRef = document.nodeRef(index.toLong(), consema.document.NodeRole.PlistDictEntry)

    private fun keyNodeRef(index: Int): NodeRef = document.nodeRef(index.toLong(), consema.document.NodeRole.PlistKey)

    /** One key node of the root dictionary (the ObjectKey provenance origin;
     * projection.rs:798-802 uses a container-level key node). */
    private fun firstKeyNodeRef(dict: NativeValue.Dict): NodeRef {
        val first = dict.entries.firstOrNull()
        return if (first != null) {
            keyNodeRef(document.dictEntryEntity(first).keyIndex)
        } else {
            keyNodeRef(document.rootIndex)
        }
    }

    private fun elementNodeRef(index: Int): NodeRef =
        document.nodeRef(index.toLong(), consema.document.NodeRole.PlistArrayElement)

    private fun keyText(keyIndex: Int): String =
        (document.valueEntity(keyIndex).native as? NativeValue.StringV)?.string?.toUnicode()
            ?: throw ProjectionFailure.UnpairedSurrogate

    private fun stringValue(native: NativeValue.StringV): PortableValue =
        native.string.toUnicode()?.let { PvString(it) }
            ?: throw ProjectionFailure.UnpairedSurrogate

    private fun dateValue(seconds: Double): PortableValue {
        val builder = ObjectBuilder()
        builder.insert("epoch", PvString(PLIST_EPOCH_SPELLING))
        builder.insert("seconds", PvBinaryFloat64.fromFloat(seconds))
        return builder.build()
    }

    private fun uidValue(uid: PlistUid): PortableValue {
        val builder = ObjectBuilder()
        builder.insert("uid", PvInteger(BigInteger.valueOf(uid.toLong())))
        return builder.build()
    }

    // ------------------------------------------------------------------
    // Value-tree target
    // ------------------------------------------------------------------

    fun projectValueTree(uidPolicy: UidPolicy): ProjectionResult.Complete {
        val rootPath = ValuePath.root().child(ValuePathSegment.ObjectValue("root"))
        val rootValue = valueOf(document.rootIndex, rootPath, uidPolicy)
        reserveValue(1)
        val builder = ObjectBuilder()
        builder.insert("record", PvString("plist.value-tree@1"))
        builder.insert("root", rootValue)
        return ProjectionResult.Complete(
            CompleteProjection(
                value = builder.build(),
                fidelity = Fidelity.Exact,
                report = ProjectionReport.new(events),
                provenance = ProvenanceMap.of(provenanceEntries),
            ),
        )
    }

    private fun valueOf(index: Int, path: ValuePath, uidPolicy: UidPolicy): PortableValue {
        stepSource()
        reserveValue(1)
        val native = document.valueEntity(index).native
            ?: throw ProjectionFailure.IncompleteDocument
        val projected = when (native) {
            is NativeValue.Dict -> {
                val builder = EntryMappingBuilder()
                for (entryIndex in native.entries) {
                    val entry = document.dictEntryEntity(entryIndex)
                    val key = keyText(entry.keyIndex)
                    origin(
                        ProjectedLocation.Association(
                            AssociationLocation(path, entry.ordinal.toLong(),
                                AssociationRole.EntryMappingEntry),
                        ),
                        entryNodeRef(entryIndex),
                        ProvenanceRelation.Direct,
                    )
                    origin(
                        ProjectedLocation.Value(path.child(ValuePathSegment.EntryKey(entry.ordinal.toLong()))),
                        keyNodeRef(entry.keyIndex),
                        ProvenanceRelation.Direct,
                    )
                    val entryPath = path.child(ValuePathSegment.EntryValue(entry.ordinal.toLong()))
                    val child = valueOf(entry.valueIndex, entryPath, uidPolicy)
                    builder.push(PvString(key), child)
                }
                builder.build()
            }
            is NativeValue.Array -> {
                val items = ArrayList<PortableValue>()
                for (elementIndex in native.elements) {
                    val element = document.arrayElementEntity(elementIndex)
                    origin(
                        ProjectedLocation.Value(path.child(ValuePathSegment.SequenceElement(element.ordinal.toLong()))),
                        elementNodeRef(elementIndex),
                        ProvenanceRelation.Direct,
                    )
                    val elementPath = path.child(ValuePathSegment.SequenceElement(element.ordinal.toLong()))
                    items.add(valueOf(element.valueIndex, elementPath, uidPolicy))
                }
                PvArray(items)
            }
            is NativeValue.StringV -> stringValue(native)
            is NativeValue.Integer -> PvInteger(BigInteger.valueOf(native.value))
            is NativeValue.Real -> PvBinaryFloat64.fromFloat(native.real.asDouble())
            is NativeValue.BooleanV -> PvBoolean(native.value)
            is NativeValue.Date -> dateValue(native.seconds)
            is NativeValue.Data -> PvBytes.of(native.data.bytes())
            is NativeValue.Uid -> when (uidPolicy) {
                UidPolicy.Exclude -> throw ProjectionFailure.Unrepresentable("uid")
                UidPolicy.Include -> uidValue(native.uid)
            }
        }
        origin(
            ProjectedLocation.Value(path),
            valueNodeRef(index),
            ProvenanceRelation.Direct,
        )
        return projected
    }

    // ------------------------------------------------------------------
    // Require-object target
    // ------------------------------------------------------------------

    fun projectRequireObject(collision: CollisionPolicy): ProjectionResult.Complete {
        stepSource()
        reserveValue(1)
        val rootNative = document.valueEntity(document.rootIndex).native
            ?: throw ProjectionFailure.IncompleteDocument
        val dict = rootNative as? NativeValue.Dict
            ?: throw ProjectionFailure.Unrepresentable("root-not-dict")
        val seen = HashMap<String, Int>()
        val retained = ArrayList<RetainedOccurrence?>()
        val discards = ArrayList<MutableList<DiscardedOccurrence>>()
        var fidelity = Fidelity.Exact
        for (entryIndex in dict.entries) {
            val entry = document.dictEntryEntity(entryIndex)
            val key = keyText(entry.keyIndex)
            stepSource()
            reserveValue(1)
            val valueNative = document.valueEntity(entry.valueIndex).native
                ?: throw ProjectionFailure.IncompleteDocument
            val scalar = when (valueNative) {
                is NativeValue.StringV -> stringValue(valueNative)
                is NativeValue.Integer -> PvInteger(BigInteger.valueOf(valueNative.value))
                is NativeValue.Real -> PvBinaryFloat64.fromFloat(valueNative.real.asDouble())
                is NativeValue.BooleanV -> PvBoolean(valueNative.value)
                is NativeValue.Date -> throw ProjectionFailure.Unrepresentable("date")
                is NativeValue.Data -> throw ProjectionFailure.Unrepresentable("data")
                is NativeValue.Uid -> throw ProjectionFailure.Unrepresentable("uid")
                is NativeValue.Dict -> throw ProjectionFailure.Unrepresentable("dict")
                is NativeValue.Array -> throw ProjectionFailure.Unrepresentable("array")
            }
            val entryRef = entryNodeRef(entryIndex)
            val valueRef = valueNodeRef(entry.valueIndex)
            val position = seen[key]
            if (position == null) {
                val at = retained.size
                seen[key] = at
                retained.add(
                    RetainedOccurrence(
                        key = key,
                        value = scalar,
                        entry = entryRef,
                        valueNode = valueRef,
                    ),
                )
                discards.add(ArrayList())
            } else {
                when (collision) {
                    CollisionPolicy.Reject -> throw ProjectionFailure.Collision(key)
                    CollisionPolicy.First -> {
                        fidelity = Fidelity.Transformed
                        events.add(
                            ProjectionEvent(ProjectionEventKind.AssociationDiscarded,
                                entryRef, Fidelity.Transformed),
                        )
                        discards[position].add(
                            DiscardedOccurrence(key = key, entry = entryRef, valueNode = valueRef),
                        )
                    }
                    CollisionPolicy.Last -> {
                        fidelity = Fidelity.Transformed
                        val previous = retained[position]
                            ?: throw ProjectionFailure.CoreInvariant
                        events.add(
                            ProjectionEvent(ProjectionEventKind.AssociationDiscarded,
                                previous.entry, Fidelity.Transformed),
                        )
                        discards[position].add(
                            DiscardedOccurrence(
                                key = previous.key,
                                entry = previous.entry,
                                valueNode = previous.valueNode,
                            ),
                        )
                        retained[position] = RetainedOccurrence(
                            key = key,
                            value = scalar,
                            entry = entryRef,
                            valueNode = valueRef,
                        )
                    }
                }
            }
        }
        // Provenance follows the final retained object (RFC 0013 §9).
        val builder = ObjectBuilder()
        for ((position, slot) in retained.withIndex()) {
            val occurrence = slot ?: continue
            builder.insert(occurrence.key, occurrence.value)
            origin(
                ProjectedLocation.Association(
                    AssociationLocation(ValuePath.root(), position.toLong(),
                        AssociationRole.ObjectEntry),
                ),
                occurrence.entry,
                ProvenanceRelation.Direct,
            )
            origin(
                ProjectedLocation.Association(
                    AssociationLocation(ValuePath.root(), position.toLong(),
                        AssociationRole.ObjectKey),
                ),
                firstKeyNodeRef(dict),
                ProvenanceRelation.Direct,
            )
            origin(
                ProjectedLocation.Value(
                    ValuePath.root().child(ValuePathSegment.ObjectValue(occurrence.key)),
                ),
                occurrence.valueNode,
                ProvenanceRelation.Direct,
            )
            for (discarded in discards[position]) {
                origin(
                    ProjectedLocation.Association(
                        AssociationLocation(ValuePath.root(), position.toLong(),
                            AssociationRole.ObjectEntry),
                    ),
                    discarded.entry,
                    ProvenanceRelation.Collapsed,
                )
                origin(
                    ProjectedLocation.Value(
                        ValuePath.root().child(ValuePathSegment.ObjectValue(discarded.key)),
                    ),
                    discarded.valueNode,
                    ProvenanceRelation.Collapsed,
                )
            }
        }
        return ProjectionResult.Complete(
            CompleteProjection(
                value = builder.build(),
                fidelity = fidelity,
                report = ProjectionReport.new(events),
                provenance = ProvenanceMap.of(provenanceEntries),
            ),
        )
    }

    private data class RetainedOccurrence(
        val key: String,
        val value: PortableValue,
        val entry: NodeRef,
        val valueNode: NodeRef,
    )

    private data class DiscardedOccurrence(
        val key: String,
        val entry: NodeRef,
        val valueNode: NodeRef,
    )
}
