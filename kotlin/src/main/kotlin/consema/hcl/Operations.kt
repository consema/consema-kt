// The frozen HCL format operation registry (RFC 0014 §10, RFC 0004 §10).
//
// Data authority:
//   - https://github.com/consema/consema-rs/blob/main/consema-hcl/src/operation_registry.rs:16-99 pins every id,
//     target role, argument, and support classification: `hcl.native@1`
//     publishes all six structural operations
//     (operation_registry.rs:105-127: hcl.edit.insert-attribute@1,
//     hcl.edit.insert-block@1, hcl.edit.remove-attribute@1,
//     hcl.edit.remove-block@1, hcl.edit.rename-attribute@1,
//     hcl.edit.set-attribute-value@1); `hcl.tfvars@1` publishes the four
//     attribute operations only (operation_registry.rs:129-156), because
//     the tfvars restriction admits no block (RFC 0014 §5, §10).
//   - RFC 0014 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0014-hcl-family-profiles-v1.md:630-671)
//     freezes the six operation semantics; RFC 0004 §10 (:244-269) freezes
//     the registry discipline.
//   - consema-go/go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design: the full FormatOperationRegistry type is not
// shipped in Kotlin (recorded gap, six-repo audit G090;
// document/EditPlan.kt:22-23); this surface exposes the frozen descriptors
// as immutable data the facade's per-profile operation registries consume,
// mirroring the toml/json family registries.

package consema.hcl

import consema.document.FormatOperationId

/** The argument vocabulary of one operation descriptor (the Rust
 * OperationArgumentKind, operation_registry.rs:22-25). */
enum class HclOperationArgumentKind {
    String,
    PortableValue,
    Placement,
}

/** The support classification of one operation (the Rust
 * OperationSupport, operation_registry.rs:26). */
enum class HclOperationSupport {
    /** The operation is part of the published structural surface. */
    Supported,
}

/** One frozen format operation descriptor (operation_registry.rs:88-98). */
data class HclOperationDescriptor(
    /** Immutable namespaced operation ID (version 1). */
    val id: FormatOperationId,
    /** Immutable target role ID (version 1). */
    val targetRole: FormatOperationId,
    /** Ordered argument names with their kinds. */
    val arguments: List<Pair<String, HclOperationArgumentKind>>,
    /** Support classification. */
    val support: HclOperationSupport,
) {
    /** The frozen `id@version` spelling. */
    override fun toString(): String = id.toString()
}

/** Returns the validated operation registry for one exact HCL profile
 * (operation_registry.rs:16-23). The registry orders the descriptors by
 * their frozen ids (the pinned surface of operation_registry.rs:105-127:
 * insert-attribute, insert-block, remove-attribute, remove-block,
 * rename-attribute, set-attribute-value). */
fun formatOperationRegistry(profile: HclProfile): List<HclOperationDescriptor> =
    (if (profile == HclProfile.NATIVE_V1) nativeDescriptors() else tfvarsDescriptors())
        .sortedBy { it.id.id }

private fun descriptor(
    id: String,
    targetRole: String,
    arguments: List<Pair<String, HclOperationArgumentKind>>,
): HclOperationDescriptor = HclOperationDescriptor(
    id = FormatOperationId(id, 1),
    targetRole = FormatOperationId(targetRole, 1),
    arguments = arguments,
    support = HclOperationSupport.Supported,
)

/** The attribute-only surface of `hcl.tfvars@1` (operation_registry.rs:
 * 49-80). */
private fun tfvarsDescriptors(): List<HclOperationDescriptor> =
    listOf(
        descriptor(
            "hcl.edit.insert-attribute",
            "hcl.body",
            listOf(
                "name" to HclOperationArgumentKind.String,
                "value" to HclOperationArgumentKind.PortableValue,
                "placement" to HclOperationArgumentKind.Placement,
            ),
        ),
        descriptor(
            "hcl.edit.remove-attribute",
            "hcl.attribute",
            emptyList(),
        ),
        descriptor(
            "hcl.edit.rename-attribute",
            "hcl.attribute",
            listOf("name" to HclOperationArgumentKind.String),
        ),
        descriptor(
            "hcl.edit.set-attribute-value",
            "hcl.attribute",
            listOf("value" to HclOperationArgumentKind.PortableValue),
        ),
    )

/** The full six-operation surface of `hcl.native@1`
 * (operation_registry.rs:26-46). */
private fun nativeDescriptors(): List<HclOperationDescriptor> =
    tfvarsDescriptors() + listOf(
        descriptor(
            "hcl.edit.insert-block",
            "hcl.body",
            listOf(
                "type" to HclOperationArgumentKind.String,
                "labels" to HclOperationArgumentKind.String,
                "attributes" to HclOperationArgumentKind.PortableValue,
                "placement" to HclOperationArgumentKind.Placement,
            ),
        ),
        descriptor(
            "hcl.edit.remove-block",
            "hcl.block",
            emptyList(),
        ),
    )
