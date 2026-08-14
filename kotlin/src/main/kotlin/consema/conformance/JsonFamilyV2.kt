// The `consema.json-family.conformance@2` suite runner
// (conformance/vectors/json-family-v2.json).
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-conformance/src/json_family_v2.rs (the
// per-case dispatch and every handler is transcribed from the Rust runner);
// the vector file itself drives every input and expectation
// (conformance/README.md rules 3-4). The family tests under
// kotlin/src/test/kotlin/json/ transcribe the same vector behaviors.
// consema-go/go/conformance is a cross-reference only.
//
// Kotlin-idiomatic design: one handler per vector action family
// (parse/syntax-query/native-query/project/materialize/convert/move-member/
// edit-scalars/registry-v4/parse-limit), dispatched by case id like the Rust
// runner; failures surface as typed exceptions caught into CaseFailure
// records; every case runs (no skips).

package consema.conformance

import consema.ConversionFailure
import consema.ConversionResult
import consema.convertJson
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvString
import consema.document.AssociationPlacement
import consema.document.EditPlanSourceId
import consema.document.FormationStatus
import consema.document.MaterializationFailureKind
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ParseLimits
import consema.document.ProfileId
import consema.document.UntouchedByteProofException
import consema.json.Document
import consema.json.EditFailure
import consema.json.EditFailureException
import consema.json.EditTransactionBuilder
import consema.json.JsonObjectMember
import consema.json.JsonProfile
import consema.json.JsonValue
import consema.json.JsonValueKind
import consema.json.ProjectionRequest
import consema.json.ProjectionResult
import consema.json.ProjectionTarget
import consema.json.RepresentationPolicy
import consema.json.SemanticAvailability
import consema.json.commit
import consema.json.dryRun
import consema.json.executeJsonQuery
import consema.json.executeJsonSyntaxQuery
import consema.json.materialize
import consema.json.parse
import consema.json.project
import consema.protocol.ContractRegistry
import consema.protocol.ContractRegistryVersion
import consema.protocol.Domains
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind
import consema.protocol.RegistryManifest
import java.math.BigInteger

/** Runs the `consema.json-family.conformance@2` suite. */
fun runJsonFamilyV2(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runJsonFamilyV2Case(case)
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

private fun runJsonFamilyV2Case(case: CaseData) {
    when (case.id) {
        "json5.parse.full-surface",
        "json5.parse.identifiers",
        "json5.parse.string-extensions",
        "json5.parse.extended-whitespace-comments",
        "json5.parse.unescaped-separator-warning",
        "json5.reject.invalid-escaped-identifier",
        "json5.reject.leading-zero-decimal",
        "json5.reject.empty-hex",
        "json5.reject.decimal-string-escape",
        "json5.reject.isolated-surrogate",
        "json5.reject.unterminated-comment",
        "json.strict.reject-json5-surface",
        "jsonc.complete-shared-surface",
        "json5.complete-jsonc-surface",
        "json5.number.positive-infinity",
        "json5.number.negative-nan",
        "json5.number.huge-hex-exact",
        "json5.number.leading-trailing-exact",
        -> parseCase(case)
        "json5.query.syntax-v2-identifier" -> syntaxQueryCase(case)
        "json5.query.native-v2-binary" -> nativeQueryCase(case)
        "json5.projection.duplicates-nonfinite",
        "json5.projection.old-target-rejected",
        -> projectionCase(case)
        "json5.materialize.canonical-specials",
        "json5.materialize.reject-finite-binary",
        "json5.materialize.reject-profile-style-mismatch",
        -> materializationCase(case)
        "json5.convert.finite-to-strict",
        "json5.convert.nonfinite-to-strict-fails",
        "json5.convert.strict-to-json5",
        -> conversionCase(case)
        "json5.edit.move-member",
        "json5.edit.move-cross-object-rejected",
        -> moveMemberCase(case)
        "json5.edit.preserve-scalars" -> editScalarsCase(case)
        "protocol.registry.semantic-model-v4" -> registryV4Case(case)
        "json5.security.depth-limit" -> parseLimitCase(case)
        else -> fail("runner does not recognize published case")
    }
}

// ---------------------------------------------------------------------------
// parse
// ---------------------------------------------------------------------------

private fun parseCase(case: CaseData) {
    val source = inputString(case, "source") ?: fail("missing input.source")
    val document = parseDocument(source, profile(inputString(case, "profile") ?: fail("missing input.profile")))
    ensure(document.render().contentEquals(source.toByteArray(Charsets.UTF_8)))
    expectedString(case, "formation")?.let { expected ->
        ensure(formationStatusName(document.formationStatus()) == expected)
    }
    expectedSequence(case, "diagnostic_contains")?.forEach { item ->
        val code = (item as? PvString)?.value ?: fail("diagnostic_contains must contain strings")
        ensure(document.diagnostics().any { it.code == code })
    }
    expectedSequence(case, "syntax_contains")?.forEach { item ->
        val kind = (item as? PvString)?.value ?: fail("syntax_contains must contain strings")
        ensure(document.losslessSyntaxKinds().any { it.asStr() == kind })
    }
    val root = document.root()
    expectedString(case, "root_kind")?.let { expected ->
        ensure(valueKindName(root) == expected)
    }
    expectedString(case, "root_bits")?.let { expected ->
        val bits = when (val available = root.asBinaryFloat64()) {
            is SemanticAvailability.Available -> available.value
                ?: fail("root is not BinaryFloat64")
            is SemanticAvailability.Unavailable -> fail("root semantics unavailable")
        }
        ensure("%016x".format(bits) == expected)
    }
    expectedString(case, "root_integer")?.let { expected ->
        val integer = when (val available = root.asInteger()) {
            is SemanticAvailability.Available -> available.value
                ?: fail("root is not Integer")
            is SemanticAvailability.Unavailable -> fail("root semantics unavailable")
        }
        ensure(integer.toString() == expected)
    }
    if (caseExpected(case, "member_names") != null || caseExpected(case, "member_kinds") != null) {
        val members = objectMembers(root)
        caseExpected(case, "member_names")?.let {
            val actual = members.map { member ->
                when (val name = member.name()) {
                    is SemanticAvailability.Available -> name.value
                    is SemanticAvailability.Unavailable -> fail("member name unavailable")
                }
            }
            ensure(actual == expectedStrings(case, "member_names"))
        }
        caseExpected(case, "member_kinds")?.let {
            ensure(members.map { valueKindName(it.value()) } == expectedStrings(case, "member_kinds"))
        }
    }
    if (caseExpected(case, "element_kinds") != null ||
        caseExpected(case, "element_strings") != null ||
        caseExpected(case, "element_decimals") != null
    ) {
        val elements = arrayValues(root)
        caseExpected(case, "element_kinds")?.let {
            ensure(elements.map { valueKindName(it) } == expectedStrings(case, "element_kinds"))
        }
        caseExpected(case, "element_strings")?.let {
            val actual = elements.map { value ->
                when (val string = value.asString()) {
                    is SemanticAvailability.Available -> string.value
                        ?: fail("element is not String")
                    is SemanticAvailability.Unavailable -> fail("element semantics unavailable")
                }
            }
            ensure(actual == expectedStrings(case, "element_strings"))
        }
        caseExpected(case, "element_decimals")?.let {
            val actual = elements.map { value ->
                val decimal = when (val available = value.asDecimal()) {
                    is SemanticAvailability.Available -> available.value
                        ?: fail("element is not Decimal")
                    is SemanticAvailability.Unavailable -> fail("element semantics unavailable")
                }
                listOf(decimal.coefficient.toString(), decimal.exponent.toString())
            }
            ensure(actual == expectedDecimalPairs(case))
        }
    }
}

// ---------------------------------------------------------------------------
// syntax-query / native-query
// ---------------------------------------------------------------------------

private fun syntaxQueryCase(case: CaseData) {
    val document = json5Document(inputString(case, "source") ?: fail("missing input.source"))
    val kind = inputString(case, "kind") ?: fail("missing input.kind")
    val definition = QueryDefinition(Domains.jsonLosslessSyntaxV2())
        .withExpression(
            QueryExpression(ExpressionKind.Input)
                .then(OperatorCall("json.syntax-kind-is", 1).withArgument("kind", PvString(kind))),
        )
    val executable = try {
        definition.validate().let { ExecutableQuery.bind(it, queryCapabilities()) }
    } catch (e: QueryFailureException) {
        fail("definition: ${e.kind.code}")
    }
    val matches = try {
        executeJsonSyntaxQuery(executable, document)
    } catch (e: QueryFailureException) {
        fail("query: ${e.kind.code}")
    }
    val bytes = document.render()
    val actual = matches.map { item ->
        String(bytes, item.span.startByte, item.span.endByte - item.span.startByte, Charsets.UTF_8)
    }
    ensure(actual == expectedStrings(case, "texts"))
    if (expectedBoolean(case, "v1_rejected") == true) {
        val v1 = try {
            QueryDefinition(Domains.jsonLosslessSyntaxV1())
                .validate()
                .let { ExecutableQuery.bind(it, queryCapabilities()) }
        } catch (e: QueryFailureException) {
            fail("v1 definition: ${e.kind.code}")
        }
        val rejected = try {
            executeJsonSyntaxQuery(v1, document)
            false
        } catch (e: QueryFailureException) {
            e.kind == QueryFailureKind.DOMAIN_MISMATCH
        }
        ensure(rejected)
    }
}

private fun nativeQueryCase(case: CaseData) {
    val document = json5Document(inputString(case, "source") ?: fail("missing input.source"))
    val executable = try {
        QueryDefinition(Domains.jsonNativeV2())
            .validate()
            .let { ExecutableQuery.bind(it, queryCapabilities()) }
    } catch (e: QueryFailureException) {
        fail("definition: ${e.kind.code}")
    }
    val matches = try {
        executeJsonQuery(executable, document)
    } catch (e: QueryFailureException) {
        fail("query: ${e.kind.code}")
    }
    val expectedKind = expectedString(case, "kind") ?: fail("missing expected.kind")
    val match = matches.singleOrNull() as? consema.json.JsonMatch.Value
        ?: fail("native v2 root result is not one available value")
    val kind = match.kind ?: fail("native v2 root kind is unavailable")
    ensure(kind.name == expectedKind)
    if (expectedBoolean(case, "v1_rejected") == true) {
        val v1 = try {
            QueryDefinition(Domains.jsonNativeV1())
                .validate()
                .let { ExecutableQuery.bind(it, queryCapabilities()) }
        } catch (e: QueryFailureException) {
            fail("v1 definition: ${e.kind.code}")
        }
        val rejected = try {
            executeJsonQuery(v1, document)
            false
        } catch (e: QueryFailureException) {
            e.kind == QueryFailureKind.DOMAIN_MISMATCH
        }
        ensure(rejected)
    }
}

// ---------------------------------------------------------------------------
// project / materialize / convert
// ---------------------------------------------------------------------------

private fun projectionCase(case: CaseData) {
    val document = json5Document(inputString(case, "source") ?: fail("missing input.source"))
    val target = when (inputString(case, "target")) {
        "json5-best-exact" -> ProjectionTarget.Json5BestExactCoreV1
        "json-best-exact" -> ProjectionTarget.BestExactCoreV1
        else -> fail("unknown projection target")
    }
    val request = ProjectionRequest.builder(target).build()
    when (val result = document.project(request)) {
        is ProjectionResult.Complete -> {
            val complete = expectedBoolean(case, "complete") ?: fail("missing expected.complete")
            ensure(complete)
            ensure(result.projection.value.kind.name == expectedString(case, "kind"))
            caseExpected(case, "binary_bits")?.let {
                val entries = result.projection.value as? consema.core.PvEntryMapping
                    ?: fail("projection is not EntryMapping")
                val actual = entries.entries().map { entry ->
                    val bits = (entry.value as? PvBinaryFloat64)?.bits
                        ?: fail("entry is not BinaryFloat64")
                    "%016x".format(bits)
                }
                ensure(actual == expectedStrings(case, "binary_bits"))
            }
        }
        is ProjectionResult.Failed -> {
            val complete = expectedBoolean(case, "complete") ?: fail("missing expected.complete")
            ensure(!complete)
            val code = expectedString(case, "code") ?: fail("missing expected.code")
            ensure(result.attempt.diagnostics.any { it.code == code })
        }
    }
}

private fun materializationCase(case: CaseData) {
    val values = inputSequence(case, "values") ?: fail("missing input.values")
    val input = PvArray(values.map { materializationValue(it) })
    val request = materializationRequest(
        inputString(case, "profile") ?: fail("missing input.profile"),
        inputString(case, "style") ?: fail("missing input.style"),
    )
    when (val result = materialize(input, request)) {
        is MaterializationResult.Complete -> ensure(
            result.materialization.document.render()
                .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)),
        )
        is MaterializationResult.Failed -> ensure(
            materializationFailureName(result.attempt.failure.kind) ==
                (expectedString(case, "failure") ?: fail("missing expected.failure")),
        )
    }
}

