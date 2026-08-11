// The `consema.conformance@1` suite runner (conformance/vectors/v1.json).
//
// Data authority: crates/consema-conformance/src/lib.rs:217-1049 (the
// per-case dispatch is transcribed from the Rust handlers); the vector file
// itself drives every input and expectation (conformance/README.md rules
// 3-4). go/conformance/v1.go is a cross-reference only.

package consema.conformance

import consema.core.Kind
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.core.PvceErrorKind
import consema.core.PvceException
import consema.core.decodePvce
import consema.core.encodePvce
import consema.core.encodePvceBounded
import consema.core.equal
import consema.core.hash
import consema.core.DecodeLimits
import consema.core.EncodeLimits
import consema.document.FormationStatus
import consema.document.ParseLimits
import consema.document.ProfileId
import consema.json.Document
import consema.json.DuplicateKeyPolicy
import consema.json.EditFailure
import consema.json.EditFailureException
import consema.json.EditTransactionBuilder
import consema.json.JsonMatch
import consema.json.JsonProfile
import consema.json.ProjectionRequest
import consema.json.ProjectionResult
import consema.json.ProjectionTarget
import consema.json.RepresentationPolicy
import consema.json.SemanticAvailability
import consema.json.commit
import consema.json.executeJsonQuery
import consema.json.parse
import consema.json.project
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.ExecutableQuery
import consema.protocol.OperatorCall
import consema.protocol.QueryDomain
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind
import consema.protocol.QuerySelection
import java.math.BigInteger

/** Runs the `consema.conformance@1` suite. */
fun runV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runV1Case(case)
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

/** The typed case failure; the message is the failure description. */
class CaseFailureException(message: String) : Exception(message)

