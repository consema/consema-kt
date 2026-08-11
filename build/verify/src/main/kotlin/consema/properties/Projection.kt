// Two-stage explicit projection: Document -> PortableValue with fidelity,
// report, and provenance under the exact-entry-mapping and require-object
// targets.
//
// Data authority:
//   - RFC 0010 §11 (docs/rfcs/0010-java-properties-profiles-v1.md:310-349):
//     java-properties.projection.best-exact-entry-mapping@1 produces one
//     source-ordered EntryMapping association per property and fails
//     atomically on any unpaired surrogate; RequireObject accepts only
//     well-formed String keys under RequireUnique / FirstWins /
//     LastWinsJdkTable, collapsing only under an explicitly authorized Lossy
//     policy with one event per discarded association; the authorizing rule
//     of a collapse event is java-properties.duplicate-key.first-wins@1 or
//     java-properties.duplicate-key.last-wins-jdk-table@1.
//   - conformance/vectors/java-properties-v1.json pins the fidelity, event
//     counts, first/last entry facts, provenance relations, and failure
//     codes (projection.* cases, lines 74-89).
//   - crates/consema-properties/src/projection.rs is the byte-arbitration
//     authority (targets projection.rs:9-27, request projection.rs:29-82,
//     limits projection.rs:84-106, failure codes projection.rs:741-752,
//     exact projection.rs:430-497, object projection.rs:499-648).
//     go/properties/projection.go is a cross-reference only.
//   - Value paths come from the L0 core agent (consema.core.ValuePath /
//     ValuePathSegment / AssociationLocation / AssociationRole mirroring
//     crates/consema-core/src/location.rs:1-89), the same dependency the
//     JSON family declares (kotlin/.../json/Projection.kt:21-24).
//
// Kotlin-idiomatic design: the completion algebra is a sealed class, so
// exhaustive `when` over Complete/Failed can never meet an unknown outcome;
// failures carry their frozen registered code via [projectionCode]
// (projection.rs:741-752).

package consema.properties

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.EntryMappingBuilder
import consema.core.ObjectBuilder
import consema.core.PortableValue
import consema.core.PvString
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.document.FormationStatus
import consema.document.NodeRef
import consema.document.SnapshotIdentity
import consema.document.Span
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/** Versioned Java Properties projection target (projection.rs:9-16). */
enum class ProjectionTarget {
    /** Source-ordered EntryMapping preserving every association. */
    BestExactEntryMappingV1,

    /** Unique-key Object under one explicit duplicate policy. */
    RequireObjectV1,
}

/** Explicit duplicate behavior for [ProjectionTarget.RequireObjectV1]
 * (projection.rs:18-27). */
enum class DuplicatePolicy {
    /** Reject every duplicate key. */
    RequireUnique,

    /** Retain the first occurrence in source order. */
    FirstWins,

    /** Retain the last occurrence, matching a newly loaded JDK Properties
     * table. */
    LastWinsJdkTable,
}

/** Java Properties projection limits (projection.rs:84-106). */
data class ProjectionLimits(
    /** Maximum source property associations inspected. */
    val maxSourceAssociations: Int,
    /** Maximum produced PortableValue nodes. */
    val maxValueNodes: Int,
    /** Maximum report events. */
    val maxReportEntries: Int,
    /** Maximum projected locations plus source origins. */
    val maxProvenanceUnits: Int,
) {
    companion object {
        /** The frozen defaults (projection.rs:97-106). */
        val default = ProjectionLimits(
            maxSourceAssociations = 2_000_000,
            maxValueNodes = 4_000_001,
            maxReportEntries = 100_000,
            maxProvenanceUnits = 8_000_000,
        )
    }
}

