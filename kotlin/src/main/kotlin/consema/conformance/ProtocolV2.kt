// The `consema.protocol.conformance@2` suite runner
// (conformance/vectors/protocol-v2.json).
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-conformance/src/protocol_v2.rs (the
// per-case dispatch and every fact is transcribed from the Rust handlers;
// the vector file drives every input and expectation). The
// core.source-snapshot@1 / core.source-patch@1 wire shapes are transcribed
// from https://github.com/consema/consema-rs/blob/main/consema-protocol/src/source.rs (source_snapshot_value,
// source_patch_value, encoding_value, digest_value and their decoders).
//
// The Kotlin document package owns the source model (SourceSnapshot,
// SourcePatch, SourceReplacement, EncodingFacts, ContentDigest); the
// record-level decode/re-verify helpers below mirror the Rust
// SourceSnapshotMessage::from_value and SourcePatchMessage::from_value
// decoders (schema discriminator, raw-byte digest re-verification, encoding
// facts reconciliation, decoded-status reconciliation, and the source
// error -> protocol error mapping: resource limits surface as
// core.protocol.resource-limit@1, everything else as
// core.protocol.invalid-value@1). The common envelope and the canonical
// transports carry the dual-transport roundtrips.

package consema.conformance

import consema.core.Entry
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.equal
import consema.document.BomKind
import consema.document.ContentDigest
import consema.document.EncodingFacts
import consema.document.EncodingRequest
import consema.document.SourceEncoding
import consema.document.SourceErrorKind
import consema.document.SourceException
import consema.document.SourceLimits
import consema.document.SourcePatch
import consema.document.SourcePatchErrorKind
import consema.document.SourcePatchException
import consema.document.SourcePatchLimits
import consema.document.SourceReplacement
import consema.document.SourceSnapshot
import consema.protocol.ContractId
import consema.protocol.ContractRegistry
import consema.protocol.ContractRegistryVersion
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import consema.protocol.ProtocolErrorKind
import consema.protocol.ProtocolException
import consema.protocol.ProtocolLimits
import consema.protocol.ProtocolMessage
import consema.protocol.RegistryManifest
import consema.protocol.booleanOf
import consema.protocol.decodeJson
import consema.protocol.decodePvce
import consema.protocol.encodeJson
import consema.protocol.encodePvce
import consema.protocol.errorCodeManifestValueFor
import consema.protocol.exactFields
import consema.protocol.integerValue
import consema.protocol.invalid
import consema.protocol.protocolError
import consema.protocol.resource
import consema.protocol.schemaFields
import consema.protocol.sequenceOf
import consema.protocol.stringMapFromObject
import consema.protocol.stringMapObject
import consema.protocol.stringOf
import consema.protocol.unsigned64
import consema.protocol.validateErrorCodeManifestValue

/** Runs the `consema.protocol.conformance@2` suite. */
fun runProtocolV2(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runProtocolV2Case(case)
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

private fun runProtocolV2Case(case: CaseData) {
    when (case.id) {
        "protocol.v2.registry-manifest" -> registryCase(case, frozenV1 = false)
        "protocol.v2.registry-v1-frozen" -> registryCase(case, frozenV1 = true)
        "protocol.v2.error-code-manifest" -> errorManifestCase(case)
        "protocol.v2.snapshot-dual-transport" -> snapshotTransport(case)
        "protocol.v2.patch-dual-transport" -> patchTransport(case)
        "protocol.v2.reject-source-under-v1" -> rejectSourceV1(case)
        "protocol.v2.reject-forged-digest" -> rejectForgedDigest(case)
        "protocol.v2.reject-forged-encoding" -> rejectForgedEncoding(case)
        "protocol.v2.snapshot-resource-limit" -> snapshotResourceLimit(case)
        "protocol.v2.patch-resource-limit" -> patchResourceLimit(case)
        "protocol.v2.patch-stale-after-wire" -> patchStaleAfterWire(case)
        else -> fail("runner does not recognize published protocol v2 case")
    }
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}

private val v1Registry: ContractRegistry =
    ContractRegistry.forVersion(ContractRegistryVersion.V1)

private val v2Registry: ContractRegistry =
    ContractRegistry.forVersion(ContractRegistryVersion.V2)

private val v1Errors: ErrorCodeRegistry =
    ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V1)

private val v2Errors: ErrorCodeRegistry =
    ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V2)

