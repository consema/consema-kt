// Scalar and structural edit operations: one immutable transaction, atomic
// commit, dry-run plan, untouched-byte proof, and SourcePatch derivation.
//
// Data authority:
//   - RFC 0007 §12 (docs/rfcs/0007-yaml-family-profiles-and-safety-v1.md:
//     355-399): the eight operation ids; transactions are snapshot-bound and
//     validate all operations before publishing a candidate; common edits
//     retain indentation, flow/block style, scalar style, comments, line
//     endings, delimiters, and untouched raw bytes where compatible;
//     fallback to canonical local spelling is explicit and reported; v1
//     accepts at most one structural mutation per base container; the
//     anchor-safe rules (renaming updates dependent aliases in one
//     transaction; removing an anchored definition while aliases remain is
//     rejected; removing an alias does not remove its target; inserting an
//     alias requires an earlier visible anchor; scalar edits of anchored
//     nodes change the shared graph node).
//   - RFC 0004 §13-§16 (docs/rfcs/0004-materialization-conversion-and-
//     structural-edit-v1.md:271-386): the conflict algebra, the dry-run
//     plan, the untouched-byte proof, the derived SourcePatch.
//   - conformance/vectors/yaml-v1.json pins the golden outputs
//     (edit.scalar-atomic, edit.anchor-rename, edit.structural-insert,
//     edit.anchor-dependency at lines 106-124).
//   - crates/consema-yaml/src/edit.rs is the byte-arbitration authority
//     (commit edit.rs:401-551, dry-run edit.rs:554-568, prepare edit.rs:
//     570-1344, anchor rules edit.rs:1346-1442, validation edit.rs:
//     1444-2014, candidate model edit.rs:2017-2324, literal preservation
//     edit.rs:2326-2452, metadata edit.rs:2577-2697).
//
// Kotlin-idiomatic design: failures are a sealed hierarchy whose [name] is
// the exact vector spelling; commit/dry-run throw the typed
// [EditFailureException] so callers match exhaustively on the failure class.
// ChangeSet is an L4 document milestone in Kotlin (json/Edit.kt:244-255,
// toml/Edit.kt:349-369), so this commit carries the ordered diagnostics and
// the old-to-new node mapping facts instead.

package consema.yaml

import consema.core.Kind
import consema.core.PortableValue
import consema.document.AssociationPlacement
import consema.document.EditOperationSummary
import consema.document.EditPlan
import consema.document.EditPlanSourceId
import consema.document.FormatOperationId
import consema.document.MaterializationLimits
import consema.document.MaterializationRequest
import consema.document.MaterializationResult
import consema.document.MaterializationStyleId
import consema.document.NodeRef
import consema.document.NodeRole
import consema.document.ParseLimits
import consema.document.SnapshotIdentity
import consema.document.SourceEncoding
import consema.document.SourceLimits
import consema.document.SourcePatch
import consema.document.SourcePatchLimits
import consema.document.SourceReplacement
import consema.document.Span
import consema.document.UntouchedByteProof
import consema.protocol.Diagnostic
import consema.protocol.DiagnosticCategory
import consema.protocol.Severity

/** Explicit semantic scalar representation policy (edit.rs:21-32). */
enum class RepresentationPolicy {
    /** Caller must use an exact literal operation instead. */
    ExactLiteral,

    /** Retain the target scalar category and presentation style or fail. */
    PreserveCompatible,

    /** Use the frozen canonical YAML scalar representation. */
    CanonicalForProfile,

    /** Preserve when compatible, otherwise report canonical fallback. */
    PreserveElseCanonical,
}

/** One scalar operation bound to the transaction's base snapshot (edit.rs:
 * 34-61). */
sealed class ScalarReplacement {
    /** Exact target NodeRef. */
    abstract val target: NodeRef

    /** Replace by a complete core scalar under an explicit policy. */
    data class Semantic(
        override val target: NodeRef,
        /** New complete core scalar. */
        val value: PortableValue,
        /** Representation contract. */
        val policy: RepresentationPolicy,
    ) : ScalarReplacement()

    /** Replace only the exact scalar literal bytes after profile
     * validation. */
    data class Literal(
        override val target: NodeRef,
        /** Candidate bytes in the base document's selected encoding. */
        val literal: ByteArray,
    ) : ScalarReplacement()
}

/** One typed YAML edit operation bound to an immutable base snapshot
 * (edit.rs:63-114). */
sealed class EditOperation {
    /** Existing scalar semantic or literal replacement. */
    data class ReplaceScalar(val replacement: ScalarReplacement) : EditOperation()

    /** Rename one anchor definition and all aliases that resolve to it. */
    data class RenameAnchor(
        /** Exact YAML anchor-definition target. */
        val target: NodeRef,
        /** New decoded anchor name. */
        val name: String,
    ) : EditOperation()

    /** Insert one arbitrary-key mapping association using canonical flow
     * fragments. */
    data class InsertMappingEntry(
        /** Mapping representation node to mutate. */
        val mapping: NodeRef,
        /** Complete portable key. */
        val key: PortableValue,
        /** Complete portable value. */
        val value: PortableValue,
        /** Snapshot-bound association placement. */
        val placement: AssociationPlacement,
    ) : EditOperation()

    /** Remove one exact mapping association. */
    data class RemoveMappingEntry(val target: NodeRef) : EditOperation()

    /** Insert one sequence association using a canonical flow fragment. */
    data class InsertSequenceElement(
        /** Sequence representation node to mutate. */
        val sequence: NodeRef,
        /** Complete portable element value. */
        val value: PortableValue,
        /** Snapshot-bound association placement. */
        val placement: AssociationPlacement,
    ) : EditOperation()

    /** Remove one exact sequence association. */
    data class RemoveSequenceElement(val target: NodeRef) : EditOperation()

    /** Insert an alias edge into a sequence without expanding its target. */
    data class InsertAlias(
        /** Sequence representation node to mutate. */
        val sequence: NodeRef,
        /** Earlier visible anchor definition to reference. */
        val anchor: NodeRef,
        /** Snapshot-bound association placement. */
        val placement: AssociationPlacement,
    ) : EditOperation()
}

/** Immutable transaction; every operation resolves against one base snapshot
 * (edit.rs:116-135). */
class EditTransaction internal constructor(
    /** Base snapshot identity. */
    val baseSnapshot: SnapshotIdentity,
    /** Ordered declared operations. */
    val operations: List<EditOperation>,
)

/** Builder that is not a committed edit (edit.rs:137-258). */
class EditTransactionBuilder internal constructor(private val base: SnapshotIdentity) {
    private val operations = ArrayList<EditOperation>()

    companion object {
        /** Binds a new transaction to one immutable base document
         * (edit.rs:145-152). */
        fun new(document: Document): EditTransactionBuilder =
            EditTransactionBuilder(document.snapshotIdentity)
    }

    /** Adds one semantic scalar replacement (edit.rs:154-167). */
    fun semanticScalar(
        target: NodeRef,
        value: PortableValue,
        policy: RepresentationPolicy,
    ): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceScalar(ScalarReplacement.Semantic(target, value, policy)))
        return this
    }

    /** Adds one exact literal scalar replacement (edit.rs:169-178). */
    fun literalScalar(target: NodeRef, literal: ByteArray): EditTransactionBuilder {
        operations.add(EditOperation.ReplaceScalar(ScalarReplacement.Literal(target, literal)))
        return this
    }

    /** Adds one anchor rename that also updates every dependent alias
     * (edit.rs:180-187). */
    fun renameAnchor(target: NodeRef, name: String): EditTransactionBuilder {
        operations.add(EditOperation.RenameAnchor(target, name))
        return this
    }

    /** Adds one arbitrary-key mapping association insertion (edit.rs:
     * 189-204). */
    fun insertMappingEntry(
        mapping: NodeRef,
        key: PortableValue,
        value: PortableValue,
        placement: AssociationPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertMappingEntry(mapping, key, value, placement))
        return this
    }

    /** Adds one exact mapping-association removal (edit.rs:206-211). */
    fun removeMappingEntry(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveMappingEntry(target))
        return this
    }

    /** Adds one sequence value insertion (edit.rs:213-225). */
    fun insertSequenceElement(
        sequence: NodeRef,
        value: PortableValue,
        placement: AssociationPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertSequenceElement(sequence, value, placement))
        return this
    }

    /** Adds one exact sequence-association removal (edit.rs:227-233). */
    fun removeSequenceElement(target: NodeRef): EditTransactionBuilder {
        operations.add(EditOperation.RemoveSequenceElement(target))
        return this
    }

    /** Adds one sequence alias insertion to an earlier visible anchor
     * (edit.rs:235-247). */
    fun insertAlias(
        sequence: NodeRef,
        anchor: NodeRef,
        placement: AssociationPlacement,
    ): EditTransactionBuilder {
        operations.add(EditOperation.InsertAlias(sequence, anchor, placement))
        return this
    }

    /** Completes the immutable request; validation happens atomically at
     * commit (edit.rs:249-257). */
    fun build(): EditTransaction = EditTransaction(base, operations.toList())
}

/** Atomic edit success (edit.rs:260-271). ChangeSet is an L4 milestone in
 * Kotlin; this commit carries the ordered edit diagnostics and the
 * old-to-new node mapping facts instead (json/Edit.kt:244-255). */
class EditCommit(
    /** New immutable document. */
    val document: Document,
    /** Portable exact raw-byte application fact. */
    val sourcePatch: SourcePatch,
    /** Verifiable evidence for every byte outside the replacement set. */
    val untouchedProof: UntouchedByteProof,
    /** Ordered edit diagnostics (fallback events). */
    val diagnostics: List<Diagnostic>,
    /** Old-to-new node mapping facts (the Rust ChangeSet node mappings of
     * the L4 milestone; edit.rs:444-523). */
    val nodeMappings: List<YamlNodeMapping>,
)

/** One old-to-new node mapping fact (the Rust NodeMapping of the L4
 * ChangeSet; edit.rs:498-513). */
data class YamlNodeMapping(
    /** Base structural identity. */
    val old: NodeRef,
    /** Target structural identity; null when deleted or unmapped. */
    val new: NodeRef?,
    /** Mapping status. */
    val status: YamlNodeMappingStatus,
    /** Stable reason for a non-Replaced mapping. */
    val reason: String?,
)

