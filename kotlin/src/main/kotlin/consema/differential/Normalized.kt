// The Kotlin side of the cross-language normalized-result differential
// harness (milestone 0.15.0 G1.5, bidirectional since 0.19.0 G5.2;
// docs/five-language-ci-design.md §3.3).
//
// The harness compares the language-neutral normalized results of the same
// data-driven input set (conformance/differential/normalized/cases.json,
// 108 cases) executed by the Rust SDK
// (crates/consema-conformance/examples/emit_normalized_results.rs) and by
// this file. Kotlin never imports or calls Rust: the Rust side emits one
// `<case-id>.txt` evidence file per case, the Kotlin test computes the same
// normalized facts and compares them field by field, and the reverse
// direction emits the Kotlin evidence files that the Rust example's consume
// mode (--consume) compares with its own results.
//
// The compared facts are exactly the language-neutral behavior surface of
// roadmap §11.2: parse formation, diagnostic code/order (never text), query
// count/identity/order, projection/materialization reports, edit result
// bytes or failure codes, and resource-limit completion semantics. The
// output vocabulary is mirrored verbatim by the Rust example and the Go
// harness (go/conformance/differential/normalized/runner.go + source.go);
// it contains no Rust internal type names. Error texts never participate in
// the comparison (RFC 0016 §6).

package consema.differential

import consema.core.Kind
import consema.core.PortableValue
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.document.AssociationPlacement
import consema.document.DecodedPosition
import consema.document.MaterializationLimits
import consema.document.MaterializationRequest
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ParseLimits
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.document.SourceLimits
import consema.document.SourcePatch
import consema.document.SourcePatchLimits
import consema.document.SourceReplacement
import consema.document.SourceSnapshot
import consema.document.EncodingRequest
import consema.document.FormationStatus
import consema.document.BomKind
import consema.document.BomPolicy
import consema.json.JsonProfile
import consema.json.SemanticAvailability
import consema.json.JsonValueKind
import consema.json.commit
import consema.json.project
import consema.ini.commit
import consema.ini.project
import consema.properties.commit
import consema.properties.project
import consema.toml.commit
import consema.toml.project
import consema.yaml.commit
import consema.yaml.projectValue
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryDomain
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QuerySelection
import consema.protocol.decodeJson
import java.io.File
import java.math.BigInteger

/** The frozen manifest id of the normalized input set. */
const val NORMALIZED_MANIFEST = "consema.differential.normalized@1"

/** The task's lower bound for the input set (the Go harness's MinCaseCount). */
const val NORMALIZED_MIN_CASES = 104

// ---------------------------------------------------------------------------
// Case file schema (data-driven; shared with the Rust example)
// ---------------------------------------------------------------------------

data class NormalizedCase(
    val id: String,
    val kind: String, // "document" or "source"
    val format: String,
    val profile: String,
    val source: String,
    val foreignSource: String,
    val foreignSourceHex: String,
    val parseLimits: ParseLimitsDesc?,
    val steps: List<StepDesc>,
    val input: SourceInputDesc?,
    val request: EncodingRequestDesc?,
    val positions: List<Int>,
    val patch: PatchDesc?,
)

data class ParseLimitsDesc(
    val maxSourceBytes: Int?,
    val maxNestingDepth: Int?,
    val maxTokenCount: Int?,
    val maxNodeCount: Int?,
    val maxDiagnostics: Int?,
)

data class StepDesc(
    val op: String,
    val domain: String,
    val domainVersion: Int,
    val filters: List<FilterDesc>,
    val combine: String,
    val selection: String,
    val queryLimits: QueryLimitsDesc?,
    val target: String,
    val duplicatePolicy: String,
    val input: String,
    val valueJSON: String,
    val entryMapping: EntryMappingDesc?,
    val targetProfile: String,
    val style: String,
    val newline: String,
    val matLimits: MaterializeLimitsDesc?,
    val operations: List<EditOpDesc>,
)

data class FilterDesc(val operator: String, val argument: String)

data class QueryLimitsDesc(val maxResults: Int?, val maxSteps: Int?)

data class EntryMappingDesc(val keyJSON: String, val valueJSON: String)

data class MaterializeLimitsDesc(
    val maxOutputBytes: Int?,
    val maxInputNodes: Int?,
    val maxDepth: Int?,
    val maxProvenanceEntries: Int?,
)

data class EditOpDesc(
    val operation: String,
    val target: TargetDesc?,
    val value: ValueDesc?,
    val literalHex: String,
    val name: String,
    val policy: String,
    val placement: PlacementDesc?,
)

data class TargetDesc(val kind: String, val ordinal: Int, val foreign: Boolean)

data class ValueDesc(
    val nullValue: Boolean?,
    val boolean: Boolean?,
    val integer: String,
    val decimal: String,
    val string: String,
    val binary64: String,
)

data class PlacementDesc(val at: String, val beforeOrdinal: Int?, val afterOrdinal: Int?)

data class SourceInputDesc(val rawHex: String, val source: String)

data class EncodingRequestDesc(
    val profileDefault: String,
    val declaration: String,
    val callerOverride: String,
    val bomPolicy: String,
)

data class PatchDesc(val replacements: List<PatchReplacementDesc>, val applyTo: String)

data class PatchReplacementDesc(val oldStart: Int, val oldEnd: Int, val replacementHex: String)

/** Loads and validates the checked-in case set (manifest, count, unique ids,
 * schema validity). Throws [IllegalArgumentException] on any violation. */
fun loadNormalizedCaseFile(file: File): List<NormalizedCase> {
    val root = loadCaseFile(file)
    val manifest = objectString(root, "manifest", "case file")
    require(manifest == NORMALIZED_MANIFEST) {
        "cases.json manifest = $manifest, want $NORMALIZED_MANIFEST"
    }
    val caseValues = objectArray(root, "cases", "case file")
    require(caseValues.size >= NORMALIZED_MIN_CASES) {
        "cases.json has ${caseValues.size} cases, want >= $NORMALIZED_MIN_CASES (the differential input set)"
    }
    val seen = HashSet<String>()
    return caseValues.map { value ->
        val fields = value as? PvObject ?: error("case must be an Object")
        val id = objectString(fields, "id", "case")
        require(id.isNotEmpty()) { "case with an empty id" }
        require(seen.add(id)) { "duplicate case id $id" }
        parseNormalizedCase(fields, id)
    }
}

private fun parseNormalizedCase(fields: PvObject, id: String): NormalizedCase {
    val kind = objectString(fields, "kind", "case $id")
    val steps = (objectArrayOr(fields, "steps") ?: emptyList()).map { step ->
        val stepFields = step as? PvObject ?: error("case $id: step must be an Object")
        val filters = (objectArrayOr(stepFields, "filters") ?: emptyList()).map { filter ->
            val filterFields = filter as? PvObject ?: error("case $id: filter must be an Object")
            val argument = filterFields.get("argument")
            FilterDesc(
                operator = objectString(filterFields, "operator", "case $id filter"),
                argument = if (argument == null || argument is PvNull) {
                    ""
                } else {
                    // Render the argument as its canonical transport JSON text
                    // (the decoder re-validates it; a non-canonical argument is
                    // a harness bug on every side).
                    String(
                        consema.protocol.encodeJson(argument, consema.protocol.ProtocolLimits.default),
                        Charsets.UTF_8,
                    )
                },
            )
        }
        val operations = (objectArrayOr(stepFields, "operations") ?: emptyList()).map { op ->
            val opFields = op as? PvObject ?: error("case $id: operation must be an Object")
            EditOpDesc(
                operation = objectString(opFields, "operation", "case $id operation"),
                target = objectObjectOr(opFields, "target")?.let { target ->
                    TargetDesc(
                        kind = objectString(target, "kind", "case $id target"),
                        ordinal = objectIntOr(target, "ordinal") ?: 0,
                        foreign = objectBooleanOr(target, "foreign") ?: false,
                    )
                },
                value = objectObjectOr(opFields, "value")?.let { value ->
                    ValueDesc(
                        nullValue = objectBooleanOr(value, "null"),
                        boolean = objectBooleanOr(value, "boolean"),
                        integer = objectStringOr(value, "integer"),
                        decimal = objectStringOr(value, "decimal"),
                        string = objectStringOr(value, "string"),
                        binary64 = objectStringOr(value, "binary64"),
                    )
                },
                literalHex = objectStringOr(opFields, "literal_hex"),
                name = objectStringOr(opFields, "name"),
                policy = objectStringOr(opFields, "policy"),
                placement = objectObjectOr(opFields, "placement")?.let { placement ->
                    PlacementDesc(
                        at = objectStringOr(placement, "at"),
                        beforeOrdinal = objectIntOr(placement, "before_ordinal"),
                        afterOrdinal = objectIntOr(placement, "after_ordinal"),
                    )
                },
            )
        }
        StepDesc(
            op = objectString(stepFields, "op", "case $id step"),
            domain = objectStringOr(stepFields, "domain"),
            domainVersion = objectIntOr(stepFields, "domain_version") ?: 1,
            filters = filters,
            combine = objectStringOr(stepFields, "combine"),
            selection = objectStringOr(stepFields, "selection"),
            queryLimits = objectObjectOr(stepFields, "query_limits")?.let { limits ->
                QueryLimitsDesc(
                    maxResults = objectIntOr(limits, "max_results"),
                    maxSteps = objectIntOr(limits, "max_steps"),
                )
            },
            target = objectStringOr(stepFields, "target"),
            duplicatePolicy = objectStringOr(stepFields, "duplicate_policy"),
            input = objectStringOr(stepFields, "input"),
            valueJSON = objectStringOr(stepFields, "value_json"),
            entryMapping = objectObjectOr(stepFields, "entry_mapping")?.let { mapping ->
                EntryMappingDesc(
                    keyJSON = objectString(mapping, "key_json", "case $id entry_mapping"),
                    valueJSON = objectString(mapping, "value_json", "case $id entry_mapping"),
                )
            },
            targetProfile = objectStringOr(stepFields, "target_profile"),
            style = objectStringOr(stepFields, "style"),
            newline = objectStringOr(stepFields, "newline"),
            matLimits = objectObjectOr(stepFields, "limits")?.let { limits ->
                MaterializeLimitsDesc(
                    maxOutputBytes = objectIntOr(limits, "max_output_bytes"),
                    maxInputNodes = objectIntOr(limits, "max_input_nodes"),
                    maxDepth = objectIntOr(limits, "max_depth"),
                    maxProvenanceEntries = objectIntOr(limits, "max_provenance_entries"),
                )
            },
            operations = operations,
        )
    }
    return NormalizedCase(
        id = id,
        kind = kind,
        format = objectStringOr(fields, "format"),
        profile = objectStringOr(fields, "profile"),
        source = objectStringOr(fields, "source"),
        foreignSource = objectStringOr(fields, "foreign_source"),
        foreignSourceHex = objectStringOr(fields, "foreign_source_hex"),
        parseLimits = objectObjectOr(fields, "parse_limits")?.let { limits ->
            ParseLimitsDesc(
                maxSourceBytes = objectIntOr(limits, "max_source_bytes"),
                maxNestingDepth = objectIntOr(limits, "max_nesting_depth"),
                maxTokenCount = objectIntOr(limits, "max_token_count"),
                maxNodeCount = objectIntOr(limits, "max_node_count"),
                maxDiagnostics = objectIntOr(limits, "max_diagnostics"),
            )
        },
        steps = steps,
        input = objectObjectOr(fields, "input")?.let { input ->
            SourceInputDesc(
                rawHex = objectStringOr(input, "raw_hex"),
                source = objectStringOr(input, "source"),
            )
        },
        request = objectObjectOr(fields, "request")?.let { request ->
            EncodingRequestDesc(
                profileDefault = objectStringOr(request, "profile_default"),
                declaration = objectStringOr(request, "declaration"),
                callerOverride = objectStringOr(request, "caller_override"),
                bomPolicy = objectStringOr(request, "bom_policy"),
            )
        },
        positions = objectIntArrayOr(fields, "positions") ?: emptyList(),
        patch = objectObjectOr(fields, "patch")?.let { patch ->
            PatchDesc(
                replacements = (objectArrayOr(patch, "replacements") ?: emptyList()).map { item ->
                    val itemFields = item as? PvObject ?: error("case $id: patch replacement must be an Object")
                    PatchReplacementDesc(
                        oldStart = objectInt(itemFields, "old_start", "case $id patch"),
                        oldEnd = objectInt(itemFields, "old_end", "case $id patch"),
                        replacementHex = objectString(itemFields, "replacement_hex", "case $id patch"),
                    )
                },
                applyTo = objectStringOr(patch, "apply_to"),
            )
        },
    )
}

// ---------------------------------------------------------------------------
// Normalized fact emission
// ---------------------------------------------------------------------------

/** The outcome of one normalized differential run. */
data class NormalizedReport(
    val passed: Int,
    val failures: List<String>,
    val total: Int,
)

/** Computes the ordered normalized facts of one case. */
fun runNormalizedCase(case: NormalizedCase): List<String> = when (case.kind) {
    "document" -> runDocumentCase(case)
    "source" -> runSourceCase(case)
    else -> error("case ${case.id}: unknown kind ${case.kind}")
}

/** Compares the two fact line sets field by field (the Go compareFacts
 * mirror): a missing or extra key, or a differing value, is a failure. */
