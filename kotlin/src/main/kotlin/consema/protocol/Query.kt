// Versioned typed query definitions and their validation/binding.
//
// Data authority: RFC 0016 §5.4 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md:189-192)
// and https://github.com/consema/consema-rs/blob/main/consema-core/src/query.rs (the domain/operator tables and the
// operator validation contract). The domain ids and operator ids are pinned
// spellings, not invented names; the operator table is transcribed as data
// in QueryValidate.kt. consema-go/go/protocol/query.go is a cross-reference.

package consema.protocol

import consema.core.PvArray
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import java.math.BigInteger

/** One typed match role of the query model. The roles use the
 * language-neutral spelling of the Rust MatchRole enum
 * (consema-core/src/query.rs:169-316); they type operator composition during
 * validation and name the output matches of query results. */
typealias MatchRole = String

/** The closed match-role vocabulary. */
object Roles {
    const val VALUE = "Value"
    const val OBJECT_ENTRY = "ObjectEntry"
    const val ENTRY_MAPPING_ENTRY = "EntryMappingEntry"
    const val JSON_VALUE = "JsonValue"
    const val JSON_OBJECT_MEMBER = "JsonObjectMember"
    const val JSON_ARRAY_ELEMENT = "JsonArrayElement"
    const val TOML_ITEM = "TomlItem"
    const val TOML_ENTRY = "TomlEntry"
    const val TOML_ARRAY_ELEMENT = "TomlArrayElement"
    const val YAML_STREAM = "YamlStream"
    const val YAML_DOCUMENT = "YamlDocument"
    const val YAML_NODE = "YamlNode"
    const val YAML_MAPPING_ENTRY = "YamlMappingEntry"
    const val YAML_SEQUENCE_ELEMENT = "YamlSequenceElement"
    const val YAML_ANCHOR_DEFINITION = "YamlAnchorDefinition"
    const val YAML_ALIAS_OCCURRENCE = "YamlAliasOccurrence"
    const val JSON_SYNTAX_PIECE = "JsonSyntaxPiece"
    const val TOML_SYNTAX_PIECE = "TomlSyntaxPiece"
    const val YAML_SYNTAX_PIECE = "YamlSyntaxPiece"
    const val INI_DOCUMENT = "IniDocument"
    const val INI_SECTION = "IniSection"
    const val INI_DEFAULT_SECTION = "IniDefaultSection"
    const val INI_ENTRY = "IniEntry"
    const val INI_PHYSICAL_LINE = "IniPhysicalLine"
    const val INI_LOGICAL_LINE = "IniLogicalLine"
    const val INI_ERROR_LINE = "IniErrorLine"
    const val INI_SYNTAX_PIECE = "IniSyntaxPiece"
    const val PROPERTIES_DOCUMENT = "PropertiesDocument"
    const val PROPERTIES_NATURAL_LINE = "PropertiesNaturalLine"
    const val PROPERTIES_LOGICAL_LINE = "PropertiesLogicalLine"
    const val PROPERTIES_PROPERTY = "PropertiesProperty"
    const val PROPERTIES_COMMENT = "PropertiesComment"
    const val PROPERTIES_ESCAPE = "PropertiesEscape"
    const val PROPERTIES_ERROR_LINE = "PropertiesErrorLine"
    const val PROPERTIES_SYNTAX_PIECE = "PropertiesSyntaxPiece"
    const val GRAPH_NODE = "GraphNode"
    const val GRAPH_SEQUENCE_ELEMENT = "GraphSequenceElement"
    const val GRAPH_MAPPING_ENTRY = "GraphMappingEntry"
    const val XML_DOCUMENT = "XmlDocument"
    const val XML_DECLARATION = "XmlDeclaration"
    const val XML_DOCTYPE = "XmlDoctype"
    const val XML_PROLOG_ITEM = "XmlPrologItem"
    const val XML_ELEMENT = "XmlElement"
    const val XML_CONTENT_ITEM = "XmlContentItem"
    const val XML_ATTRIBUTE = "XmlAttribute"
    const val XML_NAMESPACE_BINDING = "XmlNamespaceBinding"
    const val XML_TEXT = "XmlText"
    const val XML_CDATA = "XmlCdata"
    const val XML_COMMENT = "XmlComment"
    const val XML_PROCESSING_INSTRUCTION = "XmlProcessingInstruction"
    const val XML_REFERENCE = "XmlReference"
    const val XML_ERROR_REGION = "XmlErrorRegion"
    const val XML_SYNTAX_PIECE = "XmlSyntaxPiece"
    const val PLIST_VALUE = "PlistValue"
    const val PLIST_DICT_ENTRY = "PlistDictEntry"
    const val PLIST_KEY = "PlistKey"
    const val PLIST_ARRAY_ELEMENT = "PlistArrayElement"
    const val PLIST_SYNTAX_PIECE = "PlistSyntaxPiece"
    const val PLIST_BINARY_STRUCTURE = "PlistBinaryStructure"
    const val PLIST_BINARY_OBJECT = "PlistBinaryObject"
    const val PLIST_BINARY_OFFSET = "PlistBinaryOffset"
    const val PLIST_BINARY_REF = "PlistBinaryRef"
    const val PLIST_BINARY_TRAILER = "PlistBinaryTrailer"
    const val HCL_BODY = "HclBody"
    const val HCL_ATTRIBUTE = "HclAttribute"
    const val HCL_BLOCK = "HclBlock"
    const val HCL_BLOCK_LABEL = "HclBlockLabel"
    const val HCL_EXPRESSION = "HclExpression"
    const val HCL_TEMPLATE_PART = "HclTemplatePart"
    const val HCL_ERROR_REGION = "HclErrorRegion"
    const val HCL_SYNTAX_PIECE = "HclSyntaxPiece"
}

