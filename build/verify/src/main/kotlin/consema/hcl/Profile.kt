// The frozen HCL-family language profiles and lossless syntax-piece
// classification.
//
// Data authority (language-neutral sources first):
//   - RFC 0014 §1 (docs/rfcs/0014-hcl-family-profiles-v1.md:23-54): the two
//     mandatory profiles `hcl.native@1` and `hcl.tfvars@1`; both share one
//     native syntax system, and `hcl.tfvars@1` is `hcl.native@1` under one
//     structural restriction (the top-level body admits attributes only,
//     never blocks, RFC 0014 §5).
//   - RFC 0014 §7.2 (:487-507): the v1 lossless syntax kind set is exactly
//     the thirty PascalCase spellings below; there is no `Bom` kind because
//     a BOM is excluded at formation (RFC 0014 §2).
//   - crates/consema-hcl/src/lib.rs:101-118 (HclProfile and its id mapping),
//     crates/consema-hcl/src/native.rs:335-398 (HclSyntaxKind declaration
//     order) pin the spellings; go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the profile is a closed enum;
// the syntax-kind names are the exact language-neutral spellings asserted by
// `hcl.lossless-syntax-query@1` and the vectors, so `asStr()` IS the frozen
// spelling (RFC 0014 §7.2: PascalCase spellings FROZEN — do not rename).

package consema.hcl

import consema.document.ProfileId

/** Frozen HCL formation profiles (RFC 0014 §1; lib.rs:101-107). */
enum class HclProfile {
    /** The full HCL Native Syntax (RFC 0014 §4). */
    NATIVE_V1,

    /** `hcl.native@1` under the tfvars structural restriction: the top-level
     * body admits attributes only, never blocks (RFC 0014 §5). */
    TFVARS_V1,
    ;

    /** Stable profile identifier (lib.rs:109-118). */
    fun id(): ProfileId =
        when (this) {
            NATIVE_V1 -> ProfileId("hcl.native", 1)
            TFVARS_V1 -> ProfileId("hcl.tfvars", 1)
        }

    /** Whether top-level blocks are admitted (RFC 0014 §5). */
    internal fun admitsTopLevelBlocks(): Boolean = this == NATIVE_V1
}

/**
 * Closed HCL lossless syntax-piece classification (RFC 0014 §7.2;
 * native.rs:335-398). Exactly thirty kinds; the enum order is the Rust
 * declaration order, and the wire/query vocabulary is [asStr], which is
 * byte-identical to the vector spellings. There is no `Bom` kind: a BOM is
 * excluded at formation (RFC 0014 §2).
 */
enum class HclSyntaxKind {
    /** Space or tab trivia. */
    Whitespace,

    /** LF or CRLF newline sequence. */
    LineBreak,

    /** `//` or `#` line comment. */
    LineComment,

    /** `/* ... */` inline comment. */
    InlineComment,

    /** Identifier token. */
    Identifier,

    /** `=` equals sign. */
    Equals,

    /** Number literal token. */
    Number,

    /** `"` quoted-template opening quote. */
    StringOpen,

    /** Quoted-template literal content. */
    StringContent,

    /** `"` quoted-template closing quote. */
    StringClose,

    /** `${` interpolation opening (with optional `~` strip marker). */
    InterpolationOpen,

    /** Interpolation content between the opening and closing markers. */
    InterpolationContent,

    /** `}` interpolation closing (with optional `~` strip marker). */
    InterpolationClose,

    /** `%{` directive opening (with optional `~` strip marker). */
    DirectiveOpen,

    /** Directive content between the opening and closing markers. */
    DirectiveContent,

    /** `}` directive closing (with optional `~` strip marker). */
    DirectiveClose,

    /** `<<`/`<<-` heredoc introducer and marker identifier. */
    HeredocOpen,

    /** Heredoc content line. */
    HeredocContent,

    /** Heredoc closing marker line. */
    HeredocClose,

    /** `{` brace open. */
    BraceOpen,

    /** `}` brace close. */
    BraceClose,

    /** `[` bracket open. */
    BracketOpen,

    /** `]` bracket close. */
    BracketClose,

    /** `(` paren open. */
    ParenOpen,

    /** `)` paren close. */
    ParenClose,

    /** `,` comma. */
    Comma,

    /** `:` colon. */
    Colon,

    /** `?` question mark. */
    QuestionMark,

    /** Operator token (`-`, `!`, `==`, `!=`, `<`, `>`, `<=`, `>=`, `+`,
     * `*`, `/`, `%`, `&&`, `||`). */
    Operator,

    /** Recovered error region (BOM, lone CR, invalid character, or error
     * token region). */
    ErrorRegion,
    ;

    /** Stable query and protocol spelling (native.rs:400-435). */
    fun asStr(): String =
        when (this) {
            Whitespace -> "Whitespace"
            LineBreak -> "LineBreak"
            LineComment -> "LineComment"
            InlineComment -> "InlineComment"
            Identifier -> "Identifier"
            Equals -> "Equals"
            Number -> "Number"
            StringOpen -> "StringOpen"
            StringContent -> "StringContent"
            StringClose -> "StringClose"
            InterpolationOpen -> "InterpolationOpen"
            InterpolationContent -> "InterpolationContent"
            InterpolationClose -> "InterpolationClose"
            DirectiveOpen -> "DirectiveOpen"
            DirectiveContent -> "DirectiveContent"
            DirectiveClose -> "DirectiveClose"
            HeredocOpen -> "HeredocOpen"
            HeredocContent -> "HeredocContent"
            HeredocClose -> "HeredocClose"
            BraceOpen -> "BraceOpen"
            BraceClose -> "BraceClose"
            BracketOpen -> "BracketOpen"
            BracketClose -> "BracketClose"
            ParenOpen -> "ParenOpen"
            ParenClose -> "ParenClose"
            Comma -> "Comma"
            Colon -> "Colon"
            QuestionMark -> "QuestionMark"
            Operator -> "Operator"
            ErrorRegion -> "ErrorRegion"
        }

    companion object {
        /** Resolves one exact stable kind name (native.rs:437-467). */
        fun fromName(name: String): HclSyntaxKind? = entries.firstOrNull { it.asStr() == name }
    }
}

/** The frozen materialization style of the HCL family (RFC 0014 §9). */
object HclStyle {
    /** `hcl.canonical-document@1`: canonical rendering of a validated
     * `hcl.body@1` record (RFC 0014 §9). */
    const val CANONICAL_DOCUMENT: String = "hcl.canonical-document"
}