private val v2Manifest: RegistryManifest =
    RegistryManifest.of(2, v2Registry, v2Errors)

private fun registryCase(case: CaseData, frozenV1: Boolean) {
    val manifest = if (frozenV1) {
        RegistryManifest.of(1, v1Registry, v1Errors)
    } else {
        v2Manifest
    }
    val decoded = RegistryManifest.fromValue(manifest.toValue())
    val registry = if (frozenV1) v1Registry else v2Registry
    ensure(
        manifestEqualsFields(decoded, manifest) &&
            decoded.semanticModel.schema() ==
            (expectedString(case, "semantic_model") ?: fail("missing expected.semantic_model")) &&
            decoded.contracts.size.toLong() ==
            (expectedLong(case, "contract_count") ?: fail("missing expected.contract_count")) &&
            decoded.errorCodes.size.toLong() ==
            (expectedLong(case, "error_code_count") ?: fail("missing expected.error_code_count")) &&
            registry.recognizes(ContractId("core.source-snapshot", 1)) ==
            (expectedBoolean(case, "recognizes_source_snapshot")
                ?: fail("missing expected.recognizes_source_snapshot")) &&
            // Protocol-v2 conformance binds semantic-model v2 explicitly;
            // this suite-local "current" fact must not follow a later
            // library model.
            manifestEqualsFields(decoded, v2Manifest) ==
            (expectedBoolean(case, "is_current") ?: fail("missing expected.is_current")),
    )
}

private fun errorManifestCase(case: CaseData) {
    val manifest = errorCodeManifestValueFor(v2Errors)
    validateErrorCodeManifestValue(manifest)
    val manifestValue = manifest as? PvObject ?: fail("manifest must be Object")
    val count = (manifestValue.get("error_codes") as? PvArray)?.items()?.size
        ?: fail("error_codes must be Sequence")
    val requiredCode = expectedString(case, "required_code")
        ?: fail("missing expected.required_code")
    ensure(
        count.toLong() ==
            (expectedLong(case, "error_code_count") ?: fail("missing expected.error_code_count")) &&
            v2Errors.contains(requiredCode) &&
            !v1Errors.contains(requiredCode),
    )
}

private fun snapshotTransport(case: CaseData) {
    val snapshot = source(case, "raw_hex")
    val message = ProtocolMessage.of(
        ContractId("core.source-snapshot", 1),
        sourceSnapshotValue(snapshot),
        v2Registry,
    )
    val limits = ProtocolLimits.default
    val json = envelopeJson(message, v2Registry, limits)
    val pvce = envelopePvce(message, v2Registry, limits)
    val decoded = sourceSnapshotFromValue(json.payload, SourceLimits.default)
    ensure(
        messageEquals(json, message) ==
            (expectedBoolean(case, "json_equal") ?: fail("missing expected.json_equal")) &&
            messageEquals(pvce, message) ==
            (expectedBoolean(case, "pvce_equal") ?: fail("missing expected.pvce_equal")) &&
            decoded == snapshot &&
            snapshot.digest.toHex() ==
            (expectedString(case, "digest") ?: fail("missing expected.digest")),
    )
}

