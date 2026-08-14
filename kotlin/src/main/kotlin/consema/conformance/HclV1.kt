// The `consema.hcl.conformance@1` suite runner
// (conformance/vectors/hcl-v1.json).
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-conformance/src/hcl_v1.rs (the per-case
// dispatch is transcribed from the Rust handlers); the vector file itself
// drives every input and expectation (conformance/README.md rules 3-4).
//
// The HCL family owns two profiles over one grammar and one native semantic
// model (RFC 0014 §1, §6), so formation dispatches on `input.profile` —
// `hcl.native@1` and `hcl.tfvars@1` — while query, projection,
// materialization, edit, and limit share the `hcl.*` capability vocabulary.
// Every expected fact is asserted against the frozen vector data.

package consema.conformance

import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBinaryFloat32
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvDecimal
import consema.core.PvEntryMapping
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.document.CompleteMaterialization
import consema.document.EditPlanSourceId
import consema.document.FormationStatus
import consema.document.MaterializationRequest
import consema.document.MaterializationStyleId
import consema.document.ParseLimits
import consema.document.ProfileId
import consema.hcl.BodyPath
import consema.hcl.BodyPlacement
import consema.hcl.EditKey
import consema.hcl.EditValue
import consema.hcl.ExpressionPolicy
import consema.hcl.HclBodyHandle
import consema.hcl.HclBodyItemHandle
import consema.hcl.HclCancellationToken
import consema.hcl.HclDiagnostic
import consema.hcl.HclDocument
import consema.hcl.HclEditCommit
import consema.hcl.HclEditException
import consema.hcl.HclEditTransaction
import consema.hcl.HclEditTransactionBuilder
import consema.hcl.HclFormationException
import consema.hcl.HclLiteralValue
import consema.hcl.HclMaterializationFailure
import consema.hcl.HclMaterializationResult
import consema.hcl.HclMatch
import consema.hcl.HclNodeRef
import consema.hcl.HclParseLimits
import consema.hcl.HclProfile
import consema.hcl.HclQueryException
import consema.hcl.HclQueryLimits
import consema.hcl.HclQueryTerminal
import consema.hcl.HclSyntaxMatch
import consema.hcl.ProjectionEventKind
import consema.hcl.ProjectionLimits
import consema.hcl.ProjectionResult
import consema.hcl.ProjectionTarget
import consema.hcl.commit
import consema.hcl.dryRun
import consema.hcl.executeHclQuery
import consema.hcl.executeHclSyntaxQuery
import consema.hcl.literalValue
import consema.hcl.materialize
import consema.hcl.parse
import consema.hcl.project
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.ExecutableQuery
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryDomain
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QuerySelection
import java.math.BigDecimal
import java.math.BigInteger

/** Runs the `consema.hcl.conformance@1` suite. */
fun runHclV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runHclV1Case(runner, case)
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

private fun runHclV1Case(runner: Runner, case: CaseData) {
    when (case.id) {
        "hcl.native-formation.body-basic",
        "hcl.native-formation.comments",
        "hcl.native-formation.duplicate-attribute",
        "hcl.native-formation.expression-matrix",
        "hcl.native-formation.heredoc",
        "hcl.native-formation.templates",
        "hcl.native-formation.constructors",
        "hcl.native-formation.for-expressions",
        "hcl.native-formation.traversals-splats",
        "hcl.native-formation.production-shape",
        "hcl.native-formation.empty-body-eof-termination",
        "hcl.native-formation.directive-strip-markers",
        -> runNativeFormation(case)
        "hcl.native-formation.number-matrix",
        "hcl.native-formation.identifiers-keywords",
        "hcl.native-formation.unary-compound",
        "hcl.native-formation.operators-precedence",
        "hcl.native-formation.source-contract",
        "hcl.native-formation.recovery-matrix",
        "hcl.native-formation.leading-digit-rejection",
        "hcl.native-formation.invalid-escapes",
        "hcl.native-formation.for-key-ambiguity",
        -> runNativeFormation(case)
        "hcl.tfvars-formation.attributes-only",
        "hcl.tfvars-formation.block-rejected",
        "hcl.tfvars-formation.expression-grammar-full",
        "hcl.tfvars-formation.duplicate-attribute",
        "hcl.tfvars-formation.production-shape",
        -> runTfvarsFormation(case)
        "hcl.query.native-body-walk",
        "hcl.query.blocks-and-labels",
        "hcl.query.literal-accessors",
        "hcl.query.error-regions",
        -> runQuery(case)
        "hcl.query.lossless-kind-filter",
        -> runQuery(case)
        "hcl.projection.literal-complete-record",
        "hcl.projection.non-literal-expression",
        "hcl.projection.project-expression-policy",
        "hcl.projection.literal-complete-boundary",
        -> runProjection(case)
        "hcl.materialization.canonical-document",
        "hcl.materialization.reparse-closure",
        "hcl.materialization.unrepresentable",
        "hcl.materialization.typed-member-form",
        "hcl.materialization.tfvars-canonical",
        -> runMaterialization(case)
        "hcl.edit.attribute-operations",
        "hcl.edit.block-operations",
        "hcl.edit.conflicts",
        "hcl.edit.dry-run-equivalence",
        -> runEdit(case)
        "hcl.limit.expression-depth",
        "hcl.limit.binary-chain-depth",
        "hcl.limit.body-nesting",
        "hcl.limit.number-digits",
        "hcl.limit.arithmetic-overflow",
        "hcl.limit.attribute-count",
        "hcl.limit.block-count",
        "hcl.limit.body-item-count",
        "hcl.limit.label-count",
        "hcl.limit.template-size",
        "hcl.limit.heredoc-size",
        "hcl.limit.tuple-elements",
        "hcl.limit.object-entries",
        -> runLimit(case)
        else -> fail("runner does not recognize published case")
    }
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private fun statusName(status: FormationStatus): String =
    when (status) {
        FormationStatus.Complete -> "Complete"
        FormationStatus.Recovered -> "Recovered"
    }

private fun terminalName(terminal: HclQueryTerminal): String =
    when (terminal) {
        HclQueryTerminal.Completed -> "Completed"
        HclQueryTerminal.Cancelled -> "Cancelled"
        HclQueryTerminal.Failed -> "Failed"
    }

private fun expectedStringField(expected: PortableValue?, name: String): String? =
    stringField(expected, name)

private fun expectedBooleanField(expected: PortableValue?, name: String): Boolean? =
    booleanField(expected, name)

private fun expectedLongField(expected: PortableValue?, name: String): Long? =
    longField(expected, name)

private fun expectedSequenceField(expected: PortableValue?, name: String): List<PortableValue>? =
    sequenceField(expected, name)

private fun expectedF64Field(expected: PortableValue?, name: String): Double? =
    expectedF64(objectField(expected, name))

/** Exact double of one expected numeric fact (binary float or decimal). */
private fun expectedF64(value: PortableValue?): Double? =
    when (value) {
        is PvBinaryFloat64 -> value.toFloat()
        is PvBinaryFloat32 -> value.toFloat().toDouble()
        is PvDecimal -> decimalToF64(value)
        else -> null
    }

/** Converts one exact decimal to its double value; null when the coefficient
 * or exponent exceeds the exact Long range. Single correctly-rounded
 * conversion (BigDecimal → Double, IEEE 754), with the Rust reference
 * implementation's ±308 exponent clamp semantics (hcl_v1.rs decimal_to_f64
 * clamps to 308 and always returns a value). */
private fun decimalToF64(decimal: PvDecimal): Double? {
    val coefficient = runCatching { decimal.coefficient.toLong() }.getOrNull() ?: return null
    val exponent = runCatching { decimal.exponent.toLong() }.getOrNull() ?: return null
    val clamped = exponent.coerceIn(-308, 308)
    return BigDecimal(coefficient).scaleByPowerOfTen(clamped.toInt()).toDouble()
}

/** Exact bit equality of two doubles; every published numeric fact is an
 * exactly representable value, so bit equality is the strict comparison. */
private fun bitsEqual(left: Double, right: Double): Boolean =
    java.lang.Double.doubleToRawLongBits(left) == java.lang.Double.doubleToRawLongBits(right)

// ---------------------------------------------------------------------------
// Formation
// ---------------------------------------------------------------------------

/** One vector fact from a value or its `input` member. */
private fun vectorField(value: PortableValue?, name: String): PortableValue? =
    objectField(value, name) ?: objectField(objectField(value, "input"), name)

private fun profileOf(value: PortableValue?): HclProfile =
    when ((vectorField(value, "profile") as? PvString)?.value) {
        "hcl.native@1" -> HclProfile.NATIVE_V1
        "hcl.tfvars@1" -> HclProfile.TFVARS_V1
        null -> fail("missing profile")
        else -> fail("unknown profile")
    }

/** Raw source bytes of one vector input or sample: `source` UTF-8 text, or
 * `hex` raw bytes (the invalid-UTF-8 fatal sample of the source contract). */
private fun sourceBytes(value: PortableValue?): ByteArray {
    (vectorField(value, "hex") as? PvString)?.let { text ->
        return decodeHex(text.value) ?: fail("invalid hex")
    }
    val source = (vectorField(value, "source") as? PvString)?.value ?: fail("missing input.source")
    return source.toByteArray(Charsets.UTF_8)
}

/** Resolves the `input.limits` field overrides into the formation contract;
 * absent fields keep the frozen defaults. */
private fun parseLimits(case: CaseData): HclParseLimits {
    val overrides = caseInput(case, "limits") as? PvObject ?: return HclParseLimits.default
    fun usize(parent: PortableValue?, name: String): Int? =
        (objectField(parent, name) as? PvInteger)?.value?.toInt()
    val commonValue = objectField(overrides, "common") as? PvObject
    val defaults = HclParseLimits.default
    val common = ParseLimits(
        maxTokenCount = usize(commonValue, "max_token_count") ?: defaults.common.maxTokenCount,
        maxSourceBytes = usize(commonValue, "max_source_bytes") ?: defaults.common.maxSourceBytes,
        maxNestingDepth = usize(commonValue, "max_nesting_depth") ?: defaults.common.maxNestingDepth,
        maxNodeCount = usize(commonValue, "max_node_count") ?: defaults.common.maxNodeCount,
        maxDiagnostics = usize(commonValue, "max_diagnostics") ?: defaults.common.maxDiagnostics,
    )
    return HclParseLimits(
        common = common,
        maxDecodedUtf8Bytes = defaults.maxDecodedUtf8Bytes,
        maxDecodedScalars = defaults.maxDecodedScalars,
        maxBodyDepth = usize(overrides, "max_body_depth") ?: defaults.maxBodyDepth,
        maxExpressionDepth = usize(overrides, "max_expression_depth") ?: defaults.maxExpressionDepth,
        maxTemplateDepth = usize(overrides, "max_template_depth") ?: defaults.maxTemplateDepth,
        maxAttributeCount = usize(overrides, "max_attribute_count") ?: defaults.maxAttributeCount,
        maxBlockCount = usize(overrides, "max_block_count") ?: defaults.maxBlockCount,
        maxLabelCount = usize(overrides, "max_label_count") ?: defaults.maxLabelCount,
        maxBodyItemCount = usize(overrides, "max_body_item_count") ?: defaults.maxBodyItemCount,
        maxIdentifierLen = usize(overrides, "max_identifier_len") ?: defaults.maxIdentifierLen,
        maxStringLen = usize(overrides, "max_string_len") ?: defaults.maxStringLen,
        maxNumberDigits = usize(overrides, "max_number_digits") ?: defaults.maxNumberDigits,
        maxTemplateLen = usize(overrides, "max_template_len") ?: defaults.maxTemplateLen,
        maxTemplateInterpolations = defaults.maxTemplateInterpolations,
        maxHeredocLines = usize(overrides, "max_heredoc_lines") ?: defaults.maxHeredocLines,
        maxHeredocBytes = usize(overrides, "max_heredoc_bytes") ?: defaults.maxHeredocBytes,
        maxTupleElements = usize(overrides, "max_tuple_elements") ?: defaults.maxTupleElements,
        maxObjectEntries = usize(overrides, "max_object_entries") ?: defaults.maxObjectEntries,
        maxForExtent = usize(overrides, "max_for_extent") ?: defaults.maxForExtent,
        maxRecoveryRegions = usize(overrides, "max_recovery_regions") ?: defaults.maxRecoveryRegions,
        maxErrorRegions = usize(overrides, "max_error_regions") ?: defaults.maxErrorRegions,
        maxSyntaxPieces = usize(overrides, "max_syntax_pieces") ?: defaults.maxSyntaxPieces,
        maxReportEvents = defaults.maxReportEvents,
    )
}

/** One formation outcome: a formed document or a fatal formation failure. */
private sealed class Formed {
    class Document(val document: HclDocument) : Formed()

    class Fatal(val diagnostics: List<HclDiagnostic>) : Formed()
}

private fun formWith(profile: HclProfile, bytes: ByteArray, limits: HclParseLimits): Formed =
    try {
        Formed.Document(parse(bytes, profile, limits))
    } catch (e: HclFormationException) {
        Formed.Fatal(e.diagnostics)
    }

/** Forms one case-level input document. */
private fun formCase(case: CaseData): Formed =
    formWith(profileOf(case.input), sourceBytes(case.input), parseLimits(case))

/** One sample's profile: samples without their own `profile` fact inherit the
 * case-level input profile. */
private fun sampleProfile(case: CaseData, sample: PortableValue): HclProfile =
    when ((objectField(sample, "profile") as? PvString)?.value) {
        "hcl.native@1" -> HclProfile.NATIVE_V1
        "hcl.tfvars@1" -> HclProfile.TFVARS_V1
        null -> profileOf(case.input)
        else -> fail("unknown profile")
    }

/** One sample's source bytes: the sample's own `hex`/`source` facts win,
 * case-level facts fill the rest. */
private fun sampleSourceBytes(case: CaseData, sample: PortableValue): ByteArray {
    (objectField(sample, "hex") as? PvString)?.let { text ->
        return decodeHex(text.value) ?: fail("invalid hex")
    }
    (objectField(sample, "source") as? PvString)?.let { source ->
        return source.value.toByteArray(Charsets.UTF_8)
    }
    return sourceBytes(case.input)
}

/** Forms one sample against case-level facts. */
private fun formSample(case: CaseData, sample: PortableValue): Formed =
    formWith(sampleProfile(case, sample), sampleSourceBytes(case, sample), parseLimits(case))

private fun formedStatusName(formed: Formed): String =
    when (formed) {
        is Formed.Document -> statusName(formed.document.formationStatus())
        is Formed.Fatal -> "FatalFormationFailure"
    }

private fun formedHasCode(formed: Formed, code: String): Boolean =
    when (formed) {
        is Formed.Document -> formed.document.diagnostics().any { it.code == code }
        is Formed.Fatal -> formed.diagnostics.any { it.code == code }
    }

private fun formedDocument(formed: Formed): HclDocument =
    when (formed) {
        is Formed.Document -> formed.document
        is Formed.Fatal -> fail("formation failed")
    }

/** Asserts the `expected.status` and optional `expected.diagnostic` facts. */
private fun assertExpectedStatus(formed: Formed, expected: PortableValue?) {
    expectedStringField(expected, "status")?.let { status ->
        ensure(formedStatusName(formed) == status)
    }
    expectedStringField(expected, "diagnostic")?.let { diagnostic ->
        ensure(formedHasCode(formed, diagnostic))
    }
}

private fun runNativeFormation(case: CaseData) {
    ensure(profileOf(case.input) == HclProfile.NATIVE_V1)
    runFormation(case)
}

private fun runTfvarsFormation(case: CaseData) {
    ensure(profileOf(case.input) == HclProfile.TFVARS_V1)
    runFormation(case)
}

private fun runFormation(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runFormationSamples(case, samples, expected)
        return
    }
    val formed = formCase(case)
    assertExpectedStatus(formed, expected)
    if (formedStatusName(formed) == "Complete") {
        val document = formedDocument(formed)
        expectedString(case, "render")?.let { render ->
            val actual = document.render().toString(Charsets.UTF_8)
            ensure(actual == render)
        }
    }
}

private fun runFormationSamples(case: CaseData, samples: List<PortableValue>, expected: PortableValue) {
    val statuses = expectedSequenceField(expected, "statuses") ?: fail("missing expected.statuses")
    val diagnostics = expectedSequenceField(expected, "diagnostics") ?: fail("missing expected.diagnostics")
    ensure(samples.size == statuses.size && samples.size == diagnostics.size)
    val canonicalValues = expectedSequenceField(expected, "canonical_values")
    val provenAttributeNames = expectedSequenceField(expected, "proven_attribute_names")
    for ((index, sample) in samples.withIndex()) {
        val formed = formSample(case, sample)
        val status = (statuses[index] as? PvString)?.value ?: fail("status must be a string")
        ensure(formedStatusName(formed) == status)
        (diagnostics[index] as? PvString)?.let { code ->
            ensure(formedHasCode(formed, code.value))
        }
        if (status == "Complete") {
            canonicalValues?.let { canonicalValuesValue ->
                if (canonicalValuesValue[index] !is PvNull) {
                    val document = formedDocument(formed)
                    assertCanonicalValue(document, canonicalValuesValue[index])
                }
            }
        }
        provenAttributeNames?.let { proven ->
            (proven[index] as? PvArray)?.let { expectedNames ->
                val document = formedDocument(formed)
                val actual = document.rootBody().attributes().map { it.name() }
                val expectedNamesList = expectedNames.items().mapNotNull { (it as? PvString)?.value }
                ensure(actual == expectedNamesList)
            }
        }
    }
}

/** Asserts the canonical decimal value of the first attribute expression
 * against one expected numeric fact (RFC 0014 §2.3, §6). */
private fun assertCanonicalValue(document: HclDocument, expected: PortableValue) {
    val attribute = document.rootBody().attributes().firstOrNull()
        ?: fail("no attribute to canonicalize")
    val expression = attribute.expression().expressionValue()
    val value = literalValue(expression) ?: fail("expression is not literal-complete")
    when (value) {
        is HclLiteralValue.Integer -> {
            val actual = BigInteger(value.text)
            val expectedValue = (expected as? PvInteger)?.value
                ?: fail("expected an integer canonical value")
            ensure(actual == expectedValue)
        }
        is HclLiteralValue.Decimal -> {
            val actual = value.text.toDouble()
            val expectedValue = expectedF64(expected) ?: fail("expected a real canonical value")
            ensure(bitsEqual(actual, expectedValue))
        }
        else -> fail("unexpected literal kind")
    }
}

// ---------------------------------------------------------------------------
// Query
// ---------------------------------------------------------------------------

private fun capabilities(): CapabilitySet {
    val set = CapabilitySet()
    set.insert(CapabilityId("core.query.ordered-results", 1))
    return set
}

/** Builds the frozen operator vocabulary from one vector filter list. */
private fun buildFilters(filters: List<PortableValue>): List<OperatorCall> =
    filters.map { filter ->
        val operator = (objectField(filter, "operator") as? PvString)?.value
            ?: fail("missing filter.operator")
        val at = operator.lastIndexOf('@')
        if (at < 0) fail("operator lacks version: $operator")
        val name = operator.substring(0, at)
        val version = operator.substring(at + 1).toIntOrNull()
            ?: fail("invalid operator version: $operator")
        val call = OperatorCall(name, version)
        (objectField(filter, "argument") as? PvString)?.let { argument ->
            when (name) {
                "hcl.attribute-name-equals" -> call.withArgument("name", PvString(argument.value))
                "hcl.attribute-literal-value" -> call.withArgument("accessor", PvString(argument.value))
                "hcl.body-block-type-equals", "hcl.block-type-equals" ->
                    call.withArgument("type", PvString(argument.value))
                "hcl.block-label-equals" -> call.withArgument("label", PvString(argument.value))
                "hcl.expression-kind-is", "hcl.syntax-kind-is" ->
                    call.withArgument("kind", PvString(argument.value))
                "hcl.syntax-text-equals" -> call.withArgument("text", PvString(argument.value))
                else -> call.withArgument("argument", PvString(argument.value))
            }
        }
        call
    }

private fun bindQuery(definition: QueryDefinition): ExecutableQuery =
    try {
        definition.validate().let { ExecutableQuery.bind(it, capabilities()) }
    } catch (e: QueryFailureException) {
        fail("definition: ${e.kind.code}")
    }

private fun executeNative(
    document: HclDocument,
    calls: List<OperatorCall>,
): consema.hcl.HclQueryExecution<HclMatch> {
    var expression = QueryExpression(consema.protocol.ExpressionKind.Input)
    for (call in calls) {
        expression = expression.then(call)
    }
    val definition = QueryDefinition(QueryDomain("hcl.native-semantic-query", 1))
        .withExpression(expression)
        .withSelection(QuerySelection.All)
    val executable = bindQuery(definition)
    return executeHclQuery(executable, document, HclQueryLimits.default, HclCancellationToken())
}

private fun executeSyntax(
    document: HclDocument,
    calls: List<OperatorCall>,
): consema.hcl.HclQueryExecution<HclSyntaxMatch> {
    var expression = QueryExpression(consema.protocol.ExpressionKind.Input)
    for (call in calls) {
        expression = expression.then(call)
    }
    val definition = QueryDefinition(QueryDomain("hcl.lossless-syntax-query", 1))
        .withExpression(expression)
        .withSelection(QuerySelection.All)
    val executable = bindQuery(definition)
    return executeHclSyntaxQuery(executable, document, HclQueryLimits.default, HclCancellationToken())
}

private fun runQuery(case: CaseData) {
    when (inputString(case, "domain")) {
        "hcl.native-semantic-query@1" -> runNativeQuery(case)
        "hcl.lossless-syntax-query@1" -> runSyntaxQuery(case)
        null -> fail("missing input.domain")
        else -> fail("unknown query domain")
    }
}

/** Compares one expression match against its `{kind, text, literal}`
 * expectation. */
private fun assertExpressionMatch(actual: HclMatch, expected: PortableValue) {
    val expression = actual as? HclMatch.Expression ?: fail("match without expression payload")
    val handle = expression.handle
    (objectField(expected, "kind") as? PvString)?.let { kind ->
        ensure(handle.kindName() == kind.value)
    }
    (objectField(expected, "text") as? PvString)?.let { text ->
        ensure(handle.text() == text.value)
    }
    (objectField(expected, "literal") as? PvBoolean)?.let { literal ->
        ensure(handle.isLiteral() == literal.value)
    }
}

private fun runNativeQuery(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runNativeQuerySamples(case, samples, expected)
        return
    }
    val formed = formCase(case)
    val document = formedDocument(formed)
    // An `expected.error_regions` case queries a Recovered document: the
    // `hcl.error-regions@1` operator exposes its ordered error regions as
    // document-level facts.
    val expectsErrorRegions = expectedSequenceField(expected, "error_regions") != null
    if (document.formationStatus() != FormationStatus.Complete && !expectsErrorRegions) {
        fail("native-query input must form completely")
    }
    val filters = inputSequence(case, "filters") ?: fail("missing input.filters")
    val calls = buildFilters(filters)
    val execution = executeNative(document, calls)
    val terminal = expectedString(case, "terminal") ?: fail("missing expected.terminal")
    ensure(terminalName(execution.terminal) == terminal)
    expectedSequenceField(expected, "matches")?.let { expectedMatches ->
        val matches = execution.matches()
        ensure(matches.size == expectedMatches.size)
        for ((actual, expectedMatch) in matches.zip(expectedMatches)) {
            assertExpressionMatch(actual, expectedMatch)
        }
    }
    expectedSequenceField(expected, "error_regions")?.let { expectedRegions ->
        val regions = execution.matches().mapNotNull { item ->
            (item as? HclMatch.ErrorRegion)?.let { it.region.code to it.position }
        }
        ensure(regions.size == expectedRegions.size)
        for ((actual, expectedRegion) in regions.zip(expectedRegions)) {
            (objectField(expectedRegion, "code") as? PvString)?.let { expectedCode ->
                ensure(actual.first == expectedCode.value)
            }
            (objectField(expectedRegion, "position") as? PvInteger)?.let { expectedPosition ->
                ensure(actual.second.toLong() == expectedPosition.value.toLong())
            }
        }
    }
}

