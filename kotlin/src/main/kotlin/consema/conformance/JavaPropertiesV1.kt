// The `consema.java-properties.conformance@1` suite runner
// (conformance/vectors/java-properties-v1.json).
//
// Data authority: crates/consema-conformance/src/properties_v1.rs (the
// per-case dispatch is transcribed from the Rust handlers); the vector file
// itself drives every input and expectation (conformance/README.md rules
// 3-4). go/conformance is a cross-reference only.

package consema.conformance

import consema.core.EntryMappingBuilder
import consema.core.PortableValue
import consema.core.PvArray
import consema.core.PvBytes
import consema.core.PvEntryMapping
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.core.equal
import consema.document.AssociationPlacement
import consema.document.EditPlanSourceId
import consema.document.FormationStatus
import consema.document.MaterializationLimits
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.properties.CancellationToken
import consema.properties.DuplicatePolicy
import consema.properties.EditFailureException
import consema.properties.EditTransactionBuilder
import consema.properties.JavaString
import consema.properties.ProjectedLocation
import consema.properties.ProjectionLimits
import consema.properties.ProjectionRequest
import consema.properties.ProjectionResult
import consema.properties.PropertiesEncoding
import consema.properties.PropertiesFormationException
import consema.properties.PropertiesMatch
import consema.properties.PropertiesParseLimits
import consema.properties.PropertiesProfile
import consema.properties.PropertiesSyntaxKind
import consema.properties.ProvenanceRelation
import consema.properties.QueryLimits
import consema.properties.WindowsCodePage
import consema.properties.commit
import consema.properties.dryRun
import consema.properties.editFailureCode
import consema.properties.executePropertiesQuery
import consema.properties.executePropertiesSyntaxQuery
import consema.properties.formatOperationRegistry
import consema.properties.materialize
import consema.properties.parse
import consema.properties.parseLatin1
import consema.properties.parseReader
import consema.properties.project
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.Domains
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryDomain
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QueryFailureKind
import java.math.BigInteger

/** Runs the `consema.java-properties.conformance@1` suite. */
fun runJavaPropertiesV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runJavaPropertiesV1Case(case)
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

private fun runJavaPropertiesV1Case(case: CaseData) {
    when (case.id) {
        "formation.reader-lines-escapes-duplicates" -> formationReader(case)
        "formation.empty-blank-comment-empty-key" -> formationBasicMatrix(case)
        "formation.mixed-line-terminators" -> formationTerminators(case)
        "formation.continuation-and-backslash-parity" -> formationContinuations(case)
        "formation.escape-and-java-utf16-matrix" -> formationJavaStrings(case)
        "formation.malformed-unicode-recovery-matrix" -> formationRecoveryMatrix(case)
        "formation.reader-explicit-encodings" -> formationReaderEncodings(case)
        "formation.latin1-byte-and-bom-content" -> formationLatin1(case)
        "formation.recovery-never-publishes-partial-operation" -> recoveredIsAtomic(case)
        "query.native-duplicates-and-escape-ownership" -> nativeQuery(case)
        "query.logical-and-syntax-order" -> logicalSyntaxQuery(case)
        "query.validation-limit-cancellation" -> queryFailures(case)
        "projection.exact-duplicates-and-fragments" -> projectionExact(case)
        "projection.unpaired-and-recovered-atomic-failure" -> projectionFailures(case)
        "projection.explicit-jdk-table-collapse" -> projectionCollapse(case)
        "materialization.canonical-styles-encodings-and-closure" -> materializationStyles(case)
        "materialization.atomic-failures-and-limits" -> materializationLimits(case)
        "edit.all-five-operations" -> editAllOperations(case)
        "edit.dry-run-patch-proof-conflict-atomicity" -> editAuditArtifacts(case)
        "resource.formation-limit-matrix" -> formationLimits(case)
        "resource.projection-limit-matrix" -> projectionLimits(case)
        "registry.frozen-five-operation-surface" -> operationRegistry(case)
        else -> fail("runner does not recognize published Properties case")
    }
}

// ---------------------------------------------------------------------------
// formation.*
// ---------------------------------------------------------------------------

private fun formationReader(case: CaseData) {
    val document = parseCaseDocument(case)
    ensure(
        document.formationStatus().name == expectedString(case, "formation") &&
            document.naturalLines().size == expectedInt(case, "natural_lines") &&
            document.logicalLines().size == expectedInt(case, "logical_lines") &&
            document.comments().size == expectedInt(case, "comments") &&
            document.properties().size == expectedInt(case, "properties") &&
            document.escapes().size == expectedInt(case, "escapes") &&
            document.properties().map { it.key().toUnicode() } == expectedStrings(case, "keys") &&
            document.properties().map { it.value().toUnicode() } == expectedStrings(case, "values") &&
            document.properties().map { it.valueState().name } == expectedStrings(case, "states") &&
            (document.properties()[1].duplicateGroup() != null &&
                document.properties()[1].duplicateGroup() == document.properties()[2].duplicateGroup()) ==
            expectedBool(case, "duplicate_group") &&
            exactCoverage(document) == expectedBool(case, "exact_coverage"),
    )
}

private fun formationBasicMatrix(case: CaseData) {
    val samples = inputStrings(case, "samples")
    val formations = expectedStrings(case, "formations")
    val properties = expectedLongs(case, "properties")
    val comments = expectedLongs(case, "comments")
    ensure(samples.size == formations.size && samples.size == properties.size && samples.size == comments.size)
    for ((index, source) in samples.withIndex()) {
        val document = parseReaderText(source)
        ensure(
            document.formationStatus().name == formations[index] &&
                document.properties().size == properties[index] &&
                document.comments().size == comments[index] &&
                exactCoverage(document),
        )
    }
}

