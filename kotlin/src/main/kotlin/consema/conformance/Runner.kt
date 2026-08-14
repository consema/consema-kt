// The Kotlin conformance runner over the shared language-neutral vectors.
//
// Data authority (language-neutral sources first):
//   - conformance/README.md (rules 3-4: the runner is only an executor; the
//     vectors are the authority; every suite must validate its case count so
//     the runner never silently skips unknown items).
//   - https://github.com/consema/consema-rs/blob/main/consema-conformance/src/lib.rs (ConformanceReport shape)
//     and the per-suite Rust runners (the dispatch authority); the Rust
//     runner parses vector files with its own strict JSON parser and
//     projects them to PortableValue (the same file, lib.rs), which
//     the Kotlin runner mirrors with consema.json.
//   - https://github.com/consema/consema/blob/main/docs/five-language-ci-design.md §2 (the five-runner contract: vector
//     files read by explicit repository-relative path, no embedded copies;
//     per-runner fixed validations — suite id prefix `consema.*`, case-id
//     dedupe, 18/519 count assertion, aggregate digest assertion, unknown
//     case rejection; skip discipline).
//   - https://github.com/consema/consema/blob/main/docs/fc-manifest-0.13.0.json — digests.conformance_suite
//     (the aggregate_sha256 value and the note key document the algorithm:
//     file-name byte-order sort, per-file sha256 lowercase hex, lines
//     `{basename}:{digest}` joined with '\n' without a trailing newline,
//     then sha256 of that UTF-8 string; recorded value
//     cfd6e296da5b22b62d37b076d35bf6bbf58b0678ceddb37eea51a8b47200ab6a).
//   - https://github.com/consema/consema-go/blob/main/go/conformance/conformance.go (cross-reference only).
//
// Kotlin-idiomatic design: immutable report data classes, a Runner with
// explicit repository paths, and one handler function per suite (the
// mirror of the Go allSuites table). The vector files are read from disk at
// run time — the runner embeds no vector copy: the vector contents remain
// the authority, while the 18/519 inventory and the per-suite counts are
// hard-pinned here (ALL_SUITES) and the aggregate digest is compared
// against the manifest record (the literal digest pin lives in
// ci-kotlin.yml, the kotlin-conformance digest step).

package consema.conformance

import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.document.ParseLimits
import consema.json.JsonProfile
import consema.json.ProjectionRequest
import consema.json.ProjectionTarget
import consema.json.ProjectionResult
import consema.json.project
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/** One documented skip: the case was not executed because its capability is
 * not implemented by this Kotlin implementation. Zero-construction fact:
 * no suite handler currently builds a SkipRecord — the skipped lists stay
 * empty and ConformanceRunnerTest asserts 0 skipped; the type exists so a
 * future skip would be documented (never silent) rather than dropped. */
data class SkipRecord(
    /** The stable case identifier. */
    val id: String,
    /** The declared mandatory capability. */
    val capability: String,
    /** Why the capability is not implemented. */
    val reason: String,
)

/** One failed case with its message. */
data class CaseFailure(
    /** The stable case identifier. */
    val id: String,
    /** The failure description. */
    val message: String,
)

/** The run report of one vector suite, mirroring the Rust ConformanceReport
 * shape plus the documented skips. */
data class SuiteReport(
    /** The suite identifier read from the vector file. */
    val suite: String,
    /** The semantic-model identifier read from the vector file, when the
     * suite carries one. */
    val semanticModel: String,
    /** The frozen case count assertion. */
    val expectedCases: Int,
    /** The stable passing case IDs. */
    val passed: List<String>,
    /** The documented skips (success; never silent). */
    val skipped: List<SkipRecord>,
    /** The stable case IDs and failure descriptions. */
    val failed: List<CaseFailure>,
) {
    /** Whether the frozen case count matched the vector file
     * (conformance/README.md rule 4). */
    fun countAsserted(): Boolean = expectedCases == passed.size + skipped.size + failed.size

    /** Whether every executed case passed and the count assertion held
     * (documented skips count as success). */
    fun conformant(): Boolean = failed.isEmpty() && countAsserted()
}