private fun runV1Case(case: CaseData) {
    when (case.id) {
        "value.integer-arbitrary-precision" -> {
            val text = inputString(case, "decimal") ?: fail("missing input.decimal")
            val expected = expectedString(case, "decimal") ?: fail("missing expected.decimal")
            val parsed = try {
                BigInteger(text)
            } catch (e: NumberFormatException) {
                fail("malformed input.decimal")
            }
            ensure(parsed.toString() == expected)
        }
        "value.decimal-normalization" -> {
            val left = decimalField(case, "left")
            val right = decimalField(case, "right")
            val strictEqual = expectedBoolean(case, "strict_equal") ?: fail("missing expected.strict_equal")
            val hashEqual = expectedBoolean(case, "strict_hash_equal") ?: fail("missing expected.strict_hash_equal")
            ensure(
                (equal(left, right) == strictEqual) &&
                    (hash(left) == hash(right)) == hashEqual,
            )
        }
        "value.float-signed-zero" -> {
            val positive = hexBits(case, "positive_bits")
            val negative = hexBits(case, "negative_bits")
            val strictEqual = expectedBoolean(case, "strict_equal") ?: fail("missing expected.strict_equal")
            ensure(
                equal(
                    PvBinaryFloat64(positive),
                    PvBinaryFloat64(negative),
                ) == strictEqual,
            )
        }
        "pvce.null-vector" -> {
            val expected = expectedHex(case)
            ensure(toHex(encodePvce(PvNull)) == expected)
        }
        "pvce.negative-integer-vector" -> {
            val text = inputString(case, "integer") ?: fail("missing input.integer")
            val expected = expectedHex(case)
            ensure(toHex(encodePvce(PvInteger(BigInteger(text)))) == expected)
        }
        "pvce.object-vector" -> {
            val objectValue = valueFromInput(caseInput(case, "object")) ?: fail("unrepresentable input.object")
            val expected = expectedHex(case)
            ensure(toHex(encodePvce(objectValue)) == expected)
        }
        "pvce.reject-nonminimal-varint" -> {
            val bytes = inputHex(case)
            val failure = try {
                decodePvce(bytes, DecodeLimits.default)
                null
            } catch (e: PvceException) {
                e.kind
            }
            ensure(failure == PvceErrorKind.NON_CANONICAL_VARINT)
        }
        "pvce.encode-blob-limit" -> {
            val value = valueFromInput(caseInput(case, "value")) ?: fail("unrepresentable input.value")
            val limit = intField(case, "max_blob_bytes")
            val limits = EncodeLimits(
                maxBytes = Int.MAX_VALUE,
                maxDepth = Int.MAX_VALUE,
                maxNodes = Int.MAX_VALUE,
                maxContainerEntries = Int.MAX_VALUE,
                maxIntegerBytes = Int.MAX_VALUE,
                maxBlobBytes = limit,
            )
            val failure = try {
                encodePvceBounded(value, limits)
                null
            } catch (e: PvceException) {
                e.kind
            }
            ensure(failure == PvceErrorKind.RESOURCE_LIMIT)
        }
        "parse.strict-exact-roundtrip", "parse.jsonc-comments-trailing-comma" ->
            parseExactCase(case)
        "parse.recovery-missing-close" -> {
            val (source, profile) = parseInputs(case)
            val document = parseDocument(source, profile)
            val formation = expectedString(case, "formation") ?: fail("missing expected.formation")
            val diagnostic = expectedString(case, "diagnostic") ?: fail("missing expected.diagnostic")
            ensure(
                formationStatusName(document.formationStatus()) == formation &&
                    document.diagnostics().any { it.code == diagnostic },
            )
        }
        "parse.duplicate-members" -> duplicateMembers(case)
        "parse.lossless-byte-coverage" -> losslessCoverage(case)
        "query.reject-role-mismatch" -> {
            val pipeline = pipeline(case) ?: fail("missing input.pipeline")
            val failure = try {
                consema.protocol.QueryDefinition(QueryDomain("core.portable-value-query", 1))
                    .withExpression(pipeline)
                    .validate()
                null
            } catch (e: QueryFailureException) {
                e.kind
            }
            ensure(failure == QueryFailureKind.INVALID_OPERATOR_COMPOSITION)
        }
        "query.json-duplicate-order" -> queryDuplicateOrder(case)
        "query.root-result-limit" -> {
            val maxResults = intField(case, "max_results")
            val definition = consema.protocol.QueryDefinition(QueryDomain("core.portable-value-query", 1))
            val executable = definition.validate().let { ExecutableQuery.bind(it, portableCapabilities()) }
            val failure = try {
                executePortableQuery(executable, PvNull, PortableQueryLimits(defaultSteps, maxResults))
                null
            } catch (e: QueryFailureException) {
                e.kind
            }
            ensure(failure == QueryFailureKind.RESOURCE_LIMIT)
        }
        "query.cursor-failure-terminal" -> {
            val elements = inputSequence(case, "elements") ?: fail("missing input.elements")
            val values = elements.map { valueFromInput(it) ?: fail("unrepresentable element") }
            val maxResults = intField(case, "max_results")
            val expression = QueryExpression(consema.protocol.ExpressionKind.Input)
                .then(OperatorCall("core.try-sequence-elements", 1))
            val definition = consema.protocol.QueryDefinition(QueryDomain("core.portable-value-query", 1))
                .withExpression(expression)
            val executable = definition.validate().let { ExecutableQuery.bind(it, portableCapabilities()) }
            val input = consema.core.PvArray(values)
            val cursor = executePortableCursor(executable, input, PortableQueryLimits(defaultSteps, maxResults))
            var yielded = 0
            var limitFailure = false
            while (true) {
                try {
                    cursor.nextMatch() ?: break
                    yielded += 1
                } catch (e: QueryFailureException) {
                    if (e.kind == QueryFailureKind.RESOURCE_LIMIT) {
                        limitFailure = true
                        break
                    }
                    fail("unexpected failure: ${e.kind.code}")
                }
            }
            val expectedYielded = expectedLong(case, "yielded_before_failure")
                ?: fail("missing expected.yielded_before_failure")
            val terminal = expectedString(case, "terminal") ?: fail("missing expected.terminal")
            ensure(
                limitFailure &&
                    yielded.toLong() == expectedYielded &&
                    cursor.terminalState().name == terminal,
            )
        }
        "query.protocol-roundtrip" -> {
            val domainName = inputString(case, "domain") ?: fail("missing input.domain")
            val domain = when (domainName) {
                "core.portable-value-query@1" -> QueryDomain("core.portable-value-query", 1)
                else -> fail("unknown domain $domainName")
            }
            val operator = inputString(case, "operator") ?: fail("missing input.operator")
            val selection = when (inputString(case, "selection")) {
                "All" -> QuerySelection.All
                "First" -> QuerySelection.First
                "Last" -> QuerySelection.Last
                "ZeroOrOne" -> QuerySelection.ZeroOrOne
                "RequireOne" -> QuerySelection.RequireOne
                else -> fail("unknown selection")
            }
            val (name, version) = operator.splitOnce('@') ?: fail("descriptor lacks version: $operator")
            val definition = consema.protocol.QueryDefinition(domain)
                .withExpression(
                    QueryExpression(consema.protocol.ExpressionKind.Input)
                        .then(OperatorCall(name, version.toInt())),
                )
                .withSelection(selection)
            val value = definition.toProtocolValue()
            val decoded = consema.protocol.QueryDefinition.fromProtocolValue(value)
            val equalRoundtrip = equal(decoded.toProtocolValue(), value)
            val unknownRejected = try {
                val objectValue = value as? consema.core.PvObject ?: fail("object schema")
                val entries = objectValue.entries() + consema.core.Entry("unknown", PvNull)
                consema.protocol.QueryDefinition.fromProtocolValue(consema.core.PvObject(entries))
                false
            } catch (e: Exception) {
                true
            }
            ensure(equalRoundtrip && unknownRejected)
        }
        "projection.best-exact-duplicate-mapping" -> projectionBest(case)
        "projection.object-reject-duplicates" -> projectionReject(case)
        "projection.object-last-wins" -> projectionLast(case)
        "projection.object-key-provenance" -> projectionKeyProvenance(case)
        "edit.scalar-minimal",
        "edit.preserve-decimal-scale",
        "edit.preserve-exponent-style",
        "edit.canonical-for-profile",
        "edit.preserve-else-canonical",
        -> editSemantic(case)
        "edit.preserve-incompatible-rejected" -> editIncompatible(case)
        "edit.wrong-snapshot" -> editWrongSnapshot(case)
        "resource.parse-token-limit" -> {
            val source = inputString(case, "source") ?: fail("missing input.source")
            val limit = intField(case, "max_token_count")
            val limits = ParseLimits(
                maxTokenCount = limit,
                maxSourceBytes = ParseLimits.default.maxSourceBytes,
                maxNodeCount = ParseLimits.default.maxNodeCount,
                maxNestingDepth = ParseLimits.default.maxNestingDepth,
                maxDiagnostics = ParseLimits.default.maxDiagnostics,
            )
            val failure = try {
                parse(source.toByteArray(Charsets.UTF_8), JsonProfile.StrictV1, limits)
                null
            } catch (e: Exception) {
                e
            }
            ensure(failure != null)
        }
        else -> fail("runner does not recognize published case")
    }
}

