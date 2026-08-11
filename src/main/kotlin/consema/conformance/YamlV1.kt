// The `consema.yaml.conformance@1` suite runner
// (conformance/vectors/yaml-v1.json).
//
// Data authority: crates/consema-conformance/src/yaml_v1.rs (the per-case
// dispatch and every fact are transcribed from the Rust handlers; the
// capability mapping at yaml_v1.rs:117-134 and the case table at
// yaml_v1.rs:138-165); the vector file itself drives every input and
// expectation (conformance/README.md rules 3-4). go/conformance/yaml_v1.go
// is a cross-reference only.

package consema.conformance

import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvString
import consema.core.equal
import consema.document.AssociationPlacement
import consema.document.FormationStatus
import consema.document.MaterializationFidelity
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ParseLimits
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.document.UntouchedByteProofException
import consema.graph.GraphLimits
import consema.graph.PgceException
import consema.graph.encodePgce
import consema.graph.equal as graphEqual
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
import consema.yaml.CancellationToken as YamlCancellationToken
import consema.yaml.Document
import consema.yaml.EditFailureException
import consema.yaml.EditTransactionBuilder
import consema.yaml.Fidelity
import consema.yaml.GraphMaterializationResult
import consema.yaml.GraphProjectedLocation
import consema.yaml.GraphProjectionException
import consema.yaml.GraphProjectionLimits
import consema.yaml.GraphProjectionRequest
import consema.yaml.MappingPolicy
import consema.yaml.ProjectionEventKind
import consema.yaml.ProvenanceRelation
import consema.yaml.QueryLimits as YamlQueryLimits
import consema.yaml.RepresentationPolicy
import consema.yaml.SharingPolicy
import consema.yaml.TagPolicy
import consema.yaml.ValueProjectionRequest
import consema.yaml.ValueProjectionResult
import consema.yaml.YamlFormationException
import consema.yaml.YamlMatch
import consema.yaml.YamlProfile
import consema.yaml.YamlSyntaxKind
import consema.yaml.commit
import consema.yaml.executeYamlQuery
import consema.yaml.executeYamlSyntaxQuery
import consema.yaml.graphProjectionCode
import consema.yaml.materializeGraph
import consema.yaml.materializeValue
import consema.yaml.parse
import consema.yaml.projectGraph
import consema.yaml.projectGraphWithProvenance
import consema.yaml.projectValue
import consema.yaml.valueProjectionCode
import java.math.BigInteger

/** Runs the `consema.yaml.conformance@1` suite. */
fun runYamlV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runYamlV1Case(case)
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

private fun runYamlV1Case(case: CaseData) {
    val required = when {
        case.id.startsWith("profile.") -> "yaml.scalar-resolution@1"
        case.id.startsWith("source.") || case.id.startsWith("stream.") -> "yaml.document@1"
        case.id.startsWith("syntax.") || case.id.startsWith("regression.") ->
            "yaml.lossless-syntax@1"
        case.id.startsWith("native.") -> "yaml.native-semantics@1"
        case.id.startsWith("formation.") -> "yaml.formation@1"
        case.id.startsWith("graph.") -> "yaml.projection.best-exact-graph@1"
        case.id.startsWith("query.") -> "yaml.query@1"
        case.id == "projection.graph-provenance" -> "yaml.projection.best-exact-graph@1"
        case.id.startsWith("projection.") -> "yaml.projection.best-exact-value@1"
        case.id.startsWith("materialization.") -> "yaml.materialization@1"
        case.id.startsWith("edit.") -> "yaml.edit@1"
        case.id.startsWith("resource.parse-") -> "yaml.formation@1"
        case.id.startsWith("resource.graph-") -> "yaml.projection.best-exact-graph@1"
        else -> fail("runner does not recognize published YAML case")
    }
    if (case.capability != required) {
        fail("expected capability $required")
    }
    when (case.id) {
        "profile.yaml12-scalars", "profile.yaml11-scalars" -> scalarProfile(case)
        "source.utf16le-bom" -> sourceEncoding(case)
        "stream.empty", "stream.multi-document" -> streamFacts(case)
        "syntax.styles-and-trivia" -> syntaxFacts(case)
        "native.arbitrary-duplicate-mapping" -> mappingFacts(case)
        "formation.undefined-alias" -> formationRejection(case)
        "graph.shared-cycle" -> graphFacts(case)
        "query.mapping-entries", "query.alias-target" -> nativeQuery(case)
        "query.syntax-comments" -> syntaxQuery(case)
        "query.resource-limit" -> queryLimit(case)
        "projection.sharing-policy" -> projectionSharing(case)
        "projection.cycle" -> projectionFailure(case)
        "projection.tag-policy" -> projectionTag(case)
        "projection.mapping-policy" -> projectionMapping(case)
        "projection.graph-provenance" -> graphProvenance(case)
        "materialization.graph-cycle-flow" -> graphMaterialization(case)
        "materialization.value-flow" -> valueMaterialization(case)
        "edit.scalar-atomic" -> editScalar(case)
        "edit.anchor-rename" -> editAnchor(case)
        "edit.structural-insert" -> editStructural(case)
        "edit.anchor-dependency" -> editAnchorDependency(case)
        "resource.parse-source-bytes" -> parseLimit(case)
        "resource.graph-provenance" -> graphProvenanceLimit(case)
        "regression.plain-property-characters" -> plainPropertyRegression(case)
        else -> fail("runner does not recognize published YAML case")
    }
}

