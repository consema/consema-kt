// EditPlan (dry-run plan) tests.
//
// Data authority: RFC 0004 §14 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-
// and-structural-edit-v1.md:338-356) — the transferable dry-run plan
// contains source_id, base_digest, profile, ordered operations, exact
// SourcePatch replacement facts, precomputed target_digest, and an ordered
// report; a dry-run plan is not authority to write a file and is never
// applied without rechecking base digest and every original-byte
// precondition. The validation bounds and the operation-metadata
// cross-check follow https://github.com/consema/consema-rs/blob/main/consema-document/src/edit_plan.rs:13-127
// (test data adapted from edit_plan.rs:235-272).

package document

import consema.document.EditOperationSummary
import consema.document.EditPlan
import consema.document.EditPlanErrorKind
import consema.document.EditPlanException
import consema.document.EditPlanSourceId
import consema.document.EncodingRequest
import consema.document.FormatOperationId
import consema.document.ProfileId
import consema.document.SourceEncoding
import consema.document.SourceLimits
import consema.document.SourcePatch
import consema.document.SourceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditPlanTest {

    private fun utf8(text: String): SourceSnapshot =
        SourceSnapshot.fromRaw(text.toByteArray(Charsets.UTF_8), EncodingRequest.new(SourceEncoding.Utf8))

    /** edit_plan.rs:235-272: a plan requires a stable source identity, and
     * its operation metadata must match the ordered operation IDs; with an
     * empty replacement set base and target digests coincide. */
    @Test
    fun planRequiresStableSourceAndMatchingOperationMetadata() {
        assertFailsWith<EditPlanException> {
            EditPlanSourceId.new("")
        }.also { assertEquals(EditPlanErrorKind.INVALID_SOURCE_ID, it.kind) }

        val source = utf8("a")
        val patch = SourcePatch.create(
            source,
            emptyList(),
            mapOf("operation.0" to "json.edit.remove-member@1"),
        )
        val summary = EditOperationSummary.new(
            FormatOperationId("json.edit.remove-member", 1),
            mapOf("target_role" to "json.object-member@1"),
        )
        val plan = EditPlan.new(
            EditPlanSourceId.new("config.json"),
            ProfileId("json.strict", 1),
            listOf(summary),
            patch,
            emptyList(),
        )
        assertEquals("config.json", plan.sourceId.asStr())
        assertEquals(plan.baseDigest, plan.targetDigest)
        assertTrue(plan.replacements().isEmpty())
        assertEquals(listOf(summary), plan.operations())
        assertEquals(ProfileId("json.strict", 1), plan.profile)
    }

    /** edit_plan.rs:91-113: an operation whose metadata key disagrees with
     * the patch is refused at plan construction. */
    @Test
    fun planRejectsOperationMetadataMismatch() {
        val source = utf8("a")
        val patch = SourcePatch.create(
            source,
            emptyList(),
            mapOf("operation.0" to "json.edit.insert-member@1"),
        )
        val summary = EditOperationSummary.new(
            FormatOperationId("json.edit.remove-member", 1),
            emptyMap(),
        )
        val error = assertFailsWith<EditPlanException> {
            EditPlan.new(
                EditPlanSourceId.new("config.json"),
                ProfileId("json.strict", 1),
                listOf(summary),
                patch,
                emptyList(),
            )
        }
        assertEquals(EditPlanErrorKind.OPERATION_METADATA_MISMATCH, error.kind)
        assertEquals(0, error.index)
    }

    /** edit_plan.rs:44-58: summary argument names are bounded
     * lowercase/digit/underscore strings and values are non-empty and
     * bounded. */
    @Test
    fun summaryValidationBoundsAreFrozen() {
        assertFailsWith<EditPlanException> {
            EditOperationSummary.new(
                FormatOperationId("json.edit.remove-member", 1),
                mapOf("Target_Role" to "json.object-member@1"),
            )
        }.also { assertEquals(EditPlanErrorKind.INVALID_OPERATION_SUMMARY, it.kind) }

        assertFailsWith<EditPlanException> {
            EditOperationSummary.new(
                FormatOperationId("json.edit.remove-member", 1),
                mapOf("target_role" to ""),
            )
        }.also { assertEquals(EditPlanErrorKind.INVALID_OPERATION_SUMMARY, it.kind) }

        val valid = EditOperationSummary.new(
            FormatOperationId("json.edit.remove-member", 1),
            mapOf("target_role" to "json.object-member@1", "policy" to "exact"),
        )
        assertEquals(2, valid.arguments.size)
    }

    /** edit_plan.rs:15-31: the source identity is bounded to 1024
     * characters. */
    @Test
    fun sourceIdentityIsBounded() {
        val long = "x".repeat(1025)
        assertFailsWith<EditPlanException> {
            EditPlanSourceId.new(long)
        }.also { assertEquals(EditPlanErrorKind.INVALID_SOURCE_ID, it.kind) }
        assertEquals("ok", EditPlanSourceId.new("ok").asStr())
    }

    /** RFC 0004 §14: dry-run and commit produce the same replacement set
     * and target digest; the plan's target digest is the precomputed one. */
    @Test
    fun planCarriesExactReplacementFactsAndTargetDigest() {
        val source = utf8("a")
        val patch = SourcePatch.create(
            source,
            listOf(
                consema.document.SourceReplacement.new(1, 1, ByteArray(0), "b".toByteArray(Charsets.UTF_8)),
            ),
            mapOf("operation.0" to "json.edit.insert-member@1"),
        )
        val summary = EditOperationSummary.new(
            FormatOperationId("json.edit.insert-member", 1),
            emptyMap(),
        )
        val plan = EditPlan.new(
            EditPlanSourceId.new("config.json"),
            ProfileId("json.strict", 1),
            listOf(summary),
            patch,
            emptyList(),
        )
        assertEquals(patch.targetDigest, plan.targetDigest)
        assertEquals(patch.baseDigest, plan.baseDigest)
        assertEquals(1, plan.replacements().size)
        // The plan is never applied without rechecking: applying its patch
        // to the base reproduces the target digest (RFC 0004 §14).
        val target = plan.sourcePatch.apply(source)
        assertEquals(plan.targetDigest, target.digest)
    }

    /** edit_plan.rs:173-196: redaction passes through to the underlying
     * SourcePatch. */
    @Test
    fun planRedactionPassesThrough() {
        val source = utf8("a")
        val patch = SourcePatch.create(
            source,
            listOf(
                consema.document.SourceReplacement.new(1, 1, ByteArray(0), "b".toByteArray(Charsets.UTF_8)),
            ),
            mapOf("operation.0" to "json.edit.insert-member@1"),
        )
        val plan = EditPlan.new(
            EditPlanSourceId.new("config.json"),
            ProfileId("json.strict", 1),
            listOf(EditOperationSummary.new(FormatOperationId("json.edit.insert-member", 1), emptyMap())),
            patch,
            emptyList(),
        )
        val redacted = plan.withAllReplacementsRedacted(redactOriginal = true, redactReplacement = true)
        assertTrue(redacted.replacements()[0].redactOriginal)
        assertTrue(redacted.replacements()[0].redactReplacement)
        // Application facts are unchanged.
        val target = redacted.sourcePatch.apply(source)
        assertEquals(redacted.targetDigest, target.digest)
    }

    /** SourceLimits.UNBOUNDED is used by already-bounded format parsers
     * (source.rs:392-399); the document snapshot construction in this test
     * class uses the default limits otherwise. */
    @Test
    fun sourceLimitsAreExplicitlyExposed() {
        assertEquals(SourceLimits.default, SourceLimits.default)
        assertEquals(SourceLimits.UNBOUNDED.maxRawBytes, Int.MAX_VALUE)
    }
}
