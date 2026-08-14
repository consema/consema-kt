// Structural edit tests of the plist family: the six snapshot-bound
// operations, the sequential fold model, conflict validation, and the
// conformance vectors plist.edit.*.
//
// Data authority:
//   - conformance/vectors/plist-v1.json cases plist.edit.xml-six-operations
//     (plist-v1.json:257-325), plist.edit.binary-structural (plist-v1.json:
//     327-361), plist.edit.conflicts (plist-v1.json:363-407) pin the
//     outcomes.
//   - https://github.com/consema/consema-rs/blob/main/consema-plist/src/edit.rs is the byte authority: the fold rule
//     (edit.rs:668-728 record_edit) folds a later operation whose span lies
//     inside an earlier replacement and merges containing base spans at
//     commit (edit.rs:1947-1979); two zero-width insertions at one base
//     position conflict (edit.rs:710-719); a boundary insertion at a
//     replaced span's start is its own record (edit.rs:687-690); a replaced
//     container loses its slots so later operations through it fail
//     WrongRole (edit.rs:749-768); a binary key rename binds a fresh key
//     object so shared keys stay byte-exact (edit.rs:1700-1721).
//   - RFC 0013 §11 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md:683-715).
//
// This file runs in the verified toolchain gate (kotlin-gates gradlew
// test / the scripts/kotlin-verify-*.ps1 direct path): the toolchain is
// verified and this file is executed.

package plist

