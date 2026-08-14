// The `consema.operations.conformance@1` suite runner
// (conformance/vectors/operations-v1.json).
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-conformance/src/operations_v1.rs (the
// per-case dispatch and every handler is transcribed from the Rust runner);
// the vector file itself drives every input and expectation
// (conformance/README.md rules 3-4). consema-go/go/conformance is a cross-reference
// only.
//
// Kotlin-idiomatic design: one handler per published case id, mirroring the
// Rust dispatch table; materialization/conversion/edit outcomes compare the
// frozen registered codes and the exact golden bytes; the protocol-v3 case
// round-trips the seven registered payload contracts through both canonical
// transports (all seven record types ship in the protocol package and
// dispatch through their typed decoders, Payload.kt); every case runs
// (no skips).

package consema.conformance

import consema.ConversionFailure
import consema.ConversionResult
import consema.convertJson
import consema.convertToml
import consema.core.Entry
import consema.core.EntryMappingBuilder
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.document.AssociationPlacement
import consema.document.EditPlanSourceId
import consema.document.MaterializationFidelity
import consema.document.MaterializationLimits
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.MappingPolicy
import consema.document.NewlinePolicy
import consema.document.ParseLimits
import consema.document.ProfileId
import consema.document.SourcePatch
import consema.document.SourcePatchException
import consema.document.SourcePatchLimits
import consema.document.SourceSnapshot
import consema.document.UntouchedByteProof
import consema.document.UntouchedByteProofException
import consema.json.Document
import consema.json.EditFailure
import consema.json.EditFailureException
import consema.json.EditTransactionBuilder as JsonEditBuilder
import consema.json.Fidelity as JsonFidelity
import consema.json.JsonObjectMember
import consema.json.JsonProfile
import consema.json.ProjectionEventKind
import consema.json.ProjectionRequest
import consema.json.ProjectionResult
import consema.json.ProjectionTarget as JsonProjectionTarget
import consema.json.RepresentationPolicy as JsonRepresentationPolicy
import consema.json.SemanticAvailability
import consema.json.commit
import consema.json.dryRun
import consema.json.materialize
import consema.json.parse
import consema.json.project
import consema.protocol.ContractId
import consema.protocol.ContractRegistry
import consema.protocol.ContractRegistryVersion
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.ProtocolLimits
import consema.protocol.ProtocolMessage
import consema.protocol.RegistryManifest
import consema.protocol.decodeJson
import consema.protocol.decodePvce
import consema.protocol.encodeJson
import consema.protocol.encodePvce
import consema.toml.EditTransactionBuilder as TomlEditBuilder
import consema.toml.RepresentationPolicy as TomlRepresentationPolicy
import consema.toml.TomlDocument
import consema.toml.TomlEditException
import consema.toml.TomlEntry
import consema.toml.TomlProfile
import consema.toml.commit as tomlCommit
import consema.toml.dryRun as tomlDryRun
import consema.toml.materialize as tomlMaterialize
import consema.toml.parse as tomlParse
import consema.toml.project as tomlProject
import consema.toml.ProjectionRequest as TomlProjectionRequest
import consema.toml.ProjectionResult as TomlProjectionResult
import consema.toml.ProjectionTarget as TomlProjectionTarget
import java.math.BigInteger

/** Runs the `consema.operations.conformance@1` suite. */
fun runOperationsV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runOperationsV1Case(case)
            passed.add(case.id)
        } catch (e: CaseFailureException) {
            failed.add(CaseFailure(case.id, e.message ?: "expected behavior did not match"))
        }
    }
    return SuiteReport(
        suite = data.suite,
        semanticModel = data.semanticModel,
        expectedCases = data.cases.size,
        passed = passed,
        skipped = skipped,
        failed = failed,
    )
}

private fun runOperationsV1Case(case: CaseData) {
    when (case.id) {
        "operations.v1.registry-v3" -> registryV3(case)
        "operations.v1.protocol-v3-dual-transport" -> protocolV3(case)
        "operations.v1.operation-registry" -> operationRegistry(case)
        "operations.v1.materialize-json-compact",
        "operations.v1.materialize-json-pretty-crlf",
        "operations.v1.materialize-json-entry-mapping-duplicates",
        -> materializeJsonSuccess(case)
        "operations.v1.materialize-json-nonstring-key-rejected" ->
            materializeJsonNonstringFailure(case)
        "operations.v1.materialize-json-float-rejected" -> materializeJsonFloatFailure(case)
        "operations.v1.materialize-json-output-limit" -> materializeJsonLimit(case)
        "operations.v1.materialize-toml-native" -> materializeTomlNative(case)
        "operations.v1.materialize-toml-explicit-mapping",
        "operations.v1.materialize-toml-implicit-mapping-rejected",
        -> materializeTomlMapping(case)
        "operations.v1.materialize-toml-null-rejected" ->
            materializeTomlFailure(case, PvNull)
        "operations.v1.materialize-toml-output-limit" -> materializeTomlLimit(case)
        "operations.v1.materialization-depth-limit" -> materializationDepthLimit(case)
        "operations.v1.convert-json-to-toml-exact" -> convertJsonToml(case)
        "operations.v1.convert-toml-to-json-exact" -> convertTomlJson(case)
        "operations.v1.convert-duplicate-json-to-toml-fails" -> convertDuplicateFailure(case)
        "operations.v1.convert-transformed-report" -> convertTransformed(case)
        "operations.v1.json-object-insert" -> jsonObjectInsert(case)
        "operations.v1.json-object-remove-duplicate" -> jsonObjectRemove(case)
        "operations.v1.json-array-remove" -> jsonArrayRemove(case)
        "operations.v1.json-conflict-atomic" -> jsonConflict(case)
        "operations.v1.json-dry-run-proof-patch" -> jsonDryRun(case)
        "operations.v1.json-structural-matrix" -> jsonStructuralMatrix(case)
        "operations.v1.json-conflict-matrix" -> jsonConflictMatrix(case)
        "operations.v1.toml-root-insert" -> tomlRootInsert(case)
        "operations.v1.toml-inline-rename" -> tomlInlineRename(case)
        "operations.v1.toml-array-remove" -> tomlArrayRemove(case)
        "operations.v1.toml-conflict-atomic" -> tomlConflict(case)
        "operations.v1.toml-dry-run-proof-patch" -> tomlDryRun(case)
        "operations.v1.toml-structural-matrix" -> tomlStructuralMatrix(case)
        "operations.v1.toml-conflict-matrix" -> tomlConflictMatrix(case)
        "operations.v1.materialization-security-matrix" -> materializationSecurityMatrix(case)
        "operations.v1.untouched-proof-tamper" -> untouchedProofTamper(case)
        else -> fail("runner does not recognize published operation case")
    }
}