private fun formationTerminators(case: CaseData) {
    val document = parseCaseDocument(case)
    val terminators = document.naturalLines().map { line ->
        when (line.lineBreakSpan()?.let { span ->
            document.render().copyOfRange(span.startByte, span.endByte)
                .toString(Charsets.US_ASCII)
        }) {
            "\n" -> "Lf"
            "\r" -> "Cr"
            "\r\n" -> "CrLf"
            null -> "Eof"
            else -> "Other"
        }
    }
    ensure(
        document.naturalLines().size == expectedInt(case, "natural_lines") &&
            document.logicalLines().size == expectedInt(case, "logical_lines") &&
            document.properties().size == expectedInt(case, "properties") &&
            terminators == expectedStrings(case, "terminators") &&
            exactCoverage(document) == expectedBool(case, "exact_coverage"),
    )
}

private fun formationContinuations(case: CaseData) {
    val samples = inputSequence(case, "samples") ?: fail("missing input.samples")
    for ((index, sample) in samples.withIndex()) {
        val source = stringField(sample, "source") ?: fail("missing sample.source")
        val expectedValue = stringField(sample, "value_hex") ?: fail("missing sample.value_hex")
        val document = parseReaderText(source)
        ensure(
            document.formationStatus() == FormationStatus.Complete &&
                toHex(document.properties()[0].value().utf16beBytes()) == expectedValue &&
                document.naturalLines().size == (longField(sample, "natural_lines") ?: fail("missing sample.natural_lines")).toInt() &&
                document.logicalLines().size == (longField(sample, "logical_lines") ?: fail("missing sample.logical_lines")).toInt() &&
                exactCoverage(document),
        )
    }
    ensure(expectedBool(case, "all_complete") && expectedBool(case, "exact_coverage"))
}

private fun formationJavaStrings(case: CaseData) {
    val document = parseCaseDocument(case)
    val values = document.properties().map { toHex(it.value().utf16beBytes()) }
    val statuses = document.properties().map { it.value().status.name }
    val escapes = document.escapes().map { it.kind().name }
    ensure(
        values == expectedStrings(case, "value_utf16be_hex") &&
            statuses == expectedStrings(case, "statuses") &&
            escapes == expectedStrings(case, "escape_kinds"),
    )
}

private fun formationRecoveryMatrix(case: CaseData) {
    val samples = inputStrings(case, "samples")
    val formations = expectedStrings(case, "formations")
    val propertyCounts = expectedLongs(case, "property_counts")
    val errorCounts = expectedLongs(case, "error_counts")
    val code = expectedString(case, "code") ?: fail("missing expected.code")
    ensure(samples.size == formations.size && samples.size == propertyCounts.size && samples.size == errorCounts.size)
    for ((index, source) in samples.withIndex()) {
        val document = parseReaderText(source)
        ensure(
            document.formationStatus().name == formations[index] &&
                document.properties().size == propertyCounts[index] &&
                document.errorLines().size == errorCounts[index] &&
                (document.errorLines().isEmpty() || document.errorLines()[0].code() == code),
        )
        if (index + 1 == samples.size) {
            ensure(
                document.properties()[0].value().toUnicode() ==
                    expectedString(case, "uppercase_u_value"),
            )
        }
    }
}

private fun formationReaderEncodings(case: CaseData) {
    val samples = inputSequence(case, "samples") ?: fail("missing input.samples")
    for ((index, sample) in samples.withIndex()) {
        val encodingName = stringField(sample, "encoding") ?: fail("missing sample.encoding")
        val bytes = decodeHex(stringField(sample, "source_hex") ?: fail("missing sample.source_hex"))
            ?: fail("invalid sample.source_hex")
        val document = when (encodingName) {
            "Utf8" -> parseReader(bytes, SourceEncoding.Utf8)
            "Utf16Le" -> parseReader(bytes, SourceEncoding.Utf16Le)
            "Utf16Be" -> parseReader(bytes, SourceEncoding.Utf16Be)
            else -> {
                val number = encodingName.removePrefix("WindowsCodePage(").removeSuffix(")")
                    .toIntOrNull() ?: fail("unknown source encoding $encodingName")
                val codePage = WindowsCodePage.fromNumber(number)
                    ?: fail("unsupported Windows code page $number")
                parse(bytes, PropertiesProfile.ReaderV1, PropertiesEncoding.WindowsCodePage(codePage.number))
            }
        }
        ensure(
            document.formationStatus() == FormationStatus.Complete &&
                document.render().contentEquals(bytes) &&
                document.properties()[0].key().toUnicode() == stringField(sample, "key") &&
                document.properties()[0].value().toUnicode() == stringField(sample, "value") &&
                bomSpelling(document) == stringField(sample, "bom") &&
                exactCoverage(document),
        )
    }
    ensure(
        expectedBool(case, "all_complete") &&
            expectedBool(case, "render_identity") &&
            expectedBool(case, "exact_coverage"),
    )
}

