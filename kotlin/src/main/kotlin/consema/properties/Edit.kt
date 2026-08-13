// Java Properties structural edit operations: one immutable transaction,
// atomic commit, change set, dry-run plan, untouched-byte proof, and
// SourcePatch derivation.
//
// Data authority:
//   - RFC 0010 §13 (docs/rfcs/0010-java-properties-profiles-v1.md:383-413):
//     the five operations replace-semantic-value, replace-literal-value,
//     insert-property, remove-property, rename-property; semantic
//     replacement accepts a JavaString (exact unpaired code units through
//     canonical escapes) and preserves compatible escape/continuation style
//     or reports canonical fallback; literal replacement must form exactly
//     one raw element and cannot consume delimiter, comment, or newline
//     ownership; insertion takes explicit placement and derives profile-
//     valid representation and the existing newline convention; removal owns
//     the property's natural lines and unambiguous continuation markers but
//     not adjacent comments; rename preserves value and trivia; duplicate
//     keys are permitted and never overwrite another association;
//     multi-operation validation rejects wrong profile/role/snapshot,
//     missing or duplicate target, overlapping ownership, removed placement
//     anchor, invalid literal, unrepresentable encoding, resource failure,
//     and reparse/closure failure before a patch exists.
//   - RFC 0004 §13-§16 (docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:300-384): the transaction/conflict algebra, the
//     dry-run plan, the untouched-byte proof, and the derived SourcePatch;
//     ChangeSet remains the document-level change fact (RFC 0004 §16).
//   - conformance/vectors/java-properties-v1.json pins the golden outputs
//     and conflict codes (edit.all-five-operations, lines 105-109;
//     edit.dry-run-patch-proof-conflict-atomicity, lines 110-114).
//   - crates/consema-properties/src/edit.rs is the byte-arbitration
//     authority (commit edit.rs:270-442, dry-run edit.rs:444-459, ownership
//     edit.rs:461-605, canonical escaping edit.rs:925-1036, expected-state
//     verification edit.rs:794-833, mappings edit.rs:894-923).
//   - The shared ChangeSet shapes live in the Rust consema-document layer
//     (crates/consema-document/src/lib.rs:800-899: SourceEdit, NodeMapping,
//     NodeMappingStatus, ChangeSet); the Kotlin document layer does not own
//     ChangeSet yet (the L4 structural-edit milestone), so this package
//     defines the same immutable records locally and the post-1.0.0
//     (冻结前评估项，见五要素终审 F-28.3-1 处置) milestone can promote
//     them into the shared layer unchanged.
//
// Kotlin-idiomatic design: failures are a sealed hierarchy whose [name] is
// the exact vector spelling and whose registered code follows the Rust
// diagnostic_code mapping (edit.rs:237-252); commit/dry-run throw the typed
// [EditFailureException] so callers match exhaustively on the failure class.

package consema.properties

import consema.document.AssociationPlacement
import consema.document.BomPolicy
import consema.document.EditOperationSummary
import consema.document.EditPlan
import consema.document.EditPlanException
import consema.document.EditPlanSourceId
import consema.document.EncodingRequest
import consema.document.FormatOperationId
import consema.document.FormationStatus
import consema.document.MaterializationException
import consema.document.MaterializationFailureKind
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.SnapshotIdentity
import consema.document.SourceEncoding
import consema.document.SourceLimits
import consema.document.SourcePatch
import consema.document.SourceReplacement
import java.util.TreeMap
import consema.document.SourceSnapshot
import consema.document.Span
import consema.document.UntouchedByteProof
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/** One typed Java Properties structural edit operation (edit.rs:16-56). */
sealed class EditOperation {
    /** Replaces one property's semantic Java UTF-16 value. */
    data class ReplaceSemanticValue(
        /** Exact property target. */
        val target: NodeRef,
        /** Exact replacement Java string. */
        val value: JavaString,
    ) : EditOperation()

    /** Replaces one property's exact raw value literal. */
    data class ReplaceLiteralValue(
        /** Exact property target. */
        val target: NodeRef,
        /** Raw bytes in the base document's selected source encoding. */
        val literal: ByteArray,
    ) : EditOperation()

    /** Inserts one canonical property occurrence. */
    data class InsertProperty(
        /** Exact Properties document target. */
        val document: NodeRef,
        /** Exact Java UTF-16 key. */
        val key: JavaString,
        /** Exact Java UTF-16 value. */
        val value: JavaString,
        /** Placement among property occurrences. */
        val placement: AssociationPlacement,
    ) : EditOperation()

    /** Removes one exact property occurrence and all its natural lines. */
    data class RemoveProperty(
        /** Exact property target. */
        val target: NodeRef,
    ) : EditOperation()

    /** Replaces one exact property's semantic Java UTF-16 key. */
    data class RenameProperty(
        /** Exact property target. */
        val target: NodeRef,
        /** Exact replacement key. */
        val key: JavaString,
    ) : EditOperation()

    internal fun destructiveTarget(): NodeRef? =
        when (this) {
            is ReplaceSemanticValue -> target
            is ReplaceLiteralValue -> target
            is RemoveProperty -> target
            is RenameProperty -> target
            is InsertProperty -> null
        }
}