fun compareFacts(id: String, kotlinLines: List<String>, rustLines: List<String>): List<String> {
    val kotlinFacts = HashMap<String, String>()
    for (line in kotlinLines) {
        val (key, value) = splitFact(line) ?: return listOf("case $id: Kotlin side emitted malformed fact line $line")
        require(kotlinFacts.put(key, value) == null) { "case $id: Kotlin side emitted duplicate fact key $key" }
    }
    val rustFacts = HashMap<String, String>()
    for (line in rustLines) {
        val (key, value) = splitFact(line) ?: return listOf("case $id: Rust side emitted malformed fact line $line")
        require(rustFacts.put(key, value) == null) { "case $id: Rust side emitted duplicate fact key $key" }
    }
    val failures = ArrayList<String>()
    for ((key, kotlinValue) in kotlinFacts) {
        val rustValue = rustFacts[key]
        if (rustValue == null) {
            failures.add("case $id: field $key: Rust side has no such field (Kotlin value \"$kotlinValue\")")
            continue
        }
        if (kotlinValue != rustValue) {
            failures.add("case $id: field $key differs\n  Kotlin: \"$kotlinValue\"\n  Rust:   \"$rustValue\"")
        }
    }
    for ((key, rustValue) in rustFacts) {
        if (key !in kotlinFacts) {
            failures.add("case $id: field $key: Kotlin side has no such field (Rust value \"$rustValue\")")
        }
    }
    return failures
}

// ---------------------------------------------------------------------------
// Document face
// ---------------------------------------------------------------------------

private class DocState(val case: NormalizedCase) {
    var jsonDoc: consema.json.Document? = null
    var tomlDoc: consema.toml.TomlDocument? = null
    var yamlDoc: consema.yaml.Document? = null
    var iniDoc: consema.ini.IniDocument? = null
    var propertiesDoc: consema.properties.Document? = null

    var foreignJson: consema.json.Document? = null
    var foreignToml: consema.toml.TomlDocument? = null
    var foreignYaml: consema.yaml.Document? = null
    var foreignIni: consema.ini.IniDocument? = null
    var foreignProperties: consema.properties.Document? = null

    var format: String = case.format
    var profile: String = case.profile
    var parseLimits: ParseLimits = ParseLimits.default
    var iniLimits: consema.ini.IniParseLimits = consema.ini.IniParseLimits.default
    var propertiesLimits: consema.properties.PropertiesParseLimits = consema.properties.PropertiesParseLimits.default

    // parse facts
    var fatalCode: String = ""
    var formation: String = ""
    var diagnosticCodes: String = ""
    var rootKind: String = ""
    var native: String = ""

    // step run flags (each key set is emitted exactly once)
    var queryNativeRun = false
    var querySyntaxRun = false
    var projectRun = false
    var materializeRun = false
    var editRun = false

    // projection result
    var value: PortableValue? = null
    var projected = false

    fun documentParsed(): Boolean =
        jsonDoc != null || tomlDoc != null || yamlDoc != null ||
            iniDoc != null || propertiesDoc != null
}

private fun runDocumentCase(case: NormalizedCase): List<String> {
    validateDocumentCase(case)
    val state = DocState(case)
    applyParseLimits(state, case.parseLimits)
    val facts = Facts()
    if (!parseIntoState(state)) {
        facts.set("parse.formation", "Fatal")
        facts.set("parse.fatal_code", state.fatalCode)
        facts.set("parse.diagnostic_codes", "")
        facts.set("parse.root_kind", "")
        facts.set("parse.native", "")
        emitStepFacts(facts, state, StepDesc("", "", 1, emptyList(), "", "", null, "", "", "", "", null, "", "", "", null, emptyList()))
        return facts.lines()
    }
    facts.set("parse.formation", state.formation)
    facts.set("parse.fatal_code", "")
    facts.set("parse.diagnostic_codes", state.diagnosticCodes)
    facts.set("parse.root_kind", state.rootKind)
    facts.set("parse.native", state.native)

    for (step in case.steps) {
        when (step.op) {
            "parse" -> { /* already handled */ }
            "query-native", "query-syntax", "project", "materialize", "edit" ->
                emitStepFacts(facts, state, step)
            else -> error("case ${case.id}: unknown step op ${step.op}")
        }
    }
    // Every group's key set is emitted exactly once: groups whose step is
    // absent from the case report Blocked here, in the fixed order.
    emitStepFacts(facts, state, StepDesc("", "", 1, emptyList(), "", "", null, "", "", "", "", null, "", "", "", null, emptyList()))
    return facts.lines()
}

/** Validates one document case descriptor (the Go loadCaseFile checks). */
fun validateDocumentCase(case: NormalizedCase) {
    when (case.format) {
        "json" -> when (case.profile) {
            "json.strict@1", "jsonc.bounded@1", "json5.standard@1" -> {}
            else -> error("case ${case.id}: unknown profile ${case.profile}")
        }
        "toml" -> require(case.profile == "toml.1.0@1") { "case ${case.id}: unknown profile ${case.profile}" }
        "yaml" -> when (case.profile) {
            "yaml.1.2-core@1", "yaml.1.1-compat@1" -> {}
            else -> error("case ${case.id}: unknown profile ${case.profile}")
        }
        "ini" -> when (case.profile) {
            "ini.portable@1", "ini.windows@1", "ini.python-configparser@1" -> {}
            else -> error("case ${case.id}: unknown profile ${case.profile}")
        }
        "properties" -> when (case.profile) {
            "java-properties.reader@1", "java-properties.latin1@1" -> {}
            else -> error("case ${case.id}: unknown profile ${case.profile}")
        }
        else -> error("case ${case.id}: unknown format ${case.format}")
    }
    require(case.steps.isNotEmpty()) { "case ${case.id}: document case without steps" }
    for (step in case.steps) {
        when (step.op) {
            "parse", "query-native", "query-syntax", "project", "materialize", "edit" -> {}
            else -> error("case ${case.id}: unknown step op ${step.op}")
        }
    }
}

/** Applies the parse-limit descriptor overrides. */
private fun applyParseLimits(state: DocState, desc: ParseLimitsDesc?) {
    if (desc == null) {
        return
    }
    desc.maxSourceBytes?.let { state.parseLimits = state.parseLimits.copy(maxSourceBytes = it) }
    desc.maxNestingDepth?.let { state.parseLimits = state.parseLimits.copy(maxNestingDepth = it) }
    desc.maxTokenCount?.let { state.parseLimits = state.parseLimits.copy(maxTokenCount = it) }
    desc.maxNodeCount?.let { state.parseLimits = state.parseLimits.copy(maxNodeCount = it) }
    desc.maxDiagnostics?.let { state.parseLimits = state.parseLimits.copy(maxDiagnostics = it) }
    state.iniLimits = state.iniLimits.copy(common = state.parseLimits)
    state.propertiesLimits = state.propertiesLimits.copy(common = state.parseLimits)
}

/** Emits the fixed fact keys for every dependent step. A step that is not
 * declared in the case, or whose dependency failed, reports Blocked; each
 * key set is emitted exactly once. */
private fun emitStepFacts(facts: Facts, state: DocState, step: StepDesc) {
    when (step.op) {
        "query-native" -> emitNativeQuery(facts, state, step)
        "query-syntax" -> emitSyntaxQuery(facts, state, step)
        "project" -> emitProject(facts, state, step)
        "materialize" -> emitMaterialize(facts, state, step)
        "edit" -> emitEdit(facts, state, step)
        else -> {
            emitNativeQuery(facts, state, step)
            emitSyntaxQuery(facts, state, step)
            emitProject(facts, state, step)
            emitMaterialize(facts, state, step)
            emitEdit(facts, state, step)
        }
    }
}

/** Parses the case source and fills the parse facts. */
private fun parseIntoState(state: DocState): Boolean {
    val bytes = state.case.source.toByteArray(Charsets.UTF_8)
    return when (state.format) {
        "json" -> {
            val profile = when (state.profile) {
                "json.strict@1" -> JsonProfile.StrictV1
                "jsonc.bounded@1" -> JsonProfile.JsoncBoundedV1
                "json5.standard@1" -> JsonProfile.Json5StandardV1
                else -> error("unknown JSON profile ${state.profile}")
            }
            try {
                val doc = consema.json.parse(bytes, profile, state.parseLimits)
                state.jsonDoc = doc
                state.formation = doc.formationStatus().name
                state.diagnosticCodes = diagnosticCodes(doc.diagnostics())
                state.rootKind = jsonRootKind(doc)
                state.native = jsonNativeValue(doc.root(), 0)
                true
            } catch (e: consema.json.JsonFormationException) {
                state.fatalCode = e.code
                false
            }
        }
        "toml" -> {
            try {
                val doc = consema.toml.parse(bytes, consema.toml.TomlProfile.TOML_1_0_V1, state.parseLimits)
                state.tomlDoc = doc
                state.formation = doc.formationStatus().name
                state.diagnosticCodes = ""
                state.rootKind = doc.root().kind.name
                state.native = tomlNativeItem(doc.root(), 0)
                true
            } catch (e: consema.toml.TomlFormationException) {
                state.fatalCode = e.code
                false
            }
        }
        "yaml" -> {
            val profile = when (state.profile) {
                "yaml.1.2-core@1" -> consema.yaml.YamlProfile.Yaml12CoreV1
                "yaml.1.1-compat@1" -> consema.yaml.YamlProfile.Yaml11CompatV1
                else -> error("unknown YAML profile ${state.profile}")
            }
            try {
                val doc = consema.yaml.parse(bytes, profile, state.parseLimits)
                state.yamlDoc = doc
                state.formation = doc.formationStatus().name
                state.diagnosticCodes = ""
                state.rootKind = yamlRootKind(doc)
                state.native = yamlNativeSummary(doc)
                true
            } catch (e: consema.yaml.YamlFormationException) {
                state.fatalCode = e.code
                false
            }
        }
        "ini" -> {
            val profile = when (state.profile) {
                "ini.portable@1" -> consema.ini.IniProfile.PortableV1
                "ini.windows@1" -> consema.ini.IniProfile.WindowsV1
                "ini.python-configparser@1" -> consema.ini.IniProfile.PythonConfigParserV1
                else -> error("unknown INI profile ${state.profile}")
            }
            try {
                val doc = consema.ini.parse(bytes, profile, limits = state.iniLimits)
                state.iniDoc = doc
                state.formation = doc.formationStatus().name
                state.diagnosticCodes = diagnosticCodes(doc.diagnostics())
                state.rootKind = "Document"
                state.native = "sections=${doc.sections().size} entries=${doc.entries().size}"
                true
            } catch (e: consema.ini.IniFormationException) {
                state.fatalCode = e.code
                false
            }
        }
        "properties" -> {
            try {
                val doc = consema.properties.parseReader(bytes, SourceEncoding.Utf8, state.propertiesLimits)
                state.propertiesDoc = doc
                state.formation = doc.formationStatus().name
                state.diagnosticCodes = diagnosticCodes(doc.diagnostics())
                state.rootKind = "Document"
                state.native = "properties=${doc.properties().size} comments=${doc.comments().size}"
                true
            } catch (e: consema.properties.PropertiesFormationException) {
                state.fatalCode = e.code
                false
            }
        }
        else -> false
    }
}

/** Renders the document-0 root node kind fact of a YAML stream. */
private fun yamlRootKind(doc: consema.yaml.Document): String {
    val yamlDoc = doc.document(0) ?: return "EmptyStream"
    return yamlDoc.root().kind().name
}

/** Renders the stream-level native facts: the document count and the alias
 * occurrence count (the graph/alias face of the language-neutral surface). */
private fun yamlNativeSummary(doc: consema.yaml.Document): String =
    "docs=${doc.documentCount()} aliases=${doc.aliasCount()}"

/** Renders the ordered diagnostic codes. */
private fun diagnosticCodes(diagnostics: List<consema.protocol.Diagnostic>): String =
    join(diagnostics.map { it.code })

/** Renders the root native kind fact of a JSON document. */
private fun jsonRootKind(doc: consema.json.Document): String {
    val kind = doc.root().kind()
    return when (kind) {
        is SemanticAvailability.Available -> kind.value.name
        is SemanticAvailability.Unavailable -> "Unavailable:" + kind.reason.name
    }
}

/** Renders one JSON native value in the canonical summary vocabulary
 * (mirrored by the Rust example). */
private fun jsonNativeValue(value: consema.json.JsonValue, depth: Int): String {
    if (depth > 64) {
        return "..."
    }
    val kind = value.kind()
    if (kind is SemanticAvailability.Unavailable) {
        return "Unavailable:" + kind.reason.name
    }
    return when (val available = (kind as SemanticAvailability.Available).value) {
        JsonValueKind.Null -> "null"
        JsonValueKind.Boolean ->
            (value.asBoolean() as? SemanticAvailability.Available)?.value?.toString() ?: "?"
        JsonValueKind.Integer ->
            (value.asInteger() as? SemanticAvailability.Available)?.value?.toString() ?: "?"
        JsonValueKind.Decimal -> {
            val decimal = (value.asDecimal() as? SemanticAvailability.Available)?.value
            if (decimal == null) "?" else decimal.coefficient.toString() + "e" + decimal.exponent.toString()
        }
        JsonValueKind.BinaryFloat64 -> {
            val bits = (value.asBinaryFloat64() as? SemanticAvailability.Available)?.value
            if (bits == null) "?" else "0x%016x".format(bits)
        }
        JsonValueKind.String -> {
            val text = (value.asString() as? SemanticAvailability.Available)?.value
            if (text == null) "?" else "\"" + escape(text) + "\""
        }
        JsonValueKind.Array -> {
            val elements = value.arrayElements()
            if (elements is SemanticAvailability.Unavailable) {
                return "Unavailable:" + elements.reason.name
            }
            val items = (elements as SemanticAvailability.Available).value ?: return "?"
            "[" + items.joinToString(",") { jsonNativeValue(it.value(), depth + 1) } + "]"
        }
        JsonValueKind.Object -> {
            val members = value.objectMembers()
            if (members is SemanticAvailability.Unavailable) {
                return "Unavailable:" + members.reason.name
            }
            val items = (members as SemanticAvailability.Available).value ?: return "?"
            "{" + items.joinToString(",") { member ->
                val renderedName = when (val name = member.name()) {
                    is SemanticAvailability.Available -> escape(name.value)
                    is SemanticAvailability.Unavailable -> "?"
                }
                "\"" + renderedName + "\":" + jsonNativeValue(member.value(), depth + 1)
            } + "}"
        }
    }
}