/** profile.yaml12-scalars and profile.yaml11-scalars (yaml_v1.rs:167-186). */
private fun scalarProfile(case: CaseData) {
    val document = parseYaml(case)
    val root = document.document(0) ?: fail("document 0 missing")
    val count = root.root().sequenceLen() ?: fail("root must be Sequence")
    val kinds = ArrayList<String>(count)
    val canonical = ArrayList<String>(count)
    for (ordinal in 0 until count) {
        val scalar = root.root().sequenceItem(ordinal)?.node()?.scalar()
            ?: fail("sequence item must be Scalar")
        kinds.add(scalar.kind().name)
        canonical.add(scalar.canonical())
    }
    ensure(
        kinds == expectedStrings(case, "kinds") &&
            canonical == expectedStrings(case, "canonical"),
    )
}

/** source.utf16le-bom (yaml_v1.rs:188-199). */
private fun sourceEncoding(case: CaseData) {
    val raw = decodeHex(inputString(case, "source_hex") ?: fail("missing input.source_hex"))
        ?: fail("invalid hex")
    val document = try {
        parse(raw, yamlProfile(case), ParseLimits.default)
    } catch (e: YamlFormationException) {
        fail("parse failed: ${e.code}")
    }
    ensure(
        document.render().contentEquals(raw) &&
            sourceEncodingName(document.source().encodingFacts.selected) ==
            expectedString(case, "encoding") &&
            document.documentCount().toLong() == expectedLong(case, "document_count"),
    )
}

/** stream.empty and stream.multi-document (yaml_v1.rs:201-210). */
private fun streamFacts(case: CaseData) {
    val document = parseYaml(case)
    val source = inputString(case, "source") ?: fail("missing input.source")
    ensure(
        document.formationStatus() == FormationStatus.Complete &&
            document.documentCount().toLong() == expectedLong(case, "document_count") &&
            document.aliasCount().toLong() == expectedLong(case, "alias_count") &&
            document.render().contentEquals(source.toByteArray(Charsets.UTF_8)),
    )
}

/** syntax.styles-and-trivia (yaml_v1.rs:212-235). */
private fun syntaxFacts(case: CaseData) {
    val document = parseYaml(case)
    val kinds = document.losslessSyntaxKinds().map { it.asStr() }
    val required = expectedStrings(case, "required_kinds")
    val pieces = document.losslessStructuralIndex().pieces()
    ensure(
        kinds.size.toLong() == expectedLong(case, "piece_count") &&
            required.all { it in kinds } &&
            pieces.sumOf { it.span.len } == document.render().size,
    )
}

