// Two-stage explicit projection: Document -> PortableValue with fidelity,
// report, and provenance.
//
// Data authority:
//   - RFC 0004 §7-§8 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:171-218) pins the completion algebra
//     (Complete{value, fidelity, report, provenance} | Failed{diagnostics,
//     report, partial_analysis}) and the provenance direction (portable
//     locations to source origins).
//   - RFC 0005 §8 (https://github.com/consema/consema/blob/main/docs/rfcs/0005-json-family-production-v1.md:174-193) pins
//     the JSON5 projection contract: json5.projection.best-exact-core@1 is the
//     JSON5 default target and is profile-bound (applying the old target to
//     JSON5 or the JSON5 target to another profile fails target-not-
//     applicable); duplicate-name objects map to EntryMapping under BestExact;
//     Infinity/NaN map to the exact frozen BinaryFloat64 bits.
//   - conformance/vectors/json-family-v2.json (json5.projection.*) pins the
//     per-case outcomes; consema-rs/consema-json/src/projection.rs is the
//     byte-arbitration authority (targets projection.rs:13-24, request
//     projection.rs:52-168, failure codes projection.rs:754-765, selection
//     projection.rs:691-726).
//   - Value paths come from the L0 core agent (consema.core.ValuePath /
//     ValuePathSegment / AssociationLocation / AssociationRole mirroring
//     consema-rs/consema-core/src/location.rs:1-89; the dependency is declared by
//     kotlin/.../document/Materialization.kt:27-31).
//
// Kotlin-idiomatic design: the completion algebra is a sealed class, so
// exhaustive `when` over Complete/Failed can never meet an unknown outcome;
// failures carry their frozen registered code via the projection_code
// mapping (projection.rs:754-765).

package consema.json

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.EntryMappingBuilder
import consema.core.ObjectBuilder
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvNull
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

/** Versioned projection target contract (projection.rs:13-24). */
enum class ProjectionTarget {
    /** Every JSON object must become a unique-key PortableValue Object. */
    ProjectAsObjectV1,

    /** Every JSON object becomes an ordered EntryMapping. */
    ProjectAsEntryMappingV1,

    /** Frozen exact-first core selection algorithm. */
    BestExactCoreV1,

    /** JSON5 exact-first core selection including frozen non-finite binary64
     * values (RFC 0005 §8). */
    Json5BestExactCoreV1,
}

/** Explicit duplicate member policy (projection.rs:26-35). */
enum class DuplicateKeyPolicy {
    /** Preserve nothing by guessing; fail when Object cannot represent
     * duplicates. */
    Reject,

    /** Retain the first member and report every collapsed later member. */
    FirstWins,

    /** Retain the last member and report every collapsed earlier member. */
    LastWins,
}

/** Scope supported by v1 projection policy rules (projection.rs:37-44). */
sealed class ProjectionPolicyScope {
    /** All applicable native objects. */
    data object Global : ProjectionPolicyScope()

    /** Exactly one snapshot-bound object NodeRef. */
    data class ExactNodeRef(val node: NodeRef) : ProjectionPolicyScope()
}

/** Projection resource limits (projection.rs:146-168). */
data class ProjectionLimits(
    /** Maximum produced PortableValue nodes. */
    val maxValueNodes: Int,
    /** Maximum report events. */
    val maxReportEntries: Int,
    /** Maximum provenance locations. */
    val maxProvenanceEntries: Int,
    /** Maximum recursion depth. */
    val maxDepth: Int,
) {
    companion object {
        /** The frozen defaults (projection.rs:159-168): 1,000,000 value
         * nodes, 100,000 report entries, 2,000,000 provenance entries,
         * depth 256. */
        val default = ProjectionLimits(
            maxValueNodes = 1_000_000,
            maxReportEntries = 100_000,
            maxProvenanceEntries = 2_000_000,
            maxDepth = 256,
        )
    }
}

