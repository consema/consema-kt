// The L4 root facade conversion: audited projection-to-materialization
// composition (Kotlin).
//
// Data authority (language-neutral sources first):
//   - https://github.com/consema/consema-rs/blob/main/consema/src/conversion.rs (the audited composition:
//     every convert_* function composes one format-owned projection and the
//     requested target materializer, retaining the intermediate portable
//     value, both provenance directions, and the two-stage report; the
//     record-consumption gate at conversion.rs; the materialization
//     dispatch table at conversion.rs; the failure codes
//     core.conversion.projection-failed@1 / materialization-failed@1 /
//     unauthorized-loss@1 at conversion.rs).
//   - RFC 0004 (materialization/conversion/structural edit v1; §7
//     completion algebra — failed conversions contain no target document
//     and no partial bytes).
//   - conformance/vectors/operations-v1.json and json-family-v2.json
//     (convert.* cases) pin the observable conversion surface.
//   - https://github.com/consema/consema/blob/main/docs/multi-language-implementation-plan.md §0.3/§1 (L1 root facade
//     "Document union/Registry/convert" — implemented in this repository;
//     two-stage projection → PortableValue → materialization per RFC 0004);
//     consema-go/go/conversion.go is a cross-reference only.
//
// Kotlin-idiomatic design: sealed completion algebra (ConversionResult is
// exactly one of Complete or Failed — never a partial document), immutable
// report data classes, and top-level convert_* functions named after the
// Rust facade functions. The composition never invents a cross-format
// convention: the baseline formats project plain portable values, while the
// record formats (XML, plist, HCL) project versioned internal records that
// only their owning format family's materializer consumes.

package consema

import consema.core.PortableValue
import consema.core.PvObject
import consema.core.PvString
import consema.document.MaterializationException
import consema.document.MaterializationFailureKind
import consema.document.MaterializationFidelity
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.ProfileId
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.Severity
import consema.ini.project
import consema.json.project
import consema.properties.project
import consema.toml.project
import consema.xml.project
import consema.yaml.projectValue

/** Whole-conversion semantic fidelity (conversion.rs). */
enum class ConversionFidelity {
    /** Both stages retain exact portable semantics. */
    Exact,

    /** At least one stage performs an authorized reversible transformation. */
    Transformed,

    /** Projection contains explicitly authorized irreversible loss. */
    Lossy,
}

/** Complete format-owned projection report retained without flattening
 * facts (conversion.rs). */
sealed class ConversionProjectionReport {
    /** INI projection report. */
    data class Ini(val report: consema.ini.ProjectionReport) : ConversionProjectionReport()

    /** Java Properties projection report. */
    data class Properties(val report: consema.properties.ProjectionReport) :
        ConversionProjectionReport()

    /** JSON projection report. */
    data class Json(val report: consema.json.ProjectionReport) : ConversionProjectionReport()

    /** TOML projection report. */
    data class Toml(val report: consema.toml.ProjectionReport) : ConversionProjectionReport()

    /** HCL body projection report. */
    data class Hcl(val report: consema.hcl.ProjectionReport) : ConversionProjectionReport()

    /** XML element-tree projection report. */
    data class Xml(val report: consema.xml.ProjectionReport) : ConversionProjectionReport()

    /** Property List value-tree projection report. */
    data class Plist(val report: consema.plist.ProjectionReport) : ConversionProjectionReport()

    /** YAML value-projection report. */
    data class Yaml(val report: consema.yaml.ProjectionReport) : ConversionProjectionReport()

    /** Number of ordered projection report events. */
    fun eventCount(): Int =
        when (this) {
            is Ini -> report.events().size
            is Properties -> report.events().size
            is Json -> report.events().size
            is Toml -> report.events().size
            is Hcl -> report.events().size
            is Xml -> report.events().size
            is Plist -> report.events().size
            is Yaml -> report.events().size
        }
}

/** Complete ordered report for both conversion stages
 * (conversion.rs). */
class ConversionReport(
    /** Projection-stage fidelity. */
    val projectionFidelity: ConversionFidelity,
    /** Complete format-owned projection report. */
    val projectionReport: ConversionProjectionReport,
    /** Materialization-stage fidelity. */
    val materializationFidelity: MaterializationFidelity,
    /** Worst fidelity across both stages. */
    val overallFidelity: ConversionFidelity,
    /** Exact source profile. */
    val sourceProfile: ProfileId,
    /** Exact target profile. */
    val targetProfile: ProfileId,
)

