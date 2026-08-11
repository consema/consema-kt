// Scalar and structural edit operations: one immutable transaction, atomic
// commit, dry-run plan, untouched-byte proof, and SourcePatch derivation.
//
// Data authority:
//   - RFC 0004 §11-§16 (docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:271-384): snapshot-bound operations; inserted
//     values use the target profile's canonical materialization fragment;
//     delimiter edits own only the necessary comma plus inserted/removed
//     association span; JSONC comment ownership is explicit; the conflict
//     algebra; the dry-run plan; the untouched-byte proof; the derived
//     SourcePatch.
//   - RFC 0005 §10 (docs/rfcs/0005-json-family-production-v1.md:220-241):
//     move-member supports Start/End/Before/After within one Object, moves
//     only the exact member association span, owns the required source and
//     destination comma edits explicitly, and rejects cross-object anchors;
//     inserted caller strings are always escaped.
//   - conformance/vectors/json-family-v2.json (json5.edit.*) pins the golden
//     output bytes and failure names; conformance/vectors/v1.json
//     (edit.*, lines 107-141, 173-177) pins the PreserveCompatible /
//     CanonicalForProfile / PreserveElseCanonical scalar behaviors.
//   - crates/consema-json/src/edit.rs is the byte-arbitration authority
//     (commit edit.rs:301-451, dry-run edit.rs:453-468, prepare edit.rs:
//     472-1023, dependencies edit.rs:1025-1078, metadata/summaries edit.rs:
//     1095-1267, scalar style edit.rs:1346-1862).
//   - Kotlin document owns SourcePatch (create/apply, kotlin/.../document/
//     Patch.kt:147-296) and UntouchedByteProof (kotlin/.../document/
//     UntouchedProof.kt:83-138). ChangeSet is an L4 milestone; this L1
//     commit carries the ordered diagnostics instead.
//
// Kotlin-idiomatic design: failures are a sealed hierarchy whose [name] is
// the exact vector spelling (edit_failure_name, json_family_v2.rs:900-922);
// commit/dry-run throw the typed [EditFailureException] so callers match
// exhaustively on the failure class.

package consema.json

import consema.core.PortableValue
import consema.core.PvBinaryFloat64
import consema.core.PvBoolean
import consema.core.PvDecimal
import consema.core.PvInteger
import consema.core.PvNull
import consema.core.PvString
import consema.document.AssociationPlacement
import consema.document.EditOperationSummary
import consema.document.EditPlan
import consema.document.EditPlanException
import consema.document.EditPlanSourceId
import consema.document.FormatOperationId
import consema.document.FormationStatus
import consema.document.MaterializationException
import consema.document.MaterializationLimits
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.SnapshotIdentity
import consema.document.SourcePatch
import consema.document.SourceReplacement
import consema.document.Span
import consema.document.UntouchedByteProof
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity
import java.math.BigInteger

/** Explicit semantic scalar representation policy (edit.rs:17-28). */
enum class RepresentationPolicy {
    /** Caller must instead use Literal replacement; semantic replacement
     * rejects this. */
    ExactLiteral,

    /** Preserve the target's compatible native scalar category or fail. */
    PreserveCompatible,

    /** Use deterministic profile-canonical JSON literal syntax. */
    CanonicalForProfile,

    /** Try category preservation, then explicitly report canonical
     * fallback. */
    PreserveElseCanonical,
}

/** One scalar operation bound to the transaction's base snapshot
 * (edit.rs:30-49). */
sealed class ScalarReplacement {
    /** Exact target NodeRef. */
    abstract val target: NodeRef

    /** Replace by public semantic value under an explicit representation
     * policy. */
    data class Semantic(
        override val target: NodeRef,
        /** New complete core scalar. */
        val value: PortableValue,
        /** Representation contract. */
        val policy: RepresentationPolicy,
    ) : ScalarReplacement()

    /** Replace by exact candidate literal bytes after full profile
     * validation. */
    data class Literal(
        override val target: NodeRef,
        /** Exact candidate bytes. */
        val literal: ByteArray,
    ) : ScalarReplacement()
}

/** One typed JSON edit operation bound to an immutable base snapshot
 * (edit.rs:59-108). */
sealed class EditOperation {
    /** Existing scalar semantic or literal replacement. */
    data class ReplaceScalar(val replacement: ScalarReplacement) : EditOperation()

    /** Inserts one complete member into an Object value. */
    data class InsertMember(
        /** Exact Object value target. */
        val objectRef: NodeRef,
        /** Decoded member name. */
        val name: String,
        /** Complete inserted value. */
        val value: PortableValue,
        /** Explicit association placement. */
        val placement: AssociationPlacement,
    ) : EditOperation()

    /** Removes one exact member identity. */
    data class RemoveMember(val target: NodeRef) : EditOperation()

    /** Moves one exact member within its current Object. */
    data class MoveMember(
        /** Exact ObjectMember target. */
        val target: NodeRef,
        /** Placement within the same Object after removing the target. */
        val placement: AssociationPlacement,
    ) : EditOperation()

    /** Replaces only one exact member's key literal. */
    data class RenameMember(
        /** Exact ObjectMember target. */
        val target: NodeRef,
        /** New decoded name. */
        val name: String,
    ) : EditOperation()

    /** Inserts one complete element into an Array value. */
    data class InsertArrayElement(
        /** Exact Array value target. */
        val array: NodeRef,
        /** Complete inserted value. */
        val value: PortableValue,
        /** Explicit association placement. */
        val placement: AssociationPlacement,
    ) : EditOperation()

    /** Removes one exact array element identity. */
    data class RemoveArrayElement(val target: NodeRef) : EditOperation()
}

/** Immutable transaction; every operation resolves against one base snapshot
 * (edit.rs:110-129). */
class EditTransaction internal constructor(
    /** Base snapshot identity. */
    val baseSnapshot: SnapshotIdentity,
    /** Ordered declared operations. */
    val operations: List<EditOperation>,
)

/** Builder that is not a committed edit (edit.rs:131-243). */
class EditTransactionBuilder internal constructor(private val base: SnapshotIdentity) {
    private val operations = ArrayList<EditOperation>()

    companion object {
        /** Binds a new transaction to one immutable base document
         * (edit.rs:138-146). */
        fun new(document: Document): EditTransactionBuilder =
            EditTransactionBuilder(document.snapshotIdentity)
    }

    /** Adds semantic scalar replacement (edit.rs:148-161). */
    fun semanticScalar(
        target: NodeRef,
        value: PortableValue,
        policy: RepresentationPolicy,
    ): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceScalar(ScalarReplacement.Semantic(target, value, policy)))
        return this
    }

    /** Adds exact literal scalar replacement (edit.rs:163-171). */
    fun literalScalar(target: NodeRef, literal: ByteArray): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceScalar(ScalarReplacement.Literal(target, literal)))
        return this
    }

    /** Adds one JSON Object member insertion (edit.rs:173-189). */
    fun insertMember(
        objectRef: NodeRef,
        name: String,
        value: PortableValue,
        placement: AssociationPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertMember(objectRef, name, value, placement))
        return this
    }

    /** Adds one exact JSON Object member removal (edit.rs:191-195). */
    fun removeMember(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveMember(target))
        return this
    }

    /** Adds one exact same-Object member move (edit.rs:197-202). */
    fun moveMember(target: NodeRef, placement: AssociationPlacement): EditTransactionBuilder {
        operations.add(EditOperation.MoveMember(target, placement))
        return this
    }

    /** Adds one exact JSON Object member rename (edit.rs:204-211). */
    fun renameMember(target: NodeRef, name: String): EditTransactionBuilder {
        operations.add(EditOperation.RenameMember(target, name))
        return this
    }

    /** Adds one JSON Array element insertion (edit.rs:213-223). */
    fun insertArrayElement(
        array: NodeRef,
        value: PortableValue,
        placement: AssociationPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertArrayElement(array, value, placement))
        return this
    }

    /** Adds one exact JSON Array element removal (edit.rs:225-232). */
    fun removeArrayElement(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveArrayElement(target))
        return this
    }

    /** Completes the immutable request; target validation happens atomically
     * at commit (edit.rs:235-242). */
    fun build(): EditTransaction = EditTransaction(base, operations.toList())
}

