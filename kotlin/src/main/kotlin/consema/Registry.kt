// The L4 root facade registry: families, profiles, query domains, and
// per-profile operation registries (Kotlin).
//
// Data authority (language-neutral sources first):
//   - https://github.com/consema/consema-rs/blob/main/consema/src/lib.rs (the additive facade `registry`
//     module: format_families, profiles, query_domains, operation_registry,
//     parse_document; the drift guard is the facade's own tests at the same
//     file, lib.rs, asserting enumerated ids against parsed backend
//     documents).
//   - RFC 0015 §6.2 (`families`, `profiles`, `query_domains`, `operations`
//     facts the CLI derives exclusively from this surface;
//     https://github.com/consema/consema-rs/blob/main/consema/src/bin/consema/registry.rs as the CLI thin
//     enumeration).
//   - https://github.com/consema/consema/blob/main/docs/fc-manifest-0.13.0.json (capability_set record: 8
//     families / 16 profiles / 21 query domains / 16 operation registries
//     / 187 error codes — line numbers drift on every re-provision, the
//     field is the anchor) — the CapabilityParity test pins these counts.
//   - https://github.com/consema/consema/blob/main/docs/multi-language-implementation-plan.md §0.3/§1 (L1 root facade
//     "Document union/Registry/convert" — implemented in this repository;
//     consema-go/go/registry.go is a cross-reference only).
//
// Kotlin-idiomatic design: immutable data classes and pure functions over
// the family enum types; ids keep the exact language-neutral spellings so
// `ProfileId.id` IS the wire spelling. The registry is strictly additive —
// no existing family API is rewritten.
// NOTE: 行号可能漂移，以 capability_set 计数为锚（fc-manifest 按 sync-note 重同步后行号会变）。

package consema

import consema.document.FormatFamilyId
import consema.document.FormatOperationId
import consema.document.ProfileId
import consema.protocol.QueryDomain

/** One profile together with the format family that publishes it. */
data class FormatProfile(
    /** Format family of the profile. */
    val family: FormatFamilyId,
    /** The profile itself. */
    val profile: ProfileId,
)

/** The eight format families (RFC 0015 §6.2 `families`), sorted by id. */
fun formatFamilies(): List<FormatFamilyId> =
    listOf("hcl", "ini", "java-properties", "json", "plist", "toml", "xml", "yaml")
        .map { FormatFamilyId(it, 1) }

/** All sixteen profiles with their owning family (RFC 0015 §6.2
 * `profiles`), sorted by profile id. */
fun profiles(): List<FormatProfile> =
    listOf(
        familyProfile("hcl", consema.hcl.HclProfile.NATIVE_V1.id()),
        familyProfile("hcl", consema.hcl.HclProfile.TFVARS_V1.id()),
        familyProfile("ini", consema.ini.IniProfile.PortableV1.id()),
        familyProfile("ini", consema.ini.IniProfile.WindowsV1.id()),
        familyProfile("ini", consema.ini.IniProfile.PythonConfigParserV1.id()),
        familyProfile("java-properties", consema.properties.PropertiesProfile.ReaderV1.id()),
        familyProfile("java-properties", consema.properties.PropertiesProfile.Latin1V1.id()),
        familyProfile("json", consema.json.JsonProfile.StrictV1.id()),
        familyProfile("json", consema.json.JsonProfile.JsoncBoundedV1.id()),
        familyProfile("json", consema.json.JsonProfile.Json5StandardV1.id()),
        familyProfile("plist", consema.plist.PlistProfile.XmlV1.id()),
        familyProfile("plist", consema.plist.PlistProfile.BinaryV1.id()),
        familyProfile("toml", consema.toml.TomlProfile.TOML_1_0_V1.id()),
        familyProfile("xml", consema.xml.XmlProfile.SafeV1.id()),
        familyProfile("yaml", consema.yaml.YamlProfile.Yaml12CoreV1.id()),
        familyProfile("yaml", consema.yaml.YamlProfile.Yaml11CompatV1.id()),
    ).sortedBy { it.profile.id }

