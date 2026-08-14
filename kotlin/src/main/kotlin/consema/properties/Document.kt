// The immutable Java Properties document model: snapshot-bound natural/
// logical line identities, duplicate-preserving property associations,
// comments, escapes, recovery records, and the lossless coverage index.
//
// Data authority:
//   - RFC 0010 §2, §5, §9 (https://github.com/consema/consema/blob/main/docs/rfcs/0010-java-properties-profiles-v1.md): the Document ends at the native layer; the
//     native roles are PropertiesDocument / PropertiesNaturalLine /
//     PropertiesLogicalLine / PropertiesProperty / PropertiesComment /
//     PropertiesEscape / PropertiesErrorLine; duplicate keys never collapse;
//     the immutable Document retains exact terminators, continuation
//     markers, escape identity/spelling/output ranges, and exhaustive
//     non-overlapping syntax coverage.
//   - https://github.com/consema/consema-rs/blob/main/consema-properties/src/lib.rs (the entity shapes and
//     accessors) and lib.rs (Document) are the byte-arbitration
//     authority. consema-go/go/properties/document.go is a cross-reference only.
//   - The node roles are pinned in kotlin/src/main/kotlin/consema/document/Location.kt
//     (PropertiesDocument .. PropertiesSyntaxPiece).
//
// Kotlin-idiomatic design (NOT a translation): the Rust borrowed handles are
// immutable handle classes carrying (document, index) — the same idiom as
// the JSON family (kotlin/src/main/kotlin/consema/json/Document.kt); entity storage is
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

/** One exact natural source line (lib.rs). */
internal data class NaturalLineEntity(
    val node: NodeRef,
    val span: Span,
    val contentSpan: Span,
    val lineBreakSpan: Span?,
)

/** One property/error logical line and its natural-line constituents
 * (lib.rs). */
internal data class LogicalLineEntity(
    val node: NodeRef,
    val kind: PropertiesLogicalLineKind,
    val naturalLineIndices: List<Int>,
)

/** One comment natural line (lib.rs). */
internal data class CommentEntity(
    val node: NodeRef,
    val naturalLineIndex: Int,
    val span: Span,
    val marker: Char,
)

/** One source escape and its exact Java-string output range (lib.rs). */
internal data class EscapeEntity(
    val node: NodeRef,
    val propertyIndex: Int,
    val inKey: Boolean,
    val kind: PropertiesEscapeKind,
    val span: Span,
    val outputStart: Int,
    val outputEnd: Int,
)

/** One distinct source-ordered property association (lib.rs). */
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

/** One recovered malformed logical line (lib.rs). */
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
 * (lib.rs). */
class PropertiesNaturalLine internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): NaturalLineEntity = document.naturalLineEntity(index)

    /** Snapshot-bound natural-line identity (lib.rs). */
    fun nodeRef(): NodeRef = entity().node

    /** Complete source span including the terminator (lib.rs). */
    fun span(): Span = entity().span

    /** Content span excluding the terminator (lib.rs). */
    fun contentSpan(): Span = entity().contentSpan

    /** LF, CR, or CRLF span; absent for an EOF line (lib.rs). */
    fun lineBreakSpan(): Span? = entity().lineBreakSpan
}

/** Borrowed property/error logical line handle (lib.rs). */
class PropertiesLogicalLine internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): LogicalLineEntity = document.logicalLineEntity(index)

    /** Snapshot-bound logical-line identity (lib.rs). */
    fun nodeRef(): NodeRef = entity().node

    /** Property or recovered-error classification (lib.rs). */
    fun kind(): PropertiesLogicalLineKind = entity().kind

    /** Ordered natural-line constituents (lib.rs). */
    fun naturalLines(): List<PropertiesNaturalLine> =
        entity().naturalLineIndices.map { PropertiesNaturalLine(document, it) }
}