/** The table placeholder for input-dependent operator rows; the per-
 * operator role checks replace it. */
internal const val ROLE_ANY: MatchRole = ""

/** A versioned query domain (consema-core/src/query.rs:12-166). */
data class QueryDomain(val id: String, val version: Int)

/** The frozen domain constructors (query.rs:30-153). */
object Domains {
    fun portableValueV1(): QueryDomain = QueryDomain("core.portable-value-query", 1)
    fun portableGraphV1(): QueryDomain = QueryDomain("core.portable-graph-query", 1)
    fun jsonNativeV1(): QueryDomain = QueryDomain("json.native-semantic-query", 1)
    fun jsonNativeV2(): QueryDomain = QueryDomain("json.native-semantic-query", 2)
    fun tomlNativeV1(): QueryDomain = QueryDomain("toml.native-semantic-query", 1)
    fun yamlNativeV1(): QueryDomain = QueryDomain("yaml.native-semantic-query", 1)
    fun iniNativeV1(): QueryDomain = QueryDomain("ini.native-semantic-query", 1)
    fun javaPropertiesNativeV1(): QueryDomain = QueryDomain("java-properties.native-semantic-query", 1)
    fun xmlNativeV1(): QueryDomain = QueryDomain("xml.native-semantic-query", 1)
    fun jsonLosslessSyntaxV1(): QueryDomain = QueryDomain("json.lossless-syntax-query", 1)
    fun jsonLosslessSyntaxV2(): QueryDomain = QueryDomain("json.lossless-syntax-query", 2)
    fun tomlLosslessSyntaxV1(): QueryDomain = QueryDomain("toml.lossless-syntax-query", 1)
    fun yamlLosslessSyntaxV1(): QueryDomain = QueryDomain("yaml.lossless-syntax-query", 1)
    fun iniLosslessSyntaxV1(): QueryDomain = QueryDomain("ini.lossless-syntax-query", 1)
    fun javaPropertiesLosslessSyntaxV1(): QueryDomain =
        QueryDomain("java-properties.lossless-syntax-query", 1)
    fun xmlLosslessSyntaxV1(): QueryDomain = QueryDomain("xml.lossless-syntax-query", 1)
    fun plistNativeV1(): QueryDomain = QueryDomain("plist.native-semantic-query", 1)
    fun plistLosslessSyntaxV1(): QueryDomain = QueryDomain("plist.lossless-syntax-query", 1)
    fun plistBinaryStructureV1(): QueryDomain = QueryDomain("plist.binary-structure-query", 1)
    fun hclNativeV1(): QueryDomain = QueryDomain("hcl.native-semantic-query", 1)
    fun hclLosslessSyntaxV1(): QueryDomain = QueryDomain("hcl.lossless-syntax-query", 1)
}