/** Complete conversion result with the target document and the two-stage
 * report (conversion.rs). */
class CompleteConversion(
    /** Newly materialized target document. */
    val document: Document,
    /** Exact intermediate portable value used between the two stages. */
    val projectedValue: PortableValue,
    /** Complete two-stage report. */
    val report: ConversionReport,
)

/** Conversion failure without a partial target document
 * (conversion.rs). */
sealed class ConversionFailure {
    /** Projection did not produce a complete portable value. */
    data class ProjectionFailed(
        /** Complete stage report produced before failure; null when the
         * family projection fails before any report exists (YAML value
         * projection). */
        val report: ConversionProjectionReport?,
        /** Ordered structured failure diagnostics. */
        val diagnostics: List<Diagnostic>,
    ) : ConversionFailure()

    /** Materialization did not produce target bytes or a target document. */
    data class MaterializationFailed(
        /** Stable materialization failure. */
        val failure: MaterializationException,
    ) : ConversionFailure()

    /** A lossy projection event lacked an explicit authorizing source
     * policy. */
    data object UnauthorizedLoss : ConversionFailure()

    /** The frozen registered code of the failure (conversion.rs). */
    val code: String
        get() = when (this) {
            is ProjectionFailed -> "core.conversion.projection-failed@1"
            is MaterializationFailed -> "core.conversion.materialization-failed@1"
            UnauthorizedLoss -> "core.conversion.unauthorized-loss@1"
        }
}

// ---------------------------------------------------------------------------
// Projection-failure diagnostics (conversion.rs: the convert_* functions
// pass the projection attempt's diagnostics through; wave-4 R41-family —
// the facade no longer drops the projection stage's diagnostics).
// ---------------------------------------------------------------------------

/** The registry the conversion facade externalizes diagnostics through
 * (the frozen v7 registry). */
private val conversionRegistry: ErrorCodeRegistry =
    ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)

/** The conversion facade's caller-stable source identity of one source
 * document (the same process-local snapshot convention the Document
 * facade uses). */
private fun snapshotSourceId(snapshot: consema.document.SnapshotIdentity): String =
    snapshot.asU64.toString()

/** Externalizes one family-owned projection diagnostic whose code lives
 * outside the frozen core registry (the xml, hcl, and plist family codes
 * deliberately stay out of the core registry, RFC 0012 §12 / RFC 0013
 * §12 / RFC 0014 §11): the protocol diagnostic carries the registered
 * conversion code with the family code in the arguments, so the failure
 * is carried — never dropped, never misregistered — and the conversion
 * facade cannot crash on an unregistered family code. */
private fun familyProjectionDiagnostic(
    familyCode: String,
    familyArguments: Map<String, String>,
    occurrence: ULong,
): Diagnostic = Diagnostic.of(
    code = "core.conversion.projection-failed@1",
    category = DiagnosticCategory.Conversion,
    severity = Severity.Error,
    primary = null,
    related = emptyList(),
    arguments = familyArguments + ("code" to familyCode),
    notes = emptyList(),
    fixes = emptyList(),
    occurrence = occurrence,
    registry = conversionRegistry,
)

/** Complete or explicitly failed conversion (conversion.rs). */
sealed class ConversionResult {
    /** Complete target document and all required audit artifacts. */
    data class Complete(val conversion: CompleteConversion) : ConversionResult()

    /** Failure without a target document or partial target bytes. */
    data class Failed(val failure: ConversionFailure) : ConversionResult()
}

/** Published record envelope ids produced by the record-format projections
 * (RFC 0012 §9, RFC 0013 §9, RFC 0014 §8.2; conversion.rs). */
private const val XML_ELEMENT_TREE_RECORD: String = "xml.element-tree@1"
private const val PLIST_VALUE_TREE_RECORD: String = "plist.value-tree@1"
private const val HCL_BODY_RECORD: String = "hcl.body@1"

/** One published Consema format record envelope, identified by its exact
 * versioned `record` member; any other object is ordinary content. */
