// Structural edit operations: one immutable transaction, atomic commit,
// dry-run plan, untouched-byte proof, and SourcePatch derivation.
//
// Data authority:
//   - RFC 0004 §11-§16 (docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:271-384): snapshot-bound operations; inserted
//     literals use the target profile's canonical fragment; removal owns
//     only the record and its unambiguously attached delimiter/newline;
//     comments are not moved or deleted without explicit ownership; the
//     conflict algebra; the dry-run plan; the untouched-byte proof; the
//     derived SourcePatch.
//   - RFC 0009 §12 (docs/rfcs/0009-ini-family-profiles-v1.md:437-472):
//     semantic replacement preserves a compatible quote/multiline
//     representation or records a canonical fallback; literal replacement
//     must form exactly one value under the selected profile and cannot
//     consume surrounding trivia; rename validates portable character rules,
//     Windows ASCII case equivalence, or Python optionxform collisions
//     before any patch exists; removing a section removes its owned entries
//     atomically; targets and placement anchors are snapshot-bound; the
//     multi-operation conflict checks; success includes the new Document,
//     ChangeSet, UntouchedByteProof, and replayable SourcePatch.
//   - conformance/vectors/ini-v1.json (edit.all-eight-operations,
//     edit.dry-run-patch-proof-and-atomic-failure) pins the golden output
//     bytes and the wrong-snapshot code; crates/consema-ini/src/edit.rs is
//     the byte-arbitration authority (commit edit.rs:305-553, dry-run
//     edit.rs:556-570, preparation edit.rs:572-861, dependencies edit.rs:
//     863-920, names/collisions edit.rs:950-1069, canonical entry text
//     edit.rs:1101-1167, semantic value styles edit.rs:1228-1430,
//     ownership edit.rs:1445-1475, failure codes edit.rs:1754-1779).
//   - Kotlin document owns SourcePatch (kotlin/.../document/Patch.kt:147-296)
//     and UntouchedByteProof (kotlin/.../document/UntouchedProof.kt). The
//     ChangeSet is an L4 milestone in Kotlin (document/Patch.kt:31-33); this
//     L2 commit carries the ordered edit diagnostics instead.
//
// Kotlin-idiomatic design: failures are a sealed hierarchy whose [name] is
// the exact Rust variant spelling and whose [diagnosticCode] is the frozen
// registered code (edit.rs:1754-1779); commit/dry-run throw the typed
// [EditFailureException] so callers match exhaustively on the failure class.

package consema.ini

import consema.document.AssociationPlacement
import consema.document.EditOperationSummary
import consema.document.EditPlan
import consema.document.EditPlanException
import consema.document.EditPlanSourceId
import consema.document.FormatOperationId
import consema.document.FormationStatus
import consema.document.MaterializationException
import consema.document.MaterializationFailureKind
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.SnapshotIdentity
import consema.document.SourceLimits
import consema.document.SourcePatch
import consema.document.SourcePatchLimits
import consema.document.SourceReplacement
import consema.document.Span
import consema.document.UntouchedByteProof
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/** Explicit semantic value representation policy (edit.rs:15-26). */
enum class RepresentationPolicy {
    /** Caller must use an exact literal operation instead. */
    ExactLiteral,

    /** Retain the target's compatible quote or multiline representation. */
    PreserveCompatible,

    /** Use the selected INI profile's frozen canonical value
     * representation. */
    CanonicalForProfile,

    /** Preserve when compatible, otherwise use canonical representation
     * and report fallback. */
    PreserveElseCanonical,
}

/** One INI value replacement bound to a transaction base snapshot
 * (edit.rs:28-47). */
sealed class ValueReplacement {
    /** Exact INI entry target. */
    abstract val target: NodeRef

    /** Replaces the stored string under an explicit representation policy. */
    data class Semantic(
        override val target: NodeRef,
        /** New stored string value. */
        val value: String,
        /** Representation contract. */
        val policy: RepresentationPolicy,
    ) : ValueReplacement()

    /** Replaces the exact profile-specific value representation bytes. */
    data class Literal(
        override val target: NodeRef,
        /** Raw bytes in the base document's selected source encoding. */
        val literal: ByteArray,
    ) : ValueReplacement()
}

/** One typed INI edit operation bound to an immutable base snapshot
 * (edit.rs:57-106). */
sealed class EditOperation {
    /** Replaces one exact entry's value. */
    data class ReplaceValue(val replacement: ValueReplacement) : EditOperation()

    /** Inserts one new section occurrence. */
    data class InsertSection(
        /** Exact INI document target. */
        val document: NodeRef,
        /** Decoded section name. */
        val name: String,
        /** Placement among section occurrences. */
        val placement: AssociationPlacement,
    ) : EditOperation()

    /** Removes one exact section and all entries owned by that occurrence. */
    data class RemoveSection(
        /** Exact ordinary or default-section target. */
        val target: NodeRef,
    ) : EditOperation()

    /** Replaces one exact section name. */
    data class RenameSection(
        /** Exact ordinary or default-section target. */
        val target: NodeRef,
        /** New decoded section name. */
        val name: String,
    ) : EditOperation()

    /** Inserts one new entry into an exact section occurrence. */
    data class InsertEntry(
        /** Exact ordinary or default-section container. */
        val section: NodeRef,
        /** Decoded entry key. */
        val key: String,
        /** Stored string value. */
        val value: String,
        /** Placement among direct entry occurrences. */
        val placement: AssociationPlacement,
    ) : EditOperation()

    /** Removes one exact entry occurrence. */
    data class RemoveEntry(
        /** Exact INI entry target. */
        val target: NodeRef,
    ) : EditOperation()

    /** Replaces one exact entry key. */
    data class RenameEntry(
        /** Exact INI entry target. */
        val target: NodeRef,
        /** New decoded key. */
        val key: String,
    ) : EditOperation()
}

/** Immutable edit transaction; every operation resolves against one base
 * snapshot (edit.rs:108-127). */
class EditTransaction internal constructor(
    /** Base snapshot identity. */
    val baseSnapshot: SnapshotIdentity,
    /** Ordered declared operations. */
    val operations: List<EditOperation>,
)

/** Builder for one immutable edit transaction (edit.rs:129-243). */
class EditTransactionBuilder internal constructor(private val base: SnapshotIdentity) {
    private val operations = ArrayList<EditOperation>()

    companion object {
        /** Binds a new transaction to one immutable INI document
         * (edit.rs:137-144). */
        fun new(document: IniDocument): EditTransactionBuilder =
            EditTransactionBuilder(document.snapshotIdentity)
    }

