// The stable public diagnostic and failure code registry.
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-protocol/src/error_registry.rs — the v7
// registry pins 187 codes (55/62/90/92/132/166/187 across v1..v7;
// ERROR_CODES_V2 at error_registry.rs (V3 through V7). The records below are
// transcribed VERBATIM from the Rust registries (cross-checked against
// consema-go/go/protocol/error_registry.go):
// every code, category, introduced version, and description is
// byte-identical; nothing may be invented or dropped. The description
// wording is presentation metadata; code/category/introduced are
// normative.

package consema.protocol

import consema.core.PvArray
import consema.core.PvObject
import consema.core.PvString
import consema.core.PortableValue

/** The semantic category of one registered error code (the language-neutral
 * category spellings of the error-code manifest). */
enum class DiagnosticCategory(val wireName: String) {
    Lexical("Lexical"),
    Syntax("Syntax"),
    Conformance("Conformance"),
    Semantic("Semantic"),
    Query("Query"),
    Projection("Projection"),
    Materialization("Materialization"),
    Conversion("Conversion"),
    Edit("Edit"),
    Resource("Resource"),
    Encoding("Encoding"),
}

/** Parses one canonical category spelling. */
fun parseDiagnosticCategory(name: String): DiagnosticCategory =
    DiagnosticCategory.entries.firstOrNull { it.wireName == name }
        ?: throw invalid("$.category", "unknown error-code category")

/** One stable public code registry record. */
data class ErrorCodeDescriptor(
    /** The full namespaced code including `@version`. */
    val code: String,
    /** The semantic category. */
    val category: DiagnosticCategory,
    /** The first Consema release containing the code. */
    val introduced: String,
    /** The human-facing summary; not part of control flow. */
    val description: String,
)

/** Selects one frozen semantic-model error registry. */
enum class ErrorRegistryVersion {
    /** The Consema 0.3 error registry (55 codes). */
    V1,

    /** The Consema 0.4 error registry (62 codes). */
    V2,

    /** The Consema 0.5 error registry (90 codes). */
    V3,

    /** The Consema 0.6 error registry (92 codes). */
    V4,

    /** The Consema 0.7 error registry (132 codes). */
    V5,

    /** The Consema 0.8 error registry (166 codes). */
    V6,

    /** The Consema 0.12 error registry (187 codes, including the CLI error
     * family). */
    V7,
}

/** A closed, explicitly versioned error-code registry. */
class ErrorCodeRegistry private constructor(val version: ErrorRegistryVersion) {

    companion object {
        /** Returns the registry for one frozen semantic-model version. */
        fun forVersion(version: ErrorRegistryVersion): ErrorCodeRegistry =
            ErrorCodeRegistry(version)

        /** The semantic-model v1 registry (the Rust Default). */
        fun default(): ErrorCodeRegistry = forVersion(ErrorRegistryVersion.V1)
    }

    /** The sorted immutable descriptors (a copy; the records are never
     * mutated). */
    fun codes(): List<ErrorCodeDescriptor> = codesForVersion(version)

    /** Reports whether an exact full code is registered. */
    fun contains(candidate: String): Boolean = descriptor(candidate) != null

    /** The exact registered descriptor for one code, or null. */
    fun descriptor(candidate: String): ErrorCodeDescriptor? {
        val records = codesForVersion(version)
        var low = 0
        var high = records.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (records[middle].code < candidate) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        if (low < records.size && records[low].code == candidate) {
            return records[low]
        }
        return null
    }

    /** Rejects an unregistered public code (error_registry.rs). */
    fun validate(candidate: String) {
        if (!contains(candidate)) {
            throw invalid("$.code", "unregistered public code: $candidate")
        }
    }
}

private fun errorCode(id: String, category: DiagnosticCategory, introduced: String, description: String) =
    ErrorCodeDescriptor(id, category, introduced, description)

/**
 * Returns the frozen records of one semantic-model version. Versions v2..v7
 * are sorted merges of the previous version plus the version's new codes,
 * mirroring the Rust const-merge builders (error_registry.rs); the
 * test battery re-pins the counts, sortedness, and superset relationships.
 */
internal fun codesForVersion(version: ErrorRegistryVersion): List<ErrorCodeDescriptor> =
    when (version) {
        ErrorRegistryVersion.V1 -> ERROR_CODES_V1
        ErrorRegistryVersion.V2 -> mergeErrorCodes(ERROR_CODES_V1, NEW_CODES_V2)
        ErrorRegistryVersion.V3 -> mergeErrorCodes(codesForVersion(ErrorRegistryVersion.V2), NEW_CODES_V3)
        ErrorRegistryVersion.V4 -> mergeErrorCodes(codesForVersion(ErrorRegistryVersion.V3), NEW_CODES_V4)
        ErrorRegistryVersion.V5 -> mergeErrorCodes(codesForVersion(ErrorRegistryVersion.V4), NEW_CODES_V5)
        ErrorRegistryVersion.V6 -> mergeErrorCodes(codesForVersion(ErrorRegistryVersion.V5), NEW_CODES_V6)
        ErrorRegistryVersion.V7 -> mergeErrorCodes(codesForVersion(ErrorRegistryVersion.V6), NEW_CODES_V7)
    }