/** Projection fidelity classification (projection.rs:170-179). */
enum class Fidelity {
    /** Target directly and completely represents covered native semantics. */
    Exact,

    /** Complete semantics survive an explicit reversible re-encoding. */
    Transformed,

    /** At least one source fact cannot be recovered. */
    Lossy,
}

/** Projected value or association location (projection.rs:181-188). */
sealed class ProjectedLocation {
    /** Portable value location. */
    data class Value(val path: ValuePath) : ProjectedLocation()

    /** Portable association location. */
    data class Association(val location: AssociationLocation) : ProjectedLocation()
}

/** Source-to-projection relation (projection.rs:190-203). */
enum class ProvenanceRelation {
    /** Direct native semantic origin. */
    Direct,

    /** Derived without a one-to-one literal origin. */
    Derived,

    /** Reference expansion origin. */
    Expanded,

    /** Multiple sources merged. */
    Merged,

    /** No source origin. */
    Generated,
}

/** One exact source origin (projection.rs:205-216). */
data class SourceOrigin(
    /** Source document snapshot. */
    val snapshot: SnapshotIdentity,
    /** Exact structural identity. */
    val node: NodeRef,
    /** Exact source range. */
    val span: Span,
    /** Source relation. */
    val relation: ProvenanceRelation,
)

/** One many-valued provenance mapping entry (projection.rs:218-225). */
data class ProvenanceEntry(
    /** Projected value or association. */
    val projected: ProjectedLocation,
    /** Zero or more source origins. */
    val origins: List<SourceOrigin>,
)

/** Immutable multi-map from projected locations to source origins
 * (projection.rs:227-239). */
class ProvenanceMap private constructor(private val entries: List<ProvenanceEntry>) {
    companion object {
        internal fun of(entries: List<ProvenanceEntry>): ProvenanceMap = ProvenanceMap(entries)
    }

    /** Deterministically generated entries. */
    fun entries(): List<ProvenanceEntry> = entries

    override fun equals(other: Any?): Boolean =
        other is ProvenanceMap && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()
}

/** Machine-readable projection event category (projection.rs:241-256). */
enum class ProjectionEventKind {
    /** Object was reversibly represented as EntryMapping. */
    StructureReencoded,

    /** Native/core type mapping was explicit. */
    TypeMapped,

    /** Duplicate member was collapsed. */
    DuplicateCollapsed,

    /** Key was stringified (not authorized by JSON v1 policies). */
    KeyStringified,

    /** Value was rounded (not authorized by JSON v1 policies). */
    ValueRounded,

    /** Field was dropped. */
    FieldDropped,
}

/** One structured projection report event (projection.rs:258-277). */
data class ProjectionEvent(
    /** Stable event kind. */
    val kind: ProjectionEventKind,
    /** Policy rule that authorized it. */
    val policy: DuplicateKeyPolicy?,
    /** Exact source identity. */
    val source: NodeRef,
    /** Result location when one exists. */
    val projected: ProjectedLocation?,
    /** Stable old semantic category. */
    val oldCategory: String,
    /** Stable new semantic category. */
    val newCategory: String,
    /** Whether the source fact can be recovered from output plus contract. */
    val reversible: Boolean,
    /** Fidelity impact. */
    val loss: Fidelity,
)

/** Complete ordered projection report (projection.rs:279-291). */
class ProjectionReport private constructor(private val events: List<ProjectionEvent>) {
    companion object {
        internal val EMPTY = ProjectionReport(emptyList())

        internal fun of(events: List<ProjectionEvent>): ProjectionReport = ProjectionReport(events)
    }

    /** Events in source/operation order. */
    fun events(): List<ProjectionEvent> = events

    override fun equals(other: Any?): Boolean =
        other is ProjectionReport && events == other.events

    override fun hashCode(): Int = events.hashCode()
}

/** Complete successful projection; its value is never partial
 * (projection.rs:293-304). */
