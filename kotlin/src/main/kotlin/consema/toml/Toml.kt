// The immutable TOML document and its native handles.
//
// Data authority:
//   - RFC 0001 §1-§2 (https://github.com/consema/consema/blob/main/docs/rfcs/0001-toml-1.0-profile.md): TOML 1.0 forms
//     only complete valid documents; the public identities are TomlItem /
//     TomlEntry / TomlKey / TomlArrayElement; tables, inline tables, arrays,
//     and arrays-of-tables are distinct native categories; the root is
//     always `RootTable`; default render is byte-for-byte the source.
//   - RFC 0001 §3: the frozen formation order (max_source_bytes, UTF-8
//     validation, TOML syntax, token/node/depth limits) and the frozen
//     failure codes (core.parse.resource-limit@1, toml.parse.syntax@1).
//   - https://github.com/consema/consema-rs/blob/main/consema-toml/src/lib.rs (TomlProfile,
//     TomlProfile::id, parse, Document shape and accessors,
//     TomlItemKind, TomlDate/TomlTime/TomlOffset/TomlDateTime,
//     TomlItem/TomlEntry/TomlArrayElement handles, entity internals and
//     public_kind).
//   - The toml.* error codes are cited in Errors.kt.
//   - consema-go/go/toml is a cross-reference only.
//
// Kotlin-idiomatic design: [TomlDocument] is an immutable value; the handle
// types [TomlItem]/[TomlEntry]/[TomlArrayElement] hold the document
// reference and an entity ordinal exactly like the Rust borrowed handles,
// and every accessor resolves through the snapshot-bound [NodeRef].

package consema.toml

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
import consema.protocol.Diagnostic
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion

/** Frozen TOML language profile (lib.rs). */
enum class TomlProfile {
    /** TOML 1.0.0 without implementation extensions. */
    TOML_1_0_V1,
    ;

    /** Immutable profile identifier (lib.rs). */
    fun id(): ProfileId = ProfileId("toml.1.0", 1)
}

/**
 * Parses one complete immutable TOML 1.0 document snapshot
 * (lib.rs). The frozen formation order (RFC 0001 §3) is:
 * max_source_bytes, UTF-8 validation, TOML 1.0 syntax/semantics, then
 * token/node/depth limits. Any failure throws [TomlFormationException]
 * carrying the ordered diagnostics; no partial Document exists.
 */
fun parse(
    source: ByteArray,
    profile: TomlProfile,
    limits: ParseLimits,
): TomlDocument {
    if (source.size > limits.maxSourceBytes) {
        throw TomlFormationException(
            listOf(
                resourceLimitDiagnostic("source_bytes", source.size, limits.maxSourceBytes),
            ),
        )
    }
    val snapshot = try {
        SourceSnapshot.fromUtf8(source)
    } catch (e: consema.document.SourceException) {
        // fromUtf8 maps every invalid sequence to INVALID_UTF8 carrying the
        // valid prefix (Source.kt); the Rust parser converts the
        // same failure through FatalFormationFailure::invalid_utf8
        // (lib.rs).
        throw TomlFormationException(listOf(invalidUtf8Diagnostic(e.validUpTo ?: 0)))
    }
    val text = snapshot.decodedText() ?: error("TOML parser constructs a UTF-8 source")
    val authority = DocumentAuthority.fresh()

    // Tokenizer (max_token_count), delimiter nesting preflight
    // (max_nesting_depth), and the lossless coverage index.
    val lexed = tokenize(text, authority, snapshot.len, limits)

    // TOML 1.0 syntax and semantic validation.
    val tree = try {
        parseTree(text.toByteArray(Charsets.UTF_8))
    } catch (e: ParseError) {
        throw TomlFormationException(listOf(syntaxDiagnostic(e.startByte, e.endByte, e.reason)))
    }

    // Entity building (max_node_count, max_nesting_depth).
    val builder = EntityBuilder(authority, snapshot.len, limits)
    val root = try {
        builder.buildTable(tree, root = true, depth = 0, fallback = SpanRange(0, snapshot.len))
    } catch (e: ParseError) {
        throw TomlFormationException(listOf(syntaxDiagnostic(e.startByte, e.endByte, e.reason)))
    }
    return TomlDocument(
        authority = authority,
        source = snapshot,
        profile = profile,
        structuralIndex = lexed.structuralIndex,
        syntaxKinds = lexed.kinds,
        diagnostics = emptyList(),
        entities = builder.build(),
        rootIndex = root,
        parseLimits = limits,
    )
}

