// Typed formation, access, and operation failures of the HCL family, and
// the frozen `hcl.*@1` diagnostic-code inventory.
//
// Data authority:
//   - RFC 0014 §11 (docs/rfcs/0014-hcl-family-profiles-v1.md:674-714): the
//     `hcl.*` diagnostic codes are registered by RFC 0014 and are part of
//     the `hcl.native@1` and `hcl.tfvars@1` contracts. They do NOT enter the
//     `consema-protocol` core error registry, which covers only core/protocol
//     and line-format contract codes (RFC 0011 §10); when HCL diagnostics are
//     externalized through the protocol they follow RFC 0011's error-code
//     classification rules (RFC 0014 §11).
//   - The frozen codes are transcribed from the byte-arbitration source
//     crates/consema-hcl:
//       parse family: lexer.rs:461-486 (byte-order-mark@1, lone-cr@1,
//         invalid-utf8@1, identifier@1, invalid-number@1,
//         invalid-character@1, invalid-escape@1, unterminated-comment@1,
//         unterminated-string@1, unterminated-interpolation@1,
//         unterminated-directive@1, unterminated-heredoc@1,
//         heredoc-marker@1), parser.rs:80-97 (item@1, attribute@1, block@1,
//         label@1, expression@1, directive@1, newline@1, separator@1,
//         duplicate-attribute@1), lib.rs:301 (encoding@1), parser.rs:2616
//         (internal@1), parser.rs:2627 (coverage@1), parser.rs:2637
//         (coordinates@1);
//       tfvars family: document.rs:48 (hcl.tfvars.block-not-allowed@1);
//       limit family: parser.rs:2592 (offset-overflow@1), parser.rs:4074-4330
//         (expression-depth@1, body-depth@1, template-depth@1,
//         attribute-count@1, block-count@1, body-item-count@1,
//         label-count@1, number-digits@1, tuple-elements@1,
//         object-entries@1, for-extent@1, recovery-regions@1,
//         error-regions@1), lexer.rs:2674-3672 (identifier-len@1,
//         string-len@1, template-len@1, template-interpolations@1,
//         heredoc-lines@1, heredoc-bytes@1, token-count@1,
//         syntax-pieces@1);
//       projection family: projection.rs:470-474 (incomplete-document@1,
//         non-literal-expression@1, unrepresentable@1, resource-limit@1,
//         core-invariant@1);
//       materialization family: materialization.rs:129-131
//         (hcl.materialization.unrepresentable@1,
//         hcl.materialization.resource-limit@1);
//       edit family: edit.rs:604-607 (duplicate-attribute@1,
//         block-in-tfvars@1, unrepresentable@1);
//       query family: query.rs:802-803 (hcl.query.type-mismatch@1,
//         hcl.query.non-literal@1).
//   - The operation ids are pinned in Operations.kt (operation_registry.rs).
//   - go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the family carries its own
// immutable diagnostic record because the protocol `Diagnostic` primary
// location is wire-shaped (a caller-stable source ID is mandatory) while
// formation/projection/edit diagnostics are snapshot-bound byte spans; the
// record maps to the transferable `core.diagnostic@1` form once a caller
// supplies the stable source ID and an error-code registry, exactly like the
// TOML family precedent (toml/Errors.kt).

package consema.hcl

import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.ErrorCodeRegistry
import consema.protocol.QueryFailureKind
import consema.protocol.Severity
import consema.protocol.SourceLocation

/** `hcl.parse.byte-order-mark@1` (lexer.rs:461): a UTF-8 BOM is a Profile
 * violation; formation is Recovered (RFC 0014 §2). */
const val HCL_PARSE_BYTE_ORDER_MARK = "hcl.parse.byte-order-mark@1"

/** `hcl.parse.lone-cr@1` (lexer.rs:463): a lone CR is not a newline; the
 * source forms Recovered (RFC 0014 §2). */
const val HCL_PARSE_LONE_CR = "hcl.parse.lone-cr@1"