/** Immutable explicit Properties projection request (projection.rs:29-82). */
data class ProjectionRequest(
    /** Frozen target contract. */
    val target: ProjectionTarget,
    /** Explicit Object duplicate policy. */
    val duplicatePolicy: DuplicatePolicy,
    /** Projection resource limits. */
    val limits: ProjectionLimits,
) {
    companion object {
        /** Exact default that preserves every property occurrence
         * (projection.rs:39-47). */
        fun bestExactEntryMapping(): ProjectionRequest =
            ProjectionRequest(
                ProjectionTarget.BestExactEntryMappingV1,
                DuplicatePolicy.RequireUnique,
                ProjectionLimits.default,
            )

        /** Explicit unique Object request (projection.rs:49-56). */
        fun requireObject(duplicatePolicy: DuplicatePolicy): ProjectionRequest =
            ProjectionRequest(
                ProjectionTarget.RequireObjectV1,
                duplicatePolicy,
                ProjectionLimits.default,
            )
    }

    /** Replaces immutable resource limits (projection.rs:58-62). */
    fun withLimits(limits: ProjectionLimits): ProjectionRequest =
        copy(limits = limits)
}

/** Projection fidelity classification (projection.rs:108-117). */
enum class Fidelity {
    /** Target directly represents every native association. */
    Exact,

    /** Complete semantics survive an explicit reversible re-encoding. */
    Transformed,

    /** At least one source fact cannot be recovered from the projected value
     * and report. */
    Lossy,
}

/** Projected value or association location (projection.rs:119-126). */
sealed class ProjectedLocation {
    /** Portable value location. */
    data class Value(val path: ValuePath) : ProjectedLocation()

    /** Portable association location. */
    data class Association(val location: AssociationLocation) : ProjectedLocation()
}

/** Source-to-projection relation (projection.rs:128-143). */
enum class ProvenanceRelation {
    /** Direct property-association origin. */
    Direct,

    /** Root value derived from the complete document. */
    Derived,

    /** Raw source fragment contributing to a key. */
    KeyFragment,

    /** Raw source fragment contributing to a value. */
    ValueFragment,

    /** Escape source spelling contributing Java UTF-16 code units. */
    EscapeDerived,

    /** Discarded duplicate related to the retained projected association. */
    Collapsed,
}

/** One exact source origin (projection.rs:145-156). */
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

/** One many-valued provenance entry (projection.rs:158-165). */
data class ProvenanceEntry(
    /** Projected value or association. */
    val projected: ProjectedLocation,
    /** Ordered source origins. */
    val origins: List<SourceOrigin>,
)

/** Immutable many-valued provenance mapping (projection.rs:167-179). */
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

/** One explicit duplicate-collapse event (projection.rs:181-196). */
data class ProjectionEvent(
    /** Stable event code (java-properties.projection.duplicate-collapsed@1). */
    val code: String,
    /** Policy that authorized the transformation. */
    val policy: DuplicatePolicy,
    /** Discarded source occurrence. */
    val discarded: NodeRef,
    /** Retained source occurrence. */
    val retained: NodeRef,
    /** Association produced from the retained occurrence. */
    val projected: AssociationLocation,
    /** Fidelity impact. */
    val impact: Fidelity,
)

/** Complete ordered projection report (projection.rs:198-210). */
class ProjectionReport private constructor(private val events: List<ProjectionEvent>) {
    companion object {
        internal val EMPTY = ProjectionReport(emptyList())

        internal fun of(events: List<ProjectionEvent>): ProjectionReport = ProjectionReport(events)
    }

    /** Events in deterministic discarded-source order. */
    fun events(): List<ProjectionEvent> = events

    override fun equals(other: Any?): Boolean =
        other is ProjectionReport && events == other.events

    override fun hashCode(): Int = events.hashCode()
}

/** Complete successful projection; its value is never partial
 * (projection.rs:212-223). */
data class CompleteProjection(
    /** Complete immutable mapping. */
    val value: PortableValue,
    /** Worst operation fidelity. */
    val fidelity: Fidelity,
    /** Structured duplicate-collapse report. */
    val report: ProjectionReport,
    /** Value and association provenance. */
    val provenance: ProvenanceMap,
)