private fun sampleAccessor(sample: PortableValue): String =
    (sequenceField(sample, "filters")?.lastOrNull()
        ?.let { objectField(it, "argument") as? PvString }?.value) ?: ""

private fun runNativeQuerySamples(case: CaseData, samples: List<PortableValue>, expected: PortableValue) {
    val terminals = expectedSequenceField(expected, "terminals") ?: fail("missing expected.terminals")
    ensure(samples.size == terminals.size)
    val codes = expectedSequenceField(expected, "codes")
    val integerMatches = expectedSequenceField(expected, "integer_matches")
    val booleanMatches = expectedSequenceField(expected, "boolean_matches")
    val labelMatches = expectedSequenceField(expected, "label_matches")
    val nestedMatches = expectedSequenceField(expected, "nested_matches")
    for ((index, sample) in samples.withIndex()) {
        val formed = formSample(case, sample)
        val document = formedDocument(formed)
        if (document.formationStatus() != FormationStatus.Complete) {
            fail("native-query input must form completely")
        }
        val filters = (objectField(sample, "filters") as? PvArray)?.items()
            ?: fail("missing sample filters")
        val lastOperator = (filters.lastOrNull()?.let { objectField(it, "operator") as? PvString }?.value)
            ?: ""
        val calls = buildFilters(filters)
        val terminal = (terminals[index] as? PvString)?.value ?: fail("terminal must be a string")
        when (terminal) {
            "Completed" -> {
                val execution = executeNative(document, calls)
                when (lastOperator) {
                    "hcl.attribute-literal-value" -> {
                        val accessor = sampleAccessor(sample)
                        if (accessor == "as-integer") {
                            integerMatches?.let { assertIntegerMatches(execution.matches(), it) }
                        } else if (accessor == "as-boolean-is") {
                            booleanMatches?.let { assertBooleanMatches(execution.matches(), it) }
                        }
                    }
                    "hcl.block-label-equals" -> {
                        labelMatches?.let { assertLabelMatches(execution.matches(), it) }
                    }
                    "hcl.expression-text" -> {
                        nestedMatches?.let { assertNestedMatches(execution.matches(), it) }
                    }
                    else -> {}
                }
            }
            "Failed" -> {
                val failureCode = try {
                    executeNative(document, calls)
                    null
                } catch (e: HclQueryException) {
                    e.code
                } catch (e: QueryFailureException) {
                    e.kind.code
                }
                val expectedCode = (codes?.getOrNull(index) as? PvString)?.value
                    ?: fail("missing expected.codes")
                ensure(failureCode == expectedCode)
            }
            else -> fail("unknown terminal $terminal")
        }
    }
}