/** Immutable edit transaction; every operation resolves against one base
 * snapshot (edit.rs:70-89). */
class EditTransaction internal constructor(
    /** Base snapshot identity. */
    val baseSnapshot: SnapshotIdentity,
    /** Ordered declared operations. */
    val operations: List<EditOperation>,
)

/** Builder for one immutable Properties edit transaction (edit.rs:91-163). */
class EditTransactionBuilder internal constructor(private val base: SnapshotIdentity) {
    private val operations = ArrayList<EditOperation>()

    companion object {
        /** Binds a new transaction to one immutable Properties document
         * (edit.rs:98-106). */
        fun new(document: Document): EditTransactionBuilder =
            EditTransactionBuilder(document.snapshotIdentity)
    }

    /** Adds one semantic Java-string value replacement (edit.rs:107-114). */
    fun semanticValue(target: NodeRef, value: JavaString): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceSemanticValue(target, value))
        return this
    }

    /** Adds one exact raw value-literal replacement (edit.rs:116-123). */
    fun literalValue(target: NodeRef, literal: ByteArray): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceLiteralValue(target, literal))
        return this
    }

    /** Adds one canonical property insertion (edit.rs:125-139). */
    fun insertProperty(
        document: NodeRef,
        key: JavaString,
        value: JavaString,
        placement: AssociationPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertProperty(document, key, value, placement))
        return this
    }

    /** Adds one exact property removal (edit.rs:141-147). */
    fun removeProperty(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveProperty(target))
        return this
    }

    /** Adds one semantic Java-string property rename (edit.rs:149-157). */
    fun renameProperty(target: NodeRef, key: JavaString): EditTransactionBuilder {
        operations.add(EditOperation.RenameProperty(target, key))
        return this
    }

    /** Completes the request; validation remains atomic at dry-run or
     * commit (edit.rs:159-162). */
    fun build(): EditTransaction = EditTransaction(base, operations.toList())
}

/** Atomic edit success (edit.rs:165-176). */
class EditCommit(
    /** New immutable document. */
    val document: Document,
    /** Complete old-to-new change facts. */
    val changeSet: ChangeSet,
    /** Replayable exact raw-byte patch. */
    val sourcePatch: SourcePatch,
    /** Evidence for every byte outside the replacement set. */
    val untouchedProof: UntouchedByteProof,
)

/** Stable edit validation or commit failure (edit.rs:178-214). The [name]
 * is the exact vector spelling; the registered code follows the Rust
 * diagnostic_code mapping ([editFailureCode], edit.rs:237-252). */
sealed class EditFailure(open val name: String) {
    /** Edits are forbidden on a recovered document. */
    data object RecoveredDocument : EditFailure("RecoveredDocument")

    /** Transaction or target belongs to another snapshot. */
    data object WrongSnapshot : EditFailure("WrongSnapshot")

    /** Target has the wrong structural role. */
    data object WrongRole : EditFailure("WrongRole")

    /** More than one operation names the same exact property. */
    data object DuplicateTarget : EditFailure("DuplicateTarget")

    /** Prepared source ownership intervals overlap or share an insertion
     * point. */
    data object OverlappingOwnership : EditFailure("OverlappingOwnership")

    /** Placement is invalid or names an unavailable anchor. */
    data object InvalidPlacement : EditFailure("InvalidPlacement")

    /** An insertion anchor is removed by this transaction. */
    data object PlacementAnchorRemoved : EditFailure("PlacementAnchorRemoved")

    /** A target no longer exists in the base snapshot. */
    data object TargetNotFound : EditFailure("TargetNotFound")

    /** A semantic Java string cannot be represented by the selected source
     * encoding. */
    data object EncodingUnrepresentable : EditFailure("EncodingUnrepresentable")

    /** Literal bytes do not form exactly one raw value element. */
    data object InvalidLiteral : EditFailure("InvalidLiteral")

    /** A configured edit or output bound was exceeded. */
    data class ResourceLimit(override val name: String) : EditFailure("ResourceLimit")

    /** Replacement bytes did not close through exact reparse and semantic
     * verification. */
    data object NewDocumentFormationFailed : EditFailure("NewDocumentFormationFailed")
}

/** The typed edit failure thrown by [Document.commit] and
 * [Document.dryRun]. */
class EditFailureException(val failure: EditFailure) :
    Exception("edit: ${failure.name}")

/** The frozen registered code of one edit failure (edit.rs:237-252). */
internal fun editFailureCode(failure: EditFailure): String =
    when (failure) {
        EditFailure.RecoveredDocument -> "core.edit.incomplete-target@1"
        EditFailure.WrongSnapshot -> "core.edit.wrong-snapshot@1"
        EditFailure.WrongRole -> "core.edit.wrong-role@1"
        EditFailure.DuplicateTarget,
        EditFailure.OverlappingOwnership,
        EditFailure.PlacementAnchorRemoved,
        -> "core.edit.conflicting-edits@1"

        EditFailure.InvalidPlacement -> "java-properties.edit.invalid-placement@1"
        EditFailure.TargetNotFound -> "core.edit.target-not-found@1"
        EditFailure.EncodingUnrepresentable -> "core.edit.representation-incompatible@1"
        EditFailure.InvalidLiteral -> "core.edit.invalid-literal@1"
        is EditFailure.ResourceLimit -> "core.edit.resource-limit@1"
        EditFailure.NewDocumentFormationFailed -> "core.edit.formation-failed@1"
    }

