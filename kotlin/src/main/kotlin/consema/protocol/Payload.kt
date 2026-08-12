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
        "core.graph-projection-result@1" -> GraphProjectionResultMessage.fromValue(payload)
        "core.graph-provenance-map@1" -> GraphProvenanceMapMessage.fromValue(payload)
        "core.graph-query-result@1" -> GraphQueryResultMessage.fromValue(payload)
        "core.ini-query-result@1" -> IniQueryResultMessage.fromValue(payload)
        "core.java-properties-query-result@1" -> JavaPropertiesQueryResultMessage.fromValue(payload)
        "core.materialization-request@1" -> MaterializationRequestMessage.fromValue(payload)
        "core.materialization-request@2" -> MaterializationRequestMessageV2.fromValue(payload)
        "core.materialization-result@2" ->
            MaterializationResultMessageV2.fromValueWithRegistry(payload, errorRegistry)
        "core.portable-graph@1" ->
            PortableGraphMessage.fromValue(payload, consema.graph.PgceLimits.default)
        "core.profile-descriptor@1" -> ProfileDescriptor.fromValue(payload)
        "core.query-definition@1" -> QueryDefinition.fromProtocolValue(payload)
        "core.registry-manifest@1" -> RegistryManifest.fromValue(payload)
        "core.source-patch@2" ->
            SourcePatchMessageV2.fromValue(payload, SourcePatchLimits.default)
        "core.source-snapshot@2" ->
            SourceSnapshotMessageV2.fromValue(payload, SourceLimits.default)
        "core.yaml-query-result@1" -> YamlQueryResultMessage.fromValue(payload)
        else -> {
            // The remaining registered contracts validate at the envelope
            // level until their owning milestone ships the record type
            // (ProtocolMessage.of).
        }
    }
}