/** The query-domain constructor inventory (RFC 0015 §6.2 `query_domains`),
 * sorted by (id, version). */
fun queryDomains(): List<QueryDomain> =
    listOf(
        consema.protocol.Domains.portableValueV1(),
        consema.protocol.Domains.portableGraphV1(),
        consema.protocol.Domains.jsonNativeV1(),
        consema.protocol.Domains.jsonNativeV2(),
        consema.protocol.Domains.tomlNativeV1(),
        consema.protocol.Domains.yamlNativeV1(),
        consema.protocol.Domains.iniNativeV1(),
        consema.protocol.Domains.javaPropertiesNativeV1(),
        consema.protocol.Domains.xmlNativeV1(),
        consema.protocol.Domains.jsonLosslessSyntaxV1(),
        consema.protocol.Domains.jsonLosslessSyntaxV2(),
        consema.protocol.Domains.tomlLosslessSyntaxV1(),
        consema.protocol.Domains.yamlLosslessSyntaxV1(),
        consema.protocol.Domains.iniLosslessSyntaxV1(),
        consema.protocol.Domains.javaPropertiesLosslessSyntaxV1(),
        consema.protocol.Domains.xmlLosslessSyntaxV1(),
        consema.protocol.Domains.plistNativeV1(),
        consema.protocol.Domains.plistLosslessSyntaxV1(),
        consema.protocol.Domains.plistBinaryStructureV1(),
        consema.protocol.Domains.hclNativeV1(),
        consema.protocol.Domains.hclLosslessSyntaxV1(),
    ).sortedWith(compareBy({ it.id }, { it.version }))

/** The per-profile operation registry of one exact profile (RFC 0015 §6.2
 * `operations`), as the ordered frozen operation ids; null for ids outside
 * the facade surface. */
fun operationRegistry(profile: ProfileId): List<FormatOperationId>? =
    when (profile.id) {
        "hcl.native" ->
            consema.hcl.formatOperationRegistry(consema.hcl.HclProfile.NATIVE_V1).map { it.id }
        "hcl.tfvars" ->
            consema.hcl.formatOperationRegistry(consema.hcl.HclProfile.TFVARS_V1).map { it.id }
        "ini.portable" ->
            consema.ini.formatOperationRegistry(consema.ini.IniProfile.PortableV1).map { it.id }
        "ini.windows" ->
            consema.ini.formatOperationRegistry(consema.ini.IniProfile.WindowsV1).map { it.id }
        "ini.python-configparser" ->
            consema.ini.formatOperationRegistry(consema.ini.IniProfile.PythonConfigParserV1)
                .map { it.id }
        "java-properties.reader" ->
            consema.properties.formatOperationRegistry(consema.properties.PropertiesProfile.ReaderV1)
                .map { it.id }
        "java-properties.latin1" ->
            consema.properties.formatOperationRegistry(consema.properties.PropertiesProfile.Latin1V1)
                .map { it.id }
        "json.strict" ->
            consema.json.formatOperationRegistry(consema.json.JsonProfile.StrictV1).map { it.id }
        "jsonc.bounded" ->
            consema.json.formatOperationRegistry(consema.json.JsonProfile.JsoncBoundedV1).map { it.id }
        "json5.standard" ->
            consema.json.formatOperationRegistry(consema.json.JsonProfile.Json5StandardV1).map { it.id }
        "plist.xml" ->
            consema.plist.formatOperationRegistry(consema.plist.PlistProfile.XmlV1).map { it.id }
        "plist.binary" ->
            consema.plist.formatOperationRegistry(consema.plist.PlistProfile.BinaryV1).map { it.id }
        "toml.1.0" ->
            consema.toml.formatOperationRegistry(consema.toml.TomlProfile.TOML_1_0_V1).map { it.id }
        "xml.1.0-safe" ->
            consema.xml.formatOperationRegistry(consema.xml.XmlProfile.SafeV1).map { it.id }
        "yaml.1.2-core" ->
            consema.yaml.formatOperationRegistry(consema.yaml.YamlProfile.Yaml12CoreV1).map { it.id }
        "yaml.1.1-compat" ->
            consema.yaml.formatOperationRegistry(consema.yaml.YamlProfile.Yaml11CompatV1).map { it.id }
        else -> null
    }

