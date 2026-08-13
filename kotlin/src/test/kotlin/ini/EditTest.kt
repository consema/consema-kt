// Structural edit tests: the frozen eight operations, dry-run/commit
// equivalence, patch replay, untouched proof, and the conflict matrix.
//
// Authority: RFC 0009 §12 (https://github.com/consema/consema/blob/main/docs/rfcs/0009-ini-family-profiles-v1.md:437-
// 472), the vector cases edit.all-eight-operations and
// edit.dry-run-patch-proof-and-atomic-failure (ini-v1.json:89-105), and
// consema-rs/consema-ini/src/edit.rs (the byte-arbitration authority).

package ini

import consema.document.AssociationPlacement
import consema.document.EditPlanSourceId
import consema.document.FormationStatus
import consema.ini.EditFailure
import consema.ini.EditFailureException
import consema.ini.EditTransactionBuilder
import consema.ini.IniProfile
import consema.ini.RepresentationPolicy
import consema.ini.commit
import consema.ini.dryRun
import consema.ini.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditTest {

    /** Vector case edit.all-eight-operations (ini-v1.json:89-100): each of
     * the eight operations produces exactly its golden output and exactly
     * one source edit. */
    @Test
    fun allEightOperations() {
        val source = "[one]\na=1\n[two]\nb=2\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.PortableV1)

        val semantic = EditTransactionBuilder.new(document)
            .semanticValue(
                document.entries()[0].nodeRef,
                "9",
                RepresentationPolicy.CanonicalForProfile,
            )
            .build()
        assertEquals(
            "[one]\na=9\n[two]\nb=2\n",
            document.commit(semantic).document.render().toString(Charsets.UTF_8),
        )

        val literal = EditTransactionBuilder.new(document)
            .literalValue(document.entries()[0].nodeRef, "8".toByteArray(Charsets.UTF_8))
            .build()
        assertEquals(
            "[one]\na=8\n[two]\nb=2\n",
            document.commit(literal).document.render().toString(Charsets.UTF_8),
        )

        val insertSection = EditTransactionBuilder.new(document)
            .insertSection(document.nodeRef(), "three", AssociationPlacement.End)
            .build()
        assertEquals(
            "[one]\na=1\n[two]\nb=2\n[three]\n",
            document.commit(insertSection).document.render().toString(Charsets.UTF_8),
        )

        val removeSection = EditTransactionBuilder.new(document)
            .removeSection(document.sections()[0].nodeRef)
            .build()
        assertEquals(
            "[two]\nb=2\n",
            document.commit(removeSection).document.render().toString(Charsets.UTF_8),
        )

        val renameSection = EditTransactionBuilder.new(document)
            .renameSection(document.sections()[0].nodeRef, "renamed")
            .build()
        assertEquals(
            "[renamed]\na=1\n[two]\nb=2\n",
            document.commit(renameSection).document.render().toString(Charsets.UTF_8),
        )

        val insertEntry = EditTransactionBuilder.new(document)
            .insertEntry(document.sections()[0].nodeRef, "c", "3", AssociationPlacement.End)
            .build()
        assertEquals(
            "[one]\na=1\nc=3\n[two]\nb=2\n",
            document.commit(insertEntry).document.render().toString(Charsets.UTF_8),
        )

        val removeEntry = EditTransactionBuilder.new(document)
            .removeEntry(document.entries()[0].nodeRef)
            .build()
        assertEquals(
            "[one]\n[two]\nb=2\n",
            document.commit(removeEntry).document.render().toString(Charsets.UTF_8),
        )

        val renameEntry = EditTransactionBuilder.new(document)
            .renameEntry(document.entries()[0].nodeRef, "renamed")
            .build()
        assertEquals(
            "[one]\nrenamed=1\n[two]\nb=2\n",
            document.commit(renameEntry).document.render().toString(Charsets.UTF_8),
        )
    }

    /** Vector case edit.dry-run-patch-proof-and-atomic-failure (ini-v1.json:
     * 102-105): dry-run and commit produce the same patch, the patch
     * replays onto the base snapshot, the untouched proof verifies, and a
     * transaction bound to another snapshot fails with
     * core.edit.wrong-snapshot@1 while the base stays unchanged. */
    @Test
    fun dryRunPatchProofAndAtomicFailure() {
        val source = "; before\n[s]\nk=old\n; after\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.PortableV1)
        val transaction = EditTransactionBuilder.new(document)
            .semanticValue(
                document.entries()[0].nodeRef,
                "new value",
                RepresentationPolicy.CanonicalForProfile,
            )
            .build()

        val sourceId = EditPlanSourceId.new("memory:ini-conformance")
        val plan = document.dryRun(transaction, sourceId)
        val commit = document.commit(transaction)
        assertEquals(
            "; before\n[s]\nk=new value\n; after\n",
            commit.document.render().toString(Charsets.UTF_8),
        )
        assertEquals(plan.sourcePatch, commit.sourcePatch)
        assertEquals(plan.targetDigest, commit.document.source().digest)

        val replay = commit.sourcePatch!!.apply(document.source().v1Snapshot!!)
        assertTrue(replay.bytes().contentEquals(commit.document.render()))
        commit.untouchedProof!!.verify(
            document.source().v1Snapshot!!,
            commit.document.source().v1Snapshot!!,
            commit.sourcePatch!!.replacements(),
        )

        val wrong = parse("[x]\nk=other\n".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)
        val wrongTransaction = EditTransactionBuilder.new(wrong)
            .semanticValue(
                wrong.entries()[0].nodeRef,
                "changed",
                RepresentationPolicy.CanonicalForProfile,
            )
            .build()
        val failure = assertFailsWith<EditFailureException> {
            document.commit(wrongTransaction)
        }
        assertEquals("core.edit.wrong-snapshot@1", failure.failure.diagnosticCode())
        assertEquals(source, document.render().toString(Charsets.UTF_8))
    }

    /** RFC 0009 §12: removing a section owns its entries atomically but
     * never moves or deletes unowned comments. */
    @Test
    fun sectionRemovalOwnsEntriesButNotComments() {
        val document = parse(
            "[one]\na=1\n; independent\n[two]\nb=2\n".toByteArray(Charsets.UTF_8),
            IniProfile.PortableV1,
        )
        val transaction = EditTransactionBuilder.new(document)
            .removeSection(document.sections()[0].nodeRef)
            .build()
        val commit = document.commit(transaction)
        assertEquals(
            "; independent\n[two]\nb=2\n",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** RFC 0009 §12: Python continuation lines are owned by their entry;
     * removing the entry removes them but keeps a following comment. */
    @Test
    fun pythonContinuationOwnership() {
        val document = parse(
            "[S]\nmulti=first\n  second\n\n  fourth\n# keep\nnext=value\n".toByteArray(Charsets.UTF_8),
            IniProfile.PythonConfigParserV1,
        )
        val transaction = EditTransactionBuilder.new(document)
            .removeEntry(document.entries()[0].nodeRef)
            .build()
        val commit = document.commit(transaction)
        assertEquals("[S]\n# keep\nnext=value\n", commit.document.render().toString(Charsets.UTF_8))
    }

    /** RFC 0009 §12: semantic replacement preserves a compatible
     * representation; PreserveElseCanonical reports the canonical fallback
     * with ini.edit.canonical-fallback@1. */
    @Test
    fun windowsPreservesQuotesAndFallsBack() {
        val document = parse(
            "[S]\r\na='old'\r\nb=plain\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )
        val transaction = EditTransactionBuilder.new(document)
            .semanticValue(
                document.entries()[0].nodeRef,
                " new ",
                RepresentationPolicy.PreserveCompatible,
            )
            .semanticValue(
                document.entries()[1].nodeRef,
                " spaced ",
                RepresentationPolicy.PreserveElseCanonical,
            )
            .build()
        val commit = document.commit(transaction)
        assertEquals(
            "[S]\r\na=' new '\r\nb=\" spaced \"\r\n",
            commit.document.render().toString(Charsets.UTF_8),
        )
        assertEquals("ini.edit.canonical-fallback@1", commit.diagnostics[0].code)
    }

    /** RFC 0009 §12: Python multiline values preserve their per-line
     * indentation under PreserveCompatible and canonicalize shape changes
     * under PreserveElseCanonical. */
    @Test
    fun pythonMultilinePreserveAndCanonical() {
        val source = "[S]\nkey : first  \n\tsecond\t\n\n\tthird\nnext=x\n"
        val document = parse(source.toByteArray(Charsets.UTF_8), IniProfile.PythonConfigParserV1)

        val preserve = EditTransactionBuilder.new(document)
            .semanticValue(
                document.entries()[0].nodeRef,
                "one\ntwo\n\nthree",
                RepresentationPolicy.PreserveCompatible,
            )
            .build()
        val preserved = document.commit(preserve)
        assertEquals(
            "[S]\nkey : one  \n\ttwo\t\n\n\tthree\nnext=x\n",
            preserved.document.render().toString(Charsets.UTF_8),
        )
        assertEquals("one\ntwo\n\nthree", preserved.document.entries()[0].value)

        val fallback = EditTransactionBuilder.new(document)
            .semanticValue(
                document.entries()[0].nodeRef,
                "single",
                RepresentationPolicy.PreserveElseCanonical,
            )
            .build()
        val committed = document.commit(fallback)
        assertEquals("single", committed.document.entries()[0].value)
        assertEquals(1, committed.diagnostics.size)
    }

    /** RFC 0009 §12: insertions use each profile's canonical entry
     * representation. */
    @Test
    fun insertedValuesUseProfileCanonicalRepresentation() {
        val windows = parse(
            "[S]\r\na=1\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )
        val windowsTransaction = EditTransactionBuilder.new(windows)
            .insertEntry(windows.sections()[0].nodeRef, "quoted", " spaced ", AssociationPlacement.End)
            .build()
        assertEquals(
            "[S]\r\na=1\r\nquoted=\" spaced \"\r\n",
            windows.commit(windowsTransaction).document.render().toString(Charsets.UTF_8),
        )

        val python = parse("[S]\na=1\n".toByteArray(Charsets.UTF_8), IniProfile.PythonConfigParserV1)
        val pythonTransaction = EditTransactionBuilder.new(python)
            .insertEntry(python.sections()[0].nodeRef, "multi", "first\n\nthird", AssociationPlacement.End)
            .build()
        assertEquals(
            "[S]\na=1\nmulti = first\n\n    third\n",
            python.commit(pythonTransaction).document.render().toString(Charsets.UTF_8),
        )
    }

    /** RFC 0009 §12: inserting at EOF after a non-terminated record
     * introduces exactly one profile newline. */
    @Test
    fun eofInsertionIntroducesOneNewline() {
        val document = parse("[one]\na=1".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)
        val transaction = EditTransactionBuilder.new(document)
            .insertSection(document.nodeRef(), "two", AssociationPlacement.End)
            .build()
        assertEquals(
            "[one]\na=1\n[two]\n",
            document.commit(transaction).document.render().toString(Charsets.UTF_8),
        )
    }

    /** RFC 0009 §12: the multi-operation conflict matrix fails before any
     * patch exists. */
    @Test
    fun conflictMatrixFailsAtomically() {
        val document = parse(
            "[one]\na=1\n[two]\nb=2\n".toByteArray(Charsets.UTF_8),
            IniProfile.PortableV1,
        )
        val first = document.sections()[0].nodeRef
        val firstEntry = document.entries()[0].nodeRef

        val ancestor = EditTransactionBuilder.new(document)
            .removeSection(first)
            .semanticValue(firstEntry, "new", RepresentationPolicy.CanonicalForProfile)
            .build()
        assertEquals(
            EditFailure.AncestorDescendantConflict,
            assertFailsWith<EditFailureException> { document.commit(ancestor) }.failure,
        )

        val removedAnchor = EditTransactionBuilder.new(document)
            .removeSection(first)
            .insertSection(document.nodeRef(), "three", AssociationPlacement.After(first))
            .build()
        assertEquals(
            EditFailure.PlacementAnchorRemoved,
            assertFailsWith<EditFailureException> { document.commit(removedAnchor) }.failure,
        )

        val invalidName = EditTransactionBuilder.new(document)
            .renameSection(first, "bad name")
            .build()
        assertEquals(
            EditFailure.InvalidName,
            assertFailsWith<EditFailureException> { document.commit(invalidName) }.failure,
        )

        val nameCollision = EditTransactionBuilder.new(document)
            .renameSection(first, "two")
            .build()
        assertEquals(
            EditFailure.NameCollision,
            assertFailsWith<EditFailureException> { document.commit(nameCollision) }.failure,
        )

        val samePosition = EditTransactionBuilder.new(document)
            .insertSection(document.nodeRef(), "three", AssociationPlacement.End)
            .insertSection(document.nodeRef(), "four", AssociationPlacement.End)
            .build()
        assertEquals(
            EditFailure.OverlappingOwnership,
            assertFailsWith<EditFailureException> { document.commit(samePosition) }.failure,
        )

        val duplicateTarget = EditTransactionBuilder.new(document)
            .semanticValue(firstEntry, "one", RepresentationPolicy.CanonicalForProfile)
            .literalValue(firstEntry, "two".toByteArray(Charsets.UTF_8))
            .build()
        assertEquals(
            EditFailure.DuplicateTarget,
            assertFailsWith<EditFailureException> { document.commit(duplicateTarget) }.failure,
        )
    }

    /** RFC 0009 §12: Python key collisions and invalid keys are validated
     * before any patch exists; a cross-section placement anchor is
     * invalid. */
    @Test
    fun pythonEntryValidation() {
        val document = parse(
            "[S]\nKey=1\nother=2\n[T]\nx=3\n".toByteArray(Charsets.UTF_8),
            IniProfile.PythonConfigParserV1,
        )
        val section = document.sections()[0].nodeRef

        val collision = EditTransactionBuilder.new(document)
            .renameEntry(document.entries()[1].nodeRef, "KEY")
            .build()
        assertEquals(
            EditFailure.KeyCollision,
            assertFailsWith<EditFailureException> { document.commit(collision) }.failure,
        )
        assertEquals(
            "ini.edit.case-collision@1",
            assertFailsWith<EditFailureException> { document.commit(collision) }.failure.diagnosticCode(),
        )

        val invalid = EditTransactionBuilder.new(document)
            .insertEntry(section, "bad:key", "v", AssociationPlacement.End)
            .build()
        assertEquals(
            EditFailure.InvalidKey,
            assertFailsWith<EditFailureException> { document.commit(invalid) }.failure,
        )

        val crossSection = EditTransactionBuilder.new(document)
            .insertEntry(
                section,
                "new",
                "v",
                AssociationPlacement.Before(document.entries()[2].nodeRef),
            )
            .build()
        assertEquals(
            EditFailure.InvalidPlacement,
            assertFailsWith<EditFailureException> { document.commit(crossSection) }.failure,
        )

        val duplicate = EditTransactionBuilder.new(document)
            .insertEntry(section, "Key", "v", AssociationPlacement.End)
            .build()
        assertEquals(
            EditFailure.DuplicateKey,
            assertFailsWith<EditFailureException> { document.commit(duplicate) }.failure,
        )
    }

    /** RFC 0009 §12: a literal replacement must form exactly one value at
     * the target; a replacement that introduces new records is
     * InvalidLiteral. */
    @Test
    fun literalMustFormExactlyOneValue() {
        val document = parse("[s]\nk=old\n".toByteArray(Charsets.UTF_8), IniProfile.PortableV1)
        val transaction = EditTransactionBuilder.new(document)
            .literalValue(document.entries()[0].nodeRef, "x\n[y]\nq=z".toByteArray(Charsets.UTF_8))
            .build()
        assertEquals(
            EditFailure.InvalidLiteral,
            assertFailsWith<EditFailureException> { document.commit(transaction) }.failure,
        )
    }

    /** RFC 0009 §12: Windows entry renames keep ordered case-equivalent
     * occurrences as an ambiguity group in the committed document. */
    @Test
    fun windowsEntryRenameKeepsOrderedCaseEquivalents() {
        val document = parse(
            "[S]\r\nKey=1\r\nother=2\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )
        val transaction = EditTransactionBuilder.new(document)
            .renameEntry(document.entries()[1].nodeRef, "KEY")
            .build()
        val commit = document.commit(transaction)
        assertEquals("key", commit.document.entries()[0].comparisonKey)
        assertEquals("key", commit.document.entries()[1].comparisonKey)
        assertEquals(commit.document.entries()[0].duplicateGroup, commit.document.entries()[1].duplicateGroup)
    }

    /** RFC 0009 §12: PreserveCompatible cannot invent a representation; an
     * unquoted Windows value needing quotes fails explicitly. */
    @Test
    fun preserveCompatibleFailsWithoutRepresentation() {
        val document = parse(
            "[S]\r\nk=plain\r\n".toByteArray(Charsets.UTF_8),
            IniProfile.WindowsV1,
        )
        val transaction = EditTransactionBuilder.new(document)
            .semanticValue(document.entries()[0].nodeRef, " spaced ", RepresentationPolicy.PreserveCompatible)
            .build()
        assertEquals(
            EditFailure.RepresentationIncompatible,
            assertFailsWith<EditFailureException> { document.commit(transaction) }.failure,
        )
    }

    /** The edit operation ids of the registry (ini-v1.json
     * registry.frozen-eight-operation-surface). */
    @Test
    fun operationRegistryPublishesEightFrozenIds() {
        for (profile in IniProfile.entries) {
            val operations = consema.ini.formatOperationRegistry(profile)
            assertEquals(8, operations.size)
            assertEquals(
                listOf(
                    "ini.edit.insert-entry@1",
                    "ini.edit.insert-section@1",
                    "ini.edit.remove-entry@1",
                    "ini.edit.remove-section@1",
                    "ini.edit.rename-entry@1",
                    "ini.edit.rename-section@1",
                    "ini.edit.replace-literal-value@1",
                    "ini.edit.replace-semantic-value@1",
                ),
                operations.map { it.id.toString() }.sorted(),
            )
            assertEquals(6, operations.count { it.support == consema.ini.OperationSupport.Supported })
        }
    }

    /** RFC 0009 §12: a successful edit keeps the base snapshot's selected
     * encoding facts. */
    @Test
    fun utf16EncodingFactsSurviveTheEdit() {
        val text = "[S]\r\nk=old\r\n"
        val utf16 = ByteArray(2 + text.toByteArray(Charsets.UTF_16LE).size)
        utf16[0] = 0xff.toByte()
        utf16[1] = 0xfe.toByte()
        System.arraycopy(text.toByteArray(Charsets.UTF_16LE), 0, utf16, 2, utf16.size - 2)
        val document = parse(utf16, IniProfile.WindowsV1)
        val transaction = EditTransactionBuilder.new(document)
            .semanticValue(document.entries()[0].nodeRef, "wide", RepresentationPolicy.CanonicalForProfile)
            .build()
        val commit = document.commit(transaction)
        assertEquals("wide", commit.document.entries()[0].value)
        assertEquals(
            document.source().encodingFacts,
            commit.document.source().encodingFacts,
        )
        assertNotNull(commit.sourcePatch)
    }
}