/** Asserts typed integer literal matches against `{kind, value}` facts. */
private fun assertIntegerMatches(matches: List<HclMatch>, expectedMatches: List<PortableValue>) {
    ensure(matches.size == expectedMatches.size)
    for ((actual, expectedMatch) in matches.zip(expectedMatches)) {
        val expectedKind = (objectField(expectedMatch, "kind") as? PvString)?.value
            ?: fail("missing expected match kind")
        ensure(expectedKind == "integer")
        val value = actual as? HclMatch.LiteralValue ?: fail("match is not a literal value")
        val actualInteger = (value.value as? PvInteger)?.value ?: fail("match is not an integer literal")
        val expectedValue = (objectField(expectedMatch, "value") as? PvInteger)?.value
            ?: fail("missing expected integer value")
        ensure(actualInteger == expectedValue)
    }
}

/** Asserts typed boolean literal matches against `{kind, value}` facts. */
private fun assertBooleanMatches(matches: List<HclMatch>, expectedMatches: List<PortableValue>) {
    ensure(matches.size == expectedMatches.size)
    for ((actual, expectedMatch) in matches.zip(expectedMatches)) {
        val expectedKind = (objectField(expectedMatch, "kind") as? PvString)?.value
            ?: fail("missing expected match kind")
        ensure(expectedKind == "boolean")
        val value = actual as? HclMatch.LiteralValue ?: fail("match is not a literal value")
        val actualBoolean = (value.value as? PvBoolean)?.value ?: fail("match is not a boolean literal")
        val expectedValue = (objectField(expectedMatch, "value") as? PvBoolean)?.value
            ?: fail("missing expected boolean value")
        ensure(actualBoolean == expectedValue)
    }
}