/** Failed projection attempt without a partial value (projection.rs:225-232). */
data class FailedProjectionAttempt(
    /** Stable ordered diagnostics. */
    val diagnostics: List<Diagnostic>,
    /** Empty report: failed projections publish no partial transformation. */
    val report: ProjectionReport,
)

/** Projection completion algebra (projection.rs:234-241). */
sealed class ProjectionResult {
    /** Complete success. */
    data class Complete(val projection: CompleteProjection) : ProjectionResult()

    /** Failure with no value or provenance map. */
    data class Failed(val attempt: FailedProjectionAttempt) : ProjectionResult()
}

/** Internal projection failure classification (projection.rs:243-262). */
internal sealed class ProjectionFailure {
    data object RecoveredDocument : ProjectionFailure()

    data class UnpairedSurrogate(val property: NodeRef, val component: StringComponent) :
        ProjectionFailure()

    data class DuplicateKey(val retained: NodeRef, val duplicate: NodeRef) : ProjectionFailure()

    data class ResourceLimit(val name: String) : ProjectionFailure()

    data object CoreInvariant : ProjectionFailure()
}

internal enum class StringComponent { Key, Value }

/**
 * Projects this snapshot under one explicit target and duplicate contract
 * (projection.rs:264-306). A failure contains no partial value.
 */
fun Document.project(request: ProjectionRequest): ProjectionResult {
    if (formationStatus != FormationStatus.Complete) {
        return failed(this, ProjectionFailure.RecoveredDocument)
    }
    if (propertyEntities.size > request.limits.maxSourceAssociations) {
        return failed(this, ProjectionFailure.ResourceLimit("max_source_associations"))
    }
    for (property in propertyEntities) {
        if (property.key.status == JavaStringStatus.UnpairedSurrogate) {
            return failed(
                this,
                ProjectionFailure.UnpairedSurrogate(property.node, StringComponent.Key),
            )
        }
        if (property.value.status == JavaStringStatus.UnpairedSurrogate) {
            return failed(
                this,
                ProjectionFailure.UnpairedSurrogate(property.node, StringComponent.Value),
            )
        }
    }
    return when (request.target) {
        ProjectionTarget.BestExactEntryMappingV1 -> projectExact(request)
        ProjectionTarget.RequireObjectV1 -> projectObject(request)
    }
}

private fun Document.projectExact(request: ProjectionRequest): ProjectionResult {
    val requiredNodes = propertyEntities.size.toLong() * 2 + 1
    if (requiredNodes > request.limits.maxValueNodes.toLong()) {
        return failed(this, ProjectionFailure.ResourceLimit("max_value_nodes"))
    }
    val context = Context(this, request)
    val root = ValuePath.root()
    val mapping = EntryMappingBuilder()
    for ((ordinal, property) in propertyEntities.withIndex()) {
        val association = AssociationLocation(
            root,
            ordinal.toLong(),
            AssociationRole.EntryMappingEntry,
        )
        context.addOrigin(
            ProjectedLocation.Association(association),
            property.node,
            property.span,
            ProvenanceRelation.Direct,
        ) ?: return failed(this, context.failure())
        context.addStringOrigins(
            ProjectedLocation.Value(root.child(ValuePathSegment.EntryKey(ordinal.toLong()))),
            ordinal,
            StringComponent.Key,
        ) ?: return failed(this, context.failure())
        context.addStringOrigins(
            ProjectedLocation.Value(root.child(ValuePathSegment.EntryValue(ordinal.toLong()))),
            ordinal,
            StringComponent.Value,
        ) ?: return failed(this, context.failure())
        mapping.push(
            PvString(property.key.toUnicode()),
            PvString(property.value.toUnicode()),
        )
    }
    context.addRootOrigin() ?: return failed(this, context.failure())
    return ProjectionResult.Complete(
        CompleteProjection(
            value = mapping.build(),
            fidelity = context.fidelity,
            report = ProjectionReport.of(context.report),
            provenance = ProvenanceMap.of(context.provenance),
        ),
    )
}

