// Snapshot-bound structural edit transactions of the HCL family (RFC 0014
// §10, RFC 0004 §13-§16).
//
// Data authority:
//   - RFC 0014 §10 (https://github.com/consema/consema/blob/main/docs/rfcs/0014-hcl-family-profiles-v1.md:630-671):
//     both profiles publish the same snapshot-bound operations
//     (set-attribute-value, insert-attribute, remove-attribute,
//     rename-attribute, insert-block, remove-block); the tfvars profile
//     does not publish the block operations; values are supplied as typed
//     native facts or validated literal-complete values, never as raw
//     markup and never as unevaluated expression text; expression-AST
//     editing and derived-expression insertion are explicit v1 non-goals —
//     the `Expression` edit value is refused by every commit with
//     `hcl.edit.unrepresentable@1`; edits replace text only within
//     operation-owned spans, keep every untouched byte, reparse the target,
//     and verify the promised HCL semantics; conflict validation covers
//     wrong profile/role/snapshot, missing or duplicate target, stale
//     anchors, overlapping source ownership, duplicate-attribute creation,
//     `hcl.tfvars@1` block insertion, unrepresentable values, limit
//     failure, and reparse failure; success returns the new Document,
//     ChangeSet, `UntouchedByteProof`, and a replayable `SourcePatch`.
//   - https://github.com/consema/consema-rs/blob/main/consema-hcl/src/edit.rs pins the operation shapes (edit.rs:93-
//     264: BodyPathStep, BodyPath, NodeRef, BodyPlacement, EditValue,
//     EditKey), the failure codes (edit.rs:599-612: core.edit.wrong-
//     snapshot@1, core.edit.wrong-role@1, core.edit.incomplete-target@1,
//     hcl.edit.duplicate-attribute@1, hcl.edit.block-in-tfvars@1,
//     core.edit.conflicting-edits@1, hcl.edit.unrepresentable@1,
//     core.edit.resource-limit@1, core.edit.formation-failed@1), the
//     resolution and line-region helpers (edit.rs:940-1239), and the
//     commit/dry-run surface (edit.rs:614-637).
//   - RFC 0004 §13-§16 (:270-386): the transaction/conflict algebra, the
//     dry-run EditPlan, the untouched-byte proof, and SourcePatch
//     derivation.
//   - The six operation ids are pinned in Operations.kt
//     (operation_registry.rs:105-127).
//   - consema-go/go/hcl is a cross-reference only.
//
// Kotlin-idiomatic design: the edit surface mirrors the toml family
// (toml/Edit.kt): a builder-bound immutable transaction, a sealed failure
// vocabulary with frozen registered codes, and an atomic commit that
// renders, reparses, patches, and proves in one step; a failure never
// changes the base snapshot.

package consema.hcl

import consema.core.Kind
import consema.document.AssociationPlacement
import consema.document.ContentDigest
import consema.document.EditOperationSummary
import consema.document.EditPlan
import consema.document.EditPlanSourceId
import consema.document.FormatOperationId
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.SnapshotIdentity
import consema.document.SourcePatch
import consema.document.SourcePatchLimits
import consema.document.SourceReplacement
import consema.document.SourceLimits
import consema.document.Span
import consema.document.UntouchedByteProof
import consema.protocol.ErrorCodeRegistry
import consema.protocol.ErrorRegistryVersion
import java.math.BigInteger

/** One root-relative body path step (RFC 0014 §4.2, §10; edit.rs:99-138):
 * one block occurrence selected by exact type, exact label sequence, and
 * 0-based source position among the blocks with the same type and labels. */
data class BodyPathStep(
    val blockType: String,
    val labels: List<String>,
    val occurrence: Int,
)

/** A root-relative path to one body (RFC 0014 §10; edit.rs:140-174). The
 * empty path denotes the root body. */
data class BodyPath(private val steps: List<BodyPathStep>) {
    companion object {
        /** Root body path. */
        fun root(): BodyPath = BodyPath(emptyList())
    }

    /** Ordered path steps. */
    fun segments(): List<BodyPathStep> = steps

    /** Creates a child path without modifying this path. */
    fun child(step: BodyPathStep): BodyPath = BodyPath(steps + step)
}

/** One exact body item address (RFC 0014 §10; edit.rs:176-212). An
 * attribute is addressed by owning body and name — unique per body in a
 * Complete document (RFC 0014 §3). */
sealed class HclNodeRef {
    /** One attribute occurrence. */
    data class Attribute(
        /** Owning body. */
        val body: BodyPath,
        /** Exact attribute name. */
        val name: String,
    ) : HclNodeRef()

    /** One block occurrence. */
    data class Block(
        /** Owning body. */
        val body: BodyPath,
        /** Exact block type. */
        val blockType: String,
        /** Exact label sequence. */
        val labels: List<String>,
        /** 0-based source position among the equal-type-and-labels blocks. */
        val occurrence: Int,
    ) : HclNodeRef()
}

/** Attribute insertion placement inside one body (RFC 0014 §10;
 * edit.rs:214-226). */
sealed class BodyPlacement {
    /** Insert before the body's first item (or at the body content start of
     * an empty body). */
    data object First : BodyPlacement()

    /** Insert after the body's last item's terminating line (or at the body
     * content end of an empty body). */
    data object Last : BodyPlacement()

    /** Insert immediately after the item addressed by one exact HclNodeRef
     * of the same body. */
    data class After(val anchor: HclNodeRef) : BodyPlacement()
}

/** One object-constructor literal key of an edit value (RFC 0014 §4.6,
 * §8.1; edit.rs:310-325). An identifier key spelled `for` is refused,
 * because the for-expression interpretation has priority. */
sealed class EditKey {
    /** Bare identifier key. */
    data class Identifier(val name: kotlin.String) : EditKey()

    /** Bare number-literal key. */
    data class Number(val value: Long) : EditKey()

    /** Bare quoted-literal-string key. */
    data class String(val value: kotlin.String) : EditKey()
}

/** One typed literal-complete HCL value supplied to an edit (RFC 0014 §10;
 * edit.rs:228-308). Values are typed native facts, never raw markup and
 * never unevaluated expression text. The `Expression` variant exists so
 * that derived-expression insertion is refused explicitly with
 * `hcl.edit.unrepresentable@1`; no commit ever renders it. */
sealed class EditValue {
    /** Signed 64-bit integer. */
    data class Integer(val value: Long) : EditValue()

    /** IEEE 754 real; must be finite. */
    data class Real(val value: Double) : EditValue()

    /** Exact string content. */
    data class String(val value: kotlin.String) : EditValue()