/** native.arbitrary-duplicate-mapping (yaml_v1.rs:237-261). */
private fun mappingFacts(case: CaseData) {
    val document = parseYaml(case)
    val root = document.document(0) ?: fail("document 0 missing")
    val count = root.root().mappingLen() ?: fail("root must be Mapping")
    val keyKinds = ArrayList<String>(count)
    val values = ArrayList<String>(count)
    for (ordinal in 0 until count) {
        val entry = root.root().mappingEntry(ordinal) ?: fail("mapping entry missing")
        keyKinds.add(entry.key().kind().name)
        values.add(entry.value().scalar()?.canonical() ?: fail("value must be Scalar"))
    }
    ensure(
        count.toLong() == expectedLong(case, "entry_count") &&
            keyKinds == expectedStrings(case, "key_kinds") &&
            values == expectedStrings(case, "values"),
    )
}

/** formation.undefined-alias (yaml_v1.rs:263-275). */
private fun formationRejection(case: CaseData) {
    val source = inputString(case, "source") ?: fail("missing input.source")
    val error = try {
        parse(source.toByteArray(Charsets.UTF_8), yamlProfile(case), ParseLimits.default)
        fail("formation unexpectedly completed")
    } catch (e: YamlFormationException) {
        e
    }
    ensure(error.code == expectedString(case, "code"))
}

/** graph.shared-cycle (yaml_v1.rs:277-291). */
private fun graphFacts(case: CaseData) {
    val document = parseYaml(case)
    val graph = try {
        document.projectGraph()
    } catch (e: GraphProjectionException) {
        fail("graph projection failed: ${graphProjectionCode(e.failure)}")
    }
    val pgce = try {
        encodePgce(graph)
    } catch (e: PgceException) {
        fail("pgce encode failed")
    }
    ensure(
        graph.nodeCount().toLong() == expectedLong(case, "node_count") &&
            graph.roots().size.toLong() == expectedLong(case, "root_count") &&
            toHex(pgce) == expectedString(case, "pgce_hex"),
    )
}

/** query.mapping-entries and query.alias-target (yaml_v1.rs:293-314). */
private fun nativeQuery(case: CaseData) {
    val document = parseYaml(case)
    val executable = queryFromPipeline(case, Domains.yamlNativeV1())
    val result = try {
        executeYamlQuery(executable, document, YamlQueryLimits.default, YamlCancellationToken())
    } catch (e: QueryFailureException) {
        fail("query: ${e.kind.code}")
    }
    val roles = result.map { yamlMatchRole(it) }
    ensure(roles == expectedStrings(case, "roles"))
}

/** query.syntax-comments (yaml_v1.rs:316-344). */
private fun syntaxQuery(case: CaseData) {
    val document = parseYaml(case)
    val kind = inputString(case, "kind") ?: fail("missing input.kind")
    val expression = QueryExpression(ExpressionKind.Input).then(
        OperatorCall("yaml.syntax-kind-is", 1).withArgument("kind", PvString(kind)),
    )
    val executable = try {
        ExecutableQuery.bind(
            QueryDefinition(Domains.yamlLosslessSyntaxV1()).withExpression(expression).validate(),
            queryCapabilities(),
        )
    } catch (e: QueryFailureException) {
        fail("definition: ${e.kind.code}")
    }
    val result = try {
        executeYamlSyntaxQuery(executable, document, YamlQueryLimits.default, YamlCancellationToken())
    } catch (e: QueryFailureException) {
        fail("query: ${e.kind.code}")
    }
    val ordinals = result.map { it.ordinal.toLong() }
    ensure(ordinals == expectedLongs(case, "ordinals"))
}

/** query.resource-limit (yaml_v1.rs:346-363). */
private fun queryLimit(case: CaseData) {
    val document = parseYaml(case)
    val executable = queryFromPipeline(case, Domains.yamlNativeV1())
    val maxResults = inputInt(case, "max_results")
    val error = try {
        executeYamlQuery(
            executable,
            document,
            YamlQueryLimits(maxSteps = 100_000, maxResults = maxResults),
            YamlCancellationToken(),
        )
        fail("query unexpectedly completed")
    } catch (e: QueryFailureException) {
        e
    }
    ensure(error.kind.code == expectedString(case, "code"))
}

