// Explicit two-target projection: IniDocument -> nested EntryMapping or
// nested Object with fidelity, collision report, and provenance.
//
// Data authority:
//   - RFC 0009 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md:347-385): the
//     default exact projection ini.projection.best-exact-entry-mapping@1
//     produces an outer EntryMapping in source section order with inner
//     EntryMappings of original key String to value String, preserving
//     duplicate spellings; the Python default section is an ordinary
//     association whose provenance carries the DefaultSection role; missing
//     values cannot enter the complete profiles; RequireObjectV1 requires a
//     NameComparison of exactly OriginalExact | ProfileEquivalent and a
//     CollisionPolicy of exactly Reject | First | Last; any authorized
//     collapse is Transformed, emits one report event per discarded
//     association, and keeps retained/discarded provenance; Recovered
//     documents do not project.
//   - conformance/vectors/ini-v1.json (projection.*) pins the per-case
//     fidelity, events, section/key order, and provenance relations;
//     https://github.com/consema/consema-rs/blob/main/consema-ini/src/projection.rs is the byte-arbitration
//     authority (request projection.rs:9-124, exact projection.rs:428-537,
//     object projection.rs:546-785, selection projection.rs:787-821,
//     comparison projection.rs:831-846, failures projection.rs:852-893).
//   - Value paths come from the L0 core agent (consema.core.ValuePath /
//     ValuePathSegment / AssociationLocation / AssociationRole mirroring
//     https://github.com/consema/consema-rs/blob/main/consema-core/src/location.rs:1-89; the dependency is declared
//     by kotlin/src/main/kotlin/consema/document/Materialization.kt:27-31).
//
// Kotlin-idiomatic design: the completion algebra is a sealed class, so
// exhaustive `when` over Complete/Failed can never meet an unknown outcome;
// failures carry their frozen registered code via the failure-code mapping
// (projection.rs:886-893).

package consema.ini

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

/** Versioned INI projection target (projection.rs:9-16). */
enum class ProjectionTarget {
    /** Exact nested EntryMapping preserving every section and entry
     * occurrence. */
    BestExactEntryMappingV1,

    /** Nested unique-key Objects under explicit comparison and collision
     * policy. */
    RequireObjectV1,
}

/** Name comparison used only by `RequireObjectV1` (projection.rs:18-25). */
enum class NameComparison {
    /** Compare retained original decoded spelling exactly. */
    OriginalExact,

    /** Apply the selected INI profile's frozen comparison rule. */
    ProfileEquivalent,
}

/** Explicit collision behavior for Object projection (projection.rs:27-36). */
enum class CollisionPolicy {
    /** Reject every collision. */
    Reject,

    /** Retain the first occurrence in source order. */
    First,

    /** Retain the last occurrence while preserving retained-source order. */
    Last,
}

/** Immutable explicit projection request (projection.rs:38-100). */
class ProjectionRequest private constructor(
    /** Frozen target contract. */
    val target: ProjectionTarget,
    /** Explicit Object-name comparison. */
    val comparison: NameComparison,
    /** Explicit Object collision policy. */
    val collisionPolicy: CollisionPolicy,
    /** Projection resource limits. */
    val limits: ProjectionLimits,
) {
    companion object {
        /** Exact default that preserves duplicate associations
         * (projection.rs:48-57). */
        fun bestExactEntryMapping(): ProjectionRequest =
            ProjectionRequest(
                ProjectionTarget.BestExactEntryMappingV1,
                NameComparison.OriginalExact,
                CollisionPolicy.Reject,
                ProjectionLimits.default,
            )

        /** Explicit unique Object request (projection.rs:59-68). */
        fun requireObject(
            comparison: NameComparison,
            collisionPolicy: CollisionPolicy,
        ): ProjectionRequest =
            ProjectionRequest(
                ProjectionTarget.RequireObjectV1,
                comparison,
                collisionPolicy,
                ProjectionLimits.default,
            )
    }

    /** Replaces immutable resource limits (projection.rs:70-75). */
    fun withLimits(limits: ProjectionLimits): ProjectionRequest =
        ProjectionRequest(target, comparison, collisionPolicy, limits)
}

