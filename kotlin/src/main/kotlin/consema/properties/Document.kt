// The immutable Java Properties document model: snapshot-bound natural/
// logical line identities, duplicate-preserving property associations,
// comments, escapes, recovery records, and the lossless coverage index.
//
// Data authority:
//   - RFC 0010 §2, §5, §9 (https://github.com/consema/consema/blob/main/docs/rfcs/0010-java-properties-profiles-v1.md:
//     37-63, 132-159, 236-267): the Document ends at the native layer; the
//     native roles are PropertiesDocument / PropertiesNaturalLine /
//     PropertiesLogicalLine / PropertiesProperty / PropertiesComment /
//     PropertiesEscape / PropertiesErrorLine; duplicate keys never collapse;
//     the immutable Document retains exact terminators, continuation
//     markers, escape identity/spelling/output ranges, and exhaustive
//     non-overlapping syntax coverage.
//   - https://github.com/consema/consema-rs/blob/main/consema-properties/src/lib.rs:309-589 (the entity shapes and
//     accessors) and lib.rs:590-775 (Document) are the byte-arbitration
//     authority. consema-go/go/properties/document.go is a cross-reference only.
//   - The node roles are pinned in kotlin/src/main/kotlin/consema/document/Location.kt:119-141
//     (PropertiesDocument .. PropertiesSyntaxPiece).
//
// Kotlin-idiomatic design (NOT a translation): the Rust borrowed handles are
// immutable handle classes carrying (document, index) — the same idiom as
// the JSON family (kotlin/src/main/kotlin/consema/json/Document.kt:15-19); entity storage is
// typed lists per role and `internal` accessors are module-visible so
// query/projection/materialization/edit in this package share one truth.

package consema.properties

import consema.document.DocumentAuthority
import consema.document.FormatFamilyId
import consema.document.FormationStatus
import consema.document.LosslessStructuralIndex
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.ProfileId
import consema.document.SnapshotIdentity
import consema.document.SourceSnapshot
import consema.document.Span
import consema.document.StructuralPiece
import consema.protocol.Diagnostic

// ---------------------------------------------------------------------------
// Internal entities
// ---------------------------------------------------------------------------

/** One exact natural source line (lib.rs:309-342). */
internal data class NaturalLineEntity(
    val node: NodeRef,
    val span: Span,
    val contentSpan: Span,
    val lineBreakSpan: Span?,
)

/** One property/error logical line and its natural-line constituents
 * (lib.rs:344-370). */
internal data class LogicalLineEntity(
    val node: NodeRef,
    val kind: PropertiesLogicalLineKind,
    val naturalLineIndices: List<Int>,
)

/** One comment natural line (lib.rs:372-405). */
internal data class CommentEntity(
    val node: NodeRef,
    val naturalLineIndex: Int,
    val span: Span,
    val marker: Char,
)

/** One source escape and its exact Java-string output range (lib.rs:407-455). */
internal data class EscapeEntity(
    val node: NodeRef,
    val propertyIndex: Int,
    val inKey: Boolean,
    val kind: PropertiesEscapeKind,
    val span: Span,
    val outputStart: Int,
    val outputEnd: Int,
)

/** One distinct source-ordered property association (lib.rs:457-546). */
internal data class PropertyEntity(
    val node: NodeRef,
    val logicalLineIndex: Int,
    val span: Span,
    val keyAnchor: Span,
    val valueAnchor: Span,
    val keyFragments: List<Span>,
    val valueFragments: List<Span>,
    val key: JavaString,
    val value: JavaString,
    val valueState: PropertiesValueState,
    val escapeIndices: List<Int>,
    val duplicateGroup: Int?,
)

/** One recovered malformed logical line (lib.rs:548-588). */
internal data class ErrorLineEntity(
    val node: NodeRef,
    val logicalLineIndex: Int,
    val naturalLineIndices: List<Int>,
    val span: Span,
    val code: String,
)

// ---------------------------------------------------------------------------
// Borrowed typed handles
// ---------------------------------------------------------------------------

/** Borrowed natural source line handle bound to one Document snapshot
 * (lib.rs:311-342). */
class PropertiesNaturalLine internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): NaturalLineEntity = document.naturalLineEntity(index)

    /** Snapshot-bound natural-line identity (lib.rs:318-321). */
    fun nodeRef(): NodeRef = entity().node

    /** Complete source span including the terminator (lib.rs:323-326). */
    fun span(): Span = entity().span

    /** Content span excluding the terminator (lib.rs:328-331). */
    fun contentSpan(): Span = entity().contentSpan

    /** LF, CR, or CRLF span; absent for an EOF line (lib.rs:333-338). */
    fun lineBreakSpan(): Span? = entity().lineBreakSpan
}

