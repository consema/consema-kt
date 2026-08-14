// Materialization tests: all canonical styles, atomic failures, and the
// Windows code-page path.
//
// Authority: RFC 0009 §11 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md
// 435), the vector cases materialization.all-canonical-styles and
// materialization.atomic-failures-and-limits (ini-v1.json:75-86), and
// https://github.com/consema/consema-rs/blob/main/consema-ini/src/materialization.rs (the byte-arbitration
// authority).
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package ini

import consema.core.EntryMappingBuilder
import consema.core.PvEntryMapping
import consema.core.PvString
import consema.document.MaterializationFailureKind
import consema.document.MaterializationLimits
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.ini.IniEncodingSelection
import consema.ini.IniSourceEncoding
import consema.ini.IniWindowsCodePage
import consema.ini.materializationFailureName
import consema.ini.materialize
import consema.ini.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds the nested EntryMapping input of the vector cases. */
private fun nestedEntryMapping(sections: List<Pair<String, List<Pair<String, String>>>>): PvEntryMapping {
    val outer = EntryMappingBuilder()
    for ((section, entries) in sections) {
        val inner = EntryMappingBuilder()
        for ((key, value) in entries) {
            inner.push(PvString(key), PvString(value))
        }
        outer.push(PvString(section), inner.build())
    }
    return outer.build()
}

/** The frozen request of one profile (materialization.rs). */
private fun request(profile: consema.ini.IniProfile): MaterializationRequest =
    when (profile) {
        consema.ini.IniProfile.PortableV1 -> MaterializationRequest.new(
            ProfileId("ini.portable", 1),
            MaterializationStyleId("ini.portable-canonical", 1),
        )
        consema.ini.IniProfile.WindowsV1 -> MaterializationRequest.new(
            ProfileId("ini.windows", 1),
            MaterializationStyleId("ini.windows-canonical", 1),
        )
            .withEncoding(consema.document.SourceEncoding.Utf16Le)
            .withNewline(NewlinePolicy.CrLf)
        consema.ini.IniProfile.PythonConfigParserV1 -> MaterializationRequest.new(
            ProfileId("ini.python-configparser", 1),
            MaterializationStyleId("ini.python-configparser-canonical", 1),
        )
    }

class MaterializationTest {

    /** Vector case materialization.all-canonical-styles (ini-v1.json:75-
     * 81): the golden bytes of all three canonical styles, with UTF-16LE
     * BOM for Windows and exact closure (reparse + reproject reproduce the
     * input). */
    @Test
    fun allCanonicalStyles() {
        val portableInput = nestedEntryMapping(
            listOf("main" to listOf("key" to "value", "empty" to "")),
        )
        val portable = materialize(portableInput, request(consema.ini.IniProfile.PortableV1))
            as MaterializationResult.Complete
        assertEquals(
            "[main]\nkey=value\nempty=\n",
            portable.materialization.document.render().toString(Charsets.UTF_8),
        )

        val windowsInput = nestedEntryMapping(
            listOf("Main" to listOf("quoted" to " value ", "plain" to "value")),
        )
        val windows = materialize(windowsInput, request(consema.ini.IniProfile.WindowsV1))
            as MaterializationResult.Complete
        val windowsDecoded = windows.materialization.document.source().decodedText()
        assertEquals("\uFEFF[Main]\r\nquoted=\" value \"\r\nplain=value\r\n", windowsDecoded)
        assertEquals("Utf16Le", windows.materialization.document.source().encodingFacts.selected.asStr())

        val pythonInput = nestedEntryMapping(
            listOf("DEFAULT" to listOf("raw" to "%(name)s", "multi" to "first\n\nthird")),
        )
        val python = materialize(pythonInput, request(consema.ini.IniProfile.PythonConfigParserV1))
            as MaterializationResult.Complete
        assertEquals(
            "[DEFAULT]\nraw = %(name)s\nmulti = first\n\n    third\n",
            python.materialization.document.render().toString(Charsets.UTF_8),
        )

        assertEquals(consema.document.MaterializationFidelity.Exact, portable.materialization.fidelity)
        assertEquals(consema.document.MaterializationFidelity.Exact, windows.materialization.fidelity)
        assertEquals(consema.document.MaterializationFidelity.Exact, python.materialization.fidelity)
    }

