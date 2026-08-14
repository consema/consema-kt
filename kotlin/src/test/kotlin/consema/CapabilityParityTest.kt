// The L4 capability parity test: the Kotlin mandatory capability set
// matches the Feature-Complete Manifest (8 families / 16 profiles / 21
// query domains / 16 operation registries / 187 error codes), and no
// mandatory behavior is Rust-only (multi-language-implementation-plan.md
// §6 capability parity gate).
//
// Wave-4 R10 (2026-08-15): the five headline counts are compared against
// the provisioned docs/fc-manifest-0.13.0.json (digests.capability_set
// record) instead of hardcoded snapshots — a manifest record drift fails
// the test. The manifest is provisioned data (gitignored; CI copies it
// from the pinned consema checkout, see ci-kotlin.yml provision steps),
// so when it is not reachable the comparison is skipped (JUnit
// assumption) — a fresh checkout without provisioned data does not fail.
//
// Data authority: https://github.com/consema/consema/blob/main/docs/fc-manifest-0.13.0.json:30-34 (capability_set
// record); https://github.com/consema/consema-rs/blob/main/consema/src/lib.rs (the Rust facade's own
// registry tests this test mirrors); https://github.com/consema/consema-go/blob/main/go/capability_parity_test.go is a
// cross-reference only.
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package consema

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

class CapabilityParityTest {

    /** The provisioned manifest file: the CONSEMA_REPO path first, then
     * the nearest ancestor of the working directory carrying
     * docs/fc-manifest-0.13.0.json (the Runner.resolveRepoRoot
     * precedence), or null when not provisioned. */
    private fun provisionedManifest(): File? {
        System.getenv("CONSEMA_REPO")?.takeIf { it.isNotBlank() }?.let {
            val file = File(it, "docs/fc-manifest-0.13.0.json")
            if (file.isFile) return file
        }
        var directory = File(System.getProperty("user.dir"))
        while (true) {
            val file = File(directory, "docs/fc-manifest-0.13.0.json")
            if (file.isFile) return file
            val parent = directory.parentFile ?: return null
            directory = parent
        }
    }

    /** The five capability counts from the manifest's
     * digests.capability_set.value sentence ("8 families / 16 profiles /
     * 21 query domains / 16 operation registries / 187 error codes"), or
     * null when the record is absent or unrecognized. */
    private fun manifestCapabilityCounts(manifest: File): List<Int>? {
        val match = Regex(
            "(\\d+) families / (\\d+) profiles / (\\d+) query domains / " +
                "(\\d+) operation registries / (\\d+) error codes",
        ).find(manifest.readText()) ?: return null
        return match.groupValues.drop(1).map { it.toInt() }
    }

    @Test
    fun kotlinCapabilitySetMatchesTheManifest() {
        val parity = CapabilityParity.current()
        val counts = provisionedManifest()?.let { manifestCapabilityCounts(it) }
        if (counts == null) {
            Assumptions.assumeTrue(
                false,
                "provisioned docs/fc-manifest-0.13.0.json (digests.capability_set) not reachable — live-manifest comparison skipped",
            )
            return
        }
        assertEquals(counts[0], parity.families.size, "families match the manifest capability_set")
        assertEquals(counts[1], parity.profiles.size, "profiles match the manifest capability_set")
        assertEquals(counts[2], parity.queryDomains.size, "query domains match the manifest capability_set")
        assertEquals(counts[3], parity.operationRegistries.size, "operation registries match the manifest capability_set")
        assertEquals(counts[4], parity.errorCodes.size, "error codes match the manifest capability_set")
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
            // kotlin/src/main/kotlin/consema/hcl/Operations.kt).
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
