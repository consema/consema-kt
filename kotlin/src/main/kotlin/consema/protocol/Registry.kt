// The transferable Profile and Capability registry records plus the
// registry manifest.
//
// Data authority: consema-rs/consema-protocol/src/registry.rs and
// registry_manifest.rs (namespace rules at registry.rs:475-498; manifest
// validation at registry_manifest.rs:119-151). consema-go/go/protocol/
// registry_descriptor.go is a cross-reference.
//
// Kotlin-idiomatic design: immutable value types; validation throws
// [ProtocolException] at construction; the capability set is a deterministic
// set keyed by the `namespace@version` identity.

package consema.protocol

import consema.core.PvArray
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue
import java.math.BigInteger

/**
 * A versioned reference to a Profile, whose ID may contain numeric segments
 * (registry.rs:14-46).
 */
data class ProfileReference(val id: String, val version: Int) {
    init {
        validateNamespace(id, true, "$.profile.id")
        if (version == 0) {
            throw invalid("$.profile.version", "version must be non-zero")
        }
    }
}

/**
 * An immutable language profile registry descriptor (registry.rs:48-250).
 * Constructs a normalized descriptor and rejects malformed or duplicate
 * facts (registry.rs:60-114): differences and required capabilities are
 * sorted and must be unique.
 */
class ProfileDescriptor private constructor(
    /** The format-family namespace. */
    val formatFamilyId: String,
    /** The format-family contract version. */
    val formatFamilyVersion: Int,
    /** The profile namespace. */
    val profileId: String,
    /** The profile version. */
    val profileVersion: Int,
    /** The optional immutable base profile. */
    val baseProfile: ProfileReference?,
    /** The sorted stable difference identifiers. */
    val differences: List<String>,
    /** The sorted required capabilities. */
    val requiredCapabilities: List<CapabilityId>,
) {
    companion object {
        fun of(
            formatFamilyId: String,
            formatFamilyVersion: Int,
            profileId: String,
            profileVersion: Int,
            baseProfile: ProfileReference?,
            differences: List<String>,
            requiredCapabilities: List<CapabilityId>,
        ): ProfileDescriptor {
            validateNamespace(formatFamilyId, false, "$.format_family_id")
            validateNamespace(profileId, true, "$.profile_id")
            if (formatFamilyVersion == 0 || profileVersion == 0) {
                throw invalid("$", "family and profile versions must be non-zero")
            }
            for (difference in differences) {
                validateNamespace(difference, true, "$.differences")
            }
            for (capability in requiredCapabilities) {
                ContractId(capability.namespace, capability.version)
            }
            val sortedDifferences = differences.sorted()
            for (index in 1 until sortedDifferences.size) {
                if (sortedDifferences[index - 1] == sortedDifferences[index]) {
                    throw invalid("$.differences", "difference IDs must be unique")
                }
            }
            val sortedCapabilities = requiredCapabilities.sorted()
            for (index in 1 until sortedCapabilities.size) {
                if (sortedCapabilities[index - 1] == sortedCapabilities[index]) {
                    throw invalid("$.required_capabilities", "capability IDs must be unique")
                }
            }
            return ProfileDescriptor(
                formatFamilyId, formatFamilyVersion, profileId, profileVersion,
                baseProfile, sortedDifferences, sortedCapabilities,
            )
        }

        /** Strictly decodes `core.profile-descriptor@1` (registry.rs:
         * 203-249). */
        fun fromValue(value: PortableValue): ProfileDescriptor {
            val fields = schemaFields(
                value,
                "core.profile-descriptor@1",
                listOf(
                    "schema", "format_family_id", "format_family_version", "profile_id",
                    "profile_version", "base_profile", "differences", "required_capabilities",
                ),
                "$",
            )
            val formatFamilyId = stringOf(fields[1], "$.format_family_id")
            val formatFamilyVersion = unsigned32(fields[2], "$.format_family_version")
            val profileId = stringOf(fields[3], "$.profile_id")
            val profileVersion = unsigned32(fields[4], "$.profile_version")
            val baseProfile = if (fields[5] is PvNull) null else parseProfileReference(fields[5], "$.base_profile")
            val differenceValues = sequenceOf(fields[6], "$.differences")
            val differences = differenceValues.mapIndexed { index, item ->
                stringOf(item, "$.differences[$index]")
            }
            val capabilityValues = sequenceOf(fields[7], "$.required_capabilities")
            val capabilities = capabilityValues.mapIndexed { index, item ->
                val path = "$.required_capabilities[$index]"
                val contract = parseContractReference(item, path)
                CapabilityId(contract.id, contract.version)
            }
            return of(
                formatFamilyId, formatFamilyVersion, profileId, profileVersion,
                baseProfile, differences, capabilities,
            )
        }
    }

    /** Encodes `core.profile-descriptor@1` (registry.rs:158-201). */
    fun toValue(): PortableValue = PvObject(
        listOf(
            consema.core.Entry("schema", PvString("core.profile-descriptor@1")),
            consema.core.Entry("format_family_id", PvString(formatFamilyId)),
            consema.core.Entry("format_family_version", integerValue(formatFamilyVersion.toULong())),
            consema.core.Entry("profile_id", PvString(profileId)),
            consema.core.Entry("profile_version", integerValue(profileVersion.toULong())),
            consema.core.Entry(
                "base_profile",
                if (baseProfile == null) PvNull else referenceValue(baseProfile.id, baseProfile.version),
            ),
            consema.core.Entry("differences", PvArray(differences.map { PvString(it) })),
            consema.core.Entry(
                "required_capabilities",
                PvArray(requiredCapabilities.map { referenceValue(it.namespace, it.version) }),
            ),
        ),
    )
}