// ---------------------------------------------------------------------------
// registry / protocol
// ---------------------------------------------------------------------------

private fun registryV3(case: CaseData) {
    val v1 = RegistryManifest.of(
        1,
        ContractRegistry.forVersion(ContractRegistryVersion.V1),
        ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V1),
    )
    val v2 = RegistryManifest.of(
        2,
        ContractRegistry.forVersion(ContractRegistryVersion.V2),
        ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V2),
    )
    val v3 = RegistryManifest.of(
        3,
        ContractRegistry.forVersion(ContractRegistryVersion.V3),
        ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V3),
    )
    ensure(
        v3.semanticModel.version == 3 &&
            v3.contracts.size.toLong() == (expectedLong(case, "contract_count") ?: fail("missing expected.contract_count")) &&
            v3.errorCodes.size.toLong() == (expectedLong(case, "error_code_count") ?: fail("missing expected.error_code_count")) &&
            v1.contracts.size.toLong() == (expectedLong(case, "v1_contract_count") ?: fail("missing expected.v1_contract_count")) &&
            v1.errorCodes.size.toLong() == (expectedLong(case, "v1_error_code_count") ?: fail("missing expected.v1_error_code_count")) &&
            v2.contracts.size.toLong() == (expectedLong(case, "v2_contract_count") ?: fail("missing expected.v2_contract_count")) &&
            v2.errorCodes.size.toLong() == (expectedLong(case, "v2_error_code_count") ?: fail("missing expected.v2_error_code_count")) &&
            registryManifestEqual(RegistryManifest.fromValue(v3.toValue()), v3),
    )
}

private fun protocolV3(case: CaseData) {
    val registry = ContractRegistry.forVersion(ContractRegistryVersion.V3)
    val limits = ProtocolLimits.default
    val ids = listOf(
        "core.conversion-report",
        "core.edit-plan",
        "core.format-operation-registry",
        "core.materialization-provenance-map",
        "core.materialization-report",
        "core.materialization-request",
        "core.materialization-result",
    )
    var jsonEqual = true
    var pvceEqual = true
    for (id in ids) {
        // The materialization-request contract is registered with its full
        // record decoder (operations_v1.rs builds a valid request);
        // the remaining v3 contracts validate at the envelope level until
        // their record types ship, so the schema-only payload suffices.
        val payload = if (id == "core.materialization-request") {
            consema.protocol.MaterializationRequestMessage.fromRequest(
                jsonRequest("json.canonical-compact", NewlinePolicy.None),
            ).toValue()
        } else {
            PvObject(listOf(Entry("schema", PvString("$id@1"))))
        }
        val message = ProtocolMessage.of(ContractId(id, 1), payload, registry)
        val viaJson = ProtocolMessage.fromValue(
            decodeJson(encodeJson(message.toValue(), limits), limits),
            registry,
        )
        jsonEqual = jsonEqual && viaJson.contract == message.contract &&
            consema.core.equal(viaJson.payload, message.payload)
        val viaPvce = ProtocolMessage.fromValue(
            decodePvce(encodePvce(message.toValue(), limits), limits),
            registry,
        )
        pvceEqual = pvceEqual && viaPvce.contract == message.contract &&
            consema.core.equal(viaPvce.payload, message.payload)
    }
    ensure(
        ids.size.toLong() == (expectedLong(case, "new_payload_count") ?: fail("missing expected.new_payload_count")) &&
            jsonEqual == (expectedBoolean(case, "json_equal") ?: fail("missing expected.json_equal")) &&
            pvceEqual == (expectedBoolean(case, "pvce_equal") ?: fail("missing expected.pvce_equal")),
    )
}

private fun operationRegistry(case: CaseData) {
    val json = consema.json.formatOperationRegistry(JsonProfile.StrictV1)
    val toml = consema.toml.formatOperationRegistry(TomlProfile.TOML_1_0_V1)
    val requiredJson = expectedString(case, "required_json") ?: fail("missing expected.required_json")
    val requiredToml = expectedString(case, "required_toml") ?: fail("missing expected.required_toml")
    ensure(
        json.size.toLong() == (expectedLong(case, "json_operation_count") ?: fail("missing expected.json_operation_count")) &&
            toml.size.toLong() == (expectedLong(case, "toml_operation_count") ?: fail("missing expected.toml_operation_count")) &&
            json.any { it.id.toString() == requiredJson } &&
            toml.any { it.id.toString() == requiredToml },
    )
}

// ---------------------------------------------------------------------------
// materialization
// ---------------------------------------------------------------------------

private fun materializeJsonSuccess(case: CaseData) {
    val document = jsonParse(
        (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8),
        JsonProfile.StrictV1,
    )
    val target = when (inputString(case, "projection")) {
        "BestExactCore", null -> JsonProjectionTarget.BestExactCoreV1
        else -> fail("unknown projection")
    }
    val projected = jsonProject(document, target)
    val style = inputString(case, "style") ?: "json.canonical-compact"
    val newline = parseNewline(inputString(case, "newline") ?: "None")
    when (val result = materialize(projected, jsonRequest(style, newline))) {
        is MaterializationResult.Complete -> ensure(
            result.materialization.document.render()
                .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)) &&
                result.materialization.fidelity.name == (expectedString(case, "fidelity") ?: fail("missing expected.fidelity")) &&
                result.materialization.provenance.entries().size.toLong() >=
                (expectedLong(case, "minimum_provenance_entries") ?: 0L),
        )
        is MaterializationResult.Failed -> fail("unexpected failure: ${result.attempt.failure.code}")
    }
}

private fun materializeJsonNonstringFailure(case: CaseData) {
    val key = BigInteger(inputString(case, "key_integer") ?: fail("missing input.key_integer"))
    val mapping = EntryMappingBuilder()
    mapping.push(PvInteger(key), PvBoolean(true))
    materializeJsonFailure(case, mapping.build(), MaterializationLimits.default)
}

