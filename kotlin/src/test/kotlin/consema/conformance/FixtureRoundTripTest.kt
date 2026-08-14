// Fixture round-trip gate: the production-shaped fixtures under
// conformance/fixtures (the single-authority tree provisioned from the
// consema spec repository in CI) must close byte-exactly — parse -> render
// reproduces the source bytes, formation is Complete, and a reparse of the
// rendered bytes is byte-stable. The adversarial fixtures pin the
// transport facts:
//
//   - ini/windows-cp1252.ini.hex — explicit Windows-1252 bytes under the
//     Windows profile with an explicit code page (no encoding guess);
//   - ini/legacy-mixed-newline.ini.hex — deliberately mixed LF/CRLF;
//   - properties/utf16-edge.properties — a supplementary scalar and legal
//     unpaired Java UTF-16 code units through uXXXX escapes (RFC 0010 §4:
//     unpaired units stay native content, never U+FFFD);
//   - properties/latin1-resource.properties.hex — non-UTF-8 Latin-1 bytes;
//   - yaml/*.yaml — the real-project fixtures (kubernetes-workload with
//     two documents, anchor-heavy with five aliases).
//
// Facts (counts) mirror https://github.com/consema/consema-rs/blob/main/consema-conformance/tests/
// line_format_fixtures.rs and yaml_fixtures.rs. The fixture
// directory resolves through the same repository-relative rule as the
// runner (CONSEMA_REPO or an ancestor carrying conformance/vectors +
// docs/fc-manifest-0.13.0.json); when the shared tree is not reachable
// the tests fail at construction (resolveRepoRoot throws — the
// CONSEMA_REPO prerequisite, kotlin/README.md). Fixtures are read-only;
// tests never modify them.

package consema.conformance

