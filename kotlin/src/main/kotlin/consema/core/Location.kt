// Portable value and association locations.
//
// Data authority (language-neutral sources first):
//   - https://github.com/consema/consema-rs/blob/main/consema-core/src/location.rs is the ONLY authority for the
//     location model: ValuePathSegment (location.rs), ValuePath
//     (location.rs), AssociationRole (location.rs),
//     AssociationLocation (location.rs). consema-go/go/core has no equivalent;
//     the shapes below are not invented elsewhere. The dependency is
//     consumed by the json/toml/properties/ini/yaml families
//     (kotlin/src/main/kotlin/consema/json/Projection.kt imports, Projection.kt
//     call sites).
//   - The Rust u64 payloads (location.rs) map to Kotlin Long,
//     the mapping already exercised by the family call sites
//     (kotlin/src/main/kotlin/consema/json/Projection.kt pass ordinal.toLong();
//     kotlin/src/main/kotlin/consema/toml/Projection.kt pass ordinal.toLong();
//     kotlin/src/main/kotlin/consema/properties/Projection.kt).
//
// Kotlin-idiomatic design (NOT a translation of any other language's code):
// the closed segment set is a sealed class hierarchy, so exhaustive `when`
// over the subclasses can never meet an unknown segment (the same closed-set
// principle as the PortableValue kinds in Value.kt); the role set is a
// closed enum with the exact Rust spellings. Paths are immutable:
// child() builds a new path by copy and never modifies this path, matching
// the Rust contract (location.rs). location.rs defines no
// construction-time invariants, so no validation is invented here.

package consema.core

/**
 * One segment of a root-relative portable value path.
 *
 * The closed variant set mirrors the Rust enum exactly
 * (https://github.com/consema/consema-rs/blob/main/consema-core/src/location.rs): [ObjectValue] — value of a
 * uniquely named object entry (location.rs); [SequenceElement] —
 * sequence element at a non-negative index (location.rs);
 * [EntryKey] — key value of an entry-mapping association (location.rs);
 * [EntryValue] — value of an entry-mapping association (location.rs).
 * The Rust u64 payloads map to Kotlin Long (see the family call sites
 * passing `.toLong()`, e.g. kotlin/src/main/kotlin/consema/json/Projection.kt).
 */
sealed class ValuePathSegment {
    /** Value of a uniquely named object entry (location.rs). */
    data class ObjectValue(val name: String) : ValuePathSegment()

    /** Sequence element at a non-negative index (location.rs). */
    data class SequenceElement(val index: Long) : ValuePathSegment()

    /** Key value of an entry-mapping association (location.rs). */
    data class EntryKey(val ordinal: Long) : ValuePathSegment()

    /** Value of an entry-mapping association (location.rs). */
    data class EntryValue(val ordinal: Long) : ValuePathSegment()
}

/**
 * A path to a value; the empty path denotes the root
 * (https://github.com/consema/consema-rs/blob/main/consema-core/src/location.rs).
 *
 * Immutable: [child] returns a new path and never modifies this path
 * (location.rs). Equality and hashing are structural over the segment
 * sequence in order (the Rust PartialEq/Hash are derived on the backing
 * Vec, location.rs), so two paths with the same segments in the same
 * order are equal and hash alike. Construction is private to mirror the
 * Rust tuple struct with a private field (location.rs): paths are
 * created only via [root] and [child]; [kotlin.ConsistentCopyVisibility]
 * keeps the generated [copy] as private as the constructor.
 */
@ConsistentCopyVisibility
data class ValuePath private constructor(val segments: List<ValuePathSegment>) {
    /** Returns the path segments (location.rs). */
    fun segments(): List<ValuePathSegment> = segments

    /**
     * Creates a child path without modifying this path (location.rs):
     * returns a new path whose segment sequence is this path's sequence
     * followed by [segment].
     */
    fun child(segment: ValuePathSegment): ValuePath = ValuePath(segments + segment)

    companion object {
        /** Root path (location.rs). */
        fun root(): ValuePath = ValuePath(emptyList())
    }
}

/**
 * Association kind independent from child values
 * (https://github.com/consema/consema-rs/blob/main/consema-core/src/location.rs). The entries use the exact
 * Rust spellings (location.rs).
 */
enum class AssociationRole {
    /** Whole object entry (location.rs). */
    ObjectEntry,

    /** The name role of an object entry (location.rs). */
    ObjectKey,

    /** Whole entry-mapping association (location.rs). */
    EntryMappingEntry,
}

/**
 * Location of an association, not a portable value node
 * (https://github.com/consema/consema-rs/blob/main/consema-core/src/location.rs).
 *
 * [ordinal] is the structural association ordinal (location.rs); the
 * Rust u64 maps to Kotlin Long. location.rs defines no construction-time
 * invariants: `new` (location.rs) is total — there is no
 * role/container-consistency rule or ordinal validation to enforce, and
 * none is invented here. Equality and hashing are field-wise (the Rust
 * PartialEq/Hash are derived, location.rs).
 */
data class AssociationLocation(
    /** Path of the containing value (location.rs). */
    val container: ValuePath,
    /** Structural association ordinal (location.rs). */
    val ordinal: Long,
    /** Association role (location.rs). */
    val role: AssociationRole,
)