private fun publishedRecord(value: PortableValue): String? {
    val obj = value as? PvObject ?: return null
    val record = obj.get("record") as? PvString ?: return null
    return when (record.value) {
        XML_ELEMENT_TREE_RECORD, PLIST_VALUE_TREE_RECORD, HCL_BODY_RECORD -> record.value
        else -> null
    }
}

/** Owning format family of one profile id; unknown profiles return null. */
private fun formatFamily(profileId: String): String? =
    when (profileId) {
        "json.strict", "jsonc.bounded", "json5.standard" -> "json"
        "toml.1.0" -> "toml"
        "yaml.1.2-core", "yaml.1.1-compat" -> "yaml"
        "ini.portable", "ini.windows", "ini.python-configparser" -> "ini"
        "java-properties.reader", "java-properties.latin1" -> "properties"
        "xml.1.0-safe" -> "xml"
        "plist.xml", "plist.binary" -> "plist"
        "hcl.native", "hcl.tfvars" -> "hcl"
        else -> null
    }

/** Record-consumption gate of the composition (conversion.rs): a
 * record-format source (XML, plist, HCL) projects its versioned internal
 * record envelope; the envelope is consumed only by the owning format
 * family's materializer. Baseline sources never project envelopes — a
 * `"record"` member in their content is content — and the explicit
 * non-record projection targets of the record formats publish plain values,
 * so both pass the gate untouched. */
private fun validateRecordConsumption(
    sourceProfile: ProfileId,
    value: PortableValue,
    request: MaterializationRequest,
): ConversionFailure? {
    val sourceFamily = formatFamily(sourceProfile.id) ?: return null
    if (sourceFamily != "xml" && sourceFamily != "plist" && sourceFamily != "hcl") {
        return null
    }
    val record = publishedRecord(value) ?: return null
    val owningFamily = when (record) {
        XML_ELEMENT_TREE_RECORD -> "xml"
        PLIST_VALUE_TREE_RECORD -> "plist"
        HCL_BODY_RECORD -> "hcl"
        else -> null
    }
    if (owningFamily == formatFamily(request.targetProfile.id)) {
        return null
    }
    return ConversionFailure.MaterializationFailed(
        MaterializationException(
            MaterializationFailureKind.INVALID_REQUEST,
            reason = "the projected value is the $record internal record; " +
                "only the $owningFamily family materializer consumes it",
        ),
    )
}

private fun completeConversion(
    sourceProfile: ProfileId,
    projectedValue: PortableValue,
    projectionFidelity: ConversionFidelity,
    projectionReport: ConversionProjectionReport,
    request: MaterializationRequest,
): ConversionResult {
    validateRecordConsumption(sourceProfile, projectedValue, request)?.let {
        return ConversionResult.Failed(it)
    }
    val materialized = materializeTarget(projectedValue, request)
    return when (materialized) {
        is MaterializationResult.Complete -> {
            val materialization = materialized.materialization
            val materializationOverall = when (materialization.fidelity) {
                MaterializationFidelity.Exact -> ConversionFidelity.Exact
                MaterializationFidelity.Transformed -> ConversionFidelity.Transformed
            }
            val overall = when {
                projectionFidelity == ConversionFidelity.Lossy ||
                    materializationOverall == ConversionFidelity.Lossy -> ConversionFidelity.Lossy
                projectionFidelity == ConversionFidelity.Transformed ||
                    materializationOverall == ConversionFidelity.Transformed ->
                    ConversionFidelity.Transformed
                else -> ConversionFidelity.Exact
            }
            ConversionResult.Complete(
                CompleteConversion(
                    document = materialization.document,
                    projectedValue = projectedValue,
                    report = ConversionReport(
                        projectionFidelity = projectionFidelity,
                        projectionReport = projectionReport,
                        materializationFidelity = materialization.fidelity,
                        overallFidelity = overall,
                        sourceProfile = sourceProfile,
                        targetProfile = request.targetProfile,
                    ),
                ),
            )
        }
        is MaterializationResult.Failed -> ConversionResult.Failed(
            ConversionFailure.MaterializationFailed(materialized.attempt.failure),
        )
    }
}

