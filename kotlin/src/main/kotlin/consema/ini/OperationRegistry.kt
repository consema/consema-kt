// The frozen INI-family format operation registry.
//
// Data authority:
//   - RFC 0004 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md): every structural operation has an
//     immutable ID/version, target role, argument schema, and support
//     classification; the registry does not claim that operations with
//     similar names have identical format semantics.
//   - RFC 0009 §12 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md):
//     all three format profiles publish the same operation count but
//     independently typed INI operations: the eight ini.edit.*@1 names.
//   - conformance/vectors/ini-v1.json registry.frozen-eight-operation-
//     surface pins the eight ids and the six direct structural operations;
//     https://github.com/consema/consema-rs/blob/main/consema-ini/src/operation_registry.rs is the exact
//     byte-arbitration source of the descriptor records and the support
//     classes (operation_registry.rs).
//
// Kotlin-idiomatic design: the registry is an immutable list of descriptor
// data classes per profile; argument kinds use the language-neutral
// spellings of the Rust OperationArgumentKind; target-role strings carry
// the `@1` version suffix, the established Kotlin family convention
// (kotlin/src/main/kotlin/consema/json/OperationRegistry.kt).

package consema.ini

import consema.document.FormatOperationId

/** Argument kind of one operation descriptor (operation_registry.rs). */
enum class OperationArgumentKind {
    /** Decoded name (String). */
    String,

    /** Association placement (Placement). */
    Placement,

    /** Value representation policy (RepresentationPolicy). */
    RepresentationPolicy,

    /** Exact candidate literal bytes (ExactBytes). */
    ExactBytes,
}

/** Support classification of one operation (operation_registry.rs). */
enum class OperationSupport {
    /** Structural operation supported by every INI profile. */
    Supported,

    /** Existing typed capability declared by the registry (scalar
     * semantic/literal replacement; RFC 0004 §10). */
    ExistingTypedCapability,
}

/** One immutable operation descriptor (operation_registry.rs). */
data class IniOperationDescriptor(
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
 * Returns the validated operation registry for one exact INI profile
 * (operation_registry.rs). Every profile publishes the same frozen
 * eight-record surface (operation_registry.rs); the records are
 * ordered by id, the order the frozen surface test and the shared vectors
 * assert (operation_registry.rs).
 */
fun formatOperationRegistry(profile: IniProfile): List<IniOperationDescriptor> =
    descriptors().sortedBy { it.id.toString() }

private fun descriptor(
    id: String,
    targetRole: String,
    arguments: List<Pair<String, OperationArgumentKind>>,
    support: OperationSupport,
): IniOperationDescriptor =
    IniOperationDescriptor(
        FormatOperationId(id, 1),
        targetRole,
        arguments,
        support,
    )

/** The frozen eight descriptor records (operation_registry.rs). */
private fun descriptors(): List<IniOperationDescriptor> =
    listOf(
        descriptor(
            "ini.edit.insert-section",
            "ini.document@1",
            listOf(
                "name" to OperationArgumentKind.String,
                "placement" to OperationArgumentKind.Placement,
            ),
            OperationSupport.Supported,
        ),
        descriptor(
            "ini.edit.remove-section",
            "ini.section@1",
            emptyList(),
            OperationSupport.Supported,
        ),
        descriptor(
            "ini.edit.rename-section",
            "ini.section@1",
            listOf("name" to OperationArgumentKind.String),
            OperationSupport.Supported,
        ),
        descriptor(
            "ini.edit.insert-entry",
            "ini.section@1",
            listOf(
                "key" to OperationArgumentKind.String,
                "value" to OperationArgumentKind.String,
                "placement" to OperationArgumentKind.Placement,
            ),
            OperationSupport.Supported,
        ),
        descriptor(
            "ini.edit.remove-entry",
            "ini.entry@1",
            emptyList(),
            OperationSupport.Supported,
        ),
        descriptor(
            "ini.edit.rename-entry",
            "ini.entry@1",
            listOf("key" to OperationArgumentKind.String),
            OperationSupport.Supported,
        ),
        descriptor(
            "ini.edit.replace-semantic-value",
            "ini.entry@1",
            listOf(
                "value" to OperationArgumentKind.String,
                "representation_policy" to OperationArgumentKind.RepresentationPolicy,
            ),
            OperationSupport.ExistingTypedCapability,
        ),
        descriptor(
            "ini.edit.replace-literal-value",
            "ini.entry@1",
            listOf("literal" to OperationArgumentKind.ExactBytes),
            OperationSupport.ExistingTypedCapability,
        ),
    )