/** Borrowed comment occurrence handle (lib.rs). */
class PropertiesComment internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): CommentEntity = document.commentEntity(index)

    /** Snapshot-bound comment identity (lib.rs). */
    fun nodeRef(): NodeRef = entity().node

    /** Owning natural line (lib.rs). */
    fun naturalLine(): PropertiesNaturalLine =
        PropertiesNaturalLine(document, entity().naturalLineIndex)

    /** Complete comment content span excluding its line break (lib.rs). */
    fun span(): Span = entity().span

    /** Exact comment marker (lib.rs). */
    fun marker(): Char = entity().marker
}

/** Borrowed escape occurrence handle (lib.rs). */
class PropertiesEscape internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): EscapeEntity = document.escapeEntity(index)

    /** Snapshot-bound escape identity (lib.rs). */
    fun nodeRef(): NodeRef = entity().node

    /** Owning property occurrence (lib.rs). */
    fun property(): Property = Property(document, entity().propertyIndex)

    /** Whether the output range belongs to the decoded key (lib.rs). */
    fun inKey(): Boolean = entity().inKey

    /** Exact escape kind (lib.rs). */
    fun kind(): PropertiesEscapeKind = entity().kind

    /** Complete raw escape spelling (lib.rs). */
    fun span(): Span = entity().span

    /** Half-open output code-unit range in the owning key or value
     * (lib.rs). */
    fun outputStart(): Int = entity().outputStart

    /** Exclusive output code-unit boundary (lib.rs). */
    fun outputEnd(): Int = entity().outputEnd
}

/** Borrowed duplicate-preserving property association handle
 * (lib.rs). */
class Property internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): PropertyEntity = document.propertyEntity(index)

    /** Snapshot-bound property association identity (lib.rs). */
    fun nodeRef(): NodeRef = entity().node

    /** Owning logical line (lib.rs). */
    fun logicalLine(): PropertiesLogicalLine =
        PropertiesLogicalLine(document, entity().logicalLineIndex)

    /** Complete first-to-last property source range (lib.rs). */
    fun span(): Span = entity().span

    /** Zero-width source anchor at the start of the decoded key (lib.rs). */
    fun keyAnchor(): Span = entity().keyAnchor

    /** Zero-width source anchor at the start of the decoded value (lib.rs). */
    fun valueAnchor(): Span = entity().valueAnchor

    /** Ordered raw source fragments contributing to the key (lib.rs). */
    fun keyFragments(): List<Span> = entity().keyFragments

    /** Ordered raw source fragments contributing to the value (lib.rs). */
    fun valueFragments(): List<Span> = entity().valueFragments

    /** Exact decoded Java UTF-16 key (lib.rs). */
    fun key(): JavaString = entity().key

    /** Exact decoded Java UTF-16 element (lib.rs). */
    fun value(): JavaString = entity().value

    /** Implicit, explicit empty, or present source state (lib.rs). */
    fun valueState(): PropertiesValueState = entity().valueState

    /** Ordered escape identities in key-then-value decode order (lib.rs). */
    fun escapes(): List<PropertiesEscape> =
        entity().escapeIndices.map { PropertiesEscape(document, it) }

    /** Deterministic exact-code-unit duplicate group (lib.rs). */
    fun duplicateGroup(): Int? = entity().duplicateGroup
}

/** Borrowed recovered error-line handle (lib.rs). */
class PropertiesErrorLine internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): ErrorLineEntity = document.errorLineEntity(index)

    /** Snapshot-bound error identity (lib.rs). */
    fun nodeRef(): NodeRef = entity().node

    /** Owning recovered logical line (lib.rs). */
    fun logicalLine(): PropertiesLogicalLine =
        PropertiesLogicalLine(document, entity().logicalLineIndex)

    /** Natural lines retained by this recovery record (lib.rs). */
    fun naturalLines(): List<PropertiesNaturalLine> =
        entity().naturalLineIndices.map { PropertiesNaturalLine(document, it) }

    /** Complete recovered source range (lib.rs). */
    fun span(): Span = entity().span

    /** Stable diagnostic code (lib.rs). */
    fun code(): String = entity().code
}

