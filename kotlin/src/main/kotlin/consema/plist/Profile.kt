// The frozen plist-family language profiles, lossless syntax-piece
// classification, and formation limits.
//
// Data authority (language-neutral sources first):
//   - RFC 0013 §1 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md): the two
//     profiles plist.xml@1 and plist.binary@1 share one native value model
//     but no syntax; the profile is selected by the caller before formation;
//     the bplist00 magic number never selects semantics.
//   - RFC 0013 §8.2 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md) freezes the v1 lossless
//     syntax-kind set and the root-tag partition rule (PlistOpen on the name,
//     Whitespace on the separator, PlistVersionName on `version`,
//     PlistVersionValue on `="1.0"`, a second PlistOpen on the closing `>`).
//   - RFC 0013 §12 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md) bounds at least the raw
//     bytes, object count, nesting depth, dictionary entries, duplicate-key
//     groups, array elements, string code units, data bytes, UID count,
//     extended-size integers and magnitudes, offset/ref widths and offset-
//     table bytes, syntax pieces, binary facts, conversion nodes, and
//     report/recovery regions.
//   - https://github.com/consema/consema-rs/blob/main/consema-plist/src/lib.rs (PlistProfile ids), lib.rs
//     (PlistParseLimits fields and frozen defaults), parser_xml.rs
//     (PlistSyntaxKind declaration) and parser_xml.rs (the exact
//     kebab-case wire spellings). consema-go/go/plist is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the profile is a closed enum
// and the syntax kinds a closed enum whose `wireName` IS the frozen kebab-
// case spelling asserted by plist.lossless-syntax-query@1 and the vectors.

package consema.plist

import consema.document.ParseLimits
import consema.document.ProfileId

/** Frozen plist formation profiles (RFC 0013 §1; lib.rs). */
enum class PlistProfile {
    /** The plist value vocabulary expressed as XML 1.0 (RFC 0013 §4). */
    XmlV1,

    /** The binary object-table representation (RFC 0013 §5). */
    BinaryV1,
    ;

    /** Immutable profile identifier (lib.rs). */
    fun id(): ProfileId =
        when (this) {
            XmlV1 -> ProfileId("plist.xml", 1)
            BinaryV1 -> ProfileId("plist.binary", 1)
        }

    /** Whether this profile admits a decoded-text view (RFC 0013 §2). */
    internal fun isXml(): Boolean = this == XmlV1
}

/**
 * Closed plist XML lossless syntax-piece classification (RFC 0013 §8.2;
 * parser_xml.rs). The enum order is the Rust declaration order; the
 * query/protocol vocabulary is [wireName] (parser_xml.rs), which is
 * byte-identical to the vector spellings.
 */
enum class PlistSyntaxKind {
    /** Unicode byte-order mark. */
    Bom,

    /** Horizontal whitespace trivia. */
    Whitespace,

    /** Line break trivia. */
    LineBreak,

    /** `<?xml` declaration opening. */
    DeclarationOpen,

    /** Declaration pseudo-attribute name. */
    DeclarationName,

    /** Declaration pseudo-attribute value. */
    DeclarationValue,

    /** `?>` declaration closing. */
    DeclarationClose,

    /** `<!DOCTYPE` opening. */
    DoctypeOpen,

    /** DOCTYPE content between the opening and the closing `>`. */
    DoctypeBody,

    /** Closing `>` of the DOCTYPE. */
    DoctypeClose,

    /** Root element name `plist` and the `>` that closes the root open tag. */
    PlistOpen,

    /** Root attribute name `version`. */
    PlistVersionName,

    /** Root version attribute `="1.0"` including equals, quotes, and value. */
    PlistVersionValue,

    /** `</plist>` root close tag and the root's `/>` when self-closing. */
    PlistClose,

    /** `<dict` open-tag name and its closing `>`. */
    DictOpen,

    /** `</dict>` close tag. */
    DictClose,