private fun conversionCase(case: CaseData) {
    val sourceProfile = profile(inputString(case, "source_profile") ?: fail("missing input.source_profile"))
    val document = parseDocument(inputString(case, "source") ?: fail("missing input.source"), sourceProfile)
    val target = if (sourceProfile == JsonProfile.Json5StandardV1) {
        ProjectionTarget.Json5BestExactCoreV1
    } else {
        ProjectionTarget.BestExactCoreV1
    }
    val projection = ProjectionRequest.builder(target).build()
    val materialization = materializationRequest(
        inputString(case, "target_profile") ?: fail("missing input.target_profile"),
        inputString(case, "style") ?: fail("missing input.style"),
    )
    when (val result = convertJson(document, projection, materialization)) {
        is ConversionResult.Complete -> ensure(
            result.conversion.document.render()
                .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)) &&
                result.conversion.report.overallFidelity.name ==
                (expectedString(case, "fidelity") ?: fail("missing expected.fidelity")),
        )
        is ConversionResult.Failed -> {
            val actual = when (val failure = result.failure) {
                is ConversionFailure.MaterializationFailed -> materializationFailureName(failure.failure.kind)
                is ConversionFailure.ProjectionFailed -> "ProjectionFailed"
                ConversionFailure.UnauthorizedLoss -> "UnauthorizedLoss"
            }
            ensure(actual == (expectedString(case, "failure") ?: fail("missing expected.failure")))
        }
    }
}

