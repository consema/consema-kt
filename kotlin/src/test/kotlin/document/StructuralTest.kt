// Formation status closure and structural coverage tests.
//
// Data authority: RFC 0016 §5.1 F10 (https://github.com/consema/consema/blob/main/docs/rfcs/0016-go-api-mapping-v1.md:
// 172-176) — FormationStatus is a closed two-value enum (Complete,
// Recovered); RFC 0003 §7 — binary coverage obeys the no-gap/no-overlap/
// final-length invariant; conformance/vectors/source-v1.json cases
// source.binary.* (lines 102-118, capability core.source.binary-coverage@1)
// pin the coverage semantics; consema-rs/consema-document/src/lib.rs:404-579
// pins the validation outcomes.

package document

import consema.document.BinaryRegion
import consema.document.BinaryStructuralIndex
import consema.document.DocumentAuthority
import consema.document.FormationStatus
import consema.document.LocationErrorKind
import consema.document.LocationException
import consema.document.LosslessStructuralIndex
import consema.document.NodeRole
import consema.document.StructuralPiece
import consema.document.StructuralPieceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StructuralTest {

    /** RFC 0016 §5.1 F10: FormationStatus is a closed binary enum — exactly
     * the two values Complete and Recovered exist, in that order. */
    @Test
    fun formationStatusIsClosedBinary() {
        val values = FormationStatus.entries
        assertEquals(listOf(FormationStatus.Complete, FormationStatus.Recovered), values)
        assertEquals("Complete", FormationStatus.Complete.name)
        assertEquals("Recovered", FormationStatus.Recovered.name)
    }

    /** Vector case source.binary.empty-coverage (source-v1.json:102-106):
     * an empty source has an empty valid index. */
    @Test
    fun emptyBinarySourceHasEmptyIndex() {
        val authority = DocumentAuthority.fresh()
        val index = BinaryStructuralIndex.new(authority.identity, 0, emptyList())
        assertEquals(0, index.regions().size)
    }

    /** Vector case source.binary.region-coverage (source-v1.json:108-112):
     * two adjacent regions covering the whole source are valid. */
    @Test
    fun adjacentBinaryRegionsCoverTheSource() {
        val authority = DocumentAuthority.fresh()
        val regions = listOf(
            BinaryRegion(authority.nodeRef(0, NodeRole.BinaryRegion), authority.span(0, 1), "header"),
            BinaryRegion(authority.nodeRef(1, NodeRole.BinaryRegion), authority.span(1, 4), "payload"),
        )
        val index = BinaryStructuralIndex.new(authority.identity, 4, regions)
        assertEquals(2, index.regions().size)
        assertEquals(4, index.regions().last().span.endByte)
    }

    /** Vector case source.binary.reject-gap (source-v1.json:114-118): a gap
     * between regions fails with the unregistered name
     * "IncompleteStructuralCoverage". */
    @Test
    fun binaryGapIsRejected() {
        val authority = DocumentAuthority.fresh()
        val regions = listOf(
            BinaryRegion(authority.nodeRef(0, NodeRole.BinaryRegion), authority.span(0, 1), "header"),
            BinaryRegion(authority.nodeRef(1, NodeRole.BinaryRegion), authority.span(2, 4), "payload"),
        )
        val error = assertFailsWith<LocationException> {
            BinaryStructuralIndex.new(authority.identity, 4, regions)
        }
        assertEquals(LocationErrorKind.IncompleteStructuralCoverage, error.kind)
        assertEquals("IncompleteStructuralCoverage", error.kind.name)
    }

    /** RFC 0003 §7 / lib.rs:549-557: a region kind must be non-empty. */
    @Test
    fun emptyBinaryRegionKindIsRejected() {
        val authority = DocumentAuthority.fresh()
        val regions = listOf(
            BinaryRegion(authority.nodeRef(0, NodeRole.BinaryRegion), authority.span(0, 2), ""),
        )
        val error = assertFailsWith<LocationException> {
            BinaryStructuralIndex.new(authority.identity, 2, regions)
        }
        assertEquals(LocationErrorKind.InvalidBinaryRegionKind, error.kind)
    }

    /** lib.rs:555-557: two regions must not reuse one process-local
     * identity. */
    @Test
    fun duplicateBinaryIdentityIsRejected() {
        val authority = DocumentAuthority.fresh()
        val shared = authority.nodeRef(0, NodeRole.BinaryRegion)
        val regions = listOf(
            BinaryRegion(shared, authority.span(0, 1), "header"),
            BinaryRegion(shared, authority.span(1, 2), "payload"),
        )
        val error = assertFailsWith<LocationException> {
            BinaryStructuralIndex.new(authority.identity, 2, regions)
        }
        assertEquals(LocationErrorKind.DuplicateStructuralIdentity, error.kind)
    }

    /** lib.rs:546-551: a region handle must carry the BinaryRegion role. */
    @Test
    fun wrongBinaryRegionRoleIsRejected() {
        val authority = DocumentAuthority.fresh()
        val regions = listOf(
            BinaryRegion(authority.nodeRef(0, NodeRole.Token), authority.span(0, 2), "payload"),
        )
        val error = assertFailsWith<LocationException> {
            BinaryStructuralIndex.new(authority.identity, 2, regions)
        }
        assertEquals(LocationErrorKind.WrongRole, error.kind)
    }

    /** RFC 0003 §7: text coverage (LosslessStructuralIndex) obeys the same
     * invariant over token/trivia/error-region pieces. */
    @Test
    fun losslessStructuralIndexCoversTheTextSource() {
        val authority = DocumentAuthority.fresh()
        val index = LosslessStructuralIndex.new(
            authority.identity,
            5,
            listOf(
                StructuralPiece(authority.span(0, 3), StructuralPieceKind.Token),
                StructuralPiece(authority.span(3, 5), StructuralPieceKind.Trivia),
            ),
        )
        assertEquals(2, index.pieces().size)
        assertEquals(StructuralPieceKind.Token, index.pieces()[0].kind)
    }

    @Test
    fun losslessStructuralIndexRejectsGapsAndOverlaps() {
        val authority = DocumentAuthority.fresh()
        val gap = assertFailsWith<LocationException> {
            LosslessStructuralIndex.new(
                authority.identity,
                5,
                listOf(
                    StructuralPiece(authority.span(0, 2), StructuralPieceKind.Token),
                    StructuralPiece(authority.span(3, 5), StructuralPieceKind.Trivia),
                ),
            )
        }
        assertEquals(LocationErrorKind.IncompleteStructuralCoverage, gap.kind)
        val overlap = assertFailsWith<LocationException> {
            LosslessStructuralIndex.new(
                authority.identity,
                5,
                listOf(
                    StructuralPiece(authority.span(0, 3), StructuralPieceKind.Token),
                    StructuralPiece(authority.span(2, 5), StructuralPieceKind.Trivia),
                ),
            )
        }
        assertEquals(LocationErrorKind.IncompleteStructuralCoverage, overlap.kind)
    }

    /** lib.rs:463-468: a piece from another snapshot is rejected. */
    @Test
    fun structuralIndexRejectsForeignSpans() {
        val authority = DocumentAuthority.fresh()
        val other = DocumentAuthority.fresh()
        val error = assertFailsWith<LocationException> {
            LosslessStructuralIndex.new(
                authority.identity,
                5,
                listOf(StructuralPiece(other.span(0, 5), StructuralPieceKind.Token)),
            )
        }
        assertEquals(LocationErrorKind.WrongSnapshot, error.kind)
    }

    /** lib.rs:83-93: an inverted span is rejected at creation. */
    @Test
    fun invertedSpanIsRejected() {
        val authority = DocumentAuthority.fresh()
        val error = assertFailsWith<LocationException> { authority.span(4, 2) }
        assertEquals(LocationErrorKind.InvertedSpan, error.kind)
        assertTrue(authority.span(2, 2).isEmpty)
    }
}
