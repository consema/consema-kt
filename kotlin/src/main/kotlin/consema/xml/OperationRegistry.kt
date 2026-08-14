// The frozen XML format operation registry (RFC 0012 §11; RFC 0004 §10).
//
// Data authority:
//   - RFC 0012 §11 (https://github.com/consema/consema/blob/main/docs/rfcs/0012-xml-1.0-safe-profile-v1.md):
//     V1 publishes exactly these eight snapshot-bound operations:
//     xml.edit.replace-text@1, xml.edit.insert-attribute@1,
//     xml.edit.remove-attribute@1, xml.edit.rename-attribute@1,
//     xml.edit.set-attribute-value@1, xml.edit.insert-element@1,
//     xml.edit.remove-element@1, xml.edit.rename-element@1.
//   - https://github.com/consema/consema-rs/blob/main/consema-xml/src/operation_registry.rs is the exact
//     byte-arbitration source of the eight descriptor records (ids, target
//     roles, argument names/kinds, support classes); the frozen surface test
//     (operation_registry.rs) pins the exact eight-operation surface
//     and the Supported classification of every record.
//   - consema-go/go/xml has no registry file; the operation_test.go surface is a
//     cross-check against the Rust records only.
//
// Kotlin-idiomatic design: the registry is an immutable list of descriptor
// data classes per profile; argument kinds use the language-neutral
// spellings of the Rust OperationArgumentKind (String, Placement), matching
// the json family registry (kotlin/src/main/kotlin/consema/json/OperationRegistry.kt).

package consema.xml

import consema.document.FormatOperationId

/** Argument kind of one operation descriptor (operation_registry.rs;
 * the json family transcription kotlin/src/main/kotlin/consema/json/OperationRegistry.kt). */
enum class OperationArgumentKind {
    /** Decoded name or text (String). */
    String,

    /** Association placement (Placement). */
    Placement,
}

/** Support classification of one operation (operation_registry.rs). */
enum class OperationSupport {
    /** Structural operation supported by the xml.1.0-safe@1 profile. */
    Supported,
}

/** One immutable operation descriptor (operation_registry.rs). */
data class XmlOperationDescriptor(
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
 * Returns the validated operation registry for one exact XML profile
 * (operation_registry.rs). The profile publishes the frozen eight-
 * record surface (operation_registry.rs).
 */
fun formatOperationRegistry(profile: XmlProfile): List<XmlOperationDescriptor> =
    descriptors()

private fun descriptor(
    id: String,
    targetRole: String,
    arguments: List<Pair<String, OperationArgumentKind>>,
): XmlOperationDescriptor =
    XmlOperationDescriptor(
        FormatOperationId(id, 1),
        targetRole,
        arguments,
        OperationSupport.Supported,
    )

/** The frozen eight descriptor records (operation_registry.rs). */
private fun descriptors(): List<XmlOperationDescriptor> =
    listOf(
        descriptor(
            "xml.edit.replace-text",
            "xml.text",
            listOf("text" to OperationArgumentKind.String),
        ),
        descriptor(
            "xml.edit.insert-attribute",
            "xml.element",
            listOf(
                "name" to OperationArgumentKind.String,
                "value" to OperationArgumentKind.String,
                "placement" to OperationArgumentKind.Placement,
            ),
        ),
        descriptor(
            "xml.edit.remove-attribute",
            "xml.attribute",
            emptyList(),
        ),
        descriptor(
            "xml.edit.rename-attribute",
            "xml.attribute",
            listOf("name" to OperationArgumentKind.String),
        ),
        descriptor(
            "xml.edit.set-attribute-value",
            "xml.attribute",
            listOf("value" to OperationArgumentKind.String),
        ),
        descriptor(
            "xml.edit.insert-element",
            "xml.element",
            listOf(
                "name" to OperationArgumentKind.String,
                "content" to OperationArgumentKind.String,
                "placement" to OperationArgumentKind.Placement,
            ),
        ),
        descriptor(
            "xml.edit.remove-element",
            "xml.element",
            emptyList(),
        ),
        descriptor(
            "xml.edit.rename-element",
            "xml.element",
            listOf("name" to OperationArgumentKind.String),
        ),
    )
