// Formation status and the exhaustive structural coverage indexes.
//
// Data authority:
//   - RFC 0016 §5.1 F10 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md:172-176):
//     FormationStatus is a closed two-value enum (Complete, Recovered).
//   - RFC 0003 §7 (https://github.com/consema/consema/blob/main/docs/rfcs/0003-source-syntax-query-and-patch-v1.md:163-171):
//     text and binary coverage obey the same no-gap/no-overlap/final-length
//     invariant; empty source has an empty valid index; non-empty source
//     requires at least one non-empty region.
//   - consema-rs/consema-document/src/lib.rs:404-579 pins the shapes and the
//     LocationError validation outcomes; conformance/vectors/source-v1.json
//     cases source.binary.* (lines 102-118) pin the coverage semantics.
//   - consema-go/go/document/structural.go and consema-go/go/document/formation.go are
//     cross-references only.

package consema.document

/**
 * Successful document formation state (RFC 0016 §5.1 F10; lib.rs:404-411).
 * Closed binary enum: exactly [Complete] and [Recovered] exist; the facade
 * exposes only the formation-status equivalent, no status alias (RFC 0016
 * §5.1).
 */
enum class FormationStatus {
    /** Entire syntax was formed without recovery. */
    Complete,

    /** A complete snapshot with explicit recovery structure was formed. */
    Recovered,
}

/** One exhaustive source-byte classification (lib.rs:413-422). */
enum class StructuralPieceKind {
    /** Lexical token. */
    Token,

    /** Whitespace, newline, comment, or profile trivia. */
    Trivia,

    /** Bytes not accepted as token or trivia. */
    ErrorRegion,
}

/** One source byte interval and its lossless class (lib.rs:424-449). */
data class StructuralPiece(
    /** Exact source range. */
    val span: Span,
    /** Classification. */
    val kind: StructuralPieceKind,
)

/**
 * Exhaustive ordered token/trivia/error-region coverage of one text source
 * (RFC 0003 §7; lib.rs:451-490). Validates exact snapshot binding and the
 * no-gap/no-overlap/final-length invariant.
 */
class LosslessStructuralIndex private constructor(
    private val pieces: List<StructuralPiece>,
) {
    companion object {
        /** Validates exact source coverage and stores pieces in structural
         * order (lib.rs:458-483). Throws [LocationException] on a gap,
         * overlap, empty interval, wrong final length, or wrong snapshot. */
        fun new(identity: SnapshotIdentity, sourceLen: Int, pieces: List<StructuralPiece>):
            LosslessStructuralIndex {
            var next = 0
            for (piece in pieces) {
                val span = piece.span
                if (span.snapshot != identity) {
                    throw LocationException(LocationErrorKind.WrongSnapshot)
                }
                if (span.startByte != next || span.endByte <= span.startByte ||
                    span.endByte > sourceLen
                ) {
                    throw LocationException(LocationErrorKind.IncompleteStructuralCoverage)
                }
                next = span.endByte
            }
            if (next != sourceLen) {
                throw LocationException(LocationErrorKind.IncompleteStructuralCoverage)
            }
            return LosslessStructuralIndex(pieces)
        }
    }

    /** Ordered exhaustive pieces. */
    fun pieces(): List<StructuralPiece> = pieces
}

/** One format-owned region in an opaque binary source (lib.rs:492-528). */
data class BinaryRegion(
    /** Process-local structural identity. */
    val nodeRef: NodeRef,
    /** Exact raw byte range. */
    val span: Span,
    /** Non-empty stable format-owned kind. */
    val kind: String,
)

/**
 * Exhaustive ordered format-owned region coverage for one opaque binary
 * source (RFC 0003 §7; lib.rs:530-579). Binary coverage obeys the same
 * no-gap/no-overlap/final-length invariant but does not call bytes tokens or
 * trivia. Empty source has an empty valid index; non-empty source requires
 * at least one non-empty region.
 */
class BinaryStructuralIndex private constructor(
    private val regions: List<BinaryRegion>,
) {
    companion object {
        /** Validates exact raw-byte coverage, snapshot binding, roles, kinds,
         * and unique identities (lib.rs:537-572). Throws [LocationException]
         * on any violation. */
        fun new(identity: SnapshotIdentity, sourceLen: Int, regions: List<BinaryRegion>):
            BinaryStructuralIndex {
            var next = 0
            val identities = HashSet<NodeRef>()
            for (region in regions) {
                if (region.span.snapshot != identity || region.nodeRef.snapshot != identity) {
                    throw LocationException(LocationErrorKind.WrongSnapshot)
                }
                if (region.nodeRef.role != NodeRole.BinaryRegion) {
                    throw LocationException(LocationErrorKind.WrongRole)
                }
                if (region.kind.isEmpty()) {
                    throw LocationException(LocationErrorKind.InvalidBinaryRegionKind)
                }
                if (!identities.add(region.nodeRef)) {
                    throw LocationException(LocationErrorKind.DuplicateStructuralIdentity)
                }
                if (region.span.startByte != next || region.span.endByte <= region.span.startByte ||
                    region.span.endByte > sourceLen
                ) {
                    throw LocationException(LocationErrorKind.IncompleteStructuralCoverage)
                }
                next = region.span.endByte
            }
            if (next != sourceLen) {
                throw LocationException(LocationErrorKind.IncompleteStructuralCoverage)
            }
            return BinaryStructuralIndex(regions)
        }
    }

    /** Ordered exhaustive regions. */
    fun regions(): List<BinaryRegion> = regions
}