private fun Document.projectObject(request: ProjectionRequest): ProjectionResult {
    val keys = propertyEntities.map { it.key.toUnicode() }
    val selection = selectIndices(this, keys, request.duplicatePolicy)
    val retained: List<Int> = when (selection) {
        is Selection.Indices -> selection.indices
        is Selection.Duplicate -> return failed(
            this,
            ProjectionFailure.DuplicateKey(selection.retained, selection.duplicate),
        )
    }
    val requiredNodes = retained.size.toLong() + 1
    if (requiredNodes > request.limits.maxValueNodes.toLong()) {
        return failed(this, ProjectionFailure.ResourceLimit("max_value_nodes"))
    }
    val context = Context(this, request)
    val root = ValuePath.root()
    val retainedSet = retained.toSet()
    val retainedByKey = HashMap<String, Int>()
    for (index in retained) {
        retainedByKey[keys[index]] = index
    }
    val projectedOrdinal = HashMap<Int, Int>()
    retained.forEachIndexed { projected, source -> projectedOrdinal[source] = projected }
    for (sourceIndex in propertyEntities.indices) {
        if (sourceIndex in retainedSet) {
            continue
        }
        val retainedIndex = retainedByKey[keys[sourceIndex]]!!
        val ordinal = projectedOrdinal[retainedIndex]!!
        val location = AssociationLocation(
            root,
            ordinal.toLong(),
            AssociationRole.ObjectEntry,
        )
        context.pushEvent(
            ProjectionEvent(
                code = "java-properties.projection.duplicate-collapsed@1",
                policy = request.duplicatePolicy,
                discarded = propertyEntities[sourceIndex].node,
                retained = propertyEntities[retainedIndex].node,
                projected = location,
                impact = Fidelity.Lossy,
            ),
        ) ?: return failed(this, context.failure())
        context.addOrigin(
            ProjectedLocation.Association(location),
            propertyEntities[sourceIndex].node,
            propertyEntities[sourceIndex].span,
            ProvenanceRelation.Collapsed,
        ) ?: return failed(this, context.failure())
    }

    val objectBuilder = ObjectBuilder()
    for ((projected, propertyIndex) in retained.withIndex()) {
        val property = propertyEntities[propertyIndex]
        val association = AssociationLocation(
            root,
            projected.toLong(),
            AssociationRole.ObjectEntry,
        )
        context.addOrigin(
            ProjectedLocation.Association(association),
            property.node,
            property.span,
            ProvenanceRelation.Direct,
        ) ?: return failed(this, context.failure())
        context.addStringOrigins(
            ProjectedLocation.Association(
                AssociationLocation(root, projected.toLong(), AssociationRole.ObjectKey),
            ),
            propertyIndex,
            StringComponent.Key,
        ) ?: return failed(this, context.failure())
        context.addStringOrigins(
            ProjectedLocation.Value(
                root.child(ValuePathSegment.ObjectValue(keys[propertyIndex])),
            ),
            propertyIndex,
            StringComponent.Value,
        ) ?: return failed(this, context.failure())
        objectBuilder.insert(keys[propertyIndex], PvString(property.value.toUnicode()))
    }
    context.addRootOrigin() ?: return failed(this, context.failure())
    return ProjectionResult.Complete(
        CompleteProjection(
            value = objectBuilder.build(),
            fidelity = context.fidelity,
            report = ProjectionReport.of(context.report),
            provenance = ProvenanceMap.of(context.provenance),
        ),
    )
}

/** The closed retention selection outcome (projection.rs:613-648). */
private sealed class Selection {
    /** Ordered retained source indices. */
    data class Indices(val indices: List<Int>) : Selection()

    /** The first rejected duplicate pair under RequireUnique. */
    data class Duplicate(val retained: NodeRef, val duplicate: NodeRef) : Selection()
}

