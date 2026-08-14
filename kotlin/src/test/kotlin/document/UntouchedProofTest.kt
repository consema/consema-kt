// UntouchedByteProof behavior tests.
//
// Data authority: RFC 0004 §15 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-
// and-structural-edit-v1.md:358-372) — the proof is an ordered cover of all
// old-source intervals outside replacements mapped to target intervals, with
// equal length and equal bytes per mapped region, monotonic order, and base
// and target digests matching the proof. The scenario (base "abXXcd!" ->
// target ">abYYYcd" with three replacements) and the expected regions
// (0,2)->(1,3) and (4,6)->(6,8) follow https://github.com/consema/consema-rs/blob/main/consema-document/src/
// untouched_proof.rs:335-401 (the crate's own test data, which the vector
// suite does not cover directly).

package document

import consema.document.ContentDigest
import consema.document.SourceReplacement
import consema.document.UntouchedByteProof
import consema.document.UntouchedByteProofErrorKind
import consema.document.UntouchedByteProofException
import consema.document.UntouchedByteRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UntouchedProofTest {

    private fun utf8(text: String) =
        consema.document.SourceSnapshot.fromUtf8(text.toByteArray(Charsets.UTF_8))

    private fun replacements(): List<SourceReplacement> = listOf(
        SourceReplacement.new(0, 0, ByteArray(0), ">".toByteArray(Charsets.UTF_8)),
        SourceReplacement.new(2, 4, "XX".toByteArray(Charsets.UTF_8), "YYY".toByteArray(Charsets.UTF_8)),
        SourceReplacement.new(6, 7, "!".toByteArray(Charsets.UTF_8), ByteArray(0)),
    )

    /** RFC 0004 §15 + untouched_proof.rs:336-349: the proof covers every
     * and only the untouched byte, and verification succeeds. */
    @Test
    fun proofCoversEveryAndOnlyUntouchedByte() {
        val base = utf8("abXXcd!")
        val target = utf8(">abYYYcd")
        val proof = UntouchedByteProof.create(base, target, replacements())
        assertEquals(
            listOf(
                UntouchedByteRegion(0, 2, 1, 3),
                UntouchedByteRegion(4, 6, 6, 8),
            ),
            proof.regions(),
        )
        proof.verify(base, target, replacements())
    }

    /** untouched_proof.rs:351-377: tampering with a region, the target
     * digest, or the target bytes is detected. */
    @Test
    fun proofDetectsRegionDigestAndTargetTampering() {
        val base = utf8("abXXcd!")
        val target = utf8(">abYYYcd")
        val tampered = UntouchedByteProof.fromFacts(
            base.digest,
            target.digest,
            listOf(
                UntouchedByteRegion(0, 2, 0, 2),
                UntouchedByteRegion(4, 6, 6, 8),
            ),
        )
        val proofError = assertFailsWith<UntouchedByteProofException> {
            tampered.verify(base, target, replacements())
        }
        assertEquals(UntouchedByteProofErrorKind.ProofMismatch, proofError.kind)

        val digestError = assertFailsWith<UntouchedByteProofException> {
            tampered.verify(base, utf8(">abYYYcD"), replacements())
        }
        assertEquals(UntouchedByteProofErrorKind.DigestMismatch, digestError.kind)

        val targetError = assertFailsWith<UntouchedByteProofException> {
            UntouchedByteProof.create(base, utf8(">aBYYYcd"), replacements())
        }
        assertEquals(UntouchedByteProofErrorKind.TargetMismatch, targetError.kind)
    }

    /** untouched_proof.rs:379-385: no replacements prove the complete
     * snapshot as one region. */
    @Test
    fun noReplacementsProveTheCompleteSnapshot() {
        val source = utf8("same")
        val proof = UntouchedByteProof.create(source, source, emptyList())
        assertEquals(listOf(UntouchedByteRegion(0, 4, 0, 4)), proof.regions())
        proof.verify(source, source, emptyList())
    }

    /** untouched_proof.rs:388-401: transferred proofs reject non-canonical
     * (mergeable-adjacent) regions. */
    @Test
    fun transferredProofRejectsNonCanonicalRegions() {
        val digest = ContentDigest.of("abc".toByteArray(Charsets.UTF_8))
        val error = assertFailsWith<UntouchedByteProofException> {
            UntouchedByteProof.fromFacts(
                digest,
                digest,
                listOf(
                    UntouchedByteRegion(0, 1, 0, 1),
                    UntouchedByteRegion(1, 3, 1, 3),
                ),
            )
        }
        assertEquals(UntouchedByteProofErrorKind.InvalidRegion, error.kind)
        assertEquals(1, error.index)
    }

    /** untouched_proof.rs:187-189: a proof across differing encoding facts
     * is refused. */
    @Test
    fun proofRequiresEqualEncodingFacts() {
        val base = utf8("ab")
        val target = consema.document.SourceSnapshot.fromRaw(
            byteArrayOf(0x61, 0x62),
            consema.document.EncodingRequest.new(consema.document.SourceEncoding.Latin1),
        )
        val error = assertFailsWith<UntouchedByteProofException> {
            UntouchedByteProof.create(base, target, emptyList())
        }
        assertEquals(UntouchedByteProofErrorKind.EncodingMismatch, error.kind)
    }

    /** RFC 0004 §15: old regions exactly cover every non-replaced old byte
     * once — a replacement whose original bytes do not match the base is
     * detected before any region is produced. */
    @Test
    fun proofRejectsOriginalByteMismatch() {
        val base = utf8("abXXcd!")
        val target = utf8(">abYYYcd")
        val bad = listOf(
            SourceReplacement.new(0, 0, ByteArray(0), ">".toByteArray(Charsets.UTF_8)),
            SourceReplacement.new(2, 4, "ZZ".toByteArray(Charsets.UTF_8), "YYY".toByteArray(Charsets.UTF_8)),
            SourceReplacement.new(6, 7, "!".toByteArray(Charsets.UTF_8), ByteArray(0)),
        )
        val error = assertFailsWith<UntouchedByteProofException> {
            UntouchedByteProof.create(base, target, bad)
        }
        assertEquals(UntouchedByteProofErrorKind.OriginalMismatch, error.kind)
        assertEquals(1, error.index)
    }
}
