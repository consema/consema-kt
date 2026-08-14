// Two-stage explicit projection: Document -> the versioned `hcl.body@1`
// record with fidelity, report, and provenance (RFC 0014 §8, RFC 0004
// §7-§8).
//
// Data authority:
//   - RFC 0014 §8 (https://github.com/consema/consema/blob/main/docs/rfcs/0014-hcl-family-profiles-v1.md): the
//     default exact target `hcl.projection.body@1` produces the versioned
//     `hcl.body@1` record — one ordered body of items, each an attribute
//     (name string + value) or a block (type, ordered labels, nested
//     `hcl.body@1`), where every attribute value is literal-complete and
//     rendered as a typed member; attribute order, block order, label
//     order, and duplicate object-constructor keys are preserved exactly;
//     a derived expression fails atomically with
//     `hcl.projection.non-literal-expression@1` unless the explicit
//     `ProjectExpression` policy substitutes the authorized `hcl.expression@1`
//     ExtendedValue with one `Transformed` event per substituted
//     expression, with value and expression provenance; a Recovered
//     Document never projects.
//   - RFC 0014 §8.1: the literal-complete boundary.
//   - RFC 0014 §6: structural equality, canonical decimals,
//     exact decoded string text, ordered duplicates.
//   - https://github.com/consema/consema-rs/blob/main/consema-hcl/src/projection.rs pins the record shape
//     (projection.rs: `{ "record": "hcl.body@1", "items": [...] }`,
//     the typed members, the expression record
//     `{ "record": "hcl.expression@1", "kind", "text", "fingerprint" }`,
//     the kind family table), the failure codes (projection.rs),
//     and the codec envelope (projection.rs).
//   - The structural fingerprint is defined in Expression.kt
//     (materialization.rs).
//   - consema-go/go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design: the completion algebra is a sealed class, so
// exhaustive `when` over Complete/Failed can never meet an unknown outcome;
// failures carry their frozen registered code.

package consema.hcl

import consema.core.AssociationLocation
import consema.core.Entry
import consema.core.EntryMappingBuilder
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.ValuePath
import consema.document.FormationStatus
import consema.document.NodeRef
import consema.document.SnapshotIdentity
import consema.document.Span
import java.math.BigInteger

/** The versioned `hcl.body@1` record name (RFC 0014 §8.2; projection.rs). */
const val HCL_BODY_RECORD: String = "hcl.body@1"

/** The versioned `hcl.expression@1` record name (RFC 0014 §8.2;
 * projection.rs). */
const val HCL_EXPRESSION_RECORD: String = "hcl.expression@1"

/** The stable type identifier of the `hcl.expression@1` ExtendedValue
 * (projection.rs). */
const val HCL_EXPRESSION_TYPE_ID: String = "hcl.expression"

/** The canonical payload codec of the `hcl.expression@1` ExtendedValue
 * (projection.rs). */
const val HCL_EXPRESSION_CODEC: String = "hcl.expression.canonical@1"

/** Versioned projection target contract (RFC 0014 §8.2; projection.rs
 * 24). */
enum class ProjectionTarget {
    /** The default exact target: one ordered `hcl.body@1` record. */
    BodyV1,
}

/** The explicit derived-expression policy (RFC 0014 §8.2; projection.rs). */
enum class ExpressionPolicy {
    /** A derived expression fails the projection atomically with
     * `hcl.projection.non-literal-expression@1`. */
    Default,

    /** Each derived expression is projected as the authorized
     * `hcl.expression@1` ExtendedValue, reported as one `Transformed`
     * event per substitution. */
    ProjectExpression,
}

/** Projection resource limits (projection.rs). */
data class ProjectionLimits(
    /** Maximum produced PortableValue nodes. */
    val maxValueNodes: Int,
    /** Maximum report events. */
    val maxReportEntries: Int,
    /** Maximum provenance locations. */
    val maxProvenanceEntries: Int,
    /** Maximum visited native source nodes. */
    val maxSourceNodes: Int,
    /** Maximum recursion depth. */
    val maxDepth: Int,
) {
    companion object {
        /** The frozen defaults (projection.rs). */
        val default = ProjectionLimits(
            maxValueNodes = 1_000_000,
            maxReportEntries = 100_000,
            maxProvenanceEntries = 2_000_000,
            maxSourceNodes = 1_000_000,
            maxDepth = 256,
        )
    }
}