/** Atomic edit success (edit.rs:246-256). ChangeSet is an L4 milestone in
 * Kotlin; this L1 commit carries the ordered edit diagnostics instead. */
class EditCommit(
    /** New immutable document. */
    val document: Document,
    /** Portable exact raw-byte application fact. */
    val sourcePatch: SourcePatch,
    /** Verifiable evidence for every byte outside the replacement set. */
    val untouchedProof: UntouchedByteProof,
    /** Ordered edit diagnostics (fallback events). */
    val diagnostics: List<Diagnostic>,
)

/** Stable edit validation or commit failure (edit.rs:258-299). The [name]
 * is the exact vector spelling (json_family_v2.rs:900-922). */
sealed class EditFailure(val name: String) {
    /** Edits are forbidden on a recovered document. */
    data object RecoveredDocument : EditFailure("RecoveredDocument")

    /** Transaction or target belongs to another snapshot. */
    data object WrongSnapshot : EditFailure("WrongSnapshot")

    /** Target role is not a scalar value or object key. */
    data object WrongRole : EditFailure("WrongRole")

    /** Target is not a complete literal syntax node. */
    data object IncompleteTarget : EditFailure("IncompleteTarget")

    /** Target native semantics are unavailable. */
    data object SemanticUnavailable : EditFailure("SemanticUnavailable")

    /** Public value cannot be represented as a JSON scalar. */
    data class UnsupportedSemanticValue(val kind: consema.core.Kind) :
        EditFailure("UnsupportedSemanticValue")

    /** Exact candidate is not one complete legal scalar literal for the
     * profile. */
    data object InvalidLiteral : EditFailure("InvalidLiteral")

    /** PreserveCompatible could not retain the scalar category. */
    data object RepresentationIncompatible : EditFailure("RepresentationIncompatible")

    /** ExactLiteral was incorrectly requested without literal bytes. */
    data object ExactLiteralRequiresLiteralOperation :
        EditFailure("ExactLiteralRequiresLiteralOperation")

    /** Two source edits overlap or target the same scalar. */
    data object ConflictingEdits : EditFailure("ConflictingEdits")

    /** More than one operation names the same exact destructive target. */
    data object DuplicateTarget : EditFailure("DuplicateTarget")

    /** Prepared source ownership intervals overlap or reuse one insertion
     * point. */
    data object OverlappingOwnership : EditFailure("OverlappingOwnership")

    /** One transaction edits an association and one of its owned
     * descendants. */
    data object AncestorDescendantConflict : EditFailure("AncestorDescendantConflict")

    /** An insertion anchor is removed by the same transaction. */
    data object PlacementAnchorRemoved : EditFailure("PlacementAnchorRemoved")

    /** A move target or anchor is modified by another operation in the
     * transaction. */
    data object PlacementAnchorModified : EditFailure("PlacementAnchorModified")

    /** A target or placement anchor is not present in its declared
     * container. */
    data object TargetNotFound : EditFailure("TargetNotFound")

    /** A structural value cannot be represented by the JSON target
     * profile. */
    data class UnrepresentableValue(val kind: consema.core.Kind) :
        EditFailure("UnrepresentableValue")

    /** A configured edit or output bound was exceeded. */
    data class ResourceLimit(val limitName: String) : EditFailure("ResourceLimit")

    /** Replacement document could not be formed under the original
     * limits. */
    data object NewDocumentFormationFailed : EditFailure("NewDocumentFormationFailed")
}

/** The typed edit failure thrown by [Document.commit] and
 * [Document.dryRun]. */
class EditFailureException(val failure: EditFailure) :
    Exception("edit: ${failure.name}")

/** One prepared byte edit (edit.rs:317-399). */
private data class PreparedEdit(
    val oldSpan: Span,
    val replacement: ByteArray,
)

/** Source ownership interval helper (edit.rs:330-346). */
private data class SourceEdit(
    val oldSpan: Span,
    val newSpan: Span,
    val replacement: ByteArray,
)

/**
 * Atomically commits scalar and structural operations. On failure the
 * document remains unchanged (edit.rs:301-451).
 */
