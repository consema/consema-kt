// Transcriptions of the conformance/vectors/hcl-v1.json hcl.edit.* cases
// (:1462-1647, :2047-2080): the six structural operations, the conflict
// matrix, the untouched-byte proof, patch replay, and dry-run equivalence.

package hcl

import consema.document.EditPlanSourceId
import consema.hcl.BodyPath
import consema.hcl.BodyPlacement
import consema.hcl.EditValue
import consema.hcl.HclEditException
import consema.hcl.HclEditOperation
import consema.hcl.HclEditTransactionBuilder
import consema.hcl.HclNodeRef
import consema.hcl.HclProfile
import consema.hcl.commit
import consema.hcl.dryRun
import consema.hcl.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditTest {

    private fun native(source: String) =
        parse(source.toByteArray(Charsets.UTF_8), HclProfile.NATIVE_V1)

    private fun tfvars(source: String) =
        parse(source.toByteArray(Charsets.UTF_8), HclProfile.TFVARS_V1)

    /** Vector case hcl.edit.attribute-operations (hcl-v1.json:1462-1504):
     * insert First, set, rename, remove in one transaction; the commit
     * returns the new document, a replayable SourcePatch, and an untouched
     * byte proof. */
    @Test
    fun attributeOperations() {
        val document = native(Golden.EDIT_ATTRIBUTES_SOURCE)
        val transaction = HclEditTransactionBuilder.new(document)
            .insertAttribute(
                BodyPath.root(),
                "zone",
                EditValue.String("a"),
                BodyPlacement.First,
            )
            .setAttributeValue(BodyPath.root(), "count", EditValue.Integer(3))
            .renameAttribute(BodyPath.root(), "enabled", "active")
            .removeAttribute(BodyPath.root(), "region")
            .build()

        val commit = document.commit(transaction)
        assertEquals("zone = \"a\"\ncount = 3\nactive = true\n", commit.document.render().toString(Charsets.UTF_8))

        // The SourcePatch reapplies to the base and reproduces the exact
        // target digest (RFC 0004 §16).
        val reapplied = commit.sourcePatch.apply(document.source())
        assertEquals(
            commit.document.render().toString(Charsets.UTF_8),
            reapplied.bytes().toString(Charsets.UTF_8),
        )
        // The untouched-byte proof verifies against the exact snapshots
        // (RFC 0004 §15).
        commit.untouchedProof.verify(document.source(), commit.document.source(), commit.sourcePatch.replacements())
        // The new document reparses to the promised semantics.
        assertEquals(consema.document.FormationStatus.Complete, commit.document.formationStatus())
    }

    /** Vector case hcl.edit.block-operations (hcl-v1.json:1506-1549):
     * insert-block Last with nested attributes, then remove-block by exact
     * type and labels; labels always render quoted. */
    @Test
    fun blockOperations() {
        val document = native(Golden.EDIT_BLOCKS_SOURCE)
        val transaction = HclEditTransactionBuilder.new(document)
            .insertBlock(
                BodyPath.root(),
                "server",
                listOf("db"),
                listOf("port" to EditValue.Integer(5432)),
                BodyPlacement.Last,
            )
            .removeBlock(BodyPath.root(), "server", listOf("web"), 0)
            .build()

        val commit = document.commit(transaction)
        assertEquals("server \"db\" {\n  port = 5432\n}\n", commit.document.render().toString(Charsets.UTF_8))
        commit.untouchedProof.verify(document.source(), commit.document.source(), commit.sourcePatch.replacements())
    }

    /** Vector case hcl.edit.conflicts (hcl-v1.json:1551-1647): the frozen
     * conflict codes; the base stays byte-exact (atomicity). */
    @Test
    fun conflicts() {
        // Duplicate-attribute creation.
        val duplicate = native("count = 2\n")
        val duplicateTransaction = HclEditTransactionBuilder.new(duplicate)
            .insertAttribute(
                BodyPath.root(),
                "count",
                EditValue.Integer(3),
                BodyPlacement.Last,
            )
            .build()
        val duplicateFailure = assertFailsWith<HclEditException> {
            duplicate.commit(duplicateTransaction)
        }
        assertEquals("hcl.edit.duplicate-attribute@1", duplicateFailure.code)

        // Block insertion under hcl.tfvars@1.
        val tfvarsDocument = tfvars("region = \"x\"\n")
        val blockTransaction = HclEditTransactionBuilder.new(tfvarsDocument)
            .insertBlock(
                BodyPath.root(),
                "server",
                listOf("db"),
                emptyList(),
                BodyPlacement.Last,
            )
            .build()
        val blockFailure = assertFailsWith<HclEditException> {
            tfvarsDocument.commit(blockTransaction)
        }
        assertEquals("hcl.edit.block-in-tfvars@1", blockFailure.code)

        // A derived-expression value is refused by every commit.
        val expressionDocument = native("count = 2\n")
        val expressionTransaction = HclEditTransactionBuilder.new(expressionDocument)
            .setAttributeValue(
                BodyPath.root(),
                "count",
                EditValue.Expression("binary", "1 + 2"),
            )
            .build()
        val expressionFailure = assertFailsWith<HclEditException> {
            expressionDocument.commit(expressionTransaction)
        }
        assertEquals("hcl.edit.unrepresentable@1", expressionFailure.code)

        // A missing target is core.edit.incomplete-target@1.
        val missingDocument = native("count = 2\n")
        val missingTransaction = HclEditTransactionBuilder.new(missingDocument)
            .setAttributeValue(BodyPath.root(), "missing", EditValue.Integer(1))
            .build()
        val missingFailure = assertFailsWith<HclEditException> {
            missingDocument.commit(missingTransaction)
        }
        assertEquals("core.edit.incomplete-target@1", missingFailure.code)

        // A transaction bound to another snapshot is core.edit.wrong-snapshot@1.
        val other = native("other = 1\n")
        val otherTransaction = HclEditTransactionBuilder.new(other)
            .setAttributeValue(BodyPath.root(), "count", EditValue.Integer(9))
            .build()
        val wrongSnapshotFailure = assertFailsWith<HclEditException> {
            native("count = 2\n").commit(otherTransaction)
        }
        assertEquals("core.edit.wrong-snapshot@1", wrongSnapshotFailure.code)

        // The base is unchanged after every failed commit.
        assertEquals("count = 2\n", duplicate.render().toString(Charsets.UTF_8))
        assertEquals("region = \"x\"\n", tfvarsDocument.render().toString(Charsets.UTF_8))
        assertEquals("count = 2\n", expressionDocument.render().toString(Charsets.UTF_8))
        assertEquals("count = 2\n", missingDocument.render().toString(Charsets.UTF_8))
    }

    /** Vector case hcl.edit.dry-run-equivalence (hcl-v1.json:2047-2080):
     * dry-run and commit produce the same replacement set and target
     * digest. */
    @Test
    fun dryRunEquivalence() {
        val document = native(Golden.EDIT_DRY_RUN_SOURCE)
        val transaction = HclEditTransactionBuilder.new(document)
            .setAttributeValue(BodyPath.root(), "count", EditValue.Integer(7))
            .insertAttribute(
                BodyPath.root(),
                "zone",
                EditValue.String("b"),
                BodyPlacement.Last,
            )
            .build()

        val commit = document.commit(transaction)
        assertEquals(
            "region = \"us-east-1\"\ncount = 7\nenabled = true\nzone = \"b\"\n",
            commit.document.render().toString(Charsets.UTF_8),
        )
        val plan = document.dryRun(transaction, EditPlanSourceId.new("memory:hcl-conformance"))
        assertEquals(commit.sourcePatch.replacements(), plan.replacements())
        assertEquals(commit.sourcePatch.targetDigest, plan.targetDigest)
        assertEquals(commit.sourcePatch.baseDigest, plan.baseDigest)
    }

    /** The operation registry: `hcl.native@1` publishes the frozen six
     * operations; `hcl.tfvars@1` publishes the four attribute operations
     * only (operation_registry.rs:105-156). */
    @Test
    fun operationRegistry() {
        val nativeRegistry = consema.hcl.formatOperationRegistry(HclProfile.NATIVE_V1)
        assertEquals(
            listOf(
                "hcl.edit.insert-attribute@1",
                "hcl.edit.insert-block@1",
                "hcl.edit.remove-attribute@1",
                "hcl.edit.remove-block@1",
                "hcl.edit.rename-attribute@1",
                "hcl.edit.set-attribute-value@1",
            ),
            nativeRegistry.map { it.id.toString() },
        )
        val tfvarsRegistry = consema.hcl.formatOperationRegistry(HclProfile.TFVARS_V1)
        assertEquals(
            listOf(
                "hcl.edit.insert-attribute@1",
                "hcl.edit.remove-attribute@1",
                "hcl.edit.rename-attribute@1",
                "hcl.edit.set-attribute-value@1",
            ),
            tfvarsRegistry.map { it.id.toString() },
        )
        assertTrue(tfvarsRegistry.none { it.id.id.contains("block") })
    }

    /** Untouched bytes: every byte outside the replacement set is provably
     * identical (RFC 0004 §15). */
    @Test
    fun untouchedBytesAreProven() {
        val source = "# lead\nregion = \"us-east-1\"\ncount = 2\n"
        val document = native(source)
        val transaction = HclEditTransactionBuilder.new(document)
            .setAttributeValue(BodyPath.root(), "count", EditValue.Integer(3))
            .build()
        val commit = document.commit(transaction)
        commit.untouchedProof.verify(document.source(), commit.document.source(), commit.sourcePatch.replacements())
        // The leading comment and the untouched attribute survive byte-exact.
        assertTrue(commit.document.render().toString(Charsets.UTF_8).startsWith("# lead\nregion = \"us-east-1\"\n"))
    }
}
