// Typed failures of plist-family formation and access, the internal
// diagnostic factory, and the frozen plist-family diagnostic codes.
//
// Data authority:
//   - RFC 0013 §12 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md:716-753):
//     stable diagnostics cover source/declaration conflicts, DOCTYPE
//     mismatch, element/attribute violations, integer and real grammar, date
//     grammar and calendar validity, base64, XML reference errors, binary
//     header/trailer/offset integrity, unknown or excluded markers, non-
//     string dictionary keys, overflow, every limit, projection
//     unrepresentability, conversion representability, and edit conflicts.
//     Codes follow the plist.<phase>.<name>@1 pattern; `plist.parse.*@1`
//     covers XML grammar diagnostics, `plist.binary.*@1` binary structure
//     integrity, and `plist.limit.*@1` resource limits. Per RFC 0013 §12
//     (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md:748-753) plist-family diagnostics do NOT enter
//     the consema-protocol core error registry, which covers only core/
//     protocol and line-format contract codes (RFC 0011 §10); the core codes
//     referenced below are registered in https://github.com/consema/consema-rs/blob/main/consema-protocol/src/
//     error_registry.rs (core.parse.resource-limit@1 at error_registry.rs:
//     38-43; core.edit.* at error_registry.rs:507-543; core.materialization.*
//     at error_registry.rs:556-604) and transcribed in
//     kotlin/src/main/kotlin/consema/protocol/ErrorRegistry.kt (178, 262-270, 280-288).
//   - https://github.com/consema/consema-rs/blob/main/consema-plist/src/parser_xml.rs and parser_binary.rs are the
//     byte-arbitration sources for every plist.parse.* / plist.binary.* code
//     (each code is cited at its emission site below); projection.rs:393-401
//     pins the plist.projection.* codes; edit.rs:442-453 pins the edit codes;
//     materialization.rs:81-88 pins the plist.materialization.* codes;
//     document.rs:252-289, 718 pins the conversion codes;
//     https://github.com/consema/consema-rs/blob/main/consema-conformance/src/plist_v1.rs:1143-1153 pins the
//     plist.query.* codes.
//   - RFC 0016 §6 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md:194-200): SDK errors
//     carry the stable registered code; error text is human presentation only.
//
// Kotlin-idiomatic design: the family carries its own immutable diagnostic
// record because the protocol `Diagnostic` primary location is wire-shaped
// (a caller-stable source ID is mandatory) while formation/projection/edit
// diagnostics are snapshot-bound byte spans; the record maps to the
// transferable `core.diagnostic@1` form once a caller supplies the stable
// source ID and an error-code registry. This follows the HCL family
// precedent (kotlin/src/main/kotlin/consema/hcl/Errors.kt:47-53) and the TOML family precedent
// (toml/Errors.kt), both of which keep family codes out of the core registry.
// Fatal formation failure is a typed exception carrying the frozen code
// (the established consema.core/consema.document style); the plist codes are
// frozen constants so tests and diagnostics can refer to them by name
// without inventing spellings.

package consema.plist

import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.ErrorCodeRegistry
import consema.protocol.Severity
import consema.protocol.SourceLocation

/** The frozen plist-family diagnostic codes (RFC 0013 §12; cited per code
 * below). These are NOT part of the core error registry (RFC 0013 §12,
 * https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md:748-753). */
object PlistCodes {
    // -- XML grammar (plist.parse.*@1; parser_xml.rs emission sites) --
    /** Declaration version is not exactly 1.0 (parser_xml.rs:821-828). */
    const val PARSE_DECLARATION_VERSION = "plist.parse.declaration-version@1"

    /** Declaration encoding contradicts the selected source encoding
     * (parser_xml.rs:854-864). */
    const val PARSE_DECLARATION_CONFLICT = "plist.parse.declaration-conflict@1"

    /** A processing instruction used the reserved `xml` target
     * (parser_xml.rs:934-941). */
    const val PARSE_PI_TARGET = "plist.parse.pi-target@1"

    /** DOCTYPE identifier does not match the frozen Apple plist identifier
     * (parser_xml.rs:1079; vector plist.xml-formation.doctype-violations). */
    const val PARSE_DOCTYPE = "plist.parse.doctype@1"