data class CompleteProjection(
    /** Complete immutable value. */
    val value: PortableValue,
    /** Worst fidelity of the whole operation. */
    val fidelity: Fidelity,
    /** Machine-readable transformation/loss report. */
    val report: ProjectionReport,
    /** Basic value and association provenance. */
    val provenance: ProvenanceMap,
)

/** Failed attempt without a partial PortableValue (projection.rs:306-315). */
data class FailedProjectionAttempt(
    /** Ordered operation diagnostics. */
    val diagnostics: List<Diagnostic>,
    /** Events discovered before the failed completion check. */
    val report: ProjectionReport,
    /** Stable path descriptions of locally analyzed regions. */
    val partialAnalysis: List<String>,
)

/** Projection completion algebra (projection.rs:317-324). */
sealed class ProjectionResult {
    /** Complete success. */
    data class Complete(val projection: CompleteProjection) : ProjectionResult()

    /** Failed attempt with no value. */
    data class Failed(val attempt: FailedProjectionAttempt) : ProjectionResult()
}

/** Stable projection failure category (projection.rs:326-355). */
sealed class ProjectionFailure {
    /** Recovered documents cannot publish partial semantic values. */
    data object RecoveredDocument : ProjectionFailure()

    /** Equal-precedence rules conflict. */
    data object ConflictingPolicyRules : ProjectionFailure()

    /** Exact NodeRef scope belongs to another snapshot or role. */
    data object WrongSnapshotPolicy : ProjectionFailure()

    /** Exact NodeRef scope does not identify an Object value. */
    data object InvalidPolicyTarget : ProjectionFailure()

    /** Root does not satisfy an explicitly requested mapping target. */
    data object TargetNotApplicable : ProjectionFailure()

    /** Duplicate member cannot enter Object under Reject. */
    data class DuplicateKeys(val node: NodeRef, val name: String) : ProjectionFailure()

    /** Native semantics are locally unavailable. */
    data class SemanticUnavailable(
        val node: NodeRef,
        val reason: consema.json.SemanticUnavailable,
    ) : ProjectionFailure()

    /** Declared resource limit was reached; output is not truncated to
     * success. */
    data class ResourceLimit(val name: String) : ProjectionFailure()
}

/**
 * Immutable versioned projection request (projection.rs:52-72). The builder
 * (projection.rs:74-144) starts with `ExactOrReject` behavior and rejects
 * conflicting equal-precedence rules with [ProjectionFailure.ConflictingPolicyRules].
 */
class ProjectionRequest private constructor(
    /** Target contract. */
    val target: ProjectionTarget,
    internal val duplicateRules: List<DuplicateRule>,
    /** Projection resource limits. */
    val limits: ProjectionLimits,
) {
    /** The frozen conservative default policy (RFC 0016 §5.2): exact or
     * reject, never invented. */
    class Builder(private val target: ProjectionTarget) {
        private val duplicateRules = ArrayList<DuplicateRule>()
        private var limits: ProjectionLimits = ProjectionLimits.default

        init {
            duplicateRules.add(
                DuplicateRule(ProjectionPolicyScope.Global, DuplicateKeyPolicy.Reject),
            )
        }

        /** Replaces the global duplicate policy (projection.rs:96-108). */
        fun globalDuplicatePolicy(policy: DuplicateKeyPolicy): Builder {
            duplicateRules.removeAll { it.scope == ProjectionPolicyScope.Global }
            duplicateRules.add(DuplicateRule(ProjectionPolicyScope.Global, policy))
            return this
        }

        /** Adds an exact-node override (projection.rs:108-121). */
        fun exactNodeDuplicatePolicy(node: NodeRef, policy: DuplicateKeyPolicy): Builder {
            duplicateRules.add(DuplicateRule(ProjectionPolicyScope.ExactNodeRef(node), policy))
            return this
        }

        /** Sets immutable resource limits (projection.rs:122-127). */
        fun limits(limits: ProjectionLimits): Builder {
            this.limits = limits
            return this
        }

        /** Validates rule precedence and completes the request
         * (projection.rs:128-143). */
        fun build(): ProjectionRequest {
            for (index in duplicateRules.indices) {
                for (right in duplicateRules.drop(index + 1)) {
                    if (duplicateRules[index].scope == right.scope &&
                        duplicateRules[index].policy != right.policy
                    ) {
                        throw ProjectionFailureException(ProjectionFailure.ConflictingPolicyRules)
                    }
                }
            }
            return ProjectionRequest(target, duplicateRules, limits)
        }
    }

    companion object {
        /** Starts a builder with the conservative exact-or-reject default
         * (projection.rs:82-94). */
        fun builder(target: ProjectionTarget): Builder = Builder(target)
    }
}

