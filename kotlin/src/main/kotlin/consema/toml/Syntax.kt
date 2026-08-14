// The closed TOML v1 lossless syntax-piece classification.
//
// Data authority:
//   - https://github.com/consema/consema-rs/blob/main/consema-toml/src/lib.rs:41-109 (TomlSyntaxKind, as_str,
//     from_name) pins the twelve kind spellings; the same spellings are the
//     argument vocabulary of `toml.syntax-kind-is@1`
//     (kotlin/src/main/kotlin/consema/protocol/QueryValidate.kt:911-916 transcribes them).
//   - conformance/vectors/syntax-query-v1.json cases syntax.toml.*
//     (lines 54-99) pin the wire behavior (kind names in matches, ordinal
//     ordering, "TomlSyntaxPiece" match role).
//   - consema-go/go/toml is a cross-reference only.
//
// Kotlin-idiomatic design: an enum with the exact frozen spellings as entry
// names (so `kind.name` IS the stable kind name) plus the Rust `as_str`/
// `from_name` surface as explicit functions.

package consema.toml

/** Closed TOML v1 lossless syntax-piece classification
 * (consema-toml/src/lib.rs:41-68). */
enum class TomlSyntaxKind {
    /** Horizontal whitespace. */
    Whitespace,

    /** LF, CRLF, or invalid bare CR retained for formation diagnostics. */
    Newline,

    /** `#` comment excluding its newline. */
    Comment,

    /** Basic or literal string token, including multiline forms. */
    String,

    /** Bare key or value fragment. */
    Bare,

    /** `=`. */
    Equals,

    /** `[`. */
    LeftBracket,

    /** `]`. */
    RightBracket,

    /** `{`. */
    LeftBrace,

    /** `}`. */
    RightBrace,

    /** `,`. */
    Comma,

    /** `.`. */
    Dot,
    ;

    /** Stable query and protocol name (lib.rs:70-88). */
    fun asStr(): String = name

    companion object {
        /** Resolves one exact stable kind name (lib.rs:91-108). */
        fun fromName(name: String): TomlSyntaxKind? =
            entries.firstOrNull { it.name == name }
    }
}