/** Renders one TOML native item in the canonical summary vocabulary. */
private fun tomlNativeItem(item: consema.toml.TomlItem, depth: Int): String {
    if (depth > 64) {
        return "..."
    }
    return when (item.kind) {
        consema.toml.TomlItemKind.String -> "\"" + escape(item.asString() ?: return "?") + "\""
        consema.toml.TomlItemKind.Integer -> (item.asInteger() ?: return "?").toString()
        consema.toml.TomlItemKind.Float -> "0x%016x".format(item.asFloat() ?: return "?")
        consema.toml.TomlItemKind.Boolean -> (item.asBoolean() ?: return "?").toString()
        consema.toml.TomlItemKind.OffsetDateTime, consema.toml.TomlItemKind.LocalDateTime,
        consema.toml.TomlItemKind.LocalDate, consema.toml.TomlItemKind.LocalTime ->
            tomlDateTimeSummary(item)
        consema.toml.TomlItemKind.Array, consema.toml.TomlItemKind.ArrayOfTables ->
            (item.arrayElements() ?: return "?")
                .joinToString(",", "[", "]") { tomlNativeItem(it.item(), depth + 1) }
        consema.toml.TomlItemKind.InlineTable, consema.toml.TomlItemKind.RootTable,
        consema.toml.TomlItemKind.StandardTable, consema.toml.TomlItemKind.ImplicitTable,
        consema.toml.TomlItemKind.DottedTable ->
            (item.tableEntries() ?: return "?")
                .joinToString(",", "{", "}") { "\"" + escape(it.name()) + "\":" + tomlNativeItem(it.item(), depth + 1) }
    }
}

/** Renders one TOML date/time datum canonically. */
private fun tomlDateTimeSummary(item: consema.toml.TomlItem): String {
    val dateTime = item.asDateTime() ?: return "?"
    val parts = ArrayList<String>()
    dateTime.date?.let { date ->
        parts.add("date=%04d-%02d-%02d".format(date.year, date.month, date.day))
    }
    dateTime.time?.let { time ->
        var text = "time=%02d:%02d:%02d".format(time.hour, time.minute, time.second)
        if (time.nanosecond != 0L) {
            text += "." + "%09d".format(time.nanosecond)
        }
        parts.add(text)
    }
    dateTime.offset?.let { offset ->
        when (offset) {
            is consema.toml.TomlOffset.Z -> parts.add("offset=Z")
            is consema.toml.TomlOffset.CustomMinutes -> {
                var minutes = offset.minutes
                val sign = if (minutes < 0) "-" else "+"
                if (minutes < 0) {
                    minutes = -minutes
                }
                parts.add("offset=$sign%02d:%02d".format(minutes / 60, minutes % 60))
            }
        }
    }
    return "datetime(" + parts.joinToString(",") + ")"
}

// ---------------------------------------------------------------------------
// Query steps
// ---------------------------------------------------------------------------

private fun emitNativeQuery(facts: Facts, state: DocState, step: StepDesc) {
    if (state.queryNativeRun) {
        return
    }
    fun blocked() {
        facts.set("query.native.status", "Blocked")
        facts.set("query.native.failure", "")
        facts.set("query.native.count", "")
        facts.set("query.native.matches", "")
    }
    if (step.op != "query-native" || !state.documentParsed()) {
        state.queryNativeRun = true
        blocked()
        return
    }
    state.queryNativeRun = true
    val domain = QueryDomain(step.domain, step.domainVersion)
    val limits = applyQueryLimits(step.queryLimits)
    val executable = try {
        buildQueryDefinition(step, domain, state.case.id)
    } catch (e: QueryFailureException) {
        facts.set("query.native.status", "Failed")
        facts.set("query.native.failure", e.kind.code)
        facts.set("query.native.count", "")
        facts.set("query.native.matches", "")
        return
    }
    if (executable == null) {
        facts.set("query.native.status", "Failed")
        facts.set("query.native.failure", "core.query.invalid-argument@1")
        facts.set("query.native.count", "")
        facts.set("query.native.matches", "")
        return
    }
    try {
        val matches = executeNativeQuery(executable, state, limits)
        val items = matches.map(::nativeMatch)
        facts.set("query.native.status", "Completed")
        facts.set("query.native.failure", "")
        facts.set("query.native.count", items.size.toString())
        facts.set("query.native.matches", join(items))
    } catch (e: QueryFailureException) {
        facts.set("query.native.status", "Failed")
        facts.set("query.native.failure", e.kind.code)
        facts.set("query.native.count", "")
        facts.set("query.native.matches", "")
    }
}

/** Executes one native query on the current document; returns the ordered
 * match identity facts. */
private fun executeNativeQuery(
    executable: ExecutableQuery,
    state: DocState,
    limits: Pair<Int, Int>,
): List<Any> {
    state.jsonDoc?.let { doc ->
        return consema.json.executeJsonQuery(
            executable,
            doc,
            consema.json.QueryLimits(limits.first, limits.second),
        )
    }
    state.tomlDoc?.let { doc ->
        return consema.toml.executeTomlQuery(
            executable,
            doc,
            consema.toml.TomlQueryLimits(limits.first, limits.second),
            consema.toml.TomlCancellationToken(),
        ).matches()
    }
    state.yamlDoc?.let { doc ->
        return consema.yaml.executeYamlQuery(
            executable,
            doc,
            consema.yaml.QueryLimits(limits.first, limits.second),
        )
    }
    state.iniDoc?.let { doc ->
        return consema.ini.executeIniQuery(
            executable,
            doc,
            consema.ini.QueryLimits(limits.first, limits.second),
        )
    }
    state.propertiesDoc?.let { doc ->
        return consema.properties.executePropertiesQuery(
            executable,
            doc,
            consema.properties.QueryLimits(limits.first, limits.second),
        )
    }
    error("no document parsed")
}

/** Renders one native match identity fact in the canonical vocabulary. */
private fun nativeMatch(match: Any): String = when (match) {
    is consema.json.JsonMatch.Value ->
        "V:" + (match.kind?.name ?: "?")
    is consema.json.JsonMatch.ObjectMember ->
        "M:" + match.ordinal + ":" + escape(match.name ?: "?")
    is consema.json.JsonMatch.ArrayElement ->
        "E:" + match.ordinal
    is consema.toml.TomlMatch.Item ->
        "I:" + match.kind.name
    is consema.toml.TomlMatch.Entry ->
        "M:" + match.ordinal + ":" + escape(match.name)
    is consema.toml.TomlMatch.ArrayElement ->
        "E:" + match.ordinal
    is consema.yaml.YamlMatch.Stream -> "Stream:0"
    is consema.yaml.YamlMatch.Document -> "Document:" + match.ordinal
    is consema.yaml.YamlMatch.Node -> "Node:" + match.kind.name
    is consema.yaml.YamlMatch.MappingEntry -> "MappingEntry:" + match.ordinal
    is consema.yaml.YamlMatch.SequenceElement -> "SequenceElement:" + match.ordinal
    is consema.yaml.YamlMatch.AnchorDefinition -> "AnchorDefinition:" + escape(match.name)
    is consema.yaml.YamlMatch.AliasOccurrence -> "AliasOccurrence:" + match.ordinal
    is consema.ini.IniMatch.Document -> "Document:0"
    is consema.ini.IniMatch.Section -> "Section:" + match.ordinal
    is consema.ini.IniMatch.Entry -> "Entry:" + match.ordinal
    is consema.ini.IniMatch.PhysicalLine -> "PhysicalLine:" + match.ordinal
    is consema.ini.IniMatch.LogicalLine -> "LogicalLine:" + match.ordinal
    is consema.properties.PropertiesMatch.Document -> "Document:0"
    is consema.properties.PropertiesMatch.Property -> "Property:" + match.ordinal
    is consema.properties.PropertiesMatch.NaturalLine -> "NaturalLine:" + match.ordinal
    is consema.properties.PropertiesMatch.LogicalLine -> "LogicalLine:" + match.ordinal
    is consema.properties.PropertiesMatch.Escape -> "Escape:" + match.ordinal
    else -> "?"
}

private fun emitSyntaxQuery(facts: Facts, state: DocState, step: StepDesc) {
    if (state.querySyntaxRun) {
        return
    }
    fun blocked() {
        facts.set("query.syntax.status", "Blocked")
        facts.set("query.syntax.failure", "")
        facts.set("query.syntax.count", "")
        facts.set("query.syntax.matches", "")
    }
    if (step.op != "query-syntax" || !state.documentParsed()) {
        state.querySyntaxRun = true
        blocked()
        return
    }
    state.querySyntaxRun = true
    val domain = QueryDomain(step.domain, step.domainVersion)
    val limits = applyQueryLimits(step.queryLimits)
    val executable = try {
        buildQueryDefinition(step, domain, state.case.id)
    } catch (e: QueryFailureException) {
        facts.set("query.syntax.status", "Failed")
        facts.set("query.syntax.failure", e.kind.code)
        facts.set("query.syntax.count", "")
        facts.set("query.syntax.matches", "")
        return
    }
    if (executable == null) {
        facts.set("query.syntax.status", "Failed")
        facts.set("query.syntax.failure", "core.query.invalid-argument@1")
        facts.set("query.syntax.count", "")
        facts.set("query.syntax.matches", "")
        return
    }
    try {
        val matches = executeSyntaxQuery(executable, state, limits)
        val items = matches.map(::syntaxMatch)
        facts.set("query.syntax.status", "Completed")
        facts.set("query.syntax.failure", "")
        facts.set("query.syntax.count", items.size.toString())
        facts.set("query.syntax.matches", join(items))
    } catch (e: QueryFailureException) {
        facts.set("query.syntax.status", "Failed")
        facts.set("query.syntax.failure", e.kind.code)
        facts.set("query.syntax.count", "")
        facts.set("query.syntax.matches", "")
    }
}

/** Executes one lossless syntax query on the current document. */
private fun executeSyntaxQuery(
    executable: ExecutableQuery,
    state: DocState,
    limits: Pair<Int, Int>,
): List<Any> {
    state.jsonDoc?.let { doc ->
        return consema.json.executeJsonSyntaxQuery(
            executable,
            doc,
            consema.json.QueryLimits(limits.first, limits.second),
        )
    }
    state.tomlDoc?.let { doc ->
        return consema.toml.executeTomlSyntaxQuery(
            executable,
            doc,
            consema.toml.TomlQueryLimits(limits.first, limits.second),
            consema.toml.TomlCancellationToken(),
        ).matches()
    }
    state.yamlDoc?.let { doc ->
        return consema.yaml.executeYamlSyntaxQuery(
            executable,
            doc,
            consema.yaml.QueryLimits(limits.first, limits.second),
        )
    }
    state.iniDoc?.let { doc ->
        return consema.ini.executeIniSyntaxQuery(
            executable,
            doc,
            consema.ini.QueryLimits(limits.first, limits.second),
        )
    }
    state.propertiesDoc?.let { doc ->
        return consema.properties.executePropertiesSyntaxQuery(
            executable,
            doc,
            consema.properties.QueryLimits(limits.first, limits.second),
        )
    }
    error("no document parsed")
}

/** Renders one syntax match identity fact: KIND@ordinal. */
private fun syntaxMatch(match: Any): String = when (match) {
    is consema.json.JsonSyntaxMatch -> match.kind.asStr() + "@" + match.ordinal
    is consema.toml.TomlSyntaxMatch -> match.kind.name + "@" + match.ordinal
    is consema.yaml.YamlSyntaxMatch -> match.kind.asStr() + "@" + match.ordinal
    is consema.ini.IniSyntaxMatch -> match.kind.asStr() + "@" + match.ordinal
    is consema.properties.PropertiesSyntaxMatch -> match.kind.asStr() + "@" + match.ordinal
    else -> "?"
}

/** Applies the query-limit descriptor overrides (maxSteps, maxResults). */
private fun applyQueryLimits(desc: QueryLimitsDesc?): Pair<Int, Int> {
    val defaults = Pair(100_000, 100_000)
    if (desc == null) {
        return defaults
    }
    return Pair(
        desc.maxSteps ?: defaults.first,
        desc.maxResults ?: defaults.second,
    )
}

/** Builds the executable from the declarative filters, mirroring the Go
 * harness's buildQueryDefinition and the conformance runner pipeline
 * (kotlin/.../conformance/SyntaxQueryV1.kt). Returns null on a
 * missing-argument invalid-argument failure. */