/** One scope/policy rule (projection.rs:46-50). */
internal data class DuplicateRule(
    val scope: ProjectionPolicyScope,
    val policy: DuplicateKeyPolicy,
)

/** The typed projection failure carrying the frozen registered code
 * (projection.rs:754-765). */
class ProjectionFailureException(val failure: ProjectionFailure) :
    Exception("projection: ${projectionCode(failure)}")

/** The frozen code mapping (projection.rs:754-765). */
internal fun projectionCode(failure: ProjectionFailure): String =
    when (failure) {
        is ProjectionFailure.RecoveredDocument -> "json.projection.incomplete-document@1"
        is ProjectionFailure.ConflictingPolicyRules -> "core.projection.conflicting-policy@1"
        is ProjectionFailure.WrongSnapshotPolicy -> "core.projection.wrong-snapshot-policy@1"
        is ProjectionFailure.InvalidPolicyTarget -> "core.projection.invalid-policy-target@1"
        is ProjectionFailure.TargetNotApplicable -> "core.projection.target-not-applicable@1"
        is ProjectionFailure.DuplicateKeys -> "json.projection.duplicate-keys@1"
        is ProjectionFailure.SemanticUnavailable -> "json.projection.semantic-unavailable@1"
        is ProjectionFailure.ResourceLimit -> "core.projection.resource-limit@1"
    }

/** The frozen failure spellings used by the shared vectors
 * (json-family-v2.json expected.code / expected.failure fields). */
internal fun projectionFailureName(failure: ProjectionFailure): String =
    when (failure) {
        is ProjectionFailure.RecoveredDocument -> "RecoveredDocument"
        is ProjectionFailure.ConflictingPolicyRules -> "ConflictingPolicyRules"
        is ProjectionFailure.WrongSnapshotPolicy -> "WrongSnapshotPolicy"
        is ProjectionFailure.InvalidPolicyTarget -> "InvalidPolicyTarget"
        is ProjectionFailure.TargetNotApplicable -> "TargetNotApplicable"
        is ProjectionFailure.DuplicateKeys -> "DuplicateKeys"
        is ProjectionFailure.SemanticUnavailable -> "SemanticUnavailable"
        is ProjectionFailure.ResourceLimit -> "ResourceLimit"
    }

/** Applies an immutable request; a failure never contains a partial value
 * (projection.rs:357-430). */