/** `hcl.parse.invalid-utf8@1` (lexer.rs:465): invalid UTF-8 byte decoding
 * makes formation FatalFormationFailure (RFC 0014 §2-§3). */
const val HCL_PARSE_INVALID_UTF8 = "hcl.parse.invalid-utf8@1"

/** `hcl.parse.identifier@1` (lexer.rs:467): an identifier violates
 * `Identifier = ID_Start (ID_Continue | "-")*` (RFC 0014 §4.1). */
const val HCL_PARSE_IDENTIFIER = "hcl.parse.identifier@1"

/** `hcl.parse.invalid-number@1` (lexer.rs:469): a numeric literal violates
 * the decimal-only rule (RFC 0014 §4.1). */
const val HCL_PARSE_INVALID_NUMBER = "hcl.parse.invalid-number@1"

/** `hcl.parse.invalid-character@1` (lexer.rs:471): an unexpected character
 * makes formation Recovered (RFC 0014 §2). */
const val HCL_PARSE_INVALID_CHARACTER = "hcl.parse.invalid-character@1"

/** `hcl.parse.invalid-escape@1` (lexer.rs:473): an invalid template escape
 * sequence (RFC 0014 §4.4). */
const val HCL_PARSE_INVALID_ESCAPE = "hcl.parse.invalid-escape@1"

/** `hcl.parse.unterminated-comment@1` (lexer.rs:475): an inline comment
 * without its closing star-slash terminator. */
const val HCL_PARSE_UNTERMINATED_COMMENT = "hcl.parse.unterminated-comment@1"

/** `hcl.parse.unterminated-string@1` (lexer.rs:477): a quoted template
 * without a closing quote; the error region extends to end of line (RFC
 * 0014 §3). */
const val HCL_PARSE_UNTERMINATED_STRING = "hcl.parse.unterminated-string@1"

/** `hcl.parse.unterminated-interpolation@1` (lexer.rs:479): an interpolation
 * without its closing brace; the error region covers the remainder of the
 * template (RFC 0014 §3). */
const val HCL_PARSE_UNTERMINATED_INTERPOLATION = "hcl.parse.unterminated-interpolation@1"

/** `hcl.parse.unterminated-directive@1` (lexer.rs:481): a directive without
 * its closing brace. */
const val HCL_PARSE_UNTERMINATED_DIRECTIVE = "hcl.parse.unterminated-directive@1"

/** `hcl.parse.unterminated-heredoc@1` (lexer.rs:483): a heredoc without its
 * closing marker; the error region extends to end of file within the
 * heredoc size limit (RFC 0014 §3). */
const val HCL_PARSE_UNTERMINATED_HEREDOC = "hcl.parse.unterminated-heredoc@1"

/** `hcl.parse.heredoc-marker@1` (lexer.rs:486): a heredoc introducer without
 * a valid bare identifier marker; the quoted-marker form is Recovered (RFC
 * 0014 §4.5). */
const val HCL_PARSE_HEREDOC_MARKER = "hcl.parse.heredoc-marker@1"

/** `hcl.parse.item@1` (parser.rs:80): a body item failed to parse. */
const val HCL_PARSE_ITEM = "hcl.parse.item@1"

/** `hcl.parse.attribute@1` (parser.rs:82): an attribute failed to parse. */
const val HCL_PARSE_ATTRIBUTE = "hcl.parse.attribute@1"

/** `hcl.parse.block@1` (parser.rs:84): a block failed to parse (for example
 * an unterminated block body, hcl-v1.json hcl.query.error-regions). */
const val HCL_PARSE_BLOCK = "hcl.parse.block@1"

/** `hcl.parse.label@1` (parser.rs:86): a block label failed to parse. */
const val HCL_PARSE_LABEL = "hcl.parse.label@1"

/** `hcl.parse.expression@1` (parser.rs:88): an expression failed to parse;
 * the error region ends at end of line unless a delimiter extends it (RFC
 * 0014 §3). */
const val HCL_PARSE_EXPRESSION = "hcl.parse.expression@1"

/** `hcl.parse.directive@1` (parser.rs:90): a template directive failed to
 * parse. */