    /** Boolean. */
    data class Boolean(val value: kotlin.Boolean) : EditValue()

    /** Null. */
    data object Null : EditValue()

    /** Ordered tuple of literal values. */
    data class Tuple(val elements: List<EditValue>) : EditValue()

    /** Ordered object entries; duplicate keys are preserved. */
    data class Object(val entries: List<Pair<EditKey, EditValue>>) : EditValue()

    /** A derived expression: refused by every commit with
     * `hcl.edit.unrepresentable@1`. */
    data class Expression(val kind: kotlin.String, val text: kotlin.String) : EditValue()

    /** Stable value-kind spelling for summaries (edit.rs:266-281). */
    fun kindName(): kotlin.String = when (this) {
        is Integer -> "integer"
        is Real -> "real"
        is String -> "string"
        is Boolean -> "boolean"
        is Null -> "null"
        is Tuple -> "tuple"
        is Object -> "object"
        is Expression -> "expression"
    }
}

/** One snapshot-bound HCL structural operation (RFC 0014 §10; edit.rs:327-
 * 397). Every body path, name, and occurrence refers to the document state
 * as of the operation's own application. */
sealed class HclEditOperation {
    /** Replaces the target attribute's expression span with the canonical
     * rendering of one typed literal-complete value. */
    data class SetAttributeValue(
        /** Owning body of the target attribute. */
        val body: BodyPath,
        /** Exact target attribute name. */
        val attribute: String,
        /** New typed literal value. */
        val value: EditValue,
    ) : HclEditOperation()

    /** Adds one attribute to a target body at a position anchor. */
    data class InsertAttribute(
        /** Target body. */
        val body: BodyPath,
        /** New attribute name. */
        val name: String,
        /** New typed literal value. */
        val value: EditValue,
        /** Explicit placement inside the body. */
        val placement: BodyPlacement,
    ) : HclEditOperation()

    /** Removes one attribute's name, equals, expression, and owned trivia. */
    data class RemoveAttribute(
        /** Owning body of the target attribute. */
        val body: BodyPath,
        /** Exact target attribute name. */
        val attribute: String,
    ) : HclEditOperation()

    /** Changes one attribute name, preserving its expression. */
    data class RenameAttribute(
        /** Owning body of the target attribute. */
        val body: BodyPath,
        /** Exact target attribute name. */
        val attribute: String,
        /** New attribute name. */
        val name: String,
    ) : HclEditOperation()

    /** Adds one block (type, labels, and a nested body whose attributes are
     * typed literal-complete values) to a target body. */
    data class InsertBlock(
        /** Target body. */
        val body: BodyPath,
        /** New block type. */
        val blockType: String,
        /** New label sequence; labels always render quoted. */
        val labels: List<String>,
        /** Ordered nested attributes of the new block. */
        val attributes: List<Pair<String, EditValue>>,
        /** Explicit placement inside the body. */
        val placement: BodyPlacement,
    ) : HclEditOperation()

    /** Removes one block by exact type, labels, and occurrence. */
    data class RemoveBlock(
        /** Owning body of the target block. */
        val body: BodyPath,
        /** Exact target block type. */
        val blockType: String,
        /** Exact target label sequence. */
        val labels: List<String>,
        /** 0-based source position among the equal-type-and-labels blocks. */
        val occurrence: Int,
    ) : HclEditOperation()
}

/** Immutable snapshot-bound transaction (edit.rs:399-418). */
class HclEditTransaction internal constructor(
    /** Base snapshot identity. */
    val baseSnapshot: SnapshotIdentity,
    private val operations: List<HclEditOperation>,
) {
    /** Ordered declared operations. */
    fun operations(): List<HclEditOperation> = operations
}

/** Builder that is not a committed edit (edit.rs:420-527). */
class HclEditTransactionBuilder internal constructor(
    private val base: SnapshotIdentity,
    private val operations: MutableList<HclEditOperation> = ArrayList(),
) {
    companion object {
        /** Binds a new transaction to one immutable base document. */
        fun new(document: HclDocument): HclEditTransactionBuilder =
            HclEditTransactionBuilder(document.snapshotIdentity)
    }

    /** Replaces one attribute value. */
    fun setAttributeValue(body: BodyPath, attribute: String, value: EditValue): HclEditTransactionBuilder {
        operations.add(HclEditOperation.SetAttributeValue(body, attribute, value))
        return this
    }

    /** Inserts one attribute into a target body. */
    fun insertAttribute(
        body: BodyPath,
        name: String,
        value: EditValue,
        placement: BodyPlacement,
    ): HclEditTransactionBuilder {
        operations.add(HclEditOperation.InsertAttribute(body, name, value, placement))
        return this
    }

    /** Removes one attribute. */
    fun removeAttribute(body: BodyPath, attribute: String): HclEditTransactionBuilder {
        operations.add(HclEditOperation.RemoveAttribute(body, attribute))
        return this
    }

    /** Renames one attribute. */
    fun renameAttribute(body: BodyPath, attribute: String, name: String): HclEditTransactionBuilder {
        operations.add(HclEditOperation.RenameAttribute(body, attribute, name))
        return this
    }

    /** Inserts one block into a target body. */
    fun insertBlock(
        body: BodyPath,
        blockType: String,
        labels: List<String>,
        attributes: List<Pair<String, EditValue>>,
        placement: BodyPlacement,
    ): HclEditTransactionBuilder {
        operations.add(HclEditOperation.InsertBlock(body, blockType, labels, attributes, placement))
        return this
    }

    /** Removes one block. */
    fun removeBlock(
        body: BodyPath,
        blockType: String,
        labels: List<String>,
        occurrence: Int,
    ): HclEditTransactionBuilder {
        operations.add(HclEditOperation.RemoveBlock(body, blockType, labels, occurrence))
        return this
    }

    /** Completes the immutable request; target validation occurs atomically
     * at commit. */
    fun build(): HclEditTransaction = HclEditTransaction(base, operations.toList())
}

/** The closed edit failure vocabulary (edit.rs:536-578). The kind names are
 * the language-neutral comparison facts; [code] is the frozen registered
 * code (edit.rs:599-612). */
sealed class HclEditFailureKind {
    /** Transaction or target belongs to another snapshot. */
    data object WrongSnapshot : HclEditFailureKind()

    /** Target is not an attribute or the addressed body step meets an
     * attribute. */
    data object WrongRole : HclEditFailureKind()

    /** Target or placement anchor is not present in its declared body. */
    data object IncompleteTarget : HclEditFailureKind()

