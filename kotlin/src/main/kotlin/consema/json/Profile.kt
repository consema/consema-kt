// The frozen JSON-family language profiles and lossless syntax-piece
// classification.
//
// Data authority (language-neutral sources first):
//   - RFC 0005 §1-§2 (https://github.com/consema/consema/blob/main/docs/rfcs/0005-json-family-production-v1.md):
//     the three profiles json.strict@1, jsonc.bounded@1, json5.standard@1;
//     JSON5 accepts exactly the Standard JSON5 1.0.0 grammar plus Consema
//     resource bounds.
//   - conformance/vectors/json-family-v2.json pins the profile spellings and
//     the syntax kinds the vectors assert (syntax_contains, kind-is).
//   - https://github.com/consema/consema-rs/blob/main/consema-json/src/lib.rs pins JsonProfile and
//     JsonSyntaxKind (the exact kind names "Bom".."ErrorRegion" at
//     lib.rs). consema-go/go/json/profile.go is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the profile is a closed enum;
// the syntax-kind names are the exact language-neutral spellings asserted by
// the query domain and the vectors, so `asStr()` IS the frozen spelling.

package consema.json

import consema.document.ProfileId

/** Frozen JSON-family language profile (lib.rs). */
enum class JsonProfile {
    /** RFC-style strict JSON plus the baseline duplicate/BOM diagnostics
     * (RFC 0005 §1). */
    StrictV1,

    /** Strict JSON plus comments, trailing commas, and an optional leading
     * BOM (RFC 0005 §1). */
    JsoncBoundedV1,

    /** Standard JSON5 1.0.0 plus bounded Consema resource behavior
     * (RFC 0005 §1). */
    Json5StandardV1,
    ;

    /** Immutable profile identifier (lib.rs). */
    fun id(): ProfileId =
        when (this) {
            StrictV1 -> ProfileId("json.strict", 1)
            JsoncBoundedV1 -> ProfileId("jsonc.bounded", 1)
            Json5StandardV1 -> ProfileId("json5.standard", 1)
        }

    /** Whether bounded comments and trailing commas are accepted
     * (lib.rs). */
    internal fun permitsJsoncExtensions(): Boolean =
        this == JsoncBoundedV1 || this == Json5StandardV1

    /** Whether the Standard JSON5 lexical surface is accepted
     * (lib.rs). */
    internal fun isJson5(): Boolean = this == Json5StandardV1
}

/**
 * Closed JSON-family lossless syntax-piece classification (lib.rs).
 * The enum order is the Rust declaration order; the wire/query vocabulary is
 * [asStr] (lib.rs), which is byte-identical to the vector spellings.
 */
enum class JsonSyntaxKind {
    /** Leading UTF-8 byte-order mark. */
    Bom,

    /** JSON whitespace. */
    Whitespace,

    /** `//` comment. */
    LineComment,

    /** Closed slash-star block comment (the star-slash terminator). */
    BlockComment,

    /** `{`. */
    LeftBrace,

    /** `}`. */
    RightBrace,

    /** `[`. */
    LeftBracket,

    /** `]`. */
    RightBracket,

    /** `:`. */
    Colon,

    /** `,`. */
    Comma,

    /** Complete string token. */
    String,

    /** Complete JSON5 IdentifierName token (RFC 0005 §4). */
    Identifier,

    /** Valid JSON number token. */
    Number,

    /** `true`. */
    True,

    /** `false`. */
    False,

    /** `null`. */
    Null,

    /** Bytes retained after bounded lexical recovery. */
    ErrorRegion,
    ;

    /** Stable query and protocol name (lib.rs). */
    fun asStr(): String =
        when (this) {
            Bom -> "Bom"
            Whitespace -> "Whitespace"
            LineComment -> "LineComment"
            BlockComment -> "BlockComment"
            LeftBrace -> "LeftBrace"
            RightBrace -> "RightBrace"
            LeftBracket -> "LeftBracket"
            RightBracket -> "RightBracket"
            Colon -> "Colon"
            Comma -> "Comma"
            String -> "String"
            Identifier -> "Identifier"
            Number -> "Number"
            True -> "True"
            False -> "False"
            Null -> "Null"
            ErrorRegion -> "ErrorRegion"
        }

    companion object {
        /** Resolves one exact stable kind name (lib.rs). */
        fun fromName(name: String): JsonSyntaxKind? =
            entries.firstOrNull { it.asStr() == name }
    }
}
