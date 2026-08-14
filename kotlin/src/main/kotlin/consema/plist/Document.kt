// The immutable plist-family document model: the representation-independent
// native value model, snapshot-bound handles, entity storage, and the binary
// structural facts.
//
// Data authority:
//   - RFC 0013 §6 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md): the
//     native value model is representation-independent and owned by the plist
//     family (PlistDocument/PlistValue/PlistDict/PlistDictEntry/PlistKey/
//     PlistArray/PlistString/PlistInteger/PlistReal/PlistBoolean/PlistDate/
//     PlistData/PlistUid); a dictionary preserves physical key/value
//     association order and duplicate occurrences; a string holds exact
//     UTF-16 code units with a WellFormedUnicode | UnpairedSurrogate
//     validation result; an integer is signed 64-bit; a real is an exact
//     IEEE 754 double with a Float32/Float64 width fact; a date is exact
//     double seconds since 2001-01-01T00:00:00Z; a UID is an unsigned 32-bit
//     value; shared object identity from the binary object table is
//     preserved (one source object referenced by several containers is one
//     native node with multiple owners).
//   - RFC 0013 §3 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md): formation is Complete or
//     Recovered; a Recovered Document retains the immutable source,
//     exhaustive piece coverage, ordered diagnostics, and every independently
//     proven construct; recovery never invents unproven native semantics.
//   - RFC 0013 §8.3 and §5 (binary structure facts: object table, offset
//     table, references, trailer); https://github.com/consema/consema-rs/blob/main/consema-plist/src/parser_binary.rs
// pins BinaryObjectFact/BinaryOffsetFact/BinaryObjectRefFact/
//     BinaryTrailerFacts and native.rs the arena document.
//   - https://github.com/consema/consema-rs/blob/main/consema-plist/src/native.rs (PlistString/PlistKey),
//     native.rs (PlistInteger/PlistReal/PlistBoolean/PlistDate/
//     PlistData/PlistUid) pins the value semantics; consema-go/go/plist is a
//     cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the JSON-family precedent
// (kotlin/src/main/kotlin/consema/json/Document.kt) of immutable handle classes carrying
// (document, entity index) is reused; the native value semantics are a
// sealed class so exhaustive `when` over them can never meet an unknown
// kind; entity storage is a private sealed hierarchy shared by both
// representations.

package consema.plist

import consema.document.BinaryStructuralIndex
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
import java.util.Arrays

/** Well-formedness status of one exact UTF-16 string (RFC 0013 §6;
 * native.rs). */
enum class PlistStringStatus {
    /** The code-unit sequence is well-formed Unicode. */
    WellFormedUnicode,

    /** The sequence contains an unpaired surrogate (binary sources only). */
    UnpairedSurrogate,
}

/**
 * Exact UTF-16 code units with a bounded validation result (RFC 0013 §6;
 * native.rs). Equality is code-unit equality; the unpaired-surrogate
 * status blocks ordinary Unicode projection and XML conversion (RFC 0013 §7,
 * §9).
 */
class PlistString private constructor(
    private val units: IntArray,
    val status: PlistStringStatus,
) {
    companion object {
        /** Builds the exact code-unit sequence of one Unicode string
         * (native.rs). */
        fun fromUnicode(value: String): PlistString =
            PlistString(encodeUtf16(value), PlistStringStatus.WellFormedUnicode)

        /** Adopts an exact code-unit sequence and validates its well-formed
         * status (native.rs). */
        fun fromCodeUnits(codeUnits: IntArray): PlistString {
            val units = codeUnits.copyOf()
            val status = if (hasUnpairedSurrogate(units)) {
                PlistStringStatus.UnpairedSurrogate
            } else {
                PlistStringStatus.WellFormedUnicode
            }
            return PlistString(units, status)
        }
    }

    /** Exact UTF-16 code units; returns a defensive copy. */
    fun codeUnits(): IntArray = units.copyOf()

    /** Exact UTF-16BE bytes (native.rs). */
    fun utf16beBytes(): ByteArray {
        val output = ByteArray(units.size * 2)
        for ((index, unit) in units.withIndex()) {
            output[index * 2] = (unit ushr 8).toByte()
            output[index * 2 + 1] = unit.toByte()
        }
        return output
    }

    /** Decoded Unicode text, or null when the sequence has an unpaired
     * surrogate (native.rs). */
    fun toUnicode(): String? {
        if (status == PlistStringStatus.UnpairedSurrogate) {
            return null
        }
        val builder = StringBuilder()
        for (unit in units) {
            builder.append(unit.toChar())
        }
        return builder.toString()
    }

    override fun equals(other: Any?): Boolean =
        other is PlistString && Arrays.equals(units, other.units)

    override fun hashCode(): Int = Arrays.hashCode(units)

    override fun toString(): String = "PlistString(units=${units.size})"
}

