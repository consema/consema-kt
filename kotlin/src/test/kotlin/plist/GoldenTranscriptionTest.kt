// Golden transcriptions of conformance/vectors/plist-v1.json cases.
//
// Each test transcribes one vector case (input.source / input.hex /
// expected.*) VERBATIM from conformance/vectors/plist-v1.json and asserts
// the language-neutral facts the Rust/Go differential runners assert
// (https://github.com/consema/consema-rs/blob/main/consema-conformance/src/plist_v1.rs). The case id is cited on
// every test.
//
// This file runs in the verified toolchain gate (kotlin-gates gradlew
// test / the scripts/kotlin-verify-*.ps1 direct path): the toolchain is
// verified and this file is executed.
// NOTE: 行号可能漂移，以 case id 为锚（provisioned conformance/vectors 文件按 pin 复制，re-provision 后行号会变）。

package plist

import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.document.FormationStatus
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NewlinePolicy
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.plist.Document
import consema.plist.EditValue
import consema.plist.PlistProfile
import consema.plist.convertTo
import consema.plist.materialize
import consema.plist.parse
import consema.plist.project
import consema.plist.ProjectionTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** hexOf renders the exact raw source bytes of a document. */
private fun Document.hex(): String = render().joinToString("") { "%02x".format(it) }

/** The full Apple header source of the all-value-types vector. */
private val ALL_VALUE_TYPES_SOURCE =
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

class GoldenTranscriptionTest {