const val HCL_PARSE_DIRECTIVE = "hcl.parse.directive@1"

/** `hcl.parse.newline@1` (parser.rs:92): a required newline terminator was
 * missing (for example `a = 1.` then a digit, hcl-v1.json
 * hcl.native-formation.number-matrix). */
const val HCL_PARSE_NEWLINE = "hcl.parse.newline@1"

/** `hcl.parse.separator@1` (parser.rs:94): a required separator was missing. */
const val HCL_PARSE_SEPARATOR = "hcl.parse.separator@1"

/** `hcl.parse.duplicate-attribute@1` (parser.rs:97): a second attribute with
 * the same name in one body; formation is Recovered, the duplicate remains
 * an inspectable proven syntax piece, never a native attribute (RFC 0014
 * §3). */
const val HCL_PARSE_DUPLICATE_ATTRIBUTE = "hcl.parse.duplicate-attribute@1"

/** `hcl.parse.encoding@1` (lib.rs:301): the caller-selected encoding
 * contradicts the UTF-8-only source contract; fatal before any byte is read
 * (RFC 0014 §2). */
const val HCL_PARSE_ENCODING = "hcl.parse.encoding@1"

/** `hcl.parse.internal@1` (parser.rs:2616): an internal invariant failure. */
const val HCL_PARSE_INTERNAL = "hcl.parse.internal@1"

/** `hcl.parse.coverage@1` (parser.rs:2627): exhaustive source coverage could
 * not be constructed; fatal (RFC 0014 §3). */
const val HCL_PARSE_COVERAGE = "hcl.parse.coverage@1"

/** `hcl.parse.coordinates@1` (parser.rs:2637): impossible source
 * coordinates; fatal (RFC 0014 §3). */
const val HCL_PARSE_COORDINATES = "hcl.parse.coordinates@1"

/** `hcl.tfvars.block-not-allowed@1` (document.rs:48): a block at the top
 * level of a tfvars document; formation is Recovered, the block remains a
 * native item (RFC 0014 §5). */
const val HCL_TFVARS_BLOCK_NOT_ALLOWED = "hcl.tfvars.block-not-allowed@1"

/** `hcl.limit.offset-overflow@1` (parser.rs:2592): coordinate arithmetic
 * exceeded the host representation; fatal. */
const val HCL_LIMIT_OFFSET_OVERFLOW = "hcl.limit.offset-overflow@1"

/** `hcl.limit.expression-depth@1` (parser.rs:4074): the expression parse
 * recursion budget was exceeded; fatal. */
const val HCL_LIMIT_EXPRESSION_DEPTH = "hcl.limit.expression-depth@1"

/** `hcl.limit.body-depth@1` (parser.rs:4101): the body nesting depth was
 * exceeded; fatal. */
const val HCL_LIMIT_BODY_DEPTH = "hcl.limit.body-depth@1"

/** `hcl.limit.template-depth@1` (parser.rs:4217): the template nesting depth
 * was exceeded; fatal. */
const val HCL_LIMIT_TEMPLATE_DEPTH = "hcl.limit.template-depth@1"

/** `hcl.limit.attribute-count@1` (parser.rs:4237): per-body attribute count
 * exceeded; fatal. */
const val HCL_LIMIT_ATTRIBUTE_COUNT = "hcl.limit.attribute-count@1"

/** `hcl.limit.block-count@1` (parser.rs:4246): per-body block count
 * exceeded; fatal. */
const val HCL_LIMIT_BLOCK_COUNT = "hcl.limit.block-count@1"

/** `hcl.limit.body-item-count@1` (parser.rs:4255): per-body item count
 * exceeded; fatal. */
const val HCL_LIMIT_BODY_ITEM_COUNT = "hcl.limit.body-item-count@1"

/** `hcl.limit.label-count@1` (parser.rs:4264): per-block label count
 * exceeded; fatal. */
const val HCL_LIMIT_LABEL_COUNT = "hcl.limit.label-count@1"

/** `hcl.limit.number-digits@1` (parser.rs:4273): the canonical-decimal digit
 * budget was exceeded; fatal. */