/**
 * A string key identity of one dictionary association (RFC 0013 §6;
 * native.rs). Equality is exact code-unit equality; `toUnicode`
 * returns null for an unpaired-surrogate key.
 */
class PlistKey private constructor(private val string: PlistString) {
    companion object {
        /** Builds a key from one Unicode string (native.rs). */
        fun fromUnicode(value: String): PlistKey = PlistKey(PlistString.fromUnicode(value))

        /** Adopts an exact code-unit sequence (native.rs). */
        fun fromCodeUnits(codeUnits: IntArray): PlistKey =
            PlistKey(PlistString.fromCodeUnits(codeUnits))
    }

    /** Exact key code units. */
    fun codeUnits(): IntArray = string.codeUnits()

    /** Decoded Unicode text, or null for an unpaired-surrogate key. */
    fun toUnicode(): String? = string.toUnicode()

    /** Whether the key content is well-formed Unicode. */
    val status: PlistStringStatus
        get() = string.status

    /** The underlying exact string content. */
    fun asString(): PlistString = string

    override fun equals(other: Any?): Boolean = other is PlistKey && string == other.string

    override fun hashCode(): Int = string.hashCode()

    override fun toString(): String = "PlistKey(${string.toUnicode() ?: "<unpaired>"})"
}

/** Signed 64-bit native integer (RFC 0013 §6; native.rs). */
data class PlistInteger(val value: Long)

/** Real width fact of one native real (RFC 0013 §5.5; native.rs). */
enum class RealWidth {
    /** 4-byte IEEE 754 single; the width fact survives parsing and
     * re-emission. */
    Float32,

    /** 8-byte IEEE 754 double. */
    Float64,
}

/**
 * Exact IEEE 754 real with its width fact (RFC 0013 §6; native.rs).
 * The identity is the exact bit pattern plus width; NaN payloads and the
 * sign of zero are preserved.
 */
data class PlistReal(
    /** Exact IEEE 754 bits: the 32-bit pattern in the low word for
     * [RealWidth.Float32], the 64-bit pattern otherwise. */
    val bits: Long,
    /** Width fact. */
    val width: RealWidth,
) {
    companion object {
        /** Exact 64-bit double (native.rs). */
        fun double(bits: Long): PlistReal = PlistReal(bits, RealWidth.Float64)

        /** Exact 32-bit single (native.rs). */
        fun single(bits: Int): PlistReal = PlistReal(bits.toLong() and 0xFFFF_FFFFL, RealWidth.Float32)
    }

    /** The exact double-converted value (native.rs). */
    fun asDouble(): Double =
        when (width) {
            RealWidth.Float64 -> java.lang.Double.longBitsToDouble(bits)
            RealWidth.Float32 -> java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
        }
}

/** Two-valued native boolean (native.rs). */
data class PlistBoolean(val value: Boolean)

/**
 * Exact double seconds since `2001-01-01T00:00:00Z` (RFC 0013 §6;
 * native.rs). A non-finite payload is rejected at construction.
 */
data class PlistDate(val seconds: Double) {
    companion object {
        /** Constructs a date, rejecting non-finite payloads
         * (native.rs). */
        fun fromSeconds(seconds: Double): PlistDate? =
            if (seconds.isFinite()) PlistDate(seconds) else null
    }
}

/** Exact data bytes (RFC 0013 §6; native.rs). */
class PlistData private constructor(private val content: ByteArray) {
    companion object {
        /** Wraps a copy of the bytes (native.rs). */
        fun fromBytes(bytes: ByteArray): PlistData = PlistData(bytes.copyOf())
    }

    /** Exact bytes; returns a defensive copy. */
    fun bytes(): ByteArray = content.copyOf()

    /** Byte length. */
    val len: Int
        get() = content.size

    override fun equals(other: Any?): Boolean =
        other is PlistData && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()
}

