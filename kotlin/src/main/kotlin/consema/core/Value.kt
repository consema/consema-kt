// The closed fifteen-kind PortableValue model (Kotlin).
//
// Data authority (language-neutral sources first):
//   - RFC 0016 §4.1 (docs/rfcs/0016-go-api-mapping-v1.md:119-160): the
//     language-neutral PortableValue contract is the closed fifteen-kind
//     registry of 配置内容统一处理标准与 Rust 参考实现.md §10 and
//     crates/consema-core/src/value.rs (PortableValueKind): Null, Boolean,
//     Integer, Decimal, BinaryFloat32, BinaryFloat64, String, Bytes, Date,
//     Time, LocalDateTime, OffsetDateTime, Sequence, Object, EntryMapping.
//   - conformance/vectors/v1.json (pvce.null-vector, pvce.object-vector,
//     pvce.negative-integer-vector) pins the byte surface exercised here.
//   - Rust crates/consema-core/src/value.rs:622-652 pins the kind registry
//     order; go/core/value.go:39-381 is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation of any other language's code):
// the closed kind set is a sealed class hierarchy — exhaustive `when` over
// the subclasses can never meet an unknown kind (RFC 0016 §4.1: "no default
// that silently accepts unknown kinds"). Class names use the `Pv` prefix to
// avoid clashing with kotlin.String / kotlin.Boolean / kotlin.Array; the
// Kind enum entries use the exact language-neutral wire spellings, so
// `kind.name` IS the canonical kind name (the spellings the JSON transport
// emits, conformance/vectors + RFC 0015 §3.2).
//
// Value semantics: object keys are unique (the RFC 0002 object contract;
// duplicate keys are rejected at construction, mirroring the Rust
// ObjectBuilder uniqueness invariant); entry mappings allow arbitrary keys
// and duplicates (the Rust EntryMappingBuilder). Entry order is a
// language-neutral fact: strict equality, hashing, and the PVCE/1 codec all
// depend on it.

package consema.core

import java.math.BigInteger

/**
 * The closed fifteen PortableValue kinds.
 *
 * The entries use the exact language-neutral spellings (RFC 0016 §4.1;
 * crates/consema-core/src/value.rs:622-652). The numeric enum ordinals are
 * NOT wire semantics: PVCE/1 record tags are a separate registry (see
 * Pvce.kt).
 */
enum class Kind {
    Null,
    Boolean,
    Integer,
    Decimal,
    BinaryFloat32,
    BinaryFloat64,
    String,
    Bytes,
    Date,
    Time,
    LocalDateTime,
    OffsetDateTime,
    Sequence,
    Object,
    EntryMapping,
}

/**
 * The closed PortableValue interface: only the fifteen kinds in this package
 * implement it. Canonical equality and hashing are the top-level [equal] and
 * [hash] functions (RFC 0016 §4.1; go/core/equal.go as cross-reference), NOT
 * Kotlin `==`, because the canonical equality contract is kind identity plus
 * canonical content equality.
 */
sealed class PortableValue {
    /** The closed kind of the value. */
    abstract val kind: Kind
}

/** The singleton Null kind. All Null values are equal and encode to the
 * same PVCE/1 bytes (conformance/vectors/v1.json pvce.null-vector:
 * "50564345010000"). */
object PvNull : PortableValue() {
    override val kind: Kind get() = Kind.Null
}

/** A two-valued PortableValue Boolean. */
data class PvBoolean(val value: Boolean) : PortableValue() {
    override val kind: Kind get() = Kind.Boolean
}

/** A PortableValue string: an immutable Unicode scalar sequence (valid UTF-8
 * when encoded). */
data class PvString(val value: String) : PortableValue() {
    override val kind: Kind get() = Kind.String
}

/**
 * An exact IEEE-754 binary32 datum. The identity is the 32-bit pattern: NaN
 * payloads and the sign of zero are preserved exactly, and PVCE/1 encodes
 * the bits big-endian (conformance/vectors/v1.json value.float-signed-zero).
 */
data class PvBinaryFloat32(val bits: Int) : PortableValue() {
    override val kind: Kind get() = Kind.BinaryFloat32

    companion object {
        /** Wraps the exact bit pattern of [value] (floatToRawIntBits never
         * normalizes NaN). */
        fun fromFloat(value: Float): PvBinaryFloat32 =
            PvBinaryFloat32(java.lang.Float.floatToRawIntBits(value))
    }

    /** Converts back to a float32 without changing the bit pattern. */
    fun toFloat(): Float = java.lang.Float.intBitsToFloat(bits)
}

/**
 * An exact IEEE-754 binary64 datum. The identity is the 64-bit pattern: NaN
 * payloads and the sign of zero are preserved exactly, and PVCE/1 encodes
 * the bits big-endian.
 */
