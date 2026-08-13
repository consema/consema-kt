// The operator validation table and semantic argument checks.
//
// Data authority: consema-rs/consema-core/src/query.rs:899-1897 (the operator
// rows: expected input role, output role, argument value kinds; the
// argument-value semantic checks at query.rs:1634-1897; the kind
// vocabularies at query.rs:1900-2209). Every row and vocabulary spelling is
// transcribed VERBATIM as data; the semantic checks follow in the Rust
// order. consema-go/go/protocol/query_validate.go is a cross-reference.

package consema.protocol

import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvString
import consema.core.PortableValue

/** One operator argument's required value kind name. */
internal data class ArgSpec(val name: String, val kind: String)

/** One operator row: expected input role, output role, and argument
 * kinds. */
internal data class OperatorSpec(
    val expected: MatchRole,
    val output: MatchRole,
    val arguments: List<ArgSpec> = emptyList(),
)

// The argument value-kind spellings used by the table (the
// PortableValueKind names of consema-core, including kinds outside the
// Kotlin value model; definitions carrying them cannot be constructed
// here).
private const val KIND_STRING = "String"
private const val KIND_BOOLEAN = "Boolean"
private const val KIND_INTEGER = "Integer"
private const val KIND_BYTES = "Bytes"

/**
 * The operator table mapping "(domain, operator)" to its validation row.
 * The generic rows (core.take, core.distinct-by-identity) are
 * domain-agnostic and resolved in [validateOperator].
 */