/** Unsigned 32-bit UID value (RFC 0013 §6; native.rs). */
data class PlistUid(val value: Int) {
    /** The value as an unsigned long. */
    fun toLong(): Long = value.toLong() and 0xFFFF_FFFFL
}

/** Closed native value kind; the spellings are the frozen query kind names
 * (RFC 0013 §8.1; the vector `value_types` / `kind` facts). */
enum class PlistValueKind {
    /** PlistString. */
    String,

    /** PlistInteger. */
    Integer,

    /** PlistReal. */
    Real,

    /** PlistBoolean. */
    Boolean,

    /** PlistDate. */
    Date,

    /** PlistData. */
    Data,

    /** PlistUid. */
    Uid,

    /** PlistArray. */
    Array,

    /** PlistDict. */
    Dict,
    ;

    /** The frozen language-neutral kind spelling used by
     * plist.value-type-is@1 and the query match records. */
    fun kindName(): String =
        when (this) {
            String -> "string"
            Integer -> "integer"
            Real -> "real"
            Boolean -> "boolean"
            Date -> "date"
            Data -> "data"
            Uid -> "uid"
            Array -> "array"
            Dict -> "dict"
        }

    companion object {
        /** Resolves one exact kind name, or null. */
        fun fromName(name: String): PlistValueKind? =
            entries.firstOrNull { it.kindName() == name }
    }
}

/**
 * Representation-independent native value of one entity. Containers hold
 * entity indices: a dict holds its dict-entry entities, an array its
 * element-value entities (RFC 0013 §6 shared identity: one entity index may
 * be referenced by several containers).
 */
internal sealed class NativeValue {
    abstract fun kind(): PlistValueKind

    data class StringV(val string: PlistString) : NativeValue() {
        override fun kind(): PlistValueKind = PlistValueKind.String
    }

    data class Integer(val value: Long) : NativeValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Integer
    }

    data class Real(val real: PlistReal) : NativeValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Real
    }

    data class BooleanV(val value: Boolean) : NativeValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Boolean
    }

    data class Date(val seconds: Double) : NativeValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Date
    }

    data class Data(val data: PlistData) : NativeValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Data
    }

    data class Uid(val uid: PlistUid) : NativeValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Uid
    }

    /** Ordered element-value entity indices (RFC 0013 §5.9). */
    data class Array(val elements: List<Int>) : NativeValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Array
    }

    /** Ordered dict-entry entity indices (RFC 0013 §4.4, §5.9). */
    data class Dict(val entries: List<Int>) : NativeValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Dict
    }
}

/** One value entity (RFC 0013 §6; the json-family ValueEntity precedent).
 * XML dict keys are value entities too; [isKey] keeps the post-order rank
 * of value-only entities aligned with the native arena ordinals
 * (materialization.rs). */
internal data class ValueEntity(
    val span: Span,
    /** Native value when proven; null for an invalid or unproven value. */
    val native: NativeValue?,
    /** Whether this entity is a dict key occurrence (XML profile). */
    val isKey: Boolean = false,
)

/** One dict-entry association entity: key value entity + value entity. */
internal data class DictEntryEntity(
    val span: Span,
    val keyIndex: Int,
    val valueIndex: Int,
    val ordinal: Int,
)

/** One array-element association entity. */
internal data class ArrayElementEntity(
    val span: Span,
    val valueIndex: Int,
    val ordinal: Int,
)

/** One structural entity. */
internal sealed class Entity {
    abstract val span: Span

    data class Value(val entity: ValueEntity) : Entity() {
        override val span: Span get() = entity.span
    }

    data class DictEntry(val entity: DictEntryEntity) : Entity() {
        override val span: Span get() = entity.span
    }

    data class ArrayElement(val entity: ArrayElementEntity) : Entity() {
        override val span: Span get() = entity.span
    }
}

// ---------------------------------------------------------------------------
// Binary structure facts (RFC 0013 §8.3; parser_binary.rs)
// ---------------------------------------------------------------------------

/** One proven object-table entry fact (parser_binary.rs). */
data class BinaryObjectFact(
    /** Object-table ordinal. */
    val index: Int,
    /** Marker byte offset (equals the offset-table entry value). */
    val offset: Int,
    /** Marker byte; the low nibble preserves non-minimal width facts. */
    val marker: Int,
    /** Exact marker-through-payload byte range. */
    val span: Span,
)