    /** `<key` open-tag name and its closing `>`. */
    KeyOpen,

    /** `</key>` close tag. */
    KeyClose,

    /** `<array` open-tag name and its closing `>`. */
    ArrayOpen,

    /** `</array>` close tag. */
    ArrayClose,

    /** `<string` open-tag name and its closing `>`. */
    StringOpen,

    /** `</string>` close tag. */
    StringClose,

    /** `<integer` open-tag name and its closing `>`. */
    IntegerOpen,

    /** `</integer>` close tag. */
    IntegerClose,

    /** `<real` open-tag name and its closing `>`. */
    RealOpen,

    /** `</real>` close tag. */
    RealClose,

    /** `<date` open-tag name and its closing `>`. */
    DateOpen,

    /** `</date>` close tag. */
    DateClose,

    /** `<data` open-tag name and its closing `>`. */
    DataOpen,

    /** `</data>` close tag. */
    DataClose,

    /** `<true/>`, `<true>`, or `</true>`. */
    True,

    /** `<false/>`, `<false>`, or `</false>`. */
    False,

    /** Literal character data of string and key content. */
    Text,

    /** One `&name;` entity reference in string or key content. */
    EntityReference,

    /** One `&#...;` character reference in string or key content. */
    CharacterReference,

    /** `<![CDATA[` opening. */
    CdataOpen,

    /** CDATA character data. */
    CdataText,

    /** `]]>` closing. */
    CdataClose,

    /** `<!--` opening. */
    CommentOpen,

    /** Comment character data. */
    CommentText,

    /** `-->` closing. */
    CommentClose,

    /** `<?` processing-instruction opening. */
    ProcessingInstructionOpen,

    /** Processing-instruction target. */
    ProcessingInstructionTarget,

    /** Processing-instruction content. */
    ProcessingInstructionContent,

    /** `?>` processing-instruction closing. */
    ProcessingInstructionClose,

    /** Bytes not admitted by the Profile's grammar. */
    ErrorRegion,
    ;

    /** Stable query and protocol name (parser_xml.rs). */
    fun wireName(): String =
        when (this) {
            Bom -> "bom"
            Whitespace -> "whitespace"
            LineBreak -> "line-break"
            DeclarationOpen -> "declaration-open"
            DeclarationName -> "declaration-name"
            DeclarationValue -> "declaration-value"
            DeclarationClose -> "declaration-close"
            DoctypeOpen -> "doctype-open"
            DoctypeBody -> "doctype-body"
            DoctypeClose -> "doctype-close"
            PlistOpen -> "plist-open"
            PlistVersionName -> "plist-version-name"
            PlistVersionValue -> "plist-version-value"
            PlistClose -> "plist-close"
            DictOpen -> "dict-open"
            DictClose -> "dict-close"
            KeyOpen -> "key-open"
            KeyClose -> "key-close"
            ArrayOpen -> "array-open"
            ArrayClose -> "array-close"
            StringOpen -> "string-open"
            StringClose -> "string-close"
            IntegerOpen -> "integer-open"
            IntegerClose -> "integer-close"
            RealOpen -> "real-open"
            RealClose -> "real-close"
            DateOpen -> "date-open"
            DateClose -> "date-close"
            DataOpen -> "data-open"
            DataClose -> "data-close"
            True -> "true"
            False -> "false"
            Text -> "text"
            EntityReference -> "entity-reference"
            CharacterReference -> "character-reference"
            CdataOpen -> "cdata-open"
            CdataText -> "cdata-text"
            CdataClose -> "cdata-close"
            CommentOpen -> "comment-open"
            CommentText -> "comment-text"
            CommentClose -> "comment-close"
            ProcessingInstructionOpen -> "processing-instruction-open"
            ProcessingInstructionTarget -> "processing-instruction-target"
            ProcessingInstructionContent -> "processing-instruction-content"
            ProcessingInstructionClose -> "processing-instruction-close"
            ErrorRegion -> "error-region"
        }