/** INI projection limits (projection.rs:102-124). */
data class ProjectionLimits(
    /** Maximum source section and entry associations inspected. */
    val maxSourceAssociations: Int,
    /** Maximum produced PortableValue nodes. */
    val maxValueNodes: Int,
    /** Maximum report events. */
    val maxReportEntries: Int,
    /** Maximum projected locations plus source origins. */
    val maxProvenanceUnits: Int,
) {
    companion object {
        /** The frozen defaults (projection.rs:115-123). */
        val default = ProjectionLimits(
            maxSourceAssociations = 2_000_000,
            maxValueNodes = 2_000_000,
            maxReportEntries = 100_000,
            maxProvenanceUnits = 4_000_000,
        )
    }
}

/** Projection fidelity classification (projection.rs:126-135). */
enum class Fidelity {
    /** Target directly represents every native association. */
    Exact,

    /** An explicit reported collision policy transformed associations. */
    Transformed,

    /** Source facts were irreversibly omitted without a retained source
     * relation. */
    Lossy,
}

/** Projected value or association location (projection.rs:137-144). */
sealed class ProjectedLocation {
    /** Portable value location. */
    data class Value(val path: ValuePath) : ProjectedLocation()

    /** Portable association location. */
    data class Association(val location: AssociationLocation) : ProjectedLocation()
}

/** Source-to-projection relation (projection.rs:146-159). */
enum class ProvenanceRelation {
    /** Direct native semantic origin. */
    Direct,

    /** Container value derived from a source record. */
    Derived,

    /** More-indented Python physical-line value fragment. */
    ContinuationFragment,

    /** Semantic content derived by removing exact Windows outer quotes. */
    QuoteDerived,

    /** Discarded association related to the retained projected
     * association. */
    Collapsed,
}

/** One exact source origin (projection.rs:161-172). */
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

/** One many-valued provenance entry (projection.rs:174-181). */
data class ProvenanceEntry(
    /** Projected value or association. */
    val projected: ProjectedLocation,
    /** Ordered source origins. */
    val origins: List<SourceOrigin>,
)

/** Immutable many-valued provenance mapping (projection.rs:183-195). */
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

/** Collision report category (projection.rs:197-204). */
enum class ProjectionEventKind {
    /** Section association was collapsed. */
    SectionCollisionCollapsed,

    /** Entry association was collapsed. */
    EntryCollisionCollapsed,
}

/** One explicit Object collision event (projection.rs:206-223). */
data class ProjectionEvent(
    /** Stable event kind. */
    val kind: ProjectionEventKind,
    /** Policy that authorized the transformation. */
    val policy: CollisionPolicy,
    /** Comparison mode that formed the collision class. */
    val comparison: NameComparison,
    /** Discarded source occurrence. */
    val discarded: NodeRef,
    /** Retained source occurrence. */
    val retained: NodeRef,
    /** Association produced from the retained occurrence. */
    val projected: AssociationLocation,
    /** Fidelity impact. */
    val impact: Fidelity,
)

/** Complete ordered projection report (projection.rs:225-237). */
class ProjectionReport private constructor(private val events: List<ProjectionEvent>) {
    companion object {
        internal val EMPTY = ProjectionReport(emptyList())

        internal fun of(events: List<ProjectionEvent>): ProjectionReport = ProjectionReport(events)
    }

    /** Events in deterministic source order. */
    fun events(): List<ProjectionEvent> = events

    override fun equals(other: Any?): Boolean =
        other is ProjectionReport && events == other.events

    override fun hashCode(): Int = events.hashCode()
}

