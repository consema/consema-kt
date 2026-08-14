// The immutable YAML document model: snapshot-bound native handles, the
// ordered serialization tree, anchor/alias occurrences, and the lossless
// coverage index.
//
// Data authority:
//   - RFC 0007 §2 (https://github.com/consema/consema/blob/main/docs/rfcs/0007-yaml-family-profiles-and-safety-v1.md):
//     the three layers — presentation stream, serialization tree
//     (ordered document nodes, unresolved/resolved tag facts), and
//     representation graph (resolved tagged nodes, sharing, cycles). Anchors
//     and aliases are NOT PortableGraph node kinds.
//   - RFC 0007 §7: the immutable YAML Document retains
//     directives, markers, BOMs, comments, styles, anchor definitions and
//     alias occurrences with exact names and source spans, arbitrary keys,
//     duplicate source associations, compact notation, and exhaustive
//     non-overlapping raw-byte coverage.
//   - https://github.com/consema/consema-rs/blob/main/consema-yaml/src/lib.rs pins the public handle surface
//     (stream_node_ref, document(ordinal), alias_count, node anchors/spans,
//     sequence/mapping associations); https://github.com/consema/consema-rs/blob/main/consema-yaml/src/native.rs
//     pins the internal node/alias/association storage and native.rs
//     pins the composer; consema-go/go/yaml/document.go is a cross-reference only.
//
// Kotlin-idiomatic design: handles are immutable classes carrying (document,
// index), the Kotlin analogue of the Rust borrowed handles; entity storage is
// a private model and the Document is a closed read surface. The composer
// consumes the backend event list and the scanned anchor/alias occurrences
// exactly like native.rs.

package consema.yaml

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

/** The immutable native stream model (native.rs). */
internal class NativeStream(
    val nodes: List<NativeNode>,
    val documents: List<NativeDocument>,
    val aliases: List<NativeAlias>,
)

/** One independent document (native.rs). */
internal class NativeDocument(val root: Int, val span: Span)

/** One representation node (native.rs). */
internal class NativeNode(
    val tag: String,
    val anchor: String?,
    val anchorSpan: Span?,
    val span: Span,
    val content: NativeContent,
)

/** Node content (native.rs). */
internal sealed class NativeContent {
    data class Scalar(val scalar: NativeScalar) : NativeContent()
    data class Sequence(val items: List<NativeSequenceItem>) : NativeContent()
    data class Mapping(val entries: List<NativeMappingEntry>) : NativeContent()
}

/** One ordered sequence association (native.rs). */
internal class NativeSequenceItem(
    val identity: Long,
    val node: Int,
    val span: Span,
    val alias: Int?,
)

/** One ordered mapping association (native.rs). */
internal class NativeMappingEntry(
    val identity: Long,
    val key: Int,
    val value: Int,
    val span: Span,
    val keyAlias: Int?,
    val valueAlias: Int?,
)

/** One alias serialization occurrence (native.rs). */
internal class NativeAlias(
    val identity: Long,
    val name: String,
    val target: Int,
    val span: Span,
)

/** One composed node occurrence: its representation index, occurrence span,
 * and the alias ordinal that supplied the edge, when present (native.rs). */
private class ComposedOccurrence(val node: Int, val span: Span, val alias: Int?)

/**
 * Composes the backend event stream into the immutable native model
 * (native.rs). Every anchor registers before descending into its
 * node; an alias resolves to the most recent preceding anchor of the same
 * document (native.rs); events and named occurrences must be
 * exhausted exactly (yaml.native.trailing-events@1,
 * yaml.native.trailing-named-occurrence@1).
 */
internal fun compose(
    events: List<BackendEvent>,
    source: SourceSnapshot,
    authority: DocumentAuthority,
    profile: YamlProfile,
    tokenized: Tokenized,
    limits: ParseLimits,
): NativeStream {
    val composer = Composer(events, source, authority, profile, tokenized, limits)
    return composer.compose()
}

