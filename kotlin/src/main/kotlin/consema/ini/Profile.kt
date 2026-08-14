// The frozen INI-family language profiles and their closed vocabularies.
//
// Data authority (language-neutral sources first):
//   - RFC 0009 §1 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md): Consema
//     publishes exactly three independent profiles `ini.portable@1`,
//     `ini.windows@1`, `ini.python-configparser@1`; the caller selects one
//     profile before formation; there is no auto-detection.
//   - RFC 0009 §8 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md): the lossless Document
//     retains value states Missing | Empty | Present, quote facts, and
//     duplicate/case-collision groups; the snapshot-bound handles are named
//     IniDocument / IniPhysicalLine / IniLogicalLine / IniSection /
//     IniDefaultSection / IniEntry / IniErrorLine.
//   - conformance/vectors/ini-v1.json pins the profile spellings and the
//     syntax-kind names the vectors assert (case.formation.*, query.*).
//   - https://github.com/consema/consema-rs/blob/main/consema-ini/src/lib.rs (IniProfile and its ProfileId),
//     lib.rs (IniSyntaxKind and the exact as_str names), lib.rs
//     228 (IniValueState, IniQuoteStyle, IniLogicalLineKind), lib.rs
//     (IniEncodingSelection). https://github.com/consema/consema-rs/blob/main/consema-ini/src/parser.rs pins the
//     encoding-request construction.
//   - RFC 0009 §3.2 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md) pins the mandatory v1
//     Windows code-page set: 874, 932, 936, 949, 950, 1250 through 1258, and
//     65001; no-BOM bytes never imply the machine's active code page.
//   - consema-go/go/ini/profile.go and consema-go/go/ini/parser.go are cross-references only.
//
// Kotlin-idiomatic design (NOT a translation): the profile is a closed enum;
// the syntax-kind names and value-state spellings ARE the language-neutral
// query vocabulary (RFC 0009 §9), so `asStr()` is the frozen spelling; the
// Windows code page is a validated value class so an invalid page number can
// never be constructed.

package consema.ini

import consema.document.ProfileId

/** Frozen INI formation profile (lib.rs). */
enum class IniProfile {
    /** Conservative ASCII exchange subset (RFC 0009 §5). */
    PortableV1,

    /** Deterministic Windows profile-string file surface (RFC 0009 §6). */
    WindowsV1,

    /** Python 3.14 ConfigParser default formation surface without
     * evaluation (RFC 0009 §7). */
    PythonConfigParserV1,
    ;

    /** Stable profile identifier (lib.rs). */
    fun id(): ProfileId =
        when (this) {
            PortableV1 -> ProfileId("ini.portable", 1)
            WindowsV1 -> ProfileId("ini.windows", 1)
            PythonConfigParserV1 -> ProfileId("ini.python-configparser", 1)
        }
}

/**
 * Closed INI lossless syntax-piece classification (lib.rs). The enum
 * order is the Rust declaration order; the query/protocol vocabulary is
 * [asStr] (lib.rs), which is byte-identical to the vector spellings.
 */
enum class IniSyntaxKind {
    /** Unicode byte-order mark. */
    Bom,

    /** Horizontal whitespace. */
    Whitespace,

    /** LF or CRLF. */
    LineBreak,

    /** Prefix comment marker. */
    CommentMarker,

    /** Comment payload. */
    CommentText,

    /** Opening section bracket. */
    SectionOpen,

    /** Section name text. */
    SectionName,

    /** Closing section bracket. */
    SectionClose,

    /** Entry key text. */
    EntryKey,

    /** Entry delimiter. */
    Delimiter,

    /** Value quote. */
    Quote,

    /** Entry value text. */
    EntryValue,

    /** Skipped indentation on a continuation line. */
    ContinuationMarker,

    /** Profile-invalid or malformed source range. */
    ErrorRegion,
    ;

    /** Stable query and protocol name (lib.rs). */
    fun asStr(): String =
        when (this) {
            Bom -> "Bom"
            Whitespace -> "Whitespace"
            LineBreak -> "LineBreak"
            CommentMarker -> "CommentMarker"
            CommentText -> "CommentText"
            SectionOpen -> "SectionOpen"
            SectionName -> "SectionName"
            SectionClose -> "SectionClose"
            EntryKey -> "EntryKey"
            Delimiter -> "Delimiter"
            Quote -> "Quote"
            EntryValue -> "EntryValue"
            ContinuationMarker -> "ContinuationMarker"
            ErrorRegion -> "ErrorRegion"
        }