private fun selectIndices(
    document: Document,
    keys: List<String>,
    policy: DuplicatePolicy,
): Selection {
    val firstByKey = HashMap<String, Int>()
    for ((index, key) in keys.withIndex()) {
        val first = firstByKey[key]
        if (first != null) {
            if (policy == DuplicatePolicy.RequireUnique) {
                return Selection.Duplicate(
                    document.propertyEntities[first].node,
                    document.propertyEntities[index].node,
                )
            }
        } else {
            firstByKey[key] = index
        }
    }
    return Selection.Indices(
        when (policy) {
            DuplicatePolicy.RequireUnique, DuplicatePolicy.FirstWins -> {
                val seen = HashSet<String>()
                keys.indices.filter { seen.add(keys[it]) }
            }
            DuplicatePolicy.LastWinsJdkTable -> {
                val seen = HashSet<String>()
                val retained = keys.indices.reversed().filter { seen.add(keys[it]) }
                retained.reversed()
            }
        },
    )
}

/** The projection context (projection.rs:308-428). */
private class Context(
    val document: Document,
    val request: ProjectionRequest,
) {
    val provenance = ArrayList<ProvenanceEntry>()
    var provenanceUnits = 0
    val report = ArrayList<ProjectionEvent>()
    var fidelity: Fidelity = Fidelity.Exact
    private var failure: ProjectionFailure? = null

    fun failure(): ProjectionFailure = failure ?: ProjectionFailure.CoreInvariant

    fun addOrigin(
        projected: ProjectedLocation,
        node: NodeRef,
        span: Span,
        relation: ProvenanceRelation,
    ): ProjectionFailure? {
        val newLocation = provenance.none { it.projected == projected }
        val increment = if (newLocation) 2 else 1
        provenanceUnits += increment
        if (provenanceUnits > request.limits.maxProvenanceUnits) {
            failure = ProjectionFailure.ResourceLimit("max_provenance_units")
            return failure
        }
        val origin = SourceOrigin(document.snapshotIdentity, node, span, relation)
        val existing = provenance.firstOrNull { it.projected == projected }
        if (existing != null) {
            val origins = existing.origins.toMutableList()
            if (relation == ProvenanceRelation.Direct) {
                origins.add(0, origin)
            } else {
                origins.add(origin)
            }
            provenance[provenance.indexOf(existing)] =
                ProvenanceEntry(projected, origins)
        } else {
            provenance.add(ProvenanceEntry(projected, listOf(origin)))
        }
        return null
    }

    /** Key/value fragment and escape origins (projection.rs:364-404). */
    fun addStringOrigins(
        projected: ProjectedLocation,
        propertyIndex: Int,
        component: StringComponent,
    ): ProjectionFailure? {
        val property = document.propertyEntity(propertyIndex)
        val (fragments, relation) = when (component) {
            StringComponent.Key -> property.keyFragments to ProvenanceRelation.KeyFragment
            StringComponent.Value -> property.valueFragments to ProvenanceRelation.ValueFragment
        }
        if (fragments.isEmpty()) {
            val anchor = when (component) {
                StringComponent.Key -> property.keyAnchor
                StringComponent.Value -> property.valueAnchor
            }
            addOrigin(projected, property.node, anchor, relation)?.let { return it }
        } else {
            for (span in fragments) {
                addOrigin(projected, property.node, span, relation)?.let { return it }
            }
        }
        for (escapeIndex in property.escapeIndices) {
            val escape = document.escapeEntity(escapeIndex)
            if (escape.inKey == (component == StringComponent.Key)) {
                addOrigin(
                    projected,
                    escape.node,
                    escape.span,
                    ProvenanceRelation.EscapeDerived,
                )?.let { return it }
            }
        }
        return null
    }

    fun pushEvent(event: ProjectionEvent): ProjectionFailure? {
        if (report.size >= request.limits.maxReportEntries) {
            failure = ProjectionFailure.ResourceLimit("max_report_entries")
            return failure
        }
        fidelity = maxOf(fidelity, event.impact)
        report.add(event)
        return null
    }

    /** The derived root origin over the complete document (projection.rs:415-428). */
    fun addRootOrigin(): ProjectionFailure? {
        val rootSpan = try {
            document.authority.span(0, document.source.len)
        } catch (e: consema.document.LocationException) {
            failure = ProjectionFailure.CoreInvariant
            return failure
        }
        return addOrigin(
            ProjectedLocation.Value(ValuePath.root()),
            document.rootNode,
            rootSpan,
            ProvenanceRelation.Derived,
        )
    }
}