private class Composer(
    private val events: List<BackendEvent>,
    private val source: SourceSnapshot,
    private val authority: DocumentAuthority,
    private val profile: YamlProfile,
    tokenized: Tokenized,
    private val limits: ParseLimits,
) {
    private var position = 0
    private val nodes = ArrayList<NativeNode?>()
    private val documents = ArrayList<NativeDocument>()
    private val anchors = HashMap<Int, Int>()
    private val anchorNames = HashMap<Int, String>()
    private val anchorOccurrences = tokenized.anchors.iterator()
    private val aliasOccurrences = tokenized.aliases.iterator()
    private val composedAliases = ArrayList<NativeAlias>()
    private var nextAssociation = 0L
    private val raw = RawByteResolver(source)

    fun compose(): NativeStream {
        expectSimple { it is BackendEventKind.StreamStart }
        while (!peekIs { it is BackendEventKind.StreamEnd }) {
            val documentStart = takeSimple { it is BackendEventKind.DocumentStart }
            anchors.clear()
            anchorNames.clear()
            val root = node()
            val documentEnd = takeSimple { it is BackendEventKind.DocumentEnd }
            val span = rawSpan(BackendSpan(documentStart.startScalar, documentEnd.endScalar))
            documents.add(NativeDocument(root.node, span))
        }
        expectSimple { it is BackendEventKind.StreamEnd }
        if (position != events.size) {
            throw nativeFailure("yaml.native.trailing-events@1")
        }
        if (anchorOccurrences.hasNext() || aliasOccurrences.hasNext()) {
            throw nativeFailure("yaml.native.trailing-named-occurrence@1")
        }
        val completed = ArrayList<NativeNode>(nodes.size)
        for (index in nodes.indices) {
            completed.add(nodes[index] ?: error("every reserved YAML node is defined"))
        }
        return NativeStream(completed, documents, composedAliases)
    }

    private fun node(): ComposedOccurrence {
        val event = events.getOrNull(position)
            ?: throw nativeFailure("yaml.native.unexpected-end@1")
        position++
        return when (val kind = event.kind) {
            is BackendEventKind.Alias -> {
                val target = anchors[kind.anchorId]
                    ?: throw nativeFailure("yaml.anchor.unknown@1")
                val occurrence = aliasOccurrences.next()
                    ?: throw nativeFailure("yaml.alias.name-unavailable@1")
                val name = occurrence.name
                if (anchorNames[kind.anchorId] != name) {
                    throw nativeFailure("yaml.alias.name-mismatch@1")
                }
                val identity = associationIdentity()
                val ordinal = composedAliases.size
                composedAliases.add(
                    NativeAlias(identity, name, target, occurrence.span),
                )
                ComposedOccurrence(target, occurrence.span, ordinal)
            }
            is BackendEventKind.Scalar -> {
                val index = reserveNode()
                val (anchor, anchorSpan) = registerAnchor(kind.anchorId, index)
                val decoded = exactEmptyScalar(
                    kind.decoded,
                    event.span,
                    kind.style,
                    raw,
                    source.decodedText() ?: "",
                )
                val (tag, scalar) = resolveScalar(decoded, publicStyle(kind.style), kind.tag?.let { it.prefix + it.suffix }, profile)
                val span = rawSpan(event.span)
                nodes[index] = NativeNode(
                    tag,
                    anchor,
                    anchorSpan,
                    span,
                    NativeContent.Scalar(scalar),
                )
                ComposedOccurrence(index, span, null)
            }
            is BackendEventKind.SequenceStart -> {
                val index = reserveNode()
                val (anchor, anchorSpan) = registerAnchor(kind.anchorId, index)
                val tag = resolveCollectionTag(kind.tag?.let { it.prefix + it.suffix }, TAG_SEQ)
                val start = rawSpan(event.span)
                val items = ArrayList<NativeSequenceItem>()
                while (!peekIs { it is BackendEventKind.SequenceEnd }) {
                    val occurrence = node()
                    items.add(
                        NativeSequenceItem(
                            associationIdentity(),
                            occurrence.node,
                            occurrence.span,
                            occurrence.alias,
                        ),
                    )
                }
                val end = rawSpan(takeSimple { it is BackendEventKind.SequenceEnd })
                val span = authority.span(start.startByte, end.endByte)
                nodes[index] = NativeNode(
                    tag,
                    anchor,
                    anchorSpan,
                    span,
                    NativeContent.Sequence(items),
                )
                ComposedOccurrence(index, span, null)
            }
            is BackendEventKind.MappingStart -> {
                val index = reserveNode()
                val (anchor, anchorSpan) = registerAnchor(kind.anchorId, index)
                val tag = resolveCollectionTag(kind.tag?.let { it.prefix + it.suffix }, TAG_MAP)
                val start = rawSpan(event.span)
                val entries = ArrayList<NativeMappingEntry>()
                while (!peekIs { it is BackendEventKind.MappingEnd }) {
                    val key = node()
                    if (peekIs { it is BackendEventKind.MappingEnd }) {
                        throw nativeFailure("yaml.mapping.missing-value@1")
                    }
                    val value = node()
                    entries.add(
                        NativeMappingEntry(
                            associationIdentity(),
                            key.node,
                            value.node,
                            authority.span(key.span.startByte, value.span.endByte),
                            key.alias,
                            value.alias,
                        ),
                    )
                }
                val end = rawSpan(takeSimple { it is BackendEventKind.MappingEnd })
                val span = authority.span(start.startByte, end.endByte)
                nodes[index] = NativeNode(
                    tag,
                    anchor,
                    anchorSpan,
                    span,
                    NativeContent.Mapping(entries),
                )
                ComposedOccurrence(index, span, null)
            }
            else -> throw nativeFailure("yaml.native.unexpected-event@1")
        }
    }

    private fun reserveNode(): Int {
        val observed = nodes.size + 1
        if (observed > limits.maxNodeCount) {
            throw resourceLimit("native-nodes", observed, limits.maxNodeCount)
        }
        val index = nodes.size
        nodes.add(null)
        return index
    }

    private fun registerAnchor(anchorId: Int?, node: Int): Pair<String?, Span?> {
        if (anchorId == null) {
            return null to null
        }
        val occurrence = anchorOccurrences.next()
            ?: throw nativeFailure("yaml.anchor.name-unavailable@1")
        anchors[anchorId] = node
        anchorNames[anchorId] = occurrence.name
        return occurrence.name to occurrence.span
    }

    private fun associationIdentity(): Long {
        val identity = nextAssociation
        nextAssociation++
        return identity
    }

    private fun peekIs(predicate: (BackendEventKind) -> Boolean): Boolean =
        events.getOrNull(position)?.let { predicate(it.kind) } ?: false

    private fun expectSimple(predicate: (BackendEventKind) -> Boolean) {
        takeSimple(predicate)
    }

    private fun takeSimple(predicate: (BackendEventKind) -> Boolean): BackendSpan {
        val event = events.getOrNull(position)
        if (event == null || !predicate(event.kind)) {
            throw nativeFailure("yaml.native.unexpected-event@1")
        }
        position++
        return event.span
    }

    private fun rawSpan(span: BackendSpan): Span {
        val start = raw.resolve(span.startScalar)
        val end = raw.resolve(span.endScalar)
        return authority.span(start, end)
    }
}

