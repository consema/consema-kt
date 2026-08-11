// The no-evaluation contract (SECURITY.md:36; RFC 0014 §1, hard gate 1):
// HCL is NEVER evaluated — parse, query, projection, materialization, and
// edit carry only syntax facts. This test proves the contract: derived
// expressions (variables, function calls, binary operators, interpolations,
// for-expressions) exist as AST facts with exact source text; no operation
// computes a value from them.

package hcl

import consema.document.FormationStatus
import consema.hcl.ExpressionPolicy
import consema.hcl.HclExpressionHandle
import consema.hcl.HclExpressionKind
import consema.hcl.HclProfile
import consema.hcl.HclTemplatePart
import consema.hcl.ProjectionLimits
import consema.hcl.ProjectionResult
import consema.hcl.ProjectionTarget
import consema.hcl.commit
import consema.hcl.isLiteralComplete
import consema.hcl.parse
import consema.hcl.project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NoEvaluationTest {

    /** Formation: `1 + 2` forms a Binary AST with exact spans; no
     * arithmetic is folded. */
    @Test
    fun binaryOperatorIsNeverFolded() {
        val document = parse(
            "count = 1 + 2\n".toByteArray(Charsets.UTF_8),
            HclProfile.NATIVE_V1,
        )
        val expression = document.rootBody().attributes()[0].expression()
        val kind = expression.kind() as HclExpressionKind.Binary
        assertEquals("+", kind.op.spelling)
        assertEquals("1 + 2", expression.text())
        assertEquals(2, expression.children().size)
        assertFalse(expression.isLiteral())
        assertFalse(isLiteralComplete(expression.expressionValue()))
    }

    /** Formation: variables, function calls, traversals, interpolations,
     * and for-expressions carry kind facts and exact text, never values. */
    @Test
    fun derivedExpressionsCarryOnlySyntaxFacts() {
        val document = parse(
            ("v = my_var\ncall = max(1, 2)\nmsg = \"hi \${name}\"\n" +
                "items = [for x in list : x]\n").toByteArray(Charsets.UTF_8),
            HclProfile.NATIVE_V1,
        )
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val attributes = document.rootBody().attributes()

        val variable = attributes[0].expression()
        assertIs<HclExpressionKind.VariableRef>(variable.kind())
        assertEquals("my_var", (variable.kind() as HclExpressionKind.VariableRef).name)
        assertEquals("my_var", variable.text())
        assertFalse(variable.isLiteral())

        val call = attributes[1].expression()
        assertIs<HclExpressionKind.FunctionCall>(call.kind())
        assertEquals("max", (call.kind() as HclExpressionKind.FunctionCall).name)
        assertEquals("max(1, 2)", call.text())
        assertFalse(call.isLiteral())

        val message = attributes[2].expression()
        assertIs<HclExpressionKind.Template>(message.kind())
        val template = message.kind() as HclExpressionKind.Template
        assertTrue(template.parts.any { it is HclTemplatePart.Interpolation })
        assertEquals("\"hi \${name}\"", message.text())
        assertFalse(message.isLiteral())

        val items = attributes[3].expression()
        assertIs<HclExpressionKind.ForTuple>(items.kind())
        assertFalse(items.isLiteral())
    }

    /** The literal-complete predicate is purely syntactic: `-1` is literal,
     * `1 + 2` and `-x` are not; no arithmetic is ever computed. */
    @Test
    fun literalPredicateIsSyntactic() {
        fun literal(source: String): Boolean {
            val document = parse(
                "a = $source\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
            )
            return document.rootBody().attributes()[0].expression().isLiteral()
        }
        assertTrue(literal("-1"))
        assertFalse(literal("1 + 2"))
        assertTrue(literal("1.50"))
        assertFalse(literal("-x"))
        assertTrue(literal("[1, \"two\", {k = 3}]"))
        assertFalse(literal("max(1, 2)"))
        assertFalse(literal("\"x\${y}\""))
        assertTrue(literal("(42)"))
        assertFalse(literal("!true"))
    }

    /** Projection never evaluates: a derived expression fails atomically
     * with `hcl.projection.non-literal-expression@1` under the default
     * policy, and the explicit ProjectExpression policy substitutes the
     * authorized `hcl.expression@1` record carrying only kind, text, and
     * fingerprint. */
    @Test
    fun projectionNeverEvaluates() {
        val document = parse(
            "count = 1 + 2\nname = var.name\nok = 42\n".toByteArray(Charsets.UTF_8),
            HclProfile.NATIVE_V1,
        )
        val failed = project(document, ProjectionTarget.BodyV1, ExpressionPolicy.Default)
        val failedAttempt = assertIs<ProjectionResult.Failed>(failed)
        assertEquals("hcl.projection.non-literal-expression@1", failedAttempt.attempt.diagnostics.first().code)

        val completed = project(document, ProjectionTarget.BodyV1, ExpressionPolicy.ProjectExpression)
        val projection = assertIs<ProjectionResult.Complete>(completed).projection
        assertEquals(2, projection.report.events().count { it.kind == consema.hcl.ProjectionEventKind.ExpressionSubstituted })
        assertTrue(projection.provenance.entries().isNotEmpty())
    }

    /** Edit never evaluates: every derived-expression value is refused
     * explicitly with `hcl.edit.unrepresentable@1` (RFC 0014 §10, §14). */
    @Test
    fun editRefusesDerivedExpressions() {
        val document = parse(
            "count = 2\n".toByteArray(Charsets.UTF_8),
            HclProfile.NATIVE_V1,
        )
        val transaction = consema.hcl.HclEditTransactionBuilder.new(document)
            .setAttributeValue(
                consema.hcl.BodyPath.root(),
                "count",
                consema.hcl.EditValue.Expression("binary", "1 + 2"),
            )
            .build()
        val failure = kotlin.test.assertFailsWith<consema.hcl.HclEditException> {
            document.commit(transaction)
        }
        assertEquals("hcl.edit.unrepresentable@1", failure.code)
        // The base stays byte-exact (atomicity, hard gate 4).
        assertEquals("count = 2\n", document.render().toString(Charsets.UTF_8))
    }

    /** Materialization of an `hcl.expression@1` value emits the canonical
     * text verbatim and the closure compares structure — never values. */
    @Test
    fun materializationEmitsExpressionTextVerbatim() {
        val record = bodyRecord(
            listOf(attributeItem("derived", expressionValue("binary", "1 + 2"))),
        )
        val request = consema.document.MaterializationRequest.new(
            consema.document.ProfileId("hcl.native", 1),
            consema.document.MaterializationStyleId("hcl.canonical-document", 1),
        )
        val result = consema.hcl.materialize(record, request)
        val complete = assertIs<consema.hcl.HclMaterializationResult.Complete>(result)
        assertEquals("derived = 1 + 2\n", complete.materialization.document.render().toString(Charsets.UTF_8))
    }
}
