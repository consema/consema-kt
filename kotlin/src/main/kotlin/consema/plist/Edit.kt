// Structural edit operations of the plist family: one immutable
// transaction, atomic commit, dry-run plan, untouched-byte proof, and
// SourcePatch derivation.
//
// Data authority:
//   - RFC 0013 §11 (https://github.com/consema/consema/blob/main/docs/rfcs/0013-plist-family-profiles-v1.md:683-715):
//     the six snapshot-bound operations; XML edits replace text or elements
//     only within operation-owned spans, keep every untouched byte, reparse
//     the target, and verify the promised plist semantics; binary edits are
//     structural (set-value rewrites the target object's marker and payload;
//     insert/remove rewrite the owning container's reference block, the
//     offset table, and the trailer when sizes change; shared references
//     are preserved; cycles are refused; all offset, size, and reference
//     arithmetic is checked before any output exists); values are typed
//     native facts, never raw markup; conflict validation covers wrong
//     profile/role/snapshot, missing or duplicate target, stale anchors,
//     overlapping source ownership, non-string keys, UID insertion into an
//     XML Document, unrepresentable values, limit failure, and reparse
//     failure; success returns the new Document, ChangeSet, UntouchedByte-
//     Proof, and a replayable SourcePatch.
//   - RFC 0004 §13-§16 (https://github.com/consema/consema/blob/main/docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:313-384): the transaction, precondition, and
//     conflict algebra; the dry-run plan; the untouched-byte proof; the
//     derived SourcePatch.
//   - conformance/vectors/plist-v1.json (plist.edit.*) pins the outcomes;
//     consema-rs/consema-plist/src/edit.rs is the byte-arbitration authority
//     (operation shapes edit.rs:83-251, failures edit.rs:389-455).
//   - Kotlin document owns SourcePatch (create/apply,
//     kotlin/.../document/Patch.kt:147-296) and UntouchedByteProof
//     (kotlin/.../document/UntouchedProof.kt:83-138); ChangeSet is not
//     shipped in the Kotlin plist family (recorded gap, six-repo audit
//     G090; the json-family precedent, kotlin/.../json/Edit.kt:27-28), so
//     the commit carries the ordered diagnostics instead.
//
// Kotlin-idiomatic design: failures are a sealed hierarchy whose [code] is
// the frozen registered mapping (edit.rs:442-453); commit/dry-run throw the
// typed [EditFailureException] so callers match exhaustively on the failure
// class.

package consema.plist

import consema.core.PortableValue
import consema.core.PvBinaryFloat32
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvBytes
import consema.core.PvInteger
import consema.core.PvObject
import consema.core.PvString
import consema.document.EditOperationSummary
import consema.document.EditPlan
import consema.document.EditPlanException
import consema.document.EditPlanSourceId
import consema.document.FormationStatus
import consema.document.SnapshotIdentity
import consema.document.SourcePatch
import consema.document.SourceReplacement
import consema.document.Span
import consema.document.UntouchedByteProof
import kotlin.math.absoluteValue

/** One root-relative path step (edit.rs:83-94; RFC 0013 §11). */
sealed class EditPathStep {
    /** One dictionary association with the given key; [occurrence] selects
     * the N-th physical association among duplicate keys. */
    data class DictKey(val key: PlistKey, val occurrence: Int) : EditPathStep()

    /** One array element at the given 0-based position. */
    data class ArrayIndex(val index: Int) : EditPathStep()
}

/** A root-relative path to one value or container (edit.rs:96-130). The
 * empty path denotes the root value. */
class EditPath private constructor(private val steps: List<EditPathStep>) {
    companion object {
        /** Root path. */
        fun root(): EditPath = EditPath(emptyList())

        /** Creates a path from ordered steps. */
        fun new(steps: List<EditPathStep>): EditPath = EditPath(steps)
    }

    /** Ordered path steps. */
    fun segments(): List<EditPathStep> = steps

    /** Creates a child path without modifying this path. */
    fun child(step: EditPathStep): EditPath = EditPath(steps + step)

    override fun equals(other: Any?): Boolean =
        other is EditPath && steps == other.steps

    override fun hashCode(): Int = steps.hashCode()

    override fun toString(): String = "EditPath($steps)"
}

/** Dictionary entry insertion placement (edit.rs:132-143). */
sealed class DictPlacement {
    /** Append before the closing `</dict>` (or wrap a self-closing
     * `<dict/>`). */
    data object End : DictPlacement()

    /** Insert immediately before the entry at the given 0-based source
     * position of the current dictionary state. */
    data class Before(val index: Int) : DictPlacement()

    /** Insert immediately after the entry at the given 0-based source
     * position of the current dictionary state. */
    data class After(val index: Int) : DictPlacement()
}

/** One typed native plist value supplied to an edit (edit.rs:145-185; RFC
 * 0013 §11). Values are typed native facts, never raw markup or raw bytes.
 * UID values, Float32 width facts, unpaired-surrogate strings, fractional-
 * second dates, dates outside the XML calendar's year range, non-XML
 * characters, and non-canonical NaN payloads are binary-only and fail XML
 * edits. */
sealed class EditValue {
    abstract fun kind(): PlistValueKind

    /** Exact UTF-16 string content. */
    data class String(val string: PlistString) : EditValue() {
        override fun kind(): PlistValueKind = PlistValueKind.String
    }

    /** Signed 64-bit integer. */
    data class Integer(val value: Long) : EditValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Integer
    }

    /** IEEE 754 real with its exact width fact. */
    data class Real(val real: PlistReal) : EditValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Real
    }

    /** Boolean. */
    data class BooleanV(val value: Boolean) : EditValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Boolean
    }

    /** Double seconds since the plist epoch. */
    data class Date(val seconds: Double) : EditValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Date
    }

    /** Exact bytes. */
    data class Data(val data: PlistData) : EditValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Data
    }

    /** Unsigned 32-bit UID (binary profile only). */
    data class Uid(val uid: PlistUid) : EditValue() {
        override fun kind(): PlistValueKind = PlistValueKind.Uid
    }

    companion object {
        /** Converts one complete PortableValue leaf to a typed edit value;
         * null when the value kind is not a plist native leaf. */
        fun fromPortable(value: PortableValue): EditValue? =
            when (value) {
                is PvString -> EditValue.String(PlistString.fromUnicode(value.value))
                is PvInteger -> bigIntegerToLong(value.value)?.let { EditValue.Integer(it) }
                is PvBinaryFloat64 -> EditValue.Real(PlistReal.double(value.bits))
                is PvBinaryFloat32 -> EditValue.Real(PlistReal.single(value.bits))
                is PvBoolean -> EditValue.BooleanV(value.value)
                is PvBytes -> EditValue.Data(PlistData.fromBytes(value.content()))
                is PvObject -> {
                    val uid = value.entries().firstOrNull { it.key == "uid" }
                    val epoch = value.entries().firstOrNull { it.key == "epoch" }
                    val seconds = value.entries().firstOrNull { it.key == "seconds" }
                    when {
                        epoch != null && seconds != null -> {
                            val epochValue = epoch.value as? PvString
                            val secondsValue = seconds.value as? PvBinaryFloat64
                            if (epochValue != null && epochValue.value == PLIST_EPOCH_SPELLING &&
                                secondsValue != null
                            ) {
                                EditValue.Date(secondsValue.toFloat())
                            } else {
                                null
                            }
                        }
                        uid != null -> {
                            val integer = uid.value as? PvInteger
                            val long = integer?.value?.let { bigIntegerToLong(it) }
                            if (long != null && long in 0..0xFFFF_FFFFL) {
                                EditValue.Uid(PlistUid(long.toInt()))
                            } else {
                                null
                            }
                        }
                        else -> null
                    }
                }
                else -> null
            }
    }
}

/** One snapshot-bound plist structural operation (edit.rs:187-251; RFC 0013
 * §11). The path, key, occurrence, index, and placement of every operation
 * refer to the document state as of the operation's own application:
 * operations of one transaction apply sequentially, so a later removal by
 * index may target an element an earlier insertion shifted. */
sealed class EditOperation {
    /** Replaces the value at the path with one typed native value. */
    data class SetValue(
        /** Path to the value to replace; the empty path is the root. */
        val path: EditPath,
        /** New typed value. */
        val value: EditValue,
    ) : EditOperation()