/** Merges two strictly sorted code lists into one strictly sorted list,
 * rejecting duplicates. */
private fun mergeErrorCodes(
    old: List<ErrorCodeDescriptor>,
    added: List<ErrorCodeDescriptor>,
): List<ErrorCodeDescriptor> {
    val merged = ArrayList<ErrorCodeDescriptor>(old.size + added.size)
    var left = 0
    var right = 0
    while (left < old.size && right < added.size) {
        if (old[left].code < added[right].code) {
            merged.add(old[left])
            left++
        } else {
            merged.add(added[right])
            right++
        }
    }
    while (left < old.size) {
        merged.add(old[left])
        left++
    }
    while (right < added.size) {
        merged.add(added[right])
        right++
    }
    return merged
}

// The semantic-model v1 records (ERROR_CODES_V1, 55 codes). Strictly sorted
// by code; introduced versions and descriptions transcribed verbatim from
// https://github.com/consema/consema-rs/blob/main/consema-protocol/src/error_registry.rs.
private val ERROR_CODES_V1: List<ErrorCodeDescriptor> = listOf(
    errorCode("core.diagnostic.truncated@1", DiagnosticCategory.Resource, "0.1.0", "Diagnostic limit truncated a sequence"),
    errorCode("core.parse.resource-limit@1", DiagnosticCategory.Resource, "0.1.0", "Parser resource limit was reached"),
    errorCode("core.projection.conflicting-policy@1", DiagnosticCategory.Projection, "0.1.0", "Projection policy rules conflict"),
    errorCode("core.projection.invalid-policy-target@1", DiagnosticCategory.Projection, "0.1.0", "Projection policy target is invalid"),
    errorCode("core.projection.resource-limit@1", DiagnosticCategory.Resource, "0.1.0", "Projection resource limit was reached"),
    errorCode("core.projection.target-not-applicable@1", DiagnosticCategory.Projection, "0.1.0", "Projection target does not apply"),
    errorCode("core.projection.wrong-snapshot-policy@1", DiagnosticCategory.Projection, "0.1.0", "Projection policy uses another snapshot"),
    errorCode("core.protocol.invalid-json@1", DiagnosticCategory.Encoding, "0.3.0", "Protocol JSON is invalid"),
    errorCode("core.protocol.invalid-pvce@1", DiagnosticCategory.Encoding, "0.3.0", "Protocol PVCE is invalid"),
    errorCode("core.protocol.invalid-value@1", DiagnosticCategory.Encoding, "0.3.0", "Protocol field value violates its invariant"),
    errorCode("core.protocol.missing-field@1", DiagnosticCategory.Encoding, "0.3.0", "Required protocol field is absent"),
    errorCode("core.protocol.non-canonical-json@1", DiagnosticCategory.Encoding, "0.3.0", "Protocol JSON is not canonical"),
    errorCode("core.protocol.process-local-handle@1", DiagnosticCategory.Encoding, "0.3.0", "Process-local handle cannot cross the wire"),
    errorCode("core.protocol.resource-limit@1", DiagnosticCategory.Resource, "0.3.0", "Protocol resource limit was reached"),
    errorCode("core.protocol.schema-mismatch@1", DiagnosticCategory.Encoding, "0.3.0", "Protocol schema or field order does not match"),
    errorCode("core.protocol.unknown-contract@1", DiagnosticCategory.Encoding, "0.3.0", "Protocol contract ID or version is unknown"),
    errorCode("core.protocol.unknown-field@1", DiagnosticCategory.Encoding, "0.3.0", "Fixed protocol schema contains an unknown field"),
    errorCode("core.protocol.wrong-type@1", DiagnosticCategory.Encoding, "0.3.0", "Protocol field has the wrong value type"),
    errorCode("core.query.cancelled@1", DiagnosticCategory.Query, "0.3.0", "Query execution was cancelled"),
    errorCode("core.query.cardinality-violation@1", DiagnosticCategory.Query, "0.3.0", "Query selection cardinality was violated"),
    errorCode("core.query.domain-mismatch@1", DiagnosticCategory.Query, "0.3.0", "Query domain is unknown or mismatched"),
    errorCode("core.query.invalid-argument@1", DiagnosticCategory.Query, "0.3.0", "Query operator argument is invalid"),
    errorCode("core.query.invalid-composition@1", DiagnosticCategory.Query, "0.3.0", "Query operator roles cannot be composed"),
    errorCode("core.query.missing-capability@1", DiagnosticCategory.Query, "0.3.0", "Query implementation lacks a required capability"),
    errorCode("core.query.required-type-mismatch@1", DiagnosticCategory.Query, "0.3.0", "Required query value type did not match"),
    errorCode("core.query.resource-limit@1", DiagnosticCategory.Resource, "0.3.0", "Query resource limit was reached"),
    errorCode("core.query.target-unavailable@1", DiagnosticCategory.Query, "0.3.0", "Target native semantics are unavailable"),
    errorCode("core.query.unknown-operator@1", DiagnosticCategory.Query, "0.3.0", "Query operator ID or version is unknown"),
    errorCode("core.query.wrong-argument-type@1", DiagnosticCategory.Query, "0.3.0", "Query operator argument has the wrong type"),
    errorCode("core.source.invalid-utf8@1", DiagnosticCategory.Lexical, "0.1.0", "Source bytes are not valid UTF-8"),
    errorCode("json.edit.representation-fallback@1", DiagnosticCategory.Edit, "0.1.0", "JSON edit used an authorized canonical fallback"),
    errorCode("json.object.duplicate-member@1", DiagnosticCategory.Semantic, "0.1.0", "JSON object contains duplicate member names"),
    errorCode("json.projection.duplicate-keys@1", DiagnosticCategory.Projection, "0.1.0", "JSON projection encountered duplicate keys"),
    errorCode("json.projection.semantic-unavailable@1", DiagnosticCategory.Projection, "0.1.0", "Recovered JSON region lacks native semantics"),
    errorCode("json.strict.comment-not-allowed@1", DiagnosticCategory.Conformance, "0.1.0", "Strict JSON profile rejects comments"),
    errorCode("json.strict.leading-bom@1", DiagnosticCategory.Conformance, "0.1.0", "Strict JSON source has a leading BOM"),
    errorCode("json.strict.trailing-comma@1", DiagnosticCategory.Conformance, "0.1.0", "Strict JSON profile rejects trailing commas"),
    errorCode("json.syntax.expected-object-key@1", DiagnosticCategory.Syntax, "0.1.0", "JSON object key was expected"),
    errorCode("json.syntax.expected-value@1", DiagnosticCategory.Syntax, "0.1.0", "JSON value was expected"),
    errorCode("json.syntax.invalid-number@1", DiagnosticCategory.Syntax, "0.1.0", "JSON number syntax is invalid"),
    errorCode("json.syntax.invalid-string-escape@1", DiagnosticCategory.Syntax, "0.1.0", "JSON string escape is invalid"),
    errorCode("json.syntax.missing-array-close@1", DiagnosticCategory.Syntax, "0.1.0", "JSON array close delimiter is missing"),
    errorCode("json.syntax.missing-colon@1", DiagnosticCategory.Syntax, "0.1.0", "JSON member colon is missing"),
    errorCode("json.syntax.missing-comma@1", DiagnosticCategory.Syntax, "0.1.0", "JSON container comma is missing"),
    errorCode("json.syntax.missing-object-close@1", DiagnosticCategory.Syntax, "0.1.0", "JSON object close delimiter is missing"),
    errorCode("json.syntax.missing-value@1", DiagnosticCategory.Syntax, "0.1.0", "JSON value is missing"),
    errorCode("json.syntax.trailing-content@1", DiagnosticCategory.Syntax, "0.1.0", "JSON has trailing content"),
    errorCode("json.syntax.unexpected-character@1", DiagnosticCategory.Syntax, "0.1.0", "JSON has an unexpected character"),
    errorCode("json.syntax.unexpected-word@1", DiagnosticCategory.Syntax, "0.1.0", "JSON has an unexpected word"),
    errorCode("json.syntax.unterminated-block-comment@1", DiagnosticCategory.Syntax, "0.1.0", "JSONC block comment is unterminated"),
    errorCode("json.syntax.unterminated-string@1", DiagnosticCategory.Syntax, "0.1.0", "JSON string is unterminated"),
    errorCode("toml.edit.representation-fallback@1", DiagnosticCategory.Edit, "0.2.0", "TOML edit used an authorized canonical fallback"),
    errorCode("toml.parse.syntax@1", DiagnosticCategory.Syntax, "0.2.0", "TOML syntax is invalid"),
    errorCode("toml.projection.core-invariant@1", DiagnosticCategory.Projection, "0.2.0", "TOML projection hit a core invariant"),
    errorCode("toml.projection.unrepresentable-datetime@1", DiagnosticCategory.Projection, "0.2.0", "TOML temporal value is not exactly representable"),
)

