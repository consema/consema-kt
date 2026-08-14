// The immutable JSON-family document model: snapshot-bound native value
// handles, member/element associations, and the lossless coverage index.
//
// Data authority:
//   - RFC 0005 §2 (https://github.com/consema/consema/blob/main/docs/rfcs/0005-json-family-production-v1.md):
//     formation is the same Complete | Recovered algebra as the existing JSON
//     family parser; source/encoding and configured resource failures are
//     fatal; recovered syntax never turns into available native semantics.
//   - https://github.com/consema/consema-rs/blob/main/consema-json/src/lib.rs (Document), lib.rs
//     (SemanticAvailability / SemanticUnavailable / JsonValueKind),
//     lib.rs (JsonValue / JsonObjectMember / JsonArrayElement),
//     lib.rs (entities). consema-go/go/json/document.go is a cross-reference
//     only.
//
// Kotlin-idiomatic design (NOT a translation): the Rust borrowed handles are
// immutable handle classes carrying (document, index) — the Kotlin analogue
// of the Go handle structs; entity storage is a private sealed hierarchy and
// `when` over it is exhaustive. The document keeps its own
// consema.document.DocumentAuthority (module-internal, shared across family
// packages per kotlin/src/main/kotlin/consema/document/Location.kt).

package consema.json

import consema.document.DocumentAuthority
import consema.document.FormatFamilyId
import consema.document.FormationStatus
import consema.document.LosslessStructuralIndex
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.ParseLimits
import consema.document.ProfileId
import consema.document.SnapshotIdentity
import consema.document.SourceSnapshot
import consema.document.Span
import consema.core.PvDecimal
import consema.document.StructuralPiece
import consema.protocol.Diagnostic
import java.math.BigInteger

/** Regional semantic availability (lib.rs). */
sealed class SemanticAvailability<out T> {
    /** Complete native meaning. */
    data class Available<T>(val value: T) : SemanticAvailability<T>()

    /** Recovery or an invalid literal prevented native meaning. */
    data class Unavailable(val reason: SemanticUnavailable) : SemanticAvailability<Nothing>()

    /** Maps an available value while preserving unavailability (lib.rs). */
    fun <U> map(function: (T) -> U): SemanticAvailability<U> =
        when (this) {
            is Available -> SemanticAvailability.Available(function(value))
            is Unavailable -> this
        }
}

/** Stable reason that a region has no native semantic value (lib.rs). */
enum class SemanticUnavailable {
    /** Parser inserted a zero-width missing value. */
    Missing,

    /** Source bytes occupy an explicit error region. */
    ErrorRegion,

    /** Literal syntax was complete but its decoded meaning was invalid. */
    InvalidLiteral,

    /** A child prevents complete container semantics. */
    ChildUnavailable,
}

/**
 * Native JSON value category, preserving integer-form versus decimal-form
 * numbers (lib.rs). Enum names ARE the frozen vector spellings
 * (member_kinds / element_kinds / native-query kind).
 */
enum class JsonValueKind {
    /** JSON null. */
    Null,

    /** Boolean. */
    Boolean,

    /** Number without decimal point or exponent. */
    Integer,

    /** Number with decimal point or exponent. */
    Decimal,

    /** Exact frozen IEEE-754 binary64 bits for a JSON5 non-finite literal
     * (RFC 0005 §6). */
    BinaryFloat64,

    /** Decoded string. */
    String,

    /** Ordered array. */
    Array,

    /** Ordered object with duplicate member preservation. */
    Object,
}

/** Internal value semantics of one value entity (lib.rs). */
internal sealed class InternalValueKind {
    data object Null : InternalValueKind()

    data class Boolean(val value: kotlin.Boolean) : InternalValueKind()

    data class Integer(val value: BigInteger) : InternalValueKind()

    data class Decimal(val value: PvDecimal) : InternalValueKind()

    data class BinaryFloat64(val bits: Long) : InternalValueKind()

    data class String(val value: kotlin.String) : InternalValueKind()