/** Rewrites the backend's empty-plain-scalar placeholder back to the empty
 * string (native.rs): only the `"~"` plain placeholder is rewritten;
 * a quoted `"~"` or `'~'` is a real string scalar. */
private fun exactEmptyScalar(
    decoded: String,
    span: BackendSpan,
    style: BackendScalarStyle,
    raw: RawByteResolver,
    text: String,
): String {
    if (style == BackendScalarStyle.Plain && decoded == "~") {
        val start = raw.decodedByteAt(span.startScalar)
        val end = raw.decodedByteAt(span.endScalar)
        if (text.substring(start, end) != "~") {
            return ""
        }
    }
    return decoded
}

private fun publicStyle(style: BackendScalarStyle): YamlScalarStyle =
    when (style) {
        BackendScalarStyle.Plain -> YamlScalarStyle.Plain
        BackendScalarStyle.SingleQuoted -> YamlScalarStyle.SingleQuoted
        BackendScalarStyle.DoubleQuoted -> YamlScalarStyle.DoubleQuoted
        BackendScalarStyle.Literal -> YamlScalarStyle.Literal
        BackendScalarStyle.Folded -> YamlScalarStyle.Folded
    }

internal fun nodeRef(authority: DocumentAuthority, index: Int): NodeRef =
    authority.nodeRef(index.toLong(), NodeRole.YamlNode)