/** Complete successful projection (projection.rs:239-250). */
data class CompleteProjection(
    /** Complete immutable nested mapping. */
    val value: PortableValue,
    /** Worst operation fidelity. */
    val fidelity: Fidelity,
    /** Structured collision report. */
    val report: ProjectionReport,
    /** Value and association provenance. */
    val provenance: ProvenanceMap,
)

/** Failed projection attempt without a partial value (projection.rs:252-259). */
data class FailedProjectionAttempt(
    /** Stable ordered diagnostics. */
    val diagnostics: List<Diagnostic>,
    /** Empty report: failed projections publish no partial transformation
     * result. */
    val report: ProjectionReport,
)

/** Projection completion algebra (projection.rs:261-268). */
sealed class ProjectionResult {
    /** Complete success. */
    data class Complete(val projection: CompleteProjection) : ProjectionResult()

    /** Failure with no value or provenance map. */
    data class Failed(val attempt: FailedProjectionAttempt) : ProjectionResult()
}

/** Stable INI projection failure (projection.rs:270-286). */
sealed class ProjectionFailure {
    /** Recovered documents cannot publish partial semantic values. */
    data object RecoveredDocument : ProjectionFailure()

    /** Object collision under `Reject`. */
    data class Collision(
        /** Colliding section or entry container. */
        val container: NodeRef,
        /** Comparison name that collided. */
        val name: String,
    ) : ProjectionFailure()

    /** Declared projection resource limit was reached. */
    data class ResourceLimit(val name: String) : ProjectionFailure()

    /** PortableValue construction invariant failed. */
    data object CoreInvariant : ProjectionFailure()
}

/** The typed projection failure carrying the frozen registered code
 * (projection.rs:886-893). */
class ProjectionFailureException(val failure: ProjectionFailure) :
    Exception("ini projection: ${projectionCode(failure)}")

/** The frozen code mapping (projection.rs:886-893). */
internal fun projectionCode(failure: ProjectionFailure): String =
    when (failure) {
        is ProjectionFailure.RecoveredDocument -> "ini.projection.incomplete-document@1"
        is ProjectionFailure.Collision -> "ini.projection.collision@1"
        is ProjectionFailure.ResourceLimit -> "core.projection.resource-limit@1"
        is ProjectionFailure.CoreInvariant -> "core.projection.target-not-applicable@1"
    }

/**
 * Projects this snapshot under one explicit target and collision contract
 * (projection.rs:288-314). A failure publishes no value, provenance map, or
 * partial event report (RFC 0009 §10).
 */
fun IniDocument.project(request: ProjectionRequest): ProjectionResult {
    if (formationStatus != FormationStatus.Complete) {
        return failed(this, ProjectionFailure.RecoveredDocument)
    }
    val sourceAssociations = sectionsList.size + entriesList.size
    if (sourceAssociations > request.limits.maxSourceAssociations) {
        return failed(this, ProjectionFailure.ResourceLimit("max_source_associations"))
    }
    return try {
        ProjectionResult.Complete(
            when (request.target) {
                ProjectionTarget.BestExactEntryMappingV1 -> projectExact(this, request)
                ProjectionTarget.RequireObjectV1 -> projectObject(this, request)
            },
        )
    } catch (e: ProjectionFailureException) {
        failed(this, e.failure)
    }
}

/** Mutable projection context carrying provenance, report, and fidelity
 * (projection.rs:316-426). */