/** One validated offset-table entry fact (parser_binary.rs). */
data class BinaryOffsetFact(
    /** Object-table ordinal of this entry. */
    val index: Int,
    /** Decoded absolute file offset of the object's marker byte. */
    val offset: Int,
    /** Exact byte range of this entry inside the offset table. */
    val span: Span,
)

/** One decoded object reference of a proven container (parser_binary.rs
 *). For dictionaries keys occupy positions `0..count` and values
 * `count..2*count`. */
data class BinaryObjectRefFact(
    /** Referencing object index. */
    val owner: Int,
    /** Ordinal of this reference within the owner's reference block. */
    val position: Int,
    /** Decoded target object index. */
    val target: Int,
    /** Exact byte range of this reference inside the owner's payload. */
    val span: Span,
)

/** Trailer field facts (RFC 0013 §5.10; parser_binary.rs). The raw
 * field values are always recorded; validity is carried by formation
 * diagnostics and status. */
data class BinaryTrailerFacts(
    /** `sortVersion` byte (0 or 1; canonical materialization writes 0). */
    val sortVersion: Int,
    /** `offsetIntSize` byte. */
    val offsetIntSize: Int,
    /** `objectRefSize` byte. */
    val objectRefSize: Int,
    /** `numObjects` value. */
    val numObjects: Long,
    /** `topObject` value (the native document root when proven). */
    val topObject: Long,
    /** `offsetTableOffset` value. */
    val offsetTableOffset: Long,
    /** Exact trailer byte range. */
    val span: Span,
)

/** Complete binary structural facts of the proven prefix (RFC 0013 §8.3;
 * parser_binary.rs). */
data class BinaryFacts(
    /** Proven object facts in table order. */
    val objects: List<BinaryObjectFact>,
    /** Proven offset-table entries in entry order. */
    val offsets: List<BinaryOffsetFact>,
    /** Proven references in owner/position order. */
    val refs: List<BinaryObjectRefFact>,
    /** Trailer facts (always recorded). */
    val trailer: BinaryTrailerFacts,
)

// ---------------------------------------------------------------------------
// Handles
// ---------------------------------------------------------------------------

/**
 * Borrowed typed native semantic value bound to one Document snapshot
 * (RFC 0013 §6). Kotlin has no borrowed references, so the handle carries
 * its owning document and entity index; handles are immutable. Shared
 * identity is preserved: one entity index may be referenced by several
 * containers (RFC 0013 §6).
 */
class PlistValue internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    /** Exact value node handle. */
    fun nodeRef(): NodeRef = document.nodeRef(index.toLong(), NodeRole.PlistValue)

    /** Exact source span of the value element or binary object. */
    fun span(): Span = document.entity(index).span

    /** Native kind when proven. */
    fun kind(): PlistValueKind? = document.valueEntity(index).native?.kind()

    /** Whether more than one container references this node (RFC 0013 §6). */
    fun isShared(): Boolean = document.incomingRefCount(index) > 1

    /** Exact string content when the kind is String. */
    fun asString(): PlistString? =
        (document.valueEntity(index).native as? NativeValue.StringV)?.string

    /** Exact signed 64-bit integer when the kind is Integer. */
    fun asInteger(): Long? =
        (document.valueEntity(index).native as? NativeValue.Integer)?.value

    /** Exact real with width fact when the kind is Real. */
    fun asReal(): PlistReal? =
        (document.valueEntity(index).native as? NativeValue.Real)?.real

    /** Boolean value when the kind is Boolean. */
    fun asBoolean(): Boolean? =
        (document.valueEntity(index).native as? NativeValue.BooleanV)?.value

    /** Exact double seconds when the kind is Date. */
    fun asDateSeconds(): Double? =
        (document.valueEntity(index).native as? NativeValue.Date)?.seconds

    /** Exact bytes when the kind is Data. */
    fun asData(): PlistData? =
        (document.valueEntity(index).native as? NativeValue.Data)?.data

    /** Unsigned 32-bit UID when the kind is Uid. */
    fun asUid(): PlistUid? =
        (document.valueEntity(index).native as? NativeValue.Uid)?.uid

    /** Ordered array elements when the kind is Array. */
    fun arrayElements(): List<PlistArrayElement>? =
        (document.valueEntity(index).native as? NativeValue.Array)?.elements
            ?.map { PlistArrayElement(document, it) }

    /** Ordered dict entries without duplicate collapse when the kind is
     * Dict. */
    fun dictEntries(): List<PlistDictEntry>? =
        (document.valueEntity(index).native as? NativeValue.Dict)?.entries
            ?.map { PlistDictEntry(document, it) }

    internal fun rawIndex(): Int = index
}

