// The `consema.toml.conformance@1` suite runner
// (conformance/vectors/toml-v1.json).
//
// Data authority: consema-rs/consema-conformance/src/toml_v1.rs (the per-case
// dispatch is transcribed from the Rust handlers; the fixture files under
// conformance/fixtures/toml/ drive the corpus cases); the vector file itself
// drives every input and expectation. consema-go/go/conformance/toml_v1.go is a
// cross-reference only.

package consema.conformance

import consema.core.PortableValue
import consema.core.PvString
import consema.document.FormationStatus
import consema.document.ParseLimits
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.ExecutableQuery
import consema.protocol.OperatorCall
import consema.protocol.QueryDomain
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.toml.EditFailureKind
import consema.toml.EditTransactionBuilder
import consema.toml.TomlDocument
import consema.toml.TomlEditException
import consema.toml.TomlItemKind
import consema.toml.TomlMatch
import consema.toml.TomlProfile
import consema.toml.TomlQueryLimits
import consema.toml.TomlQueryTerminal
import consema.toml.TomlCancellationToken
import consema.toml.commit
import consema.toml.executeTomlQuery
import consema.toml.parse
import consema.toml.project
import java.io.File

/** Runs the `consema.toml.conformance@1` suite. */
fun runTomlV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runTomlV1Case(runner, case)
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