/** Materializes one portable value into the requested target profile through
 * the per-family materializers (conversion.rs). The target document
 * is rewrapped into the facade [Document] union. */
private fun materializeTarget(
    value: PortableValue,
    request: MaterializationRequest,
): MaterializationResult<Document> {
    val result: MaterializationResult<*>? = when (request.targetProfile.id) {
        "ini.portable", "ini.windows", "ini.python-configparser" ->
            consema.ini.materialize(value, request)
        "java-properties.reader", "java-properties.latin1" ->
            consema.properties.materialize(value, request)
        "json.strict", "jsonc.bounded", "json5.standard" ->
            consema.json.materialize(value, request)
        "toml.1.0" -> consema.toml.materialize(value, request)
        "yaml.1.2-core", "yaml.1.1-compat" -> consema.yaml.materializeValue(value, request)
        "xml.1.0-safe" -> consema.xml.materialize(value, request)
        "plist.xml", "plist.binary" -> consema.plist.materialize(value, request)
        else -> null
    }
    if (result == null) {
        // HCL materialization has its own completion algebra
        // (kotlin/src/main/kotlin/consema/hcl/Materialization.kt); the facade converts it to the
        // common algebra, preserving the frozen registered code.
        if (request.targetProfile.id == "hcl.native" || request.targetProfile.id == "hcl.tfvars") {
            return when (val hcl = consema.hcl.materialize(value, request)) {
                is consema.hcl.HclMaterializationResult.Complete ->
                    MaterializationResult.Complete(
                        consema.document.CompleteMaterialization(
                            document = Document.Hcl(hcl.materialization.document),
                            fidelity = hcl.materialization.fidelity,
                            report = hcl.materialization.report,
                            provenance = hcl.materialization.provenance,
                        ),
                    )
                is consema.hcl.HclMaterializationResult.Failed ->
                    MaterializationResult.Failed(
                        consema.document.FailedMaterializationAttempt(
                            hclFailureToCommon(hcl.failure),
                            consema.document.MaterializationReport.new(emptyList(), request.limits),
                            hcl.analyzedInputPaths,
                        ),
                    )
            }
        }
        return MaterializationResult.Failed(
            consema.document.FailedMaterializationAttempt(
                MaterializationException(MaterializationFailureKind.UNSUPPORTED_PROFILE),
                consema.document.MaterializationReport.new(emptyList(), request.limits),
                emptyList(),
            ),
        )
    }
    return when (result) {
        is MaterializationResult.Complete<*> -> {
            val document = when (val inner = result.materialization.document) {
                is consema.ini.IniDocument -> Document.Ini(inner)
                is consema.properties.Document -> Document.Properties(inner)
                is consema.json.Document -> Document.Json(inner)
                is consema.toml.TomlDocument -> Document.Toml(inner)
                is consema.yaml.Document -> Document.Yaml(inner)
                is consema.xml.Document -> Document.Xml(inner)
                is consema.plist.Document -> Document.Plist(inner)
                else -> error("facade materialization returned an unknown document type")
            }
            MaterializationResult.Complete(
                consema.document.CompleteMaterialization(
                    document = document,
                    fidelity = result.materialization.fidelity,
                    report = result.materialization.report,
                    provenance = result.materialization.provenance,
                ),
            )
        }
        is MaterializationResult.Failed -> MaterializationResult.Failed(result.attempt)
    }
}

/** Maps one HCL family materialization failure to the common failure kind
 * (the frozen registered codes are identical). */
private fun hclFailureToCommon(failure: consema.hcl.HclMaterializationFailure): MaterializationException {
    val kind = when (failure) {
        is consema.hcl.HclMaterializationFailure.Unrepresentable ->
            MaterializationFailureKind.UNREPRESENTABLE
        is consema.hcl.HclMaterializationFailure.ResourceLimit ->
            MaterializationFailureKind.RESOURCE_LIMIT
        consema.hcl.HclMaterializationFailure.InvalidRequest ->
            MaterializationFailureKind.INVALID_REQUEST
        consema.hcl.HclMaterializationFailure.UnsupportedProfile ->
            MaterializationFailureKind.UNSUPPORTED_PROFILE
        consema.hcl.HclMaterializationFailure.UnsupportedStyle ->
            MaterializationFailureKind.UNSUPPORTED_STYLE
        consema.hcl.HclMaterializationFailure.UnsupportedEncoding ->
            MaterializationFailureKind.UNSUPPORTED_ENCODING
        consema.hcl.HclMaterializationFailure.UnsupportedNewline ->
            MaterializationFailureKind.UNSUPPORTED_NEWLINE
        consema.hcl.HclMaterializationFailure.FormationFailed ->
            MaterializationFailureKind.FORMATION_FAILED
    }
    return MaterializationException(kind)
}

