// The immutable INI-family document model: snapshot-bound native handles,
// ordered physical/logical lines, sections, entries, error records, and the
// lossless coverage index.
//
// Data authority:
//   - RFC 0009 §4 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md):
//     formation continues to use Complete | Recovered | FatalFormationFailure;
//     Recovered retains the complete source, exhaustive syntax/error-region
//     coverage, ordered diagnostics, and every independently proven section
//     or entry; syntax and native queries may inspect proven records and
//     distinguish them from error regions.
//   - RFC 0009 §8 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md): the immutable INI Document
//     retains ordered physical lines with exact raw/decoded ranges, logical
//     lines with constituent physical-line identities, BOM/newline/quote/
//     comment facts, section/entry identities with original and comparison
//     names, Missing | Empty | Present value states, continuation joins,
//     duplicate groups without collapsing, error-line identities, and
//     exhaustive non-overlapping syntax pieces; all handles are
//     snapshot-bound NodeRefs with the INI roles.
//   - https://github.com/consema/consema-rs/blob/main/consema-ini/src/lib.rs pins the handle shapes (node,
//     span, content_span, line_break_span, name_span, key_span, value_span,
//     comparison names, quote style, duplicate group, error code) and the
//     resolver behavior lib.rs. consema-go/go/ini/document.go is a
//     cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the Rust borrowed handles are
// immutable handle classes carrying (document, index) — the Kotlin analogue
// of the Go handle structs — and `when` over the closed kinds is exhaustive.
// The document keeps its own consema.document.DocumentAuthority
// (module-internal, shared across family packages per
// kotlin/src/main/kotlin/consema/document/Location.kt).

package consema.ini

import consema.document.DocumentAuthority
import consema.document.FormatFamilyId
import consema.document.FormationStatus
import consema.document.LosslessStructuralIndex
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.ProfileId
import consema.document.SnapshotIdentity
import consema.document.Span
import consema.document.StructuralPiece
import consema.protocol.Diagnostic

/** One exact physical source line (lib.rs). */
data class IniPhysicalLine(
    /** Snapshot-bound physical-line identity. */
    val nodeRef: NodeRef,
    /** Complete raw line including its line break. */
    val span: Span,
    /** Raw line content excluding its line break. */
    val contentSpan: Span,
    /** Exact LF or CRLF range, absent at EOF. */
    val lineBreakSpan: Span?,
)

/** One logical record and its ordered physical constituents
 * (lib.rs). */
data class IniLogicalLine(
    /** Snapshot-bound logical-line identity. */
    val nodeRef: NodeRef,
    /** Logical record kind. */
    val kind: IniLogicalLineKind,
    /** Ordered physical-line identities. */
    val physicalLines: List<NodeRef>,
)

/** One distinct section-header occurrence (lib.rs). */
data class IniSection(
    /** Snapshot-bound section occurrence identity. */
    val nodeRef: NodeRef,
    /** Owning logical-line identity. */
    val logicalLine: NodeRef,
    /** Complete header content span, excluding the line break. */
    val span: Span,
    /** Exact section-name span. */
    val nameSpan: Span,
    /** Original decoded name spelling. */
    val name: String,
    /** Profile-specific comparison name. */
    val comparisonName: String,
    /** Whether this is Python's exact `DEFAULT` section. */
    val isDefault: Boolean,
    /** Deterministic duplicate/case-equivalence group identity. */
    val duplicateGroup: Int?,
)

/** One distinct key/value occurrence (lib.rs). */
data class IniEntry(
    /** Snapshot-bound entry occurrence identity. */
    val nodeRef: NodeRef,
    /** Owning logical-line identity. */
    val logicalLine: NodeRef,
    /** Owning section occurrence. */
    val section: NodeRef,
    /** Complete first physical-line content span. */
    val span: Span,
    /** Exact original key span. */
    val keySpan: Span,
    /** Exact first-line semantic value span. */
    val valueSpan: Span,
    /** Original decoded key spelling. */
    val key: String,
    /** Profile-specific comparison key. */
    val comparisonKey: String,
    /** Stored semantic string, including deterministic continuation joins. */
    val value: String,
    /** Missing, empty, or present value fact. */
    val valueState: IniValueState,
    /** Profile-recognized outer quote style. */
    val quoteStyle: IniQuoteStyle,
    /** Deterministic duplicate/case-equivalence group identity. */
    val duplicateGroup: Int?,
)

/** One recovered physical error record (lib.rs). */
data class IniErrorLine(
    /** Snapshot-bound error identity. */
    val nodeRef: NodeRef,
    /** Owning logical-line identity. */
    val logicalLine: NodeRef,
    /** Physical line retained by recovery. */
    val physicalLine: NodeRef,
    /** Exact malformed content span. */
    val span: Span,
    /** Stable diagnostic code. */
    val code: String,
)

/**
 * Immutable lossless INI document (lib.rs). Parsing happens in
 * Parser.kt; this file pins the read surface and the module-internal access
 * shared by query, projection, materialization, and edit.
 */