/** projection.sharing-policy (yaml_v1.rs:365-388). */
private fun projectionSharing(case: CaseData) {
    val document = parseYaml(case)
    val default = document.projectValue(ValueProjectionRequest.bestExactV1())
    val failure = when (default) {
        is ValueProjectionResult.Failed -> default.failure
        is ValueProjectionResult.Complete -> fail("default sharing policy unexpectedly completed")
    }
    val duplicated = document.projectValue(
        ValueProjectionRequest.bestExactV1().withSharing(SharingPolicy.DuplicateAcyclic),
    )
    val complete = when (duplicated) {
        is ValueProjectionResult.Complete -> duplicated.projection
        is ValueProjectionResult.Failed -> fail("explicit acyclic duplication failed")
    }
    ensure(
        valueProjectionCode(failure) == expectedString(case, "default_code") &&
            complete.fidelity == Fidelity.Transformed &&
            complete.report.events().size.toLong() == expectedLong(case, "event_count") &&
            complete.report.events().all { it.kind == ProjectionEventKind.SharingDuplicated },
    )
}

/** projection.cycle (yaml_v1.rs:390-402). */
private fun projectionFailure(case: CaseData) {
    val document = parseYaml(case)
    val result = document.projectValue(
        ValueProjectionRequest.bestExactV1().withSharing(SharingPolicy.DuplicateAcyclic),
    )
    val failure = when (result) {
        is ValueProjectionResult.Failed -> result.failure
        is ValueProjectionResult.Complete -> fail("projection unexpectedly completed")
    }
    ensure(valueProjectionCode(failure) == expectedString(case, "code"))
}

/** projection.tag-policy (yaml_v1.rs:404-423). */
private fun projectionTag(case: CaseData) {
    val document = parseYaml(case)
    val rejected = document.projectValue(ValueProjectionRequest.bestExactV1())
    val failure = when (rejected) {
        is ValueProjectionResult.Failed -> rejected.failure
        is ValueProjectionResult.Complete -> fail("unknown tag unexpectedly projected exactly")
    }
    val stripped = document.projectValue(
        ValueProjectionRequest.bestExactV1().withTags(TagPolicy.StripToNodeKind),
    )
    val complete = when (stripped) {
        is ValueProjectionResult.Complete -> stripped.projection
        is ValueProjectionResult.Failed -> fail("explicit tag stripping failed")
    }
    ensure(
        valueProjectionCode(failure) == expectedString(case, "default_code") &&
            complete.fidelity == Fidelity.Lossy &&
            (complete.value as? PvString)?.value == expectedString(case, "value") &&
            complete.report.events().size == 1,
    )
}

/** projection.mapping-policy (yaml_v1.rs:425-446). */
private fun projectionMapping(case: CaseData) {
    val document = parseYaml(case)
    val rejected = document.projectValue(
        ValueProjectionRequest.bestExactV1().withMapping(MappingPolicy.RequireObject),
    )
    val failure = when (rejected) {
        is ValueProjectionResult.Failed -> rejected.failure
        is ValueProjectionResult.Complete -> fail("duplicate mapping unexpectedly became Object")
    }
    val entries = document.projectValue(
        ValueProjectionRequest.bestExactV1().withMapping(MappingPolicy.RequireEntryMapping),
    )
    val complete = when (entries) {
        is ValueProjectionResult.Complete -> entries.projection
        is ValueProjectionResult.Failed -> fail("explicit EntryMapping projection failed")
    }
    val mapping = complete.value as? consema.core.PvEntryMapping ?: fail("expected EntryMapping")
    ensure(
        valueProjectionCode(failure) == expectedString(case, "object_code") &&
            mapping.entries().size.toLong() == expectedLong(case, "entry_count"),
    )
}

/** projection.graph-provenance (yaml_v1.rs:448-480). */
private fun graphProvenance(case: CaseData) {
    val document = parseYaml(case)
    val projection = try {
        document.projectGraphWithProvenance(GraphProjectionRequest.bestExactV1())
    } catch (e: GraphProjectionException) {
        fail("graph provenance failed: ${graphProjectionCode(e.failure)}")
    }
    val references = projection.provenance.entries()
        .sumOf { entry -> entry.origins.count { it.relation == ProvenanceRelation.Reference } }
        .toLong()
    val associations = projection.provenance.entries().count { entry ->
        entry.projected is GraphProjectedLocation.SequenceElement ||
            entry.projected is GraphProjectedLocation.MappingKey ||
            entry.projected is GraphProjectedLocation.MappingValue
    }.toLong()
    ensure(
        references == expectedLong(case, "reference_origins") &&
            associations == expectedLong(case, "association_entries"),
    )
}

