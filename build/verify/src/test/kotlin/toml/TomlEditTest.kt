// Golden transcriptions of the toml.edit.* and operations.v1.toml-* cases.
//
// Data authority: conformance/vectors/toml-v1.json (toml.edit.literal-
// minimal, toml.edit.reject-unrepresentable) and operations-v1.json
// (operations.v1.toml-root-insert, toml-inline-rename, toml-array-remove,
// toml-conflict-atomic, toml-dry-run-proof-patch, toml-structural-matrix,
// toml-conflict-matrix), plus the Rust crate edit tests
// (consema-toml/src/edit.rs:1653-2156). The L5 conformance runner executes
// the shared vectors directly; these tests are the L1 intent documents.

package toml

import consema.core.Kind
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvInteger
import consema.core.PvString
import consema.document.AssociationPlacement
import consema.document.EditPlanSourceId
import consema.document.ParseLimits
import consema.toml.EditFailureKind
import consema.toml.EditTransactionBuilder
import consema.toml.RepresentationPolicy
import consema.toml.TomlEditException
import consema.toml.TomlProfile
import consema.toml.commit
import consema.toml.dryRun
import consema.toml.parse
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TomlEditTest {

    private fun document(source: String) =
        parse(source.toByteArray(), TomlProfile.TOML_1_0_V1, ParseLimits.default)

    private fun rootItem(document: consema.toml.TomlDocument, name: String) =
        document.root().tableEntries()!!.first { it.name() == name }.itemNodeRef

    private fun rootEntry(document: consema.toml.TomlDocument, name: String) =
        document.root().tableEntries()!!.first { it.name() == name }

    /** toml-v1.json toml.edit.literal-minimal: an exact literal replacement
     * changes only the scalar span and preserves the trailing comment. */
    @Test
    fun literalMinimal() {
        val document = document("hex = 0x2A # keep\n")
        val commit = document.commit(
            EditTransactionBuilder.new(document)
                .literalScalar(rootItem(document, "hex"), "0x2B".toByteArray())
                .build(),
        )
        assertTrue(commit.document.render().contentEquals("hex = 0x2B # keep\n".toByteArray()))
        assertEquals(1, commit.sourcePatch.replacements().size)
        // The patch replays onto the base and reproduces the committed
        // bytes (RFC 0004 §16).
        val replayed = commit.sourcePatch.apply(document.source())
        assertTrue(replayed.bytes().contentEquals(commit.document.render()))
        commit.untouchedProof.verify(document.source(), commit.document.source(), commit.sourcePatch.replacements())
    }

    /** toml-v1.json toml.edit.reject-unrepresentable: a non-canonical NaN
     * payload fails as UnsupportedSemanticValue and the base stays
     * unchanged. */
    @Test
    fun rejectUnrepresentableNanPayload() {
        val document = document("float = 1.0\n")
        val failure = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .semanticScalar(
                        rootItem(document, "float"),
                        PvBinaryFloat64(0x7FF8000000000001L),
                        RepresentationPolicy.CanonicalForProfile,
                    )
                    .build(),
            )
        }
        assertEquals(EditFailureKind.UnsupportedSemanticValue(Kind.BinaryFloat64), failure.kind)
        assertEquals("core.edit.unsupported-value@1", failure.code)
        assertTrue(document.render().contentEquals("float = 1.0\n".toByteArray()))
    }

    /** operations-v1.json operations.v1.toml-root-insert: a root-level End
     * insertion is line-owned before the first table header. */
    @Test
    fun rootInsert() {
        val document = document("root = 1\n\n[service]\nport = 80\n")
        val commit = document.commit(
            EditTransactionBuilder.new(document)
                .insertEntry(
                    document.root().nodeRef,
                    "enabled",
                    PvBoolean(true),
                    AssociationPlacement.End,
                )
                .build(),
        )
        assertTrue(
            commit.document.render().contentEquals(
                "root = 1\n\n\"enabled\" = true\n[service]\nport = 80\n".toByteArray(),
            ),
        )
        val inserted = commit.document.root().tableEntries()!!.first { it.name() == "enabled" }
        assertEquals(true, inserted.item().asBoolean())
    }

    /** operations-v1.json operations.v1.toml-inline-rename: a rename
     * replaces only the direct key literal. */
    @Test
    fun inlineRename() {
        val document = document("point = { a = 1, b = 2 }\n")
        val point = rootEntry(document, "point").item()
        val beta = point.tableEntries()!![1]
        val commit = document.commit(
            EditTransactionBuilder.new(document)
                .renameEntry(beta.nodeRef, "beta")
                .build(),
        )
        assertTrue(commit.document.render().contentEquals("point = { a = 1, \"beta\" = 2 }\n".toByteArray()))
    }

    /** operations-v1.json operations.v1.toml-array-remove: removing one
     * element owns only the necessary comma and preserves comments. */
    @Test
    fun arrayRemove() {
        val document = document("items = [1, # keep\n 2, 3,]\n")
        val items = rootEntry(document, "items").item()
        val second = items.arrayElements()!![1]
        val commit = document.commit(
            EditTransactionBuilder.new(document)
                .removeArrayElement(second.nodeRef)
                .build(),
        )
        assertTrue(commit.document.render().contentEquals("items = [1, # keep\n  3,]\n".toByteArray()))
        commit.untouchedProof.verify(document.source(), commit.document.source(), commit.sourcePatch.replacements())
    }

    /** operations-v1.json operations.v1.toml-conflict-atomic: a duplicate
     * key fails atomically with the frozen code. */
    @Test
    fun conflictAtomicDuplicateKey() {
        val document = document("a = 1\nb = 2\n")
        val failure = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .insertEntry(
                        document.root().nodeRef,
                        "a",
                        PvBoolean(true),
                        AssociationPlacement.Start,
                    )
                    .build(),
            )
        }
        assertEquals(EditFailureKind.DuplicateKey, failure.kind)
        assertEquals("core.edit.duplicate-key@1", failure.code)
        assertTrue(document.render().contentEquals("a = 1\nb = 2\n".toByteArray()))
    }

    /** operations-v1.json operations.v1.toml-structural-matrix: the four
     * structural cases produce the exact pinned outputs. */
    @Test
    fun structuralMatrix() {
        // insert-standard-table
        val service = document("[service]\nport = 80\n")
        val serviceCommit = service.commit(
            EditTransactionBuilder.new(service)
                .insertEntry(
                    rootEntry(service, "service").itemNodeRef,
                    "host",
                    PvString("localhost"),
                    AssociationPlacement.End,
                )
                .build(),
        )
        assertTrue(
            serviceCommit.document.render().contentEquals(
                "[service]\nport = 80\n\"host\" = \"localhost\"".toByteArray(),
            ),
        )

        // insert-inline (before_ordinal 1)
        val inline = document("point = { a = 1, b = 2 }\n")
        val point = rootEntry(inline, "point").item()
        val inlineCommit = inline.commit(
            EditTransactionBuilder.new(inline)
                .insertEntry(
                    point.nodeRef,
                    "axis",
                    consema.core.PvArray(listOf(PvBoolean(true))),
                    AssociationPlacement.Before(point.tableEntries()!![1].nodeRef),
                )
                .build(),
        )
        assertTrue(
            inlineCommit.document.render().contentEquals(
                "point = { a = 1, \"axis\" = [true],b = 2 }\n".toByteArray(),
            ),
        )

        // remove-inline (target_ordinal 0)
        val removeInline = document("point = { a = 1, b = 2 }\n")
        val removePoint = rootEntry(removeInline, "point").item()
        val removeCommit = removeInline.commit(
            EditTransactionBuilder.new(removeInline)
                .removeEntry(removePoint.tableEntries()!![0].nodeRef)
                .build(),
        )
        assertTrue(removeCommit.document.render().contentEquals("point = {  b = 2 }\n".toByteArray()))

        // insert-array-start on an empty array
        val empty = document("items = [ ]\n")
        val emptyArray = rootEntry(empty, "items").item()
        val emptyCommit = empty.commit(
            EditTransactionBuilder.new(empty)
                .insertArrayElement(
                    emptyArray.nodeRef,
                    PvInteger(BigInteger.ONE),
                    AssociationPlacement.Start,
                )
                .build(),
        )
        assertTrue(emptyCommit.document.render().contentEquals("items = [1 ]\n".toByteArray()))
    }

    /** operations-v1.json operations.v1.toml-conflict-matrix: the four
     * conflict modes fail atomically with the frozen codes. */
    @Test
    fun conflictMatrix() {
        // duplicate-target
        val duplicateTarget = document("a = 1\n")
        val a = rootItem(duplicateTarget, "a")
        val duplicateFailure = assertFailsWith<TomlEditException> {
            duplicateTarget.commit(
                EditTransactionBuilder.new(duplicateTarget)
                    .literalScalar(a, "2".toByteArray())
                    .literalScalar(a, "3".toByteArray())
                    .build(),
            )
        }
        assertEquals(EditFailureKind.DuplicateTarget, duplicateFailure.kind)
        assertEquals("core.edit.conflicting-edits@1", duplicateFailure.code)

        // removed-anchor
        val removedAnchor = document("a = 1\nb = 2\n")
        val aEntry = rootEntry(removedAnchor, "a")
        val anchorFailure = assertFailsWith<TomlEditException> {
            removedAnchor.commit(
                EditTransactionBuilder.new(removedAnchor)
                    .removeEntry(aEntry.nodeRef)
                    .insertEntry(
                        removedAnchor.root().nodeRef,
                        "x",
                        PvBoolean(true),
                        AssociationPlacement.Before(aEntry.nodeRef),
                    )
                    .build(),
            )
        }
        assertEquals(EditFailureKind.PlacementAnchorRemoved, anchorFailure.kind)
        assertEquals("core.edit.conflicting-edits@1", anchorFailure.code)

        // ancestor-descendant
        val ancestorDescendant = document("a = 1\n")
        val aItem = rootItem(ancestorDescendant, "a")
        val aEntry2 = rootEntry(ancestorDescendant, "a")
        val ancestorFailure = assertFailsWith<TomlEditException> {
            ancestorDescendant.commit(
                EditTransactionBuilder.new(ancestorDescendant)
                    .semanticScalar(
                        aItem,
                        PvInteger(BigInteger.valueOf(3)),
                        RepresentationPolicy.PreserveCompatible,
                    )
                    .removeEntry(aEntry2.nodeRef)
                    .build(),
            )
        }
        assertEquals(EditFailureKind.AncestorDescendantConflict, ancestorFailure.kind)
        assertEquals("core.edit.conflicting-edits@1", ancestorFailure.code)

        // unsupported-table-remove
        val unsupported = document("[service]\nport = 80\n")
        val serviceEntry = rootEntry(unsupported, "service")
        val unsupportedFailure = assertFailsWith<TomlEditException> {
            unsupported.commit(
                EditTransactionBuilder.new(unsupported)
                    .removeEntry(serviceEntry.nodeRef)
                    .build(),
            )
        }
        assertEquals(EditFailureKind.UnsupportedOperation, unsupportedFailure.kind)
        assertEquals("core.edit.operation-unsupported@1", unsupportedFailure.code)
    }

    /** operations-v1.json operations.v1.toml-dry-run-proof-patch: dry-run
     * and commit share the replacement set and target digest, the summary
     * hides raw values, and the patch replays. */
    @Test
    fun dryRunProofPatch() {
        val document = document("value = 1\n")
        val transaction = EditTransactionBuilder.new(document)
            .insertEntry(
                document.root().nodeRef,
                "secret-key",
                PvString("secret-value"),
                AssociationPlacement.End,
            )
            .build()
        val plan = document.dryRun(transaction, EditPlanSourceId.new("config.toml"))
        val commit = document.commit(transaction)
        assertEquals(plan.replacements(), commit.sourcePatch.replacements())
        assertEquals(plan.targetDigest, commit.sourcePatch.targetDigest)
        assertTrue(
            plan.operations().flatMap { it.arguments.values }.none { it.contains("secret") },
        )
        val replayed = plan.sourcePatch.apply(document.source())
        assertTrue(replayed.bytes().contentEquals(commit.document.render()))
        commit.untouchedProof.verify(document.source(), commit.document.source(), commit.sourcePatch.replacements())
    }

    /** edit.rs:1685-1732: literal and semantic edits change only the scalar
     * spans and the ChangeSet facts map to the reparsed items. */
    @Test
    fun literalAndSemanticEditsChangeOnlyScalarSpans() {
        val document = document("hex = 0x2A # keep\nname = 'old'\nfloat = 1.0\n")
        val commit = document.commit(
            EditTransactionBuilder.new(document)
                .literalScalar(rootItem(document, "hex"), "0x2B".toByteArray())
                .semanticScalar(
                    rootItem(document, "name"),
                    PvString("new\nvalue"),
                    RepresentationPolicy.PreserveCompatible,
                )
                .semanticScalar(
                    rootItem(document, "float"),
                    PvBinaryFloat64(0x8000000000000000L),
                    RepresentationPolicy.PreserveCompatible,
                )
                .build(),
        )
        assertTrue(
            commit.document.render().contentEquals(
                "hex = 0x2B # keep\nname = \"new\\nvalue\"\nfloat = -0.0\n".toByteArray(),
            ),
        )
        assertEquals(3, commit.sourcePatch.replacements().size)
        assertEquals(3, commit.nodeMappings.size)
        assertTrue(commit.nodeMappings.all { it.status == consema.toml.TomlNodeMappingStatus.Replaced && it.new != null })
        val replayed = commit.sourcePatch.apply(document.source())
        assertTrue(replayed.bytes().contentEquals(commit.document.render()))
        commit.untouchedProof.verify(document.source(), commit.document.source(), commit.sourcePatch.replacements())
    }

    /** edit.rs:1734-1765: invalid or conflicting transactions leave the
     * base unchanged. */
    @Test
    fun invalidOrConflictingTransactionsLeaveBaseUnchanged() {
        val document = document("value = 1\narray = [1, 2]\n")
        val incompatible = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .semanticScalar(
                        rootItem(document, "value"),
                        PvString("one"),
                        RepresentationPolicy.PreserveCompatible,
                    )
                    .build(),
            )
        }
        assertEquals(EditFailureKind.RepresentationIncompatible, incompatible.kind)
        assertEquals("core.edit.representation-incompatible@1", incompatible.code)

        val container = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .literalScalar(rootItem(document, "array"), "3".toByteArray())
                    .build(),
            )
        }
        assertEquals(EditFailureKind.WrongRole, container.kind)
        assertEquals("core.edit.wrong-role@1", container.code)

        assertTrue(document.render().contentEquals("value = 1\narray = [1, 2]\n".toByteArray()))
    }

    /** edit.rs:1819-1837: exact literal validation rejects trivia,
     * containers, and extra assignments. */
    @Test
    fun exactLiteralRejectsTriviaContainersAndExtraAssignments() {
        for (literal in listOf(" 2", "2 # comment", "[1, 2]", "2\nother = 3")) {
            val failure = assertFailsWith<TomlEditException> {
                consema.toml.validateExactScalar(literal.toByteArray())
            }
            assertEquals(EditFailureKind.InvalidLiteral, failure.kind)
            assertEquals("core.edit.invalid-literal@1", failure.code)
        }
        assertEquals(consema.toml.TomlItemKind.Integer, consema.toml.validateExactScalar("0x2A".toByteArray()))
        assertEquals(
            consema.toml.TomlItemKind.String,
            consema.toml.validateExactScalar("\"multi\\nline\"".toByteArray()),
        )
    }

    /** edit.rs:1767-1817: semantic boundaries are rejected instead of
     * rounded. */
    @Test
    fun semanticBoundariesAreRejectedInsteadOfRounded() {
        val document = document("float = 1.0\ntime = 00:00:00\noffset = 1979-05-27T00:00:00Z\n")

        val nanPayload = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .semanticScalar(
                        rootItem(document, "float"),
                        PvBinaryFloat64(0x7FF8000000000001L),
                        RepresentationPolicy.CanonicalForProfile,
                    )
                    .build(),
            )
        }
        assertIs<EditFailureKind.UnsupportedSemanticValue>(nanPayload.kind)

        val time = consema.core.PvTime.of(
            0, 0, 0,
            consema.core.PvDecimal.of(BigInteger.ONE, BigInteger.valueOf(-10)),
        )
        val precision = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .semanticScalar(
                        rootItem(document, "time"),
                        time,
                        RepresentationPolicy.CanonicalForProfile,
                    )
                    .build(),
            )
        }
        assertIs<EditFailureKind.UnsupportedSemanticValue>(precision.kind)

        val offset = consema.core.PvOffsetDateTime.of(
            consema.core.PvLocalDateTime(
                consema.core.PvDate.of(BigInteger.valueOf(1979), 5, 27),
                consema.core.PvTime.of(0, 0, 0, consema.core.PvDecimal.of(BigInteger.ONE, BigInteger.valueOf(-10))),
            ),
            1,
        )
        val offsetFailure = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .semanticScalar(
                        rootItem(document, "offset"),
                        offset,
                        RepresentationPolicy.CanonicalForProfile,
                    )
                    .build(),
            )
        }
        assertIs<EditFailureKind.UnsupportedSemanticValue>(offsetFailure.kind)
    }

    /** edit.rs:1839-1892: root and standard table insertions preserve
     * ownership. */
    @Test
    fun rootAndStandardTableInsertionsPreserveOwnership() {
        val document = document("root = 1\n\n[service]\nport = 80\n")
        val service = rootEntry(document, "service").item()

        val rootCommit = document.commit(
            EditTransactionBuilder.new(document)
                .insertEntry(
                    document.root().nodeRef,
                    "enabled",
                    PvBoolean(true),
                    AssociationPlacement.End,
                )
                .build(),
        )
        assertTrue(
            rootCommit.document.render().contentEquals(
                "root = 1\n\n\"enabled\" = true\n[service]\nport = 80\n".toByteArray(),
            ),
        )

        val tableCommit = document.commit(
            EditTransactionBuilder.new(document)
                .insertEntry(
                    service.nodeRef,
                    "host",
                    PvString("localhost"),
                    AssociationPlacement.End,
                )
                .build(),
        )
        assertTrue(
            tableCommit.document.render().contentEquals(
                "root = 1\n\n[service]\nport = 80\n\"host\" = \"localhost\"".toByteArray(),
            ),
        )
    }

    /** edit.rs:1942-1977: array insertions cover empty and commented
     * arrays. */
    @Test
    fun arrayInsertAndRemoveCoverEmptyAndCommentedArrays() {
        val empty = document("items = [ ]\n")
        val emptyArray = rootEntry(empty, "items").item()
        val start = empty.commit(
            EditTransactionBuilder.new(empty)
                .insertArrayElement(
                    emptyArray.nodeRef,
                    PvInteger(BigInteger.ONE),
                    AssociationPlacement.Start,
                )
                .build(),
        )
        assertTrue(start.document.render().contentEquals("items = [1 ]\n".toByteArray()))

        val commented = document("items = [1, # keep\n 2, 3,]\n")
        val elements = rootEntry(commented, "items").item().arrayElements()!!
        val after = commented.commit(
            EditTransactionBuilder.new(commented)
                .insertArrayElement(
                    rootEntry(commented, "items").itemNodeRef,
                    PvString("end"),
                    AssociationPlacement.After(elements[2].nodeRef),
                )
                .build(),
        )
        assertTrue(
            after.document.render().contentEquals("items = [1, # keep\n 2, 3,\"end\",]\n".toByteArray()),
        )
    }

    /** edit.rs:1979-2093: structural dependencies and table rules fail
     * atomically. */
    @Test
    fun structuralDependenciesFailAtomically() {
        val document = document("a = 1\nb = 2\n\n[service]\nport = 80\n")
        val aEntry = rootEntry(document, "a")
        val bEntry = rootEntry(document, "b")
        val serviceEntry = rootEntry(document, "service")

        val duplicateRename = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .renameEntry(bEntry.nodeRef, "a")
                    .build(),
            )
        }
        assertEquals(EditFailureKind.DuplicateKey, duplicateRename.kind)

        val crossContainer = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .insertEntry(
                        serviceEntry.itemNodeRef,
                        "x",
                        PvBoolean(true),
                        AssociationPlacement.Before(aEntry.nodeRef),
                    )
                    .build(),
            )
        }
        assertEquals(EditFailureKind.TargetNotFound, crossContainer.kind)

        val sameBoundary = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .insertEntry(
                        document.root().nodeRef,
                        "x",
                        PvBoolean(true),
                        AssociationPlacement.End,
                    )
                    .insertEntry(
                        document.root().nodeRef,
                        "y",
                        PvBoolean(false),
                        AssociationPlacement.End,
                    )
                    .build(),
            )
        }
        assertEquals(EditFailureKind.OverlappingOwnership, sameBoundary.kind)

        val nullValue = assertFailsWith<TomlEditException> {
            document.commit(
                EditTransactionBuilder.new(document)
                    .insertEntry(
                        document.root().nodeRef,
                        "null",
                        consema.core.PvNull,
                        AssociationPlacement.Start,
                    )
                    .build(),
            )
        }
        assertEquals(EditFailureKind.UnrepresentableValue(Kind.Null), nullValue.kind)
        assertTrue(document.render().contentEquals("a = 1\nb = 2\n\n[service]\nport = 80\n".toByteArray()))
    }

    /** edit.rs:2095-2120: an empty standard table insertion uses its header
     * newline and preserves CRLF. */
    @Test
    fun emptyStandardTableInsertionUsesHeaderNewlineAndCrlf() {
        val document = document("[empty]\r\n[next]\r\nx = 1\r\n")
        val empty = rootEntry(document, "empty").item()
        val commit = document.commit(
            EditTransactionBuilder.new(document)
                .insertEntry(
                    empty.nodeRef,
                    "enabled",
                    PvBoolean(true),
                    AssociationPlacement.End,
                )
                .build(),
        )
        assertTrue(
            commit.document.render().contentEquals(
                "[empty]\r\n\"enabled\" = true\r\n[next]\r\nx = 1\r\n".toByteArray(),
            ),
        )
    }
}