// ---------------------------------------------------------------------------
// edit
// ---------------------------------------------------------------------------

private fun moveMemberCase(case: CaseData) {
    val document = parseDocument(
        inputString(case, "source") ?: fail("missing input.source"),
        profile(inputString(case, "profile") ?: fail("missing input.profile")),
    )
    val target = resolveMember(document, inputOrdinals(case, "target_path"))
    val placement = when (inputString(case, "placement")) {
        "start" -> AssociationPlacement.Start
        "end" -> AssociationPlacement.End
        "before", "after" -> {
            val anchor = resolveMember(document, inputOrdinals(case, "anchor_path"))
            if (inputString(case, "placement") == "before") {
                AssociationPlacement.Before(anchor.nodeRef())
            } else {
                AssociationPlacement.After(anchor.nodeRef())
            }
        }
        else -> fail("unknown placement")
    }
    val builder = EditTransactionBuilder.new(document)
    builder.moveMember(target.nodeRef(), placement)
    val transaction = builder.build()
    val commit = try {
        document.commit(transaction)
    } catch (e: EditFailureException) {
        ensure(e.failure.name == (expectedString(case, "failure") ?: fail("missing expected.failure")))
        return
    }
    ensure(
        commit.document.render()
            .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)),
    )
    val plan = document.dryRun(transaction, EditPlanSourceId.new("conformance.json5"))
    val patchEqual = plan.replacements() == commit.sourcePatch.replacements() &&
        plan.targetDigest == commit.sourcePatch.targetDigest
    ensure(patchEqual == (expectedBoolean(case, "patch_equal") ?: fail("missing expected.patch_equal")))
    val proofValid = try {
        commit.untouchedProof.verify(
            document.source(),
            commit.document.source(),
            commit.sourcePatch.replacements(),
        )
        true
    } catch (e: UntouchedByteProofException) {
        false
    }
    ensure(proofValid == (expectedBoolean(case, "proof_valid") ?: fail("missing expected.proof_valid")))
}