private class ProjectionContext(
    val document: IniDocument,
    val request: ProjectionRequest,
) {
    val provenance = ArrayList<ProvenanceEntry>()
    var provenanceUnits = 0
    val report = ArrayList<ProjectionEvent>()
    var fidelity: Fidelity = Fidelity.Exact

    fun addOrigin(
        projected: ProjectedLocation,
        node: NodeRef,
        span: Span,
        relation: ProvenanceRelation,
    ) {
        val newLocation = provenance.none { it.projected == projected }
        val increment = if (newLocation) 2 else 1
        provenanceUnits = checkedUnits(provenanceUnits + increment)
        if (provenanceUnits > request.limits.maxProvenanceUnits) {
            throw ProjectionFailureException(ProjectionFailure.ResourceLimit("max_provenance_units"))
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
            provenance[provenance.indexOf(existing)] = existing.copy(origins = origins)
        } else {
            provenance.add(ProvenanceEntry(projected, listOf(origin)))
        }
    }

    fun pushEvent(event: ProjectionEvent) {
        if (report.size >= request.limits.maxReportEntries) {
            throw ProjectionFailureException(ProjectionFailure.ResourceLimit("max_report_entries"))
        }
        report.add(event)
        fidelity = maxOf(fidelity, Fidelity.Transformed)
    }

    fun addEntryValueOrigins(projected: ProjectedLocation, entryIndex: Int) {
        val entry = document.entriesList[entryIndex]
        addOrigin(
            projected,
            entry.nodeRef,
            entry.valueSpan,
            if (entry.quoteStyle == IniQuoteStyle.None) {
                ProvenanceRelation.Direct
            } else {
                ProvenanceRelation.QuoteDerived
            },
        )
        val logical = document.logicalLinesList.firstOrNull { it.nodeRef == entry.logicalLine }
            ?: return
        for (physicalNode in logical.physicalLines.drop(1)) {
            val physical = try {
                document.physicalLine(physicalNode)
            } catch (e: IniAccessException) {
                continue
            }
            val contentStart = physical.contentSpan.startByte
            val contentEnd = physical.contentSpan.endByte
            val pieces = document.pieces()
            val kinds = document.losslessSyntaxKinds()
            val start = pieces.indexOfFirst { it.span.endByte > contentStart }
                .let { if (it < 0) pieces.size else it }
            for (ordinal in start until pieces.size) {
                val piece = pieces[ordinal]
                if (piece.span.startByte >= contentEnd) {
                    break
                }
                if (kinds[ordinal] == IniSyntaxKind.EntryValue) {
                    addOrigin(
                        projected,
                        entry.nodeRef,
                        piece.span,
                        ProvenanceRelation.ContinuationFragment,
                    )
                }
            }
        }
    }

    private fun checkedUnits(value: Int): Int =
        if (value < 0) {
            throw ProjectionFailureException(ProjectionFailure.ResourceLimit("max_provenance_units"))
        } else {
            value
        }
}

/** The exact nested EntryMapping projection (projection.rs:428-537). */
private fun projectExact(
    document: IniDocument,
    request: ProjectionRequest,
): CompleteProjection {
    val requiredNodes = document.sectionsList.size * 2 + document.entriesList.size * 2 + 1
    if (requiredNodes > request.limits.maxValueNodes) {
        throw ProjectionFailureException(ProjectionFailure.ResourceLimit("max_value_nodes"))
    }
    val context = ProjectionContext(document, request)
    val root = ValuePath.root()
    val outer = EntryMappingBuilder()
    val entriesBySection = groupEntries(document)
    for ((sectionOrdinal, section) in document.sectionsList.withIndex()) {
        val outerOrdinal = sectionOrdinal.toLong()
        val sectionPath = root.child(ValuePathSegment.EntryValue(outerOrdinal))
        val outerAssociation = AssociationLocation(
            root,
            outerOrdinal,
            AssociationRole.EntryMappingEntry,
        )
        context.addOrigin(
            ProjectedLocation.Association(outerAssociation),
            section.nodeRef,
            section.span,
            ProvenanceRelation.Direct,
        )
        context.addOrigin(
            ProjectedLocation.Value(root.child(ValuePathSegment.EntryKey(outerOrdinal))),
            section.nodeRef,
            section.nameSpan,
            ProvenanceRelation.Direct,
        )
        context.addOrigin(
            ProjectedLocation.Value(sectionPath),
            section.nodeRef,
            section.span,
            ProvenanceRelation.Derived,
        )
        val inner = EntryMappingBuilder()
        val owned = entriesBySection[section.nodeRef].orEmpty()
        for ((localOrdinal, entryIndex) in owned.withIndex()) {
            val entry = document.entriesList[entryIndex]
            val ordinal = localOrdinal.toLong()
            val association = AssociationLocation(
                sectionPath,
                ordinal,
                AssociationRole.EntryMappingEntry,
            )
            context.addOrigin(
                ProjectedLocation.Association(association),
                entry.nodeRef,
                entry.span,
                ProvenanceRelation.Direct,
            )
            context.addOrigin(
                ProjectedLocation.Value(sectionPath.child(ValuePathSegment.EntryKey(ordinal))),
                entry.nodeRef,
                entry.keySpan,
                ProvenanceRelation.Direct,
            )
            val valuePath = sectionPath.child(ValuePathSegment.EntryValue(ordinal))
            context.addEntryValueOrigins(ProjectedLocation.Value(valuePath), entryIndex)
            inner.push(PvString(entry.key), PvString(entry.value))
        }
        outer.push(PvString(section.name), inner.build())
    }
    context.addOrigin(
        ProjectedLocation.Value(root),
        document.rootNode,
        document.authority.span(0, document.sourceSnapshot.len),
        ProvenanceRelation.Derived,
    )
    return CompleteProjection(
        value = outer.build(),
        fidelity = context.fidelity,
        report = ProjectionReport.of(context.report),
        provenance = ProvenanceMap.of(context.provenance),
    )
}