    /** An insertion would create a duplicate attribute in one body. */
    data object DuplicateAttribute : HclEditFailureKind()

    /** A block operation was declared under the `hcl.tfvars@1` profile. */
    data object BlockInTfvars : HclEditFailureKind()

    /** Two operations map to the same exact base position, or an insertion
     * anchor is removed by the same transaction. */
    data object ConflictingEdits : HclEditFailureKind()

    /** A typed value or name cannot be expressed as literal-complete HCL;
     * the payload names the blocking native fact. */
    data class UnrepresentableValue(val reason: String) : HclEditFailureKind()

    /** A configured edit or output bound was exceeded. */
    data class ResourceLimit(val limitName: String) : HclEditFailureKind()

    /** The replacement document could not be formed under the original
     * limits, or the reparsed target does not carry the promised
     * semantics. */
    data object NewDocumentFormationFailed : HclEditFailureKind()
    ;

    /** The frozen registered code (edit.rs:599-612). */
    val code: String
        get() = when (this) {
            WrongSnapshot -> "core.edit.wrong-snapshot@1"
            WrongRole -> "core.edit.wrong-role@1"
            IncompleteTarget -> "core.edit.incomplete-target@1"
            DuplicateAttribute -> HCL_EDIT_DUPLICATE_ATTRIBUTE
            BlockInTfvars -> HCL_EDIT_BLOCK_IN_TFVARS
            ConflictingEdits -> "core.edit.conflicting-edits@1"
            is UnrepresentableValue -> HCL_EDIT_UNREPRESENTABLE
            is ResourceLimit -> "core.edit.resource-limit@1"
            NewDocumentFormationFailed -> "core.edit.formation-failed@1"
        }

    /** The stable kind name. */
    val name: String
        get() = this::class.simpleName ?: "HclEditFailure"
}

/** The typed edit failure; [kind] is the stable language-neutral fact and
 * [code] the frozen registered code. */
class HclEditException(val kind: HclEditFailureKind) :
    Exception("hcl edit: ${kind.name} (${kind.code})") {
    /** The frozen registered code of the failure. */
    val code: String
        get() = kind.code
}

/** Atomic edit success (edit.rs:229-240). ChangeSet is not shipped in the
 * Kotlin HCL family (recorded gap, six-repo audit G090); the commit
 * exposes the equivalent facts as family records. */
class HclEditCommit(
    /** New immutable document. */
    val document: HclDocument,
    /** Portable exact raw-byte application fact. */
    val sourcePatch: SourcePatch,
    /** Verifiable evidence for every byte outside the replacement set. */
    val untouchedProof: UntouchedByteProof,
    /** Ordered edit diagnostics. */
    val diagnostics: List<HclDiagnostic>,
    /** Old-to-new node mapping facts. */
    val nodeMappings: List<HclNodeMapping>,
)

/** One old-to-new node mapping fact. */
data class HclNodeMapping(
    /** Old structural identity. */
    val old: NodeRef,
    /** New structural identity, or null when deleted/unmapped. */
    val new: NodeRef?,
    /** Mapping status. */
    val status: HclNodeMappingStatus,
    /** Stable reason for non-replaced mappings. */
    val reason: String?,
)

/** Node mapping status. */
enum class HclNodeMappingStatus {
    /** The item was replaced at the same span. */
    Replaced,

    /** The item was deleted. */
    Deleted,

    /** The item was not mapped. */
    Unmapped,
}

/**
 * Atomically commits structural operations (edit.rs:641-681). Operations
 * apply sequentially against the evolving document state; every splice is
 * recorded against the base snapshot with the fold semantics of
 * edit.rs:693-812, and commit merges the recorded base spans into maximal
 * non-overlapping runs whose replacements are the exact committed bytes
 * (edit.rs:1785-1854), so the ChangeSet, the SourcePatch, and the
 * UntouchedByteProof are always self-consistent. A failure never changes
 * this snapshot (RFC 0004 §13).
 */
fun HclDocument.commit(transaction: HclEditTransaction): HclEditCommit {
    if (transaction.baseSnapshot != snapshotIdentity) {
        throw HclEditException(HclEditFailureKind.WrongSnapshot)
    }
    if (formationStatus() != consema.document.FormationStatus.Complete) {
        throw HclEditException(HclEditFailureKind.IncompleteTarget)
    }
    if (transaction.operations().size > parseLimits.maxReportEvents) {
        throw HclEditException(HclEditFailureKind.ResourceLimit("report-events"))
    }
    validateDependencies(transaction)
    var bytes = render()
    val edits = ArrayList<AppliedEdit>()
    var current = this
    for (operation in transaction.operations()) {
        val splices = prepareOperation(current, operation)
        for (splice in splices) {
            // The target length bound is checked before the splice is
            // recorded (hard gate 4; edit.rs:814-838).
            val delta = splice.replacement.size - splice.preLen
            val targetLen = try {
                Math.addExact(bytes.size, delta)
            } catch (e: ArithmeticException) {
                throw HclEditException(HclEditFailureKind.ResourceLimit("target-bytes"))
            }
            if (targetLen > parseLimits.common.maxSourceBytes) {
                throw HclEditException(HclEditFailureKind.ResourceLimit("target-bytes"))
            }
            recordEdit(edits, splice.preStart, splice.preLen, splice.replacement)
            bytes = applySplice(bytes, splice)
        }
        val formed = try {
            parse(bytes, profile, parseLimits)
        } catch (e: HclFormationException) {
            throw HclEditException(HclEditFailureKind.NewDocumentFormationFailed)
        }
        if (formed.formationStatus() != consema.document.FormationStatus.Complete) {
            throw HclEditException(HclEditFailureKind.NewDocumentFormationFailed)
        }
        current = formed
    }
    verifyPromisedSemantics(transaction, current)

    // Merge the recorded base spans into maximal non-overlapping runs
    // (edit.rs:1801-1828); each run's replacement is the exact target bytes
    // at its new span (edit.rs:1829-1854).
    val spans = edits.mapIndexed { index, edit ->
        Triple(
            unmapIn(edits, index, edit.preStart),
            unmapIn(edits, index, edit.preStart + edit.preLen),
            edit.replacement.size - edit.preLen,
        )
    }.sortedWith(compareBy({ it.first }, { it.second }))
    val runs = ArrayList<Triple<Int, Int, Int>>()
    for ((start, end, delta) in spans) {
        val last = runs.lastOrNull()
        if (last != null && start <= last.second) {
            runs[runs.size - 1] = Triple(last.first, maxOf(last.second, end), last.third + delta)
        } else {
            runs.add(Triple(start, end, delta))
        }
    }
    val targetBytes = current.render()
    var beforeDelta = 0
    val replacements = ArrayList<SourceReplacement>(runs.size)
    for ((start, end, runDelta) in runs) {
        val targetStart = start + beforeDelta
        val runLen = (end - start) + runDelta
        if (runLen < 0) {
            throw HclEditException(HclEditFailureKind.NewDocumentFormationFailed)
        }
        val targetEnd = targetStart + runLen
        if (targetEnd > targetBytes.size) {
            throw HclEditException(HclEditFailureKind.NewDocumentFormationFailed)
        }
        replacements.add(
            SourceReplacement.new(
                start,
                end,
                source.rawBytes().copyOfRange(start, end),
                targetBytes.copyOfRange(targetStart, targetEnd),
            ),
        )
        beforeDelta += runDelta
    }

    val patchLimits = sourcePatchLimits(parseLimits, runs.size)
    val sourcePatch = try {
        SourcePatch.create(
            source,
            replacements,
            operationMetadata(transaction),
            patchLimits,
        )
    } catch (e: consema.document.SourcePatchException) {
        throw HclEditException(HclEditFailureKind.NewDocumentFormationFailed)
    }
    val untouchedProof = try {
        UntouchedByteProof.create(source, current.source(), sourcePatch.replacements())
    } catch (e: consema.document.UntouchedByteProofException) {
        throw HclEditException(HclEditFailureKind.NewDocumentFormationFailed)
    }
    return HclEditCommit(current, sourcePatch, untouchedProof, emptyList(), emptyList())
}

