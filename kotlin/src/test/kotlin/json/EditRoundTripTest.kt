// Edit round-trip tests transcribed from conformance/vectors/json-family-v2.json.
//
// Vector case json5.edit.move-member (json-family-v2.json:174-178) pins the
// golden move output, the dry-run/commit replacement and digest equality
// (patch_equal), and untouched-proof validity (proof_valid). Vector case
// json5.edit.preserve-scalars (json-family-v2.json:186-190) pins the
// PreserveCompatible scalar literal renderings. Vector case
// json5.edit.move-cross-object-rejected (json-family-v2.json:180-184) pins
// the TargetNotFound failure name.

package json

import consema.core.PvBinaryFloat64
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvString
import consema.document.AssociationPlacement
import consema.document.EditPlanSourceId
import consema.json.EditFailure
import consema.json.EditFailureException
import consema.json.EditTransactionBuilder
import consema.json.JsonProfile
import consema.json.RepresentationPolicy
import consema.json.SemanticAvailability
import consema.json.commit
import consema.json.dryRun
import consema.json.parse
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditRoundTripTest {

    /** Vector case json5.edit.move-member (json-family-v2.json:174-178):
     * moving the second member to Start keeps adjacent trivia/comments at
     * their original source positions, owns the comma edits, and produces
     * the golden bytes; the dry-run plan and the commit publish the same
     * replacement set and target digest, and the untouched-byte proof
     * verifies (RFC 0005 §10). */
    @Test
    fun moveMemberRoundTrip() {
        val source = "{ /*before*/ a:1, /*stay*/ b:2, c:3, }"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        val members = (document.root().objectMembers() as SemanticAvailability.Available).value!!
        val target = members[1]

        val builder = EditTransactionBuilder.new(document)
        builder.moveMember(target.nodeRef(), AssociationPlacement.Start)
        val transaction = builder.build()
        val commit = document.commit(transaction)

        assertEquals(
            "{ /*before*/ b:2,a:1, /*stay*/  c:3, }",
            commit.document.render().toString(Charsets.UTF_8),
        )

        val plan = document.dryRun(transaction, EditPlanSourceId.new("conformance.json5"))
        assertEquals(commit.sourcePatch.replacements(), plan.sourcePatch.replacements())
        assertEquals(commit.sourcePatch.targetDigest, plan.targetDigest)

        commit.untouchedProof.verify(
            document.source(),
            commit.document.source(),
            commit.sourcePatch.replacements(),
        )
    }

    /** Vector case json5.edit.move-cross-object-rejected (json-family-v2.json:
 *): a placement anchor in another Object is TargetNotFound. */
    @Test
    fun moveCrossObjectIsRejected() {
        val source = "{left:{a:1},right:{b:2}}"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        val left = (document.root().objectMembers() as SemanticAvailability.Available).value!![0]
        val right = (document.root().objectMembers() as SemanticAvailability.Available).value!![1]
        val leftMember =
            (left.value().objectMembers() as SemanticAvailability.Available).value!![0]
        val rightMember =
            (right.value().objectMembers() as SemanticAvailability.Available).value!![0]

        val builder = EditTransactionBuilder.new(document)
        builder.moveMember(
            leftMember.nodeRef(),
            AssociationPlacement.Before(rightMember.nodeRef()),
        )
        val error = assertFailsWith<EditFailureException> {
            document.commit(builder.build())
        }
        assertEquals(EditFailure.TargetNotFound, error.failure)
        assertEquals("TargetNotFound", error.failure.name)
    }

    /** Vector case json5.edit.preserve-scalars (json-family-v2.json:186-190):
     * PreserveCompatible keeps hex prefix/case, explicit plus, fraction
     * scale with leading point, string quote and per-character escapes, and
     * the non-finite sign category (RFC 0005 §5-§6). */
    @Test
    fun preserveScalarsKeepsLexicalStyle() {
        val source = "{hex:+0X0f,point:+.50,string:'a\\x20\\v',nf:+Infinity}"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.Json5StandardV1)
        val members = (document.root().objectMembers() as SemanticAvailability.Available).value!!

        val builder = EditTransactionBuilder.new(document)
        builder.semanticScalar(
            members[0].valueNodeRef(),
            PvInteger(BigInteger("16")),
            RepresentationPolicy.PreserveCompatible,
        )
        builder.semanticScalar(
            members[1].valueNodeRef(),
            PvDecimal.of(BigInteger("75"), BigInteger("-2")),
            RepresentationPolicy.PreserveCompatible,
        )
        builder.semanticScalar(
            members[2].valueNodeRef(),
            PvString("a \u000b"),
            RepresentationPolicy.PreserveCompatible,
        )
        builder.semanticScalar(
            members[3].valueNodeRef(),
            PvBinaryFloat64(0x7ff8_0000_0000_0000L),
            RepresentationPolicy.PreserveCompatible,
        )
        val commit = document.commit(builder.build())
        assertEquals(
            "{hex:+0X10,point:+.75,string:'a\\x20\\v',nf:+NaN}",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** Vector case edit.preserve-incompatible-rejected (v1.json:137-141):
     * PreserveCompatible fails rather than inventing a canonical spelling
     * when the fraction scale cannot represent the value. */
    @Test
    fun preserveCompatibleRejectsUnrepresentableScale() {
        val source = "{\"a\": 1.000}"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.StrictV1)
        val members = (document.root().objectMembers() as SemanticAvailability.Available).value!!

        val builder = EditTransactionBuilder.new(document)
        builder.semanticScalar(
            members[0].valueNodeRef(),
            PvDecimal.of(BigInteger("1"), BigInteger("-4")),
            RepresentationPolicy.PreserveCompatible,
        )
        val error = assertFailsWith<EditFailureException> {
            document.commit(builder.build())
        }
        assertEquals(EditFailure.RepresentationIncompatible, error.failure)
    }

    /** Vector case edit.canonical-for-profile (v1.json:125-129): the
     * profile-canonical decimal spelling is coefficient e exponent. */
    @Test
    fun canonicalForProfileUsesCanonicalDecimalSpelling() {
        val source = "{\"a\": 1.00}"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.StrictV1)
        val members = (document.root().objectMembers() as SemanticAvailability.Available).value!!

        val builder = EditTransactionBuilder.new(document)
        builder.semanticScalar(
            members[0].valueNodeRef(),
            PvDecimal.of(BigInteger("25"), BigInteger("-1")),
            RepresentationPolicy.CanonicalForProfile,
        )
        val commit = document.commit(builder.build())
        assertEquals("{\"a\": 25e-1}", commit.document.render().toString(Charsets.UTF_8))
    }

    /** RFC 0004 §14: a committed edit re-applies through its SourcePatch to
     * the exact committed bytes; a tampered base is rejected atomically. */
    @Test
    fun committedPatchReappliesAndDetectsTampering() {
        val source = "{\"a\": 1}"
        val document = parse(source.toByteArray(Charsets.UTF_8), JsonProfile.StrictV1)
        val members = (document.root().objectMembers() as SemanticAvailability.Available).value!!

        val builder = EditTransactionBuilder.new(document)
        builder.semanticScalar(
            members[0].valueNodeRef(),
            PvInteger(BigInteger("200")),
            RepresentationPolicy.PreserveCompatible,
        )
        val commit = document.commit(builder.build())
        val reapplied = commit.sourcePatch.apply(document.source())
        assertEquals(commit.document.source(), reapplied)

        // A stale base snapshot fails atomically with the frozen
        // patch-base-mismatch code (RFC 0003 §10).
        val tampered = document.source().let { base ->
            consema.document.SourceSnapshot.fromUtf8(
                "{\"a\": 9}".toByteArray(Charsets.UTF_8),
            )
        }
        val error = assertFailsWith<consema.document.SourcePatchException> {
            commit.sourcePatch.apply(tampered)
        }
        assertEquals("core.source.patch-base-mismatch@1", error.code)
        assertTrue(commit.diagnostics.isEmpty())
    }
}