/** One retained section with its entry selection facts (projection.rs:539-544). */
private data class SelectedSection(
    val sourceIndex: Int,
    val allEntryIndices: List<Int>,
    val entryIndices: List<Int>,
)

/** The explicit unique-Object projection (projection.rs:546-785). */
private fun projectObject(
    document: IniDocument,
    request: ProjectionRequest,
): CompleteProjection {
    val sectionNames = document.sectionsList.map { section ->
        comparisonName(document.profile, section.name, request.comparison, isKey = false)
    }
    val retainedSections = selectIndices(sectionNames, request.collisionPolicy, document.rootNode)
    val entriesBySection = groupEntries(document)
    val selected = retainedSections.map { sectionIndex ->
        val section = document.sectionsList[sectionIndex]
        val entryIndices = entriesBySection[section.nodeRef].orEmpty()
        val entryNames = entryIndices.map { index ->
            comparisonName(
                document.profile,
                document.entriesList[index].key,
                request.comparison,
                isKey = true,
            )
        }
        val retainedLocal = selectIndices(entryNames, request.collisionPolicy, section.nodeRef)
        SelectedSection(
            sourceIndex = sectionIndex,
            allEntryIndices = entryIndices,
            entryIndices = retainedLocal.map { entryIndices[it] },
        )
    }
    val retainedEntries = selected.sumOf { it.entryIndices.size }
    val requiredNodes = retainedEntries + selected.size + 1
    if (requiredNodes > request.limits.maxValueNodes) {
        throw ProjectionFailureException(ProjectionFailure.ResourceLimit("max_value_nodes"))
    }
    val context = ProjectionContext(document, request)
    val root = ValuePath.root()
    val outer = ObjectBuilder()
    val retainedSectionByName = selected.associate { item ->
        sectionNames[item.sourceIndex] to item.sourceIndex
    }
    val projectedSectionOrdinal = selected.mapIndexed { projected, item -> item.sourceIndex to projected }
        .toMap()
    for ((sourceIndex, section) in document.sectionsList.withIndex()) {
        val retainedIndex = retainedSectionByName[sectionNames[sourceIndex]]!!
        if (retainedIndex == sourceIndex) {
            continue
        }
        val projectedOrdinal = projectedSectionOrdinal[retainedIndex]!!
        val location = AssociationLocation(
            root,
            projectedOrdinal.toLong(),
            AssociationRole.ObjectEntry,
        )
        context.pushEvent(
            ProjectionEvent(
                kind = ProjectionEventKind.SectionCollisionCollapsed,
                policy = request.collisionPolicy,
                comparison = request.comparison,
                discarded = section.nodeRef,
                retained = document.sectionsList[retainedIndex].nodeRef,
                projected = location,
                impact = Fidelity.Transformed,
            ),
        )
        context.addOrigin(
            ProjectedLocation.Association(location),
            section.nodeRef,
            section.span,
            ProvenanceRelation.Collapsed,
        )
    }
    for ((projectedOrdinal, selectedSection) in selected.withIndex()) {
        val section = document.sectionsList[selectedSection.sourceIndex]
        val sectionPath = root.child(ValuePathSegment.ObjectValue(section.name))
        val outerLocation = AssociationLocation(
            root,
            projectedOrdinal.toLong(),
            AssociationRole.ObjectEntry,
        )
        context.addOrigin(
            ProjectedLocation.Association(outerLocation),
            section.nodeRef,
            section.span,
            ProvenanceRelation.Direct,
        )
        context.addOrigin(
            ProjectedLocation.Association(
                AssociationLocation(root, projectedOrdinal.toLong(), AssociationRole.ObjectKey),
            ),
            section.nodeRef,
            section.nameSpan,
            ProvenanceRelation.Direct,
        )
        context.addOrigin(
            ProjectedLocation.Value(sectionPath),
            section.nodeRef,
            section.span,
            ProvenanceRelation.Derived,
        )

        val retainedEntrySet = selectedSection.entryIndices.toSet()
        val retainedEntryByName = selectedSection.entryIndices.associate { index ->
            comparisonName(
                document.profile,
                document.entriesList[index].key,
                request.comparison,
                isKey = true,
            ) to index
        }
        val projectedEntryOrdinal = selectedSection.entryIndices.mapIndexed { projected, source -> source to projected }
            .toMap()
        for (entryIndex in selectedSection.allEntryIndices) {
            if (entryIndex in retainedEntrySet) {
                continue
            }
            val entry = document.entriesList[entryIndex]
            val name = comparisonName(document.profile, entry.key, request.comparison, isKey = true)
            val retainedIndex = retainedEntryByName[name]!!
            val location = AssociationLocation(
                sectionPath,
                projectedEntryOrdinal[retainedIndex]!!.toLong(),
                AssociationRole.ObjectEntry,
            )
            context.pushEvent(
                ProjectionEvent(
                    kind = ProjectionEventKind.EntryCollisionCollapsed,
                    policy = request.collisionPolicy,
                    comparison = request.comparison,
                    discarded = entry.nodeRef,
                    retained = document.entriesList[retainedIndex].nodeRef,
                    projected = location,
                    impact = Fidelity.Transformed,
                ),
            )
            context.addOrigin(
                ProjectedLocation.Association(location),
                entry.nodeRef,
                entry.span,
                ProvenanceRelation.Collapsed,
            )
        }

        val inner = ObjectBuilder()
        for ((projectedEntryOrdinalValue, entryIndex) in selectedSection.entryIndices.withIndex()) {
            val entry = document.entriesList[entryIndex]
            val ordinal = projectedEntryOrdinalValue.toLong()
            context.addOrigin(
                ProjectedLocation.Association(
                    AssociationLocation(sectionPath, ordinal, AssociationRole.ObjectEntry),
                ),
                entry.nodeRef,
                entry.span,
                ProvenanceRelation.Direct,
            )
            context.addOrigin(
                ProjectedLocation.Association(
                    AssociationLocation(sectionPath, ordinal, AssociationRole.ObjectKey),
                ),
                entry.nodeRef,
                entry.keySpan,
                ProvenanceRelation.Direct,
            )
            context.addEntryValueOrigins(
                ProjectedLocation.Value(
                    sectionPath.child(ValuePathSegment.ObjectValue(entry.key)),
                ),
                entryIndex,
            )
            inner.insert(entry.key, PvString(entry.value))
        }
        outer.insert(section.name, inner.build())
    }
    context.addOrigin(
        ProjectedLocation.Value(root),
        document.rootNode,
        document.authority.span(0, document.sourceSnapshot.len),
        ProvenanceRelation.Derived,
    )
    return CompleteProjection(
        value = outer.build(),
        fidelity = context.fidelity,
        report = ProjectionReport.of(context.report),
        provenance = ProvenanceMap.of(context.provenance),
    )
}

