// The L4 root facade: one opaque Document union over the eight format
// families (Kotlin).
//
// Data authority (language-neutral sources first):
//   - crates/consema/src/lib.rs:512-820 (Document, DocumentInner, FormatMismatch,
//     and the typed adapters as_json/as_toml/as_yaml/as_ini/as_properties/
//     as_xml/as_plist/as_hcl) — the common opaque facade contract; the Rust
//     tests at lib.rs:822-1068 pin the adapter failure vocabulary.
//   - RFC 0015 §6.2 (the facade surface the CLI derives every format fact
//     from; crates/consema/src/bin/consema/registry.rs as the CLI thin
//     enumeration over it).
//   - docs/multi-language-implementation-plan.md §0.3/§1 (L1 root facade
//     "Document union/Registry/convert" mirrors Go G1.4; each language
//     implements its own idiom — Kotlin sealed class, never a translation of
//     the Rust enum wrapper).
//   - go/document.go and go/registry.go are cross-references only.
//
// Kotlin-idiomatic design: the closed family set is a sealed class
// hierarchy, so an exhaustive `when` over the variants can never meet an
// unknown family (RFC 0016 §4.1 no-default spirit). The variant carries the
// exact immutable family document; the common surface (render, formation
// status, diagnostics, snapshot identity, profile) is dispatched through the
// sealed class, and the typed adapters return the family document or null
// (Kotlin idiom for the Rust Result<&Document, FormatMismatch>).

package consema

import consema.document.FormationStatus
import consema.document.ParseLimits
import consema.document.ProfileId
import consema.document.SnapshotIdentity
import consema.protocol.Diagnostic
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion

/** The current frozen error-code registry used to externalize family
 * diagnostics (the v7 registry; the family codes are all registered there). */
private val FACADE_REGISTRY: ErrorCodeRegistry =
    ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)

/** Caller-stable source identity of one snapshot for diagnostic
 * externalization (the process-local snapshot identity; the same convention
 * the families use, Errors.kt:65-80). */
private fun Document.sourceId(): String = snapshotIdentity().asU64.toString()

private fun xmlToProtocol(item: consema.xml.XmlDiagnostic): Diagnostic =
    Diagnostic.of(
        code = item.code,
        category = item.category,
        severity = item.severity,
        primary = item.primary,
        related = emptyList(),
        arguments = emptyMap(),
        notes = emptyList(),
        fixes = emptyList(),
        occurrence = item.occurrence,
        registry = FACADE_REGISTRY,
    )

/** One immutable snapshot over the supported format documents (lib.rs:512-531).
 * The concrete family is private; format access happens only through the
 * typed adapters. All returned facts are immutable snapshot facts. */
sealed class Document {
    /** An immutable JSON-family document (json.strict/jsonc.bounded/
     * json5.standard profiles). */
    data class Json(val document: consema.json.Document) : Document()

    /** An immutable TOML 1.0 document. */
    data class Toml(val document: consema.toml.TomlDocument) : Document()

    /** An immutable YAML stream document (yaml.1.2-core/yaml.1.1-compat). */
    data class Yaml(val document: consema.yaml.Document) : Document()

    /** An immutable INI-family document (portable/windows/python-configparser). */
    data class Ini(val document: consema.ini.IniDocument) : Document()

    /** An immutable Java Properties document (reader/latin1). */
    data class Properties(val document: consema.properties.Document) : Document()

    /** An immutable XML 1.0 safe document. */
    data class Xml(val document: consema.xml.Document) : Document()

    /** An immutable Property List document (xml/binary profiles). */
    data class Plist(val document: consema.plist.Document) : Document()

    /** An immutable HCL document (native/tfvars profiles). */
    data class Hcl(val document: consema.hcl.HclDocument) : Document()

    /** Default rendering is byte-for-byte identical to the source. */
    fun render(): ByteArray =
        when (this) {
            is Json -> document.render()
            is Toml -> document.render()
            is Yaml -> document.render()
            is Ini -> document.render()
            is Properties -> document.render()
            is Xml -> document.render()
            is Plist -> document.render()
            is Hcl -> document.render()
        }

    /** Formation status of the underlying snapshot. */
    fun formationStatus(): FormationStatus =
        when (this) {
            is Json -> document.formationStatus()
            is Toml -> document.formationStatus()
            is Yaml -> document.formationStatus()
            is Ini -> document.formationStatus()
            is Properties -> document.formationStatus()
            is Xml -> document.formationStatus()
            is Plist -> document.formationStatus()
            is Hcl -> document.formationStatus()
        }

    /** Deterministically ordered document diagnostics, externalized as
     * transferable `core.diagnostic@1` records under the current error
     * registry. */
    fun diagnostics(): List<Diagnostic> =
        when (this) {
            is Json -> document.diagnostics()
            is Toml -> document.diagnostics()
            is Yaml -> document.diagnostics()
            is Ini -> document.diagnostics()
            is Properties -> document.diagnostics()
            is Xml -> document.diagnostics().map { xmlToProtocol(it) }
            is Plist -> document.diagnostics().map { it.toProtocolDiagnostic(sourceId(), FACADE_REGISTRY) }
            is Hcl -> document.diagnostics().map { it.toProtocolDiagnostic(sourceId(), FACADE_REGISTRY) }
        }

