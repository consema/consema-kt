// Consema SDK chain example (Kotlin): one JSON document through the full SDK
// surface — parse, native semantic query, best-exact projection, structural
// edit, canonical materialization, and cross-format conversion to TOML.
//
// Scenario: read `{"a":1,"b":{"c":2}}` under `json.strict`, query `b.c`
// (`json.native-semantic-query@1`), project
// `json.projection.best-exact-core@1`, edit `a` to `42` (semantic scalar
// replacement, `CanonicalForProfile` representation), materialize the edited
// value as canonical compact JSON, and convert the edited document to TOML
// (`toml.canonical-document`).
//
// Run (kotlinc must compile the main sources and the example together):
//   kotlinc -jvm-target 17 -d out src/main/kotlin examples/SdkChain.kt
//   java -cp "out;<kotlinc>\lib\kotlin-stdlib.jar" consema.examples.SdkChainKt
//
// Language-neutral contract reference (consema spec repository):
//   - https://github.com/consema/consema/blob/main/docs/cookbook.md — the CLI recipes for the same operations
//   - https://github.com/consema/consema/blob/main/docs/multi-language-implementation-plan.md — the five-language SDK design
//   https://github.com/consema/consema/blob/main/docs/cookbook.md
package consema.examples

import consema.ConversionResult
import consema.convertJson
import consema.core.PvInteger
import consema.parseDocument
import consema.core.PvString
import consema.document.MappingPolicy
import consema.document.MaterializationRequest
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.json.EditTransactionBuilder
import consema.json.Fidelity
import consema.json.JsonObjectMember
import consema.json.JsonValue
import consema.json.ProjectionRequest
import consema.json.ProjectionResult
import consema.json.ProjectionTarget
import consema.json.RepresentationPolicy
import consema.json.SemanticAvailability
import consema.json.commit
import consema.json.executeJsonQuery
import consema.json.materialize
import consema.json.project
import consema.protocol.CapabilityId
import consema.protocol.CapabilitySet
import consema.protocol.Domains
import consema.protocol.ExecutableQuery
import consema.protocol.ExpressionKind
import consema.protocol.OperatorCall
import consema.protocol.QueryDefinition
import consema.protocol.QueryExpression
import consema.protocol.QuerySelection
import java.math.BigInteger

/** Returns the value of one object member by decoded name, walking
 * `objectMembers()` with an explicit `SemanticAvailability` pattern match. */
fun memberValueRef(value: JsonValue, name: String): JsonValue =
    when (val availability = value.objectMembers()) {
        is SemanticAvailability.Available -> {
            val members = availability.value
                ?: error("value is not an object")
            members.firstOrNull { member ->
                when (val memberName = member.name()) {
                    is SemanticAvailability.Available -> memberName.value == name
                    is SemanticAvailability.Unavailable -> false
                }
            }?.let(JsonObjectMember::value)
                ?: error("member '$name' not found")
        }
        is SemanticAvailability.Unavailable -> error("semantics unavailable: ${availability.reason}")
    }

/** Projects one JSON document and renders its value as canonical compact
 * JSON bytes. */
fun projectToJson(
    jsonDocument: consema.json.Document,
    projectionRequest: consema.json.ProjectionRequest,
    compactRequest: MaterializationRequest,
): ByteArray {
    val projection = when (val result = jsonDocument.project(projectionRequest)) {
        is ProjectionResult.Complete -> result.projection
        is ProjectionResult.Failed -> error("projection failed: ${result.attempt.diagnostics}")
    }
    return when (val materialized = materialize(projection.value, compactRequest)) {
        is consema.document.MaterializationResult.Complete ->
            materialized.materialization.document.render()
        is consema.document.MaterializationResult.Failed ->
            error("materialization failed: ${materialized.attempt.failure}")
    }
}