/**
 * One versioned operator call with deterministic arguments
 * (query.rs:318-361).
 */
class OperatorCall(
    val id: String,
    val version: Int,
) {
    val arguments: LinkedHashMap<String, PortableValue> = LinkedHashMap()

    /** Adds or replaces a named argument. */
    fun withArgument(name: String, value: PortableValue): OperatorCall {
        arguments[name] = value
        return this
    }
}

/** The closed query-expression kind. */
enum class ExpressionKind {
    /** The domain root input. */
    Input,

    /** Applies an operator to an input expression. */
    Apply,

    /** Appends complete branch results in branch order. */
    Concat,

    /** Merges branches by structural identity order. */
    StructureOrderMerge,
}

/**
 * The declarative operator tree (query.rs:363-390).
 */
class QueryExpression(
    val kind: ExpressionKind,
    /** The input expression of an Apply. */
    val input: QueryExpression? = null,
    /** The operator call of an Apply. */
    val operator: OperatorCall? = null,
    /** The branch expressions of Concat/StructureOrderMerge. */
    val branches: List<QueryExpression> = emptyList(),
) {
    /** Applies one operator to the expression (the Rust `then` builder). */
    fun then(operator: OperatorCall): QueryExpression =
        QueryExpression(ExpressionKind.Apply, input = this, operator = operator)
}

/** The cardinality selection applied to the complete standard result
 * sequence (query.rs:434-447). */
enum class QuerySelection(val wireName: String) {
    All("All"),
    First("First"),
    Last("Last"),
    ZeroOrOne("ZeroOrOne"),
    RequireOne("RequireOne"),
}

/**
 * A transferable, not-yet-validated query definition (query.rs:449-598).
 */
class QueryDefinition(
    val domain: QueryDomain,
    var expression: QueryExpression = QueryExpression(ExpressionKind.Input),
    var selection: QuerySelection = QuerySelection.All,
) {
    /** Replaces the expression. */
    fun withExpression(expression: QueryExpression): QueryDefinition {
        this.expression = expression
        return this
    }

    /** Sets the cardinality selection. */
    fun withSelection(selection: QuerySelection): QueryDefinition {
        this.selection = selection
        return this
    }

    /** Validates the domain, argument schemas, composition, and role typing
     * (query.rs:500-530). The required capability set of a validated query
     * is always [core.query.ordered-results@1]. */
    fun validate(): ValidatedQuery {
        val inputRole = domainInputRole(domain.id, domain.version)
            ?: throw QueryFailureException(QueryFailureKind.DOMAIN_MISMATCH, domain = domain)
        val outputRole = validateExpression(domain, expression, inputRole)
        val orderedResults = CapabilityId("core.query.ordered-results", 1)
        return ValidatedQuery(this, outputRole, listOf(orderedResults))
    }

    /** Encodes `core.query-definition@1` through the fixed-field
     * PortableValue schema (query.rs:532-559). */
    fun toProtocolValue(): PortableValue = PvObject(
        listOf(
            consema.core.Entry("schema", PvString("core.query-definition@1")),
            consema.core.Entry("domain_id", PvString(domain.id)),
            consema.core.Entry("domain_version", PvInteger(BigInteger.valueOf(domain.version.toLong()))),
            consema.core.Entry("selection", PvString(selection.wireName)),
            consema.core.Entry("expression", encodeExpression(expression, 0)),
        ),
    )

    companion object {
        /** Strictly decodes `core.query-definition@1` (query.rs:561-598).
         * Unknown, reordered, or missing fields are rejected;
         * structural/operator validation remains the explicit next
         * lifecycle step. */
        fun fromProtocolValue(value: PortableValue): QueryDefinition {
            val fields = exactObjectFields(value, listOf("schema", "domain_id",
                "domain_version", "selection", "expression"), "core.query-definition@1")
            val schema = fields[0] as? PvString
                ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "schema")
            if (schema.value != "core.query-definition@1") {
                throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "schema")
            }
            val domainId = fields[1] as? PvString
                ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "domain_id")
            val domainVersion = queryUnsigned32(fields[2], "domain_version")
            val selectionText = fields[3] as? PvString
                ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "selection")
            val selection = QuerySelection.entries.firstOrNull { it.wireName == selectionText.value }
                ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "selection")
            val expression = decodeExpression(fields[4], 0)
            return QueryDefinition(QueryDomain(domainId.value, domainVersion))
                .withExpression(expression)
                .withSelection(selection)
        }
    }
}