    /** Vector case plist.xml-formation.all-value-types (plist-v1.json:9-45):
     * every XML value element forms Complete with the exact ordered keys and
     * typed values; the render is byte-exact. */
    @Test
    fun xmlFormationAllValueTypes() {
        val document = parse(ALL_VALUE_TYPES_SOURCE.toByteArray(Charsets.UTF_8), PlistProfile.XmlV1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals(ALL_VALUE_TYPES_SOURCE, document.render().toString(Charsets.UTF_8))

        val root = document.root()
        assertEquals("dict", root.kind()?.kindName())
        val entries = root.dictEntries()!!
        assertEquals(
            listOf("name", "count", "ratio", "negative", "enabled", "disabled",
                "payload", "born", "tags", "empty"),
            entries.map { it.key()!!.toUnicode()!! },
        )
        assertEquals(42L, entries[1].value().asInteger())
        assertEquals(-7L, entries[3].value().asInteger())
        assertEquals(1500.0, entries[2].value().asReal()!!.asDouble())
        assertEquals(true, entries[4].value().asBoolean())
        assertEquals(false, entries[5].value().asBoolean())
        assertEquals("010203", entries[6].value().asData()!!.bytes()
            .joinToString("") { "%02x".format(it) })
        assertEquals(694224000.0, entries[7].value().asDateSeconds())
        val tags = entries[8].value().arrayElements()!!
        assertEquals(listOf("string", "dict"),
            tags.map { it.value().kind()?.kindName() })
        assertEquals("", entries[9].value().asString()!!.toUnicode())
    }

    /** Vector case plist.binary-formation.minimal-document (plist-v1.json:
 *): the 42-byte minimum document forms Complete with the exact
     * trailer facts. */
    @Test
    fun binaryFormationMinimalDocument() {
        val hex = "62706c697374303050080000000000000101000000000000000100000000000000000000000000000009"
        val document = parse(hexToBytes(hex), PlistProfile.BinaryV1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals("", document.root().asString()!!.toUnicode())
        val facts = document.binaryFacts()!!
        assertEquals(1L, facts.trailer.numObjects)
        assertEquals(0L, facts.trailer.topObject)
        assertEquals(1, facts.trailer.offsetIntSize)
        assertEquals(1, facts.trailer.objectRefSize)
        assertEquals(9L, facts.trailer.offsetTableOffset)
        assertEquals(0, facts.trailer.sortVersion)
        assertEquals(hex, document.hex())
    }

    /** Vector case plist.binary-formation.all-types-document (plist-v1.json:
 *): all admitted marker kinds form Complete with the exact typed
     * values, object count, and offset table facts. */
    @Test
    fun binaryFormationAllTypesDocument() {
        val hex = "62706c6973743030d90102030405060708090a0d0e0f101112131455617272617954626f6f6c54646174615464617465536633325a6672616374696f6e616c53696e74547265616c53737472a20b0c100110020943010203330000000000000000223f000000333ff8000000000000102a233ff8000000000000526869081b21262b30343f43484c4f5153545861666f717a000000000000010100000000000000150000000000000000000000000000007d"
        val document = parse(hexToBytes(hex), PlistProfile.BinaryV1)

        assertEquals(FormationStatus.Complete, document.formationStatus())
        val root = document.root()
        assertEquals("dict", root.kind()?.kindName())
        val entries = root.dictEntries()!!
        assertEquals(
            listOf("array", "bool", "data", "date", "f32", "fractional", "int", "real", "str"),
            entries.map { it.key()!!.toUnicode()!! },
        )
        assertEquals(42L, entries[6].value().asInteger())
        assertEquals(1.5, entries[7].value().asReal()!!.asDouble())
        assertEquals(0.5, entries[4].value().asReal()!!.asDouble())
        assertEquals("Float32", entries[4].value().asReal()!!.width.name)
        assertEquals("010203", entries[2].value().asData()!!.bytes()
            .joinToString("") { "%02x".format(it) })
        assertEquals(0.0, entries[3].value().asDateSeconds())
        assertEquals(1.5, entries[5].value().asDateSeconds())
        assertEquals(listOf(1L, 2L), entries[0].value().arrayElements()!!
            .map { it.value().asInteger()!! })
        assertEquals("hi", entries[8].value().asString()!!.toUnicode())
        val facts = document.binaryFacts()!!
        assertEquals(21L, facts.trailer.numObjects)
        assertEquals(1, facts.trailer.offsetIntSize)
        assertEquals(1, facts.trailer.objectRefSize)
        assertEquals(125L, facts.trailer.offsetTableOffset)
    }

    /** Vector case plist.materialization.xml-canonical-text (plist-v1.json:
 *): the golden XML render, byte-exact. */
    @Test
    fun materializationXmlCanonicalText() {
        val record = valueTreeRecord(
            "name" to PvString("value"),
            "count" to PvInteger(java.math.BigInteger.valueOf(42)),
            "ratio" to PvBinaryFloat64.fromFloat(1.5),
            "enabled" to PvBoolean(true),
            "disabled" to PvBoolean(false),
            "payload" to PvObject(listOf(consema.core.Entry("hex", PvString("010203")))),
            "created" to PvObject(
                listOf(
                    consema.core.Entry("epoch", PvString("2001-01-01T00:00:00Z")),
                    consema.core.Entry("seconds", PvBinaryFloat64.fromFloat(694224000.0)),
                ),
            ),
            "title" to PvString("a & b < c"),
            "tags" to consema.core.PvArray(
                listOf(PvString("a"), PvString("b")),
            ),
        )
        val request = MaterializationRequest.new(
            ProfileId("plist.xml", 1),
            MaterializationStyleId("plist.xml-canonical", 1),
        )
        val result = materialize(record, request)

        val complete = result as? MaterializationResult.Complete ?: error("materialization failed")
        val expected =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n" +
                "    <dict>\n" +
                "        <key>name</key>\n" +
                "        <string>value</string>\n" +
                "        <key>count</key>\n" +
                "        <integer>42</integer>\n" +
                "        <key>ratio</key>\n" +
                "        <real>1.5</real>\n" +
                "        <key>enabled</key>\n" +
                "        <true/>\n" +
                "        <key>disabled</key>\n" +
                "        <false/>\n" +
                "        <key>payload</key>\n" +
                "        <data>AQID</data>\n" +
                "        <key>created</key>\n" +
                "        <date>2023-01-01T00:00:00Z</date>\n" +
                "        <key>title</key>\n" +
                "        <string>a &amp; b &lt; c</string>\n" +
                "        <key>tags</key>\n" +
                "        <array>\n" +
                "            <string>a</string>\n" +
                "            <string>b</string>\n" +
                "        </array>\n" +
                "    </dict>\n" +
                "</plist>\n"
        assertEquals(expected, complete.materialization.document.render().toString(Charsets.UTF_8))
    }

    /** Vector case plist.materialization.binary-canonical-hex (plist-v1.json:
 *): the golden binary object table, byte-exact. */
    @Test
    fun materializationBinaryCanonicalHex() {
        val record = valueTreeRecord(
            "name" to PvString("value"),
            "count" to PvInteger(java.math.BigInteger.valueOf(42)),
            "ratio" to PvBinaryFloat64.fromFloat(1.5),
            "enabled" to PvBoolean(true),
            "disabled" to PvBoolean(false),
            "payload" to PvObject(listOf(consema.core.Entry("hex", PvString("010203")))),
            "created" to PvObject(
                listOf(
                    consema.core.Entry("epoch", PvString("2001-01-01T00:00:00Z")),
                    consema.core.Entry("seconds", PvBinaryFloat64.fromFloat(694224000.0)),
                ),
            ),
            "title" to PvString("a & b < c"),
            "tags" to consema.core.PvArray(
                listOf(PvString("a"), PvString("b")),
            ),
        )
        val request = MaterializationRequest.new(
            ProfileId("plist.binary", 1),
            MaterializationStyleId("plist.binary-canonical", 1),
        ).withEncoding(SourceEncoding.Binary).withNewline(NewlinePolicy.None)
        val result = materialize(record, request)

        val complete = result as? MaterializationResult.Complete ?: error("materialization failed")
        val expected = "62706c6973743030d90102030405060708090a0b0c0d0e0f101112546e616d6555636f756e7455726174696f57656e61626c65645864697361626c6564577061796c6f61645763726561746564557469746c6554746167735576616c7565102a233ff80000000000000908430102033341c4b08240000000596120262062203c2063a2131451615162081b20262c343d454d53585e60696a6b6f788285870000000000000101000000000000001500000000000000000000000000000089"
        assertEquals(expected, complete.materialization.document.render()
            .joinToString("") { "%02x".format(it) })
    }

    /** Vector case plist.xml-formation.doctype-violations (plist-v1.json:
 *): a wrong DOCTYPE name, an internal subset, and a SYSTEM-only
     * DOCTYPE each recover with their frozen codes. */
    @Test
    fun xmlFormationDoctypeViolations() {
        val wrongName = "<!DOCTYPE wrong PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\"><string>ok</string></plist>"
        val internalSubset = "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\" [<!ENTITY x \"y\">]>\n<plist version=\"1.0\"><string>ok</string></plist>"
        val systemOnly = "<!DOCTYPE plist SYSTEM \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\"><string>ok</string></plist>"

        val first = parse(wrongName.toByteArray(Charsets.UTF_8), PlistProfile.XmlV1)
        assertEquals(FormationStatus.Recovered, first.formationStatus())
        assertTrue(first.diagnostics().any { it.code == "plist.parse.doctype@1" })

        val second = parse(internalSubset.toByteArray(Charsets.UTF_8), PlistProfile.XmlV1)
        assertEquals(FormationStatus.Recovered, second.formationStatus())
        assertTrue(second.diagnostics().any { it.code == "plist.parse.doctype-subset@1" })

        val third = parse(systemOnly.toByteArray(Charsets.UTF_8), PlistProfile.XmlV1)
        assertEquals(FormationStatus.Recovered, third.formationStatus())
        assertTrue(third.diagnostics().any { it.code == "plist.parse.doctype@1" })
    }

    /** Vector case plist.xml-formation.integer-matrix (plist-v1.json:179-
     * 236): decimal/hex signs, whitespace after the sign, leading zeros, and
     * the signed 64-bit bounds. */
    @Test
    fun xmlFormationIntegerMatrix() {
        val cases = listOf(
            "<integer>-42</integer>" to -42L,
            "<integer>0x2A</integer>" to 42L,
            "<integer>+ 7</integer>" to 7L,
            "<integer>007</integer>" to 7L,
        )
        for ((element, expected) in cases) {
            val document = parse(
                "<plist version=\"1.0\">$element</plist>".toByteArray(Charsets.UTF_8),
                PlistProfile.XmlV1,
            )
            assertEquals(FormationStatus.Complete, document.formationStatus(), element)
            assertEquals(expected, document.root().asInteger(), element)
        }
        for (element in listOf("<integer>12a</integer>",
                "<integer>9223372036854775808</integer>",
                "<integer>-9223372036854775809</integer>")
        ) {
            val document = parse(
                "<plist version=\"1.0\">$element</plist>".toByteArray(Charsets.UTF_8),
                PlistProfile.XmlV1,
            )
            assertEquals(FormationStatus.Recovered, document.formationStatus(), element)
            assertTrue(document.diagnostics().any { it.code == "plist.parse.integer@1" }, element)
        }
    }

    /** Vector case plist.query.typed-accessors (plist-v1.json:949-1042): the
     * typed accessors complete on a matching kind and fail with
     * `plist.query.type-mismatch@1` on a mismatch. */
    @Test
    fun queryTypedAccessors() {
        val source = "<plist version=\"1.0\"><dict><key>count</key><integer>42</integer><key>created</key><date>2023-01-01T00:00:00Z</date><key>name</key><string>x</string></dict></plist>"
        val document = parse(source.toByteArray(Charsets.UTF_8), PlistProfile.XmlV1)

        val integer = runNativeQuery(
            document,
            listOf("plist.document-root", "plist.dict-entries",
                "plist.dict-key-equals", "plist.dict-entry-value",
                "plist.value-type-is", "plist.value-as-integer"),
            mapOf(
                "plist.dict-key-equals" to mapOf("key" to PvString("count")),
                "plist.value-type-is" to mapOf("kind" to PvString("integer")),
            ),
        )
        val integerCompleted = integer as? consema.plist.PlistQueryOutcome.Completed
            ?: error("integer query must complete")
        val match = integerCompleted.matches.single() as consema.plist.PlistMatch.Value
        assertEquals("integer", match.kind?.kindName())
        assertEquals(42L, (match.typed as consema.plist.TypedValue.Integer).value)

        val date = runNativeQuery(
            document,
            listOf("plist.document-root", "plist.dict-entries",
                "plist.dict-key-equals", "plist.dict-entry-value",
                "plist.value-as-date"),
            mapOf("plist.dict-key-equals" to mapOf("key" to PvString("created"))),
        )
        val dateCompleted = date as? consema.plist.PlistQueryOutcome.Completed
            ?: error("date query must complete")
        val dateMatch = dateCompleted.matches.single() as consema.plist.PlistMatch.Value
        assertEquals("date", dateMatch.kind?.kindName())
        assertEquals(694224000.0, (dateMatch.typed as consema.plist.TypedValue.Date).seconds)

        val mismatch = runNativeQuery(
            document,
            listOf("plist.document-root", "plist.dict-entries",
                "plist.dict-key-equals", "plist.dict-entry-value",
                "plist.value-as-string"),
            mapOf("plist.dict-key-equals" to mapOf("key" to PvString("count"))),
        )
        val failed = mismatch as? consema.plist.PlistQueryOutcome.Failed
            ?: error("mismatch query must fail")
        assertEquals("plist.query.type-mismatch@1", failed.code)
    }

    /** Vector case plist.query.binary-structure (plist-v1.json:1044-1089):
     * the object/offset/trailer facts with exact byte spans. */
    @Test
    fun queryBinaryStructure() {
        val hex = "62706c6973743030d1010251611001080b0d000000000000010100000000000000030000000000000000000000000000000f"
        val document = parse(hexToBytes(hex), PlistProfile.BinaryV1)

        // The object-table operator exposes the object facts (vector
        // object_offsets [8, 11, 13] and markers [d1, 51, 10]).
        val objectDefinition = consema.protocol.QueryDefinition(
            consema.protocol.QueryDomain("plist.binary-structure-query", 1),
        ).withExpression(
            consema.protocol.QueryExpression(consema.protocol.ExpressionKind.Input)
                .then(consema.protocol.OperatorCall("plist.object-table", 1)),
        )
        val objectOutcome = consema.plist.executePlistBinaryQuery(bind(objectDefinition), document)
        val objectCompleted = objectOutcome as? consema.plist.PlistQueryOutcome.Completed
            ?: error("object-table query must complete")
        val objects = objectCompleted.matches.filterIsInstance<consema.plist.PlistBinaryMatch.Object>()
        assertEquals(listOf(8, 11, 13), objects.map { it.offset })
        assertEquals(listOf("d1", "51", "10"), objects.map { "%02x".format(it.marker) })

        // The top-object chain resolves the root object and its refs
        // (vector top_marker "d1" and top_refs [1, 2]).
        val definition = consema.protocol.QueryDefinition(
            consema.protocol.QueryDomain("plist.binary-structure-query", 1),
        ).withExpression(
            consema.protocol.QueryExpression(consema.protocol.ExpressionKind.Input)
                .then(consema.protocol.OperatorCall("plist.object-table", 1))
                .then(consema.protocol.OperatorCall("plist.offset-table", 1))
                .then(consema.protocol.OperatorCall("plist.trailer-facts", 1))
                .then(consema.protocol.OperatorCall("plist.top-object", 1)),
        )
        val executable = bind(definition)
        val outcome = consema.plist.executePlistBinaryQuery(executable, document)

        val completed = outcome as? consema.plist.PlistQueryOutcome.Completed
            ?: error("binary structure query must complete")
        assertEquals(3L, document.binaryFacts()!!.trailer.numObjects)
        assertEquals(0L, document.binaryFacts()!!.trailer.topObject)
        assertEquals(15L, document.binaryFacts()!!.trailer.offsetTableOffset)
        val top = completed.matches.filterIsInstance<consema.plist.PlistBinaryMatch.TopObject>()
        assertEquals("d1", "%02x".format(top.single().topObject.marker))
        assertEquals(listOf(1, 2), top.single().refs.map { it.second })
    }

    /** Vector case plist.query.dict-entries-order (plist-v1.json:918-947):
     * duplicate keys keep physical association order with one duplicate
     * group. */
    @Test
    fun queryDictEntriesOrder() {
        val source = "<plist version=\"1.0\"><dict><key>a</key><integer>1</integer><key>b</key><array><string>x</string></array><key>a</key><integer>2</integer></dict></plist>"
        val document = parse(source.toByteArray(Charsets.UTF_8), PlistProfile.XmlV1)

        val outcome = runNativeQuery(
            document,
            listOf("plist.document-root", "plist.dict-entries"),
            emptyMap(),
        )
        val completed = outcome as? consema.plist.PlistQueryOutcome.Completed
            ?: error("query must complete")
        val entries = completed.matches.filterIsInstance<consema.plist.PlistMatch.DictEntry>()
        assertEquals(listOf("a", "b", "a"), entries.map { keyTextOf(document, it) })
        assertEquals(
            listOf("integer", "array", "integer"),
            entries.map { it.value.let { v -> documentRootKind(document, v) } },
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun keyTextOf(document: Document, entry: consema.plist.PlistMatch.DictEntry): String =
        document.dictEntryEntity(entry.index).let { entity ->
            (document.valueEntity(entity.keyIndex).native as consema.plist.NativeValue.StringV)
                .string.toUnicode()!!
        }

    private fun documentRootKind(document: Document, node: consema.document.NodeRef): String {
        val index = node.index.toInt()
        return document.valueEntity(index).native?.kind()?.kindName() ?: "?"
    }

    private fun runNativeQuery(
        document: Document,
        operators: List<String>,
        arguments: Map<String, Map<String, consema.core.PortableValue>>,
    ): consema.plist.PlistQueryOutcome<consema.plist.PlistMatch> {
        var expression = consema.protocol.QueryExpression(consema.protocol.ExpressionKind.Input)
        for (operator in operators) {
            val call = consema.protocol.OperatorCall(operator, 1)
            for ((name, value) in arguments[operator] ?: emptyMap()) {
                call.withArgument(name, value)
            }
            expression = expression.then(call)
        }
        val definition = consema.protocol.QueryDefinition(
            consema.protocol.QueryDomain("plist.native-semantic-query", 1),
        ).withExpression(expression)
        return consema.plist.executePlistNativeQuery(bind(definition), document)
    }

    private fun bind(definition: consema.protocol.QueryDefinition): consema.protocol.ExecutableQuery {
        val validated = definition.validate()
        val capabilities = consema.protocol.CapabilitySet()
        capabilities.insert(consema.protocol.CapabilityId("core.query.ordered-results", 1))
        return consema.protocol.ExecutableQuery.bind(validated, capabilities)
    }

    /** Builds one `plist.value-tree@1` record with the fixed record/root
     * spelling (RFC 0013 §9; materialization.rs). */
    private fun valueTreeRecord(vararg rootEntries: Pair<String, consema.core.PortableValue>):
        consema.core.PortableValue = PvObject(
        listOf(
            consema.core.Entry("record", PvString("plist.value-tree@1")),
            consema.core.Entry("root", PvObject(rootEntries.map { consema.core.Entry(it.first, it.second) })),
        ),
    )

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }
}