/** Stable TOML native handle failure (lib.rs). */
enum class TomlAccessError {
    /** Handle belongs to another immutable snapshot. */
    WRONG_SNAPSHOT,

    /** Handle role does not match the requested native entity. */
    WRONG_ROLE,

    /** Handle index is not present in this document. */
    UNKNOWN_NODE,
}

/** The typed native handle failure. The stable [kind] is the
 * language-neutral comparison fact. */
class TomlAccessException(val kind: TomlAccessError) :
    Exception("toml: ${kind.name}")

/** Native TOML item category (lib.rs). */
enum class TomlItemKind {
    /** Decoded TOML string. */
    String,

    /** Signed 64-bit TOML integer. */
    Integer,

    /** IEEE-754 binary64 TOML float. */
    Float,

    /** Boolean. */
    Boolean,

    /** Date-time with a fixed offset. */
    OffsetDateTime,

    /** Date-time without an offset. */
    LocalDateTime,

    /** Date without time or offset. */
    LocalDate,

    /** Time without date or offset. */
    LocalTime,

    /** Inline value array. */
    Array,

    /** Inline table value. */
    InlineTable,

    /** Document root table. */
    RootTable,

    /** Explicit standard table. */
    StandardTable,

    /** Logical table created by a table path. */
    ImplicitTable,

    /** Logical table created by dotted-key syntax. */
    DottedTable,

    /** Ordered array of explicit tables. */
    ArrayOfTables,
}

/** Parsed TOML date fields (lib.rs). */
data class TomlDate(
    /** Four-digit year. */
    val year: Int,
    /** Month in `1..=12`. */
    val month: Int,
    /** Day in the selected month. */
    val day: Int,
)

/** Parsed TOML time fields (lib.rs). */
data class TomlTime(
    /** Hour in `0..=23`. */
    val hour: Int,
    /** Minute in `0..=59`. */
    val minute: Int,
    /** Parsed second. */
    val second: Int,
    /** Fractional second truncated to nanoseconds by the profile backend. */
    val nanosecond: Long,
)

/** Parsed TOML UTC offset (lib.rs). */
sealed class TomlOffset {
    /** Literal UTC `Z`. */
    data object Z : TomlOffset()

    /** Signed offset in minutes. */
    data class CustomMinutes(val minutes: Int) : TomlOffset()
}

/** Complete native TOML date/time datum (lib.rs). */
data class TomlDateTime(
    /** Optional date component. */
    val date: TomlDate?,
    /** Optional time component. */
    val time: TomlTime?,
    /** Optional UTC offset. */
    val offset: TomlOffset?,
)

/**
 * Opaque immutable TOML document snapshot (lib.rs). TOML forms
 * only Complete documents (RFC 0001 §1; lib.rs), so
 * [formationStatus] is always [FormationStatus.Complete] and the ordered
 * [diagnostics] are always empty.
 */