private fun runTomlV1Case(runner: Runner, case: CaseData) {
    when (case.id) {
        "toml.parse.exact-roundtrip" -> {
            val fixture = fixture(runner, "all-values.toml")
            val document = parseFixture(fixture)
            ensure(
                document.render().contentEquals(fixture) &&
                    document.formationStatus() == FormationStatus.Complete &&
                    document.formatFamily().id == "toml" &&
                    document.profile().id == "toml.1.0" &&
                    document.diagnostics().isEmpty(),
            )
        }
        "toml.parse.lossless-byte-coverage" -> {
            val fixture = fixture(runner, "trivia-and-strings.toml")
            val document = parseFixture(fixture)
            val pieces = document.losslessStructuralIndex().pieces()
            ensure(
                pieces.firstOrNull()?.span?.startByte == 0 &&
                    pieces.zipWithNext().all { (left, right) ->
                        left.span.endByte == right.span.startByte
                    } &&
                    (pieces.lastOrNull()?.span?.endByte ?: 0) == fixture.size,
            )
        }
        "toml.native.dotted-segments" -> {
            val document = parseFixture("alpha.beta.gamma = 1\n".toByteArray())
            val alpha = directItem(document.root(), "alpha")
            val beta = directItem(alpha, "beta")
            val gamma = directItem(beta, "gamma")
            ensure(
                alpha.kind == TomlItemKind.DottedTable &&
                    beta.kind == TomlItemKind.DottedTable &&
                    gamma.kind == TomlItemKind.Integer &&
                    gamma.asInteger() == 1L,
            )
        }
        "toml.native.table-flavors" -> {
            val document = parseFixture(fixture(runner, "application.toml"))
            ensure(
                directItem(document.root(), "service").kind == TomlItemKind.DottedTable &&
                    directItem(document.root(), "database").kind == TomlItemKind.StandardTable &&
                    directItem(document.root(), "observability").kind == TomlItemKind.ImplicitTable,
            )
        }
        "toml.native.array-aot-distinct" -> {
            val document = parseFixture(fixture(runner, "application.toml"))
            val database = directItem(document.root(), "database")
            val timeouts = directItem(database, "timeouts")
            val upstreams = directItem(document.root(), "upstreams")
            ensure(
                timeouts.kind == TomlItemKind.Array &&
                    upstreams.kind == TomlItemKind.ArrayOfTables &&
                    upstreams.arrayElements()?.size == 2,
            )
        }
        "toml.native.float-signed-zero" -> {
            val document = parseFixture("positive = 0.0\nnegative = -0.0\n".toByteArray())
            val positive = directItem(document.root(), "positive").asFloat() ?: fail("missing float")
            val negative = directItem(document.root(), "negative").asFloat() ?: fail("missing float")
            ensure(positive == 0L && negative == (1L shl 63))
        }
        "toml.query.nested-entry-order" -> {
            val document = parseFixture(fixture(runner, "application.toml"))
            val expression = namedRootItemExpression("service")
                .then(OperatorCall("toml.try-table-entries", 1))
            val result = executeQuery(expression, document)
            val names = result.matches().mapNotNull { item ->
                (item as? TomlMatch.Entry)?.name
            }
            ensure(names == listOf("name", "environment", "listen"))
        }
        "toml.query.aot-element-order" -> {
            val document = parseFixture(fixture(runner, "application.toml"))
            val expression = namedRootItemExpression("upstreams")
                .then(OperatorCall("toml.try-array-elements", 1))
            val result = executeQuery(expression, document)
            val ordinals = result.matches().mapNotNull { item ->
                (item as? TomlMatch.ArrayElement)?.ordinal
            }
            ensure(ordinals == listOf(0, 1))
        }
        "toml.projection.all-core-kinds" -> {
            val document = parseFixture(fixture(runner, "all-values.toml"))
            val projection = document.project(bestExactRequest())
            val result = when (projection) {
                is consema.toml.ProjectionResult.Complete -> projection.projection
                is consema.toml.ProjectionResult.Failed -> fail("exact projection failed")
            }
            val root = result.value as? consema.core.PvObject ?: fail("root is not Object")
            val kinds = root.entries().map { it.value.kind }
            ensure(
                result.fidelity == consema.toml.Fidelity.Exact &&
                    kinds.contains(consema.core.Kind.String) &&
                    kinds.contains(consema.core.Kind.Boolean) &&
                    kinds.contains(consema.core.Kind.Integer) &&
                    kinds.contains(consema.core.Kind.BinaryFloat64) &&
                    kinds.contains(consema.core.Kind.Date) &&
                    kinds.contains(consema.core.Kind.Time) &&
                    kinds.contains(consema.core.Kind.LocalDateTime) &&
                    kinds.contains(consema.core.Kind.OffsetDateTime) &&
                    kinds.contains(consema.core.Kind.Sequence) &&
                    kinds.contains(consema.core.Kind.Object),
            )
        }
        "toml.projection.provenance" -> {
            val document = parseFixture("point = { x = 1, y = 2 }\n".toByteArray())
            val projection = document.project(bestExactRequest())
            val result = when (projection) {
                is consema.toml.ProjectionResult.Complete -> projection.projection
                is consema.toml.ProjectionResult.Failed -> fail("projection failed")
            }
            val snapshot = document.snapshotIdentity
            ensure(
                result.provenance.entries().all { entry ->
                    entry.origins.all { origin ->
                        origin.snapshot == snapshot &&
                            origin.node.snapshot == snapshot &&
                            origin.span.snapshot == snapshot
                    }
                } && result.provenance.entries().any { entry ->
                    (entry.projected as? consema.toml.ProjectedLocation.Association)
                        ?.location?.role == consema.core.AssociationRole.ObjectEntry
                },
            )
        }
        "toml.projection.reject-leap-second" -> {
            val document = parseFixture("time = 23:59:60\n".toByteArray())
            val projection = document.project(bestExactRequest())
            val failure = when (projection) {
                is consema.toml.ProjectionResult.Complete -> fail("leap second projection succeeded")
                is consema.toml.ProjectionResult.Failed -> projection.attempt
            }
            ensure(
                failure.diagnostics.size == 1 &&
                    failure.diagnostics[0].code == "toml.projection.unrepresentable-datetime@1",
            )
        }
        "toml.edit.literal-minimal" -> {
            val document = parseFixture("hex = 0x2A # keep\n".toByteArray())
            val target = directItem(document.root(), "hex").nodeRef
            val builder = EditTransactionBuilder.new(document)
            builder.literalScalar(target, "0x2B".toByteArray())
            val commit = document.commit(builder.build())
            ensure(
                commit.document.render().contentEquals("hex = 0x2B # keep\n".toByteArray()) &&
                    commit.sourcePatch.replacements().size == 1,
            )
        }
        "toml.edit.reject-unrepresentable" -> {
            val document = parseFixture("float = 1.0\n".toByteArray())
            val target = directItem(document.root(), "float").nodeRef
            val builder = EditTransactionBuilder.new(document)
            builder.semanticScalar(
                target,
                consema.core.PvBinaryFloat64(0x7ff8000000000001L),
                consema.toml.RepresentationPolicy.CanonicalForProfile,
            )
            val failure = try {
                document.commit(builder.build())
                null
            } catch (e: TomlEditException) {
                e.kind
            }
            ensure(
                failure is EditFailureKind.UnsupportedSemanticValue &&
                    (failure as EditFailureKind.UnsupportedSemanticValue).kind ==
                    consema.core.Kind.BinaryFloat64 &&
                    document.render().contentEquals("float = 1.0\n".toByteArray()),
            )
        }
        "toml.parse.reject-invalid" -> {
            val failure = try {
                parse(
                    fixture(runner, "invalid-duplicate.toml"),
                    TomlProfile.TOML_1_0_V1,
                    ParseLimits.default,
                )
                fail("duplicate key must fail")
            } catch (e: consema.toml.TomlFormationException) {
                e
            }
            ensure(failure.diagnostics.size == 1 && failure.diagnostics[0].code == "toml.parse.syntax@1")
        }
        "toml.resource.token-limit" -> {
            val limits = ParseLimits(
                maxTokenCount = 3,
                maxSourceBytes = ParseLimits.default.maxSourceBytes,
                maxNestingDepth = ParseLimits.default.maxNestingDepth,
                maxNodeCount = ParseLimits.default.maxNodeCount,
                maxDiagnostics = ParseLimits.default.maxDiagnostics,
            )
            val failure = try {
                parse("values = [1, 2, 3]".toByteArray(), TomlProfile.TOML_1_0_V1, limits)
                null
            } catch (e: Exception) {
                e
            }
            ensure(failure != null)
        }
        "toml.resource.node-depth-limits" -> {
            val nodeLimits = ParseLimits(
                maxNodeCount = 3,
                maxSourceBytes = ParseLimits.default.maxSourceBytes,
                maxNestingDepth = ParseLimits.default.maxNestingDepth,
                maxTokenCount = ParseLimits.default.maxTokenCount,
                maxDiagnostics = ParseLimits.default.maxDiagnostics,
            )
            val depthLimits = ParseLimits(
                maxNestingDepth = 2,
                maxSourceBytes = ParseLimits.default.maxSourceBytes,
                maxNodeCount = ParseLimits.default.maxNodeCount,
                maxTokenCount = ParseLimits.default.maxTokenCount,
                maxDiagnostics = ParseLimits.default.maxDiagnostics,
            )
            val source = "value = [[[[1]]]]".toByteArray()
            val nodeFailed = try {
                parse(source, TomlProfile.TOML_1_0_V1, nodeLimits)
                false
            } catch (e: Exception) {
                true
            }
            val depthFailed = try {
                parse(source, TomlProfile.TOML_1_0_V1, depthLimits)
                false
            } catch (e: Exception) {
                true
            }
            ensure(nodeFailed && depthFailed)
        }
        "toml.corpus.cargo-manifest" -> corpusDocument(runner, File(runner.fixturesDir, "toml/Cargo.toml"))
        "toml.corpus.pyproject" -> corpusDocument(runner, File(runner.fixturesDir, "toml/pyproject.toml"))
        else -> fail("runner does not recognize published TOML case")
    }
}