/** One applied raw-byte splice, recorded for base-coordinate translation
 * (edit.rs:693-707). */
private class AppliedEdit(
    var preStart: Int,
    val preLen: Int,
    var replacement: ByteArray,
)

/** Applies one splice to the current bytes (edit.rs:838-860). */
private fun applySplice(bytes: ByteArray, splice: PreparedSplice): ByteArray {
    val out = ByteArray(bytes.size - splice.preLen + splice.replacement.size)
    System.arraycopy(bytes, 0, out, 0, splice.preStart)
    System.arraycopy(splice.replacement, 0, out, splice.preStart, splice.replacement.size)
    System.arraycopy(
        bytes,
        splice.preStart + splice.preLen,
        out,
        splice.preStart + splice.replacement.size,
        bytes.size - splice.preStart - splice.preLen,
    )
    return out
}

/** Maps one position from one pre-state to the final state through the
 * applied edits in application order (edit.rs:731-744). */
private fun mapIn(edits: List<AppliedEdit>, fromIndex: Int, pos: Int): Int {
    var p = pos
    for (index in fromIndex until edits.size) {
        val edit = edits[index]
        if (p <= edit.preStart) {
            continue
        }
        if (p < edit.preStart + edit.preLen) {
            throw HclEditException(HclEditFailureKind.ConflictingEdits)
        }
        p = p + edit.replacement.size - edit.preLen
    }
    return p
}

/** Maps one position from the final state back to the base snapshot through
 * the applied edits in reverse application order; a position inside an
 * earlier replacement is an ownership overlap (edit.rs:709-729). */
private fun unmapIn(edits: List<AppliedEdit>, upTo: Int, pos: Int): Int {
    var p = pos
    for (index in (0 until upTo).reversed()) {
        val edit = edits[index]
        if (p <= edit.preStart) {
            continue
        }
        if (p < edit.preStart + edit.replacement.size) {
            val baseStart = unmapIn(edits, index, edit.preStart)
            return baseStart + (p - edit.preStart)
        }
        p = p - edit.replacement.size + edit.preLen
    }
    return p
}

/** Records one splice and rejects two insertions that map to the same base
 * position; an operation whose span lies inside a replacement an earlier
 * operation wrote folds into that replacement (edit.rs:746-812). */
private fun recordEdit(
    edits: MutableList<AppliedEdit>,
    preStart: Int,
    preLen: Int,
    replacement: ByteArray,
) {
    if (preLen == 0 && replacement.isEmpty()) {
        return
    }
    for (index in (0 until edits.size).reversed()) {
        val regionStart = mapIn(edits, index + 1, edits[index].preStart)
        val regionEnd = regionStart + edits[index].replacement.size
        // A zero-width insertion exactly at the region end is not operation
        // content of the region's owner and is recorded on its own.
        if (preStart >= regionStart && preStart + preLen <= regionEnd &&
            !(preLen == 0 && preStart == regionEnd)
        ) {
            val offset = preStart - regionStart
            val merged = ByteArray(edits[index].replacement.size + replacement.size - preLen)
            System.arraycopy(edits[index].replacement, 0, merged, 0, offset)
            System.arraycopy(replacement, 0, merged, offset, replacement.size)
            System.arraycopy(
                edits[index].replacement,
                offset + preLen,
                merged,
                offset + replacement.size,
                edits[index].replacement.size - offset - preLen,
            )
            val delta = merged.size - edits[index].replacement.size
            val targetStart = edits[index].preStart
            for (later in (index + 1) until edits.size) {
                if (edits[later].preStart > targetStart) {
                    edits[later].preStart += delta
                }
            }
            edits[index].replacement = merged
            return
        }
    }
    val baseStart = unmapIn(edits, edits.size, preStart)
    val baseEnd = unmapIn(edits, edits.size, preStart + preLen)
    for (index in edits.indices) {
        if (edits[index].preLen == 0 && baseStart == baseEnd) {
            val previousBase = unmapIn(edits, index, edits[index].preStart)
            if (previousBase == baseStart) {
                throw HclEditException(HclEditFailureKind.ConflictingEdits)
            }
        }
    }
    edits.add(AppliedEdit(preStart, preLen, replacement))
}

/**
 * Fully validates and plans an edit without returning a new Document
 * (edit.rs:621-637; RFC 0004 §14). Dry-run and commit produce the same
 * replacement set and target digest.
 */
fun HclDocument.dryRun(
    transaction: HclEditTransaction,
    sourceId: EditPlanSourceId,
): EditPlan {
    val commit = commit(transaction)
    val registry = ErrorCodeRegistry.forVersion(ErrorRegistryVersion.V7)
    val report = commit.diagnostics.map {
        it.toProtocolDiagnostic(sourceId.asStr(), registry)
    }
    return try {
        EditPlan.new(
            sourceId,
            profileId(),
            operationSummaries(transaction),
            commit.sourcePatch,
            report,
        )
    } catch (e: consema.document.EditPlanException) {
        throw HclEditException(HclEditFailureKind.NewDocumentFormationFailed)
    }
}