    /** DOCTYPE carries an internal subset (parser_xml.rs:1013-1018; vector
     * plist.xml-formation.doctype-violations). */
    const val PARSE_DOCTYPE_SUBSET = "plist.parse.doctype-subset@1"

    /** Root element missing or `version` attribute not exactly "1.0"
     * (parser_xml.rs:1303, 1389, 1435; vector plist.xml-formation.root-
     * contracts). */
    const val PARSE_ROOT_VERSION = "plist.parse.root-version@1"

    /** Root element carries an unexpected attribute (parser_xml.rs:1318;
     * vector plist.xml-formation.root-contracts). */
    const val PARSE_ROOT_ATTRIBUTE = "plist.parse.root-attribute@1"

    /** A non-root value element carries an attribute (parser_xml.rs:1320). */
    const val PARSE_ELEMENT_ATTRIBUTE = "plist.parse.element-attribute@1"

    /** Element name is prefixed or unknown (parser_xml.rs:1221; vector
     * plist.xml-formation.unknown-element). */
    const val PARSE_ELEMENT_NAME = "plist.parse.element-name@1"

    /** A `</dict>` closed while a key was pending (parser_xml.rs:1163, 1593,
     * 1614; vector plist.xml-formation.key-pair). */
    const val PARSE_DICT_MISSING_VALUE = "plist.parse.dict-missing-value@1"

    /** A value element appeared where a dict key was required
     * (parser_xml.rs:1192; vector plist.xml-formation.key-pair). */
    const val PARSE_DICT_KEY = "plist.parse.dict-key@1"

    /** A `<key>` appeared outside a dictionary (parser_xml.rs:1172). */
    const val PARSE_KEY_OUTSIDE_DICT = "plist.parse.key-outside-dict@1"

    /** Scalar text was malformed in a scalar element (parser_xml.rs:1209). */
    const val PARSE_SCALAR_CONTENT = "plist.parse.scalar-content@1"

    /** Empty `<integer/>`, `<real/>`, `<date/>`, or `<data/>` (parser_xml.rs:
     * 1656, 1681, 1706, 1732; vector plist.xml-formation.empty-value-matrix). */
    const val PARSE_EMPTY_VALUE = "plist.parse.empty-value@1"

    /** Integer text does not match the frozen decimal/hex grammar or the
     * signed 64-bit range (parser_xml.rs:1667; vector plist.xml-formation.
     * integer-matrix). */
    const val PARSE_INTEGER = "plist.parse.integer@1"

    /** Real text does not match the DTD grammar or the special spellings
     * (parser_xml.rs:1692). */
    const val PARSE_REAL = "plist.parse.real@1"

    /** Date text does not match `[-]YYYY-MM-DDTHH:MM:SSZ` or the calendar
     * fields are invalid (parser_xml.rs:1720; vector plist.xml-formation.
     * date-matrix). */
    const val PARSE_DATE = "plist.parse.date@1"

    /** Data content is not strict base64 with exact padding (parser_xml.rs:
     * 1757; vector plist.xml-formation.base64-matrix). */
    const val PARSE_DATA = "plist.parse.data@1"

    /** Non-whitespace text appeared outside a value element (parser_xml.rs:
     * 1808, 1860). */
    const val PARSE_TEXT_OUTSIDE_VALUE = "plist.parse.text-outside-value@1"

    /** `<true>`/`<false>` carried content (parser_xml.rs:1826, 1874). */
    const val PARSE_BOOLEAN_CONTENT = "plist.parse.boolean-content@1"

    /** A character reference decoded to an invalid XML 1.0 character
     * (parser_xml.rs:1960, 2023, 2033). */
    const val PARSE_REFERENCE = "plist.parse.reference@1"

    /** An entity other than the five predefined ones was used
     * (parser_xml.rs:2052). */
    const val PARSE_ENTITY = "plist.parse.entity@1"

    /** End tag name did not match the open tag (parser_xml.rs:1458). */
    const val PARSE_MISMATCHED_END_TAG = "plist.parse.mismatched-end-tag@1"

    /** An end tag had no matching open tag (parser_xml.rs:1482). */
    const val PARSE_EXTRA_END_TAG = "plist.parse.extra-end-tag@1"

    /** Trailing content after `</plist>` is not admitted trivia
     * (parser_xml.rs:2141; vector plist.xml-formation.trailing-content). */
    const val PARSE_WELL_FORMEDNESS = "plist.parse.well-formedness@1"

