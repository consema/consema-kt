// The expression syntax model: frozen kind spellings, operator spellings,
// canonical decimals, structural fingerprints, and the literal-complete
// boundary (RFC 0014 §4.3-§4.6, §6, §8.1).
//
// Data authority: https://github.com/consema/consema-rs/blob/main/consema-hcl/src/expression.rs (kind names
// :564-650, operators :856-956, canonical_decimal :737-851) and the
// hcl-v1.json projection/query cases pinning the spellings
// (`hcl.expression-kind-is@1` argument "number"; the kind family spellings
// of the `hcl.expression@1` record).

package hcl

import consema.hcl.HclBinaryOp
import consema.hcl.HclExpressionHandle
import consema.hcl.HclExpressionKind
import consema.hcl.HclExpressionKindName
import consema.hcl.HclNumber
import consema.hcl.HclProfile
import consema.hcl.HclTemplatePart
import consema.hcl.HclUnaryOp
import consema.hcl.canonicalDecimalBounded
import consema.hcl.isLiteralComplete
import consema.hcl.parse
import consema.hcl.structuralFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExpressionSyntaxTest {

    private fun expression(source: String): HclExpressionHandle {
        val document = parse(
            "a = $source\n".toByteArray(Charsets.UTF_8),
            HclProfile.NATIVE_V1,
        )
        return document.rootBody().attributes()[0].expression()
    }

    /** The closed kind-name spellings of `hcl.expression-kind-is@1` (RFC
     * 0014 §7.1; expression.rs). */
    @Test
    fun kindNameSpellings() {
        assertEquals(
            listOf(
                "number", "boolean", "null", "template", "function-call",
                "variable-ref", "traversal", "unary", "binary", "conditional",
                "for-tuple", "for-object", "tuple", "object", "parenthesized",
            ),
            HclExpressionKindName.entries.map { it.spelling },
        )
        assertEquals("number", expression("42").kindName())
        assertEquals("boolean", expression("true").kindName())
        assertEquals("null", expression("null").kindName())
        assertEquals("template", expression("\"x\"").kindName())
        assertEquals("function-call", expression("f(1)").kindName())
        assertEquals("variable-ref", expression("x").kindName())
        assertEquals("traversal", expression("x.y").kindName())
        assertEquals("unary", expression("-1").kindName())
        assertEquals("binary", expression("1 + 2").kindName())
        assertEquals("conditional", expression("a ? b : c").kindName())
        assertEquals("for-tuple", expression("[for x in l : x]").kindName())
        assertEquals("for-object", expression("{for k, v in m : k => v}").kindName())
        assertEquals("tuple", expression("[1, 2]").kindName())
        assertEquals("object", expression("{a = 1}").kindName())
        assertEquals("parenthesized", expression("(1)").kindName())
    }

    /** Operator spellings (RFC 0014 §4.3; expression.rs). */
    @Test
    fun operatorSpellings() {
        assertEquals("+", HclBinaryOp.Add.spelling)
        assertEquals("==", HclBinaryOp.Equal.spelling)
        assertEquals("&&", HclBinaryOp.And.spelling)
        assertEquals("-", HclUnaryOp.Minus.spelling)
        assertEquals("!", HclUnaryOp.Not.spelling)
        val binary = expression("2 > 1 && 3 <= 3").kind() as HclExpressionKind.Binary
        assertEquals("&&", binary.op.spelling)
        val lhs = binary.lhs.kind as HclExpressionKind.Binary
        assertEquals(">", lhs.op.spelling)
        val rhs = binary.rhs.kind as HclExpressionKind.Binary
        assertEquals("<=", rhs.op.spelling)
    }

    /** Canonical decimal normalization (RFC 0014 §4.1, §6, §9;
     * expression.rs): `1.50` and `15e-1` both normalize to `1.5`,
     * `1e3` to `1000`, every zero to `0`; grammar violations return null. */
    @Test
    fun canonicalDecimal() {
        assertEquals("1.5", canonicalDecimalBounded("1.50", 100))
        assertEquals("1.5", canonicalDecimalBounded("15e-1", 100))
        assertEquals("1000", canonicalDecimalBounded("1e3", 100))
        assertEquals("100", canonicalDecimalBounded("1E+2", 100))
        assertEquals("0.5", canonicalDecimalBounded("0.5", 100))
        assertEquals("0", canonicalDecimalBounded("0", 100))
        assertEquals("0", canonicalDecimalBounded("0.00", 100))
        assertEquals("42", canonicalDecimalBounded("042", 100))
        assertEquals("0.125", canonicalDecimalBounded("0.12500", 100))
        assertEquals(null, canonicalDecimalBounded("1e", 100))
        assertEquals(null, canonicalDecimalBounded("1.", 100))
        assertEquals(null, canonicalDecimalBounded("0x1F", 100))
        assertEquals(null, canonicalDecimalBounded("1_000", 100))
        assertEquals(null, canonicalDecimalBounded("", 100))
        // The digit budget is checked before any padding (RFC 0014 §11).
        assertEquals(null, canonicalDecimalBounded("1e10", 5))
        // Number literals carry their canonical value and exact span.
        val number = expression("15e-1").kind() as HclExpressionKind.Number
        assertEquals("1.5", number.number.canonicalDecimal)
        assertEquals("15e-1", expression("15e-1").text())
    }

    /** Structural fingerprints: FNV-1a 64-bit over the canonical structural
     * serialization (RFC 0014 §8.2). Spelling trivia does not change
     * structure; canonical-decimal equality is structural; different
     * structures differ. */
    @Test
    fun structuralFingerprint() {
        val first = expression("1 + 2").expressionValue()
        val second = expression("1+2").expressionValue()
        assertEquals(structuralFingerprint(first), structuralFingerprint(second))
        val other = expression("1 - 2").expressionValue()
        assertNotEquals(structuralFingerprint(first), structuralFingerprint(other))
        val number = expression("1.50").expressionValue()
        val folded = expression("15e-1").expressionValue()
        assertEquals(structuralFingerprint(number), structuralFingerprint(folded))
        // Fingerprints never depend on source spans or trivia.
        assertEquals(
            structuralFingerprint(expression("x").expressionValue()),
            structuralFingerprint(expression("x ").expressionValue()),
        )
    }

    /** Template parts preserve ordered literal/interpolation/directive
     * facts with exact decoded text (RFC 0014 §4.4, §6). */
    @Test
    fun templateParts() {
        val template = expression("\"a\${x}b\"").kind() as HclExpressionKind.Template
        assertEquals(3, template.parts.size)
        val first = template.parts[0] as HclTemplatePart.Literal
        assertEquals("a", first.text)
        val middle = template.parts[1] as HclTemplatePart.Interpolation
        assertEquals("x", middle.expression.kind.let { (it as HclExpressionKind.VariableRef).name })
        val last = template.parts[2] as HclTemplatePart.Literal
        assertEquals("b", last.text)
        // Escaped `$${` decodes to literal `${` text (RFC 0014 §4.4).
        val literal = expression("\"\$\${x}\"").kind() as HclExpressionKind.Template
        assertEquals("\${x}", (literal.parts[0] as HclTemplatePart.Literal).text)
    }

    /** The literal-complete boundary of RFC 0014 §8.1: exactly the frozen
     * set is literal; everything else is derived. */
    @Test
    fun literalCompleteBoundary() {
        fun literal(source: String): Boolean {
            val document = parse(
                "a = $source\n".toByteArray(Charsets.UTF_8),
                HclProfile.NATIVE_V1,
            )
            return isLiteralComplete(document.rootBody().attributes()[0].expression().expressionValue())
        }
        // Literal-complete.
        assertTrue(literal("42"))
        assertTrue(literal("1.50"))
        assertTrue(literal("true"))
        assertTrue(literal("null"))
        assertTrue(literal("\"no interpolation\""))
        assertTrue(literal("<<EOT\nplain\nEOT"))
        assertTrue(literal("-1"))
        assertTrue(literal("(42)"))
        assertTrue(literal("[1, \"two\", {k = 3}]"))
        assertTrue(literal("{1 = \"a\"}"))
        // Derived.
        kotlin.test.assertFalse(literal("1 + 2"))
        kotlin.test.assertFalse(literal("\"x\${y}\""))
        kotlin.test.assertFalse(literal("-x"))
        kotlin.test.assertFalse(literal("!true"))
        kotlin.test.assertFalse(literal("max(1, 2)"))
        kotlin.test.assertFalse(literal("[for x in list : x]"))
    }

    /** The number equality contract: `1.50`, `1.5`, and `15e-1` compare
     * equal as values while remaining distinct source facts (RFC 0014 §6). */
    @Test
    fun numberEqualityIsCanonical() {
        fun number(source: String): HclNumber =
            (expression(source).kind() as HclExpressionKind.Number).number
        assertEquals(number("1.50"), number("1.5"))
        assertEquals(number("1.50"), number("15e-1"))
        kotlin.test.assertNotEquals(number("1.50"), number("1.6"))
    }

    /** Unary binding: `-1 + 2` parses as `(-1) + 2`, `2 * -1` as
     * `2 * (-1)`, and `!!x` as `!(!x)` (RFC 0014 §4.3). */
    @Test
    fun unaryBinding() {
        val compound = expression("-1 + 2").kind() as HclExpressionKind.Binary
        assertEquals("+", compound.op.spelling)
        val negated = compound.lhs.kind as HclExpressionKind.Unary
        assertEquals(HclUnaryOp.Minus, negated.op)
        val doubleNot = expression("!!x").kind() as HclExpressionKind.Unary
        val inner = doubleNot.operand.kind as HclExpressionKind.Unary
        assertEquals(HclUnaryOp.Not, inner.op)
    }

    /** Traversal and splat facts (RFC 0014 §4.1, §4.3). */
    @Test
    fun traversalFacts() {
        val traversal = expression("foo[0].bar[*].baz").kind() as HclExpressionKind.Traversal
        assertEquals(consema.hcl.HclTraversalRoot.Variable("foo"), traversal.root)
        assertEquals(3, traversal.steps.size)
        assertIs<consema.hcl.HclTraversalStep.Index>(traversal.steps[0])
        assertIs<consema.hcl.HclTraversalStep.GetAttr>(traversal.steps[1])
        assertIs<consema.hcl.HclTraversalStep.FullSplat>(traversal.steps[2])
        // Keyword spellings are dual-read traversal roots (RFC 0014 §4.1).
        val keyword = expression("true.bar").kind() as HclExpressionKind.Traversal
        assertEquals(consema.hcl.HclTraversalRoot.Boolean(true), keyword.root)
    }

    /** The expression children order used by `hcl.expression-children@1`
     * (RFC 0014 §6; expression.rs). */
    @Test
    fun expressionChildren() {
        val binary = expression("1 + 2 * 3").kind() as HclExpressionKind.Binary
        assertEquals(2, binary.children.size)
        val rhs = binary.children[1]
        assertIs<HclExpressionKind.Binary>(rhs.kind)
        val conditional = expression("a ? b : c").kind() as HclExpressionKind.Conditional
        assertEquals(3, conditional.children.size)
        val call = expression("f(1, 2)").kind() as HclExpressionKind.FunctionCall
        assertEquals(2, call.children.size)
    }
}