/** Asserts block-label matches against `{text, quoted}` facts. */
private fun assertLabelMatches(matches: List<HclMatch>, expectedMatches: List<PortableValue>) {
    ensure(matches.size == expectedMatches.size)
    for ((actual, expectedMatch) in matches.zip(expectedMatches)) {
        val label = (actual as? HclMatch.BlockLabel)?.handle ?: fail("match is not a block label")
        (objectField(expectedMatch, "text") as? PvString)?.let { text ->
            ensure(label.text() == text.value)
        }
        (objectField(expectedMatch, "quoted") as? PvBoolean)?.let { quoted ->
            ensure(label.quoted() == quoted.value)
        }
    }
}

/** Asserts expression matches against `{kind, text}` facts. */
private fun assertNestedMatches(matches: List<HclMatch>, expectedMatches: List<PortableValue>) {
    ensure(matches.size == expectedMatches.size)
    for ((actual, expectedMatch) in matches.zip(expectedMatches)) {
        val expression = actual as? HclMatch.Expression ?: fail("match without expression payload")
        (objectField(expectedMatch, "kind") as? PvString)?.let { kind ->
            ensure(expression.handle.kindName() == kind.value)
        }
        (objectField(expectedMatch, "text") as? PvString)?.let { text ->
            ensure(expression.handle.text() == text.value)
        }
    }
}