/** materialization.graph-cycle-flow (yaml_v1.rs:482-498). */
private fun graphMaterialization(case: CaseData) {
    val document = parseYaml(case)
    val graph = try {
        document.projectGraph()
    } catch (e: GraphProjectionException) {
        fail("graph projection failed: ${graphProjectionCode(e.failure)}")
    }
    val result = materializeGraph(graph, materializationRequest("yaml.canonical-flow"))
    val complete = when (result) {
        is GraphMaterializationResult.Complete -> result.materialization
        is GraphMaterializationResult.Failed -> fail("graph materialization failed")
    }
    val reparsed = try {
        complete.document.projectGraph()
    } catch (e: GraphProjectionException) {
        fail("reprojection failed: ${graphProjectionCode(e.failure)}")
    }
    ensure(
        complete.document.render().contentEquals(
            (expectedString(case, "source") ?: fail("missing expected.source"))
                .toByteArray(Charsets.UTF_8),
        ) &&
            graphEqual(reparsed, graph) &&
            complete.fidelity == MaterializationFidelity.Exact,
    )
}

/** materialization.value-flow (yaml_v1.rs:500-529). */
private fun valueMaterialization(case: CaseData) {
    val document = parseYaml(case)
    val projected = document.projectValue(ValueProjectionRequest.bestExactV1())
    val value = when (projected) {
        is ValueProjectionResult.Complete -> projected.projection.value
        is ValueProjectionResult.Failed -> fail("input value projection failed")
    }
    val result = materializeValue(value, materializationRequest("yaml.canonical-flow"))
    val complete = when (result) {
        is MaterializationResult.Complete -> result.materialization
        is MaterializationResult.Failed -> fail("value materialization failed")
    }
    val reprojected = complete.document.projectValue(ValueProjectionRequest.bestExactV1())
    val reprojectedValue = when (reprojected) {
        is ValueProjectionResult.Complete -> reprojected.projection.value
        is ValueProjectionResult.Failed -> fail("materialized value did not reproject")
    }
    ensure(
        complete.document.render().contentEquals(
            (expectedString(case, "source") ?: fail("missing expected.source"))
                .toByteArray(Charsets.UTF_8),
        ) &&
            equal(reprojectedValue, value) &&
            complete.fidelity == MaterializationFidelity.Exact,
    )
}

/** edit.scalar-atomic (yaml_v1.rs:531-561). */
private fun editScalar(case: CaseData) {
    val document = parseYaml(case)
    val entry = inputInt(case, "entry")
    val target = document.document(0)?.root()?.mappingEntry(entry)?.value()
        ?: fail("scalar edit target missing")
    val integer = inputString(case, "integer") ?: fail("missing input.integer")
    val builder = EditTransactionBuilder.new(document)
    builder.semanticScalar(
        target.nodeRef(),
        PvInteger(BigInteger(integer)),
        RepresentationPolicy.PreserveCompatible,
    )
    val commit = try {
        document.commit(builder.build())
    } catch (e: EditFailureException) {
        fail("commit: ${e.failure.code}")
    }
    try {
        commit.untouchedProof.verify(
            document.source(),
            commit.document.source(),
            commit.sourcePatch.replacements(),
        )
    } catch (e: UntouchedByteProofException) {
        fail("untouched proof did not verify")
    }
    ensure(
        commit.document.render().contentEquals(
            (expectedString(case, "source") ?: fail("missing expected.source"))
                .toByteArray(Charsets.UTF_8),
        ) &&
            commit.sourcePatch.replacements().size.toLong() == expectedLong(case, "edit_count"),
    )
}