private fun materializeJsonFloatFailure(case: CaseData) {
    val bits = hexBits(inputString(case, "binary64_bits") ?: fail("missing input.binary64_bits"))
    materializeJsonFailure(case, PvBinaryFloat64(bits), MaterializationLimits.default)
}

private fun materializeJsonLimit(case: CaseData) {
    val document = jsonParse(
        (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8),
        JsonProfile.StrictV1,
    )
    val value = jsonProject(document, JsonProjectionTarget.BestExactCoreV1)
    val maxOutputBytes = (caseInput(case, "max_output_bytes") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.max_output_bytes")
    materializeJsonFailure(case, value, limitsWith(maxOutputBytes = maxOutputBytes))
}

private fun materializeJsonFailure(case: CaseData, value: PortableValue, limits: MaterializationLimits) {
    val request = jsonRequest("json.canonical-compact", NewlinePolicy.None).withLimits(limits)
    val hasDocument = expectedBoolean(case, "has_document") ?: fail("missing expected.has_document")
    when (val result = materialize(value, request)) {
        is MaterializationResult.Complete -> ensure(hasDocument)
        is MaterializationResult.Failed -> ensure(
            result.attempt.failure.code == (expectedString(case, "code") ?: fail("missing expected.code")) &&
                !hasDocument,
        )
    }
}

private fun materializeTomlNative(case: CaseData) {
    val source = (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8)
    val document = tomlParse(source, TomlProfile.TOML_1_0_V1, ParseLimits.default)
    val value = tomlProjectValue(document)
    when (val result = tomlMaterialize(value, tomlRequest(MappingPolicy.RequireObject))) {
        is MaterializationResult.Complete -> {
            val reparsed = tomlParse(
                result.materialization.document.render(),
                TomlProfile.TOML_1_0_V1,
                ParseLimits.default,
            )
            ensure(
                result.materialization.fidelity.name == (expectedString(case, "fidelity") ?: fail("missing expected.fidelity")) &&
                    result.materialization.provenance.entries().size.toLong() >=
                    (expectedLong(case, "minimum_provenance_entries") ?: fail("missing expected.minimum_provenance_entries")) &&
                    consema.core.equal(tomlProjectValue(reparsed), value) ==
                    (expectedBoolean(case, "reprojects_equal") ?: fail("missing expected.reprojects_equal")),
            )
        }
        is MaterializationResult.Failed -> fail("unexpected failure: ${result.attempt.failure.code}")
    }
}

private fun materializeTomlMapping(case: CaseData) {
    val document = jsonParse(
        (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8),
        JsonProfile.StrictV1,
    )
    val value = jsonProject(document, JsonProjectionTarget.ProjectAsEntryMappingV1)
    val policy = when (inputString(case, "mapping_policy")) {
        "RequireObject" -> MappingPolicy.RequireObject
        "UniqueStringEntriesToObject" -> MappingPolicy.UniqueStringEntriesToObject
        else -> fail("unknown mapping policy")
    }
    when (val result = tomlMaterialize(value, tomlRequest(policy))) {
        is MaterializationResult.Complete -> ensure(
            result.materialization.document.render()
                .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)) &&
                result.materialization.fidelity.name == (expectedString(case, "fidelity") ?: fail("missing expected.fidelity")) &&
                result.materialization.report.events().any {
                    it.code == (expectedString(case, "event_code") ?: "")
                },
        )
        is MaterializationResult.Failed -> ensure(
            result.attempt.failure.code == (expectedString(case, "code") ?: fail("missing expected.code")) &&
                !(expectedBoolean(case, "has_document") ?: fail("missing expected.has_document")),
        )
    }
}

private fun materializeTomlFailure(case: CaseData, value: PortableValue) {
    val hasDocument = expectedBoolean(case, "has_document") ?: fail("missing expected.has_document")
    when (val result = tomlMaterialize(value, tomlRequest(MappingPolicy.RequireObject))) {
        is MaterializationResult.Complete -> ensure(hasDocument)
        is MaterializationResult.Failed -> ensure(
            result.attempt.failure.code == (expectedString(case, "code") ?: fail("missing expected.code")) &&
                !hasDocument,
        )
    }
}

private fun materializeTomlLimit(case: CaseData) {
    val source = (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8)
    val document = tomlParse(source, TomlProfile.TOML_1_0_V1, ParseLimits.default)
    val value = tomlProjectValue(document)
    val maxOutputBytes = (caseInput(case, "max_output_bytes") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.max_output_bytes")
    val request = tomlRequest(MappingPolicy.RequireObject)
        .withLimits(limitsWith(maxOutputBytes = maxOutputBytes))
    val hasDocument = expectedBoolean(case, "has_document") ?: fail("missing expected.has_document")
    when (val result = tomlMaterialize(value, request)) {
        is MaterializationResult.Complete -> ensure(hasDocument)
        is MaterializationResult.Failed -> ensure(
            result.attempt.failure.code == (expectedString(case, "code") ?: fail("missing expected.code")) &&
                !hasDocument,
        )
    }
}

private fun materializationDepthLimit(case: CaseData) {
    val document = jsonParse(
        (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8),
        JsonProfile.StrictV1,
    )
    val value = jsonProject(document, JsonProjectionTarget.BestExactCoreV1)
    val maxDepth = (caseInput(case, "max_depth") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.max_depth")
    val request = jsonRequest("json.canonical-compact", NewlinePolicy.None)
        .withLimits(limitsWith(maxDepth = maxDepth))
    val hasDocument = expectedBoolean(case, "has_document") ?: fail("missing expected.has_document")
    when (val result = materialize(value, request)) {
        is MaterializationResult.Complete -> ensure(hasDocument)
        is MaterializationResult.Failed -> ensure(
            result.attempt.failure.code == (expectedString(case, "code") ?: fail("missing expected.code")) &&
                !hasDocument,
        )
    }
}

// ---------------------------------------------------------------------------
// conversion
// ---------------------------------------------------------------------------

private fun convertJsonToml(case: CaseData) {
    val document = jsonParse(
        (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8),
        JsonProfile.StrictV1,
    )
    val projection = ProjectionRequest.builder(JsonProjectionTarget.BestExactCoreV1).build()
    when (val result = convertJson(document, projection, tomlRequest(MappingPolicy.UniqueStringEntriesToObject))) {
        is ConversionResult.Complete -> ensure(
            result.conversion.document.render()
                .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)) &&
                result.conversion.report.overallFidelity.name ==
                (expectedString(case, "overall_fidelity") ?: fail("missing expected.overall_fidelity")),
        )
        is ConversionResult.Failed -> fail("unexpected failure: ${result.failure.code}")
    }
}

