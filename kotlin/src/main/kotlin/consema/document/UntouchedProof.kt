// Verifiable proof that planned replacements did not alter surrounding bytes.
//
// Data authority:
//   - RFC 0004 §15 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-structural-
//     edit-v1.md:358-372) freezes the proof contract: an ordered cover of all
//     old-source intervals outside replacements mapped to target intervals;
//     old regions exactly cover every non-replaced old byte once, new regions
//     exactly cover every non-inserted new byte once, each mapped region has
//     equal length and equal bytes, region order is monotonic, and base and
//     target digests match the proof. The proof asserts only that bytes
//     outside planned replacements are identical.
//   - https://github.com/consema/consema-rs/blob/main/consema-document/src/untouched_proof.rs:1-317 pins the shapes
//     and validation rules; consema-go/go/document/untouched.go is a cross-reference
//     only.

package consema.document

/** One maximal unchanged raw-byte interval mapped across two source
 * snapshots (untouched_proof.rs:7-59). The enclosing proof validates length
 * and ordering. */
data class UntouchedByteRegion(
    /** Inclusive start in the base snapshot. */
    val oldStart: Int,
    /** Exclusive end in the base snapshot. */
    val oldEnd: Int,
    /** Inclusive start in the target snapshot. */
    val newStart: Int,
    /** Exclusive end in the target snapshot. */
    val newEnd: Int,
)

/** Stable proof construction or verification failure
 * (untouched_proof.rs:134-172). */
enum class UntouchedByteProofErrorKind {
    /** Base and target encoding facts differ. */
    EncodingMismatch,

    /** A replacement has an inverted or out-of-bounds old interval. */
    InvalidReplacement,

    /** Replacements are not in canonical non-overlapping order. */
    ReplacementOrder,

    /** Two replacements target the same insertion point. */
    DuplicateInsertion,

    /** Base bytes do not satisfy an original-byte precondition. */
    OriginalMismatch,

    /** Supplied target bytes are not the exact result of the replacement
     * set. */
    TargetMismatch,

    /** A target coordinate calculation overflowed. */
    CoordinateOverflow,

    /** A transferred region has an invalid range, unequal lengths, order, or
     * canonicality. */
    InvalidRegion,

    /** Supplied snapshots do not have the proof's declared digests. */
    DigestMismatch,

    /** Region facts differ from the canonical proof of the supplied
     * replacement set. */
    ProofMismatch,
    ;
}

/** The typed proof failure; [index] names the offending zero-based
 * replacement or region where applicable. */
class UntouchedByteProofException(
    val kind: UntouchedByteProofErrorKind,
    val index: Int = -1,
) : Exception(
    "untouched-proof: $kind" + (if (index >= 0) " at index $index" else ""),
)

/**
 * Immutable evidence for every byte outside one exact replacement plan
 * (RFC 0004 §15; untouched_proof.rs:61-132).
 */
class UntouchedByteProof private constructor(
    /** Required base digest. */
    val baseDigest: ContentDigest,
    /** Required target digest. */
    val targetDigest: ContentDigest,
    private val regions: List<UntouchedByteRegion>,
) {
    companion object {
        /**
         * Creates a proof only when the replacements exactly produce the
         * supplied target snapshot (untouched_proof.rs:70-82).
         */
        fun create(
            base: SourceSnapshot,
            target: SourceSnapshot,
            replacements: List<SourceReplacement>,
        ): UntouchedByteProof {
            val regions = expectedRegions(base, target, replacements)
            return UntouchedByteProof(base.digest, target.digest, regions)
        }

        /**
         * Constructs transferable proof facts after validating their
         * canonical structure (untouched_proof.rs:84-96).
         */
        fun fromFacts(
            baseDigest: ContentDigest,
            targetDigest: ContentDigest,
            regions: List<UntouchedByteRegion>,
        ): UntouchedByteProof {
            validateRegions(regions)
            return UntouchedByteProof(baseDigest, targetDigest, regions)
        }
    }

    /** Canonical maximal unchanged regions. */
    fun regions(): List<UntouchedByteRegion> = regions

    /**
     * Rechecks digests, replacement preconditions, exact target bytes, and
     * every region fact (untouched_proof.rs:98-113; RFC 0004 §15).
     */
    fun verify(
        base: SourceSnapshot,
        target: SourceSnapshot,
        replacements: List<SourceReplacement>,
    ) {
        if (base.digest != baseDigest || target.digest != targetDigest) {
            throw UntouchedByteProofException(UntouchedByteProofErrorKind.DigestMismatch)
        }
        val expected = expectedRegions(base, target, replacements)
        if (expected != regions) {
            throw UntouchedByteProofException(UntouchedByteProofErrorKind.ProofMismatch)
        }
    }
}

/** Computes the canonical maximal unchanged regions of one replacement set
 * (untouched_proof.rs:182-245). */
