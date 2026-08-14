// The frozen YAML language profiles and the closed lossless presentation
// classification.
//
// Data authority (language-neutral sources first):
//   - RFC 0007 §1 (https://github.com/consema/consema/blob/main/docs/rfcs/0007-yaml-family-profiles-and-safety-v1.md):
//     the two profiles yaml.1.2-core@1 (YAML 1.2.2 presentation grammar +
//     Core schema) and yaml.1.1-compat@1 (YAML 1.2-compatible presentation +
//     frozen 1.1 scalar resolution); they share source/lossless structure/
//     graph/transaction/proof infrastructure but not implicit scalar
//     resolution, accepted %YAML versions, canonical scalar spellings, or
//     materialization styles.
//   - conformance/vectors/yaml-v1.json pins the profile spellings (line 3)
//     and the syntax kinds the vectors assert (syntax.styles-and-trivia,
//     yaml-v1.json:31-34).
//   - https://github.com/consema/consema-rs/blob/main/consema-yaml/src/lib.rs pins YamlProfile and the exact
//     YamlSyntaxKind spellings (as_str lib.rs, from_name lib.rs
//); lib.rs pins the profile ids and accepted %YAML
//     versions. consema-go/go/yaml/profile.go is a cross-reference only.
//
// Kotlin-idiomatic design (NOT a translation): the profile is a closed enum;
// the syntax-kind names are the exact language-neutral spellings asserted by
// the query domain and the vectors, so [YamlSyntaxKind.asStr] IS the frozen
// spelling.

package consema.yaml

import consema.document.ProfileId

/** Frozen YAML language profile (lib.rs). */
enum class YamlProfile {
    /** YAML 1.2.2 presentation grammar with the Core schema. */
    Yaml12CoreV1,

    /** Safe YAML 1.2-compatible presentation with frozen YAML 1.1 scalar
     * resolution (RFC 0007 §1). */
    Yaml11CompatV1,
    ;

    /** Immutable profile identifier (lib.rs). */
    fun id(): ProfileId =
        when (this) {
            Yaml12CoreV1 -> ProfileId("yaml.1.2-core", 1)
            Yaml11CompatV1 -> ProfileId("yaml.1.1-compat", 1)
        }

    /** The exact `%YAML` version accepted by this profile (lib.rs). */
    internal fun acceptedVersion(): String =
        when (this) {
            Yaml12CoreV1 -> "1.2"
            Yaml11CompatV1 -> "1.1"
        }
}

/**
 * Closed YAML lossless presentation-piece classification (lib.rs).
 * The enum order is the Rust declaration order; the query/protocol
 * vocabulary is [asStr] (lib.rs), which is byte-identical to the
 * vector spellings.
 */
enum class YamlSyntaxKind {
    /** Unicode byte-order mark retained in the decoded stream. */
    Bom,

    /** Horizontal separation. */
    Whitespace,

    /** LF, CRLF, or bare CR line break. */
    Newline,

    /** Comment excluding its line break. */
    Comment,

    /** `%YAML`, `%TAG`, or reserved directive line. */
    Directive,

    /** `---` document start. */
    DocumentStart,

    /** `...` document end. */
    DocumentEnd,

    /** Block sequence `-` indicator. */
    SequenceEntry,

    /** Explicit mapping key `?` indicator. */
    ExplicitKey,

    /** Mapping value `:` indicator. */
    MappingValue,

    /** `[`. */
    FlowSequenceStart,

    /** `]`. */
    FlowSequenceEnd,

    /** `{`. */
    FlowMappingStart,

    /** `}`. */
    FlowMappingEnd,

    /** Flow `,` separator. */
    FlowEntry,

    /** Anchor spelling beginning with `&`. */
    Anchor,

    /** Alias spelling beginning with `*`. */
    Alias,

    /** Tag spelling beginning with `!`. */
    Tag,

    /** Plain scalar presentation fragment. */
    PlainScalar,

    /** Complete single-quoted scalar presentation. */
    SingleQuotedScalar,

    /** Complete double-quoted scalar presentation. */
    DoubleQuotedScalar,

    /** Literal block-scalar header beginning with `|`. */
    LiteralBlockHeader,

    /** Folded block-scalar header beginning with `>`. */
    FoldedBlockHeader,

    /** Exact indented block-scalar content region. */
    BlockScalarContent,

    /** Bytes retained after bounded syntax recovery. */
    ErrorRegion,
    ;

    /** Stable query and protocol name (lib.rs). */
    fun asStr(): String =
        when (this) {
            Bom -> "Bom"
            Whitespace -> "Whitespace"
            Newline -> "Newline"
            Comment -> "Comment"
            Directive -> "Directive"
            DocumentStart -> "DocumentStart"
            DocumentEnd -> "DocumentEnd"
            SequenceEntry -> "SequenceEntry"
            ExplicitKey -> "ExplicitKey"
            MappingValue -> "MappingValue"
            FlowSequenceStart -> "FlowSequenceStart"
            FlowSequenceEnd -> "FlowSequenceEnd"
            FlowMappingStart -> "FlowMappingStart"
            FlowMappingEnd -> "FlowMappingEnd"
            FlowEntry -> "FlowEntry"
            Anchor -> "Anchor"
            Alias -> "Alias"
            Tag -> "Tag"
            PlainScalar -> "PlainScalar"
            SingleQuotedScalar -> "SingleQuotedScalar"
            DoubleQuotedScalar -> "DoubleQuotedScalar"
            LiteralBlockHeader -> "LiteralBlockHeader"
            FoldedBlockHeader -> "FoldedBlockHeader"
            BlockScalarContent -> "BlockScalarContent"
            ErrorRegion -> "ErrorRegion"
        }

    /** Whether this kind is classified as structural trivia (lib.rs). */
    internal fun isTrivia(): Boolean =
        this == Bom || this == Whitespace || this == Newline || this == Comment

    companion object {
        /** Resolves one exact stable kind name (lib.rs). */
        fun fromName(name: String): YamlSyntaxKind? =
            entries.firstOrNull { it.asStr() == name }
    }
}

/** YAML native representation node kind (lib.rs). */
enum class YamlNodeKind {
    /** Tagged scalar. */
    Scalar,

    /** Ordered sequence associations. */
    Sequence,

    /** Ordered arbitrary key/value associations. */
    Mapping,
}

/** Exact scalar presentation style (lib.rs). */
enum class YamlScalarStyle {
    /** Plain style. */
    Plain,

    /** Single-quoted style. */
    SingleQuoted,

    /** Double-quoted style. */
    DoubleQuoted,

    /** Literal block style. */
    Literal,

    /** Folded block style. */
    Folded,
}

/** Resolved native scalar semantic category (lib.rs). */
enum class YamlScalarKind {
    /** Null. */
    Null,

    /** Boolean. */
    Boolean,

    /** Arbitrary-precision integer. */
    Integer,

    /** Exact decimal or frozen non-finite float spelling. */
    Float,

    /** String. */
    String,

    /** YAML 1.1-compatible timestamp. */
    Timestamp,

    /** Validated YAML binary scalar. */
    Binary,

    /** Scalar carrying an uninterpreted custom tag. */
    Custom,

    /** Scalar carrying a retained standard tag without a core tree lowering. */
    Tagged,
}
