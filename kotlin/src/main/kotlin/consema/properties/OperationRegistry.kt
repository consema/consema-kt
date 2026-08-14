// The frozen Java Properties format operation registry.
//
// Data authority:
//   - RFC 0004 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md): every structural operation has an
//     immutable ID/version, target role, argument schema, and support
//     classification.
//   - RFC 0010 §13 (https://github.com/consema/consema/blob/main/docs/rfcs/0010-java-properties-profiles-v1.md):
//     both Profiles publish the same five independently validated
//     operations.
//   - https://github.com/consema/consema-rs/blob/main/consema-properties/src/operation_registry.rs is the exact
//     byte-arbitration source of the five descriptor records (ids, target
//     roles, argument names/kinds, support classes); the frozen surface test
//     (operation_registry.rs) pins the five Supported operations for
//     both profiles in sorted id order.
//   - conformance/vectors/java-properties-v1.json registry.frozen-five-
//     operation-surface (lines 146-150) pins the exact operation list.
//
// Kotlin-idiomatic design: the registry is an immutable list of descriptor
// data classes per profile; argument kinds use the language-neutral
// spellings of the Rust OperationArgumentKind (PortableValue, Placement,
// ExactBytes) — the JSON family precedent (kotlin/src/main/kotlin/consema/json/
// OperationRegistry.kt).

package consema.properties

import consema.document.FormatOperationId

/** Argument kind of one operation descriptor (operation_registry.rs). */
enum class PropertiesOperationArgumentKind {
    /** Complete portable value (PortableValue). */
    PortableValue,

    /** Association placement (Placement). */
    Placement,

    /** Exact candidate literal bytes (ExactBytes). */
    ExactBytes,
}

/** Support classification of one operation (operation_registry.rs). */
enum class PropertiesOperationSupport {
    /** Structural operation supported by every Java Properties profile. */
    Supported,
}

/** One immutable operation descriptor (operation_registry.rs). */
data class PropertiesOperationDescriptor(
    /** Exact immutable operation ID/version. */
    val id: FormatOperationId,
    /** Versioned target role. */
    val targetRole: String,
    /** Ordered argument name/kind pairs; all arguments are required. */
    val arguments: List<Pair<String, PropertiesOperationArgumentKind>>,
    /** Support classification. */
    val support: PropertiesOperationSupport,
)

/**
 * Returns the validated operation registry for one exact Java Properties
 * profile (operation_registry.rs). Both profiles publish the same
 * frozen five-record surface (operation_registry.rs;
 * registry.frozen-five-operation-surface, java-properties-v1.json:146-150).
 */
fun formatOperationRegistry(profile: PropertiesProfile): List<PropertiesOperationDescriptor> {
    check(profile == PropertiesProfile.ReaderV1 || profile == PropertiesProfile.Latin1V1) {
        "built-in Java Properties operation descriptors are valid"
    }
    fun descriptor(
        id: String,
        targetRole: String,
        arguments: List<Pair<String, PropertiesOperationArgumentKind>>,
    ): PropertiesOperationDescriptor = PropertiesOperationDescriptor(
        id = FormatOperationId(id, 1),
        targetRole = targetRole,
        arguments = arguments,
        support = PropertiesOperationSupport.Supported,
    )
    fun argument(name: String, kind: PropertiesOperationArgumentKind): Pair<String, PropertiesOperationArgumentKind> =
        name to kind

    return listOf(
        descriptor(
            "java-properties.edit.insert-property",
            "java-properties.document@1",
            listOf(
                argument("key", PropertiesOperationArgumentKind.PortableValue),
                argument("value", PropertiesOperationArgumentKind.PortableValue),
                argument("placement", PropertiesOperationArgumentKind.Placement),
            ),
        ),
        descriptor(
            "java-properties.edit.remove-property",
            "java-properties.property@1",
            emptyList(),
        ),
        descriptor(
            "java-properties.edit.rename-property",
            "java-properties.property@1",
            listOf(argument("key", PropertiesOperationArgumentKind.PortableValue)),
        ),
        descriptor(
            "java-properties.edit.replace-literal-value",
            "java-properties.property@1",
            listOf(argument("literal", PropertiesOperationArgumentKind.ExactBytes)),
        ),
        descriptor(
            "java-properties.edit.replace-semantic-value",
            "java-properties.property@1",
            listOf(argument("value", PropertiesOperationArgumentKind.PortableValue)),
        ),
    )
}