fun Document.project(request: ProjectionRequest): ProjectionResult {
    if (formationStatus != FormationStatus.Complete) {
        return failed(
            ProjectionFailure.RecoveredDocument,
            ProjectionReport.EMPTY,
            emptyList(),
        )
    }
    if ((request.target == ProjectionTarget.Json5BestExactCoreV1 &&
            profile != JsonProfile.Json5StandardV1) ||
        (request.target == ProjectionTarget.BestExactCoreV1 &&
            profile == JsonProfile.Json5StandardV1)
    ) {
        return failed(
            ProjectionFailure.TargetNotApplicable,
            ProjectionReport.EMPTY,
            emptyList(),
        )
    }
    for (rule in request.duplicateRules) {
        val scope = rule.scope as? ProjectionPolicyScope.ExactNodeRef ?: continue
        val node = scope.node
        if (node.snapshot != snapshotIdentity) {
            return failed(
                ProjectionFailure.WrongSnapshotPolicy,
                ProjectionReport.EMPTY,
                emptyList(),
            )
        }
        val index = try {
            validateRef(node, listOf(consema.document.NodeRole.Value))
        } catch (e: JsonAccessException) {
            return failed(
                ProjectionFailure.InvalidPolicyTarget,
                ProjectionReport.EMPTY,
                emptyList(),
            )
        }
        if (valueEntity(index).kind !is InternalValueKind.Object) {
            return failed(
                ProjectionFailure.InvalidPolicyTarget,
                ProjectionReport.EMPTY,
                emptyList(),
            )
        }
    }
    val rootKind = root().kind()
    if ((request.target == ProjectionTarget.ProjectAsObjectV1 ||
            request.target == ProjectionTarget.ProjectAsEntryMappingV1) &&
        rootKind != SemanticAvailability.Available(JsonValueKind.Object)
    ) {
        return failed(
            ProjectionFailure.TargetNotApplicable,
            ProjectionReport.EMPTY,
            emptyList(),
        )
    }
    val context = ProjectionContext(this, request)
    return try {
        val value = context.projectValue(root(), ValuePath.root(), 0)
        ProjectionResult.Complete(
            CompleteProjection(
                value = value,
                fidelity = context.fidelity,
                report = ProjectionReport.of(context.report),
                provenance = ProvenanceMap.of(context.provenance),
            ),
        )
    } catch (e: ProjectionFailureException) {
        failedWithAnalysis(e.failure, context)
    }
}