private fun jsonFidelity(fidelity: consema.json.Fidelity): ConversionFidelity =
    when (fidelity) {
        consema.json.Fidelity.Exact -> ConversionFidelity.Exact
        consema.json.Fidelity.Transformed -> ConversionFidelity.Transformed
        consema.json.Fidelity.Lossy -> ConversionFidelity.Lossy
    }

private fun iniFidelity(fidelity: consema.ini.Fidelity): ConversionFidelity =
    when (fidelity) {
        consema.ini.Fidelity.Exact -> ConversionFidelity.Exact
        consema.ini.Fidelity.Transformed -> ConversionFidelity.Transformed
        consema.ini.Fidelity.Lossy -> ConversionFidelity.Lossy
    }

private fun propertiesFidelity(fidelity: consema.properties.Fidelity): ConversionFidelity =
    when (fidelity) {
        consema.properties.Fidelity.Exact -> ConversionFidelity.Exact
        consema.properties.Fidelity.Transformed -> ConversionFidelity.Transformed
        consema.properties.Fidelity.Lossy -> ConversionFidelity.Lossy
    }

private fun tomlFidelity(fidelity: consema.toml.Fidelity): ConversionFidelity =
    when (fidelity) {
        consema.toml.Fidelity.Exact -> ConversionFidelity.Exact
        consema.toml.Fidelity.Transformed -> ConversionFidelity.Transformed
        consema.toml.Fidelity.Lossy -> ConversionFidelity.Lossy
    }

private fun yamlFidelity(fidelity: consema.yaml.Fidelity): ConversionFidelity =
    when (fidelity) {
        consema.yaml.Fidelity.Exact -> ConversionFidelity.Exact
        consema.yaml.Fidelity.Transformed -> ConversionFidelity.Transformed
        consema.yaml.Fidelity.Lossy -> ConversionFidelity.Lossy
    }

private fun xmlFidelity(fidelity: consema.xml.Fidelity): ConversionFidelity =
    when (fidelity) {
        consema.xml.Fidelity.Exact -> ConversionFidelity.Exact
        consema.xml.Fidelity.Transformed -> ConversionFidelity.Transformed
        consema.xml.Fidelity.Lossy -> ConversionFidelity.Lossy
    }

private fun plistFidelity(fidelity: consema.plist.Fidelity): ConversionFidelity =
    when (fidelity) {
        consema.plist.Fidelity.Exact -> ConversionFidelity.Exact
        consema.plist.Fidelity.Transformed -> ConversionFidelity.Transformed
        consema.plist.Fidelity.Lossy -> ConversionFidelity.Lossy
    }

private fun hclFidelity(fidelity: consema.hcl.Fidelity): ConversionFidelity =
    when (fidelity) {
        consema.hcl.Fidelity.Exact -> ConversionFidelity.Exact
        consema.hcl.Fidelity.Transformed -> ConversionFidelity.Transformed
        consema.hcl.Fidelity.Lossy -> ConversionFidelity.Lossy
    }

/** Converts one JSON document by composing its published projection and a
 * target materializer (conversion.rs). */
fun convertJson(
    source: consema.json.Document,
    projectionRequest: consema.json.ProjectionRequest,
    materializationRequest: MaterializationRequest,
): ConversionResult {
    return when (val result = source.project(projectionRequest)) {
        is consema.json.ProjectionResult.Complete -> {
            val projection = result.projection
            val unauthorized = projection.fidelity == consema.json.Fidelity.Lossy &&
                projection.report.events().any {
                    it.loss == consema.json.Fidelity.Lossy && it.policy == null
                }
            if (unauthorized) {
                return ConversionResult.Failed(ConversionFailure.UnauthorizedLoss)
            }
            completeConversion(
                source.profileId(),
                projection.value,
                jsonFidelity(projection.fidelity),
                ConversionProjectionReport.Json(projection.report),
                materializationRequest,
            )
        }
        is consema.json.ProjectionResult.Failed -> ConversionResult.Failed(
            ConversionFailure.ProjectionFailed(
                report = ConversionProjectionReport.Json(result.attempt.report),
                diagnostics = result.attempt.diagnostics,
            ),
        )
    }
}