    companion object {
        /** Resolves one exact stable kind name (parser_xml.rs). */
        fun fromName(name: String): PlistSyntaxKind? =
            entries.firstOrNull { it.wireName() == name }
    }
}

/**
 * Plist-specific formation, structure, recovery, and conversion limits
 * (RFC 0013 §12; lib.rs).
 *
 * Every limit failure is a fatal formation failure or an atomic operation
 * failure; a limit failure never masquerades as an empty tree, truncated
 * data, a shortened query, a partial target, or a successful edit (hard gate
 * 4, RFC 0013 §12).
 */
data class PlistParseLimits(
    /** Common source, node, nesting, token, and diagnostic limits. */
    val common: ParseLimits,
    /** Maximum decoded UTF-8 bytes (XML profile). */
    val maxDecodedUtf8Bytes: Int,
    /** Maximum decoded Unicode scalars and coordinate steps (XML profile). */
    val maxDecodedScalars: Int,
    /** Maximum native objects: binary object-table entries and native arena
     * nodes. */
    val maxObjectCount: Int,
    /** Maximum container nesting depth of the native value graph. */
    val maxContainerDepth: Int,
    /** Maximum dictionary entries in one dictionary. */
    val maxDictEntries: Int,
    /** Maximum array elements in one array. */
    val maxArrayElements: Int,
    /** Maximum members in one duplicate-key group. */
    val maxDuplicateKeyGroupMembers: Int,
    /** Maximum UTF-16 code units in one string or key. */
    val maxStringCodeUnits: Int,
    /** Maximum bytes in one data value. */
    val maxDataBytes: Int,
    /** Maximum UID values in one document. */
    val maxUidCount: Int,
    /** Maximum extended-size integer objects (binary profile). */
    val maxExtendedSizeIntegers: Int,
    /** Maximum magnitude claimed by one extended size (binary profile). */
    val maxExtendedSizeValue: Int,
    /** Maximum `offsetIntSize` width in bytes (binary profile). */
    val maxOffsetIntSize: Int,
    /** Maximum `objectRefSize` width in bytes (binary profile). */
    val maxObjectRefSize: Int,
    /** Maximum offset-table bytes (binary profile). */
    val maxOffsetTableBytes: Int,
    /** Maximum XML lossless syntax pieces. */
    val maxSyntaxPieces: Int,
    /** Maximum binary object/offset/trailer structural facts. */
    val maxBinaryFacts: Int,
    /** Maximum cross-representation conversion nodes. */
    val maxConversionNodes: Int,
    /** Maximum conversion, projection, or edit report events. */
    val maxReportEvents: Int,
    /** Maximum recovery regions. */
    val maxRecoveryRegions: Int,
) {
    companion object {
        /** The frozen defaults (lib.rs). */
        val default = PlistParseLimits(
            common = ParseLimits.default,
            maxDecodedUtf8Bytes = 128 shl 20,
            maxDecodedScalars = 64 shl 20,
            maxObjectCount = 1_000_000,
            maxContainerDepth = 256,
            maxDictEntries = 1_000_000,
            maxArrayElements = 1_000_000,
            maxDuplicateKeyGroupMembers = 1_000_000,
            maxStringCodeUnits = 16 shl 20,
            maxDataBytes = 16 shl 20,
            maxUidCount = 100_000,
            maxExtendedSizeIntegers = 10_000,
            maxExtendedSizeValue = 1_000_000,
            maxOffsetIntSize = 8,
            maxObjectRefSize = 8,
            maxOffsetTableBytes = 8 shl 20,
            maxSyntaxPieces = 2_000_000,
            maxBinaryFacts = 2_000_000,
            maxConversionNodes = 1_000_000,
            maxReportEvents = 100_000,
            maxRecoveryRegions = 100_000,
        )
    }
}
