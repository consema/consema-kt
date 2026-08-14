// The immutable namespace-aware native XML tree and the lossless syntax
// classification (RFC 0012 §4-7).
//
// Data authority:
//   - RFC 0012 §4 (https://github.com/consema/consema/blob/main/docs/rfcs/0012-xml-1.0-safe-profile-v1.md:132-166):
//     Complete | Recovered | FatalFormationFailure; recovery only at
//     deterministic markup boundaries; the parser never invents a closing
//     tag, namespace binding, attribute value, entity replacement, or second
//     root.
//   - RFC 0012 §5 (0012-...:168-226): the snapshot-bound native roles and
//     the namespace-aware element model (attributes and namespace
//     declarations are ordered native associations with independent
//     identity and source spans).
//   - RFC 0012 §6 (0012-...:228-256): text and attribute values retain
//     ordered fragments (Literal | CharacterReference |
//     PredefinedEntityReference | GeneralEntityReference); native Text
//     semantic content is the concatenation of resolved fragments after XML
//     line-end normalization; CDATA remains a distinct child occurrence;
//     adjacent Text occurrences are not merged.
//   - RFC 0012 §7 (0012-...:258-282): every non-empty raw byte belongs to
//     exactly one ordered structural piece; the frozen v1 kind set.
//   - https://github.com/consema/consema-rs/blob/main/consema-xml/src/document.rs:17-94 (XmlSyntaxKind), document.rs:
//     96-120 (QNameFacts), document.rs:122-172 (ReferenceFragment),
//     document.rs:174-387 (the occurrence data structs), document.rs:388-568
//     (Document), document.rs:570-763 (XmlDocument/XmlElement/XmlContentItem),
//     document.rs:765-799 (text_semantic and the CR/CRLF->LF normalization),
//     document.rs:801-890 (XmlSyntaxKind::as_str/from_name).
//   - consema-go/go/xml/document.go is a cross-reference only.
//
// The `xml.*` diagnostic codes are registered by RFC 0012 as part of the
// `xml.1.0-safe@1` contract and do NOT enter the consema-protocol core error
// registry (RFC 0012 §12, 0012-...:426-433; verified: no xml.* code exists
// in https://github.com/consema/consema-rs/blob/main/consema-protocol/src/error_registry.rs or its Kotlin
// transcription ErrorRegistry.kt), so this family carries its own
// [XmlDiagnostic] record instead of the registry-validated protocol
// Diagnostic (the Rust xml parser constructs consema_core::Diagnostic
// directly, parser.rs:1742-1748).
//
// Kotlin-idiomatic design (NOT a translation): the occurrence families are
// immutable data classes; child content is a sealed [XmlContent] hierarchy
// so `when` over it is exhaustive; handles are immutable classes carrying
// (document, index) like the Go handle structs; the arena is an ordered
// List plus an index-parallel parent table (document.rs:403-407).

package consema.xml

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
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity
import consema.protocol.SourceLocation

/**
 * Closed XML lossless syntax-piece classification (document.rs:17-94). The
 * enum order is the Rust declaration order; the wire/query vocabulary is
 * [asStr] (document.rs:801-844), which is byte-identical to the vector
 * spellings (conformance/vectors/xml-1-0-safe-v1.json xml.syntax-query.*
 * cases).
 */
enum class XmlSyntaxKind {
    /** Unicode byte-order mark. */
    Bom,

    /** Horizontal whitespace. */
    Whitespace,

    /** Line break. */
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

    /** DOCTYPE name. */
    DoctypeName,

    /** Admitted internal DTD subset markup. */
    DtdMarkup,

    /** `>` DOCTYPE closing. */
    DoctypeClose,

    /** `<` or `</` tag opening. */
    TagOpen,

    /** `>` tag closing. */
    TagClose,

    /** `/>` empty-element closing. */
    EmptyElementClose,

    /** `</` end-tag opening. */
    EndTagOpen,

    /** QName prefix spelling. */
    Prefix,

    /** QName local-name spelling. */
    LocalName,