private fun convertTomlJson(case: CaseData) {
    val document = tomlParse(
        (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8),
        TomlProfile.TOML_1_0_V1,
        ParseLimits.default,
    )
    when (val result = convertToml(
        document,
        TomlProjectionRequest.new(TomlProjectionTarget.BEST_EXACT_CORE_V1),
        jsonRequest("json.canonical-compact", NewlinePolicy.None),
    )) {
        is ConversionResult.Complete -> ensure(
            result.conversion.document.render()
                .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)) &&
                result.conversion.report.overallFidelity.name ==
                (expectedString(case, "overall_fidelity") ?: fail("missing expected.overall_fidelity")),
        )
        is ConversionResult.Failed -> fail("unexpected failure: ${result.failure.code}")
    }
}

private fun convertDuplicateFailure(case: CaseData) {
    val document = jsonParse(
        (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8),
        JsonProfile.StrictV1,
    )
    val projection = ProjectionRequest.builder(JsonProjectionTarget.BestExactCoreV1).build()
    val hasDocument = expectedBoolean(case, "has_document") ?: fail("missing expected.has_document")
    when (val result = convertJson(document, projection, tomlRequest(MappingPolicy.UniqueStringEntriesToObject))) {
        is ConversionResult.Complete -> ensure(hasDocument)
        is ConversionResult.Failed -> ensure(
            result.failure.code == (expectedString(case, "code") ?: fail("missing expected.code")) &&
                !hasDocument,
        )
    }
}

private fun convertTransformed(case: CaseData) {
    val document = jsonParse(
        (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8),
        JsonProfile.StrictV1,
    )
    val projection = ProjectionRequest.builder(JsonProjectionTarget.ProjectAsEntryMappingV1).build()
    val projected = document.project(projection) as? ProjectionResult.Complete
        ?: fail("projection failed")
    val projectionEvent = projected.projection.report.events().any {
        it.kind == ProjectionEventKind.StructureReencoded
    }
    val materialization = tomlMaterialize(
        projected.projection.value,
        tomlRequest(MappingPolicy.UniqueStringEntriesToObject),
    )
    when (materialization) {
        is MaterializationResult.Complete -> {
            val materializationEvent = materialization.materialization.report.events().any {
                it.code == (expectedString(case, "materialization_event") ?: "")
            }
            val overall = if (projected.projection.fidelity == JsonFidelity.Transformed ||
                materialization.materialization.fidelity == MaterializationFidelity.Transformed
            ) {
                "Transformed"
            } else {
                "Exact"
            }
            ensure(
                overall == (expectedString(case, "overall_fidelity") ?: fail("missing expected.overall_fidelity")) &&
                    projectionEvent &&
                    materializationEvent,
            )
        }
        is MaterializationResult.Failed -> fail("unexpected failure: ${materialization.attempt.failure.code}")
    }
}

// ---------------------------------------------------------------------------
// json edits
// ---------------------------------------------------------------------------

private fun jsonObjectInsert(case: CaseData) {
    val document = jsoncDocument(case)
    val members = jsonMembers(document)
    val anchor = members[(caseInput(case, "before_ordinal") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.before_ordinal")].nodeRef()
    val builder = JsonEditBuilder.new(document)
    builder.insertMember(
        document.root().nodeRef(),
        inputString(case, "name") ?: fail("missing input.name"),
        PvArray(listOf(PvBoolean(true))),
        AssociationPlacement.Before(anchor),
    )
    val commit = document.commit(builder.build())
    ensure(
        commit.document.render()
            .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)),
    )
}