private fun buildQueryDefinition(step: StepDesc, domain: QueryDomain, caseId: String): ExecutableQuery? {
    val format = when {
        step.domain.startsWith("toml.") -> "toml"
        step.domain.startsWith("yaml.") -> "yaml"
        step.domain.startsWith("ini.") -> "ini"
        step.domain.startsWith("java-properties.") -> "properties"
        else -> "json"
    }
    val calls = ArrayList<OperatorCall>(step.filters.size)
    for (filter in step.filters) {
        val argument = filter.argument
        when (filter.operator) {
            "kind-is" -> {
                val value = argumentValue(argument) ?: return null
                calls.add(OperatorCall("$format.syntax-kind-is", 1).withArgument("kind", value))
            }
            "text-equals" -> {
                val value = argumentValue(argument) ?: return null
                calls.add(OperatorCall("$format.syntax-text-equals", 1).withArgument("text", value))
            }
            "take" -> {
                val value = argumentValue(argument) ?: return null
                calls.add(OperatorCall("core.take", 1).withArgument("count", value))
            }
            "json.member-name-equals", "toml.entry-name-equals" -> {
                val value = argumentValue(argument) ?: return null
                calls.add(OperatorCall(filter.operator, 1).withArgument("name", value))
            }
            "yaml.where-node-kind" -> {
                val value = argumentValue(argument) ?: return null
                calls.add(OperatorCall("yaml.where-node-kind", 1).withArgument("kind", value))
            }
            "yaml.where-tag" -> {
                val value = argumentValue(argument) ?: return null
                calls.add(OperatorCall("yaml.where-tag", 1).withArgument("tag", value))
            }
            "yaml.scalar-canonical-equals" -> {
                val value = argumentValue(argument) ?: return null
                calls.add(OperatorCall("yaml.scalar-canonical-equals", 1).withArgument("canonical", value))
            }
            "ini.entry-value-state-is" -> {
                val value = argumentValue(argument) ?: return null
                calls.add(OperatorCall("ini.entry-value-state-is", 1).withArgument("state", value))
            }
            "properties.property-value-state-is" -> {
                val value = argumentValue(argument) ?: return null
                calls.add(OperatorCall("properties.property-value-state-is", 1).withArgument("state", value))
            }
            else -> calls.add(OperatorCall(filter.operator, 1))
        }
    }
    val expression = when (step.combine) {
        "Single", "" -> {
            var current = QueryExpression(ExpressionKind.Input)
            for (call in calls) {
                current = current.then(call)
            }
            current
        }
        "StructureOrderMerge" -> QueryExpression(
            ExpressionKind.StructureOrderMerge,
            branches = calls.map { QueryExpression(ExpressionKind.Input).then(it) },
        )
        "Concat" -> QueryExpression(
            ExpressionKind.Concat,
            branches = calls.map { QueryExpression(ExpressionKind.Input).then(it) },
        )
        else -> return null
    }
    val selection = when (step.selection) {
        "All", "" -> QuerySelection.All
        "First" -> QuerySelection.First
        "Last" -> QuerySelection.Last
        "ZeroOrOne" -> QuerySelection.ZeroOrOne
        "RequireOne" -> QuerySelection.RequireOne
        else -> return null
    }
    val validated = try {
        QueryDefinition(domain)
            .withExpression(expression)
            .withSelection(selection)
            .validate()
    } catch (e: QueryFailureException) {
        throw e
    }
    val capabilities = CapabilitySet()
    capabilities.insert(CapabilityId("core.query.ordered-results", 1))
    return ExecutableQuery.bind(validated, capabilities)
}

/** Decodes one filter argument text. The argument is rendered as canonical
 * transport JSON by the case loader; a missing argument is an
 * invalid-argument failure (null), a present-but-wrong-typed argument is
 * bound verbatim so the definition validation reports the wrong argument
 * kind (core.query.wrong-argument-type@1 on both sides). */
private fun argumentValue(argument: String): PortableValue? {
    if (argument.isEmpty()) {
        return null
    }
    return decodeJson(argument.toByteArray(Charsets.UTF_8), consema.protocol.ProtocolLimits.default)
}

// ---------------------------------------------------------------------------
// Projection / materialization / edit steps
// ---------------------------------------------------------------------------

private fun emitProject(facts: Facts, state: DocState, step: StepDesc) {
    if (state.projectRun) {
        return
    }
    fun blocked() {
        facts.set("project.status", "Blocked")
        facts.set("project.failure", "")
        facts.set("project.fidelity", "")
        facts.set("project.value_kind", "")
        facts.set("project.report", "")
        facts.set("project.provenance_entries", "")
    }
    if (step.op != "project" || !state.documentParsed()) {
        state.projectRun = true
        blocked()
        return
    }
    state.projectRun = true
    state.jsonDoc?.let { doc ->
        val request = buildJsonProjectionRequest(step)
        val result = doc.project(request)
        when (result) {
            is consema.json.ProjectionResult.Complete -> {
                state.value = result.projection.value
                state.projected = true
                facts.set("project.status", "Completed")
                facts.set("project.failure", "")
                facts.set("project.fidelity", result.projection.fidelity.name)
                facts.set("project.value_kind", neutralKindName(result.projection.value.kind))
                facts.set("project.report", jsonEventSummary(result.projection.report))
                facts.set("project.provenance_entries", result.projection.provenance.entries().size.toString())
            }
            is consema.json.ProjectionResult.Failed -> {
                facts.set("project.status", "Failed")
                facts.set("project.failure", result.attempt.diagnostics.firstOrNull()?.code ?: "")
                facts.set("project.fidelity", "")
                facts.set("project.value_kind", "")
                facts.set("project.report", jsonEventSummary(result.attempt.report))
                facts.set("project.provenance_entries", "")
            }
        }
        return
    }
    state.tomlDoc?.let { doc ->
        val request = consema.toml.ProjectionRequest.new(consema.toml.ProjectionTarget.BEST_EXACT_CORE_V1)
        val result = doc.project(request)
        when (result) {
            is consema.toml.ProjectionResult.Complete -> {
                state.value = result.projection.value
                state.projected = true
                facts.set("project.status", "Completed")
                facts.set("project.failure", "")
                facts.set("project.fidelity", result.projection.fidelity.name)
                facts.set("project.value_kind", neutralKindName(result.projection.value.kind))
                facts.set("project.report", tomlReportSummary(result.projection.report))
                facts.set("project.provenance_entries", result.projection.provenance.entries().size.toString())
            }
            is consema.toml.ProjectionResult.Failed -> {
                facts.set("project.status", "Failed")
                facts.set("project.failure", result.attempt.diagnostics.firstOrNull()?.code ?: "")
                facts.set("project.fidelity", "")
                facts.set("project.value_kind", "")
                facts.set("project.report", tomlReportSummary(result.attempt.report))
                facts.set("project.provenance_entries", "")
            }
        }
        return
    }
    state.yamlDoc?.let { doc ->
        val result = doc.projectValue(consema.yaml.ValueProjectionRequest.bestExactV1())
        when (result) {
            is consema.yaml.ValueProjectionResult.Complete -> {
                state.value = result.projection.value
                state.projected = true
                facts.set("project.status", "Completed")
                facts.set("project.failure", "")
                facts.set("project.fidelity", result.projection.fidelity.name)
                facts.set("project.value_kind", neutralKindName(result.projection.value.kind))
                facts.set("project.report", yamlEventSummary(result.projection.report))
                facts.set("project.provenance_entries", result.projection.provenance.entries().size.toString())
            }
            is consema.yaml.ValueProjectionResult.Failed -> {
                facts.set("project.status", "Failed")
                facts.set("project.failure", consema.yaml.valueProjectionCode(result.failure))
                facts.set("project.fidelity", "")
                facts.set("project.value_kind", "")
                facts.set("project.report", "")
                facts.set("project.provenance_entries", "")
            }
        }
        return
    }
    state.iniDoc?.let { doc ->
        val result = doc.project(consema.ini.ProjectionRequest.bestExactEntryMapping())
        when (result) {
            is consema.ini.ProjectionResult.Complete -> {
                state.value = result.projection.value
                state.projected = true
                facts.set("project.status", "Completed")
                facts.set("project.failure", "")
                facts.set("project.fidelity", result.projection.fidelity.name)
                facts.set("project.value_kind", neutralKindName(result.projection.value.kind))
                facts.set("project.report", iniEventSummary(result.projection.report))
                facts.set("project.provenance_entries", result.projection.provenance.entries().size.toString())
            }
            is consema.ini.ProjectionResult.Failed -> {
                facts.set("project.status", "Failed")
                facts.set("project.failure", result.attempt.diagnostics.firstOrNull()?.code ?: "")
                facts.set("project.fidelity", "")
                facts.set("project.value_kind", "")
                facts.set("project.report", iniEventSummary(result.attempt.report))
                facts.set("project.provenance_entries", "")
            }
        }
        return
    }
    state.propertiesDoc?.let { doc ->
        val result = doc.project(consema.properties.ProjectionRequest.bestExactEntryMapping())
        when (result) {
            is consema.properties.ProjectionResult.Complete -> {
                state.value = result.projection.value
                state.projected = true
                facts.set("project.status", "Completed")
                facts.set("project.failure", "")
                facts.set("project.fidelity", result.projection.fidelity.name)
                facts.set("project.value_kind", neutralKindName(result.projection.value.kind))
                facts.set("project.report", propertiesEventSummary(result.projection.report))
                facts.set("project.provenance_entries", result.projection.provenance.entries().size.toString())
            }
            is consema.properties.ProjectionResult.Failed -> {
                facts.set("project.status", "Failed")
                facts.set("project.failure", result.attempt.diagnostics.firstOrNull()?.code ?: "")
                facts.set("project.fidelity", "")
                facts.set("project.value_kind", "")
                facts.set("project.report", propertiesEventSummary(result.attempt.report))
                facts.set("project.provenance_entries", "")
            }
        }
    }
}

/** Renders one ordered EventKind:count summary (the JSON/INI/Properties
 * projection reports). */
private fun orderedEventSummary(kinds: List<String>): String {
    val order = ArrayList<String>()
    val counts = HashMap<String, Int>()
    for (kind in kinds) {
        if (!counts.containsKey(kind)) {
            order.add(kind)
        }
        counts[kind] = (counts[kind] ?: 0) + 1
    }
    return join(order.map { "$it:${counts[it]}" })
}

/** Renders the JSON projection report as ordered EventKind:count pairs. */
private fun jsonEventSummary(report: consema.json.ProjectionReport): String =
    orderedEventSummary(report.events().map { it.kind.name })

/** Renders the INI projection report as ordered EventKind:count pairs. */
private fun iniEventSummary(report: consema.ini.ProjectionReport): String =
    orderedEventSummary(report.events().map { it.kind.name })

/** Renders the Properties projection report as ordered event-code:count
 * pairs (the report events carry their registered code,
 * java-properties.projection.duplicate-collapsed@1). */
private fun propertiesEventSummary(report: consema.properties.ProjectionReport): String =
    orderedEventSummary(report.events().map { it.code })

/** Renders the YAML projection report as ordered EventKind:count pairs. */
private fun yamlEventSummary(report: consema.yaml.ProjectionReport): String =
    orderedEventSummary(report.events().map { it.kind.name })

/** Renders the TOML projection report as ordered diagnostic codes. */
private fun tomlReportSummary(report: consema.toml.ProjectionReport): String =
    join(report.events().map { it.code })

/** Maps one core kind to the language-neutral kind vocabulary (the array
 * kind is "Sequence" on the PVCE surface). */
private fun neutralKindName(kind: Kind): String =
    if (kind == Kind.Sequence) "Sequence" else kind.name

/** Builds the JSON projection request from the descriptor. */
private fun buildJsonProjectionRequest(step: StepDesc): consema.json.ProjectionRequest {
    val target = when (step.target) {
        "ProjectAsObject" -> consema.json.ProjectionTarget.ProjectAsObjectV1
        "ProjectAsEntryMapping" -> consema.json.ProjectionTarget.ProjectAsEntryMappingV1
        "Json5BestExactCore" -> consema.json.ProjectionTarget.Json5BestExactCoreV1
        else -> consema.json.ProjectionTarget.BestExactCoreV1
    }
    val builder = consema.json.ProjectionRequest.builder(target)
    when (step.duplicatePolicy) {
        "FirstWins" -> builder.globalDuplicatePolicy(consema.json.DuplicateKeyPolicy.FirstWins)
        "LastWins" -> builder.globalDuplicatePolicy(consema.json.DuplicateKeyPolicy.LastWins)
    }
    return builder.build()
}