/** Borrowed plist dictionary key/value association (RFC 0013 §6, §8.1). */
class PlistDictEntry internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): DictEntryEntity = document.dictEntryEntity(index)

    /** Zero-based structural association ordinal. */
    fun ordinal(): Int = entity().ordinal

    /** Association identity. */
    fun nodeRef(): NodeRef = document.nodeRef(index.toLong(), NodeRole.PlistDictEntry)

    /** Key identity. */
    fun keyNodeRef(): NodeRef = document.nodeRef(entity().keyIndex.toLong(), NodeRole.PlistKey)

    /** Value identity. */
    fun valueNodeRef(): NodeRef = document.nodeRef(entity().valueIndex.toLong(), NodeRole.PlistValue)

    /** Whole association source span (key element through value element). */
    fun span(): Span = entity().span

    /** Exact key content. */
    fun key(): PlistKey? =
        (document.valueEntity(entity().keyIndex).native as? NativeValue.StringV)?.string
            ?.let { PlistKey.fromCodeUnits(it.codeUnits()) }

    /** Associated value. */
    fun value(): PlistValue = PlistValue(document, entity().valueIndex)
}

/** Borrowed plist array element association (RFC 0013 §6, §8.1). */
class PlistArrayElement internal constructor(
    internal val document: Document,
    internal val index: Int,
) {
    private fun entity(): ArrayElementEntity = document.arrayElementEntity(index)

    /** Zero-based element ordinal. */
    fun ordinal(): Int = entity().ordinal

    /** Association identity. */
    fun nodeRef(): NodeRef = document.nodeRef(index.toLong(), NodeRole.PlistArrayElement)

    /** Value identity. */
    fun valueNodeRef(): NodeRef = document.nodeRef(entity().valueIndex.toLong(), NodeRole.PlistValue)

    /** Whole element span (the value element span in XML; the reference
     * span in binary). */
    fun span(): Span = entity().span

    /** Element value. */
    fun value(): PlistValue = PlistValue(document, entity().valueIndex)
}

/**
 * Opaque immutable plist document snapshot (RFC 0013 §3). Parsing happens in
 * Parser.kt and BinaryParser.kt; this file pins the read surface and the
 * module-internal entity access shared by query, projection, materialization,
 * and edit.
 */