/** Node mapping status (the Rust NodeMappingStatus). */
enum class YamlNodeMappingStatus {
    /** The target identity exists and is mapped. */
    Replaced,

    /** The base identity was deliberately removed. */
    Deleted,

    /** The base identity could not be located in the reparsed target. */
    Unmapped,
}

/** Stable YAML edit validation or commit failure (edit.rs:273-314). The
 * [name] is the exact vector spelling; [code] is the frozen registered
 * code (edit.rs:318-343). */
sealed class EditFailure(val name: String) {
    /** Transaction or target belongs to another snapshot. */
    data object WrongSnapshot : EditFailure("WrongSnapshot")

    /** Target role does not match the selected operation. */
    data object WrongRole : EditFailure("WrongRole")

    /** Target identity is not present in the base document. */
    data object TargetNotFound : EditFailure("TargetNotFound")

    /** Target is not one complete editable scalar or anchor occurrence. */
    data object IncompleteTarget : EditFailure("IncompleteTarget")

    /** Public value cannot be represented as a YAML scalar. */
    data class UnsupportedSemanticValue(val kind: Kind) : EditFailure("UnsupportedSemanticValue")

    /** Exact candidate is not one complete scalar literal. */
    data object InvalidLiteral : EditFailure("InvalidLiteral")

    /** PreserveCompatible could not retain category and presentation
     * style. */
    data object RepresentationIncompatible : EditFailure("RepresentationIncompatible")

    /** ExactLiteral was requested without an exact literal operation. */
    data object ExactLiteralRequiresLiteralOperation :
        EditFailure("ExactLiteralRequiresLiteralOperation")

    /** New anchor name is not accepted as one exact anchor property. */
    data object InvalidAnchorName : EditFailure("InvalidAnchorName")

    /** Placement anchor is from another container or has the wrong
     * association role. */
    data object InvalidPlacement : EditFailure("InvalidPlacement")

    /** Inserted alias target is not the last visible definition of its
     * name. */
    data object AnchorNotVisible : EditFailure("AnchorNotVisible")

    /** Removal would leave an alias whose anchor definition no longer
     * exists. */
    data object AnchorDependency : EditFailure("AnchorDependency")

    /** Portable input cannot be represented exactly by the YAML value
     * materializer. */
    data class UnsupportedInsertedValue(val kind: Kind) : EditFailure("UnsupportedInsertedValue")

    /** More than one structural mutation targets the same base container. */
    data object StructuralContainerConflict : EditFailure("StructuralContainerConflict")

    /** More than one operation names the same destructive target. */
    data object DuplicateTarget : EditFailure("DuplicateTarget")

    /** Prepared source ownership intervals overlap or reuse an insertion
     * point. */
    data object OverlappingOwnership : EditFailure("OverlappingOwnership")

    /** One operation edits an ancestor/descendant region of another
     * operation. */
    data object AncestorDescendantConflict : EditFailure("AncestorDescendantConflict")

    /** A configured edit or output bound was exceeded. */
    data class ResourceLimit(val name: String) : EditFailure("ResourceLimit")

    /** Replacement bytes did not form the promised YAML document and
     * topology. */
    data object NewDocumentFormationFailed : EditFailure("NewDocumentFormationFailed")

    /** The frozen registered code of the failure (edit.rs:318-343). */
    val code: String
        get() = when (this) {
            is WrongSnapshot -> "core.edit.wrong-snapshot@1"
            is WrongRole -> "core.edit.wrong-role@1"
            is TargetNotFound -> "core.edit.target-not-found@1"
            is IncompleteTarget -> "core.edit.incomplete-target@1"
            is UnsupportedSemanticValue, is UnsupportedInsertedValue -> "core.edit.unsupported-value@1"
            is InvalidLiteral -> "core.edit.invalid-literal@1"
            is RepresentationIncompatible -> "core.edit.representation-incompatible@1"
            is ExactLiteralRequiresLiteralOperation -> "core.edit.exact-literal-requires-literal@1"
            is InvalidAnchorName -> "yaml.edit.invalid-anchor-name@1"
            is InvalidPlacement -> "yaml.edit.invalid-placement@1"
            is AnchorNotVisible -> "yaml.edit.anchor-not-visible@1"
            is AnchorDependency -> "yaml.edit.anchor-dependency@1"
            is StructuralContainerConflict -> "yaml.edit.structural-container-conflict@1"
            is DuplicateTarget, is OverlappingOwnership, is AncestorDescendantConflict ->
                "core.edit.conflicting-edits@1"
            is ResourceLimit -> "core.edit.resource-limit@1"
            is NewDocumentFormationFailed -> "core.edit.formation-failed@1"
        }
}

/** The typed edit failure thrown by [Document.commit] and
 * [Document.dryRun]. */
class EditFailureException(val failure: EditFailure) :
    Exception("edit: ${failure.name}")

/** One prepared byte edit (edit.rs:380-385). */
private data class PreparedEdit(
    val oldSpan: Span,
    val replacement: ByteArray,
    val mapping: Pair<NodeRef, MappingPlan>? = null,
)

/** The old-to-new mapping plan of one prepared edit (edit.rs:393-399). */
private sealed class MappingPlan {
    data class Node(val index: Int) : MappingPlan()
    data class Anchor(val index: Int) : MappingPlan()
    data class Alias(val ordinal: Int) : MappingPlan()
    data object Removed : MappingPlan()
}

/** The validated old-to-new identity map of a reparsed candidate
 * (edit.rs:386-391). */
private class CandidateMap {
    val nodes = HashMap<Int, Int>()
    val aliases = HashMap<Int, Int>()
}

private class SourceEdit(
    val oldSpan: Span,
    val newSpan: Span,
    val replacement: ByteArray,
)

/**
 * Atomically commits validated YAML scalar, collection, anchor, and alias
 * operations (edit.rs:401-551). On failure the document remains unchanged;
 * a failure returns none of the successful artifacts (RFC 0004 §13).
 */
fun Document.commit(transaction: EditTransaction): EditCommit {
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
    validatePreparedOwnership(prepared)
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
    val baseBytes = source.rawBytes()
    for (edit in prepared) {
        val keep = edit.oldSpan.startByte - cursor
        System.arraycopy(baseBytes, cursor, rendered, out, keep)
        out += keep
        System.arraycopy(edit.replacement, 0, rendered, out, edit.replacement.size)
        out += edit.replacement.size
        cursor = edit.oldSpan.endByte
    }
    System.arraycopy(baseBytes, cursor, rendered, out, baseBytes.size - cursor)
    val newDocument = try {
        parse(rendered, profile, parseLimits)
    } catch (e: YamlFormationException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    val candidateMap = validateCandidate(newDocument, transaction)

    var delta = 0
    val sourceEdits = ArrayList<SourceEdit>()
    val mappings = ArrayList<YamlNodeMapping>()
    val mappedOld = HashSet<NodeRef>()
    for (edit in prepared) {
        val newStart = edit.oldSpan.startByte + delta
        val newEnd = newStart + edit.replacement.size
        val newSpan = newDocument.authority.span(newStart, newEnd)
        sourceEdits.add(SourceEdit(edit.oldSpan, newSpan, edit.replacement))
        val mapping = edit.mapping
        if (mapping != null && mappedOld.add(mapping.first)) {
            val newRef = when (val plan = mapping.second) {
                is MappingPlan.Node -> candidateMap.nodes[plan.index]
                    ?.let { nodeRef(newDocument.authority, it) }
                is MappingPlan.Anchor -> candidateMap.nodes[plan.index]
                    ?.let { index ->
                        newDocument.native.nodes.getOrNull(index)
                            ?.takeIf { it.anchor != null }
                            ?.let {
                                newDocument.authority.nodeRef(index.toLong(), NodeRole.YamlAnchorDefinition)
                            }
                    }
                is MappingPlan.Alias -> candidateMap.aliases[plan.ordinal]
                    ?.let { newDocument.alias(it) }
                    ?.let { it.nodeRef() }
                is MappingPlan.Removed -> null
            }
            val status = if (mapping.second is MappingPlan.Removed) {
                YamlNodeMappingStatus.Deleted
            } else if (newRef != null) {
                YamlNodeMappingStatus.Replaced
            } else {
                YamlNodeMappingStatus.Unmapped
            }
            val reason = when {
                mapping.second is MappingPlan.Removed -> "association-removed-by-declared-operation"
                newRef == null -> "reparsed-node-not-uniquely-located"
                else -> null
            }
            mappings.add(YamlNodeMapping(mapping.first, newRef, status, reason))
        }
        delta += edit.replacement.size - edit.oldSpan.len
    }
    val sourcePatch = try {
        SourcePatch.create(
            source,
            sourceEdits.map {
                SourceReplacement.new(
                    it.oldSpan.startByte,
                    it.oldSpan.endByte,
                    baseBytes.copyOfRange(it.oldSpan.startByte, it.oldSpan.endByte),
                    it.replacement,
                )
            },
            operationMetadata(transaction),
            sourcePatchLimits(parseLimits, sourceEdits.size),
        )
    } catch (e: consema.document.SourcePatchException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    val untouchedProof = try {
        UntouchedByteProof.create(source, newDocument.source, sourcePatch.replacements())
    } catch (e: consema.document.UntouchedByteProofException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    return EditCommit(newDocument, sourcePatch, untouchedProof, diagnostics, mappings)
}

/** Fully validates and plans an edit without returning a new Document
 * (edit.rs:554-568). */
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
            commit.diagnostics,
        )
    } catch (e: consema.document.EditPlanException) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
}

private fun Document.prepareOperation(
    operation: EditOperation,
    diagnostics: MutableList<Diagnostic>,
): List<PreparedEdit> =
    when (operation) {
        is EditOperation.ReplaceScalar -> prepareScalar(operation.replacement, diagnostics)
        is EditOperation.RenameAnchor -> prepareAnchorRename(operation.target, operation.name)
        is EditOperation.InsertMappingEntry ->
            prepareMappingInsertion(operation.mapping, operation.key, operation.value, operation.placement)
        is EditOperation.RemoveMappingEntry -> prepareMappingRemoval(operation.target)
        is EditOperation.InsertSequenceElement ->
            prepareSequenceInsertion(operation.sequence, operation.value, operation.placement)
        is EditOperation.RemoveSequenceElement -> prepareSequenceRemoval(operation.target)
        is EditOperation.InsertAlias ->
            prepareAliasInsertion(operation.sequence, operation.anchor, operation.placement)
    }