    /** An element was never closed (parser_xml.rs:2158). */
    const val PARSE_UNCLOSED_ELEMENT = "plist.parse.unclosed-element@1"

    /** No root element was found (parser_xml.rs:2178). */
    const val PARSE_MISSING_ROOT = "plist.parse.missing-root@1"

    /** The root holds zero or two value elements (parser_xml.rs:2188, 2210;
     * vector plist.xml-formation.root-contracts). */
    const val PARSE_ROOT_VALUE_COUNT = "plist.parse.root-value-count@1"

    // -- Binary structure (plist.binary.*@1; parser_binary.rs emission
    //    sites) --
    /** Source shorter than the 42-byte minimum (parser_binary.rs:529-540;
     * RFC 0013 §2.2). Fatal. */
    const val BINARY_MINIMUM_SIZE = "plist.binary.minimum-size@1"

    /** Header bytes are not exactly `bplist00` (parser_binary.rs:545-552;
     * vector plist.binary-formation.header-and-trailer). */
    const val BINARY_HEADER = "plist.binary.header@1"

    /** One trailer field violated a mandatory check (parser_binary.rs:
     * 783-915; vector plist.binary-formation.header-and-trailer). */
    const val BINARY_TRAILER = "plist.binary.trailer@1"

    /** One offset-table entry is missing or out of `[8, offsetTableOffset)`
     * (parser_binary.rs:941-962, 1021-1029, 1283-1291; vector plist.binary-
     * formation.offset-and-reference). */
    const val BINARY_OFFSET_TABLE = "plist.binary.offset-table@1"

    /** An object marker byte is unknown or excluded (parser_binary.rs:1108-
     * 1118; vector plist.binary-formation.rejected-markers). */
    const val BINARY_MARKER = "plist.binary.marker@1"

    /** An object's payload extent crosses the object-table end
     * (parser_binary.rs:1135-1146). */
    const val BINARY_EXTENT = "plist.binary.extent@1"

    /** An ASCII string payload contains a byte with the high bit set
     * (parser_binary.rs:1150-1165; vector plist.binary-formation.strings-
     * matrix). */
    const val BINARY_STRING = "plist.binary.string@1"

    /** A date payload is not finite (parser_binary.rs:1166-1176; vector
     * plist.binary-formation.value-integrity). */
    const val BINARY_DATE = "plist.binary.date@1"

    /** A UID payload exceeds 32 bits (parser_binary.rs:1177-1189; vector
     * plist.binary-formation.uid-matrix). */
    const val BINARY_UID = "plist.binary.uid@1"

    /** A container reference indexes a nonexistent object
     * (parser_binary.rs:1216-1223; vector plist.binary-formation.offset-and-
     * reference). */
    const val BINARY_REFERENCE = "plist.binary.reference@1"

    /** An extended-size position does not hold an integer marker, or its
     * count is out of range (parser_binary.rs:1294-1303; vector plist.binary-
     * formation.extended-size-and-cycle). */
    const val BINARY_EXTENDED_SIZE = "plist.binary.extended-size@1"

    /** A dictionary key reference targets a non-string object
     * (parser_binary.rs:1340-1350; vector plist.binary-formation.value-
     * integrity). */
    const val BINARY_NON_STRING_KEY = "plist.binary.non-string-key@1"

    /** The proven object graph revisits an open object (parser_binary.rs:
     * 659-662; vector plist.binary-formation.extended-size-and-cycle). */
    const val BINARY_CYCLE = "plist.binary.cycle@1"

    /** The top object lies outside the proven prefix (parser_binary.rs:618-
     * 625). */
    const val BINARY_UNPROVEN_TOP_OBJECT = "plist.binary.unproven-top-object@1"

    /** A proven object references an unproven object (parser_binary.rs:626-
     * 642). */
    const val BINARY_UNPROVEN_REFERENCE = "plist.binary.unproven-reference@1"

    /** Exhaustive region coverage could not be constructed
     * (parser_binary.rs:749-758). */
    const val BINARY_COVERAGE = "plist.binary.coverage@1"

    /** Checked size arithmetic overflowed (parser_binary.rs:1604). Fatal. */
    const val BINARY_OVERFLOW = "plist.binary.overflow@1"

