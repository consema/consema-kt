// The frozen YAML format operation registry.
//
// Data authority:
//   - RFC 0004 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md): every structural operation has an
//     immutable ID/version, target role, argument schema, and support
//     classification; the registry does not claim that operations with
//     similar names have identical format semantics.
//   - RFC 0007 §12 (https://github.com/consema/consema/blob/main/docs/rfcs/0007-yaml-family-profiles-and-safety-v1.md) freezes the eight YAML operation ids (yaml.edit.
//     replace-scalar-semantic, replace-scalar-literal, insert-mapping-entry,
//     remove-mapping-entry, insert-sequence-element, remove-sequence-element,
//     rename-anchor, insert-alias).
//   - https://github.com/consema/consema-rs/blob/main/consema-yaml/src/operation_registry.rs is the exact
//     byte-arbitration source of the eight descriptor records (ids, target
//     roles, argument names/kinds, support classes); the frozen surface test
//     (operation_registry.rs) pins the six Supported operations and
//     the total count of 8.
//
// Kotlin-idiomatic design: the registry is an immutable list of descriptor
// data classes per profile; argument kinds use the language-neutral spellings
// of the Rust OperationArgumentKind (String, PortableValue, Placement,
// RepresentationPolicy, ExactBytes, NodeRef).

package consema.yaml

import consema.document.FormatOperationId

/** Argument kind of one operation descriptor (operation_registry.rs). */
enum class OperationArgumentKind {
    /** Decoded anchor name (String). */
    String,

    /** Complete portable value (PortableValue). */
    PortableValue,

    /** Association placement (Placement). */
    Placement,

    /** Scalar representation policy (RepresentationPolicy). */
    RepresentationPolicy,

    /** Exact candidate literal bytes (ExactBytes). */
    ExactBytes,

    /** Snapshot-bound node reference (NodeRef). */
    NodeRef,
}

/** Support classification of one operation (operation_registry.rs). */
enum class OperationSupport {
    /** Structural operation supported by every YAML profile. */
    Supported,

    /** Existing typed capability declared by the registry (scalar
     * semantic/literal replacement; RFC 0004 §10). */
    ExistingTypedCapability,
}

/** One immutable operation descriptor (operation_registry.rs). */
data class YamlOperationDescriptor(
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
 * Returns the validated operation registry for one exact YAML profile
 * (operation_registry.rs). Every profile publishes the same frozen
 * eight-record surface (operation_registry.rs).
 */
fun formatOperationRegistry(profile: YamlProfile): List<YamlOperationDescriptor> =
    descriptors()

private fun descriptor(
    id: String,
    targetRole: String,
    arguments: List<Pair<String, OperationArgumentKind>>,
    support: OperationSupport,
): YamlOperationDescriptor =
    YamlOperationDescriptor(
        FormatOperationId(id, 1),
        targetRole,
        arguments,
        support,
    )

/** The frozen eight descriptor records (operation_registry.rs). */
private fun descriptors(): List<YamlOperationDescriptor> =
    listOf(
        descriptor(
            "yaml.edit.insert-alias",
            "yaml.sequence",
            listOf(
                "anchor" to OperationArgumentKind.NodeRef,
                "placement" to OperationArgumentKind.Placement,
            ),
            OperationSupport.Supported,
        ),
        descriptor(
            "yaml.edit.insert-mapping-entry",
            "yaml.mapping",
            listOf(
                "key" to OperationArgumentKind.PortableValue,
                "value" to OperationArgumentKind.PortableValue,
                "placement" to OperationArgumentKind.Placement,
            ),
            OperationSupport.Supported,
        ),
        descriptor(
            "yaml.edit.insert-sequence-element",
            "yaml.sequence",
            listOf(
                "value" to OperationArgumentKind.PortableValue,
                "placement" to OperationArgumentKind.Placement,
            ),
            OperationSupport.Supported,
        ),
        descriptor(
            "yaml.edit.remove-mapping-entry",
            "yaml.mapping-entry",
            emptyList(),
            OperationSupport.Supported,
        ),
        descriptor(
            "yaml.edit.remove-sequence-element",
            "yaml.sequence-element",
            emptyList(),
            OperationSupport.Supported,
        ),
        descriptor(
            "yaml.edit.rename-anchor",
            "yaml.anchor-definition",
            listOf("name" to OperationArgumentKind.String),
            OperationSupport.Supported,
        ),
        descriptor(
            "yaml.edit.replace-scalar-literal",
            "yaml.scalar",
            listOf("literal" to OperationArgumentKind.ExactBytes),
            OperationSupport.ExistingTypedCapability,
        ),
        descriptor(
            "yaml.edit.replace-scalar-semantic",
            "yaml.scalar",
            listOf(
                "value" to OperationArgumentKind.PortableValue,
                "representation_policy" to OperationArgumentKind.RepresentationPolicy,
            ),
            OperationSupport.ExistingTypedCapability,
        ),
    )