    /** QName colon. */
    Colon,

    /** Attribute name. */
    AttributeName,

    /** `=` assignment. */
    Equals,

    /** Attribute value quote. */
    Quote,

    /** Attribute value content. */
    AttributeValue,

    /** `xmlns` or `xmlns:p` declaration. */
    NamespaceDeclaration,

    /** Character data without markup. */
    Text,

    /** General or predefined entity reference. */
    EntityReference,

    /** Decimal or hexadecimal character reference. */
    CharacterReference,

    /** `<![CDATA[` opening. */
    CdataOpen,

    /** CDATA content. */
    CdataText,

    /** `]]>` CDATA closing. */
    CdataClose,

    /** `<!--` comment opening. */
    CommentOpen,

    /** Comment content. */
    CommentText,

    /** `-->` comment closing. */
    CommentClose,

    /** `<?` PI opening. */
    ProcessingInstructionOpen,

    /** PI target. */
    ProcessingInstructionTarget,

    /** PI content. */
    ProcessingInstructionContent,

    /** `?>` PI closing. */
    ProcessingInstructionClose,

    /** Recovered error region. */
    ErrorRegion,
    ;

    /** Stable kind name used by the lossless syntax query protocol
     * (document.rs:801-844). */
    fun asStr(): String =
        when (this) {
            Bom -> "bom"
            Whitespace -> "whitespace"
            LineBreak -> "line-break"
            DeclarationOpen -> "declaration-open"
            DeclarationName -> "declaration-name"
            DeclarationValue -> "declaration-value"
            DeclarationClose -> "declaration-close"
            DoctypeOpen -> "doctype-open"
            DoctypeName -> "doctype-name"
            DtdMarkup -> "dtd-markup"
            DoctypeClose -> "doctype-close"
            TagOpen -> "tag-open"
            TagClose -> "tag-close"
            EmptyElementClose -> "empty-element-close"
            EndTagOpen -> "end-tag-open"
            Prefix -> "prefix"
            LocalName -> "local-name"
            Colon -> "colon"
            AttributeName -> "attribute-name"
            Equals -> "equals"
            Quote -> "quote"
            AttributeValue -> "attribute-value"
            NamespaceDeclaration -> "namespace-declaration"
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
        /** Resolves a stable kind name from the lossless syntax query
         * protocol (document.rs:846-889). */
        fun fromName(name: String): XmlSyntaxKind? =
            entries.firstOrNull { it.asStr() == name }
    }
}

/** One lexical QName with its source-derived facts (RFC 0012 §5;
 * document.rs:96-120). */
data class QNameFacts(
    /** Original prefix spelling, when present. */
    val prefix: String?,
    /** Local name. */
    val local: String,
    /** Complete QName span. */
    val span: Span,
    /** Prefix span, when present. */
    val prefixSpan: Span?,
    /** Local-name span. */
    val localSpan: Span,
) {
    /** Resolves this QName against an element's in-scope scope
     * (document.rs:111-120). */
    fun qname(): QName = QName(prefix, local)
}

/**
 * One ordered text or attribute-value fragment (RFC 0012 §6;
 * document.rs:135-172).
 */
sealed class ReferenceFragment {
    /** Exact source span of this fragment (document.rs:122-133). */
    abstract val span: Span

    /** Literal character data. */
    data class Literal(
        /** Exact source span. */
        override val span: Span,
        /** Decoded literal text. */
        val text: String,
    ) : ReferenceFragment()

    /** Decimal or hexadecimal character reference. */
    data class CharacterReference(
        /** Exact source span of `&#…;`. */
        override val span: Span,
        /** Resolved legal XML character. */
        val resolved: Char,
    ) : ReferenceFragment()

    /** One of the five predefined entity references. */
    data class PredefinedEntity(
        /** Exact source span of `&…;`. */
        override val span: Span,
        /** Entity name. */
        val name: String,
        /** Replacement character data. */
        val resolved: String,
    ) : ReferenceFragment()

