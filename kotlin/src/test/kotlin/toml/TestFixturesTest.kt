// W3-45 (2026-08-14): the fallback constants in TestFixtures.kt are
// transcriptions with newline normalization, not byte-for-byte copies — but
// they must never be reached when the shared tree is reachable. This test
// asserts the loader returns the real shared file bytes whenever the shared
// tree is reachable from the test working directory (the probe list mirrors
// FIXTURE_DIRS). It skips with a documented line when the tree is not
// provisioned (the fallback path is then the only option).

package toml

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals

class TestFixturesTest {

    @Test
    fun realSharedFilesArePreferredWhenReachable() {
        val names = listOf(
            "all-values.toml",
            "trivia-and-strings.toml",
            "application.toml",
            "invalid-duplicate.toml",
            "pyproject.toml",
            "Cargo.toml",
        )
        val dirs = buildList {
            add(File("conformance/fixtures/toml"))
            add(File("../conformance/fixtures/toml"))
            add(File("../../conformance/fixtures/toml"))
            System.getenv("CONSEMA_REPO")?.takeIf { it.isNotBlank() }?.let {
                add(File(File(it), "conformance/fixtures/toml"))
            }
        }
        var reachable = 0
        for (name in names) {
            val real = dirs.firstOrNull { File(it, name).isFile } ?: continue
            reachable++
            val expected = File(real, name).readBytes()
            val got = fixtureBytes(name, ByteArray(0))
            assertContentEquals(
                expected,
                got,
                "fixtureBytes($name) must return the real shared file bytes when the shared tree is reachable",
            )
        }
        if (reachable == 0) {
            println("[SKIP] TestFixturesTest.realSharedFilesArePreferredWhenReachable: conformance/fixtures/toml not reachable from the test working directory — fallback transcriptions are the only path")
        } else {
            println("fixtureBytes returned the real shared file bytes for $reachable/${names.size} fixtures")
        }
    }
}
