// Registry pinning tests — intent documents.
//
// The frozen counts and sortedness are transcribed from
// consema-rs/consema-protocol/src/contract.rs (CONTRACTS_V1..V7 at contract.rs:
// 71/90/111/142/178/225) and consema-rs/consema-protocol/src/error_registry.rs
// (ERROR_CODES_V1..V7; the typed arrays at error_registry.rs:412/617/662/
// 935/1172/1339): 16/18/25/25/30/38/41 contracts and 55/62/90/92/132/166/187
// codes. These tests run once the toolchain is ready (START GATE).

package consema.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegistryTest {

    private val contractCounts = mapOf(
        ContractRegistryVersion.V1 to 16,
        ContractRegistryVersion.V2 to 18,
        ContractRegistryVersion.V3 to 25,
        ContractRegistryVersion.V4 to 25,
        ContractRegistryVersion.V5 to 30,
        ContractRegistryVersion.V6 to 38,
        ContractRegistryVersion.V7 to 41,
    )

    private val errorCounts = mapOf(
        ErrorRegistryVersion.V1 to 55,
        ErrorRegistryVersion.V2 to 62,
        ErrorRegistryVersion.V3 to 90,
        ErrorRegistryVersion.V4 to 92,
        ErrorRegistryVersion.V5 to 132,
        ErrorRegistryVersion.V6 to 166,
        ErrorRegistryVersion.V7 to 187,
    )

    @Test
    fun contractCountsAreFrozen() {
        for ((version, count) in contractCounts) {
            val contracts = ContractRegistry.forVersion(version).contracts()
            assertEquals(count, contracts.size, "contract count for $version")
            // Strictly sorted by (id, version), unique.
            for (index in 1 until contracts.size) {
                val previous = contracts[index - 1]
                val current = contracts[index]
                assertTrue(
                    previous.id < current.id ||
                        (previous.id == current.id && previous.version < current.version),
                    "contracts[$index] out of order: $previous then $current",
                )
            }
        }
    }

    @Test
    fun errorCodeCountsAreFrozen() {
        for ((version, count) in errorCounts) {
            val codes = ErrorCodeRegistry.forVersion(version).codes()
            assertEquals(count, codes.size, "error code count for $version")
            // Strictly sorted and unique.
            for (index in 1 until codes.size) {
                assertTrue(codes[index - 1].code < codes[index].code, "codes[$index] out of order")
            }
        }
    }

    @Test
    fun errorRegistriesAreSupersets() {
        // Each version's codes are a strict superset of the previous
        // version's codes.
        for (version in ErrorRegistryVersion.entries.drop(1)) {
            val previous = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.entries[version.ordinal - 1]).codes()
            val current = ErrorCodeRegistry.forVersion(version).codes()
            for (descriptor in previous) {
                assertTrue(current.any { it.code == descriptor.code }, "${descriptor.code} missing in $version")
            }
        }
    }

    @Test
    fun v7PinsKnownCodes() {
        val registry = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)
        assertTrue(registry.contains("core.pgce.invalid@1"))
        assertTrue(registry.contains("core.graph.resource-limit@1"))
        assertTrue(registry.contains("cli.usage.unknown-command@1"))
        assertTrue(registry.contains("json.projection.incomplete-document@1"))
        // core.pvce.* codes are NOT registry entries (verified by grep of
        // error_registry.rs); the codec codes live on the codec exceptions.
        assertEquals(null, registry.descriptor("core.pvce.invalid-magic@1"))
    }

    @Test
    fun v7RecognizesEveryContractId() {
        val registry = ContractRegistry.forVersion(ContractRegistryVersion.V7)
        for (descriptor in registry.contracts()) {
            assertTrue(registry.recognizes(ContractId(descriptor.id, descriptor.version)))
        }
    }
}