    /** Inserts one dictionary association. */
    data class InsertDictEntry(
        /** Path to the owning dictionary. */
        val path: EditPath,
        /** New key content. */
        val key: PlistKey,
        /** New entry value. */
        val value: EditValue,
        /** Explicit placement inside the dictionary. */
        val placement: DictPlacement,
    ) : EditOperation()

    /** Removes one dictionary association by key and occurrence. */
    data class RemoveDictEntry(
        /** Path to the owning dictionary. */
        val path: EditPath,
        /** Key content of the association to remove. */
        val key: PlistKey,
        /** 0-based position among the associations with this key. */
        val occurrence: Int,
    ) : EditOperation()

    /** Renames one dictionary key, preserving its association value. */
    data class RenameDictKey(
        /** Path to the owning dictionary. */
        val path: EditPath,
        /** Key content to rename. */
        val from: PlistKey,
        /** 0-based position among the associations with this key. */
        val occurrence: Int,
        /** New key content. */
        val to: PlistKey,
    ) : EditOperation()

    /** Inserts one array element before the current element at the index;
     * an index equal to the current length appends. */
    data class InsertArrayElement(
        /** Path to the owning array. */
        val path: EditPath,
        /** 0-based insertion position in the current array state. */
        val index: Int,
        /** New element value. */
        val value: EditValue,
    ) : EditOperation()

    /** Removes the array element at the given 0-based position of the
     * current array state. */
    data class RemoveArrayElement(
        /** Path to the owning array. */
        val path: EditPath,
        /** 0-based position of the element to remove. */
        val index: Int,
    ) : EditOperation()
}

/** Immutable snapshot-bound transaction (edit.rs:253-258). */
class EditTransaction internal constructor(
    /** Base snapshot identity. */
    val baseSnapshot: SnapshotIdentity,
    /** Ordered declared operations. */
    val operations: List<EditOperation>,
)

/** Builder that is not a committed edit (edit.rs:276-370). */
class EditTransactionBuilder internal constructor(private val base: SnapshotIdentity) {
    private val operations = ArrayList<EditOperation>()

    companion object {
        /** Binds a new transaction to one immutable base document. */
        fun new(document: Document): EditTransactionBuilder =
            EditTransactionBuilder(document.snapshotIdentity)
    }

    /** Adds one value replacement (edit.rs:292-297). */
    fun setValue(path: EditPath, value: EditValue): EditTransactionBuilder {
        operations.add(EditOperation.SetValue(path, value))
        return this
    }

    /** Adds one dictionary entry insertion (edit.rs:299-314). */
    fun insertDictEntry(
        path: EditPath,
        key: PlistKey,
        value: EditValue,
        placement: DictPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertDictEntry(path, key, value, placement))
        return this
    }

    /** Adds one dictionary entry removal (edit.rs:316-329). */
    fun removeDictEntry(path: EditPath, key: PlistKey, occurrence: Int = 0): EditTransactionBuilder {
        operations.add(EditOperation.RemoveDictEntry(path, key, occurrence))
        return this
    }

    /** Adds one dictionary key rename (edit.rs:331-346). */
    fun renameDictKey(
        path: EditPath,
        from: PlistKey,
        occurrence: Int,
        to: PlistKey,
    ): EditTransactionBuilder {
        operations.add(EditOperation.RenameDictKey(path, from, occurrence, to))
        return this
    }

    /** Adds one array element insertion (edit.rs:348-358). */
    fun insertArrayElement(path: EditPath, index: Int, value: EditValue): EditTransactionBuilder {
        operations.add(EditOperation.InsertArrayElement(path, index, value))
        return this
    }

    /** Adds one array element removal (edit.rs:360-366). */
    fun removeArrayElement(path: EditPath, index: Int): EditTransactionBuilder {
        operations.add(EditOperation.RemoveArrayElement(path, index))
        return this
    }

    /** Completes the immutable request; target validation happens atomically
     * at commit (edit.rs:368-370). */
    fun build(): EditTransaction = EditTransaction(base, operations.toList())
}

/** Atomic edit success (edit.rs:378-387). ChangeSet is not shipped in the
 * Kotlin plist family (recorded gap, six-repo audit G090; the json-family
 * precedent, kotlin/.../json/Edit.kt:27-28); the commit carries the
 * ordered edit diagnostics instead. */
class EditCommit(
    /** New immutable document. */
    val document: Document,
    /** Portable exact raw-byte application fact. */
    val sourcePatch: SourcePatch,
    /** Verifiable evidence for every byte outside the replacement set. */
    val untouchedProof: UntouchedByteProof,
    /** Ordered edit diagnostics. */
    val diagnostics: List<PlistDiagnostic>,
)

/** Stable edit validation or commit failure (edit.rs:389-420). The [code]
 * is the frozen registered mapping (edit.rs:442-453). */
sealed class EditFailure(val code: String) {
    /** Edits are forbidden on a recovered document or without a provable
     * native graph. */
    data object IncompleteTarget : EditFailure("core.edit.incomplete-target@1")

    /** Transaction or target belongs to another snapshot. */
    data object WrongSnapshot : EditFailure("core.edit.wrong-snapshot@1")

    /** A path step meets a container of the wrong kind. */
    data object WrongRole : EditFailure("core.edit.wrong-role@1")

    /** A path step, key occurrence, index, or placement anchor does not
     * exist in the current document state. */
    data object TargetNotFound : EditFailure("core.edit.target-not-found@1")

    /** Two operations target the same exact source position or occurrence. */
    data object ConflictingEdits : EditFailure("core.edit.conflicting-edits@1")

    /** One operation's source span contains bytes an earlier operation of
     * the same transaction replaced. */
    data object OverlappingOwnership : EditFailure("core.edit.conflicting-edits@1")

    /** A UID value was inserted into or set on an XML document. */
    data object UidInXml : EditFailure(PlistCodes.EDIT_UID_IN_XML)

    /** A typed value or key cannot be expressed in the target
     * representation. */
    data class UnrepresentableValue(val fact: String) : EditFailure(PlistCodes.EDIT_UNREPRESENTABLE)

    /** A configured edit or output bound was exceeded. */
    data class ResourceLimit(val name: String) : EditFailure("core.edit.resource-limit@1")

    /** The replacement document could not be formed under the original
     * limits. */
    data object NewDocumentFormationFailed : EditFailure("core.edit.formation-failed@1")
}

/** The typed edit failure thrown by [Document.commit] and
 * [Document.dryRun]. */
class EditFailureException(val failure: EditFailure) :
    Exception("edit: ${failure.code}")

/**
 * Atomically commits structural operations. On failure the document remains
 * unchanged (edit.rs:457-462; RFC 0013 §11).
 */