/** A definition proven structurally valid for its domain (query.rs:
 * 768-798). */
class ValidatedQuery internal constructor(
    val definition: QueryDefinition,
    val outputRole: MatchRole,
    val requiredCapabilities: List<CapabilityId>,
)

/** A fully validated and capability-bound query (query.rs:800-865).
 * Execution against PortableValue values is provided by the family
 * packages; this milestone pins the definition surface. */
class ExecutableQuery internal constructor(val validated: ValidatedQuery) {
    /** Binds the validated definition to implementation capabilities
     * (query.rs:789-798). */
    companion object {
        fun bind(validated: ValidatedQuery, capabilities: CapabilitySet): ExecutableQuery {
            for (capability in validated.requiredCapabilities) {
                if (!capabilities.contains(capability)) {
                    throw QueryFailureException(
                        QueryFailureKind.MISSING_CAPABILITY,
                        capability = capability,
                    )
                }
            }
            return ExecutableQuery(validated)
        }
    }
}

/** Maps a domain to its root match role (query.rs:502-523). */
internal fun domainInputRole(id: String, version: Int): MatchRole? = when {
    id == "core.portable-value-query" && version == 1 -> Roles.VALUE
    id == "core.portable-graph-query" && version == 1 -> Roles.GRAPH_NODE
    id == "json.native-semantic-query" && (version == 1 || version == 2) -> Roles.JSON_VALUE
    id == "toml.native-semantic-query" && version == 1 -> Roles.TOML_ITEM
    id == "yaml.native-semantic-query" && version == 1 -> Roles.YAML_STREAM
    id == "ini.native-semantic-query" && version == 1 -> Roles.INI_DOCUMENT
    id == "java-properties.native-semantic-query" && version == 1 -> Roles.PROPERTIES_DOCUMENT
    id == "xml.native-semantic-query" && version == 1 -> Roles.XML_DOCUMENT
    id == "json.lossless-syntax-query" && (version == 1 || version == 2) -> Roles.JSON_SYNTAX_PIECE
    id == "toml.lossless-syntax-query" && version == 1 -> Roles.TOML_SYNTAX_PIECE
    id == "yaml.lossless-syntax-query" && version == 1 -> Roles.YAML_SYNTAX_PIECE
    id == "ini.lossless-syntax-query" && version == 1 -> Roles.INI_SYNTAX_PIECE
    id == "java-properties.lossless-syntax-query" && version == 1 -> Roles.PROPERTIES_SYNTAX_PIECE
    id == "xml.lossless-syntax-query" && version == 1 -> Roles.XML_SYNTAX_PIECE
    id == "plist.native-semantic-query" && version == 1 -> Roles.PLIST_VALUE
    id == "plist.lossless-syntax-query" && version == 1 -> Roles.PLIST_SYNTAX_PIECE
    id == "plist.binary-structure-query" && version == 1 -> Roles.PLIST_BINARY_STRUCTURE
    id == "hcl.native-semantic-query" && version == 1 -> Roles.HCL_BODY
    id == "hcl.lossless-syntax-query" && version == 1 -> Roles.HCL_SYNTAX_PIECE
    else -> null
}

/** Checks the whole operator tree and returns its output role
 * (query.rs:867-897). */