class IniDocument internal constructor(
    internal val authority: DocumentAuthority,
    internal val sourceSnapshot: IniSource,
    internal val profile: IniProfile,
    private val structuralIndex: LosslessStructuralIndex,
    private val syntaxKinds: List<IniSyntaxKind>,
    internal val formationStatus: FormationStatus,
    internal val diagnosticsList: List<Diagnostic>,
    internal val physicalLinesList: List<IniPhysicalLine>,
    internal val logicalLinesList: List<IniLogicalLine>,
    internal val sectionsList: List<IniSection>,
    internal val entriesList: List<IniEntry>,
    internal val errorLinesList: List<IniErrorLine>,
    internal val parseLimits: IniParseLimits,
    internal val rootNode: NodeRef,
) {
    /** Snapshot identity to which every INI handle and span belongs
     * (lib.rs). */
    val snapshotIdentity: SnapshotIdentity
        get() = authority.identity

    /** Exact immutable source (lib.rs). */
    fun source(): IniSource = sourceSnapshot

    /** Default rendering is the exact current source bytes (lib.rs). */
    fun render(): ByteArray = sourceSnapshot.bytes()

    /** INI format family contract (lib.rs). */
    fun formatFamily(): FormatFamilyId = FormatFamilyId("ini", 1)

    /** Exact language profile (lib.rs). */
    fun profileId(): ProfileId = profile.id()

    /** Root INI document identity (lib.rs). */
    fun nodeRef(): NodeRef = rootNode

    /** Whether recovery structure was required (lib.rs). */
    fun formationStatus(): FormationStatus = formationStatus

    /** Deterministically ordered document diagnostics (lib.rs). */
    fun diagnostics(): List<Diagnostic> = diagnosticsList

    /** Exhaustive token/trivia/error-region byte coverage (lib.rs). */
    fun losslessStructuralIndex(): LosslessStructuralIndex = structuralIndex

    /** Format-specific kind for every structural piece, in the same source
     * order (lib.rs). */
    fun losslessSyntaxKinds(): List<IniSyntaxKind> = syntaxKinds

    /** Ordered physical source lines (lib.rs). */
    fun physicalLines(): List<IniPhysicalLine> = physicalLinesList

    /** Ordered logical records (lib.rs). */
    fun logicalLines(): List<IniLogicalLine> = logicalLinesList

    /** Ordered distinct section occurrences (lib.rs). */
    fun sections(): List<IniSection> = sectionsList

    /** Ordered distinct entry occurrences (lib.rs). */
    fun entries(): List<IniEntry> = entriesList

    /** Ordered recovered error records (lib.rs). */
    fun errorLines(): List<IniErrorLine> = errorLinesList

    /** Resource contract used to form this snapshot (lib.rs). */
    fun parseLimits(): IniParseLimits = parseLimits

    /** Resolves one physical-line handle only within this snapshot
     * (lib.rs). Throws [IniAccessException]: WrongSnapshot,
     * WrongRole, or UnknownNode. */
    fun physicalLine(node: NodeRef): IniPhysicalLine {
        validate(node, NodeRole.IniPhysicalLine)
        return physicalLinesList.firstOrNull { it.nodeRef == node }
            ?: throw IniAccessException(IniAccessErrorKind.UnknownNode)
    }

    /** Resolves one logical-line handle only within this snapshot
     * (lib.rs). */
    fun logicalLine(node: NodeRef): IniLogicalLine {
        validate(node, NodeRole.IniLogicalLine)
        return logicalLinesList.firstOrNull { it.nodeRef == node }
            ?: throw IniAccessException(IniAccessErrorKind.UnknownNode)
    }

    /** Resolves one section/default-section handle only within this snapshot
     * (lib.rs). */
    fun section(node: NodeRef): IniSection {
        if (node.snapshot != snapshotIdentity) {
            throw IniAccessException(IniAccessErrorKind.WrongSnapshot)
        }
        if (node.role != NodeRole.IniSection && node.role != NodeRole.IniDefaultSection) {
            throw IniAccessException(IniAccessErrorKind.WrongRole)
        }
        return sectionsList.firstOrNull { it.nodeRef == node }
            ?: throw IniAccessException(IniAccessErrorKind.UnknownNode)
    }

    /** Resolves one entry handle only within this snapshot (lib.rs). */
    fun entry(node: NodeRef): IniEntry {
        validate(node, NodeRole.IniEntry)
        return entriesList.firstOrNull { it.nodeRef == node }
            ?: throw IniAccessException(IniAccessErrorKind.UnknownNode)
    }

    /** Structural coverage pieces (lib.rs). */
    internal fun pieces(): List<StructuralPiece> = structuralIndex.pieces()

    /** Verifies snapshot binding and one exact role (lib.rs). */
    private fun validate(node: NodeRef, role: NodeRole) {
        if (node.snapshot != snapshotIdentity) {
            throw IniAccessException(IniAccessErrorKind.WrongSnapshot)
        }
        if (node.role != role) {
            throw IniAccessException(IniAccessErrorKind.WrongRole)
        }
    }
}