import consema.document.FormationStatus
import consema.document.ParseLimits
import consema.document.SourceEncoding
import consema.ini.IniEncodingSelection
import consema.ini.IniParseLimits
import consema.ini.IniProfile
import consema.ini.IniSourceEncoding
import consema.ini.IniWindowsCodePage
import consema.ini.parse as parseIni
import consema.properties.JavaStringStatus
import consema.properties.PropertiesParseLimits
import consema.properties.parseLatin1
import consema.properties.parseReader
import consema.yaml.YamlProfile
import consema.yaml.parse as parseYaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureRoundTripTest {

    private val fixtures = File(resolveRepoRoot(), "conformance/fixtures")

    /** The frozen fixture inventory of this gate: every file the tests
     * consume, asserted present at construction (a missing or renamed
     * fixture fails the gate instead of silently skipping — the same exact
     * discipline as the differential case-count guards and the runner's
     * 519-case hard pin; a partially provisioned tree must never go green).
     */
    private val REQUIRED_FIXTURES: List<String> = listOf(
        "ini/windows-cp1252.ini.hex",
        "ini/legacy-mixed-newline.ini.hex",
        "properties/utf16-edge.properties",
        "properties/latin1-resource.properties.hex",
        "yaml/kubernetes-workload.yaml",
        "yaml/github-actions-ci.yaml",
        "yaml/compose-services.yaml",
        "yaml/anchor-heavy.yaml",
    )

    init {
        for (relative in REQUIRED_FIXTURES) {
            check(File(fixtures, relative).isFile) {
                "shared fixture missing: $relative (the fixture set must be provisioned whole)"
            }
        }
    }

    /** The canonical byte container: `.hex` files are lowercase-hex text
     * (see the fixtures README); the gate decodes them before parsing. */
    private fun fixtureBytes(relative: String): ByteArray {
        val path = File(fixtures, relative)
        check(path.isFile) { "shared fixture not available: $relative (the fixture set must be provisioned whole)" }
        val raw = path.readBytes()
        if (!relative.endsWith(".hex")) {
            return raw
        }
        val digits = String(raw, Charsets.US_ASCII).filterNot { it.isWhitespace() }
        require(digits.length % 2 == 0) { "hex fixture has an odd digit count" }
        return ByteArray(digits.length / 2) { index ->
            val high = Character.digit(digits[index * 2], 16)
            val low = Character.digit(digits[index * 2 + 1], 16)
            ((high shl 4) or low).toByte()
        }
    }

    private fun assertByteStableRoundTrip(
        name: String,
        parse: (ByteArray) -> consema.ini.IniDocument,
    ) {
        val source = fixtureBytes(name)
        val document = parse(source)
        assertEquals(FormationStatus.Complete, document.formationStatus(), "$name: formation")
        val rendered = document.render()
        assertTrue(rendered.contentEquals(source), "$name: parse -> render must be byte-exact")
        val reparsed = parse(rendered)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus(), "$name: reparse")
        assertTrue(reparsed.render().contentEquals(source), "$name: reparse -> render must be byte-stable")
    }

    private fun assertPropertiesRoundTrip(name: String, latin1: Boolean, count: Int) {
        val source = fixtureBytes(name)
        val parse: (ByteArray) -> consema.properties.Document = { bytes ->
            if (latin1) parseLatin1(bytes) else parseReader(bytes, SourceEncoding.Utf8)
        }
        val document = parse(source)
        assertEquals(FormationStatus.Complete, document.formationStatus(), "$name: formation")
        val rendered = document.render()
        assertTrue(rendered.contentEquals(source), "$name: parse -> render must be byte-exact")
        val reparsed = parse(rendered)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus(), "$name: reparse")
        assertTrue(reparsed.render().contentEquals(source), "$name: reparse -> render must be byte-stable")
        assertEquals(count, document.properties().size, "$name: property count")
    }

    private fun assertYamlRoundTrip(name: String, documents: Int, aliases: Int) {
        val source = fixtureBytes(name)
        val parse: (ByteArray) -> consema.yaml.Document = { bytes ->
            parseYaml(bytes, YamlProfile.Yaml12CoreV1, ParseLimits.default)
        }
        val document = parse(source)
        assertEquals(FormationStatus.Complete, document.formationStatus(), "$name: formation")
        val rendered = document.render()
        assertTrue(rendered.contentEquals(source), "$name: parse -> render must be byte-exact")
        val reparsed = parse(rendered)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus(), "$name: reparse")
        assertTrue(reparsed.render().contentEquals(source), "$name: reparse -> render must be byte-stable")
        assertEquals(documents, document.documentCount(), "$name: document count")
        assertEquals(aliases, document.aliasCount(), "$name: alias count")
    }

    // -------------------------------------------------------------------
    // INI fixtures
    // -------------------------------------------------------------------

    @Test
    fun iniWindowsCp1252RoundTripsByteExact() {
        val codePage = IniWindowsCodePage.fromNumber(1252) ?: error("CP1252 unavailable")
        val parse: (ByteArray) -> consema.ini.IniDocument = { bytes ->
            parseIni(
                bytes,
                IniProfile.WindowsV1,
                IniEncodingSelection.Explicit(IniSourceEncoding.WindowsCodePage(codePage)),
                IniParseLimits.default,
            )
        }
        val source = fixtureBytes("ini/windows-cp1252.ini.hex")
        val document = parse(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val rendered = document.render()
        assertTrue(rendered.contentEquals(source), "parse -> render must be byte-exact")
        val reparsed = parse(rendered)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus())
        assertTrue(reparsed.render().contentEquals(source), "reparse -> render must be byte-stable")
        assertEquals(1, document.sections().size, "section count")
        assertEquals(3, document.entries().size, "entry count")
    }

    @Test
    fun iniWindowsCp1252KeepsDeclaredTransportFacts() {
        val source = fixtureBytes("ini/windows-cp1252.ini.hex")
        // "Montréal" (é = 0xe9) and "€" (0x80) are not valid UTF-8.
        assertTrue(source.any { it.toInt() and 0xff == 0xe9 }, "must contain the é byte")
        assertTrue(source.any { it.toInt() and 0xff == 0x80 }, "must contain the € byte")
        val codePage = IniWindowsCodePage.fromNumber(1252) ?: error("CP1252 unavailable")
        val document = parseIni(
            source,
            IniProfile.WindowsV1,
            IniEncodingSelection.Explicit(IniSourceEncoding.WindowsCodePage(codePage)),
            IniParseLimits.default,
        )
        val values = document.entries().map { it.value }
        assertEquals("Montréal", values[0], "the explicit code page decodes é")
        assertEquals("€", values[1], "the explicit code page decodes €")
    }

    @Test
    fun iniLegacyMixedNewlineRoundTripsByteExact() {
        val parse: (ByteArray) -> consema.ini.IniDocument = { bytes ->
            parseIni(bytes, IniProfile.PortableV1, IniEncodingSelection.ProfileDefault, IniParseLimits.default)
        }
        assertByteStableRoundTrip("ini/legacy-mixed-newline.ini.hex", parse)
        val document = parse(fixtureBytes("ini/legacy-mixed-newline.ini.hex"))
        assertEquals(2, document.sections().size, "section count")
        assertEquals(3, document.entries().size, "entry count")
    }

    // -------------------------------------------------------------------
    // Java Properties fixtures
    // -------------------------------------------------------------------

    @Test
    fun propertiesUtf16EdgeRoundTripsByteExact() {
        assertPropertiesRoundTrip("properties/utf16-edge.properties", latin1 = false, count = 3)
    }

    @Test
    fun propertiesUtf16EdgeKeepsExactJavaUnits() {
        val document = parseReader(fixtureBytes("properties/utf16-edge.properties"), SourceEncoding.Utf8)
        val byKey = document.properties().associate { it.key().toUnicode() to it.value() }
        assertEquals(listOf("rocket", "unpaired.high", "unpaired.low"), byKey.keys.toList())
        assertEquals("🚀", byKey["rocket"]!!.toUnicode(), "the supplementary scalar decodes exactly")
        assertEquals(JavaStringStatus.UnpairedSurrogate, byKey["unpaired.high"]!!.status, "high surrogate stays unpaired")
        assertEquals(JavaStringStatus.UnpairedSurrogate, byKey["unpaired.low"]!!.status, "low surrogate stays unpaired")
    }

    @Test
    fun propertiesLatin1ResourceRoundTripsByteExact() {
        assertPropertiesRoundTrip("properties/latin1-resource.properties.hex", latin1 = true, count = 3)
    }

    @Test
    fun propertiesLatin1ResourceDecodesLatin1NotAccidentalUtf8() {
        val source = fixtureBytes("properties/latin1-resource.properties.hex")
        assertTrue(source.any { it.toInt() and 0xff == 0xe9 }, "must contain the é byte")
        assertTrue(source.any { it.toInt() and 0xff == 0xa3 }, "must contain the £ byte")
        assertTrue(source.any { it.toInt() and 0xff == 0xef }, "must contain the ï byte")
        val document = parseLatin1(source)
        val byKey = document.properties().associate { it.key().toUnicode() to it.value().toUnicode() }
        assertEquals(mapOf("title" to "café", "currency" to "£", "author" to "naïve"), byKey)
    }

    // -------------------------------------------------------------------
    // YAML fixtures
    // -------------------------------------------------------------------

    @Test
    fun yamlKubernetesWorkloadRoundTripsByteExact() {
        assertYamlRoundTrip("yaml/kubernetes-workload.yaml", documents = 2, aliases = 0)
    }

    @Test
    fun yamlGithubActionsCiRoundTripsByteExact() {
        assertYamlRoundTrip("yaml/github-actions-ci.yaml", documents = 1, aliases = 0)
    }

    @Test
    fun yamlComposeServicesRoundTripsByteExact() {
        assertYamlRoundTrip("yaml/compose-services.yaml", documents = 1, aliases = 0)
    }

    @Test
    fun yamlAnchorHeavyRoundTripsByteExact() {
        assertYamlRoundTrip("yaml/anchor-heavy.yaml", documents = 1, aliases = 5)
    }
}