// ---------------------------------------------------------------------------
// Change set records (consema-document lib.rs:800-899)
// ---------------------------------------------------------------------------

/** One ordered non-overlapping source replacement (lib.rs:800-809). */
data class SourceEdit(
    /** Replaced old range. */
    val oldSpan: Span,
    /** Range occupied by replacement bytes in the new snapshot. */
    val newSpan: Span,
    /** Exact replacement bytes. */
    val replacement: ByteArray,
)

/** Explicit node mapping status across immutable snapshots (lib.rs:811-826). */
enum class NodeMappingStatus {
    /** Exact structural entity survived. */
    Preserved,

    /** Entity was replaced. */
    Replaced,

    /** Entity was deleted. */
    Deleted,

    /** One entity became several. */
    Split,

    /** Several entities became one. */
    Merged,

    /** No reliable mapping is known. */
    Unmapped,
}

/** One explicit old-to-new node mapping fact (lib.rs:828-839). */
data class NodeMapping(
    /** Old handle. */
    val old: NodeRef,
    /** New handle when a one-to-one mapping is known. */
    val new: NodeRef?,
    /** Mapping status. */
    val status: NodeMappingStatus,
    /** Stable reason for missing or non-trivial mapping. */
    val reason: String?,
)

/** Complete immutable description of one atomic document transition
 * (RFC 0004 §13, §16; lib.rs:841-899). */
class ChangeSet internal constructor(
    /** Base snapshot. */
    val oldSnapshot: SnapshotIdentity,
    /** Committed snapshot. */
    val newSnapshot: SnapshotIdentity,
    /** Ordered non-overlapping source edits. */
    val sourceEdits: List<SourceEdit>,
    /** Explicit node mappings. */
    val nodeMappings: List<NodeMapping>,
    /** Operation diagnostics, never written into either Document. */
    val diagnostics: List<Diagnostic>,
)

// ---------------------------------------------------------------------------
// Commit
// ---------------------------------------------------------------------------

/** One prepared byte edit (edit.rs:265-268). */
private data class PreparedEdit(
    val oldSpan: Span,
    val replacement: ByteArray,
)

/** The expected post-commit property state (edit.rs:255-263). */
private data class ExpectedProperty(
    val old: NodeRef?,
    val key: JavaString,
    val value: JavaString?,
    val literal: Boolean,
    val literalOldSpan: Span?,
    val removed: Boolean,
)

/**
 * Atomically commits every declared Properties operation (edit.rs:270-442).
 * On failure the document remains unchanged.
 */
