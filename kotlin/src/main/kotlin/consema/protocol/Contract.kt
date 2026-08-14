// The frozen contract registry and the common protocol envelope.
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-protocol/src/contract.rs — CONTRACTS_V1 at
// contract.rs, V2 at :90, V3 at :111, V5 at :142, V6 at :178, V7 at :225
// (16/18/25/25/30/38/41 records; v4 reuses v3). The record lists below are
// transcribed VERBATIM from the Rust source (cross-checked against
// consema-go/go/protocol/contract.go:289-473). Every contract id/version/stability is
// byte-identical; nothing may be invented or dropped.
//
// Kotlin-idiomatic design: immutable value types with `id`/`version`
// properties; registries are small value objects with binary search over
// the sorted record lists; the envelope is a validated [ProtocolMessage].

package consema.protocol

import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import java.math.BigInteger

/** The compatibility status of one frozen contract. */
enum class ContractStability {
    /** A normative public contract for the current semantic model. */
    Stable,

    /** A transport-only contract; still immutable within its version. */
    Transport,
}

/** Parses one canonical stability spelling. */
fun parseContractStability(name: String): ContractStability = when (name) {
    "Stable" -> ContractStability.Stable
    "Transport" -> ContractStability.Transport
    else -> throw invalid("$.stability", "unknown contract stability")
}

/**
 * A stable versioned protocol contract identifier. The version must be
 * non-zero and the id must be a dotted lowercase identifier of at most 255
 * bytes whose segments start with a lowercase letter
 * (https://github.com/consema/consema-rs/blob/main/consema-protocol/src/contract.rs).
 */
data class ContractId(val id: String, val version: Int) : Comparable<ContractId> {
    init {
        if (version == 0) {
            throw invalid("$.contract.version", "version must be non-zero")
        }
        validateIdentifier(id, "$.contract.id")
    }

    /** The canonical `id@version` schema discriminator. */
    fun schema(): String = "$id@$version"

    /** Orders contract ids by (id, version). */
    override fun compareTo(other: ContractId): Int {
        val byId = id.compareTo(other.id)
        return if (byId != 0) byId else version.compareTo(other.version)
    }
}

/** Enforces the strict dotted identifier rule of the contract registry
 * (contract.rs): at most 255 bytes, at least two segments, each
 * segment starting with a lowercase letter and continuing with lowercase
 * letters, digits, or dashes. */
internal fun validateIdentifier(identifier: String, path: String) {
    if (identifier.length > 255 || !identifier.contains('.')) {
        throw invalid(path, "identifier must contain multiple segments and be at most 255 bytes")
    }
    for (segment in identifier.split('.')) {
        if (segment.isEmpty() || !segment[0].isLowerCase()) {
            throw invalid(path, "identifier contains an invalid segment")
        }
        for (index in 1 until segment.length) {
            val character = segment[index]
            if (!character.isLowerCase() && !character.isDigit() && character != '-') {
                throw invalid(path, "identifier contains an invalid segment")
            }
        }
    }
}

/**
 * Enforces the profile/capability namespace rule (https://github.com/consema/consema-rs/blob/main/consema-protocol/
 * src/registry.rs): at most 255 bytes, and when [requireDot] is set
 * at least two segments; every segment starts with a lowercase letter (or a
 * digit when not the first segment) and continues with lowercase letters,
 * digits, or dashes.
 */
internal fun validateNamespace(identifier: String, requireDot: Boolean, path: String) {
    if (identifier.isEmpty() || identifier.length > 255 ||
        (requireDot && !identifier.contains('.'))
    ) {
        throw invalid(path, "invalid namespaced identifier")
    }
    for ((index, segment) in identifier.split('.').withIndex()) {
        if (segment.isEmpty()) {
            throw invalid(path, "invalid identifier segment")
        }
        val first = segment[0]
        if (!first.isLowerCase() && !(index != 0 && first.isDigit())) {
            throw invalid(path, "invalid identifier segment")
        }
        for (offset in 1 until segment.length) {
            val character = segment[offset]
            if (!character.isLowerCase() && !character.isDigit() && character != '-') {
                throw invalid(path, "invalid identifier segment")
            }
        }
    }
}

/** One static registry record. */
data class ContractDescriptor(val id: String, val version: Int, val stability: ContractStability)

/** Selects one frozen semantic-model registry. */
enum class ContractRegistryVersion {
    /** The Consema 0.3 semantic-model v1 registry (16 contracts). */
    V1,