fun Document.commit(transaction: EditTransaction): EditCommit {
    if (formationStatus != FormationStatus.Complete) {
        throw EditFailureException(EditFailure.RecoveredDocument)
    }
    if (transaction.baseSnapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    validateDependencies(transaction)
    val diagnostics = ArrayList<Diagnostic>()
    val prepared = ArrayList<PreparedEdit>()
    for (operation in transaction.operations) {
        prepared.addAll(prepareOperation(operation, diagnostics))
    }
    prepared.sortWith(compareBy<PreparedEdit> { it.oldSpan.startByte }.thenBy { it.oldSpan.endByte })
    for (index in 1 until prepared.size) {
        val left = prepared[index - 1]
        val right = prepared[index]
        if (!left.oldSpan.isEmpty && !right.oldSpan.isEmpty &&
            (left.oldSpan.endByte > right.oldSpan.startByte || left.oldSpan == right.oldSpan)
        ) {
            throw EditFailureException(EditFailure.AncestorDescendantConflict)
        }
        if (left.oldSpan == right.oldSpan ||
            (left.oldSpan.isEmpty && right.oldSpan.isEmpty &&
                left.oldSpan.startByte == right.oldSpan.startByte)
        ) {
            throw EditFailureException(EditFailure.OverlappingOwnership)
        }
    }
    var targetLen = source.len
    for (edit in prepared) {
        targetLen = targetLen - edit.oldSpan.len + edit.replacement.size
        if (targetLen > parseLimits.maxSourceBytes) {
            throw EditFailureException(EditFailure.ResourceLimit("target-bytes"))
        }
    }
    val rendered = ByteArray(targetLen)
    var cursor = 0
    var out = 0
    for (edit in prepared) {
        val keep = edit.oldSpan.startByte - cursor
        System.arraycopy(source.rawBytes(), cursor, rendered, out, keep)
        out += keep
        System.arraycopy(edit.replacement, 0, rendered, out, edit.replacement.size)
        out += edit.replacement.size
        cursor = edit.oldSpan.endByte
    }
    System.arraycopy(source.rawBytes(), cursor, rendered, out, source.len - cursor)
    val newDocument = try {
        parse(rendered, profile, parseLimits)
    } catch (e: JsonFormationException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }

    var delta = 0
    val sourceEdits = ArrayList<SourceEdit>()
    for (edit in prepared) {
        val newStart = edit.oldSpan.startByte + delta
        val newEnd = newStart + edit.replacement.size
        val newSpan = newDocument.authority.span(newStart, newEnd)
        sourceEdits.add(SourceEdit(edit.oldSpan, newSpan, edit.replacement))
        delta = delta + (edit.replacement.size - edit.oldSpan.len)
    }
    val replacements = sourceEdits.map { edit ->
        SourceReplacement.new(
            edit.oldSpan.startByte,
            edit.oldSpan.endByte,
            source.rawBytes().copyOfRange(edit.oldSpan.startByte, edit.oldSpan.endByte),
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

/**
 * Fully validates and plans an edit without returning a new Document
 * (edit.rs:453-468; RFC 0004 §14). Dry-run and commit produce the same
 * replacement set and target digest (RFC 0004 §20).
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
            operationSummariesOrFail(transaction),
            commit.sourcePatch,
            commit.diagnostics,
        )
    } catch (e: EditPlanException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
}

/** Content-free operation summaries (edit.rs:1135-1229). */
private fun operationSummariesOrFail(transaction: EditTransaction): List<EditOperationSummary> =
    try {
        operationSummaries(transaction)
    } catch (e: EditPlanException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }

// ---------------------------------------------------------------------------
// Operation preparation
// ---------------------------------------------------------------------------

private fun Document.prepareOperation(
    operation: EditOperation,
    diagnostics: ArrayList<Diagnostic>,
): List<PreparedEdit> = when (operation) {
    is EditOperation.ReplaceScalar -> listOf(prepareScalar(operation.replacement, diagnostics))
    is EditOperation.InsertMember -> prepareInsertMember(operation.objectRef, operation.name, operation.value, operation.placement)
    is EditOperation.RemoveMember -> prepareRemoveMember(operation.target)
    is EditOperation.MoveMember -> prepareMoveMember(operation.target, operation.placement)
    is EditOperation.RenameMember -> prepareRenameMember(operation.target, operation.name)
    is EditOperation.InsertArrayElement -> prepareInsertArrayElement(operation.array, operation.value, operation.placement)
    is EditOperation.RemoveArrayElement -> prepareRemoveArrayElement(operation.target)
}

private fun Document.prepareScalar(
    operation: ScalarReplacement,
    diagnostics: ArrayList<Diagnostic>,
): PreparedEdit {
    val target = operation.target
    val index = resolveTarget(target, listOf(NodeRole.Value, NodeRole.ObjectKey))
    val entity = valueEntity(index)
    if (!entity.complete || entity.literalSpan == null) {
        throw EditFailureException(EditFailure.IncompleteTarget)
    }
    if (entity.kind is InternalValueKind.Unavailable) {
        throw EditFailureException(EditFailure.SemanticUnavailable)
    }
    if (entity.kind is InternalValueKind.Array || entity.kind is InternalValueKind.Object) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    val replacement = when (operation) {
        is ScalarReplacement.Literal -> {
            val literalKind = validateLiteral(operation.literal, profile, parseLimits)
            if (target.role == NodeRole.ObjectKey && literalKind != JsonValueKind.String) {
                throw EditFailureException(EditFailure.InvalidLiteral)
            }
            operation.literal
        }
        is ScalarReplacement.Semantic -> {
            if (target.role == NodeRole.ObjectKey && operation.value !is PvString) {
                throw EditFailureException(
                    EditFailure.UnsupportedSemanticValue(operation.value.kind),
                )
            }
            val oldSpan = entity.literalSpan!!
            val oldLiteral = source.rawBytes().copyOfRange(oldSpan.startByte, oldSpan.endByte)
            semanticLiteral(
                authority,
                operation.value,
                entity.kind,
                oldLiteral,
                profile,
                operation.policy,
                oldSpan,
                diagnostics,
            )
        }
    }
    return PreparedEdit(entity.literalSpan!!, replacement)
}

private fun Document.prepareInsertMember(
    objectRef: NodeRef,
    name: String,
    value: PortableValue,
    placement: AssociationPlacement,
): List<PreparedEdit> {
    val index = resolveTarget(objectRef, listOf(NodeRole.Value))
    val entity = valueEntity(index)
    if (!entity.complete) {
        throw EditFailureException(EditFailure.IncompleteTarget)
    }
    val kind = entity.kind
    if (kind !is InternalValueKind.Object) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    val nameFragment = fragment(PvString(name))
    val memberFragment = appendFragment(
        nameFragment + byteArrayOf(0x3a), // ':'
        fragment(value),
        parseLimits.maxSourceBytes,
    )
    return listOf(
        prepareInsertion(
            objectRef,
            entity.span,
            kind.members,
            InsertionSyntax(NodeRole.ObjectMember, JsonSyntaxKind.LeftBrace, JsonSyntaxKind.RightBrace),
            placement,
            memberFragment,
        ),
    )
}

private fun Document.prepareInsertArrayElement(
    array: NodeRef,
    value: PortableValue,
    placement: AssociationPlacement,
): List<PreparedEdit> {
    val index = resolveTarget(array, listOf(NodeRole.Value))
    val entity = valueEntity(index)
    if (!entity.complete) {
        throw EditFailureException(EditFailure.IncompleteTarget)
    }
    val kind = entity.kind
    if (kind !is InternalValueKind.Array) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    return listOf(
        prepareInsertion(
            array,
            entity.span,
            kind.elements,
            InsertionSyntax(NodeRole.ArrayElement, JsonSyntaxKind.LeftBracket, JsonSyntaxKind.RightBracket),
            placement,
            fragment(value),
        ),
    )
}

private data class InsertionSyntax(
    val anchorRole: NodeRole,
    val open: JsonSyntaxKind,
    val close: JsonSyntaxKind,
)

private fun Document.prepareInsertion(
    container: NodeRef,
    containerSpan: Span,
    associations: List<Int>,
    syntax: InsertionSyntax,
    placement: AssociationPlacement,
    fragmentBytes: ByteArray,
): PreparedEdit {
    val (position, prefixComma, suffixComma) = if (associations.isEmpty()) {
        when (placement) {
            AssociationPlacement.Start -> Triple(
                delimiter(syntax.open, containerSpan, last = false).endByte,
                false,
                false,
            )
            AssociationPlacement.End -> Triple(
                delimiter(syntax.close, containerSpan, last = true).startByte,
                false,
                false,
            )
            is AssociationPlacement.Before, is AssociationPlacement.After ->
                throw EditFailureException(EditFailure.TargetNotFound)
        }
    } else {
        when (placement) {
            AssociationPlacement.Start -> Triple(
                span(associations.first()).startByte,
                false,
                true,
            )
            AssociationPlacement.End -> Triple(
                span(associations.last()).endByte,
                true,
                false,
            )
            is AssociationPlacement.Before -> {
                val anchor = resolveAnchor(placement.anchor, syntax.anchorRole, associations)
                Triple(span(anchor).startByte, false, true)
            }
            is AssociationPlacement.After -> {
                val anchor = resolveAnchor(placement.anchor, syntax.anchorRole, associations)
                Triple(span(anchor).endByte, true, false)
            }
        }
    }
    val replacement = ByteArray(fragmentBytes.size + 1)
    var out = 0
    if (prefixComma) {
        replacement[out++] = 0x2c
    }
    System.arraycopy(fragmentBytes, 0, replacement, out, fragmentBytes.size)
    out += fragmentBytes.size
    if (suffixComma) {
        replacement[out++] = 0x2c
    }
    return PreparedEdit(
        authority.span(position, position),
        replacement.copyOf(out),
    )
}

private fun Document.prepareRemoveMember(target: NodeRef): List<PreparedEdit> {
    val index = resolveTarget(target, listOf(NodeRole.ObjectMember))
    val parent = parentObject(index)
        ?: throw EditFailureException(EditFailure.TargetNotFound)
    return prepareRemoval(
        index,
        parent.members,
        parent.ordinal,
        span(parent.containerIndex).endByte,
    )
}

private fun Document.prepareMoveMember(
    target: NodeRef,
    placement: AssociationPlacement,
): List<PreparedEdit> {
    val index = resolveTarget(target, listOf(NodeRole.ObjectMember))
    val parent = parentObject(index)
        ?: throw EditFailureException(EditFailure.TargetNotFound)
    val remaining = parent.members.filter { it != index }
    val destination = when (placement) {
        AssociationPlacement.Start -> 0
        AssociationPlacement.End -> remaining.size
        is AssociationPlacement.Before -> {
            if (placement.anchor == target) {
                throw EditFailureException(EditFailure.PlacementAnchorModified)
            }
            val anchor = resolveAnchor(placement.anchor, NodeRole.ObjectMember, remaining)
            remaining.indexOf(anchor)
        }
        is AssociationPlacement.After -> {
            if (placement.anchor == target) {
                throw EditFailureException(EditFailure.PlacementAnchorModified)
            }
            val anchor = resolveAnchor(placement.anchor, NodeRole.ObjectMember, remaining)
            remaining.indexOf(anchor) + 1
        }
    }
    if (destination == parent.ordinal) {
        return emptyList()
    }
    val targetSpan = span(index)
    val fragmentBytes = source.rawBytes().copyOfRange(targetSpan.startByte, targetSpan.endByte)
    val edits = prepareRemoval(
        index,
        parent.members,
        parent.ordinal,
        span(parent.containerIndex).endByte,
    ).toMutableList()
    edits.add(
        prepareInsertion(
            nodeRef(parent.containerIndex, NodeRole.Value),
            span(parent.containerIndex),
            remaining,
            InsertionSyntax(NodeRole.ObjectMember, JsonSyntaxKind.LeftBrace, JsonSyntaxKind.RightBrace),
            placement,
            fragmentBytes,
        ),
    )
    return edits
}

private fun Document.prepareRemoveArrayElement(target: NodeRef): List<PreparedEdit> {
    val index = resolveTarget(target, listOf(NodeRole.ArrayElement))
    val parent = parentArray(index)
        ?: throw EditFailureException(EditFailure.TargetNotFound)
    return prepareRemoval(
        index,
        parent.elements,
        parent.ordinal,
        span(parent.containerIndex).endByte,
    )
}

private fun Document.prepareRemoval(
    index: Int,
    associations: List<Int>,
    ordinal: Int,
    containerEnd: Int,
): List<PreparedEdit> {
    val targetSpan = span(index)
    val edits = ArrayList<PreparedEdit>(2)
    val comma = removalComma(associations, ordinal, containerEnd)
    if (comma != null) {
        if (comma.endByte == targetSpan.startByte || comma.startByte == targetSpan.endByte) {
            edits.add(
                PreparedEdit(
                    authority.span(
                        minOf(comma.startByte, targetSpan.startByte),
                        maxOf(comma.endByte, targetSpan.endByte),
                    ),
                    ByteArray(0),
                ),
            )
            return edits
        }
        edits.add(PreparedEdit(targetSpan, ByteArray(0)))
        edits.add(PreparedEdit(comma, ByteArray(0)))
    } else {
        edits.add(PreparedEdit(targetSpan, ByteArray(0)))
    }
    return edits
}

private fun Document.prepareRenameMember(target: NodeRef, name: String): List<PreparedEdit> {
    val index = resolveTarget(target, listOf(NodeRole.ObjectMember))
    parentObject(index) ?: throw EditFailureException(EditFailure.TargetNotFound)
    val member = memberEntity(index)
    val key = valueEntity(member.key)
    val oldSpan = key.literalSpan
        ?: throw EditFailureException(EditFailure.IncompleteTarget)
    return listOf(PreparedEdit(oldSpan, fragment(PvString(name))))
}

private fun Document.resolveTarget(target: NodeRef, roles: List<NodeRole>): Int {
    if (target.snapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (target.role !in roles) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    return try {
        validateRef(target, roles)
    } catch (e: JsonAccessException) {
        when (e.kind) {
            JsonAccessErrorKind.WrongSnapshot -> throw EditFailureException(EditFailure.WrongSnapshot)
            JsonAccessErrorKind.WrongRole -> throw EditFailureException(EditFailure.WrongRole)
            JsonAccessErrorKind.UnknownNode -> throw EditFailureException(EditFailure.TargetNotFound)
        }
    }
}

private fun Document.resolveAnchor(
    anchor: NodeRef,
    role: NodeRole,
    associations: List<Int>,
): Int {
    val index = resolveTarget(anchor, listOf(role))
    if (index !in associations) {
        throw EditFailureException(EditFailure.TargetNotFound)
    }
    return index
}

/** The canonical fragment writer (edit.rs:902-923). */
private fun Document.fragment(value: PortableValue): ByteArray =
    try {
        canonicalFragment(
            value,
            profile,
            MaterializationLimits(
                maxInputNodes = parseLimits.maxNodeCount,
                maxOutputBytes = parseLimits.maxSourceBytes,
                maxDepth = parseLimits.maxNestingDepth,
                maxReportEntries = parseLimits.maxDiagnostics,
                maxProvenanceEntries = parseLimits.maxNodeCount * 4,
            ),
        )
    } catch (e: MaterializationException) {
        when (e.kind) {
            consema.document.MaterializationFailureKind.UNREPRESENTABLE -> throw EditFailureException(
                EditFailure.UnrepresentableValue(e.valueKind ?: consema.core.Kind.Null),
            )

            consema.document.MaterializationFailureKind.RESOURCE_LIMIT -> throw EditFailureException(
                EditFailure.ResourceLimit(e.name),
            )

            else -> throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
    }

/** Parent Object lookup by entity scan (edit.rs:925-939). */
private data class ParentContainer(
    val containerIndex: Int,
    val members: List<Int>,
    val ordinal: Int,
)

private data class ParentArray(
    val containerIndex: Int,
    val elements: List<Int>,
    val ordinal: Int,
)

private fun Document.parentObject(member: Int): ParentContainer? {
    for ((index, entity) in entities.withIndex()) {
        if (entity is Entity.Value) {
            val kind = entity.entity.kind
            if (kind is InternalValueKind.Object) {
                val ordinal = kind.members.indexOf(member)
                if (ordinal >= 0) {
                    return ParentContainer(index, kind.members, ordinal)
                }
            }
        }
    }
    return null
}

private fun Document.parentArray(element: Int): ParentArray? {
    for ((index, entity) in entities.withIndex()) {
        if (entity is Entity.Value) {
            val kind = entity.entity.kind
            if (kind is InternalValueKind.Array) {
                val ordinal = kind.elements.indexOf(element)
                if (ordinal >= 0) {
                    return ParentArray(index, kind.elements, ordinal)
                }
            }
        }
    }
    return null
}

/** Comma ownership of one removal (edit.rs:957-987). */
private fun Document.removalComma(
    associations: List<Int>,
    ordinal: Int,
    containerEnd: Int,
): Span? {
    val current = span(associations[ordinal])
    val followingEnd = associations.getOrNull(ordinal + 1)?.let { span(it).startByte } ?: containerEnd
    val comma = syntaxBetween(
        JsonSyntaxKind.Comma,
        current.endByte,
        followingEnd,
        last = false,
    )
    if (comma != null) {
        return comma
    }
    if (ordinal == 0) {
        return null
    }
    val previous = span(associations[ordinal - 1])
    return syntaxBetween(
        JsonSyntaxKind.Comma,
        previous.endByte,
        current.startByte,
        last = true,
    ) ?: throw EditFailureException(EditFailure.IncompleteTarget)
}

/** Container delimiter lookup (edit.rs:989-997). */
private fun Document.delimiter(
    kind: JsonSyntaxKind,
    container: Span,
    last: Boolean,
): Span =
    syntaxBetween(kind, container.startByte, container.endByte, last)
        ?: throw EditFailureException(EditFailure.IncompleteTarget)

/** Finds the first (or last) syntax piece of one kind inside a range
 * (edit.rs:999-1022). */
private fun Document.syntaxBetween(
    kind: JsonSyntaxKind,
    start: Int,
    end: Int,
    last: Boolean,
): Span? {
    val matches = pieces().zip(losslessSyntaxKinds()).filter { (piece, candidate) ->
        candidate == kind && piece.span.startByte >= start && piece.span.endByte <= end
    }.map { (piece, _) -> piece.span }
    return if (last) matches.lastOrNull() else matches.firstOrNull()
}

/** Cross-operation dependency validation (edit.rs:1025-1078). */
private fun validateDependencies(transaction: EditTransaction) {
    val destructive = HashSet<NodeRef>()
    val removed = HashSet<NodeRef>()
    val anchors = ArrayList<NodeRef>()
    val moveAnchors = ArrayList<NodeRef>()
    val moved = HashSet<NodeRef>()
    for (operation in transaction.operations) {
        val target: NodeRef? = when (operation) {
            is EditOperation.ReplaceScalar -> operation.replacement.target
            is EditOperation.RemoveMember -> operation.target
            is EditOperation.MoveMember -> operation.target
            is EditOperation.RenameMember -> operation.target
            is EditOperation.RemoveArrayElement -> operation.target
            is EditOperation.InsertMember, is EditOperation.InsertArrayElement -> null
        }
        if (target != null) {
            if (!destructive.add(target)) {
                throw EditFailureException(EditFailure.DuplicateTarget)
            }
        }
        when (operation) {
            is EditOperation.RemoveMember -> {
                removed.add(operation.target)
            }
            is EditOperation.RemoveArrayElement -> {
                removed.add(operation.target)
            }
            is EditOperation.InsertMember ->
                collectAnchor(operation.placement, anchors)
            is EditOperation.InsertArrayElement ->
                collectAnchor(operation.placement, anchors)
            is EditOperation.MoveMember -> {
                collectAnchor(operation.placement, anchors)
                collectAnchor(operation.placement, moveAnchors)
                moved.add(operation.target)
            }
            is EditOperation.ReplaceScalar, is EditOperation.RenameMember -> {}
        }
    }
    if (anchors.any { it in removed }) {
        throw EditFailureException(EditFailure.PlacementAnchorRemoved)
    }
    if (anchors.any { it in moved } || moveAnchors.any { it in destructive }) {
        throw EditFailureException(EditFailure.PlacementAnchorModified)
    }
}

private fun collectAnchor(placement: AssociationPlacement, anchors: ArrayList<NodeRef>) {
    if (placement is AssociationPlacement.Before) {
        anchors.add(placement.anchor)
    } else if (placement is AssociationPlacement.After) {
        anchors.add(placement.anchor)
    }
}

/** Checked fragment append (edit.rs:1080-1093). */
private fun appendFragment(output: ByteArray, fragment: ByteArray, max: Int): ByteArray {
    val newLength = output.size + fragment.size
    if (newLength > max) {
        throw EditFailureException(EditFailure.ResourceLimit("insert-fragment"))
    }
    val combined = ByteArray(newLength)
    System.arraycopy(output, 0, combined, 0, output.size)
    System.arraycopy(fragment, 0, combined, output.size, fragment.size)
    return combined
}

/** Patch metadata: operation.{index} -> frozen operation id@version
 * (edit.rs:1110-1133). */
private fun operationMetadata(transaction: EditTransaction): Map<String, String> {
    val metadata = LinkedHashMap<String, String>()
    for ((index, operation) in transaction.operations.withIndex()) {
        metadata["operation.$index"] = operationId(operation).toString()
    }
    return metadata
}

/** The frozen operation id@version (edit.rs:1116-1129). */
internal fun operationId(operation: EditOperation): FormatOperationId =
    FormatOperationId(
        when (operation) {
            is EditOperation.ReplaceScalar -> when (operation.replacement) {
                is ScalarReplacement.Semantic -> "json.edit.replace-scalar-semantic"
                is ScalarReplacement.Literal -> "json.edit.replace-scalar-literal"
            }
            is EditOperation.InsertMember -> "json.edit.insert-member"
            is EditOperation.RemoveMember -> "json.edit.remove-member"
            is EditOperation.MoveMember -> "json.edit.move-member"
            is EditOperation.RenameMember -> "json.edit.rename-member"
            is EditOperation.InsertArrayElement -> "json.edit.insert-array-element"
            is EditOperation.RemoveArrayElement -> "json.edit.remove-array-element"
        },
        1,
    )

/** Content-free operation summaries (edit.rs:1135-1229). */
private fun operationSummaries(transaction: EditTransaction): List<EditOperationSummary> =
    transaction.operations.map { operation ->
        val (id, targetRole, arguments) = when (operation) {
            is EditOperation.ReplaceScalar -> when (val replacement = operation.replacement) {
                is ScalarReplacement.Semantic -> Triple(
                    "json.edit.replace-scalar-semantic",
                    "json.scalar@1",
                    linkedMapOf(
                        "representation_policy" to jsonPolicyName(replacement.policy),
                        "value_kind" to valueKindName(replacement.value.kind),
                    ),
                )
                is ScalarReplacement.Literal -> Triple(
                    "json.edit.replace-scalar-literal",
                    "json.scalar@1",
                    linkedMapOf("literal_bytes" to replacement.literal.size.toString()),
                )
            }
            is EditOperation.InsertMember -> Triple(
                "json.edit.insert-member",
                "json.object@1",
                linkedMapOf(
                    "name_bytes" to operation.name.length.toString(),
                    "placement" to placementName(operation.placement),
                    "value_kind" to valueKindName(operation.value.kind),
                ),
            )
            is EditOperation.RemoveMember -> Triple(
                "json.edit.remove-member",
                "json.object-member@1",
                linkedMapOf(),
            )
            is EditOperation.MoveMember -> Triple(
                "json.edit.move-member",
                "json.object-member@1",
                linkedMapOf("placement" to placementName(operation.placement)),
            )
            is EditOperation.RenameMember -> Triple(
                "json.edit.rename-member",
                "json.object-member@1",
                linkedMapOf("name_bytes" to operation.name.length.toString()),
            )
            is EditOperation.InsertArrayElement -> Triple(
                "json.edit.insert-array-element",
                "json.array@1",
                linkedMapOf(
                    "placement" to placementName(operation.placement),
                    "value_kind" to valueKindName(operation.value.kind),
                ),
            )
            is EditOperation.RemoveArrayElement -> Triple(
                "json.edit.remove-array-element",
                "json.array-element@1",
                linkedMapOf(),
            )
        }
        val all = arguments.toMutableMap()
        all["target_role"] = targetRole
        EditOperationSummary.new(FormatOperationId(id, 1), all)
    }

private fun placementName(placement: AssociationPlacement): String =
    when (placement) {
        AssociationPlacement.Start -> "start"
        AssociationPlacement.End -> "end"
        is AssociationPlacement.Before -> "before"
        is AssociationPlacement.After -> "after"
    }

private fun jsonPolicyName(policy: RepresentationPolicy): String =
    when (policy) {
        RepresentationPolicy.ExactLiteral -> "exact-literal"
        RepresentationPolicy.PreserveCompatible -> "preserve-compatible"
        RepresentationPolicy.CanonicalForProfile -> "canonical-for-profile"
        RepresentationPolicy.PreserveElseCanonical -> "preserve-else-canonical"
    }

internal fun valueKindName(kind: consema.core.Kind): String =
    when (kind) {
        consema.core.Kind.Null -> "null"
        consema.core.Kind.Boolean -> "boolean"
        consema.core.Kind.Integer -> "integer"
        consema.core.Kind.Decimal -> "decimal"
        consema.core.Kind.BinaryFloat32 -> "binary-float32"
        consema.core.Kind.BinaryFloat64 -> "binary-float64"
        consema.core.Kind.String -> "string"
        consema.core.Kind.Bytes -> "bytes"
        consema.core.Kind.Date -> "date"
        consema.core.Kind.Time -> "time"
        consema.core.Kind.LocalDateTime -> "local-date-time"
        consema.core.Kind.OffsetDateTime -> "offset-date-time"
        consema.core.Kind.Sequence -> "sequence"
        consema.core.Kind.Object -> "object"
        consema.core.Kind.EntryMapping -> "entry-mapping"
    }

// ---------------------------------------------------------------------------
// Scalar literal preservation
// ---------------------------------------------------------------------------

/** Renders the replacement literal for one semantic scalar operation
 * (edit.rs:1346-1386). */
private fun semanticLiteral(
    authority: consema.document.DocumentAuthority,
    value: PortableValue,
    old: InternalValueKind,
    oldLiteral: ByteArray,
    profile: JsonProfile,
    policy: RepresentationPolicy,
    targetSpan: Span,
    diagnostics: ArrayList<Diagnostic>,
): ByteArray {
    if (policy == RepresentationPolicy.ExactLiteral) {
        throw EditFailureException(EditFailure.ExactLiteralRequiresLiteralOperation)
    }
    if (portableJsonKind(value, profile) == null) {
        throw EditFailureException(EditFailure.UnsupportedSemanticValue(value.kind))
    }
    val preserved = analyzeLexicalStyle(oldLiteral, old)?.let { style ->
        renderPreservingStyle(value, style)
    }
    return when (policy) {
        RepresentationPolicy.PreserveCompatible ->
            preserved ?: throw EditFailureException(EditFailure.RepresentationIncompatible)

        RepresentationPolicy.CanonicalForProfile -> canonicalLiteral(value, profile)

        RepresentationPolicy.PreserveElseCanonical -> {
            if (preserved != null) {
                preserved
            } else {
                diagnostics.add(
                    sourceDiagnostic(
                        authority,
                        "json.edit.representation-fallback@1",
                        DiagnosticCategory.Edit,
                        Severity.Warning,
                        targetSpan.startByte,
                        targetSpan.endByte,
                        diagnostics.size.toULong(),
                    ),
                )
                canonicalLiteral(value, profile)
            }
        }

        RepresentationPolicy.ExactLiteral -> error("ExactLiteral is rejected before matching")
    }
}

/** Maximum digits a preserved fixed-fraction rendering may produce
 * (edit.rs:1388-1389). */
private const val MAX_PRESERVED_FRACTION_DIGITS = 1_000_000

/** Bounded lexical style retained by PreserveCompatible edits
 * (edit.rs:1391-1440). */
private sealed class JsonScalarLexicalStyle {
    data object Null : JsonScalarLexicalStyle()

    data object Boolean : JsonScalarLexicalStyle()

    data class Integer(val style: IntegerLexicalStyle) : JsonScalarLexicalStyle()

    data class Decimal(val style: DecimalLexicalStyle) : JsonScalarLexicalStyle()

    data class NonFinite(val style: NonFiniteLexicalStyle) : JsonScalarLexicalStyle()

    data class String(val style: StringLexicalStyle) : JsonScalarLexicalStyle()
}

private data class IntegerLexicalStyle(
    val radix: IntegerRadix,
    val explicitPlus: Boolean,
)

private sealed class IntegerRadix {
    data object Decimal : IntegerRadix()

    data class Hex(val uppercasePrefix: Boolean, val uppercaseDigits: Boolean) : IntegerRadix()
}

private data class DecimalLexicalStyle(
    val fractionScale: Int?,
    val exponentMarker: Char?,
    val exponentPlus: Boolean,
    val leadingPlus: Boolean,
    val leadingPoint: Boolean,
)

private data class NonFiniteLexicalStyle(val explicitPlus: Boolean)

private data class StringLexicalStyle(
    val quote: Char,
    val escapes: Map<Char, String>,
)

/** Analyzes the lexical style of one complete scalar literal
 * (edit.rs:1442-1504). */
private fun analyzeLexicalStyle(literal: ByteArray, old: InternalValueKind): JsonScalarLexicalStyle? {
    return when (old) {
        is InternalValueKind.Null -> JsonScalarLexicalStyle.Null
        is InternalValueKind.Boolean -> JsonScalarLexicalStyle.Boolean
        is InternalValueKind.Integer -> {
            val text = literal.toString(Charsets.UTF_8)
            val unsigned = text.removePrefix("+").removePrefix("-")
            val radix = if (unsigned.startsWith("0x") || unsigned.startsWith("0X")) {
                val hex = unsigned.substring(2)
                IntegerRadix.Hex(
                    uppercasePrefix = unsigned.startsWith("0X"),
                    uppercaseDigits = hex.any { it.isUpperCase() },
                )
            } else {
                IntegerRadix.Decimal
            }
            JsonScalarLexicalStyle.Integer(
                IntegerLexicalStyle(radix, explicitPlus = text.startsWith("+")),
            )
        }
        is InternalValueKind.Decimal -> {
            val text = literal.toString(Charsets.UTF_8)
            val unsigned = text.removePrefix("+").removePrefix("-")
            val exponentIndex = unsigned.indexOfFirst { it == 'e' || it == 'E' }
            val mantissa = if (exponentIndex >= 0) unsigned.substring(0, exponentIndex) else unsigned
            val fractionScale = mantissa.indexOf('.').let { dot ->
                if (dot >= 0) mantissa.length - dot - 1 else null
            }
            val (exponentMarker, exponentPlus) = if (exponentIndex >= 0) {
                val plus = exponentIndex + 1 < unsigned.length && unsigned[exponentIndex + 1] == '+'
                unsigned[exponentIndex] to plus
            } else {
                null to false
            }
            JsonScalarLexicalStyle.Decimal(
                DecimalLexicalStyle(
                    fractionScale = fractionScale,
                    exponentMarker = exponentMarker,
                    exponentPlus = exponentPlus,
                    leadingPlus = text.startsWith("+"),
                    leadingPoint = mantissa.startsWith("."),
                ),
            )
        }
        is InternalValueKind.BinaryFloat64 -> {
            val text = literal.toString(Charsets.UTF_8)
            JsonScalarLexicalStyle.NonFinite(
                NonFiniteLexicalStyle(explicitPlus = text.startsWith("+")),
            )
        }
        is InternalValueKind.String -> analyzeStringStyle(literal)?.let {
            JsonScalarLexicalStyle.String(it)
        }
        is InternalValueKind.Array,
        is InternalValueKind.Object,
        is InternalValueKind.Unavailable -> null
    }
}

/** Analyzes the per-character escape choices of one string literal
 * (edit.rs:1506-1579). */
private fun analyzeStringStyle(literal: ByteArray): StringLexicalStyle? {
    val text = literal.toString(Charsets.UTF_8)
    if (text.isEmpty()) return null
    val quote = text.first()
    if ((quote != '\'' && quote != '"') || !text.endsWith(quote)) {
        return null
    }
    val escapes = HashMap<Char, String>()
    val end = text.length - 1
    var offset = 1
    while (offset < end) {
        val character = text[offset]
        if (character != '\\') {
            offset += 1
            continue
        }
        val escapeStart = offset
        offset += 1
        if (offset >= end) return null
        val escaped = text[offset]
        offset += 1
        val decoded: Char? = when (escaped.code) {
            0x22 -> '"'
            0x27 -> '\''
            0x5c -> '\\'
            0x2f -> '/'
            0x62 -> '\b'
            0x66 -> 0x0c.toChar()
            0x6e -> '\n'
            0x72 -> '\r'
            0x74 -> '\t'
            0x76 -> 0x0b.toChar()
            0x30 -> 0x00.toChar()
            0x78 -> {
                if (offset + 2 > end) return null
                val value = text.substring(offset, offset + 2).toIntOrNull(16) ?: return null
                offset += 2
                value.toChar()
            }
            0x75 -> {
                if (offset + 4 > end) return null
                val first = text.substring(offset, offset + 4).toIntOrNull(16) ?: return null
                offset += 4
                if (first in 0xd800..0xdbff) {
                    if (offset + 6 > end || text.substring(offset, offset + 2) != "\\u") {
                        return null
                    }
                    val second = text.substring(offset + 2, offset + 6).toIntOrNull(16) ?: return null
                    if (second !in 0xdc00..0xdfff) {
                        return null
                    }
                    offset += 6
                    (0x1_0000 + ((first - 0xd800) shl 10) + (second - 0xdc00)).toChar()
                } else if (first in 0xdc00..0xdfff) {
                    return null
                } else {
                    first.toChar()
                }
            }
            0x0d -> {
                if (offset < end && text[offset] == '\n') {
                    offset += 1
                }
                null
            }
            0x0a, 0x2028, 0x2029 -> null
            else -> escaped
        }
        if (decoded != null) {
            escapes[decoded] = text.substring(escapeStart, offset)
        }
    }
    return StringLexicalStyle(quote, escapes)
}

/** Renders the new value in the old lexical style (edit.rs:1581-1613). */
private fun renderPreservingStyle(
    value: PortableValue,
    style: JsonScalarLexicalStyle,
): ByteArray? = when (style) {
    is JsonScalarLexicalStyle.Null -> {
        if (value is PvNull) "null".toByteArray(Charsets.US_ASCII) else null
    }
    is JsonScalarLexicalStyle.Boolean -> {
        if (value is PvBoolean) {
            (if (value.value) "true" else "false").toByteArray(Charsets.US_ASCII)
        } else {
            null
        }
    }
    is JsonScalarLexicalStyle.Integer -> {
        if (value is PvInteger) renderIntegerStyle(value.value, style.style) else null
    }
    is JsonScalarLexicalStyle.Decimal -> {
        if (value is PvDecimal || value is PvInteger) renderDecimalStyle(value, style.style) else null
    }
    is JsonScalarLexicalStyle.NonFinite -> {
        if (value is PvBinaryFloat64) {
            renderNonFiniteStyle(value.bits, style.style)
        } else {
            null
        }
    }
    is JsonScalarLexicalStyle.String -> {
        if (value is PvString) {
            renderStringStyle(value.value, style.style).toByteArray(Charsets.UTF_8)
        } else {
            null
        }
    }
}

/** Renders one integer preserving radix and explicit sign (edit.rs:1615-1651). */
private fun renderIntegerStyle(value: BigInteger, style: IntegerLexicalStyle): ByteArray? {
    val output = StringBuilder()
    if (value.signum() < 0) {
        output.append('-')
    } else if (style.explicitPlus) {
        output.append('+')
    }
    when (style.radix) {
        is IntegerRadix.Decimal -> output.append(value.abs().toString())
        is IntegerRadix.Hex -> {
            output.append(if (style.radix.uppercasePrefix) "0X" else "0x")
            val digits = value.abs().toString(16)
            output.append(if (style.radix.uppercaseDigits) digits.uppercase() else digits)
        }
    }
    return output.toString().toByteArray(Charsets.US_ASCII)
}

/** Renders one decimal preserving fraction scale, exponent marker/sign, and
 * leading plus/point (edit.rs:1653-1702). */
private fun renderDecimalStyle(value: PortableValue, style: DecimalLexicalStyle): ByteArray? {
    val coefficient: BigInteger = when (value) {
        is PvDecimal -> value.coefficient
        is PvInteger -> value.value
        else -> return null
    }
    val exponent: BigInteger = when (value) {
        is PvDecimal -> value.exponent
        is PvInteger -> BigInteger.ZERO
        else -> return null
    }
    val output: String = if (style.exponentMarker != null) {
        val scale = style.fractionScale ?: 0
        var mantissa = if (style.fractionScale != null) {
            decimalFixedText(coefficient, scale)
        } else {
            coefficient.toString()
        }
        if (style.leadingPoint) {
            mantissa = removeLeadingZero(mantissa) ?: return null
        }
        val shifted = exponent.toLongExactOrNull()?.plus(scale.toLong()) ?: return null
        mantissa += style.exponentMarker
        if (shifted >= 0 && style.exponentPlus) {
            mantissa += '+'
        }
        mantissa += shifted.toString()
        mantissa
    } else {
        val scale = style.fractionScale ?: return null
        val shift: Int = when {
            exponent.signum() >= 0 -> {
                val e = exponent.toIntExactOrNull() ?: return null
                (e.toLong() + scale.toLong()).toIntExactOrNull() ?: return null
            }
            else -> {
                // The Rust checked_sub returns None when the scale cannot
                // absorb the exponent (edit.rs:1682-1687).
                val negative = exponent.negate().toIntExactOrNull() ?: return null
                (scale.toLong() - negative.toLong()).toIntExactOrNull()
                    ?.takeIf { it >= 0 } ?: return null
            }
        }
        if (shift > MAX_PRESERVED_FRACTION_DIGITS) {
            return null
        }
        val mantissa = coefficient.multiply(BigInteger.TEN.pow(shift))
        var output = decimalFixedText(mantissa, scale)
        if (style.leadingPoint) {
            output = removeLeadingZero(output) ?: return null
        }
        output
    }
    val finalOutput = if (style.leadingPlus && !output.startsWith("-")) {
        "+$output"
    } else {
        output
    }
    return finalOutput.toByteArray(Charsets.US_ASCII)
}

private fun BigInteger.toLongExactOrNull(): Long? =
    try {
        longValueExact()
    } catch (e: ArithmeticException) {
        null
    }

private fun Long.toIntExactOrNull(): Int? =
    try {
        java.lang.Math.toIntExact(this)
    } catch (e: ArithmeticException) {
        null
    }

private fun BigInteger.toIntExactOrNull(): Int? =
    try {
        intValueExact()
    } catch (e: ArithmeticException) {
        null
    }

/** Removes a leading zero before the decimal point (edit.rs:1704-1709). */
private fun removeLeadingZero(text: String): String? {
    val zero = if (text.startsWith("-0.")) 1 else 0
    if (text.length < zero + 2 || text.substring(zero, zero + 2) != "0.") {
        return null
    }
    return text.removeRange(zero, zero + 1)
}

/** Renders one non-finite value preserving the explicit sign (edit.rs:1711-1725). */
private fun renderNonFiniteStyle(bits: Long, style: NonFiniteLexicalStyle): ByteArray? {
    val text: String = when (bits) {
        java.lang.Double.doubleToRawLongBits(Double.POSITIVE_INFINITY) ->
            if (style.explicitPlus) "+Infinity" else "Infinity"
        java.lang.Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY) -> "-Infinity"
        java.lang.Double.doubleToRawLongBits(Double.NaN) ->
            if (style.explicitPlus) "+NaN" else "NaN"
        java.lang.Double.doubleToRawLongBits(-Double.NaN) -> "-NaN"
        else -> return null
    }
    return text.toByteArray(Charsets.US_ASCII)
}

/** Fixed-point rendering of one integer at a scale (edit.rs:1727-1739). */
private fun decimalFixedText(mantissa: BigInteger, scale: Int): String {
    val text = mantissa.toString()
    val (sign, digits) = if (text.startsWith("-")) {
        "-" to text.substring(1)
    } else {
        "" to text
    }
    return if (digits.length <= scale) {
        "$sign" + "0." + "0".repeat(scale - digits.length) + digits
    } else {
        val split = digits.length - scale
        "$sign" + digits.substring(0, split) + "." + digits.substring(split)
    }
}

/** Renders one string preserving quote and per-character escapes
 * (edit.rs:1741-1753). */
private fun renderStringStyle(value: String, style: StringLexicalStyle): String {
    val output = StringBuilder(value.length + 2)
    output.append(style.quote)
    for (character in value) {
        val escape = style.escapes[character]
        if (escape != null) {
            output.append(escape)
        } else {
            pushJsonStringChar(output, character, style.quote, canonicalJson5 = false)
        }
    }
    output.append(style.quote)
    return output.toString()
}

/** The portable kinds a JSON scalar edit accepts (edit.rs:1755-1767). */
private fun portableJsonKind(value: PortableValue, profile: JsonProfile): JsonValueKind? =
    when (value) {
        is PvNull -> JsonValueKind.Null
        is PvBoolean -> JsonValueKind.Boolean
        is PvInteger -> JsonValueKind.Integer
        is PvDecimal -> JsonValueKind.Decimal
        is PvBinaryFloat64 -> {
            if (profile.isJson5()) JsonValueKind.BinaryFloat64 else null
        }
        is PvString -> JsonValueKind.String
        else -> null
    }

/** The deterministic profile-canonical literal (edit.rs:1769-1795). */
private fun canonicalLiteral(value: PortableValue, profile: JsonProfile): ByteArray {
    val text = when (value) {
        is PvNull -> "null"
        is PvBoolean -> value.value.toString()
        is PvInteger -> value.value.toString()
        is PvDecimal -> "${value.coefficient}e${value.exponent}"
        is PvBinaryFloat64 -> {
            if (!profile.isJson5()) {
                throw EditFailureException(EditFailure.UnsupportedSemanticValue(value.kind))
            }
            return renderNonFiniteStyle(value.bits, NonFiniteLexicalStyle(explicitPlus = false))
                ?: throw EditFailureException(EditFailure.UnsupportedSemanticValue(value.kind))
        }
        is PvString -> encodeJsonString(value.value, json5 = profile.isJson5())
        else -> throw EditFailureException(EditFailure.UnsupportedSemanticValue(value.kind))
    }
    return text.toByteArray(Charsets.US_ASCII)
}

/** Canonical double-quoted string encoding (edit.rs:1797-1805). */
private fun encodeJsonString(value: String, json5: Boolean): String {
    val output = StringBuilder(value.length + 2)
    output.append('"')
    for (character in value) {
        pushJsonStringChar(output, character, '"', json5)
    }
    output.append('"')
    return output.toString()
}

/** One canonical string character (edit.rs:1807-1829). */
private fun pushJsonStringChar(
    output: StringBuilder,
    character: Char,
    quote: Char,
    canonicalJson5: Boolean,
) {
    val code = character.code
    when {
        code == quote.code -> {
            output.append('\\')
            output.append(character)
        }
        code == 0x5c -> output.append("\\\\")
        code == 0x08 -> output.append("\\b")
        code == 0x0c -> output.append("\\f")
        code == 0x0a -> output.append("\\n")
        code == 0x0d -> output.append("\\r")
        code == 0x09 -> output.append("\\t")
        code in 0x00..0x1f -> output.append("\\u%04X".format(code))
        (code == 0x2028 || code == 0x2029) && canonicalJson5 ->
            output.append("\\u%04X".format(code))

        else -> output.append(character)
    }
}

/** Validates one exact literal candidate for the profile (edit.rs:1831-1862). */
private fun validateLiteral(
    literal: ByteArray,
    profile: JsonProfile,
    limits: consema.document.ParseLimits,
): JsonValueKind {
    if (literal.isEmpty() || !isValidUtf8(literal)) {
        throw EditFailureException(EditFailure.InvalidLiteral)
    }
    val document = try {
        parse(literal, profile, limits)
    } catch (e: JsonFormationException) {
        throw EditFailureException(EditFailure.InvalidLiteral)
    }
    val root = document.root()
    val rootKind = root.kind()
    if (document.formationStatus() != FormationStatus.Complete ||
        root.span().startByte != 0 ||
        root.span().endByte != literal.size ||
        rootKind !is SemanticAvailability.Available ||
        rootKind.value !in setOf(
            JsonValueKind.Null,
            JsonValueKind.Boolean,
            JsonValueKind.Integer,
            JsonValueKind.Decimal,
            JsonValueKind.BinaryFloat64,
            JsonValueKind.String,
        )
    ) {
        throw EditFailureException(EditFailure.InvalidLiteral)
    }
    return rootKind.value
}

/** Strict UTF-8 validation of the candidate literal. */
private fun isValidUtf8(bytes: ByteArray): Boolean {
    var i = 0
    while (i < bytes.size) {
        val first = bytes[i].toInt() and 0xff
        when {
            first < 0x80 -> i += 1
            first in 0xc2..0xdf -> {
                if (!isContinuation(bytes, i + 1)) return false
                i += 2
            }
            first == 0xe0 -> {
                if (!isContinuation(bytes, i + 1) || (bytes[i + 1].toInt() and 0xff) !in 0xa0..0xbf) {
                    return false
                }
                if (!isContinuation(bytes, i + 2)) return false
                i += 3
            }
            first in 0xe1..0xec || first in 0xee..0xef -> {
                if (!isContinuation(bytes, i + 1) || !isContinuation(bytes, i + 2)) return false
                i += 3
            }
            first == 0xed -> {
                if (!isContinuation(bytes, i + 1) || (bytes[i + 1].toInt() and 0xff) !in 0x80..0x9f) {
                    return false
                }
                if (!isContinuation(bytes, i + 2)) return false
                i += 3
            }
            first == 0xf0 -> {
                if (!isContinuation(bytes, i + 1) || (bytes[i + 1].toInt() and 0xff) !in 0x90..0xbf) {
                    return false
                }
                if (!isContinuation(bytes, i + 2) || !isContinuation(bytes, i + 3)) return false
                i += 4
            }
            first in 0xf1..0xf3 -> {
                if (!isContinuation(bytes, i + 1) || !isContinuation(bytes, i + 2) ||
                    !isContinuation(bytes, i + 3)
                ) {
                    return false
                }
                i += 4
            }
            first == 0xf4 -> {
                if (!isContinuation(bytes, i + 1) || (bytes[i + 1].toInt() and 0xff) !in 0x80..0x8f) {
                    return false
                }
                if (!isContinuation(bytes, i + 2) || !isContinuation(bytes, i + 3)) return false
                i += 4
            }
            else -> return false
        }
    }
    return true
}

private fun isContinuation(bytes: ByteArray, index: Int): Boolean =
    index < bytes.size && (bytes[index].toInt() and 0xc0) == 0x80