private fun Document.prepareScalar(
    operation: ScalarReplacement,
    diagnostics: MutableList<Diagnostic>,
): List<PreparedEdit> {
    val target = operation.target
    val index = resolveNode(target, NodeRole.YamlNode)
    val node = native.nodes[index]
    val content = node.content as? NativeContent.Scalar
        ?: throw EditFailureException(EditFailure.WrongRole)
    val literalSpan = scalarLiteralSpan(index)
        ?: throw EditFailureException(EditFailure.IncompleteTarget)
    return when (operation) {
        is ScalarReplacement.Literal -> {
            validateLiteral(operation.literal)
            listOf(
                PreparedEdit(
                    literalSpan,
                    operation.literal.copyOf(),
                    target to MappingPlan.Node(index),
                ),
            )
        }
        is ScalarReplacement.Semantic -> {
            if (!isScalarValue(operation.value.kind)) {
                throw EditFailureException(EditFailure.UnsupportedSemanticValue(operation.value.kind))
            }
            if (operation.policy == RepresentationPolicy.ExactLiteral) {
                throw EditFailureException(EditFailure.ExactLiteralRequiresLiteralOperation)
            }
            val canonical = canonicalScalarFragment(operation.value)
            val preserve = {
                preservedLiteral(
                    content.scalar.kind,
                    content.scalar.style,
                    node.tag,
                    tagSpan(index) != null,
                    canonical,
                    operation.value.kind,
                    profile,
                )
            }
            when (operation.policy) {
                RepresentationPolicy.PreserveCompatible -> {
                    val text = preserve()
                        ?: throw EditFailureException(EditFailure.RepresentationIncompatible)
                    listOf(
                        PreparedEdit(
                            literalSpan,
                            encodeFragment(text),
                            target to MappingPlan.Node(index),
                        ),
                    )
                }
                RepresentationPolicy.CanonicalForProfile ->
                    canonicalScalarEdits(index, target, literalSpan, canonical)
                RepresentationPolicy.PreserveElseCanonical -> {
                    val text = preserve()
                    if (text != null) {
                        listOf(
                            PreparedEdit(
                                literalSpan,
                                encodeFragment(text),
                                target to MappingPlan.Node(index),
                            ),
                        )
                    } else {
                        pushFallbackDiagnostic(diagnostics, literalSpan)
                        canonicalScalarEdits(index, target, literalSpan, canonical)
                    }
                }
                RepresentationPolicy.ExactLiteral -> error("handled above")
            }
        }
    }
}

private fun Document.canonicalScalarEdits(
    index: Int,
    target: NodeRef,
    literalSpan: Span,
    canonical: CanonicalScalar,
): List<PreparedEdit> {
    val edits = ArrayList<PreparedEdit>()
    val encodedLiteral = encodeFragment(canonical.literal)
    val tag = tagSpan(index)
    if (tag != null) {
        edits.add(PreparedEdit(tag, encodeFragment(canonical.tag), null))
        edits.add(PreparedEdit(literalSpan, encodedLiteral, target to MappingPlan.Node(index)))
    } else {
        edits.add(
            PreparedEdit(
                literalSpan,
                encodeFragment("${canonical.tag} ${canonical.literal}"),
                target to MappingPlan.Node(index),
            ),
        )
    }
    return edits
}

private fun Document.prepareAnchorRename(target: NodeRef, name: String): List<PreparedEdit> {
    val index = resolveNode(target, NodeRole.YamlAnchorDefinition)
    validateAnchorName(name)
    val node = native.nodes[index]
    val oldName = node.anchor
        ?: throw EditFailureException(EditFailure.WrongRole)
    val definition = node.anchorSpan
        ?: throw EditFailureException(EditFailure.IncompleteTarget)
    val edits = ArrayList<PreparedEdit>()
    edits.add(
        PreparedEdit(
            definition,
            encodeFragment("&$name"),
            target to MappingPlan.Anchor(index),
        ),
    )
    for ((ordinal, alias) in native.aliases.withIndex()) {
        if (alias.target == index && alias.name == oldName) {
            edits.add(
                PreparedEdit(
                    alias.span,
                    encodeFragment("*$name"),
                    authority.nodeRef(alias.identity, NodeRole.YamlAlias) to MappingPlan.Alias(ordinal),
                ),
            )
        }
    }
    return edits
}

private fun Document.prepareMappingInsertion(
    mapping: NodeRef,
    key: PortableValue,
    value: PortableValue,
    placement: AssociationPlacement,
): List<PreparedEdit> {
    val index = resolveNode(mapping, NodeRole.YamlNode)
    val entries = (native.nodes[index].content as? NativeContent.Mapping)?.entries
        ?: throw EditFailureException(EditFailure.WrongRole)
    val ordinal = mappingPlacement(index, entries, placement)
    val keyFragment = canonicalValueFragment(key)
    val valueFragment = canonicalValueFragment(value)
    val fragment = "? $keyFragment : $valueFragment"
    val blockLines = listOf("? $keyFragment", ": $valueFragment")
    val spans = entries.map { associationSpan(it.span) }
    val (oldSpan, replacement) = prepareCollectionInsertion(
        index,
        spans,
        ordinal,
        fragment,
        blockLines,
        YamlSyntaxKind.FlowMappingStart,
        YamlSyntaxKind.FlowMappingEnd,
    )
    return listOf(PreparedEdit(oldSpan, replacement, mapping to MappingPlan.Node(index)))
}

private fun Document.prepareSequenceInsertion(
    sequence: NodeRef,
    value: PortableValue,
    placement: AssociationPlacement,
): List<PreparedEdit> {
    val index = resolveNode(sequence, NodeRole.YamlNode)
    val items = (native.nodes[index].content as? NativeContent.Sequence)?.items
        ?: throw EditFailureException(EditFailure.WrongRole)
    val ordinal = sequencePlacement(index, items, placement)
    val fragment = canonicalValueFragment(value)
    val blockLines = listOf("- $fragment")
    val spans = items.map { associationSpan(it.span) }
    val (oldSpan, replacement) = prepareCollectionInsertion(
        index,
        spans,
        ordinal,
        fragment,
        blockLines,
        YamlSyntaxKind.FlowSequenceStart,
        YamlSyntaxKind.FlowSequenceEnd,
    )
    return listOf(PreparedEdit(oldSpan, replacement, sequence to MappingPlan.Node(index)))
}

private fun Document.prepareAliasInsertion(
    sequence: NodeRef,
    anchor: NodeRef,
    placement: AssociationPlacement,
): List<PreparedEdit> {
    val sequenceIndex = resolveNode(sequence, NodeRole.YamlNode)
    val anchorIndex = resolveNode(anchor, NodeRole.YamlAnchorDefinition)
    val items = (native.nodes[sequenceIndex].content as? NativeContent.Sequence)?.items
        ?: throw EditFailureException(EditFailure.WrongRole)
    val ordinal = sequencePlacement(sequenceIndex, items, placement)
    val spans = items.map { associationSpan(it.span) }
    val insertion = collectionInsertionPoint(
        sequenceIndex,
        spans,
        ordinal,
        YamlSyntaxKind.FlowSequenceStart,
        YamlSyntaxKind.FlowSequenceEnd,
    )
    validateVisibleAnchor(sequenceIndex, anchorIndex, insertion)
    val name = native.nodes[anchorIndex].anchor
        ?: throw EditFailureException(EditFailure.WrongRole)
    val (oldSpan, replacement) = prepareCollectionInsertionAt(
        sequenceIndex,
        spans,
        ordinal,
        "*$name",
        listOf("- *$name"),
        YamlSyntaxKind.FlowSequenceStart,
        YamlSyntaxKind.FlowSequenceEnd,
        insertion,
    )
    return listOf(
        PreparedEdit(oldSpan, replacement, sequence to MappingPlan.Node(sequenceIndex)),
    )
}

private fun Document.prepareMappingRemoval(target: NodeRef): List<PreparedEdit> {
    val (container, ordinal) = resolveMappingEntry(target)
    val entries = (native.nodes[container].content as NativeContent.Mapping).entries
    val spans = entries.map { associationSpan(it.span) }
    val owned = collectionRemovalSpan(
        container,
        spans,
        ordinal,
        YamlSyntaxKind.FlowMappingStart,
        YamlSyntaxKind.FlowMappingEnd,
    )
    validateRemovalDependencies(
        owned,
        listOf(
            entries[ordinal].key to entries[ordinal].keyAlias,
            entries[ordinal].value to entries[ordinal].valueAlias,
        ),
    )
    val replacement = if (entries.size == 1 &&
        !collectionIsFlow(container, YamlSyntaxKind.FlowMappingStart)
    ) {
        emptyBlockReplacement(owned, spans[ordinal], "{}")
    } else {
        ByteArray(0)
    }
    return listOf(PreparedEdit(owned, replacement, target to MappingPlan.Removed))
}

private fun Document.prepareSequenceRemoval(target: NodeRef): List<PreparedEdit> {
    val (container, ordinal) = resolveSequenceItem(target)
    val items = (native.nodes[container].content as NativeContent.Sequence).items
    val spans = items.map { associationSpan(it.span) }
    val owned = collectionRemovalSpan(
        container,
        spans,
        ordinal,
        YamlSyntaxKind.FlowSequenceStart,
        YamlSyntaxKind.FlowSequenceEnd,
    )
    validateRemovalDependencies(owned, listOf(items[ordinal].node to items[ordinal].alias))
    val replacement = if (items.size == 1 &&
        !collectionIsFlow(container, YamlSyntaxKind.FlowSequenceStart)
    ) {
        emptyBlockReplacement(owned, spans[ordinal], "[]")
    } else {
        ByteArray(0)
    }
    return listOf(PreparedEdit(owned, replacement, target to MappingPlan.Removed))
}

private fun Document.mappingPlacement(
    expected: Int,
    entries: List<NativeMappingEntry>,
    placement: AssociationPlacement,
): Int =
    when (placement) {
        AssociationPlacement.Start -> 0
        AssociationPlacement.End -> entries.size
        is AssociationPlacement.Before, is AssociationPlacement.After -> {
            val (container, ordinal) = resolveMappingEntry(placement.anchor)
            if (container != expected) {
                throw EditFailureException(EditFailure.InvalidPlacement)
            }
            if (placement is AssociationPlacement.After) ordinal + 1 else ordinal
        }
    }

