// The semantic-model v6 externalized INI and Java Properties query result
// records.
//
// Data authority (language-neutral sources first):
//   - https://github.com/consema/consema-rs/blob/main/consema-protocol/src/line_query.rs (IniMatchLocator,
//     IniQueryResultMessage, JavaPropertiesMatchLocator,
//     JavaPropertiesQueryResultMessage: the line-format domain/role
//     matrices, the strictly increasing ordinals, the bounded identifiers,
//     and the process-local rejections).
//   - conformance/vectors/semantic-model-v6.json pins the behaviors.
//
// Kotlin-idiomatic design: one shared locator core with the two role
// vocabularies; the records validate the exact domain/role matrix at
// construction exactly as the Rust constructors do.

package consema.protocol

import consema.core.PvArray
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import java.math.BigInteger

private const val MAX_SOURCE_ID_BYTES = 1024
private const val MAX_NODE_LOCATOR_BYTES = 4096

/** The INI roles accepted by the INI query records (line_query.rs). */
internal fun isIniRole(role: String): Boolean =
    role == Roles.INI_DOCUMENT ||
        role == Roles.INI_PHYSICAL_LINE ||
        role == Roles.INI_LOGICAL_LINE ||
        role == Roles.INI_SECTION ||
        role == Roles.INI_DEFAULT_SECTION ||
        role == Roles.INI_ENTRY ||
        role == Roles.INI_ERROR_LINE ||
        role == Roles.INI_SYNTAX_PIECE

internal fun isIniNativeRole(role: String): Boolean =
    role != Roles.INI_SYNTAX_PIECE && isIniRole(role)

/** The Java Properties roles accepted by the Properties query records
 * (line_query.rs). */
internal fun isPropertiesRole(role: String): Boolean =
    role == Roles.PROPERTIES_DOCUMENT ||
        role == Roles.PROPERTIES_NATURAL_LINE ||
        role == Roles.PROPERTIES_LOGICAL_LINE ||
        role == Roles.PROPERTIES_PROPERTY ||
        role == Roles.PROPERTIES_COMMENT ||
        role == Roles.PROPERTIES_ESCAPE ||
        role == Roles.PROPERTIES_ERROR_LINE ||
        role == Roles.PROPERTIES_SYNTAX_PIECE

internal fun isPropertiesNativeRole(role: String): Boolean =
    role != Roles.PROPERTIES_SYNTAX_PIECE && isPropertiesRole(role)

/** Whether the INI domain accepts the role (line_query.rs). */
internal fun iniDomainAcceptsRole(domain: QueryDomain, role: String): Boolean =
    when (domain.id to domain.version) {
        "ini.native-semantic-query" to 1 -> isIniNativeRole(role)
        "ini.lossless-syntax-query" to 1 -> role == Roles.INI_SYNTAX_PIECE
        else -> false
    }

/** Whether the Properties domain accepts the role (line_query.rs). */
internal fun propertiesDomainAcceptsRole(domain: QueryDomain, role: String): Boolean =
    when (domain.id to domain.version) {
        "java-properties.native-semantic-query" to 1 -> isPropertiesNativeRole(role)
        "java-properties.lossless-syntax-query" to 1 -> role == Roles.PROPERTIES_SYNTAX_PIECE
        else -> false
    }

/** The shared external line locator facts (line_query.rs). */
internal class ExternalLineLocator private constructor(
    val sourceId: String,
    val nodeLocator: String,
    val role: String,
    val ordinal: ULong,
) {
    companion object {
        fun new(
            sourceId: String,
            nodeLocator: String,
            role: String,
            ordinal: ULong,
            acceptsRole: (String) -> Boolean,
        ): ExternalLineLocator {
            if (!validIdentifier(sourceId, MAX_SOURCE_ID_BYTES) ||
                !validIdentifier(nodeLocator, MAX_NODE_LOCATOR_BYTES) ||
                !acceptsRole(role)
            ) {
                throw invalid("$.matches", "invalid source, locator, or line-format role")
            }
            return ExternalLineLocator(sourceId, nodeLocator, role, ordinal)
        }
    }
}

private fun validIdentifier(value: String, maximum: Int): Boolean =
    value.isNotEmpty() && value.length <= maximum

/** One INI match after caller externalization of its process-local handle
 * (line_query.rs). */