/** Selects retained indices under one collision policy (projection.rs:787-821). */
private fun selectIndices(
    names: List<String>,
    policy: CollisionPolicy,
    container: NodeRef,
): List<Int> {
    val counts = HashMap<String, Int>()
    for (name in names) {
        counts[name] = (counts[name] ?: 0) + 1
    }
    if (policy == CollisionPolicy.Reject) {
        val collision = names.firstOrNull { (counts[it] ?: 0) > 1 }
        if (collision != null) {
            throw ProjectionFailureException(ProjectionFailure.Collision(container, collision))
        }
    }
    return when (policy) {
        CollisionPolicy.Reject, CollisionPolicy.First -> {
            val seen = HashSet<String>()
            names.indices.filter { seen.add(names[it]) }
        }
        CollisionPolicy.Last -> {
            val seen = HashSet<String>()
            val retained = names.indices.reversed().filter { seen.add(names[it]) }
            retained.reversed()
        }
    }
}

/** Entry indices grouped by their owning section occurrence
 * (projection.rs:823-829). */
private fun groupEntries(document: IniDocument): Map<NodeRef, List<Int>> {
    val groups = HashMap<NodeRef, MutableList<Int>>()
    for ((index, entry) in document.entriesList.withIndex()) {
        groups.getOrPut(entry.section) { ArrayList() }.add(index)
    }
    return groups
}