/** Borrowed property/error logical line handle (lib.rs:345-370). */
class PropertiesLogicalLine internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): LogicalLineEntity = document.logicalLineEntity(index)

    /** Snapshot-bound logical-line identity (lib.rs:352-355). */
    fun nodeRef(): NodeRef = entity().node

    /** Property or recovered-error classification (lib.rs:357-360). */
    fun kind(): PropertiesLogicalLineKind = entity().kind

    /** Ordered natural-line constituents (lib.rs:362-365). */
    fun naturalLines(): List<PropertiesNaturalLine> =
        entity().naturalLineIndices.map { PropertiesNaturalLine(document, it) }
}

/** Borrowed comment occurrence handle (lib.rs:373-405). */
class PropertiesComment internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): CommentEntity = document.commentEntity(index)

    /** Snapshot-bound comment identity (lib.rs:381-384). */
    fun nodeRef(): NodeRef = entity().node

    /** Owning natural line (lib.rs:386-389). */
    fun naturalLine(): PropertiesNaturalLine =
        PropertiesNaturalLine(document, entity().naturalLineIndex)

    /** Complete comment content span excluding its line break (lib.rs:391-394). */
    fun span(): Span = entity().span

    /** Exact comment marker (lib.rs:396-399). */
    fun marker(): Char = entity().marker
}

/** Borrowed escape occurrence handle (lib.rs:408-455). */
class PropertiesEscape internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): EscapeEntity = document.escapeEntity(index)

    /** Snapshot-bound escape identity (lib.rs:419-422). */
    fun nodeRef(): NodeRef = entity().node

    /** Owning property occurrence (lib.rs:424-427). */
    fun property(): Property = Property(document, entity().propertyIndex)

    /** Whether the output range belongs to the decoded key (lib.rs:429-432). */
    fun inKey(): Boolean = entity().inKey

    /** Exact escape kind (lib.rs:434-437). */
    fun kind(): PropertiesEscapeKind = entity().kind

    /** Complete raw escape spelling (lib.rs:439-442). */
    fun span(): Span = entity().span

    /** Half-open output code-unit range in the owning key or value
     * (lib.rs:444-450). */
    fun outputStart(): Int = entity().outputStart

    /** Exclusive output code-unit boundary (lib.rs:444-450). */
    fun outputEnd(): Int = entity().outputEnd
}

/** Borrowed duplicate-preserving property association handle
 * (lib.rs:458-546). */
class Property internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): PropertyEntity = document.propertyEntity(index)

    /** Snapshot-bound property association identity (lib.rs:475-478). */
    fun nodeRef(): NodeRef = entity().node

    /** Owning logical line (lib.rs:480-483). */
    fun logicalLine(): PropertiesLogicalLine =
        PropertiesLogicalLine(document, entity().logicalLineIndex)

    /** Complete first-to-last property source range (lib.rs:485-488). */
    fun span(): Span = entity().span

    /** Zero-width source anchor at the start of the decoded key (lib.rs:490-493). */
    fun keyAnchor(): Span = entity().keyAnchor

    /** Zero-width source anchor at the start of the decoded value (lib.rs:495-498). */
    fun valueAnchor(): Span = entity().valueAnchor

    /** Ordered raw source fragments contributing to the key (lib.rs:500-503). */
    fun keyFragments(): List<Span> = entity().keyFragments

    /** Ordered raw source fragments contributing to the value (lib.rs:505-508). */
    fun valueFragments(): List<Span> = entity().valueFragments

    /** Exact decoded Java UTF-16 key (lib.rs:510-513). */
    fun key(): JavaString = entity().key

    /** Exact decoded Java UTF-16 element (lib.rs:515-518). */
    fun value(): JavaString = entity().value

    /** Implicit, explicit empty, or present source state (lib.rs:520-523). */
    fun valueState(): PropertiesValueState = entity().valueState

    /** Ordered escape identities in key-then-value decode order (lib.rs:525-528). */
    fun escapes(): List<PropertiesEscape> =
        entity().escapeIndices.map { PropertiesEscape(document, it) }

    /** Deterministic exact-code-unit duplicate group (lib.rs:530-533). */
    fun duplicateGroup(): Int? = entity().duplicateGroup
}

/** Borrowed recovered error-line handle (lib.rs:549-588). */
class PropertiesErrorLine internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): ErrorLineEntity = document.errorLineEntity(index)

    /** Snapshot-bound error identity (lib.rs:558-561). */
    fun nodeRef(): NodeRef = entity().node

    /** Owning recovered logical line (lib.rs:563-566). */
    fun logicalLine(): PropertiesLogicalLine =
        PropertiesLogicalLine(document, entity().logicalLineIndex)

    /** Natural lines retained by this recovery record (lib.rs:568-571). */
    fun naturalLines(): List<PropertiesNaturalLine> =
        entity().naturalLineIndices.map { PropertiesNaturalLine(document, it) }

    /** Complete recovered source range (lib.rs:573-576). */
    fun span(): Span = entity().span

    /** Stable diagnostic code (lib.rs:578-581). */
    fun code(): String = entity().code
}