    /** Adds one semantic stored-value replacement (edit.rs:146-160). */
    fun semanticValue(
        target: NodeRef,
        value: String,
        policy: RepresentationPolicy,
    ): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceValue(ValueReplacement.Semantic(target, value, policy)))
        return this
    }

    /** Adds one exact raw value-representation replacement (edit.rs:162-170). */
    fun literalValue(target: NodeRef, literal: ByteArray): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceValue(ValueReplacement.Literal(target, literal)))
        return this
    }

    /** Adds one canonical section insertion (edit.rs:172-181). */
    fun insertSection(
        document: NodeRef,
        name: String,
        placement: AssociationPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertSection(document, name, placement))
        return this
    }

    /** Adds one exact section removal, including that occurrence's owned
     * entries (edit.rs:183-192). */
    fun removeSection(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveSection(target))
        return this
    }

    /** Adds one exact section-name replacement (edit.rs:194-201). */
    fun renameSection(target: NodeRef, name: String): EditTransactionBuilder {
        operations.add(EditOperation.RenameSection(target, name))
        return this
    }

    /** Adds one canonical entry insertion (edit.rs:203-218). */
    fun insertEntry(
        section: NodeRef,
        key: String,
        value: String,
        placement: AssociationPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertEntry(section, key, value, placement))
        return this
    }

    /** Adds one exact entry removal (edit.rs:220-224). */
    fun removeEntry(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveEntry(target))
        return this
    }

    /** Adds one exact entry-key replacement (edit.rs:226-233). */
    fun renameEntry(target: NodeRef, key: String): EditTransactionBuilder {
        operations.add(EditOperation.RenameEntry(target, key))
        return this
    }

    /** Completes the immutable request; validation happens atomically at
     * commit or dry-run (edit.rs:235-242). */
    fun build(): EditTransaction = EditTransaction(base, operations.toList())
}

/**
 * Atomic edit success (edit.rs:245-256). The ChangeSet is an L4 milestone in
 * Kotlin; this L2 commit carries the ordered edit diagnostics instead. For
 * base documents whose selected encoding is a Windows code page, the
 * document-contract artifacts are unavailable until the source-v2 extension
 * (kotlin/.../document/Encoding.kt:18-25) and are null.
 */
class EditCommit(
    /** New immutable document. */
    val document: IniDocument,
    /** Portable exact raw-byte application fact; null for code-page
     * documents (document source-v2 gap). */
    val sourcePatch: SourcePatch?,
    /** Verifiable evidence for every byte outside the replacement set; null
     * for code-page documents. */
    val untouchedProof: UntouchedByteProof?,
    /** Ordered edit diagnostics (canonical-fallback events). */
    val diagnostics: List<Diagnostic>,
)

/** Stable edit validation or commit failure (edit.rs:258-303). The [name]
 * is the exact Rust variant spelling; [diagnosticCode] is the frozen
 * registered code (edit.rs:1754-1779). */
sealed class EditFailure(val name: String) {
    /** Edits are forbidden on a recovered document. */
    data object RecoveredDocument : EditFailure("RecoveredDocument")

    /** Transaction or target belongs to another snapshot. */
    data object WrongSnapshot : EditFailure("WrongSnapshot")

    /** Target is not an INI entry (or section/document). */
    data object WrongRole : EditFailure("WrongRole")

    /** More than one operation names the same exact target. */
    data object DuplicateTarget : EditFailure("DuplicateTarget")

    /** Prepared value ownership intervals overlap. */
    data object OverlappingOwnership : EditFailure("OverlappingOwnership")

    /** One operation removes a section while another edits its owned
     * entry. */
    data object AncestorDescendantConflict : EditFailure("AncestorDescendantConflict")

    /** An insertion anchor is removed by the same transaction. */
    data object PlacementAnchorRemoved : EditFailure("PlacementAnchorRemoved")

    /** A target or placement anchor does not exist in its declared
     * container. */
    data object TargetNotFound : EditFailure("TargetNotFound")

    /** A valid entry anchor belongs to another section container. */
    data object InvalidPlacement : EditFailure("InvalidPlacement")

    /** A section name is invalid under the selected profile. */
    data object InvalidName : EditFailure("InvalidName")

    /** A strict profile would become ambiguous after insertion or rename. */
    data object NameCollision : EditFailure("NameCollision")

    /** An entry key is invalid under the selected profile. */
    data object InvalidKey : EditFailure("InvalidKey")

    /** A strict profile would contain an exact duplicate key. */
    data object DuplicateKey : EditFailure("DuplicateKey")

    /** Python `optionxform` makes two distinctly spelled keys equivalent. */
    data object KeyCollision : EditFailure("KeyCollision")

    /** `PreserveCompatible` cannot retain the target representation. */
    data object RepresentationIncompatible : EditFailure("RepresentationIncompatible")

    /** `ExactLiteral` was requested without literal bytes. */
    data object ExactLiteralRequiresLiteralOperation :
        EditFailure("ExactLiteralRequiresLiteralOperation")

    /** The semantic string cannot be represented by the selected profile. */
    data object UnrepresentableValue : EditFailure("UnrepresentableValue")

    /** The replacement cannot be encoded exactly in the source encoding. */
    data object EncodingUnrepresentable : EditFailure("EncodingUnrepresentable")

    /** Literal bytes do not form exactly one value at the target. */
    data object InvalidLiteral : EditFailure("InvalidLiteral")

    /** A configured edit or output bound was exceeded. */
    data class ResourceLimit(val name: String) : EditFailure("ResourceLimit")

    /** Replacement bytes could not form one complete document under the
     * original contract. */
    data object NewDocumentFormationFailed : EditFailure("NewDocumentFormationFailed")

    /** The frozen registered diagnostic code (edit.rs:1754-1779). */
    fun diagnosticCode(): String =
        when (this) {
            is RecoveredDocument -> "core.edit.incomplete-target@1"
            is WrongSnapshot -> "core.edit.wrong-snapshot@1"
            is WrongRole -> "core.edit.wrong-role@1"
            is DuplicateTarget, is OverlappingOwnership, is AncestorDescendantConflict,
            is PlacementAnchorRemoved -> "core.edit.conflicting-edits@1"
            is TargetNotFound -> "core.edit.target-not-found@1"
            is InvalidPlacement -> "ini.edit.invalid-placement@1"
            is InvalidName, is InvalidKey -> "ini.edit.invalid-name@1"
            is NameCollision, is DuplicateKey -> "core.edit.duplicate-key@1"
            is KeyCollision -> "ini.edit.case-collision@1"
            is RepresentationIncompatible, is EncodingUnrepresentable ->
                "core.edit.representation-incompatible@1"
            is ExactLiteralRequiresLiteralOperation ->
                "core.edit.exact-literal-requires-literal@1"
            is UnrepresentableValue -> "core.edit.unsupported-value@1"
            is InvalidLiteral -> "core.edit.invalid-literal@1"
            is ResourceLimit -> "core.edit.resource-limit@1"
            is NewDocumentFormationFailed -> "core.edit.formation-failed@1"
        }
}