class IniMatchLocator internal constructor(internal val locator: ExternalLineLocator) {
    companion object {
        /** Validates stable identities, an exact INI role, and its result
         * ordinal (line_query.rs). */
        fun new(
            sourceId: String,
            nodeLocator: String,
            role: String,
            ordinal: ULong,
        ): IniMatchLocator =
            IniMatchLocator(ExternalLineLocator.new(sourceId, nodeLocator, role, ordinal, ::isIniRole))

        /** Explicitly refuses a raw process-local INI node handle
         * (line_query.rs). */
        fun fromProcessLocal(): IniMatchLocator =
            throw protocolError(
                ProtocolErrorKind.PROCESS_LOCAL_HANDLE,
                "$.matches.node",
                "INI NodeRef requires a stable caller locator",
            )
    }

    /** Stable source identity. */
    val sourceId: String
        get() = locator.sourceId

    /** Stable caller-defined node locator. */
    val nodeLocator: String
        get() = locator.nodeLocator

    /** Exact INI result role. */
    val role: String
        get() = locator.role

    /** Strictly increasing standard-result ordinal. */
    val ordinal: ULong
        get() = locator.ordinal
}

/** Complete or explicitly non-complete `core.ini-query-result@1`
 * (line_query.rs). */
class IniQueryResultMessage private constructor(
    /** Exact INI query domain. */
    val domain: QueryDomain,
    /** Uniform result role. */
    val role: String,
    /** Ordered external INI match locators. */
    val matches: List<IniMatchLocator>,
    /** Explicit terminal state. */
    val completion: Completion,
    /** Ordered diagnostics. */
    val diagnostics: List<Diagnostic>,
) {
    companion object {
        /** Validates the exact INI domain/role matrix, ordering, and
         * produced count (line_query.rs). */
        fun new(
            domain: QueryDomain,
            role: String,
            matches: List<IniMatchLocator>,
            completion: Completion,
            diagnostics: List<Diagnostic>,
        ): IniQueryResultMessage {
            validateLineResult(domain, role, matches.map { it.locator }, completion, ::iniDomainAcceptsRole)
            return IniQueryResultMessage(domain, role, matches, completion, diagnostics)
        }

        /** Strictly decodes with explicit registry and pre-allocation limits
         * (line_query.rs). */
        fun fromValueWithRegistryAndLimits(
            value: PortableValue,
            registry: ErrorCodeRegistry,
            limits: ProtocolLimits,
        ): IniQueryResultMessage {
            val decoded = decodeLineResult(
                value,
                "core.ini-query-result@1",
                registry,
                limits,
                ::parseIniRole,
                ::isIniRole,
            )
            return new(
                decoded.domain,
                decoded.role,
                decoded.matches.map { IniMatchLocator(it) },
                decoded.completion,
                decoded.diagnostics,
            )
        }

        /** Strictly decodes under the v1 registry. */
        fun fromValue(value: PortableValue): IniQueryResultMessage =
            fromValueWithRegistryAndLimits(
                value,
                ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V1),
                ProtocolLimits.default,
            )
    }

    /** Encodes `core.ini-query-result@1` (line_query.rs). */
    fun toValue(): PortableValue =
        encodeLineResult(
            "core.ini-query-result@1",
            domain,
            role,
            matches.map { it.locator },
            completion,
            diagnostics,
        )
}

/** One Java Properties match after externalization of its process-local
 * handle (line_query.rs). */
class JavaPropertiesMatchLocator internal constructor(internal val locator: ExternalLineLocator) {
    companion object {
        /** Validates stable identities, an exact Properties role, and its
         * result ordinal (line_query.rs). */
        fun new(
            sourceId: String,
            nodeLocator: String,
            role: String,
            ordinal: ULong,
        ): JavaPropertiesMatchLocator =
            JavaPropertiesMatchLocator(
                ExternalLineLocator.new(sourceId, nodeLocator, role, ordinal, ::isPropertiesRole),
            )

        /** Explicitly refuses a raw process-local Properties node handle
         * (line_query.rs). */
        fun fromProcessLocal(): JavaPropertiesMatchLocator =
            throw protocolError(
                ProtocolErrorKind.PROCESS_LOCAL_HANDLE,
                "$.matches.node",
                "Java Properties NodeRef requires a stable caller locator",
            )
    }

    /** Stable source identity. */
    val sourceId: String
        get() = locator.sourceId

    /** Stable caller-defined node locator. */
    val nodeLocator: String
        get() = locator.nodeLocator

    /** Exact Java Properties result role. */
    val role: String
        get() = locator.role