    /** An internal invariant failed during binary formation
     * (parser_binary.rs:1614). Fatal. */
    const val BINARY_INTERNAL = "plist.binary.internal@1"

    /** The caller selected an encoding inconsistent with the profile
     * (https://github.com/consema/consema-rs/blob/main/consema-plist/src/lib.rs:246-257 for binary,
     * lib.rs:284-298 for XML). Fatal. */
    const val BINARY_ENCODING = "plist.binary.encoding@1"

    /** The caller selected an encoding inconsistent with the XML profile
     * (lib.rs:284-298). Fatal. */
    const val XML_ENCODING = "plist.xml.encoding@1"

    /** An unreachable internal state of XML formation (parser_xml.rs:2785-
     * 2792). Fatal. */
    const val XML_INTERNAL = "plist.xml.internal@1"

    /** Exhaustive source coverage could not be constructed (parser_xml.rs:
     * 2796-2803). Fatal. */
    const val XML_COVERAGE = "plist.xml.coverage@1"

    /** Impossible source coordinates (parser_xml.rs:2806-2813). Fatal. */
    const val XML_COORDINATES = "plist.xml.coordinates@1"

    // -- Query (plist.query.*@1; plist_v1.rs:1143-1153) --
    /** The typed accessor validated a value of the wrong native kind
     * (plist_v1.rs:1149; vector plist.query.typed-accessors). */
    const val QUERY_TYPE_MISMATCH = "plist.query.type-mismatch@1"

    // -- Projection (projection.rs:393-401) --
    /** Recovered documents cannot publish partial semantic values
     * (vector plist.projection.atomic-failures). */
    const val PROJECTION_INCOMPLETE_DOCUMENT = "plist.projection.incomplete-document@1"

    /** An unpaired-surrogate string cannot enter ordinary Unicode
     * projection (vector plist.projection.atomic-failures). */
    const val PROJECTION_UNPAIRED_SURROGATE = "plist.projection.unpaired-surrogate@1"

    /** A duplicate key collided under `Reject` (vector plist.projection.
     * require-object-policies). */
    const val PROJECTION_COLLISION = "plist.projection.collision@1"

    /** A native fact the target cannot represent (vector plist.projection.
     * require-object-policies). */
    const val PROJECTION_UNREPRESENTABLE = "plist.projection.unrepresentable@1"

    /** A configured projection limit was reached (projection.rs:400). */
    const val PROJECTION_RESOURCE_LIMIT = "plist.projection.resource-limit@1"

    /** A PortableValue construction invariant failed (projection.rs:401). */
    const val PROJECTION_CORE_INVARIANT = "plist.projection.core-invariant@1"

    // -- Materialization (materialization.rs:81-88) --
    /** A fractional-second date leaf requires an explicit truncation policy
     * (vector plist.materialization.fractional-date-policy). */
    const val MATERIALIZATION_FRACTIONAL_DATE = "plist.materialization.fractional-date@1"

    /** A value kind the target representation cannot express
     * (materialization.rs:85). */
    const val MATERIALIZATION_UNREPRESENTABLE = "plist.materialization.unrepresentable@1"

    /** A configured materialization limit was reached (materialization.rs:
     * 86). */
    const val MATERIALIZATION_RESOURCE_LIMIT = "plist.materialization.resource-limit@1"

    // -- Edit (edit.rs:442-453) --
    /** A UID value was inserted into or set on an XML document (vector
     * plist.edit.conflicts). */
    const val EDIT_UID_IN_XML = "plist.edit.uid-in-xml@1"

    /** A typed value or key cannot be expressed in the target representation
     * (edit.rs:450). */
    const val EDIT_UNREPRESENTABLE = "plist.edit.unrepresentable@1"

    // -- Conversion (document.rs:252-289, 718, 1292-1303) --
    /** A binary-only native fact blocks conversion to XML (vector
     * plist.conversion.uid-inexpressible-to-xml). */
    const val CONVERSION_INEXPRESSIBLE = "plist.conversion.inexpressible@1"

    /** Source and target representations are identical (document.rs:263-267). */
    const val CONVERSION_SAME_REPRESENTATION = "plist.conversion.same-representation@1"