    data class Array(val elements: List<Int>) : InternalValueKind()

    data class Object(val members: List<Int>) : InternalValueKind()

    data class Unavailable(val reason: SemanticUnavailable) : InternalValueKind()
}

/** One value syntax entity (lib.rs). */
internal data class ValueEntity(
    val span: Span,
    val literalSpan: Span?,
    val complete: Boolean,
    val kind: InternalValueKind,
)

/** One object member association entity (lib.rs). */
internal data class MemberEntity(
    val span: Span,
    val key: Int,
    val value: Int,
    val ordinal: Int,
)

/** One array element association entity (lib.rs). */
internal data class ElementEntity(
    val span: Span,
    val value: Int,
    val ordinal: Int,
)

/** One structural entity (lib.rs). */
internal sealed class Entity {
    abstract val span: Span

    data class Value(val entity: ValueEntity) : Entity() {
        override val span: Span get() = entity.span
    }

    data class Member(val entity: MemberEntity) : Entity() {
        override val span: Span get() = entity.span
    }

    data class Element(val entity: ElementEntity) : Entity() {
        override val span: Span get() = entity.span
    }
}

/**
 * Borrowed typed native semantic value bound to one Document snapshot
 * (lib.rs). Kotlin has no borrowed references, so the handle carries
 * its owning document and entity index; handles are immutable.
 */
class JsonValue internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    /** Exact value node handle (lib.rs). */
    fun nodeRef(): NodeRef = document.nodeRef(index, NodeRole.Value)

    /** Exact syntax span, possibly zero-width for a missing recovered node
     * (lib.rs). */
    fun span(): Span = document.entity(index).span

    /** Native semantic category when available (lib.rs). */
    fun kind(): SemanticAvailability<JsonValueKind> =
        when (val kind = document.valueEntity(index).kind) {
            is InternalValueKind.Null -> SemanticAvailability.Available(JsonValueKind.Null)
            is InternalValueKind.Boolean -> SemanticAvailability.Available(JsonValueKind.Boolean)
            is InternalValueKind.Integer -> SemanticAvailability.Available(JsonValueKind.Integer)
            is InternalValueKind.Decimal -> SemanticAvailability.Available(JsonValueKind.Decimal)
            is InternalValueKind.BinaryFloat64 ->
                SemanticAvailability.Available(JsonValueKind.BinaryFloat64)

            is InternalValueKind.String -> SemanticAvailability.Available(JsonValueKind.String)
            is InternalValueKind.Array -> SemanticAvailability.Available(JsonValueKind.Array)
            is InternalValueKind.Object -> SemanticAvailability.Available(JsonValueKind.Object)
            is InternalValueKind.Unavailable ->
                SemanticAvailability.Unavailable(kind.reason)
        }

    /** Boolean value (lib.rs). */
    fun asBoolean(): SemanticAvailability<Boolean?> =
        when (val kind = document.valueEntity(index).kind) {
            is InternalValueKind.Boolean -> SemanticAvailability.Available(kind.value)
            is InternalValueKind.Unavailable -> SemanticAvailability.Unavailable(kind.reason)
            else -> SemanticAvailability.Available<Boolean?>(null)
        }

    /** Exact arbitrary-precision integer (lib.rs). */
    fun asInteger(): SemanticAvailability<BigInteger?> =
        when (val kind = document.valueEntity(index).kind) {
            is InternalValueKind.Integer -> SemanticAvailability.Available(kind.value)
            is InternalValueKind.Unavailable -> SemanticAvailability.Unavailable(kind.reason)
            else -> SemanticAvailability.Available(null)
        }

    /** Exact normalized decimal (lib.rs). */
    fun asDecimal(): SemanticAvailability<PvDecimal?> =
        when (val kind = document.valueEntity(index).kind) {
            is InternalValueKind.Decimal -> SemanticAvailability.Available(kind.value)
            is InternalValueKind.Unavailable -> SemanticAvailability.Unavailable(kind.reason)
            else -> SemanticAvailability.Available(null)
        }

    /** Exact IEEE-754 binary64 datum used by JSON5 non-finite literals
     * (lib.rs). */
    fun asBinaryFloat64(): SemanticAvailability<Long?> =
        when (val kind = document.valueEntity(index).kind) {
            is InternalValueKind.BinaryFloat64 -> SemanticAvailability.Available(kind.bits)
            is InternalValueKind.Unavailable -> SemanticAvailability.Unavailable(kind.reason)
            else -> SemanticAvailability.Available(null)
        }

    /** Decoded Unicode string without normalization (lib.rs). */
    fun asString(): SemanticAvailability<String?> =
        when (val kind = document.valueEntity(index).kind) {
            is InternalValueKind.String -> SemanticAvailability.Available(kind.value)
            is InternalValueKind.Unavailable -> SemanticAvailability.Unavailable(kind.reason)
            else -> SemanticAvailability.Available<String?>(null)
        }

    /** Ordered array elements (lib.rs). */
    fun arrayElements(): SemanticAvailability<List<JsonArrayElement>?> =
        when (val kind = document.valueEntity(index).kind) {
            is InternalValueKind.Array -> SemanticAvailability.Available(
                kind.elements.map { JsonArrayElement(document, it) },
            )

            is InternalValueKind.Unavailable -> SemanticAvailability.Unavailable(kind.reason)
            else -> SemanticAvailability.Available(null)
        }

    /** Ordered object members without duplicate collapse (lib.rs). */
    fun objectMembers(): SemanticAvailability<List<JsonObjectMember>?> =
        when (val kind = document.valueEntity(index).kind) {
            is InternalValueKind.Object -> SemanticAvailability.Available(
                kind.members.map { JsonObjectMember(document, it) },
            )

            is InternalValueKind.Unavailable -> SemanticAvailability.Unavailable(kind.reason)
            else -> SemanticAvailability.Available(null)
        }

    internal fun rawIndex(): Int = index
}