/** edit.anchor-rename (yaml_v1.rs:563-581). */
private fun editAnchor(case: CaseData) {
    val document = parseYaml(case)
    val entry = inputInt(case, "entry")
    val target = document.document(0)?.root()?.mappingEntry(entry)?.value()?.anchorNodeRef()
        ?: fail("anchor target missing")
    val name = inputString(case, "name") ?: fail("missing input.name")
    val builder = EditTransactionBuilder.new(document)
    builder.renameAnchor(target, name)
    val commit = try {
        document.commit(builder.build())
    } catch (e: EditFailureException) {
        fail("commit: ${e.failure.code}")
    }
    ensure(
        commit.document.render().contentEquals(
            (expectedString(case, "source") ?: fail("missing expected.source"))
                .toByteArray(Charsets.UTF_8),
        ) &&
            commit.document.alias(0)?.name() == name,
    )
}

/** edit.structural-insert (yaml_v1.rs:583-620). */
private fun editStructural(case: CaseData) {
    val document = parseYaml(case)
    val root = document.document(0)?.root() ?: fail("document missing")
    val sequence = root.mappingEntry(0)?.value() ?: fail("sequence missing")
    val mapping = root.mappingEntry(1)?.value() ?: fail("mapping missing")
    val builder = EditTransactionBuilder.new(document)
    builder
        .insertSequenceElement(
            sequence.nodeRef(),
            PvBoolean(true),
            AssociationPlacement.Before(
                sequence.sequenceItem(1)?.nodeRef() ?: fail("second sequence item missing"),
            ),
        )
        .insertMappingEntry(
            mapping.nodeRef(),
            PvString("b"),
            PvInteger(BigInteger.valueOf(2)),
            AssociationPlacement.End,
        )
    val commit = try {
        document.commit(builder.build())
    } catch (e: EditFailureException) {
        fail("commit: ${e.failure.code}")
    }
    ensure(
        commit.document.render().contentEquals(
            (expectedString(case, "source") ?: fail("missing expected.source"))
                .toByteArray(Charsets.UTF_8),
        ),
    )
}

/** edit.anchor-dependency (yaml_v1.rs:622-637). */
private fun editAnchorDependency(case: CaseData) {
    val document = parseYaml(case)
    val target = document.document(0)?.root()?.mappingEntry(0)?.value()?.sequenceItem(0)
        ?: fail("anchored sequence item missing")
    val builder = EditTransactionBuilder.new(document)
    builder.removeSequenceElement(target.nodeRef())
    val error = try {
        document.commit(builder.build())
        fail("commit unexpectedly completed")
    } catch (e: EditFailureException) {
        e
    }
    val source = inputString(case, "source") ?: fail("missing input.source")
    ensure(
        error.failure.code == expectedString(case, "code") &&
            document.render().contentEquals(source.toByteArray(Charsets.UTF_8)),
    )
}

/** resource.parse-source-bytes (yaml_v1.rs:639-654). */
private fun parseLimit(case: CaseData) {
    val source = inputString(case, "source") ?: fail("missing input.source")
    val limits = ParseLimits(
        maxSourceBytes = inputInt(case, "max_source_bytes"),
        maxNestingDepth = ParseLimits.default.maxNestingDepth,
        maxTokenCount = ParseLimits.default.maxTokenCount,
        maxNodeCount = ParseLimits.default.maxNodeCount,
        maxDiagnostics = ParseLimits.default.maxDiagnostics,
    )
    val error = try {
        parse(source.toByteArray(Charsets.UTF_8), yamlProfile(case), limits)
        fail("parse unexpectedly completed")
    } catch (e: YamlFormationException) {
        e
    }
    ensure(error.code == expectedString(case, "code"))
}

/** resource.graph-provenance (yaml_v1.rs:656-670). */
private fun graphProvenanceLimit(case: CaseData) {
    val document = parseYaml(case)
    val request = GraphProjectionRequest.bestExactV1().withLimits(
        GraphProjectionLimits(
            graph = GraphLimits.default,
            maxProvenanceEntries = inputInt(case, "max_provenance_entries"),
        ),
    )
    val error = try {
        document.projectGraphWithProvenance(request)
        fail("graph provenance unexpectedly completed")
    } catch (e: GraphProjectionException) {
        e
    }
    ensure(graphProjectionCode(error.failure) == expectedString(case, "code"))
}

/** regression.plain-property-characters (yaml_v1.rs:672-687). */
private fun plainPropertyRegression(case: CaseData) {
    val document = parseYaml(case)
    val scalar = document.document(0)?.root()?.scalar() ?: fail("root must be Scalar")
    ensure(
        scalar.canonical() == expectedString(case, "canonical") &&
            document.aliasCount() == 0 &&
            YamlSyntaxKind.Anchor !in document.losslessSyntaxKinds() &&
            YamlSyntaxKind.Tag !in document.losslessSyntaxKinds(),
    )
}

