// The `consema.syntax-query.conformance@1` suite runner
// (conformance/vectors/syntax-query-v1.json).
//
// Data authority: crates/consema-conformance/src/syntax_query_v1.rs (the
// per-case dispatch by prefix and the per-case handlers are transcribed from
// the Rust runner: the definition builder at syntax_query_v1.rs:186-269, the
// match comparison at syntax_query_v1.rs:271-312, the failure-code comparison
// at syntax_query_v1.rs:314-316, and the ordered-cursor terminal semantics at
// syntax_query_v1.rs:318-366); the vector file itself drives every input and
// expectation (conformance/README.md rules 3-4). go/conformance/
// syntax_query_v1.go is a cross-reference only.

package consema.conformance

import consema.core.PortableValue
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.document.ParseLimits
import consema.json.CancellationToken
import consema.json.JsonProfile
import consema.json.QueryLimits
import consema.json.executeJsonSyntaxQuery
import consema.json.parse
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.Domains
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryExpression
import consema.protocol.QueryFailureException
import consema.protocol.QuerySelection
import consema.toml.TomlCancellationToken
import consema.toml.TomlProfile
import consema.toml.TomlQueryLimits
import consema.toml.executeTomlSyntaxQuery

/** Runs the `consema.syntax-query.conformance@1` suite. */
fun runSyntaxQueryV1(runner: Runner, data: SuiteData): SuiteReport {
    val passed = mutableListOf<String>()
    val skipped = mutableListOf<SkipRecord>()
    val failed = mutableListOf<CaseFailure>()
    for (case in data.cases) {
        try {
            runSyntaxQueryV1Case(case)
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

private fun runSyntaxQueryV1Case(case: CaseData) {
    when {
        case.id.startsWith("syntax.json.") -> runJsonSyntaxCase(case)
        case.id.startsWith("syntax.toml.") -> runTomlSyntaxCase(case)
        case.id.startsWith("syntax.cursor.") -> runCursorCase(case)
        else -> fail("runner does not recognize published syntax-query case")
    }
}

/** syntax.json.*: one lossless JSON syntax query (syntax_query_v1.rs:
 * 106-145). */
private fun runJsonSyntaxCase(case: CaseData) {
    val profile = when (inputString(case, "profile") ?: fail("missing input.profile")) {
        "json.strict@1" -> JsonProfile.StrictV1
        "jsonc.bounded@1" -> JsonProfile.JsoncBoundedV1
        else -> fail("unknown JSON profile")
    }
    val source = inputString(case, "source") ?: fail("missing input.source")
    val document = try {
        parse(source.toByteArray(Charsets.UTF_8), profile, ParseLimits.default)
    } catch (e: Exception) {
        fail("parse failed: ${e.message}")
    }
    val executable = try {
        syntaxDefinition(case, "json")
    } catch (e: QueryFailureException) {
        expectFailure(case, e)
        return
    }
    val cancellation = CancellationToken()
    if (booleanField(case.input, "cancelled") == true) {
        cancellation.cancel()
    }
    val matches = try {
        executeJsonSyntaxQuery(executable, document, jsonLimits(case), cancellation)
    } catch (e: QueryFailureException) {
        expectFailure(case, e)
        return
    }
    val actual = matches.map { match ->
        ActualMatch(
            kind = match.kind.asStr(),
            text = String(
                document.source().bytes()
                    .copyOfRange(match.span.startByte, match.span.endByte),
                Charsets.UTF_8,
            ),
            ordinal = match.ordinal.toLong(),
            role = match.node.role.name,
        )
    }
    compareMatches(case, actual, "Completed")
}

/** syntax.toml.*: one lossless TOML syntax query (syntax_query_v1.rs:
 * 147-184). */
private fun runTomlSyntaxCase(case: CaseData) {
    val profile = inputString(case, "profile") ?: fail("missing input.profile")
    if (profile != "toml.1.0@1") {
        fail("unknown TOML profile")
    }
    val source = inputString(case, "source") ?: fail("missing input.source")
    val document = try {
        consema.toml.parse(
            source.toByteArray(Charsets.UTF_8),
            TomlProfile.TOML_1_0_V1,
            ParseLimits.default,
        )
    } catch (e: Exception) {
        fail("parse failed: ${e.message}")
    }
    val executable = try {
        syntaxDefinition(case, "toml")
    } catch (e: QueryFailureException) {
        expectFailure(case, e)
        return
    }
    val cancellation = TomlCancellationToken()
    if (booleanField(case.input, "cancelled") == true) {
        cancellation.cancel()
    }
    val execution = try {
        executeTomlSyntaxQuery(executable, document, tomlLimits(case), cancellation)
    } catch (e: QueryFailureException) {
        expectFailure(case, e)
        return
    }
    val actual = execution.matches().map { match ->
        ActualMatch(
            kind = match.kind.name,
            text = String(
                document.source().bytes()
                    .copyOfRange(match.span.startByte, match.span.endByte),
                Charsets.UTF_8,
            ),
            ordinal = match.ordinal.toLong(),
            role = match.nodeRef.role.name,
        )
    }
    compareMatches(case, actual, execution.terminal.name)
}

/** One actual match fact tuple (kind, raw source text, ordinal, role). */
private data class ActualMatch(
    val kind: String,
    val text: String,
    val ordinal: Long,
    val role: String,
)

/** Builds and validates the query definition from the vector facts
 * (syntax_query_v1.rs:186-269). Throws [QueryFailureException] on an
 * invalid definition and [CaseFailureException] on a malformed vector. */
private fun syntaxDefinition(case: CaseData, format: String): ExecutableQuery {
    val domain = when (format) {
        "json" -> Domains.jsonLosslessSyntaxV1()
        "toml" -> Domains.tomlLosslessSyntaxV1()
        else -> error("unknown syntax format")
    }
    val filterValues = inputSequence(case, "filters") ?: fail("missing input.filters")
    val branches = ArrayList<QueryExpression>(filterValues.size)
    for (filter in filterValues) {
        val fields = filter as? PvObject ?: fail("filter must be an Object")
        val operator = (fields.get("operator") as? PvString)?.value
            ?: fail("filter.operator must be String")
        val argument = fields.get("argument")
        val call = when (operator) {
            "kind-is" -> OperatorCall("$format.syntax-kind-is", 1)
                .withArgument("kind", argument ?: fail("missing filter.argument"))
            "text-equals" -> OperatorCall("$format.syntax-text-equals", 1)
                .withArgument("text", argument ?: fail("missing filter.argument"))
            "take" -> OperatorCall("core.take", 1)
                .withArgument("count", argument ?: fail("missing filter.argument"))
            "distinct-by-identity" -> OperatorCall("core.distinct-by-identity", 1)
            else -> OperatorCall(operator, 1)
        }
        branches.add(QueryExpression(ExpressionKind.Input).then(call))
    }
    val expression = when (inputString(case, "combine") ?: "Single") {
        "Single" -> if (branches.isEmpty()) {
            QueryExpression(ExpressionKind.Input)
        } else if (branches.size == 1) {
            branches[0]
        } else {
            fail("Single combine requires at most one filter")
        }
        "StructureOrderMerge" ->
            QueryExpression(ExpressionKind.StructureOrderMerge, branches = branches)
        "Concat" -> QueryExpression(ExpressionKind.Concat, branches = branches)
        else -> fail("unknown combine")
    }
    return QueryDefinition(domain)
        .withExpression(expression)
        .withSelection(selection(case))
        .validate()
        .let { ExecutableQuery.bind(it, syntaxCapabilities()) }
}

/** Compares the actual match facts and the terminal against the expected
 * facts (syntax_query_v1.rs:271-312). */
private fun compareMatches(case: CaseData, actual: List<ActualMatch>, terminalName: String) {
    val expectedMatches = expectedSequence(case, "matches") ?: fail("missing expected.matches")
    ensure(actual.size == expectedMatches.size)
    for ((item, expectedValue) in actual.zip(expectedMatches)) {
        val fields = expectedValue as? PvObject ?: fail("expected match must be an Object")
        val expectedKind = (fields.get("kind") as? PvString)?.value
            ?: fail("expected match.kind")
        val expectedText = (fields.get("text") as? PvString)?.value
            ?: fail("expected match.text")
        val expectedOrdinal = (fields.get("ordinal") as? PvInteger)?.value?.toLong()
            ?: fail("expected match.ordinal")
        val expectedRole = (fields.get("role") as? PvString)?.value
            ?: fail("expected match.role")
        ensure(
            item.kind == expectedKind &&
                item.text == expectedText &&
                item.ordinal == expectedOrdinal &&
                item.role == expectedRole,
        )
    }
    val expectedTerminal = expectedString(case, "terminal") ?: fail("missing expected.terminal")
    ensure(terminalName == expectedTerminal)
}

/** Compares one query failure code against the expected code
 * (syntax_query_v1.rs:314-316). */
private fun expectFailure(case: CaseData, error: QueryFailureException) {
    ensure(error.kind.code == expectedString(case, "code") ?: fail("missing expected.code"))
}

/** syntax.cursor.*: the ordered-cursor terminal semantics (Completed,
 * Cancelled, Failed) over the vector values (syntax_query_v1.rs:318-366;
 * PortableQuery.kt). */
private fun runCursorCase(case: CaseData) {
    val valueItems = inputSequence(case, "values") ?: fail("missing input.values")
    val values = valueItems.map { item ->
        item as? PvInteger ?: fail("cursor value must be a host-size Integer")
    }
    val mode = inputString(case, "mode") ?: fail("missing input.mode")
    val yielded = mutableListOf<PortableValue>()
    val terminal: PortableTerminalState = when (mode) {
        "Completed" -> {
            val cursor = OrderedQueryCursor.new(values)
            while (true) {
                val value = cursor.next() ?: break
                yielded.add(value)
            }
            cursor.terminalState() ?: PortableTerminalState.Completed
        }
        "Cancelled" -> {
            val token = CancellationFlag()
            val cursor = OrderedQueryCursor.withCancellation(values, token)
            val first = cursor.next()
            if (first != null) {
                yielded.add(first)
            }
            token.cancel()
            if (cursor.next() != null) {
                fail("cancelled cursor must stop yielding")
            }
            PortableTerminalState.Cancelled
        }
        "Failed" -> {
            val cursor = OrderedQueryCursor.withTerminal(values, PortableTerminalState.Failed)
            while (true) {
                val value = cursor.next() ?: break
                yielded.add(value)
            }
            cursor.terminalState() ?: PortableTerminalState.Failed
        }
        else -> fail("unknown cursor mode $mode")
    }
    val expectedYielded = expectedLong(case, "yielded") ?: fail("missing expected.yielded")
    val expectedTerminal = expectedString(case, "terminal") ?: fail("missing expected.terminal")
    ensure(yielded.size.toLong() == expectedYielded && terminal.name == expectedTerminal)
}

private fun selection(case: CaseData): QuerySelection =
    when (inputString(case, "selection") ?: "All") {
        "All" -> QuerySelection.All
        "First" -> QuerySelection.First
        "Last" -> QuerySelection.Last
        "ZeroOrOne" -> QuerySelection.ZeroOrOne
        "RequireOne" -> QuerySelection.RequireOne
        else -> fail("unknown selection")
    }

private fun jsonLimits(case: CaseData): QueryLimits {
    val maxSteps = longField(case.input, "max_steps")?.toInt() ?: QueryLimits.default.maxSteps
    val maxResults = longField(case.input, "max_results")?.toInt() ?: QueryLimits.default.maxResults
    return QueryLimits(maxSteps = maxSteps, maxResults = maxResults)
}

private fun tomlLimits(case: CaseData): TomlQueryLimits {
    val maxSteps = longField(case.input, "max_steps")?.toInt() ?: TomlQueryLimits.default.maxSteps
    val maxResults =
        longField(case.input, "max_results")?.toInt() ?: TomlQueryLimits.default.maxResults
    return TomlQueryLimits(maxSteps = maxSteps, maxResults = maxResults)
}

private fun syntaxCapabilities(): CapabilitySet {
    val set = CapabilitySet()
    set.insert(CapabilityId("core.query.ordered-results", 1))
    return set
}

private fun fail(message: String): Nothing = throw CaseFailureException(message)

private fun ensure(condition: Boolean) {
    if (!condition) fail("expected behavior did not match")
}