/**
 * A stable namespaced capability contract (consema-core capability.rs:7-28).
 */
data class CapabilityId(val namespace: String, val version: Int) : Comparable<CapabilityId> {
    override fun compareTo(other: CapabilityId): Int {
        val byNamespace = namespace.compareTo(other.namespace)
        return if (byNamespace != 0) byNamespace else version.compareTo(other.version)
    }
}

/** The closed support kind of one capability. */
enum class SupportKind {
    /** Promises the whole contract. */
    Conformant,

    /** Depends on machine-readable preconditions. */
    Conditional,

    /** Unavailable. */
    Unsupported,
}

/** One machine-readable conditional-support precondition. */
data class Precondition(val key: String, val value: String)

/** The declared support state of one capability (consema-core
 * capability.rs:30-43). */
data class ImplementationSupport(val kind: SupportKind, val preconditions: List<Precondition>)

/** How capability support was verified (consema-core capability.rs:45-56). */
enum class VerificationStatus(val wireName: String) {
    /** Verified against the named conformance suite. */
    Verified("Verified"),

    /** Declared by the implementation. */
    SelfDeclared("SelfDeclared"),

    /** Not verified. */
    Unverified("Unverified"),
}

/** Parses one canonical verification spelling. */
fun parseVerificationStatus(name: String): VerificationStatus =
    VerificationStatus.entries.firstOrNull { it.wireName == name }
        ?: throw invalid("$.verification", "unknown verification status")

/**
 * One implementation's support and verification claim for a capability
 * (registry.rs:252-439). Construction validates the cross-field support and
 * verification invariants (registry.rs:262-315): Conditional support
 * requires preconditions, only Conditional support may carry preconditions,
 * and Verified requires a suite ID.
 */
class CapabilityDeclaration private constructor(
    val capability: CapabilityId,
    val support: ImplementationSupport,
    val verification: VerificationStatus,
    val suiteId: String?,
) {
    companion object {
        fun of(
            capability: CapabilityId,
            support: ImplementationSupport,
            verification: VerificationStatus,
            suiteId: String?,
        ): CapabilityDeclaration {
            ContractId(capability.namespace, capability.version)
            if (support.kind == SupportKind.Conditional && support.preconditions.isEmpty()) {
                throw invalid("$.preconditions", "Conditional support requires preconditions")
            }
            if (support.kind != SupportKind.Conditional && support.preconditions.isNotEmpty()) {
                throw invalid("$.preconditions", "only Conditional support may carry preconditions")
            }
            val seen = HashSet<String>()
            for (precondition in support.preconditions) {
                if (!seen.add(precondition.key)) {
                    throw invalid("$.preconditions", "precondition keys must be unique")
                }
            }
            if (verification == VerificationStatus.Verified) {
                if (suiteId == null) {
                    throw invalid("$.suite_id", "Verified requires a suite ID")
                }
                validateNamespace(suiteId, true, "$.suite_id")
            } else if (suiteId != null) {
                throw invalid("$.suite_id", "only Verified may name a suite")
            }
            return CapabilityDeclaration(capability, support, verification, suiteId)
        }

        /** Strictly decodes `core.capability-declaration@1`
         * (registry.rs:381-438). */
        fun fromValue(value: PortableValue): CapabilityDeclaration {
            val fields = schemaFields(
                value,
                "core.capability-declaration@1",
                listOf(
                    "schema", "capability_id", "capability_version", "support",
                    "preconditions", "verification", "suite_id",
                ),
                "$",
            )
            val namespace = stringOf(fields[1], "$.capability_id")
            val version = unsigned32(fields[2], "$.capability_version")
            val preconditionMap = stringMapFromObject(fields[4], "$.preconditions")
            val preconditions = preconditionMap.toSortedMap().map { (key, value) ->
                Precondition(key, value)
            }
            val supportName = stringOf(fields[3], "$.support")
            val support = when {
                supportName == "Conformant" && preconditions.isEmpty() ->
                    ImplementationSupport(SupportKind.Conformant, emptyList())
                supportName == "Conditional" ->
                    ImplementationSupport(SupportKind.Conditional, preconditions)
                supportName == "Unsupported" && preconditions.isEmpty() ->
                    ImplementationSupport(SupportKind.Unsupported, emptyList())
                else -> throw invalid("$.support", "invalid support/preconditions combination")
            }
            val verification = parseVerificationStatus(stringOf(fields[5], "$.verification"))
            val suiteId = optionalString(fields[6], "$.suite_id")
            return of(CapabilityId(namespace, version), support, verification, suiteId)
        }
    }

    /** Encodes `core.capability-declaration@1` (registry.rs:341-379). */
    fun toValue(): PortableValue {
        val supportName = when (support.kind) {
            SupportKind.Conformant -> "Conformant"
            SupportKind.Conditional -> "Conditional"
            SupportKind.Unsupported -> "Unsupported"
        }
        val preconditions = when (support.kind) {
            SupportKind.Conditional -> support.preconditions.associate { it.key to it.value }
            else -> emptyMap()
        }
        return PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.capability-declaration@1")),
                consema.core.Entry("capability_id", PvString(capability.namespace)),
                consema.core.Entry("capability_version", integerValue(capability.version.toULong())),
                consema.core.Entry("support", PvString(supportName)),
                consema.core.Entry("preconditions", stringMapObject(preconditions)),
                consema.core.Entry("verification", PvString(verification.wireName)),
                consema.core.Entry("suite_id", nullableString(suiteId)),
            ),
        )
    }
}

