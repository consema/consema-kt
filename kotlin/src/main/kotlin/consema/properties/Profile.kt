// Frozen Java Properties formation profiles and lossless classifications.
//
// Data authority (language-neutral sources first):
//   - RFC 0010 §1 (https://github.com/consema/consema/blob/main/docs/rfcs/0010-java-properties-profiles-v1.md:14-35):
//     the two frozen profiles java-properties.reader@1 and
//     java-properties.latin1@1; the profile is always selected by the caller
//     and a `.properties` extension never chooses between them.
//   - conformance/vectors/java-properties-v1.json pins the profile spellings
//     (line 3) and the syntax-kind vocabulary of the lossless syntax query
//     domain (the syntax_kinds / syntax_contains facts of the query cases).
//   - consema-rs/consema-properties/src/lib.rs:33-50 (PropertiesProfile),
//     lib.rs:208-235 (PropertiesSyntaxKind), lib.rs:276-285
//     (PropertiesValueState), lib.rs:287-295 (PropertiesLogicalLineKind),
//     lib.rs:296-307 (PropertiesEscapeKind). consema-go/go/properties is a
//     cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the profile is a closed enum;
// the syntax-kind names are the exact language-neutral spellings asserted by
// the query domain and the vectors, so `asStr()` IS the frozen spelling.

package consema.properties

import consema.document.ProfileId

/** Frozen Java Properties formation profile (lib.rs:33-50). */
enum class PropertiesProfile {
    /** Character-source semantics corresponding to `Properties.load(Reader)`
     * (RFC 0010 §3.1). */
    ReaderV1,

    /** ISO-8859-1 byte semantics corresponding to
     * `Properties.load(InputStream)` (RFC 0010 §3.2). */
    Latin1V1,
    ;

    /** Stable profile identifier (lib.rs:41-50). */
    fun id(): ProfileId =
        when (this) {
            ReaderV1 -> ProfileId("java-properties.reader", 1)
            Latin1V1 -> ProfileId("java-properties.latin1", 1)
        }
}

/**
 * Closed Java Properties lossless syntax-piece classification (lib.rs:208-235).
 * The enum order is the Rust declaration order; the wire/query vocabulary is
 * [asStr] (lib.rs:237-274), which is byte-identical to the vector spellings.
 */
enum class PropertiesSyntaxKind {
    /** Unicode byte-order mark recognized by the Reader source contract. */
    Bom,

    /** Space, tab, or form feed. */
    Whitespace,

    /** LF, CR, or CRLF. */
    LineBreak,

    /** `#` or `!` starting a comment natural line. */
    CommentMarker,

    /** Comment payload. */
    CommentText,

    /** Raw property key content. */
    Key,

    /** Whitespace and optional `=` or `:` between key and value. */
    Separator,

    /** Raw property element content. */
    Value,

    /** Backslash beginning a normal escape. */
    EscapeMarker,

    /** Named, Unicode, or dropped-backslash escape body. */
    EscapeBody,

    /** Backslash consumed by natural-line continuation. */
    ContinuationMarker,

    /** Malformed source retained through recovery. */
    ErrorRegion,
    ;

    /** Stable query and protocol name (lib.rs:237-254). */
    fun asStr(): String =
        when (this) {
            Bom -> "Bom"
            Whitespace -> "Whitespace"
            LineBreak -> "LineBreak"
            CommentMarker -> "CommentMarker"
            CommentText -> "CommentText"
            Key -> "Key"
            Separator -> "Separator"
            Value -> "Value"
            EscapeMarker -> "EscapeMarker"
            EscapeBody -> "EscapeBody"
            ContinuationMarker -> "ContinuationMarker"
            ErrorRegion -> "ErrorRegion"
        }

    companion object {
        /** Resolves one exact stable kind name (lib.rs:257-273). */
        fun fromName(name: String): PropertiesSyntaxKind? =
            entries.firstOrNull { it.asStr() == name }
    }
}

/** Semantic empty/present state with exact separator provenance
 * (lib.rs:276-285). */
enum class PropertiesValueState {
    /** No separator followed the key. */
    ImplicitEmpty,

    /** A whitespace, `=`, or `:` separator was present but the element is
     * empty. */
    ExplicitEmpty,

    /** The decoded element contains at least one UTF-16 code unit. */
    Present,
}

/** Kind of one logical Properties record (lib.rs:287-295). */
enum class PropertiesLogicalLineKind {
    /** One completely formed property occurrence. */
    Property,

    /** One recovered malformed logical line. */
    Error,
}

/** Kind of one retained escape occurrence (lib.rs:296-307). */
enum class PropertiesEscapeKind {
    /** `\t`, `\n`, `\r`, or `\f`. */
    Named,

    /** `\\`. */
    Backslash,

    /** Exact lowercase-`u` four-hex-digit escape. */
    Unicode,

    /** Backslash removed before another source character. */
    DroppedBackslash,
}