/** Converts one INI document by composing its explicit projection and a
 * target materializer (conversion.rs). */
fun convertIni(
    source: consema.ini.IniDocument,
    projectionRequest: consema.ini.ProjectionRequest,
    materializationRequest: MaterializationRequest,
): ConversionResult =
    when (val result = source.project(projectionRequest)) {
        is consema.ini.ProjectionResult.Complete -> completeConversion(
            source.profileId(),
            result.projection.value,
            iniFidelity(result.projection.fidelity),
            ConversionProjectionReport.Ini(result.projection.report),
            materializationRequest,
        )
        is consema.ini.ProjectionResult.Failed -> ConversionResult.Failed(
            ConversionFailure.ProjectionFailed(
                report = ConversionProjectionReport.Ini(result.attempt.report),
                diagnostics = result.attempt.diagnostics,
            ),
        )
    }

/** Converts one Java Properties document through an explicit duplicate
 * policy (conversion.rs). */
fun convertProperties(
    source: consema.properties.Document,
    projectionRequest: consema.properties.ProjectionRequest,
    materializationRequest: MaterializationRequest,
): ConversionResult =
    when (val result = source.project(projectionRequest)) {
        is consema.properties.ProjectionResult.Complete -> completeConversion(
            source.profileId(),
            result.projection.value,
            propertiesFidelity(result.projection.fidelity),
            ConversionProjectionReport.Properties(result.projection.report),
            materializationRequest,
        )
        is consema.properties.ProjectionResult.Failed -> ConversionResult.Failed(
            ConversionFailure.ProjectionFailed(
                report = ConversionProjectionReport.Properties(result.attempt.report),
                diagnostics = result.attempt.diagnostics,
            ),
        )
    }

/** Converts one TOML document by composing its published projection and a
 * target materializer (conversion.rs). */
fun convertToml(
    source: consema.toml.TomlDocument,
    projectionRequest: consema.toml.ProjectionRequest,
    materializationRequest: MaterializationRequest,
): ConversionResult =
    when (val result = source.project(projectionRequest)) {
        is consema.toml.ProjectionResult.Complete -> completeConversion(
            source.profile(),
            result.projection.value,
            tomlFidelity(result.projection.fidelity),
            ConversionProjectionReport.Toml(result.projection.report),
            materializationRequest,
        )
        is consema.toml.ProjectionResult.Failed -> ConversionResult.Failed(
            ConversionFailure.ProjectionFailed(
                report = ConversionProjectionReport.Toml(result.attempt.report),
                diagnostics = result.attempt.diagnostics.map {
                    it.toProtocolDiagnostic(
                        snapshotSourceId(source.snapshotIdentity),
                        conversionRegistry,
                    )
                },
            ),
        )
    }

/** Converts one YAML stream through its explicit PortableValue projection
 * (conversion.rs). */
fun convertYaml(
    source: consema.yaml.Document,
    projectionRequest: consema.yaml.ValueProjectionRequest,
    materializationRequest: MaterializationRequest,
): ConversionResult =
    when (val result = source.projectValue(projectionRequest)) {
        is consema.yaml.ValueProjectionResult.Complete -> completeConversion(
            source.profileId(),
            result.projection.value,
            yamlFidelity(result.projection.fidelity),
            ConversionProjectionReport.Yaml(result.projection.report),
            materializationRequest,
        )
        is consema.yaml.ValueProjectionResult.Failed -> {
            // The YAML projection failure carries its frozen registered
            // code (yaml.projection.*@1): externalize it as one protocol
            // diagnostic instead of dropping the failure (the rs
            // YamlProjectionFailed{failure} counterpart carries the same
            // typed code).
            val code = consema.yaml.valueProjectionCode(result.failure)
            val descriptor = conversionRegistry.descriptor(code)
                ?: error("yaml projection code $code must be registered")
            ConversionResult.Failed(
                ConversionFailure.ProjectionFailed(
                    report = null,
                    diagnostics = listOf(
                        Diagnostic.of(
                            code = code,
                            category = descriptor.category,
                            severity = Severity.Error,
                            primary = null,
                            related = emptyList(),
                            arguments = emptyMap(),
                            notes = emptyList(),
                            fixes = emptyList(),
                            occurrence = 0.toULong(),
                            registry = conversionRegistry,
                        ),
                    ),
                ),
            )
        }
    }