/** The aggregate vector digest verification (fc-manifest conformance_suite). */
data class DigestResult(
    /** Whether the computed aggregate matches the manifest. */
    val ok: Boolean,
    /** The computed aggregate sha256. */
    val computed: String,
    /** The manifest-recorded aggregate sha256. */
    val recorded: String,
    /** The counted vector file count. */
    val suites: Int,
    /** The counted total case count. */
    val cases: Int,
)

/** The complete conformance run result. */
data class RunReport(
    /** The aggregate digest verification result. */
    val digest: DigestResult,
    /** The per-suite reports in vector inventory order. */
    val suites: List<SuiteReport>,
    /** The total case count across suites. */
    val total: Int,
    /** The total passing case count. */
    val passed: Int,
    /** The total documented skip count. */
    val skipped: Int,
    /** The total failing case count. */
    val failed: Int,
) {
    /** Whether every applicable case passed, every count assertion held,
     * the aggregate digest matched the manifest, and the full frozen
     * inventory was actually executed. The executed-count hard assertion
     * closes the empty-run hole: a suite dropped from ALL_SUITES (or a
     * registration bug) would otherwise leave a smaller-but-green run that
     * the per-suite checks cannot see. */
    fun conformant(): Boolean {
        if (!digest.ok) return false
        if (suites.size != ALL_SUITES.size) return false
        if (total != ALL_SUITES.sumOf { it.expectedCases }) return false
        return suites.all { it.conformant() }
    }
}

/** One frozen vector suite definition (fc-manifest 519 inventory). */
data class SuiteDefinition(
    /** The vector file basename. */
    val file: String,
    /** The frozen suite identifier. */
    val suiteId: String,
    /** The required semantic-model identifier; empty when the suite carries
     * none. */
    val semanticModel: String,
    /** The frozen case count assertion. */
    val expectedCases: Int,
    /** Runs the suite. */
    val run: (Runner, SuiteData) -> SuiteReport,
)

/** The frozen 18-suite inventory (fc-manifest conformance_suite; the case
 * counts are re-pinned by the digest check against the manifest). The
 * semantic-model identifiers are the declarations of the authoritative
 * vector files (each suite's semantic_model field, pinned here like the
 * case counts) — wave-4 R4: all 18 suites validate their semantic-model
 * identifier, declaration-driven; suites whose vector carries none keep
 * the empty declaration and are skipped by the equality check. */
val ALL_SUITES: List<SuiteDefinition> = listOf(
    SuiteDefinition("v1.json", "consema.conformance@1", "", 30, ::runV1),
    SuiteDefinition("toml-v1.json", "consema.toml.conformance@1", "", 18, ::runTomlV1),
    SuiteDefinition(
        "protocol-v1.json",
        "consema.protocol.conformance@1",
        "core.semantic-model@1",
        32,
        ::runProtocolV1,
    ),
    SuiteDefinition(
        "source-v1.json",
        "consema.source.conformance@1",
        "core.semantic-model@2",
        28,
        ::runSourceV1,
    ),
    SuiteDefinition(
        "syntax-query-v1.json",
        "consema.syntax-query.conformance@1",
        "core.semantic-model@2",
        19,
        ::runSyntaxQueryV1,
    ),
    SuiteDefinition(
        "protocol-v2.json",
        "consema.protocol.conformance@2",
        "core.semantic-model@2",
        11,
        ::runProtocolV2,
    ),
    SuiteDefinition(
        "operations-v1.json",
        "consema.operations.conformance@1",
        "core.semantic-model@3",
        35,
        ::runOperationsV1,
    ),
    SuiteDefinition(
        "json-family-v2.json",
        "consema.json-family.conformance@2",
        "core.semantic-model@4",
        33,
        ::runJsonFamilyV2,
    ),
    SuiteDefinition(
        "portable-graph-v1.json",
        "consema.portable-graph.conformance@1",
        "",
        10,
        ::runPortableGraphV1,
    ),
    SuiteDefinition(
        "semantic-model-v5.json",
        "consema.semantic-model-v5.conformance@1",
        "core.semantic-model@5",
        22,
        ::runSemanticModelV5,
    ),
    SuiteDefinition("yaml-v1.json", "consema.yaml.conformance@1", "", 31, ::runYamlV1),
    SuiteDefinition(
        "semantic-model-v6.json",
        "consema.semantic-model-v6.conformance@1",
        "core.semantic-model@6",
        25,
        ::runSemanticModelV6,
    ),
    SuiteDefinition("ini-v1.json", "consema.ini.conformance@1", "", 20, ::runIniV1),
    SuiteDefinition(
        "java-properties-v1.json",
        "consema.java-properties.conformance@1",
        "",
        25,
        ::runJavaPropertiesV1,
    ),
    SuiteDefinition(
        "xml-1-0-safe-v1.json",
        "consema.xml-1-0-safe.conformance@1",
        "core.semantic-model@6",
        34,
        ::runXml10SafeV1,
    ),
    SuiteDefinition(
        "plist-v1.json",
        "consema.plist.conformance@1",
        "core.semantic-model@6",
        49,
        ::runPlistV1,
    ),
    SuiteDefinition(
        "hcl-v1.json",
        "consema.hcl.conformance@1",
        "core.semantic-model@6",
        57,
        ::runHclV1,
    ),
    SuiteDefinition(
        "cli-v1.json",
        "consema.cli.conformance@1",
        "core.semantic-model@7",
        40,
        ::runCliV1,
    ),
)