/** Projection fidelity classification (RFC 0004 §7). */
enum class Fidelity {
    /** Target directly and completely represents covered native semantics. */
    Exact,

    /** Complete semantics survive an explicit reversible re-encoding. */
    Transformed,

    /** At least one source fact cannot be recovered. */
    Lossy,
}

/** Projected value or association location (RFC 0004 §8). */
sealed class ProjectedLocation {
    /** Portable value location. */
    data class Value(val path: ValuePath) : ProjectedLocation()

    /** Portable association location. */
    data class Association(val location: AssociationLocation) : ProjectedLocation()
}

/** Source-to-projection relation (RFC 0004 §8). */
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

/** One exact source origin (RFC 0004 §8). */
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

/** One many-valued provenance mapping entry. */
data class ProvenanceEntry(
    /** Projected value or association. */
    val projected: ProjectedLocation,
    /** Zero or more source origins. */
    val origins: List<SourceOrigin>,
)

/** Immutable multi-map from projected locations to source origins. */
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

/** Machine-readable projection event category (RFC 0014 §8.2). */
enum class ProjectionEventKind {
    /** A derived expression was substituted by the authorized
     * `hcl.expression@1` ExtendedValue under the explicit policy. */
    ExpressionSubstituted,
}

/** One structured projection report event. */
data class ProjectionEvent(
    /** Stable event kind. */
    val kind: ProjectionEventKind,
    /** Exact source expression identity. */
    val expression: NodeRef,
    /** Result location when one exists. */
    val projected: ProjectedLocation?,
    /** Fidelity impact. */
    val impact: Fidelity,
)

/** Complete ordered projection report. */
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

/** Complete successful projection; its value is never partial (RFC 0004
 * §7). */
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

/** Failed attempt without a partial PortableValue. */
data class FailedProjectionAttempt(
    /** Ordered operation diagnostics. */
    val diagnostics: List<HclDiagnostic>,
    /** Events discovered before the failed completion check. */
    val report: ProjectionReport,
    /** Stable path descriptions of locally analyzed regions. */
    val partialAnalysis: List<String>,
)

/** Projection completion algebra (RFC 0004 §7). */
sealed class ProjectionResult {
    /** Complete success. */
    data class Complete(val projection: CompleteProjection) : ProjectionResult()

    /** Failed attempt with no value. */
    data class Failed(val attempt: FailedProjectionAttempt) : ProjectionResult()
}

/** Stable projection failure category (RFC 0014 §8.2; projection.rs
 * 474). */
sealed class ProjectionFailure {
    /** A Recovered document never projects. */
    data object IncompleteDocument : ProjectionFailure()

    /** A derived expression under the default policy. */
    data object NonLiteralExpression : ProjectionFailure()

    /** A native fact cannot enter the record (for example a tuple/object
     * parenthesized object key). */
    data class Unrepresentable(val reason: String) : ProjectionFailure()

    /** A declared resource limit was reached; output is not truncated to
     * success. */
    data class ResourceLimit(val name: String) : ProjectionFailure()

    /** An internal invariant failure of the record codec. */
    data object CoreInvariant : ProjectionFailure()

    /** The frozen registered code (projection.rs). */
    val code: String
        get() = when (this) {
            IncompleteDocument -> HCL_PROJECTION_INCOMPLETE_DOCUMENT
            NonLiteralExpression -> HCL_PROJECTION_NON_LITERAL_EXPRESSION
            is Unrepresentable -> HCL_PROJECTION_UNREPRESENTABLE
            is ResourceLimit -> HCL_PROJECTION_RESOURCE_LIMIT
            CoreInvariant -> HCL_PROJECTION_CORE_INVARIANT
        }
}

/** The canonical payload of one `hcl.expression@1` ExtendedValue: the kind
 * family spelling, the exact source text, and the structural fingerprint
 * (projection.rs). */