    /** An admitted internal general entity reference. */
    data class GeneralEntity(
        /** Exact source span of `&…;`. */
        override val span: Span,
        /** Entity name. */
        val name: String,
        /** Fully resolved replacement text. */
        val resolved: String,
        /** Span of the declaring `<!ENTITY …>`. */
        val declarationSpan: Span,
    ) : ReferenceFragment()
}

/** One XML namespace declaration association (RFC 0012 §5;
 * document.rs:174-187). */
data class XmlNamespaceBindingData(
    /** Document-wide binding ordinal for stable identity. */
    val ordinal: Long,
    /** `xmlns="…"` or `xmlns:p="…"` span. */
    val span: Span,
    /** Bound prefix; null is the default namespace. */
    val prefix: String?,
    /** Namespace URI value span. */
    val uriSpan: Span,
    /** Namespace URI. */
    val uri: String,
)

/** One XML attribute association (RFC 0012 §5-6; document.rs:189-211). */
data class XmlAttributeData(
    /** Document-wide attribute ordinal for stable identity. */
    val ordinal: Long,
    /** Whole attribute span. */
    val span: Span,
    /** Lexical QName facts. */
    val qname: QNameFacts,
    /** Resolved expanded name; null when a namespace error kept the name
     * unprovable. */
    val expanded: ExpandedName?,
    /** Whether the value used single or double quotes. */
    val singleQuote: Boolean,
    /** Exact value span between the quotes; empty for an empty value. */
    val valueSpan: Span,
    /** Ordered raw value fragments. */
    val fragments: List<ReferenceFragment>,
    /** XML 1.0 CDATA-normalized semantic value. */
    val normalizedValue: String,
)

/** One text occurrence with ordered fragments (RFC 0012 §6;
 * document.rs:213-222). */
data class XmlTextData(
    /** Document-wide text ordinal for stable identity. */
    val ordinal: Long,
    /** Exact source span. */
    val span: Span,
    /** Ordered fragments; adjacent literals are not merged across markup. */
    val fragments: List<ReferenceFragment>,
)

/** One CDATA occurrence (RFC 0012 §6; document.rs:224-235). */
data class XmlCdataData(
    /** Document-wide ordinal for stable identity. */
    val ordinal: Long,
    /** `![CDATA[…]]>` span. */
    val span: Span,
    /** Content text span. */
    val textSpan: Span,
    /** Content text; never entity-expanded. */
    val text: String,
)

/** One comment occurrence (RFC 0012 §6; document.rs:237-248). */
data class XmlCommentData(
    /** Document-wide ordinal for stable identity. */
    val ordinal: Long,
    /** `<!--…-->` span. */
    val span: Span,
    /** Content text span. */
    val textSpan: Span,
    /** Content text; never entity-expanded. */
    val text: String,
)

/** One processing instruction (RFC 0012 §6; document.rs:250-263). */
data class XmlPiData(
    /** Document-wide ordinal for stable identity. */
    val ordinal: Long,
    /** `<?…?>` span. */
    val span: Span,
    /** Target span. */
    val targetSpan: Span,
    /** Target; cannot compare case-insensitively equal to `xml`. */
    val target: String,
    /** Content span and text, when present; never entity-expanded. */
    val content: Pair<Span, String>?,
)

/** One recovered error region (RFC 0012 §4; document.rs:265-272). */
data class XmlErrorRegionData(
    /** Document-wide ordinal for stable identity. */
    val ordinal: Long,
    /** Recovered error span. */
    val span: Span,
)

/** One element occurrence (RFC 0012 §5; document.rs:274-296). */
data class XmlElementData(
    /** Arena index for stable identity. */
    val index: Int,
    /** Full start-tag span, or the whole empty-element span. */
    val span: Span,
    /** Lexical QName facts. */
    val qname: QNameFacts,
    /** Resolved expanded name; null when a namespace error kept the name
     * unprovable. */
    val expanded: ExpandedName?,
    /** Whether a namespace error kept the name unprovable. */
    val hasNamespaceError: Boolean,
    /** Immutable ancestry-derived in-scope namespace chain. */
    val scope: NamespaceScope,
    /** Ordered namespace declarations on this element. */
    val namespaces: List<XmlNamespaceBindingData>,
    /** Ordered attributes, excluding namespace declarations. */
    val attributes: List<XmlAttributeData>,
    /** Ordered child content arena indices; never sorted by type. */
    val children: List<Int>,
)

