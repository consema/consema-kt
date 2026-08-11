// Transcriptions of the conformance/vectors/hcl-v1.json hcl.projection.*
// cases (:889-1151): the `hcl.projection.body@1` record, the
// literal-complete matrix, the atomic non-literal failure, and the
// ProjectExpression policy.
//
// The projected record publishes the raw typed members of RFC 0014 §9:
// strings/integers/reals/booleans/null as raw PortableValue members,
// tuples as Sequences, objects as EntryMappings with ordered duplicate
// preservation, and derived expressions as the authorized `hcl.expression@1`
// record under the explicit policy.

package hcl

import consema.core.Entry
import consema.core.PortableValue
import consema.core.PvBoolean
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvObject
import consema.core.PvString
import consema.hcl.ExpressionPolicy
import consema.hcl.HclProfile
import consema.hcl.ProjectionLimits
import consema.hcl.ProjectionResult
import consema.hcl.ProjectionTarget
import consema.hcl.parse
import consema.hcl.project
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectionTest {

    private fun projectSource(source: String, policy: ExpressionPolicy = ExpressionPolicy.Default):
        ProjectionResult {
        val document = parse(source.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)
        return project(document, ProjectionTarget.BodyV1, policy, ProjectionLimits.default)
    }

    private fun recordMember(result: ProjectionResult): PvObject {
        val complete = assertIs<ProjectionResult.Complete>(result)
        val value = complete.projection.value
        val record = (value as? PvObject)?.get("record") as? PvString
        assertEquals("hcl.body@1", record?.value)
        return value as PvObject
    }

    private fun items(value: PvObject): List<PortableValue> {
        val itemsValue = value.get("items")
        return (itemsValue as consema.core.PvArray).items()
    }

    /** Vector case hcl.projection.literal-complete-record (hcl-v1.json:889-
     * 1009): every literal family projects to the exact raw typed members
     * with order, duplicate keys, and canonical decimals preserved. */
    @Test
    fun literalCompleteRecord() {
        val result = projectSource(Golden.PROJECTION_RECORD)
        val record = recordMember(result)
        val projected = items(record)

        assertEquals(12, projected.size)
        fun attribute(index: Int): Triple<String, String, PortableValue> {
            val objectValue = projected[index] as PvObject
            val kind = (objectValue.get("kind") as PvString).value
            val name = (objectValue.get("name") as PvString).value
            return Triple(kind, name, objectValue.get("value")!!)
        }

        val (_, name, nameValue) = attribute(0)
        assertEquals("name", name)
        assertEquals(PvString("consema"), nameValue)

        val (_, count, countValue) = attribute(1)
        assertEquals(PvInteger(BigInteger("42")), countValue)

        val (_, ratio, ratioValue) = attribute(2)
        // `1.50` normalizes to the canonical decimal 1.5 (RFC 0014 §6).
        assertEquals(PvDecimal.of(BigInteger("15"), BigInteger("-1")), ratioValue)

        val (_, big, bigValue) = attribute(3)
        assertEquals(PvInteger(BigInteger("1000")), bigValue)

        val (_, small, smallValue) = attribute(4)
        assertEquals(PvDecimal.of(BigInteger("15"), BigInteger("-1")), smallValue)

        val (_, enabled, enabledValue) = attribute(5)
        assertEquals(PvBoolean(true), enabledValue)

        val (_, nothing, nothingValue) = attribute(6)
        assertEquals(PvNull, nothingValue)

        val (_, tags, tagsValue) = attribute(7)
        assertEquals(
            listOf(PvString("a"), PvString("b")),
            (tagsValue as consema.core.PvArray).items(),
        )

        val (_, labels, labelsValue) = attribute(8)
        val labelsMapping = labelsValue as consema.core.PvEntryMapping
        assertEquals(1, labelsMapping.size())
        assertEquals(PvString("env"), labelsMapping.entries()[0].key)
        assertEquals(PvString("prod"), labelsMapping.entries()[0].value)

        // Duplicate object keys are preserved as ordered entries
        // (RFC 0014 §6).
        val (_, dups, dupsValue) = attribute(9)
        val dupsMapping = dupsValue as consema.core.PvEntryMapping
        assertEquals(2, dupsMapping.size())
        assertEquals(PvString("a"), dupsMapping.entries()[0].key)
        assertEquals(PvInteger(BigInteger("1")), dupsMapping.entries()[0].value)
        assertEquals(PvString("a"), dupsMapping.entries()[1].key)
        assertEquals(PvInteger(BigInteger("2")), dupsMapping.entries()[1].value)

        // Number-literal keys render their exact canonical decimal.
        val (_, numkeys, numkeysValue) = attribute(10)
        val numkeysMapping = numkeysValue as consema.core.PvEntryMapping
        assertEquals(PvString("1"), numkeysMapping.entries()[0].key)
        assertEquals(PvString("2"), numkeysMapping.entries()[1].key)

        // Nested constructors stay raw members.
        val (_, nested, nestedValue) = attribute(11)
        val nestedMapping = nestedValue as consema.core.PvEntryMapping
        val innerObject = nestedMapping.entries()[0].value as consema.core.PvEntryMapping
        val innerTuple = innerObject.entries()[0].value as consema.core.PvArray
        assertEquals(listOf(PvInteger(BigInteger("1")), PvInteger(BigInteger("2"))), innerTuple.items())
    }

    /** Vector case hcl.projection.non-literal-expression (hcl-v1.json:1011-
     * 1041): a derived expression fails the projection atomically with
     * `hcl.projection.non-literal-expression@1` under the default policy. */
    @Test
    fun nonLiteralExpressionFailsAtomically() {
        for (source in listOf(
            "count = 1 + 2\n",
            "name = var.name\n",
            "msg = \"hi \${name}\"\n",
            "items = [for x in list : x]\n",
        )) {
            val result = projectSource(source)
            val failed = assertIs<ProjectionResult.Failed>(result, "source: $source")
            assertEquals(
                "hcl.projection.non-literal-expression@1",
                failed.attempt.diagnostics.first().code,
                "source: $source",
            )
        }
    }

    /** Vector case hcl.projection.project-expression-policy (hcl-v1.json:
     * 1043-1081): under the explicit policy each derived expression is
     * substituted by the authorized `hcl.expression@1` record with kind
     * family, exact text, and fingerprint; two Transformed events with
     * provenance; literal values stay raw members. */
    @Test
    fun projectExpressionPolicy() {
        val result = projectSource(
            "count = 1 + 2\nname = var.name\nok = 42\n",
            ExpressionPolicy.ProjectExpression,
        )
        val complete = assertIs<ProjectionResult.Complete>(result)
        val record = recordMember(result)
        val projected = items(record)
        assertEquals(3, projected.size)

        // The item stays an attribute; the VALUE member IS the authorized
        // `hcl.expression@1` record itself (RFC 0014 §8.2; projection.rs:
        // 806-840 and 933-970 — never a {kind, expression} wrapper).
        val count = projected[0] as PvObject
        assertEquals("attribute", (count.get("kind") as PvString).value)
        val expressionRecord = count.get("value") as PvObject
        assertEquals("hcl.expression@1", (expressionRecord.get("record") as PvString).value)
        assertEquals("binary", (expressionRecord.get("kind") as PvString).value)
        assertEquals("1 + 2", (expressionRecord.get("text") as PvString).value)
        val fingerprint = (expressionRecord.get("fingerprint") as PvString).value
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{16}")), "fingerprint: $fingerprint")

        val name = projected[1] as PvObject
        val nameRecord = name.get("value") as PvObject
        assertEquals("variable", (nameRecord.get("kind") as PvString).value)
        assertEquals("var.name", (nameRecord.get("text") as PvString).value)

        val ok = projected[2] as PvObject
        assertEquals(PvInteger(BigInteger("42")), ok.get("value"))

        // One Transformed event per substituted expression (RFC 0014 §8.2).
        assertEquals(
            2,
            complete.projection.report.events().count {
                it.kind == consema.hcl.ProjectionEventKind.ExpressionSubstituted
            },
        )
        assertTrue(complete.projection.provenance.entries().isNotEmpty())
    }

    /** Vector case hcl.projection.literal-complete-boundary (hcl-v1.json:
     * 1083-1151): the projection completion of every boundary sample. */
    @Test
    fun literalCompleteBoundary() {
        val samples = listOf(
            "a = -1\n" to true,
            "a = 1 + 2\n" to false,
            "a = {1 = \"a\"}\n" to true,
            "a = \"no interpolation\"\n" to true,
            "a = \"x\${y}\"\n" to false,
            "a = <<EOT\nplain\nEOT\n" to true,
            "a = <<EOT\nhi \${x}\nEOT\n" to false,
            "a = (42)\n" to true,
            "a = -x\n" to false,
            "a = [1, \"two\", {k = 3}]\n" to true,
            "a = null\n" to true,
            "a = !true\n" to false,
            "a = max(1, 2)\n" to false,
            "a = 15e-1\n" to true,
        )
        for ((source, literal) in samples) {
            val result = projectSource(source)
            if (literal) {
                assertIs<ProjectionResult.Complete>(result, "source: $source")
            } else {
                assertIs<ProjectionResult.Failed>(result, "source: $source")
            }
        }
    }

    /** A Recovered document never projects (RFC 0014 §8.2). */
    @Test
    fun recoveredDocumentNeverProjects() {
        val document = parse("a = 1\na = 2\n".toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)
        val result = project(document, ProjectionTarget.BodyV1, ExpressionPolicy.Default)
        val failed = assertIs<ProjectionResult.Failed>(result)
        assertEquals("hcl.projection.incomplete-document@1", failed.attempt.diagnostics.first().code)
    }
}