data class ExpressionPayload(
    /** Kind family spelling. */
    val kind: String,
    /** Exact source text. */
    val text: String,
    /** Structural fingerprint. */
    val fingerprint: ULong,
) {
    /** Encodes the canonical payload bytes under the `hcl.expression@1`
     * codec (projection.rs). */
    fun encode(): ByteArray {
        val output = ArrayList<Byte>()
        encodeBlob(kind.toByteArray(Charsets.UTF_8), output)
        encodeBlob(text.toByteArray(Charsets.UTF_8), output)
        val fingerprintBytes = ByteArray(8)
        var value = fingerprint
        for (index in 0 until 8) {
            fingerprintBytes[index] = (value and 0xffUL).toByte()
            value = value shr 8
        }
        output.addAll(fingerprintBytes.toList())
        return output.toByteArray()
    }

    /** Decodes one canonical payload; null for an envelope violation. */
    companion object {
        fun decode(payload: ByteArray): ExpressionPayload? {
            val cursor = PayloadCursor(payload)
            val kindBytes = cursor.blob() ?: return null
            val kind = String(kindBytes, Charsets.UTF_8)
            if (kind !in EXPRESSION_KIND_FAMILY_SPELLINGS) {
                return null
            }
            val textBytes = cursor.blob() ?: return null
            val text = String(textBytes, Charsets.UTF_8)
            if (text.isEmpty()) {
                return null
            }
            val fingerprintBytes = cursor.bytes(8) ?: return null
            if (!cursor.finished()) {
                return null
            }
            var fingerprint = 0UL
            for (index in 7 downTo 0) {
                fingerprint = (fingerprint shl 8) or (fingerprintBytes[index].toULong() and 0xffUL)
            }
            return ExpressionPayload(kind, text, fingerprint)
        }
    }
}

/** A cursor over the canonical payload envelope (projection.rs). */
private class PayloadCursor(private val bytes: ByteArray) {
    private var offset = 0

    fun blob(): ByteArray? {
        val length = varint() ?: return null
        val end = offset + length
        if (end < offset || end > bytes.size) {
            return null
        }
        val blob = bytes.copyOfRange(offset, end)
        offset = end
        return blob
    }

    fun bytes(length: Int): ByteArray? {
        val end = offset + length
        if (end < offset || end > bytes.size) {
            return null
        }
        val result = bytes.copyOfRange(offset, end)
        offset = end
        return result
    }

    fun finished(): Boolean = offset == bytes.size

    private fun varint(): Int? {
        var value = 0
        var shift = 0
        while (true) {
            if (offset >= bytes.size) {
                return null
            }
            val byte = bytes[offset].toInt() and 0xff
            offset += 1
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) {
                return value
            }
            shift += 7
            if (shift > 28) {
                return null
            }
        }
    }
}

/** Encodes one length-prefixed blob into the canonical payload envelope
 * (projection.rs). */
private fun encodeBlob(bytes: ByteArray, output: ArrayList<Byte>) {
    var length = bytes.size
    while (true) {
        var octet = length and 0x7f
        length = length ushr 7
        if (length != 0) {
            octet = octet or 0x80
        }
        output.add(octet.toByte())
        if (length == 0) {
            break
        }
    }
    for (byte in bytes) {
        output.add(byte)
    }
}

/** The internal projection failure carrier: [ProjectionFailure] is a
 * sealed category, not a Throwable, so the context throws this wrapper and
 * [project] maps it back to the sealed category. */
private class ProjectionFailureException(val failure: ProjectionFailure) :
    Exception("hcl projection: ${failure.code}")

/**
 * Projects one complete HCL document under one explicit target and policy
 * contract (RFC 0014 §8; projection.rs). The projection is atomic:
 * a recovered source, a derived expression under the default policy, an
 * unrepresentable native fact, or a resource limit returns no partial
 * value, provenance, or report (hard gate 4).
 */
fun project(
    document: HclDocument,
    target: ProjectionTarget = ProjectionTarget.BodyV1,
    policy: ExpressionPolicy = ExpressionPolicy.Default,
    limits: ProjectionLimits = ProjectionLimits.default,
): ProjectionResult {
    if (document.formationStatus() != FormationStatus.Complete) {
        return failed(ProjectionFailure.IncompleteDocument)
    }
    return try {
        val context = ProjectionContext(document, limits, policy)
        val value = when (target) {
            ProjectionTarget.BodyV1 -> context.projectBodyRecord(document.rootBody())
        }
        ProjectionResult.Complete(
            CompleteProjection(
                value = value,
                fidelity = context.fidelity,
                report = ProjectionReport.of(context.report),
                provenance = ProvenanceMap.of(context.provenance),
            ),
        )
    } catch (e: ProjectionFailureException) {
        failed(e.failure)
    }
}