// The semantic-model v2 additions (7 codes).
private val NEW_CODES_V2: List<ErrorCodeDescriptor> = listOf(
    errorCode("core.source.encoding-conflict@1", DiagnosticCategory.Encoding, "0.4.0", "Source encoding facts conflict"),
    errorCode("core.source.invalid-sequence@1", DiagnosticCategory.Lexical, "0.4.0", "Source bytes are invalid for the selected encoding"),
    errorCode("core.source.patch-base-mismatch@1", DiagnosticCategory.Edit, "0.4.0", "SourcePatch base digest does not match"),
    errorCode("core.source.patch-original-mismatch@1", DiagnosticCategory.Edit, "0.4.0", "SourcePatch original-byte precondition does not match"),
    errorCode("core.source.patch-target-mismatch@1", DiagnosticCategory.Edit, "0.4.0", "SourcePatch target digest does not match"),
    errorCode("core.source.resource-limit@1", DiagnosticCategory.Resource, "0.4.0", "Source construction or patch limit was reached"),
    errorCode("core.source.unsupported-bom@1", DiagnosticCategory.Encoding, "0.4.0", "Source begins with an unsupported byte-order mark"),
)

// The semantic-model v3 additions (28 codes).
private val NEW_CODES_V3: List<ErrorCodeDescriptor> = listOf(
    errorCode("core.conversion.materialization-failed@1", DiagnosticCategory.Conversion, "0.5.0", "Conversion target materialization failed"),
    errorCode("core.conversion.projection-failed@1", DiagnosticCategory.Conversion, "0.5.0", "Conversion source projection failed"),
    errorCode("core.conversion.unauthorized-loss@1", DiagnosticCategory.Conversion, "0.5.0", "Conversion encountered loss without explicit authorization"),
    errorCode("core.edit.conflicting-edits@1", DiagnosticCategory.Edit, "0.5.0", "Edit operations have conflicting source ownership"),
    errorCode("core.edit.duplicate-key@1", DiagnosticCategory.Edit, "0.5.0", "Edit would create a duplicate key"),
    errorCode("core.edit.exact-literal-requires-literal@1", DiagnosticCategory.Edit, "0.5.0", "Exact literal policy requires a literal operation"),
    errorCode("core.edit.formation-failed@1", DiagnosticCategory.Edit, "0.5.0", "Edited bytes did not form the required target document"),
    errorCode("core.edit.incomplete-target@1", DiagnosticCategory.Edit, "0.5.0", "Edit target is not a complete syntax node"),
    errorCode("core.edit.invalid-literal@1", DiagnosticCategory.Edit, "0.5.0", "Edit literal is invalid for the target profile"),
    errorCode("core.edit.operation-unsupported@1", DiagnosticCategory.Edit, "0.5.0", "Edit operation is not supported for the target"),
    errorCode("core.edit.precondition-failed@1", DiagnosticCategory.Edit, "0.5.0", "Edit original-byte or digest precondition failed"),
    errorCode("core.edit.representation-incompatible@1", DiagnosticCategory.Edit, "0.5.0", "Edit representation policy cannot preserve the target category"),
    errorCode("core.edit.resource-limit@1", DiagnosticCategory.Resource, "0.5.0", "Edit planning or commit resource limit was reached"),
    errorCode("core.edit.semantic-unavailable@1", DiagnosticCategory.Edit, "0.5.0", "Edit target native semantics are unavailable"),
    errorCode("core.edit.target-not-found@1", DiagnosticCategory.Edit, "0.5.0", "Edit target or placement anchor was not found"),
    errorCode("core.edit.unsupported-value@1", DiagnosticCategory.Edit, "0.5.0", "Edit value is not representable by the target profile"),
    errorCode("core.edit.wrong-role@1", DiagnosticCategory.Edit, "0.5.0", "Edit target has the wrong structural role"),
    errorCode("core.edit.wrong-snapshot@1", DiagnosticCategory.Edit, "0.5.0", "Edit target belongs to another snapshot"),
    errorCode("core.materialization.formation-failed@1", DiagnosticCategory.Materialization, "0.5.0", "Generated bytes did not form the target profile"),
    errorCode("core.materialization.invalid-request@1", DiagnosticCategory.Materialization, "0.5.0", "Materialization request fields are contradictory"),
    errorCode("core.materialization.mapping-transformed@1", DiagnosticCategory.Materialization, "0.5.0", "Ordered mapping was explicitly transformed into an object"),
    errorCode("core.materialization.resource-limit@1", DiagnosticCategory.Resource, "0.5.0", "Materialization resource limit was reached"),
    errorCode("core.materialization.unrepresentable@1", DiagnosticCategory.Materialization, "0.5.0", "Portable input cannot be represented by the target profile"),
    errorCode("core.materialization.unsupported-encoding@1", DiagnosticCategory.Encoding, "0.5.0", "Target profile does not support the requested encoding"),
    errorCode("core.materialization.unsupported-newline@1", DiagnosticCategory.Materialization, "0.5.0", "Target style does not support the requested newline policy"),
    errorCode("core.materialization.unsupported-profile@1", DiagnosticCategory.Materialization, "0.5.0", "Requested materialization profile is unavailable"),
    errorCode("core.materialization.unsupported-style@1", DiagnosticCategory.Materialization, "0.5.0", "Requested materialization style is unavailable"),
    errorCode("json.projection.structure-reencoded@1", DiagnosticCategory.Projection, "0.5.0", "JSON object structure was reversibly represented as an entry mapping"),
)