private fun Document.sequencePlacement(
    expected: Int,
    items: List<NativeSequenceItem>,
    placement: AssociationPlacement,
): Int =
    when (placement) {
        AssociationPlacement.Start -> 0
        AssociationPlacement.End -> items.size
        is AssociationPlacement.Before, is AssociationPlacement.After -> {
            val (container, ordinal) = resolveSequenceItem(placement.anchor)
            if (container != expected) {
                throw EditFailureException(EditFailure.InvalidPlacement)
            }
            if (placement is AssociationPlacement.After) ordinal + 1 else ordinal
        }
    }

private fun Document.resolveMappingEntry(target: NodeRef): Pair<Int, Int> {
    if (target.snapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (target.role != NodeRole.YamlMappingEntry) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    val identity = target.index
    for ((container, node) in native.nodes.withIndex()) {
        val content = node.content as? NativeContent.Mapping ?: continue
        val ordinal = content.entries.indexOfFirst { it.identity == identity }
        if (ordinal >= 0) {
            return container to ordinal
        }
    }
    throw EditFailureException(EditFailure.TargetNotFound)
}

private fun Document.resolveSequenceItem(target: NodeRef): Pair<Int, Int> {
    if (target.snapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (target.role != NodeRole.YamlSequenceElement) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    val identity = target.index
    for ((container, node) in native.nodes.withIndex()) {
        val content = node.content as? NativeContent.Sequence ?: continue
        val ordinal = content.items.indexOfFirst { it.identity == identity }
        if (ordinal >= 0) {
            return container to ordinal
        }
    }
    throw EditFailureException(EditFailure.TargetNotFound)
}

/** Extends an association span back over its tag/anchor/explicit-key
 * properties (edit.rs:1018-1051). */
private fun Document.associationSpan(span: Span): Span {
    val pieces = structuralIndex.pieces()
    var start = span.startByte
    while (true) {
        val index = pieces.indexOfLast { it.span.endByte == start }
        if (index < 0) {
            break
        }
        val kind = syntaxKinds[index]
        if (kind == YamlSyntaxKind.Tag || kind == YamlSyntaxKind.Anchor ||
            kind == YamlSyntaxKind.ExplicitKey
        ) {
            start = pieces[index].span.startByte
            continue
        }
        if (kind != YamlSyntaxKind.Whitespace || index == 0) {
            break
        }
        val property = index - 1
        if (pieces[property].span.endByte == pieces[index].span.startByte &&
            (syntaxKinds[property] == YamlSyntaxKind.Tag ||
                syntaxKinds[property] == YamlSyntaxKind.Anchor ||
                syntaxKinds[property] == YamlSyntaxKind.ExplicitKey)
        ) {
            start = pieces[property].span.startByte
            continue
        }
        break
    }
    return authority.span(start, span.endByte)
}

private fun Document.prepareCollectionInsertion(
    container: Int,
    spans: List<Span>,
    ordinal: Int,
    flowFragment: String,
    blockLines: List<String>,
    flowStart: YamlSyntaxKind,
    flowEnd: YamlSyntaxKind,
): Pair<Span, ByteArray> {
    val insertion = collectionInsertionPoint(container, spans, ordinal, flowStart, flowEnd)
    return prepareCollectionInsertionAt(
        container,
        spans,
        ordinal,
        flowFragment,
        blockLines,
        flowStart,
        flowEnd,
        insertion,
    )
}

private fun Document.collectionInsertionPoint(
    container: Int,
    spans: List<Span>,
    ordinal: Int,
    flowStart: YamlSyntaxKind,
    flowEnd: YamlSyntaxKind,
): Int {
    if (ordinal > spans.size) {
        throw EditFailureException(EditFailure.InvalidPlacement)
    }
    if (collectionIsFlow(container, flowStart)) {
        val span = spans.getOrNull(ordinal)
            ?: spans.lastOrNull()
            ?: syntaxWithin(native.nodes[container].span, flowEnd, true)
                ?.startByte
                ?: throw EditFailureException(EditFailure.IncompleteTarget)
        return if (spans.getOrNull(ordinal) != null) {
            span.startByte
        } else if (spans.isNotEmpty()) {
            span.endByte
        } else {
            span
        }
    }
    val span = spans.getOrNull(ordinal) ?: spans.lastOrNull()
        ?: throw EditFailureException(EditFailure.IncompleteTarget)
    return if (spans.getOrNull(ordinal) != null) {
        blockOwnedSpan(span).startByte
    } else {
        blockOwnedSpan(span).endByte
    }
}

private fun Document.prepareCollectionInsertionAt(
    container: Int,
    spans: List<Span>,
    ordinal: Int,
    flowFragment: String,
    blockLines: List<String>,
    flowStart: YamlSyntaxKind,
    flowEnd: YamlSyntaxKind,
    insertion: Int,
): Pair<Span, ByteArray> {
    val span = authority.span(insertion, insertion)
    if (collectionIsFlow(container, flowStart)) {
        val text = when {
            spans.isEmpty() -> flowFragment
            ordinal < spans.size -> "$flowFragment, "
            else -> ", $flowFragment"
        }
        return span to encodeFragment(text)
    }
    val reference = spans.getOrNull(ordinal) ?: spans.last()
    val owned = blockOwnedSpan(reference)
    val indent = lineIndent(owned.startByte)
    val newline = nearestNewline(insertion)
    val suffixNewline = ordinal < spans.size ||
        rawDecoded(owned.startByte, owned.endByte).endsWith("\n") ||
        rawDecoded(owned.startByte, owned.endByte).endsWith("\r")
    val text = StringBuilder()
    if (ordinal == spans.size && !suffixNewline) {
        text.append(newline)
    }
    for ((index, line) in blockLines.withIndex()) {
        text.append(indent)
        text.append(line)
        if (index + 1 < blockLines.size || suffixNewline) {
            text.append(newline)
        }
    }
    return span to encodeFragment(text.toString())
}

private fun Document.collectionRemovalSpan(
    container: Int,
    spans: List<Span>,
    ordinal: Int,
    flowStart: YamlSyntaxKind,
    flowEnd: YamlSyntaxKind,
): Span {
    val target = spans.getOrNull(ordinal)
        ?: throw EditFailureException(EditFailure.TargetNotFound)
    if (!collectionIsFlow(container, flowStart)) {
        return blockOwnedSpan(target)
    }
    if (spans.size == 1) {
        return target
    }
    if (ordinal + 1 < spans.size) {
        syntaxBetween(
            YamlSyntaxKind.FlowEntry,
            target.endByte,
            spans[ordinal + 1].startByte,
            false,
        ) ?: throw EditFailureException(EditFailure.IncompleteTarget)
        return authority.span(target.startByte, spans[ordinal + 1].startByte)
    }
    val comma = syntaxBetween(
        YamlSyntaxKind.FlowEntry,
        spans[ordinal - 1].endByte,
        target.startByte,
        true,
    ) ?: throw EditFailureException(EditFailure.IncompleteTarget)
    return authority.span(comma.startByte, target.endByte)
}

private fun Document.collectionIsFlow(container: Int, flowStart: YamlSyntaxKind): Boolean {
    val node = native.nodes[container]
    val pieces = structuralIndex.pieces()
    for ((index, piece) in pieces.withIndex()) {
        if (piece.span.startByte < node.span.startByte || piece.span.endByte > node.span.endByte) {
            continue
        }
        val kind = syntaxKinds[index]
        if (kind == YamlSyntaxKind.Whitespace || kind == YamlSyntaxKind.Newline ||
            kind == YamlSyntaxKind.Comment || kind == YamlSyntaxKind.Tag ||
            kind == YamlSyntaxKind.Anchor
        ) {
            continue
        }
        return kind == flowStart
    }
    return false
}

private fun Document.blockOwnedSpan(occurrence: Span): Span {
    val start = lineStart(occurrence.startByte)
    val lineStartOfEnd = lineStart(occurrence.endByte)
    val end = if (lineStartOfEnd == occurrence.endByte && occurrence.endByte > start) {
        occurrence.endByte
    } else {
        lineEnd(occurrence.endByte)
    }
    return authority.span(start, end)
}

private fun Document.lineStart(raw: Int): Int {
    val position = source.decodedPosition(raw)
    val text = source.decodedText() ?: throw EditFailureException(EditFailure.IncompleteTarget)
    val prefix = text.substring(0, position.decodedUtf8Byte)
    val start = prefix.lastIndexOfAny(charArrayOf('\r', '\n')) + 1
    return source.rawByteAt(consema.document.DecodedOffset.Utf8Byte(start))
}

private fun Document.lineEnd(raw: Int): Int {
    val position = source.decodedPosition(raw)
    val text = source.decodedText() ?: throw EditFailureException(EditFailure.IncompleteTarget)
    val suffix = text.substring(position.decodedUtf8Byte)
    val found = suffix.indexOfFirst { it == '\r' || it == '\n' }
    var end = if (found < 0) text.length else position.decodedUtf8Byte + found
    if (end < text.length) {
        if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') {
            end += 2
        } else {
            end += 1
        }
    }
    return source.rawByteAt(consema.document.DecodedOffset.Utf8Byte(end))
}

private fun Document.lineIndent(rawLineStart: Int): String {
    val end = lineEnd(rawLineStart)
    return rawDecoded(rawLineStart, end).takeWhile { it == ' ' }
}

private fun Document.rawDecoded(start: Int, end: Int): String {
    val startPosition = source.decodedPosition(start)
    val endPosition = source.decodedPosition(end)
    val text = source.decodedText() ?: throw EditFailureException(EditFailure.IncompleteTarget)
    return text.substring(startPosition.decodedUtf8Byte, endPosition.decodedUtf8Byte)
}

private fun Document.nearestNewline(raw: Int): String {
    val pieces = structuralIndex.pieces()
    var best: Pair<Span, String>? = null
    for ((index, piece) in pieces.withIndex()) {
        if (syntaxKinds[index] != YamlSyntaxKind.Newline) {
            continue
        }
        val distance = kotlin.math.abs(piece.span.startByte - raw)
        val current = best
        if (current == null || distance < kotlin.math.abs(current.first.startByte - raw)) {
            best = piece.span to rawDecoded(piece.span.startByte, piece.span.endByte)
        }
    }
    return best?.second ?: "\n"
}

private fun Document.emptyBlockReplacement(owned: Span, occurrence: Span, empty: String): ByteArray {
    val indent = lineIndent(owned.startByte)
    val whole = rawDecoded(owned.startByte, owned.endByte)
    val tail = when {
        occurrence.endByte < owned.endByte -> rawDecoded(occurrence.endByte, owned.endByte)
        whole.endsWith("\r\n") -> "\r\n"
        whole.endsWith("\n") -> "\n"
        whole.endsWith("\r") -> "\r"
        else -> ""
    }
    return encodeFragment("$indent$empty$tail")
}

/** Inserting an alias requires the exact latest visible definition of its
 * name (edit.rs:1346-1396). */
private fun Document.validateVisibleAnchor(sequence: Int, anchor: Int, insertion: Int) {
    val anchorSpan = native.nodes[anchor].anchorSpan
        ?: throw EditFailureException(EditFailure.WrongRole)
    val sequenceSpan = native.nodes[sequence].span
    val document = native.documents.firstOrNull { candidate ->
        candidate.span.startByte <= sequenceSpan.startByte &&
            sequenceSpan.endByte <= candidate.span.endByte
    } ?: throw EditFailureException(EditFailure.AnchorNotVisible)
    if (anchorSpan.endByte > insertion ||
        anchorSpan.startByte < document.span.startByte ||
        anchorSpan.endByte > document.span.endByte
    ) {
        throw EditFailureException(EditFailure.AnchorNotVisible)
    }
    val name = native.nodes[anchor].anchor
        ?: throw EditFailureException(EditFailure.WrongRole)
    var visible: Int? = null
    var visibleEnd = -1
    for ((index, node) in native.nodes.withIndex()) {
        val span = node.anchorSpan ?: continue
        if (node.anchor == name &&
            span.startByte >= document.span.startByte &&
            span.endByte <= insertion
        ) {
            if (span.endByte > visibleEnd) {
                visibleEnd = span.endByte
                visible = index
            }
        }
    }
    if (visible != anchor) {
        throw EditFailureException(EditFailure.AnchorNotVisible)
    }
}

/** Only collect deleted subtrees: removing an anchored definition while a
 * live alias remains outside the owned span is rejected (edit.rs:
 * 1398-1418; RFC 0007 §12). */
private fun Document.validateRemovalDependencies(
    owned: Span,
    roots: List<Pair<Int, Int?>>,
) {
    val removed = HashSet<Int>()
    for ((node, alias) in roots) {
        if (alias == null) {
            collectOwnedNodes(node, removed)
        }
    }
    val conflict = native.aliases.any { alias ->
        removed.contains(alias.target) &&
            !(alias.span.startByte >= owned.startByte && alias.span.endByte <= owned.endByte)
    }
    if (conflict) {
        throw EditFailureException(EditFailure.AnchorDependency)
    }
}

private fun Document.collectOwnedNodes(node: Int, output: MutableSet<Int>) {
    if (!output.add(node)) {
        return
    }
    when (val content = native.nodes[node].content) {
        is NativeContent.Scalar -> {}
        is NativeContent.Sequence -> {
            for (item in content.items) {
                if (item.alias == null) {
                    collectOwnedNodes(item.node, output)
                }
            }
        }
        is NativeContent.Mapping -> {
            for (entry in content.entries) {
                if (entry.keyAlias == null) {
                    collectOwnedNodes(entry.key, output)
                }
                if (entry.valueAlias == null) {
                    collectOwnedNodes(entry.value, output)
                }
            }
        }
    }
}

private fun Document.resolveNode(target: NodeRef, role: NodeRole): Int {
    if (target.snapshot != snapshotIdentity) {
        throw EditFailureException(EditFailure.WrongSnapshot)
    }
    if (target.role != role) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    val index = target.index
    if (index < 0 || index >= native.nodes.size.toLong()) {
        throw EditFailureException(EditFailure.TargetNotFound)
    }
    val node = native.nodes[index.toInt()]
    if (role == NodeRole.YamlAnchorDefinition && node.anchor == null) {
        throw EditFailureException(EditFailure.WrongRole)
    }
    return index.toInt()
}

/** The exact literal span of a scalar node (edit.rs:1468-1497). */
private fun Document.scalarLiteralSpan(index: Int): Span? {
    val node = native.nodes.getOrNull(index) ?: return null
    val scalar = (node.content as? NativeContent.Scalar)?.scalar ?: return null
    val expected = when (scalar.style) {
        YamlScalarStyle.Plain -> YamlSyntaxKind.PlainScalar
        YamlScalarStyle.SingleQuoted -> YamlSyntaxKind.SingleQuotedScalar
        YamlScalarStyle.DoubleQuoted -> YamlSyntaxKind.DoubleQuotedScalar
        YamlScalarStyle.Literal -> YamlSyntaxKind.LiteralBlockHeader
        YamlScalarStyle.Folded -> YamlSyntaxKind.FoldedBlockHeader
    }
    val header = syntaxWithin(node.span, expected, false) ?: return null
    if (scalar.style == YamlScalarStyle.Literal || scalar.style == YamlScalarStyle.Folded) {
        val end = syntaxBetween(
            YamlSyntaxKind.BlockScalarContent,
            header.endByte,
            node.span.endByte,
            true,
        )?.endByte ?: header.endByte
        return authority.span(header.startByte, end)
    }
    return header
}

private fun Document.tagSpan(index: Int): Span? =
    native.nodes.getOrNull(index)?.let { syntaxWithin(it.span, YamlSyntaxKind.Tag, false) }

private fun Document.syntaxWithin(span: Span, kind: YamlSyntaxKind, last: Boolean): Span? =
    syntaxBetween(kind, span.startByte, span.endByte, last)

private fun Document.syntaxBetween(
    kind: YamlSyntaxKind,
    start: Int,
    end: Int,
    last: Boolean,
): Span? {
    val pieces = structuralIndex.pieces()
    val matches = ArrayList<Span>()
    for ((index, piece) in pieces.withIndex()) {
        if (syntaxKinds[index] == kind &&
            piece.span.startByte >= start &&
            piece.span.endByte <= end
        ) {
            matches.add(piece.span)
        }
    }
    return if (last) matches.lastOrNull() else matches.firstOrNull()
}

/** An exact literal must be one complete scalar with no node properties or
 * markers (edit.rs:1536-1567). */
private fun Document.validateLiteral(literal: ByteArray) {
    if (literal.isEmpty()) {
        throw EditFailureException(EditFailure.InvalidLiteral)
    }
    val source = try {
        standaloneSource(literal, source.encodingFacts.selected)
    } catch (e: EditFailureException) {
        throw e
    }
    val candidate = try {
        parse(source, profile, parseLimits)
    } catch (e: YamlFormationException) {
        throw EditFailureException(EditFailure.InvalidLiteral)
    }
    val root = candidate.document(0)
        ?.takeIf { candidate.documentCount() == 1 }
        ?.root()
        ?.takeIf { it.kind() == YamlNodeKind.Scalar }
        ?: throw EditFailureException(EditFailure.InvalidLiteral)
    if (root.anchor() != null ||
        candidate.losslessSyntaxKinds().any {
            it == YamlSyntaxKind.Tag || it == YamlSyntaxKind.Anchor ||
                it == YamlSyntaxKind.Alias || it == YamlSyntaxKind.Directive ||
                it == YamlSyntaxKind.DocumentStart || it == YamlSyntaxKind.DocumentEnd ||
                it == YamlSyntaxKind.Comment || it == YamlSyntaxKind.ErrorRegion
        }
    ) {
        throw EditFailureException(EditFailure.InvalidLiteral)
    }
}

/** The canonical tag/literal fragment of one scalar value (edit.rs:
 * 1569-1614). The canonical content is read from the materialized document
 * itself, exactly like the reference implementation. */
private fun Document.canonicalScalarFragment(value: PortableValue): CanonicalScalar {
    val request = MaterializationRequest.new(
        profileId(),
        MaterializationStyleId("yaml.canonical-flow", 1),
    ).withLimits(editMaterializationLimits(parseLimits))
    val complete = when (val result = materializeValue(value, request)) {
        is MaterializationResult.Complete -> result.materialization
        is MaterializationResult.Failed -> {
            val failure = result.attempt.failure
            throw EditFailureException(
                when (failure.kind) {
                    MaterializationFailureKind.UNREPRESENTABLE ->
                        EditFailure.UnsupportedSemanticValue(failure.valueKind ?: Kind.Null)
                    MaterializationFailureKind.RESOURCE_LIMIT ->
                        EditFailure.ResourceLimit(failure.name)
                    else -> EditFailure.NewDocumentFormationFailed
                },
            )
        }
    }
    val text = complete.document.source.decodedText()
        ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    if (!text.startsWith("--- ") || !text.endsWith("\n")) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    val fragment = text.substring(4, text.length - 1)
    val parts = fragment.split(' ', limit = 2)
    if (parts.size != 2) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    val scalar = complete.document.document(0)?.root()?.scalar()
        ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    return CanonicalScalar(parts[0], parts[1], scalar.canonical())
}

/** The canonical flow fragment of one inserted value (edit.rs:1616-1644). */
private fun Document.canonicalValueFragment(value: PortableValue): String =
    materializeScalarFragment(value) {
        EditFailure.UnsupportedInsertedValue(it)
    }

private fun Document.materializeScalarFragment(
    value: PortableValue,
    unsupported: (Kind) -> EditFailure,
): String {
    val request = MaterializationRequest.new(
        profileId(),
        MaterializationStyleId("yaml.canonical-flow", 1),
    ).withLimits(editMaterializationLimits(parseLimits))
    val complete = when (val result = materializeValue(value, request)) {
        is MaterializationResult.Complete -> result.materialization
        is MaterializationResult.Failed -> {
            val failure = result.attempt.failure
            throw EditFailureException(
                when (failure.kind) {
                    MaterializationFailureKind.UNREPRESENTABLE ->
                        unsupported(failure.valueKind ?: Kind.Null)
                    MaterializationFailureKind.RESOURCE_LIMIT ->
                        EditFailure.ResourceLimit(failure.name)
                    else -> EditFailure.NewDocumentFormationFailed
                },
            )
        }
    }
    val text = complete.document.source.decodedText()
        ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    if (!text.startsWith("--- ") || !text.endsWith("\n")) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    return text.substring(4, text.length - 1)
}

private fun Document.editParseLimits(): ParseLimits =
    ParseLimits(
        maxSourceBytes = parseLimits.maxSourceBytes,
        maxNestingDepth = 2,
        maxTokenCount = 32,
        maxNodeCount = 8,
        maxDiagnostics = parseLimits.maxDiagnostics,
    )

/** A new anchor name must form one exact anchor property (edit.rs:
 * 1646-1672). */
private fun Document.validateAnchorName(name: String) {
    if (name.isEmpty() || name.length > parseLimits.maxSourceBytes) {
        throw EditFailureException(EditFailure.InvalidAnchorName)
    }
    val source = "--- &$name !!str \"x\"\n"
    val candidate = try {
        parse(
            source.toByteArray(Charsets.UTF_8),
            profile,
            editParseLimits(),
        )
    } catch (e: YamlFormationException) {
        throw EditFailureException(EditFailure.InvalidAnchorName)
    }
    if (candidate.document(0)?.root()?.anchor() != name) {
        throw EditFailureException(EditFailure.InvalidAnchorName)
    }
}

private fun Document.encodeFragment(text: String): ByteArray =
    encodeFragment(text, source.encodingFacts.selected, parseLimits.maxSourceBytes)

private fun encodeFragment(text: String, encoding: SourceEncoding, max: Int): ByteArray =
    when (encoding) {
        SourceEncoding.Utf8 -> {
            if (text.length > max) {
                throw EditFailureException(EditFailure.ResourceLimit("replacement-bytes"))
            }
            text.toByteArray(Charsets.UTF_8)
        }
        SourceEncoding.Utf16Le, SourceEncoding.Utf16Be -> {
            val length = text.length.saturatingMul(2)
            if (length > max) {
                throw EditFailureException(EditFailure.ResourceLimit("replacement-bytes"))
            }
            val output = ByteArray(length)
            for (index in text.indices) {
                val value = text[index].code
                if (encoding == SourceEncoding.Utf16Le) {
                    output[index * 2] = (value and 0xff).toByte()
                    output[index * 2 + 1] = (value shr 8).toByte()
                } else {
                    output[index * 2] = (value shr 8).toByte()
                    output[index * 2 + 1] = (value and 0xff).toByte()
                }
            }
            output
        }
        else -> throw EditFailureException(EditFailure.InvalidLiteral)
    }

private fun standaloneSource(fragment: ByteArray, encoding: SourceEncoding): ByteArray =
    when (encoding) {
        SourceEncoding.Utf8 -> fragment
        SourceEncoding.Utf16Le -> byteArrayOf(0xff.toByte(), 0xfe.toByte()) + fragment
        SourceEncoding.Utf16Be -> byteArrayOf(0xfe.toByte(), 0xff.toByte()) + fragment
        else -> throw EditFailureException(EditFailure.InvalidLiteral)
    }

/** Validates the reparsed candidate against the declared operations
 * (edit.rs:1682-2014). */
private fun Document.validateCandidate(
    candidate: Document,
    transaction: EditTransaction,
): CandidateMap {
    if (transaction.operations.any { isStructuralOperation(it) }) {
        return validateStructuralCandidate(candidate, transaction)
    }
    val scalarTargets = HashSet<Int>()
    val renames = HashMap<Int, String>()
    for (operation in transaction.operations) {
        when (operation) {
            is EditOperation.ReplaceScalar ->
                scalarTargets.add(resolveNode(operation.replacement.target, NodeRole.YamlNode))
            is EditOperation.RenameAnchor ->
                renames[resolveNode(operation.target, NodeRole.YamlAnchorDefinition)] = operation.name
            else -> error("structural transactions use structural validation")
        }
    }
    if (native.documents.size != candidate.native.documents.size ||
        native.nodes.size != candidate.native.nodes.size ||
        native.aliases.size != candidate.native.aliases.size
    ) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    for ((old, new) in native.documents.zip(candidate.native.documents)) {
        if (old.root != new.root) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
    }
    for ((index, pair) in native.nodes.zip(candidate.native.nodes).withIndex()) {
        val (old, new) = pair
        val expectedAnchor = renames[index] ?: old.anchor
        if (new.anchor != expectedAnchor ||
            !sameTopology(old.content, new.content) ||
            (!scalarTargets.contains(index) &&
                (old.tag != new.tag || !sameScalarSemantics(old.content, new.content)))
        ) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
    }
    for ((old, new) in native.aliases.zip(candidate.native.aliases)) {
        val expectedName = renames[old.target] ?: old.name
        if (old.target != new.target || new.name != expectedName) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
    }
    val map = CandidateMap()
    for (index in native.nodes.indices) {
        map.nodes[index] = index
    }
    for (index in native.aliases.indices) {
        map.aliases[index] = index
    }
    return map
}

private fun Document.validateStructuralCandidate(
    candidate: Document,
    transaction: EditTransaction,
): CandidateMap {
    if (native.documents.size != candidate.native.documents.size) {
        throw EditFailureException(EditFailure.NewDocumentFormationFailed)
    }
    val expected = ValidationModel.fromDocument(this, retainSource = true)
    for (operation in transaction.operations) {
        when (operation) {
            is EditOperation.ReplaceScalar -> {
                when (val replacement = operation.replacement) {
                    is ScalarReplacement.Semantic -> {
                        val target = resolveNode(replacement.target, NodeRole.YamlNode)
                        if (expected.nodes[target].content !is ValidationContent.Scalar) {
                            throw EditFailureException(EditFailure.WrongRole)
                        }
                        val imported = expected.appendRoot(validationModelForValue(replacement.value))
                        val replacementNode = expected.nodes[imported]
                        expected.nodes[target].tag = replacementNode.tag
                        expected.nodes[target].content = replacementNode.content
                        expected.nodes[target].scalarWildcard = false
                    }
                    is ScalarReplacement.Literal -> {
                        val target = resolveNode(replacement.target, NodeRole.YamlNode)
                        if (expected.nodes[target].content !is ValidationContent.Scalar) {
                            throw EditFailureException(EditFailure.WrongRole)
                        }
                        expected.nodes[target].scalarWildcard = true
                    }
                }
            }
            is EditOperation.RenameAnchor -> {
                val target = resolveNode(operation.target, NodeRole.YamlAnchorDefinition)
                val oldName = expected.nodes[target].anchor
                    ?: throw EditFailureException(EditFailure.WrongRole)
                expected.nodes[target].anchor = operation.name
                for (node in expected.nodes) {
                    when (val content = node.content) {
                        is ValidationContent.Scalar -> {}
                        is ValidationContent.Sequence -> {
                            for (edge in content.items) {
                                if (edge.target == target && edge.alias?.name == oldName) {
                                    edge.alias = ValidationAlias(operation.name, edge.alias?.sourceAlias)
                                }
                            }
                        }
                        is ValidationContent.Mapping -> {
                            for (entry in content.entries) {
                                for (edge in listOf(entry.key, entry.value)) {
                                    if (edge.target == target && edge.alias?.name == oldName) {
                                        edge.alias = ValidationAlias(operation.name, edge.alias?.sourceAlias)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            is EditOperation.InsertMappingEntry -> {
                val container = resolveNode(operation.mapping, NodeRole.YamlNode)
                val base = (native.nodes[container].content as? NativeContent.Mapping)?.entries
                    ?: throw EditFailureException(EditFailure.WrongRole)
                val ordinal = mappingPlacement(container, base, operation.placement)
                val key = expected.appendRoot(validationModelForValue(operation.key))
                val value = expected.appendRoot(validationModelForValue(operation.value))
                val entries = (expected.nodes[container].content as? ValidationContent.Mapping)
                    ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
                entries.entries.add(
                    ordinal,
                    ValidationMappingEntry(
                        ValidationEdge(key, null),
                        ValidationEdge(value, null),
                    ),
                )
            }
            is EditOperation.RemoveMappingEntry -> {
                val (container, ordinal) = resolveMappingEntry(operation.target)
                val entries = (expected.nodes[container].content as? ValidationContent.Mapping)
                    ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
                entries.entries.removeAt(ordinal)
            }
            is EditOperation.InsertSequenceElement -> {
                val container = resolveNode(operation.sequence, NodeRole.YamlNode)
                val base = (native.nodes[container].content as? NativeContent.Sequence)?.items
                    ?: throw EditFailureException(EditFailure.WrongRole)
                val ordinal = sequencePlacement(container, base, operation.placement)
                val target = expected.appendRoot(validationModelForValue(operation.value))
                val items = (expected.nodes[container].content as? ValidationContent.Sequence)
                    ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
                items.items.add(ordinal, ValidationEdge(target, null))
            }
            is EditOperation.RemoveSequenceElement -> {
                val (container, ordinal) = resolveSequenceItem(operation.target)
                val items = (expected.nodes[container].content as? ValidationContent.Sequence)
                    ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
                items.items.removeAt(ordinal)
            }
            is EditOperation.InsertAlias -> {
                val container = resolveNode(operation.sequence, NodeRole.YamlNode)
                val target = resolveNode(operation.anchor, NodeRole.YamlAnchorDefinition)
                val base = (native.nodes[container].content as? NativeContent.Sequence)?.items
                    ?: throw EditFailureException(EditFailure.WrongRole)
                val ordinal = sequencePlacement(container, base, operation.placement)
                val name = native.nodes[target].anchor
                    ?: throw EditFailureException(EditFailure.WrongRole)
                val items = (expected.nodes[container].content as? ValidationContent.Sequence)
                    ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
                items.items.add(
                    ordinal,
                    ValidationEdge(target, ValidationAlias(name, null)),
                )
            }
        }
    }
    return expected.compare(ValidationModel.fromDocument(candidate, retainSource = true))
}

private fun Document.validationModelForValue(value: PortableValue): ValidationModel {
    val request = MaterializationRequest.new(
        profileId(),
        MaterializationStyleId("yaml.canonical-flow", 1),
    ).withLimits(editMaterializationLimits(parseLimits))
    val complete = when (val result = materializeValue(value, request)) {
        is MaterializationResult.Complete -> result.materialization
        is MaterializationResult.Failed -> {
            val failure = result.attempt.failure
            throw EditFailureException(
                when (failure.kind) {
                    MaterializationFailureKind.UNREPRESENTABLE ->
                        EditFailure.UnsupportedInsertedValue(failure.valueKind ?: Kind.Null)
                    MaterializationFailureKind.RESOURCE_LIMIT ->
                        EditFailure.ResourceLimit(failure.name)
                    else -> EditFailure.NewDocumentFormationFailed
                },
            )
        }
    }
    return ValidationModel.fromDocument(complete.document, retainSource = false)
}

/** Duplicate targets and same-container structural conflicts are rejected
 * before any byte planning (edit.rs:1974-2014). */
private fun Document.validateDependencies(transaction: EditTransaction) {
    val targets = HashSet<NodeRef>()
    val structuralContainers = HashSet<Int>()
    for (operation in transaction.operations) {
        val target = when (operation) {
            is EditOperation.ReplaceScalar -> operation.replacement.target
            is EditOperation.RenameAnchor -> operation.target
            is EditOperation.RemoveMappingEntry -> operation.target
            is EditOperation.RemoveSequenceElement -> operation.target
            is EditOperation.InsertMappingEntry -> operation.mapping
            is EditOperation.InsertSequenceElement -> operation.sequence
            is EditOperation.InsertAlias -> operation.sequence
        }
        if (!targets.add(target)) {
            throw EditFailureException(EditFailure.DuplicateTarget)
        }
        val structuralContainer = when (operation) {
            is EditOperation.InsertMappingEntry -> resolveNode(operation.mapping, NodeRole.YamlNode)
            is EditOperation.RemoveMappingEntry -> resolveMappingEntry(operation.target).first
            is EditOperation.InsertSequenceElement ->
                resolveNode(operation.sequence, NodeRole.YamlNode)
            is EditOperation.InsertAlias -> resolveNode(operation.sequence, NodeRole.YamlNode)
            is EditOperation.RemoveSequenceElement -> resolveSequenceItem(operation.target).first
            is EditOperation.ReplaceScalar, is EditOperation.RenameAnchor -> null
        }
        if (structuralContainer != null && !structuralContainers.add(structuralContainer)) {
            throw EditFailureException(EditFailure.StructuralContainerConflict)
        }
    }
}

/** The validation model used for structural candidate isomorphism
 * (edit.rs:2017-2324). */
private class ValidationModel(
    val roots: List<Int>,
    val nodes: MutableList<ValidationNode>,
) {
    companion object {
        fun fromDocument(document: Document, retainSource: Boolean): ValidationModel {
            val nodes = document.native.nodes.mapIndexed { index, node ->
                val content = when (val nativeContent = node.content) {
                    is NativeContent.Scalar -> ValidationContent.Scalar(
                        nativeContent.scalar.kind,
                        nativeContent.scalar.canonical,
                    )
                    is NativeContent.Sequence -> ValidationContent.Sequence(
                        nativeContent.items.map { item ->
                            ValidationEdge(
                                item.node,
                                item.alias?.let { ordinal ->
                                    ValidationAlias(
                                        document.native.aliases[ordinal].name,
                                        if (retainSource) ordinal else null,
                                    )
                                },
                            )
                        }.toMutableList(),
                    )
                    is NativeContent.Mapping -> ValidationContent.Mapping(
                        nativeContent.entries.map { entry ->
                            ValidationMappingEntry(
                                ValidationEdge(
                                    entry.key,
                                    entry.keyAlias?.let { ordinal ->
                                        ValidationAlias(
                                            document.native.aliases[ordinal].name,
                                            if (retainSource) ordinal else null,
                                        )
                                    },
                                ),
                                ValidationEdge(
                                    entry.value,
                                    entry.valueAlias?.let { ordinal ->
                                        ValidationAlias(
                                            document.native.aliases[ordinal].name,
                                            if (retainSource) ordinal else null,
                                        )
                                    },
                                ),
                            )
                        }.toMutableList(),
                    )
                }
                ValidationNode(
                    node.tag,
                    node.anchor,
                    content,
                    if (retainSource) index else null,
                    false,
                )
            }.toMutableList()
            return ValidationModel(
                document.native.documents.map { it.root },
                nodes,
            )
        }
    }

    /** Imports one single-root model into this model's node space
     * (edit.rs:2131-2174). */
    fun appendRoot(imported: ValidationModel): Int {
        if (imported.roots.size != 1) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
        val offset = nodes.size
        for (node in imported.nodes) {
            node.sourceNode = null
            when (val content = node.content) {
                is ValidationContent.Scalar -> {}
                is ValidationContent.Sequence -> {
                    for (item in content.items) {
                        item.target += offset
                        item.alias?.sourceAlias = null
                    }
                }
                is ValidationContent.Mapping -> {
                    for (entry in content.entries) {
                        for (edge in listOf(entry.key, entry.value)) {
                            edge.target += offset
                            edge.alias?.sourceAlias = null
                        }
                    }
                }
            }
        }
        val root = imported.roots[0] + offset
        nodes.addAll(imported.nodes)
        return root
    }

    /** Compares this expected model against a candidate model and returns
     * the old-to-new mapping (edit.rs:2176-2317). */
    fun compare(candidate: ValidationModel): CandidateMap {
        if (roots.size != candidate.roots.size) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
        val state = ValidationComparison()
        for ((expected, actual) in roots.zip(candidate.roots)) {
            compareNode(candidate, expected, actual, state)
        }
        if (state.nodePairs.size != reachableCount() ||
            state.actualNodes.size != candidate.reachableCount()
        ) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
        return state.output
    }

    private fun compareNode(
        candidate: ValidationModel,
        expected: Int,
        actual: Int,
        state: ValidationComparison,
    ) {
        val mapped = state.nodePairs[expected]
        if (mapped != null) {
            if (mapped != actual) {
                throw EditFailureException(EditFailure.NewDocumentFormationFailed)
            }
            return
        }
        if (!state.actualNodes.add(actual)) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
        val expectedNode = nodes.getOrNull(expected)
            ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        val actualNode = candidate.nodes.getOrNull(actual)
            ?: throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        state.nodePairs[expected] = actual
        expectedNode.sourceNode?.let { state.output.nodes[it] = actual }
        if (expectedNode.anchor != actualNode.anchor) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
        if (expectedNode.scalarWildcard) {
            if (expectedNode.content is ValidationContent.Scalar &&
                actualNode.content is ValidationContent.Scalar
            ) {
                return
            }
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
        if (expectedNode.tag != actualNode.tag) {
            throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
        when {
            expectedNode.content is ValidationContent.Scalar &&
                actualNode.content is ValidationContent.Scalar -> {
                val expectedScalar = expectedNode.content as ValidationContent.Scalar
                val actualScalar = actualNode.content as ValidationContent.Scalar
                if (expectedScalar.kind != actualScalar.kind ||
                    expectedScalar.canonical != actualScalar.canonical
                ) {
                    throw EditFailureException(EditFailure.NewDocumentFormationFailed)
                }
            }
            expectedNode.content is ValidationContent.Sequence &&
                actualNode.content is ValidationContent.Sequence -> {
                val expectedItems = (expectedNode.content as ValidationContent.Sequence).items
                val actualItems = (actualNode.content as ValidationContent.Sequence).items
                if (expectedItems.size != actualItems.size) {
                    throw EditFailureException(EditFailure.NewDocumentFormationFailed)
                }
                for ((expectedEdge, actualEdge) in expectedItems.zip(actualItems)) {
                    compareEdge(candidate, expectedEdge, actualEdge, state)
                }
            }
            expectedNode.content is ValidationContent.Mapping &&
                actualNode.content is ValidationContent.Mapping -> {
                val expectedEntries = (expectedNode.content as ValidationContent.Mapping).entries
                val actualEntries = (actualNode.content as ValidationContent.Mapping).entries
                if (expectedEntries.size != actualEntries.size) {
                    throw EditFailureException(EditFailure.NewDocumentFormationFailed)
                }
                for ((expectedEntry, actualEntry) in expectedEntries.zip(actualEntries)) {
                    compareEdge(candidate, expectedEntry.key, actualEntry.key, state)
                    compareEdge(candidate, expectedEntry.value, actualEntry.value, state)
                }
            }
            else -> throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
    }

    private fun compareEdge(
        candidate: ValidationModel,
        expected: ValidationEdge,
        actual: ValidationEdge,
        state: ValidationComparison,
    ) {
        when {
            expected.alias == null && actual.alias == null -> {}
            expected.alias != null && actual.alias != null &&
                expected.alias.name == actual.alias.name -> {
                if (expected.alias.sourceAlias != null && actual.alias.sourceAlias != null) {
                    state.output.aliases[expected.alias.sourceAlias!!] = actual.alias.sourceAlias!!
                }
            }
            else -> throw EditFailureException(EditFailure.NewDocumentFormationFailed)
        }
        compareNode(candidate, expected.target, actual.target, state)
    }

    fun reachableCount(): Int {
        val reached = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        pending.addAll(roots)
        while (pending.isNotEmpty()) {
            val index = pending.removeLast()
            if (!reached.add(index)) {
                continue
            }
            val node = nodes.getOrNull(index) ?: continue
            when (val content = node.content) {
                is ValidationContent.Scalar -> {}
                is ValidationContent.Sequence ->
                    pending.addAll(content.items.map { it.target })
                is ValidationContent.Mapping -> {
                    for (entry in content.entries) {
                        pending.addLast(entry.key.target)
                        pending.addLast(entry.value.target)
                    }
                }
            }
        }
        return reached.size
    }
}

private class ValidationNode(
    var tag: String,
    var anchor: String?,
    var content: ValidationContent,
    var sourceNode: Int?,
    var scalarWildcard: Boolean,
)

private sealed class ValidationContent {
    data class Scalar(val kind: YamlScalarKind, val canonical: String) : ValidationContent()
    data class Sequence(val items: MutableList<ValidationEdge>) : ValidationContent()
    data class Mapping(val entries: MutableList<ValidationMappingEntry>) : ValidationContent()
}

private data class ValidationEdge(
    var target: Int,
    var alias: ValidationAlias?,
)

private data class ValidationAlias(
    var name: String,
    var sourceAlias: Int?,
)

private data class ValidationMappingEntry(
    val key: ValidationEdge,
    val value: ValidationEdge,
)

private class ValidationComparison {
    val nodePairs = HashMap<Int, Int>()
    val actualNodes = HashSet<Int>()
    val output = CandidateMap()
}

private class CanonicalScalar(val tag: String, val literal: String, val canonical: String)

/** Preserves the target scalar category and presentation style when
 * compatible (edit.rs:2326-2362). */
private fun preservedLiteral(
    oldKind: YamlScalarKind,
    oldStyle: YamlScalarStyle,
    oldTag: String,
    explicitTag: Boolean,
    canonical: CanonicalScalar,
    valueKind: Kind,
    profile: YamlProfile,
): String? {
    if (oldKind != yamlKind(valueKind) || oldTag != shorthandTagUri(canonical.tag)) {
        return null
    }
    val decoded = decodeCanonicalLiteral(canonical.literal) ?: return null
    return when (oldStyle) {
        YamlScalarStyle.DoubleQuoted -> canonical.literal
        YamlScalarStyle.SingleQuoted -> {
            if (decoded.contains('\n') || decoded.contains('\r')) {
                null
            } else {
                "'${decoded.replace("'", "''")}'"
            }
        }
        YamlScalarStyle.Plain -> {
            val source = if (explicitTag) {
                "${canonical.tag} $decoded"
            } else {
                decoded
            }
            val candidate = try {
                parse(source.toByteArray(Charsets.UTF_8), profile, ParseLimits.default)
            } catch (e: YamlFormationException) {
                return null
            }
            val scalar = candidate.document(0)?.root()?.scalar() ?: return null
            if (scalar.kind() == oldKind && scalar.canonical() == canonical.canonical) {
                decoded
            } else {
                null
            }
        }
        YamlScalarStyle.SingleQuoted, YamlScalarStyle.Literal, YamlScalarStyle.Folded -> null
    }
}

private fun shorthandTagUri(tag: String): String? =
    when (tag) {
        "!!null" -> TAG_NULL
        "!!bool" -> TAG_BOOL
        "!!int" -> TAG_INT
        "!!float" -> TAG_FLOAT
        "!!str" -> TAG_STR
        "!!timestamp" -> TAG_TIMESTAMP
        "!!binary" -> TAG_BINARY
        else -> null
    }

private fun decodeCanonicalLiteral(literal: String): String? {
    val candidate = try {
        parse(literal.toByteArray(Charsets.UTF_8), YamlProfile.Yaml12CoreV1, ParseLimits.default)
    } catch (e: YamlFormationException) {
        return null
    }
    return candidate.document(0)?.root()?.scalar()?.decoded()
}

private fun yamlKind(kind: Kind): YamlScalarKind =
    when (kind) {
        Kind.Null -> YamlScalarKind.Null
        Kind.Boolean -> YamlScalarKind.Boolean
        Kind.Integer -> YamlScalarKind.Integer
        Kind.Decimal, Kind.BinaryFloat64 -> YamlScalarKind.Float
        Kind.String -> YamlScalarKind.String
        Kind.Bytes -> YamlScalarKind.Binary
        Kind.Date, Kind.OffsetDateTime -> YamlScalarKind.Timestamp
        else -> YamlScalarKind.Custom
    }

private fun isScalarValue(kind: Kind): Boolean =
    kind != Kind.Sequence && kind != Kind.Object && kind != Kind.EntryMapping

private fun sameTopology(old: NativeContent, new: NativeContent): Boolean =
    when {
        old is NativeContent.Scalar && new is NativeContent.Scalar -> true
        old is NativeContent.Sequence && new is NativeContent.Sequence ->
            old.items.size == new.items.size &&
                old.items.zip(new.items).all { (left, right) ->
                    left.node == right.node && (left.alias != null) == (right.alias != null)
                }
        old is NativeContent.Mapping && new is NativeContent.Mapping ->
            old.entries.size == new.entries.size &&
                old.entries.zip(new.entries).all { (left, right) ->
                    left.key == right.key && left.value == right.value &&
                        (left.keyAlias != null) == (right.keyAlias != null) &&
                        (left.valueAlias != null) == (right.valueAlias != null)
                }
        else -> false
    }

private fun sameScalarSemantics(old: NativeContent, new: NativeContent): Boolean =
    when {
        old is NativeContent.Scalar && new is NativeContent.Scalar ->
            old.scalar.canonical == new.scalar.canonical && old.scalar.kind == new.scalar.kind
        else -> true
    }

private fun isStructuralOperation(operation: EditOperation): Boolean =
    operation is EditOperation.InsertMappingEntry ||
        operation is EditOperation.RemoveMappingEntry ||
        operation is EditOperation.InsertSequenceElement ||
        operation is EditOperation.RemoveSequenceElement ||
        operation is EditOperation.InsertAlias

private fun validatePreparedOwnership(prepared: List<PreparedEdit>) {
    for (index in 0 until prepared.size - 1) {
        val first = prepared[index]
        val second = prepared[index + 1]
        if (!first.oldSpan.isEmpty && !second.oldSpan.isEmpty &&
            first.oldSpan.endByte > second.oldSpan.startByte
        ) {
            throw EditFailureException(EditFailure.AncestorDescendantConflict)
        }
        if (first.oldSpan == second.oldSpan) {
            throw EditFailureException(EditFailure.OverlappingOwnership)
        }
    }
}

/** Reports the authorized canonical fallback of a PreserveElseCanonical
 * scalar edit (edit.rs:2469-2489). */
private fun Document.pushFallbackDiagnostic(diagnostics: MutableList<Diagnostic>, span: Span) {
    val occurrence = diagnostics.size.toULong()
    diagnostics.add(
        sourceDiagnostic(
            authority,
            "yaml.edit.canonical-fallback@1",
            DiagnosticCategory.Edit,
            Severity.Info,
            span.startByte,
            span.endByte,
            occurrence,
        ),
    )
}

private fun editMaterializationLimits(limits: ParseLimits): MaterializationLimits =
    MaterializationLimits(
        maxInputNodes = limits.maxNodeCount,
        maxOutputBytes = limits.maxSourceBytes,
        maxDepth = limits.maxNestingDepth,
        maxReportEntries = limits.maxDiagnostics,
        maxProvenanceEntries = limits.maxNodeCount.saturatingMul(4),
    )

private fun sourcePatchLimits(
    limits: ParseLimits,
    operationCount: Int,
): SourcePatchLimits =
    SourcePatchLimits(
        source = SourceLimits(
            maxRawBytes = limits.maxSourceBytes,
            maxDecodedUtf8Bytes = limits.maxSourceBytes.saturatingMul(2),
            maxDecodedScalars = limits.maxSourceBytes,
        ),
        maxReplacements = operationCount,
        maxPatchBytes = limits.maxSourceBytes.saturatingMul(2),
    )

private fun operationMetadata(transaction: EditTransaction): Map<String, String> {
    val metadata = HashMap<String, String>()
    for ((index, operation) in transaction.operations.withIndex()) {
        val id = when (operation) {
            is EditOperation.ReplaceScalar ->
                if (operation.replacement is ScalarReplacement.Semantic) {
                    "yaml.edit.replace-scalar-semantic@1"
                } else {
                    "yaml.edit.replace-scalar-literal@1"
                }
            is EditOperation.RenameAnchor -> "yaml.edit.rename-anchor@1"
            is EditOperation.InsertMappingEntry -> "yaml.edit.insert-mapping-entry@1"
            is EditOperation.RemoveMappingEntry -> "yaml.edit.remove-mapping-entry@1"
            is EditOperation.InsertSequenceElement -> "yaml.edit.insert-sequence-element@1"
            is EditOperation.RemoveSequenceElement -> "yaml.edit.remove-sequence-element@1"
            is EditOperation.InsertAlias -> "yaml.edit.insert-alias@1"
        }
        metadata["operation.$index"] = id
    }
    return metadata
}

private fun operationSummaries(transaction: EditTransaction): List<EditOperationSummary> =
    transaction.operations.map { operation ->
        val (id, role, arguments) = when (operation) {
            is EditOperation.ReplaceScalar -> {
                when (val replacement = operation.replacement) {
                    is ScalarReplacement.Semantic -> Triple(
                        "yaml.edit.replace-scalar-semantic",
                        "yaml.scalar@1",
                        mapOf(
                            "policy" to policyName(replacement.policy),
                            "value_kind" to replacement.value.kind.name,
                        ),
                    )
                    is ScalarReplacement.Literal -> Triple(
                        "yaml.edit.replace-scalar-literal",
                        "yaml.scalar@1",
                        mapOf("literal_bytes" to replacement.literal.size.toString()),
                    )
                }
            }
            is EditOperation.RenameAnchor -> Triple(
                "yaml.edit.rename-anchor",
                "yaml.anchor-definition@1",
                mapOf("name_bytes" to operation.name.length.toString()),
            )
            is EditOperation.InsertMappingEntry -> Triple(
                "yaml.edit.insert-mapping-entry",
                "yaml.mapping@1",
                mapOf(
                    "key_kind" to operation.key.kind.name,
                    "value_kind" to operation.value.kind.name,
                    "placement" to placementName(operation.placement),
                ),
            )
            is EditOperation.RemoveMappingEntry -> Triple(
                "yaml.edit.remove-mapping-entry",
                "yaml.mapping-entry@1",
                emptyMap(),
            )
            is EditOperation.InsertSequenceElement -> Triple(
                "yaml.edit.insert-sequence-element",
                "yaml.sequence@1",
                mapOf(
                    "value_kind" to operation.value.kind.name,
                    "placement" to placementName(operation.placement),
                ),
            )
            is EditOperation.RemoveSequenceElement -> Triple(
                "yaml.edit.remove-sequence-element",
                "yaml.sequence-element@1",
                emptyMap(),
            )
            is EditOperation.InsertAlias -> Triple(
                "yaml.edit.insert-alias",
                "yaml.sequence@1",
                mapOf("placement" to placementName(operation.placement)),
            )
        }
        EditOperationSummary.new(
            FormatOperationId(id, 1),
            arguments + ("target_role" to role),
        )
    }

private fun placementName(placement: AssociationPlacement): String =
    when (placement) {
        AssociationPlacement.Start -> "start"
        AssociationPlacement.End -> "end"
        is AssociationPlacement.Before -> "before"
        is AssociationPlacement.After -> "after"
    }

private fun policyName(policy: RepresentationPolicy): String =
    when (policy) {
        RepresentationPolicy.ExactLiteral -> "exact-literal"
        RepresentationPolicy.PreserveCompatible -> "preserve-compatible"
        RepresentationPolicy.CanonicalForProfile -> "canonical-for-profile"
        RepresentationPolicy.PreserveElseCanonical -> "preserve-else-canonical"
    }
