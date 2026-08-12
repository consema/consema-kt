// Explicit TOML-to-PortableValue projection.
//
// Data authority:
//   - RFC 0001 §5 (docs/rfcs/0001-toml-1.0-profile.md:78-100): the frozen
//     target `toml.best-exact-core@1` mapping table (Boolean→Boolean,
//     Integer→Integer, Float→BinaryFloat64, String→String, LocalDate→Date,
//     LocalTime→Time, LocalDateTime→LocalDateTime, OffsetDateTime→
//     OffsetDateTime, Array→Sequence, every table→Object, ArrayOfTables→
//     Sequence<Object>); provenance must map every produced value and
//     object association back to source NodeRef/span; leap seconds fail
//     the whole projection with `toml.projection.unrepresentable-
//     datetime@1`.
//   - crates/consema-toml/src/projection.rs:9-75 (ProjectionTarget,
//     ProjectionRequest, ProjectionLimits with the frozen defaults 1M value
//     nodes / 100k report entries / 2M provenance entries / depth 256),
//     :77-199 (Fidelity, ProjectedLocation, ProvenanceRelation, SourceOrigin,
//     ProvenanceEntry, ProvenanceMap, ProjectionReport, CompleteProjection,
//     FailedProjectionAttempt, ProjectionResult, ProjectionFailure),
//     :202-435 (Document::project and the mapping/provenance rules:
//     object entries produce an ObjectEntry association origin for the
//     TomlEntry and an ObjectKey association origin for the TomlKey; every
//     item produces a Value origin).
//   - conformance/vectors/toml-v1.json cases toml.projection.* (lines
//     54-70): all-core-kinds (Success/Exact/Object), provenance
//     (all_origins_snapshot_bound, object_associations_present), and
//     reject-leap-second (Failed, toml.projection.unrepresentable-
//     datetime@1, no partial value).
//   - go/toml/projection.go is a cross-reference only.
//
// Kotlin-idiomatic design: the completion algebra is the sealed
// [ProjectionResult] exactly like the Rust enum; the provenance map is an
// immutable ordered list of entries; failures carry the frozen code with
// the stable `limit` argument.

package consema.toml

import consema.core.AssociationLocation
import consema.core.AssociationRole
import consema.core.PvArray
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvDate
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvLocalDateTime
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvOffsetDateTime
import consema.core.PvString
import consema.core.PvTime
import consema.core.PortableValue
import consema.core.ValuePath
import consema.core.ValuePathSegment
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.SnapshotIdentity
import consema.document.Span
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity
import java.math.BigInteger

/** Versioned TOML projection target contract (projection.rs:9-14). */
enum class ProjectionTarget {
    /** Frozen exact-first TOML-to-core mapping (RFC 0001 §5). */
    BEST_EXACT_CORE_V1,
}

/** Immutable explicit projection request (projection.rs:16-51). */
class ProjectionRequest private constructor(
    /** Frozen target contract. */
    val target: ProjectionTarget,
    /** Projection resource limits. */
    val limits: ProjectionLimits,
) {
    companion object {
        /** Creates an explicit request with default resource limits
         * (projection.rs:24-31). */
        fun new(target: ProjectionTarget): ProjectionRequest =
            ProjectionRequest(target, ProjectionLimits.default)
    }

    /** Replaces immutable resource limits (projection.rs:33-38). */
    fun withLimits(limits: ProjectionLimits): ProjectionRequest =
        ProjectionRequest(target, limits)
}