/** Borrowed JSON object member association (lib.rs). */
class JsonObjectMember internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): MemberEntity = document.memberEntity(index)

    /** Zero-based structural member ordinal (lib.rs). */
    fun ordinal(): Int = entity().ordinal

    /** Member association identity (lib.rs). */
    fun nodeRef(): NodeRef = document.nodeRef(index, NodeRole.ObjectMember)

    /** Key node identity (lib.rs). */
    fun keyNodeRef(): NodeRef = document.nodeRef(entity().key, NodeRole.ObjectKey)

    /** Value node identity (lib.rs). */
    fun valueNodeRef(): NodeRef = document.nodeRef(entity().value, NodeRole.Value)

    /** Whole member source span (lib.rs). */
    fun span(): Span = entity().span

    /** Decoded member name (lib.rs). */
    fun name(): SemanticAvailability<String> =
        when (val kind = document.valueEntity(entity().key).kind) {
            is InternalValueKind.String -> SemanticAvailability.Available(kind.value)
            is InternalValueKind.Unavailable -> SemanticAvailability.Unavailable(kind.reason)
            else -> SemanticAvailability.Unavailable(SemanticUnavailable.InvalidLiteral)
        }

    /** Associated value (lib.rs). */
    fun value(): JsonValue = JsonValue(document, entity().value)
}

/** Borrowed JSON array element association (lib.rs). */
class JsonArrayElement internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): ElementEntity = document.elementEntity(index)

    /** Zero-based structural index (lib.rs). */
    fun ordinal(): Int = entity().ordinal

    /** Element association identity (lib.rs). */
    fun nodeRef(): NodeRef = document.nodeRef(index, NodeRole.ArrayElement)

    /** Associated value identity (lib.rs). */
    fun valueNodeRef(): NodeRef = document.nodeRef(entity().value, NodeRole.Value)

    /** Whole element span (lib.rs). */
    fun span(): Span = entity().span

    /** Element value (lib.rs). */
    fun value(): JsonValue = JsonValue(document, entity().value)
}