fun Document.commit(transaction: EditTransaction): EditCommit {
    if (formationStatus != FormationStatus.Complete) {
        throw EditFailureException(EditFailure.RecoveredDocument)
    }
    if (transaction.baseSnapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (transaction.operations.size > parseLimits.common.maxNodeCount) {
        throw EditFailureException(EditFailure.ResourceLimit("edit-operations"))
    }
    validateRemovedAnchors(transaction)

    val targets = HashSet<NodeRef>()
    val insertBoundaries = HashSet<Int>()
    val diagnostics = ArrayList<Diagnostic>()
    val prepared = ArrayList<PreparedEdit>()
    val expected = propertyEntities.map { property ->
        ExpectedProperty(
            old = property.node,
            key = property.key,
            value = property.value,
            literal = false,
            literalOldSpan = null,
            removed = false,
        )
    }.toMutableList()
    val insertions = TreeMap<Int, ExpectedProperty>()

    for (operation in transaction.operations) {
        operation.destructiveTarget()?.let { target ->
            if (!targets.add(target)) {
                throw EditFailureException(EditFailure.DuplicateTarget)
            }
        }
        when (operation) {
            is EditOperation.ReplaceSemanticValue -> {
                val ordinal = resolvePropertyOrdinal(operation.target)
                val property = propertyEntity(ordinal)
                val oldSpan = valueOwnership(property)
                val replacement = preserveDirectValue(property, operation.value)
                    ?: run {
                        diagnostics.add(canonicalFallbackDiagnostic(property.span))
                        canonicalFragment(operation.value, false)
                    }
                expected[ordinal] = expected[ordinal].copy(value = operation.value)
                prepared.add(PreparedEdit(oldSpan, replacement))
            }
            is EditOperation.ReplaceLiteralValue -> {
                val ordinal = resolvePropertyOrdinal(operation.target)
                validateLiteral(operation.literal)
                val property = propertyEntity(ordinal)
                val oldSpan = valueOwnership(property)
                expected[ordinal] = expected[ordinal].copy(value = null, literal = true, literalOldSpan = oldSpan)
                prepared.add(PreparedEdit(oldSpan, operation.literal))
            }
            is EditOperation.InsertProperty -> {
                validateDocumentTarget(operation.document)
                val (boundary, position) = insertionLocation(operation.placement)
                if (!insertBoundaries.add(boundary)) {
                    throw EditFailureException(EditFailure.OverlappingOwnership)
                }
                insertions[boundary] = ExpectedProperty(
                    old = null,
                    key = operation.key,
                    value = operation.value,
                    literal = false,
                    literalOldSpan = null,
                    removed = false,
                )
                val insertionSpan = try {
                    authority.span(position, position)
                } catch (e: consema.document.LocationException) {
                    throw EditFailureException(EditFailure.InvalidPlacement)
                }
                prepared.add(
                    PreparedEdit(
                        insertionSpan,
                        canonicalRecord(position, operation.key, operation.value),
                    ),
                )
            }
            is EditOperation.RemoveProperty -> {
                val ordinal = resolvePropertyOrdinal(operation.target)
                expected[ordinal] = expected[ordinal].copy(removed = true)
                prepared.add(
                    PreparedEdit(
                        recordOwnership(propertyEntity(ordinal)),
                        ByteArray(0),
                    ),
                )
            }
            is EditOperation.RenameProperty -> {
                val ordinal = resolvePropertyOrdinal(operation.target)
                expected[ordinal] = expected[ordinal].copy(key = operation.key)
                prepared.add(
                    PreparedEdit(
                        keyOwnership(propertyEntity(ordinal)),
                        canonicalFragment(operation.key, true),
                    ),
                )
            }
        }
    }
    prepared.sortWith(compareBy<PreparedEdit> { it.oldSpan.startByte }.thenBy { it.oldSpan.endByte })
    validateNonOverlapping(prepared)
    val finalExpected = assembleExpected(expected, insertions)
    val closureFailure = closureFailureOf(finalExpected)
    val rendered = applyPrepared(prepared)
    val newDocument = try {
        parse(rendered, profile, sourceEncoding, parseLimits)
    } catch (e: PropertiesFormationException) {
        throw EditFailureException(closureFailure)
    }
    if (newDocument.formationStatus != FormationStatus.Complete) {
        throw EditFailureException(closureFailure)
    }
    verifyExpected(newDocument, finalExpected)

    val sourceEdits = buildSourceEdits(newDocument, prepared)
    verifyLiteralOwnership(newDocument, finalExpected, sourceEdits)
    val mappings = buildNodeMappings(newDocument, finalExpected, transaction)
    val changeSet = ChangeSet(
        oldSnapshot = snapshotIdentity,
        newSnapshot = newDocument.snapshotIdentity,
        sourceEdits = sourceEdits,
        nodeMappings = mappings,
        diagnostics = diagnostics,
    )
    val replacements = sourceEdits.map { edit ->
        SourceReplacement.new(
            edit.oldSpan.startByte,
            edit.oldSpan.endByte,
            source.rawBytes().copyOfRange(edit.oldSpan.startByte, edit.oldSpan.endByte),
            edit.replacement,
        )
    }
    val patchLimits = sourcePatchLimits(parseLimits, transaction.operations.size)
    val sourcePatch = try {
        SourcePatch.create(source, replacements, operationMetadata(transaction), patchLimits)
    } catch (e: consema.document.SourcePatchException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    val untouchedProof = try {
        UntouchedByteProof.create(source, newDocument.source(), replacements)
    } catch (e: consema.document.UntouchedByteProofException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    return EditCommit(newDocument, changeSet, sourcePatch, untouchedProof)
}

/**
 * Fully validates and plans an edit without publishing a new Document
 * (edit.rs:444-459; RFC 0004 §14). Dry-run and commit produce the same
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
            operationSummaries(transaction),
            commit.sourcePatch,
            commit.changeSet.diagnostics,
        )
    } catch (e: EditPlanException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
}

/** Content-free operation summaries (edit.rs:1091-1138). */
private fun operationSummaries(transaction: EditTransaction): List<EditOperationSummary> =
    try {
        transaction.operations.map { operation ->
            val arguments = when (operation) {
                is EditOperation.ReplaceSemanticValue -> linkedMapOf(
                    "value_code_units" to operation.value.length.toString(),
                )
                is EditOperation.ReplaceLiteralValue -> linkedMapOf(
                    "literal_bytes" to operation.literal.size.toString(),
                )
                is EditOperation.InsertProperty -> linkedMapOf(
                    "key_code_units" to operation.key.length.toString(),
                    "value_code_units" to operation.value.length.toString(),
                    "placement" to placementName(operation.placement),
                )
                is EditOperation.RemoveProperty -> linkedMapOf()
                is EditOperation.RenameProperty -> linkedMapOf(
                    "key_code_units" to operation.key.length.toString(),
                )
            }
            EditOperationSummary.new(operationId(operation), arguments)
        }
    } catch (e: EditPlanException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }

// ---------------------------------------------------------------------------
// Ownership and target resolution
// ---------------------------------------------------------------------------

private fun Document.resolvePropertyOrdinal(target: NodeRef): Int {
    try {
        return propertyOrdinal(target)
            ?: throw EditFailureException(EditFailure.TargetNotFound)
    } catch (e: PropertiesAccessException) {
        throw EditFailureException(
            when (e.kind) {
                PropertiesAccessErrorKind.WrongSnapshot -> EditFailure.WrongSnapshot
                PropertiesAccessErrorKind.WrongRole -> EditFailure.WrongRole
                PropertiesAccessErrorKind.UnknownNode -> EditFailure.TargetNotFound
            },
        )
    }
}

private fun Document.validateDocumentTarget(target: NodeRef) {
    try {
        verifySnapshot(target)
    } catch (e: PropertiesAccessException) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (target.role != NodeRole.PropertiesDocument) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    if (target != rootNode) {
        throw EditFailureException(EditFailure.TargetNotFound)
    }
}

/** An insertion anchor removed by the same transaction is a conflict
 * (edit.rs:487-505). */
private fun validateRemovedAnchors(transaction: EditTransaction) {
    val removed = transaction.operations
        .filterIsInstance<EditOperation.RemoveProperty>()
        .map { it.target }
        .toSet()
    for (operation in transaction.operations) {
        if (operation is EditOperation.InsertProperty) {
            val anchor = when (val placement = operation.placement) {
                is AssociationPlacement.Before -> placement.anchor
                is AssociationPlacement.After -> placement.anchor
                AssociationPlacement.Start, AssociationPlacement.End -> null
            }
            if (anchor != null && anchor in removed) {
                throw EditFailureException(EditFailure.PlacementAnchorRemoved)
            }
        }
    }
}

/** The (property-boundary, raw byte) insertion point of one placement
 * (edit.rs:507-541). */
private fun Document.insertionLocation(placement: AssociationPlacement): Pair<Int, Int> {
    val count = propertyEntities.size
    return when (placement) {
        AssociationPlacement.Start -> {
            val first = propertyEntities.firstOrNull()
            0 to (first?.let { recordOwnership(it).startByte } ?: source.len)
        }
        AssociationPlacement.End -> count to source.len
        is AssociationPlacement.Before -> {
            val ordinal = resolvePropertyOrdinal(placement.anchor)
            ordinal to recordOwnership(propertyEntity(ordinal)).startByte
        }
        is AssociationPlacement.After -> {
            val ordinal = resolvePropertyOrdinal(placement.anchor)
            ordinal + 1 to recordOwnership(propertyEntity(ordinal)).endByte
        }
    }
}

/** A property owns its natural lines including the final terminator but not
 * adjacent comments (RFC 0010 §13; edit.rs:543-560). */
private fun Document.recordOwnership(property: PropertyEntity): Span {
    val logical = logicalLineEntity(property.logicalLineIndex)
    val firstIndex = logical.naturalLineIndices.firstOrNull()
        ?: throw EditFailureException(EditFailure.TargetNotFound)
    val lastIndex = logical.naturalLineIndices.last()
    val first = naturalLineEntity(firstIndex)
    val last = naturalLineEntity(lastIndex)
    return span(first.span.startByte, last.span.endByte)
}

private fun Document.keyOwnership(property: PropertyEntity): Span =
    fragmentOwnership(property.keyFragments, property.keyAnchor)

private fun Document.valueOwnership(property: PropertyEntity): Span =
    fragmentOwnership(property.valueFragments, property.valueAnchor)

private fun Document.fragmentOwnership(fragments: List<Span>, anchor: Span): Span {
    if (fragments.isEmpty()) {
        return anchor
    }
    return span(fragments.first().startByte, fragments.last().endByte)
}

private fun Document.span(startByte: Int, endByte: Int): Span =
    try {
        authority.span(startByte, endByte)
    } catch (e: consema.document.LocationException) {
        throw EditFailureException(EditFailure.TargetNotFound)
    }

// ---------------------------------------------------------------------------
// Replacement fragments
// ---------------------------------------------------------------------------

/** Direct value preservation: one natural line, no value escapes, no
 * leading whitespace or backslash/CR/LF content (edit.rs:578-599). */
private fun Document.preserveDirectValue(property: PropertyEntity, value: JavaString): ByteArray? {
    val logical = logicalLineEntity(property.logicalLineIndex)
    if (logical.naturalLineIndices.size != 1 ||
        property.escapeIndices.any { !escapeEntity(it).inKey }
    ) {
        return null
    }
    val text = try {
        value.toUnicode()
    } catch (e: JavaStringConversionException) {
        return null
    }
    if (text.startsWith(' ') || text.startsWith('\t') || text.startsWith('\u000C') ||
        text.contains('\\') || text.contains('\r') || text.contains('\n')
    ) {
        return null
    }
    return try {
        encodeFragment(
            text,
            sourceOutputEncoding(),
            parseLimits.common.maxSourceBytes,
        )
    } catch (e: MaterializationException) {
        null
    }
}

/** The canonical replacement fragment of one Java string (edit.rs:601-614). */
private fun Document.canonicalFragment(value: JavaString, isKey: Boolean): ByteArray {
    val text = canonicalJavaString(value, profile, isKey, parseLimits.common.maxSourceBytes)
    return try {
        encodeFragment(text, sourceOutputEncoding(), parseLimits.common.maxSourceBytes)
    } catch (e: MaterializationException) {
        throw EditFailureException(
            when (e.kind) {
                MaterializationFailureKind.RESOURCE_LIMIT -> EditFailure.ResourceLimit(e.name)
                else -> EditFailure.EncodingUnrepresentable
            },
        )
    }
}

/** One canonical `key=value<newline>` record with the document's newline
 * convention (edit.rs:616-663). */
private fun Document.canonicalRecord(position: Int, key: JavaString, value: JavaString): ByteArray {
    val newline = newlineConvention()
    val text = StringBuilder()
    if (position > 0 && !this.text.endsWithLineBreak(position)) {
        pushBounded(text, newline, parseLimits.common.maxSourceBytes)
    }
    pushBounded(
        text,
        canonicalJavaString(key, profile, true, parseLimits.common.maxSourceBytes),
        parseLimits.common.maxSourceBytes,
    )
    pushBounded(text, "=", parseLimits.common.maxSourceBytes)
    pushBounded(
        text,
        canonicalJavaString(value, profile, false, parseLimits.common.maxSourceBytes),
        parseLimits.common.maxSourceBytes,
    )
    pushBounded(text, newline, parseLimits.common.maxSourceBytes)
    return try {
        encodeFragment(text.toString(), sourceOutputEncoding(), parseLimits.common.maxSourceBytes)
    } catch (e: MaterializationException) {
        throw EditFailureException(
            when (e.kind) {
                MaterializationFailureKind.RESOURCE_LIMIT -> EditFailure.ResourceLimit(e.name)
                else -> EditFailure.EncodingUnrepresentable
            },
        )
    }
}

/** The first line terminator of the decoded source; `\n` when none exists
 * (edit.rs:665-683). */
private fun Document.newlineConvention(): String {
    val decoded = text.text
    for (index in decoded.indices) {
        if (decoded[index] == '\r') {
            return if (index + 1 < decoded.length && decoded[index + 1] == '\n') {
                "\r\n"
            } else {
                "\r"
            }
        }
        if (decoded[index] == '\n') {
            return "\n"
        }
    }
    return "\n"
}

/** Literal validation: bounded bytes, decodable, and exactly one raw value
 * element without line ownership (RFC 0010 §13; edit.rs:694-720). */
private fun Document.validateLiteral(literal: ByteArray) {
    if (literal.size > parseLimits.common.maxSourceBytes) {
        throw EditFailureException(EditFailure.ResourceLimit("replacement-bytes"))
    }
    val decoded = when (val encoding = sourceEncoding) {
        is PropertiesEncoding.Reader -> {
            val request = EncodingRequest.new(encoding.encoding)
                .withCallerOverride(encoding.encoding)
                .withBomPolicy(BomPolicy.TreatAsContent)
            val snapshot = try {
                SourceSnapshot.fromRaw(
                    literal,
                    request,
                    SourceLimits(
                        maxRawBytes = parseLimits.common.maxSourceBytes,
                        maxDecodedUtf8Bytes = parseLimits.maxDecodedUtf8Bytes,
                        maxDecodedScalars = parseLimits.maxDecodedScalars,
                    ),
                )
            } catch (e: consema.document.SourceException) {
                throw EditFailureException(EditFailure.InvalidLiteral)
            }
            snapshot.decodedText()!!
        }
        PropertiesEncoding.Latin1 -> {
            val request = EncodingRequest.new(SourceEncoding.Latin1)
                .withCallerOverride(SourceEncoding.Latin1)
                .withBomPolicy(BomPolicy.TreatAsContent)
            val snapshot = try {
                SourceSnapshot.fromRaw(
                    literal,
                    request,
                    SourceLimits(
                        maxRawBytes = parseLimits.common.maxSourceBytes,
                        maxDecodedUtf8Bytes = parseLimits.maxDecodedUtf8Bytes,
                        maxDecodedScalars = parseLimits.maxDecodedScalars,
                    ),
                )
            } catch (e: consema.document.SourceException) {
                throw EditFailureException(EditFailure.InvalidLiteral)
            }
            snapshot.decodedText()!!
        }
        is PropertiesEncoding.WindowsCodePage -> decodeCodePage(literal, encoding.number)
    }
    if (decoded.contains('\r') || decoded.contains('\n')) {
        throw EditFailureException(EditFailure.InvalidLiteral)
    }
}

/** The output encoding of this snapshot's source contract. */
internal fun Document.sourceOutputEncoding(): OutputEncoding =
    when (val encoding = sourceEncoding) {
        is PropertiesEncoding.Reader -> when (encoding.encoding) {
            SourceEncoding.Utf8 -> OutputEncoding.Utf8
            SourceEncoding.Utf16Le -> OutputEncoding.Utf16Le
            SourceEncoding.Utf16Be -> OutputEncoding.Utf16Be
            SourceEncoding.Latin1 -> OutputEncoding.Latin1
            SourceEncoding.Binary -> error("Properties Reader never selects Binary")
        }
        PropertiesEncoding.Latin1 -> OutputEncoding.Latin1
        is PropertiesEncoding.WindowsCodePage -> OutputEncoding.CodePage(encoding.number)
    }

/** The authorized canonical-fallback warning (edit.rs:722-730). */
private fun Document.canonicalFallbackDiagnostic(span: Span): Diagnostic =
    sourceDiagnostic(
        authority,
        "java-properties.edit.canonical-fallback@1",
        DiagnosticCategory.Edit,
        Severity.Warning,
        span.startByte,
        span.endByte,
        0uL,
    )

// ---------------------------------------------------------------------------
// Byte planning and expected-state verification
// ---------------------------------------------------------------------------

private fun validateNonOverlapping(prepared: List<PreparedEdit>) {
    for (index in 1 until prepared.size) {
        val left = prepared[index - 1]
        val right = prepared[index]
        if (left.oldSpan == right.oldSpan ||
            left.oldSpan.endByte > right.oldSpan.startByte ||
            (left.oldSpan.isEmpty && left.oldSpan.startByte == right.oldSpan.startByte) ||
            (right.oldSpan.isEmpty && left.oldSpan.endByte == right.oldSpan.startByte)
        ) {
            throw EditFailureException(EditFailure.OverlappingOwnership)
        }
    }
}

/** The closure failure class of one expected state (edit.rs:392-397). */
private fun closureFailureOf(expected: List<ExpectedProperty>): EditFailure =
    if (expected.any { it.literal }) {
        EditFailure.InvalidLiteral
    } else {
        EditFailure.NewDocumentFormationFailed
    }

private fun assembleExpected(
    old: List<ExpectedProperty>,
    insertions: TreeMap<Int, ExpectedProperty>,
): List<ExpectedProperty> {
    val output = ArrayList<ExpectedProperty>(old.size + insertions.size)
    for (boundary in 0..old.size) {
        insertions.remove(boundary)?.let { output.add(it) }
        old.getOrNull(boundary)?.takeIf { !it.removed }?.let { output.add(it) }
    }
    return output
}

/** Exact semantic verification of the reparse (edit.rs:810-833). */
private fun verifyExpected(document: Document, expected: List<ExpectedProperty>) {
    if (document.propertyEntities.size != expected.size) {
        throw EditFailureException(closureFailureOf(expected))
    }
    for ((actual, wanted) in document.propertyEntities.zip(expected)) {
        if (actual.key != wanted.key ||
            (wanted.value != null && actual.value != wanted.value)
        ) {
            throw EditFailureException(closureFailureOf(expected))
        }
    }
}

/** Renders the exact target bytes (edit.rs:732-760). */
private fun Document.applyPrepared(prepared: List<PreparedEdit>): ByteArray {
    var targetLen = source.len
    for (edit in prepared) {
        targetLen = targetLen - edit.oldSpan.len + edit.replacement.size
        if (targetLen > parseLimits.common.maxSourceBytes) {
            throw EditFailureException(EditFailure.ResourceLimit("target-bytes"))
        }
    }
    val output = ByteArray(targetLen)
    var cursor = 0
    var out = 0
    for (edit in prepared) {
        val keep = edit.oldSpan.startByte - cursor
        System.arraycopy(source.rawBytes(), cursor, output, out, keep)
        out += keep
        System.arraycopy(edit.replacement, 0, output, out, edit.replacement.size)
        out += edit.replacement.size
        cursor = edit.oldSpan.endByte
    }
    System.arraycopy(source.rawBytes(), cursor, output, out, source.len - cursor)
    return output
}

/** Old/new span pairs of the prepared edits (edit.rs:835-870). */
private fun buildSourceEdits(newDocument: Document, prepared: List<PreparedEdit>): List<SourceEdit> {
    val sourceEdits = ArrayList<SourceEdit>(prepared.size)
    var delta = 0
    for (edit in prepared) {
        val newStart = edit.oldSpan.startByte + delta
        val newEnd = newStart + edit.replacement.size
        val newSpan = try {
            newDocument.authority.span(newStart, newEnd)
        } catch (e: consema.document.LocationException) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
        sourceEdits.add(SourceEdit(edit.oldSpan, newSpan, edit.replacement))
        delta += edit.replacement.size - edit.oldSpan.len
    }
    return sourceEdits
}

/** Literal replacements must own exactly the value interval of the new
 * document (edit.rs:872-892). */
private fun verifyLiteralOwnership(
    document: Document,
    expected: List<ExpectedProperty>,
    sourceEdits: List<SourceEdit>,
) {
    for ((ordinal, wanted) in expected.withIndex()) {
        if (!wanted.literal) continue
        val oldSpan = wanted.literalOldSpan
            ?: throw EditFailureException(EditFailure.InvalidLiteral)
        val sourceEdit = sourceEdits.firstOrNull { it.oldSpan == oldSpan }
            ?: throw EditFailureException(EditFailure.InvalidLiteral)
        val actual = document.propertyEntity(ordinal)
        if (sourceEdit.newSpan != document.valueOwnership(actual)) {
            throw EditFailureException(EditFailure.InvalidLiteral)
        }
    }
}

/** Explicit old-to-new property mappings (edit.rs:894-923). */
private fun buildNodeMappings(
    document: Document,
    expected: List<ExpectedProperty>,
    transaction: EditTransaction,
): List<NodeMapping> =
    transaction.operations.mapNotNull { operation ->
        when (operation) {
            is EditOperation.RemoveProperty -> NodeMapping(
                old = operation.target,
                new = null,
                status = NodeMappingStatus.Deleted,
                reason = null,
            )
            is EditOperation.ReplaceSemanticValue,
            is EditOperation.ReplaceLiteralValue,
            is EditOperation.RenameProperty,
            -> {
                val old = operation.destructiveTarget()!!
                val ordinal = expected.indexOfFirst { it.old == old }
                    .takeIf { it >= 0 } ?: return@mapNotNull null
                NodeMapping(
                    old = old,
                    new = document.propertyEntity(ordinal).node,
                    status = NodeMappingStatus.Replaced,
                    reason = null,
                )
            }
            is EditOperation.InsertProperty -> null
        }
    }

// ---------------------------------------------------------------------------
// Canonical Java string escaping (edit.rs:925-1036)
// ---------------------------------------------------------------------------

private fun canonicalJavaString(
    value: JavaString,
    profile: PropertiesProfile,
    isKey: Boolean,
    limit: Int,
): String {
    val output = StringBuilder()
    val units = value.rawUnits()
    var index = 0
    var leadingValueSpace = !isKey
    while (index < units.size) {
        val unit = units[index].code
        val scalar: Int
        if (unit in 0xd800..0xdbff &&
            index + 1 < units.size && units[index + 1].code in 0xdc00..0xdfff
        ) {
            scalar = 0x10000 + ((unit - 0xd800) shl 10) + (units[index + 1].code - 0xdc00)
            index += 2
        } else if (unit in 0xd800..0xdfff) {
            index += 1
            pushUnicodeEscape(output, unit, limit)
            leadingValueSpace = false
            continue
        } else {
            scalar = unit
            index += 1
        }
        when {
            scalar == 0x20 && (isKey || leadingValueSpace) -> pushBounded(output, "\\ ", limit)
            scalar == 0x09 -> pushBounded(output, "\\t", limit)
            scalar == 0x0a -> pushBounded(output, "\\n", limit)
            scalar == 0x0d -> pushBounded(output, "\\r", limit)
            scalar == 0x0c -> pushBounded(output, "\\f", limit)
            scalar == 0x5c -> pushBounded(output, "\\\\", limit)
            scalar == 0x23 || scalar == 0x21 || scalar == 0x3d || scalar == 0x3a -> {
                pushBounded(output, "\\", limit)
                pushScalarBounded(output, scalar, limit)
            }
            Character.isISOControl(scalar) -> {
                for (unitValue in String(Character.toChars(scalar)).toCharArray()) {
                    pushUnicodeEscape(output, unitValue.code, limit)
                }
            }
            profile == PropertiesProfile.Latin1V1 && scalar !in 0x20..0x7e -> {
                for (unitValue in String(Character.toChars(scalar)).toCharArray()) {
                    pushUnicodeEscape(output, unitValue.code, limit)
                }
            }
            else -> pushScalarBounded(output, scalar, limit)
        }
        if (scalar != 0x20) {
            leadingValueSpace = false
        }
    }
    return output.toString()
}

private fun pushUnicodeEscape(output: StringBuilder, unit: Int, limit: Int) {
    val digits = "0123456789ABCDEF"
    pushBounded(
        output,
        "\\u${digits[(unit ushr 12) and 0xf]}${digits[(unit ushr 8) and 0xf]}" +
            "${digits[(unit ushr 4) and 0xf]}${digits[unit and 0xf]}",
        limit,
    )
}

private fun pushScalarBounded(output: StringBuilder, scalar: Int, limit: Int) {
    pushBounded(output, String(Character.toChars(scalar)), limit)
}

private fun pushBounded(output: StringBuilder, text: String, limit: Int) {
    val newLength = output.length + text.length
    if (newLength > limit) {
        throw EditFailureException(EditFailure.ResourceLimit("replacement-bytes"))
    }
    output.append(text)
}

// ---------------------------------------------------------------------------
// Patch metadata and summaries
// ---------------------------------------------------------------------------

/** Patch metadata: operation.{index} -> frozen operation id@version
 * (edit.rs:1077-1089). */
private fun operationMetadata(transaction: EditTransaction): Map<String, String> {
    val metadata = LinkedHashMap<String, String>()
    for ((index, operation) in transaction.operations.withIndex()) {
        metadata["operation.$index"] = operationId(operation).toString()
    }
    return metadata
}

/** The frozen operation id@version (edit.rs:1140-1148). */
internal fun operationId(operation: EditOperation): FormatOperationId =
    FormatOperationId(
        when (operation) {
            is EditOperation.ReplaceSemanticValue -> "java-properties.edit.replace-semantic-value"
            is EditOperation.ReplaceLiteralValue -> "java-properties.edit.replace-literal-value"
            is EditOperation.InsertProperty -> "java-properties.edit.insert-property"
            is EditOperation.RemoveProperty -> "java-properties.edit.remove-property"
            is EditOperation.RenameProperty -> "java-properties.edit.rename-property"
        },
        1,
    )

private fun placementName(placement: AssociationPlacement): String =
    when (placement) {
        AssociationPlacement.Start -> "start"
        AssociationPlacement.End -> "end"
        is AssociationPlacement.Before -> "before"
        is AssociationPlacement.After -> "after"
    }

/** The patch limits of one commit (edit.rs:1062-1075). */
private fun sourcePatchLimits(
    limits: PropertiesParseLimits,
    operationCount: Int,
): consema.document.SourcePatchLimits =
    consema.document.SourcePatchLimits(
        source = SourceLimits(
            maxRawBytes = limits.common.maxSourceBytes,
            maxDecodedUtf8Bytes = limits.maxDecodedUtf8Bytes,
            maxDecodedScalars = limits.maxDecodedScalars,
        ),
        maxReplacements = operationCount,
        maxPatchBytes = limits.common.maxSourceBytes * 2,
    )