    /** Strictly increasing standard-result ordinal. */
    val ordinal: ULong
        get() = locator.ordinal
}

/** Complete or explicitly non-complete `core.java-properties-query-result@1`
 * (line_query.rs). */
class JavaPropertiesQueryResultMessage private constructor(
    /** Exact Java Properties query domain. */
    val domain: QueryDomain,
    /** Uniform result role. */
    val role: String,
    /** Ordered external Java Properties match locators. */
    val matches: List<JavaPropertiesMatchLocator>,
    /** Explicit terminal state. */
    val completion: Completion,
    /** Ordered diagnostics. */
    val diagnostics: List<Diagnostic>,
) {
    companion object {
        /** Validates the exact Properties domain/role matrix, ordering, and
         * produced count (line_query.rs). */
        fun new(
            domain: QueryDomain,
            role: String,
            matches: List<JavaPropertiesMatchLocator>,
            completion: Completion,
            diagnostics: List<Diagnostic>,
        ): JavaPropertiesQueryResultMessage {
            validateLineResult(
                domain,
                role,
                matches.map { it.locator },
                completion,
                ::propertiesDomainAcceptsRole,
            )
            return JavaPropertiesQueryResultMessage(domain, role, matches, completion, diagnostics)
        }

        /** Strictly decodes with explicit registry and pre-allocation limits
         * (line_query.rs). */
        fun fromValueWithRegistryAndLimits(
            value: PortableValue,
            registry: ErrorCodeRegistry,
            limits: ProtocolLimits,
        ): JavaPropertiesQueryResultMessage {
            val decoded = decodeLineResult(
                value,
                "core.java-properties-query-result@1",
                registry,
                limits,
                ::parsePropertiesRole,
                ::isPropertiesRole,
            )
            return new(
                decoded.domain,
                decoded.role,
                decoded.matches.map { JavaPropertiesMatchLocator(it) },
                decoded.completion,
                decoded.diagnostics,
            )
        }

        /** Strictly decodes under the v1 registry. */
        fun fromValue(value: PortableValue): JavaPropertiesQueryResultMessage =
            fromValueWithRegistryAndLimits(
                value,
                ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V1),
                ProtocolLimits.default,
            )
    }

    /** Encodes `core.java-properties-query-result@1` (line_query.rs). */
    fun toValue(): PortableValue =
        encodeLineResult(
            "core.java-properties-query-result@1",
            domain,
            role,
            matches.map { it.locator },
            completion,
            diagnostics,
        )
}

/** Validates the line-format result invariants (line_query.rs). */
private fun validateLineResult(
    domain: QueryDomain,
    role: String,
    matches: List<ExternalLineLocator>,
    completion: Completion,
    domainAcceptsRole: (QueryDomain, String) -> Boolean,
) {
    if (!domainAcceptsRole(domain, role)) {
        throw invalid("$", "line-format query domain and result role are inconsistent")
    }
    if (completion.produced != matches.size.toLong() ||
        matches.any { it.role != role } ||
        matches.zipWithNext().any { (left, right) -> left.ordinal >= right.ordinal }
    ) {
        throw invalid("$", "completion count, role, or match ordinals are inconsistent")
    }
}

/** Encodes one line-format result record (line_query.rs). */
private fun encodeLineResult(
    schema: String,
    domain: QueryDomain,
    role: String,
    matches: List<ExternalLineLocator>,
    completion: Completion,
    diagnostics: List<Diagnostic>,
): PortableValue =
    PvObject(
        listOf(
            consema.core.Entry("schema", PvString(schema)),
            consema.core.Entry("domain_id", PvString(domain.id)),
            consema.core.Entry(
                "domain_version",
                PvInteger(BigInteger.valueOf(domain.version.toLong())),
            ),
            consema.core.Entry("role", PvString(role)),
            consema.core.Entry(
                "matches",
                PvArray(
                    matches.map { match ->
                        PvObject(
                            listOf(
                                consema.core.Entry("source_id", PvString(match.sourceId)),
                                consema.core.Entry("node_locator", PvString(match.nodeLocator)),
                                consema.core.Entry("role", PvString(match.role)),
                                consema.core.Entry("ordinal", integerValue(match.ordinal)),
                            ),
                        )
                    },
                ),
            ),
            consema.core.Entry("completion", completion.toValue()),
            consema.core.Entry(
                "diagnostics",
                PvArray(diagnostics.map { it.toValue() }),
            ),
        ),
    )