/** One loaded vector case. */
data class CaseData(
    /** The stable case identifier. */
    val id: String,
    /** The declared mandatory capability. */
    val capability: String,
    /** The declared contract of construction-based suites
     * (protocol-v1); empty otherwise. */
    val contract: String,
    /** The operation input facts. */
    val input: PortableValue?,
    /** The public expectation facts. */
    val expected: PortableValue?,
    /** The zero-based case ordinal in the vector file. */
    val index: Int,
)

/** One loaded vector suite. */
data class SuiteData(
    /** The suite identifier from the vector file. */
    val suite: String,
    /** The semantic-model identifier, when present. */
    val semanticModel: String,
    /** The loaded cases in file order. */
    val cases: List<CaseData>,
)

/** Executes the shared vector suites from explicit repository paths. */
class Runner(
    /** The repository `conformance/vectors` directory. */
    val vectorsDir: String,
    /** The repository `conformance/fixtures` directory. */
    val fixturesDir: String,
    /** The Feature-Complete Manifest whose conformance_suite record pins the
     * aggregate digest. */
    val manifestPath: String,
) {
    /** Executes every shared vector suite and verifies the aggregate
     * digest. */
    fun run(): RunReport {
        val digest = verifyVectorsDigest()
        val suites = ALL_SUITES.map { runSuite(it) }
        var total = 0
        var passed = 0
        var skipped = 0
        var failed = 0
        for (suite in suites) {
            total += suite.passed.size + suite.skipped.size + suite.failed.size
            passed += suite.passed.size
            skipped += suite.skipped.size
            failed += suite.failed.size
        }
        return RunReport(digest, suites, total, passed, skipped, failed)
    }

    /** Loads and runs one vector suite with the fixed validations: suite
     * identifier, semantic-model identifier, case-ID uniqueness, case
     * count, capability dispatch, and unknown-case rejection. */
    fun runSuite(definition: SuiteDefinition): SuiteReport {
        val data = loadSuite(definition)
        if (data == null) {
            return SuiteReport(
                suite = definition.suiteId,
                semanticModel = definition.semanticModel,
                expectedCases = definition.expectedCases,
                passed = emptyList(),
                skipped = emptyList(),
                failed = listOf(CaseFailure("suite.parse", "vector file could not be loaded")),
            )
        }
        val report = SuiteReport(
            suite = data.suite,
            semanticModel = data.semanticModel,
            expectedCases = definition.expectedCases,
            passed = mutableListOf(),
            skipped = mutableListOf(),
            failed = mutableListOf(),
        )
        // Wave-4 R4: the semantic-model identifier is validated for every
        // suite, declaration-driven — the expected value is pinned in
        // ALL_SUITES from the vector declarations, and a suite whose
        // vector carries no semantic model has an empty declaration that
        // the equality check skips naturally. Previously the check only
        // ran when the declaration was non-empty, leaving the 9 suites
        // whose vectors declare a model (cli-v1 @7, hcl/plist/xml @6,
        // json-family-v2 @4, operations-v1 @3, protocol-v1 @1,
        // source-v1/syntax-query-v1 @2) unvalidated.
        if (data.suite != definition.suiteId ||
            data.semanticModel != definition.semanticModel
        ) {
            return report.copy(
                failed = report.failed + CaseFailure(
                    "suite.schema",
                    "unexpected suite or semantic-model identifier",
                ),
            )
        }
        val seen = HashSet<String>()
        val failures = report.failed.toMutableList()
        for (vector in data.cases) {
            if (!seen.add(vector.id)) {
                failures.add(CaseFailure(vector.id, "duplicate case id"))
            }
        }
        if (definition.expectedCases != data.cases.size) {
            failures.add(
                CaseFailure(
                    "suite.count",
                    "case count changed: expected ${definition.expectedCases}, found ${data.cases.size}",
                ),
            )
            // The count assertion fails the suite; the cases still run so
            // the report carries the full case detail.
        }
        val inner = try {
            definition.run(this, data)
        } catch (e: Exception) {
            // A handler bug must fail the suite, never escape the runner
            // (the Rust runner records per-case errors the same way).
            SuiteReport(
                suite = definition.suiteId,
                semanticModel = definition.semanticModel,
                expectedCases = definition.expectedCases,
                passed = emptyList(),
                skipped = emptyList(),
                failed = listOf(CaseFailure("suite.crash", e.toString())),
            )
        }
        return report.copy(
            passed = report.passed + inner.passed,
            skipped = report.skipped + inner.skipped,
            failed = failures + inner.failed,
        )
    }

    /** Reads one vector file, parses it as strict JSON with the family
     * parser, and projects it to the PortableValue model (the Rust runner
     * does the same, lib.rs). Returns null when the file is missing
     * or malformed. */
    fun loadSuite(definition: SuiteDefinition): SuiteData? {
        val file = File(File(vectorsDir, definition.file).path)
        if (!file.isFile) return null
        val value = parseVectorJson(file.readBytes()) ?: return null
        val root = value as? PvObject ?: return null
        val suite = (root.get("suite") as? PvString)?.value ?: return null
        val model = (root.get("semantic_model") as? PvString)?.value ?: ""
        val cases = (root.get("cases") as? PvArray)?.items() ?: return null
        val loaded = ArrayList<CaseData>(cases.size)
        for ((index, item) in cases.withIndex()) {
            val obj = item as? PvObject ?: return null
            val id = (obj.get("id") as? PvString)?.value ?: return null
            val capability = (obj.get("capability") as? PvString)?.value ?: ""
            val contract = (obj.get("contract") as? PvString)?.value ?: ""
            val input = obj.get("input")
            val expected = obj.get("expected")
            loaded.add(CaseData(id, capability, contract, input, expected, index))
        }
        return SuiteData(suite, model, loaded)
    }

    /** Computes the aggregate sha256 of the vector files and compares it
     * against the Feature-Complete Manifest conformance_suite record:
     * file-name byte-order sort, per-file sha256 lowercase hex, lines
     * `{basename}:{digest}` joined with '\n' without a trailing newline,
     * then sha256 of that UTF-8 string. */
    fun verifyVectorsDigest(): DigestResult {
        val names = File(vectorsDir).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
        val builder = StringBuilder()
        var totalCases = 0
        for ((index, name) in names.withIndex()) {
            val bytes = File(vectorsDir, name).readBytes()
            val count = countCases(bytes) ?: return DigestResult(false, "", "", names.size, totalCases)
            totalCases += count
            val digest = sha256Hex(bytes)
            builder.append(name).append(':').append(digest)
            if (index + 1 < names.size) {
                builder.append('\n')
            }
        }
        val aggregate = sha256Hex(builder.toString().toByteArray(Charsets.UTF_8))
        val manifest = readManifestConformanceSuite() ?: return DigestResult(false, aggregate, "", names.size, totalCases)
        return DigestResult(
            ok = aggregate == manifest.aggregate &&
                names.size == manifest.suites &&
                totalCases == manifest.cases,
            computed = aggregate,
            recorded = manifest.aggregate,
            suites = names.size,
            cases = totalCases,
        )
    }

    private data class ManifestConformanceSuite(val aggregate: String, val suites: Int, val cases: Int)

    private fun readManifestConformanceSuite(): ManifestConformanceSuite? {
        val file = File(manifestPath)
        if (!file.isFile) return null
        val root = parseVectorJson(file.readBytes()) as? PvObject ?: return null
        val digests = root.get("digests") as? PvObject ?: return null
        val suite = digests.get("conformance_suite") as? PvObject ?: return null
        val aggregate = (suite.get("aggregate_sha256") as? PvString)?.value ?: return null
        val suites = (suite.get("suites") as? PvInteger)?.value?.toInt() ?: return null
        val cases = (suite.get("cases") as? PvInteger)?.value?.toInt() ?: return null
        return ManifestConformanceSuite(aggregate, suites, cases)
    }
}