private const val defaultSteps = 100_000

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}

private fun portableCapabilities(): CapabilitySet {
    val set = CapabilitySet()
    set.insert(CapabilityId("core.query.ordered-results", 1))
    return set
}

private fun parseDocument(source: String, profile: JsonProfile): Document =
    try {
        parse(source.toByteArray(Charsets.UTF_8), profile, ParseLimits.default)
    } catch (e: Exception) {
        fail("parse failed: ${e.message}")
    }

private fun parseInputs(case: CaseData): Pair<String, JsonProfile> {
    val source = inputString(case, "source") ?: fail("missing input.source")
    val profile = when (inputString(case, "profile")) {
        "json.strict@1" -> JsonProfile.StrictV1
        "jsonc.bounded@1" -> JsonProfile.JsoncBoundedV1
        else -> fail("unknown profile")
    }
    return source to profile
}

private fun parseExactCase(case: CaseData) {
    val (source, profile) = parseInputs(case)
    val document = parseDocument(source, profile)
    val formation = expectedString(case, "formation") ?: fail("missing expected.formation")
    val renderEquals = expectedBoolean(case, "render_equals_source") ?: fail("missing expected.render_equals_source")
    ensure(
        formationStatusName(document.formationStatus()) == formation &&
            (document.render().contentEquals(source.toByteArray(Charsets.UTF_8))) == renderEquals,
    )
}

private fun formationStatusName(status: FormationStatus): String =
    when (status) {
        FormationStatus.Complete -> "Complete"
        FormationStatus.Recovered -> "Recovered"
    }