private fun expectedRegions(
    base: SourceSnapshot,
    target: SourceSnapshot,
    replacements: List<SourceReplacement>,
): List<UntouchedByteRegion> {
    if (base.encodingFacts != target.encodingFacts) {
        throw UntouchedByteProofException(UntouchedByteProofErrorKind.EncodingMismatch)
    }
    val regions = ArrayList<UntouchedByteRegion>(replacements.size + 1)
    var oldCursor = 0
    var newCursor = 0
    var previous: SourceReplacement? = null
    for ((index, replacement) in replacements.withIndex()) {
        validateReplacement(base, previous, replacement, index)

        val unchangedLen = replacement.oldStart - oldCursor
        val newUnchangedEnd = checkedAddProof(newCursor, unchangedLen)
        if (!bytesEqual(target.rawBytes(), newCursor, newUnchangedEnd, base.rawBytes(), oldCursor, replacement.oldStart)) {
            throw UntouchedByteProofException(UntouchedByteProofErrorKind.TargetMismatch)
        }
        pushRegion(
            regions,
            UntouchedByteRegion(oldCursor, replacement.oldStart, newCursor, newUnchangedEnd),
        )

        val replacementEnd = checkedAddProof(newUnchangedEnd, replacement.replacementBytes().size)
        if (!bytesEqual(
                target.rawBytes(), newUnchangedEnd, replacementEnd,
                replacement.replacementBytes(), 0, replacement.replacementBytes().size,
            )
        ) {
            throw UntouchedByteProofException(UntouchedByteProofErrorKind.TargetMismatch)
        }
        oldCursor = replacement.oldEnd
        newCursor = replacementEnd
        previous = replacement
    }

    val tailLen = base.rawBytes().size - oldCursor
    val newEnd = checkedAddProof(newCursor, tailLen)
    if (newEnd != target.rawBytes().size ||
        !bytesEqual(target.rawBytes(), newCursor, newEnd, base.rawBytes(), oldCursor, base.rawBytes().size)
    ) {
        throw UntouchedByteProofException(UntouchedByteProofErrorKind.TargetMismatch)
    }
    pushRegion(regions, UntouchedByteRegion(oldCursor, base.rawBytes().size, newCursor, newEnd))
    validateRegions(regions)
    return regions
}

/** Validates one replacement's range, order, and original-byte precondition
 * (untouched_proof.rs:247-281). */
private fun validateReplacement(
    base: SourceSnapshot,
    previous: SourceReplacement?,
    replacement: SourceReplacement,
    index: Int,
) {
    // Negative offsets are impossible in the Rust usize surface; Kotlin
    // Ints require the explicit guard (untouched_proof.rs:253-258).
    if (replacement.oldStart < 0 || replacement.oldEnd < 0 ||
        replacement.oldStart > replacement.oldEnd ||
        replacement.oldEnd > base.rawBytes().size ||
        replacement.originalBytes().size != replacement.oldEnd - replacement.oldStart
    ) {
        throw UntouchedByteProofException(UntouchedByteProofErrorKind.InvalidReplacement, index)
    }
    previous?.let {
        if (replacement.oldStart == replacement.oldEnd &&
            it.oldStart == it.oldEnd &&
            replacement.oldStart == it.oldStart
        ) {
            throw UntouchedByteProofException(UntouchedByteProofErrorKind.DuplicateInsertion, index)
        }
        if (replacement.oldStart < it.oldStart ||
            (replacement.oldStart == it.oldStart && replacement.oldEnd <= it.oldEnd) ||
            replacement.oldStart < it.oldEnd
        ) {
            throw UntouchedByteProofException(UntouchedByteProofErrorKind.ReplacementOrder, index)
        }
    }
    if (!bytesEqual(
            base.rawBytes(), replacement.oldStart, replacement.oldEnd,
            replacement.originalBytes(), 0, replacement.originalBytes().size,
        )
    ) {
        throw UntouchedByteProofException(UntouchedByteProofErrorKind.OriginalMismatch, index)
    }
}

/** Merges adjacent regions and skips zero-width ones (untouched_proof.rs:283-295). */
private fun pushRegion(regions: MutableList<UntouchedByteRegion>, region: UntouchedByteRegion) {
    if (region.oldStart == region.oldEnd) {
        return
    }
    val previous = regions.lastOrNull()
    if (previous != null && previous.oldEnd == region.oldStart && previous.newEnd == region.newStart) {
        regions[regions.size - 1] = UntouchedByteRegion(previous.oldStart, region.oldEnd, previous.newStart, region.newEnd)
        return
    }
    regions.add(region)
}

/** Validates the canonical region structure (untouched_proof.rs:297-317). */
private fun validateRegions(regions: List<UntouchedByteRegion>) {
    var previous: UntouchedByteRegion? = null
    for ((index, region) in regions.withIndex()) {
        if (region.oldStart >= region.oldEnd ||
            region.newStart >= region.newEnd ||
            region.oldEnd - region.oldStart != region.newEnd - region.newStart
        ) {
            throw UntouchedByteProofException(UntouchedByteProofErrorKind.InvalidRegion, index)
        }
        previous?.let {
            if (region.oldStart < it.oldEnd ||
                region.newStart < it.newEnd ||
                (region.oldStart == it.oldEnd && region.newStart == it.newEnd)
            ) {
                throw UntouchedByteProofException(UntouchedByteProofErrorKind.InvalidRegion, index)
            }
        }
        previous = region
    }
}

private fun checkedAddProof(left: Int, right: Int): Int {
    if (left > Int.MAX_VALUE - right) {
        throw UntouchedByteProofException(UntouchedByteProofErrorKind.CoordinateOverflow)
    }
    return left + right
}

private fun bytesEqual(
    a: ByteArray, aStart: Int, aEnd: Int,
    b: ByteArray, bStart: Int, bEnd: Int,
): Boolean {
    if (aEnd - aStart != bEnd - bStart || aEnd > a.size || bEnd > b.size) {
        return false
    }
    for (i in 0 until (aEnd - aStart)) {
        if (a[aStart + i] != b[bStart + i]) {
            return false
        }
    }
    return true
}