/** Parses one vector file as strict JSON into the core value model. The
 * vector files are plain strict JSON documents; the family parser plus the
 * exact best-core projection is the runner's own decoder (mirroring the
 * Rust runner, lib.rs). Returns null for a malformed file. */
fun parseVectorJson(bytes: ByteArray): PortableValue? {
    val document = try {
        consema.json.parse(bytes, JsonProfile.StrictV1, ParseLimits.default)
    } catch (e: Exception) {
        return null
    }
    val request = ProjectionRequest.builder(ProjectionTarget.BestExactCoreV1).build()
    val projected = document.project(request)
    return when (projected) {
        is ProjectionResult.Complete -> projected.projection.value
        is ProjectionResult.Failed -> null
    }
}

/** Counts the cases array of one vector file. */
fun countCases(bytes: ByteArray): Int? {
    val root = parseVectorJson(bytes) as? PvObject ?: return null
    val cases = root.get("cases") as? PvArray ?: return null
    return cases.size()
}

/** Lowercase hex sha256 of one byte array. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = CharArray(digest.size * 2)
    val digits = "0123456789abcdef"
    for (i in digest.indices) {
        val value = digest[i].toInt() and 0xff
        hex[i * 2] = digits[value ushr 4]
        hex[i * 2 + 1] = digits[value and 0x0f]
    }
    return String(hex)
}

/** Resolves the repository root: the `CONSEMA_REPO` environment variable,
 * or the nearest ancestor of the working directory containing
 * `conformance/vectors`. */