    companion object {
        /** Resolves one exact stable kind name (lib.rs). */
        fun fromName(name: String): IniSyntaxKind? =
            entries.firstOrNull { it.asStr() == name }
    }
}

/** Native value-presence fact (lib.rs). */
enum class IniValueState {
    /** No delimiter/value was present; only recovered error records use
     * this in v1. */
    Missing,

    /** A delimiter was present with empty semantic content. */
    Empty,

    /** Non-empty semantic string content. */
    Present,
}

/** Profile-recognized outer quote style (lib.rs). */
enum class IniQuoteStyle {
    /** No semantic outer quotes. */
    None,

    /** Exact single quotes under the Windows profile. */
    Single,

    /** Exact double quotes under the Windows profile. */
    Double,
}

/** Kind of one logical INI record (lib.rs). */
enum class IniLogicalLineKind {
    /** Section header record. */
    Section,

    /** Entry and any continuation lines. */
    Entry,

    /** Recovered malformed record. */
    Error,
}

/**
 * Explicit source-encoding selection; no host locale is consulted
 * (lib.rs). [ProfileDefault] applies only the selected profile's
 * frozen default and BOM rules; [Explicit] selects one exact encoding.
 */
sealed class IniEncodingSelection {
    /** Apply only the selected profile's frozen default and BOM rules. */
    data object ProfileDefault : IniEncodingSelection()

    /** Use one caller-selected source encoding. */
    data class Explicit(val encoding: IniSourceEncoding) : IniEncodingSelection()
}

/**
 * Closed INI source-encoding set: the source-v1 encodings plus the
 * mandatory Windows code-page set (RFC 0009 §3.2; parser.rs).
 *
 * The consema.document v1 `SourceEncoding` cannot express Windows code
 * pages (the source-v2 extension belongs to the L2 properties milestone,
 * kotlin/src/main/kotlin/consema/document/Encoding.kt), so the INI family owns its
 * encoding vocabulary here and decodes code pages itself
 * (kotlin/src/main/kotlin/consema/ini/Source.kt).
 */
sealed class IniSourceEncoding {
    /** Unicode UTF-8. */
    data object Utf8 : IniSourceEncoding()

    /** Unicode UTF-16 with little-endian code units. */
    data object Utf16Le : IniSourceEncoding()

    /** Unicode UTF-16 with big-endian code units. */
    data object Utf16Be : IniSourceEncoding()

    /** ISO-8859-1 byte-to-scalar mapping; not Windows-1252. */
    data object Latin1 : IniSourceEncoding()

    /** One published Windows code page (RFC 0009 §3.2). */
    data class WindowsCodePage(val codePage: IniWindowsCodePage) : IniSourceEncoding()

    /** Stable spelling used by the conformance vectors: "Utf8",
     * "Utf16Le", "Utf16Be", "Latin1", "WindowsCodePage(1252)". */
    fun asStr(): String =
        when (this) {
            Utf8 -> "Utf8"
            Utf16Le -> "Utf16Le"
            Utf16Be -> "Utf16Be"
            Latin1 -> "Latin1"
            is WindowsCodePage -> "WindowsCodePage(${codePage.number})"
        }
}

/**
 * One published Windows code page (RFC 0009 §3.2: 874, 932, 936, 949, 950,
 * 1250-1258, 65001). Construction rejects every unpublished number.
 */
class IniWindowsCodePage private constructor(val number: Int) {
    companion object {
        /** The frozen mandatory v1 code-page set (RFC 0009 §3.2). */
        val published = setOf(874, 932, 936, 949, 950) + (1250..1258) + 65001

        /** Validates one code-page number (RFC 0009 §3.2); returns null for
         * an unpublished number. */
        fun fromNumber(number: Int): IniWindowsCodePage? =
            if (number in published) IniWindowsCodePage(number) else null
    }

    override fun equals(other: Any?): Boolean =
        other is IniWindowsCodePage && number == other.number

    override fun hashCode(): Int = number

    override fun toString(): String = "IniWindowsCodePage($number)"
}