    /** The Consema 0.4 semantic-model v2 registry (18 contracts). */
    V2,

    /** The Consema 0.5 semantic-model v3 registry (25 contracts). */
    V3,

    /** The Consema 0.6 semantic-model v4 registry (25 contracts). */
    V4,

    /** The Consema 0.7 semantic-model v5 registry (30 contracts). */
    V5,

    /** The Consema 0.8 semantic-model v6 registry (38 contracts). */
    V6,

    /** The Consema 0.12 semantic-model v7 registry (41 contracts; CLI
     * machine payloads). */
    V7,
}

/**
 * A closed, explicitly versioned contract registry.
 */
class ContractRegistry private constructor(val version: ContractRegistryVersion) {

    companion object {
        /** Returns the registry for one frozen semantic-model version. */
        fun forVersion(version: ContractRegistryVersion): ContractRegistry =
            ContractRegistry(version)

        /** The semantic-model v1 registry (the Rust Default). */
        fun default(): ContractRegistry = forVersion(ContractRegistryVersion.V1)
    }

    /** The sorted immutable descriptors. */
    fun contracts(): List<ContractDescriptor> = contractsForVersion(version)

    /** Reports whether an exact ID/version pair is registered. */
    fun recognizes(contract: ContractId): Boolean = descriptor(contract) != null