// The semantic-model v4 additions (2 codes).
private val NEW_CODES_V4: List<ErrorCodeDescriptor> = listOf(
    errorCode("json5.string.unescaped-line-separator@1", DiagnosticCategory.Conformance, "0.6.0", "JSON5 string contains an unescaped Unicode line separator"),
    errorCode("json5.syntax.invalid-identifier@1", DiagnosticCategory.Syntax, "0.6.0", "JSON5 IdentifierName syntax is invalid"),
)

// The semantic-model v5 additions (40 codes).
private val NEW_CODES_V5: List<ErrorCodeDescriptor> = listOf(
    errorCode("core.graph.invalid@1", DiagnosticCategory.Semantic, "0.7.0", "PortableGraph construction invariants were violated"),
    errorCode("core.graph.resource-limit@1", DiagnosticCategory.Resource, "0.7.0", "PortableGraph construction or traversal limit was reached"),
    errorCode("core.pgce.invalid@1", DiagnosticCategory.Encoding, "0.7.0", "PGCE input is structurally invalid"),
    errorCode("core.pgce.non-canonical@1", DiagnosticCategory.Encoding, "0.7.0", "PGCE input is valid but not canonical"),
    errorCode("core.pgce.resource-limit@1", DiagnosticCategory.Resource, "0.7.0", "PGCE encode or decode limit was reached"),
    errorCode("core.pgce.unsupported-version@1", DiagnosticCategory.Encoding, "0.7.0", "PGCE wire version is unsupported"),
    errorCode("yaml.alias.name-mismatch@1", DiagnosticCategory.Semantic, "0.7.0", "YAML alias name does not match its resolved anchor"),
    errorCode("yaml.alias.name-unavailable@1", DiagnosticCategory.Semantic, "0.7.0", "YAML alias event lacks a usable name"),
    errorCode("yaml.anchor.name-unavailable@1", DiagnosticCategory.Semantic, "0.7.0", "YAML anchor event lacks a usable name"),
    errorCode("yaml.anchor.unknown@1", DiagnosticCategory.Semantic, "0.7.0", "YAML alias refers to an undefined anchor"),
    errorCode("yaml.edit.anchor-dependency@1", DiagnosticCategory.Edit, "0.7.0", "YAML edit would leave a live alias without its anchor"),
    errorCode("yaml.edit.anchor-not-visible@1", DiagnosticCategory.Edit, "0.7.0", "YAML alias insertion target is not the visible anchor definition"),
    errorCode("yaml.edit.canonical-fallback@1", DiagnosticCategory.Edit, "0.7.0", "YAML edit used an authorized canonical scalar fallback"),
    errorCode("yaml.edit.invalid-anchor-name@1", DiagnosticCategory.Edit, "0.7.0", "YAML anchor edit name is invalid"),
    errorCode("yaml.edit.invalid-placement@1", DiagnosticCategory.Edit, "0.7.0", "YAML structural edit placement is invalid"),
    errorCode("yaml.edit.structural-container-conflict@1", DiagnosticCategory.Edit, "0.7.0", "Multiple structural edits target the same base YAML container"),
    errorCode("yaml.mapping.missing-value@1", DiagnosticCategory.Semantic, "0.7.0", "YAML mapping event stream lacks an association value"),
    errorCode("yaml.materialization.cross-document-sharing@1", DiagnosticCategory.Materialization, "0.7.0", "YAML cannot preserve graph sharing across document roots"),
    errorCode("yaml.materialization.round-trip-mismatch@1", DiagnosticCategory.Materialization, "0.7.0", "Generated YAML did not reproduce the promised input value"),
    errorCode("yaml.materialization.tag-kind-mismatch@1", DiagnosticCategory.Materialization, "0.7.0", "YAML tag is incompatible with the graph node kind"),
    errorCode("yaml.materialization.unsupported-tag@1", DiagnosticCategory.Materialization, "0.7.0", "YAML materializer has no published constructor for a tag"),
    errorCode("yaml.native.invalid-source-span@1", DiagnosticCategory.Semantic, "0.7.0", "YAML native event span is outside the source snapshot"),
    errorCode("yaml.native.trailing-events@1", DiagnosticCategory.Semantic, "0.7.0", "YAML native composition left trailing structural events"),
    errorCode("yaml.native.trailing-named-occurrence@1", DiagnosticCategory.Semantic, "0.7.0", "YAML native composition left an unmatched anchor or alias occurrence"),
    errorCode("yaml.native.unexpected-end@1", DiagnosticCategory.Semantic, "0.7.0", "YAML native event stream ended unexpectedly"),
    errorCode("yaml.native.unexpected-event@1", DiagnosticCategory.Semantic, "0.7.0", "YAML native event order is invalid"),
    errorCode("yaml.parse.syntax@1", DiagnosticCategory.Syntax, "0.7.0", "YAML source does not satisfy the selected grammar"),
    errorCode("yaml.profile.version-directive@1", DiagnosticCategory.Conformance, "0.7.0", "YAML version directive conflicts with the selected profile"),
    errorCode("yaml.projection.cycle@1", DiagnosticCategory.Projection, "0.7.0", "YAML representation cycle cannot enter a PortableValue tree"),
    errorCode("yaml.projection.document-cardinality@1", DiagnosticCategory.Projection, "0.7.0", "YAML stream cardinality does not satisfy a single-value projection"),
    errorCode("yaml.projection.graph-invalid@1", DiagnosticCategory.Projection, "0.7.0", "YAML representation graph could not form a PortableGraph"),
    errorCode("yaml.projection.invalid-canonical-scalar@1", DiagnosticCategory.Projection, "0.7.0", "YAML canonical scalar cannot form its promised PortableValue kind"),
    errorCode("yaml.projection.mapping-not-object@1", DiagnosticCategory.Projection, "0.7.0", "YAML mapping does not satisfy the requested Object policy"),
    errorCode("yaml.projection.provenance-limit@1", DiagnosticCategory.Resource, "0.7.0", "YAML graph projection provenance limit was reached"),
    errorCode("yaml.projection.resource-limit@1", DiagnosticCategory.Resource, "0.7.0", "YAML value or graph projection limit was reached"),
    errorCode("yaml.projection.sharing@1", DiagnosticCategory.Projection, "0.7.0", "YAML shared identity requires explicit tree-duplication policy"),
    errorCode("yaml.projection.unrepresentable-timestamp@1", DiagnosticCategory.Projection, "0.7.0", "YAML timestamp is outside PortableValue temporal categories"),
    errorCode("yaml.projection.unsupported-tag@1", DiagnosticCategory.Projection, "0.7.0", "YAML tag has no published target projection semantics"),
    errorCode("yaml.scalar.invalid-explicit-tag@1", DiagnosticCategory.Semantic, "0.7.0", "YAML scalar content is invalid for its explicit tag"),
    errorCode("yaml.tag.kind-mismatch@1", DiagnosticCategory.Semantic, "0.7.0", "YAML tag is incompatible with the representation node kind"),
)