private fun patchTransport(case: CaseData) {
    val base = source(case, "base_hex")
    val patch = createPatch(case, base)
    val message = ProtocolMessage.of(
        ContractId("core.source-patch", 1),
        sourcePatchValue(patch),
        v2Registry,
    )
    val limits = ProtocolLimits.default
    val json = envelopeJson(message, v2Registry, limits)
    val pvce = envelopePvce(message, v2Registry, limits)
    val decoded = sourcePatchFromValue(json.payload, SourcePatchLimits.default)
    val target = try {
        decoded.apply(base, SourcePatchLimits.default)
    } catch (e: SourcePatchException) {
        fail("apply: ${e.code}")
    }
    ensure(
        messageEquals(json, message) ==
            (expectedBoolean(case, "json_equal") ?: fail("missing expected.json_equal")) &&
            messageEquals(pvce, message) ==
            (expectedBoolean(case, "pvce_equal") ?: fail("missing expected.pvce_equal")) &&
            toHex(target.bytes()) ==
            (expectedString(case, "target_hex") ?: fail("missing expected.target_hex")),
    )
}

private fun rejectSourceV1(case: CaseData) {
    val snapshot = source(case, "raw_hex")
    val failure = try {
        ProtocolMessage.of(
            ContractId("core.source-snapshot", 1),
            sourceSnapshotValue(snapshot),
            v1Registry,
        )
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(
        failure?.code == (expectedString(case, "code") ?: fail("missing expected.code")),
    )
}

private fun rejectForgedDigest(case: CaseData) {
    val snapshot = source(case, "raw_hex")
    val value = sourceSnapshotValue(snapshot)
    val forgedDigest = PvObject(
        listOf(
            Entry("algorithm", PvString("sha256")),
            Entry("hex", PvString("00".repeat(32))),
        ),
    )
    val forged = replaceField(value, "digest", forgedDigest)
    val failure = try {
        sourceSnapshotFromValue(forged, SourceLimits.default)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(
        failure?.code == (expectedString(case, "code") ?: fail("missing expected.code")),
    )
}

private fun rejectForgedEncoding(case: CaseData) {
    val snapshot = source(case, "raw_hex")
    val value = sourceSnapshotValue(snapshot)
    val encoding = (value as? PvObject)?.get("encoding") as? PvObject
        ?: fail("encoding field missing")
    val forgedEncoding = replaceField(
        encoding,
        "selected",
        PvString(inputString(case, "forged_selected") ?: fail("missing input.forged_selected")),
    )
    val forged = replaceField(value, "encoding", forgedEncoding)
    val failure = try {
        sourceSnapshotFromValue(forged, SourceLimits.default)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(
        failure?.code == (expectedString(case, "code") ?: fail("missing expected.code")),
    )
}

private fun snapshotResourceLimit(case: CaseData) {
    val snapshot = source(case, "raw_hex")
    val value = sourceSnapshotValue(snapshot)
    val limits = SourceLimits(
        maxRawBytes = (caseInput(case, "max_raw_bytes") as? PvInteger)?.value?.toInt()
            ?: fail("missing input.max_raw_bytes"),
        maxDecodedUtf8Bytes = SourceLimits.default.maxDecodedUtf8Bytes,
        maxDecodedScalars = SourceLimits.default.maxDecodedScalars,
    )
    val failure = try {
        sourceSnapshotFromValue(value, limits)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(
        failure?.code == (expectedString(case, "code") ?: fail("missing expected.code")),
    )
}

private fun patchResourceLimit(case: CaseData) {
    val base = source(case, "base_hex")
    val patch = createPatch(case, base)
    val value = sourcePatchValue(patch)
    val limits = SourcePatchLimits(
        source = SourceLimits.default,
        maxReplacements = (caseInput(case, "max_replacements") as? PvInteger)?.value?.toInt()
            ?: fail("missing input.max_replacements"),
        maxPatchBytes = SourcePatchLimits.default.maxPatchBytes,
    )
    val failure = try {
        sourcePatchFromValue(value, limits)
        null
    } catch (e: ProtocolException) {
        e.kind
    }
    ensure(
        failure?.code == (expectedString(case, "code") ?: fail("missing expected.code")),
    )
}

private fun patchStaleAfterWire(case: CaseData) {
    val base = source(case, "base_hex")
    val patch = createPatch(case, base)
    val value = sourcePatchValue(patch)
    val limits = ProtocolLimits.default
    val transported = decodePvce(encodePvce(value, limits), limits)
    val decoded = sourcePatchFromValue(transported, SourcePatchLimits.default)
    val stale = source(case, "stale_hex")
    val code = try {
        decoded.apply(stale, SourcePatchLimits.default)
        null
    } catch (e: SourcePatchException) {
        e.code
    }
    ensure(code == (expectedString(case, "code") ?: fail("missing expected.code")))
}

// ---------------------------------------------------------------------------
// Shared envelope helpers.
// ---------------------------------------------------------------------------

private fun envelopeJson(
    message: ProtocolMessage,
    registry: ContractRegistry,
    limits: ProtocolLimits,
): ProtocolMessage =
    ProtocolMessage.fromValue(decodeJson(encodeJson(message.toValue(), limits), limits), registry)

private fun envelopePvce(
    message: ProtocolMessage,
    registry: ContractRegistry,
    limits: ProtocolLimits,
): ProtocolMessage =
    ProtocolMessage.fromValue(decodePvce(encodePvce(message.toValue(), limits), limits), registry)

private fun messageEquals(a: ProtocolMessage, b: ProtocolMessage): Boolean =
    a.contract == b.contract && equal(a.payload, b.payload)

private fun manifestEqualsFields(a: RegistryManifest, b: RegistryManifest): Boolean =
    a.semanticModel == b.semanticModel &&
        a.contracts == b.contracts &&
        a.errorCodes == b.errorCodes

// ---------------------------------------------------------------------------
// Source snapshot and patch wire records (protocol/src/source.rs).
// ---------------------------------------------------------------------------

/** Parses the frozen v1 encoding kind spellings of the encoding facts
 * record. */
private fun encodingFromName(name: String): SourceEncoding = when (name) {
    "Binary" -> SourceEncoding.Binary
    "Utf8" -> SourceEncoding.Utf8
    "Utf16Le" -> SourceEncoding.Utf16Le
    "Utf16Be" -> SourceEncoding.Utf16Be
    "Latin1" -> SourceEncoding.Latin1
    else -> throw invalid("$.encoding", "unknown encoding ID")
}

private fun encodingName(encoding: SourceEncoding): String = when (encoding) {
    SourceEncoding.Binary -> "Binary"
    SourceEncoding.Utf8 -> "Utf8"
    SourceEncoding.Utf16Le -> "Utf16Le"
    SourceEncoding.Utf16Be -> "Utf16Be"
    SourceEncoding.Latin1 -> "Latin1"
}

private fun bomName(bom: BomKind): String = when (bom) {
    BomKind.Utf8 -> "Utf8"
    BomKind.Utf16Le -> "Utf16Le"
    BomKind.Utf16Be -> "Utf16Be"
}

private fun optionalBom(value: PortableValue, path: String): BomKind? {
    if (value is PvNull) {
        return null
    }
    return when (stringOf(value, path)) {
        "Utf8" -> BomKind.Utf8
        "Utf16Le" -> BomKind.Utf16Le
        "Utf16Be" -> BomKind.Utf16Be
        else -> throw invalid(path, "unknown BOM ID")
    }
}

private fun optionalEncoding(value: PortableValue, path: String): SourceEncoding? {
    if (value is PvNull) {
        return null
    }
    return encodingFromName(stringOf(value, path))
}

/** The v1 encoding-facts record (encoding_value, source.rs). */
private fun v1EncodingFactsValue(facts: EncodingFacts): PortableValue = PvObject(
    listOf(
        Entry("profile_default", PvString(encodingName(facts.profileDefault))),
        Entry("bom", if (facts.bom == null) PvNull else PvString(bomName(facts.bom))),
        Entry(
            "declaration",
            if (facts.declaration == null) PvNull else PvString(encodingName(facts.declaration)),
        ),
        Entry(
            "caller_override",
            if (facts.callerOverride == null) {
                PvNull
            } else {
                PvString(encodingName(facts.callerOverride))
            },
        ),
        Entry("selected", PvString(encodingName(facts.selected))),
    ),
)

/** Strictly decodes the v1 encoding-facts record (encoding_from_value,
 * source.rs). */
private fun v1EncodingFactsFromValue(value: PortableValue, path: String): EncodingFacts {
    val fields = exactFields(
        value,
        listOf("profile_default", "bom", "declaration", "caller_override", "selected"),
        path,
    )
    val profileDefault = encodingFromName(stringOf(fields[0], "$path.profile_default"))
    val bom = optionalBom(fields[1], "$path.bom")
    val declaration = optionalEncoding(fields[2], "$path.declaration")
    val callerOverride = optionalEncoding(fields[3], "$path.caller_override")
    val selected = encodingFromName(stringOf(fields[4], "$path.selected"))
    return try {
        EncodingFacts.fromClaim(profileDefault, bom, declaration, callerOverride, selected)
    } catch (e: SourceException) {
        throw sourceErrorToProtocol(e)
    }
}

/** Rebuilds the resolution request from claimed facts (request_from_facts,
 * source.rs). */
private fun requestFromFacts(facts: EncodingFacts): EncodingRequest {
    var request = EncodingRequest.new(facts.profileDefault)
    facts.declaration?.let { request = request.withDeclaration(it) }
    facts.callerOverride?.let { request = request.withCallerOverride(it) }
    return request
}

/** Compares the five wire-level encoding facts (the Rust EncodingFacts
 * equality over the v1 record fields). */
private fun sameFacts(a: EncodingFacts, b: EncodingFacts): Boolean =
    a.profileDefault == b.profileDefault &&
        a.bom == b.bom &&
        a.declaration == b.declaration &&
        a.callerOverride == b.callerOverride &&
        a.selected == b.selected

private fun digestValue(digest: ContentDigest): PortableValue = PvObject(
    listOf(
        Entry("algorithm", PvString(digest.algorithm)),
        Entry("hex", PvString(digest.toHex())),
    ),
)

private fun digestFromValue(value: PortableValue, path: String): ContentDigest {
    val fields = exactFields(value, listOf("algorithm", "hex"), path)
    if (stringOf(fields[0], "$path.algorithm") != "sha256") {
        throw invalid("$path.algorithm", "expected sha256")
    }
    val hex = stringOf(fields[1], "$path.hex")
    if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
        throw invalid("$path.hex", "expected 64 lowercase hexadecimal characters")
    }
    val bytes = decodeHex(hex) ?: throw invalid("$path.hex", "invalid hex")
    return ContentDigest.fromBytes(bytes)
}

/** Encodes `core.source-snapshot@1` (source_snapshot_value, source.rs). */
private fun sourceSnapshotValue(snapshot: SourceSnapshot): PortableValue = PvObject(
    listOf(
        Entry("schema", PvString("core.source-snapshot@1")),
        Entry("raw_bytes", PvBytes.of(snapshot.bytes())),
        Entry("digest", digestValue(snapshot.digest)),
        Entry("encoding", v1EncodingFactsValue(snapshot.encodingFacts)),
        Entry(
            "decoded_status",
            PvString(if (snapshot.decodedText() != null) "Available" else "NotText"),
        ),
    ),
)

/** Strictly decodes and re-verifies `core.source-snapshot@1`
 * (source_snapshot_from_value, source.rs). */
private fun sourceSnapshotFromValue(
    value: PortableValue,
    limits: SourceLimits,
): SourceSnapshot {
    val fields = schemaFields(
        value,
        "core.source-snapshot@1",
        listOf("schema", "raw_bytes", "digest", "encoding", "decoded_status"),
        "$",
    )
    val raw = fields[1] as? PvBytes
        ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$.raw_bytes", "expected Bytes")
    val claimedDigest = digestFromValue(fields[2], "$.digest")
    val claimedEncoding = v1EncodingFactsFromValue(fields[3], "$.encoding")
    val decodedStatus = stringOf(fields[4], "$.decoded_status")
    if (decodedStatus != "Available" && decodedStatus != "NotText") {
        throw invalid("$.decoded_status", "expected Available or NotText")
    }
    val snapshot = try {
        SourceSnapshot.fromRaw(raw.content(), requestFromFacts(claimedEncoding), limits)
    } catch (e: SourceException) {
        throw sourceErrorToProtocol(e)
    }
    if (snapshot.digest != claimedDigest) {
        throw invalid("$.digest", "digest does not match raw_bytes")
    }
    if (!sameFacts(snapshot.encodingFacts, claimedEncoding)) {
        throw invalid("$.encoding", "encoding facts do not match raw_bytes resolution")
    }
    val actualStatus = if (snapshot.decodedText() != null) "Available" else "NotText"
    if (decodedStatus != actualStatus) {
        throw invalid("$.decoded_status", "decoded status contradicts selected encoding")
    }
    return snapshot
}

/** Encodes `core.source-patch@1` (source_patch_value, source.rs). */
private fun sourcePatchValue(patch: SourcePatch): PortableValue = PvObject(
    listOf(
        Entry("schema", PvString("core.source-patch@1")),
        Entry("base_digest", digestValue(patch.baseDigest)),
        Entry("target_digest", digestValue(patch.targetDigest)),
        Entry("encoding", v1EncodingFactsValue(patch.encodingFacts)),
        Entry(
            "replacements",
            PvArray(
                patch.replacements().map { replacement ->
                    PvObject(
                        listOf(
                            Entry("old_start", integerValue(replacement.oldStart.toLong().toULong())),
                            Entry("old_end", integerValue(replacement.oldEnd.toLong().toULong())),
                            Entry("original", PvBytes.of(replacement.original())),
                            Entry("replacement", PvBytes.of(replacement.replacement())),
                            Entry("redact_original", PvBoolean(replacement.redactOriginal)),
                            Entry("redact_replacement", PvBoolean(replacement.redactReplacement)),
                        ),
                    )
                },
            ),
        ),
        Entry("metadata", stringMapObject(patch.metadata())),
    ),
)

/** Strictly decodes `core.source-patch@1` structural facts
 * (source_patch_from_value, source.rs). */
private fun sourcePatchFromValue(
    value: PortableValue,
    limits: SourcePatchLimits,
): SourcePatch {
    val fields = schemaFields(
        value,
        "core.source-patch@1",
        listOf("schema", "base_digest", "target_digest", "encoding", "replacements", "metadata"),
        "$",
    )
    val baseDigest = digestFromValue(fields[1], "$.base_digest")
    val targetDigest = digestFromValue(fields[2], "$.target_digest")
    val encoding = v1EncodingFactsFromValue(fields[3], "$.encoding")
    val replacementValues = sequenceOf(fields[4], "$.replacements")
    if (replacementValues.size > limits.maxReplacements) {
        throw resource("$.replacements", "replacement count exceeds configured limit")
    }
    val replacements = replacementValues.mapIndexed { index, item ->
        val path = "$.replacements[$index]"
        val entry = exactFields(
            item,
            listOf(
                "old_start", "old_end", "original", "replacement",
                "redact_original", "redact_replacement",
            ),
            path,
        )
        val oldStart = unsigned64(entry[0], "$path.old_start").toInt()
        val oldEnd = unsigned64(entry[1], "$path.old_end").toInt()
        val original = entry[2] as? PvBytes
            ?: throw protocolError(ProtocolErrorKind.WRONG_TYPE, "$path.original", "expected Bytes")
        val replacement = entry[3] as? PvBytes
            ?: throw protocolError(
                ProtocolErrorKind.WRONG_TYPE,
                "$path.replacement",
                "expected Bytes",
            )
        val redactOriginal = booleanOf(entry[4], "$path.redact_original")
        val redactReplacement = booleanOf(entry[5], "$path.redact_replacement")
        if (oldStart > oldEnd || original.content().size != oldEnd - oldStart) {
            throw invalid(path, "invalid replacement range or original length")
        }
        SourceReplacement.new(oldStart, oldEnd, original.content(), replacement.content())
            .withOriginalRedacted(redactOriginal)
            .withReplacementRedacted(redactReplacement)
    }
    val metadata = stringMapFromObject(fields[5], "$.metadata")
    return try {
        SourcePatch.new(baseDigest, targetDigest, encoding, replacements, metadata, limits)
    } catch (e: SourcePatchException) {
        throw patchErrorToProtocol(e)
    }
}

/** The Rust source_error mapping (source.rs): resource limits
 * surface as the protocol resource-limit code, everything else as
 * invalid-value. */
private fun sourceErrorToProtocol(e: SourceException): ProtocolException =
    if (e.kind == SourceErrorKind.RESOURCE_LIMIT || e.kind == SourceErrorKind.OFFSET_OVERFLOW) {
        resource("$.raw_bytes", e.message ?: "source failure")
    } else {
        invalid("$.raw_bytes", e.message ?: "source failure")
    }

/** The Rust patch_error mapping (source.rs). */
private fun patchErrorToProtocol(e: SourcePatchException): ProtocolException =
    if (e.kind == SourcePatchErrorKind.SOURCE_RESOURCE_LIMIT) {
        resource("$.replacements", e.message ?: "patch failure")
    } else {
        invalid("$.replacements", e.message ?: "patch failure")
    }

/** Rebuilds one object value with one named field replaced
 * (protocol_v2.rs replace_field). */
private fun replaceField(value: PortableValue, target: String, replacement: PortableValue): PortableValue {
    val fields = value as? PvObject ?: fail("value must be Object")
    return PvObject(
        fields.entries().map { entry ->
            if (entry.key == target) Entry(entry.key, replacement) else entry
        },
    )
}

// ---------------------------------------------------------------------------
// Vector input helpers.
// ---------------------------------------------------------------------------

private fun source(case: CaseData, field: String): SourceSnapshot {
    val bytes = decodeHex(inputString(case, field) ?: fail("missing input.$field"))
        ?: fail("invalid hex")
    val encoding = when (inputString(case, "encoding")) {
        "utf-8" -> SourceEncoding.Utf8
        "utf-16le" -> SourceEncoding.Utf16Le
        "utf-16be" -> SourceEncoding.Utf16Be
        "latin-1" -> SourceEncoding.Latin1
        "binary" -> SourceEncoding.Binary
        else -> fail("unknown encoding")
    }
    return try {
        SourceSnapshot.fromRaw(bytes, EncodingRequest.new(encoding), SourceLimits.default)
    } catch (e: SourceException) {
        fail("source: ${e.code}")
    }
}

private fun createPatch(case: CaseData, base: SourceSnapshot): SourcePatch {
    val replacements = (inputSequence(case, "replacements")
        ?: fail("missing input.replacements")).map { value ->
        val fields = value as? PvObject ?: fail("replacement must be Object")
        val oldStart = (fields.get("old_start") as? PvInteger)?.value?.toInt()
            ?: fail("missing old_start")
        val oldEnd = (fields.get("old_end") as? PvInteger)?.value?.toInt()
            ?: fail("missing old_end")
        val original = decodeHex((fields.get("original_hex") as? PvString)?.value
            ?: fail("missing original_hex")) ?: fail("invalid original_hex")
        val replacement = decodeHex((fields.get("replacement_hex") as? PvString)?.value
            ?: fail("missing replacement_hex")) ?: fail("invalid replacement_hex")
        SourceReplacement.new(oldStart, oldEnd, original, replacement)
    }
    return try {
        SourcePatch.create(
            base,
            replacements,
            mapOf("actor" to "protocol-v2"),
            SourcePatchLimits.default,
        )
    } catch (e: SourcePatchException) {
        fail("patch: ${e.code}")
    }
}