    /** The exact registered descriptor for the contract, or null. */
    fun descriptor(contract: ContractId): ContractDescriptor? {
        val records = contractsForVersion(version)
        var low = 0
        var high = records.size
        while (low < high) {
            val middle = (low + high) ushr 1
            val candidate = records[middle]
            val byId = candidate.id.compareTo(contract.id)
            if (byId < 0 || (byId == 0 && candidate.version < contract.version)) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        if (low < records.size) {
            val candidate = records[low]
            if (candidate.id == contract.id && candidate.version == contract.version) {
                return candidate
            }
        }
        return null
    }

    /** The error-code registry for the same semantic-model version. */
    fun errorCodeRegistry(): ErrorCodeRegistry =
        ErrorCodeRegistry.forVersion(ErrorRegistryVersion.values()[version.ordinal])
}

private fun contract(id: String, version: Int, stability: ContractStability) =
    ContractDescriptor(id, version, stability)

private fun stable(id: String): ContractDescriptor = contract(id, 1, ContractStability.Stable)

private fun transport(id: String): ContractDescriptor = contract(id, 1, ContractStability.Transport)

// The frozen records, transcribed verbatim from
// https://github.com/consema/consema-rs/blob/main/consema-protocol/src/contract.rs (CONTRACTS_V1..V7).

private val CONTRACTS_V1: List<ContractDescriptor> = listOf(
    stable("core.cancellation-request"),
    stable("core.capability-declaration"),
    stable("core.change-set"),
    stable("core.completion"),
    stable("core.diagnostic"),
    stable("core.error-code-registry"),
    stable("core.execution-policy"),
    stable("core.profile-descriptor"),
    stable("core.projection-report"),
    stable("core.projection-request"),
    stable("core.projection-result"),
    transport("core.protocol-message"),
    stable("core.provenance-map"),
    stable("core.query-definition"),
    stable("core.query-result"),
    stable("core.registry-manifest"),
)

private val CONTRACTS_V2: List<ContractDescriptor> = listOf(
    stable("core.cancellation-request"),
    stable("core.capability-declaration"),
    stable("core.change-set"),
    stable("core.completion"),
    stable("core.diagnostic"),
    stable("core.error-code-registry"),
    stable("core.execution-policy"),
    stable("core.profile-descriptor"),
    stable("core.projection-report"),
    stable("core.projection-request"),
    stable("core.projection-result"),
    transport("core.protocol-message"),
    stable("core.provenance-map"),
    stable("core.query-definition"),
    stable("core.query-result"),
    stable("core.registry-manifest"),
    stable("core.source-patch"),
    stable("core.source-snapshot"),
)

private val CONTRACTS_V3: List<ContractDescriptor> = listOf(
    stable("core.cancellation-request"),
    stable("core.capability-declaration"),
    stable("core.change-set"),
    stable("core.completion"),
    stable("core.conversion-report"),
    stable("core.diagnostic"),
    stable("core.edit-plan"),
    stable("core.error-code-registry"),
    stable("core.execution-policy"),
    stable("core.format-operation-registry"),
    stable("core.materialization-provenance-map"),
    stable("core.materialization-report"),
    stable("core.materialization-request"),
    stable("core.materialization-result"),
    stable("core.profile-descriptor"),
    stable("core.projection-report"),
    stable("core.projection-request"),
    stable("core.projection-result"),
    transport("core.protocol-message"),
    stable("core.provenance-map"),
    stable("core.query-definition"),
    stable("core.query-result"),
    stable("core.registry-manifest"),
    stable("core.source-patch"),
    stable("core.source-snapshot"),
)

private val CONTRACTS_V5: List<ContractDescriptor> = listOf(
    stable("core.cancellation-request"),
    stable("core.capability-declaration"),
    stable("core.change-set"),
    stable("core.completion"),
    stable("core.conversion-report"),
    stable("core.diagnostic"),
    stable("core.edit-plan"),
    stable("core.error-code-registry"),
    stable("core.execution-policy"),
    stable("core.format-operation-registry"),
    stable("core.graph-projection-result"),
    stable("core.graph-provenance-map"),
    stable("core.graph-query-result"),
    stable("core.materialization-provenance-map"),
    stable("core.materialization-report"),
    stable("core.materialization-request"),
    stable("core.materialization-result"),
    stable("core.portable-graph"),
    stable("core.profile-descriptor"),
    stable("core.projection-report"),
    stable("core.projection-request"),
    stable("core.projection-result"),
    transport("core.protocol-message"),
    stable("core.provenance-map"),
    stable("core.query-definition"),
    stable("core.query-result"),
    stable("core.registry-manifest"),
    stable("core.source-patch"),
    stable("core.source-snapshot"),
    stable("core.yaml-query-result"),
)

private val CONTRACTS_V6: List<ContractDescriptor> = listOf(
    stable("core.cancellation-request"),
    stable("core.capability-declaration"),
    stable("core.change-set"),
    stable("core.completion"),
    stable("core.conversion-report"),
    stable("core.diagnostic"),
    stable("core.edit-plan"),
    stable("core.error-code-registry"),
    stable("core.execution-policy"),
    stable("core.format-operation-registry"),
    stable("core.graph-projection-result"),
    stable("core.graph-provenance-map"),
    stable("core.graph-query-result"),
    stable("core.ini-query-result"),
    stable("core.java-properties-query-result"),
    stable("core.java-utf16-string"),
    stable("core.materialization-provenance-map"),
    stable("core.materialization-report"),
    stable("core.materialization-request"),
    contract("core.materialization-request", 2, ContractStability.Stable),
    stable("core.materialization-result"),
    contract("core.materialization-result", 2, ContractStability.Stable),
    stable("core.portable-graph"),
    stable("core.profile-descriptor"),
    stable("core.projection-report"),
    stable("core.projection-request"),
    stable("core.projection-result"),
    transport("core.protocol-message"),
    stable("core.provenance-map"),
    stable("core.query-definition"),
    stable("core.query-result"),
    stable("core.registry-manifest"),
    stable("core.source-encoding"),
    stable("core.source-patch"),
    contract("core.source-patch", 2, ContractStability.Stable),
    stable("core.source-snapshot"),
    contract("core.source-snapshot", 2, ContractStability.Stable),
    stable("core.yaml-query-result"),
)

private val CONTRACTS_V7: List<ContractDescriptor> = listOf(
    stable("core.batch-plan"),
    stable("core.batch-result"),
    stable("core.cancellation-request"),
    stable("core.capability-declaration"),
    stable("core.change-set"),
    stable("core.cli-output"),
    stable("core.completion"),
    stable("core.conversion-report"),
    stable("core.diagnostic"),
    stable("core.edit-plan"),
    stable("core.error-code-registry"),
    stable("core.execution-policy"),
    stable("core.format-operation-registry"),
    stable("core.graph-projection-result"),
    stable("core.graph-provenance-map"),
    stable("core.graph-query-result"),
    stable("core.ini-query-result"),
    stable("core.java-properties-query-result"),
    stable("core.java-utf16-string"),
    stable("core.materialization-provenance-map"),
    stable("core.materialization-report"),
    stable("core.materialization-request"),
    contract("core.materialization-request", 2, ContractStability.Stable),
    stable("core.materialization-result"),
    contract("core.materialization-result", 2, ContractStability.Stable),
    stable("core.portable-graph"),
    stable("core.profile-descriptor"),
    stable("core.projection-report"),
    stable("core.projection-request"),
    stable("core.projection-result"),
    transport("core.protocol-message"),
    stable("core.provenance-map"),
    stable("core.query-definition"),
    stable("core.query-result"),
    stable("core.registry-manifest"),
    stable("core.source-encoding"),
    stable("core.source-patch"),
    contract("core.source-patch", 2, ContractStability.Stable),
    stable("core.source-snapshot"),
    contract("core.source-snapshot", 2, ContractStability.Stable),
    stable("core.yaml-query-result"),
)

/** Returns the frozen contract records of one semantic-model version
 * (v4 reuses v3; strictly sorted by (id, version)). */
internal fun contractsForVersion(version: ContractRegistryVersion): List<ContractDescriptor> =
    when (version) {
        ContractRegistryVersion.V1 -> CONTRACTS_V1
        ContractRegistryVersion.V2 -> CONTRACTS_V2
        ContractRegistryVersion.V3, ContractRegistryVersion.V4 -> CONTRACTS_V3
        ContractRegistryVersion.V5 -> CONTRACTS_V5
        ContractRegistryVersion.V6 -> CONTRACTS_V6
        ContractRegistryVersion.V7 -> CONTRACTS_V7
    }

/**
 * One validated protocol payload in the common envelope
 * (https://github.com/consema/consema-rs/blob/main/consema-protocol/src/contract.rs).
 */
class ProtocolMessage private constructor(
    val contract: ContractId,
    val payload: PortableValue,
) {
    companion object {
        /**
         * Validates a recognized contract, rejects transport envelopes as
         * nested payload contracts, checks the payload schema
         * discriminator, and applies the registered-payload validation of
         * the contracts whose Kotlin record types exist in this package.
         */
        fun of(contract: ContractId, payload: PortableValue, registry: ContractRegistry): ProtocolMessage {
            val descriptor = registry.descriptor(contract)
                ?: throw protocolError(
                    ProtocolErrorKind.UNKNOWN_CONTRACT,
                    "$.contract",
                    contract.schema(),
                )
            if (descriptor.stability == ContractStability.Transport) {
                throw protocolError(
                    ProtocolErrorKind.INVALID_VALUE,
                    "$.contract",
                    "transport envelopes cannot be nested as payload contracts",
                )
            }
            validateContractPayloadSchema(payload, contract)
            validateRegisteredPayload(contract, payload, registry)
            return ProtocolMessage(contract, payload)
        }

        /** Strictly decodes the envelope and validates the selected payload
         * contract. */
        fun fromValue(value: PortableValue, registry: ContractRegistry): ProtocolMessage {
            val fields = schemaFields(
                value,
                "core.protocol-message@1",
                listOf("schema", "contract_id", "contract_version", "payload"),
                "$",
            )
            val id = stringOf(fields[1], "$.contract_id")
            val version = unsigned32(fields[2], "$.contract_version")
            return of(ContractId(id, version), fields[3], registry)
        }
    }

    /** Encodes the fixed `core.protocol-message@1` envelope as a
     * PortableValue tree. */
    fun toValue(): PortableValue =
        PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.protocol-message@1")),
                consema.core.Entry("contract_id", PvString(contract.id)),
                consema.core.Entry("contract_version", integerValue(contract.version.toULong())),
                consema.core.Entry("payload", payload),
            ),
        )
}

/** Requires the payload to be an Object whose first field is "schema"
 * carrying the exact contract schema (contract.rs). */
internal fun validateContractPayloadSchema(payload: PortableValue, contract: ContractId) {
    val objectValue = payload as? PvObject
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$.payload", "payload must be an Object")
    val entries = objectValue.entries()
    if (entries.isEmpty()) {
        throw protocolError(ProtocolErrorKind.MISSING_FIELD, "$.payload.schema", "payload schema is absent")
    }
    if (entries[0].key != "schema") {
        throw protocolError(ProtocolErrorKind.SCHEMA_MISMATCH, "$.payload", "schema must be the first field")
    }
    val observed = stringOf(entries[0].value, "$.payload.schema")
    if (observed != contract.schema()) {
        throw protocolError(
            ProtocolErrorKind.SCHEMA_MISMATCH,
            "$.payload.schema",
            "expected ${contract.schema()}",
        )
    }
}