class TomlDocument internal constructor(
    internal val authority: DocumentAuthority,
    internal val source: SourceSnapshot,
    internal val profile: TomlProfile,
    internal val structuralIndex: LosslessStructuralIndex,
    internal val syntaxKinds: List<TomlSyntaxKind>,
    internal val diagnostics: List<Diagnostic>,
    internal val entities: List<Entity>,
    internal val rootIndex: Int,
    internal val parseLimits: ParseLimits,
) {
    /** Snapshot identity to which every native handle and span belongs
     * (lib.rs). */
    val snapshotIdentity: SnapshotIdentity
        get() = authority.identity

    /** Exact immutable UTF-8 source (lib.rs). */
    fun source(): SourceSnapshot = source

    /** Default rendering is byte-for-byte identical to the source
     * (lib.rs). */
    fun render(): ByteArray = source.bytes()

    /** TOML format family contract (lib.rs). */
    fun formatFamily(): FormatFamilyId = FormatFamilyId("toml", 1)

    /** Exact language profile (lib.rs). */
    fun profile(): ProfileId = profile.id()

    /** TOML 0.2 forms only complete valid documents (lib.rs). */
    fun formationStatus(): FormationStatus = FormationStatus.Complete

    /** Deterministically ordered non-fatal diagnostics (lib.rs);
     * always empty for TOML Complete documents. */
    fun diagnostics(): List<Diagnostic> = diagnostics

    /** Exhaustive token/trivia byte coverage (lib.rs). */
    fun losslessStructuralIndex(): LosslessStructuralIndex = structuralIndex

    /** Format-specific kind for every structural piece, in the same source
     * order (lib.rs). */
    fun losslessSyntaxKinds(): List<TomlSyntaxKind> = syntaxKinds

    /** Resource contract used to form this snapshot and any edit successor
     * (lib.rs). */
    fun parseLimits(): ParseLimits = parseLimits

    /** Root native item, which is always `RootTable` (lib.rs). */
    fun root(): TomlItem = TomlItem(this, rootIndex)

    /** Resolves a snapshot-bound TOML item handle (lib.rs). Throws
     * [TomlAccessException] for a foreign snapshot, a non-item role, or an
     * unknown index. */
    fun item(node: NodeRef): TomlItem {
        val index = validateRef(node, NodeRole.TomlItem)
        if (entities[index].kind !is EntityKind.Item) {
            throw TomlAccessException(TomlAccessError.WRONG_ROLE)
        }
        return TomlItem(this, index)
    }

    internal fun entity(index: Int): Entity = entities[index]

    internal fun itemEntity(index: Int): ItemEntity =
        (entities[index].kind as? EntityKind.Item)?.item
            ?: error("typed TOML item handle")

    internal fun nodeRef(index: Int, role: NodeRole): NodeRef =
        authority.nodeRef(index.toLong(), role)

    internal fun validateRef(node: NodeRef, role: NodeRole): Int {
        if (node.snapshot != authority.identity) {
            throw TomlAccessException(TomlAccessError.WRONG_SNAPSHOT)
        }
        if (node.role != role) {
            throw TomlAccessException(TomlAccessError.WRONG_ROLE)
        }
        val index = node.index.toInt()
        if (index < 0 || index >= entities.size) {
            throw TomlAccessException(TomlAccessError.UNKNOWN_NODE)
        }
        return index
    }

    /** Converts the ordered family diagnostics to transferable
     * `core.diagnostic@1` records under one caller-supplied source identity
     * and the current error registry. */
    fun formationDiagnosticsToProtocol(
        diagnostics: List<TomlDiagnostic>,
        sourceId: String,
        registry: ErrorCodeRegistry = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7),
    ): List<Diagnostic> = diagnostics.map { it.toProtocolDiagnostic(sourceId, registry) }
}

/** Borrowed native TOML item bound to one document snapshot
 * (lib.rs). */
class TomlItem internal constructor(
    internal val document: TomlDocument,
    internal val index: Int,
) {
    /** Exact item identity (lib.rs). */
    val nodeRef: NodeRef
        get() = document.nodeRef(index, NodeRole.TomlItem)

    /** Exact or contract-authorized logical source span (lib.rs). */
    val span: Span
        get() = document.entity(index).span

    /** Native item category (lib.rs). */
    val kind: TomlItemKind
        get() = document.itemEntity(index).kind.publicKind()

    /** Decoded string when this item is a string (lib.rs). */
    fun asString(): String? = (document.itemEntity(index).kind as? InternalItemKind.String)?.value

    /** Signed integer when this item is an integer (lib.rs). */
    fun asInteger(): Long? = (document.itemEntity(index).kind as? InternalItemKind.Integer)?.value

    /** Exact IEEE-754 datum when this item is a float (lib.rs). */
    fun asFloat(): Long? = (document.itemEntity(index).kind as? InternalItemKind.Float)?.bits

    /** Boolean when this item is a boolean (lib.rs). */
    fun asBoolean(): Boolean? =
        (document.itemEntity(index).kind as? InternalItemKind.Boolean)?.value

    /** Native temporal datum when this item is any TOML date/time category
     * (lib.rs). */
    fun asDateTime(): TomlDateTime? =
        (document.itemEntity(index).kind as? InternalItemKind.DateTime)?.value

    /** Direct ordered entries for any table category or inline table
     * (lib.rs). */
    fun tableEntries(): List<TomlEntry>? {
        val kind = document.itemEntity(index).kind
        val entries = when (kind) {
            is InternalItemKind.Table -> kind.entries
            is InternalItemKind.InlineTable -> kind.entries
            else -> return null
        }
        return entries.map { TomlEntry(document, it) }
    }

    /** Direct ordered elements for arrays and arrays-of-tables
     * (lib.rs). */
    fun arrayElements(): List<TomlArrayElement>? {
        val kind = document.itemEntity(index).kind
        val elements = when (kind) {
            is InternalItemKind.Array -> kind.elements
            is InternalItemKind.ArrayOfTables -> kind.elements
            else -> return null
        }
        return elements.map { TomlArrayElement(document, it) }
    }

    override fun toString(): String = "TomlItem(kind=$kind, index=$index)"
}