// ---------------------------------------------------------------------------
// Preparation
// ---------------------------------------------------------------------------

/** One prepared raw-byte splice in the current pre-state coordinates. */
private class PreparedSplice(
    val preStart: Int,
    val preLen: Int,
    val replacement: ByteArray,
)

/** Validates cross-operation dependencies before any preparation (RFC 0014
 * §10; edit.rs:1064-1100). */
private fun validateDependencies(transaction: HclEditTransaction) {
    val destructive = HashSet<HclNodeRef>()
    val anchors = ArrayList<HclNodeRef>()
    for (operation in transaction.operations()) {
        val target = when (operation) {
            is HclEditOperation.SetAttributeValue ->
                HclNodeRef.Attribute(operation.body, operation.attribute)
            is HclEditOperation.RemoveAttribute ->
                HclNodeRef.Attribute(operation.body, operation.attribute)
            is HclEditOperation.RenameAttribute ->
                HclNodeRef.Attribute(operation.body, operation.attribute)
            is HclEditOperation.RemoveBlock ->
                HclNodeRef.Block(operation.body, operation.blockType, operation.labels, operation.occurrence)
            is HclEditOperation.InsertAttribute, is HclEditOperation.InsertBlock -> null
        }
        if (target != null && !destructive.add(target)) {
            throw HclEditException(HclEditFailureKind.ConflictingEdits)
        }
        when (operation) {
            is HclEditOperation.InsertAttribute -> {
                if (operation.placement is BodyPlacement.After) {
                    anchors.add(operation.placement.anchor)
                }
            }
            is HclEditOperation.InsertBlock -> {
                if (operation.placement is BodyPlacement.After) {
                    anchors.add(operation.placement.anchor)
                }
            }
            else -> Unit
        }
    }
    for (anchor in anchors) {
        val removed = transaction.operations().any { operation ->
            when (operation) {
                is HclEditOperation.RemoveAttribute ->
                    HclNodeRef.Attribute(operation.body, operation.attribute) == anchor
                is HclEditOperation.RemoveBlock ->
                    HclNodeRef.Block(operation.body, operation.blockType, operation.labels, operation.occurrence) == anchor
                else -> false
            }
        }
        if (removed) {
            // A placement anchor removed by the same transaction is a stale
            // anchor conflict (RFC 0014 §10).
            throw HclEditException(HclEditFailureKind.ConflictingEdits)
        }
    }
}

/** Prepares one operation against the current document state (edit.rs:
 * 1334-1352); the splice is recorded in the current pre-state coordinates. */
private fun prepareOperation(document: HclDocument, operation: HclEditOperation): List<PreparedSplice> =
    when (operation) {
        is HclEditOperation.SetAttributeValue ->
            listOf(prepareSetAttributeValue(document, operation.body, operation.attribute, operation.value))
        is HclEditOperation.InsertAttribute ->
            listOf(prepareInsertAttribute(document, operation.body, operation.name, operation.value, operation.placement))
        is HclEditOperation.RemoveAttribute ->
            listOf(prepareRemoveAttribute(document, operation.body, operation.attribute))
        is HclEditOperation.RenameAttribute ->
            listOf(prepareRenameAttribute(document, operation.body, operation.attribute, operation.name))
        is HclEditOperation.InsertBlock ->
            listOf(prepareInsertBlock(document, operation.body, operation.blockType, operation.labels, operation.attributes, operation.placement))
        is HclEditOperation.RemoveBlock ->
            listOf(prepareRemoveBlock(document, operation.body, operation.blockType, operation.labels, operation.occurrence))
    }

/** Resolves one body by path (edit.rs:940-960). */
private fun HclDocument.resolveBody(path: BodyPath): HclBodyHandle {
    var body = rootBody()
    for (step in path.segments()) {
        val block = findBlock(body, step.blockType, step.labels, step.occurrence)
            ?: run {
                if (body.attributes().any { it.name() == step.blockType }) {
                    throw HclEditException(HclEditFailureKind.WrongRole)
                }
                throw HclEditException(HclEditFailureKind.IncompleteTarget)
            }
        body = block.body()
    }
    return body
}

/** One attribute occurrence by exact name; attributes are unique per body
 * in a Complete document (RFC 0014 §3; edit.rs:962-969). */
private fun findAttribute(body: HclBodyHandle, name: String): HclAttributeHandle? =
    body.attributes().firstOrNull { it.name() == name }

/** One block occurrence by exact type, label sequence, and occurrence
 * (edit.rs:971-996). */
private fun findBlock(
    body: HclBodyHandle,
    blockType: String,
    labels: List<String>,
    occurrence: Int,
): HclBlockHandle? {
    var seen = 0
    for (block in body.blocks()) {
        if (block.blockType() == blockType && block.labels().map { it.text() } == labels) {
            if (seen == occurrence) {
                return block
            }
            seen += 1
        }
    }
    return null
}

/** Resolves one exact item address (edit.rs:1025-1052). */
private fun HclDocument.resolveItem(node: HclNodeRef): Span {
    return when (node) {
        is HclNodeRef.Attribute -> {
            val body = resolveBody(node.body)
            findAttribute(body, node.name)?.span
                ?: throw HclEditException(HclEditFailureKind.IncompleteTarget)
        }
        is HclNodeRef.Block -> {
            val body = resolveBody(node.body)
            findBlock(body, node.blockType, node.labels, node.occurrence)?.span
                ?: throw HclEditException(HclEditFailureKind.IncompleteTarget)
        }
    }
}

/** The end of the line that terminates an item ending at `from`: the end of
 * the first LineBreak piece at or after `from`, or `from` itself when the
 * item is end-of-file-terminated (edit.rs:1071-1098). */
private fun HclDocument.itemLineEnd(from: Int): Int {
    var pos = from
    while (pos < source.len) {
        val kind = pieceKindAt(pos)
        when (kind) {
            HclSyntaxKind.Whitespace, HclSyntaxKind.LineComment, HclSyntaxKind.InlineComment -> {
                pos = pieceEndAt(pos)
            }
            HclSyntaxKind.LineBreak -> return pieceEndAt(pos)
            else -> throw HclEditException(HclEditFailureKind.NewDocumentFormationFailed)
        }
    }
    return pos
}

/** The start of the line that begins at `itemStart`: the beginning of the
 * whitespace run that indents the item (edit.rs:1100-1112). */
