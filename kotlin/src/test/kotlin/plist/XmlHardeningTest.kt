// XML-profile hardening: the wave-5 per-parser number-digit cap.
//
// Wave-5 audit P1 (Parser.kt parseInteger): an unbounded `<integer>`
// literal was handed to BigInteger(digits, 10/16) with no digit cap (only
// maxSourceBytes=64 MiB bound it). The cap reuses the common
// ParseLimits.maxNumberDigits (default 100,000 — the json/hcl
// checkNumberDigits shape) and fails fatally with the frozen
// resource-limit code (RFC 0016 §5.1) before the O(N²) BigInteger
// construction — never a crash and never a silent recovery to a
// Recovered document (hard gate 4, RFC 0013 §12).

package plist

import consema.plist.PlistFormationException
import consema.plist.PlistProfile
import consema.plist.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XmlHardeningTest {

    /** An `<integer>` with more than maxNumberDigits decimal digits is a
     * fatal resource-limit failure (name number-digits, the frozen
     * core.parse.resource-limit@1 code). */
    @Test
    fun integerOverDigitCapIsFatal() {
        val error = assertFailsWith<PlistFormationException> {
            parse(
                ("<plist version=\"1.0\"><integer>" + "9".repeat(100_001) + "</integer></plist>")
                    .toByteArray(Charsets.UTF_8),
                PlistProfile.XmlV1,
            )
        }
        assertEquals("core.parse.resource-limit@1", error.code)
        assertEquals("number-digits", error.name)
        assertEquals(100_001, error.observed)
        assertEquals(100_000, error.limit)
    }

    /** An `<integer>` with more than maxNumberDigits hex digits is a
     * fatal resource-limit failure (hex digits count, the json
     * checkNumberDigits shape). */
    @Test
    fun hexIntegerOverDigitCapIsFatal() {
        val error = assertFailsWith<PlistFormationException> {
            parse(
                ("<plist version=\"1.0\"><integer>0x" + "f".repeat(100_001) + "</integer></plist>")
                    .toByteArray(Charsets.UTF_8),
                PlistProfile.XmlV1,
            )
        }
        assertEquals("core.parse.resource-limit@1", error.code)
        assertEquals("number-digits", error.name)
        assertEquals(100_001, error.observed)
        assertEquals(100_000, error.limit)
    }

    /** Integers well within the digit cap parse normally: the signed
     * 64-bit maximum and a hex spelling form their exact Long values. */
    @Test
    fun integerWithinDigitCapParses() {
        val decimal = parse(
            "<plist version=\"1.0\"><integer>9223372036854775807</integer></plist>"
                .toByteArray(Charsets.UTF_8),
            PlistProfile.XmlV1,
        )
        assertEquals(9223372036854775807L, decimal.root().asInteger())

        val hex = parse(
            "<plist version=\"1.0\"><integer>0x2A</integer></plist>"
                .toByteArray(Charsets.UTF_8),
            PlistProfile.XmlV1,
        )
        assertEquals(42L, hex.root().asInteger())
    }
}