private fun formationLatin1(case: CaseData) {
    val bytes = decodeHex(inputString(case, "source_hex") ?: fail("missing input.source_hex"))
        ?: fail("invalid input.source_hex")
    val document = parseLatin1(bytes)
    ensure(
        toHex(document.properties()[0].key().utf16beBytes()) == expectedString(case, "key_utf16be_hex") &&
            toHex(document.properties()[0].value().utf16beBytes()) == expectedString(case, "value_utf16be_hex") &&
            bomSpelling(document) == expectedString(case, "bom") &&
            (PropertiesSyntaxKind.Bom in document.losslessSyntaxKinds()) == expectedBool(case, "bom_syntax") &&
            exactCoverage(document) == expectedBool(case, "exact_coverage"),
    )
}

private fun recoveredIsAtomic(case: CaseData) {
    val document = parseCaseDocument(case)
    val projectionCode = when (val result = document.project(ProjectionRequest.bestExactEntryMapping())) {
        is ProjectionResult.Failed -> result.attempt.diagnostics.firstOrNull()?.code
        is ProjectionResult.Complete -> null
    }
    val editCode = try {
        document.commit(EditTransactionBuilder.new(document).build())
        null
    } catch (e: EditFailureException) {
        editFailureCode(e.failure)
    }
    ensure(
        document.formationStatus().name == expectedString(case, "formation") &&
            document.properties().map { it.key().toUnicode() } == expectedStrings(case, "keys") &&
            document.errorLines().size == expectedInt(case, "error_lines") &&
            document.errorLines()[0].code() == expectedString(case, "code") &&
            projectionCode == expectedString(case, "projection_code") &&
            editCode == expectedString(case, "edit_code"),
    )
}

// ---------------------------------------------------------------------------
// query.*
// ---------------------------------------------------------------------------

private fun nativeQuery(case: CaseData) {
    val document = parseCaseDocument(case)
    val keyBytes = decodeHex(inputString(case, "key_utf16be_hex") ?: fail("missing input.key_utf16be_hex"))
        ?: fail("invalid input.key_utf16be_hex")
    val take = inputLong(case, "take")
    val duplicates = QueryExpression(ExpressionKind.Input)
        .then(OperatorCall("properties.document-properties", 1))
        .then(
            OperatorCall("properties.property-key-equals", 1)
                .withArgument("key", PvBytes.of(keyBytes)),
        )
        .then(OperatorCall("core.take", 1).withArgument("count", PvInteger(BigInteger.valueOf(take))))
        .then(OperatorCall("properties.duplicate-group", 1))
    val duplicateResult = executeQuery(propertiesExecutable(duplicates), document)
    val escapes = QueryExpression(ExpressionKind.Input)
        .then(OperatorCall("properties.document-properties", 1))
        .then(OperatorCall("core.take", 1).withArgument("count", PvInteger(BigInteger.valueOf(take))))
        .then(OperatorCall("properties.property-escapes", 1))
    val escapeResult = executeQuery(propertiesExecutable(escapes), document)
    ensure(
        duplicateResult.size == expectedInt(case, "duplicate_matches") &&
            escapeResult.size == expectedInt(case, "escape_matches") &&
            duplicateResult.all { it is PropertiesMatch.Property && it.duplicateGroup != null } ==
            expectedBool(case, "duplicate_group") &&
            escapeResult.all { it is PropertiesMatch.Escape } == expectedBool(case, "escape_roles") &&
            expectedString(case, "terminal") == "Completed",
    )
}

private fun logicalSyntaxQuery(case: CaseData) {
    val logical = parseReaderText(inputString(case, "logical_source") ?: fail("missing input.logical_source"))
    val logicalExpression = QueryExpression(ExpressionKind.Input)
        .then(OperatorCall("properties.logical-lines", 1))
        .then(OperatorCall("properties.logical-line-natural-lines", 1))
    val logicalResult = executeQuery(propertiesExecutable(logicalExpression), logical)
    val ordinals = logicalResult.map {
        (it as? PropertiesMatch.NaturalLine)?.ordinal ?: fail("logical query returned non-natural line")
    }
    ensure(ordinals == expectedLongs(case, "natural_ordinals"))

    val syntax = parseReaderText(inputString(case, "syntax_source") ?: fail("missing input.syntax_source"))
    val textArg = inputString(case, "text") ?: fail("missing input.text")
    val rawHex = inputString(case, "raw_hex") ?: fail("missing input.raw_hex")
    val utf16Hex = inputString(case, "utf16be_hex") ?: fail("missing input.utf16be_hex")
    val text = QueryExpression(ExpressionKind.Input)
        .then(OperatorCall("properties.syntax-text-equals", 1).withArgument("text", PvString(textArg)))
    val raw = QueryExpression(ExpressionKind.Input)
        .then(
            OperatorCall("properties.syntax-raw-bytes-equals", 1)
                .withArgument("bytes", PvBytes.of(decodeHex(rawHex) ?: fail("invalid input.raw_hex"))),
        )
    val utf16 = QueryExpression(ExpressionKind.Input)
        .then(
            OperatorCall("properties.syntax-utf16be-equals", 1)
                .withArgument("code_units", PvBytes.of(decodeHex(utf16Hex) ?: fail("invalid input.utf16be_hex"))),
        )
    val syntaxResult = executeSyntaxQuery(
        propertiesExecutable(
            QueryExpression(ExpressionKind.StructureOrderMerge, branches = listOf(text, raw, utf16)),
            Domains.javaPropertiesLosslessSyntaxV1(),
        ),
        syntax,
    )
    val kinds = syntaxResult.map { it.kind.asStr() }
    val increasing = syntaxResult.zipWithNext().all { (left, right) -> left.ordinal < right.ordinal }
    val role = stringField(case.expected, "syntax_role") ?: ""
    ensure(
        kinds == expectedStrings(case, "syntax_kinds") &&
            syntaxResult.all { it.node.role.name == role } &&
            increasing == expectedBool(case, "strictly_increasing_ordinals"),
    )
}

