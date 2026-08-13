// Golden transcriptions of the toml operation registry facts.
//
// Data authority: consema-rs/consema-toml/src/operation_registry.rs:94-119 (the
// pinned registry facts: seven operations, five Supported structural
// operations in sorted id order) and conformance/vectors/operations-v1.json
// operations.v1.operation-registry (lines 18-22: toml_operation_count = 7,
// required_toml = "toml.edit.insert-entry@1").

package toml

import consema.toml.TomlOperationSupport
import consema.toml.TomlProfile
import consema.toml.formatOperationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TomlOperationsTest {

    /** operation_registry.rs:98-118: the registry publishes exactly seven
     * operations and five Supported structural operations in sorted id
     * order. */
    @Test
    fun tomlProfilePublishesTheFrozenStructuralSurface() {
        val registry = formatOperationRegistry(TomlProfile.TOML_1_0_V1)
        val structural = registry
            .filter { it.support == TomlOperationSupport.Supported }
            .map { it.id.toString() }
            .sorted()
        assertEquals(
            listOf(
                "toml.edit.insert-array-element@1",
                "toml.edit.insert-entry@1",
                "toml.edit.remove-array-element@1",
                "toml.edit.remove-entry@1",
                "toml.edit.rename-entry@1",
            ),
            structural,
        )
        assertEquals(7, registry.size)
    }

    /** operations-v1.json operations.v1.operation-registry: the required
     * structural operation is insert-entry. */
    @Test
    fun registryPinsRequiredOperations() {
        val registry = formatOperationRegistry(TomlProfile.TOML_1_0_V1)
        assertTrue(registry.any { it.id.toString() == "toml.edit.insert-entry@1" })
        assertEquals(7, registry.size)
    }

    /** operation_registry.rs:16-74: every descriptor carries its frozen
     * target role and argument schema. */
    @Test
    fun descriptorsCarryFrozenRolesAndArguments() {
        val registry = formatOperationRegistry(TomlProfile.TOML_1_0_V1)
        val insertEntry = registry.first { it.id.id == "toml.edit.insert-entry" }
        assertEquals("toml.table-item", insertEntry.targetRole.id)
        assertEquals(
            listOf("key", "value", "placement"),
            insertEntry.arguments.map { it.first },
        )
        val replaceSemantic = registry.first { it.id.id == "toml.edit.replace-scalar-semantic" }
        assertEquals(TomlOperationSupport.ExistingTypedCapability, replaceSemantic.support)
        assertEquals("toml.scalar-item", replaceSemantic.targetRole.id)
        assertEquals(
            listOf("value", "representation_policy"),
            replaceSemantic.arguments.map { it.first },
        )
    }
}
