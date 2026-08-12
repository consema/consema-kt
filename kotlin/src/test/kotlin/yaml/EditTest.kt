// YAML structural edit tests: atomic transactions, anchor-safe rules,
// placement validation, dry-run plans, untouched-byte proofs, and the
// derived SourcePatch.
//
// Data authority: RFC 0007 §12 (docs/rfcs/0007-yaml-family-profiles-and-
// safety-v1.md:355-399) pins the operation registry, the anchor-safe rules
// (renaming updates dependent aliases in one transaction; removing an
// anchored definition while aliases remain is rejected; removing an alias
// does not remove its target; inserting an alias requires an earlier visible
// anchor; a scalar edit of an anchored node changes the shared graph node),
// and the at-most-one-structural-mutation-per-container rule. The golden
// vectors edit.scalar-atomic / edit.anchor-rename / edit.structural-insert /
// edit.anchor-dependency are transcribed in GoldenTranscriptionTest.kt.
//
// This file is an intent document: the toolchain is not verified yet, so
// these tests pin the intent; they run at the L2 verification gate.

package yaml

import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvString
import consema.document.AssociationPlacement
import consema.document.EditPlanSourceId
import consema.document.SourcePatchLimits
import consema.document.SourceLimits
import consema.yaml.EditFailure
import consema.yaml.EditFailureException
import consema.yaml.EditTransactionBuilder
import consema.yaml.RepresentationPolicy
import consema.yaml.YamlProfile
import consema.yaml.commit
import consema.yaml.dryRun
import consema.yaml.parse
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditTest {

    /** RFC 0007 §12: removing an alias does not remove its target; the
     * anchor definition and the shared node stay intact. */
    @Test
    fun removingAliasKeepsTarget() {
        val source = "first: &x one\ncopy: *x\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val root = document.document(0)!!.root()
        val alias = root.mappingEntry(1)!!.valueAlias()!!
        val transaction = EditTransactionBuilder.new(document)
            .removeMappingEntry(root.mappingEntry(1)!!.nodeRef())
            .build()
        val commit = document.commit(transaction)
        assertEquals("first: &x one\n", commit.document.render().toString(Charsets.UTF_8))
        // The alias is gone but its target anchor definition remains.
        assertEquals(0, commit.document.aliasCount())
        assertEquals("x", commit.document.document(0)!!.root().mappingEntry(0)!!.value().anchor())
    }

    /** RFC 0007 §12: renaming an anchor updates every dependent alias in
     * one transaction, and the dry-run plan matches the commit exactly
     * (RFC 0004 §20: same replacement set and target digest). */
    @Test
    fun anchorRenameDryRunMatchesCommit() {
        val source = "root: &node [one, *node, *node]\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val seq = document.document(0)!!.root().mappingEntry(0)!!.value()
        val transaction = EditTransactionBuilder.new(document)
            .renameAnchor(seq.anchorNodeRef()!!, "shared")
            .build()
        val commit = document.commit(transaction)
        assertEquals(
            "root: &shared [one, *shared, *shared]\n",
            commit.document.render().toString(Charsets.UTF_8),
        )
        assertEquals(3, commit.sourcePatch.replacements().size)

        val plan = document.dryRun(transaction, EditPlanSourceId.new("config.yaml"))
        assertEquals(commit.sourcePatch.replacements(), plan.replacements())
        assertEquals(commit.sourcePatch.targetDigest, plan.targetDigest)
    }

    /** RFC 0007 §12: a scalar edit of an anchored node changes the shared
     * graph node; aliases are not expanded or rewritten. */
    @Test
    fun scalarEditOfAnchoredNodeChangesSharedNode() {
        val source = "first: &x one\ncopy: *x\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val anchored = document.document(0)!!.root().mappingEntry(0)!!.value()
        val transaction = EditTransactionBuilder.new(document)
            .semanticScalar(
                anchored.nodeRef(),
                PvString("two"),
                RepresentationPolicy.PreserveCompatible,
            )
            .build()
        val commit = document.commit(transaction)
        assertEquals(
            "first: &x two\ncopy: *x\n",
            commit.document.render().toString(Charsets.UTF_8),
        )
        val newDocument = commit.document
        val alias = newDocument.alias(0)!!
        assertEquals("x", alias.name())
        assertEquals(
            newDocument.document(0)!!.root().mappingEntry(0)!!.value().nodeRef(),
            alias.target().nodeRef(),
        )
    }

    /** RFC 0007 §12: inserting an alias requires an earlier visible anchor
     * in the same document; an anchor defined after the insertion point is
     * rejected with yaml.edit.anchor-not-visible@1. */
    @Test
    fun aliasInsertionRequiresVisibleAnchor() {
        val source = "first: &x one\nseq: [two]\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val root = document.document(0)!!.root()
        val seq = root.mappingEntry(1)!!.value()
        val anchor = root.mappingEntry(0)!!.value()
        val transaction = EditTransactionBuilder.new(document)
            .insertAlias(seq.nodeRef(), anchor.anchorNodeRef()!!, AssociationPlacement.End)
            .build()
        val commit = document.commit(transaction)
        assertEquals("first: &x one\nseq: [two, *x]\n", commit.document.render().toString(Charsets.UTF_8))

        // An anchor that appears later in the document is not visible.
        val later = "seq: [two]\nlast: &y three\n"
        val laterDocument = parse(later.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val laterRoot = laterDocument.document(0)!!.root()
        val laterSeq = laterRoot.mappingEntry(0)!!.value()
        val laterAnchor = laterRoot.mappingEntry(1)!!.value()
        val rejected = EditTransactionBuilder.new(laterDocument)
            .insertAlias(laterSeq.nodeRef(), laterAnchor.anchorNodeRef()!!, AssociationPlacement.End)
            .build()
        val error = assertFailsWith<EditFailureException> { laterDocument.commit(rejected) }
        assertEquals(EditFailure.AnchorNotVisible, error.failure)
        assertEquals("yaml.edit.anchor-not-visible@1", error.failure.code)
    }

    /** RFC 0007 §12: at most one structural mutation per base container;
     * two insertions into the same sequence fail atomically with
     * yaml.edit.structural-container-conflict@1. */
    @Test
    fun sameContainerStructuralConflict() {
        // Two removals of different items in the same container (the Rust
        // edit.rs structural-container-conflict case, edit.rs:3209-3218):
        // the targets differ, so the same-container check fires.
        val source = "seq: [one, two]\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val seq = document.document(0)!!.root().mappingEntry(0)!!.value()
        val transaction = EditTransactionBuilder.new(document)
            .removeSequenceElement(seq.sequenceItem(0)!!.nodeRef())
            .removeSequenceElement(seq.sequenceItem(1)!!.nodeRef())
            .build()
        val error = assertFailsWith<EditFailureException> { document.commit(transaction) }
        assertEquals(EditFailure.StructuralContainerConflict, error.failure)
        assertEquals("yaml.edit.structural-container-conflict@1", error.failure.code)
    }

    /** RFC 0004 §13: a transaction bound to another snapshot is rejected
     * with core.edit.wrong-snapshot@1 and the base document stays
     * unchanged. */
    @Test
    fun wrongSnapshotRejected() {
        val source = "a: 1\n"
        val first = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val second = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val target = first.document(0)!!.root().mappingEntry(0)!!.value()
        val transaction = EditTransactionBuilder.new(first)
            .semanticScalar(
                target.nodeRef(),
                PvInteger(BigInteger.TWO),
                RepresentationPolicy.PreserveCompatible,
            )
            .build()
        val error = assertFailsWith<EditFailureException> { second.commit(transaction) }
        assertEquals(EditFailure.WrongSnapshot, error.failure)
        assertEquals(source, second.render().toString(Charsets.UTF_8))
    }

    /** RFC 0004 §15-§16: the committed edit derives a SourcePatch and an
     * untouched-byte proof; the patch reapplies to the base and reproduces
     * the exact committed bytes. */
    @Test
    fun patchReappliesAndProofVerifies() {
        val source = "# keep\na: 1\nb: two\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1)
        val value = document.document(0)!!.root().mappingEntry(0)!!.value()
        val transaction = EditTransactionBuilder.new(document)
            .semanticScalar(
                value.nodeRef(),
                PvInteger(BigInteger.valueOf(2)),
                RepresentationPolicy.PreserveCompatible,
            )
            .build()
        val commit = document.commit(transaction)
        val reapplied = commit.sourcePatch.apply(
            document.source(),
            SourcePatchLimits(
                source = SourceLimits.default,
                maxReplacements = 1,
                maxPatchBytes = 128 shl 20,
            ),
        )
        assertEquals(commit.document.source().bytes().toList(), reapplied.bytes().toList())
        assertTrue(commit.untouchedProof.regions().isNotEmpty())
    }

    /** RFC 0007 §12: UTF-16LE edits keep the encoding and produce
     * UTF-16LE replacement bytes. */
    @Test
    fun utf16EditsKeepEncoding() {
        val text = "a: 1\n"
        val utf16 = text.encodeToUtf16LEWithBom()
        val document = parse(utf16, YamlProfile.Yaml12CoreV1)
        val value = document.document(0)!!.root().mappingEntry(0)!!.value()
        val transaction = EditTransactionBuilder.new(document)
            .semanticScalar(
                value.nodeRef(),
                PvInteger(BigInteger.TWO),
                RepresentationPolicy.PreserveCompatible,
            )
            .build()
        val commit = document.commit(transaction)
        assertEquals(
            consema.document.SourceEncoding.Utf16Le,
            commit.document.source().encodingFacts.selected,
        )
        val rendered = commit.document.render()
        val bytes = rendered.drop(2) // BOM
        assertEquals("a: 2\n", String(bytes.toByteArray(), Charsets.UTF_16LE))
    }
}

/** Encodes one text as UTF-16LE with the matching BOM (RFC 0007 §11). */
private fun String.encodeToUtf16LEWithBom(): ByteArray {
    val units = length
    val output = ByteArray(units * 2 + 2)
    output[0] = 0xff.toByte()
    output[1] = 0xfe.toByte()
    for (index in indices) {
        val value = this[index].code
        output[index * 2 + 2] = (value and 0xff).toByte()
        output[index * 2 + 3] = (value shr 8).toByte()
    }
    return output
}
