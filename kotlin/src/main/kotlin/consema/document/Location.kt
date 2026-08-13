// Structural locations: snapshot-bound spans, node handles, and the frozen
// node-role registry.
//
// Data authority:
//   - RFC 0003 §5 (https://github.com/consema/consema/blob/main/docs/rfcs/0003-source-syntax-query-and-patch-v1.md:125-141):
//     Span is [start_byte, end_byte) over original raw bytes; offsets never
//     become UTF-8 indices after decoding UTF-16 or Latin-1; only scalar
//     boundaries are addressable.
//   - consema-rs/consema-document/src/lib.rs:113-272 (NodeRole, NodeRef,
//     AssociationPlacement), lib.rs:294-342 (Span), lib.rs:39-110
//     (SnapshotIdentity, DocumentAuthority), lib.rs:582-604 (LocationError).
//   - consema-rs/consema-conformance/src/source_v1.rs:423-436 pins the exact
//     error *names* the shared vectors expect ("NoDecodedText",
//     "IncompleteStructuralCoverage", ...). consema-go/go/document/location.go is a
//     cross-reference only.
//
// Kotlin-idiomatic design: byte offsets are Int (all frozen limits fit well
// below 2 GiB); node ordinals are Long (the Rust u64 index). DocumentAuthority
// is module-internal like the Rust #[doc(hidden)] authority: family packages
// in this module create spans and node refs through it.

package consema.document

import java.util.concurrent.atomic.AtomicLong

/**
 * Semantic role of a document structural identity (lib.rs:113-251). The
 * spellings are frozen language-neutral names; new family agents must not
 * invent new roles.
 */
enum class NodeRole {
    /** Format syntax node. */
    SyntaxNode,

    /** Lexical token. */
    Token,

    /** JSON object member association. */
    ObjectMember,

    /** JSON object key. */
    ObjectKey,

    /** JSON array element association. */
    ArrayElement,

    /** Complete semantic value syntax. */
    Value,

    /** TOML native semantic item, including tables and array-of-tables. */
    TomlItem,

    /** TOML table or inline-table key-to-item association. */
    TomlEntry,

    /** TOML decoded key segment with source identity. */
    TomlKey,

    /** TOML array or array-of-tables element association. */
    TomlArrayElement,

    /** Format-owned region in an opaque binary document. */
    BinaryRegion,

    /** One JSON lossless syntax piece. */
    JsonSyntaxPiece,

    /** One TOML lossless syntax piece. */
    TomlSyntaxPiece,

    /** Complete YAML serialization stream. */
    YamlStream,

    /** One independent YAML document in a stream. */
    YamlDocument,

    /** YAML representation node. */
    YamlNode,

    /** YAML ordered sequence association. */
    YamlSequenceElement,

    /** YAML ordered mapping association. */
    YamlMappingEntry,

    /** YAML alias serialization occurrence. */
    YamlAlias,

    /** YAML anchor definition occurrence. */
    YamlAnchorDefinition,

    /** One YAML lossless syntax piece. */
    YamlSyntaxPiece,

    /** Complete INI document. */
    IniDocument,

    /** One physical INI source line. */
    IniPhysicalLine,

    /** One logical INI record. */
    IniLogicalLine,

    /** One ordinary INI section occurrence. */
    IniSection,

    /** One Python ConfigParser default-section occurrence. */
    IniDefaultSection,

    /** One INI entry occurrence. */
    IniEntry,

    /** One recovered INI error line. */
    IniErrorLine,

    /** One INI lossless syntax piece. */
    IniSyntaxPiece,

    /** Complete Java Properties document. */
    PropertiesDocument,

    /** One Java Properties natural source line. */
    PropertiesNaturalLine,

    /** One Java Properties logical line. */
    PropertiesLogicalLine,

    /** One Java Properties property occurrence. */
    PropertiesProperty,

    /** One Java Properties comment occurrence. */
    PropertiesComment,

    /** One Java Properties escape occurrence. */
    PropertiesEscape,

    /** One recovered Java Properties error line. */
    PropertiesErrorLine,

    /** One Java Properties lossless syntax piece. */
    PropertiesSyntaxPiece,

    /** Complete XML document. */
    XmlDocument,

    /** XML declaration. */
    XmlDeclaration,

