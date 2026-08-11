// The frozen TOML format operation registry.
//
// Data authority:
//   - crates/consema-toml/src/operation_registry.rs:16-74 (descriptors) and
//     :94-119 (the pinned registry facts: exactly seven operations, five
//     Supported structural operations in sorted id order, two
//     ExistingTypedCapability scalar replacements) pin every id, target
//     role, argument, and support classification; RFC 0004 §10
//     (docs/rfcs/0004-materialization-conversion-and-structural-edit-v1.md:
//     244-269) freezes the five structural IDs in the language-neutral
//     contract.
//   - conformance/vectors/operations-v1.json operations.v1.operation-
//     registry (lines 18-22) pins toml_operation_count = 7 and
//     required_toml = "toml.edit.insert-entry@1".
//
// Kotlin-idiomatic design: the full FormatOperationRegistry type belongs
// to the L4 structural-edit milestone (document/EditPlan.kt:22-23); this
// L1 surface exposes the frozen descriptors as immutable data so the L4
// registry can adopt them unchanged.

package consema.toml

/** The argument vocabulary of one operation descriptor (the Rust
 * OperationArgumentKind, operation_registry.rs:22-25). */
enum class TomlOperationArgumentKind {
    String,
    PortableValue,
    Placement,
    RepresentationPolicy,
    ExactBytes,
}

/** The support classification of one operation (the Rust
 * OperationSupport). */
enum class TomlOperationSupport {
    /** The operation is part of the published structural surface. */
    Supported,

    /** The operation exists as a typed capability from the scalar edit
     * milestone (RFC 0004 §10). */
    ExistingTypedCapability,
}

/** One frozen format operation descriptor
 * (operation_registry.rs:76-88). */
data class TomlOperationDescriptor(
    /** Immutable namespaced operation ID (version 1). */
    val id: consema.document.FormatOperationId,
    /** Immutable target role ID (version 1). */
    val targetRole: consema.document.FormatOperationId,
    /** Ordered argument names with their kinds. */
    val arguments: List<Pair<String, TomlOperationArgumentKind>>,
    /** Support classification. */
    val support: TomlOperationSupport,
) {
    /** The frozen `id@version` spelling. */
    override fun toString(): String = id.toString()
}

/** Returns the validated operation registry for one exact TOML profile
 * (operation_registry.rs:9-14). The seven descriptors are transcribed
 * verbatim from operation_registry.rs:16-74. */
fun formatOperationRegistry(profile: TomlProfile): List<TomlOperationDescriptor> {
    check(profile == TomlProfile.TOML_1_0_V1) { "built-in TOML operation descriptors are valid" }
    fun descriptor(
        id: String,
        targetRole: String,
        arguments: List<Pair<String, TomlOperationArgumentKind>>,
        support: TomlOperationSupport,
    ): TomlOperationDescriptor = TomlOperationDescriptor(
        id = consema.document.FormatOperationId(id, 1),
        targetRole = consema.document.FormatOperationId(targetRole, 1),
        arguments = arguments,
        support = support,
    )
    fun argument(name: String, kind: TomlOperationArgumentKind): Pair<String, TomlOperationArgumentKind> =
        name to kind

    return listOf(
        descriptor(
            "toml.edit.insert-entry",
            "toml.table-item",
            listOf(
                argument("key", TomlOperationArgumentKind.String),
                argument("value", TomlOperationArgumentKind.PortableValue),
                argument("placement", TomlOperationArgumentKind.Placement),
            ),
            TomlOperationSupport.Supported,
        ),
        descriptor(
            "toml.edit.remove-entry",
            "toml.entry",
            emptyList(),
            TomlOperationSupport.Supported,
        ),
        descriptor(
            "toml.edit.rename-entry",
            "toml.entry",
            listOf(argument("key", TomlOperationArgumentKind.String)),
            TomlOperationSupport.Supported,
        ),
        descriptor(
            "toml.edit.insert-array-element",
            "toml.array-item",
            listOf(
                argument("value", TomlOperationArgumentKind.PortableValue),
                argument("placement", TomlOperationArgumentKind.Placement),
            ),
            TomlOperationSupport.Supported,
        ),
        descriptor(
            "toml.edit.remove-array-element",
            "toml.array-element",
            emptyList(),
            TomlOperationSupport.Supported,
        ),
        descriptor(
            "toml.edit.replace-scalar-semantic",
            "toml.scalar-item",
            listOf(
                argument("value", TomlOperationArgumentKind.PortableValue),
                argument("representation_policy", TomlOperationArgumentKind.RepresentationPolicy),
            ),
            TomlOperationSupport.ExistingTypedCapability,
        ),
        descriptor(
            "toml.edit.replace-scalar-literal",
            "toml.scalar-item",
            listOf(argument("literal", TomlOperationArgumentKind.ExactBytes)),
            TomlOperationSupport.ExistingTypedCapability,
        ),
    )
}