/** Projection resource limits (projection.rs:53-75). */
data class ProjectionLimits(
    /** Maximum produced PortableValue nodes. */
    val maxValueNodes: Int,
    /** Maximum report events. */
    val maxReportEntries: Int,
    /** Maximum provenance locations and origins combined. */
    val maxProvenanceEntries: Int,
    /** Maximum recursive container depth. */
    val maxDepth: Int,
) {
    companion object {
        /** The frozen defaults (projection.rs:66-75): 1,000,000 value
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

/** Projection fidelity classification (projection.rs:77-86). */
enum class Fidelity {
    /** Target directly and completely represents TOML value semantics. */
    Exact,

    /** Complete semantics survive an explicit reversible re-encoding. */
    Transformed,

    /** At least one source fact cannot be recovered. */
    Lossy,
}

/** Projected value or association location (projection.rs:88-95). */
sealed class ProjectedLocation {
    /** Portable value location. */
    data class Value(val path: ValuePath) : ProjectedLocation()

    /** Portable association location. */
    data class Association(val location: AssociationLocation) : ProjectedLocation()
}

/** Source-to-projection relation (projection.rs:97-104). */
enum class ProvenanceRelation {
    /** Direct native semantic origin. */
    Direct,

    /** Derived without a one-to-one literal origin. */
    Derived,
}

/** One exact source origin (projection.rs:106-117). */
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

/** One many-valued provenance mapping entry (projection.rs:119-126). */
data class ProvenanceEntry(
    /** Projected value or association. */
    val projected: ProjectedLocation,
    /** One or more exact source origins. */
    val origins: List<SourceOrigin>,
)

/** Immutable multi-map from projected locations to source origins
 * (projection.rs:128-140). */
class ProvenanceMap private constructor(private val entries: List<ProvenanceEntry>) {
    companion object {
        /** Creates the map from deterministically generated entries. */
        fun of(entries: List<ProvenanceEntry>): ProvenanceMap = ProvenanceMap(entries)
    }

    /** Deterministically generated entries. */
    fun entries(): List<ProvenanceEntry> = entries

    override fun equals(other: Any?): Boolean =
        other is ProvenanceMap && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()
}

/** Complete ordered projection report (projection.rs:142-156). Exact TOML
 * 1.0 projections emit no transformation or loss events. */
class ProjectionReport private constructor(private val events: List<TomlDiagnostic>) {
    companion object {
        /** The empty report of every exact projection. */
        val empty: ProjectionReport = ProjectionReport(emptyList())
    }

    /** Ordered structured transformation/loss diagnostics. */
    fun events(): List<TomlDiagnostic> = events
}

/** Complete successful projection; its value is never partial
 * (projection.rs:158-169). */
data class CompleteProjection(
    /** Complete immutable public value. */
    val value: PortableValue,
    /** Worst fidelity of the whole operation. */
    val fidelity: Fidelity,
    /** Machine-readable transformation/loss report. */
    val report: ProjectionReport,
    /** Value and object-association provenance. */
    val provenance: ProvenanceMap,
)

/** Failed attempt without a partial PortableValue (projection.rs:171-180). */
data class FailedProjectionAttempt(
    /** Ordered diagnostics explaining the failure. */
    val diagnostics: List<TomlDiagnostic>,
    /** Events discovered before the failed completion check. */
    val report: ProjectionReport,
    /** Stable paths that were locally analyzed before failure. */
    val partialAnalysis: List<String>,
)

/** Projection completion algebra (projection.rs:182-189). */
sealed class ProjectionResult {
    /** Complete success. */
    data class Complete(val projection: CompleteProjection) : ProjectionResult()

    /** Failed attempt with no value. */
    data class Failed(val attempt: FailedProjectionAttempt) : ProjectionResult()
}

/** Stable projection failure category (projection.rs:191-200). */
internal sealed class ProjectionFailure {
    /** TOML temporal fields are outside PortableValue v1. */
    data object UnrepresentableDateTime : ProjectionFailure()

    /** Declared resource limit was reached. */
    data class ResourceLimit(val name: String) : ProjectionFailure()

    /** A valid TOML table violated the core unique-key invariant. */
    data object CoreInvariant : ProjectionFailure()
}

/** Internal projection failure carrier. */
internal class ProjectionException(val failure: ProjectionFailure) :
    Exception("toml projection failed")

/**
 * Applies an immutable explicit projection request (projection.rs:202-227).
 * The projection is exact for every Complete TOML document; the whole
 * operation fails without any partial value when a limit or the temporal
 * closure is violated (RFC 0001 §5).
 */
fun TomlDocument.project(request: ProjectionRequest): ProjectionResult {
    val context = ProjectionContext(this, request.limits)
    return try {
        val value = context.projectItem(rootIndex, ValuePath.root(), 0)
        ProjectionResult.Complete(
            CompleteProjection(
                value = value,
                fidelity = Fidelity.Exact,
                report = ProjectionReport.empty,
                provenance = ProvenanceMap.of(context.provenanceEntries),
            ),
        )
    } catch (e: ProjectionException) {
        ProjectionResult.Failed(
            FailedProjectionAttempt(
                diagnostics = listOf(failureDiagnostic(this, e.failure)),
                report = ProjectionReport.empty,
                partialAnalysis = emptyList(),
            ),
        )
    }
}

/** The projection execution state: counters and the ordered provenance
 * entries (projection.rs:229-235). */
internal class ProjectionContext(
    private val document: TomlDocument,
    private val limits: ProjectionLimits,
) {
    private var valueNodes = 0
    private var provenanceUnits = 0
    val provenanceEntries = ArrayList<ProvenanceEntry>()

    fun projectItem(index: Int, path: ValuePath, depth: Int): PortableValue {
        if (depth > limits.maxDepth) {
            throw ProjectionException(ProjectionFailure.ResourceLimit("max_depth"))
        }
        valueNodes += 1
        if (valueNodes > limits.maxValueNodes) {
            throw ProjectionException(ProjectionFailure.ResourceLimit("max_value_nodes"))
        }

        val value: PortableValue = when (val kind = document.itemEntity(index).kind) {
            is InternalItemKind.String -> PvString(kind.value)
            is InternalItemKind.Integer -> PvInteger(BigInteger.valueOf(kind.value))
            is InternalItemKind.Float -> PvBinaryFloat64(kind.bits)
            is InternalItemKind.Boolean -> PvBoolean(kind.value)
            is InternalItemKind.DateTime -> projectDateTime(kind.value)
            is InternalItemKind.Array, is InternalItemKind.ArrayOfTables -> {
                val elements = when (kind) {
                    is InternalItemKind.Array -> kind.elements
                    is InternalItemKind.ArrayOfTables -> kind.elements
                    else -> error("unreachable")
                }
                val items = ArrayList<PortableValue>(elements.size)
                for ((ordinal, elementIndex) in elements.withIndex()) {
                    val element = document.entity(elementIndex).kind as? EntityKind.Element
                        ?: error("typed TOML element")
                    val childPath = path.child(
                        ValuePathSegment.SequenceElement(ordinal.toLong()),
                    )
                    items.add(projectItem(element.element.item, childPath, depth + 1))
                    addOrigin(
                        ProjectedLocation.Value(childPath),
                        elementIndex,
                        NodeRole.TomlArrayElement,
                        ProvenanceRelation.Direct,
                    )
                }
                PvArray(items)
            }
            is InternalItemKind.InlineTable, is InternalItemKind.Table -> {
                val entries = when (kind) {
                    is InternalItemKind.InlineTable -> kind.entries
                    is InternalItemKind.Table -> kind.entries
                    else -> error("unreachable")
                }
                val objectEntries = ArrayList<consema.core.Entry>(entries.size)
                for (entryIndex in entries) {
                    val entry = document.entity(entryIndex).kind as? EntityKind.Entry
                        ?: error("typed TOML entry")
                    val key = document.entity(entry.entry.key).kind as? EntityKind.Key
                        ?: error("typed TOML key")
                    val name = key.key.name
                    val childPath = path.child(ValuePathSegment.ObjectValue(name))
                    val child = projectItem(entry.entry.item, childPath, depth + 1)
                    objectEntries.add(consema.core.Entry(name, child))
                    val ordinal = entry.entry.ordinal.toLong()
                    addOrigin(
                        ProjectedLocation.Association(
                            AssociationLocation(path, ordinal, AssociationRole.ObjectEntry),
                        ),
                        entryIndex,
                        NodeRole.TomlEntry,
                        ProvenanceRelation.Direct,
                    )
                    addOrigin(
                        ProjectedLocation.Association(
                            AssociationLocation(path, ordinal, AssociationRole.ObjectKey),
                        ),
                        entry.entry.key,
                        NodeRole.TomlKey,
                        ProvenanceRelation.Direct,
                    )
                }
                PvObject(objectEntries)
            }
        }
        addOrigin(
            ProjectedLocation.Value(path),
            index,
            NodeRole.TomlItem,
            ProvenanceRelation.Direct,
        )
        return value
    }

    private fun addOrigin(
        projected: ProjectedLocation,
        index: Int,
        role: NodeRole,
        relation: ProvenanceRelation,
    ) {
        provenanceUnits += 1
        if (provenanceUnits > limits.maxProvenanceEntries) {
            throw ProjectionException(ProjectionFailure.ResourceLimit("max_provenance_entries"))
        }
        val origin = SourceOrigin(
            snapshot = document.snapshotIdentity,
            node = document.nodeRef(index, role),
            span = document.entity(index).span,
            relation = relation,
        )
        val existing = provenanceEntries.firstOrNull { it.projected == projected }
        if (existing != null) {
            provenanceEntries[provenanceEntries.indexOf(existing)] =
                existing.copy(origins = existing.origins + origin)
        } else {
            provenanceEntries.add(ProvenanceEntry(projected, listOf(origin)))
        }
    }
}

/** Maps one native TOML temporal datum into PortableValue v1
 * (projection.rs:367-408). Leap seconds and other out-of-closure fields
 * fail the whole projection with UnrepresentableDateTime. */
internal fun projectDateTime(value: TomlDateTime): PortableValue =
    try {
        val date = value.date?.let { PvDate.of(BigInteger.valueOf(it.year.toLong()), it.month, it.day) }
        val time = value.time?.let { coreTime(it) }
        when {
            value.date != null && value.time == null && value.offset == null -> date!!
            value.date == null && value.time != null && value.offset == null -> time!!
            value.date != null && value.time != null && value.offset == null ->
                PvLocalDateTime(date!!, time!!)
            value.date != null && value.time != null && value.offset != null -> {
                val local = PvLocalDateTime(date!!, time!!)
                val offsetSeconds = when (val offset = value.offset) {
                    TomlOffset.Z -> 0
                    is TomlOffset.CustomMinutes -> offset.minutes * 60
                    null -> throw ProjectionException(ProjectionFailure.UnrepresentableDateTime)
                }
                PvOffsetDateTime.of(local, offsetSeconds)
            }
            else -> throw ProjectionException(ProjectionFailure.UnrepresentableDateTime)
        }
    } catch (e: ProjectionException) {
        throw e
    } catch (e: consema.core.InvalidTemporalException) {
        // Every core construction failure maps to the frozen temporal code
        // (map_temporal_build_error, projection.rs:406-408).
        throw ProjectionException(ProjectionFailure.UnrepresentableDateTime)
    }

/** Builds the core Time from TOML fields; the fraction is nanoseconds ×
 * 10^-9 (projection.rs:398-404). */
private fun coreTime(value: TomlTime): PvTime {
    val fraction = PvDecimal.of(
        BigInteger.valueOf(value.nanosecond),
        BigInteger.valueOf(-9),
    )
    return PvTime.of(value.hour, value.minute, value.second, fraction)
}

/** Maps the failure to its frozen diagnostic (projection.rs:410-435):
 * unrepresentable-datetime carries the root span as primary;
 * resource-limit carries the stable `limit` argument; core-invariant has
 * no primary. */
internal fun failureDiagnostic(document: TomlDocument, failure: ProjectionFailure): TomlDiagnostic {
    val (code, category, primary) = when (failure) {
        ProjectionFailure.UnrepresentableDateTime -> Triple(
            TOML_PROJECTION_UNREPRESENTABLE_DATETIME,
            DiagnosticCategory.Projection,
            document.root().span,
        )
        is ProjectionFailure.ResourceLimit -> Triple(
            "core.projection.resource-limit@1",
            DiagnosticCategory.Resource,
            null,
        )
        ProjectionFailure.CoreInvariant -> Triple(
            TOML_PROJECTION_CORE_INVARIANT,
            DiagnosticCategory.Projection,
            null,
        )
    }
    val arguments = if (failure is ProjectionFailure.ResourceLimit) {
        mapOf("limit" to failure.name)
    } else {
        emptyMap()
    }
    return TomlDiagnostic(
        code = code,
        category = category,
        severity = Severity.Error,
        startByte = primary?.startByte,
        endByte = primary?.endByte,
        arguments = arguments,
        notes = emptyList(),
        occurrence = 0,
    )
}