private class ProjectionContext(
    val document: Document,
    val request: ProjectionRequest,
) {
    val report = ArrayList<ProjectionEvent>()
    val provenance = ArrayList<ProvenanceEntry>()
    var fidelity: Fidelity = Fidelity.Exact
    var valueNodes = 0
    val partialAnalysis = ArrayList<String>()

    fun projectValue(value: JsonValue, path: ValuePath, depth: Int): PortableValue {
        if (depth > request.limits.maxDepth) {
            throw ProjectionFailureException(ProjectionFailure.ResourceLimit("projection-depth"))
        }
        valueNodes = valueNodes.inc()
        if (valueNodes > request.limits.maxValueNodes) {
            throw ProjectionFailureException(
                ProjectionFailure.ResourceLimit("projected-value-nodes"),
            )
        }
        partialAnalysis.add("$path:Projectable")
        addOrigin(ProjectedLocation.Value(path), value.nodeRef(), value.span())
        return when (val kind = document.valueEntity(value.rawIndex()).kind) {
            is InternalValueKind.Null -> PvNull
            is InternalValueKind.Boolean -> PvBoolean(kind.value)
            is InternalValueKind.Integer -> PvInteger(kind.value)
            is InternalValueKind.Decimal -> kind.value
            is InternalValueKind.BinaryFloat64 -> PvBinaryFloat64(kind.bits)
            is InternalValueKind.String -> PvString(kind.value)
            is InternalValueKind.Array -> {
                val items = kind.elements.mapIndexed { index, entityIndex ->
                    val element = JsonArrayElement(document, entityIndex)
                    projectValue(
                        element.value(),
                        path.child(ValuePathSegment.SequenceElement(index.toLong())),
                        depth + 1,
                    )
                }
                PvArray(items)
            }
            is InternalValueKind.Object -> {
                val members = kind.members.map { JsonObjectMember(document, it) }
                projectObject(value, members, path, depth)
            }
            is InternalValueKind.Unavailable -> throw ProjectionFailureException(
                ProjectionFailure.SemanticUnavailable(value.nodeRef(), kind.reason),
            )
        }
    }

    private fun projectObject(
        objectValue: JsonValue,
        members: List<JsonObjectMember>,
        path: ValuePath,
        depth: Int,
    ): PortableValue {
        val names = members.map { member ->
            when (val name = member.name()) {
                is SemanticAvailability.Available -> name.value
                is SemanticAvailability.Unavailable -> throw ProjectionFailureException(
                    ProjectionFailure.SemanticUnavailable(member.keyNodeRef(), name.reason),
                )
            }
        }
        val hasDuplicates = names.toSet().size != names.size
        val useMapping = when (request.target) {
            ProjectionTarget.ProjectAsEntryMappingV1 -> true
            ProjectionTarget.BestExactCoreV1, ProjectionTarget.Json5BestExactCoreV1 ->
                hasDuplicates

            ProjectionTarget.ProjectAsObjectV1 -> false
        }
        if (useMapping) {
            if (request.target != ProjectionTarget.ProjectAsObjectV1) {
                fidelity = maxOf(fidelity, Fidelity.Transformed)
                pushEvent(
                    ProjectionEvent(
                        kind = ProjectionEventKind.StructureReencoded,
                        policy = null,
                        source = objectValue.nodeRef(),
                        projected = ProjectedLocation.Value(path),
                        oldCategory = "JsonObject",
                        newCategory = "EntryMapping",
                        reversible = true,
                        loss = Fidelity.Transformed,
                    ),
                )
            }
            val builder = EntryMappingBuilder()
            for ((ordinal, member) in members.withIndex()) {
                val name = names[ordinal]
                val keyPath = path.child(ValuePathSegment.EntryKey(ordinal.toLong()))
                val valuePath = path.child(ValuePathSegment.EntryValue(ordinal.toLong()))
                val association = AssociationLocation(
                    path,
                    ordinal.toLong(),
                    AssociationRole.EntryMappingEntry,
                )
                addOrigin(ProjectedLocation.Association(association), member.nodeRef(), member.span())
                addOrigin(
                    ProjectedLocation.Value(keyPath),
                    member.keyNodeRef(),
                    document.span(document.memberEntity(member.index).key),
                )
                val projected = projectValue(member.value(), valuePath, depth + 1)
                builder.push(PvString(name), projected)
            }
            return builder.build()
        }

        val policy = duplicatePolicy(objectValue.nodeRef())
        val retained = selectMembers(members, names, policy, objectValue.nodeRef())
        if (retained.size != members.size) {
            fidelity = Fidelity.Lossy
        }
        val retainedSet = retained.toSet()
        val projectedOrdinals = HashMap<Int, Int>()
        retained.forEachIndexed { ordinal, source -> projectedOrdinals[source] = ordinal }
        for ((sourceOrdinal, member) in members.withIndex()) {
            if (sourceOrdinal !in retainedSet) {
                val name = names[sourceOrdinal]
                val retainedSource = retained.first { names[it] == name }
                val projectedOrdinal = projectedOrdinals[retainedSource]!!
                pushEvent(
                    ProjectionEvent(
                        kind = ProjectionEventKind.DuplicateCollapsed,
                        policy = policy,
                        source = member.nodeRef(),
                        projected = ProjectedLocation.Association(
                            AssociationLocation(
                                path,
                                projectedOrdinal.toLong(),
                                AssociationRole.ObjectEntry,
                            ),
                        ),
                        oldCategory = "JsonObjectMember",
                        newCategory = "Collapsed",
                        reversible = false,
                        loss = Fidelity.Lossy,
                    ),
                )
            }
        }
        val builder = ObjectBuilder()
        for ((projectedOrdinal, sourceOrdinal) in retained.withIndex()) {
            val member = members[sourceOrdinal]
            val name = names[sourceOrdinal]
            val valuePath = path.child(ValuePathSegment.ObjectValue(name))
            addOrigin(
                ProjectedLocation.Association(
                    AssociationLocation(path, projectedOrdinal.toLong(), AssociationRole.ObjectEntry),
                ),
                member.nodeRef(),
                member.span(),
            )
            addOrigin(
                ProjectedLocation.Association(
                    AssociationLocation(path, projectedOrdinal.toLong(), AssociationRole.ObjectKey),
                ),
                member.keyNodeRef(),
                document.span(document.memberEntity(member.index).key),
            )
            val value = projectValue(member.value(), valuePath, depth + 1)
            builder.insert(name, value)
        }
        return builder.build()
    }

    private fun duplicatePolicy(node: NodeRef): DuplicateKeyPolicy {
        val exact = request.duplicateRules.firstOrNull { rule ->
            val scope = rule.scope as? ProjectionPolicyScope.ExactNodeRef
            scope != null && scope.node == node
        }
        if (exact != null) return exact.policy
        val global = request.duplicateRules.firstOrNull { it.scope == ProjectionPolicyScope.Global }
        return global?.policy ?: DuplicateKeyPolicy.Reject
    }

    private fun addOrigin(projected: ProjectedLocation, node: NodeRef, span: Span) {
        if (provenance.size >= request.limits.maxProvenanceEntries) {
            throw ProjectionFailureException(
                ProjectionFailure.ResourceLimit("provenance-entries"),
            )
        }
        provenance.add(
            ProvenanceEntry(
                projected,
                listOf(
                    SourceOrigin(
                        snapshot = document.snapshotIdentity,
                        node = node,
                        span = span,
                        relation = ProvenanceRelation.Direct,
                    ),
                ),
            ),
        )
    }

    private fun pushEvent(event: ProjectionEvent) {
        if (report.size >= request.limits.maxReportEntries) {
            throw ProjectionFailureException(
                ProjectionFailure.ResourceLimit("projection-report-entries"),
            )
        }
        report.add(event)
    }
}

