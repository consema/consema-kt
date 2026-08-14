// Wave-4 R42 regression tests: plist decimal-to-double materialization.
//
// The previous decimalToF64 multiplied by 10 in a loop capped at 400
// iterations: 1e1000 silently became +Infinity, 1e-1000 silently became
// 0.0, and an exponent above Int.MAX wrapped the loop away entirely —
// every out-of-range decimal "materialized" instead of failing
// atomically. The wave-4 conversion is a single correctly rounded pass
// (BigDecimal.doubleValue, round-half-even) that fails atomically
// (core.materialization.unrepresentable@1) when |exponent| > 308 or the
// correctly rounded result overflows to infinity / underflows to zero.

package plist

import consema.core.Entry
import consema.core.PortableValue
import consema.core.PvDecimal
import consema.core.PvObject
import consema.core.PvString
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.ProfileId
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class DecimalToF64Test {

    private fun plistRequest(): MaterializationRequest =
        MaterializationRequest.new(
            ProfileId("plist.xml", 1),
            MaterializationStyleId("plist.xml-canonical", 1),
        )

    /** The plist materialization consumes the versioned
     * plist.value-tree@1 record, never a bare portable value
     * (materialization.rs validateRecord). */
    private fun valueTreeRecord(value: PortableValue): PortableValue =
        PvObject(
            listOf(
                Entry("record", PvString("plist.value-tree@1")),
                Entry("root", value),
            ),
        )

    /** The R42 regression case 7.038531e-26: within range, must
     * materialize to the correctly rounded double — bit-identical to
     * Double.parseDouble, the Java correctly-rounded parser. */
    @Test
    fun inRangeDecimalRoundsCorrectly() {
        val decimal = PvDecimal.of(BigInteger("7038531"), BigInteger("-32"))
        val result = consema.plist.materialize(valueTreeRecord(decimal), plistRequest())
        val complete = result as? MaterializationResult.Complete
            ?: fail("materialization must complete, got $result")
        val rendered = String(complete.materialization.document.render())
        val realText = Regex("<real>(.*?)</real>").find(rendered)?.groupValues?.get(1)
            ?: fail("expected a <real> element, got: $rendered")
        assertEquals(
            java.lang.Double.doubleToRawLongBits(java.lang.Double.parseDouble("7.038531e-26")),
            java.lang.Double.doubleToRawLongBits(realText.toDouble()),
            "the rendered real must be the correctly rounded double",
        )
    }

    /** 1e1000: |exponent| > 308 — previously multiplied to +Infinity
     * silently; now an atomic unrepresentable failure. */
    @Test
    fun overflowingDecimalFailsAtomically() {
        val decimal = PvDecimal.of(BigInteger.ONE, BigInteger("1000"))
        val result = consema.plist.materialize(valueTreeRecord(decimal), plistRequest())
        val failed = result as? MaterializationResult.Failed
            ?: fail("materialization must fail, got $result")
        assertEquals("core.materialization.unrepresentable@1", failed.attempt.failure.code)
    }

    /** 1e-1000: below the double range — previously rounded to 0.0
     * silently; now an atomic unrepresentable failure. */
    @Test
    fun underflowingDecimalFailsAtomically() {
        val decimal = PvDecimal.of(BigInteger.ONE, BigInteger("-1000"))
        val result = consema.plist.materialize(valueTreeRecord(decimal), plistRequest())
        val failed = result as? MaterializationResult.Failed
            ?: fail("materialization must fail, got $result")
        assertEquals("core.materialization.unrepresentable@1", failed.attempt.failure.code)
    }

    /** The |exponent| == 308 boundary stays finite (1e308 < Double.MAX),
     * so the atomic-failure rule never rejects representable values
     * within the ruling's threshold. */
    @Test
    fun boundaryExponent308IsFinite() {
        val decimal = PvDecimal.of(BigInteger.ONE, BigInteger("308"))
        val result = consema.plist.materialize(valueTreeRecord(decimal), plistRequest())
        assertTrue(result is MaterializationResult.Complete, "1e308 is within the finite double range")
    }
}
