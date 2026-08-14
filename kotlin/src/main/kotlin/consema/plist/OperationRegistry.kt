// The frozen plist-family format operation registry.
//
// Data authority:
//   - RFC 0013 §11 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md):
//     both profiles publish the same six snapshot-bound structural
//     operations, independently typed per profile.
//   - RFC 0004 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md): every structural operation has an
//     immutable ID/version, target role, argument schema, and support
//     classification; the registry does not claim that operations with
//     similar names have identical format semantics.
//   - https://github.com/consema/consema-rs/blob/main/consema-plist/src/operation_registry.rs is the exact
//     byte-arbitration source of the six descriptor records (ids, target
//     roles, argument names/kinds, support class); the frozen surface test
//     (operation_registry.rs) pins the sorted six-operation list
//     for both profiles. consema-go/go/plist is a cross-reference only.
//
// Kotlin-idiomatic design: the registry is an immutable list of descriptor
// data classes per profile (the json-family precedent,
// kotlin/src/main/kotlin/consema/json/OperationRegistry.kt); argument kinds use the language-
// neutral spellings of the Rust OperationArgumentKind (NodeRef, PortableValue,
// String, Placement).

package consema.plist

import consema.document.FormatOperationId

/** Argument kind of one operation descriptor (operation_registry.rs). */
enum class OperationArgumentKind {
    /** Exact structural target (NodeRef). */
    NodeRef,

    /** Complete portable value (PortableValue). */
    PortableValue,

    /** Decoded key or name (String). */
    String,

    /** Association placement (Placement). */
    Placement,
}

/** Support classification of one operation (operation_registry.rs). */
enum class OperationSupport {
    /** Structural operation supported by every plist profile. */
    Supported,
}

/** One immutable operation descriptor (operation_registry.rs). */
data class PlistOperationDescriptor(
    /** Exact immutable operation ID/version. */
    val id: FormatOperationId,
    /** Versioned target role. */
    val targetRole: String,
    /** Ordered argument name/kind pairs; all arguments are required. */
    val arguments: List<Pair<String, OperationArgumentKind>>,
    /** Support classification. */
    val support: OperationSupport,
)

/**
 * Returns the validated operation registry for one exact plist profile
 * (operation_registry.rs). Both profiles publish the same frozen
 * six-record surface (operation_registry.rs).
 */
fun formatOperationRegistry(profile: PlistProfile): List<PlistOperationDescriptor> =
    descriptors()

private fun descriptor(
    id: String,
    targetRole: String,
    arguments: List<Pair<String, OperationArgumentKind>>,
): PlistOperationDescriptor =
    PlistOperationDescriptor(
        FormatOperationId(id, 1),
        targetRole,
        arguments,
        OperationSupport.Supported,
    )

/** The frozen six descriptor records (operation_registry.rs). */
private fun descriptors(): List<PlistOperationDescriptor> =
    listOf(
        descriptor(
            "plist.edit.set-value",
            "plist.value",
            listOf(
                "path" to OperationArgumentKind.NodeRef,
                "value" to OperationArgumentKind.PortableValue,
            ),
        ),
        descriptor(
            "plist.edit.insert-dict-entry",
            "plist.value",
            listOf(
                "path" to OperationArgumentKind.NodeRef,
                "key" to OperationArgumentKind.String,
                "value" to OperationArgumentKind.PortableValue,
                "placement" to OperationArgumentKind.Placement,
            ),
        ),
        descriptor(
            "plist.edit.remove-dict-entry",
            "plist.dict-entry",
            listOf(
                "path" to OperationArgumentKind.NodeRef,
                "key" to OperationArgumentKind.String,
                "occurrence" to OperationArgumentKind.NodeRef,
            ),
        ),
        descriptor(
            "plist.edit.rename-dict-key",
            "plist.dict-entry",
            listOf(
                "path" to OperationArgumentKind.NodeRef,
                "from" to OperationArgumentKind.String,
                "occurrence" to OperationArgumentKind.NodeRef,
                "to" to OperationArgumentKind.String,
            ),
        ),
        descriptor(
            "plist.edit.insert-array-element",
            "plist.value",
            listOf(
                "path" to OperationArgumentKind.NodeRef,
                "index" to OperationArgumentKind.NodeRef,
                "value" to OperationArgumentKind.PortableValue,
            ),
        ),
        descriptor(
            "plist.edit.remove-array-element",
            "plist.array-element",
            listOf(
                "path" to OperationArgumentKind.NodeRef,
                "index" to OperationArgumentKind.NodeRef,
            ),
        ),
    )