const val HCL_LIMIT_NUMBER_DIGITS = "hcl.limit.number-digits@1"

/** `hcl.limit.tuple-elements@1` (parser.rs:4294): tuple element count
 * exceeded; fatal. */
const val HCL_LIMIT_TUPLE_ELEMENTS = "hcl.limit.tuple-elements@1"

/** `hcl.limit.object-entries@1` (parser.rs:4303): object entry count
 * exceeded; fatal. */
const val HCL_LIMIT_OBJECT_ENTRIES = "hcl.limit.object-entries@1"

/** `hcl.limit.for-extent@1` (parser.rs:4312): for-expression extent
 * exceeded; fatal. */
const val HCL_LIMIT_FOR_EXTENT = "hcl.limit.for-extent@1"

/** `hcl.limit.recovery-regions@1` (parser.rs:4321): recovery region count
 * exceeded; fatal. */
const val HCL_LIMIT_RECOVERY_REGIONS = "hcl.limit.recovery-regions@1"

/** `hcl.limit.error-regions@1` (parser.rs:4330): error region count
 * exceeded; fatal. */
const val HCL_LIMIT_ERROR_REGIONS = "hcl.limit.error-regions@1"

/** `hcl.limit.identifier-len@1` (lexer.rs:2674): identifier byte length
 * exceeded; fatal. */
const val HCL_LIMIT_IDENTIFIER_LEN = "hcl.limit.identifier-len@1"

/** `hcl.limit.string-len@1` (lexer.rs:3163): quoted-template byte length
 * exceeded; fatal. */
const val HCL_LIMIT_STRING_LEN = "hcl.limit.string-len@1"

/** `hcl.limit.template-len@1` (lexer.rs:3168): template byte length
 * exceeded; fatal. */
const val HCL_LIMIT_TEMPLATE_LEN = "hcl.limit.template-len@1"

/** `hcl.limit.template-interpolations@1` (lexer.rs:3177): interpolation or
 * directive count in one template exceeded; fatal. */
const val HCL_LIMIT_TEMPLATE_INTERPOLATIONS = "hcl.limit.template-interpolations@1"

/** `hcl.limit.heredoc-lines@1` (lexer.rs:3430): heredoc line count exceeded;
 * fatal. */
const val HCL_LIMIT_HEREDOC_LINES = "hcl.limit.heredoc-lines@1"

/** `hcl.limit.heredoc-bytes@1` (lexer.rs:3439): heredoc byte count exceeded;
 * fatal. */
const val HCL_LIMIT_HEREDOC_BYTES = "hcl.limit.heredoc-bytes@1"

/** `hcl.limit.token-count@1` (lexer.rs:3651): token count exceeded; fatal. */
const val HCL_LIMIT_TOKEN_COUNT = "hcl.limit.token-count@1"

/** `hcl.limit.syntax-pieces@1` (lexer.rs:3660): lossless syntax piece count
 * exceeded; fatal. */
const val HCL_LIMIT_SYNTAX_PIECES = "hcl.limit.syntax-pieces@1"

/** `hcl.projection.incomplete-document@1` (projection.rs:470): a Recovered
 * document never projects (RFC 0014 §8.2). */
const val HCL_PROJECTION_INCOMPLETE_DOCUMENT = "hcl.projection.incomplete-document@1"

/** `hcl.projection.non-literal-expression@1` (projection.rs:471): a derived
 * expression has no default rendering; projection fails atomically unless
 * the explicit ProjectExpression policy is supplied (RFC 0014 §8.2). */
const val HCL_PROJECTION_NON_LITERAL_EXPRESSION = "hcl.projection.non-literal-expression@1"

/** `hcl.projection.unrepresentable@1` (projection.rs:472): a native fact
 * (for example an object key form) cannot enter the record (RFC 0014 §8.2). */
const val HCL_PROJECTION_UNREPRESENTABLE = "hcl.projection.unrepresentable@1"