/** One child content occurrence (RFC 0012 §5; document.rs:298-313). */
sealed class XmlContent {
    /** Exact source span of this occurrence (document.rs:315-328). */
    abstract val span: Span

    /** Child element. */
    data class Element(val data: XmlElementData) : XmlContent() {
        override val span: Span get() = data.span
    }

    /** Text occurrence. */
    data class Text(val data: XmlTextData) : XmlContent() {
        override val span: Span get() = data.span
    }

    /** CDATA occurrence. */
    data class Cdata(val data: XmlCdataData) : XmlContent() {
        override val span: Span get() = data.span
    }

    /** Comment occurrence. */
    data class Comment(val data: XmlCommentData) : XmlContent() {
        override val span: Span get() = data.span
    }

    /** Processing instruction. */
    data class ProcessingInstruction(val data: XmlPiData) : XmlContent() {
        override val span: Span get() = data.span
    }

    /** Recovered error region. */
    data class ErrorRegion(val data: XmlErrorRegionData) : XmlContent() {
        override val span: Span get() = data.span
    }
}

/** One prolog or epilog occurrence (document.rs:330-345). */
sealed class XmlPrologItem {
    /** The XML declaration, only in the prolog. */
    data class Declaration(val data: XmlDeclarationData) : XmlPrologItem()

    /** DOCTYPE occurrence, only in the prolog. */
    data class Doctype(val data: XmlDoctypeData) : XmlPrologItem()

    /** Processing instruction. */
    data class ProcessingInstruction(val data: XmlPiData) : XmlPrologItem()

    /** Comment. */
    data class Comment(val data: XmlCommentData) : XmlPrologItem()

    /** Byte-order mark trivia. */
    data class Bom(val span: Span) : XmlPrologItem()

    /** Whitespace trivia. */
    data class Whitespace(val span: Span) : XmlPrologItem()
}

/** XML declaration facts (RFC 0012 §2; document.rs:347-360). */
data class XmlDeclarationData(
    /** `<?xml …?>` span. */
    val span: Span,
    /** Version pseudo-attribute span. */
    val versionSpan: Span,
    /** Version; exactly `1.0`. */
    val version: String,
    /** Optional encoding pseudo-attribute span and value. */
    val encoding: Pair<Span, String>?,
    /** Optional standalone pseudo-attribute span and value. */
    val standalone: Pair<Span, Boolean>?,
)

/** One admitted internal general entity declaration (RFC 0012 §3;
 * document.rs:362-373). */
data class EntityDeclarationData(
    /** `<!ENTITY …>` span. */
    val span: Span,
    /** Entity name. */
    val name: String,
    /** Replacement value span. */
    val replacementSpan: Span,
    /** Raw replacement text. */
    val replacement: String,
)

/** DOCTYPE facts (RFC 0012 §3; document.rs:375-386). */
data class XmlDoctypeData(
    /** `<!DOCTYPE …>` span. */
    val span: Span,
    /** Root-name QName facts. */
    val name: QNameFacts,
    /** Ordered admitted internal general entity declarations. */
    val entities: List<EntityDeclarationData>,
    /** Whether an excluded external/validation construct forced recovery. */
    val recovered: Boolean,
)

/**
 * One stable `xml.*` diagnostic published by this family (RFC 0012 §12).
 * The xml codes are part of the `xml.1.0-safe@1` contract, not the
 * consema-protocol core registry, so this record mirrors the Rust
 * consema_core::Diagnostic::new surface (https://github.com/consema/consema-rs/blob/main/consema-core/src/
 * diagnostic.rs:65-104) and is constructed without registry validation.
 * [occurrence] is the final stable ordering key (diagnostic.rs:80-81).
 */
