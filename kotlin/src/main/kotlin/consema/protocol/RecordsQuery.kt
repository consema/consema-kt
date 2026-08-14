// The semantic-model v5 externalized YAML query result records and the
// native match locator boundary.
//
// Data authority (language-neutral sources first):
//   - https://github.com/consema/consema-rs/blob/main/consema-protocol/src/yaml_query.rs (YamlMatchLocator and
//     YamlQueryResultMessage: the stable identities, the YAML domain/role
//     matrix, the strictly increasing ordinals, and the process-local
//     rejection).
//   - https://github.com/consema/consema-rs/blob/main/consema-protocol/src/query.rs (NativeMatchLocator and the
//     process-local NodeRef boundary).
//   - conformance/vectors/semantic-model-v5.json pins the behaviors.
//
// Kotlin-idiomatic design: immutable locator and result classes; the
// process-local boundary is the fixed rejection entry (the Kotlin milestone
// exposes no process-local NodeRef handle surface, so fromProcessLocal is a
// pure boundary).

package consema.protocol

import consema.core.PvArray
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import java.math.BigInteger

/** The YAML result roles accepted by the externalized YAML query records
 * (yaml_query.rs:259-274). */
internal fun isYamlRole(role: String): Boolean =
    role == Roles.YAML_STREAM ||
        role == Roles.YAML_DOCUMENT ||
        role == Roles.YAML_NODE ||
        role == Roles.YAML_MAPPING_ENTRY ||
        role == Roles.YAML_SEQUENCE_ELEMENT ||
        role == Roles.YAML_ANCHOR_DEFINITION ||
        role == Roles.YAML_ALIAS_OCCURRENCE ||
        role == Roles.YAML_SYNTAX_PIECE

internal fun isYamlNativeRole(role: String): Boolean =
    role != Roles.YAML_SYNTAX_PIECE && isYamlRole(role)

/** Whether the YAML domain accepts the role (yaml_query.rs:251-257). */
internal fun yamlDomainAcceptsRole(domain: QueryDomain, role: String): Boolean =
    when (domain.id to domain.version) {
        "yaml.native-semantic-query" to 1 -> isYamlNativeRole(role)
        "yaml.lossless-syntax-query" to 1 -> role == Roles.YAML_SYNTAX_PIECE
        else -> false
    }

/** One YAML match after caller externalization of its process-local handle
 * (yaml_query.rs:10-80). */
class YamlMatchLocator private constructor(
    /** Stable source identity. */
    val sourceId: String,
    /** Stable caller-defined node locator. */
    val nodeLocator: String,
    /** Exact YAML result role. */
    val role: String,
    /** Strictly increasing standard-result ordinal. */
    val ordinal: ULong,
) {
    companion object {
        /** Validates stable identities, a YAML role, and its result ordinal
         * (yaml_query.rs:20-45). */
        fun new(
            sourceId: String,
            nodeLocator: String,
            role: String,
            ordinal: ULong,
        ): YamlMatchLocator {
            if (sourceId.isEmpty() || sourceId.length > 1024 ||
                nodeLocator.isEmpty() || nodeLocator.length > 4096 ||
                !isYamlRole(role)
            ) {
                throw invalid("$.yaml_match", "invalid source, locator, or YAML role")
            }
            return YamlMatchLocator(sourceId, nodeLocator, role, ordinal)
        }

        /** Explicitly refuses a raw process-local YAML node handle
         * (yaml_query.rs:48-55). */
        fun fromProcessLocal(): YamlMatchLocator =
            throw protocolError(
                ProtocolErrorKind.PROCESS_LOCAL_HANDLE,
                "$.yaml_match.node",
                "NodeRef requires a stable caller locator",
            )
    }
}

/** Complete or explicitly non-complete `core.yaml-query-result@1`
 * (yaml_query.rs:82-249). */