private fun HclDocument.itemLineStart(itemStart: Int): Int {
    var pos = itemStart
    while (pos > 0) {
        val (start, kind) = pieceEndingAt(pos) ?: break
        if (kind != HclSyntaxKind.Whitespace) {
            break
        }
        pos = start
    }
    return pos
}

/** The leading whitespace run of the line that starts an item, used as the
 * indentation of inserted markup (edit.rs:1114-1138). */
private fun HclDocument.itemIndent(itemStart: Int): String {
    var pos = itemStart
    var indent = ""
    while (pos > 0) {
        val (start, kind) = pieceEndingAt(pos) ?: break
        if (kind != HclSyntaxKind.Whitespace) {
            break
        }
        indent = source.decodedText()!!.substring(start, pos) + indent
        pos = start
    }
    return indent
}

/** The piece kind at one byte offset, or null at end of source. */
private fun HclDocument.pieceKindAt(offset: Int): HclSyntaxKind? {
    val kinds = losslessSyntaxKinds()
    val pieces = losslessStructuralIndex().pieces()
    val index = pieces.indexOfFirst { it.span.startByte <= offset && offset < it.span.endByte }
    return if (index >= 0) kinds[index] else null
}

/** The end byte of the piece starting at [offset]. */
private fun HclDocument.pieceEndAt(offset: Int): Int {
    val pieces = losslessStructuralIndex().pieces()
    val index = pieces.indexOfFirst { it.span.startByte == offset }
    return if (index >= 0) pieces[index].span.endByte else offset
}

/** The (start, kind) of the piece ending at [offset]. */
private fun HclDocument.pieceEndingAt(offset: Int): Pair<Int, HclSyntaxKind>? {
    val pieces = losslessStructuralIndex().pieces()
    val kinds = losslessSyntaxKinds()
    for ((index, piece) in pieces.withIndex()) {
        if (piece.span.endByte == offset && offset > 0 && piece.span.startByte < offset) {
            return piece.span.startByte to kinds[index]
        }
    }
    return null
}

/** The insertion point facts of an empty target body (edit.rs:1173-1190). */
private fun HclDocument.emptyBodyPoint(path: BodyPath): Pair<Int, String> =
    if (path.segments().isEmpty()) {
        source.len to ""
    } else {
        val parent = rootBody()
        var body = parent
        var depth = 0
        for (step in path.segments()) {
            val block = findBlock(body, step.blockType, step.labels, step.occurrence)
                ?: throw HclEditException(HclEditFailureKind.IncompleteTarget)
            body = block.body()
            depth += 1
        }
        // The content-end position is the owning block's closing brace; the
        // canonical indentation is two spaces per path depth.
        val blockSpan = resolveItem(HclNodeRef.Block(path, path.segments().last().blockType,
            path.segments().last().labels, path.segments().last().occurrence))
        val closeStart = blockSpan.endByte - 1
        closeStart to "  ".repeat(depth)
    }

/** Computes the insertion point, markup indentation, and whether the markup
 * needs a separating leading newline (the anchor item is end-of-file
 * terminated) for one insertion placement (edit.rs:1192-1239). */
private fun HclDocument.insertionPoint(
    body: HclBodyHandle,
    path: BodyPath,
    placement: BodyPlacement,
): Triple<Int, String, Boolean> {
    val items = body.items()
    if (items.isEmpty()) {
        val (point, indent) = emptyBodyPoint(path)
        return Triple(point, indent, false)
    }
    return when (placement) {
        BodyPlacement.First -> {
            val first = items.first()
            val start = when (first) {
                is HclBodyItemHandle.Attribute -> first.handle.nameSpan().startByte
                is HclBodyItemHandle.Block -> first.handle.span.startByte
            }
            Triple(itemLineStart(start), itemIndent(start), false)
        }
        BodyPlacement.Last -> {
            val last = items.last()
            val end = when (last) {
                is HclBodyItemHandle.Attribute -> last.handle.expression().span.endByte
                is HclBodyItemHandle.Block -> last.handle.span.endByte
            }
            val lineEnd = itemLineEnd(end)
            val indent = itemIndent(
                when (last) {
                    is HclBodyItemHandle.Attribute -> last.handle.nameSpan().startByte
                    is HclBodyItemHandle.Block -> last.handle.span.startByte
                },
            )
            // A separating leading newline is needed only when the anchor
            // item is end-of-file terminated without a terminating newline
            // (edit.rs:1192-1239).
            Triple(lineEnd, indent, lineEnd == end)
        }
        is BodyPlacement.After -> {
            val anchorSpan = resolveItem(placement.anchor)
            val lineEnd = itemLineEnd(anchorSpan.endByte)
            val indent = itemIndent(anchorSpan.startByte)
            Triple(lineEnd, indent, lineEnd == anchorSpan.endByte)
        }
    }
}

/** The canonical rendering of one typed literal-complete value (RFC 0014
 * §9-§10; edit.rs:1340-1490). */
private fun editValueText(value: EditValue): String = when (value) {
    is EditValue.Integer -> value.value.toString()
    is EditValue.Real -> {
        if (!value.value.isFinite()) {
            throw HclEditException(HclEditFailureKind.UnrepresentableValue("real"))
        }
        canonicalReal(value.value)
    }
    is EditValue.String -> "\"${escapeString(value.value)}\""
    is EditValue.Boolean -> if (value.value) "true" else "false"
    is EditValue.Null -> "null"
    is EditValue.Tuple -> {
        if (value.elements.isEmpty()) {
            "[]"
        } else {
            val out = StringBuilder("[\n")
            for (element in value.elements) {
                out.append("  ")
                out.append(editValueText(element))
                out.append(",\n")
            }
            out.append("]")
            out.toString()
        }
    }
    is EditValue.Object -> {
        if (value.entries.isEmpty()) {
            "{}"
        } else {
            val out = StringBuilder("{\n")
            for ((key, entryValue) in value.entries) {
                out.append("  ")
                out.append(editKeyText(key))
                out.append(" = ")
                out.append(editValueText(entryValue))
                out.append("\n")
            }
            out.append("}")
            out.toString()
        }
    }
    is EditValue.Expression ->
        // Derived-expression insertion is refused explicitly (RFC 0014
        // §10, §14); no commit ever renders it.
        throw HclEditException(HclEditFailureKind.UnrepresentableValue("expression"))
}

/** The canonical spelling of one edit object key (edit.rs:1492-1560). */
private fun editKeyText(key: EditKey): String = when (key) {
    is EditKey.Identifier -> {
        if (key.name == "for") {
            // An identifier key spelled `for` is refused, because the
            // for-expression interpretation has priority (RFC 0014 §4.6).
            throw HclEditException(HclEditFailureKind.UnrepresentableValue("object-key"))
        }
        if (!validIdentifier(key.name)) {
            throw HclEditException(HclEditFailureKind.UnrepresentableValue("object-key"))
        }
        key.name
    }
    is EditKey.Number -> key.value.toString()
    is EditKey.String -> "\"${escapeString(key.value)}\""
}