/** The typed edit failure thrown by [IniDocument.commit] and
 * [IniDocument.dryRun]. */
class EditFailureException(val failure: EditFailure) :
    Exception("ini edit: ${failure.name}")

/** One prepared byte edit with its planned mappings (edit.rs:1782-1811). */
private data class PreparedEdit(
    val oldSpan: Span,
    val replacement: ByteArray,
    val mergeableDeletion: Boolean,
    val mappings: List<PlannedMapping>,
)

/** The value replacement's mapping expectation (edit.rs:1794-1811). */
private sealed class MappingPlan {
    /** The new entry with [expectedKey] must own exactly the new span. */
    data class ReplacedValue(val expectedKey: String, val literal: Boolean) : MappingPlan()

    /** The new section with [expectedName] must own exactly the new span. */
    data class ReplacedSection(val expectedName: String) : MappingPlan()

    /** The new entry with [expectedKey] must own exactly the new span. */
    data class ReplacedEntry(val expectedKey: String) : MappingPlan()

    /** An inserted entry with the expected key/value must end at the new
     * span. */
    data class SectionAfterEntryInsertion(
        val expectedKey: String,
        val expectedValue: String,
    ) : MappingPlan()

    /** Deleted occurrence; no structural verification. */
    data object Deleted : MappingPlan()
}

/** One planned mapping of a prepared edit (edit.rs:1789-1792). */
private data class PlannedMapping(val old: NodeRef, val plan: MappingPlan)

/**
 * Atomically commits all declared operations (edit.rs:305-553). On failure
 * the document remains unchanged and none of the successful artifacts
 * exist.
 */
