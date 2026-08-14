// The `consema.source.conformance@1` suite runner
// (conformance/vectors/source-v1.json).
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-conformance/src/source_v1.rs (the per-case
// dispatch is transcribed from the Rust handlers; the frozen failure-code
// mappings come from source_error_code at source_v1.rs, the
// location-error spellings from location_error_name at source_v1.rs,
// and the patch-mode table from patch_case at source_v1.rs); the
// vector file itself drives every input and expectation (conformance/README.md
// rules 3-4). consema-go/go/conformance/source_v1.go is a cross-reference only.

package consema.conformance

import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.document.BinaryRegion
import consema.document.BinaryStructuralIndex
import consema.document.ContentDigest
import consema.document.DecodedOffset
import consema.document.DocumentAuthority
import consema.document.EncodingRequest
import consema.document.LocationErrorKind
import consema.document.LocationException
import consema.document.NodeRole
import consema.document.ParseLimits
import consema.document.SourceEncoding
import consema.document.SourceException
import consema.document.SourceLimits
import consema.document.SourcePatch
import consema.document.SourcePatchException
import consema.document.SourcePatchLimits
import consema.document.SourceReplacement
import consema.document.SourceSnapshot
import consema.json.Document
import consema.json.JsonProfile
import consema.json.parse

/** Runs the `consema.source.conformance@1` suite. */
fun runSourceV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runSourceV1Case(case)
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

private fun runSourceV1Case(case: CaseData) {
    when {
        case.id == "source.digest.sha256-empty" || case.id == "source.digest.sha256-abc" ->
            digestCase(case)
        case.id == "source.identity.equal-bytes-distinct-snapshots" -> identityCase(case)
        case.id.startsWith("source.encoding.") -> encodingCase(case)
        case.id.startsWith("source.location.") -> locationCase(case)
        case.id.startsWith("source.binary.") -> binaryCase(case)
        case.id.startsWith("source.patch.") -> patchCase(case)
        case.id.startsWith("source.resource.") -> resourceCase(case)
        else -> fail("runner does not recognize published source case")
    }
}

/** source.digest.*: SHA-256 over the exact raw bytes (source_v1.rs). */
private fun digestCase(case: CaseData) {
    val raw = rawHex(case, "raw_hex")
    val expected = expectedString(case, "digest") ?: fail("missing expected.digest")
    ensure(ContentDigest.of(raw).toHex() == expected)
}

/** source.identity.*: equal digests and distinct snapshot identities for
 * two parses of the same bytes (source_v1.rs). */
private fun identityCase(case: CaseData) {
    val raw = rawHex(case, "raw_hex")
    val first = parseJsonSnapshot(raw)
    val second = parseJsonSnapshot(raw)
    val equalDigest = expectedBoolean(case, "equal_digest") ?: fail("missing expected.equal_digest")
    val distinctSnapshot =
        expectedBoolean(case, "distinct_snapshot") ?: fail("missing expected.distinct_snapshot")
    ensure(
        (first.source().digest == second.source().digest) == equalDigest &&
            (first.snapshotIdentity != second.snapshotIdentity) == distinctSnapshot,
    )
}

/** source.encoding.*: explicit encoding resolution, byte retention, and the
 * frozen rejection codes (source_v1.rs). */
private fun encodingCase(case: CaseData) {
    val raw = rawHex(case, "raw_hex")
    val snapshot = try {
        SourceSnapshot.fromRaw(raw, encodingRequest(case), SourceLimits.default)
    } catch (e: SourceException) {
        ensure(e.code == expectedString(case, "code") ?: fail("missing expected.code"))
        return
    }
    val expectedRaw = expectedString(case, "raw_hex") ?: fail("missing expected.raw_hex")
    val expectedSelected = expectedString(case, "selected") ?: fail("missing expected.selected")
    val expectedDecoded = caseExpected(case, "decoded_utf8_hex")
    val decodedMatches = if (expectedDecoded == null || expectedDecoded is PvNull) {
        snapshot.decodedText() == null
    } else {
        val text = (expectedDecoded as? PvString)?.value
            ?: fail("expected.decoded_utf8_hex must be String or Null")
        toHex(
            snapshot.decodedText()
                ?.toByteArray(Charsets.UTF_8)
                ?: fail("decoded text unavailable"),
        ) == text
    }
    ensure(
        snapshot.bytes().contentEquals(raw) &&
            toHex(snapshot.bytes()) == expectedRaw &&
            snapshot.encodingFacts.selected.asStr() == expectedSelected &&
            decodedMatches,
    )
}

/** source.location.*: decoded boundary mapping and the frozen
 * location-error spellings (source_v1.rs). */
private fun locationCase(case: CaseData) {
    val snapshot = try {
        SourceSnapshot.fromRaw(rawHex(case, "raw_hex"), encodingRequest(case), SourceLimits.default)
    } catch (e: SourceException) {
        fail("snapshot: ${e.code}")
    }
    if (snapshot.decodedText() == null) {
        val failure = try {
            snapshot.decodedPosition(0)
            null
        } catch (e: LocationException) {
            e.kind.name
        }
        ensure(failure == expectedString(case, "code") ?: fail("missing expected.code"))
        return
    }
    val rawByte = longField(case.input, "raw_byte")?.toInt() ?: fail("missing input.raw_byte")
    val position = try {
        snapshot.decodedPosition(rawByte)
    } catch (e: LocationException) {
        fail("decoded_position: ${e.kind.name}")
    }
    val reverseUtf8 = try {
        snapshot.rawByteAt(DecodedOffset.Utf8Byte(position.decodedUtf8Byte))
    } catch (e: LocationException) {
        fail("raw_byte_at: ${e.kind.name}")
    }
    val reverseScalar = try {
        snapshot.rawByteAt(DecodedOffset.UnicodeScalar(position.unicodeScalarOffset))
    } catch (e: LocationException) {
        fail("raw_byte_at: ${e.kind.name}")
    }
    val reverseUtf16 = try {
        snapshot.rawByteAt(DecodedOffset.Utf16CodeUnit(position.utf16CodeUnitOffset))
    } catch (e: LocationException) {
        fail("raw_byte_at: ${e.kind.name}")
    }
    val invalidRaw = longField(case.input, "invalid_raw_byte")?.toInt()
        ?: fail("missing input.invalid_raw_byte")
    val invalidUtf16 = longField(case.input, "invalid_utf16_offset")?.toInt()
        ?: fail("missing input.invalid_utf16_offset")
    val notBoundary = try {
        snapshot.decodedPosition(invalidRaw)
        null
    } catch (e: LocationException) {
        e.kind
    }
    val notDecodedBoundary = try {
        snapshot.rawByteAt(DecodedOffset.Utf16CodeUnit(invalidUtf16))
        null
    } catch (e: LocationException) {
        e.kind
    }
    ensure(
        position.decodedUtf8Byte.toLong() == expectedLong(case, "decoded_utf8_byte") &&
            position.unicodeScalarOffset.toLong() == expectedLong(case, "unicode_scalar_offset") &&
            position.utf16CodeUnitOffset.toLong() == expectedLong(case, "utf16_code_unit_offset") &&
            reverseUtf8 == rawByte &&
            reverseScalar == rawByte &&
            reverseUtf16 == rawByte &&
            notBoundary == LocationErrorKind.NotDecodedBoundary &&
            notDecodedBoundary == LocationErrorKind.DecodedOffsetNotBoundary,
    )
}

/** source.binary.*: exhaustive ordered region coverage with the frozen
 * location-error spellings (source_v1.rs). */
private fun binaryCase(case: CaseData) {
    val sourceLen = longField(case.input, "source_len")?.toInt() ?: fail("missing input.source_len")
    val authority = DocumentAuthority.fresh()
    val regionValues = inputSequence(case, "regions") ?: fail("missing input.regions")
    val regions = ArrayList<BinaryRegion>(regionValues.size)
    for ((index, value) in regionValues.withIndex()) {
        val fields = value as? PvObject ?: fail("region must be an Object")
        val start = (fields.get("start") as? PvInteger)?.value?.toInt()
            ?: fail("region.start must be a non-negative host-size Integer")
        val end = (fields.get("end") as? PvInteger)?.value?.toInt()
            ?: fail("region.end must be a non-negative host-size Integer")
        val kind = (fields.get("kind") as? PvString)?.value ?: fail("region.kind must be String")
        val span = try {
            authority.span(start, end)
        } catch (e: LocationException) {
            fail("span: ${e.kind.name}")
        }
        regions.add(
            BinaryRegion(
                authority.nodeRef(index.toLong(), NodeRole.BinaryRegion),
                span,
                kind,
            ),
        )
    }
    try {
        val index = BinaryStructuralIndex.new(authority.identity, sourceLen, regions)
        val regionCount = expectedLong(case, "region_count") ?: fail("missing expected.region_count")
        ensure(
            index.regions().size.toLong() == regionCount &&
                (index.regions().lastOrNull()?.span?.endByte ?: 0) == sourceLen,
        )
    } catch (e: LocationException) {
        ensure(e.kind.name == expectedString(case, "code") ?: fail("missing expected.code"))
    }
}

/** source.patch.*: verifiable raw-byte patches with the frozen patch-mode
 * table and rejection codes (source_v1.rs). */
private fun patchCase(case: CaseData) {
    val mode = inputString(case, "mode") ?: fail("missing input.mode")
    val base = sourceFromCase(case, "base_hex")
    val replacements = replacementValues(case)
    val limits = patchLimits(case)
    when (mode) {
        "create-apply" -> {
            val patch = createPatch(base, replacements, limits)
            val target = try {
                patch.apply(base, limits)
            } catch (e: SourcePatchException) {
                fail("patch: ${e.code}")
            }
            ensure(
                toHex(target.bytes()) == expectedString(case, "target_hex") &&
                    target.digest == patch.targetDigest &&
                    patch.metadata()["actor"] == "conformance",
            )
        }
        "stale-base" -> {
            val patch = createPatch(base, replacements, limits)
            val stale = try {
                SourceSnapshot.fromRaw(
                    rawHex(case, "stale_hex"),
                    encodingRequest(case),
                    SourceLimits.default,
                )
            } catch (e: SourceException) {
                fail("source: ${e.code}")
            }
            expectPatchError(case) { patch.apply(stale, limits) }
        }
        "wrong-original" -> {
            val patch = newPatch(
                base,
                ContentDigest.of(rawHex(case, "target_hex")),
                replacements,
                limits,
            )
            expectPatchError(case) { patch.apply(base, limits) }
        }
        "overlap", "count-limit" -> expectPatchError(case) {
            SourcePatch.create(base, replacements, metadata(), limits)
        }
        "wrong-target" -> {
            val patch = newPatch(
                base,
                ContentDigest.of("deliberately-wrong-target".toByteArray(Charsets.UTF_8)),
                replacements,
                limits,
            )
            expectPatchError(case) { patch.apply(base, limits) }
        }
        "encoding-change" -> {
            val patch = newPatch(
                base,
                ContentDigest.of(rawHex(case, "target_hex")),
                replacements,
                limits,
            )
            expectPatchError(case) { patch.apply(base, limits) }
        }
        else -> fail("unknown patch mode $mode")
    }
}

/** source.resource.*: construction and patch bounds with the frozen
 * resource-limit code (source_v1.rs). */
private fun resourceCase(case: CaseData) {
    if (case.id == "source.resource.patch-count-limit") {
        patchCase(case)
        return
    }
    val raw = rawHex(case, "raw_hex")
    var limits = SourceLimits.default
    longField(case.input, "max_raw_bytes")?.let { limits = limits.copy(maxRawBytes = it.toInt()) }
    longField(case.input, "max_decoded_utf8_bytes")?.let {
        limits = limits.copy(maxDecodedUtf8Bytes = it.toInt())
    }
    longField(case.input, "max_decoded_scalars")?.let {
        limits = limits.copy(maxDecodedScalars = it.toInt())
    }
    val code = try {
        SourceSnapshot.fromRaw(raw, encodingRequest(case), limits)
        null
    } catch (e: SourceException) {
        e.code
    }
    ensure(code == expectedString(case, "code") ?: fail("missing expected.code"))
}

private fun sourceFromCase(case: CaseData, name: String): SourceSnapshot =
    try {
        SourceSnapshot.fromRaw(rawHex(case, name), encodingRequest(case), SourceLimits.default)
    } catch (e: SourceException) {
        fail("source: ${e.code}")
    }

private fun createPatch(
    base: SourceSnapshot,
    replacements: List<SourceReplacement>,
    limits: SourcePatchLimits,
): SourcePatch =
    try {
        SourcePatch.create(base, replacements, metadata(), limits)
    } catch (e: SourcePatchException) {
        fail("patch: ${e.code}")
    }

private fun newPatch(
    base: SourceSnapshot,
    targetDigest: ContentDigest,
    replacements: List<SourceReplacement>,
    limits: SourcePatchLimits,
): SourcePatch =
    try {
        SourcePatch.new(
            base.digest,
            targetDigest,
            base.encodingFacts,
            replacements,
            metadata(),
            limits,
        )
    } catch (e: SourcePatchException) {
        fail("patch: ${e.code}")
    }

private fun expectPatchError(case: CaseData, block: () -> Unit) {
    val code = try {
        block()
        null
    } catch (e: SourcePatchException) {
        e.code
    }
    ensure(code == expectedString(case, "code") ?: fail("missing expected.code"))
}

private fun replacementValues(case: CaseData): List<SourceReplacement> {
    val values = inputSequence(case, "replacements") ?: fail("missing input.replacements")
    return values.map { value ->
        val fields = value as? PvObject ?: fail("replacement must be an Object")
        val oldStart = (fields.get("old_start") as? PvInteger)?.value?.toInt()
            ?: fail("replacement.old_start must be a non-negative host-size Integer")
        val oldEnd = (fields.get("old_end") as? PvInteger)?.value?.toInt()
            ?: fail("replacement.old_end must be a non-negative host-size Integer")
        SourceReplacement.new(
            oldStart,
            oldEnd,
            decodeHex(
                (fields.get("original_hex") as? PvString)?.value
                    ?: fail("replacement.original_hex must be String"),
            ) ?: fail("invalid replacement.original_hex"),
            decodeHex(
                (fields.get("replacement_hex") as? PvString)?.value
                    ?: fail("replacement.replacement_hex must be String"),
            ) ?: fail("invalid replacement.replacement_hex"),
        )
    }
}

private fun patchLimits(case: CaseData): SourcePatchLimits {
    var limits = SourcePatchLimits.default
    longField(case.input, "max_replacements")?.let {
        limits = limits.copy(maxReplacements = it.toInt())
    }
    longField(case.input, "max_patch_bytes")?.let { limits = limits.copy(maxPatchBytes = it.toInt()) }
    return limits
}

private fun metadata(): Map<String, String> = mapOf("actor" to "conformance")

private fun encodingRequest(case: CaseData): EncodingRequest {
    var request = EncodingRequest.new(
        parseEncoding(inputString(case, "encoding") ?: fail("missing input.encoding")),
    )
    inputString(case, "declaration")?.let { request = request.withDeclaration(parseEncoding(it)) }
    inputString(case, "caller_override")?.let {
        request = request.withCallerOverride(parseEncoding(it))
    }
    return request
}

private fun parseEncoding(value: String): SourceEncoding =
    when (value) {
        "binary" -> SourceEncoding.Binary
        "utf-8" -> SourceEncoding.Utf8
        "utf-16le" -> SourceEncoding.Utf16Le
        "utf-16be" -> SourceEncoding.Utf16Be
        "latin-1" -> SourceEncoding.Latin1
        else -> fail("unknown encoding $value")
    }

private fun rawHex(case: CaseData, name: String): ByteArray =
    decodeHex(inputString(case, name) ?: fail("missing input.$name")) ?: fail("invalid input.$name")

private fun parseJsonSnapshot(bytes: ByteArray): Document =
    try {
        parse(bytes, JsonProfile.StrictV1, ParseLimits.default)
    } catch (e: Exception) {
        fail("parse failed: ${e.message}")
    }

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}