/** The frozen code mapping (projection.rs:741-752). */
internal fun projectionCode(failure: ProjectionFailure): String =
    when (failure) {
        is ProjectionFailure.RecoveredDocument -> "java-properties.projection.incomplete-document@1"
        is ProjectionFailure.UnpairedSurrogate -> "java-properties.projection.unpaired-surrogate@1"
        is ProjectionFailure.DuplicateKey, is ProjectionFailure.CoreInvariant ->
            "core.projection.target-not-applicable@1"

        is ProjectionFailure.ResourceLimit -> "core.projection.resource-limit@1"
    }

/** Builds the failed attempt with the ordered diagnostic (projection.rs:654-711). */
private fun failed(document: Document, failure: ProjectionFailure): ProjectionResult {
    val arguments = HashMap<String, String>()
    arguments["reason"] = when (failure) {
        is ProjectionFailure.RecoveredDocument -> "incomplete-document"
        is ProjectionFailure.UnpairedSurrogate -> "unpaired-surrogate"
        is ProjectionFailure.DuplicateKey -> "duplicate-key"
        is ProjectionFailure.ResourceLimit -> "resource-limit"
        is ProjectionFailure.CoreInvariant -> "target-not-applicable"
    }
    val primary: Span? = when (failure) {
        is ProjectionFailure.UnpairedSurrogate -> document.propertySpan(failure.property)
        is ProjectionFailure.DuplicateKey -> document.propertySpan(failure.duplicate)
        else -> null
    }
    when (failure) {
        is ProjectionFailure.UnpairedSurrogate -> {
            arguments["component"] = when (failure.component) {
                StringComponent.Key -> "key"
                StringComponent.Value -> "value"
            }
            insertPropertyOrdinal(document, arguments, "property_ordinal", failure.property)
        }
        is ProjectionFailure.DuplicateKey -> {
            insertPropertyOrdinal(document, arguments, "retained_ordinal", failure.retained)
            insertPropertyOrdinal(document, arguments, "duplicate_ordinal", failure.duplicate)
        }
        is ProjectionFailure.ResourceLimit -> arguments["limit"] = failure.name
        is ProjectionFailure.RecoveredDocument, is ProjectionFailure.CoreInvariant -> {}
    }
    val profile = document.profileId()
    arguments["profile"] = "${profile.id}@${profile.version}"
    val diagnostic = Diagnostic.of(
        projectionCode(failure),
        DiagnosticCategory.Projection,
        Severity.Error,
        primary?.let {
            consema.protocol.SourceLocation.of(
                document.snapshotIdentity.asU64.toString(),
                it.startByte.toULong(),
                it.endByte.toULong(),
            )
        },
        emptyList(),
        arguments,
        emptyList(),
        emptyList(),
        0uL,
        PROPERTIES_DIAGNOSTIC_REGISTRY,
    )
    return ProjectionResult.Failed(
        FailedProjectionAttempt(
            diagnostics = listOf(diagnostic),
            report = ProjectionReport.EMPTY,
        ),
    )
}

/** One snapshot-bound property span of the failure location
 * (the Rust failure_span, projection.rs:730-739). */
private fun Document.propertySpan(node: NodeRef): Span? =
    propertyEntities.firstOrNull { it.node == node }?.span

/** The source ordinal of one property node (the Rust
 * insert_property_ordinal, projection.rs:713-728). */
private fun insertPropertyOrdinal(
    document: Document,
    arguments: MutableMap<String, String>,
    name: String,
    node: NodeRef,
) {
    val ordinal = document.propertyEntities.indexOfFirst { it.node == node }
    if (ordinal >= 0) {
        arguments[name] = ordinal.toString()
    }
}