private fun duplicateMembers(case: CaseData) {
    val (source, profile) = parseInputs(case)
    val document = parseDocument(source, profile)
    val members = when (val availability = document.root().objectMembers()) {
        is SemanticAvailability.Available -> availability.value
        is SemanticAvailability.Unavailable -> fail("object semantics unavailable")
    } ?: fail("object semantics unavailable")
    val expectedNames = expectedSequence(case, "member_names") ?: fail("missing expected.member_names")
    val distinct = expectedBoolean(case, "distinct_member_identity") ?: fail("missing expected.distinct_member_identity")
    val diagnostic = expectedString(case, "diagnostic") ?: fail("missing expected.diagnostic")
    val names = members.map { member ->
        when (val name = member.name()) {
            is SemanticAvailability.Available -> name.value
            is SemanticAvailability.Unavailable -> null
        }
    }
    val distinctIdentity = members.map { it.nodeRef() }.toHashSet().size == members.size
    ensure(
        names.size == expectedNames.size &&
            names.zip(expectedNames).all { (actual, expected) ->
                actual != null && (expected as? PvString)?.value == actual
            } &&
            distinctIdentity == distinct &&
            document.diagnostics().any { it.code == diagnostic },
    )
}

private fun losslessCoverage(case: CaseData) {
    val (source, profile) = parseInputs(case)
    val document = parseDocument(source, profile)
    val pieces = document.pieces()
    val gapCount = expectedLong(case, "gap_count") ?: fail("missing expected.gap_count")
    val overlapCount = expectedLong(case, "overlap_count") ?: fail("missing expected.overlap_count")
    val covered = expectedLong(case, "covered_bytes") ?: fail("missing expected.covered_bytes")
    var gaps = 0L
    var overlaps = 0L
    for (index in 1 until pieces.size) {
        if (pieces[index - 1].span.endByte < pieces[index].span.startByte) gaps += 1
        if (pieces[index - 1].span.endByte > pieces[index].span.startByte) overlaps += 1
    }
    val coveredBytes = pieces.lastOrNull()?.span?.endByte?.toLong() ?: 0L
    ensure(gaps == gapCount && overlaps == overlapCount && coveredBytes == covered)
}

private fun pipeline(case: CaseData): QueryExpression? {
    val descriptors = inputSequence(case, "pipeline") ?: return null
    var expression = QueryExpression(consema.protocol.ExpressionKind.Input)
    for (descriptor in descriptors) {
        val text = (descriptor as? PvString)?.value ?: return null
        val (name, version) = text.splitOnce('@') ?: return null
        expression = expression.then(OperatorCall(name, version.toInt()))
    }
    return expression
}

private fun String.splitOnce(separator: Char): Pair<String, String>? {
    val index = indexOf(separator)
    if (index < 0) return null
    return substring(0, index) to substring(index + 1)
}

private fun queryDuplicateOrder(case: CaseData) {
    val source = inputString(case, "source") ?: fail("missing input.source")
    val memberName = inputString(case, "member_name") ?: fail("missing input.member_name")
    val document = parseDocument(source, JsonProfile.StrictV1)
    val expression = QueryExpression(consema.protocol.ExpressionKind.Input)
        .then(OperatorCall("json.try-object-members", 1))
        .then(
            OperatorCall("json.member-name-equals", 1)
                .withArgument("name", PvString(memberName)),
        )
    val definition = consema.protocol.QueryDefinition(QueryDomain("json.native-semantic-query", 1))
        .withExpression(expression)
    val executable = try {
        definition.validate().let { ExecutableQuery.bind(it, portableCapabilities()) }
    } catch (e: QueryFailureException) {
        fail("definition: ${e.kind.code}")
    }
    val matches = try {
        executeJsonQuery(executable, document)
    } catch (e: QueryFailureException) {
        fail("query: ${e.kind.code}")
    }
    val expectedOrdinals = expectedSequence(case, "ordinals") ?: fail("missing expected.ordinals")
    val expectedCount = expectedLong(case, "count") ?: fail("missing expected.count")
    ensure(
        matches.size.toLong() == expectedCount &&
            matches.zip(expectedOrdinals).all { (item, expectedOrdinal) ->
                item is JsonMatch.ObjectMember &&
                    (expectedOrdinal as? PvInteger)?.value?.toLong() == item.ordinal.toLong()
            },
    )
}