private fun jsonObjectRemove(case: CaseData) {
    val document = jsoncDocument(case)
    val target = jsonMembers(document)[(caseInput(case, "target_ordinal") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.target_ordinal")].nodeRef()
    val builder = JsonEditBuilder.new(document)
    builder.removeMember(target)
    val commit = document.commit(builder.build())
    ensure(
        verifyCommit(
            case,
            document.source(),
            commit.document.source(),
            commit.sourcePatch,
            commit.untouchedProof,
            commit.document.render(),
        ),
    )
}

private fun jsonArrayRemove(case: CaseData) {
    val document = jsoncDocument(case)
    val elements = when (val available = document.root().arrayElements()) {
        is SemanticAvailability.Available -> available.value ?: fail("expected array")
        is SemanticAvailability.Unavailable -> fail("expected array")
    }
    val builder = JsonEditBuilder.new(document)
    builder.removeArrayElement(elements[(caseInput(case, "target_ordinal") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.target_ordinal")].nodeRef())
    val commit = document.commit(builder.build())
    ensure(
        commit.document.render()
            .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)),
    )
}

private fun jsonConflict(case: CaseData) {
    val document = strictDocument(case)
    val original = document.render()
    val target = jsonMembers(document)[(caseInput(case, "target_ordinal") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.target_ordinal")].nodeRef()
    val builder = JsonEditBuilder.new(document)
    builder.renameMember(target, "x").removeMember(target)
    val failure = try {
        document.commit(builder.build())
        null
    } catch (e: EditFailureException) {
        e.failure
    } ?: fail("conflict commit succeeded")
    ensure(
        jsonEditCode(failure) == (expectedString(case, "code") ?: fail("missing expected.code")) &&
            document.render().contentEquals(original) ==
            (expectedBoolean(case, "base_unchanged") ?: fail("missing expected.base_unchanged")),
    )
}

private fun jsonDryRun(case: CaseData) {
    val document = strictDocument(case)
    val name = inputString(case, "name") ?: fail("missing input.name")
    val value = inputString(case, "value") ?: fail("missing input.value")
    val builder = JsonEditBuilder.new(document)
    builder.insertMember(
        document.root().nodeRef(),
        name,
        PvString(value),
        AssociationPlacement.End,
    )
    val transaction = builder.build()
    val plan = document.dryRun(
        transaction,
        EditPlanSourceId.new(inputString(case, "source_id") ?: fail("missing input.source_id")),
    )
    val commit = document.commit(transaction)
    val safe = plan.operations().flatMap { it.arguments.values }.all { !it.contains("secret") }
    val redacted = plan.withAllReplacementsRedacted(true, true)
    val redactedDebug = redacted.replacements().all { it.redactReplacement && it.redactOriginal }
    val verified = verifyCommit(
        case,
        document.source(),
        commit.document.source(),
        commit.sourcePatch,
        commit.untouchedProof,
        commit.document.render(),
    )
    ensure(
        commit.document.render()
            .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)) &&
            (plan.replacements() == commit.sourcePatch.replacements()) ==
            (expectedBoolean(case, "same_replacements") ?: fail("missing expected.same_replacements")) &&
            (plan.targetDigest == commit.sourcePatch.targetDigest) ==
            (expectedBoolean(case, "same_target_digest") ?: fail("missing expected.same_target_digest")) &&
            safe == (expectedBoolean(case, "safe_summary") ?: fail("missing expected.safe_summary")) &&
            redactedDebug == (expectedBoolean(case, "redacted_debug") ?: fail("missing expected.redacted_debug")) &&
            verified,
    )
}

private fun jsonStructuralMatrix(case: CaseData) {
    val items = inputSequence(case, "cases") ?: fail("missing input.cases")
    var completed = 0
    for (item in items) {
        val operation = stringField(item, "operation") ?: fail("matrix item must be Object with operation")
        val document = jsonParse(
            (stringField(item, "source") ?: fail("matrix item lacks source")).toByteArray(Charsets.UTF_8),
            JsonProfile.StrictV1,
        )
        val builder = JsonEditBuilder.new(document)
        when (operation) {
            "insert-member-end" -> builder.insertMember(
                document.root().nodeRef(),
                stringField(item, "name") ?: fail("matrix item lacks name"),
                PvBoolean(true),
                AssociationPlacement.End,
            )
            "remove-member" -> builder.removeMember(
                jsonMembers(document)[(objectField(item, "target_ordinal") as? PvInteger)?.value?.toInt()
                    ?: fail("matrix item lacks target_ordinal")].nodeRef(),
            )
            "rename-member" -> builder.renameMember(
                jsonMembers(document)[(objectField(item, "target_ordinal") as? PvInteger)?.value?.toInt()
                    ?: fail("matrix item lacks target_ordinal")].nodeRef(),
                stringField(item, "name") ?: fail("matrix item lacks name"),
            )
            "insert-array-start" -> builder.insertArrayElement(
                document.root().nodeRef(),
                PvInteger(BigInteger("1")),
                AssociationPlacement.Start,
            )
            "insert-array-after" -> {
                val elements = when (val available = document.root().arrayElements()) {
                    is SemanticAvailability.Available -> available.value ?: fail("expected array")
                    is SemanticAvailability.Unavailable -> fail("expected array")
                }
                builder.insertArrayElement(
                    document.root().nodeRef(),
                    PvString("x"),
                    AssociationPlacement.After(
                        elements[(objectField(item, "anchor_ordinal") as? PvInteger)?.value?.toInt()
                            ?: fail("matrix item lacks anchor_ordinal")].nodeRef(),
                    ),
                )
            }
            else -> fail("unknown JSON matrix operation: $operation")
        }
        val commit = document.commit(builder.build())
        if (!commit.document.render().contentEquals(
                (stringField(item, "expected") ?: fail("matrix item lacks expected")).toByteArray(Charsets.UTF_8),
            )
        ) {
            fail("JSON matrix output mismatch for $operation")
        }
        completed += 1
    }
    ensure(completed.toLong() == (expectedLong(case, "completed") ?: fail("missing expected.completed")))
}

private fun jsonConflictMatrix(case: CaseData) {
    val items = inputSequence(case, "cases") ?: fail("missing input.cases")
    var failedAtomically = 0
    for (item in items) {
        val mode = stringField(item, "mode") ?: fail("matrix item must be Object with mode")
        val document = jsonParse(
            (stringField(item, "source") ?: fail("matrix item lacks source")).toByteArray(Charsets.UTF_8),
            JsonProfile.StrictV1,
        )
        val original = document.render()
        val failure = try {
            when (mode) {
                "wrong-snapshot" -> {
                    val foreign = jsonParse(
                        (stringField(item, "foreign") ?: fail("matrix item lacks foreign")).toByteArray(Charsets.UTF_8),
                        JsonProfile.StrictV1,
                    )
                    val builder = JsonEditBuilder.new(document)
                    builder.literalScalar(foreign.root().nodeRef(), "3".toByteArray(Charsets.US_ASCII))
                    document.commit(builder.build())
                    null
                }
                "same-boundary" -> {
                    val builder = JsonEditBuilder.new(document)
                    builder.insertMember(
                        document.root().nodeRef(),
                        "x",
                        PvBoolean(true),
                        AssociationPlacement.End,
                    ).insertMember(
                        document.root().nodeRef(),
                        "y",
                        PvBoolean(false),
                        AssociationPlacement.End,
                    )
                    document.commit(builder.build())
                    null
                }
                "removed-anchor" -> {
                    val member = jsonMembers(document)[0]
                    val builder = JsonEditBuilder.new(document)
                    builder.removeMember(member.nodeRef()).insertMember(
                        document.root().nodeRef(),
                        "x",
                        PvBoolean(true),
                        AssociationPlacement.Before(member.nodeRef()),
                    )
                    document.commit(builder.build())
                    null
                }
                "ancestor-descendant" -> {
                    val member = jsonMembers(document)[0]
                    val builder = JsonEditBuilder.new(document)
                    builder.semanticScalar(
                        member.valueNodeRef(),
                        PvInteger(BigInteger("3")),
                        JsonRepresentationPolicy.PreserveCompatible,
                    ).removeMember(member.nodeRef())
                    document.commit(builder.build())
                    null
                }
                else -> fail("unknown JSON conflict mode: $mode")
            }
        } catch (e: EditFailureException) {
            e.failure
        }
        val failureKind = failure ?: fail("JSON conflict mismatch for $mode")
        if (jsonEditCode(failureKind) != (stringField(item, "code") ?: fail("matrix item lacks code")) ||
            !document.render().contentEquals(original)
        ) {
            fail("JSON conflict mismatch for $mode")
        }
        failedAtomically += 1
    }
    ensure(failedAtomically.toLong() == (expectedLong(case, "failed_atomically") ?: fail("missing expected.failed_atomically")))
}

// ---------------------------------------------------------------------------
// toml edits
// ---------------------------------------------------------------------------

private fun tomlRootInsert(case: CaseData) {
    val document = tomlDocument(case)
    val builder = TomlEditBuilder.new(document)
    builder.insertEntry(
        document.root().nodeRef,
        inputString(case, "key") ?: fail("missing input.key"),
        PvBoolean(true),
        AssociationPlacement.End,
    )
    val commit = document.tomlCommit(builder.build())
    ensure(
        commit.document.render()
            .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)),
    )
}

private fun tomlInlineRename(case: CaseData) {
    val document = tomlDocument(case)
    val table = tomlRootEntry(document, inputString(case, "table") ?: fail("missing input.table")).item()
    val entries = table.tableEntries() ?: fail("expected inline table")
    val builder = TomlEditBuilder.new(document)
    builder.renameEntry(
        entries[(caseInput(case, "target_ordinal") as? PvInteger)?.value?.toInt()
            ?: fail("missing input.target_ordinal")].nodeRef,
        inputString(case, "key") ?: fail("missing input.key"),
    )
    val commit = document.tomlCommit(builder.build())
    ensure(
        commit.document.render()
            .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)),
    )
}