/** Strictly decodes one line-format result record (line_query.rs). */
private fun decodeLineResult(
    value: PortableValue,
    expectedSchema: String,
    registry: ErrorCodeRegistry,
    limits: ProtocolLimits,
    parseRole: (String) -> String,
    acceptsRole: (String) -> Boolean,
): DecodedLineResult {
    val fields = schemaFields(
        value,
        expectedSchema,
        listOf(
            "schema", "domain_id", "domain_version", "role", "matches",
            "completion", "diagnostics",
        ),
        "$",
    )
    val matchValues = sequenceOf(fields[4], "$.matches")
    val diagnosticValues = sequenceOf(fields[6], "$.diagnostics")
    checkContainerLimit("$.matches", matchValues.size, limits)
    checkContainerLimit("$.diagnostics", diagnosticValues.size, limits)
    if (matchValues.size.toLong() + diagnosticValues.size.toLong() + 8 > limits.maxNodes.toLong()) {
        throw resource("$", "query-result structure exceeds the configured node limit")
    }
    val matches = matchValues.mapIndexed { index, item ->
        val path = "$.matches[$index]"
        val matchFields = exactFields(
            item,
            listOf("source_id", "node_locator", "role", "ordinal"),
            path,
        )
        val sourceId = boundedCopy(
            stringOf(matchFields[0], "$path.source_id"),
            MAX_SOURCE_ID_BYTES,
            limits,
            "$path.source_id",
        )
        val nodeLocator = boundedCopy(
            stringOf(matchFields[1], "$path.node_locator"),
            MAX_NODE_LOCATOR_BYTES,
            limits,
            "$path.node_locator",
        )
        ExternalLineLocator.new(
            sourceId,
            nodeLocator,
            parseRole(stringOf(matchFields[2], "$path.role")),
            unsigned64(matchFields[3], "$path.ordinal"),
            acceptsRole,
        )
    }
    val diagnostics = diagnosticValues.map { Diagnostic.fromValue(it, registry) }
    return DecodedLineResult(
        domain = QueryDomain(
            stringOf(fields[1], "$.domain_id"),
            unsigned32(fields[2], "$.domain_version"),
        ),
        role = parseRole(stringOf(fields[3], "$.role")),
        matches = matches,
        completion = Completion.fromValueWithRegistry(fields[5], registry),
        diagnostics = diagnostics,
    )
}

private class DecodedLineResult(
    val domain: QueryDomain,
    val role: String,
    val matches: List<ExternalLineLocator>,
    val completion: Completion,
    val diagnostics: List<Diagnostic>,
)

private fun boundedCopy(
    value: String,
    formatLimit: Int,
    limits: ProtocolLimits,
    path: String,
): String {
    if (!validIdentifier(value, formatLimit)) {
        throw invalid(path, "identifier is empty or exceeds its format limit")
    }
    if (value.length > limits.maxBlobBytes) {
        throw resource(path, "identifier exceeds the configured blob limit")
    }
    return value
}

private fun checkContainerLimit(path: String, count: Int, limits: ProtocolLimits) {
    if (count > limits.maxContainerEntries) {
        throw resource(path, "container exceeds the configured entry limit")
    }
}

/** Parses one INI query role spelling (line_query.rs). */
internal fun parseIniRole(value: String): String =
    when (value) {
        Roles.INI_DOCUMENT, Roles.INI_PHYSICAL_LINE, Roles.INI_LOGICAL_LINE,
        Roles.INI_SECTION, Roles.INI_DEFAULT_SECTION, Roles.INI_ENTRY,
        Roles.INI_ERROR_LINE, Roles.INI_SYNTAX_PIECE,
        -> value
        else -> throw invalid("$.role", "unknown INI query match role")
    }

/** Parses one Java Properties query role spelling (line_query.rs). */
internal fun parsePropertiesRole(value: String): String =
    when (value) {
        Roles.PROPERTIES_DOCUMENT, Roles.PROPERTIES_NATURAL_LINE,
        Roles.PROPERTIES_LOGICAL_LINE, Roles.PROPERTIES_PROPERTY,
        Roles.PROPERTIES_COMMENT, Roles.PROPERTIES_ESCAPE,
        Roles.PROPERTIES_ERROR_LINE, Roles.PROPERTIES_SYNTAX_PIECE,
        -> value
        else -> throw invalid("$.role", "unknown Java Properties query match role")
    }
