// The `consema.semantic-model-v6.conformance@1` suite runner
// (conformance/vectors/semantic-model-v6.json).
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-conformance/src/semantic_model_v6.rs (the
// per-case dispatch and every assertion are transcribed from the Rust
// handlers); the vector file itself drives every input and expectation
// (conformance/README.md rules 3-4). The registries, the
// core.source-encoding@1 record, the protocol envelope, and the canonical
// JSON/PVCE transports are the Kotlin consema.protocol package; the exact
// Java UTF-16 code-unit semantics are the consema.properties JavaString
// API (RFC 0010 §4; the classification scan is lib.rs).
// consema-go/go/protocol is a cross-reference only.
//
// The registry, source-encoding, java-utf16, and envelope cases whose
// observables the Kotlin packages implement run here. All v6 record types
// (SourceSnapshotMessageV2, SourcePatchMessageV2,
// MaterializationRequestMessageV2, MaterializationResultMessageV2,
// IniQueryResultMessage, JavaPropertiesQueryResultMessage, and the
// Completion record) ship in the Kotlin protocol package (Payload.kt
// dispatches them); every case runs (the runner records zero skips).

package consema.conformance

import consema.core.Entry
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.equal
import consema.document.DecodedOffset
import consema.document.MaterializationLimits
import consema.document.MaterializationStyleId
import consema.document.ProfileId
import consema.properties.JavaString
import consema.properties.JavaStringStatus
import consema.protocol.Completion
import consema.protocol.CompletionStatus
import consema.protocol.ContractId
import consema.protocol.ContractRegistry
import consema.protocol.ContractRegistryVersion
import consema.protocol.Domains
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.IniMatchLocator
import consema.protocol.IniQueryResultMessage
import consema.protocol.JavaPropertiesMatchLocator
import consema.protocol.JavaPropertiesQueryResultMessage
import consema.protocol.MaterializationProvenanceMapMessage
import consema.protocol.MaterializationReportMessage
import consema.protocol.MaterializationRequestFacts
import consema.protocol.MaterializationRequestMessageV2
import consema.protocol.MaterializationResultMessageV2
import consema.protocol.ProtocolErrorKind
import consema.protocol.ProtocolException
import consema.protocol.ProtocolLimits
import consema.protocol.ProtocolMessage
import consema.protocol.RegistryManifest
import consema.protocol.Roles
import consema.protocol.SourceEncoding
import consema.protocol.SourceLimits
import consema.protocol.SourceLocationException
import consema.protocol.SourcePatchLimits
import consema.protocol.SourcePatchMessageV2
import consema.protocol.SourcePatchV2
import consema.protocol.SourcePatchV2Exception
import consema.protocol.SourceReplacementV2
import consema.protocol.SourceSnapshotMessageV2
import consema.protocol.SourceSnapshotV2
import consema.protocol.V2EncodingRequest
import consema.protocol.decodeJson
import consema.protocol.decodePvce
import consema.protocol.encodeJson
import consema.protocol.encodePvce
import consema.protocol.windowsCodePageFromNumber
import java.io.File
import java.math.BigInteger

/** Runs the `consema.semantic-model-v6.conformance@1` suite. */
fun runSemanticModelV6(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runSemanticModelV6Case(runner, case)
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

private fun runSemanticModelV6Case(runner: Runner, case: CaseData) {
    when (case.id) {
        "registry.v6-manifest" -> registryV6Manifest(case)
        "registry.v1-v5-frozen" -> registryFrozen(runner, case)
        "registry.v6-additive-contracts" -> registryAdditions(case)
        "registry.v6-error-codes" -> registryErrorCodes(case)
        "source-encoding.mandatory-code-pages" -> sourceCodePages(case)
        "source-encoding.reject-unsupported" -> sourceRejectCodePage(case)
        "source.bom-policy-distinct" -> sourceBomPolicy(case)
        "source.snapshot-v2-code-page-boundaries" -> sourceBoundaries(case)
        "source.snapshot-v2-reject-digest" -> sourceDigest(case)
        "source.patch-v2-atomic-apply" -> sourcePatch(case)
        "materialization.request-v2-roundtrip" -> materializationRequest(case)
        "materialization.result-v2-version-closure" -> materializationResult(case)
        "java-utf16.edge-matrix" -> javaMatrix(case)
        "java-utf16.reject-noncanonical-unit", "java-utf16.reject-byte-mismatch" -> javaRejection(case)
        "ini-query.all-roles" -> iniRoles(case)
        "properties-query.all-roles" -> propertiesRoles(case)
        "line-query.reject-domain-role" -> lineDomainRejection(case)
        "line-query.reject-ordinal-and-count" -> lineOrdinalRejection(case)
        "line-query.reject-process-local" -> lineProcessLocal(case)
        "protocol.v1-v5-reject-v6-contracts" -> protocolOldRejection(case)
        "protocol.exact-version-dispatch" -> protocolExactVersionDispatch(case)
        "protocol.v6-nested-error-code" -> protocolNestedError(case)
        "protocol.new-contract-canonical-bytes" -> protocolCanonicalBytes(case)
        "protocol.new-payload-schema-and-limits" -> protocolSchemaLimits(case)
        else -> fail("runner does not recognize published case")
    }
}

// ---------------------------------------------------------------------------
// Registry facts (semantic_model_v6.rs).
// ---------------------------------------------------------------------------

private fun registryV6Manifest(case: CaseData) {
    val manifest = RegistryManifest.of(
        6,
        ContractRegistry.forVersion(ContractRegistryVersion.V6),
        ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V6),
    )
    val decoded = decodeManifest(manifest.toValue())
    val semanticModel = expectedString(case, "semantic_model") ?: fail("missing expected.semantic_model")
    val contractCount = expectedLong(case, "contract_count") ?: fail("missing expected.contract_count")
    val errorCodeCount = expectedLong(case, "error_code_count") ?: fail("missing expected.error_code_count")
    ensure(
        manifest.semanticModel.schema() == semanticModel &&
            manifest.contracts.size.toLong() == contractCount &&
            manifest.errorCodes.size.toLong() == errorCodeCount &&
            decoded.semanticModel == manifest.semanticModel &&
            decoded.contracts == manifest.contracts &&
            decoded.errorCodes == manifest.errorCodes,
    )
}

private fun registryFrozen(runner: Runner, case: CaseData) {
    val contractCounts = expectedLongList(case, "contract_counts")
    val errorCounts = expectedLongList(case, "error_code_counts")
    for (version in 1..5) {
        val manifest = RegistryManifest.of(
            version,
            ContractRegistry.forVersion(ContractRegistryVersion.entries[version - 1]),
            ErrorCodeRegistry.forVersion(ErrorRegistryVersion.entries[version - 1]),
        )
        ensure(
            manifest.contracts.size.toLong() == contractCounts[version - 1] &&
                manifest.errorCodes.size.toLong() == errorCounts[version - 1] &&
                registryManifestRoundtrips(manifest),
        )
    }
    val previous = inputSequence(case, "previous_vectors") ?: fail("missing input.previous_vectors")
    val names = listOf("semantic-model-v5", "protocol-v2", "source-v1")
    val files = listOf("semantic-model-v5.json", "protocol-v2.json", "source-v1.json")
    ensure(previous.size == names.size)
    for ((index, item) in previous.withIndex()) {
        val fields = item as? PvObject ?: fail("previous vector must be Object")
        val name = (fields.get("name") as? PvString)?.value ?: fail("previous vector name missing")
        val digest = (fields.get("sha256") as? PvString)?.value ?: fail("previous vector sha256 missing")
        val file = File(File(runner.vectorsDir), files[index])
        ensure(
            name == names[index] &&
                file.isFile &&
                sha256Hex(file.readBytes()) == digest,
        )
    }
}

private fun registryManifestRoundtrips(manifest: RegistryManifest): Boolean =
    try {
        val decoded = RegistryManifest.fromValue(manifest.toValue())
        decoded.semanticModel == manifest.semanticModel &&
            decoded.contracts == manifest.contracts &&
            decoded.errorCodes == manifest.errorCodes
    } catch (e: ProtocolException) {
        false
    }

private fun decodeManifest(value: PortableValue): RegistryManifest =
    try {
        RegistryManifest.fromValue(value)
    } catch (e: ProtocolException) {
        fail("registry manifest decode failed: ${e.kind.code} at ${e.path}")
    }

private fun registryAdditions(case: CaseData) {
    val v5 = ContractRegistry.forVersion(ContractRegistryVersion.V5)
    val v6 = ContractRegistry.forVersion(ContractRegistryVersion.V6)
    val actual = v6.contracts()
        .filter { candidate ->
            v5.contracts().none { old -> old.id == candidate.id && old.version == candidate.version }
        }
        .map { "${it.id}@${it.version}" }
    val expected = expectedStringList(case, "contracts")
    ensure(actual == expected)
}

private fun registryErrorCodes(case: CaseData) {
    val v5 = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V5)
    val v6 = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V6)
    val additions = v6.codes().filter { !v5.contains(it.code) }
    val expected = expectedStringList(case, "new_codes")
    val errorCodeCount = expectedLong(case, "error_code_count") ?: fail("missing expected.error_code_count")
    ensure(
        v6.codes().size.toLong() == errorCodeCount &&
            additions.map { it.code } == expected &&
            additions.all { it.introduced == "0.8.0" && it.description.isNotEmpty() },
    )
}