private fun tomlArrayRemove(case: CaseData) {
    val document = tomlDocument(case)
    val array = tomlRootEntry(document, inputString(case, "array") ?: fail("missing input.array")).item()
    val elements = array.arrayElements() ?: fail("expected array")
    val builder = TomlEditBuilder.new(document)
    builder.removeArrayElement(elements[(caseInput(case, "target_ordinal") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.target_ordinal")].nodeRef)
    val commit = document.tomlCommit(builder.build())
    ensure(
        commit.document.render()
            .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)),
    )
}

private fun tomlConflict(case: CaseData) {
    val document = tomlDocument(case)
    val original = document.render()
    val builder = TomlEditBuilder.new(document)
    builder.insertEntry(
        document.root().nodeRef,
        inputString(case, "key") ?: fail("missing input.key"),
        PvBoolean(true),
        AssociationPlacement.Start,
    )
    val failure = try {
        document.tomlCommit(builder.build())
        null
    } catch (e: TomlEditException) {
        e.kind
    } ?: fail("conflict commit succeeded")
    ensure(
        failure.code == (expectedString(case, "code") ?: fail("missing expected.code")) &&
            document.render().contentEquals(original) ==
            (expectedBoolean(case, "base_unchanged") ?: fail("missing expected.base_unchanged")),
    )
}

private fun tomlDryRun(case: CaseData) {
    val document = tomlDocument(case)
    val key = inputString(case, "key") ?: fail("missing input.key")
    val value = inputString(case, "value") ?: fail("missing input.value")
    val builder = TomlEditBuilder.new(document)
    builder.insertEntry(
        document.root().nodeRef,
        key,
        PvString(value),
        AssociationPlacement.End,
    )
    val transaction = builder.build()
    val plan = document.tomlDryRun(
        transaction,
        EditPlanSourceId.new(inputString(case, "source_id") ?: fail("missing input.source_id")),
    )
    val commit = document.tomlCommit(transaction)
    val safe = plan.operations().flatMap { it.arguments.values }.all { !it.contains("secret") }
    val redacted = plan.withAllReplacementsRedacted(true, true)
    val redactedDebug = redacted.replacements().all { it.redactReplacement && it.redactOriginal }
    val verified = verifyCommit(
        case,
        document.source(),
        commit.document.source(),
        commit.sourcePatch,
        commit.untouchedProof,
        commit.document.render(),
    )
    ensure(
        commit.document.render()
            .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)) &&
            (plan.replacements() == commit.sourcePatch.replacements()) ==
            (expectedBoolean(case, "same_replacements") ?: fail("missing expected.same_replacements")) &&
            (plan.targetDigest == commit.sourcePatch.targetDigest) ==
            (expectedBoolean(case, "same_target_digest") ?: fail("missing expected.same_target_digest")) &&
            safe == (expectedBoolean(case, "safe_summary") ?: fail("missing expected.safe_summary")) &&
            redactedDebug == (expectedBoolean(case, "redacted_debug") ?: fail("missing expected.redacted_debug")) &&
            verified,
    )
}

private fun tomlStructuralMatrix(case: CaseData) {
    val items = inputSequence(case, "cases") ?: fail("missing input.cases")
    var completed = 0
    for (item in items) {
        val operation = stringField(item, "operation") ?: fail("matrix item must be Object with operation")
        val document = tomlParse(
            (stringField(item, "source") ?: fail("matrix item lacks source")).toByteArray(Charsets.UTF_8),
            TomlProfile.TOML_1_0_V1,
            ParseLimits.default,
        )
        val builder = TomlEditBuilder.new(document)
        when (operation) {
            "insert-standard-table" -> {
                val table = tomlRootEntry(document, stringField(item, "table") ?: fail("matrix item lacks table")).item()
                builder.insertEntry(
                    table.nodeRef,
                    stringField(item, "key") ?: fail("matrix item lacks key"),
                    PvString("localhost"),
                    AssociationPlacement.End,
                )
            }
            "insert-inline" -> {
                val table = tomlRootEntry(document, stringField(item, "table") ?: fail("matrix item lacks table")).item()
                val entries = table.tableEntries() ?: fail("expected inline table")
                builder.insertEntry(
                    table.nodeRef,
                    stringField(item, "key") ?: fail("matrix item lacks key"),
                    PvArray(listOf(PvBoolean(true))),
                    AssociationPlacement.Before(
                        entries[(objectField(item, "before_ordinal") as? PvInteger)?.value?.toInt()
                            ?: fail("matrix item lacks before_ordinal")].nodeRef,
                    ),
                )
            }
            "remove-inline" -> {
                val table = tomlRootEntry(document, stringField(item, "table") ?: fail("matrix item lacks table")).item()
                val entries = table.tableEntries() ?: fail("expected inline table")
                builder.removeEntry(entries[(objectField(item, "target_ordinal") as? PvInteger)?.value?.toInt()
                    ?: fail("matrix item lacks target_ordinal")].nodeRef)
            }
            "insert-array-start" -> {
                val array = tomlRootEntry(document, stringField(item, "array") ?: fail("matrix item lacks array")).item()
                builder.insertArrayElement(
                    array.nodeRef,
                    PvInteger(BigInteger("1")),
                    AssociationPlacement.Start,
                )
            }
            else -> fail("unknown TOML matrix operation: $operation")
        }
        val commit = document.tomlCommit(builder.build())
        if (!commit.document.render().contentEquals(
                (stringField(item, "expected") ?: fail("matrix item lacks expected")).toByteArray(Charsets.UTF_8),
            )
        ) {
            fail("TOML matrix output mismatch for $operation")
        }
        completed += 1
    }
    ensure(completed.toLong() == (expectedLong(case, "completed") ?: fail("missing expected.completed")))
}