private fun fixture(runner: Runner, name: String): ByteArray =
    File(runner.fixturesDir, "toml/$name").readBytes()

private fun parseFixture(source: ByteArray): TomlDocument =
    try {
        parse(source, TomlProfile.TOML_1_0_V1, ParseLimits.default)
    } catch (e: Exception) {
        fail("TOML formation failed: ${e.message}")
    }

private fun directItem(container: consema.toml.TomlItem, name: String): consema.toml.TomlItem =
    container.tableEntries()
        ?.firstOrNull { it.name() == name }
        ?.item()
        ?: fail("missing direct entry $name")

private fun bestExactRequest(): consema.toml.ProjectionRequest =
    consema.toml.ProjectionRequest.new(consema.toml.ProjectionTarget.BEST_EXACT_CORE_V1)

private fun executeQuery(
    expression: QueryExpression,
    document: TomlDocument,
): consema.toml.TomlQueryExecution<TomlMatch> {
    val definition = consema.protocol.QueryDefinition(QueryDomain("toml.native-semantic-query", 1))
        .withExpression(expression)
    val executable = try {
        definition.validate().let { ExecutableQuery.bind(it, tomlCapabilities()) }
    } catch (e: QueryFailureException) {
        fail("validation: ${e.kind.code}")
    }
    return try {
        executeTomlQuery(executable, document, TomlQueryLimits.default, TomlCancellationToken())
    } catch (e: QueryFailureException) {
        fail("query: ${e.kind.code}")
    }
}

private fun namedRootItemExpression(name: String): QueryExpression =
    QueryExpression(consema.protocol.ExpressionKind.Input)
        .then(OperatorCall("toml.try-table-entries", 1))
        .then(
            OperatorCall("toml.entry-name-equals", 1)
                .withArgument("name", PvString(name)),
        )
        .then(OperatorCall("toml.entry-item", 1))

private fun tomlCapabilities(): CapabilitySet {
    val set = CapabilitySet()
    set.insert(CapabilityId("core.query.ordered-results", 1))
    return set
}

private fun corpusDocument(runner: Runner, file: File) {
    val source = file.readBytes()
    val document = parseFixture(source)
    ensure(
        document.render().contentEquals(source) &&
            document.formationStatus() == FormationStatus.Complete &&
            document.project(bestExactRequest()) is consema.toml.ProjectionResult.Complete,
    )
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}