/** Converts one XML document by composing its element-tree projection and a
 * target materializer (conversion.rs). Recovered documents never
 * project. */
fun convertXml(
    source: consema.xml.Document,
    projectionRequest: consema.xml.ProjectionRequest,
    materializationRequest: MaterializationRequest,
): ConversionResult =
    when (val result = source.project(projectionRequest)) {
        is consema.xml.ProjectionResult.Complete -> completeConversion(
            source.profileId(),
            result.projection.value,
            xmlFidelity(result.projection.fidelity),
            ConversionProjectionReport.Xml(result.projection.report),
            materializationRequest,
        )
        is consema.xml.ProjectionResult.Failed -> ConversionResult.Failed(
            ConversionFailure.ProjectionFailed(
                report = ConversionProjectionReport.Xml(result.attempt.report),
                diagnostics = result.attempt.diagnostics.map {
                    familyProjectionDiagnostic(
                        familyCode = it.code,
                        familyArguments = it.arguments,
                        occurrence = it.occurrence,
                    )
                },
            ),
        )
    }

/** Converts one Property List document by composing its value-tree
 * projection and a target materializer (conversion.rs). Recovered
 * documents never project. */
fun convertPlist(
    source: consema.plist.Document,
    projectionRequest: consema.plist.ProjectionRequest,
    materializationRequest: MaterializationRequest,
): ConversionResult =
    when (val result = consema.plist.project(source, projectionRequest)) {
        is consema.plist.ProjectionResult.Complete -> completeConversion(
            source.profileId(),
            result.projection.value,
            plistFidelity(result.projection.fidelity),
            ConversionProjectionReport.Plist(result.projection.report),
            materializationRequest,
        )
        is consema.plist.ProjectionResult.Failed -> ConversionResult.Failed(
            ConversionFailure.ProjectionFailed(
                report = ConversionProjectionReport.Plist(result.attempt.report),
                diagnostics = listOf(
                    familyProjectionDiagnostic(
                        familyCode = result.attempt.failure.code,
                        familyArguments = emptyMap(),
                        occurrence = 0.toULong(),
                    ),
                ),
            ),
        )
    }

/** Converts one HCL document by composing its body projection and a target
 * materializer (conversion.rs). Recovered documents never project.
 * The exact body target is the default `ExpressionPolicy.Default`: a derived
 * expression fails the conversion atomically; conversion never implicitly
 * enables the `ProjectExpression` strategy. */
fun convertHcl(
    source: consema.hcl.HclDocument,
    materializationRequest: MaterializationRequest,
    target: consema.hcl.ProjectionTarget = consema.hcl.ProjectionTarget.BodyV1,
    policy: consema.hcl.ExpressionPolicy = consema.hcl.ExpressionPolicy.Default,
    limits: consema.hcl.ProjectionLimits = consema.hcl.ProjectionLimits.default,
): ConversionResult =
    when (val result = consema.hcl.project(source, target, policy, limits)) {
        is consema.hcl.ProjectionResult.Complete -> completeConversion(
            source.profileId(),
            result.projection.value,
            hclFidelity(result.projection.fidelity),
            ConversionProjectionReport.Hcl(result.projection.report),
            materializationRequest,
        )
        is consema.hcl.ProjectionResult.Failed -> ConversionResult.Failed(
            ConversionFailure.ProjectionFailed(
                report = ConversionProjectionReport.Hcl(result.attempt.report),
                diagnostics = result.attempt.diagnostics.map {
                    familyProjectionDiagnostic(
                        familyCode = it.code,
                        familyArguments = it.arguments,
                        occurrence = it.occurrence.toULong(),
                    )
                },
            ),
        )
    }
