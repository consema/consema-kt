// Transcriptions of the conformance/vectors/hcl-v1.json
// hcl.materialization.* cases (:1153-1411, :1973-2045): the canonical
// document style, the reparse closure, the unrepresentable matrix, the
// typed-member form, and the tfvars canonical style.

package hcl

import consema.core.PortableValue
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.document.MaterializationRequest
import consema.document.MaterializationStyleId
import consema.document.ProfileId
import consema.hcl.HclMaterializationResult
import consema.hcl.materialize
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MaterializationTest {

    private fun request(profile: String): MaterializationRequest =
        MaterializationRequest.new(
            ProfileId(profile, 1),
            MaterializationStyleId("hcl.canonical-document", 1),
        )

    private fun complete(result: HclMaterializationResult): consema.hcl.HclDocument {
        val completed = assertIs<HclMaterializationResult.Complete>(result)
        return completed.materialization.document
    }

    private fun render(result: HclMaterializationResult): String =
        complete(result).render().toString(Charsets.UTF_8)

    /** Vector case hcl.materialization.canonical-document (hcl-v1.json:
 *): every scalar family, escapes, tuples/objects with the
     * one-item-per-line layout, empty constructors, and blocks with quoted
     * labels render byte-exact. */
    @Test
    fun canonicalDocument() {
        val record = bodyRecord(
            listOf(
                attributeItem("name", stringValue("hello")),
                attributeItem("escaped", stringValue("a\nb\t\"c\\d")),
                attributeItem("count", integerValue(42)),
                attributeItem("ratio", realValue("1.5")),
                attributeItem("enabled", booleanValue(true)),
                attributeItem("nothing", nullValue()),
                attributeItem("tags", tupleValue(listOf(stringValue("a"), stringValue("b")))),
                attributeItem("labels", objectValue(listOf("env" to stringValue("prod")))),
                attributeItem("empty_tuple", tupleValue(emptyList())),
                attributeItem("empty_obj", objectValue(emptyList())),
                blockItem(
                    "server",
                    listOf("web", "1"),
                    bodyRecord(listOf(attributeItem("port", integerValue(8080)))),
                ),
            ),
        )
        val result = materialize(record, request("hcl.native"))
        assertEquals(Golden.CANONICAL_RENDER, render(result))
        // The reparsed native model matches the promised semantics
        // (RFC 0014 §9 closure).
        val document = complete(result)
        assertEquals(consema.document.FormationStatus.Complete, document.formationStatus())
    }

    /** Vector case hcl.materialization.reparse-closure (hcl-v1.json:1285-
     * 1329): `hcl.expression@1` values emit their canonical text and the
     * closure holds (fingerprint match). */
    @Test
    fun reparseClosure() {
        val record = bodyRecord(
            listOf(
                attributeItem("derived", expressionValue("binary", "1 + 2")),
                attributeItem("big", integerValue(1000)),
                attributeItem("small", realValue("1.5")),
            ),
        )
        val result = materialize(record, request("hcl.native"))
        assertEquals(
            "derived = 1 + 2\nbig = 1000\nsmall = 1.5\n",
            render(result),
        )
    }

    /** Vector case hcl.materialization.unrepresentable (hcl-v1.json:1331-
     * 1411): the tfvars block restriction, a wrong record identity, and
     * the native block rendering. */
    @Test
    fun unrepresentable() {
        val block = blockItem(
            "server",
            listOf("x"),
            bodyRecord(listOf(attributeItem("a", integerValue(1)))),
        )

        // tfvars rejects any record containing a block (RFC 0014 §5, §9).
        val tfvars = materialize(bodyRecord(listOf(block)), request("hcl.tfvars"))
        val tfvarsFailed = assertIs<HclMaterializationResult.Failed>(tfvars)
        assertEquals("hcl.materialization.unrepresentable@1", tfvarsFailed.failure.code)

        // A wrong record identity is the stable "invalid-record" failure
        // (hcl-v1.json sample 2).
        val wrongRecord = PvObject(
            listOf(
                consema.core.Entry("record", PvString("hcl.something-else@1")),
                consema.core.Entry("items", consema.core.PvArray(emptyList())),
            ),
        )
        val wrong = materialize(wrongRecord, request("hcl.native"))
        val wrongFailed = assertIs<HclMaterializationResult.Failed>(wrong)
        assertTrue(
            (wrongFailed.failure as? consema.hcl.HclMaterializationFailure.Unrepresentable)?.reason == "invalid-record",
            "failure: ${wrongFailed.failure}",
        )

        // The same block is representable under hcl.native@1.
        val native = materialize(bodyRecord(listOf(block)), request("hcl.native"))
        assertEquals("server \"x\" {\n  a = 1\n}\n", render(native))
    }

    /** Vector case hcl.materialization.typed-member-form (hcl-v1.json:1413-
     * 1460): the raw typed member spelling the projection publishes is
     * accepted and materializes identical bytes. */
    @Test
    fun typedMemberForm() {
        val record = bodyRecord(
            listOf(
                attributeItem("name", PvString("hello")),
                attributeItem("count", PvInteger(BigInteger("42"))),
                attributeItem("ratio", PvDecimal.of(BigInteger("15"), BigInteger("-1"))),
                attributeItem("enabled", consema.core.PvBoolean(true)),
                attributeItem("nothing", PvNull),
                attributeItem("tags", consema.core.PvArray(listOf(PvString("a"), PvString("b")))),
            ),
        )
        val result = materialize(record, request("hcl.native"))
        assertEquals(
            "name = \"hello\"\ncount = 42\nratio = 1.5\nenabled = true\nnothing = null\n" +
                "tags = [\n  \"a\",\n  \"b\"\n]\n",
            render(result),
        )
    }

    /** Vector case hcl.materialization.tfvars-canonical (hcl-v1.json:1973-
     * 2045): the tfvars canonical style with closure. */
    @Test
    fun tfvarsCanonical() {
        val record = bodyRecord(
            listOf(
                attributeItem("region", stringValue("us-east-1")),
                attributeItem("count", integerValue(3)),
                attributeItem("ratio", realValue("0.5")),
                attributeItem("tags", tupleValue(listOf(stringValue("a"), stringValue("b")))),
                attributeItem("labels", objectValue(listOf("env" to stringValue("prod")))),
            ),
        )
        val result = materialize(record, request("hcl.tfvars"))
        assertEquals(
            "region = \"us-east-1\"\ncount = 3\nratio = 0.5\n" +
                "tags = [\n  \"a\",\n  \"b\"\n]\nlabels = {\n  env = \"prod\"\n}\n",
            render(result),
        )
        assertEquals(consema.document.FormationStatus.Complete, complete(result).formationStatus())
    }

    /** The projection -> materialization round trip of RFC 0014 §9: the
     * projected record (raw typed members) materializes to a document whose
     * reparse projects identically. */
    @Test
    fun projectionMaterializationRoundTrip() {
        val document = consema.hcl.parse(
            Golden.PROJECTION_RECORD.toByteArray(Charsets.UTF_8),
            consema.hcl.HclProfile.NATIVE_V1,
        )
        val projected = consema.hcl.project(
            document,
            consema.hcl.ProjectionTarget.BodyV1,
            consema.hcl.ExpressionPolicy.Default,
        )
        val value = (projected as consema.hcl.ProjectionResult.Complete).projection.value
        val result = materialize(value, request("hcl.native"))
        val rendered = render(result)
        assertTrue(rendered.startsWith("name = \"consema\"\n"))
        // Duplicate object keys stay ordered entries with one per line
        // (RFC 0014 §6, §9).
        assertTrue(rendered.contains("dups = {\n  a = 1\n  a = 2\n}\n"))
    }

    /** Unsupported request fields fail with the frozen core codes (RFC 0014
     * §9; materialization.rs). */
    @Test
    fun unsupportedRequests() {
        val record = bodyRecord(emptyList())

        val wrongProfile = materialize(
            record,
            MaterializationRequest.new(ProfileId("json.strict", 1), MaterializationStyleId("hcl.canonical-document", 1)),
        )
        val profileFailed = assertIs<HclMaterializationResult.Failed>(wrongProfile)
        assertEquals("core.materialization.unsupported-profile@1", profileFailed.failure.code)

        val wrongStyle = materialize(
            record,
            MaterializationRequest.new(ProfileId("hcl.native", 1), MaterializationStyleId("json.canonical-compact", 1)),
        )
        val styleFailed = assertIs<HclMaterializationResult.Failed>(wrongStyle)
        assertEquals("core.materialization.unsupported-style@1", styleFailed.failure.code)
    }
}