private fun queryFailures(case: CaseData) {
    val invalid = try {
        QueryDefinition(Domains.javaPropertiesNativeV1())
            .withExpression(
                QueryExpression(ExpressionKind.Input)
                    .then(OperatorCall("properties.document-properties", 1))
                    .then(
                        OperatorCall("properties.property-key-equals", 1)
                            .withArgument("key", PvBytes.of(byteArrayOf(0))),
                    ),
            )
            .validate()
        null
    } catch (e: QueryFailureException) {
        e.argument.takeIf { e.kind == QueryFailureKind.INVALID_ARGUMENT }
    }
    val document = parseCaseDocument(case)
    val all = propertiesExecutable(
        QueryExpression(ExpressionKind.Input).then(OperatorCall("properties.document-properties", 1)),
    )
    val limitFailure = try {
        executePropertiesQuery(
            all,
            document,
            QueryLimits(maxSteps = 100, maxResults = inputLong(case, "max_results").toInt()),
        )
        null
    } catch (e: QueryFailureException) {
        e.takeIf { it.kind == QueryFailureKind.RESOURCE_LIMIT }
    }
    val first = try {
        executePropertiesQuery(all, document).isNotEmpty()
    } catch (e: QueryFailureException) {
        fail("query must complete without limits: ${e.kind.code}")
    }
    val exhausted = try {
        val token = CancellationToken()
        token.cancel()
        executePropertiesQuery(all, document, cancellation = token)
        fail("cancelled query must not complete")
    } catch (e: QueryFailureException) {
        e.kind == QueryFailureKind.CANCELLED
    }
    ensure(
        invalid == expectedString(case, "invalid_argument") &&
            limitFailure?.kind?.code == expectedString(case, "limit_code") &&
            first == expectedBool(case, "first_yielded") &&
            exhausted &&
            expectedString(case, "terminal") == "Cancelled",
    )
}

// ---------------------------------------------------------------------------
// projection.*
// ---------------------------------------------------------------------------

private fun projectionExact(case: CaseData) {
    val document = parseCaseDocument(case)
    val result = document.project(ProjectionRequest.bestExactEntryMapping())
    val complete = result as? ProjectionResult.Complete ?: fail("exact Properties projection failed")
    val mapping = complete.projection.value as? PvEntryMapping
        ?: fail("exact projection did not produce EntryMapping")
    val keys = mapping.entries().map { (it.key as? PvString)?.value ?: fail("projected key not String") }
    val values = mapping.entries().map { (it.value as? PvString)?.value ?: fail("projected value not String") }
    val provenance = complete.projection.provenance.entries()
    val escape = provenance.any { entry ->
        entry.origins.any { it.relation == ProvenanceRelation.EscapeDerived }
    }
    val fragments = provenance.any { entry ->
        entry.origins.count { it.relation == ProvenanceRelation.ValueFragment } == 2
    }
    val association = provenance.any { entry ->
        entry.projected is ProjectedLocation.Association
    }
    ensure(
        complete.projection.fidelity.name == expectedString(case, "fidelity") &&
            keys == expectedStrings(case, "keys") &&
            values == expectedStrings(case, "values") &&
            complete.projection.report.events().size == expectedInt(case, "events") &&
            escape == expectedBool(case, "escape_provenance") &&
            fragments == expectedBool(case, "two_value_fragments") &&
            association == expectedBool(case, "association_provenance"),
    )
}

private fun projectionFailures(case: CaseData) {
    val unpaired = parseReaderText(inputString(case, "unpaired_source") ?: fail("missing input.unpaired_source"))
    val recovered = parseReaderText(inputString(case, "recovered_source") ?: fail("missing input.recovered_source"))
    val unpairedFailure = unpaired.project(ProjectionRequest.bestExactEntryMapping()) as? ProjectionResult.Failed
        ?: fail("unpaired surrogate projection completed")
    val recoveredFailure = recovered.project(ProjectionRequest.bestExactEntryMapping()) as? ProjectionResult.Failed
        ?: fail("recovered projection completed")
    ensure(
        unpairedFailure.attempt.diagnostics[0].code == expectedString(case, "unpaired_code") &&
            unpairedFailure.attempt.diagnostics[0].primary?.startByte ==
            expectedLong(case, "unpaired_start_byte")?.toULong() &&
            recoveredFailure.attempt.diagnostics[0].code == expectedString(case, "recovered_code") &&
            (unpairedFailure.attempt.report.events().isEmpty() &&
                recoveredFailure.attempt.report.events().isEmpty()) ==
            expectedBool(case, "empty_reports"),
    )
}

private fun projectionCollapse(case: CaseData) {
    val document = parseCaseDocument(case)
    val unique = document.project(ProjectionRequest.requireObject(DuplicatePolicy.RequireUnique))
    val uniqueCode = (unique as? ProjectionResult.Failed)?.attempt?.diagnostics?.firstOrNull()?.code
        ?: fail("unique projection accepted duplicates")
    val firstResult = document.project(ProjectionRequest.requireObject(DuplicatePolicy.FirstWins))
    val first = (firstResult as? ProjectionResult.Complete)?.projection
        ?: fail("FirstWins projection failed")
    val lastResult = document.project(ProjectionRequest.requireObject(DuplicatePolicy.LastWinsJdkTable))
    val last = (lastResult as? ProjectionResult.Complete)?.projection
        ?: fail("LastWinsJdkTable projection failed")
    val events = first.report.events()
    ensure(
        uniqueCode == expectedString(case, "unique_code") &&
            first.fidelity.name == expectedString(case, "first_fidelity") &&
            events.size == expectedInt(case, "events") &&
            events[0].code == expectedString(case, "event_code") &&
            objectPairs(first.value) == expectedPairs(case, "first_entries") &&
            objectPairs(last.value) == expectedPairs(case, "last_entries") &&
            first.provenance.entries().any { entry ->
                entry.origins.any { it.relation == ProvenanceRelation.Collapsed }
            } == expectedBool(case, "collapsed_provenance"),
    )
}