    /** XML internal-only DOCTYPE occurrence. */
    XmlDoctype,

    /** XML element occurrence. */
    XmlElement,

    /** XML attribute association. */
    XmlAttribute,

    /** XML namespace declaration association. */
    XmlNamespaceBinding,

    /** XML text occurrence. */
    XmlText,

    /** XML CDATA occurrence. */
    XmlCdata,

    /** XML comment occurrence. */
    XmlComment,

    /** XML processing instruction. */
    XmlProcessingInstruction,

    /** XML entity reference occurrence. */
    XmlEntityReference,

    /** One recovered XML error region. */
    XmlErrorRegion,

    /** One XML lossless syntax piece. */
    XmlSyntaxPiece,

    /** Complete plist document (native-domain root handle, RFC 0013 §8.1). */
    PlistDocument,

    /** One plist dictionary key/value association (RFC 0013 §8.1). */
    PlistDictEntry,

    /** One plist string key identity (RFC 0013 §8.1). */
    PlistKey,

    /** One plist array element association (RFC 0013 §8.1). */
    PlistArrayElement,

    /** One native plist value node; shared identity lets one node serve
     * several containers (RFC 0013 §6, §8.1). */
    PlistValue,

    /** One plist XML lossless syntax piece, parallel to the format-owned
     * PlistSyntaxKind (RFC 0013 §8.2). */
    PlistSyntaxPiece,

    /** Complete HCL document (native-domain root handle, RFC 0014 §7.1). */
    HclDocument,

    /** One HCL body: an ordered container of attributes and blocks, shared by
     * the root and nested bodies (RFC 0014 §7.1). */
    HclBody,

    /** One HCL attribute occurrence (RFC 0014 §7.1). */
    HclAttribute,

    /** One HCL block occurrence (RFC 0014 §7.1). */
    HclBlock,

    /** One HCL block label with its quote/naked fact (RFC 0014 §7.1). */
    HclBlockLabel,

    /** One HCL expression AST node (RFC 0014 §7.1). */
    HclExpression,

    /** One ordered HCL template part: literal, interpolation, or directive
     * (RFC 0014 §7.1). */
    HclTemplatePart,

    /** One recovered HCL error region, parallel to XmlErrorRegion
     * (RFC 0014 §3). */
    HclErrorRegion,

    /** One HCL lossless syntax piece, parallel to the format-owned
     * HclSyntaxKind (RFC 0014 §7.2). */
    HclSyntaxPiece,
}

/**
 * Opaque handle to one structural identity in exactly one snapshot
 * (lib.rs:253-292).
 */
data class NodeRef(
    /** Owning snapshot. */
    val snapshot: SnapshotIdentity,
    /** Process-local ordinal within the owning snapshot (Rust u64). */
    val index: Long,
    /** Structural role. */
    val role: NodeRole,
)

/**
 * Placement of a new association relative to one container or exact anchor
 * (lib.rs:261-272).
 */
sealed class AssociationPlacement {
    /** First association in the target container. */
    data object Start : AssociationPlacement()

    /** Last association in the target container. */
    data object End : AssociationPlacement()

    /** Immediately before one exact existing association. */
    data class Before(val anchor: NodeRef) : AssociationPlacement()

    /** Immediately after one exact existing association. */
    data class After(val anchor: NodeRef) : AssociationPlacement()
}

/**
 * Half-open byte range bound to one snapshot (RFC 0003 §5; lib.rs:294-342).
 * Offsets are over the original raw bytes and never become UTF-8 indices
 * after decoding UTF-16 or Latin-1.
 */
data class Span(
    /** Owning snapshot. */
    val snapshot: SnapshotIdentity,
    /** Inclusive start byte. */
    val startByte: Int,
    /** Exclusive end byte. */
    val endByte: Int,
) {
    /** Byte length. */
    val len: Int
        get() = endByte - startByte

    /** Whether the range is an insertion point (zero width). */
    val isEmpty: Boolean
        get() = startByte == endByte
}

/** One exact boundary expressed in every supported coordinate system
 * (RFC 0003 §5; source.rs:411-422). */