internal fun validateExpression(
    domain: QueryDomain,
    expression: QueryExpression,
    inputRole: MatchRole,
): MatchRole = when (expression.kind) {
    ExpressionKind.Input -> inputRole
    ExpressionKind.Apply -> {
        val actualInput = validateExpression(domain, expression.input!!, inputRole)
        validateOperator(domain, expression.operator!!, actualInput)
    }
    ExpressionKind.Concat, ExpressionKind.StructureOrderMerge -> {
        var output: MatchRole = ""
        for (branch in expression.branches) {
            val branchOutput = validateExpression(domain, branch, inputRole)
            if (output != "" && output != branchOutput) {
                throw QueryFailureException(
                    QueryFailureKind.INVALID_OPERATOR_COMPOSITION,
                    operator = "composition.concat",
                    expectedRole = output,
                    actualRole = branchOutput,
                )
            }
            output = branchOutput
        }
        if (output == "") {
            throw QueryFailureException(
                QueryFailureKind.INVALID_ARGUMENT,
                operator = "composition.concat",
                argument = "branches",
            )
        }
        output
    }
}

/** Encodes one expression node (query.rs:610-656). */
private fun encodeExpression(expression: QueryExpression, depth: Int): PortableValue {
    if (depth > 256) {
        throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
    }
    return when (expression.kind) {
        ExpressionKind.Input -> PvObject(listOf(consema.core.Entry("kind", PvString("Input"))))
        ExpressionKind.Apply -> PvObject(
            listOf(
                consema.core.Entry("kind", PvString("Apply")),
                consema.core.Entry("input", encodeExpression(expression.input!!, depth + 1)),
                consema.core.Entry("operator", encodeOperator(expression.operator!!)),
            ),
        )
        ExpressionKind.Concat, ExpressionKind.StructureOrderMerge -> {
            val kind = if (expression.kind == ExpressionKind.Concat) "Concat" else "StructureOrderMerge"
            val branches = PvArray(expression.branches.map { encodeExpression(it, depth + 1) })
            PvObject(
                listOf(
                    consema.core.Entry("kind", PvString(kind)),
                    consema.core.Entry("branches", branches),
                ),
            )
        }
    }
}

/** Encodes one operator call (query.rs:658-679). */
private fun encodeOperator(operator: OperatorCall): PortableValue {
    val arguments = operator.arguments.toSortedMap().map { (name, value) ->
        consema.core.Entry(name, value)
    }
    return PvObject(
        listOf(
            consema.core.Entry("id", PvString(operator.id)),
            consema.core.Entry("version", PvInteger(BigInteger.valueOf(operator.version.toLong()))),
            consema.core.Entry("arguments", PvObject(arguments)),
        ),
    )
}

/** Strictly validates a fixed-field object (query.rs:736-751). */
private fun exactObjectFields(value: PortableValue, names: List<String>, context: String): List<PortableValue> {
    val objectValue = value as? PvObject
        ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = context)
    val entries = objectValue.entries()
    if (entries.size != names.size) {
        throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = context)
    }
    return entries.mapIndexed { index, entry ->
        if (entry.key != names[index]) {
            throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = context)
        }
        entry.value
    }
}

/** Strictly decodes one expression node (query.rs:681-718). */
private fun decodeExpression(value: PortableValue, depth: Int): QueryExpression {
    if (depth > 256) {
        throw QueryFailureException(QueryFailureKind.RESOURCE_LIMIT)
    }
    val objectValue = value as? PvObject
        ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "expression")
    val entries = objectValue.entries()
    if (entries.isEmpty()) {
        throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "expression.kind")
    }
    val kind = entries[0].value as? PvString
        ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "expression.kind")
    if (entries[0].key != "kind") {
        throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "expression.kind")
    }
    return when (kind.value) {
        "Input" -> {
            if (entries.size != 1) {
                throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "expression.kind")
            }
            QueryExpression(ExpressionKind.Input)
        }
        "Apply" -> {
            val fields = exactObjectFields(value, listOf("kind", "input", "operator"), "Apply")
            val input = decodeExpression(fields[1], depth + 1)
            val operator = decodeOperator(fields[2])
            QueryExpression(ExpressionKind.Apply, input = input, operator = operator)
        }
        "Concat", "StructureOrderMerge" -> {
            val fields = exactObjectFields(value, listOf("kind", "branches"), kind.value)
            val branchesValue = fields[1] as? PvArray
                ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "expression.branches")
            val branches = branchesValue.items().map { decodeExpression(it, depth + 1) }
            if (kind.value == "Concat") {
                QueryExpression(ExpressionKind.Concat, branches = branches)
            } else {
                QueryExpression(ExpressionKind.StructureOrderMerge, branches = branches)
            }
        }
        else -> throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "expression.kind")
    }
}