class Document internal constructor(
    internal val authority: DocumentAuthority,
    internal val source: SourceSnapshot,
    internal val profile: PlistProfile,
    private val losslessIndex: LosslessStructuralIndex?,
    private val binaryIndex: BinaryStructuralIndex?,
    internal val formationStatus: FormationStatus,
    internal val diagnosticsList: List<PlistDiagnostic>,
    internal val entities: List<Entity>,
    internal val rootIndex: Int,
    internal val nativeRoot: NativeValue?,
    internal val syntaxKinds: List<PlistSyntaxKind>?,
    internal val binaryFacts: BinaryFacts?,
    internal val parseLimits: PlistParseLimits,
) {
    /** Snapshot identity to which every NodeRef and Span belongs. */
    val snapshotIdentity: SnapshotIdentity
        get() = authority.identity

    /** Exact immutable source. */
    fun source(): SourceSnapshot = source

    /** Default rendering is the exact current source bytes. */
    fun render(): ByteArray = source.bytes()

    /** Plist format family contract. */
    fun formatFamily(): FormatFamilyId = FormatFamilyId("plist", 1)

    /** Exact language profile. */
    fun profileId(): ProfileId = profile.id()

    /** Whether recovery structure was required. */
    fun formationStatus(): FormationStatus = formationStatus

    /** Deterministically ordered document diagnostics (family record; the
     * externalized `core.diagnostic@1` form requires a caller-stable source
     * ID and an error-code registry, Errors.kt). */
    fun diagnostics(): List<PlistDiagnostic> = diagnosticsList

    /** Exhaustive token/trivia/error-region byte coverage (XML profile), or
     * null for the binary profile (hard gate 1: a binary Document has no
     * token fiction). */
    fun losslessStructuralIndex(): LosslessStructuralIndex? = losslessIndex

    /** Exhaustive format-owned region coverage (binary profile), or null for
     * the XML profile. */
    fun binaryStructuralIndex(): BinaryStructuralIndex? = binaryIndex

    /** Lossless syntax kinds in the same source order (XML profile only). */
    fun losslessSyntaxKinds(): List<PlistSyntaxKind>? = syntaxKinds

    /** Binary structural facts (binary profile only). */
    fun binaryFacts(): BinaryFacts? = binaryFacts

    /** Whether a provable native value graph exists (RFC 0013 §3). */
    fun hasNativeValue(): Boolean = nativeRoot != null

    /** Root native value; throws [PlistAccessException] when no provable
     * native root exists. */
    fun root(): PlistValue {
        if (nativeRoot == null) {
            throw PlistAccessException(PlistAccessErrorKind.UnknownNode)
        }
        return PlistValue(this, rootIndex)
    }

    /** Incoming container-reference count of one value entity (shared
     * identity, RFC 0013 §6). Container natives hold association ENTITY
     * indices, so the reference targets resolve through the entry/element
     * entities. */
    internal fun incomingRefCount(index: Int): Int {
        var count = 0
        for (entity in entities) {
            when (entity) {
                is Entity.Value -> when (val native = entity.entity.native) {
                    is NativeValue.Array -> for (elementIndex in native.elements) {
                        if (arrayElementEntity(elementIndex).valueIndex == index) count++
                    }
                    is NativeValue.Dict -> {
                        for (entryIndex in native.entries) {
                            val entry = dictEntryEntity(entryIndex)
                            if (entry.keyIndex == index || entry.valueIndex == index) count++
                        }
                    }
                    else -> {}
                }
                else -> {}
            }
        }
        return count
    }

    internal fun entity(index: Int): Entity = entities[index]

    internal fun valueEntity(index: Int): ValueEntity =
        when (val entity = entities[index]) {
            is Entity.Value -> entity.entity
            else -> error("typed value handle required a value entity")
        }

    internal fun dictEntryEntity(index: Int): DictEntryEntity =
        when (val entity = entities[index]) {
            is Entity.DictEntry -> entity.entity
            else -> error("typed entry handle required an entry entity")
        }

    internal fun arrayElementEntity(index: Int): ArrayElementEntity =
        when (val entity = entities[index]) {
            is Entity.ArrayElement -> entity.entity
            else -> error("typed element handle required an element entity")
        }

    internal fun nodeRef(index: Long, role: NodeRole): NodeRef =
        authority.nodeRef(index, role)

    /**
     * Validates one NodeRef against the allowed roles and resolves its
     * entity index. Throws [PlistAccessException]: WrongSnapshot, WrongRole,
     * or UnknownNode.
     */
    internal fun validateRef(node: NodeRef, roles: List<NodeRole>): Int {
        try {
            authority.verify(node)
        } catch (e: consema.document.LocationException) {
            throw PlistAccessException(PlistAccessErrorKind.WrongSnapshot)
        }
        if (node.role !in roles) {
            throw PlistAccessException(PlistAccessErrorKind.WrongRole)
        }
        val index = node.index
        if (index < 0 || index >= entities.size.toLong()) {
            throw PlistAccessException(PlistAccessErrorKind.UnknownNode)
        }
        return index.toInt()
    }
}

/** UTF-16 code units of one Unicode string (RFC 0013 §6; a Kotlin String
 * is UTF-16 storage, so code units are the exact string units). */
internal fun encodeUtf16(value: String): IntArray {
    val units = IntArray(value.length)
    for (index in value.indices) {
        units[index] = value[index].code
    }
    return units
}

/** Whether one code-unit sequence contains an unpaired surrogate
 * (native.rs). */
internal fun hasUnpairedSurrogate(units: IntArray): Boolean {
    var index = 0
    while (index < units.size) {
        val unit = units[index]
        if (unit in 0xD800..0xDBFF) {
            if (index + 1 >= units.size || units[index + 1] !in 0xDC00..0xDFFF) {
                return true
            }
            index += 2
        } else if (unit in 0xDC00..0xDFFF) {
            return true
        } else {
            index += 1
        }
    }
    return false
}