fun resolveRepoRoot(): String {
    System.getenv("CONSEMA_REPO")?.takeIf { it.isNotBlank() }?.let { return it }
    var directory = File(System.getProperty("user.dir"))
    while (true) {
        if (File(directory, "conformance/vectors").isDirectory &&
            File(directory, "docs/fc-manifest-0.13.0.json").isFile
        ) {
            return directory.path
        }
        val parent = directory.parentFile
            ?: error("repository root not found (set CONSEMA_REPO or run inside the checkout)")
        directory = parent
    }
}

// ---------------------------------------------------------------------------
// PortableValue field helpers over the vector value model.
// ---------------------------------------------------------------------------

/** Returns one named field of an Object value. */
fun objectField(value: PortableValue?, name: String): PortableValue? =
    (value as? PvObject)?.get(name)

/** Reads one String field. */
fun stringField(value: PortableValue?, name: String): String? =
    (objectField(value, name) as? PvString)?.value

/** Reads one Boolean field. */
fun booleanField(value: PortableValue?, name: String): Boolean? =
    (objectField(value, name) as? PvBoolean)?.value

/** Reads one Integer field as a Long. */
fun longField(value: PortableValue?, name: String): Long? =
    (objectField(value, name) as? PvInteger)?.value?.toLong()

/** Reads one Sequence field. */
fun sequenceField(value: PortableValue?, name: String): List<PortableValue>? =
    (objectField(value, name) as? PvArray)?.items()

/** Reads one named input field of a case. */
fun caseInput(case: CaseData, name: String): PortableValue? = objectField(case.input, name)

/** Reads one named expected field of a case. */
fun caseExpected(case: CaseData, name: String): PortableValue? = objectField(case.expected, name)

/** One input string field. */
fun inputString(case: CaseData, name: String): String? = stringField(case.input, name)

/** One expected string field. */
fun expectedString(case: CaseData, name: String): String? = stringField(case.expected, name)

/** One expected boolean field. */
fun expectedBoolean(case: CaseData, name: String): Boolean? = booleanField(case.expected, name)

/** One expected integer field as a Long. */
fun expectedLong(case: CaseData, name: String): Long? = longField(case.expected, name)