// ---------------------------------------------------------------------------
// materialization.*
// ---------------------------------------------------------------------------

private fun materializationStyles(case: CaseData) {
    val readerRequest = MaterializationRequest.new(
        ProfileId("java-properties.reader", 1),
        MaterializationStyleId("java-properties.reader-canonical", 1),
    )
    val readerValue = flatMapping(caseInput(case, "reader") ?: fail("missing input.reader"))
    val reader = materialize(readerValue, readerRequest) as? MaterializationResult.Complete
        ?: fail("Reader materialization failed")
    val latinRequest = MaterializationRequest.new(
        ProfileId("java-properties.latin1", 1),
        MaterializationStyleId("java-properties.latin1-canonical", 1),
    ).withEncoding(SourceEncoding.Latin1).withNewline(NewlinePolicy.CrLf)
    val latinValue = flatMapping(caseInput(case, "latin1") ?: fail("missing input.latin1"))
    val latin = materialize(latinValue, latinRequest) as? MaterializationResult.Complete
        ?: fail("Latin-1 materialization failed")
    val utf16Request = MaterializationRequest.new(
        ProfileId("java-properties.reader", 1),
        MaterializationStyleId("java-properties.reader-canonical", 1),
    ).withEncoding(SourceEncoding.Utf16Be).withNewline(NewlinePolicy.CrLf)
    val utf16Value = flatMapping(caseInput(case, "utf16be") ?: fail("missing input.utf16be"))
    val utf16 = materialize(utf16Value, utf16Request) as? MaterializationResult.Complete
        ?: fail("UTF-16BE Reader materialization failed")
    val cpValue = flatMapping(caseInput(case, "cp1252") ?: fail("missing input.cp1252"))
    val codePage = WindowsCodePage.fromNumber(1252) ?: fail("CP1252 unavailable")
    val cpResult = materialize(cpValue, readerRequest, codePage) as? MaterializationResult.Complete
        ?: fail("CP1252 Reader materialization failed")
    val closure = listOf(
        reader.materialization.document to readerValue,
        latin.materialization.document to latinValue,
        utf16.materialization.document to utf16Value,
        cpResult.materialization.document to cpValue,
    ).all { (document, value) ->
        when (val projection = document.project(ProjectionRequest.bestExactEntryMapping())) {
            is ProjectionResult.Complete -> equal(projection.projection.value, value)
            is ProjectionResult.Failed -> false
        }
    }
    ensure(
        String(reader.materialization.document.render(), Charsets.UTF_8) == expectedString(case, "reader_source") &&
            String(latin.materialization.document.render(), Charsets.UTF_8) == expectedString(case, "latin1_source") &&
            utf16.materialization.document.source().decodedText() == expectedString(case, "utf16be_decoded") &&
            toHex(cpResult.materialization.document.render()) == expectedString(case, "cp1252_hex") &&
            listOf(
                reader.materialization.fidelity,
                latin.materialization.fidelity,
                utf16.materialization.fidelity,
                cpResult.materialization.fidelity,
            ).all { it.name == "Exact" } == expectedBool(case, "exact_fidelity") &&
            closure == expectedBool(case, "closure"),
    )
}

private fun materializationLimits(case: CaseData) {
    val request = MaterializationRequest.new(
        ProfileId("java-properties.reader", 1),
        MaterializationStyleId("java-properties.reader-canonical", 1),
    )
    val scalar = materialize(PvString("scalar"), request) as? MaterializationResult.Failed
        ?: fail("scalar materialized")
    val scalarCode = scalar.attempt.failure.code
    val value = flatMapping(caseInput(case, "value") ?: fail("missing input.value"))
    val encodingRequest = MaterializationRequest.new(
        ProfileId("java-properties.latin1", 1),
        MaterializationStyleId("java-properties.latin1-canonical", 1),
    ).withEncoding(SourceEncoding.Utf8)
    val encoding = materialize(value, encodingRequest) as? MaterializationResult.Failed
        ?: fail("Latin-1 accepted UTF-8 request")
    val encodingCode = encoding.attempt.failure.code
    val names = inputStrings(case, "limit_names")
    val expected = expectedStrings(case, "limit_outcomes")
    ensure(names.size == expected.size)
    val limitCode = expectedString(case, "limit_code") ?: fail("missing expected.limit_code")
    val outcomes = names.map { name ->
        val limits = when (name) {
            "max_input_nodes" -> MaterializationLimits.default.copy(maxInputNodes = 1)
            "max_output_bytes" -> MaterializationLimits.default.copy(maxOutputBytes = 2)
            "max_depth" -> MaterializationLimits.default.copy(maxDepth = 0)
            "max_report_entries" -> MaterializationLimits.default.copy(maxReportEntries = 0)
            "max_provenance_entries" -> MaterializationLimits.default.copy(maxProvenanceEntries = 1)
            else -> fail("unknown materialization limit $name")
        }
        when (val result = materialize(value, request.withLimits(limits))) {
            is MaterializationResult.Complete -> "Complete"
            is MaterializationResult.Failed -> {
                ensure(result.attempt.failure.code == limitCode)
                "Failed"
            }
        }
    }
    ensure(
        scalarCode == expectedString(case, "scalar_code") &&
            encodingCode == expectedString(case, "encoding_code") &&
            outcomes == expected,
    )
}