/** `hcl.projection.resource-limit@1` (projection.rs:473): a configured
 * projection limit was reached; no partial output exists (hard gate 4). */
const val HCL_PROJECTION_RESOURCE_LIMIT = "hcl.projection.resource-limit@1"

/** `hcl.projection.core-invariant@1` (projection.rs:474): an internal
 * invariant failure of the record codec. */
const val HCL_PROJECTION_CORE_INVARIANT = "hcl.projection.core-invariant@1"

/** `hcl.materialization.unrepresentable@1` (materialization.rs:129): a
 * record cannot be expressed under the promised profile — the tfvars block
 * restriction, invalid names, duplicate attributes, non-finite reals, or
 * unexpressible shapes (RFC 0014 §9). */
const val HCL_MATERIALIZATION_UNREPRESENTABLE = "hcl.materialization.unrepresentable@1"

/** `hcl.materialization.resource-limit@1` (materialization.rs:130): a
 * configured materialization limit was reached. */
const val HCL_MATERIALIZATION_RESOURCE_LIMIT = "hcl.materialization.resource-limit@1"

/** `hcl.edit.duplicate-attribute@1` (edit.rs:604): an insertion would create
 * a duplicate attribute in one body. */
const val HCL_EDIT_DUPLICATE_ATTRIBUTE = "hcl.edit.duplicate-attribute@1"

/** `hcl.edit.block-in-tfvars@1` (edit.rs:605): a block operation under the
 * tfvars profile, which does not publish the block operations (RFC 0014
 * §10). */
const val HCL_EDIT_BLOCK_IN_TFVARS = "hcl.edit.block-in-tfvars@1"

/** `hcl.edit.unrepresentable@1` (edit.rs:607): an edit value cannot be
 * rendered — including every derived-expression value, which every commit
 * refuses explicitly (RFC 0014 §10, §14). */
const val HCL_EDIT_UNREPRESENTABLE = "hcl.edit.unrepresentable@1"

/** `hcl.query.type-mismatch@1` (query.rs:803): a typed literal accessor
 * found a literal of a different type; never a null, empty, or converted
 * result (RFC 0014 §7.1). */
const val HCL_QUERY_TYPE_MISMATCH = "hcl.query.type-mismatch@1"

/** `hcl.query.non-literal@1` (query.rs:802): a typed literal accessor found
 * a non-literal expression (RFC 0014 §7.1). */
const val HCL_QUERY_NON_LITERAL = "hcl.query.non-literal@1"

/** The frozen core invalid-UTF-8 source code used by fatal formation
 * (consema-document lib.rs:658-672; the toml family precedent
 * toml/Errors.kt:66-70). */
const val CORE_SOURCE_INVALID_UTF8 = "core.source.invalid-utf8@1"

/** The frozen core parse resource-limit code used by the common source
 * bound (consema-document lib.rs:771-791; RFC 0016 §5.1). */
const val CORE_PARSE_RESOURCE_LIMIT = "core.parse.resource-limit@1"

/**
 * One ordered formation/query/projection/edit diagnostic with a
 * snapshot-bound primary byte span (RFC 0014 §11). The [code], [category],
 * [severity], and [occurrence] are the frozen language-neutral facts; human
 * wording never participates in conformance comparison.
 */
data class HclDiagnostic(
    /** The frozen `hcl.*@1` (or core) code. */
    val code: String,
    /** The diagnostic category. */
    val category: DiagnosticCategory,
    /** The presentation severity. */
    val severity: Severity,
    /** Inclusive start byte of the primary span over the raw source, or
     * null when no span applies. */
    val startByte: Int?,
    /** Exclusive end byte of the primary span, or null. */
    val endByte: Int?,
    /** Deterministic stable arguments. */
    val arguments: Map<String, String>,
    /** Stable note texts. */
    val notes: List<String>,
    /** The deterministic occurrence ordinal. */
    val occurrence: Long,
) {
    /**
     * Maps to the transferable `core.diagnostic@1` record with a
     * caller-supplied stable source identity (RFC 0014 §11: when HCL
     * diagnostics are externalized through the protocol they follow RFC
     * 0011's error-code classification rules; the registry-bound validation
     * of the protocol layer applies, Diagnostic.kt:109-132).
     */
    fun toProtocolDiagnostic(
        sourceId: String,
        registry: ErrorCodeRegistry,
    ): Diagnostic {
        val primary = if (startByte == null || endByte == null) {
            null
        } else {
            SourceLocation.of(
                sourceId,
                startByte.toLong().toULong(),
                endByte.toLong().toULong(),
            )
        }
        return Diagnostic.of(
            code = code,
            category = category,
            severity = severity,
            primary = primary,
            related = emptyList(),
            arguments = arguments,
            notes = notes,
            fixes = emptyList(),
            occurrence = occurrence.toULong(),
            registry = registry,
        )
    }
}