fun main() {
    val source = """{"a":1,"b":{"c":2}}""".toByteArray(Charsets.UTF_8)
    val profile = ProfileId("json.strict", 1)

    // 1. Parse under the exact profile through the single facade parse entry.
    val document = parseDocument(source, profile)
    val status = document.formationStatus()
    if (status != consema.document.FormationStatus.Complete) {
        error("expected a Complete document, got $status")
    }
    println(
        "parse: profile=${document.profile().id} status=$status " +
            "render=${String(document.render(), Charsets.UTF_8)}",
    )
    val jsonDocument = document.asJson() ?: error("source is not a JSON document")

    // 2. Query `b.c` through the JSON native semantic domain.
    val expression = QueryExpression(ExpressionKind.Input)
        .then(OperatorCall("json.try-object-members", 1))
        .then(
            OperatorCall("json.member-name-equals", 1)
                .withArgument("name", PvString("b")),
        )
        .then(OperatorCall("json.member-value", 1))
        .then(OperatorCall("json.try-object-members", 1))
        .then(
            OperatorCall("json.member-name-equals", 1)
                .withArgument("name", PvString("c")),
        )
        .then(OperatorCall("json.member-value", 1))
    val definition = QueryDefinition(Domains.jsonNativeV1())
        .withExpression(expression)
        .withSelection(QuerySelection.RequireOne)
    val validated = definition.validate()
    val capabilities = CapabilitySet()
    capabilities.insert(CapabilityId("core.query.ordered-results", 1))
    val executable: ExecutableQuery = ExecutableQuery.bind(validated, capabilities)
    val matches = executeJsonQuery(executable, jsonDocument)
    // Render the matched value through the semantic tree API (the same walk
    // the edit target below uses).
    val cValue = memberValueRef(memberValueRef(jsonDocument.root(), "b"), "c")
    val kind = when (val availability = cValue.kind()) {
        is SemanticAvailability.Available -> availability.value.name
        is SemanticAvailability.Unavailable -> "?"
    }
    val value = when (val availability = cValue.asInteger()) {
        is SemanticAvailability.Available -> availability.value?.toString() ?: "?"
        is SemanticAvailability.Unavailable -> "?"
    }
    println("query b.c: matches=${matches.size} value=$value kind=$kind")

    // 3. Project the document with the conservative best-exact core target.
    val projectionRequest: consema.json.ProjectionRequest =
        ProjectionRequest.builder(ProjectionTarget.BestExactCoreV1).build()
    val compactRequest = MaterializationRequest.new(
        ProfileId("json.strict", 1),
        MaterializationStyleId("json.canonical-compact", 1),
    ).withNewline(NewlinePolicy.None)
    println(
        "project json.projection.best-exact-core@1: fidelity=${Fidelity.Exact} " +
            "value=${String(projectToJson(jsonDocument, projectionRequest, compactRequest), Charsets.UTF_8)}",
    )

    // 4. Edit `a` to 42 with a semantic scalar replacement under the
    //    profile-canonical representation policy.
    val aValue = memberValueRef(jsonDocument.root(), "a")
    val transaction = EditTransactionBuilder.new(jsonDocument)
        .semanticScalar(
            aValue.nodeRef(),
            PvInteger(BigInteger.valueOf(42)),
            RepresentationPolicy.CanonicalForProfile,
        )
        .build()
    val commit = jsonDocument.commit(transaction)
    val edited = commit.document
    println(
        "edit a->42 semantic_scalar CanonicalForProfile: " +
            "render=${String(edited.render(), Charsets.UTF_8)}",
    )

    // 5. Materialize the edited value as canonical compact JSON.
    println(
        "materialize json.canonical-compact: " +
            String(projectToJson(edited, projectionRequest, compactRequest), Charsets.UTF_8),
    )

    // 6. Convert the edited JSON document to TOML (two-stage composition).
    val tomlRequest = MaterializationRequest.new(
        ProfileId("toml.1.0", 1),
        MaterializationStyleId("toml.canonical-document", 1),
    ).withMappingPolicy(MappingPolicy.UniqueStringEntriesToObject)
    val conversion = convertJson(edited, projectionRequest, tomlRequest)
    when (conversion) {
        is ConversionResult.Complete -> {
            println("convert to toml.canonical-document:")
            print(String(conversion.conversion.document.render(), Charsets.UTF_8))
        }
        is ConversionResult.Failed ->
            error("conversion failed: ${conversion.failure.code}")
    }
}