/**
 * Immutable exact-source YAML stream snapshot (lib.rs). Parsing
 * happens in Parser.kt; this class pins the read surface and the
 * module-internal native model shared by query, projection, materialization,
 * and edit.
 */
class Document internal constructor(
    internal val authority: DocumentAuthority,
    internal val source: SourceSnapshot,
    internal val profile: YamlProfile,
    internal val structuralIndex: LosslessStructuralIndex,
    internal val syntaxKinds: List<YamlSyntaxKind>,
    internal val native: NativeStream,
    internal val streamDocuments: Int,
    internal val parseLimits: ParseLimits,
) {
    /** Snapshot-bound identity of the complete serialization stream
     * (lib.rs). */
    fun streamNodeRef(): NodeRef = authority.nodeRef(0, NodeRole.YamlStream)

    /** Exact raw span of the complete serialization stream (lib.rs). */
    fun streamSpan(): Span = authority.span(0, source.len)

    /** Snapshot identity to which every NodeRef and Span belongs
     * (lib.rs). */
    val snapshotIdentity: SnapshotIdentity
        get() = authority.identity

    /** Exact immutable raw source and decoded-location facts (lib.rs). */
    fun source(): SourceSnapshot = source

    /** Default rendering is byte-for-byte identical to the input (lib.rs). */
    fun render(): ByteArray = source.bytes()

    /** YAML format-family contract (lib.rs). */
    fun formatFamily(): FormatFamilyId = FormatFamilyId("yaml", 1)

    /** Exact selected YAML profile (lib.rs). */
    fun profileId(): ProfileId = profile.id()

    /** Complete valid streams require no recovered semantic claims
     * (lib.rs). */
    fun formationStatus(): FormationStatus = FormationStatus.Complete

    /** Complete YAML formation publishes no recovery diagnostics (lib.rs). */
    fun diagnostics(): List<consema.protocol.Diagnostic> = emptyList()

    /** Exhaustive token/trivia byte coverage (lib.rs). */
    fun losslessStructuralIndex(): LosslessStructuralIndex = structuralIndex

    /** Format-specific kind for each structural piece in source order
     * (lib.rs). */
    fun losslessSyntaxKinds(): List<YamlSyntaxKind> = syntaxKinds

    /** Returns one independent YAML document by stream ordinal (lib.rs). */
    fun document(ordinal: Int): YamlDocument? =
        native.documents.getOrNull(ordinal)?.let { YamlDocument(this, ordinal, it) }

    /** Number of alias serialization occurrences; aliases are never
     * expanded (lib.rs). */
    fun aliasCount(): Int = native.aliases.size

    /** Returns one alias occurrence in serialization order (lib.rs). */
    fun alias(ordinal: Int): YamlAlias? =
        native.aliases.getOrNull(ordinal)?.let { YamlAlias(this, it) }

    /** Number of independent YAML documents in this stream (lib.rs). */
    fun documentCount(): Int = streamDocuments

    /** Resource contract used to form this stream (lib.rs). */
    fun parseLimits(): ParseLimits = parseLimits

    internal fun pieces(): List<consema.document.StructuralPiece> = structuralIndex.pieces()

    /** Validates one NodeRef against the allowed roles and resolves its
     * node index. */
    internal fun validateNodeRef(node: NodeRef, roles: List<NodeRole>): Int {
        try {
            authority.verify(node)
        } catch (e: consema.document.LocationException) {
            throw YamlAccessException(YamlAccessErrorKind.WrongSnapshot)
        }
        if (node.role !in roles) {
            throw YamlAccessException(YamlAccessErrorKind.WrongRole)
        }
        val index = node.index
        if (index < 0 || index >= native.nodes.size.toLong()) {
            throw YamlAccessException(YamlAccessErrorKind.UnknownNode)
        }
        return index.toInt()
    }
}