/**
 * The typed fatal formation failure; no Document exists (RFC 0014 §3;
 * FatalFormationFailure). The stable [code] is the first diagnostic's frozen
 * registered code.
 */
class HclFormationException(
    /** Ordered diagnostics explaining why no Document exists. */
    val diagnostics: List<HclDiagnostic>,
) : Exception("hcl formation: ${diagnostics.firstOrNull()?.code}") {
    /** The frozen registered code of the first diagnostic. */
    val code: String
        get() = diagnostics.first().code
}

/** Builds one limit-failure diagnostic with the frozen `hcl.limit.*@1` code
 * and the limit/name/observed argument convention (RFC 0014 §11). */
internal fun hclLimitDiagnostic(
    code: String,
    name: String,
    observed: Int,
    limit: Int,
): HclDiagnostic = HclDiagnostic(
    code = code,
    category = DiagnosticCategory.Resource,
    severity = Severity.Error,
    startByte = null,
    endByte = null,
    arguments = mapOf(
        "limit" to limit.toString(),
        "name" to name,
        "observed" to observed.toString(),
    ),
    notes = emptyList(),
    occurrence = 0,
)

/** Builds the invalid-UTF-8 fatal diagnostic (RFC 0014 §2; primary at the
 * valid prefix boundary). */
internal fun hclInvalidUtf8Diagnostic(validUpTo: Int): HclDiagnostic = HclDiagnostic(
    code = HCL_PARSE_INVALID_UTF8,
    category = DiagnosticCategory.Lexical,
    severity = Severity.Error,
    startByte = validUpTo,
    endByte = validUpTo,
    arguments = emptyMap(),
    notes = emptyList(),
    occurrence = 0,
)

/** Builds one source-contract Recovered diagnostic (BOM / lone CR). */
internal fun hclSourceDiagnostic(code: String, startByte: Int, endByte: Int): HclDiagnostic =
    HclDiagnostic(
        code = code,
        category = DiagnosticCategory.Lexical,
        severity = Severity.Error,
        startByte = startByte,
        endByte = endByte,
        arguments = emptyMap(),
        notes = emptyList(),
        occurrence = 0,
    )

/**
 * Stable typed HCL access failure (the HclAccessError of the Rust surface,
 * consema-hcl/src/lib.rs:262-270 precedent). The [name] spellings are the
 * language-neutral comparison facts; these names are NOT registered error
 * codes.
 */
enum class HclAccessErrorKind {
    /** NodeRef belongs to another snapshot. */
    WrongSnapshot,

    /** NodeRef role cannot be used by this operation. */
    WrongRole,

    /** Index is not present in this snapshot. */
    UnknownNode,
}

/** The typed HCL access failure. */
class HclAccessException(val kind: HclAccessErrorKind) :
    Exception("hcl access: ${kind.name}")

/**
 * The typed HCL query failure carrying a frozen family code (RFC 0014 §11:
 * the `hcl.query.*@1` codes are registered by the RFC and never enter the
 * `consema-protocol` core registry; the [kind] mirrors the protocol
 * classification for externalization).
 */
class HclQueryException(
    /** The frozen `hcl.query.*@1` code. */
    val code: String,
    /** The protocol failure classification. */
    val kind: QueryFailureKind,
) : Exception("hcl query: $code")