// ---------------------------------------------------------------------------
// edit.*
// ---------------------------------------------------------------------------

private fun editAllOperations(case: CaseData) {
    val source = inputString(case, "source") ?: fail("missing input.source")
    val expected = expectedStrings(case, "outputs")
    val outputs = mutableListOf<String>()
    val editCounts = mutableListOf<Int>()

    val semantic = parseReaderText(source)
    collectEdit(
        semantic,
        EditTransactionBuilder.new(semantic)
            .semanticValue(
                semantic.properties()[0].nodeRef(),
                JavaString.fromUnicode(inputString(case, "semantic_value") ?: fail("missing input.semantic_value")),
            )
            .build(),
        outputs,
        editCounts,
    )

    val literal = parseReaderText(source)
    collectEdit(
        literal,
        EditTransactionBuilder.new(literal)
            .literalValue(
                literal.properties()[0].nodeRef(),
                (inputString(case, "literal_value") ?: fail("missing input.literal_value"))
                    .toByteArray(Charsets.UTF_8),
            )
            .build(),
        outputs,
        editCounts,
    )

    val inserted = parseReaderText(source)
    collectEdit(
        inserted,
        EditTransactionBuilder.new(inserted)
            .insertProperty(
                inserted.nodeRef(),
                JavaString.fromUnicode(inputString(case, "new_key") ?: fail("missing input.new_key")),
                JavaString.fromUnicode(inputString(case, "new_value") ?: fail("missing input.new_value")),
                AssociationPlacement.End,
            )
            .build(),
        outputs,
        editCounts,
    )

    val removed = parseReaderText(source)
    collectEdit(
        removed,
        EditTransactionBuilder.new(removed)
            .removeProperty(removed.properties()[0].nodeRef())
            .build(),
        outputs,
        editCounts,
    )

    val renamed = parseReaderText(source)
    collectEdit(
        renamed,
        EditTransactionBuilder.new(renamed)
            .renameProperty(
                renamed.properties()[0].nodeRef(),
                JavaString.fromUnicode(inputString(case, "renamed_key") ?: fail("missing input.renamed_key")),
            )
            .build(),
        outputs,
        editCounts,
    )

    ensure(
        outputs == expected &&
            editCounts.all { it == 1 } == expectedBool(case, "one_source_edit_each"),
    )
}

/** Commits one transaction and collects the render and source-edit count
 * (properties_v1.rs:1048-1061). */
private fun collectEdit(
    document: consema.properties.Document,
    transaction: consema.properties.EditTransaction,
    outputs: MutableList<String>,
    editCounts: MutableList<Int>,
) {
    val commit = try {
        document.commit(transaction)
    } catch (e: EditFailureException) {
        fail("edit commit: ${editFailureCode(e.failure)}")
    }
    outputs.add(String(commit.document.render(), Charsets.UTF_8))
    editCounts.add(commit.changeSet.sourceEdits.size)
}

private fun editAuditArtifacts(case: CaseData) {
    val document = parseCaseDocument(case)
    val first = document.properties()[0].nodeRef()
    val second = document.properties()[1].nodeRef()
    val builder = EditTransactionBuilder.new(document)
    builder
        .renameProperty(first, JavaString.fromUnicode(inputString(case, "rename") ?: fail("missing input.rename")))
        .semanticValue(second, JavaString.fromUnicode(inputString(case, "value") ?: fail("missing input.value")))
    val transaction = builder.build()
    val plan = try {
        document.dryRun(
            transaction,
            EditPlanSourceId.new(inputString(case, "source_id") ?: fail("missing input.source_id")),
        )
    } catch (e: EditFailureException) {
        fail("edit dry-run: ${editFailureCode(e.failure)}")
    }
    val commit = try {
        document.commit(transaction)
    } catch (e: EditFailureException) {
        fail("edit commit: ${editFailureCode(e.failure)}")
    }
    val replay = try {
        commit.sourcePatch.apply(document.source())
    } catch (e: consema.document.SourcePatchException) {
        fail("edit patch replay failed")
    }
    val proofVerifies = try {
        commit.untouchedProof.verify(
            document.source(),
            commit.document.source(),
            commit.sourcePatch.replacements(),
        )
        true
    } catch (e: Exception) {
        false
    }
    val conflict = EditTransactionBuilder.new(document)
        .semanticValue(first, JavaString.fromUnicode("x"))
        .renameProperty(first, JavaString.fromUnicode("renamed"))
        .build()
    val conflictCode = try {
        document.commit(conflict)
        fail("duplicate target must fail")
    } catch (e: EditFailureException) {
        editFailureCode(e.failure)
    }
    ensure(
        String(commit.document.render(), Charsets.UTF_8) == expectedString(case, "source") &&
            commit.changeSet.sourceEdits.size == expectedInt(case, "edit_count") &&
            plan.operations().size == expectedInt(case, "dry_run_operations") &&
            replay.bytes().contentEquals(commit.document.render()) == expectedBool(case, "patch_replays") &&
            proofVerifies == expectedBool(case, "proof_verifies") &&
            conflictCode == expectedString(case, "conflict_code") &&
            (String(document.render(), Charsets.UTF_8) == inputString(case, "source")) ==
            expectedBool(case, "base_unchanged"),
    )
}