data class XmlDiagnostic(
    /** Stable namespaced code. */
    val code: String,
    /** Stable category. */
    val category: DiagnosticCategory,
    /** Presentation severity. */
    val severity: Severity,
    /** Primary location when one exists. */
    val primary: SourceLocation?,
    /** Occurrence ordinal used as the final stable ordering key. */
    val occurrence: ULong,
)

/**
 * One stable `xml.*` diagnostic with an argument map, mirroring the full
 * consema_core::Diagnostic record (diagnostic.rs:65-82) for the projection
 * and edit failure surfaces that carry structured arguments.
 */
data class XmlDiagnosticWithArguments(
    /** Stable namespaced code. */
    val code: String,
    /** Stable category. */
    val category: DiagnosticCategory,
    /** Presentation severity. */
    val severity: Severity,
    /** Primary location when one exists. */
    val primary: SourceLocation?,
    /** Structured arguments sorted by key. */
    val arguments: Map<String, String>,
    /** Occurrence ordinal used as the final stable ordering key. */
    val occurrence: ULong,
)

/**
 * The immutable XML document (RFC 0012 §4; document.rs:388-568). The
 * Document retains prolog order, one document element, epilog order, and
 * every exact source span. Parsing happens in Parser.kt; this file pins the
 * read surface and the module-internal arena access shared by query,
 * projection, materialization, and edit.
 */