private fun tomlConflictMatrix(case: CaseData) {
    val items = inputSequence(case, "cases") ?: fail("missing input.cases")
    var failedAtomically = 0
    for (item in items) {
        val mode = stringField(item, "mode") ?: fail("matrix item must be Object with mode")
        val document = tomlParse(
            (stringField(item, "source") ?: fail("matrix item lacks source")).toByteArray(Charsets.UTF_8),
            TomlProfile.TOML_1_0_V1,
            ParseLimits.default,
        )
        val original = document.render()
        val failure = try {
            when (mode) {
                "duplicate-target" -> {
                    val entry = tomlRootEntry(document, "a")
                    val builder = TomlEditBuilder.new(document)
                    builder.renameEntry(entry.nodeRef, "x").removeEntry(entry.nodeRef)
                    document.tomlCommit(builder.build())
                    null
                }
                "removed-anchor" -> {
                    val entry = tomlRootEntry(document, "a")
                    val builder = TomlEditBuilder.new(document)
                    builder.removeEntry(entry.nodeRef).insertEntry(
                        document.root().nodeRef,
                        "x",
                        PvBoolean(true),
                        AssociationPlacement.Before(entry.nodeRef),
                    )
                    document.tomlCommit(builder.build())
                    null
                }
                "ancestor-descendant" -> {
                    val entry = tomlRootEntry(document, "a")
                    val builder = TomlEditBuilder.new(document)
                    builder.semanticScalar(
                        entry.itemNodeRef,
                        PvInteger(BigInteger("3")),
                        TomlRepresentationPolicy.PreserveCompatible,
                    ).removeEntry(entry.nodeRef)
                    document.tomlCommit(builder.build())
                    null
                }
                "unsupported-table-remove" -> {
                    val entry = tomlRootEntry(document, "service")
                    val builder = TomlEditBuilder.new(document)
                    builder.removeEntry(entry.nodeRef)
                    document.tomlCommit(builder.build())
                    null
                }
                else -> fail("unknown TOML conflict mode: $mode")
            }
        } catch (e: TomlEditException) {
            e.kind
        }
        val failureKind = failure ?: fail("TOML conflict mismatch for $mode")
        if (failureKind.code != (stringField(item, "code") ?: fail("matrix item lacks code")) ||
            !document.render().contentEquals(original)
        ) {
            fail("TOML conflict mismatch for $mode")
        }
        failedAtomically += 1
    }
    ensure(failedAtomically.toLong() == (expectedLong(case, "failed_atomically") ?: fail("missing expected.failed_atomically")))
}

// ---------------------------------------------------------------------------
// security matrix / untouched proof
// ---------------------------------------------------------------------------

private fun materializationSecurityMatrix(case: CaseData) {
    val items = inputSequence(case, "cases") ?: fail("missing input.cases")
    var completed = 0
    for (item in items) {
        val mode = stringField(item, "mode") ?: fail("matrix item must be Object with mode")
        val document = jsonParse(
            (stringField(item, "source") ?: fail("matrix item lacks source")).toByteArray(Charsets.UTF_8),
            JsonProfile.StrictV1,
        )
        val value = jsonProject(document, JsonProjectionTarget.BestExactCoreV1)
        when (mode) {
            "node-limit", "provenance-limit" -> {
                val limit = (objectField(item, "limit") as? PvInteger)?.value?.toInt()
                    ?: fail("matrix item lacks limit")
                val limits = if (mode == "node-limit") {
                    limitsWith(maxInputNodes = limit)
                } else {
                    limitsWith(maxProvenanceEntries = limit)
                }
                val request = jsonRequest("json.canonical-compact", NewlinePolicy.None)
                    .withLimits(limits)
                when (val result = materialize(value, request)) {
                    is MaterializationResult.Complete -> fail("security case $mode unexpectedly completed")
                    is MaterializationResult.Failed -> {
                        if (result.attempt.failure.code != (stringField(item, "code") ?: fail("matrix item lacks code"))) {
                            fail("security code mismatch for $mode")
                        }
                    }
                }
            }
            "escaping" -> {
                when (val result = materialize(value, jsonRequest("json.canonical-compact", NewlinePolicy.None))) {
                    is MaterializationResult.Complete -> {
                        if (!result.materialization.document.render().contentEquals(
                                (stringField(item, "expected") ?: fail("matrix item lacks expected")).toByteArray(Charsets.UTF_8),
                            )
                        ) {
                            fail("escaping output mismatch")
                        }
                    }
                    is MaterializationResult.Failed -> fail("escaping case unexpectedly failed")
                }
            }
            else -> fail("unknown security mode: $mode")
        }
        completed += 1
    }
    ensure(completed.toLong() == (expectedLong(case, "completed") ?: fail("missing expected.completed")))
}