/** The canonical decimal of one finite double's shortest round-trip
 * spelling (RFC 0014 §9). */
private fun canonicalReal(value: Double): String {
    val text = value.toString()
    val canonical = canonicalDecimal(text) ?: text
    return canonical
}

/** One prepared set-attribute-value edit: replaces the expression span. */
private fun prepareSetAttributeValue(
    document: HclDocument,
    bodyPath: BodyPath,
    attributeName: String,
    value: EditValue,
): PreparedSplice {
    val body = document.resolveBody(bodyPath)
    val attribute = findAttribute(body, attributeName)
        ?: throw HclEditException(HclEditFailureKind.IncompleteTarget)
    val expressionSpan = attribute.expression().span
    val replacement = editValueText(value).toByteArray(Charsets.UTF_8)
    return PreparedSplice(expressionSpan.startByte, expressionSpan.len, replacement)
}

/** One prepared insert-attribute edit. */
private fun prepareInsertAttribute(
    document: HclDocument,
    bodyPath: BodyPath,
    name: String,
    value: EditValue,
    placement: BodyPlacement,
): PreparedSplice {
    if (!validIdentifier(name)) {
        throw HclEditException(HclEditFailureKind.UnrepresentableValue("attribute-name"))
    }
    val body = document.resolveBody(bodyPath)
    if (findAttribute(body, name) != null) {
        throw HclEditException(HclEditFailureKind.DuplicateAttribute)
    }
    val (point, indent, leadingNewline) = document.insertionPoint(body, bodyPath, placement)
    val valueText = editValueText(value)
    val markup = buildString {
        if (leadingNewline) {
            append('\n')
        }
        append(indent)
        append(name)
        append(" = ")
        append(valueText)
        append('\n')
    }
    return PreparedSplice(point, 0, markup.toByteArray(Charsets.UTF_8))
}

/** One prepared remove-attribute edit: removes the item's whole line. */
private fun prepareRemoveAttribute(
    document: HclDocument,
    bodyPath: BodyPath,
    attributeName: String,
): PreparedSplice {
    val body = document.resolveBody(bodyPath)
    val attribute = findAttribute(body, attributeName)
        ?: throw HclEditException(HclEditFailureKind.IncompleteTarget)
    val start = document.itemLineStart(attribute.nameSpan().startByte)
    val end = document.itemLineEnd(attribute.expression().span.endByte)
    return PreparedSplice(start, end - start, ByteArray(0))
}

/** One prepared rename-attribute edit: replaces only the name span. */
private fun prepareRenameAttribute(
    document: HclDocument,
    bodyPath: BodyPath,
    attributeName: String,
    newName: String,
): PreparedSplice {
    if (!validIdentifier(newName)) {
        throw HclEditException(HclEditFailureKind.UnrepresentableValue("attribute-name"))
    }
    val body = document.resolveBody(bodyPath)
    val attribute = findAttribute(body, attributeName)
        ?: throw HclEditException(HclEditFailureKind.IncompleteTarget)
    if (findAttribute(body, newName) != null && newName != attributeName) {
        throw HclEditException(HclEditFailureKind.DuplicateAttribute)
    }
    val nameSpan = attribute.nameSpan()
    return PreparedSplice(nameSpan.startByte, nameSpan.len, newName.toByteArray(Charsets.UTF_8))
}

/** One prepared insert-block edit. */
private fun prepareInsertBlock(
    document: HclDocument,
    bodyPath: BodyPath,
    blockType: String,
    labels: List<String>,
    attributes: List<Pair<String, EditValue>>,
    placement: BodyPlacement,
): PreparedSplice {
    if (document.profile == HclProfile.TFVARS_V1) {
        throw HclEditException(HclEditFailureKind.BlockInTfvars)
    }
    if (!validIdentifier(blockType)) {
        throw HclEditException(HclEditFailureKind.UnrepresentableValue("block-type"))
    }
    val names = HashSet<String>()
    for ((name, _) in attributes) {
        if (!validIdentifier(name)) {
            throw HclEditException(HclEditFailureKind.UnrepresentableValue("attribute-name"))
        }
        if (!names.add(name)) {
            throw HclEditException(HclEditFailureKind.DuplicateAttribute)
        }
    }
    val body = document.resolveBody(bodyPath)
    val (point, indent, leadingNewline) = document.insertionPoint(body, bodyPath, placement)
    val markup = buildString {
        if (leadingNewline) {
            append('\n')
        }
        append(indent)
        append(blockType)
        for (label in labels) {
            append(" \"")
            append(escapeString(label))
            append('"')
        }
        append(" {\n")
        for ((name, value) in attributes) {
            append(indent)
            append("  ")
            append(name)
            append(" = ")
            append(editValueText(value))
            append('\n')
        }
        append(indent)
        append("}\n")
    }
    return PreparedSplice(point, 0, markup.toByteArray(Charsets.UTF_8))
}

/** One prepared remove-block edit: removes the item's whole line. */
private fun prepareRemoveBlock(
    document: HclDocument,
    bodyPath: BodyPath,
    blockType: String,
    labels: List<String>,
    occurrence: Int,
): PreparedSplice {
    val body = document.resolveBody(bodyPath)
    val block = findBlock(body, blockType, labels, occurrence)
        ?: throw HclEditException(HclEditFailureKind.IncompleteTarget)
    val start = document.itemLineStart(block.span.startByte)
    val end = document.itemLineEnd(block.span.endByte)
    return PreparedSplice(start, end - start, ByteArray(0))
}

/** Verifies the promised semantics of the reparsed target (RFC 0014 §10:
 * the reparsed literal value must equal the supplied typed value, numbers
 * by canonical-decimal equality). */
private fun verifyPromisedSemantics(
    transaction: HclEditTransaction,
    newDocument: HclDocument,
) {
    for (operation in transaction.operations()) {
        when (operation) {
            is HclEditOperation.SetAttributeValue -> {
                val body = newDocument.resolveBody(operation.body)
                val attribute = findAttribute(body, operation.attribute)
                    ?: throw HclEditException(HclEditFailureKind.NewDocumentFormationFailed)
                val promised = literalOf(operation.value)
                val actual = literalValue(attribute.expression().expressionValue())
                if (promised == null || actual == null || !literalEqual(promised, actual)) {
                    throw HclEditException(HclEditFailureKind.NewDocumentFormationFailed)
                }
            }
            else -> Unit
        }
    }
}

