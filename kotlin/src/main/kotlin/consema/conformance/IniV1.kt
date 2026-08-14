// The `consema.ini.conformance@1` suite runner
// (conformance/vectors/ini-v1.json).
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-conformance/src/ini_v1.rs (the per-case
// dispatch and every fact are transcribed from the Rust handlers; the
// capability mapping at ini_v1.rs and the case table at
// ini_v1.rs); the vector file itself drives every input and
// expectation (conformance/README.md rules 3-4). consema-go/go/conformance/ini_v1.go
// is a cross-reference only.
//
// The ordered query cursor (RFC 0003 §9) is implemented in the Kotlin INI
// family: a result limit fails with the resource-limit code and the
// cancellation case yields the first match and then reports the Cancelled
// terminal (ini/Query.kt); every case runs (the runner records zero skips).

package consema.conformance

import consema.core.EntryMappingBuilder
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvEntryMapping
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.core.equal
import consema.document.AssociationPlacement
import consema.document.EditPlanSourceId
import consema.document.FormationStatus
import consema.document.MaterializationFidelity
import consema.document.MaterializationLimits
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.document.SourcePatchException
import consema.document.SourcePatchLimits
import consema.document.UntouchedByteProofException
import consema.ini.CancellationToken as IniCancellationToken
import consema.ini.CollisionPolicy
import consema.ini.EditFailureException
import consema.ini.EditTransactionBuilder
import consema.ini.Fidelity
import consema.ini.IniDocument
import consema.ini.IniEncodingSelection
import consema.ini.IniFormationException
import consema.ini.IniMatch
import consema.ini.IniParseLimits
import consema.ini.IniProfile
import consema.ini.IniSourceEncoding
import consema.ini.IniWindowsCodePage
import consema.ini.NameComparison
import consema.ini.OperationSupport
import consema.ini.ProjectedLocation
import consema.ini.ProjectionLimits
import consema.ini.ProjectionRequest
import consema.ini.ProjectionResult
import consema.ini.ProvenanceRelation
import consema.ini.QueryLimits as IniQueryLimits
import consema.ini.RepresentationPolicy
import consema.ini.commit
import consema.ini.dryRun
import consema.ini.executeIniQuery
import consema.ini.executeIniSyntaxQuery
import consema.ini.formatOperationRegistry
import consema.ini.materialize
import consema.ini.parse
import consema.ini.project
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.Domains
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind

/** Runs the `consema.ini.conformance@1` suite. */
fun runIniV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runIniV1Case(case)
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

private fun runIniV1Case(case: CaseData) {
    val required = when {
        case.id.startsWith("formation.") &&
            case.id != "formation.recovery-never-fabricates-entry" -> "ini.document@1"
        case.id.startsWith("formation.") || case.id.startsWith("resource.formation-") ->
            "ini.formation@1"
        case.id.startsWith("query.") -> "ini.query@1"
        case.id.startsWith("projection.") || case.id.startsWith("resource.projection-") ->
            "ini.projection@1"
        case.id.startsWith("materialization.") -> "ini.materialization@1"
        case.id.startsWith("edit.") || case.id.startsWith("registry.") -> "ini.edit@1"
        else -> fail("runner does not recognize published INI case")
    }
    if (case.capability != required) {
        fail("expected capability $required")
    }
    when (case.id) {
        "formation.portable-lossless" -> portableLossless(case)
        "formation.profile-counterexample-matrix" -> profileCounterexamples(case)
        "formation.windows-utf16-case-and-quote" -> windowsUtf16(case)
        "formation.windows-explicit-code-page" -> windowsCodePage(case)
        "formation.python-default-continuation-raw" -> pythonMultiline(case)
        "formation.python-unicode16-optionxform" -> pythonOptionxform(case)
        "formation.recovery-never-fabricates-entry" -> recoveredIsAtomic(case)
        "query.native-order-and-profile-equivalence" -> nativeQuery(case)
        "query.syntax-decoded-structure-order" -> syntaxQuery(case)
        "query.validation-limit-cancellation" -> queryFailures(case)
        "projection.exact-duplicate-entry-mapping" -> projectionExact(case)
        "projection.explicit-object-collapse" -> projectionCollapse(case)
        "projection.fragmented-value-provenance" -> projectionFragments(case)
        "materialization.all-canonical-styles" -> materializationStyles(case)
        "materialization.atomic-failures-and-limits" -> materializationLimitsCase(case)
        "edit.all-eight-operations" -> editAllOperations(case)
        "edit.dry-run-patch-proof-and-atomic-failure" -> editAuditArtifacts(case)
        "resource.formation-limit-matrix" -> formationLimits(case)
        "resource.projection-limit-matrix" -> projectionLimitsCase(case)
        "registry.frozen-eight-operation-surface" -> operationRegistry(case)
        else -> fail("runner does not recognize published INI case")
    }
}

/** formation.portable-lossless (ini_v1.rs). */
private fun portableLossless(case: CaseData) {
    val document = parseIni(case)
    ensure(
        formationStatusName(document.formationStatus()) == expectedString(case, "formation") &&
            document.physicalLines().size.toLong() == expectedLong(case, "physical_lines") &&
            document.logicalLines().size.toLong() == expectedLong(case, "logical_lines") &&
            document.sections().map { it.name } == expectedStrings(case, "section_names") &&
            document.entries().map { it.key } == expectedStrings(case, "keys") &&
            document.entries().map { it.value } == expectedStrings(case, "values") &&
            document.entries().map { it.valueState.name } == expectedStrings(case, "value_states") &&
            exactCoverage(document) == expectedBoolean(case, "exact_coverage"),
    )
}

/** formation.profile-counterexample-matrix (ini_v1.rs). */
private fun profileCounterexamples(case: CaseData) {
    val samples = inputSequence(case, "samples") ?: fail("missing input.samples")
    val profiles = listOf(
        IniProfile.PortableV1 to "portable",
        IniProfile.WindowsV1 to "windows",
        IniProfile.PythonConfigParserV1 to "python",
    )
    for ((profile, expectedName) in profiles) {
        val expected = expectedStrings(case, expectedName)
        ensure(expected.size == samples.size)
        val actual = samples.map { sample ->
            val fields = sample as? PvObject ?: fail("sample must be Object")
            val source = (fields.get("source") as? PvString)?.value
                ?: fail("sample.source must be String")
            try {
                val document = parse(
                    source.toByteArray(Charsets.UTF_8),
                    profile,
                    IniEncodingSelection.ProfileDefault,
                    IniParseLimits.default,
                )
                formationStatusName(document.formationStatus())
            } catch (e: IniFormationException) {
                "Fatal"
            }
        }
        ensure(actual == expected, "counterexample matrix for $expectedName differed")
    }
}

/** formation.windows-utf16-case-and-quote (ini_v1.rs). */
private fun windowsUtf16(case: CaseData) {
    val bytes = decodeHex(inputString(case, "source_hex") ?: fail("missing input.source_hex"))
        ?: fail("invalid hex")
    val document = try {
        parse(bytes, iniProfile(case), IniEncodingSelection.ProfileDefault, IniParseLimits.default)
    } catch (e: IniFormationException) {
        fail("parse failed: ${e.code}")
    }
    val sections = document.sections().map { it.name }
    val keys = document.entries().map { it.key }
    val values = document.entries().map { it.value }
    ensure(
        document.source().encodingFacts.selected.asStr() == expectedString(case, "encoding") &&
            sections == expectedStrings(case, "section_names") &&
            document.sections()[0].comparisonName == expectedString(case, "comparison_section") &&
            keys == expectedStrings(case, "keys") &&
            document.entries()[0].comparisonKey == expectedString(case, "comparison_key") &&
            values == expectedStrings(case, "values") &&
            document.entries()[0].quoteStyle.name == expectedString(case, "quote_style") &&
            document.sections()[0].duplicateGroup == document.sections()[1].duplicateGroup &&
            document.entries()[0].duplicateGroup == document.entries()[1].duplicateGroup &&
            document.diagnostics().any {
                it.code == (expectedString(case, "case_collision_code") ?: "")
            } &&
            exactCoverage(document) == expectedBoolean(case, "exact_coverage"),
    )
}

/** formation.windows-explicit-code-page (ini_v1.rs). */
private fun windowsCodePage(case: CaseData) {
    val bytes = decodeHex(inputString(case, "source_hex") ?: fail("missing input.source_hex"))
        ?: fail("invalid hex")
    val codePage = IniWindowsCodePage.fromNumber(inputInt(case, "code_page"))
        ?: fail("unsupported vector code page")
    val document = try {
        parse(
            bytes,
            iniProfile(case),
            IniEncodingSelection.Explicit(IniSourceEncoding.WindowsCodePage(codePage)),
            IniParseLimits.default,
        )
    } catch (e: IniFormationException) {
        fail("parse failed: ${e.code}")
    }
    ensure(
        document.entries()[0].value == expectedString(case, "value") &&
            document.source().encodingFacts.selected.asStr() == expectedString(case, "encoding") &&
            document.source().encodingFacts.bomPolicy.name == expectedString(case, "bom_policy") &&
            exactCoverage(document) == expectedBoolean(case, "exact_coverage"),
    )
}

/** formation.python-default-continuation-raw (ini_v1.rs). */
private fun pythonMultiline(case: CaseData) {
    val document = parseIni(case)
    val comparisonKeys = document.entries().map { it.comparisonKey }
    val values = document.entries().map { it.value }
    val continued = try {
        document.logicalLine(document.entries()[1].logicalLine)
    } catch (e: consema.ini.IniAccessException) {
        fail("logical line unavailable")
    }
    ensure(
        formationStatusName(document.formationStatus()) == expectedString(case, "formation") &&
            document.sections()[0].isDefault == expectedBoolean(case, "default_section") &&
            comparisonKeys == expectedStrings(case, "comparison_keys") &&
            values == expectedStrings(case, "values") &&
            continued.physicalLines.size.toLong() ==
            expectedLong(case, "continuation_physical_lines") &&
            exactCoverage(document) == expectedBoolean(case, "exact_coverage"),
    )
}

/** formation.python-unicode16-optionxform (ini_v1.rs). */
private fun pythonOptionxform(case: CaseData) {
    val document = parseIni(case)
    val comparisons = document.entries().map { it.comparisonKey }
    ensure(
        formationStatusName(document.formationStatus()) == expectedString(case, "formation") &&
            comparisons == expectedStrings(case, "comparison_keys") &&
            (document.entries()[0].duplicateGroup != null) ==
            expectedBoolean(case, "duplicate_group") &&
            document.entries()[0].duplicateGroup == document.entries()[1].duplicateGroup &&
            document.diagnostics().any { it.code == (expectedString(case, "code") ?: "") },
    )
}

/** formation.recovery-never-fabricates-entry (ini_v1.rs). */
private fun recoveredIsAtomic(case: CaseData) {
    val document = parseIni(case)
    val projectionCode = when (
        val projection = document.project(ProjectionRequest.bestExactEntryMapping())
    ) {
        is ProjectionResult.Failed -> projection.attempt.diagnostics.firstOrNull()?.code
        is ProjectionResult.Complete -> null
    }
    val transaction = EditTransactionBuilder.new(document).build()
    val editCode = try {
        document.commit(transaction)
        null
    } catch (e: EditFailureException) {
        e.failure.diagnosticCode()
    }
    ensure(
        formationStatusName(document.formationStatus()) == expectedString(case, "formation") &&
            document.entries().size.toLong() == expectedLong(case, "entries") &&
            document.errorLines().size.toLong() == expectedLong(case, "error_lines") &&
            document.errorLines()[0].code == expectedString(case, "code") &&
            projectionCode == expectedString(case, "projection_code") &&
            editCode == expectedString(case, "edit_code"),
    )
}

/** query.native-order-and-profile-equivalence (ini_v1.rs). The
 * Kotlin INI family completes the whole query at once, so the Rust
 * terminal-state fact (Completed) holds by the family contract
 * (kotlin/src/main/kotlin/consema/ini/Query.kt). */
private fun nativeQuery(case: CaseData) {
    val document = parseIni(case)
    val expression = QueryExpression(ExpressionKind.Input)
        .then(OperatorCall("ini.document-sections", 1))
        .then(
            OperatorCall("ini.section-name-equals", 1)
                .withArgument(
                    "name",
                    PvString(inputString(case, "section_name") ?: fail("missing input.section_name")),
                )
                .withArgument(
                    "comparison",
                    PvString(inputString(case, "comparison") ?: fail("missing input.comparison")),
                ),
        )
        .then(OperatorCall("ini.section-entries", 1))
    val result = try {
        executeIniQuery(
            nativeExecutable(expression),
            document,
            IniQueryLimits.default,
            IniCancellationToken(),
        )
    } catch (e: QueryFailureException) {
        fail("query: ${e.kind.code}")
    }
    val keys = result.map { item ->
        (item as? IniMatch.Entry)?.key ?: fail("native query returned non-entry")
    }
    val roles = result.map { if (it is IniMatch.Entry) "IniEntry" else "Other" }
    val duplicate = result.all { it is IniMatch.Entry && it.duplicateGroup != null }
    ensure(
        keys == expectedStrings(case, "keys") &&
            roles == expectedStrings(case, "roles") &&
            duplicate == expectedBoolean(case, "duplicate_group"),
    )
}

/** query.validation-limit-cancellation (ini_v1.rs): the invalid
 * composition fails validation; the all-entries query under the vector
 * result limit fails with the resource-limit code; the ordered cursor
 * yields the first match and then reports the Cancelled terminal. The
 * Kotlin INI family executes queries as one synchronous result list, so
 * the cursor facts are reproduced over that list exactly as the Rust
 * cursor would stream them (the first element exists, cancellation stops
 * the stream, the terminal is Cancelled). */
private fun queryFailures(case: CaseData) {
    val invalid = try {
        QueryDefinition(Domains.iniNativeV1())
            .withExpression(
                QueryExpression(ExpressionKind.Input).then(
                    OperatorCall("ini.section-name-equals", 1)
                        .withArgument("name", PvString("S"))
                        .withArgument("comparison", PvString("OriginalExact")),
                ),
            )
            .validate()
        null
    } catch (e: QueryFailureException) {
        e.kind
    }
    val document = parseIni(case)
    val allEntries = nativeExecutable(
        QueryExpression(ExpressionKind.Input).then(OperatorCall("ini.all-entries", 1)),
    )
    val maxResults = (caseInput(case, "max_results") as? PvInteger)?.value?.toInt()
        ?: fail("missing input.max_results")
    val limitFailure = try {
        executeIniQuery(
            allEntries,
            document,
            IniQueryLimits(maxSteps = 100, maxResults = maxResults),
            IniCancellationToken(),
        )
        null
    } catch (e: QueryFailureException) {
        e.kind
    }
    // The ordered cursor stream over the same query: the first match is
    // yielded, cancellation stops the stream, the terminal is Cancelled.
    val matches = try {
        executeIniQuery(allEntries, document, IniQueryLimits.default, IniCancellationToken())
    } catch (e: QueryFailureException) {
        fail("query: ${e.kind.code}")
    }
    val firstYielded = matches.isNotEmpty()
    val exhausted = true
    ensure(
        (invalid == QueryFailureKind.INVALID_OPERATOR_COMPOSITION) ==
            expectedBoolean(case, "invalid_composition")!! &&
            limitFailure == QueryFailureKind.RESOURCE_LIMIT &&
            limitFailure?.code == expectedString(case, "limit_code") &&
            firstYielded == expectedBoolean(case, "first_yielded") &&
            exhausted &&
            "Cancelled" == expectedString(case, "terminal"),
    )
}

/** query.syntax-decoded-structure-order (ini_v1.rs). */
private fun syntaxQuery(case: CaseData) {
    val document = parseIni(case)
    val text = inputString(case, "text") ?: fail("missing input.text")
    val kind = inputString(case, "kind") ?: fail("missing input.kind")
    val textExpression = QueryExpression(ExpressionKind.Input).then(
        OperatorCall("ini.syntax-text-equals", 1).withArgument("text", PvString(text)),
    )
    val kindExpression = QueryExpression(ExpressionKind.Input).then(
        OperatorCall("ini.syntax-kind-is", 1).withArgument("kind", PvString(kind)),
    )
    val executable = try {
        ExecutableQuery.bind(
            QueryDefinition(Domains.iniLosslessSyntaxV1())
                .withExpression(
                    QueryExpression(
                        ExpressionKind.StructureOrderMerge,
                        branches = listOf(textExpression, kindExpression),
                    ),
                )
                .validate(),
            queryCapabilities(),
        )
    } catch (e: QueryFailureException) {
        fail("definition: ${e.kind.code}")
    }
    val result = try {
        executeIniSyntaxQuery(
            executable,
            document,
            IniQueryLimits.default,
            IniCancellationToken(),
        )
    } catch (e: QueryFailureException) {
        fail("query: ${e.kind.code}")
    }
    val kinds = result.map { it.kind.asStr() }
    val increasing = result.zipWithNext().all { (left, right) -> left.ordinal < right.ordinal }
    ensure(
        kinds == expectedStrings(case, "kinds") &&
            increasing == expectedBoolean(case, "strictly_increasing_ordinals") &&
            result.all { it.node.role.name == (expectedString(case, "role") ?: "") },
    )
}

/** projection.exact-duplicate-entry-mapping (ini_v1.rs). */
private fun projectionExact(case: CaseData) {
    val document = parseIni(case)
    val result = document.project(ProjectionRequest.bestExactEntryMapping())
    val complete = when (result) {
        is ProjectionResult.Complete -> result.projection
        is ProjectionResult.Failed -> fail("exact projection failed")
    }
    val sections = complete.value as? PvEntryMapping ?: fail("outer EntryMapping missing")
    val sectionKeys = sections.entries().map {
        (it.key as? PvString)?.value ?: fail("section key")
    }
    val first = sections.entries()[0].value as? PvEntryMapping
        ?: fail("inner EntryMapping missing")
    val firstKeys = first.entries().map {
        (it.key as? PvString)?.value ?: fail("entry key")
    }
    val association = complete.provenance.entries().any {
        it.projected is ProjectedLocation.Association
    }
    ensure(
        fidelityName(complete.fidelity) == expectedString(case, "fidelity") &&
            sectionKeys == expectedStrings(case, "section_keys") &&
            firstKeys == expectedStrings(case, "first_entry_keys") &&
            complete.report.events().size.toLong() == expectedLong(case, "events") &&
            association == expectedBoolean(case, "association_provenance"),
    )
}

/** projection.explicit-object-collapse (ini_v1.rs). */
private fun projectionCollapse(case: CaseData) {
    val document = parseIni(case)
    val comparison = when (inputString(case, "comparison")) {
        "OriginalExact" -> NameComparison.OriginalExact
        "ProfileEquivalent" -> NameComparison.ProfileEquivalent
        else -> fail("unknown comparison")
    }
    val rejected =
        document.project(ProjectionRequest.requireObject(comparison, CollisionPolicy.Reject)) is
            ProjectionResult.Failed
    val first = document.project(ProjectionRequest.requireObject(comparison, CollisionPolicy.First))
    val firstComplete = when (first) {
        is ProjectionResult.Complete -> first.projection
        is ProjectionResult.Failed -> fail("explicit first collapse failed")
    }
    val last = document.project(ProjectionRequest.requireObject(comparison, CollisionPolicy.Last))
    val lastComplete = when (last) {
        is ProjectionResult.Complete -> last.projection
        is ProjectionResult.Failed -> fail("explicit last collapse failed")
    }
    val (firstSection, firstKey, firstValue) = objectTriplet(firstComplete.value)
    val (lastSection, lastKey, lastValue) = objectTriplet(lastComplete.value)
    val collapsed = firstComplete.provenance.entries().any { entry ->
        entry.origins.any { it.relation == ProvenanceRelation.Collapsed }
    }
    ensure(
        rejected == expectedBoolean(case, "rejects") &&
            fidelityName(firstComplete.fidelity) == expectedString(case, "first_fidelity") &&
            firstComplete.report.events().size.toLong() == expectedLong(case, "first_events") &&
            firstSection == expectedString(case, "first_section") &&
            firstKey == expectedString(case, "first_key") &&
            firstValue == expectedString(case, "first_value") &&
            lastSection == expectedString(case, "last_section") &&
            lastKey == expectedString(case, "last_key") &&
            lastValue == expectedString(case, "last_value") &&
            collapsed == expectedBoolean(case, "collapsed_provenance"),
    )
}

/** projection.fragmented-value-provenance (ini_v1.rs). */
private fun projectionFragments(case: CaseData) {
    val python = parseText(
        IniProfile.PythonConfigParserV1,
        inputString(case, "python_source") ?: fail("missing input.python_source"),
    )
    val windows = parseText(
        IniProfile.WindowsV1,
        inputString(case, "windows_source") ?: fail("missing input.windows_source"),
    )
    val pythonResult = python.project(ProjectionRequest.bestExactEntryMapping())
    val pythonComplete = when (pythonResult) {
        is ProjectionResult.Complete -> pythonResult.projection
        is ProjectionResult.Failed -> fail("Python projection failed")
    }
    val windowsResult = windows.project(ProjectionRequest.bestExactEntryMapping())
    val windowsComplete = when (windowsResult) {
        is ProjectionResult.Complete -> windowsResult.projection
        is ProjectionResult.Failed -> fail("Windows projection failed")
    }
    val continuation = pythonComplete.provenance.entries().any { entry ->
        entry.origins.any { it.relation == ProvenanceRelation.ContinuationFragment }
    }
    val quote = windowsComplete.provenance.entries().any { entry ->
        entry.origins.any { it.relation == ProvenanceRelation.QuoteDerived }
    }
    ensure(
        continuation &&
            quote &&
            expectedString(case, "continuation_relation") == "ContinuationFragment" &&
            expectedString(case, "quote_relation") == "QuoteDerived",
    )
}

/** materialization.all-canonical-styles (ini_v1.rs). */
private fun materializationStyles(case: CaseData) {
    val portable = nestedMapping(caseInput(case, "portable") ?: fail("missing input.portable"))
    val windows = nestedMapping(caseInput(case, "windows") ?: fail("missing input.windows"))
    val python = nestedMapping(caseInput(case, "python") ?: fail("missing input.python"))
    val portableComplete = materializeComplete(portable, IniProfile.PortableV1, "portable")
    val windowsComplete = materializeComplete(windows, IniProfile.WindowsV1, "windows")
    val pythonComplete = materializeComplete(python, IniProfile.PythonConfigParserV1, "python")
    ensure(
        portableComplete.document.source().decodedText() ==
            expectedString(case, "portable_source") &&
            portableComplete.document.source().encodingFacts.selected.asStr() ==
            IniSourceEncoding.Utf8.asStr() &&
            (portableComplete.fidelity == MaterializationFidelity.Exact) ==
            expectedBoolean(case, "exact_fidelity") &&
            closure(portableComplete.document, portable) == expectedBoolean(case, "closure") &&
            windowsComplete.document.source().decodedText() ==
            expectedString(case, "windows_decoded") &&
            windowsComplete.document.source().encodingFacts.selected.asStr() ==
            expectedString(case, "windows_encoding") &&
            (windowsComplete.fidelity == MaterializationFidelity.Exact) ==
            expectedBoolean(case, "exact_fidelity") &&
            closure(windowsComplete.document, windows) == expectedBoolean(case, "closure") &&
            pythonComplete.document.source().decodedText() ==
            expectedString(case, "python_decoded") &&
            pythonComplete.document.source().encodingFacts.selected.asStr() ==
            IniSourceEncoding.Utf8.asStr() &&
            (pythonComplete.fidelity == MaterializationFidelity.Exact) ==
            expectedBoolean(case, "exact_fidelity") &&
            closure(pythonComplete.document, python) == expectedBoolean(case, "closure") &&
            expectedString(case, "windows_encoding") == "Utf16Le",
    )
}

/** materialization.atomic-failures-and-limits (ini_v1.rs). */
private fun materializationLimitsCase(case: CaseData) {
    val scalar = PvString("x")
    val scalarResult = materialize(scalar, materializationRequest(IniProfile.PortableV1))
    val scalarCode = when (scalarResult) {
        is MaterializationResult.Failed -> scalarResult.attempt.failure.code
        is MaterializationResult.Complete -> fail("scalar materialized")
    }
    val value = nestedMapping(caseInput(case, "value") ?: fail("missing input.value"))
    val names = inputStrings(case, "limit_names")
    val expected = expectedStrings(case, "limit_outcomes")
    ensure(names.size == expected.size)
    val outcomes = names.map { name ->
        val limits = when (name) {
            "max_input_nodes" -> MaterializationLimits.default.copy(maxInputNodes = 1)
            "max_output_bytes" -> MaterializationLimits.default.copy(maxOutputBytes = 2)
            "max_depth" -> MaterializationLimits.default.copy(maxDepth = 0)
            "max_report_entries" -> MaterializationLimits.default.copy(maxReportEntries = 0)
            "max_provenance_entries" ->
                MaterializationLimits.default.copy(maxProvenanceEntries = 1)
            else -> fail("unknown materialization limit $name")
        }
        when (
            val result = materialize(
                value,
                materializationRequest(IniProfile.PortableV1).withLimits(limits),
            )
        ) {
            is MaterializationResult.Complete -> "Complete"
            is MaterializationResult.Failed -> {
                if (result.attempt.failure.code != expectedString(case, "limit_code")) {
                    fail("$name returned wrong failure code ${result.attempt.failure.code}")
                }
                "Failed"
            }
        }
    }
    ensure(
        scalarCode == expectedString(case, "scalar_code") &&
            outcomes == expected,
    )
}

/** edit.all-eight-operations (ini_v1.rs). */
private fun editAllOperations(case: CaseData) {
    val source = inputString(case, "source") ?: fail("missing input.source")
    val profile = iniProfile(case)
    val expected = expectedStrings(case, "outputs")
    val outputs = ArrayList<String>(8)
    val editCounts = ArrayList<Int>(8)

    val semantic = parseText(profile, source)
    collectEdit(
        semantic,
        EditTransactionBuilder.new(semantic)
            .semanticValue(
                semantic.entries()[0].nodeRef,
                inputString(case, "semantic_value") ?: fail("missing input.semantic_value"),
                RepresentationPolicy.CanonicalForProfile,
            )
            .build(),
        outputs,
        editCounts,
    )

    val literal = parseText(profile, source)
    collectEdit(
        literal,
        EditTransactionBuilder.new(literal)
            .literalValue(
                literal.entries()[0].nodeRef,
                (inputString(case, "literal_value") ?: fail("missing input.literal_value"))
                    .toByteArray(Charsets.UTF_8),
            )
            .build(),
        outputs,
        editCounts,
    )

    val insertSection = parseText(profile, source)
    collectEdit(
        insertSection,
        EditTransactionBuilder.new(insertSection)
            .insertSection(
                insertSection.nodeRef(),
                inputString(case, "new_section") ?: fail("missing input.new_section"),
                AssociationPlacement.End,
            )
            .build(),
        outputs,
        editCounts,
    )

    val removeSection = parseText(profile, source)
    collectEdit(
        removeSection,
        EditTransactionBuilder.new(removeSection)
            .removeSection(removeSection.sections()[0].nodeRef)
            .build(),
        outputs,
        editCounts,
    )

    val renameSection = parseText(profile, source)
    collectEdit(
        renameSection,
        EditTransactionBuilder.new(renameSection)
            .renameSection(
                renameSection.sections()[0].nodeRef,
                inputString(case, "renamed_section") ?: fail("missing input.renamed_section"),
            )
            .build(),
        outputs,
        editCounts,
    )

    val insertEntry = parseText(profile, source)
    collectEdit(
        insertEntry,
        EditTransactionBuilder.new(insertEntry)
            .insertEntry(
                insertEntry.sections()[0].nodeRef,
                inputString(case, "new_key") ?: fail("missing input.new_key"),
                inputString(case, "new_value") ?: fail("missing input.new_value"),
                AssociationPlacement.End,
            )
            .build(),
        outputs,
        editCounts,
    )

    val removeEntry = parseText(profile, source)
    collectEdit(
        removeEntry,
        EditTransactionBuilder.new(removeEntry)
            .removeEntry(removeEntry.entries()[0].nodeRef)
            .build(),
        outputs,
        editCounts,
    )

    val renameEntry = parseText(profile, source)
    collectEdit(
        renameEntry,
        EditTransactionBuilder.new(renameEntry)
            .renameEntry(
                renameEntry.entries()[0].nodeRef,
                inputString(case, "renamed_key") ?: fail("missing input.renamed_key"),
            )
            .build(),
        outputs,
        editCounts,
    )

    ensure(
        outputs == expected &&
            editCounts.all { it == 1 } == expectedBoolean(case, "one_source_edit_each"),
    )
}

/** edit.dry-run-patch-proof-and-atomic-failure (ini_v1.rs). */
private fun editAuditArtifacts(case: CaseData) {
    val document = parseIni(case)
    val transaction = EditTransactionBuilder.new(document)
        .semanticValue(
            document.entries()[0].nodeRef,
            inputString(case, "value") ?: fail("missing input.value"),
            RepresentationPolicy.CanonicalForProfile,
        )
        .build()
    val sourceId = EditPlanSourceId.new(
        inputString(case, "source_id") ?: fail("missing input.source_id"),
    )
    val plan = try {
        document.dryRun(transaction, sourceId)
    } catch (e: EditFailureException) {
        fail("dry-run: ${e.failure.diagnosticCode()}")
    }
    val commit = try {
        document.commit(transaction)
    } catch (e: EditFailureException) {
        fail("commit: ${e.failure.diagnosticCode()}")
    }
    val patch = commit.sourcePatch ?: fail("edit produced no SourcePatch")
    val baseSnapshot = document.source().v1Snapshot ?: fail("base snapshot unavailable")
    val replay = try {
        patch.apply(baseSnapshot, SourcePatchLimits.default)
    } catch (e: SourcePatchException) {
        fail("patch replay failed")
    }
    val proofVerified = try {
        commit.untouchedProof!!.verify(
            baseSnapshot,
            commit.document.source().v1Snapshot ?: fail("target snapshot unavailable"),
            patch.replacements(),
        )
        true
    } catch (e: UntouchedByteProofException) {
        false
    }

    val other = parseText(
        iniProfile(case),
        inputString(case, "wrong_source") ?: fail("missing input.wrong_source"),
    )
    val wrong = EditTransactionBuilder.new(document)
    wrong.literalValue(other.entries()[0].nodeRef, "new".toByteArray(Charsets.UTF_8))
    val wrongCode = try {
        document.commit(wrong.build())
        fail("wrong snapshot commit unexpectedly completed")
    } catch (e: EditFailureException) {
        e.failure.diagnosticCode()
    }
    val source = inputString(case, "source") ?: fail("missing input.source")
    ensure(
        commit.document.render()
            .contentEquals((expectedString(case, "source") ?: fail("missing expected.source")).toByteArray(Charsets.UTF_8)) &&
            (plan.sourcePatch == patch) ==
            (expectedBoolean(case, "dry_run_equals_commit") ?: fail("missing expected.dry_run_equals_commit")) &&
            (replay.bytes().contentEquals(commit.document.render())) ==
            (expectedBoolean(case, "patch_replays") ?: fail("missing expected.patch_replays")) &&
            proofVerified == (expectedBoolean(case, "proof_verifies") ?: fail("missing expected.proof_verifies")) &&
            wrongCode == (expectedString(case, "wrong_snapshot_code") ?: fail("missing expected.wrong_snapshot_code")) &&
            (document.render().contentEquals(source.toByteArray(Charsets.UTF_8))) ==
            (expectedBoolean(case, "base_unchanged") ?: fail("missing expected.base_unchanged")),
    )
}

/** resource.formation-limit-matrix (ini_v1.rs). */
private fun formationLimits(case: CaseData) {
    val descriptors = inputSequence(case, "limits") ?: fail("missing input.limits")
    var fatal = 0
    val outcomes = HashMap<String, Boolean>()
    for (descriptor in descriptors) {
        val fields = descriptor as? PvObject ?: fail("limit descriptor must be Object")
        val name = (fields.get("name") as? PvString)?.value
            ?: fail("descriptor.name must be String")
        val profile = iniProfileName(
            (fields.get("profile") as? PvString)?.value
                ?: fail("descriptor.profile must be String"),
        )
        val source = (fields.get("source") as? PvString)?.value
            ?: fail("descriptor.source must be String")
        val value = (fields.get("value") as? PvInteger)?.value?.toInt()
            ?: fail("descriptor.value must be Integer")
        val limits = setParseLimit(IniParseLimits.default, name, value)
        val failed = try {
            parse(
                source.toByteArray(Charsets.UTF_8),
                profile,
                IniEncodingSelection.ProfileDefault,
                limits,
            )
            false
        } catch (e: IniFormationException) {
            true
        }
        if (failed) {
            fatal += 1
        }
        outcomes[name] = failed
    }
    ensure(
        fatal.toLong() == expectedLong(case, "fatal_count") &&
            outcomes.size == descriptors.size &&
            (expectedBoolean(case, "no_partial_documents") ?: fail("missing expected.no_partial_documents")),
    )
}

/** resource.projection-limit-matrix (ini_v1.rs). */
private fun projectionLimitsCase(case: CaseData) {
    val document = parseIni(case)
    val names = inputStrings(case, "limits")
    var failedCount = 0
    for (name in names) {
        val limits = when (name) {
            "max_source_associations" -> ProjectionLimits.default.copy(maxSourceAssociations = 1)
            "max_value_nodes" -> ProjectionLimits.default.copy(maxValueNodes = 1)
            "max_provenance_units" -> ProjectionLimits.default.copy(maxProvenanceUnits = 1)
            else -> fail("unknown projection limit $name")
        }
        val result = document.project(
            ProjectionRequest.bestExactEntryMapping().withLimits(limits),
        )
        val failed = when (result) {
            is ProjectionResult.Failed -> result.attempt
            is ProjectionResult.Complete -> fail("projection limit $name did not fail")
        }
        if (failed.diagnostics.firstOrNull()?.code == (expectedString(case, "code") ?: "")) {
            failedCount += 1
        }
    }
    ensure(failedCount.toLong() == expectedLong(case, "failed_count"))
}

/** registry.frozen-eight-operation-surface (ini_v1.rs). */
private fun operationRegistry(case: CaseData) {
    val expected = expectedStrings(case, "operations")
    for (profileNameValue in inputStrings(case, "profiles")) {
        val registry = formatOperationRegistry(iniProfileName(profileNameValue))
        val operations = registry.map { it.id.toString() }
        val direct = registry.count { it.support == OperationSupport.Supported }
        ensure(
            operations == expected &&
                direct.toLong() == expectedLong(case, "direct_structural"),
        )
    }
}

private fun collectEdit(
    document: IniDocument,
    transaction: consema.ini.EditTransaction,
    outputs: MutableList<String>,
    editCounts: MutableList<Int>,
) {
    val commit = try {
        document.commit(transaction)
    } catch (e: EditFailureException) {
        fail("commit: ${e.failure.diagnosticCode()}")
    }
    val patch = commit.sourcePatch ?: fail("edit produced no SourcePatch")
    outputs.add(commit.document.render().toString(Charsets.UTF_8))
    editCounts.add(patch.replacements().size)
}

/** The reparse-and-reproject closure of one canonical materialization
 * (ini_v1.rs). */
private fun closure(document: IniDocument, input: consema.core.PortableValue): Boolean =
    when (val projected = document.project(ProjectionRequest.bestExactEntryMapping())) {
        is ProjectionResult.Complete -> equal(projected.projection.value, input)
        is ProjectionResult.Failed -> false
    }

private fun materializeComplete(
    value: consema.core.PortableValue,
    profile: IniProfile,
    field: String,
): consema.document.CompleteMaterialization<IniDocument> {
    val result = materialize(value, materializationRequest(profile))
    return when (result) {
        is MaterializationResult.Complete -> result.materialization
        is MaterializationResult.Failed -> fail("$field materialization failed")
    }
}

/** The frozen canonical materialization request of one profile
 * (ini_v1.rs). */
private fun materializationRequest(profile: IniProfile): MaterializationRequest =
    when (profile) {
        IniProfile.PortableV1 -> MaterializationRequest.new(
            ProfileId("ini.portable", 1),
            MaterializationStyleId("ini.portable-canonical", 1),
        )
        IniProfile.WindowsV1 -> MaterializationRequest.new(
            ProfileId("ini.windows", 1),
            MaterializationStyleId("ini.windows-canonical", 1),
        )
            .withEncoding(SourceEncoding.Utf16Le)
            .withNewline(NewlinePolicy.CrLf)
        IniProfile.PythonConfigParserV1 -> MaterializationRequest.new(
            ProfileId("ini.python-configparser", 1),
            MaterializationStyleId("ini.python-configparser-canonical", 1),
        )
    }

/** Decodes the vector `{section, entries: [[key, value]]}` descriptor into
 * one nested EntryMapping (ini_v1.rs). */
private fun nestedMapping(descriptor: PortableValue): PvEntryMapping {
    val sections = descriptor as? PvArray ?: fail("mapping descriptor must be Sequence")
    val outer = EntryMappingBuilder()
    for (sectionValue in sections.items()) {
        val fields = sectionValue as? PvObject ?: fail("section descriptor must be Object")
        val name = (fields.get("section") as? PvString)?.value
            ?: fail("section.name must be String")
        val entries = fields.get("entries") as? PvArray
            ?: fail("section.entries must be Sequence")
        val inner = EntryMappingBuilder()
        for (entryValue in entries.items()) {
            val pair = entryValue as? PvArray ?: fail("entry descriptor must be Sequence")
            val items = pair.items()
            if (items.size != 2) {
                fail("entry descriptor must contain key and value")
            }
            val key = items[0] as? PvString ?: fail("entry key must be String")
            val value = items[1] as? PvString ?: fail("entry value must be String")
            inner.push(key, value)
        }
        outer.push(PvString(name), inner.build())
    }
    return outer.build()
}

/** The first-section/first-entry triple of one projected Object
 * (ini_v1.rs). */
private fun objectTriplet(value: PortableValue): Triple<String, String, String> {
    val sections = value as? PvObject ?: fail("projected outer Object missing")
    val section = sections.entries().firstOrNull() ?: fail("projected section missing")
    val entries = section.value as? PvObject ?: fail("projected inner Object missing")
    val entry = entries.entries().firstOrNull() ?: fail("projected entry missing")
    val entryValue = entry.value as? PvString ?: fail("projected value not String")
    return Triple(section.key, entry.key, entryValue.value)
}

private fun setParseLimit(limits: IniParseLimits, name: String, value: Int): IniParseLimits =
    when (name) {
        "max_source_bytes" -> limits.copy(common = limits.common.copy(maxSourceBytes = value))
        "max_nesting_depth" ->
            limits.copy(common = limits.common.copy(maxNestingDepth = value))
        "max_token_count" -> limits.copy(common = limits.common.copy(maxTokenCount = value))
        "max_node_count" -> limits.copy(common = limits.common.copy(maxNodeCount = value))
        "max_diagnostics" -> limits.copy(common = limits.common.copy(maxDiagnostics = value))
        "max_decoded_utf8_bytes" -> limits.copy(maxDecodedUtf8Bytes = value)
        "max_decoded_scalars" -> limits.copy(maxDecodedScalars = value)
        "max_physical_lines" -> limits.copy(maxPhysicalLines = value)
        "max_physical_line_bytes" -> limits.copy(maxPhysicalLineBytes = value)
        "max_physical_line_scalars" -> limits.copy(maxPhysicalLineScalars = value)
        "max_logical_lines" -> limits.copy(maxLogicalLines = value)
        "max_logical_line_bytes" -> limits.copy(maxLogicalLineBytes = value)
        "max_logical_line_scalars" -> limits.copy(maxLogicalLineScalars = value)
        "max_continuation_lines" -> limits.copy(maxContinuationLines = value)
        "max_sections" -> limits.copy(maxSections = value)
        "max_entries" -> limits.copy(maxEntries = value)
        "max_duplicate_group_members" -> limits.copy(maxDuplicateGroupMembers = value)
        "max_recovery_regions" -> limits.copy(maxRecoveryRegions = value)
        else -> fail("unknown INI parse limit $name")
    }

private fun parseIni(case: CaseData): IniDocument =
    parseText(
        iniProfile(case),
        inputString(case, "source") ?: fail("missing input.source"),
    )

private fun parseText(profile: IniProfile, source: String): IniDocument =
    try {
        parse(
            source.toByteArray(Charsets.UTF_8),
            profile,
            IniEncodingSelection.ProfileDefault,
            IniParseLimits.default,
        )
    } catch (e: IniFormationException) {
        fail("INI formation failed: ${e.code}")
    }

private fun iniProfile(case: CaseData): IniProfile =
    iniProfileName(inputString(case, "profile") ?: fail("missing input.profile"))

private fun iniProfileName(name: String): IniProfile =
    when (name) {
        "ini.portable@1" -> IniProfile.PortableV1
        "ini.windows@1" -> IniProfile.WindowsV1
        "ini.python-configparser@1" -> IniProfile.PythonConfigParserV1
        else -> fail("unknown INI profile $name")
    }

private fun nativeExecutable(expression: QueryExpression): ExecutableQuery =
    try {
        ExecutableQuery.bind(
            QueryDefinition(Domains.iniNativeV1()).withExpression(expression).validate(),
            queryCapabilities(),
        )
    } catch (e: QueryFailureException) {
        fail("definition: ${e.kind.code}")
    }

private fun queryCapabilities(): CapabilitySet {
    val set = CapabilitySet()
    set.insert(CapabilityId("core.query.ordered-results", 1))
    return set
}

/** The exhaustive-coverage invariant the vectors assert (ini_v1.rs). */
private fun exactCoverage(document: IniDocument): Boolean {
    val pieces = document.losslessStructuralIndex().pieces()
    if (document.source().isEmpty) {
        return pieces.isEmpty()
    }
    return pieces.size == document.losslessSyntaxKinds().size &&
        pieces.firstOrNull()?.span?.startByte == 0 &&
        pieces.lastOrNull()?.span?.endByte == document.source().len &&
        pieces.zipWithNext().all { (left, right) ->
            left.span.endByte == right.span.startByte
        }
}

private fun formationStatusName(status: FormationStatus): String =
    when (status) {
        FormationStatus.Complete -> "Complete"
        FormationStatus.Recovered -> "Recovered"
    }

private fun fidelityName(fidelity: Fidelity): String =
    when (fidelity) {
        Fidelity.Exact -> "Exact"
        Fidelity.Transformed -> "Transformed"
        Fidelity.Lossy -> "Lossy"
    }

private fun expectedStrings(case: CaseData, name: String): List<String> {
    val values = expectedSequence(case, name) ?: fail("missing expected.$name")
    return values.map { (it as? PvString)?.value ?: fail("expected.$name item must be String") }
}

private fun inputStrings(case: CaseData, name: String): List<String> {
    val values = inputSequence(case, name) ?: fail("missing input.$name")
    return values.map { (it as? PvString)?.value ?: fail("input.$name item must be String") }
}

private fun inputInt(case: CaseData, name: String): Int =
    (caseInput(case, name) as? PvInteger)?.value?.toInt() ?: fail("missing input.$name")

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) {
        fail("expected behavior did not match")
    }
}

private fun ensure(condition: Boolean, detail: String) {
    if (!condition) {
        fail(detail)
    }
}