/** Borrowed direct table entry association (lib.rs). */
class TomlEntry internal constructor(
    internal val document: TomlDocument,
    internal val index: Int,
) {
    private fun entity(): EntryEntity =
        (document.entity(index).kind as? EntityKind.Entry)?.entry
            ?: error("typed TOML entry handle")

    /** Zero-based direct entry ordinal (lib.rs). */
    val ordinal: Int
        get() = entity().ordinal

    /** Association identity (lib.rs). */
    val nodeRef: NodeRef
        get() = document.nodeRef(index, NodeRole.TomlEntry)

    /** Direct key segment identity (lib.rs). */
    val keyNodeRef: NodeRef
        get() = document.nodeRef(entity().key, NodeRole.TomlKey)

    /** Associated item identity (lib.rs). */
    val itemNodeRef: NodeRef
        get() = document.nodeRef(entity().item, NodeRole.TomlItem)

    /** Association source span (lib.rs). */
    val span: Span
        get() = document.entity(index).span

    /** Exact source span of the direct key segment (the Rust
     * document.entity(entry.key).span used by materialization provenance
     * and key renames). */
    internal val keySpan: Span
        get() = document.entity(entity().key).span

    /** Decoded direct key segment without normalization (lib.rs). */
    fun name(): String {
        val key = document.entity(entity().key).kind as? EntityKind.Key
            ?: error("typed TOML key handle")
        return key.key.name
    }

    /** Associated native item (lib.rs). */
    fun item(): TomlItem = TomlItem(document, entity().item)
}

/** Borrowed array or array-of-tables element association (lib.rs). */
class TomlArrayElement internal constructor(
    internal val document: TomlDocument,
    internal val index: Int,
) {
    private fun entity(): ElementEntity =
        (document.entity(index).kind as? EntityKind.Element)?.element
            ?: error("typed TOML element handle")

    /** Zero-based direct element ordinal (lib.rs). */
    val ordinal: Int
        get() = entity().ordinal

    /** Association identity (lib.rs). */
    val nodeRef: NodeRef
        get() = document.nodeRef(index, NodeRole.TomlArrayElement)

    /** Associated item identity (lib.rs). */
    val itemNodeRef: NodeRef
        get() = document.nodeRef(entity().item, NodeRole.TomlItem)

    /** Association source span (lib.rs). */
    val span: Span
        get() = document.entity(index).span

    /** Associated native item (lib.rs). */
    fun item(): TomlItem = TomlItem(document, entity().item)
}

/** Maps one internal item kind to its public category (lib.rs). */
internal fun InternalItemKind.publicKind(): TomlItemKind = when (this) {
    is InternalItemKind.String -> TomlItemKind.String
    is InternalItemKind.Integer -> TomlItemKind.Integer
    is InternalItemKind.Float -> TomlItemKind.Float
    is InternalItemKind.Boolean -> TomlItemKind.Boolean
    is InternalItemKind.DateTime -> when {
        value.date != null && value.time != null && value.offset != null ->
            TomlItemKind.OffsetDateTime
        value.date != null && value.time != null -> TomlItemKind.LocalDateTime
        value.date != null -> TomlItemKind.LocalDate
        value.time != null -> TomlItemKind.LocalTime
        else -> error("TOML parser returns one defined datetime shape")
    }
    is InternalItemKind.Array -> TomlItemKind.Array
    is InternalItemKind.InlineTable -> TomlItemKind.InlineTable
    is InternalItemKind.Table -> when (flavor) {
        TableFlavor.ROOT -> TomlItemKind.RootTable
        TableFlavor.STANDARD -> TomlItemKind.StandardTable
        TableFlavor.IMPLICIT -> TomlItemKind.ImplicitTable
        TableFlavor.DOTTED -> TomlItemKind.DottedTable
    }
    is InternalItemKind.ArrayOfTables -> TomlItemKind.ArrayOfTables
}