    /** Snapshot identity to which every handle and span belongs. */
    fun snapshotIdentity(): SnapshotIdentity =
        when (this) {
            is Json -> document.snapshotIdentity
            is Toml -> document.snapshotIdentity
            is Yaml -> document.snapshotIdentity
            is Ini -> document.snapshotIdentity
            is Properties -> document.snapshotIdentity
            is Xml -> document.snapshotIdentity
            is Plist -> document.snapshotIdentity
            is Hcl -> document.snapshotIdentity
        }

    /** Exact source profile of the underlying format document. */
    fun profile(): ProfileId =
        when (this) {
            is Json -> document.profileId()
            is Toml -> document.profile()
            is Yaml -> document.profileId()
            is Ini -> document.profileId()
            is Properties -> document.profileId()
            is Xml -> document.profileId()
            is Plist -> document.profileId()
            is Hcl -> document.profileId()
        }

    /** Typed JSON adapter; null when the snapshot is not JSON. */
    fun asJson(): consema.json.Document? = (this as? Json)?.document

    /** Typed TOML adapter; null when the snapshot is not TOML. */
    fun asToml(): consema.toml.TomlDocument? = (this as? Toml)?.document

    /** Typed YAML adapter; null when the snapshot is not YAML. */
    fun asYaml(): consema.yaml.Document? = (this as? Yaml)?.document

    /** Typed INI adapter; null when the snapshot is not INI. */
    fun asIni(): consema.ini.IniDocument? = (this as? Ini)?.document

    /** Typed Java Properties adapter; null when the snapshot is not
     * Properties. */
    fun asProperties(): consema.properties.Document? = (this as? Properties)?.document

    /** Typed XML adapter; null when the snapshot is not XML. */
    fun asXml(): consema.xml.Document? = (this as? Xml)?.document

    /** Typed Property List adapter; null when the snapshot is not a plist. */
    fun asPlist(): consema.plist.Document? = (this as? Plist)?.document

    /** Typed HCL adapter; null when the snapshot is not HCL. */
    fun asHcl(): consema.hcl.HclDocument? = (this as? Hcl)?.document

    companion object {
        /** Parses one JSON/JSONC/JSON5 snapshot under an exact profile. */
        fun parseJson(
            bytes: ByteArray,
            profile: consema.json.JsonProfile,
            limits: ParseLimits = ParseLimits.default,
        ): Document = Json(consema.json.parse(bytes, profile, limits))

        /** Parses one TOML snapshot under the exact profile. */
        fun parseToml(
            bytes: ByteArray,
            profile: consema.toml.TomlProfile,
            limits: ParseLimits = ParseLimits.default,
        ): Document = Toml(consema.toml.parse(bytes, profile, limits))

        /** Parses one YAML stream under one exact frozen profile. */
        fun parseYaml(
            bytes: ByteArray,
            profile: consema.yaml.YamlProfile,
            limits: ParseLimits = ParseLimits.default,
        ): Document = Yaml(consema.yaml.parse(bytes, profile, limits))

        /** Parses one INI snapshot under an exact profile and explicit
         * encoding selection. */
        fun parseIni(
            bytes: ByteArray,
            profile: consema.ini.IniProfile,
            encoding: consema.ini.IniEncodingSelection = consema.ini.IniEncodingSelection.ProfileDefault,
            limits: consema.ini.IniParseLimits = consema.ini.IniParseLimits.default,
        ): Document = Ini(consema.ini.parse(bytes, profile, encoding, limits))

        /** Parses one Java Properties snapshot under an exact profile and
         * source contract. */
        fun parseProperties(
            bytes: ByteArray,
            profile: consema.properties.PropertiesProfile,
            encoding: consema.properties.PropertiesEncoding,
            limits: consema.properties.PropertiesParseLimits = consema.properties.PropertiesParseLimits.default,
        ): Document = Properties(consema.properties.parse(bytes, profile, encoding, limits))

        /** Parses one XML 1.0 safe snapshot under the exact profile and
         * explicit encoding selection. */
        fun parseXml(
            bytes: ByteArray,
            profile: consema.xml.XmlProfile,
            selection: consema.xml.XmlEncodingSelection,
            limits: consema.xml.XmlParseLimits,
        ): Document = Xml(consema.xml.parse(bytes, profile, selection, limits))

        /** Parses one Property List snapshot under an exact profile and
         * explicit encoding selection. */
        fun parsePlist(
            bytes: ByteArray,
            profile: consema.plist.PlistProfile,
            selection: consema.plist.PlistEncodingSelection = consema.plist.PlistEncodingSelection.ProfileDefault,
            limits: consema.plist.PlistParseLimits = consema.plist.PlistParseLimits.default,
        ): Document = Plist(consema.plist.parse(bytes, profile, selection, limits))

        /** Parses one HCL snapshot under the exact profile. */
        fun parseHcl(
            bytes: ByteArray,
            profile: consema.hcl.HclProfile,
            limits: consema.hcl.HclParseLimits = consema.hcl.HclParseLimits.default,
        ): Document = Hcl(consema.hcl.parse(bytes, profile, limits))
    }
}