private fun hexBits(case: CaseData, name: String): Long {
    val text = inputString(case, name) ?: fail("missing input.$name")
    val bytes = decodeHex(text) ?: fail("invalid hex")
    if (bytes.size != 8) fail("expected 8 hex bytes")
    var value = 0L
    for (byte in bytes) {
        value = (value shl 8) or (byte.toLong() and 0xff)
    }
    return value
}

private fun decimalField(case: CaseData, name: String): PortableValue {
    val text = inputString(case, name) ?: fail("missing input.$name")
    return parseDecimalJson(text) ?: fail("malformed input.$name")
}

private fun intField(case: CaseData, name: String): Int =
    (caseInput(case, name) as? PvInteger)?.value?.toInt() ?: fail("missing input.$name")

private fun expectedHex(case: CaseData): String =
    expectedString(case, "hex") ?: fail("missing expected.hex")

private fun inputHex(case: CaseData): ByteArray =
    decodeHex(inputString(case, "hex") ?: fail("missing input.hex")) ?: fail("invalid hex")

private fun projectionBest(case: CaseData) {
    val request = ProjectionRequest.builder(ProjectionTarget.BestExactCoreV1).build()
    val document = duplicateDocument(case)
    when (val result = document.project(request)) {
        is ProjectionResult.Complete -> {
            val kind = expectedString(case, "kind") ?: fail("missing expected.kind")
            val fidelity = expectedString(case, "fidelity") ?: fail("missing expected.fidelity")
            val associations = expectedLong(case, "association_origins") ?: fail("missing expected.association_origins")
            val actualKind = when {
                result.projection.value is consema.core.PvEntryMapping -> "EntryMapping"
                result.projection.value is consema.core.PvObject -> "Object"
                else -> "Other"
            }
            val actualFidelity = fidelityName(result.projection.fidelity)
            val associationCount = result.projection.provenance.entries()
                .count { it.projected is consema.json.ProjectedLocation.Association }
                .toLong()
            ensure(
                actualKind == kind &&
                    actualFidelity == fidelity &&
                    associationCount == associations,
            )
        }
        is ProjectionResult.Failed -> fail("best exact failed")
    }
}

private fun fidelityName(fidelity: consema.json.Fidelity): String =
    when (fidelity) {
        consema.json.Fidelity.Exact -> "Exact"
        consema.json.Fidelity.Transformed -> "Transformed"
        consema.json.Fidelity.Lossy -> "Lossy"
    }

private fun projectionReject(case: CaseData) {
    val request = ProjectionRequest.builder(ProjectionTarget.ProjectAsObjectV1).build()
    val result = duplicateDocument(case).project(request)
    ensure(result is ProjectionResult.Failed)
}

private fun projectionLast(case: CaseData) {
    val request = ProjectionRequest.builder(ProjectionTarget.ProjectAsObjectV1)
        .globalDuplicatePolicy(DuplicateKeyPolicy.LastWins)
        .build()
    when (val result = duplicateDocument(case).project(request)) {
        is ProjectionResult.Complete -> ensure(
            result.projection.fidelity == consema.json.Fidelity.Lossy &&
                result.projection.report.events().any {
                    it.kind == consema.json.ProjectionEventKind.DuplicateCollapsed
                },
        )
        is ProjectionResult.Failed -> fail("authorized projection failed")
    }
}

private fun projectionKeyProvenance(case: CaseData) {
    val request = ProjectionRequest.builder(ProjectionTarget.ProjectAsObjectV1).build()
    val document = duplicateDocument(case)
    val result = document.project(request)
    val projection = when (result) {
        is ProjectionResult.Complete -> result.projection
        is ProjectionResult.Failed -> fail("projection failed")
    }
    val keyOrigins = expectedLong(case, "key_association_origins") ?: fail("missing expected.key_association_origins")
    val entryOrigins = expectedLong(case, "entry_association_origins") ?: fail("missing expected.entry_association_origins")
    var keys = 0L
    var entries = 0L
    for (entry in projection.provenance.entries()) {
        val location = (entry.projected as? consema.json.ProjectedLocation.Association) ?: continue
        when (location.location.role) {
            consema.core.AssociationRole.ObjectKey -> keys += 1
            consema.core.AssociationRole.ObjectEntry -> entries += 1
            consema.core.AssociationRole.EntryMappingEntry -> {}
        }
    }
    ensure(keys == keyOrigins && entries == entryOrigins)
}