/**
 * Opaque immutable document snapshot (lib.rs). Parsing happens in
 * Parser.kt; this file pins the read surface and the module-internal entity
 * access shared by query, projection, materialization, and edit.
 */
class Document internal constructor(
    internal val authority: DocumentAuthority,
    internal val source: SourceSnapshot,
    internal val profile: JsonProfile,
    private val structuralIndex: LosslessStructuralIndex,
    private val syntaxKinds: List<JsonSyntaxKind>,
    internal val formationStatus: FormationStatus,
    internal val diagnosticsList: List<Diagnostic>,
    internal val entities: List<Entity>,
    internal val rootIndex: Int,
    internal val parseLimits: ParseLimits,
) {
    /** Snapshot identity to which every NodeRef and Span belongs
     * (lib.rs). */
    val snapshotIdentity: SnapshotIdentity
        get() = authority.identity

    /** Exact immutable source (lib.rs). */
    fun source(): SourceSnapshot = source

    /** Default rendering is the exact current source bytes (lib.rs). */
    fun render(): ByteArray = source.bytes()

    /** JSON format family contract (lib.rs). */
    fun formatFamily(): FormatFamilyId = FormatFamilyId("json", 1)

    /** Exact language profile (lib.rs). The Kotlin property
     * [profile] carries the internal [JsonProfile]; the public surface is
     * the transferable [ProfileId]. */
    fun profileId(): ProfileId = profile.id()

    /** Whether recovery structure was required (lib.rs). */
    fun formationStatus(): FormationStatus = formationStatus

    /** Deterministically ordered document diagnostics (lib.rs). */
    fun diagnostics(): List<Diagnostic> = diagnosticsList

    /** Exhaustive token/trivia/error-region byte coverage (lib.rs). */
    fun losslessStructuralIndex(): LosslessStructuralIndex = structuralIndex

    /** Format-specific kind for every structural piece, in the same source
     * order (lib.rs). */
    fun losslessSyntaxKinds(): List<JsonSyntaxKind> = syntaxKinds

    /** Root native semantic value (lib.rs). */
    fun root(): JsonValue = JsonValue(this, rootIndex)

    internal fun entity(index: Int): Entity = entities[index]

    internal fun valueEntity(index: Int): ValueEntity =
        when (val entity = entities[index]) {
            is Entity.Value -> entity.entity
            else -> error("typed value handle required a value entity")
        }

    internal fun memberEntity(index: Int): MemberEntity =
        when (val entity = entities[index]) {
            is Entity.Member -> entity.entity
            else -> error("typed member handle required a member entity")
        }

    internal fun elementEntity(index: Int): ElementEntity =
        when (val entity = entities[index]) {
            is Entity.Element -> entity.entity
            else -> error("typed element handle required an element entity")
        }

    internal fun nodeRef(index: Int, role: NodeRole): NodeRef = authority.nodeRef(index.toLong(), role)

    internal fun span(index: Int): Span = entity(index).span

    /**
     * Validates one NodeRef against the allowed roles and resolves its
     * entity index (lib.rs). Throws [JsonAccessException]:
     * WrongSnapshot, WrongRole, or UnknownNode.
     */
    internal fun validateRef(node: NodeRef, roles: List<NodeRole>): Int {
        try {
            authority.verify(node)
        } catch (e: consema.document.LocationException) {
            throw JsonAccessException(JsonAccessErrorKind.WrongSnapshot)
        }
        if (node.role !in roles) {
            throw JsonAccessException(JsonAccessErrorKind.WrongRole)
        }
        val index = node.index
        if (index < 0 || index >= entities.size.toLong()) {
            throw JsonAccessException(JsonAccessErrorKind.UnknownNode)
        }
        return index.toInt()
    }

    /** Structural coverage pieces (lib.rs). */
    internal fun pieces(): List<StructuralPiece> = structuralIndex.pieces()
}