class YamlQueryResultMessage private constructor(
    /** Exact YAML query domain. */
    val domain: QueryDomain,
    /** Uniform result role. */
    val role: String,
    /** Ordered external match locators. */
    val matches: List<YamlMatchLocator>,
    /** Explicit terminal state. */
    val completion: Completion,
    /** Ordered diagnostics. */
    val diagnostics: List<Diagnostic>,
) {
    companion object {
        /** Validates domain/role binding, match ordering, and produced
         * count (yaml_query.rs:92-127). */
        fun new(
            domain: QueryDomain,
            role: String,
            matches: List<YamlMatchLocator>,
            completion: Completion,
            diagnostics: List<Diagnostic>,
        ): YamlQueryResultMessage {
            if (!yamlDomainAcceptsRole(domain, role)) {
                throw invalid("$", "YAML query domain and result role are inconsistent")
            }
            if (completion.produced != matches.size.toLong() ||
                matches.any { it.role != role } ||
                matches.zipWithNext().any { (left, right) -> left.ordinal >= right.ordinal }
            ) {
                throw invalid(
                    "$",
                    "completion count, role, or YAML match ordinals are inconsistent",
                )
            }
            return YamlQueryResultMessage(domain, role, matches, completion, diagnostics)
        }

        /** Strictly decodes terminal facts under one explicit semantic-model
         * registry (yaml_query.rs:192-248). */
        fun fromValueWithRegistry(
            value: PortableValue,
            registry: ErrorCodeRegistry,
        ): YamlQueryResultMessage {
            val fields = schemaFields(
                value,
                "core.yaml-query-result@1",
                listOf(
                    "schema", "domain_id", "domain_version", "role", "matches",
                    "completion", "diagnostics",
                ),
                "$",
            )
            val matches = sequenceOf(fields[4], "$.matches").mapIndexed { index, item ->
                val path = "$.matches[$index]"
                val matchFields = exactFields(
                    item,
                    listOf("source_id", "node_locator", "role", "ordinal"),
                    path,
                )
                YamlMatchLocator.new(
                    stringOf(matchFields[0], "$path.source_id"),
                    stringOf(matchFields[1], "$path.node_locator"),
                    parseYamlRole(stringOf(matchFields[2], "$path.role")),
                    unsigned64(matchFields[3], "$path.ordinal"),
                )
            }
            val diagnostics = sequenceOf(fields[6], "$.diagnostics")
                .map { Diagnostic.fromValue(it, registry) }
            return new(
                QueryDomain(
                    stringOf(fields[1], "$.domain_id"),
                    unsigned32(fields[2], "$.domain_version"),
                ),
                parseYamlRole(stringOf(fields[3], "$.role")),
                matches,
                Completion.fromValueWithRegistry(fields[5], registry),
                diagnostics,
            )
        }

        /** Strictly decodes under the v1 registry. */
        fun fromValue(value: PortableValue): YamlQueryResultMessage =
            fromValueWithRegistry(
                value,
                ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V1),
            )
    }

    /** Encodes `core.yaml-query-result@1` (yaml_query.rs:159-190). */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.yaml-query-result@1")),
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
}

/** Parses one YAML query role spelling (yaml_query.rs:290-302). */
internal fun parseYamlRole(value: String): String =
    when (value) {
        Roles.YAML_STREAM, Roles.YAML_DOCUMENT, Roles.YAML_NODE,
        Roles.YAML_MAPPING_ENTRY, Roles.YAML_SEQUENCE_ELEMENT,
        Roles.YAML_ANCHOR_DEFINITION, Roles.YAML_ALIAS_OCCURRENCE,
        Roles.YAML_SYNTAX_PIECE,
        -> value
        else -> throw invalid("$.role", "unknown YAML query match role")
    }

/**
 * The native match locator boundary (protocol_v1.rs:612-618): a
 * process-local node handle can never be externalized into a transferable
 * native match locator. The Kotlin milestone exposes no process-local
 * NodeRef handle surface, so the boundary is the fixed rejection entry.
 */
object NativeMatchLocator {
    /** Explicitly refuses a raw process-local node handle. */
    fun fromProcessLocal(): Nothing =
        throw protocolError(
            ProtocolErrorKind.PROCESS_LOCAL_HANDLE,
            "$.match.node",
            "NodeRef requires a stable caller locator",
        )
}

/**
 * The diagnostic source-binding boundary (protocol_v1.rs:511-527): a core
 * diagnostic whose primary location still references a process-local
 * snapshot handle can never be externalized; the Kotlin Diagnostic API
 * accepts only transferable SourceLocation facts, so the boundary is the
 * fixed rejection entry at the snapshot-binding point.
 */
object DiagnosticSourceBinding {
    /** Refuses to externalize a snapshot-bound diagnostic location. */
    fun requireTransferableSource(): Nothing =
        throw protocolError(
            ProtocolErrorKind.PROCESS_LOCAL_HANDLE,
            "$.location.snapshot",
            "snapshot-bound locations require a stable caller source ID",
        )
}