/** The literal value of one edit value, or null for an expression. */
private fun literalOf(value: EditValue): HclLiteralValue? = when (value) {
    is EditValue.Integer -> HclLiteralValue.Integer(value.value.toString())
    is EditValue.Real -> canonicalReal(value.value).let {
        if (it.contains('.')) HclLiteralValue.Decimal(it) else HclLiteralValue.Integer(it)
    }
    is EditValue.String -> HclLiteralValue.String(value.value)
    is EditValue.Boolean -> HclLiteralValue.Boolean(value.value)
    is EditValue.Null -> HclLiteralValue.Null
    is EditValue.Tuple -> HclLiteralValue.Tuple(
        value.elements.map { literalOf(it) ?: return null },
    )
    is EditValue.Object -> HclLiteralValue.Object(
        value.entries.map { (key, entryValue) ->
            val literalKey = when (key) {
                is EditKey.Identifier -> HclLiteralKey.Identifier(key.name)
                is EditKey.Number -> HclLiteralKey.Number(key.value.toString())
                is EditKey.String -> HclLiteralKey.String(key.value)
            }
            HclLiteralObjectEntry(literalKey, literalOf(entryValue) ?: return null)
        },
    )
    is EditValue.Expression -> null
}

/** Structural literal equality (canonical decimals compare equal as
 * values). */
private fun literalEqual(left: HclLiteralValue, right: HclLiteralValue): Boolean = when (left) {
    is HclLiteralValue.Integer -> right is HclLiteralValue.Integer && left.text == right.text
    is HclLiteralValue.Decimal -> right is HclLiteralValue.Decimal && left.text == right.text
    is HclLiteralValue.String -> right is HclLiteralValue.String && left.text == right.text
    is HclLiteralValue.Boolean -> right is HclLiteralValue.Boolean && left.value == right.value
    is HclLiteralValue.Null -> right is HclLiteralValue.Null
    is HclLiteralValue.Tuple ->
        right is HclLiteralValue.Tuple &&
            left.elements.size == right.elements.size &&
            left.elements.zip(right.elements).all { (a, b) -> literalEqual(a, b) }
    is HclLiteralValue.Object ->
        right is HclLiteralValue.Object &&
            left.entries.size == right.entries.size &&
            left.entries.zip(right.entries).all { (a, b) ->
                literalKeyEqual(a.key, b.key) && literalEqual(a.value, b.value)
            }
}

private fun literalKeyEqual(left: HclLiteralKey, right: HclLiteralKey): Boolean = when (left) {
    is HclLiteralKey.Identifier -> right is HclLiteralKey.Identifier && left.name == right.name
    is HclLiteralKey.Number -> right is HclLiteralKey.Number && left.canonical == right.canonical
    is HclLiteralKey.String -> right is HclLiteralKey.String && left.text == right.text
    is HclLiteralKey.Value ->
        right is HclLiteralKey.Value && literalEqual(left.value, right.value)
}

/** The derived SourcePatch limits. */
private fun sourcePatchLimits(parseLimits: HclParseLimits, operationCount: Int): SourcePatchLimits =
    SourcePatchLimits(
        source = SourceLimits(
            maxRawBytes = parseLimits.common.maxSourceBytes,
            maxDecodedUtf8Bytes = parseLimits.common.maxSourceBytes,
            maxDecodedScalars = parseLimits.common.maxSourceBytes,
        ),
        maxReplacements = operationCount,
        maxPatchBytes = parseLimits.common.maxSourceBytes.coerceAtMost(Int.MAX_VALUE / 2) * 2,
    )

/** The ordered `operation.{index}` metadata of the derived SourcePatch. */
private fun operationMetadata(transaction: HclEditTransaction): Map<String, String> =
    transaction.operations().mapIndexed { index, operation ->
        "operation.$index" to operationId(operation)
    }.toMap()

/** The frozen `id@1` spelling of one operation (Operations.kt;
 * edit.rs:2037-2042). */
private fun operationId(operation: HclEditOperation): String = when (operation) {
    is HclEditOperation.SetAttributeValue -> "hcl.edit.set-attribute-value@1"
    is HclEditOperation.InsertAttribute -> "hcl.edit.insert-attribute@1"
    is HclEditOperation.RemoveAttribute -> "hcl.edit.remove-attribute@1"
    is HclEditOperation.RenameAttribute -> "hcl.edit.rename-attribute@1"
    is HclEditOperation.InsertBlock -> "hcl.edit.insert-block@1"
    is HclEditOperation.RemoveBlock -> "hcl.edit.remove-block@1"
}

/** The safe content-free operation summaries of the EditPlan (RFC 0004
 * §14). */
private fun operationSummaries(transaction: HclEditTransaction): List<EditOperationSummary> =
    transaction.operations().map { operation ->
        val (id, targetRole, arguments) = when (operation) {
            is HclEditOperation.SetAttributeValue -> Triple(
                "hcl.edit.set-attribute-value",
                "hcl.attribute",
                mapOf("value_kind" to operation.value.kindName()),
            )
            is HclEditOperation.InsertAttribute -> Triple(
                "hcl.edit.insert-attribute",
                "hcl.body",
                mapOf(
                    "placement" to placementName(operation.placement),
                    "value_kind" to operation.value.kindName(),
                ),
            )
            is HclEditOperation.RemoveAttribute -> Triple(
                "hcl.edit.remove-attribute",
                "hcl.attribute",
                emptyMap(),
            )
            is HclEditOperation.RenameAttribute -> Triple(
                "hcl.edit.rename-attribute",
                "hcl.attribute",
                emptyMap(),
            )
            is HclEditOperation.InsertBlock -> Triple(
                "hcl.edit.insert-block",
                "hcl.body",
                mapOf(
                    "placement" to placementName(operation.placement),
                    "label_count" to operation.labels.size.toString(),
                    "attribute_count" to operation.attributes.size.toString(),
                ),
            )
            is HclEditOperation.RemoveBlock -> Triple(
                "hcl.edit.remove-block",
                "hcl.block",
                emptyMap(),
            )
        }
        EditOperationSummary.new(FormatOperationId(id, 1), arguments)
    }

/** The stable placement spelling of one body placement. */
private fun placementName(placement: BodyPlacement): String = when (placement) {
    BodyPlacement.First -> "first"
    BodyPlacement.Last -> "last"
    is BodyPlacement.After -> "after"
}