internal val operatorTable: Map<String, OperatorSpec> = buildMap {
    // core.portable-value-query@1
    put("core.portable-value-query/core.try-object-entries", OperatorSpec(Roles.VALUE, Roles.OBJECT_ENTRY))
    put("core.portable-value-query/core.object-entry-value", OperatorSpec(Roles.OBJECT_ENTRY, Roles.VALUE))
    put(
        "core.portable-value-query/core.object-entry-name-equals",
        OperatorSpec(Roles.OBJECT_ENTRY, Roles.OBJECT_ENTRY, listOf(ArgSpec("name", KIND_STRING))),
    )
    put("core.portable-value-query/core.try-entry-mapping-entries", OperatorSpec(Roles.VALUE, Roles.ENTRY_MAPPING_ENTRY))
    put("core.portable-value-query/core.entry-key", OperatorSpec(Roles.ENTRY_MAPPING_ENTRY, Roles.VALUE))
    put("core.portable-value-query/core.entry-value", OperatorSpec(Roles.ENTRY_MAPPING_ENTRY, Roles.VALUE))
    put("core.portable-value-query/core.try-sequence-elements", OperatorSpec(Roles.VALUE, Roles.VALUE))
    put(
        "core.portable-value-query/core.where-type",
        OperatorSpec(Roles.VALUE, Roles.VALUE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "core.portable-value-query/core.require-type",
        OperatorSpec(Roles.VALUE, Roles.VALUE, listOf(ArgSpec("kind", KIND_STRING))),
    )

    // json.native-semantic-query@1|2
    put("json.native-semantic-query/json.try-object-members", OperatorSpec(Roles.JSON_VALUE, Roles.JSON_OBJECT_MEMBER))
    put(
        "json.native-semantic-query/json.member-name-equals",
        OperatorSpec(Roles.JSON_OBJECT_MEMBER, Roles.JSON_OBJECT_MEMBER, listOf(ArgSpec("name", KIND_STRING))),
    )
    put("json.native-semantic-query/json.member-value", OperatorSpec(Roles.JSON_OBJECT_MEMBER, Roles.JSON_VALUE))
    put("json.native-semantic-query/json.try-array-elements", OperatorSpec(Roles.JSON_VALUE, Roles.JSON_ARRAY_ELEMENT))
    put("json.native-semantic-query/json.array-element-value", OperatorSpec(Roles.JSON_ARRAY_ELEMENT, Roles.JSON_VALUE))

    // toml.native-semantic-query@1
    put("toml.native-semantic-query/toml.try-table-entries", OperatorSpec(Roles.TOML_ITEM, Roles.TOML_ENTRY))
    put(
        "toml.native-semantic-query/toml.entry-name-equals",
        OperatorSpec(Roles.TOML_ENTRY, Roles.TOML_ENTRY, listOf(ArgSpec("name", KIND_STRING))),
    )
    put("toml.native-semantic-query/toml.entry-item", OperatorSpec(Roles.TOML_ENTRY, Roles.TOML_ITEM))
    put("toml.native-semantic-query/toml.try-array-elements", OperatorSpec(Roles.TOML_ITEM, Roles.TOML_ARRAY_ELEMENT))
    put("toml.native-semantic-query/toml.array-element-item", OperatorSpec(Roles.TOML_ARRAY_ELEMENT, Roles.TOML_ITEM))

    // yaml.native-semantic-query@1
    put("yaml.native-semantic-query/yaml.documents", OperatorSpec(Roles.YAML_STREAM, Roles.YAML_DOCUMENT))
    put("yaml.native-semantic-query/yaml.document-root", OperatorSpec(Roles.YAML_DOCUMENT, Roles.YAML_NODE))
    put(
        "yaml.native-semantic-query/yaml.where-node-kind",
        OperatorSpec(Roles.YAML_NODE, Roles.YAML_NODE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "yaml.native-semantic-query/yaml.where-tag",
        OperatorSpec(Roles.YAML_NODE, Roles.YAML_NODE, listOf(ArgSpec("tag", KIND_STRING))),
    )
    put(
        "yaml.native-semantic-query/yaml.scalar-canonical-equals",
        OperatorSpec(Roles.YAML_NODE, Roles.YAML_NODE, listOf(ArgSpec("canonical", KIND_STRING))),
    )
    put("yaml.native-semantic-query/yaml.try-sequence-elements", OperatorSpec(Roles.YAML_NODE, Roles.YAML_SEQUENCE_ELEMENT))
    put("yaml.native-semantic-query/yaml.sequence-element-node", OperatorSpec(Roles.YAML_SEQUENCE_ELEMENT, Roles.YAML_NODE))
    put("yaml.native-semantic-query/yaml.try-mapping-entries", OperatorSpec(Roles.YAML_NODE, Roles.YAML_MAPPING_ENTRY))
    put("yaml.native-semantic-query/yaml.mapping-entry-key", OperatorSpec(Roles.YAML_MAPPING_ENTRY, Roles.YAML_NODE))
    put("yaml.native-semantic-query/yaml.mapping-entry-value", OperatorSpec(Roles.YAML_MAPPING_ENTRY, Roles.YAML_NODE))
    put("yaml.native-semantic-query/yaml.anchor-definition", OperatorSpec(Roles.YAML_NODE, Roles.YAML_ANCHOR_DEFINITION))
    put("yaml.native-semantic-query/yaml.anchor-node", OperatorSpec(Roles.YAML_ANCHOR_DEFINITION, Roles.YAML_NODE))
    put("yaml.native-semantic-query/yaml.alias-occurrences", OperatorSpec(Roles.YAML_STREAM, Roles.YAML_ALIAS_OCCURRENCE))
    put("yaml.native-semantic-query/yaml.alias-target", OperatorSpec(Roles.YAML_ALIAS_OCCURRENCE, Roles.YAML_NODE))

    // ini.native-semantic-query@1
    put("ini.native-semantic-query/ini.document-sections", OperatorSpec(Roles.INI_DOCUMENT, Roles.INI_SECTION))
    put("ini.native-semantic-query/ini.section-entries", OperatorSpec(Roles.INI_SECTION, Roles.INI_ENTRY))
    put("ini.native-semantic-query/ini.all-entries", OperatorSpec(Roles.INI_DOCUMENT, Roles.INI_ENTRY))
    put("ini.native-semantic-query/ini.entry-section", OperatorSpec(Roles.INI_ENTRY, Roles.INI_SECTION))
    put(
        "ini.native-semantic-query/ini.section-name-equals",
        OperatorSpec(
            Roles.INI_SECTION,
            Roles.INI_SECTION,
            listOf(ArgSpec("name", KIND_STRING), ArgSpec("comparison", KIND_STRING)),
        ),
    )
    put(
        "ini.native-semantic-query/ini.entry-key-equals",
        OperatorSpec(
            Roles.INI_ENTRY,
            Roles.INI_ENTRY,
            listOf(ArgSpec("key", KIND_STRING), ArgSpec("comparison", KIND_STRING)),
        ),
    )
    put(
        "ini.native-semantic-query/ini.entry-value-state-is",
        OperatorSpec(Roles.INI_ENTRY, Roles.INI_ENTRY, listOf(ArgSpec("state", KIND_STRING))),
    )
    // ini.duplicate-group is the input-dependent row (RoleAny placeholder);
    // checkInputDependentRoles types it by the input role (query.rs:1056-1065).
    put("ini.native-semantic-query/ini.duplicate-group", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put("ini.native-semantic-query/ini.physical-lines", OperatorSpec(Roles.INI_DOCUMENT, Roles.INI_PHYSICAL_LINE))
    put("ini.native-semantic-query/ini.logical-lines", OperatorSpec(Roles.INI_DOCUMENT, Roles.INI_LOGICAL_LINE))

    // java-properties.native-semantic-query@1
    put(
        "java-properties.native-semantic-query/properties.document-properties",
        OperatorSpec(Roles.PROPERTIES_DOCUMENT, Roles.PROPERTIES_PROPERTY),
    )
    put(
        "java-properties.native-semantic-query/properties.natural-lines",
        OperatorSpec(Roles.PROPERTIES_DOCUMENT, Roles.PROPERTIES_NATURAL_LINE),
    )
    put(
        "java-properties.native-semantic-query/properties.logical-lines",
        OperatorSpec(Roles.PROPERTIES_DOCUMENT, Roles.PROPERTIES_LOGICAL_LINE),
    )
    put(
        "java-properties.native-semantic-query/properties.logical-line-natural-lines",
        OperatorSpec(Roles.PROPERTIES_LOGICAL_LINE, Roles.PROPERTIES_NATURAL_LINE),
    )
    put(
        "java-properties.native-semantic-query/properties.property-key-equals",
        OperatorSpec(Roles.PROPERTIES_PROPERTY, Roles.PROPERTIES_PROPERTY, listOf(ArgSpec("key", KIND_BYTES))),
    )
    put(
        "java-properties.native-semantic-query/properties.property-value-state-is",
        OperatorSpec(Roles.PROPERTIES_PROPERTY, Roles.PROPERTIES_PROPERTY, listOf(ArgSpec("state", KIND_STRING))),
    )
    put(
        "java-properties.native-semantic-query/properties.property-escapes",
        OperatorSpec(Roles.PROPERTIES_PROPERTY, Roles.PROPERTIES_ESCAPE),
    )
    put(
        "java-properties.native-semantic-query/properties.duplicate-group",
        OperatorSpec(Roles.PROPERTIES_PROPERTY, Roles.PROPERTIES_PROPERTY),
    )

    // json.lossless-syntax-query@1|2
    put(
        "json.lossless-syntax-query/json.syntax-kind-is",
        OperatorSpec(Roles.JSON_SYNTAX_PIECE, Roles.JSON_SYNTAX_PIECE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "json.lossless-syntax-query/json.syntax-text-equals",
        OperatorSpec(Roles.JSON_SYNTAX_PIECE, Roles.JSON_SYNTAX_PIECE, listOf(ArgSpec("text", KIND_STRING))),
    )

    // toml.lossless-syntax-query@1
    put(
        "toml.lossless-syntax-query/toml.syntax-kind-is",
        OperatorSpec(Roles.TOML_SYNTAX_PIECE, Roles.TOML_SYNTAX_PIECE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "toml.lossless-syntax-query/toml.syntax-text-equals",
        OperatorSpec(Roles.TOML_SYNTAX_PIECE, Roles.TOML_SYNTAX_PIECE, listOf(ArgSpec("text", KIND_STRING))),
    )

    // yaml.lossless-syntax-query@1
    put(
        "yaml.lossless-syntax-query/yaml.syntax-kind-is",
        OperatorSpec(Roles.YAML_SYNTAX_PIECE, Roles.YAML_SYNTAX_PIECE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "yaml.lossless-syntax-query/yaml.syntax-text-equals",
        OperatorSpec(Roles.YAML_SYNTAX_PIECE, Roles.YAML_SYNTAX_PIECE, listOf(ArgSpec("text", KIND_STRING))),
    )

    // ini.lossless-syntax-query@1
    put(
        "ini.lossless-syntax-query/ini.syntax-kind-is",
        OperatorSpec(Roles.INI_SYNTAX_PIECE, Roles.INI_SYNTAX_PIECE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "ini.lossless-syntax-query/ini.syntax-text-equals",
        OperatorSpec(Roles.INI_SYNTAX_PIECE, Roles.INI_SYNTAX_PIECE, listOf(ArgSpec("text", KIND_STRING))),
    )

    // java-properties.lossless-syntax-query@1
    put(
        "java-properties.lossless-syntax-query/properties.syntax-kind-is",
        OperatorSpec(Roles.PROPERTIES_SYNTAX_PIECE, Roles.PROPERTIES_SYNTAX_PIECE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "java-properties.lossless-syntax-query/properties.syntax-text-equals",
        OperatorSpec(Roles.PROPERTIES_SYNTAX_PIECE, Roles.PROPERTIES_SYNTAX_PIECE, listOf(ArgSpec("text", KIND_STRING))),
    )
    put(
        "java-properties.lossless-syntax-query/properties.syntax-raw-bytes-equals",
        OperatorSpec(Roles.PROPERTIES_SYNTAX_PIECE, Roles.PROPERTIES_SYNTAX_PIECE, listOf(ArgSpec("bytes", KIND_BYTES))),
    )
    put(
        "java-properties.lossless-syntax-query/properties.syntax-utf16be-equals",
        OperatorSpec(Roles.PROPERTIES_SYNTAX_PIECE, Roles.PROPERTIES_SYNTAX_PIECE, listOf(ArgSpec("code_units", KIND_BYTES))),
    )

    // core.portable-graph-query@1
    put("core.portable-graph-query/graph.reachable-nodes", OperatorSpec(Roles.GRAPH_NODE, Roles.GRAPH_NODE))
    put(
        "core.portable-graph-query/graph.where-kind",
        OperatorSpec(Roles.GRAPH_NODE, Roles.GRAPH_NODE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "core.portable-graph-query/graph.where-tag",
        OperatorSpec(Roles.GRAPH_NODE, Roles.GRAPH_NODE, listOf(ArgSpec("tag", KIND_STRING))),
    )
    put(
        "core.portable-graph-query/graph.try-sequence-elements",
        OperatorSpec(Roles.GRAPH_NODE, Roles.GRAPH_SEQUENCE_ELEMENT),
    )
    put(
        "core.portable-graph-query/graph.sequence-element-node",
        OperatorSpec(Roles.GRAPH_SEQUENCE_ELEMENT, Roles.GRAPH_NODE),
    )
    put("core.portable-graph-query/graph.try-mapping-entries", OperatorSpec(Roles.GRAPH_NODE, Roles.GRAPH_MAPPING_ENTRY))
    put("core.portable-graph-query/graph.mapping-entry-key", OperatorSpec(Roles.GRAPH_MAPPING_ENTRY, Roles.GRAPH_NODE))
    put("core.portable-graph-query/graph.mapping-entry-value", OperatorSpec(Roles.GRAPH_MAPPING_ENTRY, Roles.GRAPH_NODE))

    // xml.native-semantic-query@1
    put("xml.native-semantic-query/xml.document-root", OperatorSpec(Roles.XML_DOCUMENT, Roles.XML_ELEMENT))
    put("xml.native-semantic-query/xml.document-declaration", OperatorSpec(Roles.XML_DOCUMENT, Roles.XML_DECLARATION))
    put("xml.native-semantic-query/xml.document-doctype", OperatorSpec(Roles.XML_DOCUMENT, Roles.XML_DOCTYPE))
    put("xml.native-semantic-query/xml.document-prolog", OperatorSpec(Roles.XML_DOCUMENT, Roles.XML_PROLOG_ITEM))
    put("xml.native-semantic-query/xml.document-epilog", OperatorSpec(Roles.XML_DOCUMENT, Roles.XML_PROLOG_ITEM))
    put("xml.native-semantic-query/xml.element-children", OperatorSpec(Roles.XML_ELEMENT, Roles.XML_CONTENT_ITEM))
    put("xml.native-semantic-query/xml.element-child-elements", OperatorSpec(Roles.XML_ELEMENT, Roles.XML_ELEMENT))
    put("xml.native-semantic-query/xml.element-descendants", OperatorSpec(Roles.XML_ELEMENT, Roles.XML_ELEMENT))
    put("xml.native-semantic-query/xml.element-child-text", OperatorSpec(Roles.XML_ELEMENT, Roles.XML_TEXT))
    put("xml.native-semantic-query/xml.element-child-cdata", OperatorSpec(Roles.XML_ELEMENT, Roles.XML_CDATA))
    put("xml.native-semantic-query/xml.element-child-comments", OperatorSpec(Roles.XML_ELEMENT, Roles.XML_COMMENT))
    put(
        "xml.native-semantic-query/xml.element-child-pi",
        OperatorSpec(Roles.XML_ELEMENT, Roles.XML_PROCESSING_INSTRUCTION),
    )
    put("xml.native-semantic-query/xml.element-attributes", OperatorSpec(Roles.XML_ELEMENT, Roles.XML_ATTRIBUTE))
    put(
        "xml.native-semantic-query/xml.element-namespace-bindings",
        OperatorSpec(Roles.XML_ELEMENT, Roles.XML_NAMESPACE_BINDING),
    )
    put(
        "xml.native-semantic-query/xml.element-in-scope-namespaces",
        OperatorSpec(Roles.XML_ELEMENT, Roles.XML_NAMESPACE_BINDING),
    )
    put("xml.native-semantic-query/xml.text-references", OperatorSpec(Roles.XML_TEXT, Roles.XML_REFERENCE))
    put("xml.native-semantic-query/xml.content-parent", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put("xml.native-semantic-query/xml.attribute-element", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put("xml.native-semantic-query/xml.reference-text", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put(
        "xml.native-semantic-query/xml.name-equals",
        OperatorSpec(
            ROLE_ANY,
            ROLE_ANY,
            listOf(
                ArgSpec("prefix", KIND_STRING),
                ArgSpec("local", KIND_STRING),
                ArgSpec("namespace", KIND_STRING),
                ArgSpec("comparison", KIND_STRING),
            ),
        ),
    )
    put(
        "xml.native-semantic-query/xml.attribute-value-equals",
        OperatorSpec(Roles.XML_ATTRIBUTE, Roles.XML_ATTRIBUTE, listOf(ArgSpec("value", KIND_STRING))),
    )
    put(
        "xml.native-semantic-query/xml.pi-target-equals",
        OperatorSpec(
            Roles.XML_PROCESSING_INSTRUCTION,
            Roles.XML_PROCESSING_INSTRUCTION,
            listOf(ArgSpec("target", KIND_STRING)),
        ),
    )
    put(
        "xml.native-semantic-query/xml.reference-kind-is",
        OperatorSpec(Roles.XML_REFERENCE, Roles.XML_REFERENCE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "xml.native-semantic-query/xml.reference-name-equals",
        OperatorSpec(Roles.XML_REFERENCE, Roles.XML_REFERENCE, listOf(ArgSpec("name", KIND_STRING))),
    )
    put(
        "xml.native-semantic-query/xml.node-kind-is",
        OperatorSpec(ROLE_ANY, ROLE_ANY, listOf(ArgSpec("kind", KIND_STRING))),
    )

    // xml.lossless-syntax-query@1
    put(
        "xml.lossless-syntax-query/xml.syntax-kind-is",
        OperatorSpec(Roles.XML_SYNTAX_PIECE, Roles.XML_SYNTAX_PIECE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "xml.lossless-syntax-query/xml.syntax-text-equals",
        OperatorSpec(Roles.XML_SYNTAX_PIECE, Roles.XML_SYNTAX_PIECE, listOf(ArgSpec("text", KIND_STRING))),
    )

    // plist.native-semantic-query@1
    put("plist.native-semantic-query/plist.document-root", OperatorSpec(Roles.PLIST_VALUE, Roles.PLIST_VALUE))
    put("plist.native-semantic-query/plist.dict-entries", OperatorSpec(Roles.PLIST_VALUE, Roles.PLIST_DICT_ENTRY))
    put("plist.native-semantic-query/plist.dict-entry-key", OperatorSpec(Roles.PLIST_DICT_ENTRY, Roles.PLIST_KEY))
    put("plist.native-semantic-query/plist.dict-entry-value", OperatorSpec(Roles.PLIST_DICT_ENTRY, Roles.PLIST_VALUE))
    put(
        "plist.native-semantic-query/plist.dict-key-equals",
        OperatorSpec(Roles.PLIST_DICT_ENTRY, Roles.PLIST_DICT_ENTRY, listOf(ArgSpec("key", KIND_STRING))),
    )
    put(
        "plist.native-semantic-query/plist.duplicate-key-group",
        OperatorSpec(Roles.PLIST_DICT_ENTRY, Roles.PLIST_DICT_ENTRY),
    )
    put("plist.native-semantic-query/plist.array-elements", OperatorSpec(Roles.PLIST_VALUE, Roles.PLIST_ARRAY_ELEMENT))
    put(
        "plist.native-semantic-query/plist.value-type-is",
        OperatorSpec(ROLE_ANY, ROLE_ANY, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put("plist.native-semantic-query/plist.value-as-integer", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put("plist.native-semantic-query/plist.value-as-real", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put("plist.native-semantic-query/plist.value-as-string", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put("plist.native-semantic-query/plist.value-as-data", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put("plist.native-semantic-query/plist.value-as-date", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put("plist.native-semantic-query/plist.value-as-uid", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put(
        "plist.native-semantic-query/plist.value-as-boolean-is",
        OperatorSpec(ROLE_ANY, ROLE_ANY, listOf(ArgSpec("value", KIND_BOOLEAN))),
    )

    // plist.lossless-syntax-query@1
    put(
        "plist.lossless-syntax-query/plist.syntax-kind-is",
        OperatorSpec(Roles.PLIST_SYNTAX_PIECE, Roles.PLIST_SYNTAX_PIECE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "plist.lossless-syntax-query/plist.syntax-text-equals",
        OperatorSpec(Roles.PLIST_SYNTAX_PIECE, Roles.PLIST_SYNTAX_PIECE, listOf(ArgSpec("text", KIND_STRING))),
    )

    // plist.binary-structure-query@1
    put("plist.binary-structure-query/plist.object-table", OperatorSpec(ROLE_ANY, Roles.PLIST_BINARY_OBJECT))
    put("plist.binary-structure-query/plist.object-offset", OperatorSpec(ROLE_ANY, Roles.PLIST_BINARY_OFFSET))
    put("plist.binary-structure-query/plist.object-refs", OperatorSpec(ROLE_ANY, Roles.PLIST_BINARY_REF))
    put("plist.binary-structure-query/plist.offset-table", OperatorSpec(ROLE_ANY, Roles.PLIST_BINARY_OFFSET))
    put("plist.binary-structure-query/plist.trailer-facts", OperatorSpec(ROLE_ANY, Roles.PLIST_BINARY_TRAILER))
    put("plist.binary-structure-query/plist.top-object", OperatorSpec(ROLE_ANY, Roles.PLIST_BINARY_OBJECT))

    // hcl.native-semantic-query@1
    put("hcl.native-semantic-query/hcl.document-body", OperatorSpec(Roles.HCL_BODY, Roles.HCL_BODY))
    put("hcl.native-semantic-query/hcl.body-items", OperatorSpec(Roles.HCL_BODY, Roles.HCL_ATTRIBUTE))
    put("hcl.native-semantic-query/hcl.body-attributes", OperatorSpec(Roles.HCL_BODY, Roles.HCL_ATTRIBUTE))
    put("hcl.native-semantic-query/hcl.body-blocks", OperatorSpec(Roles.HCL_BODY, Roles.HCL_BLOCK))
    put(
        "hcl.native-semantic-query/hcl.body-block-type-equals",
        OperatorSpec(Roles.HCL_BODY, Roles.HCL_BLOCK, listOf(ArgSpec("type", KIND_STRING))),
    )
    put("hcl.native-semantic-query/hcl.attribute-name", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put(
        "hcl.native-semantic-query/hcl.attribute-name-equals",
        OperatorSpec(ROLE_ANY, ROLE_ANY, listOf(ArgSpec("name", KIND_STRING))),
    )
    put("hcl.native-semantic-query/hcl.attribute-expression", OperatorSpec(ROLE_ANY, Roles.HCL_EXPRESSION))
    put(
        "hcl.native-semantic-query/hcl.attribute-literal-value",
        OperatorSpec(ROLE_ANY, ROLE_ANY, listOf(ArgSpec("accessor", KIND_STRING))),
    )
    put("hcl.native-semantic-query/hcl.block-type", OperatorSpec(ROLE_ANY, ROLE_ANY))
    put(
        "hcl.native-semantic-query/hcl.block-type-equals",
        OperatorSpec(ROLE_ANY, ROLE_ANY, listOf(ArgSpec("type", KIND_STRING))),
    )
    put("hcl.native-semantic-query/hcl.block-labels", OperatorSpec(ROLE_ANY, Roles.HCL_BLOCK_LABEL))
    put("hcl.native-semantic-query/hcl.block-nested-body", OperatorSpec(ROLE_ANY, Roles.HCL_BODY))
    put(
        "hcl.native-semantic-query/hcl.block-label-equals",
        OperatorSpec(Roles.HCL_BLOCK_LABEL, Roles.HCL_BLOCK_LABEL, listOf(ArgSpec("label", KIND_STRING))),
    )
    put(
        "hcl.native-semantic-query/hcl.expression-kind-is",
        OperatorSpec(Roles.HCL_EXPRESSION, Roles.HCL_EXPRESSION, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put("hcl.native-semantic-query/hcl.expression-is-literal", OperatorSpec(Roles.HCL_EXPRESSION, Roles.HCL_EXPRESSION))
    put("hcl.native-semantic-query/hcl.expression-text", OperatorSpec(Roles.HCL_EXPRESSION, Roles.HCL_EXPRESSION))
    put("hcl.native-semantic-query/hcl.expression-children", OperatorSpec(Roles.HCL_EXPRESSION, Roles.HCL_EXPRESSION))
    put("hcl.native-semantic-query/hcl.template-parts", OperatorSpec(Roles.HCL_EXPRESSION, Roles.HCL_TEMPLATE_PART))
    put("hcl.native-semantic-query/hcl.tuple-elements", OperatorSpec(Roles.HCL_EXPRESSION, Roles.HCL_EXPRESSION))
    put("hcl.native-semantic-query/hcl.object-entries", OperatorSpec(Roles.HCL_EXPRESSION, Roles.HCL_EXPRESSION))
    put("hcl.native-semantic-query/hcl.error-regions", OperatorSpec(ROLE_ANY, Roles.HCL_ERROR_REGION))

    // hcl.lossless-syntax-query@1
    put(
        "hcl.lossless-syntax-query/hcl.syntax-kind-is",
        OperatorSpec(Roles.HCL_SYNTAX_PIECE, Roles.HCL_SYNTAX_PIECE, listOf(ArgSpec("kind", KIND_STRING))),
    )
    put(
        "hcl.lossless-syntax-query/hcl.syntax-text-equals",
        OperatorSpec(Roles.HCL_SYNTAX_PIECE, Roles.HCL_SYNTAX_PIECE, listOf(ArgSpec("text", KIND_STRING))),
    )
}

/**
 * Validates one operator call against its domain and input role
 * (query.rs:899-1897). The semantic argument checks (kind-name
 * vocabularies, non-empty tags, state sets) mirror the Rust checks in
 * order. Returns the output role.
 */
internal fun validateOperator(
    domain: QueryDomain,
    operator: OperatorCall,
    input: MatchRole,
): MatchRole {
    if (operator.version != 1) {
        throw QueryFailureException(
            QueryFailureKind.UNKNOWN_OPERATOR,
            operator = operator.id,
            version = operator.version,
        )
    }
    val key = "${domain.id}/${operator.id}"
    var spec = operatorTable[key]
    if (spec == null) {
        // The domain-agnostic generic rows.
        spec = when (operator.id) {
            "core.take" -> OperatorSpec(input, input, listOf(ArgSpec("count", KIND_INTEGER)))
            "core.distinct-by-identity" -> OperatorSpec(input, input)
            else -> throw QueryFailureException(
                QueryFailureKind.UNKNOWN_OPERATOR,
                operator = operator.id,
                version = operator.version,
            )
        }
    }
    if (spec.expected != ROLE_ANY && input != spec.expected) {
        throw QueryFailureException(
            QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
            operator = operator.id,
            expectedRole = spec.expected,
            actualRole = input,
        )
    }
    // Input-dependent role rows (they also fix the output role).
    val fixedOutput = checkInputDependentRoles(domain.id, operator.id, input)
    val output = fixedOutput ?: spec.output
    if (operator.arguments.size != spec.arguments.size) {
        throw QueryFailureException(
            QueryFailureKind.INVALID_ARGUMENT,
            operator = operator.id,
            argument = "argument-set",
        )
    }
    for (argument in spec.arguments) {
        val value = operator.arguments[argument.name]
        if (value == null || value.kind.name != argument.kind) {
            throw QueryFailureException(
                QueryFailureKind.WRONG_ARGUMENT_TYPE,
                operator = operator.id,
                argument = argument.name,
                expectedKind = argument.kind,
            )
        }
    }
    // Semantic argument-value checks (query.rs:1634-1897).
    checkOperatorArguments(domain, operator)
    return output
}

/** Applies the role-union rows that accept several input roles
 * (ini.duplicate-group, the XML parent/kind unions, the plist value-
 * operator union, the binary-structure union, and the HCL attribute/block
 * union). Each handled row also fixes the output role, mirroring the Rust
 * rows; returns the fixed output role, or null when the row does not fix
 * it. */
private fun checkInputDependentRoles(
    domainId: String,
    operatorId: String,
    input: MatchRole,
): MatchRole? = when {
        domainId == "ini.native-semantic-query" && operatorId == "ini.duplicate-group" -> {
            if (input != Roles.INI_SECTION && input != Roles.INI_ENTRY) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
                    operator = operatorId,
                    expectedRole = Roles.INI_SECTION,
                    actualRole = input,
                )
            }
            input
        }
        domainId == "xml.native-semantic-query" &&
            (operatorId == "xml.content-parent" ||
                operatorId == "xml.attribute-element" ||
                operatorId == "xml.reference-text") -> {
            if (!xmlContentInputRoles(input)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
                    operator = operatorId,
                    expectedRole = Roles.XML_CONTENT_ITEM,
                    actualRole = input,
                )
            }
            Roles.XML_ELEMENT
        }
        domainId == "xml.native-semantic-query" && operatorId == "xml.name-equals" -> input
        domainId == "xml.native-semantic-query" && operatorId == "xml.node-kind-is" -> {
            if (!xmlNodeKindRoles(input)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
                    operator = operatorId,
                    expectedRole = Roles.XML_DOCUMENT,
                    actualRole = input,
                )
            }
            input
        }
        domainId == "plist.native-semantic-query" &&
            (operatorId == "plist.value-type-is" ||
                operatorId == "plist.value-as-integer" ||
                operatorId == "plist.value-as-real" ||
                operatorId == "plist.value-as-string" ||
                operatorId == "plist.value-as-data" ||
                operatorId == "plist.value-as-date" ||
                operatorId == "plist.value-as-uid" ||
                operatorId == "plist.value-as-boolean-is") -> {
            if (input != Roles.PLIST_VALUE && input != Roles.PLIST_ARRAY_ELEMENT) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
                    operator = operatorId,
                    expectedRole = Roles.PLIST_VALUE,
                    actualRole = input,
                )
            }
            input
        }
        domainId == "plist.binary-structure-query" -> {
            // The structure facts are document-level; every operator accepts
            // any binary-structure match as input so that chains of
            // structure operators validate (query.rs:1406-1442). The table
            // row already pins the operator's output role.
            if (!plistBinaryInputRoles(input)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
                    operator = operatorId,
                    expectedRole = Roles.PLIST_BINARY_STRUCTURE,
                    actualRole = input,
                )
            }
            null
        }
        domainId == "hcl.native-semantic-query" &&
            (operatorId == "hcl.attribute-name" ||
                operatorId == "hcl.attribute-name-equals" ||
                operatorId == "hcl.block-type" ||
                operatorId == "hcl.block-type-equals") -> {
            if (input != Roles.HCL_ATTRIBUTE && input != Roles.HCL_BLOCK) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
                    operator = operatorId,
                    expectedRole = Roles.HCL_ATTRIBUTE,
                    actualRole = input,
                )
            }
            input
        }
        domainId == "hcl.native-semantic-query" && operatorId == "hcl.attribute-literal-value" -> {
            if (input != Roles.HCL_EXPRESSION && input != Roles.HCL_ATTRIBUTE) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
                    operator = operatorId,
                    expectedRole = Roles.HCL_EXPRESSION,
                    actualRole = input,
                )
            }
            input
        }
        domainId == "hcl.native-semantic-query" &&
            (operatorId == "hcl.attribute-expression" ||
                operatorId == "hcl.block-labels" ||
                operatorId == "hcl.block-nested-body") -> {
            if (input != Roles.HCL_ATTRIBUTE && input != Roles.HCL_BLOCK) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
                    operator = operatorId,
                    expectedRole = Roles.HCL_ATTRIBUTE,
                    actualRole = input,
                )
            }
            null
        }
        domainId == "hcl.native-semantic-query" && operatorId == "hcl.error-regions" -> {
            if (!hclErrorRegionInputRoles(input)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
                    operator = operatorId,
                    expectedRole = Roles.HCL_BODY,
                    actualRole = input,
                )
            }
            Roles.HCL_ERROR_REGION
        }
        else -> null
    }

private fun xmlContentInputRoles(input: MatchRole): Boolean = when (input) {
    Roles.XML_CONTENT_ITEM, Roles.XML_ATTRIBUTE, Roles.XML_NAMESPACE_BINDING, Roles.XML_REFERENCE,
    Roles.XML_ELEMENT, Roles.XML_TEXT, Roles.XML_CDATA, Roles.XML_COMMENT, Roles.XML_PROCESSING_INSTRUCTION ->
        true
    else -> false
}

private fun xmlNodeKindRoles(input: MatchRole): Boolean = when (input) {
    Roles.XML_DOCUMENT, Roles.XML_DECLARATION, Roles.XML_DOCTYPE, Roles.XML_PROLOG_ITEM,
    Roles.XML_ELEMENT, Roles.XML_CONTENT_ITEM, Roles.XML_ATTRIBUTE, Roles.XML_NAMESPACE_BINDING,
    Roles.XML_TEXT, Roles.XML_CDATA, Roles.XML_COMMENT, Roles.XML_PROCESSING_INSTRUCTION,
    Roles.XML_REFERENCE, Roles.XML_ERROR_REGION ->
        true
    else -> false
}

private fun plistBinaryInputRoles(input: MatchRole): Boolean = when (input) {
    Roles.PLIST_BINARY_STRUCTURE, Roles.PLIST_BINARY_OBJECT, Roles.PLIST_BINARY_OFFSET,
    Roles.PLIST_BINARY_REF, Roles.PLIST_BINARY_TRAILER ->
        true
    else -> false
}

private fun hclErrorRegionInputRoles(input: MatchRole): Boolean = when (input) {
    Roles.HCL_BODY, Roles.HCL_ATTRIBUTE, Roles.HCL_BLOCK, Roles.HCL_BLOCK_LABEL,
    Roles.HCL_EXPRESSION, Roles.HCL_TEMPLATE_PART, Roles.HCL_ERROR_REGION ->
        true
    else -> false
}

/** Applies the semantic argument-value checks of the Rust validator
 * (query.rs:1634-1897), in order. */
private fun checkOperatorArguments(domain: QueryDomain, operator: OperatorCall) {
    fun stringArg(name: String): String? {
        val value = operator.arguments[name] ?: return null
        return (value as? PvString)?.value
    }
    when (operator.id) {
        "core.take" -> {
            // The argument-set check guarantees the Integer kind.
            val number = (operator.arguments["count"] as PvInteger).value
            if (number.signum() < 0 || number.bitLength() > 63) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "count",
                )
            }
        }
        "core.where-type", "core.require-type" -> {
            val kind = stringArg("kind") ?: ""
            if (!isValueKindName(kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = "value-kind",
                    argument = kind,
                )
            }
        }
        "json.syntax-kind-is" -> {
            val kind = stringArg("kind") ?: ""
            if (!isJsonSyntaxKind(domain.version, kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "toml.syntax-kind-is" -> {
            val kind = stringArg("kind") ?: ""
            if (!isTomlSyntaxKind(kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "yaml.syntax-kind-is" -> {
            val kind = stringArg("kind") ?: ""
            if (!isYamlSyntaxKind(kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "ini.syntax-kind-is" -> {
            val kind = stringArg("kind") ?: ""
            if (!isIniSyntaxKind(kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "properties.syntax-kind-is" -> {
            val kind = stringArg("kind") ?: ""
            if (!isPropertiesSyntaxKind(kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "xml.syntax-kind-is" -> {
            val kind = stringArg("kind") ?: ""
            if (!isXmlSyntaxKind(kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "plist.value-type-is" -> {
            val kind = stringArg("kind") ?: ""
            if (!isPlistValueKind(kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "plist.syntax-kind-is" -> {
            val kind = stringArg("kind") ?: ""
            if (!isPlistSyntaxKind(kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "hcl.expression-kind-is" -> {
            val kind = stringArg("kind") ?: ""
            if (!isHclExpressionKind(kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "hcl.syntax-kind-is" -> {
            val kind = stringArg("kind") ?: ""
            if (!isHclSyntaxKind(kind)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "hcl.attribute-literal-value" -> {
            val accessor = stringArg("accessor") ?: ""
            if (!isHclLiteralAccessor(accessor)) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "accessor",
                )
            }
        }
        "properties.property-key-equals", "properties.syntax-utf16be-equals" -> {
            // The Bytes-typed arguments are validated against the
            // language-neutral argument-kind vocabulary; the even-length
            // check is transcribed verbatim for parity (the `UTF16BE/1`
            // argument must carry a whole number of code units).
            val name = if (operator.id == "properties.syntax-utf16be-equals") "code_units" else "key"
            val value = operator.arguments[name]
            val bytes = value as? PvBytes
            if (bytes == null || bytes.content().size % 2 != 0) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = name,
                )
            }
        }
        "properties.property-value-state-is" -> {
            val state = stringArg("state") ?: ""
            if (state != "ImplicitEmpty" && state != "ExplicitEmpty" && state != "Present") {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "state",
                )
            }
        }
        "ini.section-name-equals", "ini.entry-key-equals" -> {
            val comparison = stringArg("comparison") ?: ""
            if (comparison != "OriginalExact" && comparison != "ProfileEquivalent") {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "comparison",
                )
            }
        }
        "ini.entry-value-state-is" -> {
            val state = stringArg("state") ?: ""
            if (state != "Missing" && state != "Empty" && state != "Present") {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "state",
                )
            }
        }
        "yaml.where-node-kind" -> {
            val kind = stringArg("kind") ?: ""
            if (kind != "Scalar" && kind != "Sequence" && kind != "Mapping") {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "yaml.where-tag" -> {
            val tag = stringArg("tag") ?: ""
            if (tag.isEmpty()) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "tag",
                )
            }
        }
        "graph.where-kind" -> {
            val kind = stringArg("kind") ?: ""
            if (kind != "Scalar" && kind != "Sequence" && kind != "Mapping") {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "kind",
                )
            }
        }
        "graph.where-tag" -> {
            val tag = stringArg("tag") ?: ""
            if (tag.isEmpty()) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_ARGUMENT,
                    operator = operator.id,
                    argument = "tag",
                )
            }
        }
    }
}

/** Accepts the frozen fifteen-kind vocabulary of the value-kind arguments
 * (query.rs:2187-2209), matching the closed core model. */
internal fun isValueKindName(kind: String): Boolean = when (kind) {
    "Null", "Boolean", "Integer", "Decimal", "BinaryFloat32", "BinaryFloat64",
    "String", "Bytes", "Date", "Time", "LocalDateTime", "OffsetDateTime",
    "Sequence", "Object", "EntryMapping" ->
        true
    else -> false
}

// The frozen syntax-kind and value-kind vocabularies (query.rs:1900-2185).
// Spellings are language-neutral and byte-exact.

private fun isJsonSyntaxKind(domainVersion: Int, kind: String): Boolean = when (kind) {
    "Bom", "Whitespace", "LineComment", "BlockComment", "LeftBrace", "RightBrace",
    "LeftBracket", "RightBracket", "Colon", "Comma", "String", "Number",
    "True", "False", "Null", "ErrorRegion" ->
        true
    else -> domainVersion == 2 && kind == "Identifier"
}

private fun isTomlSyntaxKind(kind: String): Boolean = when (kind) {
    "Whitespace", "Newline", "Comment", "String", "Bare", "Equals",
    "LeftBracket", "RightBracket", "LeftBrace", "RightBrace", "Comma", "Dot" ->
        true
    else -> false
}

private fun isYamlSyntaxKind(kind: String): Boolean = when (kind) {
    "Bom", "Whitespace", "Newline", "Comment", "Directive", "DocumentStart",
    "DocumentEnd", "FlowSequenceStart", "FlowSequenceEnd", "FlowMappingStart",
    "FlowMappingEnd", "FlowEntry", "SequenceEntry", "ExplicitKey", "MappingValue",
    "Anchor", "Alias", "Tag", "PlainScalar", "SingleQuotedScalar",
    "DoubleQuotedScalar", "LiteralBlockHeader", "FoldedBlockHeader",
    "BlockScalarContent", "ErrorRegion" ->
        true
    else -> false
}

private fun isIniSyntaxKind(kind: String): Boolean = when (kind) {
    "Bom", "Whitespace", "LineBreak", "CommentMarker", "CommentText",
    "SectionOpen", "SectionName", "SectionClose", "EntryKey", "Delimiter",
    "Quote", "EntryValue", "ContinuationMarker", "ErrorRegion" ->
        true
    else -> false
}

private fun isPropertiesSyntaxKind(kind: String): Boolean = when (kind) {
    "Bom", "Whitespace", "LineBreak", "CommentMarker", "CommentText",
    "Key", "Separator", "Value", "EscapeMarker", "EscapeBody",
    "ContinuationMarker", "ErrorRegion" ->
        true
    else -> false
}

private fun isXmlSyntaxKind(kind: String): Boolean = when (kind) {
    "bom", "whitespace", "line-break", "declaration-open", "declaration-name",
    "declaration-value", "declaration-close", "doctype-open", "doctype-name",
    "dtd-markup", "doctype-close", "tag-open", "tag-close",
    "empty-element-close", "end-tag-open", "prefix", "local-name", "colon",
    "attribute-name", "equals", "quote", "attribute-value",
    "namespace-declaration", "text", "entity-reference", "character-reference",
    "cdata-open", "cdata-text", "cdata-close", "comment-open", "comment-text",
    "comment-close", "processing-instruction-open",
    "processing-instruction-target", "processing-instruction-content",
    "processing-instruction-close", "error-region" ->
        true
    else -> false
}

private fun isPlistValueKind(kind: String): Boolean = when (kind) {
    "dict", "array", "string", "integer", "real", "boolean", "date", "data", "uid" ->
        true
    else -> false
}

private fun isPlistSyntaxKind(kind: String): Boolean = when (kind) {
    "bom", "whitespace", "line-break", "declaration-open", "declaration-name",
    "declaration-value", "declaration-close", "doctype-open", "doctype-body",
    "doctype-close", "plist-open", "plist-version-name", "plist-version-value",
    "plist-close", "dict-open", "dict-close", "key-open", "key-close",
    "array-open", "array-close", "string-open", "string-close", "integer-open",
    "integer-close", "real-open", "real-close", "date-open", "date-close",
    "data-open", "data-close", "true", "false", "text", "entity-reference",
    "character-reference", "cdata-open", "cdata-text", "cdata-close",
    "comment-open", "comment-text", "comment-close",
    "processing-instruction-open", "processing-instruction-target",
    "processing-instruction-content", "processing-instruction-close",
    "error-region" ->
        true
    else -> false
}

private fun isHclExpressionKind(kind: String): Boolean = when (kind) {
    "number", "boolean", "null", "template", "function-call", "variable-ref",
    "traversal", "unary", "binary", "conditional", "for-tuple", "for-object",
    "tuple", "object", "parenthesized" ->
        true
    else -> false
}

private fun isHclSyntaxKind(kind: String): Boolean = when (kind) {
    "Whitespace", "LineBreak", "LineComment", "InlineComment", "Identifier",
    "Equals", "Number", "StringOpen", "StringContent", "StringClose",
    "InterpolationOpen", "InterpolationContent", "InterpolationClose",
    "DirectiveOpen", "DirectiveContent", "DirectiveClose", "HeredocOpen",
    "HeredocContent", "HeredocClose", "BraceOpen", "BraceClose", "BracketOpen",
    "BracketClose", "ParenOpen", "ParenClose", "Comma", "Colon", "QuestionMark",
    "Operator", "ErrorRegion" ->
        true
    else -> false
}

private fun isHclLiteralAccessor(accessor: String): Boolean = when (accessor) {
    "as-string", "as-integer", "as-real", "as-boolean-is", "as-null-is" ->
        true
    else -> false
}