/** One expected sequence field. */
fun expectedSequence(case: CaseData, name: String): List<PortableValue>? =
    sequenceField(case.expected, name)

/** One input sequence field. */
fun inputSequence(case: CaseData, name: String): List<PortableValue>? =
    sequenceField(case.input, name)

/** Decodes one lowercase hex string into bytes; null for malformed input. */
fun decodeHex(text: String): ByteArray? {
    if (text.length % 2 != 0) return null
    val bytes = ByteArray(text.length / 2)
    for (i in bytes.indices) {
        val high = Character.digit(text[i * 2], 16)
        val low = Character.digit(text[i * 2 + 1], 16)
        if (high < 0 || low < 0) return null
        bytes[i] = ((high shl 4) or low).toByte()
    }
    return bytes
}

/** Lowercase hex encoding of one byte array. */
fun toHex(bytes: ByteArray): String {
    val hex = CharArray(bytes.size * 2)
    val digits = "0123456789abcdef"
    for (i in bytes.indices) {
        val value = bytes[i].toInt() and 0xff
        hex[i * 2] = digits[value ushr 4]
        hex[i * 2 + 1] = digits[value and 0x0f]
    }
    return String(hex)
}

/** Compact value constructor from vector descriptors: `"Null"`, booleans,
 * `{"integer": "..."}`, `{"decimal": "..."}`, `{"string": "..."}`,
 * `{"sequence": [...]}`, and `{"object": {...}}` (lib.rs). */
fun valueFromInput(input: PortableValue?): PortableValue? {
    if (input == null) return null
    if ((input as? PvString)?.value == "Null") return consema.core.PvNull
    if (input is PvBoolean) return input
    if (input is PvString) return input
    if (input is PvInteger) return input
    val obj = input as? PvObject ?: return input
    (obj.get("integer") as? PvString)?.let { text ->
        return try {
            consema.core.PvInteger(BigInteger(text.value))
        } catch (e: NumberFormatException) {
            null
        }
    }
    (obj.get("decimal") as? PvString)?.let { text ->
        return parseDecimalJson(text.value)
    }
    (obj.get("string") as? PvString)?.let { return it }
    (obj.get("sequence") as? PvArray)?.let { sequence ->
        val elements = sequence.items().map { valueFromInput(it) ?: return null }
        return PvArray(elements)
    }
    (obj.get("object") as? PvObject)?.let { nested ->
        return decodeBareObject(nested)
    }
    // Bare object descriptor without a wrapping key.
    return decodeBareObject(obj)
}

private fun decodeBareObject(obj: PvObject): PortableValue? {
    val entries = ArrayList<consema.core.Entry>(obj.size())
    for (entry in obj.entries()) {
        val value = valueFromInput(entry.value) ?: return null
        entries.add(consema.core.Entry(entry.key, value))
    }
    return PvObject(entries)
}

/** Parses one decimal text into a canonical [PvDecimal]; null for
 * malformed input. */
fun parseDecimalJson(text: String): consema.core.PvDecimal? {
    // The vector discipline keeps decimals as coefficient×10^exponent
    // spellings (or plain JSON number texts); the core Decimal parser in
    // the families mirrors the Rust Decimal::parse_json_number. A plain
    // decimal spelling is parsed here because the vectors carry a few
    // plain JSON number texts in expected fields.
    val trimmed = text.trim()
    var coefficientText = trimmed
    var exponent = BigInteger.ZERO
    val eIndex = trimmed.indexOfFirst { it == 'e' || it == 'E' }
    if (eIndex >= 0) {
        val exponentText = trimmed.substring(eIndex + 1)
        exponent = exponentText.toBigIntegerOrNull() ?: return null
        coefficientText = trimmed.substring(0, eIndex)
    }
    val dotIndex = coefficientText.indexOf('.')
    var scale = BigInteger.ZERO
    if (dotIndex >= 0) {
        val fraction = coefficientText.substring(dotIndex + 1)
        coefficientText = coefficientText.substring(0, dotIndex) + fraction
        scale = BigInteger.valueOf(-fraction.length.toLong())
    }
    val coefficient = coefficientText.toBigIntegerOrNull() ?: return null
    return consema.core.PvDecimal.of(coefficient, exponent.add(scale))
}
