// Edit transcriptions from conformance/vectors/java-properties-v1.json.
//
// The five frozen operations are replace-semantic-value,
// replace-literal-value, insert-property, remove-property, and
// rename-property (RFC 0010 §13); commits are atomic and close through
// reparse, SourcePatch replay, and untouched-byte proof (RFC 0004 §13-§16).
// Case ids are cited on every test; these tests pin the intent and run at
// the L2 verification gate.

package properties

import consema.document.AssociationPlacement
import consema.document.EditPlanSourceId
import consema.document.SourceEncoding
import consema.properties.EditFailure
import consema.properties.EditFailureException
import consema.properties.EditTransactionBuilder
import consema.properties.JavaString
import consema.properties.PropertiesProfile
import consema.properties.dryRun
import consema.properties.editFailureCode
import consema.properties.formatOperationRegistry
import consema.properties.commit
import consema.properties.parseReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditTest {

    /** Vector case edit.all-five-operations (java-properties-v1.json:105-109):
     * each operation alone produces the exact golden output with exactly one
     * source edit. */
    @Test
    fun allFiveOperations() {
        val base = parseReader("a=1\nb=2\n".toByteArray(Charsets.UTF_8), SourceEncoding.Utf8)
        val first = base.properties()[0].nodeRef()
        val root = base.nodeRef()

        val semantic = base.commit(
            EditTransactionBuilder.new(base)
                .semanticValue(first, JavaString.fromUnicode("changed"))
                .build(),
        )
        assertEquals("a=changed\nb=2\n", semantic.document.render().toString(Charsets.UTF_8))
        assertEquals(1, semantic.sourcePatch.replacements().size)

        val literal = base.commit(
            EditTransactionBuilder.new(base)
                .literalValue(first, "raw\\ value".toByteArray(Charsets.UTF_8))
                .build(),
        )
        assertEquals("a=raw\\ value\nb=2\n", literal.document.render().toString(Charsets.UTF_8))
        assertEquals("raw value", literal.document.properties()[0].value().toUnicode())
        assertEquals(1, literal.sourcePatch.replacements().size)

        val inserted = base.commit(
            EditTransactionBuilder.new(base)
                .insertProperty(
                    root,
                    JavaString.fromUnicode("c"),
                    JavaString.fromUnicode("3"),
                    AssociationPlacement.End,
                )
                .build(),
        )
        assertEquals("a=1\nb=2\nc=3\n", inserted.document.render().toString(Charsets.UTF_8))
        assertEquals(1, inserted.sourcePatch.replacements().size)

        val removed = base.commit(
            EditTransactionBuilder.new(base).removeProperty(first).build(),
        )
        assertEquals("b=2\n", removed.document.render().toString(Charsets.UTF_8))
        assertEquals(1, removed.sourcePatch.replacements().size)

        val renamed = base.commit(
            EditTransactionBuilder.new(base)
                .renameProperty(first, JavaString.fromUnicode("renamed"))
                .build(),
        )
        assertEquals("renamed=1\nb=2\n", renamed.document.render().toString(Charsets.UTF_8))
        assertEquals(1, renamed.sourcePatch.replacements().size)
    }

    /** Vector case edit.dry-run-patch-proof-conflict-atomicity
     * (java-properties-v1.json:110-114): a two-operation transaction commits
     * atomically with two source edits; the dry-run plan reports both
     * operations; the patch replays onto the base and the untouched proof
     * verifies; conflicting edits fail with core.edit.conflicting-edits@1
     * and leave the base unchanged. */
    @Test
    fun dryRunPatchProofConflictAtomicity() {
        val base = parseReader("a=one\nb=two\n".toByteArray(Charsets.UTF_8), SourceEncoding.Utf8)
        val transaction = EditTransactionBuilder.new(base)
            .renameProperty(base.properties()[0].nodeRef(), JavaString.fromUnicode("first"))
            .semanticValue(base.properties()[1].nodeRef(), JavaString.fromUnicode("changed"))
            .build()

        val commit = base.commit(transaction)
        assertEquals(
            "first=one\nb=changed\n",
            commit.document.render().toString(Charsets.UTF_8),
        )
        assertEquals(2, commit.changeSet.sourceEdits.size)
        assertEquals(2, commit.sourcePatch.replacements().size)

        val plan = base.dryRun(transaction, EditPlanSourceId.new("fixture.properties"))
        assertEquals(2, plan.operations().size)
        assertEquals(
            "java-properties.edit.rename-property@1",
            plan.operations()[0].operation.toString(),
        )
        assertEquals(plan.sourcePatch, commit.sourcePatch)

        val replayed = commit.sourcePatch.apply(base.source())
        assertEquals(
            commit.document.render().toList(),
            replayed.bytes().toList(),
        )
        commit.untouchedProof.verify(base.source(), commit.document.source(), commit.sourcePatch.replacements())

        // The conflict: inserting after a property removed by the same
        // transaction is PlacementAnchorRemoved -> conflicting-edits@1.
        val conflicting = EditTransactionBuilder.new(base)
            .removeProperty(base.properties()[0].nodeRef())
            .insertProperty(
                base.nodeRef(),
                JavaString.fromUnicode("x"),
                JavaString.fromUnicode("0"),
                AssociationPlacement.After(base.properties()[0].nodeRef()),
            )
            .build()
        val failure = assertFailsWith<EditFailureException> { base.commit(conflicting) }
        assertEquals(EditFailure.PlacementAnchorRemoved, failure.failure)
        assertEquals("core.edit.conflicting-edits@1", editFailureCode(failure.failure))
        assertEquals("a=one\nb=two\n", base.render().toString(Charsets.UTF_8))
    }

    /** RFC 0010 §13: semantic replacement preserves a direct style and
     * reports the canonical fallback only when required; exact unpaired Java
     * units round-trip through canonical escapes. */
    @Test
    fun semanticValuePreservesDirectStyleAndUnpairedUnits() {
        val direct = parseReader("a=one\n".toByteArray(Charsets.UTF_8), SourceEncoding.Utf8)
        val directCommit = direct.commit(
            EditTransactionBuilder.new(direct)
                .semanticValue(direct.properties()[0].nodeRef(), JavaString.fromUnicode("two words"))
                .build(),
        )
        assertEquals("a=two words\n", directCommit.document.render().toString(Charsets.UTF_8))
        assertEquals(0, directCommit.changeSet.diagnostics.size)

        val escaped = parseReader(
            "a=one\\ value\n".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val fallback = escaped.commit(
            EditTransactionBuilder.new(escaped)
                .semanticValue(escaped.properties()[0].nodeRef(), JavaString.fromUnicode("next value"))
                .build(),
        )
        assertEquals("a=next value\n", fallback.document.render().toString(Charsets.UTF_8))
        assertEquals(
            "java-properties.edit.canonical-fallback@1",
            fallback.changeSet.diagnostics[0].code,
        )

        val unpaired = parseReader("a=x\n".toByteArray(Charsets.UTF_8), SourceEncoding.Utf8)
        val exact = JavaString.fromCodeUnits(charArrayOf(0xd800.toChar()))
        val unpairedCommit = unpaired.commit(
            EditTransactionBuilder.new(unpaired)
                .semanticValue(unpaired.properties()[0].nodeRef(), exact)
                .build(),
        )
        assertEquals("a=\\uD800\n", unpairedCommit.document.render().toString(Charsets.UTF_8))
        assertEquals(exact, unpairedCommit.document.properties()[0].value())
    }

    /** RFC 0010 §13: removal owns all continuation lines but not adjacent
     * comments; rename replaces the complete continued key ownership. */
    @Test
    fun removalAndRenameOwnership() {
        val removed = parseReader(
            "# before\nkey=first\\\n  second\n# after\nnext=v\n".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val removal = removed.commit(
            EditTransactionBuilder.new(removed)
                .removeProperty(removed.properties()[0].nodeRef())
                .build(),
        )
        assertEquals(
            "# before\n# after\nnext=v\n",
            removal.document.render().toString(Charsets.UTF_8),
        )
        assertEquals(2, removal.document.comments().size)
        assertEquals(1, removal.document.properties().size)

        val renamed = parseReader(
            "old\\\n key=value\n".toByteArray(Charsets.UTF_8),
            SourceEncoding.Utf8,
        )
        val rename = renamed.commit(
            EditTransactionBuilder.new(renamed)
                .renameProperty(renamed.properties()[0].nodeRef(), JavaString.fromUnicode("new key"))
                .build(),
        )
        assertEquals("new\\ key=value\n", rename.document.render().toString(Charsets.UTF_8))
        assertEquals("new key", rename.document.properties()[0].key().toUnicode())
    }

    /** Vector case registry.frozen-five-operation-surface
     * (java-properties-v1.json:146-150): both profiles publish the same five
     * Supported operations in sorted id order. */
    @Test
    fun registryFrozenFiveOperationSurface() {
        for (profile in listOf(PropertiesProfile.ReaderV1, PropertiesProfile.Latin1V1)) {
            val operations = formatOperationRegistry(profile)
            assertEquals(
                listOf(
                    "java-properties.edit.insert-property@1",
                    "java-properties.edit.remove-property@1",
                    "java-properties.edit.rename-property@1",
                    "java-properties.edit.replace-literal-value@1",
                    "java-properties.edit.replace-semantic-value@1",
                ),
                operations.map { it.id.toString() },
            )
            assertTrue(operations.all { it.support.name == "Supported" })
        }
    }
}
