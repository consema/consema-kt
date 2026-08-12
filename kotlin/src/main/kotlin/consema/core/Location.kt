// Portable value and association locations.
//
// Data authority (language-neutral sources first):
//   - crates/consema-core/src/location.rs:1-89 is the ONLY authority for the
//     location model: ValuePathSegment (location.rs:5-14), ValuePath
//     (location.rs:17-40), AssociationRole (location.rs:44-51),
//     AssociationLocation (location.rs:54-89). go/core has no equivalent;
//     the shapes below are not invented elsewhere. The dependency is
//     consumed by the json/toml/properties/ini/yaml families
//     (consema/json/Projection.kt:33-45 imports, Projection.kt:525-648
//     call sites).
//   - The Rust u64 payloads (location.rs:9, 11, 13, 57) map to Kotlin Long,
//     the mapping already exercised by the family call sites
//     (consema/json/Projection.kt:582-587 pass ordinal.toLong();
//     consema/toml/Projection.kt:331-334 pass ordinal.toLong();
//     consema/properties/Projection.kt:338-343).
//
// Kotlin-idiomatic design (NOT a translation of any other language's code):
// the closed segment set is a sealed class hierarchy, so exhaustive `when`
// over the subclasses can never meet an unknown segment (the same closed-set
// principle as the PortableValue kinds in Value.kt); the role set is a
// closed enum with the exact Rust spellings. Paths are immutable:
// child() builds a new path by copy and never modifies this path, matching
// the Rust contract (location.rs:35-39). location.rs defines no
// construction-time invariants, so no validation is invented here.

package consema.core

/**
 * One segment of a root-relative portable value path.
 *
 * The closed variant set mirrors the Rust enum exactly
 * (crates/consema-core/src/location.rs:5-14): [ObjectValue] — value of a
 * uniquely named object entry (location.rs:6-7); [SequenceElement] —
 * sequence element at a non-negative index (location.rs:8-9);
 * [EntryKey] — key value of an entry-mapping association (location.rs:10-11);
 * [EntryValue] — value of an entry-mapping association (location.rs:12-13).
 * The Rust u64 payloads map to Kotlin Long (see the family call sites
 * passing `.toLong()`, e.g. consema/json/Projection.kt:582-583).
 */
sealed class ValuePathSegment {
    /** Value of a uniquely named object entry (location.rs:7). */
    data class ObjectValue(val name: String) : ValuePathSegment()

    /** Sequence element at a non-negative index (location.rs:9). */
    data class SequenceElement(val index: Long) : ValuePathSegment()

    /** Key value of an entry-mapping association (location.rs:11). */
    data class EntryKey(val ordinal: Long) : ValuePathSegment()

    /** Value of an entry-mapping association (location.rs:13). */
    data class EntryValue(val ordinal: Long) : ValuePathSegment()
}

/**
 * A path to a value; the empty path denotes the root
 * (crates/consema-core/src/location.rs:17-18).
 *
 * Immutable: [child] returns a new path and never modifies this path
 * (location.rs:35-39). Equality and hashing are structural over the segment
 * sequence in order (the Rust PartialEq/Hash are derived on the backing
 * Vec, location.rs:17-18), so two paths with the same segments in the same
 * order are equal and hash alike. Construction is private to mirror the
 * Rust tuple struct with a private field (location.rs:18): paths are
 * created only via [root] and [child]; [kotlin.ConsistentCopyVisibility]
 * keeps the generated [copy] as private as the constructor.
 */
@ConsistentCopyVisibility
data class ValuePath private constructor(val segments: List<ValuePathSegment>) {
    /** Returns the path segments (location.rs:29-31). */
    fun segments(): List<ValuePathSegment> = segments

    /**
     * Creates a child path without modifying this path (location.rs:35-39):
     * returns a new path whose segment sequence is this path's sequence
     * followed by [segment].
     */
    fun child(segment: ValuePathSegment): ValuePath = ValuePath(segments + segment)

    companion object {
        /** Root path (location.rs:22-25). */
        fun root(): ValuePath = ValuePath(emptyList())
    }
}

/**
 * Association kind independent from child values
 * (crates/consema-core/src/location.rs:43-51). The entries use the exact
 * Rust spellings (location.rs:44-51).
 */
enum class AssociationRole {
    /** Whole object entry (location.rs:45-46). */
    ObjectEntry,

    /** The name role of an object entry (location.rs:47-48). */
    ObjectKey,

    /** Whole entry-mapping association (location.rs:49-50). */
    EntryMappingEntry,
}

/**
 * Location of an association, not a portable value node
 * (crates/consema-core/src/location.rs:54-59).
 *
 * [ordinal] is the structural association ordinal (location.rs:79-82); the
 * Rust u64 maps to Kotlin Long. location.rs defines no construction-time
 * invariants: `new` (location.rs:64-70) is total — there is no
 * role/container-consistency rule or ordinal validation to enforce, and
 * none is invented here. Equality and hashing are field-wise (the Rust
 * PartialEq/Hash are derived, location.rs:54-55).
 */
data class AssociationLocation(
    /** Path of the containing value (location.rs:74-76). */
    val container: ValuePath,
    /** Structural association ordinal (location.rs:80-82). */
    val ordinal: Long,
    /** Association role (location.rs:86-88). */
    val role: AssociationRole,
)