// ---------------------------------------------------------------------------
// resource.* and registry.*
// ---------------------------------------------------------------------------

private fun formationLimits(case: CaseData) {
    val descriptors = inputSequence(case, "limits") ?: fail("missing input.limits")
    var fatal = 0
    val outcomes = HashMap<String, Boolean>()
    for (descriptor in descriptors) {
        val name = stringField(descriptor, "name") ?: fail("missing limit name")
        val source = stringField(descriptor, "source") ?: fail("missing limit source")
        val value = longField(descriptor, "value")?.toInt() ?: fail("missing limit value")
        val limits = setPropertiesParseLimit(name, value)
        val failed = try {
            parseReader(source.toByteArray(Charsets.UTF_8), SourceEncoding.Utf8, limits)
            false
        } catch (e: PropertiesFormationException) {
            true
        }
        if (failed) {
            fatal += 1
        }
        outcomes[name] = failed
    }
    ensure(
        fatal == expectedInt(case, "fatal_count") &&
            outcomes.size == descriptors.size &&
            expectedBool(case, "no_partial_documents"),
    )
}

private fun setPropertiesParseLimit(name: String, value: Int): PropertiesParseLimits {
    val defaults = PropertiesParseLimits.default
    return when (name) {
        "max_source_bytes" -> defaults.copy(common = defaults.common.copy(maxSourceBytes = value))
        "max_token_count" -> defaults.copy(common = defaults.common.copy(maxTokenCount = value))
        "max_node_count" -> defaults.copy(common = defaults.common.copy(maxNodeCount = value))
        "max_diagnostics" -> defaults.copy(common = defaults.common.copy(maxDiagnostics = value))
        "max_decoded_utf8_bytes" -> defaults.copy(maxDecodedUtf8Bytes = value)
        "max_decoded_scalars" -> defaults.copy(maxDecodedScalars = value)
        "max_natural_lines" -> defaults.copy(maxNaturalLines = value)
        "max_natural_line_bytes" -> defaults.copy(maxNaturalLineBytes = value)
        "max_natural_line_scalars" -> defaults.copy(maxNaturalLineScalars = value)
        "max_logical_lines" -> defaults.copy(maxLogicalLines = value)
        "max_logical_line_natural_lines" -> defaults.copy(maxLogicalLineNaturalLines = value)
        "max_logical_line_scalars" -> defaults.copy(maxLogicalLineScalars = value)
        "max_properties" -> defaults.copy(maxProperties = value)
        "max_comments" -> defaults.copy(maxComments = value)
        "max_escapes" -> defaults.copy(maxEscapes = value)
        "max_unicode_escapes" -> defaults.copy(maxUnicodeEscapes = value)
        "max_java_code_units_per_string" -> defaults.copy(maxJavaCodeUnitsPerString = value)
        "max_total_java_code_units" -> defaults.copy(maxTotalJavaCodeUnits = value)
        "max_duplicate_group_members" -> defaults.copy(maxDuplicateGroupMembers = value)
        "max_recovery_regions" -> defaults.copy(maxRecoveryRegions = value)
        else -> fail("unknown Properties parse limit $name")
    }
}

private fun projectionLimits(case: CaseData) {
    val document = parseCaseDocument(case)
    val code = expectedString(case, "code") ?: fail("missing expected.code")
    var failedCount = 0
    for (name in inputStrings(case, "limits")) {
        val limits = when (name) {
            "max_source_associations" -> ProjectionLimits.default.copy(maxSourceAssociations = 0)
            "max_value_nodes" -> ProjectionLimits.default.copy(maxValueNodes = 1)
            "max_provenance_units" -> ProjectionLimits.default.copy(maxProvenanceUnits = 1)
            else -> fail("unknown projection limit $name")
        }
        val result = document.project(ProjectionRequest.bestExactEntryMapping().withLimits(limits))
        val failed = result as? ProjectionResult.Failed ?: fail("projection limit $name did not fail")
        if (failed.attempt.diagnostics[0].code == code) {
            failedCount += 1
        }
    }
    val duplicate = parseReaderText(inputString(case, "duplicate_source") ?: fail("missing input.duplicate_source"))
    val reportLimited = duplicate.project(
        ProjectionRequest.requireObject(DuplicatePolicy.FirstWins)
            .withLimits(ProjectionLimits.default.copy(maxReportEntries = 0)),
    )
    val failed = reportLimited as? ProjectionResult.Failed ?: fail("report limit did not fail")
    if (failed.attempt.diagnostics[0].code == code) {
        failedCount += 1
    }
    ensure(failedCount == expectedInt(case, "failed_count"))
}