// ---------------------------------------------------------------------------
// Document
// ---------------------------------------------------------------------------

/**
 * Immutable, duplicate-preserving Java Properties document (lib.rs).
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
     * (lib.rs). */
    val snapshotIdentity: SnapshotIdentity
        get() = authority.identity

    /** Exact immutable source (lib.rs). */
    fun source(): SourceSnapshot = source

    /** Default rendering is byte-for-byte source identity (lib.rs). */
    fun render(): ByteArray = source.bytes()

    /** Stable Java Properties format family (lib.rs). */
    fun formatFamily(): FormatFamilyId = FormatFamilyId("java-properties", 1)

    /** Exact selected profile (lib.rs). */
    fun profileId(): ProfileId = profile.id()

    /** Concrete selected profile (lib.rs). */
    fun selectedProfile(): PropertiesProfile = profile

    /** Root Properties document identity (lib.rs). */
    fun nodeRef(): NodeRef = rootNode

    /** Complete or explicitly recovered formation state (lib.rs). */
    fun formationStatus(): FormationStatus = formationStatus

    /** Stable ordered diagnostics (lib.rs). */
    fun diagnostics(): List<Diagnostic> = diagnosticsList

    /** Exhaustive ordered source coverage (lib.rs). */
    fun losslessStructuralIndex(): LosslessStructuralIndex = structuralIndex

    /** Format kind aligned with every structural piece (lib.rs). */
    fun losslessSyntaxKinds(): List<PropertiesSyntaxKind> = syntaxKindList

    /** Ordered natural source lines (lib.rs). */
    fun naturalLines(): List<PropertiesNaturalLine> =
        naturalLineEntities.indices.map { PropertiesNaturalLine(this, it) }

    /** Ordered property/error logical lines (lib.rs). */
    fun logicalLines(): List<PropertiesLogicalLine> =
        logicalLineEntities.indices.map { PropertiesLogicalLine(this, it) }

    /** Ordered duplicate-preserving property associations (lib.rs). */
    fun properties(): List<Property> =
        propertyEntities.indices.map { Property(this, it) }

    /** Ordered comment occurrences (lib.rs). */
    fun comments(): List<PropertiesComment> =
        commentEntities.indices.map { PropertiesComment(this, it) }

    /** Ordered escape occurrences (lib.rs). */
    fun escapes(): List<PropertiesEscape> =
        escapeEntities.indices.map { PropertiesEscape(this, it) }

    /** Ordered recovered error lines (lib.rs). */
    fun errorLines(): List<PropertiesErrorLine> =
        errorLineEntities.indices.map { PropertiesErrorLine(this, it) }

    /** Resource contract used to form this snapshot (lib.rs). */
    fun parseLimits(): PropertiesParseLimits = parseLimits

    /** Resolves one property handle only within this snapshot (lib.rs). */
    fun property(node: NodeRef): Property {
        val index = propertyOrdinal(node)
            ?: throw PropertiesAccessException(PropertiesAccessErrorKind.UnknownNode)
        return Property(this, index)
    }

    /** Resolves one natural-line handle only within this snapshot (lib.rs). */
    fun naturalLine(node: NodeRef): PropertiesNaturalLine {
        val index = naturalLineOrdinal(node)
            ?: throw PropertiesAccessException(PropertiesAccessErrorKind.UnknownNode)
        return PropertiesNaturalLine(this, index)
    }

    /** Resolves one logical-line handle only within this snapshot (lib.rs). */
    fun logicalLine(node: NodeRef): PropertiesLogicalLine {
        val index = logicalLineOrdinal(node)
            ?: throw PropertiesAccessException(PropertiesAccessErrorKind.UnknownNode)
        return PropertiesLogicalLine(this, index)
    }

    /** Resolves one escape handle only within this snapshot (lib.rs). */
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