private fun editScalarsCase(case: CaseData) {
    val document = json5Document(inputString(case, "source") ?: fail("missing input.source"))
    val members = objectMembers(document.root())
    val replacements = inputSequence(case, "replacements") ?: fail("missing input.replacements")
    val builder = EditTransactionBuilder.new(document)
    for (replacement in replacements) {
        val ordinal = (objectField(replacement, "ordinal") as? PvInteger)?.value?.toInt()
            ?: fail("replacement ordinal is invalid")
        val value = scalarReplacement(replacement)
        val member = members.getOrNull(ordinal) ?: fail("replacement ordinal is out of range")
        builder.semanticScalar(member.valueNodeRef(), value, RepresentationPolicy.PreserveCompatible)
    }
    val commit = document.commit(builder.build())
    ensure(
        commit.document.render()
            .contentEquals((expectedString(case, "output") ?: fail("missing expected.output")).toByteArray(Charsets.UTF_8)),
    )
}

// ---------------------------------------------------------------------------
// registry-v4 / parse-limit
// ---------------------------------------------------------------------------

private fun registryV4Case(case: CaseData) {
    val contracts = ContractRegistry.forVersion(ContractRegistryVersion.V4)
    val v4 = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V4)
    val v3 = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V3)
    val manifest = RegistryManifest.of(4, contracts, v4)
    val restored = RegistryManifest.fromValue(manifest.toValue())
    val newCode = expectedString(case, "new_code") ?: fail("missing expected.new_code")
    ensure(
        contracts.contracts().size.toLong() == (expectedLong(case, "contract_count") ?: fail("missing expected.contract_count")) &&
            v4.codes().size.toLong() == (expectedLong(case, "error_code_count") ?: fail("missing expected.error_code_count")) &&
            v3.codes().size.toLong() == (expectedLong(case, "v3_error_code_count") ?: fail("missing expected.v3_error_code_count")) &&
            v4.contains(newCode) &&
            !v3.contains(newCode) &&
            manifest.semanticModel.version == 4 &&
            !manifest.isCurrent() &&
            registryManifestEqual(restored, manifest),
    )
}