private fun runSyntaxQuery(case: CaseData) {
    val formed = formCase(case)
    val document = formedDocument(formed)
    if (document.formationStatus() != FormationStatus.Complete) {
        fail("syntax-query input must form completely")
    }
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples") ?: fail("missing input.samples")
    val terminals = expectedSequenceField(expected, "terminals") ?: fail("missing expected.terminals")
    ensure(samples.size == terminals.size)
    val matchesSets = expectedSequenceField(expected, "matches") ?: fail("missing expected.matches")
    ensure(samples.size == matchesSets.size)
    for ((index, sample) in samples.withIndex()) {
        val filters = (objectField(sample, "filters") as? PvArray)?.items()
            ?: fail("missing sample filters")
        val calls = buildFilters(filters)
        val execution = executeSyntax(document, calls)
        val terminal = (terminals[index] as? PvString)?.value ?: fail("terminal must be a string")
        ensure(terminalName(execution.terminal) == terminal)
        val matches = execution.matches()
        val expectedMatches = (matchesSets[index] as? PvArray)?.items()
            ?: fail("expected matches must be a sequence")
        ensure(matches.size == expectedMatches.size)
        val sourceText = document.source().decodedText() ?: fail("match text not UTF-8")
        for ((actual, expectedMatch) in matches.zip(expectedMatches)) {
            val expectedKind = (objectField(expectedMatch, "kind") as? PvString)?.value
                ?: fail("missing expected match kind")
            ensure(actual.kind.asStr() == expectedKind)
            (objectField(expectedMatch, "text") as? PvString)?.let { expectedText ->
                val actualText = sourceText.substring(actual.span.startByte, actual.span.endByte)
                ensure(actualText == expectedText.value)
            }
            (objectField(expectedMatch, "ordinal") as? PvInteger)?.let { expectedOrdinal ->
                ensure(actual.ordinal.toLong() == expectedOrdinal.value.toLong())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Projection
// ---------------------------------------------------------------------------

private fun projectionPolicy(case: CaseData): ExpressionPolicy =
    when (inputString(case, "policy")) {
        "ProjectExpression" -> ExpressionPolicy.ProjectExpression
        else -> ExpressionPolicy.Default
    }

private fun runProjection(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runProjectionSamples(case, samples, expected)
        return
    }
    val formed = formCase(case)
    val document = formedDocument(formed)
    val result = project(
        document,
        ProjectionTarget.BodyV1,
        projectionPolicy(case),
        ProjectionLimits.default,
    )
    expectedStringField(expected, "failure")?.let { failure ->
        val failed = result as? ProjectionResult.Failed ?: fail("projection must fail")
        val code = failed.attempt.diagnostics.firstOrNull()?.code
            ?: fail("projection failure without diagnostics")
        ensure(code == failure)
        return
    }
    val complete = result as? ProjectionResult.Complete ?: fail("projection must complete")
    val record = stringField(expected, "record") ?: fail("missing expected.record")
    val actualRecord = stringField(complete.projection.value, "record")
        ?: fail("missing record member")
    ensure(actualRecord == record)
    expectedSequenceField(expected, "attributes")?.let { attributes ->
        assertProjectedAttributes(complete.projection.value, attributes)
    }
    expectedSequenceField(expected, "blocks")?.let { blocks ->
        assertProjectedBlocks(complete.projection.value, blocks)
    }
    expectedLongField(expected, "transformed_events")?.let { transformed ->
        val events = complete.projection.report.events()
            .count { it.kind == ProjectionEventKind.ExpressionSubstituted }
        ensure(events.toLong() == transformed)
    }
    expectedBooleanField(expected, "event_provenance")?.let { provenance ->
        ensure(provenance != complete.projection.provenance.entries().isEmpty())
    }
    // Order, duplicate-key, and canonical-decimal preservation are verified
    // by the attribute assertions above; a declared flag must be true.
    for (name in listOf("attribute_order_preserved", "duplicate_keys_preserved", "canonical_decimal")) {
        expectedBooleanField(expected, name)?.let { declared ->
            ensure(declared)
        }
    }
}

/** The projected `hcl.body@1` record's ordered item sequence. */
private fun projectedItems(projected: PortableValue): List<PortableValue> =
    (objectField(projected, "items") as? PvArray)?.items() ?: fail("missing projected items")

/** Asserts the attribute items of the projected `hcl.body@1` record against
 * the ordered expected attribute facts. */
private fun assertProjectedAttributes(
    projected: PortableValue,
    expectedAttributes: List<PortableValue>,
) {
    val attributes = projectedItems(projected).filter { item ->
        (objectField(item, "kind") as? PvString)?.value == "attribute"
    }
    ensure(attributes.size == expectedAttributes.size)
    for ((actual, expectedAttribute) in attributes.zip(expectedAttributes)) {
        val expectedName = (objectField(expectedAttribute, "name") as? PvString)?.value
            ?: fail("missing expected attribute name")
        val actualName = (objectField(actual, "name") as? PvString)?.value
            ?: fail("missing projected attribute name")
        ensure(actualName == expectedName)
        // Expected attribute descriptors carry their value facts flat:
        // `{name, kind, text | value | elements | entries | expression}`.
        val value = objectField(actual, "value") ?: fail("missing projected value")
        assertProjectedValue(value, expectedAttribute)
    }
}

/** Asserts the block items of the projected `hcl.body@1` record. */
private fun assertProjectedBlocks(projected: PortableValue, expectedBlocks: List<PortableValue>) {
    val blocks = projectedItems(projected).filter { item ->
        (objectField(item, "kind") as? PvString)?.value == "block"
    }
    ensure(blocks.size == expectedBlocks.size)
    for ((actual, expectedBlock) in blocks.zip(expectedBlocks)) {
        (objectField(expectedBlock, "type") as? PvString)?.let { expectedType ->
            val actualType = (objectField(actual, "type") as? PvString)?.value
                ?: fail("missing projected block type")
            ensure(actualType == expectedType.value)
        }
        (objectField(expectedBlock, "labels") as? PvArray)?.let { expectedLabels ->
            val actualLabels = (objectField(actual, "labels") as? PvArray)?.items()
                ?: fail("missing projected block labels")
            ensure(actualLabels.size == expectedLabels.size())
            for ((actualLabel, expectedLabel) in actualLabels.zip(expectedLabels.items())) {
                val expectedText = (expectedLabel as? PvString)?.value
                    ?: fail("expected label must be a string")
                val actualText = (actualLabel as? PvString)?.value
                    ?: fail("projected label must be a string")
                ensure(actualText == expectedText)
            }
        }
    }
}

/** Asserts one projected value against its `{kind, ...}` expectation. */
private fun assertProjectedValue(actual: PortableValue, expected: PortableValue) {
    val kind = (objectField(expected, "kind") as? PvString)?.value ?: fail("missing expected value kind")
    when (kind) {
        "string" -> {
            val text = (objectField(expected, "text") as? PvString)?.value ?: fail("missing expected text")
            ensure((actual as? PvString)?.value == text)
        }
        "integer" -> {
            val expectedValue = (objectField(expected, "value") as? PvInteger)?.value
                ?: fail("missing expected integer")
            val actualValue = (actual as? PvInteger)?.value ?: fail("projected value is not an integer")
            ensure(actualValue == expectedValue)
        }
        "real" -> {
            val expectedValue = expectedF64(objectField(expected, "value")) ?: fail("missing expected real")
            val actualValue = expectedF64(actual) ?: fail("projected value is not a real")
            ensure(bitsEqual(actualValue, expectedValue))
        }
        "boolean" -> {
            val expectedValue = (objectField(expected, "value") as? PvBoolean)?.value
                ?: fail("missing expected boolean")
            ensure((actual as? PvBoolean)?.value == expectedValue)
        }
        "null" -> ensure(actual is PvNull)
        "tuple" -> {
            val elements = (objectField(expected, "elements") as? PvArray)?.items()
                ?: fail("missing expected elements")
            val actualElements = (actual as? PvArray)?.items() ?: fail("projected value is not a tuple")
            ensure(actualElements.size == elements.size)
            for ((actualElement, expectedElement) in actualElements.zip(elements)) {
                assertProjectedElement(actualElement, expectedElement)
            }
        }
        "object" -> {
            val entries = (objectField(expected, "entries") as? PvArray)?.items()
                ?: fail("missing expected entries")
            val actualEntries = (actual as? PvEntryMapping)?.entries()
                ?: fail("projected value is not an object")
            ensure(actualEntries.size == entries.size)
            for ((actualEntry, expectedEntry) in actualEntries.zip(entries)) {
                val expectedPair = (expectedEntry as? PvArray)?.items()
                    ?: fail("expected object entry must be a pair")
                val expectedKey = (expectedPair.firstOrNull() as? PvString)?.value
                    ?: fail("expected object key must be a string")
                val actualKey = (actualEntry.key as? PvString)?.value
                    ?: fail("projected object key is not a string")
                ensure(actualKey == expectedKey)
                val expectedValue = expectedPair.getOrNull(1)
                    ?: fail("expected object entry value missing")
                assertProjectedElement(actualEntry.value, expectedValue)
            }
        }
        "expression" -> {
            val expectedExpression = objectField(expected, "expression")
                ?: fail("missing expected expression record")
            val actualRecord = (objectField(actual, "record") as? PvString)?.value
                ?: fail("missing expression record member")
            val expectedRecord = (objectField(expectedExpression, "record") as? PvString)?.value
                ?: fail("missing expected expression record id")
            ensure(actualRecord == expectedRecord)
            val actualKind = (objectField(actual, "kind") as? PvString)?.value
                ?: fail("missing expression kind member")
            val expectedKind = (objectField(expectedExpression, "kind") as? PvString)?.value
                ?: fail("missing expected expression kind")
            ensure(actualKind == expectedKind)
            val actualText = (objectField(actual, "text") as? PvString)?.value
                ?: fail("missing expression text member")
            val expectedText = (objectField(expectedExpression, "text") as? PvString)?.value
                ?: fail("missing expected expression text")
            ensure(actualText == expectedText)
        }
        else -> fail("unknown projected value kind $kind")
    }
}

/** Asserts one tuple element or object value: a scalar, or a nested
 * `{kind, ...}` descriptor. */
private fun assertProjectedElement(actual: PortableValue, expected: PortableValue) {
    when (expected) {
        is PvString -> {
            ensure((actual as? PvString)?.value == expected.value)
            return
        }
        is PvInteger -> {
            ensure((actual as? PvInteger)?.value == expected.value)
            return
        }
        is PvBoolean -> {
            ensure((actual as? PvBoolean)?.value == expected.value)
            return
        }
        else -> {}
    }
    expectedF64(expected)?.let { expectedReal ->
        val actualReal = expectedF64(actual) ?: fail("projected element is not a real")
        ensure(bitsEqual(actualReal, expectedReal))
        return
    }
    if (objectField(expected, "kind") != null) {
        assertProjectedValue(actual, expected)
        return
    }
    fail("unsupported expected element")
}

private fun runProjectionSamples(case: CaseData, samples: List<PortableValue>, expected: PortableValue) {
    val codes = expectedSequenceField(expected, "codes")
    val literals = expectedSequenceField(expected, "literals")
    for ((index, sample) in samples.withIndex()) {
        val formed = formSample(case, sample)
        val document = formedDocument(formed)
        val result = project(
            document,
            ProjectionTarget.BodyV1,
            projectionPolicy(case),
            ProjectionLimits.default,
        )
        codes?.let { codesValue ->
            (codesValue[index] as? PvString)?.let { expectedCode ->
                val failed = result as? ProjectionResult.Failed ?: fail("projection must fail")
                val code = failed.attempt.diagnostics.firstOrNull()?.code
                    ?: fail("projection failure without diagnostics")
                ensure(code == expectedCode.value)
            }
        }
        literals?.let { literalsValue ->
            val expectedLiteral = (literalsValue[index] as? PvBoolean)?.value
                ?: fail("expected literal must be a boolean")
            val completed = result is ProjectionResult.Complete
            ensure(completed == expectedLiteral)
        }
    }
}

// ---------------------------------------------------------------------------
// Materialization
// ---------------------------------------------------------------------------

private fun materializationRequest(style: String, profile: PortableValue): MaterializationRequest {
    val profileId = when ((profile as? PvString)?.value) {
        "hcl.native@1" -> ProfileId("hcl.native", 1)
        "hcl.tfvars@1" -> ProfileId("hcl.tfvars", 1)
        null -> fail("missing profile")
        else -> fail("unknown profile")
    }
    return when (style) {
        "hcl.canonical-document@1" -> MaterializationRequest.new(
            profileId,
            MaterializationStyleId("hcl.canonical-document", 1),
        )
        else -> fail("unknown materialization style $style")
    }
}

/** Stable vector spelling of one HCL materialization failure. The family
 * reports a wrong record identity as the stable `"invalid-record"` failure
 * name, matching the published vector spelling. */
private fun materializationFailureSpelling(failure: HclMaterializationFailure): String =
    if (failure is HclMaterializationFailure.Unrepresentable && failure.reason == "invalid-record") {
        "invalid-record"
    } else {
        failure.code
    }

private fun runMaterialization(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runMaterializationSamples(case, samples, expected)
        return
    }
    val style = inputString(case, "style") ?: fail("missing input.style")
    val profile = caseInput(case, "profile") ?: fail("missing input.profile")
    val request = materializationRequest(style, profile)
    val record = caseInput(case, "record") ?: fail("missing input.record")
    expectedStringField(expected, "failure")?.let { failure ->
        when (val result = materialize(record, request)) {
            is HclMaterializationResult.Failed -> {
                ensure(materializationFailureSpelling(result.failure) == failure)
            }
            is HclMaterializationResult.Complete -> fail("materialization must fail")
        }
        return
    }
    when (val result = materialize(record, request)) {
        is HclMaterializationResult.Complete -> {
            expectedStringField(expected, "render")?.let { render ->
                val actual = result.materialization.document.render().toString(Charsets.UTF_8)
                ensure(actual == render)
            }
            if (expectedBooleanField(expected, "closure") == true) {
                ensure(result.materialization.document.formationStatus() == FormationStatus.Complete)
            }
            if (expectedBooleanField(expected, "fingerprint_match") == true) {
                assertFingerprintMatch(result.materialization, record)
            }
        }
        is HclMaterializationResult.Failed -> fail("materialization failed: ${result.failure.code}")
    }
}

/** Asserts that every `hcl.expression@1` record of the input record is
 * reproduced by the re-projection of the materialized document. */
private fun assertFingerprintMatch(
    complete: CompleteMaterialization<HclDocument>,
    record: PortableValue,
) {
    val result = project(
        complete.document,
        ProjectionTarget.BodyV1,
        ExpressionPolicy.ProjectExpression,
        ProjectionLimits.default,
    )
    val projection = result as? ProjectionResult.Complete ?: fail("materialized document must re-project")
    val items = (objectField(record, "items") as? PvArray)?.items() ?: fail("missing record items")
    val attributes = projectedItems(projection.projection.value).filter { item ->
        (objectField(item, "kind") as? PvString)?.value == "attribute"
    }
    for (item in items) {
        val kind = (objectField(item, "kind") as? PvString)?.value ?: continue
        if (kind != "attribute") continue
        val value = objectField(item, "value") ?: continue
        val valueKind = (objectField(value, "kind") as? PvString)?.value ?: continue
        if (valueKind != "expression") continue
        val name = (objectField(item, "name") as? PvString)?.value ?: fail("missing attribute name")
        val expectedExpression = objectField(value, "expression") ?: fail("missing expression record")
        val projected = attributes.firstOrNull { attribute ->
            (objectField(attribute, "name") as? PvString)?.value == name
        } ?: fail("projected attribute $name not found")
        val projectedValue = objectField(projected, "value") ?: fail("missing projected value")
        val actualKind = (objectField(projectedValue, "kind") as? PvString)?.value
            ?: fail("missing projected expression kind")
        val expectedKind = (objectField(expectedExpression, "kind") as? PvString)?.value
            ?: fail("missing expected expression kind")
        ensure(actualKind == expectedKind)
        val actualText = (objectField(projectedValue, "text") as? PvString)?.value
            ?: fail("missing projected expression text")
        val expectedText = (objectField(expectedExpression, "text") as? PvString)?.value
            ?: fail("missing expected expression text")
        ensure(actualText == expectedText)
        val actualRecord = (objectField(projectedValue, "record") as? PvString)?.value
            ?: fail("missing projected expression record")
        val expectedRecord = (objectField(expectedExpression, "record") as? PvString)?.value
            ?: fail("missing expected expression record")
        ensure(actualRecord == expectedRecord)
    }
}

private fun runMaterializationSamples(case: CaseData, samples: List<PortableValue>, expected: PortableValue) {
    val renders = expectedSequenceField(expected, "renders")
    val codes = expectedSequenceField(expected, "codes")
    val closure = expectedBooleanField(expected, "closure") == true
    ensure(samples.size == (renders?.size ?: codes?.size ?: fail("missing expected.codes")))
    for ((index, sample) in samples.withIndex()) {
        val style = stringField(sample, "style") ?: inputString(case, "style")
            ?: fail("missing sample style")
        val profile = objectField(sample, "profile") ?: caseInput(case, "profile")
            ?: fail("missing sample profile")
        val request = materializationRequest(style, profile)
        val record = objectField(sample, "record") ?: fail("missing sample record")
        when (val result = materialize(record, request)) {
            is HclMaterializationResult.Complete -> {
                renders?.let { rendersValue ->
                    val expectedRender = (rendersValue[index] as? PvString)?.value
                        ?: fail("expected render must be a string")
                    val actual = result.materialization.document.render().toString(Charsets.UTF_8)
                    ensure(actual == expectedRender)
                }
                if (closure) {
                    ensure(result.materialization.document.formationStatus() == FormationStatus.Complete)
                }
            }
            is HclMaterializationResult.Failed -> {
                val expectedCode = (codes?.getOrNull(index) as? PvString)?.value
                    ?: fail("materialization must complete")
                ensure(materializationFailureSpelling(result.failure) == expectedCode)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Edit
// ---------------------------------------------------------------------------

private fun bodyPath(operation: PortableValue): BodyPath {
    val body = stringField(operation, "body")
    ensure(body == null || body == "root")
    return BodyPath.root()
}

private fun placement(operation: PortableValue): BodyPlacement =
    when (stringField(operation, "placement") ?: "Last") {
        "First" -> BodyPlacement.First
        "Last" -> BodyPlacement.Last
        else -> fail("unknown placement")
    }

private fun editValue(value: PortableValue): EditValue =
    when (stringField(value, "kind")) {
        "string" -> EditValue.String(stringField(value, "text") ?: fail("missing text"))
        "integer" -> EditValue.Integer(
            (objectField(value, "value") as? PvInteger)?.value?.toLong()
                ?: fail("missing integer value"),
        )
        "real" -> EditValue.Real(expectedF64(objectField(value, "value")) ?: fail("missing real value"))
        "boolean" -> EditValue.Boolean(
            (objectField(value, "value") as? PvBoolean)?.value ?: fail("missing boolean value"),
        )
        "null" -> EditValue.Null
        "tuple" -> {
            val elements = (objectField(value, "elements") as? PvArray)?.items()
                ?: fail("missing tuple elements")
            EditValue.Tuple(elements.map { editValue(it) })
        }
        "object" -> {
            val entries = (objectField(value, "entries") as? PvArray)?.items()
                ?: fail("missing object entries")
            val pairs = entries.map { entry ->
                val pair = (entry as? PvArray)?.items() ?: fail("entry must be a pair")
                val keyText = (pair.firstOrNull() as? PvString)?.value ?: fail("entry key must be a string")
                val key = keyText.toLongOrNull()?.let { EditKey.Number(it) }
                    ?: EditKey.Identifier(keyText)
                val entryValue = pair.getOrNull(1) ?: fail("entry value missing")
                key to editValue(entryValue)
            }
            EditValue.Object(pairs)
        }
        "expression" -> {
            val expression = objectField(value, "expression") ?: fail("missing expression record")
            val kind = stringField(expression, "kind") ?: fail("missing expression kind")
            val text = stringField(expression, "text") ?: fail("missing expression text")
            EditValue.Expression(kind, text)
        }
        else -> fail("unknown value kind")
    }

private fun blockNodeRef(operation: PortableValue): HclNodeRef.Block {
    val nodeRef = objectField(operation, "node_ref") ?: fail("missing node_ref")
    val blockType = stringField(nodeRef, "type") ?: fail("missing node_ref type")
    val labels = (objectField(nodeRef, "labels") as? PvArray)?.items()?.map { label ->
        (label as? PvString)?.value ?: fail("node_ref label must be a string")
    } ?: fail("missing node_ref labels")
    return HclNodeRef.Block(BodyPath.root(), blockType, labels, 0)
}

private fun buildTransaction(document: HclDocument, operations: List<PortableValue>): HclEditTransactionBuilder {
    val builder = HclEditTransactionBuilder.new(document)
    for (operation in operations) {
        when (stringField(operation, "op")) {
            "hcl.edit.set-attribute-value@1" -> {
                val body = bodyPath(operation)
                val attribute = stringField(operation, "attribute") ?: fail("missing attribute")
                val value = editValue(objectField(operation, "value") ?: fail("missing value"))
                builder.setAttributeValue(body, attribute, value)
            }
            "hcl.edit.insert-attribute@1" -> {
                val body = bodyPath(operation)
                val name = stringField(operation, "name") ?: fail("missing name")
                val value = editValue(objectField(operation, "value") ?: fail("missing value"))
                builder.insertAttribute(body, name, value, placement(operation))
            }
            "hcl.edit.remove-attribute@1" -> {
                val body = bodyPath(operation)
                val attribute = stringField(operation, "attribute") ?: fail("missing attribute")
                builder.removeAttribute(body, attribute)
            }
            "hcl.edit.rename-attribute@1" -> {
                val body = bodyPath(operation)
                val attribute = stringField(operation, "attribute") ?: fail("missing attribute")
                val name = stringField(operation, "name") ?: fail("missing name")
                builder.renameAttribute(body, attribute, name)
            }
            "hcl.edit.insert-block@1" -> {
                val body = bodyPath(operation)
                val blockType = stringField(operation, "type") ?: fail("missing block type")
                val labels = (objectField(operation, "labels") as? PvArray)?.items()?.map { label ->
                    (label as? PvString)?.value ?: fail("block label must be a string")
                } ?: fail("missing block labels")
                val attributes = (objectField(operation, "attributes") as? PvArray)?.items()
                    ?: fail("missing block attributes")
                val typed = attributes.map { attribute ->
                    val name = stringField(attribute, "name") ?: fail("missing block attribute name")
                    val value = editValue(objectField(attribute, "value") ?: fail("missing attribute value"))
                    name to value
                }
                builder.insertBlock(body, blockType, labels, typed, placement(operation))
            }
            "hcl.edit.remove-block@1" -> {
                val ref = blockNodeRef(operation)
                builder.removeBlock(BodyPath.root(), ref.blockType, ref.labels, ref.occurrence)
            }
            else -> fail("unknown edit op")
        }
    }
    return builder
}

/** Reparses one committed document under its own profile. */
private fun reparse(document: HclDocument): HclDocument =
    try {
        parse(
            document.render(),
            if (document.profileId().id == "hcl.tfvars") HclProfile.TFVARS_V1 else HclProfile.NATIVE_V1,
            HclParseLimits.default,
        )
    } catch (e: HclFormationException) {
        fail("reparse: ${e.code}")
    }

/** Whether every block label of one native body tree is quoted. */
private fun allLabelsQuoted(body: HclBodyHandle): Boolean =
    body.items().all { item ->
        when (item) {
            is HclBodyItemHandle.Attribute -> true
            is HclBodyItemHandle.Block ->
                item.handle.labels().all { it.quoted() } && allLabelsQuoted(item.handle.body())
        }
    }

private fun runEdit(case: CaseData) {
    val expected = case.expected ?: fail("missing expected")
    val samples = inputSequence(case, "samples")
    if (samples != null) {
        runEditConflicts(case, samples, expected)
        return
    }
    val formed = formCase(case)
    val document = formedDocument(formed)
    if (document.formationStatus() != FormationStatus.Complete) {
        fail("edit input must form completely")
    }
    val operations = inputSequence(case, "operations") ?: fail("missing input.operations")
    val transaction = buildTransaction(document, operations).build()
    val commit = try {
        document.commit(transaction)
    } catch (e: HclEditException) {
        fail("commit: ${e.code}")
    }
    assertEditFacts(document, transaction, commit, expected)
}

/** Asserts the vector facts of one committed edit against its base document. */
private fun assertEditFacts(
    base: HclDocument,
    transaction: HclEditTransaction,
    commit: HclEditCommit,
    expected: PortableValue,
) {
    val committed = commit.document
    ensure(committed.formationStatus() == FormationStatus.Complete)
    expectedStringField(expected, "render")?.let { render ->
        val actual = committed.render().toString(Charsets.UTF_8)
        ensure(actual == render)
    }
    if (expectedBooleanField(expected, "reparse_closure") == true) {
        ensure(reparse(committed).formationStatus() == FormationStatus.Complete)
    }
    if (expectedBooleanField(expected, "untouched_byte_proof") == true) {
        try {
            commit.untouchedProof.verify(
                base.source(),
                committed.source(),
                commit.sourcePatch.replacements(),
            )
        } catch (e: Exception) {
            fail("untouched proof: ${e.message}")
        }
    }
    if (expectedBooleanField(expected, "patch_replays") == true) {
        val replay = commit.sourcePatch.apply(base.source())
        ensure(replay.bytes().contentEquals(committed.render()))
    }
    if (expectedBooleanField(expected, "labels_always_quoted") == true) {
        ensure(allLabelsQuoted(committed.rootBody()))
    }
    if (expectedBooleanField(expected, "dry_run_equivalent") == true) {
        val sourceId = try {
            EditPlanSourceId.new("hcl-conformance")
        } catch (e: Exception) {
            fail("source id: ${e.message}")
        }
        val plan = base.dryRun(transaction, sourceId)
        ensure(plan.replacements() == commit.sourcePatch.replacements())
    }
}

private fun runEditConflicts(case: CaseData, samples: List<PortableValue>, expected: PortableValue) {
    val codes = expectedSequenceField(expected, "codes") ?: fail("missing expected.codes")
    val baseUnchanged = expectedBooleanField(expected, "base_unchanged") == true
    ensure(samples.size == codes.size)
    for ((index, sample) in samples.withIndex()) {
        val formed = formSample(case, sample)
        val document = formedDocument(formed)
        val operations = sequenceField(sample, "operations") ?: fail("missing operations")
        val transaction = objectField(sample, "wrong_source")?.let { wrong ->
            // The transaction is bound to another document's snapshot.
            val other = formSample(case, wrong)
            buildTransaction(formedDocument(other), operations).build()
        } ?: buildTransaction(document, operations).build()
        val failureCode = try {
            document.commit(transaction)
            null
        } catch (e: HclEditException) {
            e.code
        }
        val expectedCode = (codes[index] as? PvString)?.value ?: fail("expected code must be a string")
        ensure(failureCode == expectedCode)
        if (baseUnchanged) {
            ensure(document.render().contentEquals(document.source().bytes()))
        }
    }
}

// ---------------------------------------------------------------------------
// Limit
// ---------------------------------------------------------------------------

/** Runs one `hcl.limit@1` case: every published limit vector is
 * formation-class today, so this runner delegates to [`runFormation`]. */
private fun runLimit(case: CaseData) {
    runFormation(case)
}