private fun parseYaml(case: CaseData): Document {
    val source = inputString(case, "source") ?: fail("missing input.source")
    return try {
        parse(source.toByteArray(Charsets.UTF_8), yamlProfile(case), ParseLimits.default)
    } catch (e: YamlFormationException) {
        fail("YAML formation failed: ${e.code}")
    }
}

private fun yamlProfile(case: CaseData): YamlProfile =
    when (inputString(case, "profile")) {
        "yaml.1.2-core@1" -> YamlProfile.Yaml12CoreV1
        "yaml.1.1-compat@1" -> YamlProfile.Yaml11CompatV1
        else -> fail("unknown YAML profile")
    }

/** Builds one executable query from the input.pipeline descriptor sequence
 * (yaml_v1.rs:706-728). */
private fun queryFromPipeline(case: CaseData, domain: QueryDomain): ExecutableQuery {
    val descriptors = inputSequence(case, "pipeline") ?: fail("missing input.pipeline")
    var expression = QueryExpression(ExpressionKind.Input)
    for (descriptor in descriptors) {
        val text = (descriptor as? PvString)?.value ?: fail("operator must be String")
        val (id, version) = text.splitOnce('@') ?: fail("operator lacks version: $text")
        expression = expression.then(OperatorCall(id, version.toInt()))
    }
    val definition = QueryDefinition(domain).withExpression(expression)
    return try {
        ExecutableQuery.bind(definition.validate(), queryCapabilities())
    } catch (e: QueryFailureException) {
        fail("definition: ${e.kind.code}")
    }
}

private fun queryCapabilities(): CapabilitySet {
    val set = CapabilitySet()
    set.insert(CapabilityId("core.query.ordered-results", 1))
    return set
}

/** The frozen canonical-flow materialization request (yaml_v1.rs:736-742). */
private fun materializationRequest(style: String): MaterializationRequest =
    MaterializationRequest.new(
        ProfileId("yaml.1.2-core", 1),
        MaterializationStyleId(style, 1),
    ).withNewline(NewlinePolicy.Lf)

private fun yamlMatchRole(item: YamlMatch): String =
    when (item) {
        is YamlMatch.Stream -> "YamlStream"
        is YamlMatch.Document -> "YamlDocument"
        is YamlMatch.Node -> "YamlNode"
        is YamlMatch.MappingEntry -> "YamlMappingEntry"
        is YamlMatch.SequenceElement -> "YamlSequenceElement"
        is YamlMatch.AnchorDefinition -> "YamlAnchorDefinition"
        is YamlMatch.AliasOccurrence -> "YamlAliasOccurrence"
    }

private fun sourceEncodingName(encoding: SourceEncoding): String =
    when (encoding) {
        SourceEncoding.Binary -> "Binary"
        SourceEncoding.Utf8 -> "Utf8"
        SourceEncoding.Utf16Le -> "Utf16Le"
        SourceEncoding.Utf16Be -> "Utf16Be"
        SourceEncoding.Latin1 -> "Latin1"
    }

private fun expectedStrings(case: CaseData, name: String): List<String> {
    val values = expectedSequence(case, name) ?: fail("missing expected.$name")
    return values.map { (it as? PvString)?.value ?: fail("expected.$name item must be String") }
}

private fun expectedLongs(case: CaseData, name: String): List<Long> {
    val values = expectedSequence(case, name) ?: fail("missing expected.$name")
    return values.map {
        (it as? PvInteger)?.value?.toLong() ?: fail("expected.$name item must be Integer")
    }
}

private fun inputInt(case: CaseData, name: String): Int =
    (caseInput(case, name) as? PvInteger)?.value?.toInt() ?: fail("missing input.$name")

private fun String.splitOnce(separator: Char): Pair<String, String>? {
    val index = indexOf(separator)
    if (index < 0) {
        return null
    }
    return substring(0, index) to substring(index + 1)
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) {
        fail("expected behavior did not match")
    }
}
