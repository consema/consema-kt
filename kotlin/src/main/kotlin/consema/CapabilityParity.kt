// The L4 capability parity surface (Kotlin).
//
// Data authority (language-neutral sources first):
//   - https://github.com/consema/consema/blob/main/docs/fc-manifest-0.13.0.json (capability_set record: "8 families /
//     16 profiles / 21 query domains / 16 operation registries / 187 error
//     codes"; the manifest records the machine-readable capability facts
//     the Rust CLI derives from the facade registry).
//   - RFC 0015 §6.2 (the `families`, `profiles`, `query_domains`,
//     `operations` facts) and https://github.com/consema/consema/blob/main/docs/multi-language-implementation-plan.md §6
//     (the capability parity gate: the Kotlin mandatory capability set
//     matches the manifest; no "Rust only" mandatory behavior).
//   - https://github.com/consema/consema-rs/blob/main/consema/src/lib.rs (the facade registry this
//     surface mirrors); https://github.com/consema/consema-rs/blob/main/consema/src/bin/consema/capabilities.rs
//     (the Rust CLI derives its capabilities list from the facade registry,
//     never redeclares it).
//
// Kotlin-idiomatic design: the parity facts are derived from the Kotlin
// facade registry and the protocol registries — the function below holds no
// duplicated inventory; the CapabilityParity test pins the manifest counts.
// NOTE: 行号可能漂移，以 capability_set 计数为锚（fc-manifest 按 sync-note 重同步后行号会变）。

package consema

import consema.document.FormatOperationId
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.QueryDomain

/** The eight format families of the facade (RFC 0015 §6.2 `families`). */
data class FamilyFact(
    /** The family namespace id. */
    val id: String,
    /** The family contract version. */
    val version: Int,
)

/** One capability-parity fact group: the counts the Feature-Complete
 * Manifest pins (the fc-manifest-0.13.0.json capability_set record; line
 * numbers drift on every re-provision, the field is the anchor). */
data class CapabilityParity(
    /** The eight format families. */
    val families: List<FamilyFact>,
    /** All sixteen profiles with their owning family. */
    val profiles: List<FormatProfile>,
    /** The twenty-one query domains. */
    val queryDomains: List<QueryDomain>,
    /** The sixteen per-profile operation registries (profile id → ordered
     * operation ids). */
    val operationRegistries: Map<String, List<FormatOperationId>>,
    /** The 187 error codes of the v7 registry. */
    val errorCodes: List<String>,
) {
    companion object {
        /** Derives the Kotlin mandatory capability set from the facade and
         * protocol registries. */
        fun current(): CapabilityParity {
            val registries = LinkedHashMap<String, List<FormatOperationId>>()
            for (entry in profiles()) {
                val ids = operationRegistry(entry.profile)
                    ?: error("facade profile ${entry.profile.id} must publish an operation registry")
                registries[entry.profile.id] = ids
            }
            return CapabilityParity(
                families = formatFamilies().map { FamilyFact(it.id, it.version) },
                profiles = profiles(),
                queryDomains = queryDomains(),
                operationRegistries = registries,
                errorCodes = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)
                    .codes()
                    .map { it.code },
            )
        }
    }
}