data class DecodedPosition(
    /** Offset in retained raw source bytes. */
    val rawByte: Int,
    /** Offset in the UTF-8 representation of decoded text. */
    val decodedUtf8Byte: Int,
    /** Number of decoded Unicode scalar values. */
    val unicodeScalarOffset: Int,
    /** Number of UTF-16 code units in decoded text. */
    val utf16CodeUnitOffset: Int,
)

/** A decoded coordinate to resolve back to an exact raw-byte boundary
 * (RFC 0003 §5; source.rs:424-433). */
sealed class DecodedOffset {
    /** UTF-8 byte offset in decoded text. */
    data class Utf8Byte(val value: Int) : DecodedOffset()

    /** Unicode scalar offset in decoded text. */
    data class UnicodeScalar(val value: Int) : DecodedOffset()

    /** UTF-16 code-unit offset in decoded text. */
    data class Utf16CodeUnit(val value: Int) : DecodedOffset()

    internal fun component(position: DecodedPosition): Int =
        when (this) {
            is Utf8Byte -> position.decodedUtf8Byte
            is UnicodeScalar -> position.unicodeScalarOffset
            is Utf16CodeUnit -> position.utf16CodeUnitOffset
        }

    internal val requestValue: Int
        get() = when (this) {
            is Utf8Byte -> value
            is UnicodeScalar -> value
            is Utf16CodeUnit -> value
        }
}

/**
 * Stable span, identity, or coverage failure (lib.rs:582-604). The
 * [name] spellings are exactly what the shared vectors expect
 * (source_v1.rs:423-436); these names are NOT registered error codes.
 */
enum class LocationErrorKind {
    /** Span start followed its end. */
    InvertedSpan,

    /** Handle or span belongs to another snapshot. */
    WrongSnapshot,

    /** Pieces had a gap, overlap, empty interval, or wrong final length. */
    IncompleteStructuralCoverage,

    /** Requested coordinate is beyond the source or decoded text. */
    OutOfBounds,

    /** Binary sources do not have decoded coordinates. */
    NoDecodedText,

    /** A raw offset lies inside one encoded scalar. */
    NotDecodedBoundary,

    /** A decoded offset lies inside one scalar's UTF-8 or UTF-16
     * representation. */
    DecodedOffsetNotBoundary,

    /** A structural handle has a role other than the one required by its
     * index. */
    WrongRole,

    /** A binary region kind is empty. */
    InvalidBinaryRegionKind,

    /** More than one structural region reused the same process-local
     * identity. */
    DuplicateStructuralIdentity,
    ;
}

/** The typed span/identity/coverage failure. The stable [kind.name] is the
 * language-neutral comparison fact; the [message] is human presentation only
 * (RFC 0016 §6). */
class LocationException(val kind: LocationErrorKind) :
    Exception("location: ${kind.name}")

/**
 * Authority owned by one document implementation for issuing snapshot-bound
 * handles (lib.rs:53-110). Mirrors the Rust #[doc(hidden)] DocumentAuthority:
 * module-internal, but every family package in this module creates spans and
 * node refs through it.
 */
internal class DocumentAuthority private constructor(val identity: SnapshotIdentity) {

    /** Issues one opaque node handle (lib.rs:74-81). */
    fun nodeRef(index: Long, role: NodeRole): NodeRef = NodeRef(identity, index, role)

    /** Creates a snapshot-bound span after range validation
     * (lib.rs:83-93). Negative offsets are impossible in the Rust usize
     * surface; Kotlin Ints require the explicit guard. */
    fun span(startByte: Int, endByte: Int): Span {
        if (startByte < 0 || endByte < 0 || startByte > endByte) {
            throw LocationException(LocationErrorKind.InvertedSpan)
        }
        return Span(identity, startByte, endByte)
    }

    /** Verifies that a node handle belongs to this snapshot (lib.rs:95-102). */
    fun verify(node: NodeRef) {
        if (node.snapshot != identity) {
            throw LocationException(LocationErrorKind.WrongSnapshot)
        }
    }

    companion object {
        private val next = AtomicLong(0)

        /** Allocates a fresh snapshot identity (lib.rs:60-65); every call
         * returns a distinct identity. */
        fun fresh(): DocumentAuthority {
            val id = next.incrementAndGet()
            return DocumentAuthority(SnapshotIdentity(id))
        }
    }
}