data class PvBinaryFloat64(val bits: Long) : PortableValue() {
    override val kind: Kind get() = Kind.BinaryFloat64

    companion object {
        /** Wraps the exact bit pattern of [value] (doubleToRawLongBits never
         * normalizes NaN). */
        fun fromFloat(value: Double): PvBinaryFloat64 =
            PvBinaryFloat64(java.lang.Double.doubleToRawLongBits(value))
    }

    /** Converts back to a float64 without changing the bit pattern. */
    fun toFloat(): Double = java.lang.Double.longBitsToDouble(bits)
}

/**
 * A PortableValue octet sequence. No UTF-8, base64, or hex interpretation is
 * ever implied; Bytes and String are always different kinds. Constructed
 * bytes are copied defensively.
 */
class PvBytes private constructor(private val content: ByteArray) : PortableValue() {
    override val kind: Kind get() = Kind.Bytes

    companion object {
        /** Wraps a copy of the octet sequence. */
        fun of(bytes: ByteArray): PvBytes = PvBytes(bytes.copyOf())
    }

    /** Returns a copy of the octet sequence. */
    fun content(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PvBytes && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()
}

/** One ordered object entry: a unique key and its value. */
data class Entry(val key: String, val value: PortableValue)

/**
 * An ordered unique-key object. Entry order is a language-neutral fact:
 * strict equality, hashing, and the PVCE/1 codec all depend on it.
 * Completed objects are logically immutable.
 */
class PvObject internal constructor(internal val entries: List<Entry>) : PortableValue() {
    override val kind: Kind get() = Kind.Object

    /** Reports the number of entries. */
    fun size(): Int = entries.size

    /** Returns the ordered entries. */
    fun entries(): List<Entry> = entries

    /** Returns the value stored under [key], if present. */
    fun get(key: String): PortableValue? = entries.firstOrNull { it.key == key }?.value
}

/**
 * Incrementally constructs an [PvObject], rejecting duplicate keys at
 * construction time (the RFC 0002 object contract).
 */
class ObjectBuilder {
    private val entries = ArrayList<Entry>()
    private val keys = HashSet<String>()

    /** Appends one entry, throwing [DuplicateKeyException] if [key] is
     * already present. */
    fun insert(key: String, value: PortableValue): ObjectBuilder {
        if (!keys.add(key)) {
            throw DuplicateKeyException(key)
        }
        entries.add(Entry(key, value))
        return this
    }

    /** Reports the number of entries inserted so far. */
    fun size(): Int = entries.size

    /** Returns the completed object. The builder must not be used after
     * build. */
    fun build(): PvObject = PvObject(entries.toList())
}

/** An ordered value sequence (RFC 0016 §4.1; "Sequence" on the wire). */
class PvArray internal constructor(internal val items: List<PortableValue>) : PortableValue() {
    override val kind: Kind get() = Kind.Sequence

    /** Reports the number of items. */
    fun size(): Int = items.size

    /** Returns the ordered items. */
    fun items(): List<PortableValue> = items

    /** Returns the item at [index]. */
    fun at(index: Int): PortableValue = items[index]
}

/**
 * One ordered entry-mapping association with arbitrary PortableValue key and
 * value. Duplicate associations and association order are value semantics.
 */
data class EntryMappingEntry(val key: PortableValue, val value: PortableValue)

/**
 * An ordered arbitrary-key mapping. Keys may be any PortableValue and may
 * repeat; entry order and duplicates are language-neutral facts that strict
 * equality, hashing, and the PVCE/1 codec all depend on.
 */
class PvEntryMapping internal constructor(internal val entries: List<EntryMappingEntry>) :
    PortableValue() {
    override val kind: Kind get() = Kind.EntryMapping

    /** Reports the number of associations. */
    fun size(): Int = entries.size

    /** Returns the ordered associations. */
    fun entries(): List<EntryMappingEntry> = entries
}

/**
 * Incrementally constructs an [PvEntryMapping]. Unlike the ObjectBuilder
 * there is no deduplication: arbitrary keys may repeat (the Rust
 * EntryMappingBuilder::push semantics, crates/consema-core/src/value.rs:
 * 973-978).
 */
class EntryMappingBuilder {
    private val entries = ArrayList<EntryMappingEntry>()

    /** Appends one association. */
    fun push(key: PortableValue, value: PortableValue): EntryMappingBuilder {
        entries.add(EntryMappingEntry(key, value))
        return this
    }

    /** Reports the number of associations inserted so far. */
    fun size(): Int = entries.size

    /** Returns the completed entry mapping. */
    fun build(): PvEntryMapping = PvEntryMapping(entries.toList())
}
