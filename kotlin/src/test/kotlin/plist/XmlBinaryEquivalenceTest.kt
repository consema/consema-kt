// XML/binary equivalence intent tests: one value model across the two
// representations (RFC 0013 §7), projection -> materialization round trips
// with reparse closure, cross-representation conversion, and preserved
// shared object identity.
//
// Data authority:
//   - RFC 0013 §7 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md:512-538): the
//     XML and binary profiles are distinct formats with one value model;
//     conversion is exact when every native fact is expressible and fails
//     atomically otherwise; "XML/binary exact round trip" means native-model
//     equality across a chain of conversions with every representation
//     change reported.
//   - conformance/vectors/plist-v1.json cases plist.conversion.*,
//     plist.binary-formation.shared-reference, plist.materialization.*.
//
// This file runs in the verified toolchain gate (kotlin-gates gradlew
// test / the scripts/kotlin-verify-*.ps1 direct path): the toolchain is
// verified and this file is executed.

package plist

import consema.core.equal
import consema.core.PortableValue
import consema.document.FormationStatus
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.plist.Document
import consema.plist.PlistProfile
import consema.plist.convertTo
import consema.plist.materialize
import consema.plist.parse
import consema.plist.project
import consema.plist.ProjectionRequest
import consema.plist.ProjectionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlBinaryEquivalenceTest {

    private val source =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
            "<plist version=\"1.0\">\n" +
            "    <dict>\n" +
            "        <key>name</key><string>Consema</string>\n" +
            "        <key>count</key><integer>0x2A</integer>\n" +
            "        <key>ratio</key><real>1.5e3</real>\n" +
            "        <key>negative</key><integer>-7</integer>\n" +
            "        <key>enabled</key><true/>\n" +
            "        <key>disabled</key><false/>\n" +
            "        <key>payload</key><data>AQID</data>\n" +
            "        <key>born</key><date>2023-01-01T00:00:00Z</date>\n" +
            "        <key>tags</key><array><string>a</string><dict/></array>\n" +
            "        <key>empty</key><string></string>\n" +
            "    </dict>\n" +
            "</plist>\n"

    /** Projection -> binary materialization -> reparse -> projection is
     * native-model equal (RFC 0013 §10.3 reparse closure). */
    @Test
    fun xmlProjectionMaterializesBinaryEqual() {
        val xml = parse(source.toByteArray(Charsets.UTF_8), PlistProfile.XmlV1)
        assertEquals(FormationStatus.Complete, xml.formationStatus())

        val projected = project(xml, ProjectionRequest.valueTree())
            as? ProjectionResult.Complete ?: error("projection failed")
        val binaryRequest = MaterializationRequest.new(
            ProfileId("plist.binary", 1),
            MaterializationStyleId("plist.binary-canonical", 1),
        ).withEncoding(SourceEncoding.Binary).withNewline(NewlinePolicy.None)
        val materialized = materialize(projected.projection.value, binaryRequest)
            as? MaterializationResult.Complete ?: error("materialization failed")
        assertEquals(FormationStatus.Complete, materialized.materialization.document.formationStatus())

        val reparsed = parse(
            materialized.materialization.document.render(),
            PlistProfile.BinaryV1,
        )
        val reprojected = project(reparsed, ProjectionRequest.valueTree())
            as? ProjectionResult.Complete ?: error("reprojection failed")
        assertTrue(equal(projected.projection.value, reprojected.projection.value))
    }

    /** Vector case plist.conversion.xml-to-binary-round-trip (plist-v1.json:
     * 1566-1590): conversion reports the representation change and the
     * round trip preserves the exact dict keys. */
    @Test
    fun xmlToBinaryConversionRoundTrip() {
        val xml = parse(source.toByteArray(Charsets.UTF_8), PlistProfile.XmlV1)
        val converted = xml.convertTo(PlistProfile.BinaryV1)

        assertTrue(converted.report.representationChanged())
        assertEquals(FormationStatus.Complete, converted.document.formationStatus())

        val back = converted.document.convertTo(PlistProfile.XmlV1)
        assertTrue(back.report.representationChanged())
        val keys = back.document.root().dictEntries()!!.map { it.key()!!.toUnicode()!! }
        assertEquals(
            listOf("name", "count", "ratio", "negative", "enabled", "disabled",
                "payload", "born", "tags", "empty"),
            keys,
        )
        val first = project(xml, ProjectionRequest.valueTree())
            as? ProjectionResult.Complete ?: error("projection failed")
        val second = project(back.document, ProjectionRequest.valueTree())
            as? ProjectionResult.Complete ?: error("projection failed")
        assertTrue(equal(first.projection.value, second.projection.value))
    }

    /** Vector case plist.conversion.binary-to-xml-round-trip (plist-v1.json:
     * 1592-1610): the dict keys survive the binary -> XML conversion. */
    @Test
    fun binaryToXmlConversionRoundTrip() {
        val hex = "62706c6973743030517810020908a20203233ff80000000000005161516251635164d40607080900010405080a0c0d0e111a1c1e20220000000000000101000000000000000b000000000000000a000000000000002b"
        val binary = parse(hexToBytes(hex), PlistProfile.BinaryV1)
        assertEquals(FormationStatus.Complete, binary.formationStatus())

        val converted = binary.convertTo(PlistProfile.XmlV1)
        assertTrue(converted.report.representationChanged())
        assertEquals(
            listOf("a", "b", "c", "d"),
            converted.document.root().dictEntries()!!.map { it.key()!!.toUnicode()!! },
        )
        val first = project(binary, ProjectionRequest.valueTree())
            as? ProjectionResult.Complete ?: error("projection failed")
        val second = project(converted.document, ProjectionRequest.valueTree())
            as? ProjectionResult.Complete ?: error("projection failed")
        assertTrue(equal(first.projection.value, second.projection.value))
    }

    /** Vector case plist.conversion.uid-inexpressible-to-xml (plist-v1.json:
     * 1612-1622): a UID document fails binary -> XML conversion atomically
     * with plist.conversion.inexpressible@1. */
    @Test
    fun uidConversionToXmlFailsAtomically() {
        val hex = "62706c6973743030800508000000000000010100000000000000010000000000000000000000000000000a"
        val binary = parse(hexToBytes(hex), PlistProfile.BinaryV1)
        assertEquals(FormationStatus.Complete, binary.formationStatus())

        try {
            binary.convertTo(PlistProfile.XmlV1)
            error("conversion must fail")
        } catch (e: consema.plist.ConversionFailureException) {
            assertEquals("plist.conversion.inexpressible@1", e.code)
        }
    }

    /** Vector case plist.binary-formation.shared-reference (plist-v1.json:
     * 685-702): one source object referenced by several containers is one
     * native node with multiple owners (RFC 0013 §6). */
    @Test
    fun binarySharedIdentityPreserved() {
        val hex = "62706c6973743030a3010102d103045178516b5176080c0f11130000000000000101000000000000000500000000000000000000000000000015"
        val binary = parse(hexToBytes(hex), PlistProfile.BinaryV1)
        assertEquals(FormationStatus.Complete, binary.formationStatus())

        val root = binary.root()
        assertEquals("array", root.kind()?.kindName())
        val elements = root.arrayElements()!!
        // Refs of the top: [1, 1, 2] (shared object 1).
        assertEquals(3, elements.size)
        assertEquals(elements[0].value().rawIndex(), elements[1].value().rawIndex())
        assertTrue(elements[0].value().isShared())
        assertTrue(!elements[2].value().isShared())
        val facts = binary.binaryFacts()!!
        assertEquals(5L, facts.trailer.numObjects)
    }

    /** The materialized binary document reparses Complete and the projected
     * record is itself representable (reparse closure, RFC 0013 §10.3). */
    @Test
    fun binaryMaterializationClosure() {
        val record = goldenRecord()
        val request = MaterializationRequest.new(
            ProfileId("plist.binary", 1),
            MaterializationStyleId("plist.binary-canonical", 1),
        ).withEncoding(SourceEncoding.Binary).withNewline(NewlinePolicy.None)
        val materialized = materialize(record, request)
            as? MaterializationResult.Complete ?: error("materialization failed")
        val document = materialized.materialization.document
        assertEquals(FormationStatus.Complete, document.formationStatus())

        val projected = project(document, ProjectionRequest.valueTree())
            as? ProjectionResult.Complete ?: error("projection failed")
        // The projected value-tree record is itself materializable.
        val second = materialize(projected.projection.value, request)
            as? MaterializationResult.Complete ?: error("closure materialization failed")
        assertEquals(FormationStatus.Complete, second.materialization.document.formationStatus())
        val secondProjected = project(second.materialization.document, ProjectionRequest.valueTree())
            as? ProjectionResult.Complete ?: error("closure reprojection failed")
        assertTrue(equal(projected.projection.value, secondProjected.projection.value))
    }

    /** Vector case plist.materialization.fractional-date-policy (plist-v1.json:
     * 1314-1355): a fractional-second date fails without the explicit
     * TruncateWithReport policy and truncates with it. */
    @Test
    fun fractionalDatePolicy() {
        val record = consema.core.PvObject(
            listOf(
                consema.core.Entry("record", consema.core.PvString("plist.value-tree@1")),
                consema.core.Entry(
                    "root",
                    consema.core.PvObject(
                        listOf(
                            consema.core.Entry(
                                "t",
                                consema.core.PvObject(
                                    listOf(
                                        consema.core.Entry("epoch", consema.core.PvString("2001-01-01T00:00:00Z")),
                                        consema.core.Entry("seconds", consema.core.PvBinaryFloat64.fromFloat(1.5)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val request = MaterializationRequest.new(
            ProfileId("plist.xml", 1),
            MaterializationStyleId("plist.xml-canonical", 1),
        )

        val withoutPolicy = materialize(record, request)
        val failed = withoutPolicy as? MaterializationResult.Failed
            ?: error("fractional date must fail without policy")
        // The shared exception carries the core code; the family mapping
        // restores the frozen plist code the vectors assert
        // (materialization.rs:81-88; plist_v1.rs:1946-1957).
        assertEquals(
            "plist.materialization.fractional-date@1",
            consema.plist.materializationFailureCode(failed.attempt.failure),
        )

        val withPolicy = consema.core.PvObject(
            listOf(
                consema.core.Entry("record", consema.core.PvString("plist.value-tree@1")),
                consema.core.Entry("truncate_policy", consema.core.PvString("TruncateWithReport")),
                consema.core.Entry(
                    "root",
                    (record as consema.core.PvObject).entries().first { it.key == "root" }.value,
                ),
            ),
        )
        val truncated = materialize(withPolicy, request)
            as? MaterializationResult.Complete ?: error("truncation must succeed")
        val render = truncated.materialization.document.render().toString(Charsets.UTF_8)
        assertTrue("<date>2001-01-01T00:00:01Z</date>" in render)
        assertEquals(1, truncated.materialization.document.diagnostics()
            .count { it.code == "plist.materialization.fractional-date@1" })
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun goldenRecord(): PortableValue {
        val root = consema.core.PvObject(
            listOf(
                consema.core.Entry("name", consema.core.PvString("value")),
                consema.core.Entry("count", consema.core.PvInteger(java.math.BigInteger.valueOf(42))),
                consema.core.Entry("ratio", consema.core.PvBinaryFloat64.fromFloat(1.5)),
                consema.core.Entry("enabled", consema.core.PvBoolean(true)),
                consema.core.Entry("disabled", consema.core.PvBoolean(false)),
                consema.core.Entry(
                    "payload",
                    consema.core.PvObject(listOf(consema.core.Entry("hex", consema.core.PvString("010203")))),
                ),
                consema.core.Entry(
                    "created",
                    consema.core.PvObject(
                        listOf(
                            consema.core.Entry("epoch", consema.core.PvString("2001-01-01T00:00:00Z")),
                            consema.core.Entry("seconds", consema.core.PvBinaryFloat64.fromFloat(694224000.0)),
                        ),
                    ),
                ),
                consema.core.Entry("title", consema.core.PvString("a & b < c")),
                consema.core.Entry(
                    "tags",
                    consema.core.PvArray(listOf(consema.core.PvString("a"), consema.core.PvString("b"))),
                ),
            ),
        )
        return consema.core.PvObject(
            listOf(
                consema.core.Entry("record", consema.core.PvString("plist.value-tree@1")),
                consema.core.Entry("root", root),
            ),
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }
}