    /** The source document is not complete with a provable native model
     * (document.rs:268-279). */
    const val CONVERSION_FORMATION = "plist.conversion.formation@1"

    /** The target reparse did not reproduce the promised native model
     * (document.rs:1303). */
    const val CONVERSION_REPARSE = "plist.conversion.reparse@1"

    /** An internal conversion invariant failed (document.rs:1292, 1297). */
    const val CONVERSION_INTERNAL = "plist.conversion.internal@1"

    /** A configured conversion node, event, or limit bound was exceeded
     * (document.rs:498-522, 565-572; RFC 0013 §12). */
    const val CONVERSION_RESOURCE_LIMIT = "plist.conversion.resource-limit@1"
}

/**
 * One ordered formation/query/projection/edit diagnostic with a
 * snapshot-bound primary byte span (RFC 0013 §12). The [code], [category],
 * [severity], and [occurrence] are the frozen language-neutral facts; human
 * wording never participates in conformance comparison.
 */
data class PlistDiagnostic(
    /** The frozen `plist.*@1` (or core) code. */
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
     * caller-supplied stable source identity (RFC 0013 §12: when plist
     * diagnostics are externalized through the protocol they follow RFC
     * 0011's error-code classification rules; the registry-bound validation
     * of the protocol layer applies, Diagnostic.kt:109-132). The family
     * codes themselves stay out of the core registry (RFC 0013 §12).
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
 * The fatal formation failure of the plist family. Exceeding a parse limit is
 * a ResourceLimit failure carrying the frozen limit code (RFC 0016 §5.1,
 * https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md:176); source-construction and encoding
 * conflicts carry their core.source.* codes (RFC 0003 §12). A fatal failure
 * returns no Document and no partial snapshot.
 */
class PlistFormationException(
    /** The frozen registered code of the failure. */
    val code: String,
    message: String,
    /** Stable limit name (plist.limit.<name>@1 / core.parse.resource-limit@1). */
    val name: String = "",
    /** Observed amount (RESOURCE_LIMIT). */
    val observed: Int? = null,
    /** Configured maximum (RESOURCE_LIMIT). */
    val limit: Int? = null,
    /** Wrapped source construction failure (SOURCE_*). */
    override val cause: Exception? = null,
) : Exception(message, cause)

/**
 * Builds a `plist.limit.<name>@1` fatal resource-limit failure (RFC 0013 §12;
 * parser_xml.rs:2831-2835, parser_binary.rs:1649-1653). The message carries
 * the limit name; a limit failure never masquerades as a Recovered or
 * Complete tree (hard gate 4).
 */
internal fun plistLimit(name: String, observed: Int, limit: Int): PlistFormationException =
    PlistFormationException(
        "plist.limit.$name@1",
        "plist parse: $name limit reached ($observed > $limit)",
        name = name,
        observed = observed,
        limit = limit,
    )

/** Builds a common resource-limit fatal failure (error_registry.rs:38-43). */
internal fun commonLimit(name: String, observed: Int, limit: Int): PlistFormationException =
    PlistFormationException(
        "core.parse.resource-limit@1",
        "plist parse: $name limit reached ($observed > $limit)",
        name = name,
        observed = observed,
        limit = limit,
    )

/**
 * Stable typed plist access failure. The [name] spellings are the language-
 * neutral comparison facts; these names are NOT registered error codes.
 */
enum class PlistAccessErrorKind {
    /** NodeRef belongs to another snapshot. */
    WrongSnapshot,

    /** NodeRef role cannot be used by this operation. */
    WrongRole,

    /** Index is not present in this snapshot. */
    UnknownNode,
}

/** The typed plist access failure. */
class PlistAccessException(val kind: PlistAccessErrorKind) :
    Exception("plist access: ${kind.name}")

/** Builds one syntax-family Recovered diagnostic with a raw byte span. */
internal fun plistSyntaxDiagnostic(
    code: String,
    category: DiagnosticCategory,
    startByte: Int?,
    endByte: Int?,
    arguments: Map<String, String> = emptyMap(),
    occurrence: Long = 0,
): PlistDiagnostic = PlistDiagnostic(
    code = code,
    category = category,
    severity = Severity.Error,
    startByte = startByte,
    endByte = endByte,
    arguments = arguments,
    notes = emptyList(),
    occurrence = occurrence,
)