/** Selects retained members under one duplicate policy (projection.rs:691-726). */
private fun selectMembers(
    members: List<JsonObjectMember>,
    names: List<String>,
    policy: DuplicateKeyPolicy,
    node: NodeRef,
): List<Int> {
    val counts = HashMap<String, Int>()
    for (name in names) {
        counts[name] = (counts[name] ?: 0) + 1
    }
    if (policy == DuplicateKeyPolicy.Reject) {
        val duplicate = names.firstOrNull { (counts[it] ?: 0) > 1 }
        if (duplicate != null) {
            throw ProjectionFailureException(ProjectionFailure.DuplicateKeys(node, duplicate))
        }
    }
    return when (policy) {
        DuplicateKeyPolicy.Reject, DuplicateKeyPolicy.FirstWins -> {
            val seen = HashSet<String>()
            members.indices.filter { seen.add(names[it]) }
        }
        DuplicateKeyPolicy.LastWins -> {
            val seen = HashSet<String>()
            val retained = members.indices.reversed().filter { seen.add(names[it]) }
            retained.reversed()
        }
    }
}

/** Builds a failed attempt with the ordered diagnostics and analysis
 * (projection.rs:728-752). */
private fun failed(
    failure: ProjectionFailure,
    report: ProjectionReport,
    analysis: List<String>,
): ProjectionResult {
    val diagnostic = Diagnostic.of(
        projectionCode(failure),
        DiagnosticCategory.Projection,
        Severity.Error,
        null,
        emptyList(),
        mapOf("failure" to projectionFailureName(failure)),
        emptyList(),
        emptyList(),
        0uL,
        JSON_DIAGNOSTIC_REGISTRY,
    )
    return ProjectionResult.Failed(
        FailedProjectionAttempt(
            diagnostics = listOf(diagnostic),
            report = report,
            partialAnalysis = analysis,
        ),
    )
}

private fun failedWithAnalysis(
    failure: ProjectionFailure,
    context: ProjectionContext,
): ProjectionResult = failed(failure, ProjectionReport.of(context.report), context.partialAnalysis)