private fun failed(failure: ProjectionFailure): ProjectionResult {
    val category = when (failure) {
        is ProjectionFailure.ResourceLimit -> consema.protocol.DiagnosticCategory.Resource
        else -> consema.protocol.DiagnosticCategory.Projection
    }
    val diagnostic = HclDiagnostic(
        code = failure.code,
        category = category,
        severity = consema.protocol.Severity.Error,
        startByte = null,
        endByte = null,
        arguments = if (failure is ProjectionFailure.Unrepresentable) {
            mapOf("reason" to failure.reason)
        } else if (failure is ProjectionFailure.ResourceLimit) {
            mapOf("name" to failure.name)
        } else {
            emptyMap()
        },
        notes = emptyList(),
        occurrence = 0,
    )
    return ProjectionResult.Failed(
        FailedProjectionAttempt(
            diagnostics = listOf(diagnostic),
            report = ProjectionReport.EMPTY,
            partialAnalysis = emptyList(),
        ),
    )
}

/** The projection state (projection.rs). */
private class ProjectionContext(
    private val document: HclDocument,
    private val limits: ProjectionLimits,
    private val policy: ExpressionPolicy,
) {
    internal val report = ArrayList<ProjectionEvent>()
    internal val provenance = ArrayList<ProvenanceEntry>()
    internal var fidelity: Fidelity = Fidelity.Exact
    private var valueNodes = 0
    private var sourceNodes = 0

    private fun step() {
        sourceNodes += 1
        if (sourceNodes > limits.maxSourceNodes) {
            throw ProjectionFailureException(ProjectionFailure.ResourceLimit("max_source_nodes"))
        }
        if (sourceNodes > limits.maxDepth) {
            throw ProjectionFailureException(ProjectionFailure.ResourceLimit("max_depth"))
        }
    }

    private fun reserveValue(count: Int) {
        valueNodes += count
        if (valueNodes > limits.maxValueNodes) {
            throw ProjectionFailureException(ProjectionFailure.ResourceLimit("max_value_nodes"))
        }
    }

    private fun event(expression: NodeRef, path: ValuePath) {
        if (report.size >= limits.maxReportEntries) {
            throw ProjectionFailureException(ProjectionFailure.ResourceLimit("max_report_entries"))
        }
        report.add(
            ProjectionEvent(
                kind = ProjectionEventKind.ExpressionSubstituted,
                expression = expression,
                projected = ProjectedLocation.Value(path),
                impact = Fidelity.Transformed,
            ),
        )
        fidelity = Fidelity.Transformed
    }

    private fun origin(node: NodeRef, span: Span) {
        if (provenance.size >= limits.maxProvenanceEntries) {
            throw ProjectionFailureException(ProjectionFailure.ResourceLimit("max_provenance_entries"))
        }
        provenance.add(
            ProvenanceEntry(
                projected = ProjectedLocation.Value(ValuePath.root()),
                origins = listOf(
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

    /** The one ordered `hcl.body@1` record of a body (projection.rs
     * 829). */
    fun projectBodyRecord(body: HclBodyHandle): PortableValue {
        reserveValue(1)
        val items = ArrayList<PortableValue>()
        var itemOrdinal = 0
        for (item in body.items()) {
            when (item) {
                is HclBodyItemHandle.Attribute -> {
                    val attribute = item.handle
                    step()
                    reserveValue(2)
                    val value = projectLiteral(attribute.expression())
                    val member = PvObject(
                        listOf(
                            Entry("kind", PvString("attribute")),
                            Entry("name", PvString(attribute.name())),
                            Entry("value", value),
                        ),
                    )
                    items.add(member)
                    itemOrdinal += 1
                }
                is HclBodyItemHandle.Block -> {
                    val block = item.handle
                    step()
                    reserveValue(2)
                    val labelValues = block.labels().map { label ->
                        PvString(label.text())
                    }
                    val nested = projectBodyRecord(block.body())
                    val member = PvObject(
                        listOf(
                            Entry("kind", PvString("block")),
                            Entry("type", PvString(block.blockType())),
                            Entry("labels", PvArray(labelValues)),
                            Entry("body", nested),
                        ),
                    )
                    items.add(member)
                    itemOrdinal += 1
                }
            }
        }
        return PvObject(
            listOf(
                Entry("record", PvString(HCL_BODY_RECORD)),
                Entry("items", PvArray(items)),
            ),
        )
    }

    /** One attribute value member of the record: the raw typed member the
     * projection publishes, or the authorized `hcl.expression@1` record
     * under the explicit policy (RFC 0014 §8.2; projection.rs). */
    private fun projectLiteral(handle: HclExpressionHandle): PortableValue {
        val expression = handle.expressionValue()
        val literal = literalValue(expression)
        if (literal == null) {
            if (policy != ExpressionPolicy.ProjectExpression) {
                throw ProjectionFailureException(ProjectionFailure.NonLiteralExpression)
            }
            event(handle.nodeRef, ValuePath.root())
            origin(handle.nodeRef, handle.span)
            val payload = ExpressionPayload(
                kind = expression.kind.kindFamily,
                text = handle.text(),
                fingerprint = structuralFingerprint(expression),
            )
            // The authorized `hcl.expression@1` ExtendedValue record is the
            // attribute value member itself (projection.rs); the
            // `{kind: "expression", expression: ...}` wrapper is only the
            // materialization value-record spelling, never the published
            // projection member.
            return PvObject(
                listOf(
                    Entry("record", PvString(HCL_EXPRESSION_RECORD)),
                    Entry("kind", PvString(payload.kind)),
                    Entry("text", PvString(payload.text)),
                    Entry("fingerprint", PvString(fingerprintHex(payload.fingerprint))),
                ),
            )
        }
        return literalToValue(literal)
    }

    /** One literal-complete value mapped to its raw typed PortableValue
     * member — the form the projection publishes (RFC 0014 §9;
     * projection.rs). */
    private fun literalToValue(literal: HclLiteralValue): PortableValue = when (literal) {
        is HclLiteralValue.String -> PvString(literal.text)
        is HclLiteralValue.Integer -> PvInteger(BigInteger(literal.text))
        is HclLiteralValue.Decimal -> decimalFromCanonical(literal.text)
        is HclLiteralValue.Boolean -> PvBoolean(literal.value)
        is HclLiteralValue.Null -> PvNull
        is HclLiteralValue.Tuple -> PvArray(literal.elements.map { literalToValue(it) })
        is HclLiteralValue.Object -> {
            // Object members project as an ordered EntryMapping, because
            // object-constructor keys may repeat and may be non-identifier
            // spellings; duplicate keys remain ordered entries
            // (projection.rs).
            val builder = EntryMappingBuilder()
            for (entry in literal.entries) {
                builder.push(
                    PvString(literalKeyString(entry.key)),
                    literalToValue(entry.value),
                )
            }
            builder.build()
        }
    }

    /** The canonical string spelling of one literal key (projection.rs
     * 65). */
    private fun literalKeyString(key: HclLiteralKey): String = when (key) {
        is HclLiteralKey.Identifier -> key.name
        is HclLiteralKey.Number -> key.canonical
        is HclLiteralKey.String -> key.text
        is HclLiteralKey.Value -> when (val value = key.value) {
            is HclLiteralValue.String -> value.text
            is HclLiteralValue.Integer -> value.text
            is HclLiteralValue.Decimal -> value.text
            is HclLiteralValue.Boolean -> if (value.value) "true" else "false"
            is HclLiteralValue.Null -> "null"
            is HclLiteralValue.Tuple, is HclLiteralValue.Object ->
                throw ProjectionFailureException(ProjectionFailure.Unrepresentable("object-key"))
        }
    }
}

/** The 16 lowercase hex digits of one fingerprint. */
internal fun fingerprintHex(fingerprint: ULong): String =
    fingerprint.toString(16).padStart(16, '0')
