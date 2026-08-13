// The L4 capability parity test: the Kotlin mandatory capability set
// matches the Feature-Complete Manifest (8 families / 16 profiles / 21
// query domains / 16 operation registries / 187 error codes), and no
// mandatory behavior is Rust-only (multi-language-implementation-plan.md
// §6 capability parity gate).
//
// Data authority: https://github.com/consema/consema/blob/main/docs/fc-manifest-0.13.0.json:30-34 (capability_set
// record); crates/consema/src/lib.rs:317-488 (the Rust facade's own
// registry tests this test mirrors); consema-go/go/capability_parity_test.go is a
// cross-reference only.

package consema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityParityTest {

    @Test
    fun kotlinCapabilitySetMatchesTheManifest() {
        val parity = CapabilityParity.current()
        // fc-manifest capability_set: 8 families / 16 profiles / 21 query
        // domains / 16 operation registries / 187 error codes.
        assertEquals(8, parity.families.size, "eight format families")
        assertEquals(16, parity.profiles.size, "sixteen profiles")
        assertEquals(21, parity.queryDomains.size, "twenty-one query domains")
        assertEquals(16, parity.operationRegistries.size, "sixteen operation registries")
        assertEquals(187, parity.errorCodes.size, "187 v7 error codes")
    }

    @Test
    fun familiesAreTheEightFrozenIds() {
        val ids = CapabilityParity.current().families.map { it.id }
        assertEquals(
            listOf("hcl", "ini", "java-properties", "json", "plist", "toml", "xml", "yaml"),
            ids,
            "the eight RFC 0015 §6.2 family ids",
        )
        assertTrue(CapabilityParity.current().families.all { it.version == 1 })
    }

    @Test
    fun profilesAreTheSixteenFrozenIds() {
        val ids = CapabilityParity.current().profiles.map { it.profile.id }
        assertEquals(
            listOf(
                "hcl.native",
                "hcl.tfvars",
                "ini.portable",
                "ini.python-configparser",
                "ini.windows",
                "java-properties.latin1",
                "java-properties.reader",
                "json.strict",
                "json5.standard",
                "jsonc.bounded",
                "plist.binary",
                "plist.xml",
                "toml.1.0",
                "xml.1.0-safe",
                "yaml.1.1-compat",
                "yaml.1.2-core",
            ),
            ids,
            "the sixteen RFC 0015 §6.2 profile ids in sorted order",
        )
    }

    @Test
    fun queryDomainsAreTheTwentyOneFrozenConstructors() {
        val domains = CapabilityParity.current().queryDomains
        assertEquals(21, domains.size)
        // Strictly sorted by (id, version), unique.
        for (index in 1 until domains.size) {
            val previous = domains[index - 1]
            val current = domains[index]
            assertTrue(
                previous.id < current.id || (previous.id == current.id && previous.version < current.version),
                "domains[$index] out of order",
            )
        }
        assertTrue(domains.any { it.id == "core.portable-value-query" })
        assertTrue(domains.any { it.id == "hcl.native-semantic-query" })
        assertTrue(domains.any { it.id == "plist.binary-structure-query" })
    }

    @Test
    fun everyProfilePublishesAnOperationRegistryWithFrozenCounts() {
        val parity = CapabilityParity.current()
        // The frozen per-family operation registry counts (fc-manifest F-5:
        // json 8 / toml 7 / yaml 8 / ini 8 / properties 5 / xml 8 / plist 6
        // / hcl 6).
        val expected = mapOf(
            "json.strict" to 8, "jsonc.bounded" to 8, "json5.standard" to 8,
            "toml.1.0" to 7,
            "yaml.1.2-core" to 8, "yaml.1.1-compat" to 8,
            "ini.portable" to 8, "ini.windows" to 8, "ini.python-configparser" to 8,
            "java-properties.reader" to 5, "java-properties.latin1" to 5,
            "xml.1.0-safe" to 8,
            "plist.xml" to 6, "plist.binary" to 6,
            // The tfvars profile admits attributes only, so its registry
            // publishes the four attribute operations (RFC 0014 §5;
            // kotlin/.../hcl/Operations.kt:78-106).
            "hcl.native" to 6, "hcl.tfvars" to 4,
        )
        for ((profileId, ids) in parity.operationRegistries) {
            assertEquals(
                expected[profileId],
                ids.size,
                "operation registry count for $profileId",
            )
            // Every operation id is a versioned namespace id.
            for (id in ids) {
                assertTrue(id.id.contains("."), "operation id $id must be namespaced")
                assertEquals(1, id.version, "operation $id must be v1")
            }
        }
    }

    @Test
    fun errorCodesAreStrictlySortedAndCoverTheFamilies() {
        val codes = CapabilityParity.current().errorCodes
        assertEquals(187, codes.size)
        for (index in 1 until codes.size) {
            assertTrue(codes[index - 1] < codes[index], "error codes strictly sorted")
        }
        // No mandatory capability is Rust-only: every baseline family
        // namespace has registered codes (the Kotlin implementation
        // publishes them all).
        for (namespace in listOf("json", "toml", "yaml", "ini", "java-properties")) {
            assertTrue(
                codes.any { it.startsWith("$namespace.") },
                "family $namespace must register codes (no Rust-only mandatory behavior)",
            )
        }
        // The record formats (xml/plist/hcl) keep their format-local codes
        // out of the core registry by design (RFC 0012 §12, RFC 0013 §12,
        // RFC 0014 §11: "The family codes themselves stay out of the core
        // registry") — the Kotlin implementation follows the same boundary,
        // so the registry contains none of them.
        for (namespace in listOf("xml", "plist", "hcl")) {
            assertTrue(
                codes.none { it.startsWith("$namespace.") },
                "family $namespace codes stay format-local (RFC 0012/0013/0014)",
            )
        }
        assertTrue(codes.contains("cli.data.io@1"))
        assertTrue(codes.contains("cli.detection.ambiguous@1"))
    }
}