private fun untouchedProofTamper(case: CaseData) {
    val document = strictDocument(case)
    val member = jsonMembers(document)[0]
    val builder = JsonEditBuilder.new(document)
    builder.semanticScalar(
        member.valueNodeRef(),
        PvInteger(BigInteger("2")),
        JsonRepresentationPolicy.PreserveCompatible,
    )
    val commit = document.commit(builder.build())
    val tampered = SourceSnapshot.fromUtf8(
        (inputString(case, "tampered_target") ?: fail("missing input.tampered_target")).toByteArray(Charsets.UTF_8),
    )
    val tamperDetected = try {
        commit.untouchedProof.verify(
            document.source(),
            tampered,
            commit.sourcePatch.replacements(),
        )
        false
    } catch (e: UntouchedByteProofException) {
        true
    }
    ensure(tamperDetected == (expectedBoolean(case, "tamper_detected") ?: fail("missing expected.tamper_detected")))
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun jsonParse(source: ByteArray, profile: JsonProfile): Document =
    try {
        parse(source, profile, ParseLimits.default)
    } catch (e: Exception) {
        fail("JSON parse failed: ${e.message}")
    }

private fun jsonProject(document: Document, target: JsonProjectionTarget): PortableValue =
    when (val result = document.project(ProjectionRequest.builder(target).build())) {
        is ProjectionResult.Complete -> result.projection.value
        is ProjectionResult.Failed -> fail("projection failed")
    }

private fun tomlProjectValue(document: TomlDocument): PortableValue =
    when (val result = document.tomlProject(TomlProjectionRequest.new(TomlProjectionTarget.BEST_EXACT_CORE_V1))) {
        is TomlProjectionResult.Complete -> result.projection.value
        is TomlProjectionResult.Failed -> fail("projection failed")
    }

private fun jsonRequest(style: String, newline: NewlinePolicy): MaterializationRequest =
    MaterializationRequest.new(ProfileId("json.strict", 1), MaterializationStyleId(style, 1))
        .withNewline(newline)

private fun tomlRequest(mappingPolicy: MappingPolicy): MaterializationRequest =
    MaterializationRequest.new(ProfileId("toml.1.0", 1), MaterializationStyleId("toml.canonical-document", 1))
        .withNewline(NewlinePolicy.Lf)
        .withMappingPolicy(mappingPolicy)

private fun parseNewline(name: String): NewlinePolicy =
    when (name) {
        "None" -> NewlinePolicy.None
        "Lf" -> NewlinePolicy.Lf
        "CrLf" -> NewlinePolicy.CrLf
        else -> fail("unknown newline: $name")
    }

/** Materialization limits with one overridden field. */
private fun limitsWith(
    maxInputNodes: Int = MaterializationLimits.default.maxInputNodes,
    maxOutputBytes: Int = MaterializationLimits.default.maxOutputBytes,
    maxDepth: Int = MaterializationLimits.default.maxDepth,
    maxReportEntries: Int = MaterializationLimits.default.maxReportEntries,
    maxProvenanceEntries: Int = MaterializationLimits.default.maxProvenanceEntries,
): MaterializationLimits =
    MaterializationLimits(maxInputNodes, maxOutputBytes, maxDepth, maxReportEntries, maxProvenanceEntries)

private fun strictDocument(case: CaseData): Document {
    val source = inputString(case, "source") ?: fail("missing input.source")
    return jsonParse(source.toByteArray(Charsets.UTF_8), JsonProfile.StrictV1)
}

private fun jsoncDocument(case: CaseData): Document {
    val source = inputString(case, "source") ?: fail("missing input.source")
    return jsonParse(source.toByteArray(Charsets.UTF_8), JsonProfile.JsoncBoundedV1)
}

private fun tomlDocument(case: CaseData): TomlDocument {
    val source = inputString(case, "source") ?: fail("missing input.source")
    return tomlParse(source.toByteArray(Charsets.UTF_8), TomlProfile.TOML_1_0_V1, ParseLimits.default)
}

private fun jsonMembers(document: Document): List<JsonObjectMember> =
    when (val members = document.root().objectMembers()) {
        is SemanticAvailability.Available -> members.value ?: fail("expected object")
        is SemanticAvailability.Unavailable -> fail("expected object")
    }

private fun tomlRootEntry(document: TomlDocument, name: String): TomlEntry =
    document.root().tableEntries()?.firstOrNull { it.name() == name }
        ?: fail("missing root entry: $name")

private fun verifyCommit(
    case: CaseData,
    base: SourceSnapshot,
    target: SourceSnapshot,
    patch: SourcePatch,
    proof: UntouchedByteProof,
    output: ByteArray,
): Boolean {
    val replay = try {
        patch.apply(base, SourcePatchLimits.default)
    } catch (e: SourcePatchException) {
        fail("patch apply: ${e.code}")
    }
    val patchReplays = replay.bytes().contentEquals(output)
    val proofVerifies = try {
        proof.verify(base, target, patch.replacements())
        true
    } catch (e: UntouchedByteProofException) {
        false
    }
    return output.contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)) &&
        patchReplays == (expectedBoolean(case, "patch_replays") ?: fail("missing expected.patch_replays")) &&
        proofVerifies == (expectedBoolean(case, "proof_verifies") ?: fail("missing expected.proof_verifies"))
}

/** The frozen json edit code mapping (consema-json edit.rs). */
private fun jsonEditCode(failure: EditFailure): String =
    when (failure) {
        EditFailure.RecoveredDocument, EditFailure.IncompleteTarget -> "core.edit.incomplete-target@1"
        EditFailure.WrongSnapshot -> "core.edit.wrong-snapshot@1"
        EditFailure.WrongRole -> "core.edit.wrong-role@1"
        EditFailure.SemanticUnavailable -> "core.edit.semantic-unavailable@1"
        is EditFailure.UnsupportedSemanticValue, is EditFailure.UnrepresentableValue ->
            "core.edit.unsupported-value@1"
        EditFailure.InvalidLiteral -> "core.edit.invalid-literal@1"
        EditFailure.RepresentationIncompatible -> "core.edit.representation-incompatible@1"
        EditFailure.ExactLiteralRequiresLiteralOperation ->
            "core.edit.exact-literal-requires-literal@1"
        EditFailure.ConflictingEdits,
        EditFailure.DuplicateTarget,
        EditFailure.OverlappingOwnership,
        EditFailure.AncestorDescendantConflict,
        EditFailure.PlacementAnchorRemoved,
        EditFailure.PlacementAnchorModified,
        -> "core.edit.conflicting-edits@1"
        EditFailure.TargetNotFound -> "core.edit.target-not-found@1"
        is EditFailure.ResourceLimit -> "core.edit.resource-limit@1"
        EditFailure.NewDocumentFormationFailed -> "core.edit.formation-failed@1"
    }

private fun hexBits(text: String): Long {
    val bytes = decodeHex(text) ?: fail("invalid hex bits")
    if (bytes.size != 8) fail("expected 8 hex bytes")
    var value = 0L
    for (byte in bytes) {
        value = (value shl 8) or (byte.toLong() and 0xff)
    }
    return value
}

private fun registryManifestEqual(left: RegistryManifest, right: RegistryManifest): Boolean =
    left.semanticModel == right.semanticModel &&
        left.contracts == right.contracts &&
        left.errorCodes == right.errorCodes

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}