// ---------------------------------------------------------------------------
// Document
// ---------------------------------------------------------------------------

/**
 * Immutable, duplicate-preserving Java Properties document (lib.rs:590-775).
 * Parsing happens in Parser.kt; this file pins the read surface and the
 * module-internal entity access shared by query, projection, materialization,
 * and edit.
 */
class Document internal constructor(
    internal val authority: DocumentAuthority,
    internal val source: SourceSnapshot,
    /** The exact source selection used to form this snapshot. */
    internal val sourceEncoding: PropertiesEncoding,
    internal val profile: PropertiesProfile,
    /** The decoded text view (module-internal carrier). */
    internal val text: PropertiesText,
    private val structuralIndex: LosslessStructuralIndex,
    private val syntaxKindList: List<PropertiesSyntaxKind>,
    internal val formationStatus: FormationStatus,
    internal val diagnosticsList: List<Diagnostic>,
    internal val naturalLineEntities: List<NaturalLineEntity>,
    internal val logicalLineEntities: List<LogicalLineEntity>,
    internal val propertyEntities: List<PropertyEntity>,
    internal val commentEntities: List<CommentEntity>,
    internal val escapeEntities: List<EscapeEntity>,
    internal val errorLineEntities: List<ErrorLineEntity>,
    internal val parseLimits: PropertiesParseLimits,
    internal val rootNode: NodeRef,
) {
    /** Snapshot identity to which every handle and span belongs
     * (lib.rs:611-615). */
    val snapshotIdentity: SnapshotIdentity
        get() = authority.identity

    /** Exact immutable source (lib.rs:617-622). */
    fun source(): SourceSnapshot = source

    /** Default rendering is byte-for-byte source identity (lib.rs:624-627). */
    fun render(): ByteArray = source.bytes()

    /** Stable Java Properties format family (lib.rs:629-633). */
    fun formatFamily(): FormatFamilyId = FormatFamilyId("java-properties", 1)

    /** Exact selected profile (lib.rs:635-639). */
    fun profileId(): ProfileId = profile.id()

    /** Concrete selected profile (lib.rs:641-645). */
    fun selectedProfile(): PropertiesProfile = profile

    /** Root Properties document identity (lib.rs:647-650). */
    fun nodeRef(): NodeRef = rootNode

    /** Complete or explicitly recovered formation state (lib.rs:652-656). */
    fun formationStatus(): FormationStatus = formationStatus

    /** Stable ordered diagnostics (lib.rs:658-663). */
    fun diagnostics(): List<Diagnostic> = diagnosticsList

    /** Exhaustive ordered source coverage (lib.rs:665-669). */
    fun losslessStructuralIndex(): LosslessStructuralIndex = structuralIndex

    /** Format kind aligned with every structural piece (lib.rs:671-675). */
    fun losslessSyntaxKinds(): List<PropertiesSyntaxKind> = syntaxKindList

    /** Ordered natural source lines (lib.rs:677-681). */
    fun naturalLines(): List<PropertiesNaturalLine> =
        naturalLineEntities.indices.map { PropertiesNaturalLine(this, it) }

    /** Ordered property/error logical lines (lib.rs:683-687). */
    fun logicalLines(): List<PropertiesLogicalLine> =
        logicalLineEntities.indices.map { PropertiesLogicalLine(this, it) }

    /** Ordered duplicate-preserving property associations (lib.rs:689-693). */
    fun properties(): List<Property> =
        propertyEntities.indices.map { Property(this, it) }

    /** Ordered comment occurrences (lib.rs:695-699). */
    fun comments(): List<PropertiesComment> =
        commentEntities.indices.map { PropertiesComment(this, it) }

    /** Ordered escape occurrences (lib.rs:701-705). */
    fun escapes(): List<PropertiesEscape> =
        escapeEntities.indices.map { PropertiesEscape(this, it) }

    /** Ordered recovered error lines (lib.rs:707-711). */
    fun errorLines(): List<PropertiesErrorLine> =
        errorLineEntities.indices.map { PropertiesErrorLine(this, it) }

    /** Resource contract used to form this snapshot (lib.rs:713-717). */
    fun parseLimits(): PropertiesParseLimits = parseLimits

    /** Resolves one property handle only within this snapshot (lib.rs:719-729). */
    fun property(node: NodeRef): Property {
        val index = propertyOrdinal(node)
            ?: throw PropertiesAccessException(PropertiesAccessErrorKind.UnknownNode)
        return Property(this, index)
    }

    /** Resolves one natural-line handle only within this snapshot (lib.rs:731-744). */
    fun naturalLine(node: NodeRef): PropertiesNaturalLine {
        val index = naturalLineOrdinal(node)
            ?: throw PropertiesAccessException(PropertiesAccessErrorKind.UnknownNode)
        return PropertiesNaturalLine(this, index)
    }

    /** Resolves one logical-line handle only within this snapshot (lib.rs:746-759). */
    fun logicalLine(node: NodeRef): PropertiesLogicalLine {
        val index = logicalLineOrdinal(node)
            ?: throw PropertiesAccessException(PropertiesAccessErrorKind.UnknownNode)
        return PropertiesLogicalLine(this, index)
    }

    /** Resolves one escape handle only within this snapshot (lib.rs:761-774). */
    fun escape(node: NodeRef): PropertiesEscape {
        val index = escapeOrdinal(node)
            ?: throw PropertiesAccessException(PropertiesAccessErrorKind.UnknownNode)
        return PropertiesEscape(this, index)
    }

    /** Ordinal of one snapshot-bound property node, or null. */
    internal fun propertyOrdinal(node: NodeRef): Int? {
        verifySnapshot(node)
        if (node.role != NodeRole.PropertiesProperty) {
            throw PropertiesAccessException(PropertiesAccessErrorKind.WrongRole)
        }
        return propertyEntities.indexOfFirst { it.node == node }
            .takeIf { it >= 0 }
    }

    internal fun naturalLineOrdinal(node: NodeRef): Int? {
        verifySnapshot(node)
        if (node.role != NodeRole.PropertiesNaturalLine) {
            throw PropertiesAccessException(PropertiesAccessErrorKind.WrongRole)
        }
        return naturalLineEntities.indexOfFirst { it.node == node }
            .takeIf { it >= 0 }
    }

    internal fun logicalLineOrdinal(node: NodeRef): Int? {
        verifySnapshot(node)
        if (node.role != NodeRole.PropertiesLogicalLine) {
            throw PropertiesAccessException(PropertiesAccessErrorKind.WrongRole)
        }
        return logicalLineEntities.indexOfFirst { it.node == node }
            .takeIf { it >= 0 }
    }

    internal fun escapeOrdinal(node: NodeRef): Int? {
        verifySnapshot(node)
        if (node.role != NodeRole.PropertiesEscape) {
            throw PropertiesAccessException(PropertiesAccessErrorKind.WrongRole)
        }
        return escapeEntities.indexOfFirst { it.node == node }
            .takeIf { it >= 0 }
    }

    /** Verifies snapshot binding; the typed WrongSnapshot failure replaces
     * the document-layer LocationException (RFC 0016 §6). */
    internal fun verifySnapshot(node: NodeRef) {
        try {
            authority.verify(node)
        } catch (e: consema.document.LocationException) {
            throw PropertiesAccessException(PropertiesAccessErrorKind.WrongSnapshot)
        }
    }

    internal fun naturalLineEntity(index: Int): NaturalLineEntity = naturalLineEntities[index]

    internal fun logicalLineEntity(index: Int): LogicalLineEntity = logicalLineEntities[index]

    internal fun commentEntity(index: Int): CommentEntity = commentEntities[index]

    internal fun escapeEntity(index: Int): EscapeEntity = escapeEntities[index]

    internal fun propertyEntity(index: Int): PropertyEntity = propertyEntities[index]

    internal fun errorLineEntity(index: Int): ErrorLineEntity = errorLineEntities[index]

    internal fun nodeRef(index: Long, role: NodeRole): NodeRef = authority.nodeRef(index, role)

    /** Structural coverage pieces. */
    internal fun pieces(): List<StructuralPiece> = structuralIndex.pieces()

    /** Validates one NodeRef and resolves its entity ordinal for the allowed
     * roles. Throws [PropertiesAccessException]: WrongSnapshot, WrongRole,
     * or UnknownNode. */
    internal fun validateRef(node: NodeRef, roles: List<NodeRole>): Int {
        try {
            authority.verify(node)
        } catch (e: consema.document.LocationException) {
            throw PropertiesAccessException(PropertiesAccessErrorKind.WrongSnapshot)
        }
        if (node.role !in roles) {
            throw PropertiesAccessException(PropertiesAccessErrorKind.WrongRole)
        }
        return when (node.role) {
            NodeRole.PropertiesProperty -> propertyEntities.indexOfFirst { it.node == node }
            NodeRole.PropertiesNaturalLine -> naturalLineEntities.indexOfFirst { it.node == node }
            NodeRole.PropertiesLogicalLine -> logicalLineEntities.indexOfFirst { it.node == node }
            NodeRole.PropertiesEscape -> escapeEntities.indexOfFirst { it.node == node }
            else -> -1
        }.takeIf { it >= 0 } ?: throw PropertiesAccessException(PropertiesAccessErrorKind.UnknownNode)
    }
}