/** The profile-specific comparison of one name (projection.rs:831-846). */
private fun comparisonName(
    profile: IniProfile,
    value: String,
    comparison: NameComparison,
    isKey: Boolean,
): String {
    if (comparison == NameComparison.OriginalExact) {
        return value
    }
    return when {
        profile == IniProfile.WindowsV1 -> asciiLowercase(value)
        profile == IniProfile.PythonConfigParserV1 && isKey -> optionxform(value)
        else -> value
    }
}

/** Builds a failed attempt with the ordered diagnostics and no partial
 * report (projection.rs:852-884). */
private fun failed(document: IniDocument, failure: ProjectionFailure): ProjectionResult {
    val arguments = LinkedHashMap<String, String>()
    arguments["reason"] = when (failure) {
        is ProjectionFailure.RecoveredDocument -> "incomplete-document"
        is ProjectionFailure.Collision -> "collision"
        is ProjectionFailure.ResourceLimit -> "resource-limit"
        is ProjectionFailure.CoreInvariant -> "target-not-applicable"
    }
    if (failure is ProjectionFailure.ResourceLimit) {
        arguments["limit"] = failure.name
    }
    val profile = document.profileId()
    arguments["profile"] = "${profile.id}@${profile.version}"
    val diagnostic = Diagnostic.of(
        projectionCode(failure),
        // `core.projection.resource-limit@1` is registered Resource; every
        // other projection failure is Projection (ErrorRegistry.kt:181-183).
        if (failure is ProjectionFailure.ResourceLimit) {
            DiagnosticCategory.Resource
        } else {
            DiagnosticCategory.Projection
        },
        Severity.Error,
        null,
        emptyList(),
        arguments,
        emptyList(),
        emptyList(),
        0uL,
        INI_DIAGNOSTIC_REGISTRY,
    )
    return ProjectionResult.Failed(
        FailedProjectionAttempt(
            diagnostics = listOf(diagnostic),
            report = ProjectionReport.EMPTY,
        ),
    )
}
