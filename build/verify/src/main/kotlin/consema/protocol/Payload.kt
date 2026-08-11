// Registered-payload validation dispatch.
//
// Data authority: crates/consema-protocol/src/payload.rs — NewProtocolMessage
// dispatches every registered contract to its full record decoder. This
// package implements those record types in stages (L0): core.cli-output@1,
// core.batch-plan@1, core.batch-result@1, core.diagnostic@1,
// core.query-definition@1, core.capability-declaration@1,
// core.profile-descriptor@1, core.error-code-registry@1, and
// core.registry-manifest@1 are validated in full here; the remaining
// contracts are validated to the schema-discriminator level until their
// owning milestone ships the record type (documented reachable-code
// difference; the shared vectors exercise only the implemented records).
// go/protocol/payload.go is a cross-reference.

package consema.protocol

import consema.core.PortableValue

/** Dispatches the registered contracts whose Kotlin record types exist in
 * this package to their full record decoders. */
internal fun validateRegisteredPayload(
    contract: ContractId,
    payload: PortableValue,
    registry: ContractRegistry,
) {
    val errorRegistry = registry.errorCodeRegistry()
    when ("${contract.id}@${contract.version}") {
        "core.batch-plan@1" -> BatchPlanMessage.fromValueWithRegistry(payload, errorRegistry)
        "core.batch-result@1" -> BatchResultMessage.fromValue(payload)
        "core.capability-declaration@1" -> CapabilityDeclaration.fromValue(payload)
        "core.cli-output@1" -> CliOutputMessage.fromValueWithRegistry(payload, errorRegistry)
        "core.diagnostic@1" -> Diagnostic.fromValue(payload, errorRegistry)
        "core.error-code-registry@1" -> validateErrorCodeManifestValue(payload)
        "core.profile-descriptor@1" -> ProfileDescriptor.fromValue(payload)
        "core.query-definition@1" -> QueryDefinition.fromProtocolValue(payload)
        "core.registry-manifest@1" -> RegistryManifest.fromValue(payload)
        else -> {
            // The remaining registered contracts validate at the envelope
            // level until their owning milestone ships the record type
            // (ProtocolMessage.of).
        }
    }
}