/** Strictly decodes one operator call (query.rs:720-734). */
private fun decodeOperator(value: PortableValue): OperatorCall {
    val fields = exactObjectFields(value, listOf("id", "version", "arguments"), "operator")
    val id = fields[0] as? PvString
        ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "operator.id")
    val version = queryUnsigned32(fields[1], "operator.version")
    val arguments = fields[2] as? PvObject
        ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = "operator.arguments")
    val operator = OperatorCall(id.value, version)
    for (entry in arguments.entries()) {
        operator.withArgument(entry.key, entry.value)
    }
    return operator
}

/** Reads an Integer field that must fit an unsigned 32-bit range. */
private fun queryUnsigned32(value: PortableValue, name: String): Int {
    val integer = value as? PvInteger
        ?: throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = name)
    val number = integer.value
    if (number.signum() < 0 || number.bitLength() > 32) {
        throw QueryFailureException(QueryFailureKind.INVALID_ARGUMENT, argument = name)
    }
    return number.toInt()
}

/** One failure class of query definition validation and binding
 * (consema-core/src/query.rs:3114+; the query_failure_code mapping,
 * error_registry.rs:1515-1529). */
enum class QueryFailureKind(val code: String) {
    /** The domain ID or version is unavailable or mismatched. */
    DOMAIN_MISMATCH("core.query.domain-mismatch@1"),

    /** The operator ID/version is unknown. */
    UNKNOWN_OPERATOR("core.query.unknown-operator@1"),

    /** An argument has the wrong value kind. */
    WRONG_ARGUMENT_TYPE("core.query.wrong-argument-type@1"),

    /** An argument is malformed or missing. */
    INVALID_ARGUMENT("core.query.invalid-argument@1"),

    /** The operator role composition is invalid. */
    INVALID_OPERATOR_COMPOSITION("core.query.invalid-composition@1"),

    /** The capability binding failed. */
    MISSING_CAPABILITY("core.query.missing-capability@1"),

    /** A required value type did not match. */
    REQUIRED_TYPE_MISMATCH("core.query.required-type-mismatch@1"),

    /** The query selection cardinality was violated. */
    CARDINALITY_VIOLATION("core.query.cardinality-violation@1"),

    /** A query resource limit was reached. */
    RESOURCE_LIMIT("core.query.resource-limit@1"),

    /** Query execution was cancelled. */
    CANCELLED("core.query.cancelled@1"),

    /** The target native semantics are unavailable. */
    TARGET_UNAVAILABLE("core.query.target-unavailable@1"),
}

/**
 * The typed query failure. The stable [code] is always the registered code,
 * so cross-language error-code parity holds (RFC 0016 §6).
 */
class QueryFailureException(
    val kind: QueryFailureKind,
    message: String = kind.code,
    /** The offending domain (DomainMismatch). */
    val domain: QueryDomain? = null,
    /** The offending operator ID. */
    val operator: String = "",
    /** The offending operator version (UnknownOperator). */
    val version: Int = 0,
    /** The offending argument name (InvalidArgument, WrongArgumentType). */
    val argument: String = "",
    /** The required argument value kind name. */
    val expectedKind: String = "",
    /** The required input match role (composition). */
    val expectedRole: MatchRole = "",
    /** The actual input match role (composition). */
    val actualRole: MatchRole = "",
    /** The missing capability (binding). */
    val capability: CapabilityId? = null,
) : Exception(message)