fun Document.commit(transaction: EditTransaction): EditCommit {
    if (formationStatus != FormationStatus.Complete || nativeRoot == null) {
        throw EditFailureException(EditFailure.IncompleteTarget)
    }
    if (transaction.baseSnapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    return if (profile.isXml()) {
        commitXml(transaction)
    } else {
        commitBinary(transaction)
    }
}

/**
 * Fully validates and plans a transaction without returning a new Document
 * (RFC 0004 §14; edit.rs:464-468). Dry-run and commit produce the same
 * replacement set and target digest.
 */
fun Document.dryRun(
    transaction: EditTransaction,
    sourceId: EditPlanSourceId,
): EditPlan {
    val commit = commit(transaction)
    return try {
        EditPlan.new(
            sourceId,
            profileId(),
            operationSummaries(transaction),
            commit.sourcePatch,
            commit.diagnostics.map {
                it.toProtocolDiagnostic(
                    sourceId.asStr(),
                    consema.protocol.ErrorCodeRegistry.forVersion(
                        consema.protocol.ErrorRegistryVersion.V7,
                    ),
                )
            },
        )
    } catch (e: EditPlanException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
}

/** Content-free operation summaries (RFC 0004 §14). */
private fun operationSummaries(transaction: EditTransaction): List<EditOperationSummary> =
    transaction.operations.map { operation ->
        val (id, arguments) = operationSummary(operation)
        EditOperationSummary.new(consema.document.FormatOperationId(id, 1), arguments)
    }

private fun operationSummary(operation: EditOperation): Pair<String, Map<String, String>> =
    when (operation) {
        is EditOperation.SetValue -> "plist.edit.set-value" to mapOf(
            "kind" to operation.value.kind().kindName(),
        )
        is EditOperation.InsertDictEntry -> "plist.edit.insert-dict-entry" to mapOf(
            "kind" to operation.value.kind().kindName(),
            "placement" to placementName(operation.placement),
        )
        is EditOperation.RemoveDictEntry -> "plist.edit.remove-dict-entry" to mapOf(
            "occurrence" to operation.occurrence.toString(),
        )
        is EditOperation.RenameDictKey -> "plist.edit.rename-dict-key" to mapOf(
            "occurrence" to operation.occurrence.toString(),
        )
        is EditOperation.InsertArrayElement -> "plist.edit.insert-array-element" to mapOf(
            "index" to operation.index.toString(),
            "kind" to operation.value.kind().kindName(),
        )
        is EditOperation.RemoveArrayElement -> "plist.edit.remove-array-element" to mapOf(
            "index" to operation.index.toString(),
        )
    }

private fun placementName(placement: DictPlacement): String =
    when (placement) {
        DictPlacement.End -> "End"
        is DictPlacement.Before -> "Before"
        is DictPlacement.After -> "After"
    }

private fun operationMetadata(transaction: EditTransaction): Map<String, String> =
    transaction.operations.mapIndexed { index, operation ->
        "operation.$index" to operationSummary(operation).first + "@1"
    }.toMap()

// ---------------------------------------------------------------------------
// Shared commit artifacts
// ---------------------------------------------------------------------------

private fun Document.buildCommit(
    newDocument: Document,
    prepared: List<PreparedEdit>,
    diagnostics: List<PlistDiagnostic>,
    transaction: EditTransaction,
): EditCommit {
    val replacements = prepared.map { edit ->
        SourceReplacement.new(
            edit.oldStart,
            edit.oldEnd,
            source.rawBytes().copyOfRange(edit.oldStart, edit.oldEnd),
            edit.replacement,
        )
    }
    val sourcePatch = try {
        SourcePatch.create(source, replacements, operationMetadata(transaction))
    } catch (e: consema.document.SourcePatchException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    val untouchedProof = try {
        UntouchedByteProof.create(source, newDocument.source(), replacements)
    } catch (e: consema.document.UntouchedByteProofException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    return EditCommit(newDocument, sourcePatch, untouchedProof, diagnostics)
}

/** One prepared byte edit in base-source coordinates; [operationIndex] is
 * the declaring operation's ordinal, used by the fold rule (edit.rs:403-
 * 408: the sequential model folds operations whose spans lie inside earlier
 * replacements and merges overlapping base spans at commit). */
private data class PreparedEdit(
    val oldStart: Int,
    val oldEnd: Int,
    val replacement: ByteArray,
    val operationIndex: Int = 0,
)

// ---------------------------------------------------------------------------
// XML edits
// ---------------------------------------------------------------------------

/** One logical container association of the working model: a base entity
 * index or a virtual inserted value. */
private class XmlSlot(
    /** Base value entity index, or -1 for a virtual slot. */
    val baseIndex: Int,
    /** Base dict-entry entity index of a base dict slot, or -1. */
    val entryIndex: Int = -1,
    /** Virtual inserted value. */
    val virtual: EditValue? = null,
    /** Key content of a virtual dict entry. */
    val virtualKey: PlistKey? = null,
)

/** One working container: the logical association list of one base
 * container over the base entity space. */
private class XmlContainer(val isDict: Boolean) {
    val slots = ArrayList<XmlSlot>()
}

/** The sequential working model of one XML transaction. */
private class XmlWorkingModel(private val document: Document) {
    private val containers = HashMap<Int, XmlContainer>()
    private val virtualEdits = HashMap<XmlSlot, Int>()

    init {
        fun materialize(index: Int) {
            val native = document.valueEntity(index).native ?: return
            when (native) {
                is NativeValue.Dict -> {
                    val container = XmlContainer(true)
                    containers[index] = container
                    for (entryIndex in native.entries) {
                        val entry = document.dictEntryEntity(entryIndex)
                        container.slots.add(XmlSlot(entry.valueIndex, entryIndex = entryIndex))
                        materialize(entry.valueIndex)
                    }
                }
                is NativeValue.Array -> {
                    val container = XmlContainer(false)
                    containers[index] = container
                    for (elementIndex in native.elements) {
                        val element = document.arrayElementEntity(elementIndex)
                        container.slots.add(XmlSlot(element.valueIndex))
                        materialize(element.valueIndex)
                    }
                }
                else -> {}
            }
        }
        materialize(document.rootIndex)
    }

    fun containerOf(index: Int): XmlContainer =
        containers[index] ?: throw EditFailureException(EditFailure.WrongRole)

    /** Invalidates one container whose value was replaced: its slots no
     * longer exist in the sequential document state, so later operations
     * resolving through it fail WrongRole (edit.rs:749-768). */
    fun invalidateContainer(index: Int) {
        containers.remove(index)
    }

    /** Records the prepared insertion edit of one virtual slot so later
     * operations can rewrite its fragment. */
    fun recordVirtualEdit(slot: XmlSlot, editIndex: Int) {
        virtualEdits[slot] = editIndex
    }

    /** The prepared insertion edit of one virtual slot. */
    fun virtualEditIndex(slot: XmlSlot): Int? = virtualEdits[slot]

    /** The key content of one slot (base key or virtual key). */
    fun slotKey(container: XmlContainer, slotIndex: Int): PlistKey? {
        val slot = container.slots[slotIndex]
        if (slot.virtualKey != null) {
            return slot.virtualKey
        }
        val entry = document.dictEntryEntity(slot.entryIndex)
        return (document.valueEntity(entry.keyIndex).native as? NativeValue.StringV)
            ?.string?.let { PlistKey.fromCodeUnits(it.codeUnits()) }
    }
}

private fun Document.commitXml(transaction: EditTransaction): EditCommit {
    val diagnostics = ArrayList<PlistDiagnostic>()
    val working = XmlWorkingModel(this)
    val prepared = ArrayList<PreparedEdit>()
    for ((operationIndex, operation) in transaction.operations.withIndex()) {
        prepared.addAll(prepareXmlOperation(operation, working, prepared, operationIndex))
    }
    // Overlap resolution on the base spans, mirroring the Rust sequential
    // model (edit.rs:668-728 record_edit, edit.rs:1947-1979 run merge):
    //   - a zero-width insertion exactly at a replaced span's boundary is
    //     its own record and never folds (edit.rs:687-690);
    //   - two zero-width insertions mapping to the same base position
    //     conflict (edit.rs:710-719 ConflictingEdits);
    //   - a later operation whose span contains an earlier operation's span
    //     folds the earlier one: the later replacement covers the earlier
    //     bytes, so the folded edit is dropped from the render set (the
    //     same result as the Rust commit-time run merge, edit.rs:1966-1979);
    //   - a partial overlap where the later span starts inside the earlier
    //     span without containing it is OverlappingOwnership (edit.rs:648-
    //     659 map_in); the working model invalidates replaced containers so
    //     a later operation can never target replaced content, which keeps
    //     this the defensive branch.
    val folded = HashSet<Int>()
    val sorted = prepared.sortedWith(compareBy<PreparedEdit> { it.oldStart }.thenBy { it.oldEnd })
    for (index in 1 until sorted.size) {
        val left = sorted[index - 1]
        val right = sorted[index]
        // Disjoint spans are independent; the boundary test must not hide a
        // same-position pair (two zero-width insertions at one base position
        // conflict, edit.rs:710-719).
        if (left.oldStart != right.oldStart && left.oldEnd <= right.oldStart) {
            continue
        }
        val leftZero = left.oldStart == left.oldEnd
        val rightZero = right.oldStart == right.oldEnd
        if (left.oldStart == right.oldStart) {
            if (leftZero && rightZero) {
                // Two zero-width insertions at one base position
                // (edit.rs:710-719).
                throw EditFailureException(EditFailure.ConflictingEdits)
            }
            if (leftZero || rightZero) {
                // A boundary insertion at a replaced span's start is its
                // own record (edit.rs:687-690); both edits render.
                continue
            }
        }
        val later = if (left.operationIndex > right.operationIndex) left else right
        val earlier = if (later === left) right else left
        if (later.oldStart <= earlier.oldStart && later.oldEnd >= earlier.oldEnd) {
            folded.add(earlier.operationIndex * 1_000_000 + earlier.oldStart)
        } else {
            throw EditFailureException(EditFailure.OverlappingOwnership)
        }
    }
    val effective = sorted.filter { edit ->
        // The defensive no-op filter: no operation of this commit produces
        // a void edit anymore, but a void edit would otherwise collide with
        // a same-start span in the scan above.
        !(edit.oldStart == 0 && edit.oldEnd == 0 && edit.replacement.isEmpty()) &&
            !folded.contains(edit.operationIndex * 1_000_000 + edit.oldStart)
    }
    var targetLen = source.len
    for (edit in effective) {
        targetLen = targetLen - (edit.oldEnd - edit.oldStart) + edit.replacement.size
        if (targetLen > parseLimits.common.maxSourceBytes) {
            throw EditFailureException(EditFailure.ResourceLimit("target-bytes"))
        }
    }
    val rendered = ByteArray(targetLen)
    var cursor = 0
    var out = 0
    for (edit in effective) {
        val keep = edit.oldStart - cursor
        System.arraycopy(source.rawBytes(), cursor, rendered, out, keep)
        out += keep
        System.arraycopy(edit.replacement, 0, rendered, out, edit.replacement.size)
        out += edit.replacement.size
        cursor = edit.oldEnd
    }
    System.arraycopy(source.rawBytes(), cursor, rendered, out, source.len - cursor)
    val newDocument = try {
        parseXml(rendered, PlistEncodingSelection.ProfileDefault, parseLimits)
    } catch (e: PlistFormationException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    if (newDocument.formationStatus != FormationStatus.Complete) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    return buildCommit(newDocument, effective, diagnostics, transaction)
}

/** One resolved path target: a base value entity or a virtual inserted
 * value slot. */
private sealed class XmlTarget {
    class Base(val index: Int) : XmlTarget()

    class Virtual(val container: XmlContainer, val slotIndex: Int, val slot: XmlSlot) : XmlTarget()
}

private fun Document.prepareXmlOperation(
    operation: EditOperation,
    working: XmlWorkingModel,
    prepared: ArrayList<PreparedEdit>,
    operationIndex: Int,
): List<PreparedEdit> = when (operation) {
    is EditOperation.SetValue -> prepareXmlSetValue(operation, working, prepared, operationIndex)
    is EditOperation.InsertDictEntry -> prepareXmlInsertDictEntry(operation, working, prepared, operationIndex)
    is EditOperation.RemoveDictEntry -> prepareXmlRemoveDictEntry(operation, working, prepared, operationIndex)
    is EditOperation.RenameDictKey -> prepareXmlRenameDictKey(operation, working, prepared, operationIndex)
    is EditOperation.InsertArrayElement -> prepareXmlInsertArrayElement(operation, working, prepared, operationIndex)
    is EditOperation.RemoveArrayElement -> prepareXmlRemoveArrayElement(operation, working, prepared, operationIndex)
}

/** Resolves one path against the current working state. Intermediate steps
 * must land on base containers (inserted values are scalars, RFC 0013 §11);
 * the final step may be a virtual slot. */
private fun Document.resolveXmlTarget(
    path: EditPath,
    working: XmlWorkingModel,
): XmlTarget {
    var current = rootIndex
    val segments = path.segments()
    for ((stepIndex, step) in segments.withIndex()) {
        val last = stepIndex == segments.size - 1
        val native = valueEntity(current).native
        when (step) {
            is EditPathStep.DictKey -> {
                val dict = native as? NativeValue.Dict
                    ?: throw EditFailureException(EditFailure.WrongRole)
                val container = working.containerOf(current)
                var seen = 0
                var found = -1
                for (slotIndex in container.slots.indices) {
                    val slot = container.slots[slotIndex]
                    val key = if (slot.virtualKey != null) {
                        slot.virtualKey
                    } else {
                        val entry = dictEntryEntity(slot.entryIndex)
                        (valueEntity(entry.keyIndex).native as? NativeValue.StringV)
                            ?.string?.let { PlistKey.fromCodeUnits(it.codeUnits()) }
                    }
                    if (key == step.key) {
                        if (seen == step.occurrence) {
                            found = slotIndex
                            break
                        }
                        seen += 1
                    }
                }
                if (found < 0) {
                    throw EditFailureException(EditFailure.TargetNotFound)
                }
                val slot = container.slots[found]
                if (last && slot.baseIndex < 0) {
                    return XmlTarget.Virtual(container, found, slot)
                }
                if (slot.baseIndex < 0) {
                    // A virtual value is a scalar and cannot own a container.
                    throw EditFailureException(EditFailure.WrongRole)
                }
                current = slot.baseIndex
            }
            is EditPathStep.ArrayIndex -> {
                val array = native as? NativeValue.Array
                    ?: throw EditFailureException(EditFailure.WrongRole)
                val container = working.containerOf(current)
                if (step.index !in container.slots.indices) {
                    throw EditFailureException(EditFailure.TargetNotFound)
                }
                val slot = container.slots[step.index]
                if (last && slot.baseIndex < 0) {
                    return XmlTarget.Virtual(container, step.index, slot)
                }
                if (slot.baseIndex < 0) {
                    throw EditFailureException(EditFailure.WrongRole)
                }
                current = slot.baseIndex
            }
        }
    }
    return XmlTarget.Base(current)
}

/** Resolves one path to a container entity index. */
private fun Document.resolveXmlContainer(
    path: EditPath,
    working: XmlWorkingModel,
): Int {
    val target = resolveXmlTarget(path, working)
    return when (target) {
        is XmlTarget.Base -> target.index
        is XmlTarget.Virtual -> throw EditFailureException(EditFailure.WrongRole)
    }
}

private fun Document.prepareXmlSetValue(
    operation: EditOperation.SetValue,
    working: XmlWorkingModel,
    prepared: ArrayList<PreparedEdit>,
    operationIndex: Int,
): List<PreparedEdit> {
    checkXmlValue(operation.value)
    val target = resolveXmlTarget(operation.path, working)
    val fragment = xmlFragment(operation.value)
    return when (target) {
        is XmlTarget.Base -> {
            val span = valueEntity(target.index).span
            // A replaced container loses its working slots: a later
            // operation resolving through it fails WrongRole exactly like
            // the Rust sequential model, where the target reparses to a
            // scalar after the replacement (edit.rs:749-768 resolve_path).
            working.invalidateContainer(target.index)
            listOf(PreparedEdit(span.startByte, span.endByte, fragment, operationIndex))
        }
        is XmlTarget.Virtual -> {
            // Replace the value element inside the insertion fragment: the
            // dict-entry fragment is `<key>K</key><VALUE>`, the array
            // fragment is `<VALUE>`. The rewrite merges into the insertion
            // edit in place (the Rust fold, edit.rs:691-706), so no separate
            // edit is recorded.
            val editIndex = working.virtualEditIndex(target.slot)
                ?: throw EditFailureException(EditFailure.TargetNotFound)
            val edit = prepared[editIndex]
            val text = edit.replacement.toString(Charsets.UTF_8)
            val valueStart = text.indexOf("</key>")
            val newText = if (valueStart >= 0) {
                text.substring(0, valueStart + 6) + fragment.toString(Charsets.UTF_8)
            } else {
                fragment.toString(Charsets.UTF_8)
            }
            prepared[editIndex] = PreparedEdit(edit.oldStart, edit.oldEnd, newText.toByteArray(Charsets.UTF_8), operationIndex)
            emptyList()
        }
    }
}

private fun Document.prepareXmlInsertDictEntry(
    operation: EditOperation.InsertDictEntry,
    working: XmlWorkingModel,
    prepared: ArrayList<PreparedEdit>,
    operationIndex: Int,
): List<PreparedEdit> {
    checkXmlValue(operation.value)
    val container = resolveXmlContainer(operation.path, working)
    val native = valueEntity(container).native as? NativeValue.Dict
        ?: throw EditFailureException(EditFailure.WrongRole)
    val workingContainer = working.containerOf(container)
    val position = when (operation.placement) {
        DictPlacement.End -> workingContainer.slots.size
        is DictPlacement.Before -> {
            if (operation.placement.index !in workingContainer.slots.indices) {
                throw EditFailureException(EditFailure.TargetNotFound)
            }
            operation.placement.index
        }
        is DictPlacement.After -> {
            if (operation.placement.index !in workingContainer.slots.indices) {
                throw EditFailureException(EditFailure.TargetNotFound)
            }
            operation.placement.index + 1
        }
    }
    val fragment = ("<key>" + xmlEscapeText(keyText(operation.key)) + "</key>" +
        xmlFragment(operation.value).toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
    val edits = ArrayList<PreparedEdit>()
    // The insertion edit lands at prepared.size + its local index once the
    // returned list is appended by the caller.
    val localIndex = insertXmlFragment(container, workingContainer, position, fragment, edits,
        native is NativeValue.Dict)
    val slot = XmlSlot(-1, virtual = operation.value, virtualKey = operation.key)
    workingContainer.slots.add(position, slot)
    working.recordVirtualEdit(slot, prepared.size + localIndex)
    return edits
}

private fun Document.prepareXmlRemoveDictEntry(
    operation: EditOperation.RemoveDictEntry,
    working: XmlWorkingModel,
    prepared: ArrayList<PreparedEdit>,
    operationIndex: Int,
): List<PreparedEdit> {
    val container = resolveXmlContainer(operation.path, working)
    val native = valueEntity(container).native as? NativeValue.Dict
        ?: throw EditFailureException(EditFailure.WrongRole)
    val workingContainer = working.containerOf(container)
    var seen = 0
    for (slotIndex in workingContainer.slots.indices) {
        val slot = workingContainer.slots[slotIndex]
        val key = if (slot.virtualKey != null) {
            slot.virtualKey
        } else {
            val entry = dictEntryEntity(slot.entryIndex)
            (valueEntity(entry.keyIndex).native as? NativeValue.StringV)
                ?.string?.let { PlistKey.fromCodeUnits(it.codeUnits()) }
        }
        if (key == operation.key) {
            if (seen == operation.occurrence) {
                if (slot.virtualKey != null) {
                    // Remove the inserted entry by emptying its insertion.
                    workingContainer.slots.removeAt(slotIndex)
                    val editIndex = working.virtualEditIndex(slot)
                        ?: throw EditFailureException(EditFailure.TargetNotFound)
                    val edit = prepared[editIndex]
                    prepared[editIndex] = PreparedEdit(edit.oldStart, edit.oldEnd, ByteArray(0), operationIndex)
                    return emptyList()
                }
                val entry = dictEntryEntity(slot.entryIndex)
                val entrySpan = entry.span
                workingContainer.slots.removeAt(slotIndex)
                return listOf(PreparedEdit(entrySpan.startByte, entrySpan.endByte, ByteArray(0), operationIndex))
            }
            seen += 1
        }
    }
    throw EditFailureException(EditFailure.TargetNotFound)
}

private fun Document.prepareXmlRenameDictKey(
    operation: EditOperation.RenameDictKey,
    working: XmlWorkingModel,
    prepared: ArrayList<PreparedEdit>,
    operationIndex: Int,
): List<PreparedEdit> {
    val container = resolveXmlContainer(operation.path, working)
    val native = valueEntity(container).native as? NativeValue.Dict
        ?: throw EditFailureException(EditFailure.WrongRole)
    val workingContainer = working.containerOf(container)
    var seen = 0
    for (slotIndex in workingContainer.slots.indices) {
        val slot = workingContainer.slots[slotIndex]
        val key = if (slot.virtualKey != null) {
            slot.virtualKey
        } else {
            val entry = dictEntryEntity(slot.entryIndex)
            (valueEntity(entry.keyIndex).native as? NativeValue.StringV)
                ?.string?.let { PlistKey.fromCodeUnits(it.codeUnits()) }
        }
        if (key == operation.from) {
            if (seen == operation.occurrence) {
                if (slot.virtualKey != null) {
                    // Rename the inserted key by rewriting the leading key
                    // element of its insertion fragment.
                    val editIndex = working.virtualEditIndex(slot)
                        ?: throw EditFailureException(EditFailure.TargetNotFound)
                    val edit = prepared[editIndex]
                    val text = edit.replacement.toString(Charsets.UTF_8)
                    val fromTag = "<key>" + xmlEscapeText(keyText(operation.from)) + "</key>"
                    if (!text.startsWith(fromTag)) {
                        throw EditFailureException(EditFailure.TargetNotFound)
                    }
                    val newText = "<key>" + xmlEscapeText(keyText(operation.to)) + "</key>" +
                        text.removePrefix(fromTag)
                    prepared[editIndex] =
                        PreparedEdit(edit.oldStart, edit.oldEnd, newText.toByteArray(Charsets.UTF_8), operationIndex)
                    return emptyList()
                }
                val keySpan = valueEntity(dictEntryEntity(slot.entryIndex).keyIndex).span
                return listOf(
                    PreparedEdit(
                        keySpan.startByte,
                        keySpan.endByte,
                        ("<key>" + xmlEscapeText(keyText(operation.to)) + "</key>")
                            .toByteArray(Charsets.UTF_8),
                        operationIndex,
                    ),
                )
            }
            seen += 1
        }
    }
    throw EditFailureException(EditFailure.TargetNotFound)
}

private fun Document.prepareXmlInsertArrayElement(
    operation: EditOperation.InsertArrayElement,
    working: XmlWorkingModel,
    prepared: ArrayList<PreparedEdit>,
    operationIndex: Int,
): List<PreparedEdit> {
    checkXmlValue(operation.value)
    val container = resolveXmlContainer(operation.path, working)
    val native = valueEntity(container).native as? NativeValue.Array
        ?: throw EditFailureException(EditFailure.WrongRole)
    val workingContainer = working.containerOf(container)
    if (operation.index !in 0..workingContainer.slots.size) {
        throw EditFailureException(EditFailure.TargetNotFound)
    }
    val fragment = xmlFragment(operation.value)
    val edits = ArrayList<PreparedEdit>()
    val localIndex = insertXmlFragment(container, workingContainer, operation.index, fragment, edits,
        native is NativeValue.Dict)
    val slot = XmlSlot(-1, virtual = operation.value)
    workingContainer.slots.add(operation.index, slot)
    working.recordVirtualEdit(slot, prepared.size + localIndex)
    return edits
}

private fun Document.prepareXmlRemoveArrayElement(
    operation: EditOperation.RemoveArrayElement,
    working: XmlWorkingModel,
    prepared: ArrayList<PreparedEdit>,
    operationIndex: Int,
): List<PreparedEdit> {
    val container = resolveXmlContainer(operation.path, working)
    val native = valueEntity(container).native as? NativeValue.Array
        ?: throw EditFailureException(EditFailure.WrongRole)
    val workingContainer = working.containerOf(container)
    if (operation.index !in workingContainer.slots.indices) {
        throw EditFailureException(EditFailure.TargetNotFound)
    }
    val slot = workingContainer.slots[operation.index]
    workingContainer.slots.removeAt(operation.index)
    if (slot.baseIndex < 0) {
        // Remove the inserted element by emptying its insertion.
        val editIndex = working.virtualEditIndex(slot)
            ?: throw EditFailureException(EditFailure.TargetNotFound)
        val edit = prepared[editIndex]
        prepared[editIndex] = PreparedEdit(edit.oldStart, edit.oldEnd, ByteArray(0), operationIndex)
        return emptyList()
    }
    val span = valueEntity(slot.baseIndex).span
    return listOf(PreparedEdit(span.startByte, span.endByte, ByteArray(0), operationIndex))
}

/** Inserts one fragment at the logical position of a working container and
 * returns the index of the prepared insertion edit (for later virtual-slot
 * operations). */
private fun Document.insertXmlFragment(
    containerIndex: Int,
    container: XmlContainer,
    position: Int,
    fragment: ByteArray,
    edits: ArrayList<PreparedEdit>,
    isDict: Boolean,
): Int {
    val containerSpan = valueEntity(containerIndex).span
    // Find the previous and next BASE slots around the position. A
    // dictionary slot anchors on the association span (key element through
    // value element), so Before/After placements land at entry boundaries;
    // an array slot anchors on the value element.
    var prevBaseEnd: Int? = null
    var nextBaseStart: Int? = null
    for (slotIndex in container.slots.indices) {
        val slot = container.slots[slotIndex]
        if (slot.baseIndex < 0) {
            continue
        }
        val span = if (slot.entryIndex >= 0) {
            dictEntryEntity(slot.entryIndex).span
        } else {
            valueEntity(slot.baseIndex).span
        }
        if (slotIndex < position) {
            prevBaseEnd = span.endByte
        }
        if (slotIndex >= position && nextBaseStart == null) {
            nextBaseStart = span.startByte
        }
    }
    return when {
        nextBaseStart != null -> {
            edits.add(PreparedEdit(nextBaseStart, nextBaseStart, fragment))
            edits.size - 1
        }
        prevBaseEnd != null -> {
            edits.add(PreparedEdit(prevBaseEnd, prevBaseEnd, fragment))
            edits.size - 1
        }
        else -> {
            // Empty container: wrap a self-closing tag or insert before the
            // close tag.
            val raw = source.rawBytes()
            var firstGreater = -1
            for (at in containerSpan.startByte until containerSpan.endByte) {
                if (raw[at] == '>'.code.toByte()) {
                    firstGreater = at
                    break
                }
            }
            if (firstGreater > containerSpan.startByte &&
                firstGreater < containerSpan.endByte &&
                raw[firstGreater - 1] == '/'.code.toByte()
            ) {
                // Self-closing `<dict/>` / `<array/>`: replace `/>` with
                // `>` + fragment + close tag.
                val closeTag = if (isDict) "</dict>" else "</array>"
                edits.add(
                    PreparedEdit(
                        firstGreater - 1,
                        firstGreater + 1,
                        (">" + fragment.toString(Charsets.UTF_8) + closeTag)
                            .toByteArray(Charsets.UTF_8),
                    ),
                )
                edits.size - 1
            } else {
                val closeStart = closeTagStartOf(containerSpan, isDict)
                edits.add(PreparedEdit(closeStart, closeStart, fragment))
                edits.size - 1
            }
        }
    }
}

/** The byte offset of the closing tag start of one container element. */
private fun Document.closeTagStartOf(span: Span, isDict: Boolean): Int {
    val raw = source.rawBytes()
    var at = span.endByte - 1
    while (at > span.startByte && raw[at] != '<'.code.toByte()) {
        at -= 1
    }
    return at
}

/** The XML fragment of one typed value (RFC 0013 §11: inserted values use
 * canonical fragments). */
internal fun xmlFragment(value: EditValue): ByteArray {
    val out = StringBuilder()
    when (value) {
        is EditValue.String -> {
            out.append("<string>")
            out.append(xmlEscapeText(value.string.toUnicode()
                ?: throw EditFailureException(EditFailure.UnrepresentableValue("unpaired-surrogate"))))
            out.append("</string>")
        }
        is EditValue.Integer -> {
            out.append("<integer>")
            out.append(value.value.toString())
            out.append("</integer>")
        }
        is EditValue.Real -> {
            out.append("<real>")
            out.append(renderEditReal(value.real))
            out.append("</real>")
        }
        is EditValue.BooleanV -> out.append(if (value.value) "<true/>" else "<false/>")
        is EditValue.Date -> {
            val fields = wholeSecondDateForEdit(value.seconds)
                ?: throw EditFailureException(EditFailure.UnrepresentableValue("fractional-date"))
            out.append("<date>")
            out.append(
                String.format(
                    java.util.Locale.ROOT,
                    "%s%04d-%02d-%02dT%02d:%02d:%02dZ",
                    if (fields.year < 0) "-" else "",
                    fields.year.absoluteValue, fields.month, fields.day,
                    fields.hour, fields.minute, fields.second,
                ),
            )
            out.append("</date>")
        }
        is EditValue.Data -> {
            out.append("<data>")
            out.append(editBase64(value.data.bytes()))
            out.append("</data>")
        }
        is EditValue.Uid -> throw EditFailureException(EditFailure.UidInXml)
    }
    return out.toString().toByteArray(Charsets.UTF_8)
}

private data class EditDateFields(val year: Long, val month: Long, val day: Long, val hour: Long, val minute: Long, val second: Long)

private fun wholeSecondDateForEdit(seconds: Double): EditDateFields? {
    if (seconds % 1.0 != 0.0) {
        return null
    }
    val unix = seconds + 978307200.0
    if (kotlin.math.abs(unix) >= 9_007_199_254_740_992.0) {
        return null
    }
    val unixInt = unix.toLong()
    val days = Math.floorDiv(unixInt, 86_400L)
    val secondsOfDay = Math.floorMod(unixInt, 86_400L)
    val z = days + 719_468
    val era = if (z >= 0) z else z - 146_096
    val eraNormalized = era / 146_097
    val dayOfEra = z - eraNormalized * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    var year = yearOfEra + eraNormalized * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
    val month = monthPrime + if (monthPrime < 10) 3 else -9
    year += if (month <= 2) 1 else 0
    if (year.absoluteValue > 0xFFFF_FFFFL) {
        return null
    }
    return EditDateFields(year, month, day, secondsOfDay / 3_600, (secondsOfDay % 3_600) / 60, secondsOfDay % 60)
}

private fun renderEditReal(real: PlistReal): String {
    val value = real.asDouble()
    return when {
        value.isNaN() -> "nan"
        value.isInfinite() -> if (negativeSign(value)) "-inf" else "inf"
        else -> value.toString()
    }
}

private fun editBase64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val out = StringBuilder()
    var index = 0
    while (index < bytes.size) {
        val first = bytes[index].toInt() and 0xFF
        val second = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
        val third = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
        val chunkLen = minOf(3, bytes.size - index)
        out.append(alphabet[first ushr 2])
        out.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
        out.append(if (chunkLen > 1) alphabet[((second and 0x0F) shl 2) or (third ushr 6)] else '=')
        out.append(if (chunkLen > 2) alphabet[third and 0x3F] else '=')
        index += 3
    }
    return out.toString()
}

/** XML-escapes one text run for fragments (RFC 0013 §4.9). */
private fun xmlEscapeText(text: String): String {
    val out = StringBuilder()
    for (character in text) {
        when (character) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '\r' -> out.append("&#13;")
            else -> out.append(character)
        }
    }
    return out.toString()
}

/** Expressibility gate of one typed value under the XML representation
 * (RFC 0013 §7; edit.rs:145-152). */
private fun checkXmlValue(value: EditValue) {
    when (value) {
        is EditValue.Uid -> throw EditFailureException(EditFailure.UidInXml)
        is EditValue.Real ->
            if (value.real.width == RealWidth.Float32) {
                throw EditFailureException(EditFailure.UnrepresentableValue("float32"))
            }
        is EditValue.Date -> {
            if (value.seconds.toRawBits() == java.lang.Double.doubleToRawLongBits(-0.0) ||
                wholeSecondDateForEdit(value.seconds) == null
            ) {
                throw EditFailureException(EditFailure.UnrepresentableValue("fractional-date"))
            }
        }
        is EditValue.String -> {
            if (value.string.status == PlistStringStatus.UnpairedSurrogate) {
                throw EditFailureException(EditFailure.UnrepresentableValue("unpaired-surrogate"))
            }
        }
        else -> {}
    }
}

private fun keyText(key: PlistKey): String =
    key.toUnicode() ?: throw EditFailureException(EditFailure.UnrepresentableValue("unpaired-surrogate"))

/** Exact signed 64-bit conversion of one portable integer; null when the
 * magnitude exceeds the range. */
private fun bigIntegerToLong(value: java.math.BigInteger): Long? =
    try {
        value.toLong()
    } catch (e: NumberFormatException) {
        null
    }

// ---------------------------------------------------------------------------
// Binary edits
// ---------------------------------------------------------------------------

private fun Document.commitBinary(transaction: EditTransaction): EditCommit {
    val working = BinaryWorkingModel(this)
    for (operation in transaction.operations) {
        applyBinaryOperation(operation, working)
    }
    val bytes = working.render()
    val newDocument = try {
        parseBinaryEntry(bytes, PlistEncodingSelection.ProfileDefault, parseLimits)
    } catch (e: PlistFormationException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    if (newDocument.formationStatus != FormationStatus.Complete) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    // Binary edits are structural: the object table, offset table, and
    // trailer form the replacement region (RFC 0013 §11).
    val replacements = listOf(
        SourceReplacement.new(
            8,
            source.len,
            source.rawBytes().copyOfRange(8, source.len),
            bytes.copyOfRange(8, bytes.size),
        ),
    )
    val sourcePatch = try {
        SourcePatch.create(source, replacements, operationMetadata(transaction))
    } catch (e: consema.document.SourcePatchException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    val untouchedProof = try {
        UntouchedByteProof.create(source, newDocument.source(), replacements)
    } catch (e: consema.document.UntouchedByteProofException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    return EditCommit(newDocument, sourcePatch, untouchedProof, emptyList())
}

/** The binary working model: object contents and container reference lists
 * over the base object table (RFC 0013 §11: shared references are
 * preserved; only the owning container's reference block, the offset table,
 * and the trailer are rewritten when sizes change). */
private class BinaryWorkingModel(private val document: Document) {
    /** Object content bytes, indexed by base object index. */
    val contents = ArrayList<ByteArray>()

    /** Container reference lists: element object indices for arrays; key
     * then value object indices for dicts. */
    val containerRefs = HashMap<Int, MutableList<Int>>()

    /** Whether each container is a dictionary (marker selection). */
    val containerKinds = HashMap<Int, Boolean>()

    /** Objects appended at the end of the table. */
    val appended = ArrayList<ByteArray>()

    /** Key strings of appended key objects, by object index (the Rust
     * sequential model reparses after every operation, so keys an earlier
     * operation of the transaction inserted resolve like base keys). */
    val appendedKeys = HashMap<Int, PlistString>()

    /** Rewritten container reference blocks. */
    val rewrite = HashMap<Int, List<Int>>()

    init {
        val facts = document.binaryFacts()
            ?: throw EditFailureException(EditFailure.IncompleteTarget)
        for (fact in facts.objects) {
            val span = fact.span
            contents.add(document.source.rawBytes().copyOfRange(span.startByte, span.endByte))
        }
        for (index in 0 until contents.size) {
            val native = document.valueEntity(index).native ?: continue
            when (native) {
                is NativeValue.Array -> {
                    val refs = ArrayList<Int>()
                    for (elementIndex in native.elements) {
                        refs.add(document.arrayElementEntity(elementIndex).valueIndex)
                    }
                    containerRefs[index] = refs
                    containerKinds[index] = false
                }
                is NativeValue.Dict -> {
                    val refs = ArrayList<Int>()
                    for (entryIndex in native.entries) {
                        refs.add(document.dictEntryEntity(entryIndex).keyIndex)
                    }
                    for (entryIndex in native.entries) {
                        refs.add(document.dictEntryEntity(entryIndex).valueIndex)
                    }
                    containerRefs[index] = refs
                    containerKinds[index] = true
                }
                else -> {}
            }
        }
    }

    fun appendObject(bytes: ByteArray): Int {
        appended.add(bytes)
        return contents.size + appended.size - 1
    }

    /** Appends one key object and registers its string content. */
    fun appendKeyObject(key: PlistKey): Int {
        val index = appendObject(binaryStringBytes(key))
        appendedKeys[index] = key.asString()
        return index
    }

    /** The string content of one key object: a base key entity or an
     * appended key object of this transaction. */
    fun keyStringOf(index: Int): PlistString? =
        appendedKeys[index] ?: (document.valueEntity(index).native as? NativeValue.StringV)?.string

    /** Renders the new snapshot: mutated contents, rewritten container ref
     * blocks, the offset table, and the trailer. */
    fun render(): ByteArray {
        val numObjects = contents.size + appended.size
        val refSize = refSizeForBinary(numObjects)
        val out = ArrayList<Byte>(1024)
        "bplist00".forEach { out.add(it.code.toByte()) }
        val offsets = ArrayList<Int>(numObjects)
        for (index in contents.indices) {
            offsets.add(out.size)
            val bytes = if (index in rewrite) {
                encodeContainer(containerKinds[index] ?: false, rewrite[index]!!, refSize)
            } else {
                contents[index]
            }
            for (byte in bytes) {
                out.add(byte)
            }
        }
        for (bytes in appended) {
            offsets.add(out.size)
            for (byte in bytes) {
                out.add(byte)
            }
        }
        val offsetTableOffset = out.size
        val offsetIntSize = refSizeForBinary(offsetTableOffset)
        for (offset in offsets) {
            writeBeBinary(out, offset.toLong(), offsetIntSize)
        }
        repeat(5) { out.add(0) }
        out.add(0)
        out.add(offsetIntSize.toByte())
        out.add(refSize.toByte())
        writeBeBinary(out, numObjects.toLong(), 8)
        // The top object keeps its base index; the trailer must name the
        // actual root (edit.rs:1561 writes document.root().index()).
        writeBeBinary(out, document.rootIndex.toLong(), 8)
        writeBeBinary(out, offsetTableOffset.toLong(), 8)
        return out.toByteArray()
    }

    private fun encodeContainer(isDict: Boolean, refs: List<Int>, refSize: Int): ByteArray {
        val out = ArrayList<Byte>()
        if (isDict) {
            writeSizedBinary(out, 0xD0, refs.size / 2)
        } else {
            writeSizedBinary(out, 0xA0, refs.size)
        }
        for (ref in refs) {
            writeBeBinary(out, ref.toLong(), refSize)
        }
        return out.toByteArray()
    }
}

private fun refSizeForBinary(maxIndex: Int): Int {
    var size = 1
    var capacity = 256L
    while (maxIndex >= capacity && size < 8) {
        size += 1
        capacity = if (capacity > Long.MAX_VALUE / 256) Long.MAX_VALUE else capacity * 256
    }
    return size
}

private fun writeBeBinary(out: ArrayList<Byte>, value: Long, width: Int) {
    for (shift in (0 until width).reversed()) {
        out.add(((value ushr (8 * shift)) and 0xFF).toByte())
    }
}

private fun writeSizedBinary(out: ArrayList<Byte>, marker: Int, count: Int) {
    if (count < 0x0F) {
        out.add((marker or count).toByte())
        return
    }
    out.add((marker or 0x0F).toByte())
    val width = if (count <= 0xFF) 1 else if (count <= 0xFFFF) 2 else 4
    out.add((0x10 or java.lang.Integer.numberOfTrailingZeros(width)).toByte())
    writeBeBinary(out, count.toLong(), width)
}

private fun Document.applyBinaryOperation(operation: EditOperation, working: BinaryWorkingModel) {
    when (operation) {
        is EditOperation.SetValue -> {
            val target = resolveBinaryValue(operation.path, working)
            working.contents[target] = binaryObjectBytes(operation.value)
        }
        is EditOperation.InsertDictEntry -> {
            val container = resolveBinaryContainer(operation.path, working)
            val refs = working.containerRefs[container]
                ?: throw EditFailureException(EditFailure.WrongRole)
            val keyIndex = working.appendKeyObject(operation.key)
            val valueIndex = working.appendObject(binaryObjectBytes(operation.value))
            val count = refs.size / 2
            val position = when (operation.placement) {
                DictPlacement.End -> count
                is DictPlacement.Before -> {
                    if (operation.placement.index !in 0..count) {
                        throw EditFailureException(EditFailure.TargetNotFound)
                    }
                    operation.placement.index
                }
                is DictPlacement.After -> {
                    if (operation.placement.index !in 0 until count) {
                        throw EditFailureException(EditFailure.TargetNotFound)
                    }
                    operation.placement.index + 1
                }
            }
            val newRefs = ArrayList<Int>(refs.size + 2)
            for (slot in 0 until position) {
                newRefs.add(refs[slot])
            }
            newRefs.add(keyIndex)
            for (slot in position until count) {
                newRefs.add(refs[slot])
            }
            for (slot in 0 until position) {
                newRefs.add(refs[count + slot])
            }
            newRefs.add(valueIndex)
            for (slot in position until count) {
                newRefs.add(refs[count + slot])
            }
            working.rewrite[container] = newRefs
            // Later operations of the transaction resolve against the
            // evolving reference lists (the Rust model reparses after every
            // operation, edit.rs:551-563), so the working refs must follow
            // the rewrite immediately.
            working.containerRefs[container] = newRefs
        }
        is EditOperation.RemoveDictEntry -> {
            val container = resolveBinaryContainer(operation.path, working)
            val refs = working.containerRefs[container]
                ?: throw EditFailureException(EditFailure.WrongRole)
            val count = refs.size / 2
            var seen = 0
            var found = -1
            for (slot in 0 until count) {
                val key = working.keyStringOf(refs[slot])
                if (key == operation.key.asString()) {
                    if (seen == operation.occurrence) {
                        found = slot
                        break
                    }
                    seen += 1
                }
            }
            if (found < 0) {
                throw EditFailureException(EditFailure.TargetNotFound)
            }
            val newRefs = ArrayList<Int>(refs.size - 2)
            for (slot in 0 until count) {
                if (slot != found) {
                    newRefs.add(refs[slot])
                }
            }
            for (slot in 0 until count) {
                if (slot != found) {
                    newRefs.add(refs[count + slot])
                }
            }
            working.rewrite[container] = newRefs
            // Later operations of the transaction resolve against the
            // evolving reference lists (the Rust model reparses after every
            // operation, edit.rs:551-563), so the working refs must follow
            // the rewrite immediately.
            working.containerRefs[container] = newRefs
        }
        is EditOperation.RenameDictKey -> {
            val container = resolveBinaryContainer(operation.path, working)
            val refs = working.containerRefs[container]
                ?: throw EditFailureException(EditFailure.WrongRole)
            val count = refs.size / 2
            var seen = 0
            var found = -1
            for (slot in 0 until count) {
                val key = working.keyStringOf(refs[slot])
                if (key == operation.from.asString()) {
                    if (seen == operation.occurrence) {
                        found = slot
                        break
                    }
                    seen += 1
                }
            }
            if (found < 0) {
                throw EditFailureException(EditFailure.TargetNotFound)
            }
            // A rename binds a fresh key object and rebinds this
            // dictionary's reference; other dictionaries sharing the old
            // key object keep its exact bytes (edit.rs:1700-1721,
            // RFC 0013 §11).
            val newKeyIndex = working.appendKeyObject(operation.to)
            val newRefs = ArrayList<Int>(refs)
            newRefs[found] = newKeyIndex
            working.rewrite[container] = newRefs
            // Later operations of the transaction resolve against the
            // evolving reference lists (the Rust model reparses after every
            // operation, edit.rs:551-563), so the working refs must follow
            // the rewrite immediately.
            working.containerRefs[container] = newRefs
        }
        is EditOperation.InsertArrayElement -> {
            val container = resolveBinaryContainer(operation.path, working)
            val refs = working.containerRefs[container]
                ?: throw EditFailureException(EditFailure.WrongRole)
            if (operation.index !in 0..refs.size) {
                throw EditFailureException(EditFailure.TargetNotFound)
            }
            val valueIndex = working.appendObject(binaryObjectBytes(operation.value))
            val newRefs = ArrayList<Int>(refs.size + 1)
            for (position in 0 until operation.index) {
                newRefs.add(refs[position])
            }
            newRefs.add(valueIndex)
            for (position in operation.index until refs.size) {
                newRefs.add(refs[position])
            }
            working.rewrite[container] = newRefs
            // Later operations of the transaction resolve against the
            // evolving reference lists (the Rust model reparses after every
            // operation, edit.rs:551-563), so the working refs must follow
            // the rewrite immediately.
            working.containerRefs[container] = newRefs
        }
        is EditOperation.RemoveArrayElement -> {
            val container = resolveBinaryContainer(operation.path, working)
            val refs = working.containerRefs[container]
                ?: throw EditFailureException(EditFailure.WrongRole)
            if (operation.index !in refs.indices) {
                throw EditFailureException(EditFailure.TargetNotFound)
            }
            val newRefs = ArrayList<Int>(refs.size - 1)
            for ((position, ref) in refs.withIndex()) {
                if (position != operation.index) {
                    newRefs.add(ref)
                }
            }
            working.rewrite[container] = newRefs
            // Later operations of the transaction resolve against the
            // evolving reference lists (the Rust model reparses after every
            // operation, edit.rs:551-563), so the working refs must follow
            // the rewrite immediately.
            working.containerRefs[container] = newRefs
        }
    }
}

/** Resolves one path to a value object index against the working ref
 * lists. */
private fun Document.resolveBinaryValue(path: EditPath, working: BinaryWorkingModel): Int {
    var current = rootIndex
    for (step in path.segments()) {
        when (step) {
            is EditPathStep.DictKey -> {
                val refs = working.containerRefs[current]
                    ?: throw EditFailureException(EditFailure.WrongRole)
                val count = refs.size / 2
                var seen = 0
                var found = -1
                for (slot in 0 until count) {
                    val key = working.keyStringOf(refs[slot])
                    if (key == step.key.asString()) {
                        if (seen == step.occurrence) {
                            found = slot
                            break
                        }
                        seen += 1
                    }
                }
                if (found < 0) {
                    throw EditFailureException(EditFailure.TargetNotFound)
                }
                current = refs[count + found]
            }
            is EditPathStep.ArrayIndex -> {
                val refs = working.containerRefs[current]
                    ?: throw EditFailureException(EditFailure.WrongRole)
                if (step.index !in refs.indices) {
                    throw EditFailureException(EditFailure.TargetNotFound)
                }
                current = refs[step.index]
            }
        }
    }
    return current
}

private fun Document.resolveBinaryContainer(path: EditPath, working: BinaryWorkingModel): Int =
    resolveBinaryValue(path, working)

/** Canonical binary object bytes of one typed value (RFC 0013 §10.2
 * widths). */
internal fun binaryObjectBytes(value: EditValue): ByteArray {
    val out = ArrayList<Byte>()
    when (value) {
        is EditValue.String -> writeBinaryString(out, value.string)
        is EditValue.Integer -> {
            val width = if (value.value >= 0) {
                when {
                    value.value <= 0xFF -> 1
                    value.value <= 0xFFFF -> 2
                    value.value <= 0xFFFF_FFFFL -> 4
                    else -> 8
                }
            } else {
                8
            }
            out.add((0x10 or java.lang.Integer.numberOfTrailingZeros(width)).toByte())
            writeBeBinary(out, value.value.toULong().toLong(), width)
        }
        is EditValue.Real -> when (value.real.width) {
            RealWidth.Float64 -> {
                out.add(0x23)
                writeBeBinary(out, value.real.bits, 8)
            }
            RealWidth.Float32 -> {
                out.add(0x22)
                writeBeBinary(out, value.real.bits, 4)
            }
        }
        is EditValue.BooleanV -> out.add(if (value.value) 0x09 else 0x08)
        is EditValue.Date -> {
            out.add(0x33)
            writeBeBinary(out, value.seconds.toRawBits(), 8)
        }
        is EditValue.Data -> {
            val bytes = value.data.bytes()
            writeSizedBinary(out, 0x40, bytes.size)
            for (byte in bytes) {
                out.add(byte)
            }
        }
        is EditValue.Uid -> {
            val uid = value.uid.toLong()
            val width = when {
                uid <= 0xFF -> 1
                uid <= 0xFFFF -> 2
                uid <= 0xFF_FFFF -> 3
                else -> 4
            }
            out.add((0x80 or (width - 1)).toByte())
            writeBeBinary(out, uid, width)
        }
    }
    return out.toByteArray()
}

private fun binaryStringBytes(key: PlistKey): ByteArray {
    val out = ArrayList<Byte>()
    writeBinaryString(out, key.asString())
    return out.toByteArray()
}

private fun writeBinaryString(out: ArrayList<Byte>, string: PlistString) {
    val units = string.codeUnits()
    if (units.all { it < 0x80 }) {
        writeSizedBinary(out, 0x50, units.size)
        for (unit in units) {
            out.add(unit.toByte())
        }
    } else {
        writeSizedBinary(out, 0x60, units.size)
        for (unit in units) {
            out.add((unit ushr 8).toByte())
            out.add(unit.toByte())
        }
    }
}

/** The sign bit of one double (true for -0.0, -inf, and negatives). */
private fun negativeSign(value: Double): Boolean =
    java.lang.Double.doubleToRawLongBits(value) < 0
