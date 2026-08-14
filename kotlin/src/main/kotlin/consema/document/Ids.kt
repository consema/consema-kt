// Namespaced identifiers of the document domain.
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-document/src/lib.rs (FormatFamilyId,
// ProfileId), https://github.com/consema/consema-rs/blob/main/consema-document/src/materialization.rs
// (MaterializationStyleId), https://github.com/consema/consema-rs/blob/main/consema-document/src/operation_registry.rs
// (FormatOperationId). The v1 target profiles and style IDs frozen by
// RFC 0004 §4 are: json.strict@1, jsonc.bounded@1, toml.1.0@1 and
// json.canonical-compact@1, json.canonical-pretty@1, toml.canonical-
// document@1 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-structural-edit-
// v1.md). consema-go/go/document/ids.go is a cross-reference only.
//
// Kotlin-idiomatic design: immutable data classes with the namespaced `id`
// spelling kept as the exact language-neutral string and the `version`
// as a separate field, so `"json.strict" + "@1"` is reconstructible.

package consema.document

/** Stable namespaced format family contract (lib.rs). */
data class FormatFamilyId(
    /** Namespace (the exact language-neutral spelling, e.g. "json"). */
    val id: String,
    /** Contract version. */
    val version: Int,
)

/** Immutable named language profile (lib.rs). */
data class ProfileId(
    /** Namespace (the exact language-neutral spelling, e.g. "json.strict"). */
    val id: String,
    /** Profile version. */
    val version: Int,
)

/** Versioned format-owned materialization style identifier
 * (materialization.rs). */
data class MaterializationStyleId(
    /** Namespaced style ID without version suffix, e.g. "json.canonical-compact". */
    val id: String,
    /** Immutable style version. */
    val version: Int,
)

/** Immutable namespaced operation identifier (operation_registry.rs).
 * A registry validates its public spelling; `toString` renders the frozen
 * `id@version` form used by EditPlan metadata (operation_registry.rs). */
data class FormatOperationId(
    /** Namespaced identifier without its version suffix. */
    val id: String,
    /** Immutable operation version. */
    val version: Int,
) {
    override fun toString(): String = "$id@$version"
}