// ---------------------------------------------------------------------------
// Source-encoding facts (semantic_model_v6.rs).
// ---------------------------------------------------------------------------

private fun sourceCodePages(case: CaseData) {
    val pages = inputSequence(case, "code_pages") ?: fail("missing input.code_pages")
    var accepted = 0
    for (pageValue in pages) {
        val page = (pageValue as? PvInteger)?.value?.toInt() ?: fail("code page must be Integer")
        val encoding = windowsCodePageFromNumber(page) ?: fail("published code page rejected")
        val roundtrip = try {
            SourceEncoding.fromValue(encoding.toValue(), "$")
        } catch (e: ProtocolException) {
            fail("source-encoding round-trip failed: ${e.kind.code}")
        }
        if (roundtrip == encoding) {
            accepted += 1
        }
    }
    val acceptedCount = expectedLong(case, "accepted_count") ?: fail("missing expected.accepted_count")
    ensure(accepted.toLong() == acceptedCount)
}

private fun sourceRejectCodePage(case: CaseData) {
    val page = (caseInput(case, "code_page") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.code_page")
    val value = PvObject(
        listOf(
            Entry("schema", PvString("core.source-encoding@1")),
            Entry("kind", PvString("WindowsCodePage")),
            Entry("windows_code_page", PvInteger(BigInteger.valueOf(page.toLong()))),
        ),
    )
    val failure = try {
        SourceEncoding.fromValue(value, "$")
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind == ProtocolErrorKind.INVALID_VALUE && failure.kind.code == expectedCode)
}

// ---------------------------------------------------------------------------
// Source-v2 snapshot and patch cases (semantic_model_v6.rs).
// ---------------------------------------------------------------------------

/** source.bom-policy-distinct (semantic_model_v6.rs): the same
 * Latin1 bytes with the DetectUnicode and TreatAsContent BOM policies stay
 * distinct through the v2 wire form. */
private fun sourceBomPolicy(case: CaseData) {
    val bytes = decodeHex(inputString(case, "hex") ?: fail("missing input.hex"))
        ?: fail("invalid hex")
    val detected = transport {
        SourceSnapshotV2.fromRaw(
            bytes,
            V2EncodingRequest.new(SourceEncoding("Latin1", null)),
            SourceLimits.default,
        )
    }
    val content = transport {
        SourceSnapshotV2.fromRaw(
            bytes,
            V2EncodingRequest.new(SourceEncoding("Latin1", null))
                .withBomPolicy("TreatAsContent"),
            SourceLimits.default,
        )
    }
    dualRoundtrip("core.source-snapshot", 2, SourceSnapshotMessageV2.fromSnapshot(detected).toValue())
    dualRoundtrip("core.source-snapshot", 2, SourceSnapshotMessageV2.fromSnapshot(content).toValue())
    val detectText = expectedString(case, "detect_text") ?: fail("missing expected.detect_text")
    val contentText = expectedString(case, "content_text") ?: fail("missing expected.content_text")
    ensure(
        detected.decodedText() == detectText &&
            content.decodedText() == contentText &&
            detected.encodingFacts.bomPolicy == "DetectUnicode" &&
            content.encodingFacts.bomPolicy == "TreatAsContent",
    )
}

/** source.snapshot-v2-code-page-boundaries (semantic_model_v6.rs):
 * the CP932 snapshot decodes with exact raw byte boundaries. */
private fun sourceBoundaries(case: CaseData) {
    val page = (caseInput(case, "code_page") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.code_page")
    val snapshot = codePageSnapshotV2(page, decodeHex(inputString(case, "hex") ?: fail("missing input.hex"))
        ?: fail("invalid hex"))
    val payload = SourceSnapshotMessageV2.fromSnapshot(snapshot).toValue()
    val decoded = transport {
        SourceSnapshotMessageV2.fromValue(payload, SourceLimits.default)
    }
    val boundaries = expectedSequence(case, "raw_boundaries") ?: fail("missing expected.raw_boundaries")
    val boundaryValues = boundaries.map {
        (it as? PvInteger)?.value?.toInt() ?: fail("raw_boundaries item must be Integer")
    }
    val invalidBoundary = (caseExpected(case, "invalid_raw_boundary") as? PvInteger)?.value?.toInt()
        ?: fail("missing expected.invalid_raw_boundary")
    ensure(
        decoded.snapshot().decodedText() == (expectedString(case, "text") ?: fail("missing expected.text")) &&
            boundaryValues.all { boundary ->
                try {
                    decoded.snapshot().decodedPosition(boundary)
                    true
                } catch (e: SourceLocationException) {
                    false
                }
            } &&
            try {
                decoded.snapshot().decodedPosition(invalidBoundary)
                false
            } catch (e: SourceLocationException) {
                true
            } &&
            decoded.snapshot().rawByteAt(DecodedOffset.UnicodeScalar(1)) == 2,
    )
}

/** source.snapshot-v2-reject-digest (semantic_model_v6.rs): a
 * claimed digest that the raw bytes do not produce is rejected. */
private fun sourceDigest(case: CaseData) {
    val page = (caseInput(case, "code_page") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.code_page")
    val snapshot = codePageSnapshotV2(page, decodeHex(inputString(case, "hex") ?: fail("missing input.hex"))
        ?: fail("invalid hex"))
    val encoded = SourceSnapshotMessageV2.fromSnapshot(snapshot).toValue() as PvObject
    val digest = encoded.get("digest") as? PvObject ?: fail("missing digest")
    val changedDigest = replaceField(digest, "hex", PvString("0".repeat(64)))
    val changed = replaceField(encoded, "digest", changedDigest)
    val failure = try {
        SourceSnapshotMessageV2.fromValue(changed, SourceLimits.default)
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode && failure.path == "$.digest")
}

/** source.patch-v2-atomic-apply (semantic_model_v6.rs). */
private fun sourcePatch(case: CaseData) {
    val page = (caseInput(case, "code_page") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.code_page")
    val base = codePageSnapshotV2(
        page,
        decodeHex(inputString(case, "base_hex") ?: fail("missing input.base_hex")) ?: fail("invalid hex"),
    )
    val start = (caseInput(case, "start") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.start")
    val end = (caseInput(case, "end") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.end")
    val replacementBytes = decodeHex(inputString(case, "replacement_hex") ?: fail("missing input.replacement_hex"))
        ?: fail("invalid hex")
    val replacement = SourceReplacementV2(
        oldStart = start,
        oldEnd = end,
        original = base.bytes().copyOfRange(start, end),
        replacement = replacementBytes,
        redactOriginal = false,
        redactReplacement = false,
    )
    val patch = transport { SourcePatchV2.create(base, listOf(replacement), emptyMap(), SourcePatchLimits.default) }
    val wire = transport { SourcePatchMessageV2.fromPatch(patch).toValue() }
    val decoded = transport { SourcePatchMessageV2.fromValue(wire, SourcePatchLimits.default) }
    val target = transport { decoded.patch().apply(base, SourcePatchLimits.default) }
    val wrong = codePageSnapshotV2(page, "wrong".toByteArray(Charsets.UTF_8))
    val wrongCode = try {
        decoded.patch().apply(wrong, SourcePatchLimits.default)
        fail("wrong base patch unexpectedly applied")
    } catch (e: SourcePatchV2Exception) {
        e.code
    }
    val targetHex = expectedString(case, "target_hex") ?: fail("missing expected.target_hex")
    val wrongBaseCode = expectedString(case, "wrong_base_code") ?: fail("missing expected.wrong_base_code")
    ensure(
        toHex(target.bytes()) == targetHex &&
            wrongCode == wrongBaseCode,
    )
}

/** Builds one v2 snapshot under the explicit code page with the
 * TreatAsContent BOM policy (semantic_model_v6.rs). */
private fun codePageSnapshotV2(page: Int, bytes: ByteArray): SourceSnapshotV2 {
    val encoding = windowsCodePageFromNumber(page) ?: fail("unsupported code page $page")
    return transport {
        SourceSnapshotV2.fromRaw(
            bytes,
            V2EncodingRequest.new(encoding).withBomPolicy("TreatAsContent"),
            SourceLimits.default,
        )
    }
}

// ---------------------------------------------------------------------------
// Materialization request/result v2 cases (semantic_model_v6.rs).
// ---------------------------------------------------------------------------

private fun materializationRequest(case: CaseData) {
    val page = (caseInput(case, "code_page") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.code_page")
    val encoding = windowsCodePageFromNumber(page) ?: fail("unsupported code page $page")
    val request = MaterializationRequestFacts(
        targetProfile = ProfileId(inputString(case, "profile") ?: fail("missing input.profile"), 1),
        style = MaterializationStyleId(inputString(case, "style") ?: fail("missing input.style"), 1),
        encoding = encoding,
        newline = "CrLf",
        mappingPolicy = "RequireObject",
        representability = "ExactOnly",
        limits = consema.document.MaterializationLimits.default,
    )
    val payload = transport { MaterializationRequestMessageV2.fromFacts(request).toValue() }
    val decoded = transport { MaterializationRequestMessageV2.fromValue(payload) }
    val fields = payload as? PvObject ?: fail("request payload must be Object")
    val encodingFields = fields.get("encoding") as? PvObject ?: fail("encoding must be Object")
    val encodingKind = (encodingFields.get("kind") as? PvString)?.value
        ?: fail("encoding kind missing")
    ensure(
        decoded.request() == request &&
            encodingKind == (expectedString(case, "encoding_kind") ?: fail("missing expected.encoding_kind")),
    )
}

private fun materializationResult(case: CaseData) {
    val page = (caseInput(case, "code_page") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.code_page")
    val snapshot = codePageSnapshotV2(page, decodeHex(inputString(case, "hex") ?: fail("missing input.hex"))
        ?: fail("invalid hex"))
    val message = transport {
        MaterializationResultMessageV2.complete(
            ProfileId("ini.windows", 1),
            "target:ini",
            SourceSnapshotMessageV2.fromSnapshot(snapshot),
            "Exact",
            MaterializationReportMessage.empty(),
            MaterializationProvenanceMapMessage.empty(),
        )
    }
    dualRoundtrip("core.materialization-result", 2, message.toValue())

    val utf8 = SourceSnapshotV2.fromUtf8("k=v".toByteArray(Charsets.UTF_8))
    val v2 = transport {
        MaterializationResultMessageV2.complete(
            ProfileId("ini.portable", 1),
            "target:ini",
            SourceSnapshotMessageV2.fromSnapshot(utf8),
            "Exact",
            MaterializationReportMessage.empty(),
            MaterializationProvenanceMapMessage.empty(),
        )
    }
    val mixed = replaceOutcomeSnapshot(v2.toValue() as PvObject, v1SnapshotValue(utf8))
    val failure = try {
        MaterializationResultMessageV2.fromValueWithRegistry(
            mixed,
            ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V6),
        )
        null
    } catch (e: ProtocolException) {
        e
    }
    val mixedCode = expectedString(case, "mixed_version_code") ?: fail("missing expected.mixed_version_code")
    ensure(failure != null && failure.kind.code == mixedCode)
}

/** The v1 core.source-snapshot@1 wire value of one v2 snapshot (the mixed
 * version closure replaces the outcome snapshot with it). */
private fun v1SnapshotValue(snapshot: SourceSnapshotV2): PortableValue =
    PvObject(
        listOf(
            Entry("schema", PvString("core.source-snapshot@1")),
            Entry("raw_bytes", PvBytes.of(snapshot.bytes())),
            Entry(
                "digest",
                PvObject(
                    listOf(
                        Entry("algorithm", PvString("sha256")),
                        Entry("hex", PvString(sha256Hex(snapshot.bytes()))),
                    ),
                ),
            ),
            Entry(
                "encoding",
                PvObject(
                    listOf(
                        Entry("profile_default", PvString("utf-8")),
                        Entry("bom", PvNull),
                        Entry("declaration", PvNull),
                        Entry("caller_override", PvString("utf-8")),
                        Entry("selected", PvString("utf-8")),
                    ),
                ),
            ),
            Entry("decoded_status", PvString("Available")),
        ),
    )

/** Replaces the outcome.snapshot member of one result value. */
private fun replaceOutcomeSnapshot(result: PvObject, snapshot: PortableValue): PortableValue {
    val outcome = result.get("outcome") as? PvObject ?: fail("missing outcome")
    val changedOutcome = replaceField(outcome, "snapshot", snapshot)
    return replaceField(result, "outcome", changedOutcome)
}

/** Replaces one named member of an Object value, preserving the field order. */
private fun replaceField(value: PvObject, name: String, replacement: PortableValue): PvObject =
    PvObject(
        value.entries().map { entry ->
            if (entry.key == name) Entry(name, replacement) else entry
        },
    )

/** protocol.exact-version-dispatch (semantic_model_v6.rs): the v1
 * request decoder observes the v2-shaped encoding member. */
private fun protocolExactVersionDispatch(case: CaseData) {
    val request = MaterializationRequestFacts(
        targetProfile = ProfileId("ini.portable", 1),
        style = MaterializationStyleId("ini.portable-canonical", 1),
        encoding = SourceEncoding("Utf8", null),
        newline = "Lf",
        mappingPolicy = "RequireObject",
        representability = "ExactOnly",
        limits = consema.document.MaterializationLimits.default,
    )
    val v2 = transport { MaterializationRequestMessageV2.fromFacts(request).toValue() }
    val disguised = replaceField(v2 as PvObject, "schema", PvString("core.materialization-request@1"))
    val failure = try {
        ProtocolMessage.of(
            ContractId("core.materialization-request", 1),
            disguised,
            ContractRegistry.forVersion(ContractRegistryVersion.V6),
        )
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(
        failure != null &&
            failure.kind == ProtocolErrorKind.WRONG_TYPE &&
            failure.kind.code == expectedCode &&
            failure.path == "$.encoding",
    )
}

// ---------------------------------------------------------------------------
// Line-format query result cases (semantic_model_v6.rs).
// ---------------------------------------------------------------------------

private fun iniRoles(case: CaseData) {
    val roles = inputSequence(case, "roles") ?: fail("missing input.roles")
    val sourceId = inputString(case, "source_id") ?: fail("missing input.source_id")
    for ((ordinal, roleValue) in roles.withIndex()) {
        val role = (roleValue as? PvString)?.value ?: fail("role must be String")
        val domain = if (role == Roles.INI_SYNTAX_PIECE) {
            Domains.iniLosslessSyntaxV1()
        } else {
            Domains.iniNativeV1()
        }
        val locator = transport {
            IniMatchLocator.new(sourceId, "ini:node:$ordinal", role, ordinal.toULong())
        }
        val completion = Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null)
        val result = transport {
            IniQueryResultMessage.new(domain, role, listOf(locator), completion, emptyList())
        }
        dualRoundtrip("core.ini-query-result", 1, result.toValue())
    }
    val roleCount = expectedLong(case, "role_count") ?: fail("missing expected.role_count")
    ensure(roles.size.toLong() == roleCount)
}

private fun propertiesRoles(case: CaseData) {
    val roles = inputSequence(case, "roles") ?: fail("missing input.roles")
    val sourceId = inputString(case, "source_id") ?: fail("missing input.source_id")
    for ((ordinal, roleValue) in roles.withIndex()) {
        val role = (roleValue as? PvString)?.value ?: fail("role must be String")
        val domain = if (role == Roles.PROPERTIES_SYNTAX_PIECE) {
            Domains.javaPropertiesLosslessSyntaxV1()
        } else {
            Domains.javaPropertiesNativeV1()
        }
        val locator = transport {
            JavaPropertiesMatchLocator.new(sourceId, "properties:node:$ordinal", role, ordinal.toULong())
        }
        val completion = Completion.new(CompletionStatus.SUCCESS, 1, 1, null, null)
        val result = transport {
            JavaPropertiesQueryResultMessage.new(domain, role, listOf(locator), completion, emptyList())
        }
        dualRoundtrip("core.java-properties-query-result", 1, result.toValue())
    }
    val roleCount = expectedLong(case, "role_count") ?: fail("missing expected.role_count")
    ensure(roles.size.toLong() == roleCount)
}

private fun lineDomainRejection(case: CaseData) {
    val role = inputString(case, "role") ?: fail("missing input.role")
    val completion = Completion.new(CompletionStatus.SUCCESS, 0, 0, null, null)
    val failure = try {
        IniQueryResultMessage.new(Domains.iniNativeV1(), role, emptyList(), completion, emptyList())
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode)
}

private fun lineOrdinalRejection(case: CaseData) {
    val role = inputString(case, "role") ?: fail("missing input.role")
    val ordinals = inputSequence(case, "ordinals") ?: fail("missing input.ordinals")
    val produced = (caseInput(case, "produced") as? PvInteger)?.value?.toLong()
        ?: fail("missing input.produced")
    val matches = ordinals.mapIndexed { index, ordinalValue ->
        val ordinal = (ordinalValue as? PvInteger)?.value?.toLong()?.toULong()
            ?: fail("ordinal must be an unsigned Integer")
        transport { JavaPropertiesMatchLocator.new("source:properties", "property:$index", role, ordinal) }
    }
    val completion = Completion.new(CompletionStatus.SUCCESS, produced, produced, null, null)
    val failure = try {
        JavaPropertiesQueryResultMessage.new(
            Domains.javaPropertiesNativeV1(),
            role,
            matches,
            completion,
            emptyList(),
        )
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode)
}

private fun lineProcessLocal(case: CaseData) {
    val failure = try {
        IniMatchLocator.fromProcessLocal()
        null
    } catch (e: ProtocolException) {
        e
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(failure != null && failure.kind.code == expectedCode)
}

/** One dual-transport round trip of a v6-registry payload. */
private fun dualRoundtrip(contractId: String, version: Int, payload: PortableValue) {
    val registry = ContractRegistry.forVersion(ContractRegistryVersion.V6)
    val message = transport { ProtocolMessage.of(ContractId(contractId, version), payload, registry) }
    val limits = ProtocolLimits.default
    val json = transport { encodeJson(message.toValue(), limits) }
    val pvce = transport { encodePvce(message.toValue(), limits) }
    val jsonRoundtrip = transport { ProtocolMessage.fromValue(decodeJson(json, limits), registry) }
    val pvceRoundtrip = transport { ProtocolMessage.fromValue(decodePvce(pvce, limits), registry) }
    ensure(
        messageEquals(jsonRoundtrip, message) &&
            messageEquals(pvceRoundtrip, message),
    )
}

// ---------------------------------------------------------------------------
// Java UTF-16 string cases (semantic_model_v6.rs). The
// core.java-utf16-string@1 wire checks are implemented here
// (javaUtf16WireCheck; the contract is registered in the protocol
// ContractRegistry, and the protocol package dispatches it at envelope
// level only — no separate record decoder there). The exact code-unit
// classification and the UTF16BE/1 bytes are the real
// consema.properties JavaString API, and the strict wire checks below
// mirror the Rust JavaUtf16String::from_value (java_utf16.rs) one
// assertion at a time.
// ---------------------------------------------------------------------------

private fun javaMatrix(case: CaseData) {
    val cases = inputSequence(case, "cases") ?: fail("missing input.cases")
    var accepted = 0
    for (item in cases) {
        val fields = item as? PvObject ?: fail("Java case must be Object")
        val unitValues = (fields.get("units") as? PvArray)?.items() ?: fail("units must be Sequence")
        val units = unitValues.map { unit ->
            val text = (unit as? PvString)?.value ?: fail("invalid UTF-16 unit")
            text.toIntOrNull(16) ?: fail("invalid UTF-16 unit")
        }
        val expectedStatus = (fields.get("status") as? PvString)?.value ?: fail("missing Java case status")
        val status = javaUtf16Status(units)
        val wire = javaUtf16WireValue(units, javaUtf16Bytes(units), javaUtf16StatusName(status))
        if (javaUtf16StatusName(status) == expectedStatus &&
            javaUtf16WireCheck(wire, ProtocolLimits.default) is JavaUtf16Check.Valid
        ) {
            accepted += 1
        }
    }
    val acceptedCount = expectedLong(case, "accepted_count") ?: fail("missing expected.accepted_count")
    ensure(accepted.toLong() == acceptedCount)
}

private fun javaRejection(case: CaseData) {
    val unit = inputString(case, "unit") ?: fail("missing input.unit")
    val bytes = decodeHex(inputString(case, "bytes_hex") ?: fail("missing input.bytes_hex"))
        ?: fail("invalid hex")
    val status = inputString(case, "status") ?: fail("missing input.status")
    val value = PvObject(
        listOf(
            Entry("schema", PvString(JAVA_UTF16_SCHEMA)),
            Entry("encoding", PvString(JAVA_UTF16_ENCODING)),
            Entry("code_units", PvArray(listOf(PvString(unit)))),
            Entry("bytes", PvBytes.of(bytes)),
            Entry("unicode_status", PvString(status)),
        ),
    )
    val failure = javaUtf16WireCheck(value, ProtocolLimits.default)
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    val expectedPath = expectedString(case, "path") ?: fail("missing expected.path")
    ensure(
        failure is JavaUtf16Check.Invalid &&
            failure.code == expectedCode &&
            failure.path == expectedPath,
    )
}

/** The strict outcome of one core.java-utf16-string@1 wire check. */
private sealed class JavaUtf16Check {
    object Valid : JavaUtf16Check()
    data class Invalid(val code: String, val path: String) : JavaUtf16Check()
}

private const val JAVA_UTF16_SCHEMA = "core.java-utf16-string@1"
private const val JAVA_UTF16_ENCODING = "UTF16BE/1"
private const val CODE_UNKNOWN_FIELD = "core.protocol.unknown-field@1"
private const val CODE_SCHEMA_MISMATCH = "core.protocol.schema-mismatch@1"
private const val CODE_WRONG_TYPE = "core.protocol.wrong-type@1"
private const val CODE_INVALID_VALUE = "core.protocol.invalid-value@1"
private const val CODE_RESOURCE_LIMIT = "core.protocol.resource-limit@1"

/** Strictly checks one core.java-utf16-string@1 value against the fixed
 * schema, the canonical uppercase unit spellings, the UTF16BE/1 byte
 * identity, the exact surrogate-pairing status, and the canonical
 * re-encoding (java_utf16.rs). */
private fun javaUtf16WireCheck(value: PortableValue, limits: ProtocolLimits): JavaUtf16Check {
    val entries = (value as? PvObject)?.entries()
        ?: return JavaUtf16Check.Invalid(CODE_WRONG_TYPE, "$")
    val expected = listOf("schema", "encoding", "code_units", "bytes", "unicode_status")
    for (entry in entries) {
        if (entry.key !in expected) {
            return JavaUtf16Check.Invalid(CODE_UNKNOWN_FIELD, "$.${entry.key}")
        }
    }
    if (entries.size != expected.size || entries.map { it.key } != expected) {
        return JavaUtf16Check.Invalid(CODE_SCHEMA_MISMATCH, "$")
    }
    if ((entries[0].value as? PvString)?.value != JAVA_UTF16_SCHEMA) {
        return JavaUtf16Check.Invalid(CODE_SCHEMA_MISMATCH, "$.schema")
    }
    if ((entries[1].value as? PvString)?.value != JAVA_UTF16_ENCODING) {
        return JavaUtf16Check.Invalid(CODE_INVALID_VALUE, "$.encoding")
    }
    val unitValues = (entries[2].value as? PvArray)?.items()
        ?: return JavaUtf16Check.Invalid(CODE_WRONG_TYPE, "$.code_units")
    if (unitValues.size > limits.maxContainerEntries) {
        return JavaUtf16Check.Invalid(CODE_RESOURCE_LIMIT, "$.code_units")
    }
    val bytes = (entries[3].value as? PvBytes)?.content()
        ?: return JavaUtf16Check.Invalid(CODE_WRONG_TYPE, "$.bytes")
    if (bytes.size > limits.maxBlobBytes) {
        return JavaUtf16Check.Invalid(CODE_RESOURCE_LIMIT, "$.bytes")
    }
    if (bytes.size % 2 != 0 || bytes.size != unitValues.size * 2) {
        return JavaUtf16Check.Invalid(CODE_INVALID_VALUE, "$.bytes")
    }
    val units = IntArray(unitValues.size)
    for ((index, unitValue) in unitValues.withIndex()) {
        val path = "$.code_units[$index]"
        val text = (unitValue as? PvString)?.value
            ?: return JavaUtf16Check.Invalid(CODE_WRONG_TYPE, path)
        val unit = parseJavaUtf16Unit(text)
            ?: return JavaUtf16Check.Invalid(CODE_INVALID_VALUE, path)
        if (bytes[index * 2].toInt() and 0xff != (unit ushr 8) ||
            bytes[index * 2 + 1].toInt() and 0xff != (unit and 0xff)
        ) {
            return JavaUtf16Check.Invalid(CODE_INVALID_VALUE, path)
        }
        units[index] = unit
    }
    val claimedStatus = (entries[4].value as? PvString)?.value
        ?: return JavaUtf16Check.Invalid(CODE_WRONG_TYPE, "$.unicode_status")
    if (claimedStatus != javaUtf16StatusName(javaUtf16Status(units.toList()))) {
        return JavaUtf16Check.Invalid(CODE_INVALID_VALUE, "$.unicode_status")
    }
    if (!equal(javaUtf16WireValue(units.toList(), bytes, claimedStatus), value)) {
        return JavaUtf16Check.Invalid(CODE_INVALID_VALUE, "$")
    }
    return JavaUtf16Check.Valid
}

/** Parses one code unit only in the canonical uppercase four-hex-digit
 * spelling (java_utf16.rs). */
private fun parseJavaUtf16Unit(text: String): Int? {
    if (text.length != 4) {
        return null
    }
    for (character in text) {
        if (character.isLowerCase() || Character.digit(character, 16) < 0) {
            return null
        }
    }
    return text.toIntOrNull(16)
}

/** Encodes the canonical core.java-utf16-string@1 value (java_utf16.rs
 *): uppercase code-unit hex, BOM-free big-endian bytes, and the
 * exact surrogate-pairing status. */
private fun javaUtf16WireValue(units: List<Int>, bytes: ByteArray, status: String): PortableValue =
    PvObject(
        listOf(
            Entry("schema", PvString(JAVA_UTF16_SCHEMA)),
            Entry("encoding", PvString(JAVA_UTF16_ENCODING)),
            Entry("code_units", PvArray(units.map { PvString("%04X".format(it)) })),
            Entry("bytes", PvBytes.of(bytes)),
            Entry("unicode_status", PvString(status)),
        ),
    )

/** The canonical BOM-free big-endian UTF-16 bytes of one unit sequence
 * (java_utf16.rs). */
private fun javaUtf16Bytes(units: List<Int>): ByteArray {
    val bytes = ByteArray(units.size * 2)
    for ((index, unit) in units.withIndex()) {
        bytes[index * 2] = (unit ushr 8).toByte()
        bytes[index * 2 + 1] = (unit and 0xff).toByte()
    }
    return bytes
}

/** The exact surrogate-pairing status of one unit sequence, computed by
 * the real JavaString classification (lib.rs). */
private fun javaUtf16Status(units: List<Int>): JavaStringStatus =
    JavaString.fromCodeUnits(units.map { it.toChar() }.toCharArray()).status

private fun javaUtf16StatusName(status: JavaStringStatus): String =
    when (status) {
        JavaStringStatus.WellFormedUnicode -> "WellFormedUnicode"
        JavaStringStatus.UnpairedSurrogate -> "UnpairedSurrogate"
    }

// ---------------------------------------------------------------------------
// Protocol envelope cases (semantic_model_v6.rs).
// ---------------------------------------------------------------------------

private fun protocolOldRejection(case: CaseData) {
    val payloads = newPayloads()
    val old = listOf(1, 2, 3, 4, 5).map {
        ContractRegistry.forVersion(ContractRegistryVersion.entries[it - 1])
    }
    val expectedCode = expectedString(case, "code") ?: fail("missing expected.code")
    val rejected = payloads.count { (contract, payload) ->
        old.all { registry ->
            val failure = try {
                ProtocolMessage.of(contract, payload, registry)
                null
            } catch (e: ProtocolException) {
                e
            }
            failure != null && failure.kind.code == expectedCode
        }
    }
    val expectedPairs = expectedLong(case, "rejected_pairs") ?: fail("missing expected.rejected_pairs")
    ensure(rejected.toLong() == expectedPairs)
}

private fun protocolNestedError(case: CaseData) {
    val code = inputString(case, "failure_code") ?: fail("missing input.failure_code")
    val v5Code = expectedString(case, "v5_code") ?: fail("missing expected.v5_code")
    val v5 = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V5)
    val v6 = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V6)
    val v5Rejection = try {
        v5.validate(code)
        null
    } catch (e: ProtocolException) {
        e
    }
    try {
        v6.validate(code)
    } catch (e: ProtocolException) {
        fail("v6 registry rejected a published code: ${e.kind.code}")
    }
    val registry = ContractRegistry.forVersion(ContractRegistryVersion.V6)
    val message = transport {
        ProtocolMessage.of(
            ContractId("core.completion", 1),
            completionValue("Failed", 1, 0, code),
            registry,
        )
    }
    val decoded = transport { ProtocolMessage.fromValue(message.toValue(), registry) }
    ensure(
        v5Rejection != null &&
            v5Rejection.kind.code == v5Code &&
            messageEquals(decoded, message),
    )
}

private fun protocolCanonicalBytes(case: CaseData) {
    val registry = ContractRegistry.forVersion(ContractRegistryVersion.V6)
    val limits = ProtocolLimits.default
    val encodingPayload = SourceEncoding("WindowsCodePage", 1252).toValue()
    val javaUnits = listOf(0x0000, 0xd83d, 0xde00, 0xd800)
    val javaPayload = javaUtf16WireValue(
        javaUnits,
        javaUtf16Bytes(javaUnits),
        javaUtf16StatusName(javaUtf16Status(javaUnits)),
    )
    val encodingMessage = transport {
        ProtocolMessage.of(ContractId("core.source-encoding", 1), encodingPayload, registry)
    }
    val javaMessage = transport {
        ProtocolMessage.of(ContractId("core.java-utf16-string", 1), javaPayload, registry)
    }
    val actual = listOf(
        transport { toHex(encodeJson(encodingMessage.toValue(), limits)) },
        transport { toHex(encodePvce(encodingMessage.toValue(), limits)) },
        transport { toHex(encodeJson(javaMessage.toValue(), limits)) },
        transport { toHex(encodePvce(javaMessage.toValue(), limits)) },
    )
    val expected = listOf(
        expectedString(case, "source_encoding_json_hex") ?: fail("missing expected.source_encoding_json_hex"),
        expectedString(case, "source_encoding_pvce_hex") ?: fail("missing expected.source_encoding_pvce_hex"),
        expectedString(case, "java_utf16_json_hex") ?: fail("missing expected.java_utf16_json_hex"),
        expectedString(case, "java_utf16_pvce_hex") ?: fail("missing expected.java_utf16_pvce_hex"),
    )
    ensure(actual == expected)
}

private fun protocolSchemaLimits(case: CaseData) {
    val exact = javaUtf16WireValue(listOf(0x0041), javaUtf16Bytes(listOf(0x0041)), "WellFormedUnicode")
    val unknown = PvObject((exact as consema.core.PvObject).entries() + Entry("unknown", PvNull))
    val unknownFailure = javaUtf16WireCheck(unknown, ProtocolLimits.default)
    val maxUnits = (caseInput(case, "max_units") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.max_units")
    val limitFailure = javaUtf16WireCheck(exact, ProtocolLimits.default.copy(maxContainerEntries = maxUnits))
    val unknownFieldCode = expectedString(case, "unknown_field_code") ?: fail("missing expected.unknown_field_code")
    val limitCode = expectedString(case, "limit_code") ?: fail("missing expected.limit_code")
    ensure(
        unknownFailure is JavaUtf16Check.Invalid &&
            unknownFailure.code == unknownFieldCode &&
            unknownFailure.path == "$.unknown" &&
            limitFailure is JavaUtf16Check.Invalid &&
            limitFailure.code == limitCode &&
            limitFailure.path == "$.code_units",
    )
}

/** The eight v6 contract payloads that no v1-v5 registry may recognize
 * (semantic_model_v6.rs). */
private fun newPayloads(): List<Pair<ContractId, PortableValue>> {
    val encoding = SourceEncoding("WindowsCodePage", 1252).toValue()
    val java = javaUtf16WireValue(listOf(0xd800), javaUtf16Bytes(listOf(0xd800)), "UnpairedSurrogate")
    fun schemaObject(schema: String): PortableValue = PvObject(listOf(Entry("schema", PvString(schema))))
    return listOf(
        ContractId("core.ini-query-result", 1) to schemaObject("core.ini-query-result@1"),
        ContractId("core.java-properties-query-result", 1) to schemaObject("core.java-properties-query-result@1"),
        ContractId("core.java-utf16-string", 1) to java,
        ContractId("core.materialization-request", 2) to schemaObject("core.materialization-request@2"),
        ContractId("core.materialization-result", 2) to schemaObject("core.materialization-result@2"),
        ContractId("core.source-encoding", 1) to encoding,
        ContractId("core.source-patch", 2) to schemaObject("core.source-patch@2"),
        ContractId("core.source-snapshot", 2) to schemaObject("core.source-snapshot@2"),
    )
}

// ---------------------------------------------------------------------------
// Shared helpers.
// ---------------------------------------------------------------------------

/** The fixed `core.completion@1` value (execution.rs). */
private fun completionValue(status: String, processed: Long, produced: Long, failureCode: String?): PortableValue =
    PvObject(
        listOf(
            Entry("schema", PvString("core.completion@1")),
            Entry("status", PvString(status)),
            Entry("processed", integerValue(processed)),
            Entry("produced", integerValue(produced)),
            Entry("limit_name", PvNull),
            Entry("failure_code", if (failureCode == null) PvNull else PvString(failureCode)),
        ),
    )

private fun messageEquals(left: ProtocolMessage, right: ProtocolMessage): Boolean =
    left.contract == right.contract && equal(left.payload, right.payload)

private fun integerValue(value: Long): PvInteger = PvInteger(BigInteger.valueOf(value))

private fun expectedLongList(case: CaseData, name: String): List<Long> =
    expectedSequence(case, name)?.map {
        (it as? PvInteger)?.value?.toLong() ?: fail("expected.$name item must be Integer")
    } ?: fail("missing expected.$name")

private fun expectedStringList(case: CaseData, name: String): List<String> =
    expectedSequence(case, name)?.map {
        (it as? PvString)?.value ?: fail("expected.$name item must be String")
    } ?: fail("missing expected.$name")

/** Runs one protocol operation; a transport or record failure fails the
 * case instead of escaping the suite loop. */
private fun <T> transport(block: () -> T): T =
    try {
        block()
    } catch (e: ProtocolException) {
        fail("protocol operation failed: ${e.kind.code} at ${e.path}")
    }

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}