/** One independent document in a YAML stream (lib.rs). */
class YamlDocument internal constructor(
    internal val owner: Document,
    internal val ordinal: Int,
    internal val document: NativeDocument,
) {
    /** Zero-based stream ordinal (lib.rs). */
    fun ordinal(): Int = ordinal

    /** Snapshot-bound document identity (lib.rs). */
    fun nodeRef(): NodeRef =
        owner.authority.nodeRef(ordinal.toLong(), NodeRole.YamlDocument)

    /** Backend-validated raw document presentation span (lib.rs). */
    fun span(): Span = document.span

    /** Representation root; alias occurrences already share target identity
     * (lib.rs). */
    fun root(): YamlNode = YamlNode(owner, document.root)
}

/** Snapshot-bound YAML representation node (lib.rs). */
class YamlNode internal constructor(
    internal val owner: Document,
    internal val index: Int,
) {
    /** Process-local stable identity within this snapshot (lib.rs). */
    fun nodeRef(): NodeRef = nodeRef(owner.authority, index)

    /** Exact raw representation occurrence span (lib.rs). */
    fun span(): Span = owner.native.nodes[index].span

    /** Resolved tag identifier (lib.rs). */
    fun tag(): String = owner.native.nodes[index].tag

    /** Exact anchor name on the defining occurrence, if present (lib.rs). */
    fun anchor(): String? = owner.native.nodes[index].anchor

    /** Snapshot-bound anchor-definition identity, when this node defines
     * one (lib.rs). */
    fun anchorNodeRef(): NodeRef? =
        owner.native.nodes[index].anchor?.let {
            owner.authority.nodeRef(index.toLong(), NodeRole.YamlAnchorDefinition)
        }

    /** Exact raw `&name` span, when this node defines an anchor (lib.rs). */
    fun anchorSpan(): Span? = owner.native.nodes[index].anchorSpan

    /** Native node kind (lib.rs). */
    fun kind(): YamlNodeKind =
        when (val content = owner.native.nodes[index].content) {
            is NativeContent.Scalar -> YamlNodeKind.Scalar
            is NativeContent.Sequence -> YamlNodeKind.Sequence
            is NativeContent.Mapping -> YamlNodeKind.Mapping
        }

    /** Scalar facts, when this is a scalar node (lib.rs). */
    fun scalar(): YamlScalar? =
        (owner.native.nodes[index].content as? NativeContent.Scalar)?.scalar
            ?.let { YamlScalar(it) }

    /** Ordered sequence association count (lib.rs). */
    fun sequenceLen(): Int? =
        (owner.native.nodes[index].content as? NativeContent.Sequence)?.items?.size

    /** One exact sequence association (lib.rs). */
    fun sequenceItem(ordinal: Int): YamlSequenceItem? =
        (owner.native.nodes[index].content as? NativeContent.Sequence)?.items
            ?.getOrNull(ordinal)?.let { YamlSequenceItem(owner, it) }

    /** Ordered mapping association count (lib.rs). */
    fun mappingLen(): Int? =
        (owner.native.nodes[index].content as? NativeContent.Mapping)?.entries?.size

    /** One exact arbitrary key/value association (lib.rs). */
    fun mappingEntry(ordinal: Int): YamlMappingEntry? =
        (owner.native.nodes[index].content as? NativeContent.Mapping)?.entries
            ?.getOrNull(ordinal)?.let { YamlMappingEntry(owner, it) }
}

