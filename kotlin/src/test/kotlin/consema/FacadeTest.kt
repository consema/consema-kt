// The L4 root facade tests: Registry, Document union, and Convert.
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema/src/lib.rs (the Rust facade's
// own test suite this file mirrors: registry_lists_eight_families_and_
// sixteen_profiles, registry_query_domains_are_sorted_and_unique,
// registry_parse_document_round_trips_every_profile,
// registry_family_ids_match_parsed_backend_documents,
// common_document_facade_is_opaque_and_typed, facade_exposes_all_format_
// implementations) and https://github.com/consema/consema-rs/blob/main/consema/src/conversion.rs tests
// (json_to_toml_keeps_both_stages_and_exact_target_closure,
// toml_to_json_is_exact_and_materialization_failure_has_no_document,
// json_cannot_materialize_into_record_formats, plist_value_tree_record_is_
// consumed_only_by_the_plist_family).

package consema

import consema.document.FormatFamilyId
import consema.document.MaterializationRequest
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RegistryFacadeTest {

    @Test
    fun registryListsEightFamiliesAndSixteenProfiles() {
        val families = formatFamilies()
        assertEquals(8, families.size, "eight format families")
        for (index in 1 until families.size) {
            assertTrue(families[index - 1].id < families[index].id, "families sorted by id")
        }
        val profiles = profiles()
        assertEquals(16, profiles.size, "sixteen profiles across the families")
        for (index in 1 until profiles.size) {
            assertTrue(profiles[index - 1].profile.id < profiles[index].profile.id, "profiles sorted by id")
        }
        val expectedProfiles = listOf(
            "hcl.native", "hcl.tfvars", "ini.portable", "ini.python-configparser",
            "ini.windows", "java-properties.latin1", "java-properties.reader",
            "json.strict", "json5.standard", "jsonc.bounded", "plist.binary",
            "plist.xml", "toml.1.0", "xml.1.0-safe", "yaml.1.1-compat", "yaml.1.2-core",
        )
        assertEquals(expectedProfiles, profiles.map { it.profile.id }, "profile inventory")
        // Every profile id maps to a per-profile operation registry.
        for (entry in profiles) {
            assertTrue(
                operationRegistry(entry.profile) != null,
                "${entry.profile.id} must resolve an operation registry",
            )
        }
    }

    @Test
    fun registryQueryDomainsAreSortedAndUnique() {
        val domains = queryDomains()
        assertEquals(21, domains.size, "query-domain constructor inventory")
        for (index in 1 until domains.size) {
            val previous = domains[index - 1]
            val current = domains[index]
            assertTrue(
                previous.id < current.id || (previous.id == current.id && previous.version < current.version),
                "domains sorted by (id, version)",
            )
        }
        assertTrue(domains.any { it.id == "core.portable-value-query" })
        assertTrue(domains.any { it.id == "hcl.native-semantic-query" })
        assertTrue(domains.any { it.id == "plist.binary-structure-query" })
    }

    @Test
    fun registryParseDocumentRoundTripsEveryProfile() {
        val cases = listOf(
            "ini.portable" to "[section]\nvalue=1\n".toByteArray(),
            "ini.windows" to "[section]\nvalue=1\r\n".toByteArray(),
            "ini.python-configparser" to "[section]\nvalue=1\n".toByteArray(),
            "java-properties.reader" to "name=api\n".toByteArray(),
            "java-properties.latin1" to "name=api\n".toByteArray(),
            "json.strict" to "{\"a\":1}".toByteArray(),
            "jsonc.bounded" to "{\"a\":1,}".toByteArray(),
            "json5.standard" to "{a:1,}".toByteArray(),
            "toml.1.0" to "value = 1\n".toByteArray(),
            "yaml.1.2-core" to "value: 1\n".toByteArray(),
            "yaml.1.1-compat" to "value: 1\n".toByteArray(),
            "xml.1.0-safe" to "<service><name>catalog</name></service>".toByteArray(),
            "plist.xml" to "<plist version=\"1.0\"><string>x</string></plist>".toByteArray(),
            "hcl.native" to "a = 1\n".toByteArray(),
            "hcl.tfvars" to "a = 1\n".toByteArray(),
        )
        for ((id, bytes) in cases) {
            val document = parseDocument(bytes, ProfileId(id, 1))
            assertEquals(id, document.profile().id, "$id profile round trip")
        }
        // Unknown profile ids fail like the typed adapters.
        val unknown = try {
            parseDocument("x".toByteArray(), ProfileId("example.unknown", 1))
            null
        } catch (e: UnknownProfileException) {
            e
        }
        assertTrue(unknown != null, "unknown profile id fails")
    }

    @Test
    fun registryFamilyIdsMatchParsedBackendDocuments() {
        val cases = listOf(
            "hcl" to "hcl.native",
            "ini" to "ini.portable",
            "java-properties" to "java-properties.reader",
            "json" to "json.strict",
            "plist" to "plist.xml",
            "toml" to "toml.1.0",
            "xml" to "xml.1.0-safe",
            "yaml" to "yaml.1.2-core",
        )
        val familyOf: (Document) -> String = { document ->
            when (document) {
                is Document.Ini -> document.document.formatFamily().id
                is Document.Properties -> document.document.formatFamily().id
                is Document.Json -> document.document.formatFamily().id
                is Document.Toml -> document.document.formatFamily().id
                is Document.Yaml -> document.document.formatFamily().id
                is Document.Xml -> document.document.formatFamily().id
                is Document.Plist -> document.document.formatFamily().id
                is Document.Hcl -> document.document.formatFamily().id
            }
        }
        for ((familyId, profileId) in cases) {
            val document = parseDocument(sampleBytes(profileId), ProfileId(profileId, 1))
            assertEquals(familyId, familyOf(document), "family id $familyId must match the backend")
        }
    }

    private fun sampleBytes(profileId: String): ByteArray =
        when (profileId) {
            "hcl.native" -> "a = 1\n".toByteArray()
            "ini.portable" -> "value=1\n".toByteArray()
            "java-properties.reader" -> "name=api\n".toByteArray()
            "json.strict" -> "{}".toByteArray()
            "plist.xml" -> "<plist version=\"1.0\"><string>x</string></plist>".toByteArray()
            "toml.1.0" -> "value = 1\n".toByteArray()
            "xml.1.0-safe" -> "<a/>".toByteArray()
            "yaml.1.2-core" -> "value: 1\n".toByteArray()
            else -> error("unknown profile")
        }
}

class DocumentFacadeTest {

    @Test
    fun commonDocumentFacadeIsOpaqueAndTyped() {
        val json = Document.parseJson("{\"a\":1}".toByteArray(), consema.json.JsonProfile.StrictV1)
        assertEquals("{\"a\":1}", String(json.render()))
        assertEquals(consema.document.FormationStatus.Complete, json.formationStatus())
        assertEquals("{\"a\":1}", String(json.asJson()!!.render()))
        assertNull(json.asToml())
        assertNull(json.asIni())
        assertNull(json.asProperties())

        val toml = Document.parseToml("value = 1".toByteArray(), consema.toml.TomlProfile.TOML_1_0_V1)
        assertEquals("value = 1", String(toml.render()))
        assertNull(toml.asJson())
        assertNull(toml.asYaml())
        assertEquals("value = 1", String(toml.asToml()!!.render()))

        val yaml = Document.parseYaml("value: 1\n".toByteArray(), consema.yaml.YamlProfile.Yaml12CoreV1)
        assertEquals("value: 1\n", String(yaml.render()))
        assertNull(yaml.asJson())
        assertNull(yaml.asToml())
        assertEquals("value: 1\n", String(yaml.asYaml()!!.render()))
        assertTrue(yaml.diagnostics().isEmpty())

        val ini = Document.parseIni("[section]\nvalue=1\n".toByteArray(), consema.ini.IniProfile.PortableV1)
        assertEquals("[section]\nvalue=1\n", String(ini.render()))
        assertEquals("ini.portable", ini.profile().id)
        assertNull(ini.asJson())

        val properties = Document.parseProperties(
            "name=api\nport=8080\n".toByteArray(),
            consema.properties.PropertiesProfile.ReaderV1,
            consema.properties.PropertiesEncoding.Reader(consema.document.SourceEncoding.Utf8),
        )
        assertEquals("name=api\nport=8080\n", String(properties.render()))
        assertEquals("java-properties.reader", properties.profile().id)
        assertNull(properties.asIni())

        val xml = Document.parseXml(
            "<service><name>catalog</name></service>".toByteArray(),
            consema.xml.XmlProfile.SafeV1,
            consema.xml.XmlEncodingSelection.ProfileDefault,
            consema.xml.XmlParseLimits.default,
        )
        assertEquals("<service><name>catalog</name></service>", String(xml.render()))
        assertEquals("xml.1.0-safe", xml.profile().id)
        assertNull(xml.asJson())
        assertNull(xml.asPlist())
        assertNull(xml.asHcl())
        assertTrue(xml.diagnostics().isEmpty())

        val plist = Document.parsePlist(
            "<plist version=\"1.0\"><string>x</string></plist>".toByteArray(),
            consema.plist.PlistProfile.XmlV1,
        )
        assertEquals("plist.xml", plist.profile().id)
        assertNull(plist.asXml())
        assertNull(plist.asHcl())

        val hcl = Document.parseHcl("a = 1\n".toByteArray(), consema.hcl.HclProfile.NATIVE_V1)
        assertEquals("hcl.native", hcl.profile().id)
        assertNull(hcl.asXml())
        assertNull(hcl.asPlist())
        assertTrue(hcl.diagnostics().isEmpty())

        val other = Document.parseJson("{}".toByteArray(), consema.json.JsonProfile.StrictV1)
        assertTrue(json.snapshotIdentity() != other.snapshotIdentity())
        assertTrue(json.diagnostics().isEmpty())
    }
}

class ConvertFacadeTest {

    private fun jsonRequest(): MaterializationRequest =
        MaterializationRequest.new(
            ProfileId("json.strict", 1),
            MaterializationStyleId("json.canonical-compact", 1),
        ).withNewline(NewlinePolicy.None)

    private fun tomlRequest(): MaterializationRequest =
        MaterializationRequest.new(
            ProfileId("toml.1.0", 1),
            MaterializationStyleId("toml.canonical-document", 1),
        ).withNewline(NewlinePolicy.Lf)
            .withMappingPolicy(consema.document.MappingPolicy.UniqueStringEntriesToObject)

    private fun jsonBestExact(): consema.json.ProjectionRequest =
        consema.json.ProjectionRequest.builder(consema.json.ProjectionTarget.BestExactCoreV1).build()

    @Test
    fun jsonToTomlKeepsBothStagesAndExactTargetClosure() {
        val source = consema.json.parse(
            "{\"service\":{\"port\":8080,\"enabled\":true}}".toByteArray(),
            consema.json.JsonProfile.StrictV1,
            consema.document.ParseLimits.default,
        )
        val result = convertJson(source, jsonBestExact(), tomlRequest())
        val complete = result as? ConversionResult.Complete ?: fail("complete conversion expected")
        assertEquals(
            "\"service\" = { \"port\" = 8080, \"enabled\" = true }\n",
            String(complete.conversion.document.render()),
        )
        assertEquals(ConversionFidelity.Exact, complete.conversion.report.overallFidelity)
        assertEquals("json.strict", complete.conversion.report.sourceProfile.id)
        assertEquals("toml.1.0", complete.conversion.report.targetProfile.id)
    }

    @Test
    fun tomlToJsonIsExactAndMaterializationFailureHasNoDocument() {
        val source = consema.toml.parse(
            "name = \"api\"\nports = [80, 443]\n".toByteArray(),
            consema.toml.TomlProfile.TOML_1_0_V1,
            consema.document.ParseLimits.default,
        )
        val request = consema.toml.ProjectionRequest.new(consema.toml.ProjectionTarget.BEST_EXACT_CORE_V1)
        val result = convertToml(source, request, jsonRequest())
        val complete = result as? ConversionResult.Complete ?: fail("complete conversion expected")
        assertEquals("{\"name\":\"api\",\"ports\":[80,443]}", String(complete.conversion.document.render()))
        assertEquals(ConversionFidelity.Exact, complete.conversion.report.overallFidelity)

        // A temporal value cannot enter strict JSON: atomic failure, no
        // document.
        val temporal = consema.toml.parse(
            "when = 1979-05-27\n".toByteArray(),
            consema.toml.TomlProfile.TOML_1_0_V1,
            consema.document.ParseLimits.default,
        )
        val failed = convertToml(temporal, request, jsonRequest())
        val failure = failed as? ConversionResult.Failed ?: fail("conversion must fail")
        val materialization = failure.failure as? ConversionFailure.MaterializationFailed
            ?: fail("materialization failure expected")
        assertEquals("core.materialization.unrepresentable@1", materialization.failure.code)
    }

    @Test
    fun jsonCannotMaterializeIntoRecordFormats() {
        val source = consema.json.parse(
            "{\"service\":{\"port\":8080}}".toByteArray(),
            consema.json.JsonProfile.StrictV1,
            consema.document.ParseLimits.default,
        )
        val xmlTarget = MaterializationRequest.new(
            ProfileId("xml.1.0-safe", 1),
            MaterializationStyleId("xml.safe-canonical-document", 1),
        )
        val failed = convertJson(source, jsonBestExact(), xmlTarget)
        val failure = failed as? ConversionResult.Failed ?: fail("conversion must fail")
        val materialization = failure.failure as? ConversionFailure.MaterializationFailed
            ?: fail("materialization failure expected")
        assertEquals("core.materialization.invalid-request@1", materialization.failure.code)
    }

    @Test
    fun plistValueTreeRecordIsConsumedOnlyByThePlistFamily() {
        val source = consema.plist.parse(
            "<plist version=\"1.0\"><dict><key>name</key><string>api</string></dict></plist>"
                .toByteArray(),
            consema.plist.PlistProfile.XmlV1,
            consema.plist.PlistEncodingSelection.ProfileDefault,
            consema.plist.PlistParseLimits.default,
        )
        // The plist projection publishes the exact plist.value-tree@1
        // record; the record-consumption gate fails the JSON target
        // atomically.
        val failed = convertPlist(
            source,
            consema.plist.ProjectionRequest.valueTree(),
            jsonRequest(),
        )
        val failure = failed as? ConversionResult.Failed ?: fail("conversion must fail")
        val materialization = failure.failure as? ConversionFailure.MaterializationFailed
            ?: fail("materialization failure expected")
        assertEquals("core.materialization.invalid-request@1", materialization.failure.code)
        // The owning family still consumes the record exactly.
        val plistTarget = MaterializationRequest.new(
            ProfileId("plist.xml", 1),
            MaterializationStyleId("plist.xml-canonical", 1),
        )
        val complete = convertPlist(source, consema.plist.ProjectionRequest.valueTree(), plistTarget)
        assertTrue(complete is ConversionResult.Complete, "owning family consumes the record")
    }

    /** Wave-4 R41-family fix: the conversion facade no longer drops the
     * projection stage's diagnostics — a failed INI projection carries
     * the attempt's diagnostics (ini.projection.incomplete-document@1
     * for a Recovered document). */
    @Test
    fun iniProjectionFailureCarriesTheAttemptDiagnostics() {
        val source = consema.ini.parse(
            "[section]\nvalue=1\n[section]\nother=2\n".toByteArray(),
            consema.ini.IniProfile.PortableV1,
            limits = consema.ini.IniParseLimits.default,
        )
        assertEquals(
            consema.document.FormationStatus.Recovered,
            source.formationStatus(),
            "duplicate sections make the document Recovered, so projection fails",
        )
        val failed = convertIni(
            source,
            consema.ini.ProjectionRequest.bestExactEntryMapping(),
            jsonRequest(),
        )
        val failure = failed as? ConversionResult.Failed ?: fail("conversion must fail")
        val projection = failure.failure as? ConversionFailure.ProjectionFailed
            ?: fail("projection failure expected, got $failure")
        assertTrue(
            projection.diagnostics.any { it.code == "ini.projection.incomplete-document@1" },
            "the attempt diagnostics must be carried, got ${projection.diagnostics}",
        )
    }

    /** Wave-4 R41-family fix: a failed YAML value projection carries its
     * frozen registered code (yaml.projection.document-cardinality@1 for
     * a multi-document stream) instead of an empty diagnostic list. */
    @Test
    fun yamlProjectionFailureCarriesItsRegisteredCode() {
        val source = consema.yaml.parse(
            "a: 1\n---\nb: 2\n".toByteArray(),
            consema.yaml.YamlProfile.Yaml12CoreV1,
            consema.document.ParseLimits.default,
        )
        assertEquals(2, source.documentCount(), "two documents: value projection fails")
        val failed = convertYaml(
            source,
            consema.yaml.ValueProjectionRequest.bestExactV1(),
            jsonRequest(),
        )
        val failure = failed as? ConversionResult.Failed ?: fail("conversion must fail")
        val projection = failure.failure as? ConversionFailure.ProjectionFailed
            ?: fail("projection failure expected, got $failure")
        assertTrue(
            projection.diagnostics.any { it.code == "yaml.projection.document-cardinality@1" },
            "the yaml.projection.* code must be carried, got ${projection.diagnostics}",
        )
    }
}