private fun duplicateDocument(case: CaseData): Document {
    val source = inputString(case, "source") ?: fail("missing input.source")
    return parseDocument(source, JsonProfile.StrictV1)
}

private fun editInputs(case: CaseData): Triple<String, JsonProfile, Pair<PortableValue?, RepresentationPolicy>> {
    val (source, profile) = parseInputs(case)
    val newValue = valueFromInput(caseInput(case, "new_value"))
    val policy = when (inputString(case, "policy")) {
        "PreserveCompatible" -> RepresentationPolicy.PreserveCompatible
        "CanonicalForProfile" -> RepresentationPolicy.CanonicalForProfile
        "PreserveElseCanonical" -> RepresentationPolicy.PreserveElseCanonical
        else -> fail("unknown policy")
    }
    return Triple(source, profile, newValue to policy)
}

private fun editSemantic(case: CaseData) {
    val (source, profile, replacement) = editInputs(case)
    val newValue = replacement.first ?: fail("missing or unrepresentable input.new_value")
    val policy = replacement.second
    val document = parseDocument(source, profile)
    val member = when (val members = document.root().objectMembers()) {
        is SemanticAvailability.Available -> members.value?.firstOrNull()
        is SemanticAvailability.Unavailable -> null
    } ?: fail("member unavailable")
    val builder = EditTransactionBuilder.new(document)
    builder.semanticScalar(member.valueNodeRef(), newValue, policy)
    val commit = try {
        document.commit(builder.build())
    } catch (e: EditFailureException) {
        fail("commit: ${e.failure.name}")
    }
    val expectedSource = expectedString(case, "source") ?: fail("missing expected.source")
    val editCount = expectedLong(case, "source_edit_count") ?: fail("missing expected.source_edit_count")
    val fallback = expectedLong(case, "fallback_diagnostics") ?: 0L
    ensure(
        commit.document.render().contentEquals(expectedSource.toByteArray(Charsets.UTF_8)) &&
            commit.sourcePatch.replacements().size.toLong() == editCount &&
            commit.diagnostics.count { it.code == "json.edit.representation-fallback@1" }.toLong() == fallback,
    )
}

private fun editIncompatible(case: CaseData) {
    val (source, profile, replacement) = editInputs(case)
    val newValue = replacement.first ?: fail("missing or unrepresentable input.new_value")
    val policy = replacement.second
    val document = parseDocument(source, profile)
    val member = when (val members = document.root().objectMembers()) {
        is SemanticAvailability.Available -> members.value?.firstOrNull()
        is SemanticAvailability.Unavailable -> null
    } ?: fail("member unavailable")
    val builder = EditTransactionBuilder.new(document)
    builder.semanticScalar(member.valueNodeRef(), newValue, policy)
    val failure = try {
        document.commit(builder.build())
        null
    } catch (e: EditFailureException) {
        e.failure
    }
    ensure(failure == EditFailure.RepresentationIncompatible)
}

private fun editWrongSnapshot(case: CaseData) {
    val firstSource = inputString(case, "first") ?: fail("missing input.first")
    val secondSource = inputString(case, "second") ?: fail("missing input.second")
    val literal = inputString(case, "literal") ?: fail("missing input.literal")
    val first = parseDocument(firstSource, JsonProfile.StrictV1)
    val second = parseDocument(secondSource, JsonProfile.StrictV1)
    val builder = EditTransactionBuilder.new(second)
    builder.literalScalar(first.root().nodeRef(), literal.toByteArray(Charsets.UTF_8))
    val failure = try {
        second.commit(builder.build())
        null
    } catch (e: EditFailureException) {
        e.failure
    }
    ensure(
        failure == EditFailure.WrongSnapshot &&
            second.render().contentEquals(secondSource.toByteArray(Charsets.UTF_8)),
    )
}