private fun parseLimitCase(case: CaseData) {
    val maxDepth = (caseInput(case, "max_depth") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.max_depth")
    val limits = ParseLimits(
        maxSourceBytes = ParseLimits.default.maxSourceBytes,
        maxNestingDepth = maxDepth,
        maxTokenCount = ParseLimits.default.maxTokenCount,
        maxNodeCount = ParseLimits.default.maxNodeCount,
        maxDiagnostics = ParseLimits.default.maxDiagnostics,
    )
    val fatal = try {
        parse(
            (inputString(case, "source") ?: fail("missing input.source")).toByteArray(Charsets.UTF_8),
            JsonProfile.Json5StandardV1,
            limits,
        )
        false
    } catch (e: Exception) {
        true
    }
    ensure(fatal == (expectedBoolean(case, "fatal") ?: fail("missing expected.fatal")))
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun profile(name: String): JsonProfile =
    when (name) {
        "json.strict@1" -> JsonProfile.StrictV1
        "jsonc.bounded@1" -> JsonProfile.JsoncBoundedV1
        "json5.standard@1" -> JsonProfile.Json5StandardV1
        else -> fail("unknown JSON profile: $name")
    }

private fun parseDocument(source: String, profile: JsonProfile): Document =
    try {
        parse(source.toByteArray(Charsets.UTF_8), profile, ParseLimits.default)
    } catch (e: Exception) {
        fail("parse failed: ${e.message}")
    }

private fun json5Document(source: String): Document =
    parseDocument(source, JsonProfile.Json5StandardV1)

private fun formationStatusName(status: FormationStatus): String =
    when (status) {
        FormationStatus.Complete -> "Complete"
        FormationStatus.Recovered -> "Recovered"
    }

private fun valueKindName(value: JsonValue): String =
    when (val kind = value.kind()) {
        is SemanticAvailability.Available -> kind.value.name
        is SemanticAvailability.Unavailable -> fail("native semantics unavailable")
    }

private fun objectMembers(value: JsonValue): List<JsonObjectMember> =
    when (val members = value.objectMembers()) {
        is SemanticAvailability.Available -> members.value ?: fail("value is not Object")
        is SemanticAvailability.Unavailable -> fail("object semantics unavailable")
    }

private fun arrayValues(value: JsonValue): List<JsonValue> =
    when (val elements = value.arrayElements()) {
        is SemanticAvailability.Available -> elements.value?.map { it.value() }
            ?: fail("value is not Array")
        is SemanticAvailability.Unavailable -> fail("array semantics unavailable")
    }

private fun resolveMember(document: Document, path: List<Int>): JsonObjectMember {
    var value = document.root()
    for ((depth, ordinal) in path.withIndex()) {
        val members = objectMembers(value)
        val member = members.getOrNull(ordinal)
            ?: fail("member path ordinal $ordinal is out of range")
        if (depth + 1 == path.size) {
            return member
        }
        value = member.value()
    }
    fail("member path is empty")
}

private fun materializationValue(descriptor: PortableValue): PortableValue {
    val bits = stringField(descriptor, "bits")
    if (bits != null) {
        return PvBinaryFloat64(hexBits(bits))
    }
    val string = stringField(descriptor, "string")
    if (string != null) {
        return PvString(string)
    }
    if (booleanField(descriptor, "null") == true) {
        return PvNull
    }
    fail("unknown materialization value")
}

private fun scalarReplacement(descriptor: PortableValue): PortableValue {
    val integer = stringField(descriptor, "integer")
    if (integer != null) {
        return PvInteger(BigInteger(integer))
    }
    val coefficient = stringField(descriptor, "decimal_coefficient")
    val exponent = stringField(descriptor, "decimal_exponent")
    if (coefficient != null && exponent != null) {
        return consema.core.PvDecimal.of(BigInteger(coefficient), BigInteger(exponent))
    }
    val string = stringField(descriptor, "string")
    if (string != null) {
        return PvString(string)
    }
    val bits = stringField(descriptor, "bits")
    if (bits != null) {
        return PvBinaryFloat64(hexBits(bits))
    }
    fail("replacement has no supported scalar value")
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

private fun inputOrdinals(case: CaseData, name: String): List<Int> {
    val values = inputSequence(case, name) ?: fail("missing input.$name")
    return values.map { value ->
        (value as? PvInteger)?.value?.toInt() ?: fail("input.$name contains a non-integer")
    }
}

private fun expectedStrings(case: CaseData, name: String): List<String> {
    val values = expectedSequence(case, name) ?: fail("missing expected.$name")
    return values.map { value ->
        (value as? PvString)?.value ?: fail("expected.$name contains a non-string")
    }
}

private fun expectedDecimalPairs(case: CaseData): List<List<String>> {
    val values = expectedSequence(case, "element_decimals") ?: fail("missing expected.element_decimals")
    return values.map { pair ->
        val elements = (pair as? PvArray)?.items() ?: fail("decimal pair is not Sequence")
        if (elements.size != 2) fail("decimal pair must contain two strings")
        listOf(
            (elements[0] as? PvString)?.value ?: fail("decimal coefficient is not String"),
            (elements[1] as? PvString)?.value ?: fail("decimal exponent is not String"),
        )
    }
}

private fun materializationRequest(profileName: String, style: String): MaterializationRequest =
    MaterializationRequest.new(profile(profileName).id(), MaterializationStyleId(style, 1))
        .withNewline(NewlinePolicy.None)

private fun materializationFailureName(kind: MaterializationFailureKind): String =
    when (kind) {
        MaterializationFailureKind.INVALID_REQUEST -> "InvalidRequest"
        MaterializationFailureKind.UNSUPPORTED_PROFILE -> "UnsupportedProfile"
        MaterializationFailureKind.UNSUPPORTED_STYLE -> "UnsupportedStyle"
        MaterializationFailureKind.UNSUPPORTED_ENCODING -> "UnsupportedEncoding"
        MaterializationFailureKind.UNSUPPORTED_NEWLINE -> "UnsupportedNewline"
        MaterializationFailureKind.UNREPRESENTABLE -> "Unrepresentable"
        MaterializationFailureKind.RESOURCE_LIMIT -> "ResourceLimit"
        MaterializationFailureKind.FORMATION_FAILED -> "FormationFailed"
    }

private fun registryManifestEqual(left: RegistryManifest, right: RegistryManifest): Boolean =
    left.semanticModel == right.semanticModel &&
        left.contracts == right.contracts &&
        left.errorCodes == right.errorCodes

private fun queryCapabilities(): consema.protocol.CapabilitySet {
    val set = consema.protocol.CapabilitySet()
    set.insert(consema.protocol.CapabilityId("core.query.ordered-results", 1))
    return set
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}