    /** Vector case materialization.atomic-failures-and-limits (ini-v1.json:
 *): a scalar input is Unrepresentable; max_input_nodes,
     * max_output_bytes, max_depth, and max_provenance_entries fail with
     * resource-limit while max_report_entries (an empty report) succeeds. */
    @Test
    fun atomicFailuresAndLimits() {
        val scalar = PvString("x")
        val scalarResult = materialize(scalar, request(consema.ini.IniProfile.PortableV1))
        val scalarFailed = scalarResult as MaterializationResult.Failed
        assertEquals("Unrepresentable", materializationFailureName(scalarFailed.attempt.failure.kind))

        val input = nestedEntryMapping(
            listOf("s" to listOf("key" to "value")),
        )
        val limitSets = listOf(
            MaterializationLimits(maxInputNodes = 1, maxOutputBytes = 64 shl 20, maxDepth = 256, maxReportEntries = 100_000, maxProvenanceEntries = 2_000_000),
            MaterializationLimits(maxInputNodes = 1_000_000, maxOutputBytes = 2, maxDepth = 256, maxReportEntries = 100_000, maxProvenanceEntries = 2_000_000),
            MaterializationLimits(maxInputNodes = 1_000_000, maxOutputBytes = 64 shl 20, maxDepth = 0, maxReportEntries = 100_000, maxProvenanceEntries = 2_000_000),
            MaterializationLimits(maxInputNodes = 1_000_000, maxOutputBytes = 64 shl 20, maxDepth = 256, maxReportEntries = 1, maxProvenanceEntries = 2_000_000),
            MaterializationLimits(maxInputNodes = 1_000_000, maxOutputBytes = 64 shl 20, maxDepth = 256, maxReportEntries = 100_000, maxProvenanceEntries = 1),
        )
        val outcomes = limitSets.map { limits ->
            val result = materialize(
                input,
                request(consema.ini.IniProfile.PortableV1).withLimits(limits),
            )
            when (result) {
                is MaterializationResult.Complete -> "Complete"
                is MaterializationResult.Failed -> {
                    assertEquals(
                        "ResourceLimit",
                        materializationFailureName(result.attempt.failure.kind),
                    )
                    "Failed"
                }
            }
        }
        assertEquals(listOf("Failed", "Failed", "Failed", "Complete", "Failed"), outcomes)
    }

    /** The Windows code page materialization path (materialization.rs
     * 991): cp1252 encodes café with the exact byte 0xE9 and the document
     * reparses under the explicit code page. */
    @Test
    fun windowsCodePageMaterialization() {
        val codePage = IniWindowsCodePage.fromNumber(1252)!!
        val input = nestedEntryMapping(
            listOf("s" to listOf("name" to "café", "name" to "two")),
        )
        val result = materialize(
            input,
            request(consema.ini.IniProfile.WindowsV1),
            codePage,
        )
        val complete = result as MaterializationResult.Complete
        val bytes = complete.materialization.document.render()
        assertTrue(bytes.contains(0xe9.toByte()))
        assertEquals(2, complete.materialization.document.entries().size)

        // Unrepresentable scalars fail the whole operation.
        val unrepresentable = nestedEntryMapping(
            listOf("s" to listOf("name" to "漢")),
        )
        val failed = materialize(unrepresentable, request(consema.ini.IniProfile.WindowsV1), codePage)
            as MaterializationResult.Failed
        assertEquals(
            MaterializationFailureKind.UNSUPPORTED_ENCODING,
            failed.attempt.failure.kind,
        )
    }

    /** RFC 0009 §11: the Windows profile rejects a case-equivalent Object
     * input (it cannot fabricate collisions) while an EntryMapping input
     * keeps its ordered duplicates. */
    @Test
    fun objectInputCannotFabricateWindowsCollisions() {
        val inner = consema.core.ObjectBuilder()
        inner.insert("Name", PvString("one"))
        inner.insert("name", PvString("two"))
        val outer = consema.core.ObjectBuilder()
        outer.insert("s", inner.build())
        val result = materialize(outer.build(), request(consema.ini.IniProfile.WindowsV1))
        assertTrue(result is MaterializationResult.Failed)

        val unique = consema.core.ObjectBuilder()
        unique.insert("Name", PvString("one"))
        val outerUnique = consema.core.ObjectBuilder()
        outerUnique.insert("s", unique.build())
        assertTrue(
            materialize(outerUnique.build(), request(consema.ini.IniProfile.WindowsV1))
                is MaterializationResult.Complete,
        )
    }

    /** RFC 0009 §11: the Python profile rejects a value whose terminal
     * empty line would be normalized away by the frozen parser. */
    @Test
    fun pythonTrailingEmptyLineIsUnrepresentable() {
        val input = nestedEntryMapping(
            listOf("s" to listOf("value" to "line\n")),
        )
        val result = materialize(input, request(consema.ini.IniProfile.PythonConfigParserV1))
        assertTrue(result is MaterializationResult.Failed)
    }

    /** RFC 0009 §11: the Python profile materializes Latin-1 output whose
     * bytes round-trip through the explicit encoding. */
    @Test
    fun pythonLatin1Materialization() {
        val input = nestedEntryMapping(
            listOf("s" to listOf("name" to "café")),
        )
        val request = request(consema.ini.IniProfile.PythonConfigParserV1)
            .withEncoding(consema.document.SourceEncoding.Latin1)
        val result = materialize(input, request) as MaterializationResult.Complete
        assertEquals(
            "Latin1",
            result.materialization.document.source().encodingFacts.selected.asStr(),
        )
        assertTrue(result.materialization.document.render().contains(0xe9.toByte()))
        // The emitted bytes reparse under the same explicit selection.
        val reparsed = parse(
            result.materialization.document.render(),
            consema.ini.IniProfile.PythonConfigParserV1,
            IniEncodingSelection.Explicit(IniSourceEncoding.Latin1),
        )
        assertEquals("café", reparsed.entries()[0].value)
    }
}