import consema.document.EditPlanSourceId
import consema.document.FormationStatus
import consema.plist.DictPlacement
import consema.plist.Document
import consema.plist.EditFailure
import consema.plist.EditFailureException
import consema.plist.EditOperation
import consema.plist.EditPath
import consema.plist.EditPathStep
import consema.plist.EditTransactionBuilder
import consema.plist.EditValue
import consema.plist.PlistKey
import consema.plist.PlistProfile
import consema.plist.PlistUid
import consema.plist.commit
import consema.plist.dryRun
import consema.plist.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditTest {

    private fun xml(source: String): Document =
        parse(source.toByteArray(Charsets.UTF_8), PlistProfile.XmlV1)

    private fun binary(hex: String): Document = parse(hexToBytes(hex), PlistProfile.BinaryV1)

    private fun key(name: String): PlistKey = PlistKey.fromUnicode(name)

    private fun dictKey(name: String, occurrence: Int = 0) = EditPathStep.DictKey(key(name), occurrence)

    private fun pathOf(vararg steps: EditPathStep): EditPath = EditPath.new(steps.toList())

    /** Vector case plist.edit.xml-six-operations (plist-v1.json:257-325):
     * all six operations in one sequential transaction; the later
     * remove-dict-entry folds the earlier set-value on the same entry span,
     * the rename targets the key an earlier insertion created (virtual-slot
     * rewrite), and the array removal indexes the state after the earlier
     * insertion. */
    @Test
    fun xmlSixOperationsVector() {
        val source = "<plist version=\"1.0\"><dict><key>a</key><dict><key>b</key><string>old</string></dict><key>arr</key><array><integer>1</integer><integer>2</integer></array></dict></plist>"
        val document = xml(source)
        assertEquals(FormationStatus.Complete, document.formationStatus())

        val builder = EditTransactionBuilder.new(document)
        builder.setValue(pathOf(dictKey("a"), dictKey("b")), EditValue.String(key("new").asString()))
        builder.insertDictEntry(pathOf(dictKey("a")), key("c"), EditValue.Integer(3), DictPlacement.End)
        builder.insertArrayElement(pathOf(dictKey("arr")), 0, EditValue.String(key("z").asString()))
        builder.removeArrayElement(pathOf(dictKey("arr")), 2)
        builder.renameDictKey(pathOf(dictKey("a")), key("c"), 0, key("c2"))
        builder.removeDictEntry(pathOf(dictKey("a")), key("b"))
        val commit = document.commit(builder.build())

        // The golden render: b's entry is gone (its set-value folded into
        // the removal), c2 = 3 sits at the inner dict's end, and the array
        // is [z, 1] (the removal targeted the state after the insertion).
        assertEquals(
            "<plist version=\"1.0\"><dict><key>a</key><dict><key>c2</key><integer>3</integer></dict><key>arr</key><array><string>z</string><integer>1</integer></array></dict></plist>",
            commit.document.render().toString(Charsets.UTF_8),
        )
        assertEquals("dict", commit.document.root().kind()?.kindName())
        val inner = commit.document.root().dictEntries()!![0].value()
        assertEquals(listOf("c2"), inner.dictEntries()!!.map { it.key()!!.toUnicode()!! })
        assertEquals(listOf(3L), inner.dictEntries()!!.map { it.value().asInteger()!! })
        assertEquals(listOf("z", "1"), commit.document.root().dictEntries()!![1].value()
            .arrayElements()!!.map { it.value().asString()?.toUnicode() ?: it.value().asInteger()!!.toString() })

        // Reparse closure, untouched-byte proof, and patch replay
        // (plist-v1.json expected.reparse_closure / untouched_byte_proof /
        // patch_replays).
        val reparsed = parse(commit.document.render(), PlistProfile.XmlV1)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus())
        commit.untouchedProof.verify(
            document.source(),
            commit.document.source(),
            commit.sourcePatch.replacements(),
        )
        val reapplied = commit.sourcePatch.apply(document.source())
        assertTrue(reapplied.bytes().contentEquals(commit.document.render()))

        // The dry-run plan publishes the identical replacement set and
        // digests (RFC 0004 §14).
        val plan = document.dryRun(builder.build(), EditPlanSourceId.new("memory:plist-conformance"))
        assertEquals(commit.sourcePatch.replacements(), plan.replacements())
        assertEquals(commit.sourcePatch.targetDigest, plan.targetDigest)
        assertEquals(commit.sourcePatch.baseDigest, plan.baseDigest)
    }

    /** Vector case plist.edit.binary-structural (plist-v1.json:327-361):
     * set-value rewrites the target object, insert-array-element appends a
     * fresh object and rebinds the root reference block, and the offset
     * table and trailer are regenerated (RFC 0013 §11). */
    @Test
    fun binaryStructuralVector() {
        val document = binary("62706c6973743030a2010210015162080b0d000000000000010100000000000000030000000000000000000000000000000f")
        assertEquals(FormationStatus.Complete, document.formationStatus())

        val builder = EditTransactionBuilder.new(document)
        builder.setValue(pathOf(EditPathStep.ArrayIndex(1)), EditValue.Integer(42))
        builder.insertArrayElement(EditPath.root(), 0, EditValue.BooleanV(true))
        val commit = document.commit(builder.build())

        // The golden object table: root array [3, 1, 2] (boolean true, 1,
        // 42) at 8..12, object 1 (10 01) at 12..14 untouched byte-exact,
        // object 2 (10 2a) at 14..16, object 3 (09) at 16..17; offsets
        // [8, 12, 14, 16], offset table at 17, trailer with numObjects 4,
        // topObject 0, offsetTableOffset 0x11.
        assertEquals(
            "62706c6973743030a30301021001102a09080c0e100000000000000101000000000000000400000000000000000000000000000011",
            commit.document.render().joinToString("") { "%02x".format(it) },
        )
        val root = commit.document.root()
        assertEquals("array", root.kind()?.kindName())
        assertEquals(listOf(true, 1L, 42L), root.arrayElements()!!.map { it.value().asBoolean() ?: it.value().asInteger()!! })

        val reparsed = parse(commit.document.render(), PlistProfile.BinaryV1)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus())
        commit.untouchedProof.verify(
            document.source(),
            commit.document.source(),
            commit.sourcePatch.replacements(),
        )
        val reapplied = commit.sourcePatch.apply(document.source())
        assertTrue(reapplied.bytes().contentEquals(commit.document.render()))
    }

    /** Vector case plist.edit.conflicts (plist-v1.json:363-407): a UID
     * value on XML fails atomically with plist.edit.uid-in-xml@1; a
     * Recovered base is IncompleteTarget; a transaction bound to another
     * snapshot is WrongSnapshot. */
    @Test
    fun conflictsVector() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><string>x</string></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.setValue(pathOf(dictKey("a")), EditValue.Uid(PlistUid(5)))
        val uidError = assertFailsWith<EditFailureException> {
            document.commit(builder.build())
        }
        assertEquals(EditFailure.UidInXml, uidError.failure)
        assertEquals("plist.edit.uid-in-xml@1", uidError.failure.code)

        // A Recovered base cannot be edited (vector sample 2: the empty
        // `<key>a</key>` dict with no value recovers).
        val recovered = xml("<plist version=\"1.0\"><dict><key>a</key></dict></plist>")
        assertTrue(recovered.formationStatus() == FormationStatus.Recovered)
        val recoveredError = assertFailsWith<EditFailureException> {
            recovered.commit(builder.build())
        }
        assertEquals(EditFailure.IncompleteTarget, recoveredError.failure)

        // A transaction built on another snapshot is WrongSnapshot.
        val other = xml("<plist version=\"1.0\"><string>x</string></plist>")
        val otherError = assertFailsWith<EditFailureException> {
            other.commit(builder.build())
        }
        assertEquals(EditFailure.WrongSnapshot, otherError.failure)
    }

    /** set-value replaces the value element of one path (RFC 0013 §11). */
    @Test
    fun setValueReplacesElement() {
        val document = xml("<plist version=\"1.0\"><dict><key>k</key><string>old</string></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.setValue(pathOf(dictKey("k")), EditValue.Integer(7))
        val commit = document.commit(builder.build())
        assertEquals(
            "<plist version=\"1.0\"><dict><key>k</key><integer>7</integer></dict></plist>",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** insert-dict-entry honors End, Before, and After placement (RFC 0013
     * §11; edit.rs:132-143). */
    @Test
    fun insertDictEntryPlacements() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><integer>1</integer><key>b</key><integer>2</integer></dict></plist>")

        val end = EditTransactionBuilder.new(document)
        end.insertDictEntry(EditPath.root(), key("c"), EditValue.Integer(3), DictPlacement.End)
        assertEquals(
            listOf("a", "b", "c"),
            document.commit(end.build()).document.root().dictEntries()!!.map { it.key()!!.toUnicode()!! },
        )

        val before = EditTransactionBuilder.new(document)
        before.insertDictEntry(EditPath.root(), key("c"), EditValue.Integer(3), DictPlacement.Before(1))
        assertEquals(
            listOf("a", "c", "b"),
            document.commit(before.build()).document.root().dictEntries()!!.map { it.key()!!.toUnicode()!! },
        )

        val after = EditTransactionBuilder.new(document)
        after.insertDictEntry(EditPath.root(), key("c"), EditValue.Integer(3), DictPlacement.After(0))
        assertEquals(
            listOf("a", "c", "b"),
            document.commit(after.build()).document.root().dictEntries()!!.map { it.key()!!.toUnicode()!! },
        )
    }

    /** remove-dict-entry removes by key content and occurrence among
     * duplicate keys. */
    @Test
    fun removeDictEntryByOccurrence() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><integer>1</integer><key>a</key><integer>2</integer><key>b</key><integer>3</integer></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.removeDictEntry(EditPath.root(), key("a"), occurrence = 1)
        val commit = document.commit(builder.build())
        assertEquals(
            listOf("a", "b"),
            commit.document.root().dictEntries()!!.map { it.key()!!.toUnicode()!! },
        )
        assertEquals(listOf(1L, 3L), commit.document.root().dictEntries()!!.map { it.value().asInteger()!! })
    }

    /** rename-dict-key rewrites the key element and preserves the
     * association value. */
    @Test
    fun renameDictKeyBase() {
        val document = xml("<plist version=\"1.0\"><dict><key>old</key><string>x</string></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.renameDictKey(EditPath.root(), key("old"), 0, key("new"))
        val commit = document.commit(builder.build())
        assertEquals(
            "<plist version=\"1.0\"><dict><key>new</key><string>x</string></dict></plist>",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** Array indexing is sequential: the removal targets the state after
     * the earlier insertion (RFC 0013 §11; edit.rs:187-191). */
    @Test
    fun arrayInsertionShiftsRemovalIndex() {
        val document = xml("<plist version=\"1.0\"><array><integer>1</integer><integer>2</integer><integer>3</integer></array></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.insertArrayElement(EditPath.root(), 0, EditValue.String(key("x").asString()))
        builder.removeArrayElement(EditPath.root(), 2)
        val commit = document.commit(builder.build())
        assertEquals(
            listOf("x", "1", "3"),
            commit.document.root().arrayElements()!!.map { it.value().asString()?.toUnicode() ?: it.value().asInteger()!!.toString() },
        )
    }

    /** The fold rule, same-span case: two set-values on one target — the
     * later replacement folds the earlier one (edit.rs:691-706). */
    @Test
    fun foldSameTargetSetTwice() {
        val document = xml("<plist version=\"1.0\"><string>a</string></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.setValue(EditPath.root(), EditValue.String(key("first").asString()))
        builder.setValue(EditPath.root(), EditValue.String(key("second").asString()))
        val commit = document.commit(builder.build())
        assertEquals(
            "<plist version=\"1.0\"><string>second</string></plist>",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** The fold rule, containing case: a later set-value on a container
     * folds an earlier insertion inside it (the inserted bytes are
     * subsumed by the replacement; edit.rs:1947-1979 run merge). */
    @Test
    fun foldInsertThenSetValueOnContainer() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><integer>1</integer></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.insertDictEntry(EditPath.root(), key("b"), EditValue.Integer(2), DictPlacement.End)
        builder.setValue(EditPath.root(), EditValue.String(key("scalar").asString()))
        val commit = document.commit(builder.build())
        assertEquals(
            "<plist version=\"1.0\"><string>scalar</string></plist>",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** A later set-value on a virtual slot rewrites the insertion fragment
     * in place (the fold of edit.rs:691-706); no separate edit exists. */
    @Test
    fun foldSetValueOnInsertedEntry() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><integer>1</integer></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.insertDictEntry(EditPath.root(), key("b"), EditValue.Integer(2), DictPlacement.End)
        builder.setValue(pathOf(dictKey("b")), EditValue.String(key("v").asString()))
        val commit = document.commit(builder.build())
        assertEquals(
            "<plist version=\"1.0\"><dict><key>a</key><integer>1</integer><key>b</key><string>v</string></dict></plist>",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** A later rename of an inserted key rewrites the insertion fragment's
     * key element (the vector's rename of the op-2 insertion). */
    @Test
    fun foldRenameInsertedKey() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><integer>1</integer></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.insertDictEntry(EditPath.root(), key("b"), EditValue.Integer(2), DictPlacement.End)
        builder.renameDictKey(EditPath.root(), key("b"), 0, key("b2"))
        val commit = document.commit(builder.build())
        assertEquals(
            "<plist version=\"1.0\"><dict><key>a</key><integer>1</integer><key>b2</key><integer>2</integer></dict></plist>",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** A later removal of an inserted entry folds the insertion to empty:
     * the final document never contained the entry (edit.rs:691-706
     * merge). */
    @Test
    fun foldRemoveInsertedEntry() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><integer>1</integer></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.insertDictEntry(EditPath.root(), key("b"), EditValue.Integer(2), DictPlacement.End)
        builder.removeDictEntry(EditPath.root(), key("b"))
        val commit = document.commit(builder.build())
        assertEquals(
            "<plist version=\"1.0\"><dict><key>a</key><integer>1</integer></dict></plist>",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** A removal folded by a later containing removal of the same entry
     * span (the six-operations vector pattern in isolation). */
    @Test
    fun foldSetValueThenRemoveSameEntry() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><string>old</string></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.setValue(pathOf(dictKey("a")), EditValue.String(key("new").asString()))
        builder.removeDictEntry(EditPath.root(), key("a"))
        val commit = document.commit(builder.build())
        assertEquals(
            "<plist version=\"1.0\"><dict></dict></plist>",
            commit.document.render().toString(Charsets.UTF_8),
        )
    }

    /** A boundary insertion at a replaced span's start is its own record:
     * removing an entry and inserting at the same base position are both
     * recorded, and the inserted fragment survives (edit.rs:687-690). */
    @Test
    fun boundaryRemoveThenInsertAtSamePosition() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><integer>1</integer><key>b</key><integer>2</integer></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.removeDictEntry(EditPath.root(), key("b"))
        builder.insertDictEntry(EditPath.root(), key("c"), EditValue.Integer(3), DictPlacement.After(0))
        val commit = document.commit(builder.build())
        assertEquals(
            listOf("a", "c"),
            commit.document.root().dictEntries()!!.map { it.key()!!.toUnicode()!! },
        )
        assertEquals(listOf(1L, 3L), commit.document.root().dictEntries()!!.map { it.value().asInteger()!! })
    }

    /** Two zero-width insertions at one base position conflict
     * (edit.rs:710-719 ConflictingEdits). */
    @Test
    fun twoInsertionsAtSamePositionConflict() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><integer>1</integer></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.insertDictEntry(EditPath.root(), key("b"), EditValue.Integer(2), DictPlacement.End)
        builder.insertDictEntry(EditPath.root(), key("c"), EditValue.Integer(3), DictPlacement.End)
        val error = assertFailsWith<EditFailureException> {
            document.commit(builder.build())
        }
        assertEquals(EditFailure.ConflictingEdits, error.failure)
        assertEquals("core.edit.conflicting-edits@1", error.failure.code)
    }

    /** A replaced container loses its slots: a later operation resolving
     * through it fails WrongRole like the Rust sequential reparse
     * (edit.rs:749-768). */
    @Test
    fun setValueOnContainerThenChildOperationWrongRole() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><dict><key>b</key><integer>1</integer></dict></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.setValue(pathOf(dictKey("a")), EditValue.String(key("scalar").asString()))
        builder.setValue(pathOf(dictKey("a"), dictKey("b")), EditValue.Integer(9))
        val error = assertFailsWith<EditFailureException> {
            document.commit(builder.build())
        }
        assertEquals(EditFailure.WrongRole, error.failure)
        assertEquals("core.edit.wrong-role@1", error.failure.code)
    }

    /** set-value on a missing target is TargetNotFound. */
    @Test
    fun missingTargetRejected() {
        val document = xml("<plist version=\"1.0\"><dict><key>a</key><integer>1</integer></dict></plist>")
        val builder = EditTransactionBuilder.new(document)
        builder.setValue(pathOf(dictKey("missing")), EditValue.Integer(9))
        val error = assertFailsWith<EditFailureException> {
            document.commit(builder.build())
        }
        assertEquals(EditFailure.TargetNotFound, error.failure)
    }

    /** Binary edits: all six operations on one binary document (root dict
     * {a: array [1]}). */
    @Test
    fun binaryAllSixOperations() {
        val document = binary("62706c6973743030d101025161a1031001080b0d0f0000000000000101000000000000000400000000000000000000000000000011")
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val root = document.root()
        assertEquals("dict", root.kind()?.kindName())
        assertEquals(listOf("a"), root.dictEntries()!!.map { it.key()!!.toUnicode()!! })
        assertEquals(listOf(1L), root.dictEntries()!![0].value().arrayElements()!!.map { it.value().asInteger()!! })

        val builder = EditTransactionBuilder.new(document)
        builder.setValue(pathOf(dictKey("a"), EditPathStep.ArrayIndex(0)), EditValue.Integer(42))
        builder.insertDictEntry(EditPath.root(), key("b"), EditValue.String(key("v").asString()), DictPlacement.End)
        builder.insertArrayElement(pathOf(dictKey("a")), 0, EditValue.BooleanV(true))
        builder.renameDictKey(EditPath.root(), key("b"), 0, key("b2"))
        builder.removeArrayElement(pathOf(dictKey("a")), 1)
        builder.removeDictEntry(EditPath.root(), key("a"), occurrence = 0)
        builder.insertDictEntry(EditPath.root(), key("c"), EditValue.Integer(3), DictPlacement.End)
        val commit = document.commit(builder.build())

        val committedRoot = commit.document.root()
        assertEquals(listOf("b2", "c"), committedRoot.dictEntries()!!.map { it.key()!!.toUnicode()!! })
        assertEquals("v", committedRoot.dictEntries()!![0].value().asString()!!.toUnicode())
        assertEquals(3L, committedRoot.dictEntries()!![1].value().asInteger())

        val reparsed = parse(commit.document.render(), PlistProfile.BinaryV1)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus())
    }

    /** The binary trailer names the actual root object: an edit on a
     * document whose root is not object 0 keeps the root identity
     * (edit.rs:1561). */
    @Test
    fun binaryRootIndexNotZeroPreserved() {
        val document = binary("62706c6973743030517810020908a20203233ff80000000000005161516251635164d40607080900010405080a0c0d0e111a1c1e20220000000000000101000000000000000b000000000000000a000000000000002b")
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals("dict", document.root().kind()?.kindName())
        assertEquals(10, document.root().rawIndex())

        val builder = EditTransactionBuilder.new(document)
        builder.setValue(pathOf(dictKey("c")), EditValue.Integer(7))
        val commit = document.commit(builder.build())

        assertEquals(FormationStatus.Complete, commit.document.formationStatus())
        assertEquals("dict", commit.document.root().kind()?.kindName())
        assertEquals(10, commit.document.root().rawIndex())
        val entries = commit.document.root().dictEntries()!!
        assertEquals(listOf("a", "b", "c", "d"), entries.map { it.key()!!.toUnicode()!! })
        assertEquals(listOf(2L, 7L), entries.map { it.value().asInteger() }.filterNotNull())
        assertEquals(listOf("x"), entries[0].value().asString()!!.toUnicode().let { listOf(it) })
        val reparsed = parse(commit.document.render(), PlistProfile.BinaryV1)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus())
        assertEquals("dict", reparsed.root().kind()?.kindName())
    }

    /** A binary key rename binds a fresh key object: another dictionary
     * sharing the old key object keeps it byte-exact (edit.rs:1700-1721,
     * RFC 0013 §11). */
    @Test
    fun binaryRenameKeepsSharedKeyObjectByteExact() {
        // Root array [dict1, dict2] sharing key object 1 ("a"):
        // dict1 = {a: false}, dict2 = {a: true}.
        val document = binary("62706c6973743030a2040551610809d10102d10103080b0d0e0f120000000000000101000000000000000600000000000000000000000000000015")
        assertEquals(FormationStatus.Complete, document.formationStatus())
        assertEquals("a", document.root().arrayElements()!![0].value().dictEntries()!![0].key()!!.toUnicode())
        assertEquals("a", document.root().arrayElements()!![1].value().dictEntries()!![0].key()!!.toUnicode())

        val builder = EditTransactionBuilder.new(document)
        builder.renameDictKey(
            pathOf(EditPathStep.ArrayIndex(0)),
            key("a"),
            0,
            key("b"),
        )
        val commit = document.commit(builder.build())

        val elements = commit.document.root().arrayElements()!!
        assertEquals("b", elements[0].value().dictEntries()!![0].key()!!.toUnicode())
        assertEquals("a", elements[1].value().dictEntries()!![0].key()!!.toUnicode())
        // The shared key object's bytes are untouched in the output.
        val render = commit.document.render()
        assertEquals(listOf(0x51, 0x61), render.copyOfRange(11, 13).map { it.toInt() and 0xFF })
        val reparsed = parse(render, PlistProfile.BinaryV1)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus())
    }

    /** Binary structural edits preserve shared references: a new entry
     * never disturbs an object referenced from elsewhere, and the shared
     * value's identity is one native node with multiple owners (RFC 0013
     * §11). */
    @Test
    fun binaryInsertKeepsSharedValue() {
        // Root dict {a: [x, x]} — the array shares value object 3 ("x").
        val document = binary("62706c6973743030d101025161a203035178080b0d100000000000000101000000000000000400000000000000000000000000000012")
        assertEquals(FormationStatus.Complete, document.formationStatus())
        val array = document.root().dictEntries()!![0].value().arrayElements()!!
        assertTrue(array[0].value().isShared())

        val builder = EditTransactionBuilder.new(document)
        builder.insertDictEntry(EditPath.root(), key("b"), EditValue.Integer(5), DictPlacement.End)
        val commit = document.commit(builder.build())

        val entries = commit.document.root().dictEntries()!!
        assertEquals(listOf("a", "b"), entries.map { it.key()!!.toUnicode()!! })
        assertEquals(listOf("x", "x"), entries[0].value().arrayElements()!!.map { it.value().asString()!!.toUnicode() })
        assertEquals(5L, entries[1].value().asInteger())
        // The shared value stays one object (no duplication on insert).
        assertEquals(array[0].value().rawIndex(), entries[0].value().arrayElements()!![0].value().rawIndex())
        val reparsed = parse(commit.document.render(), PlistProfile.BinaryV1)
        assertEquals(FormationStatus.Complete, reparsed.formationStatus())
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }
}