/** Native scalar facts with exact decoded and canonical content (lib.rs). */
class YamlScalar internal constructor(internal val scalar: NativeScalar) {
    /** Decoded YAML scalar content before schema canonicalization (lib.rs). */
    fun decoded(): String = scalar.decoded

    /** Profile-defined canonical scalar content (lib.rs). */
    fun canonical(): String = scalar.canonical

    /** Resolved scalar category (lib.rs). */
    fun kind(): YamlScalarKind = scalar.kind

    /** Source presentation style (lib.rs). */
    fun style(): YamlScalarStyle = scalar.style
}

/** One ordered sequence association (lib.rs). */
class YamlSequenceItem internal constructor(
    internal val owner: Document,
    internal val item: NativeSequenceItem,
) {
    /** Snapshot-bound association identity (lib.rs). */
    fun nodeRef(): NodeRef =
        owner.authority.nodeRef(item.identity, NodeRole.YamlSequenceElement)

    /** Exact raw element occurrence span, including an alias spelling when
     * used (lib.rs). */
    fun span(): Span = item.span

    /** Referenced representation node (lib.rs). */
    fun node(): YamlNode = YamlNode(owner, item.node)

    /** Alias occurrence that supplied this element edge, when present
     * (lib.rs). */
    fun alias(): YamlAlias? =
        item.alias?.let { YamlAlias(owner, owner.native.aliases[it]) }
}

/** One ordered YAML mapping association with an arbitrary key node (lib.rs). */
class YamlMappingEntry internal constructor(
    internal val owner: Document,
    internal val entry: NativeMappingEntry,
) {
    /** Snapshot-bound association identity (lib.rs). */
    fun nodeRef(): NodeRef =
        owner.authority.nodeRef(entry.identity, NodeRole.YamlMappingEntry)

    /** Raw span from the key occurrence through the value occurrence
     * (lib.rs). */
    fun span(): Span = entry.span

    /** Arbitrary key node (lib.rs). */
    fun key(): YamlNode = YamlNode(owner, entry.key)

    /** Value node (lib.rs). */
    fun value(): YamlNode = YamlNode(owner, entry.value)

    /** Alias occurrence that supplied the key edge, when present (lib.rs). */
    fun keyAlias(): YamlAlias? =
        entry.keyAlias?.let { YamlAlias(owner, owner.native.aliases[it]) }

    /** Alias occurrence that supplied the value edge, when present (lib.rs). */
    fun valueAlias(): YamlAlias? =
        entry.valueAlias?.let { YamlAlias(owner, owner.native.aliases[it]) }
}

/** One alias serialization occurrence pointing at an existing representation
 * node (lib.rs). */
class YamlAlias internal constructor(
    internal val owner: Document,
    internal val alias: NativeAlias,
) {
    /** Snapshot-bound occurrence identity (lib.rs). */
    fun nodeRef(): NodeRef =
        owner.authority.nodeRef(alias.identity, NodeRole.YamlAlias)

    /** Exact raw `*name` occurrence span (lib.rs). */
    fun span(): Span = alias.span

    /** Exact alias name without `*` (lib.rs). */
    fun name(): String = alias.name

    /** Shared target representation node; no expansion occurs (lib.rs). */
    fun target(): YamlNode = YamlNode(owner, alias.target)
}