fun IniDocument.commit(transaction: EditTransaction): EditCommit {
    if (formationStatus != FormationStatus.Complete) {
        throw EditFailureException(EditFailure.RecoveredDocument)
    }
    if (transaction.baseSnapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (transaction.operations.size > parseLimits.common.maxNodeCount) {
        throw EditFailureException(EditFailure.ResourceLimit("edit-operations"))
    }
    validateDependencies(transaction)
    val targets = HashSet<NodeRef>()
    val diagnostics = ArrayList<Diagnostic>()
    val prepared = ArrayList<PreparedEdit>()
    for (operation in transaction.operations) {
        destructiveTarget(operation)?.let { target ->
            if (!targets.add(target)) {
                throw EditFailureException(EditFailure.DuplicateTarget)
            }
        }
        prepared.addAll(prepareOperation(operation, diagnostics))
    }
    prepared.sortWith(
        compareBy<PreparedEdit> { it.oldSpan.startByte }.thenBy { it.oldSpan.endByte },
    )
    val coalesced = coalesceAdjacentDeletions(prepared)
    for (pair in coalesced.windowed(2)) {
        val left = pair[0]
        val right = pair[1]
        if (left.oldSpan == right.oldSpan) {
            throw EditFailureException(EditFailure.OverlappingOwnership)
        }
        if (left.oldSpan.endByte > right.oldSpan.startByte) {
            throw EditFailureException(EditFailure.AncestorDescendantConflict)
        }
    }
    val literalOnly = transaction.operations.isNotEmpty() &&
        transaction.operations.all { operation ->
            operation is EditOperation.ReplaceValue &&
                operation.replacement is ValueReplacement.Literal
        }

    var targetLen = sourceSnapshot.len
    for (edit in coalesced) {
        targetLen = targetLen - edit.oldSpan.len + edit.replacement.size
        if (targetLen > parseLimits.common.maxSourceBytes) {
            throw EditFailureException(EditFailure.ResourceLimit("target-bytes"))
        }
    }
    val rendered = ByteArray(targetLen)
    var cursor = 0
    var out = 0
    for (edit in coalesced) {
        val keep = edit.oldSpan.startByte - cursor
        System.arraycopy(sourceSnapshot.rawBytes(), cursor, rendered, out, keep)
        out += keep
        System.arraycopy(edit.replacement, 0, rendered, out, edit.replacement.size)
        out += edit.replacement.size
        cursor = edit.oldSpan.endByte
    }
    System.arraycopy(sourceSnapshot.rawBytes(), cursor, rendered, out, sourceSnapshot.len - cursor)

    val newDocument = try {
        parse(rendered, profile, originalEncodingSelection(), parseLimits)
    } catch (e: IniFormationException) {
        throw EditFailureException(
            if (literalOnly) EditFailure.InvalidLiteral else EditFailure.NewDocumentFormationFailed,
        )
    }
    if (newDocument.formationStatus() != FormationStatus.Complete) {
        throw EditFailureException(
            if (literalOnly) EditFailure.InvalidLiteral else EditFailure.NewDocumentFormationFailed,
        )
    }

    // Structural verification of every replacement (edit.rs:444-516): a
    // literal replacement must form exactly one value at the target, and a
    // semantic replacement must reproduce the promised record.
    var delta = 0
    val sourceEdits = ArrayList<SourceEditFacts>()
    for (edit in coalesced) {
        val newStart = edit.oldSpan.startByte + delta
        val newEnd = newStart + edit.replacement.size
        val newSpan = newDocument.authority.span(newStart, newEnd)
        sourceEdits.add(SourceEditFacts(edit, newSpan))
        delta = delta + (edit.replacement.size - edit.oldSpan.len)
    }
    for ((index, facts) in sourceEdits.withIndex()) {
        for (mapping in coalesced[index].mappings) {
            verifyMapping(newDocument, mapping, facts.newSpan)
        }
    }

    val replacements = sourceEdits.map { facts ->
        val edit = facts.edit
        SourceReplacement.new(
            edit.oldSpan.startByte,
            edit.oldSpan.endByte,
            sourceSnapshot.rawBytes().copyOfRange(edit.oldSpan.startByte, edit.oldSpan.endByte),
            edit.replacement,
        )
    }
    val sourcePatch: SourcePatch?
    val untouchedProof: UntouchedByteProof?
    if (sourceSnapshot.v1Snapshot != null && newDocument.sourceSnapshot.v1Snapshot != null) {
        sourcePatch = try {
            SourcePatch.create(
                sourceSnapshot.v1Snapshot,
                replacements,
                operationMetadata(transaction),
                sourcePatchLimits(replacements.size),
            )
        } catch (e: consema.document.SourcePatchException) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
        untouchedProof = try {
            UntouchedByteProof.create(
                sourceSnapshot.v1Snapshot,
                newDocument.sourceSnapshot.v1Snapshot,
                replacements,
            )
        } catch (e: consema.document.UntouchedByteProofException) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
    } else {
        sourcePatch = null
        untouchedProof = null
    }
    return EditCommit(newDocument, sourcePatch, untouchedProof, diagnostics)
}

/** One prepared edit plus its target span (edit.rs:425-443). */
private data class SourceEditFacts(val edit: PreparedEdit, val newSpan: Span)

/**
 * Fully validates and plans an edit without returning a new Document
 * (edit.rs:556-570; RFC 0004 §14). Dry-run and commit produce the same
 * replacement set and target digest (RFC 0004 §20). Code-page documents
 * cannot produce the transferable plan until the document source-v2
 * extension lands (kotlin/.../document/Encoding.kt:18-25).
 */
fun IniDocument.dryRun(
    transaction: EditTransaction,
    sourceId: EditPlanSourceId,
): EditPlan {
    val commit = commit(transaction)
    val patch = commit.sourcePatch
        ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    return try {
        EditPlan.new(
            sourceId,
            profileId(),
            operationSummaries(transaction),
            patch,
            commit.diagnostics,
        )
    } catch (e: EditPlanException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
}

// ---------------------------------------------------------------------------
// Operation preparation (edit.rs:572-861)
// ---------------------------------------------------------------------------

private fun IniDocument.prepareOperation(
    operation: EditOperation,
    diagnostics: ArrayList<Diagnostic>,
): List<PreparedEdit> = when (operation) {
    is EditOperation.ReplaceValue -> listOf(prepareValue(operation.replacement, diagnostics))
    is EditOperation.InsertSection ->
        listOf(prepareInsertSection(operation.document, operation.name, operation.placement))
    is EditOperation.RemoveSection -> prepareRemoveSection(operation.target)
    is EditOperation.RenameSection ->
        listOf(prepareRenameSection(operation.target, operation.name))
    is EditOperation.InsertEntry ->
        listOf(prepareInsertEntry(operation.section, operation.key, operation.value, operation.placement))
    is EditOperation.RemoveEntry -> prepareRemoveEntry(operation.target)
    is EditOperation.RenameEntry ->
        listOf(prepareRenameEntry(operation.target, operation.key))
}

private fun IniDocument.prepareValue(
    operation: ValueReplacement,
    diagnostics: ArrayList<Diagnostic>,
): PreparedEdit {
    val target = operation.target
    val entry = resolveEntry(target)
    val oldSpan = valueOwnership(entry)
    val (replacement, literal) = when (operation) {
        is ValueReplacement.Literal -> {
            if (operation.literal.size > parseLimits.common.maxSourceBytes) {
                throw EditFailureException(EditFailure.ResourceLimit("replacement-bytes"))
            }
            operation.literal to true
        }
        is ValueReplacement.Semantic -> {
            semanticValue(entry, operation.value, operation.policy, diagnostics) to false
        }
    }
    return PreparedEdit(
        oldSpan,
        replacement,
        mergeableDeletion = false,
        mappings = listOf(
            PlannedMapping(
                target,
                MappingPlan.ReplacedValue(entry.key, literal),
            ),
        ),
    )
}

private fun IniDocument.prepareInsertSection(
    document: NodeRef,
    name: String,
    placement: AssociationPlacement,
): PreparedEdit {
    resolveDocument(document)
    validateSectionName(name)
    validateSectionCollision(name, except = null)
    val position = when (placement) {
        AssociationPlacement.Start -> sectionsList.firstOrNull()?.let { sectionLineStart(it) }
            ?: throw EditFailureException(EditFailure.TargetNotFound)
        AssociationPlacement.End -> sourceSnapshot.len
        is AssociationPlacement.Before -> sectionLineStart(resolveSection(placement.anchor))
        is AssociationPlacement.After -> {
            resolveSection(placement.anchor)
            val ordinal = sectionsList.indexOfFirst { it.nodeRef == placement.anchor }
            sectionsList.getOrNull(ordinal + 1)?.let { sectionLineStart(it) } ?: sourceSnapshot.len
        }
    }
    var text = ""
    if (position == sourceSnapshot.len && !endsWithNewline(sourceSnapshot.decodedText())) {
        text += profileNewline()
    }
    text += "[$name]" + profileNewline()
    return PreparedEdit(
        authority.span(position, position),
        encodeValue(text),
        mergeableDeletion = false,
        mappings = listOf(PlannedMapping(document, MappingPlan.Deleted)),
    )
}

private fun IniDocument.prepareRemoveSection(target: NodeRef): List<PreparedEdit> {
    val section = resolveSection(target)
    val edits = ArrayList<PreparedEdit>()
    for ((index, span) in logicalPhysicalSpans(section.logicalLine).withIndex()) {
        edits.add(deletionEdit(span, if (index == 0) target else null))
    }
    for (entry in entriesList.filter { it.section == target }) {
        for ((index, span) in logicalPhysicalSpans(entry.logicalLine).withIndex()) {
            edits.add(deletionEdit(span, if (index == 0) entry.nodeRef else null))
        }
    }
    return edits
}

private fun IniDocument.prepareRenameSection(target: NodeRef, name: String): PreparedEdit {
    val section = resolveSection(target)
    validateSectionName(name)
    validateSectionCollision(name, except = target)
    return PreparedEdit(
        section.nameSpan,
        encodeValue(name),
        mergeableDeletion = false,
        mappings = listOf(PlannedMapping(target, MappingPlan.ReplacedSection(name))),
    )
}

private fun IniDocument.prepareInsertEntry(
    section: NodeRef,
    key: String,
    value: String,
    placement: AssociationPlacement,
): PreparedEdit {
    resolveSection(section)
    validateEntryKey(key)
    validateEntryCollision(section, key, except = null)
    validateSemanticValue(profile, value)
    val entries = entriesList.filter { it.section == section }
    val position = when (placement) {
        AssociationPlacement.Start -> entries.firstOrNull()
            ?.let { entryLineStart(it) } ?: sectionContentEnd(section)
        AssociationPlacement.End -> sectionContentEnd(section)
        is AssociationPlacement.Before -> {
            val entry = resolveEntryInSection(placement.anchor, section, entries)
            entryLineStart(entry)
        }
        is AssociationPlacement.After -> {
            val entry = resolveEntryInSection(placement.anchor, section, entries)
            entryLineEnd(entry)
        }
    }
    var text = ""
    if (position == sourceSnapshot.len && !endsWithNewline(sourceSnapshot.decodedText())) {
        text += profileNewline()
    }
    text += try {
        canonicalEntryText(profile, key, value, parseLimits.common.maxSourceBytes)
    } catch (e: MaterializationException) {
        when (e.kind) {
            MaterializationFailureKind.RESOURCE_LIMIT ->
                throw EditFailureException(EditFailure.ResourceLimit("replacement-bytes"))
            else -> throw EditFailureException(EditFailure.UnrepresentableValue)
        }
    }
    return PreparedEdit(
        authority.span(position, position),
        encodeValue(text),
        mergeableDeletion = false,
        mappings = listOf(
            PlannedMapping(
                section,
                MappingPlan.SectionAfterEntryInsertion(key, value),
            ),
        ),
    )
}

private fun IniDocument.prepareRemoveEntry(target: NodeRef): List<PreparedEdit> {
    val entry = resolveEntry(target)
    return logicalPhysicalSpans(entry.logicalLine).mapIndexed { index, span ->
        deletionEdit(span, if (index == 0) target else null)
    }
}

private fun IniDocument.prepareRenameEntry(target: NodeRef, key: String): PreparedEdit {
    val entry = resolveEntry(target)
    validateEntryKey(key)
    validateEntryCollision(entry.section, key, except = target)
    return PreparedEdit(
        entry.keySpan,
        encodeValue(key),
        mergeableDeletion = false,
        mappings = listOf(PlannedMapping(target, MappingPlan.ReplacedEntry(key))),
    )
}

// ---------------------------------------------------------------------------
// Dependency validation (edit.rs:863-920)
// ---------------------------------------------------------------------------

private fun IniDocument.validateDependencies(transaction: EditTransaction) {
    val removedSections = HashSet<NodeRef>()
    val removedEntries = HashSet<NodeRef>()
    for (operation in transaction.operations) {
        when (operation) {
            is EditOperation.RemoveSection -> removedSections.add(operation.target)
            is EditOperation.RemoveEntry -> removedEntries.add(operation.target)
            else -> {}
        }
    }
    for (operation in transaction.operations) {
        when (operation) {
            is EditOperation.InsertSection -> {
                if (operation.placement is AssociationPlacement.Before &&
                    operation.placement.anchor in removedSections
                ) {
                    throw EditFailureException(EditFailure.PlacementAnchorRemoved)
                }
                if (operation.placement is AssociationPlacement.After &&
                    operation.placement.anchor in removedSections
                ) {
                    throw EditFailureException(EditFailure.PlacementAnchorRemoved)
                }
            }
            is EditOperation.InsertEntry -> {
                if (operation.placement is AssociationPlacement.Before &&
                    operation.placement.anchor in removedEntries
                ) {
                    throw EditFailureException(EditFailure.PlacementAnchorRemoved)
                }
                if (operation.placement is AssociationPlacement.After &&
                    operation.placement.anchor in removedEntries
                ) {
                    throw EditFailureException(EditFailure.PlacementAnchorRemoved)
                }
                if (operation.section in removedSections) {
                    throw EditFailureException(EditFailure.AncestorDescendantConflict)
                }
            }
            is EditOperation.ReplaceValue -> {
                val entry = entriesList.firstOrNull { it.nodeRef == operation.replacement.target }
                if (entry != null && entry.section in removedSections) {
                    throw EditFailureException(EditFailure.AncestorDescendantConflict)
                }
            }
            is EditOperation.RemoveEntry, is EditOperation.RenameEntry -> {
                val entry = entriesList.firstOrNull { it.nodeRef == operation.target }
                if (entry != null && entry.section in removedSections) {
                    throw EditFailureException(EditFailure.AncestorDescendantConflict)
                }
            }
            is EditOperation.RemoveSection, is EditOperation.RenameSection -> {}
        }
    }
}

// ---------------------------------------------------------------------------
// Target resolution and validation (edit.rs:922-1069)
// ---------------------------------------------------------------------------

private fun IniDocument.resolveDocument(target: NodeRef) {
    if (target.snapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (target.role != NodeRole.IniDocument) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    if (target != rootNode) {
        throw EditFailureException(EditFailure.TargetNotFound)
    }
}

private fun IniDocument.resolveSection(target: NodeRef): IniSection {
    if (target.snapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (target.role != NodeRole.IniSection && target.role != NodeRole.IniDefaultSection) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    return sectionsList.firstOrNull { it.nodeRef == target }
        ?: throw EditFailureException(EditFailure.TargetNotFound)
}

private fun IniDocument.resolveEntry(target: NodeRef): IniEntry {
    if (target.snapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (target.role != NodeRole.IniEntry) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    return entriesList.firstOrNull { it.nodeRef == target }
        ?: throw EditFailureException(EditFailure.TargetNotFound)
}

private fun IniDocument.resolveEntryInSection(
    target: NodeRef,
    section: NodeRef,
    entries: List<IniEntry>,
): IniEntry {
    resolveEntry(target)
    return entries.firstOrNull { it.nodeRef == target && it.section == section }
        ?: throw EditFailureException(EditFailure.InvalidPlacement)
}

private fun IniDocument.validateSectionName(name: String) {
    val valid = when (profile) {
        IniProfile.PortableV1 ->
            name.isNotEmpty() && name.all { isPortableNameChar(it) }
        IniProfile.WindowsV1 ->
            name.isNotEmpty() && name.all { isWindowsNameChar(it) }
        IniProfile.PythonConfigParserV1 ->
            name.isNotEmpty() && name.none { it.code == 0 || it == '\r' || it == '\n' }
    }
    if (!valid) {
        throw EditFailureException(EditFailure.InvalidName)
    }
}

private fun IniDocument.validateSectionCollision(name: String, except: NodeRef?) {
    if (profile != IniProfile.WindowsV1 &&
        sectionsList.any { it.nodeRef != except && it.name == name }
    ) {
        throw EditFailureException(EditFailure.NameCollision)
    }
}

private fun IniDocument.validateEntryKey(key: String) {
    val valid = when (profile) {
        IniProfile.PortableV1 ->
            key.isNotEmpty() && key.all { isPortableNameChar(it) }
        IniProfile.WindowsV1 ->
            key.isNotEmpty() &&
                key.trim(' ', '\t') == key &&
                key.all { isWindowsNameChar(it) }
        IniProfile.PythonConfigParserV1 ->
            key.isNotEmpty() &&
                key.trim(' ', '\t') == key &&
                key.none { it.code == 0 || it == '\r' || it == '\n' || it == '=' || it == ':' } &&
                key.first() != '#' && key.first() != ';'
    }
    if (!valid) {
        throw EditFailureException(EditFailure.InvalidKey)
    }
}

private fun IniDocument.validateEntryCollision(section: NodeRef, key: String, except: NodeRef?) {
    if (profile == IniProfile.WindowsV1) {
        return
    }
    val comparison = if (profile == IniProfile.PythonConfigParserV1) {
        optionxform(key)
    } else {
        key
    }
    val entry = entriesList.firstOrNull { candidate ->
        candidate.section == section &&
            candidate.nodeRef != except &&
            candidate.comparisonKey == comparison
    }
    if (entry != null) {
        throw EditFailureException(
            if (entry.key == key) EditFailure.DuplicateKey else EditFailure.KeyCollision,
        )
    }
}

// ---------------------------------------------------------------------------
// Span helpers (edit.rs:1071-1226)
// ---------------------------------------------------------------------------

private fun IniDocument.entryLineStart(entry: IniEntry): Int =
    logicalPhysicalSpans(entry.logicalLine).firstOrNull()?.startByte
        ?: throw EditFailureException(EditFailure.TargetNotFound)

private fun IniDocument.entryLineEnd(entry: IniEntry): Int =
    logicalPhysicalSpans(entry.logicalLine).lastOrNull()?.endByte
        ?: throw EditFailureException(EditFailure.TargetNotFound)

private fun IniDocument.sectionContentEnd(target: NodeRef): Int {
    val ordinal = sectionsList.indexOfFirst { it.nodeRef == target }
        .takeIf { it >= 0 } ?: throw EditFailureException(EditFailure.TargetNotFound)
    return sectionsList.getOrNull(ordinal + 1)?.let { sectionLineStart(it) } ?: sourceSnapshot.len
}

private fun IniDocument.sectionLineStart(section: IniSection): Int =
    logicalPhysicalSpans(section.logicalLine).firstOrNull()?.startByte
        ?: throw EditFailureException(EditFailure.TargetNotFound)

private fun IniDocument.logicalPhysicalSpans(logical: NodeRef): List<Span> {
    val record = logicalLinesList.firstOrNull { it.nodeRef == logical }
        ?: throw EditFailureException(EditFailure.TargetNotFound)
    return record.physicalLines.map { line ->
        physicalLinesList.firstOrNull { it.nodeRef == line }?.span
            ?: throw EditFailureException(EditFailure.TargetNotFound)
    }
}

private fun deletionEdit(span: Span, target: NodeRef? = null): PreparedEdit =
    PreparedEdit(
        span,
        ByteArray(0),
        mergeableDeletion = true,
        mappings = listOfNotNull(target?.let { PlannedMapping(it, MappingPlan.Deleted) }),
    )

private fun IniDocument.coalesceAdjacentDeletions(
    edits: List<PreparedEdit>,
): List<PreparedEdit> {
    val merged = ArrayList<PreparedEdit>(edits.size)
    for (edit in edits) {
        val previous = merged.lastOrNull()
        if (previous != null &&
            previous.mergeableDeletion &&
            edit.mergeableDeletion &&
            previous.oldSpan.endByte == edit.oldSpan.startByte
        ) {
            merged[merged.size - 1] = previous.copy(
                oldSpan = authority.span(previous.oldSpan.startByte, edit.oldSpan.endByte),
                mappings = previous.mappings + edit.mappings,
            )
        } else {
            merged.add(edit)
        }
    }
    return merged
}

/** The exact source-ownership interval of one entry's value
 * (edit.rs:1445-1475). */
private fun IniDocument.valueOwnership(entry: IniEntry): Span {
    val (start, end) = when (profile) {
        IniProfile.PortableV1 ->
            entry.valueSpan.startByte to entry.valueSpan.endByte
        IniProfile.WindowsV1 -> {
            val delimiter = syntaxSpan(IniSyntaxKind.Delimiter, entry.span)
                ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
            delimiter.endByte to entry.span.endByte
        }
        IniProfile.PythonConfigParserV1 -> {
            val logical = logicalLinesList.firstOrNull { it.nodeRef == entry.logicalLine }
                ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
            val last = logical.physicalLines.lastOrNull()?.let { line ->
                physicalLinesList.firstOrNull { it.nodeRef == line }
            } ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
            entry.valueSpan.startByte to last.contentSpan.endByte
        }
    }
    return authority.span(start, end)
}

/** The full record span of one entry (edit.rs:1477-1494). */
private fun IniDocument.entryRecordSpan(entry: IniEntry): Span {
    val logical = logicalLinesList.firstOrNull { it.nodeRef == entry.logicalLine }
        ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    val first = logical.physicalLines.firstOrNull()?.let { line ->
        physicalLinesList.firstOrNull { it.nodeRef == line }
    } ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    val last = logical.physicalLines.lastOrNull()?.let { line ->
        physicalLinesList.firstOrNull { it.nodeRef == line }
    } ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    return authority.span(first.span.startByte, last.span.endByte)
}

/** The first syntax piece of one kind inside a range (edit.rs:1496-1508). */
private fun IniDocument.syntaxSpan(kind: IniSyntaxKind, within: Span): Span? =
    pieces().zip(losslessSyntaxKinds()).firstNotNullOfOrNull { (piece, candidate) ->
        val span = piece.span
        if (candidate == kind && span.startByte >= within.startByte && span.endByte <= within.endByte) {
            span
        } else {
            null
        }
    }

/** Structural verification of one replacement (edit.rs:444-516). */
private fun verifyMapping(
    document: IniDocument,
    mapping: PlannedMapping,
    newSpan: Span,
) {
    when (val plan = mapping.plan) {
        is MappingPlan.ReplacedValue -> {
            val found = document.entriesList.any { entry ->
                entry.key == plan.expectedKey &&
                    document.valueOwnership(entry) == newSpan
            }
            if (!found) {
                throw EditFailureException(
                    if (plan.literal) EditFailure.InvalidLiteral else EditFailure.NewDocumentFormationFailed,
                )
            }
        }
        is MappingPlan.ReplacedSection -> {
            val found = document.sectionsList.any { section ->
                section.name == plan.expectedName && section.nameSpan == newSpan
            }
            if (!found) {
                throw EditFailureException(EditFailure.NewDocumentFormationFailed)
            }
        }
        is MappingPlan.ReplacedEntry -> {
            val found = document.entriesList.any { entry ->
                entry.key == plan.expectedKey && entry.keySpan == newSpan
            }
            if (!found) {
                throw EditFailureException(EditFailure.NewDocumentFormationFailed)
            }
        }
        is MappingPlan.SectionAfterEntryInsertion -> {
            val inserted = document.entriesList.any { entry ->
                entry.key == plan.expectedKey &&
                    entry.value == plan.expectedValue &&
                    try {
                        val span = document.entryRecordSpan(entry)
                        span.startByte >= newSpan.startByte && span.endByte == newSpan.endByte
                    } catch (e: EditFailureException) {
                        false
                    }
            }
            if (!inserted) {
                throw EditFailureException(EditFailure.NewDocumentFormationFailed)
            }
        }
        is MappingPlan.Deleted -> {}
    }
}

// ---------------------------------------------------------------------------
// Semantic value styles (edit.rs:1228-1430)
// ---------------------------------------------------------------------------

private fun IniDocument.semanticValue(
    entry: IniEntry,
    value: String,
    policy: RepresentationPolicy,
    diagnostics: ArrayList<Diagnostic>,
): ByteArray {
    if (policy == RepresentationPolicy.ExactLiteral) {
        throw EditFailureException(EditFailure.ExactLiteralRequiresLiteralOperation)
    }
    validateSemanticValue(profile, value)
    return when (policy) {
        RepresentationPolicy.PreserveCompatible -> preservedValue(entry, value)
        RepresentationPolicy.PreserveElseCanonical -> {
            try {
                preservedValue(entry, value)
            } catch (e: EditFailureException) {
                if (e.failure != EditFailure.RepresentationIncompatible) {
                    throw e
                }
                diagnostics.add(
                    sourceDiagnostic(
                        authority,
                        "ini.edit.canonical-fallback@1",
                        DiagnosticCategory.Edit,
                        Severity.Warning,
                        entry.valueSpan.startByte,
                        entry.valueSpan.endByte,
                        diagnostics.size.toULong(),
                    ),
                )
                canonicalValue(entry, value)
            }
        }
        RepresentationPolicy.CanonicalForProfile -> canonicalValue(entry, value)
        RepresentationPolicy.ExactLiteral -> error("ExactLiteral is rejected before matching")
    }
}

private fun IniDocument.preservedValue(entry: IniEntry, value: String): ByteArray =
    when (profile) {
        IniProfile.PortableV1 -> encodeValue(value)
        IniProfile.WindowsV1 -> when (entry.quoteStyle) {
            IniQuoteStyle.Single, IniQuoteStyle.Double -> {
                val quote = if (entry.quoteStyle == IniQuoteStyle.Single) '\'' else '"'
                encodeValue("$quote$value$quote")
            }
            IniQuoteStyle.None ->
                if (!windowsValueNeedsQuotes(value)) {
                    encodeValue(value)
                } else {
                    throw EditFailureException(EditFailure.RepresentationIncompatible)
                }
        }
        IniProfile.PythonConfigParserV1 -> preservedPythonValue(entry, value)
    }

private fun IniDocument.canonicalValue(entry: IniEntry, value: String): ByteArray =
    when (profile) {
        IniProfile.PortableV1 -> encodeValue(value)
        IniProfile.WindowsV1 -> {
            if (windowsValueNeedsQuotes(value)) {
                val quote = if (value.startsWith("\"") && value.endsWith("\"")) '\'' else '"'
                encodeValue("$quote$value$quote")
            } else {
                encodeValue(value)
            }
        }
        IniProfile.PythonConfigParserV1 -> canonicalPythonValue(entry, value)
    }

private fun IniDocument.preservedPythonValue(entry: IniEntry, value: String): ByteArray {
    val logical = logicalLinesList.firstOrNull { it.nodeRef == entry.logicalLine }
        ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    val physical = logical.physicalLines
    val newLines = value.split('\n')
    val oldLines = entry.value.split('\n')
    if (physical.size != newLines.size || oldLines.size != newLines.size) {
        throw EditFailureException(EditFailure.RepresentationIncompatible)
    }
    val output = ByteArrayOutputStreamBounded(parseLimits.common.maxSourceBytes)
    output.write(encodeValue(newLines[0]))
    val first = physicalLinesList.firstOrNull { it.nodeRef == physical[0] }
        ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    output.write(raw(entry.valueSpan.endByte, first.contentSpan.endByte))
    for (index in 1 until physical.size) {
        val previous = physicalLinesList.firstOrNull { it.nodeRef == physical[index - 1] }
            ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        val lineBreak = previous.lineBreakSpan
            ?: throw EditFailureException(EditFailure.RepresentationIncompatible)
        output.write(raw(lineBreak.startByte, lineBreak.endByte))
        val line = physicalLinesList.firstOrNull { it.nodeRef == physical[index] }
            ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        if (oldLines[index].isEmpty() != newLines[index].isEmpty()) {
            throw EditFailureException(EditFailure.RepresentationIncompatible)
        }
        if (newLines[index].isEmpty()) {
            output.write(raw(line.contentSpan.startByte, line.contentSpan.endByte))
            continue
        }
        val valuePiece = syntaxSpan(IniSyntaxKind.EntryValue, line.contentSpan)
            ?: throw EditFailureException(EditFailure.RepresentationIncompatible)
        output.write(raw(line.contentSpan.startByte, valuePiece.startByte))
        output.write(encodeValue(newLines[index]))
        output.write(raw(valuePiece.endByte, line.contentSpan.endByte))
    }
    return output.bytes()
}

private fun IniDocument.canonicalPythonValue(entry: IniEntry, value: String): ByteArray {
    val logical = logicalLinesList.firstOrNull { it.nodeRef == entry.logicalLine }
        ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    val first = logical.physicalLines.firstOrNull()?.let { line ->
        physicalLinesList.firstOrNull { it.nodeRef == line }
    } ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    val baseIndent = raw(first.contentSpan.startByte, entry.keySpan.startByte)
    val output = ByteArrayOutputStreamBounded(parseLimits.common.maxSourceBytes)
    for ((index, line) in value.split('\n').withIndex()) {
        if (index > 0) {
            output.write(encodeValue("\n"))
            output.write(baseIndent)
            if (line.isNotEmpty()) {
                output.write(encodeValue("    "))
            }
        }
        output.write(encodeValue(line))
    }
    return output.bytes()
}

/** The frozen semantic-value validation of one profile (edit.rs:1518-1535). */
private fun validateSemanticValue(profile: IniProfile, value: String) {
    val valid = when (profile) {
        IniProfile.PortableV1 -> value.all { isPortableValueChar(it) }
        IniProfile.WindowsV1 -> value.none { it.code == 0 || it == '\r' || it == '\n' }
        IniProfile.PythonConfigParserV1 ->
            value.none { it.code == 0 || it == '\r' } &&
                !value.endsWith('\n') &&
                value.split('\n').withIndex().all { (index, line) ->
                    line.trim(' ', '\t') == line &&
                        (index == 0 || (line.firstOrNull() != '#' && line.firstOrNull() != ';'))
                }
    }
    if (!valid) {
        throw EditFailureException(EditFailure.UnrepresentableValue)
    }
}

private fun IniDocument.encodeValue(value: String): ByteArray =
    try {
        encodeFragment(
            value,
            sourceSnapshot.encodingFacts.selected,
            parseLimits.common.maxSourceBytes,
        )
    } catch (e: MaterializationException) {
        when (e.kind) {
            MaterializationFailureKind.RESOURCE_LIMIT ->
                throw EditFailureException(EditFailure.ResourceLimit(e.name))
            MaterializationFailureKind.UNSUPPORTED_ENCODING ->
                throw EditFailureException(EditFailure.EncodingUnrepresentable)
            else -> throw EditFailureException(EditFailure.UnrepresentableValue)
        }
    }

private fun IniDocument.raw(start: Int, end: Int): ByteArray =
    if (start < 0 || end < start || end > sourceSnapshot.len) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    } else {
        sourceSnapshot.rawBytes().copyOfRange(start, end)
    }

private fun IniDocument.originalEncodingSelection(): IniEncodingSelection {
    val override = sourceSnapshot.encodingFacts.callerOverride
    return if (override != null) {
        IniEncodingSelection.Explicit(override)
    } else {
        IniEncodingSelection.ProfileDefault
    }
}

private fun IniDocument.profileNewline(): String =
    if (profile == IniProfile.WindowsV1) "\r\n" else "\n"

private fun endsWithNewline(text: String): Boolean =
    text.endsWith('\n') || text.endsWith('\r')

/** Bounded byte accumulation with the replacement-byte limit
 * (edit.rs:1577-1590). */
private class ByteArrayOutputStreamBounded(private val max: Int) {
    private val bytes = java.io.ByteArrayOutputStream()

    fun write(chunk: ByteArray) {
        val length = bytes.size() + chunk.size
        if (length > max) {
            throw EditFailureException(EditFailure.ResourceLimit("replacement-bytes"))
        }
        bytes.write(chunk, 0, chunk.size)
    }

    fun bytes(): ByteArray = bytes.toByteArray()
}

private fun destructiveTarget(operation: EditOperation): NodeRef? =
    when (operation) {
        is EditOperation.ReplaceValue -> operation.replacement.target
        is EditOperation.RemoveSection -> operation.target
        is EditOperation.RenameSection -> operation.target
        is EditOperation.RemoveEntry -> operation.target
        is EditOperation.RenameEntry -> operation.target
        is EditOperation.InsertSection, is EditOperation.InsertEntry -> null
    }

// ---------------------------------------------------------------------------
// Patch metadata and summaries (edit.rs:1604-1720)
// ---------------------------------------------------------------------------

/** Patch metadata: operation.{index} -> frozen operation id@version
 * (edit.rs:1604-1627). */
private fun operationMetadata(transaction: EditTransaction): Map<String, String> {
    val metadata = LinkedHashMap<String, String>()
    for ((index, operation) in transaction.operations.withIndex()) {
        metadata["operation.$index"] = operationId(operation).toString()
    }
    return metadata
}

/** The frozen operation id@version (edit.rs:1609-1623). */
internal fun operationId(operation: EditOperation): FormatOperationId =
    FormatOperationId(
        when (operation) {
            is EditOperation.ReplaceValue -> when (operation.replacement) {
                is ValueReplacement.Semantic -> "ini.edit.replace-semantic-value"
                is ValueReplacement.Literal -> "ini.edit.replace-literal-value"
            }
            is EditOperation.InsertSection -> "ini.edit.insert-section"
            is EditOperation.RemoveSection -> "ini.edit.remove-section"
            is EditOperation.RenameSection -> "ini.edit.rename-section"
            is EditOperation.InsertEntry -> "ini.edit.insert-entry"
            is EditOperation.RemoveEntry -> "ini.edit.remove-entry"
            is EditOperation.RenameEntry -> "ini.edit.rename-entry"
        },
        1,
    )

/** Content-free operation summaries (edit.rs:1629-1702). */
private fun operationSummaries(transaction: EditTransaction): List<EditOperationSummary> =
    transaction.operations.map { operation ->
        val (id, arguments) = when (operation) {
            is EditOperation.ReplaceValue -> when (val replacement = operation.replacement) {
                is ValueReplacement.Semantic -> "ini.edit.replace-semantic-value" to
                    linkedMapOf(
                        "representation_policy" to policyName(replacement.policy),
                        "value_scalars" to replacement.value.codePointCount(0, replacement.value.length).toString(),
                    )
                is ValueReplacement.Literal -> "ini.edit.replace-literal-value" to
                    linkedMapOf("literal_bytes" to replacement.literal.size.toString())
            }
            is EditOperation.InsertSection -> "ini.edit.insert-section" to
                linkedMapOf(
                    "name_scalars" to operation.name.codePointCount(0, operation.name.length).toString(),
                    "placement" to placementName(operation.placement),
                )
            is EditOperation.RemoveSection -> "ini.edit.remove-section" to linkedMapOf()
            is EditOperation.RenameSection -> "ini.edit.rename-section" to
                linkedMapOf("name_scalars" to operation.name.codePointCount(0, operation.name.length).toString())
            is EditOperation.InsertEntry -> "ini.edit.insert-entry" to
                linkedMapOf(
                    "key_scalars" to operation.key.codePointCount(0, operation.key.length).toString(),
                    "placement" to placementName(operation.placement),
                    "value_scalars" to operation.value.codePointCount(0, operation.value.length).toString(),
                )
            is EditOperation.RemoveEntry -> "ini.edit.remove-entry" to linkedMapOf()
            is EditOperation.RenameEntry -> "ini.edit.rename-entry" to
                linkedMapOf("key_scalars" to operation.key.codePointCount(0, operation.key.length).toString())
        }
        EditOperationSummary.new(FormatOperationId(id, 1), arguments)
    }

private fun policyName(policy: RepresentationPolicy): String =
    when (policy) {
        RepresentationPolicy.ExactLiteral -> "exact-literal"
        RepresentationPolicy.PreserveCompatible -> "preserve-compatible"
        RepresentationPolicy.CanonicalForProfile -> "canonical-for-profile"
        RepresentationPolicy.PreserveElseCanonical -> "preserve-else-canonical"
    }

private fun placementName(placement: AssociationPlacement): String =
    when (placement) {
        AssociationPlacement.Start -> "start"
        AssociationPlacement.End -> "end"
        is AssociationPlacement.Before -> "before"
        is AssociationPlacement.After -> "after"
    }

/** The patch limits of one commit (edit.rs:1592-1602). */
private fun IniDocument.sourcePatchLimits(operationCount: Int): SourcePatchLimits =
    SourcePatchLimits(
        source = SourceLimits(
            maxRawBytes = parseLimits.common.maxSourceBytes,
            maxDecodedUtf8Bytes = parseLimits.maxDecodedUtf8Bytes,
            maxDecodedScalars = parseLimits.maxDecodedScalars,
        ),
        maxReplacements = operationCount,
        maxPatchBytes = parseLimits.common.maxSourceBytes.saturatingMul(2),
    )

// ---------------------------------------------------------------------------
// Portable character rules (edit.rs:1518-1568)
// ---------------------------------------------------------------------------

private fun isPortableNameChar(character: Char): Boolean {
    val value = character.code
    return (value in 0x30..0x39) || (value in 0x41..0x5a) || (value in 0x61..0x7a) ||
        value == 0x5f || value == 0x2d || value == 0x2e
}

private fun isPortableValueChar(character: Char): Boolean {
    val value = character.code
    return (value in 0x21..0x7e && value !in setOf(0x27, 0x22, 0x5c, 0x3a, 0x23, 0x3b)) ||
        value == 0x20
}

private fun isWindowsNameChar(character: Char): Boolean {
    val value = character.code
    return (value in 0x21..0x7e || value == 0x20) && value !in setOf(0x5b, 0x5d, 0x3d, 0x00, 0x0d, 0x0a)
}