class Document internal constructor(
    internal val authority: DocumentAuthority,
    internal val source: SourceSnapshot,
    internal val profile: XmlProfile,
    private val structuralIndex: LosslessStructuralIndex,
    private val syntaxKindList: List<XmlSyntaxKind>,
    internal val formationStatus: FormationStatus,
    internal val diagnosticsList: List<XmlDiagnostic>,
    internal val declarationData: XmlDeclarationData?,
    internal val doctypeData: XmlDoctypeData?,
    internal val prologItems: List<XmlPrologItem>,
    internal val epilogItems: List<XmlPrologItem>,
    internal val rootIndex: Int?,
    internal val nodes: List<XmlContent>,
    internal val parentOf: List<Int?>,
    internal val parseLimits: XmlParseLimits,
) {
    /** Snapshot identity to which every NodeRef and Span belongs
     * (document.rs:532-536). */
    val snapshotIdentity: SnapshotIdentity
        get() = authority.identity

    /** Exact immutable source (document.rs:460-464). */
    fun source(): SourceSnapshot = source

    /** Default rendering is the exact current source bytes (document.rs:466-470). */
    fun render(): ByteArray = source.bytes()

    /** XML format family contract (document.rs:545-549). */
    fun formatFamily(): FormatFamilyId = FormatFamilyId("xml", 1)

    /** Stable profile identifier (document.rs:551-555). */
    fun profileId(): ProfileId = profile.id()

    /** Formation status (document.rs:448-458). */
    fun formationStatus(): FormationStatus = formationStatus

    /** Deterministically ordered document diagnostics (document.rs:490-494). */
    fun diagnostics(): List<XmlDiagnostic> = diagnosticsList

    /** Exhaustive token/trivia/error-region byte coverage
     * (document.rs:472-476). */
    fun losslessStructuralIndex(): LosslessStructuralIndex = structuralIndex

    /** Format-specific kind for every structural piece, in the same source
     * order (document.rs:478-482). */
    fun losslessSyntaxKinds(): List<XmlSyntaxKind> = syntaxKindList

    /** The XML declaration, when present (document.rs:496-500). */
    fun declaration(): XmlDeclarationData? = declarationData

    /** The DOCTYPE occurrence, when present (document.rs:502-506). */
    fun doctype(): XmlDoctypeData? = doctypeData

    /** Ordered prolog items before the document element (document.rs:508-512). */
    fun prolog(): List<XmlPrologItem> = prologItems

    /** Ordered epilog items after the document element (document.rs:514-518). */
    fun epilog(): List<XmlPrologItem> = epilogItems

    /** The one document element, when formation proved it (document.rs:520-526). */
    fun root(): XmlElement? = rootIndex?.let { XmlElement(this, it) }

    /** All arena nodes; child content of every element is reachable here
     * (document.rs:528-530). */
    fun nodes(): List<XmlContent> = nodes

    /** Parent element arena index of one arena node; null for the root
     * element and for orphaned content (document.rs:538-543). */
    internal fun parentOf(index: Int): Int? = parentOf.getOrNull(index)

    /** Snapshot-bound document handle (document.rs:557-561). */
    fun nodeRef(): NodeRef = authority.nodeRef(0, NodeRole.XmlDocument)

    /** Snapshot-bound identity of one ordinal-scoped occurrence
     * (document.rs:563-567). */
    fun occurrenceNodeRef(ordinal: Long, role: NodeRole): NodeRef =
        authority.nodeRef(ordinal, role)

    /** Element arena node (module-internal). */
    internal fun elementData(index: Int): XmlElementData =
        when (val content = nodes[index]) {
            is XmlContent.Element -> content.data
            else -> error("element handle always points at element arena data")
        }

    /** Child role of one arena node (document.rs:690-700). */
    internal fun nodeRoleOf(index: Int): NodeRole =
        when (nodes[index]) {
            is XmlContent.Element -> NodeRole.XmlElement
            is XmlContent.Text -> NodeRole.XmlText
            is XmlContent.Cdata -> NodeRole.XmlCdata
            is XmlContent.Comment -> NodeRole.XmlComment
            is XmlContent.ProcessingInstruction -> NodeRole.XmlProcessingInstruction
            is XmlContent.ErrorRegion -> NodeRole.XmlErrorRegion
        }

    /** Snapshot-bound element identity (document.rs:618-626). */
    internal fun elementNodeRef(index: Int): NodeRef =
        authority.nodeRef(index.toLong(), NodeRole.XmlElement)

    /**
     * Validates one NodeRef against the allowed roles and resolves its
     * entity index. Throws [XmlAccessException]: WrongSnapshot, WrongRole,
     * or UnknownNode.
     */
    internal fun validateRef(node: NodeRef, roles: List<NodeRole>): Int {
        try {
            authority.verify(node)
        } catch (e: consema.document.LocationException) {
            throw XmlAccessException(XmlAccessErrorKind.WrongSnapshot)
        }
        if (node.role !in roles) {
            throw XmlAccessException(XmlAccessErrorKind.WrongRole)
        }
        val index = node.index
        if (index < 0 || index >= nodes.size.toLong()) {
            throw XmlAccessException(XmlAccessErrorKind.UnknownNode)
        }
        return index.toInt()
    }

    /** Structural coverage pieces (document.rs:472-476). */
    internal fun pieces(): List<StructuralPiece> = structuralIndex.pieces()
}

/** Snapshot-bound view of the whole document (document.rs:570-609). */
class XmlDocument internal constructor(
    private val owner: Document,
) {
    /** Snapshot-bound document identity (document.rs:583-587). */
    fun nodeRef(): NodeRef = owner.nodeRef()

    /** Exact raw document span (document.rs:589-596). */
    fun span(): Span = owner.authority.span(0, owner.source.len)

    /** The document element (document.rs:598-602). */
    fun root(): XmlElement? = owner.root()

    /** Formation status (document.rs:604-608). */
    fun status(): FormationStatus = owner.formationStatus
}