private fun emitMaterialize(facts: Facts, state: DocState, step: StepDesc) {
    if (state.materializeRun) {
        return
    }
    fun blocked() {
        facts.set("materialize.status", "Blocked")
        facts.set("materialize.failure", "")
        facts.set("materialize.output", "")
        facts.set("materialize.fidelity", "")
    }
    if (step.op != "materialize" || !state.documentParsed()) {
        state.materializeRun = true
        blocked()
        return
    }
    state.materializeRun = true
    val value: PortableValue = when (step.input) {
        "", "project" -> {
            if (!state.projected) {
                blocked()
                return
            }
            state.value ?: run {
                blocked()
                return
            }
        }
        "value" -> {
            val decoded = decodeMaterializeValue(step)
            if (decoded == null) {
                facts.set("materialize.status", "Failed")
                facts.set("materialize.failure", "core.protocol.invalid-value@1")
                facts.set("materialize.output", "")
                facts.set("materialize.fidelity", "")
                return
            }
            decoded
        }
        else -> {
            facts.set("materialize.status", "Failed")
            facts.set("materialize.failure", "core.protocol.invalid-value@1")
            facts.set("materialize.output", "")
            facts.set("materialize.fidelity", "")
            return
        }
    }
    val request = buildMaterializationRequest(step)
    if (request == null) {
        facts.set("materialize.status", "Failed")
        facts.set("materialize.failure", "core.materialization.invalid-request@1")
        facts.set("materialize.output", "")
        facts.set("materialize.fidelity", "")
        return
    }
    fun complete(output: String, fidelity: String) {
        facts.set("materialize.status", "Completed")
        facts.set("materialize.failure", "")
        facts.set("materialize.output", escape(output))
        facts.set("materialize.fidelity", fidelity)
    }
    fun failed(code: String) {
        facts.set("materialize.status", "Failed")
        facts.set("materialize.failure", code)
        facts.set("materialize.output", "")
        facts.set("materialize.fidelity", "")
    }
    when (val result = familyMaterialize(state, value, request)) {
        is FamilyMaterialization.Success -> complete(result.output, result.fidelity)
        is FamilyMaterialization.Failure -> failed(result.code)
    }
}

private sealed class FamilyMaterialization {
    data class Success(val output: String, val fidelity: String) : FamilyMaterialization()
    data class Failure(val code: String) : FamilyMaterialization()
}

/** Materializes one value under the current document family. */
private fun familyMaterialize(
    state: DocState,
    value: PortableValue,
    request: MaterializationRequest,
): FamilyMaterialization {
    state.jsonDoc?.let {
        return when (val result = consema.json.materialize(value, request)) {
            is consema.document.MaterializationResult.Complete -> FamilyMaterialization.Success(
                String(result.materialization.document.render(), Charsets.UTF_8),
                result.materialization.fidelity.name,
            )
            is consema.document.MaterializationResult.Failed -> FamilyMaterialization.Failure(
                result.attempt.failure.kind.code,
            )
        }
    }
    state.tomlDoc?.let {
        return when (val result = consema.toml.materialize(value, request)) {
            is consema.document.MaterializationResult.Complete -> FamilyMaterialization.Success(
                String(result.materialization.document.render(), Charsets.UTF_8),
                result.materialization.fidelity.name,
            )
            is consema.document.MaterializationResult.Failed -> FamilyMaterialization.Failure(
                result.attempt.failure.kind.code,
            )
        }
    }
    state.yamlDoc?.let {
        return when (val result = consema.yaml.materializeValue(value, request)) {
            is consema.document.MaterializationResult.Complete -> FamilyMaterialization.Success(
                String(result.materialization.document.render(), Charsets.UTF_8),
                result.materialization.fidelity.name,
            )
            is consema.document.MaterializationResult.Failed -> FamilyMaterialization.Failure(
                result.attempt.failure.kind.code,
            )
        }
    }
    state.iniDoc?.let {
        return when (val result = consema.ini.materialize(value, request)) {
            is consema.document.MaterializationResult.Complete -> FamilyMaterialization.Success(
                String(result.materialization.document.render(), Charsets.UTF_8),
                result.materialization.fidelity.name,
            )
            is consema.document.MaterializationResult.Failed -> FamilyMaterialization.Failure(
                result.attempt.failure.kind.code,
            )
        }
    }
    state.propertiesDoc?.let {
        return when (val result = consema.properties.materialize(value, request)) {
            is consema.document.MaterializationResult.Complete -> FamilyMaterialization.Success(
                String(result.materialization.document.render(), Charsets.UTF_8),
                result.materialization.fidelity.name,
            )
            is consema.document.MaterializationResult.Failed -> FamilyMaterialization.Failure(
                result.attempt.failure.kind.code,
            )
        }
    }
    return FamilyMaterialization.Failure("core.materialization.invalid-request@1")
}

/** Decodes the materialize input descriptor through the canonical transport
 * JSON decoder (RFC 0015 §3.2). */
private fun decodeMaterializeValue(step: StepDesc): PortableValue? {
    step.entryMapping?.let { mapping ->
        val key = try {
            decodeJson(mapping.keyJSON.toByteArray(Charsets.UTF_8), consema.protocol.ProtocolLimits.default)
        } catch (e: Exception) {
            return null
        }
        val value = try {
            decodeJson(mapping.valueJSON.toByteArray(Charsets.UTF_8), consema.protocol.ProtocolLimits.default)
        } catch (e: Exception) {
            return null
        }
        return consema.core.EntryMappingBuilder().push(key, value).build()
    }
    return try {
        decodeJson(step.valueJSON.toByteArray(Charsets.UTF_8), consema.protocol.ProtocolLimits.default)
    } catch (e: Exception) {
        null
    }
}

/** Builds the materialization request from the descriptor. */
private fun buildMaterializationRequest(step: StepDesc): MaterializationRequest? {
    if (step.targetProfile.isEmpty() || step.style.isEmpty()) {
        return null
    }
    val targetParts = step.targetProfile.split("@", limit = 2)
    val styleParts = step.style.split("@", limit = 2)
    var request = MaterializationRequest.new(
        ProfileId(targetParts[0], 1),
        MaterializationStyleId(styleParts[0], 1),
    )
    request = when (step.newline) {
        "None" -> request.withNewline(NewlinePolicy.None)
        "CrLf" -> request.withNewline(NewlinePolicy.CrLf)
        else -> request.withNewline(NewlinePolicy.Lf)
    }
    step.matLimits?.let { limits ->
        var materializationLimits = MaterializationLimits.default
        limits.maxOutputBytes?.let { materializationLimits = materializationLimits.copy(maxOutputBytes = it) }
        limits.maxInputNodes?.let { materializationLimits = materializationLimits.copy(maxInputNodes = it) }
        limits.maxDepth?.let { materializationLimits = materializationLimits.copy(maxDepth = it) }
        limits.maxProvenanceEntries?.let {
            materializationLimits = materializationLimits.copy(maxProvenanceEntries = it)
        }
        request = request.withLimits(materializationLimits)
    }
    return request
}

private fun emitEdit(facts: Facts, state: DocState, step: StepDesc) {
    if (state.editRun) {
        return
    }
    fun blocked() {
        facts.set("edit.status", "Blocked")
        facts.set("edit.failure", "")
        facts.set("edit.output", "")
        facts.set("edit.source_edit_count", "")
    }
    if (step.op != "edit" || !state.documentParsed()) {
        state.editRun = true
        blocked()
        return
    }
    state.editRun = true
    val outcome = runEdit(state, step)
    when (outcome) {
        is EditOutcome.Completed -> {
            facts.set("edit.status", "Completed")
            facts.set("edit.failure", "")
            facts.set("edit.output", escape(outcome.output))
            facts.set("edit.source_edit_count", outcome.sourceEditCount.toString())
        }
        is EditOutcome.Failed -> {
            facts.set("edit.status", "Failed")
            facts.set("edit.failure", outcome.code)
            facts.set("edit.output", "")
            facts.set("edit.source_edit_count", "")
        }
    }
}

private sealed class EditOutcome {
    data class Completed(val output: String, val sourceEditCount: Int) : EditOutcome()
    data class Failed(val code: String) : EditOutcome()
}

/** Executes one atomic edit transaction under the current family. */
private fun runEdit(state: DocState, step: StepDesc): EditOutcome {
    if (state.jsonDoc != null) {
        if (!ensureForeignJson(state)) {
            return EditOutcome.Failed("core.source.invalid-sequence@1")
        }
        val builder = consema.json.EditTransactionBuilder.new(state.jsonDoc!!)
        if (!applyJsonEditOperations(builder, state, step)) {
            return EditOutcome.Failed("core.edit.target-not-found@1")
        }
        return try {
            val commit = state.jsonDoc!!.commit(builder.build())
            EditOutcome.Completed(
                String(commit.document.render(), Charsets.UTF_8),
                commit.sourcePatch.replacements().size,
            )
        } catch (e: consema.json.EditFailureException) {
            EditOutcome.Failed(jsonEditCode(e.failure))
        }
    }
    if (state.tomlDoc != null) {
        if (!ensureForeignToml(state)) {
            return EditOutcome.Failed("core.source.invalid-sequence@1")
        }
        val builder = consema.toml.EditTransactionBuilder.new(state.tomlDoc!!)
        if (!applyTomlEditOperations(builder, state, step)) {
            return EditOutcome.Failed("core.edit.target-not-found@1")
        }
        return try {
            val commit = state.tomlDoc!!.commit(builder.build())
            EditOutcome.Completed(
                String(commit.document.render(), Charsets.UTF_8),
                commit.sourcePatch.replacements().size,
            )
        } catch (e: consema.toml.TomlEditException) {
            EditOutcome.Failed(tomlEditCode(e.kind))
        }
    }
    if (state.yamlDoc != null) {
        if (!ensureForeignYaml(state)) {
            return EditOutcome.Failed("core.source.invalid-sequence@1")
        }
        val builder = consema.yaml.EditTransactionBuilder.new(state.yamlDoc!!)
        if (!applyYamlEditOperations(builder, state, step)) {
            return EditOutcome.Failed("core.edit.target-not-found@1")
        }
        return try {
            val commit = state.yamlDoc!!.commit(builder.build())
            EditOutcome.Completed(
                String(commit.document.render(), Charsets.UTF_8),
                commit.sourcePatch.replacements().size,
            )
        } catch (e: consema.yaml.EditFailureException) {
            EditOutcome.Failed(yamlEditCode(e.failure))
        }
    }
    if (state.iniDoc != null) {
        if (!ensureForeignIni(state)) {
            return EditOutcome.Failed("core.source.invalid-sequence@1")
        }
        val builder = consema.ini.EditTransactionBuilder.new(state.iniDoc!!)
        if (!applyIniEditOperations(builder, state, step)) {
            return EditOutcome.Failed("core.edit.target-not-found@1")
        }
        return try {
            val commit = state.iniDoc!!.commit(builder.build())
            EditOutcome.Completed(
                String(commit.document.render(), Charsets.UTF_8),
                commit.sourcePatch?.replacements()?.size ?: 0,
            )
        } catch (e: consema.ini.EditFailureException) {
            EditOutcome.Failed(iniEditCode(e.failure))
        }
    }
    if (state.propertiesDoc != null) {
        if (!ensureForeignProperties(state)) {
            return EditOutcome.Failed("core.source.invalid-sequence@1")
        }
        val builder = consema.properties.EditTransactionBuilder.new(state.propertiesDoc!!)
        if (!applyPropertiesEditOperations(builder, state, step)) {
            return EditOutcome.Failed("core.edit.target-not-found@1")
        }
        return try {
            val commit = state.propertiesDoc!!.commit(builder.build())
            EditOutcome.Completed(
                String(commit.document.render(), Charsets.UTF_8),
                commit.sourcePatch.replacements().size,
            )
        } catch (e: consema.properties.EditFailureException) {
            EditOutcome.Failed(propertiesEditCode(e.failure))
        }
    }
    return EditOutcome.Failed("core.edit.target-not-found@1")
}

// --- edit failure codes (mirroring the conformance runner mappings) -------

private fun jsonEditCode(failure: consema.json.EditFailure): String = when (failure) {
    consema.json.EditFailure.RecoveredDocument, consema.json.EditFailure.IncompleteTarget ->
        "core.edit.incomplete-target@1"
    consema.json.EditFailure.WrongSnapshot -> "core.edit.wrong-snapshot@1"
    consema.json.EditFailure.WrongRole -> "core.edit.wrong-role@1"
    consema.json.EditFailure.SemanticUnavailable -> "core.edit.semantic-unavailable@1"
    is consema.json.EditFailure.UnsupportedSemanticValue, is consema.json.EditFailure.UnrepresentableValue ->
        "core.edit.unsupported-value@1"
    consema.json.EditFailure.InvalidLiteral -> "core.edit.invalid-literal@1"
    consema.json.EditFailure.RepresentationIncompatible ->
        "core.edit.representation-incompatible@1"
    consema.json.EditFailure.ExactLiteralRequiresLiteralOperation ->
        "core.edit.exact-literal-requires-literal@1"
    consema.json.EditFailure.ConflictingEdits,
    consema.json.EditFailure.DuplicateTarget,
    consema.json.EditFailure.OverlappingOwnership,
    consema.json.EditFailure.AncestorDescendantConflict,
    consema.json.EditFailure.PlacementAnchorRemoved,
    consema.json.EditFailure.PlacementAnchorModified,
    -> "core.edit.conflicting-edits@1"
    consema.json.EditFailure.TargetNotFound -> "core.edit.target-not-found@1"
    is consema.json.EditFailure.ResourceLimit -> "core.edit.resource-limit@1"
    consema.json.EditFailure.NewDocumentFormationFailed -> "core.edit.formation-failed@1"
}

/** The TOML edit failure code is carried by the family enum's frozen code
 * property (toml/Edit.kt, edit.rs:1308-1331). */
private fun tomlEditCode(failure: consema.toml.EditFailureKind): String = failure.code

/** The YAML edit failure code is carried by the family enum's frozen code
 * property (yaml/Edit.kt, yaml edit.rs:318-343). */
private fun yamlEditCode(failure: consema.yaml.EditFailure): String = failure.code