// The semantic-model v6 additions (34 codes).
private val NEW_CODES_V6: List<ErrorCodeDescriptor> = listOf(
    errorCode("core.source.code-page-required@1", DiagnosticCategory.Encoding, "0.8.0", "The selected source profile requires an explicit Windows code page"),
    errorCode("core.source.unsupported-code-page@1", DiagnosticCategory.Encoding, "0.8.0", "The requested Windows code page is not in the portable registry"),
    errorCode("ini.edit.canonical-fallback@1", DiagnosticCategory.Edit, "0.8.0", "INI editing used an authorized canonical representation fallback"),
    errorCode("ini.edit.case-collision@1", DiagnosticCategory.Edit, "0.8.0", "INI editing would create a profile-equivalent name collision"),
    errorCode("ini.edit.invalid-name@1", DiagnosticCategory.Edit, "0.8.0", "INI section or entry name is invalid for the selected profile"),
    errorCode("ini.edit.invalid-placement@1", DiagnosticCategory.Edit, "0.8.0", "INI structural edit placement is invalid"),
    errorCode("ini.formation.case-collision@1", DiagnosticCategory.Semantic, "0.8.0", "INI formation found profile-equivalent names with different spelling"),
    errorCode("ini.formation.duplicate-entry@1", DiagnosticCategory.Semantic, "0.8.0", "INI formation found a duplicate entry"),
    errorCode("ini.formation.duplicate-section@1", DiagnosticCategory.Semantic, "0.8.0", "INI formation found a duplicate section"),
    errorCode("ini.materialization.round-trip-mismatch@1", DiagnosticCategory.Materialization, "0.8.0", "Generated INI did not reproduce the promised input value"),
    errorCode("ini.parse.invalid-character@1", DiagnosticCategory.Syntax, "0.8.0", "INI source contains a character forbidden by the selected profile"),
    errorCode("ini.parse.invalid-continuation@1", DiagnosticCategory.Syntax, "0.8.0", "INI continuation syntax is invalid"),
    errorCode("ini.parse.malformed-line@1", DiagnosticCategory.Syntax, "0.8.0", "INI source line is malformed"),
    errorCode("ini.parse.malformed-section@1", DiagnosticCategory.Syntax, "0.8.0", "INI section header is malformed"),
    errorCode("ini.parse.missing-delimiter@1", DiagnosticCategory.Syntax, "0.8.0", "INI entry is missing a required key/value delimiter"),
    errorCode("ini.parse.missing-section@1", DiagnosticCategory.Conformance, "0.8.0", "INI entry appears where the selected profile requires a section"),
    errorCode("ini.profile.encoding@1", DiagnosticCategory.Encoding, "0.8.0", "INI source encoding conflicts with the selected profile"),
    errorCode("ini.profile.mismatch@1", DiagnosticCategory.Conformance, "0.8.0", "INI operation profile does not match the document profile"),
    errorCode("ini.projection.collision@1", DiagnosticCategory.Projection, "0.8.0", "INI projection encountered a rejected key or section collision"),
    errorCode("ini.projection.duplicate-collapsed@1", DiagnosticCategory.Projection, "0.8.0", "INI projection collapsed a duplicate under explicit policy"),
    errorCode("ini.projection.incomplete-document@1", DiagnosticCategory.Projection, "0.8.0", "Recovered INI syntax cannot enter a complete semantic projection"),
    errorCode("ini.query.invalid-name-mode@1", DiagnosticCategory.Query, "0.8.0", "INI query name comparison mode is invalid"),
    errorCode("java-properties.edit.canonical-fallback@1", DiagnosticCategory.Edit, "0.8.0", "Properties editing used an authorized canonical representation fallback"),
    errorCode("java-properties.edit.invalid-placement@1", DiagnosticCategory.Edit, "0.8.0", "Properties structural edit placement is invalid"),
    errorCode("java-properties.java-string.invalid-wire@1", DiagnosticCategory.Encoding, "0.8.0", "Exact Java UTF-16 string wire content is invalid"),
    errorCode("java-properties.java-string.non-canonical-wire@1", DiagnosticCategory.Encoding, "0.8.0", "Exact Java UTF-16 string wire content is not canonical"),
    errorCode("java-properties.materialization.round-trip-mismatch@1", DiagnosticCategory.Materialization, "0.8.0", "Generated Properties text did not reproduce the promised input value"),
    errorCode("java-properties.parse.malformed-unicode-escape@1", DiagnosticCategory.Syntax, "0.8.0", "Properties Unicode escape is malformed"),
    errorCode("java-properties.profile.mismatch@1", DiagnosticCategory.Conformance, "0.8.0", "Properties operation profile does not match the document profile"),
    errorCode("java-properties.projection.duplicate-collapsed@1", DiagnosticCategory.Projection, "0.8.0", "Properties projection collapsed a duplicate under explicit policy"),
    errorCode("java-properties.projection.incomplete-document@1", DiagnosticCategory.Projection, "0.8.0", "Recovered Properties syntax cannot enter a complete semantic projection"),
    errorCode("java-properties.projection.unpaired-surrogate@1", DiagnosticCategory.Projection, "0.8.0", "Properties content with an unpaired surrogate cannot become a PortableValue String"),
    errorCode("java-properties.query.invalid-code-unit-filter@1", DiagnosticCategory.Query, "0.8.0", "Properties query UTF-16 code-unit filter is invalid"),
    errorCode("java-properties.source.profile-encoding@1", DiagnosticCategory.Encoding, "0.8.0", "Properties source encoding conflicts with the selected profile"),
)