/** Snapshot-bound element handle (document.rs:611-679). */
class XmlElement internal constructor(
    private val owner: Document,
    internal val index: Int,
) {
    /** Snapshot-bound stable identity (document.rs:618-626). */
    fun nodeRef(): NodeRef = owner.elementNodeRef(index)

    /** Full start-tag or empty-element span (document.rs:628-632). */
    fun span(): Span = elementData().span

    /** Lexical QName facts (document.rs:634-637). */
    fun qname(): QNameFacts = elementData().qname

    /** Resolved expanded name, when the namespace binding could be proven
     * (document.rs:639-643). */
    fun expanded(): ExpandedName? = elementData().expanded

    /** Ordered namespace declarations on this element (document.rs:645-649). */
    fun namespaceBindings(): List<XmlNamespaceBindingData> = elementData().namespaces

    /** Ordered attributes, excluding namespace declarations
     * (document.rs:651-655). */
    fun attributes(): List<XmlAttributeData> = elementData().attributes

    /** Ordered child content occurrences; mixed-content order is retained
     * (document.rs:657-665). */
    fun children(): List<XmlContentItem> =
        elementData().children.map { XmlContentItem(owner, it) }

    /** Whether the element has no child content (document.rs:667-671). */
    fun isEmpty(): Boolean = elementData().children.isEmpty()

    internal fun elementData(): XmlElementData = owner.elementData(index)
}

/** One child content occurrence (document.rs:681-763). */
class XmlContentItem internal constructor(
    private val owner: Document,
    internal val index: Int,
) {
    /** Snapshot-bound stable identity (document.rs:688-701). */
    fun nodeRef(): NodeRef = owner.authority.nodeRef(index.toLong(), owner.nodeRoleOf(index))

    /** Exact source span (document.rs:703-714). */
    fun span(): Span = owner.nodes[index].span

    /** Element content, when this is an element occurrence (document.rs:716-726). */
    fun element(): XmlElement? =
        if (owner.nodes[index] is XmlContent.Element) XmlElement(owner, index) else null

    /** Text occurrence data, when this is a text occurrence (document.rs:728-735). */
    fun text(): XmlTextData? =
        (owner.nodes[index] as? XmlContent.Text)?.data

    /** CDATA occurrence data, when present (document.rs:737-744). */
    fun cdata(): XmlCdataData? =
        (owner.nodes[index] as? XmlContent.Cdata)?.data

    /** Comment occurrence data, when present (document.rs:746-753). */
    fun comment(): XmlCommentData? =
        (owner.nodes[index] as? XmlContent.Comment)?.data

    /** Processing-instruction data, when present (document.rs:755-762). */
    fun processingInstruction(): XmlPiData? =
        (owner.nodes[index] as? XmlContent.ProcessingInstruction)?.data
}

/**
 * Semantic concatenation of one text occurrence after XML line-end
 * normalization to LF (RFC 0012 §6; document.rs:765-799).
 */
fun textSemantic(text: XmlTextData): String {
    val out = StringBuilder()
    for (fragment in text.fragments) {
        when (fragment) {
            is ReferenceFragment.Literal -> pushNormalized(out, fragment.text)
            is ReferenceFragment.CharacterReference -> out.append(fragment.resolved)
            is ReferenceFragment.PredefinedEntity -> pushNormalized(out, fragment.resolved)
            is ReferenceFragment.GeneralEntity -> pushNormalized(out, fragment.resolved)
        }
    }
    return out.toString()
}

private fun pushNormalized(out: StringBuilder, text: String) {
    var index = 0
    while (index < text.length) {
        val c = text[index]
        when (c) {
            // XML 1.0 line-end normalization: CRLF and CR become LF.
            '\r' -> {
                out.append('\n')
                if (index + 1 < text.length && text[index + 1] == '\n') {
                    index += 1
                }
            }
            else -> out.append(c)
        }
        index += 1
    }
}

/** Stable typed XML access failure (document.rs:596-604; the Rust
 * LocationError surface). The [name] spellings are the language-neutral
 * comparison facts; these names are NOT registered error codes. */
enum class XmlAccessErrorKind {
    /** NodeRef belongs to another snapshot. */
    WrongSnapshot,

    /** NodeRef role cannot be used by this operation. */
    WrongRole,

    /** Index is not present in this snapshot. */
    UnknownNode,
}

/** The typed XML access failure. */
class XmlAccessException(val kind: XmlAccessErrorKind) :
    Exception("xml access: ${kind.name}")