/** The INI edit failure codes (ini edit.rs:1754-1777). */
private fun iniEditCode(failure: consema.ini.EditFailure): String = when (failure) {
    consema.ini.EditFailure.RecoveredDocument -> "core.edit.incomplete-target@1"
    consema.ini.EditFailure.WrongSnapshot -> "core.edit.wrong-snapshot@1"
    consema.ini.EditFailure.WrongRole -> "core.edit.wrong-role@1"
    consema.ini.EditFailure.DuplicateTarget,
    consema.ini.EditFailure.OverlappingOwnership,
    consema.ini.EditFailure.AncestorDescendantConflict,
    consema.ini.EditFailure.PlacementAnchorRemoved,
    -> "core.edit.conflicting-edits@1"
    consema.ini.EditFailure.TargetNotFound -> "core.edit.target-not-found@1"
    consema.ini.EditFailure.InvalidPlacement -> "ini.edit.invalid-placement@1"
    consema.ini.EditFailure.InvalidName,
    consema.ini.EditFailure.InvalidKey,
    -> "ini.edit.invalid-name@1"
    consema.ini.EditFailure.NameCollision,
    consema.ini.EditFailure.DuplicateKey,
    -> "core.edit.duplicate-key@1"
    consema.ini.EditFailure.KeyCollision -> "ini.edit.case-collision@1"
    consema.ini.EditFailure.RepresentationIncompatible,
    consema.ini.EditFailure.EncodingUnrepresentable,
    -> "core.edit.representation-incompatible@1"
    consema.ini.EditFailure.ExactLiteralRequiresLiteralOperation ->
        "core.edit.exact-literal-requires-literal@1"
    consema.ini.EditFailure.UnrepresentableValue -> "core.edit.unsupported-value@1"
    consema.ini.EditFailure.InvalidLiteral -> "core.edit.invalid-literal@1"
    is consema.ini.EditFailure.ResourceLimit -> "core.edit.resource-limit@1"
    consema.ini.EditFailure.NewDocumentFormationFailed -> "core.edit.formation-failed@1"
}

/** The Properties edit failure codes (properties edit.rs diagnostic_code). */
private fun propertiesEditCode(failure: consema.properties.EditFailure): String = when (failure) {
    consema.properties.EditFailure.RecoveredDocument -> "core.edit.incomplete-target@1"
    consema.properties.EditFailure.WrongSnapshot -> "core.edit.wrong-snapshot@1"
    consema.properties.EditFailure.WrongRole -> "core.edit.wrong-role@1"
    consema.properties.EditFailure.DuplicateTarget,
    consema.properties.EditFailure.OverlappingOwnership,
    consema.properties.EditFailure.PlacementAnchorRemoved,
    -> "core.edit.conflicting-edits@1"
    consema.properties.EditFailure.InvalidPlacement -> "java-properties.edit.invalid-placement@1"
    consema.properties.EditFailure.TargetNotFound -> "core.edit.target-not-found@1"
    consema.properties.EditFailure.EncodingUnrepresentable ->
        "core.edit.representation-incompatible@1"
    consema.properties.EditFailure.InvalidLiteral -> "core.edit.invalid-literal@1"
    is consema.properties.EditFailure.ResourceLimit -> "core.edit.resource-limit@1"
    consema.properties.EditFailure.NewDocumentFormationFailed -> "core.edit.formation-failed@1"
}

// --- edit operation application and target resolution ---------------------

private fun applyJsonEditOperations(
    builder: consema.json.EditTransactionBuilder,
    state: DocState,
    step: StepDesc,
): Boolean {
    for (op in step.operations) {
        when (op.operation) {
            "semantic-scalar" -> {
                val value = op.value?.coreValue() ?: return false
                val target = resolveJsonTarget(state, op.target) ?: return false
                val policy = jsonRepresentationPolicy(op.policy) ?: return false
                builder.semanticScalar(target, value, policy)
            }
            "literal-scalar" -> {
                val target = resolveJsonTarget(state, op.target) ?: return false
                val literal = try {
                    unhex(op.literalHex)
                } catch (e: Exception) {
                    return false
                }
                builder.literalScalar(target, literal)
            }
            "insert-member" -> {
                val container = resolveJsonTarget(state, op.target) ?: return false
                val value = op.value?.coreValue() ?: return false
                val placement = resolveJsonPlacement(state, op.placement) ?: return false
                builder.insertMember(container, op.name, value, placement)
            }
            "remove-member" -> {
                val target = resolveJsonTarget(state, op.target) ?: return false
                builder.removeMember(target)
            }
            "rename-member" -> {
                val target = resolveJsonTarget(state, op.target) ?: return false
                builder.renameMember(target, op.name)
            }
            "insert-array-element" -> {
                val container = resolveJsonTarget(state, op.target) ?: return false
                val value = op.value?.coreValue() ?: return false
                val placement = resolveJsonPlacement(state, op.placement) ?: return false
                builder.insertArrayElement(container, value, placement)
            }
            "remove-array-element" -> {
                val target = resolveJsonTarget(state, op.target) ?: return false
                builder.removeArrayElement(target)
            }
            else -> return false
        }
    }
    return true
}

private fun applyTomlEditOperations(
    builder: consema.toml.EditTransactionBuilder,
    state: DocState,
    step: StepDesc,
): Boolean {
    for (op in step.operations) {
        when (op.operation) {
            "semantic-scalar" -> {
                val value = op.value?.coreValue() ?: return false
                val target = resolveTomlTarget(state, op.target) ?: return false
                val policy = tomlRepresentationPolicy(op.policy) ?: return false
                builder.semanticScalar(target, value, policy)
            }
            "literal-scalar" -> {
                val target = resolveTomlTarget(state, op.target) ?: return false
                val literal = try {
                    unhex(op.literalHex)
                } catch (e: Exception) {
                    return false
                }
                builder.literalScalar(target, literal)
            }
            "insert-entry" -> {
                val container = resolveTomlTarget(state, op.target) ?: return false
                val value = op.value?.coreValue() ?: return false
                val placement = resolveTomlPlacement(state, op.placement) ?: return false
                builder.insertEntry(container, op.name, value, placement)
            }
            "remove-entry" -> {
                val target = resolveTomlTarget(state, op.target) ?: return false
                builder.removeEntry(target)
            }
            "rename-entry" -> {
                val target = resolveTomlTarget(state, op.target) ?: return false
                builder.renameEntry(target, op.name)
            }
            "insert-array-element" -> {
                val container = resolveTomlTarget(state, op.target) ?: return false
                val value = op.value?.coreValue() ?: return false
                val placement = resolveTomlPlacement(state, op.placement) ?: return false
                builder.insertArrayElement(container, value, placement)
            }
            "remove-array-element" -> {
                val target = resolveTomlTarget(state, op.target) ?: return false
                builder.removeArrayElement(target)
            }
            else -> return false
        }
    }
    return true
}

private fun applyYamlEditOperations(
    builder: consema.yaml.EditTransactionBuilder,
    state: DocState,
    step: StepDesc,
): Boolean {
    for (op in step.operations) {
        when (op.operation) {
            "semantic-scalar" -> {
                val value = op.value?.coreValue() ?: return false
                val target = resolveYamlTarget(state, op.target) ?: return false
                val policy = yamlRepresentationPolicy(op.policy) ?: return false
                builder.semanticScalar(target, value, policy)
            }
            "literal-scalar" -> {
                val target = resolveYamlTarget(state, op.target) ?: return false
                val literal = try {
                    unhex(op.literalHex)
                } catch (e: Exception) {
                    return false
                }
                builder.literalScalar(target, literal)
            }
            "rename-anchor" -> {
                val target = resolveYamlTarget(state, op.target) ?: return false
                builder.renameAnchor(target, op.name)
            }
            "insert-mapping-entry" -> {
                val container = resolveYamlTarget(state, op.target) ?: return false
                val value = op.value?.coreValue() ?: return false
                val placement = resolveYamlPlacement(state, op.placement) ?: return false
                builder.insertMappingEntry(container, consema.core.PvString(op.name), value, placement)
            }
            "remove-mapping-entry" -> {
                val target = resolveYamlTarget(state, op.target) ?: return false
                builder.removeMappingEntry(target)
            }
            "insert-sequence-element" -> {
                val container = resolveYamlTarget(state, op.target) ?: return false
                val value = op.value?.coreValue() ?: return false
                val placement = resolveYamlPlacement(state, op.placement) ?: return false
                builder.insertSequenceElement(container, value, placement)
            }
            "remove-sequence-element" -> {
                val target = resolveYamlTarget(state, op.target) ?: return false
                builder.removeSequenceElement(target)
            }
            else -> return false
        }
    }
    return true
}

private fun applyIniEditOperations(
    builder: consema.ini.EditTransactionBuilder,
    state: DocState,
    step: StepDesc,
): Boolean {
    for (op in step.operations) {
        when (op.operation) {
            "semantic-value" -> {
                val target = resolveIniTarget(state, op.target) ?: return false
                val value = op.value?.coreString() ?: return false
                val policy = iniRepresentationPolicy(op.policy) ?: return false
                builder.semanticValue(target, value, policy)
            }
            "literal-value" -> {
                val target = resolveIniTarget(state, op.target) ?: return false
                val literal = try {
                    unhex(op.literalHex)
                } catch (e: Exception) {
                    return false
                }
                builder.literalValue(target, literal)
            }
            "insert-section" -> {
                val container = resolveIniTarget(state, op.target) ?: return false
                val placement = resolveIniPlacement(op.placement) ?: return false
                builder.insertSection(container, op.name, placement)
            }
            "remove-section" -> {
                val target = resolveIniTarget(state, op.target) ?: return false
                builder.removeSection(target)
            }
            "rename-section" -> {
                val target = resolveIniTarget(state, op.target) ?: return false
                builder.renameSection(target, op.name)
            }
            "insert-entry" -> {
                val container = resolveIniTarget(state, op.target) ?: return false
                val value = op.value?.coreString() ?: return false
                val placement = resolveIniPlacement(op.placement) ?: return false
                builder.insertEntry(container, op.name, value, placement)
            }
            "remove-entry" -> {
                val target = resolveIniTarget(state, op.target) ?: return false
                builder.removeEntry(target)
            }
            "rename-entry" -> {
                val target = resolveIniTarget(state, op.target) ?: return false
                builder.renameEntry(target, op.name)
            }
            else -> return false
        }
    }
    return true
}

private fun applyPropertiesEditOperations(
    builder: consema.properties.EditTransactionBuilder,
    state: DocState,
    step: StepDesc,
): Boolean {
    for (op in step.operations) {
        when (op.operation) {
            "semantic-value" -> {
                val target = resolvePropertiesTarget(state, op.target) ?: return false
                val value = op.value?.coreString() ?: return false
                builder.semanticValue(target, consema.properties.JavaString.fromUnicode(value))
            }
            "literal-value" -> {
                val target = resolvePropertiesTarget(state, op.target) ?: return false
                val literal = try {
                    unhex(op.literalHex)
                } catch (e: Exception) {
                    return false
                }
                builder.literalValue(target, literal)
            }
            "insert-property" -> {
                val container = resolvePropertiesTarget(state, op.target) ?: return false
                val value = op.value?.coreString() ?: return false
                val placement = resolvePropertiesPlacement(op.placement) ?: return false
                builder.insertProperty(
                    container,
                    consema.properties.JavaString.fromUnicode(op.name),
                    consema.properties.JavaString.fromUnicode(value),
                    placement,
                )
            }
            "remove-property" -> {
                val target = resolvePropertiesTarget(state, op.target) ?: return false
                builder.removeProperty(target)
            }
            "rename-property" -> {
                val target = resolvePropertiesTarget(state, op.target) ?: return false
                builder.renameProperty(target, consema.properties.JavaString.fromUnicode(op.name))
            }
            else -> return false
        }
    }
    return true
}

private fun ValueDesc.coreValue(): consema.core.PortableValue? {
    return when {
        nullValue != null -> consema.core.PvNull
        boolean != null -> consema.core.PvBoolean(boolean!!)
        integer.isNotEmpty() -> consema.core.PvInteger.of(BigInteger(integer))
        decimal.isNotEmpty() -> parseDecimalNumber(decimal)
        string.isNotEmpty() -> consema.core.PvString(string)
        binary64.isNotEmpty() -> consema.core.PvBinaryFloat64(
            java.lang.Long.parseUnsignedLong(binary64.removePrefix("0x"), 16),
        )
        else -> null
    }
}

private fun ValueDesc.coreString(): String? = if (string.isNotEmpty()) string else null

/** Parses one JSON-number spelling ("1.00", "10e-1") into its canonical
 * coefficient x 10^exponent decimal (the conformance runner helper). */
private fun parseDecimalNumber(source: String): consema.core.PvDecimal? {
    var coefficientText = source
    var exponent = BigInteger.ZERO
    val exponentIndex = source.indexOfFirst { it == 'e' || it == 'E' }
    if (exponentIndex >= 0) {
        val exponentText = source.substring(exponentIndex + 1)
        coefficientText = source.substring(0, exponentIndex)
        exponent = BigInteger(exponentText) // throws on malformed text
    }
    var scale = BigInteger.ZERO
    val dotIndex = coefficientText.indexOf('.')
    if (dotIndex >= 0) {
        val fraction = coefficientText.substring(dotIndex + 1)
        coefficientText = coefficientText.substring(0, dotIndex) + fraction
        scale = BigInteger.valueOf(-fraction.length.toLong())
    }
    if (coefficientText.isEmpty() || coefficientText == "-" || coefficientText == "+") {
        return null
    }
    val coefficient = try {
        BigInteger(coefficientText)
    } catch (e: NumberFormatException) {
        return null
    }
    return consema.core.PvDecimal.of(coefficient, exponent.add(scale))
}