/** A deterministic set of capabilities available to an operation
 * (consema-core capability.rs:59-96). */
class CapabilitySet {
    private val capabilities = HashMap<String, CapabilityId>()

    /** Adds a capability and reports whether it was newly added. */
    fun insert(capability: CapabilityId): Boolean {
        val key = "${capability.namespace}@${capability.version}"
        if (capabilities.containsKey(key)) {
            return false
        }
        capabilities[key] = capability
        return true
    }

    /** Reports whether a capability is available. */
    fun contains(capability: CapabilityId): Boolean =
        capabilities.containsKey("${capability.namespace}@${capability.version}")

    /** The capabilities in stable identifier order. */
    fun iterate(): List<CapabilityId> = capabilities.values.sorted()
}

/** One owned contract entry of the registry manifest. */
data class ContractManifestEntry(val contract: ContractId, val stability: ContractStability)

/** One owned error-code entry of the registry manifest. */
data class ErrorCodeManifestEntry(
    val code: String,
    val category: DiagnosticCategory,
    val introduced: String,
    val description: String,
)

/**
 * The `core.registry-manifest@1` record of one semantic-model contract set
 * (registry_manifest.rs:30-282).
 */
class RegistryManifest private constructor(
    val semanticModel: ContractId,
    val contracts: List<ContractManifestEntry>,
    val errorCodes: List<ErrorCodeManifestEntry>,
) {
    companion object {
        /** Builds a manifest from one semantic-model version, mirroring the
         * Rust version constructors. */
        fun of(
            semanticModelVersion: Int,
            contractRegistry: ContractRegistry,
            errorCodeRegistry: ErrorCodeRegistry,
        ): RegistryManifest {
            val contracts = contractRegistry.contracts().map { descriptor ->
                ContractManifestEntry(
                    ContractId(descriptor.id, descriptor.version),
                    descriptor.stability,
                )
            }
            val codes = errorCodeRegistry.codes().map { descriptor ->
                ErrorCodeManifestEntry(
                    descriptor.code,
                    descriptor.category,
                    descriptor.introduced,
                    descriptor.description,
                )
            }
            return RegistryManifest(ContractId("core.semantic-model", semanticModelVersion), contracts, codes)
        }

        /** Validates a manifest's sorted, unique, versioned records
         * (registry_manifest.rs:119-151). */
        fun validate(
            semanticModel: ContractId,
            contracts: List<ContractManifestEntry>,
            errorCodes: List<ErrorCodeManifestEntry>,
        ): RegistryManifest {
            for (index in 1 until contracts.size) {
                if (contracts[index - 1].contract >= contracts[index].contract) {
                    throw invalid("$", "manifest records must be sorted and unique")
                }
            }
            for (index in 1 until errorCodes.size) {
                if (errorCodes[index - 1].code >= errorCodes[index].code) {
                    throw invalid("$", "manifest records must be sorted and unique")
                }
            }
            for (entry in errorCodes) {
                validateVersionedCode(entry.code, "$.error_codes.code")
                if (entry.introduced.isEmpty() || entry.description.isEmpty()) {
                    throw invalid("$.error_codes", "error-code metadata cannot be empty")
                }
            }
            return RegistryManifest(semanticModel, contracts, errorCodes)
        }

        /** Strictly decodes `core.registry-manifest@1`
         * (registry_manifest.rs:232-281). */
        fun fromValue(value: PortableValue): RegistryManifest {
            val fields = schemaFields(
                value,
                "core.registry-manifest@1",
                listOf("schema", "semantic_model", "contracts", "error_codes"),
                "$",
            )
            val semanticModel = parseContractReference(fields[1], "$.semantic_model")
            val contractValues = sequenceOf(fields[2], "$.contracts")
            val contracts = contractValues.mapIndexed { index, item ->
                val path = "$.contracts[$index]"
                val entry = exactFields(item, listOf("id", "version", "stability"), path)
                val contract = ContractId(stringOf(entry[0], "$path.id"), unsigned32(entry[1], "$path.version"))
                val stability = parseContractStability(stringOf(entry[2], "$path.stability"))
                ContractManifestEntry(contract, stability)
            }
            val codeValues = sequenceOf(fields[3], "$.error_codes")
            val codes = codeValues.mapIndexed { index, item ->
                val path = "$.error_codes[$index]"
                val entry = exactFields(
                    item,
                    listOf("code", "category", "introduced", "stability", "description"),
                    path,
                )
                val code = stringOf(entry[0], "$path.code")
                val category = parseDiagnosticCategory(stringOf(entry[1], "$path.category"))
                val introduced = stringOf(entry[2], "$path.introduced")
                val stability = stringOf(entry[3], "$path.stability")
                if (stability != "Stable") {
                    throw invalid("$path.stability", "unknown error-code stability")
                }
                val description = stringOf(entry[4], "$path.description")
                ErrorCodeManifestEntry(code, category, introduced, description)
            }
            return validate(semanticModel, contracts, codes)
        }
    }

    /** Reports whether this manifest exactly equals the built-in current
     * (v7) contract set. */
    fun isCurrent(): Boolean {
        val current = of(7, ContractRegistry.forVersion(ContractRegistryVersion.V7),
            ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7))
        return semanticModel == current.semanticModel &&
            contracts == current.contracts &&
            errorCodes == current.errorCodes
    }

    /** Encodes `core.registry-manifest@1` (registry_manifest.rs:177-230). */
    fun toValue(): PortableValue {
        val contractValues = contracts.map { entry ->
            PvObject(
                listOf(
                    consema.core.Entry("id", PvString(entry.contract.id)),
                    consema.core.Entry("version", integerValue(entry.contract.version.toULong())),
                    consema.core.Entry("stability", PvString(entry.stability.name)),
                ),
            )
        }
        val codeValues = errorCodes.map { entry ->
            PvObject(
                listOf(
                    consema.core.Entry("code", PvString(entry.code)),
                    consema.core.Entry("category", PvString(entry.category.wireName)),
                    consema.core.Entry("introduced", PvString(entry.introduced)),
                    consema.core.Entry("stability", PvString("Stable")),
                    consema.core.Entry("description", PvString(entry.description)),
                ),
            )
        }
        return PvObject(
            listOf(
                consema.core.Entry("schema", PvString("core.registry-manifest@1")),
                consema.core.Entry(
                    "semantic_model",
                    referenceValue(semanticModel.id, semanticModel.version),
                ),
                consema.core.Entry("contracts", PvArray(contractValues)),
                consema.core.Entry("error_codes", PvArray(codeValues)),
            ),
        )
    }
}

/** Builds the {"id","version"} reference record. */
internal fun referenceValue(id: String, version: Int): PortableValue =
    PvObject(
        listOf(
            consema.core.Entry("id", PvString(id)),
            consema.core.Entry("version", PvInteger(BigInteger.valueOf(version.toLong()))),
        ),
    )

/** Strictly decodes a {"id","version"} contract reference
 * (registry_manifest.rs:284-290). */
internal fun parseContractReference(value: PortableValue, path: String): ContractId {
    val fields = exactFields(value, listOf("id", "version"), path)
    return ContractId(stringOf(fields[0], "$path.id"), unsigned32(fields[1], "$path.version"))
}

/** Strictly decodes a {"id","version"} profile reference
 * (registry.rs:459-465). */
internal fun parseProfileReference(value: PortableValue, path: String): ProfileReference {
    val fields = exactFields(value, listOf("id", "version"), path)
    return ProfileReference(stringOf(fields[0], "$path.id"), unsigned32(fields[1], "$path.version"))
}
