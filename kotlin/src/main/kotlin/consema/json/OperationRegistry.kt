// The frozen JSON-family format operation registry.
//
// Data authority:
//   - RFC 0004 §10 (docs/rfcs/0004-materialization-conversion-and-structural-
//     edit-v1.md:244-269): every structural operation has an immutable
//     ID/version, target role, argument schema, and support classification;
//     the registry does not claim that operations with similar names have
//     identical format semantics.
//   - RFC 0005 §10 (docs/rfcs/0005-json-family-production-v1.md:220-241):
//     json.edit.move-member@1 raises the JSON format-operation registry to
//     eight records for every JSON-family profile.
//   - crates/consema-json/src/operation_registry.rs:16-80 is the exact
//     byte-arbitration source of the eight descriptor records (ids, target
//     roles, argument names/kinds, support classes); the frozen surface test
//     (operation_registry.rs:104-129) pins the six Supported operations and
//     the total count of 8. go/json has no registry file; it is cross-checked
//     against the Rust records only.
//
// Kotlin-idiomatic design: the registry is an immutable list of descriptor
// data classes per profile; argument kinds use the language-neutral spellings
// of the Rust OperationArgumentKind (String, PortableValue, Placement,
// RepresentationPolicy, ExactBytes).

package consema.json

import consema.document.FormatOperationId

/** Argument kind of one operation descriptor (operation_registry.rs:22-25). */
enum class OperationArgumentKind {
    /** Decoded member name (String). */
    String,

    /** Complete portable value (PortableValue). */
    PortableValue,

    /** Association placement (Placement). */
    Placement,

    /** Scalar representation policy (RepresentationPolicy). */
    RepresentationPolicy,

    /** Exact candidate literal bytes (ExactBytes). */
    ExactBytes,
}

/** Support classification of one operation (operation_registry.rs:26). */
enum class OperationSupport {
    /** Structural operation supported by every JSON-family profile. */
    Supported,

    /** Existing typed capability declared by the registry (scalar
     * semantic/literal replacement; RFC 0004 §10). */
    ExistingTypedCapability,
}

/** One immutable operation descriptor (operation_registry.rs:16-80). */
data class JsonOperationDescriptor(
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
 * Returns the validated operation registry for one exact JSON-family profile
 * (operation_registry.rs:9-14). Every profile publishes the same frozen
 * eight-record surface (operation_registry.rs:104-129).
 */
fun formatOperationRegistry(profile: JsonProfile): List<JsonOperationDescriptor> =
    descriptors()

private fun descriptor(
    id: String,
    targetRole: String,
    arguments: List<Pair<String, OperationArgumentKind>>,
    support: OperationSupport,
): JsonOperationDescriptor =
    JsonOperationDescriptor(
        FormatOperationId(id, 1),
        targetRole,
        arguments,
        support,
    )

/** The frozen eight descriptor records (operation_registry.rs:16-80). */
private fun descriptors(): List<JsonOperationDescriptor> =
    listOf(
        descriptor(
            "json.edit.insert-member",
            "json.object@1",
            listOf(
                "name" to OperationArgumentKind.String,
                "value" to OperationArgumentKind.PortableValue,
                "placement" to OperationArgumentKind.Placement,
            ),
            OperationSupport.Supported,
        ),
        descriptor(
            "json.edit.remove-member",
            "json.object-member@1",
            emptyList(),
            OperationSupport.Supported,
        ),
        descriptor(
            "json.edit.move-member",
            "json.object-member@1",
            listOf("placement" to OperationArgumentKind.Placement),
            OperationSupport.Supported,
        ),
        descriptor(
            "json.edit.rename-member",
            "json.object-member@1",
            listOf("name" to OperationArgumentKind.String),
            OperationSupport.Supported,
        ),
        descriptor(
            "json.edit.insert-array-element",
            "json.array@1",
            listOf(
                "value" to OperationArgumentKind.PortableValue,
                "placement" to OperationArgumentKind.Placement,
            ),
            OperationSupport.Supported,
        ),
        descriptor(
            "json.edit.remove-array-element",
            "json.array-element@1",
            emptyList(),
            OperationSupport.Supported,
        ),
        descriptor(
            "json.edit.replace-scalar-semantic",
            "json.scalar@1",
            listOf(
                "value" to OperationArgumentKind.PortableValue,
                "representation_policy" to OperationArgumentKind.RepresentationPolicy,
            ),
            OperationSupport.ExistingTypedCapability,
        ),
        descriptor(
            "json.edit.replace-scalar-literal",
            "json.scalar@1",
            listOf("literal" to OperationArgumentKind.ExactBytes),
            OperationSupport.ExistingTypedCapability,
        ),
    )