private fun operationRegistry(case: CaseData) {
    val expected = expectedStrings(case, "operations")
    val supported = expectedInt(case, "supported")
    for (profileName in inputStrings(case, "profiles")) {
        val registry = formatOperationRegistry(propertiesProfileName(profileName))
        ensure(
            registry.map { it.id.toString() } == expected &&
                registry.count { it.support.name == "Supported" } == supported,
        )
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/** Parses the vector case source under its declared profile. */
private fun parseCaseDocument(case: CaseData): consema.properties.Document {
    val source = inputString(case, "source") ?: fail("missing input.source")
    return when (propertiesProfileName(inputString(case, "profile") ?: fail("missing input.profile"))) {
        PropertiesProfile.ReaderV1 -> parseReaderText(source)
        PropertiesProfile.Latin1V1 -> parseLatin1(source.toByteArray(Charsets.UTF_8))
    }
}

private fun propertiesProfileName(name: String): PropertiesProfile =
    when (name) {
        "java-properties.reader@1" -> PropertiesProfile.ReaderV1
        "java-properties.latin1@1" -> PropertiesProfile.Latin1V1
        else -> fail("unknown Java Properties profile $name")
    }

private fun parseReaderText(source: String): consema.properties.Document =
    try {
        parseReader(source.toByteArray(Charsets.UTF_8), SourceEncoding.Utf8)
    } catch (e: PropertiesFormationException) {
        fail("Properties formation failed: ${e.code}")
    }

/** Exhaustive non-overlapping piece coverage (properties_v1.rs:1264-1279). */
private fun exactCoverage(document: consema.properties.Document): Boolean {
    val pieces = document.losslessStructuralIndex().pieces()
    if (document.source().len == 0) {
        return pieces.isEmpty()
    }
    return pieces.size == document.losslessSyntaxKinds().size &&
        pieces.firstOrNull()?.span?.startByte == 0 &&
        pieces.lastOrNull()?.span?.endByte == document.source().len &&
        pieces.zipWithNext().all { (left, right) -> left.span.endByte == right.span.startByte }
}

/** The Rust Debug spelling of the encoding-facts BOM fact
 * (properties_v1.rs:351, 374). */
private fun bomSpelling(document: consema.properties.Document): String {
    val bom = document.source().encodingFacts.bom ?: return "None"
    return "Some(${bom.name})"
}

/** One complete EntryMapping from a vector `[[key, value], ...]` descriptor. */
private fun flatMapping(descriptor: PortableValue): PortableValue {
    val entries = (descriptor as? PvArray)?.items() ?: fail("mapping descriptor must be Sequence")
    val builder = EntryMappingBuilder()
    for (entry in entries) {
        val pair = (entry as? PvArray)?.items() ?: fail("mapping entry must be Sequence")
        if (pair.size != 2) {
            fail("mapping entry must contain key and value")
        }
        builder.push(
            pair[0] as? PvString ?: fail("mapping key must be String"),
            pair[1] as? PvString ?: fail("mapping value must be String"),
        )
    }
    return builder.build()
}

private fun objectPairs(value: PortableValue): List<Pair<String, String>> {
    val obj = value as? PvObject ?: fail("projected Object missing")
    return obj.entries().map { entry ->
        entry.key to ((entry.value as? PvString)?.value ?: fail("projected Object value not String"))
    }
}

private fun propertiesExecutable(
    expression: QueryExpression,
    domain: QueryDomain = Domains.javaPropertiesNativeV1(),
): ExecutableQuery {
    val capabilities = CapabilitySet()
    capabilities.insert(CapabilityId("core.query.ordered-results", 1))
    return try {
        QueryDefinition(domain)
            .withExpression(expression)
            .validate()
            .let { ExecutableQuery.bind(it, capabilities) }
    } catch (e: QueryFailureException) {
        fail("properties query validation: ${e.kind.code}")
    }
}

/** Executes one native Properties query; a failure is a case failure. */
private fun executeQuery(
    executable: ExecutableQuery,
    document: consema.properties.Document,
): List<PropertiesMatch> =
    try {
        executePropertiesQuery(executable, document)
    } catch (e: QueryFailureException) {
        fail("properties query: ${e.kind.code}")
    }

/** Executes one lossless syntax Properties query; a failure is a case
 * failure. */
private fun executeSyntaxQuery(
    executable: ExecutableQuery,
    document: consema.properties.Document,
): List<consema.properties.PropertiesSyntaxMatch> =
    try {
        executePropertiesSyntaxQuery(executable, document)
    } catch (e: QueryFailureException) {
        fail("properties syntax query: ${e.kind.code}")
    }

private fun inputLong(case: CaseData, name: String): Long =
    longField(case.input, name) ?: fail("missing input.$name")

private fun expectedInt(case: CaseData, name: String): Int =
    expectedLong(case, name)?.toInt() ?: fail("missing expected.$name")

private fun expectedBool(case: CaseData, name: String): Boolean =
    expectedBoolean(case, name) ?: fail("missing expected.$name")

private fun expectedStrings(case: CaseData, name: String): List<String> =
    expectedSequence(case, name)
        ?.map { (it as? PvString)?.value ?: fail("expected.$name item must be String") }
        ?: fail("missing expected.$name")

private fun expectedLongs(case: CaseData, name: String): List<Int> =
    expectedSequence(case, name)
        ?.map { (it as? PvInteger)?.value?.toInt() ?: fail("expected.$name item must be Integer") }
        ?: fail("missing expected.$name")

private fun expectedPairs(case: CaseData, name: String): List<Pair<String, String>> =
    expectedSequence(case, name)?.map { pair ->
        val items = (pair as? PvArray)?.items() ?: fail("expected.$name item must be Sequence")
        if (items.size != 2) {
            fail("expected.$name pair length must be 2")
        }
        ((items[0] as? PvString)?.value ?: fail("expected.$name pair key must be String")) to
            ((items[1] as? PvString)?.value ?: fail("expected.$name pair value must be String"))
    } ?: fail("missing expected.$name")

private fun inputStrings(case: CaseData, name: String): List<String> =
    inputSequence(case, name)
        ?.map { (it as? PvString)?.value ?: fail("input.$name item must be String") }
        ?: fail("missing input.$name")

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}