private fun jsonRepresentationPolicy(name: String): consema.json.RepresentationPolicy? = when (name) {
    "PreserveCompatible" -> consema.json.RepresentationPolicy.PreserveCompatible
    "CanonicalForProfile" -> consema.json.RepresentationPolicy.CanonicalForProfile
    "PreserveElseCanonical" -> consema.json.RepresentationPolicy.PreserveElseCanonical
    "ExactLiteral" -> consema.json.RepresentationPolicy.ExactLiteral
    else -> null
}

private fun tomlRepresentationPolicy(name: String): consema.toml.RepresentationPolicy? = when (name) {
    "PreserveCompatible" -> consema.toml.RepresentationPolicy.PreserveCompatible
    "CanonicalForProfile" -> consema.toml.RepresentationPolicy.CanonicalForProfile
    "PreserveElseCanonical" -> consema.toml.RepresentationPolicy.PreserveElseCanonical
    "ExactLiteral" -> consema.toml.RepresentationPolicy.ExactLiteral
    else -> null
}

private fun yamlRepresentationPolicy(name: String): consema.yaml.RepresentationPolicy? = when (name) {
    "PreserveCompatible" -> consema.yaml.RepresentationPolicy.PreserveCompatible
    "CanonicalForProfile" -> consema.yaml.RepresentationPolicy.CanonicalForProfile
    "PreserveElseCanonical" -> consema.yaml.RepresentationPolicy.PreserveElseCanonical
    "ExactLiteral" -> consema.yaml.RepresentationPolicy.ExactLiteral
    else -> null
}

private fun iniRepresentationPolicy(name: String): consema.ini.RepresentationPolicy? = when (name) {
    "PreserveCompatible" -> consema.ini.RepresentationPolicy.PreserveCompatible
    "CanonicalForProfile" -> consema.ini.RepresentationPolicy.CanonicalForProfile
    "PreserveElseCanonical" -> consema.ini.RepresentationPolicy.PreserveElseCanonical
    "ExactLiteral" -> consema.ini.RepresentationPolicy.ExactLiteral
    else -> null
}

// --- target and placement resolution --------------------------------------

private fun resolveJsonTarget(state: DocState, target: TargetDesc?): consema.document.NodeRef? {
    if (target == null) {
        return null
    }
    val doc = if (target.foreign) {
        state.foreignJson ?: return null
    } else {
        state.jsonDoc ?: return null
    }
    val root = doc.root()
    return when (target.kind) {
        "root" -> root.nodeRef()
        "member" -> {
            val members = root.objectMembers()
            val items = (members as? SemanticAvailability.Available)?.value ?: return null
            if (target.ordinal >= items.size) null else items[target.ordinal].nodeRef()
        }
        "member-value" -> {
            val members = root.objectMembers()
            val items = (members as? SemanticAvailability.Available)?.value ?: return null
            if (target.ordinal >= items.size) null else items[target.ordinal].valueNodeRef()
        }
        "member-key" -> {
            val members = root.objectMembers()
            val items = (members as? SemanticAvailability.Available)?.value ?: return null
            if (target.ordinal >= items.size) null else items[target.ordinal].keyNodeRef()
        }
        "array-element" -> {
            val elements = root.arrayElements()
            val items = (elements as? SemanticAvailability.Available)?.value ?: return null
            if (target.ordinal >= items.size) null else items[target.ordinal].nodeRef()
        }
        "array-element-value" -> {
            val elements = root.arrayElements()
            val items = (elements as? SemanticAvailability.Available)?.value ?: return null
            if (target.ordinal >= items.size) null else items[target.ordinal].valueNodeRef()
        }
        else -> null
    }
}

private fun resolveTomlTarget(state: DocState, target: TargetDesc?): consema.document.NodeRef? {
    if (target == null) {
        return null
    }
    val doc = if (target.foreign) {
        state.foreignToml ?: return null
    } else {
        state.tomlDoc ?: return null
    }
    val root = doc.root()
    return when (target.kind) {
        "root" -> root.nodeRef
        "entry" -> {
            val entries = root.tableEntries() ?: return null
            if (target.ordinal >= entries.size) null else entries[target.ordinal].nodeRef
        }
        "entry-item" -> {
            val entries = root.tableEntries() ?: return null
            if (target.ordinal >= entries.size) null else entries[target.ordinal].itemNodeRef
        }
        "entry-key" -> {
            val entries = root.tableEntries() ?: return null
            if (target.ordinal >= entries.size) null else entries[target.ordinal].keyNodeRef
        }
        "array-element" -> {
            val elements = root.arrayElements() ?: return null
            if (target.ordinal >= elements.size) null else elements[target.ordinal].nodeRef
        }
        "array-element-item" -> {
            val elements = root.arrayElements() ?: return null
            if (target.ordinal >= elements.size) null else elements[target.ordinal].itemNodeRef
        }
        else -> null
    }
}

private fun resolveYamlTarget(state: DocState, target: TargetDesc?): consema.document.NodeRef? {
    if (target == null) {
        return null
    }
    val doc = if (target.foreign) {
        state.foreignYaml ?: return null
    } else {
        state.yamlDoc ?: return null
    }
    val yamlDoc = doc.document(0) ?: return null
    val root = yamlDoc.root()
    return when (target.kind) {
        "document-root" -> root.nodeRef()
        "mapping-entry" -> root.mappingEntry(target.ordinal)?.nodeRef()
        "mapping-value" -> root.mappingEntry(target.ordinal)?.value()?.nodeRef()
        "mapping-key" -> root.mappingEntry(target.ordinal)?.key()?.nodeRef()
        "sequence-element" -> {
            root.sequenceItem(target.ordinal)?.nodeRef()
                ?: root.mappingEntry(0)?.value()?.sequenceItem(target.ordinal)?.nodeRef()
        }
        "sequence-element-node" -> {
            root.sequenceItem(target.ordinal)?.node()?.nodeRef()
                ?: root.mappingEntry(0)?.value()?.sequenceItem(target.ordinal)?.node()?.nodeRef()
        }
        "anchor-value" -> root.mappingEntry(target.ordinal)?.value()?.anchorNodeRef()
        else -> null
    }
}

private fun resolveIniTarget(state: DocState, target: TargetDesc?): consema.document.NodeRef? {
    if (target == null) {
        return null
    }
    val doc = if (target.foreign) {
        state.foreignIni ?: return null
    } else {
        state.iniDoc ?: return null
    }
    return when (target.kind) {
        "document" -> doc.nodeRef()
        "section" -> {
            val sections = doc.sections()
            if (target.ordinal >= sections.size) null else sections[target.ordinal].nodeRef
        }
        "entry" -> {
            val entries = doc.entries()
            if (target.ordinal >= entries.size) null else entries[target.ordinal].nodeRef
        }
        else -> null
    }
}

private fun resolvePropertiesTarget(state: DocState, target: TargetDesc?): consema.document.NodeRef? {
    if (target == null) {
        return null
    }
    val doc = if (target.foreign) {
        state.foreignProperties ?: return null
    } else {
        state.propertiesDoc ?: return null
    }
    return when (target.kind) {
        "document" -> doc.nodeRef()
        "property" -> {
            val properties = doc.properties()
            if (target.ordinal >= properties.size) null else properties[target.ordinal].nodeRef()
        }
        else -> null
    }
}

private fun resolveJsonPlacement(
    state: DocState,
    placement: PlacementDesc?,
): AssociationPlacement? = resolveOrdinalPlacement(placement, { ordinal ->
    val doc = state.jsonDoc ?: return@resolveOrdinalPlacement null
    val root = doc.root()
    val members = root.objectMembers()
    if (members is SemanticAvailability.Available) {
        val items = members.value ?: emptyList()
        if (ordinal < items.size) {
            return@resolveOrdinalPlacement items[ordinal].nodeRef()
        }
    }
    val elements = root.arrayElements()
    if (elements is SemanticAvailability.Available) {
        val items = elements.value ?: emptyList()
        if (ordinal < items.size) {
            return@resolveOrdinalPlacement items[ordinal].nodeRef()
        }
    }
    null
})

private fun resolveTomlPlacement(
    state: DocState,
    placement: PlacementDesc?,
): AssociationPlacement? = resolveOrdinalPlacement(placement, { ordinal ->
    val doc = state.tomlDoc ?: return@resolveOrdinalPlacement null
    val root = doc.root()
    val entries = root.tableEntries()
    if (entries != null && ordinal < entries.size) {
        return@resolveOrdinalPlacement entries[ordinal].nodeRef
    }
    val elements = root.arrayElements()
    if (elements != null && ordinal < elements.size) {
        return@resolveOrdinalPlacement elements[ordinal].nodeRef
    }
    null
})

private fun resolveYamlPlacement(
    state: DocState,
    placement: PlacementDesc?,
): AssociationPlacement? = resolveOrdinalPlacement(placement, { ordinal ->
    val doc = state.yamlDoc ?: return@resolveOrdinalPlacement null
    val yamlDoc = doc.document(0) ?: return@resolveOrdinalPlacement null
    val root = yamlDoc.root()
    root.mappingEntry(ordinal)?.nodeRef() ?: root.sequenceItem(ordinal)?.nodeRef()
})

private fun resolveIniPlacement(placement: PlacementDesc?): AssociationPlacement? =
    resolveOrdinalPlacement(placement) { null }

private fun resolvePropertiesPlacement(placement: PlacementDesc?): AssociationPlacement? =
    resolveOrdinalPlacement(placement) { null }

/** Resolves one placement descriptor: start/end or an ordinal anchor. */
private fun resolveOrdinalPlacement(
    placement: PlacementDesc?,
    anchor: (Int) -> consema.document.NodeRef?,
): AssociationPlacement? {
    if (placement == null) {
        return AssociationPlacement.End
    }
    when (placement.at) {
        "start" -> return AssociationPlacement.Start
        "end" -> return AssociationPlacement.End
    }
    if (placement.beforeOrdinal != null) {
        val resolved = anchor(placement.beforeOrdinal) ?: return null
        return AssociationPlacement.Before(resolved)
    }
    if (placement.afterOrdinal != null) {
        val resolved = anchor(placement.afterOrdinal) ?: return null
        return AssociationPlacement.After(resolved)
    }
    return AssociationPlacement.End
}

// --- foreign-source parsing (the wrong-snapshot edit cases) ----------------

/** Parses the foreign source when the case declares one. A declared source
 * that fails to decode or parse reports edit.failure =
 * core.source.invalid-sequence@1 (the Go-side norm the Rust example
 * mirrors). */
private fun ensureForeignJson(state: DocState): Boolean {
    if (state.foreignJson != null ||
        (state.case.foreignSource.isEmpty() && state.case.foreignSourceHex.isEmpty())
    ) {
        return true
    }
    val bytes = foreignBytes(state) ?: return false
    val profile = when (state.profile) {
        "json.strict@1" -> JsonProfile.StrictV1
        "jsonc.bounded@1" -> JsonProfile.JsoncBoundedV1
        "json5.standard@1" -> JsonProfile.Json5StandardV1
        else -> return false
    }
    return try {
        state.foreignJson = consema.json.parse(bytes, profile, state.parseLimits)
        true
    } catch (e: consema.json.JsonFormationException) {
        false
    }
}

private fun ensureForeignToml(state: DocState): Boolean {
    if (state.foreignToml != null ||
        (state.case.foreignSource.isEmpty() && state.case.foreignSourceHex.isEmpty())
    ) {
        return true
    }
    val bytes = foreignBytes(state) ?: return false
    return try {
        state.foreignToml = consema.toml.parse(bytes, consema.toml.TomlProfile.TOML_1_0_V1, state.parseLimits)
        true
    } catch (e: consema.toml.TomlFormationException) {
        false
    }
}

private fun ensureForeignYaml(state: DocState): Boolean {
    if (state.foreignYaml != null ||
        (state.case.foreignSource.isEmpty() && state.case.foreignSourceHex.isEmpty())
    ) {
        return true
    }
    val bytes = foreignBytes(state) ?: return false
    val profile = when (state.profile) {
        "yaml.1.2-core@1" -> consema.yaml.YamlProfile.Yaml12CoreV1
        "yaml.1.1-compat@1" -> consema.yaml.YamlProfile.Yaml11CompatV1
        else -> return false
    }
    return try {
        state.foreignYaml = consema.yaml.parse(bytes, profile, state.parseLimits)
        true
    } catch (e: consema.yaml.YamlFormationException) {
        false
    }
}

private fun ensureForeignIni(state: DocState): Boolean {
    if (state.foreignIni != null ||
        (state.case.foreignSource.isEmpty() && state.case.foreignSourceHex.isEmpty())
    ) {
        return true
    }
    val bytes = foreignBytes(state) ?: return false
    val profile = when (state.profile) {
        "ini.portable@1" -> consema.ini.IniProfile.PortableV1
        "ini.windows@1" -> consema.ini.IniProfile.WindowsV1
        "ini.python-configparser@1" -> consema.ini.IniProfile.PythonConfigParserV1
        else -> return false
    }
    return try {
        state.foreignIni = consema.ini.parse(bytes, profile, limits = state.iniLimits)
        true
    } catch (e: consema.ini.IniFormationException) {
        false
    }
}