// The semantic-model v7 additions (21 codes: the RFC 0015 §13.1 CLI error
// family of 20 codes plus the 0.13.0 json.projection.incomplete-document@1
// registration, audit finding F3).
private val NEW_CODES_V7: List<ErrorCodeDescriptor> = listOf(
    errorCode("cli.data.invalid-request@1", DiagnosticCategory.Encoding, "0.12.0", "Request or plan file failed strict decoding"),
    errorCode("cli.data.io@1", DiagnosticCategory.Encoding, "0.12.0", "Input file could not be read"),
    errorCode("cli.detection.ambiguous@1", DiagnosticCategory.Semantic, "0.12.0", "Candidate profiles are ambiguous and no profile was selected"),
    errorCode("cli.internal.unclassified@1", DiagnosticCategory.Semantic, "0.12.0", "Unclassified internal CLI error"),
    errorCode("cli.interrupted.signal@1", DiagnosticCategory.Semantic, "0.12.0", "CLI execution was interrupted by a signal"),
    errorCode("cli.limit.batch-count@1", DiagnosticCategory.Resource, "0.12.0", "Batch file count exceeded the configured limit"),
    errorCode("cli.limit.file-size@1", DiagnosticCategory.Resource, "0.12.0", "Input file exceeded the CLI file-size limit"),
    errorCode("cli.limit.manifest-size@1", DiagnosticCategory.Resource, "0.12.0", "Manifest or request input exceeded the size limit"),
    errorCode("cli.usage.invalid-argument@1", DiagnosticCategory.Syntax, "0.12.0", "Known argument received an invalid value"),
    errorCode("cli.usage.invalid-format@1", DiagnosticCategory.Syntax, "0.12.0", "--format is missing or invalid"),
    errorCode("cli.usage.missing-plan@1", DiagnosticCategory.Syntax, "0.12.0", "--apply requires a prior plan"),
    errorCode("cli.usage.missing-required@1", DiagnosticCategory.Syntax, "0.12.0", "A required argument such as --profile is missing"),
    errorCode("cli.usage.redaction-pattern@1", DiagnosticCategory.Syntax, "0.12.0", "--redact-keys pattern is invalid"),
    errorCode("cli.usage.unknown-argument@1", DiagnosticCategory.Syntax, "0.12.0", "Unknown argument or rejected abbreviation"),
    errorCode("cli.usage.unknown-command@1", DiagnosticCategory.Syntax, "0.12.0", "Unknown command"),
    errorCode("cli.write.io@1", DiagnosticCategory.Edit, "0.12.0", "Write I/O failure such as a full disk"),
    errorCode("cli.write.permission@1", DiagnosticCategory.Edit, "0.12.0", "Permission denied while writing the target"),
    errorCode("cli.write.read-only@1", DiagnosticCategory.Edit, "0.12.0", "Target file is read-only"),
    errorCode("cli.write.symlink-policy@1", DiagnosticCategory.Edit, "0.12.0", "Write path rejected by the symlink policy"),
    errorCode("cli.write.target-is-directory@1", DiagnosticCategory.Edit, "0.12.0", "Write target is a directory"),
    // Registered in 0.13.0 (audit finding F3): the 0.13.0 json
    // Recovered-document gate emits this code (consema-json projection.rs
    // 756) and the CLI's failed projection record requires it to be
    // registry-validated; without the entry the CLI panicked on `.expect`.
    errorCode("json.projection.incomplete-document@1", DiagnosticCategory.Projection, "0.13.0", "Recovered JSON syntax cannot enter a complete semantic projection"),
)

