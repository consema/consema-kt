// Resource limit transcriptions from conformance/vectors/
// java-properties-v1.json.
//
// Every PropertiesParseLimits field is fatal when exceeded and never
// publishes a partial document (RFC 0010 §14; resource.formation-limit-
// matrix, java-properties-v1.json:115-140). The source-construction limits
// (max_source_bytes, max_decoded_utf8_bytes, max_decoded_scalars) fail
// through FatalFormationFailure::source_error with core.source.resource-
// limit@1 (source_v1.rs:410-421); every parse-level limit fails with
// core.parse.resource-limit@1. These tests pin the intent and run at the
// L2 verification gate.

package properties

import consema.document.ParseLimits
import consema.document.SourceEncoding
import consema.properties.PropertiesFormationException
import consema.properties.PropertiesParseLimits
import consema.properties.parseReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResourceTest {

    /** Vector case resource.formation-limit-matrix
     * (java-properties-v1.json:115-140): all twenty limit names are fatal
     * with no partial document. */
    @Test
    fun formationLimitMatrix() {
        val defaults = PropertiesParseLimits.default
        val cases = listOf(
            defaults.copy(common = common(maxSourceBytes = 2)) to "a=1\n" to "core.source.resource-limit@1",
            defaults.copy(common = common(maxTokenCount = 1)) to "a=1\n" to "core.parse.resource-limit@1",
            defaults.copy(common = common(maxNodeCount = 1)) to "a=1\n" to "core.parse.resource-limit@1",
            defaults.copy(common = common(maxDiagnostics = 0)) to "a=\\u\nb=\\u\n" to "core.parse.resource-limit@1",
            defaults.copy(maxDecodedUtf8Bytes = 1) to "a=1\n" to "core.source.resource-limit@1",
            defaults.copy(maxDecodedScalars = 1) to "a=1\n" to "core.source.resource-limit@1",
            defaults.copy(maxNaturalLines = 1) to "a=1\nb=2\n" to "core.parse.resource-limit@1",
            defaults.copy(maxNaturalLineBytes = 3) to "long=value\n" to "core.parse.resource-limit@1",
            defaults.copy(maxNaturalLineScalars = 3) to "long=value\n" to "core.parse.resource-limit@1",
            defaults.copy(maxLogicalLines = 1) to "a=1\nb=2\n" to "core.parse.resource-limit@1",
            defaults.copy(maxLogicalLineNaturalLines = 1) to "a=one\\\n two\n" to "core.parse.resource-limit@1",
            defaults.copy(maxLogicalLineScalars = 3) to "a=one\\\n two\n" to "core.parse.resource-limit@1",
            defaults.copy(maxProperties = 1) to "a=1\nb=2\n" to "core.parse.resource-limit@1",
            defaults.copy(maxComments = 1) to "# a\n# b\n" to "core.parse.resource-limit@1",
            defaults.copy(maxEscapes = 1) to "a=\\t\\n\n" to "core.parse.resource-limit@1",
            defaults.copy(maxUnicodeEscapes = 1) to "a=\\u0041\\u0042\n" to "core.parse.resource-limit@1",
            defaults.copy(maxJavaCodeUnitsPerString = 3) to "long=value\n" to "core.parse.resource-limit@1",
            defaults.copy(maxTotalJavaCodeUnits = 3) to "a=1\nb=2\n" to "core.parse.resource-limit@1",
            defaults.copy(maxDuplicateGroupMembers = 1) to "a=1\na=2\n" to "core.parse.resource-limit@1",
            defaults.copy(maxRecoveryRegions = 1) to "a=\\u\nb=\\u\n" to "core.parse.resource-limit@1",
        )
        for ((case, expectedCode) in cases) {
            val (limits, source) = case
            val failure = assertFailsWith<PropertiesFormationException> {
                parseReader(source.toByteArray(Charsets.UTF_8), SourceEncoding.Utf8, limits)
            }
            assertEquals(expectedCode, failure.code, source)
        }
    }
}

/** The common parse limits with explicit overrides. */
private fun common(
    maxSourceBytes: Int = 64 shl 20,
    maxNestingDepth: Int = 256,
    maxTokenCount: Int = 2_000_000,
    maxNodeCount: Int = 1_000_000,
    maxDiagnostics: Int = 10_000,
): ParseLimits = ParseLimits(maxSourceBytes, maxNestingDepth, maxTokenCount, maxNodeCount, maxDiagnostics)
