// SourcePatch behavior tests, transcribed from conformance/vectors/
// source-v1.json (the language-neutral vector suite, capability
// core.source.patch@1). Every golden case cites its vector case id.
//
// The success case (source.patch.success, source-v1.json:120-124) is the
// primary round-trip: create -> apply must reproduce the exact target bytes
// and the precomputed target digest, and apply must be repeatable.

package document

import consema.document.ContentDigest
import consema.document.EncodingRequest
import consema.document.SourceEncoding
import consema.document.SourceLimits
import consema.document.SourcePatch
import consema.document.SourcePatchErrorKind
import consema.document.SourcePatchException
import consema.document.SourcePatchLimits
import consema.document.SourceReplacement
import consema.document.SourceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchTest {

    private fun utf8(rawHex: String): SourceSnapshot =
        SourceSnapshot.fromRaw(hexToBytes(rawHex), EncodingRequest.new(SourceEncoding.Utf8))

    private fun replacement(oldStart: Int, oldEnd: Int, originalHex: String, replacementHex: String) =
        SourceReplacement.new(oldStart, oldEnd, hexToBytes(originalHex), hexToBytes(replacementHex))

    /** Vector case source.patch.success (source-v1.json:120-124): base
     * "name = old\n", insert "# " at 0 and replace "old" [7,10) with "new";
     * create-apply must produce "23206e616d65203d206e65770a" ("# name =
     * new\n"). */
    @Test
    fun createAndApplyRoundTrip() {
        val base = utf8("6e616d65203d206f6c640a")
        val replacements = listOf(
            replacement(0, 0, "", "2320"),
            replacement(7, 10, "6f6c64", "6e6577"),
        )
        val patch = SourcePatch.create(base, replacements, mapOf("actor" to "conformance"))
        val target = patch.apply(base)
        assertEquals("23206e616d65203d206e65770a", bytesToHex(target.bytes()))
        assertEquals(target.digest, patch.targetDigest)
        assertEquals("conformance", patch.metadata()["actor"])
        // Application is deterministic and repeatable (RFC 0003 §10).
        val second = patch.apply(base)
        assertTrue(target.bytes().contentEquals(second.bytes()))
        // The patch reproduces the exact new digest during tests (RFC 0004
        // §16).
        assertEquals(ContentDigest.of(hexToBytes("23206e616d65203d206e65770a")), patch.targetDigest)
    }

    /** Vector case source.patch.reject-stale-base (source-v1.json:126-130):
     * applying a patch to a snapshot with a different digest fails with
     * core.source.patch-base-mismatch@1. */
    @Test
    fun staleBaseIsRejected() {
        val base = utf8("616263")
        val patch = SourcePatch.create(
            base,
            listOf(replacement(1, 2, "62", "42")),
            emptyMap(),
        )
        val stale = utf8("616264")
        val error = assertFailsWith<SourcePatchException> { patch.apply(stale) }
        assertEquals(SourcePatchErrorKind.BASE_MISMATCH, error.kind)
        assertEquals("core.source.patch-base-mismatch@1", error.code)
    }

    /** Vector case source.patch.reject-original-mismatch
     * (source-v1.json:132-136): a wrong original-byte precondition fails
     * with core.source.patch-original-mismatch@1. */
    @Test
    fun wrongOriginalIsRejected() {
        val base = utf8("616263")
        val patch = SourcePatch.new(
            base.digest,
            ContentDigest.of(hexToBytes("614263")),
            base.encodingFacts,
            listOf(replacement(1, 2, "78", "42")),
            emptyMap(),
        )
        val error = assertFailsWith<SourcePatchException> { patch.apply(base) }
        assertEquals(SourcePatchErrorKind.ORIGINAL_MISMATCH, error.kind)
        assertEquals("core.source.patch-original-mismatch@1", error.code)
        assertEquals(0, error.index)
    }

    /** Vector case source.patch.reject-overlap (source-v1.json:138-142):
     * overlapping old ranges are not a valid patch and surface the protocol
     * invalid-value code. */
    @Test
    fun overlappingReplacementsAreRejected() {
        val base = utf8("616263646566")
        val error = assertFailsWith<SourcePatchException> {
            SourcePatch.create(
                base,
                listOf(
                    replacement(1, 4, "626364", ""),
                    replacement(3, 5, "6465", ""),
                ),
                emptyMap(),
            )
        }
        assertEquals(SourcePatchErrorKind.REPLACEMENT_ORDER, error.kind)
        assertEquals("core.protocol.invalid-value@1", error.code)
        assertEquals(1, error.index)
    }

    /** Vector case source.patch.reject-target-mismatch (source-v1.json:144-148):
     * a patch whose declared target digest does not match the computed
     * result fails with core.source.patch-target-mismatch@1. */
    @Test
    fun wrongTargetDigestIsRejected() {
        val base = utf8("6162")
        val patch = SourcePatch.new(
            base.digest,
            ContentDigest.of("deliberately-wrong-target".toByteArray(Charsets.UTF_8)),
            base.encodingFacts,
            listOf(replacement(0, 2, "6162", "6364")),
            emptyMap(),
        )
        val error = assertFailsWith<SourcePatchException> { patch.apply(base) }
        assertEquals(SourcePatchErrorKind.TARGET_MISMATCH, error.kind)
        assertEquals("core.source.patch-target-mismatch@1", error.code)
    }

    /** Vector case source.patch.reject-encoding-change (source-v1.json:150-154):
     * a replacement that changes the resolved encoding (latin-1 base ->
     * UTF-16LE BOM bytes) fails with core.source.encoding-conflict@1. */
    @Test
    fun encodingChangeIsRejected() {
        val base = SourceSnapshot.fromRaw(
            hexToBytes("6162"),
            EncodingRequest.new(SourceEncoding.Latin1),
        )
        val patch = SourcePatch.new(
            base.digest,
            ContentDigest.of(hexToBytes("fffe4100")),
            base.encodingFacts,
            listOf(replacement(0, 2, "6162", "fffe4100")),
            emptyMap(),
        )
        val error = assertFailsWith<SourcePatchException> { patch.apply(base) }
        assertEquals(SourcePatchErrorKind.ENCODING_MISMATCH, error.kind)
        assertEquals("core.source.encoding-conflict@1", error.code)
    }

    /** Vector case source.resource.patch-count-limit (source-v1.json:168-172):
     * max_replacements 0 against one replacement fails with
     * core.source.resource-limit@1. */
    @Test
    fun replacementCountLimitIsEnforced() {
        val base = utf8("61")
        val limits = SourcePatchLimits(
            source = SourceLimits.default,
            maxReplacements = 0,
            maxPatchBytes = Int.MAX_VALUE,
        )
        val error = assertFailsWith<SourcePatchException> {
            SourcePatch.create(
                base,
                listOf(replacement(1, 1, "", "62")),
                emptyMap(),
                limits,
            )
        }
        assertEquals(SourcePatchErrorKind.SOURCE_RESOURCE_LIMIT, error.kind)
        assertEquals("core.source.resource-limit@1", error.code)
    }

    /** RFC 0003 §10: zero-width insertions are permitted, but two
     * replacements may not target the same insertion point. */
    @Test
    fun duplicateInsertionPointIsRejected() {
        val base = utf8("61")
        val error = assertFailsWith<SourcePatchException> {
            SourcePatch.create(
                base,
                listOf(
                    replacement(1, 1, "", "62"),
                    replacement(1, 1, "", "63"),
                ),
                emptyMap(),
            )
        }
        assertEquals(SourcePatchErrorKind.DUPLICATE_INSERTION, error.kind)
        assertEquals("core.protocol.invalid-value@1", error.code)
        assertEquals(1, error.index)
    }

    /** RFC 0003 §10: original byte count must equal the old range width. */
    @Test
    fun originalByteCountMustMatchRangeWidth() {
        val base = utf8("616263")
        val error = assertFailsWith<SourcePatchException> {
            SourcePatch.create(
                base,
                listOf(replacement(1, 2, "7878", "42")),
                emptyMap(),
            )
        }
        assertEquals(SourcePatchErrorKind.INVALID_REPLACEMENT, error.kind)
        assertEquals("core.protocol.invalid-value@1", error.code)
    }

    /** RFC 0003 §10: redaction flags control review presentation, not the
     * bytes required for application (source_patch.rs). */
    @Test
    fun redactedBytesRemainApplicableButHiddenFromPresentation() {
        val secret = replacement(0, 6, "736563726574", "68696464656e")
            .withOriginalRedacted(true)
            .withReplacementRedacted(true)
        assertTrue(secret.original().contentEquals("secret".toByteArray(Charsets.UTF_8)))
        assertTrue(secret.replacement().contentEquals("hidden".toByteArray(Charsets.UTF_8)))
        val rendered = secret.toString()
        assertFalse(rendered.contains("secret"))
        assertFalse(rendered.contains("hidden"))
        assertTrue(rendered.contains("<redacted>"))

        // Application still verifies the exact bytes (source_patch.rs).
        val base = utf8("736563726574")
        val patch = SourcePatch.create(base, listOf(secret), emptyMap())
            .withAllReplacementsRedacted(redactOriginal = true, redactReplacement = true)
        val target = patch.apply(base)
        assertEquals("68696464656e", bytesToHex(target.bytes()))
    }

    /** Patch construction validates limits before any allocation
     * (source_patch.rs): an oversized insertion fails at creation. */
    @Test
    fun patchByteLimitIsEnforcedAtCreation() {
        val base = utf8("61")
        val limits = SourcePatchLimits(
            source = SourceLimits(maxRawBytes = 2, maxDecodedUtf8Bytes = Int.MAX_VALUE, maxDecodedScalars = Int.MAX_VALUE),
            maxReplacements = 1,
            maxPatchBytes = 2,
        )
        val error = assertFailsWith<SourcePatchException> {
            SourcePatch.create(
                base,
                listOf(replacement(1, 1, "", "6c61726765")),
                emptyMap(),
                limits,
            )
        }
        assertEquals(SourcePatchErrorKind.SOURCE_RESOURCE_LIMIT, error.kind)
        assertEquals("core.source.resource-limit@1", error.code)
    }

    /** Zero replacements form a valid identity patch: apply returns a
     * snapshot with equal bytes and digest. */
    @Test
    fun emptyReplacementSetIsAnIdentityPatch() {
        val base = utf8("616263")
        val patch = SourcePatch.create(base, emptyList(), emptyMap())
        assertEquals(base.digest, patch.baseDigest)
        assertEquals(base.digest, patch.targetDigest)
        val target = patch.apply(base)
        assertTrue(target.bytes().contentEquals(base.bytes()))
        assertEquals(base.digest, target.digest)
    }
}