private fun ensureForeignProperties(state: DocState): Boolean {
    if (state.foreignProperties != null ||
        (state.case.foreignSource.isEmpty() && state.case.foreignSourceHex.isEmpty())
    ) {
        return true
    }
    val bytes = foreignBytes(state) ?: return false
    return try {
        state.foreignProperties =
            consema.properties.parseReader(bytes, SourceEncoding.Utf8, state.propertiesLimits)
        true
    } catch (e: consema.properties.PropertiesFormationException) {
        false
    }
}

private fun foreignBytes(state: DocState): ByteArray? {
    if (state.case.foreignSourceHex.isNotEmpty()) {
        return try {
            unhex(state.case.foreignSourceHex)
        } catch (e: Exception) {
            null
        }
    }
    return state.case.foreignSource.toByteArray(Charsets.UTF_8)
}

// ---------------------------------------------------------------------------
// Source face
// ---------------------------------------------------------------------------

/** Runs one source-face case and returns its ordered normalized facts. */
private fun runSourceCase(case: NormalizedCase): List<String> {
    val facts = Facts()
    val raw = sourceRawBytes(case) ?: return fatalSourceFacts(facts, case, "core.source.invalid-sequence@1")
    if (case.request?.profileDefault == "windows-1252") {
        // The document-layer v1 encoding set has no code pages; the
        // source-v2 snapshot publishes the same facts.
        return runSourceCaseV2(facts, case, raw)
    }
    val request = buildEncodingRequest(case) ?: return fatalSourceFacts(facts, case, "core.source.invalid-sequence@1")
    val snapshot = try {
        SourceSnapshot.fromRaw(raw, request, SourceLimits.default)
    } catch (e: consema.document.SourceException) {
        return fatalSourceFacts(facts, case, e.code)
    }
    facts.set("source.status", "Ok")
    facts.set("source.failure", "")
    facts.set("source.encoding", asStr(snapshot.encodingFacts.selected))
    facts.set("source.bom", bomName(snapshot.encodingFacts.bom))
    facts.set("source.declared", snapshot.encodingFacts.declaration?.let(::asStr) ?: "")
    facts.set("source.digest", snapshot.digest.toHex())
    facts.set("source.len", snapshot.len.toString())
    val text = snapshot.decodedText()
    facts.set("source.text", if (text != null) escape(text) else "binary")
    emitPositionFacts(facts, case, snapshot)
    emitPatchFacts(facts, case, raw, snapshot, request)
    return facts.lines()
}

/** The source-v2 path for the windows-1252 case (the document-layer v1
 * encoding set has no code pages). */
private fun runSourceCaseV2(facts: Facts, case: NormalizedCase, raw: ByteArray): List<String> {
    var v2Request = consema.protocol.V2EncodingRequest.new(
        consema.protocol.SourceEncoding("WindowsCodePage", 1252),
    )
    case.request?.let { requestDesc ->
        if (requestDesc.declaration.isNotEmpty()) {
            val declaration = v2EncodingByName(requestDesc.declaration) ?: return fatalSourceFacts(
                facts, case, "core.source.invalid-sequence@1",
            )
            v2Request = v2Request.withDeclaration(declaration)
        }
        if (requestDesc.callerOverride.isNotEmpty()) {
            val override = v2EncodingByName(requestDesc.callerOverride) ?: return fatalSourceFacts(
                facts, case, "core.source.invalid-sequence@1",
            )
            v2Request = v2Request.withCallerOverride(override)
        }
        if (requestDesc.bomPolicy == "TreatAsContent") {
            v2Request = v2Request.withBomPolicy("TreatAsContent")
        }
    }
    val snapshot = try {
        consema.protocol.SourceSnapshotV2.fromRaw(raw, v2Request, consema.protocol.SourceLimits.default)
    } catch (e: Exception) {
        return fatalSourceFacts(facts, case, "core.source.invalid-sequence@1")
    }
    val selected = snapshot.encodingFacts.selected
    if (selected == null) {
        return fatalSourceFacts(facts, case, "core.source.invalid-sequence@1")
    }
    facts.set("source.status", "Ok")
    facts.set("source.failure", "")
    facts.set("source.encoding", protocolEncodingAsStr(selected))
    facts.set("source.bom", protocolBomName(snapshot.encodingFacts.bom))
    facts.set("source.declared", snapshot.encodingFacts.declaration?.let(::protocolEncodingAsStr) ?: "")
    facts.set("source.digest", snapshot.digest.hex())
    facts.set("source.len", snapshot.len.toString())
    val text = snapshot.decodedText()
    facts.set("source.text", if (text != null) escape(text) else "binary")
    for ((index, rawByte) in case.positions.withIndex()) {
        val key = "source.position.$index."
        try {
            val position = snapshot.decodedPosition(rawByte)
            facts.set(key + "raw_byte", position.rawByte.toString())
            facts.set(key + "decoded_utf8", position.decodedUtf8Byte.toString())
            facts.set(key + "scalars", position.unicodeScalarOffset.toString())
            facts.set(key + "utf16", position.utf16CodeUnitOffset.toString())
        } catch (e: Exception) {
            facts.set(key + "raw_byte", rawByte.toString())
            facts.set(key + "decoded_utf8", "")
            facts.set(key + "scalars", "")
            facts.set(key + "utf16", "")
        }
    }
    facts.set("patch.status", "Skipped")
    facts.set("patch.failure", "")
    facts.set("patch.output", "")
    facts.set("patch.replacement_count", "")
    return facts.lines()
}

/** Resolves one stable encoding name on the source-v2 surface. */
private fun v2EncodingByName(name: String): consema.protocol.SourceEncoding? = when (name) {
    "binary" -> consema.protocol.SourceEncoding("Binary", null)
    "utf-8" -> consema.protocol.SourceEncoding("Utf8", null)
    "utf-16le" -> consema.protocol.SourceEncoding("Utf16Le", null)
    "utf-16be" -> consema.protocol.SourceEncoding("Utf16Be", null)
    "latin-1" -> consema.protocol.SourceEncoding("Latin1", null)
    "windows-1252" -> consema.protocol.SourceEncoding("WindowsCodePage", 1252)
    else -> null
}

private fun fatalSourceFacts(facts: Facts, case: NormalizedCase, code: String): List<String> {
    facts.set("source.status", "Failed")
    facts.set("source.failure", code)
    facts.set("source.encoding", "")
    facts.set("source.bom", "")
    facts.set("source.declared", "")
    facts.set("source.digest", "")
    facts.set("source.len", "")
    facts.set("source.text", "")
    for ((index, rawByte) in case.positions.withIndex()) {
        facts.set("source.position.$index.raw_byte", rawByte.toString())
        facts.set("source.position.$index.decoded_utf8", "")
        facts.set("source.position.$index.scalars", "")
        facts.set("source.position.$index.utf16", "")
    }
    facts.set("patch.status", "Skipped")
    facts.set("patch.failure", "")
    facts.set("patch.output", "")
    facts.set("patch.replacement_count", "")
    return facts.lines()
}

/** Resolves the raw input bytes of a source case. */
private fun sourceRawBytes(case: NormalizedCase): ByteArray? {
    val input = case.input ?: return null
    if (input.rawHex.isNotEmpty()) {
        return try {
            unhex(input.rawHex)
        } catch (e: Exception) {
            null
        }
    }
    return input.source.toByteArray(Charsets.UTF_8)
}

/** Resolves the encoding-resolution request of a source case (document v1
 * encodings). */
private fun buildEncodingRequest(case: NormalizedCase): EncodingRequest? {
    val request = case.request ?: return null
    val defaultEncoding = documentEncodingByName(request.profileDefault) ?: return null
    var built = EncodingRequest.new(defaultEncoding)
    if (request.declaration.isNotEmpty()) {
        val declaration = documentEncodingByName(request.declaration) ?: return null
        built = built.withDeclaration(declaration)
    }
    if (request.callerOverride.isNotEmpty()) {
        val override = documentEncodingByName(request.callerOverride) ?: return null
        built = built.withCallerOverride(override)
    }
    when (request.bomPolicy) {
        "", "DetectUnicode" -> { /* the default */ }
        "TreatAsContent" -> built = built.withBomPolicy(BomPolicy.TreatAsContent)
        else -> return null
    }
    return built
}

/** Resolves one stable encoding name on the document v1 surface. */
private fun documentEncodingByName(name: String): SourceEncoding? = when (name) {
    "binary" -> SourceEncoding.Binary
    "utf-8" -> SourceEncoding.Utf8
    "utf-16le" -> SourceEncoding.Utf16Le
    "utf-16be" -> SourceEncoding.Utf16Be
    "latin-1" -> SourceEncoding.Latin1
    else -> null
}

/** The stable wire identifier of one document encoding (the Go AsStr). */
private fun asStr(encoding: SourceEncoding): String = when (encoding) {
    SourceEncoding.Binary -> "binary"
    SourceEncoding.Utf8 -> "utf-8"
    SourceEncoding.Utf16Le -> "utf-16le"
    SourceEncoding.Utf16Be -> "utf-16be"
    SourceEncoding.Latin1 -> "latin-1"
}

/** Renders one detected BOM fact. */
private fun bomName(bom: BomKind?): String = when (bom) {
    null -> ""
    BomKind.Utf8 -> "utf-8"
    BomKind.Utf16Le -> "utf-16le"
    BomKind.Utf16Be -> "utf-16be"
}

/** The stable wire identifier of one source-v2 encoding. */
private fun protocolEncodingAsStr(encoding: consema.protocol.SourceEncoding): String = when (encoding.kind) {
    "Binary" -> "binary"
    "Utf8" -> "utf-8"
    "Utf16Le" -> "utf-16le"
    "Utf16Be" -> "utf-16be"
    "Latin1" -> "latin-1"
    "WindowsCodePage" -> "windows-" + (encoding.windowsCodePage ?: 0)
    else -> "unknown"
}

private fun protocolBomName(bom: String?): String = when (bom) {
    null -> ""
    "Utf8" -> "utf-8"
    "Utf16Le" -> "utf-16le"
    "Utf16Be" -> "utf-16be"
    else -> ""
}

/** Emits the byte-exact position conversions. */
private fun emitPositionFacts(
    facts: Facts,
    case: NormalizedCase,
    snapshot: SourceSnapshot,
) {
    for ((index, rawByte) in case.positions.withIndex()) {
        val key = "source.position.$index."
        try {
            val position: DecodedPosition = snapshot.decodedPosition(rawByte)
            facts.set(key + "raw_byte", position.rawByte.toString())
            facts.set(key + "decoded_utf8", position.decodedUtf8Byte.toString())
            facts.set(key + "scalars", position.unicodeScalarOffset.toString())
            facts.set(key + "utf16", position.utf16CodeUnitOffset.toString())
        } catch (e: Exception) {
            facts.set(key + "raw_byte", rawByte.toString())
            facts.set(key + "decoded_utf8", "")
            facts.set(key + "scalars", "")
            facts.set(key + "utf16", "")
        }
    }
}

/** Emits the optional SourcePatch application facts. */
private fun emitPatchFacts(
    facts: Facts,
    case: NormalizedCase,
    raw: ByteArray,
    snapshot: SourceSnapshot,
    request: EncodingRequest,
) {
    fun skipped() {
        facts.set("patch.status", "Skipped")
        facts.set("patch.failure", "")
        facts.set("patch.output", "")
        facts.set("patch.replacement_count", "")
    }
    fun failed(code: String) {
        facts.set("patch.status", "Failed")
        facts.set("patch.failure", code)
        facts.set("patch.output", "")
        facts.set("patch.replacement_count", "")
    }
    val patchDesc = case.patch ?: return skipped()
    val replacements = buildSourceReplacements(snapshot, patchDesc.replacements)
        ?: run {
            failed("core.protocol.invalid-value@1")
            return
        }
    val patch = try {
        SourcePatch.create(snapshot, replacements, emptyMap(), SourcePatchLimits.default)
    } catch (e: consema.document.SourcePatchException) {
        failed(e.code)
        return
    }
    var base = snapshot
    if (patchDesc.applyTo == "tampered") {
        val tampered = raw.copyOf()
        if (tampered.isNotEmpty()) {
            tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        }
        val tamperedSnapshot = try {
            SourceSnapshot.fromRaw(tampered, request, SourceLimits.default)
        } catch (e: consema.document.SourceException) {
            failed(e.code)
            return
        }
        base = tamperedSnapshot
    }
    val target = try {
        patch.apply(base, SourcePatchLimits.default)
    } catch (e: consema.document.SourcePatchException) {
        failed(e.code)
        return
    }
    facts.set("patch.status", "Applied")
    facts.set("patch.failure", "")
    facts.set("patch.output", escape(String(target.bytes(), Charsets.UTF_8)))
    facts.set("patch.replacement_count", replacements.size.toString())
}

/** Builds the replacements from the descriptor; the original bytes are
 * taken from the base snapshot (both sides do the same). */
private fun buildSourceReplacements(
    snapshot: SourceSnapshot,
    descriptions: List<PatchReplacementDesc>,
): List<SourceReplacement>? {
    val base = snapshot.bytes()
    val replacements = ArrayList<SourceReplacement>(descriptions.size)
    for (desc in descriptions) {
        if (desc.oldStart < 0 || desc.oldEnd < desc.oldStart || desc.oldEnd > base.size) {
            return null
        }
        val replacement = try {
            unhex(desc.replacementHex)
        } catch (e: Exception) {
            return null
        }
        val original = base.copyOfRange(desc.oldStart, desc.oldEnd)
        replacements.add(SourceReplacement.new(desc.oldStart, desc.oldEnd, original, replacement))
    }
    return replacements
}
