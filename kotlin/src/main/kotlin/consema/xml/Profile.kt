// The frozen XML formation profile, explicit encoding selection, and the
// XML-specific parse/entity/recovery limits.
//
// Data authority (language-neutral sources first):
//   - RFC 0012 §1 (https://github.com/consema/consema/blob/main/docs/rfcs/0012-xml-1.0-safe-profile-v1.md:13-40):
//     exactly one Profile, `xml.1.0-safe@1`; selected before formation, never
//     by extension; the parser consumes one complete document entity and
//     opens no other entity, file, URI, network connection, registry,
//     classpath, or catalog.
//   - RFC 0012 §2 (0012-...:46-81): the v1 document-entity encoding table
//     (UTF-8 optional BOM; UTF-16LE/BE with a required BOM; no-BOM defaults
//     to UTF-8; UTF-16 without a BOM is rejected; UTF-32/Latin-1/Windows
//     code pages are explicit v1 exclusions).
//   - https://github.com/consema/consema-rs/blob/main/consema-xml/src/lib.rs:54-67 (XmlProfile, id), lib.rs:69-79
//     (XmlEncodingSelection), lib.rs:81-157 (XmlParseLimits and the frozen
//     defaults), lib.rs:159-172 (entity_limits derivation). The frozen
//     numbers are transcribed VERBATIM from lib.rs:130-156.
//   - consema-go/go/xml/profile.go is a cross-reference only.
//
// Kotlin-idiomatic design: the profile is a closed enum, the encoding
// selection is a sealed class, and the limits are an immutable data class
// whose defaults are the frozen lib.rs:130-156 values.

package consema.xml

import consema.document.ParseLimits
import consema.document.ProfileId
import consema.document.SourceEncoding

/** Frozen XML formation profile (lib.rs:54-67). */
enum class XmlProfile {
    /** Namespace-aware, side-effect-free XML 1.0 with the safe DTD subset
     * (RFC 0012 §1). */
    SafeV1,
    ;

    /** Stable profile identifier (lib.rs:61-67). */
    fun id(): ProfileId = ProfileId("xml.1.0-safe", 1)
}

/**
 * Explicit document-entity encoding selection (lib.rs:69-79; RFC 0012 §2).
 * No-BOM source defaults to UTF-8. An explicit caller choice is evidence,
 * not permission to contradict a BOM or a declaration.
 */
sealed class XmlEncodingSelection {
    /** Apply only the frozen profile default and BOM rules. */
    data object ProfileDefault : XmlEncodingSelection()

    /** Use one caller-selected document-entity encoding. */
    data class Explicit(val encoding: SourceEncoding) : XmlEncodingSelection()
}

/**
 * XML-specific formation, entity, and recovery limits (RFC 0012 §12;
 * lib.rs:81-128). All frozen defaults are transcribed from lib.rs:130-156.
 * Common source, node, piece, nesting, and diagnostic limits mirror the
 * document domain [ParseLimits] (document/Limits.kt:31-55).
 */
data class XmlParseLimits(
    /** Common source, node, piece, nesting, and diagnostic limits. */
    val common: ParseLimits,
    /** Maximum decoded UTF-8 bytes. */
    val maxDecodedUtf8Bytes: Int,
    /** Maximum decoded Unicode scalars and coordinate steps. */
    val maxDecodedScalars: Int,
    /** Maximum elements in the native tree. */
    val maxElementCount: Int,
    /** Maximum attributes per element. */
    val maxAttributeCount: Int,
    /** Maximum namespace declarations per element. */
    val maxNamespaceDeclarationCount: Int,
    /** Maximum child content items per element. */
    val maxMixedContentItems: Int,
    /** Maximum QName bytes (prefix, local, and full spelling). */
    val maxQnameLength: Int,
    /** Maximum namespace URI bytes. */
    val maxNamespaceUriLength: Int,
    /** Maximum attribute-value decoded bytes. */
    val maxAttributeValueLength: Int,
    /** Maximum comment decoded bytes. */
    val maxCommentLength: Int,
    /** Maximum processing-instruction content decoded bytes. */
    val maxPiLength: Int,
    /** Maximum CDATA content decoded bytes. */
    val maxCdataLength: Int,
    /** Maximum text content decoded bytes. */
    val maxTextLength: Int,
    /** Maximum DTD subset raw bytes. */
    val maxDtdBytes: Int,
    /** Maximum entity declarations. */
    val maxEntityDeclarations: Int,
    /** Maximum entity references. */
    val maxEntityReferences: Int,
    /** Maximum reference expansion depth. */
    val maxEntityExpansionDepth: Int,
    /** Maximum expanded bytes across the whole document. */
    val maxExpandedEntityBytes: Int,
    /** Maximum expanded scalars across the whole document. */
    val maxExpandedEntityScalars: Int,
    /** Maximum expanded/declared byte amplification ratio. */
    val maxEntityAmplificationRatio: Long,
    /** Maximum recovery error regions. */
    val maxRecoveryRegions: Int,
) {
    companion object {
        /** The frozen defaults (lib.rs:130-156). */
        val default = XmlParseLimits(
            common = ParseLimits.default,
            maxDecodedUtf8Bytes = 128 * 1024 * 1024,
            maxDecodedScalars = 64 * 1024 * 1024,
            maxElementCount = 1_000_000,
            maxAttributeCount = 100_000,
            maxNamespaceDeclarationCount = 100_000,
            maxMixedContentItems = 2_000_000,
            maxQnameLength = 4 * 1024,
            maxNamespaceUriLength = 8 * 1024,
            maxAttributeValueLength = 4 * 1024 * 1024,
            maxCommentLength = 4 * 1024 * 1024,
            maxPiLength = 4 * 1024 * 1024,
            maxCdataLength = 4 * 1024 * 1024,
            maxTextLength = 4 * 1024 * 1024,
            maxDtdBytes = 4 * 1024 * 1024,
            maxEntityDeclarations = 10_000,
            maxEntityReferences = 1_000_000,
            maxEntityExpansionDepth = 100,
            maxExpandedEntityBytes = 32 * 1024 * 1024,
            maxExpandedEntityScalars = 16 * 1024 * 1024,
            maxEntityAmplificationRatio = 1_000,
            maxRecoveryRegions = 100_000,
        )
    }

    /** Entity expansion limits derived from these parse limits (lib.rs:159-172). */
    fun entityLimits(): EntityExpansionLimits =
        EntityExpansionLimits(
            maxDeclarations = maxEntityDeclarations,
            maxReferences = maxEntityReferences,
            maxExpansionDepth = maxEntityExpansionDepth,
            maxExpandedBytes = maxExpandedEntityBytes,
            maxExpandedScalars = maxExpandedEntityScalars,
            maxAmplificationRatio = maxEntityAmplificationRatio,
        )
}