/**
 * Parses one snapshot under an exact profile id through the single facade
 * parse entry. The per-format encoding selection and limits use the frozen
 * profile defaults; the properties reader profile uses an explicit UTF-8
 * selection because its contract has no profile default. An unknown profile
 * id throws [UnknownProfileException] carrying the frozen code
 * core.materialization.unsupported-profile@1 (wave-4 R1 five-language
 * unified choice).
 */
fun parseDocument(bytes: ByteArray, profile: ProfileId): Document =
    when (profile.id) {
        "ini.portable" -> Document.parseIni(bytes, consema.ini.IniProfile.PortableV1)
        "ini.windows" -> Document.parseIni(bytes, consema.ini.IniProfile.WindowsV1)
        "ini.python-configparser" -> Document.parseIni(bytes, consema.ini.IniProfile.PythonConfigParserV1)
        "java-properties.reader" -> Document.parseProperties(
            bytes,
            consema.properties.PropertiesProfile.ReaderV1,
            consema.properties.PropertiesEncoding.Reader(consema.document.SourceEncoding.Utf8),
        )
        "java-properties.latin1" -> Document.parseProperties(
            bytes,
            consema.properties.PropertiesProfile.Latin1V1,
            consema.properties.PropertiesEncoding.Latin1,
        )
        "json.strict" -> Document.parseJson(bytes, consema.json.JsonProfile.StrictV1)
        "jsonc.bounded" -> Document.parseJson(bytes, consema.json.JsonProfile.JsoncBoundedV1)
        "json5.standard" -> Document.parseJson(bytes, consema.json.JsonProfile.Json5StandardV1)
        "toml.1.0" -> Document.parseToml(bytes, consema.toml.TomlProfile.TOML_1_0_V1)
        "yaml.1.2-core" -> Document.parseYaml(bytes, consema.yaml.YamlProfile.Yaml12CoreV1)
        "yaml.1.1-compat" -> Document.parseYaml(bytes, consema.yaml.YamlProfile.Yaml11CompatV1)
        "xml.1.0-safe" -> Document.parseXml(
            bytes,
            consema.xml.XmlProfile.SafeV1,
            consema.xml.XmlEncodingSelection.ProfileDefault,
            consema.xml.XmlParseLimits.default,
        )
        "plist.xml" -> Document.parsePlist(bytes, consema.plist.PlistProfile.XmlV1)
        "plist.binary" -> Document.parsePlist(bytes, consema.plist.PlistProfile.BinaryV1)
        "hcl.native" -> Document.parseHcl(bytes, consema.hcl.HclProfile.NATIVE_V1)
        "hcl.tfvars" -> Document.parseHcl(bytes, consema.hcl.HclProfile.TFVARS_V1)
        else -> throw UnknownProfileException(profile)
    }

/** Unknown profile id failure of the facade parse entry. Wave-4 R1
 * (five-language unification): the five repos raise the same frozen
 * registered code for an unknown/unsupported profile — the v7 187-code
 * registry contains exactly one code whose literal name is
 * "unsupported-profile", core.materialization.unsupported-profile@1
 * (grep of error_registry.rs; the ts T1c selection is the same code), so
 * this exception carries it instead of being codeless. No new code is
 * added (the registry is frozen; additions are v8 post-1.0.0). */
class UnknownProfileException(val profile: ProfileId) :
    Exception("consema: unknown profile ${profile.id}@${profile.version}") {
    /** The frozen registered code carried by every unknown-profile
     * failure (wave-4 R1, five-language unified). */
    val code: String = "core.materialization.unsupported-profile@1"
}

private fun familyProfile(familyId: String, profile: ProfileId): FormatProfile =
    FormatProfile(FormatFamilyId(familyId, 1), profile)
