// Materialization transcriptions from conformance/vectors/
// java-properties-v1.json.
//
// The canonical styles java-properties.reader-canonical@1 and
// java-properties.latin1-canonical@1 emit `key=value` in input order with
// deterministic escaping and close through exact reparse and reprojection
// (RFC 0010 §12). Case ids are cited on every test; these tests pin the
// intent and run at the L2 verification gate.

package properties

import consema.core.EntryMappingBuilder
import consema.core.PvString
import consema.core.PortableValue
import consema.document.MaterializationFailureKind
import consema.document.MaterializationLimits
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.properties.WindowsCodePage
import consema.properties.materialize
import kotlin.test.Test
import kotlin.test.assertEquals

class MaterializationTest {

    /** Vector case materialization.canonical-styles-encodings-and-closure
     * (java-properties-v1.json:90-99): the Reader canonical style escapes
     * structure and control characters; the Latin-1 canonical style uses
     * uppercase surrogate-pair escapes without a BOM; UTF-16BE output carries
     * its BOM; cp1252 output is byte-exact; every result closes. */
    @Test
    fun canonicalStylesEncodingsAndClosure() {
        // Reader canonical (UTF-8, LF default).
        val readerRequest = MaterializationRequest.new(
            ProfileId("java-properties.reader", 1),
            MaterializationStyleId("java-properties.reader-canonical", 1),
        )
        val readerInput = mapping(listOf(" a#" to "  v:=!\\\t\u0008值"))
        val reader = materialize(readerInput, readerRequest) as MaterializationResult.Complete
        assertEquals(
            "\\ a\\#=\\ \\ v\\:\\=\\!\\\\\\t\\u0008值\n",
            reader.materialization.document.render().toString(Charsets.UTF_8),
        )
        assertEquals(
            consema.document.MaterializationFidelity.Exact,
            reader.materialization.fidelity,
        )
        assertEquals(0, reader.materialization.report.events().size)
        assertEquals(4, reader.materialization.provenance.entries().size)

        // Latin-1 canonical with CRLF: surrogate-pair escapes and no BOM.
        val latin1Request = MaterializationRequest.new(
            ProfileId("java-properties.latin1", 1),
            MaterializationStyleId("java-properties.latin1-canonical", 1),
        ).withEncoding(SourceEncoding.Latin1).withNewline(NewlinePolicy.CrLf)
        val latin1Input = mapping(listOf("emoji😀" to "café"))
        val latin1 = materialize(latin1Input, latin1Request) as MaterializationResult.Complete
        assertEquals(
            "emoji\\uD83D\\uDE00=caf\\u00E9\\u007F\r\n",
            latin1.materialization.document.render().toString(Charsets.UTF_8),
        )
        assertEquals(
            SourceEncoding.Latin1,
            latin1.materialization.document.source().encodingFacts.selected,
        )
        assertEquals(null, latin1.materialization.document.source().encodingFacts.bom)

        // Reader UTF-16BE with CRLF: BOM + UTF-16BE units.
        val utf16Request = MaterializationRequest.new(
            ProfileId("java-properties.reader", 1),
            MaterializationStyleId("java-properties.reader-canonical", 1),
        ).withEncoding(SourceEncoding.Utf16Be).withNewline(NewlinePolicy.CrLf)
        val utf16 = materialize(mapping(listOf("名" to "值")), utf16Request) as MaterializationResult.Complete
        val utf16Render = utf16.materialization.document.render()
        assertEquals(listOf(0xfe.toByte(), 0xff.toByte()), utf16Render.copyOfRange(0, 2).toList())
        assertEquals(
            SourceEncoding.Utf16Be,
            utf16.materialization.document.source().encodingFacts.selected,
        )
        // The decoded text is BOM + "名=值\r\n".
        assertEquals(
            "\uFEFF名=值\r\n",
            utf16.materialization.document.source().decodedText(),
        )

        // Reader cp1252: "name=café\n" with 0xE9 for é.
        val cp1252Request = MaterializationRequest.new(
            ProfileId("java-properties.reader", 1),
            MaterializationStyleId("java-properties.reader-canonical", 1),
        )
        val cp1252 = materialize(
            mapping(listOf("name" to "café")),
            cp1252Request,
            WindowsCodePage.fromNumber(1252)!!,
        ) as MaterializationResult.Complete
        assertEquals(
            "6e616d653d636166e90a",
            cp1252.materialization.document.render().toHexString(),
        )
    }

    /** Vector case materialization.atomic-failures-and-limits
     * (java-properties-v1.json:100-104): unrepresentable scalars and
     * unsupported encodings fail with their frozen codes; every limit fails
     * atomically except an empty report, which stays Complete. */
    @Test
    fun atomicFailuresAndLimits() {
        val request = MaterializationRequest.new(
            ProfileId("java-properties.reader", 1),
            MaterializationStyleId("java-properties.reader-canonical", 1),
        )
        val value = mapping(listOf("key" to "value"))

        val scalar = materialize(PvString("scalar"), request) as MaterializationResult.Failed
        assertEquals(
            MaterializationFailureKind.UNREPRESENTABLE,
            scalar.attempt.failure.kind,
        )

        val encoding = materialize(
            value,
            MaterializationRequest.new(
                ProfileId("java-properties.latin1", 1),
                MaterializationStyleId("java-properties.latin1-canonical", 1),
            ).withEncoding(SourceEncoding.Utf8),
        ) as MaterializationResult.Failed
        assertEquals(
            MaterializationFailureKind.UNSUPPORTED_ENCODING,
            encoding.attempt.failure.kind,
        )

        val defaults = MaterializationLimits.default
        val limitOutcomes = listOf(
            defaults.copy(maxInputNodes = 1),
            defaults.copy(maxOutputBytes = 2),
            defaults.copy(maxDepth = 0),
            defaults.copy(maxReportEntries = 0),
            defaults.copy(maxProvenanceEntries = 1),
        ).map { limits ->
            val result = materialize(value, request.withLimits(limits))
            when (result) {
                is MaterializationResult.Complete -> "Complete"
                is MaterializationResult.Failed -> "Failed"
            }
        }
        assertEquals(
            listOf("Failed", "Failed", "Failed", "Complete", "Failed"),
            limitOutcomes,
        )
    }
}

/** One String-entry mapping in input order. */
private fun mapping(entries: List<Pair<String, String>>): PortableValue {
    val builder = EntryMappingBuilder()
    for ((key, value) in entries) {
        builder.push(PvString(key), PvString(value))
    }
    return builder.build()
}

/** Lowercase hex of exact bytes. */
private fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val hex = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xff
        hex[i * 2] = digits[value ushr 4]
        hex[i * 2 + 1] = digits[value and 0x0f]
    }
    return String(hex)
}