/** Encodes one `core.error-code-registry@1` payload
 * (error_registry.rs). */
internal fun errorCodeManifestValueFor(registry: ErrorCodeRegistry): PortableValue {
    val items = registry.codes().map { descriptor ->
        PvObject(
            listOf(
                consema.core.Entry("code", PvString(descriptor.code)),
                consema.core.Entry("category", PvString(descriptor.category.wireName)),
                consema.core.Entry("introduced", PvString(descriptor.introduced)),
                consema.core.Entry("stability", PvString("Stable")),
                consema.core.Entry("description", PvString(descriptor.description)),
            ),
        )
    }
    return PvObject(
        listOf(
            consema.core.Entry("schema", PvString("core.error-code-registry@1")),
            consema.core.Entry("error_codes", PvArray(items)),
        ),
    )
}

/** Encodes the semantic-model v7 `core.error-code-registry@1` payload. */
fun errorCodeManifestValue(): PortableValue =
    errorCodeManifestValueFor(ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7))

/** Strictly validates one transferable `core.error-code-registry@1` value
 * (error_registry.rs). Identity, ordering, category, and
 * stability are normative; the description wording is presentation metadata
 * and is not re-checked for equality. */
fun validateErrorCodeManifestValue(value: PortableValue) {
    val fields = schemaFields(value, "core.error-code-registry@1", listOf("schema", "error_codes"), "$")
    val items = sequenceOf(fields[1], "$.error_codes")
    var previous = ""
    for ((index, item) in items.withIndex()) {
        val path = "$.error_codes[$index]"
        val entry = exactFields(
            item,
            listOf("code", "category", "introduced", "stability", "description"),
            path,
        )
        val code = stringOf(entry[0], "$path.code")
        validateVersionedCode(code, "$path.code")
        val categoryText = stringOf(entry[1], "$path.category")
        parseDiagnosticCategory(categoryText)
        val introduced = stringOf(entry[2], "$path.introduced")
        val description = stringOf(entry[4], "$path.description")
        if (introduced.isEmpty() || description.isEmpty()) {
            throw invalid(path, "introduced and description must be non-empty")
        }
        val stability = stringOf(entry[3], "$path.stability")
        if (stability != "Stable") {
            throw invalid("$path.stability", "unknown error-code stability")
        }
        if (previous.isNotEmpty() && previous >= code) {
            throw invalid("$.error_codes", "error codes must be sorted and unique")
        }
        previous = code
    }
}

/** Requires the `id@version` shape of a registered code
 * (error_registry.rs). */
internal fun validateVersionedCode(code: String, path: String) {
    val at = code.lastIndexOf('@')
    if (at < 0) {
        throw invalid(path, "code lacks @version suffix")
    }
    val id = code.substring(0, at)
    val versionText = code.substring(at + 1)
    if (versionText.isEmpty() || versionText.any { !it.isDigit() }) {
        throw invalid(path, "code version is invalid")
    }
    var version = 0uL
    for (digit in versionText) {
        version = version * 10uL + (digit - '0').toULong()
        if (version > 0xffff_ffffuL) {
            throw invalid(path, "code version is invalid")
        }
    }
    if (version == 0uL) {
        throw invalid(path, "code version is invalid")
    }
    validateIdentifier(id, path)
}
